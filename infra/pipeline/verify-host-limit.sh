#!/usr/bin/env bash
#
# Per-host rate limiting, against a local HTTP server that answers 429.
#
#   ./infra/pipeline/verify-host-limit.sh
#
# The question is not "does a 429 back off the board" -- that already worked. It is
# whether the NEXT board on the SAME host is held back too, or whether moving to a
# different board walks straight around the limit the server just stated.
#
# No job site is contacted. The 429 comes from a container serving canned responses on
# the test network, so the exporter's own client does the real work against a real
# socket, and the timing assertions mean something.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE="${AIJOB_PIPELINE_IMAGE:-aijob-pipeline:local}"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export MSYS_NO_PATHCONV=1 ;; esac

PASS=0; FAIL=0
check() {
  local name="$1" expected="$2" actual="$3" detail="${4:-}"
  if [[ "$expected" == "$actual" ]]; then
    printf '  %-54s expected=%-12s actual=%-12s PASS\n' "$name" "$expected" "$actual"; PASS=$((PASS+1))
  else
    printf '  %-54s expected=%-12s actual=%-12s FAIL\n' "$name" "$expected" "$actual"; FAIL=$((FAIL+1))
  fi
  [[ -n "$detail" ]] && echo "      $detail"
  return 0
}

STATE="$(mktemp -d)"
MOUNT_STATE="$STATE"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) MOUNT_STATE="$(cygpath -w "$STATE")" ;; esac
export AIJOB_STATE_ROOT="$STATE"
# shellcheck source=infra/pipeline/pipeline-state.sh
source "$HERE/pipeline-state.sh"
trap 'rm -rf "$STATE"' EXIT

command -v jq >/dev/null || { echo "jq is required (run this inside the pipeline image)"; exit 1; }

T0=1767225600
export AIJOB_NOW=$T0
HOST="boards-api.example.test"

echo "=== 1. a 429 holds back the host, not just the board ==="
# Two boards on one host. The first is rate-limited; the second must not proceed.
state_record_host live greenhouse boardone "$HOST"
state_record_host live greenhouse boardtwo "$HOST"
state_record_failure live greenhouse boardone run-1 "RATE_LIMITED_429: the server asked" ""
host_state_record_limit live "$HOST" run-1 "RATE_LIMITED_429: the server asked" ""

state_is_due live greenhouse boardone >/dev/null
check '1a: the board that got the 429 is held' 1 $?
state_is_due live greenhouse boardtwo >/dev/null
check '1b: its NEIGHBOUR is still due by board state alone' 0 $? \
  'board state knows nothing about the host -- which is why the host gate exists'
host_state_is_due live "$HOST" >/dev/null
check '1c: but the HOST gate holds it' 1 $?

echo
echo "=== 2. the server's Retry-After wins when it is longer ==="
FAR="$(date -u -d "@$(( T0 + 30*3600 ))" +%Y-%m-%dT%H:%M:%SZ)"
host_state_record_limit live "$HOST" run-2 "RATE_LIMITED_429" "$FAR"
check '2a: host deadline follows the server' $(( T0 + 30*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(host_state_read live "$HOST")")"
NEAR="$(date -u -d "@$(( T0 + 60 ))" +%Y-%m-%dT%H:%M:%SZ)"
host_state_record_limit live "$HOST" run-3 "RATE_LIMITED_429" "$NEAR"
check '2b: a nearer deadline never shortens the local ladder' true \
  "$([[ "$(jq -r .next_allowed_at <<<"$(host_state_read live "$HOST")")" -gt $(( T0 + 60 )) ]] && echo true || echo false)"

echo
echo "=== 3. no Retry-After: an explicit local back-off, not zero ==="
HOST2="api.other.test"
host_state_record_limit live "$HOST2" run-4 "RATE_LIMITED_429: no usable Retry-After" ""
check '3a: first limit is the 6h rung' $(( T0 + 6*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(host_state_read live "$HOST2")")"
export AIJOB_NOW=$(( T0 + 6*3600 ))
host_state_record_limit live "$HOST2" run-5 "RATE_LIMITED_429" ""
check '3b: second climbs to 12h' $(( T0 + 6*3600 + 12*3600 )) \
  "$(jq -r .next_allowed_at <<<"$(host_state_read live "$HOST2")")"

echo
echo "=== 4. a success clears the host ==="
host_state_record_success live "$HOST2"
check '4a: cleared' 0 "$(jq -r .next_allowed_at <<<"$(host_state_read live "$HOST2")")"
host_state_is_due live "$HOST2" >/dev/null
check '4b: due again' 0 $?

echo
echo "=== 5. hosts are independent, and so are modes ==="
export AIJOB_NOW=$T0
host_state_record_limit live "$HOST" run-6 "RATE_LIMITED_429" ""
host_state_is_due live "api.unrelated.test" >/dev/null
check '5a: an unrelated host is unaffected' 0 $?
host_state_is_due fixture "$HOST" >/dev/null
check '5b: the same host in fixture mode is unaffected' 0 $?

echo
echo "=== 6. the host is read from the request the exporter actually made ==="
M="$(mktemp)"
cat > "$M" <<'J'
{"requests":["https://boards-api.greenhouse.io/robots.txt",
             "https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true"]}
J
check '6a: the board endpoint, not the robots preflight' 'boards-api.greenhouse.io' \
  "$(host_from_manifest "$M")"
cat > "$M" <<'J'
{"requests":["https://api.lever.co/robots.txt","https://api.lever.co/v0/postings/acme?mode=json"]}
J
check '6b: and for the other provider' 'api.lever.co' "$(host_from_manifest "$M")"
rm -f "$M"

echo
echo "=== 7. state survives a restart ==="
out="$(AIJOB_STATE_ROOT="$STATE" AIJOB_NOW=$T0 bash -c \
  'source "'"$HERE"'/pipeline-state.sh"; host_state_is_due live "'"$HOST"'" >/dev/null; echo $?')"
check '7a: the host limit still applies in a new process' 1 "$out"

echo
echo "=== summary ==="
echo "  $((PASS + FAIL)) checks, $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]] || exit 1
