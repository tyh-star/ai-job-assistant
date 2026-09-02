#!/usr/bin/env bash
#
# What the pipeline is doing, read from local files. Nothing is sent anywhere: no mail,
# no webhook, no external alerting. If you want to be paged, wire that up deliberately
# rather than inheriting it from a status command.
#
#   pipeline-status.sh                      # per-board state, both modes
#   pipeline-status.sh --runs 5             # the last 5 run summaries
#   pipeline-status.sh --json               # the same state as JSON
#   pipeline-status.sh --resume greenhouse/vercel [--mode live]
#
# --resume clears a pause. It is the only mutating option here, it is never automatic,
# and it exists because a paused board means a server said no -- robots, a challenge
# page, a 403 -- and a scheduler must not decide on its own that the answer has changed.

set -uo pipefail

STATE_ROOT="${AIJOB_STATE_ROOT:-/var/lib/aijob}"
BATCH_ROOT="${AIJOB_BATCH_ROOT:-$STATE_ROOT/batches}"

RUNS=0
AS_JSON=0
RESUME=""
MODE="live"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runs)   RUNS="${2:-5}"; shift 2 ;;
    --json)   AS_JSON=1; shift ;;
    --resume) RESUME="${2:-}"; shift 2 ;;
    --mode)   MODE="${2:-live}"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

if [[ -n "$RESUME" ]]; then
  provider="${RESUME%%/*}"
  board="${RESUME#*/}"
  safe="$(printf '%s__%s' "$provider" "$board" | tr -c 'A-Za-z0-9_.-' '_')"
  f="$STATE_ROOT/state/$MODE/$safe.json"
  [[ -f "$f" ]] || { echo "no state for $RESUME in mode $MODE ($f)" >&2; exit 1; }
  was="$(jq -r '.paused_reason // ""' "$f")"
  # The failure count is reset too. Keeping it would put the board straight back into a
  # 24h back-off on its first attempt after a pause someone deliberately cleared.
  tmp="$f.partial.$$"
  jq '.paused = false | .paused_reason = "" | .next_allowed_at = 0 | .consecutive_failures = 0' \
    "$f" > "$tmp" && mv -f "$tmp" "$f"
  echo "resumed $RESUME (mode $MODE)"
  [[ -n "$was" ]] && echo "  it had been paused by: $was"
  exit 0
fi

if [[ "$RUNS" -gt 0 ]]; then
  echo "=== last $RUNS runs ==="
  # Newest first. Each run directory holds pipeline-run.json, which is the de-identified
  # summary: counts and statuses, no posting content.
  find "$BATCH_ROOT" -maxdepth 2 -name pipeline-run.json -type f 2>/dev/null \
    | sort -r | head -n "$RUNS" | while read -r f; do
      jq -r '"  \(.pipeline_run_id)  mode=\(.mode)  exporter=\(.exporter_sha[0:12])  importer=\(.importer_sha[0:12])"' "$f"
      jq -r '.boards[] | "      \(.board)  export=\(.export)  import=\(.import)  fetched=\(.fetched) exported=\(.exported) ins=\(.inserted) upd=\(.updated) unch=\(.unchanged)" + (if .note != "" then "\n        note: " + .note else "" end)' "$f"
    done
  echo
fi

collect_state() {
  local mode="$1" dir="$STATE_ROOT/state/$mode"
  [[ -d "$dir" ]] || return 0
  find "$dir" -maxdepth 1 -name '*.json' -type f 2>/dev/null | sort | while read -r f; do
    jq -c --arg mode "$mode" '. + {mode: $mode}' "$f" 2>/dev/null || true
  done
}

if [[ "$AS_JSON" -eq 1 ]]; then
  { collect_state live; collect_state fixture; } | jq -s '.'
  exit 0
fi

now="$(date -u +%s)"
for mode in live fixture; do
  rows="$(collect_state "$mode")"
  [[ -n "$rows" ]] || continue
  echo "=== $mode ==="
  while read -r row; do
    [[ -n "$row" ]] || continue
    provider="$(jq -r '.provider' <<<"$row")"
    board="$(jq -r '.board' <<<"$row")"
    fails="$(jq -r '.consecutive_failures // 0' <<<"$row")"
    paused="$(jq -r '.paused // false' <<<"$row")"
    next="$(jq -r '.next_allowed_at // 0' <<<"$row")"
    outcome="$(jq -r '.last_outcome // "?"' <<<"$row")"
    reason="$(jq -r '.last_reason // ""' <<<"$row")"
    run="$(jq -r '.last_run_id // ""' <<<"$row")"
    success="$(jq -r '.last_success_at // ""' <<<"$row")"

    if [[ "$paused" == "true" ]]; then
      when="PAUSED"
    elif [[ "$next" == "0" || "$next" -le "$now" ]]; then
      when="due now"
    else
      when="$(date -u -d "@$next" +%Y-%m-%dT%H:%M:%SZ)"
    fi
    printf '  %-28s last=%-9s failures=%-3s next=%s\n' "$provider/$board" "$outcome" "$fails" "$when"
    [[ -n "$reason"  ]] && printf '      reason: %s\n' "$reason"
    [[ -n "$run"     ]] && printf '      last run: %s\n' "$run"
    [[ -n "$success" ]] && printf '      last success: %s\n' "$success"
    if [[ "$paused" == "true" ]]; then
      printf '      resume with: pipeline-status.sh --resume %s/%s --mode %s\n' "$provider" "$board" "$mode"
    fi
  done <<<"$rows"
  echo
done

echo "logs: journalctl -u aijob-pipeline.service   (or the batch dir's export.log / import.log)"
