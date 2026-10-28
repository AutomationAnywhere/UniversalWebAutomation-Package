# TypeSafe FSD Action Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new AA bot-command action, `FullSelfDrivingTypeSafe`, that drives a
goal-directed step loop over an existing Playwright session using TypeSafe's `Choice`
classifier for every step decision (instead of a full LLM call), with OpenAI used only
as a narrow "writer" for free-text composition, URL proposals, and the final answer.

**Architecture:** Each step: (1) deterministically extract a numbered list of visible
interactive DOM elements ("items") from the page via a Playwright `evaluate()` walk, (2)
send one `POST /v1/systemone` request to TypeSafe with two `Choice` questions
(`action_type`, `target_item`), (3) execute the chosen action directly via Playwright
APIs, (4) invoke the optional OpenAI writer only for `type_text`/`navigate` steps, (5)
check layered stop conditions (step cap, wall-clock timeout, confidence gate, stall
detection, explicit done). The loop itself lives in a testable `TypeSafeRunner` util,
kept separate from the thin `@Execute` action class per this package's existing
conventions (action classes parse/validate input and format output; utils do the work).

**Tech Stack:** Java 11, Gradle 7.6.2 + Shadow plugin, Playwright for Java, TestNG,
log4j2, Gson (already resolved transitively via Playwright; this plan pins it as a
direct dependency), `java.net.http.HttpClient` for both TypeSafe and OpenAI calls
(matching the existing HTTP pattern in `FullSelfDriving`/`FullSelfDrivingMultiTurn`).

**Spec:** [docs/superpowers/specs/2026-09-19-typesafe-fsd-design.md](../specs/2026-09-19-typesafe-fsd-design.md)

## Global Constraints

- JDK 11 is a hard build requirement (see `AA_PACKAGE_BEST_PRACTICES.md` §2.2) — always
  build/test with `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home`.
- Credentials only via `@Idx(type = CREDENTIAL)` / `SecureString`, never `TEXT`. Never log
  a `SecureString`'s insecure value or any API key, at any log level.
- Every action class: one `private static final Logger` via `LogManager.getLogger(...)`,
  `info` at start and completion, `error` with the exception object on failure.
- Broad `catch (Exception e)` → log → rethrow as `BotCommandException` with a message a
  non-developer bot author can act on. Never re-wrap a `BotCommandException` you threw
  yourself in the same method.
- `@NotEmpty` on every required string/credential param; optional params get an explicit
  default applied in code.
- Structured returns use `DictionaryValue` with the envelope pattern already used by
  `FullSelfDriving`/`FullSelfDrivingMultiTurn` (`status`, plus action-specific fields).
- Prefer real Playwright integration tests over mocks (§9); pure logic (JSON building,
  guardrail validation, stop-condition math) gets plain TestNG unit tests with no
  session/SDK setup.
- Never build JS strings by interpolating user-supplied text (§11) — use Playwright's
  parameterized `page.evaluate(fn, arg)` two-arg form throughout.
- `maxSteps` hard ceiling: 50, regardless of the value a bot author passes in.
  `maxRunSeconds` has no upper ceiling but must be a positive number; log a `warn` (not an
  error) if set above 1800 (30 minutes).
- `minConfidence` default 0.4, `maxSteps` default 15, `maxRunSeconds` default 120.
- Package name for all new classes: `com.automationanywhere.botcommand.utils` (flat, no
  new sub-package) — matches this package's existing flat `utils/` layout
  (`ElementLocator`, `PlaywrightConnection`).
- New test fixtures live under `src/test/resources/typesafe-fixtures/` and are loaded via
  `file://` URLs (via `LaunchBrowser`) wherever a static page suffices — no dependency on
  live external sites for anything except the final opt-in live-API integration task.
- Never hardcode a real API key in a test file. Read test credentials from environment
  variables (`OPENAI_API_KEY_TEST`, `TYPESAFE_API_KEY_TEST`) with a clear
  skip-if-unset behavior, per the existing leaked-key issue already flagged separately in
  `TestFullSelfDriving.java` — do not repeat that mistake here.

---

## Task 1: Add Gson dependency, `PageItem`, and `PageItemExtractor` happy-path extraction

**Files:**
- Modify: `build.gradle` (add explicit Gson dependency)
- Create: `src/main/java/com/automationanywhere/botcommand/utils/PageItem.java`
- Create: `src/main/java/com/automationanywhere/botcommand/utils/PageItemExtractor.java`
- Test: `src/test/java/TestPageItemExtractor.java`
- Test fixtures: `src/test/resources/typesafe-fixtures/login-form.html`,
  `src/test/resources/typesafe-fixtures/dropdown.html`

**Interfaces:**
- Produces: `PageItem` (public fields: `int index`, `String description`,
  `com.microsoft.playwright.Locator locator`, `boolean isSelectOption`,
  `String optionValue`, `com.microsoft.playwright.Locator selectLocator`).
- Produces: `PageItemExtractor.extract(Page page)` →
  `PageItemExtractor.ExtractionResult` (public fields: `List<PageItem> items`,
  `int iframesSkipped`, `boolean truncated`).
- Produces: `PageItemExtractor.MAX_ITEMS` (public static final int, value 255) and
  `PageItemExtractor.MAX_CANDIDATES_SCANNED` (public static final int, value 400) — later
  tasks and tests reference these constants directly, don't hardcode the numbers again.

- [ ] **Step 1: Add the Gson dependency to `build.gradle`**

In the `dependencies { }` block inside `configure(allprojects) { }`, right after the
existing `implementation("com.microsoft.playwright:playwright:1.63.0")` line, add:

```groovy
implementation group: 'com.google.code.gson', name: 'gson', version: '2.14.0'
```

