package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.DictionaryHelper;
import com.automationanywhere.botcommand.utils.PlaywrightConnection;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.BotCommand;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AllowedTarget;
import com.automationanywhere.commandsdk.model.AttributeType;
import com.automationanywhere.commandsdk.model.DataType;
import com.microsoft.playwright.Page;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.*;
import static com.automationanywhere.commandsdk.model.DataType.STRING;

/**
 * Enhanced Wait command with smart waiting strategies:
 * - Network idle detection
 * - DOM stability checking
 * - Element appearance/disappearance
 * - Custom JavaScript condition
 */
@BotCommand
@CommandPkg(
        name = "Wait",
        label = "Wait",
        description = "Smart wait with network idle, DOM stability, and custom condition support",
        node_label = "Wait for {{waitType}}",
        icon = "pkg.svg",
        allowed_agent_targets = {
                AllowedTarget.WINDOWS,
                AllowedTarget.MAC_OS
        },
        return_type = DataType.DICTIONARY,
        return_sub_type = STRING,
        return_required = false,
        return_label = "Status"
)
public class Wait {

    private static final Logger logger = LogManager.getLogger(Wait.class);

    @Sessions
    private Map<String, Object> sessions;

    @Execute
    public DictionaryValue action(
            @Idx(index = "1", type = TEXT)
            @Pkg(label = "Session name", default_value_type = STRING, default_value = "Default")
            @NotEmpty String sessionName,

            @Idx(index = "2", type = SELECT, options = {
                    @Idx.Option(index = "2.1", pkg = @Pkg(label = "Element Visible", value = "element_visible")),
                    @Idx.Option(index = "2.2", pkg = @Pkg(label = "Element Hidden", value = "element_hidden")),
                    @Idx.Option(index = "2.3", pkg = @Pkg(label = "Fixed Time", value = "time")),
                    @Idx.Option(index = "2.4", pkg = @Pkg(label = "Network Idle", value = "network_idle")),
                    @Idx.Option(index = "2.5", pkg = @Pkg(label = "DOM Stable", value = "dom_stable")),
                    @Idx.Option(index = "2.6", pkg = @Pkg(label = "Custom Condition (JS)", value = "custom"))
            })
            @Pkg(label = "Wait type", default_value = "time", default_value_type = STRING)
            String waitType,

            @Idx(index = "3", type = AttributeType.NUMBER)
            @Pkg(label = "Timeout in seconds", default_value_type = STRING, default_value = "30")
            Double seconds,

            @Idx(index = "4", type = SELECT, options = {
                    @Idx.Option(index = "4.1", pkg = @Pkg(label = "XPath", value = "xpath")),
                    @Idx.Option(index = "4.2", pkg = @Pkg(label = "CSS", value = "css")),
                    @Idx.Option(index = "4.3", pkg = @Pkg(label = "Text", value = "text"))
            })
            @Pkg(label = "Selector type (for element wait)", default_value = "xpath", default_value_type = STRING)
            String selectorType,

            @Idx(index = "5", type = TEXT)
            @Pkg(label = "Selector/Condition", description = "Element selector OR JavaScript condition that returns true/false")
            String condition
    ) {
        logger.info("Wait started - session: {}, waitType: {}, timeoutSeconds: {}", sessionName, waitType, seconds);
        try {
            if ("time".equalsIgnoreCase(waitType)) {
                DictionaryValue result = waitForTime(seconds);
                logger.info("Wait completed - waitType: {}", waitType);
                return result;
            }

            Object connObj = this.sessions.get(sessionName);
            if (!(connObj instanceof PlaywrightConnection)) {
                throw new BotCommandException("No active session found with name: " + sessionName);
            }

            PlaywrightConnection connection = (PlaywrightConnection) connObj;
            Page page = connection.getPage();

            DictionaryValue result;
            switch (waitType.toLowerCase()) {
                case "element_visible":
                    result = waitForElementVisible(page, selectorType, condition, seconds);
                    break;

                case "element_hidden":
                    result = waitForElementHidden(page, selectorType, condition, seconds);
                    break;

                case "network_idle":
                    result = waitForNetworkIdle(page, seconds);
                    break;

                case "dom_stable":
                    result = waitForDOMStable(page, seconds);
                    break;

                case "custom":
                    result = waitForCustomCondition(page, condition, seconds);
                    break;

                default:
                    throw new BotCommandException("Unknown wait type: " + waitType);
            }
            logger.info("Wait completed - waitType: {}", waitType);
            return result;

        } catch (BotCommandException e) {
            logger.error("Wait failed - session: {}, waitType: {}", sessionName, waitType, e);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Wait interrupted - session: {}, waitType: {}", sessionName, waitType, e);
            throw new BotCommandException("Wait was interrupted", e);
        } catch (Exception e) {
            logger.error("Wait failed - session: {}, waitType: {}", sessionName, waitType, e);
            throw new BotCommandException("Wait failed: " + e.getMessage(), e);
        }
    }

