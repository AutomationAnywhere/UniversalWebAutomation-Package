import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.FullSelfDrivingTypeSafe;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.core.security.SecureString;
import org.testng.annotations.Test;
import org.testng.SkipException;

import java.util.HashMap;
import java.util.Map;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

/**
 * Opt-in live-API smoke test. Set TYPESAFE_API_KEY_TEST (and optionally
 * OPENAI_API_KEY_TEST) to run this against the real services; otherwise it skips
 * cleanly. Never hardcode a real key here.
 *
 * rpachallenge.com is a real-world multi-field form-fill benchmark (Angular app,
 * shuffled field positions, fields identified only by an ng-reflect-name debug
 * attribute rather than aria-label/placeholder/name/id). It exercises the exact
 * combination that previously broke FSD TypeSafe: indistinguishable field labels,
 * a submit button the classifier could mistake for a text field, and multi-field
 * form-fill sequences that a target-blind stall detector would misread as stuck.
 */
public class TestFullSelfDrivingTypeSafeLiveRpaChallenge {

    @Test
    public void testFillAndSubmitOneRound() {
        String typeSafeKey = System.getenv("TYPESAFE_API_KEY_TEST");
        if (typeSafeKey == null || typeSafeKey.isBlank()) {
            throw new SkipException("TYPESAFE_API_KEY_TEST not set - skipping live TypeSafe smoke test");
        }
        String openAiKey = System.getenv("OPENAI_API_KEY_TEST");

        String sessionName = "rpaChallengeLiveTest";
        HashMap<String, Object> sessionMap = new HashMap<>();

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        launch.action(sessionName, "https://www.rpachallenge.com/", "true", "chromium");

        try {
            FullSelfDrivingTypeSafe action = new FullSelfDrivingTypeSafe();
            action.setSessions(sessionMap);

            SecureString typeSafeSS = new SecureString(typeSafeKey.toCharArray());
            SecureString openAiSS = (openAiKey == null || openAiKey.isBlank()) ? null : new SecureString(openAiKey.toCharArray());

            String goal = "Click the start button, then enter the following data into the form before clicking submit:\n\n" +
                    "First Name: John\n" +
                    "Last Name: Smith\n" +
                    "Company Name: IT Solutions\n" +
                    "Role in Company: Analyst\n" +
                    "Address: 98 North Road\n" +
                    "Email: jsmith@itsolutions.co.uk\n" +
                    "Phone: 40716543298\n\n" +
                    "Once all of that data has been entered, press the submit button.";

            // 20 steps is comfortably more than one fill-and-submit round needs (~10
            // steps observed), enough margin to absorb an occasional wrong-target retry
            // without needing all 10 of the site's auto-repeating rounds to complete.
            Value<?> result = action.action(
                    sessionName, typeSafeSS, openAiSS, "gpt-5-mini",
                    goal, null, 20.0, 120.0, 0.35
            );

            DictionaryValue dict = (DictionaryValue) result;
            Map<String, Value> map = dict.get();

            String status = str(map, "status");
            String stopReason = str(map, "stopReason");
            int numberOfSteps = Integer.parseInt(str(map, "numberOfSteps"));

            System.out.println("RPA Challenge run - status: " + status + ", stopReason: " + stopReason
                    + ", steps: " + numberOfSteps + ", reason: " + str(map, "reason")
                    + ", typeSafeInputTokens: " + str(map, "typeSafeInputTokens"));
            for (int i = 1; i <= numberOfSteps; i++) {
                System.out.println("  " + i + ": " + ((DictionaryValue) map.get("step" + i)).get());
            }

            assertEquals("OK", status);
            // A run that stalls or drops below minConfidence this early means the classifier
            // lost track of the task - the exact failure mode this test guards against.
            assertTrue("Run should not stall or lose confidence within the step budget: " + stopReason,
                    !"stalled".equals(stopReason) && !"low_confidence".equals(stopReason));

            boolean submitted = false;
            for (int i = 1; i <= numberOfSteps; i++) {
                DictionaryValue stepDict = (DictionaryValue) map.get("step" + i);
                Map<String, Value> stepMap = stepDict.get();
                if ("click_item".equals(str(stepMap, "actionType"))
                        && str(stepMap, "target").contains("Submit")
                        && "OK".equals(str(stepMap, "result"))) {
                    submitted = true;
                    break;
                }
            }
            assertTrue("Should have successfully clicked Submit at least once", submitted);
        } finally {
            CloseBrowser close = new CloseBrowser();
            close.setSessions(sessionMap);
            close.action(sessionName);
        }
    }

    private static String str(Map<String, Value> map, String key) {
        Value v = map.get(key);
        return v == null ? "null" : ((StringValue) v).get();
    }
}
