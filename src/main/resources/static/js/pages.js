// pages.js
import { authFetch } from './auth.js';
import { renderPlayersView, renderMatchesView, renderTableView, renderFixturesView, renderLeagueMatchesView } from './pages-renderers.js';
    let currentUserTeamId = null;
    let currentPageId = 'dashboard';
    let currentNavState = { type: 'dashboard' };
    const navHistoryStack = [];
    let navReplayMode = false;

    function pushNavState(nextState) {
        if (navReplayMode) return;
        if (currentNavState) navHistoryStack.push(currentNavState);
        currentNavState = nextState;
    }
    async function renderNavState(state) {
        if (!state) return;
        if (state.type === 'dashboard') {
            currentPageId = 'dashboard';
            currentNavState = { type: 'dashboard' };
            if (typeof window.loadDashboard === 'function') window.loadDashboard();
            return;
        }
        if (state.type === 'page') {
            await loadPage(state.page, { pushHistory: false });
            return;
        }
        if (state.type === 'player') {
            await loadPlayer(state.playerId, state.callerPage, { pushHistory: false });
            return;
        }
        if (state.type === 'match') {
            await loadMatch(state.matchId, state.caller, { pushHistory: false });
            return;
        }
        if (state.type === 'fixture') {
            await loadFixture(state.fixtureId, { pushHistory: false });
            return;
        }
        if (state.type === 'leagueTeam') {
            await loadLeagueTeam(state.teamId, state.teamName, { pushHistory: false });
            return;
        }
        if (state.type === 'leagueTeamPlayer') {
            await loadLeagueTeamPlayer(state.playerId, state.teamId, state.teamName, { pushHistory: false });
        }
    }
    async function goBackSmart(fallback = 'dashboard') {
        if (navHistoryStack.length > 0) {
            const previous = navHistoryStack.pop();
            currentNavState = previous;
            navReplayMode = true;
            try {
                await renderNavState(previous);
            } finally {
                navReplayMode = false;
            }
            return;
        }
        if (fallback === 'dashboard') {
            currentPageId = 'dashboard';
            currentNavState = { type: 'dashboard' };
            if (typeof window.loadDashboard === 'function') window.loadDashboard();
            return;
        }
        await loadPage(fallback, { pushHistory: false });
    }

    async function loadUserTeamId() {
        try {
            const res = await authFetch('/auth/me');
            const user = await res.json();
            currentUserTeamId = user.teamId;
            console.log("Team ID loaded:", currentUserTeamId);
            return currentUserTeamId;
        } catch (err) {
            console.error("Error /auth/me:", err);
            localStorage.removeItem('token');
            window.location.href = '/login.html';
            return null;
        }
    }
    async function ensureUserTeamId() {
        if (currentUserTeamId) return currentUserTeamId;
        return await loadUserTeamId();
    }

    // Event delegation za back-button (radi i posle svakog innerHTML overwrite-a)
   document.addEventListener('click', function(e) {
           if (e.target.id === 'back-button' || e.target.closest('#back-button')) {
               const button = e.target.closest('#back-button');
               const target = button.dataset.target || 'results';
               console.log(`Back clicked -> loading: ${target}`);
               goBackSmart(target);
           }
       });

    function buildEmptyState(message) {
        return `<div class="manager-card" style="text-align:center; padding:40px;">
                    <h2>${message}</h2>
                </div>`;
    }
    async function loadPage(page, options = {}) {
        const pushHistory = options.pushHistory !== false;
        const mainContent = document.getElementById("main-content");
        currentPageId = page;
        if (pushHistory) pushNavState({ type: 'page', page });
    if (!currentUserTeamId) {
            await loadUserTeamId();
            if (!currentUserTeamId) return;
        }
        try {

            switch(page) {

                // TEAM
                case "firstTeam":
                    await loadFirstTeam();
                    break;

                case "juniors":
                    await loadJuniors();
                    break;
                case "medicalCenter":
                    await loadMedicalCenter();
                    break;

                case "formations":
                    await loadFormations();
                    break;

                case "coaches":
                    await loadCoaches();
                    break;

                case "training":
                case "trainingSetup":
                    await loadTrainingReports();
                    break;
                case "trainingReports":
                    await loadTrainingReportsPage();
                    break;

                case "profile":
                    await loadClubProfile();
                    break;

                // MATCHES
                case "upcoming":
                    await loadUpcomingMatches();
                    break;

                case "results":
                    await loadResults();
                    break;

                case "fixtures":
                    await loadFixtures();
                    break;

                // COMPETITIONS
                case "leagueTable":
                    await loadLeagueTable();
                    break;

                case "leagueSchedule":
                    await loadLeagueSchedule();
                    break;

                case "leagueMatches":
                    await loadLeagueMatches();
                    break;

                case "cup":
                    await loadCup();
                    break;

                case "international":
                    await loadInternational();
                    break;

                case "friendlies":
                    await loadFriendlies();
                    break;

                // COMMUNITY
                case "forum":
                    await loadForum();
                    break;

                case "chat":
                    await loadChat();
                    break;

                case "events":
                    await loadEvents();
                    break;

                // STATS
                case "playerStats":
                    await loadPlayerStats();
                    break;

                case "teamStats":
                    await loadTeamStats();
                    break;

                case "topScorers":
                    await loadTopScorersAndAssists();
                    break;

                case "analytics":
                    await loadAnalytics();
                    break;

                default:
                    mainContent.innerHTML = buildEmptyState("Page not found");
            }

        } catch (err) {
            console.error(err);
            mainContent.innerHTML = buildEmptyState("API Error");
        }
    }
    function parseMatchDate(dateArr) {
        if(Array.isArray(dateArr)) {
            const [year, month, day, hour, minute, second, nano] = dateArr;
            const ms = nano ? Math.floor(nano / 1000000) : 0; // pretvori nanosekunde u milisekunde
            return new Date(year, month - 1, day, hour, minute, second, ms);
        }
        return new Date(dateArr); // fallback za stringove ili timestamps
    }
    function getImageFilename(name) {
        return name
            .normalize("NFD")                    // razdvaja dijakritike
            .replace(/[\u0300-\u036f]/g, "")     // uklanja dijakritike (ć→c, č→c...)
            .replace(/đ/g, "dj")
            .replace(/Đ/g, "Dj")
            .replace(/\s+/g, '_')                // razmak → _
            .replace(/[^a-zA-Z0-9_-]/g, '');     // uklanja sve što nije slovo/broj/_/-
    }
    function normalizeTeamKey(name) {
        return (name || "")
            .toLowerCase()
            .normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "")
            .replace(/[^a-z0-9]/g, "");
    }
    async function openTeamByName(teamName) {
        try {
            const res = await authFetch('/countries/leagues/1/teams');
            if (!res.ok) return;
            const teams = await res.json();
            const key = normalizeTeamKey(teamName);
            const found = teams.find(t => normalizeTeamKey(t.name) === key);
            if (found) {
                loadLeagueTeam(found.id, found.name);
            }
        } catch (e) {
            console.warn('Team navigation failed:', e);
        }
    }
    function escapeHtml(value) {
        return String(value ?? "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/\"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }
    function formatGoalDiff(value) {
        const number = Number(value || 0);
        return `${number > 0 ? "+" : ""}${number}`;
    }
    function getRatingColor(rating) {
        const value = Number(rating);
        if (!Number.isFinite(value)) return "#9aa0a6";
        if (value >= 7.5) return "#4caf50";
        if (value >= 6.5) return "#ffd700";
        if (value >= 5.5) return "#ff9800";
        return "#f44336";
    }
    function formatFormBadge(formValue) {
        const value = Number(formValue);
        if (!Number.isFinite(value)) return `<span class="form-badge neutral">-</span>`;
        if (value >= 7.8) return `<span class="form-badge hot">&#128293; ${value.toFixed(1)}</span>`;
        if (value <= 5.8) return `<span class="form-badge cold">&#129482; ${value.toFixed(1)}</span>`;
        return `<span class="form-badge neutral">${value.toFixed(1)}</span>`;
    }
    function formatRatingBadge(ratingValue) {
        const value = Number(ratingValue);
        if (!Number.isFinite(value)) return `<span style="color:#9aa0a6;">-</span>`;
        return `<span style="color:${getRatingColor(value)}; font-weight:700;">${value.toFixed(1)}</span>`;
    }
    function getPendingJuniorReveal(playerId) {
        try {
            const raw = sessionStorage.getItem("junior_promotion_reveal");
            if (!raw) return null;
            const payload = JSON.parse(raw);
            if (!payload || Number(payload.playerId) !== Number(playerId)) return null;
            return payload;
        } catch (e) {
            return null;
        }
    }
    function delay(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }
    async function runJuniorRevealAnimation(payload) {
        if (!payload) return;
        const allocated = payload.allocatedSkills || {};
        const sequence = Array.isArray(payload.allocationSequence) ? payload.allocationSequence : [];
        const remainingEl = document.getElementById("junior-reveal-remaining");
        const statusEl = document.getElementById("junior-reveal-status");
        const idByKey = {
            stamina: "skill-stamina-val",
            goalkeeper: "skill-goalkeeper-val",
            defending: "skill-defending-val",
            pace: "skill-pace-val",
            technique: "skill-technique-val",
            playmaker: "skill-playmaker-val",
            passing: "skill-passing-val",
            shooting: "skill-shooting-val"
        };
        const current = {
            stamina: 0,
            goalkeeper: 0,
            defending: 0,
            pace: 0,
            technique: 0,
            playmaker: 0,
            passing: 0,
            shooting: 0
        };
        let remaining = Number(payload.totalSkillBudget || 0);
        if (remainingEl) remainingEl.textContent = String(remaining);
        if (statusEl) statusEl.textContent = "Allocating 1 point every second...";

        if (sequence.length > 0) {
            for (const skillKey of sequence) {
                await delay(1000);
                if (Object.prototype.hasOwnProperty.call(current, skillKey)) {
                    current[skillKey] += 1;
                    const node = document.getElementById(idByKey[skillKey]);
                    if (node) {
                        node.textContent = `${current[skillKey].toFixed(2)}`;
                        node.style.color = "#6fcf97";
                    }
                }
                remaining = Math.max(0, remaining - 1);
                if (remainingEl) remainingEl.textContent = String(remaining);
            }
        } else {
            const order = ["goalkeeper", "defending", "pace", "technique", "playmaker", "passing", "shooting", "stamina"];
            for (const key of order) {
                await delay(1000);
                current[key] = Number(allocated[key] || 0);
                const node = document.getElementById(idByKey[key]);
                if (node) {
                    node.textContent = `${current[key].toFixed(2)}`;
                    node.style.color = "#6fcf97";
                }
            }
            if (remainingEl) remainingEl.textContent = String(Math.max(0, Number(payload.remainingAfterFill || 0)));
        }

        if (statusEl) statusEl.textContent = "Promotion reveal completed.";
        sessionStorage.removeItem("junior_promotion_reveal");
    }
    async function fetchPlayerRatingSummary(playerId) {
        try {
            const response = await authFetch(`/match-stats/player/${playerId}`);
            if (!response.ok) return { averageRating10: null, averageRating100: null, matchesPlayed: 0 };
            const payload = await response.json();
            return {
                averageRating10: payload.averageRating10 ?? null,
                averageRating100: payload.averageRating100 ?? null,
                matchesPlayed: payload.matchesPlayed ?? 0
            };
        } catch (err) {
            return { averageRating10: null, averageRating100: null, matchesPlayed: 0 };
        }
    }
    async function loadPlayer(playerId, callerPage = currentPageId, options = {}) {
        const pushHistory = options.pushHistory !== false;
        if (pushHistory) pushNavState({ type: 'player', playerId, callerPage });
        const mainContent = document.getElementById("main-content");
        console.log(`Loading player for team ${currentUserTeamId} and player ${playerId}`);
        const [response, ratingSummary] = await Promise.all([
            authFetch(`/teams/${currentUserTeamId}/players/${playerId}`),
            fetchPlayerRatingSummary(playerId)
        ]);
        console.log(`Response status: ${response.status}`);
        if (!response.ok) {
            const backTarget = callerPage || "firstTeam";
            mainContent.innerHTML = `<div class="team-card"><p>Player not found.</p><button onclick="loadPage('${backTarget}')">Back</button></div>`;
            return;
        }

        const player = await response.json();
        const revealPayload = getPendingJuniorReveal(playerId);
        const revealActive = !!revealPayload;
        const fmtSkill = (exact, visible) => {
            if (exact != null && Number.isFinite(Number(exact))) return Number(exact).toFixed(2);
            if (visible != null && Number.isFinite(Number(visible))) return Number(visible).toFixed(2);
            return "-";
        };
        const backMap = {
            juniors: "Back to Youth Academy",
            trainingReports: "Back to Training Reports",
            trainingSetup: "Back to Training Setup",
            firstTeam: "Back to Team",
            leagueTable: "Back to League Table",
            leagueMatches: "Back to League Results",
            results: "Back to Results",
            fixtures: "Back to Fixtures"
        };
        const backLabel = backMap[callerPage] || "Back";
        const backTarget = callerPage || "firstTeam";
        mainContent.innerHTML = `
            <div class="manager-card">
                <button id="player-back-button" class="big-button" style="margin-bottom:16px;">${backLabel}</button>
                <h2>${escapeHtml(player.name)}</h2>
                ${revealActive ? `
                <div class="manager-card" style="margin:10px 0 16px; background:rgba(17,26,39,0.85); border:1px solid rgba(111,207,151,0.45);">
                    <h3 style="margin:0 0 8px;">Junior Promotion Reveal</h3>
                    <p class="training-note" style="margin:0 0 6px;">Skills are being generated from academy potential.</p>
                    <p class="training-note" style="margin:0;">Remaining skill budget: <strong id="junior-reveal-remaining">${Number(revealPayload.totalSkillBudget || 0)}</strong></p>
                    <p class="training-note" id="junior-reveal-status" style="margin:6px 0 0;">Allocating 1 point every second...</p>
                </div>` : ""}
                <div class="cs-stat-grid">
                    <div class="cs-stat-card"><div class="icon">📋</div><div class="val">${escapeHtml(player.position)}</div><div class="lbl">Position</div></div>
                    <div class="cs-stat-card"><div class="icon">🎂</div><div class="val">${player.age}</div><div class="lbl">Age</div></div>
                    <div class="cs-stat-card"><div class="icon">⭐</div><div class="val">${player.overall ?? "-"}</div><div class="lbl">OVR</div></div>
                    <div class="cs-stat-card"><div class="icon">😮‍💨</div><div class="val">${player.fatigue != null ? Number(player.fatigue).toFixed(1) : "-"}</div><div class="lbl">Fatigue</div></div>
                    <div class="cs-stat-card"><div class="icon">📈</div><div class="val">${formatRatingBadge(ratingSummary.averageRating10)}</div><div class="lbl">Average Grade (1-10)</div></div>
                    <div class="cs-stat-card"><div class="icon">&#128293;</div><div class="val">${formatFormBadge(player.form)}</div><div class="lbl">Form</div></div>
                    <div class="cs-stat-card"><div class="icon">⚽</div><div class="val">${player.totalGoals ?? 0}</div><div class="lbl">Goals</div></div>
                    <div class="cs-stat-card"><div class="icon">🅰️</div><div class="val">${player.totalAssists ?? 0}</div><div class="lbl">Assists</div></div>
                    <div class="cs-stat-card"><div class="icon">💰</div><div class="val">${player.value != null ? Math.round(player.value).toLocaleString() : "-"}</div><div class="lbl">Value</div></div>
                </div>

                <h3 style="margin-top:20px;">Skills</h3>
                <div class="cs-stat-grid">
                    <div class="cs-stat-card"><div class="icon">🔋</div><div class="val" id="skill-stamina-val">${revealActive ? "0.00" : fmtSkill(player.staminaExact, player.stamina)}</div><div class="lbl">Stamina</div></div>
                    <div class="cs-stat-card"><div class="icon">💨</div><div class="val" id="skill-pace-val">${revealActive ? "0.00" : fmtSkill(player.paceExact, player.pace)}</div><div class="lbl">Pace</div></div>
                    <div class="cs-stat-card"><div class="icon">🛡️</div><div class="val" id="skill-defending-val">${revealActive ? "0.00" : fmtSkill(player.defendingExact, player.defending)}</div><div class="lbl">Defending</div></div>
                    <div class="cs-stat-card"><div class="icon">🎯</div><div class="val" id="skill-technique-val">${revealActive ? "0.00" : fmtSkill(player.techniqueExact, player.technique)}</div><div class="lbl">Technique</div></div>
                    <div class="cs-stat-card"><div class="icon">🧠</div><div class="val" id="skill-playmaker-val">${revealActive ? "0.00" : fmtSkill(player.playmakerExact, player.playmaker)}</div><div class="lbl">Playmaker</div></div>
                    <div class="cs-stat-card"><div class="icon">🎁</div><div class="val" id="skill-passing-val">${revealActive ? "0.00" : fmtSkill(player.passingExact, player.passing)}</div><div class="lbl">Passing</div></div>
                    <div class="cs-stat-card"><div class="icon">🚀</div><div class="val" id="skill-shooting-val">${revealActive ? "0.00" : fmtSkill(player.shootingExact, player.shooting)}</div><div class="lbl">Shooting</div></div>
                    <div class="cs-stat-card"><div class="icon">🧤</div><div class="val" id="skill-goalkeeper-val">${revealActive ? "0.00" : fmtSkill(player.goalkeeperExact, player.goalkeeper)}</div><div class="lbl">Goalkeeper</div></div>
                </div>
            </div>`;
        if (revealActive) {
            await runJuniorRevealAnimation(revealPayload);
        }
        const playerBackBtn = document.getElementById("player-back-button");
        if (playerBackBtn) {
            playerBackBtn.addEventListener("click", () => goBackSmart(backTarget));
        }
    }
    async function loadMatch(matchId, caller, options = {}) {
        const pushHistory = options.pushHistory !== false;
        if (pushHistory) pushNavState({ type: 'match', matchId, caller });
        const mainContent = document.getElementById("main-content");
        console.log(`Loading match ID: ${matchId}, caller: ${caller}`);
        if(caller==="undefined"){
           console.log(`Match not found.`);
           mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p></div>`;
           return;
        }
        try {
            const response = await authFetch(`/matches/${matchId}/detail`);
            console.log(`Status: ${response.status}`);

            if (!response.ok) {
                const text = await response.text();
                console.error(`Error ${response.status}: ${text}`);
                mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p></div>`;
                return;
            }

            const [events, lineupsPayload] = await Promise.all([
                response.json(),
                authFetch(`/match-stats/lineups/${matchId}`)
                    .then(r => r.ok ? r.json() : null)
                    .catch(() => null)
            ]);
            console.log("MATCH EVENTS:", events);
            console.log("Event count:", events.length);

            if (events.length === 0) {
                mainContent.innerHTML = `<div class="team-card"><p>No data available for this match.</p></div>`;
                return;
            }

            // Osnovni podaci meča
            const first = events[0];
            const homeTeamName = first.homeTeam || "Home";
            const awayTeamName = first.awayTeam || "Away";
            const homeGoals = first.homeGoals ?? 0;
            const awayGoals = first.awayGoals ?? 0;
            const homeTeamId = lineupsPayload?.homeTeamId || null;
            const awayTeamId = lineupsPayload?.awayTeamId || null;

            const matchDate = parseMatchDate(first.matchDate);
            const formattedDate = matchDate.toLocaleString('en-US', {
                weekday: 'short', year: 'numeric', month: 'short', day: 'numeric',
                hour: '2-digit', minute: '2-digit'
            });

            // HTML struktura – sa JEDNIM back dugmetom
            mainContent.innerHTML = `
            <div class="team-card">
                <h2 style="text-align:center;">Match Details</h2>

                <div style="display:flex; justify-content:space-around; font-size:1.3em; margin:20px 0; font-weight:bold;">
                    <div style="text-align:center;">
                        <div>${homeTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${homeTeamId}, '${escapeHtml(homeTeamName)}')">${homeTeamName}</span>` : homeTeamName}</div>
                        <div>${homeGoals}</div>
                    </div>
                    <div style="align-self:center; font-size:1.6em;">–</div>
                    <div style="text-align:center;">
                        <div>${awayTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${awayTeamId}, '${escapeHtml(awayTeamName)}')">${awayTeamName}</span>` : awayTeamName}</div>
                        <div>${awayGoals}</div>
                    </div>
                </div>

                <div style="text-align:center; color:#aaa; margin-bottom:25px;">
                    🗓 ${formattedDate}
                </div>

                <div id="match-buttons-container" style="display:flex; justify-content:center; gap:12px; margin-bottom:25px; flex-wrap:wrap;">
                    <button id="view-lineups" style="padding:8px 16px; font-weight:bold;">Lineups</button>
                    <button id="view-stats" style="padding:8px 16px; font-weight:bold;">Stats</button>
                    <button id="view-goals" style="padding:8px 16px; font-weight:bold;">Goals</button>
                    <button id="view-events" style="padding:8px 16px; font-weight:bold;">All Events</button>
                </div>

                <div id="match-info" style="margin-top:15px; min-height:200px;"></div>

                <!-- Jedno zajedničko Back dugme -->
                <div style="text-align:center; margin-top:30px;">
                    <button id="back-button" style="padding:10px 24px; font-size:1.1em;">
                        Back
                    </button>
                </div>
            </div>`;

            // Postavi ponašanje Back dugmeta u zavisnosti od caller-a
            const backButton = document.getElementById('back-button');
            let backTarget = 'results';

            if (caller === 'match') {
                backTarget = 'results';
                backButton.textContent = 'Back to Results';
            } else if (caller === 'leagueMatches') {
                backTarget = 'leagueMatches';
                backButton.textContent = 'Back to League Matches';
            } else {
                console.warn(`Unknown caller: ${caller} → fallback na 'results'`);
            }

            backButton.dataset.target = backTarget;
            //backButton.textContent = backText;
            backButton.style.display = 'inline-block';

             const infoDiv = document.getElementById("match-info");

            // Funkcija za prikaz statistike
            function showStats() {
                const homeShotsOn  = events.filter(e => e.eventType === "ShotOnTargetEvent" && e.shotOnTargetTeam === homeTeamName).length;
                const awayShotsOn  = events.filter(e => e.eventType === "ShotOnTargetEvent" && e.shotOnTargetTeam === awayTeamName).length;
                const homeShotsOff = events.filter(e => e.eventType === "ShotOffTargetEvent" && e.shotOffTargetTeam === homeTeamName).length;
                const awayShotsOff = events.filter(e => e.eventType === "ShotOffTargetEvent" && e.shotOffTargetTeam === awayTeamName).length;
                const homeGoalsCount = events.filter(e => e.eventType === "GoalEvent" && e.scoreTeam === homeTeamName && e.goalScored !== false).length;
                const awayGoalsCount = events.filter(e => e.eventType === "GoalEvent" && e.scoreTeam === awayTeamName && e.goalScored !== false).length;

                const adjHomeShotsOn = Math.max(homeShotsOn, homeGoalsCount);
                const adjAwayShotsOn = Math.max(awayShotsOn, awayGoalsCount);
                const homeTotalShots = Math.max(adjHomeShotsOn + homeShotsOff, homeGoalsCount);
                const awayTotalShots = Math.max(adjAwayShotsOn + awayShotsOff, awayGoalsCount);

                const homeCorners = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === homeTeamName).length;
                const awayCorners = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === awayTeamName).length;

                const homeYellows = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === homeTeamName).length;
                const awayYellows = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === awayTeamName).length;

                const homeReds = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === homeTeamName).length;
                const awayReds = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === awayTeamName).length;

                const homePenalties = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === homeTeamName).length;
                const awayPenalties = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === awayTeamName).length;

                const countTeamEvents = (type, teamName) =>
                    events.filter(e => e.eventType === type && e.eventTeam === teamName).length;

                // Possession proxy: weighted attacking/control events to avoid constant 50-50 when ChanceEvent is sparse.
                const homePossWeight =
                    (countTeamEvents("ChanceEvent", homeTeamName) * 3.0) +
                    (homeShotsOn * 2.0) +
                    (homeShotsOff * 1.4) +
                    (homeCorners * 1.2) +
                    (countTeamEvents("FreeKickEvent", homeTeamName) * 0.9) +
                    (homePenalties * 1.3) +
                    (countTeamEvents("GoalEvent", homeTeamName) * 1.1);

                const awayPossWeight =
                    (countTeamEvents("ChanceEvent", awayTeamName) * 3.0) +
                    (awayShotsOn * 2.0) +
                    (awayShotsOff * 1.4) +
                    (awayCorners * 1.2) +
                    (countTeamEvents("FreeKickEvent", awayTeamName) * 0.9) +
                    (awayPenalties * 1.3) +
                    (countTeamEvents("GoalEvent", awayTeamName) * 1.1);

                const baselineWeight = 18.0;
                const totalPoss = (homePossWeight + baselineWeight) + (awayPossWeight + baselineWeight);
                let homePossPct = totalPoss > 0
                    ? Math.round(((homePossWeight + baselineWeight) / totalPoss) * 100)
                    : 50;
                homePossPct = Math.max(32, Math.min(68, homePossPct));
                const awayPossPct = 100 - homePossPct;

                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Match Stats</h3>`;

                html += `
                <table style="width:100%; border-collapse:collapse; font-size:0.95em;">
                    <thead>
                        <tr style="background:rgba(76,175,80,0.15);">
                            <th style="padding:12px; text-align:left;">Stats</th>
                            <th style="padding:12px; text-align:center;">${homeTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${homeTeamId}, '${escapeHtml(homeTeamName)}')">${homeTeamName}</span>` : homeTeamName}</th>
                            <th style="padding:12px; text-align:center;">${awayTeamId ? `<span class="cs-clickable" onclick="loadLeagueTeam(${awayTeamId}, '${escapeHtml(awayTeamName)}')">${awayTeamName}</span>` : awayTeamName}</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr><td style="padding:10px;">Possession</td><td style="text-align:center;font-weight:bold;">${homePossPct}%</td><td style="text-align:center;font-weight:bold;">${awayPossPct}%</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Shots</td><td style="text-align:center;">${homeTotalShots}</td><td style="text-align:center;">${awayTotalShots}</td></tr>
                        <tr><td style="padding:10px;">Shots on target</td><td style="text-align:center;">${adjHomeShotsOn}</td><td style="text-align:center;">${adjAwayShotsOn}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Shots off target</td><td style="text-align:center;">${homeShotsOff}</td><td style="text-align:center;">${awayShotsOff}</td></tr>
                        <tr><td style="padding:10px;">Corners</td><td style="text-align:center;">${homeCorners}</td><td style="text-align:center;">${awayCorners}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Yellow cards</td><td style="text-align:center;color:#ff9800;">${homeYellows}</td><td style="text-align:center;color:#ff9800;">${awayYellows}</td></tr>
                        <tr><td style="padding:10px;">Red cards</td><td style="text-align:center;color:#f44336;">${homeReds}</td><td style="text-align:center;color:#f44336;">${awayReds}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Penalties</td><td style="text-align:center;">${homePenalties}</td><td style="text-align:center;">${awayPenalties}</td></tr>
                    </tbody>
                </table>`;

                infoDiv.innerHTML = html;
            }

            // Automatski prikaži statistiku odmah
            showStats();

            // Listener-i za ostala dugmad
            document.getElementById("view-lineups").addEventListener("click", () => {
                if (!lineupsPayload || (!lineupsPayload.homeLineup && !lineupsPayload.awayLineup)) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Lineups are not available for this match.</p>`;
                    return;
                }

                const renderLineup = (teamName, players) => {
                    const sorted = [...(players || [])].sort((a, b) => {
                        const posOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
                        return (posOrder[a.position] ?? 9) - (posOrder[b.position] ?? 9);
                    });
                    if (sorted.length === 0) return `<p style="color:#aaa;">No lineup data.</p>`;

                    let html = `
                        <h4 style="margin: 16px 0 8px; color:#ddd;">${teamName}</h4>
                        <div style="display:flex; gap:10px; padding:4px 10px; color:#888; font-size:0.82em;">
                            <div style="width:42px; text-align:center;">POS</div>
                            <div style="flex:1;">Name</div>
                            <div style="width:56px; text-align:center;">Grade</div>
                            <div style="width:42px; text-align:center;">G</div>
                            <div style="width:42px; text-align:center;">A</div>
                        </div>`;
                    sorted.forEach((p, i) => {
                        const rowBg = i % 2 === 0 ? "rgba(255,255,255,0.03)" : "transparent";
                        const gradeValue = Number(p.grade);
                        const gradeText = Number.isFinite(gradeValue) ? gradeValue.toFixed(1) : "-";
                        const gradeColor = getRatingColor(gradeValue);
                        html += `
                            <div style="display:flex; gap:10px; padding:8px 10px; border-radius:6px; background:${rowBg};">
                                <div style="width:42px; text-align:center; color:#2a8c4a; font-weight:700;">${p.position}</div>
                                <div style="flex:1;">${p.playerId ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${teamName === (lineupsPayload.homeTeam || homeTeamName) ? (lineupsPayload.homeTeamId || 0) : (lineupsPayload.awayTeamId || 0)}, ${p.playerId}, '${escapeHtml(teamName)}')">${p.playerName}</span>` : p.playerName}</div>
                                <div style="width:56px; text-align:center; font-weight:700; color:${gradeColor};">${gradeText}</div>
                                <div style="width:42px; text-align:center;">${p.goals ?? 0}</div>
                                <div style="width:42px; text-align:center;">${p.assists ?? 0}</div>
                            </div>`;
                    });
                    return html;
                };

                infoDiv.innerHTML = `
                    <h3 style="text-align:center; margin:0 0 16px; color:#4CAF50;">Lineups & Grades</h3>
                    ${renderLineup(lineupsPayload.homeTeam || homeTeamName, lineupsPayload.homeLineup || [])}
                    ${renderLineup(lineupsPayload.awayTeam || awayTeamName, lineupsPayload.awayLineup || [])}
                `;
            });
            document.getElementById("view-stats").addEventListener("click", showStats);

            document.getElementById("view-goals").addEventListener("click", () => {
                const goals = events.filter(e => e.eventType === "GoalEvent");
                if (goals.length === 0) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">No goals in this match.</p>`;
                    return;
                }

                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Goals</h3><ul style="list-style:none; padding:0;">`;

                goals.forEach(g => {
                    const disallowed = g.goalScored === false;
                    const lineColor = disallowed ? "#ffb3b3" : "inherit";
                    const verdict = disallowed ? ` <span style="color:#ff6b6b; font-weight:600;">DISALLOWED (VAR)</span>` : "";
                    const scorerTeamId = g.scoreTeam === homeTeamName ? homeTeamId : (g.scoreTeam === awayTeamName ? awayTeamId : null);
                    const scorerStat = (g.scoreTeam === homeTeamName ? (lineupsPayload?.homeLineup || []) : (lineupsPayload?.awayLineup || []))
                        .find(p => p.playerName === g.scorer);
                    const assistStat = (g.scoreTeam === homeTeamName ? (lineupsPayload?.homeLineup || []) : (lineupsPayload?.awayLineup || []))
                        .find(p => p.playerName === g.assistant);
                    const scorerLabel = scorerStat?.playerId && scorerTeamId
                        ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${scorerTeamId}, ${scorerStat.playerId}, '${escapeHtml(g.scoreTeam || '')}')">${g.scorer || "?"}</span>`
                        : (g.scorer || "?");
                    const assistLabel = assistStat?.playerId && scorerTeamId
                        ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${scorerTeamId}, ${assistStat.playerId}, '${escapeHtml(g.scoreTeam || '')}')">${g.assistant}</span>`
                        : (g.assistant || "");
                    const assistHtml = g.assistant ? ` <span style="color:#888;">(assist: ${assistLabel})</span>` : '';
                    html += `
                    <li style="padding:12px; margin:8px 0; background:rgba(255,255,255,0.05); border-radius:8px;">
                        <strong>${g.matchMinute}'</strong> <span style="color:${lineColor};">⚽ ${scorerLabel} ${assistHtml}${verdict}</span>
                        <span style="float:right; color:#aaa;">${g.scoreAfterGoal || ""}</span>
                    </li>`;
                });

                html += `</ul>`;
                infoDiv.innerHTML = html;
            });

            document.getElementById("view-events").addEventListener("click", () => {
                // ... tvoj postojeći kod za prikaz svih eventa (možeš ga ostaviti isti ili malo očistiti) ...
                // Primer minimalne verzije:
                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">All Events</h3>`;
                html += `<ul style="list-style:none; padding:0;">`;

                events.sort((a,b) => (a.matchMinute||0) - (b.matchMinute||0)).forEach(e => {
                    html += `<li style="padding:10px; border-bottom:1px solid #333;">
                        ${e.matchMinute || "?"}' <strong>[${e.eventType}]</strong> ${e.description || ""}
                    </li>`;
                });

                html += `</ul>`;
                infoDiv.innerHTML = html;
            });

        } catch (err) {
            console.error("Error loading match:", err);
            mainContent.innerHTML = `<div class="team-card"><p>Error loading match: ${err.message}</p></div>`;
        }
    }
    async function loadFirstTeam() {
            console.log(`Loading first team for ${currentUserTeamId}`);
            const response = await authFetch(`/teams/${currentUserTeamId}/players`);
            console.log(`Response status: ${response.status}`);
            const players = await response.json();
            renderPlayers(players, "First Team");
        }
    async function loadResults() {
        console.log(`Loading results for ${currentUserTeamId}`);
        const response = await authFetch(`/teams/${currentUserTeamId}/matches`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json()
        const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
        renderMatches(results, "Results");
    }
    async function loadJuniors() {
        const mainContent = document.getElementById("main-content");
        const response = await authFetch(`/juniors/team/${currentUserTeamId}`);
        if (!response.ok) {
            mainContent.innerHTML = `<div class="manager-card"><button class="back-to-dashboard" onclick="goBackSmart('dashboard')">Back to Dashboard</button><h2>Youth Academy</h2><p>Could not load academy data.</p></div>`;
            return;
        }
        const academy = await response.json();

        const canDecide = academy.decisionsOpen === true;
        const statusColor = (status) => {
            if (status === "ACTIVE") return "#6fcf97";
            if (status === "PROMOTED") return "#4ea1ff";
            if (status === "TRANSFER_LISTED") return "#f5b041";
            if (status === "RELEASED") return "#ff6b6b";
            return "#b7bec9";
        };
        const actionButton = (label, juniorId, action, danger = false) =>
            `<button class="mini-btn junior-action-btn" data-junior-id="${juniorId}" data-action="${action}" style="margin-right:6px;${danger ? "background:#8a2d2d;" : ""}">${label}</button>`;

        let html = `
        <div class="manager-card">
            <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">Back to Dashboard</button>
            <h2>Youth Academy</h2>
            <p class="training-note">Season ${academy.currentSeasonNumber} • Week ${academy.currentWeekNumber} • Junior Coach Skill: ${academy.juniorCoachSkill}/100</p>
            <p class="training-note">${canDecide ? "Decisions are open this week: Promote / Transfer list / Release." : "Decisions open only in week 1 of a new season for previous-season juniors."}</p>
            <p class="training-note">Juniors from previous seasons who are still active stay here but no longer gain skill progression.</p>
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
                    <tbody>`;

        const juniors = Array.isArray(academy.juniors) ? academy.juniors : [];
        if (juniors.length === 0) {
            html += `<tr><td colspan="7" style="text-align:center; color:#9aa0a6;">No juniors in academy yet.</td></tr>`;
        } else {
            juniors.forEach(j => {
                const delta = Number(j.lastWeeklyDelta || 0);
                const deltaText = `${delta >= 0 ? "+ " : "- "}${Math.abs(delta).toFixed(2)}`;
                const decisionEligible = canDecide && j.status === "ACTIVE" && Number(j.arrivalSeasonNumber || 0) < Number(academy.currentSeasonNumber || 0);
                html += `
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
            });
        }

        html += `
                    </tbody>
                </table>
            </div>
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
    }
    async function loadMedicalCenter() {
        const mainContent = document.getElementById("main-content");
        mainContent.innerHTML = `
            <div class="manager-card" style="text-align:center; min-height:320px; display:flex; flex-direction:column; align-items:center; justify-content:center;">
                <button class="back-to-dashboard" onclick="goBackSmart('dashboard')" style="align-self:flex-start;">Back to Dashboard</button>
                <div style="font-size:4rem; line-height:1;">⛑️</div>
                <div style="font-size:2.7rem; line-height:1; margin:8px 0;">🩺</div>
                <h2 style="margin:12px 0 6px;">Medical Center</h2>
                <p style="color:#9aa0a6; max-width:520px;">
                    Injury diagnosis, recovery plans, and medical staff management are coming soon.
                </p>
                <div style="margin-top:10px; color:#ff6b6b; font-weight:700;">Coming Soon</div>
            </div>`;
    }
    async function loadFormations() {
        console.log(`Loading formations for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/teams/${currentUserTeamId}/formations`);
        console.log(`Response status: ${response.status}`);
        const formations = await response.json();

        const mainContent = document.getElementById("main-content");
        const availableFormations = Array.isArray(formations) && formations.length
            ? formations.map(f => f.name)
            : ['4-4-2', '4-3-3', '4-2-3-1', '3-5-2', '5-3-2', '4-5-1'];
        const availableStyles = ['BALANCED', 'ATTACKING', 'DEFENSIVE', 'COUNTER'];

        const savedFormation = localStorage.getItem('main_app_tactics_formation') || availableFormations[0] || '4-4-2';
        const savedStyle = localStorage.getItem('main_app_tactics_style') || 'BALANCED';

        const render = (formation, style) => {
            let html = `<div class="manager-card">
                <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">Back to Dashboard</button>
                <h2>Tactics</h2>
                <h3>Formation: <span id="currentFormation">${escapeHtml(formation)}</span></h3>
                <div class="cs-tactics-grid">`;

            availableFormations.forEach(f => {
                const active = f === formation ? 'active' : '';
                html += `<div class="cs-tactics-btn ${active}" data-formation="${escapeHtml(f)}">${escapeHtml(f)}</div>`;
            });
            html += `</div>
                <h3 style="margin-top:20px;">Style: <span id="currentStyle">${escapeHtml(style)}</span></h3>
                <div class="cs-tactics-grid">`;
            availableStyles.forEach(s => {
                const active = s === style ? 'active' : '';
                html += `<div class="cs-tactics-btn ${active}" data-style="${escapeHtml(s)}">${escapeHtml(s)}</div>`;
            });
            html += `</div>
                <p style="margin-top:14px; color:#9aa0a6;">Tactics are saved locally for demo UI mode.</p>
            </div>`;
            mainContent.innerHTML = html;

            mainContent.querySelectorAll('.cs-tactics-btn[data-formation]').forEach(btn => {
                btn.addEventListener('click', () => {
                    const newFormation = btn.getAttribute('data-formation');
                    localStorage.setItem('main_app_tactics_formation', newFormation);
                    render(newFormation, localStorage.getItem('main_app_tactics_style') || style);
                });
            });
            mainContent.querySelectorAll('.cs-tactics-btn[data-style]').forEach(btn => {
                btn.addEventListener('click', () => {
                    const newStyle = btn.getAttribute('data-style');
                    localStorage.setItem('main_app_tactics_style', newStyle);
                    render(localStorage.getItem('main_app_tactics_formation') || formation, newStyle);
                });
            });
        };

        render(savedFormation, savedStyle);
    }
    async function loadCoaches() {
        console.log(`Loading coaches for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/teams/${currentUserTeamId}/coaches`);
        console.log(`Response status: ${response.status}`);
        const coaches = await response.json();

        const mainContent = document.getElementById("main-content");

        let html = `<div class="manager-card">
                <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">⬅ Back to Dashboard</button>
            <h2>Coaches</h2>
            <div class="manager-grid">`;

        coaches.forEach(c => {
            html += `
                <div class="manager-player-card">
                    <div class="player-name">${c.name}</div>
                    <div class="player-meta">${c.role}</div>
                    <div class="player-rating">Rating ${c.rating}</div>
                </div>`;
        });

        html += `</div></div>`;
        mainContent.innerHTML = html;
    }
    async function loadTrainingReports() {
        const mainContent = document.getElementById("main-content");
        const playersRes = await authFetch(`/teams/${currentUserTeamId}/players`);
        if (!playersRes.ok) {
            mainContent.innerHTML = `<div class="manager-card"><button class="back-to-dashboard" onclick="goBackSmart('dashboard')">Back to Dashboard</button><h2>Training Setup</h2><p>Could not load players.</p></div>`;
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

        const setupRes = await authFetch(`/training/setup/team/${currentUserTeamId}`);
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
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/reports`);
            if (!res.ok) return [];
            return await res.json();
        }

        async function saveSetup() {
            const payload = {
                teamId: currentUserTeamId,
                groupSkills: state.groupSkills,
                advancedAssignments: state.advanced.map(a => ({ playerId: a.playerId, role: a.role }))
            };
            const res = await authFetch(`/training/setup/team/${currentUserTeamId}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            return res.ok;
        }

        async function runTrainingWeek() {
            await saveSetup();
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/run`, { method: "POST" });
            if (!res.ok) return null;
            return await res.json();
        }

        async function openWeekReport(season, week) {
            state.loadingReport = true;
            await render();
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/reports/${season}/${week}`);
            state.loadingReport = false;
            if (!res.ok) return;
            state.selectedReport = await res.json();
            state.selectedPlayerGraph = null;
            await render();
        }

        async function openPlayerGraph(playerId) {
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/player/${playerId}/graph`);
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
                    <td><span class="cs-clickable" data-training-player-graph="${p.playerId}">${escapeHtml(p.playerName)}</span></td>
                    <td>${escapeHtml(p.role)}</td>
                    <td>${escapeHtml(skillLabel(p.directTrainingSkill || "-"))}</td>
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
                return `<div class="training-block"><h3>${escapeHtml(graph.player?.name || "Player")} - Training Graph</h3><p class="training-empty">No graph data.</p></div>`;
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

            let html = `<div class="training-block" style="margin-top:14px;"><h3>${escapeHtml(graph.player?.name || "Player")} - Training Graph</h3>`;
            html += `<div class="training-report-table-wrap"><table class="training-report-table"><thead><tr><th>Skill</th>`;
            weekKeys.forEach(k => {
                const [s, w] = k.split("-");
                html += `<th>S${s}W${w}</th>`;
            });
            html += `</tr></thead><tbody>`;

            Object.keys(bySkill).forEach(skill => {
                let prevInt = null;
                html += `<tr><td>${escapeHtml(skillLabel(skill))}</td>`;
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
            <div class="manager-card training-setup-card">
                <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">Back to Dashboard</button>
                <h2>Training Setup</h2>
                <p class="training-note">Advanced + Formation training. Wingers are under MID group. Stamina is automatic.</p>

                <div class="training-grid">
                    <div class="training-block">
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
                                        <strong>${escapeHtml(p.name)}</strong>
                                        <small>${escapeHtml(playerBadge(p))}</small>
                                    </div>
                                    <select class="adv-role-select" data-player-id="${p.id}">
                                        ${ROLE_OPTIONS.map(role => `<option value="${role}" ${entry.role === role ? "selected" : ""}>${role}</option>`).join("")}
                                    </select>
                                    <button class="mini-btn" data-remove-adv="${idx}">Remove</button>
                                </div>`;
                            }).join("")}
                        </div>
                    </div>
                </div>

                <div class="training-block" style="margin-top:14px;">
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
                                            <strong>${escapeHtml(p.name)}</strong>
                                            <small>${escapeHtml(playerBadge(p))}</small>
                                        </div>
                                    </div>`;
                                }).join("")}
                            </div>
                        </div>
                    </div>
                </div>

                <div class="training-actions">
                    <button id="save-training-setup" class="big-button">Save Setup</button>
                    <button id="run-training-week" class="big-button" style="margin-left:10px; background:#145d39;">Run Weekly Training</button>
                </div>

                <div class="training-note" style="margin-top:12px;">
                    Weekly report history is available in <strong>Training > Training Reports</strong>.
                </div>
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
        const playersRes = await authFetch(`/teams/${currentUserTeamId}/players`);
        if (!playersRes.ok) {
            mainContent.innerHTML = `<div class="manager-card"><button class="back-to-dashboard" onclick="goBackSmart('dashboard')">Back to Dashboard</button><h2>Training Reports</h2><p>Could not load players.</p></div>`;
            return;
        }
        const players = await playersRes.json();
        const playerById = new Map(players.map(p => [p.id, p]));

        let selectedReport = null;

        const colorByIntDelta = (delta) => {
            if (delta > 0) return "#4caf50";
            if (delta < 0) return "#f44336";
            return "#b7bec9";
        };
        const skillLabel = (skill) => skill.charAt(0).toUpperCase() + skill.slice(1);
        const skillIcon = (skill) => {
            switch ((skill || "").toLowerCase()) {
                case "goalkeeper": return "🧤";
                case "defending": return "🛡️";
                case "pace": return "💨";
                case "technique": return "🎯";
                case "playmaker": return "🧠";
                case "passing": return "🎁";
                case "shooting": return "🚀";
                case "stamina": return "🔋";
                default: return "•";
            }
        };
        const normalizeWeekKey = (season, week) => `${Number(season)}|${Number(week)}`;

        async function fetchSummaries() {
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/reports`);
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
            const res = await authFetch(`/training/weekly/team/${currentUserTeamId}/reports/${season}/${week}`);
            if (!res.ok) return null;
            return await res.json();
        }

        function renderReportCards(report) {
            if (!report) return `<p class="training-empty">Select a week report.</p>`;
            let html = `<h3>Season ${report.seasonNumber} • Week ${report.weekNumber}</h3><div class="manager-grid">`;
            (report.players || [])
                .sort((a, b) => String(a.playerName || "").localeCompare(String(b.playerName || "")))
                .forEach(p => {
                const player = playerById.get(p.playerId);
                const playerName = player?.name || p.playerName || `#${p.playerId}`;
                const filename = getImageFilename(playerName);
                const skillsText = (p.skills || []).map(s => {
                    const intDelta = Number(s.integerChange || 0);
                    const decDelta = Number(s.decimalChange ?? (Number(s.after || 0) - Number(s.before || 0)));
                    const decDeltaText = `${decDelta >= 0 ? "+ " : "- "}${Math.abs(decDelta).toFixed(2)}`;
                    const intDeltaText = `${intDelta >= 0 ? "+ " : "- "}${Math.abs(intDelta)}`;
                    return `<div style="font-size:0.9em; color:${colorByIntDelta(intDelta)}; font-weight:700;">
                                ${skillIcon(s.skill)} ${skillLabel(s.skill)}: ${Number(s.after).toFixed(2)}
                                <span style="opacity:0.9;">(Delta ${decDeltaText} | int ${intDeltaText})</span>
                            </div>`;
                }).join("");
                html += `
                    <div class="manager-player-card training-report-player-card">
                        <img src="/images/${filename}.jpg" onerror="this.src='/images/player.jpg'">
                        <div class="player-name"><span class="cs-clickable" data-open-training-player="${p.playerId}">${escapeHtml(playerName)}</span></div>
                        <div class="player-meta">Age: ${Number.isFinite(Number(player?.age)) ? Number(player.age) : "?"}</div>
                        <div class="player-meta">${escapeHtml(p.role || "-")} • DT: ${escapeHtml(skillLabel(p.directTrainingSkill || "-"))}</div>
                        <div class="player-meta">${p.advancedTraining ? "Advanced training" : "Formation training"}</div>
                        <div style="margin-top:8px; text-align:left; width:100%;">${skillsText}</div>
                    </div>`;
            });
            html += `</div>`;
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

            mainContent.innerHTML = `
                <div class="manager-card training-setup-card">
                    <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">Back to Dashboard</button>
                    <h2>Training Reports</h2>
                    <p class="training-note">Click week to open players report. Decimal values are shown for testing; color tracks integer up/down.</p>
                    <div class="training-grid">
                        <div class="training-block">
                            <h3>Weeks</h3>
                            <div class="training-week-list">
                                ${summaries.length === 0 ? `<div class="training-empty">No reports yet.</div>` : summaries.map(s => `<div class="training-week-item cs-clickable" data-open-week="${s.seasonNumber}|${s.weekNumber}">Season ${s.seasonNumber} • Week ${s.weekNumber}</div>`).join("")}
                            </div>
                        </div>
                        <div class="training-block">
                            ${renderReportCards(selectedReport)}
                        </div>
                    </div>
                </div>
            `;

            mainContent.querySelectorAll("[data-open-week]").forEach(item => {
                item.addEventListener("click", async () => {
                    const [season, week] = (item.getAttribute("data-open-week") || "").split("|").map(Number);
                    const report = await fetchReport(season, week);
                    if (report) {
                        selectedReport = report;
                        await render();
                    }
                });
            });
            mainContent.querySelectorAll("[data-open-training-player]").forEach(item => {
                item.addEventListener("click", async (event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    const playerId = Number(item.getAttribute("data-open-training-player"));
                    if (playerId) {
                        await loadPlayer(playerId, "trainingReports");
                    }
                });
            });
        }

        await render();
    }
    async function loadClubProfile() {
        console.log(`Loading club profile for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/teams/${currentUserTeamId}/profile`);
        console.log(`Response status: ${response.status}`);
        const profile = await response.json();

        const mainContent = document.getElementById("main-content");

        // Dodajemo sliku stadiona (pretpostavljamo da je u /images/stadion.jpg ili slično)
        // Možeš promeniti putanju ili dodati logiku po imenu stadiona ako imaš više
        const stadiumImage = "/images/dunjareal.png"; // default, ili po profil.stadium

        mainContent.innerHTML = `
        <div class="club-profile-card">
            <!-- Glavni header sa logom i imenom -->
            <div class="club-header">
                <div class="club-logo-container">
                    <img src="${profile.logo || '/images/omladinac.png'}"
                         class="club-logo"
                         alt="${profile.name}"
                         onerror="this.src='/images/omladinac.png'">
                </div>
                <div class="club-title">
                    <h1>${profile.name}</h1>
                    <p class="club-subtitle">Serbian Super League • Season 2025/26</p>
                </div>
            </div>

            <!-- Statistike u lepim karticama -->
            <div class="club-stats-grid">
                <div class="stat-card">
                    <div class="stat-icon">📅</div>
                    <div class="stat-value">${profile.founded || "N/A"}</div>
                    <div class="stat-label">Founded</div>
                </div>
            <button class="stadium-button" onclick="showStadiumModal('${stadiumImage}', '${profile.stadium || 'Stadion'}')">
                    <div class="stadium-overlay">
                        <div class="stat-icon">🏟️</div>
                        <div class="stat-value">${profile.stadium || "N/A"}</div>
                        <div class="stat-label">Stadium (click to view)</div>
                    </div>
            </button>
                <div class="stat-card">
                    <div class="stat-icon">💰</div>
                    <div class="stat-value">€${(profile.budget || 0).toLocaleString()}</div>
                    <div class="stat-label">Budget</div>
                </div>
                <div class="stat-card">
                    <div class="stat-icon">⭐</div>
                    <div class="stat-value">${profile.reputation || "N/A"}</div>
                    <div class="stat-label">Reputation</div>
                </div>
            </div>

            <!-- Back dugme -->
            <button class="back-to-dashboard-profile" onclick="goBackSmart('dashboard')">
                ← Back to Dashboard
            </button>
        </div>`;
    }
    async function loadUpcomingMatches() {
        if (!await ensureUserTeamId()) return;
        console.log(`Loading upcoming matches for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/matches/teams/${currentUserTeamId}/upcoming`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "Upcoming Matches");
    }
    async function loadFixtures() {
        if (!await ensureUserTeamId()) return;
        console.log(`Loading fixtures for: ${currentUserTeamId}`);
        const response = await authFetch(`/demo/matches/teams/${currentUserTeamId}/fixtures`);
        console.log(`Response status: ${response.status}`);
        const fixtures = await response.json();
        renderFixtures(fixtures, "Fixtures");
    }
    async function loadFixture(fixtureId, options = {}) {
        const pushHistory = options.pushHistory !== false;
        if (pushHistory) pushNavState({ type: 'fixture', fixtureId });
        const mainContent = document.getElementById("main-content");
        if (!await ensureUserTeamId()) return;
        console.log(`Loading fixture ID: ${fixtureId}`);

        try {
            const response = await authFetch(`/demo/matches/teams/${currentUserTeamId}/fixtures/${fixtureId}`);
            console.log(`Response status: ${response.status}`);
        // Mapiranje ID → slika stadiona (možeš proširiti)
            let stadiumImage = "/images/default-stadium.png"; // fallback
            if (fixtureId == 1) {
                stadiumImage = "/images/livadice.png";
            } else if (fixtureId == 2) {
                stadiumImage = "/images/dunjareal.png";
            } else if (fixtureId == 3) {
                stadiumImage = "/images/bilinopolje.png";
            }
            if (!response.ok) {
                const text = await response.text();
                console.error(`Error ${response.status}: ${text}`);
                mainContent.innerHTML = `<div class="team-card"><p>Fixture not found.</p><button class="back-to-dashboard" onclick="goBackSmart('fixtures')">⬅ Back to Fixtures</button></div>`;
                return;
            }

            const fixture = await response.json();

            const homeTeamName = fixture.homeTeam || "Home";
            const awayTeamName = fixture.awayTeam || "Away";
            const matchDateTime = `${fixture.matchDate || "N/A"} • ${fixture.matchTime || "N/A"}`;
            const venue = fixture.stadiumName || "N/A";

            mainContent.innerHTML = `
            <div class="team-card">
                <h2 style="text-align:center;">Upcoming Fixture</h2>

            <div style="text-align:center; margin:20px 0;">
                    <img src="${stadiumImage}"
                         alt="${venue}"
                         style="max-width: 280px; height: auto; border-radius: 10px; box-shadow: 0 8px 24px rgba(0,0,0,0.5); border: 2px solid #333;">
                    <p style="margin-top: 10px; color: #aaa; font-style: italic; font-size: 1em;">
                        ${venue}
                    </p>
            </div>

                <div style="display:flex; justify-content:space-around; font-size:1.5em; margin:30px 0; font-weight:bold;">
                    <div style="text-align:center;">
                        ${homeTeamName}
                    </div>
                    <div style="align-self:center; font-size:1.2em; color:#2a8c4a;">VS</div>
                    <div style="text-align:center;">
                        ${awayTeamName}
                    </div>
                </div>

                <div style="text-align:center; font-size:1.2em; color:#ccc; margin:25px 0;">
                    🗓 ${matchDateTime}<br>
                    🏟️ ${venue}
                </div>

                <div style="text-align:center; margin:40px 0; color:#aaa; font-style:italic; font-size:1.1em;">
                    This is an upcoming match.<br>
                    Full preview, lineups, and statistics will be available closer to kick-off or after simulation.
                </div>

                <div style="text-align:center;">
                    <button class="back-to-dashboard" onclick="goBackSmart('fixtures')">
                        ← Back to Fixtures
                    </button>
                </div>
            </div>`;
        } catch (err) {
            console.error("Error loading fixture:", err);
            mainContent.innerHTML = `<div class="team-card"><p>Error loading fixture: ${err.message}</p><button onclick="goBackSmart('fixtures')">⬅ Back</button></div>`;
        }
    }
    async function loadFriendlies() {
        console.log(`Loading friendlies for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/matches/teams/${currentUserTeamId}/friendlies`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "Friendlies");
    }
    async function loadLeagueTable(seasonYear = null) {
        const leagueId = 1;
        try {
            const seasonParam = seasonYear ? `?seasonYear=${seasonYear}` : "";
            const [tableResponse, teamsResponse] = await Promise.all([
                authFetch(`/countries/leagues/${leagueId}/table${seasonParam}`),
                authFetch(`/countries/leagues/${leagueId}/teams`)
            ]);
            if (!tableResponse.ok) throw new Error(`League table load failed: ${tableResponse.status}`);
            if (!teamsResponse.ok) throw new Error(`League teams load failed: ${teamsResponse.status}`);

            const table = await tableResponse.json();
            const leagueTeams = await teamsResponse.json();
            const teamIdByName = new Map();
            leagueTeams.forEach(team => {
                teamIdByName.set(normalizeTeamKey(team.name), team.id);
            });

            const enhancedTable = table.map(row => ({
                ...row,
                teamId: teamIdByName.get(normalizeTeamKey(row.name)) ?? null
            }));
            renderTable(enhancedTable);
        } catch (err) {
            console.error("Failed to load league table:", err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league table.</p>
                </div>`;
        }
    }
    async function loadLeagueMatches(seasonYear = null) {
        const leagueId = 1; // Superliga – kasnije možeš proslediti parametar
        try {
            console.log(`Loading league matches...`);
            const seasonParam = seasonYear ? `?seasonYear=${seasonYear}` : "";
            const response = await authFetch(`/countries/leagues/${leagueId}/matches${seasonParam}`);
            console.log(`Response status: ${response.status}`);
            if (!response.ok) throw new Error("Failed to load league matches");
            const matches = await response.json();
            const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
            const seasonNumber = seasonYear ? Math.max(1, seasonYear - 2025 + 1) : null;
            renderLeagueMatches(results, seasonNumber ? `League Results - Season ${seasonNumber}` : "League Results");
        } catch (err) {
            console.error(err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button onclick="goBackSmart('dashboard')">⬅ Back</button>
                    <h2>Error</h2>
                    <p>Could not load league matches.</p>
                </div>`;
        }
    }
    async function loadLeagueSchedule(seasonYear = null) {
        const mainContent = document.getElementById("main-content");
        const leagueId = 1;
        try {
            const seasonsResponse = await authFetch(`/countries/leagues/${leagueId}/seasons`);
            const seasons = seasonsResponse.ok ? await seasonsResponse.json() : [];
            const selectedSeason = seasonYear || seasons[seasons.length - 1]?.seasonYear || null;
            const selectedSeasonNumber = seasons.find(s => s.seasonYear === selectedSeason)?.seasonNumber || 1;
            const seasonParam = selectedSeason ? `?seasonYear=${selectedSeason}` : "";
            const response = await authFetch(`/countries/leagues/${leagueId}/schedule${seasonParam}`);
            if (!response.ok) throw new Error(`Failed to load schedule: ${response.status}`);
            const schedule = await response.json();

            const byRound = new Map();
            let currentRound = 1;
            schedule.forEach(m => {
                const round = Number(m.round || 1);
                if (!byRound.has(round)) byRound.set(round, []);
                byRound.get(round).push(m);
                if (!m.played && round < currentRound) currentRound = round;
            });
            const rounds = [...byRound.keys()].sort((a, b) => a - b);
            const firstUnplayed = rounds.find(r => byRound.get(r).some(m => !m.played));
            if (firstUnplayed) currentRound = firstUnplayed;

            let html = `
            <div class="manager-card">
                <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">Back to Dashboard</button>
                <h2>League Schedule ${selectedSeason ? `- Season ${selectedSeasonNumber}` : ""}</h2>
                ${seasons.length ? `
                <div style="margin:8px 0 14px;">
                    <label for="season-select">Season:</label>
                    <select id="season-select" style="margin-left:8px;">
                        ${seasons.map(s => `<option value="${s.seasonYear}" ${s.seasonYear === selectedSeason ? "selected" : ""}>Season ${s.seasonNumber}</option>`).join("")}
                    </select>
                </div>` : ""}
                <div id="schedule-rounds">`;

            rounds.forEach(round => {
                const matches = byRound.get(round) || [];
                const currentTag = round === currentRound ? `<span style="color:#4caf50; font-size:0.9em;">(Current)</span>` : "";
                html += `<div id="round-${round}" style="margin:14px 0 18px;"><h3 style="margin-bottom:8px;">Round ${round} ${currentTag}</h3>`;
                matches.forEach(match => {
                    const score = match.played ? `${match.homeGoals} : ${match.awayGoals}` : "vs";
                    const playedClass = match.played ? "" : "opacity:0.86; cursor:default;";
                    const homeEsc = String(match.homeTeam || "").replace(/'/g, "\\'");
                    const awayEsc = String(match.awayTeam || "").replace(/'/g, "\\'");
                    const matchIdAttr = match.played && match.id ? `data-match-id="${match.id}"` : "";
                    html += `
                        <div class="match-row" style="${playedClass}" ${matchIdAttr} data-caller="leagueMatches">
                            <div style="font-size:0.88em; color:#aaa;">${match.matchDate || "N/A"}</div>
                            <div class="match-teams">
                                <span class="team-home"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${homeEsc}')">${escapeHtml(match.homeTeam)}</span></span>
                                <span class="score">${score}</span>
                                <span class="team-away"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${awayEsc}')">${escapeHtml(match.awayTeam)}</span></span>
                            </div>
                        </div>`;
                });
                html += `</div>`;
            });

            html += `</div></div>`;
            mainContent.innerHTML = html;
            mainContent.querySelectorAll('.match-row[data-match-id]').forEach(row => {
                row.addEventListener('click', () => {
                    const matchId = row.getAttribute('data-match-id');
                    if (matchId) loadMatch(matchId, 'leagueMatches');
                });
            });
            const seasonSelect = document.getElementById('season-select');
            if (seasonSelect) {
                seasonSelect.addEventListener('change', () => loadLeagueSchedule(Number(seasonSelect.value)));
            }

            setTimeout(() => {
                const currentEl = document.getElementById(`round-${currentRound}`);
                if (currentEl) currentEl.scrollIntoView({ behavior: "smooth", block: "start" });
            }, 20);
        } catch (err) {
            console.error("Failed to load league schedule:", err);
            mainContent.innerHTML = `
                <div class="manager-card">
                    <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league schedule.</p>
                </div>`;
        }
    }
    async function loadCup() {
        console.log(`Loading cup matches for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/cups/${currentUserTeamId}`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "Cup");
    }
    async function loadInternational() {
        console.log(`Loading international matches for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/internationals/${currentUserTeamId}`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "International Matches");
    }
    async function loadForum() {
        console.log(`Loading forum for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/forum/teams/${currentUserTeamId}`);
        console.log(`Response status: ${response.status}`);
        const posts = await response.json();

        const mainContent = document.getElementById("main-content");

        let html = `
        <div class="manager-card">
            <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">⬅ Back to Dashboard</button>
            <h2>Forum</h2>`;

        posts.forEach(p => {
            html += `
                <div class="match-row">
                    <span>${p.author}</span>
                    <span>${p.title}</span>
                    <span>${p.date}</span>
                </div>`;
        });

        html += `</div>`;
        mainContent.innerHTML = html;
    }
    async function loadChat() {
        console.log(`Loading chat for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/chat/teams/${currentUserTeamId}`);
        console.log(`Response status: ${response.status}`);
        const messages = await response.json();

        const mainContent = document.getElementById("main-content");

        let html = `
        <div class="manager-card">
            <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">⬅ Back to Dashboard</button>
            <h2>Team Chat</h2>`;

        messages.forEach(m => {
            html += `
                <div class="match-row">
                    <span>${m.user}</span>
                    <span>${m.message}</span>
                </div>`;
        });

        html += `</div>`;
        mainContent.innerHTML = html;
    }
    async function loadEvents() {
        console.log(`Loading events for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/events/teams/${currentUserTeamId}`);
        console.log(`Response status: ${response.status}`);
        const events = await response.json();

        const mainContent = document.getElementById("main-content");

        let html = `
        <div class="manager-card">
            <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">⬅ Back to Dashboard</button>
            <h2>Events</h2>`;

        events.forEach(e => {
            html += `
                <div class="match-row">
                    <span>${e.title}</span>
                    <span>${e.date}</span>
                </div>`;
        });

        html += `</div>`;
        mainContent.innerHTML = html;
    }
    async function loadPlayerStats() {
        console.log(`Loading player stats for userTeamId ${currentUserTeamId}`);
        const response = await authFetch(`/demo/stats/teams/${currentUserTeamId}/players`);
        console.log(`Response status: ${response.status}`);
        const players = await response.json();
        renderPlayers(players, "Player Stats");
    }
    async function loadTeamStats() {
        console.log(`Loading team stats for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/stats/teams/${currentUserTeamId}`);
        console.log(`Response status: ${response.status}`);
        const stats = await response.json();

        const mainContent = document.getElementById("main-content");

        let html = `
        <div class="manager-card">
            <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">⬅ Back to Dashboard</button>
            <h2>Team Stats</h2>
            <p>Goals: ${stats.goals}</p>
            <p>Conceded: ${stats.conceded}</p>
            <p>Possession: ${stats.possession}%</p>
            <p>Shots per game: ${stats.shots}</p>
        </div>`;
        mainContent.innerHTML = html;
    }
    async function loadTopScorersAndAssists() {
        try {
            console.log(`Loading top scorers for ${currentUserTeamId}`);
            const leagueId = 1;
            const [scorersRes, assistsRes, leagueTeamsRes] = await Promise.all([
                authFetch(`/stats/leagues/${leagueId}/topscorers`),
                authFetch(`/stats/leagues/${leagueId}/topassists`),
                authFetch(`/countries/leagues/${leagueId}/teams`)
            ]);
            console.log(`Response status: ${scorersRes.status}`);
            console.log(`Response status: ${assistsRes.status}`);
            const scorers = await scorersRes.json();
            const assists = await assistsRes.json();
            const leagueTeams = leagueTeamsRes.ok ? await leagueTeamsRes.json() : [];
            const teamIdByName = new Map();
            leagueTeams.forEach(t => teamIdByName.set(t.name, t.id));
            const playerIdByKey = new Map();
            await Promise.all(leagueTeams.map(async team => {
                try {
                    const r = await authFetch(`/countries/teams/${team.id}/players`);
                    if (!r.ok) return;
                    const players = await r.json();
                    players.forEach(p => playerIdByKey.set(`${team.name}|${p.name}`, p.id));
                } catch (e) {}
            }));

            const mainContent = document.getElementById("main-content");

            let html = `
            <div class="manager-card" style="padding: 25px;">
                <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">⬅ Back to Dashboard</button>
                <h2 style="text-align: center; margin: 20px 0 30px; color: #e94560;">Stats lige – Top liste</h2>

                <div class="top-lists" style="display: flex; gap: 40px; justify-content: center; flex-wrap: wrap;">

                    <!-- Top Strelci -->
                    <div class="top-scorers" style="min-width: 340px; flex: 1;">
                        <h3 style="text-align: center; color: #ffd700; margin-bottom: 15px;">⚽ Top Strelci</h3>
                        <ul style="list-style: none; padding: 0; margin: 0;">`;

            scorers.forEach((s, i) => {
                const rankColor = i < 3 ? '#ffd700' : '#aaa'; // zlatno za top 3
                const bgColor = i % 2 === 0 ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)'; // zebra
                html += `
                    <li style="padding: 12px 15px; background: ${bgColor}; border-radius: 8px; margin: 6px 0;
                               transition: all 0.2s; display: flex; justify-content: space-between; align-items: center;">
                        <span style="color: ${rankColor}; font-weight: bold; min-width: 30px;">${i+1}.</span>
                        <span style="flex: 1; text-align: left; padding-left: 10px;">
                            ${playerIdByKey.get(`${s.teamName}|${s.playerName}`) && teamIdByName.get(s.teamName)
                                ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${teamIdByName.get(s.teamName)}, ${playerIdByKey.get(`${s.teamName}|${s.playerName}`)}, '${escapeHtml(s.teamName)}')">${s.playerName}</span>`
                                : s.playerName}
                            <small style="color: #888;">(${teamIdByName.get(s.teamName) ? `<span class="cs-clickable" onclick="loadLeagueTeam(${teamIdByName.get(s.teamName)}, '${escapeHtml(s.teamName)}')">${s.teamName}</span>` : s.teamName})</small>
                        </span>
                        <span style="font-weight: bold; color: #ff7582; min-width: 60px; text-align: right;">
                            ${s.goals} ⚽
                        </span>
                    </li>`;
            });

            html += `</ul></div>

                    <!-- Top Asistenti -->
                    <div class="top-assists" style="min-width: 340px; flex: 1;">
                        <h3 style="text-align: center; color: #9d4edd; margin-bottom: 15px;">🅰 Top Asistenti</h3>
                        <ul style="list-style: none; padding: 0; margin: 0;">`;

            assists.forEach((a, i) => {
                const rankColor = i < 3 ? '#9d4edd' : '#aaa';
                const bgColor = i % 2 === 0 ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)';
                html += `
                    <li style="padding: 12px 15px; background: ${bgColor}; border-radius: 8px; margin: 6px 0;
                               transition: all 0.2s; display: flex; justify-content: space-between; align-items: center;">
                        <span style="color: ${rankColor}; font-weight: bold; min-width: 30px;">${i+1}.</span>
                        <span style="flex: 1; text-align: left; padding-left: 10px;">
                            ${playerIdByKey.get(`${a.teamName}|${a.playerName}`) && teamIdByName.get(a.teamName)
                                ? `<span class="cs-clickable" onclick="loadLeagueTeamPlayer(${teamIdByName.get(a.teamName)}, ${playerIdByKey.get(`${a.teamName}|${a.playerName}`)}, '${escapeHtml(a.teamName)}')">${a.playerName}</span>`
                                : a.playerName}
                            <small style="color: #888;">(${teamIdByName.get(a.teamName) ? `<span class="cs-clickable" onclick="loadLeagueTeam(${teamIdByName.get(a.teamName)}, '${escapeHtml(a.teamName)}')">${a.teamName}</span>` : a.teamName})</small>
                        </span>
                        <span style="font-weight: bold; color: #4fc3f7; min-width: 60px; text-align: right;">
                            ${a.assists} 🅰
                        </span>
                    </li>`;
            });

            html += `</ul></div></div></div>`;

            mainContent.innerHTML = html;

            // Dodatni hover efekat (možeš i CSS-om, ali ovde inline za brzinu)
            document.querySelectorAll('.top-lists li').forEach(li => {
                li.addEventListener('mouseenter', () => {
                    li.style.background = 'rgba(157, 78, 221, 0.15)'; // ljubičasto hover
                    li.style.transform = 'translateX(5px)';
                });
                li.addEventListener('mouseleave', () => {
                    li.style.background = li.style.background.includes('0.05') ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.1)';
                    li.style.transform = 'translateX(0)';
                });
            });

        } catch (err) {
            console.error("Error loading top lists:", err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button onclick="goBackSmart('dashboard')">⬅ Back</button>
                    <h2>Error</h2>
                    <p>Could not load top lists. Check connection or backend.</p>
                </div>`;
        }
    }
    async function loadAnalytics() {
            console.log(`Loading analytics for ${currentUserTeamId}`);
            const response = await authFetch(`/demo/analytics/teams/${currentUserTeamId}`);
            console.log(`Response status: ${response.status}`);
            const data = await response.json();

            const mainContent = document.getElementById("main-content");

            let html = `
            <div class="manager-card">
                <button class="back-to-dashboard" onclick="goBackSmart('dashboard')">⬅ Back to Dashboard</button>
                <h2>Analytics</h2>
                <p>xG: ${data.xg}</p>
                <p>xGA: ${data.xga}</p>
                <p>Pressing Index: ${data.pressing}</p>
                <p>Form Rating: ${data.form}</p>
            </div>`;
            mainContent.innerHTML = html;
        }
    function renderPlayers(players, title) {
        renderPlayersView(players, title, { loadPlayer, getImageFilename });
    }
        function renderMatches(matches, title) {
        renderMatchesView(matches, title, { loadMatch });
    }
    function renderTable(table) {
        renderTableView(table, { loadLeagueTeam, escapeHtml, formatGoalDiff });
    }
    async function loadLeagueTeam(teamId, teamName, options = {}) {
        const pushHistory = options.pushHistory !== false;
        if (pushHistory) pushNavState({ type: 'leagueTeam', teamId, teamName });
        const mainContent = document.getElementById("main-content");
        try {
            const response = await authFetch(`/teams/${teamId}/players`);
            if (!response.ok) throw new Error(`Team players load failed: ${response.status}`);
            const players = await response.json();
            const isUserTeam = Number(teamId) === Number(currentUserTeamId);

            let html = `
            <div class="manager-card">
                <button class="back-to-dashboard" onclick="loadLeagueTable()">Back to League Table</button>
                <h2>${escapeHtml(teamName)}</h2>
                <p style="text-align:center; color:#9aa0a6; margin-top:-8px;">Click a player to open profile</p>`;

            if (isUserTeam) {
                html += `<div class="manager-grid">`;
                players.forEach(player => {
                    const filename = getImageFilename(player.name);
                    html += `
                    <div class="manager-player-card league-player-card"
                         data-player-id="${player.id}"
                         data-team-id="${teamId}"
                         data-team-name="${escapeHtml(teamName)}">
                        <img src="/images/${filename}.jpg" onerror="this.src='/images/player.jpg'">
                        <div class="player-name">${escapeHtml(player.name)}</div>
                        <div class="player-meta">${escapeHtml(player.position)} - ${player.age}</div>
                        <div class="player-rating">OVR ${player.overall}</div>
                        <div class="player-meta">Rating: ${formatRatingBadge(player.rating)} | Form: ${formatFormBadge(player.form)}</div>
                        <div class="player-meta">Goals: ${player.totalGoals ?? 0} | Assists: ${player.totalAssists ?? 0}</div>
                    </div>`;
                });
                html += `</div>`;
            } else {
                html += `
                <div style="overflow-x:auto;">
                    <table class="league-table" style="width:100%; border-collapse:collapse; margin-top:10px;">
                        <thead>
                            <tr style="background:rgba(157,78,221,0.25); color:#fff;">
                                <th style="padding:10px; text-align:left;">Player</th>
                                <th style="padding:10px; text-align:center;">POS</th>
                                <th style="padding:10px; text-align:center;">Rating</th>
                                <th style="padding:10px; text-align:center;">Form</th>
                                <th style="padding:10px; text-align:center;">OVR</th>
                                <th style="padding:10px; text-align:center;">G/A</th>
                            </tr>
                        </thead>
                        <tbody>`;
                players.forEach((player, index) => {
                    const rowBg = index % 2 === 0 ? "rgba(255,255,255,0.03)" : "rgba(0,0,0,0.08)";
                    html += `
                            <tr class="league-player-card" style="background:${rowBg}; cursor:pointer;"
                                data-player-id="${player.id}"
                                data-team-id="${teamId}"
                                data-team-name="${escapeHtml(teamName)}">
                                <td style="padding:10px;">${escapeHtml(player.name)}</td>
                                <td style="padding:10px; text-align:center;">${escapeHtml(player.position)}</td>
                                <td style="padding:10px; text-align:center;">${formatRatingBadge(player.rating)}</td>
                                <td style="padding:10px; text-align:center;">${formatFormBadge(player.form)}</td>
                                <td style="padding:10px; text-align:center;">${player.overall ?? 0}</td>
                                <td style="padding:10px; text-align:center;">${player.totalGoals ?? 0}/${player.totalAssists ?? 0}</td>
                            </tr>`;
                });
                html += `
                        </tbody>
                    </table>
                </div>`;
            }

            html += `</div>`;
            mainContent.innerHTML = html;

            mainContent.querySelectorAll(".league-player-card").forEach(card => {
                card.addEventListener("click", () => {
                    const playerId = Number(card.dataset.playerId);
                    const playerTeamId = Number(card.dataset.teamId);
                    const playerTeamName = card.dataset.teamName || "Team";
                    loadLeagueTeamPlayer(playerId, playerTeamId, playerTeamName);
                });
            });
        } catch (err) {
            console.error("Failed to load team details:", err);
            mainContent.innerHTML = `
            <div class="manager-card">
                <button class="back-to-dashboard" onclick="loadLeagueTable()">Back</button>
                <h2>Error</h2>
                <p>Could not load team details.</p>
            </div>`;
        }
    }
    async function loadLeagueTeamPlayer(playerId, teamId, teamName, options = {}) {
        const pushHistory = options.pushHistory !== false;
        if (pushHistory) pushNavState({ type: 'leagueTeamPlayer', playerId, teamId, teamName });
        const mainContent = document.getElementById("main-content");
        try {
            const isUserTeam = Number(teamId) === Number(currentUserTeamId);
            const [playerResponse, ratingSummary] = await Promise.all([
                authFetch(`/players/${playerId}`),
                fetchPlayerRatingSummary(playerId)
            ]);

            if (!playerResponse.ok) throw new Error(`Player load failed: ${playerResponse.status}`);
            const player = await playerResponse.json();
            const fmtSkill = (exact, visible) => {
                if (exact != null && Number.isFinite(Number(exact))) return Number(exact).toFixed(2);
                if (visible != null && Number.isFinite(Number(visible))) return Number(visible).toFixed(2);
                return "-";
            };
            const filename = getImageFilename(player.name);

            if (isUserTeam) {
                mainContent.innerHTML = `
                <div class="player-card-wrapper">
                    <div class="player-card">
                        <button id="back-to-league-team" class="back-to-dashboard">Back to ${escapeHtml(teamName)}</button>
                        <div class="card-header">
                            <div class="overall-rating">${player.overall}</div>
                            <div class="position">${escapeHtml(player.position)}</div>
                        </div>
                        <div class="player-image">
                            <img src="/images/${filename}.jpg" onerror="this.src='/images/player.jpg'" alt="${escapeHtml(player.name)}">
                        </div>
                        <div class="player-name">${escapeHtml(player.name)}</div>
                        <div class="player-stats">
                            <div class="stat"><span>Age</span><span>${player.age}</span></div>
                            <div class="stat"><span>Stamina</span><span>${fmtSkill(player.staminaExact, player.stamina)}</span></div>
                            <div class="stat"><span>Goalkeeper</span><span>${fmtSkill(player.goalkeeperExact, player.goalkeeper)}</span></div>
                            <div class="stat"><span>Pace</span><span>${fmtSkill(player.paceExact, player.pace)}</span></div>
                            <div class="stat"><span>Defending</span><span>${fmtSkill(player.defendingExact, player.defending)}</span></div>
                            <div class="stat"><span>Technique</span><span>${fmtSkill(player.techniqueExact, player.technique)}</span></div>
                            <div class="stat"><span>Playmaker</span><span>${fmtSkill(player.playmakerExact, player.playmaker)}</span></div>
                            <div class="stat"><span>Passing</span><span>${fmtSkill(player.passingExact, player.passing)}</span></div>
                            <div class="stat"><span>Shooting</span><span>${fmtSkill(player.shootingExact, player.shooting)}</span></div>
                            <div class="stat"><span>OVR</span><span>${player.overall ?? "-"}</span></div>
                            <div class="stat"><span>Rating</span><span>${formatRatingBadge(player.rating)}</span></div>
                            <div class="stat"><span>Form</span><span>${formatFormBadge(player.form)}</span></div>
                            <div class="stat"><span>Total Goals</span><span>${player.totalGoals ?? 0}</span></div>
                            <div class="stat"><span>Total Assists</span><span>${player.totalAssists ?? 0}</span></div>
                            <div class="stat"><span>Average Grade (1-10)</span><span>${formatRatingBadge(ratingSummary.averageRating10)}</span></div>
                        </div>
                    </div>
                </div>`;
            } else {
                mainContent.innerHTML = `
                <div class="manager-card" style="max-width:720px; margin:0 auto;">
                    <button id="back-to-league-team" class="back-to-dashboard">Back to ${escapeHtml(teamName)}</button>
                    <h2 style="text-align:center;">${escapeHtml(player.name)}</h2>
                    <div style="display:grid; grid-template-columns: 1fr 1fr; gap:12px; margin-top:18px;">
                        <div class="stat-item"><div class="stat-label">Position</div><div class="stat-value">${escapeHtml(player.position)}</div></div>
                        <div class="stat-item"><div class="stat-label">OVR</div><div class="stat-value">${player.overall ?? "-"}</div></div>
                        <div class="stat-item"><div class="stat-label">Rating</div><div class="stat-value">${formatRatingBadge(player.rating)}</div></div>
                        <div class="stat-item"><div class="stat-label">Form</div><div class="stat-value">${formatFormBadge(player.form)}</div></div>
                        <div class="stat-item"><div class="stat-label">Age</div><div class="stat-value">${player.age ?? "-"}</div></div>
                        <div class="stat-item"><div class="stat-label">Goals</div><div class="stat-value">${player.totalGoals ?? 0}</div></div>
                        <div class="stat-item"><div class="stat-label">Assists</div><div class="stat-value">${player.totalAssists ?? 0}</div></div>
                        <div class="stat-item"><div class="stat-label">Average Grade (1-10)</div><div class="stat-value">${formatRatingBadge(ratingSummary.averageRating10)} (${ratingSummary.matchesPlayed} matches)</div></div>
                    </div>
                    <p style="margin-top:16px; color:#9aa0a6; text-align:center;">Detailed skills are hidden for players outside your team.</p>
                </div>`;
            }

            const backButton = document.getElementById("back-to-league-team");
            if (backButton) {
                backButton.addEventListener("click", () => loadLeagueTeam(teamId, teamName));
            }
        } catch (err) {
            console.error("Failed to load player profile:", err);
            mainContent.innerHTML = `
                <div class="manager-card">
                    <button id="back-to-league-team-fallback" class="back-to-dashboard">Back</button>
                    <h2>Error</h2>
                    <p>Could not load player profile.</p>
                </div>`;
            const backButton = document.getElementById("back-to-league-team-fallback");
            if (backButton) {
                backButton.addEventListener("click", () => loadLeagueTeam(teamId, teamName));
            }
        }
    }
        function renderFixtures(fixtures, title) {
        renderFixturesView(fixtures, title);
    }
    function renderLeagueMatches(matches, title = "League Results") {
        renderLeagueMatchesView(matches, title, { loadMatch });
    }
    function openStadiumImage(imageUrl) {
        // Otvara sliku u novom tabu ili modalu
        window.open(imageUrl, '_blank');
        // Alternativa: modal (ako želiš lepše)
         const modal = document.createElement('div');
         modal.innerHTML = `<img src="${imageUrl}" style="max-width:90vw; max-height:90vh;">`;
         modal.style.position = 'fixed'; modal.style.top='5%'; modal.style.left='5%'; etc.
         document.body.appendChild(modal);
    }
    function showStadiumModal(imageUrl, stadiumName) {
        const modal = document.createElement('div');
        modal.style.position = 'fixed';
        modal.style.inset = '0';
        modal.style.background = 'rgba(0,0,0,0.85)';
        modal.style.display = 'flex';
        modal.style.alignItems = 'center';
        modal.style.justifyContent = 'center';
        modal.style.zIndex = '9999';
        modal.innerHTML = `
            <div style="position: relative; max-width: 90vw; max-height: 90vh;">
                <button onclick="this.parentElement.parentElement.remove()"
                        style="position: absolute; top: -40px; right: 0; background: #f44336; color: white; border: none; border-radius: 50%; width: 36px; height: 36px; font-size: 1.4em; cursor: pointer;">
                    ×
                </button>
                <img src="${imageUrl}" alt="${stadiumName}" style="max-width: 100%; max-height: 85vh; border-radius: 12px; box-shadow: 0 10px 40px rgba(0,0,0,0.7);">
                <p style="color: white; text-align: center; margin-top: 12px; font-size: 1.2em;">
                    ${stadiumName}
                </p>
            </div>
        `;
        // Zatvaranje klika van slike
        modal.onclick = (e) => {
            if (e.target === modal) modal.remove();
        };
        document.body.appendChild(modal);
    }

    window.loadPage = loadPage;
    window.parseMatchDate = parseMatchDate;
    window.getImageFilename = getImageFilename;
    window.loadPlayer = loadPlayer;
    window.loadMatch = loadMatch;
    window.loadFirstTeam = loadFirstTeam;
    window.loadResults = loadResults;
    window.loadJuniors = loadJuniors;
    window.loadFormations = loadFormations;
    window.loadCoaches = loadCoaches;
    window.loadTrainingSetup = loadTrainingReports;
    window.loadTrainingReports = loadTrainingReports;
    window.loadTrainingReportsPage = loadTrainingReportsPage;
    window.loadClubProfile = loadClubProfile;
    window.loadUpcomingMatches = loadUpcomingMatches;
    window.loadFixtures = loadFixtures;
    window.renderFixtures = renderFixtures;
    window.loadFixture = loadFixture;
    window.loadFriendlies = loadFriendlies;
    window.loadLeagueTable = loadLeagueTable;
    window.loadLeagueSchedule = loadLeagueSchedule;
    window.loadLeagueMatches = loadLeagueMatches;
    window.renderLeagueMatches = renderLeagueMatches;
    window.loadCup = loadCup;
    window.loadInternational = loadInternational;
    window.loadForum = loadForum;
    window.loadChat = loadChat;
    window.loadEvents = loadEvents;
    window.loadPlayerStats = loadPlayerStats;
    window.loadTeamStats = loadTeamStats;
    window.loadTopScorersAndAssists = loadTopScorersAndAssists;
    window.loadAnalytics = loadAnalytics;
    window.renderPlayers = renderPlayers;
    window.renderMatches = renderMatches;
    window.renderTable = renderTable;
    window.loadLeagueTeam = loadLeagueTeam;
    window.loadLeagueTeamPlayer = loadLeagueTeamPlayer;
    window.openTeamByName = openTeamByName;
    window.openStadiumImage = openStadiumImage;
    window.showStadiumModal = showStadiumModal;
    window.goBackSmart = goBackSmart;
























