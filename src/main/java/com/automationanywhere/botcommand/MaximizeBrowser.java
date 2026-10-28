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
import com.microsoft.playwright.Page;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.TEXT;
import static com.automationanywhere.commandsdk.model.DataType.STRING;

@BotCommand
@CommandPkg(
        name = "MaximizeBrowser",
        label = "Maximize Browser",
        description = "Maximizes the current browser window (Chromium only, visible mode)",
        node_label = "Maximize Browser",
        icon = "pkg.svg",
        allowed_agent_targets = {
//                AllowedTarget.HEADLESS,
                AllowedTarget.WINDOWS,
                AllowedTarget.MAC_OS
//                AllowedTarget.ONDEMAND_CLOUD
        },
        return_type = DataType.DICTIONARY,
        return_sub_type = STRING,
        return_required = false,
        return_label = "Status"
)
public class MaximizeBrowser {

    private static final Logger logger = LogManager.getLogger(MaximizeBrowser.class);

    @Sessions
    private Map<String, Object> sessions;

    @Execute
    public DictionaryValue action(
            @Idx(index = "1", type = TEXT)
            @Pkg(label = "Session name", default_value_type = STRING, default_value = "Default")
            @NotEmpty String sessionName
    ) {
        logger.info("MaximizeBrowser started - session: {}", sessionName);

        Object connObj = this.sessions.get(sessionName);
        if (!(connObj instanceof PlaywrightConnection)) {
            throw new BotCommandException("No active session found with name: " + sessionName);
        }

        PlaywrightConnection connection = (PlaywrightConnection) connObj;
        Page page = connection.getPage();

        try {
            // If headless, skip gracefully
            if (connection.getBrowser().isConnected() && connection.getBrowser().browserType().name().equalsIgnoreCase("chromium")) {
                if (page.context().browser().isConnected() && !page.context().browser().isConnected()) {
                    logger.info("MaximizeBrowser skipped - headless mode - session: {}", sessionName);
                    return DictionaryHelper.success("skipped_headless");
                }

                // Chromium only: run JS to maximize
                page.evaluate("() => { window.moveTo(0,0); window.resizeTo(screen.width, screen.height); }");
                logger.info("MaximizeBrowser completed - session: {}", sessionName);
                return DictionaryHelper.success("maximized");
            } else {
                logger.info("MaximizeBrowser not supported for this browser type - session: {}", sessionName);
                return DictionaryHelper.success("unsupported_browser_type");
            }
        } catch (Exception e) {
            logger.error("MaximizeBrowser failed - session: {}", sessionName, e);
            throw new BotCommandException("Failed to maximize browser: " + e.getMessage(), e);
        }
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}