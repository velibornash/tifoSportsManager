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
 * Why a condition could not happen, in the manager's words.
 *
 * <p>Keyed by the engine's `VoidReason`. The engine has written these since the rules existed and nothing
 * ever read them back, so a condition could die mid-match and the manager never found out — which is the
 * whole reason the outcome is on the screen now.
 *
 * <p>`PLAYER_ALREADY_ON_PITCH` is the one whose name lies: the engine sets it when the player named to
 * come <em>off</em> could not be found on the pitch (`fire`, when `resolvePlayerOff` returns null), so it
 * is described by what actually happened rather than by what the constant is called.
 */
const VOID_REASONS = {
    NO_SUBS_LEFT: 'the five substitutions were already used',
    NO_WINDOWS_LEFT: 'the three substitution windows were already used',
    PLAYER_UNAVAILABLE: 'the player named to come on was not available',
    PLAYER_ALREADY_ON: 'the player named to come on was already on the pitch',
    PLAYER_ALREADY_ON_PITCH: 'the player named to come off was no longer on the pitch',
    EMPTY_BENCH: 'nobody was left on the bench'
};

/** What identifies a rule well enough to line its outcome up with it. */
function ruleSignature(rule) {
    return [
        String(rule?.team ?? ''),
        Number(rule?.triggerMinute) || 0,
        String(rule?.condition ?? 'ANYTIME'),
        String(rule?.playerOnId ?? ''),
        String(rule?.playerOffId ?? '')
    ].join('|');
}

/**
 * Lines each rule up with the outcome the engine recorded for it.
 *
 * <p>By content, not by position. The engine keeps the order the rules were written in, so position would
 * usually do — but a rule that failed to parse is skipped when the outcome is built, which shifts every
 * later row by one and would report the wrong fate for the wrong condition. Matching on what the rule
 * <em>is</em> cannot shift. Falls back to null when there is genuinely no outcome for a rule, which is the
 * honest answer for a plan written after the match was played.
 *
 * @returns {(object|null)[]} one entry per rule, in rule order
 */
export function matchOutcomes(rules, outcomes) {
    const queues = new Map();
    (Array.isArray(outcomes) ? outcomes : []).forEach(outcome => {
        const signature = ruleSignature(outcome);
        if (!queues.has(signature)) queues.set(signature, []);
        queues.get(signature).push(outcome);
    });
    return (Array.isArray(rules) ? rules : []).map(rule => {
        const queue = queues.get(ruleSignature(rule));
        return queue && queue.length > 0 ? queue.shift() : null;
    });
}

/**
 * One rule's fate at the final whistle, as a badge.
 *
 * <p>`PENDING` is the common case and is not a failure — "if we are losing from 60" on a game won 3–0 was
 * a correct instruction that never came due. It is worded so it cannot be misread as a broken rule.
 *
 * @returns {{tone: string, label: string}|null} null when the rule has no recorded outcome
 */
export function describeOutcome(outcome) {
    if (!outcome) return null;
    const status = String(outcome.status || '');
    const firedAt = Number(outcome.firedAtMinute);

    if (status === 'FIRED') {
        const hasMinute = Number.isFinite(firedAt) && firedAt > 0;
        return { tone: 'good', label: hasMinute ? `Fired at ${firedAt}'` : 'Fired' };
    }
    if (status === 'VOID') {
        const reason = VOID_REASONS[outcome.voidReason];
        return { tone: 'bad', label: reason ? `Void — ${reason}` : 'Void' };
    }
    if (status === 'WAITING_FOR_STOPPAGE') {
        return { tone: 'flat', label: 'Never happened — the window closed on it' };
    }
    return { tone: 'flat', label: 'The condition never came up' };
}

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

    function parseOutcome(outcomeJson) {
        if (!outcomeJson) return [];
        try {
            const parsed = JSON.parse(outcomeJson);
            return Array.isArray(parsed) ? parsed : [];
        } catch {
            return [];
        }
    }

    function render(host, { fixtureId, plan, squad, playersById }) {
        const editable = plan.editable !== false;
        const starters = squad.filter(p => !p.onBench);
        const outcomes = matchOutcomes(rules, parseOutcome(plan.outcomeJson));
        // `some`, not `length > 0`. The matched array always has one slot per rule — filled with null
        // when there is no record — so its length says nothing about whether the match has been played.
        const played = outcomes.some(Boolean);

        host.innerHTML = `
            <section class="fm-panel">
                <div class="fm-panel-head"><div>
                    <h3>Substitutions</h3>
                    <p class="fm-subtle">${plan.homeTeam || 'Home'} v ${plan.awayTeam || 'Away'}.
                        ${played
                            ? 'How each condition went at the final whistle.'
                            : editable
                                ? 'Decisions close an hour before kickoff.'
                                : '<strong>Closed</strong> — the team is already picked.'}</p>
                </div></div>

                ${rules.length === 0
                    ? `<p class="fm-empty">No conditions set. The manager and the engine decide as the
                        game goes.</p>`
                    : `<ul class="fm-list">${rules.map((rule, index) => {
                        const outcome = describeOutcome(outcomes[index]);
                        return `
                        <li class="fm-substitution-rule">
                            <span>${htmlEscape(describeRule(rule, playersById))}</span>
                            ${outcome ? `<span class="fm-badge ${outcomeBadgeClass(outcome.tone)}"
                                title="${htmlEscape(outcome.label)}">${htmlEscape(outcome.label)}</span>` : ''}
                            ${editable ? `<button type="button" class="fm-link-btn"
                                data-sub-rule-remove="${index}">Remove</button>` : ''}
                        </li>`;
                    }).join('')}</ul>`}

                ${played ? `
                <p class="fm-subtle">A condition that fired is one the engine used. One that was void
                    could not happen — the reason is on the badge.</p>` : ''}

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

    /** Reuses the palette the rest of the app already uses rather than inventing three new colours. */
    function outcomeBadgeClass(tone) {
        if (tone === 'good') return 'fm-badge-fit';
        if (tone === 'bad') return 'fm-badge-inj';
        return 'fm-badge-cold';
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