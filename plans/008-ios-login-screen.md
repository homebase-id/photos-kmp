# Plan 008: iOS login — screen, session-gated root, ASWebAuthenticationSession

> **Executor instructions**: WRITER mode — no builds (single verifier afterward);
> self-review. In-scope files only. STOP conditions binding. No commits.
> Drift check: compare excerpts to live code; STOP on real mismatch.
> **Contract dependency**: a sibling writer is producing Plan 006's shared API in
> parallel — do NOT read half-written shared files; code against the contract
> quoted below. SKIE exposes it to Swift (suspend → async, StateFlow/SharedFlow →
> AsyncSequence, sealed → flattened classes: expect `LoginEventOpenUrl`-style
> flattened names like `TimelineEventError` was).

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (ASWebAuthenticationSession lifecycle + SKIE symbol names)
- **Depends on**: plans/006-shared-login-viewmodel.md (contract only — parallel-writable)
- **Category**: feature (auth T8)
- **Planned at**: commit `86e57a2`, 2026-07-04

## Why this matters

iOS shows the timeline unconditionally and has no sign-in. This plan adds the
§5.1 login screen, gates the root on the shared session state, and runs the
YouAuth browser handoff through `ASWebAuthenticationSession` — which intercepts
the `homebase-photos://` redirect itself, so NO Info.plist URL scheme, NO
`onOpenURL`, and NO project.yml change is needed (chat-kmp proved this pattern).

## Current state (verified excerpts)

- `iosApp/iosApp/ContentView.swift` — entire file:

```swift
/// App root. Batch 1: shows the Timeline grid (replacing the Batch-0 hello/ping proof).
struct ContentView: View {
    var body: some View {
        TimelineView()
    }
}
```

- `iosApp/iosApp/iOSApp.swift` — `@main`, `IosBootstrapKt.initializeApp()` in init,
  `WindowGroup { ContentView() }`. Do NOT edit (no onOpenURL needed).
- `iosApp/iosApp/timeline/TimelineModel.swift` — the `@StateObject` +
  AsyncSequence-collection pattern to copy for LoginModel (weak-self tasks,
  deinit cancel, flattened sealed-class cast: `if let err = e as? TimelineEventError`
  — note the FLATTENED name; check its exact spelling in that file and mirror it
  for `LoginEvent.OpenUrl`).
- Shared contract from Plan 006 (`id.homebase.photos.auth` + accessors in
  `PhotosModuleKt`): `loginViewModel()` (factory — hold in ONE @StateObject);
  `youAuthFlowManager()` (single). `LoginUiState(phase: LoginPhase, identity: String,
  error: String?)`; `LoginPhase.loggedOut/awaitingBrowser/authenticating/loggedIn`
  (SKIE enum casing may be lowerCamel — check the generated header);
  `LoginEvent.OpenUrl(url)`; VM funcs `onIdentityChange/startLogin/onCallback/
  onBrowserDismissed/logout`; `YouAuthFlowManager.authState` StateFlow of
  `YouAuthState` (Initializing/Unauthenticated/Authenticating/Authenticated/Error
  — also SKIE-flattened for the concrete subtypes).
- Redirect scheme (006): `homebase-photos` — pass exactly this string as
  `callbackURLScheme`.
- Tokens: `PhotosColor` / `PhotosFont` / `PhotosMetrics` in `iosApp/Theme/Theme.swift`
  (`radiusXl = 28`, `screenEdge = 16`, `display`, `bodyMedium`, `caption`, `label`).
- Design spec: `docs/design/design-system.md` §5.1 — layout/states/copy identical
  to the Android plan: leaf glyph, "Homebase Photos" display, "Your photos, your
  server." subtext, "Homebase ID" field (placeholder `your.identity.id`),
  full-width moss pill "Sign in with Homebase" → "Connecting…" + spinner while
  authenticating, destructive inline error line, bottom caption
  "You sign in on your own server. This app never sees a password."
- UI tests dir: `iosApp/iosAppUITests/` — `TimelineGridUITest.swift` currently
  assumes the timeline is the root; that assumption breaks once the root is
  session-gated (fresh sim installs are logged out).

## Scope

**In scope**:
- `iosApp/iosApp/login/LoginView.swift` (create)
- `iosApp/iosApp/login/LoginModel.swift` (create)
- `iosApp/iosApp/RootView.swift` (create — session gate)
- `iosApp/iosApp/ContentView.swift` (edit — delegate to RootView + UI-test bypass)
- `iosApp/iosAppUITests/LoginScreenUITest.swift` (create)
- `iosApp/iosAppUITests/TimelineGridUITest.swift` (edit — launch argument)

**Out of scope**: `iOSApp.swift`, `project.yml`, `Info.plist`/URL schemes (none
needed), `shared/**`, `androidApp/**`, `Theme.swift`, `timeline/*` views.

## Steps

### Step 1: `LoginModel.swift` (mirror TimelineModel's pattern)

