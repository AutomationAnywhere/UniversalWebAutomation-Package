import com.automationanywhere.botcommand.ClickObject;
import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.BooleanValue;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import org.testng.annotations.Test;

import java.util.HashMap;

import static org.testng.AssertJUnit.*;

public class TestLaunchBrowser {

    @Test
    public void testLaunchClickClose() throws InterruptedException {
        String sessionName = "testsession";

        // Shared session map for testing
        HashMap<String, Object> sessionMap = new HashMap<>();

        // LaunchBrowser
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);

        DictionaryValue launchResult = launch.action(
                sessionName,
                "https://the-internet.herokuapp.com/add_remove_elements/",
                "false", // visible mode? true = headless, false = show browser
                "chromium"
        );
        System.out.println(DictionaryTestHelper.result(launchResult));
        // 👇 pause so you can see the window open

        Thread.sleep(5000);

        // ClickObject
        ClickObject click = new ClickObject();
        click.setSessions(sessionMap);

        DictionaryValue clickResult = click.action(sessionName, "xpath", "//*[@onclick='addElement()']");
        System.out.println(DictionaryTestHelper.result(clickResult));

        // Validate the "Delete" button appears
        PlaywrightConnection conn =
                (PlaywrightConnection) sessionMap.get(sessionName);
        assertTrue(conn.getPage().isVisible("xpath=//button[text()='Delete']"));
        Thread.sleep(5000);
        // CloseBrowser
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);

        DictionaryValue closeResult = close.action(sessionName);
        System.out.println(DictionaryTestHelper.result(closeResult));

        // Ensure session is removed
        assertFalse(sessionMap.containsKey(sessionName));
    }

    @Test
    public void testLaunchHeadless() {
        LaunchBrowser cmd = new LaunchBrowser();
        cmd.setSessions(new HashMap<>());  // inject session map

        DictionaryValue result = cmd.action(
                "testsession",               // session name
                "https://example.com",       // URL
                "true",     // visibleMode: true => headless
                "chromium"                   // browser type
        );

        assertNotNull(result);
        String resultText = DictionaryTestHelper.result(result);
        System.out.println("Headless result: " + resultText);
        assertTrue(resultText.contains("Example Domain"));
    }

    @Test
    public void testLaunchVisible() {
        LaunchBrowser cmd = new LaunchBrowser();
        cmd.setSessions(new HashMap<>());

        DictionaryValue result = cmd.action(
                "testsession",
                "https://example.com",
                "false",      // visibleMode: false => non-headless
                "chromium"
        );

        assertNotNull(result);
        String resultText = DictionaryTestHelper.result(result);
        System.out.println("Visible result: " + resultText);
        assertTrue(resultText.contains("Example Domain"));
    }
}
