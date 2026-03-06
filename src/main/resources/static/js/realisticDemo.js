// realisticDemo.js
import { authFetch } from './auth.js';

let matchId = null;
let positionSocket = null;
let eventSocket = null;
let streamMinute = 0;
let displayMinute = 0;
let homeScore = 0;
let awayScore = 0;
let isProcessingEvent = false;
let bannerTimeout = null;
const EVENT_DELAY = 1650;
const GOAL_DELAY = 2600;
const playerElements = new Map();
const eventQueue = [];
const playerSlots = new Map();
const playerNames = new Map();

const matchData = {
    homeTeam: 'Home',
    awayTeam: 'Away'
};

window.addEventListener('load', async () => {
    const params = new URLSearchParams(window.location.search);
    matchId = params.get('matchId');

    if (!matchId) {
        document.getElementById('events-list').innerHTML = '<p style="color:#f44336;">Missing matchId.</p>';
        return;
    }

    await Promise.all([loadMatchData(), loadLineups()]);
    connectToWebSockets();
});

async function loadMatchData() {
    try {
        const response = await authFetch(`/matches/${matchId}`);
        if (!response.ok) throw new Error('Failed to load match data');

        const data = await response.json();
        matchData.homeTeam = data.homeTeam || matchData.homeTeam;
        matchData.awayTeam = data.awayTeam || matchData.awayTeam;
        homeScore = 0;
        awayScore = 0;
        document.getElementById('homeTeam').textContent = matchData.homeTeam;
        document.getElementById('awayTeam').textContent = matchData.awayTeam;
        updateDisplayedMinute(0);
        updateScore();
    } catch (error) {
        console.error('Error loading match data:', error);
    }
}

async function loadLineups() {
    try {
        const response = await authFetch(`/matches/${matchId}/lineups`);
        if (!response.ok) return;

        const data = await response.json();
        (data.homeLineup || []).forEach(registerPlayerSlot);
        (data.awayLineup || []).forEach(registerPlayerSlot);
    } catch (error) {
        console.warn('Failed to load lineups for realistic demo:', error);
    }
}

function registerPlayerSlot(player) {
    if (!player || !Number.isFinite(Number(player.id))) return;
    playerSlots.set(Number(player.id), player);
    if (player.name) {
        playerNames.set(normalize(player.name), Number(player.id));
    }
}

function connectToWebSockets() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const token = localStorage.getItem('token');

    if (!token) {
        document.getElementById('events-list').innerHTML = '<p style="color:#f44336;">JWT token missing.</p>';
        return;
    }

    positionSocket = new WebSocket(`${protocol}//${window.location.host}/demo-position-updates?matchId=${matchId}&token=${token}`);
    eventSocket = new WebSocket(`${protocol}//${window.location.host}/demo-match-events?matchId=${matchId}&token=${token}`);

    positionSocket.onopen = () => console.log('Position socket connected');
    positionSocket.onmessage = event => handlePositionUpdate(event.data);
    positionSocket.onerror = error => console.error('Position socket error:', error);
    positionSocket.onclose = () => console.log('Position socket closed');

    eventSocket.onopen = () => console.log('Event socket connected');
    eventSocket.onmessage = event => handleMatchEvent(event.data);
    eventSocket.onerror = error => console.error('Event socket error:', error);
    eventSocket.onclose = () => console.log('Event socket closed');
}

function handlePositionUpdate(raw) {
    try {
        const state = JSON.parse(raw);
        if (state.matchId && String(state.matchId) !== String(matchId)) return;

        const minute = Number.isFinite(state.minute) ? state.minute : Number.isFinite(state.second) ? state.second : streamMinute;
        streamMinute = minute;
        if (!isProcessingEvent && eventQueue.length === 0) {
            updateDisplayedMinute(streamMinute);
        }
        renderPlayers(state);
    } catch (error) {
        console.error('Position parse error:', error, raw);
    }
}

