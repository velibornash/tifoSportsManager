// pages/views/match-lineup-view.js
//
// The eleven a manager picks for one fixture.
//
// Why this is a panel on the match screen rather than a setting on the squad page: a team selection is
// about a specific game. The same eleven is not right for a cup tie and a derby, and one global order
// would have to be redone every time the fixture changed.
//
// WHY THIS SCREEN NOW LOOKS LIKE THE TACTICS PAGE (owner, 2026-10-16): it used to be three numbered
// lists — Starting eleven, Bench, Squad — with "Formation", "Save team", "Back to default eleven" and
// a paragraph about picking order. All four are gone. What is left is the same pitch and Selection
// Dock the tactics page draws, because both screens call `lineup-board.js`; two copies of a pitch
// drift apart, one component cannot.
//
// WHY THERE IS NO SAVE BUTTON. That instruction came with "remove Formation, Save team and Back to
// default eleven", and the save was the only thing writing the eleven. The owner's decision: the board
// saves itself, 600 ms after the last change. A screen that keeps the eleven only when you remember to
// press something is worse than one that always keeps it.

import { htmlEscape } from './utils.js';
import { createLineupBoard, formationToSlots, STARTERS } from './lineup-board.js';

export function createMatchLineupView(deps) {
    const { authFetch, getTeamId } = deps;

    let saveTimer = null;

    /** The fixture's `role`, as the four letters the board and the engine speak. */
    const ROLE_POSITION = {
        GOALKEEPER: 'GK', CENTRE_BACK: 'DEF', DEFENDER: 'DEF', SWEEPER: 'DEF', LEFT_BACK: 'DEF', RIGHT_BACK: 'DEF',
        CENTRE_MIDFIELDER: 'MID', DEFENSIVE_MIDFIELDER: 'MID', ATTACKING_MIDFIELDER: 'MID',
        WINGER: 'MID', PLAYMAKER: 'MID',
        STRIKER: 'ATT', FORWARD: 'ATT', CENTRE_FORWARD: 'ATT'
    };

    /**
     * The club's players, by id.
     *
     * <p>Two shapes have to be joined here. The fixture's own lineup names each man and says whether he
     * is injured or suspended, in a `role` ("CENTRE_BACK"), and carries neither a position nor a rating.
     * The board needs `position` ("DEF") to know who may fill a slot, and `overall` to print on the chip.
     * The fixture's own state always wins over the club's: a man injured today is injured today.
     */
    async function readSquadIndex(teamId) {
        try {
            const response = await authFetch(`/teams/${teamId}/players`);
            if (!response.ok) return new Map();
            const players = await response.json();
            const index = new Map();
            (Array.isArray(players) ? players : []).forEach(player => index.set(Number(player.id), player));
            return index;
        } catch {
            return new Map();
        }
    }

    async function loadPlan(fixtureId, host) {
        const teamId = getTeamId();
        const [response, squadIndex] = await Promise.all([
            teamId ? authFetch(`/api/sim/fixtures/${fixtureId}/lineup`) : Promise.resolve(null),
            teamId ? readSquadIndex(teamId) : Promise.resolve(new Map())
        ]);

        // Nothing rendered on failure: a manager not playing in this fixture has no team to pick, and an
        // empty panel would read as "you have no squad", which is a different and wrong statement.
        if (!response || !response.ok) return;

        const plan = await response.json();
        if (plan && plan.error) return;

        render(host, { fixtureId, plan, squadIndex });
    }

    function render(host, { fixtureId, plan, squadIndex }) {
        const editable = plan.editable !== false;
        const players = (plan.players || []).map(player => {
            const club = squadIndex.get(Number(player.id));
            return {
                ...player,
                position: club?.position || ROLE_POSITION[String(player.role || '').toUpperCase()] || 'MID',
                overall: club?.overall ?? null
            };
        });
        const warnings = plan.warnings || [];
        // The shape the fixture already carries. The old screen let a manager type one here; the board
        // draws the pitch from the fixture's own formation instead (owner, 2026-10-16).
        const formation = plan.formation || '4-4-2';
        const isMobile = window.matchMedia('(max-width: 768px)').matches;

        host.innerHTML = `
            <section class="fm-panel">
                <div class="fm-panel-head"><div>
                    <h3>Team selection</h3>
                    <p class="fm-subtle">${htmlEscape(plan.teamName || '')} v ${htmlEscape(plan.opponentName || '')}.
                        ${editable
                            ? (plan.perMatch
                                ? `Saved for this fixture, in ${htmlEscape(formation)}. Change anything and it saves itself.`
                                : `Your default eleven, in ${htmlEscape(formation)}. Changing it here saves it for this match only.`)
                            : (plan.lockReason
                                ? `<strong>Closed</strong> — ${htmlEscape(plan.lockReason)}`
                                : '<strong>Closed</strong> — the team has been picked.')}</p>
                    <p class="fm-subtle" data-lineup-save-state></p>
                </div></div>

                ${warnings.length ? `
                <div class="fm-tactic-warnings">
                    ${warnings.map(w => `<p class="fm-tactic-warning">${htmlEscape(w)}</p>`).join('')}
                </div>` : ''}

                <div data-lineup-board></div>
            </section>`;

        if (!editable) return;

        const roles = formationToSlots(formation).map(slot => slot.role);
        const board = createLineupBoard({
            players,
            formation,
            isMobile,
            onChange: selection => scheduleSave(host, fixtureId, selection, roles, formation)
        });
        board.setSelection(
            (plan.starters || []).map(Number),
            (plan.bench || []).map(Number)
        );
        board.render(host.querySelector('[data-lineup-board]'));
        setSaveState(host, plan.perMatch ? 'Saved for this fixture.' : 'Showing the club default eleven.');
    }

    function setSaveState(host, text) {
        const target = host.querySelector('[data-lineup-save-state]');
        if (target) target.textContent = text;
    }

    /**
     * Writes the eleven once the manager stops changing it.
     *
     * <p>Debounced, because a drag produces a drop and a phone produces a change per tap, and the whole
     * point of the change was that nothing has to be pressed. Failures are shown rather than swallowed:
     * a silently unsaved eleven is a manager discovering at kickoff that the screen lied.
     */
    function scheduleSave(host, fixtureId, selection, roles, formation) {
        setSaveState(host, 'Saving…');
        if (saveTimer) clearTimeout(saveTimer);
        saveTimer = setTimeout(async () => {
            saveTimer = null;
            const starters = selection.starterIds.filter(Boolean).map(Number);
            const bench = selection.benchIds.filter(Boolean).map(Number);
            if (starters.length !== STARTERS) {
                setSaveState(host, `Pick ${STARTERS - starters.length} more before this is saved.`);
                return;
            }
            try {
                const response = await authFetch(`/api/sim/fixtures/${fixtureId}/lineup`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        starters,
                        bench,
                        formation,
                        // The board draws the pitch from the formation, so slot N is the slot on the pitch
                        // and travels with the man standing in it. This is what the engine reads.
                        starterPositions: selection.starterIds.map((_, index) => roles[index] || 'MID')
                    })
                });
                if (!response.ok) {
                    let message = `Could not save the team (${response.status}).`;
                    try {
                        const body = await response.json();
                        if (body && body.message) message = body.message;
                    } catch { /* the body was not the JSON we expected */ }
                    setSaveState(host, message);
                    return;
                }
                setSaveState(host, 'Saved for this fixture.');
            } catch (err) {
                setSaveState(host, `Could not save the team (${err.message}).`);
            }
        }, 600);
    }

    return { loadPlan };
}