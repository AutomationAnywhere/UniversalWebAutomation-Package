package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.DictionaryHelper;
import com.automationanywhere.botcommand.utils.ElementLocator;
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

import static com.automationanywhere.commandsdk.model.AttributeType.SELECT;
import static com.automationanywhere.commandsdk.model.AttributeType.TEXT;
import static com.automationanywhere.commandsdk.model.DataType.STRING;

@BotCommand
@CommandPkg(
        name = "GetText",
        label = "Get Text",
        description = "Extracts visible text from an element by XPath, CSS, Text, or JS Path",
        node_label = "Get text from {{selectorValue}}",
        return_label = "Text Value",
        icon = "pkg.svg",
        allowed_agent_targets = {
//                AllowedTarget.HEADLESS,
                AllowedTarget.WINDOWS,
                AllowedTarget.MAC_OS
//                AllowedTarget.ONDEMAND_CLOUD
        },
        return_type = DataType.DICTIONARY,
        return_sub_type = STRING,
        return_required = true
)
public class GetText {

    private static final Logger logger = LogManager.getLogger(GetText.class);

    @Sessions
    private Map<String, Object> sessions;

    @Execute
    public DictionaryValue action(
            @Idx(index = "1", type = TEXT)
            @Pkg(label = "Session name", default_value_type = STRING, default_value = "Default")
            @NotEmpty String sessionName,

            @Idx(index = "2", type = SELECT, options = {
                    @Idx.Option(index = "2.1", pkg = @Pkg(label = "XPath (default)", value = "xpath")),
                    @Idx.Option(index = "2.2", pkg = @Pkg(label = "CSS", value = "css")),
                    @Idx.Option(index = "2.3", pkg = @Pkg(label = "Text", value = "text")),
//                    @Idx.Option(index = "2.4", pkg = @Pkg(label = "JS Path", value = "jspath"))
            })
            @Pkg(label = "Selector type", default_value = "xpath", default_value_type = STRING)
            String selectorType,

            @Idx(index = "3", type = TEXT)
            @Pkg(label = "Selector value") @NotEmpty String selectorValue
    ) {
        logger.info("GetText started - session: {}, selectorType: {}, selector: {}", sessionName, selectorType, selectorValue);

        Object connObj = this.sessions.get(sessionName);
        if (!(connObj instanceof PlaywrightConnection)) {
            throw new BotCommandException("No active session with name: " + sessionName);
        }

        PlaywrightConnection connection = (PlaywrightConnection) connObj;
        Page page = connection.getPage();

        try {
            String text = (String) ElementLocator.handle(page, selectorType, selectorValue, "gettext", null);
            if (text == null) {
                text = "";
            }
            logger.info("GetText completed - selector: {}", selectorValue);
            return DictionaryHelper.success(text.trim());
        } catch (Exception e) {
            logger.error("GetText failed for selector ({}: {})", selectorType, selectorValue, e);
            throw new BotCommandException("Failed to get text using " + selectorType + ": " + selectorValue, e);
        }
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}