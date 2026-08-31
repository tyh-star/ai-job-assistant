#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="${ROOT}/infra/aws/docker-compose.ec2.yml"
ENV_FILE="${ROOT}/infra/aws/.env.aws"
PROJECT="${COMPOSE_PROJECT_NAME:-aijob}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}. Copy infra/aws/.env.aws.example and fill values on the server." >&2
  exit 1
fi

cd "${ROOT}"
docker compose -p "${PROJECT}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --build
docker compose -p "${PROJECT}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