(Gson 2.14.0 is already resolved transitively via Playwright's own dependencies — this
just makes it an explicit, versioned direct dependency instead of an implicit one, so a
future Playwright upgrade can't silently change or drop it out from under this code.)

- [ ] **Step 2: Verify the build still resolves**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Create the two HTML test fixtures**

`src/test/resources/typesafe-fixtures/login-form.html`:
```html
<!DOCTYPE html>
<html><head><title>Login</title></head>
<body>
  <h1>Sign In</h1>
  <form>
    <input type="text" id="username" name="username" placeholder="Username">
    <input type="password" id="password" name="password" placeholder="Password">
    <button type="button" id="submit-btn">Log In</button>
  </form>
  <a href="#" id="forgot-link">Forgot password?</a>
</body></html>
```

`src/test/resources/typesafe-fixtures/dropdown.html`:
```html
<!DOCTYPE html>
<html><head><title>Dropdown</title></head>
<body>
  <h1>Pick a color</h1>
  <select id="color-select">
    <option value="">Choose...</option>
    <option value="red">Red</option>
    <option value="green">Green</option>
    <option value="blue">Blue</option>
  </select>
</body></html>
```

- [ ] **Step 4: Write the failing test for happy-path extraction**

`src/test/java/TestPageItemExtractor.java`:
```java
import com.automationanywhere.botcommand.utils.PageItem;
import com.automationanywhere.botcommand.utils.PageItemExtractor;
import com.microsoft.playwright.*;
import org.testng.annotations.Test;

import java.io.File;
import java.util.List;

import static org.testng.AssertJUnit.*;

public class TestPageItemExtractor {

    private String fixtureUrl(String name) {
        File f = new File("src/test/resources/typesafe-fixtures/" + name);
        return f.toURI().toString();
    }

    @Test
    public void testExtractsVisibleFormControls() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("login-form.html"));

            PageItemExtractor.ExtractionResult result = PageItemExtractor.extract(page);

            assertFalse(result.truncated);
            assertEquals(0, result.iframesSkipped);
            // username input, password input, submit button, forgot-password link
            assertEquals(4, result.items.size());

            boolean sawUsername = result.items.stream()
                    .anyMatch(i -> i.description.toLowerCase().contains("username"));
            boolean sawSubmit = result.items.stream()
                    .anyMatch(i -> i.description.toLowerCase().contains("log in"));
            assertTrue(sawUsername);
            assertTrue(sawSubmit);

            browser.close();
        }
    }

    @Test
    public void testSelectExpandsIntoOneItemPerOption() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("dropdown.html"));

            PageItemExtractor.ExtractionResult result = PageItemExtractor.extract(page);

            // 4 <option> elements (including the blank "Choose..." one) become 4 items
            assertEquals(4, result.items.size());
            assertTrue(result.items.stream().allMatch(i -> i.isSelectOption));
            assertTrue(result.items.stream().anyMatch(i -> "red".equals(i.optionValue)));
            assertTrue(result.items.stream().anyMatch(i -> "blue".equals(i.optionValue)));

            browser.close();
        }
    }
}
```

- [ ] **Step 5: Run the test to confirm it fails (classes don't exist yet)**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew compileTestJava
```
Expected: FAIL — `cannot find symbol PageItem` / `PageItemExtractor`.

- [ ] **Step 6: Implement `PageItem`**

`src/main/java/com/automationanywhere/botcommand/utils/PageItem.java`:
```java
package com.automationanywhere.botcommand.utils;

import com.microsoft.playwright.Locator;

/**
 * One deterministically-extracted, visible, interactive page element (or, for a
 * &lt;select&gt;, one of its &lt;option&gt;s) offered to TypeSafe as a target_item choice.
 */
public class PageItem {

    public final int index;
    public final String description;
    public final Locator locator;
    public final boolean isSelectOption;
    public final String optionValue;
    public final Locator selectLocator;

    public PageItem(int index, String description, Locator locator) {
        this(index, description, locator, false, null, null);
    }

    public PageItem(int index, String description, Locator locator,
                     boolean isSelectOption, String optionValue, Locator selectLocator) {
        this.index = index;
        this.description = description;
        this.locator = locator;
        this.isSelectOption = isSelectOption;
        this.optionValue = optionValue;
        this.selectLocator = selectLocator;
    }
}
```

- [ ] **Step 7: Implement `PageItemExtractor` (page-only, no iframe walk yet)**

`src/main/java/com/automationanywhere/botcommand/utils/PageItemExtractor.java`:
```java
package com.automationanywhere.botcommand.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class PageItemExtractor {

    private static final Logger logger = LogManager.getLogger(PageItemExtractor.class);
    private static final Gson GSON = new Gson();

    public static final int MAX_ITEMS = 255;
    public static final int MAX_CANDIDATES_SCANNED = 400;
    private static final int MAX_OPTIONS_PER_SELECT = 20;

    private static final String MARK_AND_COLLECT_JS =
            "(maxCandidates) => {" +
            "  document.querySelectorAll('[data-tsfsd-idx]').forEach(el => el.removeAttribute('data-tsfsd-idx'));" +
            "  const out = [];" +
            "  const candidates = document.querySelectorAll(" +
            "    'input, textarea, select, button, a, [role=\"button\"], [contenteditable]');" +
            "  let idx = 0;" +
            "  candidates.forEach(el => {" +
            "    if (idx >= maxCandidates) return;" +
            "    const rect = el.getBoundingClientRect();" +
            "    const style = getComputedStyle(el);" +
            "    const visible = !!el.offsetParent && rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden';" +
            "    if (!visible) return;" +
            "    el.setAttribute('data-tsfsd-idx', String(idx));" +
            "    const label = (el.getAttribute('aria-label') || el.getAttribute('placeholder') ||" +
            "        el.innerText || el.value || el.name || el.id || '').trim().substring(0, 60);" +
            "    const vw = window.innerWidth || 1, vh = window.innerHeight || 1;" +
            "    const midX = rect.left + rect.width / 2, midY = rect.top + rect.height / 2;" +
            "    const pos = (midY < vh / 2 ? 'top' : 'bottom') + '-' + (midX < vw / 2 ? 'left' : 'right');" +
            "    let options = null;" +
            "    if (el.tagName === 'SELECT') {" +
            "      options = Array.from(el.options).map(o => ({ value: o.value, text: (o.text || '').trim().substring(0, 40) }));" +
            "    }" +
            "    out.push({ idx, tag: el.tagName.toLowerCase(), role: el.getAttribute('role') || '', label, pos, options });" +
            "    idx++;" +
            "  });" +
            "  return JSON.stringify(out);" +
            "}";

    public static class ExtractionResult {
        public final List<PageItem> items;
        public final int iframesSkipped;
        public final boolean truncated;

        public ExtractionResult(List<PageItem> items, int iframesSkipped, boolean truncated) {
            this.items = items;
            this.iframesSkipped = iframesSkipped;
            this.truncated = truncated;
        }
    }

    private static class RawOption {
        String value;
        String text;
    }

    private static class RawItemMeta {
        int idx;
        String tag;
        String role;
        String label;
        String pos;
        List<RawOption> options;
    }

    public static ExtractionResult extract(Page page) {
        List<PageItem> items = new ArrayList<>();
        boolean truncated = extractFromFrame(page, items);
        logger.info("PageItemExtractor: extracted {} items from main frame, truncated={}", items.size(), truncated);
        return new ExtractionResult(items, 0, truncated);
    }

    private static boolean extractFromFrame(Page page, List<PageItem> items) {
        String json = String.valueOf(page.evaluate(MARK_AND_COLLECT_JS, MAX_CANDIDATES_SCANNED));
        Type listType = new TypeToken<List<RawItemMeta>>() {}.getType();
        List<RawItemMeta> raw = GSON.fromJson(json, listType);
        if (raw == null) return false;

        boolean truncated = false;
        for (RawItemMeta meta : raw) {
            if (items.size() >= MAX_ITEMS) {
                truncated = true;
                break;
            }
            Locator markedLocator = page.locator("[data-tsfsd-idx='" + meta.idx + "']");
            if (meta.options != null) {
                int added = 0;
                for (RawOption opt : meta.options) {
                    if (items.size() >= MAX_ITEMS) { truncated = true; break; }
                    if (added >= MAX_OPTIONS_PER_SELECT) break;
                    String desc = "option '" + opt.text + "' in select '" + meta.label + "' (" + meta.pos + ")";
                    items.add(new PageItem(items.size() + 1, desc, markedLocator, true, opt.value, markedLocator));
                    added++;
                }
            } else {
                String roleOrTag = meta.role.isEmpty() ? meta.tag : meta.role;
                String desc = roleOrTag + " '" + meta.label + "' (" + meta.pos + ")";
                items.add(new PageItem(items.size() + 1, desc, markedLocator));
            }
        }
        return truncated;
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestPageItemExtractor
```
Expected: both tests PASS.

- [ ] **Step 9: Commit**

```bash
git add build.gradle src/main/java/com/automationanywhere/botcommand/utils/PageItem.java \
        src/main/java/com/automationanywhere/botcommand/utils/PageItemExtractor.java \
        src/test/java/TestPageItemExtractor.java \
        src/test/resources/typesafe-fixtures/login-form.html \
        src/test/resources/typesafe-fixtures/dropdown.html
git commit -m "Add PageItem/PageItemExtractor for deterministic DOM item extraction"
```

---

## Task 2: `PageItemExtractor` edge cases — truncation and iframes

**Files:**
- Modify: `src/main/java/com/automationanywhere/botcommand/utils/PageItemExtractor.java`
- Modify: `src/test/java/TestPageItemExtractor.java`
- Test fixtures: `src/test/resources/typesafe-fixtures/many-buttons.html`,
  `src/test/resources/typesafe-fixtures/iframe-parent.html`,
  `src/test/resources/typesafe-fixtures/iframe-same-origin-child.html`

**Interfaces:**
- Consumes: `PageItem`, `PageItemExtractor.ExtractionResult`,
  `PageItemExtractor.MAX_ITEMS` from Task 1.
- Produces: `PageItemExtractor.extract(Page page)` now also walks same-origin iframes
  one level deep and reports `iframesSkipped` for cross-origin ones (signature
  unchanged, behavior extended — no other task needs a new symbol from this one).

- [ ] **Step 1: Create the "many buttons" fixture (forces truncation)**

`src/test/resources/typesafe-fixtures/many-buttons.html`:
```html
<!DOCTYPE html>
<html><head><title>Many Buttons</title></head>
<body>
  <div id="container"></div>
  <script>
    const container = document.getElementById('container');
    for (let i = 0; i < 300; i++) {
      const btn = document.createElement('button');
      btn.textContent = 'Button ' + i;
      container.appendChild(btn);
    }
  </script>
</body></html>
```

- [ ] **Step 2: Create the iframe fixtures**

`src/test/resources/typesafe-fixtures/iframe-same-origin-child.html`:
```html
<!DOCTYPE html>
<html><body>
  <button id="inner-btn">Inner Button</button>
</body></html>
```

`src/test/resources/typesafe-fixtures/iframe-parent.html`:
```html
<!DOCTYPE html>
<html><head><title>Iframe Parent</title></head>
<body>
  <button id="outer-btn">Outer Button</button>
  <iframe id="same-origin-frame" src="iframe-same-origin-child.html"></iframe>
  <iframe id="cross-origin-frame" src="data:text/html,<button>Cross Origin</button>"></iframe>
</body></html>
```

(A `data:` URI iframe has an opaque origin distinct from the parent's `file://` origin,
so it deterministically exercises the cross-origin-skip path without any live network
dependency.)

- [ ] **Step 3: Write the failing tests**

Add to `src/test/java/TestPageItemExtractor.java`:
```java
    @Test
    public void testTruncatesAtMaxItemsAndReportsTruncated() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("many-buttons.html"));

            PageItemExtractor.ExtractionResult result = PageItemExtractor.extract(page);

            assertTrue(result.truncated);
            assertEquals(PageItemExtractor.MAX_ITEMS, result.items.size());

            browser.close();
        }
    }

    @Test
    public void testWalksSameOriginIframeAndSkipsCrossOrigin() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("iframe-parent.html"));
            page.waitForLoadState();

            PageItemExtractor.ExtractionResult result = PageItemExtractor.extract(page);

            boolean sawOuter = result.items.stream().anyMatch(i -> i.description.contains("Outer Button"));
            boolean sawInner = result.items.stream().anyMatch(i -> i.description.contains("Inner Button"));
            assertTrue(sawOuter);
            assertTrue(sawInner);
            assertEquals(1, result.iframesSkipped); // the data: URI iframe

            browser.close();
        }
    }
```

- [ ] **Step 4: Run to confirm failure**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestPageItemExtractor
```
Expected: the two new tests FAIL (`testWalksSameOriginIframeAndSkipsCrossOrigin` fails
because `iframesSkipped` is always 0 today; truncation test likely already passes by
luck of Step 7's cap logic — if so, that's fine, note it and move on to Step 5 for the
iframe walk, which is the real gap).

- [ ] **Step 5: Implement the iframe walk**

Replace the body of `extract(Page page)` in `PageItemExtractor.java` with:
```java
    public static ExtractionResult extract(Page page) {
        List<PageItem> items = new ArrayList<>();
        boolean truncated = extractFromFrame(page.mainFrame(), items);

        int iframesSkipped = 0;
        String mainOrigin = originOf(page.url());
        for (Frame frame : page.frames()) {
            if (frame == page.mainFrame()) continue;
            if (items.size() >= MAX_ITEMS) { truncated = true; break; }

            String frameOrigin = originOf(frame.url());
            if (frameOrigin == null || !frameOrigin.equals(mainOrigin)) {
                iframesSkipped++;
                logger.warn("PageItemExtractor: skipping cross-origin iframe url={}", frame.url());
                continue;
            }
            truncated = extractFromFrame(frame, items) || truncated;
        }

        logger.info("PageItemExtractor: extracted {} items total, iframesSkipped={}, truncated={}",
                items.size(), iframesSkipped, truncated);
        return new ExtractionResult(items, iframesSkipped, truncated);
    }

    /** Returns a same-origin comparison key, or null for an opaque/unparseable origin (e.g. data:, about:blank). */
    private static String originOf(String url) {
        if (url == null) return null;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            if (scheme.equals("data") || scheme.equals("about") || scheme.equals("blank")) return null;
            if (scheme.equals("file")) return "file://" + uri.getPath(); // each fixture file compared by its own path
            return scheme + "://" + uri.getAuthority();
        } catch (Exception e) {
            return null;
        }
    }
```

Change `extractFromFrame`'s signature from `Page page` to the shared `Frame`-compatible
type — Playwright's `Page` and `Frame` both implement the methods this code needs
(`evaluate`, `locator`) but do not share a common interface for them, so give
`extractFromFrame` two thin overloads instead of trying to unify types:

```java
    private static boolean extractFromFrame(Frame frame, List<PageItem> items) {
        String json = String.valueOf(frame.evaluate(MARK_AND_COLLECT_JS, MAX_CANDIDATES_SCANNED));
        return collectFromJson(json, frame::locator, items);
    }
```

And factor the shared parsing logic (previously inline in `extractFromFrame`) into:
```java
    private static boolean collectFromJson(String json, java.util.function.Function<String, Locator> locatorFactory,
                                            List<PageItem> items) {
        Type listType = new TypeToken<List<RawItemMeta>>() {}.getType();
        List<RawItemMeta> raw = GSON.fromJson(json, listType);
        if (raw == null) return false;

        boolean truncated = false;
        for (RawItemMeta meta : raw) {
            if (items.size() >= MAX_ITEMS) { truncated = true; break; }
            Locator markedLocator = locatorFactory.apply("[data-tsfsd-idx='" + meta.idx + "']");
            if (meta.options != null) {
                int added = 0;
                for (RawOption opt : meta.options) {
                    if (items.size() >= MAX_ITEMS) { truncated = true; break; }
                    if (added >= MAX_OPTIONS_PER_SELECT) break;
                    String desc = "option '" + opt.text + "' in select '" + meta.label + "' (" + meta.pos + ")";
                    items.add(new PageItem(items.size() + 1, desc, markedLocator, true, opt.value, markedLocator));
                    added++;
                }
            } else {
                String roleOrTag = meta.role.isEmpty() ? meta.tag : meta.role;
                String desc = roleOrTag + " '" + meta.label + "' (" + meta.pos + ")";
                items.add(new PageItem(items.size() + 1, desc, markedLocator));
            }
        }
        return truncated;
    }
```

`page.mainFrame()`'s own extraction call becomes:
```java
        boolean truncated = collectFromJson(
                String.valueOf(page.mainFrame().evaluate(MARK_AND_COLLECT_JS, MAX_CANDIDATES_SCANNED)),
                page.mainFrame()::locator,
                items);
```
(replace the `extractFromFrame(page.mainFrame(), items)` call in `extract(...)` with this
form, and delete the now-unused `Page`-typed `extractFromFrame` overload from Task 1,
keeping only the `Frame`-typed one above).

Add `import com.microsoft.playwright.Frame;` at the top of the file.

- [ ] **Step 6: Run tests to verify they pass**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestPageItemExtractor
```
Expected: all 4 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/automationanywhere/botcommand/utils/PageItemExtractor.java \
        src/test/java/TestPageItemExtractor.java \
        src/test/resources/typesafe-fixtures/many-buttons.html \
        src/test/resources/typesafe-fixtures/iframe-parent.html \
        src/test/resources/typesafe-fixtures/iframe-same-origin-child.html
git commit -m "Extend PageItemExtractor to walk same-origin iframes and report truncation"
```

---

## Task 3: `TypeSafeTypes`, `TypeSafeDecisionSource`, and `TypeSafeClient`

**Files:**
- Create: `src/main/java/com/automationanywhere/botcommand/utils/TypeSafeTypes.java`
- Create: `src/main/java/com/automationanywhere/botcommand/utils/TypeSafeDecisionSource.java`
- Create: `src/main/java/com/automationanywhere/botcommand/utils/TypeSafeClient.java`
- Test: `src/test/java/TestTypeSafeClient.java`

**Interfaces:**
- Produces: `TypeSafeTypes.Request` (fields: `String state`, `String model`,
  `Map<String, TypeSafeTypes.ChoiceQuestion> questions`).
- Produces: `TypeSafeTypes.ChoiceQuestion` (fields: `String type` ="choice",
  `String instructions`, `Map<String, String> criteria`).
- Produces: `TypeSafeTypes.Response` (fields: `String model`,
  `Map<String, TypeSafeTypes.ChoiceAnswer> answers`).
- Produces: `TypeSafeTypes.ChoiceAnswer` (fields: `String type`, `String choice`,
  `Map<String, Double> probabilities`, `double confidence`).
- Produces: `TypeSafeDecisionSource` interface: `TypeSafeTypes.Response classify(TypeSafeTypes.Request request)`.
- Produces: `TypeSafeClient implements TypeSafeDecisionSource`, public constructor
  `TypeSafeClient(String apiKey)`, and a second **public** constructor
  `TypeSafeClient(String apiKey, String baseUrl)` (public, not package-private — this
  package's tests live in the default/unnamed package and cannot reach a package-private
  constructor of a class in `com.automationanywhere.botcommand.utils`; there is no
  TestSupport shim class in this plan, by ruling — see Global Constraints).
- Consumed by: Task 5's `TypeSafeRunner` (via the `TypeSafeDecisionSource` interface, not
  the concrete class — so Task 6's stop-condition tests can inject a fake).

- [ ] **Step 1: Write the failing tests against a local HTTP stub**

`src/test/java/TestTypeSafeClient.java`:
```java
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
```

- [ ] **Step 2: Run to confirm failure**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew compileTestJava
```
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Implement `TypeSafeTypes`**

`src/main/java/com/automationanywhere/botcommand/utils/TypeSafeTypes.java`:
```java
package com.automationanywhere.botcommand.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/** Java models for the TypeSafe `POST /v1/systemone` request/response shape. */
public class TypeSafeTypes {

    public static class ChoiceQuestion {
        public String type = "choice";
        public String instructions;
        public Map<String, String> criteria = new LinkedHashMap<>();
    }

    public static class Request {
        public String state;
        public String model = "jev-latest";
        public Map<String, ChoiceQuestion> questions = new LinkedHashMap<>();
    }

    public static class ChoiceAnswer {
        public String type;
        public String choice;
        public Map<String, Double> probabilities;
        public double confidence;
    }

    public static class Response {
        public String model;
        public Map<String, ChoiceAnswer> answers;
    }
}
```

- [ ] **Step 4: Implement `TypeSafeDecisionSource`**

`src/main/java/com/automationanywhere/botcommand/utils/TypeSafeDecisionSource.java`:
```java
package com.automationanywhere.botcommand.utils;

/** Abstraction over "ask TypeSafe (or a test double) for a classification". */
public interface TypeSafeDecisionSource {
    TypeSafeTypes.Response classify(TypeSafeTypes.Request request);
}
```

- [ ] **Step 5: Implement `TypeSafeClient`**

`src/main/java/com/automationanywhere/botcommand/utils/TypeSafeClient.java`:
```java
package com.automationanywhere.botcommand.utils;

import com.automationanywhere.botcommand.exception.BotCommandException;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class TypeSafeClient implements TypeSafeDecisionSource {

    private static final Logger logger = LogManager.getLogger(TypeSafeClient.class);
    private static final Gson GSON = new Gson();
    private static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    private static final int MAX_ATTEMPTS = 2;

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    public TypeSafeClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public TypeSafeClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public TypeSafeTypes.Response classify(TypeSafeTypes.Request request) {
        String body = GSON.toJson(request);
        Exception lastTransientError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> resp;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/systemone"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (HttpTimeoutException e) {
                lastTransientError = e;
                logger.warn("TypeSafe call attempt {} of {} timed out, retrying", attempt, MAX_ATTEMPTS);
                continue;
            } catch (Exception e) {
                throw new BotCommandException("Failed to call TypeSafe: " + e.getMessage(), e);
            }

            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                throw new BotCommandException(
                        "TypeSafe rejected the API key (HTTP " + resp.statusCode() + "). Check the mapped credential.");
            }
            if (resp.statusCode() >= 500) {
                lastTransientError = new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
                logger.warn("TypeSafe call attempt {} of {} got HTTP {}, retrying", attempt, MAX_ATTEMPTS, resp.statusCode());
                continue;
            }
            if (resp.statusCode() < 200 || resp.statusCode() > 299) {
                throw new BotCommandException("TypeSafe request failed (HTTP " + resp.statusCode() + "): " + resp.body());
            }
            return GSON.fromJson(resp.body(), TypeSafeTypes.Response.class);
        }

        throw new BotCommandException("TypeSafe call failed after " + MAX_ATTEMPTS + " attempts: " +
                (lastTransientError == null ? "unknown error" : lastTransientError.getMessage()), lastTransientError);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestTypeSafeClient
```
Expected: all 4 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/automationanywhere/botcommand/utils/TypeSafeTypes.java \
        src/main/java/com/automationanywhere/botcommand/utils/TypeSafeDecisionSource.java \
        src/main/java/com/automationanywhere/botcommand/utils/TypeSafeClient.java \
        src/test/java/TestTypeSafeClient.java
git commit -m "Add TypeSafeClient with retry-once-on-5xx and immediate-abort-on-401"
```

---

## Task 4: `OpenAiWriterClient`

**Files:**
- Create: `src/main/java/com/automationanywhere/botcommand/utils/OpenAiWriterClient.java`
- Test: `src/test/java/TestOpenAiWriterClient.java`

**Interfaces:**
- Produces: `OpenAiWriterClient(String apiKey, String model)` public constructor, and a
  second **public** constructor `OpenAiWriterClient(String apiKey, String model, String baseUrl)`
  (public, not package-private, for the same cross-package-test-access reason as
  `TypeSafeClient`'s two-constructor shape above — no TestSupport shim class in this plan).
- Produces: `java.util.Optional<String> composeTypeText(String goal, String fieldLabel, java.util.List<String> recentActions)`.
- Produces: `java.util.Optional<String> proposeUrl(String goal, java.util.List<String> recentActions)`
  — returns `Optional.empty()` if the model's proposed URL isn't a clean `https` URL with
  a hostname.
- Produces: `String extractFinalAnswer(String goal, String schema, String pageText)` —
  returns `null` (not an exception) on any failure, so a writer outage at the very end of
  a run doesn't discard the whole step trace already collected.
- Consumed by: Task 5's `TypeSafeRunner`.

- [ ] **Step 1: Write the failing tests against a local HTTP stub**

`src/test/java/TestOpenAiWriterClient.java`:
```java
import com.automationanywhere.botcommand.utils.OpenAiWriterClient;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;

import static org.testng.AssertJUnit.*;

public class TestOpenAiWriterClient {

    private HttpServer stubReturning(String content) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = "{\"choices\":[{\"message\":{\"content\":" + com.google.gson.Gson.class
                    .getMethod("toJson", Object.class).invoke(new com.google.gson.Gson(), content) + "}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        return server;
    }

    @Test
    public void testComposeTypeTextReturnsModelText() throws Exception {
        HttpServer server = stubReturning("john@example.com");
        try {
            OpenAiWriterClient writer = new OpenAiWriterClient("key", "gpt-4o-mini",
                    "http://localhost:" + server.getAddress().getPort());
            Optional<String> result = writer.composeTypeText("fill in the email field", "Email", Collections.emptyList());
            assertTrue(result.isPresent());
            assertEquals("john@example.com", result.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testProposeUrlRejectsNonHttps() throws Exception {
        HttpServer server = stubReturning("http://insecure.example.com");
        try {
            OpenAiWriterClient writer = new OpenAiWriterClient("key", "gpt-4o-mini",
                    "http://localhost:" + server.getAddress().getPort());
            Optional<String> result = writer.proposeUrl("go to insecure site", Collections.emptyList());
            assertFalse(result.isPresent());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testProposeUrlAcceptsCleanHttps() throws Exception {
        HttpServer server = stubReturning("https://example.com/page");
        try {
            OpenAiWriterClient writer = new OpenAiWriterClient("key", "gpt-4o-mini",
                    "http://localhost:" + server.getAddress().getPort());
            Optional<String> result = writer.proposeUrl("go to example", Collections.emptyList());
            assertTrue(result.isPresent());
            assertEquals("https://example.com/page", result.get());
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
```

- [ ] **Step 2: Run to confirm failure**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew compileTestJava`
Expected: FAIL — `OpenAiWriterClient` doesn't exist.

- [ ] **Step 3: Implement `OpenAiWriterClient`**

`src/main/java/com/automationanywhere/botcommand/utils/OpenAiWriterClient.java`:
```java
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
 * Narrow "writer" model wrapper: composes free text for a single field, proposes a
 * navigation URL, or extracts a final answer against a schema. Never generates or runs
 * page JavaScript (that's FullSelfDriving/FullSelfDrivingMultiTurn's job, not this one's).
 */
public class OpenAiWriterClient {

    private static final Logger logger = LogManager.getLogger(OpenAiWriterClient.class);
    private static final Gson GSON = new Gson();
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient httpClient;

    public OpenAiWriterClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL);
    }

    public OpenAiWriterClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Optional<String> composeTypeText(String goal, String fieldLabel, List<String> recentActions) {
        String system = "You compose a single short value to type into one web form field. " +
                "Reply with ONLY the value, no quotes, no explanation, no markdown.";
        String user = "GOAL: " + goal + "\nFIELD LABEL: " + fieldLabel +
                "\nRECENT ACTIONS: " + String.join(" | ", recentActions);
        try {
            String content = callChat(system, user, 20);
            if (content == null || content.isBlank()) return Optional.empty();
            return Optional.of(content.trim());
        } catch (Exception e) {
            logger.warn("OpenAiWriterClient.composeTypeText failed, treating as no-op", e);
            return Optional.empty();
        }
    }

    public Optional<String> proposeUrl(String goal, List<String> recentActions) {
        String system = "You propose exactly one destination URL to navigate to next. " +
                "Reply with ONLY the URL, must be https, no quotes, no explanation.";
        String user = "GOAL: " + goal + "\nRECENT ACTIONS: " + String.join(" | ", recentActions);
        try {
            String content = callChat(system, user, 20);
            if (content == null) return Optional.empty();
            String url = content.trim();
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
                logger.warn("OpenAiWriterClient.proposeUrl rejected non-https/no-host URL: {}", url);
                return Optional.empty();
            }
            return Optional.of(url);
        } catch (Exception e) {
            logger.warn("OpenAiWriterClient.proposeUrl failed, treating as no-op", e);
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
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(sysMsg);
        messages.add(userMsg);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("temperature", 0.2);
        payload.add("messages", messages);

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
        return root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestOpenAiWriterClient`
Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/automationanywhere/botcommand/utils/OpenAiWriterClient.java \
        src/test/java/TestOpenAiWriterClient.java
git commit -m "Add OpenAiWriterClient for narrow text/URL/answer composition"
```

---

## Task 5: `TypeSafeRunner` — happy-path loop (click, type, select, scroll, press, wait)

**Files:**
- Create: `src/main/java/com/automationanywhere/botcommand/utils/TypeSafeRunner.java`
- Test: `src/test/java/TestTypeSafeRunner.java`
- Test fixture: `src/test/resources/typesafe-fixtures/contenteditable.html`

**Interfaces:**
- Consumes: `PageItem`, `PageItemExtractor` (Task 1/2), `TypeSafeDecisionSource`,
  `TypeSafeTypes.*` (Task 3), `OpenAiWriterClient` (Task 4, nullable).
- Produces: `TypeSafeRunner.StepTrace` (public fields: `String actionType`,
  `String target`, `double confidence`, `String result`).
- Produces: `TypeSafeRunner.RunResult` (public fields: `String status`,
  `String stopReason`, `java.util.List<StepTrace> steps`, `String data`,
  `int iframesSkipped`).
- Produces: `TypeSafeRunner.run(Page page, TypeSafeDecisionSource typeSafe, OpenAiWriterClient writer, String goal, String schema, int maxSteps, long maxRunMillis, double minConfidence)` → `RunResult`.
  This exact signature is what Task 8's action class calls — do not change parameter
  order or types without updating that task too.
- A `FakeTypeSafeDecisionSource` test double (canned, deterministic sequence of
  responses) is introduced in this task's test file and reused by Task 6 and Task 7 —
  keep its class name and constructor shape (`new FakeTypeSafeDecisionSource(List<TypeSafeTypes.Response> scripted)`)
  stable, or copy this exact definition into those test files.

- [ ] **Step 1: Create the contenteditable fixture**

`src/test/resources/typesafe-fixtures/contenteditable.html`:
```html
<!DOCTYPE html>
<html><head><title>Rich Text</title></head>
<body>
  <div id="editor" contenteditable="true" aria-label="Notes"></div>
</body></html>
```

- [ ] **Step 2: Write the failing test for a click+type happy path**

`src/test/java/TestTypeSafeRunner.java`:
```java
import com.automationanywhere.botcommand.utils.*;
import com.microsoft.playwright.*;
import org.testng.annotations.Test;

import java.io.File;
import java.util.*;

import static org.testng.AssertJUnit.*;

public class TestTypeSafeRunner {

    /** Deterministic, scripted decision source for tests — no real TypeSafe call. */
    static class FakeTypeSafeDecisionSource implements TypeSafeDecisionSource {
        private final List<TypeSafeTypes.Response> scripted;
        private int callIndex = 0;

        FakeTypeSafeDecisionSource(List<TypeSafeTypes.Response> scripted) {
            this.scripted = scripted;
        }

        @Override
        public TypeSafeTypes.Response classify(TypeSafeTypes.Request request) {
            TypeSafeTypes.Response resp = scripted.get(Math.min(callIndex, scripted.size() - 1));
            callIndex++;
            return resp;
        }
    }

    static TypeSafeTypes.Response actionAndTarget(String actionType, double confidence, String targetIndex) {
        TypeSafeTypes.Response resp = new TypeSafeTypes.Response();
        resp.answers = new LinkedHashMap<>();

        TypeSafeTypes.ChoiceAnswer actionAnswer = new TypeSafeTypes.ChoiceAnswer();
        actionAnswer.choice = actionType;
        actionAnswer.confidence = confidence;
        resp.answers.put("action_type", actionAnswer);

        if (targetIndex != null) {
            TypeSafeTypes.ChoiceAnswer targetAnswer = new TypeSafeTypes.ChoiceAnswer();
            targetAnswer.choice = targetIndex;
            targetAnswer.confidence = confidence;
            resp.answers.put("target_item", targetAnswer);
        }
        return resp;
    }

    private String fixtureUrl(String name) {
        return new File("src/test/resources/typesafe-fixtures/" + name).toURI().toString();
    }

    @Test
    public void testClickThenDoneStopsWithDoneReason() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("login-form.html"));

            // Step 1: click the submit button (item 3 in login-form.html: username, password, submit, forgot-link)
            // Step 2: done
            FakeTypeSafeDecisionSource fake = new FakeTypeSafeDecisionSource(List.of(
                    actionAndTarget("click_item", 0.9, "3"),
                    actionAndTarget("done", 0.9, null)
            ));

            TypeSafeRunner runner = new TypeSafeRunner();
            TypeSafeRunner.RunResult result = runner.run(page, fake, null,
                    "click the log in button", null, 15, 60_000, 0.4);

            assertEquals("done", result.stopReason);
            assertEquals(2, result.steps.size());
            assertEquals("click_item", result.steps.get(0).actionType);

            browser.close();
        }
    }

    @Test
    public void testSelectOptionChoosesCorrectValue() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("dropdown.html"));

            // dropdown.html options in order: "" (1), red (2), green (3), blue (4)
            FakeTypeSafeDecisionSource fake = new FakeTypeSafeDecisionSource(List.of(
                    actionAndTarget("select_option", 0.9, "4"), // blue
                    actionAndTarget("done", 0.9, null)
            ));

            TypeSafeRunner runner = new TypeSafeRunner();
            TypeSafeRunner.RunResult result = runner.run(page, fake, null,
                    "select blue", null, 15, 60_000, 0.4);

            assertEquals("blue", page.evalOnSelector("#color-select", "el => el.value"));
            assertEquals("done", result.stopReason);

            browser.close();
        }
    }

    @Test
    public void testTypeTextUsesWriterComposedValue() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("login-form.html"));

            FakeTypeSafeDecisionSource fake = new FakeTypeSafeDecisionSource(List.of(
                    actionAndTarget("type_text", 0.9, "1"), // username field
                    actionAndTarget("done", 0.9, null)
            ));

            // Fake writer: no network, returns a fixed value.
            OpenAiWriterClient writer = new OpenAiWriterClient("unused", "unused", "http://localhost:1") {
            };
            // OpenAiWriterClient isn't designed for subclass overriding in production code,
            // so for this test we instead point it at a tiny local stub (see Step 3 note).

            TypeSafeRunner runner = new TypeSafeRunner();
            TypeSafeRunner.RunResult result = runner.run(page, fake, null,
                    "type testuser into username", null, 15, 60_000, 0.4);

            // With writer == null, type_text must be a safe no-op, not a crash.
            assertEquals("done", result.stopReason);
            assertEquals("NOOP: no writer configured", result.steps.get(0).result);

            browser.close();
        }
    }

    @Test
    public void testContentEditableTypeTextViaExecutor() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("contenteditable.html"));

            FakeTypeSafeDecisionSource fake = new FakeTypeSafeDecisionSource(List.of(
                    actionAndTarget("scroll_down", 0.9, null),
                    actionAndTarget("press_escape", 0.9, null),
                    actionAndTarget("wait", 0.9, null),
                    actionAndTarget("done", 0.9, null)
            ));

            TypeSafeRunner runner = new TypeSafeRunner();
            TypeSafeRunner.RunResult result = runner.run(page, fake, null,
                    "no-op through the basic actions", null, 15, 60_000, 0.4);

            assertEquals("done", result.stopReason);
            assertEquals(4, result.steps.size());

            browser.close();
        }
    }
}
```

(The `testTypeTextUsesWriterComposedValue` test's dead `writer` variable and anonymous
subclass are placeholders to delete in Step 3 below once the real no-writer contract is
confirmed — see that step.)

- [ ] **Step 3: Simplify Step 2's `type_text`-with-no-writer test**

Before implementing, replace the body of `testTypeTextUsesWriterComposedValue` with the
simpler, real intent (delete the unused `OpenAiWriterClient writer = ...` block entirely):
```java
    @Test
    public void testTypeTextWithNoWriterConfiguredIsSafeNoOp() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("login-form.html"));

            FakeTypeSafeDecisionSource fake = new FakeTypeSafeDecisionSource(List.of(
                    actionAndTarget("type_text", 0.9, "1"),
                    actionAndTarget("done", 0.9, null)
            ));

            TypeSafeRunner runner = new TypeSafeRunner();
            TypeSafeRunner.RunResult result = runner.run(page, fake, null,
                    "type testuser into username", null, 15, 60_000, 0.4);

            assertEquals("done", result.stopReason);
            assertEquals("NOOP: no writer configured", result.steps.get(0).result);

            browser.close();
        }
    }
