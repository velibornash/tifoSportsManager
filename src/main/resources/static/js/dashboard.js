// dashboard.js
import { authFetch } from './auth.js';

let currentUserTeamId = null;

window.addEventListener('load', async () => {
    const token = localStorage.getItem('token');
    if (!token) {
        console.warn('No token on load - redirecting');
        window.location.href = '/login.html';
        return;
    }

    try {
        const res = await authFetch('/auth/me');
        const user = await res.json();

        currentUserTeamId = user.teamId;
        console.log('Authenticated user:', user.username, 'Team ID:', currentUserTeamId);

        loadDashboard();
    } catch (err) {
        console.error('Error loading /auth/me:', err);
        localStorage.removeItem('token');
        window.location.href = '/login.html';
    }
});

function loadDashboard() {
    if (!currentUserTeamId) {
        console.warn('Team ID not loaded yet - waiting for /auth/me');
        return;
    }

    const mainContent = document.getElementById('main-content');
    mainContent.innerHTML = `
    <div class="team-card">
        <div class="team-header">
            <img src="/images/omladinac.png" class="team-logo">
            <div class="team-name-wrapper">
                <h1>OFK Omladinac</h1>
                <p class="team-subtitle"><span class="cs-clickable" onclick="loadLeagueTable()">Serbian Superliga</span> - Season 2025/26</p>
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
                15.03.2026 - 17:00<br>
                Stadion Livadice
            </div>
        </div>

        <div class="recent-matches-section">
            <h3>Recent Matches</h3>
            <div id="recent-matches-list" class="match-list">
                <div class="loading">Loading recent matches...</div>
            </div>
        </div>

        <div class="recent-matches-section">
            <h3>Recent League Results</h3>
            <div id="recent-league-matches-list" class="match-list">
                <div class="loading">Loading recent league matches...</div>
            </div>
        </div>

        <div class="dashboard-actions">
            <button id="start-demo-btn" onclick="startDemoTest()" disabled style="opacity:0.6; cursor:not-allowed;">Start Full match (SOON)</button>
            <button id="start-key-events-btn" onclick="startKeyEventsTest()" style="background:#135f3d;">Simulate Key Events</button>
        </div>
    </div>`;

    loadRecentMatches();
    loadRecentLeagueMatches();
    loadHomeTeamStats();
}

async function resetDatabase() {
    const confirmReset = confirm('This will delete the entire database. Continue?');
    if (!confirmReset) return;

    try {
        const response = await authFetch('/admin/reset-db', { method: 'POST' });

        const message = await response.text();
        alert(message);
        window.location.reload();
    } catch (err) {
        console.error('Reset error:', err);
        alert('Database reset failed.');
    }
}

async function initializeDatabase() {
    const confirmInit = confirm('Initialize database now? This may take a few seconds.');
    if (!confirmInit) return;

    const loadingPopup = document.createElement('div');
    loadingPopup.id = 'loading-popup';
    loadingPopup.style.position = 'fixed';
    loadingPopup.style.top = '0';
    loadingPopup.style.left = '0';
    loadingPopup.style.width = '100%';
    loadingPopup.style.height = '100%';
    loadingPopup.style.background = 'rgba(0,0,0,0.6)';
    loadingPopup.style.display = 'flex';
    loadingPopup.style.alignItems = 'center';
    loadingPopup.style.justifyContent = 'center';
    loadingPopup.style.zIndex = '9999';

    loadingPopup.innerHTML = `
        <div style="
            background: white;
            padding: 30px 50px;
            border-radius: 12px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.4);
            text-align: center;
            font-family: Arial, sans-serif;
        ">
            <h2 style="margin: 0 0 15px 0; color: #2c3e50;">Database initialization in progress...</h2>
            <div style="font-size: 1.1em; color: #7f8c8d;">Please wait and keep this page open.</div>
            <div style="margin-top: 20px; font-size: 2em;">...</div>
        </div>
    `;

    document.body.appendChild(loadingPopup);

    try {
        const response = await authFetch('/admin/initialize-db', { method: 'POST' });

        if (document.body.contains(loadingPopup)) {
            document.body.removeChild(loadingPopup);
        }

        const message = await response.text();
        alert(`${message}\n\nThe page will reload now.`);
        window.location.reload();
    } catch (err) {
        if (document.body.contains(loadingPopup)) {
            document.body.removeChild(loadingPopup);
        }

        console.error('DB init error:', err);
        alert(`Database initialization failed.\n\nError: ${err.message}`);
    }
}

