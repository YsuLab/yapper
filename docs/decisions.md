# Decisions

Newest first. Format: date — decision — why.

## 2026-11-09 — M1 (Capture)
- **Capture ends on silence (2 s default, configurable) or a tap. Side-key release is not used.**
  Android starts the assistant on the long-press and does not deliver the side key's release (or any
  power-key event) to a `VoiceInteractionSession`; Samsung handles the side key in the system. A second
  side-key press while listening is treated as "done" (same as a tap).
- **Silence detector polls `MediaRecorder.getMaxAmplitude()` every 100 ms with an adaptive noise floor**
  (300 ms calibration, speech = max(900, 2.5 × floor)). No speech for 6 s → save a screen-only capture.
  5 min hard cap for quick capture (ramble mode is M4). Pure Java, unit-tested.
- **Audio = Ogg/Opus, 16 kHz mono, 24 kbps; fallback AAC/.m4a.** ~180 KB/min. Accepted by OpenAI, Groq,
  ElevenLabs and Whisper servers, plays inside Obsidian, and a truncated Ogg file is still mostly playable
  if the app dies mid-capture (an .m4a without its index is not).
- **Bundle first, vault second.** Every capture is a folder in app-private storage
  (`meta.properties`, audio, `screenshot.jpg`, `screen.txt`). The folder exists before recording starts.
  State moves forward only (RECORDING → RECORDED → INBOX → DONE). Atomic temp-file + rename writes.
  Bundles are never deleted in M1. `reconcile()` runs on app open, boot, assistant ready and each new
  capture, and re-queues anything unfinished. A RECORDING bundle older than 10 min is recovered.
- **Two WorkManager jobs per capture.** `inbox-<id>`: expedited, no constraints, so the Inbox note appears
  even offline. `transcribe-<id>`: expedited, needs network, exponential backoff from 30 s, gives up on a
  provider after 25 attempts (writes "failed" in the note). Unique names + KEEP make re-queuing safe.
- **Inbox note is written once; transcripts are appended** as their own `## Transcript · <provider>`
  blocks, each tagged `%%yap:<id>%%`. Follows the append-only write rule. Attachments go to
  `Inbox/attachments/<id>.ogg|jpg`. File name `Inbox/Capture YYYY-MM-DD HHmmss.md` in Asia/Riyadh time.
- **SAF files are created as `application/octet-stream`** so providers keep the exact `.md` / `.ogg` name.
- **Screenshot: kept only if not blank.** `FLAG_SECURE` apps give a black bitmap; we sample a 48×96 grid
  and drop it if < 0.5 % of pixels have content. JPEG q80 is used (PNG of a 1440×3120 screen is too slow
  for the < 1 s goal). It's stored in the vault for the owner; sending it to an LLM is an M2 decision
  (text first, screenshot only when needed).
- **Claude is not a transcriber.** The Claude Messages API takes text, images and PDFs, not audio. So the
  transcriber is a separate pluggable provider.
- **Transcriber candidates (benchmark):** OpenAI `gpt-4o-transcribe`, ElevenLabs `scribe_v2`, Groq
  `whisper-large-v3`, and a **self-hosted** slot (any OpenAI-compatible `/v1/audio/transcriptions`
  server, e.g. Speaches / faster-whisper on the GPU home lab). One OpenAI-compatible client covers three
  of them. Whisper-style providers get a short Arabic prompt that biases toward Saudi dialect with English
  terms kept in Latin script. Language is auto-detect by default (forcing `ar` tends to transliterate the
  English words).
- **Benchmark runs inside normal use.** With "Benchmark" on (default during M1), every configured
  provider transcribes every capture and appends its transcript + latency. Keys never leave the phone.
  `tools/transcribe-bench/score.py` scores CER/WER per provider against a `## Reference` section the owner
  adds to ~10 notes, with Arabic normalization (diacritics, alef/ya/ta-marbuta forms, Arabic-Indic digits).
  Adding a provider later re-runs it on old captures too.
- **Self-hosted needs HTTPS** (Tailscale Serve / `tailscale cert`, or a reverse proxy on the VPS).
  Cleartext HTTP stays blocked, so keys and audio never cross the network unencrypted.
- **API keys: AES-GCM key in the Android Keystore**, ciphertext in private prefs, `allowBackup=false`.
  Not Jetpack `security-crypto` (deprecated). Keys are never logged; HTTP error text in notes never
  includes request headers.
- **Vault sync recommendation: Syncthing, VPS as the always-on hub.** Phone ↔ VPS ↔ PC/laptop/tablet.
  Works with plain files that Yapper writes through SAF while Obsidian is closed (plugin-based sync only
  sees changes when Obsidian runs). On Android use the maintained Syncthing-Fork (the official Android app
  was discontinued in Dec 2024). Self-hosted, no subscription, fits "VPS for data". Owner's call; Yapper
  doesn't depend on it.
- **AndroidX added:** `work-runtime` 2.11.2, `documentfile` 1.1.0, `annotation`. No Kotlin in app code.

## 2026-11-09 — M0
- **Package `com.ysulab.yapper`.** Matches the owner's GitHub handle. Permanent: changing it means reinstalling.
- **Plain `android.app.Activity`, no AndroidX yet.** M0 needs one screen. Fewer dependencies, faster CI. Add
  AndroidX (WorkManager, core) in M1 when it is needed.
- **minSdk 29 (Android 10), target/compile SDK 36.** Owner's Galaxy is recent; 29 gives the modern
  `VoiceInteractionSession` APIs and `Theme.DeviceDefault.DayNight`. Revisit when the phone model is known.
- **AGP 8.13.0 + Gradle 8.14.3, Java 17.** Stable, well-known combination that supports SDK 36. The cloud VM
  cannot reach dl.google.com, so versions are validated by CI, not locally. Upgrade to AGP 9 later in its own PR.
- **Release signing from GitHub secrets only.** One stable keystore made by the owner, stored base64 in a
  secret. Every APK is signed with it, so each build installs over the last. The key never touches the repo or Claude.
- **No-secret fallback = debug APK with a CI warning.** CI stays green before the owner adds the secrets, but
  that APK will not upgrade cleanly. Once the secrets exist, CI always signs with the release key.
- **`versionCode` = `GITHUB_RUN_NUMBER`.** Monotonic per repo, so Android accepts each new APK as an update.
- **Rolling `latest` pre-release on main.** A fixed download page for the phone, no zip to unpack. PR builds
  stay as Actions artifacts.
- **`YapId` format `yyyyMMdd-HHmmss-xxxx` (UTC + 4 base-36 chars).** Time-sortable, short enough for
  `%%yap:<id>%%` markers, and collision-safe for one person's capture rate.
