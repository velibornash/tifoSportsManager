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

/**
 * The slots a starter can be put in: the engine's positions, not the player's own.
 *
 * <p>Set per starter and deliberately. A midfielder at full-back is a slot saying DEF while the man stays
 * MID - that is what `Lineup.starterPositions` is for and what the engine reads - so offering only the
 * player's own position is how a lineup ends up with eleven midfielders.
 */
export const SLOT_POSITIONS = [
    { value: 'GK', label: 'GK' },
    { value: 'DEF', label: 'DEF' },
    { value: 'MID', label: 'MID' },
    { value: 'ATT', label: 'ATT' },
    { value: 'WNG', label: 'WNG' },
];
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

    let state = { starters: [], bench: [], slotPositions: [] };
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
            bench: (plan.bench || []).map(Number),
            // What the server holds for this match. Empty on a club template or a lineup saved before the
            // column existed, and the row then falls back to the player's own position - which is the
            // documented fallback, not a blank.
            slotPositions: (plan.starterPositions || []).map(String)
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
                            : (plan.lockReason
                                ? `<strong>Closed</strong> — ${htmlEscape(plan.lockReason)}`
                                : '<strong>Closed</strong> — the team has been picked.')}</p>
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
            state.bench.map((id, i) => row(i + 1, byId.get(String(id)), 'bench')).join('');
        host.querySelector('[data-squad]').innerHTML =
            squad.map(p => available(p)).join('');

        const counter = host.querySelector('[data-starter-count]');
        if (counter) {
            counter.textContent = state.starters.length === STARTERS
                ? '— 11 picked'
                : `— ${STARTERS - state.starters.length} still to pick`;
        }
    }

    /**
     * One starter, with the two things the owner could not do with it.
     *
     * <p><b>Swap.</b> The order the eleven are picked in <em>is</em> the order they play in, and until now
     * the only way to change it was to remove everyone below and re-add them, which is a lot of clicking to
     * move one man up. Two arrows per row, and the first and last are disabled rather than hidden, so the
     * column does not reflow as a manager moves somebody along it.
     *
     * <p><b>A slot position.</b> Deliberately not the player's own position: a midfielder at full-back is a
     * slot saying DEF while the man stays MID, which is what `Lineup.starterPositions` is for and what the
     * engine reads.
     */
    function row(number, player, list = 'xi') {
        const id = player?.id ?? '';
        const slotPosition = state.slotPositions?.[number - 1] || player?.position || 'MID';
        const canUp = list === 'xi' ? number > 1 : number > 1;
        const canDown = list === 'xi'
            ? number < state.starters.length
            : number < state.bench.length;
        return `<li class="fm-lineup-row">
            <span class="fm-lineup-actions fm-lineup-actions--order">
                <button type="button" class="fm-link-btn" data-swap="${list}:${number - 1}:-1"
                    ${canUp ? '' : 'disabled'} title="Move up">&uarr;</button>
                <button type="button" class="fm-link-btn" data-swap="${list}:${number - 1}:1"
                    ${canDown ? '' : 'disabled'} title="Move down">&darr;</button>
            </span>
            <span class="fm-lineup-name">${htmlEscape(describePick(number, player, list === 'xi' ? 'xi' : 'bench'))}</span>
            ${list === 'xi' ? `<label class="fm-subtle fm-lineup-slot">
                <select class="fm-input fm-lineup-slot-select" data-slot-position="${number - 1}">
                    ${SLOT_POSITIONS.map(p => `<option value="${p.value}"
                        ${p.value === slotPosition ? 'selected' : ''}>${htmlEscape(p.label)}</option>`).join('')}
                </select>
            </label>` : ''}
            <button type="button" class="fm-link-btn" data-remove="${id}">Remove</button>
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
            const swap = event.target.closest('[data-swap]');
            if (swap) {
                // "list:index:delta" - the bench swaps within the bench and the eleven within the eleven,
                // because moving the last man on the bench into the side is a different act with a
                // different meaning, and the two buttons that do it already exist (XI / Bench).
                const [list, indexText, deltaText] = swap.dataset.swap.split(':');
                const index = Number(indexText);
                const target = index + Number(deltaText);
                const ids = list === 'bench' ? state.bench : state.starters;
                if (target < 0 || target >= ids.length) return;
                const moved = ids[index];
                ids.splice(index, 1);
                ids.splice(target, 0, moved);
                // The slot positions travel with the men. Moving XI 5 up must not leave XI 4 wearing
                // his position, which is the bug a naive swap of the ids alone produces.
                if (list !== 'bench' && state.slotPositions.length) {
                    const slot = state.slotPositions[index];
                    state.slotPositions.splice(index, 1);
                    state.slotPositions.splice(target, 0, slot);
                }
                redraw();
            } else if (addXi) {
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

        host.addEventListener('change', event => {
            const slot = event.target.closest('[data-slot-position]');
            if (!slot) return;
            const index = Number(slot.dataset.slotPosition);
            // Grow to the length of the eleven so a slot that had none can be set at all, rather than
            // writing into a hole and leaving undefined above it.
            while (state.slotPositions.length < state.starters.length) {
                state.slotPositions.push('');
            }
            state.slotPositions[index] = slot.value;
        });

        host.querySelector('[data-lineup-save]').addEventListener('click', async () => {
            const formation = host.querySelector('[data-lineup-formation]').value;
            const response = await authFetch(`/api/sim/fixtures/${fixtureId}/lineup`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    starters: state.starters,
                    bench: state.bench,
                    formation,
                    // Sent on every save rather than only when a slot was changed, so a save that follows
                    // a swap cannot drop the positions that travelled with the men.
                    starterPositions: state.slotPositions
                })
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