import SwiftUI

/// Loading placeholder: an edge-to-edge grid of quiet neutral squares. Non-interactive.
struct SkeletonGrid: View {
    @Environment(\.colorScheme) private var scheme
    let columns: Int
    var identifier: String = "timeline-skeleton"

    var body: some View {
        let gap = PhotosMetrics.gridGapWidth
        let gridColumns = Array(
            repeating: GridItem(.flexible(), spacing: gap, alignment: .center),
            count: max(1, columns)
        )
        return ScrollView {
            LazyVGrid(columns: gridColumns, spacing: gap) {
                ForEach(0..<(max(1, columns) * 12), id: \.self) { _ in
                    Rectangle()
                        .fill(PhotosColor.surfaceVariant(scheme))
                        .aspectRatio(1, contentMode: .fill)
                        .clipped()
                }
            }
        }
        .background(PhotosColor.background(scheme))
        .allowsHitTesting(false)
        .accessibilityIdentifier(identifier)
    }
}

/// Centered empty state: quiet display title + one-line hint on the plain background.
struct EmptyStateView: View {
    @Environment(\.colorScheme) private var scheme
    var title: String = "No photos yet"
    var message: String = "Back up your camera roll to see it here."
    var identifier: String = "timeline-empty"

    var body: some View {
        VStack(spacing: PhotosMetrics.space12) {
            Text(title)
                .font(PhotosFont.display)
                .foregroundColor(PhotosColor.onBackground(scheme))
            Text(message)
                .font(PhotosFont.bodyMedium)
                .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                .multilineTextAlignment(.center)
        }
        .padding(PhotosMetrics.screenEdge)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }
}

/// Centered error state with a filled primary retry pill.
struct ErrorStateView: View {
    @Environment(\.colorScheme) private var scheme
    var title: String = "Couldn't load photos"
    let message: String
    let onRetry: () -> Void
    var identifier: String = "timeline-error"

    var body: some View {
        VStack(spacing: PhotosMetrics.space12) {
            Text(title)
                .font(PhotosFont.display)
                .foregroundColor(PhotosColor.onBackground(scheme))
            Text(message)
                .font(PhotosFont.bodyMedium)
                .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                .multilineTextAlignment(.center)
            Button(action: onRetry) {
                Text("Try again")
                    .font(PhotosFont.label)
                    .foregroundColor(PhotosColor.onPrimary(scheme))
                    .padding(.horizontal, PhotosMetrics.space24)
                    .padding(.vertical, PhotosMetrics.space12)
                    .background(PhotosColor.primary(scheme))
                    .clipShape(RoundedRectangle(cornerRadius: PhotosMetrics.radiusXl))
            }
            .accessibilityLabel("Try again")
        }
        .padding(PhotosMetrics.screenEdge)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }
}

/// Centered spinner row appended while an older page loads.
struct PaginationFooter: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack {
            Spacer()
            ProgressView().tint(PhotosColor.primary(scheme))
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .frame(height: PhotosMetrics.space48)
        .accessibilityIdentifier("timeline-footer")
    }
}
