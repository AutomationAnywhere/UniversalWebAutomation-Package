# Demo TaskBot: SauceDemo walkthrough

There are three demos, each in two platform versions. Names end in `_mac` or
`_windows`, and each version has its own importable zip in this folder:

| Demo | macOS | Windows |
|---|---|---|
| Direct actions | `UniversalWebAutomation_SauceDemo_Demo_mac` | `UniversalWebAutomation_SauceDemo_Demo_windows` |
| FSD TypeSafe, two goals | `UniversalWebAutomation_SauceDemo_FSD_Demo_mac` | `UniversalWebAutomation_SauceDemo_FSD_Demo_windows` |
| FSD TypeSafe, one goal | `UniversalWebAutomation_SauceDemo_FSD_SinglePrompt_Demo_mac` | `UniversalWebAutomation_SauceDemo_FSD_SinglePrompt_Demo_windows` |

The two versions of a demo have identical steps, inputs and outputs; only the
platform the TaskBot is built for differs. The text below describes the macOS
bots by name, and applies equally to the Windows ones. The macOS versions were
run on a Mac Bot Agent. The Windows versions compile for Windows and import
cleanly, but have not yet been run on a Windows Bot Agent.

`UniversalWebAutomation_SauceDemo_Demo_mac` is a TaskBot (macOS and Windows versions) that shows the
Universal Web Automation package end to end against a public practice shop,
[saucedemo.com](https://www.saucedemo.com) (its test login is published on
the login page). It needs only this package and runs unattended on a
Bot Agent. Nothing real is ordered.

- **In Control Room:** `Bot Store\UniversalWebAutomationPackage\UniversalWebAutomation_SauceDemo_Demo_mac`
- **Importable copy:** `UniversalWebAutomation_SauceDemo_Demo_mac.zip` (import into any Control Room that has the package)

## What it does, and which action does it

| Step | What happens | Actions shown |
|---|---|---|
| 1. Launch | Opens Chromium on the shop and reads the page title | Launch Browser, Maximize Browser |
| 2. Log in | Waits for the form, types credentials, presses Log In | Wait, Set Text, Click Object |
| 3. Read the catalog | Reads the heading, sorts by price, reads the first product, counts and lists all products with JavaScript | Get Text, Select Dropdown, Execute JavaScript |
| 4. Add to cart | Adds the first product and reads the cart badge | Click Object, Get Text |
| 5. Check out | Fills the checkout form (the postal code with raw keystrokes) and reads the order total | Set Text, Insert Keystrokes, Wait, Get Text |
| 6. Finish | Places the order, reads the confirmation, then navigates the same session to another page | Click Object, Get Text, Navigate To URL |
| 7. Close | Closes the browser (also done on failure) | Close Browser |

Not shown here: **Set Rich Text** (needs a rich text editor, not present on
this site) and the two AI actions, **FSD TypeSafe** and **FSD Multi-Turn**
(they need API keys mapped from the Credential Vault).

## How the actions' results are used

Every action returns a dictionary with the same four keys: `result`,
`status`, `elapsed_ms`, `error_message`. The bot stores each return in one
internal dictionary variable (`dResult`) and copies the `result` key into
the output it belongs to with `$dResult{result}$`.

## Inputs (all have working defaults)

