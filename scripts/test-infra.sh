#!/usr/bin/env bash
# What the Compose files and the Caddyfile promise, against the real images and with no running stack of ours:
#   1. Compose refuses a missing E9X_JWT_SECRET or SMTP host, readably, before anything starts, and accepts infra/.env.example once the secret is filled in.
#   2. infra/preflight.sh: a public E9X_DOMAIN refuses every development default password, by name, and the mail placeholder.
#   3. infra/Caddyfile, in a real Caddy in front of a small upstream that reads the body: 10MB by default, 600MB on the upload routes (one upload above
#      500MB, one above 600MB), 1MB on the webhook, and the health listener answers "/" through Caddy, and only that.
#   4. The media route (P2.2), against a REAL MinIO and a real Caddy: infra/minio/init.sh makes the bucket and the application's own key (and is safe to run twice);
#      a SigV4 link signed for the public host is served through Caddy (200, the stored bytes) because Caddy keeps the client's Host; the same Caddyfile with
#      `header_up Host {upstream_hostport}` is refused by MinIO (the measurement behind the note in infra/Caddyfile); altered, expired and unsigned requests are
#      refused; the route forwards GET of one object key of one bucket and nothing else, and the application's key reaches nothing outside the bucket.
# Touches only the containers and the network it creates here (labels below), and removes them. Needs Docker. Run by verify.sh and by the CI job `compose`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
pass=0; fail=0
ok() { echo "PASS $1"; pass=$((pass+1)); }
ko() { echo "FAIL $1"; fail=$((fail+1)); }

CADDY_IMAGE="$(sed -n 's/^ *image: \(caddy:.*\)$/\1/p' infra/docker-compose.yml)"
PG_IMAGE="$(sed -n 's/^ *image: \(postgres:.*\)$/\1/p' infra/docker-compose.yml | head -1)"
MINIO_IMAGE="$(sed -n 's/^ *image: \(chainguard\/minio@.*\)$/\1/p' infra/docker-compose.yml | head -1)"
MC_IMAGE="$(sed -n 's/^ *image: \(chainguard\/minio-client@.*\)$/\1/p' infra/docker-compose.yml | head -1)"
CURL_IMAGE="curlimages/curl:8.11.1@sha256:c1fe1679c34d9784c1b0d1e5f62ac0a79fca01fb6377cdd33e90473c6f9f9a69"
NODE_IMAGE="$(sed -n 's/^ *image: \(node:.*\)$/\1/p' infra/docker-compose.e2e.yml | head -1)"
RUN="e9x-infra-test-$$"
LABEL="e9x.infra-test=$RUN"
cleanup() {
  docker ps -aq --filter "label=$LABEL" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker network rm "$RUN" >/dev/null 2>&1 || true
  docker volume rm "$RUN-backups" >/dev/null 2>&1 || true
  docker rmi -f "$RUN-backup" >/dev/null 2>&1 || true
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
DEV=(-e POSTGRES_PASSWORD=e9x_dev_password -e E9X_FLYWAY_PASSWORD=e9x_owner_dev_password -e E9X_DB_PASSWORD=e9x_app_dev_password -e E9X_WORKER_DB_PASSWORD=e9x_worker_dev_password -e E9X_GRADER_DB_PASSWORD=e9x_grader_dev_password -e MINIO_ROOT_PASSWORD=e9x_minio_dev_password -e E9X_MINIO_SECRET_KEY=e9x_media_dev_secret)
GOOD=(-e POSTGRES_PASSWORD=a -e E9X_FLYWAY_PASSWORD=b -e E9X_DB_PASSWORD=c -e E9X_WORKER_DB_PASSWORD=d -e E9X_GRADER_DB_PASSWORD=g -e MINIO_ROOT_PASSWORD=e -e E9X_MINIO_SECRET_KEY=f -e E9X_SMTP_HOST=smtp.example.com)
if preflight -e E9X_DOMAIN=localhost "${DEV[@]}" -e E9X_SMTP_HOST=mail.invalid >/dev/null; then ok "localhost may use the development defaults"; else ko "localhost refused"; fi
if out="$(preflight -e E9X_DOMAIN=app.example.com "${DEV[@]}" -e E9X_SMTP_HOST=smtp.example.com)"; then ko "a public domain with dev passwords must be refused"; else
  missing=""; for n in POSTGRES_PASSWORD E9X_FLYWAY_PASSWORD E9X_DB_PASSWORD E9X_WORKER_DB_PASSWORD E9X_GRADER_DB_PASSWORD MINIO_ROOT_PASSWORD E9X_MINIO_SECRET_KEY; do case "$out" in *"$n"*) ;; *) missing="$missing $n";; esac; done
  case "$out" in *e9x_dev_password*) ko "the message must not print a value";; *) if [ -z "$missing" ]; then ok "a public domain refuses every development default, by name, printing no value"; else ko "not named:$missing"; fi;; esac
