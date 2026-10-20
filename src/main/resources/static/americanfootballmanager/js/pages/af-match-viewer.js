let _afPlaybackTimer = null;
let _afPlaybackRunning = false;
let _afLiveSortState = { home: { colIdx: 6, dir: 'desc' }, away: { colIdx: 6, dir: 'desc' } };

async function afRenderMatchViewer(options = {}) {
    const mc = document.getElementById('main-content');
    if (!mc) return;

    const id = options?.fixtureId;
    if (!id) {
        mc.innerHTML = `<div class="fm-panel">${cmBuildEmptyState('No match specified')}</div>`;
        return;
    }

    mc.innerHTML = '<div class="fm-panel" style="text-align:center;padding:40px;color:#99a6bb;">Loading match...</div>';

    let match;
    match = await window.afFetchMatchDetail(id);
    if (!match || !match.played) {
        const result = await window.afPlayFixture(id);
        if (!result) {
            mc.innerHTML = `<div class="fm-panel">${cmBuildEmptyState('Could not load match')}</div>`;
            return;
        }
        match = {
            id: id,
            homeTeamId: result.homeTeamId,
            homeTeamName: result.homeTeamName,
            awayTeamId: result.awayTeamId,
            awayTeamName: result.awayTeamName,
            played: true,
            homeScore: result.homeScore,
            awayScore: result.awayScore,
            homeQuarterScores: result.homeQuarterScores,
            awayQuarterScores: result.awayQuarterScores,
            events: result.events || [],
            homePlayerStats: result.homePlayerStats || [],
            awayPlayerStats: result.awayPlayerStats || [],
        };
        window.afInvalidateCache();
    }

    const homeQS = (match.homeQuarterScores || '').split('-').filter(Boolean).map(Number);
    const awayQS = (match.awayQuarterScores || '').split('-').filter(Boolean).map(Number);
    const numQtrs = Math.max(homeQS.length, awayQS.length);
    const quarterLabels = [];
    for (let i = 0; i < numQtrs; i++) {
        quarterLabels.push(i < 4 ? 'Q' + (i + 1) : 'OT' + (i - 3));
    }

    const rawEvents = match.events || [];
    const homeStatsArr = match.homePlayerStats || [];
    const awayStatsArr = match.awayPlayerStats || [];

    const homePlayerIds = new Set(homeStatsArr.map(s => String(s.playerId)));
    const awayPlayerIds = new Set(awayStatsArr.map(s => String(s.playerId)));

    function detectTeam(parts) {
        const p1Id = parts[2] || '';
        if (homePlayerIds.has(p1Id)) return 'home';
        if (awayPlayerIds.has(p1Id)) return 'away';
        return null;
    }

    const parsedEvents = [];
    let lastKnownTeam = 'home';
    for (const ev of rawEvents) {
        const parts = ev.split('|');
        const timeStr = parts[0] || '';
        const type = parts[1] || '';
        const p1Id = parseInt(parts[2]) || 0;
        const p1Name = parts[3] || '';

        let team = detectTeam(parts);
        if (!team) {
            team = lastKnownTeam;
        }
        lastKnownTeam = team;

        let displayName = p1Name.replace(/_/g, ' ');
        let detail = '';
        let pts = 0;

        switch (type) {
            case 'RUN':
                detail = parts[5] || `rush ${parts[4] || 0} yds`;
                break;
            case 'PASS':
                detail = parts[7] || `pass ${parts[6] || 0} yds`;
                break;
            case 'INC':
                detail = 'incomplete pass';
                break;
            case 'SACK':
                detail = parts[5] || `sacked for ${parts[4] || 0} yds`;
                break;
            case 'FIRST':
                displayName = 'First Down';
                detail = '';
                break;
            case 'FG': {
                detail = parts[5] || 'FG attempt';
                const made = parseInt(parts[4]) || 0;
                if (made > 0) pts = 3;
                break;
            }
            case 'PUNT':
                displayName = 'Punt';
                detail = '';
                break;
            case 'TD':
                detail = parts[5] || 'TOUCHDOWN!';
                pts = 6;
                break;
            case 'PAT':
                detail = 'extra point';
                pts = 1;
                break;
            case 'SAF':
                detail = 'Safety';
                pts = 2;
                break;
            default:
                detail = parts.slice(4).join(' ').replace(/_/g, ' ');
        }

        parsedEvents.push({ timeStr, type, p1Id, p1Name: displayName, detail, pts, team, raw: ev });
    }

    _afPlaybackRunning = false;
    if (_afPlaybackTimer) {
        clearTimeout(_afPlaybackTimer);
        _afPlaybackTimer = null;
    }

    mc.innerHTML = `
        <div class="fm-panel match-viewer" style="position:relative;overflow:hidden;">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
                <div>
                    <div class="fm-eyebrow" style="color:var(--af-primary-light);">Match Replay</div>
                    <h2 style="margin:4px 0;">${cmEscapeHtml(match.homeTeamName)} vs ${cmEscapeHtml(match.awayTeamName)}</h2>
                </div>
                <button class="back-button" onclick="afStopPlayback();loadPage('dashboard')">← Dashboard</button>
            </div>

            <div class="scoreboard">
                <div class="score-team">
                    <div class="score-team-name">${cmEscapeHtml(match.homeTeamName || 'Home')}</div>
                    <div class="score-value" id="af-live-home-score">0</div>
                </div>
                <div class="score-vs">
                    VS
                    <div id="af-live-clock" style="font-size:0.9rem;font-weight:700;margin-top:4px;color:#f5a623;">Q1 0:00</div>
                </div>
                <div class="score-team away">
                    <div class="score-team-name">${cmEscapeHtml(match.awayTeamName || 'Away')}</div>
                    <div class="score-value" id="af-live-away-score">0</div>
                </div>
            </div>

            <div class="qtr-scores">
                ${quarterLabels.map((ql, i) => `
                    <div class="qtr-score">
                        <div class="qtr-label">${ql}</div>
                        <div class="qtr-home">${homeQS[i] ?? 0}</div>
                        <div class="qtr-away">${awayQS[i] ?? 0}</div>
                    </div>
                `).join('')}
            </div>

            <div style="display:flex;gap:12px;margin-bottom:12px;">
                <span class="tab-btn active" onclick="afSwitchMatchTab('stats', this)">📊 Stats</span>
                <span class="tab-btn" onclick="afSwitchMatchTab('feed', this)">📜 Play-by-Play</span>
            </div>

            <div id="match-stats-tab" class="match-tab">
                <div class="side-stats-grid">
                    <div class="side-stats-home">
                        <h4 style="text-align:center;color:var(--af-primary-light);margin:0 0 8px;">${cmEscapeHtml(match.homeTeamName)}</h4>
                        <div style="overflow-x:auto;">
                            <table class="player-table stats-table" id="af-live-home-stats-table">
                                <thead><tr>
                                    <th data-sort="string" style="text-align:left;">Player</th>
                                    <th data-sort="string">Pos</th>
                                    <th data-sort="number">TD</th>
                                    <th data-sort="number">FG</th>
                                    <th data-sort="number">FGA</th>
                                    <th data-sort="number">Tkl</th>
                                    <th data-sort="number">Int</th>
                                    <th data-sort="number">Sack</th>
                                    <th data-sort="number">Pass</th>
                                    <th data-sort="number">Rush</th>
                                    <th data-sort="number">Rec</th>
                                </tr></thead>
                                <tbody></tbody>
                            </table>
                        </div>
                    </div>
                    <div class="side-stats-away">
                        <h4 style="text-align:center;color:var(--af-away);margin:0 0 8px;">${cmEscapeHtml(match.awayTeamName)}</h4>
                        <div style="overflow-x:auto;">
                            <table class="player-table stats-table" id="af-live-away-stats-table">
                                <thead><tr>
                                    <th data-sort="string" style="text-align:left;">Player</th>
                                    <th data-sort="string">Pos</th>
                                    <th data-sort="number">TD</th>
                                    <th data-sort="number">FG</th>
                                    <th data-sort="number">FGA</th>
                                    <th data-sort="number">Tkl</th>
                                    <th data-sort="number">Int</th>
                                    <th data-sort="number">Sack</th>
                                    <th data-sort="number">Pass</th>
                                    <th data-sort="number">Rush</th>
                                    <th data-sort="number">Rec</th>
                                </tr></thead>
                                <tbody></tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>

            <div id="match-feed-tab" class="match-tab" style="display:none;">
                <div id="af-live-event-feed" class="event-feed">
                    <div class="match-event" style="color:#f5a623;">Kickoff...</div>
                </div>
            </div>
        </div>`;

    if (match.played && match.homeScore > 0) {
        const homeLiveStats = buildLiveStats(homeStatsArr);
        const awayLiveStats = buildLiveStats(awayStatsArr);
        homeStatsArr.forEach(s => {
            const ls = homeLiveStats[s.playerId];
            if (ls) { Object.assign(ls, s); }
        });
        awayStatsArr.forEach(s => {
            const ls = awayLiveStats[s.playerId];
            if (ls) { Object.assign(ls, s); }
        });
        renderFinalStats(homeLiveStats, 'af-live-home-stats-table');
        renderFinalStats(awayLiveStats, 'af-live-away-stats-table');
        document.getElementById('af-live-home-score').textContent = match.homeScore;
        document.getElementById('af-live-away-score').textContent = match.awayScore;
        const feed = document.getElementById('af-live-event-feed');
        if (feed) {
            feed.innerHTML = '<div class="match-event" style="color:#6fcf97;font-weight:700;">Final: ' + match.homeScore + ' - ' + match.awayScore + '</div>';
        }
        setTimeout(() => {
            const ht = document.getElementById('af-live-home-stats-table');
            const at = document.getElementById('af-live-away-stats-table');
            if (ht) afMakeSortable(ht);
            if (at) afMakeSortable(at);
            afSortTableByColumn(ht, 8, 'desc');
            afSortTableByColumn(at, 8, 'desc');
        }, 100);
    } else {
        afPlaybackStart(parsedEvents, homeStatsArr, awayStatsArr);
        setTimeout(() => {
            const ht = document.getElementById('af-live-home-stats-table');
            const at = document.getElementById('af-live-away-stats-table');
            if (ht) afMakeSortable(ht);
            if (at) afMakeSortable(at);
            afSortTableByColumn(ht, 8, 'desc');
            afSortTableByColumn(at, 8, 'desc');
        }, 100);
    }
}

