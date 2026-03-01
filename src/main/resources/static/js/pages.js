   // Event delegation za back-button (radi i posle svakog innerHTML overwrite-a)
   document.addEventListener('click', function(e) {
       if (e.target.id === 'back-button' || e.target.closest('#back-button')) {
           const button = e.target.closest('#back-button');
           const target = button.dataset.target || 'results';
           console.log(`Back kliknut → učitavam: ${target}`);
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
    async function loadPlayer(playerId) {
        const mainContent = document.getElementById("main-content");
        const response = await fetch(`/teams/1/players/${playerId}`);
        if(!response.ok) {
            mainContent.innerHTML = `<div class="team-card"><p>Player not found.</p><button onclick="loadPage('firstTeam')">⬅ Back</button></div>`;
            return;
        }

        const player = await response.json();
        const filename = getImageFilename(player.name);
        mainContent.innerHTML = `

                <div class="player-card-wrapper">
                    <div class="player-card">
                    <button onclick="loadPage('firstTeam')">⬅ Back to Team</button>
                        <div class="card-header">
                            <div class="overall-rating">${player.overall}</div>
                            <div class="position">${player.position}</div>
                        </div>
                        <div class="player-image">
                            <img src="/images/${filename}.jpg" onerror="this.src='/images/player.jpg'" alt="${player.name}">
                        </div>
                        <div class="player-name">${player.name}</div>
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
                            <div class="stat"><span>Total Goals</span><span>${player.totalGoals}</span></div>
                        </div>
                    </div>
                </div>

    </div>`;

    }
    async function loadMatch(matchId, caller) {
        const mainContent = document.getElementById("main-content");
        console.log(`Učitavam meč ID: ${matchId}, caller: ${caller}`);

        try {
            const response = await fetch(`/matches/${matchId}/detail`);
            console.log(`Status: ${response.status}`);

            if (!response.ok) {
                const text = await response.text();
                console.error(`Greška ${response.status}: ${text}`);
                mainContent.innerHTML = `<div class="team-card"><p>Meč nije pronađen.</p></div>`;
                return;
            }

            const events = await response.json();
            console.log("MATCH EVENTS:", events);
            console.log("Broj eventa:", events.length);

            if (events.length === 0) {
                mainContent.innerHTML = `<div class="team-card"><p>Nema podataka za ovaj meč.</p></div>`;
                return;
            }

            // Osnovni podaci meča
            const first = events[0];
            const homeTeamName = first.homeTeam || "Home";
            const awayTeamName = first.awayTeam || "Away";
            const homeGoals = first.homeGoals ?? 0;
            const awayGoals = first.awayGoals ?? 0;

            const matchDate = parseMatchDate(first.matchDate);
            const formattedDate = matchDate.toLocaleString('sr-RS', {
                weekday: 'short', year: 'numeric', month: 'short', day: 'numeric',
                hour: '2-digit', minute: '2-digit'
            });

            // HTML struktura – sa JEDNIM back dugmetom
            mainContent.innerHTML = `
            <div class="team-card">
                <h2 style="text-align:center;">Detalji meča</h2>

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
                    <button id="view-stats" style="padding:8px 16px; font-weight:bold;">Statistika</button>
                    <button id="view-goals" style="padding:8px 16px; font-weight:bold;">Golovi</button>
                    <button id="view-events" style="padding:8px 16px; font-weight:bold;">Svi događaji</button>
                </div>

                <div id="match-info" style="margin-top:15px; min-height:200px;"></div>

                <!-- Jedno zajedničko Back dugme -->
                <div style="text-align:center; margin-top:30px;">
                    <button id="back-button" style="padding:10px 24px; font-size:1.1em;">
                        ⬅ Nazad
                    </button>
                </div>
            </div>`;

            // Postavi ponašanje Back dugmeta u zavisnosti od caller-a
            const backButton = document.getElementById('back-button');
            let backTarget = 'results';

            if (caller === 'match') {
                backTarget = 'results';
                backButton.textContent = '⬅ Nazad na Rezultate';
            } else if (caller === 'leagueMatches') {
                backTarget = 'leagueMatches';
                backButton.textContent = '⬅ Nazad na Mečeve lige';
            } else {
                console.warn(`Nepoznat caller: ${caller} → fallback na 'results'`);
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

                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Statistika meča</h3>`;

                html += `
                <table style="width:100%; border-collapse:collapse; font-size:0.95em;">
                    <thead>
                        <tr style="background:rgba(76,175,80,0.15);">
                            <th style="padding:12px; text-align:left;">Statistika</th>
                            <th style="padding:12px; text-align:center;">${homeTeamName}</th>
                            <th style="padding:12px; text-align:center;">${awayTeamName}</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr><td style="padding:10px;">Posed lopte</td><td style="text-align:center;font-weight:bold;">${homePossPct}%</td><td style="text-align:center;font-weight:bold;">${awayPossPct}%</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Šutevi</td><td style="text-align:center;">${homeTotalShots}</td><td style="text-align:center;">${awayTotalShots}</td></tr>
                        <tr><td style="padding:10px;">Šutevi u okvir</td><td style="text-align:center;">${homeShotsOn}</td><td style="text-align:center;">${awayShotsOn}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Šutevi van okvira</td><td style="text-align:center;">${homeShotsOff}</td><td style="text-align:center;">${awayShotsOff}</td></tr>
                        <tr><td style="padding:10px;">Korneri</td><td style="text-align:center;">${homeCorners}</td><td style="text-align:center;">${awayCorners}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Žuti kartoni</td><td style="text-align:center;color:#ff9800;">${homeYellows}</td><td style="text-align:center;color:#ff9800;">${awayYellows}</td></tr>
                        <tr><td style="padding:10px;">Crveni kartoni</td><td style="text-align:center;color:#f44336;">${homeReds}</td><td style="text-align:center;color:#f44336;">${awayReds}</td></tr>
                        <tr style="background:rgba(255,255,255,0.04);"><td style="padding:10px;">Penali</td><td style="text-align:center;">${homePenalties}</td><td style="text-align:center;">${awayPenalties}</td></tr>
                    </tbody>
                </table>`;

                infoDiv.innerHTML = html;
            }

            // Automatski prikaži statistiku odmah
            showStats();

            // Listener-i za ostala dugmad
            document.getElementById("view-stats").addEventListener("click", showStats);

            document.getElementById("view-goals").addEventListener("click", () => {
                const goals = events.filter(e => e.eventType === "GoalEvent");
                if (goals.length === 0) {
                    infoDiv.innerHTML = `<p style="color:#aaa; text-align:center; padding:30px;">Nema postignutih golova na ovom meču.</p>`;
                    return;
                }

                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Golovi</h3><ul style="list-style:none; padding:0;">`;

                goals.forEach(g => {
                    const assist = g.assistant ? ` <span style="color:#888;">(asist: ${g.assistant})</span>` : '';
                    html += `
                    <li style="padding:12px; margin:8px 0; background:rgba(255,255,255,0.05); border-radius:8px;">
                        <strong>${g.matchMinute}'</strong> ⚽ ${g.scorer || "?"} ${assist}
                        <span style="float:right; color:#aaa;">${g.scoreAfterGoal || ""}</span>
                    </li>`;
                });

                html += `</ul>`;
                infoDiv.innerHTML = html;
            });

            document.getElementById("view-events").addEventListener("click", () => {
                // ... tvoj postojeći kod za prikaz svih eventa (možeš ga ostaviti isti ili malo očistiti) ...
                // Primer minimalne verzije:
                let html = `<h3 style="text-align:center; margin:0 0 20px; color:#4CAF50;">Svi događaji</h3>`;
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
            console.error("Greška pri učitavanju meča:", err);
            mainContent.innerHTML = `<div class="team-card"><p>Greška pri učitavanju meča: ${err.message}</p></div>`;
        }
    }
    async function loadFirstTeam() {
            const response = await fetch("/teams/1/players");
            const players = await response.json();
            renderPlayers(players, "First Team");
        }
    async function loadResults() {
        const response = await fetch("/teams/1/matches");
        const matches = await response.json()
        const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
        renderMatches(results, "Results");
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
        const response = await fetch("/demo/matches/teams/1/upcoming");
        const matches = await response.json();
        renderMatches(matches, "Upcoming Matches");
    }
    async function loadFixtures() {
        const response = await fetch("/demo/matches/teams/1/fixtures");
        const fixtures = await response.json();
        renderFixtures(fixtures, "Fixtures");
    }
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
                    🗓 ${fixture.matchDate || "N/A"} • ${fixture.matchTime || ""}
                </div>
                <span class="team-home">${fixture.homeTeam}</span>
                <span class="score">VS</span>
                <span class="team-away">${fixture.awayTeam}</span>
                <div style="font-size:0.85em; color:#888; margin-top:6px;">
                    🏟️ ${fixture.stadiumName || "N/A"}
                </div>
            </div>`;
        });

        html += `</div></div>`;
        mainContent.innerHTML = html;
    }
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
        const leagueId = 1; // Superliga – kasnije možeš proslediti iz URL-a ili dropdown-a

        try {
            const response = await fetch(`/countries/leagues/${leagueId}/table`);
            if (!response.ok) {
                throw new Error(`Greška: ${response.status}`);
            }
            const table = await response.json();
            renderTable(table);
        } catch (err) {
            console.error("Greška pri učitavanju tabele lige:", err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back</button>
                    <h2>Greška</h2>
                    <p>Ne mogu da učitam tabelu lige. Proveri konzolu.</p>
                </div>`;
        }
    }
    async function loadLeagueMatches() {
        const leagueId = 1; // Superliga – kasnije možeš proslediti parametar
        try {
            const response = await fetch(`/countries/leagues/${leagueId}/matches`);
            if (!response.ok) throw new Error("Greška pri učitavanju mečeva lige");
            const matches = await response.json();
            const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
            renderLeagueMatches(results);
        } catch (err) {
            console.error(err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button onclick="loadDashboard()">⬅ Back</button>
                    <h2>Greška</h2>
                    <p>Ne mogu da učitam mečeve lige.</p>
                </div>`;
        }
    }
    function renderLeagueMatches(matches) {
        const mainContent = document.getElementById("main-content");

        let html = `
        <div class="manager-card">
            <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
            <h2>Superliga Matches</h2>
            <div class="match-list">`;

        if (!Array.isArray(matches) || matches.length === 0) {
            html += `<p style="text-align:center; color:#aaa;">Još nema mečeva u ovoj ligi.</p>`;
        } else {
            matches.forEach(match => {  // ← match, ne m!
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
                <div class="match-row"
                     data-match-id="${match.id}"
                     data-caller="leagueMatches">
                    <div style="font-size:0.9em; color:#aaa;">${match.matchDate || "N/A"}</div>
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
        }

        html += `</div></div>`;
        mainContent.innerHTML = html;

        // Event delegation – radi za sve .match-row u #main-content
        document.getElementById("main-content").addEventListener('click', function(e) {
            const row = e.target.closest('.match-row');
            if (row) {
                const matchId = row.dataset.matchId;
                const caller  = row.dataset.caller || 'leagueMatches';
                if (matchId) {
                    loadMatch(matchId, caller);
                }
            }
        });
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
    async function loadTopScorersAndAssists() {
        try {
            const scorersRes = await fetch('/stats/leagues/1/topscorers');
            const scorers = await scorersRes.json();

            const assistsRes = await fetch('/stats/leagues/1/topassists');
            const assists = await assistsRes.json();

            const mainContent = document.getElementById("main-content");

            let html = `
            <div class="manager-card" style="padding: 25px;">
                <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
                <h2 style="text-align: center; margin: 20px 0 30px; color: #e94560;">Statistika lige – Top liste</h2>

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
            console.error("Greška pri učitavanju top lista:", err);
            document.getElementById("main-content").innerHTML = `
                <div class="manager-card">
                    <button onclick="loadDashboard()">⬅ Back</button>
                    <h2>Greška</h2>
                    <p>Ne mogu da učitam top liste. Proveri konekciju ili backend.</p>
                </div>`;
        }
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
            const filename = getImageFilename(player.name);  // ← DODAJ OVO

            html += `
            <div class="manager-player-card" onclick="loadPlayer(${player.id})">
                <img src="/images/${filename}.jpg"
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

        if (!Array.isArray(matches) || matches.length === 0) {
            html += `<p style="text-align:center; color:#aaa;">Nema mečeva za prikaz.</p>`;
        } else {
            matches.forEach(match => {  // ← OVDE JE match, NE m !
                html += `
                <div class="match-row"
                     data-match-id="${match.id}"
                     data-caller="match">
                    <div style="font-size:0.9em; color:#aaa; margin-bottom:4px;">
                        🗓 ${match.matchDate || "N/A"}
                    </div>
                    <div class="match-teams">
                        <span class="team-home">${match.homeTeam}</span>
                        <span class="score">
                            ${match.homeGoals ?? "-"} : ${match.awayGoals ?? "-"}
                        </span>
                        <span class="team-away">${match.awayTeam}</span>
                    </div>
                </div>`;
            });
        }

        html += `</div></div>`;
        mainContent.innerHTML = html;

        // Event delegation – radi i posle pre-rendera stranice
        document.getElementById("main-content").addEventListener('click', function(e) {
            const row = e.target.closest('.match-row');
            if (row) {
                const matchId = row.dataset.matchId;
                const caller  = row.dataset.caller || 'match';
                if (matchId) {
                    loadMatch(matchId, caller);
                }
            }
        });
    }
    function renderTable(table) {
        const mainContent = document.getElementById("main-content");

        let html = `
        <div class="manager-card" style="padding: 25px;">
            <button class="back-to-dashboard" onclick="loadDashboard()">⬅ Back to Dashboard</button>
            <h2 style="text-align: center; margin: 20px 0 30px; color: #e94560;">Superliga – Tabela</h2>

            <div style="overflow-x: auto;">
                <table class="league-table" style="width: 100%; border-collapse: collapse; font-size: 0.95rem;">
                    <thead>
                        <tr style="background: rgba(157, 78, 221, 0.25); color: #fff;">
                            <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">#</th>
                            <th style="padding: 12px; text-align: left; border-bottom: 2px solid #555;">Tim</th>
                            <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">Pts</th>
                            <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">GS</th>
                            <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">GC</th>
                            <th style="padding: 12px; text-align: center; border-bottom: 2px solid #555;">GD</th>
                        </tr>
                    </thead>
                    <tbody>`;

        table.forEach((team, i) => {
            const rank = i + 1;
            let rankStyle = '';
            let rankIcon = '';

            if (rank === 1) {
                rankStyle = 'color: #ffd700; font-weight: bold;';
                rankIcon = '🏆 ';
            } else if (rank === 2) {
                rankStyle = 'color: #c0c0c0; font-weight: bold;';
                rankIcon = '🥈 ';
            } else if (rank === 3) {
                rankStyle = 'color: #cd7f32; font-weight: bold;';
                rankIcon = '🥉 ';
            } else {
                rankStyle = 'color: #aaa;';
            }

            const rowBg = i % 2 === 0 ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.08)';
            const gdColor = team.goalDifference > 0 ? '#4caf50' : team.goalDifference < 0 ? '#f44336' : '#aaa';

            html += `
                <tr style="background: ${rowBg}; transition: all 0.2s;">
                    <td style="padding: 12px; text-align: center; ${rankStyle}">${rankIcon}${rank}</td>
                    <td style="padding: 12px; font-weight: 600;">${team.name}</td>
                    <td style="padding: 12px; text-align: center; font-weight: bold; color: #ffd700;">${team.points}</td>
                    <td style="padding: 12px; text-align: center;">${team.goalsScored}</td>
                    <td style="padding: 12px; text-align: center;">${team.goalsConceded}</td>
                    <td style="padding: 12px; text-align: center; color: ${gdColor}; font-weight: bold;">
                        ${team.goalDifference > 0 ? '+' : ''}${team.goalDifference}
                    </td>
                </tr>`;
        });

        html += `
                    </tbody>
                </table>
            </div>

            <p style="text-align: center; color: #888; margin-top: 20px; font-size: 0.9rem;">
                Poslednje ažuriranje: ${new Date().toLocaleString('sr-RS')}
            </p>
        </div>`;

        mainContent.innerHTML = html;

        // Hover efekat na redovima
        document.querySelectorAll('.league-table tr').forEach(row => {
            if (!row.querySelector('th')) { // preskoči header
                row.addEventListener('mouseenter', () => {
                    row.style.background = 'rgba(157, 78, 221, 0.15) !important';
                    row.style.transform = 'scale(1.01)';
                });
                row.addEventListener('mouseleave', () => {
                    row.style.background = row.style.background.includes('0.03') ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.08)';
                    row.style.transform = 'scale(1)';
                });
            }
        });
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