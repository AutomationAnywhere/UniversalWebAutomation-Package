import com.automationanywhere.botcommand.ClickObject;
import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.InsertKeystrokes;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import org.testng.annotations.Test;

import java.io.File;
import java.util.HashMap;

import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

public class TestInsertKeystrokes {

    @Test
    public void testInsertKeystrokesIntoContentEditableElement() {
        String sessionName = "keystrokeTest";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // Launch browser against a local fixture (was previously a live third-party site,
        // whose UI has since changed and is no longer a stable target for this test - see
        // src/test/resources/typesafe-fixtures/contenteditable.html, already used elsewhere
        // in this project's test suite for the same purpose).
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        String fixtureUrl = new File("src/test/resources/typesafe-fixtures/contenteditable.html").toURI().toString();
        launch.action(
                sessionName,
                fixtureUrl,
                "true",   // headless
                "chromium"
        );

        // Click into the contenteditable div to focus it
        ClickObject click = new ClickObject();
        click.setSessions(sessionMap);
        DictionaryValue clickResult = click.action(
                sessionName,
                "css",
                "#editor"
        );
        System.out.println(DictionaryTestHelper.result(clickResult));

        // Type text into the focused editor
        InsertKeystrokes keys = new InsertKeystrokes();
        keys.setSessions(sessionMap);
        DictionaryValue typeResult = keys.action(sessionName, "This is a test job description typed via keystrokes.");
        System.out.println(DictionaryTestHelper.result(typeResult));

        // Validate against the contenteditable div
        PlaywrightConnection conn = (PlaywrightConnection) sessionMap.get(sessionName);
        String editorText = conn.getPage().innerText("#editor");
        System.out.println("Editor contains: " + editorText);
        assertTrue("Contenteditable element should contain the typed text",
                editorText.contains("This is a test job description"));

        // Close browser
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        DictionaryValue closeResult = close.action(sessionName);
        System.out.println(DictionaryTestHelper.result(closeResult));

        assertFalse(sessionMap.containsKey(sessionName));
    }
}