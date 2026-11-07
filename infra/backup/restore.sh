#!/usr/bin/env bash
# Restores a dump made by backup.sh into a FRESH database: an empty volume on which infra/postgres/init/01-roles.sh has run (Compose does that when it
# first starts postgres). It runs as e9x_owner, never as the superuser that made the dump: backup.sh dumps with --no-owner, so the objects belong to whoever
# restores. Restored as the superuser, every table, function and flyway_schema_history would belong to the superuser, the next migration could not run,
# and the SECURITY DEFINER functions would run as a superuser.
#   PGHOST=postgres PGDATABASE=e9x E9X_OWNER_PASSWORD=<E9X_FLYWAY_PASSWORD> restore.sh /backups/pg-<stamp>.sql.gz    (the service `backup` has these set)
# Refuses a database that already has tables, and any login other than e9x_owner. One transaction: a failure leaves the database empty.
# The MinIO half: see README (Backup and restore).
set -euo pipefail
dump="${1:-}"
[ -n "$dump" ] && [ -f "$dump" ] || { echo "usage: restore.sh <pg-*.sql.gz>  (no such file: '$dump')" >&2; exit 2; }
export PGUSER="${E9X_RESTORE_USER:-e9x_owner}"
export PGPASSWORD="${E9X_OWNER_PASSWORD:-${PGPASSWORD:-}}"
: "${PGDATABASE:?PGDATABASE is required}"
psql=(psql -X -q -v ON_ERROR_STOP=1)
who="$("${psql[@]}" -Atc "select current_user || ' ' || (select rolsuper from pg_roles where rolname = current_user)")"
if [ "$who" != "e9x_owner false" ]; then
  echo "restore: refusing to restore as '$who': it must be the non-superuser e9x_owner, prepared by 01-roles.sh" >&2
  exit 3
fi
tables="$("${psql[@]}" -Atc "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r','p')")"
if [ "$tables" != "0" ]; then
  echo "restore: refusing: database $PGDATABASE already has $tables tables; restore into a fresh volume" >&2
  exit 4
fi
gunzip -c "$dump" | "${psql[@]}" --single-transaction >/dev/null
migrations="$("${psql[@]}" -Atc "select count(*) from flyway_schema_history where success")"
wrong="$("${psql[@]}" -Atc "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r','p') and pg_get_userbyid(c.relowner) <> 'e9x_owner'")"
[ "$wrong" = "0" ] || { echo "restore: $wrong tables are not owned by e9x_owner" >&2; exit 5; }
echo "restore: $(basename "$dump") restored into $PGDATABASE as e9x_owner, $migrations migrations on record. Start the app: Flyway validates and goes on from there."
