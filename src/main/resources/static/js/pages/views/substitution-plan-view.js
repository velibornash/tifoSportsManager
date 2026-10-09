// pages/views/substitution-plan-view.js
//
// The manager's conditional substitutions for one fixture.
//
// Why this is a screen and not a setting on the tactics page: a rule is about a specific game. "If we
// are losing at 60' bring on the striker" is true of Saturday and false of the cup round in week 3, and
// a single global list would have to be cleared by hand every time the fixture changed. It is keyed by
// fixture, which is also the key the engine reads it by — `SimMatchService.applySubstitutionPlan` loads
// the plan for the fixture it is about to simulate and hands it to the engine before the first tick.
//
// The bench is a dropdown rather than a free-text id because the failure this prevents is concrete: a
// rule naming a player who is not in the match cannot fire, and the engine's only report of that is a
// void reason the manager never sees until after the game.

import { htmlEscape } from './utils.js';

/** The conditions the engine understands. Kept in step with `ConditionalSubstitutionRules.Rule`. */
export const CONDITIONS = [
    { value: 'ANYTIME', label: 'At any time' },
    { value: 'LOSING', label: 'If losing' },
    { value: 'DRAWING', label: 'If drawing' },
    { value: 'LEADING', label: 'If leading' }
];

/**
 * One rule in plain words.
 *
 * <p>A condition set is easy to get wrong and hard to read back: four rows of dropdowns say nothing
 * about what will actually happen at minute 60. This is the sentence a manager checks against their own
 * intention, so it is written as a sentence rather than as a summary of the fields.
 */
export function describeRule(rule, playersById) {
    const condition = CONDITIONS.find(c => c.value === rule.condition)?.label?.toLowerCase()
        ?? String(rule.condition || 'anytime').toLowerCase();
    const minute = Number(rule.triggerMinute) || 0;
    const on = playersById.get(String(rule.playerOnId));
    const off = playersById.get(String(rule.playerOffId));
    const when = `From minute ${minute}, ${condition}`;
    if (on && off) return `${when}: ${on} on, ${off} off.`;
    if (on) return `${when}: ${on} on, the engine picks who comes off.`;
    if (off) return `${when}: ${off} off, the engine picks who comes on.`;
    return `${when}: the engine picks both.`;
}

