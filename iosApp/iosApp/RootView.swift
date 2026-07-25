import SwiftUI
import Foundation
import Shared

/// Session gate. Observes the shared `YouAuthFlowManager.authState` and swaps the root between a
/// splash placeholder, the login screen, and the tabbed home. Kept above any NavigationStack so
/// the gate always wins regardless of in-app navigation (see plan 004's viewer).
struct RootView: View {
    @StateObject private var model = RootModel()

    var body: some View {
        Group {
            switch model.route {
            case .splash:
                SplashView()
            case .login:
                LoginView()
            case .home:
                HomeTabView()
            }
        }
        .task { model.start() }
    }
}

/// Coarse root destinations derived from the shared auth state.
enum RootRoute {
    case splash, login, home
}

/// Maps `YouAuthState` → a `RootRoute`. `Initializing` shows the splash (avoids a login flash
/// before the stored session resolves); `Authenticated` shows the tabbed home; everything else
/// (unauthenticated / authenticating / error) shows login.
@MainActor
final class RootModel: ObservableObject {
    private let manager = PhotosModuleKt.youAuthFlowManager()

    @Published private(set) var route: RootRoute = .splash

    private var observeTask: Task<Void, Never>?

    /// Idempotent: wires the authState subscription exactly once.
    func start() {
        guard observeTask == nil else { return }
        let states = manager.authState
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.route = Self.route(for: s)
            }
        }
    }

    // Flattened SKIE sealed-subclass casts (mirror the YouAuthStateAuthenticated etc. names).
    private static func route(for state: YouAuthState) -> RootRoute {
        if state is YouAuthStateInitializing { return .splash }
        if state is YouAuthStateAuthenticated { return .home }
        return .login
    }

    deinit {
        observeTask?.cancel()
    }
}
