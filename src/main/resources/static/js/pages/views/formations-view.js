// pages/views/formations-view.js
import { htmlEscape } from './utils.js';
import { apiErrorMessage } from '../../auth.js';
import { createLineupBoard } from './lineup-board.js';

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

        // The pitch, the Selection Dock and the pool are one shared component now, because the match
        // screen has to look the same as this one (owner, 2026-10-16) and two copies of a pitch drift.
        // This page keeps what is genuinely its own: the formation and style pickers and the save.
        const board = createLineupBoard({
            // The pool above the dock: the dock's beginning was below the fold, and a drag that
            // starts from a card you cannot see is a drag you cannot make (owner, 2026-10-22).
            poolFirst: true,
            players,
            formation: state.formation,
            isMobile,
            onChange: next => {
                state.starterIds = next.starterIds;
                state.benchIds = next.benchIds;
                render2();
            }
        });

        board.setSelection(state.starterIds, state.benchIds);
        board.normalize({ autofillStarters: true, autofillBench: true });
        state.starterIds = board.selection().starterIds;
        state.benchIds = board.selection().benchIds;

        const render2 = () => {
            const slots = board.slots;
            const injuredCount = players.filter(p => p.injured).length;
            const currentPageId = deps.getCurrentPageId?.() || 'formations';

            let html = `<div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Club tactics</div>
                            <h2>Tactics Setup</h2>
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
                <p class="training-note" style="margin-top:12px;">Formation slots: DEF ${slots.filter(s2 => s2.role === "DEF").length}, MID ${slots.filter(s2 => s2.role === "MID").length}, ATT ${slots.filter(s2 => s2.role === "ATT").length}. Injured unavailable: ${injuredCount}.</p>
                <div data-lineup-board></div>
                <div class="training-actions" style="margin-top:14px;">
                    <button id="save-tactics-main" class="big-button">Save Tactics + XI + Bench</button>
                </div>
                <p style="margin-top:14px; color:#9aa0a6;">Desktop uses drag & drop. Mobile uses filtered dropdowns with unique player lock.</p>
            </section>
            </div>`;
            mainContent.innerHTML = html;

            // The board draws itself into its own container, so it can be re-rendered without this page
            // rebuilding everything around it.
            board.setSelection(state.starterIds, state.benchIds);
            board.render(mainContent.querySelector('[data-lineup-board]'));

            mainContent.querySelectorAll('.cs-tactics-btn[data-formation]').forEach(btn => {
                btn.addEventListener('click', () => {
                    state.formation = btn.getAttribute("data-formation");
                    board.setFormation(state.formation);
                    state.starterIds = new Array(11).fill(null);
                    state.benchIds = new Array(7).fill(null);
                    board.setSelection(state.starterIds, state.benchIds);
                    board.normalize({ autofillStarters: true, autofillBench: true });
                    state.starterIds = board.selection().starterIds;
                    state.benchIds = board.selection().benchIds;
                    render2();
                });
            });
            mainContent.querySelectorAll('.cs-tactics-btn[data-style]').forEach(btn => {
                btn.addEventListener('click', () => { state.style = btn.getAttribute("data-style"); render2(); });
            });

            const saveBtn = document.getElementById("save-tactics-main");
            if (saveBtn) {
                saveBtn.addEventListener("click", async () => {
                    saveBtn.disabled = true;
                    const squadIds = new Set(players.map(p => Number(p.id)));
                    const dedupStarter = [];
                    const used = new Set();
                    state.starterIds.forEach(id => { if (id && !used.has(id) && squadIds.has(Number(id))) { used.add(id); dedupStarter.push(id); } });
                    const dedupBench = [];
                    state.benchIds.forEach(id => { if (id && !used.has(id) && squadIds.has(Number(id))) { used.add(id); dedupBench.push(id); } });
                    let saved = false;
                    try {
                        const res = await authFetch(`/teams/${getTeamId()}/lineup-template`, {
                            method: "PUT",
                            headers: { "Content-Type": "application/json" },
                            body: JSON.stringify({ formation: state.formation, style: state.style, starterIds: dedupStarter, benchIds: dedupBench })
                        });
                        const savedPayload = await res.json();
                        state.formation = savedPayload?.formation || state.formation;
                        state.style = savedPayload?.style || state.style;
                        state.starterIds = Array.isArray(savedPayload?.starterIds) ? savedPayload.starterIds.map(Number).filter(Number.isFinite).slice(0, 11) : state.starterIds;
                        state.benchIds = Array.isArray(savedPayload?.benchIds) ? savedPayload.benchIds.map(Number).filter(Number.isFinite).slice(0, 7) : state.benchIds;
                        localStorage.setItem("main_app_tactics_formation", state.formation);
                        localStorage.setItem("main_app_tactics_style", state.style);
                        saved = true;
                    } catch (err) {
                        // The server refused the lineup and says why - eleven starters, a player who is
                        // not in the squad, a formation the club does not have. That sentence is shown
                        // instead of a bare "Save failed".
                        window.alert(apiErrorMessage(err, 'The lineup could not be saved.'));
                    }
                    saveBtn.disabled = false;
                    saveBtn.textContent = saved ? "Saved" : "Save failed";
                    setTimeout(() => { saveBtn.textContent = "Save Tactics + XI + Bench"; }, 1400);
                });
            }
        };
        render2();
    }

    return { loadFormations };
}
