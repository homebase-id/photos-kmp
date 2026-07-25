import SwiftUI
import Foundation
import Shared

/// Owns ONE shared `AlbumsViewModel` across SwiftUI struct re-inits (same `@StateObject` +
/// SKIE `for await` pattern as `TimelineModel` — the Koin `factory` behind `albumsViewModel()`
/// would otherwise mint a fresh VM, and re-trigger the album load, on every re-init).
///
/// Hosted by every screen that reads or writes albums: `CollectionsView` (the hub),
/// `AddToAlbumSheet` (the picker), and `AlbumDetailModel` (album-level writes only, since
/// rename/delete/set-cover live on `AlbumsViewModel` by the frozen shared contract).
@MainActor
final class AlbumsModel: ObservableObject {
    let vm = PhotosModuleKt.albumsViewModel()

    /// nil until the first shared emission — avoids constructing an AlbumsUiState in Swift.
    @Published private(set) var uiState: AlbumsUiState?
    /// Transient bottom capsule (auto-hides). Rendered by hosts that show a toast.
    @Published private(set) var toastMessage: String?

    /// Extra sink for hosts that render their own toast instead of `toastMessage`.
    var onMessage: ((String) -> Void)?

    private var observeTask: Task<Void, Never>?
    private var eventsTask: Task<Void, Never>?
    private var toastHideTask: Task<Void, Never>?

    var albums: [AlbumSummary] { uiState?.albums ?? [] }
    var isMutating: Bool { uiState?.isMutating == true }

    /// Idempotent: wires the state + events subscriptions exactly once. Write-only hosts skip
    /// this — they read the `…AndWait` return value instead of the events flow.
    func start() {
        guard observeTask == nil else { return }
        // Capture the AsyncSequences (not self) so the tasks never retain the model — the
        // weak self re-check each iteration lets `deinit` fire and cancel them (no leaked flow).
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
                if let message = AlbumsEventText.message(for: e) { self.show(message) }
            }
        }
    }

    // MARK: - Intents
    //
    // Every write awaits the shared `…AndWait` variant, which returns nil/false on failure with
    // the reason already on the events flow. `Uuid` is deliberately never named in Swift — file
    // ids are always mapped straight off a `PhotoItem`/`AlbumItem` at the call site.

    func create(name: String) async -> AlbumItem? {
        (try? await vm.createAlbumAndWait(name: name)) ?? nil
    }

    func create(name: String, with photos: [PhotoItem]) async -> AlbumItem? {
        (try? await vm.createAlbumWithPhotosAndWait(
            name: name,
            fileIds: photos.map { $0.fileId }
        )) ?? nil
    }

    /// Tags `photos` into `album`. Membership is one header patch per photo, so a partial result
    /// is normal — the returned line carries the added/failed split. nil = the write failed
    /// (reason already on the events flow).
    func add(_ photos: [PhotoItem], to album: AlbumItem) async -> String? {
        let result = (try? await vm.addToAlbumAndWait(
            albumTag: album.albumId,
            fileIds: photos.map { $0.fileId }
        )) ?? nil
        guard let result else { return nil }
        return AlbumsEventText.photosAdded(
            added: result.succeeded.count,
            failed: result.failed.count
        )
    }

    func rename(_ album: AlbumItem, to newName: String) async -> AlbumItem? {
        (try? await vm.renameAndWait(album: album, newName: newName)) ?? nil
    }

    func delete(_ album: AlbumItem) async -> Bool {
        (try? await vm.deleteAndWait(album: album)) ?? false
    }

    func setCover(_ album: AlbumItem, photo: PhotoItem) async -> AlbumItem? {
        (try? await vm.setCoverAndWait(album: album, photoFileId: photo.fileId)) ?? nil
    }

    /// Transient bottom capsule (auto-hides after 4s; a newer message restarts the clock).
    func show(_ message: String) {
        onMessage?(message)
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
    }
}

// MARK: - Event copy

/// The single place the flattened SKIE sealed-subclass casts for `AlbumsEvent` live (same style
/// as `RootModel`'s `YouAuthState*` casts). Kept exhaustive by hand: a Kotlin case not listed
/// here falls through to nil and shows nothing.
///
/// The parameter is `Any` on purpose — every other call site in the app casts the flattened
/// subclasses without ever naming the exported sealed type, and this keeps that habit.
enum AlbumsEventText {
    static func message(for event: Any) -> String? {
        if let e = event as? AlbumsEventError { return e.message }
        // Refused because another write is still in flight — nothing was sent.
        if event is AlbumsEventBusy { return "Another change is still in progress" }
        if let e = event as? AlbumsEventCreated { return "Created \u{201C}\(e.album.name)\u{201D}" }
        if let e = event as? AlbumsEventRenamed { return "Renamed to \u{201C}\(e.album.name)\u{201D}" }
        if let e = event as? AlbumsEventDeleted { return "Deleted \u{201C}\(e.album.name)\u{201D}" }
        if event is AlbumsEventCoverSet { return "Cover updated" }
        if let e = event as? AlbumsEventPhotosAdded {
            return photosAdded(added: Int(e.added), failed: Int(e.failed))
        }
        return nil
    }

    static func photosAdded(added: Int, failed: Int) -> String {
        if added == 0 { return failed == 1 ? "Couldn't add the photo" : "Couldn't add those photos" }
        if failed == 0 { return added == 1 ? "1 added to album" : "\(added) added to album" }
        return "\(added) of \(added + failed) added to album"
    }
}
