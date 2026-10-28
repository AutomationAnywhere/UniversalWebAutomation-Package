package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utils.*;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.BotCommand;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AllowedTarget;
import com.automationanywhere.commandsdk.model.DataType;
import com.automationanywhere.core.security.SecureString;
import com.microsoft.playwright.Page;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.*;

@BotCommand
@CommandPkg(
        name = "FullSelfDrivingTypeSafe",
        label = "FSD TypeSafe (Fast, Cheap Decisions)",
        description = "Goal-based browser automation: an OpenAI model plans each page (which fields get which " +
                "values, what to click), TypeSafe's classifier matches them to the page's controls, and " +
                "Playwright performs the actions.",
        node_label = "FSD TypeSafe: {{request}}",
        icon = "pkg.svg",
        allowed_agent_targets = {
                AllowedTarget.WINDOWS,
                AllowedTarget.MAC_OS
        },
        return_label = "Result Dictionary",
        return_required = true,
        return_type = DataType.DICTIONARY,
        return_sub_type = DataType.STRING,
        return_description = "Keys are 'status', 'stopReason' (done | step_limit | timeout | stalled | no_writer), " +
                "'reason' (planner's explanation when it stopped), 'history' (one line per page step), 'decisions' (rounds " +
                "TypeSafe decided alone vs rounds the planner planned), 'numberOfSteps', 'data' (if schema given), " +
                "'iframesSkipped', 'typeSafeInputTokens', 'openAiInputTokens', 'openAiOutputTokens', " +
                "and 'step1'..'stepN' each a dictionary of actionType/target/confidence/result"
)
public class FullSelfDrivingTypeSafe {

    private static final Logger logger = LogManager.getLogger(FullSelfDrivingTypeSafe.class);
    private static final int MAX_STEPS_CEILING = 50;
    private static final long WARN_ABOVE_SECONDS = 1800;

    @Sessions
    private Map<String, Object> sessions;