function buildLiveStats(statsArr) {
    const map = {};
    for (const s of statsArr) {
        map[s.playerId] = {
            playerId: s.playerId, playerName: s.playerName, position: s.position,
            touchdowns: 0, fieldGoalsMade: 0, fieldGoalsAttempted: 0,
            tackles: 0, interceptions: 0, sacks: 0,
            passingYards: 0, rushingYards: 0, receivingYards: 0,
            passingTouchdowns: 0, rushingTouchdowns: 0, receivingTouchdowns: 0, fumbles: 0, twoPointConversions: 0
        };
    }
    return map;
}

function renderFinalStats(statsMap, tableId) {
    const tbody = document.querySelector('#' + tableId + ' tbody');
    if (!tbody) return;
    const players = Object.values(statsMap);
    const totals = players.reduce((acc, p) => {
        acc.touchdowns += p.touchdowns || 0;
        acc.fieldGoalsMade += p.fieldGoalsMade || 0;
        acc.fieldGoalsAttempted += p.fieldGoalsAttempted || 0;
        acc.tackles += p.tackles || 0;
        acc.interceptions += p.interceptions || 0;
        acc.sacks += p.sacks || 0;
        acc.passingYards += p.passingYards || 0;
        acc.rushingYards += p.rushingYards || 0;
        acc.receivingYards += p.receivingYards || 0;
        return acc;
    }, { touchdowns: 0, fieldGoalsMade: 0, fieldGoalsAttempted: 0, tackles: 0, interceptions: 0, sacks: 0, passingYards: 0, rushingYards: 0, receivingYards: 0 });

    tbody.innerHTML = players.map(ps => {
        const pid = ps.playerId;
        return `<tr>
            <td style="text-align:left;color:#99a6bb;font-size:0.78rem;font-weight:600;">${cmEscapeHtml(ps.playerName || '')}</td>
            <td><span class="pos-badge ${ps.position || ''}" style="font-size:0.5rem;padding:1px 5px;">${cmEscapeHtml(ps.position || '')}</span></td>
            <td>${ps.touchdowns || 0}</td>
            <td>${ps.fieldGoalsMade || 0}</td>
            <td>${ps.fieldGoalsAttempted || 0}</td>
            <td>${ps.tackles || 0}</td>
            <td>${ps.interceptions || 0}</td>
            <td>${ps.sacks || 0}</td>
            <td style="font-weight:700;">${ps.passingYards || 0}</td>
            <td style="font-weight:700;">${ps.rushingYards || 0}</td>
            <td style="font-weight:700;">${ps.receivingYards || 0}</td>
        </tr>`;
    }).join('') + `<tr style="font-weight:900;background:rgba(46,133,255,0.08);border-top:2px solid var(--af-primary);">
        <td style="text-align:left;color:var(--af-primary-light);">TOTAL</td>
        <td></td>
        <td>${totals.touchdowns}</td>
        <td>${totals.fieldGoalsMade}</td>
        <td>${totals.fieldGoalsAttempted}</td>
        <td>${totals.tackles}</td>
        <td>${totals.interceptions}</td>
        <td>${totals.sacks}</td>
        <td style="font-weight:900;">${totals.passingYards}</td>
        <td style="font-weight:900;">${totals.rushingYards}</td>
        <td style="font-weight:900;">${totals.receivingYards}</td>
    </tr>`;
}

