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
    // Answer both without stopping the run.
    globalThis.prompt = () => 'Delta';
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

const TACTICS = [
    { id: 11, name: 'Alpha', formation: '4-4-2', style: 'Balanced', isDefault: true, position: 0, maxTactics: 10, atCap: false },
    { id: 12, name: 'Bravo', formation: '4-3-3', style: 'Attacking', isDefault: false, position: 1, maxTactics: 10, atCap: false },
    { id: 13, name: 'Charlie', formation: '3-5-2', style: 'Defensive', isDefault: false, position: 2, maxTactics: 10, atCap: false }
];

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
            return { ok: true, status: 200, json: async () => TACTICS };
        }
        if (/\/tactics\/\d+$/.test(url) && method === 'GET') {
            return { ok: true, status: 200, json: async () => ({ ...TACTICS[0], rulesJson: '[]', setPiecesJson: '{}' }) };
        }
        if (url.includes('/tactics')) {
            // Any write: accepted. The page should reload afterwards.
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
assert.match(html, /New/, 'there is no way to create a tactic under a new name');
assert.match(html, /Copy \(based on\)/, 'there is no way to copy a tactic');
assert.match(html, /Mirror/, 'there is no way to mirror a tactic');
assert.match(html, /Delete/, 'there is no way to delete a tactic');
assert.match(html, /3 of 10/, 'the page does not say how much of the cap is used');

console.log('OK  the shelf renders with every tactic, every operation and the cap');

// ---------------------------------------------------------------- the buttons do something

/**
 * The defect the owner reported, run for real: the shelf painted perfectly and every button did
 * nothing, because the listeners were bound before the markup existed. Parsing could never catch it.
 */

const copyButton = elementsFor(html, '#te-copy-btn')[0];
assert.ok(copyButton, 'the Copy button is not in the markup');
assert.ok((copyButton.listeners.click || []).length > 0,
    'the Copy button has no click handler: it would look perfect and do nothing');
await copyButton.click();
assert.ok(requests.some(r => r.url.includes('/tactics/11/copy') && r.method === 'POST'),
    'clicking Copy asked the server for nothing');

const mirrorButton = elementsFor(html, '#te-mirror-btn')[0];
assert.ok(mirrorButton && (mirrorButton.listeners.click || []).length > 0,
    'the Mirror button has no click handler');
await mirrorButton.click();
assert.ok(requests.some(r => r.url.includes('/tactics/11/mirror')),
    'clicking Mirror asked the server for nothing');

const deleteButton = elementsFor(html, '#te-delete-btn')[0];
assert.ok(deleteButton && (deleteButton.listeners.click || []).length > 0,
    'the Delete button has no click handler');
await deleteButton.click();
assert.ok(requests.some(r => r.method === 'DELETE' && r.url.includes('/tactics/11')),
    'clicking Delete asked the server for nothing');

console.log('OK  Copy, Mirror and Delete each issue their request when clicked');