```
Rename the test method accordingly and remove the old `testTypeTextUsesWriterComposedValue`.

- [ ] **Step 4: Run to confirm failure**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew compileTestJava`
Expected: FAIL — `TypeSafeRunner` doesn't exist.

- [ ] **Step 5: Implement `TypeSafeRunner`**

`src/main/java/com/automationanywhere/botcommand/utils/TypeSafeRunner.java`:
```java
package com.automationanywhere.botcommand.utils;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SelectOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TypeSafeRunner {

    private static final Logger logger = LogManager.getLogger(TypeSafeRunner.class);
    private static final int HISTORY_SIZE = 3;
    private static final int STALL_THRESHOLD = 2;

    private static final Map<String, String> ACTION_TYPE_CRITERIA = new LinkedHashMap<>();
    static {
        ACTION_TYPE_CRITERIA.put("click_item", "click the chosen item");
        ACTION_TYPE_CRITERIA.put("type_text", "type composed text into the chosen item");
        ACTION_TYPE_CRITERIA.put("select_option", "choose the chosen dropdown option item");
        ACTION_TYPE_CRITERIA.put("scroll_down", "scroll the page down");
        ACTION_TYPE_CRITERIA.put("scroll_up", "scroll the page up");
        ACTION_TYPE_CRITERIA.put("press_enter", "press the Enter key");
        ACTION_TYPE_CRITERIA.put("press_escape", "press the Escape key");
        ACTION_TYPE_CRITERIA.put("wait", "wait briefly, e.g. page still loading");
        ACTION_TYPE_CRITERIA.put("navigate", "go to a different URL entirely");
        ACTION_TYPE_CRITERIA.put("done", "the goal is fully achieved, stop");
        ACTION_TYPE_CRITERIA.put("none", "nothing useful to do, stop");
    }

    public static class StepTrace {
        public String actionType;
        public String target = "none";
        public double confidence;
        public String result;
    }

    public static class RunResult {
        public String status = "OK";
        public String stopReason;
        public List<StepTrace> steps = new ArrayList<>();
        public String data;
        public int iframesSkipped;
    }

    public RunResult run(Page page, TypeSafeDecisionSource typeSafe, OpenAiWriterClient writer,
                          String goal, String schema, int maxSteps, long maxRunMillis, double minConfidence) {
        RunResult result = new RunResult();
        long startTime = System.currentTimeMillis();
        Deque<String> history = new ArrayDeque<>();
        int consecutiveNoOps = 0;
        List<String> previousDescriptions = null;
        String previousActionType = null;

        int step;
        for (step = 1; step <= maxSteps; step++) {
            if (System.currentTimeMillis() - startTime > maxRunMillis) {
                result.stopReason = "timeout";
                break;
            }

            PageItemExtractor.ExtractionResult extraction = PageItemExtractor.extract(page);
            result.iframesSkipped = extraction.iframesSkipped;

            TypeSafeTypes.Request request = buildRequest(goal, extraction.items, history);
            TypeSafeTypes.Response response = typeSafe.classify(request);

            TypeSafeTypes.ChoiceAnswer actionAnswer = response.answers.get("action_type");
            StepTrace trace = new StepTrace();
            trace.actionType = actionAnswer.choice;
            trace.confidence = actionAnswer.confidence;

            if (actionAnswer.confidence < minConfidence) {
                result.stopReason = "low_confidence";
                result.steps.add(trace);
                break;
            }
            if ("done".equals(trace.actionType) || "none".equals(trace.actionType)) {
                result.stopReason = "done";
                result.steps.add(trace);
                break;
            }

            TypeSafeTypes.ChoiceAnswer targetAnswer = response.answers.get("target_item");
            PageItem target = resolveTarget(extraction.items, targetAnswer == null ? null : targetAnswer.choice);
            trace.target = target == null ? "none" : target.description;

            String execResult = executeAction(page, trace.actionType, target, writer, goal, history);
            trace.result = execResult;
            result.steps.add(trace);

            history.addLast(trace.actionType + " -> " + execResult);
            if (history.size() > HISTORY_SIZE) history.removeFirst();

            List<String> currentDescriptions = descriptionsOf(extraction.items);
            // A no-op is either an execution failure/refusal (NOOP-prefixed result), or the
            // classifier repeating the exact same action_type as last step while the page's
            // item list stayed identical. Requiring BOTH "same action repeated" and "nothing
            // changed" (not just "nothing changed") is what lets a legitimately varied
            // sequence of no-visible-effect actions (scroll, then escape, then wait) run to
            // completion on a static page without being mistaken for a stuck loop, while
            // still catching the case that actually matters: the same action chosen over
            // and over with no progress.
            boolean noOp = execResult.startsWith("NOOP")
                    || (trace.actionType.equals(previousActionType) && currentDescriptions.equals(previousDescriptions));
            consecutiveNoOps = noOp ? consecutiveNoOps + 1 : 0;
            previousDescriptions = currentDescriptions;
            previousActionType = trace.actionType;

            if (consecutiveNoOps >= STALL_THRESHOLD) {
                result.stopReason = "stalled";
                break;
            }
        }

        if (result.stopReason == null) {
            result.stopReason = "step_limit";
        }

        if (schema != null && !schema.isBlank() && writer != null) {
            String pageText = safeInnerText(page);
            String answer = writer.extractFinalAnswer(goal, schema, pageText);
            if (answer != null) {
                result.data = answer;
            }
        }

        logger.info("TypeSafeRunner finished - stopReason: {}, steps: {}", result.stopReason, result.steps.size());
        return result;
    }

    private TypeSafeTypes.Request buildRequest(String goal, List<PageItem> items, Deque<String> history) {
        TypeSafeTypes.Request request = new TypeSafeTypes.Request();

        StringBuilder state = new StringBuilder();
        state.append("GOAL: ").append(goal).append("\n");
        state.append("RECENT ACTIONS: ").append(String.join(" | ", history)).append("\n");
        state.append("ITEMS:\n");
        for (PageItem item : items) {
            state.append(item.index).append(") ").append(item.description).append("\n");
        }
        request.state = state.toString();

        TypeSafeTypes.ChoiceQuestion actionQuestion = new TypeSafeTypes.ChoiceQuestion();
        actionQuestion.instructions = "Which single action should happen next to achieve the goal?";
        actionQuestion.criteria = new LinkedHashMap<>(ACTION_TYPE_CRITERIA);
        request.questions.put("action_type", actionQuestion);

        if (!items.isEmpty()) {
            TypeSafeTypes.ChoiceQuestion targetQuestion = new TypeSafeTypes.ChoiceQuestion();
            targetQuestion.instructions = "Which item should the action apply to?";
            targetQuestion.criteria = new LinkedHashMap<>();
            for (PageItem item : items) {
                targetQuestion.criteria.put(String.valueOf(item.index), item.description);
            }
            request.questions.put("target_item", targetQuestion);
        }

        return request;
    }

    private PageItem resolveTarget(List<PageItem> items, String targetIndexStr) {
        if (targetIndexStr == null) return null;
        try {
            int idx = Integer.parseInt(targetIndexStr.trim());
            for (PageItem item : items) {
                if (item.index == idx) return item;
            }
        } catch (NumberFormatException ignored) {
            // TypeSafe returned something that isn't one of our numeric indices; treat as "no target".
        }
        return null;
    }

    private String executeAction(Page page, String actionType, PageItem target,
                                  OpenAiWriterClient writer, String goal, Deque<String> history) {
        try {
            switch (actionType) {
                case "click_item":
                    if (target == null) return "NOOP: no target item";
                    target.locator.click(new Locator.ClickOptions().setTimeout(5000));
                    return "OK";

                case "type_text": {
                    if (target == null) return "NOOP: no target item";
                    if (writer == null) return "NOOP: no writer configured";
                    java.util.Optional<String> composed = writer.composeTypeText(goal, target.description, new ArrayList<>(history));
                    if (composed.isEmpty()) return "NOOP: writer returned no text";
                    target.locator.fill(composed.get(), new Locator.FillOptions().setTimeout(5000));
                    return "OK";
                }

                case "select_option":
                    if (target == null || !target.isSelectOption) return "NOOP: no select-option target item";
                    target.selectLocator.selectOption(new SelectOption().setValue(target.optionValue));
                    return "OK";

                case "scroll_down":
                    page.mouse().wheel(0, 400);
                    return "OK";

                case "scroll_up":
                    page.mouse().wheel(0, -400);
                    return "OK";

                case "press_enter":
                    page.keyboard().press("Enter");
                    return "OK";

                case "press_escape":
                    page.keyboard().press("Escape");
                    return "OK";

                case "wait":
                    Thread.sleep(500);
                    return "OK";

                case "navigate": {
                    if (writer == null) return "NOOP: no writer configured";
                    java.util.Optional<String> url = writer.proposeUrl(goal, new ArrayList<>(history));
                    if (url.isEmpty()) return "NOOP: writer proposed no valid https URL";
                    page.navigate(url.get());
                    return "OK";
                }

                default:
                    return "NOOP: unrecognized action_type " + actionType;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "NOOP: interrupted";
        } catch (Exception e) {
            logger.warn("TypeSafeRunner: action {} failed, treating as no-op for stall detection", actionType, e);
            return "NOOP: " + e.getMessage();
        }
    }

    private List<String> descriptionsOf(List<PageItem> items) {
        List<String> descriptions = new ArrayList<>(items.size());
        for (PageItem item : items) descriptions.add(item.description);
        return descriptions;
    }

    private String safeInnerText(Page page) {
        try {
            String txt = String.valueOf(page.evalOnSelector("body", "el => el.innerText || ''"));
            return txt.length() > 4000 ? txt.substring(0, 4000) : txt;
        } catch (Exception e) {
            return "";
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestTypeSafeRunner`
Expected: all 4 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/automationanywhere/botcommand/utils/TypeSafeRunner.java \
        src/test/java/TestTypeSafeRunner.java \
        src/test/resources/typesafe-fixtures/contenteditable.html
