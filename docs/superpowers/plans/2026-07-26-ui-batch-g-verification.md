# Batch G Verification — 2026-07-26

Verifier run against `photos-ui-batch-g` @ 37cc3bc (plan 8d0bff5, shared b9ad864, iOS 4822f08, Android 37cc3bc).

## Matrix

| # | Step | Result |
|---|------|--------|
| 1 | `:shared:jvmTest` | PASS — 1206 tests, 0 failures, 0 skipped |
| 2 | `:shared:compileAndroidMain` + `:shared:compileKotlinIosSimulatorArm64` | PASS |
| 3 | `:androidApp:assembleDebug` + `:androidApp:compileDebugAndroidTestKotlin` | PASS (SettingsFlowTest / TimelineScreenTest / AppShellNavTest compile; connected tests NOT run — owner's Redmi) |
| 4 | iOS build-for-testing (iPhone 17 / iOS 26.5) | PASS after one known K/N link-cache flake (`No module deserializer` → `:shared:linkDebugFrameworkIosSimulatorArm64 --rerun-tasks`, per plan; no code change) |
| 5 | XCUITest suite | PASS — 19 executed: 12 passed, 7 skipped, 0 failed |
| 6 | Argent QA (sim + Redmi) | PASS — found + fixed one accent gap (below) |

## XCUITest detail

- **SettingsUITest 3/3 passed**, **LogoutUITest 1/1 passed** (the Batch G gates). MainFlowUITest **passed** this run (known-flaky, no skip needed — sim session auto-restored).
- 7 skips are the pre-existing data/login-state pattern, not Batch G regressions: AlbumLifecycle (1), LibraryStates (1), LoginScreen (2, "fresh install" assumption), Selection (1), Viewer (2, "no photos in test env").
- SettingsUITest + LogoutUITest re-run green after the tab-tint fix.

## Implementer watch-list

- **iOS nested NavigationStack (BackupView pushed from SettingsView):** exercised live on the sim — single nav bar, back chevron present, title/toolbar correct, Backup's Done pops back to Settings; no runtime issues in the xcresult. Condition for the pre-approved removal fix did not trigger; left as-is.
- **`PhotosModuleKt.settingsViewModel()`** resolves in the framework — confirmed live (account header shows Peter Parker / peter.parker.demo.rocks / PP initials from shared state).
- **pbxproj hand-edit** — clean; SettingsView/PhotoLibraryObserver compile and ship in the bundle.
- **Android icons** (`Icons.AutoMirrored.Outlined.Logout`, `Icons.Outlined.CloudUpload`) and stateful/stateless SettingsScreen/BackupScreen overloads — compile clean.

## Argent QA

- **iPhone 17 sim (logged-in demo identity):** account button → Settings sheet (moss `primaryContainer` initials circle, identity row, Backup/About/Sign out); Backup push renders toggle + disabled "Back up now" + footer copy; Done buttons moss green. Session left untouched (no logout confirm, no backup toggle).
- **Redmi Note 5 Pro (SDK 30 → static moss fallback):** APK `install -r` (data preserved, login intact); Settings screen with dark-moss initials container; Backup screen with toggle Off, disabled Back up now, green "Choose folders". No connected gradle tests run.

## Finding fixed

- **2741286** `fix(ios): tint TabView chrome moss — selected tab stayed system blue after accent swap.` Every screen tints itself with `PhotosColor.primary`, but the tab bar is chrome outside those views; pre-Batch-G the default blue equalled the accent so the gap was invisible. Verified visually on sim + SettingsUITest/LogoutUITest re-run green.

## Verdict

**DONE.** No DONE_WITH_CONCERNS items. Notes for close-out: the nested NavigationStack in BackupView is tolerated by iOS 26 but remains a pattern to flatten if it ever misbehaves; K/N link-cache flake recurred once (documented workaround sufficed).
