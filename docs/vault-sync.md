# Vault sync through the VPS

Your Obsidian vault lives on the phone today. This guide makes the same vault show up on the tablet and
the PC, with your VPS as the always-on hub. About 30 minutes, once.

```
 phone (Yapper + Obsidian) ─┐
 tablet (Obsidian)  ────────┼──  VPS (Syncthing, always on, keeps old versions)
 PC (Obsidian)      ────────┘
```

**Why Syncthing:** it syncs plain files, so it sees what Yapper writes even when Obsidian is closed.
Plugin sync (Self-hosted LiveSync, Remotely Save) only runs inside Obsidian, so Yapper's notes would wait
until you open the app. The data stays on your VPS, there's no subscription, and every connection is
TLS between devices that know each other's ID. Decision record: `docs/decisions.md` (2026-11-11).

You need: SSH into the VPS with Docker installed, and the files in `server/syncthing/` of this repo.

---

## 1. VPS: start Syncthing (10 min)

```bash
mkdir -p ~/yapper-sync/data && cd ~/yapper-sync
# copy docker-compose.yml from server/syncthing/ in the repo into this folder, then:
id -u; id -g            # if these are not 1000 and 1000, edit PUID/PGID in docker-compose.yml
docker compose up -d
docker compose ps       # STATUS should become "healthy" within a minute
```

Open the sync port, and **only** the sync port (the web UI stays private):

```bash
sudo ufw allow 22000/tcp
sudo ufw allow 22000/udp
```

If your VPS provider has its own firewall panel, allow the same two there.

Open the web UI through an SSH tunnel, from your PC:

```bash
ssh -L 8384:127.0.0.1:8384 you@your-vps
```

Then browse to <http://localhost:8384> on the PC.

In the UI:
1. It asks to set a GUI password: **Settings → GUI → user + password**. Save.
2. **Actions → Show ID**: copy the VPS **device ID**. You'll paste it on every device.

## 2. Phone: Syncthing-Fork (10 min)

1. Install **Syncthing-Fork** from F-Droid (package `com.github.catfriend1.syncthingfork`, source
   `github.com/researchxxl/syncthing-android`). The old official Syncthing Android app is discontinued.
2. Allow **All files access** when it asks. Settings → Apps → Syncthing-Fork → Battery → **Unrestricted**.
3. Devices tab → **+** → paste the VPS device ID, name it `vps`.
   *Addresses*: `tcp://<VPS-IP>:22000, quic://<VPS-IP>:22000, dynamic` (faster than discovery).
4. Folders tab → **+**:
   - *Label* `Vault`, *Folder ID* `yapper-vault` (same ID on every device).
   - *Directory*: the vault folder you picked in Yapper (Yapper → Settings shows its name).
   - Share with `vps`. *Folder type*: **Send & Receive**.
   - *Ignore patterns*: paste `server/syncthing/stignore.txt`.
5. Back in the VPS web UI: accept the new device `phone`, then accept the shared folder `Vault`:
   - *Folder path*: `/var/syncthing/vault`
   - *File Versioning* tab → **Staggered**, *Maximum Age* `365` days. This is the safety net: every
     version another device overwrote stays on the VPS for a year (`data/vault/.stversions`).
   - *Ignore Patterns* tab: paste the same list.
6. Wait for **Up to Date** on both sides.

Run conditions: in Syncthing-Fork → Settings → Run conditions, allow mobile data if you want notes to sync
away from Wi-Fi (Yapper's own uploads already use mobile data).

## 3. Tablet (Samsung, Android)

Same as the phone, steps 1–4, with one change: in step 4 pick a new empty folder (e.g. `Documents/Vault`).
In the VPS UI, accept `tablet`, then **edit the folder Vault → Sharing → tick `tablet`**. When the files
arrive, open Obsidian → *Open folder as vault* → that folder.

## 4. PC

- **Windows**: install *Syncthing Windows Setup* (`github.com/Bill-Stewart/SyncthingWindowsSetup`, linked
  from syncthing.net/downloads). It runs Syncthing in the background and opens the UI at localhost:8384.
- **Linux**: `sudo apt install syncthing`, then `systemctl --user enable --now syncthing`.

Then: add device `vps` with its ID (and the addresses line from step 2.3), accept on the VPS, share the
folder `Vault` with the PC from the VPS UI, accept it on the PC into an empty folder, paste the ignore
patterns, and open that folder in Obsidian.

## 5. Check it works (3 min)

1. Phone: press the side key, say "test sync, buy milk tomorrow".
2. Within a minute or two the capture note shows up in the PC's Obsidian, Inbox folder.
3. PC: type a line into any note. It shows up on the phone.

---

## How Yapper stays safe with sync on

- **Yapper only appends or creates.** It never rewrites your text, so two devices rarely touch the same
  bytes.
- **Every append is read back.** Syncthing replaces a file by writing a temp copy and renaming it. If that
  lands in the same moment Yapper appends, the append can vanish. Yapper now re-reads the file after every
  append, and appends again if its block is missing (up to 3 tries; the block's `%%yap:…%%` marker
  prevents doubles). If all tries fail, the job fails and WorkManager retries it later. The capture bundle
  on the phone is never deleted before that.
- **Conflict copies are never hidden.** If you edit a note on the PC while Yapper appends to it on a phone
  that's offline, Syncthing keeps both versions: `Note.sync-conflict-20261111-130000-XXXXXXX.md`. Yapper:
  - never files anything into a conflict copy (they're kept out of `_yapper/index.md`),
  - lists every conflict copy with links in **`_yapper/sync-conflicts.md`** on each sort.
  Open the copy, move what you want into the main note, delete the copy.
- **Old versions on the VPS.** Staggered versioning keeps what any device overwrote, for 365 days.

## Don'ts

- Don't run a second sync tool (Obsidian Sync, LiveSync, Google Drive) on the same folder.
- Don't expose port 8384 on the VPS. Use the SSH tunnel.
- Don't share the folder as *Receive Only* or *Send Only* on the phone: Yapper and you both write there.

## Troubleshooting

| What you see | Fix |
|---|---|
| Device shows *Disconnected* | Check the VPS firewall for 22000 tcp+udp, and the `tcp://<VPS-IP>:22000` address. |
| Phone stops syncing in the background | Syncthing-Fork battery → Unrestricted; check its run conditions. |
| Lots of `workspace…sync-conflict` files | Ignore patterns missing on a device: paste `stignore.txt` on all of them. |
| Notes missing on the VPS | `docker compose logs syncthing`; folder path must be `/var/syncthing/vault`. |
| Need an old version | VPS UI → folder Vault → *Versions*, or `~/yapper-sync/data/vault/.stversions/`. |
