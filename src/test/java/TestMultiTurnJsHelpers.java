import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.FullSelfDrivingMultiTurn;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import com.microsoft.playwright.Page;
import org.testng.annotations.Test;

import java.io.File;
import java.util.HashMap;

import static org.testng.AssertJUnit.assertEquals;

/**
 * The actor's generated JS runs inside FullSelfDrivingMultiTurn's wrapper. Plain
 * `el.value = x` + an input event never reaches a React/Vue controlled input's state
 * (the field looks filled, the app submits empty) - observed on TaskDeck's login, where
 * every MultiTurn run stayed on /login. The wrapper's setValue() helper must commit it.
 */
public class TestMultiTurnJsHelpers {

    private String run(String code) {
        return run("react-controlled-input.html", code, "() => window.committed");
    }

    private String run(String fixture, String code, String probe) {
        String session = "mtJsHelpers";
        HashMap<String, Object> sessions = new HashMap<>();
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessions);
        launch.action(session, new File("src/test/resources/typesafe-fixtures/" + fixture).toURI().toString(),
                "true", "chromium");
        try {
            Page page = ((PlaywrightConnection) sessions.get(session)).getPage();
            FullSelfDrivingMultiTurn.executeJavaScriptSafely(page, code, 10.0, 0);
            return String.valueOf(page.evaluate(probe));
        } finally {
            CloseBrowser close = new CloseBrowser();
            close.setSessions(sessions);
            close.action(session);
        }
    }

    @Test
    public void naiveValueAssignmentDoesNotReachControlledState() {
        // Documents the failure mode the helper exists for.
        assertEquals("null", run("const el = document.querySelector('#title'); el.value = 'Deck';"
                + " el.dispatchEvent(new Event('input', {bubbles: true})); return 'OK';"));
    }

    @Test
    public void setValueHelperCommitsToControlledState() {
        assertEquals("Deck", run("await setValue(document.querySelector('#title'), 'Deck'); return 'OK';"));
    }

    @Test
    public void rowForReturnsTheRowNotAContainerHoldingEveryRow() {
        // Generated code like querySelectorAll('tr, div').find(text includes X && 'Delete')
        // matches the wrapping <div> first and deletes the FIRST row (observed: a High task
        // deleted while removing Low ones). rowFor() must return the smallest matching row.
        String remaining = run("task-rows.html",
                "rowFor('Archive old invoices').querySelector('button').click(); return 'OK';",
                "() => [...document.querySelectorAll('td:first-child')].map(td => td.innerText).join(',')");
        assertEquals("Renew SSL certificate", remaining);
    }
}
