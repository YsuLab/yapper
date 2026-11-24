#!/bin/sh
# Restore the hub from one of its nightly backups. Run on the VPS from the repository root:
#   sh hub/deploy/restore.sh hub-2026-11-24.db        (a file in hub/deploy/data/backups/)
# The current database is kept as data/hub.db.before-restore-<time>.
set -eu
NAME="$(basename "$1")"
COMPOSE="docker compose -f hub/deploy/docker-compose.yml"
test -f "hub/deploy/data/backups/$NAME" || { echo "No such backup: hub/deploy/data/backups/$NAME"; exit 1; }
STAMP=$(date +%Y%m%d-%H%M%S)
$COMPOSE stop hub
$COMPOSE run --rm --no-deps --entrypoint sh hub -c "
  set -e
  sqlite3 /data/backups/$NAME 'PRAGMA integrity_check;' | grep -qx ok
  for f in hub.db hub.db-wal hub.db-shm; do [ -f /data/\$f ] && mv /data/\$f /data/\$f.before-restore-$STAMP || true; done
  cp /data/backups/$NAME /data/hub.db
  echo \"Tasks in the restored database: \$(sqlite3 /data/hub.db 'SELECT count(*) FROM task;')\"
"
$COMPOSE start hub
echo "Restored from $NAME."
