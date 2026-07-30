// pages/views/training-view.js
import { htmlEscape } from './utils.js';

export function createTrainingView(deps) {
    const { authFetch, getTeamId, buildTrainingActionsHtml, buildPlayerProfileHeroHtml } = deps;

    async function loadTrainingReports() {
        const mainContent = document.getElementById("main-content");
        const teamId = getTeamId();
        const playersRes = await authFetch(`/teams/${teamId}/players`);
        if (!playersRes.ok) {
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Training ground</div>
                                <h2>Training Setup</h2>
                                <p class="fm-subtle">Could not load player data for the training setup page.</p>
                            </div>
                            ${buildTrainingActionsHtml('trainingSetup')}
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid">
                            <div><strong>0</strong><span>Players</span></div>
                            <div><strong>0</strong><span>Advanced slots</span></div>
                            <div><strong>0</strong><span>Formation pool</span></div>
                            <div><strong>4</strong><span>Training groups</span></div>
                        </div>
                    </section>
                </div>`;
            return;
        }
        const players = await playersRes.json();

        const GROUPS = ["GK", "DEF", "MID", "ATT"];
        const SKILLS_ALL = ["pace", "defending", "technique", "passing"];
        const SKILLS_BY_GROUP = {
            GK: ["goalkeeper", ...SKILLS_ALL],
            DEF: ["defending", ...SKILLS_ALL.filter(s => s !== "defending")],
            MID: ["playmaker", ...SKILLS_ALL],
            ATT: ["shooting", ...SKILLS_ALL]
        };
        const ROLE_OPTIONS = ["GK", "DEF", "MID", "ATT"];
        const defaultGroupSkills = { GK: "goalkeeper", DEF: "defending", MID: "playmaker", ATT: "shooting" };

        const setupRes = await authFetch(`/training/setup/team/${teamId}`);
        const setup = setupRes.ok ? await setupRes.json() : null;

        const state = {
            groupSkills: {
                GK: setup?.groupSkills?.GK || defaultGroupSkills.GK,
                DEF: setup?.groupSkills?.DEF || defaultGroupSkills.DEF,
                MID: setup?.groupSkills?.MID || defaultGroupSkills.MID,
                ATT: setup?.groupSkills?.ATT || defaultGroupSkills.ATT
            },
            advanced: Array.isArray(setup?.advancedAssignments) ? setup.advancedAssignments.slice(0, 10).map(a => ({
                playerId: Number(a.playerId),
                role: ROLE_OPTIONS.includes((a.role || "").toUpperCase()) ? a.role.toUpperCase() : "MID"
            })) : [],
            general: [],
            selectedReport: null,
            selectedPlayerGraph: null,
            loadingReport: false
        };

        const allIds = new Set(players.map(p => p.id));
        state.advanced = state.advanced.filter(a => allIds.has(a.playerId));
        const advancedIds = new Set(state.advanced.map(a => a.playerId));
        state.general = players.map(p => p.id).filter(id => !advancedIds.has(id));

        const getPlayer = id => players.find(p => p.id === id);
        const skillLabel = skill => skill.charAt(0).toUpperCase() + skill.slice(1);
        const playerBadge = p => `${p.name} (${p.position}, OVR ${p.overall ?? "-"})`;

        function colorByIntDelta(delta) {
            if (delta > 0) return "#4caf50";
            if (delta < 0) return "#f44336";
            return "#b7bec9";
        }

        function moveToAdvanced(playerId, role = "MID") {
            if (state.advanced.some(a => a.playerId === playerId) || state.advanced.length >= 10) return;
            state.general = state.general.filter(id => id !== playerId);
            state.advanced.push({ playerId, role });
        }

        function moveToGeneral(playerId) {
            state.advanced = state.advanced.filter(a => a.playerId !== playerId);
            if (!state.general.includes(playerId)) state.general.push(playerId);
        }

        async function loadSummaries() {
            const res = await authFetch(`/training/weekly/team/${teamId}/reports`);
            if (!res.ok) return [];
            return await res.json();
        }

        async function saveSetup() {
            const payload = {
                teamId,
                groupSkills: state.groupSkills,
                advancedAssignments: state.advanced.map(a => ({ playerId: a.playerId, role: a.role }))
            };
            const res = await authFetch(`/training/setup/team/${teamId}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            return res.ok;
        }

        async function runTrainingWeek() {
            await saveSetup();
            const res = await authFetch(`/training/weekly/team/${teamId}/run`, { method: "POST" });
            if (!res.ok) return null;
            return await res.json();
        }

        async function openWeekReport(season, week) {
            state.loadingReport = true;
            await render();
            const res = await authFetch(`/training/weekly/team/${teamId}/reports/${season}/${week}`);
            state.loadingReport = false;
            if (!res.ok) return;
            state.selectedReport = await res.json();
            state.selectedPlayerGraph = null;
            await render();
        }

        async function openPlayerGraph(playerId) {
            const res = await authFetch(`/training/weekly/team/${teamId}/player/${playerId}/graph`);
            if (!res.ok) return;
            state.selectedPlayerGraph = {
                playerId,
                player: getPlayer(playerId),
                points: await res.json()
            };
            await render();
        }

        function renderReportTable() {
            if (state.loadingReport) return `<p>Loading report...</p>`;
            if (!state.selectedReport) return `<p class="training-empty">Select a week report.</p>`;

            const report = state.selectedReport;
            let html = `<h3>Report: Season ${report.seasonNumber} • Week ${report.weekNumber}</h3>`;
            html += `<div class="training-report-table-wrap"><table class="training-report-table"><thead><tr><th>Player</th><th>Role</th><th>DT Skill</th><th>Advanced</th><th>Skills (after / weekly delta / int delta)</th></tr></thead><tbody>`;
            (report.players || []).forEach(p => {
                const skillsText = (p.skills || []).map(s => {
                    const intDelta = Number(s.integerChange || 0);
                    const decDelta = Number(s.decimalChange ?? (Number(s.after || 0) - Number(s.before || 0)));
                    const decDeltaText = `${decDelta >= 0 ? "+ " : "- "}${Math.abs(decDelta).toFixed(2)}`;
                    const intDeltaText = `${intDelta >= 0 ? "+ " : "- "}${Math.abs(intDelta)}`;
                    return `<span style="color:${colorByIntDelta(intDelta)}; font-weight:700;">${skillLabel(s.skill)} ${Number(s.after).toFixed(2)} (Delta ${decDeltaText} | int ${intDeltaText})</span>`;
                }).join(" | ");
                html += `<tr>
                    <td><span class="cs-clickable" data-training-player-graph="${p.playerId}">${htmlEscape(p.playerName)}</span></td>
                    <td>${htmlEscape(p.role)}</td>
                    <td>${htmlEscape(skillLabel(p.directTrainingSkill || "-"))}</td>
                    <td>${p.advancedTraining ? "Yes" : "No"}</td>
                    <td>${skillsText}</td>
                </tr>`;
            });
            html += `</tbody></table></div>`;
            return html;
        }

        function renderGraph() {
            if (!state.selectedPlayerGraph) return "";
            const graph = state.selectedPlayerGraph;
            const points = Array.isArray(graph.points) ? graph.points : [];
            if (points.length === 0) {
                return `<div class="training-block"><h3>${htmlEscape(graph.player?.name || "Player")} - Training Graph</h3><p class="training-empty">No graph data.</p></div>`;
            }

            const weekKeys = Array.from(new Set(points.map(p => `${p.seasonNumber}-${p.weekNumber}`)))
                .sort((a, b) => {
                    const [sa, wa] = a.split("-").map(Number);
                    const [sb, wb] = b.split("-").map(Number);
                    return sa === sb ? wa - wb : sa - sb;
                });

            const bySkill = {};
            points.forEach(p => {
                if (!bySkill[p.skill]) bySkill[p.skill] = {};
                bySkill[p.skill][`${p.seasonNumber}-${p.weekNumber}`] = p.value;
            });

            let html = `<div class="training-block" style="margin-top:14px;"><h3>${htmlEscape(graph.player?.name || "Player")} - Training Graph</h3>`;
            html += `<div class="training-report-table-wrap"><table class="training-report-table"><thead><tr><th>Skill</th>`;
            weekKeys.forEach(k => {
                const [s, w] = k.split("-");
                html += `<th>S${s}W${w}</th>`;
            });
            html += `</tr></thead><tbody>`;

            Object.keys(bySkill).forEach(skill => {
                let prevInt = null;
                html += `<tr><td>${htmlEscape(skillLabel(skill))}</td>`;
                weekKeys.forEach(k => {
                    const val = bySkill[skill][k];
                    if (typeof val !== "number") {
                        html += `<td>-</td>`;
                        return;
                    }
                    const currInt = Math.floor(val);
                    const delta = prevInt == null ? 0 : currInt - prevInt;
                    prevInt = currInt;
                    html += `<td style="color:${colorByIntDelta(delta)}; font-weight:700;">${Number(val).toFixed(2)}</td>`;
                });
                html += `</tr>`;
            });

            html += `</tbody></table></div></div>`;
            return html;
        }

        async function render() {
            let html = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Training ground</div>
                            <h2>Training Setup</h2>
                            <p class="fm-subtle">Advanced and formation training stay intact, but the page now opens directly with the same action-row language as Club.</p>
                        </div>
                        ${buildTrainingActionsHtml('trainingSetup')}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${players.length}</strong><span>Players</span></div>
                        <div><strong>${state.advanced.length}</strong><span>Advanced slots</span></div>
                        <div><strong>${state.general.length}</strong><span>Formation pool</span></div>
                        <div><strong>${GROUPS.length}</strong><span>Training groups</span></div>
                    </div>
                </section>
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Weekly plan</h3>
                            <p class="fm-subtle">Advanced + Formation training. Wingers are under MID group. Stamina is automatic.</p>
                        </div>
                        <span class="fm-panel-action">Setup</span>
                    </div>
                    <div class="training-grid training-grid-setup">
                    <div class="training-block training-block-groups">
                        <h3>Formation Training Groups</h3>
                        <div class="training-groups">`;

            GROUPS.forEach(group => {
                html += `
                    <label class="training-group-row">
                        <span class="group-tag">${group}</span>
                        <select data-group="${group}" class="group-skill-select">
                            ${(SKILLS_BY_GROUP[group] || []).map(opt => `<option value="${opt}" ${state.groupSkills[group] === opt ? "selected" : ""}>${skillLabel(opt)}</option>`).join("")}
                        </select>
                    </label>`;
            });

            html += `
                        </div>
                    </div>

                    <div class="training-side-grid">
                        <div class="training-block">
                            <h3>Advanced Training (max 10 players)</h3>
                            <div id="advanced-drop" class="training-dropzone">
                                ${state.advanced.length === 0 ? `<div class="training-empty">Drop players here</div>` : ""}
                                ${state.advanced.map((entry, idx) => {
                                    const p = getPlayer(entry.playerId);
                                    if (!p) return "";
                                    return `
                                    <div class="training-player-card" draggable="true" data-player-id="${p.id}" data-origin="advanced">
                                        <div class="training-player-main">
                                            <strong>${htmlEscape(p.name)}</strong>
                                            <small>${htmlEscape(playerBadge(p))}</small>
                                        </div>
                                        <select class="adv-role-select" data-player-id="${p.id}">
                                            ${ROLE_OPTIONS.map(role => `<option value="${role}" ${entry.role === role ? "selected" : ""}>${role}</option>`).join("")}
                                        </select>
                                        <button class="mini-btn" data-remove-adv="${idx}">Remove</button>
                                    </div>`;
                                }).join("")}
                            </div>
                            <div class="quick-add-wrap" style="margin-top:10px;">
                                <label style="font-size:0.88rem; color:#9aa7bc;">Mobile fallback: add player to advanced</label>
                                <select id="quick-player-select">
                                    <option value="">Select player...</option>
                                    ${state.general.map(id => {
                                        const p = getPlayer(id);
                                        if (!p) return "";
                                        return `<option value="${p.id}">${htmlEscape(playerBadge(p))}</option>`;
                                    }).join("")}
                                </select>
                                <select id="quick-role-select">
                                    ${ROLE_OPTIONS.map(role => `<option value="${role}">${role}</option>`).join("")}
                                </select>
                                <button id="quick-add-advanced" class="big-button" style="padding:8px 12px;">Add to Advanced</button>
                            </div>
                        </div>

                        <div class="training-block training-block-pool">
                            <h3>Player Pool</h3>
                            <div class="training-pools">
                                <div>
                                    <h4>Formation Training Pool</h4>
                                    <div id="general-drop" class="training-dropzone">
                                        ${state.general.length === 0 ? `<div class="training-empty">No players in formation pool</div>` : ""}
                                        ${state.general.map(id => {
                                            const p = getPlayer(id);
                                            if (!p) return "";
                                            return `
                                            <div class="training-player-card" draggable="true" data-player-id="${p.id}" data-origin="general">
                                                <div class="training-player-main">
                                                    <strong>${htmlEscape(p.name)}</strong>
                                                    <small>${htmlEscape(playerBadge(p))}</small>
                                                </div>
                                            </div>`;
                                        }).join("")}
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="training-actions">
                    <button id="save-training-setup" class="big-button">Save Setup</button>
                    <button id="run-training-week" class="big-button" style="margin-left:10px; background:#145d39;">Run Weekly Training</button>
                </div>

                    <div class="training-note" style="margin-top:12px;">
                        Weekly report history is available in <strong>Training Reports</strong>.
                    </div>
                </section>
            </div>`;

            mainContent.innerHTML = html;
            bindUi();
        }

        function bindUi() {
            mainContent.querySelectorAll(".group-skill-select").forEach(sel => {
                sel.addEventListener("change", () => {
                    state.groupSkills[sel.getAttribute("data-group")] = sel.value;
                });
            });
            mainContent.querySelectorAll(".adv-role-select").forEach(sel => {
                sel.addEventListener("change", () => {
                    const playerId = Number(sel.getAttribute("data-player-id"));
                    const row = state.advanced.find(a => a.playerId === playerId);
                    if (row) row.role = sel.value;
                });
            });
            mainContent.querySelectorAll("[data-remove-adv]").forEach(btn => {
                btn.addEventListener("click", () => {
                    const idx = Number(btn.getAttribute("data-remove-adv"));
                    const entry = state.advanced[idx];
                    if (entry) moveToGeneral(entry.playerId);
                    render();
                });
            });

            let dragPlayerId = null;
            let dragOrigin = null;
            mainContent.querySelectorAll('.training-player-card[draggable="true"]').forEach(card => {
                card.addEventListener("dragstart", () => {
                    dragPlayerId = Number(card.getAttribute("data-player-id"));
                    dragOrigin = card.getAttribute("data-origin");
                });
            });

            const advancedDrop = document.getElementById("advanced-drop");
            const generalDrop = document.getElementById("general-drop");
            [advancedDrop, generalDrop].forEach(zone => zone && zone.addEventListener("dragover", e => e.preventDefault()));

            if (advancedDrop) {
                advancedDrop.addEventListener("drop", e => {
                    e.preventDefault();
                    if (dragPlayerId && dragOrigin === "general") moveToAdvanced(dragPlayerId, "MID");
                    render();
                });
            }
            if (generalDrop) {
                generalDrop.addEventListener("drop", e => {
                    e.preventDefault();
                    if (dragPlayerId && dragOrigin === "advanced") moveToGeneral(dragPlayerId);
                    render();
                });
            }

            const saveBtn = document.getElementById("save-training-setup");
            if (saveBtn) {
                saveBtn.addEventListener("click", async () => {
                    saveBtn.disabled = true;
                    const ok = await saveSetup();
                    saveBtn.disabled = false;
                    saveBtn.textContent = ok ? "Saved" : "Save failed";
                    setTimeout(() => { saveBtn.textContent = "Save Setup"; }, 1100);
                });
            }

            const runBtn = document.getElementById("run-training-week");
            if (runBtn) {
                runBtn.addEventListener("click", async () => {
                    runBtn.disabled = true;
                    runBtn.textContent = "Running...";
                    const report = await runTrainingWeek();
                    runBtn.disabled = false;
                    runBtn.textContent = "Run Weekly Training";
                    if (report && Number.isFinite(report.seasonNumber) && Number.isFinite(report.weekNumber)) {
                        sessionStorage.setItem("training_report_focus", `${report.seasonNumber}|${report.weekNumber}`);
                        await loadTrainingReportsPage();
                        return;
                    }
                    await render();
                });
            }

            const quickAddBtn = document.getElementById("quick-add-advanced");
            if (quickAddBtn) {
                quickAddBtn.addEventListener("click", () => {
                    const playerId = Number(document.getElementById("quick-player-select")?.value || 0);
                    const role = (document.getElementById("quick-role-select")?.value || "MID").toUpperCase();
                    if (!playerId) return;
                    moveToAdvanced(playerId, ROLE_OPTIONS.includes(role) ? role : "MID");
                    render();
                });
            }

            mainContent.querySelectorAll("[data-open-week]").forEach(item => {
                item.addEventListener("click", async () => {
                    const [season, week] = (item.getAttribute("data-open-week") || "").split("|").map(Number);
                    if (Number.isFinite(season) && Number.isFinite(week)) {
                        await openWeekReport(season, week);
                    }
                });
            });

            mainContent.querySelectorAll("[data-training-player-graph]").forEach(item => {
                item.addEventListener("click", async () => {
                    const playerId = Number(item.getAttribute("data-training-player-graph"));
                    if (playerId) {
                        await openPlayerGraph(playerId);
                    }
                });
            });
        }

        await render();
    }

    async function loadTrainingReportsPage() {
        const mainContent = document.getElementById("main-content");
        const teamId = getTeamId();
        const playersRes = await authFetch(`/teams/${teamId}/players`);
        if (!playersRes.ok) {
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Training reports</div>
                                <h2>Training Reports</h2>
                                <p class="fm-subtle">Could not load player data for the reports page.</p>
                            </div>
                            ${buildTrainingActionsHtml('trainingReports')}
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid">
                            <div><strong>0</strong><span>Weeks</span></div>
                            <div><strong>0</strong><span>Players</span></div>
                            <div><strong>0</strong><span>Reports</span></div>
                            <div><strong>0</strong><span>Graphs</span></div>
                        </div>
                    </section>
                </div>`;
            return;
        }
        const players = await playersRes.json();
        const playerById = new Map(players.map(p => [p.id, p]));

        let selectedReport = null;
        let selectedPlayerGraph = null;

        const colorByIntDelta = (delta) => {
            if (delta > 0) return "#4caf50";
            if (delta < 0) return "#f44336";
            return "#b7bec9";
        };
        const skillLabel = (skill) => skill.charAt(0).toUpperCase() + skill.slice(1);
        const skillShortLabel = (skill) => {
            switch ((skill || "").toLowerCase()) {
                case "goalkeeper": return "GK";
                case "defending": return "DEF";
                case "pace": return "PAC";
                case "technique": return "TEC";
                case "playmaker": return "PLY";
                case "passing": return "PAS";
                case "shooting": return "SHT";
                case "stamina": return "STA";
                default: return skillLabel(skill).slice(0, 3).toUpperCase();
            }
        };
        const skillIcon = (skill) => {
            switch ((skill || "").toLowerCase()) {
                case "goalkeeper": return "\u{1F9E4}";
                case "defending": return "\u{1F6E1}\uFE0F";
                case "pace": return "\u{1F4A8}";
                case "technique": return "\u2692\uFE0F";
                case "playmaker": return "\u{1F9E0}";
                case "passing": return "\u{1F381}";
                case "shooting": return "\u{1F3AF}";
                case "stamina": return "\u{1F50B}";
                default: return "\u2022";
            }
        };
        const normalizeWeekKey = (season, week) => `${Number(season)}|${Number(week)}`;
        const weekShort = (season, week) => `S${Number(season)}W${Number(week)}`;
        const weekLabel = (season, week) => `Season ${Number(season)} Week ${Number(week)}`;
        const skillTone = (delta, isDirectTraining) => {
            if (delta > 0) return "#4caf50";
            if (delta < 0) return "#f44336";
            if (isDirectTraining) return "#9d4edd";
            return "#dce6f5";
        };
        const trackedSkills = ["goalkeeper", "defending", "pace", "technique", "playmaker", "passing", "shooting", "stamina"];
        const skillHeaderCells = () => trackedSkills.map(skill => `
            <th class="training-skill-col" title="${htmlEscape(skillLabel(skill))}">
                <div class="training-skill-head">
                    <span class="training-skill-head-icon">${skillIcon(skill)}</span>
                    <span class="training-skill-head-text">${htmlEscape(skillShortLabel(skill))}</span>
                </div>
            </th>`).join('');
        const buildSkillMetricCell = ({ valueText = '-', deltaText = '\u2014', tone = '#dce6f5', isDirect = false, isEmpty = false, title = '' } = {}) => `
            <td class="training-skill-col${isDirect ? ' is-direct-focus' : ''}${isEmpty ? ' is-empty' : ''}"${title ? ` title="${htmlEscape(title)}"` : ''}>
                <div class="training-skill-metric" style="--skill-tone:${tone};">
                    <span class="training-skill-value">${htmlEscape(String(valueText))}</span>
                    <span class="training-skill-delta">${htmlEscape(String(deltaText))}</span>
                </div>
            </td>`;

        async function fetchSummaries() {
            const res = await authFetch(`/training/weekly/team/${teamId}/reports`);
            if (!res.ok) return [];
            const all = await res.json();
            const unique = [];
            const seen = new Set();
            all.forEach(s => {
                const key = normalizeWeekKey(s.seasonNumber, s.weekNumber);
                if (seen.has(key)) return;
                seen.add(key);
                unique.push(s);
            });
            return unique;
        }

        async function fetchReport(season, week) {
            const res = await authFetch(`/training/weekly/team/${teamId}/reports/${season}/${week}`);
            if (!res.ok) return null;
            return await res.json();
        }

        async function openPlayerGraph(playerId) {
            const res = await authFetch(`/training/weekly/team/${teamId}/player/${playerId}/graph`);
            if (!res.ok) return;
            selectedPlayerGraph = {
                playerId,
                player: playerById.get(playerId),
                points: await res.json()
            };
            await render();
        }

        function renderReportCards(report) {
            if (!report) return `<p class="training-empty">Select a week report.</p>`;
            const posOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
            const reportPlayers = (report.players || [])
                .sort((a, b) => {
                    const aPlayer = playerById.get(a.playerId);
                    const bPlayer = playerById.get(b.playerId);
                    const posDiff = (posOrder[aPlayer?.position] ?? 5) - (posOrder[bPlayer?.position] ?? 5);
                    if (posDiff !== 0) return posDiff;
                    return String(a.playerName || "").localeCompare(String(b.playerName || ""));
                })
                .map(p => {
                const player = playerById.get(p.playerId);
                const playerName = player?.name || p.playerName || `#${p.playerId}`;
                const skillByName = new Map((p.skills || []).map(s => [String(s.skill || "").toLowerCase(), s]));
                const pos = player?.position || "-";
                const age = Number.isFinite(Number(player?.age)) ? Number(player.age) : "-";
                const rating = Number.isFinite(Number(player?.rating)) ? Number(player.rating) : "-";
                const form = Number.isFinite(Number(player?.form)) ? Number(player.form).toFixed(1) : "-";
                const goals = Number(player?.goals || 0);
                const assists = Number(player?.assists || 0);
                const skillCells = trackedSkills.map(skill => {
                    const s = skillByName.get(skill);
                    if (!s) {
                        return buildSkillMetricCell({
                            isEmpty: true,
                            title: `${skillLabel(skill)}: no report data for this week`
                        });
                    }
                    const intDelta = Number(s.integerChange || 0);
                    const decDelta = Number(s.decimalChange ?? (Number(s.after || 0) - Number(s.before || 0)));
                    const decSign = decDelta > 0 ? "+" : decDelta < 0 ? "-" : "";
                    const title = `${skillLabel(skill)}: ${Number(s.after || 0).toFixed(2)} | Delta ${decSign}${Math.abs(decDelta).toFixed(2)} | Int ${intDelta >= 0 ? "+" : "-"}${Math.abs(intDelta)}`;
                    const tone = skillTone(intDelta, skill === String(p.directTrainingSkill || "").toLowerCase());
                    const isDirect = skill === String(p.directTrainingSkill || "").toLowerCase();
                    return buildSkillMetricCell({
                        valueText: Number(s.after || 0).toFixed(2),
                        deltaText: decDelta === 0 ? '\u00B10.00' : `${decSign}${Math.abs(decDelta).toFixed(2)}`,
                        tone,
                        isDirect,
                        title
                    });
                }).join("");
                return `
                    <tr class="fm-squad-row training-report-player-row" data-open-training-player="${p.playerId}">
                        <td class="sq-name">
                            <span class="sq-player-link">${htmlEscape(playerName)}</span>
                            <span class="ps-team">Form ${form} \u2022 G ${goals} \u2022 A ${assists}</span>
                        </td>
                        <td class="sq-pos">${htmlEscape(pos)}</td>
                        <td class="sq-age">${age}</td>
                        <td class="sq-rating">${rating}</td>
                        <td class="sq-role">${htmlEscape(p.role || '-')}</td>
                        <td class="sq-focus">${htmlEscape(skillShortLabel(p.directTrainingSkill || '-'))}</td>
                        <td class="sq-mode">${p.advancedTraining ? 'ADV' : 'FORM'}</td>
                        ${skillCells}
                    </tr>`;
            }).join('');

            const playerCount = report.players?.length || 0;
            const advancedCount = (report.players || []).filter(player => player.advancedTraining).length;
            return `
                <div class="fm-page training-report-shell">
                    <section class="fm-panel fm-club-hero training-report-hero">
                        <button class="back-to-dashboard" data-training-week-back="1">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Training report</div>
                                <h2>${weekLabel(report.seasonNumber, report.weekNumber)}</h2>
                                <p class="fm-subtle">Week list is hidden while this squad-style report is open. Click any player row for the detailed progress view.</p>
                            </div>
                            <div>
                                ${buildTrainingActionsHtml('trainingReports')}
                            </div>
                        </div>
                        <div class="training-report-actions">
                                <span class="fm-player-chip secondary">${playerCount} players</span>
                                <span class="fm-player-chip secondary">${advancedCount} ADV</span>
                                <span class="fm-player-chip secondary">Focus skill accented</span>
                        </div>
                    </section>
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <h3>Squad report</h3>
                            <span class="fm-panel-action">First-team inspired layout</span>
                        </div>
                        <div class="fm-squad-wrap">
                            <table class="fm-squad training-report-squad">
                                <thead>
                                    <tr>
                                        <th class="sq-name">Name</th>
                                        <th>Pos</th>
                                        <th class="sq-age">Age</th>
                                        <th class="sq-rating">Rating</th>
                                        <th>Role</th>
                                        <th>Focus</th>
                                        <th>Mode</th>
                                        ${skillHeaderCells()}
                                    </tr>
                                </thead>
                                <tbody>${reportPlayers}</tbody>
                            </table>
                        </div>
                    </section>
                </div>`;
        }

        function renderGraph() {
            if (!selectedPlayerGraph) return "";
            const player = selectedPlayerGraph.player;
            const points = Array.isArray(selectedPlayerGraph.points) ? selectedPlayerGraph.points : [];
            const currentPlayer = playerById.get(selectedPlayerGraph.playerId) || player || {};
            const headerHtml = buildPlayerProfileHeroHtml(currentPlayer, {
                backLabel: 'Back',
                eyebrow: 'Training progress',
                teamName: 'Training reports',
                ratingSummary: {
                    averageRating10: currentPlayer?.rating,
                    matchesPlayed: currentPlayer?.played ?? currentPlayer?.matchesPlayed ?? 0
                },
                backButtonId: 'training-player-back-button',
                backButtonAttributes: 'data-training-report-back="1"',
                bannerClassName: 'training-player-banner'
            });
            if (points.length === 0) {
                return `${headerHtml}
                    <section class="fm-panel">
                        ${buildTrainingActionsHtml('trainingReports')}
                    </section>
                    <section class="fm-panel">
                        <div class="fm-empty">No graph data.</div>
                    </section>`;
            }

            const weekMap = new Map();
            points.forEach(point => {
                const key = normalizeWeekKey(point.seasonNumber, point.weekNumber);
                if (!weekMap.has(key)) {
                    weekMap.set(key, {
                        seasonNumber: point.seasonNumber,
                        weekNumber: point.weekNumber,
                        role: point.role || "-",
                        directTrainingSkill: point.directTrainingSkill || "-",
                        advancedTraining: !!point.advancedTraining,
                        skills: {}
                    });
                }
                weekMap.get(key).skills[String(point.skill || "").toLowerCase()] = point;
            });

            const weeksAsc = [...weekMap.values()].sort((a, b) =>
                a.seasonNumber === b.seasonNumber ? a.weekNumber - b.weekNumber : a.seasonNumber - b.seasonNumber
            );
            const prevInts = {};
            weeksAsc.forEach(week => {
                week.skillMeta = {};
                trackedSkills.forEach(skill => {
                    const point = week.skills[skill];
                    if (!point) return;
                    const prevInt = prevInts[skill];
                    const delta = prevInt == null ? 0 : Number(point.integerValue) - Number(prevInt);
                    prevInts[skill] = Number(point.integerValue);
                    week.skillMeta[skill] = { point, delta };
                });
            });
            const weeks = [...weeksAsc].reverse();
            let html = `<div class="training-player-shell">
                ${headerHtml}
                <section class="fm-panel">
                    ${buildTrainingActionsHtml('trainingReports')}
                </section>
                <section class="fm-panel training-player-detail">
                    <div class="fm-panel-head">
                        <h3>Weekly progression</h3>
                        <span class="fm-panel-action">${weeks.length} tracked weeks</span>
                    </div>
                    <p class="training-note">Skill columns show exact values, with the week delta beneath. Focus skill stays accented; green means growth and red means decline.</p>
                    <div class="fm-squad-wrap">
                        <table class="fm-squad training-graph-squad">
                            <thead>
                                <tr>
                                    <th>Week</th>
                                    <th>Role</th>
                                    <th>Focus</th>
                                    <th>Mode</th>
                                    ${skillHeaderCells()}
                                </tr>
                            </thead>
                            <tbody>`;

            weeks.forEach(week => {
                const skillHtml = trackedSkills.map(skill => {
                    const meta = week.skillMeta?.[skill];
                    if (!meta?.point) {
                        return buildSkillMetricCell({
                            isEmpty: true,
                            title: `${skillLabel(skill)}: no tracked value`
                        });
                    }
                    const isDirect = skill === String(week.directTrainingSkill || "").toLowerCase();
                    const tone = skillTone(meta.delta, isDirect);
                    const deltaText = meta.delta === 0 ? '\u00B10' : `${meta.delta > 0 ? '+' : '-'}${Math.abs(meta.delta)}`;
                    return buildSkillMetricCell({
                        valueText: Number(meta.point.value).toFixed(2),
                        deltaText,
                        tone,
                        isDirect,
                        title: `${skillLabel(skill)}: ${Number(meta.point.value).toFixed(2)} | Weekly int delta ${deltaText}`
                    });
                }).join("");

                html += `<tr>
                    <td>${weekShort(week.seasonNumber, week.weekNumber)}</td>
                    <td>${htmlEscape(week.role || '-')}</td>
                    <td>${htmlEscape(skillShortLabel(week.directTrainingSkill || '-'))}</td>
                    <td>${week.advancedTraining ? 'ADV' : 'FORM'}</td>
                    ${skillHtml}
                </tr>`;
            });

            html += `</tbody></table></div></section></div>`;
            return html;
        }

        async function render() {
            const summaries = await fetchSummaries();
            const focusRaw = sessionStorage.getItem("training_report_focus");
            let focusSeason = null;
            let focusWeek = null;
            if (focusRaw && focusRaw.includes("|")) {
                const [s, w] = focusRaw.split("|").map(Number);
                if (Number.isFinite(s) && Number.isFinite(w)) {
                    focusSeason = s;
                    focusWeek = w;
                }
            }
            if (!selectedReport && focusSeason != null && focusWeek != null) {
                selectedReport = await fetchReport(focusSeason, focusWeek);
                sessionStorage.removeItem("training_report_focus");
            }

            if (selectedPlayerGraph) {
                mainContent.innerHTML = renderGraph();
            } else if (selectedReport) {
                mainContent.innerHTML = renderReportCards(selectedReport);
            } else {
                mainContent.innerHTML = `
                    <div class="fm-page fm-page--club">
                        <section class="fm-panel fm-club-hero">
                            <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                            <div class="fm-club-hero-main">
                                <div>
                                    <div class="fm-eyebrow">Training reports</div>
                                    <h2>Training Reports</h2>
                                    <p class="fm-subtle">Open any week to keep the current report/player drill-down flow, now wrapped in the same page shell as the rest of Training.</p>
                                </div>
                                ${buildTrainingActionsHtml('trainingReports')}
                            </div>
                            <div class="fm-medical-stat-grid team-summary-grid">
                                <div><strong>${summaries.length}</strong><span>Weeks logged</span></div>
                                <div><strong>${players.length}</strong><span>Players</span></div>
                                <div><strong>${summaries[0] ? weekLabel(summaries[0].seasonNumber, summaries[0].weekNumber) : '\u2014'}</strong><span>Latest report</span></div>
                                <div><strong>Live</strong><span>Click-through</span></div>
                            </div>
                        </section>
                        <section class="fm-panel">
                            <div class="fm-panel-head">
                                <div>
                                    <h3>Week archive</h3>
                                    <p class="fm-subtle">Purple marks the skill trained that week. Click a week to open the detailed report.</p>
                                </div>
                                <span class="fm-panel-action">Archive</span>
                            </div>
                            <div class="training-grid">
                                <div class="training-block">
                                    <h3>Weeks</h3>
                                    <div class="training-week-list">
                                        ${summaries.length === 0 ? `<div class="training-empty">No reports yet.</div>` : summaries.map(s => `<button type="button" class="training-week-item" data-open-week="${s.seasonNumber}|${s.weekNumber}"><strong>${weekLabel(s.seasonNumber, s.weekNumber)}</strong><span>Open detailed report</span></button>`).join("")}
                                    </div>
                                </div>
                                <div class="training-block">
                                    <div class="training-empty">Select a week to open the report.</div>
                                </div>
                            </div>
                        </section>
                    </div>
                `;
            }

            mainContent.onclick = async (event) => {
                const backEl = event.target.closest("[data-training-report-back]");
                if (backEl && mainContent.contains(backEl)) {
                    selectedPlayerGraph = null;
                    await render();
                    return;
                }

                const weekBackEl = event.target.closest("[data-training-week-back]");
                if (weekBackEl && mainContent.contains(weekBackEl)) {
                    selectedReport = null;
                    selectedPlayerGraph = null;
                    await render();
                    return;
                }

                const weekEl = event.target.closest("[data-open-week]");
                if (weekEl && mainContent.contains(weekEl)) {
                    const [season, week] = (weekEl.getAttribute("data-open-week") || "").split("|").map(Number);
                    const report = await fetchReport(season, week);
                    if (report) {
                        selectedReport = report;
                        selectedPlayerGraph = null;
                        await render();
                    }
                    return;
                }

                const playerEl = event.target.closest("[data-open-training-player]");
                if (playerEl && mainContent.contains(playerEl)) {
                    event.preventDefault();
                    event.stopPropagation();
                    const playerId = Number(playerEl.getAttribute("data-open-training-player"));
                    if (playerId) {
                        await openPlayerGraph(playerId);
                    }
                }
            };
        }

        await render();
    }

    return { loadTrainingReports, loadTrainingReportsPage };
}
