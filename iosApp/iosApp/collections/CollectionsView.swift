import SwiftUI
import Shared

/// The Collections tab (C1): the library shortcuts card (Favorites/Archive/Trash/Utilities,
/// disabled until Batch D) above a 2-column album grid, pushing `AlbumDetailView` for a tapped
/// album. The toolbar `+` creates an album (C3) and opens it once the sheet is gone.
/// State comes from `AlbumsModel` (one shared `AlbumsViewModel`); this view only renders.
struct CollectionsView: View {
    @Environment(\.colorScheme) private var scheme
    @EnvironmentObject private var router: Router
    @StateObject private var model = AlbumsModel()

    @State private var showCreate = false
    /// Set by a successful create; the push happens in the sheet's `onDismiss` so the new
    /// screen never races the sheet's dismissal animation.
    @State private var createdAlbum: AlbumItem?

    var body: some View {
        NavigationStack(path: $router.path) {
            // The backdrop doubles as the `collections-root` a11y marker — same collapse lesson
            // as TimelineView: a container modifier on the single-child surface shadows its id.
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(
                    PhotosColor.background(scheme)
                        .ignoresSafeArea()
                        .accessibilityElement(children: .ignore)
                        .accessibilityIdentifier("collections-root")
                )
                .overlay(alignment: .bottom) { toastView }
                .navigationTitle("Collections")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(action: { showCreate = true }) {
                            Image(systemName: "plus")
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundColor(PhotosColor.primary(scheme))
                                .frame(width: 32, height: 32)
                        }
                        .accessibilityLabel("New album")
                        .accessibilityIdentifier("collections-add")
                    }
                }
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .albumDetail(let album):
                        AlbumDetailView(album: album)
                    case .favorites:
                        FavoritesView()
                    case .archive:
                        ArchiveView()
                    case .trash:
                        TrashView()
                    }
                }
        }
        .tint(PhotosColor.primary(scheme))
        .task { model.start() }
        .sheet(isPresented: $showCreate, onDismiss: openCreatedAlbum) {
            AlbumNameSheet(
                title: "New album",
                confirmLabel: "Create",
                identifier: "create-album-dialog"
            ) { name in
                guard let album = await model.create(name: name) else { return false }
                createdAlbum = album
                return true
            }
        }
    }

    private func openCreatedAlbum() {
        guard let album = createdAlbum else { return }
        createdAlbum = nil
        router.push(.albumDetail(album))
    }

    // MARK: - Content

    private var content: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                LibrarySection()
                albumsHeader
                albumsBody
            }
            .padding(.bottom, PhotosMetrics.space24)
        }
        .refreshable { try? await model.vm.refreshAndWait() }
    }

    private var albumsHeader: some View {
        Text("Albums")
            .font(PhotosFont.monthHeader)
            .foregroundColor(PhotosColor.onBackground(scheme))
            .padding(.horizontal, PhotosMetrics.screenEdge)
            .padding(.top, PhotosMetrics.space24)
            .padding(.bottom, PhotosMetrics.space8)
    }

    /// State branching for the album section only — the library card above always renders.
    @ViewBuilder
    private var albumsBody: some View {
        let state = model.uiState
        let albums = model.albums
        if state == nil || (state!.isLoading && albums.isEmpty) {
            skeleton
        } else if albums.isEmpty, let message = state?.error {
            ErrorStateView(
                title: "Couldn't load albums",
                message: message,
                onRetry: { model.vm.refresh() },
                identifier: "collections-error"
            )
        } else if albums.isEmpty {
            EmptyStateView(
                title: "No albums yet",
                message: "Tap + to make one, or add photos to an album from the timeline.",
                identifier: "collections-empty"
            )
        } else {
            grid(albums)
        }
    }

    // MARK: - Grid

    private func grid(_ albums: [AlbumSummary]) -> some View {
        LazyVGrid(columns: twoColumns, spacing: PhotosMetrics.space16) {
            ForEach(albums, id: \.album.fileId.description) { summary in
                NavigationLink(value: Route.albumDetail(summary.album)) {
                    AlbumCard(summary: summary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, PhotosMetrics.screenEdge)
        // `.contain` (not the default) so the grid itself vends an AX element for the id while
        // the cards stay reachable — the id no longer rides the ScrollView, which is shared
        // with the library card above.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("collections-grid")
    }

    private var twoColumns: [GridItem] {
        Array(
            repeating: GridItem(.flexible(), spacing: PhotosMetrics.space16, alignment: .top),
            count: 2
        )
    }

    /// Loading placeholder: six quiet rounded squares in the album-grid layout.
    private var skeleton: some View {
        LazyVGrid(columns: twoColumns, spacing: PhotosMetrics.space16) {
            ForEach(0..<6, id: \.self) { _ in
                RoundedRectangle(cornerRadius: PhotosMetrics.radiusAlbumCover)
                    .fill(PhotosColor.surfaceVariant(scheme))
                    .aspectRatio(1, contentMode: .fit)
            }
        }
        .padding(.horizontal, PhotosMetrics.screenEdge)
        .allowsHitTesting(false)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("collections-skeleton")
    }

    @ViewBuilder
    private var toastView: some View {
        if let message = model.toastMessage {
            ToastCapsule(message: message, a11yId: "collections-toast")
                .padding(.bottom, PhotosMetrics.space24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}
