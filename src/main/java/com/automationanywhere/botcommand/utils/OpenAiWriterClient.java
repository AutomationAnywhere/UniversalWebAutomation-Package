package com.automationanywhere.botcommand.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * FSD TypeSafe's LLM: plans one page-level step at a time (which fields get which values,
 * what to click) and extracts a final answer against a schema. Never generates or runs page
 * JavaScript (that's FullSelfDrivingMultiTurn's job, not this one's).
 */
public class OpenAiWriterClient {

    private static final Logger logger = LogManager.getLogger(OpenAiWriterClient.class);
    private static final Gson GSON = new Gson();
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient httpClient;

    // Accumulated across every callChat() this instance makes over one FSD run, so the caller
    // can report total OpenAI token spend for the run (e.g. to compare against TypeSafe's token
    // usage for the same task).
    private long promptTokens;
    private long completionTokens;

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public OpenAiWriterClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL);
    }

    public OpenAiWriterClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** One page-level step: the fields to fill on this page and the clicks to make, in order. */
    public static class PagePlan {
        public boolean done;
        public String reason = "";
        public String navigate = "";
        public java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        public List<String> clicks = new java.util.ArrayList<>();
    }

    static final String PLANNER_SYSTEM = String.join("\n",
            "You drive a web browser toward the GOAL one page at a time. You see the current page (its text",
            "and its CONTROLS) and the HISTORY of what was already done. Reply with ONE JSON object for the next",
            "page step, with the keys in this order: {\"reason\": str, \"done\": bool, \"navigate\": str,",
            "\"fields\": {\"<field label>\": \"<value>\"}, \"clicks\": [\"<button or link text>\", ...]}. Write reason",
            "first (what is done, what is left), then decide done from it. Fields are filled first, then clicks",
            "run in order.",
            "",
            "RULES:",
            "- Fill EVERY field of the form in view and click its submit/save button in the same step.",
            "- Use labels exactly as shown in CONTROLS. For dropdowns give the option text. Checkboxes: \"true\"/\"false\".",
            "- File uploads go in fields: {\"<file control label>\": \"<full file path>\"} - never as a click.",
            "- Never save a form while a value the GOAL requires for it (e.g. its receipt file) is missing.",
            "- To click a button inside one row of a list or table, write: \"<button text> in row '<text unique to that row>'\".",
            "- If a click opens a form or menu, return just that click; you'll see what it opened next step.",
            "- Not every control is visible: sign out/log out usually lives in the account menu (a button showing",
            "  the username or initials); other actions hide behind 'More', '...', or a menu icon. Open the likely",
            "  menu before deciding something can't be done.",
            "- The GOAL's criteria are binding. Only act on items that meet them - check each row's text on the",
            "  page (e.g. its priority) before a delete. Never take an action the GOAL says not to take.",
            "- Never repeat something HISTORY shows was done, unless the page shows an error for it.",
            "- Pages often reset after the final action (a form clears itself, a wizard returns to step one,",
            "  a new round starts). If HISTORY shows every part of the GOAL was done, you are done - even if",
            "  the page now looks like it could be done again. Repeat only if the GOAL asks for repeats.",
            "- navigate: a full URL only if you must go to a page no control leads to; otherwise \"\".",
            "- Before done=true, go through the GOAL instruction by instruction - including final ones like",
            "  signing out - and make sure HISTORY or the page shows each one was done.",
            "- done=true when every part of the GOAL is complete: confirm it on the page where the page shows",
            "  results (a saved item in a list, a status), otherwise from HISTORY. Also done=true if the GOAL",
            "  cannot be completed - say why in reason. When done, fields={} and clicks=[].");

    public Optional<PagePlan> planPageStep(String goal, String pageSummary, List<String> history) {
        return planPageStep(goal, pageSummary, history, false);
    }

    /**
     * checkDone: the previous reply said done. Planners (especially small models) declare done
     * with items still missing - a run skipped an expense and never signed out - so before
     * accepting it, make them audit the GOAL instruction by instruction.
     */
    public Optional<PagePlan> planPageStep(String goal, String pageSummary, List<String> history, boolean checkDone) {
        String user = "GOAL:\n" + goal + "\n\nHISTORY:\n" + (history.isEmpty() ? "(nothing yet)" : String.join("\n", history))
                + "\n\nCURRENT PAGE:\n" + pageSummary;
        if (checkDone) {
            user += "\n\nThe GOAL looks done. Before the run stops, check it: list every instruction in the "
                    + "GOAL (every item to enter, every value, every file, and final steps like signing out) and, for "
                    + "each, find the HISTORY line or page text that proves it was done correctly and exactly once. Put "
                    + "that audit in reason, before deciding done. If the audit finds anything missing or wrong - even "
                    + "a final step - done MUST be false and fields/clicks must be the next step that fixes it.";
        }
        try {
            String content = callChat(PLANNER_SYSTEM, user, 60, true);
            if (content == null || content.isBlank()) return Optional.empty();
            PagePlan plan = GSON.fromJson(content.trim(), PagePlan.class);
            if (plan == null) return Optional.empty();
            if (plan.fields == null) plan.fields = new java.util.LinkedHashMap<>();
            if (plan.clicks == null) plan.clicks = new java.util.ArrayList<>();
            if (plan.reason == null) plan.reason = "";
            if (plan.navigate == null) plan.navigate = "";
            return Optional.of(plan);
        } catch (Exception e) {
            logger.warn("OpenAiWriterClient.planPageStep failed", e);
            return Optional.empty();
        }
    }

    public String extractFinalAnswer(String goal, String schema, String pageText) {
        String system = "Extract the final answer from the page text below, matching the given JSON schema exactly. " +
                "Reply with ONLY the JSON, no markdown fences.";
        String user = "GOAL: " + goal + "\nSCHEMA: " + schema + "\nPAGE TEXT:\n" + pageText;
        try {
            return callChat(system, user, 60);
        } catch (Exception e) {
            logger.warn("OpenAiWriterClient.extractFinalAnswer failed", e);
            return null;
        }
    }

    private String callChat(String systemPrompt, String userPrompt, int timeoutSec) throws Exception {
        return callChat(systemPrompt, userPrompt, timeoutSec, false);
    }

    private String callChat(String systemPrompt, String userPrompt, int timeoutSec, boolean json) throws Exception {
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(sysMsg);
        messages.add(userMsg);

        // No explicit temperature: the gpt-5 family rejects any value other than the
        // default (1) on /v1/chat/completions, so omitting it is the only setting that
        // works across both the gpt-5 and gpt-4.1 families this action offers.
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.add("messages", messages);
        if (json) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            payload.add("response_format", format);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSec))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() > 299) {
            throw new RuntimeException("OpenAI HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
        JsonObject usage = root.getAsJsonObject("usage");
        if (usage != null) {
            if (usage.has("prompt_tokens")) promptTokens += usage.get("prompt_tokens").getAsLong();
            if (usage.has("completion_tokens")) completionTokens += usage.get("completion_tokens").getAsLong();
        }
        return root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
    }
}
