# Universal Web Automation Package

Browser automation actions for Automation Anywhere, built on Playwright. Use the
low-level actions (click, type, read, run JavaScript) when you know the page, and the
**FSD** actions when you would rather describe the goal in plain English and let AI drive.

Works in TaskBots on **Windows** and **macOS** Bot Agents. (Headless and on-demand cloud
targets need extra signing that only Automation Anywhere engineering can do.)

- [Install](#install) · [Result dictionary](#what-every-action-returns) · [Low-level actions](#low-level-actions) · [FSD actions](#fsd-actions-ai-driven) · [Demo bots](#demo-bots) · [Troubleshooting](#troubleshooting)

---

## Install

A release has two files, and they are installed in **different places**:

| File | What it is | How to install |
|---|---|---|
| `UniversalWebAutomation-<version>.jar` | The **package** (the actions themselves) | Control Room → **Administration → Packages → Add package** → upload the jar → set it as the default version |
| `UniversalWebAutomationDemo_<version>_WithoutPackages.zip` | Six demo **TaskBots** (no package inside) | Control Room → **Automation → Import bots** → choose the zip → import |

**Order matters:** install the jar first, then import the zip. The demo bots reference the
package, so they only open and run once it is present.

After importing, the bots are in `Bot Store\UniversalWebAutomationPackage`:
`..._mac` bots run on macOS Bot Agents and `..._windows` bots run on Windows Bot Agents.

The two FSD demos also need a credential for a `TypeSafe` API Key and a credential a `OpenAI` API Key. Open the FSD actions in those taskbots
and re-map the two credential fields. See [docs/demo-taskbot/README.md](docs/demo-taskbot/README.md).

---

## How to use any action

Every action starts from a **session name** (default `Default`). `Launch Browser` creates the
session; every other action finds its browser by that name; `Close Browser` ends it. You can
run several browsers at once by giving each its own session name.

Typical bot shape:

1. **Launch Browser** (session `Shop`, URL `https://www.saucedemo.com`)
2. Wait / Set Text / Click Object / Get Text … (all with session `Shop`)
3. **Close Browser** (session `Shop`), also in the catch block of a Try/Catch so a failure never leaves a browser open.

### Selectors

Actions that find an element take a **Selector type** and a **Selector value**:

| Type | Example value | Matches |
|---|---|---|
| CSS | `#user-name`, `.inventory_item:first-child .inventory_item_name`, `button[type=submit]` | Standard CSS selector |
| XPath | `//input[@id='user-name']`, `//button[normalize-space()='Login']` | XPath expression |
| Text | `Add to cart` | Element showing that visible text |

Prefer CSS with an `id` or `data-*` attribute; it is the most stable. Most actions default to
XPath; **Set Rich Text** defaults to CSS.

### What every action returns

Every low-level action returns a **Dictionary of String** with the same four keys:

| Key | Meaning |
|---|---|
| `result` | The action's payload: page title, element text, script result, or a confirmation message |
| `status` | `success` or `error` |
| `elapsed_ms` | How long the action took |
| `error_message` | Empty on success; the reason when `status` is `error` |

Assign the return to a Dictionary variable (e.g. `dResult`) and read what you need with
`$dResult{result}$`. Most failures raise an error you can catch with Try/Catch; some return `status` = `error` instead, so check `$dResult{status}$` when it matters.

---

## Low-level actions

Examples use SauceDemo (`https://www.saucedemo.com`, test login `standard_user` / `secret_sauce`).
"Session" is always the session name; examples assume `Shop`.

### Launch Browser
Opens a browser at a URL and creates the session.

| Field | Value |
|---|---|
| Session name | `Shop` (default `Default`) |
| URL | `https://www.saucedemo.com` |
| Headless? | `False` shows the window (default); `True` hides it |
| Browser type | `Chromium` (default), `Firefox`, `WebKit` |

`result`: a confirmation including the page title.

### Maximize Browser
Maximizes the window (Chromium, visible mode only). Fields: Session name. Call it right after Launch Browser so pages lay out like a full-size window.

### Navigate To URL
Loads a new URL in the **existing** session (cookies and login are kept).

| Field | Example |
|---|---|
| Session name | `Shop` |
| Target URL | `https://www.saucedemo.com/inventory.html` |

`result`: confirmation with the final URL.

### Wait
Waits before the next step. Use it right after a click that loads a new page.

| Field | Value |
|---|---|
| Wait type | `Element Visible`, `Element Hidden`, `Fixed Time`, `Network Idle`, `DOM Stable`, `Custom Condition (JS)` (default `Fixed Time`) |
| Timeout in seconds | `30` by default; the Fixed Time wait lasts this long |
| Selector type | CSS / XPath / Text (for element waits) |
| Selector/Condition | An element selector, or for Custom Condition a JavaScript expression returning true/false |

Example: wait for the product list after login: type `Element Visible`, timeout `10`, selector type `CSS`, selector `.inventory_list`.
Example custom condition: `document.querySelectorAll('.inventory_item').length > 0`.

### Click Object
Clicks an element.

| Field | Example |
|---|---|
| Selector type | `CSS` |
| Selector value | `#login-button` |

### Set Text
Types or fills text into an input, textarea or contenteditable element.

| Field | Example |
|---|---|
| Selector type / value | `CSS` / `#user-name` |
| Text to type | `standard_user` (or a variable such as `$sUsername$`) |

### Set Rich Text
Like Set Text, for rich text editors (Quill, TinyMCE, CKEditor) where plain fill does not work.
Point it at the editable element, e.g. selector type `CSS`, value `.ql-editor`, text `Meeting notes for Monday`.

### Insert Keystrokes
Types text into whatever element **currently has focus**, one key at a time. Use it after a
Click Object on the field, e.g. click `#postal-code`, then Insert Keystrokes with `12345`.
It types characters; it does not press shortcut combinations.

### Select Dropdown
Chooses an option in a `<select>`.

| Field | Example |
|---|---|
| Selector type / value | `CSS` / `.product_sort_container` |
| Selection Mode | `By Value`, `By Text` or `By Index` |
| Option to select | `lohi` (by value), `Price (low to high)` (by text) or `2` (by index) |

### Get Text
Reads the visible text of an element. Example: selector type `CSS`, value `.title` returns `Products` in `$dResult{result}$`. Store it in a String variable to use it later.

### Execute JavaScript
Runs JavaScript in the page and returns its result as text.

```javascript
return document.querySelectorAll('.inventory_item').length;
```

returns `6`. Return a JSON string to hand back several values:
`return JSON.stringify([...document.querySelectorAll('.inventory_item_name')].map(e => e.innerText));`

### Close Browser
Closes the session and frees it. Field: Session name.

---

## FSD actions (AI-driven)

FSD ("Full Self Driving") actions take a goal written in plain English and operate the
browser to reach it. Both need an **OpenAI API key** (and FSD TypeSafe also a **TypeSafe API key**).
Map keys from the Credential Vault; never type them into the bot.

The browser session must already exist (`Launch Browser` first), and the actions stop
at the page where the goal is complete, so read results from that page.

### FSD TypeSafe (Fast, Cheap Decisions) — recommended

An OpenAI model reads each page and plans the step (which fields get which values, what to
click). TypeSafe's classifier matches each planned field and click to the page's real controls, and
on pages with nothing to fill it picks the next click itself without calling the model.
Playwright performs the actions, so React/Vue forms receive the values. An uncertain match
goes back to the planner instead of being guessed. Field values go only to OpenAI, never to TypeSafe.

| Field | Meaning | Default |
|---|---|---|
| Session name | Browser session to drive | `Default` |
| TypeSafe API Key | Credential Vault mapping | required |
| OpenAI API Key | Credential Vault mapping (plans each page) | required for any work |
| OpenAI Writer Model | `gpt-6-luna`, `gpt-6-astra`, `gpt-6-sol`, `gpt-5-mini`, `gpt-5`, `gpt-4.1`, `gpt-4.1-mini` | `gpt-6-luna` |
| Request | The goal in plain English | required |
| Output Format Schema | Optional JSON shape of the data to read from the **final page** | empty |
| Max page steps | Each step fills one page's fields and makes its clicks (hard cap 50) | 30 |
| Max run seconds | Stop after this long | 120 |
| Min confidence (0-1) | Below this a match goes back to the planner | 0.4 |

**Example request**

```
Log in with username "standard_user" and password "secret_sauce". Sort the products by price from low
to high. Add the first product to the cart, open the cart, click Checkout, and fill in first name "Ada",
last name "Lovelace" and postal code "12345", click Continue, and then click Finish.
Stop only once the order confirmation (Thank you for your order!) is showing.
```

**Example output schema**: `{"confirmation": "string"}`

**Returns** a Dictionary of String:

| Key | Meaning |
|---|---|
| `status`, `stopReason` | `stopReason` is `done`, `step_limit`, `timeout`, `stalled` or `no_writer` |
| `reason` | The planner's explanation when it stopped |
| `numberOfSteps`, `history`, `decisions` | Steps taken, one line per page step, who decided each round |
| `data` | JSON text matching your schema (only if a schema was given), e.g. `{"confirmation":"Thank you for your order!"}`; parse with the JSON package |
| `typeSafeInputTokens`, `openAiInputTokens`, `openAiOutputTokens` | Token usage for cost tracking |
| `step1` … `stepN` | Each action taken (dictionaries with `actionType`, `target`, `confidence`, `result`) |

**Always verify FSD's work.** `stopReason = done` means the planner believed it finished, not that the
goal is true. In testing, a refused login still ended in `done`, and answer extraction can fill in
plausible but wrong data. After the run, **Wait** for an element that exists only if the goal truly
succeeded (for the shop above, `.complete-header`) and fail the bot if it is missing. The demo bots do this.

**Tips:** list one outcome per request ("Stop only once X is showing"); put exact values in quotes;
the data schema can only read the final page, so for data from a middle page, split the work into
two FSD runs and stop the first on the page you want to read.

### FSD Multi-Turn (AI-Powered)

A planner writes a numbered plan; for each step an actor model writes JavaScript that runs in the
page and a validator model judges the result, re-planning on failure. Slower and less reliable than
FSD TypeSafe (see [docs/fsd-benchmarks.md](docs/fsd-benchmarks.md)); use it for flows that need arbitrary in-page scripting.

| Field | Meaning | Default |
|---|---|---|
| Session name | Browser session | `Default` |
| OpenAI API Key | Credential Vault mapping | required |
| OpenAI Model | Same model list as above | `gpt-6-luna` |
| Request | The goal in plain English | required |
| Output Format Schema | Optional JSON shape of the data to return | empty |
| Execution timeout (seconds) | Per generated script | 60 |
| Maximum turns | Plan/act/validate rounds | 5 |
| Max run seconds | Overall limit | 120 |

**Example request:** `On this page, read the names and prices of all products and return them.`
with schema `{"products": [{"name": "string", "price": "string"}]}`.

**Returns** a Dictionary with `status`, `stopReason`, `initial_plan`, `turn1`…`turnN`
(action, code, result, validation), token counts, and `data` (if a schema was given).

---

## Demo bots

[docs/demo-taskbot/README.md](docs/demo-taskbot/README.md) describes six ready-made TaskBots
(each in a `_mac` and a `_windows` version) that run the same SauceDemo shop flow three ways:

| Demo | Shows |
|---|---|
| `UniversalWebAutomation_SauceDemo_Demo_*` | Every low-level action, with a `bSlowForDemo` switch that adds visible pauses |
| `UniversalWebAutomation_SauceDemo_FSD_Demo_*` | FSD TypeSafe with two goals, reading data from the order overview and a token-usage message box |
| `UniversalWebAutomation_SauceDemo_FSD_SinglePrompt_Demo_*` | The whole flow in one FSD goal |

The `_mac` bots have been run on macOS. The `_windows` bots are built for Windows but have not yet been run on a Windows Bot Agent.

---

## Troubleshooting

- **"No active session found with name …"**: the session name differs from the one in Launch Browser, or the browser was already closed.
- **Element not found / click fails right after a page change**: add a **Wait** (`Element Visible`) for the next element before using it.
- **First run of a brand-new bot fails with "File not found"** (Control Room deployment): run it again unchanged; it can take a few tries.
- **FSD stops with `stalled` or `step_limit`**: make the request more specific, raise Max page steps, or check `history` to see where it got stuck.
- **FSD says `done` but nothing happened**: verify with a Wait on a success-only element, as described above.

---

## For developers

See [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) for building the package, and [AA_PACKAGE_BEST_PRACTICES.md](AA_PACKAGE_BEST_PRACTICES.md) for conventions. Build the jar with `./gradlew build`; it is written to `build/libs/`.
