import XCTest

/// Smoke test: the app boots into the Timeline screen (driven by the shared
/// `TimelineViewModel` StateFlow via SKIE). The hello/ping screen was replaced by
/// the Timeline grid in Batch 1, so the detailed grid assertions live in
/// `TimelineGridUITest`; this just proves the app launches into that root.
final class MainFlowUITest: XCTestCase {
    func testAppLaunchesIntoTimeline() {
        let app = XCUIApplication()
        app.launch()

        let root = app.otherElements["timeline-root"]
        XCTAssertTrue(
            root.waitForExistence(timeout: 15),
            "App did not launch into the Timeline root"
        )
    }
}
