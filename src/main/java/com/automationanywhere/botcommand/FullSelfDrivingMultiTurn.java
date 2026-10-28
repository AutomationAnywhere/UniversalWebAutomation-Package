package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.BotCommand;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AllowedTarget;
import com.automationanywhere.commandsdk.model.DataType;
import com.automationanywhere.core.security.SecureString;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Page;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.*;

@BotCommand
@CommandPkg(
        name = "FullSelfDrivingMultiTurn",
        label = "FSD Multi-Turn (AI-Powered)",
        description = "Goal-based, multi-agent browser automation with Planner-Actor-Validator architecture",
        node_label = "FSD Multi-Turn: {{request}}",
        icon = "pkg.svg",
        allowed_agent_targets = {
                AllowedTarget.WINDOWS,
                AllowedTarget.MAC_OS
        },
        return_label = "Result Dictionary",
        return_required = true,
        return_type = DataType.DICTIONARY,
        return_sub_type = DataType.STRING,
        return_description = "Keys are 'status' ('OK' or 'ERROR: <message>'), 'stopReason' ('done' | " +
                "'turn_limit' | 'timeout' | 'error'), 'initial_plan' (the planner's numbered step list), " +
                "'numberOfTurns', 'openAiInputTokens', 'openAiOutputTokens', and " +
                "'turn1'..'turnN' each a dictionary of action/code/result/validation (plus 'error' if that " +
                "turn failed, and 'replanned'=\"true\" if it triggered a re-plan). A turn that replans also " +
                "adds a sibling 'turnN_new_plan' key with the updated plan. 'completion_reason' is present " +
                "only if stopReason is 'done'. 'data' is present only if an Output Format Schema " +
                "was given and the last turn's result looked like JSON matching it."
)
public class FullSelfDrivingMultiTurn {

    private static final Logger logger = LogManager.getLogger(FullSelfDrivingMultiTurn.class);
    private static final long WARN_ABOVE_SECONDS = 1800;

    private String lastExecResult = "";
    private String lastExecError  = "";

    // Accumulated across every callOpenAI() this instance makes over one run (planner,
    // per-turn actor, per-turn validator, and any replans), so the total OpenAI spend for
    // the run can be reported and compared against other FSD actions' token usage.
    private long openAiInputTokens;
    private long openAiOutputTokens;

    @Sessions
    private Map<String, Object> sessions;

