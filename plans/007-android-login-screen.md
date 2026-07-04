# Plan 007: Android login — screen, session-gated root, redirect intent-filter

> **Executor instructions**: WRITER mode — no builds (single verifier afterward);
> self-review. In-scope files only. STOP conditions binding. No commits.
> Drift check: compare excerpts to live code; STOP on real mismatch.
> **Contract dependency**: a sibling writer is producing Plan 006's shared API in
> parallel — do NOT read half-written shared files; code against the contract
> quoted below.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (Activity relaunch semantics with singleTask + deep link)
- **Depends on**: plans/006-shared-login-viewmodel.md (contract only — parallel-writable)
- **Category**: feature (auth T8)
- **Planned at**: commit `86e57a2`, 2026-07-04

## Why this matters

Android has a polished timeline but no sign-in and no way to receive the YouAuth
browser redirect. This plan adds the design-system §5.1 login screen, switches the
Activity root on the shared session state, and registers the `homebase-photos://`
deep link that completes login.

## Current state (verified excerpts)

- `androidApp/src/main/kotlin/id/homebase/photos/android/MainActivity.kt` — current
  shape (post-timeline-wave): `enableEdgeToEdge()`; `setContent { PhotosTheme { ... } }`
  resolving Koin `TimelineViewModel` + `buildHomebaseImageLoader`, hoisting a
  `SnackbarHostState`, collecting `vm.events` → snackbar, and rendering
  `TimelineScreen(state-callbacks...)`. Open it and read fully before editing.
- `androidApp/src/main/AndroidManifest.xml` — MainActivity has ONLY a MAIN/LAUNCHER
  intent-filter today; no launchMode set. (Read it first; small file.)
- Shared contract from Plan 006 (`id.homebase.photos.auth`):
  `LoginPhase { LoggedOut, AwaitingBrowser, Authenticating, LoggedIn }`;
  `LoginUiState(phase, identity, error)`; `LoginEvent.OpenUrl(url)`;
  `LoginViewModel { state; events; onIdentityChange(String); startLogin();
  onCallback(String); onBrowserDismissed(); logout() }` — Koin `factory`.
  `YouAuthFlowManager` (Koin `single`): `authState: StateFlow<YouAuthState>`
  where `YouAuthState` = `Initializing | Unauthenticated | Authenticating |
  Authenticated | Error` (in `id.homebase.api.youauth`); `suspend onAppResumed()`;
  `suspend handleCallback(url: String)`.
- Theme/tokens: `PhotosTheme`, `MaterialTheme.colorScheme` (Conservatory),
  `MaterialTheme.shapes.extraLarge` = pill. Type roles: displaySmall (headline),
  bodyMedium (subtext), bodySmall (caption). Exemplar for state-driven screens +
  testTags + hand-drawn ImageVector icons: `androidApp/.../ui/timeline/TimelineScreen.kt`
  (see `AccountCircleIcon`/`AddIcon` at the bottom — material-icons-core is NOT a
  dependency; draw needed vectors locally).
- Design spec: `docs/design/design-system.md` §5.1 (Login): full-bleed `background`;
  centered column: small moss leaf glyph, `display` "Homebase Photos", one
  `bodyMedium` `onSurfaceVariant` line "Your photos, your server.", identity field,
  full-width primary pill "Sign in with Homebase"; `caption` help line at the bottom.
  States: Idle / Authenticating (inline `onPrimary` spinner + "Connecting…", button
  disabled) / Error (destructive-tinted inline line below the button; no apology copy).

## Scope

**In scope**:
- `androidApp/src/main/kotlin/id/homebase/photos/android/ui/login/LoginScreen.kt` (create)
- `androidApp/src/main/kotlin/id/homebase/photos/android/MainActivity.kt` (edit)
- `androidApp/src/main/AndroidManifest.xml` (edit)
- `androidApp/src/androidTest/kotlin/id/homebase/photos/android/ui/login/LoginScreenTest.kt` (create)

