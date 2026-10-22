// Executes the fixture row renderer against a stub DOM, so the numbers on a row can be *run* rather
// than read.
//
// The owner's report (2026-10-22): *"za International meceve nije dobar round S1 · D1 · Wk 1 · Rd null,
// verujem i za Cup, liga je dobra"* - the round on an international tie and on a cup tie read "Rd null",
// while the league's read correctly.
//
// The cause was one expression: `Number.isFinite(Number(value))`. `Number(null)` is **zero**, so the
// league round - which is null for every cup and every international tie, because the league round only
// exists for a league - passed as "a number", and the row printed the null itself. The real round sat
// in the row, unused, because the branch that would have read it was never reached.
//
// The same null read as a zero counted a result the manager has not seen yet as a 0-0 draw in the
// club page's "Wins / Draws / Losses" - so the count of draws gave the hidden result away before it
// was revealed.
//
// Run:  node src/test/javascript/fixture-row-numbers.test.mjs
// Skipped by FixtureRowNumbersTest when node is absent.

import assert from 'node:assert/strict';

const RENDERERS = new URL('../../main/resources/static/js/pages-renderers.js', import.meta.url).pathname;
const UTILS = new URL('../../main/resources/static/js/pages/views/utils.js', import.meta.url).pathname;

// ---------------------------------------------------------------- the DOM we hand the page

/**
 * One fake element: it records listeners and takes the markup.
 *
 * @param attributes the attributes it claims to have
 * @returns a fake element
 */
function element(attributes = {}) {
    return {
        attributes,
        listeners: {},
        textContent: '',
        innerHTML: '',
        dataset: {},
        setAttribute(name, value) { this.attributes[name] = value; },
        getAttribute(name) { return this.attributes[name] ?? null; },
        addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); },
        removeEventListener() {},
        querySelectorAll() { return []; },
        querySelector() { return null; },
        closest() { return null; }
    };
}

function installDom() {
    const store = new Map();

    globalThis.localStorage = {
        getItem: key => (store.has(key) ? store.get(key) : null),
        setItem: (key, value) => store.set(key, String(value)),
        removeItem: key => store.delete(key),
        clear: () => store.clear()
    };
    globalThis.sessionStorage = globalThis.localStorage;
    globalThis.window = globalThis;
    globalThis.document = {
        getElementById: () => element(),
        querySelectorAll: () => [],
        querySelector: () => null,
        createElement: () => element(),
        body: element(),
        addEventListener: () => {},
        documentElement: { style: {} },
        cookie: ''
    };
    globalThis.fetch = async () => ({ ok: true, status: 200, json: async () => ({}) });
    // Node's own `navigator` is a read-only getter, so it has to be replaced rather than assigned.
    Object.defineProperty(globalThis, 'navigator', {
        value: { serviceWorker: undefined, userAgent: 'node' },
        configurable: true,
        writable: true
    });
}

installDom();

const { buildScheduleFixtureCardHtml, renderMatchesView } = await import(RENDERERS);
const { hasNumber } = await import(UTILS);

// ---------------------------------------------------------------- the numbers themselves

// A null is not a number, and it is not a zero either.
assert.equal(hasNumber(null), false, 'a null must not pass as a number');
assert.equal(hasNumber(undefined), false, 'an absent value must not pass as a number');
assert.equal(hasNumber(''), false, 'an empty string must not pass as a number');
assert.equal(hasNumber(0), true, 'a zero is a number');
assert.equal(hasNumber(3), true, 'a number is a number');
assert.equal(hasNumber('3'), true, 'a number written as a string is a number');

console.log('OK  a null is not a number, and a zero still is');

// ---------------------------------------------------------------- the row's calendar label

/**
 * The calendar label the row actually shows.
 *
 * @param match the row's data
 * @returns the label, or an empty string when the row shows none
 */
function calendarLabel(match) {
    const html = buildScheduleFixtureCardHtml(match, { safe: value => String(value ?? '') });
    const found = html.match(/fx-calendar">([^<]*)</);
    return found ? found[1] : '';
}

/** A league row: the league round is there. */
const league = calendarLabel({
    seasonYear: 1, day: 3, week: 1, leagueRound: 3, round: 3,
    homeTeam: 'OFK Omladinac', awayTeam: 'FK Beograd', played: false
});
assert.equal(league, 'S1 · D3 · Wk 1 · Rd 3', 'the league row no longer says what it used to say');

/** A cup tie: no league round, and the row's own round. */
const cup = calendarLabel({
    seasonYear: 1, day: 2, week: 4, leagueRound: null, round: 2,
    homeTeam: 'OFK Omladinac', awayTeam: 'FK Cup', played: false
});
assert.ok(!cup.includes('null'), `a cup tie reads "${cup}": the null league round is being printed`);
assert.ok(cup.includes('Rd 2'), `a cup tie reads "${cup}": the round it does have is not shown`);

/** An international tie: no league round either, and its own round. */
const international = calendarLabel({
    seasonYear: 1, day: 1, week: 1, leagueRound: null, round: 3,
    homeTeam: 'Srbija', awayTeam: 'Hrvatska', played: false
});
assert.ok(!international.includes('null'),
    `an international tie reads "${international}": the null league round is being printed`);
assert.ok(international.includes('Rd 3'),
    `an international tie reads "${international}": the round it does have is not shown`);

/** A row with no round at all must not invent one. */
const noRound = calendarLabel({
    seasonYear: 1, day: 1, week: 1, leagueRound: null, round: null,
    homeTeam: 'A', awayTeam: 'B', played: false
});
assert.ok(!noRound.includes('Rd'), `a row with no round reads "${noRound}": it invented one`);
assert.ok(!noRound.includes('null'), `a row with no round reads "${noRound}": it printed the null`);

console.log('OK  the league, the cup and the international row each show the round they have');

// ---------------------------------------------------------------- the summary of a hidden result

/**
 * The club page's summary: matches, wins, draws, losses.
 *
 * @param matches the rows
 * @returns the summary's four numbers
 */
function summary(matches) {
    const main = element({ id: 'main-content' });
    globalThis.document.getElementById = id => (id === 'main-content' ? main : element());
    renderMatchesView(matches, 'Club schedule', { loadMatch: () => {} });
    const html = main.innerHTML;
    const read = label => {
        const found = html.match(new RegExp(`<strong>(\\d+)</strong><span>${label}</span>`));
        return found ? Number(found[1]) : null;
    };
    return { matches: read('Matches'), wins: read('Wins'), draws: read('Draws'), losses: read('Losses') };
}

const withHidden = summary([
    { homeTeam: 'A', awayTeam: 'B', homeGoals: 2, awayGoals: 1, played: true },
    { homeTeam: 'A', awayTeam: 'C', homeGoals: null, awayGoals: null, played: true, resultHidden: true }
]);
assert.equal(withHidden.draws, 0,
    `a result the manager has not seen yet is counted as a draw (draws: ${withHidden.draws})`);
assert.equal(withHidden.wins, 1, 'the result that was shown is not counted as a win');
assert.equal(withHidden.losses, 0, 'a loss is counted where there is none');

console.log('OK  a result that has not been revealed yet is not counted as a draw');