    @Execute
    public DictionaryValue action(
            @Idx(index = "1", type = TEXT)
            @Pkg(label = "Session name", default_value_type = DataType.STRING, default_value = "Default")
            @NotEmpty String sessionName,

            @Idx(index = "2", type = CREDENTIAL)
            @Pkg(label = "OpenAI API Key")
            @NotEmpty SecureString openAiApiKeySS,

            @Idx(index = "3", type = SELECT, options = {
                    @Idx.Option(index = "3.1", pkg = @Pkg(label = "gpt-6-luna", value = "gpt-6-luna")),
                    @Idx.Option(index = "3.2", pkg = @Pkg(label = "gpt-6-astra", value = "gpt-6-astra")),
                    @Idx.Option(index = "3.3", pkg = @Pkg(label = "gpt-6-sol", value = "gpt-6-sol")),
                    @Idx.Option(index = "3.4", pkg = @Pkg(label = "gpt-5-mini", value = "gpt-5-mini")),
                    @Idx.Option(index = "3.5", pkg = @Pkg(label = "gpt-5", value = "gpt-5")),
                    @Idx.Option(index = "3.6", pkg = @Pkg(label = "gpt-4.1", value = "gpt-4.1")),
                    @Idx.Option(index = "3.7", pkg = @Pkg(label = "gpt-4.1-mini", value = "gpt-4.1-mini"))
            })
            @Pkg(label = "OpenAI Model", default_value = "gpt-6-luna", default_value_type = DataType.STRING)
            String model,

            @Idx(index = "4", type = TEXTAREA)
            @Pkg(label = "Request (what should be done?)")
            @NotEmpty String request,

            @Idx(index = "5", type = TEXTAREA)
            @Pkg(label = "Output Format Schema (optional)")
            String outputFormatSchema,

            @Idx(index = "6", type = NUMBER)
            @Pkg(label = "Execution timeout (seconds)", default_value = "60", default_value_type = DataType.NUMBER)
            Double jsTimeoutSeconds,

            @Idx(index = "7", type = NUMBER)
            @Pkg(label = "Maximum turns", default_value = "5", default_value_type = DataType.NUMBER)
            @NotEmpty Double maxTurns,

            @Idx(index = "8", type = NUMBER)
            @Pkg(label = "Max run seconds", default_value = "120", default_value_type = DataType.NUMBER)
            @NotEmpty Double maxRunSeconds
    ) {
        // Never log the request/schema text or the API key - just enough to trace the run.
        logger.info("FullSelfDrivingMultiTurn started - session: {}, model: {}, maxTurns: {}", sessionName, model, maxTurns);

        Object connObj = this.sessions.get(sessionName);
        if (!(connObj instanceof PlaywrightConnection)) {
            throw new BotCommandException("No active session found with name: " + sessionName);
        }

        String apiKey = openAiApiKeySS.getInsecureString();
        PlaywrightConnection conn = (PlaywrightConnection) this.sessions.get(sessionName);
        Page page = conn.getPage();

        // Configuration
        final int MIN_DOM_DELAY_MS = 1500;
        final int DEFAULT_TURN_WAIT_MS = 3000;

        // Build system prompts once
        String plannerPrompt = buildPlannerSystemPrompt();
        String actorPrompt = buildActorSystemPrompt();
        String validatorPrompt = buildValidatorSystemPrompt();

        int maxTurnsInt = (int) Math.round(maxTurns);
        long maxRunMillis = validateMaxRunSeconds(maxRunSeconds);
        long startTime = System.currentTimeMillis();
        Map<String, Value> out = new HashMap<>();
        out.put("status", new StringValue("OK"));
        int turnCount = 0;
        String stopReason = null;

        try {
            // Initial planning phase
            String currentPlan = generateInitialPlan(page, apiKey, model, plannerPrompt, request, outputFormatSchema, 60);
            out.put("initial_plan", new StringValue(currentPlan));

            // Which step of currentPlan runs next. Kept separate from the turn number: a re-plan
            // is written from the page as it is now, so it must run from its own step 1. Reusing
            // the turn number skipped the new plan's first steps (observed: after a failed login
            // at turn 4 the new plan's "log in" steps were skipped and turn 8 ran "Log out" on
            // the login page).
            int planStep = 1;
            java.util.List<String> completedSteps = new java.util.ArrayList<>();
            for (int t = 1; t <= maxTurnsInt; t++) {
                if (System.currentTimeMillis() - startTime > maxRunMillis) {
                    stopReason = "timeout";
                    break;
                }

                TurnOutcome turn = runEnhancedTurn(
                        page, apiKey, model,
                        actorPrompt, validatorPrompt,
                        request, currentPlan, planStep, maxTurnsInt,
                        outputFormatSchema, jsTimeoutSeconds,
                        MIN_DOM_DELAY_MS, DEFAULT_TURN_WAIT_MS
                );
                turnCount = t;

                // Create JSON object for this turn
                Map<String, Value> turnData = new HashMap<>();
                turnData.put("action", new StringValue(turn.action));
                turnData.put("code", new StringValue(turn.code));
                turnData.put("result", new StringValue(turn.execResult));
                turnData.put("validation", new StringValue(turn.validation));

                if (turn.error != null && !turn.error.isBlank()) {
                    turnData.put("error", new StringValue(turn.error));
                }

                if (turn.needsReplanning) {
                    turnData.put("replanned", new StringValue("true"));
                }

                // Store as single dictionary value for this turn
                out.put("turn" + t, new DictionaryValue(turnData));

                // If replanned, also store the new plan
                if (turn.needsReplanning) {
                    currentPlan = replan(page, apiKey, model, plannerPrompt, request,
                            currentPlan, turn.validation, completedSteps, 60);
                    out.put("turn" + t + "_new_plan", new StringValue(currentPlan));
                    planStep = 1;
                } else {
                    completedSteps.add(turn.action + " -> " + turn.validation);
                    planStep++;
                }

                // Check if task is complete
                if (turn.isComplete) {
                    out.put("completion_reason", new StringValue("Task completed successfully"));
                    stopReason = "done";
                    break;
                }

                // Wait between turns
                try { Thread.sleep(DEFAULT_TURN_WAIT_MS); } catch (InterruptedException ignored) {}
            }

            if (stopReason == null) {
                stopReason = "turn_limit";
            }

            // Extract final data if schema provided
            if (outputFormatSchema != null && !outputFormatSchema.isBlank()) {
                String finalRes = lastExecResult;
                if (finalRes != null && (finalRes.trim().startsWith("{") || finalRes.trim().startsWith("["))) {
                    out.put("data", new StringValue(finalRes));
                }
            }

        } catch (Exception ex) {
            logger.error("FullSelfDrivingMultiTurn failed - session: {}, turnCount: {}", sessionName, turnCount, ex);
            out.put("status", new StringValue("ERROR: " + ex.getMessage()));
            stopReason = "error";
        }

        out.put("stopReason", new StringValue(stopReason == null ? "error" : stopReason));
        out.put("numberOfTurns", new StringValue(String.valueOf(turnCount)));
        out.put("openAiInputTokens", new StringValue(String.valueOf(openAiInputTokens)));
        out.put("openAiOutputTokens", new StringValue(String.valueOf(openAiOutputTokens)));
        logger.info("FullSelfDrivingMultiTurn completed - session: {}, turns: {}", sessionName, turnCount);
        return new DictionaryValue(out);
    }

