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

import java.util.Locale;
import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.SELECT;
import static com.automationanywhere.commandsdk.model.AttributeType.TEXT;
import static com.automationanywhere.commandsdk.model.DataType.STRING;

@BotCommand
@CommandPkg(
        name = "SetText",
        label = "Set Text",
        description = "Types or fills text into an input field using XPath, CSS, Text, or JS Path",
        node_label = "Set text in {{selectorValue}}",
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
public class SetText {

    private static final Logger logger = LogManager.getLogger(SetText.class);

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

            @Idx(index = "4", type = TEXT)
            @Pkg(label = "Text to type") @NotEmpty String textToType
    ) {
        logger.info("SetText started - session: {}, selectorType: {}, selector: {}", sessionName, selectorType, selectorValue);

        Object connObj = this.sessions.get(sessionName);
        if (!(connObj instanceof PlaywrightConnection)) {
            throw new BotCommandException("No active session found with name: " + sessionName);
        }

        PlaywrightConnection connection = (PlaywrightConnection) connObj;
        Page page = connection.getPage();

        String selector = selectorType + "=" + selectorValue;

        try {
            // Safely resolve tag name and contenteditable flag
            String tagName = "";
            Object rawTag = page.evalOnSelector(selector, "el => el ? el.tagName : ''");
            if (rawTag != null) tagName = rawTag.toString().toLowerCase();

            boolean isEditable = Boolean.parseBoolean(String.valueOf(
                    page.evalOnSelector(selector, "el => el ? !!el.isContentEditable : false")
            ));

            if ("input".equals(tagName) || "textarea".equals(tagName)) {
                page.fill(selector, textToType);

            } else if (isEditable) {
                // For contenteditable elements
                page.click(selector); // focus

                try {
                    page.fill(selector, textToType);
                } catch (Exception ignore) {
                    // OS-specific modifier (Meta = Command on macOS, Control elsewhere)
                    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
                    String modifier = os.contains("mac") ? "Meta" : "Control";

                    // fallback to keystrokes
                    page.keyboard().press(modifier + "+A"); // select all
                    page.keyboard().press("Backspace");     // clear
                    page.keyboard().type(textToType);       // type
                }

            } else {
                throw new BotCommandException("Selector does not match an editable element: " + selector);
            }

            logger.info("SetText completed - selector: {}", selector);
            return DictionaryHelper.success("");

        } catch (Exception e) {
            logger.error("SetText failed - selector: {}", selector, e);
            throw new BotCommandException("Failed to set text: " + e.getMessage(), e);
        }
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}