async function loadRecentMatches() {
    try {
        const response = await fetch(`/teams/${currentUserTeamId}/matches`);
        if (!response.ok) throw new Error('Failed to load matches');

        const matches = await response.json();
        const recent = matches
            .sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate))
            .slice(0, 3);

        const list = document.getElementById('recent-matches-list');
        if (!list) return;

        if (recent.length === 0) {
            list.innerHTML = `
                <div class="empty-badge-wrap">
                    <span class="empty-badge">No played matches yet</span>
                </div>
                <p style="text-align:center; color:#aaa;">Play a match to populate this section.</p>`;
            return;
        }

        let html = '';
        recent.forEach(match => {
            const isWin = match.homeGoals > match.awayGoals ? 'win' : '';
            const isDraw = match.homeGoals === match.awayGoals ? 'draw' : '';
            html += `
            <div class="match-row recent-match" onclick="loadMatch(${match.id}, 'match')">
                <div class="match-date-small">${match.matchDate || 'N/A'}</div>
                <div class="match-teams">
                    <span class="team-home">${match.homeTeam}</span>
                    <span class="score">${match.homeGoals ?? '-'} : ${match.awayGoals ?? '-'}</span>
                    <span class="result-badge ${isWin ? 'win' : isDraw ? 'draw' : 'loss'}">${isWin ? 'W' : isDraw ? 'D' : 'L'}</span>
                    <span class="team-away">${match.awayTeam}</span>
                </div>
            </div>`;
        });

        list.innerHTML = html;
    } catch (err) {
        console.error('Error loading recent matches:', err);
        document.getElementById('recent-matches-list').innerHTML =
            '<p style="text-align:center; color:#f44336;">Failed to load recent matches.</p>';
    }
}

async function loadRecentLeagueMatches() {
    const list = document.getElementById('recent-league-matches-list');
    const renderEmpty = () => {
        if (!list) return;
        list.innerHTML = `
            <div class="empty-badge-wrap">
                <span class="empty-badge">No league results yet</span>
            </div>
            <p style="text-align:center; color:#aaa;">Simulate a round to populate this section.</p>`;
    };

    try {
        const leagueId = 1;
        const response = await fetch(`/countries/leagues/${leagueId}/matches`);
        if (!response.ok) {
            renderEmpty();
            return;
        }

        const matches = await response.json();
        const recent = matches
            .sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate))
            .slice(0, 5);

        if (!list) return;

        if (recent.length === 0) {
            renderEmpty();
            return;
        }

        let html = '';
        recent.forEach(match => {
            let badgeClass = '';
            let badgeText = '';
            if (match.homeGoals !== null && match.awayGoals !== null) {
                if (match.homeGoals > match.awayGoals) {
                    badgeClass = 'win';
                    badgeText = '1';
                } else if (match.homeGoals < match.awayGoals) {
                    badgeClass = 'loss';
                    badgeText = '2';
                } else {
                    badgeClass = 'draw';
                    badgeText = 'X';
                }
            }

            html += `
            <div class="match-row recent-match" onclick="loadMatch(${match.id}, 'leagueMatches')">
                <div class="match-date-small">${match.matchDate || 'N/A'}</div>
                <div class="match-teams">
                    <span class="team-home">${match.homeTeam}</span>
                    <span class="score">${match.homeGoals ?? '-'} : ${match.awayGoals ?? '-'}</span>
                    <span class="team-away">${match.awayTeam}</span>
                </div>
                ${badgeText ? `<span class="result-badge ${badgeClass}">${badgeText}</span>` : ''}
            </div>`;
        });

        list.innerHTML = html;
    } catch (err) {
        console.error('Error loading recent league matches:', err);
        renderEmpty();
    }
}

async function loadHomeTeamStats() {
    try {
        const leagueId = 1;
        const response = await fetch(`/countries/leagues/${leagueId}/table`);
        if (!response.ok) throw new Error('Failed to load league table');

        const table = await response.json();

        const currentName = document.querySelector('.team-name-wrapper h1')?.textContent?.trim() || 'OFK Omladinac';
        const entry = table.find(t => t.name === currentName) || table.find(t => t.name === 'OFK Omladinac');
        if (!entry) return;

        document.querySelector('.team-name-wrapper h1').textContent = entry.name;
        document.querySelector('.team-subtitle').textContent = 'Serbian Superliga - Season 2025/26';

        const statValues = document.querySelectorAll('.stat-value');
        statValues[0].textContent = entry.position || '?';
        statValues[1].textContent = entry.points || '0';
        statValues[2].textContent = `${entry.wins || 0}-${entry.draws || 0}-${entry.losses || 0}`;
        statValues[3].textContent = entry.goalDifference || '0';
    } catch (err) {
        console.error('Error loading team stats:', err);
    }
}

window.toggleSidebar = function(id) {
    const sidebars = document.querySelectorAll('.sidebar');
    sidebars.forEach(sb => {
        if (sb.id === id) {
            sb.classList.toggle('active');
        } else {
            sb.classList.remove('active');
        }
    });
};

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

window.loadDashboard = loadDashboard;
window.resetDatabase = resetDatabase;
window.initializeDatabase = initializeDatabase;
window.loadRecentMatches = loadRecentMatches;
window.loadRecentLeagueMatches = loadRecentLeagueMatches;
window.loadHomeTeamStats = loadHomeTeamStats;
window.toggleMobileMenu = toggleMobileMenu;
window.closeMobileMenu = closeMobileMenu;