fi
for one in POSTGRES_PASSWORD=e9x_dev_password E9X_FLYWAY_PASSWORD=e9x_owner_dev_password E9X_DB_PASSWORD=e9x_app_dev_password E9X_WORKER_DB_PASSWORD=e9x_worker_dev_password E9X_GRADER_DB_PASSWORD=e9x_grader_dev_password MINIO_ROOT_PASSWORD=e9x_minio_dev_password E9X_MINIO_SECRET_KEY=e9x_media_dev_secret; do
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

echo "== media: real MinIO behind the real Caddyfile (SigV4 and the Host header, what the route forwards)"
BUCKET=e9x-media
ROOTPW="infra-test-root-$$"; APPKEY=infra_app_key; APPSECRET="infra-app-secret-$$"
docker run -d --rm --label "$LABEL" --network "$RUN" --network-alias minio --name "$RUN-minio" -e MINIO_ROOT_USER=infra_root -e MINIO_ROOT_PASSWORD="$ROOTPW" "$MINIO_IMAGE" server /data >/dev/null
initsh() {
  docker run --rm --label "$LABEL" --network "$RUN" -v "$ROOT/infra/minio/init.sh:/init.sh:ro" -e MINIO_ROOT_USER=infra_root -e MINIO_ROOT_PASSWORD="$ROOTPW" \
    -e E9X_MINIO_BUCKET=$BUCKET -e E9X_MINIO_ACCESS_KEY=$APPKEY -e E9X_MINIO_SECRET_KEY="$APPSECRET" --entrypoint bash "$MC_IMAGE" /init.sh 2>&1
}
mc() { docker run --rm --label "$LABEL" --network "$RUN" --entrypoint bash "$MC_IMAGE" -c "export MC_CONFIG_DIR=/tmp/mc; $1" 2>&1; }
if out="$(initsh)" && out2="$(initsh)"; then ok "minio-init creates the bucket and the application key, and runs twice without harm"; else ko "minio-init failed: $out $out2"; fi
KEY=0b8e7f3a-4c1d-4e2b-9a55-3f1d2c4b6a70
mc "echo -n hello-media > /tmp/f; mc alias set root http://minio:9000 infra_root '$ROOTPW' >/dev/null; mc mb --ignore-existing root/other >/dev/null; mc cp /tmp/f root/$BUCKET/$KEY >/dev/null; mc cp /tmp/f root/other/$KEY >/dev/null" >/dev/null
# the application's key: its bucket, and nothing else
r="$(mc "mc alias set app http://minio:9000 $APPKEY '$APPSECRET' >/dev/null; mc cat app/$BUCKET/$KEY; echo; mc cat app/other/$KEY 2>&1 | tail -1; mc admin user list app 2>&1 | tail -1")"
case "$r" in *hello-media*"Insufficient permissions"*"Access Denied"*) ok "the application's key reads its own bucket and is refused another bucket and the admin API";; *) ko "the application's key is not limited to its bucket: $r";; esac

