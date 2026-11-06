FROM postgres:16
COPY backup.sh /usr/local/bin/backup.sh
RUN chmod +x /usr/local/bin/backup.sh
# One backup at startup, then every 24h. E9X_BACKUP_ONCE=1 runs a single backup and exits.
ENTRYPOINT ["/bin/bash", "-c", "while true; do /usr/local/bin/backup.sh || echo 'backup failed' >&2; [ -n \"$E9X_BACKUP_ONCE\" ] && exit 0; sleep 86400; done"]
