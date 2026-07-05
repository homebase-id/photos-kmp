import SwiftUI
import Shared

/// App root. Session-gated: `RootView` swaps between splash/login/home on the shared auth
/// state. UI-test seam: `-uiTestTimeline` bypasses the gate so timeline/tab tests run on a
/// fresh (logged-out) simulator install.
struct ContentView: View {
    var body: some View {
        if ProcessInfo.processInfo.arguments.contains("-uiTestTimeline") {
            HomeTabView()   // UI-test seam: tab-hosted timeline, bypassing the auth gate
        } else {
            RootView()
        }
    }
}
