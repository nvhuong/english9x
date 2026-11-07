#!/usr/bin/env bash
# What the Compose files and the Caddyfile promise, against the real images and with no running stack of ours:
#   1. Compose refuses a missing E9X_JWT_SECRET or SMTP host, readably, before anything starts, and accepts infra/.env.example once the secret is filled in.
#   2. infra/preflight.sh: a public E9X_DOMAIN refuses every development default password, by name, and the mail placeholder.
#   3. infra/Caddyfile, in a real Caddy in front of a small upstream that reads the body: 10MB by default, 600MB on the upload routes (one upload above
#      500MB, one above 600MB), 1MB on the webhook, and the health listener answers "/" through Caddy, and only that.
# Touches only the containers and the network it creates here (labels below), and removes them. Needs Docker. Run by verify.sh and by the CI job `compose`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
pass=0; fail=0
ok() { echo "PASS $1"; pass=$((pass+1)); }
ko() { echo "FAIL $1"; fail=$((fail+1)); }

CADDY_IMAGE="$(sed -n 's/^ *image: \(caddy:.*\)$/\1/p' infra/docker-compose.yml)"
PG_IMAGE="$(sed -n 's/^ *image: \(postgres:.*\)$/\1/p' infra/docker-compose.yml | head -1)"
NODE_IMAGE="$(sed -n 's/^ *image: \(node:.*\)$/\1/p' infra/docker-compose.e2e.yml | head -1)"
RUN="e9x-infra-test-$$"
LABEL="e9x.infra-test=$RUN"
cleanup() {
  docker ps -aq --filter "label=$LABEL" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker network rm "$RUN" >/dev/null 2>&1 || true
  rm -f "$ROOT/infra/.env.infra-test.$$"
}
trap cleanup EXIT

echo "== compose: required settings"
base=(docker compose -p "$RUN" -f infra/docker-compose.yml)
clean_env=(env -u E9X_JWT_SECRET -u E9X_SMTP_HOST)
if out="$("${clean_env[@]}" "${base[@]}" --env-file /dev/null config -q 2>&1)"; then ko "a missing JWT secret must stop Compose"; else
  case "$out" in *E9X_JWT_SECRET*"openssl rand -hex 32"*|*E9X_SMTP_HOST*"README"*) ok "a missing setting stops Compose, naming it and the fix";; *) ko "unclear message: $out";; esac
fi
if out="$(env -u E9X_SMTP_HOST E9X_JWT_SECRET=x "${base[@]}" --env-file /dev/null config -q 2>&1)"; then ko "a missing SMTP host must stop Compose"; else
  case "$out" in *E9X_SMTP_HOST*) ok "a missing SMTP host stops Compose";; *) ko "unclear message: $out";; esac
fi
ENVF="infra/.env.infra-test.$$"
sed "s/^E9X_JWT_SECRET=.*/E9X_JWT_SECRET=$(openssl rand -hex 32)/" infra/.env.example > "$ENVF"
if "${clean_env[@]}" "${base[@]}" --env-file "$ENVF" config -q; then ok "infra/.env.example with the secret filled in is all Compose needs"; else ko "infra/.env.example is not enough"; fi
if "${clean_env[@]}" docker compose -p "$RUN" -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml --env-file "$ENVF" config -q; then ok "the same for the dev overlay"; else ko "the dev overlay rejects infra/.env.example"; fi

echo "== preflight"
preflight() { docker run --rm --label "$LABEL" -v "$ROOT/infra/preflight.sh:/preflight.sh:ro" --entrypoint /bin/sh "$@" "$PG_IMAGE" /preflight.sh 2>&1; }
DEV=(-e POSTGRES_PASSWORD=e9x_dev_password -e E9X_FLYWAY_PASSWORD=e9x_owner_dev_password -e E9X_DB_PASSWORD=e9x_app_dev_password -e E9X_WORKER_DB_PASSWORD=e9x_worker_dev_password -e MINIO_ROOT_PASSWORD=e9x_minio_dev_password)
GOOD=(-e POSTGRES_PASSWORD=a -e E9X_FLYWAY_PASSWORD=b -e E9X_DB_PASSWORD=c -e E9X_WORKER_DB_PASSWORD=d -e MINIO_ROOT_PASSWORD=e -e E9X_SMTP_HOST=smtp.example.com)
if preflight -e E9X_DOMAIN=localhost "${DEV[@]}" -e E9X_SMTP_HOST=mail.invalid >/dev/null; then ok "localhost may use the development defaults"; else ko "localhost refused"; fi
if out="$(preflight -e E9X_DOMAIN=app.example.com "${DEV[@]}" -e E9X_SMTP_HOST=smtp.example.com)"; then ko "a public domain with dev passwords must be refused"; else
  missing=""; for n in POSTGRES_PASSWORD E9X_FLYWAY_PASSWORD E9X_DB_PASSWORD E9X_WORKER_DB_PASSWORD MINIO_ROOT_PASSWORD; do case "$out" in *"$n"*) ;; *) missing="$missing $n";; esac; done
  case "$out" in *e9x_dev_password*) ko "the message must not print a value";; *) if [ -z "$missing" ]; then ok "a public domain refuses every development default, by name, printing no value"; else ko "not named:$missing"; fi;; esac
