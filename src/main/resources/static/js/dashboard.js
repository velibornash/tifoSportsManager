function loadDashboard() {
    const mainContent = document.getElementById("main-content");

    mainContent.innerHTML = `
    <div class="team-card">
        <div class="team-header">
            <img src="/images/omladinac.png" class="team-logo">
            <div class="team-name-wrapper">
                <h1>OFK Omladinac</h1>
                <p class="team-subtitle">Serbian League Division 2 • Season 2025/26</p>
            </div>
        </div>

        <div class="stats-grid clickable" onclick="loadLeagueTable()">
            <div class="stat-item">
                <div class="stat-value">1</div>
                <div class="stat-label">Position</div>
            </div>
            <div class="stat-item">
                <div class="stat-value">0</div>
                <div class="stat-label">Points</div>
            </div>
            <div class="stat-item">
                <div class="stat-value">0-0-0</div>
                <div class="stat-label">W-D-L</div>
            </div>
            <div class="stat-item">
                <div class="stat-value">0</div>
                <div class="stat-label">Goal Diff</div>
            </div>
        </div>

        <div class="next-match">
            <h3>Next Match</h3>
            <div class="match-info clickable" onclick="loadFixture(1)">
                <div class="team-away-home">
                    <img src="/images/sremac.jpg" class="match-team-logo small">
                    <span>Sremac Berkasovo</span>
                </div>
                <span class="vs">VS</span>
                <div class="team-away-home">
                    <img src="/images/omladinac.png" class="match-team-logo small">
                    <span>OFK Omladinac</span>
                </div>
            </div>
            <div class="match-date">
                🗓 15.03.2026 • 17:00<br>
                🏟️ Stadion Livadice
            </div>
        </div>

        <!-- Recent Matches (klub) -->
        <div class="recent-matches-section">
            <h3>Recent Matches</h3>
            <div id="recent-matches-list" class="match-list">
                <div class="loading">Učitavanje poslednjih mečeva...</div>
            </div>
        </div>

        <!-- Nova sekcija: Recent League Matches (poslednjih 5 iz lige) -->
        <div class="recent-matches-section">
            <h3>Recent Superliga Matches</h3>
            <div id="recent-league-matches-list" class="match-list">
                <div class="loading">Učitavanje poslednjih mečeva lige...</div>
            </div>
        </div>

        <div class="quick-stats">
            <div>Form: <span class="form-good">W W D L W</span></div>
            <div>Top Scorer: LJ. Ozegovic — 11 goals</div>
        </div>

        <div class="dashboard-actions">
    <button onclick="startDemoTest()">Pokreni odigravanje narednog kola</button>
    <button onclick="initializeDatabase()">Popuni bazu timovima</button>
    <button onclick="resetDatabase()" style="background:#b71c1c;">Obriši sve iz baze</button>
</div>
    </div>`;

    // Učitaj obe liste
    loadRecentMatches();                // klub
    loadRecentLeagueMatches();          // liga
    loadHomeTeamStats();                    // ← NOVO: učitaj poziciju, bodove, itd.
}

async function resetDatabase() {

    const confirmReset = confirm("⚠ This will DELETE entire database. Continue?");
    if (!confirmReset) return;

    try {
        const response = await fetch("/admin/reset-db", {
            method: "POST"
        });

        if (!response.ok) throw new Error("Reset failed");

        const message = await response.text();
        alert(message);
        window.location.reload();

    } catch (err) {
        console.error("Reset error:", err);
        alert("Database reset failed.");
    }
}

