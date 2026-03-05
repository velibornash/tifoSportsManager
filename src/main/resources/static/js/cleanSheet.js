// cleanSheet.js
import { authFetch } from './auth.js';

let gameState = null;
let currentUserTeamId = null;
let csSessionClosed = false;

async function closeCsSession() {
    if (csSessionClosed) return;
    csSessionClosed = true;
    try {
        await authFetch('/api/cs/reset', { method: 'POST', keepalive: true });
    } catch (e) {
        console.warn('CS reset on exit failed:', e);
    }
}

function logMessage(msg, type = 'info') {
    const prefix = `[CleanSheet ${type.toUpperCase()}]`;
    console.log(`${prefix} ${msg}`);

    const logsDiv = document.getElementById('logs');
    if (logsDiv) {
        const entry = document.createElement('div');
        entry.style.margin = '4px 0';
        entry.style.color = type === 'error' ? '#ff6b6b' : type === 'success' ? '#4caf50' : '#aaa';
        entry.textContent = `${new Date().toLocaleTimeString()} - ${msg}`;
        logsDiv.appendChild(entry);
        logsDiv.scrollTop = logsDiv.scrollHeight;
    }

    if (gameState && gameState.inbox && (type === 'error' || type === 'match' || type === 'success')) {
        addInboxMessage(type, msg);
    }
}
document.addEventListener('DOMContentLoaded', async () => {
    logMessage("Clean Sheet page loaded – checking authentication...");

    const token = localStorage.getItem('token');
    if (!token) {
        logMessage("No token – redirecting to login", 'error');
        window.location.href = '/login.html';
        return;
    }

    try {
        logMessage("Checking user...");
        const res = await authFetch('/auth/me');
        const user = await res.json();
        currentUserTeamId = user.teamId;
        logMessage(`Logged in: ${user.username} | Team ID: ${currentUserTeamId}`, 'success');

        await initGame();
    } catch (err) {
        logMessage(`Authentication failed: ${err.message}`, 'error');
        localStorage.removeItem('token');
        window.location.href = '/login.html';
    }

    document.getElementById('startGameBtn')?.addEventListener('click', initGame);
    document.getElementById('nextMatchBtn')?.addEventListener('click', nextMatch);
    document.getElementById('nextMatchBtnMobile')?.addEventListener('click', nextMatch);
});

