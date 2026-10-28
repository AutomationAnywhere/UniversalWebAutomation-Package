package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.DictionaryHelper;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.BotCommand;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AllowedTarget;
import com.automationanywhere.commandsdk.model.DataType;
import com.microsoft.playwright.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.*;
import static com.automationanywhere.commandsdk.model.DataType.STRING;

@BotCommand
@CommandPkg(
        name = "LaunchBrowser",
        label = "Launch Browser",
        description = "Launches browser and creates a session",
        node_label = "Open {{browserType}} at {{pageURL}}",
        icon = "pkg.svg",
        allowed_agent_targets = {
//                AllowedTarget.HEADLESS,
                AllowedTarget.WINDOWS,
                AllowedTarget.MAC_OS
//                AllowedTarget.ONDEMAND_CLOUD
        },
        return_type = DataType.DICTIONARY,
        return_sub_type = STRING,
        return_required = false
)
public class LaunchBrowser {

    private static final Logger logger = LogManager.getLogger(LaunchBrowser.class);

    @Sessions
    private Map<String, Object> sessions;   // injected by Control Room runtime

    @Execute
    public DictionaryValue action(
            @Idx(index = "1", type = TEXT)
            @Pkg(label = "Session name", default_value_type = STRING, default_value = "Default")
            @NotEmpty String sessionName,

            @Idx(index = "2", type = TEXT)
            @Pkg(label = "URL") @NotEmpty String pageURL,

            @Idx(index = "3", type = SELECT, options = {
                    @Idx.Option(index = "3.1", pkg = @Pkg(label = "False", value = "false")),
                    @Idx.Option(index = "3.2", pkg = @Pkg(label = "True", value = "true")),
            })
            @Pkg(label = "Headless?", default_value = "false", default_value_type = STRING)
            String visibleMode,

            @Idx(index = "4", type = SELECT, options = {
                    @Idx.Option(index = "4.1", pkg = @Pkg(label = "Chromium", value = "chromium")),
                    @Idx.Option(index = "4.2", pkg = @Pkg(label = "Firefox", value = "firefox")),
                    @Idx.Option(index = "4.3", pkg = @Pkg(label = "WebKit", value = "webkit"))
            })
            @Pkg(label = "Browser type", default_value = "chromium", default_value_type = STRING)
            String browserType
    ) {
        logger.info("LaunchBrowser started - session: {}, browserType: {}, url: {}, headless: {}", sessionName, browserType, pageURL, visibleMode);

        Playwright playwright = null;
        Browser browser = null;
        try {
            playwright = Playwright.create();

            BrowserType type;
            switch (browserType.toLowerCase()) {
                case "firefox": type = playwright.firefox(); break;
                case "webkit":  type = playwright.webkit();  break;
                default:        type = playwright.chromium();
            }
            boolean headless = visibleMode.equalsIgnoreCase("true");

            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setArgs(java.util.Collections.singletonList("--start-maximized"));

            browser = type.launch(options);

            // Disable viewport so OS window size is respected
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(null));
            Page page = context.newPage();
            page.navigate(pageURL);

            // Wrap everything in a connection object (good for cleanup later)
            PlaywrightConnection connection = new PlaywrightConnection(playwright, browser, page);

            // Store in session
            this.sessions.put(sessionName, connection);

            logger.info("LaunchBrowser completed - session: {}, title: {}", sessionName, page.title());
            return DictionaryHelper.success(page.title());
        } catch (Exception e) {
            // Resource cleanup on partial failure: don't leak a launched browser/driver
            // process if a later step (context/page/navigate) threw.
            if (browser != null) {
                try { browser.close(); } catch (Exception closeEx) { logger.warn("Failed to close browser during LaunchBrowser cleanup", closeEx); }
            }
            if (playwright != null) {
                try { playwright.close(); } catch (Exception closeEx) { logger.warn("Failed to close Playwright driver during LaunchBrowser cleanup", closeEx); }
            }
            logger.error("LaunchBrowser failed - session: {}, url: {}", sessionName, pageURL, e);
            throw new BotCommandException("Failed to launch browser: " + e.getMessage(), e);
        }
    }

    // Needed so AA can inject the session map
    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}