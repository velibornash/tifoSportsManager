// tifo.js — Clean Sheet Text Mode (in-memory)
import { authFetch } from './auth.js';

let gameState = null;
// Store all round results (keyed by round) so schedule fixtures can link to any match
let allRoundResults = {};

// ─── Auth helper ───
async function csApi(url, options = {}) {
    const token = localStorage.getItem('token');
    if (!token) { window.location.href = '/login.html'; return null; }
    const headers = { ...options.headers, 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' };
    const res = await fetch(url, { ...options, headers });
    if (res.status === 401) { localStorage.removeItem('token'); window.location.href = '/login.html'; return null; }
    return res;
}

// ─── Init ───
document.addEventListener('DOMContentLoaded', async () => {
    const token = localStorage.getItem('token');
    if (!token) { window.location.href = '/login.html'; return; }

    const res = await csApi('/api/cs/state');
    if (!res) return;
    const data = await res.json();

    if (data.active) {
        gameState = data;
        updateRoundInfo();
        renderPage('clubInfo');
    } else {
        const startRes = await csApi('/api/cs/start', { method: 'POST' });
        if (!startRes || !startRes.ok) {
            document.getElementById('main-content').innerHTML = `<div class="manager-card"><h2>Greska pri pokretanju igre</h2></div>`;
            return;
        }
        gameState = await startRes.json();
        updateRoundInfo();
        renderPage('inbox');
    }
});

function updateRoundInfo() {
    const el = document.getElementById('roundInfo');
    if (el && gameState) {
        el.textContent = `Kolo ${gameState.currentRound} / ${gameState.totalRounds} | Sezona ${gameState.seasonYear}/${gameState.seasonYear + 1}`;
    }
}

// ─── Navigation ───
function renderPage(page) {
    const main = document.getElementById('main-content');
    main.innerHTML = '';

    const card = document.createElement('div');
    card.className = 'manager-card';
    main.appendChild(card);

    switch (page) {
        case 'inbox': renderInbox(card); break;
        case 'players': renderPlayers(card); break;
        case 'tactics': renderTactics(card); break;
        case 'leagueTable': renderLeagueTable(card); break;
        case 'schedule': renderSchedule(card); break;
        case 'matches': renderMatches(card); break;
        case 'clubInfo': renderClubInfo(card); break;
        case 'topScorers': renderTopScorers(card); break;
        case 'topAssists': renderTopAssists(card); break;
        case 'transfers': renderTransfers(card); break;
        default: card.innerHTML = '<h2>Izaberi kategoriju</h2>';
    }
}

// ─── Modal helpers ───
function showModal(title, bodyHtml) {
    closeModal();
    const overlay = document.createElement('div');
    overlay.className = 'cs-modal-overlay';
    overlay.id = 'csModalOverlay';
    overlay.onclick = (e) => { if (e.target === overlay) closeModal(); };
    overlay.innerHTML = `<div class="cs-modal">
        <button class="cs-modal-close" onclick="tifoCloseModal()">✕</button>
        <h3>${title}</h3>
        <div>${bodyHtml}</div>
    </div>`;
    document.body.appendChild(overlay);
}

function closeModal() {
    const existing = document.getElementById('csModalOverlay');
    if (existing) existing.remove();
}

// ─── Club Info ───
function renderClubInfo(el) {
    const t = gameState?.userTeam;
    if (!t) { el.innerHTML = '<p>Nema podataka</p>'; return; }
    el.innerHTML = `
        <h2>Club Info</h2>
        <div class="cs-stat-grid">
            <div class="cs-stat-card"><div class="icon">🏟️</div><div class="val">${t.name}</div><div class="lbl">Team</div></div>
            <div class="cs-stat-card"><div class="icon">💰</div><div class="val">€${t.budget?.toLocaleString() || 0}</div><div class="lbl">Budget</div></div>
            <div class="cs-stat-card"><div class="icon">⭐</div><div class="val">${t.reputation || 0}</div><div class="lbl">Reputation</div></div>
            <div class="cs-stat-card"><div class="icon">🏟️</div><div class="val">${t.stadiumName || '?'}</div><div class="lbl">Stadium (${t.stadiumCapacity || '?'})</div></div>
            <div class="cs-stat-card"><div class="icon">📋</div><div class="val">${gameState.roster?.length || 0}</div><div class="lbl">Players</div></div>
            <div class="cs-stat-card"><div class="icon">📅</div><div class="val">${gameState.currentRound} / ${gameState.totalRounds}</div><div class="lbl">Round</div></div>
        </div>`;
}

// ─── Inbox (click opens modal) ───
function renderInbox(el) {
    const inbox = gameState?.inbox || [];
    let html = `<h2>Inbox (${inbox.length})</h2>`;
    if (inbox.length === 0) { html += '<p style="color:#aaa;">Nema poruka.</p>'; }
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

function openInboxMessage(index) {
    const msg = gameState?.inbox?.[index];
    if (!msg) return;
    const badgeHtml = `<span class="cs-inbox-badge ${msg.type}">${msg.type.toUpperCase()}</span>`;
    showModal(badgeHtml + ' Poruka', `
        <p style="line-height:1.6;">${msg.text}</p>
        <div style="font-size:0.85em; color:#666; margin-top:12px;">${msg.timestamp || ''}</div>
    `);
}

// ─── Players ───
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

// ─── Player Detail (own roster) ───
function renderPlayerDetail(playerId) {
    const p = gameState?.roster?.find(pl => pl.id === playerId);
    if (!p) return;
    renderAnyPlayerDetail(p, () => renderPage('players'));
}

// ─── Generic Player Detail (any player, any team) ───
function renderAnyPlayerDetail(p, backFn) {
    const main = document.getElementById('main-content');
    main.innerHTML = `<div class="manager-card">
        <button class="big-button" onclick="window._tifoBack()" style="margin-bottom:16px;">⬅ Nazad</button>
        <h2>${p.name}</h2>
        <div class="cs-stat-grid">
            <div class="cs-stat-card"><div class="icon">📋</div><div class="val">${p.position}</div><div class="lbl">Position</div></div>
            <div class="cs-stat-card"><div class="icon">🎂</div><div class="val">${p.age}</div><div class="lbl">Age</div></div>
            <div class="cs-stat-card"><div class="icon">⭐</div><div class="val">${p.rating}</div><div class="lbl">Rating</div></div>
            <div class="cs-stat-card"><div class="icon">🔥</div><div class="val">${p.form?.toFixed(1) ?? '-'}</div><div class="lbl">Form</div></div>
            <div class="cs-stat-card"><div class="icon">😓</div><div class="val">${p.fatigue?.toFixed(1) ?? '-'}</div><div class="lbl">Fatigue</div></div>
            <div class="cs-stat-card"><div class="icon">⚽</div><div class="val">${p.goals || 0}</div><div class="lbl">Goals</div></div>
            <div class="cs-stat-card"><div class="icon">🅰️</div><div class="val">${p.assists || 0}</div><div class="lbl">Assists</div></div>
            <div class="cs-stat-card"><div class="icon">💰</div><div class="val">€${(p.value || 0).toLocaleString()}</div><div class="lbl">Value</div></div>
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

// ─── League Table (clickable rows → team detail) ───
function renderLeagueTable(el) {
    const table = gameState?.leagueTable || [];
    const userTeamId = gameState?.userTeam?.id;

    let html = `<h2>Liga – Tabela</h2>
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

// ─── Team Detail (any team — roster view) ───
async function renderTeamDetail(teamId) {
    const main = document.getElementById('main-content');
    main.innerHTML = '<div class="manager-card"><h2>Loading...</h2></div>';

    const res = await csApi(`/api/cs/team/${teamId}`);
    if (!res || !res.ok) {
        main.innerHTML = '<div class="manager-card"><p>Tim nije pronadjen</p></div>';
        return;
    }
    const data = await res.json();
    const team = data.team;
    const roster = data.roster || [];

    const posOrder = { GK: 0, DEF: 1, MID: 2, WNG: 3, ATT: 4 };
    const sorted = [...roster].sort((a, b) => (posOrder[a.position] ?? 5) - (posOrder[b.position] ?? 5));

    let html = `<div class="manager-card">
        <button class="big-button" onclick="tifoNav('leagueTable')" style="margin-bottom:16px;">⬅ Nazad</button>
        <h2>${team.name}</h2>
        <div class="cs-stat-grid">
            <div class="cs-stat-card"><div class="icon">💰</div><div class="val">€${team.budget?.toLocaleString() || 0}</div><div class="lbl">Budget</div></div>
            <div class="cs-stat-card"><div class="icon">⭐</div><div class="val">${team.reputation || 0}</div><div class="lbl">Reputation</div></div>
            <div class="cs-stat-card"><div class="icon">📋</div><div class="val">${roster.length}</div><div class="lbl">Players</div></div>
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

// ─── Schedule (clickable matches) ───
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

    let html = `<h2>Raspored</h2>`;
    const rounds = Object.keys(byRound).map(Number).sort((a, b) => a - b);
    const nextRound = rounds.find(r => byRound[r].some(f => !f.played)) || rounds[rounds.length - 1];

    for (const round of rounds) {
        const isCurrentRound = round === currentRound;
        const isNext = round === nextRound;
        html += `<div class="cs-fixture-round" ${isNext ? 'id="nextRoundAnchor"' : ''}>
            <h4>Kolo ${round} ${isCurrentRound ? '⬅ sledece' : ''}</h4>`;
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
        <p style="text-align:center;color:#888;">Kolo ${round} – nije odigrano</p>`;

    if (homeTable && awayTable) {
        body += `<table class="cs-table" style="margin-top:16px;">
            <thead><tr><th></th><th>${homeName}</th><th>${awayName}</th></tr></thead>
            <tbody>
                <tr><td>Pozicija</td><td>${getPosition(homeId)}</td><td>${getPosition(awayId)}</td></tr>
                <tr><td>Bodovi</td><td>${homeTable.points}</td><td>${awayTable.points}</td></tr>
                <tr><td>W/D/L</td><td>${homeTable.wins}/${homeTable.draws}/${homeTable.losses}</td><td>${awayTable.wins}/${awayTable.draws}/${awayTable.losses}</td></tr>
                <tr><td>Golovi</td><td>${homeTable.goalsScored}:${homeTable.goalsConceded}</td><td>${awayTable.goalsScored}:${awayTable.goalsConceded}</td></tr>
            </tbody>
        </table>`;
    }
    showModal('⚽ Najava meca', body);
}

function getPosition(teamId) {
    const table = gameState?.leagueTable || [];
    const idx = table.findIndex(t => t.teamId === teamId);
    return idx >= 0 ? (idx + 1) + '.' : '?';
}

function fixtureDetail(round, homeTeamId, awayTeamId) {
    let match = gameState?.matchHistory?.find(m => m.round === round);
    if (!match && allRoundResults[round]) {
        match = allRoundResults[round].find(m =>
            m.homeTeamId === homeTeamId && m.awayTeamId === awayTeamId);
    }
    if (match) {
        renderMatchDetailFull(match, () => renderPage('schedule'));
    } else {
        showModal('Mec', `<p>Detalji za ovaj mec nisu dostupni.</p>`);
    }
}

// ─── Matches (results history) ───
function renderMatches(el) {
    const matches = gameState?.matchHistory || [];
    let html = `<h2>Rezultati tvojih meceva</h2>`;
    if (matches.length === 0) {
        html += '<p style="color:#aaa;">Nema odigranih meceva.</p>';
    } else {
        matches.slice().reverse().forEach(m => {
            html += `<div class="cs-match-card cs-clickable" onclick="tifoMatchDetail(${m.round})">
                <div class="cs-match-teams">
                    <strong>Kolo ${m.round}</strong> &nbsp; ${m.homeTeamName} vs ${m.awayTeamName}
                </div>
                <div class="cs-match-score">${m.homeGoals} : ${m.awayGoals}</div>
            </div>`;
        });
    }
    el.innerHTML = html;
}

// ─── Match Detail (tabbed: Lineups | Goals | Stats) ───
function renderMatchDetail(round) {
    const match = gameState?.matchHistory?.find(m => m.round === round);
    if (!match) {
        document.getElementById('main-content').innerHTML = '<div class="manager-card"><p>Mec nije pronadjen</p></div>';
        return;
    }
    renderMatchDetailFull(match, () => renderPage('matches'));
}

function renderMatchDetailFull(match, backFn) {
    const main = document.getElementById('main-content');
    const homeName = match.homeTeamName;
    const awayName = match.awayTeamName;

    let html = `<div class="manager-card">
        <button class="big-button" onclick="window._tifoMatchBack()" style="margin-bottom:16px;">⬅ Nazad</button>
        <h2 style="text-align:center;">${homeName} ${match.homeGoals} : ${match.awayGoals} ${awayName}</h2>
        <p style="text-align:center;color:#aaa;">Kolo ${match.round}</p>

        <div class="cs-tabs">
            <button class="cs-tab-btn active" onclick="tifoMatchTab('lineups')">Postave</button>
            <button class="cs-tab-btn" onclick="tifoMatchTab('goals')">Golovi</button>
            <button class="cs-tab-btn" onclick="tifoMatchTab('stats')">Statistika</button>
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
        const label = tab === 'lineups' ? 'Postave' : tab === 'goals' ? 'Golovi' : 'Statistika';
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

    const renderLineup = (title, playerStats) => {
        if (!playerStats || playerStats.length === 0) return `<h3>${title}</h3><p style="color:#aaa;">Nema podataka o postavi</p>`;
        const sorted = [...playerStats].sort((a, b) => (posOrder[a.position] ?? 5) - (posOrder[b.position] ?? 5));
        let h = `<h3>${title}</h3>
            <div style="display:flex; gap:10px; padding:4px 12px; color:#888; font-size:0.8em;">
                <div class="cs-lineup-pos">POS</div>
                <div class="cs-lineup-name">Name</div>
                <div class="cs-lineup-rating">Rat</div>
                <div class="cs-lineup-stat">⚽</div>
                <div class="cs-lineup-stat">🅰️</div>
            </div>`;
        sorted.forEach(p => {
            const ratingColor = p.rating >= 7.5 ? '#4caf50' : p.rating >= 6.5 ? '#ffd700' : p.rating >= 5.5 ? '#ff9800' : '#f44336';
            h += `<div class="cs-lineup-row">
                <div class="cs-lineup-pos">${p.position}</div>
                <div class="cs-lineup-name">${p.playerName}</div>
                <div class="cs-lineup-rating" style="color:${ratingColor}">${p.rating?.toFixed(1) || '-'}</div>
                <div class="cs-lineup-stat">${p.goals || ''}</div>
                <div class="cs-lineup-stat">${p.assists || ''}</div>
            </div>`;
        });
        return h;
    };

    html += renderLineup(match.homeTeamName, match.homePlayerStats);
    html += `<div style="margin-top:20px;"></div>`;
    html += renderLineup(match.awayTeamName, match.awayPlayerStats);
    return html;
}

function buildGoalsHtml(match) {
    const goals = (match.events || []).filter(e => e.eventType === 'GOAL');
    if (goals.length === 0) return '<p style="color:#aaa;">Nema golova</p>';

    let html = '';
    goals.sort((a, b) => a.minute - b.minute).forEach(g => {
        html += `<div style="padding:8px 0; border-bottom:1px solid rgba(255,255,255,0.05);">
            <strong>${g.minute}'</strong> ⚽ ${g.playerName} <span style="color:#888;">(${g.teamName})</span>
            ${g.assistName ? `<span style="color:#666;"> asist: ${g.assistName}</span>` : ''}
            <span style="float:right;color:#aaa;font-weight:bold;">${g.scoreAfterGoal || ''}</span>
        </div>`;
    });
    return html;
}

function buildStatsHtml(match) {
    const events = match.events || [];
    const homeName = match.homeTeamName;
    const awayName = match.awayTeamName;
    const countFor = (type, team) => events.filter(e => e.eventType === type && e.teamName === team).length;

    return `<table class="cs-table">
        <thead><tr><th>Stat</th><th>${homeName}</th><th>${awayName}</th></tr></thead>
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

// ─── Tactics ───
function renderTactics(el) {
    const tactics = gameState?.tactics || {};
    const formations = ['4-4-2', '4-3-3', '4-2-3-1', '3-5-2', '5-3-2', '4-5-1'];
    const styles = ['BALANCED', 'ATTACKING', 'DEFENSIVE', 'COUNTER'];

    let html = `<h2>Taktika</h2>
        <h3>Formacija: <span id="currentFormation">${tactics.formation || '4-4-2'}</span></h3>
        <div class="cs-tactics-grid">`;
    formations.forEach(f => {
        const active = f === tactics.formation ? 'active' : '';
        html += `<div class="cs-tactics-btn ${active}" onclick="tifoSetTactics('${f}', null)">${f}</div>`;
    });
    html += `</div>
        <h3 style="margin-top:20px;">Stil: <span id="currentStyle">${tactics.style || 'BALANCED'}</span></h3>
        <div class="cs-tactics-grid">`;
    styles.forEach(s => {
        const active = s === tactics.style ? 'active' : '';
        html += `<div class="cs-tactics-btn ${active}" onclick="tifoSetTactics(null, '${s}')">${s}</div>`;
    });
    html += `</div>`;
    el.innerHTML = html;
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

// ─── Top Scorers ───
async function renderTopScorers(el) {
    el.innerHTML = '<h2>Najbolji strelci</h2><p>Loading...</p>';
    const res = await csApi('/api/cs/top-scorers');
    if (!res || !res.ok) { el.innerHTML = '<h2>Najbolji strelci</h2><p style="color:#aaa;">Nema podataka</p>'; return; }
    const data = await res.json();

    let html = '<h2>🏅 Najbolji strelci</h2>';
    if (data.length === 0) { html += '<p style="color:#aaa;">Nema golova u sezoni.</p>'; }
    else {
        data.forEach((p, i) => {
            html += `<div class="cs-rank-row">
                <div class="cs-rank-num">${i + 1}</div>
                <div class="cs-rank-name">${p.name} <span class="cs-rank-team">${p.teamName} (${p.position})</span></div>
                <div class="cs-rank-val">⚽ ${p.goals}</div>
            </div>`;
        });
    }
    el.innerHTML = html;
}

// ─── Top Assists ───
async function renderTopAssists(el) {
    el.innerHTML = '<h2>Najbolji asistenti</h2><p>Loading...</p>';
    const res = await csApi('/api/cs/top-assists');
    if (!res || !res.ok) { el.innerHTML = '<h2>Najbolji asistenti</h2><p style="color:#aaa;">Nema podataka</p>'; return; }
    const data = await res.json();

    let html = '<h2>🅰️ Najbolji asistenti</h2>';
    if (data.length === 0) { html += '<p style="color:#aaa;">Nema asistencija u sezoni.</p>'; }
    else {
        data.forEach((p, i) => {
            html += `<div class="cs-rank-row">
                <div class="cs-rank-num">${i + 1}</div>
                <div class="cs-rank-name">${p.name} <span class="cs-rank-team">${p.teamName} (${p.position})</span></div>
                <div class="cs-rank-val">🅰️ ${p.assists}</div>
            </div>`;
        });
    }
    el.innerHTML = html;
}

// ─── Transfers (dummy) ───
function renderTransfers(el) {
    el.innerHTML = `<h2>🔄 Transferi</h2>
        <div class="cs-transfer-placeholder">
            <div class="icon">🔄</div>
            <h3>Transfer Market</h3>
            <p>Transfer prozor je trenutno zatvoren.</p>
            <p style="margin-top:12px;">Transferi ce biti dostupni u narednoj verziji.</p>
        </div>`;
}

// ─── Next Round ───
async function nextRound() {
    const btn = document.getElementById('nextRoundBtn');
    if (btn) { btn.disabled = true; btn.textContent = 'Simulating...'; }

    try {
        const res = await csApi('/api/cs/next-round', { method: 'POST' });
        if (!res || !res.ok) {
            const err = await res?.json();
            alert(err?.error || 'Greska pri simulaciji');
            return;
        }
        const data = await res.json();

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

        updateRoundInfo();

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
        alert('Greska: ' + e.message);
    } finally {
        if (btn) { btn.disabled = false; btn.textContent = 'Next Round →'; }
    }
}

// ─── Mobile menu ───
function toggleMobileMenu() {
    const sidebar = document.getElementById('mobileSidebar');
    const overlay = document.getElementById('mobileOverlay');
    sidebar?.classList.toggle('active');
    overlay?.classList.toggle('active');
}

// ─── Expose to global scope (for onclick handlers in HTML) ───
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
