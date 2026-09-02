#!/usr/bin/env bash
#
# Ordered release for the job catalog change.
#
# The order below is not cosmetic. `collector` and the catalog routes read tables that
# do not exist on an already-populated production volume until the migration is
# applied by hand (MySQL only runs mysql-init on an EMPTY volume). Starting them first
# makes the collector's heartbeat fail, which makes its healthcheck fail, which makes
# a `--wait` deploy time out. So: migrate on a running MySQL, verify the structure,
# and only then start the services that depend on it.
#
#   1. verify the release commit
#   2. back up database + uploads
#   3. validate the Compose configuration
#   4. tag the currently running images so they stay addressable for rollback
#   5. build backend, web and collector WITHOUT starting anything
#   6. start MySQL only
#   7. apply the migration, then verify the resulting structure
#   8. start backend, then web
#   9. start collector
#  10. health checks and a read-only smoke test
#
# Usage: ./infra/aws/release.sh <expected-git-sha>
#
# Never runs `down -v`. Never deletes a volume. Never rebuilds over a rollback tag.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="${ROOT}/infra/aws/docker-compose.ec2.yml"
ENV_FILE="${ROOT}/infra/aws/.env.aws"
PROJECT="aijob"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RELEASE_DIR="${ROOT}/infra/aws/releases"
RECORD="${RELEASE_DIR}/${STAMP}.env"

compose() {
  docker compose -p "${PROJECT}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

step() { printf '\n=== %s ===\n' "$1"; }

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <expected-git-sha>" >&2
  echo "Pass the full commit SHA you reviewed and intend to ship." >&2
  exit 2
fi
EXPECTED_SHA="$1"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}." >&2
  exit 2
fi

cd "${ROOT}"

step "1/10 Verify the release commit"
ACTUAL_SHA="$(git rev-parse HEAD)"
if [[ "${ACTUAL_SHA}" != "${EXPECTED_SHA}" ]]; then
  echo "Checked out ${ACTUAL_SHA}, expected ${EXPECTED_SHA}." >&2
  echo "Check out the reviewed commit before releasing. Not doing it for you." >&2
  exit 1
fi
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "The working tree has uncommitted changes; release only a clean, reviewed tree." >&2
  git status --short --untracked-files=no >&2
  exit 1
fi
echo "Releasing ${ACTUAL_SHA} on branch $(git rev-parse --abbrev-ref HEAD)."

step "2/10 Back up database and uploads"
./infra/aws/backup.sh

step "3/10 Validate the Compose configuration"
compose config --quiet
echo "Compose configuration is valid."

step "4/10 Record the currently running images for rollback"
mkdir -p "${RELEASE_DIR}"
{
  echo "# Images that were serving traffic before the ${STAMP} release of ${ACTUAL_SHA}."
  echo "# Feed this file to infra/aws/rollback.sh to go back without rebuilding."
} > "${RECORD}"
for service in backend web collector; do
  container="$(compose ps -q "${service}" || true)"
  if [[ -z "${container}" ]]; then
    echo "No running ${service}; nothing to tag (first release?)."
    continue
  fi
  image_id="$(docker inspect --format '{{.Image}}' "${container}")"
  rollback_tag="aijob-${service}:rollback-${STAMP}"
  # Tagging by image ID: the new build reuses the ":current" tag, and without this the
  # previous image would become untagged and be a `docker image prune` away from gone.
  docker tag "${image_id}" "${rollback_tag}"
  case "${service}" in
    backend)   echo "AIJOB_BACKEND_IMAGE=${rollback_tag}" >> "${RECORD}" ;;
    web)       echo "AIJOB_WEB_IMAGE=${rollback_tag}" >> "${RECORD}" ;;
    collector) echo "AIJOB_COLLECTOR_IMAGE=${rollback_tag}" >> "${RECORD}" ;;
  esac
  echo "${service}: ${image_id} tagged ${rollback_tag}"
done
echo "Rollback record: ${RECORD}"

step "5/10 Build backend, web and collector (no containers started)"
compose build backend web collector

step "6/10 Start MySQL only"
compose up -d --no-deps --wait --wait-timeout 180 mysql

step "7/10 Apply the migrations and verify the structure"
# In order. Each file gates on its own structure before recording completion, and each
# is re-runnable, so a re-release re-applies them harmlessly.
./infra/aws/db-apply.sh schema/job_catalog_phase1.sql
# Makes job_source_records.external_job_id case-sensitive. CREATE TABLE IF NOT EXISTS
# cannot fix a database that already has the table, so an existing deployment only gets
# this through the ALTER in here.
./infra/aws/db-apply.sh schema/job_catalog_external_id_exact_identity.sql
# And on to utf8mb4_0900_bin: the collation above is binary but PAD SPACE, so it
# still folds "REQ-A" with "REQ-A ". Separate version, separate file, applied after.
./infra/aws/db-apply.sh schema/job_catalog_external_id_no_pad.sql
./infra/aws/db-apply.sh schema/job_catalog_phase1_verify.sql
echo "Schema structure verified."

step "8/10 Start backend, then web"
compose up -d --no-deps --no-build --force-recreate --wait --wait-timeout 300 backend
compose up -d --no-deps --no-build --force-recreate --wait --wait-timeout 300 web

step "9/10 Start collector"
compose up -d --no-deps --no-build --force-recreate --wait --wait-timeout 300 collector

step "10/10 Health checks and smoke"
compose ps
compose ps -aq | xargs -r docker inspect \
  --format '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}'

PUBLIC_HOST="$(grep -E '^PUBLIC_HOST=' "${ENV_FILE}" | cut -d= -f2- || true)"
if [[ -n "${PUBLIC_HOST}" ]]; then
  ./infra/aws/smoke.sh "${PUBLIC_HOST}"
else
  echo "PUBLIC_HOST is not set in ${ENV_FILE}; skipping the public smoke test." >&2
fi

cat <<EOF

Release ${STAMP} of ${ACTUAL_SHA} is up.

Still manual, on purpose:
  * JOB_COLLECTOR_ENABLED stays false until you have reviewed a board's terms and
    done one controlled run (see infra/aws/README.md).
  * JOB_SEARCH_PRIMARY_PROVIDER decides which provider the legacy /jobs/search route
    uses; the catalog page is served either way.

To roll back:  ./infra/aws/rollback.sh ${RECORD}
EOF