fi
for one in POSTGRES_PASSWORD=e9x_dev_password E9X_FLYWAY_PASSWORD=e9x_owner_dev_password E9X_DB_PASSWORD=e9x_app_dev_password E9X_WORKER_DB_PASSWORD=e9x_worker_dev_password MINIO_ROOT_PASSWORD=e9x_minio_dev_password; do
  if preflight -e E9X_DOMAIN=app.example.com "${GOOD[@]}" -e "$one" >/dev/null; then ko "one default alone must be refused: ${one%%=*}"; fi
done
ok "any one default alone is enough to refuse"
if preflight -e E9X_DOMAIN=app.example.com "${GOOD[@]}" >/dev/null; then ok "a public domain with its own passwords starts"; else ko "good settings refused"; fi
if preflight -e E9X_DOMAIN=app.example.com "${GOOD[@]}" -e E9X_SMTP_HOST=mail.invalid >/dev/null; then ko "the mail placeholder must be refused on a public domain"; else ok "a public domain refuses the mail placeholder"; fi

echo "== Caddy: body limits and health listener (real Caddy, real Caddyfile)"
docker network create --label "$LABEL" "$RUN" >/dev/null
docker run -d --rm --label "$LABEL" --network "$RUN" --network-alias app --name "$RUN-app" -e NODE_NO_WARNINGS=1 "$NODE_IMAGE" node -e '
  require("http").createServer((q, s) => { let n = 0; q.on("data", (d) => { n += d.length; }); q.on("end", () => { s.setHeader("x-seen-path", q.url); s.end(String(n)); }); }).listen(8080);' >/dev/null
docker run -d --rm --label "$LABEL" --network "$RUN" --name "$RUN-caddy" -e E9X_DOMAIN=localhost -v "$ROOT/infra/Caddyfile:/etc/caddy/Caddyfile:ro" "$CADDY_IMAGE" >/dev/null
# curl runs in a third container on the same network; the body is zeros from a pipe, so nothing lands on disk
send() { # send <MB> <path>: prints the HTTP status
  docker run -i --rm --label "$LABEL" --network "$RUN" --entrypoint sh curlimages/curl:8.11.1@sha256:c1fe1679c34d9784c1b0d1e5f62ac0a79fca01fb6377cdd33e90473c6f9f9a69 -c \
    "head -c $(($1 * 1024 * 1024)) /dev/zero | curl -sk -o /dev/null -w '%{http_code}' --resolve localhost:443:\$(getent hosts $RUN-caddy | cut -d' ' -f1) -X POST -H 'Transfer-Encoding: chunked' -H 'Content-Type: application/octet-stream' --data-binary @- https://localhost$2 || true"
}
expect() { # expect <wanted> <MB> <path>
  local got; got="$(send "$2" "$3")"
  if [ "$got" = "$1" ]; then ok "$2MB to $3 answers $1"; else ko "$2MB to $3 answered '$got', wanted $1"; fi
}
ready=0
for _ in $(seq 1 30); do
  if docker exec "$RUN-caddy" wget -q -O /dev/null http://127.0.0.1:2080/ 2>/dev/null; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ] && ok "the health listener answers / through Caddy to the upstream" || ko "the health listener did not answer"
if docker exec "$RUN-caddy" wget -q -O /dev/null http://127.0.0.1:2080/api/auth/me 2>/dev/null; then ko "the health listener must serve / only"; else ok "the health listener serves / only"; fi
if docker run --rm --label "$LABEL" --network "$RUN" --entrypoint curl curlimages/curl:8.11.1@sha256:c1fe1679c34d9784c1b0d1e5f62ac0a79fca01fb6377cdd33e90473c6f9f9a69 -s -m 3 -o /dev/null "http://$RUN-caddy:2080/"; then ko "the health listener must not be reachable from the network"; else ok "the health listener is not reachable from the network"; fi
docker stop "$RUN-app" >/dev/null
if docker exec "$RUN-caddy" wget -q -O /dev/null http://127.0.0.1:2080/ 2>/dev/null; then ko "with the app gone the check must fail"; else ok "with the app gone the check fails (it is a real probe)"; fi
docker run -d --rm --label "$LABEL" --network "$RUN" --network-alias app --name "$RUN-app2" -e NODE_NO_WARNINGS=1 "$NODE_IMAGE" node -e '
  require("http").createServer((q, s) => { let n = 0; q.on("data", (d) => { n += d.length; }); q.on("end", () => { s.end(String(n)); }); }).listen(8080);' >/dev/null
sleep 2
expect 200 9 /api/auth/login
expect 413 11 /api/auth/login
expect 413 11 /api/cms/questions
expect 200 1 /api/cms/media
expect 200 510 /api/cms/import
expect 200 510 /api/cms/media
expect 413 610 /api/cms/import
expect 200 0 /api/telegram/webhook
expect 413 2 /api/telegram/webhook
echo
echo "infra tests: $((pass+fail)), passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
