// Drives the REAL routing decision in pages-renderers.js — the one that decides whether a manager ever
// sees the substitution plan — and then the real match-view mount on top of it.
//
// This exists because T0-UI-4b was invisible to every other test. The plan's module was exercised
// directly, its controller was exercised with MockMvc, and both were green while the feature was
// completely unreachable: mounted on a page no route rendered. The defect lived in the routing, and
// nothing looked at the routing.
import { readFileSync } from 'node:fs';

const dir = 'src/main/resources/static/js/pages/views';
const strip = (src) => src
    .replace(/^import[^;]*;$/gm, '')
    .replace(/^export /gm, '');
const utils = strip(readFileSync(`${dir}/utils.js`, 'utf8'));
const renderers = strip(readFileSync('src/main/resources/static/js/pages-renderers.js', 'utf8'));
const matchView = strip(readFileSync(`${dir}/match-view.js`, 'utf8'));
const planView = strip(readFileSync(`${dir}/substitution-plan-view.js`, 'utf8'));

const failures = [];
const check = (name, condition, detail) => {
    if (condition) console.log(`ok   ${name}`);
    else { console.log(`FAIL ${name} — ${detail}`); failures.push(name); }
};

// ── 1. The routing: a played fixture card must not offer a path to the dead fixture sheet ─────────────
// `bindScheduleInteractions` prefers `onLoadMatch` over `onLoadFixture`. That preference is correct and
// was the owner's decision; what it broke was a panel bolted to the surface that lost.
{
    // `renderers` has already had its `export ` keywords stripped, so the search is for the bare
    // function name. Looking for the keyword-prefixed form here finds nothing, and slicing from a
    // not-found index silently yields a one-character string that matches nothing — a harness that
    // cannot fail. It made that mistake the first time it was run, and every check below passed.
    const bindAt = renderers.indexOf('function bindScheduleInteractions');
    check('the routing function was found in the source', bindAt >= 0,
        'bindScheduleInteractions is not in pages-renderers.js; the rest of this section proves nothing');
    const body = renderers.slice(bindAt);
    const fixtureHandler = body.slice(body.indexOf(".js-load-fixture"));

    check('a played fixture is routed to the match view',
        /if \(match\?\.played && match\?\.id\) classes\.push\('js-load-match'/.test(renderers),
        'a played fixture no longer carries js-load-match, so it may be routing somewhere else');

    check('an unplayed fixture is routed to the match view, not the fixture sheet',
        /onLoadMatch\(fixtureId, 'schedule', \{ initialTab: 'preview', fixture: true \}\)/.test(fixtureHandler),
        'the unplayed fixture handler no longer prefers onLoadMatch');

    check('the fixture sheet is not the first choice for an unplayed fixture',
        fixtureHandler.indexOf("typeof onLoadMatch === 'function'")
            < fixtureHandler.indexOf("typeof onLoadFixture === 'function'"),
        'onLoadFixture is being tried first again, which is the defect in its original form');
}

// ── 2. The mount: match-view must carry the host and reach the plan ─────────────────────────────────
{
    check('the match view carries the plan host',
        matchView.includes('id="fm-substitution-plan"'),
        'match-view.js has no #fm-substitution-plan, so there is nowhere to mount the plan');

    check('the mount is called',
        /void mountSubstitutionPlan\(\)/.test(matchView),
        'mountSubstitutionPlan is defined but never called, which is how the fixture sheet died');

    check('the mount runs for a fixture as well as a played match',
        /if \(isFixture\) await planView\.loadPlan\(/.test(matchView)
            && /else await planView\.loadPlanForMatch\(/.test(matchView),
        'the two id spaces are not both handled, so one of the two surfaces is dead');

    check('the panel is offered only to the home club',
        /Number\(managerTeamId\) !== Number\(homeTeamId\)\) return;/.test(matchView),
        'the ownership test is missing or compares something other than the two club ids');

    check('the ownership test compares ids, never names',
        !/managerIsHome\s*=\s*.*homeTeamName\s*===/.test(matchView),
        'home/away is being decided by comparing club names');
}

// ── 3. One mounting point, not two ───────────────────────────────────────────────────────────────────
{
    const fixtureSheet = readFileSync(`${dir}/fixture-view.js`, 'utf8');
    check('the fixture sheet no longer mounts the plan',
        !/substitutionPlanView\.loadPlan\(/.test(fixtureSheet),
        'fixture-view.js mounts the plan as well; two mounting points will drift apart');
}

// ── 4. The module actually renders when reached from the match view ─────────────────────────────────
{
    const sandbox = `
        const escapeHtml = (v) => String(v ?? '');
        ${utils}
        ${planView}
        globalThis.__factory = createSubstitutionPlanView;
    `;
    const factory = new Function(sandbox + '\nreturn __factory;')();
    globalThis.window = { alert: () => {} };

    const asked = [];
    const authFetch = async (url) => {
        asked.push(url);
        if (url.includes('substitution-plan')) {
            return { ok: true, status: 200, json: async () => ({
                fixtureId: 7, homeTeam: 'Home', awayTeam: 'Away', editable: false,
                rulesJson: '[{"team":"HOME","triggerMinute":60,"condition":"LOSING","playerOnId":"","playerOffId":""}]',
                outcomeJson: '[{"team":"HOME","triggerMinute":60,"condition":"LOSING","playerOnId":"","playerOffId":"","status":"FIRED","voidReason":"NONE","firedAtMinute":63}]'
            }) };
        }
        return { ok: true, status: 200, json: async () => [] };
    };

    const host = { innerHTML: '', querySelector: () => null, querySelectorAll: () => [] };
    const view = factory({ authFetch, getTeamId: () => 1 });

    await view.loadPlanForMatch(42, host, 1);
    check('the match route is the one a played match asks for',
        asked[0] === '/api/sim/matches/42/substitution-plan',
        `asked for ${asked[0]}`);
    check('the outcome badge reaches the page',
        (host.innerHTML.includes('Fired at') ?? false),
        `rendered: ${host.innerHTML.slice(0, 200)}`);

    asked.length = 0;
    await view.loadPlan(7, host, 1);
    check('the fixture route is the one a fixture asks for',
        asked[0] === '/api/sim/fixtures/7/substitution-plan',
        `asked for ${asked[0]}`);
}

console.log(failures.length === 0 ? '\nALL CHECKS PASSED' : `\n${failures.length} CHECK(S) FAILED`);
process.exit(failures.length === 0 ? 0 : 1);