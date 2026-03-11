// dashboard.js
import { authFetch } from './auth.js';

let currentUserTeamId = null;
let currentUserTeamName = null;
let currentUserCompetitionId = null;
let currentUserCompetitionName = null;
let currentSeasonYear = null;

function getCurrentTeamImagePath() {
    return currentUserTeamName === 'OFK Omladinac' ? '/images/omladinac.png' : '/images/default-team.png';
}

function getCurrentLeagueId() {
    const leagueId = Number(currentUserCompetitionId);
    return Number.isFinite(leagueId) && leagueId > 0 ? leagueId : 1;
}

function getCurrentLeagueName() {
    return currentUserCompetitionName || 'League';
}

function formatSeasonLabel(seasonYear) {
    const startYear = Number(seasonYear);
    if (!Number.isFinite(startYear)) return 'Current season';
    return `${startYear}/${String((startYear + 1) % 100).padStart(2, '0')}`;
}

function buildDashboardSubtitle() {
    const seasonLabel = currentSeasonYear ? `Season ${formatSeasonLabel(currentSeasonYear)}` : 'Current season';
    return `<span class="cs-clickable" onclick="loadLeagueTable()">${escapeHtml(getCurrentLeagueName())}</span> · ${escapeHtml(seasonLabel)}`;
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function parseDashboardDate(value) {
    if (!value || value === 'N/A') return null;
    const normalized = String(value).includes('T') ? String(value) : String(value).replace(' ', 'T');
    const parsed = new Date(normalized);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
}

function formatDashboardDate(value) {
    const parsed = parseDashboardDate(value);
    if (!parsed) return value || 'Date TBD';
    const date = parsed.toLocaleDateString('sr-RS');
    const time = parsed.toLocaleTimeString('sr-RS', { hour: '2-digit', minute: '2-digit' });
    return `${date} - ${time}`;
}

function renderNextMatchCard(bodyHtml) {
    const host = document.getElementById('next-match-card');
    if (!host) return null;
    host.innerHTML = `<h3>Next Match</h3>${bodyHtml}`;
    return host;
}

function renderNextMatchEmpty(message, meta) {
    renderNextMatchCard(`
        <div class="match-info">
            <div class="empty-badge-wrap"><span class="empty-badge">${escapeHtml(message)}</span></div>
        </div>
        <div class="match-date">${escapeHtml(meta || 'The next fixture will appear here once the schedule is ready.')}</div>`);
}

function buildHeadToHeadText(h2h) {
    if (!h2h) return escapeHtml('No head-to-head data yet.');
    const summary = escapeHtml(h2h.summary || 'No head-to-head data yet.');
    const lastMeeting = h2h.lastMeetingSummary ? `<br>${escapeHtml(h2h.lastMeetingSummary)}` : '';
    return `${summary}${lastMeeting}`;
}

function extractTeamId(team) {
    if (team === null || team === undefined) return null;
    if (typeof team === 'object') {
        return team.id ?? team.teamId ?? team._id ?? null;
    }
    return team;
}

function extractTeamName(team) {
    if (team === null || team === undefined) return null;
    if (typeof team === 'object') {
        return team.name ?? team.teamName ?? null;
    }
    return typeof team === 'string' ? team : null;
}

function isCurrentUserTeam(team) {
    const teamId = extractTeamId(team);
    if (teamId !== null && teamId !== undefined && currentUserTeamId !== null && currentUserTeamId !== undefined) {
        if (Number(teamId) === Number(currentUserTeamId)) {
            return true;
        }
    }

    const teamName = extractTeamName(team);
    if (teamName && currentUserTeamName) {
        return teamName.trim().toLowerCase() === currentUserTeamName.trim().toLowerCase();
    }

    return false;
}

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
        currentUserTeamName = user.teamName;
        currentUserCompetitionId = user.competitionId ?? null;
        currentUserCompetitionName = user.competitionName ?? null;
        currentSeasonYear = user.seasonYear ?? null;
        console.log('Authenticated user:', user.username, 'Team ID:', currentUserTeamId, 'Team Name:', currentUserTeamName, 'League:', currentUserCompetitionName || currentUserCompetitionId);

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

    const teamName = currentUserTeamName || 'Your Team';
    const teamImagePath = getCurrentTeamImagePath();

    const mainContent = document.getElementById('main-content');
    mainContent.innerHTML = `
    <div class="team-card fm-panel fm-dashboard-shell">
        <div class="team-header">
            <img src="${teamImagePath}" class="team-logo" onerror="this.src='/images/default-team.png'">
            <div class="team-name-wrapper">
                <div class="fm-eyebrow">Club overview</div>
                <h1>${teamName}</h1>
                <p class="team-subtitle">${buildDashboardSubtitle()}</p>
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

        <div class="next-match" id="next-match-card">
            <h3>Next Match</h3>
            <div class="match-info">
                <div class="loading">Loading next match...</div>
            </div>
            <div class="match-date">Preparing your live club schedule…</div>
        </div>

        <div class="recent-matches-section">
            <h3>Recent Matches</h3>
            <div id="recent-matches-list" class="match-list">
                <div class="loading">Loading recent matches...</div>
            </div>
        </div>

        <div class="recent-matches-section fm-milestone-board">
            <h3>Club Milestones</h3>
            <div id="dashboard-milestones" class="fm-milestone-grid">
                <div class="fm-milestone-card"><div class="fm-milestone-kicker">Season board</div><div class="fm-milestone-value">Loading...</div><div class="fm-milestone-meta">Collecting current season milestones for your club.</div></div>
            </div>
        </div>

<!--        <div class="recent-matches-section">
            <h3>Recent League Results</h3>
            <div id="recent-league-matches-list" class="match-list">
                <div class="loading">Loading recent league matches...</div>
            </div>
        </div>-->

        <div class="dashboard-actions">
<!--            <button id="start-demo-btn" onclick="startDemoTest()" disabled style="opacity:0.6; cursor:not-allowed;">Start Full match (SOON)</button>-->
            <button id="start-realistic-demo-btn" class="fm-action-btn fm-dashboard-cta" onclick="startRealisticDemoTest()">⚽ Realistic Match</button>
<!--            <button id="start-key-events-btn" onclick="startKeyEventsTest()" style="background:#135f3d;">Simulate Key Events</button>-->
        </div>
    </div>`;

    loadRecentMatches();
//    loadRecentLeagueMatches();
    loadHomeTeamStats();
    loadDashboardMilestones();
    loadNextMatch();
}

