#!/usr/bin/env bash
# Daily backup: pg_dump + a mirror of the media bucket; keeps the newest E9X_BACKUP_RETENTION (default 14) dumps.
# Env: PGHOST PGDATABASE PGUSER PGPASSWORD, BACKUP_DIR (default /backups); for the media: MINIO_ENDPOINT MINIO_BUCKET MINIO_ROOT_USER MINIO_ROOT_PASSWORD (without MINIO_ENDPOINT only the dump is made)
set -euo pipefail
DIR="${BACKUP_DIR:-/backups}"
KEEP="${E9X_BACKUP_RETENTION:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$DIR"

pg_dump --no-owner --format=plain | gzip > "$DIR/pg-$STAMP.sql.gz.tmp"
mv "$DIR/pg-$STAMP.sql.gz.tmp" "$DIR/pg-$STAMP.sql.gz"
echo "pg_dump -> pg-$STAMP.sql.gz"

# retention: the newest $KEEP dumps survive
ls -1 "$DIR"/pg-*.sql.gz 2>/dev/null | sort -r | tail -n +"$((KEEP + 1))" | while read -r old; do
  find "$old" -delete
  echo "pruned $(basename "$old")"
done

# The media bucket (mirror-media.sh): last, so a failing mirror never costs the dump or the pruning above.
if [ -n "${MINIO_ENDPOINT:-}" ]; then
  /usr/local/bin/mirror-media.sh
fi
