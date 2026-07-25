import SwiftUI
import Shared

/// The app's single navigation authority. Owned by `HomeTabView` and injected into every tab as
/// an `@EnvironmentObject`, so pushes and the fullscreen viewer flow through one place instead of
/// each screen minting its own stack + presentation booleans.
///
/// `path` backs the Collections push stack (the only push destination today); the viewer stays a
/// `fullScreenCover` but is driven by `viewer` state and presented once at the shell.
@MainActor
final class Router: ObservableObject {
    /// The selected shell tab. Bound to `HomeTabView`'s `TabView(selection:)` so a cross-tab
    /// jump (Create → the new album in Collections) is one call instead of per-tab state.
    @Published var selectedTab: HomeTab = .photos
    /// The Collections back-stack. Bound to that tab's `NavigationStack(path:)`; edge-swipe pop
    /// and the stock chevron drive it as usual.
    @Published var path = NavigationPath()
    /// Fullscreen viewer presentation, nil when closed. Set by any screen that opens a photo.
    @Published var viewer: ViewerPresentation?

    func push(_ route: Route) { path.append(route) }

    func popToRoot() { path = NavigationPath() }

    /// Show `album`'s detail from anywhere (C3: created albums open straight away) — switch to
    /// the tab that owns the push stack, then push onto a clean stack.
    func openAlbum(_ album: AlbumItem) {
        selectedTab = .collections
        path = NavigationPath()
        path.append(Route.albumDetail(album))
    }

    func openViewer(items: [PhotoItem], initialIndex: Int) {
        viewer = ViewerPresentation(items: items, initialIndex: initialIndex)
    }

    func closeViewer() { viewer = nil }
}

/// The shell's tabs, as `TabView` selection tags.
enum HomeTab: Hashable {
    case photos, collections, create, search
}

/// Pushable destinations. Grows as later screens (search results, settings) land.
enum Route: Hashable {
    case albumDetail(AlbumItem)
}

/// The data a fullscreen viewer needs, carried through router state instead of per-view booleans.
struct ViewerPresentation: Identifiable {
    let id = UUID()
    let items: [PhotoItem]
    let initialIndex: Int
}
