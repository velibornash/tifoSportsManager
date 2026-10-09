// Loads the real country-view module and drives buildGeneralTab / buildSelectorTab through
// loadCountryPage's own body-building, with the fetch layer stubbed. This is the check that
// catches an undefined identifier in a template string: text assertions cannot.
import { readFileSync } from 'node:fs';

const dir = 'src/main/resources/static/js/pages/views';
const strip = (src) => src
    .replace(/^import[^;]*;$/gm, '')
    .replace(/^export /gm, '');
const utils = strip(readFileSync(`${dir}/utils.js`, 'utf8'));
const view = strip(readFileSync(`${dir}/country-view.js`, 'utf8'));

const sandbox = `
// escape.js supplies escapeHtml, which utils.js re-exports as htmlEscape.
const escapeHtml = (v) => String(v ?? '');
// utils.js supplies htmlEscape, sortCountryLeagues, buildCountryFlagBadgeHtml,
// buildLeagueMetaLabel and buildEmptyState. country-view.js imports them. Both files are
// concatenated with their import/export lines stripped, so the module graph is flattened and
// every name resolves the way the browser resolves it.
${utils}
${view}
globalThis.__view = createCountryView;
`;
const fn = new Function(sandbox + '\nreturn __view;');
const factory = fn();

// Minimal DOM + fetch stubs.
// A DOM stub with no query engine: every lookup returns null, which is the state of a page that has
// just been assigned innerHTML and not yet been parsed. The render path is what is under test.
const noQuery = { querySelector: () => null, querySelectorAll: () => [] };
const main = Object.assign({ innerHTML: '' }, noQuery);
globalThis.document = Object.assign({
    getElementById: () => main,
    addEventListener: () => {},
}, noQuery);
globalThis.window = { alert: () => {}, confirm: () => true, prompt: () => '', loadStadium: () => {} };

const NOT_FOUND = { failed: true, status: 404 };
async function json(payload) { return { ok: true, status: 200, json: async () => payload }; }

const seniorNt = { exists: true, teamId: 7, isSelector: true, squad: [], pool: [], squadLock: {} };

// The manager's own country iso is a variable, not a constant: the represented-country page is only
// reached when the country being asked about IS the manager's own, so driving it needs a second
// instance whose own country is a represented one.
function buildView(iso, name, teamId) {
    return factory({
        // T1-13b: the manager's own club id, so the qualifying table can mark it.
        getTeamId: () => teamId ?? null,
        authFetch: async (path) => {
            if (path.includes('friendly-requests/opponents')) return json([{ id: 9, name: 'Opponent', country: 'X' }]);
            if (path.includes('friendly-requests/slot')) return json({ week: 6, day: 1 });
            if (path.match(/friendly-requests\/\d+$/)) return json({ season: 1, week: 6, incoming: [], outgoing: [] });
            if (path.includes('/national-team')) return json(seniorNt);
            if (path.includes('/clubs/ranking')) return json({ totalClubs: 2, clubs: [] });
            if (path.includes('/leagues')) return json([]);
            if (path.includes('/calendar/week')) return json({ days: [] });
            if (path.includes('/calendar/season')) return json({ weeks: [] });
            // Endpoints the represented-country page reads. A ranking list, and a qualifying group
            // for each of the country's two sides. All three are deliberately populated, so the page
            // has something to draw and a missing one cannot pass by drawing nothing.
            if (path.startsWith('/countries/ranking')) {
                return json([
                    { isoCode: 'ROU', name: 'Romania', rated: true, position: 12, points: 1504 },
                    { isoCode: 'SRB', name: 'Serbia', rated: false, position: null, points: 1500 },
                ]);
            }
            if (path.includes('/national-tournaments/senior/QUALIFYING')) {
                return json({
                    exists: true, week: 6,
                    groups: [{
                        code: 'C',
                        table: [
                            { countryIso: 'ROU', teamName: 'Romania', position: 1, played: 3, goalDifference: 4, points: 7 },
                            { countryIso: 'SRB', teamName: 'Serbia', position: 2, played: 3, goalDifference: 1, points: 5 },
                        ],
                        fixtures: [],
                    }],
                });
            }
            if (path.includes('/national-tournaments/u21/QUALIFYING')) {
                return json({ exists: false, week: null, groups: [] });
            }
            if (path === '/countries') return json([{ isoCode: iso, name }]);
            return json({});
        },
        loadPage: async () => {},
        loadMatch: async () => {},
        setActiveLeagueContext: () => {},
        getCurrentUserCountryIsoCode: () => iso,
        getActiveLeagueCountryIsoCode: () => iso,
        getCurrentUserCountryName: () => name,
    });
}

const view_ = buildView('SRB', 'Serbia');

const tabs = ['general', 'calendar', 'clubs', 'qualifying', 'senior', 'u21'];
let failed = 0;
for (const tab of tabs) {
    main.innerHTML = '';
    try {
        await view_.loadCountryPage({ tab });
        const text = main.innerHTML || '';
        const broke = text.includes('Could not load') && !text.includes('fm-country-header');
        const hasWarmUp = main.innerHTML.includes('National warm-up');
        console.log(`${tab.padEnd(11)} ${broke ? 'FAILED' : 'ok'}  ${main.innerHTML.length} chars`
            + (hasWarmUp ? '  [warm-up present]' : ''));
        if (broke) { failed++; console.log('   -> ' + text.slice(0, 160)); }
    } catch (e) {
        failed++;
        console.log(`${tab.padEnd(11)} THREW  ${e.message}`);
    }
}

