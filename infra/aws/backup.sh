#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="${ROOT}/infra/aws/docker-compose.ec2.yml"
ENV_FILE="${ROOT}/infra/aws/.env.aws"
PROJECT="aijob"
BACKUP_DIR="${BACKUP_DIR:-${ROOT}/backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}" >&2
  exit 1
fi

mkdir -p "${BACKUP_DIR}"
DUMP="${BACKUP_DIR}/aijob-${STAMP}.sql.gz"

# The password goes through a 0600 option file rather than argv, so it never appears
# in the container's process list. It is never echoed and never written to the repo.
docker compose -p "${PROJECT}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T mysql \
  sh -c '
    set -eu
    umask 077
    cnf="$(mktemp)"
    trap "rm -f \"$cnf\"" EXIT INT TERM
    printf "[client]\nuser=%s\npassword=%s\n" "$MYSQL_USER" "$MYSQL_PASSWORD" > "$cnf"
    mysqldump --defaults-extra-file="$cnf" --single-transaction --quick "$MYSQL_DATABASE"
  ' | gzip > "${DUMP}"

# `set -o pipefail` catches a failed mysqldump, but a dump that "succeeded" while
# writing almost nothing is the more dangerous case: it looks like a backup and
# restores an empty database. A real dump always ends with mysqldump's own trailer.
if ! gzip -dc "${DUMP}" | tail -5 | grep -q "Dump completed"; then
  echo "Backup looks truncated: ${DUMP} has no mysqldump completion marker." >&2
  echo "Keeping the file for inspection. Do NOT treat this as a usable backup." >&2
  exit 1
fi

VOLUME="${PROJECT}_uploads_data"
if docker volume inspect "${VOLUME}" >/dev/null 2>&1; then
  docker run --rm \
    -v "${VOLUME}:/data:ro" \
    -v "${BACKUP_DIR}:/backup" \
    busybox tar -C /data -czf "/backup/uploads-${STAMP}.tar.gz" .
fi

echo "Wrote database dump and uploads archive under ${BACKUP_DIR}"
