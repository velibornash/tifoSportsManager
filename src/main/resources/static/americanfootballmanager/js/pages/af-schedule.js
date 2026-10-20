async function afRenderSchedule() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.afTeams.length) await window.afInit();

    let fixtures, matches, roundStatus;
    try {
        [fixtures, roundStatus] = await Promise.all([
            window.afFetchTeamFixtures(window.afCurrentTeamId),
            window.AF_CONF.competitionId
                ? window.authFetch('/api/af/fixtures/round/' + window.AF_CONF.competitionId + '/status?seasonYear=' + window.AF_CONF.seasonYear).then(r => r.json())
                : Promise.resolve(null)
        ]);
    } catch { fixtures = []; roundStatus = null; }

    try { matches = await window.afFetchTeamMatches(window.afCurrentTeamId); } catch { matches = []; }

    const currentRound = roundStatus?.currentRound || 1;
    const roundsMap = roundStatus?.rounds || {};

    const rounds = {};
    for (const f of fixtures) {
        const rn = f.roundNumber;
        if (!rounds[rn]) rounds[rn] = [];
        if (f.played) {
            const match = matches.find(m =>
                m.homeTeamId === f.homeTeamId && m.awayTeamId === f.awayTeamId);
            if (match) {
                const dupIdx = rounds[rn].findIndex(x =>
                    x.homeTeamId === f.homeTeamId && x.awayTeamId === f.awayTeamId);
                if (dupIdx >= 0) rounds[rn].splice(dupIdx, 1);
                rounds[rn].push({
                    ...f,
                    homeScore: match.homeScore,
                    awayScore: match.awayScore,
                    playedMatchId: match.id,
                    _match: match,
                });
                continue;
            }
        }
        const existing = rounds[rn].find(x =>
            x.homeTeamId === f.homeTeamId && x.awayTeamId === f.awayTeamId && x.played);
        if (!existing) rounds[rn].push(f);
    }

    const roundKeys = Object.keys(rounds).map(Number).sort((a, b) => a - b);

    mc.innerHTML = `
        <div class="fm-panel" style="max-width:800px;margin:0 auto;">
            <div style="margin-bottom:8px;">
                <button class="back-btn" onclick="window.loadPage('dashboard')">← Back to Dashboard</button>
            </div>
            <h2 style="color:var(--af-primary);margin-bottom:4px;">Schedule</h2>
            <div class="fm-eyebrow" style="margin-bottom:20px;">
                ${cmEscapeHtml(window.AF_CONF.teamName || 'My Team')} — ${window.AF_CONF.seasonYear}
            </div>
            ${roundKeys.map(rn => {
                const games = rounds[rn];
                const rInfo = roundsMap[String(rn)];
                const locked = rInfo?.locked || false;
                const playedCount = games.filter(g => g.played).length;
                const totalInRound = games.length;
                const isCurrent = rn === currentRound;

                return `
                    <div class="schedule-round ${isCurrent ? 'current-round' : ''}" style="margin-bottom:16px;">
                        <div style="display:flex;align-items:center;justify-content:space-between;padding:8px 0;border-bottom:1px solid ${isCurrent ? 'rgba(88,166,18,0.3)' : 'rgba(255,255,255,0.06)'};">
                            <h3 style="color:${isCurrent ? 'var(--af-primary-light)' : '#99a6bb'};font-size:0.85rem;letter-spacing:0.5px;">
                                Round ${rn}
                                ${locked ? '<span style="color:#e74c3c;font-size:0.65rem;margin-left:4px;">🔒</span>' : ''}
                                ${playedCount > 0 ? `<span style="color:#99a6bb;font-size:0.65rem;font-weight:400;"> (${playedCount}/${totalInRound})</span>` : ''}
                            </h3>
                        </div>
                        ${games.map(g => {
                            const isHome = g.homeTeamId === Number(window.afCurrentTeamId);
                            const opponent = isHome ? g.awayTeamName : g.homeTeamName;
                            const played = g.played;
                            const score = played && g.homeScore != null
                                ? `${g.homeScore} : ${g.awayScore}`
                                : '—';
                            const date = cmFormatDate(g.matchDate);
                            const matchId = g.playedMatchId ? g.playedMatchId : g.id;
                            if (played) {
                                const isHomeWin = g.homeScore > g.awayScore;
                                const won = isHome ? isHomeWin : !isHomeWin;
                                return `
                                    <div class="match-row ${locked ? 'locked-row' : ''} ${won ? '' : ''}" onclick="window.loadPage('matchViewer', { fixtureId: ${matchId}, showQuarters: true })">
                                        <div class="match-teams">
                                            <span class="result-badge ${won ? 'win' : 'loss'}">${won ? 'W' : 'L'}</span>
                                            <span style="font-weight:600;">${cmEscapeHtml(opponent)}</span>
                                        </div>
                                        <div class="match-score-stack">
                                            <span class="score" style="color:#f5a623;">${score}</span>
                                            <span class="match-date-small">${date}</span>
                                        </div>
                                    </div>`;
                            }
                            if (locked) {
                                return `
                                    <div class="match-row locked-row">
                                        <div class="match-teams">
                                            <span style="font-weight:600;">${cmEscapeHtml(opponent)}</span>
                                        </div>
                                        <div class="match-score-stack">
                                            <span style="color:#5a6a7a;">—</span>
                                            <span class="match-date-small">${date}</span>
                                        </div>
                                    </div>`;
                            }
                            return `
                                <div class="match-row clickable" onclick="window.afPlayAndShowMatch(${g.id})">
                                    <div class="match-teams">
                                        <span style="font-weight:600;">${cmEscapeHtml(opponent)}</span>
                                    </div>
                                    <div class="match-score-stack">
                                        <span class="play-btn">▶ PLAY</span>
                                        <span class="match-date-small">${date}</span>
                                    </div>
                                </div>`;
                        }).join('')}
                    </div>`;
            }).join('') || cmBuildEmptyState('No fixtures')}
        </div>`;
}

async function afPlayAllInRound(roundNumber) {
    const compId = window.AF_CONF.competitionId;
    if (!compId) return;

    const btn = document.querySelector('.play-all-btn');
    if (btn) { btn.disabled = true; btn.textContent = 'Playing...'; }

    try {
        const res = await window.authFetch(
            `/api/af/fixtures/round/${compId}/play-all?roundNumber=${roundNumber}&seasonYear=${window.AF_CONF.seasonYear}`,
            { method: 'POST' });
        const data = await res.json();
        window.afInvalidateCache();

        if (btn) {
            btn.textContent = `✅ Played ${data.played}/${data.total}`;
            setTimeout(() => { window.loadPage('schedule'); }, 1500);
        }
    } catch (e) {
        console.error('Play all failed', e);
        if (btn) { btn.disabled = false; btn.textContent = '▶ Play All Round ' + roundNumber; }
    }
}

window.afRenderSchedule = afRenderSchedule;
window.afPlayAllInRound = afPlayAllInRound;
