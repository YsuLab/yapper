# CLAUDE.md — Yapper

Voice-first capture app for one owner (Yasir). Side key → talk → everything lands in the right place
(Samsung Reminder via Microsoft To Do, Obsidian vault notes, daily plan). Read `docs/spec.md` first.

## Stack
- Android app in **Java** (owner preference; no Kotlin in app code). Gradle Kotlin DSL for build files.
- Package / applicationId: `com.ysulab.yapper`. Never change the applicationId (breaks upgrades).
- minSdk 29, compile/target SDK 36, Java 17. AGP and Gradle versions live in `build.gradle.kts` and the wrapper.
- Unit tests: JUnit 4 in `app/src/test`. Keep logic in plain Java classes so it is testable without Android.

## Code map
- `assist/` — side-key entry: `YapperAssistService`, `CaptureSession` (record + screen data), `AudioCapture`.
- `capture/` — pure Java: `CaptureStore`/`CaptureMeta` (bundles), `SilenceDetector`, `ScreenText`, `ScreenshotCheck`.
- `work/` — WorkManager: `InboxWorker` (vault note, offline), `TranscribeWorker` (network), `CaptureScheduler.reconcile`.
- `vault/` — `VaultAccess` (SAF, append/create only), `InboxNote` (markdown, pure Java), `VaultFs` (path API;
  `SafVaultFs` on the phone, `MemoryVaultFs` in tests), `VaultIndex` (`_yapper/index.md`).
- `sort/` — M2 sorter, pure Java (+ second brain: `link_entity` → entity notes via `vault/EntityIndex`): `Sorter` (OpenAI-compatible `/chat/completions` + tool calls), `SorterPrompt`,
  `SortAction` + `SafePath` (validate every tool call), `Executor` (append/create with `%%yap:<id>/<n>%%`, Undo),
  `ActionLog`, `Summary`. Prompt, tools and starter rules live in `app/src/main/assets/sorter/` (shared with the eval).
- `work/SortWorker` (transcript → plan.json → vault → result notification), `UndoWorker` + `UndoReceiver`.
- `prayer/` — `PrayerTimes`: Umm al-Qura prayer times for Jeddah, computed locally; its 8-day table goes into the
  sorter prompt so "after Asr" resolves to a clock time.
- `transcribe/` — `Transcriber` interface, OpenAI-compatible + ElevenLabs clients, `TranscriberConfig`.
- `settings/` — `Settings`, `SecretStore` (Android Keystore).
- `tools/transcribe-bench/score.py` — CER/WER per transcriber from Inbox notes (`python3 -m unittest` in CI).
- `tools/sorter-eval/run_eval.py` + `evals/cases.jsonl` — routing eval against any model (bar ≥ 90 %).

## Build
- `./gradlew testDebugUnitTest` — unit tests.
- `./gradlew assembleDebug` — debug APK.
- Release signing only happens in CI from GitHub secrets (`YAPPER_KEYSTORE_BASE64`, `YAPPER_KEYSTORE_PASSWORD`,
  `YAPPER_KEY_ALIAS`, `YAPPER_KEY_PASSWORD`). See `docs/setup-guide.md`.
- The cloud VM may not have the Android SDK (dl.google.com can be blocked). Then build through GitHub Actions
  (`.github/workflows/android.yml`) and fix from CI logs. Pure-Java classes can be checked locally with `javac` + JUnit.
- `versionCode` = `GITHUB_RUN_NUMBER`, so every CI APK installs over the previous one.

## Working agreement
- One milestone (`docs/milestones.md`) = one thread = one PR: code, unit tests, green CI, APK, and a
  "Test on phone" checklist of at most 10 steps.
- Owner's time is the scarcest resource. Decide what you can. At most one batched round of questions per milestone.
- Plan before building. Small, reviewable PRs. PR description: what changed, how to test on the phone, known risks.
- Record every non-trivial decision in `docs/decisions.md` (date, decision, why).
- Verify Android, Samsung, Microsoft Graph and Claude API details against current official docs. Samsung can
  differ from stock Android.
- Replies to the owner: answer first, short sentences, real technical terms, end with one "Next:" line.

## Hard rules
- **Never** put secrets (API keys, keystore, passwords, tokens) in the repo, logs, or PR text.
- No telemetry or analytics.
- **A capture must never be lost.** Persist the capture bundle before any processing.
- Vault writes: append or create only, never delete or rewrite owner text. Tag written blocks with
  `%%yap:<id>%%` (see `YapId`). Re-read a file right before appending.
- Commit first, then offer Undo. Ask the owner only when confidence is low.
