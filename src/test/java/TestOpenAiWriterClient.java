import com.automationanywhere.botcommand.utils.OpenAiWriterClient;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.testng.AssertJUnit.*;

public class TestOpenAiWriterClient {

    private HttpServer stubReturning(String content) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                String body = "{\"choices\":[{\"message\":{\"content\":" + com.google.gson.Gson.class
                        .getMethod("toJson", Object.class).invoke(new com.google.gson.Gson(), content) + "}}]}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        server.start();
        return server;
    }

    @Test
    public void testPlanPageStepParsesFieldsAndClicksInOrder() throws Exception {
        HttpServer server = stubReturning("{\"done\": false, \"reason\": \"\", \"fields\": "
                + "{\"Title\": \"Deck\", \"Priority\": \"High\"}, \"clicks\": [\"Save Task\", \"Log Out\"]}");
        try {
            OpenAiWriterClient writer = new OpenAiWriterClient("key", "gpt-4o-mini",
                    "http://localhost:" + server.getAddress().getPort());
            OpenAiWriterClient.PagePlan plan = writer.planPageStep("goal", "page", Collections.emptyList()).get();
            assertFalse(plan.done);
            assertEquals(java.util.List.of("Title", "Priority"), new java.util.ArrayList<>(plan.fields.keySet()));
            assertEquals(java.util.List.of("Save Task", "Log Out"), plan.clicks);
            assertEquals("", plan.navigate);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testPlanPageStepFillsInMissingKeys() throws Exception {
        HttpServer server = stubReturning("{\"done\": true}");
        try {
            OpenAiWriterClient writer = new OpenAiWriterClient("key", "gpt-4o-mini",
                    "http://localhost:" + server.getAddress().getPort());
            OpenAiWriterClient.PagePlan plan = writer.planPageStep("goal", "page", Collections.emptyList()).get();
            assertTrue(plan.done);
            assertTrue(plan.fields.isEmpty());
            assertTrue(plan.clicks.isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testPlanPageStepReturnsEmptyOnNonJson() throws Exception {
        HttpServer server = stubReturning("I think you should click Save.");
        try {
            OpenAiWriterClient writer = new OpenAiWriterClient("key", "gpt-4o-mini",
                    "http://localhost:" + server.getAddress().getPort());
            assertFalse(writer.planPageStep("goal", "page", Collections.emptyList()).isPresent());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testExtractFinalAnswerReturnsNullOnFailureInsteadOfThrowing() {
        // Port 1 is reserved and will refuse the connection, simulating an outage.
        OpenAiWriterClient writer = new OpenAiWriterClient("key", "gpt-4o-mini", "http://localhost:1");
        String result = writer.extractFinalAnswer("goal", "{}", "some page text");
        assertNull(result);
    }
}
