#!/usr/bin/env bash
#
# Scheduling behaviour: back-off, pauses, restart persistence, lock contention, and the
# retention boundaries.
#
#   ./infra/pipeline/verify-schedule.sh
#
# Time is injected through AIJOB_NOW, so a 24-hour back-off is checked in milliseconds
# rather than by waiting a day. Nothing here contacts a job site; the failure cases are
# built by writing state and by pointing the exporter at a fixture that does not exist.
#
# The retention cases run against a temporary synthetic tree, never the real batch
# directory, and the destructive path is exercised on that copy alone.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA_REPO="$(cd "$HERE/../.." && pwd)"
IMAGE="${AIJOB_PIPELINE_IMAGE:-aijob-pipeline:local}"
NETWORK="${AIJOB_TEST_NETWORK:-aijob-mysqltest_default}"
ENV_FILE="$JAVA_REPO/infra/mysql-test/.env.mysql-test"

# Part 5 needs the Docker CLI, which the pipeline image does not have. Everything else
# needs jq and flock, which the host may not have. So the two halves run in different
# places: --state-only inside the container, --docker-only on the host.
RUN_STATE=1
RUN_DOCKER=1
for a in "$@"; do
  case "$a" in
    --state-only)  RUN_DOCKER=0 ;;
    --docker-only) RUN_STATE=0 ;;
  esac
done

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export MSYS_NO_PATHCONV=1 ;; esac

PASS=0; FAIL=0
check() {
  local name="$1" expected="$2" actual="$3" detail="${4:-}"
  if [[ "$expected" == "$actual" ]]; then
    printf '  %-52s expected=%-12s actual=%-12s PASS\n' "$name" "$expected" "$actual"; PASS=$((PASS+1))
  else
    printf '  %-52s expected=%-12s actual=%-12s FAIL\n' "$name" "$expected" "$actual"; FAIL=$((FAIL+1))
  fi
  [[ -n "$detail" ]] && echo "      $detail"
  return 0
}
contains() { grep -qF "$2" <<<"$1" && echo yes || echo no; }

# ---------------------------------------------------------------------------
# Part 1: the state machine, exercised directly in this shell
# ---------------------------------------------------------------------------
if [[ "$RUN_STATE" -eq 1 ]]; then
echo "=== 1. back-off state (AIJOB_NOW drives the clock) ==="

command -v jq >/dev/null || { echo "jq is required"; exit 1; }
export AIJOB_STATE_ROOT="$(mktemp -d)"
trap 'rm -rf "${AIJOB_STATE_ROOT:-/nonexistent}" "${RET_ROOT:-/nonexistent}"' EXIT
# shellcheck source=infra/pipeline/pipeline-state.sh
source "$HERE/pipeline-state.sh"

T0=1767225600   # a fixed epoch; the absolute value does not matter, the deltas do

export AIJOB_NOW=$T0
state_is_due live greenhouse vercel >/dev/null; check '1a: a board never run is due' 0 $?

state_record_failure live greenhouse vercel run-1 "HTTP_503: transient"
s="$(state_read live greenhouse vercel)"
check '1b: first failure counted' 1 "$(jq -r .consecutive_failures <<<"$s")"
check '1c: first back-off is 6h' $(( T0 + 6*3600 )) "$(jq -r .next_allowed_at <<<"$s")"

state_is_due live greenhouse vercel >/dev/null; check '1d: not due immediately after' 1 $?
export AIJOB_NOW=$(( T0 + 6*3600 - 1 ))
state_is_due live greenhouse vercel >/dev/null; check '1e: still not due one second early' 1 $?
export AIJOB_NOW=$(( T0 + 6*3600 ))
state_is_due live greenhouse vercel >/dev/null; check '1f: due exactly on time' 0 $?

