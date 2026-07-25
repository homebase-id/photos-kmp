import SwiftUI
import Shared

/// The Create tab. "New album" opens the shared name sheet (C3) and the created album opens
/// straight away in Collections. Collage / animation / cinematic remain out of scope.
struct CreateView: View {
    @Environment(\.colorScheme) private var scheme
    @EnvironmentObject private var router: Router
    @StateObject private var model = AlbumsModel()

    @State private var showCreate = false
    /// Set by a successful create; the jump happens in the sheet's `onDismiss` so the pushed
    /// screen never races the sheet's dismissal animation.
    @State private var createdAlbum: AlbumItem?

    var body: some View {
        NavigationStack {
            List {
                Button {
                    showCreate = true
                } label: {
                    Label("New album", systemImage: "rectangle.stack.badge.plus")
                        .foregroundColor(PhotosColor.onSurface(scheme))
                }
                .accessibilityIdentifier("create-new-album")
            }
            .navigationTitle("Create")
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("create-root")
            .overlay(alignment: .bottom) { toastView }
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
        router.openAlbum(album)
    }

    @ViewBuilder
    private var toastView: some View {
        if let message = model.toastMessage {
            ToastCapsule(message: message, a11yId: "create-toast")
                .padding(.bottom, PhotosMetrics.space24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}
