import SwiftUI
import UIKit
import Shared

/// A single square grid cell: flat neutral fallback → blurred inline placeholder → crossfaded
/// thumbnail, with a play badge for videos. No GeometryReader — `aspectRatio(1,
/// .fit) + .overlay + .clipped()` inside the grid column does the square sizing.
///
/// Selection (C5): a selected cell insets its image (6pt padding + 8pt corner radius) and shows
/// a filled primary check-circle badge top-leading. Unselected cells in selection mode render
/// unchanged. Long-press is the selection entry point; the caller decides what tap/long-press do.
struct PhotoCell: View {
    @Environment(\.colorScheme) private var scheme
    let item: PhotoItem
    var selected: Bool = false
    var selectionMode: Bool = false
    var onTap: () -> Void = {}
    var onLongPress: () -> Void = {}

    @State private var image: UIImage?
    @State private var placeholder: UIImage?

    /// Grid thumbnails request the 300-max-dim server thumbnail (§4.4 / PhotoConfig).
    private static let gridMaxDim = 300

    /// Decoded inline placeholders, keyed by fileId — decode base64 once, never in `body`.
    private static let placeholderCache = NSCache<NSString, UIImage>()

    private static let dateLabelFormatter: DateFormatter = {
        let f = DateFormatter()
        f.timeZone = TimeZone(identifier: "UTC")!
        f.dateFormat = "MMM d, yyyy"
        return f
    }()

    var body: some View {
        // Canonical square-cell idiom: an aspect-locked Color.clear base that IGNORES child
        // sizing drives the 1:1 frame; the image/gradient layers live in an overlay so their
        // .scaledToFill ideal height can't leak into layout (which was inflating cells and
        // overpainting the day header above each row).
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                ZStack {
                    PhotosColor.surfaceVariant(scheme)
                    if let placeholder {
                        Image(uiImage: placeholder)
                            .resizable()
                            .scaledToFill()
                            .blur(radius: 6)
                    }
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                            .transition(.opacity)
                    }
                    if item.isVideo {
                        VideoBadge()
                    }
                }
                .padding(selected ? 6 : 0)
                .clipShape(RoundedRectangle(cornerRadius: selected ? 8 : 0))
            }
            .clipped()
            .contentShape(Rectangle())
            .onTapGesture { onTap() }
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.4).onEnded { _ in onLongPress() }
            )
            .accessibilityElement(children: .ignore)
            .accessibilityAddTraits(.isButton)
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityLabel(item.isVideo ? "Video, \(dateLabel)" : "Photo, \(dateLabel)")
            .accessibilityValue(selectionMode ? (selected ? "Selected" : "Not selected") : "")
            .accessibilityIdentifier("photo-cell")
            // Badge overlays AFTER the a11y collapse above so its identifier stays reachable.
            .overlay(alignment: .topLeading) {
                if selected { checkBadge }
            }
            .animation(.easeOut(duration: 0.15), value: selected)
            .task(id: item.fileId.description) {
                // Decode placeholder + load thumbnail off the render path; both keyed by fileId.
                loadPlaceholder()
                let loaded = await ThumbnailLoader.shared.image(for: item, maxDim: Self.gridMaxDim)
                withAnimation(.easeIn(duration: 0.2)) { image = loaded }
            }
    }

    private var checkBadge: some View {
        Image(systemName: "checkmark.circle.fill")
            .font(.system(size: 20, weight: .semibold))
            .symbolRenderingMode(.palette)
            .foregroundStyle(PhotosColor.onPrimary(scheme), PhotosColor.primary(scheme))
            .padding(PhotosMetrics.space8)
            .allowsHitTesting(false)
            .accessibilityIdentifier("photo-cell-check")
    }

    private var dateLabel: String {
        let date = Date(timeIntervalSince1970: Double(item.userDate) / 1000.0)
        return Self.dateLabelFormatter.string(from: date)
    }

    /// Decode the inline base64 webp placeholder (cached by fileId). No-op if absent/undecodable
    /// — the cell falls back to the flat neutral fill.
    private func loadPlaceholder() {
        guard placeholder == nil else { return }
        let key = item.fileId.description as NSString
        if let cached = Self.placeholderCache.object(forKey: key) {
            placeholder = cached
            return
        }
        guard let base64 = item.previewPlaceholder, !base64.isEmpty else { return }
        // Tolerate an optional data-URL prefix ("data:image/webp;base64,").
        let payload = base64.contains(",") ? String(base64.split(separator: ",").last ?? "") : base64
        guard let data = Data(base64Encoded: payload, options: .ignoreUnknownCharacters),
              let img = UIImage(data: data) else { return }
        Self.placeholderCache.setObject(img, forKey: key)
        placeholder = img
    }
}

/// Small play badge top-right over a faint top-corner scrim (Google Photos placement), drawn
/// with over-photo `onOverlay` tokens so it stays legible on any thumbnail.
struct VideoBadge: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack {
            HStack {
                Spacer()
                Image(systemName: "play.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(PhotosColor.onOverlay(scheme))
                    .padding(PhotosMetrics.space4)
            }
            .background(
                LinearGradient(
                    colors: [PhotosColor.overlayChrome(scheme), .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            Spacer()
        }
        .allowsHitTesting(false)
    }
}
