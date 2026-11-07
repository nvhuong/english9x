#!/usr/bin/env bash
# End-to-end run against the REAL Compose stack (infra/docker-compose.yml + docker-compose.e2e.yml):
#   1. removes any leftover stack of this project, then builds and starts a fresh one (clean database and volumes),
#   2. waits until every service is healthy and the app answers through Caddy over HTTPS,
#   3. runs Playwright (e2e/), passing your arguments through,
#   4. ALWAYS tears down its own project with `down -v` (unless --keep), and exits with Playwright's exit code.
#
# Usage:  ./scripts/e2e.sh [--keep] [--no-build] [--rmi] [playwright args...]
#   ./scripts/e2e.sh                                  everything
#   ./scripts/e2e.sh specs/_smoke.spec.ts             one file
#   ./scripts/e2e.sh -g "language switch" --headed    any Playwright option
#   ./scripts/e2e.sh --keep specs/common              leave the stack up afterwards for debugging
#       (stack: https://localhost, Mailpit http://localhost:$E9X_MAILPIT_PORT; stop it with: ./scripts/e2e.sh --down)
#   --no-build   reuse the already built app image    --rmi   also remove the images this project built at the end
#   --down       only tear the project down (after a --keep run)
# Environment: E2E_WORKERS (default 2; the stack uses ~2-3 GB of RAM), E2E_VIDEO (on|off|retain-on-failure, default on),
#   E9X_MAILPIT_PORT / E9X_FAKE_TELEGRAM_PORT / E9X_FAKE_GEMINI_PORT / E9X_FAKE_OIDC_PORT (default: first free port from
#   8025 / 8081 / 8082 / 8083). E9X_JWT_SECRET and E9X_E2E_SEED_SECRET are generated per run unless you set them.
# Needs host ports 80 and 443 free (Caddy). Touches only the Compose project "e9x-e2e"; never prunes anything.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PROJECT="e9x-e2e"
COMPOSE=(docker compose -p "$PROJECT" -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml)
SERVICES=(app caddy mailpit fake-telegram fake-gemini fake-oidc)

KEEP=0; BUILD=1; RMI=0; DOWN_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    --no-build) BUILD=0; shift ;;
    --rmi) RMI=1; shift ;;
    --down) DOWN_ONLY=1; shift ;;
    --) shift; break ;;
    *) break ;;
  esac
done

say() { printf '\n== e2e: %s\n' "$1"; }
die() { printf 'e2e: %s\n' "$1" >&2; exit 2; }

command -v docker >/dev/null || die "docker not found"
docker info >/dev/null 2>&1 || die "Docker is not running"
command -v pnpm >/dev/null || die "pnpm not found (Node 22 + pnpm 10: source ~/.nvm/nvm.sh && nvm use 22)"
command -v node >/dev/null || die "node not found"
command -v openssl >/dev/null || die "openssl not found"

teardown() {
  say "tearing down project $PROJECT (down -v)"
  local rmi=()
  [ "$RMI" = "1" ] && rmi=(--rmi local)
  "${COMPOSE[@]}" down -v --remove-orphans ${rmi[@]+"${rmi[@]}"} 2>&1 | tail -n 15
}

if [ "$DOWN_ONLY" = "1" ]; then teardown; exit 0; fi

