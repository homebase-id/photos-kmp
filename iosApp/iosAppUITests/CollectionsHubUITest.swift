import XCTest

/// Batch C1 + D: the Collections hub is a library block (Favorites · Archive · Trash · Utilities)
/// above the album grid, with a toolbar `+` that opens the create sheet. Batch D wired the first
/// three rows to push their own screen; Utilities has no screen yet and stays disabled.
///
/// Backend-agnostic like TabsUITest — none of this needs photos or a live session, since the
/// library rows, navigation, and the create sheet render off local state.
final class CollectionsHubUITest: XCTestCase {
    func testLibraryRowsNavigate_utilitiesStaysDisabled_andPlusOpensTheCreateSheet() {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        XCTAssertTrue(
            app.otherElements["timeline-root"].waitForExistence(timeout: 15),
            "Timeline root did not render"
        )
        app.tabBars.buttons["Collections"].tap()

        // The four library shortcuts exist; Batch D enabled the first three.
        let favorites = app.descendants(matching: .any)["collections-library-row-favorites"].firstMatch
        XCTAssertTrue(favorites.waitForExistence(timeout: 10), "Favorites library row missing")
        XCTAssertTrue(favorites.isEnabled, "Favorites row should be enabled (Batch D)")
        for id in ["collections-library-row-archive", "collections-library-row-trash"] {
            let row = app.descendants(matching: .any)[id].firstMatch
            XCTAssertTrue(row.exists, "\(id) missing from the library section")
            XCTAssertTrue(row.isEnabled, "\(id) should be enabled (Batch D)")
        }
        let utilities = app.descendants(matching: .any)["collections-library-row-utilities"].firstMatch
        XCTAssertTrue(utilities.exists, "Utilities row missing")
        XCTAssertFalse(utilities.isEnabled, "Utilities has no screen yet — must stay disabled")

        // Tapping Favorites pushes its own screen; back returns to the hub.
        favorites.tap()
        XCTAssertTrue(
            app.navigationBars["Favorites"].waitForExistence(timeout: 10),
            "Favorites screen did not open"
        )
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(
            app.descendants(matching: .any)["collections-library-row-favorites"].firstMatch
                .waitForExistence(timeout: 10),
            "Did not return to the Collections hub"
        )

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
