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
            <div><span>Goalkeeper:</span> ${player.overall}</div>
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
