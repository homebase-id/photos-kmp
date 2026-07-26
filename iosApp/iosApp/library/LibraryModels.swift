import SwiftUI
import Foundation
import Shared

/// Normalized snapshot `LibraryStateView` renders. Favorites/Archive/Trash each hand SKIE a
/// distinct Kotlin `UiState` class with the same shape, so this flattens all three instead of
/// forking the shared grid view.
struct LibrarySnapshot {
    let isLoading: Bool
    let isPaginating: Bool
    let pagedItems: [PhotoItem]
    let endReached: Bool
    let error: String?
    let selectedIds: Set<String>
    let isMutating: Bool

    var inSelectionMode: Bool { !selectedIds.isEmpty }
    var selectedCount: Int { selectedIds.count }
    func isSelected(_ item: PhotoItem) -> Bool { selectedIds.contains(item.fileId.description) }
}

// MARK: - Favorites

/// Owns the shared `FavoritesViewModel` across SwiftUI struct re-inits (same `@StateObject` +
/// SKIE `for await` pattern as `TimelineModel`); day grouping reuses `TimelineModel.groupDays`.
@MainActor
final class FavoritesModel: ObservableObject {
    let vm = PhotosModuleKt.favoritesViewModel()

    @Published private(set) var snapshot: LibrarySnapshot?
    @Published private(set) var monthSections: [TimelineMonth] = []
    @Published private(set) var toastMessage: String?

    private var observeTask: Task<Void, Never>?
    private var eventsTask: Task<Void, Never>?
    private var toastHideTask: Task<Void, Never>?
    private var photosChangedObserver: NSObjectProtocol?

    func start() {
        guard observeTask == nil else { return }
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.snapshot = LibrarySnapshot(
                    isLoading: s.isLoading,
                    isPaginating: s.isPaginating,
                    pagedItems: s.pagedItems,
                    endReached: s.endReached,
                    error: s.error,
                    selectedIds: Set(s.selectedIds),
                    isMutating: s.isMutating
                )
                self.monthSections = TimelineModel.groupDays(s.sections)
            }
        }
        let events = vm.events
        eventsTask = Task { [weak self] in
            for await e in events {
                guard let self else { return }
                if let err = e as? FavoritesEventError {
                    self.showToast(err.message)
                } else if let done = e as? FavoritesEventUnfavorited {
                    let n = Int(done.succeeded)
                    self.showToast(n == 1 ? "1 removed from favorites" : "\(n) removed from favorites")
                }
            }
        }
        // Cross-screen writes (viewer delete, archive/trash) can affect what belongs here.
        photosChangedObserver = NotificationCenter.default.addObserver(
            forName: .hbPhotosChanged, object: nil, queue: .main
        ) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, (note.object as AnyObject?) !== self else { return }
                self.vm.refresh()
            }
        }
    }

    func openViewer(for item: PhotoItem, in router: Router) {
        let items = snapshot?.pagedItems ?? []
        if let idx = items.firstIndex(where: { $0.fileId.description == item.fileId.description }) {
            router.openViewer(items: items, initialIndex: idx)
        }
    }

    func refreshAndWait() async { try? await vm.refreshAndWait() }
    func loadMore() { vm.loadMore() }
    func toggleSelection(_ item: PhotoItem) { vm.toggleSelection(photo: item) }
    func clearSelection() { vm.clearSelection() }

    /// Unfavorite the selection, then ping other screens (Timeline keeps the item, just flips
    /// its badge) — self-filtered so this screen's own listener doesn't re-fetch page 1.
    func unfavoriteSelected() async {
        try? await vm.unfavoriteSelectedAndWait()
        NotificationCenter.default.post(name: .hbPhotosChanged, object: self)
    }

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

// MARK: - Archive

/// Owns the shared `ArchiveViewModel` — mirrors `FavoritesModel`.
@MainActor
final class ArchiveModel: ObservableObject {
    let vm = PhotosModuleKt.archiveViewModel()

    @Published private(set) var snapshot: LibrarySnapshot?
    @Published private(set) var monthSections: [TimelineMonth] = []
    @Published private(set) var toastMessage: String?

    private var observeTask: Task<Void, Never>?
    private var eventsTask: Task<Void, Never>?
    private var toastHideTask: Task<Void, Never>?
    private var photosChangedObserver: NSObjectProtocol?

