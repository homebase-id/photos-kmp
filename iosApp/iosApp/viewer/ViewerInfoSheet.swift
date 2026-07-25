import SwiftUI
import Shared

/// Photo metadata sheet (viewer Info action). Reads only what `PhotoItem` carries —
/// no filename/byte-size rows (deliberate: not on the model, per the Batch B contract).
struct ViewerInfoSheet: View {
    let item: PhotoItem

    var body: some View {
        NavigationStack {
            List {
                LabeledContent("Date", value: Self.dateTimeFormatter.string(from: date(millis: item.userDate)))
                LabeledContent("Dimensions", value: "\(item.pixelWidth) × \(item.pixelHeight)")
                LabeledContent("Type", value: item.payloadContentType ?? "Unknown")
                LabeledContent("Video", value: item.isVideo ? "Yes" : "No")
                if let lastModified = item.lastModified {
                    LabeledContent(
                        "Modified",
                        value: Self.dateTimeFormatter.string(from: date(millis: lastModified.int64Value))
                    )
                }
            }
            .navigationTitle("Info")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("viewer-info-sheet")
    }

    private func date(millis: Int64) -> Date {
        Date(timeIntervalSince1970: Double(millis) / 1000.0)
    }

    // UTC to match the timeline's month/day bucketing of the same userDate millis.
    private static let dateTimeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.timeZone = TimeZone(identifier: "UTC")!
        f.dateFormat = "MMM d, yyyy · h:mm a"
        return f
    }()
}
