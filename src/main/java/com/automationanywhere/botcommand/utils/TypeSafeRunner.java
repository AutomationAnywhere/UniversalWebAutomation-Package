package com.automationanywhere.botcommand.utils;

import com.microsoft.playwright.Locator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Page;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FSD TypeSafe's engine: TypeSafe drives navigation, the LLM fills forms, Playwright acts.
 *
 * Each round:
 * - A page with a form to fill (an empty text field) goes to the writer LLM, which returns one
 *   page step: field label -> value, then clicks. Labels and click phrases are matched to
 *   controls exactly, or by one TypeSafe Choice with a "none" option.
 * - Any other page goes to TypeSafe first (as in github.com/awlevin/typesafe-computer-use):
 *   one request asks what kind of move comes next (click / done / none) and which control. A
 *   confident click is executed with no LLM call; about half the rounds of a form-heavy task
 *   are like this. "none", low confidence, or a click that left the page unchanged hands the
 *   round to the LLM, which plans it; a "done" is audited by the LLM before the run stops.
 *
 * TypeSafe sees the goal, the page's visible text and controls, and the actions taken - never a
 * value typed into a field (history lines sent to it are stripped of values).
 */
public class TypeSafeRunner {

    private static final Logger logger = LogManager.getLogger(TypeSafeRunner.class);
    private static final String NONE = "none";
    private static final int MAX_HELPLESS_ROUNDS = 3;
    private static final int MAX_DONE_CHECKS = 2;
    // A wrong click costs a round and can undo work, while handing a round to the planner costs
    // one LLM call - so TypeSafe's clicks need more confidence than its field matches.
    private static final double ROUTE_CONFIDENCE = 0.7;
    private static final long SETTLE_MILLIS = 4000;
    private static final Pattern IN_ROW = Pattern.compile("^(.*?)\\s+(?:in|for|on)\\s+(?:the\\s+)?row\\s+['\"‘“](.+?)['\"’”]\\s*$",
            Pattern.CASE_INSENSITIVE);

    // Same action under different names; the classifier treated "sign in" vs "Log In" as a mismatch.
    private static final List<Set<String>> SYNONYMS = List.of(
            Set.of("sign in", "log in", "login", "signin", "log on", "sign on"),
            Set.of("sign out", "log out", "logout", "signout", "log off", "sign off"),
            Set.of("sign up", "register", "create account"));

    public static class StepTrace {
        public String actionType;
        public String target = "none";
        public double confidence;
        public String result;
    }

    public static class RunResult {
        public String status = "OK";
        public String stopReason;
        public String reason = "";
        public List<StepTrace> steps = new ArrayList<>();
        public String data;
        /** One line per round: what the planner asked for, what was done, any problem. */
        public List<String> history = new ArrayList<>();
        public int iframesSkipped;
        public long typeSafeInputTokens;
        public long typeSafeOutputTokens;
        /** Rounds decided by TypeSafe alone vs rounds the LLM planned. */
        public int classifierRounds;
        public int plannerRounds;
        /** Why TypeSafe handed each non-form round to the planner: its top choice and confidence. */
        public List<String> routing = new ArrayList<>();
    }

    /** One thing done this round: the planner's line (with values) and TypeSafe's (without). */
    private static final class Done {
        final String full;
        final String plain;

        Done(String full, String plain) {
            this.full = full;
            this.plain = plain;
        }
    }

    /** A decision the runner could not make safely; reported to the planner instead of guessed. */
    static class NeedsHelp extends Exception {
        NeedsHelp(String message) {
            super(message);
        }
    }

    private TypeSafeDecisionSource typeSafe;
    private Page page;
    private RunResult result;
    private double minConfidence;

