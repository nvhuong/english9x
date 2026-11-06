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
echo
echo "DB tests: $((pass+fail)), passed: $pass, failed: $fail"
[ $((pass+fail)) -gt 0 ] || { echo "No DB tests found."; exit 2; }
[ "$fail" -eq 0 ]
