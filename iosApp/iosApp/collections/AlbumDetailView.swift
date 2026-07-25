import SwiftUI
import Shared

/// One album's photo grid: the timeline's month/day layout over the album's photos, pushed by
/// `CollectionsView` inside its NavigationStack. Tapping a photo opens the fullscreen viewer
/// over the album's flat list.
///
/// C2: long-press enters selection (the Timeline UX, same `SelectionTopBar`) where a single
/// picked photo can become the cover and any selection can be removed from the album; the
/// toolbar `Menu` (`album-menu`) renames and deletes the album itself.
///
/// Back is the stock NavigationStack chevron — the system button can't carry the `album-back`
/// id without hiding it (which also drops edge-swipe pop), so the id is skipped per plan.
struct AlbumDetailView: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var router: Router
    @StateObject private var model: AlbumDetailModel

    @State private var showRename = false
    @State private var renameText = ""
    @State private var showDeleteConfirm = false
    @State private var showRemoveConfirm = false

    init(album: AlbumItem) {
        _model = StateObject(wrappedValue: AlbumDetailModel(album: album))
    }

    private var inSelectionMode: Bool { model.inSelectionMode }
    private var selectedCount: Int { model.selectedCount }

    var body: some View {
        GeometryReader { geo in
            let columns = PhotosMetrics.timelineColumns(forWidth: geo.size.width)
            // No container id here: on a single-child container it would merge onto the surface
            // element and shadow the contracted `album-detail-grid` id (see TimelineView).
            content(columns: columns)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(PhotosColor.background(scheme).ignoresSafeArea())
                .overlay(alignment: .bottom) { toastView }
        }
        .navigationTitle(model.album.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { albumMenu }
        }
        // Selection swaps the whole top bar (mirrors the Timeline): hide the nav bar, mount
        // SelectionTopBar in its place, and drop the tab bar until selection ends.
        .toolbar(inSelectionMode ? .hidden : .automatic, for: .navigationBar)
        .toolbar(inSelectionMode ? .hidden : .visible, for: .tabBar)
        .safeAreaInset(edge: .top) {
            if inSelectionMode {
                SelectionTopBar(
                    count: selectedCount,
                    onClose: { model.vm.clearSelection() },
                    // Set-as-cover needs exactly one photo, so it only appears then.
                    onSetCover: selectedCount == 1
                        ? { Task { await model.setCoverFromSelection() } }
                        : nil,
                    onRemoveFromAlbum: { showRemoveConfirm = true }
                )
                .disabled(model.isBusy || model.uiState?.isRemoving == true)
            }
        }
        .task { model.start() }
        // Rename in an alert text field (C2). Alerts, not confirmationDialogs, for both the
        // rename and the destructive confirms — same choice as the timeline's delete.
        .alert("Rename album", isPresented: $showRename) {
            TextField("Album name", text: $renameText)
                .accessibilityIdentifier("album-rename-field")
            Button("Rename") {
                let name = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !name.isEmpty else { return }
                Task { await model.rename(to: name) }
            }
            .accessibilityIdentifier("album-rename-confirm")
            Button("Cancel", role: .cancel) {}
        }
        .alert("Delete album?", isPresented: $showDeleteConfirm) {
            Button("Delete", role: .destructive) {
                Task { if await model.deleteAlbum() { dismiss() } }
            }
            .accessibilityIdentifier("album-delete-confirm")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The album goes away. Its photos stay in your library.")
        }
        .alert(removeTitle, isPresented: $showRemoveConfirm) {
            Button("Remove", role: .destructive) {
                Task { await model.removeSelected() }
            }
            .accessibilityIdentifier("album-remove-confirm")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("They stay in your library — only the album loses them.")
        }
    }

    private var removeTitle: String {
        selectedCount == 1 ? "Remove 1 photo?" : "Remove \(selectedCount) photos?"
    }

    // MARK: - Album menu

    private var albumMenu: some View {
        Menu {
            Button {
                renameText = model.album.name
                showRename = true
            } label: {
                Label("Rename", systemImage: "pencil")
            }
            .accessibilityIdentifier("album-rename")

            Button(role: .destructive) {
                showDeleteConfirm = true
            } label: {
                Label("Delete album", systemImage: "trash")
            }
            .accessibilityIdentifier("album-delete")
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.system(size: 20))
                .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                .frame(width: 32, height: 32)
        }
        .disabled(model.isBusy)
        .accessibilityLabel("Album actions")
        .accessibilityIdentifier("album-menu")
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
                message: "Add photos from the timeline — select them, then Add to album.",
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
                                    cell(for: item)
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

    /// Selection routing: tap toggles in selection mode, opens the viewer otherwise;
    /// long-press enters selection with this photo (mirrors the Timeline).
    private func cell(for item: PhotoItem) -> some View {
        PhotoCell(
            item: item,
            selected: model.uiState?.isSelected(photo: item) == true,
            selectionMode: inSelectionMode,
            onTap: {
                if inSelectionMode {
                    model.vm.toggleSelection(photo: item)
                } else {
                    model.openViewer(for: item, in: router)
                }
            },
            onLongPress: {
                if !inSelectionMode { model.vm.toggleSelection(photo: item) }
            }
        )
    }

    @ViewBuilder
    private var toastView: some View {
        if let message = model.toastMessage {
            ToastCapsule(message: message, a11yId: "album-detail-toast")
                .padding(.bottom, PhotosMetrics.space24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}
