# Decisions

Newest first. Format: date — decision — why.

## 2026-11-09 — M3–M5 (one release)
- **M3, M4 and M5 ship as one PR and one APK.** Owner asked for a single build to test instead of one per
  milestone (reinstalls are tiring). Overrides "one milestone = one PR" for these three.
- **No MSAL.** OAuth 2.0 authorization code + PKCE against `login.microsoftonline.com/consumers`, redirect
  `yapper://auth`, scopes `Tasks.ReadWrite offline_access`. Three HTTP calls; avoids a large dependency and the
  MSAL redirect URI that embeds the signing-key hash (which would change while the owner still has debug builds).
  Refresh token in the Keystore-backed `SecretStore`.
- **Graph times in UTC.** `reminderDateTime`/`dueDateTime` sent with `timeZone: "UTC"`; To Do and Samsung Reminder
  show them in the phone's zone. Avoids Windows-vs-IANA zone name issues.
- **Reminder routing (exact-minute safety net).** Due within the alarm window (default 60 min, setting) → local
  `AlarmManager.setAlarmClock` alarm rings, and the To Do task is created with `isReminderOn: false` so Samsung
  Reminder does not ring a second time. Later → To Do reminder only. Push delayed until inside the window
  (offline) → switches to the local alarm. Not signed in / token revoked / list gone → every waiting reminder rings
  locally. Past times → To Do task without reminder. The window is a setting so the owner can widen it if the
  sync test shows Samsung Reminder is late.
- **`USE_EXACT_ALARM`** (granted at install, Android 13+) instead of asking for "Alarms & reminders". Sideloaded
  app, so the Play policy limit on that permission does not apply. Alarms are restored on boot and app update.
- **Ramble = microphone foreground service, ~5-min Ogg/Opus parts cut at a pause** (hard cut at 7 min). Each part is
  added to the bundle when finalized, so a crash loses at most the current part, and each part stays far below
  transcription upload limits. Transcribed per part with resume; a part that fails for good leaves a visible gap
  marker instead of failing the whole ramble. Partial wake lock for the recording only (max 65 min).
- **Ramble starts from an activity** (app button, Quick Settings tile, launcher shortcuts, nudge notification):
  Android 14+ only lets a visible app start microphone recording.
- **Day plan = `plan_day` tool:** `## Plan` with `- [ ] HH:mm–HH:mm title` lines in the daily note, and an alarm
  (same routing as reminders, no extra `⏰` line) at each block start unless `alarm: false`.
- **Debrief ticks checkboxes.** `complete_task` changes only `- [ ]` → `- [x]` on the first matching open task in
  that day's daily note; logged with the exact before/after line, so Undo reverts it. This is the one exception
  to "append only", because the milestone asks for done items to be checked. No match → `- [x]` line under
  `## Debrief`, so nothing is lost. Unfinished items roll over to tomorrow via `add_to_daily` ("Rolled over").
- **Daily nudges** at 08:00 and 21:30 (settings, empty = off), inexact alarms. One tap starts a plan or debrief
  ramble. The owner forgets lists that need him to look, so the app comes to him.

## 2026-11-09 — M2 (Sorter)
- **Shadow mode is a toggle, default off.** The brief planned ~3 days of proposals first. The owner wants real
  filing now, and every write is append-only, tagged and undoable, so the safety comes from Undo instead. The
  toggle stays for testing a new model or new rules: proposals go into the capture note, nothing else changes.
- **One model call per capture, tool calls only.** The tools have no results the model needs, so there is no
  agent loop: faster, cheaper, and easier to replay. A text-only answer becomes a question.
