import SwiftUI
import Shared

/// Per-screen a11y ids + copy the shared grid renders (Favorites/Archive/Trash each pass one).
struct LibraryScreenConfig {
    let gridId: String
    let skeletonId: String
    let emptyId: String
    let errorId: String
    let emptyTitle: String
    let emptyMessage: String
    let errorTitle: String
}

/// The month-sectioned grid content shared by Favorites/Archive/Trash (Batch D) — the same
/// skeleton/error/empty/grid state machine as `AlbumDetailView`, generalized over a
/// `LibrarySnapshot` so the three screens don't fork this logic three times. Selection chrome
/// (`SelectionTopBar`, confirmations, toasts) is each host's own job; this view only renders
/// content and reports taps/long-press/pagination/refresh upward.
struct LibraryStateView: View {
    @Environment(\.colorScheme) private var scheme
    let config: LibraryScreenConfig
    let snapshot: LibrarySnapshot?
    let monthSections: [TimelineMonth]
    let onTap: (PhotoItem) -> Void
    let onLongPress: (PhotoItem) -> Void
    let onLoadMore: () -> Void
    let onRefresh: () async -> Void

    var body: some View {
        GeometryReader { geo in
            let columns = PhotosMetrics.timelineColumns(forWidth: geo.size.width)
            content(columns: columns)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(PhotosColor.background(scheme).ignoresSafeArea())
        }
    }

    @ViewBuilder
    private func content(columns: Int) -> some View {
        if snapshot == nil || (snapshot!.isLoading && monthSections.isEmpty) {
            SkeletonGrid(columns: columns, identifier: config.skeletonId)
        } else if monthSections.isEmpty, let message = snapshot?.error {
            ErrorStateView(
                title: config.errorTitle,
                message: message,
                onRetry: { Task { await onRefresh() } },
                identifier: config.errorId
            )
        } else if monthSections.isEmpty {
            EmptyStateView(title: config.emptyTitle, message: config.emptyMessage, identifier: config.emptyId)
        } else {
            grid(columns: columns)
        }
    }

    // MARK: - Grid

    private func grid(columns: Int) -> some View {
        let gap = PhotosMetrics.gridGapWidth
        let gridColumns = Array(
            repeating: GridItem(.flexible(), spacing: gap, alignment: .center),
            count: max(1, columns)
        )
        return ScrollView {
            LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                ForEach(monthSections) { month in
                    Section(header: MonthHeader(title: month.title)) {
                        ForEach(month.days) { day in
                            DayHeader(title: day.title)
                            LazyVGrid(columns: gridColumns, spacing: gap) {
                                ForEach(day.items, id: \.fileId.description) { item in
                                    cell(for: item)
                                        .onAppear { maybePaginate(item: item) }
                                }
                            }
                        }
                    }
                }
                if snapshot?.isPaginating == true {
                    PaginationFooter()
                }
            }
        }
        // Edge-to-edge: the background shows through the hairline cell spacing.
        .background(PhotosColor.gridGap(scheme))
        .refreshable { await onRefresh() }
        .accessibilityIdentifier(config.gridId)
    }

    /// Selection routing mirrors the Timeline/AlbumDetail cells: tap toggles in selection mode,
    /// opens the viewer otherwise; long-press enters selection with this photo.
    private func cell(for item: PhotoItem) -> some View {
        PhotoCell(
            item: item,
            selected: snapshot?.isSelected(item) == true,
            selectionMode: snapshot?.inSelectionMode == true,
            onTap: { onTap(item) },
            onLongPress: { onLongPress(item) }
        )
    }

    /// Trigger the next page once the last currently-loaded item scrolls into view.
    private func maybePaginate(item: PhotoItem) {
        guard let snapshot, !snapshot.endReached, !snapshot.isPaginating else { return }
        if item.fileId.description == snapshot.pagedItems.last?.fileId.description {
            onLoadMore()
        }
    }
}