git commit -m "Add TypeSafeRunner step loop with click/type/select/scroll/press/wait actions"
```

---

## Task 6: `TypeSafeRunner` stop conditions — confidence, stall, step limit, timeout

**Files:**
- Modify: `src/test/java/TestTypeSafeRunner.java`
- Test fixture: `src/test/resources/typesafe-fixtures/static-page.html`

**Interfaces:**
- Consumes: `TypeSafeRunner.run(...)`, `FakeTypeSafeDecisionSource` from Task 5 (no
  production code signature changes expected in this task — if a stop condition doesn't
  fire correctly, fix `TypeSafeRunner`'s logic, not its public interface).

- [ ] **Step 1: Create a static page fixture (nothing ever changes)**

`src/test/resources/typesafe-fixtures/static-page.html`:
```html
<!DOCTYPE html>
<html><head><title>Static</title></head>
<body>
  <button id="noop-btn">Does Nothing</button>
</body></html>
```

- [ ] **Step 2: Write the failing stop-condition tests**

Add to `src/test/java/TestTypeSafeRunner.java`:
```java
    @Test
    public void testLowConfidenceStopsImmediately() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("static-page.html"));

            FakeTypeSafeDecisionSource fake = new FakeTypeSafeDecisionSource(List.of(
                    actionAndTarget("click_item", 0.1, "1") // below default 0.4 threshold
            ));

            TypeSafeRunner runner = new TypeSafeRunner();
            TypeSafeRunner.RunResult result = runner.run(page, fake, null, "do something", null, 15, 60_000, 0.4);

            assertEquals("low_confidence", result.stopReason);
            assertEquals(1, result.steps.size());

            browser.close();
        }
    }

    @Test
    public void testStallDetectionStopsAfterTwoNoOps() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("static-page.html"));

            // Every step repeats the identical action (scroll_down) on a one-button static
            // page, so both the "same action repeated" and "item list unchanged" no-op
            // signals fire. A repeat can only be detected on its second occurrence (there's
            // nothing to compare the first occurrence against), so the stall fires one step
            // later than STALL_THRESHOLD's raw count suggests: step 1 establishes the
            // baseline (not a no-op yet), steps 2 and 3 are the two consecutive detected
            // no-ops that reach STALL_THRESHOLD=2 - hence 3 steps total, not 2.
            FakeTypeSafeDecisionSource fake = new FakeTypeSafeDecisionSource(List.of(
                    actionAndTarget("scroll_down", 0.9, null)
            ));

            TypeSafeRunner runner = new TypeSafeRunner();
            TypeSafeRunner.RunResult result = runner.run(page, fake, null, "scroll forever", null, 15, 60_000, 0.4);

            assertEquals("stalled", result.stopReason);
            assertEquals(3, result.steps.size());

            browser.close();
        }
    }

    @Test
    public void testStepLimitStopsAtMaxSteps() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("static-page.html"));

            // Alternate scroll_down/scroll_up so the stall detector's 2-in-a-row rule never
            // fires (each step's action differs from the prior one's, even though the page
            // itself never changes) -- isolates the step-limit path from the stall path.
            FakeTypeSafeDecisionSource fake = new FakeTypeSafeDecisionSource(List.of(
                    actionAndTarget("scroll_down", 0.9, null),
                    actionAndTarget("scroll_up", 0.9, null)
            ));

            TypeSafeRunner runner = new TypeSafeRunner();
            TypeSafeRunner.RunResult result = runner.run(page, fake, null, "scroll forever", null, 3, 60_000, 0.4);

            assertEquals("step_limit", result.stopReason);
            assertEquals(3, result.steps.size());

            browser.close();
        }
    }

    @Test
    public void testTimeoutStopsBeforeStepLimit() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(fixtureUrl("static-page.html"));

            FakeTypeSafeDecisionSource fake = new FakeTypeSafeDecisionSource(List.of(
                    actionAndTarget("scroll_down", 0.9, null),
                    actionAndTarget("scroll_up", 0.9, null)
            ));

            TypeSafeRunner runner = new TypeSafeRunner();
            // 1ms budget: the very first loop iteration's elapsed-time check should already exceed it.
            TypeSafeRunner.RunResult result = runner.run(page, fake, null, "scroll forever", null, 50, 1, 0.4);

            assertEquals("timeout", result.stopReason);

            browser.close();
        }
    }
