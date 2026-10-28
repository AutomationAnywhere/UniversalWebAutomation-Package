package com.automationanywhere.botcommand.utils;
import com.microsoft.playwright.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PlaywrightConnection {

    private static final Logger logger = LogManager.getLogger(PlaywrightConnection.class);

    private final Playwright playwright;
    private final Browser browser;
    private final Page page;

    public PlaywrightConnection(Playwright playwright, Browser browser, Page page) {
        this.playwright = playwright;
        this.browser = browser;
        this.page = page;
    }

    public Page getPage() {
        return page;
    }

    public Browser getBrowser() {
        return browser;
    }

    public Playwright getPlaywright() {
        return playwright;
    }

    /**
     * Best-effort, idempotent close: each sub-resource is closed independently so a
     * failure closing the browser doesn't prevent the Playwright driver process from
     * also being released (see AA_PACKAGE_BEST_PRACTICES.md §5.3).
     */
    public void close() {
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (Exception e) {
            logger.warn("Failed to close browser cleanly, continuing cleanup", e);
        }

        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception e) {
            logger.warn("Failed to close Playwright driver cleanly", e);
        }
    }
}