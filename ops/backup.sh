#!/bin/sh
set -eu
umask 077
backup() (
    set -eu
    staging=$(mktemp /backups/.delivery.XXXXXX)
    trap 'rm -f "$staging"' EXIT
    MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -h db -u delivery --single-transaction \
        --quick --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF \
        --default-character-set=utf8mb4 delivery > "$staging" || exit 1
    test -s "$staging" || exit 1
    mv "$staging" "/backups/delivery-$(date -u +%Y%m%dT%H%M%SZ)-${staging##*.}.sql"
)
if [ "${1:-}" = once ]; then
    backup
    exit
fi
while :; do
    if backup; then sleep 86400; else sleep 60; fi
done
