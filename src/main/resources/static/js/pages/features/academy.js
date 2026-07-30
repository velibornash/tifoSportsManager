export function createAcademyFeature(deps) {
    const {
        authFetch,
        getTeamId,
        escapeHtml,
        buildClubActionsHtml,
        loadPlayer,
        goBackSmart,
    } = deps;

    async function loadJuniors() {
        const currentUserTeamId = getTeamId();
        const mainContent = document.getElementById("main-content");
        const response = await authFetch(`/juniors/team/${currentUserTeamId}`);
        if (!response.ok) {
            mainContent.innerHTML = `<div class="fm-page fm-page--club"><section class="fm-panel fm-club-hero"><button class="back-to-dashboard" data-nav-back="dashboard">Back</button><div class="fm-club-hero-main"><div><div class="fm-eyebrow">Academy overview</div><h2>Youth Academy</h2><p class="fm-subtle">Could not load academy data.</p></div>${buildClubActionsHtml('juniors')}</div></section><section class="fm-panel"><div class="fm-empty">Could not load academy data.</div></section></div>`;
            return;
        }
        const academy = await response.json();

        const canDecide = academy.decisionsOpen === true;
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
                const decisionEligible = withActions && canDecide && j.status === "ACTIVE" && Number(j.arrivalSeasonNumber || 0) < currentSeason;
                return `
                    <tr>
                        <td>${escapeHtml(j.name)}</td>
                        <td>${j.age}</td>
                        <td>${Number(j.talent).toFixed(1)}</td>
                        <td>${Number(j.academySkillExact).toFixed(2)} <span style="opacity:0.8;">(int ${j.academySkill})</span></td>
                        <td class="academy-delta-cell" style="color:${delta >= 0 ? "#6fcf97" : "#ff6b6b"};">${deltaText}</td>
                        <td>${renderStatus(j.status)}</td>
                        <td>
                            <div class="academy-action-cell">
                                ${decisionEligible ? actionButton("Promote", j.id, "promote-reveal") : ""}
                                ${decisionEligible ? actionButton("Transfer List", j.id, "transfer-list") : ""}
                                ${decisionEligible ? actionButton("Release", j.id, "release", true) : ""}
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
                                <th>Talent</th>
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
                        <p class="fm-subtle academy-hero-copy">${canDecide ? "Carryover juniors are ready for Promote / Transfer List / Release decisions." : "No carryover juniors are waiting for a final decision right now."}</p>
                    </div>
                    ${buildClubActionsHtml('juniors')}
                </div>
                <div class="fm-medical-stat-grid academy-summary-grid">
                    <div><strong>${carryover.length}</strong><span>Carryover</span></div>
                    <div><strong>${currentIntake.length}</strong><span>Current intake</span></div>
                    <div><strong>${otherVisible.length}</strong><span>Resolved</span></div>
                    <div><strong>${archive.length}</strong><span>Archive</span></div>
                </div>
                <p class="fm-subtle academy-footnote">Carryover juniors stay visible, do not train further, and keep actions until resolved. Academy active limit is 10.</p>
            </section>

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
                                    <th>Talent</th>
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
                                            <td>${Number(j.talent).toFixed(1)}</td>
                                            <td>${Number(j.academySkillExact).toFixed(2)} <span class="ps-team">int ${j.academySkill}</span></td>
                                            <td>${renderStatus(j.status)}</td>
                                            <td>S${j.arrivalSeasonNumber} W${j.arrivalWeekNumber}</td>
                                            <td>${j.promotedPlayerId ? `<span class="sq-player-link" data-open-player="${j.promotedPlayerId}">Open Player</span>` : "-"}</td>
                                        </tr>`).join("")
                                    : `<tr><td colspan="7"><div class="fm-empty">Archive is empty.</div></td></tr>`
                                }
                            </tbody>
                        </table>
                    </div>
                </div>
            </section>
        </div>`;

        mainContent.innerHTML = html;

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
