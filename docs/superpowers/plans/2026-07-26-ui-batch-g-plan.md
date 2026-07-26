# UI Batch G — Settings & Backup Screens + Green Accent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Real Settings screen (account header, Backup entry, About, Sign out) + a full Backup screen on both platforms, iOS `PHPhotoLibraryChangeObserver` backup parity, and the owner-approved moss-green accent swap.

**Architecture:** Thin shared `SettingsViewModel` (StateFlow-combining, narrow-seam constructor for TDD) → native SwiftUI/Compose screens. Backup shared layer (`BackupViewModel`, `BackupManager`, `BackgroundBackup`) is **FROZEN** — screens only consume it. Android Backup screen resurrects the deleted `BackupStatusCard` logic (incl. `BackupScheduler` arming). Accent = token-only swap; neutral surfaces untouched.

**Tech Stack:** KMP shared (Koin, StateFlow, kotlinx-coroutines-test) · Jetpack Compose M3 · SwiftUI iOS 26 · hand-edited pbxproj.

## Global Constraints

- Branch: `photos-ui-batch-g` off `1e3367f` (= main, batches A–E). Worktree = the main repo checkout.
- UI fully native; shared stays headless at `StateFlow<UiState>`. No Compose Multiplatform.
- **Owner decisions this batch:** accent → moss green (both platforms). **Delete→bin routing: DO NOT TOUCH** (owner: "we will talk later").
- **Storage meter: OMITTED** — no quota/usage API exists in homebase-api (verified by recon; only per-file `fileByteCount`). No `settings-storage` row.
- **iOS crawler already shipped** (`PHAssetCrawler`, BGTasks, `BackupView` sheet — commit `32ebd7e`). Do NOT rebuild; only re-home + add change observer.
- Logout must NEVER run on a scope the `_authState` flip destroys (warning comments in `YouAuthFlowManager.kt` + `DriveSyncManager.kt`). Keep the existing platform chains: Android `lifecycleScope` (`MainActivity.kt:101`), iOS detached `Task`. **`SettingsViewModel` gets NO `logout()` intent** — deliberate deviation from the 07-25 pre-plan, for this reason.
- iOS: hand-register new Swift files in `iosApp/iosApp.xcodeproj/project.pbxproj` (classic groups). **NEVER run `xcodegen generate`** — it reshuffles UUIDs (verified 2026-07-26).
- a11y ids are cross-platform contract (listed per task). Preserve all existing ids not explicitly changed.
- Minimal comments; terse *why*-only. Reuse `ui/components/` (Compose) & `iosApp/components/` (SwiftUI) — never copy-paste between screens.
- Compile checks: `:shared:jvmTest`, `:shared:compileAndroidMain`, `:shared:compileKotlinIosSimulatorArm64`; Android `:androidApp:assembleDebug` + `:androidApp:compileDebugAndroidTestKotlin`; iOS build via Xcode (iPhone 17 / iOS 26.5 sim). K/N link-cache flake: `:shared:linkDebugFrameworkIosSimulatorArm64 --rerun-tasks`.
- Commit messages end with: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

### Task 1: Shared `SettingsViewModel` (TDD — build & FREEZE first)

**Files:**
- Create: `shared/src/commonMain/kotlin/id/homebase/photos/settings/SettingsViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/id/homebase/photos/PhotosModule.kt` (Koin factory + iOS resolvers)
- Test: `shared/src/jvmTest/kotlin/id/homebase/photos/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `YouAuthFlowManager.authState: StateFlow<YouAuthState>` (`YouAuthState.Authenticated(identity: OdinId, ...)`), `OwnerSessionRepository.user: StateFlow<OwnerSession?>` + `suspend fun load(odinId: OdinId)`, `OwnerSession.initials(): String` (exists, tested).
- Produces (frozen contract for Tasks 2+3):

```kotlin
data class SettingsUiState(
    val identity: String? = null,      // odinId domain, null when unauthenticated
    val displayName: String? = null,   // falls back to identity via OwnerSessionRepository's own fallback emit
    val initials: String? = null,
)