SIGN='const c=require("crypto");const [host,bucket,key,ak,sk,secs,ago]=process.argv.slice(1);const t=new Date(Date.now()-Number(ago)*1000);const amz=t.toISOString().replace(/[-:]|\.\d{3}/g,"");const day=amz.slice(0,8);const scope=day+"/us-east-1/s3/aws4_request";const e=encodeURIComponent;const q={"X-Amz-Algorithm":"AWS4-HMAC-SHA256","X-Amz-Credential":ak+"/"+scope,"X-Amz-Date":amz,"X-Amz-Expires":secs,"X-Amz-SignedHeaders":"host"};const qs=Object.keys(q).sort().map(k=>e(k)+"="+e(q[k])).join("&");const path="/"+bucket+"/"+key;const creq=["GET",path,qs,"host:"+host+"\n","host","UNSIGNED-PAYLOAD"].join("\n");const sts=["AWS4-HMAC-SHA256",amz,scope,c.createHash("sha256").update(creq).digest("hex")].join("\n");const h=(k,d)=>c.createHmac("sha256",k).update(d).digest();const sig=h(h(h(h(h("AWS4"+sk,day),"us-east-1"),"s3"),"aws4_request"),sts).toString("hex");console.log(path+"?"+qs+"&X-Amz-Signature="+sig);'
signed() { docker run --rm --label "$LABEL" "$NODE_IMAGE" node -e "$SIGN" "$1" "$BUCKET" "$2" "$APPKEY" "$APPSECRET" "$3" "$4"; }   # signed <host> <key> <expires s> <age s>
# creq <caddy container> <curl args...>: status, then a blank line, then the start of the body
creq() {
  local c="$1"; shift
  docker run --rm --label "$LABEL" --network "$RUN" --entrypoint sh "$CURL_IMAGE" -c \
    "curl -sk -m 15 --path-as-is --resolve localhost:443:\$(getent hosts $c | cut -d' ' -f1) -o /tmp/b -D /tmp/h -w '%{http_code}' \"\$@\"; echo; head -c 60 /tmp/b | tr -d '\\n'; echo; grep -i -E '^(content-security-policy|x-content-type-options|server|content-type):' /tmp/h | tr -d '\\r' || true" sh "$@"
}
status() { local o; o="$(creq "$@")"; echo "${o%%$'\n'*}"; }
MPATH="$(signed localhost $KEY 300 0)"
r="$(creq "$RUN-caddy" "https://localhost$MPATH")"
case "$r" in 200*hello-media*) ok "a SigV4 link signed for the public host is served through Caddy: 200 and the stored bytes";; *) ko "signed link through Caddy: $r";; esac
case "$r" in *"sandbox"*) ok "the answer is marked default-src 'none'; sandbox";; *) ko "media answer lacks the sandbox policy: $r";; esac
case "$r" in *[Nn]osniff*) ok "and nosniff";; *) ko "media answer lacks nosniff: $r";; esac
case "$r" in *[Ss]erver:*) ko "the Server banner of MinIO must be dropped: $r";; *) ok "and the MinIO banner is dropped";; esac
r="$(status "$RUN-caddy" -H 'Range: bytes=0-4' "https://localhost$MPATH")"
[ "$r" = 206 ] && ok "a Range request answers 206 (seeking in an audio file)" || ko "Range answered $r"
# an altered request is MinIO's to refuse (Caddy cannot sign): another object under the same signature, a longer life, a different Host, no signature
OTHER="${KEY%?}1"
[ "$(status "$RUN-caddy" "https://localhost${MPATH/$KEY/$OTHER}")" = 403 ] && ok "another key under the same signature is refused (403)" || ko "another key was served"
[ "$(status "$RUN-caddy" "https://localhost${MPATH/X-Amz-Expires=300/X-Amz-Expires=3000}")" = 403 ] && ok "a longer life under the same signature is refused (403)" || ko "a longer life was served"
[ "$(status "$RUN-caddy" -H 'Host: localhost:443' "https://localhost$MPATH")" = 403 ] && ok "a link used under another Host header is refused (403): the Host is part of the signature" || ko "a link worked under another Host"
[ "$(status "$RUN-caddy" "https://localhost$(signed localhost $KEY 60 3600)")" = 403 ] && ok "an expired link is refused (403)" || ko "an expired link was served"
[ "$(status "$RUN-caddy" "https://localhost$(signed localhost 00000000-0000-4000-8000-000000000000 300 0)")" = 404 ] && ok "a correctly signed link to an absent object is a 404" || ko "absent object not 404"
# what the route must NOT forward: these reach the fake app (a number as the body), never MinIO (XML)
notminio() { # notminio <label> <curl args...>
  local label="$1"; shift
  local r; r="$(creq "$RUN-caddy" "$@")"
  case "$r" in *"<?xml"*|*"<Error"*) ko "$label reached MinIO: $r";; *) ok "$label is not forwarded to MinIO";; esac
}
notminio "an unsigned GET of a stored key" "https://localhost/$BUCKET/$KEY"
notminio "the listing of the bucket" "https://localhost/$BUCKET/?X-Amz-Signature=x"
notminio "a HEAD (a GET link does not sign HEAD; players use GET with Range)" -I "https://localhost${MPATH}"
notminio "a signed PUT" -X PUT --data x "https://localhost${MPATH}"
notminio "a signed DELETE" -X DELETE "https://localhost${MPATH}"
notminio "a signed POST" -X POST --data x "https://localhost${MPATH}"
notminio "another bucket" "https://localhost${MPATH/$BUCKET/other}"
notminio "a nested key" "https://localhost${MPATH/$KEY/a/$KEY}"
notminio "the MinIO admin API" "https://localhost/minio/admin/v3/info?X-Amz-Signature=x"
notminio "the MinIO health endpoint" "https://localhost/minio/health/live?X-Amz-Signature=x"
notminio "a path traversal out of the bucket" "https://localhost/$BUCKET/../minio/health/live?X-Amz-Signature=x"
notminio "an encoded traversal out of the bucket" "https://localhost/$BUCKET/%2e%2e/minio/health/live?X-Amz-Signature=x"

# the backup's media half (infra/backup/mirror-media.sh in the backup image): a complete mirror through the S3 API, deletions followed, no leftovers
BACKUP_IMAGE="$RUN-backup"
docker build -q --label "$LABEL" -t "$BACKUP_IMAGE" infra/backup >/dev/null
mirror() { docker run --rm --label "$LABEL" --network "$RUN" -v "$RUN-backups:/backups" -e MINIO_ENDPOINT=http://minio:9000 -e MINIO_BUCKET=$BUCKET \
  -e MINIO_ROOT_USER=infra_root -e MINIO_ROOT_PASSWORD="$ROOTPW" --entrypoint mirror-media.sh "$BACKUP_IMAGE" 2>&1; }
lsbackup() { docker run --rm --label "$LABEL" -v "$RUN-backups:/backups" --entrypoint sh "$BACKUP_IMAGE" -c "$1" 2>&1; }
if out="$(mirror)" && [ "$(lsbackup "cat /backups/minio/$KEY")" = "hello-media" ]; then ok "the backup mirrors the media bucket through the S3 API (the object, byte for byte)"; else ko "mirror failed: $out"; fi
mc "mc alias set root http://minio:9000 infra_root '$ROOTPW' >/dev/null; mc rm root/$BUCKET/$KEY >/dev/null; echo -n second > /tmp/g; mc cp /tmp/g root/$BUCKET/second >/dev/null" >/dev/null
if out="$(mirror)" && [ "$(lsbackup "ls /backups/minio")" = "second" ]; then ok "a second run follows deletions and adds, and leaves no temporary directory"; else ko "second mirror: $out / $(lsbackup 'ls -la /backups')"; fi
mc "echo -n hello-media > /tmp/f; mc alias set root http://minio:9000 infra_root '$ROOTPW' >/dev/null; mc cp /tmp/f root/$BUCKET/$KEY >/dev/null" >/dev/null   # the object is back
docker volume rm "$RUN-backups" >/dev/null 2>&1 || true

# the measurement behind the note in infra/Caddyfile: the same Caddyfile with Host rewritten to the upstream's address cannot serve a link signed for "localhost"
BAD="$ROOT/infra/.Caddyfile.bad-host.$$"
sed 's#reverse_proxy minio:9000 {#reverse_proxy minio:9000 {\n\t\t\theader_up Host {upstream_hostport}#' infra/Caddyfile > "$BAD"
docker run -d --rm --label "$LABEL" --network "$RUN" --name "$RUN-caddy-badhost" -e E9X_DOMAIN=localhost -v "$BAD:/etc/caddy/Caddyfile:ro" "$CADDY_IMAGE" >/dev/null
sleep 3
r="$(status "$RUN-caddy-badhost" "https://localhost$MPATH")"
rm -f "$BAD"
[ "$r" = 403 ] && ok "with header_up Host {upstream_hostport} the same link is refused by MinIO (403): that line must never be added" || ko "the Host rewrite did not break the signature (status $r): the note in the Caddyfile is wrong"

echo
echo "infra tests: $((pass+fail)), passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
