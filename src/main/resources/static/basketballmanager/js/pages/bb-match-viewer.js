// bb-match-viewer.js — Match viewer with playback for basketball manager

let _bbPlaybackTimer = null;
let _bbPlaybackRunning = false;

async function bbRenderMatchViewer(options) {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    mc.innerHTML = `
        <div class="fm-panel match-viewer" style="text-align:center;padding:40px;">
            <div style="font-size:1.2rem;color:var(--bball-primary-light);">Loading match...</div>
            <div style="margin-top:16px;font-size:0.85rem;color:#99a6bb;">Please wait</div>
        </div>`;

    const id = options?.fixtureId;
    if (!id) { mc.innerHTML = `<div class="fm-panel">${cmBuildEmptyState('No match selected')}</div>`; return; }

    let matchData = null;
    let isReEntry = false;
    try {
        const res = await window.authFetch('/api/bb/matches/' + id);
        if (res.ok) {
            matchData = await res.json();
            isReEntry = true;
        }
    } catch (e) {
    }

    let result, rawEvents, homeStats, awayStats, homeScore, awayScore, homeQS, awayQS;

    if (isReEntry && matchData) {
        result = matchData;
        rawEvents = result.events || [];
        homeStats = result.homePlayerStats || [];
        awayStats = result.awayPlayerStats || [];
        homeScore = result.homeScore;
        awayScore = result.awayScore;
        homeQS = (result.homeQuarterScores || '').split('-').filter(Boolean);
        awayQS = (result.awayQuarterScores || '').split('-').filter(Boolean);
    } else {
        const simResult = await window.bbPlayFixture(id);
        if (!simResult) { mc.innerHTML = `<div class="fm-panel">${cmBuildEmptyState('Match failed')}</div>`; return; }
        result = simResult;
        rawEvents = result.events || [];
        homeStats = result.homePlayerStats || [];
        awayStats = result.awayPlayerStats || [];
        homeScore = result.homeScore;
        awayScore = result.awayScore;
        homeQS = (result.homeQuarterScores || '').split('-').filter(Boolean);
        awayQS = (result.awayQuarterScores || '').split('-').filter(Boolean);
        window.bbInvalidateCache();
    }

    const toEngineFormat = (statsArr) => (statsArr || []).map(s => ({
        playerId: s.playerId, playerName: s.playerName, position: s.position,
        minutes: s.minutes || 0,
        points: s.points || 0,
        rebounds: s.rebounds || 0,
        assists: s.assists || 0,
        steals: s.steals || 0,
        blocks: s.blocks || 0,
        turnovers: s.turnovers || 0,
        fouls: s.fouls || 0,
        twoPtMade: s.twoPtMade || 0,
        twoPtAttempted: s.twoPtAttempted || 0,
        threePtMade: s.threePtMade || 0,
        threePtAttempted: s.threePtAttempted || 0,
        ftMade: s.ftMade || 0,
        ftAttempted: s.ftAttempted || 0
    }));

    const homeStatsEngine = isReEntry ? toEngineFormat(homeStats) : homeStats;
    const awayStatsEngine = isReEntry ? toEngineFormat(awayStats) : awayStats;

    const parsedEvents = rawEvents.map(e => {
        const parts = e.split('|');
        const timePart = parts[0] || '';
        const type = parts[1] || 'TEXT';
        const p1Id = parseInt(parts[2]) || 0;
        const p1Name = parts[3] || '';

        if (type === 'MADE') {
            const pts = parseInt(parts[4]) || 2;
            const passerId = parseInt(parts[5]) || 0;
            const passerName = parts[6] || '';
            return { time: timePart, type, p1Id, p1Name, p2Id: passerId, p2Name: passerName, pts, isThree: pts === 3 };
        }
        if (type === 'AND1') {
            const pts = parseInt(parts[4]) || 2;
            const passerId = parseInt(parts[5]) || 0;
            const passerName = parts[6] || '';
            const foulerId = parseInt(parts[7]) || 0;
            const foulerName = parts[8] || '';
            return { time: timePart, type, p1Id, p1Name, p2Id: passerId, p2Name: passerName, p3Id: foulerId, p3Name: foulerName, pts, isThree: pts === 3 };
        }
        if (type === 'MISS') {
            const pts = parseInt(parts[4]) || 2;
            return { time: timePart, type, p1Id, p1Name, pts, isThree: pts === 3 };
        }
        if (type === 'FT') {
            const made = parseInt(parts[4]) || 0;
            const att = parseInt(parts[5]) || 2;
            const foulerId = parseInt(parts[6]) || 0;
            const foulerName = parts[7] || '';
            return { time: timePart, type, p1Id, p1Name, p2Id: att, p2Name: '', p3Id: foulerId, p3Name: foulerName, pts: made };
        }
        const p2Id = parseInt(parts[4]) || 0;
        const p2Name = parts[5] || '';
        const p3Id = parseInt(parts[6]) || 0;
        const p3Name = parts[7] || '';
        const desc = parts.slice(8).join(' ') || '';
        return { time: timePart, type, p1Id, p1Name, p2Id, p2Name, p3Id, p3Name, pts: 0, desc };
    });

    const qScoresHtml = homeQS.map((qs, i) => `
        <div class="qtr-score">
            <div class="qtr-label">${homeQS.length > 4 ? 'OT' + (i - 3) : 'Q' + (i + 1)}</div>
            <div class="qtr-home">${qs || '?'}</div>
            <div class="qtr-away">${awayQS[i] || '?'}</div>
        </div>
    `).join('');
    const showQuarters = !options?.hideQuarters;

    mc.innerHTML = `
        <div class="fm-panel match-viewer" style="position:relative;overflow:hidden;">
            <div class="court-bg" style="position:absolute;top:0;left:0;right:0;bottom:0;background:url('/basketballmanager/court.jpg') center/cover no-repeat;opacity:0.15;pointer-events:none;z-index:0;"></div>
            <div style="position:relative;z-index:1;">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
                <div>
                    <div class="bball-eyebrow">${isReEntry ? 'Match Replay' : 'Live Match'}</div>
                    <h2 style="margin:4px 0;">${cmEscapeHtml(result.homeTeamName)} vs ${cmEscapeHtml(result.awayTeamName)}</h2>
                </div>
                <button class="back-button" onclick="bbStopPlayback();loadPage('')">← Dashboard</button>
            </div>

            <div class="scoreboard" style="position:relative;z-index:1;">
                <div class="score-team home">
                    <div class="score-team-name">${cmEscapeHtml(result.homeTeamName)}</div>
                    <div class="score-value" id="bb-live-home-score">0</div>
                </div>
                <div class="score-vs">
                    VS
                    <div id="bb-live-clock" style="font-size:0.9rem;font-weight:700;margin-top:4px;color:#f5a623;">10:00</div>
                    <div id="bb-live-quarter" style="font-size:0.7rem;text-transform:uppercase;color:#99a6bb;">Q1</div>
                </div>
                <div class="score-team away">
                    <div class="score-team-name">${cmEscapeHtml(result.awayTeamName)}</div>
                    <div class="score-value" id="bb-live-away-score">0</div>
                </div>
            </div>

            ${showQuarters ? `<div class="qtr-scores" style="position:relative;z-index:1;">${qScoresHtml}</div>` : ''}

            <div style="margin:20px 0;position:relative;z-index:1;">
                <div style="font-size:0.75rem;text-transform:uppercase;letter-spacing:1px;color:#99a6bb;">Fouls: ${cmEscapeHtml(result.homeTeamName)} <span id="bb-live-home-fouls">0</span> · ${cmEscapeHtml(result.awayTeamName)} <span id="bb-live-away-fouls">0</span></div>
            </div>

            <div style="display:flex;gap:12px;margin-bottom:12px;position:relative;z-index:1;">
                <span class="tab-btn active" onclick="bbSwitchMatchTab('bbside', this)">📊 Live Stats</span>
                <span class="tab-btn" onclick="bbSwitchMatchTab('feed', this)">📜 Play-by-Play</span>
            </div>

            <div id="match-bbside-tab" class="match-tab">
                <div class="side-stats-grid">
                    <div class="side-stats-home">
                        <h4 style="text-align:center;color:var(--bball-primary-light);margin:0 0 8px;">${cmEscapeHtml(result.homeTeamName)}</h4>
                        <div style="overflow-x:auto;">
                            <table class="player-table stats-table" id="bb-live-home-stats-table">
                                <thead><tr>
                                    <th data-sort="string" style="text-align:left;">Player</th><th data-sort="number">Pts</th><th data-sort="string">FT</th><th data-sort="string">2P</th><th data-sort="string">3P</th><th data-sort="number">Reb</th><th data-sort="number">Ast</th><th data-sort="number">Stl</th><th data-sort="number">TO</th><th data-sort="number">Blk</th><th data-sort="number">F</th>
                                </tr></thead>
                                <tbody></tbody>
                            </table>
                        </div>
                    </div>
                    <div class="side-stats-away">
                        <h4 style="text-align:center;color:var(--bball-primary-light);margin:0 0 8px;">${cmEscapeHtml(result.awayTeamName)}</h4>
                        <div style="overflow-x:auto;">
                            <table class="player-table stats-table" id="bb-live-away-stats-table">
                                <thead><tr>
                                    <th data-sort="string" style="text-align:left;">Player</th><th data-sort="number">Pts</th><th data-sort="string">FT</th><th data-sort="string">2P</th><th data-sort="string">3P</th><th data-sort="number">Reb</th><th data-sort="number">Ast</th><th data-sort="number">Stl</th><th data-sort="number">TO</th><th data-sort="number">Blk</th><th data-sort="number">F</th>
                                </tr></thead>
                                <tbody></tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>

            <div id="match-feed-tab" class="match-tab" style="display:none;">
                <div id="bb-live-event-feed" class="event-feed">
                    <div class="match-event" style="color:#f5a623;">Tip-off...</div>
                </div>
            </div>
        </div>`;

    if (isReEntry) {
        const homeLiveStats = {};
        const awayLiveStats = {};
        homeStatsEngine.forEach(s => homeLiveStats[s.playerId] = {...s});
        awayStatsEngine.forEach(s => awayLiveStats[s.playerId] = {...s});

        const homeScoreEl = document.getElementById('bb-live-home-score');
        const awayScoreEl = document.getElementById('bb-live-away-score');
        const homeFoulsEl = document.getElementById('bb-live-home-fouls');
        const awayFoulsEl = document.getElementById('bb-live-away-fouls');
        if (homeScoreEl) homeScoreEl.textContent = homeScore;
        if (awayScoreEl) awayScoreEl.textContent = awayScore;
        if (homeFoulsEl) homeFoulsEl.textContent = homeLiveStats ? Object.values(homeLiveStats).reduce((sum, p) => sum + p.fouls, 0) : 0;
        if (awayFoulsEl) awayFoulsEl.textContent = awayLiveStats ? Object.values(awayLiveStats).reduce((sum, p) => sum + p.fouls, 0) : 0;

        function renderFinalStats(statsMap, tableId) {
            const tbody = document.querySelector('#' + tableId + ' tbody');
            if (!tbody) return;
            const players = Object.values(statsMap);
            const totals = players.reduce((acc, p) => {
                acc.points += p.points || 0;
                acc.rebounds += p.rebounds || 0;
                acc.assists += p.assists || 0;
                acc.steals += p.steals || 0;
                acc.blocks += p.blocks || 0;
                acc.turnovers += p.turnovers || 0;
                acc.fouls += p.fouls || 0;
                acc.ftMade += p.ftMade || 0;
                acc.ftAttempted += p.ftAttempted || 0;
                acc.twoPtMade += p.twoPtMade || 0;
                acc.twoPtAttempted += p.twoPtAttempted || 0;
                acc.threePtMade += p.threePtMade || 0;
                acc.threePtAttempted += p.threePtAttempted || 0;
                return acc;
            }, { points: 0, rebounds: 0, assists: 0, steals: 0, blocks: 0, turnovers: 0, fouls: 0, ftMade: 0, ftAttempted: 0, twoPtMade: 0, twoPtAttempted: 0, threePtMade: 0, threePtAttempted: 0 });

            const ftPctTeam = totals.ftAttempted > 0 ? ((totals.ftMade / totals.ftAttempted) * 100).toFixed(0) : '-';
            const twoPctTeam = totals.twoPtAttempted > 0 ? ((totals.twoPtMade / totals.twoPtAttempted) * 100).toFixed(0) : '-';
            const threePctTeam = totals.threePtAttempted > 0 ? ((totals.threePtMade / totals.threePtAttempted) * 100).toFixed(0) : '-';

            tbody.innerHTML = players.map(ps => {
                const pid = ps.playerId;
                const ftPct = ps.ftAttempted > 0 ? ((ps.ftMade / ps.ftAttempted) * 100).toFixed(0) : '-';
                const twoPct = ps.twoPtAttempted > 0 ? ((ps.twoPtMade / ps.twoPtAttempted) * 100).toFixed(0) : '-';
                const threePct = ps.threePtAttempted > 0 ? ((ps.threePtMade / ps.threePtAttempted) * 100).toFixed(0) : '-';
                const trebs = ps.rebounds || 0;
                return `<tr>
                    <td style="text-align:left;color:#99a6bb;"><a href="#" onclick="bbShowPlayer(${pid});return false;" style="color:inherit;text-decoration:none;">${cmEscapeHtml(ps.playerName)}</a></td>
                    <td style="font-weight:900;color:#f5a623;">${ps.points || 0}</td>
                    <td>${ps.ftMade || 0}/${ps.ftAttempted || 0}</td>
                    <td>${ps.twoPtMade || 0}/${ps.twoPtAttempted || 0}</td>
                    <td>${ps.threePtMade || 0}/${ps.threePtAttempted || 0}</td>
                    <td>${trebs}</td>
                    <td>${ps.assists || 0}</td>
                    <td>${ps.steals || 0}</td>
                    <td>${ps.turnovers || 0}</td>
                    <td>${ps.blocks || 0}</td>
                    <td>${ps.fouls || 0}</td>
                </tr>`;
            }).join('') + `<tr style="font-weight:900;background:rgba(232,125,47,0.1);border-top:2px solid var(--bball-primary);">
                <td style="text-align:left;color:var(--bball-primary-light);">TOTAL</td>
                <td style="color:#f5a623;">${totals.points}</td>
                <td>${totals.ftMade}/${totals.ftAttempted} (${ftPctTeam}%)</td>
                <td>${totals.twoPtMade}/${totals.twoPtAttempted} (${twoPctTeam}%)</td>
                <td>${totals.threePtMade}/${totals.threePtAttempted} (${threePctTeam}%)</td>
                <td>${totals.rebounds}</td>
                <td>${totals.assists}</td>
                <td>${totals.steals}</td>
                <td>${totals.turnovers}</td>
                <td>${totals.blocks}</td>
                <td>${totals.fouls}</td>
            </tr>`;
        }

        renderFinalStats(homeLiveStats, 'bb-live-home-stats-table');
        renderFinalStats(awayLiveStats, 'bb-live-away-stats-table');

        setTimeout(() => {
            const hTable = document.getElementById('bb-live-home-stats-table');
            const aTable = document.getElementById('bb-live-away-stats-table');
            window.bbMakeSortable(hTable);
            window.bbMakeSortable(aTable);
            window.bbSortTableByColumn(hTable, 1, 'desc');
            window.bbSortTableByColumn(aTable, 1, 'desc');
        }, 100);

        const feed = document.getElementById('bb-live-event-feed');
        if (feed) {
            feed.innerHTML = '<div class="match-event" style="color:#6fcf97;font-weight:700;">🏁 Final: ' + homeScore + ' - ' + awayScore + '</div>';
        }
    } else {
        bbPlaybackStart(parsedEvents, homeStatsEngine, awayStatsEngine, result);
        setTimeout(() => {
            const hTable = document.getElementById('bb-live-home-stats-table');
            const aTable = document.getElementById('bb-live-away-stats-table');
            window.bbMakeSortable(hTable);
            window.bbMakeSortable(aTable);
            window.bbSortTableByColumn(hTable, 1, 'desc');
            window.bbSortTableByColumn(aTable, 1, 'desc');
        }, 100);
    }
}

