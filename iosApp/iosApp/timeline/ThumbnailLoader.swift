import Foundation
import UIKit
import Shared

/// Loads + caches grid/viewer thumbnails by bridging the shared (SKIE `async`)
/// `loadThumbnailData(item:maxDim:)` decrypt+decode pipeline into `UIImage`. That shared
/// function returns `NSData` in a single memcpy, not one Kotlin/ObjC round-trip per byte.
///
/// Two layers of de-duplication keep the scroll wall cheap:
///   1. `NSCache<NSString, UIImage>` — decoded images, memory-pressure-evicted by iOS.
///   2. An in-flight task map — concurrent requests for the same (fileId, maxDim)
///      await one shared decrypt instead of N, which matters during fast fling-scroll
///      when the same cell is requested as it enters/leaves/re-enters the viewport.
///
/// `actor` gives the in-flight map race-free access without a manual lock.
actor ThumbnailLoader {
    static let shared = ThumbnailLoader()

    private let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        // Decoded grid thumbnails are small; cap the count so a long scroll session
        // doesn't pin unbounded memory. iOS still evicts earlier under pressure.
        c.countLimit = 512
        return c
    }()

    private var inFlight: [String: Task<UIImage?, Never>] = [:]

    private func key(_ item: PhotoItem, _ maxDim: Int) -> String {
        // fileId is the stable identity; maxDim distinguishes grid (300) vs viewer (1200).
        "\(item.fileId)-\(maxDim)"
    }

    /// Returns the decoded thumbnail for `item` at `maxDim`, from cache, an in-flight
    /// load, or a fresh shared load. `nil` on decode/transport failure (the caller
    /// keeps showing the blur placeholder).
    func image(for item: PhotoItem, maxDim: Int) async -> UIImage? {
        let k = key(item, maxDim)
        if let cached = cache.object(forKey: k as NSString) { return cached }
        if let existing = inFlight[k] { return await existing.value }

        let task = Task<UIImage?, Never> { [item, maxDim] in
            await Self.loadDecoded(item: item, maxDim: maxDim)
        }
        inFlight[k] = task
        let image = await task.value
        inFlight[k] = nil
        if let image { cache.setObject(image, forKey: k as NSString) }
        return image
    }

    /// Crosses the SKIE boundary: shared `loadThumbnailData` (suspend → async) →
    /// `NSData` (single memcpy) → `Data` → `UIImage`. Off the actor so the shared decrypt
    /// runs on its own structured-concurrency executor, not serialized on the actor.
    private static func loadDecoded(item: PhotoItem, maxDim: Int) async -> UIImage? {
        guard
            let ns = try? await PhotosModuleIosKt.loadThumbnailData(item: item, maxDim: Int32(maxDim))
        else { return nil }
        let data = ns as Data
        guard !data.isEmpty else { return nil }
        guard let image = UIImage(data: data) else {
            // Bytes arrived but won't decode — the silent failure that masked the
            // per-payload-IV bug (wrong IV → corrupt webp header → nil image).
            NSLog("ThumbnailLoader: undecodable thumbnail for \(item.fileId) maxDim=\(maxDim) bytes=\(data.count)")
            return nil
        }
        return image
    }
}
