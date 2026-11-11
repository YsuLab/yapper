# Yapper — product spec

Source: owner's kickoff brief (2026-11-09). Working name "Yapper". Owner: Yasir.

## 1. Owner and problem
- CS student (KAU, Jeddah), DevOps intern, several side projects. Very little free time.
- Very forgetful: capture is slower than his forgetting, and organizing is a second job he never does.
- Talks far more easily than he writes. Long voice messages. Dictates to AI.
- Notion and a home-screen to-do widget failed: both need him to go and look.
- Likes Obsidian (everything in one place) and Samsung Reminder.
- Many tasks arrive through WhatsApp: text, timetables, images.
- Devices: Samsung Galaxy phone, Samsung tablet, laptop, desktop PC.

## 2. Vision
One button. Talk for 5 seconds or 50 minutes. Reminders land in Samsung Reminder, notes in the right
Obsidian notes, the day in a plan. He never chooses a folder, list or format.

## 3. Core experience (non-negotiable)

### Fire-and-forget
1. Long-press the side key. Yapper is Android's default digital assistant. It grabs screenshot + screen text
   and starts recording immediately.
2. He speaks.
3. Capture ends on ~2 s silence (configurable) or a tap. Side-key release may not be observable on Samsung:
   investigate, don't assume.
4. Within 1 s: haptic + "Got it" + overlay closes. He can lock the phone right away.
5. Processing runs in the background (`WorkManager`, expedited). Offline → queued and retried.
   **A capture must never be lost.**
6. Result notification, e.g. "Added 6 reminders · 1 note → Tabby.md", with **Undo** and **View**.
7. Interrupt only when unsure (ambiguous date/time, unclear target): notification with choices or quick reply.

Rule: **commit first, then offer Undo.** No confirmation screen except for low-confidence items.

### Modes
- **Quick capture** — side key, default.
- **Ramble mode** — tap to start; records with screen off (foreground service, `microphone` type), stop from
  the notification. Up to ~60 min.
- **Morning plan / evening debrief** — M5.
- **Ask your vault** — ask by side key ("what did I say about GCP last week?"); the sorter routes it to `ask_vault`, the answer comes back as a notification with links to the source notes.

## 4. Architecture

```
Side key
  → VoiceInteractionSession  (screenshot Bitmap + AssistStructure text + audio)
  → Capture bundle saved locally  (audio, screenshot, screen text, timestamp)
  → WorkManager job: Transcribe → Sort (LLM + tools) → Execute actions → Action log
  → Notification with Undo / View
```

- **Assistant entry:** `VoiceInteractionService` + `VoiceInteractionSessionService` + `VoiceInteractionSession`.
  `onHandleAssist(AssistState)` → `AssistStructure`/`AssistContent`; `onHandleScreenshot(Bitmap)`.
  Declare a stub `RecognitionService` or Android may not list the app as an assistant. Owner enables
  "use text from screen" + "use screenshot". `FLAG_SECURE` apps give a black screenshot → fall back to voice only.
- **Audio:** record to file (AAC/m4a or Opus). Audio is the source of truth.
- **Transcriber (pluggable):** Saudi Arabic + English code-switching. Benchmark ~10 real clips in M1; pick by
  accuracy on his speech, then latency, then cost. Check current docs on whether the Claude API takes audio.
- **Sorter (LLM):** any model behind an OpenAI-compatible `/v1/chat/completions` endpoint with tool calls and
  image input. Base URL + model ID + key are settings. Default: OpenRouter with a Claude Sonnet model as the
  quality baseline. Same client works for a self-hosted vLLM / llama.cpp server on the home lab. Prompt caching
  for rules + index where the provider supports it. See decisions 2026-11-09 (Models).
- **Vault writer:** Obsidian vault on the phone via Storage Access Framework (persisted tree URI). Plain markdown.
- **Reminders:** Microsoft Graph To Do → Samsung Reminder (two-way sync, one list). MSAL Android, personal
  account, delegated `Tasks.ReadWrite`. `todoTask` with `reminderDateTime` + `isReminderOn: true`.
  One time per task ("5 and 15 min before" = two tasks) unless testing shows better.
- **Exact-minute safety net (M3):** measure Graph → Samsung sync latency; if late, add
  `AlarmManager.setAlarmClock` for soon/time-critical items without double alerts.
- **Action log:** every action with enough detail to undo it exactly.
- **Secrets/privacy:** keys in Android Keystore-backed storage, never in repo/logs/PRs. No telemetry.
  Send screen text by default; screenshot only when text is missing or the content is an image.

## 5. Vault rules
- Sorter reads `_yapper/rules.md` every run. Owner changes routing by editing it in Obsidian.
- `_yapper/index.md` is auto-generated (paths, titles, aliases, top headings) to route into existing notes.
- Starter `rules.md` is created in M2 (text in the kickoff brief).
- **Write safety:** never delete or rewrite existing text; only append or create. Tag each written block with
  `%%yap:<id>%%` so Undo removes exactly that block. Re-read a file right before appending.

## 6. Sorter contract
**Input:** transcript, screen text, screenshot (only when needed), current datetime + `Asia/Riyadh`,
`rules.md`, `index.md`, today's daily note. **Output:** tool calls only.

| Tool | Purpose |
|---|---|
| `create_reminder(title, remind_at, notes?, source_ref)` | One reminder at one time |
| `append_to_note(path, heading?, markdown)` | Add to an existing note |
| `create_note(path, markdown)` | New note, only when nothing fits |
| `add_to_daily(date, section, markdown)` | Today plan, debrief |
| `ask_owner(question, options[])` | Low confidence |

Resolve relative dates from now. Handle Arabic-Indic numerals, 12h/24h, Hijri dates. One capture → many
actions. Prefer screen text over the screenshot.

## 7. Success metrics (v1)
- Side key → overlay closed < 1 s after capture ends.
- Zero lost captures over 2 weeks.
- ≥ 90% correct routing and 100% correct reminder times on the eval set.
- Owner uses it daily for 2 weeks without going back to manual notes.

## 8. Non-goals (v1)
Play Store, iOS, multiple users, sharing, polished UI, desktop app.

## 9. Owner setup answers (2026-11-09, by voice)

| # | Question | Answer | What it means for the build |
|---|---|---|---|
| 1 | Phone, One UI / Android | Galaxy **S24 Ultra**, latest or second-latest One UI (One UI 8.x / Android 16 or One UI 7 / Android 15). | Target SDK 36 fits. minSdk 29 stays (no reason to raise it yet). Test on Samsung behavior, not stock. |
| 2 | Vault + sync | **New, empty vault.** Sync not set up yet. Owns a home lab and a VPS; prefers the **VPS for data**. | Yapper creates `Inbox/` itself. Recommended sync: Syncthing with the VPS as always-on hub (see decisions). |
| 3 | Samsung Reminder ↔ Microsoft To Do | Probably **not** syncing yet. | M3 setup step: turn on the To Do sync in Samsung Reminder and pick the list. |
| 4 | Repo name + visibility | Fine. (Repo is currently **public**.) | No change. Never put secrets or personal captures in the repo. |
| 5 | Arabic vs English | Fine with either, **prefers Arabic**. | Transcripts keep his words as spoken (Arabic script for Arabic, Latin for English terms). Sorter output language: Arabic-first is allowed. UI stays English for now. |
| 6 | Must never leave the phone | **Nothing.** Building a GPU home lab and wants **fully local AI later**. | Cloud transcribers/LLM are fine now. Keep the transcriber and LLM interfaces ready for a self-hosted provider; the M1 benchmark already includes a self-hosted (OpenAI-compatible) slot. |