function bbStopPlayback() {
    _bbPlaybackRunning = false;
    if (_bbPlaybackTimer) {
        clearTimeout(_bbPlaybackTimer);
        _bbPlaybackTimer = null;
    }
}

async function bbPlaybackStart(events, homeStats, awayStats, result) {
    _bbPlaybackRunning = true;
    const feed = document.getElementById('bb-live-event-feed');
    const homeScoreEl = document.getElementById('bb-live-home-score');
    const awayScoreEl = document.getElementById('bb-live-away-score');
    const homeFoulsEl = document.getElementById('bb-live-home-fouls');
    const awayFoulsEl = document.getElementById('bb-live-away-fouls');
    const clockEl = document.getElementById('bb-live-clock');
    const quarterEl = document.getElementById('bb-live-quarter');

    let homeScore = 0, awayScore = 0;
    let homeFouls = 0, awayFouls = 0;
    let currentQuarter = 1;

    const homeLiveStats = {};
    const awayLiveStats = {};
    function initLiveStats(statsArr, map) {
        for (const s of statsArr) {
            map[s.playerId] = {
                playerId: s.playerId, playerName: s.playerName, position: s.position,
                points: 0, rebounds: 0, assists: 0, steals: 0, blocks: 0, turnovers: 0, fouls: 0,
                twoPtMade: 0, twoPtAttempted: 0, threePtMade: 0, threePtAttempted: 0, ftMade: 0, ftAttempted: 0
            };
        }
    }
    initLiveStats(homeStats, homeLiveStats);
    initLiveStats(awayStats, awayLiveStats);

    function liveStat(pid) { return homeLiveStats[pid] || awayLiveStats[pid]; }
    function isHome(pid) { return pid in homeLiveStats; }

    function ls(pid) { return homeLiveStats[pid] || awayLiveStats[pid]; }
    function isHome(pid) { return pid in homeLiveStats; }

    function updateStatsTables() {
        function renderRows(statsMap, tableId) {
            const tbody = document.querySelector('#' + tableId + ' tbody');
            if (!tbody) return;
            const players = Object.values(statsMap);
            const totals = players.reduce((acc, p) => {
                acc.points += p.points || 0;
                acc.rebounds += p.rebounds || 0;
                acc.assists += p.assists || 0;
                acc.steals += p.steals || 0;
                acc.blocks += p.blocks || 0;
                acc.turnovers += p.turnovers || 0;
                acc.fouls += p.fouls || 0;
                acc.ftMade += p.ftMade || 0;
                acc.ftAttempted += p.ftAttempted || 0;
                acc.twoPtMade += p.twoPtMade || 0;
                acc.twoPtAttempted += p.twoPtAttempted || 0;
                acc.threePtMade += p.threePtMade || 0;
                acc.threePtAttempted += p.threePtAttempted || 0;
                return acc;
            }, { points: 0, rebounds: 0, assists: 0, steals: 0, blocks: 0, turnovers: 0, fouls: 0, ftMade: 0, ftAttempted: 0, twoPtMade: 0, twoPtAttempted: 0, threePtMade: 0, threePtAttempted: 0 });

            const ftPctTeam = totals.ftAttempted > 0 ? ((totals.ftMade / totals.ftAttempted) * 100).toFixed(0) : '-';
            const twoPctTeam = totals.twoPtAttempted > 0 ? ((totals.twoPtMade / totals.twoPtAttempted) * 100).toFixed(0) : '-';
            const threePctTeam = totals.threePtAttempted > 0 ? ((totals.threePtMade / totals.threePtAttempted) * 100).toFixed(0) : '-';

            tbody.innerHTML = players.map(ps => {
                const pid = ps.playerId;
                const twoPct = ps.twoPtAttempted > 0 ? ((ps.twoPtMade / ps.twoPtAttempted) * 100).toFixed(0) : '-';
                const threePct = ps.threePtAttempted > 0 ? ((ps.threePtMade / ps.threePtAttempted) * 100).toFixed(0) : '-';
                const ftPct = ps.ftAttempted > 0 ? ((ps.ftMade / ps.ftAttempted) * 100).toFixed(0) : '-';
                const trebs = ps.rebounds || 0;
                return `<tr>
                    <td style="text-align:left;color:#99a6bb;"><a href="#" onclick="bbShowPlayer(${pid});return false;" style="color:inherit;text-decoration:none;">${cmEscapeHtml(ps.playerName)}</a></td>
                    <td style="font-weight:900;color:#f5a623;">${ps.points || 0}</td>
                    <td>${ps.ftMade || 0}/${ps.ftAttempted || 0}</td>
                    <td>${ps.twoPtMade || 0}/${ps.twoPtAttempted || 0}</td>
                    <td>${ps.threePtMade || 0}/${ps.threePtAttempted || 0}</td>
                    <td>${trebs}</td>
                    <td>${ps.assists || 0}</td>
                    <td>${ps.steals || 0}</td>
                    <td>${ps.turnovers || 0}</td>
                    <td>${ps.blocks || 0}</td>
                    <td>${ps.fouls || 0}</td>
                </tr>`;
            }).join('') + `<tr style="font-weight:900;background:rgba(232,125,47,0.1);border-top:2px solid var(--bball-primary);">
                <td style="text-align:left;color:var(--bball-primary-light);">TOTAL</td>
                <td style="color:#f5a623;">${totals.points}</td>
                <td>${totals.ftMade}/${totals.ftAttempted} (${ftPctTeam}%)</td>
                <td>${totals.twoPtMade}/${totals.twoPtAttempted} (${twoPctTeam}%)</td>
                <td>${totals.threePtMade}/${totals.threePtAttempted} (${threePctTeam}%)</td>
                <td>${totals.rebounds}</td>
                <td>${totals.assists}</td>
                <td>${totals.steals}</td>
                <td>${totals.turnovers}</td>
                <td>${totals.blocks}</td>
                <td>${totals.fouls}</td>
            </tr>`;
        }
        renderRows(homeLiveStats, 'bb-live-home-stats-table');
        renderRows(awayLiveStats, 'bb-live-away-stats-table');
        const hTable = document.getElementById('bb-live-home-stats-table');
        const aTable = document.getElementById('bb-live-away-stats-table');
        window.bbMakeSortable(hTable);
        window.bbMakeSortable(aTable);
        window.bbReapplySort(hTable);
        window.bbReapplySort(aTable);
    }

    function playerLink(pid, name) {
        return `<a href="#" onclick="bbShowPlayer(${pid});return false;" style="color:var(--bball-primary-light);text-decoration:none;font-weight:600;">${cmEscapeHtml(name)}</a>`;
    }

    function formatEvent(ev) {
        const t = ev.time || '';
        const scorePrefix = `${homeScore} - ${awayScore} | `;
        switch (ev.type) {
            case 'MADE':
            case 'AND1': {
                const pts = ev.pts || 2;
                const suffix = ev.type === 'AND1' ? ' and-1!' : '';
                const passer = ev.p2Id ? ' (' + playerLink(ev.p2Id, ev.p2Name) + ' ast)' : '';
                return scorePrefix + t + ' — ' + playerLink(ev.p1Id, ev.p1Name) + ' scores ' + pts + 'pts' + passer + suffix;
            }
            case 'TO':
                if (ev.p2Id === 0 && ev.p2Name === 'turnover') {
                    return scorePrefix + t + ' — ' + playerLink(ev.p1Id, ev.p1Name) + ' turnover';
                }
                return scorePrefix + t + ' — ' + playerLink(ev.p2Id, ev.p2Name) + ' steals from ' + playerLink(ev.p1Id, ev.p1Name);
            case 'BLK':
                return scorePrefix + t + ' — ' + playerLink(ev.p1Id, ev.p1Name) + '\'s shot blocked by ' + playerLink(ev.p2Id, ev.p2Name);
            case 'FT': {
                const m = ev.pts || 0;
                const a = ev.p2Id || 2;
                const word = m === 0 ? 'misses' : 'makes';
                return scorePrefix + t + ' — ' + playerLink(ev.p1Id, ev.p1Name) + ' ' + word + ' ' + m + '/' + a + ' FTs';
            }
            case 'MISS':
                return scorePrefix + t + ' — ' + playerLink(ev.p1Id, ev.p1Name) + ' misses ' + (ev.isThree ? '3pt' : '2pt');
            case 'OREB':
                return scorePrefix + t + ' — ' + playerLink(ev.p1Id, ev.p1Name) + ' offensive rebound';
            case 'DREB':
                return scorePrefix + t + ' — ' + playerLink(ev.p1Id, ev.p1Name) + ' defensive rebound';
            default:
                return scorePrefix + t + ' — ' + cmEscapeHtml(ev.desc || '');
        }
    }

    function applyEvent(ev) {
        const s = liveStat(ev.p1Id);
        if (!s) return;

        switch (ev.type) {
            case 'MADE': {
                const pts = ev.pts || 2;
                s.points += pts;
                if (pts === 3) { s.threePtMade++; s.threePtAttempted++; }
                else { s.twoPtMade++; s.twoPtAttempted++; }
                if (ev.p2Id) {
                    const ps = liveStat(ev.p2Id);
                    if (ps) ps.assists++;
                }
                break;
            }
            case 'AND1': {
                const pts = ev.pts || 2;
                s.points += pts + 1;
                if (pts === 3) { s.threePtMade++; s.threePtAttempted++; }
                else { s.twoPtMade++; s.twoPtAttempted++; }
                if (ev.p2Id) {
                    const ps = liveStat(ev.p2Id);
                    if (ps) ps.assists++;
                }
                if (ev.p3Id) {
                    const fs = liveStat(ev.p3Id);
                    if (fs) fs.fouls++;
                }
                break;
            }
            case 'MISS': {
                const pts = ev.pts || 2;
                if (pts === 3) s.threePtAttempted++;
                else s.twoPtAttempted++;
                break;
            }
            case 'FT': {
                const m = ev.pts || 0;
                const a = ev.p2Id || 2;
                s.ftMade += m;
                s.ftAttempted += a;
                s.points += m;
                if (ev.p3Id) {
                    const fs = liveStat(ev.p3Id);
                    if (fs) fs.fouls++;
                }
                break;
            }
            case 'TO': {
                s.turnovers++;
                if (ev.p2Id && ev.p2Name !== 'turnover') {
                    const st = liveStat(ev.p2Id);
                    if (st) st.steals++;
                }
                break;
            }
            case 'BLK': {
                const bk = liveStat(ev.p2Id);
                if (bk) bk.blocks++;
                break;
            }
            case 'OREB':
            case 'DREB': {
                s.rebounds++;
                break;
            }
        }
    }

    function updateScoreboard() {
        homeScore = Object.values(homeLiveStats).reduce((s, p) => s + p.points, 0);
        awayScore = Object.values(awayLiveStats).reduce((s, p) => s + p.points, 0);
        homeFouls = Object.values(homeLiveStats).reduce((s, p) => s + p.fouls, 0);
        awayFouls = Object.values(awayLiveStats).reduce((s, p) => s + p.fouls, 0);
        if (homeScoreEl) homeScoreEl.textContent = homeScore;
        if (awayScoreEl) awayScoreEl.textContent = awayScore;
        if (homeFoulsEl) homeFoulsEl.textContent = homeFouls;
        if (awayFoulsEl) awayFoulsEl.textContent = awayFouls;
        const totalEvents = events.length;
        const progress = idx / Math.max(1, totalEvents);
        const totalSeconds = 4 * 10 * 60;
        const elapsedSeconds = Math.floor(progress * totalSeconds);
        const q = Math.floor(elapsedSeconds / (10 * 60)) + 1;
        const qRemain = 10 * 60 - (elapsedSeconds % (10 * 60));
        const qNum = Math.min(q, 4);
        if (quarterEl) quarterEl.textContent = qNum <= 4 ? 'Q' + qNum : 'OT';
        if (clockEl) {
            const m = Math.floor(qRemain / 60);
            const s = qRemain % 60;
            clockEl.textContent = m + ':' + (s < 10 ? '0' : '') + s;
        }
        updateStatsTables();
    }

    let idx = 0;
    let isFirstEvent = true;
    function playNext() {
        if (!_bbPlaybackRunning || idx >= events.length) {
            if (idx >= events.length && feed) {
                const el = document.createElement('div');
                el.className = 'match-event';
                el.style.cssText = 'color:#6fcf97;font-weight:700;margin-top:12px;';
                el.textContent = '🏁 Final: ' + homeScore + ' - ' + awayScore;
                feed.appendChild(el);
                feed.scrollTop = feed.scrollHeight;
                updateScoreboard();
            }
            _bbPlaybackRunning = false;
            return;
        }
        const ev = events[idx];
        applyEvent(ev);
        updateScoreboard();

        if (feed) {
            const el = document.createElement('div');
            el.className = 'match-event';
            el.innerHTML = formatEvent(ev);
            feed.appendChild(el);
            feed.scrollTop = feed.scrollHeight;
        }

        idx++;
        let delay = (ev.type === 'MADE' || ev.type === 'AND1' || ev.type === 'FT') ? 500 : 200;
        if (isFirstEvent) {
            delay = 2500;
            isFirstEvent = false;
        }
        _bbPlaybackTimer = setTimeout(playNext, delay);
    }

    updateScoreboard();
    playNext();
}

window.bbRenderMatchViewer = bbRenderMatchViewer;
window.bbStopPlayback = bbStopPlayback;
