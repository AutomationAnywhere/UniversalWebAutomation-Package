# Automation Anywhere Bot Command Package Development Guide

This guide provides comprehensive best practices for developing Automation Anywhere bot command packages using the patterns established in the Universal Web Automation package.

---

## Table of Contents

1. [Project Architecture Overview](#project-architecture-overview)
2. [Setting Up a New Package](#setting-up-a-new-package)
3. [Command Development Patterns](#command-development-patterns)
4. [Session Management](#session-management)
5. [Data Types and Return Values](#data-types-and-return-values)
6. [Selector Strategies and Element Location](#selector-strategies-and-element-location)
7. [Testing Patterns](#testing-patterns)
8. [Error Handling Best Practices](#error-handling-best-practices)
9. [Localization and Internationalization](#localization-and-internationalization)
10. [Build and Packaging](#build-and-packaging)
11. [Advanced Patterns](#advanced-patterns)

---

## Project Architecture Overview

### Directory Structure

```
your-package/
├── build.gradle                 # Build configuration
├── settings.gradle              # Project settings
├── libs/                        # SDK JAR files
│   ├── package-compileonly-sdk-1.6.0.jar
│   └── package-runtime-sdk-1.0.0.jar
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/automationanywhere/botcommand/
│   │   │       ├── YourCommand.java
│   │   │       └── utils/
│   │   │           └── ConnectionManager.java
│   │   └── resources/
│   │       └── locales/
│   │           ├── en_US.json
│   │           └── [other locales]
│   └── test/
│       └── java/
│           └── TestYourCommand.java
└── README.md
```

### Key Components

- **Commands**: Individual action classes annotated with `@BotCommand`
- **Utils**: Helper classes for common operations (connection management, element location, etc.)
- **Tests**: TestNG-based tests that validate command functionality
- **Locales**: Internationalization files for UI labels

---

## Setting Up a New Package

### 1. Initial Project Setup

Start your conversation with Claude Code by providing:

```
I want to create a new Automation Anywhere bot command package for [describe functionality].

The package should:
- Use Java 11
- Include Gradle build with shadow plugin
- Support Windows and macOS targets
- Use [describe external library, e.g., "Playwright for browser automation"]

Please help me set up the project structure with:
1. build.gradle configuration
2. Package structure under com.automationanywhere.botcommand
3. A basic LaunchSession command
4. Corresponding test class
```

### 2. Essential build.gradle Configuration

```gradle
buildscript {
    repositories {
        gradlePluginPortal()
        flatDir { dirs 'libs' }
        dependencies {
            classpath name:'package-compileonly-sdk', version: '1.6.0'
            classpath "gradle.plugin.com.github.johnrengelman:shadow:7.1.1"
        }
    }
}

plugins {
    id 'java'
}

configure(allprojects){
    apply plugin: 'com.github.johnrengelman.shadow'
    apply plugin: "com.automationanywhere.command-codegen"

    ext {
        groupName = 'com.automationanywhere'
        version '1.0.0'
    }

    sourceCompatibility = JavaVersion.VERSION_11
    compileJava.options.encoding = 'UTF-8'

    repositories {
        mavenCentral()
        flatDir { dirs 'libs' }
    }

    packageJson {
        artifactName = project.name
        group = "$groupName"
        author = "Your Name"
        generatePackageWithDateTime = true
    }

    classes.dependsOn commandCodeGen

    shadowJar {
        archiveBaseName = project.name
        classifier = null
        mergeServiceFiles('META-INF/spring.*')
        zip64 = true
        exclude 'META-INF/MANIFEST.MF'
        exclude 'META-INF/*.SF'
        exclude 'META-INF/*.DSA'
        exclude 'META-INF/*.RSA'
    }

    dependencies {
        annotationProcessor name: 'package-compileonly-sdk', version: '1.6.0'
        compileOnly name: 'package-compileonly-sdk', version: '1.6.0'
        implementation name: 'package-runtime-sdk', version: '1.0.0'
        testImplementation name: 'package-compileonly-sdk', version: '1.6.0'
        testImplementation group: "org.testng", name:"testng", version: "6.14.3"

        // Add your external dependencies here
    }
}
```

---

## Command Development Patterns

### Basic Command Structure

Every command follows this pattern:

```java
package com.automationanywhere.botcommand;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AllowedTarget;

import java.util.Map;

import static com.automationanywhere.commandsdk.model.AttributeType.*;
import static com.automationanywhere.commandsdk.model.DataType.STRING;

@BotCommand
@CommandPkg(
        name = "YourCommandName",
        label = "Your Command Label",
        description = "What this command does",
        node_label = "Action {{parameterName}}",  // Dynamic label
        icon = "pkg.svg",
        allowed_agent_targets = {
                AllowedTarget.WINDOWS,
                AllowedTarget.MAC_OS
        },
        return_type = STRING,
        return_required = true,
        return_label = "Result"
)
public class YourCommand {

    @Sessions
    private Map<String, Object> sessions;

    @Execute
    public Value<String> action(
            @Idx(index = "1", type = TEXT)
            @Pkg(label = "Session name", default_value_type = STRING, default_value = "Default")
            @NotEmpty String sessionName,

            @Idx(index = "2", type = TEXT)
            @Pkg(label = "Parameter description") @NotEmpty String parameter
    ) {
        try {
            // Command logic here
            return new StringValue("Success message");
        } catch (Exception e) {
            throw new BotCommandException("Error message: " + e.getMessage(), e);
        }
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}
```

### Key Annotation Patterns

#### 1. **@CommandPkg Attributes**

- `name`: Internal identifier (no spaces)
- `label`: User-facing command name
- `node_label`: Dynamic label with `{{variableName}}` placeholders
- `icon`: Always use `"pkg.svg"` for package icon
- `allowed_agent_targets`: Platform support (WINDOWS, MAC_OS, HEADLESS, ONDEMAND_CLOUD)
- `return_type`: Return data type
- `return_required`: Whether command must return a value

#### 2. **Parameter Indexing**

Parameters MUST have sequential indices starting at "1":

```java
@Idx(index = "1", type = TEXT)  // First parameter
@Idx(index = "2", type = SELECT, options = {...})  // Second parameter
@Idx(index = "3", type = NUMBER)  // Third parameter
```

#### 3. **Input Types**

Common input types:

- `TEXT`: Single-line text input
- `TEXTAREA`: Multi-line text input
- `SELECT`: Dropdown with predefined options
- `NUMBER`: Numeric input
- `CREDENTIAL`: Secure credential reference
- `CHECKBOX`: Boolean checkbox
- `FILE`: File path selector

**SELECT Example:**

```java
@Idx(index = "2", type = SELECT, options = {
    @Idx.Option(index = "2.1", pkg = @Pkg(label = "Option 1", value = "value1")),
    @Idx.Option(index = "2.2", pkg = @Pkg(label = "Option 2", value = "value2")),
    @Idx.Option(index = "2.3", pkg = @Pkg(label = "Option 3", value = "value3"))
})
@Pkg(label = "Selection Type", default_value = "value1", default_value_type = STRING)
String selectionType
```

#### 4. **Default Values**

Always provide sensible defaults:

```java
@Pkg(label = "Timeout (seconds)",
     default_value = "30",
     default_value_type = STRING)
Double timeout
```

---

## Session Management

### Connection Object Pattern

Create a connection wrapper class to manage external resources:

```java
// utils/YourConnection.java
package com.automationanywhere.botcommand.utils;

public class YourConnection {
    private final ExternalResource resource;
    private final OtherDependency dependency;

    public YourConnection(ExternalResource resource, OtherDependency dependency) {
        this.resource = resource;
        this.dependency = dependency;
    }

    public ExternalResource getResource() {
        return resource;
    }

    public OtherDependency getDependency() {
        return dependency;
    }

    public void close() {
        // Cleanup logic
        if (resource != null) {
            resource.dispose();
        }
        if (dependency != null) {
            dependency.cleanup();
        }
    }
}
```

### Launch Command Pattern

The "Launch" command creates and stores the connection:

```java
@Execute
public Value<String> action(
        @Idx(index = "1", type = TEXT)
        @Pkg(label = "Session name", default_value = "Default")
        @NotEmpty String sessionName,

        // ... other parameters ...
) {
    try {
        // Initialize resources
        ExternalResource resource = ExternalResource.create();
        OtherDependency dependency = new OtherDependency();

        // Wrap in connection object
        YourConnection connection = new YourConnection(resource, dependency);

        // Store in session map
        this.sessions.put(sessionName, connection);

        return new StringValue("Session '" + sessionName + "' created successfully");
    } catch (Exception e) {
        throw new BotCommandException("Failed to create session: " + e.getMessage(), e);
    }
}
```

### Using Sessions in Other Commands

```java
@Execute
public Value<String> action(
        @Idx(index = "1", type = TEXT)
        @Pkg(label = "Session name", default_value = "Default")
        @NotEmpty String sessionName,

        // ... other parameters ...
) {
    // Retrieve session
    Object connObj = this.sessions.get(sessionName);
    if (!(connObj instanceof YourConnection)) {
        throw new BotCommandException("No active session found with name: " + sessionName);
    }

    YourConnection connection = (YourConnection) connObj;
    ExternalResource resource = connection.getResource();

    try {
        // Use the resource
        resource.doSomething();
        return new StringValue("Action completed");
    } catch (Exception e) {
        throw new BotCommandException("Action failed: " + e.getMessage(), e);
    }
}
```

### Close Command Pattern

Always provide a cleanup command:

```java
@BotCommand
@CommandPkg(
        name = "CloseSession",
        label = "Close Session",
        description = "Closes an active session and releases resources",
        node_label = "Close session {{sessionName}}",
        // ... other attributes ...
)
public class CloseSession {

    @Sessions
    private Map<String, Object> sessions;

    @Execute
    public Value<String> action(
            @Idx(index = "1", type = TEXT)
            @Pkg(label = "Session name", default_value = "Default")
            @NotEmpty String sessionName
    ) {
        Object connObj = this.sessions.get(sessionName);
        if (!(connObj instanceof YourConnection)) {
            throw new BotCommandException("No active session found: " + sessionName);
        }

        YourConnection connection = (YourConnection) connObj;

        try {
            connection.close();
            this.sessions.remove(sessionName);
            return new StringValue("Session '" + sessionName + "' closed successfully");
        } catch (Exception e) {
            throw new BotCommandException("Failed to close session: " + e.getMessage(), e);
        }
    }

    public void setSessions(Map<String, Object> sessions) {
        this.sessions = sessions;
    }
}
```

---

## Data Types and Return Values

### Available Return Types

| Type | Java Class | Use Case |
|------|-----------|----------|
| STRING | `StringValue` | Text results, status messages |
| NUMBER | `NumberValue` | Numeric results |
| BOOLEAN | `BooleanValue` | True/false results |
| DICTIONARY | `DictionaryValue` | Key-value pairs (structured data) |
| LIST | `ListValue` | Arrays of values |
| TABLE | `TableValue` | Tabular data |

### STRING Returns

Most common return type for simple operations:

```java
@CommandPkg(
        // ...
        return_type = STRING,
        return_required = true,
        return_label = "Status message"
)
public class SimpleCommand {
    @Execute
    public Value<String> action(...) {
        return new StringValue("Operation completed successfully");
    }
}
```

### DICTIONARY Returns

For structured data with multiple fields:

```java
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.commandsdk.model.DataType;
import java.util.HashMap;

@CommandPkg(
        // ...
        return_type = DataType.DICTIONARY,
        return_required = true,
        return_label = "Result Dictionary",
        return_description = "Keys: 'status', 'data', 'message'"
)
public class DictionaryCommand {
    @Execute
    public DictionaryValue action(...) {
        Map<String, Value> resultMap = new HashMap<>();
        resultMap.put("status", new StringValue("OK"));
        resultMap.put("data", new StringValue("{\"key\": \"value\"}"));
        resultMap.put("count", new NumberValue(42.0));

        return new DictionaryValue(resultMap);
    }
}
```

### LIST Returns

For arrays of values:

```java
import com.automationanywhere.botcommand.data.impl.ListValue;
import java.util.ArrayList;

@CommandPkg(
        // ...
        return_type = DataType.LIST,
        return_required = true
)
public class ListCommand {
    @Execute
    public ListValue<String> action(...) {
        List<Value<String>> items = new ArrayList<>();
        items.add(new StringValue("Item 1"));
        items.add(new StringValue("Item 2"));
        items.add(new StringValue("Item 3"));

        return new ListValue<>(items);
    }
}
```

### Optional Returns

Some commands don't need to return values:

```java
@CommandPkg(
        // ...
        return_type = STRING,
        return_required = false  // Makes return optional
)
public class NoReturnCommand {
    @Execute
    public Value<String> action(...) {
        // Can return null or a status message
        return new StringValue("Action performed");
    }
}
```

### SecureString for Credentials

Never store credentials in plain text:

```java
import com.automationanywhere.core.security.SecureString;

@Execute
public Value<String> action(
        @Idx(index = "2", type = CREDENTIAL)
        @Pkg(label = "API Key (credential)")
        @NotEmpty SecureString apiKey,

        // ... other parameters ...
) {
    // Extract the actual value
    String actualKey = apiKey.getInsecureString();

    // Use the key
    callExternalAPI(actualKey);

    return new StringValue("API call successful");
}
```

---

## Selector Strategies and Element Location

### Multi-Strategy Element Locator

Create a robust element locator utility that tries multiple strategies:

```java
package com.automationanywhere.botcommand.utils;

public class ElementLocator {

    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 1000;

    /**
     * Handles element interactions with fallback strategies
     */
    public static Object handle(
            Page page,
            String selectorType,
            String selector,
            String action,
            String value
    ) {
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                switch (selectorType.toLowerCase()) {
                    case "css":
                        return performAction(page, "css=" + selector, action, value);
                    case "text":
                        return performActionWithTextFallback(page, selector, action, value);
                    case "xpath":
                    default:
                        return performAction(page, "xpath=" + selector, action, value);
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                }
            }
        }

        throw new RuntimeException(
            "Failed after " + MAX_RETRIES + " attempts: " + lastException.getMessage(),
            lastException
        );
    }

    private static Object performActionWithTextFallback(
            Page page,
            String text,
            String action,
            String value
    ) {
        // Try multiple text matching strategies
        List<String> strategies = Arrays.asList(
            "text=" + text,                          // Exact match
            "text=/" + text + "/i",                  // Case insensitive
            String.format("button:has-text(\"%s\")", text),
            String.format("a:has-text(\"%s\")", text)
        );

        for (String strategy : strategies) {
            try {
                return performAction(page, strategy, action, value);
            } catch (Exception e) {
                // Continue to next strategy
            }
        }

        throw new RuntimeException("No strategy succeeded for text: " + text);
    }
}
```

### Selector Types Best Practices

1. **XPath** - Use for complex hierarchical relationships
   - Example: `//div[@class='container']//button[text()='Submit']`
   - Good for: Navigating complex DOM structures

2. **CSS** - Use for simple, performance-critical selections
   - Example: `button.submit-btn`, `#login-form input[name='username']`
   - Good for: Class-based and ID-based selection

3. **Text** - Use for user-facing elements when structure changes
   - Example: `"Click Here"`, `"Submit Form"`
   - Good for: Buttons, links where text is stable

4. **JS Path** - Use for dynamic, script-generated elements
   - Example: `document.querySelector('#app').shadowRoot.querySelector('button')`
   - Good for: Shadow DOM, dynamically inserted content

---

## Testing Patterns

### TestNG Test Class Structure

```java
import org.testng.annotations.Test;
import java.util.HashMap;
import static org.testng.AssertJUnit.*;

public class TestYourCommand {

    @Test
    public void testBasicOperation() throws InterruptedException {
        String sessionName = "test-session";
        HashMap<String, Object> sessionMap = new HashMap<>();

        // Launch/Create session
        LaunchCommand launch = new LaunchCommand();
        launch.setSessions(sessionMap);

        Value<String> launchResult = launch.action(
            sessionName,
            "parameter1",
            "parameter2"
        );

        System.out.println(launchResult.get());
        assertNotNull(launchResult);
        assertTrue(launchResult.get().contains("expected text"));

        // Test main operation
        YourCommand cmd = new YourCommand();
        cmd.setSessions(sessionMap);

        Value<String> result = cmd.action(sessionName, "test-input");

        System.out.println(result.get());
        assertNotNull(result);

        // Validate state
        YourConnection conn = (YourConnection) sessionMap.get(sessionName);
        assertTrue(conn.getResource().hasExpectedState());

        // Cleanup
        CloseCommand close = new CloseCommand();
        close.setSessions(sessionMap);
        Value<String> closeResult = close.action(sessionName);

        System.out.println(closeResult.get());
        assertFalse(sessionMap.containsKey(sessionName));
    }

    @Test
    public void testErrorCondition() {
        YourCommand cmd = new YourCommand();
        cmd.setSessions(new HashMap<>());

        try {
            cmd.action("nonexistent-session", "input");
            fail("Should have thrown BotCommandException");
        } catch (BotCommandException e) {
            assertTrue(e.getMessage().contains("No active session"));
        }
    }

    @Test
    public void testVisibleMode() throws InterruptedException {
        // For browser automation or UI tests
        String sessionName = "visible-test";
        HashMap<String, Object> sessionMap = new HashMap<>();

        LaunchCommand launch = new LaunchCommand();
        launch.setSessions(sessionMap);

        launch.action(sessionName, "parameter", "visible-mode");

        // Add delay to observe visual behavior
        Thread.sleep(5000);

        // Continue testing...
    }
}
```

### Test Best Practices

1. **Use Real External Resources**: Test against actual APIs, browsers, databases
2. **Add Visual Delays**: `Thread.sleep(5000)` for debugging visible operations
3. **Test Error Paths**: Validate error handling for invalid inputs
4. **Session Lifecycle**: Test launch → use → close for every session-based command
5. **Assertions**: Validate both return values and side effects

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests TestYourCommand

# Run with verbose output
./gradlew test --info
```

---

## Error Handling Best Practices

### Throw BotCommandException

Always use `BotCommandException` for user-facing errors:

```java
try {
    resource.performOperation();
} catch (Exception e) {
    throw new BotCommandException(
        "Operation failed: " + e.getMessage(),
        e  // Include original exception
    );
}
```

### Session Validation Pattern

```java
Object connObj = this.sessions.get(sessionName);
if (!(connObj instanceof YourConnection)) {
    throw new BotCommandException(
        "No active session found with name: " + sessionName
    );
}
```

### Input Validation

```java
if (timeout <= 0) {
    throw new BotCommandException("Timeout must be positive, got: " + timeout);
}

if (selector == null || selector.trim().isEmpty()) {
    throw new BotCommandException("Selector cannot be empty");
}
```

### Try-Catch-Finally for Cleanup

```java
Resource resource = null;
try {
    resource = Resource.acquire();
    resource.doWork();
    return new StringValue("Success");
} catch (Exception e) {
    throw new BotCommandException("Work failed: " + e.getMessage(), e);
} finally {
    if (resource != null) {
        resource.release();
    }
}
```

---

## Localization and Internationalization

### Locale File Structure

Create `src/main/resources/locales/en_US.json`:

```json
{
  "label": "Your Package Name",
  "description": "Your package description",

  "YourCommand.label": "Your Command",
  "YourCommand.description": "What your command does",
  "YourCommand.node_label": "Action with {{parameter}}",
  "YourCommand.return_label": "Result variable",

  "YourCommand.sessionName.label": "Session name",
  "YourCommand.parameter1.label": "Parameter 1 label",
  "YourCommand.parameter2.label": "Parameter 2 label"
}
```

### Supported Locales

Create corresponding files for:
- `de_DE.json` - German
- `es_XL.json` - Spanish (Latin America)
- `fr_FR.json` - French
- `it_IT.json` - Italian
- `ja_JP.json` - Japanese
- `ko_KR.json` - Korean
- `pt_BR.json` - Portuguese (Brazil)
- `ru_RU.json` - Russian
- `zh_CN.json` - Chinese (Simplified)
- `zh_TW.json` - Chinese (Traditional)

---

## Build and Packaging

### Building the Package

```bash
# Clean and build
./gradlew clean build

# Build without tests
./gradlew build -x test

# Generate shadowJar (packaged JAR)
./gradlew shadowJar
```

### Output Location

Built package appears in:
```
build/libs/your-package-[version]-[timestamp].jar
```

### Gradle Commands Reference

```bash
# List all tasks
./gradlew tasks

# Run command code generation
./gradlew commandCodeGen

# View dependencies
./gradlew dependencies

# Clean build artifacts
./gradlew clean
```

---

## Advanced Patterns

### 1. AI-Powered Commands with External APIs

```java
@Execute
public DictionaryValue action(
        @Idx(index = "1", type = TEXT)
        @Pkg(label = "Session name") @NotEmpty String sessionName,

        @Idx(index = "2", type = CREDENTIAL)
        @Pkg(label = "API Key") @NotEmpty SecureString apiKey,

        @Idx(index = "3", type = TEXTAREA)
        @Pkg(label = "Natural language request") @NotEmpty String request
) {
    String actualKey = apiKey.getInsecureString();

    // Build API request
    String apiResponse = callExternalAI(actualKey, request);

    // Parse and execute
    String generatedCode = extractCodeFromResponse(apiResponse);
    String result = executeInContext(generatedCode);

    // Return structured result
    Map<String, Value> output = new HashMap<>();
    output.put("status", new StringValue("OK"));
    output.put("executedCode", new StringValue(generatedCode));
    output.put("result", new StringValue(result));

    return new DictionaryValue(output);
}

private String callExternalAI(String apiKey, String request) throws Exception {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .build();

    String requestBody = buildJsonRequest(request);

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("https://api.example.com/v1/completions"))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();

    HttpResponse<String> response = client.send(
        req,
        HttpResponse.BodyHandlers.ofString()
    );

    if (response.statusCode() != 200) {
        throw new Exception("API error: " + response.body());
    }

    return response.body();
}
```

### 2. Smart Wait with Multiple Conditions

```java
@Idx(index = "2", type = SELECT, options = {
    @Idx.Option(index = "2.1", pkg = @Pkg(label = "Element Visible", value = "element_visible")),
    @Idx.Option(index = "2.2", pkg = @Pkg(label = "Network Idle", value = "network_idle")),
    @Idx.Option(index = "2.3", pkg = @Pkg(label = "DOM Stable", value = "dom_stable")),
    @Idx.Option(index = "2.4", pkg = @Pkg(label = "Custom JS Condition", value = "custom"))
})
@Pkg(label = "Wait type")
String waitType

// Implementation
switch (waitType.toLowerCase()) {
    case "element_visible":
        return waitForElementVisible(page, selector, timeout);
    case "network_idle":
        return waitForNetworkIdle(page, timeout);
    case "dom_stable":
        return waitForDOMStable(page, timeout);
    case "custom":
        return waitForCustomCondition(page, jsCondition, timeout);
}
```

### 3. Robust Click with Fallback Strategies

```java
private void performClickWithFallbacks(Page page, String selector) {
    Locator loc = page.locator(selector).first();

    // Strategy 1: Standard click
    try {
        loc.scrollIntoViewIfNeeded();
        Thread.sleep(300);
        loc.click(new Locator.ClickOptions().setTimeout(5000));
        return;
    } catch (Exception e) {
        // Continue to next strategy
    }

    // Strategy 2: Force click (handles overlays)
    try {
        loc.click(new Locator.ClickOptions()
            .setForce(true)
            .setTimeout(5000));
        return;
    } catch (Exception e) {
        // Continue to next strategy
    }

    // Strategy 3: JavaScript click (most reliable)
    try {
        String cssSelector = extractCssFromSelector(selector);
        page.evaluate(
            "sel => document.querySelector(sel).click()",
            cssSelector
        );
        return;
    } catch (Exception e) {
        throw new RuntimeException("All click strategies failed", e);
    }
}
```

### 4. OS-Specific Behavior

```java
String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
String modifier = os.contains("mac") ? "Meta" : "Control";

// Use OS-specific key combinations
page.keyboard().press(modifier + "+A");  // Select All
page.keyboard().press(modifier + "+C");  // Copy
page.keyboard().press(modifier + "+V");  // Paste
```

---

## Prompting Claude Code for Package Development

### Initial Prompt Template

```
I want to create an Automation Anywhere bot command package for [DESCRIBE FUNCTIONALITY].

Package Requirements:
- Java 11 with Gradle build system
- Support for Windows and macOS
- Use [EXTERNAL LIBRARY NAME] for [core functionality]
- Session-based architecture with Launch/Close pattern

Commands needed:
1. LaunchSession - Initialize connection to [resource]
2. PerformAction - Do [specific action]
3. GetData - Retrieve [specific data]
4. CloseSession - Clean up resources

Please:
1. Set up the project structure with build.gradle
2. Create a connection wrapper class in utils/
3. Implement the LaunchSession command with session management
4. Add corresponding test class with TestNG
5. Include proper error handling and logging

Reference the DEVELOPMENT_GUIDE.md file for architectural patterns.
```

### Follow-Up Prompts

```
Now implement the PerformAction command following these patterns:
- Use session management from LaunchSession
- Support multiple selector types (xpath, css, text)
- Include retry logic with 3 attempts
- Return StringValue with status message
- Add comprehensive error handling
```

```
Add a test class TestPerformAction that:
- Creates a test session
- Performs the action with valid inputs
- Tests error conditions (missing session, invalid input)
- Validates results with assertions
- Cleans up the session
```

```
Implement GetData command that returns a DictionaryValue with:
- "status" key (OK or ERROR)
- "data" key with extracted information
- "timestamp" key with current time
Include test coverage for all return keys.
```

### Best Practices for Claude Code Collaboration

1. **Reference this guide**: Always mention "follow patterns from DEVELOPMENT_GUIDE.md"
2. **Be specific about data types**: Specify StringValue, DictionaryValue, etc.
3. **Request tests alongside code**: Ask for TestNG tests with every command
4. **Incremental development**: Build one command at a time
5. **Review generated code**: Check for session management, error handling, return types
6. **Test frequently**: Run `./gradlew test` after each new command

---

## Common Pitfalls and Solutions

### Pitfall 1: Missing Session Injection

**Problem**: NullPointerException when accessing sessions

**Solution**: Always include:
```java
@Sessions
private Map<String, Object> sessions;

public void setSessions(Map<String, Object> sessions) {
    this.sessions = sessions;
}
```

### Pitfall 2: Incorrect Return Type

**Problem**: Build fails with type mismatch

**Solution**: Match `return_type` in `@CommandPkg` with method return:
```java
@CommandPkg(return_type = STRING)  // Must match
public Value<String> action(...) {  // Return type here
    return new StringValue("result");
}
```

### Pitfall 3: Parameter Index Gaps

**Problem**: Parameters don't appear in UI

**Solution**: Use sequential indices:
```java
@Idx(index = "1", ...)  // Good
@Idx(index = "2", ...)  // Good
@Idx(index = "4", ...)  // BAD - skips 3
```

### Pitfall 4: Missing @NotEmpty Validation

**Problem**: Runtime errors on empty inputs

**Solution**: Add `@NotEmpty` to required parameters:
```java
@Pkg(label = "Session name")
@NotEmpty String sessionName  // Validates non-empty
```

### Pitfall 5: Hardcoded Timeouts

**Problem**: Commands fail on slow systems

**Solution**: Make timeouts configurable:
```java
@Idx(index = "5", type = NUMBER)
@Pkg(label = "Timeout (seconds)", default_value = "30")
Double timeout
```

---

## Checklist for New Commands

- [ ] Command class annotated with `@BotCommand` and `@CommandPkg`
- [ ] All parameters have sequential `@Idx` annotations
- [ ] Required parameters have `@NotEmpty` validation
- [ ] `@Sessions` field and `setSessions()` method present
- [ ] Session validation in `@Execute` method
- [ ] Proper exception handling with `BotCommandException`
- [ ] Meaningful return value (StringValue, DictionaryValue, etc.)
- [ ] Corresponding TestNG test class
- [ ] Test covers success and error cases
- [ ] Locale entries in `en_US.json`
- [ ] README.md updated with command documentation

---

## Quick Reference

### Import Statements

```java
// Core AA SDK
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.*;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AllowedTarget;
import com.automationanywhere.commandsdk.model.AttributeType;
import com.automationanywhere.commandsdk.model.DataType;
import com.automationanywhere.core.security.SecureString;

// Java standard library
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

// TestNG (for tests)
import org.testng.annotations.Test;
import static org.testng.AssertJUnit.*;
```

### Gradle Commands

```bash
./gradlew clean build        # Full clean build
./gradlew test              # Run tests only
./gradlew shadowJar         # Create packaged JAR
./gradlew commandCodeGen    # Generate command metadata
./gradlew build -x test     # Build without tests
```

---

## Conclusion

This guide captures the proven patterns from the Universal Web Automation package. Follow these practices to build robust, maintainable Automation Anywhere bot command packages efficiently with Claude Code assistance.

When starting a new package project, provide Claude Code with:
1. This DEVELOPMENT_GUIDE.md file
2. Clear functional requirements
3. External library dependencies
4. Target platform constraints

Claude Code will use these patterns to scaffold your package structure, implement commands with proper session management, create comprehensive tests, and ensure production-quality code following AA SDK best practices.