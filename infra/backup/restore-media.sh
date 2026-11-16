#!/usr/bin/env bash
# Restores the media bucket from the mirror that mirror-media.sh keeps in <backup dir>/minio, through the S3 API (mc mirror): objects arrive complete, through
# MinIO's own write path, never by copying files into MinIO's data directory (a cp -a of /data would bypass its metadata and could not be consistent).
# Run it AFTER `minio-init` has made the bucket and the application's key (Compose does that on `up`); it works with the root credentials.
#   restore-media.sh [<dir>]      <dir> is the mirror (default $BACKUP_DIR/minio, BACKUP_DIR default /backups)
# Env: MINIO_ENDPOINT MINIO_BUCKET MINIO_ROOT_USER MINIO_ROOT_PASSWORD (the service `backup` has them set).
# Refuses a bucket that already holds objects (unless E9X_RESTORE_MEDIA_FORCE=1, which overwrites same-named objects and keeps the others), and checks the
# object count afterwards. Exit codes: 2 usage, 4 bucket not empty, 5 count mismatch.
set -euo pipefail
src="${1:-${BACKUP_DIR:-/backups}/minio}"
[ -d "$src" ] || { echo "usage: restore-media.sh [<mirror dir>]  (no such directory: '$src')" >&2; exit 2; }
export MC_CONFIG_DIR="${MC_CONFIG_DIR:-/tmp/mc-restore}"
bucket="${MINIO_BUCKET:?MINIO_BUCKET is required}"
mc alias set e9x "${MINIO_ENDPOINT:?MINIO_ENDPOINT is required}" "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}" >/dev/null
mc mb --ignore-existing "e9x/$bucket" >/dev/null
existing="$(mc ls --recursive "e9x/$bucket" | wc -l | tr -d ' ')"
if [ "$existing" != "0" ] && [ "${E9X_RESTORE_MEDIA_FORCE:-}" != "1" ]; then
  echo "restore-media: refusing: bucket $bucket already holds $existing objects; restore into a fresh volume (or E9X_RESTORE_MEDIA_FORCE=1)" >&2
  exit 4
fi
expected="$(find "$src" -type f | wc -l | tr -d ' ')"
mc mirror --overwrite --quiet "$src" "e9x/$bucket"
got="$(mc ls --recursive "e9x/$bucket" | wc -l | tr -d ' ')"
if [ "$got" -lt "$expected" ]; then
  echo "restore-media: only $got of $expected objects are in $bucket" >&2
  exit 5
fi
echo "restore-media: $expected objects from $src restored into bucket $bucket ($got there now)"