    // =========================
    // SYSTEM PROMPTS (Skyvern-inspired)
    // =========================

    private static String buildPlannerSystemPrompt() {
        return String.join("\n",
                "You are the PLANNER agent in a multi-agent browser automation system.",
                "Your job is to decompose complex user goals into clear, achievable steps.",
                "",
                "CRITICAL RULES:",
                "- The browser is ALREADY on the target page - DO NOT include navigation steps",
                "- Break down the goal into 3-7 sequential steps",
                "- Each step should be a single, clear action",
                "- Steps should be granular enough that a simple actor can execute them",
                "- Consider the current page state when planning",
                "- One step per form: fill EVERY field of the form and click its submit/save button in the SAME",
                "  step. Never save a form before all its fields are filled - once saved, the form is gone.",
                "- For data extraction with schema, final step must return data in EXACT schema format",
                "",
                "DATA EXTRACTION RULES:",
                "- If schema shows array (e.g., 'rows': []), return data as array: { rows: [ {item1}, {item2} ] }",
                "- If schema shows object, return single object: { field1: 'value', field2: 'value' }",
                "- Final extraction step should explicitly state: 'Return data as JSON matching schema'",
                "",
                "OUTPUT FORMAT:",
                "Return a numbered list of steps ONLY. No explanations. Example:",
                "1. Enter username in the input field with type='text' or name='username'",
                "2. Enter password in the input field with type='password'",
                "3. Click the submit button (look for input[type='submit'] or button with text 'Login')",
                "4. Wait for page load",
                "5. Extract heading text and return as JSON: { \"heading\": \"text\" }"
        );
    }