function handleMatchEvent(raw) {
    try {
        const event = JSON.parse(raw);
        eventQueue.push(event);
        processNextEvent();
    } catch (error) {
        console.error('Event parse error:', error, raw);
    }
}

function processNextEvent() {
    if (isProcessingEvent || eventQueue.length === 0) return;
    isProcessingEvent = true;

    const event = eventQueue.shift();
    applyEventState(event);
    renderEvent(event);
    renderPitchEvent(event);

    const delay = (event.type || '').toLowerCase() === 'goal' ? GOAL_DELAY : EVENT_DELAY;
    setTimeout(() => {
        isProcessingEvent = false;
        if (eventQueue.length === 0 && streamMinute > displayMinute) {
            updateDisplayedMinute(streamMinute);
        }
        processNextEvent();
    }, delay);
}

function applyEventState(event) {
    if (event.homeTeamName) matchData.homeTeam = event.homeTeamName;
    if (event.awayTeamName) matchData.awayTeam = event.awayTeamName;
    document.getElementById('homeTeam').textContent = matchData.homeTeam;
    document.getElementById('awayTeam').textContent = matchData.awayTeam;

    if (Number.isFinite(event.minute)) {
        updateDisplayedMinute(event.minute);
    }

    const parsedScore = parseScore(event.scoreAfterGoal);
    if (parsedScore) {
        homeScore = parsedScore.home;
        awayScore = parsedScore.away;
    } else if (Number.isFinite(event.homeGoals) && Number.isFinite(event.awayGoals)) {
        homeScore = event.homeGoals;
        awayScore = event.awayGoals;
    } else if ((event.type || '').toLowerCase() === 'goal') {
        const eventTeam = normalize(event.teamName);
        if (eventTeam && eventTeam === normalize(matchData.homeTeam)) homeScore += 1;
        if (eventTeam && eventTeam === normalize(matchData.awayTeam)) awayScore += 1;
    }

    updateScore();
}

function renderEvent(event) {
    const eventsList = document.getElementById('events-list');
    const loading = eventsList.querySelector('.loading');
    if (loading) loading.remove();

    const item = document.createElement('div');
    item.className = `event ${(event.type || '').toLowerCase()}`;
    item.innerHTML = buildEventHtml(event);
    eventsList.prepend(item);

    while (eventsList.children.length > 50) {
        eventsList.removeChild(eventsList.lastChild);
    }
}

