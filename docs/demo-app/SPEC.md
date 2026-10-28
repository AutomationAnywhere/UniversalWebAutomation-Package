# TaskDeck — Demo App Spec (test ground for UniversalWebAutomation FSD)

## Why this exists

RPA Challenge (rpachallenge.com) is a bad first demo for FSD: it deliberately
shuffles field positions every submission, gives every input a meaningless
generated `id`/`name`, and auto-loops into a new round the instant you submit.
That's a genuinely hard benchmark (great for stress-testing the classifier),
but it's the wrong thing to show someone as "look, it can log in and click
around." It buries the core value prop — *"I don't know exactly where the
button is or what its element id is, but it says 'Submit' and it should get
clicked"* — under simultaneous multi-field ambiguity that has nothing to do
with that.

This spec is a small, realistic, **linear** app: log in, then do a handful of
obviously-named, one-thing-at-a-time operations. Nothing shuffles. Nothing
auto-resets. Every step has exactly one sensible next action. It's meant to
be the "does the basic thing work reliably" demo, with RPA Challenge staying
in the suite as the "does it hold up under adversarial conditions" stress test.

## Tech constraints (keep this trivial to build and host)

- **Plain HTML + CSS + vanilla JS. No backend, no build step, no framework.**
  All state lives in memory (a JS array) or `localStorage`; refreshing the
  page may reset to seed data, that's fine.
- Single HTML file is fine, or a handful of files if that's cleaner — either
  way it must be able to run by just opening `index.html` in a browser, or
  serving the folder with any static file server. No npm install, no server
  process required to test against it.
