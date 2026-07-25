import SwiftUI
import UIKit
import Shared

/// One album tile: square cover (resolved thumbnail, else a flat neutral fill) under a 14pt
/// radius, album name below — semibold, nothing else.
struct AlbumCard: View {
    @Environment(\.colorScheme) private var scheme
    let summary: AlbumSummary

    @State private var cover: UIImage?

    /// Covers reuse the grid's 300-max-dim tier — likely already cached by the timeline.
    private static let coverMaxDim = 300

    var body: some View {
        VStack(alignment: .leading, spacing: PhotosMetrics.space8) {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    ZStack {
                        PhotosColor.surfaceVariant(scheme)
                        if let cover {
                            Image(uiImage: cover)
                                .resizable()
                                .scaledToFill()
                                .transition(.opacity)
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: PhotosMetrics.radiusAlbumCover))
            Text(summary.album.name)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(PhotosColor.onBackground(scheme))
                .lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Album, \(summary.album.name)")
        .accessibilityIdentifier("album-card")
        .task(id: coverKey) {
            guard let item = summary.cover else { return }
            let loaded = await ThumbnailLoader.shared.image(for: item, maxDim: Self.coverMaxDim)
            withAnimation(.easeIn(duration: 0.2)) { cover = loaded }
        }
    }

    private var coverKey: String { summary.cover?.fileId.description ?? "none" }
}

/// The list form of an album tile (add-to-album picker): a small rounded cover next to the name.
/// Same cover tier and loader as `AlbumCard`, so the picker paints from the same cache.
struct AlbumRow: View {
    @Environment(\.colorScheme) private var scheme
    let summary: AlbumSummary

    @State private var cover: UIImage?

    private static let coverMaxDim = 300
    private static let side: CGFloat = 44

    var body: some View {
        HStack(spacing: PhotosMetrics.space12) {
            ZStack {
                PhotosColor.surfaceVariant(scheme)
                if let cover {
                    Image(uiImage: cover)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                } else {
                    Image(systemName: "rectangle.stack")
                        .font(.system(size: 15))
                        .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                }
            }
            .frame(width: Self.side, height: Self.side)
            .clipShape(RoundedRectangle(cornerRadius: PhotosMetrics.space8))

            Text(summary.album.name)
                .font(PhotosFont.body)
                .foregroundColor(PhotosColor.onSurface(scheme))
                .lineLimit(1)
            Spacer()
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Album, \(summary.album.name)")
        .task(id: coverKey) {
            guard let item = summary.cover else { return }
            let loaded = await ThumbnailLoader.shared.image(for: item, maxDim: Self.coverMaxDim)
            withAnimation(.easeIn(duration: 0.2)) { cover = loaded }
        }
    }

    private var coverKey: String { summary.cover?.fileId.description ?? "none" }
}
