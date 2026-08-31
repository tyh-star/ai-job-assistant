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

# Recreate containers from the current Compose file. Named volumes are kept.
# Never use: docker compose down -v
if ! compose up -d --build --force-recreate --wait --wait-timeout 300; then
  print_diagnose
  exit 1
fi

compose ps
