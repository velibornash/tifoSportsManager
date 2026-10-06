/**
 * The hero Back button.
 *
 * <p>One button, one behaviour, and it is the behaviour every other page in this application has:
 * <b>go back to the previous screen.</b> It carries {@code data-nav-back="dashboard"}, which a
 * document-level listener in {@code pages.js} hands to {@code goBackSmart(fallback)}; that function pops
 * the navigation history when there is something to pop and only falls back to the dashboard when there
 * is not.
 *
 * <p>The Club profile writes this button by hand rather than calling a helper — see
 * {@code club-view.js} — so the two are the same button either way.
 *
 * <p><b>There was a second kind of button here, and it was wrong.</b> A short-lived
 * "always go to the dashboard" variant was added on 2026-10-06 because a forum section's Back was read
 * as going to the wrong place; it bypassed the history by design. The correction from the owner was to
 * copy what the Club section does instead of inventing a third behaviour, and that is what this file is
 * now: one button, standard behaviour, no special case.
 */
export function backButtonHtml(label = "Back", fallback = "dashboard", extraClass = "") {
    return `<button class="back-to-dashboard ${extraClass}" data-nav-back="${fallback}">${label}</button>`;
}