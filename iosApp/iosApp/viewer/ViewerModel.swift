import SwiftUI
import UIKit
import Foundation
import Shared

/// Owns the shared `ViewerViewModel` across SwiftUI struct re-inits (same `@StateObject` +
/// SKIE `for await` pattern as `TimelineModel`) and centralizes the viewer's side-effect
/// lifecycles: prepared-video temp files, the library-changed ping on close, share payloads.
@MainActor
final class ViewerModel: ObservableObject {
    let vm: ViewerViewModel

    @Published private(set) var items: [PhotoItem]
    @Published private(set) var index: Int
    @Published private(set) var isDeleting = false
    @Published private(set) var deletedAny = false
    /// Transient error banner (auto-hides), fed by ViewerEventError.
    @Published private(set) var errorMessage: String?

    private let onDismiss: () -> Void
    private var observeTask: Task<Void, Never>?
    private var eventsTask: Task<Void, Never>?
    private var errorHideTask: Task<Void, Never>?
    /// Prepared decrypt-to-temp video handles keyed by fileId — disposed on page change/close.
    private var videoHandles: [String: VideoHandle] = [:]
    private var closed = false

    init(items: [PhotoItem], initialIndex: Int, onDismiss: @escaping () -> Void) {
        self.onDismiss = onDismiss
        vm = PhotosModuleKt.viewerViewModel(items: items, initialIndex: Int32(initialIndex))
        // Seed from the arguments so frame 0 renders before the first shared emission lands.
        self.items = items
        self.index = max(0, min(initialIndex, items.count - 1))
    }

    var currentItem: PhotoItem? {
        items.indices.contains(index) ? items[index] : nil
    }

    /// Idempotent: wires the state + events subscriptions exactly once.
    func start() {
        guard observeTask == nil else { return }
        // Capture the AsyncSequences (not self) so the tasks never retain the model — the
        // weak self re-check each iteration lets `deinit` fire and cancel them.
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.items = s.items
                self.index = Int(s.index)
                self.isDeleting = s.isDeleting
                self.deletedAny = s.deletedAny
            }
        }
        let events = vm.events
        eventsTask = Task { [weak self] in
            for await e in events {
                guard let self else { return }
                if let err = e as? ViewerEventError {
                    self.showError(err.message)
                } else if e is ViewerEventClosed {
                    // Last item deleted — the shared VM asks the platform to dismiss.
                    self.requestClose()
                }
            }
        }
    }

    func setIndex(_ i: Int) {
        vm.setIndex(i: Int32(i))
    }

    func deleteCurrent() async {
        try? await vm.deleteCurrentAndWait()
    }

    /// The single close path (close button, swipe-down, Closed event): ping the hosts if
    /// anything was deleted, tear down prepared videos, then hand control back to the router.
    func requestClose() {
        guard !closed else { return }
        closed = true
        if deletedAny {
            NotificationCenter.default.post(name: .hbPhotosChanged, object: nil)
        }
        disposeAllVideos()
        onDismiss()
    }

    /// Surface a one-line result from a sheet the viewer hosts (add-to-album) in its toast.
    func note(_ message: String) {
        showError(message)
    }

    private func showError(_ message: String) {
        errorMessage = message
        errorHideTask?.cancel()
        errorHideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            self?.errorMessage = nil
        }
    }

    // MARK: - Video

    /// Decrypt-to-temp for `item` (memoized per fileId). nil = can't play (HLS/failed).
    func prepareVideoURL(for item: PhotoItem) async -> URL? {
        let key = item.fileId.description
        if let handle = videoHandles[key] {
            return URL(fileURLWithPath: handle.filePath)
        }
        let handle = (try? await PhotosModuleKt.prepareVideo(item: item)) ?? nil
        guard let handle else { return nil }
        videoHandles[key] = handle
        return URL(fileURLWithPath: handle.filePath)
    }

    func disposeVideo(for item: PhotoItem) {
        guard let handle = videoHandles.removeValue(forKey: item.fileId.description) else { return }
        Task { try? await PhotosModuleKt.disposeVideo(handle: handle) }
    }

    private func disposeAllVideos() {
        let handles = Array(videoHandles.values)
        videoHandles = [:]
        Task {
            for handle in handles {
                try? await PhotosModuleKt.disposeVideo(handle: handle)
            }
        }
    }

    // MARK: - Share

    /// Share payload for `item`: video → the prepared temp-file URL; still → the decrypted
    /// original written to a temp file (extension from its MIME), falling back to the hi-res
    /// 1200 thumbnail image if the original fetch fails. nil = nothing shareable.
    func shareItems(for item: PhotoItem) async -> [Any]? {
        if item.isVideo {
            guard let url = await prepareVideoURL(for: item) else { return nil }
            return [url]
        }
        let ns = (try? await PhotosModuleIosKt.loadOriginalData(item: item)) ?? nil
        if let ns, (ns as Data).isEmpty == false {
            let data = ns as Data
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("share_\(item.fileId.description)")
                .appendingPathExtension(Self.fileExtension(for: item.payloadContentType))
            if (try? data.write(to: url, options: .atomic)) != nil {
                return [url]
            }
            if let image = UIImage(data: data) {
                return [image]
            }
        }
        if let image = await ThumbnailLoader.shared.image(for: item, maxDim: 1200) {
            return [image]
        }
        return nil
    }

    private static func fileExtension(for contentType: String?) -> String {
        switch contentType?.lowercased() {
        case "image/png": return "png"
        case "image/webp": return "webp"
        case "image/heic", "image/heif": return "heic"
        case "image/gif": return "gif"
        default: return "jpg"
        }
    }

    deinit {
        observeTask?.cancel()
        eventsTask?.cancel()
        errorHideTask?.cancel()
    }
}
