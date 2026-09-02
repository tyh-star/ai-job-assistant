#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="${ROOT}/infra/aws/docker-compose.ec2.yml"
ENV_FILE="${ROOT}/infra/aws/.env.aws"
PROJECT="aijob"

compose() {
  docker compose -p "${PROJECT}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

print_diagnose() {
  cat >&2 <<'EOF'
Deploy failed. Containers were kept for ps/logs/inspect. Do not run down -v.
  docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml ps -a
  docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml logs --tail=200 mysql backend web
  docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml ps -aq | xargs -r docker inspect --format '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}'
EOF
}

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}. Copy infra/aws/.env.aws.example and fill values on the server." >&2
  exit 1
fi

cd "${ROOT}"

if ! compose config --quiet; then
  echo "Compose config is invalid." >&2
  print_diagnose
  exit 1
fi

# A plain deploy assumes the schema is already in place. `collector` and the catalog
# routes read tables that MySQL does NOT create on an existing volume (mysql-init only
# runs on an empty one), and starting them against a database without those tables
# just produces an unhealthy container and a --wait timeout with a confusing message.
#
# So: if MySQL is already up, check the structure first and refuse early with a useful
# pointer. Use infra/aws/release.sh for a release that includes the migration.
if compose ps --status running --services 2>/dev/null | grep -qx mysql; then
  if ! ./infra/aws/db-apply.sh schema/job_catalog_phase1_verify.sql; then
    cat >&2 <<'EOF'
The job catalog tables are missing or incomplete, so backend and collector would not
come up healthy. Nothing was started or changed.

Run the ordered release instead, which backs up, migrates and verifies before it
starts anything that needs those tables:

  ./infra/aws/release.sh <commit-sha>
EOF
    exit 1
  fi
fi

# Recreate containers from the current Compose file. Named volumes are kept.
# Never use: docker compose down -v
if ! compose up -d --build --force-recreate --wait --wait-timeout 300; then
  print_diagnose
  exit 1
fi

compose ps
