#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <public-host>   or set PUBLIC_HOST" >&2
  echo "Example: $0 203.0.113.10.sslip.io" >&2
  echo "Local check: SMOKE_BASE_URL=http://127.0.0.1:4173 SMOKE_API_BASE_URL=http://127.0.0.1:18080 $0" >&2
  exit 1
}

HOST="${1:-${PUBLIC_HOST:-}}"
if [[ -z "${HOST}" && -z "${SMOKE_BASE_URL:-}" ]]; then
  usage
fi

HOST="${HOST#https://}"
HOST="${HOST#http://}"
HOST="${HOST%/}"
# Production always checks https://<host> for both the site and the API. The two
# overrides exist only to run the same checks against a local build.
BASE="${SMOKE_BASE_URL:-https://${HOST}}"
BASE="${BASE%/}"
API_BASE="${SMOKE_API_BASE_URL:-${BASE}}"
API_BASE="${API_BASE%/}"

# A well-formed JWT that expired in 1970 and is not signed with this server's key.
# The backend must treat it like no credentials at all.
UNUSABLE_TOKEN="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzbW9rZUBleGFtcGxlLmNvbSIsImF1dGhWZXJzaW9uIjowLCJleHAiOjF9.c21va2Utbm90LWEtcmVhbC1zaWduYXR1cmU"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

echo "Public smoke against ${BASE} (API ${API_BASE}; read-only, no account creation)"

home_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 "${BASE}/")"
[[ "${home_code}" == "200" ]] || fail "GET / expected 200, got ${home_code}"

register_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 \
  -X POST -H 'Content-Type: application/json' -d '{}' \
  "${API_BASE}/api/v1/auth/register")"
[[ "${register_code}" == "400" ]] || fail "POST /api/v1/auth/register {} expected 400, got ${register_code}"

login_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 \
  -X POST -H 'Content-Type: application/json' -d '{}' \
  "${API_BASE}/api/v1/auth/login")"
[[ "${login_code}" == "400" ]] || fail "POST /api/v1/auth/login {} expected 400, got ${login_code}"

# Missing credentials are 401. 403 is reserved for a signed-in user who is not allowed,
# so a 403 here would make the website treat an expired login as a permission error.
protected_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 \
  "${API_BASE}/api/v1/resumes")"
[[ "${protected_code}" == "401" ]] || fail "GET /api/v1/resumes without JWT expected 401, got ${protected_code}"

unusable_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 \
  -H "Authorization: Bearer ${UNUSABLE_TOKEN}" \
  "${API_BASE}/api/v1/resumes")"
[[ "${unusable_code}" == "401" ]] || fail "GET /api/v1/resumes with an expired/forged JWT expected 401, got ${unusable_code}"

# Public routes keep serving a visitor even when a stale token is still attached.
public_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 \
  -H "Authorization: Bearer ${UNUSABLE_TOKEN}" \
  "${API_BASE}/api/v1/feedback/summary")"
[[ "${public_code}" == "200" ]] || fail "GET /api/v1/feedback/summary with a stale JWT expected 200, got ${public_code}"

# "Signed in but not allowed" (403) needs a real account, so it is covered by the
# backend tests (ApiAuthenticationStatusSecurityTest), not by this account-free smoke.

# TLS verification is implicit for https: curl -sS fails if the certificate is invalid.
echo "OK ${BASE} home=${home_code} register=${register_code} login=${login_code} protected=${protected_code} staleToken=${unusable_code} publicWithStaleToken=${public_code}"
