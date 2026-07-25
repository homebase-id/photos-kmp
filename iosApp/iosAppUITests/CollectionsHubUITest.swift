import XCTest

/// Batch C1: the Collections hub is a library block (Favorites · Archive · Trash · Utilities,
/// disabled until Batch D) above the album grid, with a toolbar `+` that opens the create sheet.
///
/// Backend-agnostic like TabsUITest — none of this needs photos or a live session, since the
/// library rows and the create sheet render off local state.
final class CollectionsHubUITest: XCTestCase {
    func testLibraryRowsRenderDisabled_andPlusOpensTheCreateSheet() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        XCTAssertTrue(
            app.otherElements["timeline-root"].waitForExistence(timeout: 15),
            "Timeline root did not render"
        )
        app.tabBars.buttons["Collections"].tap()

        // The four library shortcuts exist (and stay in the a11y tree while disabled, so
        // Batch D only has to flip them on).
        let favorites = app.descendants(matching: .any)["collections-library-row-favorites"].firstMatch
        XCTAssertTrue(favorites.waitForExistence(timeout: 10), "Favorites library row missing")
        for id in [
            "collections-library-row-archive",
            "collections-library-row-trash",
            "collections-library-row-utilities",
        ] {
            XCTAssertTrue(
                app.descendants(matching: .any)[id].firstMatch.exists,
                "\(id) missing from the library section"
            )
        }
        XCTAssertFalse(favorites.isEnabled, "Library rows must stay disabled until Batch D")

        // Toolbar + opens the create sheet; Cancel closes it without writing anything.
        let add = app.buttons["collections-add"]
        XCTAssertTrue(add.waitForExistence(timeout: 5), "Collections + button missing")
        add.tap()

        let dialog = app.descendants(matching: .any)["create-album-dialog"].firstMatch
        XCTAssertTrue(dialog.waitForExistence(timeout: 5), "Create-album sheet did not open")
        XCTAssertTrue(
            app.textFields["album-name-field"].waitForExistence(timeout: 5),
            "Create sheet has no name field"
        )
        // Create stays disabled until a name is typed.
        XCTAssertFalse(app.buttons["album-name-confirm"].isEnabled, "Create enabled with no name")

        app.buttons["album-name-cancel"].tap()
        XCTAssertTrue(waitForDisappearance(of: dialog, timeout: 5), "Create sheet did not cancel")
    }

    private func waitForDisappearance(of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let gone = NSPredicate(format: "exists == false")
        let e = expectation(for: gone, evaluatedWith: element)
        return XCTWaiter().wait(for: [e], timeout: timeout) == .completed
    }
}
