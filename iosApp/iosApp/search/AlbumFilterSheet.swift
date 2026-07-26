import SwiftUI
import Shared

/// Album filter picker: a plain list of the user's albums plus an "All albums" clear row.
/// Hosts its own `AlbumsModel` (independent, freshly-loaded instance) the same way
/// `AddToAlbumSheet` does — reuses `AlbumRow` for the row layout.
struct AlbumFilterSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @StateObject private var model = AlbumsModel()

    let selected: AlbumItem?
    var onSelect: (AlbumItem?) -> Void = { _ in }

    var body: some View {
        NavigationStack {
            List {
                Button(action: { pick(nil) }) {
                    HStack {
                        Text("All albums")
                            .font(PhotosFont.body)
                            .foregroundColor(PhotosColor.onSurface(scheme))
                        Spacer()
                        if selected == nil {
                            Image(systemName: "checkmark")
                                .foregroundColor(PhotosColor.primary(scheme))
                        }
                    }
                }
                .accessibilityIdentifier("search-album-clear")

                Section("Albums") {
                    if model.albums.isEmpty {
                        Text(albumsPlaceholder)
                            .font(PhotosFont.bodyMedium)
                            .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                    }
                    ForEach(model.albums, id: \.album.fileId.description) { summary in
                        Button(action: { pick(summary.album) }) {
                            AlbumRow(summary: summary)
                                .overlay(alignment: .trailing) {
                                    if selected?.albumId.description == summary.album.albumId.description {
                                        Image(systemName: "checkmark")
                                            .foregroundColor(PhotosColor.primary(scheme))
                                    }
                                }
                        }
                    }
                }
            }
            .navigationTitle("Filter by album")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .tint(PhotosColor.primary(scheme))
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("search-album-sheet")
        .task { model.start() }
    }

    private var albumsPlaceholder: String {
        model.uiState == nil ? "Loading albums…" : "No albums yet"
    }

    private func pick(_ album: AlbumItem?) {
        onSelect(album)
        dismiss()
    }
}
