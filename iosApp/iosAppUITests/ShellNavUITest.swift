import XCTest

/// Smoke gate for the four-tab app shell (Batch A). The `-uiTestTimeline` seam hosts the tabbed
/// home without auth, so the four tabs must exist and switching tabs must land on the right
/// surface regardless of backend state — mirroring TabsUITest's backend-agnostic stance.
final class ShellNavUITest: XCTestCase {
    func testFourTabsExist_collectionsAndSearchResolve() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        let root = app.otherElements["timeline-root"]
        XCTAssertTrue(root.waitForExistence(timeout: 15), "Timeline root did not render")

        // All four IA tabs are present in the tab bar.
        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(tabBar.buttons["Photos"].waitForExistence(timeout: 5), "Photos tab missing")
        XCTAssertTrue(tabBar.buttons["Collections"].exists, "Collections tab missing")
        XCTAssertTrue(tabBar.buttons["Create"].exists, "Create tab missing")
        XCTAssertTrue(tabBar.buttons["Search"].exists, "Search tab missing")

        // Collections tab settles on one of its surfaces (grid / skeleton / empty / error).
        tabBar.buttons["Collections"].tap()
        let grid = app.descendants(matching: .any)["collections-grid"].firstMatch
        let empty = app.descendants(matching: .any)["collections-empty"].firstMatch
        let skeleton = app.descendants(matching: .any)["collections-skeleton"].firstMatch
        let error = app.descendants(matching: .any)["collections-error"].firstMatch
        let collectionsShown =
            skeleton.waitForExistence(timeout: 5)
            || grid.waitForExistence(timeout: 10)
            || empty.waitForExistence(timeout: 10)
            || error.waitForExistence(timeout: 5)
        XCTAssertTrue(collectionsShown, "No Collections surface rendered")

        // Search tab is idle with no query/filters yet — it shows the recents surface (Batch E).
        tabBar.buttons["Search"].tap()
        let searchRecent = app.descendants(matching: .any)["search-recent"].firstMatch
        XCTAssertTrue(searchRecent.waitForExistence(timeout: 10), "Search idle surface did not render")

        // Photos tab returns to the timeline.
        tabBar.buttons["Photos"].tap()
        XCTAssertTrue(root.waitForExistence(timeout: 10), "Timeline did not return on Photos tab")
    }
}