    @Execute
    public DictionaryValue action(
            @Idx(index = "1", type = TEXT)
            @Pkg(label = "Session name", default_value_type = DataType.STRING, default_value = "Default")
            @NotEmpty String sessionName,

            @Idx(index = "2", type = CREDENTIAL)
            @Pkg(label = "TypeSafe API Key (map a credential)")
            @NotEmpty SecureString typeSafeApiKeySS,

            @Idx(index = "3", type = CREDENTIAL)
            @Pkg(label = "OpenAI API Key (plans each page)")
            SecureString openAiApiKeySS,

            @Idx(index = "4", type = SELECT, options = {
                    @Idx.Option(index = "4.1", pkg = @Pkg(label = "gpt-6-luna", value = "gpt-6-luna")),
                    @Idx.Option(index = "4.2", pkg = @Pkg(label = "gpt-6-astra", value = "gpt-6-astra")),
                    @Idx.Option(index = "4.3", pkg = @Pkg(label = "gpt-6-sol", value = "gpt-6-sol")),
                    @Idx.Option(index = "4.4", pkg = @Pkg(label = "gpt-5-mini", value = "gpt-5-mini")),
                    @Idx.Option(index = "4.5", pkg = @Pkg(label = "gpt-5", value = "gpt-5")),
                    @Idx.Option(index = "4.6", pkg = @Pkg(label = "gpt-4.1", value = "gpt-4.1")),
                    @Idx.Option(index = "4.7", pkg = @Pkg(label = "gpt-4.1-mini", value = "gpt-4.1-mini"))
            })
            @Pkg(label = "OpenAI Writer Model", default_value = "gpt-6-luna", default_value_type = DataType.STRING)
            String writerModel,

            @Idx(index = "5", type = TEXTAREA)
            @Pkg(label = "Request (what should be done?)")
            @NotEmpty String request,

            @Idx(index = "6", type = TEXTAREA)
            @Pkg(label = "Output Format Schema (optional)")
            String outputFormatSchema,

            @Idx(index = "7", type = NUMBER)
            @Pkg(label = "Max page steps (each fills one page's fields and makes its clicks)", default_value = "30",
                    default_value_type = DataType.NUMBER)
            @NotEmpty Double maxSteps,

            @Idx(index = "8", type = NUMBER)
            @Pkg(label = "Max run seconds", default_value = "120", default_value_type = DataType.NUMBER)
            @NotEmpty Double maxRunSeconds,

            @Idx(index = "9", type = NUMBER)
            @Pkg(label = "Min confidence (0-1)", default_value = "0.4", default_value_type = DataType.NUMBER)
            @NotEmpty Double minConfidence
    ) {
        logger.info("FullSelfDrivingTypeSafe started - session: {}, maxSteps: {}, maxRunSeconds: {}, minConfidence: {}",
                sessionName, maxSteps, maxRunSeconds, minConfidence);

        Object connObj = this.sessions.get(sessionName);
        if (!(connObj instanceof PlaywrightConnection)) {
            throw new BotCommandException("No active session found with name: " + sessionName);
        }
        Page page = ((PlaywrightConnection) connObj).getPage();

        int cappedMaxSteps = validateMaxSteps(maxSteps);
        long maxRunMillis = validateMaxRunSeconds(maxRunSeconds);

        String typeSafeApiKey = typeSafeApiKeySS.getInsecureString();
        TypeSafeClient typeSafeClient = typeSafeBaseUrlOverride == null
                ? new TypeSafeClient(typeSafeApiKey)
                : new TypeSafeClient(typeSafeApiKey, typeSafeBaseUrlOverride);

        OpenAiWriterClient writer = null;
        if (openAiApiKeySS != null) {
            writer = new OpenAiWriterClient(openAiApiKeySS.getInsecureString(), writerModel);
        } else {
            logger.warn("FullSelfDrivingTypeSafe: no OpenAI key mapped - nothing can be planned, the run will stop immediately");
        }

        TypeSafeRunner.RunResult runResult;
        try {
            TypeSafeRunner runner = new TypeSafeRunner();
            runResult = runner.run(page, typeSafeClient, writer, request, outputFormatSchema,
                    cappedMaxSteps, maxRunMillis, minConfidence);
        } catch (BotCommandException e) {
            throw e;
        } catch (Exception e) {
            logger.error("FullSelfDrivingTypeSafe failed - session: {}", sessionName, e);
            throw new BotCommandException("FullSelfDrivingTypeSafe failed: " + e.getMessage(), e);
        }

        Map<String, Value> out = new HashMap<>();
        out.put("status", new StringValue(runResult.status));
        out.put("stopReason", new StringValue(runResult.stopReason));
        out.put("reason", new StringValue(runResult.reason == null ? "" : runResult.reason));
        out.put("history", new StringValue(String.join("\n", runResult.history)));
        out.put("decisions", new StringValue("TypeSafe " + runResult.classifierRounds + " rounds, planner "
                + runResult.plannerRounds + " rounds"
                + (runResult.routing.isEmpty() ? "" : "\n" + String.join("\n", runResult.routing))));
        out.put("numberOfSteps", new StringValue(String.valueOf(runResult.steps.size())));
        out.put("iframesSkipped", new StringValue(String.valueOf(runResult.iframesSkipped)));
        out.put("typeSafeInputTokens", new StringValue(String.valueOf(runResult.typeSafeInputTokens)));
        out.put("openAiInputTokens", new StringValue(String.valueOf(writer == null ? 0 : writer.getPromptTokens())));
        out.put("openAiOutputTokens", new StringValue(String.valueOf(writer == null ? 0 : writer.getCompletionTokens())));
        if (runResult.data != null) {
            out.put("data", new StringValue(runResult.data));
        }
        for (int i = 0; i < runResult.steps.size(); i++) {
            TypeSafeRunner.StepTrace trace = runResult.steps.get(i);
            Map<String, Value> stepMap = new HashMap<>();
            stepMap.put("actionType", new StringValue(trace.actionType));
            stepMap.put("target", new StringValue(trace.target));
            stepMap.put("confidence", new StringValue(String.valueOf(trace.confidence)));
            stepMap.put("result", new StringValue(trace.result == null ? "" : trace.result));
            out.put("step" + (i + 1), new DictionaryValue(stepMap));
        }

        logger.info("FullSelfDrivingTypeSafe completed - session: {}, stopReason: {}, steps: {}",
                sessionName, runResult.stopReason, runResult.steps.size());
        return new DictionaryValue(out);
    }

    public static int validateMaxSteps(Double maxSteps) {
        if (maxSteps == null || maxSteps <= 0) {
            throw new BotCommandException("Max steps must be a positive number.");
        }
        int requested = (int) Math.round(maxSteps);
        if (requested > MAX_STEPS_CEILING) {
            logger.warn("FullSelfDrivingTypeSafe: requested maxSteps {} exceeds hard ceiling {}, capping",
                    requested, MAX_STEPS_CEILING);
            return MAX_STEPS_CEILING;
        }
        return requested;
    }

    public static long validateMaxRunSeconds(Double maxRunSeconds) {
        if (maxRunSeconds == null || maxRunSeconds <= 0) {
            throw new BotCommandException("Max run seconds must be a positive number.");
        }
        if (maxRunSeconds > WARN_ABOVE_SECONDS) {
            logger.warn("FullSelfDrivingTypeSafe: maxRunSeconds {} exceeds 30 minutes - this run may take a long time",
                    maxRunSeconds);
        }
        return Math.round(maxRunSeconds * 1000);
    }

    // Test-only hook: lets tests point this action at a local TypeSafe stub instead of
    // the real https://api.typesafe.ai. Never set by production code paths.
    private String typeSafeBaseUrlOverride;

    public void setTypeSafeBaseUrlOverrideForTests(String baseUrl) {
        this.typeSafeBaseUrlOverride = baseUrl;
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}
