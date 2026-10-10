#!/usr/bin/env bash
# Nightly backup (DESIGN.md §5): consistent SQLite `.backup` → gzip → GPG (AES-256, symmetric)
# → BACKUP_DIR, optionally copied to Swiss object storage with rclone, 30 days kept.
#
# Run from cron on the host, e.g.  15 4 * * *  /opt/stadtlaerm/server/scripts/backup.sh
# Restore:  gpg -d stadtlaerm-YYYYmmdd-HHMM.sqlite.gz.gpg | gunzip > stadtlaerm.sqlite
set -euo pipefail

cd "$(dirname "$0")/.."
set -a; [ -f .env ] && . ./.env; set +a

PASSFILE=${BACKUP_PASSPHRASE_FILE:-/root/.stadtlaerm-backup-passphrase}
DIR=${BACKUP_DIR:-/var/backups/stadtlaerm}
KEEP_DAYS=${BACKUP_KEEP_DAYS:-30}
STAMP=$(date -u +%Y%m%d-%H%M)
OUT="$DIR/stadtlaerm-$STAMP.sqlite.gz.gpg"

[ -r "$PASSFILE" ] || { echo "passphrase file $PASSFILE missing" >&2; exit 1; }
mkdir -p "$DIR"
chmod 700 "$DIR"

# .backup inside the container gives a consistent copy even while the app writes (WAL).
docker compose exec -T app sqlite3 /data/stadtlaerm.sqlite ".backup /data/backup.tmp.sqlite"
docker compose exec -T app cat /data/backup.tmp.sqlite \
  | gzip -9 \
  | gpg --batch --yes --symmetric --cipher-algo AES256 --passphrase-file "$PASSFILE" -o "$OUT.part"
docker compose exec -T app rm -f /data/backup.tmp.sqlite
mv "$OUT.part" "$OUT"

if [ -n "${BACKUP_REMOTE:-}" ]; then
  # e.g. BACKUP_REMOTE=swissbackup:stadtlaerm  (an rclone remote on Infomaniak object storage)
  rclone copy "$OUT" "$BACKUP_REMOTE/"
  rclone delete --min-age "${KEEP_DAYS}d" "$BACKUP_REMOTE/"
fi

find "$DIR" -name 'stadtlaerm-*.sqlite.gz.gpg' -mtime +"$KEEP_DAYS" -delete
echo "backup written: $OUT"
