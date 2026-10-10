// pages/views/match-tactic-plan-view.js
//
// The acting manager's conditional tactics for one fixture.
//
// Why this is a panel on the match screen and not a setting on the Tactics page: an instruction is about a
// specific game. "If we are two up, go conservative" is true of Saturday and false of the cup round in week
// three, and a single global list would have to be cleared by hand every time the fixture changed. It is
// keyed by fixture, which is also the key the engine reads it by.
//
// Why only your own club: the side is resolved on the server from the session, never from this screen. A
// `side` sent from here would be a manager writing the opposition's plan for them, and the engine would
// obey it.

import { htmlEscape } from './utils.js';

/** The owner's six conditions. The order here is the order the dropdown reads in. */
export const CONDITIONS = [
    { value: 'ALWAYS', label: 'Always' },
    { value: 'LEADING_BY_ONE', label: 'If leading by 1' },
    { value: 'LEADING_BY_THREE', label: 'If leading by 3 or more' },
    { value: 'DRAWING', label: 'If drawing' },
    { value: 'TRAILING_BY_ONE', label: 'If trailing by 1' },
    { value: 'TRAILING_BY_THREE', label: 'If trailing by 3 or more' }
];

/** Three instructions per club, and priority is the position they are tried in. */
export const SLOTS = [
    { priority: 1, label: 'First choice' },
    { priority: 2, label: 'Second choice' },
    { priority: 3, label: 'Third choice' }
];

/**
 * One instruction in plain words.
 *
 * <p>The same reasoning as the substitution rules: four dropdowns say nothing about what will happen at
 * minute 60, and this is the sentence a manager checks against their own intention.
 */
export function describeInstruction(assignment, tacticsById) {
    if (!assignment) return '';
    const tactic = tacticsById.get(String(assignment.tacticId));
    const name = tactic?.name || assignment.tacticName || 'a removed tactic';
    const minute = Number(assignment.minuteFrom) || 0;
    const condition = CONDITIONS.find(c => c.value === assignment.condition)?.label?.toLowerCase()
        ?? String(assignment.condition || 'always').toLowerCase();
    const when = minute > 0 ? `From minute ${minute}, ${condition}` : condition;
    return `${when}: play ${name}${tactic?.formation ? ` (${tactic.formation})` : ''}.`;
}