    public RunResult run(Page page, TypeSafeDecisionSource typeSafe, OpenAiWriterClient writer,
                         String goal, String schema, int maxSteps, long maxRunMillis, double minConfidence) {
        this.typeSafe = typeSafe;
        this.page = page;
        this.result = new RunResult();
        this.minConfidence = minConfidence;
        long start = System.currentTimeMillis();

        if (writer == null) {
            result.status = "ERROR: an OpenAI key is required - FSD TypeSafe's planner decides what to do on each page";
            result.stopReason = "no_writer";
            return result;
        }

        List<String> history = result.history;
        List<String> plainHistory = new ArrayList<>();   // what TypeSafe sees: no typed values
        int helpless = 0;
        int doneChecks = 0;
        boolean plannerNext = false;   // set when TypeSafe's last click changed nothing
        for (int round = 1; round <= maxSteps; round++) {
            if (System.currentTimeMillis() - start > maxRunMillis) {
                result.stopReason = "timeout";
                break;
            }
            PageView.settle(page, SETTLE_MILLIS);
            PageView view;
            try {
                view = PageView.read(page);
            } catch (Exception e) {
                logger.warn("TypeSafeRunner: could not read the page, retrying next round", e);
                history.add("Round " + round + ": could not read the page (" + e.getMessage() + ")");
                if (++helpless >= MAX_HELPLESS_ROUNDS) { result.stopReason = "stalled"; break; }
                continue;
            }
            result.iframesSkipped = view.iframesSkipped;

            // TypeSafe first on pages with nothing to fill.
            Route route = null;
            if (!plannerNext && !view.hasFormToFill()) {
                route = route(view, goal, plainHistory);
                if (route.click != null) {
                    String before = PageView.fingerprint(page);
                    String name = route.click.label + (route.click.row.isEmpty() ? "" : " [row: " + route.click.row + "]");
                    String entry = "Round " + round + ": clicked " + name + " (TypeSafe)";
                    try {
                        live(route.click).click(new Locator.ClickOptions().setTimeout(5000));
                        trace("click_item", name, route.confidence, "OK");
                        PageView.settle(page, SETTLE_MILLIS);
                        if (PageView.fingerprint(page).equals(before)) {
                            entry += " - the page did not change";
                            plannerNext = true;
                        }
                    } catch (Exception e) {
                        entry += ". PROBLEM: " + abbreviate(String.valueOf(e.getMessage()).replaceAll("\\s+", " "), 200);
                        plannerNext = true;
                    }
                    history.add(entry);
                    plainHistory.add(entry);
                    result.classifierRounds++;
                    helpless = 0;
                    continue;
                }
            }
            plannerNext = false;
            result.plannerRounds++;
            if (route != null && !route.done) result.routing.add("Round " + round + ": TypeSafe handed over (" + route.note + ")");

            // TypeSafe thinks the goal is done: the LLM audits that before the run stops.
            boolean audit = route != null && route.done && doneChecks < MAX_DONE_CHECKS;
            if (audit) doneChecks++;
            Optional<OpenAiWriterClient.PagePlan> maybePlan = writer.planPageStep(goal, view.summary(3000), history, audit);
            if (maybePlan.isEmpty()) {
                history.add("Round " + round + ": planner returned nothing usable");
                if (++helpless >= MAX_HELPLESS_ROUNDS) { result.stopReason = "stalled"; break; }
                continue;
            }
            OpenAiWriterClient.PagePlan plan = maybePlan.get();
            if (audit && plan.done && (!plan.fields.isEmpty() || !plan.clicks.isEmpty() || !plan.navigate.isBlank())) {
                plan.done = false;   // a reply that claims done but still asks for actions is not done
            }
            if (!audit && plan.done && doneChecks < MAX_DONE_CHECKS) {
                doneChecks++;
                Optional<OpenAiWriterClient.PagePlan> checked = writer.planPageStep(goal, view.summary(3000), history, true);
                if (checked.isPresent()) plan = checked.get();
                // A reply that claims done but still asks for actions is not done.
                if (plan.done && (!plan.fields.isEmpty() || !plan.clicks.isEmpty() || !plan.navigate.isBlank())) plan.done = false;
                if (!plan.done) history.add("Round " + round + ": done-check found work left: " + abbreviate(plan.reason, 300));
            }
            if (plan.done) {
                result.stopReason = "done";
                result.reason = plan.reason;
                break;
            }

            List<Done> did = new ArrayList<>();
            String problem = null;
            try {
                if (!plan.navigate.isBlank()) {
                    page.navigate(plan.navigate);
                    trace("navigate", plan.navigate, 1.0, "OK");
                    did.add(new Done("opened " + plan.navigate, "opened " + plan.navigate));
                    PageView.settle(page, SETTLE_MILLIS);
                    view = PageView.read(page);
                }
                if (!plan.fields.isEmpty()) {
                    fill(view, plan.fields, goal, did);
                }
                for (String click : plan.clicks) {
                    // Re-read after each click: the previous one may have changed the page.
                    if (!did.isEmpty() && did.get(did.size() - 1).full.startsWith("clicked")) {
                        PageView.settle(page, SETTLE_MILLIS);
                        view = PageView.read(page);
                    }
                    click(view, click, goal, did);
                }
            } catch (NeedsHelp e) {
                problem = e.getMessage();
            } catch (com.automationanywhere.botcommand.exception.BotCommandException e) {
                throw e;   // e.g. TypeSafe rejected the API key - retrying can't help
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage()).replaceAll("\\s+", " ").trim();
                problem = "Browser action failed: " + abbreviate(msg, 300);
                logger.warn("TypeSafeRunner: action failed", e);
            }

            List<String> full = new ArrayList<>(), plain = new ArrayList<>();
            for (Done d : did) { full.add(d.full); plain.add(d.plain); }
            String tail = problem == null ? "" : ". PROBLEM: " + problem;
            history.add("Round " + round + ": " + (did.isEmpty() ? "did nothing" : String.join("; ", full)) + tail);
            plainHistory.add("Round " + round + ": " + (did.isEmpty() ? "did nothing" : String.join("; ", plain))
                    + (problem == null ? "" : ". PROBLEM: the planned step could not be completed"));
            helpless = did.isEmpty() ? helpless + 1 : 0;
            if (helpless >= MAX_HELPLESS_ROUNDS) {
                result.stopReason = "stalled";
                result.reason = problem == null ? "" : problem;
                break;
            }
        }
        if (result.stopReason == null) result.stopReason = "step_limit";