function afStopPlayback() {
    _afPlaybackRunning = false;
    if (_afPlaybackTimer) {
        clearTimeout(_afPlaybackTimer);
        _afPlaybackTimer = null;
    }
}

function afPlaybackStart(events, homeStatsArr, awayStatsArr) {
    _afPlaybackRunning = true;
    const feed = document.getElementById('af-live-event-feed');
    const homeScoreEl = document.getElementById('af-live-home-score');
    const awayScoreEl = document.getElementById('af-live-away-score');
    const clockEl = document.getElementById('af-live-clock');

    let homeScore = 0, awayScore = 0;

    const homeLiveStats = buildLiveStats(homeStatsArr);
    const awayLiveStats = buildLiveStats(awayStatsArr);

    function liveStat(pid) { return homeLiveStats[pid] || awayLiveStats[pid]; }

    function updateStatsTables() {
        function renderRows(statsMap, tableId) {
            const tbody = document.querySelector('#' + tableId + ' tbody');
            if (!tbody) return;
            const players = Object.values(statsMap);
            const totals = players.reduce((acc, p) => {
                acc.touchdowns += p.touchdowns || 0;
                acc.fieldGoalsMade += p.fieldGoalsMade || 0;
                acc.fieldGoalsAttempted += p.fieldGoalsAttempted || 0;
                acc.tackles += p.tackles || 0;
                acc.interceptions += p.interceptions || 0;
                acc.sacks += p.sacks || 0;
                acc.passingYards += p.passingYards || 0;
                acc.rushingYards += p.rushingYards || 0;
                acc.receivingYards += p.receivingYards || 0;
                return acc;
            }, { touchdowns: 0, fieldGoalsMade: 0, fieldGoalsAttempted: 0, tackles: 0, interceptions: 0, sacks: 0, passingYards: 0, rushingYards: 0, receivingYards: 0 });

            tbody.innerHTML = players.map(ps => {
                const pid = ps.playerId;
                return `<tr>
                    <td style="text-align:left;color:#99a6bb;font-size:0.78rem;font-weight:600;">${cmEscapeHtml(ps.playerName || '')}</td>
                    <td><span class="pos-badge ${ps.position || ''}" style="font-size:0.5rem;padding:1px 5px;">${cmEscapeHtml(ps.position || '')}</span></td>
                    <td>${ps.touchdowns || 0}</td>
                    <td>${ps.fieldGoalsMade || 0}</td>
                    <td>${ps.fieldGoalsAttempted || 0}</td>
                    <td>${ps.tackles || 0}</td>
                    <td>${ps.interceptions || 0}</td>
                    <td>${ps.sacks || 0}</td>
                    <td style="font-weight:700;">${ps.passingYards || 0}</td>
                    <td style="font-weight:700;">${ps.rushingYards || 0}</td>
                    <td style="font-weight:700;">${ps.receivingYards || 0}</td>
                </tr>`;
            }).join('') + `<tr style="font-weight:900;background:rgba(46,133,255,0.08);border-top:2px solid var(--af-primary);">
                <td style="text-align:left;color:var(--af-primary-light);">TOTAL</td>
                <td></td>
                <td>${totals.touchdowns}</td>
                <td>${totals.fieldGoalsMade}</td>
                <td>${totals.fieldGoalsAttempted}</td>
                <td>${totals.tackles}</td>
                <td>${totals.interceptions}</td>
                <td>${totals.sacks}</td>
                <td style="font-weight:900;">${totals.passingYards}</td>
                <td style="font-weight:900;">${totals.rushingYards}</td>
                <td style="font-weight:900;">${totals.receivingYards}</td>
            </tr>`;
        }
        renderRows(homeLiveStats, 'af-live-home-stats-table');
        renderRows(awayLiveStats, 'af-live-away-stats-table');
        const ht = document.getElementById('af-live-home-stats-table');
        const at = document.getElementById('af-live-away-stats-table');
        if (ht) afMakeSortable(ht);
        if (at) afMakeSortable(at);
        afReapplySort(ht);
        afReapplySort(at);
    }

    function formatEvent(ev) {
        const scorePrefix = `${homeScore} - ${awayScore} `;
        const evColor = ev.team === 'home' ? 'var(--af-primary-light)' : 'var(--af-away)';
        let suffix = '';
        if (ev.pts > 0) {
            suffix = ` <span style="color:#6fcf97;font-weight:700;">+${ev.pts}</span>`;
        }
        let detailHtml = ev.detail ? ` <span style="color:#99a6bb;">${cmEscapeHtml(ev.detail)}</span>` : '';
        let nameHtml = ev.p1Name ? `<span style="color:${evColor};font-weight:700;">${cmEscapeHtml(ev.p1Name)}</span>` : '';
        const link = nameHtml ? ` — ${nameHtml}` : '';
        return scorePrefix + ev.timeStr + link + detailHtml + suffix;
    }

    function applyEvent(ev) {
        const s = liveStat(ev.p1Id);
        if (!s && (ev.type === 'TD' || ev.type === 'FG')) {
            if (ev.team === 'home') homeScore += ev.pts;
            else awayScore += ev.pts;
            updateStatsTables();
            return;
        }
        if (!s) return;

        switch (ev.type) {
            case 'RUN':
                s.rushingYards += parseInt(ev.raw.split('|')[4]) || 0;
                break;
            case 'PASS': {
                const parts = ev.raw.split('|');
                const gain = parseInt(parts[6]) || 0;
                s.passingYards += gain;
                const recId = parseInt(parts[4]) || 0;
                const rec = liveStat(recId);
                if (rec) rec.receivingYards += gain;
                break;
            }
            case 'SACK':
                s.fumbles += 1;
                break;
            case 'FG': {
                s.fieldGoalsAttempted += 1;
                const made = parseInt(ev.raw.split('|')[4]) || 0;
                if (made > 0) {
                    s.fieldGoalsMade += 1;
                }
                break;
            }
            case 'TD': {
                s.touchdowns += 1;
                s.receivingTouchdowns += 1;
                break;
            }
        }
        if (ev.pts > 0) {
            if (ev.team === 'home') homeScore += ev.pts;
            else awayScore += ev.pts;
        }
        updateStatsTables();
    }

    function updateScoreboard() {
        if (homeScoreEl) homeScoreEl.textContent = homeScore;
        if (awayScoreEl) awayScoreEl.textContent = awayScore;
        if (clockEl) clockEl.textContent = events.length > 0 && idx < events.length
            ? events[idx].timeStr
            : (events.length > 0 ? events[events.length - 1].timeStr : 'Q1 0:00');
        updateStatsTables();
    }

    let idx = 0;
    let isFirstEvent = true;
    function playNext() {
        if (!_afPlaybackRunning || idx >= events.length) {
            if (idx >= events.length && feed) {
                const el = document.createElement('div');
                el.className = 'match-event';
                el.style.cssText = 'color:#6fcf97;font-weight:700;margin-top:12px;';
                el.textContent = 'Final: ' + homeScore + ' - ' + awayScore;
                feed.appendChild(el);
                feed.scrollTop = feed.scrollHeight;
                updateScoreboard();
            }
            _afPlaybackRunning = false;
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
        let delay = ev.pts > 0 ? 400 : 180;
        if (isFirstEvent) {
            delay = 2000;
            isFirstEvent = false;
        }
        _afPlaybackTimer = setTimeout(playNext, delay);
    }

    updateScoreboard();
    playNext();
}

function afSwitchMatchTab(tab, el) {
    document.querySelectorAll('.match-tab').forEach(t => t.style.display = 'none');
    document.querySelectorAll('.tab-btn').forEach(t => t.classList.remove('active'));
    document.getElementById('match-' + tab + '-tab').style.display = 'block';
    if (el) el.classList.add('active');
}

// ─── Sortable tables ───

function afSaveSortState(tableId, colIdx, dir) {
    const side = tableId.includes('home') ? 'home' : 'away';
    _afLiveSortState[side] = { colIdx, dir };
}

function afGetSortState(tableId) {
    const side = tableId.includes('home') ? 'home' : 'away';
    return _afLiveSortState[side] || { colIdx: 8, dir: 'desc' };
}

function afReapplySort(tableEl) {
    if (!tableEl) return;
    const tableId = tableEl.id;
    const state = afGetSortState(tableId);
    afSortTableByColumn(tableEl, state.colIdx, state.dir);
}

function afMakeSortable(tableEl) {
    if (!tableEl) return;
    const thead = tableEl.querySelector('thead');
    if (!thead) return;
    const ths = thead.querySelectorAll('th');
    ths.forEach(th => {
        if (!th.hasAttribute('data-sort')) return;
        th.style.cursor = 'pointer';
        th.addEventListener('click', () => {
            const colIdx = Array.from(ths).indexOf(th);
            const type = th.getAttribute('data-sort') || 'string';
            const tbody = tableEl.querySelector('tbody');
            if (!tbody) return;
            const rows = Array.from(tbody.querySelectorAll('tr'));
            const totalRow = rows.find(r => r.cells[0]?.textContent.trim() === 'TOTAL');
            const sortableRows = totalRow ? rows.filter(r => r !== totalRow) : rows;
            const currentDir = th.getAttribute('data-dir') || '';
            const dir = currentDir === 'asc' ? 'desc' : 'asc';

            ths.forEach(h => h.removeAttribute('data-dir'));
            th.setAttribute('data-dir', dir);
            ths.forEach(h => { h.innerHTML = h.innerHTML.replace(/ [▲▼]$/, ''); });
            th.innerHTML += dir === 'asc' ? ' ▲' : ' ▼';

            sortableRows.sort((a, b) => {
                const ca = a.cells[colIdx]?.textContent.trim() || '';
                const cb = b.cells[colIdx]?.textContent.trim() || '';
                let va, vb;
                if (type === 'number') {
                    va = parseFloat(ca) || 0;
                    vb = parseFloat(cb) || 0;
                } else {
                    va = ca.toLowerCase();
                    vb = cb.toLowerCase();
                }
                return dir === 'asc'
                    ? (va > vb ? 1 : va < vb ? -1 : 0)
                    : (va < vb ? 1 : va > vb ? -1 : 0);
            });
            [...sortableRows, ...(totalRow ? [totalRow] : [])].forEach(r => tbody.appendChild(r));
            afSaveSortState(tableEl.id, colIdx, dir);
        });
    });
}

function afSortTableByColumn(tableEl, colIdx, dir) {
    if (!tableEl) return;
    const thead = tableEl.querySelector('thead');
    if (!thead) return;
    const ths = thead.querySelectorAll('th');
    if (colIdx >= ths.length) return;
    const th = ths[colIdx];
    const type = th.getAttribute('data-sort') || 'string';
    const tbody = tableEl.querySelector('tbody');
    if (!tbody) return;
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const totalRow = rows.find(r => r.cells[0]?.textContent.trim() === 'TOTAL');
    const sortableRows = totalRow ? rows.filter(r => r !== totalRow) : rows;

    ths.forEach(h => h.removeAttribute('data-dir'));
    th.setAttribute('data-dir', dir);
    ths.forEach(h => { h.innerHTML = h.innerHTML.replace(/ [▲▼]$/, ''); });
    th.innerHTML += dir === 'asc' ? ' ▲' : ' ▼';

    sortableRows.sort((a, b) => {
        const ca = a.cells[colIdx]?.textContent.trim() || '';
        const cb = b.cells[colIdx]?.textContent.trim() || '';
        let va, vb;
        if (type === 'number') {
            va = parseFloat(ca) || 0;
            vb = parseFloat(cb) || 0;
        } else {
            va = ca.toLowerCase();
            vb = cb.toLowerCase();
        }
        return dir === 'asc'
            ? (va > vb ? 1 : va < vb ? -1 : 0)
            : (va < vb ? 1 : va > vb ? -1 : 0);
    });
    [...sortableRows, ...(totalRow ? [totalRow] : [])].forEach(r => tbody.appendChild(r));
}

window.afRenderMatchViewer = afRenderMatchViewer;
window.afSwitchMatchTab = afSwitchMatchTab;
window.afStopPlayback = afStopPlayback;

async function afPlayAndShowMatch(fixtureId) {
    await window.loadPage('matchViewer', { fixtureId });
}

window.afPlayAndShowMatch = afPlayAndShowMatch;
