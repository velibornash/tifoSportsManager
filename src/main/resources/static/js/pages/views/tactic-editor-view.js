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

function renderLibraryShelf(library, selectedId, locked) {
    const rows = library.tactics.map(t => `
        <button type="button" class="cs-tactics-btn ${t.id === selectedId ? 'active' : ''}"
                data-te-pick="${t.id}">
            ${htmlEscape(t.name)}${t.isDefault ? ' ★' : ''}
        </button>`).join('');

    const disabled = locked ? ' disabled' : '';
    const capNote = library.atCap
        ? `<p class="fm-subtle">All ${library.maxTactics} tactics are in use. Delete one, or edit an
             existing tactic by saving over its name.</p>`
        : `<p class="fm-subtle">${library.tactics.length} of ${library.maxTactics} tactics.</p>`;

    return `
        <section class="fm-panel" style="margin-bottom:16px;">
            <div class="fm-panel-head"><div>
                <h3>My tactics</h3>
                ${capNote}
            </div></div>
            <div class="cs-tactics-row" style="display:flex; flex-wrap:wrap; gap:8px; margin-bottom:12px;">
                ${rows || '<span class="fm-subtle">No tactics yet — create one below.</span>'}
            </div>
            <div style="display:flex; gap:8px; flex-wrap:wrap;">
                <button type="button" id="te-new-btn" class="big-button"${disabled}${library.atCap ? ' disabled' : ''}>New</button>
                <button type="button" id="te-copy-btn" class="big-button"${disabled}>Copy (based on)</button>
                <button type="button" id="te-mirror-btn" class="big-button"${disabled}>Mirror</button>
                <button type="button" id="te-delete-btn" class="big-button"${disabled}>Delete</button>
            </div>
            ${library.failed ? '<p class="fm-subtle" style="margin-top:8px;">The tactic list could not be loaded. Editing still works; the shelf is unavailable.</p>' : ''}
        </section>`;
}

