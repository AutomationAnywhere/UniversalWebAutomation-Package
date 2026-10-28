import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.ExecuteJavaScript;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import org.testng.annotations.Test;

import java.util.HashMap;

import static org.testng.AssertJUnit.*;

public class TestExecuteJavaScript {

    @Test
    public void testSetUsernameWithJavaScript() throws InterruptedException {
        String sessionName = "testsessionJS1";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // Launch Browser
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(
                sessionName,
                "https://the-internet.herokuapp.com/login",
                "false", // show browser
                "chromium"
        );

        // Execute JS to set value
        ExecuteJavaScript exec = new ExecuteJavaScript();
        exec.setSessions(sessionMap);
        String jsCode = "document.querySelector('#username').value = 'fromJS';";
        DictionaryValue result = exec.action(sessionName, jsCode);
        System.out.println("JS Execution result: " + DictionaryTestHelper.result(result));

        // Validate with Playwright directly
        PlaywrightConnection conn = (PlaywrightConnection) sessionMap.get(sessionName);
        String actual = conn.getPage().inputValue("#username");
        assertEquals("fromJS", actual);

        Thread.sleep(2000); // pause so you can watch it happen

        // Execute JS to set value
        exec.setSessions(sessionMap);
        jsCode = "document.querySelector(\"#login > button\").click()";
        result = exec.action(sessionName, jsCode);
        System.out.println("JS Execution result: " + DictionaryTestHelper.result(result));

        Thread.sleep(2000); // pause so you can watch it happen

        // Close
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        close.action(sessionName);
    }

    @Test
    public void testReturnValueFromJavaScript() {
        String sessionName = "testsessionJS2";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // Launch Browser (headless)
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(
                sessionName,
                "https://example.com",
                "true",  // headless
                "chromium"
        );

        // Execute JS that returns a value
        ExecuteJavaScript exec = new ExecuteJavaScript();
        exec.setSessions(sessionMap);
        String jsCode = "return document.title;";
        DictionaryValue result = exec.action(sessionName, jsCode);

        assertNotNull(result);
        String resultText = DictionaryTestHelper.result(result);
        System.out.println("Page title returned from JS: " + resultText);
        assertTrue(resultText.contains("Example Domain"));

        // Close
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        close.action(sessionName);
    }
}