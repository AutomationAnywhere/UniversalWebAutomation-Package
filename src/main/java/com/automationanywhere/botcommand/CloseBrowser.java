package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.utils.DictionaryHelper;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.BotCommand;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AllowedTarget;
import com.automationanywhere.commandsdk.model.DataType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.TEXT;
import static com.automationanywhere.commandsdk.model.DataType.STRING;

@BotCommand
@CommandPkg(
        name = "CloseBrowser",
        label = "Close Browser",
        description = "Closes the browser session",
        node_label = "Close browser for {{sessionName}}",
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
public class CloseBrowser {

    private static final Logger logger = LogManager.getLogger(CloseBrowser.class);

    @Sessions
    private Map<String, Object> sessions;

    @Execute
    public DictionaryValue action(
            @Idx(index = "1", type = TEXT) @Pkg(label = "Session name", default_value_type = STRING, default_value = "Default")
            @NotEmpty String sessionName
    ) {
        logger.info("CloseBrowser started - session: {}", sessionName);

        Object connObj = this.sessions.remove(sessionName);
        if (!(connObj instanceof PlaywrightConnection)) {
            logger.info("CloseBrowser no-op - no active session with name: {}", sessionName);
            return DictionaryHelper.success("");
        }

        PlaywrightConnection connection = (PlaywrightConnection) connObj;
        connection.close();
        logger.info("CloseBrowser completed - session: {}", sessionName);
        return DictionaryHelper.success(sessionName);
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}