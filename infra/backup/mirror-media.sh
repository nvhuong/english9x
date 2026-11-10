#!/usr/bin/env bash
# Mirrors the media bucket into $BACKUP_DIR/minio through the S3 API (mc mirror): only complete objects, and --remove drops what was deleted since, so the
# copy is the bucket as of now. Env: MINIO_ENDPOINT MINIO_BUCKET MINIO_ROOT_USER MINIO_ROOT_PASSWORD, BACKUP_DIR (default /backups).
# Restore: mc mirror <dir>/minio <alias>/<bucket> (README, Backup and restore).
set -euo pipefail
DIR="${BACKUP_DIR:-/backups}"
export MC_CONFIG_DIR="${MC_CONFIG_DIR:-/tmp/mc-backup}"
mc alias set e9x "${MINIO_ENDPOINT:?MINIO_ENDPOINT is required}" "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}" >/dev/null
mkdir -p "$DIR/minio"
mc mirror --overwrite --remove --quiet "e9x/${MINIO_BUCKET:?MINIO_BUCKET is required}" "$DIR/minio"
echo "minio mirrored: bucket $MINIO_BUCKET -> minio/"