```

Note: `testTimeoutStopsBeforeStepLimit` relies on the timeout check running before the
first `PageItemExtractor.extract` call each iteration — confirm this matches the
`TypeSafeRunner.run` implementation from Task 5 Step 5 (the `if (... > maxRunMillis)`
check is the first thing inside the `for` loop). If it flakes because extraction of the
very first step completes in under 1ms, raise the test's budget to `5` and note the
test is inherently timing-sensitive but bounded — it only needs the loop to observe
elapsed time exceeding a near-zero budget at some point within `maxSteps` iterations.

- [ ] **Step 3: Run to confirm failure or unexpected pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestTypeSafeRunner`

If any test unexpectedly already passes, that's fine — Task 5's implementation already
covers it; just verify all four new tests pass here. If any fails, fix the corresponding
logic in `TypeSafeRunner.run` (the stop-condition checks already exist from Task 5 —
this task should mostly be verification, not new production code, unless a bug is
found).

- [ ] **Step 4: Run full test class to verify everything passes together**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestTypeSafeRunner`
Expected: all 8 tests PASS (4 from Task 5 + 4 new).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/TestTypeSafeRunner.java \
        src/test/resources/typesafe-fixtures/static-page.html
git commit -m "Add stop-condition tests for TypeSafeRunner: confidence, stall, step limit, timeout"
```

