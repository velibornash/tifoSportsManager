
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
let animationFrame = null;
let activeAnimation = null;
let goalGifTimeout = null;

const EVENT_DELAY = 1650;
const GOAL_DELAY = 2600;
const playerElements = new Map();
const eventQueue = [];
const playerSlots = new Map();
const playerNames = new Map();
const latestPositions = new Map();
const pendingVarGoals = [];
const matchEndedImg = new Image();
matchEndedImg.src = '/images/match_ended.jpg';

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

    initPitchOverlay();
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
        updateDisplayedMinute(0, true);
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

    positionSocket.onmessage = event => handlePositionUpdate(event.data);
    positionSocket.onerror = error => console.error('Position socket error:', error);

    eventSocket.onmessage = event => handleMatchEvent(event.data);
    eventSocket.onerror = error => console.error('Event socket error:', error);
}

function handlePositionUpdate(raw) {
    try {
        const state = JSON.parse(raw);
        if (state.matchId && String(state.matchId) !== String(matchId)) return;

        const minute = Number.isFinite(state.minute)
            ? state.minute
            : Number.isFinite(state.second)
                ? state.second
                : streamMinute;
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

    const type = (event.type || '').toLowerCase();
    const delay = type === 'varreview'
        ? 2700
        : type === 'matchended'
            ? 2500
            : type === 'goal'
                ? GOAL_DELAY
                : isCanvasAnimationEvent(type)
                    ? 2200
                    : EVENT_DELAY;

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

    const type = (event.type || '').toLowerCase();
    const parsedScore = parseScore(event.scoreAfterGoal);
    if (parsedScore) {
        homeScore = parsedScore.home;
        awayScore = parsedScore.away;
    } else if (Number.isFinite(event.homeGoals) && Number.isFinite(event.awayGoals)) {
        homeScore = event.homeGoals;
        awayScore = event.awayGoals;
    } else if (type === 'goal') {
        const eventTeam = normalize(event.teamName);
        if (eventTeam && eventTeam === normalize(matchData.homeTeam)) homeScore += 1;
        if (eventTeam && eventTeam === normalize(matchData.awayTeam)) awayScore += 1;
    }

    if (type === 'goal') {
        const scorer = event.scorerName || event.playerName || '';
        const assistant = event.assistantName || '';
        pendingVarGoals.push({ scorer, assistant, teamName: event.teamName || '' });
    }

    if (type === 'varreview'
        && String(event.decision || '').toLowerCase() === 'overturned'
        && String(event.reviewTarget || '').toLowerCase() === 'goal') {
        rollbackGoalFromVar(event);
    }

    updateScore();
}

function rollbackGoalFromVar(event) {
    const reviewedPlayer = event.playerName || '';
    const eventTeam = normalize(event.teamName);
    if (eventTeam && eventTeam === normalize(matchData.homeTeam)) {
        homeScore = Math.max(0, homeScore - 1);
    } else if (eventTeam && eventTeam === normalize(matchData.awayTeam)) {
        awayScore = Math.max(0, awayScore - 1);
    }

    for (let i = pendingVarGoals.length - 1; i >= 0; i -= 1) {
        const candidate = pendingVarGoals[i];
        if (!reviewedPlayer || normalize(candidate.scorer) === normalize(reviewedPlayer)) {
            pendingVarGoals.splice(i, 1);
            break;
        }
    }
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
        case 'penalty':
            return `<strong>${minute}'</strong> Penalty: <strong>${player}</strong>${event.scored ? ' scored' : ' missed'}`;
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
        case 'offside':
            return `<strong>${minute}'</strong> Offside: <strong>${player}</strong>`;
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

    if (mainEl) mainEl.classList.add('involved-primary');
    if (secondaryEl) secondaryEl.classList.add('involved-secondary');

    if (type === 'goal') {
        showGoalCelebration(event);
    }

    if (isCanvasAnimationEvent(type)) {
        startCanvasAnimation(event);
    }
}

function getSlotByName(name) {
    if (!name) return null;
    const playerId = playerNames.get(normalize(name));
    return playerId != null ? playerSlots.get(Number(playerId)) || null : null;
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
        case 'offside':
            return `${minute}' OFFSIDE ${player}`;
        case 'goal':
            return `${minute}' GOAL ${player}`;
        case 'penalty':
            return `${minute}' PENALTY ${player}`;
        case 'shotontarget':
            return `${minute}' SHOT ON TARGET ${player}`;
        case 'shotofftarget':
            return `${minute}' SHOT OFF TARGET ${player}`;
        case 'varreview':
            return `${minute}' VAR REVIEW`;
        case 'substitution':
            return `${minute}' SUBSTITUTION`;
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

function showGoalCelebration(event) {
    const goalGif = document.getElementById('goal-celebration');
    if (!goalGif) return;

    const direction = getAttackDirection(event);
    goalGif.style.left = direction === 1 ? '92%' : '8%';
    goalGif.style.top = '50%';
    goalGif.classList.add('visible');

    if (goalGifTimeout) {
        clearTimeout(goalGifTimeout);
    }
    goalGifTimeout = setTimeout(() => {
        goalGif.classList.remove('visible');
    }, 1800);
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
        latestPositions.set(Number(player.id), { x, y, team: (player.team || '').toUpperCase() });
        seen.add(key);
    });

    for (const [key, el] of playerElements.entries()) {
        if (!seen.has(key)) {
            el.remove();
            playerElements.delete(key);
            latestPositions.delete(Number(key));
        }
    }

    if (state.ball && Number.isFinite(state.ball.x) && Number.isFinite(state.ball.y)) {
        const ballEl = document.getElementById('ball');
        ballEl.style.left = `calc(${clamp(state.ball.x, 0, 100)}% - 7.5px)`;
        ballEl.style.top = `calc(${clamp(state.ball.y, 0, 100)}% - 7.5px)`;
        ballEl.style.display = 'block';
    }
}

