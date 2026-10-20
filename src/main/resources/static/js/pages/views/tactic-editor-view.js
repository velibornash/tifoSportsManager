// pages/views/tactic-editor-view.js
import { htmlEscape, fetchMatchLock, renderMatchLockBanner, isPageLocked } from './utils.js';

// The library: the club's tactics, the cap, and the three operations Sokker has (owner, 2026-10-11).
// "Ten a club, three of them for a match" - the cap is the owner's and the server repeats it in every
// refusal, so the page shows it rather than letting a manager fill in a form that cannot save.
async function loadLibrary(teamId, authFetch) {
    try {
        const response = await authFetch(`/teams/${teamId}/tactics`);
        if (!response || !response.ok) return { tactics: [], maxTactics: 10, atCap: false };
        const tactics = await response.json();
        const list = Array.isArray(tactics) ? tactics : [];
        const first = list[0] || {};
        return {
            tactics: list,
            maxTactics: first.maxTactics || 10,
            atCap: !!first.atCap
        };
    } catch (_) {
        // The editor must still draw without the shelf. A library that will not load is a missing
        // convenience; a blank tactic editor is a broken page.
        return { tactics: [], maxTactics: 10, atCap: false, failed: true };
    }
}

/**
 * A tactic's shape and style, unless its name already says the shape.
 *
 * <p>The owner's first tactic is called *"Default 4-4-2"*, and the row read
 * `Default 4-4-2   4-4-2 · BALANCED` — the shape twice, once as the name and once beside it. Repetition
 * makes a list longer than it needs to be and reads as a rendering fault.
 */
function shapeOf(tactic) {
    const formation = htmlEscape(tactic.formation || '');
    const style = tactic.style ? htmlEscape(tactic.style) : '';
    const name = (tactic.name || '');
    const saysShape = formation && name.includes(formation);
    if (saysShape) {
        return style || formation;
    }
    return style ? `${formation} · ${style}` : formation;
}

function renderLibraryShelf(library, selectedId, locked) {
    // A list, not a row of buttons. The owner's words on the first version: *"treba jasnije da stoji
    // lista taktika, ovo sa ovim zatamnjenim pomalo blurovanim dugmadima nije vidljivo"* — the row of
    // small buttons was hard to read and the tactics in it were hard to tell apart. Each tactic is now a
    // row that says what it is: its name, its shape, and whether it is the club default.
    const rows = library.tactics.map(t => `
        <button type="button" class="te-tactic-row${t.id === selectedId ? ' is-selected' : ''}"
                data-te-pick="${t.id}" aria-current="${t.id === selectedId}">
            <span class="te-tactic-name">${htmlEscape(t.name)}</span>
            <span class="te-tactic-shape">${shapeOf(t)}</span>
            ${t.isDefault ? '<span class="te-tactic-flag">club default</span>' : ''}
            ${t.id === selectedId ? '<span class="te-tactic-flag is-open">editing this one</span>' : ''}
        </button>`).join('');

    const disabled = locked ? ' disabled' : '';
    const capNote = library.atCap
        ? `<p class="fm-subtle">All ${library.maxTactics} tactics are in use. Delete one, or edit an
             existing tactic by saving over its name.</p>`
        : `<p class="fm-subtle">${library.tactics.length} of ${library.maxTactics} tactics.</p>`;

    // "Based on" is chosen, not assumed. Copying from the wrong tactic was half of what made the save
    // land somewhere surprising, so the source is a dropdown the manager can see and change.
    const sourceOptions = library.tactics.map(t =>
        `<option value="${t.id}"${t.id === selectedId ? ' selected' : ''}>${htmlEscape(t.name)}</option>`).join('');

    return `
        <section class="fm-panel te-tactic-shelf" style="margin-bottom:16px;">
            <div class="fm-panel-head"><div>
                <h3>My tactics</h3>
                ${capNote}
            </div></div>
            <div class="te-tactic-list" role="list">
                ${rows || '<p class="fm-subtle">No tactics yet — create one below.</p>'}
            </div>
            <div class="te-tactic-actions">
                <button type="button" id="te-new-btn" class="big-button"${disabled}${library.atCap ? ' disabled' : ''}>New tactic</button>
                <label class="fm-subtle" style="display:flex; align-items:center; gap:6px;">
                    Copy based on
                    <select id="te-copy-source" class="fm-input"${disabled || library.tactics.length ? '' : ' disabled'}>
                        ${sourceOptions}
                    </select>
                </label>
                <button type="button" id="te-copy-btn" class="big-button"${disabled}>Copy</button>
                <button type="button" id="te-mirror-btn" class="big-button"${disabled}>Mirror</button>
                <!--
                    Paste is the cross-club half of Copy/Paste (T-MATCH-3c), and it is its own button
                    rather than a wider Copy dropdown on purpose: Copy duplicates one of *your* tactics and
                    refuses anything else, and a control that silently accepts a stranger's tactic is not
                    one to widen. Paste is named, and that is what makes reading another club's setup a
                    decision rather than an accident.
                -->

                <button type="button" id="te-delete-btn" class="big-button"${disabled}>Delete</button>
            </div>
            <p class="fm-subtle" style="margin-top:8px;">
                Saving stores your changes into the tactic you are editing. Copy creates a new one from the
                tactic you pick, so you can change it without touching the original.
            </p>
            ${library.failed ? '<p class="fm-subtle" style="margin-top:8px;">The tactic list could not be loaded. Editing still works; the shelf is unavailable.</p>' : ''}
            <p id="te-shelf-message" class="fm-subtle" style="margin-top:8px;"></p>
        </section>`;
}

/**
 * The tactic the grid is editing, remembered between loads of the page.
 *
 * Module scope rather than a local, because every shelf action reloads the view and a local would be
 * gone by then — which is exactly the bug it exists to fix.
 */
let preferredTacticId = null;

