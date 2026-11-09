# Milestones

Each milestone = one thread → one PR: code, unit tests, green CI, downloadable APK,
and a "Test on phone" checklist (≤ 10 steps). The owner tests and replies by voice.

| # | Milestone | Done when | Status |
|---|---|---|---|
| M0 | **Foundation.** Java Android app, Gradle, docs, CI (build + unit tests + APK signed with a stable key from a GitHub secret), owner setup guide. | CI is green and the APK installs on the phone | In progress |
| M1 | **Capture.** Assistant registration, side key, screenshot + screen text + audio. "Got it" in < 1 s. Bundle → `Inbox/` in the vault (transcript + screenshot + audio link). Transcriber benchmark on ~10 real clips. | 10 captures in a row, none lost, including offline | In progress |
| M2 | **Sorter.** LLM (OpenAI-compatible, any model; Claude via OpenRouter first) + `rules.md` + `index.md` → vault writes. Shadow mode ~3 days (proposals into Inbox), eval set in `evals/`, then auto-apply. Undo works. | ≥ 90% correct routing on the eval set | Not started |
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