async function loadNextMatch() {
    try {
        const response = await authFetch(`/teams/${currentUserTeamId}/schedule`);
        if (!response.ok) throw new Error(`Failed to load schedule: ${response.status}`);

        const schedule = await response.json();
        const nextMatch = (Array.isArray(schedule) ? schedule : [])
            .filter(match => !match.played)
            .sort((a, b) => {
                const left = parseDashboardDate(a.matchDate)?.getTime() ?? Number.MAX_SAFE_INTEGER;
                const right = parseDashboardDate(b.matchDate)?.getTime() ?? Number.MAX_SAFE_INTEGER;
                return left - right;
            })[0];

        if (!nextMatch) {
            renderNextMatchEmpty('Schedule updating', 'Your next fixture will appear here as soon as the current season calendar is ready.');
            return;
        }

        const teamImagePath = getCurrentTeamImagePath();
        const clickableClass = nextMatch.fixtureId ? 'clickable' : '';
        const venueLabel = nextMatch.stadium || 'Venue TBD';
        const detailBits = [
            nextMatch.competitionName || 'Competition',
            nextMatch.round ? `Round ${nextMatch.round}` : null,
            nextMatch.isHome ? 'Home' : 'Away'
        ].filter(Boolean).join(' · ');
        const homeOvr = Number(nextMatch.homeTeamStrength);
        const awayOvr = Number(nextMatch.awayTeamStrength);
        const ovrLine = Number.isFinite(homeOvr) || Number.isFinite(awayOvr)
            ? `OVR ${Number.isFinite(homeOvr) ? Math.round(homeOvr) : '—'} · ${Number.isFinite(awayOvr) ? Math.round(awayOvr) : '—'}`
            : '';

        const host = renderNextMatchCard(`
            <div class="match-info ${clickableClass}" data-fixture-id="${escapeHtml(nextMatch.fixtureId ?? '')}">
                <div class="team-away-home">
                    <img src="${nextMatch.isHome ? teamImagePath : '/images/default-team.png'}" class="match-team-logo small" onerror="this.src='/images/default-team.png'">
                    <span>${escapeHtml(nextMatch.homeTeam || 'Home')}</span>
                </div>
                <span class="vs">VS</span>
                <div class="team-away-home" id="nextMatchHome">
                    <img src="${nextMatch.isHome ? '/images/default-team.png' : teamImagePath}" class="match-team-logo small" onerror="this.src='/images/default-team.png'">
                    <span>${escapeHtml(nextMatch.awayTeam || 'Away')}</span>
                </div>
            </div>
            <div class="match-date">
                ${escapeHtml(formatDashboardDate(nextMatch.matchDate))}<br>
                ${escapeHtml(venueLabel)}<br>
                ${escapeHtml(detailBits)}<br>
                ${ovrLine ? `${escapeHtml(ovrLine)}<br>` : ''}
                ${buildHeadToHeadText(nextMatch.h2h || {})}
            </div>`);

        const clickable = host?.querySelector('[data-fixture-id]');
        if (clickable && nextMatch.fixtureId) {
            clickable.addEventListener('click', () => {
                if (typeof window.loadFixture === 'function') {
                    window.loadFixture(Number(nextMatch.fixtureId));
                }
            });
        }
    } catch (err) {
        console.error('Error loading next match:', err);
        renderNextMatchEmpty('Schedule unavailable', 'We could not refresh your club schedule right now.');
    }
}