function initPitchOverlay() {
    const overlay = document.getElementById('pitch-overlay');
    if (!overlay) return;
    syncPitchOverlaySize();
    window.addEventListener('resize', syncPitchOverlaySize);
}

function syncPitchOverlaySize() {
    const pitch = document.querySelector('.pitch');
    const overlay = document.getElementById('pitch-overlay');
    if (!pitch || !overlay) return;
    const rect = pitch.getBoundingClientRect();
    overlay.width = Math.max(1, Math.round(rect.width));
    overlay.height = Math.max(1, Math.round(rect.height));
}

function isShotAnimationEvent(type) {
    return type === 'goal' || type === 'shotontarget' || type === 'shotofftarget' || type === 'penalty';
}

function isCanvasAnimationEvent(type) {
    return isShotAnimationEvent(type) || type === 'varreview' || type === 'substitution' || type === 'matchended' || type === 'injury';
}

function startCanvasAnimation(event) {
    clearCanvasAnimation();
    syncPitchOverlaySize();

    const type = (event.type || '').toLowerCase();
    if (isShotAnimationEvent(type)) {
        const direction = getAttackDirection(event);
        const shooter = resolveShooterPoint(event, direction, type);
        const keeper = resolveGoalkeeperPoint(direction);
        const target = resolveShotTarget(event, type, direction, keeper);
        activeAnimation = {
            type,
            event,
            shooter,
            keeper,
            target,
            scored: Boolean(event.scored),
            startTs: performance.now(),
            duration: type === 'goal' || type === 'penalty' ? 1200 : 950
        };
    } else {
        activeAnimation = {
            type,
            event,
            startTs: performance.now(),
            duration: type === 'varreview' ? 2600 : type === 'matchended' ? 2300 : 2200
        };
    }

    const ballEl = document.getElementById('ball');
    if (ballEl && isShotAnimationEvent(type)) {
        ballEl.style.opacity = '0';
    }

    animationFrame = requestAnimationFrame(drawAnimationFrame);
}

function clearCanvasAnimation() {
    if (animationFrame) {
        cancelAnimationFrame(animationFrame);
        animationFrame = null;
    }
    activeAnimation = null;
    const overlay = document.getElementById('pitch-overlay');
    if (overlay) {
        const ctx = overlay.getContext('2d');
        ctx.clearRect(0, 0, overlay.width, overlay.height);
    }
    const ballEl = document.getElementById('ball');
    if (ballEl) {
        ballEl.style.opacity = '1';
    }
}

