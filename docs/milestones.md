# Milestones

Each milestone = one thread → one PR: code, unit tests, green CI, downloadable APK,
and a "Test on phone" checklist (≤ 10 steps). The owner tests and replies by voice.

| # | Milestone | Done when | Status |
|---|---|---|---|
| M0 | **Foundation.** Java Android app, Gradle, docs, CI (build + unit tests + APK signed with a stable key from a GitHub secret), owner setup guide. | CI is green and the APK installs on the phone | In progress |
| M1 | **Capture.** Assistant registration, side key, screenshot + screen text + audio. "Got it" in < 1 s. Bundle → `Inbox/` in the vault (transcript + screenshot + audio link). Transcriber benchmark on ~10 real clips. | 10 captures in a row, none lost, including offline | In progress |
| M2 | **Sorter.** LLM (OpenAI-compatible, any model; Claude via OpenRouter first) + `rules.md` + `index.md` → vault writes. Shadow mode is a toggle (default off: writes for real, Undo on every result). Eval set in `evals/`. | ≥ 90% correct routing on the eval set | In progress |
| M3 | **Reminders.** MSAL sign-in, Graph To Do, Samsung Reminder sync, latency measurement, exact-alarm safety net if needed. | WhatsApp timetable screenshot + "5 and 15 min before each" → reminders ring on time | Not started |
| M4 | **Ramble mode.** Long recording, chunked transcription, one ramble → many actions. Battery-friendly. | A 20-min ramble is fully sorted, nothing lost | Not started |
| M5 | **Today plan.** Morning ramble → time blocks + alarms. Evening debrief → done items checked, rest rolled over. | Owner plans 5 days in a row by voice | Not started |

**Later:** ask your vault by voice, lecture audio → Daftar, prayer-time-relative times ("after Asr", Jeddah),
Galaxy Watch, GLM provider, desktop companion.

## M0 scope (this PR)
- Gradle project, Java app with one launcher screen showing version + build type.
- Unit-tested pure-Java helpers: `BuildInfo`, `YapId` (id + `%%yap:<id>%%` marker for M2 Undo).
- `CLAUDE.md`, `docs/spec.md`, `docs/milestones.md`, `docs/decisions.md`, `docs/setup-guide.md`.
- GitHub Actions: wrapper validation, unit tests, signed release APK (or debug APK + warning if no key),
  certificate SHA-256 in the run summary, APK artifact, rolling `latest` pre-release on main.

## M1 scope (this PR)
- Default digital assistant: `VoiceInteractionService` + session service + stub `RecognitionService`.
- Side key → overlay → records at once (Ogg/Opus), screen text from `AssistStructure`, screenshot (blank
  `FLAG_SECURE` screens dropped). Ends on ~2 s silence, a tap, or a second side-key press. Haptic + "Got it".
- Capture bundle in private storage before anything else; crash recovery; WorkManager jobs.
- Vault folder via SAF → `Inbox/Capture <time>.md` + `Inbox/attachments/` (audio, screenshot), works offline.
- Transcribers: OpenAI, ElevenLabs, Groq, self-hosted (OpenAI-compatible). Benchmark mode runs all
  configured on every capture. Scorer: `tools/transcribe-bench/score.py`.
- Setup/status screen: permissions, assistant, vault, transcriber keys (Keystore), silence seconds,
  capture counters, "Retry unfinished".

**Not in M1:** sorting, reminders, Undo/View result notifications (M2+), ramble mode (M4).

## M2 scope (this PR)
- After transcription, `SortWorker` sends transcript + screen text (+ screenshot only when screen text is thin),
  `_yapper/rules.md`, `_yapper/index.md` and today's daily note to an OpenAI-compatible model with 5 tools.
- Tool calls are validated (`SafePath`, dates, known tools) and applied: append under a dated heading, create
  notes, daily-note sections, questions into `Inbox.md`. Every block tagged `%%yap:<id>/<n>%%`.
- `create_reminder` lands as `- [ ] ⏰ HH:mm …` under `## Reminders` in that day's daily note; M3 sends the same
  log entries to Microsoft To Do.
- Plan saved before writing, action log after; retries never duplicate. Undo from the notification removes exactly
  what was written (blocks you edited are kept) and moves the capture back to `Inbox/`.
- Sorted capture notes (audio, screenshot, transcript, "## Sorted" list of links) move to `_yapper/captures/`.
- Settings: base URL, model, key (empty = reuse the OpenRouter transcriber key), shadow mode, move, screenshots.
- `evals/cases.jsonl` (20 synthetic cases) + `tools/sorter-eval/run_eval.py`.

**Not in M2:** real reminders (M3), `ask_owner` as an actionable notification (questions go to `Inbox.md` for now),
re-sorting captures made before M2.

## M3–M5 scope (one PR, one APK — owner's request)
- **M3 Reminders:** Microsoft sign-in (PKCE, no MSAL), pick the To Do list Samsung Reminder syncs, every
  `create_reminder` → To Do task. Exact local alarm (Snooze / Done) for anything due within the alarm window,
  without a double alert. Fallback to local alarms when To Do is unusable. "Test sync" button to measure the
  Graph → Samsung Reminder delay. Undo deletes the To Do tasks and cancels alarms.
- **M4 Ramble:** up to 60 min with the screen off, foreground service, 5-min parts cut at pauses, chunked
  transcription with resume, one ramble → many actions. Start/stop from the app, Quick Settings tile, launcher
  long-press shortcuts.
- **M5 Today plan:** morning/evening nudge notifications; plan mode → `plan_day` time blocks + alarms; debrief
  mode → `complete_task` ticks, unfinished items rolled to tomorrow.
