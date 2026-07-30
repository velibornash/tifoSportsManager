import { authFetch } from './auth.js';

function getZoxContent() {
    return document.getElementById('zox-content');
}

async function getMatchId() {
    const params = new URLSearchParams(window.location.search);
    let matchId = params.get('matchId') || localStorage.getItem('lastMatchId');

    if (!matchId) {
        try {
            const userResponse = await authFetch('/auth/me');
            const user = await userResponse.json();
            if (user?.teamId) {
                const response = await authFetch(`/teams/${user.teamId}/matches`);
                const matches = await response.json();
                if (Array.isArray(matches) && matches.length > 0 && matches[0]?.id) {
                    matchId = String(matches[0].id);
                    localStorage.setItem('lastMatchId', matchId);
                }
            }
        } catch (error) {
            console.warn('Could not fetch latest match:', error);
        }
    }

    return matchId;
}

function safe(value, fallback = 'N/A') {
    return value == null || value === '' ? fallback : value;
}

function pct(value) {
    const num = Number(value || 0);
    return `${Math.round(num * 100)}%`;
}

function num(value, digits = 1) {
    const parsed = Number(value || 0);
    return Number.isFinite(parsed) ? parsed.toFixed(digits) : (0).toFixed(digits);
}

function renderPlayersGrid(players) {
    if (!Array.isArray(players) || players.length === 0) {
        return '<p class="zox-empty">No lineup data available.</p>';
    }

    return players.map(player => `
        <div class="player-item">
            <div class="player-number">${player.squadNumber || '?'}</div>
            <div class="player-info">
                <div class="player-name">${safe(player.name, 'Unknown')}</div>
                <div class="player-pos">${safe(player.position)}</div>
            </div>
            <div class="player-rating">${num(player.overallRating || 6.5)}</div>
        </div>
    `).join('');
}

function renderInsightList(items) {
    if (!Array.isArray(items) || items.length === 0) {
        return '<div class="zox-empty">No extra insight available.</div>';
    }
    return items.map(item => `
        <div class="zox-insight-item tone-${safe(item.tone, 'neutral')}">
            <span>${safe(item.label)}</span>
            <strong>${safe(item.value)}</strong>
        </div>
    `).join('');
}

function renderReasons(reasons) {
    if (!Array.isArray(reasons) || reasons.length === 0) {
        return '<div class="zox-empty">Prediction explanation unavailable.</div>';
    }
    return reasons.map(reason => `<li>${safe(reason)}</li>`).join('');
}

function renderAbsentees(absentees) {
    if (!Array.isArray(absentees) || absentees.length === 0) {
        return '<div class="zox-empty">No absences reported.</div>';
    }
    return absentees.map(item => `<div class="zox-chip">${safe(item)}</div>`).join('');
}

function renderTopPerformers(players) {
    if (!Array.isArray(players) || players.length === 0) {
        return '<div class="zox-empty">No standout performers recorded.</div>';
    }
    return players.map(player => `
        <div class="zox-performer-card">
            <div>
                <div class="zox-performer-name">${safe(player.playerName)}</div>
                <div class="zox-performer-meta">${safe(player.position)} · ${safe(player.summary, 'Match contribution logged')}</div>
            </div>
            <div class="zox-performer-rating">${num(player.rating10 || 6.0)}</div>
        </div>
    `).join('');
}

function renderTimeline(events) {
    if (!Array.isArray(events) || events.length === 0) {
        return '<div class="zox-empty">No key timeline events available.</div>';
    }
    return events.map(event => `
        <div class="zox-timeline-item">
            <div class="zox-timeline-minute">${safe(event.minute, 0)}'</div>
            <div class="zox-timeline-icon">${safe(event.icon, '•')}</div>
            <div class="zox-timeline-copy">
                <strong>${safe(event.title)}</strong>
                <div>${safe(event.teamName)} · ${safe(event.detail)}</div>
            </div>
        </div>
    `).join('');
}

