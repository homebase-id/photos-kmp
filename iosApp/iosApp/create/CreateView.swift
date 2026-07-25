import SwiftUI

/// The Create tab — a minimal, honest placeholder this batch. One "New album" row opens a sheet
/// that says album creation is not here yet. Real create flows land in a later batch.
struct CreateView: View {
    @Environment(\.colorScheme) private var scheme
    @State private var showPlaceholder = false

    var body: some View {
        NavigationStack {
            List {
                Button {
                    showPlaceholder = true
                } label: {
                    Label("New album", systemImage: "rectangle.stack.badge.plus")
                        .foregroundColor(PhotosColor.onSurface(scheme))
                }
                .accessibilityIdentifier("create-new-album")
            }
            .navigationTitle("Create")
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("create-root")
        }
        .tint(PhotosColor.primary(scheme))
        .sheet(isPresented: $showPlaceholder) {
            CreatePlaceholderSheet()
        }
    }
}

/// The "coming soon" sheet for Create actions.
private struct CreatePlaceholderSheet: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: PhotosMetrics.space16) {
            Image(systemName: "rectangle.stack.badge.plus")
                .font(.system(size: 40))
                .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
            Text("Album creation arrives in the next update.")
                .font(PhotosFont.body)
                .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                .multilineTextAlignment(.center)
            Button("Close") { dismiss() }
                .font(PhotosFont.label)
                .foregroundColor(PhotosColor.primary(scheme))
        }
        .padding(PhotosMetrics.space32)
        .presentationDetents([.medium])
        .accessibilityIdentifier("create-placeholder")
    }
}
