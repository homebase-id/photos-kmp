import SwiftUI

/// The app shell: a native four-tab TabView (Photos · Collections · Create · Search) over the
/// shared `Router`. The router is owned here and injected into every tab, so pushes and the
/// fullscreen viewer resolve at one place. Timeline still hides the tab bar during selection via
/// its own `.toolbar(.hidden, for: .tabBar)`.
struct HomeTabView: View {
    @StateObject private var router = Router()

    var body: some View {
        TabView {
            TimelineView()
                .tabItem { Label("Photos", systemImage: "photo.on.rectangle") }
                .accessibilityIdentifier("tab-photos")
            CollectionsView()
                .tabItem { Label("Collections", systemImage: "rectangle.stack") }
                .accessibilityIdentifier("tab-collections")
            CreateView()
                .tabItem { Label("Create", systemImage: "plus.circle") }
                .accessibilityIdentifier("tab-create")
            SearchView()
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .accessibilityIdentifier("tab-search")
        }
        .environmentObject(router)
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
