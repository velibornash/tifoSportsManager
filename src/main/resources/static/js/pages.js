function buildEmptyState(message) {
    return `<div class="manager-card" style="text-align:center; padding:40px;">
                <h2>${message}</h2>
            </div>`;
}
async function loadPage(page) {
    const mainContent = document.getElementById("main-content");

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
                await loadTopScorers();
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
async function loadPlayer(playerId) {
    const mainContent = document.getElementById("main-content");
    const response = await fetch(`/teams/1/players/${playerId}`);
    if(!response.ok) {
        mainContent.innerHTML = `<div class="team-card"><p>Player not found.</p><button onclick="loadPage('firstTeam')">⬅ Back</button></div>`;
        return;
    }

    const player = await response.json();

mainContent.innerHTML = `
<div class="player-detail-card">
    <div class="player-detail-image">
        <img src="/images/${player.name.replace(/\s+/g,'_')}.jpg"
             onerror="this.onerror=null;this.src='/images/player.jpg'">
    </div>
    <div class="player-detail-info">
        <h2>${player.name}</h2>
        <div class="player-detail-stats">
            <div><span>Age:</span> ${player.age}</div>
            <div><span>Position:</span> ${player.position}</div>
            <div><span>Stamina:</span> ${player.stamina}</div>
            <div><span>Goalkeeper:</span> ${player.goalkeeper}</div>
            <div><span>Pace:</span> ${player.pace}</div>
            <div><span>Defending:</span> ${player.defending}</div>
            <div><span>Technique:</span> ${player.technique}</div>
            <div><span>Playmaker:</span> ${player.playmaker}</div>
            <div><span>Passing:</span> ${player.passing}</div>
            <div><span>Shooting:</span> ${player.shooting}</div>
            <div><span>Overall:</span> ${player.overall}</div>
            <div><span>Total Goals:</span> ${player.totalGoals}</div>
        </div>
        <button onclick="loadPage('firstTeam')">⬅ Back to Team</button>
    </div>
</div>`;

}
async function loadMatch(matchId) {
    const mainContent = document.getElementById("main-content");
    console.log(`Pokušavam da učitam meč ID: ${matchId}`);

    try {
        const response = await fetch(`/matches/${matchId}/detail`);
        console.log(`Status odgovora: ${response.status}`);

        if (!response.ok) {
            const text = await response.text();
            console.error(`Greška ${response.status}: ${text}`);
            mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p><button onclick="loadPage('results')">⬅ Back</button></div>`;
            return;
        }

        const events = await response.json();
        console.log("MATCH EVENTS RECEIVED:", events);
        console.log("Broj eventa:", events.length);

        if (events.length === 0) {
            mainContent.innerHTML = `<div class="team-card"><p>No data for this match.</p><button onclick="loadPage('results')">⬅ Back</button></div>`;
            return;
        }

        // Osnovni podaci meča
        const first = events[0];
        const homeTeamName = first.homeTeam || "Home";
        const awayTeamName = first.awayTeam || "Away";
        const homeGoals = first.homeGoals ?? 0;
        const awayGoals = first.awayGoals ?? 0;

        const matchDate = parseMatchDate(first.matchDate);
        const formattedDate = matchDate.toLocaleString('en-GB', {
            weekday: 'short', year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
        });

        // HTML struktura (isto kao pre)
        mainContent.innerHTML = `
        <div class="team-card">
            <h2 style="text-align:center;">Match Details</h2>
            <div style="display:flex; justify-content:space-around; font-size:1.2em; margin:15px 0;">
                <div style="text-align:center;">
                    <div>${homeTeamName}</div>
                    <div style="font-weight:bold;">${homeGoals}</div>
                </div>
                <div style="align-self:center; font-size:1.5em;">-</div>
                <div style="text-align:center;">
                    <div>${awayTeamName}</div>
                    <div style="font-weight:bold;">${awayGoals}</div>
                </div>
            </div>
            <div style="text-align:center; color:#666; margin-bottom:20px;">🗓 ${formattedDate}</div>

            <div style="display:flex; justify-content:center; gap:12px; margin-bottom:25px; flex-wrap:wrap;">
                <button id="view-stats" style="padding:8px 16px; font-weight:bold;">View Stats</button>
                <button id="view-goals" style="padding:8px 16px; font-weight:bold;">View Goals</button>
                <button id="view-events" style="padding:8px 16px; font-weight:bold;">All Events</button>
            </div>

            <div id="match-info" style="margin-top:15px; min-height:200px;"></div>

            <button onclick="loadPage('results')" style="margin-top:20px; padding:8px 16px;">⬅ Back to Results</button>
        </div>`;

        const infoDiv = document.getElementById("match-info");

        // Definisanje funkcije za prikaz statistike (isti kod kao u view-stats listeneru)
        function showStats() {
            const homeShotsOn     = events.filter(e => e.eventType === "ShotOnTargetEvent" && e.shotOnTargetTeam === homeTeamName).length;
            const awayShotsOn     = events.filter(e => e.eventType === "ShotOnTargetEvent" && e.shotOnTargetTeam === awayTeamName).length;
            const homeShotsOff    = events.filter(e => e.eventType === "ShotOffTargetEvent" && e.shotOffTargetTeam === homeTeamName).length;
            const awayShotsOff    = events.filter(e => e.eventType === "ShotOffTargetEvent" && e.shotOffTargetTeam === awayTeamName).length;

            const homeTotalShots  = homeShotsOn + homeShotsOff;
            const awayTotalShots  = awayShotsOn + awayShotsOff;

            const homeCorners     = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === homeTeamName).length;
            const awayCorners     = events.filter(e => e.eventType === "CornerEvent" && e.eventTeam === awayTeamName).length;

            const homeYellows     = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === homeTeamName).length;
            const awayYellows     = events.filter(e => e.eventType === "YellowCardEvent" && e.eventTeam === awayTeamName).length;

            const homeReds        = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === homeTeamName).length;
            const awayReds        = events.filter(e => e.eventType === "RedCardEvent" && e.eventTeam === awayTeamName).length;

            const homePenalties   = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === homeTeamName).length;
            const awayPenalties   = events.filter(e => e.eventType === "PenaltyEvent" && e.eventTeam === awayTeamName).length;

            const homePossession  = events.filter(e => e.eventType === "ChanceEvent" && e.eventTeam === homeTeamName).length;
            const awayPossession  = events.filter(e => e.eventType === "ChanceEvent" && e.eventTeam === awayTeamName).length;
            const totalPoss       = homePossession + awayPossession;
            const homePossPct     = totalPoss > 0 ? Math.round((homePossession / totalPoss) * 100) : 50;
            const awayPossPct     = 100 - homePossPct;

            let html = `<h3 style="text-align:center; margin:0 0 15px 0; color:#2a8c4a;">Match Statistics</h3>`;

            html += `
            <table style="width:100%; border-collapse:collapse; font-size:0.95em; color:#ddd;">
                <thead>
                    <tr style="background:rgba(42,140,74,0.15);">
                        <th style="padding:10px; text-align:left; border-bottom:1px solid #444;">Stat</th>
                        <th style="padding:10px; text-align:center; border-bottom:1px solid #444;">${homeTeamName}</th>
                        <th style="padding:10px; text-align:center; border-bottom:1px solid #444;">${awayTeamName}</th>
                    </tr>
                </thead>
                <tbody>
                    <tr style="background:rgba(255,255,255,0.03);">
                        <td style="padding:10px; border-bottom:1px solid #333;">Possession</td>
                        <td style="text-align:center; font-weight:bold; color:#2a8c4a;">${homePossPct}%</td>
                        <td style="text-align:center; font-weight:bold; color:#2a8c4a;">${awayPossPct}%</td>
                    </tr>
                    <tr>
                        <td style="padding:10px; border-bottom:1px solid #333;">Shots</td>
                        <td style="text-align:center;">${homeTotalShots}</td>
                        <td style="text-align:center;">${awayTotalShots}</td>
                    </tr>
                    <tr style="background:rgba(255,255,255,0.03);">
                        <td style="padding:10px; border-bottom:1px solid #333;">Shots on Target</td>
                        <td style="text-align:center;">${homeShotsOn}</td>
                        <td style="text-align:center;">${awayShotsOn}</td>
                    </tr>
                    <tr>
                        <td style="padding:10px; border-bottom:1px solid #333;">Shots off Target</td>
                        <td style="text-align:center;">${homeShotsOff}</td>
                        <td style="text-align:center;">${awayShotsOff}</td>
                    </tr>
                    <tr style="background:rgba(255,255,255,0.03);">
                        <td style="padding:10px; border-bottom:1px solid #333;">Corners</td>
                        <td style="text-align:center;">${homeCorners}</td>
                        <td style="text-align:center;">${awayCorners}</td>
                    </tr>
                    <tr>
                        <td style="padding:10px; border-bottom:1px solid #333;">Yellow Cards</td>
                        <td style="text-align:center; color:#ff9800;">${homeYellows}</td>
                        <td style="text-align:center; color:#ff9800;">${awayYellows}</td>
                    </tr>
                    <tr style="background:rgba(255,255,255,0.03);">
                        <td style="padding:10px; border-bottom:1px solid #333;">Red Cards</td>
                        <td style="text-align:center; color:#f44336;">${homeReds}</td>
                        <td style="text-align:center; color:#f44336;">${awayReds}</td>
                    </tr>
                    <tr>
                        <td style="padding:10px;">Penalties</td>
                        <td style="text-align:center;">${homePenalties}</td>
                        <td style="text-align:center;">${awayPenalties}</td>
                    </tr>
                </tbody>
            </table>`;

            infoDiv.innerHTML = html;
        }

        // View Goals
        document.getElementById("view-goals").addEventListener("click", () => {
            const goals = events.filter(e => e.eventType === "GoalEvent");
            if (goals.length === 0) {
                infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:20px;">No goals recorded for this match.</p>`;
                return;
            }

            let html = `<h3 style="text-align:center; margin:0 0 15px 0; color:#2a8c4a;">Goals</h3>`;
            html += `<ul style="list-style:none; padding:0; margin:0;">`;

            goals.forEach(g => {
                const assist = g.assistant ? `<span style="color:#888;"> (assist: ${g.assistant})</span>` : '';
                html += `
                    <li style="padding:10px 15px; margin:6px 0; background:rgba(255,255,255,0.04); border-radius:8px; border-left:4px solid #2a8c4a;">
                        <span style="color:#eee; font-weight:bold;">${g.matchMinute}'</span> ⚽
                        <strong style="color:#fff;">${g.scorer || "?"}</strong>${assist}
                        <span style="color:#888; margin-left:10px;">${g.scoreAfterGoal || ""}</span>
                    </li>`;
            });

            html += `</ul>`;
            infoDiv.innerHTML = html;
        });

        // View Events (isti kod kao pre)
        document.getElementById("view-events").addEventListener("click", () => {
            if (events.length === 0) {
                infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:20px;">No events recorded for this match.</p>`;
                return;
            }

            let html = `
            <h3 style="text-align:center; margin:0 0 15px 0; color:#2a8c4a;">All Match Events</h3>
            <div style="text-align:center; margin-bottom:15px;">
                <label style="color:#ccc; margin-right:10px;">Filter by Type:
                    <select id="event-type-filter" style="background:#222; color:#eee; border:1px solid #444; padding:6px; border-radius:6px;">
                        <option value="">All</option>
                        ${[...new Set(events.map(e => e.eventType))].sort().map(t => `<option value="${t}">${t}</option>`).join("")}
                    </select>
                </label>
                <label style="color:#ccc;">After minute:
                    <input type="number" id="event-minute-filter" min="0" max="90" style="width:70px; background:#222; color:#eee; border:1px solid #444; padding:6px; border-radius:6px;">
                </label>
                <button id="apply-event-filter" style="margin-left:10px; padding:6px 14px; background:#2a8c4a; color:white; border:none; border-radius:6px; cursor:pointer;">Apply</button>
            </div>
            <ul id="all-events-list" style="list-style:none; padding:0; margin:0;"></ul>`;

            infoDiv.innerHTML = html;

            const renderEvents = (evts) => {
                const list = document.getElementById("all-events-list");
                list.innerHTML = "";

                evts.forEach(e => {
                    let details = "";
                    switch (e.eventType) {
                        case "GoalEvent":
                            details = `<strong style="color:#fff;">${e.scorer || "?"}</strong> ${e.assistant ? `<span style="color:#aaa;">(assist: ${e.assistant})</span>` : ""} ${e.scoreAfterGoal ? `→ ${e.scoreAfterGoal}` : ""}`;
                            break;
                        case "YellowCardEvent":
                            details = `🟨 ${e.yellowCardPlayer || "?"}`;
                            break;
                        case "RedCardEvent":
                            details = `🔴 ${e.redCardPlayer || "?"}`;
                            break;
                        case "PenaltyEvent":
                            details = `Penalty: ${e.penaltyTaker || "?"} ${e.penaltyScored ? "✅ scored" : "❌ missed"}`;
                            break;
                        case "ShotOnTargetEvent":
                            details = `Shot on: ${e.shotOnTargetPlayer || "?"} (${e.shotOnTargetTeam || "?"})`;
                            break;
                        case "ShotOffTargetEvent":
                            details = `Shot off: ${e.shotOffTargetPlayer || "?"} (${e.shotOffTargetTeam || "?"})`;
                            break;
                        case "CornerEvent":
                            details = `Corner taken by: ${e.cornerTaker || "?"}`;
                            break;
                        case "FreeKickEvent":
                            details = `Free kick: ${e.freeKickTaker || "?"}`;
                            break;
                        case "ChanceEvent":
                            details = `Possession → ${e.possessionTeam || "?"}`;
                            break;
                        default:
                            details = "";
                    }

                    const line = `
                        <span style="color:#aaa; min-width:50px; display:inline-block;">${e.matchMinute || "?"}'</span>
                        <strong style="color:#2a8c4a;">[${e.eventType}]</strong>
                        ${details ? " – " + details : ""}`;

                    list.innerHTML += `
                        <li style="padding:10px 15px; margin:6px 0; background:rgba(255,255,255,0.04); border-radius:8px; border-left:4px solid ${getEventColor(e.eventType)};">
                            ${line}
                        </li>`;
                });
            };

            function getEventColor(type) {
                if (type === "GoalEvent") return "#4CAF50";
                if (type.includes("Yellow")) return "#ff9800";
                if (type.includes("Red")) return "#f44336";
                if (type.includes("Shot")) return "#2196F3";
                if (type.includes("Corner")) return "#9C27B0";
                if (type.includes("Penalty")) return "#FF5722";
                return "#555";
            }

            const sorted = [...events].sort((a, b) => (a.matchMinute || 0) - (b.matchMinute || 0));
            renderEvents(sorted);

            document.getElementById("apply-event-filter").addEventListener("click", () => {
                const type = document.getElementById("event-type-filter").value;
                const minMinute = parseInt(document.getElementById("event-minute-filter").value) || 0;

                const filtered = sorted.filter(e =>
                    (!type || e.eventType === type) && (e.matchMinute || 0) >= minMinute
                );

                renderEvents(filtered);
            });
        });

        // KLJUČNO: Automatski prikaži statistiku odmah po učitavanju
        showStats();

        // Listener-i za dugmad ostaju (korisnik može da menja prikaz)
        document.getElementById("view-stats").addEventListener("click", showStats);
        document.getElementById("view-goals").addEventListener("click", () => {
            // ... kod za golove (isti kao pre) ...
        });
        document.getElementById("view-events").addEventListener("click", () => {
            // ... kod za all events (isti kao pre) ...
        });

    } catch (err) {
        console.error("Fetch error:", err);
        mainContent.innerHTML = `<div class="team-card"><p>Error loading match: ${err.message}</p><button onclick="loadPage('results')">⬅ Back</button></div>`;
    }
}
async function loadFirstTeam() {
    const response = await fetch("/teams/1/players");
    const players = await response.json();
    renderPlayers(players, "First Team");
}
async function loadResults() {
    const response = await fetch("/teams/1/matches");
    const matches = await response.json();
    renderMatches(matches, "Results");
}
async function loadJuniors() {
    const response = await fetch("/demo/teams/1/juniors");
    const players = await response.json();
    renderPlayers(players, "Juniors");
}
async function loadFormations() {
    const response = await fetch("/demo/teams/1/formations");
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
    const response = await fetch("/demo/teams/1/coaches");
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
    const response = await fetch("/demo/trainings/1/reports");
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
    const response = await fetch("/demo/teams/1/profile");
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
                <p class="club-subtitle">Serbian League Division 2 • Season 2025/26</p>
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
    const response = await fetch("/demo/matches/teams/1/upcoming");
    const matches = await response.json();
    renderMatches(matches, "Upcoming Matches");
}
// Prikaz liste fikstura
async function loadFixtures() {
    const response = await fetch("/demo/matches/teams/1/fixtures");
    const fixtures = await response.json();
    renderFixtures(fixtures, "Fixtures");
}

