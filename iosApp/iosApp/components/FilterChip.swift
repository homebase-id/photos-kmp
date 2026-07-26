import SwiftUI

/// A capsule filter chip — reused by Search's Date/Type/Album filter row. Filled primary when
/// a non-default value is active, quiet neutral otherwise; the label carries the current value
/// (e.g. "Album: Trip") so the chip itself doubles as the summary.
struct FilterChip: View {
    @Environment(\.colorScheme) private var scheme
    let label: String
    var isActive: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(PhotosFont.label)
                .lineLimit(1)
                .foregroundColor(isActive ? PhotosColor.onPrimary(scheme) : PhotosColor.onSurface(scheme))
                .padding(.horizontal, PhotosMetrics.space16)
                .padding(.vertical, PhotosMetrics.space8)
                .background(isActive ? PhotosColor.primary(scheme) : PhotosColor.surfaceVariant(scheme))
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}
