// Loads the real match-lineup-view module and drives loadPlan against a stubbed fetch layer.
//
// The check that was missing when the substitution plan turned out to be unreachable: the module and its
// controller were both tested and the feature still had no path from a manager's click to a rendered
// screen. This renders the screen; the mounting is asserted from match-view.js below.
import { readFileSync } from 'node:fs';

const dir = 'src/main/resources/static/js/pages/views';
const strip = (src) => src
    .replace(/^import[^;]*;$/gm, '')
    .replace(/^export /gm, '');
const escape = strip(readFileSync('src/main/resources/static/js/ui/escape.js', 'utf8'));
const utils = strip(readFileSync(`${dir}/utils.js`, 'utf8'));
const view = strip(readFileSync(`${dir}/match-lineup-view.js`, 'utf8'));
const matchView = strip(readFileSync(`${dir}/match-view.js`, 'utf8'));

const failures = [];
const check = (name, condition, detail) => {
    if (condition) console.log(`ok   ${name}`);
    else { console.log(`FAIL ${name} — ${detail}`); failures.push(name); }
};

// ── the mounting ─────────────────────────────────────────────────────────────────────────────────────
check('the match view carries a host for the team selection',
    matchView.includes('id="fm-match-lineup"'),
    'no #fm-match-lineup, so there is nowhere to mount it');
check('the mount is called',
    /void mountLineup\(\)/.test(matchView),
    'mountLineup is defined but never called — the way the substitution plan died');
check('the mount needs the manager to have a club',
    /if \(!managerTeamId\) return;/.test(matchView),
    'without this the panel is attempted for an account that manages no club');

// ── the rendering ────────────────────────────────────────────────────────────────────────────────────
const sandbox = `
    ${escape}
    ${utils}
    ${view}
    globalThis.__factory = createMatchLineupView;
`;
const factory = new Function(sandbox + '\nreturn __factory;')();
globalThis.window = { alert: () => {}, confirm: () => true };

/**
 * A host with a query engine.
 *
 * <p>This screen writes into sub-lists after it has assigned its own innerHTML — the starting eleven, the
 * bench and the squad are three elements it then fills. A stub whose every lookup returns null cannot run
 * it, and forcing the view to guard each of those would be defensive noise for a browser where the
 * elements always exist. So the stub keeps one small object per selector and remembers what was written.
 */
function host() {
    const nodes = new Map();
    return {
        innerHTML: '',
        querySelector: (selector) => {
            if (!nodes.has(selector)) {
                nodes.set(selector, {
                    innerHTML: '',
                    textContent: '',
                    value: '',
                    dataset: {},
                    addEventListener: () => {},
                    closest: () => null
                });
            }
            return nodes.get(selector);
        },
        querySelectorAll: () => [],
        addEventListener: () => {},
        __nodes: nodes
    };
}

const PLAN = {
    fixtureId: 11,
    teamName: 'OFK Omladinac',
    opponentName: 'RFK Smederevo',
    editable: true,
    perMatch: false,
    formation: '4-4-2',
    starters: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11],
    bench: [12, 13],
    players: [
        { id: 1, name: 'Player 1', injured: false, suspended: false },
        { id: 12, name: 'Player 12', injured: false, suspended: false },
        { id: 13, name: 'Banned Player', injured: false, suspended: true, suspensionReason: 'sent off in his last official match' }
    ],
    warnings: ['With no goalkeeper on the bench, the starting goalkeeper cannot be substituted.']
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
    await view.loadPlan(11, h);

    check('the screen reads the fixture route',
        asked[0] === '/api/sim/fixtures/11/lineup', `asked for ${asked[0]}`);
    check('the panel renders', h.innerHTML.includes('Team selection'), h.innerHTML.slice(0, 160));
    // The eleven, the bench and the squad are filled into sub-lists after the panel's own markup is
    // written, so they are read from those nodes rather than from the panel's innerHTML.
    const startersHtml = h.__nodes.get('[data-starters]').innerHTML;
    const benchHtml = h.__nodes.get('[data-bench]').innerHTML;
    check('all eleven starters are shown',
        (startersHtml.match(/data-remove=/g) || []).length === 11,
        `expected 11 starter rows, got ${(startersHtml.match(/data-remove=/g) || []).length}`);
    check('the bench is shown',
        (benchHtml.match(/data-remove=/g) || []).length === 2,
        `expected 2 bench rows, got ${(benchHtml.match(/data-remove=/g) || []).length}`);
    check('the counter says how many are still to pick',
        h.__nodes.get('[data-starter-count]').textContent.includes('11 picked'),
        `the counter reads "${h.__nodes.get('[data-starter-count]').textContent}" — the manager cannot tell `
        + 'a complete eleven from a short one');
    check('the formation is offered', h.innerHTML.includes('data-lineup-formation'),
        'the formation is not editable');
    check('the save and reset buttons are there',
        h.innerHTML.includes('data-lineup-save') && h.innerHTML.includes('data-lineup-clear'),
        'no way to save, or no way back to the default eleven');
    check('a saved-for-this-fixture XI says so',
        h.innerHTML.includes('Showing your default eleven'),
        'the manager cannot tell which of the two he is looking at');
}

// ── the warnings and the unavailable players ─────────────────────────────────────────────────────────
{
    const view = factory({
        authFetch: async () => ({ ok: true, status: 200, json: async () => PLAN }),
        getTeamId: () => 1
    });
    const h = host();
    await view.loadPlan(11, h);

    check('the warning is on the screen before anything is submitted',
        h.innerHTML.includes('cannot be substituted'),
        'a warning the manager can only get by submitting is one he reads too late');
    check('a suspended player is marked in the squad list',
        h.__nodes.get('[data-squad]').innerHTML.includes('sent off in his last official match'),
        'the reason must be on the row, not only in a warning about the eleven');
}

// ── a played fixture, and a club not in it ───────────────────────────────────────────────────────────
{
    const view = factory({
        authFetch: async () => ({ ok: true, status: 200, json: async () => ({ ...PLAN, editable: false }) }),
        getTeamId: () => 1
    });
    const h = host();
    await view.loadPlan(11, h);

    check('a played fixture offers no save',
        !h.innerHTML.includes('data-lineup-save'),
        'a screen that offers to edit the record of a match already played');
    check('and says the team is picked',
        h.innerHTML.includes('the team has been picked'),
        'the manager is not told why he cannot change it');
}

{
    const view = factory({
        authFetch: async () => ({ ok: true, status: 200, json: async () => ({ ...PLAN, error: 'NOT_IN_THIS_FIXTURE' }) }),
        getTeamId: () => 1
    });
    const h = host();
    await view.loadPlan(11, h);

    check('a club not in the fixture renders nothing',
        h.innerHTML === '',
        `an empty panel reads as "you have no squad": ${h.innerHTML.slice(0, 120)}`);
}

console.log(failures.length === 0 ? '\nALL CHECKS PASSED' : `\n${failures.length} CHECK(S) FAILED`);
process.exit(failures.length === 0 ? 0 : 1);