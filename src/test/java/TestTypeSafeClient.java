import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.TypeSafeClient;
import com.automationanywhere.botcommand.utils.TypeSafeTypes;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.AssertJUnit.*;

public class TestTypeSafeClient {

    private static TypeSafeTypes.Request sampleRequest() {
        TypeSafeTypes.Request req = new TypeSafeTypes.Request();
        req.state = "goal: click login\nitems: 1) button 'Login'";
        TypeSafeTypes.ChoiceQuestion actionQ = new TypeSafeTypes.ChoiceQuestion();
        actionQ.instructions = "which action";
        actionQ.criteria = new LinkedHashMap<>();
        actionQ.criteria.put("click_item", "click the item");
        actionQ.criteria.put("done", "task finished");
        req.questions.put("action_type", actionQ);
        return req;
    }

    @Test
    public void testParsesSuccessfulResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = "{\"model\":\"jev-latest\",\"answers\":{\"action_type\":{" +
                    "\"type\":\"choice\",\"choice\":\"click_item\"," +
                    "\"probabilities\":{\"click_item\":0.9,\"done\":0.1},\"confidence\":0.8}}}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            TypeSafeClient client = new TypeSafeClient("test-key", "http://localhost:" + server.getAddress().getPort());
            TypeSafeTypes.Response resp = client.classify(sampleRequest());
            assertEquals("click_item", resp.answers.get("action_type").choice);
            assertEquals(0.8, resp.answers.get("action_type").confidence, 0.0001);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testAbortsImmediatelyOn401() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            byte[] bytes = "unauthorized".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            TypeSafeClient client = new TypeSafeClient("bad-key", "http://localhost:" + server.getAddress().getPort());
            try {
                client.classify(sampleRequest());
                fail("expected BotCommandException");
            } catch (BotCommandException e) {
                assertTrue(e.getMessage().toLowerCase().contains("api key"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testRetriesOnceOn5xxThenSucceeds() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            if (callCount.getAndIncrement() == 0) {
                byte[] bytes = "server error".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } else {
                String body = "{\"model\":\"jev-latest\",\"answers\":{\"action_type\":{" +
                        "\"type\":\"choice\",\"choice\":\"done\",\"probabilities\":{\"done\":1.0},\"confidence\":0.95}}}";
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            }
        });
        server.start();
        try {
            TypeSafeClient client = new TypeSafeClient("test-key", "http://localhost:" + server.getAddress().getPort());
            TypeSafeTypes.Response resp = client.classify(sampleRequest());
            assertEquals("done", resp.answers.get("action_type").choice);
            assertEquals(2, callCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testMalformedBodyGivesClearErrorNotNpe() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            // 200 OK, but the body is not the classifier response the runner expects.
            byte[] bytes = "not json at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            TypeSafeClient client = new TypeSafeClient("test-key", "http://localhost:" + server.getAddress().getPort());
            try {
                client.classify(sampleRequest());
                fail("expected BotCommandException");
            } catch (BotCommandException e) {
                assertNotNull(e.getMessage());
                assertTrue("message should name TypeSafe: " + e.getMessage(),
                        e.getMessage().contains("TypeSafe"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testResponseMissingActionTypeAnswerGivesClearError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            // Well-formed JSON, but with no answers for the questions we asked.
            byte[] bytes = "{\"model\":\"jev-latest\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            TypeSafeClient client = new TypeSafeClient("test-key", "http://localhost:" + server.getAddress().getPort());
            try {
                client.classify(sampleRequest());
                fail("expected BotCommandException");
            } catch (BotCommandException e) {
                assertTrue("message should name action_type: " + e.getMessage(),
                        e.getMessage().contains("action_type"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testAnswersToAnyQuestionNamesAreAccepted() throws Exception {
        // The runner asks e.g. "f0"/"c" questions, not only "action_type"; a correct answer to
        // exactly what was asked must be accepted (a hard-coded action_type check rejected every
        // real call once the runner stopped asking action_type).
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            byte[] bytes = ("{\"model\":\"jev-latest\",\"answers\":{\"f0\":{\"type\":\"choice\",\"choice\":\"2\","
                    + "\"probabilities\":{\"2\":0.9},\"confidence\":0.9}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            TypeSafeTypes.Request req = new TypeSafeTypes.Request();
            req.state = "s";
            TypeSafeTypes.ChoiceQuestion q = new TypeSafeTypes.ChoiceQuestion();
            q.instructions = "which field?";
            q.criteria.put("2", "textbox \"Title\"");
            req.questions.put("f0", q);
            TypeSafeClient client = new TypeSafeClient("test-key", "http://localhost:" + server.getAddress().getPort());
            assertEquals("2", client.classify(req).answers.get("f0").choice);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testAbortsAfterRetryExhaustedOnRepeated5xx() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/systemone", exchange -> {
            byte[] bytes = "server error".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            TypeSafeClient client = new TypeSafeClient("test-key", "http://localhost:" + server.getAddress().getPort());
            try {
                client.classify(sampleRequest());
                fail("expected BotCommandException");
            } catch (BotCommandException expected) {
                // ok
            }
        } finally {
            server.stop(0);
        }
    }
}
