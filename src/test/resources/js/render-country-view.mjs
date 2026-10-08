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
const view_ = factory({
    authFetch: async (path) => {
        if (path.includes('friendly-requests/opponents')) return json([{ id: 9, name: 'Opponent', country: 'X' }]);
        if (path.includes('friendly-requests/slot')) return json({ week: 6, day: 1 });
        if (path.match(/friendly-requests\/\d+$/)) return json({ season: 1, week: 6, incoming: [], outgoing: [] });
        if (path.includes('/national-team')) return json(seniorNt);
        if (path.includes('/clubs/ranking')) return json({ totalClubs: 2, clubs: [] });
        if (path.includes('/leagues')) return json([]);
        if (path.includes('/calendar/week')) return json({ days: [] });
        if (path.includes('/calendar/season')) return json({ weeks: [] });
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
process.exit(failed ? 1 : 0);
