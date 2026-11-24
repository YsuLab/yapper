#!/bin/sh
# Weekly off-site copy of the hub's newest nightly backup. Run on Sakhr (home lab), from cron:
#   0 5 * * 5  /path/to/pull-backup.sh ubuntu@VPS_HOST /path/to/yapper/hub/deploy/data/backups ~/yapper-hub-backups
# Keeps 8 weekly copies. Needs SSH key access to the VPS.
set -eu
SRC_HOST="$1"; SRC_DIR="$2"; DEST="$3"
mkdir -p "$DEST"
NEWEST=$(ssh "$SRC_HOST" "ls -1 '$SRC_DIR'/hub-*.db | sort | tail -1")
scp -q "$SRC_HOST:$NEWEST" "$DEST/"
# Check the copy opens and has the task table before trusting it.
if command -v sqlite3 >/dev/null; then
  sqlite3 "$DEST/$(basename "$NEWEST")" "PRAGMA integrity_check; SELECT count(*) FROM task;" >/dev/null
fi
ls -1 "$DEST"/hub-*.db | sort | head -n -8 | xargs -r rm --
echo "Copied $(basename "$NEWEST")"
