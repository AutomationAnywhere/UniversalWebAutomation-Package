import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.ClickObject;
import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.BooleanValue;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import org.testng.annotations.Test;

import java.util.HashMap;

import static org.testng.AssertJUnit.*;

public class TestClickSelectors {

    @Test
    public void testClickWithDifferentSelectors() throws InterruptedException {
        String sessionName = "clicktest";

        // Shared session map
        HashMap<String, Object> sessionMap = new HashMap<>();

        // Launch Browser
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        DictionaryValue launchResult = launch.action(
                sessionName,
                "https://the-internet.herokuapp.com/add_remove_elements/",
                "false",  // visible for debugging
                "webkit"
        );
        System.out.println(DictionaryTestHelper.result(launchResult));

        // ---- Test 1: XPath ----
        ClickObject click = new ClickObject();
        click.setSessions(sessionMap);
        click.action(sessionName, "xpath", "//*[@onclick='addElement()']");
        System.out.println("Clicked via XPath");

        Thread.sleep(1000); // pause so we can see it

        // ---- Test 2: CSS ----
        click.action(sessionName, "css", "button[onclick='addElement()']");
        System.out.println("Clicked via CSS");

        Thread.sleep(1000);

        // ---- Test 3: Text ----
        click.action(sessionName, "text", "Add Element");
        System.out.println("Clicked via Text");

        Thread.sleep(1000);

        // ---- Test 4: JS Path ----
        click.action(sessionName, "jspath", "document.querySelector(\"button[onclick='addElement()']\")");
        System.out.println("Clicked via JS Path");

        Thread.sleep(2000);

        // Validate a Delete button appeared
        PlaywrightConnection conn = (PlaywrightConnection) sessionMap.get(sessionName);
        assertTrue("At least one Delete button should be visible after clicks",
                conn.getPage().isVisible("xpath=//button[text()='Delete']"));

        // Close browser
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        DictionaryValue closeResult = close.action(sessionName);
        System.out.println(DictionaryTestHelper.result(closeResult));
    }
}