window.addEventListener('pagehide', () => {
    closeCsSession();
});
async function initGame() {
    logMessage("Starting new game...");

    gameState = {
        team: { name: "OFK Omladinac", budget: 1200000, reputation: 65, stadium: "Dunjareal" },
        inbox: [],
        currentRound: 1,
        leagueTable: [],
        players: [],
        juniors: [],
        tactics: { formation: "4-4-2", style: "balanced" },
        finances: { income: 0, expenses: 30000 },
        matches: []
    };

    await fetchInitialData();

    logMessage("Game initialized – showing Club Info", 'success');
    renderMain('clubInfo');
}
async function fetchInitialData() {
    logMessage("Loading initial data...");
    if (!currentUserTeamId) {
        logMessage("No team ID – cannot load data", 'error');
        return;
    }

    try {
        logMessage("Loading league table...");
        const tableRes = await authFetch('/countries/leagues/1/table');
        gameState.leagueTable = await tableRes.json();
        logMessage("Table loaded");

        logMessage("Loading players...");
        const playersRes = await authFetch(`/teams/${currentUserTeamId}/players`);
        gameState.players = await playersRes.json();
        logMessage("Players loaded");

        logMessage("Loading juniors...");
        const juniorsRes = await authFetch(`/demo/teams/${currentUserTeamId}/juniors`);
        gameState.juniors = await juniorsRes.json();
        logMessage("Juniors loaded");

        addInboxMessage("welcome", "Welcome to Clean Sheet Tifo! One season, one shot, one try, no cheating!");
    } catch (err) {
        logMessage(`Error loading data: ${err.message}`, 'error');
        addInboxMessage("error", `Cannot load data: ${err.message}. Using defaults.`);
    }
}
function addInboxMessage(type, text) {
    if (!gameState?.inbox) return;
    gameState.inbox.push({ type, text, time: new Date().toLocaleString() });
    logMessage(`Inbox: [${type}] ${text}`);

    if (document.querySelector('[data-page="inbox"]')) {
        renderInbox(document.getElementById("main-content"));
    }
}
function renderInbox(container) {
    let html = `<h2>Inbox (${gameState.inbox.length})</h2>`;
    html += gameState.inbox.map((msg, index) => `
        <div onclick="showMessage(${index})" style="cursor:pointer; background:rgba(255,255,255,0.05); padding:12px; margin:8px 0; border-radius:8px;">
            <strong>[${msg.type.toUpperCase()}]</strong> ${msg.text.substring(0, 50)}...
        </div>
    `).join('');
    container.innerHTML = html;
}
window.showMessage = function(index) {
    const msg = gameState.inbox[index];
    if (!msg) return;

    const modal = document.createElement('div');
    modal.style.position = 'fixed';
    modal.style.inset = '0';
    modal.style.background = 'rgba(0,0,0,0.75)';
    modal.style.display = 'flex';
    modal.style.alignItems = 'center';
    modal.style.justifyContent = 'center';
    modal.style.zIndex = '1000';
    modal.style.transition = 'opacity 0.2s ease';

    // Zatvaranje na klik van modala
    modal.addEventListener('click', (e) => {
        if (e.target === modal) {
            modal.remove();
        }
    });

    modal.innerHTML = `
        <div style="
            background: linear-gradient(135deg, #1e1e2f 0%, #2a2a3f 100%);
            padding: 32px;
            border-radius: 16px;
            max-width: 520px;
            width: 90%;
            color: #e0e0ff;
            box-shadow: 0 20px 60px rgba(0,0,0,0.6);
            border: 1px solid rgba(100,100,255,0.15);
            position: relative;
            animation: fadeIn 0.3s ease;
        ">
            <button onclick="this.closest('div').parentElement.remove()"
                style="
                    position: absolute;
                    top: 16px;
                    right: 16px;
                    background: rgba(255,255,255,0.1);
                    border: none;
                    color: #ff6b6b;
                    font-size: 24px;
                    width: 36px;
                    height: 36px;
                    border-radius: 50%;
                    cursor: pointer;
                    transition: all 0.2s;
                "
                onmouseover="this.style.background='rgba(255,107,107,0.2)'"
                onmouseout="this.style.background='rgba(255,255,255,0.1)'"
            >×</button>

            <h2 style="
                margin: 0 0 20px;
                color: ${msg.type === 'error' ? '#ff6b6b' : '#4caf50'};
                font-size: 1.6em;
                text-align: center;
            ">
                ${msg.type.toUpperCase()}
            </h2>

            <p style="
                white-space: pre-wrap;
                line-height: 1.6;
                margin: 0 0 24px;
                font-size: 1.05em;
            ">
                ${msg.text}
            </p>

            <small style="
                display: block;
                text-align: right;
                color: #aaa;
                font-size: 0.9em;
            ">
                ${msg.time}
            </small>

            <div style="text-align: center; margin-top: 28px;">
                <button onclick="this.closest('div').parentElement.parentElement.remove()"
                    style="
                        padding: 12px 32px;
                        background: linear-gradient(90deg, #4caf50, #66bb6a);
                        border: none;
                        border-radius: 8px;
                        color: white;
                        font-weight: bold;
                        font-size: 1.1em;
                        cursor: pointer;
                        transition: all 0.2s;
                    "
                    onmouseover="this.style.transform='scale(1.05)'"
                    onmouseout="this.style.transform='scale(1)'"
                >
                    Close
                </button>
            </div>
        </div>
    `;

    document.body.appendChild(modal);

    // Mali fade-in efekat
    setTimeout(() => {
        modal.style.opacity = '1';
    }, 10);
};
function renderMain(page) {
    const main = document.getElementById("main-content");
    main.innerHTML = "";

    const card = document.createElement("div");
    card.className = "manager-card";
    main.appendChild(card);

    logMessage(`Showing page: ${page}`);

    switch (page) {
        case 'inbox':
            renderInbox(card);
            break;
        case 'players':
            renderPlayers?.(gameState.players, "First Team", card) || (card.innerHTML = "<p>Loading players...</p>");
            break;
        case 'juniors':
            renderPlayers?.(gameState.juniors, "Juniors", card) || (card.innerHTML = "<p>Loading juniors...</p>");
            break;
        case 'tactics':
            renderTactics(card);
            break;
        case 'leagueTable':
            renderTable?.(gameState.leagueTable, card) || (card.innerHTML = "<p>Loading table...</p>");
            break;
        case 'transfers':
            renderTransfers(card);
            break;
        case 'clubInfo':
            renderClubInfo(card);
            break;
        case 'matches':
            renderMatches?.(gameState.matches || [], "Matches", card) || (card.innerHTML = "<p>Loading matches...</p>");
            break;
        default:
            card.innerHTML = "<h2>Select a category</h2>";
    }
}
function renderClubInfo(container) {
    container.innerHTML = `
        <h2>Club Info</h2>
        <div class="club-stats-grid">
            <div class="stat-card">
                <div class="stat-icon">🏟️</div>
                <div class="stat-value">${gameState.team.name}</div>
                <div class="stat-label">Team</div>
            </div>
            <div class="stat-card">
                <div class="stat-icon">💰</div>
                <div class="stat-value">€${gameState.team.budget.toLocaleString()}</div>
                <div class="stat-label">Budget</div>
            </div>
            <div class="stat-card">
                <div class="stat-icon">⭐</div>
                <div class="stat-value">${gameState.team.reputation}</div>
                <div class="stat-label">Reputation</div>
            </div>
            <div class="stat-card">
                <div class="stat-icon">🏟️</div>
                <div class="stat-value">${gameState.team.stadium}</div>
                <div class="stat-label">Stadium</div>
            </div>
        </div>
    `;
}
function renderTactics(container) {
    container.innerHTML = `
        <h2>Tactics</h2>
        <p>Current Formation: <strong>${gameState.tactics.formation}</strong></p>
        <p>Style: <strong>${gameState.tactics.style}</strong></p>
        <button class="big-button" style="margin-top:15px;">Change Tactics (coming soon)</button>
    `;
}
function renderTransfers(container) {
    container.innerHTML = `
        <h2>Transfers & Budget</h2>
        <p>Current Budget: <strong>€${gameState.team.budget.toLocaleString()}</strong></p>
        <p>Monthly Expenses: <strong>€${gameState.finances.expenses.toLocaleString()}</strong></p>
        <button class="big-button" style="margin-top:15px;">Browse Market (coming soon)</button>
    `;
}
function getImageFilename(name) {
    return name
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .replace(/đ/g, "dj")
        .replace(/Đ/g, "Dj")
        .replace(/\s+/g, '_')
        .replace(/[^a-zA-Z0-9_-]/g, '');
}
async function nextMatch() {
    logMessage("Next Match clicked – starting simulation...");

    const nextBtns = [document.getElementById('nextMatchBtn'), document.getElementById('nextMatchBtnMobile')];
    nextBtns.forEach(btn => {
        if (btn) {
            btn.dataset.originalText = btn.innerHTML;
            btn.innerHTML = 'Simulating...';
            btn.disabled = true;
        }
    });

    try {
        logMessage(`Simulating match: Home ID ${currentUserTeamId} vs Away ID 2`);
        const res = await authFetch(`/api/clean-sheet/simulate-single?homeId=${currentUserTeamId}&awayId=2`, {
            method: 'POST'
        });

        if (!res.ok) {
            const errorText = await res.text();
            throw new Error(`HTTP ${res.status}: ${errorText.substring(0, 200)}...`);
        }

        const result = await res.json();
        logMessage(`Simulation success: ${result.summary}`, 'success');

        addInboxMessage("match", result.summary);

        gameState.matches = gameState.matches || [];
        gameState.matches.push(result);

        gameState.currentRound++;

        renderMain('matches');
    } catch (err) {
        logMessage(`Simulation failed: ${err.message}`, 'error');
        addInboxMessage("error", `Failed to simulate match: ${err.message}`);
    } finally {
        nextBtns.forEach(btn => {
            if (btn) {
                btn.innerHTML = btn.dataset.originalText || 'Next Match →';
                btn.disabled = false;
            }
        });
    }
}
function loadCleanPage(page) {
    logMessage(`Loading page: ${page}`);
    renderMain(page);
    closeMobileMenu();
}
async function loadCSPlayer(playerId) {
    const mainContent = document.getElementById("main-content");
    console.log(`Ucitavam load player za tim ${currentUserTeamId} i igraca ${playerId}`);
    const response = await authFetch(`/teams/${currentUserTeamId}/players/${playerId}`);
    console.log(`Status odgovora: ${response.status}`);
    if(!response.ok) {
        mainContent.innerHTML = `<div class="team-card"><p>Player not found.</p><button onclick="loadCleanPage('players')">⬅ Back</button></div>`;
        return;
    }

    const player = await response.json();
    const filename = getImageFilename(player.name);
    mainContent.innerHTML = `

            <div class="player-card-wrapper">
                <div class="player-card">
                <button class="back-to-dashboard" onclick="loadCleanPage('players')">⬅ Back to Team</button>
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
async function loadCSMatch(matchId, caller) {
    const mainContent = document.getElementById("main-content");
    console.log(`Učitavam meč ID: ${matchId}, caller: ${caller}`);
    if(caller==="undefined"){
       console.log(`Meč nije pronađen.`);
       mainContent.innerHTML = `<div class="team-card"><p>Meč nije pronađen.</p></div>`;
       return;
    }
    try {
        const response = await authFetch(`/matches/${matchId}/detail`);
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
function renderPlayers(players, title) {
    const mainContent = document.getElementById("main-content");

    if (!Array.isArray(players)) {
        mainContent.innerHTML = buildEmptyState("No players found");
        return;
    }

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadCleanPage('inbox')">⬅ Back to Inbox</button>
        <h2>${title}</h2>
        <div class="manager-grid">`;

    players.forEach(player => {
        const filename = getImageFilename(player.name);

        html += `
        <div class="manager-player-card" onclick="loadCSPlayer(${player.id})">
            <img src="/images/${filename}.jpg"
                 onerror="this.src='/images/player.jpg'">
            <div class="player-name">${player.name}</div>
            <div class="player-meta">${player.position} • ${player.age}</div>
            <div class="player-rating">OVR ${player.overall}</div>
        </div>`;
    });

    html += `</div></div>`;
    mainContent.innerHTML = html;
    // Dodaj klik za otvaranje igrača
        mainContent.querySelectorAll('.player-card').forEach(card => {
            card.addEventListener('click', () => {
                loadCSPlayer(card.dataset.playerId);
            });
        });
}
function renderMatches(matches, title) {
    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card">
        <button class="back-to-dashboard" onclick="loadCleanPage('inbox')">⬅ Back to Inbox</button>
        <h2>${title}</h2>
        <div class="match-list">`;

    if (!Array.isArray(matches) || matches.length === 0) {
        html += `<p style="text-align:center; color:#aaa;">Nema mečeva za prikaz.</p>`;
    } else {
        matches.forEach(match => {
            let formattedDate = "N/A";
            if (match.match?.matchDate) {
                const matchDate = parseMatchDate(match.match.matchDate);
                formattedDate = matchDate.toLocaleString('sr-RS', {
                    weekday: 'short',
                    year: 'numeric',
                    month: 'short',
                    day: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit'
                });
            }

            html += `
            <div class="match-row"
                 data-match-id="${match.id || ''}"
                 data-caller="match">
                <div style="font-size:0.9em; color:#aaa; margin-bottom:4px;">
                    🗓 ${formattedDate}
                </div>
                <div class="match-teams">
                    <span class="team-home">${match.match?.homeTeam?.name || "Home"}</span>
                    <span class="score">
                        ${match.homeGoals ?? "-"} : ${match.awayGoals ?? "-"}
                    </span>
                    <span class="team-away">${match.match?.awayTeam?.name || "Away"}</span>
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
                loadCSMatch(matchId, caller);
            }
        }
    });
}
function renderTable(table) {
    const mainContent = document.getElementById("main-content");

    let html = `
    <div class="manager-card" style="padding: 25px;">
        <button class="back-to-dashboard" onclick="loadCleanPage('inbox')">⬅ Back to Inbox</button>
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
function renderFixtures(fixtures, title) {
        const mainContent = document.getElementById("main-content");

        let html = `
        <div class="manager-card">
            <button class="back-to-dashboard" onclick="loadCleanPage('inbox')">⬅ Back to Inbox</button>
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
function renderLeagueMatches(matches) {
            const mainContent = document.getElementById("main-content");

            let html = `
            <div class="manager-card">
                <button class="back-to-dashboard" onclick="loadCleanPage('inbox')">⬅ Back to Inbox</button>
                <h2>Superliga Matches</h2>
                <div class="match-list">`;

            if (!Array.isArray(matches) || matches.length === 0) {
                html += `<p style="text-align:center; color:#aaa;">Još nema mečeva u ovoj ligi.</p>`;
            } else {
                matches.forEach(match => {
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
                            <span class="team-home">${match.match?.homeTeam?.name || "Home"}</span>
                            <span class="score">
                                ${match.homeGoals ?? "-"} : ${match.awayGoals ?? "-"}
                            </span>
                            <span class="team-away">${match.match?.awayTeam?.name || "Away"}</span>
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
                        loadCSMatch(matchId, caller);
                    }
                }
            });
        }
function renderMatch(result) {
    const container = document.getElementById("main-content");
    container.innerHTML = "";

    const card = document.createElement("div");
    card.className = "team-card";
    container.appendChild(card);

    const home = result.match?.homeTeam?.name || "Home";
    const away = result.match?.awayTeam?.name || "Away";
    const homeG = result.homeGoals;
    const awayG = result.awayGoals;

    card.innerHTML = `
        <h2>Match Result</h2>
        <div style="font-size:2em; text-align:center;">
            ${home} ${homeG} - ${awayG} ${away}
        </div>
        <h3>Events</h3>
        <ul>
            ${result.events.map(e => `<li>${e.minute}' ${e.eventType} - ${e.description || 'No desc'}</li>`).join('')}
        </ul>
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

window.loadCleanPage = loadCleanPage;
window.loadCSPlayer = loadCSPlayer;
window.loadCSMatch = loadCSMatch;
