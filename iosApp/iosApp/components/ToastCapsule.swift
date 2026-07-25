import SwiftUI

/// Transient bottom-capsule message (timeline errors/deletes, viewer errors).
/// Hosts own the show/hide timing; this is just the chrome.
struct ToastCapsule: View {
    @Environment(\.colorScheme) private var scheme

    let message: String
    var a11yId: String = "toast"

    var body: some View {
        Text(message)
            .font(PhotosFont.bodyMedium)
            .foregroundColor(PhotosColor.onSurface(scheme))
            .padding(.horizontal, PhotosMetrics.space16)
            .padding(.vertical, PhotosMetrics.space12)
            .background(PhotosColor.surface3(scheme))
            .clipShape(Capsule())
            .accessibilityIdentifier(a11yId)
    }
}