    private static String buildActorSystemPrompt() {
        return String.join("\n",
                "You are the ACTOR agent in a multi-agent browser automation system.",
                "You execute ONE step at a time based on the planner's instructions.",
                "",
                "CRITICAL CODE GENERATION RULES:",
                "- Generate ONLY raw JavaScript code with NO markdown, NO backticks, NO code fences",
                "- Code must be valid JavaScript that can run directly in eval()",
                "",
                "SELECTOR STRATEGY (in order of preference):",
                "1. Type attribute for inputs: input[type=\"text\"], input[type=\"password\"], input[type=\"submit\"]",
                "2. Name attribute: [name=\"username\"], [name=\"password\"]",
                "3. ID (if looks stable): #username, #loginButton",
                "4. Placeholder: [placeholder=\"Enter email\"]",
                "5. Button/link text: document.querySelector('button').textContent includes 'Login'",
                "6. Form elements by position: document.querySelector('form').elements[0]",
                "AVOID: [role=\"button\"] unless you verify it exists, complex XPaths, fragile nth-child",
                "",
                "NAVIGATION RULES:",
                "- NEVER use window.location.href or window.location.assign() - these destroy the execution context",
                "- The page is already on the correct URL when you start",
                "- If you need to navigate, just return 'ALREADY_ON_PAGE' and the system will handle it",
                "",
                "TIMING RULES:",
                "- You have access to a sleep(ms) function for delays",
                "- Add await sleep(1500) between DOM operations",
                "- After button clicks that submit forms, use await sleep(3000)",
                "- Always check if element exists before using it: if (!element) return 'ELEMENT_NOT_FOUND';",
                "",
                "INTERACTION PATTERNS:",
                "- For inputs, textareas, selects and contenteditable: await setValue(field, 'text');",
                "  setValue is provided for you. NEVER assign field.value directly: on React/Vue apps the",
                "  field looks filled but the app never sees the value and submits it empty.",
                "- Find fields by their visible label when possible: the <label> text, aria-label, or placeholder.",
                "- To act on one item in a list/table, use rowFor('item text') (provided) to get its row, then",
                "  search INSIDE that row: rowFor('Archive old invoices').querySelector('button...'). NEVER use",
                "  querySelectorAll('div').find(...) to find a row - an outer container matches first and you act",
                "  on the wrong row. Before a delete, confirm the row matches the criteria (e.g. its priority).",
                "- For clicking: if (element) { element.scrollIntoView({behavior: 'smooth', block: 'center'}); await sleep(500); element.click(); }",
                "- For a button in a list/table row: const row = rowFor('Archive old invoices');",
                "  if (!row) return 'ELEMENT_NOT_FOUND: row'; if (!row.innerText.includes('Low')) return 'SKIPPED: not Low';",
                "  [...row.querySelectorAll('button')].find(b => b.innerText.trim() === 'Delete').click();",
                "",
                "RETURN VALUES:",
                "- Return 'OK' for successful operations",
                "- Return JSON object/array when extracting data",
                "- Return 'ELEMENT_NOT_FOUND: description' if element doesn't exist",
                "",
                "EXAMPLE OUTPUT (no backticks or fences):",
                "const usernameField = document.querySelector('input[type=\"text\"]') || document.querySelector('[name=\"username\"]');",
                "if (!usernameField) return 'ELEMENT_NOT_FOUND: username field';",
                "await setValue(usernameField, 'testuser');",
                "await sleep(1500);",
                "return 'OK';"
        );
    }

    private static String buildValidatorSystemPrompt() {
        return String.join("\n",
                "You are the VALIDATOR agent in a multi-agent browser automation system.",
                "Your job is to confirm whether the actor successfully completed its step.",
                "",
                "ANALYZE:",
                "- The step that was supposed to be executed",
                "- The code that was run",
                "- The result returned",
                "- The current page state",
                "",
                "JUDGE BY THE PAGE, NOT BY THE RESULT STRING:",
                "- 'OK' only means the script didn't crash. Check CURRENT PAGE STATE for the step's intended outcome",
                "  (e.g. a saved item appears in the list with the values the step asked for).",
                "- A step that is meant to leave the page succeeds when the page it leads to is showing:",
                "  after 'log out' / 'sign out', being on the login page IS success.",
                "- If the step's purpose is already achieved on the page, that is SUCCESS - e.g. 'delete the Low",
                "  tasks' and no Low task remains; 'log in' and the dashboard is showing.",
                "",
                "SPECIAL CASES TO UNDERSTAND:",
                "- If result is '__FSD_EVAL_ERROR__: Execution context was destroyed' and step was about navigation, this is EXPECTED and means SUCCESS",
                "- If result is 'ELEMENT_NOT_FOUND: [description]', this is a FAILURE - the element wasn't found",
                "- If result is 'OK', the operation completed successfully",
                "- If result is JSON data and step asked for extraction, validate the JSON matches the request",
                "",
                "OUTPUT FORMAT:",
                "Return EXACTLY ONE of these:",
                "SUCCESS: [brief description of what succeeded]",
                "PARTIAL: [what worked and what didn't]",
                "FAILURE: [what went wrong and why]",
                "COMPLETE: [task is fully done, no more steps needed]",
                "",
                "Examples:",
                "SUCCESS: Login button clicked successfully",
                "SUCCESS: Navigation completed (execution context destroyed is normal for navigation)",
                "PARTIAL: Username entered but password field not found",
                "FAILURE: Element not found - selector may be incorrect",
                "FAILURE: Cannot read properties of null - element doesn't exist",
                "COMPLETE: All required data extracted and returned in correct format"
        );
    }

