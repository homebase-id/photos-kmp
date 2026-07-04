import SwiftUI
import UIKit
import Shared

/// The Timeline grid — the heart of the MVP. A `NavigationStack`-hosted, edge-to-edge grid
/// of square-cropped thumbnails, grouped into months (sticky frosted headers) and day
/// sub-headers, rendered over the warm `background` with the `gridGap` mat showing through a
/// hairline 1.5pt gap.
///
/// State comes from `TimelineModel` (a `@StateObject` owning ONE shared `TimelineViewModel`
/// across struct re-inits). Views render; the VM owns logic.
struct TimelineView: View {
    @Environment(\.colorScheme) private var scheme
    @StateObject private var model = TimelineModel()
    @State private var showLogoutDialog = false

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                let columns = PhotosMetrics.timelineColumns(forWidth: geo.size.width)
                // Warm background is attached as a `.background` modifier (edge-to-edge, no white
                // strips) rather than a full-bleed ZStack sibling layer. It draws BEHIND the
                // ScrollView so it never covers the nav large-title layer, and keeps this a
                // concrete drawn container so the a11y root isn't collapsed out of the AX tree.
                content(columns: columns)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(PhotosColor.background(scheme).ignoresSafeArea())
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("timeline-root")
                    .onAppear { model.setColumns(columns) }
                    .onChange(of: columns) { _, newColumns in model.setColumns(newColumns) }
            }
            .navigationTitle("Photos")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: { showLogoutDialog = true }) {
                        Image(systemName: "person.crop.circle")
                            .font(.system(size: 28))
                            .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                    }
                    .accessibilityLabel("Account")
                    .accessibilityIdentifier("account-button")
                }
            }
            // Only set the bar's scrolled (standard) appearance color — do NOT force
            // `.visible`. Forcing visibility made iOS 26 render the opaque bar material across
            // the expanded large-title band, painting over the "Photos" large title (toolbar
            // items draw above it, so the account button stayed visible). Letting the default
            // transparent scroll-edge appearance stand lets the large title paint; the warm bar
            // still fills in once the grid scrolls under it.
            .toolbarBackground(PhotosColor.surface(scheme), for: .navigationBar)
        }
        .tint(PhotosColor.primary(scheme))
        // Log-out confirmation. Confirm → the shared suspend logout() (SKIE-bridged to async); the
        // RootModel gate observes authState and swaps back to the login screen on the flip.
        .confirmationDialog(
            "Log out?",
            isPresented: $showLogoutDialog,
            titleVisibility: .visible
        ) {
            Button("Log out", role: .destructive) {
                Task { try? await PhotosModuleKt.youAuthFlowManager().logout() }
            }
            .accessibilityIdentifier("logout-confirm")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You'll need to sign in again to see your photos.")
        }
        .task { model.start() }
        .fullScreenCover(
            isPresented: Binding(
                get: { model.viewerIndex != nil },
                set: { presented in if !presented { model.viewerIndex = nil } }
            )
        ) {
            ViewerView(
                items: model.uiState?.pagedItems ?? [],
                initialIndex: model.viewerIndex ?? 0,
                onDismiss: { model.viewerIndex = nil }
            )
        }
    }

    // MARK: - State branching

    @ViewBuilder
    private func content(columns: Int) -> some View {
        let state = model.uiState
        let sections = state?.sections ?? []
        if state == nil || (state!.isLoading && sections.isEmpty) {
            skeleton(columns: columns)
        } else if sections.isEmpty, let message = state?.error ?? model.loadError {
            errorState(message: message)
        } else if sections.isEmpty {
            emptyState
        } else {
            grid(columns: columns)
        }
    }

    // MARK: - Skeleton

    private func skeleton(columns: Int) -> some View {
        let gap = PhotosMetrics.gridGapWidth
        let gridColumns = Array(
            repeating: GridItem(.flexible(), spacing: gap, alignment: .center),
            count: max(1, columns)
        )
        return ScrollView {
            LazyVGrid(columns: gridColumns, spacing: gap) {
                ForEach(0..<(max(1, columns) * 12), id: \.self) { _ in
                    Rectangle()
                        .fill(PhotosColor.gridGap(scheme))
                        .aspectRatio(1, contentMode: .fill)
                        .clipped()
                }
            }
        }
        .background(PhotosColor.gridGap(scheme))
        .allowsHitTesting(false)
        .accessibilityIdentifier("timeline-skeleton")
    }

    // MARK: - Error / empty

    private func errorState(message: String) -> some View {
        VStack(spacing: PhotosMetrics.space12) {
            Text("Couldn't load photos")
                .font(PhotosFont.display)
                .foregroundColor(PhotosColor.onBackground(scheme))
            Text(message)
                .font(PhotosFont.bodyMedium)
                .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                .multilineTextAlignment(.center)
            Button {
                model.vm.refresh()
            } label: {
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
        .accessibilityIdentifier("timeline-error")
    }

    private var emptyState: some View {
        VStack(spacing: PhotosMetrics.space12) {
            Text("No photos yet")
                .font(PhotosFont.display)
                .foregroundColor(PhotosColor.onBackground(scheme))
            Text("Back up your camera roll to see it here.")
                .font(PhotosFont.bodyMedium)
                .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                .multilineTextAlignment(.center)
        }
        .padding(PhotosMetrics.screenEdge)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("timeline-empty")
    }

    // MARK: - Grid

    private func grid(columns: Int) -> some View {
        let gap = PhotosMetrics.gridGapWidth
        let gridColumns = Array(
            repeating: GridItem(.flexible(), spacing: gap, alignment: .center),
            count: max(1, columns)
        )
        return ScrollView {
            LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                ForEach(model.monthSections) { month in
                    Section(header: MonthHeader(title: month.title)) {
                        ForEach(month.days) { day in
                            DayHeader(title: day.title)
                            LazyVGrid(columns: gridColumns, spacing: gap) {
                                ForEach(day.items, id: \.fileId.description) { item in
                                    PhotoCell(item: item, onTap: { model.showViewer(for: item) })
                                        .onAppear { maybePaginate(item: item) }
                                }
                            }
                        }
                    }
                }
                if model.uiState?.isPaginating == true {
                    paginationFooter
                }
            }
        }
        // Edge-to-edge: the grid mat is the gridGap showing through the hairline cell spacing.
        .background(PhotosColor.gridGap(scheme))
        .refreshable { try? await model.vm.refreshAndWait() }
        .overlay(alignment: .bottom) { toastView }
        .accessibilityIdentifier("timeline-grid")
    }

    private var paginationFooter: some View {
        HStack {
            Spacer()
            ProgressView().tint(PhotosColor.primary(scheme))
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .frame(height: PhotosMetrics.space48)
        .accessibilityIdentifier("timeline-footer")
    }

    @ViewBuilder
    private var toastView: some View {
        if let message = model.toastMessage {
            Text(message)
                .font(PhotosFont.bodyMedium)
                .foregroundColor(PhotosColor.onSurface(scheme))
                .padding(.horizontal, PhotosMetrics.space16)
                .padding(.vertical, PhotosMetrics.space12)
                .background(PhotosColor.surface3(scheme))
                .clipShape(Capsule())
                .padding(.bottom, PhotosMetrics.space24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityIdentifier("timeline-toast")
        }
    }

    /// Prefetch-margin pagination: any cell in the tail set (last `columns * 4` items)
    /// triggers the next page, so we load ~4 rows before the true end instead of at the
    /// very last cell.
    private func maybePaginate(item: PhotoItem) {
        guard let state = model.uiState, !state.endReached, !state.isPaginating else { return }
        if model.prefetchIds.contains(item.fileId.description) {
            model.vm.loadMore()
        }
    }
}

/// Sticky full-width month header. Real frosted blur (`.ultraThinMaterial` + a faint
/// surface tint) so photos smear softly under it on scroll — the one place type carries structure.
private struct MonthHeader: View {
    @Environment(\.colorScheme) private var scheme
    let title: String

    var body: some View {
        Text(title)
            .font(PhotosFont.monthHeader)
            .foregroundColor(PhotosColor.onSurface(scheme))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, PhotosMetrics.space16)
            .padding(.vertical, PhotosMetrics.space12)
            .background {
                ZStack {
                    Rectangle().fill(.ultraThinMaterial)
                    PhotosColor.surface(scheme).opacity(0.6)
                }
            }
            .accessibilityIdentifier("timeline-month-header")
    }
}

/// Plain full-width day sub-header inside a month. Quiet `onSurfaceVariant` label over the
/// screen background so it reads as a break in the grid mat.
private struct DayHeader: View {
    @Environment(\.colorScheme) private var scheme
    let title: String

    var body: some View {
        Text(title)
            .font(PhotosFont.dateSubhead)
            .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, PhotosMetrics.space16)
            .padding(.top, PhotosMetrics.space8)
            .padding(.bottom, PhotosMetrics.space4)
            .background(PhotosColor.background(scheme))
            .accessibilityIdentifier("timeline-day-header")
    }
}

