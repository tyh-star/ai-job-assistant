#!/usr/bin/env sh
#
# Replays stored batch artifacts through the Java importer. Runs INSIDE the pipeline
# image; the caller supplies the database through the environment.
#
# The Linux entry point for a pilot restore, and what replay-to-pilot.ps1 drives on
# Windows. Both choose the target explicitly: this script reads SPRING_DATASOURCE_* and
# has no default, so a missing configuration fails at the first import rather than
# quietly landing real postings somewhere else.
#
#   ONLY_RUN=<run-id>   replay one run instead of all of them
#
# Runs are replayed OLDEST FIRST. The importer updates a posting when its content
# changed and has no idea which batch is newer, so replaying newest-first would let a
# stale copy overwrite a fresher one.
#
# Fixture runs are skipped: synthetic postings belong in the test schema.
#
# Nothing here expires, closes or deletes -- every import is a SAMPLE_IMPORT.

set -u

B="${AIJOB_BATCH_ROOT:-/var/lib/aijob/batches}"
RUNS=""

if [ -n "${ONLY_RUN:-}" ]; then
    RUNS="$B/$ONLY_RUN/"
else
    # Directory names are UTC timestamps, so a lexical sort is chronological.
    for d in $(ls -1d "$B"/*/ 2>/dev/null | sort); do
        [ -f "$d/pipeline-run.json" ] || continue
        mode=$(jq -r '.mode // ""' "$d/pipeline-run.json" 2>/dev/null)
        if [ "$mode" != "live" ]; then
            echo "  skipping $(basename "$d"): mode=$mode (fixture data stays out of the pilot schema)"
            continue
        fi
        if ! find "$d" -name batch.jsonl -print 2>/dev/null | grep -q .; then
            continue
        fi
        RUNS="$RUNS $d"
    done
fi

total_read=0; total_ins=0; total_upd=0; total_unch=0; total_rej=0; total_batches=0; total_expired=0

for d in $RUNS; do
    echo "=== run $(basename "$d") ==="
    for b in "$d"*/; do
        [ -f "$b/batch.jsonl" ] || continue
        board=$(basename "$b")
        out=$(java -jar /opt/aijob/app.jar \
            --aijob.import.jsonl="$b/batch.jsonl" \
            --aijob.import.manifest="$b/batch.manifest.json" 2>&1)
        code=$?
        line=$(echo "$out" | grep -oE 'read=[0-9]+ inserted=[0-9]+ updated=[0-9]+ unchanged=[0-9]+ failed=[0-9]+ expired=[0-9]+' | tail -1)
        if [ -z "$line" ]; then
            reason=$(echo "$out" | grep -oE '(Batch refused before any write|Import skipped|Import failed): .*' | tail -1)
            echo "  $board  REJECTED (exit $code) ${reason:-no counters in output}"
            total_rej=$((total_rej + 1))
            continue
        fi
        r=$(echo "$line" | sed -E 's/.*read=([0-9]+).*/\1/')
        i=$(echo "$line" | sed -E 's/.*inserted=([0-9]+).*/\1/')
        u=$(echo "$line" | sed -E 's/.*updated=([0-9]+).*/\1/')
        n=$(echo "$line" | sed -E 's/.*unchanged=([0-9]+).*/\1/')
        e=$(echo "$line" | sed -E 's/.*expired=([0-9]+).*/\1/')
        printf '  %-32s read=%-5s ins=%-5s upd=%-4s unch=%-5s expired=%s\n' "$board" "$r" "$i" "$u" "$n" "$e"
        total_read=$((total_read + r)); total_ins=$((total_ins + i))
        total_upd=$((total_upd + u)); total_unch=$((total_unch + n))
        total_expired=$((total_expired + e)); total_batches=$((total_batches + 1))
    done
done

echo
echo "=== totals ==="
echo "  batches=$total_batches read=$total_read inserted=$total_ins updated=$total_upd unchanged=$total_unch rejected=$total_rej expired=$total_expired"
[ "$total_expired" -eq 0 ] || { echo "  UNEXPECTED: an import reported an expiry"; exit 1; }
exit 0
