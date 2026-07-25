import SwiftUI

/// The Collections hub's library shortcuts (C1): Favorites · Archive · Trash · Utilities, grouped
/// into one rounded card above the album grid — the Google Photos "Library" block.
///
/// Batch D owns the destinations, so every row ships disabled with a "Soon" chip. They stay in
/// the accessibility tree (disabled, not hidden) so the ids are testable now and the rows only
/// need an `action` + `enabled: true` when D lands.
struct LibrarySection: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(spacing: 0) {
            LibraryRow(
                title: "Favorites",
                systemImage: "heart",
                identifier: "collections-library-row-favorites"
            )
            rowDivider
            LibraryRow(
                title: "Archive",
                systemImage: "archivebox",
                identifier: "collections-library-row-archive"
            )
            rowDivider
            LibraryRow(
                title: "Trash",
                systemImage: "trash",
                identifier: "collections-library-row-trash"
            )
            rowDivider
            LibraryRow(
                title: "Utilities",
                systemImage: "wrench.and.screwdriver",
                identifier: "collections-library-row-utilities"
            )
        }
        .background(PhotosColor.surface1(scheme))
        .clipShape(RoundedRectangle(cornerRadius: PhotosMetrics.radiusMd))
        .padding(.horizontal, PhotosMetrics.screenEdge)
        .padding(.top, PhotosMetrics.space8)
    }

    private var rowDivider: some View {
        Divider().padding(.leading, PhotosMetrics.space48)
    }
}

/// One library shortcut row: leading glyph, title, trailing "Soon" chip (while disabled) and the
/// stock disclosure chevron.
struct LibraryRow: View {
    @Environment(\.colorScheme) private var scheme

    let title: String
    let systemImage: String
    let identifier: String
    /// Batch D flips this on and passes a real `action`.
    var enabled: Bool = false
    var action: () -> Void = {}

    var body: some View {
        Button(action: action) {
            HStack(spacing: PhotosMetrics.space16) {
                Image(systemName: systemImage)
                    .font(.system(size: 17, weight: .regular))
                    .foregroundColor(
                        enabled ? PhotosColor.primary(scheme) : PhotosColor.onSurfaceVariant(scheme)
                    )
                    .frame(width: 24)
                Text(title)
                    .font(PhotosFont.body)
                    .foregroundColor(
                        enabled ? PhotosColor.onSurface(scheme) : PhotosColor.onSurfaceVariant(scheme)
                    )
                Spacer()
                if !enabled { soonChip }
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(PhotosColor.onSurfaceVariantDim(scheme))
            }
            .padding(.horizontal, PhotosMetrics.space16)
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .disabled(!enabled)
        .accessibilityLabel(enabled ? title : "\(title), coming soon")
        .accessibilityIdentifier(identifier)
    }

    private var soonChip: some View {
        Text("Soon")
            .font(PhotosFont.caption)
            .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
            .padding(.horizontal, PhotosMetrics.space8)
            .padding(.vertical, PhotosMetrics.space2)
            .background(PhotosColor.surfaceVariant(scheme))
            .clipShape(Capsule())
    }
}
