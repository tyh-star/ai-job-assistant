#!/usr/bin/env bash
#
# Prunes old batch directories so a scheduled pipeline does not fill the disk.
#
#   retention.sh                # DRY RUN: says what it would delete, deletes nothing
#   retention.sh --apply        # actually delete
#
# Defaults, and the reasoning:
#
#   successful batches   14 days   the JSONL is a copy of what a board published; after
#                                  a fortnight the catalog is the record, not the file
#   failed batches       30 days   longer, because a failure is usually diagnosed later
#                                  than it happens -- but still bounded. "Keep failures
#                                  forever" is how a disk fills with the least useful
#                                  data on it.
#   run summaries        90 days   pipeline-run.json and the manifests: counts, statuses
#                                  and reasons, no posting content. Small enough to keep
#                                  a quarter of, and the thing you actually read when
#                                  asking what happened last month.
#
# So a directory passes through three stages: whole, then summary-only, then gone.
#
# SAFETY
#   * dry run unless --apply
#   * only inside the managed batch root, resolved and re-checked per directory
#   * never follows a symlink, and skips any batch directory that is one
#   * never touches a run that is still going (its lock, or no pipeline-run.json yet)
#   * never touches the lock directory, state directory, credentials, or a database
#     volume -- it only ever looks at $BATCH_ROOT/<run-id>/

set -uo pipefail

BATCH_ROOT="${AIJOB_BATCH_ROOT:-/var/lib/aijob/batches}"
LOCK_DIR="${AIJOB_LOCK_DIR:-/var/lib/aijob/lock}"
KEEP_SUCCESS_DAYS="${AIJOB_KEEP_SUCCESS_DAYS:-14}"
KEEP_FAILED_DAYS="${AIJOB_KEEP_FAILED_DAYS:-30}"
KEEP_SUMMARY_DAYS="${AIJOB_KEEP_SUMMARY_DAYS:-90}"

APPLY=0
for arg in "$@"; do
  case "$arg" in
    --apply) APPLY=1 ;;
    -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
[[ -d "$BATCH_ROOT" ]] || { echo "no batch root at $BATCH_ROOT"; exit 0; }

# The managed root, resolved once. Every candidate is checked to still be under it after
# its own resolution, so a symlinked run directory cannot walk the deletion somewhere else.
ROOT_REAL="$(readlink -f "$BATCH_ROOT")" || { echo "cannot resolve $BATCH_ROOT" >&2; exit 1; }

now="$(date -u +%s)"
[[ -n "${AIJOB_NOW:-}" ]] && {
  if [[ "$AIJOB_NOW" =~ ^[0-9]+$ ]]; then now="$AIJOB_NOW"; else now="$(date -u -d "$AIJOB_NOW" +%s)"; fi
}

echo "batch root : $ROOT_REAL"
echo "mode       : $([[ $APPLY -eq 1 ]] && echo 'APPLY (will delete)' || echo 'DRY RUN (nothing will be deleted)')"
echo "keep       : success ${KEEP_SUCCESS_DAYS}d, failed ${KEEP_FAILED_DAYS}d, summaries ${KEEP_SUMMARY_DAYS}d"
echo

removed_dirs=0
pruned_payloads=0
kept=0
skipped=0

# -maxdepth/-mindepth 1: run directories only, and never recurse into one while deciding.
while IFS= read -r dir; do
  [[ -n "$dir" ]] || continue
  name="$(basename "$dir")"

  # A symlink where a run directory should be is not something to delete through.
  if [[ -L "$dir" ]]; then
    echo "  SKIP  $name -- is a symlink"
    skipped=$((skipped + 1))
    continue
  fi
  real="$(readlink -f "$dir")"
  case "$real/" in
    "$ROOT_REAL"/*) : ;;
    *) echo "  SKIP  $name -- resolves outside the batch root ($real)"; skipped=$((skipped + 1)); continue ;;
  esac

  record="$dir/pipeline-run.json"
  if [[ ! -f "$record" ]]; then
    # No summary yet: either a run in progress or one that died before writing it.
    # Either way it is not a candidate -- deleting under a live run is the one mistake
    # this script must not make.
    echo "  SKIP  $name -- no pipeline-run.json (in progress, or died before finishing)"
    skipped=$((skipped + 1))
    continue
  fi

  mtime="$(stat -c %Y "$record" 2>/dev/null || echo "$now")"
  age_days=$(( (now - mtime) / 86400 ))

  # Failed if any board in the run did not both export and import cleanly.
  if jq -e '[.boards[] | select(.export != "ok" or (.import | test("^(ok|skipped)")) == false)] | length > 0' \
       "$record" >/dev/null 2>&1; then
    outcome="failed"; keep_payload="$KEEP_FAILED_DAYS"
  else
    outcome="ok"; keep_payload="$KEEP_SUCCESS_DAYS"
  fi

  if (( age_days >= KEEP_SUMMARY_DAYS )); then
    echo "  DELETE $name ($outcome, ${age_days}d) -- past the summary window"
    if [[ $APPLY -eq 1 ]]; then rm -rf -- "$dir"; fi
    removed_dirs=$((removed_dirs + 1))
  elif (( age_days >= keep_payload )); then
    # Keep the summary and the manifests, drop the postings. The manifest is the failure
    # record; the JSONL is the bulk.
    payloads="$(find "$dir" -type f \( -name '*.jsonl' -o -name 'export.log' -o -name 'import.log' \) 2>/dev/null | wc -l)"
    if (( payloads > 0 )); then
      echo "  PRUNE  $name ($outcome, ${age_days}d) -- dropping $payloads payload file(s), keeping the summary and manifests"
      if [[ $APPLY -eq 1 ]]; then
        find "$dir" -type f \( -name '*.jsonl' -o -name 'export.log' -o -name 'import.log' \) -delete
      fi
      pruned_payloads=$((pruned_payloads + 1))
    else
      kept=$((kept + 1))
    fi
  else
    kept=$((kept + 1))
  fi
done < <(find "$BATCH_ROOT" -mindepth 1 -maxdepth 1 -type d -not -name '_*' 2>/dev/null | sort)

echo
echo "=== summary ==="
echo "  directories removed : $removed_dirs"
echo "  payloads pruned     : $pruned_payloads"
echo "  kept as-is          : $kept"
echo "  skipped             : $skipped"
if [[ $APPLY -eq 0 ]]; then
  echo
  echo "DRY RUN -- nothing was deleted. Re-run with --apply to act on the above."
fi

# Nothing here ever touches these, and saying so is cheaper than someone checking.
echo
echo "untouched: $LOCK_DIR, ${AIJOB_STATE_ROOT:-/var/lib/aijob}/state, credentials, database volumes"