function renderStatsTable(stats, preview) {
    return `
        <table class="zox-stats-table">
            <thead>
                <tr>
                    <th>Metric</th>
                    <th>${safe(preview.homeTeamName, 'Home')}</th>
                    <th>${safe(preview.awayTeamName, 'Away')}</th>
                </tr>
            </thead>
            <tbody>
                <tr><td>Possession</td><td>${num(stats.homePossession, 0)}%</td><td>${num(stats.awayPossession, 0)}%</td></tr>
                <tr><td>xG</td><td>${num(stats.homeExpectedGoals, 2)}</td><td>${num(stats.awayExpectedGoals, 2)}</td></tr>
                <tr><td>Shots on target</td><td>${safe(stats.homeShotsOnTarget, 0)}</td><td>${safe(stats.awayShotsOnTarget, 0)}</td></tr>
                <tr><td>Shots off target</td><td>${safe(stats.homeShotsOffTarget, 0)}</td><td>${safe(stats.awayShotsOffTarget, 0)}</td></tr>
                <tr><td>Pass accuracy</td><td>${num(stats.homePassAccuracy, 0)}%</td><td>${num(stats.awayPassAccuracy, 0)}%</td></tr>
                <tr><td>Corners</td><td>${safe(stats.homeCorners, 0)}</td><td>${safe(stats.awayCorners, 0)}</td></tr>
                <tr><td>Offsides</td><td>${safe(stats.homeOffsides, 0)}</td><td>${safe(stats.awayOffsides, 0)}</td></tr>
                <tr><td>Yellow cards</td><td>${safe(stats.homeYellowCards, 0)}</td><td>${safe(stats.awayYellowCards, 0)}</td></tr>
                <tr><td>Red cards</td><td>${safe(stats.homeRedCards, 0)}</td><td>${safe(stats.awayRedCards, 0)}</td></tr>
                <tr><td>Dominance</td><td>${num(stats.homeDominance, 1)}</td><td>${num(stats.awayDominance, 1)}</td></tr>
            </tbody>
        </table>
    `;
}

