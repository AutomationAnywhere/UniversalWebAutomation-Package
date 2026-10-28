package com.automationanywhere.botcommand.utils;

import com.google.gson.Gson;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What FSD TypeSafe knows about the current page: its visible controls, each named the way a
 * person would name it, plus the page's visible text.
 *
 * Names come from the element's real label first (aria-label, aria-labelledby, &lt;label for&gt;,
 * wrapping &lt;label&gt;), then placeholder, then visible text. Label-less guessing from
 * placeholder/id is why the old extractor needed per-site special cases. Controls inside a
 * table row or list item carry that row's text, so "Delete" in row 1 and "Delete" in row 2 are
 * distinguishable. The page text is included because the planner verifies its work from what
 * the page shows (a controls-only view once let an agent "verify" a report missing an entry).
 */
public class PageView {

    public enum Kind { TEXT, SELECT, CHECK, FILE, CLICK }

    public static class Control {
        public final Kind kind;
        public final String label;
        public final String value;
        public final List<String> options;
        public final String row;
        public final boolean secret;   // a password field: its value is never echoed anywhere
        public final Locator locator;

        Control(Kind kind, String label, String value, List<String> options, String row, boolean secret, Locator locator) {
            this.kind = kind;
            this.label = label == null ? "" : label;
            this.value = value == null ? "" : value;
            this.options = options == null ? List.of() : options;
            this.row = row == null ? "" : row;
            this.secret = secret;
            this.locator = locator;
        }

        public boolean isField() {
            return kind != Kind.CLICK;
        }

        /** One-line description used for TypeSafe criteria and the planner's view. Never includes secrets. */
        public String describe() {
            StringBuilder s = new StringBuilder(kind.name().toLowerCase()).append(" \"").append(label).append('"');
            if (!value.isEmpty()) s.append(" (current: ").append(value).append(')');
            if (!options.isEmpty()) s.append(" options: ").append(String.join(", ", options));
            if (!row.isEmpty()) s.append(" [in row: ").append(row).append(']');
            return s.toString();
        }
    }

    public final String url;
    public final String title;
    public final String text;
    public final List<Control> controls;
    public final int iframesSkipped;

    private PageView(String url, String title, String text, List<Control> controls, int iframesSkipped) {
        this.url = url;
        this.title = title;
        this.text = text;
        this.controls = controls;
        this.iframesSkipped = iframesSkipped;
    }

    public List<Control> fields() {
        List<Control> out = new ArrayList<>();
        for (Control c : controls) if (c.isField()) out.add(c);
        return out;
    }

    /**
     * A form waiting for input: an empty, editable text field (search boxes excluded). Pages
     * like this go to the planner, which fills every field at once; TypeSafe decides the rest.
     */
    public boolean hasFormToFill() {
        for (Control c : controls) {
            if (c.kind == Kind.TEXT && c.value.isEmpty() && !c.label.toLowerCase(java.util.Locale.ROOT).contains("search")) {
                return true;
            }
        }
        return false;
    }

    public List<Control> clickables() {
        List<Control> out = new ArrayList<>();
        for (Control c : controls) if (c.kind == Kind.CLICK || c.kind == Kind.CHECK) out.add(c);
        return out;
    }

    /** Compact page description for the planner: content first, then controls. */
    public String summary(int textLimit) {
        StringBuilder s = new StringBuilder();
        s.append("URL: ").append(url).append("\nTITLE: ").append(title).append("\n\nPAGE TEXT:\n");
        s.append(text.length() > textLimit ? text.substring(0, textLimit) + "\n..." : text);
        s.append("\n\nCONTROLS:\n");
        Set<String> seen = new LinkedHashSet<>();
        for (Control c : controls) seen.add("- " + c.describe());
        s.append(String.join("\n", seen));
        return s.toString();
    }

    /** Stable fingerprint of what's showing, for settle detection. */
    public static String fingerprint(Page page) {
        try {
            return page.url() + "|" + page.evaluate("() => (document.body && document.body.innerText || '').length + ':' +"
                    + " document.querySelectorAll('input,select,textarea,button,a').length + ':' +"
                    + " (document.body && document.body.innerText || '').slice(0, 4000)");
        } catch (Exception e) {
            return page.url() + "|error";
        }
    }