    // =========================
    // PLANNING FUNCTIONS
    // =========================

    private String generateInitialPlan(Page page, String apiKey, String model,
                                       String systemPrompt, String goal,
                                       String schemaOrNull, int timeoutSec) throws Exception {
        String pageContext = collectPageContext(page);
        String userPrompt = String.format(
                "GOAL: %s\n\n%s\n\n%s",
                goal,
                pageContext,
                schemaOrNull != null && !schemaOrNull.isBlank()
                        ? "Final step should extract data matching schema: " + schemaOrNull
                        : "Final step should return 'OK'"
        );

        String response = callOpenAI(apiKey, model, systemPrompt, userPrompt, timeoutSec);
        return extractContent(response);
    }

    private String replan(Page page, String apiKey, String model, String systemPrompt,
                          String goal, String previousPlan, String validationResult,
                          java.util.List<String> completedSteps, int timeoutSec) throws Exception {
        String pageContext = collectPageContext(page);
        String userPrompt = String.format(
                "GOAL: %s\n\nSTEPS ALREADY COMPLETED (do NOT repeat these):\n%s\n\nPREVIOUS PLAN:\n%s\n\n"
                        + "VALIDATION RESULT OF THE LAST STEP:\n%s\n\n%s\n\n"
                        + "Generate a plan for ONLY the remaining work, starting from the page as it is now. "
                        + "Never redo a completed step (that creates duplicates or undoes work). "
                        + "The GOAL's criteria are binding: never plan an action on an item that does not meet "
                        + "them (e.g. never delete a High or Medium task when the goal says delete Low tasks). "
                        + "If the page shows the goal's condition is already met, plan no step for it.",
                goal, completedSteps.isEmpty() ? "(none)" : String.join("\n", completedSteps),
                previousPlan, validationResult, pageContext
        );

        String response = callOpenAI(apiKey, model, systemPrompt, userPrompt, timeoutSec);
        return extractContent(response);
    }

    // =========================
    // TURN EXECUTION
    // =========================

    private static class TurnOutcome {
        String action;
        String code;
        String execResult;
        String validation;
        String error;
        boolean isComplete;
        boolean needsReplanning;
    }