    func start() {
        guard observeTask == nil else { return }
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.snapshot = LibrarySnapshot(
                    isLoading: s.isLoading,
                    isPaginating: s.isPaginating,
                    pagedItems: s.pagedItems,
                    endReached: s.endReached,
                    error: s.error,
                    selectedIds: Set(s.selectedIds),
                    isMutating: s.isMutating
                )
                self.monthSections = TimelineModel.groupDays(s.sections)
            }
        }
        let events = vm.events
        eventsTask = Task { [weak self] in
            for await e in events {
                guard let self else { return }
                if let err = e as? ArchiveEventError {
                    self.showToast(err.message)
                } else if let done = e as? ArchiveEventUnarchived {
                    let n = Int(done.succeeded)
                    self.showToast(n == 1 ? "1 restored from archive" : "\(n) restored from archive")
                }
            }
        }
        photosChangedObserver = NotificationCenter.default.addObserver(
            forName: .hbPhotosChanged, object: nil, queue: .main
        ) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, (note.object as AnyObject?) !== self else { return }
                self.vm.refresh()
            }
        }
    }

    func openViewer(for item: PhotoItem, in router: Router) {
        let items = snapshot?.pagedItems ?? []
        if let idx = items.firstIndex(where: { $0.fileId.description == item.fileId.description }) {
            router.openViewer(items: items, initialIndex: idx)
        }
    }

    func refreshAndWait() async { try? await vm.refreshAndWait() }
    func loadMore() { vm.loadMore() }
    func toggleSelection(_ item: PhotoItem) { vm.toggleSelection(photo: item) }
    func clearSelection() { vm.clearSelection() }

    /// Unarchive the selection back to the library — items reappear in the Timeline, so ping it.
    func unarchiveSelected() async {
        try? await vm.unarchiveSelectedAndWait()
        NotificationCenter.default.post(name: .hbPhotosChanged, object: self)
    }

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

// MARK: - Trash

/// Owns the shared `TrashViewModel` — same shape as `ArchiveModel` plus permanent delete.
@MainActor
final class TrashModel: ObservableObject {
    let vm = PhotosModuleKt.trashViewModel()

    @Published private(set) var snapshot: LibrarySnapshot?
    @Published private(set) var monthSections: [TimelineMonth] = []
    @Published private(set) var toastMessage: String?

    private var observeTask: Task<Void, Never>?
    private var eventsTask: Task<Void, Never>?
    private var toastHideTask: Task<Void, Never>?
    private var photosChangedObserver: NSObjectProtocol?

    func start() {
        guard observeTask == nil else { return }
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.snapshot = LibrarySnapshot(
                    isLoading: s.isLoading,
                    isPaginating: s.isPaginating,
                    pagedItems: s.pagedItems,
                    endReached: s.endReached,
                    error: s.error,
                    selectedIds: Set(s.selectedIds),
                    isMutating: s.isMutating
                )
                self.monthSections = TimelineModel.groupDays(s.sections)
            }
        }
        let events = vm.events
        eventsTask = Task { [weak self] in
            for await e in events {
                guard let self else { return }
                if let err = e as? TrashEventError {
                    self.showToast(err.message)
                } else if let done = e as? TrashEventRestored {
                    let n = Int(done.succeeded)
                    self.showToast(n == 1 ? "1 restored" : "\(n) restored")
                } else if let done = e as? TrashEventPermanentlyDeleted {
                    let n = Int(done.count)
                    self.showToast(n == 1 ? "1 item deleted forever" : "\(n) items deleted forever")
                }
            }
        }
        photosChangedObserver = NotificationCenter.default.addObserver(
            forName: .hbPhotosChanged, object: nil, queue: .main
        ) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, (note.object as AnyObject?) !== self else { return }
                self.vm.refresh()
            }
        }
    }

    func openViewer(for item: PhotoItem, in router: Router) {
        let items = snapshot?.pagedItems ?? []
        if let idx = items.firstIndex(where: { $0.fileId.description == item.fileId.description }) {
            router.openViewer(items: items, initialIndex: idx)
        }
    }

    func refreshAndWait() async { try? await vm.refreshAndWait() }
    func loadMore() { vm.loadMore() }
    func toggleSelection(_ item: PhotoItem) { vm.toggleSelection(photo: item) }
    func clearSelection() { vm.clearSelection() }

    /// Restore the selection back to the library — items reappear in the Timeline, so ping it.
    func restoreSelected() async {
        try? await vm.restoreSelectedAndWait()
        NotificationCenter.default.post(name: .hbPhotosChanged, object: self)
    }

    /// Irreversible. No cross-screen ping needed — the items were only ever visible here.
    func permanentDeleteSelected() async {
        try? await vm.permanentDeleteSelectedAndWait()
    }

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
