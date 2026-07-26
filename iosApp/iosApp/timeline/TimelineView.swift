import SwiftUI
import UIKit
import Shared

/// The Timeline grid — the heart of the MVP. A `NavigationStack`-hosted, edge-to-edge grid
/// of square-cropped thumbnails, grouped into month headers and day headers (Google Photos
/// hierarchy) on the plain system background, which also shows through the hairline 1.5pt cell
/// gap. Long-press enters multi-select (C5): the nav bar swaps for a `SelectionTopBar`, the
/// tab bar hides, and trash confirms a batch delete.
///
/// State comes from `TimelineModel` (a `@StateObject` owning ONE shared `TimelineViewModel`
/// across struct re-inits). Views render; the VM owns logic.
struct TimelineView: View {
    @Environment(\.colorScheme) private var scheme
    @EnvironmentObject private var router: Router
    @StateObject private var model = TimelineModel()
    @State private var showSettings = false
    @State private var showDeleteDialog = false
    @State private var showAddToAlbum = false

    private var inSelectionMode: Bool { model.uiState?.inSelectionMode == true }
    private var selectedCount: Int { model.uiState?.selectedIds.count ?? 0 }

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                let columns = PhotosMetrics.timelineColumns(forWidth: geo.size.width)
                // The background is attached as a `.background` modifier (edge-to-edge, no
                // strips) rather than a full-bleed ZStack sibling layer, so it draws BEHIND the
                // ScrollView and never covers the nav bar layer. It also carries the
                // `timeline-root` a11y id: a container modifier on the single-child surface
                // collapses onto the surface's own AX element and shadows its id (timeline-grid/
                // -empty), while a drawn sibling layer always materializes alongside it.
                content(columns: columns)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(
                        PhotosColor.background(scheme)
                            .ignoresSafeArea()
                            .accessibilityElement(children: .ignore)
                            .accessibilityIdentifier("timeline-root")
                    )
                    .overlay(alignment: .bottom) { toastView }
                    .onAppear { model.setColumns(columns) }
                    .onChange(of: columns) { _, newColumns in model.setColumns(newColumns) }
            }
            .navigationTitle("Photos")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: { showSettings = true }) {
                        Image(systemName: "person.crop.circle")
                            .font(.system(size: 26))
                            .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                            .frame(width: 32, height: 32)
                    }
                    .accessibilityLabel("Account")
                    .accessibilityIdentifier("account-button")
                }
            }
            // Selection swaps the whole top bar (C5): hide the nav bar, mount SelectionTopBar
            // in its place, and drop the tab bar until selection ends.
            .toolbar(inSelectionMode ? .hidden : .automatic, for: .navigationBar)
            .toolbar(inSelectionMode ? .hidden : .visible, for: .tabBar)
            .safeAreaInset(edge: .top) {
                if inSelectionMode {
                    SelectionTopBar(
                        count: selectedCount,
                        onClose: { model.vm.clearSelection() },
                        onAddToAlbum: { showAddToAlbum = true },
                        onFavorite: { Task { try? await model.vm.favoriteSelectedAndWait() } },
                        onArchive: { Task { try? await model.vm.archiveSelectedAndWait() } },
                        onDelete: { showDeleteDialog = true }
                    )
                }
            }
            // Only set the bar's scrolled (standard) appearance color — do NOT force
            // `.visible`: on iOS 26 that renders the opaque bar material over the title layer.
            .toolbarBackground(PhotosColor.surface(scheme), for: .navigationBar)
        }
        .tint(PhotosColor.primary(scheme))
        // Delete confirmation (C5). An alert, not a confirmationDialog — iOS 26 renders the
        // dialog as a card with no visible Cancel, wrong for a destructive confirm.
        .alert(deleteTitle, isPresented: $showDeleteDialog) {
            Button("Delete", role: .destructive) {
                Task { try? await model.vm.deleteSelectedAndWait() }
            }
            .accessibilityIdentifier("delete-confirm")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("They'll be removed from your Homebase photo library.")
        }
        .task { model.start() }
        // Settings sheet (Batch G): account button opens Settings; backup + logout live there now.
        .sheet(isPresented: $showSettings) { SettingsView() }
        // Add-to-album from the selection (C3). The picker owns its own AlbumsViewModel and
        // reports one line back; a landed add leaves selection mode, like a completed action.
        .sheet(isPresented: $showAddToAlbum) {
            AddToAlbumSheet(photos: model.uiState?.selectedPhotos ?? []) { message, landed in
                model.note(message)
                if landed { model.vm.clearSelection() }
            }
        }
    }

    private var deleteTitle: String {
        selectedCount == 1 ? "Delete 1 item?" : "Delete \(selectedCount) items?"
    }

    // MARK: - State branching

    @ViewBuilder
    private func content(columns: Int) -> some View {
        let state = model.uiState
        let sections = state?.sections ?? []
        if state == nil || (state!.isLoading && sections.isEmpty) {
            SkeletonGrid(columns: columns)
        } else if sections.isEmpty, let message = state?.error ?? model.loadError {
            ErrorStateView(message: message, onRetry: { model.vm.refresh() })
        } else if sections.isEmpty {
            EmptyStateView()
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
                                        .onAppear { maybePaginate(item: item) }
                                }
                            }
                        }
                    }
                }
                if model.uiState?.isPaginating == true {
                    PaginationFooter()
                }
            }
        }
        // Edge-to-edge: the background shows through the hairline cell spacing.
        .background(PhotosColor.gridGap(scheme))
        .refreshable { try? await model.vm.refreshAndWait() }
        .accessibilityIdentifier("timeline-grid")
    }

    /// Selection routing (C5): tap toggles in selection mode, opens the viewer otherwise;
    /// long-press enters selection with this photo.
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
            ToastCapsule(message: message, a11yId: "timeline-toast")
                .padding(.bottom, PhotosMetrics.space24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    /// Prefetch-margin pagination: any cell in the tail set (last `columns * 4` items)
    /// triggers the next page, so we load ~4 rows before the true end instead of at the
    /// very last cell.
    private func maybePaginate(item: PhotoItem) {
        guard let state = model.uiState, !state.endReached, !state.isPaginating else { return }
        if model.prefetchIds.contains(item.fileId.description) {
            model.vm.loadMore()
        }
    }
}
