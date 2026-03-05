// pages.js
import { authFetch } from './auth.js';
import { renderPlayersView, renderMatchesView, renderTableView, renderFixturesView, renderLeagueMatchesView } from './pages-renderers.js';
    let currentUserTeamId = null;

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
               loadPage(target);
           }
       });

    function buildEmptyState(message) {
        return `<div class="manager-card" style="text-align:center; padding:40px;">
                    <h2>${message}</h2>
                </div>`;
    }
    async function loadPage(page) {
        const mainContent = document.getElementById("main-content");
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

                case "formations":
                    await loadFormations();
                    break;

                case "coaches":
                    await loadCoaches();
                    break;

                case "training":
                    await loadTrainingReports();
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
    async function loadPlayer(playerId) {
        const mainContent = document.getElementById("main-content");
        console.log(`Loading player for team ${currentUserTeamId} and player ${playerId}`);
        const [response, ratingSummary] = await Promise.all([
            authFetch(`/teams/${currentUserTeamId}/players/${playerId}`),
            fetchPlayerRatingSummary(playerId)
        ]);
        console.log(`Response status: ${response.status}`);
        if (!response.ok) {
            mainContent.innerHTML = `<div class="team-card"><p>Player not found.</p><button onclick="loadPage('firstTeam')">Back</button></div>`;
            return;
        }

        const player = await response.json();
        mainContent.innerHTML = `
            <div class="manager-card">
                <button class="big-button" onclick="loadPage('firstTeam')" style="margin-bottom:16px;">Back to Team</button>
                <h2>${escapeHtml(player.name)}</h2>
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
                    <div class="cs-stat-card"><div class="icon">🔋</div><div class="val">${player.stamina ?? "-"}</div><div class="lbl">Stamina</div></div>
                    <div class="cs-stat-card"><div class="icon">💨</div><div class="val">${player.pace ?? "-"}</div><div class="lbl">Pace</div></div>
                    <div class="cs-stat-card"><div class="icon">🛡️</div><div class="val">${player.defending ?? "-"}</div><div class="lbl">Defending</div></div>
                    <div class="cs-stat-card"><div class="icon">🎯</div><div class="val">${player.technique ?? "-"}</div><div class="lbl">Technique</div></div>
                    <div class="cs-stat-card"><div class="icon">🧠</div><div class="val">${player.playmaker ?? "-"}</div><div class="lbl">Playmaker</div></div>
                    <div class="cs-stat-card"><div class="icon">🎁</div><div class="val">${player.passing ?? "-"}</div><div class="lbl">Passing</div></div>
                    <div class="cs-stat-card"><div class="icon">🚀</div><div class="val">${player.shooting ?? "-"}</div><div class="lbl">Shooting</div></div>
                    <div class="cs-stat-card"><div class="icon">🧤</div><div class="val">${player.goalkeeper ?? "-"}</div><div class="lbl">Goalkeeper</div></div>
                </div>
            </div>`;
    }
    async function loadMatch(matchId, caller) {
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
                        <div>${homeTeamName}</div>
                        <div>${homeGoals}</div>
                    </div>
                    <div style="align-self:center; font-size:1.6em;">–</div>
                    <div style="text-align:center;">
                        <div>${awayTeamName}</div>
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

                const homeTotalShots = homeShotsOn + homeShotsOff;
                const awayTotalShots = awayShotsOn + awayShotsOff;

                const homeCorners = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === homeTeamName).length;
                const awayCorners = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === awayTeamName).length;

                const homeYellows = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === homeTeamName).length;
                const awayYellows = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === awayTeamName).length;

                const homeReds = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === homeTeamName).length;
                const awayReds = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === awayTeamName).length;

                const homePenalties = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === homeTeamName).length;
                const awayPenalties = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === awayTeamName).length;

                const homePoss = events.filter(e => e.eventType === "ChanceEvent" && e.eventTeam === homeTeamName).length;
                const awayPoss = events.filter(e => e.eventType === "ChanceEvent" && e.eventTeam === awayTeamName).length;
                const totalPoss = homePoss + awayPoss;
                const homePossPct = totalPoss > 0 ? Math.round((homePoss / totalPoss) * 100) : 50;
                const awayPossPct = 100 - homePossPct;

                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Match Stats</h3>`;

                html += `
                <table style="width:100%; border-collapse:collapse; font-size:0.95em;">
                    <thead>
                        <tr style="background:rgba(76,175,80,0.15);">
                            <th style="padding:12px; text-align:left;">Stats</th>
                            <th style="padding:12px; text-align:center;">${homeTeamName}</th>
                            <th style="padding:12px; text-align:center;">${awayTeamName}</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr><td style="padding:10px;">Possession</td><td style="text-align:center;font-weight:bold;">${homePossPct}%</td><td style="text-align:center;font-weight:bold;">${awayPossPct}%</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Shots</td><td style="text-align:center;">${homeTotalShots}</td><td style="text-align:center;">${awayTotalShots}</td></tr>
                        <tr><td style="padding:10px;">Shots on target</td><td style="text-align:center;">${homeShotsOn}</td><td style="text-align:center;">${awayShotsOn}</td></tr>
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
                                <div style="flex:1;">${p.playerName}</div>
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
                    const assist = g.assistant ? ` <span style="color:#888;">(assist: ${g.assistant})</span>` : '';
                    const disallowed = g.goalScored === false;
                    const lineColor = disallowed ? "#ffb3b3" : "inherit";
                    const verdict = disallowed ? ` <span style="color:#ff6b6b; font-weight:600;">DISALLOWED (VAR)</span>` : "";
                    html += `
                    <li style="padding:12px; margin:8px 0; background:rgba(255,255,255,0.05); border-radius:8px;">
                        <strong>${g.matchMinute}'</strong> <span style="color:${lineColor};">⚽ ${g.scorer || "?"} ${assist}${verdict}</span>
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
        console.log(`Loading juniors for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/teams/${currentUserTeamId}/juniors`);
        console.log(`Response status: ${response.status}`);
        const players = await response.json();
        renderPlayers(players, "Juniors");
    }
    async function loadFormations() {
        console.log(`Loading formations for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/teams/${currentUserTeamId}/formations`);
        console.log(`Response status: ${response.status}`);
        const formations = await response.json();

        const mainContent = document.getElementById("main-content");

        let html = `<div class="manager-card">
                <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
            <h2>Formations</h2>
            <div class="formation-list">`;

        formations.forEach(f => {
            html += `
                <div class="formation-card">
                    <h3>${f.name}</h3>
                    <p>${f.description ?? ""}</p>
                </div>`;
        });

        html += `</div></div>`;
        mainContent.innerHTML = html;
    }
    async function loadCoaches() {
        console.log(`Loading coaches for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/teams/${currentUserTeamId}/coaches`);
        console.log(`Response status: ${response.status}`);
        const coaches = await response.json();

        const mainContent = document.getElementById("main-content");

        let html = `<div class="manager-card">
                <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
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
        console.log(`Loading training reports for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/trainings/${currentUserTeamId}/reports`);
        console.log(`Response status: ${response.status}`);
        const reports = await response.json();

        const mainContent = document.getElementById("main-content");

        let html = `<div class="manager-card">
                <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
            <h2>Training Reports</h2>`;

        reports.forEach(r => {
            html += `
                <div class="match-row">
                    <span>${r.playerName}</span>
                    <span>${r.note}</span>
                    <span class="score">${r.improvement}</span>
                </div>`;
        });

        html += `</div>`;
        mainContent.innerHTML = html;
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
            <button class="back-to-dashboard-profile" onclick="loadDashboard()">
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
    async function loadFixture(fixtureId) {
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
                mainContent.innerHTML = `<div class="team-card"><p>Fixture not found.</p><button class="back-to-dashboard" onclick="loadPage('fixtures')">⬅ Back to Fixtures</button></div>`;
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
                    <button class="back-to-dashboard" onclick="loadPage('fixtures')">
                        ← Back to Fixtures
                    </button>
                </div>
            </div>`;
        } catch (err) {
            console.error("Error loading fixture:", err);
            mainContent.innerHTML = `<div class="team-card"><p>Error loading fixture: ${err.message}</p><button onclick="loadPage('fixtures')">⬅ Back</button></div>`;
        }
    }
    async function loadFriendlies() {
        console.log(`Loading friendlies for ${currentUserTeamId}`);
        const response = await authFetch(`/demo/matches/teams/${currentUserTeamId}/friendlies`);
        console.log(`Response status: ${response.status}`);
        const matches = await response.json();
        renderMatches(matches, "Friendlies");
    }
        async function loadLeagueTable() {
        const leagueId = 1;
        try {
            const [tableResponse, teamsResponse] = await Promise.all([
                authFetch(`/countries/leagues/${leagueId}/table`),
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
                    <button class="back-to-dashboard" onclick="loadDashboard()">Back</button>
                    <h2>Error</h2>
                    <p>Could not load league table.</p>
                </div>`;
        }
    }
    async function loadLeagueMatches() {
        const leagueId = 1; // Superliga – kasnije možeš proslediti parametar
        try {
            console.log(`Loading league matches...`);
            const response = await authFetch(`/countries/leagues/${leagueId}/matches`);
            console.log(`Response status: ${response.status}`);
            if (!response.ok) throw new Error("Failed to load league matches");
            const matches = await response.json();
            const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
            renderLeagueMatches(results);
        } catch (err) {
            console.error(err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button onclick="loadDashboard()">⬅ Back</button>
                    <h2>Error</h2>
                    <p>Could not load league matches.</p>
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
            <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
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
            <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
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
            <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
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
            <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
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
            const scorersRes = await authFetch(`/stats/leagues/${currentUserTeamId}/topscorers`);
            console.log(`Response status: ${scorersRes.status}`);
            const scorers = await scorersRes.json();

            console.log(`Loading top assists for ${currentUserTeamId}`);
            const assistsRes = await authFetch(`/stats/leagues/${currentUserTeamId}/topassists`);
            console.log(`Response status: ${assistsRes.status}`);
            const assists = await assistsRes.json();

            const mainContent = document.getElementById("main-content");

            let html = `
            <div class="manager-card" style="padding: 25px;">
                <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
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
                            ${s.playerName} <small style="color: #888;">(${s.teamName})</small>
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
                            ${a.playerName} <small style="color: #888;">(${a.teamName})</small>
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
                    <button onclick="loadDashboard()">⬅ Back</button>
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
                <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
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
    async function loadLeagueTeam(teamId, teamName) {
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
    async function loadLeagueTeamPlayer(playerId, teamId, teamName) {
        const mainContent = document.getElementById("main-content");
        try {
            const isUserTeam = Number(teamId) === Number(currentUserTeamId);
            const [playerResponse, ratingSummary] = await Promise.all([
                authFetch(`/players/${playerId}`),
                fetchPlayerRatingSummary(playerId)
            ]);

            if (!playerResponse.ok) throw new Error(`Player load failed: ${playerResponse.status}`);
            const player = await playerResponse.json();
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
                            <div class="stat"><span>Stamina</span><span>${player.stamina}</span></div>
                            <div class="stat"><span>Goalkeeper</span><span>${player.goalkeeper}</span></div>
                            <div class="stat"><span>Pace</span><span>${player.pace}</span></div>
                            <div class="stat"><span>Defending</span><span>${player.defending}</span></div>
                            <div class="stat"><span>Technique</span><span>${player.technique}</span></div>
                            <div class="stat"><span>Playmaker</span><span>${player.playmaker}</span></div>
                            <div class="stat"><span>Passing</span><span>${player.passing}</span></div>
                            <div class="stat"><span>Shooting</span><span>${player.shooting}</span></div>
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
    function renderLeagueMatches(matches) {
        renderLeagueMatchesView(matches, { loadMatch });
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
    window.loadTrainingReports = loadTrainingReports;
    window.loadClubProfile = loadClubProfile;
    window.loadUpcomingMatches = loadUpcomingMatches;
    window.loadFixtures = loadFixtures;
    window.renderFixtures = renderFixtures;
    window.loadFixture = loadFixture;
    window.loadFriendlies = loadFriendlies;
    window.loadLeagueTable = loadLeagueTable;
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
    window.openStadiumImage = openStadiumImage;
    window.showStadiumModal = showStadiumModal;

