# First free TCP port at or above $1 (the E2E host may already use the defaults).
free_port() {
  node -e '
    const net = require("net");
    let p = Number(process.argv[1]);
    (function next() {
      const s = net.createServer();
      s.once("error", () => { p++; next(); });
      s.listen(p, "0.0.0.0", () => s.close(() => console.log(p)));
    })();' "$1"
}
port_free() { node -e '
  const s = require("net").createServer();
  s.once("error", () => process.exit(1));
  s.listen(Number(process.argv[1]), "0.0.0.0", () => s.close(() => process.exit(0)));' "$1"; }

# A previous run of THIS project (e.g. after --keep) holds 80/443: remove it first, then check the ports.
say "removing any leftover $PROJECT stack"
"${COMPOSE[@]}" down -v --remove-orphans 2>&1 | tail -n 5
for p in 80 443; do
  port_free "$p" || die "host port $p is in use by something else; Caddy needs 80 and 443. Stop that process and retry."
done

export E9X_MAILPIT_PORT="${E9X_MAILPIT_PORT:-$(free_port 8025)}"
export E9X_FAKE_TELEGRAM_PORT="${E9X_FAKE_TELEGRAM_PORT:-$(free_port 8081)}"
export E9X_FAKE_GEMINI_PORT="${E9X_FAKE_GEMINI_PORT:-$(free_port 8082)}"
if [ "$E9X_FAKE_TELEGRAM_PORT" = "$E9X_FAKE_GEMINI_PORT" ]; then export E9X_FAKE_GEMINI_PORT="$(free_port $((E9X_FAKE_TELEGRAM_PORT + 1)))"; fi
export E9X_FAKE_OIDC_PORT="${E9X_FAKE_OIDC_PORT:-$(free_port 8083)}"
while [ "$E9X_FAKE_OIDC_PORT" = "$E9X_FAKE_TELEGRAM_PORT" ] || [ "$E9X_FAKE_OIDC_PORT" = "$E9X_FAKE_GEMINI_PORT" ] || [ "$E9X_FAKE_OIDC_PORT" = "$E9X_MAILPIT_PORT" ]; do
  export E9X_FAKE_OIDC_PORT="$(free_port $((E9X_FAKE_OIDC_PORT + 1)))"
done
echo "ports: https 443, mailpit $E9X_MAILPIT_PORT, fake-telegram $E9X_FAKE_TELEGRAM_PORT, fake-gemini $E9X_FAKE_GEMINI_PORT, fake-oidc $E9X_FAKE_OIDC_PORT"

# Secrets of this run only (never reused, never printed): the JWT signing key, and the secret the seed API demands.
# The Playwright process below inherits E9X_E2E_SEED_SECRET.
export E9X_JWT_SECRET="${E9X_JWT_SECRET:-$(openssl rand -hex 32)}"
export E9X_E2E_SEED_SECRET="${E9X_E2E_SEED_SECRET:-$(openssl rand -hex 24)}"

say "installing e2e dependencies"
pnpm -C e2e install --frozen-lockfile >/dev/null || die "pnpm install failed in e2e/"

STARTED=0
finish() {
  local code=$?
  if [ "$KEEP" = "1" ] && [ "$STARTED" = "1" ]; then
    say "stack left running (--keep). Mailpit http://localhost:$E9X_MAILPIT_PORT, app https://localhost. Tear down: ./scripts/e2e.sh --down"
  else
    teardown
  fi
  exit "$code"
}
trap finish EXIT
trap 'exit 130' INT TERM

say "building and starting the stack (clean database)"
STARTED=1
up=(up -d --wait --wait-timeout 900)
[ "$BUILD" = "1" ] && up+=(--build)
"${COMPOSE[@]}" "${up[@]}" "${SERVICES[@]}" || { "${COMPOSE[@]}" ps -a; "${COMPOSE[@]}" logs --tail 60 app 2>&1 | tail -n 80; exit 3; }
"${COMPOSE[@]}" ps
echo "database volume created $(docker volume inspect "${PROJECT}_pgdata" --format '{{.CreatedAt}}' 2>/dev/null) (fresh for this run)"

say "waiting for the app through Caddy (https://localhost)"
ok=0
for _ in $(seq 1 60); do
  if curl -ksf -o /dev/null https://localhost/api/platform/ping; then ok=1; break; fi
  sleep 2
done
[ "$ok" = "1" ] || { echo "app did not answer on https://localhost/api/platform/ping" >&2; "${COMPOSE[@]}" logs --tail 80 app caddy 2>&1 | tail -n 120; exit 3; }

say "running Playwright"
export E2E_WORKERS="${E2E_WORKERS:-2}"
pnpm -C e2e exec playwright test "$@"
code=$?
echo
echo "e2e: Playwright exit code $code. Videos/traces: $ROOT/e2e/test-results   HTML report: $ROOT/e2e/playwright-report"
exit "$code"
