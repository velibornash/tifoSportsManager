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
    const response = await fetch(`/matches/${matchId}/detail`);
    if (!response.ok) {
        mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p><button onclick="loadPage('results')">⬅ Back</button></div>`;
        return;
    }

    const match = await response.json();
    console.log("MATCH RECEIVED:", match);
    console.log("All events:", match.allEvents);

    const matchDate = parseMatchDate(match.matchDate);
    const formattedDate = matchDate.toLocaleString('en-GB', {
        weekday: 'short', year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
    });

    mainContent.innerHTML = `
<div class="team-card">
    <h2 style="text-align:center;">Match Details</h2>
    <div style="display:flex; justify-content:space-around;">
        <div style="text-align:center;">
            <div>${match.homeTeamName}</div>
            <div>${match.homeGoals}</div>
        </div>
        <div>-</div>
        <div style="text-align:center;">
            <div>${match.awayTeamName}</div>
            <div>${match.awayGoals}</div>
        </div>
    </div>
    <div style="text-align:center;">🗓 ${formattedDate}</div>

    <div style="display:flex; justify-content:center; gap:10px; margin-bottom:20px;">
        <button id="view-stats" style="padding:6px 12px;">View Stats</button>
        <button id="view-goals" style="padding:6px 12px;">View Goals</button>
        <button id="view-events" style="padding:6px 12px;">All Events</button>
    </div>

    <div id="match-info" style="margin-top:15px;"></div>

    <button onclick="loadPage('results')">⬅ Back to Results</button>
</div>`;

    const infoDiv = document.getElementById("match-info");

    // --- View Goals ---
    document.getElementById("view-goals").addEventListener("click", () => {
        const goals = match.allEvents.filter(e => e.type === "Goal");
        if (!goals || goals.length === 0) {
            infoDiv.innerHTML = "<p>No goals recorded for this match.</p>";
            return;
        }
        let html = "<h3>Goals:</h3><ul>";
        goals.forEach(g => {
            html += `<li>${g.minute}' ⚽ ${g.player}${g.assistantName ? ` (assist: ${g.assistantName})` : ''}</li>`;
        });
        html += "</ul>";
        infoDiv.innerHTML = html;
    });

    // --- View Stats ---
    document.getElementById("view-stats").addEventListener("click", () => {
        const homeShots = match.allEvents.filter(e => e.teamName === match.homeTeamName &&
            (e.type === "ShotOnTargetEvent" || e.type === "ShotOffTargetEvent")).length;
        const awayShots = match.allEvents.filter(e => e.teamName === match.awayTeamName &&
            (e.type === "ShotOnTargetEvent" || e.type === "ShotOffTargetEvent")).length;
        const homeCorners = match.allEvents.filter(e => e.teamName === match.homeTeamName && e.type === "CornerEvent").length;
        const awayCorners = match.allEvents.filter(e => e.teamName === match.awayTeamName && e.type === "CornerEvent").length;

        infoDiv.innerHTML = `
            <h3>Match Stats:</h3>
            <ul>
                <li>${match.homeTeamName} - Shots: ${homeShots}, Corners: ${homeCorners}</li>
                <li>${match.awayTeamName} - Shots: ${awayShots}, Corners: ${awayCorners}</li>
            </ul>
        `;
    });

    // --- All Events Viewer with filter ---
    document.getElementById("view-events").addEventListener("click", () => {
        if (!match.allEvents || match.allEvents.length === 0) {
            infoDiv.innerHTML = "<p>No events recorded for this match.</p>";
            return;
        }

        let html = `<h3>All Events:</h3>
            <div style="margin-bottom:10px;">
                <label>Filter by Type:
                    <select id="event-type-filter">
                        <option value="">All</option>
                        ${[...new Set(match.allEvents.map(e => e.type))].map(t => `<option value="${t}">${t}</option>`).join("")}
                    </select>
                </label>
                <label>After Minute: <input type="number" id="event-minute-filter" min="0" style="width:60px;"></label>
                <button id="apply-event-filter">Apply</button>
            </div>
            <ul id="all-events-list"></ul>`;

        infoDiv.innerHTML = html;

        const renderEvents = (events) => {
            const list = document.getElementById("all-events-list");
            list.innerHTML = "";
            events.forEach(e => {
                let line = `${e.minute}' [${e.type}] - Team: ${e.team}`;
                if (e.type === "GoalEvent") line += ` - ${e.scorerName}${e.assistantName ? ` (assist: ${e.assistantName})` : ''}`;
                if (e.type === "ChanceEvent") line += ` - Player: ${e.playerName} - Dangerous: ${e.dangerous}`;
                if (e.type === "YellowCardEvent" || e.type === "RedCardEvent") line += ` - Player: ${e.playerName}`;
                if (e.type === "SubstitutionEvent") line += ` - ${e.playerOutName} → ${e.playerInName}`;
                list.innerHTML += `<li>${line}</li>`;
            });
        };

        renderEvents(match.allEvents);

        document.getElementById("apply-event-filter").addEventListener("click", () => {
            const type = document.getElementById("event-type-filter").value;
            const minMinute = parseInt(document.getElementById("event-minute-filter").value) || 0;
            const filtered = match.allEvents.filter(e => (!type || e.type === type) && e.minute >= minMinute);
            renderEvents(filtered);
        });
    });
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