// Render fikstura – svaki red klikabilan
function renderFixtures(fixtures, title) {
    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
        <h2>${title}</h2>
        <div class="match-list">`;

    fixtures.forEach(fixture => {
        // Koristi fixture.id ako postoji, ili fallback na index
        const fixtureId = fixture.id || fixtures.indexOf(fixture);

        html += `
        <div class="match-row upcoming-match" onclick="loadFixture(${fixtureId})">
            <div style="font-size:0.9em; color:#aaa; margin-bottom:4px;">
                🗓 ${fixture.date || "N/A"} • ${fixture.time || ""}
            </div>
            <span class="team-home">${fixture.homeTeam}</span>
            <span class="score">VS</span>
            <span class="team-away">${fixture.awayTeam}</span>
            <div style="font-size:0.85em; color:#888; margin-top:6px;">
                🏟️ ${fixture.venue || "N/A"}
            </div>
        </div>`;
    });

    html += `</div></div>`;
    mainContent.innerHTML = html;
}

// Detalji pojedinačne fiksture
async function loadFixture(fixtureId) {
    const mainContent = document.getElementById("main-content");
    console.log(`Učitavam fiksturu ID: ${fixtureId}`);

    try {
        const response = await fetch(`/demo/matches/teams/1/fixtures/${fixtureId}`);
        console.log(`Status odgovora: ${response.status}`);
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
            console.error(`Greška ${response.status}: ${text}`);
            mainContent.innerHTML = `<div class="team-card"><p>Fixture not found.</p><button onclick="loadPage('fixtures')">⬅ Back to Fixtures</button></div>`;
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
                     style="max-width: 360px; height: auto; border-radius: 12px; box-shadow: 0 8px 24px rgba(0,0,0,0.5); border: 2px solid #333;">
                <p style="margin-top: 12px; color: #aaa; font-style: italic; font-size: 1em;">
                    ${venue}
                </p>
        </div>

            <div style="display:flex; justify-content:space-around; font-size:1.5em; margin:30px 0; font-weight:bold;">
                <div style="text-align:center;">
                    ${homeTeamName}
                </div>
                <div style="align-self:center; font-size:1.8em; color:#2a8c4a;">VS</div>
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
                <button onclick="loadPage('fixtures')" style="padding:12px 28px; background:#2a8c4a; color:white; border:none; border-radius:10px; font-weight:bold; font-size:1.1em; cursor:pointer; box-shadow:0 4px 12px rgba(0,0,0,0.3);">
                    ← Back to Fixtures
                </button>
            </div>
        </div>`;
    } catch (err) {
        console.error("Greška pri učitavanju fiksture:", err);
        mainContent.innerHTML = `<div class="team-card"><p>Error loading fixture: ${err.message}</p><button onclick="loadPage('fixtures')">⬅ Back</button></div>`;
    }
}
async function loadFriendlies() {
    const response = await fetch("/demo/matches/teams/1/friendlies");
    const matches = await response.json();
    renderMatches(matches, "Friendlies");
}
async function loadLeagueTable() {
    const response = await fetch("/demo/leagues/1/table");
    const table = await response.json();
    renderTable(table);
}
async function loadCup() {
    const response = await fetch("/demo/cups/1");
    const matches = await response.json();
    renderMatches(matches, "Cup");
}
async function loadInternational() {
    const response = await fetch("/demo/internationals/1");
    const matches = await response.json();
    renderMatches(matches, "International Matches");
}
async function loadForum() {
    const response = await fetch("/demo/forum/teams/1");
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
    const response = await fetch("/demo/chat/teams/1");
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
    const response = await fetch("/demo/events/teams/1");
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
    const response = await fetch("/demo/stats/teams/1/players");
    const players = await response.json();
    renderPlayers(players, "Player Stats");
}
async function loadTeamStats() {
    const response = await fetch("/demo/stats/teams/1");
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
async function loadTopScorers() {
    const response = await fetch("/demo/stats/leagues/1/topscorers");
    const scorers = await response.json();

    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
        <h2>Top Scorers</h2>`;

    scorers.forEach((s, i) => {
        html += `
            <div class="match-row">
                <span>${i+1}. ${s.name}</span>
                <span class="score">${s.goals}</span>
            </div>`;
    });

    html += `</div>`;
    mainContent.innerHTML = html;
}
async function loadAnalytics() {
    const response = await fetch("/demo/analytics/teams/1");
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
    const mainContent = document.getElementById("main-content");

    if (!Array.isArray(players)) {
        mainContent.innerHTML = buildEmptyState("No players found");
        return;
    }

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
        <h2>${title}</h2>
        <div class="manager-grid">`;

    players.forEach(player => {
        html += `
        <div class="manager-player-card" onclick="loadPlayer(${player.id})">
            <img src="/images/${player.name.replace(/\s+/g,'_')}.jpg"
                 onerror="this.src='/images/player.jpg'">
            <div class="player-name">${player.name}</div>
            <div class="player-meta">${player.position} • ${player.age}</div>
            <div class="player-rating">OVR ${player.overall}</div>
        </div>`;
    });

    html += `</div></div>`;
    mainContent.innerHTML = html;
}
function renderMatches(matches, title) {
    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
        <h2>${title}</h2>
        <div class="match-list">`;

    matches.forEach(match => {
        html += `
        <div class="match-row" onclick="loadMatch(${match.id})">
            <div style="font-size:0.9em; color:#aaa; margin-bottom:4px;">
                🗓 ${match.matchDate || "N/A"}
            </div>
            <span class="team-home">${match.homeTeam}</span>
            <span class="score">${match.homeGoals ?? "-"} : ${match.awayGoals ?? "-"}</span>
            <span class="team-away">${match.awayTeam}</span>
        </div>`;
    });

    html += `</div></div>`;
    mainContent.innerHTML = html;
}
function renderTable(table) {
    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
        <h2>League Table</h2>
        <table class="league-table">
            <tr>
                <th>#</th>
                <th>Team</th>
                <th>Pts</th>
                <th>GD</th>
            </tr>`;

    table.forEach((team, index) => {
        html += `
        <tr>
            <td>${index + 1}</td>
            <td>${team.name}</td>
            <td>${team.points}</td>
            <td>${team.goalDifference}</td>
        </tr>`;
    });

    html += `</table></div>`;
    mainContent.innerHTML = html;
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