import SwiftUI

/// The app shell: a native four-tab TabView (Photos · Collections · Create · Search) over the
/// shared `Router`. The router is owned here and injected into every tab, so pushes and the
/// fullscreen viewer resolve at one place. Timeline still hides the tab bar during selection via
/// its own `.toolbar(.hidden, for: .tabBar)`.
struct HomeTabView: View {
    @Environment(\.colorScheme) private var scheme
    @StateObject private var router = Router()

    var body: some View {
        // Selection is router state (not TabView-local) so Create → "open the new album" can
        // switch to Collections and push in one call.
        TabView(selection: $router.selectedTab) {
            TimelineView()
                .tabItem { Label("Photos", systemImage: "photo.on.rectangle") }
                .accessibilityIdentifier("tab-photos")
                .tag(HomeTab.photos)
            CollectionsView()
                .tabItem { Label("Collections", systemImage: "rectangle.stack") }
                .accessibilityIdentifier("tab-collections")
                .tag(HomeTab.collections)
            CreateView()
                .tabItem { Label("Create", systemImage: "plus.circle") }
                .accessibilityIdentifier("tab-create")
                .tag(HomeTab.create)
            SearchView()
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .accessibilityIdentifier("tab-search")
                .tag(HomeTab.search)
        }
        .environmentObject(router)
        // Tab-bar chrome sits outside every screen's own tint — without this the selected tab
        // stayed system blue after the moss accent swap.
        .tint(PhotosColor.primary(scheme))
        .accessibilityIdentifier("bottom-nav")
        // Single viewer presentation for the whole app — any screen opens it through the router.
        .fullScreenCover(item: $router.viewer) { presentation in
            ViewerView(
                items: presentation.items,
                initialIndex: presentation.initialIndex,
                onDismiss: { router.closeViewer() }
            )
        }
    }
}
