#!/usr/bin/env bash
#
# The Linux pipeline's behaviour, checked in the Linux image itself.
#
#   ./infra/pipeline/verify-pipeline-linux.sh
#
# Each case checks that the right thing happened for the RIGHT REASON: a batch refused
# because of a stray byte-order mark rather than because its manifest says "failed" would
# satisfy a laxer assertion while proving nothing.
#
# No job site is contacted: fixture mode replays a local file, and the failure cases are
# built by hand. The database is the existing isolated aijob_import_test, reached by the
# service name on the test network.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA_REPO="$(cd "$HERE/../.." && pwd)"
IMAGE="${AIJOB_PIPELINE_IMAGE:-aijob-pipeline:local}"
NETWORK="${AIJOB_TEST_NETWORK:-aijob-mysqltest_default}"
ENV_FILE="$JAVA_REPO/infra/mysql-test/.env.mysql-test"

MOUNT_REPO="$JAVA_REPO"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) export MSYS_NO_PATHCONV=1; MOUNT_REPO="$(cygpath -w "$JAVA_REPO")" ;;
esac

[[ -f "$ENV_FILE" ]] || { echo "missing $ENV_FILE"; exit 1; }

# Read the existing credentials; never printed, never on a command line, file untouched.
DB_USER="$(grep '^AIJOB_MYSQL_TEST_USERNAME=' "$ENV_FILE" | cut -d= -f2-)"
DB_PASS="$(grep '^AIJOB_MYSQL_TEST_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
[[ -n "$DB_USER" && -n "$DB_PASS" ]] || { echo "credentials not found in $ENV_FILE"; exit 1; }

CONTAINER_ENV="$(mktemp)"
trap 'rm -f "$CONTAINER_ENV"' EXIT
cat > "$CONTAINER_ENV" <<EOF
SPRING_DATASOURCE_URL=jdbc:mysql://mysql-test:3306/aijob_import_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
SPRING_DATASOURCE_USERNAME=$DB_USER
SPRING_DATASOURCE_PASSWORD=$DB_PASS
AIJOB_STORAGE_ROOT=/tmp/aijob-uploads
AIJOB_COLLECTOR_ALLOWLIST_FILE=/opt/aijob/config/sources-allowlist.fixture.json
JOB_COLLECTOR_ENABLED=false
EOF
MOUNT_ENV="$CONTAINER_ENV"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) MOUNT_ENV="$(cygpath -w "$CONTAINER_ENV")" ;; esac
echo "credentials: read from $ENV_FILE (values not shown, file unchanged)"

PASS=0
FAIL=0
check() {
  local name="$1" expected="$2" actual="$3" detail="${4:-}"
  if [[ "$expected" == "$actual" ]]; then
    printf '  %-48s expected=%-10s actual=%-10s PASS\n' "$name" "$expected" "$actual"
    PASS=$((PASS + 1))
  else
    printf '  %-48s expected=%-10s actual=%-10s FAIL\n' "$name" "$expected" "$actual"
    FAIL=$((FAIL + 1))
  fi
  [[ -n "$detail" ]] && echo "      $detail"
  return 0
}
contains() { grep -qF "$2" <<<"$1" && echo yes || echo no; }

# A shared batch/lock volume, so every case in this script competes for the same lock and
# writes into one place -- which is also what makes case E meaningful.
docker volume create aijob-verify-state >/dev/null

run_pipeline() {
  docker run --rm \
    --network "$NETWORK" \
    --env-file "$MOUNT_ENV" \
    -v aijob-verify-state:/var/lib/aijob \
    "$IMAGE" "$@" 2>&1
}

# Each case starts from a clean back-off state.
#
# Without this the cases interfere: case C makes the export fail, which correctly records
# a failure and puts the board into a six-hour back-off, and every later case would then
# be SKIPPED rather than exercising what it names. That is the scheduler working, but it
# is not what these cases are testing -- so the state is cleared between them, explicitly,
# rather than left to the order they happen to run in.
reset_state() {
  docker run --rm -v aijob-verify-state:/var/lib/aijob --entrypoint sh "$IMAGE" \
    -c 'rm -rf /var/lib/aijob/state' >/dev/null 2>&1
}

echo
reset_state
echo "=== A. fixture export and import ==="
OUT_A="$(run_pipeline)"; CODE_A=$?
check 'A: exit 0' 0 "$CODE_A"
check 'A: exported' yes "$(contains "$OUT_A" 'export ok: run=')"
check 'A: imported' yes "$(contains "$OUT_A" 'import: ok')"
check 'A: went to the fixture board, not a real one' yes "$(contains "$OUT_A" 'greenhouse/fixture-demo')"
EXPORTED_A="$(grep -oE 'exported=[0-9]+' <<<"$OUT_A" | head -1 | cut -d= -f2)"
INS_A="$(grep -oE 'ins=[0-9]+' <<<"$OUT_A" | head -1 | cut -d= -f2)"
UNCH_A="$(grep -oE 'unch=[0-9]+' <<<"$OUT_A" | head -1 | cut -d= -f2)"
# Deliberately not "A inserted everything": this script does not reset the database (the
# rule is that it never deletes test data), so whether these postings are new depends on
# whether an earlier verification run already imported them. What must hold either way is
# that every exported record was accounted for.
check 'A: every exported record accounted for' "${EXPORTED_A:-x}" \
  "$(( ${INS_A:-0} + ${UNCH_A:-0} ))"

