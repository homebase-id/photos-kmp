import SwiftUI

/// The Search tab — a minimal, honest placeholder this batch. A non-functional search field over
/// a "coming soon" empty state. Real search (indexing + query) lands in a later batch.
struct SearchView: View {
    @Environment(\.colorScheme) private var scheme
    @State private var query = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: PhotosMetrics.space12) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 40))
                    .foregroundColor(PhotosColor.onSurfaceVariantDim(scheme))
                Text("Search is coming soon")
                    .font(PhotosFont.body)
                    .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(PhotosColor.background(scheme).ignoresSafeArea())
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("search-empty")
        }
        // Non-functional this batch — the field accepts text but the empty state never changes.
        .searchable(text: $query, prompt: "Search photos")
        .tint(PhotosColor.primary(scheme))
    }
}
