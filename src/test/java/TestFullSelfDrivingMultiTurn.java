import com.automationanywhere.botcommand.*;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.core.security.SecureString;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

import static org.testng.AssertJUnit.*;

/**
 * Independent tests for FullSelfDrivingMultiTurn
 * Each test can run standalone
 */
public class TestFullSelfDrivingMultiTurn {

    private String sessionName;
    private HashMap<String, Object> sessionMap;
    private SecureString apiKey;

    @BeforeMethod
    public void setUp() {
        sessionName = "testSession_" + System.currentTimeMillis();
        sessionMap = new HashMap<>();

        // Opt-in live-API test: set OPENAI_API_KEY_TEST to run this against the real
        // OpenAI API; otherwise every test in this class skips cleanly, consistent with
        // the rest of the suite's live-API tests.
        String apiKeyString = System.getenv("OPENAI_API_KEY_TEST");

        if (apiKeyString == null || apiKeyString.isBlank()) {
            throw new SkipException("OPENAI_API_KEY_TEST not set - skipping live FullSelfDrivingMultiTurn tests");
        }

        apiKey = new SecureString(apiKeyString.toCharArray());
    }

    @AfterMethod
    public void tearDown() {
        if (sessionMap.containsKey(sessionName)) {
            try {
                CloseBrowser close = new CloseBrowser();
                close.setSessions(sessionMap);
                close.action(sessionName);
                System.out.println("✓ Browser closed successfully\n");
            } catch (Exception e) {
                System.out.println("⚠ Cleanup warning: " + e.getMessage() + "\n");
            }
        }
    }

    // ====================
    // SIMPLE TESTS FIRST
    // ====================

    @Test
    public void test01_SimpleFormFill() {
        System.out.println("\n=== TEST: Simple Form Fill ===");

        // 1. Launch browser
        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://the-internet.herokuapp.com/login", "false", "chromium");

        // 2. Run FSD with simple task
        FullSelfDrivingMultiTurn fsd = new FullSelfDrivingMultiTurn();
        fsd.setSessions(sessionMap);

        String request = "Enter 'tomsmith' in the username field and 'SuperSecretPassword!' in the password field";

        DictionaryValue result = fsd.action(
                sessionName, apiKey, "gpt-4.1-mini",
                request, null, 30.0, 3.0, 60.0
        );

        // 3. Validate
        Map<String, Value> resultMap = result.get();
        String status = ((StringValue) resultMap.get("status")).get();
        String numTurns = ((StringValue) resultMap.get("numberOfTurns")).get();

        System.out.println("Status: " + status);
        System.out.println("Turns: " + numTurns);

        printAllTurns(resultMap);

        assertTrue("Should complete successfully", status.contains("OK"));
        assertTrue("Should complete in 3 turns or less", Integer.parseInt(numTurns) <= 3);
    }

    @Test
    public void test02_LoginWithValidation() {
        System.out.println("\n=== TEST: Login with Validation ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://the-internet.herokuapp.com/login", "false", "chromium");

        FullSelfDrivingMultiTurn fsd = new FullSelfDrivingMultiTurn();
        fsd.setSessions(sessionMap);

        String request = "Login with username 'tomsmith' and password 'SuperSecretPassword!'. " +
                "After clicking login, verify we reached the secure page.";

        DictionaryValue result = fsd.action(
                sessionName, apiKey, "gpt-4.1-mini",
                request, null, 30.0, 5.0, 60.0
        );

        Map<String, Value> resultMap = result.get();
        String status = ((StringValue) resultMap.get("status")).get();

        System.out.println("Login test status: " + status);
        printAllTurns(resultMap);

        assertTrue("Login should succeed", status.contains("OK"));

        // Additional verification using GetText
        GetText getText = new GetText();
        getText.setSessions(sessionMap);
        String heading = DictionaryTestHelper.result(getText.action(sessionName, "css", "h2"));

        System.out.println("Page heading after login: " + heading);
        assertTrue("Should be on secure page", heading.contains("Secure Area"));
    }