**Out of scope**: `shared/**` (006's writer owns it), `iosApp/**`, `TimelineScreen.kt`,
`themes.xml`, `build.gradle.kts` (NO new dependencies — no androidx.browser/Custom
Tabs; open the browser with a plain ACTION_VIEW intent), `CoilSetup.kt`.

## Steps

### Step 1: `LoginScreen.kt` — stateless + stateful, per §5.1

Stateless signature (UI tests drive this):

```kotlin
@Composable
fun LoginScreen(
    state: LoginUiState,
    onIdentityChange: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    modifier: Modifier = Modifier,
)
```

- Full-bleed `background`; centered `Column` (`screenEdge` 16dp horizontal padding,
  max content width ~420dp for tablets), `imePadding()` so the keyboard doesn't
  cover the button.
- Leaf glyph: small (40dp) hand-drawn `ImageVector` (two overlapping leaf/petal
  bezier shapes suffice — keep it abstract), tint `primary`, follow the
  `PlayTriangle`-style `by lazy` builder from TimelineScreen.kt.
- `Text("Homebase Photos", displaySmall, onBackground)`; then
  `Text("Your photos, your server.", bodyMedium, onSurfaceVariant)`.
- `OutlinedTextField`: label "Homebase ID", placeholder "your.identity.id",
  singleLine, `KeyboardType.Uri`/ImeAction.Done (Done → onSubmit()), value =
  `state.identity`, enabled only when phase == LoggedOut,
  `testTag("login-id-field")`. Colors: focused border `primary`.
- Button (full width, `shapes.extraLarge`, `primary`/`onPrimary`, height 52dp,
  `testTag("login-submit")`):
  - `LoggedOut` → text "Sign in with Homebase", enabled iff identity not blank.
  - `AwaitingBrowser` / `Authenticating` → disabled, inline
    `CircularProgressIndicator(18dp, onPrimary, strokeWidth 2dp)` + "Connecting…".
  - `LoggedIn` → disabled, "Signed in" (transitional — root swaps away).
- Error line under the button: `state.error?.let { Text(it, bodySmall,
  color = MaterialTheme.colorScheme.error, testTag("login-error")) }`.
- Bottom caption (aligned bottom-center, bodySmall/`onSurfaceVariantDim` via
  `PhotosTheme.extended`): "You sign in on your own server. This app never sees a password."
- Root `testTag("login-root")`.
- Stateful overload `LoginScreen(viewModel: LoginViewModel, modifier)`: collects
  `state` via `collectAsStateWithLifecycle()` and wires the three callbacks; does
  NOT collect events (the Activity owns event handling).

### Step 2: MainActivity — session-gated root + event wiring

Read the current file first, then restructure `setContent`:

```kotlin
val youAuth = remember { koin.get<YouAuthFlowManager>() }
val authState by youAuth.authState.collectAsStateWithLifecycle()
when (authState) {
    is YouAuthState.Initializing -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) // splash: plain warm ground
    is YouAuthState.Authenticated -> { /* existing timeline wiring, unchanged */ }
    else -> { // Unauthenticated, Authenticating, Error → login screen owns those states
        val loginVm = remember { koin.get<LoginViewModel>() }
        LoginScreen(loginVm)
        LaunchedEffect(loginVm) {
            loginVm.events.collect { e -> when (e) {
                is LoginEvent.OpenUrl -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url)))
            } }
        }
    }
}
```

- Keep ALL existing timeline wiring intact inside the Authenticated branch
  (snackbar host, events collection, callbacks).
- Keep the SAME `loginVm` across recompositions (remember) — the Koin factory
  would otherwise re-mint. Hold it at the setContent root (remember keyed on
  nothing), NOT inside the when-branch, so a transient Authenticating flip
  doesn't recreate it. (Simplest: resolve both VMs once above the `when`.)
- Deep link: override `onNewIntent(intent: Intent)` — if
  `intent.data?.scheme == "homebase-photos"`, forward:
  `lifecycleScope.launch { koin.get<YouAuthFlowManager>().handleCallback(intent.data.toString()) }`.
  Also handle the cold-start case: in `onCreate`, if `intent?.data?.scheme == "homebase-photos"`,
  do the same (singleTask normally routes via onNewIntent, but cover both).
- `onResume()`: `lifecycleScope.launch { youAuth.onAppResumed() }` — auto-cancels
  a stuck Authenticating when the user backs out of the browser. Resolve `youAuth`
  once as an Activity field (lazy koin get) so onNewIntent/onResume don't re-query
  composition state.

### Step 3: AndroidManifest — singleTask + VIEW filter

On MainActivity: add `android:launchMode="singleTask"` (required — otherwise the
browser redirect spawns a second Activity instance and the grid/session state is
lost) and a second intent-filter (keep the LAUNCHER one):

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="homebase-photos" />
</intent-filter>
```

### Step 4: `LoginScreenTest.kt` (stateless overload; model on TimelineScreenTest.kt)

- LoggedOut + blank identity → `login-submit` displayed and NOT enabled.
- LoggedOut + identity "sam.homebase.id" → submit enabled; click → `onSubmit` invoked.
- AwaitingBrowser → "Connecting…" text exists; field not enabled.
- error = "Couldn't reach your identity" → `login-error` shows that text.
- Typing into `login-id-field` invokes `onIdentityChange` (use `performTextInput`).

## Done criteria

- [ ] Login renders per §5.1 with all testTags; all four states distinct.
- [ ] Root switches splash/login/timeline purely on `youAuth.authState`.
- [ ] Manifest: singleTask + homebase-photos VIEW filter; onNewIntent + cold-start forward to `handleCallback`; onResume → `onAppResumed()`.
- [ ] `LoginScreenTest.kt` compiles with the five cases.
- [ ] No out-of-scope file touched; no new dependencies.

## STOP conditions

- MainActivity's current content diverges materially from the description above.
- The shared contract types (006) as quoted don't compile against your usage —
  re-read the plan-006 contract block; if a NAME differs, STOP (do not invent).

## Maintenance notes

- When the viewer (plan 004) adds navigation, the auth gate must wrap the nav
  host, not individual screens — keep the `when(authState)` at the very root.
- Custom Tabs (androidx.browser) is the polish upgrade over ACTION_VIEW if the
  owner wants in-app auth later.
- Logout UI entry point deliberately deferred (account button is a no-op) —
  `LoginViewModel.logout()` exists when settings arrive.