    private TurnOutcome runEnhancedTurn(
            Page page, String apiKey, String model,
            String actorPrompt, String validatorPrompt,
            String goal, String plan, int planStep, int maxTurns,
            String outputSchemaOrNull, Double perTurnTimeoutSeconds,
            int minDomDelayMs, int defaultTurnWaitMs
    ) throws Exception {

        TurnOutcome outcome = new TurnOutcome();

        // 1. Get current step from plan
        String currentStep = extractStepFromPlan(plan, planStep);
        if (currentStep == null) {
            // Ran past the planner's actual step count with no replan pending: the plan is
            // exhausted, which means the described task is done. Stop here instead of asking
            // the actor to improvise an action for a step that doesn't exist.
            outcome.action = "(no more plan steps)";
            outcome.code = "";
            outcome.execResult = "";
            outcome.validation = "COMPLETE: reached the end of the plan with no remaining steps";
            outcome.isComplete = true;
            return outcome;
        }
        outcome.action = currentStep;

        // 2. Actor generates code for this step
        String pageContext = collectPageContext(page);
        String actorUserPrompt = String.format(
                "CURRENT STEP TO EXECUTE:\n%s\n\n%s\n\nPREVIOUS RESULT: %s\n\nPREVIOUS ERROR: %s\n\nGenerate JavaScript to execute this step.",
                currentStep, pageContext, lastExecResult, lastExecError
        );

        String actorResponse = callOpenAI(apiKey, model, actorPrompt, actorUserPrompt, 60);
        String jsCode = cleanAndExtractJavaScript(actorResponse);
        outcome.code = jsCode;

        // 3. Execute the code
        String execResult = executeJavaScriptSafely(page, jsCode, perTurnTimeoutSeconds, minDomDelayMs);
        outcome.execResult = execResult;

        // Check for errors
        if (execResult.startsWith("__FSD_EVAL_ERROR__") || execResult.startsWith("__FSD_JS_ERROR__")) {
            outcome.error = execResult;
            lastExecError = execResult;
        } else {
            lastExecResult = execResult;
            lastExecError = "";
        }

        // 4. Validator checks if step succeeded - once the page has stopped changing. Judging a
        // mid-re-render page made the validator report a saved form as "not submitted", and the
        // resulting re-plan redid work.
        waitForSettle(page, 4000);
        String newPageContext = collectPageContext(page);
        String validatorUserPrompt = String.format(
                "STEP THAT SHOULD HAVE BEEN EXECUTED:\n%s\n\nCODE THAT WAS RUN:\n%s\n\nRESULT:\n%s\n\nCURRENT PAGE STATE:\n%s\n\nDid this step succeed?",
                currentStep, jsCode, execResult, newPageContext
        );

        String validatorResponse = callOpenAI(apiKey, model, validatorPrompt, validatorUserPrompt, 60);
        String validation = extractContent(validatorResponse);
        outcome.validation = validation;

        // 5. Determine if complete or needs replanning
        if (validation.startsWith("COMPLETE:")) {
            outcome.isComplete = true;
        } else if (validation.startsWith("FAILURE:") || validation.startsWith("PARTIAL:")) {
            outcome.needsReplanning = true;
        }

        return outcome;
    }

    // =========================
    // HELPER FUNCTIONS
    // =========================

    /** Waits until the page's visible text is unchanged across two polls, or maxMillis. */
    static void waitForSettle(Page page, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        String last = null;
        int stable = 0;
        while (System.currentTimeMillis() < deadline && stable < 2) {
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            String cur = collectPageText(page, 20000) + "|" + page.url();
            stable = cur.equals(last) ? stable + 1 : 0;
            last = cur;
        }
    }

    private String collectPageContext(Page page) {
        String url = page.url();
        String title = safeEval(page, "() => document.title");
        String pageText = collectPageText(page, 3000);
        String domHints = collectDomHints(page);

        return String.format(
                "URL: %s\nTITLE: %s\n\nPAGE TEXT (truncated):\n%s\n\nINTERACTIVE ELEMENTS:\n%s",
                url, title, pageText, domHints
        );
    }

    private static String collectPageText(Page page, int charLimit) {
        try {
            String txt = String.valueOf(page.evalOnSelector("body", "el => el.innerText || ''"));
            if (txt == null) return "";
            txt = txt.replaceAll("\\s+", " ").trim();
            return txt.length() > charLimit ? txt.substring(0, charLimit) + "..." : txt;
        } catch (Exception e) {
            return "";
        }
    }

