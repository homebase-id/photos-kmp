import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        IosBootstrapKt.initializeApp()
        // Background backup: register the BGTask handler before launch completes, then arm a pass.
        BackgroundBackupTrigger.register()
        BackgroundBackupTrigger.schedule()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
