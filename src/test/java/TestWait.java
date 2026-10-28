import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.ClickObject;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.Wait;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.BooleanValue;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import org.testng.annotations.Test;

import java.util.HashMap;

import static org.testng.AssertJUnit.*;

public class TestWait {

    @Test
    public void testFixedTimeWait() throws InterruptedException {
        String sessionName = "waitTest1";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // Launch browser
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(
                sessionName,
                "https://example.com",
                "false", // show browser
                "chromium"
        );

        // Wait for 3 seconds (fixed time)
        Wait wait = new Wait();
        wait.setSessions(sessionMap);
        DictionaryValue waitResult = wait.action(
                sessionName,
                "time",     // wait type
                3.0,        // seconds
                "xpath",    // selector type (ignored in time mode)
                ""          // selector value (ignored in time mode)
        );
        System.out.println(DictionaryTestHelper.result(waitResult));

        // Close browser
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        close.action(sessionName);
    }

    @Test
    public void testElementLoadWait() throws InterruptedException {
        String sessionName = "waitTest2";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // Launch browser
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(
                sessionName,
                "https://the-internet.herokuapp.com/add_remove_elements/",
                "false", // show browser
                "chromium"
        );

        // Click "Add Element" button
        ClickObject click = new ClickObject();
        click.setSessions(sessionMap);
        click.action(sessionName, "xpath", "//*[@onclick='addElement()']");

        // Wait up to 5 seconds for the "Delete" button to appear
        Wait wait = new Wait();
        wait.setSessions(sessionMap);
        DictionaryValue waitResult = wait.action(
                sessionName,
                "element_visible",        // wait type
                5.0,                      // timeout in seconds
                "xpath",                  // selector type
                "//button[text()='Delete']" // selector value
        );
        System.out.println(DictionaryTestHelper.result(waitResult));

        // Validate "Delete" is visible
        PlaywrightConnection conn = (PlaywrightConnection) sessionMap.get(sessionName);
        assertTrue("Delete button should be visible",
                conn.getPage().isVisible("xpath=//button[text()='Delete']"));

        // Close browser
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        close.action(sessionName);
    }
}