function renderDashboard(preview, stats, report) {
    const content = getZoxContent();
    if (!content) return;

    content.innerHTML = `
        <div class="match-overview">
            <div class="overview-card">
                <h3>Home Edge</h3>
                <div class="value">${safe(preview.homeTeamName)}</div>
                <div class="subtext">OVR ${safe(preview.homeTeamRating, 0)} · Form ${safe(preview.homeRecentForm, 'N/A')}</div>
            </div>
            <div class="overview-card">
                <h3>Prediction</h3>
                <div class="value">${safe(preview.expectedResult, 'DRAW').replaceAll('_', ' ')}</div>
                <div class="subtext">${Math.round((preview.homeWinProbability || 0) * 100)}% / ${Math.round((preview.drawProbability || 0) * 100)}% / ${Math.round((preview.awayWinProbability || 0) * 100)}%</div>
            </div>
            <div class="overview-card">
                <h3>Away Edge</h3>
                <div class="value">${safe(preview.awayTeamName)}</div>
                <div class="subtext">OVR ${safe(preview.awayTeamRating, 0)} · Form ${safe(preview.awayRecentForm, 'N/A')}</div>
            </div>
        </div>

        <div class="team-comparison">
            <div class="team-comparison-header">
                <div class="team-card">
                    <div class="team-logo">🏠</div>
                    <h3 class="team-name">${safe(preview.homeTeamName)}</h3>
                    <div class="team-rating">${safe(preview.homeFormation)} · ${safe(preview.homePlayStyle)}</div>
                </div>
                <div class="vs-section">
                    <div class="vs-text">xG ${num(preview.expectedHomeGoals, 2)} : ${num(preview.expectedAwayGoals, 2)}</div>
                    <div class="prediction-score">
                        <span>${pct(preview.homeFormationFitness || 0)}</span>
                        <span>${safe(preview.homePositionMismatches, 0)} / ${safe(preview.awayPositionMismatches, 0)} mismatches</span>
                        <span>${pct((preview.awayFormationFitness || 0))}</span>
                    </div>
                </div>
                <div class="team-card">
                    <div class="team-logo">✈️</div>
                    <h3 class="team-name">${safe(preview.awayTeamName)}</h3>
                    <div class="team-rating">${safe(preview.awayFormation)} · ${safe(preview.awayPlayStyle)}</div>
                </div>
            </div>
        </div>

        <div class="zox-analysis-grid">
            <section class="zox-panel">
                <h3>Why This Prediction</h3>
                <ul class="zox-reason-list">${renderReasons(preview.predictionReasons)}</ul>
                <div class="zox-analysis-copy">${safe(preview.analysisText, 'No preview analysis available.')}</div>
            </section>
            <section class="zox-panel">
                <h3>Squad Readiness</h3>
                <div class="zox-dual-grid">
                    <div>
                        <h4>${safe(preview.homeTeamName)}</h4>
                        ${renderInsightList(preview.homeInsights)}
                    </div>
                    <div>
                        <h4>${safe(preview.awayTeamName)}</h4>
                        ${renderInsightList(preview.awayInsights)}
                    </div>
                </div>
            </section>
            <section class="zox-panel">
                <h3>Absences</h3>
                <div class="zox-dual-grid">
                    <div>
                        <h4>${safe(preview.homeTeamName)}</h4>
                        <div class="zox-chip-wrap">${renderAbsentees(preview.homeAbsentees)}</div>
                    </div>
                    <div>
                        <h4>${safe(preview.awayTeamName)}</h4>
                        <div class="zox-chip-wrap">${renderAbsentees(preview.awayAbsentees)}</div>
                    </div>
                </div>
            </section>
        </div>

        <div class="lineups-section">
            <div class="lineup-card">
                <div class="lineup-header">
                    <h3>${safe(preview.homeTeamName)}</h3>
                    <div class="formation">${safe(preview.homeFormation)} · Bench ${num(preview.homeBenchQuality || 0)}</div>
                </div>
                <div class="players-grid">${renderPlayersGrid(preview.homeLineup)}</div>
            </div>
            <div class="lineup-card">
                <div class="lineup-header">
                    <h3>${safe(preview.awayTeamName)}</h3>
                    <div class="formation">${safe(preview.awayFormation)} · Bench ${num(preview.awayBenchQuality || 0)}</div>
                </div>
                <div class="players-grid">${renderPlayersGrid(preview.awayLineup)}</div>
            </div>
        </div>

        <section class="zox-panel">
            <h3>Post-Match Report</h3>
            <div class="zox-report-headline">${safe(report.headline, 'Match report unavailable.')}</div>
            <p class="zox-report-summary">${safe(report.summary, 'No report summary available.')}</p>
            <div class="zox-report-meta">
                <div><strong>Turning point:</strong> ${safe(report.turningPoint, 'N/A')}</div>
                <div><strong>Tactical verdict:</strong> ${safe(report.tacticalVerdict, 'N/A')}</div>
            </div>
        </section>

        <div class="zox-analysis-grid">
            <section class="zox-panel">
                <h3>Top Performers</h3>
                <div class="zox-dual-grid">
                    <div>
                        <h4>${safe(preview.homeTeamName)}</h4>
                        ${renderTopPerformers(report.homeTopPerformers)}
                    </div>
                    <div>
                        <h4>${safe(preview.awayTeamName)}</h4>
                        ${renderTopPerformers(report.awayTopPerformers)}
                    </div>
                </div>
            </section>
            <section class="zox-panel">
                <h3>Timeline</h3>
                <div class="zox-timeline">${renderTimeline(report.timeline)}</div>
            </section>
        </div>

        <section class="zox-panel">
            <h3>Match Stats</h3>
            ${renderStatsTable(stats, preview)}
        </section>
    `;
}

async function initializeZoxDashboard() {
    const content = getZoxContent();
    if (!content) return;

    const matchId = await getMatchId();
    if (!matchId) {
        content.innerHTML = `
            <div class="zox-empty-block">
                <p>No match found. Please play a match first or open one from Results.</p>
                <a href="/dashboard.html" class="zox-back-inline">Back to Dashboard</a>
            </div>
        `;
        return;
    }

    try {
        const [preview, stats, report] = await Promise.all([
            authFetch(`/api/zox/match-preview/${matchId}`).then(response => response.json()),
            authFetch(`/api/zox/match-stats/${matchId}`).then(response => response.json()),
            authFetch(`/api/zox/post-match-report/${matchId}`).then(response => response.json())
        ]);
        renderDashboard(preview, stats, report);
    } catch (error) {
        console.error('Error loading ZOX data:', error);
        content.innerHTML = `
            <div class="zox-empty-block error">
                <p>Error loading match data: ${safe(error.message, 'Unknown error')}</p>
            </div>
        `;
    }
}

void initializeZoxDashboard();
