import Foundation

extension Notification.Name {
    /// Posted when the photo library changed outside a host screen (viewer delete today);
    /// Timeline/AlbumDetail observe and refresh. Name matches the frozen Batch B contract.
    static let hbPhotosChanged = Notification.Name("hbPhotosChanged")

    /// Posted by `AlbumsModel` after an album-level write (create / rename / delete / set cover).
    /// `albumsViewModel()` is a Koin factory, so every screen holds its own list — without this
    /// ping the Collections grid keeps showing an album the detail screen just deleted. The
    /// poster rides in `object` so it skips its own notification (it already patched its state).
    static let hbAlbumsChanged = Notification.Name("hbAlbumsChanged")
}
