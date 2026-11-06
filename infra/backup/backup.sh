#!/usr/bin/env bash
# Daily backup: pg_dump + mirror of the MinIO data; keeps the newest E9X_BACKUP_RETENTION (default 14) dumps.
# Env: PGHOST PGDATABASE PGUSER PGPASSWORD, MINIO_DATA_DIR (read-only mount of the MinIO data), BACKUP_DIR (default /backups)
set -euo pipefail
DIR="${BACKUP_DIR:-/backups}"
KEEP="${E9X_BACKUP_RETENTION:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$DIR"

pg_dump --no-owner --format=plain | gzip > "$DIR/pg-$STAMP.sql.gz.tmp"
mv "$DIR/pg-$STAMP.sql.gz.tmp" "$DIR/pg-$STAMP.sql.gz"
echo "pg_dump -> pg-$STAMP.sql.gz"

if [ -d "${MINIO_DATA_DIR:-}" ]; then
  # object store mirror: copy of the MinIO data directory (mounted read-only)
  mkdir -p "$DIR/minio.tmp"
  cp -a "$MINIO_DATA_DIR/." "$DIR/minio.tmp/"
  find "$DIR/minio" -mindepth 1 -delete 2>/dev/null || true
  find "$DIR/minio" -maxdepth 0 -delete 2>/dev/null || true
  mv "$DIR/minio.tmp" "$DIR/minio"
  echo "minio mirrored"
fi

# retention: the newest $KEEP dumps survive
ls -1 "$DIR"/pg-*.sql.gz 2>/dev/null | sort -r | tail -n +"$((KEEP + 1))" | while read -r old; do
  find "$old" -delete
  echo "pruned $(basename "$old")"
done
