import SwiftUI

/// Selection-mode top bar per C5: X (`selection-close`) · "N selected" (`selection-count`) ·
/// the actions the host supports. Callers hide the navigation bar and mount this via
/// `.safeAreaInset(edge: .top)` while selection is active — swapping beats covering the
/// large-title layer.
///
/// Every action is optional, so one bar serves every host: the Timeline shows add-to-album +
/// favorite + archive + delete; the album detail shows set-cover (only with exactly one photo
/// picked) + remove; Favorites/Archive/Trash (Batch D) show their own single mutation. The ids
/// are fixed per action because the action itself is what a test looks for.
struct SelectionTopBar: View {
    @Environment(\.colorScheme) private var scheme
    let count: Int
    let onClose: () -> Void
    var onAddToAlbum: (() -> Void)?
    var onFavorite: (() -> Void)?
    var onArchive: (() -> Void)?
    var onSetCover: (() -> Void)?
    var onRemoveFromAlbum: (() -> Void)?
    var onUnfavorite: (() -> Void)?
    var onUnarchive: (() -> Void)?
    var onRestore: (() -> Void)?
    var onDeleteForever: (() -> Void)?
    var onDelete: (() -> Void)?

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

            if let onAddToAlbum {
                action(
                    "rectangle.stack.badge.plus",
                    label: "Add to album",
                    id: "selection-add",
                    tint: PhotosColor.onSurface(scheme),
                    perform: onAddToAlbum
                )
            }
            if let onFavorite {
                action(
                    "heart",
                    label: "Favorite",
                    id: "selection-favorite",
                    tint: PhotosColor.onSurface(scheme),
                    perform: onFavorite
                )
            }
            if let onArchive {
                action(
                    "archivebox",
                    label: "Archive",
                    id: "selection-archive",
                    tint: PhotosColor.onSurface(scheme),
                    perform: onArchive
                )
            }
            if let onSetCover {
                action(
                    "photo.badge.checkmark",
                    label: "Set as cover",
                    id: "album-setcover",
                    tint: PhotosColor.onSurface(scheme),
                    perform: onSetCover
                )
            }
            if let onRemoveFromAlbum {
                action(
                    "minus.circle",
                    label: "Remove from album",
                    id: "album-remove",
                    tint: PhotosColor.onSurface(scheme),
                    perform: onRemoveFromAlbum
                )
            }
            if let onUnfavorite {
                action(
                    "heart.slash",
                    label: "Remove from favorites",
                    id: "selection-unfavorite",
                    tint: PhotosColor.onSurface(scheme),
                    perform: onUnfavorite
                )
            }
            if let onUnarchive {
                action(
                    "tray.and.arrow.up",
                    label: "Unarchive",
                    id: "selection-unarchive",
                    tint: PhotosColor.onSurface(scheme),
                    perform: onUnarchive
                )
            }
            if let onRestore {
                action(
                    "arrow.uturn.backward",
                    label: "Restore",
                    id: "trash-restore",
                    tint: PhotosColor.onSurface(scheme),
                    perform: onRestore
                )
            }
            if let onDeleteForever {
                action(
                    "trash",
                    label: "Delete forever",
                    id: "trash-delete-forever",
                    tint: PhotosColor.error(scheme),
                    perform: onDeleteForever
                )
            }
            if let onDelete {
                action(
                    "trash",
                    label: "Delete selected",
                    id: "selection-delete",
                    tint: PhotosColor.error(scheme),
                    perform: onDelete
                )
            }
        }
        .padding(.horizontal, PhotosMetrics.space8)
        .frame(maxWidth: .infinity)
        .background(PhotosColor.surface(scheme), ignoresSafeAreaEdges: .top)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("selection-topbar")
    }

    private func action(
        _ systemName: String,
        label: String,
        id: String,
        tint: Color,
        perform: @escaping () -> Void
    ) -> some View {
        Button(action: perform) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(tint)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(label)
        .accessibilityIdentifier(id)
    }
}
