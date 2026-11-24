# Yapper hub + Today board

A small backend that holds Yasir's tasks, raw transcripts and drawing pages, and serves the Today board.
Yapper (phone) sends tasks to it; the board (Tab S9, also the phone) shows and changes them.

```
Yapper (phone)  <-->  Hub (Contabo VPS, Dokploy)  <-->  Yapper "Today" mode (tablet, full screen)
                          ^ HTTPS by Dokploy (Traefik + Let's Encrypt): https://board.yasiraloufi.dev
```

- Code: `src/main/java/.../hub/`. API: `ApiController` (spec section 3). Board page: `src/main/resources/static/`.
  The same page is built into the APK (`assets/board/`) for the tablet mode, and also served by the hub for any
  browser.
- Tests: `./gradlew -p hub test` (from the repo root). Run locally:
  `HUB_TOKENS=me=$(openssl rand -hex 32) ./gradlew -p hub bootRun`, then open http://localhost:8080.
- Data: one SQLite file, `/data/hub.db` in the container (`/srv/yapper-hub/` on the server).

## Set it up in Dokploy (once, about 10 minutes)

1. **Two device tokens**: run `openssl rand -hex 32` twice. One for the phone, one for the tablet.
2. **DNS** (Cloudflare): an `A` record `board` → the Contabo server's IP, **DNS only** (grey cloud), so
   Dokploy's Let's Encrypt certificate works without extra settings.
3. **Dokploy** → your project → *Create Service* → *Application*, name `yapper-hub`:
   - *Provider*: GitHub (or Git with `https://github.com/YsuLab/yapper`), branch `main`.
   - *Build Type*: **Dockerfile**. *Docker File*: `hub/Dockerfile`. *Docker Context Path*: `.` (the repo root).
   - *Environment*: `HUB_TOKENS=phone=<first token>,tablet=<second token>`
   - *Advanced → Volumes/Mounts*: bind mount host path `/srv/yapper-hub` → mount path `/data`
     (the database and backups live there and survive redeploys). Create the folder first on the server:
     `sudo mkdir -p /srv/yapper-hub` (Docker Swarm, which Dokploy uses, will not create it).
   - *Domains*: host `board.yasiraloufi.dev`, path `/`, container port `8080`, HTTPS on, certificate
     Let's Encrypt.
   - *Deploy*. The first build takes a few minutes.
4. **Check**: `curl -s https://board.yasiraloufi.dev/api/health -H "Authorization: Bearer <phone token>"`
   answers `{"ok":true,"device":"phone",...}`.

Update later: *Deploy* again in Dokploy (or turn on auto-deploy for `main`).
Revoke a device: remove its pair from `HUB_TOKENS` and redeploy.
No Dokploy? `hub/deploy/docker-compose.yml` runs the same image on plain Docker (put HTTPS in front).

## Devices

- **Phone (Yapper)**: Settings → Today board → paste the **phone** token → *Save* → *Test the hub*. Then
  *Send open vault tasks to the board (once)*.
- **Tablet**: install the same Yapper APK. Settings → Today board → paste the **tablet** token → *Save*. A
  **Today** icon is on the home screen: it opens the board full screen, and the screen stays on while the
  tablet is charging. The board's own screen care (8 px shift every 4 min, dim after 10 idle min, black
  01:00–06:00, moon button) protects the panel. Drawing pages save to Pictures/Yapper.
- Any browser also works: `https://board.yasiraloufi.dev/#t=<token>`.

## Backups

- Every night at 03:30 Riyadh the hub writes `/srv/yapper-hub/backups/hub-YYYY-MM-DD.db` (SQLite online backup) and keeps
  14. Done tasks older than 30 days are archived (hidden, never deleted).
- **Restore**: write the backup's file name into `/srv/yapper-hub/RESTORE`, then restart the app in Dokploy:
  `echo hub-2026-11-24.db | sudo tee /srv/yapper-hub/RESTORE`. At start the hub checks the backup, keeps the
  current database as `hub.db.before-restore-…`, copies the backup in and deletes `RESTORE` (a bad name is
  left as `RESTORE.failed` and nothing changes).
- **Test a restore once** (milestone M5): make a copy now with
  `docker exec $(docker ps -qf name=yapper-hub) sqlite3 /data/hub.db ".backup /data/backups/hub-0000-test.db"`,
  then restore it as above with `hub-0000-test.db` and reload the board: the same tasks are there.
- **Off-site, weekly** (decision 10): on Sakhr, add to `crontab -e`:
  `0 5 * * 5 /path/to/yapper/hub/deploy/pull-backup.sh root@<contabo> /srv/yapper-hub/backups ~/yapper-hub-backups`

## What it stores

Tasks, raw transcripts and drawing pages. Never secrets, Tabby internal data or customer information (same
rule as the vault).
