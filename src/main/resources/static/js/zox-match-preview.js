/**
 * ZOX Match Preview JavaScript
 * Fetches and displays detailed match analytics
 */
import { authFetch } from './auth.js';

function getZoxContent() {
    return document.getElementById('zox-content');
}

// Get matchId from URL or try to find latest match
async function getMatchId() {
    const params = new URLSearchParams(window.location.search);
    let matchId = params.get('matchId') || localStorage.getItem('lastMatchId');
    
    // Ako nema matchId, pokušaj da pronađeš prvi odigran meč
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
        } catch (e) {
            console.warn('Could not fetch latest match:', e);
        }
    }
    
    return matchId;
}

async function initializeZoxDashboard() {
    const content = getZoxContent();
    if (!content) return;

    const matchId = await getMatchId();
    
    if (!matchId) {
        content.innerHTML = `
            <div style="text-align: center; padding: 40px; color: #888;">
                <p>No match found. Please play a match first or select one from Results.</p>
                <a href="/dashboard.html" style="color: #e8d47d;">← Back to Dashboard</a>
            </div>
        `;
        return;
    }

    try {
        // Fetch all required data
        const [preview, stats, prediction] = await Promise.all([
            authFetch(`/api/zox/match-preview/${matchId}`).then(r => r.json()),
            authFetch(`/api/zox/match-stats/${matchId}`).then(r => r.json()),
            authFetch(`/api/zox/prediction/${matchId}`).then(r => r.json())
        ]);

        // Render dashboard
        renderMatchPreview(preview, stats, prediction, matchId);
        
    } catch (error) {
        console.error('Error loading ZOX data:', error);
        content.innerHTML = `
            <div style="text-align: center; padding: 40px; color: #e74c3c;">
                <p>Error loading match data: ${error.message}</p>
            </div>
        `;
    }
}

