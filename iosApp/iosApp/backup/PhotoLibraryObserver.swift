import Foundation
import Photos

/// Watches the photo library and pulls the next auto-backup pass forward (+60s instead of the
/// ~6h floor) after new media lands, debounced so a burst of saves re-arms once.
/// ponytail: fires only while app alive; BGTask does the work — Android-MediaWatch parity, not
/// instant upload.
final class PhotoLibraryObserver: NSObject, PHPhotoLibraryChangeObserver {
    static let shared = PhotoLibraryObserver()

    private var debounce: Task<Void, Never>?

    /// Install from `iOSApp.init()` after `BackgroundBackupTrigger.register()`. Gated on access
    /// already granted — registering unauthorized would surface the permission prompt at launch.
    static func installIfAuthorized() {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard status == .authorized || status == .limited else { return }
        PHPhotoLibrary.shared().register(shared)
    }

    func photoLibraryDidChange(_ changeInstance: PHChange) {
        debounce?.cancel()
        debounce = Task {
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            BackgroundBackupTrigger.schedule(after: 60)
        }
    }
}
