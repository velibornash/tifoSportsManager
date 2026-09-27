export function createAcademyFeature(deps) {
    const {
        authFetch,
        getTeamId,
        escapeHtml,
        buildClubActionsHtml,
        loadPlayer,
        goBackSmart,
        formatPercent,
        // The junior school panel is priced in money, and a price is not a percentage.
        formatBudget,
    } = deps;

    /**
     * A junior's talent, as the manager is entitled to see it (Sprint 5.2).
     *
     * <p>Three shapes, and the difference between them is the feature: a band while he is a prospect,
     * the exact figure once he has been promoted, and a dash for a manager without PLUS. The server
     * decides which by omitting the fields — there is deliberately no raw `talent` on the payload, so
     * this function cannot accidentally print a ceiling the viewer was not sold.
     */
    function renderTalent(j) {
        if (j.talentExact != null) {
            return `${escapeHtml(formatPercent(j.talentExact, 2).replace("%", ""))}<span class="fm-subtle"> exact</span>`;
        }
        if (j.talentLow != null && j.talentHigh != null) {
            const low = escapeHtml(formatPercent(j.talentLow, 2).replace("%", ""));
            const high = escapeHtml(formatPercent(j.talentHigh, 2).replace("%", ""));
            return `${low} – ${high}`;
        }
        return '<span class="fm-subtle">PLUS</span>';
    }

    /**
     * The club's junior school, or null when the endpoint could not be reached.
     *
     * <p>Null is not an error state to shout about: the academy is still perfectly readable without
     * it, and a manager looking at his prospects should not be blocked by a budget panel failing.
     */
    async function loadSchoolState(teamId) {
        try {
            const res = await authFetch(`/juniors/school/team/${teamId}`);
            if (!res.ok) return null;
            return await res.json();
        } catch (e) {
            console.warn('Could not load the junior school state:', e);
            return null;
        }
    }

    /**
     * The junior school panel (Sprint 5.3a).
     *
     * <p>The week window is stated in words, not implied by a disabled button. A greyed control with
     * no reason is indistinguishable from a bug, and the window *is* the rule: the school can only be
     * opened in week 1 and only closed in week 12.
     */
    function renderSchoolPanel(s) {
        if (!s) {
            return `<section class="fm-panel academy-school-panel">
                <div class="fm-panel-head"><h3>Junior school</h3></div>
                <p class="fm-subtle">Could not load the junior school. Your prospects below are unaffected.</p>
            </section>`;
        }

        const fee = s.activationFee != null ? formatBudget(s.activationFee) : null;
        const upkeep = formatBudget(s.weeklyUpkeep);
        // A season's upkeep, so the running cost is not a surprise in week 6.
        const seasonCost = formatBudget(Number(s.weeklyUpkeep || 0) * 11);
        const week = Number(s.weekNumber || 0);

        const figures = s.active
            ? `<div class="fm-medical-stat-grid academy-school-grid">
                   <div><strong>${escapeHtml(upkeep)}</strong><span>Per week</span></div>
                   <div><strong>${escapeHtml(seasonCost)}</strong><span>Rest of season</span></div>
                   <div><strong>${Number(s.activeJuniors || 0)}/10</strong><span>Prospects</span></div>
                   <div><strong>${s.sinceSeason != null ? 'S' + escapeHtml(String(s.sinceSeason)) : '—'}</strong><span>Running since</span></div>
               </div>`
            : `<div class="fm-medical-stat-grid academy-school-grid">
                   <div><strong>${escapeHtml(fee || '—')}</strong><span>To open</span></div>
                   <div><strong>${escapeHtml(upkeep)}</strong><span>Per week after</span></div>
                   <div><strong>${escapeHtml(seasonCost)}</strong><span>Season total</span></div>
                   <div><strong>0/10</strong><span>Prospects</span></div>
               </div>`;

        // The button and the sentence explaining it are rendered together, so they can never disagree.
        // Closing is always styled destructive, disabled or not: whether an action is dangerous is a
        // property of the action, not of the week.
        let action;
        if (s.active) {
            action = s.canClose
                ? `<button type="button" class="fm-action-btn danger" data-school-action="close">Close the school</button>`
                : `<button type="button" class="fm-action-btn danger" disabled>Close the school</button>
                   <span class="fm-subtle academy-window-note">Only available in week 12. It is week ${week}.</span>`;
        } else {
            action = s.canOpen
                ? `<button type="button" class="fm-action-btn" data-school-action="open">Open the school</button>`
                : `<button type="button" class="fm-action-btn" disabled>Open the school</button>
                   <span class="fm-subtle academy-window-note">Only available in week 1. It is week ${week}.</span>`;
        }

        return `<section class="fm-panel academy-school-panel">
            <div class="fm-panel-head">
                <div>
                    <h3>Junior school</h3>
                    <p class="fm-subtle academy-panel-copy">${escapeHtml(s.note || '')}</p>
                </div>
                <span class="fm-panel-action ${s.active ? 'academy-school-on' : 'academy-school-off'}">${s.active ? 'Running' : 'Not running'}</span>
            </div>
            ${figures}
            <div class="fm-club-actions">${action}</div>
            <p class="fm-subtle" data-school-note></p>
        </section>`;
    }

    /**
     * What the youth setup is worth, and what it is made of (Sprint 5.3).
     *
     * <p>A manager pays a weekly upkeep for a youth facility and a youth coach, so this says what
     * those two are actually doing. Both were written in earlier sprints and read by nothing — the
     * facility is the S4.4 item that said "consumed in Sprint 5", and the coach attribute is the half
     * of S4.3 that deferred here.
     *
     * <p>The two are shown separately because <b>one cannot rescue the other</b>: a superb coach in a
     * Portakabin is not a good academy, and a superb ground with nobody teaching is not either.
     */
    function renderQuality(a) {
        const quality = Number(a.academyQuality != null ? a.academyQuality : 1);
        const label = a.academyQualityLabel || 'Average';
        const pct = Math.round((quality - 1) * 100);
        const sign = pct > 0 ? '+' : '';
        const facility = a.youthFacilityLevel != null ? a.youthFacilityLevel : '—';
        const coach = a.youthCoachDevelopment != null ? a.youthCoachDevelopment : '—';
        const boost = pct === 0
            ? 'develops prospects at the standard rate'
            : `develops prospects ${Math.abs(pct)}% ${pct > 0 ? 'faster' : 'slower'} than the standard rate`;
        return `<strong>Academy quality ${escapeHtml(label)}</strong> (${sign}${pct}%) — ${escapeHtml(boost)}. Youth facility ${escapeHtml(String(facility))}/20, youth coach development ${escapeHtml(String(coach))}/20.`;
    }

    async function loadJuniors() {
        const currentUserTeamId = getTeamId();
        const mainContent = document.getElementById("main-content");
        const response = await authFetch(`/juniors/team/${currentUserTeamId}`);
        if (!response.ok) {
            mainContent.innerHTML = `<div class="fm-page fm-page--club"><section class="fm-panel fm-club-hero"><button class="back-to-dashboard" data-nav-back="dashboard">Back</button><div class="fm-club-hero-main"><div><div class="fm-eyebrow">Academy overview</div><h2>Youth Academy</h2><p class="fm-subtle">Could not load academy data.</p></div>${buildClubActionsHtml('juniors')}</div></section><section class="fm-panel"><div class="fm-empty">Could not load academy data.</div></section></div>`;
            return;
        }
        const academy = await response.json();

        // The school is a separate endpoint on purpose: the academy is a report on players and the
        // school is a budget decision, so one of them failing must not blank the other. A null school
        // renders as an unavailable panel rather than taking the page with it.
        const school = await loadSchoolState(currentUserTeamId);

        const canDecide = academy.decisionsOpen === true;
        // Junior decisions are a registration window (Sprint 5.3, owner 2026-09-27): weeks 1-2 only.
        // A prospect cannot be signed into the first team mid-season, and a manager who could would
        // sign one in week nine because he had a good month. The window is stated in the copy below
        // rather than left as a set of missing buttons.
        const week = Number(academy.currentWeekNumber || 0);
        const inDecisionWindow = week >= 1 && week <= 2;
        const windowNote = `Decisions open in weeks 1-2 only. It is week ${week}.`;
        const currentSeason = Number(academy.currentSeasonNumber || 0);
        const archive = Array.isArray(academy.archive) ? academy.archive : [];

        const statusColor = (status) => {
            if (status === "ACTIVE") return "#6fcf97";
            if (status === "PROMOTED") return "#4ea1ff";
            if (status === "TRANSFER_LISTED") return "#f5b041";
            if (status === "RELEASED") return "#ff6b6b";
            return "#b7bec9";
        };

        const actionButton = (label, juniorId, action, danger = false) =>
            `<button class="mini-btn junior-action-btn" data-junior-id="${juniorId}" data-action="${action}" style="margin-right:6px;${danger ? "background:#8a2d2d;" : ""}">${label}</button>`;

        const juniors = Array.isArray(academy.juniors) ? academy.juniors : [];
        const carryover = juniors.filter(j => j.status === "ACTIVE" && Number(j.arrivalSeasonNumber || 0) < currentSeason);
        const currentIntake = juniors.filter(j => Number(j.arrivalSeasonNumber || 0) >= currentSeason);
        const otherVisible = juniors.filter(j =>
            Number(j.arrivalSeasonNumber || 0) < currentSeason && !(j.status === "ACTIVE" && Number(j.arrivalSeasonNumber || 0) < currentSeason)
        );

        const renderStatus = (status) => `
            <span class="academy-status-pill" style="--academy-status:${statusColor(status)};">${escapeHtml(status || 'UNKNOWN')}</span>`;

        const renderRows = (list, withActions) => {
            if (!list.length) {
                return `<tr><td colspan="7"><div class="fm-empty">No juniors in this group.</div></td></tr>`;
            }
            return list.map(j => {
                const delta = Number(j.lastWeeklyDelta || 0);
                const deltaText = `${delta >= 0 ? "+ " : "- "}${Math.abs(delta).toFixed(2)}`;
                const decisionEligible = withActions && canDecide && inDecisionWindow
                    && j.status === "ACTIVE" && Number(j.arrivalSeasonNumber || 0) < currentSeason;
                // A junior inside the window but too new to decide on, and one outside the window, are
                // different situations and must not read the same.
                const pendingNew = withActions && j.status === "ACTIVE"
                    && Number(j.arrivalSeasonNumber || 0) >= currentSeason;
                return `
                    <tr>
                        <td>${escapeHtml(j.name)}</td>
                        <td>${j.age}</td>
                        <td>${escapeHtml(j.position || '—')}</td>
                        <td>${renderTalent(j)}</td>
                        <td>${Number(j.academySkillExact).toFixed(2)} <span style="opacity:0.8;">(int ${j.academySkill})</span></td>
                        <td class="academy-delta-cell" style="color:${delta >= 0 ? "#6fcf97" : "#ff6b6b"};">${deltaText}</td>
                        <td>${renderStatus(j.status)}</td>
                        <td>
                            <div class="academy-action-cell">
                                ${decisionEligible ? actionButton("Promote", j.id, "promote-reveal") : ""}
                                ${decisionEligible ? actionButton("Transfer List", j.id, "transfer-list") : ""}
                                ${decisionEligible ? actionButton("Release", j.id, "release", true) : ""}
                                ${!decisionEligible && pendingNew ? `<span class="fm-subtle">Too new</span>` : ""}
                                ${!decisionEligible && !pendingNew && j.status === "ACTIVE" ? `<span class="fm-subtle">Window closed</span>` : ""}
                                ${j.promotedPlayerId ? `<span class="sq-player-link" data-open-player="${j.promotedPlayerId}">Open Player</span>` : ""}
                            </div>
                        </td>
                    </tr>`;
            }).join("");
        };

        const renderSection = (title, count, list, withActions, description = '') => `
            <section class="fm-panel academy-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>${title}</h3>
                        ${description ? `<p class="fm-subtle academy-panel-copy">${description}</p>` : ''}
                    </div>
                    <span class="fm-panel-action">${count} players</span>
                </div>
                <div class="fm-squad-wrap">
                    <table class="fm-squad academy-squad">
                        <thead>
                            <tr>
                                <th class="sq-name">Junior</th>
                                <th>Age</th>
                                <th>Pos</th>
                                <th>Talent (est.)</th>
                                <th>Academy</th>
                                <th>Δ Week</th>
                                <th>Status</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>${renderRows(list, withActions)}</tbody>
                    </table>
                </div>
            </section>`;

        let html = `
        <div class="fm-page fm-page--club fm-page--academy">
            <section class="fm-panel fm-club-hero academy-hero">
                <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">Academy overview</div>
                        <h2>Youth Academy</h2>
                        <p class="fm-subtle">Season ${academy.currentSeasonNumber} · Week ${academy.currentWeekNumber} · Junior Coach Skill ${academy.juniorCoachSkill}/100</p>
                        <p class="fm-subtle academy-hero-copy">${inDecisionWindow
                            ? (canDecide
                                ? "Carryover juniors are ready for Promote / Transfer List / Release decisions."
                                : "No carryover juniors are waiting for a final decision right now.")
                            : windowNote + " A prospect keeps developing until then, and still graduates at 20 whether you are ready or not."}</p>
                    </div>
                    ${buildClubActionsHtml('juniors')}
                </div>
                <div class="fm-medical-stat-grid academy-summary-grid">
                    <div><strong>${carryover.length}</strong><span>Carryover</span></div>
                    <div><strong>${currentIntake.length}</strong><span>Current intake</span></div>
                    <div><strong>${otherVisible.length}</strong><span>Resolved</span></div>
                    <div><strong>${archive.length}</strong><span>Archive</span></div>
                </div>
                <p class="fm-subtle academy-quality-line">${renderQuality(academy)}</p>
                <p class="fm-subtle academy-footnote">Carryover juniors stay visible, do not train further, and keep actions until resolved. Academy active limit is 10.</p>
            </section>

            ${renderSchoolPanel(school)}

            ${renderSection('Carryover juniors', carryover.length, carryover, true, 'Decision pending players remain visible until you resolve them.')}
            ${renderSection(`Current intake · Season ${academy.currentSeasonNumber}`, currentIntake.length, currentIntake, false, 'New intake continues developing through the current season.')}
            ${otherVisible.length > 0 ? renderSection('Resolved juniors', otherVisible.length, otherVisible, false, 'Resolved players stay visible here until they move into the archive.') : ''}

            <section class="fm-panel academy-panel academy-archive-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Junior archive</h3>
                        <p class="fm-subtle academy-panel-copy">Past academy outcomes stay here for quick review.</p>
                    </div>
                    <button id="toggle-junior-archive" class="fm-action-btn secondary" type="button">Show Archive</button>
                </div>
                <div id="junior-archive-wrap" class="academy-archive-wrap" style="display:none;">
                    <div class="fm-squad-wrap">
                        <table class="fm-squad academy-squad academy-squad--archive">
                            <thead>
                                <tr>
                                    <th class="sq-name">Junior</th>
                                    <th>Age</th>
                                    <th>Pos</th>
                                    <th>Talent (est.)</th>
                                    <th>Academy</th>
                                    <th>Status</th>
                                    <th>Season In</th>
                                    <th>Open</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${archive.length > 0
                                    ? archive.map(j => `
                                        <tr>
                                            <td class="sq-name">${escapeHtml(j.name)}</td>
                                            <td>${j.age}</td>
                                            <td>${escapeHtml(j.position || '—')}</td>
                                            <td>${renderTalent(j)}</td>
                                            <td>${Number(j.academySkillExact).toFixed(2)} <span class="ps-team">int ${j.academySkill}</span></td>
                                            <td>${renderStatus(j.status)}</td>
                                            <td>S${j.arrivalSeasonNumber} W${j.arrivalWeekNumber}</td>
                                            <td>${j.promotedPlayerId ? `<span class="sq-player-link" data-open-player="${j.promotedPlayerId}">Open Player</span>` : "-"}</td>
                                        </tr>`).join("")
                                    : `<tr><td colspan="8"><div class="fm-empty">Archive is empty.</div></td></tr>`
                                }
                            </tbody>
                        </table>
                    </div>
                </div>
            </section>
        </div>`;

        mainContent.innerHTML = html;

        mainContent.querySelectorAll("[data-school-action]").forEach(btn => {
            btn.addEventListener("click", async () => {
                const action = btn.getAttribute("data-school-action");
                const note = mainContent.querySelector("[data-school-note]");
                const teamId = getTeamId();

                // Closing graduates and lists the entire intake. It cannot be undone, so it is never
                // one click away from a mis-tap, and the confirmation names what will happen rather
                // than asking a bare "are you sure".
                if (action === 'close') {
                    const school = await loadSchoolState(teamId);
                    const count = school ? Number(school.activeJuniors || 0) : 0;
                    const warning = count === 1
                        ? 'Close the junior school?\n\n1 prospect will graduate and be listed for transfer. This cannot be undone.'
                        : `Close the junior school?\n\n${count} prospects will graduate and be listed for transfer. This cannot be undone.`;
                    if (!window.confirm(warning)) return;
                }

                btn.disabled = true;
                if (note) note.textContent = 'Working…';

                const res = await authFetch(`/juniors/school/team/${teamId}/${action}`, { method: 'POST' });
                if (!res.ok) {
                    // The API explains itself — "A junior school can only be closed in week 12; it is
                    // week 1" — and that sentence is the whole point of the window. Swallowing it into
                    // "Action failed" would throw away the only useful thing on the wire.
                    let msg = 'Could not change the junior school.';
                    try {
                        const payload = await res.json();
                        if (payload && payload.message) msg = payload.message;
                        else if (payload) msg = JSON.stringify(payload);
                    } catch (e) {
                        try { msg = await res.text(); } catch (e2) {}
                    }
                    if (note) note.textContent = msg;
                    btn.disabled = false;
                    return;
                }

                // Anything that changes money or prospects re-reads the world rather than patching it.
                await loadJuniors();
            });
        });

        mainContent.querySelectorAll(".junior-action-btn").forEach(btn => {
            btn.addEventListener("click", async () => {
                const juniorId = Number(btn.getAttribute("data-junior-id"));
                const action = btn.getAttribute("data-action");
                if (!juniorId || !action) return;
                btn.disabled = true;
                const res = await authFetch(`/juniors/${juniorId}/${action}`, { method: "POST" });
                if (!res.ok) {
                    let msg = "Action failed";
                    try { msg = await res.text(); } catch (e) {}
                    alert(msg);
                    btn.disabled = false;
                    return;
                }
                if (action === "promote-reveal") {
                    const payload = await res.json();
                    if (payload && payload.playerId) {
                        sessionStorage.setItem("junior_promotion_reveal", JSON.stringify(payload));
                        await loadPlayer(Number(payload.playerId), "juniors");
                        return;
                    }
                }
                await loadJuniors();
            });
        });

        mainContent.querySelectorAll("[data-open-player]").forEach(link => {
            link.addEventListener("click", async () => {
                const playerId = Number(link.getAttribute("data-open-player"));
                if (playerId) await loadPlayer(playerId, "juniors");
            });
        });

        const archiveBtn = document.getElementById("toggle-junior-archive");
        const archiveWrap = document.getElementById("junior-archive-wrap");
        if (archiveBtn && archiveWrap) {
            archiveBtn.addEventListener("click", () => {
                const isHidden = archiveWrap.style.display === "none";
                archiveWrap.style.display = isHidden ? "block" : "none";
                archiveBtn.textContent = isHidden ? "Hide Archive" : "Show Archive";
            });
        }

        const backBtn = mainContent.querySelector('[data-nav-back]');
        if (backBtn) {
            backBtn.addEventListener('click', () => goBackSmart('dashboard'));
        }
    }

    return { loadJuniors };
}
