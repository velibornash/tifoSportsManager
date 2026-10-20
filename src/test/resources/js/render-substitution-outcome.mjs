// Loads the real substitution-plan-view module and drives loadPlan with the fetch layer stubbed, so the
// post-match outcome is rendered by the same code the browser runs. This is the check that catches a
// ReferenceError inside a template string — text assertions cannot — and that pins the rule/outcome
// alignment, which is the part that is easy to get quietly wrong.
//
// The engine's VoidReason and Status names are repeated here from
// ConditionalSubstitutionRules.java. If those enums change, this harness is the thing that notices.
import { readFileSync } from 'node:fs';

const dir = 'src/main/resources/static/js/pages/views';
const strip = (src) => src
    .replace(/^import[^;]*;$/gm, '')
    .replace(/^export /gm, '');
const escape = strip(readFileSync('src/main/resources/static/js/ui/escape.js', 'utf8'));
const utils = strip(readFileSync(`${dir}/utils.js`, 'utf8'));
const view = strip(readFileSync(`${dir}/substitution-plan-view.js`, 'utf8'));

const sandbox = `
// escape.js supplies escapeHtml, which utils.js re-exports as htmlEscape.
${escape}
${utils}
${view}
globalThis.__factory = createSubstitutionPlanView;
globalThis.__matchOutcomes = matchOutcomes;
globalThis.__describeOutcome = describeOutcome;
`;
const factory = new Function(sandbox + '\nreturn __factory;')();
const matchOutcomes = new Function(sandbox + '\nreturn __matchOutcomes;')();
const describeOutcome = new Function(sandbox + '\nreturn __describeOutcome;')();

// A DOM stub with no query engine: every lookup returns null, which is the state of a page that has just
// been assigned innerHTML and not yet been parsed. The render path is what is under test.
const host = { innerHTML: '', querySelector: () => null, querySelectorAll: () => [] };
globalThis.window = { alert: () => {}, confirm: () => true, prompt: () => '' };

const failures = [];
const check = (name, condition, detail) => {
    if (condition) {
        console.log(`ok   ${name}`);
    } else {
        console.log(`FAIL ${name} — ${detail}`);
        failures.push(name);
    }
};

const RULE_A = { team: 'HOME', triggerMinute: 60, condition: 'LOSING', playerOnId: '11', playerOffId: '9' };
const RULE_B = { team: 'HOME', triggerMinute: 75, condition: 'LEADING', playerOnId: '12', playerOffId: '' };

const OUT_A_FIRED = {
    team: 'HOME', triggerMinute: 60, condition: 'LOSING', playerOnId: '11', playerOffId: '9',
    status: 'FIRED', voidReason: 'NONE', firedAtMinute: 63
};
const OUT_B_VOID = {
    team: 'HOME', triggerMinute: 75, condition: 'LEADING', playerOnId: '12', playerOffId: '',
    status: 'VOID', voidReason: 'EMPTY_BENCH', firedAtMinute: -1
};

function stubFetch(plan, squad = []) {
    return async (url) => {
        if (url.includes('substitution-plan')) {
            return { ok: true, status: 200, json: async () => plan };
        }
        return { ok: true, status: 200, json: async () => squad };
    };
}

async function renderPlan(plan, squad) {
    const h = { innerHTML: '', querySelector: () => null, querySelectorAll: () => [] };
    const view = factory({ authFetch: stubFetch(plan, squad), getTeamId: () => 1 });
    await view.loadPlan(plan.fixtureId, h, 1);
    return h.innerHTML;
}

/** The rendered HTML split into one chunk per rule row, so a badge can be attributed to its own rule. */
function ruleRows(html) {
    return html.split('<li class="fm-substitution-rule">').slice(1);
}

// ── 1. A fired rule reports the minute it fired at ───────────────────────────────────────────────────
{
    const html = await renderPlan({
        fixtureId: 1, homeTeam: 'Home', awayTeam: 'Away', editable: false,
        rulesJson: JSON.stringify([RULE_A, RULE_B]),
        outcomeJson: JSON.stringify([OUT_A_FIRED, OUT_B_VOID])
    });
    const rows = ruleRows(html);

    check('both rules rendered', rows.length === 2, `got ${rows.length} rows`);
    check('fired rule shows the minute', rows[0]?.includes('Fired at 63&#39;') || rows[0]?.includes("Fired at 63'"),
        `row 0 was: ${rows[0]?.slice(0, 220)}`);
    check('fired rule is badged as good news', rows[0]?.includes('fm-badge-fit'),
        `row 0 carried no good badge: ${rows[0]?.slice(0, 220)}`);
    check('void rule explains itself in words',
        (rows[1]?.includes('nobody was left on the bench') ?? false),
        `row 1 was: ${rows[1]?.slice(0, 260)}`);
    check('void rule is badged as bad news', rows[1]?.includes('fm-badge-inj'),
        `row 1 carried no bad badge: ${rows[1]?.slice(0, 260)}`);
    check('header says the match is reported', html.includes('How each condition went'),
        `header did not switch to the post-match wording`);
}

