// pages/views/formations-view.js
import { htmlEscape } from './utils.js';

export function createFormationsView(deps) {
    const { authFetch, getTeamId, buildClubActionsHtml } = deps;

    async function loadFormations() {
        const mainContent = document.getElementById("main-content");
        const [formationsRes, playersRes, templateRes] = await Promise.all([
            authFetch(`/teams/${getTeamId()}/formations`),
            authFetch(`/teams/${getTeamId()}/players`),
            authFetch(`/teams/${getTeamId()}/lineup-template`)
        ]);
        const formations = formationsRes.ok ? await formationsRes.json() : [];
        const players = playersRes.ok ? await playersRes.json() : [];
        const template = templateRes.ok ? await templateRes.json() : { formation: "4-4-2", starterIds: [], benchIds: [], saved: false };

        const availableFormations = Array.from(new Set([
            ...(Array.isArray(formations) ? formations.map(f => f.name) : []),
            "4-4-2", "4-3-3", "4-2-3-1", "4-1-4-1", "4-5-1",
            "3-5-2", "3-4-3", "3-4-2-1", "5-3-2", "5-4-1"
        ]));
        const availableStyles = ["BALANCED", "ATTACKING", "DEFENSIVE", "COUNTER", "POSSESSION", "HIGH_PRESS", "DIRECT"];
        const isMobile = window.matchMedia("(max-width: 768px)").matches;

        const formationToSlots = (formation) => {
            const parts = String(formation || "4-4-2").split("-").map(v => Number(v)).filter(Number.isFinite);
            const def = parts[0] ?? 4;
            const mid = parts[1] ?? 4;
            const att = parts[2] ?? 2;
            const slots = [{ label: "GK", role: "GK" }];
            for (let i = 0; i < def; i++) slots.push({ label: `DEF ${i + 1}`, role: "DEF" });
            for (let i = 0; i < mid; i++) slots.push({ label: `MID ${i + 1}`, role: "MID" });
            for (let i = 0; i < att; i++) slots.push({ label: `ATT ${i + 1}`, role: "ATT" });
            return slots.slice(0, 11);
        };

        const roleOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
        const sortedPlayers = [...players].sort((a, b) => {
            const left = roleOrder[String(a.position || "").toUpperCase()] ?? 9;
            const right = roleOrder[String(b.position || "").toUpperCase()] ?? 9;
            if (left !== right) return left - right;
            return Number(b.overall || 0) - Number(a.overall || 0);
        });

        const localFormation = localStorage.getItem('main_app_tactics_formation');
        const localStyle = localStorage.getItem('main_app_tactics_style');
        const hasSavedTemplate = template?.saved === true || (Array.isArray(template?.starterIds) && template.starterIds.length > 0);
        const state = {
            formation: hasSavedTemplate
                ? (template.formation || availableFormations[0] || "4-4-2")
                : (localFormation || template.formation || availableFormations[0] || "4-4-2"),
            style: hasSavedTemplate
                ? (template.style || localStyle || "BALANCED")
                : (localStyle || template.style || "BALANCED"),
            starterIds: Array.isArray(template.starterIds) ? template.starterIds.map(Number).filter(Number.isFinite).slice(0, 11) : [],
            benchIds: Array.isArray(template.benchIds) ? template.benchIds.map(Number).filter(Number.isFinite).slice(0, 7) : []
        };

        if (hasSavedTemplate && template?.formation) localStorage.setItem('main_app_tactics_formation', template.formation);
        if (hasSavedTemplate && template?.style) localStorage.setItem('main_app_tactics_style', template.style);

        const canPlayRole = (player, role) => {
            const pos = String(player?.position || "").toUpperCase();
            if (role === "GK") return pos === "GK";
            if (role === "DEF") return pos === "DEF";
            if (role === "MID") return pos === "MID" || pos === "WNG";
            if (role === "ATT") return pos === "ATT" || pos === "WNG" || pos === "MID";
            return false;
        };

        const pickDefaultStarters = (slots) => {
            if (!players.length) return [];
            const byPos = (role) => sortedPlayers.filter(p => canPlayRole(p, role) && !p.injured)
                .sort((a, b) => Number(b.overall || 0) - Number(a.overall || 0));
            const picks = [];
            const used = new Set();
            slots.forEach(slot => {
                const candidate = byPos(slot.role).find(p => !used.has(p.id));
                if (candidate) { picks.push(candidate.id); used.add(candidate.id); }
            });
            sortedPlayers.filter(p => !p.injured && !used.has(p.id))
                .sort((a, b) => Number(b.overall || 0) - Number(a.overall || 0))
                .forEach(p => { if (picks.length < 11) picks.push(p.id); });
            return picks.slice(0, 11);
        };

        const fillBenchDefaults = () => {
            const used = new Set(state.starterIds.filter(Boolean).map(Number));
            const bench = state.benchIds.filter(id => id && !used.has(Number(id))).map(Number).slice(0, 7);
            bench.forEach(id => used.add(id));
            sortedPlayers.filter(p => !p.injured && !used.has(Number(p.id))).forEach(p => { if (bench.length < 7) { bench.push(Number(p.id)); used.add(Number(p.id)); } });
            state.benchIds = bench;
        };

        const normalizeSelectionState = ({ autofillStarters = false, autofillBench = false } = {}) => {
            const slots = formationToSlots(state.formation);
            state.starterIds = Array.from({ length: 11 }, (_, idx) => Number(state.starterIds[idx] || 0) || null);
            state.benchIds = Array.from({ length: 7 }, (_, idx) => Number(state.benchIds[idx] || 0) || null);

            if (autofillStarters && state.starterIds.filter(Boolean).length < 11) state.starterIds = pickDefaultStarters(slots);
            const used = new Set();
            state.starterIds = slots.map((slot, idx) => {
                const id = Number(state.starterIds[idx] || 0);
                const p = sortedPlayers.find(sp => Number(sp.id) === id);
                if (!id || !p || p.injured || used.has(id) || !canPlayRole(p, slot.role)) return null;
                used.add(id);
                return id;
            });

            if (autofillStarters) {
                slots.forEach((slot, idx) => {
                    if (state.starterIds[idx]) return;
                    const fallback = sortedPlayers.find(p => !p.injured && !used.has(Number(p.id)) && canPlayRole(p, slot.role));
                    if (fallback) { state.starterIds[idx] = Number(fallback.id); used.add(Number(fallback.id)); }
                });
            }

            const benchUsed = new Set(state.starterIds.filter(Boolean).map(Number));
            state.benchIds = state.benchIds.map(id => {
                const numericId = Number(id || 0);
                const p = getPlayerById(numericId);
                if (!numericId || !p || p.injured || benchUsed.has(numericId)) return null;
                benchUsed.add(numericId);
                return numericId;
            });
            if (autofillBench) fillBenchDefaults();
        };

        const getPlayerById = id => sortedPlayers.find(p => Number(p.id) === Number(id));
        const selectedIds = () => new Set([...state.starterIds.filter(Boolean).map(Number), ...state.benchIds.filter(Boolean).map(Number)]);
        const candidatesForSlot = (role, currentId) => {
            const used = selectedIds();
            if (currentId) used.delete(Number(currentId));
            return sortedPlayers.filter(p => !p.injured && !used.has(Number(p.id)) && canPlayRole(p, role));
        };
        const benchCandidates = (currentId) => {
            const used = selectedIds();
            if (currentId) used.delete(Number(currentId));
            return sortedPlayers.filter(p => !p.injured && !used.has(Number(p.id)));
        };
        const poolCandidates = () => {
            const used = selectedIds();
            return sortedPlayers.filter(p => !p.injured && !used.has(Number(p.id)));
        };

        const clearPlayerFromState = (playerId) => {
            state.starterIds = state.starterIds.map(id => Number(id) === Number(playerId) ? null : id);
            state.benchIds = state.benchIds.map(id => Number(id) === Number(playerId) ? null : id);
        };
        const assignToBench = (playerId, targetIndex) => {
            const previous = Number(state.benchIds[targetIndex] || 0) || null;
            state.benchIds[targetIndex] = Number(playerId);
            return previous;
        };
        const assignToStarter = (playerId, targetIndex) => {
            const previous = Number(state.starterIds[targetIndex] || 0) || null;
            state.starterIds[targetIndex] = Number(playerId);
            return previous;
        };

        const buildPitchCoordinates = (slots) => {
            const grouped = { ATT: [], MID: [], DEF: [], GK: [] };
            slots.forEach((slot, idx) => { if (grouped[slot.role]) grouped[slot.role].push({ slot, idx }); });
            const rows = [
                { role: "ATT", top: 15 }, { role: "MID", top: 38 },
                { role: "DEF", top: 63 }, { role: "GK", top: 84 }
            ];
            return rows.flatMap(({ role, top }) => {
                const rowSlots = grouped[role] || [];
                return rowSlots.map((entry, rowIndex) => ({ ...entry, left: ((rowIndex + 1) / (rowSlots.length + 1)) * 100, top }));
            });
        };

        const renderDesktopDnD = (slots) => {
            const pitchSlots = buildPitchCoordinates(slots).map(({ slot, idx, left, top }) => {
                const selected = Number(state.starterIds[idx] || 0);
                const p = getPlayerById(selected);
                return `
                    <div class="lineup-slot-drop club-lineup-pitch-slot" data-zone="starter" data-index="${idx}" data-role="${slot.role}" style="--club-slot-left:${left}%; --club-slot-top:${top}%;">
                        <div class="club-lineup-slot-label">${slot.label}</div>
                        ${p ? `<div class="lineup-draggable club-lineup-player-chip" draggable="true" data-player-id="${p.id}" data-from-zone="starter" data-from-index="${idx}">
                                <span class="club-lineup-player-name">${htmlEscape(p.name)}</span>
                                <span class="club-lineup-player-meta">${htmlEscape(p.position)}, OVR ${p.overall ?? "-"}</span>
                            </div>`
                        : `<div class="club-lineup-slot-placeholder">Drop ${slot.role}</div>`}
                    </div>`;
            }).join("");

            const benchSlots = Array.from({ length: 7 }).map((_, idx) => {
                const selected = Number(state.benchIds[idx] || 0);
                const p = getPlayerById(selected);
                return `
                    <div class="lineup-slot-drop club-lineup-bench-slot" data-zone="bench" data-index="${idx}">
                        <div class="club-lineup-slot-label">Bench ${idx + 1}</div>
                        ${p ? `<div class="lineup-draggable club-lineup-player-chip club-lineup-player-chip--bench" draggable="true" data-player-id="${p.id}" data-from-zone="bench" data-from-index="${idx}">
                                <span class="club-lineup-player-name">${htmlEscape(p.name)}</span>
                                <span class="club-lineup-player-meta">${htmlEscape(p.position)}, OVR ${p.overall ?? "-"}</span>
                            </div>`
                        : `<div class="club-lineup-slot-placeholder">Drop player</div>`}
                    </div>`;
            }).join("");

            const pool = poolCandidates().map(p => `
                <div class="lineup-draggable club-lineup-player-chip club-lineup-player-chip--pool" draggable="true" data-player-id="${p.id}" data-from-zone="pool" data-from-index="-1">
                    <span class="club-lineup-player-name">${htmlEscape(p.name)}</span>
                    <span class="club-lineup-player-meta">${htmlEscape(p.position)}, OVR ${p.overall ?? "-"}</span>
                </div>`).join("");

            return `
                <div class="club-lineup-desktop-shell">
                    <div class="training-block club-lineup-pitch-card">
                        <div class="club-lineup-panel-head">
                            <div>
                                <h4>Starting XI</h4>
                                <p class="fm-subtle">Drag directly onto the pitch. Each role stays visible, so bench-to-XI swaps no longer need page scrolling.</p>
                            </div>
                            <span class="fm-panel-action">Drag & drop</span>
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
                            <div class="club-lineup-panel-head">
                                <div>
                                    <h4>Selection Dock</h4>
                                    <p class="fm-subtle">Bench stays next to the pitch, with the remaining squad immediately below it.</p>
                                </div>
                            </div>
                            <div class="club-lineup-bench-grid">${benchSlots}</div>
                            <div class="club-lineup-pool-head">
                                <h4>Available pool</h4>
                                <span>${poolCandidates().length} ready</span>
                            </div>
                            <div class="lineup-slot-drop club-lineup-pool-list" data-zone="pool" data-index="-1">${pool || `<div class="club-lineup-pool-empty">No available players</div>`}</div>
                        </div>
                    </div>
                </div>`;
        };

        const renderMobileDropdown = (slots) => {
            const startersHtml = slots.map((slot, idx) => {
                const selected = Number(state.starterIds[idx] || 0);
                const options = candidatesForSlot(slot.role, selected);
                return `<label class="training-group-row" style="margin-bottom:6px;">
                    <span class="group-tag">${slot.label}</span>
                    <select class="starter-select" data-slot="${idx}">
                        <option value="">-- Empty --</option>
                        ${options.map(p => `<option value="${p.id}" ${Number(p.id) === selected ? "selected" : ""}>${htmlEscape(p.name)} (${htmlEscape(p.position)}, OVR ${p.overall ?? "-"})</option>`).join("")}
                    </select>
                </label>`;
            }).join("");
            const benchHtml = Array.from({ length: 7 }).map((_, idx) => {
                const selected = Number(state.benchIds[idx] || 0);
                const options = benchCandidates(selected);
                return `<label class="training-group-row" style="margin-bottom:6px;">
                    <span class="group-tag">Bench ${idx + 1}</span>
                    <select class="bench-select" data-slot="${idx}">
                        <option value="">-- Empty --</option>
                        ${options.map(p => `<option value="${p.id}" ${Number(p.id) === selected ? "selected" : ""}>${htmlEscape(p.name)} (${htmlEscape(p.position)}, OVR ${p.overall ?? "-"})</option>`).join("")}
                    </select>
                </label>`;
            }).join("");
            return `
                <div class="training-block" style="margin-top:14px;"><h3>Starting XI</h3><p class="training-note">Mobile: dropdown selection with position filters and unique player lock.</p>${startersHtml}</div>
                <div class="training-block" style="margin-top:14px;"><h3>Bench</h3>${benchHtml}</div>`;
        };

        const bindDesktopDnD = () => {
            let dragData = null;
            mainContent.querySelectorAll(".lineup-draggable").forEach(el => {
                el.addEventListener("dragstart", () => { dragData = { playerId: Number(el.dataset.playerId), fromZone: el.dataset.fromZone, fromIndex: Number(el.dataset.fromIndex) }; });
            });
            mainContent.querySelectorAll(".lineup-slot-drop").forEach(zone => {
                zone.addEventListener("dragover", e => e.preventDefault());
                zone.addEventListener("drop", e => {
                    e.preventDefault();
                    if (!dragData || !dragData.playerId) return;
                    const targetZone = zone.dataset.zone;
                    const targetIndex = Number(zone.dataset.index);
                    const targetRole = zone.dataset.role || null;
                    const player = getPlayerById(dragData.playerId);
                    if (!player || player.injured) return;
                    const sourceStarterRole = dragData.fromZone === "starter" ? formationToSlots(state.formation)[dragData.fromIndex]?.role : null;
                    if (targetZone === "starter") {
                        if (!canPlayRole(player, targetRole)) return;
                        clearPlayerFromState(dragData.playerId);
                        const displaced = assignToStarter(dragData.playerId, targetIndex);
                        if (dragData.fromZone === "starter" && dragData.fromIndex >= 0 && targetIndex !== dragData.fromIndex) {
                            if (displaced && canPlayRole(getPlayerById(displaced), sourceStarterRole)) state.starterIds[dragData.fromIndex] = displaced;
                            else state.starterIds[dragData.fromIndex] = null;
                        } else if (dragData.fromZone === "bench" && dragData.fromIndex >= 0) {
                            state.benchIds[dragData.fromIndex] = displaced || null;
                        }
                    } else if (targetZone === "bench") {
                        clearPlayerFromState(dragData.playerId);
                        const displaced = assignToBench(dragData.playerId, targetIndex);
                        if (dragData.fromZone === "starter" && dragData.fromIndex >= 0) {
                            const dp = getPlayerById(displaced);
                            if (dp && canPlayRole(dp, sourceStarterRole)) state.starterIds[dragData.fromIndex] = displaced;
                            else state.starterIds[dragData.fromIndex] = null;
                        } else if (dragData.fromZone === "bench" && dragData.fromIndex >= 0 && targetIndex !== dragData.fromIndex) {
                            state.benchIds[dragData.fromIndex] = displaced || null;
                        }
                    } else if (targetZone === "pool") {
                        if (dragData.fromZone === "starter" && dragData.fromIndex >= 0) state.starterIds[dragData.fromIndex] = null;
                        if (dragData.fromZone === "bench" && dragData.fromIndex >= 0) state.benchIds[dragData.fromIndex] = null;
                    }
                    normalizeSelectionState();
                    render2();
                });
            });
        };

        const bindMobileSelects = () => {
            mainContent.querySelectorAll('.starter-select').forEach(sel => {
                sel.addEventListener("change", () => {
                    const slot = Number(sel.getAttribute("data-slot"));
                    const id = Number(sel.value || 0) || null;
                    if (id) { state.starterIds = state.starterIds.map((val, idx2) => idx2 !== slot && Number(val) === id ? null : val); state.benchIds = state.benchIds.map(val => Number(val) === id ? null : val); }
                    state.starterIds[slot] = id;
                    normalizeSelectionState();
                    render2();
                });
            });
            mainContent.querySelectorAll('.bench-select').forEach(sel => {
                sel.addEventListener("change", () => {
                    const slot = Number(sel.getAttribute("data-slot"));
                    const id = Number(sel.value || 0) || null;
                    if (id) { state.starterIds = state.starterIds.map(val => Number(val) === id ? null : val); state.benchIds = state.benchIds.map((val, idx2) => idx2 !== slot && Number(val) === id ? null : val); }
                    state.benchIds[slot] = id;
                    normalizeSelectionState();
                    render2();
                });
            });
        };

        normalizeSelectionState({ autofillStarters: true, autofillBench: true });

        const render2 = () => {
            normalizeSelectionState();
            const slots = formationToSlots(state.formation);
            const injuredCount = players.filter(p => p.injured).length;
            const currentPageId = deps.getCurrentPageId?.() || 'formations';

            let html = `<div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Club tactics</div>
                            <h2>Tactic Editor</h2>
                            <p class="fm-subtle">Manage the base shape, XI, bench, and the advanced tactics editor flow from one club screen.</p>
                        </div>
                        ${buildClubActionsHtml(currentPageId === 'tacticEditor' ? 'tacticEditor' : 'formations')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${state.starterIds.filter(Boolean).length}/11</strong><span>Starting XI</span></div>
                        <div><strong>${state.benchIds.filter(Boolean).length}/7</strong><span>Bench</span></div>
                        <div><strong>${htmlEscape(state.formation)}</strong><span>Shape</span></div>
                        <div><strong>${injuredCount}</strong><span>Unavailable</span></div>
                    </div>
                </section>
                <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Tactics board</h3>
                        <p class="fm-subtle">Pick the base shape, choose style, and save the exact XI + bench order.</p>
                    </div>
                    <span class="fm-panel-action">${htmlEscape(state.style)}</span>
                </div>
                <h3>Formation: <span id="currentFormation">${htmlEscape(state.formation)}</span></h3>
                <div class="cs-tactics-grid">`;
            availableFormations.forEach(f => { html += `<div class="cs-tactics-btn ${f === state.formation ? "active" : ""}" data-formation="${htmlEscape(f)}">${htmlEscape(f)}</div>`; });
            html += `</div>
                <h3 style="margin-top:20px;">Style: <span id="currentStyle">${htmlEscape(state.style)}</span></h3>
                <div class="cs-tactics-grid">`;
            availableStyles.forEach(s => { html += `<div class="cs-tactics-btn ${s === state.style ? "active" : ""}" data-style="${htmlEscape(s)}">${htmlEscape(s)}</div>`; });
            html += `</div>
                <p class="training-note" style="margin-top:12px;">Formation slots: DEF ${slots.filter(s2 => s2.role === "DEF").length}, MID ${slots.filter(s2 => s2.role === "MID").length}, ATT ${slots.filter(s2 => s2.role === "ATT").length}. Injured unavailable: ${injuredCount}.</p>`;
            html += isMobile ? renderMobileDropdown(slots) : renderDesktopDnD(slots);
            html += `
                <div class="training-actions" style="margin-top:14px;">
                    <button id="save-tactics-main" class="big-button">Save Tactics + XI + Bench</button>
                </div>
                <p style="margin-top:14px; color:#9aa0a6;">Desktop uses drag & drop. Mobile uses filtered dropdowns with unique player lock.</p>
            </section>
            </div>`;
            mainContent.innerHTML = html;

            mainContent.querySelectorAll('.cs-tactics-btn[data-formation]').forEach(btn => {
                btn.addEventListener('click', () => {
                    state.formation = btn.getAttribute("data-formation");
                    state.starterIds = new Array(11).fill(null);
                    normalizeSelectionState({ autofillStarters: true, autofillBench: true });
                    render2();
                });
            });
            mainContent.querySelectorAll('.cs-tactics-btn[data-style]').forEach(btn => {
                btn.addEventListener('click', () => { state.style = btn.getAttribute("data-style"); render2(); });
            });
            if (isMobile) bindMobileSelects();
            else bindDesktopDnD();

            const saveBtn = document.getElementById("save-tactics-main");
            if (saveBtn) {
                saveBtn.addEventListener("click", async () => {
                    saveBtn.disabled = true;
                    const dedupStarter = [];
                    const used = new Set();
                    state.starterIds.forEach(id => { if (id && !used.has(id) && getPlayerById(id)) { used.add(id); dedupStarter.push(id); } });
                    const dedupBench = [];
                    state.benchIds.forEach(id => { if (id && !used.has(id) && getPlayerById(id)) { used.add(id); dedupBench.push(id); } });
                    const res = await authFetch(`/teams/${getTeamId()}/lineup-template`, {
                        method: "PUT",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ formation: state.formation, style: state.style, starterIds: dedupStarter, benchIds: dedupBench })
                    });
                    if (res.ok) {
                        const savedPayload = await res.json();
                        state.formation = savedPayload?.formation || state.formation;
                        state.style = savedPayload?.style || state.style;
                        state.starterIds = Array.isArray(savedPayload?.starterIds) ? savedPayload.starterIds.map(Number).filter(Number.isFinite).slice(0, 11) : state.starterIds;
                        state.benchIds = Array.isArray(savedPayload?.benchIds) ? savedPayload.benchIds.map(Number).filter(Number.isFinite).slice(0, 7) : state.benchIds;
                        localStorage.setItem("main_app_tactics_formation", state.formation);
                        localStorage.setItem("main_app_tactics_style", state.style);
                    }
                    saveBtn.disabled = false;
                    saveBtn.textContent = res.ok ? "Saved" : "Save failed";
                    setTimeout(() => { saveBtn.textContent = "Save Tactics + XI + Bench"; }, 1400);
                });
            }
        };
        render2();
    }

    return { loadFormations };
}