`sUsername`, `sPassword` (SauceDemo's published test account), `sSortOption`
(`az`, `za`, `lohi`, `hilo`), `sShopperFirstName`, `sShopperLastName`,
`sPostalCode`, and `bSlowForDemo` (see below; default `false`).

## Outputs

`bSuccess`, `sErrorMessage`, `sPageTitle`, `sPageHeading`,
`sFirstProductName`, `sFirstProductPrice`, `sProductCount`,
`sProductSummary`, `sCartCount`, `sOrderTotal`, `sConfirmation`, `sFinalUrl`.

A normal run returns, for example: heading `Products`, first product
`Sauce Labs Onesie` at `$7.99`, 6 products, cart count `1`, total
`Total: $8.63`, confirmation `Thank you for your order!`.

## Fast run or demo run: `bSlowForDemo`

One input switches the bot between two speeds:

- `bSlowForDemo = false` (default): full speed, about 15 seconds. Use it for normal runs and testing.
- `bSlowForDemo = true`: the bot pauses 2 seconds after each visible action
  (15 pauses, about 30 extra seconds), so an audience can see each field fill,
  each click, and each page change.

The pauses are `If` blocks around the **Delay** action, so with the flag off
they are skipped entirely. To change how long they pause, edit the Delay
time in any of those blocks (they are all 2 seconds).

## Running it

Run it from Control Room on a macOS device with the package installed. The
browser is visible by default so you can watch it; to run headless, change
Launch Browser's `Headless?` setting to `True` in step 1. To see the failure
path, run it with `sUsername` set to `locked_out_user`: it returns
`bSuccess = false` with a message and still closes the browser.

Where the page needs time (right after a click that loads a new page), the
bot uses **Wait** for an element before touching it. That is the pattern to
copy in your own bots.

## Second demo: the same shop, driven by FSD TypeSafe

`UniversalWebAutomation_SauceDemo_FSD_Demo_mac` (same folder, plus
`UniversalWebAutomation_SauceDemo_FSD_Demo_mac.zip`) does the same SauceDemo run
without a single selector. It opens the browser, then gives the **FSD
TypeSafe** action two plain-English goals:

1. Log in, sort by price, add the first product to the cart, check out with a
   name and postal code, and stop on the overview page. An output format
   schema makes FSD read the product, price and total off that page.
2. Click Finish and read the confirmation.

TypeSafe decides each click and keystroke; OpenAI only writes the field
values and reads the final page. The bot totals the token counts from both
runs (`nTypeSafeInputTokens`, `nOpenAiInputTokens`, `nOpenAiOutputTokens`),
which is the cost story for the demo. A run takes about 30 seconds.

**Token summary box.** The last step shows a message box with the steps, stop
reason and TypeSafe/OpenAI token counts for each FSD run and in total (the
same text is returned in the `sTokenSummary` output). The input
`nSummaryBoxSeconds` controls it: `0` (the default) keeps the box open until
someone clicks OK, which suits a live demo; any number above 0 closes the box
by itself after that many seconds, which suits an unattended run.

**Setup:** it reads two Credential Vault entries, so the Control Room needs
a locker named `MicahsLocker` containing credential `TypeSafe` (attribute
`API_KEY`) and credential `OpenAI` (attribute `OpenAI API Key`). Re-point
the two credential fields on the FSD actions if yours are named differently.

**Verify FSD's work.** FSD can report `done` when the goal was not met. In
testing, a login that SauceDemo refused (`locked_out_user`) still ended with
stop reason `done`, and in one run the answer extraction filled in plausible
but invented product data. So the bot does not take FSD's word: after each
FSD run it waits for a page element that only exists if the goal truly
succeeded (the checkout overview total, then the confirmation heading), and
fails the run if it is missing. Do the same in your own bots.

Inputs: `sUsername`, `sPassword`, `sSortDescription` (plain English, for
example `price from low to high`), `sShopperFirstName`, `sShopperLastName`,
`sPostalCode`, `nSummaryBoxSeconds`. Outputs: `bSuccess`, `sErrorMessage`, `sShopStopReason`,
`sFinishStopReason`, `sCheapestProduct`, `sCheapestPrice`, `sOrderTotal`,
`sConfirmation`, `sTokenSummary` and the three token counts.

## Third demo: the whole flow from one FSD prompt

`UniversalWebAutomation_SauceDemo_FSD_SinglePrompt_Demo_mac` (same folder, plus
its own zip) gives the FSD TypeSafe action **one** goal that covers everything:
log in, sort, add the first product, check out, click Finish, and stop on the
confirmation. It takes the same inputs as the two-goal version and ends with
the same token usage message box.

It trades some output for simplicity. FSD reads data only from the page it
finishes on, and SauceDemo's confirmation page shows just the thank-you
message, so this version returns `sConfirmation` but not the product, price
and order total that the two-goal version reads off the overview page. If
you need data from a page in the middle of the flow, split the work into
more than one goal.

In testing, four of four runs completed in 12 steps and about 33 seconds, using
roughly 5.4k to 8.8k TypeSafe input tokens and 5.7k to 7.6k OpenAI input tokens.
A run with a login SauceDemo refuses (`locked_out_user`) is reported as a
failure by the same check as the other bot: it waits for the confirmation
heading, since FSD itself still says `done`.

Outputs: `bSuccess`, `sErrorMessage`, `sStopReason`, `sStepsTaken`,
`sConfirmation`, `sTokenSummary` and the three token counts.
