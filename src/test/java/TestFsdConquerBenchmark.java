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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Page;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Opt-in outcome benchmark on Conquer, the expense app from the "Run Agent" expense demo
 * (github.com/micahman33/Conquer), web part only: FSD can't read PDFs, so the receipt amounts
 * are given in the goal and the PDFs must still be attached. Scored from the app's localStorage.
 *
 * Needs Conquer on FSD_BENCH_CONQUER_URL (default http://localhost:3100), the receipt PDFs in
 * FSD_BENCH_RECEIPTS_DIR, OPENAI_API_KEY_TEST, and TYPESAFE_API_KEY_TEST for the TypeSafe action.
 */
public class TestFsdConquerBenchmark {

    private static final Object[][] TRUTH = {  // merchant keyword, amount, category, file
            {"pizza", 828.82, "Meals & Entertainment", "teampizza.pdf"},
            {"amy", 29.29, "Meals & Entertainment", "airportamys.pdf"},
            {"uber", 15.97, "Ground Transportation", "uber.pdf"},
    };

    private static String goal(String user, String dir) {
        return "Sign in to Conquer with username '" + user + "' and password 'demo'.\n"
                + "Create a new expense report named 'Q1 Leadership Offsite' for the trip March 17-19, 2026, "
                + "business purpose 'Quarterly planning offsite', using the 'Executive Travel' policy.\n"
                + "Add these three expenses to it, attaching each receipt file:\n"
                + "1. Parktown Pizza, team dinner, $828.82, Meals & Entertainment, dated 2026-03-18, receipt " + dir + "/teampizza.pdf\n"
                + "2. Amy's Drive Thru, airport meal, $29.29, Meals & Entertainment, dated 2026-03-17, receipt " + dir + "/airportamys.pdf\n"
                + "3. Uber, rideshare to airport, $15.97, Ground Transportation, dated 2026-03-19, receipt " + dir + "/uber.pdf\n"
                + "Do NOT submit the report - leave it in Draft. Then sign out.";
    }

    @Test
    public void benchmark() throws Exception {
        String openAiKey = System.getenv("OPENAI_API_KEY_TEST");
        String receipts = System.getenv("FSD_BENCH_RECEIPTS_DIR");
        if (openAiKey == null || openAiKey.isBlank() || receipts == null || !new File(receipts, "uber.pdf").exists()) {
            throw new SkipException("OPENAI_API_KEY_TEST / FSD_BENCH_RECEIPTS_DIR not set - skipping FSD Conquer benchmark");
        }
        String typeSafeKey = System.getenv("TYPESAFE_API_KEY_TEST");
        String base = System.getenv().getOrDefault("FSD_BENCH_CONQUER_URL", "http://localhost:3100");
        String model = System.getenv().getOrDefault("FSD_BENCH_MODEL", "gpt-5.4-mini");
        String actions = System.getenv().getOrDefault("FSD_BENCH_ACTIONS", "typesafe,multiturn");

        for (String kind : actions.split(",")) {
            kind = kind.trim();
            if (kind.equals("typesafe") && (typeSafeKey == null || typeSafeKey.isBlank())) continue;
            String user = "cq" + kind.substring(0, 3) + (System.currentTimeMillis() % 100000);
            String session = "cq_" + kind;
            java.util.HashMap<String, Object> sessions = new java.util.HashMap<>();
            LaunchBrowser launch = new LaunchBrowser();
            launch.setSessions(sessions);
            launch.action(session, base + "/", "true", "chromium");
            try {
                Page page = ((PlaywrightConnection) sessions.get(session)).getPage();
                long t0 = System.currentTimeMillis();
                Map<String, Value> out;
                if (kind.equals("typesafe")) {
                    FullSelfDrivingTypeSafe a = new FullSelfDrivingTypeSafe();
                    a.setSessions(sessions);
                    out = a.action(session, new SecureString(typeSafeKey.toCharArray()), new SecureString(openAiKey.toCharArray()),
                            model, goal(user, receipts), null, 40.0, 600.0, 0.4).get();
                } else {
                    FullSelfDrivingMultiTurn a = new FullSelfDrivingMultiTurn();
                    a.setSessions(sessions);
                    out = a.action(session, new SecureString(openAiKey.toCharArray()), model, goal(user, receipts),
                            null, 60.0, 30.0, 600.0).get();
                }
                long wall = (System.currentTimeMillis() - t0) / 1000;
                String sessionUser = String.valueOf(page.evaluate("() => sessionStorage.getItem('conquer_user')"));
                String state = String.valueOf(page.evaluate("() => JSON.stringify({users: localStorage.getItem('conquer_users'),"
                        + " reports: localStorage.getItem('conquer_reports'), expenses: localStorage.getItem('conquer_expenses')})"));
                List<String> checks = score(new Gson().fromJson(state, JsonObject.class), user, sessionUser);
                long passed = checks.stream().filter(c -> c.startsWith("PASS")).count();
                System.out.println("##### " + kind + " (" + user + ") wall=" + wall + "s stopReason=" + str(out, "stopReason")
                        + " reason=" + str(out, "reason")
                        + " openAiIn=" + str(out, "openAiInputTokens") + " openAiOut=" + str(out, "openAiOutputTokens")
                        + " typeSafeIn=" + str(out, "typeSafeInputTokens") + " decisions=[" + str(out, "decisions") + "]");
                checks.forEach(c -> System.out.println("  " + c));
                if (out.containsKey("history")) System.out.println("  HISTORY:\n" + str(out, "history"));
                if (out.containsKey("decisions")) System.out.println("  DECISIONS:\n" + str(out, "decisions"));
                System.out.println("  " + passed + "/" + checks.size() + " checks passed");
                for (int i = 1; i <= 120; i++) {
                    Value v = out.get((kind.equals("typesafe") ? "step" : "turn") + i);
                    if (v == null) break;
                    String line = ((DictionaryValue) v).get().toString();
                    System.out.println("    " + i + ": " + (line.length() > 300 ? line.substring(0, 300) : line));
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

    private static JsonElement parsed(JsonObject state, String key) {
        JsonElement e = state.get(key);
        return e == null || e.isJsonNull() ? null : new Gson().fromJson(e.getAsString(), JsonElement.class);
    }

    static List<String> score(JsonObject state, String username, String sessionUser) {
        List<String> out = new ArrayList<>();
        JsonElement users = parsed(state, "users");
        String userId = null;
        if (users != null) {
            for (var e : users.getAsJsonObject().entrySet()) {
                if (e.getKey().equalsIgnoreCase(username)) userId = e.getValue().getAsJsonObject().get("id").getAsString();
            }
        }
        out.add(check(userId != null, "signed in", username));
        JsonElement reports = parsed(state, "reports");
        JsonObject rep = null;
        int trips = 0;
        if (reports != null && userId != null) {
            for (JsonElement r : reports.getAsJsonArray()) {
                JsonObject o = r.getAsJsonObject();
                if (o.get("userId").getAsString().equals(userId) && "2026-03-17".equals(o.get("startDate").getAsString())
                        && "2026-03-19".equals(o.get("endDate").getAsString())) {
                    trips++;
                    if (rep == null) rep = o;
                }
            }
        }
        out.add(check(trips == 1, "exactly one report for Mar 17-19 2026", trips));
        out.add(check(rep != null && "Executive Travel".equals(rep.get("policy").getAsString()), "policy Executive Travel",
                rep == null ? "-" : rep.get("policy")));
        out.add(check(rep != null && "Draft".equals(rep.get("status").getAsString()), "status Draft (not submitted)",
                rep == null ? "-" : rep.get("status")));
        List<JsonObject> exps = new ArrayList<>();
        JsonElement expenses = parsed(state, "expenses");
        if (expenses != null && rep != null) {
            for (JsonElement e : expenses.getAsJsonArray()) {
                if (e.getAsJsonObject().get("reportId").getAsString().equals(rep.get("id").getAsString())) exps.add(e.getAsJsonObject());
            }
        }
        out.add(check(exps.size() == 3, "3 expenses", exps.size()));
        for (Object[] t : TRUTH) {
            JsonObject e = null;
            for (JsonObject x : exps) {
                String hay = (x.get("merchantName").getAsString() + " " + x.get("description").getAsString()).toLowerCase();
                if (hay.contains((String) t[0])) { e = x; break; }
            }
            if (e == null) {
                out.add(check(false, t[0] + ": present", "missing"));
                continue;
            }
            out.add(check(Math.abs(e.get("amount").getAsDouble() - (double) t[1]) < 0.005, t[0] + ": amount " + t[1], e.get("amount")));
            out.add(check(t[2].equals(e.get("category").getAsString()), t[0] + ": category", e.get("category")));
            boolean hasReceipt = e.has("receiptData") && !e.get("receiptData").isJsonNull() && !e.get("receiptData").getAsString().isEmpty();
            out.add(check(hasReceipt, t[0] + ": receipt attached", e.has("receiptName") ? e.get("receiptName") : "-"));
        }
        // Conquer's Sign Out clears the session user from sessionStorage.
        out.add(check("null".equals(sessionUser), "signed out", "null".equals(sessionUser) ? "no session user" : "still signed in"));
        return out;
    }

    private static String check(boolean ok, String name, Object detail) {
        return (ok ? "PASS  " : "FAIL  ") + name + "  " + detail;
    }
}
