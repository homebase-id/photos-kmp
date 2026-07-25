import SwiftUI
import Foundation
import Shared

/// Owns the shared `AlbumDetailViewModel` for one album across SwiftUI struct re-inits and
/// derives the month → day groups the grid renders (same `@StateObject` + SKIE `for await`
/// pattern as `TimelineModel`; day grouping reuses its `groupDays`).
///
/// Album-level writes (rename / delete / set cover) live on `AlbumsViewModel` by the frozen
/// shared contract, so this model also hosts one — lazily, because constructing it eagerly
/// loads and cover-resolves every album for a screen that may never rename anything.
@MainActor
final class AlbumDetailModel: ObservableObject {
    let vm: AlbumDetailViewModel

    /// The album as last written. Seeded from the pushed value and replaced by rename/set-cover,
    /// so the title reflects a rename without re-navigating (the shared `state.title` is seeded
    /// once at VM construction and never changes).
    @Published private(set) var album: AlbumItem
    /// nil until the first shared emission — avoids constructing an AlbumDetailUiState in Swift.
    @Published private(set) var uiState: AlbumDetailUiState?
    /// Month sections, each carrying its day groups. Recomputed on each state emission.
    @Published private(set) var monthSections: [TimelineMonth] = []
    /// Transient error/confirmation banner (auto-hides).
    @Published private(set) var toastMessage: String?
    /// An album-level write is in flight — the menu and selection actions stay disabled.
    @Published private(set) var isBusy = false

    private var observeTask: Task<Void, Never>?
    private var eventsTask: Task<Void, Never>?
    private var toastHideTask: Task<Void, Never>?
    private var photosChangedObserver: NSObjectProtocol?

    /// Write-only: never `start()`ed, so it costs one album load on the first album-level action
    /// and nothing at all if the user only browses. Outcomes come from the awaited return value.
    private lazy var albumWrites = AlbumsModel()

    init(album: AlbumItem) {
        self.album = album
        vm = PhotosModuleKt.albumDetailViewModel(album: album)
    }

    var selectedPhotos: [PhotoItem] { uiState?.selectedPhotos ?? [] }
    var inSelectionMode: Bool { uiState?.inSelectionMode == true }
    var selectedCount: Int { uiState?.selectedIds.count ?? 0 }

    /// Idempotent: wires the state + events subscriptions exactly once.
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
        let events = vm.events
        eventsTask = Task { [weak self] in
            for await e in events {
                guard let self else { return }
                if let err = e as? AlbumDetailEventError {
                    self.showToast(err.message)
                } else if let removed = e as? AlbumDetailEventRemoved {
                    let n = Int(removed.count)
                    self.showToast(n == 1 ? "1 removed from album" : "\(n) removed from album")
                }
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

    // MARK: - Writes

    /// Untag the selection from this album (the photos themselves are untouched).
    func removeSelected() async {
        try? await vm.removeSelectedAndWait()
    }

    func rename(to newName: String) async {
        guard !isBusy else { return }
        isBusy = true
        let renamed = await albumWrites.rename(album, to: newName)
        isBusy = false
        guard let renamed else {
            showToast("Couldn't rename album")
            return
        }
        album = renamed
        showToast("Renamed to \u{201C}\(renamed.name)\u{201D}")
    }

    /// Pin the single selected photo as the cover. Selection is cleared on success so the bar
    /// gets out of the way, like a completed action anywhere else in the app.
    func setCoverFromSelection() async {
        guard !isBusy, let photo = selectedPhotos.first, selectedPhotos.count == 1 else { return }
        isBusy = true
        let updated = await albumWrites.setCover(album, photo: photo)
        isBusy = false
        guard let updated else {
            showToast("Couldn't set cover")
            return
        }
        album = updated
        vm.clearSelection()
        showToast("Cover updated")
    }

    /// Deletes the album file only — its photos stay in the library. Returns whether the caller
    /// should pop back to Collections.
    func deleteAlbum() async -> Bool {
        guard !isBusy else { return false }
        isBusy = true
        let deleted = await albumWrites.delete(album)
        isBusy = false
        if !deleted { showToast("Couldn't delete album") }
        return deleted
    }

    /// Transient bottom capsule (auto-hides after 4s; a newer toast restarts the clock).
    private func showToast(_ message: String) {
        toastMessage = message
        toastHideTask?.cancel()
        toastHideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            self?.toastMessage = nil
        }
    }

    deinit {
        observeTask?.cancel()
        eventsTask?.cancel()
        toastHideTask?.cancel()
        if let photosChangedObserver {
            NotificationCenter.default.removeObserver(photosChangedObserver)
        }
    }
}
