function loadPage(page) {

    const contentMap = {
        firstTeam: "First Team - Lista igrača",
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

    const title = contentMap[page] || "Coming Soon";

    document.getElementById("main-content").innerHTML = `
        <div class="team-card">
            <h2>${title}</h2>
            <p>Ovaj deo će biti povezan sa backend-om.</p>
            <button onclick="loadDashboard()">⬅ Back to Dashboard</button>
        </div>
    `;
}
