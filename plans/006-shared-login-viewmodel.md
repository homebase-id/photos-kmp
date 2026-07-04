# Plan 006: Shared login — LoginViewModel, Koin wiring, photos redirect scheme

> **Executor instructions**: WRITER mode — do NOT run gradle/xcodebuild/any build or
> test command (a single verifier builds after all writers finish); self-review
> instead. Touch only in-scope files. STOP conditions are binding. No commits.
> Drift check: the tree is uncommitted — compare the excerpts below to live code;
> STOP on real mismatch.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW-MED (Koin graph + auth flow; unit-tested via a thin gateway seam)
- **Depends on**: none (007/008 code against THIS plan's contract in parallel)
- **Category**: feature (auth T8)
- **Planned at**: commit `86e57a2`, 2026-07-04

## Why this matters

The app has a working timeline but no way to sign in — `YouAuthFlowManager` is
fully implemented in the copied api layer yet unbound in Koin, and nothing opens
the browser or receives the redirect. This plan adds the shared `LoginViewModel`
(per the locked Batch-1 contract), binds the auth graph, and gives Photos its own
redirect scheme so it can't collide with Homebase Chat's `homebase-fchat://` on
the same device. Plans 007/008 build the native screens on this contract.

## Current state (verified excerpts)

- `shared/src/commonMain/kotlin/id/homebase/api/youauth/YouAuthFlowManager.kt` —
  complete flow engine. Key surface (lines 72–86, 115, 187–197, 335, 386, 402):

```kotlin
class YouAuthFlowManager(
    private val driveSyncManager: DriveSyncManager,
    private val credentialsManager: CredentialsManager,
    private val httpClient: HttpClient,
    private val driveFileProviderCached: DriveFileProviderCached,
    private val publicProfileProviderCached: PublicProfileProviderCached,
    private val clearPlatformCaches: suspend () -> Unit = {},
) {
    val authState: StateFlow<YouAuthState> // Initializing | Unauthenticated | Authenticating | Authenticated(identity, ...) | Error(message)
    suspend fun authorize(identity: OdinId, appId: String, appName: String,
        drives: List<TargetDriveAccessRequest> = emptyList(), /* ... */): String // returns authorize URL
    suspend fun handleCallback(url: String)
    suspend fun logout(); suspend fun cancelAuth(); suspend fun onAppResumed(delayMs: Long = 500)
}
```

  `init` runs `restoreSession()` → emits Authenticated if `CredentialStorage`
  (SecureStorage-backed) holds credentials, else Unauthenticated. All ctor deps
  EXCEPT the optional lambda are already bound in `apiModule`
  (`shared/src/commonMain/kotlin/id/homebase/api/di/ApiModule.kt` — comment at
  lines 83-85 notes YouAuthFlowManager itself was bound app-side in chat-kmp and
  is currently UNBOUND here).
- `shared/src/commonMain/kotlin/id/homebase/api/browser/RedirectConfig.android.kt`
  and `RedirectConfig.native.kt` — both currently:

```kotlin
actual object RedirectConfig {
    actual val scheme: String = "homebase-fchat"
    actual fun buildRedirectUri(clientId: String): String {
        return "homebase-fchat://$clientId/authorization-code-callback"
    }
}
```

- `shared/src/commonMain/kotlin/id/homebase/photos/PhotoConfig.kt:6-11` —
  `DRIVE_TYPE = "2af68fe72fb84896f39f97c59d60813a"`, `DRIVE_ALIAS = "6483b7b1f71bd43eb6896c86148668cc"`,
  `APP_ID = "d44e1380-fd6f-40fb-816b-106b7bc55d44"`, `APP_NAME = "Homebase Photos"`.
- `shared/src/commonMain/kotlin/id/homebase/api/youauth/TargetDriveAccessRequest.kt:22`
  — `data class TargetDriveAccessRequest(alias, type, name, description,
  permissions: List<DrivePermission>, ...)`. Find `DrivePermission` in the same
  package for the Read/Write values.
- `shared/src/commonMain/kotlin/id/homebase/photos/PhotosModule.kt` — `photosModule`
  currently binds HelloViewModel, `single<PhotosRepository> { MockPhotosRepository() }`,
  `factory { TimelineViewModel(get()) }`; iOS-callable top-level accessors live in
  this file (`timelineViewModel()`, `loadThumbnailBytes(...)`).
- Identity normalization helper: `cleanDomain()` should exist in the copied api —
  look in `shared/src/commonMain/kotlin/id/homebase/api/util/StringExtensions.kt`
  (chat-kmp had it at line ~113). If absent, normalize minimally (trim, lowercase,
  strip scheme/path) inline and note it.
- ViewModel convention exemplar: `shared/src/commonMain/kotlin/id/homebase/photos/timeline/TimelineViewModel.kt`
  (flat UiState data class, `_state.update {}`, events SharedFlow, Kermit logging).
- Test infra: `kotlinx-coroutines-test` is on the commonTest classpath (confirmed);
  existing tests under `shared/src/commonTest/kotlin/id/homebase/photos/`.

## Scope

**In scope**:
- `shared/src/commonMain/kotlin/id/homebase/photos/auth/AuthGateway.kt` (create)
- `shared/src/commonMain/kotlin/id/homebase/photos/auth/LoginViewModel.kt` (create)
- `shared/src/commonMain/kotlin/id/homebase/photos/PhotosModule.kt` (edit — bindings + iOS accessors)
- `shared/src/commonMain/kotlin/id/homebase/api/browser/RedirectConfig.android.kt` (edit — scheme)
- `shared/src/commonMain/kotlin/id/homebase/api/browser/RedirectConfig.native.kt` (edit — scheme)
- `shared/src/commonTest/kotlin/id/homebase/photos/auth/LoginViewModelTest.kt` (create)

**Out of scope**: everything under `androidApp/`, `iosApp/`; `YouAuthFlowManager.kt`
itself; `ApiModule.kt`; `PhotosRepositoryImpl` (the mock stays bound — the real-repo
swap needs driveId resolution, a later wave); `RedirectConfig.jvm.kt` (desktop
localhost variant, unused); `ApiCredentials.getIdentityId()` (known hard-coded stub
— do NOT try to fix it here).

## The locked contract (007/008 code against this — do not deviate)

```kotlin
package id.homebase.photos.auth

enum class LoginPhase { LoggedOut, AwaitingBrowser, Authenticating, LoggedIn }

data class LoginUiState(
    val phase: LoginPhase = LoginPhase.LoggedOut,
    val identity: String = "",          // current text-field value (normalized)
    val error: String? = null,          // inline error copy, cleared on edit/start
)

sealed interface LoginEvent { data class OpenUrl(val url: String) : LoginEvent }

class LoginViewModel(/* gateway */) : androidx.lifecycle.ViewModel() {
    val state: StateFlow<LoginUiState>
    val events: SharedFlow<LoginEvent>   // extraBufferCapacity = 8, like TimelineEvent
    fun onIdentityChange(value: String)  // normalize via cleanDomain, clear error
    fun startLogin()                     // parse OdinId -> authorize -> emit OpenUrl, phase=AwaitingBrowser
    fun onCallback(url: String)          // phase=Authenticating; gateway.handleCallback(url)
    fun onBrowserDismissed()             // user closed browser: gateway.cancelAuth() unless already LoggedIn/Authenticating-with-callback
    fun logout()
}

// PhotosModule.kt iOS accessors (SKIE):
fun loginViewModel(): LoginViewModel
fun youAuthFlowManager(): YouAuthFlowManager  // root session gate observes .authState
```

## Steps

### Step 1: `AuthGateway` — thin testability seam over YouAuthFlowManager

```kotlin
/** Thin seam over YouAuthFlowManager so LoginViewModel is unit-testable
 *  (the manager's ctor needs the whole HTTP/sync graph). */
interface AuthGateway {
    val authState: StateFlow<YouAuthState>
    suspend fun authorize(identity: OdinId): String
    suspend fun handleCallback(url: String)
    suspend fun cancelAuth()
    suspend fun logout()
}

class YouAuthGateway(private val manager: YouAuthFlowManager) : AuthGateway {
    override val authState get() = manager.authState
    override suspend fun authorize(identity: OdinId): String = manager.authorize(
        identity = identity,
        appId = PhotoConfig.APP_ID,
        appName = PhotoConfig.APP_NAME,
        drives = listOf(
            TargetDriveAccessRequest(
                alias = PhotoConfig.DRIVE_ALIAS,
                type = PhotoConfig.DRIVE_TYPE,
                name = "Photo Library",
                description = "Place for your memories",
                permissions = listOf(DrivePermission.Read, DrivePermission.Write),
            )
        ),
    )
    // handleCallback/cancelAuth/logout delegate 1:1
}
```

Check `DrivePermission`'s actual member names (Read/Write casing) before writing.
Requesting Write now means the backup wave needs no re-consent.

### Step 2: `LoginViewModel`

Behavior (match TimelineViewModel idioms — `_state.update {}`, Kermit `Logger.w`):
- `init`: collect `gateway.authState` in `viewModelScope`; map to phase:
  `Authenticated` → `LoggedIn` (+ set `identity` from `it.identity.domainName` if
  accessible — check `OdinId`'s member; else `toString()`), `Error` → `LoggedOut`
  + `error = message`, `Unauthenticated` → `LoggedOut` (do NOT clobber
  `AwaitingBrowser`/`Authenticating` set by the local funcs unless the manager
  says Unauthenticated AFTER we were Authenticating — that means cancelAuth ran;
  map it back to `LoggedOut`). `Initializing` → leave phase as-is (the ROOT gate,
  not this VM, handles the splash).
- `onIdentityChange(value)`: `identity = value.cleanDomain()` (or minimal inline
  normalizer), `error = null`.
- `startLogin()`: if identity blank → `error = "Enter your Homebase ID"`. Parse
  `OdinId(identity)` in try/catch → catch: `error = "That doesn't look like a Homebase ID"`.
  On success: phase `AwaitingBrowser`, launch → `val url = gateway.authorize(odinId)`;
  `_events.tryEmit(LoginEvent.OpenUrl(url))`. Catch `AuthInProgressException` →
  no-op; other exceptions → `error = message ?: "Couldn't start sign-in"`, phase `LoggedOut`.
- `onCallback(url)`: phase `Authenticating`; launch `gateway.handleCallback(url)`
  (result arrives via authState collection).
- `onBrowserDismissed()`: if phase == `AwaitingBrowser` → launch `gateway.cancelAuth()`,
  phase `LoggedOut`. (If a callback already flipped us to Authenticating, do nothing.)
- `logout()`: launch `gateway.logout()` (authState flip does the rest).

### Step 3: Koin + iOS accessors (PhotosModule.kt)

```kotlin
// in photosModule:
single { YouAuthFlowManager(get(), get(), get(), get(), get()) }
single<AuthGateway> { YouAuthGateway(get()) }
factory { LoginViewModel(get()) }
// top-level, next to timelineViewModel():
fun loginViewModel(): LoginViewModel = KoinPlatform.getKoin().get()
fun youAuthFlowManager(): YouAuthFlowManager = KoinPlatform.getKoin().get()
```

`YouAuthFlowManager` MUST be `single` (its init restores the session; the root
gate + login screen must share one instance and one `authState`).

### Step 4: Redirect scheme → `homebase-photos`

In BOTH `RedirectConfig.android.kt` and `RedirectConfig.native.kt`: replace
`homebase-fchat` with `homebase-photos` (scheme val + the URI template), and add
a one-line comment: `// Adapted from chat-kmp pin e67130cd: photos-specific scheme so Chat and Photos don't claim each other's redirects.`
Resulting redirect: `homebase-photos://d44e1380-fd6f-40fb-816b-106b7bc55d44/authorization-code-callback`.

### Step 5: Tests (`LoginViewModelTest.kt`, kotlinx-coroutines-test + a FakeAuthGateway)

FakeAuthGateway: MutableStateFlow<YouAuthState> you control + recorded calls +
configurable authorize result/throw. Cases:
1. `startLogin` with blank identity → error set, no authorize call.
2. `startLogin` with garbage ("not a domain!") → invalid-id error, no authorize call.
3. `startLogin` happy: authorize called with OdinId, `OpenUrl` event emitted,
   phase == AwaitingBrowser.
4. Gateway emits Authenticated → phase LoggedIn.
5. Gateway emits Error("boom") → phase LoggedOut + error == "boom".
6. `onBrowserDismissed` during AwaitingBrowser → cancelAuth called, phase LoggedOut.
Use `Dispatchers.setMain(StandardTestDispatcher())` (viewModelScope needs Main);
model the test-class shape on kotlinx-coroutines-test docs; keep plain kotlin.test asserts.

## Done criteria

- [ ] Contract types exactly as locked above; `LoginViewModelTest` passes under `:shared:jvmTest`.
- [ ] `YouAuthFlowManager` bound `single`; `loginViewModel()` / `youAuthFlowManager()` accessors exist.
- [ ] Both mobile RedirectConfig actuals use `homebase-photos`.
- [ ] No out-of-scope file touched.

## STOP conditions

- Excerpt mismatch; `DrivePermission` has no read/write-like members; `OdinId`
  construction/validation works differently than "throws on invalid"; `cleanDomain`
  exists but with an incompatible signature you can't adapt in one line.

## Maintenance notes

- Requesting Write drive permission now front-loads consent for the backup wave.
- `ApiCredentials.getIdentityId()` is a hard-coded stub — the real-repo swap
  (driveId resolution) must fix identity plumbing; recorded in plans/README.md.
- If chat-kmp upstream changes YouAuthFlowManager, the pin-diff (e67130cd) now
  includes the two RedirectConfig actuals as deliberate local divergence.