function drawAnimationFrame(timestamp) {
    if (!activeAnimation) return;
    const overlay = document.getElementById('pitch-overlay');
    if (!overlay) return;

    const ctx = overlay.getContext('2d');
    const progress = Math.min(1, (timestamp - activeAnimation.startTs) / activeAnimation.duration);
    ctx.clearRect(0, 0, overlay.width, overlay.height);

    if (isShotAnimationEvent(activeAnimation.type)) {
        drawShotMarkers(ctx, activeAnimation, progress);
        drawAnimatedBall(ctx, activeAnimation, progress);
    } else if (activeAnimation.type === 'varreview') {
        drawVarAnimation(ctx, overlay, activeAnimation, progress);
    } else if (activeAnimation.type === 'substitution') {
        drawSubstitutionAnimation(ctx, overlay, activeAnimation);
    } else if (activeAnimation.type === 'matchended') {
        drawMatchEndedAnimation(ctx, overlay);
    } else if (activeAnimation.type === 'injury') {
        drawInjuryAnimation(ctx, overlay, activeAnimation, progress);
    }

    if (progress < 1) {
        animationFrame = requestAnimationFrame(drawAnimationFrame);
    } else {
        clearCanvasAnimation();
    }
}

function drawShotMarkers(ctx, anim, progress) {
    drawPulseCircle(ctx, anim.shooter.x, anim.shooter.y, 13, anim.shooter.color, 0.9);
    drawPulseCircle(ctx, anim.keeper.x, anim.keeper.y, 14, '#f0ad15', 0.78);

    if ((anim.type === 'goal' || (anim.type === 'penalty' && anim.scored)) && progress > 0.82) {
        const glowWidth = 18;
        const goalX = anim.target.x + (anim.target.x > anim.keeper.x ? 4 : -4);
        ctx.fillStyle = 'rgba(255,255,255,0.14)';
        ctx.fillRect(goalX - glowWidth / 2, anim.target.y - 28, glowWidth, 56);
    }
}