function buildEventHtml(event) {
    const minute = Number.isFinite(event.minute) ? event.minute : displayMinute;
    const type = (event.type || '').toLowerCase();
    const player = event.playerName || event.scorerName || event.takerName || event.goalkeeperName || event.playerOutName || event.playerInName || 'Unknown';

    switch (type) {
        case 'matchstarted':
            return `<strong>${minute}'</strong> Kick-off: <strong>${matchData.homeTeam}</strong> vs <strong>${matchData.awayTeam}</strong>`;
        case 'pass':
            return `<strong>${minute}'</strong> Pass: <strong>${player}</strong>${event.targetPlayerName ? ` -> ${event.targetPlayerName}` : ''}${event.teamName ? ` <span style="opacity:.8">(${event.teamName})</span>` : ''}`;
        case 'interception':
            return `<strong>${minute}'</strong> Interception: <strong>${player}</strong>${event.secondaryPlayerName ? ` stopped ${event.secondaryPlayerName}` : ''}${event.teamName ? ` <span style="opacity:.8">(${event.teamName})</span>` : ''}`;
        case 'dribble':
            return `<strong>${minute}'</strong> Carry by <strong>${player}</strong>${event.teamName ? ` <span style="opacity:.8">(${event.teamName})</span>` : ''}`;
        case 'duel':
            return `<strong>${minute}'</strong> Duel won by <strong>${player}</strong>${event.secondaryPlayerName ? ` over ${event.secondaryPlayerName}` : ''}${event.teamName ? ` <span style="opacity:.8">(${event.teamName})</span>` : ''}`;
        case 'goal':
            return `<strong>${minute}'</strong> Goal: <strong>${player}</strong>${event.assistantName ? `, assist ${event.assistantName}` : ''} <span style="opacity:.8">(${event.teamName || 'team'})</span>`;
        case 'chance':
            return `<strong>${minute}'</strong> ${event.description || `Chance for ${player}`}`;
        case 'possession':
            return `<strong>${minute}'</strong> Possession: <strong>${event.teamName || 'team'}</strong>${event.playerName ? ` via ${event.playerName}` : ''}`;
        case 'shotontarget':
            return `<strong>${minute}'</strong> Shot on target by <strong>${player}</strong>`;
        case 'shotofftarget':
            return `<strong>${minute}'</strong> Shot off target by <strong>${player}</strong>`;
        case 'yellowcard':
            return `<strong>${minute}'</strong> Yellow card: <strong>${player}</strong>`;
        case 'redcard':
            return `<strong>${minute}'</strong> Red card: <strong>${player}</strong>`;
        case 'corner':
            return `<strong>${minute}'</strong> Corner for <strong>${event.teamName || 'team'}</strong>`;
        case 'throwin':
            return `<strong>${minute}'</strong> Throw-in for <strong>${event.teamName || 'team'}</strong>`;
        case 'goalkick':
            return `<strong>${minute}'</strong> Goal kick by <strong>${player}</strong>`;
        case 'freekick':
            return `<strong>${minute}'</strong> Free kick for <strong>${event.teamName || 'team'}</strong>`;
        case 'injury':
            return `<strong>${minute}'</strong> Injury: <strong>${player}</strong>`;
        case 'substitution':
            return `<strong>${minute}'</strong> Substitution: <strong>${event.playerOutName || '?'}</strong> -> <strong>${event.playerInName || '?'}</strong>`;
        case 'varreview':
            return `<strong>${minute}'</strong> VAR ${String(event.decision || 'pending').toUpperCase()}${event.overturnReason ? ` (${event.overturnReason})` : ''}`;
        case 'matchended':
            return `<strong>${minute}'</strong> Full time: <strong>${matchData.homeTeam}</strong> ${homeScore} - ${awayScore} <strong>${matchData.awayTeam}</strong>`;
        default:
            return `<strong>${minute}'</strong> ${event.description || type || 'event'}`;
    }
}

function renderPitchEvent(event) {
    const type = (event.type || '').toLowerCase();
    const mainName = event.playerName || event.scorerName || event.takerName || event.goalkeeperName;
    const secondaryName = event.targetPlayerName || event.secondaryPlayerName || event.assistantName;

    clearPitchHighlights();
    showPitchBanner(buildPitchBannerText(event));

    const mainEl = findPlayerElementByName(mainName);
    const secondaryEl = findPlayerElementByName(secondaryName);

    if (mainEl) {
        mainEl.classList.add('involved-primary');
    }
    if (secondaryEl) {
        secondaryEl.classList.add('involved-secondary');
    }

}

function buildPitchBannerText(event) {
    const minute = Number.isFinite(event.minute) ? event.minute : displayMinute;
    const type = (event.type || '').toLowerCase();
    const player = event.playerName || event.scorerName || event.takerName || event.goalkeeperName || event.playerOutName || event.playerInName || '';

    switch (type) {
        case 'pass':
            return `${minute}' PASS ${player}${event.targetPlayerName ? ` -> ${event.targetPlayerName}` : ''}`;
        case 'interception':
            return `${minute}' INTERCEPTION ${player}`;
        case 'duel':
            return `${minute}' DUEL ${player}`;
        case 'goal':
            return `${minute}' GOAL ${player}`;
        case 'shotontarget':
            return `${minute}' SHOT ON TARGET ${player}`;
        case 'shotofftarget':
            return `${minute}' SHOT OFF TARGET ${player}`;
        case 'chance':
            return `${minute}' CHANCE ${player}`;
        default:
            return `${minute}' ${(type || 'event').toUpperCase()}`;
    }
}

