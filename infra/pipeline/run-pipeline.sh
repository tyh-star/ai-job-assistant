#!/usr/bin/env bash
#
# allowlist -> Go export -> verify THIS run's output -> Java import -> summary.
#
# The Linux entry point, and the one that matters: Ubuntu is the deployment target, and
# the PowerShell version is a development convenience that will not be on that host.
# Same decisions, same exit codes, same refusals.
#
# It runs the exporter and the importer as two processes inside ONE container, so there
# is no docker-in-docker and nothing to mount a socket for.
#
#   /opt/aijob/run-pipeline.sh                 # fixture, no network
#   /opt/aijob/run-pipeline.sh --live          # fetch from the boards
#
# Exit: 0 all boards handled, 1 something failed, 3 another run holds the lock.

set -uo pipefail

# --- configuration, all overridable -----------------------------------------
JDEXPORT="${JDEXPORT:-/opt/aijob/jdexport}"
IMPORTER_JAR="${IMPORTER_JAR:-/opt/aijob/app.jar}"
FIXTURE_DIR="${FIXTURE_DIR:-/opt/aijob/testdata}"
CONFIG_DIR="${CONFIG_DIR:-/opt/aijob/config}"
BATCH_ROOT="${AIJOB_BATCH_ROOT:-/var/lib/aijob/batches}"

# The lock file's directory must be visible to every process that competes for it.
# A path inside a container's own writable layer is private to that container, so two
# containers would each take "the" lock and both run. Mount a shared volume (or a host
# directory) at LOCK_DIR when more than one container can start this.
LOCK_DIR="${AIJOB_LOCK_DIR:-/var/lib/aijob/lock}"

MAX_PER_BOARD="${AIJOB_MAX_PER_BOARD:-50}"
EXPORT_TIMEOUT="${AIJOB_EXPORT_TIMEOUT:-120s}"
EXPECTED_EXPORTER_SHA="${AIJOB_EXPECTED_EXPORTER_SHA:-}"

LIVE=0
SKIP_IMPORT=0
ALLOWLIST=""
for arg in "$@"; do
  case "$arg" in
    --live)        LIVE=1 ;;
    --skip-import) SKIP_IMPORT=1 ;;
    --allowlist=*) ALLOWLIST="${arg#*=}" ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# Fixture runs get their own allowlist, with a board identity that is not a real board.
# Sharing the live allowlist would file synthetic postings under the same source row as
# real ones, and no later count could separate them.
if [[ -z "$ALLOWLIST" ]]; then
  if [[ "$LIVE" -eq 1 ]]; then
    ALLOWLIST="$CONFIG_DIR/sources-allowlist.json"
  else
    ALLOWLIST="$CONFIG_DIR/sources-allowlist.fixture.json"
  fi
fi

abort() { echo "PIPELINE ABORT: $*" >&2; exit 1; }

for f in "$JDEXPORT" "$IMPORTER_JAR" "$ALLOWLIST"; do
  [[ -e "$f" ]] || abort "missing $f"
done
command -v jq >/dev/null || abort "jq is required"

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(head -c 3 /dev/urandom | od -An -tx1 | tr -d ' \n')"
RUN_DIR="$BATCH_ROOT/$RUN_ID"
mkdir -p "$RUN_DIR" "$LOCK_DIR"

# --- the exporter's version, recorded and optionally pinned -----------------
# Baked in at image build time: there is no git checkout in the runtime image, so the
# build stamps what it compiled and this reads it back. That is the honest source --
# a git command here would describe a repo the binary did not necessarily come from.
EXPORTER_SHA="$(cat /opt/aijob/EXPORTER_SHA 2>/dev/null || echo unknown)"
IMPORTER_SHA="$(cat /opt/aijob/IMPORTER_SHA 2>/dev/null || echo unknown)"
if [[ -n "$EXPECTED_EXPORTER_SHA" && "$EXPORTER_SHA" != "$EXPECTED_EXPORTER_SHA" ]]; then
  abort "exporter is $EXPORTER_SHA, expected $EXPECTED_EXPORTER_SHA"
fi

echo "run             : $RUN_ID"
echo "mode            : $([[ $LIVE -eq 1 ]] && echo 'LIVE (real network)' || echo 'fixture (no network)')"
echo "allowlist       : $ALLOWLIST"
echo "batch dir       : $RUN_DIR"
echo "exporter (go)   : $EXPORTER_SHA"
echo "importer (java) : $IMPORTER_SHA"

