#!/usr/bin/env bash
# DRILL (run by a person on a STAGING VPS, never against production data you cannot lose): backs up, DELETES the volumes english9x_pgdata and
# english9x_miniodata, brings the stack up empty, restores the database (restore.sh) and the media (restore-media.sh) from the backup, starts the app and
# compares what was there before with what is there after. This is the README's "remove the old volume and restore in place", as one script.
#   E9X_DRILL_CONFIRM=delete-english9x-volumes infra/backup/drill-restore-inplace.sh
# Run from the repository root, with infra/.env filled in and the stack up and healthy. E9X_DRILL_DRY_RUN=1 only prints the steps (needs no Docker).
# Other env: E9X_ENV_FILE (default infra/.env), E9X_DRILL_PROJECT (default english9x), E9X_DRILL_DIR (host copy of the backup, default ./restore-drill).
# It never runs `down -v`: the volume `backups` (holding the dump and the media mirror) is NOT deleted, and a copy goes to the host first.
# Exit codes: 2 not confirmed, 3 a step failed its precondition, 6 the before/after fingerprints differ.
set -euo pipefail
PROJECT="${E9X_DRILL_PROJECT:-english9x}"
ENVF="${E9X_ENV_FILE:-infra/.env}"
OUT="${E9X_DRILL_DIR:-restore-drill}"
DRY="${E9X_DRILL_DRY_RUN:-}"
if [ "${E9X_DRILL_CONFIRM:-}" != "delete-english9x-volumes" ]; then
  echo "drill: refusing. It DELETES the volumes ${PROJECT}_pgdata and ${PROJECT}_miniodata. Staging only; to go on: E9X_DRILL_CONFIRM=delete-english9x-volumes $0" >&2
  exit 2
fi
C=(docker compose -p "$PROJECT" --env-file "$ENVF" -f infra/docker-compose.yml)
step() { echo; echo "== $*"; }
run() { echo "+ $*"; [ -n "$DRY" ] || "$@"; }

# What must be the same after: exact row count of every table of the database, and the media bucket's object keys and total size.
fingerprint() {
  local f="$1"
  {
    echo "-- rows per table"
    "${C[@]}" exec -T backup psql -X -At -c "select relname || ' ' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', schemaname, relname), false, true, '')))[1]::text from pg_tables where schemaname = 'public' order by 1"
    echo "-- media objects (mc find) and size (mc du)"
    "${C[@]}" exec -T backup sh -c 'mc alias set e9x "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc find "e9x/$MINIO_BUCKET" | sort && mc du --recursive "e9x/$MINIO_BUCKET"'
  } > "$f"
}

step "0. the stack is up and the backup service is healthy"
run "${C[@]}" ps
run "${C[@]}" exec -T backup /usr/local/bin/healthcheck.sh

step "1. a fresh backup now (dump + checksum + media mirror), and a copy of the whole backups volume on this host"
run "${C[@]}" exec -T backup /usr/local/bin/backup.sh
run mkdir -p "$OUT"
run "${C[@]}" cp backup:/backups/. "$OUT/"
if [ -z "$DRY" ]; then
  DUMP="$(ls -1 "$OUT"/pg-*.sql.gz | sort | tail -n 1)"
  [ -n "$DUMP" ] && [ -d "$OUT/minio" ] || { echo "drill: the copy has no dump or no minio/ mirror" >&2; exit 3; }
  (cd "$OUT" && sha256sum -c "$(basename "$DUMP").sha256") || { echo "drill: the copied dump does not match its checksum" >&2; exit 3; }
else
  DUMP="$OUT/pg-<newest>.sql.gz"
fi

step "2. fingerprint BEFORE"
if [ -z "$DRY" ]; then fingerprint "$OUT/before.txt"; wc -l "$OUT/before.txt"; else echo "+ (psql row counts + mc find/du) > $OUT/before.txt"; fi

step "3. stop everything and delete ONLY the data volumes of the database and of MinIO (the backups volume stays)"
run "${C[@]}" down
run docker volume rm "${PROJECT}_pgdata" "${PROJECT}_miniodata"

step "4. an empty database (01-roles.sh runs) and an empty MinIO with its bucket and key (minio-init)"
run "${C[@]}" up -d --wait postgres minio
run "${C[@]}" run --rm minio-init

step "5. restore the database (as e9x_owner, refuses a database that has tables) and the media"
run "${C[@]}" run --rm --no-deps -v "$PWD/$OUT:/restore:ro" --entrypoint /usr/local/bin/restore.sh backup "/restore/$(basename "$DUMP")"
run "${C[@]}" run --rm --no-deps -v "$PWD/$OUT:/restore:ro" --entrypoint /usr/local/bin/restore-media.sh backup /restore/minio

step "6. the whole stack: Flyway validates what was restored and goes on from there"
run "${C[@]}" up --build -d --wait

step "7. fingerprint AFTER, compared with BEFORE"
if [ -z "$DRY" ]; then
  fingerprint "$OUT/after.txt"
  if diff -u "$OUT/before.txt" "$OUT/after.txt"; then echo "drill: PASSED. Same rows in every table, same media objects and size."; else echo "drill: FAILED. The differences are above." >&2; exit 6; fi
else
  echo "+ (the same fingerprint) > $OUT/after.txt; diff -u before.txt after.txt"
fi
