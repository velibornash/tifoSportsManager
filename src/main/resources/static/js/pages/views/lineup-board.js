// pages/views/lineup-board.js
//
// The pitch, the Selection Dock and the available pool — one board, used by two screens.
//
// WHY IT IS SHARED. The owner, 2026-10-16: the match screen's eleven and bench "should look like
// tactics setup — Starting XI + Selection Dock, literally the same". "Literally the same" is only true
// while both screens render from one implementation: a second pitch that merely resembles the first
// drifts from it the first time either is changed. So the board lives here and both screens call it,
// including its CSS class names, which are global in `dashboard.css` and not scoped to the tactics page.
//
// WHAT THE BOARD DOES NOT DO. It does not fetch and it does not save. Both belong to the screen that
// owns the data: the tactics page saves a club template, the match screen saves one fixture's lineup.
// The board renders, accepts a drag or a choice, and hands the new selection back through `onChange`.
// That is also why removing "Save team" from the match screen could not break the save — see
// `match-lineup-view.js`, which saves on every change instead.

import { htmlEscape } from './utils.js';
import { positionRank } from '../../ui/position-order.js';

/** Eleven is a law. Seven on the bench, because that is what the substitution rules can draw on. */
export const STARTERS = 11;
export const BENCH_SLOTS = 7;

/**
 * Which positions a player may fill in a slot.
 *
 * <p>Deliberately a slot question and not a player's own position: a midfielder at full-back is a slot
 * saying DEF while the man stays MID, which is what `Lineup.starterPositions` is for and what the
 * engine reads.
 */
export function canPlayRole(player, role) {
    const position = String(player?.position || '').toUpperCase();
    // GK is the only position-locked slot: a non-keeper cannot play in goal.
    // Every other slot accepts any outfield player regardless of natural position
    // (owner: "bilo koji igrac bez obzira sta mu je prirodna pozicija na bilo koju poziciju").
    if (role === 'GK') return position === 'GK';
    return position !== 'GK';
}

/**
 * A formation string as eleven slots.
 *
 * <p>GK, then defenders, then midfielders, then attackers — the order the engine walks the eleven in.
 */
export function formationToSlots(formation) {
    const parts = String(formation || '4-4-2').split('-').map(v => Number(v)).filter(Number.isFinite);
    const def = parts[0] ?? 4;
    const mid = parts[1] ?? 4;
    const att = parts[2] ?? 2;
    const slots = [{ label: 'GK', role: 'GK' }];
    for (let i = 0; i < def; i++) slots.push({ label: `DEF ${i + 1}`, role: 'DEF' });
    for (let i = 0; i < mid; i++) slots.push({ label: `MID ${i + 1}`, role: 'MID' });
    for (let i = 0; i < att; i++) slots.push({ label: `ATT ${i + 1}`, role: 'ATT' });
    return slots.slice(0, STARTERS);
}

/**
 * Whether a player may be put on the pitch or the bench.
 *
 * <p>Injured only, and that is not an oversight. This board is the tactics page's board, which has
 * always left a suspended player selectable: a suspension is known in advance and the club may be
 * told otherwise before kickoff, while an injured player cannot start. Widening this to suspensions
 * would silently change the tactics page's behaviour, which is another screen's decision to make.
 */
export function isUnavailable(player) {
    return Boolean(player && player.injured);
}

/** Squad order: by role, then by rating. The dock is read left to right, so this is what it shows. */
export function sortSquad(players) {
    return [...players].sort((a, b) => {
        const left = positionRank(a.position, 9);
        const right = positionRank(b.position, 9);
        if (left !== right) return left - right;
        return Number(b.overall || 0) - Number(a.overall || 0);
    });
}

/**
 * The board.
 *
 * <p>Owns the selection it is drawing and nothing else. `render` can be called again at any time with a
 * new squad or a new formation; every change calls `onChange` with `{ starterIds, benchIds }`.
 */
