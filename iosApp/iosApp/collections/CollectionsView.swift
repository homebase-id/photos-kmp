import SwiftUI
import Shared

/// The Collections tab: a 2-column album grid under a compact inline title, pushing
/// `AlbumDetailView` for a tapped album. State comes from `CollectionsModel` (one shared
/// `AlbumsViewModel`); this view only renders.
struct CollectionsView: View {
    @Environment(\.colorScheme) private var scheme
    @StateObject private var model = CollectionsModel()

    var body: some View {
        NavigationStack {
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
                .navigationTitle("Collections")
                .navigationBarTitleDisplayMode(.inline)
                .navigationDestination(for: AlbumItem.self) { album in
                    AlbumDetailView(album: album)
                }
        }
        .tint(PhotosColor.primary(scheme))
        .task { model.start() }
    }

    // MARK: - State branching

    @ViewBuilder
    private var content: some View {
        let state = model.uiState
        let albums = state?.albums ?? []
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
                message: "Albums in your Homebase library will show up here.",
                identifier: "collections-empty"
            )
        } else {
            grid(albums)
        }
    }

    // MARK: - Grid

    private func grid(_ albums: [AlbumSummary]) -> some View {
        ScrollView {
            LazyVGrid(columns: twoColumns, spacing: PhotosMetrics.space16) {
                ForEach(albums, id: \.album.fileId.description) { summary in
                    NavigationLink(value: summary.album) {
                        AlbumCard(summary: summary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(PhotosMetrics.screenEdge)
        }
        .refreshable { try? await model.vm.refreshAndWait() }
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
        ScrollView {
            LazyVGrid(columns: twoColumns, spacing: PhotosMetrics.space16) {
                ForEach(0..<6, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: PhotosMetrics.radiusAlbumCover)
                        .fill(PhotosColor.surfaceVariant(scheme))
                        .aspectRatio(1, contentMode: .fit)
                }
            }
            .padding(PhotosMetrics.screenEdge)
        }
        .allowsHitTesting(false)
        .accessibilityIdentifier("collections-skeleton")
    }
}
