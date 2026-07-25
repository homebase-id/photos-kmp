import SwiftUI
import UIKit
import Shared

/// Fullscreen photo viewer — a page-style pager over the dark viewer scrim (design §5.3),
/// driven by the shared `ViewerViewModel` (Batch B). Each still page loads progressively
/// (cached 300 grid tier → sharp 1200 tier crossfade) and supports pinch/double-tap zoom;
/// video pages play through the shared decrypt-to-temp pipeline. A single tap toggles chrome
/// (top: close + date; bottom: Share · Delete · Info glass bar); chrome auto-hides after 3s
/// on stills. A downward drag past the threshold dismisses (disabled while zoomed).
struct ViewerView: View {
    @Environment(\.colorScheme) private var scheme

    @StateObject private var model: ViewerModel

    @State private var chromeVisible = true
    @State private var dragOffset: CGFloat = 0
    @State private var isZoomed = false
    @State private var showDeleteAlert = false
    @State private var showInfoSheet = false
    @State private var shareBundle: ShareBundle?

    init(items: [PhotoItem], initialIndex: Int, onDismiss: @escaping () -> Void) {
        _model = StateObject(
            wrappedValue: ViewerModel(items: items, initialIndex: initialIndex, onDismiss: onDismiss)
        )
    }

    /// Downward drag past this point dismisses (design §5.3).
    private static let dismissThreshold: CGFloat = 120

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.timeZone = TimeZone(identifier: "UTC")!
        f.dateFormat = "MMM d, yyyy"
        return f
    }()

    /// Pager selection bridged through the shared VM so `index` stays the single source of truth.
    private var selection: Binding<Int> {
        Binding(
            get: { model.index },
            set: { model.setIndex($0) }
        )
    }

    var body: some View {
        ZStack {
            PhotosColor.scrim(scheme).ignoresSafeArea()

            TabView(selection: selection) {
                ForEach(entries) { entry in
                    page(for: entry)
                        .tag(entry.tag)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            .scrollDisabled(isZoomed)
            .offset(y: dragOffset)

            if chromeVisible {
                topChrome.transition(.opacity)
            }
        }
        .overlay(alignment: .bottom) {
            if chromeVisible {
                bottomBar.transition(.opacity)
            }
        }
        .overlay(alignment: .bottom) { errorToast }
        .accessibilityIdentifier("viewer-root")
        .statusBarHidden(!chromeVisible)
        .contentShape(Rectangle())
        .onTapGesture { toggleChrome() }
        // Simultaneous (not high-priority) so the pager keeps its horizontal swipe; the vertical
        // guard below keeps this from offsetting during a page change.
        .simultaneousGesture(dismissDrag)
        .task { model.start() }
        .task(id: chromeGuardKey) { await autoHideChrome() }
        // Delete confirmation — same alert form + copy as the timeline (C5).
        .alert("Delete 1 item?", isPresented: $showDeleteAlert) {
            Button("Delete", role: .destructive) {
                Task { await model.deleteCurrent() }
            }
            .accessibilityIdentifier("delete-confirm")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("They'll be removed from your Homebase photo library.")
        }
        .sheet(isPresented: $showInfoSheet) {
            if let item = model.currentItem {
                ViewerInfoSheet(item: item)
            }
        }
        .sheet(item: $shareBundle) { bundle in
            ShareSheet(items: bundle.items)
                .presentationDetents([.medium, .large])
        }
    }

    private var entries: [IndexedItem] {
        model.items.enumerated().map { IndexedItem(tag: $0.offset, item: $0.element) }
    }

    @ViewBuilder
    private func page(for entry: IndexedItem) -> some View {
        let isCurrent = entry.tag == model.index
        if entry.item.isVideo {
            ViewerVideoPage(item: entry.item, isCurrent: isCurrent, model: model)
        } else {
            ViewerPage(item: entry.item, isCurrent: isCurrent) { zoomed in
                isZoomed = zoomed
                // Google-Photos parity: zooming in drops the chrome out of the way (also masks
                // the ancestor single-tap that can co-fire with the page's double-tap).
                if zoomed && chromeVisible {
                    withAnimation(.easeInOut(duration: 0.2)) { chromeVisible = false }
                }
            }
        }
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
                    Button(action: { model.requestClose() }) {
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

    /// Share · Delete · Info on one Liquid Glass capsule over the scrim.
    /// favorite/add-to-album deliberately absent — Batches D/C.
    private var bottomBar: some View {
        HStack(spacing: 0) {
            actionButton("square.and.arrow.up", label: "Share", id: "viewer-share") {
                Task { await share() }
            }
            actionButton("trash", label: "Delete", id: "viewer-delete") {
                showDeleteAlert = true
            }
            actionButton("info.circle", label: "Info", id: "viewer-info") {
                showInfoSheet = true
            }
        }
        .padding(.vertical, PhotosMetrics.space8)
        .padding(.horizontal, PhotosMetrics.space8)
        .frame(maxWidth: 360)
        .glassEffect(.regular, in: Capsule())
        .padding(.horizontal, PhotosMetrics.space24)
        .padding(.bottom, PhotosMetrics.space8)
        .disabled(model.isDeleting)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("viewer-actionbar")
    }

    private func actionButton(
        _ systemName: String,
        label: String,
        id: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: PhotosMetrics.space4) {
                Image(systemName: systemName)
                    .font(.system(size: 20, weight: .medium))
                Text(label)
                    .font(PhotosFont.caption)
            }
            .foregroundColor(PhotosColor.onOverlay(scheme))
            .frame(maxWidth: .infinity)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .accessibilityLabel(label)
        .accessibilityIdentifier(id)
    }

    @ViewBuilder
    private var errorToast: some View {
        if let message = model.errorMessage {
            ToastCapsule(message: message, a11yId: "viewer-toast")
                .padding(.bottom, 96)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private var dateText: String? {
        guard let item = model.currentItem else { return nil }
        let date = Date(timeIntervalSince1970: Double(item.userDate) / 1000.0)
        return Self.dateFormatter.string(from: date)
    }

    private func toggleChrome() {
        withAnimation(.easeInOut(duration: 0.2)) { chromeVisible.toggle() }
    }

    /// Restarts on every chrome-show or page change (id changes); hides after 3s of no interaction.
    private var chromeGuardKey: String { "\(chromeVisible)-\(model.index)" }

    @MainActor
    private func autoHideChrome() async {
        guard chromeVisible else { return }
        // Keep chrome up on video pages — AVKit's controls consume taps, so a hidden close
        // button there would be unrecoverable.
        guard model.currentItem?.isVideo != true else { return }
        try? await Task.sleep(nanoseconds: 3_000_000_000)
        guard !Task.isCancelled, chromeVisible else { return }
        withAnimation(.easeInOut(duration: 0.2)) { chromeVisible = false }
    }

    // MARK: - Share

    private func share() async {
        guard let item = model.currentItem else { return }
        guard let items = await model.shareItems(for: item) else { return }
        shareBundle = ShareBundle(items: items)
    }

    // MARK: - Dismiss drag

    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                guard !isZoomed else { return }
                let h = value.translation.height
                // Vertical-dominant downward drag only — leave horizontal paging to the TabView.
                if h > 0 && h > abs(value.translation.width) {
                    dragOffset = h
                }
            }
            .onEnded { value in
                guard !isZoomed else { return }
                let h = value.translation.height
                if h > Self.dismissThreshold && h > abs(value.translation.width) {
                    model.requestClose()
                } else {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { dragOffset = 0 }
                }
            }
    }
}

/// A share payload wrapped for `.sheet(item:)` presentation.
private struct ShareBundle: Identifiable {
    let id = UUID()
    let items: [Any]
}

/// A pager row: the item plus its Int position (the TabView selection tag). Identity is the
/// stable `fileId` so paging doesn't rebuild pages when the underlying list re-emits.
private struct IndexedItem: Identifiable {
    let tag: Int
    let item: PhotoItem
    var id: String { item.fileId.description }
}

/// One still page: a progressively-loaded photo centered over a darkened ambient backdrop
/// (neutral base + blurred inline placeholder) so a cold page never flashes pure black.
/// The image layer is zoomable (pinch/double-tap/pan); zoom resets when the page stops
/// being current and is reported up so the pager/dismiss gestures can be gated.
private struct ViewerPage: View {
    @Environment(\.colorScheme) private var scheme
    let item: PhotoItem
    let isCurrent: Bool
    let onZoomChanged: (Bool) -> Void

    @State private var frame0: UIImage?
    @State private var hiRes: UIImage?
    @State private var placeholder: UIImage?
    @State private var zoomed = false

    /// The grid already cached this tier for visible cells → an instant frame-0.
    private static let frame0MaxDim = 300
    /// The hi-res tier for phone screens (spec §5.3; original + zoom is a follow-up).
    private static let hiResMaxDim = 1200

    /// Decoded inline placeholders keyed by fileId — decode base64 once, never in `body`.
    private static let placeholderCache = NSCache<NSString, UIImage>()

    var body: some View {
        ZStack {
            ambientBackdrop
            imageLayer
                .zoomable(isZoomed: $zoomed)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .task(id: item.fileId.description) { await load() }
        .onChange(of: zoomed) { _, z in onZoomChanged(z) }
        .onChange(of: isCurrent) { _, current in
            if !current { zoomed = false }
        }
    }

    private var imageLayer: some View {
        ZStack {
            if let frame0 {
                Image(uiImage: frame0).resizable().scaledToFit()
            }
            if let hiRes {
                Image(uiImage: hiRes).resizable().scaledToFit().transition(.opacity)
            }
        }
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

    private var gradient: some View {
        // Neutral flat backdrop (GPhotos parity) — the blurred placeholder above carries the color.
        Color(hex: 0x1C1C1E)
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
