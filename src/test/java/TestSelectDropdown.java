import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.SelectDropdown;
import com.automationanywhere.botcommand.Wait;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import org.testng.annotations.Test;

import java.util.HashMap;

import static org.testng.AssertJUnit.*;

public class TestSelectDropdown {

    @Test
    public void testDropdownSelections() {
        String sessionName = "dropdownTest";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // Launch browser
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        DictionaryValue launchResult = launch.action(
                sessionName,
                "https://the-internet.herokuapp.com/dropdown",
                "false",   // visible mode
                "chromium"
        );
        System.out.println(DictionaryTestHelper.result(launchResult));

        // Wait for 2 seconds so you can see the page
        Wait wait = new Wait();
        wait.setSessions(sessionMap);
        wait.action(sessionName, "time", 2.0, "xpath", "");

        // Select by VALUE
        SelectDropdown select = new SelectDropdown();
        select.setSessions(sessionMap);
        DictionaryValue result1 = select.action(sessionName, "css", "#dropdown", "value", "2");
        System.out.println(DictionaryTestHelper.result(result1));

        wait.action(sessionName, "time", 2.0, "xpath", "");

        // Select by TEXT
        DictionaryValue result2 = select.action(sessionName, "xpath", "//*[@id=\"dropdown\"]", "text", "Option 1");
        System.out.println(DictionaryTestHelper.result(result2));

        wait.action(sessionName, "time", 2.0, "xpath", "");

        // Select by INDEX
        DictionaryValue result3 = select.action(sessionName, "css", "#dropdown", "index", "1");
        System.out.println(DictionaryTestHelper.result(result3));

        wait.action(sessionName, "time", 3.0, "xpath", "");

        // Validate selection actually changed
        PlaywrightConnection conn = (PlaywrightConnection) sessionMap.get(sessionName);
        String selectedValue = conn.getPage().inputValue("#dropdown");
        System.out.println("Currently selected value: " + selectedValue);
        assertNotNull(selectedValue);

        // Close browser
        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        DictionaryValue closeResult = close.action(sessionName);
        System.out.println(DictionaryTestHelper.result(closeResult));

        assertFalse(sessionMap.containsKey(sessionName));
    }
}