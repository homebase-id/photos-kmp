# UI Redesign — Batch G: Settings, Account & Backup

**Depends on:** A. **Schema gate:** no. Delegation: shared-headless → Android + iOS → verifier.

## Goal
Promote the two thin surfaces into real screens: **Settings/account/storage** (the account button only opens a logout
dialog today) and a proper **Backup screen** (the floating card was removed from the timeline in Batch A; the engine still
runs). Also close the **iOS backup crawler stub** for parity.

## Headless contract (build & FREEZE first)
- `SettingsViewModel` → `SettingsUiState`: `identity` (from `YouAuthFlowManager`), `displayName?`, `storageUsed?`,
  `storageTotal?`, `appVersion`. intents: `logout()`. **Storage gap:** if the Odin drive exposes a quota/usage API, wire
  it; otherwise show used-only or omit the meter and note it. // ponytail: omit the meter unless the quota API exists.
- **Reuse `BackupViewModel`** (unchanged) for the Backup screen — `enabled`, `running`, progress (`done`/`total`/
  `currentName`), `selectedFolderCount`, `folders`; intents `onToggle`, `onBackupNow`, `loadFolders`, `onFolderToggled`.
  This is the toggle + folder picker removed from the timeline, rebuilt as a screen.
- **iOS backup crawler:** implement the `PhotoLibraryCrawler` iOS actual (currently a stub) using `PHPhotoLibrary` /
  `PHAsset` enumeration so iOS backup reaches Android parity. New iOS-side code + a `BGContinuedProcessingTask`-powered
  "Back up now" (iOS 26 API — see `ios-min-deployment-26`) and `PHPhotoLibrary` change observers for event-driven backup.
**TDD (shared):** none new for Settings beyond identity mapping; backup logic already tested. iOS crawler: a small
enumeration test if feasible.

## Android (Compose, Material 3 Expressive)
- **Settings screen** (from the account button → replace the logout-only dialog): account header (identity + avatar),
  Storage row (used/total meter if available), Backup entry (→ Backup screen), About (version), Sign out (confirm).
- **Backup screen:** the toggle, status line, progress, and the folder picker (reuse the `FolderPickerSheet` logic that
  lived in the deleted `BackupStatusCard.kt` — recover it from git history at commit `51b6ee9^`).
- New ids: `settings-root`, `settings-account`, `settings-storage`, `settings-backup`, `settings-about`,
  `settings-signout`, `backup-screen`, `backup-toggle`, `backup-now`, `backup-folders`.

## iOS (SwiftUI, iOS 26)
- **Settings** `Form`/`List` reached from the account toolbar button: account section, storage, Backup `NavigationLink`,
  About, Sign out. **Backup screen:** toggle + folder list + progress, backed by `BackupViewModel`; plus the new
  `PHPhotoLibrary` crawler + `BGContinuedProcessingTask` "Back up now".
- New ids mirror Android (`settings-root`, `settings-*`, `backup-screen`, `backup-toggle`, `backup-now`, `backup-folders`).

## Tests
Shared/identity unit. UI-flow per platform: open Settings → Backup → toggle on → folder picker opens; Sign out returns to
Login.

## Verify
Compile all; tests green. Argent: Android on the Redmi — Settings opens, Backup screen drives the real toggle/folders,
sign-out works. **iOS on-device (owner-assisted):** confirm the new PHPhotoLibrary crawler actually enumerates + uploads
(this is the iOS parity milestone — mirror the Android on-device verification done for background backup).

## Risks / deferrals
- **iOS crawler + background backup is the heavy part** — it's net-new iOS platform work, not just UI. Budget it as the
  bulk of this batch. Respect `background-workers-need-restoresession` (call `restoreSession()` in any cold-started task).
  Storage meter depends on an Odin quota API that may not exist — degrade gracefully.