- **`tool_choice: "auto"`, not "required".** Phone test: Claude via OpenRouter returned HTTP 400 ("tool_choice
  type any … not supported" with thinking on). The prompt still demands tool calls only; a text answer becomes an
  ask_owner question, so nothing is lost.
- **Every tool call is validated before it touches the vault.** `SafePath` rejects `..`, hidden folders and
  `_yapper/`, fixes wikilink-style paths and adds `.md`; reminders need an ISO time with offset. Invalid calls are
  dropped and listed in the capture note.
- **Plan first, then write; log every write.** `plan.json` is saved before any write, so a retry re-uses the same
  plan instead of asking the model again. Each block has `%%yap:<capture id>/<n>%%`, and the executor re-reads the
  file before writing, so a retry skips blocks already there. `actions.json` keeps the exact text for Undo.
- **Append at the end of the note, never insert in the middle.** Inserting under an existing heading means
  rewriting the file, which can lose a sync change. Blocks go at the end under `### YYYY-MM-DD · topic`; the
  heading is skipped when it is already the note's last heading, so one day's captures group together.
- **Undo removes exactly the logged text.** A note we created is deleted only if it is unchanged. If the owner
  edited a block, Undo keeps it and says so.
- **Reminders in M2 = lines in that day's daily note** (`## Reminders`, `- [ ] ⏰ 17:00 …`). The action log keeps
  title + time, so M3 can push them to Microsoft To Do without asking the model again.
- **Questions (`ask_owner`) go to `Inbox.md`** as tasks with a link back to the capture, per the rules note.
  Actionable notification replies come later.
- **Sorted capture notes move to `_yapper/captures/`** (setting, default on): the owner wanted ideas filed, not
  transcripts in his Inbox. The note keeps the audio, screenshot, transcripts and a "Sorted" list of links. Obsidian
  resolves `![[file]]` by name, so embeds keep working. Undo moves it back to `Inbox/`.
- **Index = one line per note** (path, title, aliases, up to 8 `##`/`###` headings), rebuilt before each sort.
  Only changed files are re-read (cache keyed by size + modified time), first 16 KB each. Skips `.*`, `_yapper/`,
  `Inbox/`.
- **Screenshot only when screen text is under 80 characters**, downscaled to ~720 px wide, JPEG q75.
- **Prompt, tools and starter rules are assets** (`app/src/main/assets/sorter/`). The eval runner reads the same
  files, so `run_eval.py` measures exactly what the phone sends.
- **Default model `anthropic/claude-sonnet-5.5` on OpenRouter** with `cache_control` on the system part (rules +
  index) for Anthropic models only. Empty sorter key + OpenRouter URL reuses the OpenRouter transcriber key.
- **Only captures transcribed by an M2 build are sorted** (the transcript is now saved in the bundle). Older test
  captures stay in Inbox.

## 2026-11-09 — Models: API vs self-hosted
- **Any model, not only Claude.** Transcriber model IDs and base URLs are already settings (M1). The M2 sorter
  is built on the OpenAI-compatible chat API (tools + image input), not the Anthropic Messages API. OpenRouter
  speaks it for Claude, GPT, Gemini, Qwen, etc., and vLLM / llama.cpp / Ollama speak it locally. So switching
  model or moving to the home lab is a settings change, not code. Claude (Sonnet) is the starting baseline
  because it is strong at following written rules and tool calls; the M2 eval set decides what else is good enough.
- **Self-host transcription first.** Open-weight Arabic ASR caught up in 2026. Candidates for the self-hosted slot:
  `IFM/Jais-ASR` (6B, Apache 2.0, ~12 GB BF16, AWQ/FP8 builds; Saudi dialect code, `code_switch` keeps English in
  Latin script; vLLM plugin exposes `/v1/audio/transcriptions`) and Cohere Transcribe Arabic (open weights, Apache 2.0,
  based on the 2B Cohere Transcribe). Vendor-reported avg WER on the Open Universal Arabic ASR sets: Jais-ASR 22.85,
  ElevenLabs Scribe v2 24.54, Cohere Transcribe Arabic 25.87, Whisper large-v3 36.86. On SADA (Saudi) Jais-ASR is
  30.85. Vendor numbers: the M1 benchmark (`score.py`) on the owner's own clips decides.
- **Self-host the sorter only if it passes the eval.** 24 GB fits one ~30B model at 4-bit with vision + tools,
  e.g. Qwen3.6-35B-A3B (Q4_K_M ≈ 22 GB, Apache 2.0), Qwen3.8-27B, Gemma 4 26B-A4B / 31B. Context left is ~35–55K
  tokens, and STT + LLM do not both fit on 24 GB; a second 8–12 GB card holds the STT model. Run the M2 eval set
  against the local model; switch when it reaches the same ≥ 90 % bar. Until then: local STT + API sorter.
- **Fallback order** (later): self-hosted first, API when the home lab is unreachable, so a capture is never stuck.

## 2026-11-09 — M1 (Capture)
- **OpenRouter slot: one key covers transcription (and can cover the M2 sorter).** Owner uses OpenRouter
  for almost everything. OpenRouter has `POST /api/v1/audio/transcriptions` (multipart, JSON response,
  no `prompt` field) with several STT models. The slot takes a comma-separated model list
  (default `openai/gpt-4o-transcribe, openai/whisper-large-v3, microsoft/mai-transcribe-2`) and runs each as
  its own transcriber, so the benchmark compares models with that one key. It has top priority when
  benchmark mode is off. ElevenLabs Scribe is not on OpenRouter's STT list, so it still needs its own key
  if we want it in the comparison.
  For M2: OpenRouter also serves Claude models, so the sorter gets an OpenRouter provider. Trade-off: the
  Max-plan API credits only pay for the direct Anthropic API, not OpenRouter. Owner picks per bill.
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

### 2026-11-11 — Capture sheet v2: smaller, see-through, crafted smoke
- Sheet is 300dp (about a third of the S24 Ultra screen), full width, no box. Owner: the full-height black sheet killed the vibe.
- The screen behind stays visible: empty cells are transparent, and a dithered dark haze rises with the smoke (none at the top, near solid under the words). The scrim is a light gradient, not 70 % black.
- Motion: 1.8 s eased rise with uneven plumes, moonrise through the smoke, two smoke layers at different speeds for depth, voice speeds the drift. On release the smoke detaches from the bottom and drifts away over 1.2 s ("saved" shows for 1.3 s).
