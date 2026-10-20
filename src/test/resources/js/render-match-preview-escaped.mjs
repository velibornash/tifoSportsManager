// Renders the real match-preview dashboard with a hostile payload and prints what reached the DOM.
//
// What this proves: the preview page builds its HTML by assigning a template literal to
// `content.innerHTML`, and the strings in it come from the server. A club name, a formation, a
// headline and a player's name all went into that innerHTML unescaped, because the helper that
// wrapped them (`safe`) only coalesced nulls. A club named `<img src=x onerror=...>` was therefore
// markup rather than text.
//
// The harness loads the REAL file from disk and calls the REAL renderer, so the only thing it
// measures is the code as it is. If the escaping is removed, the raw tag comes back out and the
// Java test that drives this goes red.
import { readFileSync } from 'node:fs';

const jsDir = 'src/main/resources/static/js';
const strip = (src) => src
    .replace(/^import[^;]*;$/gm, '')
    .replace(/^export /gm, '');

const escape = strip(readFileSync(`${jsDir}/ui/escape.js`, 'utf8'));
const preview = strip(readFileSync(`${jsDir}/zox-match-preview.js`, 'utf8'));

const sandbox = `
// The real escapeHtml, from the real ui/escape.js.
${escape}
${preview}
// The two imports the module brings in, stubbed. Neither is reached by renderDashboard, but the
// names must resolve or the module cannot be evaluated at all.
const authFetch = async () => ({ ok: true, status: 200, json: async () => ({}) });
const readJsonOr = async () => ({});
globalThis.__render = renderDashboard;
`;

// The DOM the module writes into.
const content = { innerHTML: '' };
const noQuery = { querySelector: () => null, querySelectorAll: () => [] };
globalThis.document = Object.assign({
    getElementById: (id) => (id === 'zox-content' ? content : null),
    addEventListener: () => {},
}, noQuery);
globalThis.window = { alert: () => {}, location: { search: '', href: '' } };
globalThis.localStorage = { getItem: () => null, setItem: () => {} };

const fn = new Function(sandbox + '\nreturn __render;');
const renderDashboard = fn();

// The payload a hostile club name would produce. Every field here is a plain string the server
// controls, and every one of them ends up in innerHTML.
const HOSTILE = '<img src=x onerror="window.__pwned=1">';
const previewPayload = {
    homeTeamName: HOSTILE,
    awayTeamName: 'Away FC',
    homeTeamRating: 72,
    awayTeamRating: 68,
    homeRecentForm: 'W W D',
    awayRecentForm: 'L D W',
    homeFormation: HOSTILE,
    awayFormation: '4-4-2',
    homePlayStyle: 'Attacking',
    awayPlayStyle: 'Defensive',
    expectedResult: 'HOME',
    expectedHomeGoals: 1.8,
    expectedAwayGoals: 1.1,
    homeBenchQuality: 6.5,
    awayBenchQuality: 6.0,
    homeFormationFitness: 0.8,
    awayFormationFitness: 0.7,
    homeWinProbability: 0.5,
    drawProbability: 0.25,
    awayWinProbability: 0.25,
    homePositionMismatches: 1,
    awayPositionMismatches: 0,
    analysisText: HOSTILE,
    homeLineup: [{ squadNumber: 9, name: HOSTILE, position: 'ST', rating10: 7.4 }],
    awayLineup: [{ squadNumber: 4, name: 'Away Player', position: 'CB', rating10: 6.9 }],
    homeInsights: [{ title: HOSTILE, detail: 'detail' }],
    awayInsights: [],
    homeAbsentees: [{ name: HOSTILE, reason: 'injury' }],
    awayAbsentees: [],
    predictionReasons: [{ reason: HOSTILE, probability: 0.4 }],
};

const statsPayload = {
    homePossession: 55, awayPossession: 45,
    homeExpectedGoals: 1.8, awayExpectedGoals: 1.1,
    homeShotsOnTarget: 6, awayShotsOnTarget: 3,
    homeShotsOffTarget: 4, awayShotsOffTarget: 7,
    homePassAccuracy: 86, awayPassAccuracy: 81,
    homeCorners: 7, awayCorners: 3,
    homeFouls: 9, awayFouls: 11,
    homeYellowCards: 1, awayYellowCards: 2,
    homeRedCards: 0, awayRedCards: 0,
    homeOffside: 1, awayOffside: 2,
    homePasses: 512, awayPasses: 401,
    homeTackles: 12, awayTackles: 15,
};

const reportPayload = {
    homeTopPerformers: [{ playerName: HOSTILE, position: 'ST', rating10: 8.1, summary: HOSTILE }],
    awayTopPerformers: [],
    timeline: [{ minute: 23, icon: '⚽', title: HOSTILE, teamName: HOSTILE, detail: HOSTILE }],
    headline: HOSTILE,
    summary: HOSTILE,
    tacticalVerdict: HOSTILE,
    turningPoint: HOSTILE,
};

renderDashboard(previewPayload, statsPayload, reportPayload);

console.log('[rendered] ' + content.innerHTML);
console.log('[raw-tag-count] ' + (content.innerHTML.match(/<img src=x/gi) || []).length);
console.log('[escaped-count] ' + (content.innerHTML.match(/&lt;img src=x/gi) || []).length);
console.log('[script-count] ' + (content.innerHTML.match(/<script/gi) || []).length);
