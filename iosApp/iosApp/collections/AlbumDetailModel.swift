import SwiftUI
import Foundation
import Shared

/// Owns the shared `AlbumDetailViewModel` for one album across SwiftUI struct re-inits and
/// derives the month → day groups the grid renders (same `@StateObject` + SKIE `for await`
/// pattern as `TimelineModel`; day grouping reuses its `groupDays`).
@MainActor
final class AlbumDetailModel: ObservableObject {
    let vm: AlbumDetailViewModel

    /// nil until the first shared emission — avoids constructing an AlbumDetailUiState in Swift.
    @Published private(set) var uiState: AlbumDetailUiState?
    /// Month sections, each carrying its day groups. Recomputed on each state emission.
    @Published private(set) var monthSections: [TimelineMonth] = []

    private var observeTask: Task<Void, Never>?
    private var photosChangedObserver: NSObjectProtocol?

    init(album: AlbumItem) {
        vm = PhotosModuleKt.albumDetailViewModel(album: album)
    }

    /// Idempotent: wires the state subscription exactly once.
    func start() {
        guard observeTask == nil else { return }
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.uiState = s
                self.monthSections = TimelineModel.groupDays(s.sections)
            }
        }
        // The viewer pings on close after any delete — refresh so the grid drops stale cells.
        photosChangedObserver = NotificationCenter.default.addObserver(
            forName: .hbPhotosChanged, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.vm.refresh() }
        }
    }

    /// Open the fullscreen viewer at `item`'s position in the album's flat pager list, routed
    /// through the shared `Router` so the shell presents the single viewer cover.
    func openViewer(for item: PhotoItem, in router: Router) {
        let items = uiState?.photos ?? []
        if let idx = items.firstIndex(where: { $0.fileId.description == item.fileId.description }) {
            router.openViewer(items: items, initialIndex: idx)
        }
    }

    deinit {
        observeTask?.cancel()
        if let photosChangedObserver {
            NotificationCenter.default.removeObserver(photosChangedObserver)
        }
    }
}