function formatMilestoneAttendance(value) {
    const numeric = Number(value || 0);
    return numeric > 0 ? numeric.toLocaleString() : '—';
}

function milestoneCard(title, value, meta, extraClass = '') {
    return `
        <article class="fm-milestone-card ${extraClass}">
            <div class="fm-milestone-kicker">${title}</div>
            <div class="fm-milestone-value">${value || '—'}</div>
            <div class="fm-milestone-meta">${meta || 'No milestone logged yet.'}</div>
        </article>`;
}

async function loadDashboardMilestones() {
    const host = document.getElementById('dashboard-milestones');
    if (!host) return;

    try {
        const response = await authFetch(`/teams/${currentUserTeamId}/milestones`);
        if (!response.ok) throw new Error(`Failed to load club milestones: ${response.status}`);

        const data = await response.json();
        const attendance = data?.attendance || {};
        host.innerHTML = [
            milestoneCard(
                'Top scorer',
                data?.topScorer?.playerName || '—',
                data?.topScorer?.playerName ? `${data.topScorer.teamName || 'No team'} · ${Number(data.topScorer.value || 0)} goals` : 'No goals filed yet.'
            ),
            milestoneCard(
                'Top assist',
                data?.topAssist?.playerName || '—',
                data?.topAssist?.playerName ? `${data.topAssist.teamName || 'No team'} · ${Number(data.topAssist.value || 0)} assists` : 'No assists filed yet.'
            ),
            milestoneCard(
                'Biggest win',
                data?.biggestWin?.summary || '—',
                data?.biggestWin?.context || 'Waiting for a standout result.'
            ),
            milestoneCard(
                'Heaviest loss',
                data?.biggestLoss?.summary || '—',
                data?.biggestLoss?.context || 'No heavy defeat registered yet.'
            ),
            milestoneCard(
                'Attendance',
                formatMilestoneAttendance(attendance.averageAttendance),
                attendance.averageAttendance
                    ? `High ${formatMilestoneAttendance(attendance.highestAttendance)} (${attendance.highestMatchLabel || '—'}) · Low ${formatMilestoneAttendance(attendance.lowestAttendance)} (${attendance.lowestMatchLabel || '—'}) · ${attendance.insight || ''}`
                    : (attendance.insight || 'Crowd data will appear once played fixtures start filing gates.'),
                'attendance'
            )
        ].join('');
    } catch (err) {
        console.error('Error loading club milestones:', err);
        host.innerHTML = milestoneCard('Club Milestones', 'Unavailable', 'Could not load the current season milestones for your club.');
    }
}

