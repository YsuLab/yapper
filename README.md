# Yapper

One button. Talk for 5 seconds or 50 minutes. Everything lands in the right place.

Hold the phone's side key and talk. Yapper records, transcribes, and an AI sorter files what you said:
reminders go to Samsung Reminder (through Microsoft To Do), ideas go into the right Obsidian note,
and your day becomes a plan. You never pick a folder, a list, or a format.

![Capture sheet: smoke rises from the bottom, the moon is the voice meter](docs/images/capture-sheet.png)

**Download the APK:** https://github.com/YsuLab/yapper/releases/latest/download/yapper.apk
(Android 10+. Open it on the phone and allow "Install unknown apps" once.)

## What it does

- **Side-key capture.** Yapper is the phone's digital assistant. Hold the side key, talk, let go. It also grabs the screen text, so "remind me about this" knows what "this" is.
- **Sorted into Obsidian.** An LLM reads `_yapper/rules.md` in your vault and appends to the right note. Edit that note to change routing. No code changes.
- **Reminders.** "Tomorrow at 9" becomes a Microsoft To Do task that Samsung Reminder rings. Soon reminders also ring as local exact alarms.
- **Ramble, plan, debrief.** Long recordings with the screen off. A morning plan with time blocks, an evening debrief, and a Saturday weekly review.
- **Ask your vault.** Ask a question by voice; the answer comes back as a notification with its source note.
- **Share to Yapper.** Share WhatsApp text, images, or timetables into Yapper, with an optional voice note.
- **Prayer-time reminders.** "After Asr" becomes a real time (Umm al-Qura, Jeddah).
- **Undo everything.** Every write is tagged `%%yap:<id>%%`, so Undo removes exactly that block. Yapper only appends or creates. It never deletes your text.
- **Never loses a capture.** The audio is saved first. Processing runs later in WorkManager and retries offline.

Saudi Arabic and English mixed in one sentence works. Transcription and sorting go through any
OpenAI-compatible API. The default is one [OpenRouter](https://openrouter.ai) key for both.

## Try it in 5 minutes

1. Install the APK from the link above and open **Yapper**.
2. Tap **Allow microphone and notifications**.
3. Tap **Set Yapper as digital assistant**. Turn on **Use text from screen** in the same screen.
4. Samsung: Settings → Advanced features → **Side button** → *Press and hold* → **Digital assistant**.
5. Tap **Choose Obsidian vault folder** and pick (or create) a folder.
6. Get a key at openrouter.ai → **Keys**. Paste it under **Transcribers → OpenRouter**, then **Save**.
   The sorter uses the same key.
7. Hold the side key and say: "Remind me tomorrow at 9 to call the dentist, and note an idea for my app: dark mode."

Your keys stay on the phone, encrypted with the Android Keystore. Yapper has no server and no analytics.

Full phone setup, including Microsoft To Do sign-in: [`docs/setup-guide.md`](docs/setup-guide.md).

## Fork it and build your own

You need a GitHub account. No Android Studio required: GitHub Actions builds the APK.

1. **Fork** this repo (top right).
2. In your fork: **Actions** tab → *I understand my workflows, enable them*.
3. **Actions → Android CI → Run workflow** on `main`. Tick *publish* to get your own download link.
4. When it finishes, your APK is at
   `https://github.com/<you>/yapper/releases/latest/download/yapper.apk`.

Without a signing key, CI builds a **debug** APK. That works, but each build has a new random key,
so you uninstall before installing the next one. For updates in place, add your own key once:

1. Make a keystore (any JDK has `keytool`):
   ```
   keytool -genkeypair -v -keystore my-release.jks -storetype PKCS12 -alias yapper -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Base64 it: `base64 -w0 my-release.jks > my-release.b64` (macOS: `base64 -i my-release.jks -o my-release.b64`).
3. Your fork → **Settings → Secrets and variables → Actions**, add four secrets:

   | Name | Value |
   |---|---|
   | `YAPPER_KEYSTORE_BASE64` | contents of `my-release.b64` |
   | `YAPPER_KEYSTORE_PASSWORD` | your password |
   | `YAPPER_KEY_ALIAS` | `yapper` |
   | `YAPPER_KEY_PASSWORD` | the same password |

4. Run the workflow again. The run summary shows "Signed with release key".

Keep the `.jks` file and password safe and **never commit them**. `.gitignore` already blocks `*.jks`.

⚠️ Your build and the official APK share the app ID `com.ysulab.yapper`, but use different keys.
Uninstall one before installing the other. To keep both, change `applicationId` in `app/build.gradle.kts`.

## Change it with Claude Code

This whole app was built by Claude Code from a voice-style brief, one milestone per pull request.
The repo is set up so Claude can keep going:

- [`CLAUDE.md`](CLAUDE.md): the rules Claude follows (stack, hard rules, how to build).
- [`docs/spec.md`](docs/spec.md): what the app must do.
- [`docs/milestones.md`](docs/milestones.md): the build order.
- [`docs/decisions.md`](docs/decisions.md): every non-trivial decision, with the reason.

Open your fork in Claude Code and ask for a change, for example "add a Notion target" or
"make the capture sheet blue". Claude has no Android SDK in the cloud, so it pushes a branch and
GitHub Actions builds and tests the APK.

## Build locally

```
./gradlew testDebugUnitTest   # unit tests (JUnit 4)
./gradlew assembleDebug       # debug APK in app/build/outputs/apk/debug/
```

Java 17, Android SDK 36, minSdk 29. App code is Java; build files are Gradle Kotlin DSL.

## Code map

| Folder | What lives there |
|---|---|
| `assist/` | Side-key entry: the voice interaction service and the capture session |
| `capture/` | Capture bundles, silence detection, screen text |
| `work/` | WorkManager jobs: save, transcribe, sort |
| `transcribe/` | Pluggable speech-to-text clients |
| `sort/` | The LLM sorter and its tools |
| `vault/` | Append-only Obsidian writes through the Storage Access Framework |
| `reminders/` | Microsoft To Do (OAuth PKCE) and local exact alarms |
| `today/`, `review/` | Day plan, debrief, weekly review |
| `ask/` | Ask-your-vault search and answers |
| `prayer/` | Umm al-Qura prayer times |