async function initializeDatabase() {
    const confirmInit = confirm("Da li želite da inicijalizujete bazu podataka?\n\nOvo može potrajati nekoliko sekundi.");
    if (!confirmInit) return;

    // 1. Prikazujemo loading popup
    const loadingPopup = document.createElement("div");
    loadingPopup.id = "loading-popup";
    loadingPopup.style.position = "fixed";
    loadingPopup.style.top = "0";
    loadingPopup.style.left = "0";
    loadingPopup.style.width = "100%";
    loadingPopup.style.height = "100%";
    loadingPopup.style.background = "rgba(0,0,0,0.6)";
    loadingPopup.style.display = "flex";
    loadingPopup.style.alignItems = "center";
    loadingPopup.style.justifyContent = "center";
    loadingPopup.style.zIndex = "9999";

    loadingPopup.innerHTML = `
        <div style="
            background: white;
            padding: 30px 50px;
            border-radius: 12px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.4);
            text-align: center;
            font-family: Arial, sans-serif;
        ">
            <h2 style="margin: 0 0 15px 0; color: #2c3e50;">Inicijalizacija baze u toku...</h2>
            <div style="font-size: 1.1em; color: #7f8c8d;">Molimo sačekajte, ne zatvarajte stranicu.</div>
            <div style="margin-top: 20px; font-size: 2em;">⏳</div>
        </div>
    `;

    document.body.appendChild(loadingPopup);

    try {
        const response = await fetch("/admin/initialize-db", {
            method: "POST"
        });

        // Skidamo loading popup čim dobijemo odgovor (uspešan ili ne)
        document.body.removeChild(loadingPopup);

        if (!response.ok) {
            throw new Error(`Greška: ${response.status} ${response.statusText}`);
        }

        const message = await response.text();

        // Uspešan alert + refresh nakon OK
        alert(message + "\n\nStranica će se sada osvežiti.");
        window.location.reload();  // ← automatski refresh

    } catch (err) {
        // Uklanjamo loading i prikazujemo grešku
        if (document.body.contains(loadingPopup)) {
            document.body.removeChild(loadingPopup);
        }

        console.error("DB Init error:", err);
        alert("Inicijalizacija baze nije uspela.\n\nGreška: " + err.message);
    }
}

