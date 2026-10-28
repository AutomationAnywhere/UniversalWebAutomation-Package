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
import com.microsoft.playwright.options.SelectOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.SELECT;
import static com.automationanywhere.commandsdk.model.AttributeType.TEXT;
import static com.automationanywhere.commandsdk.model.DataType.STRING;

@BotCommand
@CommandPkg(
        name = "SelectDropdown",
        label = "Select Dropdown",
        description = "Selects an option in a dropdown by value, visible text, or index",
        node_label = "Select option from {{selectorValue}}",
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
public class SelectDropdown {

    private static final Logger logger = LogManager.getLogger(SelectDropdown.class);

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
            @Pkg(label = "Selector value") @NotEmpty String selectorValue,

            @Idx(index = "4", type = SELECT, options = {
                    @Idx.Option(index = "4.1", pkg = @Pkg(label = "By Value", value = "value")),
                    @Idx.Option(index = "4.2", pkg = @Pkg(label = "By Text", value = "text")),
                    @Idx.Option(index = "4.3", pkg = @Pkg(label = "By Index", value = "index"))
            })
            @Pkg(label = "Selection Mode", default_value = "value", default_value_type = STRING)
            String mode,

            @Idx(index = "5", type = TEXT)
            @Pkg(label = "Option to select") @NotEmpty String option
    ) {
        logger.info("SelectDropdown started - session: {}, selectorType: {}, selector: {}, mode: {}", sessionName, selectorType, selectorValue, mode);

        Object connObj = this.sessions.get(sessionName);
        if (!(connObj instanceof PlaywrightConnection)) {
            throw new BotCommandException("No active session found with name: " + sessionName);
        }

        PlaywrightConnection connection = (PlaywrightConnection) connObj;
        Page page = connection.getPage();

        try {
            String selector = selectorType + "=" + selectorValue;
            switch (mode.toLowerCase()) {
                case "text":
                    page.selectOption(selector, new SelectOption().setLabel(option));
                    break;
                case "index":
                    page.selectOption(selector, new SelectOption().setIndex(Integer.parseInt(option)));
                    break;
                default: // "value"
                    page.selectOption(selector, option);
            }
            logger.info("SelectDropdown completed - selector: {}", selectorValue);
            return DictionaryHelper.success(option);
        } catch (Exception e) {
            logger.error("SelectDropdown failed - selector: {}, mode: {}", selectorValue, mode, e);
            throw new BotCommandException("Failed to select dropdown option: " + e.getMessage(), e);
        }
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}