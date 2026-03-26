// tifo.js - Clean Sheet Text Mode (in-memory)

let gameState = null;
// Store all round results (keyed by round) so schedule fixtures can link to any match
let allRoundResults = {};
let csSessionClosed = false;
let csTeamNameToId = new Map();
let csPlayerNameToEntries = new Map();
let csPlayerIndexLoaded = false;
let csPlayerIdToTeamId = new Map();
let inboxUnreadCount = 0;
let inboxSelectedIndex = null;
let inboxActiveFilter = 'all';
let inboxSortMode = 'newest';
let currentPage = null;

// --- Auth helper ---
async function csApi(url, options = {}) {
    const token = localStorage.getItem('token');
    if (!token) { window.location.href = '/login.html'; return null; }
    const headers = { ...options.headers, 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' };
    const res = await fetch(url, { ...options, headers });
    if (res.status === 401) { localStorage.removeItem('token'); window.location.href = '/login.html'; return null; }
    return res;
}

// --- Init ---
document.addEventListener('DOMContentLoaded', async () => {
    const token = localStorage.getItem('token');
    if (!token) { window.location.href = '/login.html'; return; }

    const res = await csApi('/api/cs/state');
    if (!res) return;
    const data = await res.json();

    if (data.active) {
        gameState = data;
        rebuildRoundResultsFromSchedule();
        rebuildTeamIndex();
        ensurePlayerIndexLoaded();
        inboxUnreadCount = (gameState?.inbox || []).filter(msg => !msg?.read).length;
        updateInboxRibbon();
        updateRoundInfo();
        renderPage('clubInfo');
    } else {
        const startRes = await csApi('/api/cs/start', { method: 'POST' });
        if (!startRes || !startRes.ok) {
	            let errorMessage = 'Could not start Clean Sheet mode.';
	            try {
	                const payload = startRes ? await startRes.json() : null;
	                errorMessage = payload?.error || payload?.message || errorMessage;
	            } catch {
	                // keep fallback copy
	            }
	            document.getElementById('main-content').innerHTML = `<div class="manager-card"><h2>Error starting game</h2><p>${escapeHtml(errorMessage)}</p></div>`;
            return;
        }
        gameState = await startRes.json();
        rebuildRoundResultsFromSchedule();
        rebuildTeamIndex();
        ensurePlayerIndexLoaded();
        inboxUnreadCount = (gameState?.inbox || []).filter(msg => !msg?.read).length;
        updateInboxRibbon();
        updateRoundInfo();
        renderPage('inbox');
    }
});

function updateRoundInfo() {
    const el = document.getElementById('roundInfo');
    if (el && gameState) {
        el.textContent = `Round ${gameState.currentRound} / ${gameState.totalRounds} | Season ${gameState.seasonYear}/${gameState.seasonYear + 1}`;
    }
}

function getLeagueDisplayName() {
    return gameState?.leagueName || 'League';
}

function rebuildRoundResultsFromSchedule() {
    allRoundResults = {};
    (gameState?.schedule || []).forEach(fixture => {
        if (!fixture?.played || !fixture?.result) return;
        const round = Number(fixture.result.round || fixture.round || 0);
        if (!round) return;
        const normalized = {
            ...fixture.result,
            round,
            homeTeamId: fixture.result.homeTeamId ?? fixture.homeTeamId,
            awayTeamId: fixture.result.awayTeamId ?? fixture.awayTeamId,
            homeTeamName: fixture.result.homeTeamName ?? fixture.homeTeamName,
            awayTeamName: fixture.result.awayTeamName ?? fixture.awayTeamName
        };
        if (!allRoundResults[round]) allRoundResults[round] = [];
        const exists = allRoundResults[round].some(m =>
            Number(m.homeTeamId) === Number(normalized.homeTeamId)
            && Number(m.awayTeamId) === Number(normalized.awayTeamId)
        );
        if (!exists) allRoundResults[round].push(normalized);
    });
}

function applyRoundResultsToSchedule(results) {
    if (!Array.isArray(gameState?.schedule) || !Array.isArray(results)) return;
    results.forEach(result => {
        const fixture = gameState.schedule.find(f =>
            Number(f.round) === Number(result.round)
            && Number(f.homeTeamId) === Number(result.homeTeamId)
            && Number(f.awayTeamId) === Number(result.awayTeamId)
        );
        if (fixture) {
            fixture.played = true;
            fixture.result = result;
        }
    });
    rebuildRoundResultsFromSchedule();
}

function updateInboxRibbon() {
    const suffix = inboxUnreadCount > 0 ? ` (${inboxUnreadCount})` : '';
    const desktop = document.getElementById('inboxBtnDesktop');
    const mobile = document.getElementById('inboxBtnMobile');
    if (desktop) {
        desktop.textContent = `Inbox${suffix}`;
        desktop.classList.toggle('cs-inbox-unread', inboxUnreadCount > 0);
    }
    if (mobile) {
        mobile.textContent = `Inbox${suffix}`;
        mobile.classList.toggle('cs-inbox-unread', inboxUnreadCount > 0);
    }
}

function rebuildTeamIndex() {
    csTeamNameToId = new Map();
    (gameState?.leagueTable || []).forEach(t => {
        if (t?.teamName && t?.teamId != null) {
            csTeamNameToId.set(t.teamName, t.teamId);
        }
    });
    if (gameState?.userTeam?.name && gameState?.userTeam?.id != null) {
        csTeamNameToId.set(gameState.userTeam.name, gameState.userTeam.id);
    }
}

async function ensurePlayerIndexLoaded(force = false) {
    if (csPlayerIndexLoaded && !force) return;
    csPlayerNameToEntries = new Map();
    csPlayerIdToTeamId = new Map();
    const teamIds = [...new Set((gameState?.leagueTable || []).map(t => t.teamId).filter(Boolean))];
    if (!teamIds.length) {
        csPlayerIndexLoaded = true;
        return;
    }

    const results = await Promise.all(teamIds.map(async teamId => {
        try {
            const res = await csApi(`/api/cs/team/${teamId}`);
            if (!res || !res.ok) return null;
            return await res.json();
        } catch {
            return null;
        }
    }));

    results.filter(Boolean).forEach(data => {
        const teamId = data.team?.id;
        (data.roster || []).forEach(p => {
            if (!p?.name || !p?.id || !teamId) return;
            if (!csPlayerNameToEntries.has(p.name)) csPlayerNameToEntries.set(p.name, []);
            csPlayerNameToEntries.get(p.name).push({ playerId: p.id, teamId });
            csPlayerIdToTeamId.set(p.id, teamId);
        });
    });

    csPlayerIndexLoaded = true;
}

// --- Navigation ---
function renderPage(page) {
    currentPage = page;
    const main = document.getElementById('main-content');
    main.innerHTML = '';
    closeDesktopSidebars();

    const card = document.createElement('div');
    card.className = 'manager-card';
    main.appendChild(card);

    switch (page) {
        case 'inbox':
            renderInbox(card);
            break;
        case 'players': renderPlayers(card); break;
        case 'tactics': renderTactics(card); break;
        case 'leagueTable': renderLeagueTable(card); break;
        case 'schedule': renderSchedule(card); break;
        case 'matches': renderMatches(card); break;
        case 'internationalCentre': renderInternationalCentre(card); break;
        case 'clubInfo': renderClubInfo(card); break;
        case 'topScorers': renderTopScorers(card); break;
        case 'topAssists': renderTopAssists(card); break;
        case 'transfers': renderTransfers(card); break;
        default: card.innerHTML = '<h2>Select a category</h2>';
    }
}

// --- Modal helpers ---
function showModal(title, bodyHtml) {
    closeModal();
    const overlay = document.createElement('div');
    overlay.className = 'cs-modal-overlay';
    overlay.id = 'csModalOverlay';
    overlay.onclick = (e) => { if (e.target === overlay) closeModal(); };
    overlay.innerHTML = `<div class="cs-modal">
        <button class="cs-modal-close" onclick="tifoCloseModal()">&times;</button>
        <h3>${title}</h3>
        <div>${bodyHtml}</div>
    </div>`;
    document.body.appendChild(overlay);
}

function closeModal() {
    const existing = document.getElementById('csModalOverlay');
    if (existing) existing.remove();
}

function formatMoney(value) {
    return `€${Number(value || 0).toLocaleString()}`;
}

function ordinal(value) {
    const n = Number(value || 0);
    if (!n) return '-';
    const mod100 = n % 100;
    if (mod100 >= 11 && mod100 <= 13) return `${n}th`;
    switch (n % 10) {
        case 1: return `${n}st`;
        case 2: return `${n}nd`;
        case 3: return `${n}rd`;
        default: return `${n}th`;
    }
}

function getUserTableEntry() {
    const teamId = gameState?.userTeam?.id;
    return (gameState?.leagueTable || []).find(t => Number(t.teamId) === Number(teamId)) || null;
}

function findNextFixture() {
    const teamId = gameState?.userTeam?.id;
    return (gameState?.schedule || []).find(f =>
        !f.played && (Number(f.homeTeamId) === Number(teamId) || Number(f.awayTeamId) === Number(teamId))
    ) || null;
}

function findLatestUserMatch() {
    const matches = gameState?.matchHistory || [];
    return matches.length ? matches[matches.length - 1] : null;
}

function getUserMatchOutcome(match) {
    const userTeamId = gameState?.userTeam?.id;
    if (!match || userTeamId == null) return { code: '-', goalsFor: 0, goalsAgainst: 0 };
    const userHome = Number(match.homeTeamId) === Number(userTeamId);
    const goalsFor = userHome ? Number(match.homeGoals || 0) : Number(match.awayGoals || 0);
    const goalsAgainst = userHome ? Number(match.awayGoals || 0) : Number(match.homeGoals || 0);
    return {
        code: goalsFor > goalsAgainst ? 'W' : goalsFor === goalsAgainst ? 'D' : 'L',
        goalsFor,
        goalsAgainst
    };
}

function buildRecentForm(limit = 5) {
    return (gameState?.matchHistory || []).slice(-limit).map(match => ({
        round: match.round,
        ...getUserMatchOutcome(match)
    }));
}

function getPlayedClubMatches() {
    return Array.isArray(gameState?.matchHistory) ? gameState.matchHistory : [];
}

function getOpponentForUserMatch(match) {
    const userTeamId = Number(gameState?.userTeam?.id);
    if (!match || !userTeamId) return null;
    return Number(match.homeTeamId) === userTeamId
        ? { id: match.awayTeamId, name: match.awayTeamName, home: true }
        : { id: match.homeTeamId, name: match.homeTeamName, home: false };
}

function findTableEntryByTeamId(teamId) {
    return (gameState?.leagueTable || []).find(entry => Number(entry.teamId) === Number(teamId)) || null;
}

function deterministicNoise(...values) {
    const source = values.map(value => String(value ?? '')).join('|');
    let hash = 0;
    for (let i = 0; i < source.length; i += 1) {
        hash = ((hash << 5) - hash) + source.charCodeAt(i);
        hash |= 0;
    }
    return ((Math.abs(hash) % 1000) / 999) - 0.5;
}

function roundToNearestTen(value) {
    return Math.max(0, Math.round(Number(value || 0) / 10) * 10);
}

function formatAttendance(value) {
    const numeric = Number(value || 0);
    return numeric > 0 ? numeric.toLocaleString() : '-';
}

function estimateClubAttendance(match) {
    const team = gameState?.userTeam;
    if (!team || Number(match?.homeTeamId) !== Number(team.id)) return 0;

    const capacity = Math.max(2500, Number(team.stadiumCapacity || 6000));
    const ownReputation = Math.max(25, Number(team.reputation || 55)) / 100;
    const opponent = getOpponentForUserMatch(match);
    const opponentEntry = findTableEntryByTeamId(opponent?.id);
    const opponentPull = opponentEntry
        ? Math.max(0.18, ((gameState.leagueTable.length - Number(opponentEntry.position || 1) + 1) / Math.max(1, gameState.leagueTable.length)))
        : 0.42;
    const roundShare = Number(match?.round || 1) / Math.max(1, Number(gameState?.totalRounds || 1));
    const form = buildRecentForm(5);
    const wins = form.filter(item => item.code === 'W').length;
    const momentum = Math.min(0.08, wins * 0.015);
    const noise = deterministicNoise(match?.round, match?.homeTeamId, match?.awayTeamId, gameState?.seasonYear) * 0.08;

    const fill = Math.max(0.22, Math.min(0.96,
        0.4 + (ownReputation * 0.22) + (opponentPull * 0.16) + (roundShare * 0.08) + momentum + noise
    ));
    return roundToNearestTen(capacity * fill);
}

function buildClubGateMilestone() {
    const homeMatches = getPlayedClubMatches().filter(match => Number(match.homeTeamId) === Number(gameState?.userTeam?.id));
    if (!homeMatches.length) {
        return {
            averageAttendance: 0,
            highestAttendance: 0,
            lowestAttendance: 0,
            trend: 'No home gate data yet. Play a home match to open the turnstiles.',
            peakLabel: '-',
            lowLabel: '-'
        };
    }

    const estimated = homeMatches.map(match => ({
        match,
        attendance: estimateClubAttendance(match)
    }));
    const averageAttendance = Math.round(estimated.reduce((sum, item) => sum + item.attendance, 0) / estimated.length);
    const highest = estimated.reduce((best, item) => !best || item.attendance > best.attendance ? item : best, null);
    const lowest = estimated.reduce((best, item) => !best || item.attendance < best.attendance ? item : best, null);

    const midpoint = Math.max(1, Math.ceil(estimated.length / 2));
    const firstHalfAvg = estimated.slice(0, midpoint).reduce((sum, item) => sum + item.attendance, 0) / midpoint;
    const secondHalf = estimated.slice(midpoint);
    const secondHalfAvg = secondHalf.length
        ? secondHalf.reduce((sum, item) => sum + item.attendance, 0) / secondHalf.length
        : firstHalfAvg;

    let trend = `Crowd is holding around ${formatAttendance(averageAttendance)} at ${gameState?.userTeam?.stadiumName || 'home'}.`;
    if (secondHalfAvg >= firstHalfAvg * 1.06) {
        trend = 'Turnstiles are warming up as the campaign gathers pace.';
    } else if (averageAttendance >= Number(gameState?.userTeam?.stadiumCapacity || 6000) * 0.78) {
        trend = 'Home dates are starting to feel like packed-house occasions.';
    }

    return {
        averageAttendance,
        highestAttendance: highest?.attendance || 0,
        lowestAttendance: lowest?.attendance || 0,
        trend,
        peakLabel: highest ? `vs ${highest.match.awayTeamName}` : '-',
        lowLabel: lowest ? `vs ${lowest.match.awayTeamName}` : '-'
    };
}

function buildClubMilestoneSnapshot() {
    const matches = getPlayedClubMatches();
    const biggestWin = matches
        .filter(match => getUserMatchOutcome(match).goalsFor > getUserMatchOutcome(match).goalsAgainst)
        .sort((a, b) => {
            const aOutcome = getUserMatchOutcome(a);
            const bOutcome = getUserMatchOutcome(b);
            const marginDiff = (bOutcome.goalsFor - bOutcome.goalsAgainst) - (aOutcome.goalsFor - aOutcome.goalsAgainst);
            if (marginDiff !== 0) return marginDiff;
            return Number(b.round || 0) - Number(a.round || 0);
        })[0] || null;
    const biggestLoss = matches
        .filter(match => getUserMatchOutcome(match).goalsFor < getUserMatchOutcome(match).goalsAgainst)
        .sort((a, b) => {
            const aOutcome = getUserMatchOutcome(a);
            const bOutcome = getUserMatchOutcome(b);
            const marginDiff = (bOutcome.goalsAgainst - bOutcome.goalsFor) - (aOutcome.goalsAgainst - aOutcome.goalsFor);
            if (marginDiff !== 0) return marginDiff;
            return Number(b.round || 0) - Number(a.round || 0);
        })[0] || null;

    return {
        topScorer: null,
        topAssist: null,
        biggestWin,
        biggestLoss,
        gate: buildClubGateMilestone()
    };
}

function milestoneMatchHtml(title, match, emptyText) {
    if (!match) {
        return `<div class="cs-milestone-card"><div class="cs-section-label">${escapeHtml(title)}</div><div class="cs-milestone-value">-</div><div class="cs-milestone-meta">${escapeHtml(emptyText)}</div></div>`;
    }
    const outcome = getUserMatchOutcome(match);
    const opponent = getOpponentForUserMatch(match);
    return `<div class="cs-milestone-card">
        <div class="cs-section-label">${escapeHtml(title)}</div>
        <div class="cs-milestone-value">${outcome.goalsFor}-${outcome.goalsAgainst} vs ${escapeHtml(opponent?.name || '?')}</div>
        <div class="cs-milestone-meta">Round ${match.round} · ${Number(match.homeTeamId) === Number(gameState?.userTeam?.id) ? 'Home' : 'Away'} file</div>
    </div>`;
}

function buildClubMilestonesGridHtml(snapshot) {
    const gate = snapshot?.gate || {};
    return `
        <div class="cs-milestone-card">
            <div class="cs-section-label">Top scorer</div>
            <div class="cs-milestone-value">${escapeHtml(snapshot?.topScorer?.name || '-')}</div>
            <div class="cs-milestone-meta">${snapshot?.topScorer ? `${escapeHtml(snapshot.topScorer.teamName || 'No team')} · ${Number(snapshot.topScorer.goals || 0)} goals` : 'No goals filed yet.'}</div>
        </div>
        <div class="cs-milestone-card">
            <div class="cs-section-label">Top assist</div>
            <div class="cs-milestone-value">${escapeHtml(snapshot?.topAssist?.name || '-')}</div>
            <div class="cs-milestone-meta">${snapshot?.topAssist ? `${escapeHtml(snapshot.topAssist.teamName || 'No team')} · ${Number(snapshot.topAssist.assists || 0)} assists` : 'No assists filed yet.'}</div>
        </div>
        ${milestoneMatchHtml('Best win', snapshot?.biggestWin, 'No win on file yet.')}
        ${milestoneMatchHtml('Worst defeat', snapshot?.biggestLoss, 'No defeat on file yet.')}
        <div class="cs-milestone-card attendance">
            <div class="cs-section-label">Gate watch</div>
            <div class="cs-milestone-value">${formatAttendance(gate.averageAttendance)}</div>
            <div class="cs-milestone-meta">Peak ${formatAttendance(gate.highestAttendance)} ${escapeHtml(gate.peakLabel || '-')} · Low ${formatAttendance(gate.lowestAttendance)} ${escapeHtml(gate.lowLabel || '-')} · ${escapeHtml(gate.trend || 'No crowd read yet.')}</div>
        </div>`;
}

async function hydrateClubMilestones() {
    const host = document.getElementById('cs-club-milestones');
    if (!host) return;

    const snapshot = buildClubMilestoneSnapshot();
    try {
        const [scorersRes, assistsRes] = await Promise.all([
            csApi('/api/cs/top-scorers'),
            csApi('/api/cs/top-assists')
        ]);
        const scorers = scorersRes && scorersRes.ok ? await scorersRes.json() : [];
        const assists = assistsRes && assistsRes.ok ? await assistsRes.json() : [];
        snapshot.topScorer = Array.isArray(scorers) && scorers.length ? scorers[0] : null;
        snapshot.topAssist = Array.isArray(assists) && assists.length ? assists[0] : null;
    } catch (err) {
        console.warn('Club milestone fetch failed:', err);
    }

    const activeHost = document.getElementById('cs-club-milestones');
    if (activeHost) {
        activeHost.innerHTML = buildClubMilestonesGridHtml(snapshot);
    }
}

function getInboxTypeLabel(type) {
    return ({
        welcome: 'Chairman note',
        match: 'Match note',
        report: 'Match report',
        'round-report': 'Round review',
        international: 'International desk',
        finance: 'Finance office',
        message: 'Rumour mill',
        info: 'Club office',
        error: 'Alert',
        transfer: 'Transfer desk',
        board: 'Board room',
        press: 'Press room',
        fans: 'Fan pulse',
        media: 'Media watch',
        scout: 'Scout report',
        youth: 'Academy report',
        derby: 'Derby wire'
    })[type] || 'Inbox';
}

function getInboxSourceLabel(type) {
    return ({
        welcome: 'Chairman',
        match: 'Match Day',
        report: 'Analysis Desk',
        'round-report': 'League Office',
        international: 'World Desk',
        finance: 'Finance Office',
        message: 'Rumour Mill',
        info: 'Club Office',
        error: 'System',
        transfer: 'Transfer Desk',
        board: 'Board Room',
        press: 'Press Office',
        fans: 'Supporters',
        media: 'Media Watch',
        scout: 'Scouting',
        youth: 'Academy',
        derby: 'Rival Wire'
    })[type] || 'Inbox';
}

function getInboxHeadline(msg) {
    const lines = String(msg?.text || '').split('\n').map(v => v.trim()).filter(Boolean);
    return truncate(lines[0] || `${getInboxTypeLabel(msg?.type)} update`, 82);
}

function getInboxPreview(msg) {
    const lines = String(msg?.text || '').split('\n').map(v => v.trim()).filter(Boolean);
    const body = lines.length > 1 ? lines.slice(1).join(' ') : (lines[0] || 'No details filed.');
    return truncate(body, 160);
}

function getInboxIcon(type) {
    return ({
        welcome: '👔',
        match: '⚽',
        report: '📊',
        'round-report': '📈',
        international: '🌍',
        finance: '💷',
        message: '🗞️',
        info: 'ℹ️',
        error: '⚠️',
        transfer: '♻️',
        board: '🏛️',
        press: '🎙️',
        fans: '🧣',
        media: '📰',
        scout: '🕵️',
        youth: '🧒',
        derby: '🔥'
    })[type] || '📬';
}

function getInboxDeskGroups() {
    return [
        { key: 'matchday', label: 'Matchday', types: ['match', 'report', 'round-report', 'press', 'fans', 'media', 'derby'] },
        { key: 'club', label: 'Club', types: ['welcome', 'board', 'info', 'youth'] },
        { key: 'market', label: 'Market', types: ['message', 'transfer', 'scout'] },
        { key: 'world', label: 'World', types: ['international'] }
    ];
}

function countInboxDeskMessages(inbox, desk) {
    return inbox.filter(msg => desk.types.includes(msg?.type)).length;
}

function findLatestInboxByTypes(inbox, types) {
    for (let i = inbox.length - 1; i >= 0; i--) {
        if (types.includes(inbox[i]?.type)) return inbox[i];
    }
    return null;
}

function parseStructuredBulletin(text) {
    const lines = String(text || '').split('\n').map(line => line.trim());
    const title = lines.find(Boolean) || 'Bulletin';
    const sections = [];
    let current = { heading: 'Overview', lines: [] };
    const namedHeadings = new Set(['Key incidents', 'Half-time:', 'Second half:', 'Match desk']);

    lines.slice(1).forEach(line => {
        if (!line) return;
        const isHeading = (
            (/^[A-Z0-9 /:&-]{4,}$/.test(line) && !line.startsWith('- '))
            || namedHeadings.has(line)
        );
        if (isHeading) {
            if (current.lines.length) sections.push(current);
            current = { heading: line, lines: [] };
            return;
        }
        current.lines.push(line);
    });

    if (current.lines.length) sections.push(current);
    return { title, sections };
}

function buildStructuredSectionsHtml(text, options = {}) {
    const bulletin = parseStructuredBulletin(text);
    const bodyClass = options.bodyClass || 'cs-structured-text';
    const emptyText = options.emptyText || 'No notes filed.';
    const sectionsHtml = bulletin.sections.map(section => {
        const rows = section.lines.map(line => {
            const clean = line.startsWith('- ') ? line.slice(2) : line;
            return `<div class="${bodyClass}">${injectEntityLinks(clean)}</div>`;
        }).join('');
        return `<section class="cs-structured-section">
            <div class="cs-structured-heading">${escapeHtml(section.heading)}</div>
            <div class="cs-structured-stack">${rows || `<div class="${bodyClass}">${escapeHtml(emptyText)}</div>`}</div>
        </section>`;
    }).join('');

    return {
        title: bulletin.title,
        html: sectionsHtml || `<div class="${bodyClass}">${escapeHtml(text || emptyText)}</div>`
    };
}

function extractControlEstimate(match) {
    const events = match?.events || [];
    const homeName = match?.homeTeamName;
    const awayName = match?.awayTeamName;
    const countFor = (type, team) => events.filter(e => e.eventType === type && e.teamName === team).length;
    const combinedCountFor = (types, team) => types.reduce((sum, type) => sum + countFor(type, team), 0);
    const homeControlScore = combinedCountFor(['SHOT_ON_TARGET', 'SHOT_OFF_TARGET'], homeName) * 2 + countFor('CORNER', homeName) + countFor('FREE_KICK', homeName);
    const awayControlScore = combinedCountFor(['SHOT_ON_TARGET', 'SHOT_OFF_TARGET'], awayName) * 2 + countFor('CORNER', awayName) + countFor('FREE_KICK', awayName);
    const totalControl = homeControlScore + awayControlScore;
    return totalControl === 0 ? 50 : Math.round((homeControlScore * 100) / totalControl);
}

function buildMatchReportSnapshot(match) {
    const motm = findManOfTheMatch(match);
    const homeControl = extractControlEstimate(match);
    const shots = (team) => (match.events || []).filter(e => e.teamName === team && ['SHOT_ON_TARGET', 'SHOT_OFF_TARGET'].includes(e.eventType)).length;
    return `
        <div class="cs-report-kpi-grid">
            <div class="cs-report-kpi"><span>Score</span><strong>${match.homeGoals}:${match.awayGoals}</strong></div>
            <div class="cs-report-kpi"><span>Control</span><strong>${homeControl}% / ${100 - homeControl}%</strong></div>
            <div class="cs-report-kpi"><span>Shots</span><strong>${shots(match.homeTeamName)} / ${shots(match.awayTeamName)}</strong></div>
            <div class="cs-report-kpi"><span>Standout</span><strong>${escapeHtml(motm?.playerName || 'N/A')}</strong></div>
        </div>`;
}

function buildRoundReportDigest(text) {
    const lines = String(text || '').split('\n').map(line => line.trim()).filter(Boolean);
    const scoreLines = lines.filter(line => /\b\d+:\d+\b/.test(line) && !line.includes('Final score:')).slice(0, 8);
    if (!scoreLines.length) return '';
    return `<div class="cs-round-digest">
        ${scoreLines.map(line => `<div class="cs-round-digest-row">${injectEntityLinks(line.replace('[YOUR MATCH]', '<span class="cs-round-you">YOUR MATCH</span>'))}</div>`).join('')}
    </div>`;
}

function findManOfTheMatch(match) {
    return [...(match?.homePlayerStats || []), ...(match?.awayPlayerStats || [])]
        .filter(Boolean)
        .sort((a, b) => {
            const ratingDiff = Number(b.rating || 0) - Number(a.rating || 0);
            if (ratingDiff !== 0) return ratingDiff;
            const goalDiff = Number(b.goals || 0) - Number(a.goals || 0);
            if (goalDiff !== 0) return goalDiff;
            return Number(b.assists || 0) - Number(a.assists || 0);
        })[0] || null;
}

// --- Club Info ---
function renderClubInfo(el) {
    const t = gameState?.userTeam;
    if (!t) { el.innerHTML = '<p>No data</p>'; return; }
    const tableEntry = getUserTableEntry();
    const nextFixture = findNextFixture();
    const lastMatch = findLatestUserMatch();
    const lastOutcome = lastMatch ? getUserMatchOutcome(lastMatch) : null;
    const form = buildRecentForm();
    const tactics = gameState?.tactics || {};
    const positionLabel = tableEntry ? ordinal(getPosition(t.id)) : 'Unplaced';
    const mood = gameState?.clubMood;  // NEW
    const affiliateClub = gameState?.affiliateClubName;
    const affiliateNote = gameState?.affiliateClubNote;
    const lastIncome = gameState?.lastRoundIncome || 0;
    const lastExpenses = gameState?.lastRoundExpenses || 0;
    const weeklyWageBill = gameState?.weeklyWageBill || 0;
    const boardObjectiveTitle = gameState?.boardObjectiveTitle;
    const boardObjectiveText = gameState?.boardObjectiveText;
    const boardReviewTitle = gameState?.boardReviewTitle;
    const boardReviewText = gameState?.boardReviewText;
    const notableNews = Array.isArray(gameState?.notableNews) ? gameState.notableNews : [];
    const nextOpponent = nextFixture
        ? (Number(nextFixture.homeTeamId) === Number(t.id) ? nextFixture.awayTeamName : nextFixture.homeTeamName)
        : null;
    
    // Helper for mood color
    const getMoodColor = (val) => {
        if (val == null) return '#aaa';
        if (val >= 70) return '#4caf50';
        if (val >= 40) return '#ff9800';
        return '#f44336';
    };
    
    el.innerHTML = `
        <h2>Club Info</h2>
        <div class="cs-club-hero">
            <div>
                <div class="cs-section-label">Manager file</div>
                <div class="cs-club-title">${escapeHtml(t.name)}</div>
                <div class="cs-club-subtitle">${escapeHtml(getLeagueDisplayName())} &middot; ${positionLabel} place &middot; Season ${gameState.seasonYear}/${gameState.seasonYear + 1}</div>
            </div>
            <div class="cs-club-hero-side">
                <div class="cs-section-label">Board brief</div>
                <div class="cs-note-text">${tableEntry ? `Current return: ${tableEntry.points} points from ${tableEntry.played} matches. Keep momentum and protect home turf.` : 'The board wants a stable opening and a side with enough steel for the long haul.'}</div>
            </div>
        </div>
        <div class="cs-stat-grid">
            <div class="cs-stat-card"><div class="icon">&#127967;&#65039;</div><div class="val">${t.name}</div><div class="lbl">Team</div></div>
            <div class="cs-stat-card"><div class="icon">&#128176;</div><div class="val">${formatMoney(t.budget)}</div><div class="lbl">Budget</div></div>
            <div class="cs-stat-card"><div class="icon">&#11088;</div><div class="val">${t.reputation || 0}</div><div class="lbl">Reputation</div></div>
            <div class="cs-stat-card"><div class="icon">&#127967;&#65039;</div><div class="val">${t.stadiumName || '?'}</div><div class="lbl">Stadium (${t.stadiumCapacity || '?'})</div></div>
            <div class="cs-stat-card"><div class="icon">&#128203;</div><div class="val">${gameState.roster?.length || 0}</div><div class="lbl">Players</div></div>
            <div class="cs-stat-card"><div class="icon">&#128197;</div><div class="val">${gameState.currentRound} / ${gameState.totalRounds}</div><div class="lbl">Round</div></div>
            <div class="cs-stat-card"><div class="icon">&#127942;</div><div class="val">${positionLabel}</div><div class="lbl">League Position</div></div>
            <div class="cs-stat-card"><div class="icon">&#11035;</div><div class="val">${tableEntry?.points ?? 0}</div><div class="lbl">Points</div></div>
        </div>
        <div class="cs-club-columns">
            <div class="cs-note-card">
                <div class="cs-section-label">Next fixture</div>
                <div class="cs-note-title">${nextFixture ? `Round ${nextFixture.round} vs ${escapeHtml(nextOpponent || '?')}` : 'No remaining fixture filed'}</div>
                <div class="cs-note-text">${nextFixture ? `${Number(nextFixture.homeTeamId) === Number(t.id) ? 'Home match' : 'Away trip'}: ${escapeHtml(nextFixture.homeTeamName)} vs ${escapeHtml(nextFixture.awayTeamName)}.` : 'The schedule office has no further active fixture on file for the moment.'}</div>
            </div>
            <div class="cs-note-card">
                <div class="cs-section-label">Tactical sheet</div>
                <div class="cs-note-title">${escapeHtml(tactics.formation || '4-4-2')} &middot; ${escapeHtml(tactics.style || 'BALANCED')}</div>
                <div class="cs-note-text">Recent form</div>
                <div class="cs-form-strip">
                    ${form.length ? form.map(item => `<span class="cs-form-pill ${item.code === 'W' ? 'win' : item.code === 'D' ? 'draw' : 'loss'}" title="Round ${item.round}: ${item.goalsFor}-${item.goalsAgainst}">${item.code}</span>`).join('') : '<span class="cs-form-pill empty">No results yet</span>'}
                </div>
            </div>
            <div class="cs-note-card">
                <div class="cs-section-label">Latest result</div>
                <div class="cs-note-title">${lastMatch ? `${escapeHtml(lastMatch.homeTeamName)} ${lastMatch.homeGoals}:${lastMatch.awayGoals} ${escapeHtml(lastMatch.awayTeamName)}` : 'No result on file yet'}</div>
                <div class="cs-note-text">${lastMatch ? `Round ${lastMatch.round} finished as a ${lastOutcome?.code === 'W' ? 'win' : lastOutcome?.code === 'D' ? 'draw' : 'loss'} for ${escapeHtml(t.name)}.` : 'Open the inbox or play the next round to start building the season story.'}</div>
            </div>
            <div class="cs-note-card">
                <div class="cs-section-label">Club Atmosphere</div>
                <div class="cs-note-title">Overall: <span style="color:${mood ? (mood.moodLabel === 'Excellent' || mood.moodLabel === 'Good' ? '#4caf50' : mood.moodLabel === 'Crisis' ? '#f44336' : '#ff9800') : '#aaa'}">${mood?.moodLabel || 'N/A'}</span></div>
                <div class="cs-note-text">
                    Board: <span style="color:${getMoodColor(mood?.boardConfidence)}">${mood?.boardConfidence ?? '--'}%</span><br>
                    Fans: <span style="color:${getMoodColor(mood?.fanMood)}">${mood?.fanMood ?? '--'}%</span><br>
                    Finances: <span style="color:${getMoodColor(mood?.financialHealth)}">${mood?.financialHealth ?? '--'}%</span>
                </div>
            </div>
            <div class="cs-note-card">
                <div class="cs-section-label">Finance Sheet</div>
                <div class="cs-note-title">Budget ${formatMoney(t.budget)}</div>
                <div class="cs-note-text">Last round income: ${formatMoney(lastIncome)}<br>Last round expenses: ${formatMoney(lastExpenses)}<br>Weekly wage bill: ${formatMoney(weeklyWageBill)}</div>
            </div>
            <div class="cs-note-card">
                <div class="cs-section-label">Affiliate Club</div>
                <div class="cs-note-title">${affiliateClub ? escapeHtml(affiliateClub) : 'No strategic partner yet'}</div>
                <div class="cs-note-text">${affiliateClub ? escapeHtml(affiliateNote || 'Youth and scouting pathway active.') : 'Board links with bigger clubs can unlock prospects, prestige and small annual support.'}</div>
            </div>
            <div class="cs-note-card">
                <div class="cs-section-label">Board Objective</div>
                <div class="cs-note-title">${escapeHtml(boardObjectiveTitle || 'Season target pending')}</div>
                <div class="cs-note-text">${escapeHtml(boardObjectiveText || 'The board has not published a clear target yet.')}</div>
            </div>
            <div class="cs-note-card">
                <div class="cs-section-label">Board Review</div>
                <div class="cs-note-title">${escapeHtml(boardReviewTitle || 'Review pending')}</div>
                <div class="cs-note-text">${escapeHtml(boardReviewText || 'The board has not issued a fresh review yet.')}</div>
            </div>
            <div class="cs-note-card cs-wire-card">
                <div class="cs-section-label">League Wire</div>
                <div class="cs-note-title">Around the division</div>
                <div class="cs-wire-list">
                    ${notableNews.length
                        ? notableNews.map(line => `<div class="cs-wire-item">${injectEntityLinks(escapeHtml(line))}</div>`).join('')
                        : '<div class="cs-wire-item">The wire desk is still waiting for the next wave of stories.</div>'}
                </div>
            </div>
        </div>
        <div class="cs-note-card cs-club-milestones">
            <div class="cs-section-label">Milestone board</div>
            <div class="cs-note-title">Season markers</div>
            <div class="cs-note-text">Top performers, standout scorelines and crowd mood are updated as the save rolls forward.</div>
            <div class="cs-club-milestones-grid" id="cs-club-milestones">${buildClubMilestonesGridHtml(buildClubMilestoneSnapshot())}</div>
        </div>`;
    hydrateClubMilestones();
}

function getInboxFilterTypes(filter) {
    if (filter === 'all') return null;
    const desk = getInboxDeskGroups().find(group => group.key === filter);
    return desk?.types || null;
}

function getFilteredInboxEntries(inbox, filter) {
    const allowed = getInboxFilterTypes(filter);
    const entries = inbox
        .map((msg, index) => ({ msg, index }))
        .filter(entry => !allowed || allowed.includes(entry.msg?.type));
    return sortInboxEntries(entries, inboxSortMode);
}

function sortInboxEntries(entries, mode) {
    const list = [...entries];
    const ts = (entry) => new Date(entry.msg?.timestamp || 0).getTime() || 0;
    switch (mode) {
        case 'unread':
            return list.sort((a, b) => Number(a.msg?.read) - Number(b.msg?.read) || ts(b) - ts(a));
        case 'type':
            return list.sort((a, b) => getInboxTypeLabel(a.msg?.type).localeCompare(getInboxTypeLabel(b.msg?.type)) || ts(b) - ts(a));
        case 'oldest':
            return list.sort((a, b) => ts(a) - ts(b));
        case 'newest':
        default:
            return list.sort((a, b) => ts(b) - ts(a));
    }
}

function buildInboxPreviewPane(msg, index) {
    if (!msg) {
        return `<div class="cs-inbox-empty-preview">
            <div class="cs-section-label">Preview</div>
            <div class="cs-note-title">Select a message</div>
            <div class="cs-note-text">Choose a row on the left to read the full item without leaving the inbox view.</div>
        </div>`;
    }

    const icon = getInboxIcon(msg.type);
    const title = getInboxHeadline(msg);
    const badgeHtml = `<span class="cs-inbox-badge ${msg.type}">${escapeHtml(getInboxTypeLabel(msg.type))}</span>`;
    if (msg.type === 'international' || msg.type === 'report' || msg.type === 'round-report') {
        const structured = buildStructuredSectionsHtml(msg.text || '', { bodyClass: 'cs-report-line' });
        return `<div class="cs-inbox-preview-pane">
            <div class="cs-inbox-preview-head">
                <div>
                    <div style="font-size:1.8em;">${icon}</div>
                    <div class="cs-inbox-preview-title">${escapeHtml(msg.type === 'international' ? structured.title : title)}</div>
                    <div class="cs-inbox-preview-meta">${badgeHtml}</div>
                </div>
                <div class="cs-inbox-time">${escapeHtml(msg.timestamp || '')}</div>
            </div>
            <div class="cs-report-shell">
                <div class="cs-report-body cs-report-body-structured">${structured.html}</div>
            </div>
        </div>`;
    }

    return `<div class="cs-inbox-preview-pane">
        <div class="cs-inbox-preview-head">
            <div>
                <div style="font-size:1.8em;">${icon}</div>
                <div class="cs-inbox-preview-title">${escapeHtml(title)}</div>
                <div class="cs-inbox-preview-meta">${badgeHtml}</div>
            </div>
            <div class="cs-inbox-time">${escapeHtml(msg.timestamp || '')}</div>
        </div>
        <div class="cs-message-body">${injectEntityLinks(msg.text || '')}</div>
        <div class="cs-note-text">Message #${index + 1}</div>
    </div>`;
}

function formatInboxTimestamp(timestamp) {
    if (!timestamp) return '-';
    const parsed = new Date(timestamp);
    if (Number.isNaN(parsed.getTime())) return timestamp;
    return parsed.toLocaleString([], { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
}

// --- Inbox ---
function renderInbox(el) {
    const inbox = gameState?.inbox || [];
    const counts = inbox.reduce((acc, msg) => {
        const key = msg?.type || 'message';
        acc[key] = (acc[key] || 0) + 1;
        return acc;
    }, {});
    const deskSummary = getInboxDeskGroups().map(group => {
        const count = countInboxDeskMessages(inbox, group);
        const latest = findLatestInboxByTypes(inbox, group.types);
        return { ...group, count, latest };
    });
    const summary = Object.entries(counts)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 4)
        .map(([type, count]) => `<span class="cs-pill-summary"><span class="cs-inbox-badge ${type}">${type.toUpperCase()}</span>${count}</span>`)
        .join('');
    const deskCards = deskSummary.map(group => `
        <div class="cs-desk-card ${group.key}">
            <div class="cs-desk-label">${group.label}</div>
            <div class="cs-desk-count">${group.count}</div>
            <div class="cs-desk-copy">${escapeHtml(group.latest ? getInboxHeadline(group.latest) : 'No fresh note in this desk yet.')}</div>
        </div>
    `).join('');
    const featuredReport = findLatestInboxByTypes(inbox, ['report']);
    const featuredInternational = findLatestInboxByTypes(inbox, ['international']);
    const filterTabs = [{ key: 'all', label: 'All' }, ...getInboxDeskGroups().map(group => ({ key: group.key, label: group.label }))];
    const sortTabs = [
        { key: 'newest', label: 'Newest' },
        { key: 'unread', label: 'Unread first' },
        { key: 'type', label: 'Desk' }
    ];
    const filtered = getFilteredInboxEntries(inbox, inboxActiveFilter);
    if (inboxSelectedIndex == null && filtered.length) {
        inboxSelectedIndex = filtered[0].index;
    }
    if (!filtered.some(entry => entry.index === inboxSelectedIndex)) {
        inboxSelectedIndex = filtered[0]?.index ?? null;
    }
    const selectedMsg = inboxSelectedIndex != null ? inbox[inboxSelectedIndex] : null;
    let html = `<h2>📬 Inbox (${inbox.length})</h2>`;
    html += `<div class="cs-inbox-hub">
        <div class="cs-inbox-toolbar"><div class="cs-note-text">Latest items from the chairman, scouting desk and match-day press box.</div><div class="cs-pill-wrap">${summary || '<span class="cs-note-text">No messages logged.</span>'}</div></div>
        <div class="cs-desk-grid">${deskCards}</div>
        <div class="cs-inbox-feature-grid">
            <div class="cs-inbox-feature">
                <div class="cs-section-label">Featured report</div>
                <div class="cs-inbox-feature-title">${escapeHtml(featuredReport ? getInboxHeadline(featuredReport) : 'Next match report will appear here')}</div>
                <div class="cs-note-text">${escapeHtml(featuredReport ? getInboxPreview(featuredReport) : 'Play the next round to keep the season notebook moving.')}</div>
            </div>
            <div class="cs-inbox-feature cs-clickable" onclick="tifoNav('internationalCentre')">
                <div class="cs-section-label">International centre</div>
                <div class="cs-inbox-feature-title">${escapeHtml(featuredInternational ? getInboxHeadline(featuredInternational) : 'Open the world desk')}</div>
                <div class="cs-note-text">${escapeHtml(featuredInternational ? getInboxPreview(featuredInternational) : 'Regional results, Serbia watch and scouting notes are collected here.')}</div>
            </div>
        </div>
    </div>`;
    html += `<div class="cs-inbox-filter-row">${filterTabs.map(tab => {
        const active = tab.key === inboxActiveFilter ? 'active' : '';
        const count = tab.key === 'all'
            ? inbox.length
            : countInboxDeskMessages(inbox, getInboxDeskGroups().find(group => group.key === tab.key) || { types: [] });
        return `<button class="cs-filter-chip ${active}" onclick="tifoInboxFilter('${tab.key}')">${escapeHtml(tab.label)} <span>${count}</span></button>`;
    }).join('')}</div>`;
    html += `<div class="cs-inbox-sort-row">${sortTabs.map(tab => `<button class="cs-sort-chip ${tab.key === inboxSortMode ? 'active' : ''}" onclick="tifoInboxSort('${tab.key}')">${tab.label}</button>`).join('')}</div>`;
    if (inbox.length === 0) { html += '<p style="color:#aaa;">No messages. Check back after next round.</p>'; }
    else {
        const listHtml = filtered.length
            ? filtered.map(({ msg, index }) => {
                const selected = index === inboxSelectedIndex ? 'selected' : '';
                const unread = msg.read ? '' : 'unread';
                return `<div class="cs-inbox-item cs-clickable ${selected} ${unread}" onclick="tifoOpenInbox(${index}, false)">
                    <div class="cs-inbox-col date">${escapeHtml(formatInboxTimestamp(msg.timestamp || ''))}</div>
                    <div class="cs-inbox-col subject">
                        <div class="cs-inbox-subject">${msg.read ? '' : '<span class="cs-unread-dot inline"></span>'}${escapeHtml(getInboxHeadline(msg))}</div>
                        <div class="cs-inbox-preview">${escapeHtml(getInboxPreview(msg))}</div>
                    </div>
                    <div class="cs-inbox-col source">${escapeHtml(getInboxSourceLabel(msg.type))}</div>
                </div>`;
            }).join('')
            : '<p style="color:#aaa;">No messages match this filter.</p>';

        html += `<div class="cs-inbox-split">
            <div class="cs-inbox-list-pane">
                <div class="cs-inbox-list-head">
                    <div class="cs-inbox-col date">DATE</div>
                    <div class="cs-inbox-col subject">SUBJECT</div>
                    <div class="cs-inbox-col source">SOURCE</div>
                </div>
                ${listHtml}
            </div>
            <div class="cs-inbox-reading-pane">${buildInboxPreviewPane(selectedMsg, inboxSelectedIndex ?? 0)}</div>
        </div>`;
    }
    el.innerHTML = html;
}

function renderInternationalCentre(el) {
    const reports = gameState?.internationalWindows?.slice().reverse() || [];
    const latest = reports[0] || null;
    const latestStructured = latest ? buildStructuredSectionsHtml(latest.bulletin, { bodyClass: 'cs-report-line' }) : null;
    const table = gameState?.internationalTable || [];
    const compName = gameState?.internationalCompetitionName || 'International competition';

    let html = `<h2>🌍 International Centre</h2>
        <div class="cs-intel-shell">
            <div class="cs-intel-hero">
                <div>
                    <div class="cs-section-label">World desk</div>
                    <div class="cs-intel-title">${escapeHtml(compName)}</div>
                    <div class="cs-note-text">${escapeHtml(latest ? getInboxPreview({ text: latest.bulletin }) : 'International windows will populate here as the season advances.')}</div>
                </div>
                <div class="cs-intel-meta">
                    <div class="cs-intel-stat"><span class="cs-intel-stat-label">Windows logged</span><strong>${reports.length}</strong></div>
                    <div class="cs-intel-stat"><span class="cs-intel-stat-label">Current matchday</span><strong>${gameState?.internationalMatchday || 1}</strong></div>
                </div>
            </div>`;

    if (table.length) {
        html += `<div class="cs-intel-table-wrap">
            <div class="cs-section-label">Qualifying Table</div>
            <table class="cs-table">
                <thead><tr><th>#</th><th>Nation</th><th>P</th><th>W</th><th>D</th><th>L</th><th>GD</th><th>Pts</th></tr></thead>
                <tbody>
                    ${table.map(entry => `<tr class="${entry.teamName === 'Serbia' ? 'user-row' : ''}">
                        <td>${entry.position}</td>
                        <td>${escapeHtml(entry.teamName)}</td>
                        <td>${entry.played}</td>
                        <td>${entry.wins}</td>
                        <td>${entry.draws}</td>
                        <td>${entry.losses}</td>
                        <td>${entry.goalDifference}</td>
                        <td>${entry.points}</td>
                    </tr>`).join('')}
                </tbody>
            </table>
        </div>`;
    }

    if (latestStructured) {
        html += `<div class="cs-intel-latest">${latestStructured.html}</div>`;
    }

    html += `<div class="cs-intel-archive">
        <div class="cs-section-label">Archive</div>`;
    if (!reports.length) {
        html += `<p style="color:#aaa;">No international messages yet.</p>`;
    } else {
        html += reports.map((window) => `
            <div class="cs-intel-archive-row">
                <div>
                    <div class="cs-intel-archive-title">Matchday ${window.matchday} · ${escapeHtml(window.competitionName || compName)}</div>
                    <div class="cs-note-text">${escapeHtml(getInboxPreview({ text: window.bulletin }))}</div>
                    <div class="cs-intel-result-strip">${(window.results || []).map(result => `<span>${escapeHtml(result.homeTeamName)} ${result.homeGoals}:${result.awayGoals} ${escapeHtml(result.awayTeamName)}</span>`).join('')}</div>
                </div>
                <div class="cs-inbox-time">Round ${window.round}</div>
            </div>
        `).join('');
    }
    html += `</div></div>`;
    el.innerHTML = html;
}

function setInboxFilter(filter) {
    inboxActiveFilter = filter || 'all';
    renderPage('inbox');
}

function setInboxSort(mode) {
    inboxSortMode = mode || 'newest';
    renderPage('inbox');
}

function truncate(text, max) {
    if (!text || text.length <= max) return text || '';
    return text.substring(0, max) + '...';
}

function escapeHtml(text) {
    return (text || '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

function escapeRegExp(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function getKnownMatchLinks() {
    const map = new Map();
    const add = (m) => {
        if (!m?.homeTeamName || !m?.awayTeamName) return;
        const key = `${m.round}|${m.homeTeamId}|${m.awayTeamId}`;
        if (!map.has(key)) {
            const phrase = `${m.homeTeamName} ${m.homeGoals}:${m.awayGoals} ${m.awayTeamName}`;
            map.set(key, { round: m.round, homeTeamId: m.homeTeamId, awayTeamId: m.awayTeamId, phrase });
        }
    };
    (gameState?.matchHistory || []).forEach(add);
    Object.values(allRoundResults || {}).forEach(list => (list || []).forEach(add));
    return [...map.values()];
}

function injectEntityLinks(rawText) {
    let html = escapeHtml(rawText || '');

    const matchTokenMap = new Map();
    const knownMatches = getKnownMatchLinks().sort((a, b) => b.phrase.length - a.phrase.length);
    knownMatches.forEach((m, idx) => {
        const escapedPhrase = escapeHtml(m.phrase);
        if (!html.includes(escapedPhrase)) return;
        const token = `@@CSMATCH${idx}@@`;
        html = html.split(escapedPhrase).join(token);
        matchTokenMap.set(token, `<a href="#" class="cs-inline-link" data-kind="match" data-round="${m.round}" data-home-id="${m.homeTeamId}" data-away-id="${m.awayTeamId}">${escapedPhrase}</a>`);
    });

    const teamNames = [...csTeamNameToId.keys()].sort((a, b) => b.length - a.length);
    for (const teamName of teamNames) {
        const teamId = csTeamNameToId.get(teamName);
        const rx = new RegExp(`\\b${escapeRegExp(teamName)}\\b`, 'g');
        html = html.replace(rx, `<a href="#" class="cs-inline-link" data-kind="team" data-id="${teamId}">${teamName}</a>`);
    }

    if (csPlayerIndexLoaded) {
        const playerNames = [...csPlayerNameToEntries.keys()].sort((a, b) => b.length - a.length);
        for (const playerName of playerNames) {
            const first = csPlayerNameToEntries.get(playerName)?.[0];
            if (!first) continue;
            const escapedPlayer = escapeHtml(playerName);
            const link = `<a href="#" class="cs-inline-link" data-kind="player" data-player-id="${first.playerId}" data-team-id="${first.teamId}">${escapedPlayer}</a>`;
            html = html.split(escapedPlayer).join(link);
        }
    }

    const leagueName = getLeagueDisplayName();
    html = html.replace(/\b(League Table)\b/g, `<a href="#" class="cs-inline-link" data-kind="league">$1</a>`);
    if (leagueName) {
        const rxLeague = new RegExp(`\\b${escapeRegExp(leagueName)}\\b`, 'g');
        html = html.replace(rxLeague, `<a href="#" class="cs-inline-link" data-kind="league">${leagueName}</a>`);
    }
    matchTokenMap.forEach((value, token) => {
        html = html.split(token).join(value);
    });
    return html.replaceAll('\n', '<br>');
}

async function openInboxMessage(index, forceModal = true) {
    const msg = gameState?.inbox?.[index];
    if (!msg) return;
    inboxSelectedIndex = index;
    rebuildTeamIndex();
    if (msg.type === 'report' || msg.type === 'round-report' || msg.type === 'international') {
        await ensurePlayerIndexLoaded();
    }
    if (!msg.read) {
        msg.read = true;
        inboxUnreadCount = Math.max(0, inboxUnreadCount - 1);
        updateInboxRibbon();
        try {
            await csApi(`/api/cs/inbox/${index}/read`, { method: 'POST' });
        } catch {
            // local state already updated; inbox view remains responsive
        }
    }
    if (!forceModal && currentPage === 'inbox') {
        renderPage('inbox');
        return;
    }
    const icon = getInboxIcon(msg.type);

    const badgeHtml = `<span class="cs-inbox-badge ${msg.type}">${escapeHtml(getInboxTypeLabel(msg.type))}</span>`;
    const linkedText = injectEntityLinks(msg.text || '');
    
    let content = '';
    
    if (msg.type === 'international') {
        const structured = buildStructuredSectionsHtml(msg.text || '', { bodyClass: 'cs-report-line' });
        content = `
            <div class="cs-message-shell cs-intel-modal">
                <div class="cs-message-headline">${escapeHtml(structured.title)}</div>
                <div class="cs-intel-latest">${structured.html}</div>
                <div class="cs-message-timestamp">${escapeHtml(msg.timestamp || '')}</div>
            </div>
        `;
    } else if (msg.type === 'report' || msg.type === 'round-report') {
        const structured = buildStructuredSectionsHtml(msg.text || '', { bodyClass: 'cs-report-line' });
        const roundDigest = msg.type === 'round-report' ? buildRoundReportDigest(msg.text || '') : '';
        content = `
            <div class="cs-message-shell">
                <div style="display:flex; justify-content:space-between; align-items:start; margin-bottom:12px;">
                    <div style="flex:1;">
                        <div style="font-size:2em; margin-bottom:8px;">${icon}</div>
                        <div class="cs-message-headline">${escapeHtml(structured.title)}</div>
                    </div>
                    <div style="color:#7e8b92; font-size:0.85em; text-align:right;">${escapeHtml(msg.timestamp || '')}</div>
                </div>
                ${roundDigest}
                <div class="cs-report-shell">
                    <div class="cs-report-body cs-report-body-structured">${structured.html}</div>
                </div>
            </div>
        `;
    } else {
        content = `
            <div class="cs-message-shell">
                <div style="display:flex; justify-content:space-between; align-items:start; margin-bottom:12px;">
                    <div style="flex:1;">
                        <div style="font-size:2em; margin-bottom:8px;">${icon}</div>
                        <div class="cs-message-headline">${escapeHtml(getInboxHeadline(msg))}</div>
                    </div>
                    <div style="color:#7e8b92; font-size:0.85em; text-align:right;">${escapeHtml(msg.timestamp || '')}</div>
                </div>
                <div style="border-top:1px solid rgba(255,255,255,0.08); padding-top:12px;">
                    <div class="cs-message-body">${linkedText}</div>
                </div>
            </div>
        `;
    }
    
    showModal(icon + ' ' + badgeHtml + ' Message', content);
}

// --- Players ---
function renderPlayers(el) {
    const players = gameState?.roster || [];
    let html = `<h2>First Team (${players.length})</h2>`;

    const posOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
    const sorted = [...players].sort((a, b) => (posOrder[a.position] ?? 5) - (posOrder[b.position] ?? 5));

    html += `<div style="display:flex; gap:6px; padding:8px 14px; color:#888; font-size:0.85em;">
        <div class="cs-player-pos">POS</div>
        <div class="cs-player-name">Name</div>
        <div class="cs-player-stat">Age</div>
        <div class="cs-player-stat">Rat</div>
        <div class="cs-player-stat">Form</div>
        <div class="cs-player-stat">Goals</div>
        <div class="cs-player-stat">Ast</div>
    </div>`;

    sorted.forEach(p => {
        html += `<div class="cs-player-row cs-clickable" onclick="tifoPlayerDetail(${p.id})">
            <div class="cs-player-pos">${p.position}</div>
            <div class="cs-player-name">${p.name}</div>
            <div class="cs-player-stat">${p.age}</div>
            <div class="cs-player-stat">${p.rating}</div>
            <div class="cs-player-stat">${p.form?.toFixed(1) || '-'}</div>
            <div class="cs-player-stat">${p.goals || 0}</div>
            <div class="cs-player-stat">${p.assists || 0}</div>
        </div>`;
    });
    el.innerHTML = html;
}

// --- Player Detail (own roster) ---
function renderPlayerDetail(playerId) {
    const p = gameState?.roster?.find(pl => pl.id === playerId);
    if (!p) return;
    renderAnyPlayerDetail(p, () => renderPage('players'));
}

// --- Generic Player Detail (any player, any team) ---
function renderAnyPlayerDetail(p, backFn) {
    const main = document.getElementById('main-content');
    main.innerHTML = `<div class="manager-card">
        <button class="big-button" onclick="window._tifoBack()" style="margin-bottom:16px;">Back</button>
        <h2>${p.name}</h2>
        <div class="cs-stat-grid">
            <div class="cs-stat-card"><div class="icon">&#128203;</div><div class="val">${p.position}</div><div class="lbl">Position</div></div>
            <div class="cs-stat-card"><div class="icon">&#127874;</div><div class="val">${p.age}</div><div class="lbl">Age</div></div>
            <div class="cs-stat-card"><div class="icon">&#11088;</div><div class="val">${p.rating}</div><div class="lbl">Rating</div></div>
            <div class="cs-stat-card"><div class="icon">&#128293;</div><div class="val">${p.form?.toFixed(1) ?? '-'}</div><div class="lbl">Form</div></div>
            <div class="cs-stat-card"><div class="icon">&#128531;</div><div class="val">${p.fatigue?.toFixed(1) ?? '-'}</div><div class="lbl">Fatigue</div></div>
            <div class="cs-stat-card"><div class="icon">&#9917;</div><div class="val">${p.goals || 0}</div><div class="lbl">Goals</div></div>
            <div class="cs-stat-card"><div class="icon">A</div><div class="val">${p.assists || 0}</div><div class="lbl">Assists</div></div>
            <div class="cs-stat-card"><div class="icon">&#128176;</div><div class="val">&euro;${(p.value || 0).toLocaleString()}</div><div class="lbl">Value</div></div>
        </div>
        <h3 style="margin-top:20px;">Skills</h3>
        <div class="cs-stat-grid">
            <div class="cs-stat-card"><div class="val">${p.stamina ?? '-'}</div><div class="lbl">Stamina</div></div>
            <div class="cs-stat-card"><div class="val">${p.pace ?? '-'}</div><div class="lbl">Pace</div></div>
            <div class="cs-stat-card"><div class="val">${p.defending ?? '-'}</div><div class="lbl">Defending</div></div>
            <div class="cs-stat-card"><div class="val">${p.technique ?? '-'}</div><div class="lbl">Technique</div></div>
            <div class="cs-stat-card"><div class="val">${p.playmaker ?? '-'}</div><div class="lbl">Playmaker</div></div>
            <div class="cs-stat-card"><div class="val">${p.passing ?? '-'}</div><div class="lbl">Passing</div></div>
            <div class="cs-stat-card"><div class="val">${p.shooting ?? '-'}</div><div class="lbl">Shooting</div></div>
            <div class="cs-stat-card"><div class="val">${p.goalkeeper ?? '-'}</div><div class="lbl">Goalkeeper</div></div>
        </div>
    </div>`;
    window._tifoBack = backFn;
}

// --- League Table (clickable rows -> team detail) ---
function renderLeagueTable(el) {
    const table = gameState?.leagueTable || [];
    const userTeamId = gameState?.userTeam?.id;

    let html = `<h2>League Table</h2>
    <div style="overflow-x:auto;">
    <table class="cs-table">
        <thead><tr>
            <th>#</th><th>Tim</th><th>P</th><th>W</th><th>D</th><th>L</th><th>GF</th><th>GA</th><th>GD</th><th>Pts</th>
        </tr></thead><tbody>`;

    table.forEach((t, i) => {
        const isUser = t.teamId === userTeamId;
        const gd = t.goalsScored - t.goalsConceded;
        const gdColor = gd > 0 ? '#4caf50' : gd < 0 ? '#f44336' : '#aaa';
        html += `<tr class="${isUser ? 'user-row' : ''} cs-clickable" onclick="tifoTeamDetail(${t.teamId})">
            <td>${i + 1}</td><td>${t.teamName}</td><td>${t.played}</td>
            <td>${t.wins}</td><td>${t.draws}</td><td>${t.losses}</td>
            <td>${t.goalsScored}</td><td>${t.goalsConceded}</td>
            <td style="color:${gdColor};font-weight:bold;">${gd > 0 ? '+' : ''}${gd}</td>
            <td style="font-weight:bold;color:#ffd700;">${t.points}</td>
        </tr>`;
    });

    html += `</tbody></table></div>`;
    el.innerHTML = html;
}

// --- Team Detail (any team roster view) ---
async function renderTeamDetail(teamId) {
    const main = document.getElementById('main-content');
    main.innerHTML = '<div class="manager-card"><h2>Loading...</h2></div>';

    const res = await csApi(`/api/cs/team/${teamId}`);
    if (!res || !res.ok) {
        main.innerHTML = '<div class="manager-card"><p>Team not found</p></div>';
        return;
    }
    const data = await res.json();
    const team = data.team;
    const roster = data.roster || [];
    const posOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
    const sorted = [...roster].sort((a, b) => (posOrder[a.position] ?? 5) - (posOrder[b.position] ?? 5));

    let html = `<div class="manager-card">
        <button class="big-button" onclick="tifoNav('leagueTable')" style="margin-bottom:16px;">Back</button>
        <h2>${team.name}</h2>
        <div class="cs-stat-grid">
            <div class="cs-stat-card"><div class="icon">&#128176;</div><div class="val">&euro;${team.budget?.toLocaleString() || 0}</div><div class="lbl">Budget</div></div>
            <div class="cs-stat-card"><div class="icon">&#11088;</div><div class="val">${team.reputation || 0}</div><div class="lbl">Reputation</div></div>
            <div class="cs-stat-card"><div class="icon">&#128203;</div><div class="val">${roster.length}</div><div class="lbl">Players</div></div>
        </div>
        <h3 style="margin-top:20px;">Roster</h3>
        <div style="display:flex; gap:6px; padding:8px 14px; color:#888; font-size:0.85em;">
            <div class="cs-player-pos">POS</div>
            <div class="cs-player-name">Name</div>
            <div class="cs-player-stat">Age</div>
            <div class="cs-player-stat">Rat</div>
            <div class="cs-player-stat">Goals</div>
            <div class="cs-player-stat">Ast</div>
        </div>`;

    sorted.forEach(p => {
        html += `<div class="cs-player-row cs-clickable" onclick="tifoViewPlayer(${p.id}, ${teamId})">
            <div class="cs-player-pos">${p.position}</div>
            <div class="cs-player-name">${p.name}</div>
            <div class="cs-player-stat">${p.age}</div>
            <div class="cs-player-stat">${p.rating}</div>
            <div class="cs-player-stat">${p.goals || 0}</div>
            <div class="cs-player-stat">${p.assists || 0}</div>
        </div>`;
    });

    html += `</div>`;
    main.innerHTML = html;
    window._teamRosterCache = { teamId, roster };
}

async function viewPlayerFromTeam(playerId, teamId) {
    let roster = window._teamRosterCache?.roster || [];
    if (window._teamRosterCache?.teamId !== teamId) {
        const res = await csApi(`/api/cs/team/${teamId}`);
        if (res && res.ok) {
            const data = await res.json();
            roster = data.roster || [];
        }
    }
    const p = roster.find(pl => pl.id === playerId);
    if (!p) return;
    renderAnyPlayerDetail(p, () => renderTeamDetail(teamId));
}

function openMatchPlayer(playerId, teamId) {
    const ownTeamId = gameState?.userTeam?.id;
    if (teamId === ownTeamId) {
        renderPlayerDetail(playerId);
        return;
    }
    viewPlayerFromTeam(playerId, teamId);
}

// --- Schedule (clickable matches) ---
async function renderSchedule(el) {
    let schedule = [];
    try {
        const res = await csApi('/api/cs/schedule');
        if (res && res.ok) schedule = await res.json();
    } catch (e) { /* use empty */ }

    const userTeamId = gameState?.userTeam?.id;
    const currentRound = gameState?.currentRound || 1;
    const byRound = {};
    schedule.forEach(f => {
        if (!byRound[f.round]) byRound[f.round] = [];
        byRound[f.round].push(f);
    });

    let html = `<h2>Schedule</h2>`;
    const rounds = Object.keys(byRound).map(Number).sort((a, b) => a - b);
    const nextRound = rounds.find(r => byRound[r].some(f => !f.played)) || rounds[rounds.length - 1];

    for (const round of rounds) {
        const isCurrentRound = round === currentRound;
        const isNext = round === nextRound;
        html += `<div class="cs-fixture-round" ${isNext ? 'id="nextRoundAnchor"' : ''}>
            <h4>Round ${round} ${isCurrentRound ? '- next' : ''}</h4>`;
        byRound[round].forEach(f => {
            const isUserMatch = f.homeTeamId === userTeamId || f.awayTeamId === userTeamId;
            const bg = isUserMatch ? 'rgba(42,140,74,0.12)' : '';
            if (f.played && f.result) {
                html += `<div class="cs-match-card cs-clickable" style="background:${bg}" onclick="tifoFixtureDetail(${f.round}, ${f.homeTeamId}, ${f.awayTeamId})">
                    <div class="cs-match-teams">${f.homeTeamName} vs ${f.awayTeamName}</div>
                    <div class="cs-match-score">${f.result.homeGoals} : ${f.result.awayGoals}</div>
                </div>`;
            } else {
                html += `<div class="cs-match-card cs-clickable" style="background:${bg}" onclick="tifoFixturePreview(${f.round}, '${esc(f.homeTeamName)}', ${f.homeTeamId}, '${esc(f.awayTeamName)}', ${f.awayTeamId})">
                    <div class="cs-match-teams">${f.homeTeamName} vs ${f.awayTeamName}</div>
                    <div class="cs-match-score" style="color:#666;">-:-</div>
                </div>`;
            }
        });
        html += `</div>`;
    }
    el.innerHTML = html;
    setTimeout(() => {
        const anchor = document.getElementById('nextRoundAnchor');
        if (anchor) anchor.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 100);
}

function esc(str) { return (str || '').replace(/'/g, "\\'"); }

function fixturePreview(round, homeName, homeId, awayName, awayId) {
    const homeTable = gameState?.leagueTable?.find(t => t.teamId === homeId);
    const awayTable = gameState?.leagueTable?.find(t => t.teamId === awayId);

    let body = `<p style="text-align:center;font-size:1.2em;"><strong>${homeName}</strong> vs <strong>${awayName}</strong></p>
        <p style="text-align:center;color:#888;">Round ${round} has not been played yet.</p>`;

    if (homeTable && awayTable) {
        body += `<table class="cs-table" style="margin-top:16px;">
            <thead><tr><th></th><th>${homeName}</th><th>${awayName}</th></tr></thead>
            <tbody>
                <tr><td>Position</td><td>${getPosition(homeId)}</td><td>${getPosition(awayId)}</td></tr>
                <tr><td>Points</td><td>${homeTable.points}</td><td>${awayTable.points}</td></tr>
                <tr><td>W/D/L</td><td>${homeTable.wins}/${homeTable.draws}/${homeTable.losses}</td><td>${awayTable.wins}/${awayTable.draws}/${awayTable.losses}</td></tr>
                <tr><td>Goals</td><td>${homeTable.goalsScored}:${homeTable.goalsConceded}</td><td>${awayTable.goalsScored}:${awayTable.goalsConceded}</td></tr>
            </tbody>
        </table>`;
    }
    showModal('Match Preview', body);
}

function getPosition(teamId) {
    const table = gameState?.leagueTable || [];
    const idx = table.findIndex(t => t.teamId === teamId);
    return idx >= 0 ? (idx + 1) + '.' : '?';
}

function fixtureDetail(round, homeTeamId, awayTeamId) {
    let match = null;
    if (allRoundResults[round]) {
        match = allRoundResults[round].find(m =>
            m.homeTeamId === homeTeamId && m.awayTeamId === awayTeamId);
    }
    if (!match) {
        match = gameState?.matchHistory?.find(m =>
            m.round === round && m.homeTeamId === homeTeamId && m.awayTeamId === awayTeamId);
    }
    if (!match) {
        match = gameState?.matchHistory?.find(m => m.round === round);
    }
    if (match) {
        renderMatchDetailFull(match, () => renderPage('schedule'));
    } else {
        showModal('Match', `<p>Match details are not available.</p>`);
    }
}

// --- Matches (results history) ---
function renderMatches(el) {
    const matches = gameState?.matchHistory || [];
    let html = `<h2>Your Match Results</h2>`;
    if (matches.length === 0) {
        html += '<p style="color:#aaa;">No matches played yet.</p>';
    } else {
        matches.slice().reverse().forEach(m => {
            html += `<div class="cs-match-card cs-clickable" onclick="tifoMatchDetail(${m.round})">
                <div class="cs-match-teams">
                    <strong>Round ${m.round}</strong>${m.isDerby ? ' <span class="cs-derby-badge">DERBY</span>' : ''} &nbsp; ${m.homeTeamName} vs ${m.awayTeamName}
                </div>
                <div class="cs-match-score">${m.homeGoals} : ${m.awayGoals}</div>
            </div>`;
        });
    }
    el.innerHTML = html;
}

// --- Match Detail (tabbed: Lineups | Goals | Stats) ---
function renderMatchDetail(round) {
    const match = gameState?.matchHistory?.find(m => m.round === round);
    if (!match) {
        document.getElementById('main-content').innerHTML = '<div class="manager-card"><p>Match not found</p></div>';
        return;
    }
    renderMatchDetailFull(match, () => renderPage('matches'));
}

function renderMatchDetailFull(match, backFn) {
    const main = document.getElementById('main-content');
    const homeName = match.homeTeamName;
    const awayName = match.awayTeamName;
    const homeTeamId = match.homeTeamId;
    const awayTeamId = match.awayTeamId;
    const leagueName = getLeagueDisplayName();
    const motm = findManOfTheMatch(match);
    const resultTone = match.homeGoals === match.awayGoals ? 'Drawn contest' : (match.homeGoals > match.awayGoals ? `${homeName} win` : `${awayName} win`);
    const reportReady = Boolean(match.report || match.summary);

    let html = `<div class="manager-card">
        <button class="big-button" onclick="window._tifoMatchBack()" style="margin-bottom:16px;">Back</button>
        <div class="cs-match-header-card">
            <div class="cs-section-label">Match day file</div>
            <div class="cs-match-topline">
                <span class="cs-clickable" onclick="tifoNav('leagueTable')">${leagueName}</span>
                &middot;
                <span class="cs-clickable" onclick="tifoNav('schedule')">Round ${match.round}</span>${match.isDerby ? ' <span class="cs-derby-badge">DERBY</span>' : ''}
                &middot; ${resultTone}
            </div>
            <div class="cs-match-scoreboard">
                <div class="cs-match-side">
                    <div class="cs-match-team"><span class="cs-clickable" onclick="tifoTeamDetail(${homeTeamId})">${homeName}</span></div>
                    <div class="cs-match-side-label">Home</div>
                </div>
                <div class="cs-match-score-core">
                    <div class="cs-match-big-score">${match.homeGoals} : ${match.awayGoals}</div>
                    <div class="cs-match-mini-note">${reportReady ? 'Detailed report filed' : 'Result recorded in the club ledger'}</div>
                </div>
                <div class="cs-match-side">
                    <div class="cs-match-team"><span class="cs-clickable" onclick="tifoTeamDetail(${awayTeamId})">${awayName}</span></div>
                    <div class="cs-match-side-label">Away</div>
                </div>
            </div>
            <div class="cs-match-meta-row">
                <span>${motm ? `Man of the Match: ${escapeHtml(motm.playerName)} (${Number(motm.rating || 0).toFixed(1)})` : 'Man of the Match: not available'}</span>
                <span>${(match.events || []).length} events logged</span>
            </div>
        </div>

        <div class="cs-tabs">
            <button class="cs-tab-btn active" data-tab="lineups" onclick="tifoMatchTab('lineups')">Lineups</button>
            <button class="cs-tab-btn" data-tab="events" onclick="tifoMatchTab('events')">Events</button>
            <button class="cs-tab-btn" data-tab="stats" onclick="tifoMatchTab('stats')">Stats</button>
            <button class="cs-tab-btn" data-tab="report" onclick="tifoMatchTab('report')">Report</button>
        </div>
        <div id="matchTabContent"></div>
    </div>`;
    main.innerHTML = html;
    window._currentMatch = match;
    window._tifoMatchBack = backFn || (() => renderPage('matches'));
    showMatchTab('lineups');

}

function showMatchTab(tab) {
    const match = window._currentMatch;
    if (!match) return;
    const container = document.getElementById('matchTabContent');
    if (!container) return;

    document.querySelectorAll('.cs-tab-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === tab);
    });

    switch (tab) {
        case 'lineups': container.innerHTML = buildLineupsHtml(match); break;
        case 'events': container.innerHTML = buildGoalsHtml(match); break;
        case 'stats': container.innerHTML = buildStatsHtml(match); break;
        case 'report': container.innerHTML = buildReportHtml(match); break;
        default: container.innerHTML = buildLineupsHtml(match); break;
    }
}

function buildLineupsHtml(match) {
    const posOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
    let html = '';

    const substitutionEvents = (match.events || [])
        .filter(e => e.eventType === 'SUBSTITUTION')
        .sort((a, b) => a.minute - b.minute);

    const buildTeamLineupData = (teamName, playerStats) => {
        const teamSubs = substitutionEvents.filter(e => e.teamName === teamName);
        const incomingNames = new Set(teamSubs.map(e => String(e.playerInName || '').trim()).filter(Boolean));
        const byName = new Map();

        (playerStats || []).forEach(p => {
            const key = String(p.playerName || '').trim();
            if (!key) return;
            byName.set(key, {
                ...p,
                position: p.position || '-',
                minutesPlayed: Number(p.minutesPlayed || 0),
                isStarter: !incomingNames.has(key),
                subInMinute: null,
                subOutMinute: null
            });
        });

        teamSubs.forEach(e => {
            const outName = String(e.playerOutName || '').trim();
            const inName = String(e.playerInName || '').trim();
            const minute = Number(e.minute || 0);

            if (outName) {
                const existingOut = byName.get(outName);
                if (existingOut) {
                    existingOut.isStarter = true;
                    existingOut.subOutMinute = minute;
                    if (!existingOut.minutesPlayed) existingOut.minutesPlayed = minute;
                } else {
                    byName.set(outName, {
                        playerId: null,
                        playerName: outName,
                        position: '-',
                        rating: null,
                        goals: 0,
                        assists: 0,
                        minutesPlayed: minute,
                        isStarter: true,
                        subInMinute: null,
                        subOutMinute: minute,
                        inferred: true
                    });
                }
            }

            if (inName) {
                const existingIn = byName.get(inName);
                if (existingIn) {
                    existingIn.isStarter = false;
                    existingIn.subInMinute = minute;
                } else {
                    byName.set(inName, {
                        playerId: null,
                        playerName: inName,
                        position: '-',
                        rating: null,
                        goals: 0,
                        assists: 0,
                        minutesPlayed: Math.max(0, 90 - minute),
                        isStarter: false,
                        subInMinute: minute,
                        subOutMinute: null,
                        inferred: true
                    });
                }
            }
        });

        return {
            rows: [...byName.values()],
            subs: teamSubs
        };
    };

    const sortLineupRows = (a, b) => {
        if (a.isStarter !== b.isStarter) return a.isStarter ? -1 : 1;
        const posDiff = (posOrder[a.position] ?? 5) - (posOrder[b.position] ?? 5);
        if (posDiff !== 0) return posDiff;
        const minutesDiff = (Number(b.minutesPlayed) || 0) - (Number(a.minutesPlayed) || 0);
        if (minutesDiff !== 0) return minutesDiff;
        const ratingDiff = (Number(b.rating) || 0) - (Number(a.rating) || 0);
        if (ratingDiff !== 0) return ratingDiff;
        return String(a.playerName || '').localeCompare(String(b.playerName || ''));
    };

    const renderLineup = (title, playerStats, teamId) => {
        const { rows, subs } = buildTeamLineupData(title, playerStats);
        if (!rows || rows.length === 0) return `<h3>${title}</h3><p style="color:#aaa;">No lineup data</p>`;
        const sorted = [...rows].sort(sortLineupRows);
        let h = `<h3>${title}</h3>
            <div style="display:flex; gap:10px; padding:4px 12px; color:#888; font-size:0.8em;">
                <div class="cs-lineup-pos">POS</div>
                <div class="cs-lineup-name">Name</div>
                <div class="cs-lineup-rating">Rat</div>
                <div class="cs-lineup-stat">&#9917;</div>
                <div class="cs-lineup-stat">A</div>
                <div class="cs-lineup-stat">Min</div>
                <div class="cs-lineup-stat">P%</div>
                <div class="cs-lineup-stat">Tkl</div>
                <div class="cs-lineup-stat">KP</div>
            </div>`;
        sorted.forEach(p => {
            const ratingValue = Number(p.rating);
            const ratingColor = ratingValue >= 7.5 ? '#4caf50' : ratingValue >= 6.5 ? '#ffd700' : ratingValue >= 5.5 ? '#ff9800' : '#f44336';
            const clickableName = p.playerId
                ? `<span class="cs-clickable" onclick="tifoOpenMatchPlayer(${p.playerId}, ${teamId})">${p.playerName}</span>`
                : escapeHtml(p.playerName || '?');
            const tags = [];
            if (!p.isStarter) tags.push('<span style="color:#d5b36a;">SUB</span>');
            if (p.subOutMinute != null) tags.push(`<span style="color:#ff9c7a;">&#8595; ${p.subOutMinute}'</span>`);
            if (p.subInMinute != null) tags.push(`<span style="color:#8ed39a;">&#8593; ${p.subInMinute}'</span>`);
            h += `<div class="cs-lineup-row">
                <div class="cs-lineup-pos">${p.position}</div>
                <div class="cs-lineup-name">${clickableName}${tags.length ? `<div style="font-size:0.72em; color:#8f99a3; margin-top:2px;">${tags.join(' • ')}</div>` : ''}</div>
                <div class="cs-lineup-rating" style="color:${Number.isFinite(ratingValue) ? ratingColor : '#9aa0a6'}">${Number.isFinite(ratingValue) ? ratingValue.toFixed(1) : '-'}</div>
                <div class="cs-lineup-stat">${p.goals || ''}</div>
                <div class="cs-lineup-stat">${p.assists || ''}</div>
                <div class="cs-lineup-stat">${p.minutesPlayed || 0}</div>
                <div class="cs-lineup-stat">${p.passesAttempted > 0 ? Math.round(p.passesCompleted / p.passesAttempted * 100) + '%' : '-'}</div>
                <div class="cs-lineup-stat">${p.tackles || 0}</div>
                <div class="cs-lineup-stat">${p.keyPasses || 0}</div>
            </div>`;
        });
        if (subs.length > 0) {
            h += `<div style="margin-top:10px; padding:10px 12px; border-top:1px solid rgba(255,255,255,0.08);">
                <div style="font-size:0.82em; color:#8f99a3; margin-bottom:6px;">Substitutions</div>
                ${subs.map(s => `<div style="font-size:0.88em; color:#d7dbe0; margin-bottom:4px;"><strong>${s.minute}'</strong> &#128257; ${escapeHtml(s.playerOutName || '?')} &rarr; ${escapeHtml(s.playerInName || '?')}</div>`).join('')}
            </div>`;
        }
        return h;
    };

    html += renderLineup(match.homeTeamName, match.homePlayerStats, match.homeTeamId);
    html += `<div style="margin-top:20px;"></div>`;
    html += renderLineup(match.awayTeamName, match.awayPlayerStats, match.awayTeamId);
    return html;
}

function buildGoalsHtml(match) {
    const keyEvents = (match.events || []).filter(e => ['GOAL', 'PENALTY', 'SUBSTITUTION', 'INJURY', 'VAR_REVIEW', 'YELLOW_CARD', 'RED_CARD'].includes(e.eventType));
    if (keyEvents.length === 0) return '<p style="color:#aaa;">No key events.</p>';
    let html = '<div class="cs-event-list">';
    keyEvents.sort((a, b) => a.minute - b.minute).forEach(g => {
        const teamLink = renderMatchTeamLink(match, g.teamName);
        const playerLink = renderMatchPlayerLink(match, g.teamName, g.playerName);
        const outLink = renderMatchPlayerLink(match, g.teamName, g.playerOutName);
        const inLink = renderMatchPlayerLink(match, g.teamName, g.playerInName);
        const assistLink = renderMatchPlayerLink(match, g.teamName, g.assistName);
        const icon = g.eventType === 'GOAL' ? '&#9917;' : g.eventType === 'PENALTY' ? '&#127919;' : g.eventType === 'SUBSTITUTION' ? '&#128257;' : g.eventType === 'INJURY' ? '&#10060;' : g.eventType === 'RED_CARD' ? '&#128997;' : g.eventType === 'YELLOW_CARD' ? '&#128998;' : '&#128269;';
        let headline = `${teamLink}`;
        if (g.eventType === 'GOAL') {
            headline = `${playerLink} scores for ${teamLink}${g.assistName ? ` <span class="cs-event-extra">Assist: ${assistLink}</span>` : ''}`;
        } else if (g.eventType === 'PENALTY') {
            headline = `${playerLink} ${g.penaltyScored ? 'converts' : 'misses'} the penalty for ${teamLink}`;
        } else if (g.eventType === 'SUBSTITUTION') {
            headline = `${outLink} off, ${inLink} on <span class="cs-event-extra">(${teamLink})</span>`;
        } else if (g.eventType === 'INJURY') {
            headline = `Injury concern for ${playerLink} <span class="cs-event-extra">(${teamLink})</span>`;
        } else if (g.eventType === 'YELLOW_CARD') {
            headline = `Yellow card for ${playerLink} <span class="cs-event-extra">(${teamLink})</span>`;
        } else if (g.eventType === 'RED_CARD') {
            headline = `Red card for ${playerLink} <span class="cs-event-extra">(${teamLink})</span>`;
        } else if (g.eventType === 'VAR_REVIEW') {
            headline = `VAR review involving ${teamLink}`;
        }
        html += `<div class="cs-event-row">
            <div class="cs-event-minute">${g.minute}'</div>
            <div class="cs-event-icon">${icon}</div>
            <div class="cs-event-body">
                <div class="cs-event-title">${headline}</div>
                <div class="cs-event-extra">${escapeHtml(g.description || describeEventShort(g))}</div>
            </div>
            <div class="cs-event-score">${escapeHtml(g.scoreAfterGoal || '')}</div>
        </div>`;
    });
    return html + '</div>';
}

function getMatchPlayerTeamMeta(match, playerName) {
    if (!playerName) return null;
    if ((match?.homePlayerStats || []).some(p => p.playerName === playerName)) {
        return { teamId: match.homeTeamId, teamName: match.homeTeamName };
    }
    if ((match?.awayPlayerStats || []).some(p => p.playerName === playerName)) {
        return { teamId: match.awayTeamId, teamName: match.awayTeamName };
    }
    return null;
}

function renderMatchTeamLink(match, teamName) {
    const teamId = teamName === match.homeTeamName ? match.homeTeamId : match.awayTeamId;
    const safeName = escapeHtml(teamName || '?');
    return teamId ? `<span class="cs-clickable" onclick="tifoTeamDetail(${teamId})">${safeName}</span>` : safeName;
}

function renderMatchPlayerLink(match, teamName, playerName) {
    const safeName = escapeHtml(playerName || '?');
    if (!playerName) return safeName;
    const meta = getMatchPlayerTeamMeta(match, playerName)
        || { teamId: teamName === match.homeTeamName ? match.homeTeamId : match.awayTeamId };
    const playerId = findMatchPlayerIdByName(match, teamName, playerName);
    return playerId && meta?.teamId
        ? `<span class="cs-clickable" onclick="tifoOpenMatchPlayer(${playerId}, ${meta.teamId})">${safeName}</span>`
        : safeName;
}

function findMatchPlayerIdByName(match, teamName, playerName) {
    if (!playerName) return null;
    const pool = teamName === match.homeTeamName ? (match.homePlayerStats || []) : (match.awayPlayerStats || []);
    const player = pool.find(p => p.playerName === playerName);
    return player?.playerId || null;
}

function buildStatsHtml(match) {
    const events = match.events || [];
    const homeName = match.homeTeamName;
    const awayName = match.awayTeamName;
    const countFor = (type, team) => events.filter(e => e.eventType === type && e.teamName === team).length;
    const combinedCountFor = (types, team) => types.reduce((sum, type) => sum + countFor(type, team), 0);
    const homeControlScore = combinedCountFor(['SHOT_ON_TARGET', 'SHOT_OFF_TARGET'], homeName) * 2 + countFor('CORNER', homeName) + countFor('FREE_KICK', homeName);
    const awayControlScore = combinedCountFor(['SHOT_ON_TARGET', 'SHOT_OFF_TARGET'], awayName) * 2 + countFor('CORNER', awayName) + countFor('FREE_KICK', awayName);
    const totalControl = homeControlScore + awayControlScore;
    const homeControl = totalControl === 0 ? 50 : Math.round((homeControlScore * 100) / totalControl);
    const awayControl = 100 - homeControl;

    return `<div class="cs-stats-shell">
        <div class="cs-control-bar">
            <div class="cs-control-side">
                <span class="cs-control-team">${escapeHtml(homeName)}</span>
                <strong>${homeControl}%</strong>
            </div>
            <div class="cs-control-track"><span style="width:${homeControl}%;"></span></div>
            <div class="cs-control-side right">
                <strong>${awayControl}%</strong>
                <span class="cs-control-team">${escapeHtml(awayName)}</span>
            </div>
        </div>
        <table class="cs-table">
            <thead><tr><th>Stat</th><th><span class="cs-clickable" onclick="tifoTeamDetail(${match.homeTeamId})">${homeName}</span></th><th><span class="cs-clickable" onclick="tifoTeamDetail(${match.awayTeamId})">${awayName}</span></th></tr></thead>
            <tbody>
                <tr><td>Goals</td><td>${match.homeGoals}</td><td>${match.awayGoals}</td></tr>
                <tr><td>Total shots</td><td>${combinedCountFor(['SHOT_ON_TARGET', 'SHOT_OFF_TARGET'], homeName)}</td><td>${combinedCountFor(['SHOT_ON_TARGET', 'SHOT_OFF_TARGET'], awayName)}</td></tr>
                <tr><td>Shots on target</td><td>${countFor('SHOT_ON_TARGET', homeName)}</td><td>${countFor('SHOT_ON_TARGET', awayName)}</td></tr>
                <tr><td>Shots off target</td><td>${countFor('SHOT_OFF_TARGET', homeName)}</td><td>${countFor('SHOT_OFF_TARGET', awayName)}</td></tr>
                <tr><td>Corners</td><td>${countFor('CORNER', homeName)}</td><td>${countFor('CORNER', awayName)}</td></tr>
                <tr><td>Free kicks</td><td>${countFor('FREE_KICK', homeName)}</td><td>${countFor('FREE_KICK', awayName)}</td></tr>
                <tr><td>Offsides</td><td>${countFor('OFFSIDE', homeName)}</td><td>${countFor('OFFSIDE', awayName)}</td></tr>
                <tr><td>Fouls</td><td>${countFor('FOUL', homeName)}</td><td>${countFor('FOUL', awayName)}</td></tr>
                <tr><td>Yellow cards</td><td style="color:#ff9800;">${countFor('YELLOW_CARD', homeName)}</td><td style="color:#ff9800;">${countFor('YELLOW_CARD', awayName)}</td></tr>
                <tr><td>Red cards</td><td style="color:#f44336;">${countFor('RED_CARD', homeName)}</td><td style="color:#f44336;">${countFor('RED_CARD', awayName)}</td></tr>
                <tr><td>Penalties</td><td>${countFor('PENALTY', homeName)}</td><td>${countFor('PENALTY', awayName)}</td></tr>
                <tr><td>Control estimate</td><td>${homeControl}%</td><td>${awayControl}%</td></tr>
            </tbody>
        </table>
    </div>`;
}

function buildReportHtml(match) {
    const reportText = match.report || match.summary || 'No full report available for this match.';
    const structured = buildStructuredSectionsHtml(reportText, { bodyClass: 'cs-report-line' });
    const motm = findManOfTheMatch(match);
    const motmMeta = motm ? getMatchPlayerTeamMeta(match, motm.playerName) : null;
    const motmId = motm && motmMeta ? findMatchPlayerIdByName(match, motmMeta.teamName, motm.playerName) : null;
    const motmHtml = motm
        ? (motmId && motmMeta?.teamId
            ? `<span class="cs-clickable" onclick="tifoOpenMatchPlayer(${motmId}, ${motmMeta.teamId})">${escapeHtml(motm.playerName)}</span> (${Number(motm.rating || 0).toFixed(1)})`
            : `${escapeHtml(motm.playerName)} (${Number(motm.rating || 0).toFixed(1)})`)
        : 'Not available';
    return `<div class="cs-report-shell">
        <div class="cs-report-summary">
            <div><strong>Match file:</strong> ${escapeHtml(match.homeTeamName)} ${match.homeGoals}:${match.awayGoals} ${escapeHtml(match.awayTeamName)}</div>
            <div><strong>Standout:</strong> ${motmHtml}</div>
        </div>
        ${buildMatchReportSnapshot(match)}
        <div class="cs-report-body cs-report-body-structured">${structured.html}</div>
    </div>`;
}

// --- Tactics ---
// --- Tactics ---
function renderTactics(el) {
    const tactics = gameState?.tactics || {};
    const roster = gameState?.roster || [];
    const isMobile = window.matchMedia('(max-width: 768px)').matches;
    const formations = ['4-4-2', '4-3-3', '4-2-3-1', '4-1-4-1', '3-5-2', '3-4-3', '5-3-2', '5-4-1', '4-5-1'];
    const styles = ['BALANCED', 'ATTACKING', 'DEFENSIVE', 'COUNTER'];

    const formationToSlots = (formation) => {
        const parts = String(formation || '4-4-2').split('-').map(v => Number(v) || 0);
        const def = parts[0] ?? 4;
        const mid = parts[1] ?? 4;
        const att = parts[2] ?? 2;
        const slots = [{ label: 'GK', role: 'GK' }];
        for (let i = 0; i < def; i++) slots.push({ label: `DEF ${i + 1}`, role: 'DEF' });
        for (let i = 0; i < mid; i++) slots.push({ label: `MID ${i + 1}`, role: 'MID' });
        for (let i = 0; i < att; i++) slots.push({ label: `ATT ${i + 1}`, role: 'ATT' });
        return slots.slice(0, 11);
    };

    const state = {
        formation: tactics.formation || '4-4-2',
        style: styles.includes(tactics.style) ? tactics.style : 'BALANCED',
        starterIds: Array.isArray(tactics.starterIds) ? tactics.starterIds.slice(0, 11).map(Number) : [],
        benchIds: Array.isArray(tactics.benchIds) ? tactics.benchIds.slice(0, 7).map(Number) : []
    };

    const slots = formationToSlots(state.formation);
    const byId = id => roster.find(p => Number(p.id) === Number(id));

    const canPlayRole = (player, role) => {
        const pos = String(player?.position || "").toUpperCase();
        if (role === "GK") return pos === "GK";
        if (role === "DEF") return pos === "DEF";
        if (role === "MID") return pos === "MID" || pos === "WNG";
        if (role === "ATT") return pos === "ATT" || pos === "WNG" || pos === "MID";
        return false;
    };

    const isEligibleForRole = (player, role) => {
        return canPlayRole(player, role);
    };

    const allSelected = () => new Set([
        ...state.starterIds.filter(Boolean).map(Number),
        ...state.benchIds.filter(Boolean).map(Number)
    ]);

    const ensureState = () => {
        const used = new Set();
        state.starterIds = slots.map((slot, idx) => {
            const id = Number(state.starterIds[idx] || 0);
            const p = byId(id);
            if (!id || !p || used.has(id) || !canPlayRole(p, slot.role)) return null;
            used.add(id);
            return id;
        });

        // NO AUTO-FILL - let user choose players explicitly

        state.benchIds = state.benchIds.filter(id => {
            const num = Number(id);
            return num && !used.has(num);
        }).slice(0, 7).map(Number);

        state.benchIds.forEach(id => used.add(Number(id)));
    };
    ensureState();

    const starterOptions = (role, current) => {
        const used = allSelected();
        if (current) used.delete(Number(current));
        return roster.filter(p => !used.has(Number(p.id)));
    };

    const benchOptions = (current) => {
        const used = allSelected();
        if (current) used.delete(Number(current));
        return roster.filter(p => !used.has(Number(p.id)));
    };

    // =====================================================================
    // NOVA LOGIKA: Svaki slot prikazuje SVE igrače koji zadovoljavaju uslove
    // za tu poziciju (uključujući igrače iz klupe i drugih slotova).
    // Kada se izabere igrač koji je već u nekom drugom slotu/klupi,
    // automatski se swapuju (stari igrač se skida s tog mesta).
    // =====================================================================

    const renderStarterSlotOptions = (slot, slotIdx) => {
        // Prikazi sve igrače koji mogu igrati na ovoj poziciji
        // (bez obzira da li su vec selektovani negde)
        const eligiblePlayers = roster.filter(p => canPlayRole(p, slot.role));
        const current = Number(state.starterIds[slotIdx] || 0);
        return eligiblePlayers.map(p => {
            const pid = Number(p.id);
            let label = `${p.name} (${p.position}, R ${p.rating})`;
            // Oznaci gde je igrac trenutno
            const inStarterSlot = state.starterIds.findIndex((id, i) => Number(id) === pid && i !== slotIdx);
            const inBenchSlot = state.benchIds.findIndex(id => Number(id) === pid);
            if (inStarterSlot >= 0) label += ` [${slots[inStarterSlot].label}]`;
            else if (inBenchSlot >= 0) label += ` [Bench ${inBenchSlot + 1}]`;
            return `<option value="${pid}" ${pid === current ? 'selected' : ''}>${label}</option>`;
        }).join('');
    };

    const renderBenchSlotOptions = (benchIdx) => {
        // Klupa: prikazi sve igrače koji nisu GK (ili bilo koji slobodan)
        const current = Number(state.benchIds[benchIdx] || 0);
        return roster.map(p => {
            const pid = Number(p.id);
            let label = `${p.name} (${p.position}, R ${p.rating})`;
            const inStarterSlot = state.starterIds.findIndex(id => Number(id) === pid);
            const inBenchSlot = state.benchIds.findIndex((id, i) => Number(id) === pid && i !== benchIdx);
            if (inStarterSlot >= 0) label += ` [${slots[inStarterSlot].label}]`;
            else if (inBenchSlot >= 0) label += ` [Bench ${inBenchSlot + 1}]`;
            return `<option value="${pid}" ${pid === current ? 'selected' : ''}>${label}</option>`;
        }).join('');
    };

    const renderDesktop = () => {
        const starters = slots.map((slot, idx) => {
            const current = Number(state.starterIds[idx] || 0);
            const currentPlayer = byId(current);
            const roleOk = currentPlayer ? canPlayRole(currentPlayer, slot.role) : true;
            const warningStyle = current && !roleOk ? 'border:1px solid #f44336;' : '';
            return `<label class="training-group-row"><span class="group-tag">${slot.label}</span><select class="cs-starter-select" data-slot="${idx}" style="${warningStyle}"><option value="">-- Empty --</option>${renderStarterSlotOptions(slot, idx)}</select></label>`;
        }).join('');

        const bench = Array.from({ length: 7 }).map((_, idx) => {
            const current = Number(state.benchIds[idx] || 0);
            return `<label class="training-group-row"><span class="group-tag">Bench ${idx + 1}</span><select class="cs-bench-select" data-slot="${idx}"><option value="">-- Empty --</option>${renderBenchSlotOptions(idx)}</select></label>`;
        }).join('');

        return `<div style="display:grid; grid-template-columns:1fr 1fr; gap:20px;">
            <div>
                <h3>Starting XI</h3>
                <p style="color:#9aa0a6; font-size:0.82em; margin:0 0 8px;">Možeš izabrati igrača iz klupe ili drugog slota — automatski će se zameniti.</p>
                <div style="display:grid; gap:8px;">${starters}</div>
            </div>
            <div>
                <h3>Bench (7)</h3>
                <div style="display:grid; gap:8px;">${bench}</div>
            </div>
        </div>`;
    };

    const renderMobile = () => {
        const starters = slots.map((slot, idx) => {
            const current = Number(state.starterIds[idx] || 0);
            return `<label class="training-group-row"><span class="group-tag">${slot.label}</span><select class="cs-starter-select" data-slot="${idx}"><option value="">-- Empty --</option>${renderStarterSlotOptions(slot, idx)}</select></label>`;
        }).join('');

        const bench = Array.from({ length: 7 }).map((_, idx) => {
            const current = Number(state.benchIds[idx] || 0);
            return `<label class="training-group-row"><span class="group-tag">Bench ${idx + 1}</span><select class="cs-bench-select" data-slot="${idx}"><option value="">-- Empty --</option>${renderBenchSlotOptions(idx)}</select></label>`;
        }).join('');

        return `<h3 style="margin-top:20px;">Starting XI</h3>
            <p style="color:#9aa0a6; font-size:0.82em;">Možeš izabrati igrača iz klupe ili drugog slota — automatski će se zameniti.</p>
            ${starters}<h3 style="margin-top:18px;">Bench</h3>${bench}`;
    };

    let html = `<h2>Tactics</h2>
        <h3>Formation: <span id="currentFormation">${state.formation}</span></h3>
        <div class="cs-tactics-grid">`;
    formations.forEach(f => {
        const active = f === state.formation ? 'active' : '';
        html += `<div class="cs-tactics-btn ${active}" onclick="tifoSetTactics('${f}', null)">${f}</div>`;
    });
    html += `</div>
        <h3 style="margin-top:20px;">Style: <span id="currentStyle">${state.style}</span></h3>
        <div class="cs-tactics-grid">`;
    styles.forEach(s => {
        const active = s === state.style ? 'active' : '';
        html += `<div class="cs-tactics-btn ${active}" onclick="tifoSetTactics(null, '${s}')">${s}</div>`;
    });
    html += `</div>
        <p style="color:#9aa0a6; margin-top:10px;">Formation slots: DEF ${slots.filter(s => s.role === 'DEF').length}, MID ${slots.filter(s => s.role === 'MID').length}, ATT ${slots.filter(s => s.role === 'ATT').length}</p>
        ${isMobile ? renderMobile() : renderDesktop()}
        <div style="margin-top:12px;"><button class="big-button" onclick="tifoSaveLineup()">Save Starting XI + Bench</button></div>`;
    el.innerHTML = html;

    if (isMobile) {
        document.querySelectorAll('.cs-starter-select').forEach(sel => {
            sel.addEventListener('change', () => {
                const slot = Number(sel.dataset.slot);
                const id = Number(sel.value || 0) || null;
                if (id) {
                    state.starterIds = state.starterIds.map((v, i) => i !== slot && Number(v) === id ? null : v);
                    state.benchIds = state.benchIds.map(v => Number(v) === id ? null : v);
                }
                state.starterIds[slot] = id;
                gameState.tactics = { ...gameState.tactics, starterIds: state.starterIds, benchIds: state.benchIds, formation: state.formation, style: state.style };
                renderPage('tactics');
            });
        });

        document.querySelectorAll('.cs-bench-select').forEach(sel => {
            sel.addEventListener('change', () => {
                const slot = Number(sel.dataset.slot);
                const id = Number(sel.value || 0) || null;
                if (id) {
                    state.starterIds = state.starterIds.map(v => Number(v) === id ? null : v);
                    state.benchIds = state.benchIds.map((v, i) => i !== slot && Number(v) === id ? null : v);
                }
                state.benchIds[slot] = id;
                gameState.tactics = { ...gameState.tactics, starterIds: state.starterIds, benchIds: state.benchIds, formation: state.formation, style: state.style };
                renderPage('tactics');
            });
        });
        return;
    }

    // Desktop: Handle selects
    document.querySelectorAll('.cs-starter-select').forEach(sel => {
        sel.addEventListener('change', () => {
            const slot = Number(sel.dataset.slot);
            const id = Number(sel.value || 0) || null;
            if (id) {
                state.starterIds = state.starterIds.map((v, i) => i !== slot && Number(v) === id ? null : v);
                state.benchIds = state.benchIds.map(v => Number(v) === id ? null : v);
            }
            state.starterIds[slot] = id;
            gameState.tactics = { ...gameState.tactics, starterIds: state.starterIds, benchIds: state.benchIds, formation: state.formation, style: state.style };
            renderPage('tactics');
        });
    });

    document.querySelectorAll('.cs-bench-select').forEach(sel => {
        sel.addEventListener('change', () => {
            const slot = Number(sel.dataset.slot);
            const id = Number(sel.value || 0) || null;
            if (id) {
                state.starterIds = state.starterIds.map(v => Number(v) === id ? null : v);
                state.benchIds = state.benchIds.map((v, i) => i !== slot && Number(v) === id ? null : v);
            }
            state.benchIds[slot] = id;
            gameState.tactics = { ...gameState.tactics, starterIds: state.starterIds, benchIds: state.benchIds, formation: state.formation, style: state.style };
            renderPage('tactics');
        });
    });
}

async function setTactics(formation, style) {
    const params = new URLSearchParams();
    if (formation) params.append('formation', formation);
    if (style) params.append('style', style);

    const res = await csApi(`/api/cs/tactics?${params.toString()}`, { method: 'PUT' });
    if (res && res.ok) {
        const updated = await res.json();
        gameState.tactics = updated;
        renderPage('tactics');
    }
}

async function saveLineup() {
    const starterIds = (gameState?.tactics?.starterIds || []).filter(Boolean).slice(0, 11);
    const used = new Set(starterIds.map(Number));
    const benchIds = (gameState?.tactics?.benchIds || []).filter(id => id && !used.has(Number(id))).slice(0, 7);
    const res = await csApi('/api/cs/tactics/starters', {
        method: 'PUT',
        body: JSON.stringify({ starterIds, benchIds })
    });
    if (res && res.ok) {
        const updated = await res.json();
        gameState.tactics = updated;
        renderPage('tactics');
    }
}
async function renderTopScorers(el) {
    el.innerHTML = '<h2>Top Scorers</h2><p>Loading...</p>';
    const res = await csApi('/api/cs/top-scorers');
    if (!res || !res.ok) { el.innerHTML = '<h2>Top Scorers</h2><p style="color:#aaa;">No data</p>'; return; }
    const data = await res.json();
    await ensurePlayerIndexLoaded();
    rebuildTeamIndex();

    let html = '<h2>&#127941; Top Scorers</h2>';
    if (data.length === 0) { html += '<p style="color:#aaa;">No goals this season.</p>'; }
    else {
        data.forEach((p, i) => {
            html += `<div class="cs-rank-row cs-clickable" onclick="tifoOpenRankedPlayer(${p.playerId}, '${esc(p.teamName)}')">
                <div class="cs-rank-num">${i + 1}</div>
                <div class="cs-rank-name">${p.name} <span class="cs-rank-team">${p.teamName} (${p.position})</span></div>
                <div class="cs-rank-val">&#9917; ${p.goals}</div>
            </div>`;
        });
    }
    el.innerHTML = html;
}

// --- Top Assists ---
async function renderTopAssists(el) {
    el.innerHTML = '<h2>Top Assists</h2><p>Loading...</p>';
    const res = await csApi('/api/cs/top-assists');
    if (!res || !res.ok) { el.innerHTML = '<h2>Top Assists</h2><p style="color:#aaa;">No data</p>'; return; }
    const data = await res.json();
    await ensurePlayerIndexLoaded();
    rebuildTeamIndex();

    let html = '<h2>🅰️ Top Assists</h2>';
    if (data.length === 0) { html += '<p style="color:#aaa;">No assists this season.</p>'; }
    else {
        data.forEach((p, i) => {
            html += `<div class="cs-rank-row cs-clickable" onclick="tifoOpenRankedPlayer(${p.playerId}, '${esc(p.teamName)}')">
                <div class="cs-rank-num">${i + 1}</div>
                <div class="cs-rank-name">${p.name} <span class="cs-rank-team">${p.teamName} (${p.position})</span></div>
                <div class="cs-rank-val">🅰️ ${p.assists}</div>
            </div>`;
        });
    }
    el.innerHTML = html;
}

function openRankedPlayer(playerId, teamName) {
    const teamId = csPlayerIdToTeamId.get(playerId) || csTeamNameToId.get(teamName);
    if (!teamId) {
        renderPage('leagueTable');
        return;
    }
    openMatchPlayer(playerId, teamId);
}

async function renderTransfers(el) {
    el.innerHTML = `<h2>&#128260; Transfers</h2><p>Loading market...</p>`;
    const res = await csApi('/api/cs/transfers');
    if (!res || !res.ok) {
        el.innerHTML = `<h2>&#128260; Transfers</h2><p style="color:#aaa;">Transfer centre unavailable.</p>`;
        return;
    }
    const data = await res.json();
    gameState.transferMarket = data.market || [];
    if (typeof data.budget === 'number' && gameState?.userTeam) {
        gameState.userTeam.budget = data.budget;
    }

    const listed = data.listedPlayers || [];
    const market = data.availableTargets || [];
    const incomingOffers = data.incomingOffers || [];
    const roster = [...(gameState?.roster || [])].sort((a, b) => Number(b.rating || 0) - Number(a.rating || 0));
    const listableIds = new Set(listed.map(item => Number(item.playerId)));
    const averageAsk = market.length ? Math.round(market.reduce((sum, item) => sum + Number(item.askingPrice || 0), 0) / market.length) : 0;

    el.innerHTML = `<h2>&#128260; Transfers</h2>
        <div class="cs-transfer-shell">
            <div class="cs-transfer-hero">
                <div>
                    <div class="cs-section-label">Transfer centre</div>
                    <div class="cs-note-title">Market board</div>
                    <div class="cs-note-text">List surplus players, track external interest and buy directly from clubs that are ready to sell.</div>
                </div>
                <div class="cs-transfer-hero-stats">
                    <div><strong>${formatMoney(gameState?.userTeam?.budget || 0)}</strong><span>Budget</span></div>
                    <div><strong>${listed.length}</strong><span>Listed</span></div>
                    <div><strong>${market.length}</strong><span>Targets</span></div>
                    <div><strong>${averageAsk ? formatMoney(averageAsk) : '—'}</strong><span>Avg ask</span></div>
                </div>
            </div>

            <div class="cs-transfer-grid">
                <section class="cs-transfer-panel">
                    <div class="cs-transfer-panel-head">
                        <h3>Your Listed Players</h3>
                        <span>${listed.length} active</span>
                    </div>
                    ${listed.length ? listed.map(item => `
                        <div class="cs-transfer-row">
                            <div>
                                <div class="cs-transfer-name">${escapeHtml(item.playerName)} <span class="cs-transfer-meta">${escapeHtml(item.position)} · ${item.age} · R ${item.rating}</span></div>
                                <div class="cs-note-text">Ask ${formatMoney(item.askingPrice)} · Interest: ${escapeHtml((item.interestedClubs || []).length ? item.interestedClubs.join(', ') : 'No clubs yet')}</div>
                            </div>
                            <button class="big-button cs-transfer-btn-muted" onclick="tifoRemoveTransfer(${item.playerId})">Remove</button>
                        </div>
                    `).join('') : `<div class="cs-transfer-empty">No one listed yet.</div>`}
                </section>

                <section class="cs-transfer-panel">
                    <div class="cs-transfer-panel-head">
                        <h3>Incoming Offers</h3>
                        <span>${incomingOffers.length} live</span>
                    </div>
                    ${incomingOffers.length ? incomingOffers.map(item => `
                        <div class="cs-transfer-row">
                            <div>
                                <div class="cs-transfer-name">${escapeHtml(item.playerName)} <span class="cs-transfer-meta">${escapeHtml(item.bestOfferClub || '?')} · ask ${formatMoney(item.askingPrice)}</span></div>
                                <div class="cs-note-text">Current bid ${formatMoney(item.bestOffer || 0)} · position ${escapeHtml(item.position)} · rating ${item.rating}</div>
                            </div>
                            <div class="cs-transfer-actions">
                                <button class="big-button" onclick="tifoAcceptTransfer(${item.playerId})">Accept</button>
                                <button class="big-button cs-transfer-btn-muted" onclick="tifoRejectTransfer(${item.playerId})">Reject</button>
                            </div>
                        </div>
                    `).join('') : `<div class="cs-transfer-empty">No formal offers on your listed players yet.</div>`}
                </section>

                <section class="cs-transfer-panel">
                    <div class="cs-transfer-panel-head">
                        <h3>Market Targets</h3>
                        <span>${market.length} available</span>
                    </div>
                    ${market.length ? market.map(item => `
                        <div class="cs-transfer-row">
                            <div>
                                <div class="cs-transfer-name">${escapeHtml(item.playerName)} <span class="cs-transfer-meta">${escapeHtml(item.sellerTeamName)} · ${escapeHtml(item.position)} · ${item.age} · R ${item.rating}</span></div>
                                <div class="cs-note-text">Value ${formatMoney(item.marketValue)} · Asking ${formatMoney(item.askingPrice)} · Interest ${escapeHtml((item.interestedClubs || []).length ? item.interestedClubs.join(', ') : 'Quiet market')}</div>
                            </div>
                            <div class="cs-transfer-actions">
                                <button class="big-button cs-transfer-btn-muted" onclick="tifoBidTransfer(${item.playerId}, ${item.askingPrice})">Bid</button>
                                <button class="big-button" onclick="tifoBuyTransfer(${item.playerId})" ${Number(gameState?.userTeam?.budget || 0) < Number(item.askingPrice || 0) ? 'disabled' : ''}>Buy</button>
                            </div>
                        </div>
                    `).join('') : `<div class="cs-transfer-empty">No external listings right now.</div>`}
                </section>
            </div>

            <section class="cs-transfer-panel">
                <div class="cs-transfer-panel-head">
                    <h3>Squad Availability</h3>
                    <span>${roster.length} players</span>
                </div>
                <div class="cs-transfer-squad-table">
                    <table class="cs-table">
                        <thead><tr><th>Name</th><th>Pos</th><th>Age</th><th>Rat</th><th>Value</th><th>Status</th><th>Action</th></tr></thead>
                        <tbody>
                            ${roster.map(player => `
                                <tr>
                                    <td>${escapeHtml(player.name)}</td>
                                    <td>${escapeHtml(player.position)}</td>
                                    <td>${player.age}</td>
                                    <td>${player.rating}</td>
                                    <td>${formatMoney(player.value)}</td>
                                    <td>${listableIds.has(Number(player.id)) ? 'Listed' : 'Available'}</td>
                                    <td>${listableIds.has(Number(player.id))
                                        ? '<span class="cs-transfer-status-pill listed">On market</span>'
                                        : `<button class="big-button cs-transfer-btn-small" onclick="tifoListTransfer(${player.id})">List</button>`}</td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            </section>
        </div>`;
}

async function listTransferPlayer(playerId) {
    const player = (gameState?.roster || []).find(item => Number(item.id) === Number(playerId));
    if (!player) return;
    const suggested = Math.max(25000, Math.round(Number(player.value || 0) || player.rating * 18000));
    const raw = window.prompt(`Set asking price for ${player.name}:`, String(suggested));
    if (raw == null) return;
    const askingPrice = Number(raw);
    if (!Number.isFinite(askingPrice) || askingPrice <= 0) {
        window.alert('Enter a valid asking price.');
        return;
    }
    const res = await csApi(`/api/cs/transfers/list/${playerId}`, {
        method: 'POST',
        body: JSON.stringify({ askingPrice })
    });
    if (!res || !res.ok) {
        const err = await res?.json().catch(() => null);
        window.alert(err?.error || 'Could not list player.');
        return;
    }
    renderPage('transfers');
}

async function removeTransferPlayer(playerId) {
    const res = await csApi(`/api/cs/transfers/list/${playerId}`, { method: 'DELETE' });
    if (!res || !res.ok) {
        const err = await res?.json().catch(() => null);
        window.alert(err?.error || 'Could not remove listing.');
        return;
    }
    renderPage('transfers');
}

async function buyTransferPlayer(playerId) {
    const res = await csApi(`/api/cs/transfers/buy/${playerId}`, { method: 'POST' });
    if (!res || !res.ok) {
        const err = await res?.json().catch(() => null);
        window.alert(err?.error || 'Deal failed.');
        return;
    }
    const data = await res.json();
    if (data.roster) gameState.roster = data.roster;
    if (typeof data.budget === 'number' && gameState?.userTeam) gameState.userTeam.budget = data.budget;
    if (data.market) gameState.transferMarket = data.market;
    renderPage('transfers');
}

async function bidTransferPlayer(playerId, askingPrice) {
    const suggested = Math.max(10000, Math.round(Number(askingPrice || 0) * 0.88));
    const raw = window.prompt('Enter your offer:', String(suggested));
    if (raw == null) return;
    const offer = Number(raw);
    if (!Number.isFinite(offer) || offer <= 0) {
        window.alert('Enter a valid offer.');
        return;
    }
    const res = await csApi(`/api/cs/transfers/bid/${playerId}`, {
        method: 'POST',
        body: JSON.stringify({ offer })
    });
    if (!res || !res.ok) {
        const err = await res?.json().catch(() => null);
        window.alert(err?.error || 'Bid failed.');
        return;
    }
    const data = await res.json();
    if (typeof data.budget === 'number' && gameState?.userTeam) gameState.userTeam.budget = data.budget;
    if (data.roster) gameState.roster = data.roster;
    if (data.market) gameState.transferMarket = data.market;
    window.alert(data.message || 'Bid processed.');
    renderPage('transfers');
}

async function acceptTransferOffer(playerId) {
    const res = await csApi(`/api/cs/transfers/accept/${playerId}`, { method: 'POST' });
    if (!res || !res.ok) {
        const err = await res?.json().catch(() => null);
        window.alert(err?.error || 'Could not accept offer.');
        return;
    }
    const data = await res.json();
    if (typeof data.budget === 'number' && gameState?.userTeam) gameState.userTeam.budget = data.budget;
    if (data.roster) gameState.roster = data.roster;
    if (data.market) gameState.transferMarket = data.market;
    window.alert(data.message || 'Offer accepted.');
    renderPage('transfers');
}

async function rejectTransferOffer(playerId) {
    const res = await csApi(`/api/cs/transfers/reject/${playerId}`, { method: 'POST' });
    if (!res || !res.ok) {
        const err = await res?.json().catch(() => null);
        window.alert(err?.error || 'Could not reject offer.');
        return;
    }
    const data = await res.json();
    if (typeof data.budget === 'number' && gameState?.userTeam) gameState.userTeam.budget = data.budget;
    if (data.market) gameState.transferMarket = data.market;
    window.alert(data.message || 'Offer rejected.');
    renderPage('transfers');
}

// --- Next Round ---
async function nextRound() {
    const btns = [document.getElementById('nextRoundBtn'), document.getElementById('nextRoundBtnMobileTop')].filter(Boolean);
    btns.forEach(btn => {
        btn.dataset.originalText = btn.textContent;
        btn.disabled = true;
        btn.textContent = 'Simulating...';
    });

    try {
        const res = await csApi('/api/cs/next-round', { method: 'POST' });
        if (!res || !res.ok) {
            const err = await res?.json();
            alert(err?.error || 'Simulation error');
            return;
        }
        const data = await res.json();

        if (data.seasonRestarted) {
            if (data.table) gameState.leagueTable = data.table;
            if (data.roster) gameState.roster = data.roster;
            if (data.seasonYear) gameState.seasonYear = data.seasonYear;
            gameState.currentRound = 1;
            gameState.matchHistory = [];
            allRoundResults = {};
            const stateRes = await csApi('/api/cs/state');
            if (stateRes && stateRes.ok) {
                gameState = await stateRes.json();
            }
            rebuildRoundResultsFromSchedule();
            rebuildTeamIndex();
            csPlayerIndexLoaded = false;
            ensurePlayerIndexLoaded();
            inboxUnreadCount = (gameState?.inbox || []).filter(msg => !msg?.read).length;
            updateInboxRibbon();
            updateRoundInfo();
            showModal(
                'New Season Started',
                `<p>A new season has started.</p>
                 <p>Season: <strong>${gameState.seasonYear}/${gameState.seasonYear + 1}</strong></p>
                 <div style="text-align:center; margin-top:14px;">
                    <button class="big-button" onclick="tifoCloseModal(); tifoNav('leagueTable')">Open Table</button>
                 </div>`
            );
            return;
        }

        // Update local state
        if (data.table) gameState.leagueTable = data.table;
        if (data.userMatch) {
            if (!gameState.matchHistory) gameState.matchHistory = [];
            gameState.matchHistory.push(data.userMatch);
        }
        // Update roster with refreshed goals/assists
        if (data.roster) gameState.roster = data.roster;
        if (data.internationalCompetitionName) gameState.internationalCompetitionName = data.internationalCompetitionName;
        if (data.internationalMatchday != null) gameState.internationalMatchday = data.internationalMatchday;
        if (data.internationalTable) gameState.internationalTable = data.internationalTable;
        if (data.internationalWindows) gameState.internationalWindows = data.internationalWindows;
        if (data.transferMarket) gameState.transferMarket = data.transferMarket;

        // Store all round results for schedule fixture detail
        const roundNum = data.round || gameState.currentRound;
        if (data.allResults) {
            allRoundResults[roundNum] = data.allResults;
            applyRoundResultsToSchedule(data.allResults);
        }

        gameState.currentRound = roundNum + 1;

        // Reload inbox
        const inboxRes = await csApi('/api/cs/inbox');
        if (inboxRes && inboxRes.ok) gameState.inbox = await inboxRes.json();
        const freshUnread = (gameState?.inbox || []).filter(msg => !msg?.read).length;
        inboxUnreadCount = freshUnread;
        updateInboxRibbon();

        rebuildTeamIndex();
        updateRoundInfo();

        await renderLiveRoundSimulation(data.allResults || [], gameState.userTeam?.id, 36000);

        // Show user match result
        if (data.userMatch) {
            renderMatchDetailFull(data.userMatch, () => renderPage('matches'));
        } else if (data.seasonOver) {
            renderPage('leagueTable');
        } else {
            renderPage('clubInfo');
        }
    } catch (e) {
        console.error('Next round error:', e);
        alert('Error: ' + e.message);
    } finally {
        btns.forEach(btn => {
            btn.disabled = false;
            btn.textContent = btn.dataset.originalText || 'Next Round ->';
        });
    }
}

// --- Mobile menu ---
function toggleMobileMenu() {
    const sidebar = document.getElementById('mobileSidebar');
    const overlay = document.getElementById('mobileOverlay');
    sidebar?.classList.toggle('active');
    overlay?.classList.toggle('active');
}

function closeDesktopSidebars() {
    document.querySelectorAll('#tifoClubSidebar, #tifoCompetitionsSidebar, #tifoStatsSidebar')
        .forEach(el => el.classList.remove('active'));
}

function showHalfTimeModal(match, continueFn) {
    const home = match.homeTeamName;
    const away = match.awayTeamName;
    const half = computeHalfTimeSnapshot(match);
    const keyLines = half.events.length
        ? half.events.slice(0, 6).map(e => `<li>${e.minute}' ${describeEventShort(e)}</li>`).join('')
        : '<li>No major first-half incidents.</li>';

    showModal(
        'Half-time',
        `<p style="text-align:center; font-size:1.1em;"><strong>${home} ${half.homeGoals}:${half.awayGoals} ${away}</strong></p>
         <p style="text-align:center; color:#777;">Control estimate: ${home} ${half.homeShare}% - ${away} ${100 - half.homeShare}%</p>
         <ul style="line-height:1.5; margin:12px 0 18px 18px;">${keyLines}</ul>
         <div style="text-align:center;">
            <button class="big-button" onclick="tifoContinueFromHalf()">Continue to Full Time</button>
         </div>`
    );

    window._pendingFullTimeContinue = continueFn;
}

function computeHalfTimeSnapshot(match) {
    const events = (match.events || []).filter(e => (e.minute || 0) <= 45);
    let homeGoals = 0;
    let awayGoals = 0;
    let homeShotsOn = 0;
    let awayShotsOn = 0;

    events.forEach(e => {
        if (e.eventType === 'GOAL') {
            if (e.teamName === match.homeTeamName) homeGoals++;
            if (e.teamName === match.awayTeamName) awayGoals++;
        }
        if (e.eventType === 'SHOT_ON_TARGET') {
            if (e.teamName === match.homeTeamName) homeShotsOn++;
            if (e.teamName === match.awayTeamName) awayShotsOn++;
        }
    });

    const total = homeShotsOn + awayShotsOn;
    const homeShare = total === 0 ? 50 : Math.round((homeShotsOn * 100) / total);
    return { homeGoals, awayGoals, homeShare, events };
}

function describeEventShort(e) {
    if (e.eventType === 'GOAL') {
        return `GOAL ${e.playerName || '?'} (${e.teamName || '?'}) ${e.scoreAfterGoal ? '[' + e.scoreAfterGoal + ']' : ''}`;
    }
    if (e.eventType === 'SUBSTITUTION') return `SUB ${e.playerOutName || '?'} -> ${e.playerInName || '?'}`;
    if (e.eventType === 'INJURY') return `INJURY ${e.playerName || '?'}`;
    if (e.eventType === 'SHOT_ON_TARGET') return `Shot on target by ${e.playerName || '?'}`;
    if (e.eventType === 'SHOT_OFF_TARGET') return `Shot off target by ${e.playerName || '?'}`;
    if (e.eventType === 'YELLOW_CARD') return `Yellow card: ${e.playerName || '?'}`;
    if (e.eventType === 'RED_CARD') return `Red card: ${e.playerName || '?'}`;
    if (e.eventType === 'CORNER') return `Corner for ${e.teamName || '?'}`;
    return `${e.eventType} ${e.playerName ? '- ' + e.playerName : ''}`;
}

async function renderLiveRoundSimulation(allResults, userTeamId, durationMs = 36000) {
    if (!Array.isArray(allResults) || allResults.length === 0) {
        return;
    }

    const main = document.getElementById('main-content');
    if (!main) return;

    const states = allResults.map(m => ({
        round: m.round,
        homeTeamName: m.homeTeamName,
        awayTeamName: m.awayTeamName,
        homeTeamId: m.homeTeamId,
        awayTeamId: m.awayTeamId,
        homeGoals: 0,
        awayGoals: 0,
        pulse: false,
        userMatch: m.homeTeamId === userTeamId || m.awayTeamId === userTeamId,
        finishMinute: 90 + Math.floor(Math.random() * 6),
        finished: false
    }));

    const eventTimeline = [];
    allResults.forEach(m => {
        (m.events || [])
            .filter(e => ['GOAL', 'PENALTY', 'RED_CARD', 'VAR_REVIEW'].includes(e.eventType))
            .forEach(e => {
                eventTimeline.push({
                    minute: e.minute || 1,
                    matchKey: `${m.round}|${m.homeTeamId}|${m.awayTeamId}`,
                    eventType: e.eventType,
                    teamName: e.teamName,
                    playerName: e.playerName,
                    assistName: e.assistName,
                    penaltyScored: e.penaltyScored
                });
            });
    });
    eventTimeline.sort((a, b) => a.minute - b.minute);

    const feed = [];
    const keyOf = (s) => `${s.round}|${s.homeTeamId}|${s.awayTeamId}`;
    const getDisplayMinute = (ratio) => {
        if (ratio <= 0.74) {
            return Math.floor(1 + (ratio / 0.74) * 79);
        }
        return Math.floor(80 + ((ratio - 0.74) / 0.26) * 17);
    };

    const render = (minute) => {
        const cards = states.map(s => `
            <div class="cs-live-card ${s.userMatch ? 'user-match' : ''} ${s.pulse ? 'flash' : ''} ${s.finished ? 'finished' : ''}">
                <div class="cs-live-card-head">
                    <div class="cs-live-card-page">537</div>
                    ${s.finished ? '<span class="cs-live-match-tag finished">FT</span>' : (s.userMatch ? '<span class="cs-live-match-tag">LIVE</span>' : '<span class="cs-live-match-tag alt">POOL</span>')}
                </div>
                <div class="cs-live-card-main">
                    <div class="cs-live-fixture-line"><span>${escapeHtml(s.homeTeamName).toUpperCase()}</span><strong class="${s.pulse ? 'pulse' : ''}">${s.homeGoals}</strong></div>
                    <div class="cs-live-fixture-line"><span>${escapeHtml(s.awayTeamName).toUpperCase()}</span><strong class="${s.pulse ? 'pulse' : ''}">${s.awayGoals}</strong></div>
                </div>
            </div>
        `).join('');

        const feedHtml = feed.slice(-10).reverse().map(item => `
            <div class="cs-live-feed-item">
                <strong>${item.minute}'</strong> ${item.line}
            </div>
        `).join('');

        main.innerHTML = `
            <div class="manager-card cs-live-teletext">
                <div class="cs-live-tt-head">
                    <div class="cs-live-tt-brand">ATLANTIC 537</div>
                    <div class="cs-live-tt-meta">ROUND ${states[0]?.round ?? '?'}  MIN ${String(Math.max(1, Math.min(90, minute))).padStart(2, '0')}</div>
                </div>
                <div class="cs-live-tt-sub">FOOTBALL LIVE SCORES  RESULTS SERVICE</div>
                <div class="cs-live-grid">${cards}</div>
                <div class="cs-live-feed">${feedHtml || '<div class="cs-live-feed-item">00  SERVICE NOTE  RESULTS PENDING  NO GOALS LOGGED YET</div>'}</div>
            </div>`;
    };

    let timelineIdx = 0;
    const startedAt = performance.now();
    const stepMs = 300;
    render(1);

    await new Promise(resolve => {
        const timer = setInterval(() => {
            const elapsed = performance.now() - startedAt;
            const ratio = Math.min(1, elapsed / durationMs);
            const minute = getDisplayMinute(ratio);

            while (timelineIdx < eventTimeline.length && eventTimeline[timelineIdx].minute <= minute) {
                const g = eventTimeline[timelineIdx];
                const target = states.find(s => keyOf(s) === g.matchKey);
                if (target && !target.finished) {
                    target.pulse = true;
                    setTimeout(() => { target.pulse = false; }, 2800);

                    if (g.eventType === 'GOAL' || (g.eventType === 'PENALTY' && g.penaltyScored)) {
                        if (g.teamName === target.homeTeamName) target.homeGoals += 1;
                        else target.awayGoals += 1;
                    }

                    const assist = g.assistName ? ` (assist: ${g.assistName})` : '';
                    const code = g.eventType === 'GOAL'
                        ? 'GOAL'
                        : g.eventType === 'PENALTY'
                        ? (g.penaltyScored ? 'PEN' : 'PEN MISS')
                        : g.eventType === 'RED_CARD'
                        ? 'RC'
                        : 'VAR';
                    const suffix = g.eventType === 'RED_CARD'
                        ? `  ${escapeHtml(g.teamName || '?').toUpperCase()} ${escapeHtml(g.playerName || '?').toUpperCase()} SENT OFF`
                        : g.eventType === 'VAR_REVIEW'
                        ? `  CHECK UNDERWAY  ${escapeHtml(g.teamName || '?').toUpperCase()}`
                        : `  ${escapeHtml(g.teamName || '?').toUpperCase()}  ${escapeHtml(g.playerName || '?').toUpperCase()}${escapeHtml(assist).toUpperCase()}`;
                    feed.push({
                        minute: g.minute,
                        line: `${target.userMatch ? '<span class="cs-live-tag">YOU</span> ' : ''}<span class="cs-live-code">${code}</span> ${escapeHtml(target.homeTeamName).toUpperCase()} ${target.homeGoals}-${target.awayGoals} ${escapeHtml(target.awayTeamName).toUpperCase()}${suffix}`
                    });
                }
                timelineIdx++;
            }

            states.forEach(target => {
                if (!target.finished && minute >= target.finishMinute) {
                    target.finished = true;
                    target.pulse = true;
                    setTimeout(() => { target.pulse = false; }, 2200);
                    feed.push({
                        minute: target.finishMinute,
                        line: `${target.userMatch ? '<span class="cs-live-tag">YOU</span> ' : ''}${escapeHtml(target.homeTeamName).toUpperCase()} ${target.homeGoals}-${target.awayGoals} ${escapeHtml(target.awayTeamName).toUpperCase()}  FULL TIME`
                    });
                }
            });

            render(minute);

            if (ratio >= 1) {
                clearInterval(timer);
                setTimeout(resolve, 80);
            }
        }, stepMs);
    });
}

function toggleSidebar(id) {
    const sidebars = document.querySelectorAll('#tifoClubSidebar, #tifoCompetitionsSidebar, #tifoStatsSidebar');
    sidebars.forEach(sb => {
        if (sb.id === id) sb.classList.toggle('active');
        else sb.classList.remove('active');
    });
}

document.addEventListener('click', (e) => {
    const inline = e.target.closest('.cs-inline-link');
    if (inline) {
        e.preventDefault();
        const kind = inline.dataset.kind;
        if (kind === 'team') {
            const teamId = Number(inline.dataset.id);
            if (teamId) renderTeamDetail(teamId);
            closeModal();
            return;
        }
        if (kind === 'player') {
            const playerId = Number(inline.dataset.playerId);
            const teamId = Number(inline.dataset.teamId);
            if (playerId && teamId) openMatchPlayer(playerId, teamId);
            closeModal();
            return;
        }
        if (kind === 'league') {
            closeModal();
            renderPage('leagueTable');
            return;
        }
        if (kind === 'match') {
            const round = Number(inline.dataset.round);
            const homeId = Number(inline.dataset.homeId);
            const awayId = Number(inline.dataset.awayId);
            closeModal();
            if (round && homeId && awayId) {
                fixtureDetail(round, homeId, awayId);
            }
            return;
        }
    }

    const clickedInTopMenu = e.target.closest('.top-menu');
    const clickedInSidebar = e.target.closest('#tifoClubSidebar, #tifoCompetitionsSidebar, #tifoStatsSidebar');
    if (!clickedInTopMenu && !clickedInSidebar) {
        closeDesktopSidebars();
    }
});

async function closeCsSession() {
    if (csSessionClosed) return;
    csSessionClosed = true;
    try {
	        await csApi('/api/cs/reset', { method: 'POST', keepalive: true });
    } catch (e) {
        console.warn('CS reset on exit failed:', e);
    }
}

function setupSessionLifecycle() {
    window.addEventListener('pagehide', () => {
        closeCsSession();
    });
}

setupSessionLifecycle();

async function backToMainApp() {
    await closeCsSession();
    window.location.href = '/dashboard.html';
}

// --- Expose to global scope (for onclick handlers in HTML) ---
window.tifoNav = renderPage;
window.tifoNextRound = nextRound;
window.tifoPlayerDetail = renderPlayerDetail;
window.tifoMatchDetail = renderMatchDetail;
window.tifoSetTactics = setTactics;
window.toggleMobileMenu = toggleMobileMenu;
window.tifoCloseModal = closeModal;
window.tifoOpenInbox = openInboxMessage;
window.tifoInboxFilter = setInboxFilter;
window.tifoInboxSort = setInboxSort;
window.tifoTeamDetail = renderTeamDetail;
window.tifoViewPlayer = viewPlayerFromTeam;
window.tifoFixtureDetail = fixtureDetail;
window.tifoFixturePreview = fixturePreview;
window.tifoMatchTab = (tab) => showMatchTab(tab);
window.toggleSidebar = toggleSidebar;
window.tifoOpenMatchPlayer = openMatchPlayer;
window.tifoOpenRankedPlayer = openRankedPlayer;
window.tifoBackToMain = backToMainApp;
window.tifoSaveLineup = saveLineup;
window.tifoListTransfer = listTransferPlayer;
window.tifoRemoveTransfer = removeTransferPlayer;
window.tifoBuyTransfer = buyTransferPlayer;
window.tifoBidTransfer = bidTransferPlayer;
window.tifoAcceptTransfer = acceptTransferOffer;
window.tifoRejectTransfer = rejectTransferOffer;
window.tifoContinueFromHalf = () => {
    closeModal();
    if (window._pendingFullTimeContinue) {
        window._pendingFullTimeContinue();
        window._pendingFullTimeContinue = null;
    }
};