async function loadRecentMatches() {
    try {
        const response = await fetch("/teams/1/matches");
        if (!response.ok) throw new Error("Greška pri učitavanju mečeva");

        const matches = await response.json();

        // Pretpostavljamo da su mečevi sortirani od najnovijeg (ako nisu, možeš sortirati)
       const recent = matches
       .sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate))
       .slice(0, 3);

        const list = document.getElementById("recent-matches-list");
        if (!list) return;

        if (recent.length === 0) {
            list.innerHTML = `<p style="text-align:center; color:#aaa;">No recent matches yet.</p>`;
            return;
        }

        let html = "";
        recent.forEach(match => {
            const isWin  = match.homeGoals > match.awayGoals ? "win" : "";
            const isDraw = match.homeGoals === match.awayGoals ? "draw" : "";
            const isLoss = match.homeGoals < match.awayGoals ? "loss" : "";

        html += `
        <div class="match-row recent-match" onclick="loadMatch(${match.id}, 'match')">
            <div class="match-date-small">${match.matchDate || "N/A"}</div>
            <div class="match-teams">
                <span class="team-home">${match.homeTeam}</span>
                <span class="score">
                    ${match.homeGoals ?? "-"} : ${match.awayGoals ?? "-"}
                </span>
                <span class="result-badge ${isWin ? 'win' : isDraw ? 'draw' : 'loss'}">
                    ${isWin ? 'W' : isDraw ? 'D' : 'L'}
                </span>
                <span class="team-away">${match.awayTeam}</span>
            </div>
        </div>`;
        });

        list.innerHTML = html;
    } catch (err) {
        console.error("Greška pri učitavanju recent matches:", err);
        document.getElementById("recent-matches-list").innerHTML =
            `<p style="text-align:center; color:#f44336;">Greška pri učitavanju mečeva</p>`;
    }
}
async function loadRecentLeagueMatches() {
    try {
        const leagueId = 1; // Superliga – možeš kasnije dinamicki izabrati
        const response = await fetch(`/countries/leagues/${leagueId}/matches`);
        if (!response.ok) throw new Error("Greška pri učitavanju mečeva lige");

        const matches = await response.json();

        // Sortiraj po datumu descending i uzmi poslednjih 5
        const recent = matches
            .sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate))
            .slice(0, 5);

        const list = document.getElementById("recent-league-matches-list");
        if (!list) return;

        if (recent.length === 0) {
            list.innerHTML = `<p style="text-align:center; color:#aaa;">No recent league matches yet.</p>`;
            return;
        }

        let html = "";
        recent.forEach(match => {
            const isHomeWin  = match.homeGoals > match.awayGoals ? "win" : "";
            const isDraw     = match.homeGoals === match.awayGoals ? "draw" : "";
            const isAwayWin  = match.homeGoals < match.awayGoals ? "loss" : "";

            // Odredi boju badge-a na osnovu rezultata (za neutralan prikaz)
            let badgeClass = "";
            let badgeText = "";
            if (match.homeGoals !== null && match.awayGoals !== null) {
                if (match.homeGoals > match.awayGoals) {
                    badgeClass = "win";
                    badgeText = "1";
                } else if (match.homeGoals < match.awayGoals) {
                    badgeClass = "loss";
                    badgeText = "2";
                } else {
                    badgeClass = "draw";
                    badgeText = "X";
                }
            }

            html += `
            <div class="match-row recent-match" onclick="loadMatch(${match.id}, 'leagueMatches')">
                <div class="match-date-small">${match.matchDate || "N/A"}</div>
                <div class="match-teams">
                    <span class="team-home">${match.homeTeam}</span>
                    <span class="score">
                        ${match.homeGoals ?? "-"} : ${match.awayGoals ?? "-"}
                    </span>
                    <span class="team-away">${match.awayTeam}</span>
                </div>
                ${badgeText ? `<span class="result-badge ${badgeClass}">${badgeText}</span>` : ''}
            </div>`;
        });

        list.innerHTML = html;
    } catch (err) {
        console.error("Greška pri učitavanju recent league matches:", err);
        document.getElementById("recent-league-matches-list").innerHTML =
            `<p style="text-align:center; color:#aaa;">No recent league matches yet.</p>`;
    }
}
async function loadHomeTeamStats() {
    try {

        const leagueId = 1;
        const response = await fetch(`/countries/leagues/${leagueId}/table`);
        if (!response.ok) throw new Error("Greška pri učitavanju tabele lige");

        const table = await response.json();

        const omladinacEntry = table.find(t => t.name === "OFK Omladinac");
        if (!omladinacEntry) {
            console.warn("Omladinac nije pronađen u tabeli lige");
            return;
        }
        console.log("Omladinac entry iz tabele:", omladinacEntry);
        document.querySelector(".team-name-wrapper h1").textContent = omladinacEntry.name;
        document.querySelector(".team-subtitle").textContent = "Superliga Srbije • Season 2025/26";

        const statValues = document.querySelectorAll(".stat-value");
        statValues[0].textContent = omladinacEntry.position || "?";
        statValues[1].textContent = omladinacEntry.points || "0";
        statValues[2].textContent = `${omladinacEntry.wins || 0}-${omladinacEntry.draws || 0}-${omladinacEntry.losses || 0}`;
        statValues[3].textContent = omladinacEntry.goalDifference || "+0";

    } catch (err) {
        console.error("Greška pri učitavanju stats tima:", err);
    }
}

// U loadDashboard() – pozovi je
loadHomeTeamStats();  // dodaj posle loadRecentLeagueMatches()
window.toggleSidebar = function(id) {
    const sidebars = document.querySelectorAll('.sidebar');
    sidebars.forEach(sb => {
        if(sb.id === id) {
            sb.classList.toggle('active');
        } else {
            sb.classList.remove('active');
        }
    });
};

// Mobile toggle
function toggleMobileMenu() {
    const sidebar = document.getElementById('mobileSidebar');
    const overlay = document.getElementById('mobileOverlay');

    sidebar.classList.toggle('active');
    overlay.classList.toggle('active');
}

function closeMobileMenu() {
    document.getElementById('mobileSidebar').classList.remove('active');
    document.getElementById('mobileOverlay').classList.remove('active');
}

