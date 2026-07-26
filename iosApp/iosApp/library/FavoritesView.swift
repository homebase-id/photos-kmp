import SwiftUI
import Shared

/// Favorites (Batch D): the shared month-sectioned library grid over `FavoritesViewModel`,
/// pushed from the Collections library row. Selection offers only Unfavorite — favoriting itself
/// happens from the Timeline or the viewer.
struct FavoritesView: View {
    @Environment(\.colorScheme) private var scheme
    @EnvironmentObject private var router: Router
    @StateObject private var model = FavoritesModel()

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
        .navigationTitle("Favorites")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(inSelectionMode ? .hidden : .automatic, for: .navigationBar)
        .toolbar(inSelectionMode ? .hidden : .visible, for: .tabBar)
        .safeAreaInset(edge: .top) {
            if inSelectionMode {
                SelectionTopBar(
                    count: selectedCount,
                    onClose: { model.clearSelection() },
                    onUnfavorite: { Task { await model.unfavoriteSelected() } }
                )
                .disabled(model.snapshot?.isMutating == true)
            }
        }
        .task { model.start() }
    }

    private static let config = LibraryScreenConfig(
        gridId: "favorites-grid",
        skeletonId: "favorites-skeleton",
        emptyId: "favorites-empty",
        errorId: "favorites-error",
        emptyTitle: "No favorites yet",
        emptyMessage: "Tap the heart on a photo to add it here.",
        errorTitle: "Couldn't load favorites"
    )

    @ViewBuilder
    private var toastView: some View {
        if let message = model.toastMessage {
            ToastCapsule(message: message, a11yId: "favorites-toast")
                .padding(.bottom, PhotosMetrics.space24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}
