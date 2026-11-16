#!/usr/bin/env bash
# Healthcheck of the service `backup`: healthy only when the NEWEST dump in $BACKUP_DIR is younger than E9X_BACKUP_MAX_AGE_MIN minutes (default 1560 = 26h:
# one run at start-up, then every 24h) AND its checksum file (pg-<stamp>.sql.gz.sha256, written by backup.sh) exists and matches the dump.
# Exit 0 healthy, 1 unhealthy (the reason goes to stderr). Env: BACKUP_DIR (default /backups), E9X_BACKUP_MAX_AGE_MIN.
set -uo pipefail
DIR="${BACKUP_DIR:-/backups}"
MAX_MIN="${E9X_BACKUP_MAX_AGE_MIN:-1560}"
newest="$(ls -1 "$DIR"/pg-*.sql.gz 2>/dev/null | sort | tail -n 1)"
[ -n "$newest" ] || { echo "backup unhealthy: no pg-*.sql.gz in $DIR" >&2; exit 1; }
age_min=$(( ($(date +%s) - $(stat -c %Y "$newest")) / 60 ))
if [ "$age_min" -gt "$MAX_MIN" ]; then
  echo "backup unhealthy: $(basename "$newest") is $age_min min old, the limit is $MAX_MIN" >&2
  exit 1
fi
[ -f "$newest.sha256" ] || { echo "backup unhealthy: no checksum file for $(basename "$newest")" >&2; exit 1; }
(cd "$DIR" && sha256sum --status -c "$(basename "$newest").sha256") || { echo "backup unhealthy: checksum of $(basename "$newest") does not match" >&2; exit 1; }
gzip -t "$newest" 2>/dev/null || { echo "backup unhealthy: $(basename "$newest") is not a valid gzip file" >&2; exit 1; }
exit 0
