# Owner setup guide

Things only you can do. Secrets live in GitHub secrets and the private project files, never in this repo.

## A. Signing key (once, ~3 min)

Every APK must be signed with the **same** key, or Android refuses to install it over the previous one.

### Quick path (key already made for you)
Claude generated the keystore on 2026-11-11 and put it in the **private** project files folder
`release/signing/` (never in this repo). The README there lists the four exact values.

1. Back up `yapper-release.jks` and the README (password manager or encrypted drive). Lose it and the next
   APK can't update the installed app.
2. Repo → **Settings → Secrets and variables → Actions → New repository secret**, four times:
   `YAPPER_KEYSTORE_BASE64`, `YAPPER_KEYSTORE_PASSWORD`, `YAPPER_KEY_ALIAS`, `YAPPER_KEY_PASSWORD`.
3. Check: the next CI run's summary shows "Signed with release key" with a SHA-256 starting `2F:AE:A8:2F`.
4. Uninstall the old (debug-signed) Yapper **once**, install the new APK. From then on, every APK updates in place.

### Make your own key instead (optional)

### 1. Get `keytool`
It ships with any JDK. Pick one:
- Windows: `winget install EclipseAdoptium.Temurin.21.JDK`, then open a **new** PowerShell.
- If Android Studio is installed: use `"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"`.
- macOS: `brew install --cask temurin`. Linux: `sudo apt install openjdk-21-jdk-headless`.

Check: `keytool -help` prints usage.

### 2. Make the keystore
Pick one strong password and save it in your password manager first. Then, in a folder **outside** any git repo:

```
keytool -genkeypair -v -keystore yapper-release.jks -storetype PKCS12 -alias yapper -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Yasir, O=YsuLab, C=SA"
```

Enter the password when asked. PKCS12 uses one password for the store and the key, so both secrets below
get the **same** password.

### 3. Back it up
Copy `yapper-release.jks` to two safe places (e.g. password manager attachment + an encrypted drive).
If you lose it, the next APK can't update the installed app: you'd uninstall and lose app data.
Never commit it, upload it to chat, or paste the password anywhere except GitHub secrets.

### 4. Base64-encode it
- Windows PowerShell:
  ```
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\yapper-release.jks")) | Set-Content -NoNewline yapper-release.b64
  ```
- macOS: `base64 -i yapper-release.jks -o yapper-release.b64`
- Linux: `base64 -w0 yapper-release.jks > yapper-release.b64`

### 5. Add four GitHub secrets
Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Name | Value |
|---|---|
| `YAPPER_KEYSTORE_BASE64` | full contents of `yapper-release.b64` |
| `YAPPER_KEYSTORE_PASSWORD` | your password |
| `YAPPER_KEY_ALIAS` | `yapper` |
| `YAPPER_KEY_PASSWORD` | the same password |