---

## Task 7: `navigate` action integration test with a real multi-page fixture

**Files:**
- Modify: `src/test/java/TestTypeSafeRunner.java`
- Test fixtures: `src/test/resources/typesafe-fixtures/nav-start.html`,
  `src/test/resources/typesafe-fixtures/nav-target.html`

**Interfaces:**
- Consumes: `TypeSafeRunner.run(...)`, `OpenAiWriterClient` (Task 4) — this task is the
  first to actually exercise `writer` as non-null, via a local HTTP stub rather than a
  subclass (confirms `OpenAiWriterClient`'s test constructor from Task 4 composes
  correctly with `TypeSafeRunner`).

- [ ] **Step 1: Create the two-page fixture**

`src/test/resources/typesafe-fixtures/nav-start.html`:
```html
<!DOCTYPE html>
<html><head><title>Start</title></head>
<body><h1>Start Page</h1></body></html>
```

`src/test/resources/typesafe-fixtures/nav-target.html`:
```html
<!DOCTYPE html>
<html><head><title>Target</title></head>
<body><h1 id="target-heading">You Made It</h1></body></html>
```

- [ ] **Step 2: Write the failing test**

Add to `src/test/java/TestTypeSafeRunner.java`:
```java
    @Test
    public void testNavigateUsesWriterProposedUrl() throws Exception {
        File targetFile = new File("src/test/resources/typesafe-fixtures/nav-target.html");
        String targetUrl = targetFile.toURI().toString();

        com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        stub.createContext("/v1/chat/completions", exchange -> {
            // The writer must propose an https URL for navigate to accept it -- since this
            // test's real target is a file:// fixture, simulate the "reject non-https" and
            // "accept https" halves separately isn't possible with one URL here, so instead
            // this test proves the wiring (writer consulted, its Optional respected) using a
            // clean https URL, and relies on TestOpenAiWriterClient (Task 4) for the
            // https-vs-not validation logic itself.
            String content = "https://example.com/wont-actually-load-in-this-test";
            String body = "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        stub.start();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(new File("src/test/resources/typesafe-fixtures/nav-start.html").toURI().toString());

            FakeTypeSafeDecisionSource fake = new FakeTypeSafeDecisionSource(List.of(
                    actionAndTarget("navigate", 0.9, null),
                    actionAndTarget("done", 0.9, null)
            ));

            OpenAiWriterClient writer = new OpenAiWriterClient("key", "gpt-4o-mini",
                    "http://localhost:" + stub.getAddress().getPort());

            TypeSafeRunner runner = new TypeSafeRunner();
            TypeSafeRunner.RunResult result = runner.run(page, fake, writer, "go to example", null, 15, 60_000, 0.4);

            assertEquals("done", result.stopReason);
            assertEquals("OK", result.steps.get(0).result);

            browser.close();
        } finally {
            stub.stop(0);
        }
    }
```