// ovo gore su dobre rute i imamo ih samo donje idu na demo

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

    mainContent.innerHTML = `
    <div class="player-detail-card">
        <div class="player-detail-image">
            <img src="${profile.logo || '/images/omladinac.png'}"
                 onerror="this.onerror=null;this.src='/images/omladinac.png'">
        </div>

        <div class="player-detail-info">
            <h2>${profile.name}</h2>

            <div class="player-detail-stats">
                <div><span>Founded:</span> ${profile.founded}</div>
                <div><span>Stadium:</span> ${profile.stadium}</div>
                <div><span>Budget:</span> €${profile.budget.toLocaleString()}</div>
                <div><span>Reputation:</span> ${profile.reputation}</div>
            </div>

            <button onclick="loadPage('dashboard')">⬅ Back</button>
        </div>
    </div>`;
}


async function loadUpcomingMatches() {
    const response = await fetch("/demo/matches/teams/1/upcoming");
    const matches = await response.json();
    renderMatches(matches, "Upcoming Matches");
}

async function loadFixtures() {
    const response = await fetch("/demo/matches/teams/1/fixtures");
    const matches = await response.json();
    renderMatches(matches, "Fixtures");
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

    let html = `<div class="manager-card"><h2>Forum</h2>`;

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

    let html = `<div class="manager-card"><h2>Team Chat</h2>`;

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

    let html = `<div class="manager-card"><h2>Events</h2>`;

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

    mainContent.innerHTML = `
    <div class="manager-card">
        <h2>Team Stats</h2>
        <p>Goals: ${stats.goals}</p>
        <p>Conceded: ${stats.conceded}</p>
        <p>Possession: ${stats.possession}%</p>
        <p>Shots per game: ${stats.shots}</p>
    </div>`;
}

async function loadTopScorers() {
    const response = await fetch("/demo/stats/leagues/1/topscorers");
    const scorers = await response.json();

    const mainContent = document.getElementById("main-content");

    let html = `<div class="manager-card"><h2>Top Scorers</h2>`;

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

    mainContent.innerHTML = `
    <div class="manager-card">
        <h2>Analytics</h2>
        <p>xG: ${data.xg}</p>
        <p>xGA: ${data.xga}</p>
        <p>Pressing Index: ${data.pressing}</p>
        <p>Form Rating: ${data.form}</p>
    </div>`;
}

function renderPlayers(players, title) {
    const mainContent = document.getElementById("main-content");

    if (!Array.isArray(players)) {
        mainContent.innerHTML = buildEmptyState("No players found");
        return;
    }

    let html = `
    <div class="manager-card">
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

            html += `</div>
                     <button onclick="loadDashboard()">⬅ Back to Dashboard</button>
                     </div>`;
    mainContent.innerHTML = html;
}

function renderMatches(matches, title) {

    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card">
        <h2>${title}</h2>
        <div class="match-list">`;

    matches.forEach(match => {

        html += `
        <div class="match-row" onclick="loadMatch(${match.id})">
            <span class="team-home">${match.homeTeam}</span>
            <span class="score">${match.homeGoals ?? "-"} : ${match.awayGoals ?? "-"}</span>
            <span class="team-away">${match.awayTeam}</span>
        </div>`;
    });

            html += `</div>

                     <button onclick="loadDashboard()">⬅ Back to Dashboard</button>
                     </div>`;

    mainContent.innerHTML = html;
}

function renderTable(table) {

    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card">
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

            html += `</div>
                     <button onclick="loadDashboard()">⬅ Back to Dashboard</button>
                     </div>`;

    mainContent.innerHTML = html;
}