function renderMatchPreview(preview, stats, prediction, matchId) {
    const content = document.getElementById('zox-content');
    
    let html = `
        <!-- Match Overview -->
        <div class="match-overview">
            <div class="overview-card">
                <h3>Home Team</h3>
                <div class="value">${preview.homeTeamName}</div>
                <div class="subtext">Rating: ${preview.homeTeamRating || 'N/A'}</div>
            </div>
            <div class="overview-card">
                <h3>Prediction</h3>
                <div class="value">${prediction.mostLikelyResult || 'DRAW'}</div>
                <div class="subtext">
                    ${(prediction.homeWinProbability * 100).toFixed(0)}% / 
                    ${(prediction.drawProbability * 100).toFixed(0)}% / 
                    ${(prediction.awayWinProbability * 100).toFixed(0)}%
                </div>
            </div>
            <div class="overview-card">
                <h3>Away Team</h3>
                <div class="value">${preview.awayTeamName}</div>
                <div class="subtext">Rating: ${preview.awayTeamRating || 'N/A'}</div>
            </div>
        </div>

        <!-- Team Comparison -->
        <div class="team-comparison">
            <div class="team-comparison-header">
                <div class="team-card">
                    <div class="team-logo">🏠</div>
                    <h3 class="team-name">${preview.homeTeamName}</h3>
                    <div class="team-rating">Formation: ${preview.homeFormation || '4-3-3'}</div>
                </div>
                <div class="vs-section">
                    <div class="vs-text">VS</div>
                    <div class="prediction-score">
                        <span>W: ${(prediction.homeWinProbability * 100).toFixed(0)}%</span>
                        <span>D: ${(prediction.drawProbability * 100).toFixed(0)}%</span>
                        <span>L: ${(prediction.awayWinProbability * 100).toFixed(0)}%</span>
                    </div>
                </div>
                <div class="team-card">
                    <div class="team-logo">✈️</div>
                    <h3 class="team-name">${preview.awayTeamName}</h3>
                    <div class="team-rating">Formation: ${preview.awayFormation || '4-3-3'}</div>
                </div>
            </div>
        </div>

        <!-- Lineups -->
        <div class="lineups-section">
            <div class="lineup-card">
                <div class="lineup-header">
                    <h3>${preview.homeTeamName}</h3>
                    <div class="formation">${preview.homeFormation || '4-3-3'}</div>
                </div>
                <div class="players-grid">
                    ${renderPlayersGrid(preview.homeLineup || [])}
                </div>
            </div>
            <div class="lineup-card">
                <div class="lineup-header">
                    <h3>${preview.awayTeamName}</h3>
                    <div class="formation">${preview.awayFormation || '4-3-3'}</div>
                </div>
                <div class="players-grid">
                    ${renderPlayersGrid(preview.awayLineup || [])}
                </div>
            </div>
        </div>

        <!-- Statistics -->
        <div class="stats-section">
            <div class="stat-card">
                <h4>Shots</h4>
                <div class="stat-compare">
                    <div class="stat-value">
                        <div class="label">On Target</div>
                        <div class="number">${stats.homeShotsOnTarget || 0}</div>
                    </div>
                    <div class="stat-value">
                        <div class="label">On Target</div>
                        <div class="number">${stats.awayShotsOnTarget || 0}</div>
                    </div>
                </div>
            </div>
            <div class="stat-card">
                <h4>Expected Goals (xG)</h4>
                <div class="stat-compare">
                    <div class="stat-value">
                        <div class="label">Home xG</div>
                        <div class="number">${(prediction.expectedHomeGoals || 0).toFixed(2)}</div>
                    </div>
                    <div class="stat-value">
                        <div class="label">Away xG</div>
                        <div class="number">${(prediction.expectedAwayGoals || 0).toFixed(2)}</div>
                    </div>
                </div>
            </div>
            <div class="stat-card">
                <h4>Possession</h4>
                <div class="stat-compare">
                    <div class="stat-value">
                        <div class="label">Home %</div>
                        <div class="number">${(stats.homePossession || 50).toFixed(0)}%</div>
                    </div>
                    <div class="stat-value">
                        <div class="label">Away %</div>
                        <div class="number">${(stats.awayPossession || 50).toFixed(0)}%</div>
                    </div>
                </div>
            </div>
            <div class="stat-card">
                <h4>Pass Accuracy</h4>
                <div class="stat-compare">
                    <div class="stat-value">
                        <div class="label">Home</div>
                        <div class="number">${(stats.homePassAccuracy || 0).toFixed(0)}%</div>
                    </div>
                    <div class="stat-value">
                        <div class="label">Away</div>
                        <div class="number">${(stats.awayPassAccuracy || 0).toFixed(0)}%</div>
                    </div>
                </div>
            </div>
        </div>
    `;

    content.innerHTML = html;

    // Initialize charts after rendering
    renderCharts(stats, prediction);
}

function renderPlayersGrid(players) {
    if (!players || players.length === 0) {
        return '<p style="text-align: center; color: #95a5a6;">No lineup data available</p>';
    }

    return players.map(player => `
        <div class="player-item">
            <div class="player-number">${player.squadNumber || '?'}</div>
            <div class="player-info">
                <div class="player-name">${player.name || 'Unknown'}</div>
                <div class="player-pos">${player.position || 'N/A'}</div>
            </div>
            <div class="player-rating">${(player.overallRating || 6.5).toFixed(1)}</div>
        </div>
    `).join('');
}

function renderCharts(stats, prediction) {
    // Possession Chart
    createPossessionChart(stats);
    
    // Shots Chart
    createShotsChart(stats);
    
    // xG Chart
    createXGChart(prediction);
}

