/**
 * The hero Back button, and the two ways it can behave.
 *
 * <p><b>{@link backButtonHtml} pops the navigation history.</b> It carries {@code data-nav-back}, which
 * a document-level listener in `pages.js` intercepts and hands to `goBackSmart(fallback)`. That function
 * <i>prefers the history stack</i> and only uses the fallback when there is nothing to pop. Which is
 * right for "back" everywhere else in this application: it takes you to where you actually came from.
 *
 * <p><b>{@link backToDashboardHtml} does not.</b> Owner instruction, 2026-10-06: opening a forum section
 * and pressing Back should land on the dashboard, not on the forum index. With the history-popping button
 * it went to the forum index, because the index was where it came from.
 *
 * <p>Same markup and same classes, so it looks identical — a deliberate difference in one attribute and
 * one handler, rather than a second button style nobody would recognise.
 *
 * <p><b>It calls {@code loadDashboard()}, not {@code loadPage('dashboard')}.</b> The router's switch has
 * no {@code dashboard} case: the dashboard is rendered by {@code loadDashboard}, so {@code loadPage}
 * falls through to "Page not found". That is exactly what happened first.
 */
export function backButtonHtml(label = "Back", fallback = "dashboard", extraClass = "") {
    return `<button class="back-to-dashboard ${extraClass}" data-nav-back="${fallback}">${label}</button>`;
}

/**
 * A Back button that always goes to the dashboard, ignoring where it was reached from.
 *
 * <p>Used by the forum's section and topic screens, where "the previous screen" is another screen of the
 * same feature and pressing Back between them is a click that goes nowhere useful. The Community tab is
 * one click from anywhere.
 */
export function backToDashboardHtml(label = "Back", extraClass = "") {
    return `<button type="button" class="back-to-dashboard ${extraClass}" onclick="loadDashboard()">${label}</button>`;
}