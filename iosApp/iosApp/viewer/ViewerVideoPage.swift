import SwiftUI
import AVKit
import Shared

/// One pager page for a video: the shared decrypt-to-temp `prepareVideo` feeds an `AVPlayer`.
/// The hi-res thumbnail underlays as a poster while preparing; a spinner overlays it. Only the
/// CURRENT page prepares (decrypting a neighbor video ahead of time is too expensive); leaving
/// the page pauses, drops the player, and disposes the temp file through the model.
struct ViewerVideoPage: View {
    @Environment(\.colorScheme) private var scheme

    let item: PhotoItem
    let isCurrent: Bool
    @ObservedObject var model: ViewerModel

    @State private var player: AVPlayer?
    @State private var failed = false
    @State private var poster: UIImage?

    var body: some View {
        ZStack {
            if let poster, player == nil {
                Image(uiImage: poster).resizable().scaledToFit()
            }
            if let player {
                VideoPlayer(player: player)
                    .accessibilityIdentifier("viewer-video")
            } else if failed {
                failedState
            } else {
                ProgressView()
                    .controlSize(.large)
                    .tint(PhotosColor.onOverlay(scheme))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: item.fileId.description) {
            poster = await ThumbnailLoader.shared.image(for: item, maxDim: 1200)
        }
        .task(id: "\(item.fileId.description)-\(isCurrent)") {
            guard isCurrent, player == nil, !failed else { return }
            await prepare()
        }
        .onChange(of: isCurrent) { _, current in
            if !current { teardown() }
        }
        .onDisappear { teardown() }
    }

    private var failedState: some View {
        VStack(spacing: PhotosMetrics.space8) {
            Image(systemName: "video.slash")
                .font(.system(size: 40))
            Text("Can't play this video")
                .font(PhotosFont.bodyMedium)
        }
        .foregroundColor(PhotosColor.onOverlayDim(scheme))
    }

    private func prepare() async {
        guard let url = await model.prepareVideoURL(for: item) else {
            failed = true
            return
        }
        let p = AVPlayer(url: url)
        player = p
        p.play()
    }

    private func teardown() {
        player?.pause()
        player = nil
        model.disposeVideo(for: item)
    }
}
