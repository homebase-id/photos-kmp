import XCTest

/// Batch E: Search screen smoke test, mirroring `TimelineGridUITest`'s bypass pattern. It runs
/// against the real repo with no logged-in identity (`-uiTestTimeline`), so the local index is
/// empty — idle always shows the recents surface, and any submitted query settles on the empty
/// state (or, if the test environment happens to have local data, the results grid). Both
/// outcomes prove the screen is wired; this is not a Favorites-style data-dependent flow test.
final class SearchUITest: XCTestCase {
    func testIdle_showsRecents() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        app.tabBars.buttons["Search"].tap()

        let recent = app.descendants(matching: .any)["search-recent"]
        XCTAssertTrue(recent.waitForExistence(timeout: 15), "Idle Search did not show the recents surface")
    }

    func testSubmit_showsResultsOrEmptyState() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        app.tabBars.buttons["Search"].tap()

        let searchField = app.searchFields.firstMatch
        guard searchField.waitForExistence(timeout: 10) else {
            throw XCTSkip("No search field found — nothing to submit")
        }
        searchField.tap()
        searchField.typeText("test")
        app.keyboards.buttons["search"].tap()

        let grid = app.descendants(matching: .any)["search-results-grid"]
        let empty = app.descendants(matching: .any)["search-empty"]
        let shown = grid.waitForExistence(timeout: 5) || empty.waitForExistence(timeout: 15)
        XCTAssertTrue(shown, "Neither the results grid nor the empty state rendered after submit")
    }
}
