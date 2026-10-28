> **Superseded (2026-09-26).** This per-step design (TypeSafe chooses the next action type every
> step) was replaced: an OpenAI model now plans each page and TypeSafe only matches field labels and
> click phrases to controls. See `docs/fsd-benchmarks.md` for why and for measured results.

# TypeSafe FSD Action — Design Spec

Date: 2026-09-19
Status: Approved by user (pending final spec review)

## Background

This package (`UniversalWebAutomation`, an Automation Anywhere bot-command package built
on Playwright) already has two "Full Self Driving" actions:

- `FullSelfDriving` — one-shot: sends the entire page HTML/text to OpenAI, gets back raw
  JavaScript, executes it.
- `FullSelfDrivingMultiTurn` — loops a Planner/Actor/Validator trio of OpenAI calls per
  step, each sending a large page-context blob and getting back freehand JavaScript.

Both are slow (multi-second LLM round trips per step) and expensive (large HTML/text
payloads sent to a frontier model every turn), because every decision — however small —
goes through a full LLM call.

[TypeSafe](https://docs.typesafe.ai) is a classification/decision model (`jev-latest`,
endpoint `POST https://api.typesafe.ai/v1/systemone`) that answers structured `Choice`,
`Noul`, and `Score` questions over a text `state`, in ~150-400ms, at a fraction of the
cost of a frontier-model call, returning calibrated per-option probabilities and an
overall confidence score.