class SettingsViewModel(
    authState: StateFlow<YouAuthState>,
    ownerSession: StateFlow<OwnerSession?>,
    private val loadOwner: suspend (OdinId) -> Unit,
) : ViewModel() {
    val state: StateFlow<SettingsUiState>   // combine(authState, ownerSession), SharingStarted.Eagerly
    fun refresh()                           // when Authenticated && ownerSession.value?.odinId != identity → viewModelScope.launch { loadOwner(identity) }
}
```

Narrow-seam constructor (raw flows + function ref) because `OwnerSessionRepository`/`PublicIdentityRepository` are final classes with no test fakes — do NOT invent an interface for them.

- [ ] **Step 1: Write failing tests** — mirror `SearchViewModelTest` conventions (test dispatcher, `settle()` helper if present there; copy the local idiom):

```kotlin
class SettingsViewModelTest {
    // fixtures: MutableStateFlow<YouAuthState>, MutableStateFlow<OwnerSession?>, recording loadOwner lambda
    @Test fun unauthenticated_stateIsAllNull()
    @Test fun authenticated_identityMapped_displayNameFromOwnerSession()
    @Test fun ownerSessionNull_displayNameNull_identityStillSet()   // repository emits its own fallback; VM doesn't duplicate it
    @Test fun initials_comeFromOwnerSessionInitials()
    @Test fun refresh_whenAuthenticated_callsLoadOwnerWithIdentity()
    @Test fun refresh_whenOwnerSessionAlreadyLoadedForIdentity_skipsLoad()
    @Test fun refresh_whenUnauthenticated_doesNotCallLoadOwner()
}
```

- [ ] **Step 2: Run** `./gradlew :shared:jvmTest --tests '*SettingsViewModelTest*'` — expect FAIL (class missing).
- [ ] **Step 3: Implement** `SettingsViewModel` per the contract above (flat `data class`, `combine(...).stateIn(viewModelScope, Eagerly, SettingsUiState())`).
- [ ] **Step 4: Wire Koin + iOS resolver** in `PhotosModule.kt`:

```kotlin
factory {
    val youAuth = get<YouAuthFlowManager>()
    val owner = get<OwnerSessionRepository>()
    SettingsViewModel(youAuth.authState, owner.user, owner::load)
}
// alongside the existing top-level resolvers (line ~190):
fun settingsViewModel(): SettingsViewModel = KoinPlatform.getKoin().get()
```

- [ ] **Step 5: Run** `./gradlew :shared:jvmTest` — full suite green (1198+7). Then `:shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64`.
- [ ] **Step 6: Commit** `feat(shared): SettingsViewModel — identity/displayName/initials state`

---

### Task 2: Android — Settings screen, Backup screen, green accent

**Files:**
- Create: `androidApp/src/main/kotlin/id/homebase/photos/android/ui/settings/SettingsScreen.kt`
- Create: `androidApp/src/main/kotlin/id/homebase/photos/android/ui/backup/BackupScreen.kt`
- Modify: `androidApp/.../ui/nav/Routes.kt`, `androidApp/.../ui/AppShell.kt`, `androidApp/.../ui/timeline/TimelineScreen.kt` (remove LogoutDialog; account → Settings), `androidApp/.../ui/theme/Color.kt`
- Test: `androidApp/src/androidTest/.../ui/settings/SettingsFlowTest.kt` (new), update `ui/timeline/TimelineScreenTest.kt` (3 logout cases), `ui/home/AppShellNavTest.kt`

**Interfaces:**
- Consumes: Task 1's `SettingsUiState`/`settingsViewModel()`; frozen `BackupViewModel` (`BackupUiState`: `enabled, running, done, total, currentName, lastError, lastCompletedAt, selectedFolderCount, folders: List<FolderUi>`; intents `onToggle(Boolean), onBackupNow(), loadFolders(), onFolderToggled(String)`); `BackupScheduler.enable/disable/backupNow(context)`; existing `onLogout` chain from `MainActivity` (lifecycleScope).
- Produces: routes `settings`, `backup`; ids below.

- [ ] **Step 1: Routes** — in `Routes.kt` add `data object Settings : Route("settings")`, `data object Backup : Route("backup")`. In `AppShell.kt` add both `composable` blocks; `shellVisible` logic already hides the pill off top-level routes (no change).
- [ ] **Step 2: Account button → Settings.** In `TimelineScreen.kt` delete `LogoutDialog` + `showLogoutDialog`; `PhotosTopBar(onAccountClick = onOpenSettings)` with `onOpenSettings: () -> Unit` hoisted from `AppShell` (`navController.navigate(Route.Settings.path)`). `onLogout` now forwards to the Settings destination instead of TimelineScreen.
- [ ] **Step 3: SettingsScreen** — `Scaffold` + back arrow, `remember { settingsViewModel() }` (AppShell resolver idiom), `LaunchedEffect(Unit) { vm.refresh() }`. Content: account header (initials in a `primaryContainer` circle, `displayName` bold, `identity` secondary) tag `settings-account`; `LibraryRow`-style rows (reuse component): Backup → `Route.Backup` (`settings-backup`), About with version via `context.packageManager.getPackageInfo(context.packageName, 0).versionName` (`settings-about`; no BuildConfig — it's disabled in gradle), Sign out (`settings-signout`) → `AlertDialog` "Log out?" / "You'll need to sign in again to see your photos." confirm tag `logout-confirm` → `onLogout()`. Root tag `settings-root`. NO storage row.
- [ ] **Step 4: BackupScreen** — recover the deleted card: `git show 51b6ee9^:androidApp/src/main/kotlin/id/homebase/photos/android/ui/backup/BackupStatusCard.kt`. Rebuild as a screen (root tag `backup-screen`): toggle (`backup-toggle`) with the recovered rules — permission launcher (`READ_MEDIA_IMAGES` ≥33 else `READ_EXTERNAL_STORAGE`), enable-with-0-folders opens picker instead, **`BackupScheduler.enable/disable(context)` alongside `vm.onToggle` (WorkManager never arms otherwise)**; status line (`Backed up <relative time>` / On / Off); progress row (`backup-progress`, `done/total` + `currentName` while `running`); "Back up now" button (`backup-now`) → `vm.onBackupNow()` + `BackupScheduler.backupNow(context)`; "Choose folders" (`backup-folders`) → recovered `FolderPickerSheet` verbatim (tags `backup-folder-sheet`, `backup-folder-row-<id>`, `backup-folder-done`).
- [ ] **Step 5: Green accent** — `Color.kt` token-only swap (surfaces/text/status untouched):
  - Light: `Primary 0xFF5E7A52`, `OnPrimary 0xFFFFFFFF`, `PrimaryContainer 0xFFD5E0C7`, `OnPrimaryContainer 0xFF1B2815`, `SecondaryContainer 0xFFE3E2CE`, `OnSecondaryContainer 0xFF24251A` (current values are blue-tinted).
  - Dark: `Primary 0xFFA6C394`, `OnPrimary 0xFF1B2815`, `PrimaryContainer 0xFF3C4D30`, `OnPrimaryContainer 0xFFD5E0C7` (dark Secondary* stay — already neutral grey).
  - Leave `dynamicColor` (API 31+) behavior in `Theme.kt` untouched.
- [ ] **Step 6: Tests.** New `SettingsFlowTest`: account-button opens Settings; rows visible (`settings-account/backup/about/signout`); signout → dialog → `logout-confirm` invokes callback; Backup row navigates to `backup-screen`; toggle-with-0-folders opens `backup-folder-sheet`. Update `TimelineScreenTest` (logout cases now assert navigation intent, not dialog) and `AppShellNavTest` (settings/backup destinations). Run: `./gradlew :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin` (connected tests NOT run — Redmi login).
- [ ] **Step 7: Commit** `feat(android): Settings + Backup screens, moss-green accent`

---

### Task 3: iOS — SettingsView, BackupView re-home, change observer, green accent

**Files:**
- Create: `iosApp/iosApp/settings/SettingsView.swift`
- Create: `iosApp/iosApp/backup/PhotoLibraryObserver.swift`
- Modify: `iosApp/iosApp/timeline/TimelineView.swift`, `iosApp/iosApp/backup/BackupView.swift` (ids only), `iosApp/iosApp/iOSApp.swift` (observer install), `iosApp/Theme/Theme.swift`, `iosApp/iosApp.xcodeproj/project.pbxproj` (hand-register 2 files)
- Test: `iosApp/iosAppUITests/SettingsUITest.swift` (new), update `LogoutUITest.swift`, any test referencing `backup-button`

**Interfaces:**
- Consumes: `PhotosModuleKt.settingsViewModel()` (Task 1; SKIE `for await` on `.state`), `PhotosModuleKt.youAuthFlowManager().logout()` (detached `Task`, as today), existing `BackupView`/`BackupModel`, `BackgroundBackupTrigger.schedule()`.
- Produces: ids `settings-root/account/backup/about/signout`, `backup-screen`, `backup-folders`; keeps `account-button`, `logout-confirm`, `backup-toggle`, `backup-now`.

- [ ] **Step 1: SettingsView** — `.sheet` from the account button (matches BackupView's existing presentation idiom; `router.path` is Collections-tab-only, don't use it). Inside: own `NavigationStack`, `List`/`Form` (root id `settings-root`): account section (initials circle on `primaryContainer`, displayName, identity — `settings-account`), `NavigationLink("Backup") { BackupView() }` (`settings-backup`), About row `Bundle.main.infoDictionary?["CFBundleShortVersionString"]` (`settings-about`), Sign out (`settings-signout`) → `confirmationDialog` "Log out?" (keep existing copy + `logout-confirm` id) → `Task { try? await PhotosModuleKt.youAuthFlowManager().logout() }`. `refresh()` on appear.
- [ ] **Step 2: TimelineView** — trailing account button now sets `showSettings = true` (sheet = `SettingsView`); DELETE the `confirmationDialog` and the leading cloud `backup-button` toolbar item + its `showBackup` sheet (backup lives in Settings now).
- [ ] **Step 3: BackupView ids** — add `.accessibilityIdentifier("backup-screen")` on the List root and `"backup-folders"` on the folders section/first-folders control; keep `backup-toggle`, `backup-now`, `backup-done`, `backup-folder-<id>`.
- [ ] **Step 4: PhotoLibraryObserver** — small `NSObject: PHPhotoLibraryChangeObserver`; `photoLibraryDidChange` → debounce 5s → `BackgroundBackupTrigger.schedule()` (BGProcessingTask, `earliestBeginDate` +60s instead of +6h for this path). Register in `iOSApp.init()` after `BackgroundBackupTrigger.register()`, only when auth status is `.authorized/.limited`. `// ponytail: fires only while app alive; BGTask does the work — Android-MediaWatch parity, not instant upload`.
- [ ] **Step 5: Green accent** — `Theme.swift:49-52`, scheme-aware moss mirroring Android:

```swift
static func primary(_ s: ColorScheme) -> Color { s == .dark ? Color(red: 0.651, green: 0.765, blue: 0.580) : Color(red: 0.369, green: 0.478, blue: 0.322) }  // A6C394 / 5E7A52
static func onPrimary(_ s: ColorScheme) -> Color { s == .dark ? Color(red: 0.106, green: 0.157, blue: 0.082) : .white }                                       // 1B2815 / white
static func primaryContainer(_ s: ColorScheme) -> Color { s == .dark ? Color(red: 0.235, green: 0.302, blue: 0.188) : Color(red: 0.835, green: 0.878, blue: 0.780) } // 3C4D30 / D5E0C7
static func onPrimaryContainer(_ s: ColorScheme) -> Color { s == .dark ? Color(red: 0.835, green: 0.878, blue: 0.780) : Color(red: 0.106, green: 0.157, blue: 0.082) }
```

  (Static `var`s at 49-52 keep compiling for any scheme-less call sites — point them at the light values; if none exist, delete them.)
- [ ] **Step 6: pbxproj** — hand-add `SettingsView.swift` + `PhotoLibraryObserver.swift`: one `PBXFileReference`, one `PBXBuildFile`, group child entry (new `settings` group beside `search`), Sources build-phase entry — copy the exact pattern of `SearchView.swift`'s four entries.
- [ ] **Step 7: Tests.** `SettingsUITest`: account button → `settings-root`; rows exist; `settings-backup` → `backup-screen`; `settings-signout` → `logout-confirm` visible. Update `LogoutUITest` (logout path now account → settings → signout). Grep UITests for `backup-button` and repoint. Build-for-testing on iPhone 17 / iOS 26.5.
- [ ] **Step 8: Commit** `feat(ios): SettingsView, backup re-home, PHPhotoLibrary observer, moss accent`

---

### Task 4: Verifier (ONE agent, full matrix)

- [ ] `./gradlew :shared:jvmTest` — all green (expect 1205+).
- [ ] `./gradlew :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64`
- [ ] `./gradlew :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin`
- [ ] iOS: build + run XCUITest bundle (iPhone 17 / iOS 26.5 sim; link-cache flake → `:shared:linkDebugFrameworkIosSimulatorArm64 --rerun-tasks`). Known pre-existing: `MainFlowUITest` may fail on login state — not a Batch G regression.
- [ ] Argent QA (sim): Settings opens from account button, Backup screen drives toggle/folders, sign-out returns to Login, accent visibly green. Redmi (Android 11, static fallback = the new green): same pass if adb reachable — do NOT run connected gradle tests (wipes login).
- [ ] Fix findings or file them; commit fixes.

### Task 5: Close-out

- [ ] `/code-review`-style whole-branch review; fix Critical/Important findings.
- [ ] Refresh `HANDOFF.md` (Batch G section: scope shipped, iOS-crawler-was-done finding, storage-meter omission, observer ceiling, accent swap, QA gate) — owner directive.
- [ ] Update memory: master-plan file (F skipped→parked, G status), accent decision recorded.
