import SwiftUI
import Shared

/// "Add to album" picker (C3): the existing albums plus a "New album" row, presented from the
/// Timeline selection bar and from the viewer's action bar.
///
/// It hosts its own `AlbumsModel` — `albumsViewModel()` is a Koin `factory`, so this instance is
/// independent of the Collections tab's and the picker always shows a freshly loaded list.
/// The outcome (added / partially added / failed) is reported back through `onFinished` so the
/// host owns the toast and decides whether to leave selection mode.
struct AddToAlbumSheet: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = AlbumsModel()

    let photos: [PhotoItem]
    /// Called with the message to surface, and whether anything landed.
    var onFinished: (String, Bool) -> Void = { _, _ in }

    @State private var showCreate = false
    @State private var isWorking = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button(action: { showCreate = true }) {
                        Label("New album", systemImage: "plus.rectangle.on.folder")
                            .font(PhotosFont.body)
                            .foregroundColor(PhotosColor.primary(scheme))
                    }
                    .accessibilityIdentifier("addto-new-album")
                }
                Section("Albums") {
                    if model.albums.isEmpty {
                        Text(albumsPlaceholder)
                            .font(PhotosFont.bodyMedium)
                            .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                            .accessibilityIdentifier("addto-album-empty")
                    }
                    ForEach(model.albums, id: \.album.fileId.description) { summary in
                        Button(action: { add(to: summary.album) }) {
                            AlbumRow(summary: summary)
                        }
                        .accessibilityIdentifier("addto-album-row")
                    }
                }
            }
            .navigationTitle(titleText)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("addto-cancel")
                }
            }
            .disabled(isWorking)
            .overlay { if isWorking { ProgressView().tint(PhotosColor.primary(scheme)) } }
        }
        .tint(PhotosColor.primary(scheme))
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("addto-album-sheet")
        .task { model.start() }
        .sheet(isPresented: $showCreate) {
            AlbumNameSheet(
                title: "New album",
                confirmLabel: "Create",
                identifier: "create-album-dialog"
            ) { name in
                await createAndAdd(named: name)
            }
        }
    }

    private var titleText: String {
        photos.count == 1 ? "Add to album" : "Add \(photos.count) to album"
    }

    private var albumsPlaceholder: String {
        model.uiState == nil ? "Loading albums…" : "No albums yet — start with New album."
    }

    // MARK: - Writes

    private func add(to album: AlbumItem) {
        guard !isWorking, !photos.isEmpty else { return }
        Task { @MainActor in
            isWorking = true
            let message = await model.add(photos, to: album)
            isWorking = false
            onFinished(message ?? "Couldn't add to album", message != nil)
            dismiss()
        }
    }

    /// "New album" from the picker: one shared call that creates and tags in one go.
    @MainActor
    private func createAndAdd(named name: String) async -> Bool {
        guard !photos.isEmpty else { return false }
        isWorking = true
        let album = await model.create(name: name, with: photos)
        isWorking = false
        guard let album else {
            onFinished("Couldn't create album", false)
            return false
        }
        onFinished("Added to \u{201C}\(album.name)\u{201D}", true)
        dismiss()
        return true
    }
}