// ── 2. Alignment is by content, not by position ──────────────────────────────────────────────────────
// The engine skips a rule it cannot parse when it builds the outcome list, which shifts every later
// outcome up by one. Index matching would then report rule A as having fired at rule B's minute.
{
    const html = await renderPlan({
        fixtureId: 1, homeTeam: 'Home', awayTeam: 'Away', editable: false,
        rulesJson: JSON.stringify([RULE_A, RULE_B]),
        outcomeJson: JSON.stringify([OUT_B_VOID])
    });
    const rows = ruleRows(html);

    check('a rule with no outcome shows no badge', !rows[0]?.includes('fm-badge'),
        `rule A was given an outcome that belongs to rule B: ${rows[0]?.slice(0, 220)}`);
    check('the rule that did go void is the one badged',
        (rows[1]?.includes('nobody was left on the bench') ?? false),
        `rule B lost its own outcome: ${rows[1]?.slice(0, 260)}`);

    const aligned = matchOutcomes([RULE_A, RULE_B], [OUT_B_VOID]);
    check('matchOutcomes returns null for the rule with no record', aligned[0] === null,
        `expected null for rule A, got ${JSON.stringify(aligned[0])}`);
    check('matchOutlines returns the record for the rule that has one',
        aligned[1]?.status === 'VOID', `expected VOID for rule B, got ${aligned[1]?.status}`);
}

// ── 3. Duplicate rules each get their own outcome, not both the same one ───────────────────────────
{
    const dup = { ...RULE_A };
    const aligned = matchOutcomes([RULE_A, dup], [OUT_A_FIRED, { ...OUT_A_FIRED, firedAtMinute: 71 }]);
    check('identical rules consume outcomes in order',
        aligned[0]?.firedAtMinute === 63 && aligned[1]?.firedAtMinute === 71,
        `got ${aligned[0]?.firedAtMinute} and ${aligned[1]?.firedAtMinute}`);
}

// ── 4. Before kickoff there is no outcome, and the panel must not invent one ────────────────────────
{
    const html = await renderPlan({
        fixtureId: 1, homeTeam: 'Home', awayTeam: 'Away', editable: true,
        rulesJson: JSON.stringify([RULE_A]), outcomeJson: null
    });
    check('a plan with no match carries no outcome badge', !html.includes('fm-badge-fit') && !html.includes('fm-badge-inj'),
        'a badge was shown for a match that has not been played');
    check('the editable wording is still the pre-match wording', html.includes('Decisions close an hour before kickoff'),
        'the header claimed a post-match report on an unplayed fixture');
    check('the add-condition form is still offered', html.includes('data-sub-rule-add'),
        'the pre-match panel lost its form');
}

// ── 5. A condition that simply never came up is not reported as a failure ──────────────────────────
{
    const neverCame = { ...OUT_A_FIRED, status: 'PENDING', firedAtMinute: -1 };
    const described = describeOutcome(neverCame);
    check('a pending rule reads as never-due, not as broken',
        described.tone === 'flat' && described.label.includes('never came up'),
        `got ${JSON.stringify(described)}`);

    const waiting = describeOutcome({ ...OUT_A_FIRED, status: 'WAITING_FOR_STOPPAGE', firedAtMinute: -1 });
    check('a rule the laws stopped reads as neutral, not as good',
        waiting.tone === 'flat', `got ${JSON.stringify(waiting)}`);

    check('an unknown status does not throw', describeOutcome({ status: 'SOMETHING_NEW' }) !== null,
        'an unrecognised status produced no badge at all, which hides the rule');

    check('a null outcome produces no badge', describeOutcome(null) === null,
        'a rule with no record was given a badge');
}

// ── 6. Every void reason the engine can write has words for it ──────────────────────────────────────
// A void reason with no sentence behind it falls back to a bare "Void", which tells the manager nothing.
{
    const reasons = ['NO_SUBS_LEFT', 'NO_WINDOWS_LEFT', 'PLAYER_UNAVAILABLE', 'PLAYER_ALREADY_ON',
        'PLAYER_ALREADY_ON_PITCH', 'EMPTY_BENCH'];
    const bare = reasons.filter(r => describeOutcome({ status: 'VOID', voidReason: r }).label === 'Void');
    check('no void reason falls back to a bare "Void"', bare.length === 0,
        `these reasons have no wording: ${bare.join(', ')}`);

    check('a void rule with an unknown reason still says it was void',
        describeOutcome({ status: 'VOID', voidReason: 'FROM_THE_FUTURE' }).label === 'Void',
        'an unknown reason should still report that the rule was void');
}

// ── 7. Player names from the outcome are not trusted; the sentence is built from the squad ───────────
{
    const html = await renderPlan({
        fixtureId: 1, homeTeam: 'Home', awayTeam: 'Away', editable: false,
        rulesJson: JSON.stringify([RULE_A]),
        outcomeJson: JSON.stringify([OUT_A_FIRED])
    }, [{ id: 11, name: 'Kovac' }, { id: 9, name: 'Ilic' }]);

    check('the rule sentence names the players', html.includes('Kovac') && html.includes('Ilic'),
        'the rendered rule did not name the squad players');
}

console.log(failures.length === 0 ? '\nALL CHECKS PASSED' : `\n${failures.length} CHECK(S) FAILED`);
process.exit(failures.length === 0 ? 0 : 1);