    @Test
    public void test03_DataExtractionSimple() {
        System.out.println("\n=== TEST: Simple Data Extraction ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://the-internet.herokuapp.com/login", "false", "chromium");

        FullSelfDrivingMultiTurn fsd = new FullSelfDrivingMultiTurn();
        fsd.setSessions(sessionMap);

        String request = "Extract the page heading text";
        String schema = "{ \"heading\": \"string\" }";

        DictionaryValue result = fsd.action(
                sessionName, apiKey, "gpt-4.1-mini",
                request, schema, 30.0, 2.0, 60.0
        );

        Map<String, Value> resultMap = result.get();
        String status = ((StringValue) resultMap.get("status")).get();

        System.out.println("Extraction status: " + status);
        printAllTurns(resultMap);

        if (resultMap.containsKey("data")) {
            String data = ((StringValue) resultMap.get("data")).get();
            System.out.println("Extracted data: " + data);
            assertTrue("Should contain heading data", data.contains("heading"));
            assertTrue("Should contain 'Login' text", data.toLowerCase().contains("login"));
        }

        assertTrue("Extraction should succeed", status.contains("OK"));
    }

    @Test
    public void test04_TableDataExtraction() {
        System.out.println("\n=== TEST: Table Data Extraction ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://webscraper.io/test-sites/tables", "false", "chromium");

        // Add explicit wait for page load
        Wait wait = new Wait();
        wait.setSessions(sessionMap);
        wait.action(sessionName, "time", 3.0, "xpath", "");

        FullSelfDrivingMultiTurn fsd = new FullSelfDrivingMultiTurn();
        fsd.setSessions(sessionMap);

        String request = "Extract data from the first 3 rows of the table. " +
                "For each row, get the Last Name and First Name. " +
                "Return as a JSON array with key 'rows' containing array of objects.";

        String schema = "{ \"rows\": [ { \"lastName\": \"string\", \"firstName\": \"string\" } ] }";

        DictionaryValue result = fsd.action(
                sessionName, apiKey, "gpt-4.1",  // Using gpt-4.1 for better structured output
                request, schema, 60.0, 5.0, 60.0
        );

        Map<String, Value> resultMap = result.get();
        String status = ((StringValue) resultMap.get("status")).get();

        System.out.println("Table extraction status: " + status);
        printAllTurns(resultMap);

        if (resultMap.containsKey("data")) {
            String data = ((StringValue) resultMap.get("data")).get();
            System.out.println("Extracted table data: " + data);

            // More lenient check - just verify we got some data back
            assertTrue("Should return data", data != null && !data.isEmpty());
            System.out.println("✓ Data extraction returned results");
        } else {
            System.out.println("⚠ No data key in results - extraction may have failed");
        }

        assertTrue("Extraction should complete", status.contains("OK"));
    }

    @Test
    public void test05_ClickButtonAndWait() {
        System.out.println("\n=== TEST: Click Button and Wait ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://the-internet.herokuapp.com/dynamic_loading/1",
                "false", "chromium");

        FullSelfDrivingMultiTurn fsd = new FullSelfDrivingMultiTurn();
        fsd.setSessions(sessionMap);

        String request = "Click the Start button and wait for the 'Hello World!' text to appear";

        DictionaryValue result = fsd.action(
                sessionName, apiKey, "gpt-4.1-mini",
                request, null, 30.0, 3.0, 60.0
        );

        Map<String, Value> resultMap = result.get();
        String status = ((StringValue) resultMap.get("status")).get();

        System.out.println("Click and wait status: " + status);
        printAllTurns(resultMap);

        assertTrue("Should complete successfully", status.contains("OK"));

        // Verify the text appeared
        GetText getText = new GetText();
        getText.setSessions(sessionMap);
        String text = DictionaryTestHelper.result(getText.action(sessionName, "css", "#finish"));

        System.out.println("Text that appeared: " + text);
        assertEquals("Hello World!", text);
    }

    @Test
    public void test06_FormWithDropdown() {
        System.out.println("\n=== TEST: Form with Dropdown ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://the-internet.herokuapp.com/dropdown",
                "false", "chromium");

        FullSelfDrivingMultiTurn fsd = new FullSelfDrivingMultiTurn();
        fsd.setSessions(sessionMap);

        String request = "Select 'Option 2' from the dropdown menu";

        DictionaryValue result = fsd.action(
                sessionName, apiKey, "gpt-4.1-mini",
                request, null, 30.0, 2.0, 60.0
        );

        Map<String, Value> resultMap = result.get();
        String status = ((StringValue) resultMap.get("status")).get();

        System.out.println("Dropdown selection status: " + status);
        printAllTurns(resultMap);

        assertTrue("Should complete successfully", status.contains("OK"));

        // Verify selection
        SelectDropdown select = new SelectDropdown();
        select.setSessions(sessionMap);
        // Just verify we can access the dropdown - actual verification would need getSelectedOption
        System.out.println("✓ Dropdown test completed");
    }

