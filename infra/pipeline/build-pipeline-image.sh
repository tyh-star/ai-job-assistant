#!/usr/bin/env bash
#
# Builds the Linux pipeline image from two repositories, pinning both.
#
#   ./infra/pipeline/build-pipeline-image.sh [--upstream <path>] [--tag <name>]
#
# Two repos, two versions, and reproducing a run means pinning both:
#
#   * the Go exporter comes from the FreeHire checkout (FREEHIRE_REPO or --upstream);
#     its HEAD is compiled and stamped into /opt/aijob/EXPORTER_SHA
#   * the Java importer comes from this repository; its HEAD is stamped into
#     /opt/aijob/IMPORTER_SHA
#
# Both are printed at build time and read back by run-pipeline.sh at run time, and
# AIJOB_EXPECTED_EXPORTER_SHA makes a run refuse an image built from the wrong exporter.
# A checkout with uncommitted changes is called out: the SHA then does not describe what
# was actually compiled.
#
# Docker builds have one context, so the Go binary is compiled first in its own container
# (with the upstream repo as context) and staged into .pipeline-build/ for the image build
# to COPY. .pipeline-build/ is git-ignored; it holds artifacts, not source.

set -euo pipefail

TAG="aijob-pipeline:local"
UPSTREAM="${FREEHIRE_REPO:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --upstream) UPSTREAM="$2"; shift 2 ;;
    --tag)      TAG="$2";      shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA_REPO="$(cd "$HERE/../.." && pwd)"

if [[ -z "$UPSTREAM" ]]; then
  guess="$(dirname "$JAVA_REPO")/freehire-upstream"
  [[ -d "$guess" ]] && UPSTREAM="$guess"
fi
[[ -n "$UPSTREAM" && -f "$UPSTREAM/go.mod" ]] || {
  echo "No FreeHire checkout. Pass --upstream <path> or set FREEHIRE_REPO." >&2; exit 1; }

EXPORTER_SHA="$(git -C "$UPSTREAM" rev-parse HEAD)"
EXPORTER_DIRTY="$(git -C "$UPSTREAM" status --porcelain | wc -l | tr -d ' ')"
IMPORTER_SHA="$(git -C "$JAVA_REPO" rev-parse HEAD)"
IMPORTER_DIRTY="$(git -C "$JAVA_REPO" status --porcelain --untracked-files=no | wc -l | tr -d ' ')"

echo "exporter (go)   : $EXPORTER_SHA  ($EXPORTER_DIRTY uncommitted)"
echo "importer (java) : $IMPORTER_SHA  ($IMPORTER_DIRTY uncommitted)"
[[ "$EXPORTER_DIRTY" == "0" ]] || echo "WARNING: the exporter checkout is dirty; EXPORTER_SHA will not describe what was compiled."
[[ "$IMPORTER_DIRTY" == "0" ]] || echo "WARNING: this repository is dirty; IMPORTER_SHA will not describe what was compiled."

STAGE="$JAVA_REPO/.pipeline-build"
rm -rf "$STAGE"
mkdir -p "$STAGE/testdata"

echo
echo "=== 1/2 compiling jdexport for linux/amd64 ==="

# Git Bash (MSYS) rewrites arguments that look like absolute Unix paths into Windows
# paths before the process sees them, which turns `-w /src` into `C:/Program Files/Git/src`
# and hands the daemon a path that does not exist. MSYS_NO_PATHCONV stops the rewriting;
# the mount sources still have to be Windows paths, which cygpath supplies.
#
# Both are no-ops on Linux, where this script is meant to run.
DOCKER_ENV=()
MOUNT_UPSTREAM="$UPSTREAM"
MOUNT_STAGE="$STAGE"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    export MSYS_NO_PATHCONV=1
    MOUNT_UPSTREAM="$(cygpath -w "$UPSTREAM")"
    MOUNT_STAGE="$(cygpath -w "$STAGE")"
    ;;
esac

# CGO off so the binary runs on a JRE base image without a matching libc.
docker run --rm \
  -v "$MOUNT_UPSTREAM:/src:ro" \
  -v "$MOUNT_STAGE:/out" \
  -e CGO_ENABLED=0 -e GOOS=linux -e GOARCH=amd64 \
  -e GOFLAGS=-buildvcs=false \
  -w /src \
  golang:1.25 \
  go build -o /out/jdexport ./cmd/jdexport

cp "$UPSTREAM/cmd/jdexport/testdata/"*.json "$STAGE/testdata/"
printf '%s\n' "$EXPORTER_SHA" > "$STAGE/EXPORTER_SHA"
printf '%s\n' "$IMPORTER_SHA" > "$STAGE/IMPORTER_SHA"
echo "staged: $(ls "$STAGE" | tr '\n' ' ')"

echo
echo "=== 2/2 building $TAG ==="
# Same MSYS path caveat as the Go build above: the context and the -f path both have to
# be Windows paths there, and are unchanged on Linux.
BUILD_CONTEXT="$JAVA_REPO"
DOCKERFILE="$JAVA_REPO/infra/pipeline/Dockerfile.pipeline"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    BUILD_CONTEXT="$(cygpath -w "$JAVA_REPO")"
    DOCKERFILE="$(cygpath -w "$DOCKERFILE")"
    ;;
esac
docker build -f "$DOCKERFILE" -t "$TAG" "$BUILD_CONTEXT"

echo
echo "built $TAG"
echo "  exporter $EXPORTER_SHA"
echo "  importer $IMPORTER_SHA"
