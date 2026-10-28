package com.automationanywhere.botcommand.utils;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enhanced ElementLocator with smart selector strategies and robust retry logic.
 * Inspired by Skyvern's approach to avoiding brittle selectors.
 */
public class ElementLocator {

    private static final Logger logger = LogManager.getLogger(ElementLocator.class);

    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 1000;

    /**
     * Handles element interactions with intelligent fallbacks and retry logic.
     *
     * @param page         Playwright Page
     * @param selectorType Type of selector: "xpath", "css", "text", "jspath"
     * @param selector     The actual selector string
     * @param action       The action to perform: "click", "gettext", "settext"
     * @param value        Optional value for actions like "settext"
     * @return             Action result (text, null, etc.)
     */
    public static Object handle(Page page, String selectorType, String selector, String action, String value) {
        Exception lastException = null;

        // Try multiple times with increasing wait times
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                switch (selectorType.toLowerCase()) {
                    case "css":
                        return performAction(page, "css=" + selector, action, value);
                    case "text":
                        return performActionWithTextFallback(page, selector, action, value);
                    case "jspath":
                        return executeJsPath(page, selector, action, value);
                    case "xpath":
                    default:
                        return performAction(page, "xpath=" + selector, action, value);
                }
            } catch (Exception e) {
                lastException = e;
                logger.warn("Attempt {} of {} failed for {} on selector ({}: {})", attempt + 1, MAX_RETRIES, action, selectorType, selector);
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ignored) {}
                }
            }
        }

        throw new RuntimeException(
                "Failed to execute " + action + " on element using " + selectorType +
                        ": " + selector + " after " + MAX_RETRIES + " attempts",
                lastException
        );
    }

    /**
     * Performs action with multiple fallback strategies for text-based selectors.
     * This is more robust than exact text matching.
     */
    private static Object performActionWithTextFallback(Page page, String text, String action, String value) {
        // Try different text matching strategies
        List<String> strategies = Arrays.asList(
                "text=" + text,                          // Exact match
                "text=/" + text + "/i",                  // Case insensitive
                String.format("text=/%s/", text.toLowerCase()),  // Lowercase
                String.format("button:has-text(\"%s\")", text),  // Button with text
                String.format("a:has-text(\"%s\")", text)        // Link with text
        );

        Exception lastException = null;
        for (String strategy : strategies) {
            try {
                return performAction(page, strategy, action, value);
            } catch (Exception e) {
                lastException = e;
            }
        }

        throw new RuntimeException(
                "Failed to find element with text: " + text +
                        " using any strategy", lastException
        );
    }

    /**
     * Performs the actual action with intelligent retry and fallback strategies.
     */
    private static Object performAction(Page page, String pwSelector, String action, String value) {
        switch (action.toLowerCase()) {
            case "click":
                return performClick(page, pwSelector);
            case "gettext":
                return performGetText(page, pwSelector);
            case "settext":
                return performSetText(page, pwSelector, value);
            default:
                throw new IllegalArgumentException("Unsupported action: " + action);
        }
    }

    /**
     * Smart click with multiple fallback strategies.
     */
    private static Object performClick(Page page, String pwSelector) {
        Locator loc = page.locator(pwSelector).first();

        // Strategy 1: Wait for element to be ready
        try {
            loc.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(10000));
        } catch (Exception e) {
            // Continue to next strategy if wait fails
        }

        // Strategy 2: Standard click with scroll
        try {
            loc.scrollIntoViewIfNeeded();
            Thread.sleep(300); // Brief delay for scroll to settle
            loc.click(new Locator.ClickOptions().setTimeout(5000));
            return null;
        } catch (Exception e) {
            // Continue to next strategy
        }

        // Strategy 3: Force click (handles overlays)
        try {
            loc.click(new Locator.ClickOptions()
                    .setForce(true)
                    .setTimeout(5000));
            return null;
        } catch (Exception e) {
            // Continue to next strategy
        }

        // Strategy 4: JavaScript click (most reliable fallback)
        try {
            if (pwSelector.startsWith("css=")) {
                String css = pwSelector.substring("css=".length());
                page.evaluate(
                        "sel => {" +
                                "  const el = document.querySelector(sel);" +
                                "  if (el) {" +
                                "    el.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                                "    setTimeout(() => { el.focus(); el.click(); }, 300);" +
                                "  } else {" +
                                "    throw new Error('Element not found: ' + sel);" +
                                "  }" +
                                "}",
                        css
                );
                Thread.sleep(500); // Wait for JS click to process
                return null;
            } else if (pwSelector.startsWith("xpath=")) {
                String xp = pwSelector.substring("xpath=".length());
                page.evaluate(
                        "xpath => {" +
                                "  const r = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);" +
                                "  const el = r.singleNodeValue;" +
                                "  if (el) {" +
                                "    el.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                                "    setTimeout(() => { el.focus(); el.click(); }, 300);" +
                                "  } else {" +
                                "    throw new Error('Element not found: ' + xpath);" +
                                "  }" +
                                "}",
                        xp
                );
                Thread.sleep(500);
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException("All click strategies failed for: " + pwSelector, e);
        }

        throw new RuntimeException("Unable to click element: " + pwSelector);
    }

    /**
     * Gets text with fallback to different text extraction methods.
     */
    private static Object performGetText(Page page, String pwSelector) {
        try {
            // Try textContent first (fastest)
            String text = page.textContent(pwSelector);
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
        } catch (Exception ignored) {}

        try {
            // Fallback to innerText (respects visibility)
            String text = page.innerText(pwSelector);
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
        } catch (Exception ignored) {}

        try {
            // Last resort: evaluate JavaScript
            Object result = page.evalOnSelector(pwSelector, "el => el.textContent || el.innerText || el.value || ''");
            return result != null ? result.toString().trim() : "";
        } catch (Exception e) {
            throw new RuntimeException("Failed to get text from: " + pwSelector, e);
        }
    }

    /**
     * Sets text with proper event triggering for modern web frameworks.
     */
    private static Object performSetText(Page page, String pwSelector, String value) {
        if (value == null) value = "";

        Locator loc = page.locator(pwSelector).first();

        // Strategy 1: Standard Playwright fill (works for most inputs)
        try {
            loc.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5000));
            loc.fill(value);
            return null;
        } catch (Exception e) {
            // Continue to fallback
        }

        // Strategy 2: JavaScript with proper event triggering.
        // The selector and the user-supplied value are passed as a parameterized
        // argument object (Playwright's two-arg page.evaluate(fn, arg) form) rather
        // than interpolated into the JS source, so no escaping of user input is needed.
        try {
            String jsCode =
                    "(args) => {" +
                            "  const el = document.querySelector(args.sel) || document.evaluate(args.sel.replace('xpath=', ''), document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;" +
                            "  if (!el) throw new Error('Element not found');" +
                            "  if (el.isContentEditable) {" +
                            "    el.textContent = args.value;" +
                            "    el.dispatchEvent(new Event('input', {bubbles: true}));" +
                            "  } else {" +
                            "    el.value = args.value;" +
                            "    el.dispatchEvent(new Event('input', {bubbles: true}));" +
                            "    el.dispatchEvent(new Event('change', {bubbles: true}));" +
                            "  }" +
                            "}";

            Map<String, Object> args = new HashMap<>();
            args.put("sel", pwSelector.startsWith("xpath=") ? pwSelector : "css=" + pwSelector);
            args.put("value", value);

            page.evaluate(jsCode, args);
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to set text in: " + pwSelector, e);
        }
    }

    /**
     * Executes JavaScript path selectors with error handling.
     */
    private static Object executeJsPath(Page page, String jsPath, String action, String value) {
        try {
            switch (action.toLowerCase()) {
                case "click":
                    page.evaluate(jsPath + ".scrollIntoView({behavior: 'smooth', block: 'center'});");
                    Thread.sleep(300);
                    page.evaluate(jsPath + ".click();");
                    return null;

                case "gettext":
                    return page.evaluate("() => (" + jsPath + ").textContent || (" + jsPath + ").innerText || ''");

                case "settext":
                    // jsPath is itself a user-authored JS expression (the point of "JS Path"
                    // selectors), but the value to set is passed as a parameterized argument
                    // rather than interpolated into the JS source.
                    String settextJs = "(val) => { const el = " + jsPath + "; el.value = val; el.dispatchEvent(new Event('input', {bubbles: true})); }";
                    page.evaluate(settextJs, value != null ? value : "");
                    return null;

                default:
                    throw new IllegalArgumentException("Unsupported action for JS Path: " + action);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute JS Path: " + jsPath, e);
        }
    }
}