import SwiftUI
import Shared

/// One album's photo grid: the timeline's month/day layout over the album's photos, pushed by
/// `CollectionsView` inside its NavigationStack. Tapping a photo opens the fullscreen viewer
/// over the album's flat list. No selection mode here (round 1).
///
/// Back is the stock NavigationStack chevron — the system button can't carry the `album-back`
/// id without hiding it (which also drops edge-swipe pop), so the id is skipped per plan.
struct AlbumDetailView: View {
    @Environment(\.colorScheme) private var scheme
    @EnvironmentObject private var router: Router
    @StateObject private var model: AlbumDetailModel

    init(album: AlbumItem) {
        _model = StateObject(wrappedValue: AlbumDetailModel(album: album))
    }

    var body: some View {
        GeometryReader { geo in
            let columns = PhotosMetrics.timelineColumns(forWidth: geo.size.width)
            // No container id here: on a single-child container it would merge onto the surface
            // element and shadow the contracted `album-detail-grid` id (see TimelineView).
            content(columns: columns)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(PhotosColor.background(scheme).ignoresSafeArea())
        }
        .navigationTitle(model.uiState?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { model.start() }
    }

    // MARK: - State branching

    @ViewBuilder
    private func content(columns: Int) -> some View {
        let state = model.uiState
        let sections = state?.sections ?? []
        if state == nil || (state!.isLoading && sections.isEmpty) {
            SkeletonGrid(columns: columns, identifier: "album-detail-skeleton")
        } else if sections.isEmpty, let message = state?.error {
            ErrorStateView(
                title: "Couldn't load album",
                message: message,
                onRetry: { model.vm.refresh() },
                identifier: "album-detail-error"
            )
        } else if sections.isEmpty {
            EmptyStateView(
                title: "No photos yet",
                message: "Photos added to this album will show up here.",
                identifier: "album-detail-empty"
            )
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
                ForEach(model.monthSections) { month in
                    Section(header: MonthHeader(title: month.title)) {
                        ForEach(month.days) { day in
                            DayHeader(title: day.title)
                            LazyVGrid(columns: gridColumns, spacing: gap) {
                                ForEach(day.items, id: \.fileId.description) { item in
                                    PhotoCell(item: item, onTap: { model.openViewer(for: item, in: router) })
                                }
                            }
                        }
                    }
                }
            }
        }
        // Edge-to-edge: the background shows through the hairline cell spacing.
        .background(PhotosColor.gridGap(scheme))
        .refreshable { try? await model.vm.refreshAndWait() }
        .accessibilityIdentifier("album-detail-grid")
    }
}
