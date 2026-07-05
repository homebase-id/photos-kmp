import SwiftUI

/// Sticky month-boundary header ("March 2022"): bold plain text directly on the background —
/// no band, no tint, no material. The opaque background fill only exists so pinned headers
/// don't let the grid scroll through the text.
struct MonthHeader: View {
    @Environment(\.colorScheme) private var scheme
    let title: String

    var body: some View {
        Text(title)
            .font(PhotosFont.monthHeader)
            .foregroundColor(PhotosColor.onSurface(scheme))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, PhotosMetrics.space16)
            .padding(.top, 28)
            .padding(.bottom, PhotosMetrics.space8)
            .background(PhotosColor.background(scheme))
            .accessibilityIdentifier("timeline-month-header")
    }
}

/// Day header ("Wed, Mar 30") — the primary timeline header per Google Photos hierarchy:
/// semibold, full onSurface, plain text on the background.
struct DayHeader: View {
    @Environment(\.colorScheme) private var scheme
    let title: String

    var body: some View {
        Text(title)
            .font(PhotosFont.dateSubhead)
            .foregroundColor(PhotosColor.onSurface(scheme))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, PhotosMetrics.space16)
            .padding(.top, PhotosMetrics.space20)
            .padding(.bottom, PhotosMetrics.space8)
            .background(PhotosColor.background(scheme))
            .accessibilityIdentifier("timeline-day-header")
    }
}
