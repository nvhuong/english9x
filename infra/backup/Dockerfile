FROM postgres:16.15@sha256:933463a6c8eba2b9f5cd414138738cd8d5519cd2c471214c222652f547a66a32
COPY backup.sh /usr/local/bin/backup.sh
RUN chmod +x /usr/local/bin/backup.sh
# One backup at startup, then every 24h. E9X_BACKUP_ONCE=1 runs a single backup and exits.
ENTRYPOINT ["/bin/bash", "-c", "while true; do /usr/local/bin/backup.sh || echo 'backup failed' >&2; [ -n \"$E9X_BACKUP_ONCE\" ] && exit 0; sleep 86400; done"]
