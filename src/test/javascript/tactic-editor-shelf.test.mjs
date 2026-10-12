// Executes the tactic editor view against a stub DOM, so the page can be *run* rather than read.
//
// The owner's report was that the shelf rendered nothing useful and its buttons did nothing, and no
// syntax check would ever have said so: the file parsed perfectly and the listeners were bound to
// elements that did not exist. This harness reproduces that situation faithfully enough to catch it -
// it renders the view, then presses the buttons and watches what the page asks the server for.
//
// Run:  node src/test/javascript/tactic-editor-shelf.test.mjs
// Skipped by BrowserSourceSanityTest when node is absent.

import assert from 'node:assert/strict';

const VIEW = new URL('../../main/resources/static/js/pages/views/tactic-editor-view.js', import.meta.url)
    .pathname;

// ---------------------------------------------------------------- the DOM we hand the page

/** One fake element: it records listeners and can be clicked. */
function element(attributes = {}) {
    return {
        attributes,
        listeners: {},
        textContent: '',
        disabled: false,
        innerHTML: '',
        dataset: {},
        setAttribute(name, value) { this.attributes[name] = value; },
        getAttribute(name) { return this.attributes[name] ?? null; },
        addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); },
        removeEventListener() {},
        querySelectorAll() { return []; },
        querySelector() { return null; },
        closest() { return null; },
        /** Fires every handler bound to this event, in the order they were bound. */
        async click(event = {}) {
            for (const handler of this.listeners.click || []) {
                await handler({ preventDefault() {}, target: this, ...event });
            }
        }
    };
}

/**
 * The elements the page bound listeners to, keyed so that the page and this test get the **same**
 * objects. Without the registry each lookup would build a fresh fake element, the page would bind its
 * handlers to one set and this test would look at another, and every click would report "no handler" on
 * a page that is perfectly wired.
 */
const registry = new Map();

function resetRegistry() {
    registry.clear();
}

function cached(key, make) {
    if (!registry.has(key)) {
        registry.set(key, make());
    }
    return registry.get(key);
}