    private DictionaryValue waitForTime(Double seconds) throws InterruptedException {
        long millis = (long) (seconds * 1000);
        Thread.sleep(millis);
        return DictionaryHelper.success("time", millis);
    }

    private DictionaryValue waitForElementVisible(Page page, String selectorType, String selector, Double seconds) {
        String playwrightSelector = buildPlaywrightSelector(selectorType, selector);
        long timeoutMs = (long) (seconds * 1000);
        long startTime = System.currentTimeMillis();

        try {
            page.waitForSelector(
                    playwrightSelector,
                    new Page.WaitForSelectorOptions()
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                            .setTimeout(timeoutMs)
            );
            return DictionaryHelper.success("element_visible", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            throw new BotCommandException(
                    "Element did not become visible within " + seconds + " seconds: " + playwrightSelector, e
            );
        }
    }

    private DictionaryValue waitForElementHidden(Page page, String selectorType, String selector, Double seconds) {
        String playwrightSelector = buildPlaywrightSelector(selectorType, selector);
        long timeoutMs = (long) (seconds * 1000);
        long startTime = System.currentTimeMillis();

        try {
            page.waitForSelector(
                    playwrightSelector,
                    new Page.WaitForSelectorOptions()
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN)
                            .setTimeout(timeoutMs)
            );
            return DictionaryHelper.success("element_hidden", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            throw new BotCommandException(
                    "Element did not become hidden within " + seconds + " seconds: " + playwrightSelector, e
            );
        }
    }

    private DictionaryValue waitForNetworkIdle(Page page, Double seconds) throws InterruptedException {
        long timeoutMs = (long) (seconds * 1000);
        long startTime = System.currentTimeMillis();

        // Wait for network to be idle (no requests for 500ms)
        try {
            page.waitForLoadState(
                    com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(timeoutMs)
            );

            return DictionaryHelper.success("network_idle", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            throw new BotCommandException(
                    "Network did not become idle within " + seconds + " seconds", e
            );
        }
    }

    private DictionaryValue waitForDOMStable(Page page, Double seconds) throws InterruptedException {
        long timeoutMs = (long) (seconds * 1000);
        long startTime = System.currentTimeMillis();
        int stableCheckIntervalMs = 500;
        int requiredStableTime = 1000; // DOM must be stable for 1 second

        String getDomSignature =
                "() => {" +
                        "  const allElements = document.querySelectorAll('*');" +
                        "  return allElements.length + '-' + document.body.innerHTML.length;" +
                        "}";

        String lastSignature = "";
        long lastChangeTime = startTime;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                String currentSignature = String.valueOf(page.evaluate(getDomSignature));

                if (!currentSignature.equals(lastSignature)) {
                    lastSignature = currentSignature;
                    lastChangeTime = System.currentTimeMillis();
                } else {
                    // Check if DOM has been stable long enough
                    long stableTime = System.currentTimeMillis() - lastChangeTime;
                    if (stableTime >= requiredStableTime) {
                        return DictionaryHelper.success("dom_stable", System.currentTimeMillis() - startTime);
                    }
                }

                Thread.sleep(stableCheckIntervalMs);
            } catch (Exception e) {
                // Page might be navigating, continue waiting
                Thread.sleep(stableCheckIntervalMs);
            }
        }

        throw new BotCommandException("DOM did not stabilize within " + seconds + " seconds");
    }

    private DictionaryValue waitForCustomCondition(Page page, String jsCondition, Double seconds) throws InterruptedException {
        long timeoutMs = (long) (seconds * 1000);
        long startTime = System.currentTimeMillis();
        int checkIntervalMs = 500;

        // Wrap condition in function that returns boolean
        String wrappedCondition = "() => { try { return Boolean(" + jsCondition + "); } catch(e) { return false; } }";

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                Object result = page.evaluate(wrappedCondition);
                if (Boolean.TRUE.equals(result)) {
                    return DictionaryHelper.success("custom", System.currentTimeMillis() - startTime);
                }
            } catch (Exception e) {
                // Condition evaluation failed, continue waiting
            }

            Thread.sleep(checkIntervalMs);
        }

        throw new BotCommandException("Custom condition did not become true within " + seconds + " seconds: " + jsCondition);
    }

    private String buildPlaywrightSelector(String selectorType, String selector) {
        switch (selectorType.toLowerCase()) {
            case "css":
                return "css=" + selector;
            case "text":
                return "text=" + selector;
            case "xpath":
            default:
                return "xpath=" + selector;
        }
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}