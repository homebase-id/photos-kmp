import SwiftUI
import Shared

/// Search (Batch E): metadata search over date range, type, album, and free-text matching
/// album names — no filename search, no ML (see the plan's scope rulings). Read-only results,
/// so this screen only renders state and forwards taps to the shared viewer, the same way
/// Favorites/Archive do. `.searchable` + `.searchScopes` drive the query/type; the chip row
/// below duplicates Date/Type/Album as explicit affordances (Android carries the same three
/// chips, kept for platform parity and the shared a11y-id contract).
struct SearchView: View {
    @Environment(\.colorScheme) private var scheme
    @EnvironmentObject private var router: Router
    @StateObject private var model = SearchModel()

    @State private var showDateSheet = false
    @State private var showAlbumSheet = false

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                let columns = PhotosMetrics.timelineColumns(forWidth: geo.size.width)
                VStack(spacing: 0) {
                    chipsRow
                    content(columns: columns)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(PhotosColor.background(scheme).ignoresSafeArea())
                .overlay(alignment: .bottom) { toastView }
            }
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
        }
        .searchable(text: queryBinding, prompt: "Search photos")
        .searchScopes(scopeBinding) {
            Text("All").tag(TypeFilter.all)
            Text("Photos").tag(TypeFilter.photos)
            Text("Videos").tag(TypeFilter.videos)
        }
        .onSubmit(of: .search) {
            Task { await model.submit() }
        }
        .tint(PhotosColor.primary(scheme))
        .task { model.start() }
        .sheet(isPresented: $showDateSheet) {
            DateRangeSheet(
                initialFrom: dateFrom,
                initialTo: dateTo,
                onApply: { from, to in model.setDateRange(from: from, to: to) },
                onClear: { model.setDateRange(from: nil, to: nil) }
            )
        }
        .sheet(isPresented: $showAlbumSheet) {
            AlbumFilterSheet(selected: model.uiState?.albumFilter) { model.setAlbumFilter($0) }
        }
    }

    // MARK: - Error toast

    /// Surfaces a search failure that still has stale results on screen (a re-search that fails
    /// while old results are showing) — the empty-state branch in `content()` already shows
    /// `error` when there's nothing else to show, so this only fires for the non-empty case.
    @ViewBuilder
    private var toastView: some View {
        if let message = model.toastMessage {
            ToastCapsule(message: message, a11yId: "search-error-banner")
                .padding(.bottom, PhotosMetrics.space24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    // MARK: - Bindings

    private var queryBinding: Binding<String> {
        Binding(get: { model.uiState?.query ?? "" }, set: { model.onQueryChange($0) })
    }

    private var scopeBinding: Binding<TypeFilter> {
        Binding(get: { model.uiState?.typeFilter ?? .all }, set: { model.setTypeFilter($0) })
    }

    private var dateFrom: Date? {
        model.uiState?.fromUserDate.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) }
    }

    private var dateTo: Date? {
        model.uiState?.toUserDate.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) }
    }

    // MARK: - Chips

    private var chipsRow: some View {
        HStack(spacing: PhotosMetrics.space8) {
            FilterChip(label: "Date", isActive: model.uiState?.fromUserDate != nil || model.uiState?.toUserDate != nil) {
                showDateSheet = true
            }
            .accessibilityIdentifier("search-chip-date")

            FilterChip(label: typeChipLabel, isActive: (model.uiState?.typeFilter ?? .all) != .all) {
                cycleTypeFilter()
            }
            .accessibilityIdentifier("search-chip-type")

            FilterChip(label: model.uiState?.albumFilter?.name ?? "Album", isActive: model.uiState?.albumFilter != nil) {
                showAlbumSheet = true
            }
            .accessibilityIdentifier("search-chip-album")

            Spacer()

            if hasAnyFilter {
                Button("Clear") { model.clearFilters() }
                    .font(PhotosFont.label)
                    .foregroundColor(PhotosColor.primary(scheme))
                    .accessibilityIdentifier("search-clear")
            }
        }
        .padding(.horizontal, PhotosMetrics.space16)
        .padding(.vertical, PhotosMetrics.space8)
    }

    private var hasAnyFilter: Bool {
        guard let s = model.uiState else { return false }
        return s.fromUserDate != nil || s.toUserDate != nil || s.typeFilter != .all || s.albumFilter != nil
    }

    private var typeChipLabel: String {
        switch model.uiState?.typeFilter ?? .all {
        case .all: return "Type"
        case .photos: return "Photos"
        case .videos: return "Videos"
        }
    }

    private func cycleTypeFilter() {
        let next: TypeFilter
        switch model.uiState?.typeFilter ?? .all {
        case .all: next = .photos
        case .photos: next = .videos
        case .videos: next = .all
        }
        model.setTypeFilter(next)
    }

    // MARK: - Content state machine

    @ViewBuilder
    private func content(columns: Int) -> some View {
        if let state = model.uiState {
            if state.isSearching && state.sections.isEmpty {
                SkeletonGrid(columns: columns, identifier: "search-skeleton")
            } else if let error = state.error, state.sections.isEmpty {
                // Full-screen failure — distinct id from the plain no-results empty state below,
                // matching Android's separate `search-error`/`search-empty` test tags.
                EmptyStateView(title: "Search failed", message: error, identifier: "search-error")
            } else if !state.hasSearched && !state.isSearching && state.sections.isEmpty {
                // Covers true idle AND mid-composition (query/filters edited but not yet
                // submitted) — `isIdle` alone flips false on the first keystroke, which used to
                // fall through to an unlabeled blank results grid until the next submit.
                recentsView(recent: state.recent)
            } else if state.isEmpty {
                EmptyStateView(
                    title: "No results",
                    message: "Try a different date range, type, or album.",
                    identifier: "search-empty"
                )
            } else {
                // A filter/submit re-search over already-populated results keeps the grid on
                // screen with a thin progress affordance instead of dropping to a full skeleton
                // (mirrors Android's LinearProgressIndicator over `state.sections.isNotEmpty()`).
                VStack(spacing: 0) {
                    if state.isSearching {
                        ProgressView()
                            .progressViewStyle(.linear)
                            .accessibilityIdentifier("search-progress")
                    }
                    resultsGrid(columns: columns)
                }
            }
        } else {
            SkeletonGrid(columns: columns, identifier: "search-skeleton")
        }
    }

    // MARK: - Recents

    @ViewBuilder
    private func recentsView(recent: [String]) -> some View {
        if recent.isEmpty {
            VStack(spacing: PhotosMetrics.space12) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 40))
                    .foregroundColor(PhotosColor.onSurfaceVariantDim(scheme))
                Text("Search your photos")
                    .font(PhotosFont.body)
                    .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                Text("Filter by date, type, or album — or type to match an album name.")
                    .font(PhotosFont.bodyMedium)
                    .foregroundColor(PhotosColor.onSurfaceVariantDim(scheme))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, PhotosMetrics.space32)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("search-recent")
        } else {
            List {
                Section("Recent searches") {
                    ForEach(recent, id: \.self) { query in
                        Button(action: { Task { await model.selectRecent(query) } }) {
                            Label(query, systemImage: "clock.arrow.circlepath")
                                .foregroundColor(PhotosColor.onSurface(scheme))
                        }
                    }
                }
                Button("Clear recent searches", role: .destructive) { model.clearRecent() }
            }
            .listStyle(.plain)
            .accessibilityIdentifier("search-recent")
        }
    }

    // MARK: - Results grid

    private func resultsGrid(columns: Int) -> some View {
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
                                    PhotoCell(
                                        item: item,
                                        onTap: { model.openViewer(for: item, in: router) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        .background(PhotosColor.gridGap(scheme))
        .accessibilityIdentifier("search-results-grid")
    }
}
