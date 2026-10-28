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
        name = "ExecuteJavaScript",
        label = "Execute JavaScript",
        description = "Executes raw JavaScript inside the current page context",
        node_label = "Execute JS",
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
        return_label = "Result"
)
public class ExecuteJavaScript {

    private static final Logger logger = LogManager.getLogger(ExecuteJavaScript.class);

    @Sessions
    private Map<String, Object> sessions;

    @Execute
    public DictionaryValue action(
            @Idx(index = "1", type = TEXT)
            @Pkg(label = "Session name", default_value_type = STRING, default_value = "Default")
            @NotEmpty String sessionName,

            @Idx(index = "2", type = TEXT)
            @Pkg(label = "JavaScript code to execute") @NotEmpty String script
    ) {
        logger.info("ExecuteJavaScript started - session: {}", sessionName);

        Object connObj = this.sessions.get(sessionName);
        if (!(connObj instanceof PlaywrightConnection)) {
            throw new BotCommandException("No active session found with name: " + sessionName);
        }

        PlaywrightConnection connection = (PlaywrightConnection) connObj;
        Page page = connection.getPage();

        try {
            // Wrap user code in a function body to tolerate multi-line, quotes, etc.
            // Note: this action's entire purpose is to run bot-author-supplied JavaScript,
            // so intentionally executing that script verbatim is not an injection risk here
            // (unlike selector/value handling elsewhere in the package).
            String wrapped = "(() => { " + script + " })()";

            Object result = page.evaluate(wrapped);

            logger.info("ExecuteJavaScript completed - session: {}", sessionName);
            return DictionaryHelper.success(result != null ? result.toString() : "");
        } catch (Exception e) {
            logger.error("ExecuteJavaScript failed - session: {}", sessionName, e);
            throw new BotCommandException("Failed to execute JavaScript: " + e.getMessage(), e);
        }
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}