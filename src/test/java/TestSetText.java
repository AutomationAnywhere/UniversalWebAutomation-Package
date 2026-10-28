import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.SetText;
import com.automationanywhere.botcommand.GetText;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import org.testng.annotations.Test;

import java.util.HashMap;

import static org.testng.AssertJUnit.*;

public class TestSetText {

    @Test
    public void testSetTextInUsernameField() throws InterruptedException {
        String sessionName = "testsession";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // LaunchBrowser
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        DictionaryValue launchResult = launch.action(
                sessionName,
                "https://the-internet.herokuapp.com/login",
                "false",  // visible mode = false => show browser
                "chromium"
        );
        System.out.println(DictionaryTestHelper.result(launchResult));
        Thread.sleep(2000);

        // SetText
        SetText setText = new SetText();
        setText.setSessions(sessionMap);
        DictionaryValue setResult = setText.action(
                sessionName,
                "css",        // selector type
                "#username",  // selector value
                "myTestUser"  // text to type
        );
        System.out.println(DictionaryTestHelper.result(setResult));
        Thread.sleep(2000);

        // Verify text was entered
        PlaywrightConnection conn = (PlaywrightConnection) sessionMap.get(sessionName);
        String value = conn.getPage().inputValue("css=#username");
        assertEquals("myTestUser", value);

        // CloseBrowser
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        DictionaryValue closeResult = close.action(sessionName);
        System.out.println(DictionaryTestHelper.result(closeResult));

        assertFalse(sessionMap.containsKey(sessionName));
    }

    @Test
    public void testSetTextInPasswordFieldWithXPath() throws InterruptedException {
        String sessionName = "testsession2";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // LaunchBrowser
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName,
                "https://the-internet.herokuapp.com/login",
                "false",  // show browser
                "chromium"
        );

        // SetText into password using XPath
        SetText setText = new SetText();
        setText.setSessions(sessionMap);
        setText.action(sessionName, "xpath", "//*[@id='password']", "mySecretPass");

        // Validate using GetText (should be empty for <input>, so we use inputValue for assert)
        PlaywrightConnection conn = (PlaywrightConnection) sessionMap.get(sessionName);
        String actual = conn.getPage().inputValue("xpath=//*[@id='password']");
        assertEquals("mySecretPass", actual);

        // Bonus: demonstrate GetText (will return empty for inputs, but works for labels, spans, etc.)
        GetText getText = new GetText();
        getText.setSessions(sessionMap);
        DictionaryValue textVal = getText.action(sessionName, "xpath", "//*[@id='password']");
        System.out.println("GetText on password field returned: '" + DictionaryTestHelper.result(textVal) + "'");
        Thread.sleep(2000);

        // Close
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        close.action(sessionName);
    }
}