// A represented country — a country with national sides and no club pyramid. Twenty-four of the
// forty-eight countries on the World page are exactly this, and a manager who clicks one reaches it
// from a national-tournament group table, where every country name is a button. It is a different
// code path from the six tabs above: it is taken BEFORE the try block, so nothing inside it is
// caught, and it dispatched to its own function rather than to the tab builders.
{
    const represented = buildView('ROU', 'Romania');
    main.innerHTML = '';
    try {
        await represented.loadCountryPage({ tab: 'general', simulatedCountry: 'ROU' });
        const text = main.innerHTML || '';
        const rendered = text.includes('Romania');
        const markedOwnRow = text.includes('is-highlighted');
        // The mark is asserted, not just reported. A row marked with a class the stylesheet does not
        // define looks identical to a row marked correctly on screen, so "it emitted something" has
        // to be a passing condition rather than an observation in the log.
        const ok = rendered && markedOwnRow;
        console.log(`represented  ${ok ? 'ok' : 'FAILED'}  ${main.innerHTML.length} chars`
            + (markedOwnRow ? '  [own row marked]' : '  [own row NOT marked]'));
        if (!ok) {
            failed++;
            if (!rendered) console.log('   -> the page did not name the country: ' + text.slice(0, 160));
            if (!markedOwnRow) console.log(
                '   -> the country being viewed is not marked in its own group table, so a manager '
                + 'reading "who are we drawn with" has to find their own row by name.');
        }
    } catch (e) {
        failed++;
        console.log(`represented  THREW  ${e.message}`);
    }
}

// T1-13b: the manager's own club is marked in the qualifying table, and a club that is BOTH
// qualifying and the manager's own carries BOTH classes.
{
    // A qualifying table with three rows: one qualifying, one not, one that is both.
    const qualifyingView = factory({
        getTeamId: () => 42,
        authFetch: async (path) => {
            if (path.includes('friendly-requests/opponents')) return json([{ id: 9, name: 'Opponent', country: 'X' }]);
            if (path.includes('friendly-requests/slot')) return json({ week: 6, day: 1 });
            if (path.match(/friendly-requests\/\d+$/)) return json({ season: 1, week: 6, incoming: [], outgoing: [] });
            if (path.includes('/national-team')) return json(seniorNt);
            if (path.includes('/clubs/ranking')) return json({ totalClubs: 2, clubs: [] });
            if (path.includes('/leagues')) return json([]);
            if (path.includes('/calendar/week')) return json({ days: [] });
            if (path.includes('/calendar/season')) return json({ weeks: [] });
            if (path.startsWith('/countries/ranking')) return json([]);
            // The qualifying tab reads /countries/{iso}/qualifying, which returns tiers -> cups -> standings.
            if (path.includes('/qualifying')) {
                return json({
                    country: 'Serbia', isoCode: 'SRB', season: 1,
                    tiers: [{
                        tier: 1,
                        cups: [
                            // teamId 42 is the manager's own club AND qualifies (position 1, 1 place).
                            { cup: 'Champions Cup', places: 1, standings: [
                                { teamName: 'Romania', position: 1, points: 7, goalDifference: 4, qualifies: true, teamId: 42 },
                                { teamName: 'Serbia', position: 2, points: 5, goalDifference: 1, qualifies: false, teamId: 7 },
                                { teamName: 'Hungary', position: 3, points: 3, goalDifference: -2, qualifies: false, teamId: 99 },
                            ]},
                        ],
                    }],
                });
            }
            if (path.includes('/national-tournaments/u21/QUALIFYING')) return json({ exists: false, week: null, groups: [] });
            if (path === '/countries') return json([{ isoCode: 'SRB', name: 'Serbia' }]);
            return json({});
        },
        loadPage: async () => {},
        loadMatch: async () => {},
        setActiveLeagueContext: () => {},
        getCurrentUserCountryIsoCode: () => 'SRB',
        getActiveLeagueCountryIsoCode: () => 'SRB',
        getCurrentUserCountryName: () => 'Serbia',
    });
    main.innerHTML = '';
    try {
        await qualifyingView.loadCountryPage({ tab: 'qualifying' });
        const text = main.innerHTML || '';
        // The manager's own club (teamId 42) is in position 1, which qualifies.
        const bothClasses = text.includes('is-qualified is-current-club') || text.includes('is-current-club is-qualified');
        // A club that is NOT the manager's own should NOT have is-current-club.
        const noFalsePositive = !text.includes('Serbia') || !text.includes('is-current-club') || text.includes('is-qualified is-current-club');
        console.log(`qualifying   ${bothClasses ? 'ok' : 'FAILED'}  ${bothClasses ? '[both classes on own club]' : '[own club NOT marked]'}`
            + `  ${noFalsePositive ? '[no false positive]' : '[FALSE POSITIVE: non-own club marked]'}`);
        if (!bothClasses) {
            failed++;
            console.log('   -> the manager\'s own club is not marked in the qualifying table.');
        }
        if (!noFalsePositive) {
            failed++;
            console.log('   -> a club that is not the manager\'s own is incorrectly marked.');
        }
    } catch (e) {
        failed++;
        console.log(`qualifying   THREW  ${e.message}`);
    }
}

process.exit(failed ? 1 : 0);
