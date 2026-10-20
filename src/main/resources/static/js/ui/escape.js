/**
 * HTML escaping, in one place.
 *
 * <p>There were five identical copies of this function across the frontend, differing only in quote
 * style — `pages.js`, `dashboard.js`, `demo.js`, `tifo.js` and `roundResultsTeletext.js`. Every
 * additional copy is a place for a fix to be applied to four implementations and missed in the fifth,
 * and an escaping bug is not the kind of thing a test suite catches late: it shows up as broken
 * markup, or as a script injected through a club name.
 *
 * <p>Escapes the five characters that matter in both element text and quoted attribute values. The
 * apostrophe is included because the attribute values it guards are routinely written in single
 * quotes.
 *
 * <p>Null and undefined become the empty string rather than the words "null" or "undefined", which is
 * what every one of the five copies did and what callers here depend on: a missing value should leave
 * a gap in the markup, not print a word in the UI.
 */
export function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