Or with the GitHub CLI (`gh auth login` first), from the folder with the files:
```
gh secret set YAPPER_KEYSTORE_BASE64 --repo YsuLab/yapper < yapper-release.b64
gh secret set YAPPER_KEYSTORE_PASSWORD --repo YsuLab/yapper
gh secret set YAPPER_KEY_ALIAS --repo YsuLab/yapper --body yapper
gh secret set YAPPER_KEY_PASSWORD --repo YsuLab/yapper
```
(`gh` prompts for the password values, so they don't land in shell history.)

Then delete `yapper-release.b64` (the `.jks` backup is what you keep).

### 6. Confirm
Re-run the latest **Android CI** workflow (Actions tab → run → *Re-run all jobs*). The run summary shows
**"Signed with release key"** and a certificate SHA-256. That fingerprint must stay the same on every build.
If you see a yellow warning "secret not set", step 5 is incomplete.

## B. Install the APK on the phone

**From a PR:** open the PR → *Checks* → *Android CI* → *Summary* → **Artifacts** → `yapper-apk-<n>`.
It downloads as a zip; open it in **My Files**, extract, tap the `.apk`.

**From main:** https://github.com/YsuLab/yapper/releases/latest/download/yapper.apk (same link every time).

First install only: Android asks to allow your browser / My Files to *Install unknown apps*. Allow it.
Google Play Protect may warn about an unknown developer: tap *More details → Install anyway*.

The repo is public, so the download needs no GitHub sign-in.

## C. Phone setup for capture (M1, ~5 min)

1. Install the APK and open **Yapper**.
2. Tap **1 · Allow microphone and notifications** → Allow both.
3. Tap **2 · Set Yapper as digital assistant** → *Digital assistant app* → **Yapper**.
   (Or: Settings → Apps → Choose default apps → Digital assistant app.)
   In the same screen turn on **Use text from screen** and **Use screenshot** (names vary a bit by One UI).
4. Side key: Settings → Advanced features → **Side button** → *Press and hold* → **Digital assistant**.
5. Back in Yapper tap **3 · Choose Obsidian vault folder** → pick (or create) your vault folder →
   *Use this folder* → *Allow*. Yapper creates `Inbox/` inside it.
6. (Optional now, needed for transcripts) add one or more transcriber keys, then **Save**:
   - **OpenRouter** (simplest: one key): openrouter.ai → Keys. The model field lists several speech models,
     comma-separated; each one gets benchmarked.
   - **OpenAI**: platform.openai.com → API keys.
   - **ElevenLabs**: elevenlabs.io → Developers → API keys (Speech to Text permission).
   - **Groq**: console.groq.com → API keys (has a free tier).
   - **Self-hosted** (later, GPU home lab): an OpenAI-compatible server such as Speaches, reachable over
     HTTPS (e.g. Tailscale Serve). URL ends in `/v1`.
   Keep **Benchmark** on during M1: each capture is transcribed by every provider you added.
7. Battery: Settings → Apps → Yapper → Battery → **Unrestricted**, so queued work runs on time.

All four status lines at the top should show ✅ (transcribers can stay empty: notes then hold audio only).

## D. Vault sync (recommended)

**Syncthing with the VPS as the hub**: phone ↔ VPS ↔ tablet/PC. Full steps, server config and how Yapper
stays safe with sync on: [`docs/vault-sync.md`](vault-sync.md).

## E. Reminders: Microsoft To Do → Samsung Reminder (M3, ~15 min, once)

Microsoft requires every app registration to live in a directory (tenant). A personal account gets one by
signing up for Azure first.

1. Open **portal.azure.com** with the Microsoft account Samsung Reminder will sync with. If it says you have no
   directory or subscription, create a free account (**Azure for Students** with your KAU email needs no card;
   the normal free account asks for a card for verification only). Nothing here costs money.
2. **Microsoft Entra ID → App registrations → New registration**
   - Name `Yapper`
   - Supported account types: **Personal Microsoft accounts only**
   - Redirect URI: **Public client/native (mobile & desktop)** → `yapper://auth`
3. **API permissions → Add a permission → Microsoft Graph → Delegated** → `Tasks.ReadWrite` and `offline_access`.
4. **Authentication** → *Allow public client flows* → **Yes** → Save.
5. Copy the **Application (client) ID** (a GUID; not a secret).
6. Phone: **Samsung Reminder → ☰ → Settings → sync with Microsoft To Do** → sign in with the same account.
7. Yapper → *Reminders* → paste the client ID → **Sign in to Microsoft** → approve → pick the list Samsung
   Reminder syncs.
8. Tap **Test sync**. Note how long until the task appears in Samsung Reminder, and whether it rings at the time.
   If it is late, raise the *Alarm window* so more reminders ring as phone alarms.

Until you sign in, every reminder rings as a phone alarm, so nothing is missed.

## F. Ramble, plan and debrief (M4–M5)
- Quick Settings: edit the tiles and add **Ramble**. Long-press the Yapper icon for *Ramble / Plan my day / Debrief*.
- Morning and evening nudges default to 08:00 and 21:30; change or clear them in Yapper → *Daily nudges*.
