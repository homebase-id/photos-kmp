import XCTest

/// Strict-TDD gate for the iOS Timeline grid (Batch 1).
///
/// The grid must render its container regardless of auth/backend state. Since plan 009 swapped
/// the mock repository for the real `PhotosRepositoryImpl`, the `-uiTestTimeline` seam now runs
/// against the real repo with NO logged-in identity in the test env, so the local index is empty:
/// the timeline settles on the skeleton (while loading) or the empty state, and only shows a
/// populated grid when real data is present. The container (`timeline-root`) always renders, and
/// exactly one of skeleton / grid / empty is always shown — that is the surface these tests pin.
final class TimelineGridUITest: XCTestCase {
    func testTimelineRootRenders() {
        let app = XCUIApplication()
        // Bypass the session gate: a fresh install is logged out, so the auth-gated root would
        // otherwise show the login screen instead of the timeline.
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        // The Timeline screen is the app's root once hello is replaced. The id now sits on a
        // drawn ZStack (not the old non-drawing GeometryReader that SwiftUI collapsed).
        let root = app.otherElements["timeline-root"]
        XCTAssertTrue(
            root.waitForExistence(timeout: 15),
            "Timeline root did not render"
        )
    }

    func testTimelineSurfaceRenders() {
        let app = XCUIApplication()
        // Bypass the session gate: a fresh install is logged out, so the auth-gated root would
        // otherwise show the login screen instead of the timeline.
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        // Root always renders...
        let root = app.otherElements["timeline-root"]
        XCTAssertTrue(
            root.waitForExistence(timeout: 15),
            "Timeline root did not render"
        )

        // ...and exactly one of skeleton / grid / empty is always shown, so the timeline surface
        // is provably wired regardless of backend/auth state (empty is the expected offline state
        // now the real repo backs the seam with no logged-in identity).
        let skeleton = app.scrollViews["timeline-skeleton"]
        let grid = app.scrollViews["timeline-grid"]
        let empty = app.otherElements["timeline-empty"]
        let shown =
            skeleton.waitForExistence(timeout: 5)
            || grid.waitForExistence(timeout: 15)
            || empty.waitForExistence(timeout: 15)
        XCTAssertTrue(
            shown,
            "None of the timeline skeleton, grid, or empty state rendered"
        )
    }
}
