#!/usr/bin/env bash
#
# Per-board scheduling state: how many times it has failed in a row, when it may next
# run, and whether it has been paused.
#
# Sourced by run-pipeline.sh and by the tests. Not executable on its own.
#
# WHY A FILE AND NOT job_sources
# ------------------------------
# job_sources already has consecutive_failures and next_run_at, and reusing them would
# have been less code. It would also have changed what those columns mean: they are the
# Java crawler's schedule, read by JobSourceMapper.findDue, and writing this pipeline's
# back-off into them would make the crawler's own due-list a function of a different
# component's failures. Two schedulers sharing one set of columns is a bug waiting for
# the day both are switched on. This state lives beside the batches instead.
#
# FIXTURE AND LIVE ARE SEPARATE
# -----------------------------
# A fixture run cannot tell you anything about whether a real board is healthy, and a
# live board being backed off must not stop someone testing the pipeline. Different
# subdirectory, no shared counters.

# state_dir <mode> -- where this mode's per-board files live.
state_dir() {
  local mode="$1"
  printf '%s/state/%s' "${AIJOB_STATE_ROOT:-/var/lib/aijob}" "$mode"
}

state_file() {
  local mode="$1" provider="$2" board="$3"
  # Board ids come from a version-controlled allowlist, but they still become a path, so
  # anything that is not clearly safe is replaced rather than trusted.
  local safe="${provider}__${board}"
  safe="$(printf '%s' "$safe" | tr -c 'A-Za-z0-9_.-' '_')"
  printf '%s/%s.json' "$(state_dir "$mode")" "$safe"
}

# now_epoch -- "now" as a unix timestamp.
#
# AIJOB_NOW lets a test move time without waiting six hours for a back-off to expire.
# It accepts an epoch or an RFC 3339 stamp. Unset in production, where it is `date`.
now_epoch() {
  if [[ -n "${AIJOB_NOW:-}" ]]; then
    if [[ "$AIJOB_NOW" =~ ^[0-9]+$ ]]; then
      printf '%s' "$AIJOB_NOW"
    else
      date -u -d "$AIJOB_NOW" +%s
    fi
  else
    date -u +%s
  fi
}

epoch_to_iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }

# state_read <mode> <provider> <board> -- the board's state as JSON, defaults if absent.
state_read() {
  local f
  f="$(state_file "$1" "$2" "$3")"
  if [[ -f "$f" ]] && jq -e . "$f" >/dev/null 2>&1; then
    cat "$f"
  else
    # A missing or unparseable file means "never run": due now, no failures, not paused.
    # Refusing to run on a corrupt state file would let one bad write stop collection
    # permanently, which is worse than starting the count again.
    printf '{"provider":"%s","board":"%s","mode":"%s","consecutive_failures":0,' "$2" "$3" "$1"
    printf '"next_allowed_at":0,"paused":false,"paused_reason":"","last_outcome":"never_run",'
    printf '"last_reason":"","last_run_id":"","last_success_at":""}\n'
  fi
}

state_write() {
  local mode="$1" provider="$2" board="$3" json="$4" f
  f="$(state_file "$mode" "$provider" "$board")"
  mkdir -p "$(dirname "$f")"
  # Write-then-rename: a reader never sees half a file, and a crash mid-write leaves the
  # previous state rather than an unparseable one.
  local tmp="${f}.partial.$$"
  printf '%s\n' "$json" > "$tmp" && mv -f "$tmp" "$f"
}

# state_is_due <mode> <provider> <board> -- 0 due, 1 not due, 2 paused.
# Prints a human reason on stdout.
state_is_due() {
  local mode="$1" provider="$2" board="$3" s now next paused reason fails
  s="$(state_read "$mode" "$provider" "$board")"
  paused="$(jq -r '.paused // false' <<<"$s")"
  if [[ "$paused" == "true" ]]; then
    reason="$(jq -r '.paused_reason // ""' <<<"$s")"
    echo "paused: $reason"
    return 2
  fi
  next="$(jq -r '.next_allowed_at // 0' <<<"$s")"
  now="$(now_epoch)"
  if (( now < next )); then
    fails="$(jq -r '.consecutive_failures // 0' <<<"$s")"
    echo "not due until $(epoch_to_iso "$next") (after $fails consecutive failures)"
    return 1
  fi
  echo "due"
  return 0
}

