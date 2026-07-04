import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        IosBootstrapKt.initializeApp()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
