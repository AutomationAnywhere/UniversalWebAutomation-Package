import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.FullSelfDrivingTypeSafe;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.core.security.SecureString;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.testng.AssertJUnit.*;

public class TestFullSelfDrivingTypeSafeGuardrails {

    @Test
    public void testMaxStepsIsCappedAt50EvenIfHigherRequested() {
        int capped = FullSelfDrivingTypeSafe.validateMaxSteps(9999.0);
        assertEquals(50, capped);
    }

    @Test
    public void testMaxStepsBelowCapPassesThrough() {
        int value = FullSelfDrivingTypeSafe.validateMaxSteps(10.0);
        assertEquals(10, value);
    }

    @Test
    public void testMaxStepsZeroOrNegativeRejected() {
        try {
            FullSelfDrivingTypeSafe.validateMaxSteps(0.0);
            fail("expected BotCommandException");
        } catch (BotCommandException expected) { /* ok */ }

        try {
            FullSelfDrivingTypeSafe.validateMaxSteps(-5.0);
            fail("expected BotCommandException");
        } catch (BotCommandException expected) { /* ok */ }
    }

    @Test
    public void testMaxRunSecondsZeroOrNegativeRejected() {
        try {
            FullSelfDrivingTypeSafe.validateMaxRunSeconds(0.0);
            fail("expected BotCommandException");
        } catch (BotCommandException expected) { /* ok */ }

        try {
            FullSelfDrivingTypeSafe.validateMaxRunSeconds(-1.0);
            fail("expected BotCommandException");
        } catch (BotCommandException expected) { /* ok */ }
    }

    @Test
    public void testMaxRunSecondsAboveThirtyMinutesStillAccepted() {
        // No upper ceiling -- must return the value unchanged (a warn is logged, not an error).
        long millis = FullSelfDrivingTypeSafe.validateMaxRunSeconds(3600.0);
        assertEquals(3_600_000L, millis);
    }

    @Test
    public void testMissingOpenAiKeyStopsWithAClearStatus() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress(0), 0);
        int[] callCount = {0};
        stub.createContext("/v1/systemone", exchange -> {
            String body;
            if (callCount[0]++ == 0) {
                // First step: ask for type_text -- this will no-op since no OpenAI key is mapped.
                body = "{\"model\":\"jev-latest\",\"answers\":{" +
                        "\"action_type\":{\"type\":\"choice\",\"choice\":\"type_text\",\"probabilities\":{\"type_text\":0.9},\"confidence\":0.9}," +
                        "\"target_item\":{\"type\":\"choice\",\"choice\":\"1\",\"probabilities\":{\"1\":0.9},\"confidence\":0.9}}}";
            } else {
                body = "{\"model\":\"jev-latest\",\"answers\":{" +
                        "\"action_type\":{\"type\":\"choice\",\"choice\":\"done\",\"probabilities\":{\"done\":1.0},\"confidence\":0.95}}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        stub.start();

        try {
            String sessionName = "typesafeNoOpenAiTest";
            HashMap<String, Object> sessionMap = new HashMap<>();

            LaunchBrowser launch = new LaunchBrowser();
            launch.setSessions(sessionMap);
            String loginUrl = new File("src/test/resources/typesafe-fixtures/login-form.html").toURI().toString();
            launch.action(sessionName, loginUrl, "true", "chromium");

            // Everything past the launch goes in a try/finally so a failed assertion can never
            // leak the browser process.
            try {
                FullSelfDrivingTypeSafe action = new FullSelfDrivingTypeSafe();
                action.setSessions(sessionMap);
                action.setTypeSafeBaseUrlOverrideForTests("http://localhost:" + stub.getAddress().getPort());

                SecureString typeSafeKey = new SecureString("test-key".toCharArray());

                Value<?> result = action.action(
                        sessionName, typeSafeKey, null, "gpt-4o-mini",
                        "type into username", null, 15.0, 60.0, 0.4
                );

                DictionaryValue dict = (DictionaryValue) result;
                Map<String, Value> map = dict.get();
                // The OpenAI model plans every page, so without it nothing can be done - the run
                // must stop at once with a clear status, not throw and not burn TypeSafe calls.
                assertEquals("no_writer", ((StringValue) map.get("stopReason")).get());
                assertTrue(((StringValue) map.get("status")).get().startsWith("ERROR: an OpenAI key is required"));
                assertEquals("0", ((StringValue) map.get("numberOfSteps")).get());
                assertEquals(0, callCount[0]);
            } finally {
                CloseBrowser close = new CloseBrowser();
                close.setSessions(sessionMap);
                close.action(sessionName);
            }
        } finally {
            stub.stop(0);
        }
    }
}