/// A single square grid cell: deterministic earthy gradient → blurred inline placeholder →
/// crossfaded thumbnail, with a play badge for videos. No GeometryReader — `aspectRatio(1,
/// .fill) + .clipped()` inside the grid column does the square sizing.
private struct PhotoCell: View {
    @Environment(\.colorScheme) private var scheme
    let item: PhotoItem
    var onTap: () -> Void = {}

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

    /// 6 earthy 2-stop gradients (parity with Android plan 002 Step 5), picked by fileId hash.
    private static let gradientPairs: [(UInt32, UInt32)] = [
        (0xD5E0C7, 0x8FA382),
        (0xE3E2CE, 0xB9B6A6),
        (0xEAE6DB, 0xC9C2AE),
        (0xDCE5D2, 0x9AA08C),
        (0xE7E3D7, 0xAFA893),
        (0xDFE6D8, 0x7E806C),
    ]

    var body: some View {
        // Canonical square-cell idiom: an aspect-locked Color.clear base that IGNORES child
        // sizing drives the 1:1 frame; the image/gradient layers live in an overlay so their
        // .scaledToFill ideal height can't leak into layout (which was inflating cells and
        // overpainting the day header above each row).
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                ZStack {
                    fallbackGradient
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
            }
            .clipped()
            .contentShape(Rectangle())
        .onTapGesture { onTap() }
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(item.isVideo ? "Video, \(dateLabel)" : "Photo, \(dateLabel)")
        .accessibilityIdentifier("photo-cell")
        .task(id: item.fileId.description) {
            // Decode placeholder + load thumbnail off the render path; both keyed by fileId.
            loadPlaceholder()
            let loaded = await ThumbnailLoader.shared.image(for: item, maxDim: Self.gridMaxDim)
            withAnimation(.easeIn(duration: 0.2)) { image = loaded }
        }
    }

    private var dateLabel: String {
        let date = Date(timeIntervalSince1970: Double(item.userDate) / 1000.0)
        return Self.dateLabelFormatter.string(from: date)
    }

    private var fallbackGradient: LinearGradient {
        // Fold instead of abs() to avoid the abs(Int.min) trap; still a stable per-run bucket.
        let raw = item.fileId.description.hashValue
        let idx = ((raw % Self.gradientPairs.count) + Self.gradientPairs.count) % Self.gradientPairs.count
        let pair = Self.gradientPairs[idx]
        return LinearGradient(
            colors: [Color(hex: pair.0), Color(hex: pair.1)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    /// Decode the inline base64 webp placeholder (cached by fileId). No-op if absent/undecodable
    /// — the cell falls back to the gradient.
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

/// Small play badge bottom-right over a faint bottom gradient, drawn with over-photo
/// `onOverlay` tokens so it stays legible on any thumbnail.
private struct VideoBadge: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                Image(systemName: "play.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(PhotosColor.onOverlay(scheme))
                    .padding(PhotosMetrics.space4)
            }
            .background(
                LinearGradient(
                    colors: [.clear, PhotosColor.overlayChrome(scheme)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        }
        .allowsHitTesting(false)
    }
}