        if (schema != null && !schema.isBlank()) {
            PageView.settle(page, SETTLE_MILLIS);
            String answer = writer.extractFinalAnswer(goal, schema, safeText(page));
            if (answer != null) result.data = answer;
        }
        logger.info("TypeSafeRunner finished - stopReason: {}, actions: {}", result.stopReason, result.steps.size());
        return result;
    }

    // ── routing: TypeSafe first ─────────────────────────────────────────────

    /** TypeSafe's answer for a round: a confident click, a confident "done", or neither. */
    static final class Route {
        PageView.Control click;
        boolean done;
        double confidence;
        String note = "";   // why TypeSafe handed the round over, for the history
    }

    // Mutually exclusive by construction: overlapping options read as doubt.
    private static final Map<String, String> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("click", "Click one control on this page: follow a link or row, open a form or menu, or press a "
                + "button. Choose the control in the element question.");
        KINDS.put("done", "Every part of the GOAL is already complete, per the actions taken and the page.");
        KINDS.put("none", "Unclear what to do next, or the next step needs information entered - hand it to the planner.");
    }

    Route route(PageView view, String goal, List<String> plainHistory) throws com.automationanywhere.botcommand.exception.BotCommandException {
        Route route = new Route();
        List<PageView.Control> cands = distinct(view.clickables());
        StringBuilder state = new StringBuilder();
        state.append("GOAL:\n").append(goal).append("\n\nACTIONS TAKEN (oldest first):\n");
        List<String> recent = plainHistory.subList(Math.max(0, plainHistory.size() - 12), plainHistory.size());
        state.append(recent.isEmpty() ? "(none yet)" : String.join("\n", recent));
        state.append("\n\nPAGE: ").append(view.title).append(" (").append(view.url).append(")\nPAGE TEXT:\n")
                .append(view.text.length() > 2000 ? view.text.substring(0, 2000) : view.text);

        Map<String, TypeSafeTypes.ChoiceQuestion> qs = new LinkedHashMap<>();
        Map<String, String> kinds = new LinkedHashMap<>(KINDS);
        if (cands.isEmpty()) kinds.remove("click");
        qs.put("kind", question("Which single move makes the most progress toward the GOAL right now? Items the "
                + "ACTIONS TAKEN or PAGE TEXT show as already done count as done - do not start them again.", kinds));
        if (!cands.isEmpty()) {
            Map<String, String> criteria = new LinkedHashMap<>();
            for (int i = 0; i < cands.size(); i++) criteria.put(String.valueOf(i), cands.get(i).describe());
            criteria.put(NONE, "No control here is the right next click");
            qs.put("element", question("If the next move is a click, which control?", criteria));
        }
        Map<String, TypeSafeTypes.ChoiceAnswer> a;
        try {
            a = ask(state.toString(), qs);
        } catch (NeedsHelp e) {
            return route;   // TypeSafe unavailable this round: the planner takes it
        }
        TypeSafeTypes.ChoiceAnswer kind = a.get("kind");
        if (kind == null) return route;
        route.note = String.format(Locale.ROOT, "%s %.2f", kind.choice, kind.confidence);
        if ("done".equals(kind.choice) && kind.confidence >= minConfidence) {
            route.done = true;
            route.confidence = kind.confidence;
        } else if ("click".equals(kind.choice)) {
            TypeSafeTypes.ChoiceAnswer el = a.get("element");
            if (el != null && !NONE.equals(el.choice)) {
                PageView.Control target = cands.get(Integer.parseInt(el.choice));
                double conf = Math.min(kind.confidence, el.confidence);
                route.note += String.format(Locale.ROOT, ", '%s' %.2f", target.label, el.confidence);
                if (conf >= ROUTE_CONFIDENCE) {
                    route.click = target;
                    route.confidence = conf;
                }
            }
        }
        return route;
    }

    // ── fields ──────────────────────────────────────────────────────────────

    private void fill(PageView view, Map<String, String> fields, String goal, List<Done> did) throws NeedsHelp {
        List<PageView.Control> candidates = view.fields();
        Map<String, PageView.Control> chosen = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        for (String label : fields.keySet()) {
            List<PageView.Control> exact = new ArrayList<>();
            for (PageView.Control c : candidates) if (norm(c.label).equals(norm(label))) exact.add(c);
            if (exact.size() == 1) {
                chosen.put(label, exact.get(0));
                confidence.put(label, 1.0);
            } else {
                pending.add(label);
            }
        }
        if (!pending.isEmpty()) {
            if (candidates.isEmpty()) {
                throw new NeedsHelp("No form fields on this page for: " + String.join(", ", pending)
                        + ". If the form opens after a click (e.g. an 'Add' button), click that first.");
            }
            Map<String, String> criteria = new LinkedHashMap<>();
            for (int i = 0; i < candidates.size(); i++) criteria.put(String.valueOf(i), candidates.get(i).describe());
            criteria.put(NONE, "No field on this page is meant for this value");
            Map<String, TypeSafeTypes.ChoiceQuestion> questions = new LinkedHashMap<>();
            for (int i = 0; i < pending.size(); i++) {
                questions.put("f" + i, question("Which form field should receive the value for '" + pending.get(i) + "'?", criteria));
            }
            Map<String, TypeSafeTypes.ChoiceAnswer> answers = ask("Task: " + goal + "\nPage: " + view.title, questions);
            for (int i = 0; i < pending.size(); i++) {
                String label = pending.get(i);
                TypeSafeTypes.ChoiceAnswer a = answers.get("f" + i);
                if (a == null || NONE.equals(a.choice)) {
                    throw new NeedsHelp("No field for '" + label + "' on this page. Fields here: " + labels(candidates)
                            + ". If the form opens after a click, click that first.");
                }
                if (a.confidence < minConfidence) {
                    throw new NeedsHelp(String.format(Locale.ROOT, "Unsure which field is '%s' (confidence %.2f). Fields here: %s",
                            label, a.confidence, labels(candidates)));
                }
                chosen.put(label, candidates.get(Integer.parseInt(a.choice)));
                confidence.put(label, a.confidence);
            }
        }
        List<PageView.Control> targets = new ArrayList<>(chosen.values());
        for (int i = 0; i < targets.size(); i++) {
            for (int j = i + 1; j < targets.size(); j++) {
                if (targets.get(i) == targets.get(j)) {
                    throw new NeedsHelp("Two values mapped to the same field '" + targets.get(i).label + "'");
                }
            }
        }

        for (Map.Entry<String, PageView.Control> e : chosen.entrySet()) {
            PageView.Control c = e.getValue();
            String value = fields.get(e.getKey());
            String name = c.label.isEmpty() ? e.getKey() : c.label;
            switch (c.kind) {
                case SELECT: {
                    String option = pickOption(c, value, goal);
                    live(c).selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(option));
                    trace("select_option", name + " = " + option, confidence.get(e.getKey()), "OK");
                    did.add(new Done("selected '" + option + "' in " + name, "selected an option in " + name));
                    break;
                }
                case CHECK: {
                    boolean on = Set.of("true", "yes", "on", "checked", "1", "x").contains(value.trim().toLowerCase(Locale.ROOT));
                    live(c).setChecked(on, new Locator.SetCheckedOptions().setTimeout(5000));
                    trace(on ? "check" : "uncheck", name, confidence.get(e.getKey()), "OK");
                    did.add(new Done((on ? "checked " : "unchecked ") + name, (on ? "checked " : "unchecked ") + name));
                    break;
                }
                case FILE: {
                    Path path = Paths.get(value.trim());
                    if (!path.toFile().exists()) throw new NeedsHelp("File to upload not found: " + value);
                    live(c).setInputFiles(path, new Locator.SetInputFilesOptions().setTimeout(5000));
                    trace("upload", name + " = " + path.getFileName(), confidence.get(e.getKey()), "OK");
                    did.add(new Done("uploaded " + path.getFileName() + " to " + name, "uploaded a file to " + name));
                    break;
                }
                default: {
                    fillText(live(c), value);
                    trace("type_text", name, confidence.get(e.getKey()), "OK");
                    // The value goes into HISTORY (planner only, never TypeSafe) so the planner can
                    // tell which item it already entered - without it, it re-entered one expense and
                    // skipped another. Passwords stay masked.
                    did.add(new Done("filled " + name + (c.secret ? "" : " = '" + abbreviate(value) + "'"), "filled " + name));
                }
            }
        }
    }

    /**
     * The control's locator, re-found if the page re-rendered it. Filling one field can rebuild
     * the rest of the form (picking a Category on Conquer drops the Attendees field and recreates
     * the upload input), which strips our marker attribute; re-read and match by kind/label/row.
     */
    private Locator live(PageView.Control c) {
        if (c.locator.count() > 0) return c.locator;
        PageView.settle(page, SETTLE_MILLIS);
        for (PageView.Control fresh : PageView.read(page).controls) {
            if (fresh.kind == c.kind && fresh.label.equals(c.label) && fresh.row.equals(c.row)) return fresh.locator;
        }
        return c.locator;   // let Playwright report it missing
    }

    /** Playwright's fill; &lt;input type=date&gt; only accepts ISO dates, so convert and retry once. */
    private void fillText(Locator locator, String value) {
        try {
            locator.fill(value, new Locator.FillOptions().setTimeout(5000));
        } catch (RuntimeException e) {
            String iso = toIsoDate(value);
            if (iso == null || !String.valueOf(e.getMessage()).contains("Malformed value")) throw e;
            locator.fill(iso, new Locator.FillOptions().setTimeout(5000));
        }
    }

    private String pickOption(PageView.Control c, String wanted, String goal) throws NeedsHelp {
        for (String o : c.options) if (norm(o).equals(norm(wanted))) return o;
        Map<String, String> criteria = new LinkedHashMap<>();
        for (int i = 0; i < c.options.size(); i++) criteria.put(String.valueOf(i), c.options.get(i));
        criteria.put(NONE, "None of these options fits");
        TypeSafeTypes.ChoiceAnswer a = ask("Task: " + goal + "\nDropdown: " + c.label,
                Map.of("o", question("Which option best matches: " + wanted + "?", criteria))).get("o");
        if (a == null || NONE.equals(a.choice) || a.confidence < minConfidence) {
            throw new NeedsHelp("No option matching '" + wanted + "' in " + c.label + ". Options: " + String.join(", ", c.options));
        }
        return c.options.get(Integer.parseInt(a.choice));
    }

    // ── clicks ──────────────────────────────────────────────────────────────

    private void click(PageView view, String phrase, String goal, List<Done> did) throws NeedsHelp {
        List<PageView.Control> candidates = distinct(view.clickables());
        String action = phrase;
        String rowText = null;
        Matcher m = IN_ROW.matcher(phrase.trim());
        if (m.matches()) {
            action = m.group(1);
            rowText = m.group(2);
        }
        for (PageView.Control c : view.controls) {
            if (c.kind == PageView.Kind.FILE && norm(c.label).equals(norm(action))) {
                throw new NeedsHelp("'" + c.label + "' is a file upload - put it in fields as {\"" + c.label
                        + "\": \"<full file path>\"} instead of clicking it.");
            }
        }
        PageView.Control target = null;
        double conf = 1.0;

        List<PageView.Control> exact = new ArrayList<>();
        for (PageView.Control c : candidates) {
            if (!sameAction(c.label, action)) continue;
            if (rowText != null && !norm(c.row).contains(norm(rowText))) continue;
            exact.add(c);
        }
        if (exact.size() == 1 || (exact.size() > 1 && allEquivalent(exact))) {
            target = exact.get(0);
        } else {
            Map<String, String> criteria = new LinkedHashMap<>();
            for (int i = 0; i < candidates.size(); i++) criteria.put(String.valueOf(i), candidates.get(i).describe());
            criteria.put(NONE, "Nothing on this page does this");
            TypeSafeTypes.ChoiceAnswer a = ask("Task: " + goal + "\nPage: " + view.title,
                    Map.of("c", question("Which element would a person click to: " + phrase + "? Submit buttons are "
                            + "often named after the form's action (e.g. 'Add Expense', 'Save'). For an action on one "
                            + "row, the element must be in that row. Choose 'none' only if nothing here could do it.",
                            criteria))).get("c");
            if (a == null || NONE.equals(a.choice)) {
                throw new NeedsHelp("Nothing on this page matches the click '" + phrase + "'. Clickable here: " + labels(candidates));
            }
            if (a.confidence < minConfidence) {
                throw new NeedsHelp(String.format(Locale.ROOT, "Unsure what to click for '%s' (confidence %.2f). Clickable here: %s",
                        phrase, a.confidence, labels(candidates)));
            }
            target = candidates.get(Integer.parseInt(a.choice));
            conf = a.confidence;
        }
        live(target).click(new Locator.ClickOptions().setTimeout(5000));
        String name = target.label + (target.row.isEmpty() ? "" : " [row: " + target.row + "]");
        trace("click_item", name, conf, "OK");
        did.add(new Done("clicked " + name, "clicked " + name));
    }

    /**
     * One control per distinct target. Apps repeat controls ("New Report" in the sidebar and on
     * the page, "Add Expense" in the header and the list), and TypeSafe splits its probability
     * between copies: it chose the right control every time at 0.36-0.64 confidence until the
     * copies were merged. A row only distinguishes controls when it says more than the label
     * (a nav list item's "row" is just its own text).
     */
    static List<PageView.Control> distinct(List<PageView.Control> cs) {
        Map<String, PageView.Control> seen = new LinkedHashMap<>();
        for (PageView.Control c : cs) {
            String row = norm(c.row).equals(norm(c.label)) ? "" : norm(c.row);
            seen.putIfAbsent(c.kind + "|" + norm(c.label) + "|" + row, c);
        }
        return new ArrayList<>(seen.values());
    }

    /** Duplicate controls that are the same thing (a header and an empty-state "Add Task" link). */
    private static boolean allEquivalent(List<PageView.Control> cs) {
        for (PageView.Control c : cs) {
            if (!c.describe().equals(cs.get(0).describe())) return false;
        }
        return true;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Map<String, TypeSafeTypes.ChoiceAnswer> ask(String state, Map<String, TypeSafeTypes.ChoiceQuestion> questions)
            throws NeedsHelp {
        TypeSafeTypes.Request req = new TypeSafeTypes.Request();
        req.state = state;
        req.questions.putAll(questions);
        TypeSafeTypes.Response resp;
        try {
            resp = typeSafe.classify(req);
        } catch (com.automationanywhere.botcommand.exception.BotCommandException e) {
            // Auth failures and the like must surface, not loop.
            throw e;
        } catch (Exception e) {
            throw new NeedsHelp("TypeSafe call failed: " + e.getMessage());
        }
        if (resp.usage != null) {
            result.typeSafeInputTokens += resp.usage.input_tokens;
            result.typeSafeOutputTokens += resp.usage.output_tokens;
        }
        return resp.answers == null ? Map.of() : resp.answers;
    }

    private static TypeSafeTypes.ChoiceQuestion question(String instructions, Map<String, String> criteria) {
        TypeSafeTypes.ChoiceQuestion q = new TypeSafeTypes.ChoiceQuestion();
        q.instructions = instructions;
        q.criteria = new LinkedHashMap<>(criteria);
        return q;
    }

    private void trace(String type, String target, Double confidence, String outcome) {
        StepTrace t = new StepTrace();
        t.actionType = type;
        t.target = target;
        t.confidence = confidence == null ? 1.0 : confidence;
        t.result = outcome;
        result.steps.add(t);
    }

    private static String labels(List<PageView.Control> cs) {
        List<String> out = new ArrayList<>();
        for (PageView.Control c : cs) {
            String s = "'" + c.label + "'" + (c.row.isEmpty() ? "" : " (row: " + c.row + ")");
            if (!out.contains(s)) out.add(s);
            if (out.size() >= 25) break;
        }
        return String.join(", ", out);
    }

    private static String abbreviate(String v) {
        return abbreviate(v, 60);
    }

    private static String abbreviate(String v, int max) {
        return v.length() > max ? v.substring(0, max) + "..." : v;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("*", "").replaceAll("[^a-z0-9]+", " ").trim();
    }

    static boolean sameAction(String a, String b) {
        String x = norm(a), y = norm(b);
        if (x.equals(y)) return true;
        for (Set<String> group : SYNONYMS) {
            if (group.contains(x) && group.contains(y)) return true;
        }
        return false;
    }

    static String toIsoDate(String value) {
        String[] formats = {"M/d/yyyy", "M/d/yy", "M-d-yyyy", "MMMM d, yyyy", "MMM d, yyyy", "d MMMM yyyy", "d MMM yyyy", "yyyy/M/d"};
        for (String f : formats) {
            try {
                return java.time.LocalDate.parse(value.trim(), java.time.format.DateTimeFormatter.ofPattern(f, Locale.ENGLISH)).toString();
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return null;
    }

    private static String safeText(Page page) {
        try {
            String txt = String.valueOf(page.evalOnSelector("body", "el => el.innerText || ''"));
            return txt.length() > 6000 ? txt.substring(0, 6000) : txt;
        } catch (Exception e) {
            return "";
        }
    }
}