/** Parses just enough out of rendered HTML to hand back matching elements. */
function elementsFor(html, selector) {
    const attributeMatch = selector.match(/^\[([a-z-]+)\]$/);
    if (attributeMatch) {
        const [, name] = attributeMatch;
        const pattern = new RegExp(`${name}="([^"]*)"`, 'g');
        const found = [];
        let match;
        while ((match = pattern.exec(html)) !== null) {
            const value = match[1];
            found.push(cached(`${name}=${value}`, () => element({ [name]: value })));
        }
        return found;
    }
    const idMatch = selector.match(/^#([\w-]+)$/);
    if (idMatch) {
        const id = idMatch[1];
        return html.includes(`id="${id}"`)
            ? [cached(`id=${id}`, () => element({ id }))]
            : [];
    }
    return [];
}

function installDom() {
    const store = new Map();
    const main = element({ id: 'main-content' });
    let rendered = '';

    globalThis.localStorage = {
        getItem: key => (store.has(key) ? store.get(key) : null),
        setItem: (key, value) => store.set(key, String(value)),
        removeItem: key => store.delete(key),
        clear: () => store.clear()
    };

    // `prompt` is how the page asks for a tactic's name, `confirm` how it asks before deleting one.
    // Accept whatever the page offers as the default, which is what a manager pressing Enter does -
    // and it means the test asserts on the name the *page* proposed, not one this file invented.
    globalThis.prompt = (message, suggested) => (suggested === undefined ? 'Delta' : suggested);
    globalThis.confirm = () => true;

    globalThis.window = {
        alert: message => { store.set('__alert', message); },
        confirm: () => true,
        location: { search: '' }
    };

    globalThis.document = {
        getElementById(id) {
            if (id === 'main-content') return main;
            return elementsFor(rendered, `#${id}`)[0] || element();
        },
        querySelectorAll(selector) {
            return elementsFor(rendered, selector);
        },
        querySelector(selector) {
            return elementsFor(rendered, selector)[0] || null;
        },
        createElement: () => element(),
        body: element(),
        addEventListener: () => {}
    };

    // The page assigns innerHTML; capture it so queries after a paint see the new markup.
    Object.defineProperty(main, 'innerHTML', {
        get: () => rendered,
        // A repaint invalidates every element: the page re-binds after each render, and stale fakes
        // would carry handlers from a markup that no longer exists.
        set: value => { rendered = String(value); resetRegistry(); }
    });

    return { rendered: () => rendered };
}

// ---------------------------------------------------------------- what the server answers

const TACTICS_LIST = [
    { id: 11, name: 'Alpha', formation: '4-4-2', style: 'Balanced', isDefault: true, position: 0, maxTactics: 10, atCap: false },
    { id: 12, name: 'Bravo', formation: '4-3-3', style: 'Attacking', isDefault: false, position: 1, maxTactics: 10, atCap: false },
    { id: 13, name: 'Charlie', formation: '3-5-2', style: 'Defensive', isDefault: false, position: 2, maxTactics: 10, atCap: false }
];

let nextId = 14;

const EDITOR = {
    formation: '4-4-2',
    style: 'Balanced',
    version: 1,
    slotDefinitions: [
        { slotKey: 'GK', anchorCell: 'CELL_1_3', role: 'GK' },
        { slotKey: 'CML', anchorCell: 'CELL_3_2', role: 'MID' },
        { slotKey: 'CMR', anchorCell: 'CELL_3_4', role: 'MID' },
        { slotKey: 'STR', anchorCell: 'CELL_6_3', role: 'ATT' }
    ],
    supportedBallStates: ['CELL_3_3', 'CELL_4_3'],
    supportedTargetCells: ['CELL_1_3', 'CELL_3_2', 'CELL_6_3'],
    starterIds: [],
    benchIds: [],
    movementRules: [],
    setPieceAssignments: {},
    possessionContexts: ['WE_HAVE_BALL']
};

const requests = [];

function installServer() {
    return async function authFetch(url, options = {}) {
        const method = (options.method || 'GET').toUpperCase();
        requests.push({ method, url, body: options.body ? JSON.parse(options.body) : null });

        if (url.includes('/tactics-editor')) {
            return { ok: true, status: 200, json: async () => EDITOR };
        }
        if (url.includes('/match-lock')) {
            return { ok: true, status: 200, json: async () => ({ locked: false }) };
        }
        if (url.endsWith('/tactics') && method === 'GET') {
            return { ok: true, status: 200, json: async () => TACTICS_LIST };
        }

        // A real copy/mirror/save, so the library the page re-reads actually contains what it made.
        // A stub that accepted the write and returned an id the library has never heard of would make
        // the page look broken for a reason that is the stub's.
        const copyMatch = url.match(/\/tactics\/(\d+)\/copy$/);
        if (copyMatch && method === 'POST') {
            const source = TACTICS_LIST.find(t => t.id === Number(copyMatch[1]));
            const made = { ...source, id: nextId++, name: options.body ? JSON.parse(options.body).name : 'copy',
                isDefault: false, position: TACTICS_LIST.length };
            TACTICS_LIST.push(made);
            return { ok: true, status: 200, json: async () => made };
        }
        const mirrorMatch = url.match(/\/tactics\/(\d+)\/mirror$/);
        if (mirrorMatch && method === 'POST') {
            const source = TACTICS_LIST.find(t => t.id === Number(mirrorMatch[1]));
            const made = { ...source, id: nextId++, name: `${source.name} (mirrored)`,
                isDefault: false, position: TACTICS_LIST.length };
            TACTICS_LIST.push(made);
            return { ok: true, status: 200, json: async () => made };
        }
        if (/\/tactics\/\d+$/.test(url) && method === 'GET') {
            return { ok: true, status: 200, json: async () => ({ ...TACTICS_LIST[0], rulesJson: '[]', setPiecesJson: '{}' }) };
        }
        if (url.endsWith('/tactics') && method === 'POST') {
            // Save is keyed by name, exactly as the server does: (club, name) is unique.
            const body = JSON.parse(options.body);
            const existing = TACTICS_LIST.find(t => t.name === body.name);
            if (existing) {
                existing.formation = body.formation;
                existing.style = body.style;
                existing.rulesJson = body.rulesJson;
                return { ok: true, status: 200, json: async () => existing };
            }
            const made = { id: nextId++, name: body.name, formation: body.formation,
                style: body.style, isDefault: false, position: TACTICS_LIST.length };
            TACTICS_LIST.push(made);
            return { ok: true, status: 200, json: async () => made };
        }
        if (url.includes('/tactics')) {
            return { ok: true, status: 200, json: async () => ({ id: 14, name: 'Delta' }) };
        }
        return { ok: true, status: 200, json: async () => ({}) };
    };
}

// ---------------------------------------------------------------- the test itself

const dom = installDom();
const authFetch = installServer();

const { createTacticEditorView } = await import(VIEW);
const view = createTacticEditorView({
    authFetch,
    getTeamId: () => 1,
    buildClubActionsHtml: () => ''
});

await view.loadTacticEditor();

const html = dom.rendered();

assert.ok(html.length > 0, 'the page rendered nothing at all');
assert.match(html, /My tactics/, 'the shelf heading is missing: the manager cannot see their library');
assert.match(html, /Alpha/, 'a saved tactic is not listed by name');
assert.match(html, /Bravo/, 'the second tactic is not listed');
assert.match(html, /Charlie/, 'the third tactic is not listed');
assert.match(html, /New tactic/, 'there is no way to create a tactic under a new name');
assert.match(html, /Copy based on/, 'the copy does not say what it is based on');
assert.match(html, /Mirror/, 'there is no way to mirror a tactic');
assert.match(html, /Delete/, 'there is no way to delete a tactic');
assert.match(html, /3 of 10/, 'the page does not say how much of the cap is used');

// The list must be readable, not a row of small buttons: the owner's complaint about the first version
// was that the tactics in it could not be told apart.
assert.match(html, /te-tactic-row/, 'the tactics are not rendered as a list');
assert.match(html, /4-4-2 · Balanced/, 'a tactic does not say its shape and style');
// "Default 4-4-2  4-4-2 - BALANCED" is the repetition the owner pointed at: the shape is in the name
// already, so it must not be printed beside it as well.
assert.ok(!/Default 4-4-2\s*4-4-2/.test(html),
    'a tactic whose name already says its shape repeats it beside the name');
assert.match(html, /club default/, 'the club default is not marked');

// And "based on" is chosen, not assumed.
const sourceSelect = elementsFor(html, '#te-copy-source')[0];
assert.ok(sourceSelect, 'there is no dropdown choosing what the copy is based on');
assert.ok(html.includes('<select id="te-copy-source"'), 'the copy source dropdown is missing');

// The shelf belongs **below** the club frame, not above it: the owner's second note, showing the whole
// page with the shelf sitting on top of the header, the navigation and the Shape/Slots/Rules summary.
// Asserting the order is the only way to keep it there.
const shelfAt = html.indexOf('te-tactic-list');
const headerAt = html.indexOf('Tactic Editor');
const summaryAt = html.search(/<span>Slots<\/span>/);
assert.ok(shelfAt > 0, 'the shelf did not render at all');
assert.ok(headerAt >= 0 && summaryAt >= 0, 'the club frame did not render, so the order means nothing');
assert.ok(shelfAt > headerAt,
    'the shelf is above the club header; it belongs below the club frame');
assert.ok(shelfAt > summaryAt,
    'the shelf is above the Shape/Slots/Rules summary; it belongs below the club frame');

console.log('OK  the shelf renders as a readable list, below the club frame, with every operation');

// ---------------------------------------------------------------- the buttons do something

/**
 * The defect the owner reported, run for real: the shelf painted perfectly and every button did
 * nothing, because the listeners were bound before the markup existed. Parsing could never catch it.
 */

/**
 * A button from the markup as it stands **now**.
 *
 * Every shelf action reloads the page, which repaints and re-binds. A snapshot taken once would be stale
 * by the second click - and, because the registry hands back the same object the page bound to, asking
 * for a fresh one after a repaint would get an element with no handlers at all.
 */
const button = id => elementsFor(dom.rendered(), `#${id}`)[0];

/**
 * The owner's exact sequence, in order, because the order is the defect:
 * create a tactic, save it, **Copy (based on)**, change a slot, save.
 *
 * The page reloaded onto the *original* after the copy, so the save overwrote the original and the edit
 * was lost. No error anywhere - the save returned 200 both times.
 */

const sourceTactic = TACTICS_LIST[0];
const copyName = `${sourceTactic.name} copy`;

// ---- 1. copy
const copyButton = button('te-copy-btn');
assert.ok(copyButton && (copyButton.listeners.click || []).length > 0,
    'the Copy button has no click handler: it would look perfect and do nothing');
await copyButton.click();

const copyRequest = requests.filter(r => r.url.includes('/copy')).pop();
assert.ok(copyRequest, 'clicking Copy asked the server for nothing');
assert.equal(copyRequest.body.name, copyName, 'the copy should be named in the popup');
assert.ok(copyRequest.url.includes(`/tactics/${sourceTactic.id}/copy`),
    `the copy should be based on the tactic that is open; asked for ${copyRequest.url}`);

// ---- 2. change a slot and save
const saveButton = button('te-save-btn');
assert.ok(saveButton && (saveButton.listeners.click || []).length > 0,
    'the Save button has no click handler');
await saveButton.click();

const saveRequest = requests.filter(r => r.method === 'POST' && r.url.endsWith('/tactics')
    && !r.url.includes('/copy') && !r.url.includes('/mirror')).pop();
assert.ok(saveRequest, 'Save never reached the server');
assert.equal(saveRequest.body.name, copyName,
    'Save wrote to "' + saveRequest.body.name + '" instead of the copy that was just made: '
    + 'the edit was lost and the tactic it was copied from was overwritten');
assert.ok('rulesJson' in saveRequest.body, 'Save sent no movement rules');
assert.notEqual(saveRequest.body.name, sourceTactic.name,
    'the original was overwritten, which is how the edit was lost the first time');

console.log('OK  an edit made after Copy is saved to the copy, not to the tactic it came from');

// ---- 3. the other two operations, on whatever is now open
await button('te-mirror-btn').click();
assert.ok(requests.some(r => r.url.endsWith('/mirror')),
    'clicking Mirror asked the server for nothing');

await button('te-delete-btn').click();
assert.ok(requests.some(r => r.method === 'DELETE'),
    'clicking Delete asked the server for nothing');

console.log('OK  Copy, Mirror and Delete each issue their request when clicked');