export function createTacticEditorView(deps) {
    const { authFetch, getTeamId, buildClubActionsHtml } = deps;

    async function loadTacticEditor() {
        const mainContent = document.getElementById("main-content");
        const POSSESSION_CONTEXT = 'WE_HAVE_BALL'; // Koristimo samo jednu varijantu
        const availableStyles = ["BALANCED", "ATTACKING", "DEFENSIVE", "COUNTER", "POSSESSION", "HIGH_PRESS", "DIRECT"];
        const availableFormations = ["4-4-2", "4-3-3", "4-2-3-1", "4-1-4-1", "4-5-1", "3-5-2", "3-4-3", "3-4-2-1", "5-3-2", "5-4-1"];

        // An hour before kickoff the match has been computed. The server refuses the save with 423
        // either way; this asks first so the page can say why, rather than letting a manager find out
        // by pressing Save and being told "locked" with no explanation.
        const matchLock = await fetchMatchLock(authFetch, getTeamId());
        const locked = isPageLocked(matchLock);

        // **The library is read before the editor grid, because the grid belongs to one tactic.**
        //
        // It used to be the other way round: the grid was loaded from the club-level tactics profile
        // and the tactic was picked afterwards, so the shape on screen belonged to the club while the
        // name in the corner belonged to a tactic. The owner asked for the opposite — "4-3-3 in one
        // tactic, 4-4-2 in the other" (2026-10-16) — which cannot be true while the shape is read from
        // a club-level endpoint. So the tactic is settled first, and its own formation is what the grid
        // is asked for below.
        const library = await loadLibrary(getTeamId(), authFetch);
        // **The tactic the manager is on survives a reload.** It used to snap back to the first in the
        // library, which meant that after "Copy (based on)" the page reloaded onto the *original* — and
        // the next Save wrote his changes back over the original, leaving the copy untouched. That is
        // silent loss of an edit, and the manager's report was "the change is not remembered".
        const stillThere = preferredTacticId !== null
            && library.tactics.some(t => t.id === preferredTacticId);
        const focusedId = stillThere ? preferredTacticId
            : (library.tactics.length ? library.tactics[0].id : null);
        const focused = library.tactics.find(t => t.id === focusedId);
        let selectedTacticId = focusedId;
        let workingName = focused ? focused.name : '';

        // **One draft per tactic, not one per club.** A single `te_draft_{club}` key meant a draft
        // written while editing one tactic was loaded onto whichever tactic was opened next — the exact
        // leak the owner is describing, and the reason a shape changed in one tactic and appeared in
        // another. The key names the tactic, so the two drafts cannot meet.
        const DRAFT_KEY = focusedId != null ? `te_draft_${getTeamId()}_${focusedId}` : `te_draft_${getTeamId()}_none`;
        let draft = null;
        try { const raw = localStorage.getItem(DRAFT_KEY); if (raw) draft = JSON.parse(raw); } catch (_) { draft = null; }

        // **The shape asked for is this tactic's own.** The endpoint still returns slot anchors for a
        // formation, which is what the grid draws, so it is asked for the tactic's shape rather than
        // the club's default. A draft wins: a manager who changed the shape and has not saved yet must
        // still see the shape he changed it to.
        const shapeForGrid = (draft && draft.formation) || (focused && focused.formation) || '';
        // **Which tactic is being edited goes to the server too.** Save writes the chosen tactic, so
        // the page has to READ that same tactic back. It used to read the club's single profile
        // instead, so a moved player came back where he was - owner, 2026-10-19: "kad izmenim POZICIJU
        // IGRAČA u odnosu na POZICIJU LOPTE, TE POZICIJE treba da sačuva". Measured one save apart:
        // the library had CELL_1_5 and the page loaded CELL_4_1.
        //
        // Without a tacticId the endpoint still answers from the club profile, which is what the
        // reset snapshot and the world-default button need and must keep getting.
        const params = new URLSearchParams();
        if (shapeForGrid) params.set('formation', shapeForGrid);
        if (focusedId != null) params.set('tacticId', String(focusedId));
        const query = params.toString();
        const editorUrl = `/teams/${getTeamId()}/tactics-editor${query ? '?' + query : ''}`;

        const editorRes = await authFetch(editorUrl);
        const editor = await editorRes.json();

        const slots = Array.isArray(editor.slotDefinitions) ? editor.slotDefinitions : [];
        const slotKeys = slots.map(slot => slot.slotKey);
        const slotByKey = new Map(slots.map(slot => [slot.slotKey, slot]));
        const ballStates = Array.isArray(editor.supportedBallStates) ? editor.supportedBallStates : [];
        const targetCells = Array.isArray(editor.supportedTargetCells) ? editor.supportedTargetCells : [];
        const cornerStates = ['ATTACK_LEFT_CORNER', 'ATTACK_RIGHT_CORNER', 'DEFEND_LEFT_CORNER', 'DEFEND_RIGHT_CORNER'].filter(stateKey => ballStates.includes(stateKey));
        const centerBallState = ballStates.includes('CELL_2_2') ? 'CELL_2_2' : (ballStates[0] || 'CELL_2_2');
        const currentStarterIds = Array.isArray(editor.starterIds) ? editor.starterIds : [];
        const currentBenchIds = Array.isArray(editor.benchIds) ? editor.benchIds : [];

        const buildRulesMap = (rules) => {
            const map = {};
            (rules || []).forEach(rule => {
                if (rule?.slotKey && rule?.ballStateKey && rule?.possessionContext && rule?.targetCellKey) {
                    map[`${rule.slotKey}|${rule.ballStateKey}|${rule.possessionContext}`] = rule.targetCellKey;
                }
            });
            return map;
        };

        // **A draft for this tactic is this tactic's unsaved work, whatever shape it was written in.**
        //
        // The test used to be `draft.formation === editor.formation`, i.e. "keep the draft only if the
        // shape did not change" — which meant changing the shape threw the draft away, rules and all,
        // and the panel came back showing the shape the tactic was saved with. The version check stayed
        // as it is: it still means "the draft is not older than what the server has".
        const useDraft = !!(draft && typeof draft.draftVersion === 'number' && draft.draftVersion >= (editor.version || 0));
        const setPiecesSource = useDraft ? (draft.setPieceAssignments || editor.setPieceAssignments || {}) : (editor.setPieceAssignments || {});

        // **The shape and the style come from this tactic, not from the club.**
        //
        // The endpoint answers with the club's own formation and style when nothing is asked for, so
        // reading them from there is what made 4-3-3 in one tactic and 4-4-2 in the other impossible:
        // both would open showing the club's. The tactic's own values win, a draft for this tactic wins
        // over them, and the endpoint is only the last resort for a club with no tactics yet.
        const state = {
            formation: (useDraft ? (draft.formation || null) : null)
                || (focused && focused.formation)
                || editor.formation,
            style: (useDraft ? (draft.style || null) : null)
                || (focused && focused.style)
                || editor.style,
            rulesMap: buildRulesMap(useDraft ? draft.movementRules : editor.movementRules),
            setPieces: {
                penaltyTakerSlot: setPiecesSource.penaltyTakerSlot || '',
                freeKickLeftTakerSlot: setPiecesSource.freeKickLeftTakerSlot || '',
                freeKickRightTakerSlot: setPiecesSource.freeKickRightTakerSlot || '',
                cornerLeftTakerSlot: setPiecesSource.cornerLeftTakerSlot || '',
                cornerRightTakerSlot: setPiecesSource.cornerRightTakerSlot || '',
            },
            draftVersion: useDraft ? draft.draftVersion : (editor.version || 0),
            activeBallState: useDraft && ballStates.includes(draft.activeBallState) ? draft.activeBallState : centerBallState,
            focusedSlot: useDraft && slotKeys.includes(draft.focusedSlot) ? draft.focusedSlot : (slotKeys[0] || ''),
        };

        const serializeRules = () => Object.entries(state.rulesMap).map(([compoundKey, targetCellKey]) => {
            const [slotKey, ballStateKey, possessionContext] = compoundKey.split('|');
            return { slotKey, ballStateKey, possessionContext: possessionContext || POSSESSION_CONTEXT, targetCellKey };
        });

        const saveDraft = () => {
            try {
                localStorage.setItem(DRAFT_KEY, JSON.stringify({
                    formation: state.formation, style: state.style,
                    movementRules: serializeRules(), setPieceAssignments: state.setPieces,
                    draftVersion: state.draftVersion,
                    activeBallState: state.activeBallState, focusedSlot: state.focusedSlot,
                }));
            } catch (_) {}
        };

        const getRule = (slotKey, ballStateKey) => state.rulesMap[`${slotKey}|${ballStateKey}|${POSSESSION_CONTEXT}`] || '';
        const getSlotTarget = (slotKey, ballStateKey) => getRule(slotKey, ballStateKey) || slotByKey.get(slotKey)?.anchorCellKey || 'CELL_2_2';
        const setRule = (slotKey, ballStateKey, targetCellKey) => {
            const key = `${slotKey}|${ballStateKey}|${POSSESSION_CONTEXT}`;
            if (targetCellKey) state.rulesMap[key] = targetCellKey;
            else delete state.rulesMap[key];
            saveDraft();
        };

        const clearFocusedRule = () => { if (state.focusedSlot && state.activeBallState) setRule(state.focusedSlot, state.activeBallState, ''); };
        const clearFocusedSlotRules = () => { Object.keys(state.rulesMap).forEach(key => { if (key.startsWith(`${state.focusedSlot}|`)) delete state.rulesMap[key]; }); saveDraft(); };
        const hasDraft = () => useDraft;

        /**
         * Is what is on screen different from what the server has?
         *
         * The old answer was `useDraft` — a boolean fixed once, when the page loaded. That is a
         * snapshot of "a draft existed at load time", not of "there are unsaved changes", and it was
         * wrong in both directions, which is exactly what the owner saw:
         *
         *   - an edit never set it, so the panel kept reading **Saved** with unsaved work on screen;
         *   - a successful save calls `saveDraft()` and therefore CREATES a draft, while `useDraft`
         *     stays whatever it was, so clicking Save left the panel reading **Unsaved changes**.
         *
         * A draft is a browser-side copy that exists precisely so a reload does not lose work, so its
         * presence says nothing about whether the server agrees. The only honest question is whether
         * the editable content differs from the content the server last gave us, so that is what is
         * compared. It needs no localStorage at all, and it cannot get stuck in either state.
         */
        const editableSignature = (s) => JSON.stringify({
            formation: s.formation || '',
            style: s.style || '',
            rules: serializeRules(),
            setPieces: s.setPieces || {}
        });
        // Captured before any edit, from the state as the server (or, when a draft wins, the draft
        // and the server together) left it. A draft that is newer than the server therefore reads as
        // dirty on arrival, which is the correct and useful thing for it to do.
        // Built from what the SERVER has, not from `state` — and that is the whole point.
        //
        // `state` has already had the draft merged into it, so signing `state` would say a restored
        // draft is clean, when the server has never seen it. The draft exists exactly because the
        // server has NOT got it, so arriving with one is arriving with unsaved changes, and the panel
        // must say so.
        const serverRulesMap = buildRulesMap(editor.movementRules);
        // `let`, not `const`: markSaved() writes the new signature back into it, and a `const`
        // made every save throw "invalid assignment to const 'savedSignature'" - the tactic editor
        // could not save at all (owner, 2026-10-19).
        let savedSignature = JSON.stringify({
            formation: (focused && focused.formation) || editor.formation || '',
            style: ((focused && focused.style) || editor.style || ''),
            rules: Object.entries(serverRulesMap).map(([compoundKey, targetCellKey]) => {
                const [slotKey, ballStateKey, possessionContext] = compoundKey.split('|');
                return { slotKey, ballStateKey, possessionContext: possessionContext || POSSESSION_CONTEXT, targetCellKey };
            }),
            setPieces: (editor.setPieceAssignments || {})
        });
        const isDirty = () => editableSignature(state) !== savedSignature;
        const markSaved = () => { savedSignature = editableSignature(state); };
        const parseCellKey = (cellKey) => { const match = String(cellKey || '').match(/^CELL_([0-6])_([0-5])$/); return match ? [Number(match[1]), Number(match[2])] : [3, 2]; };
        const cellLabel = (key) => {
            if (!key) return '—';
            if (key === 'ATTACK_LEFT_CORNER') return 'Attack left corner';
            if (key === 'ATTACK_RIGHT_CORNER') return 'Attack right corner';
            if (key === 'DEFEND_LEFT_CORNER') return 'Defend left corner';
            if (key === 'DEFEND_RIGHT_CORNER') return 'Defend right corner';
            const [progress, width] = parseCellKey(key);
            return `${['DEF', 'DEF+', 'MID', 'MID+', 'ATK+', 'ATK', 'ATK+'][progress]} · ${['L', 'CL', 'C', 'CR', 'R', 'RR'][width]}`;
        };
        const toPitchPosition = (cellKey) => { const [progress, width] = parseCellKey(cellKey); return { left: `${(width + 0.5) * (100/6)}%`, top: `${(6.5 - progress) * (100/7)}%` }; };
        const focusedSlotMeta = () => slotByKey.get(state.focusedSlot) || null;

        const buildCellZonesHtml = () => targetCells.map(cellKey => {
            const [progress, width] = parseCellKey(cellKey);
            const row = 6 - progress;
            const isActiveBall = state.activeBallState === cellKey;
            return `<div class="te-drop-cell ${isActiveBall ? 'is-ball-active' : ''}" data-te-drop="cell" data-ball-state="${cellKey}" data-target-cell="${cellKey}" style="--te-col:${width}; --te-row:${row};"><span class="te-cell-label">${htmlEscape(cellLabel(cellKey))}</span></div>`;
        }).join('');

        const cornerMeta = { ATTACK_LEFT_CORNER: { title: 'Atk left corner', cls: 'is-attack-left' }, ATTACK_RIGHT_CORNER: { title: 'Atk right corner', cls: 'is-attack-right' }, DEFEND_LEFT_CORNER: { title: 'Def left corner', cls: 'is-defend-left' }, DEFEND_RIGHT_CORNER: { title: 'Def right corner', cls: 'is-defend-right' } };
        const buildCornerZonesHtml = () => cornerStates.map(ballStateKey => `
            <div class="te-corner-zone ${cornerMeta[ballStateKey]?.cls || ''} ${state.activeBallState === ballStateKey ? 'is-ball-active' : ''}" data-te-drop="corner" data-ball-state="${ballStateKey}">
                <span>${htmlEscape(cornerMeta[ballStateKey]?.title || ballStateKey)}</span>
                ${state.activeBallState === ballStateKey ? `<div class="te-ball-marker" draggable="true" data-te-ball="true" title="Drag ball to change ball state"></div>` : ''}
            </div>`).join('');

        const buildMarkerOffsets = (memberCount, reserveCenter) => {
            const defaultOffsets = { 1: [{ x: 0, y: 0 }], 2: [{ x: -18, y: 0 }, { x: 18, y: 0 }], 3: [{ x: 0, y: -16 }, { x: -18, y: 14 }, { x: 18, y: 14 }], 4: [{ x: -18, y: -14 }, { x: 18, y: -14 }, { x: -18, y: 14 }, { x: 18, y: 14 }], many: [{ x: 0, y: 0 }, { x: -18, y: 0 }, { x: 18, y: 0 }, { x: 0, y: -18 }, { x: 0, y: 18 }, { x: -18, y: -18 }, { x: 18, y: -18 }, { x: -18, y: 18 }, { x: 18, y: 18 }, { x: -30, y: 0 }, { x: 30, y: 0 }] };
            const ballSafeOffsets = { 1: [{ x: -18, y: 0 }], 2: [{ x: -18, y: 0 }, { x: 18, y: 0 }], 3: [{ x: -18, y: -12 }, { x: 18, y: -12 }, { x: 0, y: 18 }], 4: [{ x: -18, y: -12 }, { x: 18, y: -12 }, { x: -18, y: 16 }, { x: 18, y: 16 }], many: [{ x: -18, y: 0 }, { x: 18, y: 0 }, { x: -18, y: 16 }, { x: 18, y: 16 }, { x: -18, y: -16 }, { x: 18, y: -16 }, { x: 0, y: 24 }, { x: 0, y: -24 }, { x: -30, y: 0 }, { x: 30, y: 0 }, { x: 0, y: 36 }] };
            const source = reserveCenter ? ballSafeOffsets : defaultOffsets;
            return source[memberCount] || source.many.slice(0, memberCount);
        };

        const buildBallMarkerHtml = () => { if (!targetCells.includes(state.activeBallState)) return ''; const pos = toPitchPosition(state.activeBallState); return `<div class="te-ball-marker" draggable="true" data-te-ball="true" style="left:${pos.left}; top:${pos.top};" title="Drag ball to change ball state"></div>`; };

        const buildPlayerMarkersHtml = () => {
            const byCell = {};
            slots.forEach(slot => { const cellKey = getSlotTarget(slot.slotKey, state.activeBallState); if (!byCell[cellKey]) byCell[cellKey] = []; byCell[cellKey].push(slot); });
            return Object.entries(byCell).flatMap(([cellKey, members]) => {
                const pos = toPitchPosition(cellKey);
                const offsets = buildMarkerOffsets(members.length, cellKey === state.activeBallState);
                return members.sort((left, right) => Number(left.order || 0) - Number(right.order || 0)).map((slot, idx) => {
                    const offset = offsets[idx] || { x: idx % 2 === 0 ? -30 : 30, y: 18 * Math.floor(idx / 2) };
                    const isFocused = slot.slotKey === state.focusedSlot;
                    return `<button type="button" class="te-player-marker ${isFocused ? 'is-focused' : ''}" draggable="true" data-te-slot-marker="${slot.slotKey}" style="left:${pos.left}; top:${pos.top}; --te-offset-x:${offset.x}px; --te-offset-y:${offset.y}px;" title="${htmlEscape(slot.slotKey)} · ${htmlEscape(cellLabel(cellKey))}"><span class="te-player-marker-label">${htmlEscape(slot.slotKey)}</span></button>`;
                });
            }).join('');
        };

        const renderSetPieceSelects = () => {
            const renderSelect = (field, label) => {
                const options = ['', ...slotKeys].map(slotKey => `<option value="${htmlEscape(slotKey)}" ${state.setPieces[field] === slotKey ? 'selected' : ''}>${htmlEscape(slotKey || '-- None --')}</option>`).join('');
                return `<label class="te-sp-label">${htmlEscape(label)}<select class="te-sp-select" data-sp-field="${field}">${options}</select></label>`;
            };
            return `<div class="te-sp-grid">
                ${renderSelect('penaltyTakerSlot', 'Penalty')}
                ${renderSelect('freeKickLeftTakerSlot', 'FK Left')}
                ${renderSelect('freeKickRightTakerSlot', 'FK Right')}
                ${renderSelect('cornerLeftTakerSlot', 'Corner Left')}
                ${renderSelect('cornerRightTakerSlot', 'Corner Right')}
            </div>`;
        };

        const readDragPayload = (event) => { try { return JSON.parse(event.dataTransfer?.getData('text/plain') || '{}'); } catch (_) { return null; } };

        /** "New tactic", "New tactic 2", ... - the server refuses duplicates, so the page avoids asking. */
        const nextFreeName = (lib, base) => {
            const taken = new Set(lib.tactics.map(t => t.name));
            if (!taken.has(base)) return base;
            let n = 2;
            while (taken.has(`${base} ${n}`)) n++;
            return `${base} ${n}`;
        };

        /** Reads the open tactic so "New" can create a variation of what the manager is looking at. */
        const copyBody = async (tacticId, name) => {
            try {
                const response = await authFetch(`/teams/${getTeamId()}/tactics/${tacticId}`);
                if (!response || !response.ok) return null;
                const full = await response.json();
                return {
                    name,
                    formation: full.formation,
                    style: full.style,
                    rulesJson: full.rulesJson,
                    setPiecesJson: full.setPiecesJson
                };
            } catch (_) {
                return null;
            }
        };

        /** What the last shelf action said, shown under the buttons. */
        let shelfMessage = '';
        const say = (message) => {
            shelfMessage = message || '';
            const bar = document.getElementById('te-shelf-message');
            if (bar) bar.textContent = shelfMessage;
        };

        /**
         * Posts and says what happened.
         *
         * The refusal carries the reason - the cap of ten, a name already taken, another club's tactic -
         * and that text is the whole point of the shelf refusing loudly. Swallowing it leaves a manager
         * clicking a button that appears to do nothing.
         */
        /**
         * The last refusal, kept so a panel can show it where the manager is looking.
         *
         * <p>The shelf's own line is not enough for Paste: the button is inside a panel that is a screen
         * away from the shelf message, and a refusal with no visible reason reads as a button that does
         * not work rather than as "this club already has a tactic called 4-4-2".
         */
        let lastRefusal = '';

        const postJson = async (url, body) => {
            try {
                const response = await authFetch(url, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: body ? JSON.stringify(body) : undefined
                });
                if (response && response.ok) {
                    lastRefusal = '';
                    return await response.json();
                }
                lastRefusal = await reasonOf(response);
                say(lastRefusal);
                return null;
            } catch (error) {
                say(String((error && error.message) || error));
                return null;
            }
        };

        /** The server's own words, or the status, so a refusal is never silent. */
        const reasonOf = async (response) => {
            try {
                const body = await response.json();
                return (body && (body.message || body.reason)) || `That did not work (${response.status}).`;
            } catch (_) {
                return `That did not work (${response ? response.status : 'no response'}).`;
            }
        };

        // ---------- the shelf ----------
        const wireShelf = () => {
            document.querySelectorAll('[data-te-pick]').forEach(button => {
                button.addEventListener('click', async () => {
                    const picked = Number(button.getAttribute('data-te-pick'));
                    selectedTacticId = picked;
                    preferredTacticId = picked;
                    const found = library.tactics.find(t => t.id === picked);
                    workingName = found ? found.name : '';
                    // **Nothing is removed here.** Drafts are keyed by tactic now, so picking another
                    // one loads that one's draft and cannot pick up this one's. Deleting the draft of
                    // the tactic being left is what used to keep them apart — at the price of losing
                    // unsaved work every time the manager looked at another tactic and came back.
                    await loadTacticEditor();
                });
            });

            const newBtn = document.getElementById('te-new-btn');
            if (newBtn) {
                newBtn.addEventListener('click', async () => {
                    if (library.atCap) return;
                    const name = prompt('Name for the new tactic', nextFreeName(library, 'New tactic'));
                    if (!name) return;
                    // "New" is created based on what is open, which is what a manager means by it:
                    // a variation of the tactic they are looking at.
                    const body = selectedTacticId
                        ? await copyBody(selectedTacticId, name)
                        : { name, formation: '4-4-2', style: 'Balanced' };
                    const saved = await postJson(`/teams/${getTeamId()}/tactics`, body);
                    if (saved) {
                        // Stay on what was just made, or the next Save writes over the first tactic.
                        preferredTacticId = saved.id;
                        await loadTacticEditor();
                    } else {
                        say(shelfMessage);
                    }
                });
            }

            const copyBtn = document.getElementById('te-copy-btn');
            if (copyBtn) {
                copyBtn.addEventListener('click', async () => {
                    if (!selectedTacticId) return;
                    // **The source is whatever the dropdown says**, not whatever happens to be open.
                    // The owner asked for this explicitly, and it is the other half of why a save after a
                    // copy went somewhere surprising.
                    const chosen = document.getElementById('te-copy-source');
                    const sourceId = chosen && chosen.value ? Number(chosen.value) : selectedTacticId;
                    const source = library.tactics.find(t => t.id === sourceId);
                    const name = prompt(
                        `Name for the copy of "${source ? source.name : 'Tactic'}"`,
                        `${source ? source.name : 'Tactic'} copy`);
                    if (!name) return;
                    const saved = await postJson(
                        `/teams/${getTeamId()}/tactics/${sourceId}/copy`, { name });
                    if (saved) {
                        preferredTacticId = saved.id;
                        await loadTacticEditor();
                    } else {
                        say(shelfMessage);
                    }
                });
            }

            const mirrorBtn = document.getElementById('te-mirror-btn');
            if (mirrorBtn) {
                mirrorBtn.addEventListener('click', async () => {
                    if (!selectedTacticId) return;
                    const saved = await postJson(
                        `/teams/${getTeamId()}/tactics/${selectedTacticId}/mirror`, null);
                    if (saved) {
                        preferredTacticId = saved.id;
                        await loadTacticEditor();
                    } else {
                        say(shelfMessage);
                    }
                });
            }

            // ── Paste from another club (T-MATCH-3c) ────────────────────────────────────────────────
            const pasteBtn = document.getElementById('te-paste-btn');
            const pastePanel = document.getElementById('te-paste-panel');
            const pasteQuery = document.getElementById('te-paste-query');
            const pasteResults = document.getElementById('te-paste-results');
            const pasteName = document.getElementById('te-paste-name');
            const pasteConfirm = document.getElementById('te-paste-confirm');
            const pasteNote = document.getElementById('te-paste-note');
            let pasteChoice = null;

            if (pasteBtn && pastePanel) {
                pasteBtn.addEventListener('click', () => {
                    const open = pastePanel.hidden;
                    pastePanel.hidden = !open;
                    if (open) void searchForPaste();
                });
            }

            async function searchForPaste() {
                if (!pasteResults) return;
                pasteResults.innerHTML = '<p class="fm-subtle">Looking…</p>';
                const query = (pasteQuery && pasteQuery.value) || '';
                // **Guarded, because an unhandled rejection here is invisible.**
                // The first version of this picker had no try/catch and called a helper that does not
                // exist in this module, so it threw, nothing caught it, and the panel sat on "Looking…"
                // forever. A loader that cannot fail visibly is a loader that will not.
                let res;
                try {
                    res = await authFetch(
                        `/teams/${getTeamId()}/tactics/pasteable${query ? `?query=${encodeURIComponent(query)}` : ''}`);
                } catch (error) {
                    pasteResults.innerHTML = `<p class="fm-subtle">Could not reach the list:
                        ${htmlEscape(error?.message || error)}</p>`;
                    return;
                }
                if (!res.ok) {
                    // response.ok is checked before the body is read, so a refusal reads as a refusal
                    // rather than as a parse error.
                    pasteResults.innerHTML = `<p class="fm-subtle">Could not list them (${res.status}).</p>`;
                    return;
                }
                let found;
                try {
                    found = await res.json();
                } catch (error) {
                    pasteResults.innerHTML = `<p class="fm-subtle">That answer was not a list:
                        ${htmlEscape(error?.message || error)}</p>`;
                    return;
                }
                pasteChoice = null;
                if (pasteConfirm) pasteConfirm.disabled = true;
                if (!found.length) {
                    pasteResults.innerHTML = '<p class="fm-subtle">Nothing found.</p>';
                    return;
                }
                pasteResults.innerHTML = found.map(t => `
                    <button type="button" class="te-paste-result" data-paste-id="${t.id}"
                            data-paste-name="${htmlEscape(t.name)}">
                        <strong>${htmlEscape(t.name)}</strong>
                        <span class="fm-subtle">${htmlEscape(t.clubName || 'another club')}
                            &middot; ${htmlEscape(t.formation || '—')}</span>
                    </button>`).join('');
                pasteResults.querySelectorAll('[data-paste-id]').forEach(row => {
                    row.addEventListener('click', () => {
                        pasteResults.querySelectorAll('.te-paste-result')
                            .forEach(r => r.classList.remove('is-chosen'));
                        row.classList.add('is-chosen');
                        pasteChoice = Number(row.dataset.pasteId);
                        // Pre-filled with the source's name, which is what most pastes want and which the
                        // manager can change before committing.
                        if (pasteName && !pasteName.value) pasteName.value = row.dataset.pasteName || '';
                        if (pasteConfirm) pasteConfirm.disabled = false;
                        if (pasteNote) pasteNote.textContent = '';
                    });
                });
            }

            if (pasteConfirm) {
                pasteConfirm.addEventListener('click', async () => {
                    if (!pasteChoice) return;
                    pasteConfirm.disabled = true;
                    const saved = await postJson(`/teams/${getTeamId()}/tactics/paste`, {
                        tacticId: pasteChoice,
                        name: pasteName ? pasteName.value : '',
                    });
                    if (saved) {
                        preferredTacticId = saved.id;
                        if (pastePanel) pastePanel.hidden = true;
                        if (pasteName) pasteName.value = '';
                        await loadTacticEditor();
                    } else {
                        // The refusal names the cap or the duplicate, and it is shown rather than
                        // swallowed: those are the two reasons a paste fails and neither is the manager's
                        // fault for trying.
                        if (pasteNote) pasteNote.textContent = lastRefusal || 'That one was refused.';
                        pasteConfirm.disabled = false;
                    }
                });
            }

            const deleteBtn = document.getElementById('te-delete-btn');
            if (deleteBtn) {
                deleteBtn.addEventListener('click', async () => {
                    if (!selectedTacticId) return;
                    const source = library.tactics.find(t => t.id === selectedTacticId);
                    if (!confirm(`Delete "${source ? source.name : 'this tactic'}"?`)) return;
                    try {
                        await authFetch(`/teams/${getTeamId()}/tactics/${selectedTacticId}`, { method: 'DELETE' });
                        selectedTacticId = null;
                        // The deleted tactic's own draft, and only that: DRAFT_KEY is keyed by the
                        // tactic this load was for, which is the one just deleted.
                        localStorage.removeItem(DRAFT_KEY);
                        await loadTacticEditor();
                    } catch (error) {
                        say(String(error.message || error));
                    }
                });
            }
        };

        const bindEvents = () => {
            // **First**, because it was the bug: the shelf used to be wired by a standalone call
            // placed before render() had painted anything, so every listener was bound to a
            // non-existent element and the buttons did nothing while looking perfectly normal.
            // bindEvents() is called at the end of render(), which is the only point at which the
            // markup exists.
            wireShelf();
            // **Changing the shape belongs to the open tactic.**
            //
            // It used to write `te_requested_formation_{club}` and reload, which is a club-level note:
//            every tactic opened afterwards was drawn in the shape the last one left behind. It now
            // changes this tactic's draft and re-reads the grid for that shape, because a 4-3-3 slot
            // list is not a 4-4-2 one and the anchors have to come back from the endpoint.
            mainContent.querySelectorAll('[data-te-formation]').forEach(btn => {
                btn.addEventListener('click', async () => {
                    const nextFormation = btn.getAttribute('data-te-formation') || state.formation;
                    if (nextFormation === state.formation) return;
                    state.formation = nextFormation;
                    // **The movement rules are dropped, and deliberately.** They are keyed by slot key,
                    // and the slot keys belong to the shape — DEF_1 means a defender in a 4-3-3 and a
                    // different defender in a 4-4-2. Carrying them across would keep the old shape's
                    // instructions against the new shape's slots, which is how a manager ends up with
                    // rules that say nothing about the team he is looking at. The shape is what he
                    // changed; the rules are redrawn from the new shape's anchors.
                    state.rulesMap = {};
                    // `draftVersion` is deliberately left alone. It says how fresh the draft is against
                    // the server, and zeroing it would mark this draft stale and throw the shape change
                    // away with it — which is the bug the old `draft.formation === editor.formation`
                    // test caused.
                    saveDraft();
                    await loadTacticEditor();
                });
            });
            mainContent.querySelectorAll('[data-te-style]').forEach(btn => { btn.addEventListener('click', () => { state.style = btn.getAttribute('data-te-style') || state.style; saveDraft(); render(); }); });
            mainContent.querySelectorAll('[data-te-slot-marker]').forEach(marker => {
                marker.addEventListener('click', () => { state.focusedSlot = marker.getAttribute('data-te-slot-marker') || state.focusedSlot; render(); });
                marker.addEventListener('dragstart', event => { state.focusedSlot = marker.getAttribute('data-te-slot-marker') || state.focusedSlot; event.dataTransfer?.setData('text/plain', JSON.stringify({ type: 'slot', slotKey: state.focusedSlot })); });
            });
            mainContent.querySelectorAll('[data-te-ball="true"]').forEach(ball => { ball.addEventListener('dragstart', event => { event.dataTransfer?.setData('text/plain', JSON.stringify({ type: 'ball' })); }); });
            mainContent.querySelectorAll('[data-te-drop]').forEach(zone => {
                zone.addEventListener('click', () => { const bsk = zone.getAttribute('data-ball-state'); if (bsk) { state.activeBallState = bsk; saveDraft(); render(); } });
                zone.addEventListener('dragover', event => { event.preventDefault(); zone.classList.add('is-drag-over'); });
                zone.addEventListener('dragleave', () => zone.classList.remove('is-drag-over'));
                zone.addEventListener('drop', event => {
                    event.preventDefault(); zone.classList.remove('is-drag-over');
                    const payload = readDragPayload(event);
                    if (!payload?.type) return;
                    const bsk = zone.getAttribute('data-ball-state') || '';
                    const tck = zone.getAttribute('data-target-cell') || '';
                    if (payload.type === 'ball' && bsk) { state.activeBallState = bsk; saveDraft(); render(); return; }
                    if (payload.type === 'slot' && payload.slotKey && tck) { state.focusedSlot = payload.slotKey; setRule(payload.slotKey, state.activeBallState, tck); render(); }
                });
            });
            mainContent.querySelectorAll('[data-sp-field]').forEach(select => { select.addEventListener('change', () => { const field = select.getAttribute('data-sp-field'); if (field) { state.setPieces[field] = select.value || ''; saveDraft(); } }); });
            const clearActiveBtn = document.getElementById('te-clear-active'); if (clearActiveBtn) clearActiveBtn.addEventListener('click', () => { clearFocusedRule(); render(); });
            const clearSlotBtn = document.getElementById('te-clear-slot'); if (clearSlotBtn) clearSlotBtn.addEventListener('click', () => { clearFocusedSlotRules(); render(); });
            const discardDraftBtn = document.getElementById('te-discard-draft'); if (discardDraftBtn) discardDraftBtn.addEventListener('click', async () => { localStorage.removeItem(DRAFT_KEY); await loadTacticEditor(); });
            const saveBtn = document.getElementById('te-save-btn');
            if (saveBtn && locked) {
                // Presentation, not security: the PUT is refused regardless. A button that looks live
                // and fails on click is the thing the banner exists to prevent.
                saveBtn.disabled = true;
                saveBtn.textContent = 'Closed until kick-off';
            } else if (saveBtn) {
                saveBtn.addEventListener('click', async () => {
                    saveBtn.disabled = true;
                    try {
                        // Saves into the library, under the name the tactic was opened with, so a save
                        // is an edit of that tactic rather than a new one. The old club-level profile
                        // endpoint is what this replaced: it could only ever hold one shape, which is the
                        // opposite of ten tactics a club keeps.
                        const payload = {
                            name: workingName || 'Tactic',
                            formation: state.formation,
                            style: state.style,
                            rulesJson: JSON.stringify(serializeRules()),
                            setPiecesJson: JSON.stringify(state.setPieces)
                        };
                        // Saves under the name the tactic was opened with, and the server upserts by
                        // name, so this is an edit of that tactic. The shape and the style in the payload
                        // are this tactic's own — "4-3-3 in one tactic, 4-4-2 in the other" is only true
                        // while nothing here falls back to a club-level value.
                        const saved = await postJson(`/teams/${getTeamId()}/tactics`, payload);
                        if (!saved) throw new Error(shelfMessage || 'The tactic was not saved.');
                        // The draft stays so the page reloads what the manager arranged rather than
                        // resetting, and it stays under this tactic's key.
                        preferredTacticId = saved.id;
                        saveDraft();
                        // BEFORE the reload: the reload rebuilds this whole closure, and without this
                        // the fresh copy would compare against a signature of the state as it was on
                        // entry and immediately read as dirty again.
                        markSaved();
                        saveBtn.textContent = 'Saved'; await loadTacticEditor();
                    } catch (error) { console.error('Failed to save tactic editor', error); saveBtn.disabled = false; saveBtn.textContent = 'Save failed'; setTimeout(() => { saveBtn.textContent = 'Save Tactic Editor'; }, 1400); }
                });
            }
        };

        const render = () => {
            const focusedMeta = focusedSlotMeta();
            const focusedTarget = state.focusedSlot ? getSlotTarget(state.focusedSlot, state.activeBallState) : '';
            const ruleCount = Object.keys(state.rulesMap).length;
            const formationButtons = availableFormations.map(f => `<button type="button" class="cs-tactics-btn ${f === state.formation ? 'active' : ''}" data-te-formation="${htmlEscape(f)}">${htmlEscape(f)}</button>`).join('');
            // Case-insensitive on purpose. The buttons are UPPERCASE and the server hands back
        // title case ("Balanced"), so `s === state.style` was false for every style and NO button was
        // ever highlighted. Owner, 2026-10-19: his chosen style was on screen in the header and not
        // lit up below, which is how a tactic ends up reading one thing and playing another.
        const currentStyle = String(state.style || '').toUpperCase();
        const styleButtons = availableStyles.map(s => `<button type="button" class="cs-tactics-btn ${s === currentStyle ? 'active' : ''}" data-te-style="${htmlEscape(s)}">${htmlEscape(s)}</button>`).join('');

            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    ${locked ? renderMatchLockBanner(matchLock) : ''}
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Club tactics</div>
                                <h2>Tactic Editor</h2>
                                <p class="fm-subtle">Drag the ball to any 6x7 zone or corner state, then drag slot circles to redraw the team shape for that exact situation.</p>
                            </div>
                            ${buildClubActionsHtml('tacticEditor')}
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid">
                            <div><strong>${htmlEscape(state.formation || '4-4-2')}</strong><span>Shape</span></div>
                            <div><strong>${slots.length}</strong><span>Slots</span></div>
                            <div><strong>${ruleCount}</strong><span>Rules</span></div>
                            <div><strong>${htmlEscape(state.style || 'BALANCED')}</strong><span>Style</span></div>
                        </div>
                    </section>
                    ${renderLibraryShelf(library, selectedTacticId, locked)}
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Base setup</h3>
                                <p class="fm-subtle">This tactic's own shape and style. Save writes both to
                                    <strong>${htmlEscape(workingName || 'this tactic')}</strong> and to no other, so two tactics can hold
                                    two different shapes.</p>
                            </div>
                            <span class="fm-panel-action">${htmlEscape(state.style || 'BALANCED')}</span>
                        </div>
                        <div class="cs-tactics-grid">${formationButtons}</div>
                        <div class="cs-tactics-grid te-style-grid">${styleButtons}</div>
                    </section>
                    <div class="te-board-layout">
                        <section class="fm-panel te-pitch-card">
                            <div class="fm-panel-head">
                                <div>
                                    <h3>Visual movement editor</h3>
                                    <p class="fm-subtle">Attack corners are on top, defend corners at the bottom. Multiple slots may share one zone.</p>
                                </div>
                                <span class="fm-panel-action">${htmlEscape(cellLabel(state.activeBallState))}</span>
                            </div>
                            <div class="te-pitch-stage">
                                ${buildCornerZonesHtml()}
                                <div class="te-pitch-board">
                                    <div class="te-pitch-surface">
                                        <div class="te-pitch-lines te-pitch-line--mid"></div>
                                        <div class="te-pitch-lines te-pitch-line--circle"></div>
                                        <div class="te-pitch-lines te-pitch-line--top-box"></div>
                                        <div class="te-pitch-lines te-pitch-line--top-six"></div>
                                        <div class="te-pitch-lines te-pitch-line--bottom-box"></div>
                                        <div class="te-pitch-lines te-pitch-line--bottom-six"></div>
                                    </div>
                                    <div class="te-drop-grid">${buildCellZonesHtml()}</div>
                                    <div class="te-player-layer">${buildPlayerMarkersHtml()}</div>
                                    <div class="te-ball-layer">${buildBallMarkerHtml()}</div>
                                </div>
                            </div>
                            <div class="te-instruction-row">
                                <span><strong>Ball:</strong> drag to 42 zones + 4 corners</span>
                                <span><strong>Slots:</strong> drag any circle to a new 6x7 zone for the active ball state</span>
                                <span><strong>Focus:</strong> click a slot circle to inspect / clear it</span>
                            </div>
                        </section>
                        <section class="fm-panel te-side-card">
                            <div class="fm-panel-head">
                                <div>
                                    <h3>Focused slot & save</h3>
                                    <p class="fm-subtle">Slot-based set-pieces stay separate from the visual pitch editor.</p>
                                </div>
                                <span class="fm-panel-action">${isDirty() ? 'Unsaved changes' : 'Saved'}</span>
                            </div>
                            <div class="training-actions te-actions-stack">
                                <button type="button" id="te-save-btn" class="big-button">Save Tactic Editor</button>
                            </div>
                        </section>
                    </div>
                </div>`;
            bindEvents();
        };
        render();
    }

    return { loadTacticEditor };
}