# The ladder. Deliberately not exponential past a day: a board that has been failing for
# a week is not more likely to come back if we ask every other week, and a fixed daily
# retry keeps the recovery time bounded and predictable.
backoff_seconds() {
  case "$1" in
    1) echo $(( 6 * 3600 )) ;;
    2) echo $(( 12 * 3600 )) ;;
    *) echo $(( 24 * 3600 )) ;;
  esac
}

# Failures that mean "this board is not collectable as configured" rather than "the
# network had a bad day". Retrying these on a timer is pointless at best: a robots
# disallow is a stated instruction, and a challenge page or an auth requirement is a
# door that a scheduler hammering it will only close harder. They pause the board and
# wait for a person.
is_pausing_failure() {
  case "$1" in
    *ROBOTS_DISALLOWED*|*BLOCKED_BOT_CHALLENGE*|*REFUSED_403*|*HTTP_401*|*HTTP_407*)
      return 0 ;;
    *) return 1 ;;
  esac
}

# state_record_success <mode> <provider> <board> <run_id>
state_record_success() {
  local mode="$1" provider="$2" board="$3" run_id="$4" now
  now="$(now_epoch)"
  state_write "$mode" "$provider" "$board" "$(jq -n \
    --arg p "$provider" --arg b "$board" --arg m "$mode" --arg r "$run_id" \
    --arg at "$(epoch_to_iso "$now")" \
    '{provider:$p, board:$b, mode:$m, consecutive_failures:0, next_allowed_at:0,
      paused:false, paused_reason:"", last_outcome:"ok", last_reason:"",
      last_run_id:$r, last_success_at:$at}')"
}

# state_record_failure <mode> <provider> <board> <run_id> <reason> [retry_not_before]
#
# retry_not_before is an RFC 3339 instant the SERVER named (from the manifest's
# rate_limit.retry_not_before). Empty when it named none.
#
# Returns nothing; the caller reads the state back if it wants the new next_allowed_at.
state_record_failure() {
  local mode="$1" provider="$2" board="$3" run_id="$4" reason="$5"
  local server_deadline="${6:-}"
  local s fails now delay next paused="false" paused_reason="" server_epoch=0
  s="$(state_read "$mode" "$provider" "$board")"
  fails=$(( $(jq -r '.consecutive_failures // 0' <<<"$s") + 1 ))
  now="$(now_epoch)"

  if [[ -n "$server_deadline" ]]; then
    server_epoch="$(date -u -d "$server_deadline" +%s 2>/dev/null || echo 0)"
  fi

  if is_pausing_failure "$reason"; then
    paused="true"
    paused_reason="$reason"
    # No next_allowed_at: a paused board is not on a timer at all. Clearing it is a
    # deliberate act (pipeline-status.sh --resume), because whatever caused it needs a
    # person to look before anything asks that server again.
    next=0
  else
    delay="$(backoff_seconds "$fails")"
    # A 429 gets at least the first rung, even on the first failure: a server that is
    # rate-limiting wants a real gap, not the minute a generic transient error would get.
    if [[ "$reason" == *RATE_LIMITED_429* ]] && (( delay < 6 * 3600 )); then
      delay=$(( 6 * 3600 ))
    fi
    next=$(( now + delay ))

    # The server's own deadline wins when it is later. Local policy may hold back
    # LONGER than asked -- that is our choice to make -- but never shorter: retrying
    # before a Retry-After has elapsed is the thing the header exists to forbid.
    #
    # It is stored as an absolute instant, so a restart in between cannot lose it. A
    # remembered duration would silently become "wait that long again from whenever we
    # happen to come back", which is a different and weaker promise.
    if (( server_epoch > next )); then
      next="$server_epoch"
    fi
  fi

  state_write "$mode" "$provider" "$board" "$(jq -n \
    --arg p "$provider" --arg b "$board" --arg m "$mode" --arg r "$run_id" \
    --arg reason "$reason" --argjson fails "$fails" --argjson next "$next" \
    --argjson paused "$paused" --arg pr "$paused_reason" \
    --arg last_success "$(jq -r '.last_success_at // ""' <<<"$s")" \
    '{provider:$p, board:$b, mode:$m, consecutive_failures:$fails,
      next_allowed_at:$next, paused:$paused, paused_reason:$pr,
      last_outcome:"failed", last_reason:$reason, last_run_id:$r,
      last_success_at:$last_success}')"
}