[typesafe-computer-use](https://github.com/awlevin/typesafe-computer-use) demonstrates
the pattern this design borrows: a step loop of **State → Decision → Act**, where the
"Decision" is one or more TypeSafe `Choice` questions over a small, deterministic,
mutually-exclusive action set (not a screenshot, not raw HTML), and a separate cheap
"writer" LLM is invoked only in the narrow moments that genuinely need free text
generation (composing a value to type, proposing a URL, and reading the final answer off
the screen once at the end). Everything else — perception, the actual click/type/scroll,
stop conditions — is deterministic code, not an LLM call.

This package doesn't need OCR or an accessibility-tree walk the way a macOS-native tool
does, because Playwright already has ground-truth DOM access. The adaptation here is:
replace the reference project's OCR+AX "items" with a deterministic DOM element walk,
keep the same State → Decision → Act loop shape, and reuse this package's existing
OpenAI integration as the writer.

## Goals

- A new action, `FullSelfDrivingTypeSafe`, matching the session-based, single-`@Execute`,
  internal-loop shape of `FullSelfDrivingMultiTurn` (one bot-command call drives a goal to
  completion or a stop condition — no per-step bot-visible actions).
- Each step's *decision* (what to do next) goes through TypeSafe, not an LLM.
- An optional OpenAI-backed "writer" is invoked only for: composing text for a `type_text`
  step, proposing a URL for a `navigate` step, and extracting the final answer against an
  optional output schema. Never for full-page code generation.
- Layered safety guardrails so the loop cannot run away, matching the reference project's
  stop rules plus one extra layer suited to unattended bot execution.
- Follows `AA_PACKAGE_BEST_PRACTICES.md` throughout (logging, error handling, credential
  handling, DictionaryValue envelope, no secrets in logs, etc).

## Non-goals

- No OS-level computer use (no screenshots, no OCR, no accessibility-tree walk, no
  cross-application control). Everything operates on the existing Playwright `Page`
  within an existing session.
- No new "site catalog" / AppleScript-style browser launching — this package already has
  a browser session; `navigate` just calls `page.navigate(url)`.
- Not a replacement for the two existing FSD actions — this is a third, complementary
  action for bot authors who want cheaper/faster step-by-step execution and are willing
  to accept a more constrained action vocabulary.

## Architecture & data flow

`FullSelfDrivingTypeSafe` is a session-based action (`@Sessions`, same
`PlaywrightConnection` lookup pattern as the other two FSD actions) with parameters:

- `sessionName` (TEXT, required)
- `TypeSafe API Key` (CREDENTIAL, required)
- `OpenAI API Key` (CREDENTIAL, optional — required only if the run actually needs
  `type_text`/`navigate`/final-answer-extraction; absence is not a hard failure up front)
- `request` (TEXTAREA, required) — the goal, plain English
- `outputFormatSchema` (TEXTAREA, optional)
- `maxSteps` (NUMBER, default 15, hard ceiling 50 regardless of input)
- `maxRunSeconds` (NUMBER, default 120, no upper ceiling — validated positive; a warning
  is logged, not an error, if set above 30 minutes)
- `minConfidence` (NUMBER, default 0.4)

Per step:

1. **Extract state** — deterministic Playwright DOM walk producing a numbered `PageItem`
   list (see "Item extraction" below).
2. **Ask TypeSafe** — one `POST /v1/systemone` call. `state` = compact serialized items +
   goal + recent action history (last 3 steps, action+result only, no full text). Two
   `Choice` questions: `action_type` and `target_item`.
3. **Execute deterministically** in Java/Playwright per the chosen `action_type` — no LLM
   in this path.
4. **Writer (OpenAI, optional)** — invoked only for `type_text` (compose the field value)
   and `navigate` (propose a URL, validated as clean https with a hostname before use).
5. **Stop-condition check** (see below). If not stopped, loop back to 1.
6. On stop, if `outputFormatSchema` was given, one final OpenAI call reads the last page
   state and extracts the answer — same schema-extraction convention the other two FSD
   actions already use.

## Action set & TypeSafe question schema

`action_type` — mutually exclusive `Choice`:

| action_type | Playwright execution |
|---|---|
| `click_item` | click the chosen item's locator |
| `type_text` | writer composes text → fill the chosen item |
| `select_option` | choose a dropdown option (reuses existing `SelectDropdown` logic); the value comes from the item's own enumerated option list, not the writer |
| `scroll_down` / `scroll_up` | `page.mouse.wheel(...)` |
| `press_enter` / `press_escape` | `page.keyboard.press(...)` |
| `wait` | short fixed sleep, for pages still loading |
| `navigate` | writer proposes a URL; validated https + hostname before use |
| `done` / `none` | stop the loop |

`target_item` — `Choice` over the numbered item list for the current step (rebuilt fresh
every step), each option a short string like `"button 'Submit' (bottom-right)"`. Capped
at TypeSafe's 255-option limit; items beyond the cap are dropped in reading order and
logged at `warn` (not an error — a page with that many interactive elements is unusual
and degraded coverage is an acceptable tradeoff over failing the run).

Two questions per step (not three, unlike the reference project's `action_type` /
`target_item` / `site` split) — there's no separate "site" concept here since we're
already inside one Playwright page; `navigate`'s destination comes from the writer
exactly like the reference project's `site: other` case.

## Item extraction

Extends the existing `collectDomHints`-style helper already present in
`FullSelfDrivingMultiTurn` into a proper `PageItem` list. For each
`input, textarea, select, button, a, [role=button], [contenteditable]` element that is
actually visible (bounding-box / `offsetParent` check — ground truth from the DOM, no
OCR/AX merge needed since Playwright already has this), capture: tag, type, name/id,
label/placeholder/innerText (truncated), and a position bucket (top-left / top-right /
etc. — same purpose as the reference project's position hints: keep `Choice` option
strings short and disambiguating). Deduplicated by identical label+role.

Iframes: same-origin iframes walked one level deep. Cross-origin iframes are skipped and
logged at `warn` — a known limitation, called out in the return payload
(`iframesSkipped` count) rather than silently missing elements.

## Stop conditions (all enforced; first to trigger wins)

1. **Hard step cap** — `maxSteps` (default 15), absolute ceiling 50 regardless of the
   param value passed in.
2. **Wall-clock timeout** — `maxRunSeconds` (default 120, bot-author-configurable with no
   upper ceiling for legitimately long tasks; validated positive, warned above 30 min).
3. **Confidence gate** — stop if TypeSafe's `action_type` confidence is below
   `minConfidence` (default 0.4).
4. **Consecutive no-op / stall detection** — stop after 2 steps in a row with no
   observable state change (`action_type: none`, or the post-action `PageItem` list is
   identical to the pre-action one).
5. **Explicit `done`/`none`** classification from TypeSafe.

Whichever fires is recorded as `stopReason`:
`step_limit | timeout | low_confidence | stalled | done`.

## Error handling

- TypeSafe HTTP calls: same `HttpClient` pattern as the existing OpenAI calls in this
  package. One retry with short backoff on transient 5xx/timeout. Non-transient errors
  (401, malformed response) abort immediately with a `BotCommandException` that names
  what actually failed (bad API key vs. malformed schema vs. network) — never a raw
  stack trace, per `AA_PACKAGE_BEST_PRACTICES.md` §7.
- Writer (OpenAI) failures on `type_text`/`navigate`: logged at `warn`, treated as a
  no-op for that step (counts toward the stall detector) rather than aborting the whole
  run.
- Per-step execution failures (stale/detached element, etc.): caught, logged, folded into
  the no-op/stall counter rather than thrown — TypeSafe's next classification over a
  freshly re-extracted item list is often self-correcting.
- Never log the TypeSafe or OpenAI API key. Never log full page HTML — only the compact
  item summaries, consistent with this package's existing logging conventions.

## Output shape

Same `DictionaryValue` envelope convention as the other two FSD actions:

- `status` ("OK" or "ERROR: ...")
- `numberOfSteps`
- `stopReason`
- `data` (only if `outputFormatSchema` was given)
- `iframesSkipped`
- Per-step trace: `step1`, `step2`, ... each a nested `DictionaryValue` with
  `actionType`, `target`, `confidence`, `result` — mirroring
  `FullSelfDrivingMultiTurn`'s `turn1`, `turn2` pattern for a familiar shape.

## Testing plan

TestNG, real Playwright pages served from local static HTML fixtures (no dependency on
live external sites), per this package's existing preference for integration tests over
mocks. Coverage, explicitly scoped to avoid tuning for a single happy path:

- Plain login-style form (click + type_text happy path)
- Page with a `<select>` dropdown (select_option path)
- Contenteditable/rich-text field (type_text on a non-standard input)
- Multi-page flow requiring `navigate`
- Page with >255 interactive elements (criteria-cap truncation path)
- Static page that never changes (forces the stall/no-op detector to fire)
- Deliberately slow-loading page (forces the `maxRunSeconds` timeout path)
- Page with a cross-origin iframe (confirms it's skipped and counted, not crashed)
- TypeSafe API error paths — a lightweight local HTTP stub returning 401 and 5xx, to
  verify retry-then-abort behavior with a clear message (can't reliably force TypeSafe's
  real backend into an error state)
- Missing OpenAI key but goal requires `type_text` → confirm graceful no-op, not a crash
- `maxSteps`/`maxRunSeconds` invalid input (0, negative) → confirm rejected with a clear
  message, not a silent infinite loop

## Open items for the implementation plan

- Exact TypeSafe request/response Java models (POJOs vs. manual JSON, matching this
  package's existing hand-rolled JSON string-building style in `FullSelfDriving`/
  `FullSelfDrivingMultiTurn`, or introducing a small JSON library — decide during
  planning based on what's already a dependency).
- Whether `select_option`'s enumerated option list needs its own TypeSafe question or can
  be resolved deterministically once `target_item` is known (likely the latter — no LLM
  needed to read a `<select>`'s own `<option>` values).
- Exact wording of the `.env` note: the `TYPESAFE_API_KEY` in the local `.env` file is for
  local development/testing of the HTTP client only; the shipped action takes the key via
  `CREDENTIAL`/`SecureString`, per §11 of the best-practices doc — `.env` is never read
  by the production code path.
