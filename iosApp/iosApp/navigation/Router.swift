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
    /// The Collections back-stack. Bound to that tab's `NavigationStack(path:)`; edge-swipe pop
    /// and the stock chevron drive it as usual.
    @Published var path = NavigationPath()
    /// Fullscreen viewer presentation, nil when closed. Set by any screen that opens a photo.
    @Published var viewer: ViewerPresentation?

    func push(_ route: Route) { path.append(route) }

    func popToRoot() { path = NavigationPath() }

    func openViewer(items: [PhotoItem], initialIndex: Int) {
        viewer = ViewerPresentation(items: items, initialIndex: initialIndex)
    }

    func closeViewer() { viewer = nil }
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
