import SwiftUI
import Shared

/// App root. Session-gated: `RootView` swaps between splash/login/timeline on the shared auth
/// state. UI-test seam: `-uiTestTimeline` bypasses the gate so timeline tests run on a fresh
/// (logged-out) simulator install.
struct ContentView: View {
    var body: some View {
        if ProcessInfo.processInfo.arguments.contains("-uiTestTimeline") {
            TimelineView()   // UI-test seam: timeline tests bypass the auth gate
        } else {
            RootView()
        }
    }
}