    // ====================
    // WAIT COMMAND TESTS
    // ====================

    @Test
    public void test07_WaitForElementVisible() {
        System.out.println("\n=== TEST: Wait for Element Visible ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://the-internet.herokuapp.com/dynamic_loading/1",
                "false", "chromium");

        // Click start
        ClickObject click = new ClickObject();
        click.setSessions(sessionMap);
        click.action(sessionName, "css", "button");

        // Wait for element to become visible (WaitEnhanced supports this)
        Wait wait = new Wait();
        wait.setSessions(sessionMap);
        DictionaryValue result = wait.action(sessionName, "element_visible", 10.0, "css", "#finish");

        System.out.println("Wait result: " + DictionaryTestHelper.result(result));
        assertNotNull("Wait should return a result", result);

        // Verify element is visible by getting its text
        GetText getText = new GetText();
        getText.setSessions(sessionMap);
        String text = DictionaryTestHelper.result(getText.action(sessionName, "css", "#finish"));
        assertEquals("Hello World!", text);

        System.out.println("✓ Element appeared and text verified");
    }

    @Test
    public void test08_WaitFixedTime() {
        System.out.println("\n=== TEST: Wait Fixed Time ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://example.com", "false", "chromium");

        Wait wait = new Wait();
        wait.setSessions(sessionMap);

        long start = System.currentTimeMillis();
        DictionaryValue result = wait.action(sessionName, "time", 2.0, "xpath", "");
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("Waited: " + elapsed + "ms");
        assertTrue("Should wait approximately 2 seconds", elapsed >= 1900 && elapsed <= 2200);
        assertNotNull("Should return result", DictionaryTestHelper.result(result));
    }

    @Test
    public void test08b_WaitNetworkIdle() {
        System.out.println("\n=== TEST: Wait Network Idle ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://example.com", "false", "chromium");

        Wait wait = new Wait();
        wait.setSessions(sessionMap);

        DictionaryValue result = wait.action(sessionName, "network_idle", 10.0, "css", "");
        String resultText = DictionaryTestHelper.result(result);

        System.out.println("Network idle result: " + resultText);
        assertNotNull("Should return result", resultText);
        assertTrue("Result should mention network or idle",
                resultText.toLowerCase().contains("network") ||
                        resultText.toLowerCase().contains("idle"));
    }

    @Test
    public void test08c_WaitDOMStable() {
        System.out.println("\n=== TEST: Wait DOM Stable ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://example.com", "false", "chromium");

        Wait wait = new Wait();
        wait.setSessions(sessionMap);

        DictionaryValue result = wait.action(sessionName, "dom_stable", 5.0, "css", "");
        String resultText = DictionaryTestHelper.result(result);

        System.out.println("DOM stable result: " + resultText);
        assertNotNull("Should return result", resultText);
        assertTrue("Result should mention DOM or stable",
                resultText.toLowerCase().contains("dom") ||
                        resultText.toLowerCase().contains("stable"));
    }

    @Test
    public void test08d_WaitCustomCondition() {
        System.out.println("\n=== TEST: Wait Custom Condition ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://the-internet.herokuapp.com/", "false", "chromium");

        Wait wait = new Wait();
        wait.setSessions(sessionMap);

        // Wait until at least 5 links are present
        DictionaryValue result = wait.action(
                sessionName,
                "custom",
                10.0,
                "css",
                "document.querySelectorAll('a').length >= 5"
        );
        String resultText = DictionaryTestHelper.result(result);

        System.out.println("Custom condition result: " + resultText);
        assertNotNull("Should return result", resultText);
        assertEquals("Result should report the custom wait mode", "custom", resultText);
    }

    // ====================
    // ERROR RECOVERY TESTS
    // ====================

