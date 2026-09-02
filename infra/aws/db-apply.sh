#!/usr/bin/env bash
#
# Runs one .sql file against the running MySQL container, safely.
#
# Why this exists rather than a one-line `docker compose exec ... mysql -u... -p...`:
#
#   1. `--env-file` feeds Compose's own variable interpolation. It does NOT export
#      anything into the shell you typed the command in, so `-u"${MYSQL_USER}"` on the
#      host expands to an empty string and the connection fails to authenticate.
#      The credentials only exist inside the mysql container, so the command that uses
#      them has to run there.
#
#   2. `-p"$MYSQL_PASSWORD"` puts the password in the mysql client's argv, where any
#      other process in that container can read it from /proc. Passing it through a
#      0600 option file instead keeps it out of the process list. This is MySQL's own
#      recommended mechanism; it is not perfect (a root shell inside the container can
#      still read the file while it exists) but it removes the argv exposure and the
#      file is deleted when the command finishes.
#
# The password is never echoed, never written to the repo or an image, and the script
# never enables shell tracing.
#
# Usage: ./infra/aws/db-apply.sh schema/job_catalog_phase1.sql

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="${ROOT}/infra/aws/docker-compose.ec2.yml"
ENV_FILE="${ROOT}/infra/aws/.env.aws"
PROJECT="aijob"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <path-to-sql-file>" >&2
  exit 2
fi

SQL_FILE="$1"
if [[ ! -f "${SQL_FILE}" ]]; then
  echo "No such SQL file: ${SQL_FILE}" >&2
  exit 2
fi
if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}. Copy infra/aws/.env.aws.example and fill it in on the server." >&2
  exit 2
fi

echo "Applying ${SQL_FILE} to the ${PROJECT} database..."

# The single quotes matter: every $VAR below is resolved by the shell INSIDE the mysql
# container, from that container's own environment, not by this shell.
docker compose -p "${PROJECT}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T mysql \
  sh -c '
    set -eu
    umask 077
    cnf="$(mktemp)"
    trap "rm -f \"$cnf\"" EXIT INT TERM
    printf "[client]\nuser=%s\npassword=%s\n" "$MYSQL_USER" "$MYSQL_PASSWORD" > "$cnf"
    mysql --defaults-extra-file="$cnf" --batch --table "$MYSQL_DATABASE"
  ' < "${SQL_FILE}"

echo "OK: ${SQL_FILE} applied and its own checks passed."
