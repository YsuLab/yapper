# Decisions

Newest first. Format: date — decision — why.

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