echo
reset_state
echo "=== B. the same batch again ==="
OUT_B="$(run_pipeline)"; CODE_B=$?
check 'B: exit 0' 0 "$CODE_B"
EXPORTED_B="$(grep -oE 'exported=[0-9]+' <<<"$OUT_B" | head -1 | cut -d= -f2)"
UNCH_B="$(grep -oE 'unch=[0-9]+' <<<"$OUT_B" | head -1 | cut -d= -f2)"
INS_B="$(grep -oE 'ins=[0-9]+' <<<"$OUT_B" | head -1 | cut -d= -f2)"
check 'B: inserted nothing the second time' 0 "${INS_B:-x}"
check 'B: all of them read back as unchanged' "${EXPORTED_B:-x}" "${UNCH_B:-y}" \
  "a replay of the identical batch must update the catalog, not grow it"

echo
reset_state
echo "=== C. export fails, a usable JSONL sits beside it ==="
# The exporter is pointed at a fixture that does not exist, so it fails -- while a batch
# from run A is still on the volume. Nothing from that older run may be consumed.
OUT_C="$(docker run --rm --network "$NETWORK" --env-file "$MOUNT_ENV" \
  -v aijob-verify-state:/var/lib/aijob \
  -e FIXTURE_DIR=/opt/aijob/nonexistent \
  "$IMAGE" 2>&1)"; CODE_C=$?
check 'C: run reports failure (exit 1)' 1 "$CODE_C"
check 'C: export failed' yes "$(contains "$OUT_C" 'EXPORT FAILED')"
check 'C: import was not attempted' yes "$(contains "$OUT_C" 'import=not attempted')"
check 'C: did not fall back to an older batch' no "$(contains "$OUT_C" 'import: ok')"

echo
reset_state
echo "=== D. a board that is not on the allowlist ==="
# The allowlist the SCRIPT reads lists the board; the one the IMPORTER reads does not.
# That is the case worth testing: the boundary refuses it on its own, without relying on
# whatever produced the batch.
OUT_D="$(docker run --rm --network "$NETWORK" --env-file "$MOUNT_ENV" \
  -v aijob-verify-state:/var/lib/aijob \
  -e AIJOB_COLLECTOR_ALLOWLIST_FILE=/opt/aijob/config/sources-allowlist.json \
  "$IMAGE" 2>&1)"; CODE_D=$?
check 'D: refused' yes "$(contains "$OUT_D" 'import: refused')"
check 'D: refused by the allowlist' yes "$(contains "$OUT_D" 'is not in')"
check 'D: nothing imported' no "$(contains "$OUT_D" 'import: ok')"

echo
reset_state
echo "=== E. two runs at once ==="
# One container holds the lock on the shared volume while a second tries to start. The
# lock is flock on a file the kernel owns, so the second cannot take it -- and the shared
# volume is what makes the two containers actually contend for the same file.
docker run -d --rm --name aijob-lock-holder \
  --network "$NETWORK" --env-file "$MOUNT_ENV" \
  -v aijob-verify-state:/var/lib/aijob \
  --entrypoint sh "$IMAGE" \
  -c 'exec 9>/var/lib/aijob/lock/pipeline.lock; flock -n 9 || exit 9; sleep 25' >/dev/null
sleep 3
OUT_E="$(run_pipeline --skip-import)"; CODE_E=$?
check 'E: second run skipped (exit 3)' 3 "$CODE_E"
check 'E: skipped because of the lock' yes "$(contains "$OUT_E" 'another run holds')"
docker stop aijob-lock-holder >/dev/null 2>&1

echo
reset_state
echo "=== F. once the lock is free ==="
OUT_F="$(run_pipeline --skip-import)"; CODE_F=$?
check 'F: proceeds (exit 0)' 0 "$CODE_F"
check 'F: exported' yes "$(contains "$OUT_F" 'export ok: run=')"

echo
reset_state
echo "=== G. no HTTP server, no scheduled collection, no expiry ==="
ALL="$OUT_A$OUT_B$OUT_F"
check 'G: no Tomcat' no "$(contains "$ALL" 'Tomcat started')"
check 'G: no scheduled collection' no "$(contains "$ALL" 'Collecting from source')"
check 'G: no board request in fixture mode' no "$(contains "$ALL" 'boards-api.greenhouse.io')"
check 'G: nothing expired' yes "$(grep -qE 'expired=[1-9]' <<<"$ALL" && echo no || echo yes)"
check 'G: credentials not echoed' no "$(contains "$ALL" "$DB_PASS")"

echo
echo "=== summary ==="
echo "  $((PASS + FAIL)) checks, $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]] || exit 1
