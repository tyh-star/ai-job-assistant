#!/usr/bin/env bash
set -euo pipefail

# Ubuntu 24.04 LTS: Docker Engine + a read-only GitHub deploy key.
# Does not clone the application repo (the deploy key directory stays outside git).
# Does not print AWS keys, GitHub PATs, or application secrets.

APP_ROOT="${APP_ROOT:-/opt/ai-job-assistant}"
SSH_USER="${SSH_USER:-ubuntu}"
SSH_HOME="$(getent passwd "${SSH_USER}" | cut -d: -f6)"
if [[ -z "${SSH_HOME}" ]]; then
  SSH_USER=root
  SSH_HOME=/root
fi
DEPLOY_KEY="${DEPLOY_KEY:-${SSH_HOME}/.ssh/ai-job-assistant-deploy}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo bash infra/aws/ec2-bootstrap.sh" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl git openssh-client

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi

systemctl enable --now docker
if id "${SSH_USER}" >/dev/null 2>&1; then
  usermod -aG docker "${SSH_USER}"
fi

mkdir -p "${APP_ROOT}" "${SSH_HOME}/.ssh"
chmod 700 "${SSH_HOME}/.ssh"
chown "${SSH_USER}:${SSH_USER}" "${APP_ROOT}" "${SSH_HOME}/.ssh"

if [[ ! -f "${DEPLOY_KEY}" ]]; then
  sudo -u "${SSH_USER}" ssh-keygen -t ed25519 -f "${DEPLOY_KEY}" -N "" -C "ai-job-assistant-ec2-readonly"
  chmod 600 "${DEPLOY_KEY}"
  chown "${SSH_USER}:${SSH_USER}" "${DEPLOY_KEY}" "${DEPLOY_KEY}.pub"
fi

KNOWN_HOSTS="${SSH_HOME}/.ssh/known_hosts"
if [[ ! -f "${KNOWN_HOSTS}" ]] || ! grep -q github.com "${KNOWN_HOSTS}"; then
  ssh-keyscan -t ed25519 github.com >> "${KNOWN_HOSTS}"
  chown "${SSH_USER}:${SSH_USER}" "${KNOWN_HOSTS}"
fi

echo
echo "Bootstrap complete. Add this public key as a read-only GitHub deploy key:"
echo
cat "${DEPLOY_KEY}.pub"
echo
echo "Clone with:"
echo "  export GIT_SSH_COMMAND='ssh -i ${DEPLOY_KEY} -o IdentitiesOnly=yes'"
echo "  git clone -b deploy/aws-ec2-public git@github.com:tyh-star/ai-job-assistant.git ${APP_ROOT}"