    /** Waits until the page stops changing (two identical polls) or maxMillis passes. */
    public static void settle(Page page, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        String last = null;
        int stable = 0;
        while (System.currentTimeMillis() < deadline && stable < 2) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            String cur = fingerprint(page);
            stable = cur.equals(last) ? stable + 1 : 0;
            last = cur;
        }
    }

    private static final String COLLECT_JS =
            "() => {" +
            "  const clean = s => (s || '').replace(/\\s+/g, ' ').trim();" +
            "  document.querySelectorAll('[data-fsd-id]').forEach(e => e.removeAttribute('data-fsd-id'));" +
            "  const visible = el => { const r = el.getBoundingClientRect(); const st = getComputedStyle(el);" +
            "    return r.width > 0 && r.height > 0 && st.visibility !== 'hidden' && st.display !== 'none'; };" +
            "  const labelOf = el => {" +
            "    let t = el.getAttribute('aria-label');" +
            "    if (!t && el.getAttribute('aria-labelledby')) t = el.getAttribute('aria-labelledby').split(/\\s+/)" +
            "        .map(id => (document.getElementById(id) || {}).innerText || '').join(' ');" +
            // Label elements are read with textContent: innerText applies CSS text-transform, which
            // turned 'Expense Date' into 'EXPENSE DATE' and broke exact matching.
            "    if (!t && el.labels && el.labels.length) t = el.labels[0].textContent;" +
            // <label for> also names a contenteditable <div>, which isn't 'labelable' so el.labels misses it.
            "    if (!t && el.id) { const l = document.querySelector('label[for=\"' + CSS.escape(el.id) + '\"]'); if (l) t = l.textContent; }" +
            "    if (!t && el.closest('label')) t = el.closest('label').textContent;" +
            // A visual label that isn't tied to its input: <div><label>First Name</label><input></div>.
            // Common on real sites (rpachallenge.com's inputs have random names and only this).
            "    if (!t && el.parentElement) { const p = el.parentElement;" +
            "      if (p.querySelectorAll('input, select, textarea, [contenteditable]').length === 1) {" +
            "        const l = p.querySelector('label'); if (l) t = l.textContent; } }" +
            "    if (!t) t = el.getAttribute('placeholder');" +
            // A hidden <input type=file> behind a drop zone: name it by the nearest container text.
            "    if (!t && el.type === 'file') { let p = el.parentElement;" +
            "      for (let i = 0; p && i < 4 && !clean(p.textContent); i++) p = p.parentElement;" +
            "      if (p) t = clean(p.textContent).slice(0, 60) || 'file upload'; }" +
            "    const tag = el.tagName;" +
            "    if (!t && tag !== 'SELECT' && tag !== 'TEXTAREA' && !(tag === 'INPUT' && !/^(submit|button|reset)$/.test(el.type)) && !el.isContentEditable) t = el.innerText;" +
            "    if (!t && tag === 'INPUT' && /^(submit|button|reset)$/.test(el.type)) t = el.value;" +
            "    if (!t) t = el.getAttribute('title') || el.getAttribute('data-testid') || el.getAttribute('data-test-id') ||" +
            "        el.getAttribute('data-qa') || el.getAttribute('data-cy') || el.getAttribute('ng-reflect-name') ||" +
            "        el.getAttribute('name') || el.id || '';" +
            "    return clean(t).replace(/\\s*\\*$/, '').slice(0, 80);" +
            "  };" +
            "  const rowOf = el => { const r = el.closest('tr, [role=row], li'); return r ? clean(r.innerText).slice(0, 120) : ''; };" +
            "  const textTypes = ['text','email','tel','password','number','search','url','date','datetime-local','month','week','time',''];" +
            "  const sel = 'input, textarea, select, button, a[href], summary, [role=button], [role=link], [role=menuitem], [role=tab],'" +
            "      + ' [role=checkbox], [role=radio], [role=option], [contenteditable=\"\"], [contenteditable=true], [onclick]';" +
            "  const out = []; let n = 0;" +
            "  for (const el of document.querySelectorAll(sel)) {" +
            "    if (out.length >= 150) break;" +
            "    const isFile = el.tagName === 'INPUT' && el.type === 'file';" +
            "    if (!isFile && !visible(el)) continue;" +
            "    if (el.getAttribute('aria-hidden') === 'true' || el.disabled) continue;" +
            "    let kind = 'CLICK', value = '', options = null;" +
            "    const type = (el.getAttribute('type') || '').toLowerCase();" +
            "    if (el.tagName === 'SELECT') { kind = 'SELECT'; options = [...el.options].map(o => clean(o.text)).slice(0, 40);" +
            "      value = el.selectedIndex >= 0 ? clean(el.options[el.selectedIndex].text) : ''; }" +
            "    else if (isFile) kind = 'FILE';" +
            "    else if (el.tagName === 'INPUT' && (type === 'checkbox' || type === 'radio')) { kind = 'CHECK'; value = el.checked ? 'checked' : ''; }" +
            "    else if (el.getAttribute('role') === 'checkbox' || el.getAttribute('role') === 'radio') { kind = 'CHECK'; value = el.getAttribute('aria-checked') === 'true' ? 'checked' : ''; }" +
            "    else if (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && textTypes.includes(type))) {" +
            "      kind = 'TEXT'; value = type === 'password' ? (el.value ? '[hidden]' : '') : clean(el.value).slice(0, 60); }" +
            "    else if (el.isContentEditable) { if (el.parentElement && el.parentElement.isContentEditable) continue; kind = 'TEXT'; value = clean(el.innerText).slice(0, 60); }" +
            // A clickable wrapper around a real button/link isn't a separate target.
            "    if (kind === 'CLICK' && el.hasAttribute('onclick') && el.querySelector('button, a[href], input')) continue;" +
            "    const label = labelOf(el);" +
            "    if (!label && kind === 'CLICK') continue;" +
            "    el.setAttribute('data-fsd-id', String(n));" +
            "    out.push({ id: n++, kind, label, value, options, row: rowOf(el), secret: type === 'password' });" +
            "  }" +
            "  const text = (document.body ? document.body.innerText : '').replace(/[ \\t]+/g, ' ').replace(/\\n{2,}/g, '\\n').trim();" +
            "  return JSON.stringify({ controls: out, text, title: document.title });" +
            "}";

    private static class Raw {
        List<RawControl> controls;
        String text;
        String title;
    }

    private static class RawControl {
        int id;
        String kind;
        String label;
        String value;
        List<String> options;
        String row;
        boolean secret;
    }

    private static final Gson GSON = new Gson();

    public static PageView read(Page page) {
        List<Control> controls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        String title = "";
        int skipped = 0;
        String mainOrigin = originOf(page.url());
        for (Frame frame : page.frames()) {
            if (frame != page.mainFrame()) {
                String origin = originOf(frame.url());
                if (origin == null || !origin.equals(mainOrigin)) {
                    skipped++;
                    continue;
                }
            }
            Raw raw;
            try {
                raw = GSON.fromJson(String.valueOf(frame.evaluate(COLLECT_JS)), Raw.class);
            } catch (Exception e) {
                if (frame == page.mainFrame()) throw e;
                skipped++;   // a subframe that detached mid-read
                continue;
            }
            if (frame == page.mainFrame()) title = raw.title;
            if (raw.text != null && !raw.text.isEmpty()) text.append(text.length() == 0 ? "" : "\n").append(raw.text);
            for (RawControl rc : raw.controls) {
                controls.add(new Control(Kind.valueOf(rc.kind), rc.label, rc.value, rc.options, rc.row, rc.secret,
                        frame.locator("[data-fsd-id='" + rc.id + "']")));
            }
        }
        return new PageView(page.url(), title == null ? "" : title, text.toString(), controls, skipped);
    }

    static String originOf(String url) {
        if (url == null) return null;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.equals("data") || scheme.equals("about")) return null;
            if (scheme.equals("file")) {
                String path = uri.getPath();
                int slash = path.lastIndexOf('/');
                return "file://" + (slash > 0 ? path.substring(0, slash) : "");
            }
            return scheme + "://" + uri.getAuthority();
        } catch (Exception e) {
            return null;
        }
    }
}