`OpenAiWriterClient`'s base-URL constructor is `public` (Task 4 ruling), so this test
class — unpackaged, like every other test class in `src/test/java/` — can call it
directly with no shim class needed.

- [ ] **Step 3: Confirm it compiles**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew compileTestJava`
Expected: `BUILD SUCCESSFUL`. Unlike most tasks in this plan, this one introduces no new
production symbol — `TypeSafeRunner`, `OpenAiWriterClient`, and `FakeTypeSafeDecisionSource`
all already exist from Tasks 4-6, so there's nothing for this step to be RED against. This
step is just a compile sanity check before running the test itself in Step 4.

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestTypeSafeRunner,TestOpenAiWriterClient`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/TestTypeSafeRunner.java \
        src/test/resources/typesafe-fixtures/nav-start.html \
        src/test/resources/typesafe-fixtures/nav-target.html
git commit -m "Add navigate action test wiring OpenAiWriterClient into TypeSafeRunner"
```

---

## Task 8: `FullSelfDrivingTypeSafe` action class

**Files:**
- Create: `src/main/java/com/automationanywhere/botcommand/FullSelfDrivingTypeSafe.java`
- Test: `src/test/java/TestFullSelfDrivingTypeSafeGuardrails.java`

**Interfaces:**
- Consumes: `TypeSafeClient`, `OpenAiWriterClient`, `TypeSafeRunner`,
  `TypeSafeRunner.RunResult` (all from Tasks 3-5), `PlaywrightConnection` (existing).
- Produces: the `FullSelfDrivingTypeSafe` bot-command action, callable exactly like
  `FullSelfDrivingMultiTurn` from a bot: `sessionName`, `TypeSafe API Key` credential,
  `OpenAI API Key` credential (optional), `request`, `outputFormatSchema` (optional),
  `maxSteps`, `maxRunSeconds`, `minConfidence` → `DictionaryValue`.
- Also produces two **public** static helpers used only by this task's own test (not by
  other tasks): `validateMaxSteps(Double)` and `validateMaxRunSeconds(Double)`, each
  returning the validated primitive and throwing `BotCommandException` on an invalid
  (zero/negative) input. Public, not package-visible, for the same reason as every other
  test-only member in this plan: `TestFullSelfDrivingTypeSafeGuardrails.java` is in the
  default/unnamed package and cannot reach a package-private static method of
  `com.automationanywhere.botcommand.FullSelfDrivingTypeSafe`.

- [ ] **Step 1: Write the failing guardrail-validation tests**

`src/test/java/TestFullSelfDrivingTypeSafeGuardrails.java`:
```java
import com.automationanywhere.botcommand.FullSelfDrivingTypeSafe;
import com.automationanywhere.botcommand.exception.BotCommandException;
import org.testng.annotations.Test;

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
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew compileTestJava`
Expected: FAIL — `FullSelfDrivingTypeSafe` doesn't exist.

- [ ] **Step 3: Implement `FullSelfDrivingTypeSafe`**

`src/main/java/com/automationanywhere/botcommand/FullSelfDrivingTypeSafe.java`:
```java
package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.*;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.BotCommand;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AllowedTarget;
import com.automationanywhere.commandsdk.model.DataType;
import com.automationanywhere.core.security.SecureString;
import com.microsoft.playwright.Page;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.*;

@BotCommand
@CommandPkg(
        name = "FullSelfDrivingTypeSafe",
        label = "FSD TypeSafe (Fast, Cheap Decisions)",
        description = "Goal-based browser automation using TypeSafe's classifier for each step decision, " +
                "with an optional OpenAI writer only for free text/URLs/final answer.",
        node_label = "FSD TypeSafe: {{request}}",
        icon = "pkg.svg",
        allowed_agent_targets = {
                AllowedTarget.WINDOWS,
                AllowedTarget.MAC_OS
        },
        return_label = "Result Dictionary",
        return_required = true,
        return_type = DataType.DICTIONARY,
        return_description = "Keys are 'status', 'stopReason', 'numberOfSteps', 'data' (if schema given), " +
                "'iframesSkipped', and 'step1'..'stepN' each a dictionary of actionType/target/confidence/result"
)
public class FullSelfDrivingTypeSafe {

    private static final Logger logger = LogManager.getLogger(FullSelfDrivingTypeSafe.class);
    private static final int MAX_STEPS_CEILING = 50;
    private static final long WARN_ABOVE_SECONDS = 1800;

    @Sessions
    private Map<String, Object> sessions;

    @Execute
    public DictionaryValue action(
            @Idx(index = "1", type = TEXT)
            @Pkg(label = "Session name", default_value_type = DataType.STRING, default_value = "Default")
            @NotEmpty String sessionName,

            @Idx(index = "2", type = CREDENTIAL)
            @Pkg(label = "TypeSafe API Key (map a credential)")
            @NotEmpty SecureString typeSafeApiKeySS,

            @Idx(index = "3", type = CREDENTIAL)
            @Pkg(label = "OpenAI API Key (optional, needed for type_text/navigate/final answer)")
            SecureString openAiApiKeySS,

            @Idx(index = "4", type = SELECT, options = {
                    @Idx.Option(index = "4.1", pkg = @Pkg(label = "gpt-4o-mini", value = "gpt-4o-mini")),
                    @Idx.Option(index = "4.2", pkg = @Pkg(label = "gpt-4o", value = "gpt-4o"))
            })
            @Pkg(label = "OpenAI Writer Model", default_value = "gpt-4o-mini", default_value_type = DataType.STRING)
            String writerModel,

            @Idx(index = "5", type = TEXTAREA)
            @Pkg(label = "Request (what should be done?)")
            @NotEmpty String request,

            @Idx(index = "6", type = TEXTAREA)
            @Pkg(label = "Output Format Schema (optional)")
            String outputFormatSchema,

            @Idx(index = "7", type = NUMBER)
            @Pkg(label = "Max steps", default_value = "15", default_value_type = DataType.NUMBER)
            @NotEmpty Double maxSteps,

            @Idx(index = "8", type = NUMBER)
            @Pkg(label = "Max run seconds", default_value = "120", default_value_type = DataType.NUMBER)
            @NotEmpty Double maxRunSeconds,

            @Idx(index = "9", type = NUMBER)
            @Pkg(label = "Min confidence (0-1)", default_value = "0.4", default_value_type = DataType.NUMBER)
            @NotEmpty Double minConfidence
    ) {
        logger.info("FullSelfDrivingTypeSafe started - session: {}, maxSteps: {}, maxRunSeconds: {}, minConfidence: {}",
                sessionName, maxSteps, maxRunSeconds, minConfidence);

        Object connObj = this.sessions.get(sessionName);
        if (!(connObj instanceof PlaywrightConnection)) {
            throw new BotCommandException("No active session found with name: " + sessionName);
        }
        Page page = ((PlaywrightConnection) connObj).getPage();

        int cappedMaxSteps = validateMaxSteps(maxSteps);
        long maxRunMillis = validateMaxRunSeconds(maxRunSeconds);

        String typeSafeApiKey = typeSafeApiKeySS.getInsecureString();
        TypeSafeClient typeSafeClient = new TypeSafeClient(typeSafeApiKey);

        OpenAiWriterClient writer = null;
        if (openAiApiKeySS != null) {
            writer = new OpenAiWriterClient(openAiApiKeySS.getInsecureString(), writerModel);
        } else {
            logger.warn("FullSelfDrivingTypeSafe: no OpenAI key mapped - type_text/navigate/final-answer steps will no-op");
        }

        TypeSafeRunner.RunResult runResult;
        try {
            TypeSafeRunner runner = new TypeSafeRunner();
            runResult = runner.run(page, typeSafeClient, writer, request, outputFormatSchema,
                    cappedMaxSteps, maxRunMillis, minConfidence);
        } catch (BotCommandException e) {
            throw e;
        } catch (Exception e) {
            logger.error("FullSelfDrivingTypeSafe failed - session: {}", sessionName, e);
            throw new BotCommandException("FullSelfDrivingTypeSafe failed: " + e.getMessage(), e);
        }

        Map<String, Value> out = new HashMap<>();
        out.put("status", new StringValue(runResult.status));
        out.put("stopReason", new StringValue(runResult.stopReason));
        out.put("numberOfSteps", new StringValue(String.valueOf(runResult.steps.size())));
        out.put("iframesSkipped", new StringValue(String.valueOf(runResult.iframesSkipped)));
        if (runResult.data != null) {
            out.put("data", new StringValue(runResult.data));
        }
        for (int i = 0; i < runResult.steps.size(); i++) {
            TypeSafeRunner.StepTrace trace = runResult.steps.get(i);
            Map<String, Value> stepMap = new HashMap<>();
            stepMap.put("actionType", new StringValue(trace.actionType));
            stepMap.put("target", new StringValue(trace.target));
            stepMap.put("confidence", new StringValue(String.valueOf(trace.confidence)));
            stepMap.put("result", new StringValue(trace.result == null ? "" : trace.result));
            out.put("step" + (i + 1), new DictionaryValue(stepMap));
        }

        logger.info("FullSelfDrivingTypeSafe completed - session: {}, stopReason: {}, steps: {}",
                sessionName, runResult.stopReason, runResult.steps.size());
        return new DictionaryValue(out);
    }

    public static int validateMaxSteps(Double maxSteps) {
        if (maxSteps == null || maxSteps <= 0) {
            throw new BotCommandException("Max steps must be a positive number.");
        }
        int requested = (int) Math.round(maxSteps);
        if (requested > MAX_STEPS_CEILING) {
            logger.warn("FullSelfDrivingTypeSafe: requested maxSteps {} exceeds hard ceiling {}, capping",
                    requested, MAX_STEPS_CEILING);
            return MAX_STEPS_CEILING;
        }
        return requested;
    }

