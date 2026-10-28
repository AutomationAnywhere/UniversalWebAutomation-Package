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

import java.util.Locale;
import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.SELECT;
import static com.automationanywhere.commandsdk.model.AttributeType.TEXT;
import static com.automationanywhere.commandsdk.model.DataType.STRING;

@BotCommand
@CommandPkg(
        name = "SetRichText",
        label = "Set Rich Text",
        description = "Types text into a rich text editor (Quill, TinyMCE, CKEditor) using XPath, CSS, Text, or JS Path.",
        node_label = "Set rich text in {{selectorValue}}",
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
public class SetRichText {

    private static final Logger logger = LogManager.getLogger(SetRichText.class);

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
            @Pkg(label = "Selector type", default_value = "css", default_value_type = STRING)
            String selectorType,

            @Idx(index = "3", type = TEXT)
            @Pkg(label = "Selector value") @NotEmpty String selectorValue,

            @Idx(index = "4", type = TEXT)
            @Pkg(label = "Text to type") @NotEmpty String textToType
    ) {
        Object connObj = this.sessions.get(sessionName);
        if (!(connObj instanceof PlaywrightConnection)) {
            throw new BotCommandException("No active session found with name: " + sessionName);
        }

        logger.info("SetRichText started - session: {}, selectorType: {}, selector: {}", sessionName, selectorType, selectorValue);

        PlaywrightConnection connection = (PlaywrightConnection) connObj;
        Page page = connection.getPage();

        String selector = selectorType + "=" + selectorValue;

        try {
            // 1) Happy path: click + keystrokes
            page.click(selector);

            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String modifier = os.contains("mac") ? "Meta" : "Control";

            // clear existing text
            page.keyboard().press(modifier + "+A");
            page.keyboard().press("Backspace");

            // type text
            page.keyboard().type(textToType);

            logger.info("SetRichText completed via keystrokes - selector: {}", selector);
            return DictionaryHelper.success("keystrokes");

        } catch (Exception primaryEx) {
            logger.warn("SetRichText keystroke path failed for {}, falling back to JS injection", selector, primaryEx);
            try {
                // 2) Fallback: JS injection into Quill/TinyMCE/CKEditor contenteditable.
                // The selector and text are passed as a parameterized argument object
                // (page.evaluate(fn, arg)) rather than interpolated into the JS source.
                page.evaluate("args => { " +
                                "const el = document.querySelector(args.selector);" +
                                "if (el) el.innerHTML = '<p>' + args.text + '</p>'; " +
                                "}",
                        Map.of("selector", selectorValue, "text", textToType));

                logger.info("SetRichText completed via JS injection - selector: {}", selector);
                return DictionaryHelper.success("javascript_injection");

            } catch (Exception fallbackEx) {
                logger.error("SetRichText failed (both keystroke and JS injection paths) - selector: {}", selector, fallbackEx);
                // 3) If both fail → throw bot error
                throw new BotCommandException("Failed to set rich text: " + fallbackEx.getMessage(), fallbackEx);
            }
        }
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}