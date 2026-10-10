// The readings on the two analytics panels.
//
// A number the manager cannot interpret is one he will not act on, so the sentence under each figure is
// the deliverable as much as the figure. These are the checks on those sentences.
import { readFileSync } from 'node:fs';

const view = readFileSync('src/main/resources/static/js/pages/views/match-view.js', 'utf8');

const failures = [];
const check = (name, condition, detail) => {
    if (condition) console.log(`ok   ${name}`);
    else { console.log(`FAIL ${name} — ${detail}`); failures.push(name); }
};

/** Pull the two reading functions out of the module and run them here. */
function readings() {
    const grab = (name) => {
        const start = view.indexOf(`function ${name}(`);
        if (start < 0) throw new Error(`${name} is not in match-view.js`);
        let depth = 0, i = view.indexOf('{', start);
        for (let j = i; j < view.length; j++) {
            if (view[j] === '{') depth++;
            else if (view[j] === '}') { depth--; if (depth === 0) return view.slice(start, j + 1); }
        }
        throw new Error(`${name} is unterminated`);
    };
    return new Function(`${grab('readFieldTilt')}\n${grab('readPpda')}\nreturn { readFieldTilt, readPpda };`)();
}

const { readFieldTilt, readPpda } = readings();

check('the readings exist', typeof readFieldTilt === 'function' && typeof readPpda === 'function',
    'a panel whose number has no reading is the defect this task exists to avoid');

// ── field tilt ───────────────────────────────────────────────────────────────────────────────────────
{
    const even = readFieldTilt(51, 49);
    check('an even match is described as even', /about as long/.test(even), even);
    check('an even match does not name a leader', !/tilt\./.test(even), even);

    const homeAhead = readFieldTilt(64, 36);
    check('a home tilt names the home side', /home side/i.test(homeAhead), homeAhead);
    check('and quotes the gap', /28-point/.test(homeAhead), homeAhead);

    const awayAhead = readFieldTilt(30, 70);
    check('an away tilt names the away side', /away side/i.test(awayAhead), awayAhead);
    check('and says it is the opposition third', /opposition's third/.test(awayAhead), awayAhead);
}

// ── PPDA ─────────────────────────────────────────────────────────────────────────────────────────────
{
    const homePressed = readPpda(18.4, 11.2);
    check('the higher PPDA is described as pressed harder', /Home was pressed harder/.test(homePressed), homePressed);
    check('and the gap is given', /7\.2 more passes/.test(homePressed), homePressed);

    const awayPressed = readPpda(9.1, 16.8);
    check('the direction follows the numbers, not the home/away assumption',
        /Away was pressed harder/.test(awayPressed), awayPressed);

    const even = readPpda(12.0, 12.3);
    check('an even PPDA says neither was pressed', /neither was put under sustained pressure/.test(even), even);
}

// ── the numbers themselves must never be invented ───────────────────────────────────────────────────
{
    const start = view.indexOf('function buildAnalyticsPanels(payload)');
    let depth = 0;
    let end = start;
    for (let j = view.indexOf('{', start); j < view.length; j++) {
        if (view[j] === '{') depth++;
        else if (view[j] === '}') { depth--; if (depth === 0) { end = j + 1; break; } }
    }
    const body = view.slice(start, end);

    check('a missing figure renders as a dash, not a zero',
        body.includes("=== null ? '—'"), 'a zero would read as a fact about the game');
    check('a match with neither number says it was not recorded',
        /were not recorded/.test(body),
        'silence would read as "this match had nothing to show" rather than "we did not measure it"');
    check('a figure that is missing while another is present is named',
        /missing\.push\('PPDA'\)/.test(body) && /Not recorded for this match/.test(body),
        'the 235 matches already stored carry field tilt and not PPDA; saying nothing about PPDA there '
        + 'would read as "nothing to show" rather than "not measured"');
    check('the definitions are rendered beside the figures',
        body.includes('tiltDefinition') && body.includes('definition'),
        'the denominator has to travel with the number');
}

console.log(failures.length === 0 ? '\nALL CHECKS PASSED' : `\n${failures.length} CHECK(S) FAILED`);
process.exit(failures.length === 0 ? 0 : 1);