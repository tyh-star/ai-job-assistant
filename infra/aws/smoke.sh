#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <public-host>   or set PUBLIC_HOST" >&2
  echo "Example: $0 203.0.113.10.sslip.io" >&2
  exit 1
}

HOST="${1:-${PUBLIC_HOST:-}}"
if [[ -z "${HOST}" ]]; then
  usage
fi

HOST="${HOST#https://}"
HOST="${HOST#http://}"
HOST="${HOST%/}"
BASE="https://${HOST}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

echo "Public smoke against ${BASE} (read-only, no account creation)"

home_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 "${BASE}/")"
[[ "${home_code}" == "200" ]] || fail "GET / expected 200, got ${home_code}"

register_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 \
  -X POST -H 'Content-Type: application/json' -d '{}' \
  "${BASE}/api/v1/auth/register")"
[[ "${register_code}" == "400" ]] || fail "POST /api/v1/auth/register {} expected 400, got ${register_code}"

login_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 \
  -X POST -H 'Content-Type: application/json' -d '{}' \
  "${BASE}/api/v1/auth/login")"
[[ "${login_code}" == "400" ]] || fail "POST /api/v1/auth/login {} expected 400, got ${login_code}"

protected_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 \
  "${BASE}/api/v1/resumes")"
if [[ "${protected_code}" != "401" && "${protected_code}" != "403" ]]; then
  fail "GET /api/v1/resumes without JWT expected 401 or 403, got ${protected_code}"
fi

# TLS verification is implicit: curl -sS fails if the certificate is invalid.
echo "OK ${BASE} home=${home_code} register=${register_code} login=${login_code} protected=${protected_code}"
