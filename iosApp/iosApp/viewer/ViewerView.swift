import SwiftUI
import UIKit
import Shared

/// Fullscreen photo viewer — a page-style pager over the dark viewer scrim (design §5.3).
/// Each page loads progressively: the already-cached 300-max-dim grid thumbnail paints
/// frame-0 (no black flash), then the sharp 1200-max-dim tier crossfades in. A single tap
/// toggles chrome (close + date); chrome auto-hides after 3s. A downward drag past the
/// threshold dismisses. MVP scope — no zoom, no action bar, no original-payload streaming,
/// no video playback (T17).
struct ViewerView: View {
    @Environment(\.colorScheme) private var scheme

    let items: [PhotoItem]
    let onDismiss: () -> Void

    /// Pre-zipped (Int tag, item) rows — a wrapper because Swift key paths can't index a tuple,
    /// so `ForEach` needs a nominal Identifiable to key pages by the stable `fileId`.
    private let entries: [IndexedItem]

    @State private var index: Int
    @State private var chromeVisible = true
    @State private var dragOffset: CGFloat = 0

    init(items: [PhotoItem], initialIndex: Int, onDismiss: @escaping () -> Void) {
        self.items = items
        self.onDismiss = onDismiss
        self.entries = items.enumerated().map { IndexedItem(tag: $0.offset, item: $0.element) }
        _index = State(initialValue: max(0, min(initialIndex, items.count - 1)))
    }

    /// Downward drag past this point dismisses (design §5.3).
    private static let dismissThreshold: CGFloat = 120

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.timeZone = TimeZone(identifier: "UTC")!
        f.dateFormat = "MMM d, yyyy"
        return f
    }()

    var body: some View {
        ZStack {
            PhotosColor.scrim(scheme).ignoresSafeArea()

            TabView(selection: $index) {
                ForEach(entries) { entry in
                    ViewerPage(item: entry.item)
                        .tag(entry.tag)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            .offset(y: dragOffset)

            if chromeVisible {
                topChrome.transition(.opacity)
            }
        }
        .accessibilityIdentifier("viewer-root")
        .statusBarHidden(!chromeVisible)
        .contentShape(Rectangle())
        .onTapGesture { toggleChrome() }
        // Simultaneous (not high-priority) so the pager keeps its horizontal swipe; the vertical
        // guard below keeps this from offsetting during a page change.
        .simultaneousGesture(dismissDrag)
        .task(id: chromeGuardKey) { await autoHideChrome() }
    }

    // MARK: - Chrome

    private var topChrome: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topLeading) {
                // Only the button below is interactive; the gradient must not eat taps/swipes.
                LinearGradient(
                    colors: [PhotosColor.overlayChrome(scheme), .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(maxWidth: .infinity)
                .frame(height: 96)
                .ignoresSafeArea(edges: .top)
                .allowsHitTesting(false)

                HStack(spacing: PhotosMetrics.space8) {
                    Button(action: onDismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(PhotosColor.onOverlay(scheme))
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("Close")
                    .accessibilityIdentifier("viewer-close")

                    if let dateText {
                        Text(dateText)
                            .font(PhotosFont.captionOverlay)
                            .foregroundColor(PhotosColor.onOverlay(scheme))
                    }
                    Spacer()
                }
                .padding(.horizontal, PhotosMetrics.space8)
            }
            Spacer()
        }
    }

    private var dateText: String? {
        guard items.indices.contains(index) else { return nil }
        let date = Date(timeIntervalSince1970: Double(items[index].userDate) / 1000.0)
        return Self.dateFormatter.string(from: date)
    }

    private func toggleChrome() {
        withAnimation(.easeInOut(duration: 0.2)) { chromeVisible.toggle() }
    }

    /// Restarts on every chrome-show or page change (id changes); hides after 3s of no interaction.
    private var chromeGuardKey: String { "\(chromeVisible)-\(index)" }

    @MainActor
    private func autoHideChrome() async {
        guard chromeVisible else { return }
        try? await Task.sleep(nanoseconds: 3_000_000_000)
        guard !Task.isCancelled, chromeVisible else { return }
        withAnimation(.easeInOut(duration: 0.2)) { chromeVisible = false }
    }

    // MARK: - Dismiss drag

    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                let h = value.translation.height
                // Vertical-dominant downward drag only — leave horizontal paging to the TabView.
                if h > 0 && h > abs(value.translation.width) {
                    dragOffset = h
                }
            }
            .onEnded { value in
                let h = value.translation.height
                if h > Self.dismissThreshold && h > abs(value.translation.width) {
                    onDismiss()
                } else {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { dragOffset = 0 }
                }
            }
    }
}