function createPossessionChart(stats) {
    const chartHtml = `
        <div class="chart-container">
            <h4>Possession %</h4>
            <canvas id="possessionChart"></canvas>
        </div>
    `;
    
    document.querySelector('.stats-section').innerHTML += chartHtml;

    const ctx = document.getElementById('possessionChart')?.getContext('2d');
    if (!ctx) return;

    new Chart(ctx, {
        type: 'doughnut',
        data: {
            labels: ['Home', 'Away'],
            datasets: [{
                data: [stats.homePossession || 50, stats.awayPossession || 50],
                backgroundColor: ['#27ae60', '#e74c3c'],
                borderColor: '#1a2332',
                borderWidth: 2
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: true,
            plugins: {
                legend: {
                    labels: {
                        color: '#ecf0f1'
                    }
                }
            }
        }
    });
}

function createShotsChart(stats) {
    const chartHtml = `
        <div class="chart-container">
            <h4>Shots Comparison</h4>
            <canvas id="shotsChart"></canvas>
        </div>
    `;
    
    document.querySelector('.stats-section').innerHTML += chartHtml;

    const ctx = document.getElementById('shotsChart')?.getContext('2d');
    if (!ctx) return;

    new Chart(ctx, {
        type: 'bar',
        data: {
            labels: ['On Target', 'Off Target'],
            datasets: [
                {
                    label: 'Home',
                    data: [stats.homeShotsOnTarget || 0, stats.homeShotsOffTarget || 0],
                    backgroundColor: '#27ae60',
                    borderColor: '#27ae60',
                    borderWidth: 1
                },
                {
                    label: 'Away',
                    data: [stats.awayShotsOnTarget || 0, stats.awayShotsOffTarget || 0],
                    backgroundColor: '#e74c3c',
                    borderColor: '#e74c3c',
                    borderWidth: 1
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: true,
            indexAxis: 'y',
            plugins: {
                legend: {
                    labels: {
                        color: '#ecf0f1'
                    }
                }
            },
            scales: {
                x: {
                    ticks: {
                        color: '#ecf0f1'
                    },
                    grid: {
                        color: '#2d3d4d'
                    }
                },
                y: {
                    ticks: {
                        color: '#ecf0f1'
                    }
                }
            }
        }
    });
}

function createXGChart(prediction) {
    const chartHtml = `
        <div class="chart-container">
            <h4>Expected Goals (xG)</h4>
            <canvas id="xgChart"></canvas>
        </div>
    `;
    
    document.querySelector('.stats-section').innerHTML += chartHtml;

    const ctx = document.getElementById('xgChart')?.getContext('2d');
    if (!ctx) return;

    new Chart(ctx, {
        type: 'bar',
        data: {
            labels: ['Expected Goals'],
            datasets: [
                {
                    label: 'Home',
                    data: [prediction.expectedHomeGoals || 1.5],
                    backgroundColor: '#27ae60',
                    borderColor: '#27ae60',
                    borderWidth: 1
                },
                {
                    label: 'Away',
                    data: [prediction.expectedAwayGoals || 1.5],
                    backgroundColor: '#e74c3c',
                    borderColor: '#e74c3c',
                    borderWidth: 1
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: true,
            plugins: {
                legend: {
                    labels: {
                        color: '#ecf0f1'
                    }
                }
            },
            scales: {
                y: {
                    ticks: {
                        color: '#ecf0f1'
                    },
                    grid: {
                        color: '#2d3d4d'
                    }
                },
                x: {
                    ticks: {
                        color: '#ecf0f1'
                    }
                }
            }
        }
    });
}

/**
 * Tab switching functionality
 */
function initializeTabs() {
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const tabName = e.target.dataset.tab;
            switchTab(tabName);
        });
    });
}

function switchTab(tabName) {
    const content = getZoxContent();
    if (!content) return;

    // Update active button
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.classList.remove('active');
        if (btn.dataset.tab === tabName) {
            btn.classList.add('active');
        }
    });

    // Load content for tab
    getMatchId().then(matchId => {
        if (!matchId) {
            content.innerHTML = `<p style="color: #e74c3c;">No match available</p>`;
            return;
        }
        
        switch(tabName) {
            case 'preview':
                initializeZoxDashboard();
                break;
            case 'formation':
                loadFormationView(matchId);
                break;
            case 'events':
                loadEventStream(matchId);
                break;
            case 'stats':
                loadStatsView(matchId);
                break;
        }
    });
}

