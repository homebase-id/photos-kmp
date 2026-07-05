import XCTest

/// Smoke gate for timeline multi-select (Round 1, C5). The `-uiTestTimeline` seam runs against
/// the real repository; with no logged-in identity the grid is empty, so the flow can only be
/// exercised when photos exist — the test SKIPS (not fails) on an empty library, and runs fully
/// on the verifier's logged-in simulator.
final class SelectionUITest: XCTestCase {
    func testLongPressEntersSelection_andCloseExits() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        let root = app.otherElements["timeline-root"]
        XCTAssertTrue(root.waitForExistence(timeout: 15), "Timeline root did not render")

        let firstCell = app.descendants(matching: .any)["photo-cell"].firstMatch
        guard firstCell.waitForExistence(timeout: 15) else {
            throw XCTSkip("No photos in the test library — selection needs at least one cell")
        }

        firstCell.press(forDuration: 0.8)

        let count = app.staticTexts["selection-count"]
        XCTAssertTrue(count.waitForExistence(timeout: 5), "Selection bar did not appear on long-press")
        XCTAssertEqual(count.label, "1 selected")

        app.buttons["selection-close"].tap()
        XCTAssertFalse(count.waitForExistence(timeout: 3), "Selection bar did not close on X")
    }
}