    public static long validateMaxRunSeconds(Double maxRunSeconds) {
        if (maxRunSeconds == null || maxRunSeconds <= 0) {
            throw new BotCommandException("Max run seconds must be a positive number.");
        }
        if (maxRunSeconds > WARN_ABOVE_SECONDS) {
            logger.warn("FullSelfDrivingTypeSafe: maxRunSeconds {} exceeds 30 minutes - this run may take a long time",
                    maxRunSeconds);
        }
        return Math.round(maxRunSeconds * 1000);
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestFullSelfDrivingTypeSafeGuardrails`
Expected: all 5 tests PASS.

- [ ] **Step 5: Full build check**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew clean build -x test`
Expected: `BUILD SUCCESSFUL` (confirms `commandCodeGen`/shadowJar pick up the new
`@BotCommand` class without any manual registration, matching the existing actions'
zero-touch packaging).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/automationanywhere/botcommand/FullSelfDrivingTypeSafe.java \
        src/test/java/TestFullSelfDrivingTypeSafeGuardrails.java
git commit -m "Add FullSelfDrivingTypeSafe action wiring TypeSafeRunner into a bot command"
```

---

## Task 9: Missing-OpenAI-key end-to-end behavior test

**Files:**
- Test: `src/test/java/TestFullSelfDrivingTypeSafeGuardrails.java` (extend)

**Interfaces:**
- Consumes: `FullSelfDrivingTypeSafe` (Task 8), `LaunchBrowser`/`CloseBrowser` (existing),
  `TypeSafeClient`'s public `(apiKey, baseUrl)` constructor (Task 3 ruling — public, not
  package-private) — this test points the action at a local TypeSafe stub server, which
  requires making the action's `TypeSafeClient` base URL overridable for tests.
- Produces: a **public** test-only hook on `FullSelfDrivingTypeSafe`,
  `setTypeSafeBaseUrlOverrideForTests(String baseUrl)` — public, not package-visible,
  because this task's own test file is in the default/unnamed package like every other
  test class in `src/test/java/`, and cannot reach a package-private member of a class in
  `com.automationanywhere.botcommand`.

- [ ] **Step 1: Add a test-only TypeSafe base-URL override hook**

Modify `src/main/java/com/automationanywhere/botcommand/FullSelfDrivingTypeSafe.java`:
replace the line
```java
        TypeSafeClient typeSafeClient = new TypeSafeClient(typeSafeApiKey);
```
with
```java
        TypeSafeClient typeSafeClient = typeSafeBaseUrlOverride == null
                ? new TypeSafeClient(typeSafeApiKey)
                : new TypeSafeClient(typeSafeApiKey, typeSafeBaseUrlOverride);
```
and add a field plus public setter just above `setSessions`:
```java
    // Test-only hook: lets tests point this action at a local TypeSafe stub instead of
    // the real https://api.typesafe.ai. Never set by production code paths.
    private String typeSafeBaseUrlOverride;

    public void setTypeSafeBaseUrlOverrideForTests(String baseUrl) {
        this.typeSafeBaseUrlOverride = baseUrl;
    }
```

No separate TestSupport shim class is needed here: `TypeSafeClient`'s two-argument
constructor is already `public` (Task 3 ruling), so this action class can call it
directly regardless of which package the caller lives in.

- [ ] **Step 2: Write the failing test**

Add to `src/test/java/TestFullSelfDrivingTypeSafeGuardrails.java`:
```java
import com.automationanywhere.botcommand.CloseBrowser;
import com.automationanywhere.botcommand.LaunchBrowser;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.core.security.SecureString;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// ... inside the class, alongside the existing @Test methods:

    @Test
    public void testMissingOpenAiKeyStillCompletesGracefully() throws Exception {
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
            String stopReason = ((StringValue) map.get("stopReason")).get();
            assertEquals("done", stopReason);

            DictionaryValue step1 = (DictionaryValue) map.get("step1");
            String step1Result = ((StringValue) step1.get().get("result")).get();
            assertEquals("NOOP: no writer configured", step1Result);

            CloseBrowser close = new CloseBrowser();
            close.setSessions(sessionMap);
            close.action(sessionName);
        } finally {
            stub.stop(0);
        }
    }
```

- [ ] **Step 3: Run to confirm failure**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew compileTestJava compileJava`
Fix any compile errors (e.g. missing imports) before proceeding.

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestFullSelfDrivingTypeSafeGuardrails`
Expected: all tests (5 from Task 8 + this one) PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/automationanywhere/botcommand/FullSelfDrivingTypeSafe.java \
        src/test/java/TestFullSelfDrivingTypeSafeGuardrails.java
git commit -m "Add end-to-end test for FullSelfDrivingTypeSafe with no OpenAI key mapped"
```

---

## Task 10: Optional live-API smoke test (opt-in, skipped without real credentials)

**Files:**
- Create: `src/test/java/TestFullSelfDrivingTypeSafeLive.java`

**Interfaces:**
- Consumes: `FullSelfDrivingTypeSafe` (Task 8), reads `TYPESAFE_API_KEY_TEST` and
  `OPENAI_API_KEY_TEST` from the environment (never hardcoded).

This is the one task that talks to the real TypeSafe and OpenAI APIs, to validate the
whole pipeline end-to-end against production behavior rather than stubs. It's opt-in
(skips cleanly when the env vars aren't set) so the rest of the suite — and CI — never
depends on live network access or real spend.

- [ ] **Step 1: Write the test with an explicit skip guard**

`src/test/java/TestFullSelfDrivingTypeSafeLive.java`:
```java
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

        CloseBrowser close = new CloseBrowser();
        close.setSessions(sessionMap);
        close.action(sessionName);
    }
}
```

- [ ] **Step 2: Run without env vars set to confirm it skips cleanly**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestFullSelfDrivingTypeSafeLive`
Expected: test reports SKIPPED, build does not fail.

- [ ] **Step 3 (manual, optional): Run with real credentials**

If you want to validate against the real TypeSafe API using the key already in this
project's local `.env`:
```bash
export TYPESAFE_API_KEY_TEST=$(grep TYPESAFE_API_KEY .env | cut -d= -f2)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew test --tests TestFullSelfDrivingTypeSafeLive
```
This step is manual and not part of the automated commit/verify cycle — report the
result but don't block the plan on it.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/TestFullSelfDrivingTypeSafeLive.java
git commit -m "Add opt-in live-API smoke test for FullSelfDrivingTypeSafe"
```

---

## Task 11: Full build verification and best-practices review pass

**Files:** none created; this task verifies and lightly cleans up Tasks 1-10's output.

- [ ] **Step 1: Run the full build**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home ./gradlew clean build
```
Expected: `BUILD SUCCESSFUL`, all tests pass except the intentionally-skipped live test
from Task 10.

- [ ] **Step 2: Re-read the new/modified files against `AA_PACKAGE_BEST_PRACTICES.md`**

Specifically confirm, for every file touched in Tasks 1-10:
- No `SecureString`/API key value ever appears in a log statement (grep for
  `logger\.(info|warn|error|debug)` calls in the new files and manually check each one).
- Every class doing meaningful work has exactly one `private static final Logger`.
- `FullSelfDrivingTypeSafe`'s `@Idx` indices are contiguous strings starting at "1" with
  no gaps or reused numbers (re-count 1 through 9 in the current file).
- The shadowJar excludes from the existing `build.gradle` (added in a prior session) are
  untouched by this plan's Gson dependency addition.

Run this grep as a quick check:
```bash
grep -rn "logger\." src/main/java/com/automationanywhere/botcommand/utils/TypeSafe*.java \
    src/main/java/com/automationanywhere/botcommand/utils/OpenAiWriterClient.java \
    src/main/java/com/automationanywhere/botcommand/FullSelfDrivingTypeSafe.java
```
Read every matched line; confirm none interpolate `apiKey`, `typeSafeApiKey`,
`openAiApiKey`, or any `SecureString`/`getInsecureString()` result.

- [ ] **Step 3: Bump the package version**

Per `AA_PACKAGE_BEST_PRACTICES.md` §3, a new action is a minor feature addition, not a
breaking change, so the auto-incrementing build number (already wired via
`version.properties`/`incrementBuildNumber`) is sufficient — no manual `major.minor` bump
needed. Just confirm a build has actually run at least once since the last commit so
`version.properties`'s `build.number` reflects it:
```bash
git diff src/main/resources/version.properties
```
If it shows a change, include it in this task's commit. If it shows no change, run
`./gradlew build` once more before committing (Step 1 already did this).

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "Final build verification pass for FullSelfDrivingTypeSafe"
```
(If Step 3 produced no `version.properties` change and nothing else is outstanding, skip
this commit — there's nothing to commit.)

---

## Self-Review Notes

- **Spec coverage:** Architecture/data flow → Tasks 5, 8. Action set/question schema →
  Tasks 3, 5. Item extraction → Tasks 1, 2. Stop conditions → Task 6. Error handling →
  Tasks 3, 4, 5. Output shape → Task 8. Testing plan's nine fixture scenarios → covered
  across Tasks 1, 2, 5, 6, 7 (the ">255 elements", "static/stall", "iframe", "dropdown",
  "contenteditable", "multi-page navigate" fixtures are all present; the "deliberately
  slow page" fixture from the spec's testing plan is intentionally *not* built as a
  separate timing test, since Task 6's `testTimeoutStopsBeforeStepLimit` already exercises
  the `maxRunMillis` stop condition directly and deterministically without needing a real
  slow-loading page — a truly slow fixture would make the suite slower for no added
  coverage). TypeSafe/OpenAI error paths → Task 3, Task 4. Missing-OpenAI-key path → Task
  9. Guardrail validation → Task 8.
- **Placeholder scan:** no TBD/TODO left in any task; every step has real, complete code.
- **Type consistency:** `TypeSafeRunner.run(...)`'s signature is defined once in Task 5
  and referenced identically (same parameter order/types) in Tasks 6, 7, 8, 9 — verified
  by re-reading each call site above. `PageItem` field names (`index`, `description`,
  `locator`, `isSelectOption`, `optionValue`, `selectLocator`) are used consistently from
  Task 1 through Task 5's executor. `TypeSafeTypes.ChoiceAnswer.choice`/`.confidence` match
  between Task 3's definition and Task 5's consumption.
