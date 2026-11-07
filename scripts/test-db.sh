#!/usr/bin/env bash
# Apply Flyway SQL migrations to a throwaway PostgreSQL 16 and run backend/db-tests/*.sql as the non-owner role e9x_app.
# Works offline. In CI with Docker, P1.3 runs the same checks through Testcontainers + Flyway.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PGBIN="${PGBIN:-$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)}"
PORT="${E9X_TEST_PGPORT:-55439}"
WORK="$(mktemp -d /var/tmp/e9x-pg.XXXXXX)"
chmod 755 "$WORK"
RUN=()
if [ "$(id -u)" = 0 ]; then chown postgres "$WORK"; RUN=(su postgres -c); fi
run() { if [ ${#RUN[@]} -gt 0 ]; then "${RUN[@]}" "$*"; else bash -c "$*"; fi; }
cleanup() { run "$PGBIN/pg_ctl -D $WORK/data -m immediate stop" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
run "$PGBIN/initdb -D $WORK/data -U postgres -A trust" >/dev/null
run "$PGBIN/pg_ctl -D $WORK/data -o '-p $PORT -k $WORK -c listen_addresses=' -l $WORK/log -w start" >/dev/null
PSQL=(psql -h "$WORK" -p "$PORT" -U postgres -v ON_ERROR_STOP=1 -q -X)
"${PSQL[@]}" -c "CREATE DATABASE e9x_test" >/dev/null
echo "== migrations"
for f in $(ls "$ROOT"/backend/e9x-app/src/main/resources/db/migration/V*.sql 2>/dev/null | sort); do
  "${PSQL[@]}" -d e9x_test -f "$f" >/dev/null
  echo "applied $(basename "$f")"
done
echo "== db tests"
pass=0; fail=0
for t in $(ls "$ROOT"/backend/db-tests/*.sql 2>/dev/null | sort); do
  "${PSQL[@]}" -d postgres -c "CREATE DATABASE t_tmp TEMPLATE e9x_test" >/dev/null
  if out=$("${PSQL[@]}" -d t_tmp -f "$t" 2>&1); then echo "PASS $(basename "$t")"; pass=$((pass+1));
  else echo "FAIL $(basename "$t")"; echo "$out" | sed 's/^/     /'; fail=$((fail+1)); fi
  "${PSQL[@]}" -d postgres -c "DROP DATABASE t_tmp" >/dev/null
done
echo "== lock order: telegram_link_consume against a sign-in that holds the user (two sessions, held locks)"
# auth_oauth_login / auth_password_reset_confirm take users FOR UPDATE, then delete the link tokens. A consume that took its token row
# first and then waited on users (the foreign key of its link insert) closed a cycle: Postgres aborted one of them with "deadlock detected".
"${PSQL[@]}" -d postgres -c "CREATE DATABASE t_tmp TEMPLATE e9x_test" >/dev/null
LOCKOUT="$WORK/lock-out"; mkdir -p "$LOCKOUT"
LU=f0000000-0000-0000-0000-000000000001
"${PSQL[@]}" -d t_tmp -c "INSERT INTO users(id,email,password_hash,consent_at,locale,email_verified_at) VALUES ('$LU','lock@x.test','h',now(),'vi',now());
  INSERT INTO telegram_link_tokens(user_id,token_hash,created_at,expires_at) VALUES ('$LU',sha256('lock-tok'::bytea),now(),now()+interval '1 hour')" >/dev/null
SESS=(psql -h "$WORK" -p "$PORT" -U postgres -q -X -At -d t_tmp)
# A: the sign-in. Holds the user, waits 1.5 s, then deletes the tokens (in that order, as the auth functions do).
"${SESS[@]}" -c "BEGIN; SELECT 1 FROM users WHERE id = '$LU' FOR UPDATE; SELECT pg_sleep(1.5); DELETE FROM telegram_link_tokens WHERE user_id = '$LU'; COMMIT;" >"$LOCKOUT/a" 2>&1 &
PA=$!
sleep 0.5
# B: the webhook, 0.5 s in, while A holds the user.
"${SESS[@]}" -c "SET ROLE e9x_app; SELECT r_status FROM telegram_link_consume(sha256('lock-tok'::bytea), 777001, now());" >"$LOCKOUT/b" 2>&1 &
PB=$!
wait $PA $PB 2>/dev/null || true
if grep -qi "deadlock" "$LOCKOUT/a" "$LOCKOUT/b"; then
  echo "FAIL telegram_link_consume deadlocks against a sign-in holding the user"; sed 's/^/     /' "$LOCKOUT/a" "$LOCKOUT/b"; fail=$((fail+1))
elif grep -q "ERROR" "$LOCKOUT/a" "$LOCKOUT/b"; then
  echo "FAIL lock-order scenario failed for another reason"; sed 's/^/     /' "$LOCKOUT/a" "$LOCKOUT/b"; fail=$((fail+1))
elif [ "$(grep -c '^INVALID$' "$LOCKOUT/b")" = "1" ] \
   && [ "$("${SESS[@]}" -c "SELECT count(*) FROM telegram_link_tokens WHERE user_id = '$LU'")" = "0" ] \
   && [ "$("${SESS[@]}" -c "SELECT count(*) FROM telegram_links WHERE user_id = '$LU'")" = "0" ]; then
  echo "PASS telegram_link_consume waits for the sign-in instead of deadlocking, then finds no token (INVALID, no link)"; pass=$((pass+1))
else
  echo "FAIL lock-order scenario ended in an unexpected state"; sed 's/^/     /' "$LOCKOUT/a" "$LOCKOUT/b"; fail=$((fail+1))
fi
"${PSQL[@]}" -d postgres -c "DROP DATABASE t_tmp" >/dev/null
echo "== operator script: grant-sys-admin.sql"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE t_tmp TEMPLATE e9x_test" >/dev/null
opfail=0
op() { "${PSQL[@]}" -d t_tmp "$@"; }
op -c "INSERT INTO users(email, email_verified_at) VALUES ('boss@example.test', now()), ('newbie@example.test', NULL)" >/dev/null
if op -v email=Boss@Example.test -f "$ROOT/scripts/grant-sys-admin.sql" >/dev/null 2>&1 \
   && [ "$(op -Atc "SELECT count(*) FROM role_grants g JOIN users u ON u.id = g.user_id WHERE u.email = 'boss@example.test' AND g.role = 'SYS_ADMIN' AND g.org_id IS NULL")" = "1" ] \
   && op -v email=boss@example.test -f "$ROOT/scripts/grant-sys-admin.sql" >/dev/null 2>&1 \
   && [ "$(op -Atc "SELECT count(*) FROM role_grants WHERE role = 'SYS_ADMIN'")" = "1" ]; then echo "PASS grant-sys-admin grants a verified account once (idempotent, case-insensitive)"; else echo "FAIL grant-sys-admin on a verified account"; opfail=1; fi
# P1.9: the grant is in the audit log, as the operator (no actor), once, naming the account by id and never by address
if [ "$(op -Atc "SELECT count(*) FROM audit_log a JOIN users u ON u.id::text = a.target_id WHERE a.action = 'sys-admin.grant' AND a.actor_kind = 'OPERATOR' AND a.actor_id IS NULL AND a.target_type = 'user' AND u.email = 'boss@example.test' AND a.detail::text NOT LIKE '%boss%'")" = "1" ] \
   && [ "$(op -Atc "SELECT count(*) FROM audit_log")" = "1" ]; then echo "PASS grant-sys-admin records itself in the audit log once, as the operator"; else echo "FAIL grant-sys-admin audit record"; opfail=1; fi
if op -v email=newbie@example.test -f "$ROOT/scripts/grant-sys-admin.sql" >/dev/null 2>&1 || op -v email=nobody@example.test -f "$ROOT/scripts/grant-sys-admin.sql" >/dev/null 2>&1; then
  echo "FAIL grant-sys-admin accepted an unverified or unknown account"; opfail=1
elif [ "$(op -Atc "SELECT count(*) FROM role_grants WHERE role = 'SYS_ADMIN'")" = "1" ]; then echo "PASS grant-sys-admin refuses unverified and unknown accounts"; else echo "FAIL grant-sys-admin wrote a grant despite refusing"; opfail=1; fi
"${PSQL[@]}" -d postgres -c "DROP DATABASE t_tmp" >/dev/null
[ "$opfail" = "0" ] && pass=$((pass+3)) || fail=$((fail+1))
echo "== operator script: grant-sys-admin.sh names and guards its target (docker is a stub that records what it is asked)"
if bash "$ROOT/scripts/test-grant-sys-admin-guard.sh"; then pass=$((pass+1)); else fail=$((fail+1)); fi
echo
echo "DB tests: $((pass+fail)), passed: $pass, failed: $fail"
[ $((pass+fail)) -gt 0 ] || { echo "No DB tests found."; exit 2; }
[ "$fail" -eq 0 ]