export function createMatchTacticPlanView(deps) {
    const { authFetch, getTeamId } = deps;

    async function loadPlan(fixtureId, host) {
        const teamId = getTeamId();
        const response = teamId
            ? await authFetch(`/api/sim/fixtures/${fixtureId}/tactic-plan`)
            : null;

        if (!response || !response.ok) {
            // Nothing is rendered on failure. A manager who does not play in this fixture has nothing to
            // set, and an empty panel would read as "you have no tactics", which is a different statement.
            return;
        }

        const plan = await response.json();

        // The server refuses when the club is not in the fixture or manages no club. Rendering nothing
        // is the honest answer: an empty panel reads as "you have no tactics", which is a different
        // statement and a wrong one on a fixture the manager is not even playing in.
        if (plan && plan.error) return;

        render(host, { fixtureId, plan });
    }

    function render(host, { fixtureId, plan }) {
        const available = plan.tactics || [];
        // A club with no tactics of its own cannot fill a slot, so it is not offered one. The form would
        // be three empty dropdowns and a Save that can only fail.
        const editable = plan.editable !== false && available.length > 0;
        const tacticsById = new Map(available.map(t => [String(t.id), t]));
        const byPriority = new Map((plan.assignments || []).map(a => [Number(a.priority), a]));

        host.innerHTML = `
            <section class="fm-panel">
                <div class="fm-panel-head"><div>
                    <h3>Game plan</h3>
                    <p class="fm-subtle">${htmlEscape(plan.teamName || '')} v ${htmlEscape(plan.opponentName || '')}.
                        ${editable
                            ? 'Up to three tactics. The first one that fits the situation is the one played.'
                            : '<strong>Closed</strong> — the team is already picked.'}</p>
                </div></div>

                ${available.length === 0
                    ? `<p class="fm-empty">Your club has no tactics yet. Build one on the Tactics page and it
                        will be offered here.</p>`
                    : ''}

                ${editable ? `
                <div class="fm-friendly-invite-form">
                    <div class="fm-subtle">Choose up to three tactics and when each applies</div>
                    <div class="fm-admin-toolbar">
                        ${SLOTS.map(slot => {
                            const current = byPriority.get(slot.priority);
                            return `
                            <fieldset class="fm-tactic-slot" data-tactic-slot="${slot.priority}">
                                <legend class="fm-subtle">${htmlEscape(slot.label)}</legend>
                                <label class="fm-subtle">Tactic
                                    <select class="fm-input" data-tactic-id>
                                        <option value="">— none —</option>
                                        ${available.map(t => `<option value="${t.id}"${
                                            current && String(current.tacticId) === String(t.id) ? ' selected' : ''
                                        }>${htmlEscape(t.name)} (${htmlEscape(t.formation)})</option>`).join('')}
                                    </select>
                                </label>
                                <label class="fm-subtle">When
                                    <select class="fm-input" data-tactic-condition>
                                        ${CONDITIONS.map(c => `<option value="${c.value}"${
                                            current && current.condition === c.value ? ' selected' : ''
                                        }>${c.label}</option>`).join('')}
                                    </select>
                                </label>
                                <label class="fm-subtle">From minute
                                    <input class="fm-input" type="number" min="0" max="90"
                                        data-tactic-minute value="${current ? Number(current.minuteFrom) || 0 : 0}">
                                </label>
                            </fieldset>`;
                        }).join('')}
                    </div>
                    <button type="button" class="fm-action-btn" data-tactic-save>Save game plan</button>
                    <p class="fm-subtle">Leave a slot empty to ignore it. A tactic named here is one the
                        engine can use; anything else falls back to your default.</p>
                </div>`
                    : (plan.assignments || []).length > 0 ? `
                    <ul class="fm-list">${SLOTS.map(slot => {
                        const current = byPriority.get(slot.priority);
                        return current ? `<li class="fm-substitution-rule">
                            <span>${htmlEscape(describeInstruction(current, tacticsById))}</span>
                        </li>` : '';
                    }).join('')}</ul>` : ''}
            </section>`;

        wire(host, fixtureId);
    }

    function wire(host, fixtureId) {
        const save = host.querySelector('[data-tactic-save]');
        if (!save) return;

        save.addEventListener('click', async () => {
            const slots = Array.from(host.querySelectorAll('[data-tactic-slot]'));
            for (const slot of slots) {
                const priority = Number(slot.dataset.tacticSlot);
                const tacticId = slot.querySelector('[data-tactic-id]').value;
                const condition = slot.querySelector('[data-tactic-condition]').value;
                const minute = Number(slot.querySelector('[data-tactic-minute]')?.value ?? 0);

                // An empty slot clears that priority rather than saving an instruction with no tactic —
                // which would be an instruction the engine could never use.
                if (!tacticId) {
                    await clearPriority(fixtureId, priority);
                    continue;
                }
                await saveSlot(fixtureId, {
                    priority,
                    tacticId: Number(tacticId),
                    condition,
                    minuteFrom: Math.min(90, Math.max(0, minute || 0))
                });
                if (!slot.dataset.saved) {
                    window.alert(slot.dataset.error || 'That tactic could not be saved.');
                    return;
                }
            }
            await loadPlan(fixtureId, host);
        });
    }

    async function saveSlot(fixtureId, body) {
        const response = await authFetch(`/api/sim/fixtures/${fixtureId}/tactic-plan`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const slot = document.querySelector(`[data-tactic-slot="${body.priority}"]`);
        if (slot) {
            slot.dataset.saved = response.ok ? '1' : '';
            if (!response.ok) slot.dataset.error = await reasonOf(response);
        }
    }

    async function clearPriority(fixtureId, priority) {
        await authFetch(`/api/sim/fixtures/${fixtureId}/tactic-plan?priority=${priority}`, {
            method: 'DELETE'
        });
    }

    async function reasonOf(response) {
        try {
            const body = await response.json();
            return (body && body.message) || `Could not save the plan (${response.status}).`;
        } catch {
            return `Could not save the plan (${response.status}).`;
        }
    }

    return { loadPlan };
}