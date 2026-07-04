# Plan 012: Backup enabled-state persistence + task #10 dependency polish

> WRITER agents (no builds) run SEQUENTIALLY (they share MainActivity.kt), then one
> FIX+VERIFY agent. Tree uncommitted — no commits.

## Part A — Backup enabled persists + background-state consistency (P1)

**Bug:** `BackupManager._state.enabled` is in-memory (MutableStateFlow, default false).
After process death the UI shows "Off" while `BackupScheduler`'s 6h periodic WorkManager
job — which persists across restarts/reboot — keeps calling `backupNow()` (it reads the
persisted folder selection, not `enabled`). UI lies; state, persistence, and the scheduled
job diverge. The folder *selection* already persists (`BackupFolderSelectionStore`); the
`enabled` flag must too, and app launch must reconcile the scheduler with it.

**Shared** (`shared/src/commonMain/kotlin/id/homebase/photos/backup/`):
- Persist `enabled` via the existing KeyValue table, mirroring `BackupFolderSelectionStore`
  exactly (namespaced sha256 key, no new `.sq`). Simplest: add `enabled`/`setEnabled`
  methods on a small store (new `BackupEnabledStore`, or fold into the selection store and
  rename to `BackupSettingsStore` — writer's call, keep it minimal).
- `BackupManager`: inject the store; `setEnabled(enabled)` persists it (launch on `scope`);
  add `suspend fun restore()` that reads persisted enabled + selection and seeds `_state`
  (`enabled`, `selectedFolderCount`) so the UI reflects reality on launch. Do NOT auto-start
  a pass in `restore()` — only reflect state (the periodic job / explicit toggle drive runs).
- Tests (jvmTest, in-memory DB pattern): store round-trip; `setEnabled(true)` persists and a
  fresh manager `restore()`s it; selection count restored.

**Android** (`androidApp/`):
- On the Authenticated branch (MainActivity) — after the graph is up — call `restore()` once
  and RECONCILE the scheduler: if persisted enabled → `BackupScheduler.enable(context)`
  (idempotent; also re-arms after a reinstall clears WorkManager's DB); else
  `BackupScheduler.disable(context)`. This is the single MainActivity edit for Part A.
- No new UI. The card already renders `state.enabled`; once restore seeds it, the toggle
  shows the true state.

## Part B — Task #10: adopt real packages (P3, owner directive [[prefer-existing-packages]])

Runs AFTER Part A (shares MainActivity.kt). Owner: "use packages that exist already instead
of reinventing the wheels."

- **Icons** — add the Material icons artifact to `gradle/libs.versions.toml` + `androidApp/
  build.gradle.kts`. Prefer `androidx.compose.material:material-icons-core`; if any needed
  glyph (AccountCircle, PlayArrow/PlayTriangle, Add) isn't in core, use
  `material-icons-extended` and note the size trade-off in a comment. Replace the hand-drawn
  `ImageVector` locals: `TimelineScreen.kt` `AccountCircleIcon`→`Icons.Filled.AccountCircle`,
  `PlayTriangle`→`Icons.Filled.PlayArrow`, any `AddIcon`→`Icons.Filled.Add`;
  `ViewerScreen.kt` `BackArrow`→`Icons.AutoMirrored.Filled.ArrowBack`, its `PlayTriangle` too.
  Delete the now-dead `by lazy` vector definitions. Keep every existing `contentDescription`,
  tint, and size Modifier — visual + a11y parity.
- **Custom Tabs** — add `androidx.browser:browser` to the catalog + androidApp. In
  `MainActivity.kt` replace the login handler
  `startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url)))` (LoginEvent.OpenUrl, ~line
  128) with `CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(event.url))`
  (chat-kmp pattern). The `homebase-photos://` redirect intent-filter still catches the
  callback — do NOT change the manifest.

## Verifier block

1. `./gradlew :shared:jvmTest --tests "id.homebase.photos.*"` → green (new persistence tests).
2. `:shared:compileAndroidMain :androidApp:assembleDebug :androidApp:compileDebugAndroidTestSources` → green.
3. iOS regression build green (shared changed).
4. Redmi (USB `6057f11e`, logged in, NEVER uninstall): install `-r`.
   - **Persistence**: enable backup with a small folder selected (DCIM already selected from
     the last test), confirm toggle ON, `am force-stop`, relaunch → toggle must READ ON
     (was the bug). Then toggle OFF, force-stop, relaunch → reads OFF. `dumpsys jobscheduler |
     grep -i photos-backup` reflects the enabled state (job present when on, gone when off).
     Do NOT let it backfill beyond the selected small folder.
   - **Icons**: account button, viewer back arrow, video play glyph render as Material icons
     (screenshot).
   - **Custom Tabs**: tap Sign in on a logged-out state (or just confirm the OpenUrl path
     builds) → opens a Custom Tab, and the `homebase-photos://` redirect still completes login.
     (Owner is currently logged in; a full re-login isn't required — a build+launch smoke is
     enough if re-login would disrupt the owner's session.)
   - Portrait: this device drifts to landscape (see [[redmi-test-device]]) — lock rotation
     (`settings put system accelerometer_rotation 0; user_rotation 0`) and use `adb input tap`
     if argent taps miss.

## STOP conditions
- material-icons-core lacks a glyph AND extended is undesirable → report; keep that one vector.
- Reconcile-on-launch would require touching auth/session internals → report, don't improvise.

## Follow-ups (not this plan)
- `wipeOutboxStaging` on logout (staged files orphan).
- Instant-on-new-photo backup (MediaStore ContentObserver) vs the 6h floor.
- MIUI battery-optimization exemption prompt (background reliability on Xiaomi).
