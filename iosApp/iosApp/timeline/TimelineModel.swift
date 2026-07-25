import SwiftUI
import Foundation
import Shared

/// Owns the shared `TimelineViewModel` across SwiftUI struct re-inits and derives the
/// view-local shapes the grid renders (month → day groups, a prefetch id set, a toast).
///
/// `@StateObject` guarantees ONE instance per view lifetime; the Koin `factory` behind
/// `timelineViewModel()` would otherwise mint a fresh VM (and re-trigger the first-page
/// load) on every struct re-init. Day grouping + prefetch are computed here, off the
/// render path, so `body` never re-groups.
@MainActor
final class TimelineModel: ObservableObject {
    let vm = PhotosModuleKt.timelineViewModel()

    /// nil until the first shared emission — avoids constructing a TimelineUiState in Swift.
    @Published private(set) var uiState: TimelineUiState?
    /// Month sections, each carrying its day groups. Recomputed on each state emission.
    @Published private(set) var monthSections: [TimelineMonth] = []
    /// fileId descriptions of the tail items whose appearance should trigger `loadMore()`.
    @Published private(set) var prefetchIds: Set<String> = []
    /// Transient error banner (auto-hides). Only set while content is on screen.
    @Published private(set) var toastMessage: String?
    /// Persistent first-load error captured from events (belt-and-suspenders with `uiState.error`).
    @Published private(set) var loadError: String?

    private var observeTask: Task<Void, Never>?
    private var eventsTask: Task<Void, Never>?
    private var toastHideTask: Task<Void, Never>?
    private var photosChangedObserver: NSObjectProtocol?

    private var columns: Int = 4
    private var latestItems: [PhotoItem] = []

    /// Idempotent: wires the state + events subscriptions exactly once.
    func start() {
        guard observeTask == nil else { return }
        // Capture the AsyncSequences (not self) so the tasks never retain the model — the
        // weak self re-check each iteration lets `deinit` fire and cancel them (no leaked flow).
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.apply(state: s)
            }
        }
        let events = vm.events
        eventsTask = Task { [weak self] in
            for await e in events {
                guard let self else { return }
                if let err = e as? TimelineEventError {
                    self.handleError(err.message)
                } else if let deleted = e as? TimelineEventDeleted {
                    self.showToast("\(deleted.count) deleted")
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

    /// Open the fullscreen viewer at `item`'s position in the flat pager list (`pagedItems`),
    /// routed through the shared `Router` so the shell presents the single viewer cover.
    func openViewer(for item: PhotoItem, in router: Router) {
        let items = uiState?.pagedItems ?? []
        if let idx = items.firstIndex(where: { $0.fileId.description == item.fileId.description }) {
            router.openViewer(items: items, initialIndex: idx)
        }
    }

    /// Grid width changed → recompute how many tail items should prefetch the next page.
    func setColumns(_ c: Int) {
        let clamped = max(1, c)
        guard clamped != columns else { return }
        columns = clamped
        recomputePrefetch()
    }

    private func apply(state: TimelineUiState) {
        // A fresh load in flight clears any stale first-load error.
        if state.isLoading { loadError = nil }
        uiState = state
        latestItems = state.pagedItems
        monthSections = Self.groupDays(state.sections)
        recomputePrefetch()
    }

    private func recomputePrefetch() {
        // Prefetch margin: the last `columns * 4` loaded items (≈4 rows before the end).
        let n = max(1, columns * 4)
        prefetchIds = Set(latestItems.suffix(n).map { $0.fileId.description })
    }

    private func handleError(_ message: String) {
        // Content on screen → transient toast; empty screen → persistent error state.
        if let s = uiState, !s.sections.isEmpty {
            showToast(message)
        } else {
            loadError = message
        }
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

// MARK: - Day grouping

/// A month bucket (sticky header) carrying its day groups. Identity by month title.
struct TimelineMonth: Identifiable {
    let title: String
    let days: [TimelineDay]
    var id: String { title }
}

/// A day group inside a month (plain full-width sub-header). Identity by day title,
/// which is unique within a single-month section.
struct TimelineDay: Identifiable {
    let title: String
    let items: [PhotoItem]
    var id: String { title }
}

extension TimelineModel {
    // UTC everywhere to match the shared month bucketing (userDate is EXIF capture millis).
    private static let utcCalendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    private static let dayTitleFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = utcCalendar
        f.timeZone = TimeZone(identifier: "UTC")!
        f.setLocalizedDateFormatFromTemplate("EEE, MMM d")
        return f
    }()

    /// Split each month section's (already userDate-DESC) items into consecutive day runs.
    static func groupDays(_ sections: [TimelineSection]) -> [TimelineMonth] {
        sections.map { section in
            var days: [TimelineDay] = []
            var currentKey: DateComponents?
            var currentTitle = ""
            var bucket: [PhotoItem] = []
            for item in section.items {
                let date = Date(timeIntervalSince1970: Double(item.userDate) / 1000.0)
                let key = utcCalendar.dateComponents([.year, .month, .day], from: date)
                if key != currentKey {
                    if !bucket.isEmpty {
                        days.append(TimelineDay(title: currentTitle, items: bucket))
                    }
                    currentKey = key
                    currentTitle = dayTitleFormatter.string(from: date)
                    bucket = [item]
                } else {
                    bucket.append(item)
                }
            }
            if !bucket.isEmpty {
                days.append(TimelineDay(title: currentTitle, items: bucket))
            }
            return TimelineMonth(title: section.title, days: days)
        }
    }
}
