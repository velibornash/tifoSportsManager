// Loads the real match-tactic-plan-view module and drives loadPlan against a stubbed fetch layer.
//
// This is the same level of check that was missing when the substitution plan turned out to be unreachable:
// the module and its controller were both tested and the feature still had no path from a manager's click
// to a rendered screen. Here the module renders, and the companion check in
// route-to-substitution-plan.mjs's style is the mounting in match-view.js.
import { readFileSync } from 'node:fs';

const dir = 'src/main/resources/static/js/pages/views';
const strip = (src) => src
    .replace(/^import[^;]*;$/gm, '')
    .replace(/^export /gm, '');
const escape = strip(readFileSync('src/main/resources/static/js/ui/escape.js', 'utf8'));
const utils = strip(readFileSync(`${dir}/utils.js`, 'utf8'));
const view = strip(readFileSync(`${dir}/match-tactic-plan-view.js`, 'utf8'));
const matchView = strip(readFileSync(`${dir}/match-view.js`, 'utf8'));

const failures = [];
const check = (name, condition, detail) => {
    if (condition) console.log(`ok   ${name}`);
    else { console.log(`FAIL ${name} — ${detail}`); failures.push(name); }
};

// ── the mounting, which is the part that was silently missing once ────────────────────────────────────
{
    check('the match view carries a host for the game plan',
        matchView.includes('id="fm-match-tactic-plan"'),
        'match-view.js has no #fm-match-tactic-plan, so there is nowhere to mount it');

    check('the mount is called',
        /void mountTacticPlan\(\)/.test(matchView),
        'mountTacticPlan is defined but never called — the way the substitution plan died on fixture-view');

    check('the screen never sends a side',
        !/\bside\s*:\s*['"]HOME['"]/.test(view) && !/\bside\s*:\s*['"]AWAY['"]/.test(view),
        'the screen must not choose a side; the server resolves it from the session, because a side sent '
            + 'from here would let a manager write the opposition\'s plan');

    check('the mount needs the manager to have a club',
        /if \(!managerTeamId\) return;/.test(matchView),
        'without this the panel is attempted for an account that manages no club');
}

// ── the rendering ────────────────────────────────────────────────────────────────────────────────────
const sandbox = `
    ${escape}
    ${utils}
    ${view}
    globalThis.__factory = createMatchTacticPlanView;
`;
const factory = new Function(sandbox + '\nreturn __factory;')();
globalThis.window = { alert: () => {}, confirm: () => true };

function host() {
    return { innerHTML: '', querySelector: () => null, querySelectorAll: () => [] };
}

const PLAN = {
    fixtureId: 16,
    side: 'HOME',
    teamName: 'OFK Omladinac',
    opponentName: 'RFK Smederevo',
    editable: true,
    tactics: [
        { id: 11, name: 'Derby 4-4-2', formation: '4-4-2', isDefault: true },
        { id: 12, name: 'Cup 4-3-3', formation: '4-3-3', isDefault: false }
    ],
    assignments: [
        { priority: 1, tacticId: 12, condition: 'LEADING_BY_THREE', minuteFrom: 0 },
        { priority: 3, tacticId: 11, condition: 'TRAILING_BY_ONE', minuteFrom: 60 }
    ]
};

{
    const asked = [];
    const view = factory({
        authFetch: async (url) => {
            asked.push(url);
            return { ok: true, status: 200, json: async () => PLAN };
        },
        getTeamId: () => 1
    });
    const h = host();
    await view.loadPlan(16, h);

    check('the screen reads the fixture route',
        asked[0] === '/api/sim/fixtures/16/tactic-plan', `asked for ${asked[0]}`);
    check('the panel renders', h.innerHTML.includes('Game plan'), h.innerHTML.slice(0, 200));
    check('all six conditions are offered',
        ['ALWAYS', 'LEADING_BY_ONE', 'LEADING_BY_THREE', 'DRAWING', 'TRAILING_BY_ONE', 'TRAILING_BY_THREE']
            .every(c => h.innerHTML.includes(`value="${c}"`)),
        'the dropdown is missing a condition the engine understands');
    check('three slots are offered',
        h.innerHTML.includes('data-tactic-slot="1"')
            && h.innerHTML.includes('data-tactic-slot="2"')
            && h.innerHTML.includes('data-tactic-slot="3"'),
        'the club can set three instructions and the screen offers fewer');
    check('the tactics the manager owns are offered',
        h.innerHTML.includes('Derby 4-4-2') && h.innerHTML.includes('Cup 4-3-3'),
        'the screen did not offer the club\'s own tactics');
    check('a saved instruction comes back selected',
        h.innerHTML.includes('value="12" selected'),
        'the instruction already set was not shown as chosen, so the manager would overwrite it blind');
    check('its minute comes back too',
        h.innerHTML.includes('value="60"'),
        'the minute gate was not shown back');
}

// ── a club with no tactics is told why, not shown an empty form ──────────────────────────────────────
{
    const view = factory({
        authFetch: async () => ({ ok: true, status: 200, json: async () => ({ ...PLAN, tactics: [], assignments: [] }) }),
        getTeamId: () => 1
    });
    const h = host();
    await view.loadPlan(16, h);

    check('a club with no tactics is pointed at the Tactics page',
        h.innerHTML.includes('no tactics yet'),
        'an empty dropdown list would read as a broken feature rather than an unfinished club');
    check('and is not offered a form that cannot be filled',
        !h.innerHTML.includes('data-tactic-save'),
        'the save button is offered for a club with nothing to save');
}

// ── an account that does not play here renders nothing at all ───────────────────────────────────────
{
    const view = factory({
        authFetch: async () => ({ ok: true, status: 200, json: async () => ({ ...PLAN, error: 'NOT_IN_THIS_FIXTURE' }) }),
        getTeamId: () => 1
    });
    const h = host();
    await view.loadPlan(16, h);

    check('a club not in the fixture renders nothing',
        h.innerHTML === '',
        `an empty panel reads as "you have no tactics", which is a different statement: ${h.innerHTML.slice(0, 120)}`);
}

console.log(failures.length === 0 ? '\nALL CHECKS PASSED' : `\n${failures.length} CHECK(S) FAILED`);
process.exit(failures.length === 0 ? 0 : 1);