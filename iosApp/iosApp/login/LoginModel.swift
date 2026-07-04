import SwiftUI
import Foundation
import UIKit
import Shared
import AuthenticationServices

/// Owns the shared `LoginViewModel` across SwiftUI struct re-inits and bridges the YouAuth browser
/// handoff to `ASWebAuthenticationSession`.
///
/// `@StateObject` guarantees ONE instance per view lifetime; `loginViewModel()` is a Koin `factory`,
/// so a fresh struct re-init would otherwise mint a new VM and drop in-flight auth state. The auth
/// session intercepts the `homebase-photos://` redirect itself — no Info.plist URL scheme, no
/// `onOpenURL`.
@MainActor
final class LoginModel: ObservableObject {
    let vm = PhotosModuleKt.loginViewModel()

    /// nil until the first shared emission — avoids constructing a LoginUiState in Swift.
    @Published private(set) var uiState: LoginUiState?

    private var observeTask: Task<Void, Never>?
    private var eventsTask: Task<Void, Never>?

    /// Retained for the browser handoff's lifetime — dropping it tears the sheet down.
    private var session: ASWebAuthenticationSession?
    private let contextProvider = AuthPresentationContext()

    /// Idempotent: wires the state + events subscriptions exactly once.
    func start() {
        guard observeTask == nil else { return }
        // Capture the AsyncSequences (not self) so the tasks never retain the model — the weak
        // self re-check each iteration lets `deinit` fire and cancel them.
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.uiState = s
            }
        }
        let events = vm.events
        eventsTask = Task { [weak self] in
            for await e in events {
                guard let self else { return }
                // Flattened SKIE sealed-subclass name (mirrors TimelineEventError).
                if let open = e as? LoginEventOpenUrl {
                    self.openAuthSession(url: open.url)
                }
            }
        }
    }

    /// Hands the YouAuth authorize URL to the system browser. ASWebAuthenticationSession intercepts
    /// the `homebase-photos://` redirect and returns the callback URL directly to the completion.
    private func openAuthSession(url: String) {
        // Guard the shared URL string rather than force-unwrap so a malformed value can't crash.
        guard let authURL = URL(string: url) else { return }
        let session = ASWebAuthenticationSession(
            url: authURL,
            callbackURLScheme: "homebase-photos"
        ) { [weak self] callbackURL, _ in
            Task { @MainActor in
                guard let self else { return }
                if let callbackURL {
                    self.vm.onCallback(url: callbackURL.absoluteString)
                } else {
                    self.vm.onBrowserDismissed() // user cancelled / error
                }
                self.session = nil
            }
        }
        session.presentationContextProvider = contextProvider
        session.prefersEphemeralWebBrowserSession = false // keep owner's server cookies
        self.session = session
        session.start()
    }

    deinit {
        observeTask?.cancel()
        eventsTask?.cancel()
        session?.cancel()
    }
}

/// Supplies the presentation anchor for `ASWebAuthenticationSession` from the first connected
/// window scene's key window. Standard boilerplate; falls back to a bare anchor.
private final class AuthPresentationContext: NSObject, ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let keyWindow = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        return keyWindow ?? ASPresentationAnchor()
    }
}
