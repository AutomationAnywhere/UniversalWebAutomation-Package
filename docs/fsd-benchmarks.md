# FSD actions: benchmarks and lessons

**Date:** 2026-09-26

Both FSD actions were measured on two local demo apps and scored from each
app's saved state (localStorage), not from the action's `stopReason`. Before
this, the live tests only checked that a `stopReason` came back. That's how
FSD Multi-Turn could report `done` while the app showed nothing had happened.

- **TaskDeck** ([web-automation-demo-site](https://github.com/micahman33/web-automation-demo-site)):
  sign in, add a task (title, priority, rich-text description), complete it,
  delete every Low-priority task (fixed seed: 2 Low, 1 High, 1 Medium), sign
  out. 7 checks. `src/test/java/TestFsdTaskDeckBenchmark.java`.
- **Conquer** ([Conquer](https://github.com/micahman33/Conquer)), the expense
  demo's web part: sign in, create a report (dates, purpose, Executive Travel
  policy), add three expenses with category, amount and a PDF receipt each,
  leave the report in Draft, sign out. 15 checks.
  `src/test/java/TestFsdConquerBenchmark.java`.

Both tests are opt-in: they skip unless `OPENAI_API_KEY_TEST` (and
`TYPESAFE_API_KEY_TEST`, `FSD_BENCH_RECEIPTS_DIR` for Conquer) are set.

## Results (planner/actor model `gpt-5.4-mini` unless noted)

| | TaskDeck (7) | Conquer (15) | Time per run |
|---|---|---|---|
| FSD TypeSafe, before | 2 (stopped at step 1) | — | 0 s |
| FSD Multi-Turn, before | 2 (never logged in; reported `done`) | — | 79 s |
| **FSD TypeSafe, rebuilt** | **7, 7, 7, 7, 6** | **11, 15, 15** | **11–13 s / 33–71 s** |
| FSD TypeSafe, rebuilt, `gpt-6-luna` | — | **15, 15** | — / 64–85 s |
| FSD Multi-Turn, fixed | 7, 6, 6, 6, 6, 6, 6, 7, 2 | 1, 10 (of 13) | 79–181 s / 197–293 s |

"Before" scores of 2 are the two checks that pass on an untouched seed (the
High and Medium tasks weren't deleted, and nobody is signed in).

TypeSafe was called 0–2 times per run. Most labels and click phrases match
the page exactly and are resolved without it. It handles the fuzzy cases:
"New Report" when the page shows a sidebar link and a button, approximate
dropdown values, and label wording that differs from the goal.

Remaining misses are planner judgment, not browser mechanics: with
`gpt-5.4-mini` the planner occasionally skips one item (Amy's expense, a
Low task that was already Done). The done-audit catches most of these. The
two `gpt-6-luna` runs were perfect but slower.

## TypeSafe-first hybrid (inspired by typesafe-computer-use)

[typesafe-computer-use](https://github.com/awlevin/typesafe-computer-use) has TypeSafe decide
every step and calls an LLM only for free text or when the classifier stops. FSD TypeSafe now
works the same way on every page that has **nothing to fill**:
- TypeSafe gets one request each round: what kind of move (click / done / none) and which control.
- A click at 0.7 confidence or more runs with no LLM call.
- Anything else goes to the LLM planner: "none", lower confidence, or a click that changed nothing.
- A "done" is audited by the planner before the run stops.

Pages with a form to fill still go to the planner, which composes every value in one call.

Same benchmarks, gpt-5.4-mini, three runs each:

| | Checks | Time | OpenAI input tokens | TypeSafe input tokens | Rounds decided by TypeSafe |
|---|---|---|---|---|---|
| Conquer, planner-only | 11, 15, 15 of 15 | 33–71 s | 21–50K | 1–2K | 0 of 12–25 |
| **Conquer, hybrid** | **15, 15, 15** | 36–42 s | **15–17K** | 11K | **4 of 13** |
| TaskDeck, planner-only | 7, 6, 7 of 7 | 11–13 s | 5.5–7.5K | 0 | 0 |
| **TaskDeck, hybrid** | **7, 7, 7** | 15–16 s | **3.6K** | 4.7K | **5 of 8** |

At $1 per million OpenAI input tokens, a Conquer run costs about 2¢ instead of 3–7¢. TaskDeck is
about 3 s slower: a TypeSafe call plus a settle is slower than one batched planner step.

The rounds TypeSafe still hands over are the ambiguous ones. Its reason is recorded in the
`decisions` output:
- two different buttons that both work ("Add Expense" vs "Add First Expense" on an empty report)
- "sign out, or anything else left?" after the last item
- an account menu labelled only with the user's initials

**One fix mattered most.** At first TypeSafe decided only 1 round of 13. It was choosing the
right control at 0.36–0.64 confidence, because the app repeats it ("New Report" in the sidebar
and in the page). Offering each distinct control once took it to 4 of 13. This is the lesson
typesafe-computer-use's author states outright: stalls come from options that mean the same thing.

What was not copied:
- Per-field text composition. On a form-heavy task it means one LLM call per field instead of one
  per form.
- Its browser loop's action set. It has no dropdown or file-upload action.

## What was wrong, and the lesson behind each fix

| Lesson | FSD TypeSafe (before → after) | FSD Multi-Turn |
|---|---|---|
| TypeSafe is a bad planner and a good matcher | It asked TypeSafe "which action next?" every step and stopped on a 0.39-confidence guess. Now an LLM plans each page; TypeSafe only answers "which control is 'Priority'?" | n/a |
| Plan a whole page at once | An LLM call per field, guessing values from a label. Now one call fills a whole form and names its clicks. | Planner now fills and submits one form in one step (it used to save a form before setting its priority). |
| Name controls the way a person would | Labels came from placeholder, framework attributes and ids, so rpachallenge.com needed special cases. Now `<label for>`, wrapping and adjacent labels, aria and placeholder. `textContent`, not `innerText`, so CSS upper-casing doesn't break matching. | Page hints now include labels and links, visible elements only. |
| Use real input events | Playwright `fill`/`selectOption`/`setInputFiles`. | Plain `el.value = x` never reaches React state, so every login submitted empty. Now a `setValue()` helper. |
| Say "not here" instead of guessing | Every TypeSafe question has a `none` option; `none` or low confidence goes back to the planner as a PROBLEM line. | n/a |
| Act on the named row, never by position | "Delete in row 'X'" can only click X's button. | Generated `querySelectorAll('div').find(...)` matched a container and deleted a High task. Now a `rowFor()` helper and a prompt example. |
| Wait for the page to settle; re-find re-rendered elements | Settle before reading; re-find a control by label if the page rebuilt it (picking a category rebuilt Conquer's upload input). | Validator waits for the page to settle before judging. |
| Show page content, not just controls | The planner sees page text and table rows, so it can check its work. | n/a |
| Never trust "done" without an audit | On `done`, the planner must list each goal instruction with its proof. Reason comes before the verdict: when `done` came first, the model wrote "not done yet" in its reason and still said `done: true`. | Validator judges the page, not the script's `'OK'`; an outcome already achieved counts as success (the login page after "log out"). |
| Re-plans must not redo finished work | n/a | Re-plans restart at step 1 of the new plan (the turn number used to skip its first steps). They get the completed steps and treat the goal's criteria as binding: one re-plan told the actor to delete the High task. |
| Pages reset after the final action | "If HISTORY shows every part of the GOAL was done, you are done, even if the page looks like it could be done again." RPA Challenge now stops after one round instead of timing out at 91 actions. | n/a |

## Would MCP help?

No. MCP is only the transport between an agent and Playwright. This package
already drives Playwright directly, which avoids the problems the MCP route
had in Enterprise-Agent-Package:
- stdio reply mix-ups
- lost browser storage at shutdown
- about 6 s click waits

What helped there was Playwright's semantics: accessible names and real
input events. Those are now used here directly.