export function createTacticEditorView(deps) {
    const { authFetch, getTeamId, buildClubActionsHtml } = deps;

    async function loadTacticEditor() {
        const mainContent = document.getElementById("main-content");
        const DRAFT_KEY = `te_draft_${getTeamId()}`;
        const FORMATION_KEY = `te_requested_formation_${getTeamId()}`;
        const POSSESSION_CONTEXT = 'WE_HAVE_BALL'; // Koristimo samo jednu varijantu
        const availableStyles = ["BALANCED", "ATTACKING", "DEFENSIVE", "COUNTER", "POSSESSION", "HIGH_PRESS", "DIRECT"];
        const availableFormations = ["4-4-2", "4-3-3", "4-2-3-1", "4-1-4-1", "4-5-1", "3-5-2", "3-4-3", "3-4-2-1", "5-3-2", "5-4-1"];
        const requestedFormation = localStorage.getItem(FORMATION_KEY) || '';
        const editorUrl = requestedFormation
            ? `/teams/${getTeamId()}/tactics-editor?formation=${encodeURIComponent(requestedFormation)}`
            : `/teams/${getTeamId()}/tactics-editor`;

        const editorRes = await authFetch(editorUrl);
        if (!editorRes.ok) {
            mainContent.innerHTML = `<div class="fm-page"><section class="fm-panel"><div class="fm-empty">Failed to load tactic editor data.</div></section></div>`;
            return;
        }
        const editor = await editorRes.json();

        // An hour before kickoff the match has been computed. The server refuses the save with 423
        // either way; this asks first so the page can say why, rather than letting a manager find out
        // by pressing Save and being told "locked" with no explanation.
        const matchLock = await fetchMatchLock(authFetch, getTeamId());
        const locked = isPageLocked(matchLock);

        // Which tactic the grid is editing. The shelf is the library; the grid below edits one of them.
        const library = await loadLibrary(getTeamId(), authFetch);
        let selectedTacticId = library.tactics.length ? library.tactics[0].id : null;
        let workingName = library.tactics.length ? library.tactics[0].name : '';

        let draft = null;
        try { const raw = localStorage.getItem(DRAFT_KEY); if (raw) draft = JSON.parse(raw); } catch (_) { draft = null; }

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

        const useDraft = !!(draft && draft.formation === editor.formation && typeof draft.draftVersion === 'number' && draft.draftVersion >= (editor.version || 0));
        const setPiecesSource = useDraft ? (draft.setPieceAssignments || editor.setPieceAssignments || {}) : (editor.setPieceAssignments || {});

        const state = {
            formation: useDraft ? (draft.formation || editor.formation) : editor.formation,
            style: useDraft ? (draft.style || editor.style) : editor.style,
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
        const postJson = async (url, body) => {
            try {
                const response = await authFetch(url, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: body ? JSON.stringify(body) : undefined
                });
                if (response && response.ok) return await response.json();
                say(await reasonOf(response));
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
                    const found = library.tactics.find(t => t.id === picked);
                    workingName = found ? found.name : '';
                    // The grid keeps its own draft; loading a different tactic under a stale draft is
                    // how a manager edits one shape and saves it over another.
                    localStorage.removeItem(DRAFT_KEY);
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
                        localStorage.removeItem(DRAFT_KEY);
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
                    const source = library.tactics.find(t => t.id === selectedTacticId);
                    const name = prompt('Name for the copy', `${source ? source.name : 'Tactic'} copy`);
                    if (!name) return;
                    const saved = await postJson(
                        `/teams/${getTeamId()}/tactics/${selectedTacticId}/copy`, { name });
                    if (saved) {
                        localStorage.removeItem(DRAFT_KEY);
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
                        localStorage.removeItem(DRAFT_KEY);
                        await loadTacticEditor();
                    } else {
                        say(shelfMessage);
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
            mainContent.querySelectorAll('[data-te-formation]').forEach(btn => { btn.addEventListener('click', async () => { const nextFormation = btn.getAttribute('data-te-formation') || state.formation; localStorage.setItem(FORMATION_KEY, nextFormation); await loadTacticEditor(); }); });
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
                        const saved = await postJson(`/teams/${getTeamId()}/tactics`, payload);
                        if (!saved) throw new Error(shelfMessage || 'The tactic was not saved.');
                        localStorage.removeItem(DRAFT_KEY); localStorage.setItem(FORMATION_KEY, state.formation);
                        saveBtn.textContent = 'Saved'; await loadTacticEditor();
                    } catch (error) { console.error('Failed to save tactic editor', error); saveBtn.disabled = false; saveBtn.textContent = 'Save failed'; setTimeout(() => { saveBtn.textContent = 'Save Tactic Editor'; }, 1400); }
                });
            }
        };

        const render = () => {
            const draftLoaded = hasDraft();
            const focusedMeta = focusedSlotMeta();
            const focusedTarget = state.focusedSlot ? getSlotTarget(state.focusedSlot, state.activeBallState) : '';
            const ruleCount = Object.keys(state.rulesMap).length;
            const formationButtons = availableFormations.map(f => `<button type="button" class="cs-tactics-btn ${f === state.formation ? 'active' : ''}" data-te-formation="${htmlEscape(f)}">${htmlEscape(f)}</button>`).join('');
            const styleButtons = availableStyles.map(s => `<button type="button" class="cs-tactics-btn ${s === state.style ? 'active' : ''}" data-te-style="${htmlEscape(s)}">${htmlEscape(s)}</button>`).join('');

            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    ${locked ? renderMatchLockBanner(matchLock) : ''}
                    ${renderLibraryShelf(library, selectedTacticId, locked)}
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
                            <div><strong>${draftLoaded ? 'Draft' : `v${editor.version || 0}`}</strong><span>Status</span></div>
                        </div>
                    </section>
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Base setup</h3>
                                <p class="fm-subtle">Changing formation reloads slot anchors from the backend. Style and visual edits autosave locally until you press Save.</p>
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
                            <div class="te-info-strip">
                                <div class="te-info-pill"><strong>Ball state</strong><span>${htmlEscape(cellLabel(state.activeBallState))}</span></div>
                                <div class="te-info-pill"><strong>Focused slot</strong><span>${htmlEscape(state.focusedSlot || '—')}</span></div>
                                <div class="te-info-pill"><strong>Focused target</strong><span>${htmlEscape(cellLabel(focusedTarget))}</span></div>
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
                                <span class="fm-panel-action">${draftLoaded ? 'Local draft loaded' : 'Server profile'}</span>
                            </div>
                            <div class="te-note-box">
                                <strong>Focused slot:</strong> ${htmlEscape(focusedMeta?.slotKey || '—')}<br>
                                <strong>Line / role:</strong> ${htmlEscape(focusedMeta?.line || '—')} / ${htmlEscape(focusedMeta?.role || '—')}<br>
                                <strong>Anchor:</strong> ${htmlEscape(focusedMeta?.anchorCellKey || '—')}<br>
                                <strong>Current target:</strong> ${htmlEscape(cellLabel(focusedTarget))}
                            </div>
                            <div class="training-actions te-actions-stack">
                                <button type="button" id="te-clear-active" class="fm-action-btn secondary">Clear focused slot in this ball state</button>
                                <button type="button" id="te-clear-slot" class="fm-action-btn secondary">Clear all rules for focused slot</button>
                            </div>
                            <div class="te-side-divider"></div>
                            ${renderSetPieceSelects()}
                            <div class="training-actions te-actions-stack">
                                <button type="button" id="te-save-btn" class="big-button">Save Tactic Editor</button>
                                <button type="button" id="te-discard-draft" class="fm-action-btn secondary">Discard local draft</button>
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
