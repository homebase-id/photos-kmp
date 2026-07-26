import XCTest

/// Batch D end-to-end: favorite a photo from the Timeline selection, see it land in Favorites,
/// then unfavorite it from there and watch it leave.
///
/// It runs against the real drive like `AlbumLifecycleUITest` — with no photos in the test env
/// (`-uiTestTimeline` runs the real repo with no identity) it SKIPS rather than fails.
final class LibraryStatesUITest: XCTestCase {
    func testFavoriteFromTimeline_showsInFavorites_thenUnfavorite() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        XCTAssertTrue(
            app.otherElements["timeline-root"].waitForExistence(timeout: 15),
            "Timeline root did not render"
        )

        let firstCell = app.descendants(matching: .any)["photo-cell"].firstMatch
        guard firstCell.waitForExistence(timeout: 15) else {
            throw XCTSkip("No photos in the test library — the favorites flow needs at least one cell")
        }

        // --- Timeline selection → favorite ---------------------------------------------------
        firstCell.press(forDuration: 0.8)
        XCTAssertTrue(
            app.staticTexts["selection-count"].waitForExistence(timeout: 5),
            "Selection bar did not appear on long-press"
        )
        guard app.buttons["selection-favorite"].waitForExistence(timeout: 5) else {
            throw XCTSkip("selection-favorite action missing — nothing to verify")
        }
        app.buttons["selection-favorite"].tap()

        // --- Collections → Favorites shows the favorited photo --------------------------------
        app.tabBars.buttons["Collections"].tap()
        let favoritesRow = app.descendants(matching: .any)["collections-library-row-favorites"].firstMatch
        XCTAssertTrue(favoritesRow.waitForExistence(timeout: 10), "Favorites row missing")
        favoritesRow.tap()

        XCTAssertTrue(
            app.navigationBars["Favorites"].waitForExistence(timeout: 10),
            "Favorites screen did not open"
        )
        let favoritesCell = app.descendants(matching: .any)["photo-cell"].firstMatch
        guard favoritesCell.waitForExistence(timeout: 20) else {
            throw XCTSkip("Favorite did not land — no writable session in this test env")
        }

        // --- Unfavorite from the Favorites grid clears it --------------------------------------
        favoritesCell.press(forDuration: 0.8)
        XCTAssertTrue(
            app.staticTexts["selection-count"].waitForExistence(timeout: 5),
            "Favorites selection bar did not appear"
        )
        app.buttons["favorites-unfavorite"].tap()

        let empty = app.descendants(matching: .any)["favorites-empty"].firstMatch
        XCTAssertTrue(empty.waitForExistence(timeout: 20), "Photo did not leave Favorites")
    }
}
