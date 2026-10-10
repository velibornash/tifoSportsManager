// pages/views/match-lineup-view.js
//
// The eleven a manager picks for one fixture.
//
// Why this is a panel on the match screen rather than a setting on the squad page: a team selection is
// about a specific game. The same eleven is not right for a cup tie and a derby, and one global order
// would have to be redone every time the fixture changed.
//
// Why the squad comes back with the view rather than being a second request: the manager needs to see who
// is unavailable *before* choosing. A list of eleven names and a warning the server sends after submitting
// is a warning read too late.

import { htmlEscape } from './utils.js';

/** Eleven is a law. Up to seven on the bench, because that is what the substitution rules can draw on. */
export const STARTERS = 11;
export const MAX_BENCH = 7;

/**
 * One player as the screen reads him, in a sentence.
 *
 * <p>The order the manager picked is the order they keep — the engine walks it that way — so the number is
 * not decoration.
 */
export function describePick(number, player, kind) {
    const state = [];
    if (player?.injured) state.push('injured');
    if (player?.suspended) state.push(player.suspensionReason || 'suspended');
    const name = player?.name || `#${player?.id ?? '?'}`;
    return `${kind === 'bench' ? 'Bench' : 'XI'} ${number}: ${name}${state.length ? ` — ${state.join(', ')}` : ''}`;
}

