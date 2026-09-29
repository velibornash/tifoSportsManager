// pages/views/training-view.js
import { htmlEscape } from './utils.js';

export function createTrainingView(deps) {
    const { authFetch, getTeamId, buildTrainingActionsHtml, buildPlayerProfileHeroHtml } = deps;

    // Shared by the setup screen and the reports screen. Declared at factory scope on purpose:
    // when each screen carried its own copy they drifted, and a screen that reaches for a helper
    // owned by another function is the exact thing that produced "render is not defined" before.
    const skillLabel = (skill) => skill.charAt(0).toUpperCase() + skill.slice(1);
    const colorByIntDelta = (delta) => {
        if (delta > 0) return "#4caf50";
        if (delta < 0) return "#f44336";
        return "#b7bec9";
    };

    async function loadTrainingReports() {
        return loadTrainingReportsPage();
    }

    // The group -> skill options below mirror TrainingProgressionService.normalizeDtSkill exactly.
    // It silently substitutes a default for anything it does not recognise, so a screen offering a
    // different list would let a manager pick something the engine then ignores.
    const TRAINING_GROUPS = ['GK', 'DEF', 'MID', 'ATT'];
    const SKILLS_BY_GROUP = {
        GK: ['goalkeeper', 'pace', 'defending', 'technique', 'passing'],
        DEF: ['defending', 'pace', 'technique', 'passing'],
        MID: ['playmaker', 'pace', 'defending', 'technique', 'passing'],
        ATT: ['shooting', 'pace', 'defending', 'technique', 'passing']
    };
    const DEFAULT_GROUP_SKILL = { GK: 'goalkeeper', DEF: 'defending', MID: 'playmaker', ATT: 'shooting' };
    const ROLE_OPTIONS = ['GK', 'DEF', 'MID', 'ATT'];
    const ADVANCED_SLOTS = 10;

    /**
     * The training setup screen: what each positional group trains, who trains as an advanced slot,
     * and the button that runs the week.
     *
     * <p>This screen had listeners and state but no renderer for a long time, so the router's
     * "trainingSetup" case fell through to the reports screen and both buttons opened the same
     * thing. Its own render() is defined here rather than shared with the reports screen on
     * purpose - a screen reaching into another function's scope is what broke the previous pair.
     */
    async function loadTrainingSetup() {
        const mainContent = document.getElementById("main-content");
        const teamId = getTeamId();

        const emptyState = (message) => `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Training ground</div>
                            <h2>Training Setup</h2>
                            <p class="fm-subtle">${htmlEscape(message)}</p>
                        </div>
                        ${buildTrainingActionsHtml('trainingSetup')}
                    </div>
                </section>
            </div>`;

        const playersRes = await authFetch(`/teams/${teamId}/players`);
        if (!playersRes.ok) {
            mainContent.innerHTML = emptyState('Could not load player data for the training setup page.');
            return;
        }
        const players = await playersRes.json();

        const setupRes = await authFetch(`/training/setup/team/${teamId}`).catch(() => null);
        const setup = setupRes && setupRes.ok ? await setupRes.json() : null;

        const state = {
            groupSkills: {},
            advanced: [],
            general: [],
            busy: false,
            message: '',
            messageIsError: false
        };
        TRAINING_GROUPS.forEach(group => {
            const saved = setup?.groupSkills?.[group];
            state.groupSkills[group] = SKILLS_BY_GROUP[group].includes(saved)
                ? saved
                : DEFAULT_GROUP_SKILL[group];
        });

        const allIds = new Set(players.map(p => p.id));
        state.advanced = (Array.isArray(setup?.advancedAssignments) ? setup.advancedAssignments : [])
            .map(a => ({
                playerId: Number(a.playerId),
                role: ROLE_OPTIONS.includes(String(a.role || '').toUpperCase()) ? String(a.role).toUpperCase() : 'MID'
            }))
            .filter(a => allIds.has(a.playerId))
            .slice(0, ADVANCED_SLOTS);
        // One player cannot hold two slots, so the pool is whoever is not already taken.
        const taken = new Set(state.advanced.map(a => a.playerId));
        state.general = players.filter(p => !taken.has(p.id)).map(p => p.id);

        const playerById = new Map(players.map(p => [p.id, p]));
        const playerName = id => {
            const p = playerById.get(id);
            return p ? `${p.name} (${p.position}, OVR ${p.overall ?? '-'})` : `Player ${id}`;
        };
        // Position -> training group. WNG and the other wide roles start with no group letter, so
        // they fall to MID, which is where the default group skill points them anyway.
        const groupForPosition = position => {
            const code = String(position || '').toUpperCase();
            return TRAINING_GROUPS.find(group => code.startsWith(group)) || 'MID';
        };

        function moveToAdvanced(playerId, role) {
            const index = state.advanced.findIndex(a => a.playerId === playerId);
            if (index >= 0) {
                state.advanced[index].role = ROLE_OPTIONS.includes(role) ? role : state.advanced[index].role;
                return;
            }
            if (state.advanced.length >= ADVANCED_SLOTS) {
                state.message = `All ${ADVANCED_SLOTS} advanced slots are taken. Remove one first.`;
                state.messageIsError = true;
                return;
            }
            state.general = state.general.filter(id => id !== playerId);
            state.advanced.push({ playerId, role: ROLE_OPTIONS.includes(role) ? role : 'MID' });
        }

        function moveToGeneral(playerId) {
            state.advanced = state.advanced.filter(a => a.playerId !== playerId);
            if (!state.general.includes(playerId)) state.general.push(playerId);
        }

        async function saveSetup() {
            const res = await authFetch(`/training/setup/team/${teamId}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    teamId,
                    groupSkills: state.groupSkills,
                    advancedAssignments: state.advanced.map(a => ({ playerId: a.playerId, role: a.role }))
                })
            });
            return res.ok;
        }

        function renderAdvancedRow(entry, index) {
            return `
                <div class="training-player-card">
                    <div class="training-player-main">
                        <strong>${htmlEscape(playerName(entry.playerId))}</strong>
                        <small>Advanced slot ${index + 1}</small>
                    </div>
                    <select class="adv-role-select" data-adv-role="${entry.playerId}" aria-label="Role for advanced slot ${index + 1}">
                        ${ROLE_OPTIONS.map(role => `<option value="${role}"${role === entry.role ? ' selected' : ''}>${role}</option>`).join('')}
                    </select>
                    <button type="button" class="mini-btn" data-remove-adv="${entry.playerId}">Remove</button>
                </div>`;
        }

        function render() {
            const freeSlots = ADVANCED_SLOTS - state.advanced.length;
            mainContent.innerHTML = `
                <div class="fm-page fm-page--club training-setup-card">
                    <section class="fm-panel fm-club-hero">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-club-hero-main">
                            <div>
                                <div class="fm-eyebrow">Training ground</div>
                                <h2>Training Setup</h2>
                                <p class="fm-subtle">Pick the skill each positional group trains, and who trains in an advanced slot. The advanced slot overrides the group skill for that player.</p>
                            </div>
                            ${buildTrainingActionsHtml('trainingSetup')}
                        </div>
                        <div class="fm-medical-stat-grid team-summary-grid">
                            <div><strong>${players.length}</strong><span>Players</span></div>
                            <div><strong>${state.advanced.length}/${ADVANCED_SLOTS}</strong><span>Advanced slots</span></div>
                            <div><strong>${freeSlots}</strong><span>Free slots</span></div>
                            <div><strong>${TRAINING_GROUPS.length}</strong><span>Training groups</span></div>
                        </div>
                    </section>

                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Group training</h3>
                                <p class="training-note">The skill every player in that group works on unless he holds an advanced slot.</p>
                            </div>
                            <span class="fm-panel-action">4 groups</span>
                        </div>
                        <div class="training-grid">
                            ${TRAINING_GROUPS.map(group => `
                                <div class="training-block">
                                    <div class="training-group-row">
                                        <span class="group-tag">${group}</span>
                                        <select class="group-skill-select" data-group="${group}" aria-label="Skill trained by ${group}">
                                            ${SKILLS_BY_GROUP[group].map(skill => `<option value="${skill}"${skill === state.groupSkills[group] ? ' selected' : ''}>${skillLabel(skill)}</option>`).join('')}
                                        </select>
                                    </div>
                                </div>`).join('')}
                        </div>
                    </section>

                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Advanced slots</h3>
                                <p class="training-note">Up to ${ADVANCED_SLOTS} players train on their own advanced assignment instead of the group skill.</p>
                            </div>
                            <span class="fm-panel-action">${state.advanced.length} of ${ADVANCED_SLOTS}</span>
                        </div>
                        <div class="quick-add-wrap">
                            <div class="training-group-row">
                                <span class="group-tag">Add</span>
                                <select id="quick-player-select" aria-label="Player to add to advanced training">
                                    <option value="">Select a player…</option>
                                    ${state.general.map(id => `<option value="${id}">${htmlEscape(playerName(id))}</option>`).join('')}
                                </select>
                            </div>
                            <div class="training-actions">
                                <select id="quick-role-select" class="adv-role-select" style="max-width:110px" aria-label="Role for the new advanced slot">
                                    ${ROLE_OPTIONS.map(role => `<option value="${role}"${role === 'MID' ? ' selected' : ''}>${role}</option>`).join('')}
                                </select>
                                <button type="button" class="fm-action-btn secondary" id="quick-add-advanced"${state.busy ? ' disabled' : ''}>Add to advanced</button>
                            </div>
                        </div>
                        <div class="training-pools">
                            <div class="training-dropzone">
                                ${state.advanced.length === 0
                                    ? '<div class="training-empty">No advanced slots filled.</div>'
                                    : state.advanced.map(renderAdvancedRow).join('')}
                            </div>
                        </div>
                    </section>

                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Squad</h3>
                                <p class="training-note">Everyone not in an advanced slot. They train on their group's skill.</p>
                            </div>
                            <span class="fm-panel-action">${state.general.length} players</span>
                        </div>
                        <div class="training-pools">
                            <div class="training-dropzone">
                                ${state.general.length === 0
                                    ? '<div class="training-empty">The whole squad is in advanced training.</div>'
                                    : state.general.map(id => `
                                        <div class="training-player-card">
                                            <div class="training-player-main">
                                                <strong>${htmlEscape(playerName(id))}</strong>
                                                <small>Group: ${htmlEscape(skillLabel(state.groupSkills[groupForPosition(playerById.get(id)?.position)]))}</small>
                                            </div>
                                            <span></span>
                                            <button type="button" class="mini-btn" data-add-adv="${id}">Make advanced</button>
                                        </div>`).join('')}
                            </div>
                        </div>
                    </section>

                    ${state.message ? `<section class="fm-panel"><div class="fm-empty" style="color:${state.messageIsError ? '#f44336' : '#6fcf97'}">${htmlEscape(state.message)}</div></section>` : ''}

                    <section class="fm-panel">
                        <div class="training-actions">
                            <button type="button" class="fm-action-btn secondary" id="save-training-setup"${state.busy ? ' disabled' : ''}>Save Setup</button>
                            <button type="button" class="fm-action-btn" id="run-training-week"${state.busy ? ' disabled' : ''}>Run Weekly Training</button>
                        </div>
                        <p class="training-note">Running the week saves the setup first, so what you see here is what gets trained. One run per week.</p>
                    </section>
                </div>`;

            // Every render replaces the markup, so the listeners went with the old nodes. Binding
            // them here rather than once at the end of load means a re-render cannot leave a screen
            // that looks alive and does nothing - which is what a click that changes nothing looks
            // like from the outside.
            bindUi();
        }

        function bindUi() {
            mainContent.querySelectorAll('.group-skill-select').forEach(sel => {
                sel.addEventListener('change', () => {
                    state.groupSkills[sel.getAttribute('data-group')] = sel.value;
                });
            });
            mainContent.querySelectorAll('[data-adv-role]').forEach(sel => {
                sel.addEventListener('change', () => {
                    const row = state.advanced.find(a => a.playerId === Number(sel.getAttribute('data-adv-role')));
                    if (row) row.role = sel.value;
                });
            });
            mainContent.querySelectorAll('[data-remove-adv]').forEach(btn => {
                btn.addEventListener('click', () => {
                    state.message = '';
                    moveToGeneral(Number(btn.getAttribute('data-remove-adv')));
                    render();
                });
            });
            mainContent.querySelectorAll('[data-add-adv]').forEach(btn => {
                btn.addEventListener('click', () => {
                    state.message = '';
                    const playerId = Number(btn.getAttribute('data-add-adv'));
                    // A player's own positional group is the sensible default advanced role.
                    moveToAdvanced(playerId, groupForPosition(playerById.get(playerId)?.position));
                    render();
                });
            });

            const quickAddBtn = mainContent.querySelector('#quick-add-advanced');
            if (quickAddBtn) {
                quickAddBtn.addEventListener('click', () => {
                    const playerId = Number(mainContent.querySelector('#quick-player-select')?.value || 0);
                    const role = (mainContent.querySelector('#quick-role-select')?.value || 'MID').toUpperCase();
                    if (!playerId) return;
                    state.message = '';
                    moveToAdvanced(playerId, ROLE_OPTIONS.includes(role) ? role : 'MID');
                    render();
                });
            }

            const saveBtn = mainContent.querySelector('#save-training-setup');
            if (saveBtn) {
                saveBtn.addEventListener('click', async () => {
                    state.busy = true;
                    state.message = '';
                    render();
                    let ok = false;
                    try {
                        ok = await saveSetup();
                    } catch (err) {
                        state.message = `Could not save the setup: ${err.message}`;
                        state.messageIsError = true;
                    }
                    state.busy = false;
                    if (ok) {
                        state.message = 'Setup saved.';
                        state.messageIsError = false;
                    }
                    render();
                });
            }

            const runBtn = mainContent.querySelector('#run-training-week');
            if (runBtn) {
                runBtn.addEventListener('click', async () => {
                    state.busy = true;
                    state.message = 'Saving the setup and running the week…';
                    state.messageIsError = false;
                    render();
                    try {
                        await saveSetup();
                        const res = await authFetch(`/training/weekly/team/${teamId}/run`, { method: 'POST' });
                        if (!res.ok) throw new Error(`Training run failed (${res.status})`);
                        const report = await res.json();
                        state.busy = false;
                        // The reports screen reads this and opens the week that was just trained.
                        if (Number.isFinite(report.seasonNumber) && Number.isFinite(report.weekNumber)) {
                            sessionStorage.setItem('training_report_focus', `${report.seasonNumber}|${report.weekNumber}`);
                        }
                        await loadTrainingReportsPage();
                        return;
                    } catch (err) {
                        state.busy = false;
                        // The backend refuses a second run in the same week, which is the guard that
                        // stopped the button being an unlimited skill-point exploit. Say so rather
                        // than reporting a failure.
                        if (err.code === 'TRAINING_ALREADY_RUN') {
                            state.message = 'This week has already been trained. Open Training Reports to see it.';
                            state.messageIsError = false;
                        } else {
                            state.message = `Could not run the week: ${err.message}`;
                            state.messageIsError = true;
                        }
                        render();
                    }
                });
            }
        }

        render();
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
            try {
                const res = await authFetch(`/training/weekly/team/${teamId}/reports/${season}/${week}`);
                return await res.json();
            } catch (err) {
                // No report for that week yet - an empty state, not a broken page.
                if (isMissing(err)) return null;
                throw err;
            }
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

    return { loadTrainingReports, loadTrainingReportsPage, loadTrainingSetup };
}
