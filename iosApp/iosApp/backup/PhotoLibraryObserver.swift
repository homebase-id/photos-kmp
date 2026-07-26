import Foundation
import Photos

/// Watches the photo library and pulls the next auto-backup pass forward (+60s instead of the
/// ~6h floor) after new media lands, debounced so a burst of saves re-arms once.
/// ponytail: fires only while app alive; BGTask does the work — Android-MediaWatch parity, not
/// instant upload.
final class PhotoLibraryObserver: NSObject, PHPhotoLibraryChangeObserver {
    static let shared = PhotoLibraryObserver()

    private static var installed = false
    private var debounce: Task<Void, Never>?

    /// Install from `iOSApp.init()` after `BackgroundBackupTrigger.register()`, and again from
    /// `BackupModel.onToggle` once access is first granted mid-session. Gated on access already
    /// granted — registering unauthorized would surface the permission prompt at launch. All
    /// mutable state (installed flag, debounce) is confined to the main queue.
    static func installIfAuthorized() {
        DispatchQueue.main.async {
            let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            guard !installed, status == .authorized || status == .limited else { return }
            installed = true
            PHPhotoLibrary.shared().register(shared)
        }
    }

    func photoLibraryDidChange(_ changeInstance: PHChange) {
        // Photos delivers this on a background queue — hop to main before touching `debounce`.
        DispatchQueue.main.async { [self] in
            debounce?.cancel()
            debounce = Task {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled else { return }
                BackgroundBackupTrigger.schedule(after: 60)
            }
        }
    }
}