# --- mutual exclusion -------------------------------------------------------
# flock on a file descriptor, not a PID file. The kernel owns the lock: a second process
# cannot take it while the first holds it, and it is released when that process dies
# however it dies -- including SIGKILL, where no trap would run.
#
# The scope of this lock is the filesystem holding LOCK_DIR. Two containers each using
# their own writable layer would both "get the lock" and both run; LOCK_DIR has to be a
# shared mount for the guarantee to mean anything across containers.
LOCK_FILE="$LOCK_DIR/pipeline.lock"
exec {LOCK_FD}>"$LOCK_FILE" || abort "cannot open $LOCK_FILE"
if ! flock -n "$LOCK_FD"; then
  echo "PIPELINE SKIPPED: another run holds $LOCK_FILE"
  exit 3
fi

# --- boards -----------------------------------------------------------------
mapfile -t BOARDS < <(jq -c '.sources[] | select(.enabled)' "$ALLOWLIST")
[[ ${#BOARDS[@]} -gt 0 ]] || abort "no enabled sources in $ALLOWLIST"
echo "boards          : ${#BOARDS[@]} enabled"

FAILURES=0
SUMMARY=()

for entry in "${BOARDS[@]}"; do
  provider="$(jq -r '.provider' <<<"$entry")"
  board="$(jq -r '.board' <<<"$entry")"
  company="$(jq -r '.company' <<<"$entry")"
  has_evidence="$(jq -r 'if .evidence then "yes" else "no" end' <<<"$entry")"
  label="$provider/$board"

  echo
  echo "=== $label ==="

  if [[ "$has_evidence" != "yes" ]]; then
    echo "  SKIP: no evidence recorded in the allowlist"
    SUMMARY+=("$label|skipped|skipped|0|0|0|0|0|")
    continue
  fi

  # This run's own directory. Nothing from a previous run is reachable from here, so a
  # failed export cannot be quietly satisfied by a file left lying around.
  board_dir="$RUN_DIR/$provider-$board"
  mkdir -p "$board_dir"
  jsonl="$board_dir/batch.jsonl"
  manifest="$board_dir/batch.manifest.json"

  export_args=(-provider "$provider" -board "$board" -company "$company"
               -max "$MAX_PER_BOARD" -timeout "$EXPORT_TIMEOUT" -out "$jsonl")
  if [[ "$LIVE" -eq 1 ]]; then
    export_args+=(-live)
  else
    fixture="$FIXTURE_DIR/${provider}_board.json"
    [[ -f "$fixture" ]] || fixture="$FIXTURE_DIR/greenhouse_board.json"
    export_args+=(-fixture "$fixture")
  fi

  "$JDEXPORT" "${export_args[@]}" > "$board_dir/export.log" 2>&1
  export_code=$?

  # The exporter's own bounded retries have already run. A failure here is final for this
  # board in this run: no further attempts, and the manifest it wrote stays as the record.
  if [[ $export_code -ne 0 ]]; then
    echo "  EXPORT FAILED (exit $export_code) -- not importing this board"
    tail -n 3 "$board_dir/export.log" | sed 's/^/    /'
    SUMMARY+=("$label|failed|not attempted|0|0|0|0|0|export exit $export_code")
    FAILURES=$((FAILURES + 1))
    continue
  fi

  # --- verify THIS run's output before importing it -------------------------
  if [[ ! -f "$jsonl" || ! -f "$manifest" ]]; then
    echo "  EXPORT reported success but produced no batch -- not importing"
    SUMMARY+=("$label|incomplete|not attempted|0|0|0|0|0|missing jsonl or manifest")
    FAILURES=$((FAILURES + 1))
    continue
  fi
  status="$(jq -r '.status' "$manifest")"
  m_provider="$(jq -r '.provider' "$manifest")"
  m_board="$(jq -r '.board' "$manifest")"
  exported="$(jq -r '.exported_count' "$manifest")"
  fetched="$(jq -r '.fetched_count' "$manifest")"
  truncated="$(jq -r '.truncated' "$manifest")"
  export_run="$(jq -r '.run_id' "$manifest")"
  lines="$(grep -c '[^[:space:]]' "$jsonl" || true)"

  problems=()
  [[ "$status" == "ok" || "$status" == "ok_empty" ]] || problems+=("manifest status=$status")
  [[ "$m_provider" == "$provider" ]] || problems+=("manifest provider=$m_provider")
  [[ "$m_board" == "$board" ]]       || problems+=("manifest board=$m_board")
  [[ "$lines" == "$exported" ]]      || problems+=("file has $lines lines, manifest says $exported")
  if [[ ${#problems[@]} -gt 0 ]]; then
    echo "  BATCH REJECTED before import: ${problems[*]}"
    SUMMARY+=("$label|invalid|not attempted|$fetched|$exported|0|0|0|${problems[*]}")
    FAILURES=$((FAILURES + 1))
    continue
  fi
  echo "  export ok: run=$export_run fetched=$fetched exported=$exported truncated=$truncated"

  if [[ "$SKIP_IMPORT" -eq 1 ]]; then
    SUMMARY+=("$label|ok|skipped by flag|$fetched|$exported|0|0|0|")
    continue
  fi

  # --- import ---------------------------------------------------------------
  # The same CLI as everywhere else. It re-checks the allowlist, the manifest, every
  # record, and takes the per-source database lease -- none of which this script repeats
  # on its behalf.
  import_log="$board_dir/import.log"
  java -jar "$IMPORTER_JAR" \
      "--aijob.import.jsonl=$jsonl" \
      "--aijob.import.manifest=$manifest" > "$import_log" 2>&1
  import_code=$?

  case $import_code in
    0) import_status="ok" ;;
    2) import_status="refused" ;;
    3) import_status="skipped (board leased elsewhere)" ;;
    *) import_status="failed ($import_code)"; FAILURES=$((FAILURES + 1)) ;;
  esac
  echo "  import: $import_status"

  counts="$(grep -oE 'read=[0-9]+ inserted=[0-9]+ updated=[0-9]+ unchanged=[0-9]+' "$import_log" | tail -1 || true)"
  ins=0; upd=0; unch=0
  if [[ -n "$counts" ]]; then
    ins="$(sed -E 's/.*inserted=([0-9]+).*/\1/' <<<"$counts")"
    upd="$(sed -E 's/.*updated=([0-9]+).*/\1/' <<<"$counts")"
    unch="$(sed -E 's/.*unchanged=([0-9]+).*/\1/' <<<"$counts")"
  fi
  # Surface WHY, not just that something went wrong. Tailing the log printed whatever
  # happened to be last -- usually the connection pool shutting down -- so a refusal
  # arrived as the word "refused" with no reason attached, which is the one thing an
  # operator needs. Pull out the line that states it.
  reason=""
  if [[ $import_code -ne 0 ]]; then
    reason="$(grep -oE '(Batch refused before any write|Import skipped|Import failed): .*' "$import_log" | tail -1 || true)"
    [[ -z "$reason" ]] && reason="$(tail -n 2 "$import_log" | tr '\n' ' ')"
    echo "    $reason"
  fi
  SUMMARY+=("$label|ok|$import_status|$fetched|$exported|$ins|$upd|$unch|${reason:-$export_run}")
done

# --- summary and run record -------------------------------------------------
echo
echo "=== summary: run $RUN_ID ==="
for row in "${SUMMARY[@]}"; do
  IFS='|' read -r label exp imp fetched exported ins upd unch note <<<"$row"
  printf '  %-26s export=%-11s import=%-32s fetched=%-5s exported=%-5s ins=%s upd=%s unch=%s\n' \
    "$label" "$exp" "$imp" "$fetched" "$exported" "$ins" "$upd" "$unch"
  [[ -n "$note" ]] && echo "      $note"
done

record="$RUN_DIR/pipeline-run.json"
{
  printf '{\n'
  printf '  "pipeline_run_id": "%s",\n' "$RUN_ID"
  printf '  "mode": "%s",\n' "$([[ $LIVE -eq 1 ]] && echo live || echo fixture)"
  printf '  "platform": "linux",\n'
  printf '  "allowlist": "%s",\n' "$ALLOWLIST"
  printf '  "exporter_sha": "%s",\n' "$EXPORTER_SHA"
  printf '  "importer_sha": "%s",\n' "$IMPORTER_SHA"
  printf '  "max_per_board": %s,\n' "$MAX_PER_BOARD"
  printf '  "boards": [\n'
  first=1
  for row in "${SUMMARY[@]}"; do
    IFS='|' read -r label exp imp fetched exported ins upd unch note <<<"$row"
    [[ $first -eq 0 ]] && printf ',\n'
    first=0
    printf '    {"board":"%s","export":"%s","import":"%s","fetched":%s,"exported":%s,' \
      "$label" "$exp" "$imp" "${fetched:-0}" "${exported:-0}"
    printf '"inserted":%s,"updated":%s,"unchanged":%s,"note":"%s"}' \
      "${ins:-0}" "${upd:-0}" "${unch:-0}" "$note"
  done
  printf '\n  ]\n}\n'
} > "$record"
echo
echo "run record: $record"

[[ $FAILURES -eq 0 ]] || exit 1
exit 0