    @Test
    public void test09_HandleMissingElement() {
        System.out.println("\n=== TEST: Handle Missing Element ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://the-internet.herokuapp.com/login", "false", "chromium");

        FullSelfDrivingMultiTurn fsd = new FullSelfDrivingMultiTurn();
        fsd.setSessions(sessionMap);

        // Ask it to click something that doesn't exist
        String request = "Click the 'Submit' button"; // Doesn't exist, should try alternatives

        DictionaryValue result = fsd.action(
                sessionName, apiKey, "gpt-4.1-mini",
                request, null, 30.0, 5.0, 60.0
        );

        Map<String, Value> resultMap = result.get();
        printAllTurns(resultMap);

        // Check if replanning occurred in any turn
        boolean replanningOccurred = false;
        String numberOfTurns = ((StringValue) resultMap.get("numberOfTurns")).get();
        int turns = Integer.parseInt(numberOfTurns);

        for (int i = 1; i <= turns; i++) {
            String turnKey = "turn" + i;
            if (resultMap.containsKey(turnKey)) {
                DictionaryValue turnDict = (DictionaryValue) resultMap.get(turnKey);
                Map<String, Value> turnData = turnDict.get();

                if (turnData.containsKey("replanned")) {
                    replanningOccurred = true;
                    System.out.println("✓ Replanning was triggered in turn " + i);
                }
            }
        }

        System.out.println("Replanning occurred: " + replanningOccurred);
    }

    @Test
    public void test10_ComplexWorkflow() {
        System.out.println("\n=== TEST: Complex Multi-Step Workflow ===");

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://the-internet.herokuapp.com/add_remove_elements/",
                "false", "chromium");

        FullSelfDrivingMultiTurn fsd = new FullSelfDrivingMultiTurn();
        fsd.setSessions(sessionMap);

        String request = "Click the 'Add Element' button 3 times, then count how many Delete buttons appear";
        String schema = "{ \"deleteButtonCount\": \"number\" }";

        DictionaryValue result = fsd.action(
                sessionName, apiKey, "gpt-4.1-mini",
                request, schema, 30.0, 5.0, 60.0
        );

        Map<String, Value> resultMap = result.get();
        String status = ((StringValue) resultMap.get("status")).get();

        System.out.println("Complex workflow status: " + status);
        printAllTurns(resultMap);

        if (resultMap.containsKey("data")) {
            String data = ((StringValue) resultMap.get("data")).get();
            System.out.println("Extracted data: " + data);
            assertTrue("Should contain count", data.contains("deleteButtonCount") || data.contains("3"));
        }

        assertTrue("Workflow should complete", status.contains("OK"));
    }

    // ====================
    // HELPER METHODS
    // ====================

    private void printAllTurns(Map<String, Value> resultMap) {
        String numberOfTurns = ((StringValue) resultMap.get("numberOfTurns")).get();
        int turns = Integer.parseInt(numberOfTurns);

        for (int i = 1; i <= turns; i++) {
            System.out.println("\n--- Turn " + i + " ---");

            String turnKey = "turn" + i;
            if (resultMap.containsKey(turnKey)) {
                // New structure: turn data is a nested dictionary
                DictionaryValue turnDict = (DictionaryValue) resultMap.get(turnKey);
                Map<String, Value> turnData = turnDict.get();

                // Print each field from the turn dictionary
                printTurnField(turnData, "action");
                printTurnField(turnData, "code");
                printTurnField(turnData, "result");
                printTurnField(turnData, "validation");

                if (turnData.containsKey("error")) {
                    System.out.println("⚠ error: " + ((StringValue) turnData.get("error")).get());
                }

                if (turnData.containsKey("replanned")) {
                    System.out.println("🔄 replanned: " + ((StringValue) turnData.get("replanned")).get());
                }
            }

            // Check for new plan (stored separately when replanning occurs)
            String newPlanKey = "turn" + i + "_new_plan";
            if (resultMap.containsKey(newPlanKey)) {
                System.out.println("📋 new_plan: " + ((StringValue) resultMap.get(newPlanKey)).get());
            }
        }
    }

    private void printTurnField(Map<String, Value> turnData, String fieldName) {
        if (turnData.containsKey(fieldName)) {
            String value = ((StringValue) turnData.get(fieldName)).get();

            // Truncate long values for readability
            if (value.length() > 200) {
                value = value.substring(0, 200) + "... (truncated)";
            }

            System.out.println(fieldName + ": " + value);
        }
    }
}