#!/usr/bin/env bash
# First SYS_ADMIN: grants English9x SYS_ADMIN to an existing, email-verified account, as the database owner, inside the
# running Compose stack. There is no HTTP endpoint and no default administrator account: operations seed the first one
# with this script, after that person has registered and verified their email through the normal screens.
#   ./scripts/grant-sys-admin.sh person@example.com
#   E9X_COMPOSE="-f infra/docker-compose.yml -f infra/docker-compose.e2e.yml" ./scripts/grant-sys-admin.sh person@example.com
# It prints the Docker context and the database it is about to write to. It writes only to a local Docker (the context
# "default", or Docker Desktop's own "desktop-linux", with no DOCKER_HOST) unless told otherwise:
#   ./scripts/grant-sys-admin.sh --allow-other-docker person@example.com
# Later administrators are invited by a SYS_ADMIN from the admin screens (Staff invitations).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
usage() { echo "usage: $0 [--allow-other-docker] <email of a verified account>" >&2; exit 2; }
allow_other=0
if [ "${1:-}" = "--allow-other-docker" ]; then allow_other=1; shift; fi
email="${1:-}"
[ $# -le 1 ] || usage
case "$email" in
  ""|-*|*[[:space:]]*|*"'"*|*\"*|*\;*|*\\*) usage ;;
  *@*) ;;
  *) usage ;;
esac

context="$(docker context show)"
endpoint="${DOCKER_HOST:-$(docker context inspect "$context" --format '{{.Endpoints.docker.Host}}')}"
echo "Docker context: $context ($endpoint)"
if [ "$allow_other" != 1 ]; then
  case "$context" in
    default|desktop-linux) other="${DOCKER_HOST:+DOCKER_HOST is set to $DOCKER_HOST}" ;;
    *) other="the active context is $context" ;;
  esac
  if [ -n "$other" ]; then
    echo "Refusing to write: $other. If this really is the target, pass --allow-other-docker." >&2
    exit 3
  fi
fi

# shellcheck disable=SC2086
compose() { docker compose ${E9X_COMPOSE:--f infra/docker-compose.yml} "$@"; }
database="$(compose exec -T postgres sh -c 'printf %s "${POSTGRES_DB:-e9x}"')"
echo "Database: $database, compose: ${E9X_COMPOSE:--f infra/docker-compose.yml}"
echo "Granting SYS_ADMIN to $email"
compose exec -T postgres \
  sh -c 'psql -q -U e9x_owner -d "${POSTGRES_DB:-e9x}" -v ON_ERROR_STOP=1 -v email="$1" -f -' sh "$email" < scripts/grant-sys-admin.sql
