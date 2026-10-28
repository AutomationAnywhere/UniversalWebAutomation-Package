import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.FullSelfDrivingTypeSafe;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.core.security.SecureString;
import org.testng.annotations.Test;
import org.testng.SkipException;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.testng.AssertJUnit.assertNotNull;

/**
 * Opt-in live-API smoke test. Set TYPESAFE_API_KEY_TEST (and optionally
 * OPENAI_API_KEY_TEST) in the environment to run this against the real services;
 * otherwise it skips cleanly. Never hardcode a real key here.
 */
public class TestFullSelfDrivingTypeSafeLive {

    @Test
    public void testRealLoginFormRun() {
        String typeSafeKey = System.getenv("TYPESAFE_API_KEY_TEST");
        if (typeSafeKey == null || typeSafeKey.isBlank()) {
            throw new SkipException("TYPESAFE_API_KEY_TEST not set - skipping live TypeSafe smoke test");
        }
        String openAiKey = System.getenv("OPENAI_API_KEY_TEST");

        String sessionName = "typesafeLiveTest";
        HashMap<String, Object> sessionMap = new HashMap<>();

        LaunchBrowser launch = new LaunchBrowser();
        launch.setSessions(sessionMap);
        String loginUrl = new File("src/test/resources/typesafe-fixtures/login-form.html").toURI().toString();
        launch.action(sessionName, loginUrl, "true", "chromium");

        // Everything past the launch goes in a try/finally so a failed assertion (or a live-API
        // error) can never leak the browser process.
        try {
            FullSelfDrivingTypeSafe action = new FullSelfDrivingTypeSafe();
            action.setSessions(sessionMap);

            SecureString typeSafeSS = new SecureString(typeSafeKey.toCharArray());
            SecureString openAiSS = (openAiKey == null || openAiKey.isBlank()) ? null : new SecureString(openAiKey.toCharArray());

            Value<?> result = action.action(
                    sessionName, typeSafeSS, openAiSS, "gpt-4o-mini",
                    "Click the Log In button.", null, 10.0, 60.0, 0.4
            );

            DictionaryValue dict = (DictionaryValue) result;
            Map<String, Value> map = dict.get();
            String stopReason = ((StringValue) map.get("stopReason")).get();
            assertNotNull(stopReason);
            System.out.println("Live TypeSafe run stopReason: " + stopReason);
        } finally {
            CloseBrowser close = new CloseBrowser();
            close.setSessions(sessionMap);
            close.action(sessionName);
        }
    }
}