/**
 * Load formation visualization
 */
async function loadFormationView(matchId) {
    const content = document.getElementById('zox-content');
    
    try {
        const preview = await authFetch(`/api/zox/match-preview/${matchId}`).then(r => r.json());

        let html = `
            <div class="formations-container">
                <div class="formation-side home">
                    <h2>${preview.homeTeamName}</h2>
                    <p style="text-align: center; color: #95a5a6; margin: 10px 0;">Formation: ${preview.homeFormation || '4-3-3'}</p>
                    <div class="formation-field" id="homeField">
                    </div>
                </div>
                <div class="formation-side away">
                    <h2>${preview.awayTeamName}</h2>
                    <p style="text-align: center; color: #95a5a6; margin: 10px 0;">Formation: ${preview.awayFormation || '4-3-3'}</p>
                    <div class="formation-field" id="awayField">
                    </div>
                </div>
            </div>
        `;
        
        content.innerHTML = html;
        
        // Fetch formations for both teams
        const homeFormation = await authFetch(`/api/zox/formation/${matchId}/${preview.homeTeamId}`).then(r => r.json());
        const awayFormation = await authFetch(`/api/zox/formation/${matchId}/${preview.awayTeamId}`).then(r => r.json());
        
        renderFormationFields(homeFormation.positions || [], awayFormation.positions || []);
        
    } catch (error) {
        console.error('Error loading formations:', error);
        content.innerHTML = `<div style="color: #e74c3c;">Error loading formations: ${error.message}</div>`;
    }
}

/**
 * Render SVG formation fields with players
 */
function renderFormationFields(homePositions, awayPositions) {
    const svgNS = "http://www.w3.org/2000/svg";
    
    // Home team
    const homeField = document.getElementById('homeField');
    const homeSvg = document.createElementNS(svgNS, 'svg');
    homeSvg.setAttribute('width', '100%');
    homeSvg.setAttribute('height', '400');
    homeSvg.setAttribute('viewBox', '0 0 100 100');
    homeSvg.style.border = '2px solid #27ae60';
    homeSvg.style.borderRadius = '8px';
    homeSvg.style.backgroundColor = '#0b3d0b';
    
    drawField(homeSvg, svgNS);
    drawPlayersWithPositions(homeSvg, svgNS, homePositions, true);
    homeField.appendChild(homeSvg);
    
    // Away team
    const awayField = document.getElementById('awayField');
    const awaySvg = document.createElementNS(svgNS, 'svg');
    awaySvg.setAttribute('width', '100%');
    awaySvg.setAttribute('height', '400');
    awaySvg.setAttribute('viewBox', '0 0 100 100');
    awaySvg.style.border = '2px solid #e74c3c';
    awaySvg.style.borderRadius = '8px';
    awaySvg.style.backgroundColor = '#2b0b0b';
    
    drawField(awaySvg, svgNS);
    drawPlayersWithPositions(awaySvg, svgNS, awayPositions, false);
    awayField.appendChild(awaySvg);
}

/**
 * Draw football field
 */
function drawField(svg, svgNS) {
    // Center line
    const line = document.createElementNS(svgNS, 'line');
    line.setAttribute('x1', '50');
    line.setAttribute('y1', '0');
    line.setAttribute('x2', '50');
    line.setAttribute('y2', '100');
    line.setAttribute('stroke', '#fff');
    line.setAttribute('stroke-width', '0.5');
    svg.appendChild(line);
    
    // Center circle
    const circle = document.createElementNS(svgNS, 'circle');
    circle.setAttribute('cx', '50');
    circle.setAttribute('cy', '50');
    circle.setAttribute('r', '10');
    circle.setAttribute('fill', 'none');
    circle.setAttribute('stroke', '#fff');
    circle.setAttribute('stroke-width', '0.5');
    svg.appendChild(circle);
    
    // Penalty areas
    [0, 85].forEach(x => {
        const penalty = document.createElementNS(svgNS, 'rect');
        penalty.setAttribute('x', x);
        penalty.setAttribute('y', '25');
        penalty.setAttribute('width', '15');
        penalty.setAttribute('height', '50');
        penalty.setAttribute('fill', 'none');
        penalty.setAttribute('stroke', '#fff');
        penalty.setAttribute('stroke-width', '0.5');
        svg.appendChild(penalty);
    });
}