    private static String collectDomHints(Page page) {
        String js =
                "(() => {" +
                        "  const hints = [];" +
                        // Visible controls only, links included, each with its <label> text: the
                        // actor can't target a field it can't name (it used to see only the first
                        // 31 inputs/buttons, hidden ones included, and no labels or links).
                        "  const els = document.querySelectorAll('input, textarea, select, button, a[href], [role=button], [contenteditable=true]');" +
                        "  for (const el of els) {" +
                        "    if (hints.length >= 80) break;" +
                        "    const r = el.getBoundingClientRect();" +
                        "    if (!(r.width > 0 && r.height > 0) || getComputedStyle(el).visibility === 'hidden') continue;" +
                        "    const lab = (el.labels && el.labels[0] ? el.labels[0].innerText : '') ||" +
                        "      (el.id ? (document.querySelector('label[for=\"' + el.id + '\"]') || {}).innerText || '' : '');" +
                        "    hints.push({" +
                        "      tag: el.tagName.toLowerCase()," +
                        "      type: el.type || ''," +
                        "      label: (lab || '').trim().substring(0, 50)," +
                        "      name: el.name || ''," +
                        "      id: el.id || ''," +
                        "      placeholder: el.placeholder || ''," +
                        "      text: (el.tagName === 'SELECT' ? '' : (el.innerText || el.value || '')).trim().substring(0, 50)," +
                        "      ariaLabel: el.getAttribute('aria-label') || ''" +
                        "    });" +
                        "  }" +
                        "  return JSON.stringify({elements: hints});" +
                        "})()";

        try {
            return String.valueOf(page.evaluate(js));
        } catch (Exception e) {
            return "{\"elements\":[]}";
        }
    }

