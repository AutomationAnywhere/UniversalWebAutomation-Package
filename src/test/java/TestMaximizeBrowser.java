import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.MaximizeBrowser;
import com.automationanywhere.botcommand.Wait;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import org.testng.annotations.Test;

import java.util.HashMap;

import static org.testng.AssertJUnit.assertFalse;

public class TestMaximizeBrowser {

    @Test
    public void testMaximizeVisibleBrowser() {
        String sessionName = "maximizeTest";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // Launch browser (visible mode)
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        DictionaryValue launchResult = launch.action(
                sessionName,
                "https://example.com",
                "false", // visible mode
                "chromium" // MaximizeBrowser only supports Chromium (see its own doc comment)
        );
        System.out.println(DictionaryTestHelper.result(launchResult));

        // Wait 3 seconds so you can see default size
        Wait wait = new Wait();
        wait.setSessions(sessionMap);
        wait.action(sessionName, "time", 3.0, "xpath", "");

        // Maximize browser
        MaximizeBrowser maximize = new MaximizeBrowser();
        maximize.setSessions(sessionMap);
        DictionaryValue maximizeResult = maximize.action(sessionName);
        System.out.println(DictionaryTestHelper.result(maximizeResult));

        // Wait 3 more seconds so you can confirm visually
        wait.action(sessionName, "time", 3.0, "xpath", "");

        // Close browser
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        DictionaryValue closeResult = close.action(sessionName);
        System.out.println(DictionaryTestHelper.result(closeResult));

        assertFalse(sessionMap.containsKey(sessionName));
    }
}