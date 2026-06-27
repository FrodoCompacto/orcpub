#!/usr/bin/env bash
# ===========================================================================
# VM deploy helper — full Docker stack behind an existing host nginx.
#
# Prerequisites on the VM:
#   - Docker Engine + Compose v2 plugin
#   - git clone in /opt/orcpub-app (or any directory)
#   - ~4 GB free RAM for first build
#
# Usage:
#   ./scripts/vm-deploy.sh setup     # .env, dirs, SSL certs (idempotent)
#   ./scripts/vm-deploy.sh build     # docker compose build (datomic + orcpub + web)
#   ./scripts/vm-deploy.sh up        # start all services
#   ./scripts/vm-deploy.sh status    # compose ps + health
#   ./scripts/vm-deploy.sh logs      # tail all logs
#   ./scripts/vm-deploy.sh init-user # create admin from INIT_ADMIN_* in .env
#   ./scripts/vm-deploy.sh all       # setup + build + up + wait + init-user hint
# ===========================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${ROOT}/.env"

cd "$ROOT"

color_green=$'\033[0;32m'
color_yellow=$'\033[1;33m'
color_red=$'\033[0;31m'
color_cyan=$'\033[0;36m'
color_reset=$'\033[0m'

info()  { printf '%s[INFO]%s  %s\n' "$color_green" "$color_reset" "$*"; }
warn()  { printf '%s[WARN]%s  %s\n' "$color_yellow" "$color_reset" "$*"; }
error() { printf '%s[ERROR]%s %s\n' "$color_red" "$color_reset" "$*" >&2; }

compose() {
  if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . <(tr -d '\r' < "$ENV_FILE")
    set +a
  fi
  docker compose "$@"
}

require_docker() {
  if ! docker compose version &>/dev/null; then
    error "Docker Compose v2 required. Install: https://docs.docker.com/engine/install/ubuntu/"
    exit 1
  fi
}

set_env_val() {
  local var="$1" val="$2" file="$3"
  if grep -q "^${var}=" "$file" 2>/dev/null; then
    awk -v var="$var" -v val="$val" '{
      if (index($0, var"=") == 1) print var"="val; else print
    }' "$file" > "${file}.tmp"
    mv "${file}.tmp" "$file"
  else
    echo "${var}=${val}" >> "$file"
  fi
}

apply_vm_env_overrides() {
  info "Applying VM port overrides to .env"
  set_env_val WEB_HTTP_PUBLISH "127.0.0.1:8880:80" "$ENV_FILE"
  set_env_val WEB_HTTPS_PUBLISH "127.0.0.1:8843:443" "$ENV_FILE"
  set_env_val COMPOSE_FILE "docker-compose.yaml:docker-compose.vm.yaml" "$ENV_FILE"
  set_env_val DEV_MODE "" "$ENV_FILE"
  set_env_val TZ "America/Sao_Paulo" "$ENV_FILE"
}

cmd_setup() {
  require_docker

  # .env.vm.example has no secrets — if user copied it manually, regenerate.
  if [ -f "$ENV_FILE" ] && ! grep -q '^DATOMIC_PASSWORD=.\+' "$ENV_FILE" 2>/dev/null; then
    warn ".env exists without DATOMIC_PASSWORD — will regenerate via ./run --auto"
    rm -f "$ENV_FILE"
  fi

  info "Generating secrets, directories, and SSL certs (./run --auto)"
  "${ROOT}/run" --auto

  apply_vm_env_overrides

  info "VM web ports (loopback only):"
  grep -E '^(WEB_|COMPOSE_FILE|DEV_MODE|TZ)' "$ENV_FILE" 2>/dev/null || true
}

cmd_build() {
  require_docker
  info "Building images (may take 10–20 min on first run)..."
  compose build
}

cmd_up() {
  require_docker
  compose up -d
  cmd_status
}

cmd_status() {
  require_docker
  compose ps
  echo
  info "Quick checks from this VM:"
  echo "  curl -sk https://127.0.0.1:8843/health"
  echo "  curl -sk https://127.0.0.1:8843/ | head"
}

cmd_logs() {
  require_docker
  compose logs -f --tail=100
}

cmd_init_user() {
  require_docker
  "${ROOT}/docker-user.sh" init
}

wait_healthy() {
  local tries=60
  info "Waiting for orcpub to become healthy (up to ~10 min)..."
  while [ "$tries" -gt 0 ]; do
    if compose ps orcpub 2>/dev/null | grep -q '(healthy)'; then
      info "orcpub is healthy"
      return 0
    fi
    tries=$((tries - 1))
    sleep 10
  done
  warn "orcpub not healthy yet — check: docker compose logs orcpub --tail 80"
  return 1
}

cmd_all() {
  cmd_setup
  cmd_build
  cmd_up
  wait_healthy || true
  echo
  info "Next: create your admin user"
  echo "  ./scripts/vm-deploy.sh init-user"
  echo "  — or set INIT_ADMIN_* in .env and run ./docker-user.sh init"
  echo
  info "Test in browser (SSH tunnel from your PC):"
  echo "  ssh -L 8843:127.0.0.1:8843 ubuntu@<vm-ip>"
  echo "  https://localhost:8843  (accept self-signed cert)"
}

usage() {
  cat <<EOF
Usage: $0 <command>

Commands:
  setup      Prepare .env, secrets, certs (./run --auto)
  build      docker compose build
  up         docker compose up -d
  status     Show service status and test URLs
  logs       Follow container logs
  init-user  Create admin via ./docker-user.sh init
  all        setup + build + up + wait for healthy
EOF
}

main() {
  local cmd="${1:-all}"
  case "$cmd" in
    setup)     cmd_setup ;;
    build)     cmd_build ;;
    up)        cmd_up ;;
    status)    cmd_status ;;
    logs)      cmd_logs ;;
    init-user) cmd_init_user ;;
    all)       cmd_all ;;
    -h|--help|help) usage ;;
    *) error "Unknown command: $cmd"; usage; exit 1 ;;
  esac
}

main "$@"
