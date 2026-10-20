// pages/views/search-view.js
import { htmlEscape } from './utils.js';
import { apiErrorMessage } from '../../auth.js';

/**
 * Global search: managers, clubs and players, anywhere in the world.
 *
 * <p>Owner, 2026-10-14: *"Search mora biti globalan tool ... niti mora da se koristi samo za
 * prijateljske ... dugme sa lupica ikonicom posle dugmeta notification u headeru, kad se klikne otvori
 * se search stranica u kojoj mogu da se pretrazuju useri, klubovi ili igraci."*
 *
 * <p>It is a page rather than a dropdown on purpose. The friendly picker was a dropdown, and it was
 * wrong twice over: it was somewhere nobody looked for it, and it only searched clubs it was allowed to
 * ask for a friendly. A search over the world is something you stay on and keep typing into.
 */
export function createSearchView(deps) {
    const { authFetch, getTeamId } = deps;
    const myTeamId = () => (typeof getTeamId === 'function' ? getTeamId() : null);


    let timer = null;
    let kind = 'ALL';
    let bound = false;

    const KINDS = [
        { value: 'ALL', label: 'All' },
        { value: 'TEAM', label: 'Clubs' },
        { value: 'USER', label: 'Managers' },
        { value: 'PLAYER', label: 'Players' },
    ];

    const KIND_WORD = { TEAM: 'clubs', USER: 'managers', PLAYER: 'players' };

    async function run(box, term) {
        if (!term) {
            box.innerHTML = '<div class="fm-subtle">Type a club, a manager or a player.</div>';
            return;
        }
        box.innerHTML = '<div class="fm-subtle">Searching…</div>';
        const params = new URLSearchParams({ q: term, type: kind, limit: '40' });
        const response = await authFetch(`/api/search?${params.toString()}`);
        const rows = await response.json();
        if (!rows.length) {
            const where = kind === 'ALL' ? '' : ` in ${KIND_WORD[kind]}`;
            box.innerHTML = `<div class="fm-empty">Nothing matches “${htmlEscape(term)}”${where}.</div>`;
            return;
        }
        box.innerHTML = `
            <table class="fm-table fm-search-table">
                <thead><tr><th>What</th><th>Who</th><th>Detail</th></tr></thead>
                <tbody>
                    ${rows.map(r => `
                        <tr>
                            <td>${htmlEscape(r.type)}</td>
                            <td><button type="button" class="fm-link-btn js-open"
                                    data-open="${htmlEscape(r.openFor || '')}"
                                    data-open-arg="${htmlEscape(String(r.openArg != null ? r.openArg : ''))}"
                                    ${r.openFor ? '' : 'disabled'}>${htmlEscape(r.name)}</button>
                                    ${r.teamId && r.teamId !== myTeamId()
                                      ? `<button type="button" class="fm-action-btn primary js-invite" data-team="${htmlEscape(String(r.teamId))}">Invite to friendly</button>` : ''}
                                    <span class="fm-search-note" data-invite-note hidden></span>
                            </td>
                            <td class="fm-subtle">${htmlEscape(r.detail || '')}</td>
                        </tr>`).join('')}
                </tbody>
            </table>`;

        // Clicking what you found leads to what you found.
        box.querySelectorAll('.js-open').forEach(button => {
            button.addEventListener('click', () => {
                const where = button.dataset.open;
                const arg = button.dataset.openArg;
                if (!where || !arg) return;
                if (where === 'user') window.loadPage('publicUserProfile', { userId: arg });
                else if (where === 'player') window.loadPlayer(arg);
                else if (where === 'club') window.loadPage('clubProfile', { teamId: parseInt(arg, 10) });
                else if (where === 'league') window.openLeagueById(arg);
            });
        box.querySelectorAll('.js-invite').forEach(button => {
            // **The refusal is written into the row, not into a popup** (owner, 2026-10-20: "to treba da
            // se handla kroz ui a ne kroz pop up error"). A browser alert throws away the page he is
            // reading, and the row it belongs to; here the sentence sits next to the button it belongs
            // to, and the button stays usable so he can try another club.
            const note = button.parentElement.querySelector('[data-invite-note]');
            const say = text => {
                if (!note) return;
                note.textContent = text;
                note.hidden = !text;
            };
            button.addEventListener('click', async () => {
                const opponentId = Number(button.dataset.team);
                const me = myTeamId();
                if (!me || !opponentId) return;
                button.disabled = true;
                say('');
                try {
                    const slotResponse = await authFetch(`/api/season/friendlies/${me}/free-slot?opponentId=${opponentId}`);
                    const slot = await slotResponse.json();
                    if (!slot || !slot.week || !slot.slot) {
                        button.disabled = false;
                        say('No shared free slot exists this season.');
                        return;
                    }
                    await authFetch(`/api/season/friendlies/${me}/request?opponentId=${opponentId}&week=${slot.week}&slot=${slot.slot}`, { method: 'POST' });
                } catch (err) {
                    // The server refuses the invitation and says why - the round is full, the opponent
                    // cannot host. The sentence is shown rather than swallowed.
                    button.disabled = false;
                    say(apiErrorMessage(err, 'The request could not be sent.'));
                    return;
                }
                button.textContent = 'Invited';
                say('Invitation sent.');
            });
        });
        });
    }

    function render(term) {
        const main = document.getElementById('main-content');
        if (!main) return;
        main.innerHTML = `
            <div class="fm-page fm-page--search">
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <div class="fm-eyebrow">Search</div>
                            <h2>Find anything</h2>
                            <p class="fm-subtle">Every club, every manager and every player in the world,
                                in one box. Matches anywhere in the name, and a manager is found by his
                                name or his login.</p>
                        </div>
                    </div>
                    <div class="fm-segmented" data-search-type role="group" aria-label="What to search for">
                        ${KINDS.map(k => `<button type="button"
                            class="fm-segment${k.value === kind ? ' is-on' : ''}"
                            data-search-type-value="${k.value}">${k.label}</button>`).join('')}
                    </div>
                    <input type="text" class="fm-input fm-search-input" data-search-input
                           placeholder="Enter search term" autocomplete="off"
                           aria-label="Enter search term"
                           value="${htmlEscape(term || '')}" />
                    <div class="fm-search-results" data-search-results></div>
                </section>
            </div>`;

        const input = main.querySelector('[data-search-input]');
        const box = main.querySelector('[data-search-results]');

        main.querySelectorAll('[data-search-type-value]').forEach(segment => {
            segment.addEventListener('click', () => {
                kind = segment.dataset.searchTypeValue;
                main.querySelectorAll('[data-search-type-value]').forEach(other => {
                    other.classList.toggle('is-on', other === segment);
                });
                // Re-run at once: changing what you are looking for is a decision, not a half-typed word.
                clearTimeout(timer);
                run(box, input.value.trim());
            });
        });

        input.addEventListener('input', () => {
            clearTimeout(timer);
            timer = setTimeout(() => run(box, input.value.trim()), 250);
        });

        input.focus();
        run(box, term ? String(term).trim() : '');
        bound = true;
    }

    return {
        loadSearch(term) {
            render(term);
        },
    };
}
