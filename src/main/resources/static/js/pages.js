async function loadPage(page) {
    console.log("loadPage called with:", page);
    const contentMap = {
        firstTeam: "First Team",
        juniors: "Juniors",
        formations: "Formations",
        coaches: "Coaches",
        training: "Training Reports",
        profile: "Profile",
        upcoming: "Upcoming Matches",
        results: "Results",
        fixtures: "Fixtures",
        leagueTable: "League Table",
        cup: "Cup",
        international: "International",
        friendlies: "Friendlies",
        forum: "Forum",
        chat: "Chat",
        events: "Events",
        playerStats: "Player Stats",
        teamStats: "Team Stats",
        topScorers: "Top Scorers",
        analytics: "Analytics"
    };

    const mainContent = document.getElementById("main-content");

    if(page === "firstTeam") {
        // Fetch players for Omladinac (teamId = 1)
        console.log("Fetching players for team 1"); // <--- DEBUG
        const response = await fetch("/teams/1/players");
        const players = await response.json();
        console.log("Players fetched:", players); // <--- DEBUG
        let html = `<div class="team-card">
                        <h2>First Team - OFK Omladinac</h2>
                        <div class="player-list" style="display:flex; flex-wrap:wrap; gap:15px;">`;

        players.forEach(player => {
            // kreiranje fajla slike
            let imageFile = `/images/${player.name.replace(/\s+/g,'_')}.jpg`;

            html += `
            <div class="player-card" onclick="loadPlayer(${player.id})">
                <div class="player-card-image">
                    <img src="${imageFile}" onerror="this.onerror=null;this.src='/images/player.jpg'">
                </div>
                <div class="player-card-info">
                    <h3>${player.name}</h3>
                    <p>${player.position} | ${player.age} yrs</p>
                </div>
            </div>`;
        });


        html += `</div>
                 <button onclick="loadDashboard()">⬅ Back to Dashboard</button>
                 </div>`;

        mainContent.innerHTML = html;
        return;
    }

    if(page === "results") {
        const mainContent = document.getElementById("main-content");
        const teamId = 1;
        const response = await fetch(`/teams/${teamId}/matches`);
        const matches = await response.json();

        // sortiranje descending po datumu
        matches.sort((a, b) => parseMatchDate(b.matchDate) - parseMatchDate(a.matchDate));

        if(matches.length === 0) {
            mainContent.innerHTML = `
                <div class="team-card" style="text-align:center; padding:20px;">
                    <h2>Results</h2>
                    <p>No matches played yet.</p>
                    <button onclick="loadDashboard()">⬅ Back to Dashboard</button>
                </div>`;
            return;
        }

        let html = `<div class="team-card" style="padding:20px; font-family:Arial, sans-serif; color:#222;">
                        <h2 style="text-align:center; margin-bottom:15px;">Match Results</h2>
                        <div class="match-list" style="display:flex; flex-direction:column; gap:15px;">`;

        matches.forEach(match => {
            const matchDate = parseMatchDate(match.matchDate);
            const formattedDate = matchDate.toLocaleString('en-GB', {
                weekday: 'short',
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit'
            });

            html += `
            <div class="match-card" onclick="loadMatch(${match.id})"
                 style="
                    display:grid;
                    grid-template-columns: 1fr auto 1fr auto;
                    align-items:center;
                    padding:12px 15px;
                    border-radius:10px;
                    background:#f5f5f5;
                    box-shadow: 1px 1px 8px rgba(0,0,0,0.07);
                    cursor:pointer;
                    font-family: 'Arial', sans-serif;
                    transition: transform 0.15s, box-shadow 0.15s;
                    gap:10px;
                 "
                 onmouseover="this.style.transform='scale(1.02)'; this.style.boxShadow='2px 2px 12px rgba(0,0,0,0.15)';"
                 onmouseout="this.style.transform='scale(1)'; this.style.boxShadow='1px 1px 8px rgba(0,0,0,0.07)';">
                <div style="text-align:left; font-weight:bold; font-size:1em; color:#0f2c54;">${match.homeTeam}</div>
                <div style="text-align:center; font-weight:bold; font-size:1.1em; color:#0f2c54;">${match.homeGoals} - ${match.awayGoals}</div>
                <div style="text-align:right; font-weight:bold; font-size:1em; color:#a11;">${match.awayTeam}</div>
                <div style="text-align:right; font-size:0.85em; color:#555; white-space:nowrap;">${formattedDate}</div>
            </div>`;
        });


        html += `</div>
                 <button onclick="loadDashboard()"
                     style="display:block; margin:20px auto 0; padding:10px 20px; border:none; border-radius:8px; background:#555; color:white; cursor:pointer;">
                     ⬅ Back to Dashboard
                 </button>
                 </div>`;

        mainContent.innerHTML = html;
        return
    }

    // fallback za ostale stranice
    const title = contentMap[page] || "Coming Soon";
    mainContent.innerHTML = `
        <div class="team-card">
            <h2>${title}</h2>
            <p>Ovaj deo će biti povezan sa backend-om.</p>
            <button onclick="loadDashboard()">⬅ Back to Dashboard</button>
        </div>
    `;
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
    const response = await fetch(`/matches/${matchId}`);
    if(!response.ok) {
        mainContent.innerHTML = `<div class="team-card"><p>Match not found.</p><button onclick="loadPage('results')">⬅ Back</button></div>`;
        return;
    }

    const match = await response.json();
    const matchDate = parseMatchDate(match.matchDate);
    const formattedDate = matchDate.toLocaleString('en-GB', {
        weekday: 'short',
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
    });

    mainContent.innerHTML = `
    <div class="team-card" style="background:#fdfdfd; border-radius:12px; padding:20px; box-shadow: 2px 2px 12px rgba(0,0,0,0.15); font-family:Arial, sans-serif; color:#222;">
        <h2 style="text-align:center; margin-bottom:15px;">Match Details</h2>

        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px;">
            <div style="text-align:center; flex:1;">
                <div style="font-size:1.5em; font-weight:bold; color:#1e3a8a;">${match.homeTeam}</div>
                <div style="font-size:1.2em; color:#555;">${match.homeGoals}</div>
            </div>

            <div style="flex:0 0 auto; font-size:1.3em; font-weight:bold;">-</div>

            <div style="text-align:center; flex:1;">
                <div style="font-size:1.5em; font-weight:bold; color:#dc2626;">${match.awayTeam}</div>
                <div style="font-size:1.2em; color:#555;">${match.awayGoals}</div>
            </div>
        </div>

        <div style="text-align:center; font-size:0.95em; color:#444; margin-bottom:15px;">
            🗓 ${formattedDate}
        </div>

        <div style="display:flex; justify-content:center; gap:20px; margin-bottom:20px;">
            <button style="padding:8px 15px; border:none; border-radius:6px; background:#1e3a8a; color:white; cursor:pointer;">View Stats</button>
            <button style="padding:8px 15px; border:none; border-radius:6px; background:#dc2626; color:white; cursor:pointer;">View Goals</button>
        </div>

        <button onclick="loadPage('results')"
            style="display:block; margin:0 auto; padding:10px 20px; border:none; border-radius:8px; background:#555; color:white; cursor:pointer;">
            ⬅ Back to Results
        </button>
    </div>
    `;
}


