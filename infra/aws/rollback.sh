#!/usr/bin/env bash
#
# Rolls back to the images that were running before a release, without rebuilding.
#
# What this is NOT: flipping JOB_SEARCH_PRIMARY_PROVIDER back to JSEARCH is not a
# rollback. That variable only changes which provider answers the legacy
# /api/v1/jobs/search route. The catalog search panel lives in the `web` image and
# calls /api/v1/job-catalog/*, which is served regardless of that setting, so a
# provider flip alone leaves users on the new UI reading a possibly empty catalog.
# A real rollback restores the web image and the backend image together.
#
# What is deliberately kept:
#   * job_catalog, job_source_records, job_ingest_runs, job_sources
#   * user_saved_catalog_jobs -- the mapping from a catalog posting to the personal
#     `jobs` row a user saved. Dropping it would orphan real user data.
# The new tables are additive, so an older backend simply ignores them.
#
# One compatibility check the older backend does need: personal jobs saved from the
# catalog carry source='JOB_CATALOG', a value the previous release never wrote. This
# script counts them and asks you to confirm, because whether the older UI renders an
# unknown source label gracefully is a property of the version you are going back to.
#
# Usage: ./infra/aws/rollback.sh infra/aws/releases/<stamp>.env

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="${ROOT}/infra/aws/docker-compose.ec2.yml"
ENV_FILE="${ROOT}/infra/aws/.env.aws"
PROJECT="aijob"

compose() {
  docker compose -p "${PROJECT}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <infra/aws/releases/<stamp>.env>" >&2
  echo "Available records:" >&2
  ls -1 "${ROOT}/infra/aws/releases" 2>/dev/null >&2 || echo "  (none)" >&2
  exit 2
fi

RECORD="$1"
[[ -f "${RECORD}" ]] || { echo "No such release record: ${RECORD}" >&2; exit 2; }
[[ -f "${ENV_FILE}" ]] || { echo "Missing ${ENV_FILE}" >&2; exit 2; }

cd "${ROOT}"

echo "=== Rolling back to the images recorded in ${RECORD} ==="
grep -E '^AIJOB_(BACKEND|WEB|COLLECTOR)_IMAGE=' "${RECORD}" || {
  echo "That record names no images; nothing to roll back to." >&2
  exit 1
}

# Every tag must still exist locally, or we would silently start something else.
missing=0
while IFS='=' read -r _ tag; do
  if ! docker image inspect "${tag}" >/dev/null 2>&1; then
    echo "Missing image ${tag} -- it was pruned. Cannot roll back to it." >&2
    missing=1
  fi
done < <(grep -E '^AIJOB_(BACKEND|WEB|COLLECTOR)_IMAGE=' "${RECORD}")
[[ ${missing} -eq 0 ]] || exit 1

echo
echo "=== 1/4 Stop collecting ==="
# Stopped rather than removed: the container and its logs stay available for
# diagnosis, and no volume is touched.
compose stop collector || true

echo
echo "=== 2/4 Personal jobs saved from the catalog ==="
COUNT_SQL="$(mktemp)"
trap 'rm -f "${COUNT_SQL}"' EXIT
cat > "${COUNT_SQL}" <<'SQL'
SELECT COUNT(*) AS personal_jobs_saved_from_catalog
  FROM jobs WHERE source = 'JOB_CATALOG';
SQL
./infra/aws/db-apply.sh "${COUNT_SQL}"
cat <<'EOF'
Those rows are ordinary personal jobs and are NOT deleted by this rollback. Confirm
that the release you are going back to renders an unknown `source` value without
erroring before you continue; if it does not, that version needs a fix rather than a
rollback.
EOF
read -r -p "Continue? [y/N] " answer
[[ "${answer}" == "y" || "${answer}" == "Y" ]] || { echo "Aborted; nothing changed."; exit 1; }

echo
echo "=== 3/4 Start the previous web and backend images ==="
# --no-build is what stops Compose from rebuilding the current source over the tag we
# are trying to go back to.
set -a
# shellcheck disable=SC1090
source "${RECORD}"
set +a
compose up -d --no-deps --no-build --force-recreate --wait --wait-timeout 300 backend
compose up -d --no-deps --no-build --force-recreate --wait --wait-timeout 300 web

echo
echo "=== 4/4 State ==="
compose ps
compose ps -aq | xargs -r docker inspect \
  --format '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}'

cat <<EOF

Rolled back backend and web to the images in ${RECORD}. The collector is stopped.

The catalog tables and user_saved_catalog_jobs were left untouched.

To make this persistent across a plain 'deploy', put these lines in ${ENV_FILE}:
$(grep -E '^AIJOB_(BACKEND|WEB|COLLECTOR)_IMAGE=' "${RECORD}")
and keep JOB_COLLECTOR_ENABLED=false. Remove them again when you roll forward.
EOF