export function createMatchLineupView(deps) {
    const { authFetch, getTeamId } = deps;

    let state = { starters: [], bench: [] };
    let squad = [];

    async function loadPlan(fixtureId, host) {
        const teamId = getTeamId();
        const response = teamId ? await authFetch(`/api/sim/fixtures/${fixtureId}/lineup`) : null;

        // Nothing rendered on failure: a manager not playing in this fixture has no team to pick, and an
        // empty panel would read as "you have no squad", which is a different and wrong statement.
        if (!response || !response.ok) return;

        const plan = await response.json();
        if (plan && plan.error) return;

        squad = plan.players || [];
        state = {
            starters: (plan.starters || []).map(Number),
            bench: (plan.bench || []).map(Number)
        };
        render(host, { fixtureId, plan });
    }

    function render(host, { fixtureId, plan }) {
        const editable = plan.editable !== false;
        const players = plan.players || [];
        const byId = new Map(players.map(p => [String(p.id), p]));
        const warnings = plan.warnings || [];

        host.innerHTML = `
            <section class="fm-panel">
                <div class="fm-panel-head"><div>
                    <h3>Team selection</h3>
                    <p class="fm-subtle">${htmlEscape(plan.teamName || '')} v ${htmlEscape(plan.opponentName || '')}.
                        ${editable
                            ? (plan.perMatch
                                ? 'Saved for this fixture.'
                                : 'Showing your default eleven — save to pick a different one for this match.')
                            : '<strong>Closed</strong> — the team has been picked.'}</p>
                </div></div>

                ${warnings.length ? `
                <div class="fm-tactic-warnings">
                    ${warnings.map(w => `<p class="fm-tactic-warning">${htmlEscape(w)}</p>`).join('')}
                </div>` : ''}

                <div class="fm-lineup-picker" data-lineup-picker>
                    <div class="fm-lineup-column">
                        <h4>Starting eleven <span class="fm-subtle" data-starter-count></span></h4>
                        <ol class="fm-lineup-list" data-starters></ol>
                    </div>
                    <div class="fm-lineup-column">
                        <h4>Bench</h4>
                        <ol class="fm-lineup-list" data-bench></ol>
                    </div>
                    <div class="fm-lineup-column fm-lineup-squad">
                        <h4>Squad</h4>
                        <ul class="fm-lineup-list" data-squad></ul>
                    </div>
                </div>

                ${editable ? `
                <div class="fm-friendly-invite-form">
                    <label class="fm-subtle">Formation
                        <input class="fm-input" data-lineup-formation maxlength="12"
                               value="${htmlEscape(plan.formation || '4-4-2')}">
                    </label>
                    <button type="button" class="fm-action-btn" data-lineup-save>Save team</button>
                    <button type="button" class="fm-link-btn" data-lineup-clear>Back to default eleven</button>
                    <p class="fm-subtle">The order you pick is the order they play in. Leave someone out and
                        the club's default eleven applies for this match.</p>
                </div>` : ''}
            </section>`;

        drawLists(host, byId);
        if (editable) wire(host, fixtureId);
    }

    function drawLists(host, byId) {
        host.querySelector('[data-starters]').innerHTML =
            state.starters.map((id, i) => row(i + 1, byId.get(String(id)))).join('');
        host.querySelector('[data-bench]').innerHTML =
            state.bench.map((id, i) => row(i + 1, byId.get(String(id)))).join('');
        host.querySelector('[data-squad]').innerHTML =
            squad.map(p => available(p)).join('');

        const counter = host.querySelector('[data-starter-count]');
        if (counter) {
            counter.textContent = state.starters.length === STARTERS
                ? '— 11 picked'
                : `— ${STARTERS - state.starters.length} still to pick`;
        }
    }

    function row(number, player) {
        return `<li class="fm-lineup-row">
            <span class="fm-lineup-name">${htmlEscape(describePick(number, player, 'xi'))}</span>
            <button type="button" class="fm-link-btn" data-remove="${player?.id ?? ''}">Remove</button>
        </li>`;
    }

    function available(player) {
        const taken = state.starters.includes(player.id) || state.bench.includes(player.id);
        const blocked = player.injured || player.suspended;
        return `<li class="fm-lineup-row fm-lineup-row--${blocked ? 'unavailable' : 'available'}${taken ? ' taken' : ''}">
            <span class="fm-lineup-name">${htmlEscape(player.name || `#${player.id}`)}
                ${player.suspended ? `<em> — ${htmlEscape(player.suspensionReason || 'suspended')}</em>` : ''}
                ${player.injured ? '<em> — injured</em>' : ''}</span>
            <span class="fm-lineup-actions">
                ${!taken ? `<button type="button" class="fm-link-btn" data-add-xi="${player.id}">XI</button>` : ''}
                ${!taken ? `<button type="button" class="fm-link-btn" data-add-bench="${player.id}">Bench</button>` : ''}
            </span>
        </li>`;
    }

    function wire(host, fixtureId) {
        const byId = () => new Map(squad.map(p => [String(p.id), p]));
        const redraw = () => drawLists(host, byId());

        host.addEventListener('click', event => {
            const addXi = event.target.closest('[data-add-xi]');
            const addBench = event.target.closest('[data-add-bench]');
            const remove = event.target.closest('[data-remove]');
            if (addXi) {
                const id = Number(addXi.dataset.addXi);
                state.starters.push(id);
                state.bench = state.bench.filter(b => b !== id);
                redraw();
            } else if (addBench) {
                const id = Number(addBench.dataset.addBench);
                if (state.bench.length >= MAX_BENCH) {
                    window.alert(`The bench is at most ${MAX_BENCH} players.`);
                    return;
                }
                state.bench.push(id);
                state.starters = state.starters.filter(s => s !== id);
                redraw();
            } else if (remove) {
                const id = Number(remove.dataset.remove);
                state.starters = state.starters.filter(s => s !== id);
                state.bench = state.bench.filter(b => b !== id);
                redraw();
            }
        });

        host.querySelector('[data-lineup-save]').addEventListener('click', async () => {
            const formation = host.querySelector('[data-lineup-formation]').value;
            const response = await authFetch(`/api/sim/fixtures/${fixtureId}/lineup`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ starters: state.starters, bench: state.bench, formation })
            });
            if (!response.ok) {
                // The server refuses a selection that cannot field a team, and says how many it got. Shown
                // rather than swallowed, because the alternative is a button that appears to do nothing.
                let message = `Could not save the team (${response.status}).`;
                try {
                    const body = await response.json();
                    if (body && body.message) message = body.message;
                } catch { /* the body was not the JSON we expected */ }
                window.alert(message);
                return;
            }
            await loadPlan(fixtureId, host);
        });

        host.querySelector('[data-lineup-clear]').addEventListener('click', async () => {
            await authFetch(`/api/sim/fixtures/${fixtureId}/lineup`, { method: 'DELETE' });
            await loadPlan(fixtureId, host);
        });
    }

    return { loadPlan };
}