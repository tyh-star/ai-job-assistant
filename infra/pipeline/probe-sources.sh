#!/usr/bin/env bash
#
# Checks candidate boards before they are added to an allowlist.
#
#   ./infra/pipeline/probe-sources.sh candidates.tsv
#
# Each line of the TSV is: provider <TAB> board <TAB> company
#
# Being listed in upstream's sources/*.yml means someone once found the board; it does
# not mean it answers today, and robots permitting a path is not the same as having a
# right to everything behind it. This asks the two questions a machine can answer --
# does it respond, and does robots.txt allow this path -- and records them. The rest,
# terms of use and whether collecting is appropriate at all, is a human judgement that
# goes in the allowlist's `evidence` field.
#
# One request per board, single concurrency, spaced. -max 1 keeps the probe small; the
# manifest still reports what the whole board holds.
#
# Every request it makes is counted and printed, including the robots.txt preflight,
# because a probe that under-reports its own traffic is not a polite one.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE="${AIJOB_PIPELINE_IMAGE:-aijob-pipeline:local}"
INTERVAL="${AIJOB_PROBE_INTERVAL:-30}"
CANDIDATES="${1:?usage: probe-sources.sh <candidates.tsv>}"

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export MSYS_NO_PATHCONV=1 ;; esac

OUT="$(mktemp -d)"
MOUNT_OUT="$OUT"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) MOUNT_OUT="$(cygpath -w "$OUT")" ;; esac

total_requests=0
printf '%-12s %-14s %-26s %-9s %-8s %-7s %s\n' PROVIDER BOARD COMPANY STATUS ROBOTS JOBS NOTE
printf '%.0s-' {1..110}; echo

first=1
while IFS=$'\t' read -r provider board company; do
  [[ -z "${provider:-}" || "$provider" == \#* ]] && continue

  if [[ $first -eq 0 ]]; then sleep "$INTERVAL"; fi
  first=0

  out="$OUT/$provider-$board.jsonl"
  log="$OUT/$provider-$board.log"
  docker run --rm -v "$MOUNT_OUT:/out" --entrypoint /opt/aijob/jdexport "$IMAGE" \
    -live -provider "$provider" -board "$board" -company "$company" \
    -max 1 -timeout 60s -out "/out/$provider-$board.jsonl" > "$log" 2>&1
  code=$?

  manifest="$OUT/$provider-$board.manifest.json"
  if [[ -f "$manifest" ]]; then
    status="$(jq -r '.status' "$manifest")"
    robots="$(jq -r 'if .robots.fetched then (if .robots.allowed then "allow" else "DENY" end) else "unread" end' "$manifest")"
    jobs="$(jq -r '.fetched_count' "$manifest")"
    reqs="$(jq -r '.requests | length' "$manifest")"
    note="$(jq -r '.error // ""' "$manifest" | cut -c1-40)"
    total_requests=$(( total_requests + reqs ))
  else
    status="no-manifest"; robots="?"; jobs="?"; reqs=0
    note="$(tail -1 "$log" | cut -c1-40)"
  fi
  [[ $code -ne 0 && -z "$note" ]] && note="exit $code"

  printf '%-12s %-14s %-26s %-9s %-8s %-7s %s\n' \
    "$provider" "$board" "$(cut -c1-26 <<<"$company")" "$status" "$robots" "$jobs" "$note"
done < "$CANDIDATES"

echo
echo "total HTTP requests made by this probe (robots preflights included): $total_requests"
echo "artifacts: $OUT"
