import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.SetRichText;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import org.testng.annotations.Test;

import java.io.File;
import java.util.HashMap;

import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

public class TestSetRichText {

    @Test
    public void testSetRichTextInContentEditableElement() {
        String sessionName = "richTextTest";
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

        // Use SetRichText on the contenteditable element
        SetRichText setRichText = new SetRichText();
        setRichText.setSessions(sessionMap);
        DictionaryValue result = setRichText.action(
                sessionName,
                "css",
                "#editor",
                "This is my rich job description typed with SetRichText."
        );
        System.out.println(DictionaryTestHelper.result(result));

        // Validate by checking innerHTML
        PlaywrightConnection conn = (PlaywrightConnection) sessionMap.get(sessionName);
        String editorHtml = conn.getPage().evalOnSelector("css=#editor", "el => el.innerHTML").toString();
        System.out.println("Editor HTML: " + editorHtml);

        assertTrue("Editor HTML should contain our typed text",
                editorHtml.contains("This is my rich job description"));

        // Close browser
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        DictionaryValue closeResult = close.action(sessionName);
        System.out.println(DictionaryTestHelper.result(closeResult));

        assertFalse(sessionMap.containsKey(sessionName));
    }
}