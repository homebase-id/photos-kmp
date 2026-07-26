import SwiftUI
import Shared

/// Trash (Batch D): the shared month-sectioned library grid over `TrashViewModel`, pushed from
/// the Collections library row. Selection offers Restore (back to the library) and Delete
/// Forever (irreversible, confirmed first). A persistent note above the grid explains the
/// retention model while not in selection mode.
struct TrashView: View {
    @Environment(\.colorScheme) private var scheme
    @EnvironmentObject private var router: Router
    @StateObject private var model = TrashModel()

    @State private var showDeleteForeverConfirm = false

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
        .navigationTitle("Trash")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(inSelectionMode ? .hidden : .automatic, for: .navigationBar)
        .toolbar(inSelectionMode ? .hidden : .visible, for: .tabBar)
        .safeAreaInset(edge: .top) {
            if inSelectionMode {
                SelectionTopBar(
                    count: selectedCount,
                    onClose: { model.clearSelection() },
                    onRestore: { Task { await model.restoreSelected() } },
                    onDeleteForever: { showDeleteForeverConfirm = true }
                )
                .disabled(model.snapshot?.isMutating == true)
            } else {
                noteBanner
            }
        }
        .task { model.start() }
        .alert(deleteForeverTitle, isPresented: $showDeleteForeverConfirm) {
            Button("Delete Forever", role: .destructive) {
                Task { await model.permanentDeleteSelected() }
            }
            .accessibilityIdentifier("delete-confirm")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This can't be undone.")
        }
    }

    private var deleteForeverTitle: String {
        selectedCount == 1 ? "Delete 1 item forever?" : "Delete \(selectedCount) items forever?"
    }

    private var noteBanner: some View {
        Text("Items stay in the bin until you delete them permanently.")
            .font(PhotosFont.caption)
            .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, PhotosMetrics.screenEdge)
            .padding(.vertical, PhotosMetrics.space8)
            .background(PhotosColor.surface1(scheme))
            .accessibilityIdentifier("trash-header-note")
    }

    private static let config = LibraryScreenConfig(
        gridId: "trash-grid",
        skeletonId: "trash-skeleton",
        emptyId: "trash-empty",
        errorId: "trash-error",
        emptyTitle: "Trash is empty",
        emptyMessage: "Deleted photos stay here for a while before they're gone for good.",
        errorTitle: "Couldn't load trash"
    )

    @ViewBuilder
    private var toastView: some View {
        if let message = model.toastMessage {
            ToastCapsule(message: message, a11yId: "trash-toast")
                .padding(.bottom, PhotosMetrics.space24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}
