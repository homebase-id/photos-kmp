import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        IosBootstrapKt.initializeApp()
        // Background backup: register the BGTask handler before launch completes, then arm a pass.
        BackgroundBackupTrigger.register()
        BackgroundBackupTrigger.schedule()
        // New-media nudge: re-arms the pass sooner when the library changes (no-op unless Photos
        // access was already granted).
        PhotoLibraryObserver.installIfAuthorized()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