/**
 * Draw players on field using calculated positions from formation API
 */
function drawPlayersWithPositions(svg, svgNS, positions, isHome) {
    if (!positions || positions.length === 0) return;
    
    positions.forEach(player => {
        if (player.x === undefined || player.y === undefined) return;
        
        // Draw player circle
        const circle = document.createElementNS(svgNS, 'circle');
        circle.setAttribute('cx', player.x);
        circle.setAttribute('cy', player.y);
        circle.setAttribute('r', '3');
        circle.setAttribute('fill', isHome ? '#27ae60' : '#e74c3c');
        circle.setAttribute('stroke', '#fff');
        circle.setAttribute('stroke-width', '0.8');
        
        // Draw jersey number
        const text = document.createElementNS(svgNS, 'text');
        text.setAttribute('x', player.x);
        text.setAttribute('y', player.y + 0.8);
        text.setAttribute('text-anchor', 'middle');
        text.setAttribute('font-size', '1.5');
        text.setAttribute('fill', '#fff');
        text.setAttribute('font-weight', 'bold');
        text.textContent = player.number || '?';
        
        // Tooltip
        const title = document.createElementNS(svgNS, 'title');
        title.textContent = `${player.playerName} (${player.position}) - Rating: ${(player.rating || 6).toFixed(0)}/10`;
        circle.appendChild(title);
        
        svg.appendChild(circle);
        svg.appendChild(text);
    });
}

/**
 * Load event stream
 */
async function loadEventStream(matchId) {
    const content = document.getElementById('zox-content');
    
    try {
        const eventStream = await authFetch(`/api/zox/event-stream/${matchId}`).then(r => r.json());
        
        let html = `
            <div class="event-stream-container">
                <div class="match-progress">
                    <div class="progress-bar">
                        <div class="progress-fill" style="width: ${Math.min(100, eventStream.minute || 0)}%"></div>
                    </div>
                    <div class="time-display">
                        ${eventStream.minute || 0}' (${eventStream.timeStatus || 'Live'})
                        <span class="score">${eventStream.homeGoals || 0} - ${eventStream.awayGoals || 0}</span>
                    </div>
                </div>
                <div class="events-timeline">
                    <h3>Match Events</h3>
        `;
        
        if (eventStream.events && eventStream.events.length > 0) {
            eventStream.events.forEach(event => {
                html += `
                    <div class="event-item event-${(event.type || '').toLowerCase()}">
                        <div class="event-time">${event.minute}'</div>
                        <div class="event-icon">${event.eventIcon || '⚽'}</div>
                        <div class="event-content">
                            <div class="event-team">${event.teamName || ''}</div>
                            <div class="event-description">${event.description || ''}</div>
                        </div>
                    </div>
                `;
            });
        } else {
            html += '<p style="text-align: center; color: #95a5a6; padding: 20px;">No events yet</p>';
        }
        
        html += '</div></div>';
        content.innerHTML = html;
        
    } catch (error) {
        console.error('Error loading event stream:', error);
        content.innerHTML = `<div style="color: #e74c3c;">Error loading events: ${error.message}</div>`;
    }
}

/**
 * Load stats view
 */
function loadStatsView(matchId) {
    const content = document.getElementById('zox-content');
    content.innerHTML = '<p style="text-align: center; padding: 40px; color: #95a5a6;">Stats view coming soon...</p>';
}

// Initialize tabs and main dashboard
document.addEventListener('DOMContentLoaded', () => {
    if (!getZoxContent()) return;
    initializeTabs();
    initializeZoxDashboard();
});
