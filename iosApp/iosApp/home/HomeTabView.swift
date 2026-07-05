import SwiftUI

/// Two-tab home: Photos (timeline) and Collections. Hidden tab bar during selection is handled
/// inside TimelineView via .toolbar(.hidden, for: .tabBar).
struct HomeTabView: View {
    var body: some View {
        TabView {
            TimelineView()
                .tabItem { Label("Photos", systemImage: "photo.on.rectangle") }
                .accessibilityIdentifier("tab-photos")
            CollectionsView()
                .tabItem { Label("Collections", systemImage: "rectangle.stack") }
                .accessibilityIdentifier("tab-collections")
        }
        .accessibilityIdentifier("bottom-nav")
    }
}
