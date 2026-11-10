# mc (a static Go binary) mirrors the media bucket through the S3 API, so the copy holds only complete objects: the previous way (cp -a of the live data
# directory) could catch a half-written file and left a stale minio.tmp behind (P1 final review, ledger 19 and 103).
FROM chainguard/minio-client@sha256:10388d580a724f356b94c9b2631b5a179039cabe8c76e9b37d86ed007d569b05 AS mc

FROM postgres:16.15@sha256:933463a6c8eba2b9f5cd414138738cd8d5519cd2c471214c222652f547a66a32
COPY --from=mc /usr/bin/mc /usr/local/bin/mc
COPY backup.sh restore.sh mirror-media.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/restore.sh /usr/local/bin/mirror-media.sh
# One backup at startup, then every 24h. E9X_BACKUP_ONCE=1 runs a single backup and exits.
ENTRYPOINT ["/bin/bash", "-c", "while true; do /usr/local/bin/backup.sh || echo 'backup failed' >&2; [ -n \"$E9X_BACKUP_ONCE\" ] && exit 0; sleep 86400; done"]
