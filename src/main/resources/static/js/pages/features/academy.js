export function createAcademyFeature(deps) {
    const {
        authFetch,
        getTeamId,
        escapeHtml,
        loadPlayer,
        goBackSmart,
    } = deps;

    async function loadJuniors() {
        const currentUserTeamId = getTeamId();
        const mainContent = document.getElementById("main-content");
        const response = await authFetch(`/juniors/team/${currentUserTeamId}`);
        if (!response.ok) {
            mainContent.innerHTML = `<div class="manager-card"><button class="back-to-dashboard" data-nav-back="dashboard">Back</button><h2>Youth Academy</h2><p>Could not load academy data.</p></div>`;
            return;
        }
        const academy = await response.json();

        const canDecide = academy.decisionsOpen === true;
        const currentSeason = Number(academy.currentSeasonNumber || 0);

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

        const renderRows = (list, withActions) => {
            if (!list.length) {
                return `<tr><td colspan="7" style="text-align:center; color:#9aa0a6;">No juniors in this group.</td></tr>`;
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
                        <td style="color:${delta >= 0 ? "#6fcf97" : "#ff6b6b"};">${deltaText}</td>
                        <td><span style="color:${statusColor(j.status)}; font-weight:700;">${escapeHtml(j.status)}</span></td>
                        <td>
                            ${decisionEligible ? actionButton("Promote", j.id, "promote-reveal") : ""}
                            ${decisionEligible ? actionButton("Transfer List", j.id, "transfer-list") : ""}
                            ${decisionEligible ? actionButton("Release", j.id, "release", true) : ""}
                            ${j.promotedPlayerId ? `<span class="cs-clickable" data-open-player="${j.promotedPlayerId}">Open Player</span>` : ""}
                        </td>
                    </tr>`;
            }).join("");
        };

        let html = `
        <div class="manager-card">
            <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
            <h2>Youth Academy</h2>
            <p class="training-note">Season ${academy.currentSeasonNumber} • Week ${academy.currentWeekNumber} • Junior Coach Skill: ${academy.juniorCoachSkill}/100</p>
            <p class="training-note">${canDecide ? "Carryover juniors are ready for decisions: Promote / Transfer list / Release." : "No carryover juniors pending decisions."}</p>
            <p class="training-note">Carryover juniors stay visible, do not train further, and keep actions until resolved. Academy active limit is 10.</p>

            <h3 style="margin:14px 0 8px;">Carryover Juniors (Decision Pending): ${carryover.length}</h3>
            <div class="training-report-table-wrap">
                <table class="training-report-table">
                    <thead>
                        <tr>
                            <th>Junior</th>
                            <th>Age</th>
                            <th>Talent</th>
                            <th>Academy Skill</th>
                            <th>Last Week Delta</th>
                            <th>Status</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>${renderRows(carryover, true)}</tbody>
                </table>
            </div>

            <h3 style="margin:14px 0 8px;">Current Intake (Season ${academy.currentSeasonNumber}): ${currentIntake.length}</h3>
            <div class="training-report-table-wrap">
                <table class="training-report-table">
                    <thead>
                        <tr>
                            <th>Junior</th>
                            <th>Age</th>
                            <th>Talent</th>
                            <th>Academy Skill</th>
                            <th>Last Week Delta</th>
                            <th>Status</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>${renderRows(currentIntake, false)}</tbody>
                </table>
            </div>

            ${otherVisible.length > 0 ? `
            <h3 style="margin:14px 0 8px;">Resolved Juniors (Not Archived Yet): ${otherVisible.length}</h3>
            <div class="training-report-table-wrap">
                <table class="training-report-table">
                    <thead>
                        <tr>
                            <th>Junior</th>
                            <th>Age</th>
                            <th>Talent</th>
                            <th>Academy Skill</th>
                            <th>Last Week Delta</th>
                            <th>Status</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>${renderRows(otherVisible, false)}</tbody>
                </table>
            </div>` : ""}

            <div style="margin-top:12px;">
                <button id="toggle-junior-archive" class="big-button" style="background:#3d4c63;">Show Archive</button>
            </div>
            <div id="junior-archive-wrap" style="display:none; margin-top:10px;">
                <h3 style="margin:8px 0;">Junior Archive</h3>
                <div class="training-report-table-wrap">
                    <table class="training-report-table">
                        <thead>
                            <tr>
                                <th>Junior</th>
                                <th>Age</th>
                                <th>Talent</th>
                                <th>Academy Skill</th>
                                <th>Status</th>
                                <th>Season In</th>
                                <th>Open</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${(Array.isArray(academy.archive) && academy.archive.length > 0)
                                ? academy.archive.map(j => `
                                    <tr>
                                        <td>${escapeHtml(j.name)}</td>
                                        <td>${j.age}</td>
                                        <td>${Number(j.talent).toFixed(1)}</td>
                                        <td>${Number(j.academySkillExact).toFixed(2)} <span style="opacity:0.8;">(int ${j.academySkill})</span></td>
                                        <td><span style="color:${statusColor(j.status)}; font-weight:700;">${escapeHtml(j.status)}</span></td>
                                        <td>S${j.arrivalSeasonNumber} W${j.arrivalWeekNumber}</td>
                                        <td>${j.promotedPlayerId ? `<span class="cs-clickable" data-open-player="${j.promotedPlayerId}">Open Player</span>` : "-"}</td>
                                    </tr>`).join("")
                                : `<tr><td colspan="7" style="text-align:center; color:#9aa0a6;">Archive is empty.</td></tr>`
                            }
                        </tbody>
                    </table>
                </div>
            </div>
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
