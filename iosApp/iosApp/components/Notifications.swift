import Foundation

extension Notification.Name {
    /// Posted when the photo library changed outside a host screen (viewer delete today);
    /// Timeline/AlbumDetail observe and refresh. Name matches the frozen Batch B contract.
    static let hbPhotosChanged = Notification.Name("hbPhotosChanged")
}
