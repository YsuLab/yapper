# Owner setup guide

Things only you can do. Claude never sees the keystore or its password.

## A. Signing key (once, ~10 min)

Every APK must be signed with the **same** key, or Android refuses to install it over the previous one.
You create that key once, keep it safe, and give it to GitHub as secrets.

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

**From main:** the repo's **Releases** → `latest` → tap the `.apk`. Same link every time.

First install only: Android asks to allow your browser / My Files to *Install unknown apps*. Allow it.
Google Play Protect may warn about an unknown developer: tap *More details → Install anyway*.

The repo is private, so you must be signed in to GitHub in the phone browser (or use the GitHub app).

## C. Later milestones (not needed for M0)
- **Claude API key** (M2): claude.ai → Settings → Billing → API credits → Link organization. In Console, create
  workspace `yapper` with a spend limit, then an API key in it. You'll paste it into the app, never the repo.
- **Microsoft Entra app** (M3): Claude will give exact, current steps then.
- **Phone settings** (M1): default digital assistant = Yapper, enable screen text + screenshot,
  side key → Press and hold → Digital assistant, grant microphone/notifications/exact alarms/vault folder.
