import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.FullSelfDrivingMultiTurn;
import com.automationanywhere.botcommand.FullSelfDrivingTypeSafe;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import com.automationanywhere.core.security.SecureString;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Page;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Opt-in outcome benchmark: runs an FSD action on the full TaskDeck task and scores the
 * result from the app's own saved state (localStorage), not from the action's stopReason.
 *
 * Needs the demo app (github.com/micahman33/web-automation-demo-site) on
 * FSD_BENCH_TASKDECK_URL (default http://localhost:3200) plus OPENAI_API_KEY_TEST, and
 * TYPESAFE_API_KEY_TEST for the TypeSafe action. Skips cleanly otherwise.
 * FSD_BENCH_ACTIONS picks which to run (default "typesafe,multiturn").
 */
public class TestFsdTaskDeckBenchmark {

    private static final String SEED = "[" +
            "{\"id\":\"seed-1\",\"title\":\"Archive old invoices\",\"priority\":\"Low\",\"description\":\"\",\"status\":\"Open\"}," +
            "{\"id\":\"seed-2\",\"title\":\"Renew SSL certificate\",\"priority\":\"High\",\"description\":\"\",\"status\":\"Open\"}," +
            "{\"id\":\"seed-3\",\"title\":\"Book venue for team offsite\",\"priority\":\"Low\",\"description\":\"\",\"status\":\"Done\"}," +
            "{\"id\":\"seed-4\",\"title\":\"Update employee handbook\",\"priority\":\"Medium\",\"description\":\"\",\"status\":\"Open\"}]";

    private static String goal(String user) {
        return "Sign in to TaskDeck with username '" + user + "' and password 'demo123'.\n" +
                "1. Add a new task titled 'Prepare Q2 board deck' with priority High and description " +
                "'Slides for the April board meeting'.\n" +
                "2. Mark that new task complete.\n" +
                "3. Delete every task whose priority is Low.\n" +
                "4. Log out.";
    }

    @Test
    public void benchmark() throws Exception {
        String openAiKey = System.getenv("OPENAI_API_KEY_TEST");
        if (openAiKey == null || openAiKey.isBlank()) {
            throw new SkipException("OPENAI_API_KEY_TEST not set - skipping FSD TaskDeck benchmark");
        }
        String typeSafeKey = System.getenv("TYPESAFE_API_KEY_TEST");
        String base = System.getenv().getOrDefault("FSD_BENCH_TASKDECK_URL", "http://localhost:3200");
        String model = System.getenv().getOrDefault("FSD_BENCH_MODEL", "gpt-5.4-mini");
        String actions = System.getenv().getOrDefault("FSD_BENCH_ACTIONS", "typesafe,multiturn");

        for (String kind : actions.split(",")) {
            kind = kind.trim();
            if (kind.equals("typesafe") && (typeSafeKey == null || typeSafeKey.isBlank())) continue;
            String user = "fsd" + kind.substring(0, 3) + (System.currentTimeMillis() % 100000);
            String session = "bench_" + kind;
            HashMap<String, Object> sessions = new HashMap<>();
            LaunchBrowser launch = new LaunchBrowser();
            launch.setSessions(sessions);
            launch.action(session, base + "/login", "true", "chromium");
            try {
                Page page = ((PlaywrightConnection) sessions.get(session)).getPage();
                page.evaluate("([k, v]) => localStorage.setItem(k, v)",
                        List.of("taskdeck:tasks:" + user, SEED));
                page.reload();

                long t0 = System.currentTimeMillis();
                Map<String, Value> out;
                if (kind.equals("typesafe")) {
                    FullSelfDrivingTypeSafe a = new FullSelfDrivingTypeSafe();
                    a.setSessions(sessions);
                    out = a.action(session, new SecureString(typeSafeKey.toCharArray()),
                            new SecureString(openAiKey.toCharArray()), model, goal(user), null,
                            40.0, 300.0, 0.4).get();
                } else {
                    FullSelfDrivingMultiTurn a = new FullSelfDrivingMultiTurn();
                    a.setSessions(sessions);
                    out = a.action(session, new SecureString(openAiKey.toCharArray()), model, goal(user),
                            null, 60.0, 20.0, 300.0).get();
                }
                long wall = (System.currentTimeMillis() - t0) / 1000;

                String raw = String.valueOf(page.evaluate("k => localStorage.getItem(k)", "taskdeck:tasks:" + user));
                String loggedOutUrl = page.url();
                List<String> checks = score(raw, loggedOutUrl);
                long passed = checks.stream().filter(c -> c.startsWith("PASS")).count();
                System.out.println("##### " + kind + " (" + user + ") wall=" + wall + "s stopReason=" + str(out, "stopReason")
                        + " steps=" + (out.containsKey("numberOfSteps") ? str(out, "numberOfSteps") : str(out, "numberOfTurns"))
                        + " openAiIn=" + str(out, "openAiInputTokens") + " openAiOut=" + str(out, "openAiOutputTokens")
                        + " typeSafeIn=" + str(out, "typeSafeInputTokens") + " decisions=[" + str(out, "decisions") + "]");
                checks.forEach(c -> System.out.println("  " + c));
                System.out.println("  " + passed + "/" + checks.size() + " checks passed");
                for (int i = 1; i <= 60; i++) {
                    Value v = out.get((kind.equals("typesafe") ? "step" : "turn") + i);
                    if (v == null) break;
                    System.out.println("    " + i + ": " + ((DictionaryValue) v).get());
                }
            } finally {
                CloseBrowser close = new CloseBrowser();
                close.setSessions(sessions);
                close.action(session);
            }
        }
    }

    private static String str(Map<String, Value> m, String k) {
        Value v = m.get(k);
        return v instanceof StringValue ? ((StringValue) v).get() : "-";
    }

    static List<String> score(String raw, String url) {
        List<String> out = new ArrayList<>();
        JsonArray tasks = raw == null || raw.equals("null") ? new JsonArray() : new Gson().fromJson(raw, JsonArray.class);
        JsonObject added = null;
        int addedCount = 0;
        TreeSet<String> lows = new TreeSet<>(), others = new TreeSet<>();
        for (var e : tasks) {
            JsonObject t = e.getAsJsonObject();
            String title = t.get("title").getAsString().trim();
            if (title.equalsIgnoreCase("Prepare Q2 board deck")) { added = t; addedCount++; continue; }
            if ("Low".equals(t.get("priority").getAsString())) lows.add(title); else others.add(title);
        }
        out.add(check(addedCount == 1, "new task added exactly once", addedCount));
        out.add(check(added != null && "High".equals(added.get("priority").getAsString()), "new task priority High",
                added == null ? "-" : added.get("priority")));
        out.add(check(added != null && "Done".equals(added.get("status").getAsString()), "new task marked Done",
                added == null ? "-" : added.get("status")));
        out.add(check(added != null && added.get("description").getAsString().contains("board meeting"),
                "new task description", added == null ? "-" : added.get("description")));
        out.add(check(lows.isEmpty(), "no Low-priority tasks remain", lows));
        out.add(check(others.equals(new TreeSet<>(List.of("Renew SSL certificate", "Update employee handbook"))),
                "non-Low seeded tasks kept, untouched", others));
        out.add(check(url.contains("/login"), "logged out", url));
        return out;
    }

    private static String check(boolean ok, String name, Object detail) {
        return (ok ? "PASS  " : "FAIL  ") + name + "  " + detail;
    }
}
