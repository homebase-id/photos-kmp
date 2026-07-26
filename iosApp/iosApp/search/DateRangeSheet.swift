import SwiftUI

/// Date-range filter sheet: two `DatePicker`s, whole days only. Converts to the day-start /
/// day-end millis `SearchViewModel.setDateRange` expects, UTC — matching the shared month/day
/// bucketing (`userDate` is EXIF capture millis, always read back in UTC elsewhere in the app).
struct DateRangeSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    @State private var from: Date
    @State private var to: Date

    let onApply: (Int64?, Int64?) -> Void
    let onClear: () -> Void

    init(initialFrom: Date?, initialTo: Date?, onApply: @escaping (Int64?, Int64?) -> Void, onClear: @escaping () -> Void) {
        let now = Date()
        _from = State(initialValue: initialFrom ?? Self.utcCalendar.date(byAdding: .day, value: -30, to: now) ?? now)
        _to = State(initialValue: initialTo ?? now)
        self.onApply = onApply
        self.onClear = onClear
    }

    var body: some View {
        NavigationStack {
            Form {
                DatePicker("From", selection: $from, in: ...to, displayedComponents: .date)
                DatePicker("To", selection: $to, in: from...Date.distantFuture, displayedComponents: .date)
            }
            .navigationTitle("Date range")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Clear") {
                        onClear()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        onApply(Self.startOfDayMillis(from), Self.endOfDayMillis(to))
                        dismiss()
                    }
                }
            }
        }
        .tint(PhotosColor.primary(scheme))
        .presentationDetents([.medium])
        .accessibilityIdentifier("search-date-sheet")
    }

    // MARK: - UTC day-boundary millis

    // Matches Android's DateRangePicker, which reports the day in UTC directly.
    private static let endOfDayOffsetMs: Int64 = 24 * 60 * 60 * 1000 - 1 // 23:59:59.999

    private static let utcCalendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    /// `DatePicker` returns a `Date` for the calendar day the user picked, interpreted in the
    /// device's local timezone — reading that instant back with a UTC calendar can land on the
    /// wrong day for any non-UTC user (e.g. IST). Pull the year/month/day the user actually
    /// picked (local calendar) and rebuild UTC midnight from those same components instead.
    private static func utcMidnight(of date: Date) -> Date {
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return utcCalendar.date(from: comps) ?? date
    }

    private static func startOfDayMillis(_ date: Date) -> Int64 {
        Int64(utcMidnight(of: date).timeIntervalSince1970 * 1000)
    }

    private static func endOfDayMillis(_ date: Date) -> Int64 {
        Int64(utcMidnight(of: date).timeIntervalSince1970 * 1000) + endOfDayOffsetMs
    }
}
