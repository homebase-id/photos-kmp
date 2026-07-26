import SwiftUI
import Shared

/// Archive (Batch D): the shared month-sectioned library grid over `ArchiveViewModel`, pushed
/// from the Collections library row. Selection offers only Unarchive, which returns the photos
/// to the main Timeline.
struct ArchiveView: View {
    @Environment(\.colorScheme) private var scheme
    @EnvironmentObject private var router: Router
    @StateObject private var model = ArchiveModel()

    private var inSelectionMode: Bool { model.snapshot?.inSelectionMode == true }
    private var selectedCount: Int { model.snapshot?.selectedCount ?? 0 }

    var body: some View {
        LibraryStateView(
            config: Self.config,
            snapshot: model.snapshot,
            monthSections: model.monthSections,
            onTap: { item in
                if inSelectionMode {
                    model.toggleSelection(item)
                } else {
                    model.openViewer(for: item, in: router)
                }
            },
            onLongPress: { item in
                if !inSelectionMode { model.toggleSelection(item) }
            },
            onLoadMore: { model.loadMore() },
            onRefresh: { await model.refreshAndWait() }
        )
        .overlay(alignment: .bottom) { toastView }
        .navigationTitle("Archive")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(inSelectionMode ? .hidden : .automatic, for: .navigationBar)
        .toolbar(inSelectionMode ? .hidden : .visible, for: .tabBar)
        .safeAreaInset(edge: .top) {
            if inSelectionMode {
                SelectionTopBar(
                    count: selectedCount,
                    onClose: { model.clearSelection() },
                    onUnarchive: { Task { await model.unarchiveSelected() } }
                )
                .disabled(model.snapshot?.isMutating == true)
            }
        }
        .task { model.start() }
    }

    private static let config = LibraryScreenConfig(
        gridId: "archive-grid",
        skeletonId: "archive-skeleton",
        emptyId: "archive-empty",
        errorId: "archive-error",
        emptyTitle: "No archived photos",
        emptyMessage: "Archived photos leave the main timeline but stay in your library.",
        errorTitle: "Couldn't load archive"
    )

    @ViewBuilder
    private var toastView: some View {
        if let message = model.toastMessage {
            ToastCapsule(message: message, a11yId: "archive-toast")
                .padding(.bottom, PhotosMetrics.space24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}
