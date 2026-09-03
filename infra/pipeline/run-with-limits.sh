#!/usr/bin/env bash
#
# Runs the pipeline under a memory and CPU cap, sampling both it and MySQL throughout.
#
#   ./infra/pipeline/run-with-limits.sh --live --allowlist=/opt/aijob/config/....json
#
# The cap is the point: an unbounded container tells you nothing about whether this fits
# on a small host. AIJOB_MEM_LIMIT / AIJOB_CPU_LIMIT default to 1g and 1 cpu.
#
# It changes NOTHING globally: no Docker Desktop setting, no MySQL container config. The
# limit is an argument to this one `docker run`.
#
# Three different numbers get confused constantly, so all three are reported separately:
#   * the JVM's max heap  -- what Java will let itself allocate
#   * the container's RSS -- what the cgroup actually accounts to this container
#   * the whole stack     -- this container PLUS MySQL, which is what a host must fit
#
# An OOM kill is reported and STOPS the run. It is never worked around by raising the cap.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE="${AIJOB_PIPELINE_IMAGE:-aijob-pipeline:local}"
NETWORK="${AIJOB_TEST_NETWORK:-aijob-mysqltest_default}"
STATE_VOLUME="${AIJOB_STATE_VOLUME:-aijob-pilot-state}"
ENV_FILE="${AIJOB_CONTAINER_ENV:-/tmp/pilot.env}"
MEM_LIMIT="${AIJOB_MEM_LIMIT:-1g}"
CPU_LIMIT="${AIJOB_CPU_LIMIT:-1}"
SAMPLE_EVERY="${AIJOB_SAMPLE_SECONDS:-5}"
NAME="aijob-limited-$$"
SAMPLES="${AIJOB_SAMPLE_FILE:-/tmp/aijob-samples.tsv}"

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export MSYS_NO_PATHCONV=1 ;; esac
MOUNT_ENV="$ENV_FILE"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) MOUNT_ENV="$(cygpath -w "$ENV_FILE")" ;; esac

echo "image      : $IMAGE"
echo "limits     : memory=$MEM_LIMIT cpus=$CPU_LIMIT   (this container only)"
echo "state      : $STATE_VOLUME"
echo "samples    : $SAMPLES every ${SAMPLE_EVERY}s"
echo

printf 'ts\tcontainer\tcpu_pct\tmem_used\tmem_limit\tmem_pct\n' > "$SAMPLES"

docker run -d --rm --name "$NAME" \
  --memory="$MEM_LIMIT" --memory-swap="$MEM_LIMIT" --cpus="$CPU_LIMIT" \
  --network "$NETWORK" \
  --env-file "$MOUNT_ENV" \
  -v "$STATE_VOLUME:/var/lib/aijob" \
  ${AIJOB_EXTRA_ENV:-} \
  "$IMAGE" "$@" >/dev/null || { echo "could not start the container"; exit 1; }

# --memory-swap equal to --memory disables swap for this container: a run that only
# "fits" by swapping has not shown that it fits.

START=$(date -u +%s)
MYSQL_NAME="$(docker ps --filter "name=mysql-test" --format '{{.Names}}' | head -1)"

# Wait for the container to actually appear before polling for it.
#
# `docker run -d` returns once the daemon has accepted the request, which is not the same
# instant the container shows up in `docker ps`. The sampling loop below exits as soon as
# it does not see the name -- so on a slow start it saw nothing, ended immediately, and
# reported "no samples captured, wall clock 1s" for a run that was in fact under way and
# kept going unattended. Losing the measurement is bad; reporting a 1-second run that was
# still running is worse.
for _ in $(seq 1 30); do
  docker ps --filter "name=$NAME" --format '{{.Names}}' | grep -q "$NAME" && break
  sleep 1
done
if ! docker ps --filter "name=$NAME" --format '{{.Names}}' | grep -q "$NAME"; then
  echo "the container did not appear within 30s; not sampling. Check: docker ps -a --filter name=$NAME" >&2
  exit 1
fi

while docker ps --filter "name=$NAME" --format '{{.Names}}' | grep -q "$NAME"; do
  now=$(date -u +%s)
  docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' \
      "$NAME" ${MYSQL_NAME:+"$MYSQL_NAME"} 2>/dev/null |
    while IFS=$'\t' read -r n cpu mem pct; do
      used="${mem%% /*}"; lim="${mem##*/ }"
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$((now - START))" "$n" "$cpu" "$used" "$lim" "$pct" >> "$SAMPLES"
    done
  sleep "$SAMPLE_EVERY"
done

# The container is gone by now (--rm), so its exit code has to come from the wait that
# was watching it. Re-read from the logs it left in this shell instead.
END=$(date -u +%s)
ELAPSED=$((END - START))

echo
echo "=== resource samples ==="
awk -F'\t' 'NR>1 && $2 ~ /aijob-limited/ {
    gsub(/%/,"",$3); if ($3+0 > maxcpu) maxcpu = $3+0;
    m=$4; v=m+0; if (m ~ /GiB/) v*=1024; if (m ~ /KiB/) v/=1024;
    if (v > maxmem) maxmem = v; n++
  }
  END { if (n) printf "  pipeline container : peak CPU %.1f%%  peak memory %.0f MiB  (%d samples)\n", maxcpu, maxmem, n
        else print "  pipeline container : no samples captured" }' "$SAMPLES"
awk -F'\t' 'NR>1 && $2 ~ /mysql/ {
    gsub(/%/,"",$3); if ($3+0 > maxcpu) maxcpu = $3+0;
    m=$4; v=m+0; if (m ~ /GiB/) v*=1024; if (m ~ /KiB/) v/=1024;
    if (v > maxmem) maxmem = v; n++
  }
  END { if (n) printf "  mysql container    : peak CPU %.1f%%  peak memory %.0f MiB  (%d samples)\n", maxcpu, maxmem, n
        else print "  mysql container    : no samples captured" }' "$SAMPLES"
echo "  wall clock         : ${ELAPSED}s"
echo
echo "(peak memory above is the container's accounted usage, NOT the JVM heap setting and"
echo " NOT the whole stack. A host has to fit both containers plus its own overhead.)"