async function resetDatabase() {
    const confirmReset = confirm('This will delete the entire database. Continue?');
    if (!confirmReset) return;

    try {
        const response = await authFetch('/admin/reset-db', { method: 'POST' });

        const message = await response.text();
        localStorage.removeItem('token');
        alert(`${message}\n\nPlease log in again.`);
        window.location.href = '/login.html';
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
            background: linear-gradient(180deg, rgba(17, 23, 37, 0.98), rgba(11, 16, 26, 0.96));
            border: 1px solid rgba(143, 211, 255, 0.16);
            color: #eef4ff;
            padding: 30px 50px;
            border-radius: 18px;
            box-shadow: 0 22px 48px rgba(0,0,0,0.34);
            text-align: center;
            font-family: Arial, sans-serif;
        ">
            <div style="margin: 0 0 8px 0; color: #8fd3ff; font-size: 0.8rem; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase;">Database</div>
            <h2 style="margin: 0 0 15px 0; color: #eef4ff;">Database initialization in progress...</h2>
            <div style="font-size: 1.05em; color: #99a6bb;">Please wait and keep this page open.</div>
            <div style="margin-top: 20px; font-size: 2em; color: #8fd3ff;">...</div>
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
            const isHomeTeam = isCurrentUserTeam(match.homeTeam);
            const isAwayTeam = isCurrentUserTeam(match.awayTeam);

            let resultBadge = '';
            let badgeText = '';

            if (isHomeTeam || isAwayTeam) {
                const myTeamGoals = isHomeTeam ? match.homeGoals : match.awayGoals;
                const opponentGoals = isHomeTeam ? match.awayGoals : match.homeGoals;

                if (myTeamGoals > opponentGoals) {
                    resultBadge = 'win';
                } else if (myTeamGoals === opponentGoals) {
                    resultBadge = 'draw';
                } else {
                    resultBadge = 'loss';
                }

                badgeText = resultBadge === 'win' ? 'W' : resultBadge === 'draw' ? 'D' : 'L';
            }

            html += `
            <div class="match-row recent-match" onclick="loadMatch(${match.id}, 'match')">
                <div class="match-date-small">${match.matchDate || 'N/A'}</div>
                <div class="match-teams">
                    <span class="team-home">${match.homeTeam?.name || match.homeTeam}</span>
                    <span class="score">${match.homeGoals ?? '-'} : ${match.awayGoals ?? '-'}</span>
                    ${badgeText ? `<span class="result-badge ${resultBadge}">${badgeText}</span>` : ''}
                    <span class="team-away">${match.awayTeam?.name || match.awayTeam}</span>
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
        const leagueId = getCurrentLeagueId();
        const response = await authFetch(`/countries/leagues/${leagueId}/matches`);
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
        const leagueId = getCurrentLeagueId();
        const seasonParam = currentSeasonYear ? `?seasonYear=${currentSeasonYear}` : '';
        const response = await authFetch(`/countries/leagues/${leagueId}/table${seasonParam}`);
        if (!response.ok) throw new Error('Failed to load league table');

        const table = await response.json();

        const currentName = currentUserTeamName || document.querySelector('.team-name-wrapper h1')?.textContent?.trim() || 'Unknown';
        const entry = table.find(t => t.name === currentName);
        if (!entry) {
            console.warn('Team not found in league table:', currentName);
            return;
        }

        document.querySelector('.team-name-wrapper h1').textContent = entry.name;
        document.querySelector('.team-subtitle').innerHTML = buildDashboardSubtitle();

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

window.loadDashboard = loadDashboard;
window.resetDatabase = resetDatabase;
window.initializeDatabase = initializeDatabase;
window.loadRecentMatches = loadRecentMatches;
window.loadRecentLeagueMatches = loadRecentLeagueMatches;
window.loadHomeTeamStats = loadHomeTeamStats;