function drawAnimatedBall(ctx, anim, progress) {
    const eased = 1 - Math.pow(1 - progress, 3);
    const x = anim.shooter.x + (anim.target.x - anim.shooter.x) * eased;
    const y = anim.shooter.y + (anim.target.y - anim.shooter.y) * eased;
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.arc(x, y, 7, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#1b1b1b';
    ctx.lineWidth = 1.2;
    ctx.stroke();
}

function drawPulseCircle(ctx, x, y, radius, color, alpha) {
    ctx.save();
    ctx.globalAlpha = alpha;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(x, y, radius, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
}

function drawVarAnimation(ctx, overlay, anim, progress) {
    const w = overlay.width;
    const h = overlay.height;

    ctx.fillStyle = 'rgba(8, 16, 28, 0.72)';
    ctx.fillRect(0, 0, w, h);

    const cardW = Math.min(520, w - 40);
    const cardH = 230;
    const cardX = (w - cardW) / 2;
    const cardY = (h - cardH) / 2;
    drawRoundedRect(ctx, cardX, cardY, cardW, cardH, 14, '#0f2038', '#3f5f90');

    drawCameraIcon(ctx, cardX + 72, cardY + 70);

    ctx.textAlign = 'left';
    ctx.fillStyle = '#d9f2ff';
    ctx.font = '700 31px Arial';
    if (progress < 0.62) {
        const dots = '.'.repeat((Math.floor(progress * 10) % 3) + 1);
        ctx.fillText(`VAR CHECK${dots}`, cardX + 142, cardY + 96);
        ctx.fillStyle = '#9cb8d8';
        ctx.font = '600 20px Arial';
        ctx.fillText(`Reviewing ${anim.event.reviewTarget || 'incident'}...`, cardX + 142, cardY + 132);
    } else {
        const isOverturned = String(anim.event.decision || '').toLowerCase() === 'overturned';
        ctx.fillStyle = isOverturned ? '#ff6767' : '#8bff9f';
        ctx.fillText(`VAR ${String(anim.event.decision || 'PENDING').toUpperCase()}`, cardX + 142, cardY + 96);
        ctx.fillStyle = '#ffffff';
        ctx.font = '600 22px Arial';
        const reason = anim.event.overturnReason || 'check complete';
        ctx.fillText(`${anim.event.reviewTarget || 'incident'} - ${reason}`, cardX + 142, cardY + 134);
    }
    ctx.textAlign = 'start';
}
function drawInjuryAnimation(ctx, overlay, anim, progress) {
    const w = overlay.width;
    const h = overlay.height;
    const pulse = 0.55 + Math.abs(Math.sin(progress * Math.PI * 6)) * 0.35;
    ctx.fillStyle = `rgba(140, 22, 22, ${pulse})`;
    ctx.fillRect(0, 0, w, h);
    ctx.textAlign = 'center';
    ctx.fillStyle = '#fff';
    ctx.font = '700 46px Arial';
    ctx.fillText('INJURY', w / 2, h / 2 - 16);
    ctx.font = '600 25px Arial';
    ctx.fillText(anim.event.playerName || 'Player', w / 2, h / 2 + 28);
    ctx.textAlign = 'start';
}

function drawSubstitutionAnimation(ctx, overlay, anim) {
    const w = overlay.width;
    const h = overlay.height;
    ctx.fillStyle = 'rgba(12, 28, 20, 0.78)';
    ctx.fillRect(0, 0, w, h);
    ctx.textAlign = 'center';
    ctx.fillStyle = '#d8ffe8';
    ctx.font = '700 42px Arial';
    ctx.fillText('SUBSTITUTION', w / 2, h / 2 - 30);
    ctx.font = '700 26px Arial';
    ctx.fillStyle = '#ff9b9b';
    ctx.fillText(anim.event.playerOutName || 'Player out', w / 2, h / 2 + 8);
    ctx.fillStyle = '#9bffb5';
    ctx.fillText(anim.event.playerInName || 'Player in', w / 2, h / 2 + 48);
    ctx.textAlign = 'start';
}

function drawMatchEndedAnimation(ctx, overlay) {
    const w = overlay.width;
    const h = overlay.height;

    ctx.fillStyle = 'rgba(8, 12, 20, 0.66)';
    ctx.fillRect(0, 0, w, h);

    const cardW = Math.min(460, w - 36);
    const cardH = 250;
    const cardX = Math.floor((w - cardW) / 2);
    const cardY = Math.floor((h - cardH) / 2);
    drawRoundedRect(ctx, cardX, cardY, cardW, cardH, 14, '#111b2a', '#3e5f86');

    if (matchEndedImg.complete) {
        ctx.drawImage(matchEndedImg, cardX + 20, cardY + 20, 180, 120);
    }

    ctx.fillStyle = '#d9f2ff';
    ctx.font = '700 36px Arial';
    ctx.fillText('FULL TIME', cardX + 220, cardY + 92);
    ctx.font = '600 24px Arial';
    ctx.fillStyle = '#ffffff';
    ctx.fillText(`${matchData.homeTeam} ${homeScore} - ${awayScore} ${matchData.awayTeam}`, cardX + 28, cardY + 190);
}

function drawRoundedRect(ctx, x, y, w, h, r, fill, stroke) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
    ctx.fillStyle = fill;
    ctx.fill();
    ctx.strokeStyle = stroke;
    ctx.lineWidth = 2;
    ctx.stroke();
}

function drawCameraIcon(ctx, cx, cy) {
    ctx.fillStyle = '#4fb2ff';
    ctx.fillRect(cx - 30, cy - 18, 60, 36);
    ctx.fillStyle = '#0f2038';
    ctx.fillRect(cx + 24, cy - 10, 20, 20);
    ctx.fillStyle = '#d6f3ff';
    ctx.beginPath();
    ctx.arc(cx, cy, 12, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#4fb2ff';
    ctx.beginPath();
    ctx.arc(cx, cy, 6, 0, Math.PI * 2);
    ctx.fill();
}

function getAttackDirection(event) {
    const teamName = normalize(event.teamName);
    if (teamName && teamName === normalize(matchData.homeTeam)) {
        return 1;
    }
    return -1;
}

function resolveShooterPoint(event, direction, type) {
    if (type === 'penalty') {
        return getPenaltySpot(direction);
    }

    const name = event.playerName || event.scorerName || event.takerName || event.goalkeeperName;
    const fromPlayer = getPlayerPointByName(name);
    if (fromPlayer) return fromPlayer;

    const fallbackX = direction === 1 ? 78 : 22;
    return {
        x: pitchPercentToX(fallbackX),
        y: pitchPercentToY(50),
        color: resolveTeamColorForEvent(event)
    };
}

function resolveGoalkeeperPoint(direction) {
    const defendingTeam = direction === 1 ? 'AWAY' : 'HOME';
    for (const [playerId, slot] of playerSlots.entries()) {
        if ((slot.position || '').toUpperCase() !== 'GK') continue;
        const current = latestPositions.get(Number(playerId));
        if (current && current.team === defendingTeam) {
            return {
                x: pitchPercentToX(current.x),
                y: pitchPercentToY(current.y),
                color: '#f0ad15'
            };
        }
    }

    return {
        x: pitchPercentToX(direction === 1 ? 94 : 6),
        y: pitchPercentToY(50),
        color: '#f0ad15'
    };
}

function resolveShotTarget(event, type, direction, keeper) {
    const insideGoalX = direction === 1 ? 97 : 3;
    const missX = direction === 1 ? 100 : 0;
    const verticalBias = (Math.random() - 0.5) * 26;

    if (type === 'goal' || (type === 'penalty' && event.scored)) {
        const keeperAvoid = keeper.y <= pitchPercentToY(50) ? 18 : -18;
        return {
            x: pitchPercentToX(insideGoalX),
            y: clampPx(keeper.y + keeperAvoid + verticalBias * 0.15, 26)
        };
    }

    if (type === 'shotontarget' || (type === 'penalty' && !event.scored)) {
        return {
            x: keeper.x,
            y: keeper.y + (Math.random() - 0.5) * 8
        };
    }

    return {
        x: pitchPercentToX(missX),
        y: clampPx(pitchPercentToY(50) + verticalBias + (Math.random() < 0.5 ? -30 : 30), 12)
    };
}

function resolveTeamColorForEvent(event) {
    return getAttackDirection(event) === 1 ? '#FF6B6B' : '#4ECDC4';
}

function getPlayerPointByName(name) {
    if (!name) return null;
    const playerId = playerNames.get(normalize(name));
    if (playerId == null) return null;
    const pos = latestPositions.get(Number(playerId));
    if (!pos) return null;
    return {
        x: pitchPercentToX(pos.x),
        y: pitchPercentToY(pos.y),
        color: pos.team === 'HOME' ? '#FF6B6B' : '#4ECDC4'
    };
}

function getPenaltySpot(direction) {
    return {
        x: pitchPercentToX(direction === 1 ? 88 : 12),
        y: pitchPercentToY(50),
        color: direction === 1 ? '#FF6B6B' : '#4ECDC4'
    };
}

function pitchPercentToX(percent) {
    const overlay = document.getElementById('pitch-overlay');
    return (clamp(percent, 0, 100) / 100) * (overlay?.width || 1);
}

function pitchPercentToY(percent) {
    const overlay = document.getElementById('pitch-overlay');
    return (clamp(percent, 0, 100) / 100) * (overlay?.height || 1);
}

function clampPx(value, padding) {
    const overlay = document.getElementById('pitch-overlay');
    const maxY = Math.max((overlay?.height || 1) - padding, padding);
    return Math.max(padding, Math.min(maxY, value));
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

function updateDisplayedMinute(minute, force = false) {
    const nextMinute = Number(minute) || 0;
    displayMinute = force ? nextMinute : Math.max(displayMinute, nextMinute);
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
    clearCanvasAnimation();
    if (goalGifTimeout) clearTimeout(goalGifTimeout);
    if (positionSocket) positionSocket.close();
    if (eventSocket) eventSocket.close();
});
