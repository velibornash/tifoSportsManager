import { authFetch, apiErrorMessage } from './auth.js';

/**
 * One place for "your result is here and you have not looked at it" (owner, 2026-09-29).
 *
 * <p>The matchday job fires at 19:00 whether or not anyone is watching, so a manager's result exists
 * the moment the job finishes. Showing it immediately makes "Watch your match" a formality and hands
 * the manager the season before he has decided to look at it. The dashboard had this UI; the club
 * schedule and the league results did not, which is why the same match showed a score in one place and
 * nothing in another.
 *
 * <p>Two buttons, because they are two different questions:
 *
 * - **Watch your match** opens the match in the viewer - you watch it happen rather than read about it.
 * - **Show results** reveals it and opens the match details on the goals.
 *
 * <p>Both reveal before navigating. A reveal with no navigation would leave the manager staring at a
 * score on a list, with the rest of the match still to be found.
 */
const VIEWER_PATH = '/demo/service/ui/proposal/index.html';

export async function revealMatch(matchId) {
    try {
        await authFetch(`/matches/${encodeURIComponent(matchId)}/reveal`, { method: 'POST' });
        return true;
    } catch (error) {
        // The reveal failing is not a reason to refuse to open the match. The manager asked for it, the
        // score is already computed, and the worst case is that the row stays masked until a reload -
        // which is a far smaller failure than a button that does nothing when pressed.
        console.warn(`Result reveal skipped for match ${matchId}:`, error);
        return false;
    }
}

export async function watchYourMatch(matchId) {
    await revealMatch(matchId);
    // **The server owns this now.** It hands back a recording that exists, rebuilding it when the
    // store has evicted it. The client used to guess: it took the replay id off the match row, which
    // the row still held long after the file had been evicted, and handed that number to the viewer
    // under the name "matchId". The viewer resolved it as a recording, found nothing, and then
    // resolved it as a match — and opened **another match's** recording. That is the shape of the
    // owner's report: "watch your match ne otvara dobar mec" — it does not open the right match.
    try {
        const res = await authFetch(`/api/sim/replay/${encodeURIComponent(matchId)}`, { method: 'POST' });
        const body = await res.json();
        window.location.href = `${VIEWER_PATH}?replayId=${encodeURIComponent(body.replayId)}`;
    } catch (error) {
        // The recording could not be produced. The server says why - the match may not be played, or
        // the store may still be building it - and the manager should read that sentence rather than
        // press a button that quietly does nothing.
        window.alert(apiErrorMessage(error, 'The match recording could not be opened.'));
    }
}

export async function showResults(matchId, loadMatch) {
    await revealMatch(matchId);
    if (typeof loadMatch === 'function') {
        await loadMatch(matchId, 'match', { initialTab: 'goals' });
        return;
    }
    window.location.reload();
}

/**
 * The two buttons, for a row whose result is hidden.
 *
 * <p><b>Only the match id is carried.</b> The replay id used to be carried too, and it was the source
 * of the whole defect: the row holds the id the recording had when it was made, the store evicts
 * recordings, and the row is never told. Handing that stale number to the viewer is what made it open
 * the wrong match. The server resolves the recording from the match id, and it knows whether the
 * recording still exists.
 */
export function hiddenResultActions(matchId) {
    return `
        <div class="fm-recent-match-actions js-hidden-result-actions">
            <button type="button" class="fm-action-btn js-watch-hidden-match"
                    data-match-id="${matchId}">Watch your match</button>
            <button type="button" class="fm-action-btn secondary js-open-hidden-report"
                    data-match-id="${matchId}">Show results</button>
        </div>`;
}

/**
 * Binds the buttons inside a container.
 *
 * <p>Delegated from the container and marked per button, because these lists are re-rendered wholesale
 * every time a page is drawn. A button that looks alive and does nothing is the exact shape of bug this
 * feature exists to remove, so a second bind must not stack a second handler.
 */
export function bindHiddenResultActions(container, loadMatch) {
    if (!container) return;
    container.querySelectorAll('.js-watch-hidden-match').forEach(button => {
        if (button.dataset.revealBound === '1') return;
        button.dataset.revealBound = '1';
        button.addEventListener('click', event => {
            event.preventDefault();
            event.stopPropagation();
            const matchId = Number(button.dataset.matchId);
            if (matchId) void watchYourMatch(matchId).catch(err => {
                console.warn('Watch your match failed:', err);
                alert(err.message || 'The match recording could not be opened.');
            });
        });
    });
    container.querySelectorAll('.js-open-hidden-report').forEach(button => {
        if (button.dataset.revealBound === '1') return;
        button.dataset.revealBound = '1';
        button.addEventListener('click', event => {
            event.preventDefault();
            event.stopPropagation();
            const matchId = Number(button.dataset.matchId);
            if (matchId) void showResults(matchId, loadMatch);
        });
    });
}

// The dashboard's own handlers predate this module; pointing them at it keeps one implementation.
window.TifoReveal = {
    revealMatch,
    watchYourMatch,
    showResults,
    hiddenResultActions,
    bindHiddenResultActions
};