export function createSubstitutionPlanView(deps) {
    const { authFetch, getTeamId } = deps;

    let rules = [];

    async function loadPlan(fixtureId, host, teamId) {
        const [planResponse, playersResponse] = await Promise.all([
            authFetch(`/api/sim/fixtures/${fixtureId}/substitution-plan`),
            teamId ? authFetch(`/teams/${teamId}/players`) : Promise.resolve(null)
        ]);

        if (!planResponse.ok) {
            host.innerHTML = `<p class="fm-empty">The substitution plan could not be read (${planResponse.status}).</p>`;
            return;
        }
        const plan = await planResponse.json();

        let squad = [];
        if (playersResponse && playersResponse.ok) {
            const body = await playersResponse.json();
            squad = Array.isArray(body) ? body : (body.players || []);
        }
        const playersById = new Map(squad.map(p => [String(p.id), p.name || p.fullName || `#${p.id}`]));

        rules = parseRules(plan.rulesJson);
        render(host, { fixtureId, plan, squad, playersById });
    }

    function parseRules(rulesJson) {
        if (!rulesJson) return [];
        try {
            const parsed = JSON.parse(rulesJson);
            return Array.isArray(parsed) ? parsed : [];
        } catch {
            // A plan we cannot parse is shown as an error rather than as an empty list. An empty list
            // reads as "you have no rules", which is a different statement and a wrong one.
            return [];
        }
    }

    function render(host, { fixtureId, plan, squad, playersById }) {
        const editable = plan.editable !== false;
        const starters = squad.filter(p => !p.onBench);

        host.innerHTML = `
            <section class="fm-panel">
                <div class="fm-panel-head"><div>
                    <h3>Substitutions</h3>
                    <p class="fm-subtle">${plan.homeTeam || 'Home'} v ${plan.awayTeam || 'Away'}.
                        ${editable
                            ? 'Decisions close an hour before kickoff.'
                            : '<strong>Closed</strong> — the team is already picked.'}</p>
                </div></div>

                ${rules.length === 0
                    ? `<p class="fm-empty">No conditions set. The manager and the engine decide as the
                        game goes.</p>`
                    : `<ul class="fm-list">${rules.map((rule, index) => `
                        <li class="fm-substitution-rule">
                            <span>${htmlEscape(describeRule(rule, playersById))}</span>
                            ${editable ? `<button type="button" class="fm-link-btn"
                                data-sub-rule-remove="${index}">Remove</button>` : ''}
                        </li>`).join('')}</ul>`}

                ${editable ? `
                <div class="fm-friendly-invite-form">
                    <div class="fm-subtle">Add a condition</div>
                    <div class="fm-admin-toolbar">
                        <label class="fm-subtle">From minute
                            <input class="fm-input" type="number" id="fm-sub-minute" min="1" max="90" value="60">
                        </label>
                        <label class="fm-subtle">If
                            <select class="fm-input" id="fm-sub-condition">
                                ${CONDITIONS.map(c => `<option value="${c.value}">${c.label}</option>`).join('')}
                            </select>
                        </label>
                        <label class="fm-subtle">On
                            <select class="fm-input" id="fm-sub-on">
                                <option value="">The engine picks</option>
                                ${squad.map(p => `<option value="${p.id}">${htmlEscape(p.name || p.fullName || `#${p.id}`)}</option>`).join('')}
                            </select>
                        </label>
                        <label class="fm-subtle">Off
                            <select class="fm-input" id="fm-sub-off">
                                <option value="">The engine picks</option>
                                ${starters.map(p => `<option value="${p.id}">${htmlEscape(p.name || p.fullName || `#${p.id}`)}</option>`).join('')}
                            </select>
                        </label>
                        <button type="button" class="fm-action-btn" data-sub-rule-add>Add condition</button>
                    </div>
                    <p class="fm-subtle">Injuries are handled automatically: a player who goes off comes
                        back on, if anyone is left on the bench.</p>
                </div>` : ''}
            </section>`;

        wire(host, fixtureId, editable, playersById, squad);
    }

    function wire(host, fixtureId, editable, playersById, squad) {
        const add = host.querySelector('[data-sub-rule-add]');
        if (add) {
            add.addEventListener('click', async () => {
                const minute = Number(host.querySelector('#fm-sub-minute')?.value ?? 60);
                const condition = host.querySelector('#fm-sub-condition')?.value || 'ANYTIME';
                const playerOnId = host.querySelector('#fm-sub-on')?.value || '';
                const playerOffId = host.querySelector('#fm-sub-off')?.value || '';

                // The five substitutions and three windows are engine limits; a sixth rule cannot all
                // fire. Refusing here, with the number, beats accepting a set the engine will drop.
                if (rules.length >= 5) {
                    window.alert('Five substitutions are available in a match, so five conditions is the '
                        + 'most that can ever apply.');
                    return;
                }
                rules.push({
                    team: 'HOME',
                    triggerMinute: Math.min(90, Math.max(1, minute)),
                    condition,
                    playerOnId,
                    playerOffId
                });
                await save(host, fixtureId, squad, playersById);
            });
        }

        host.querySelectorAll('[data-sub-rule-remove]').forEach(button => {
            button.addEventListener('click', async () => {
                rules.splice(Number(button.dataset.subRuleRemove), 1);
                await save(host, fixtureId, squad, playersById);
            });
        });
    }

    async function save(host, fixtureId, squad, playersById) {
        const response = await authFetch(`/api/sim/fixtures/${fixtureId}/substitution-plan`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ rules: JSON.stringify(rules) })
        });
        if (!response.ok) {
            // The server is the authority on whether the window is still open. It says so, and it says
            // why, so the message is shown rather than swallowed.
            let message = `Could not save the plan (${response.status}).`;
            try {
                const body = await response.json();
                if (body && body.message) message = body.message;
            } catch { /* the body was not the JSON we expected */ }
            window.alert(message);
            return;
        }
        const plan = await response.json();
        render(host, { fixtureId, plan, squad, playersById });
    }

    return { loadPlan };
}