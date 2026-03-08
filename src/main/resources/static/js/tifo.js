// tifo.js - Clean Sheet Text Mode (in-memory)
import { authFetch } from './auth.js';

let gameState = null;
// Store all round results (keyed by round) so schedule fixtures can link to any match
let allRoundResults = {};
let csSessionClosed = false;
let csTeamNameToId = new Map();
let csPlayerNameToEntries = new Map();
let csPlayerIndexLoaded = false;
let csPlayerIdToTeamId = new Map();
let inboxUnreadCount = 0;

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
        rebuildTeamIndex();
        ensurePlayerIndexLoaded();
        inboxUnreadCount = 0;
        updateInboxRibbon();
        updateRoundInfo();
        renderPage('clubInfo');
    } else {
        const startRes = await csApi('/api/cs/start', { method: 'POST' });
        if (!startRes || !startRes.ok) {
            document.getElementById('main-content').innerHTML = `<div class="manager-card"><h2>Error starting game</h2></div>`;
            return;
        }
        gameState = await startRes.json();
        rebuildTeamIndex();
        ensurePlayerIndexLoaded();
        inboxUnreadCount = 0;
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
    const main = document.getElementById('main-content');
    main.innerHTML = '';
    closeDesktopSidebars();

    const card = document.createElement('div');
    card.className = 'manager-card';
    main.appendChild(card);

    switch (page) {
        case 'inbox':
            inboxUnreadCount = 0;
            updateInboxRibbon();
            renderInbox(card);
            break;
        case 'players': renderPlayers(card); break;
        case 'tactics': renderTactics(card); break;
        case 'leagueTable': renderLeagueTable(card); break;
        case 'schedule': renderSchedule(card); break;
        case 'matches': renderMatches(card); break;
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

// --- Club Info ---
function renderClubInfo(el) {
    const t = gameState?.userTeam;
    if (!t) { el.innerHTML = '<p>No data</p>'; return; }
    el.innerHTML = `
        <h2>Club Info</h2>
        <div class="cs-stat-grid">
            <div class="cs-stat-card"><div class="icon">&#127967;&#65039;</div><div class="val">${t.name}</div><div class="lbl">Team</div></div>
            <div class="cs-stat-card"><div class="icon">&#128176;</div><div class="val">&euro;${t.budget?.toLocaleString() || 0}</div><div class="lbl">Budget</div></div>
            <div class="cs-stat-card"><div class="icon">&#11088;</div><div class="val">${t.reputation || 0}</div><div class="lbl">Reputation</div></div>
            <div class="cs-stat-card"><div class="icon">&#127967;&#65039;</div><div class="val">${t.stadiumName || '?'}</div><div class="lbl">Stadium (${t.stadiumCapacity || '?'})</div></div>
            <div class="cs-stat-card"><div class="icon">&#128203;</div><div class="val">${gameState.roster?.length || 0}</div><div class="lbl">Players</div></div>
            <div class="cs-stat-card"><div class="icon">&#128197;</div><div class="val">${gameState.currentRound} / ${gameState.totalRounds}</div><div class="lbl">Round</div></div>
        </div>`;
}

// --- Inbox (click opens modal) ---
function renderInbox(el) {
    const inbox = gameState?.inbox || [];
    let html = `<h2>Inbox (${inbox.length})</h2>`;
    if (inbox.length === 0) { html += '<p style="color:#aaa;">No messages.</p>'; }
    else {
        inbox.slice().reverse().forEach((msg, idx) => {
            const realIdx = inbox.length - 1 - idx;
            html += `<div class="cs-inbox-item cs-clickable" onclick="tifoOpenInbox(${realIdx})">
                <span class="cs-inbox-badge ${msg.type}">${msg.type.toUpperCase()}</span>
                ${truncate(msg.text, 80)}
                <div style="font-size:0.8em; color:#666; margin-top:4px;">${msg.timestamp || ''}</div>
            </div>`;
        });
    }
    el.innerHTML = html;
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

async function openInboxMessage(index) {
    const msg = gameState?.inbox?.[index];
    if (!msg) return;
    rebuildTeamIndex();
    if (msg.type === 'report' || msg.type === 'round-report') {
        await ensurePlayerIndexLoaded();
    }
    const badgeHtml = `<span class="cs-inbox-badge ${msg.type}">${msg.type.toUpperCase()}</span>`;
    const linkedText = injectEntityLinks(msg.text || '');
    if (msg.type === 'international') {
        const lines = (msg.text || "").split('\n').filter(Boolean);
        const header = lines.shift() || "International update";
        const rows = lines.length
            ? lines.map(line => `<div class="cs-match-card" style="margin-bottom:8px;"><div class="cs-match-teams">${escapeHtml(line)}</div></div>`).join("")
            : `<p style="color:#aaa;">No fixtures in this update.</p>`;
        showModal(badgeHtml + ' Message', `
            <div style="margin-bottom:10px; font-weight:700; color:#d7f3ff;">${escapeHtml(header)}</div>
            <div>${rows}</div>
            <div style="font-size:0.85em; color:#666; margin-top:12px;">${msg.timestamp || ''}</div>
        `);
        return;
    }
    showModal(badgeHtml + ' Message', `
        <p style="line-height:1.6;">${linkedText}</p>
        <div style="font-size:0.85em; color:#666; margin-top:12px;">${msg.timestamp || ''}</div>
    `);
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
                    <strong>Round ${m.round}</strong> &nbsp; ${m.homeTeamName} vs ${m.awayTeamName}
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

    let html = `<div class="manager-card">
        <button class="big-button" onclick="window._tifoMatchBack()" style="margin-bottom:16px;">Back</button>
        <h2 style="text-align:center;">
            <span class="cs-clickable" onclick="tifoTeamDetail(${homeTeamId})">${homeName}</span>
            ${match.homeGoals} : ${match.awayGoals}
            <span class="cs-clickable" onclick="tifoTeamDetail(${awayTeamId})">${awayName}</span>
        </h2>
        <p style="text-align:center;color:#aaa;">
            <span class="cs-clickable" onclick="tifoNav('leagueTable')">${leagueName}</span>
            &middot;
            <span class="cs-clickable" onclick="tifoNav('schedule')">Round ${match.round}</span>
        </p>

        <div class="cs-tabs">
            <button class="cs-tab-btn active" onclick="tifoMatchTab('lineups')">Lineups</button>
            <button class="cs-tab-btn" onclick="tifoMatchTab('goals')">Goals</button>
            <button class="cs-tab-btn" onclick="tifoMatchTab('stats')">Stats</button>
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
        const label = tab === 'lineups' ? 'Lineups' : tab === 'goals' ? 'Goals' : 'Stats';
        btn.classList.toggle('active', btn.textContent === label);
    });

    switch (tab) {
        case 'lineups': container.innerHTML = buildLineupsHtml(match); break;
        case 'goals': container.innerHTML = buildGoalsHtml(match); break;
        case 'stats': container.innerHTML = buildStatsHtml(match); break;
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
    const keyEvents = (match.events || []).filter(e => ['GOAL', 'SUBSTITUTION', 'INJURY'].includes(e.eventType));
    if (keyEvents.length === 0) return '<p style="color:#aaa;">No key events.</p>';
    let html = '';
    keyEvents.sort((a, b) => a.minute - b.minute).forEach(g => {
        if (g.eventType === 'SUBSTITUTION') {
            const teamId = g.teamName === match.homeTeamName ? match.homeTeamId : match.awayTeamId;
            html += `<div style="padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.05);">
                <strong>${g.minute}'</strong> &#128257; ${g.playerOutName || '?'} &rarr; ${g.playerInName || '?'}
                <span style="color:#888;">(<span class="cs-clickable" onclick="tifoTeamDetail(${teamId})">${g.teamName}</span>)</span>
            </div>`;
            return;
        }
        if (g.eventType === 'INJURY') {
            const teamId = g.teamName === match.homeTeamName ? match.homeTeamId : match.awayTeamId;
            html += `<div style="padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.05);">
                <strong>${g.minute}'</strong> &#10060; Injury: ${g.playerName || '?'}
                <span style="color:#888;">(<span class="cs-clickable" onclick="tifoTeamDetail(${teamId})">${g.teamName}</span>)</span>
            </div>`;
            return;
        }
        const scorerId = findMatchPlayerIdByName(match, g.teamName, g.playerName);
        const scorerTeamId = g.teamName === match.homeTeamName ? match.homeTeamId : match.awayTeamId;
        const assistId = findMatchPlayerIdByName(match, g.teamName, g.assistName);
        html += `<div style="padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.05);">
            <strong>${g.minute}'</strong> &#9917; ${scorerId ? `<span class="cs-clickable" onclick="tifoOpenMatchPlayer(${scorerId}, ${scorerTeamId})">${g.playerName}</span>` : (g.playerName || '?')}
            <span style="color:#888;">(<span class="cs-clickable" onclick="tifoTeamDetail(${scorerTeamId})">${g.teamName}</span>)</span>
            ${g.assistName ? `<span style="color:#666;"> assist: ${assistId ? `<span class="cs-clickable" onclick="tifoOpenMatchPlayer(${assistId}, ${scorerTeamId})">${g.assistName}</span>` : g.assistName}</span>` : ''}
            <span style="float:right;color:#aaa;font-weight:bold;">${g.scoreAfterGoal || ''}</span>
        </div>`;
    });
    return html;
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

    return `<table class="cs-table">
        <thead><tr><th>Stat</th><th><span class="cs-clickable" onclick="tifoTeamDetail(${match.homeTeamId})">${homeName}</span></th><th><span class="cs-clickable" onclick="tifoTeamDetail(${match.awayTeamId})">${awayName}</span></th></tr></thead>
        <tbody>
            <tr><td>Sutevi u okvir</td><td>${countFor('SHOT_ON_TARGET', homeName)}</td><td>${countFor('SHOT_ON_TARGET', awayName)}</td></tr>
            <tr><td>Sutevi van okvira</td><td>${countFor('SHOT_OFF_TARGET', homeName)}</td><td>${countFor('SHOT_OFF_TARGET', awayName)}</td></tr>
            <tr><td>Korneri</td><td>${countFor('CORNER', homeName)}</td><td>${countFor('CORNER', awayName)}</td></tr>
            <tr><td>Zuti kartoni</td><td style="color:#ff9800;">${countFor('YELLOW_CARD', homeName)}</td><td style="color:#ff9800;">${countFor('YELLOW_CARD', awayName)}</td></tr>
            <tr><td>Crveni kartoni</td><td style="color:#f44336;">${countFor('RED_CARD', homeName)}</td><td style="color:#f44336;">${countFor('RED_CARD', awayName)}</td></tr>
            <tr><td>Penali</td><td>${countFor('PENALTY', homeName)}</td><td>${countFor('PENALTY', awayName)}</td></tr>
        </tbody>
    </table>`;
}

// --- Tactics ---
// --- Tactics ---
function renderTactics(el) {
    const tactics = gameState?.tactics || {};
    const roster = gameState?.roster || [];
    const isMobile = window.matchMedia('(max-width: 768px)').matches;
    const formations = ['4-4-2', '4-3-3', '4-2-3-1', '4-1-4-1', '3-5-2', '3-4-3', '5-3-2', '5-4-1', '4-5-1'];
    const styles = ['BALANCED', 'ATTACKING', 'DEFENSIVE', 'COUNTER', 'POSSESSION', 'HIGH_PRESS', 'DIRECT'];

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
        style: tactics.style || 'BALANCED',
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

        slots.forEach((slot, idx) => {
            if (state.starterIds[idx]) return;
            const p = roster.find(x => !used.has(Number(x.id)) && canPlayRole(x, slot.role));
            if (!p) return;
            state.starterIds[idx] = Number(p.id);
            used.add(Number(p.id));
        });

        state.benchIds = state.benchIds.filter(id => {
            const num = Number(id);
            return num && !used.has(num);
        }).slice(0, 7).map(Number);

        state.benchIds.forEach(id => used.add(Number(id)));
        roster.forEach(p => {
            const id = Number(p.id);
            if (state.benchIds.length < 7 && !used.has(id)) {
                state.benchIds.push(id);
                used.add(id);
            }
        });
    };
    ensureState();

    const starterOptions = (role, current) => {
        const used = allSelected();
        if (current) used.delete(Number(current));
        return roster.filter(p => !used.has(Number(p.id)) && canPlayRole(p, role));
    };

    const benchOptions = (current) => {
        const used = allSelected();
        if (current) used.delete(Number(current));
        return roster.filter(p => !used.has(Number(p.id)));
    };

    const renderDesktop = () => {
        const pool = roster.filter(p => !allSelected().has(Number(p.id))).map(p => `
            <div class="cs-draggable" draggable="true" data-player-id="${p.id}" data-zone="pool" style="padding:6px; margin-bottom:6px; background:#223545; border-radius:6px; cursor:grab;">${p.name} (${p.position}, R ${p.rating})</div>
        `).join('');

        const starters = slots.map((slot, idx) => {
            const id = state.starterIds[idx];
            const p = byId(id);
            return `<div class="cs-drop-zone" data-target="starter" data-index="${idx}" data-role="${slot.role}" style="padding:8px; border:1px dashed #466; border-radius:8px; min-height:56px;">
                <div style="font-size:0.78em; color:#95a0a7;">${slot.label}</div>
                ${p ? `<div class="cs-draggable" draggable="true" data-player-id="${p.id}" data-zone="starter" data-index="${idx}" style="padding:6px; background:#1f2d3a; border-radius:6px; cursor:grab;">${p.name} (${p.position})</div>` : `<div style="color:#687b84; font-size:0.82em;">Drop ${slot.role}</div>`}
            </div>`;
        }).join('');

        const bench = Array.from({ length: 7 }).map((_, idx) => {
            const id = state.benchIds[idx];
            const p = byId(id);
            return `<div class="cs-drop-zone" data-target="bench" data-index="${idx}" style="padding:8px; border:1px dashed #665; border-radius:8px; min-height:56px;">
                <div style="font-size:0.78em; color:#95a0a7;">Bench ${idx + 1}</div>
                ${p ? `<div class="cs-draggable" draggable="true" data-player-id="${p.id}" data-zone="bench" data-index="${idx}" style="padding:6px; background:#302f1f; border-radius:6px; cursor:grab;">${p.name} (${p.position})</div>` : `<div style="color:#687b84; font-size:0.82em;">Drop player</div>`}
            </div>`;
        }).join('');

        return `<div style="display:grid; grid-template-columns:1fr 1fr; gap:12px;">
            <div><h4>Starting XI (DnD)</h4><div style="display:grid; gap:8px;">${starters}</div></div>
            <div><h4>Bench (7)</h4><div style="display:grid; gap:8px; margin-bottom:8px;">${bench}</div><h4>Pool</h4><div class="cs-drop-zone" data-target="pool" data-index="-1" style="border:1px dashed #344; border-radius:8px; padding:8px; min-height:100px; max-height:260px; overflow:auto;">${pool || '<div style="color:#687b84;">No free players.</div>'}</div></div>
        </div>`;
    };

    const renderMobile = () => {
        const starters = slots.map((slot, idx) => {
            const current = Number(state.starterIds[idx] || 0);
            const options = starterOptions(slot.role, current);
            return `<label class="training-group-row"><span class="group-tag">${slot.label}</span><select class="cs-starter-select" data-slot="${idx}"><option value="">-- Empty --</option>${options.map(p => `<option value="${p.id}" ${Number(p.id) === current ? 'selected' : ''}>${p.name} (${p.position}, R ${p.rating})</option>`).join('')}</select></label>`;
        }).join('');

        const bench = Array.from({ length: 7 }).map((_, idx) => {
            const current = Number(state.benchIds[idx] || 0);
            const options = benchOptions(current);
            return `<label class="training-group-row"><span class="group-tag">Bench ${idx + 1}</span><select class="cs-bench-select" data-slot="${idx}"><option value="">-- Empty --</option>${options.map(p => `<option value="${p.id}" ${Number(p.id) === current ? 'selected' : ''}>${p.name} (${p.position}, R ${p.rating})</option>`).join('')}</select></label>`;
        }).join('');

        return `<h3 style="margin-top:20px;">Starting XI</h3>${starters}<h3 style="margin-top:18px;">Bench</h3>${bench}`;
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

    let drag = null;
    document.querySelectorAll('.cs-draggable').forEach(elDrag => {
        elDrag.addEventListener('dragstart', () => {
            drag = {
                id: Number(elDrag.dataset.playerId),
                zone: elDrag.dataset.zone,
                index: Number(elDrag.dataset.index || -1)
            };
        });
    });

    document.querySelectorAll('.cs-drop-zone').forEach(zone => {
        zone.addEventListener('dragover', e => e.preventDefault());
        zone.addEventListener('drop', e => {
            e.preventDefault();
            if (!drag?.id) return;

            const p = byId(drag.id);
            const target = zone.dataset.target;
            const idx = Number(zone.dataset.index || -1);
            const role = zone.dataset.role;

            if (target === 'starter') {
                if (!canPlayRole(p, role)) return;
                state.benchIds = state.benchIds.map(v => Number(v) === drag.id ? null : v);
                state.starterIds = state.starterIds.map((v, i) => i !== idx && Number(v) === drag.id ? null : v);
                state.starterIds[idx] = drag.id;
            } else if (target === 'bench') {
                state.starterIds = state.starterIds.map(v => Number(v) === drag.id ? null : v);
                state.benchIds = state.benchIds.map((v, i) => i !== idx && Number(v) === drag.id ? null : v);
                state.benchIds[idx] = drag.id;
            } else if (target === 'pool') {
                if (drag.zone === 'starter' && drag.index >= 0) state.starterIds[drag.index] = null;
                if (drag.zone === 'bench' && drag.index >= 0) state.benchIds[drag.index] = null;
            }

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

// --- Transfers (dummy) ---
function renderTransfers(el) {
    el.innerHTML = `<h2>&#128260; Transfers</h2>
        <div class="cs-transfer-placeholder">
            <div class="icon">&#128260;</div>
            <h3>Transfer Market</h3>
            <p>The transfer window is currently closed.</p>
            <p style="margin-top:12px;">Transfers will be available in a future version.</p>
        </div>`;
}

// --- Next Round ---
async function nextRound() {
    const previousInboxCount = (gameState?.inbox || []).length;
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
            rebuildTeamIndex();
            csPlayerIndexLoaded = false;
            ensurePlayerIndexLoaded();
            inboxUnreadCount = 0;
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

        // Store all round results for schedule fixture detail
        const roundNum = data.round || gameState.currentRound;
        if (data.allResults) {
            allRoundResults[roundNum] = data.allResults;
        }

        gameState.currentRound = roundNum + 1;

        // Reload inbox
        const inboxRes = await csApi('/api/cs/inbox');
        if (inboxRes && inboxRes.ok) gameState.inbox = await inboxRes.json();
        const freshInboxCount = (gameState?.inbox || []).length;
        const delta = Math.max(0, freshInboxCount - previousInboxCount);
        if (delta > 0) {
            inboxUnreadCount += delta;
            updateInboxRibbon();
        }

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
        userMatch: m.homeTeamId === userTeamId || m.awayTeamId === userTeamId
    }));

    const goalTimeline = [];
    allResults.forEach(m => {
        (m.events || [])
            .filter(e => e.eventType === 'GOAL')
            .forEach(e => {
                goalTimeline.push({
                    minute: e.minute || 1,
                    matchKey: `${m.round}|${m.homeTeamId}|${m.awayTeamId}`,
                    teamName: e.teamName,
                    playerName: e.playerName,
                    assistName: e.assistName
                });
            });
    });
    goalTimeline.sort((a, b) => a.minute - b.minute);

    const feed = [];
    const keyOf = (s) => `${s.round}|${s.homeTeamId}|${s.awayTeamId}`;

    const render = (minute) => {
        const cards = states.map(s => `
            <div class="cs-live-card ${s.userMatch ? 'user-match' : ''}">
                <div><strong>${s.homeTeamName}</strong> vs <strong>${s.awayTeamName}</strong></div>
                <div class="cs-live-score ${s.pulse ? 'pulse' : ''}">${s.homeGoals} : ${s.awayGoals}</div>
            </div>
        `).join('');

        const feedHtml = feed.slice(-10).reverse().map(item => `
            <div class="cs-live-feed-item">
                <strong>${item.minute}'</strong> ${item.line}
            </div>
        `).join('');

        main.innerHTML = `
            <div class="manager-card">
                <h2>Live Scores - Round ${states[0]?.round ?? '?'}</h2>
                <div style="color:#9a9a9a; margin-top:4px;">Minute ${Math.max(1, Math.min(90, minute))}</div>
                <div class="cs-live-grid">${cards}</div>
                <div class="cs-live-feed">${feedHtml || '<div class="cs-live-feed-item">No major incidents yet.</div>'}</div>
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
            const minute = Math.floor(1 + ratio * 89);

            while (timelineIdx < goalTimeline.length && goalTimeline[timelineIdx].minute <= minute) {
                const g = goalTimeline[timelineIdx];
                const target = states.find(s => keyOf(s) === g.matchKey);
                if (target) {
                    if (g.teamName === target.homeTeamName) target.homeGoals += 1;
                    else target.awayGoals += 1;
                    target.pulse = true;
                    setTimeout(() => { target.pulse = false; }, 700);

                    const assist = g.assistName ? ` (assist: ${g.assistName})` : '';
                    feed.push({
                        minute: g.minute,
                        line: `${target.homeTeamName} ${target.homeGoals}:${target.awayGoals} ${target.awayTeamName} &mdash; ${g.teamName}: ${g.playerName || '?'}${assist}`
                    });
                }
                timelineIdx++;
            }

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
        await authFetch('/api/cs/reset', { method: 'POST', keepalive: true });
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
window.tifoContinueFromHalf = () => {
    closeModal();
    if (window._pendingFullTimeContinue) {
        window._pendingFullTimeContinue();
        window._pendingFullTimeContinue = null;
    }
};



