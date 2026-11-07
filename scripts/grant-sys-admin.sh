#!/usr/bin/env bash
# First SYS_ADMIN: grants English9x SYS_ADMIN to an existing, email-verified account, as the database owner, inside the
# running Compose stack. There is no HTTP endpoint and no default administrator account: operations seed the first one
# with this script, after that person has registered and verified their email through the normal screens.
#   ./scripts/grant-sys-admin.sh person@example.com
#   E9X_COMPOSE="-f infra/docker-compose.yml -f infra/docker-compose.e2e.yml" ./scripts/grant-sys-admin.sh person@example.com
# Later administrators are invited by a SYS_ADMIN from the admin screens (Staff invitations).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
email="${1:-}"
case "$email" in
  ""|*[[:space:]]*|*"'"*|*\"*|*\;*|*\\*) echo "usage: $0 <email of a verified account>" >&2; exit 2 ;;
  *@*) ;;
  *) echo "usage: $0 <email of a verified account>" >&2; exit 2 ;;
esac
# shellcheck disable=SC2086
docker compose ${E9X_COMPOSE:--f infra/docker-compose.yml} exec -T postgres \
  sh -c 'psql -q -U e9x_owner -d "${POSTGRES_DB:-e9x}" -v ON_ERROR_STOP=1 -v email="$1" -f -' sh "$email" < scripts/grant-sys-admin.sql