`@MainActor final class LoginModel: ObservableObject`:
- `let vm = PhotosModuleKt.loginViewModel()`
- `@Published private(set) var uiState: LoginUiState?`
- `start()` (idempotent): task over `vm.state` → uiState; second task over
  `vm.events` → on OpenUrl (flattened cast, mirror TimelineModel's) call
  `openAuthSession(url:)`.
- `openAuthSession(url:)`: create + retain (`private var session:
  ASWebAuthenticationSession?`) —

```swift
import AuthenticationServices

let session = ASWebAuthenticationSession(
    url: URL(string: url)!,
    callbackURLScheme: "homebase-photos"
) { [weak self] callbackURL, error in
    Task { @MainActor in
        guard let self else { return }
        if let callbackURL { self.vm.onCallback(url: callbackURL.absoluteString) }
        else { self.vm.onBrowserDismissed() }   // user cancelled / error
        self.session = nil
    }
}
session.presentationContextProvider = self.contextProvider
session.prefersEphemeralWebBrowserSession = false // keep owner's server cookies
self.session = session
session.start()
```

- `contextProvider`: a small `final class AuthPresentationContext: NSObject,
  ASWebAuthenticationPresentationContextProviding` returning
  `ASPresentationAnchor()` from the first connected `UIWindowScene`'s key window
  (standard boilerplate; keep it in this file).
- `deinit`: cancel tasks, `session?.cancel()`.

### Step 2: `LoginView.swift` (§5.1, tokens only)

- `@StateObject private var model = LoginModel()`; `.task { model.start() }`.
- Layout: `VStack(spacing: PhotosMetrics.space16)` centered, `screenEdge` padding,
  background `PhotosColor.background(scheme)` edge-to-edge, `.ignoresSafeArea(.container)`
  on the background only (no nav bar on this screen), keyboard-safe (default
  SwiftUI behavior is fine).
- Leaf glyph: `Image(systemName: "leaf.fill")` 36pt, `PhotosColor.primary(scheme)`
  (SF Symbol — no asset needed).
- `Text("Homebase Photos").font(PhotosFont.display)`, subtext bodyMedium
  onSurfaceVariant.
- `TextField("your.identity.id", text: identityBinding)` — binding proxies to
  `model.vm.onIdentityChange(value:)` with `model.uiState?.identity ?? ""` as
  source; `.textInputAutocapitalization(.never)`, `.keyboardType(.URL)`,
  `.autocorrectionDisabled()`, `.submitLabel(.go)`, `.onSubmit { model.vm.startLogin() }`;
  styled: padding 14, `PhotosColor.surface(scheme)` fill, RoundedRectangle 12
  stroke `PhotosColor.outline(scheme)` (focused: primary). Disabled unless phase
  is loggedOut. `.accessibilityIdentifier("login-id-field")`.
- Button ("login-submit"): full width, height 52, `RoundedRectangle(cornerRadius:
  PhotosMetrics.radiusXl)` fill `primary`, label `onPrimary` `PhotosFont.label`:
  loggedOut → "Sign in with Homebase" (disabled when identity empty);
  awaitingBrowser/authenticating → `ProgressView().tint(onPrimary)` + "Connecting…",
  disabled; loggedIn → "Signed in", disabled.
- Error line: `uiState?.error` → `Text(error).font(PhotosFont.caption)
  .foregroundColor(PhotosColor.error(scheme))`, id "login-error".
- Bottom caption: "You sign in on your own server. This app never sees a password."
  caption/`onSurfaceVariantDim`.
- Root `.accessibilityIdentifier("login-root")` on a concrete drawn container
  (ZStack with the background — same lesson as timeline-root).

### Step 3: `RootView.swift` + `ContentView.swift`

`RootModel: ObservableObject` observing `PhotosModuleKt.youAuthFlowManager().authState`
(same task pattern); publish a simple enum `RootRoute { splash, login, timeline }`
mapped: Initializing → splash; Authenticated → timeline; anything else → login.
`RootView`: `switch route { splash: PhotosColor.background full-screen;
login: LoginView(); timeline: TimelineView() }`.

`ContentView`:

```swift
struct ContentView: View {
    var body: some View {
        if ProcessInfo.processInfo.arguments.contains("-uiTestTimeline") {
            TimelineView()   // UI-test seam: timeline tests bypass the auth gate
        } else {
            RootView()
        }
    }
}
```

### Step 4: UI tests

- `TimelineGridUITest.swift`: add `app.launchArguments += ["-uiTestTimeline"]`
  before `app.launch()` (keep all existing assertions).
- `LoginScreenUITest.swift` (new, model structurally on TimelineGridUITest):
  launch WITHOUT the bypass arg on a fresh install → assert `login-root` exists,
  `login-id-field` exists, `login-submit` exists; type "sam.homebase.id" into the
  field and assert the submit button `isEnabled`. Do NOT tap submit in the test
  (it would open a real ASWebAuthenticationSession consent sheet).

## Done criteria

- [ ] Fresh-install launch shows the login screen; `-uiTestTimeline` shows the timeline.
- [ ] All §5.1 states render from `LoginUiState`; identifiers login-root/-id-field/-submit/-error present.
- [ ] ASWebAuthenticationSession wired with `callbackURLScheme: "homebase-photos"`, retained on the model, cancel → `onBrowserDismissed()`.
- [ ] Both UI test files updated/created.
- [ ] No out-of-scope file touched.

## STOP conditions

- SKIE symbol names for `LoginUiState`/`LoginPhase`/`LoginEvent` differ beyond
  mechanical flattening/casing — search the generated Shared interface under
  shared/build/ first (read-only); STOP only if the members genuinely don't exist.
- Excerpt mismatch in ContentView/TimelineModel.

## Maintenance notes

- `prefersEphemeralWebBrowserSession = false` keeps the owner's identity-server
  cookies so repeat logins are one-tap; flip to true if a "private login" option
  is ever wanted.
- When plan 004's viewer adds navigation, keep the auth gate in RootView above
  any NavigationStack.
- Logout entry point deferred (account button no-op), same as Android.
