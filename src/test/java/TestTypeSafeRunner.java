import com.automationanywhere.botcommand.utils.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.testng.AssertJUnit.*;

/**
 * FSD TypeSafe's runner: the planner (a stubbed OpenAI endpoint here) returns page steps,
 * fields/clicks are matched to controls exactly or via TypeSafe (a scripted fake), and
 * Playwright performs them on local fixture pages. No network.
 */
public class TestTypeSafeRunner {

    /** Scripted planner: returns the given plan JSONs in order (the last one repeats) and records prompts. */
    static class StubPlanner implements AutoCloseable {
        final HttpServer server;
        final List<String> prompts = new ArrayList<>();

        StubPlanner(String... plans) throws Exception {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/chat/completions", ex -> {
                JsonObject req = new Gson().fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                String user = req.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
                String plan;
                synchronized (prompts) {
                    prompts.add(user);
                    plan = plans[Math.min(prompts.size() - 1, plans.length - 1)];
                }
                JsonObject msg = new JsonObject();
                msg.addProperty("content", plan);
                JsonObject choice = new JsonObject();
                choice.add("message", msg);
                JsonObject body = new JsonObject();
                com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
                choices.add(choice);
                body.add("choices", choices);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            });
            server.start();
        }

        OpenAiWriterClient writer() {
            return new OpenAiWriterClient("key", "test-model", "http://localhost:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Scripted TypeSafe: answers every question with the given choice; records requests. */
    static class FakeTypeSafe implements TypeSafeDecisionSource {
        final List<TypeSafeTypes.Request> requests = new ArrayList<>();
        final java.util.function.Function<TypeSafeTypes.ChoiceQuestion, String> chooser;
        final double confidence;

        FakeTypeSafe(java.util.function.Function<TypeSafeTypes.ChoiceQuestion, String> chooser, double confidence) {
            this.chooser = chooser;
            this.confidence = confidence;
        }

        @Override
        public TypeSafeTypes.Response classify(TypeSafeTypes.Request request) {
            requests.add(request);
            TypeSafeTypes.Response r = new TypeSafeTypes.Response();
            r.answers = new LinkedHashMap<>();
            request.questions.forEach((k, q) -> {
                TypeSafeTypes.ChoiceAnswer a = new TypeSafeTypes.ChoiceAnswer();
                a.choice = chooser.apply(q);
                a.confidence = confidence;
                r.answers.put(k, a);
            });
            r.usage = new TypeSafeTypes.Usage();
            r.usage.input_tokens = 100;
            return r;
        }

        /** Picks the criterion whose description contains the text. */
        static FakeTypeSafe picking(String descriptionContains) {
            return new FakeTypeSafe(q -> q.criteria.entrySet().stream()
                    .filter(e -> e.getValue().contains(descriptionContains)).map(Map.Entry::getKey)
                    .findFirst().orElse("none"), 0.9);
        }
    }

    static boolean isRouting(TypeSafeTypes.ChoiceQuestion q) {
        return q.instructions.startsWith("Which single move") || q.instructions.startsWith("If the next move is a click");
    }

    /** Abstains on routing (hands every round to the planner); any field/click matching call fails the test. */
    static final FakeTypeSafe UNUSED_TYPESAFE = new FakeTypeSafe(q -> {
        if (isRouting(q)) return "none";
        throw new AssertionError("TypeSafe should not be called");
    }, 0.9);

    private static String fixture(String name) {
        return new File("src/test/resources/typesafe-fixtures/" + name).toURI().toString();
    }

    private interface PageTest { void run(Page page) throws Exception; }

    private static void withPage(String fixtureName, PageTest body) throws Exception {
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixture(fixtureName));
            body.run(page);
            browser.close();
        }
    }

    private static final String DONE = "{\"done\": true, \"reason\": \"all done\", \"fields\": {}, \"clicks\": []}";

    @Test
    public void exactLabelsAreResolvedWithoutCallingTypeSafe() throws Exception {
        try (StubPlanner planner = new StubPlanner(
                "{\"done\": false, \"fields\": {\"Username\": \"alice\", \"Password\": \"pw\"}, \"clicks\": [\"Log In\"]}", DONE)) {
            withPage("login-form.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(),
                        "log in", null, 10, 60_000, 0.4);
                assertEquals("done", r.stopReason);
                assertEquals("all done", r.reason);
                assertEquals("alice", page.inputValue("#username"));
                assertEquals("pw", page.inputValue("#password"));
                assertEquals(List.of("type_text", "type_text", "click_item"),
                        r.steps.stream().map(s -> s.actionType).collect(java.util.stream.Collectors.toList()));
                assertEquals(2, r.plannerRounds);   // the fill round, then the round that says done
            });
        }
    }

    @Test
    public void historyShowsEnteredValuesButNeverPasswords() throws Exception {
        try (StubPlanner planner = new StubPlanner(
                "{\"done\": false, \"fields\": {\"Username\": \"alice\", \"Password\": \"hunter2-secret\"}, \"clicks\": []}", DONE)) {
            withPage("login-form.html", page -> {
                new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(), "log in", null, 10, 60_000, 0.4);
                String second = planner.prompts.get(1);
                assertTrue(second, second.contains("filled Username = 'alice'"));
                assertFalse("password must not be echoed back", second.contains("hunter2-secret"));
            });
        }
    }

    @Test
    public void fuzzyLabelGoesToTypeSafeWithANoneOptionAndNeverSendsTheValue() throws Exception {
        FakeTypeSafe ts = FakeTypeSafe.picking("\"Username\"");
        try (StubPlanner planner = new StubPlanner(
                "{\"done\": false, \"fields\": {\"login name\": \"s3cret-value\"}, \"clicks\": []}", DONE)) {
            withPage("login-form.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, ts, planner.writer(), "sign in as me", null, 10, 60_000, 0.4);
                assertEquals("s3cret-value", page.inputValue("#username"));
                assertEquals(1, ts.requests.size());
                String sent = new Gson().toJson(ts.requests.get(0));
                assertFalse("field values must never reach TypeSafe", sent.contains("s3cret-value"));
                assertTrue(ts.requests.get(0).questions.values().iterator().next().criteria.containsKey("none"));
                assertEquals(100, r.typeSafeInputTokens);
            });
        }
    }

    @Test
    public void noneIsReportedToThePlannerInsteadOfGuessing() throws Exception {
        FakeTypeSafe ts = new FakeTypeSafe(q -> "none", 0.95);
        try (StubPlanner planner = new StubPlanner(
                "{\"done\": false, \"fields\": {\"Shipping address\": \"1 Main St\"}, \"clicks\": []}", DONE)) {
            withPage("login-form.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, ts, planner.writer(), "ship it", null, 10, 60_000, 0.4);
                assertEquals("", page.inputValue("#username"));
                assertTrue(planner.prompts.get(1).contains("PROBLEM: No field for 'Shipping address'"));
                assertEquals("done", r.stopReason);
            });
        }
    }

    @Test
    public void lowConfidenceIsReportedNotActedOn() throws Exception {
        FakeTypeSafe ts = new FakeTypeSafe(q -> q.criteria.keySet().iterator().next(), 0.2);
        try (StubPlanner planner = new StubPlanner("{\"done\": false, \"fields\": {\"login name\": \"bob\"}, \"clicks\": []}", DONE)) {
            withPage("login-form.html", page -> {
                new TypeSafeRunner().run(page, ts, planner.writer(), "x", null, 10, 60_000, 0.4);
                assertEquals("", page.inputValue("#username"));
                assertTrue(planner.prompts.get(1).contains("Unsure which field is 'login name'"));
            });
        }
    }

    @Test
    public void rowScopedClickActsOnTheNamedRowOnly() throws Exception {
        try (StubPlanner planner = new StubPlanner(
                "{\"done\": false, \"fields\": {}, \"clicks\": [\"Delete in row 'Archive old invoices'\"]}", DONE)) {
            withPage("task-rows.html", page -> {
                new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(), "delete Low tasks", null, 10, 60_000, 0.4);
                assertEquals("Renew SSL certificate",
                        String.valueOf(page.evaluate("() => [...document.querySelectorAll('td:first-child')].map(td => td.innerText).join(',')")));
            });
        }
    }

    @Test
    public void plannerSeesPageTextAndRowContext() throws Exception {
        try (StubPlanner planner = new StubPlanner(DONE)) {
            withPage("task-rows.html", page -> {
                new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(), "look", null, 10, 60_000, 0.4);
                String prompt = planner.prompts.get(0);
                assertTrue(prompt.contains("PAGE TEXT:"));
                assertTrue(prompt.contains("Archive old invoices"));
                assertTrue(prompt, prompt.contains("click \"Delete\" [in row: Archive old invoices Low Delete]"));
            });
        }
    }

    @Test
    public void dropdownOptionIsChosenByText() throws Exception {
        try (StubPlanner planner = new StubPlanner("{\"done\": false, \"fields\": {\"color-select\": \"Green\"}, \"clicks\": []}", DONE)) {
            withPage("dropdown.html", page -> {
                new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(), "pick green", null, 10, 60_000, 0.4);
                assertEquals("green", page.inputValue("#color-select"));
            });
        }
    }

    @Test
    public void approximateDropdownValueGoesToTypeSafe() throws Exception {
        FakeTypeSafe ts = new FakeTypeSafe(q -> q.criteria.entrySet().stream()
                .filter(e -> e.getValue().equals("Blue")).map(Map.Entry::getKey).findFirst().orElse("none"), 0.9);
        try (StubPlanner planner = new StubPlanner("{\"done\": false, \"fields\": {\"color-select\": \"navy\"}, \"clicks\": []}", DONE)) {
            withPage("dropdown.html", page -> {
                new TypeSafeRunner().run(page, ts, planner.writer(), "pick a dark color", null, 10, 60_000, 0.4);
                assertEquals("blue", page.inputValue("#color-select"));
            });
        }
    }

    @Test
    public void reactControlledInputReceivesTheValue() throws Exception {
        try (StubPlanner planner = new StubPlanner("{\"done\": false, \"fields\": {\"Title\": \"Deck\"}, \"clicks\": []}", DONE)) {
            withPage("react-controlled-input.html", page -> {
                new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(), "title it", null, 10, 60_000, 0.4);
                assertEquals("Deck", String.valueOf(page.evaluate("() => window.committed")));
            });
        }
    }

    @Test
    public void contentEditableFieldIsFilled() throws Exception {
        try (StubPlanner planner = new StubPlanner("{\"done\": false, \"fields\": {\"Notes\": \"hello\"}, \"clicks\": []}", DONE)) {
            withPage("contenteditable.html", page -> {
                new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(), "note", null, 10, 60_000, 0.4);
                assertEquals("hello", page.innerText("#editor"));
            });
        }
    }

    @Test
    public void unassociatedVisualLabelsNameTheirInputs() throws Exception {
        try (StubPlanner planner = new StubPlanner(
                "{\"done\": false, \"fields\": {\"First Name\": \"John\", \"Email\": \"j@x.co\"}, \"clicks\": [\"Submit\"]}", DONE)) {
            withPage("visual-label-form.html", page -> {
                new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(), "fill", null, 10, 60_000, 0.4);
                assertEquals("John", page.inputValue("[name=x7Kq2]"));
                assertEquals("j@x.co", page.inputValue("[name=p0Zr9]"));
                assertEquals("submitted", page.title());
            });
        }
    }

    @Test
    public void stopsAsStalledAfterThreeRoundsWhereNothingCouldBeDone() throws Exception {
        FakeTypeSafe ts = new FakeTypeSafe(q -> "none", 0.9);
        try (StubPlanner planner = new StubPlanner("{\"done\": false, \"fields\": {}, \"clicks\": [\"Checkout\"]}")) {
            withPage("login-form.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, ts, planner.writer(), "buy", null, 10, 60_000, 0.4);
                assertEquals("stalled", r.stopReason);
                assertEquals(3, planner.prompts.size());
                assertTrue(r.reason.contains("Nothing on this page matches the click 'Checkout'"));
            });
        }
    }

    @Test
    public void doneIsAuditedOnceAndTheAuditCanSendTheRunBackToWork() throws Exception {
        // 1st reply claims done; the audit finds work left and returns a step; then done twice.
        try (StubPlanner planner = new StubPlanner(DONE,
                "{\"done\": false, \"reason\": \"username not entered\", \"fields\": {\"Username\": \"alice\"}, \"clicks\": []}",
                DONE, DONE)) {
            withPage("login-form.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(), "enter alice", null, 10, 60_000, 0.4);
                assertEquals("alice", page.inputValue("#username"));
                assertEquals("done", r.stopReason);
                assertTrue(planner.prompts.get(1).contains("The GOAL looks done"));
                assertTrue(planner.prompts.get(3).contains("The GOAL looks done"));
                assertEquals(4, planner.prompts.size());
            });
        }
    }

    @Test
    public void fieldsRebuiltByAnEarlierSelectionAreStillFilled() throws Exception {
        try (StubPlanner planner = new StubPlanner(
                "{\"done\": false, \"fields\": {\"Category\": \"Transport\", \"Merchant\": \"Uber\"}, \"clicks\": []}", DONE, DONE)) {
            withPage("rerender-form.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(), "x", null, 10, 60_000, 0.4);
                assertEquals("Uber", page.inputValue("#m"));
                assertFalse(String.join("\n", r.history), String.join("\n", r.history).contains("PROBLEM"));
            });
        }
    }

    @Test
    public void stepLimitStopsTheRun() throws Exception {
        try (StubPlanner planner = new StubPlanner("{\"done\": false, \"fields\": {\"Username\": \"a\"}, \"clicks\": []}")) {
            withPage("login-form.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, UNUSED_TYPESAFE, planner.writer(), "x", null, 2, 60_000, 0.4);
                assertEquals("step_limit", r.stopReason);
                assertEquals(2, planner.prompts.size());
            });
        }
    }

    @Test
    public void withoutAWriterTheRunStopsImmediatelyWithAClearStatus() throws Exception {
        withPage("login-form.html", page -> {
            TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, UNUSED_TYPESAFE, null, "x", null, 10, 60_000, 0.4);
            assertEquals("no_writer", r.stopReason);
            assertTrue(r.status.startsWith("ERROR: an OpenAI key is required"));
        });
    }

    // ── TypeSafe-first routing ──────────────────────────────────────────────

    /** Routes every round to "click" on the control whose description contains the text (else abstains). */
    static FakeTypeSafe clicking(String descriptionContains, double confidence) {
        return new FakeTypeSafe(q -> {
            if (q.instructions.startsWith("Which single move")) return "click";
            return q.criteria.entrySet().stream().filter(e -> e.getValue().contains(descriptionContains))
                    .map(Map.Entry::getKey).findFirst().orElse("none");
        }, confidence);
    }

    @Test
    public void confidentClickOnAPageWithNothingToFillNeedsNoPlannerCall() throws Exception {
        FakeTypeSafe ts = clicking("[in row: Archive old invoices", 0.9);
        try (StubPlanner planner = new StubPlanner(DONE)) {
            withPage("task-rows.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, ts, planner.writer(), "delete the Low task", null, 10, 60_000, 0.4);
                assertEquals("Renew SSL certificate",
                        String.valueOf(page.evaluate("() => [...document.querySelectorAll('td:first-child')].map(td => td.innerText).join(',')")));
                assertEquals(1, r.classifierRounds);
                assertTrue(planner.prompts.get(0), planner.prompts.get(0).contains("clicked Delete [row: Archive old invoices"));
            });
        }
    }

    @Test
    public void unsureClickGoesToThePlannerInstead() throws Exception {
        FakeTypeSafe ts = clicking("[in row: Archive old invoices", 0.5);   // below the click bar
        try (StubPlanner planner = new StubPlanner(DONE)) {
            withPage("task-rows.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, ts, planner.writer(), "x", null, 10, 60_000, 0.4);
                assertEquals(0, r.classifierRounds);
                assertEquals(2L, (long) page.locator("td:first-child").count());
            });
        }
    }

    @Test
    public void pagesWithAFormToFillGoStraightToThePlanner() throws Exception {
        FakeTypeSafe ts = new FakeTypeSafe(q -> { throw new AssertionError("no routing on a form page"); }, 0.9);
        try (StubPlanner planner = new StubPlanner(DONE)) {
            withPage("login-form.html", page -> {
                new TypeSafeRunner().run(page, ts, planner.writer(), "x", null, 10, 60_000, 0.4);
                assertTrue(ts.requests.isEmpty());
            });
        }
    }

    @Test
    public void typeSafeDoneIsAuditedByThePlannerBeforeStopping() throws Exception {
        FakeTypeSafe ts = new FakeTypeSafe(q -> q.instructions.startsWith("Which single move") ? "done" : "none", 0.9);
        try (StubPlanner planner = new StubPlanner(DONE)) {
            withPage("task-rows.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, ts, planner.writer(), "x", null, 10, 60_000, 0.4);
                assertEquals("done", r.stopReason);
                assertEquals(1, planner.prompts.size());
                assertTrue(planner.prompts.get(0).contains("The GOAL looks done"));
            });
        }
    }

    @Test
    public void aClickThatChangesNothingHandsTheNextRoundToThePlanner() throws Exception {
        FakeTypeSafe ts = clicking("\"Refresh\"", 0.95);
        try (StubPlanner planner = new StubPlanner(DONE)) {
            withPage("inert-button.html", page -> {
                TypeSafeRunner.RunResult r = new TypeSafeRunner().run(page, ts, planner.writer(), "x", null, 10, 60_000, 0.4);
                assertEquals(1, r.classifierRounds);
                assertTrue(planner.prompts.get(0), planner.prompts.get(0).contains("Refresh (TypeSafe) - the page did not change"));
            });
        }
    }

    @Test
    public void typedValuesNeverReachTypeSafeRouting() throws Exception {
        FakeTypeSafe ts = new FakeTypeSafe(q -> "none", 0.9);
        try (StubPlanner planner = new StubPlanner(
                "{\"done\": false, \"fields\": {\"Username\": \"secret-xyz\", \"Password\": \"pw-123\"}, \"clicks\": []}", DONE)) {
            withPage("login-form.html", page -> {
                new TypeSafeRunner().run(page, ts, planner.writer(), "x", null, 10, 60_000, 0.4);
                assertFalse(ts.requests.isEmpty());   // round 2 had nothing left to fill: routed
                String sent = new Gson().toJson(ts.requests);
                assertTrue(sent, sent.contains("filled Username"));
                assertFalse(sent.contains("secret-xyz"));
                assertFalse(sent.contains("pw-123"));
            });
        }
    }

    @Test
    public void repeatedControlsAreOfferedToTypeSafeOnce() throws Exception {
        FakeTypeSafe ts = new FakeTypeSafe(q -> "none", 0.9);
        try (StubPlanner planner = new StubPlanner(DONE)) {
            withPage("repeated-links.html", page -> {
                new TypeSafeRunner().run(page, ts, planner.writer(), "x", null, 10, 60_000, 0.4);
                TypeSafeTypes.ChoiceQuestion element = ts.requests.get(0).questions.get("element");
                long newReport = element.criteria.values().stream().filter(v -> v.contains("\"New Report\"")).count();
                assertEquals(1, newReport);
                // Row-scoped buttons with the same label stay separate.
                long deletes = element.criteria.values().stream().filter(v -> v.contains("\"Delete\"")).count();
                assertEquals(2, deletes);
            });
        }
    }
}