state_record_failure live greenhouse vercel run-2 "HTTP_503: transient"
check '1g: second back-off is 12h' $(( T0 + 6*3600 + 12*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(state_read live greenhouse vercel)")"
export AIJOB_NOW=$(( T0 + 6*3600 + 12*3600 ))
state_record_failure live greenhouse vercel run-3 "HTTP_503: transient"
check '1h: third back-off is 24h' $(( T0 + 6*3600 + 12*3600 + 24*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(state_read live greenhouse vercel)")"
state_record_failure live greenhouse vercel run-4 "HTTP_503: transient"
check '1i: it stays at 24h, it does not keep doubling' $(( T0 + 6*3600 + 12*3600 + 24*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(state_read live greenhouse vercel)")"

state_record_success live greenhouse vercel run-5
s="$(state_read live greenhouse vercel)"
check '1j: success resets the counter' 0 "$(jq -r .consecutive_failures <<<"$s")"
check '1k: success clears the back-off' 0 "$(jq -r .next_allowed_at <<<"$s")"
state_is_due live greenhouse vercel >/dev/null; check '1l: due again after a success' 0 $?

echo
echo "=== 2. a 429 never comes back sooner than 6h ==="
export AIJOB_NOW=$T0
state_record_failure live greenhouse rate run-r1 "RATE_LIMITED_429: retries honoured Retry-After and still failed"
check '2a: first 429 waits 6h' $(( T0 + 6*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(state_read live greenhouse rate)")"
# The floor must not SHORTEN a longer wait further up the ladder.
export AIJOB_NOW=$(( T0 + 6*3600 ))
state_record_failure live greenhouse rate run-r2 "RATE_LIMITED_429"
check '2b: second 429 still gets the full 12h' $(( T0 + 6*3600 + 12*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(state_read live greenhouse rate)")"

echo
echo "=== 2b. the server's Retry-After deadline is persisted and never shortened ==="
# The exporter now reports the instant the server named. The rule is max(local back-off,
# server deadline): holding back longer than asked is our choice, going earlier is not.
export AIJOB_NOW=$T0

# A deadline BEYOND the local ladder must win.
FAR="$(date -u -d "@$(( T0 + 30*3600 ))" +%Y-%m-%dT%H:%M:%SZ)"
state_record_failure live greenhouse ra-far run-f1 "RATE_LIMITED_429: the server asked" "$FAR"
check '2b-i: a 30h deadline overrides the 6h rung' $(( T0 + 30*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(state_read live greenhouse ra-far)")"

# A deadline INSIDE the local ladder must not shorten it.
NEAR="$(date -u -d "@$(( T0 + 120 ))" +%Y-%m-%dT%H:%M:%SZ)"
state_record_failure live greenhouse ra-near run-f2 "RATE_LIMITED_429: the server asked" "$NEAR"
check '2b-ii: a 2min deadline does not shorten the 6h rung' $(( T0 + 6*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(state_read live greenhouse ra-near)")"

# No deadline: local policy alone, and that is what the state says.
state_record_failure live greenhouse ra-none run-f3 "RATE_LIMITED_429: no usable Retry-After" ""
check '2b-iii: no deadline falls back to local policy' $(( T0 + 6*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(state_read live greenhouse ra-none)")"

# A malformed instant must not silently become "now".
state_record_failure live greenhouse ra-bad run-f4 "RATE_LIMITED_429" "not-a-timestamp"
check '2b-iv: an unparseable deadline never shortens the wait' $(( T0 + 6*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(state_read live greenhouse ra-bad)")"

# Restart: a fresh process, just short of the server's deadline, must still refuse.
out="$(AIJOB_STATE_ROOT="$AIJOB_STATE_ROOT" AIJOB_NOW=$(( T0 + 30*3600 - 1 )) bash -c \
  'source "'"$HERE"'/pipeline-state.sh"; state_is_due live greenhouse ra-far >/dev/null; echo $?')"
check '2b-v: still not due one second early, in a new process' 1 "$out"
out="$(AIJOB_STATE_ROOT="$AIJOB_STATE_ROOT" AIJOB_NOW=$(( T0 + 30*3600 )) bash -c \
  'source "'"$HERE"'/pipeline-state.sh"; state_is_due live greenhouse ra-far >/dev/null; echo $?')"
check '2b-vi: due once the deadline passes' 0 "$out"

echo
echo "=== 3. a refusal pauses the board instead of backing it off ==="
for reason in "ROBOTS_DISALLOWED: robots.txt disallows this path" \
              "BLOCKED_BOT_CHALLENGE: the platform served a challenge" \
              "REFUSED_403: the platform declined this request"; do
  board="paused-$(tr -dc a-z </dev/urandom | head -c 6)"
  state_record_failure live greenhouse "$board" run-x "$reason"
  s="$(state_read live greenhouse "$board")"
  short="${reason%%:*}"
  check "3: $short pauses" true "$(jq -r .paused <<<"$s")"
  state_is_due live greenhouse "$board" >/dev/null
  check "3: $short is not merely delayed" 2 $?
done

echo
echo "=== 4. state survives a restart, and live/fixture are separate ==="
# A fresh shell, reading the same directory: this is what "survives a restart" means for
# state on disk. Nothing is held in memory between runs.
out="$(AIJOB_STATE_ROOT="$AIJOB_STATE_ROOT" AIJOB_NOW=$T0 bash -c \
  'source "'"$HERE"'/pipeline-state.sh"; state_is_due live greenhouse rate >/dev/null; echo $?')"
check '4a: back-off still applies in a new process' 1 "$out"
out="$(AIJOB_STATE_ROOT="$AIJOB_STATE_ROOT" AIJOB_NOW=$T0 bash -c \
  'source "'"$HERE"'/pipeline-state.sh"; state_is_due fixture greenhouse rate >/dev/null; echo $?')"
check '4b: the same board in fixture mode is unaffected' 0 "$out"

echo
fi  # RUN_STATE

if [[ "$RUN_DOCKER" -eq 1 ]]; then

echo "=== 5. lock contention is a skip, not a failure ==="
# Exit 3 is what the systemd unit lists in SuccessExitStatus, so this is the contract
# that keeps a held lock out of the failure count.
docker volume create aijob-sched-state >/dev/null
docker run -d --rm --name aijob-sched-holder \
  -v aijob-sched-state:/var/lib/aijob --entrypoint sh "$IMAGE" \
  -c 'exec 9>/var/lib/aijob/lock/pipeline.lock; flock -n 9 || exit 9; sleep 20' >/dev/null 2>&1 \
  || docker run -d --rm --name aijob-sched-holder \
       -v aijob-sched-state:/var/lib/aijob --entrypoint sh "$IMAGE" \
       -c 'mkdir -p /var/lib/aijob/lock; exec 9>/var/lib/aijob/lock/pipeline.lock; flock -n 9 || exit 9; sleep 20' >/dev/null
sleep 3
OUT5="$(docker run --rm -v aijob-sched-state:/var/lib/aijob "$IMAGE" --skip-import 2>&1)"; CODE5=$?
check '5a: the second run exits 3' 3 "$CODE5"
check '5b: and says why' yes "$(contains "$OUT5" 'another run holds')"
docker stop aijob-sched-holder >/dev/null 2>&1
docker volume rm aijob-sched-state >/dev/null 2>&1
fi

echo
if [[ "$RUN_STATE" -eq 1 ]]; then
echo "=== 6. retention boundaries (synthetic tree, never the real batch dir) ==="

# Back to the real clock. Part 1 exported AIJOB_NOW as a fixed epoch in the past, and
# retention.sh honours that variable too -- so leaving it set made every file's age
# come out negative and nothing matched. The fixture below sets mtimes relative to the
# real now, so the two have to agree on what "now" is.
unset AIJOB_NOW

RET_ROOT="$(mktemp -d)"
mk_run() {
  # Split, not one `local`: a `local` command expands all its arguments before assigning
  # any of them, so d="$RET_ROOT/$name" would read $name before it exists.
  local name="$1" outcome="$2" age_days="$3"
  local d="$RET_ROOT/$name"
  mkdir -p "$d/greenhouse-x"
  echo '{"x":1}' > "$d/greenhouse-x/batch.jsonl"
  echo '{"status":"ok"}' > "$d/greenhouse-x/batch.manifest.json"
  echo 'log' > "$d/greenhouse-x/export.log"
  if [[ "$outcome" == ok ]]; then
    cat > "$d/pipeline-run.json" <<'J'
{"pipeline_run_id":"x","mode":"fixture","exporter_sha":"a","importer_sha":"b",
 "boards":[{"board":"greenhouse/x","export":"ok","import":"ok","fetched":1,"exported":1,
            "inserted":1,"updated":0,"unchanged":0,"note":""}]}
J
  else
    cat > "$d/pipeline-run.json" <<'J'
{"pipeline_run_id":"x","mode":"fixture","exporter_sha":"a","importer_sha":"b",
 "boards":[{"board":"greenhouse/x","export":"failed","import":"not attempted","fetched":0,
            "exported":0,"inserted":0,"updated":0,"unchanged":0,"note":"HTTP_503"}]}
J
  fi
  touch -d "@$(( $(date -u +%s) - age_days * 86400 ))" "$d/pipeline-run.json"
}

mk_run ok-fresh   ok     1
mk_run ok-old     ok     20    # past 14d: payload pruned, summary kept
mk_run fail-mid   failed 20    # under 30d: still whole
mk_run fail-old   failed 40    # past 30d: payload pruned
mk_run ok-ancient ok     100   # past 90d: directory removed
mkdir -p "$RET_ROOT/in-progress/greenhouse-x"   # no pipeline-run.json
echo '{}' > "$RET_ROOT/in-progress/greenhouse-x/batch.jsonl"
ln -s "$RET_ROOT/ok-fresh" "$RET_ROOT/a-symlink" 2>/dev/null || true

DRY="$(AIJOB_BATCH_ROOT="$RET_ROOT" bash "$HERE/retention.sh" 2>&1)"
check '6a: dry run deletes nothing' 7 "$(find "$RET_ROOT" -maxdepth 1 -mindepth 1 | wc -l | tr -d ' ')"
check '6b: dry run says so' yes "$(contains "$DRY" 'DRY RUN')"
check '6c: skips a run with no summary' yes "$(contains "$DRY" 'in-progress -- no pipeline-run.json')"
# A symlink standing where a run directory should be is never even a candidate: the scan
# is `find -type d`, and a symlink to a directory is -type l. So the assertion is that it
# does not appear at all -- neither deleted nor "skipped". retention.sh also refuses one
# explicitly, which is belt and braces for a future scan that widened.
check '6d: a symlink is never considered' no "$(contains "$DRY" 'a-symlink')"
check '6e: would remove the 100-day run' yes "$(contains "$DRY" 'DELETE ok-ancient')"
check '6f: would prune the 20-day success' yes "$(contains "$DRY" 'PRUNE  ok-old')"
check '6g: leaves the 20-day FAILURE whole' no "$(contains "$DRY" 'PRUNE  fail-mid')"
check '6h: would prune the 40-day failure' yes "$(contains "$DRY" 'PRUNE  fail-old')"
check '6i: leaves the fresh one alone' no "$(contains "$DRY" 'ok-fresh')"

APPLIED="$(AIJOB_BATCH_ROOT="$RET_ROOT" bash "$HERE/retention.sh" --apply 2>&1)"
check '6j: apply removed the ancient run' no "$([[ -d "$RET_ROOT/ok-ancient" ]] && echo yes || echo no)"
check '6k: apply kept the old summary' yes "$([[ -f "$RET_ROOT/ok-old/pipeline-run.json" ]] && echo yes || echo no)"
check '6l: apply dropped the old payload' no "$([[ -f "$RET_ROOT/ok-old/greenhouse-x/batch.jsonl" ]] && echo yes || echo no)"
check '6m: apply kept the old manifest' yes "$([[ -f "$RET_ROOT/ok-old/greenhouse-x/batch.manifest.json" ]] && echo yes || echo no)"
check '6n: apply left the in-progress run' yes "$([[ -f "$RET_ROOT/in-progress/greenhouse-x/batch.jsonl" ]] && echo yes || echo no)"
check '6o: apply left the symlink target' yes "$([[ -f "$RET_ROOT/ok-fresh/greenhouse-x/batch.jsonl" ]] && echo yes || echo no)"

echo
fi  # RUN_STATE

echo "=== summary ==="
echo "  $((PASS + FAIL)) checks, $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]] || exit 1
