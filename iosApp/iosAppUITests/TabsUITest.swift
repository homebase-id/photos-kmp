import XCTest

/// Smoke gate for the two-tab home (Round 1, C4). The `-uiTestTimeline` seam hosts the tabbed
/// home without auth, so the Collections tab settles on the grid or one of its skeleton /
/// empty / error states — exactly one surface must render, mirroring TimelineGridUITest.
final class TabsUITest: XCTestCase {
    func testCollectionsTabShowsSurface_andPhotosTabReturns() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        let root = app.otherElements["timeline-root"]
        XCTAssertTrue(root.waitForExistence(timeout: 15), "Timeline root did not render")

        let collectionsTab = app.tabBars.buttons["Collections"]
        XCTAssertTrue(collectionsTab.waitForExistence(timeout: 5), "Collections tab missing")
        collectionsTab.tap()

        // Any of the Collections surfaces proves the tab is wired regardless of backend state.
        let grid = app.descendants(matching: .any)["collections-grid"].firstMatch
        let empty = app.descendants(matching: .any)["collections-empty"].firstMatch
        let skeleton = app.descendants(matching: .any)["collections-skeleton"].firstMatch
        let error = app.descendants(matching: .any)["collections-error"].firstMatch
        let shown =
            skeleton.waitForExistence(timeout: 5)
            || grid.waitForExistence(timeout: 10)
            || empty.waitForExistence(timeout: 10)
            || error.waitForExistence(timeout: 5)
        XCTAssertTrue(shown, "No Collections surface (grid/skeleton/empty/error) rendered")

        app.tabBars.buttons["Photos"].tap()
        XCTAssertTrue(root.waitForExistence(timeout: 10), "Timeline did not return on Photos tab")
    }
}