/// A pager row: the item plus its Int position (the TabView selection tag). Identity is the
/// stable `fileId` so paging doesn't rebuild pages when the underlying list re-emits.
private struct IndexedItem: Identifiable {
    let tag: Int
    let item: PhotoItem
    var id: String { item.fileId.description }
}

/// One pager page: a progressively-loaded photo (or video poster) centered over a darkened
/// ambient backdrop (deterministic gradient + blurred inline placeholder) so a cold page never
/// flashes black. The gradient key matches the grid cell's, giving frame-continuity from the tap.
private struct ViewerPage: View {
    @Environment(\.colorScheme) private var scheme
    let item: PhotoItem

    @State private var frame0: UIImage?
    @State private var hiRes: UIImage?
    @State private var placeholder: UIImage?

    /// The grid already cached this tier for visible cells → an instant frame-0.
    private static let frame0MaxDim = 300
    /// The hi-res tier for phone screens (spec §5.3; original + zoom is a follow-up).
    private static let hiResMaxDim = 1200

    /// Decoded inline placeholders keyed by fileId — decode base64 once, never in `body`.
    private static let placeholderCache = NSCache<NSString, UIImage>()

    /// 6 earthy 2-stop gradients (parity with the grid cell), picked by fileId hash.
    private static let gradientPairs: [(UInt32, UInt32)] = [
        (0xD5E0C7, 0x8FA382),
        (0xE3E2CE, 0xB9B6A6),
        (0xEAE6DB, 0xC9C2AE),
        (0xDCE5D2, 0x9AA08C),
        (0xE7E3D7, 0xAFA893),
        (0xDFE6D8, 0x7E806C),
    ]

    var body: some View {
        ZStack {
            ambientBackdrop
            if let frame0 {
                Image(uiImage: frame0).resizable().scaledToFit()
            }
            if let hiRes {
                Image(uiImage: hiRes).resizable().scaledToFit().transition(.opacity)
            }
            if item.isVideo {
                // video playback: T17
                Image(systemName: "play.fill")
                    .font(.system(size: 48))
                    .foregroundColor(PhotosColor.onOverlay(scheme))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: item.fileId.description) { await load() }
    }

    /// Darkened ambient behind the letterboxed photo — canonical `Color.clear`.overlay.clipped
    /// idiom so the blurred placeholder's `.scaledToFill` ideal size can't leak into layout.
    private var ambientBackdrop: some View {
        Color.clear
            .overlay {
                ZStack {
                    gradient
                    if let placeholder {
                        Image(uiImage: placeholder)
                            .resizable()
                            .scaledToFill()
                            .blur(radius: 40)
                    }
                    PhotosColor.scrim(scheme).opacity(0.4)
                }
            }
            .clipped()
    }

    private var gradient: LinearGradient {
        // Fold instead of abs() to avoid the abs(Int.min) trap; stable per-run bucket.
        let raw = item.fileId.description.hashValue
        let idx = ((raw % Self.gradientPairs.count) + Self.gradientPairs.count) % Self.gradientPairs.count
        let pair = Self.gradientPairs[idx]
        return LinearGradient(
            colors: [Color(hex: pair.0), Color(hex: pair.1)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    /// Two-tier progressive load — kick both tiers concurrently; frame-0 lands first (cache hit
    /// for cells that were on screen), then the hi-res tier crossfades over it.
    @MainActor
    private func load() async {
        loadPlaceholder()
        async let low = ThumbnailLoader.shared.image(for: item, maxDim: Self.frame0MaxDim)
        async let high = ThumbnailLoader.shared.image(for: item, maxDim: Self.hiResMaxDim)
        if let low0 = await low, hiRes == nil {
            frame0 = low0
        }
        if let high0 = await high {
            withAnimation(.easeIn(duration: 0.2)) { hiRes = high0 }
        }
    }

    /// Decode the inline base64 webp placeholder (cached by fileId); no-op if absent/undecodable.
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