    /**
     * Returns null once stepNumber runs past the planner's actual numbered steps (a plan is
     * "3-7 steps" per its own prompt, but nothing stops the step number from climbing past that if
     * maxTurns is higher). The old fallback fabricated a placeholder ("Step N from plan") and
     * fed it to the actor as if it were real, which produced ungrounded, made-up actions turn
     * after turn - observed in practice as the actor repeatedly re-clicking Submit on an
     * already-submitted form because it had nothing real left to do but had to generate
     * something. Returning null lets the caller treat "no more plan" as completion instead.
     */
    private String extractStepFromPlan(String plan, int stepNumber) {
        String[] lines = plan.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(stepNumber + ".") || trimmed.startsWith(stepNumber + ")")) {
                return trimmed;
            }
        }
        return null;
    }

    private String cleanAndExtractJavaScript(String aiResponse) {
        String content = extractContent(aiResponse);

        // Remove markdown code fences if present
        content = content.replaceAll("```javascript\\s*", "").replaceAll("```js\\s*", "").replaceAll("```\\s*$", "").trim();

        // Remove leading/trailing backticks
        while (content.startsWith("`")) content = content.substring(1);
        while (content.endsWith("`")) content = content.substring(0, content.length() - 1);

        return content.trim();
    }

    // setValue(el, v): the only reliable way for page JS to fill a field on a React/Vue app.
    // Plain `el.value = v` goes through the framework's instance-level value tracker, so the
    // following 'input' event looks like "no change" and the app's state never updates - the
    // field shows the text but the form submits empty (observed: every TaskDeck login stayed on
    // /login). Setting through the prototype's native setter bypasses the tracker.
    private static final String JS_HELPERS =
            "const setValue = async (el, v) => {" +
            "  if (!el) throw new Error('setValue: element not found');" +
            "  el.scrollIntoView({block: 'center'}); el.focus();" +
            "  if (el.isContentEditable) {" +
            "    el.textContent = String(v);" +
            "  } else {" +
            "    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype" +
            "        : el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;" +
            "    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, String(v));" +
            "  }" +
            "  el.dispatchEvent(new Event('input', {bubbles: true}));" +
            "  el.dispatchEvent(new Event('change', {bubbles: true}));" +
            "  el.blur();" +
            "};" +
            // rowFor(text): the row/list item/card that contains `text`, choosing the SMALLEST
            // match. Generated code like querySelectorAll('tr, div').find(includes(text)) hits a
            // wrapping <div> that contains every row, so its first button belongs to row 1
            // (observed: a High task deleted while "deleting the Low tasks").
            "const rowFor = (text) => {" +
            "  const smallest = (els) => els.reduce((a, b) => (a && a.innerText.length <= b.innerText.length) ? a : b, null);" +
            "  const has = (e) => (e.innerText || '').includes(text);" +
            "  const rows = [...document.querySelectorAll('tr, [role=row], li')].filter(has);" +
            "  if (rows.length) return smallest(rows);" +
            "  return smallest([...document.querySelectorAll('body *')].filter(e => has(e) &&" +
            "      e.querySelector('button, a, [role=button], input[type=checkbox]')));" +
            "};";

    public static String executeJavaScriptSafely(Page page, String jsCode, Double jsTimeoutSeconds, int minDelayMs) {
        try {
            long timeoutMs = (jsTimeoutSeconds == null ? 60_000 : (long)(jsTimeoutSeconds * 1000));
            page.setDefaultTimeout(timeoutMs);

            // Create wrapper that provides sleep() and handles errors
            String wrapper = String.format(
                    "async (codeParam) => {" +
                            "  const minDelay = %d;" +
                            "  const sleep = (ms) => new Promise(r => setTimeout(r, Math.max(ms || 0, minDelay)));" +
                            JS_HELPERS.replace("%", "%%") +
                            "  try {" +
                            "    const wrappedCode = `(async () => { ${codeParam} })()`;" +
                            "    const result = await eval(wrappedCode);" +
                            "    if (typeof result === 'undefined' || result === null) return 'OK';" +
                            "    if (typeof result === 'object') {" +
                            "      try { return JSON.stringify(result); } catch(_) { return String(result); }" +
                            "    }" +
                            "    return String(result);" +
                            "  } catch (err) {" +
                            "    return '__FSD_JS_ERROR__: ' + (err && err.message ? err.message : String(err));" +
                            "  }" +
                            "}",
                    minDelayMs
            );

            return String.valueOf(page.evaluate(wrapper, jsCode));

        } catch (Exception e) {
            return "__FSD_EVAL_ERROR__: " + e.getMessage();
        }
    }

    // =========================
    // OPENAI & UTILITY FUNCTIONS
    // =========================

    private String callOpenAI(String apiKey, String model, String systemPrompt,
                              String userPrompt, int timeoutSec) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSec))
                .build();

        // No explicit temperature: the gpt-5 family rejects any value other than the
        // default (1) on /v1/chat/completions, so omitting it is the only setting that
        // works across both the gpt-5 and gpt-4.1 families this action offers.
        String body = "{\n" +
                "  \"model\": " + jsonString(model) + ",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"system\", \"content\": " + jsonString(systemPrompt) + "},\n" +
                "    {\"role\": \"user\",   \"content\": " + jsonString(userPrompt) + "}\n" +
                "  ]\n" +
                "}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSec))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() > 299) {
            throw new Exception("OpenAI HTTP " + resp.statusCode() + ": " + resp.body());
        }
        recordUsage(resp.body());
        return resp.body();
    }

    private void recordUsage(String responseBody) {
        try {
            JsonObject usage = new Gson().fromJson(responseBody, JsonObject.class).getAsJsonObject("usage");
            if (usage == null) return;
            if (usage.has("prompt_tokens")) openAiInputTokens += usage.get("prompt_tokens").getAsLong();
            if (usage.has("completion_tokens")) openAiOutputTokens += usage.get("completion_tokens").getAsLong();
        } catch (Exception ignored) {
            // Token accounting is best-effort observability, never a reason to fail the run.
        }
    }

    private static String extractContent(String json) {
        int i = json.indexOf("\"content\":");
        if (i < 0) return "";
        int start = json.indexOf('"', i + 10);
        if (start < 0) return "";
        int end = findStringEnd(json, start + 1);
        if (end < 0) return "";
        return unescapeJson(json.substring(start + 1, end));
    }

    private static int findStringEnd(String s, int pos) {
        boolean esc = false;
        for (int i = pos; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (ch == '\\') { esc = true; continue; }
            if (ch == '"') return i;
        }
        return -1;
    }

    private static String unescapeJson(String s) {
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String safeEval(Page page, String js) {
        try { return String.valueOf(page.evaluate(js)); } catch (Exception e) { return ""; }
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }

    public static long validateMaxRunSeconds(Double maxRunSeconds) {
        if (maxRunSeconds == null || maxRunSeconds <= 0) {
            throw new BotCommandException("Max run seconds must be a positive number.");
        }
        if (maxRunSeconds > WARN_ABOVE_SECONDS) {
            logger.warn("FullSelfDrivingMultiTurn: maxRunSeconds {} exceeds 30 minutes - this run may take a long time",
                    maxRunSeconds);
        }
        return Math.round(maxRunSeconds * 1000);
    }
}