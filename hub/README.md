# Yapper hub + Today board

A small backend that holds Yasir's tasks, raw transcripts and drawing pages, and serves the Today board.
Yapper (phone) sends tasks to it; the board (Tab S9, also the phone) shows and changes them.

```
Yapper (phone)  <-->  Hub (Oracle VPS, Docker)  <-->  Today board (tablet browser, installed)
                          ^ HTTPS via Cloudflare Tunnel: https://board.yasiraloufi.dev
```

- Code: `src/main/java/.../hub/`. API: `ApiController` (spec section 3). Board: `src/main/resources/static/`.
- Tests: `./gradlew -p hub test` (from the repo root). Run locally:
  `HUB_TOKENS=me=$(openssl rand -hex 32) ./gradlew -p hub bootRun`, then open http://localhost:8080.
- Data: one SQLite file, `/data/hub.db` in the container (`hub/deploy/data/` on the VPS).

## Set it up on the VPS (once, about 15 minutes)

1. **Get the code** on the VPS (Docker with the compose plugin is already there for Syncthing):
   `git clone https://github.com/YsuLab/yapper && cd yapper`
2. **Make two device tokens**: run `openssl rand -hex 32` twice. One for the phone, one for the tablet.
3. **Cloudflare Tunnel** (Cloudflare dashboard → Zero Trust → Networks → Tunnels → Create a tunnel →
   Cloudflared). Name it `yapper`. On the "install connector" step copy only the long token. Add a public
   hostname: subdomain `board`, domain `yasiraloufi.dev`, service type `HTTP`, URL `hub:8080`.
   Cloudflare creates the DNS record itself. No port opens on the VPS.
4. **Secrets**: `cp hub/deploy/env.example hub/deploy/.env`, then put the two tokens in `HUB_TOKENS`
   (`phone=…,tablet=…`) and the tunnel token in `TUNNEL_TOKEN`. `.env` is git-ignored.
5. **Start**: `docker compose -f hub/deploy/docker-compose.yml up -d --build` (the first build takes a few
   minutes). Check: `curl -s https://board.yasiraloufi.dev/api/health -H "Authorization: Bearer <phone token>"`
   answers `{"ok":true,"device":"phone",...}`.

Update later: `git pull && docker compose -f hub/deploy/docker-compose.yml up -d --build`.
Revoke a device: remove its pair from `HUB_TOKENS`, then `docker compose -f hub/deploy/docker-compose.yml up -d`.

## Devices

- **Phone (Yapper)**: Settings → Today board → paste the phone token → *Save* → *Test the hub*. Then
  *Send open vault tasks to the board (once)*.
- **Tablet**: in Chrome open `https://board.yasiraloufi.dev/#t=<tablet token>` (the token leaves the address
  bar at once and stays on the tablet), then ⋮ → *Add to home screen* → *Install*. Open it from the home screen:
  it runs full screen. Or open the plain address and paste the token on the first screen.
- The same page works on the phone (one column). Give the phone browser its own token if you like.

## Backups

- Every night at 03:30 Riyadh the hub writes `data/backups/hub-YYYY-MM-DD.db` (SQLite online backup) and keeps
  14. Done tasks older than 30 days are archived (hidden, never deleted).
- **Restore**: `sh hub/deploy/restore.sh hub-2026-11-24.db` (stops the hub, keeps the current database as
  `hub.db.before-restore-…`, copies the backup in, starts the hub).
- **Test a restore once** (milestone M5):
  `docker compose -f hub/deploy/docker-compose.yml exec hub sqlite3 /data/hub.db ".backup /data/backups/hub-0000-test.db"`
  then `sh hub/deploy/restore.sh hub-0000-test.db` and reload the board: the same tasks are there.
- **Off-site, weekly** (decision 10): on Sakhr, add to `crontab -e`:
  `0 5 * * 5 /path/to/yapper/hub/deploy/pull-backup.sh ubuntu@<vps> /home/ubuntu/yapper/hub/deploy/data/backups ~/yapper-hub-backups`

## What it stores

Tasks, raw transcripts and drawing pages. Never secrets, Tabby internal data or customer information (same
rule as the vault).