- **Do not add automation hooks** (`data-testid`, `data-qa`, etc.) and don't
  go out of your way to add `id`/`name` attributes beyond whatever's natural
  to write. Write it the way a competent but unremarkable developer would.
  The point is to test the package's existing label-inference chain
  (`aria-label` → `placeholder` → framework debug attrs → `innerText`/`value`
  → `name`/`id`) against ordinary, slightly-imperfect real-world markup — not
  to hand-hold it, and not to sabotage it either (that's what RPA Challenge is for).
- Every button and link needs real, human-readable visible text. That's the
  one hard requirement, because "click the button that says X" is the whole
  point of this demo.

## App concept: TaskDeck (a tiny task tracker)

### Screen 1 — Login

Fields:
- **Username** (text input)
- **Password** (password input)
- **Log In** button

Behavior:
- Accept any non-empty username with password `demo123` (hardcode it; this
  is a demo, not real auth). Wrong password shows an inline error message
  below the form: `"Incorrect password."` Empty username shows
  `"Username is required."`
- On success, navigate to the Dashboard (screen 2). No page reload needed —
  a client-side view swap is fine, but it should look and behave like a
  real navigation (URL fragment change or a full view swap, whichever's
  simplest to build).

### Screen 2 — Dashboard (task list)

Layout:
- A header showing the logged-in username (e.g. `"Welcome, tomsmith"`) and a
  **Log Out** button/link.
- A table or list of tasks, seeded with 3 example rows on load:

  | Title | Priority | Status |
  |---|---|---|
  | Renew office WiFi contract | Medium | Open |
  | Write Q3 board update | High | Open |
  | Archive old invoices | Low | Done |

  Each row has two action controls: a **Complete** button (only shown/enabled
  when status is `Open`) and a **Delete** button (always shown).
- A prominent **Add Task** button that reveals a form (inline section or a
  modal — either is fine, just make sure it's not a native `<dialog>`/browser
  `confirm()`/`prompt()`, since those aren't part of the page DOM Playwright
  automates the normal way).

### Add Task form

Fields:
- **Title** (text input, required)
- **Description** (a genuine `contenteditable` rich-text div — not a plain
  `<textarea>`. This is the one field that specifically exercises
  `SetRichText`/`InsertKeystrokes`, since those actions exist to handle
  exactly this kind of editor and need a realistic target to test against.)
- **Priority** (a real `<select>` with options `Low` / `Medium` / `High`)
- **Save Task** button, **Cancel** button

Behavior:
- On Save, validate Title is non-empty (inline error `"Title is required."`
  if not); otherwise close the form and add a new row to the table with
  status `Open`, using the entered Title/Priority (Description can just be
  stored, doesn't need to display in the table — maybe show it in a tooltip
  or an expandable row if you want, but that's optional polish).
- On Cancel, close the form and discard whatever was entered.

### Row actions

- **Complete**: flips that row's status to `Done`, hides/disables the
  Complete button for that row, no confirmation needed.
- **Delete**: removes the row immediately, no confirmation dialog (keep it
  simple — a confirm() would be outside the DOM automation surface anyway).
- After either action, briefly show a toast/banner message (e.g.
  `"Task marked complete."` / `"Task deleted."`) that disappears after a
  couple seconds or on next action. This gives the automation something to
  read back as confirmation, mirroring how `the-internet.herokuapp.com`'s
  "Secure Area" heading was used as a post-login verification target earlier
  in this project's own test suite.

### Log Out

- Returns to the Login screen. Any in-progress Add Task form state should be
  discarded. Task list changes (added/completed/deleted rows) don't need to
  persist across logout — resetting to the 3 seed rows on next login is fine.

## Suggested FSD test goals

These are the natural-language prompts to actually try against the package's
FSD actions once the app is built, roughly in increasing difficulty:

1. **Basic login** — `Log in with username "tomsmith" and password "demo123".`
2. **Single field-and-click** — `Click the Add Task button.`
3. **Multi-field form fill (the core "was RPA Challenge too much" test)** —
   `Click Add Task, then enter "Order new laptops" as the title, select High
   priority, and click Save Task.`
4. **Locate-by-text on a specific row** —
   `Mark the "Write Q3 board update" task as complete.`
5. **Destructive action by description** —
   `Delete the "Archive old invoices" task.`
6. **Rich text field** —
   `Add a task titled "Plan offsite" with the description "Need a venue,
   catering, and a date in October" and save it.`
7. **Read-back / extraction** —
   `List all tasks that are currently marked Open.` (tests `GetText`/table
   extraction, or FSD's schema-based `data` output)
8. **End-to-end, multi-step, explicit stop condition** —
   `Log in, add a task titled "Renew SSL certificate" with Medium priority,
   mark the "Renew office WiFi contract" task complete, then log out. Do
   this once, do not repeat.`

## Acceptance criteria (how you'll know it's built right)

- [ ] Opening the file/folder directly in a browser (no server) works.
- [ ] Wrong password and empty username both show their specific inline errors.
- [ ] Correct login navigates to the dashboard and shows the 3 seed rows.
- [ ] Add Task form's Description field is a `contenteditable` div, not a
      `<textarea>` — verify by inspecting the element.
- [ ] Saving a task with a title appears in the table with the right title
      and priority, status `Open`.
- [ ] Saving with an empty title shows the inline error and does not add a row.
- [ ] Complete and Delete both work per-row and show their toast message.
- [ ] No `data-testid`/`data-qa`/`data-cy` attributes anywhere in the markup.
- [ ] Every clickable control has visible, human-readable text (no icon-only
      buttons with no label).
- [ ] Logging out and back in resets the task list to the 3 seed rows.

## What this deliberately leaves out (and why)

- **No file uploads, drag-and-drop, or `<iframe>` content** — the package
  doesn't target those yet; keep the demo inside what it actually supports.
- **No native browser dialogs** (`confirm()`, `prompt()`, `alert()`) — those
  live outside the page DOM and Playwright handles them differently; a demo
  app for this package shouldn't require that extra handling.
- **No shuffled layout, no auto-repeating rounds, no meaningless generated
  ids** — that's what RPA Challenge is already covering. This app's whole
  purpose is to be the "yes, the basics work" demo, not another stress test.
