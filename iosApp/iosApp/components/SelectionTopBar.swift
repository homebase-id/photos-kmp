import SwiftUI

/// Selection-mode top bar per C5: X (`selection-close`) · "N selected" (`selection-count`) ·
/// trash (`selection-delete`). Callers hide the navigation bar and mount this via
/// `.safeAreaInset(edge: .top)` while selection is active — swapping beats covering the
/// large-title layer.
struct SelectionTopBar: View {
    @Environment(\.colorScheme) private var scheme
    let count: Int
    let onClose: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: PhotosMetrics.space8) {
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(PhotosColor.onSurface(scheme))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Exit selection")
            .accessibilityIdentifier("selection-close")

            Text("\(count) selected")
                .font(PhotosFont.label)
                .foregroundColor(PhotosColor.onSurface(scheme))
                .accessibilityIdentifier("selection-count")

            Spacer()

            Button(action: onDelete) {
                Image(systemName: "trash")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(PhotosColor.error(scheme))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Delete selected")
            .accessibilityIdentifier("selection-delete")
        }
        .padding(.horizontal, PhotosMetrics.space8)
        .frame(maxWidth: .infinity)
        .background(PhotosColor.surface(scheme), ignoresSafeAreaEdges: .top)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("selection-topbar")
    }
}
