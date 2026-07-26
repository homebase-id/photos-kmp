import SwiftUI
import Foundation
import Shared

/// Owns the shared `SearchViewModel` (Batch E) across SwiftUI struct re-inits — same
/// `@StateObject` + SKIE `for await` pattern as `TimelineModel`. Read-only screen: no
/// selection/mutation, so there's no events flow to subscribe to, just `state`.
///
/// `sections` is already month-grouped by the VM (`groupIntoMonthSections`); `TimelineModel
/// .groupDays` further splits each month into day runs for the shared grid components.
@MainActor
final class SearchModel: ObservableObject {
    let vm = PhotosModuleKt.searchViewModel()

    /// nil until the first shared emission — avoids constructing a SearchUiState in Swift.
    @Published private(set) var uiState: SearchUiState?
    @Published private(set) var monthSections: [TimelineMonth] = []
    /// Transient bottom capsule for a search failure that still has stale results on screen —
    /// `content()`'s empty-state branch already surfaces `error` when `sections` is empty, but a
    /// re-search (e.g. flipping a filter) that fails while old results are still showing would
    /// otherwise be silent.
    @Published private(set) var toastMessage: String?

    private var observeTask: Task<Void, Never>?
    private var toastHideTask: Task<Void, Never>?

    func start() {
        guard observeTask == nil else { return }
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.uiState = s
                self.monthSections = TimelineModel.groupDays(s.sections)
                if let error = s.error, !s.sections.isEmpty {
                    self.showToast(error)
                }
            }
        }
    }

    /// Transient bottom capsule (auto-hides after 4s; a newer message restarts the clock) —
    /// same timing as Favorites/Archive's toast.
    private func showToast(_ message: String) {
        toastMessage = message
        toastHideTask?.cancel()
        toastHideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            self?.toastMessage = nil
        }
    }

    /// Open the fullscreen viewer at `item`'s position in the flat (already newest-first)
    /// result list — mirrors Favorites/Archive's `openViewer`.
    func openViewer(for item: PhotoItem, in router: Router) {
        let items = uiState?.sections.flatMap { $0.items } ?? []
        if let idx = items.firstIndex(where: { $0.fileId.description == item.fileId.description }) {
            router.openViewer(items: items, initialIndex: idx)
        }
    }

    // MARK: - Intents

    func onQueryChange(_ query: String) {
        vm.onQueryChange(query: query)
    }

    /// iOS awaits the search so `.onSubmit(of: .search)` can show a spinner via `isSearching`.
    func submit() async {
        try? await vm.submitAndWait()
    }

    /// `from`/`to` are whole days — the sheet already resolves them to day-start/day-end millis.
    func setDateRange(from: Int64?, to: Int64?) {
        vm.setDateRange(
            from: from.map { KotlinLong(longLong: $0) },
            to: to.map { KotlinLong(longLong: $0) }
        )
    }

    func setTypeFilter(_ filter: TypeFilter) {
        vm.setTypeFilter(filter: filter)
    }

    func setAlbumFilter(_ album: AlbumItem?) {
        vm.setAlbumFilter(album: album)
    }

    func clearFilters() {
        vm.clearFilters()
    }

    func clearRecent() {
        vm.clearRecent()
    }

    /// Recents row tap: set the query then submit, same as a manual type-and-search.
    func selectRecent(_ query: String) async {
        vm.onQueryChange(query: query)
        await submit()
    }

    deinit {
        observeTask?.cancel()
        toastHideTask?.cancel()
    }
}