/**
 * The board the tactics setup and the match lineup share.
 *
 * @param poolFirst whether the **Available pool** sits above the **Selection Dock** on the desktop.
 *   The owner asked for it on the tactics setup (2026-10-22): dragging a player out of the pool
 *   into the dock was hard, because the dock's beginning was below the fold and the drag had to
 *   start from a card he could not see. The match lineup keeps the dock first.
 */
export function createLineupBoard({ players = [], formation = '4-4-2', isMobile = false, poolFirst = false, onChange } = {}) {
    const squad = sortSquad(players);
    // Mutable: the tactics page changes the shape with its own buttons, and the board has to redraw
    // its slots from the new shape rather than keep the one it was built with.
    let slots = formationToSlots(formation);

    let starterIds = Array.from({ length: STARTERS }, () => null);
    let benchIds = Array.from({ length: BENCH_SLOTS }, () => null);

    const notify = () => {
        if (typeof onChange === 'function') {
            onChange({ starterIds: [...starterIds], benchIds: [...benchIds] });
        }
    };

    const byId = id => squad.find(p => Number(p.id) === Number(id)) || null;

    const selectedIds = () => new Set([...starterIds.filter(Boolean).map(Number), ...benchIds.filter(Boolean).map(Number)]);

    const poolCandidates = () => {
        const used = selectedIds();
        return squad.filter(p => !isUnavailable(p) && !used.has(Number(p.id)));
    };

    /** Deduplicates, drops the injured, and empties any slot whose man cannot fill it. */
    function normalize({ autofillStarters = false, autofillBench = false } = {}) {
        starterIds = Array.from({ length: STARTERS }, (_, idx) => Number(starterIds[idx] || 0) || null);
        benchIds = Array.from({ length: BENCH_SLOTS }, (_, idx) => Number(benchIds[idx] || 0) || null);

        if (autofillStarters && starterIds.filter(Boolean).length < STARTERS) {
            const used = new Set();
            starterIds = slots.map(slot => {
                const candidate = squad
                    .filter(p => canPlayRole(p, slot.role) && !isUnavailable(p) && !used.has(Number(p.id)))
                    .sort((a, b) => Number(b.overall || 0) - Number(a.overall || 0))[0];
                if (!candidate) return null;
                used.add(Number(candidate.id));
                return Number(candidate.id);
            });
            // Top up with whoever is left, in rating order. The pass below empties any slot the
            // top-up cannot really fill, so a short squad still reaches eleven slots.
            squad.filter(p => !isUnavailable(p) && !used.has(Number(p.id)))
                .sort((a, b) => Number(b.overall || 0) - Number(a.overall || 0))
                .forEach(p => { if (starterIds.filter(Boolean).length < STARTERS) starterIds.push(Number(p.id)); });
        }

        const used = new Set();
        starterIds = slots.map((slot, idx) => {
            const id = Number(starterIds[idx] || 0);
            const player = byId(id);
            if (!id || !player || isUnavailable(player) || used.has(id) || !canPlayRole(player, slot.role)) return null;
            used.add(id);
            return id;
        });

        const benchUsed = new Set(starterIds.filter(Boolean).map(Number));
        benchIds = benchIds.map(id => {
            const numericId = Number(id || 0);
            const player = byId(numericId);
            if (!numericId || !player || isUnavailable(player) || benchUsed.has(numericId)) return null;
            benchUsed.add(numericId);
            return numericId;
        });
        if (autofillBench) {
            squad.filter(p => !isUnavailable(p) && !benchUsed.has(Number(p.id)))
                .forEach(p => { if (benchIds.filter(Boolean).length < BENCH_SLOTS) { benchIds.push(Number(p.id)); benchUsed.add(Number(p.id)); } });
        }
    }

    const clearPlayer = playerId => {
        const id = Number(playerId);
        starterIds = starterIds.map(value => Number(value) === id ? null : value);
        benchIds = benchIds.map(value => Number(value) === id ? null : value);
    };

    function renderDesktop() {
        const grouped = { ATT: [], MID: [], DEF: [], GK: [] };
        slots.forEach((slot, idx) => { if (grouped[slot.role]) grouped[slot.role].push({ slot, idx }); });
        const pitchSlots = [
            { role: 'ATT', top: 15 }, { role: 'MID', top: 38 },
            { role: 'DEF', top: 63 }, { role: 'GK', top: 84 }
        ].flatMap(({ role, top }) => (grouped[role] || []).map((entry, rowIndex) => ({
            ...entry, left: ((rowIndex + 1) / (grouped[role].length + 1)) * 100, top
        }))).map(({ slot, idx, left, top }) => {
            const player = byId(starterIds[idx]);
            return `
                <div class="lineup-slot-drop club-lineup-pitch-slot" data-zone="starter" data-index="${idx}" data-role="${slot.role}" style="--club-slot-left:${left}%; --club-slot-top:${top}%;">
                    <div class="club-lineup-slot-label">${slot.label}</div>
                    ${player ? chip(player, 'starter', idx, slot.label) : `<div class="club-lineup-slot-placeholder">Drop ${slot.role}</div>`}
                </div>`;
        }).join('');

        const pool = poolCandidates().map(player => chip(player, 'pool', -1)).join('');

        return `
            <div class="club-lineup-desktop-shell">
                <div class="training-block club-lineup-pitch-card">
                    <div class="club-lineup-panel-head">
                        <div>
                            <h4>Starting XI</h4>
                            <p class="fm-subtle">Drag directly onto the pitch. Each role stays visible, so bench-to-XI swaps no longer need page scrolling.</p>
                        </div>
                        <span class="fm-panel-action">Drag &amp; drop</span>
                    </div>
                    <div class="club-lineup-pitch-stage">
                        <div class="club-lineup-pitch-board">
                            <div class="club-lineup-pitch-surface">
                                <span class="club-lineup-pitch-line club-lineup-pitch-line--mid"></span>
                                <span class="club-lineup-pitch-line club-lineup-pitch-line--circle"></span>
                                <span class="club-lineup-pitch-line club-lineup-pitch-line--top-box"></span>
                                <span class="club-lineup-pitch-line club-lineup-pitch-line--bottom-box"></span>
                                <span class="club-lineup-pitch-line club-lineup-pitch-line--top-six"></span>
                                <span class="club-lineup-pitch-line club-lineup-pitch-line--bottom-six"></span>
                            </div>
                            <div class="club-lineup-pitch-layer">${pitchSlots}</div>
                        </div>
                    </div>
                    <div class="lineup-slot-drop club-lineup-pool-strip" data-zone="pool" data-index="-1">
                        <strong>Unassign area</strong>
                        <span>Drop a starter or bench player here to remove them from the active selection.</span>
                    </div>
                </div>
                <div class="club-lineup-side-rail">
                    <div class="training-block club-lineup-side-card">
                        ${poolFirst ? poolBlock() : dockBlock()}
                        ${poolFirst ? dockBlock() : poolBlock()}
                    </div>
                </div>
            </div>`;
    }

    /** The Selection Dock: the bench, and the eleven that are on the pitch next to it. */
    function dockBlock() {
        const benchSlots = benchIds.map((selected, idx) => {
            const player = byId(selected);
            return `
                <div class="lineup-slot-drop club-lineup-bench-slot" data-zone="bench" data-index="${idx}">
                    <div class="club-lineup-slot-label">Bench ${idx + 1}</div>
                    ${player ? chip(player, 'bench', idx, 'Bench ' + (idx + 1)) : `<div class="club-lineup-slot-placeholder">Drop player</div>`}
                </div>`;
        }).join('');
        return `
                        <div class="club-lineup-panel-head">
                            <div>
                                <h4>Selection Dock</h4>
                                <p class="fm-subtle">${poolFirst
                                    ? 'The squad to pick from is above; drag a player down into the dock.'
                                    : 'Bench stays next to the pitch, with the remaining squad immediately below it.'}</p>
                            </div>
                        </div>
                        <div class="club-lineup-bench-grid">${benchSlots}</div>`;
    }

    /** The Available pool: everyone who is neither on the pitch nor on the bench. */
    function poolBlock() {
        const pool = poolCandidates().map(player => chip(player, 'pool', -1)).join('');
        return `
                        <div class="club-lineup-pool-head">
                            <h4>Available pool</h4>
                            <span>${poolCandidates().length} ready</span>
                        </div>
                        <div class="lineup-slot-drop club-lineup-pool-list" data-zone="pool" data-index="-1">${pool || `<div class="club-lineup-pool-empty">No available players</div>`}</div>`;
    }

    function renderMobile() {
        const startersHtml = slots.map((slot, idx) => {
            const selected = Number(starterIds[idx] || 0);
            return `<label class="training-group-row" style="margin-bottom:6px;">
                <span class="group-tag">${slot.label}</span>
                <select class="starter-select" data-slot="${idx}">
                    <option value="">-- Empty --</option>
                    ${candidatesFor(slot.role, selected).map(p => `<option value="${p.id}" ${Number(p.id) === selected ? 'selected' : ''}>${htmlEscape(p.name)} (${htmlEscape(p.position)}, OVR ${p.overall ?? '-'})</option>`).join('')}
                </select>
            </label>`;
        }).join('');
        const benchHtml = benchIds.map((selected, idx) => {
            const numeric = Number(selected || 0);
            return `<label class="training-group-row" style="margin-bottom:6px;">
                <span class="group-tag">Bench ${idx + 1}</span>
                <select class="bench-select" data-slot="${idx}">
                    <option value="">-- Empty --</option>
                    ${anyCandidates(numeric).map(p => `<option value="${p.id}" ${Number(p.id) === numeric ? 'selected' : ''}>${htmlEscape(p.name)} (${htmlEscape(p.position)}, OVR ${p.overall ?? '-'})</option>`).join('')}
                </select>
            </label>`;
        }).join('');
        return `
            <div class="training-block" style="margin-top:14px;"><h3>Starting XI</h3><p class="training-note">Mobile: dropdown selection with position filters and unique player lock.</p>${startersHtml}</div>
            <div class="training-block" style="margin-top:14px;"><h3>Bench</h3>${benchHtml}</div>`;
    }

    function candidatesFor(role, currentId) {
        const used = selectedIds();
        if (currentId) used.delete(Number(currentId));
        return squad.filter(p => !isUnavailable(p) && !used.has(Number(p.id)) && canPlayRole(p, role));
    }

    function anyCandidates(currentId) {
        const used = selectedIds();
        if (currentId) used.delete(Number(currentId));
        return squad.filter(p => !isUnavailable(p) && !used.has(Number(p.id)));
    }

    function chip(player, zone, index, slotLabel) {
        const variant = zone === 'bench' ? ' club-lineup-player-chip--bench' : (zone === 'pool' ? ' club-lineup-player-chip--pool' : '');
        const labelHtml = slotLabel ? `<span class="club-lineup-player-slot">${htmlEscape(slotLabel)}</span>` : '';
        return `<div class="lineup-draggable club-lineup-player-chip${variant}" draggable="true"
                     data-player-id="${player.id}" data-from-zone="${zone}" data-from-index="${index}">
            ${labelHtml}
            <span class="club-lineup-player-name">${htmlEscape(player.name)}</span>
            <span class="club-lineup-player-meta">${htmlEscape(player.position)}, OVR ${player.overall ?? '-'}</span>
        </div>`;
    }

    /** Puts the selection in without treating it as a manager action. */
    function setSelection(nextStarters, nextBench) {
        starterIds = Array.from({ length: STARTERS }, (_, idx) => Number((nextStarters || [])[idx] || 0) || null);
        benchIds = Array.from({ length: BENCH_SLOTS }, (_, idx) => Number((nextBench || [])[idx] || 0) || null);
    }

    function selection() {
        return { starterIds: [...starterIds], benchIds: [...benchIds] };
    }

    /** Renders into `host` and wires the board to it. Safe to call again after every change. */
    function render(host) {
        if (!host) return;
        normalize();
        host.innerHTML = isMobile ? renderMobile() : renderDesktop();
        if (isMobile) bindMobile(host);
        else bindDragAndDrop(host);
    }

    function bindMobile(host) {
        host.querySelectorAll('.starter-select').forEach(select => {
            select.addEventListener('change', () => {
                const slot = Number(select.getAttribute('data-slot'));
                const id = Number(select.value || 0) || null;
                if (id) {
                    starterIds = starterIds.map((value, idx) => idx !== slot && Number(value) === id ? null : value);
                    benchIds = benchIds.map(value => Number(value) === id ? null : value);
                }
                starterIds[slot] = id;
                render(host);
                notify();
            });
        });
        host.querySelectorAll('.bench-select').forEach(select => {
            select.addEventListener('change', () => {
                const slot = Number(select.getAttribute('data-slot'));
                const id = Number(select.value || 0) || null;
                if (id) {
                    starterIds = starterIds.map(value => Number(value) === id ? null : value);
                    benchIds = benchIds.map((value, idx) => idx !== slot && Number(value) === id ? null : value);
                }
                benchIds[slot] = id;
                render(host);
                notify();
            });
        });
    }

    function bindDragAndDrop(host) {
        let dragData = null;
        host.querySelectorAll('.lineup-draggable').forEach(el => {
            el.addEventListener('dragstart', () => {
                dragData = {
                    playerId: Number(el.dataset.playerId),
                    fromZone: el.dataset.fromZone,
                    fromIndex: Number(el.dataset.fromIndex)
                };
            });
        });
        host.querySelectorAll('.lineup-slot-drop').forEach(zone => {
            zone.addEventListener('dragover', e => e.preventDefault());
            zone.addEventListener('drop', e => {
                e.preventDefault();
                if (!dragData || !dragData.playerId) return;
                const targetZone = zone.dataset.zone;
                const targetIndex = Number(zone.dataset.index);
                const targetRole = zone.dataset.role || null;
                const player = byId(dragData.playerId);
                if (!player || isUnavailable(player)) return;
                const sourceRole = dragData.fromZone === 'starter' ? slots[dragData.fromIndex]?.role : null;

                if (targetZone === 'starter') {
                    if (!canPlayRole(player, targetRole)) return;
                    clearPlayer(dragData.playerId);
                    const displaced = starterIds[targetIndex] || null;
                    starterIds[targetIndex] = Number(dragData.playerId);
                    if (dragData.fromZone === 'starter' && dragData.fromIndex >= 0 && targetIndex !== dragData.fromIndex) {
                        starterIds[dragData.fromIndex] = displaced && canPlayRole(byId(displaced), sourceRole) ? displaced : null;
                    } else if (dragData.fromZone === 'bench' && dragData.fromIndex >= 0) {
                        benchIds[dragData.fromIndex] = displaced || null;
                    }
                } else if (targetZone === 'bench') {
                    clearPlayer(dragData.playerId);
                    const displaced = benchIds[targetIndex] || null;
                    benchIds[targetIndex] = Number(dragData.playerId);
                    if (dragData.fromZone === 'starter' && dragData.fromIndex >= 0) {
                        starterIds[dragData.fromIndex] = displaced && canPlayRole(byId(displaced), sourceRole) ? displaced : null;
                    } else if (dragData.fromZone === 'bench' && dragData.fromIndex >= 0 && targetIndex !== dragData.fromIndex) {
                        benchIds[dragData.fromIndex] = displaced || null;
                    }
                } else if (targetZone === 'pool') {
                    if (dragData.fromZone === 'starter' && dragData.fromIndex >= 0) starterIds[dragData.fromIndex] = null;
                    if (dragData.fromZone === 'bench' && dragData.fromIndex >= 0) benchIds[dragData.fromIndex] = null;
                }
                render(host);
                notify();
            });
        });
    }

    /** A new shape. The selection is left alone; `normalize` decides who still fits. */
    function setFormation(value) {
        slots = formationToSlots(value);
    }

    return {
        render,
        setSelection,
        setFormation,
        selection,
        normalize,
        get slots() {
            return slots;
        },
        /** How many of the eleven are filled. The screen's header counts this. */
        starterCount: () => starterIds.filter(Boolean).length,
        benchCount: () => benchIds.filter(Boolean).length
    };
}