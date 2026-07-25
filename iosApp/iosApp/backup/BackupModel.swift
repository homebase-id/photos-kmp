import SwiftUI
import Foundation
import Photos
import Shared

/// Owns the shared `BackupViewModel` across SwiftUI struct re-inits (same `@StateObject` + SKIE
/// `for await` pattern as `TimelineModel`/`AlbumsModel`). This view-model only mirrors the
/// shared state and forwards actions — the one iOS-specific step is the Photos permission gate
/// on enable: iOS needs read/write library access before we can read + back up originals.
@MainActor
final class BackupModel: ObservableObject {
    let vm = PhotosModuleKt.backupViewModel()

    /// nil until the first shared emission — avoids constructing a BackupUiState in Swift.
    @Published private(set) var state: BackupUiState?
    /// Set when the user turned backup on but denied (or has denied) Photos access.
    @Published private(set) var permissionDenied = false

    private var observeTask: Task<Void, Never>?

    /// Idempotent: wires the state subscription exactly once.
    func start() {
        guard observeTask == nil else { return }
        // Capture the AsyncSequence (not self) so the task never retains the model — the weak
        // self re-check each iteration lets `deinit` fire and cancel it (no leaked flow).
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.state = s
            }
        }
    }

    func loadFolders() { vm.loadFolders() }
    /// Route through a BGContinuedProcessingTask so the upload survives backgrounding (system
    /// progress UI); the task handler drives the same shared pass.
    func onBackupNow() { BackgroundBackupTrigger.backupNow() }
    func onFolderToggled(_ folderId: String) { vm.onFolderToggled(folderId: folderId) }

    /// Enable requires Photos read/write access first; disable is unconditional. Only flip the
    /// shared enabled flag once the system grants `.authorized`/`.limited`; reflect denial in UI.
    func onToggle(_ enabled: Bool) {
        guard enabled else {
            permissionDenied = false
            vm.onToggle(enabled: false)
            return
        }
        Task { [weak self] in
            let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
            guard let self else { return }
            if status == .authorized || status == .limited {
                self.permissionDenied = false
                self.vm.onToggle(enabled: true)
                self.vm.loadFolders() // first grant: folders() was empty pre-auth — refresh now
            } else {
                self.permissionDenied = true
            }
        }
    }

    deinit {
        observeTask?.cancel()
    }
}
