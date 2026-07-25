import SwiftUI

/// Branded launch/resolve screen shown while the stored session resolves (the `.splash` route).
/// Reuses the login wordmark motif — the `leaf.fill` glyph + "Homebase Photos" — over the plain
/// system background, with a quiet spinner so a slow session restore never looks frozen.
struct SplashView: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            PhotosColor.background(scheme).ignoresSafeArea()

            VStack(spacing: PhotosMetrics.space16) {
                Image(systemName: "leaf.fill")
                    .font(.system(size: 44))
                    .foregroundColor(PhotosColor.primary(scheme))

                Text("Homebase Photos")
                    .font(PhotosFont.display)
                    .foregroundColor(PhotosColor.onBackground(scheme))

                ProgressView()
                    .tint(PhotosColor.onSurfaceVariant(scheme))
                    .padding(.top, PhotosMetrics.space8)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("splash-root")
    }
}
