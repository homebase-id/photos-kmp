import SwiftUI
import Foundation
import Shared

/// Owns the shared `AlbumsViewModel` across SwiftUI struct re-inits (same `@StateObject` +
/// SKIE `for await` pattern as `TimelineModel` — the Koin `factory` behind `albumsViewModel()`
/// would otherwise mint a fresh VM, and re-trigger the album load, on every re-init).
@MainActor
final class CollectionsModel: ObservableObject {
    let vm = PhotosModuleKt.albumsViewModel()

    /// nil until the first shared emission — avoids constructing an AlbumsUiState in Swift.
    @Published private(set) var uiState: AlbumsUiState?

    private var observeTask: Task<Void, Never>?

    /// Idempotent: wires the state subscription exactly once.
    func start() {
        guard observeTask == nil else { return }
        // Capture the AsyncSequence (not self) so the task never retains the model — the
        // weak self re-check each iteration lets `deinit` fire and cancel it (no leaked flow).
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.uiState = s
            }
        }
    }

    deinit {
        observeTask?.cancel()
    }
}
