import SwiftUI

/// Branded launch/resolve screen shown while the stored session resolves (the `.splash` route).
/// The official white aperture over the brand gradient + "Homebase Photos", with a quiet spinner so
/// a slow session restore never looks frozen. Mirrors the Android `SplashScreen` composable.
struct SplashView: View {
    var body: some View {
        ZStack {
            PhotosColor.brandGradient.ignoresSafeArea()

            VStack(spacing: PhotosMetrics.space16) {
                Image("BrandMark")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 120, height: 120)

                Text("Homebase Photos")
                    .font(PhotosFont.display)
                    .foregroundColor(.white)

                ProgressView()
                    .tint(.white)
                    .padding(.top, PhotosMetrics.space8)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("splash-root")
    }
}
