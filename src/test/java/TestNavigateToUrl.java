import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.NavigateToUrl;
import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.Wait;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import org.testng.annotations.Test;

import java.util.HashMap;

import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertTrue;

public class TestNavigateToUrl {

    @Test
    public void testNavigationInSameSession() {
        String sessionName = "navTest";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // 1. Launch browser and open login page
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        DictionaryValue launchResult = launch.action(
                sessionName,
                "https://the-internet.herokuapp.com/login",
                "false", // headless?
                "chromium"
        );
        System.out.println("Launch result: " + DictionaryTestHelper.result(launchResult));

        // Wait for page to load fully
        Wait wait = new Wait();
        wait.setSessions(sessionMap);
        wait.action(sessionName, "time", 5.0, "xpath", "");

        // 2. Navigate to secure page in the SAME session
        NavigateToUrl navigate = new NavigateToUrl();
        navigate.setSessions(sessionMap);
        DictionaryValue navResult = navigate.action(
                sessionName,
                "https://example.com"
        );
        String navMsg = DictionaryTestHelper.result(navResult);
        System.out.println("Navigate result: " + navMsg);

        // Validate that navigation worked
        assertNotNull(navMsg);
        assertTrue(navMsg.contains("example"));

        // Wait for page to load fully
        wait.action(sessionName, "time", 5.0, "xpath", "");

        // 3. Close browser
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        DictionaryValue closeResult = close.action(sessionName);
        System.out.println("Close result: " + DictionaryTestHelper.result(closeResult));
    }
}