function showPitchBanner(text) {
    const banner = document.getElementById('pitch-event-banner');
    if (!banner) return;

    banner.textContent = text;
    banner.classList.add('visible');
    if (bannerTimeout) clearTimeout(bannerTimeout);
    bannerTimeout = setTimeout(() => banner.classList.remove('visible'), 900);
}

function clearPitchHighlights() {
    for (const el of playerElements.values()) {
        el.classList.remove('involved-primary', 'involved-secondary');
    }
}

function findPlayerElementByName(name) {
    if (!name) return null;
    const playerId = playerNames.get(normalize(name));
    return playerId != null ? playerElements.get(String(playerId)) || null : null;
}

function renderPlayers(state) {
    const container = document.getElementById('players-container');
    if (!Array.isArray(state.players) || state.players.length === 0) return;

    const seen = new Set();

    state.players.forEach(player => {
        const key = String(player.id);
        let el = playerElements.get(key);
        if (!el) {
            el = document.createElement('div');
            el.className = `player ${(player.team || 'HOME').toLowerCase()}`;
            el.dataset.playerId = key;
            playerElements.set(key, el);
            container.appendChild(el);
        }

        const slotData = playerSlots.get(Number(player.id));
        const x = clamp(player.x ?? 50, 0, 100);
        const y = clamp(player.y ?? 50, 0, 100);
        el.textContent = getPlayerBadge(player.id, slotData);
        el.title = slotData ? `${slotData.name} (${slotData.position || 'N/A'})` : `Slot ${player.id}`;
        el.classList.toggle('carrier', Number(player.id) === Number(state.carrierPlayerId));
        el.classList.toggle('home', (player.team || '').toLowerCase() === 'home');
        el.classList.toggle('away', (player.team || '').toLowerCase() === 'away');
        el.style.left = `calc(${x}% - 20px)`;
        el.style.top = `calc(${y}% - 20px)`;
        el.dataset.x = String(x);
        el.dataset.y = String(y);
        seen.add(key);
    });

    for (const [key, el] of playerElements.entries()) {
        if (!seen.has(key)) {
            el.remove();
            playerElements.delete(key);
        }
    }

    if (state.ball && Number.isFinite(state.ball.x) && Number.isFinite(state.ball.y)) {
        const ballEl = document.getElementById('ball');
        ballEl.style.left = `calc(${clamp(state.ball.x, 0, 100)}% - 7.5px)`;
        ballEl.style.top = `calc(${clamp(state.ball.y, 0, 100)}% - 7.5px)`;
        ballEl.style.display = 'block';
    }
}

function getPlayerBadge(positionId, slotData) {
    if (slotData && Number.isFinite(Number(slotData.squadNumber)) && Number(slotData.squadNumber) > 0) {
        return String(slotData.squadNumber);
    }
    if (!slotData || !slotData.name) {
        return String(positionId);
    }
    const parts = slotData.name.trim().split(/\s+/).filter(Boolean);
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return `${parts[0][0] || ''}${parts[parts.length - 1][0] || ''}`.toUpperCase();
}

function updateDisplayedMinute(minute) {
    displayMinute = Math.max(displayMinute, Number(minute) || 0);
    document.getElementById('minute').textContent = `${displayMinute}'`;
}

function updateScore() {
    document.getElementById('score').textContent = `${homeScore} - ${awayScore}`;
    document.getElementById('homeStats').textContent = `Goals: ${homeScore}`;
    document.getElementById('awayStats').textContent = `Goals: ${awayScore}`;
}

function parseScore(value) {
    if (!value || typeof value !== 'string') return null;
    const match = value.match(/(\d+)\s*[:\-]\s*(\d+)/);
    if (!match) return null;
    return { home: Number(match[1]), away: Number(match[2]) };
}

function normalize(value) {
    return (value || '').trim().toLowerCase();
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

window.addEventListener('beforeunload', () => {
    if (positionSocket) positionSocket.close();
    if (eventSocket) eventSocket.close();
});
