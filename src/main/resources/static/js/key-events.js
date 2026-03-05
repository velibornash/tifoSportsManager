// key-event.js
import { authFetch } from './auth.js';

const matchId = new URLSearchParams(window.location.search).get('matchId');

const scoreboardEl = document.getElementById('scoreboard');
const lastEventBox = document.getElementById('lastEventBox');
const eventImage = document.getElementById('eventImage');
const playerImage = document.getElementById('playerImage');
const playerInfo = document.getElementById('playerInfo');
const feedEl = document.getElementById('eventFeed');
const canvas = document.getElementById('keyPitch');
const ctx = canvas.getContext('2d');

const state = {
    homeTeamName: 'Home',
    awayTeamName: 'Away',
    homeScore: 0,
    awayScore: 0,
    minute: 0,
    queue: [],
    processing: false,
    anim: null,
    lastGoalEvent: null,
    loaded: false,
    playerStats: new Map(),
    pendingVarGoals: []
};

const keyTypes = new Set(['goal', 'penalty', 'varreview', 'shotontarget', 'matchended']);
const eventImages = {
    goal: '/images/goal-gol.gif',
    penalty: '/images/penalty.jpg',
    varreview: '/images/var.jpg',
    shotontarget: '/images/shot.jpg',
    matchended: '/images/match_ended.jpg'
};

const matchEndedImg = new Image();
matchEndedImg.src = '/images/match_ended.jpg';

function normalizeTeamName(value) {
    return (value || '').trim().toLowerCase();
}

function parseScore(value) {
    if (!value || typeof value !== 'string') return null;
    const match = value.match(/(\d+)\s*[:\-]\s*(\d+)/);
    if (!match) return null;
    return { home: Number(match[1]), away: Number(match[2]) };
}

function updateScoreboard() {
    scoreboardEl.textContent = `[${state.minute}'] ${state.homeTeamName} ${state.homeScore} - ${state.awayScore} ${state.awayTeamName}`;
    document.title = `${state.homeTeamName} ${state.homeScore} - ${state.awayScore} ${state.awayTeamName}`;
}

function updateMinute(minute) {
    const parsedMinute = Number(minute) || 0;
    if (parsedMinute > state.minute) {
        state.minute = parsedMinute;
        updateScoreboard();
    }
}

function updateTeamNames(ev) {
    if (ev.homeTeamName) state.homeTeamName = ev.homeTeamName;
    if (ev.awayTeamName) state.awayTeamName = ev.awayTeamName;
    if (ev.homeTeam) state.homeTeamName = ev.homeTeam;
    if (ev.awayTeam) state.awayTeamName = ev.awayTeam;
    updateScoreboard();
}

function applyScoreFromEvent(ev) {
    const type = (ev.type || '').toLowerCase();
    const parsed = parseScore(ev.scoreAfterGoal);
    if (parsed) {
        state.homeScore = parsed.home;
        state.awayScore = parsed.away;
        updateScoreboard();
        return;
    }

    if (Number.isFinite(ev.homeGoals) && Number.isFinite(ev.awayGoals)) {
        state.homeScore = ev.homeGoals;
        state.awayScore = ev.awayGoals;
        updateScoreboard();
        return;
    }

    if (type === 'goal') {
        const eventTeam = normalizeTeamName(ev.teamName);
        if (eventTeam && eventTeam === normalizeTeamName(state.homeTeamName)) {
            state.homeScore += 1;
        } else if (eventTeam && eventTeam === normalizeTeamName(state.awayTeamName)) {
            state.awayScore += 1;
        }
        updateScoreboard();
        return;
    }

    if (type === 'varreview' &&
        (ev.decision || '').toLowerCase() === 'overturned' &&
        (ev.reviewTarget || '').toLowerCase() === 'goal') {
        const eventTeam = normalizeTeamName(ev.teamName);
        if (eventTeam && eventTeam === normalizeTeamName(state.homeTeamName)) {
            state.homeScore = Math.max(0, state.homeScore - 1);
        } else if (eventTeam && eventTeam === normalizeTeamName(state.awayTeamName)) {
            state.awayScore = Math.max(0, state.awayScore - 1);
        }
        updateScoreboard();
    }
}

function appendFeed(text, isKey) {
    const item = document.createElement('div');
    item.className = `feed-item${isKey ? ' key' : ''}`;
    item.textContent = text;
    feedEl.prepend(item);
    while (feedEl.children.length > 24) {
        feedEl.removeChild(feedEl.lastChild);
    }
}

function getPlayerName(ev) {
    return ev.playerName || ev.scorerName || ev.takerName || ev.goalkeeperName || '';
}

function playerKey(name) {
    return (name || '').trim().toLowerCase();
}

function upsertPlayerSnapshot(ev, name) {
    if (!name) return;
    const key = playerKey(name);
    const existing = state.playerStats.get(key) || {
        goals: 0,
        assists: 0,
        age: '?',
        height: '?',
        weight: '?'
    };

    const merged = {
        goals: Number.isFinite(ev.playerTotalGoals) ? ev.playerTotalGoals : existing.goals,
        assists: Number.isFinite(ev.playerTotalAssists) ? ev.playerTotalAssists : existing.assists,
        age: ev.playerAge ?? existing.age,
        height: ev.playerHeight ? `${Math.round(ev.playerHeight * 100)} cm` : existing.height,
        weight: ev.playerWeight ? `${ev.playerWeight} kg` : existing.weight
    };
    state.playerStats.set(key, merged);
}

function incrementPlayerGoal(name, delta) {
    if (!name) return;
    const key = playerKey(name);
    const existing = state.playerStats.get(key) || {
        goals: 0,
        assists: 0,
        age: '?',
        height: '?',
        weight: '?'
    };
    existing.goals = Math.max(0, Number(existing.goals || 0) + delta);
    state.playerStats.set(key, existing);
}

function incrementPlayerAssist(name, delta) {
    if (!name) return;
    const key = playerKey(name);
    const existing = state.playerStats.get(key) || {
        goals: 0,
        assists: 0,
        age: '?',
        height: '?',
        weight: '?'
    };
    existing.assists = Math.max(0, Number(existing.assists || 0) + delta);
    state.playerStats.set(key, existing);
}

function resolveTeamColor(teamName) {
    const team = normalizeTeamName(teamName);
    if (team && team === normalizeTeamName(state.awayTeamName)) {
        return '#d13d3d';
    }
    return '#2038c9';
}

function buildText(ev) {
    const type = (ev.type || '').toLowerCase();
    const min = ev.minute || '?';
    const player = getPlayerName(ev);
    switch (type) {
        case 'goal':
            if (ev.scored === false) return `[${min}'] Goal event: ${player} (pending VAR)`;
            return `[${min}'] GOAL: ${player}${ev.assistantName ? ` (assist: ${ev.assistantName})` : ''}`;
        case 'penalty':
            return `[${min}'] Penalty: ${ev.takerName || player} (${ev.scored ? 'scored' : 'missed'})`;
        case 'shotontarget':
            return `[${min}'] Shot on target: ${player}`;
        case 'varreview':
            return `[${min}'] VAR ${String(ev.decision || 'pending').toUpperCase()} - ${ev.reviewTarget || 'incident'}${ev.overturnReason ? ` (${ev.overturnReason})` : ''}`;
        case 'matchstarted':
            return `[${min}'] Match started`;
        case 'matchended':
            return `[${min}'] Match ended`;
        default:
            return `[${min}'] ${type || 'event'}`;
    }
}

function candidatePlayerImageUrls(name) {
    const trimmed = (name || '').trim();
    if (!trimmed) return [];
    const noDiacritics = trimmed.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
    const variants = new Set([
        trimmed,
        noDiacritics,
        trimmed.replace(/\s+/g, '_'),
        noDiacritics.replace(/\s+/g, '_')
    ]);
    return [...variants].map(v => `/images/${v}.jpg`);
}

function setPlayerImage(name) {
    const urls = candidatePlayerImageUrls(name);
    if (urls.length === 0) {
        playerImage.src = '/images/player.jpg';
        return;
    }

    let index = 0;
    playerImage.src = urls[index];
    playerImage.onerror = () => {
        index += 1;
        if (index < urls.length) {
            playerImage.src = urls[index];
            return;
        }
        playerImage.onerror = null;
        playerImage.src = '/images/player.jpg';
    };
}

function updatePanel(ev) {
    const playerName = getPlayerName(ev);
    const type = (ev.type || '').toLowerCase();
    eventImage.src = eventImages[type] || '/images/shot.jpg';

    if (type === 'matchended') {
        playerImage.src = '/images/match_ended.jpg';
        playerInfo.innerHTML = `
            <strong>Full Time</strong><br>
            ${state.homeTeamName} ${state.homeScore} - ${state.awayScore} ${state.awayTeamName}
        `;
        return;
    }

    setPlayerImage(playerName);
    upsertPlayerSnapshot(ev, playerName);

    if (!playerName) {
        playerInfo.innerHTML = '<strong>No player details for this event.</strong>';
        return;
    }

    const snapshot = state.playerStats.get(playerKey(playerName)) || {
        goals: Number.isFinite(ev.playerTotalGoals) ? ev.playerTotalGoals : 0,
        assists: Number.isFinite(ev.playerTotalAssists) ? ev.playerTotalAssists : 0,
        age: ev.playerAge ?? '?',
        height: ev.playerHeight ? `${Math.round(ev.playerHeight * 100)} cm` : '?',
        weight: ev.playerWeight ? `${ev.playerWeight} kg` : '?'
    };

    playerInfo.innerHTML = `
        <strong>${playerName}</strong><br>
        Team: ${ev.teamName || 'N/A'}<br>
        Age: ${snapshot.age}<br>
        Height: ${snapshot.height}<br>
        Weight: ${snapshot.weight}<br>
        Goals: ${snapshot.goals}<br>
        Assists: ${snapshot.assists}
    `;
}

function findAttackingDirection(teamName) {
    const team = normalizeTeamName(teamName);
    if (team && team === normalizeTeamName(state.homeTeamName)) return 1;
    if (team && team === normalizeTeamName(state.awayTeamName)) return -1;
    return 1;
}

function startAnimation(type, ev) {
    const scene = getScene();
    const targetY = scene.goalCenterY + (Math.random() - 0.5) * 88;
    const keeperY = scene.goalCenterY + (Math.random() - 0.5) * 16;
    const isScored = ev.scored !== false;

    let finalX = scene.goalMouthX;
    let finalY = targetY;
    let reboundY = keeperY;
    if (type === 'penalty' && !isScored) {
        // Miss/saved penalty stays near GK or moves slightly wide.
        finalX = scene.gkX - 6;
        finalY = keeperY + (Math.random() - 0.5) * 24;
    }
    if (type === 'shotontarget') {
        finalX = scene.gkX - 10;
        finalY = keeperY;
        reboundY = keeperY + (Math.random() - 0.5) * 44;
    }

    let shotX = scene.shooterX;
    if (type === 'shotontarget' && Math.random() < 0.45) {
        shotX = scene.penaltySpotX - 75; // around 16m distance
    }

    state.anim = {
        type,
        event: ev,
        startTs: performance.now(),
        duration: type === 'varreview' ? 2600 : 2200,
        direction: findAttackingDirection(ev.teamName),
        shotX,
        shotY: scene.shotMinY + Math.random() * (scene.shotMaxY - scene.shotMinY),
        targetY,
        keeperY,
        finalX,
        finalY,
        reboundY
    };
}

function processQueue() {
    if (state.processing || state.queue.length === 0) return;
    const ev = state.queue.shift();
    if (!ev || !ev.type) {
        setTimeout(processQueue, 0);
        return;
    }

    const type = ev.type.toLowerCase();
    updateTeamNames(ev);
    updateMinute(ev.minute);
    applyScoreFromEvent(ev);

    if (type === 'goal') {
        const scorer = ev.scorerName || ev.playerName;
        const assistant = ev.assistantName;
        upsertPlayerSnapshot(ev, scorer);
        upsertPlayerSnapshot(ev, assistant);
        incrementPlayerGoal(scorer, 1);
        if (assistant) incrementPlayerAssist(assistant, 1);
        state.pendingVarGoals.push({
            minute: ev.minute,
            scorer: scorer || '',
            assistant: assistant || '',
            teamName: ev.teamName || '',
            scoreAfterGoal: ev.scoreAfterGoal || ''
        });
    }

    if (type === 'varreview' && String(ev.decision || '').toLowerCase() === 'overturned' && String(ev.reviewTarget || '').toLowerCase() === 'goal') {
        const reviewedPlayer = ev.playerName || '';
        let removed = null;
        for (let i = state.pendingVarGoals.length - 1; i >= 0; i -= 1) {
            const candidate = state.pendingVarGoals[i];
            if (!reviewedPlayer || playerKey(candidate.scorer) === playerKey(reviewedPlayer)) {
                removed = candidate;
                state.pendingVarGoals.splice(i, 1);
                break;
            }
        }

        const scorer = removed?.scorer || reviewedPlayer;
        const assistant = removed?.assistant || '';
        incrementPlayerGoal(scorer, -1);
        if (assistant) incrementPlayerAssist(assistant, -1);
    }

    const text = buildText(ev);
    lastEventBox.textContent = text;
    appendFeed(`${text} | Score: ${state.homeScore}-${state.awayScore}`, true);
    updatePanel(ev);

    if (type === 'goal') state.lastGoalEvent = ev;

    if (type === 'varreview' && !ev.teamName && state.lastGoalEvent?.teamName) {
        ev.teamName = state.lastGoalEvent.teamName;
    }

    startAnimation(type, ev);
    state.processing = true;
    setTimeout(() => {
        state.processing = false;
        processQueue();
    }, type === 'varreview' ? 2700 : type === 'matchended' ? 2500 : 2300);
}

function drawPitch() {
    const w = canvas.width;
    const h = canvas.height;
    const scene = getScene();
    ctx.fillStyle = '#12271d';
    ctx.fillRect(0, 0, w, h);

    // Right-half "30m to goal" zone only.
    ctx.fillStyle = '#1b6e37';
    ctx.fillRect(scene.zoneX, scene.zoneY, scene.zoneW, scene.zoneH);

    ctx.strokeStyle = '#d8f8e3';
    ctx.lineWidth = 3;
    ctx.strokeRect(scene.zoneX, scene.zoneY, scene.zoneW, scene.zoneH);

    // Penalty box + six-yard box at attacking goal (right side)
    ctx.strokeRect(scene.penBoxX, scene.penBoxY, scene.penBoxW, scene.penBoxH);
    ctx.strokeRect(scene.sixBoxX, scene.sixBoxY, scene.sixBoxW, scene.sixBoxH);

    // Goal frame
    ctx.fillStyle = '#f1f4f2';
    ctx.fillRect(scene.goalFrameX, scene.goalFrameY, scene.goalFrameW, scene.goalFrameH);

    // Penalty spot (no smile arc).
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.arc(scene.penaltySpotX, scene.goalCenterY, 4, 0, Math.PI * 2);
    ctx.fill();
}

function drawPlayer(x, y, color, radius = 12) {
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(x, y, radius, 0, Math.PI * 2);
    ctx.fill();
}

function drawBall(x, y, radius = 8) {
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.arc(x, y, radius, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#222';
    ctx.lineWidth = 1.2;
    ctx.stroke();
}

function drawGoalAnimation(anim, progress) {
    const scene = getScene();
    const shooterX = anim.shotX ?? scene.shooterX;
    const keeperX = scene.gkX;
    const shooterY = anim.shotY;
    const keeperY = anim.keeperY;
    const goalY = anim.targetY;

    drawPlayer(shooterX, shooterY, resolveTeamColor(anim.event.teamName), 13);
    drawPlayer(keeperX, keeperY, '#f0ad15', 14);

    const ballX = shooterX + (scene.goalMouthX - shooterX) * progress;
    const ballY = shooterY + (goalY - shooterY) * progress;
    drawBall(ballX, ballY, 8);

    if ((anim.event.scored !== false) && progress > 0.84) {
        ctx.fillStyle = 'rgba(255,255,255,0.13)';
        ctx.fillRect(scene.netGlowX, scene.netGlowY, scene.netGlowW, scene.netGlowH);
    }
}

function drawPenaltyAnimation(anim, progress) {
    const scene = getScene();
    const spotX = scene.penaltySpotX;
    const spotY = scene.goalCenterY;
    const keeperX = scene.gkX;

    drawPlayer(spotX, spotY, resolveTeamColor(anim.event.teamName), 13);
    drawPlayer(keeperX, anim.keeperY, '#f0ad15', 14);

    const ballX = spotX + (anim.finalX - spotX) * progress;
    const ballY = spotY + (anim.finalY - spotY) * progress;
    drawBall(ballX, ballY, 8);

    if (anim.event.scored && progress > 0.86) {
        ctx.fillStyle = 'rgba(255,255,255,0.13)';
        ctx.fillRect(scene.netGlowX, scene.netGlowY, scene.netGlowW, scene.netGlowH);
    }
}

function drawVarAnimation(anim, progress) {
    const w = canvas.width;
    const h = canvas.height;

    ctx.fillStyle = 'rgba(8, 16, 28, 0.72)';
    ctx.fillRect(0, 0, w, h);

    const cardW = 520;
    const cardH = 230;
    const cardX = (w - cardW) / 2;
    const cardY = (h - cardH) / 2;
    drawRoundedRect(cardX, cardY, cardW, cardH, 14, '#0f2038', '#3f5f90');

    drawCameraIcon(cardX + 72, cardY + 70);

    ctx.textAlign = 'left';
    ctx.fillStyle = '#d9f2ff';
    ctx.font = '700 31px Arial';
    if (progress < 0.62) {
        const dots = '.'.repeat((Math.floor(progress * 10) % 3) + 1);
        ctx.fillText(`VAR CHECK${dots}`, cardX + 142, cardY + 96);
        ctx.fillStyle = '#9cb8d8';
        ctx.font = '600 20px Arial';
        ctx.fillText('Reviewing goal situation...', cardX + 142, cardY + 132);
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

function drawMatchEndedAnimation() {
    const w = canvas.width;
    const h = canvas.height;

    ctx.fillStyle = 'rgba(8, 12, 20, 0.66)';
    ctx.fillRect(0, 0, w, h);

    const cardW = 460;
    const cardH = 250;
    const cardX = Math.floor((w - cardW) / 2);
    const cardY = Math.floor((h - cardH) / 2);
    drawRoundedRect(cardX, cardY, cardW, cardH, 14, '#111b2a', '#3e5f86');

    if (matchEndedImg.complete) {
        ctx.drawImage(matchEndedImg, cardX + 20, cardY + 20, 180, 120);
    }

    ctx.fillStyle = '#d9f2ff';
    ctx.font = '700 36px Arial';
    ctx.fillText('FULL TIME', cardX + 220, cardY + 92);
    ctx.font = '600 24px Arial';
    ctx.fillStyle = '#ffffff';
    ctx.fillText(`${state.homeTeamName} ${state.homeScore} - ${state.awayScore} ${state.awayTeamName}`, cardX + 28, cardY + 190);
}

function getScene() {
    const w = canvas.width;
    const h = canvas.height;
    const zoneX = Math.floor(w * 0.52);
    const zoneY = 50;
    const zoneW = w - zoneX - 60;
    const zoneH = h - 100;
    const goalCenterY = Math.floor(h / 2);

    return {
        zoneX,
        zoneY,
        zoneW,
        zoneH,
        goalCenterY,
        goalMouthX: w - 66,
        goalFrameX: w - 74,
        goalFrameY: goalCenterY - 78,
        goalFrameW: 12,
        goalFrameH: 156,
        penBoxX: w - 330,
        penBoxY: goalCenterY - 130,
        penBoxW: 240,
        penBoxH: 260,
        sixBoxX: w - 160,
        sixBoxY: goalCenterY - 58,
        sixBoxW: 70,
        sixBoxH: 116,
        penaltySpotX: w - 230,
        shooterX: zoneX + Math.floor(zoneW * 0.35),
        gkX: w - 106,
        shotMinY: goalCenterY - 132,
        shotMaxY: goalCenterY + 132,
        netGlowX: w - 86,
        netGlowY: goalCenterY - 78,
        netGlowW: 30,
        netGlowH: 156
    };
}

function drawShotOnTargetAnimation(anim, progress) {
    const scene = getScene();
    const shooterX = anim.shotX ?? scene.shooterX;
    const shooterY = anim.shotY;
    const keeperX = scene.gkX;
    const keeperY = anim.keeperY;
    const impactX = keeperX - 10;
    const impactY = keeperY;
    const reboundX = keeperX - 58;
    const reboundY = anim.reboundY;

    drawPlayer(shooterX, shooterY, resolveTeamColor(anim.event.teamName), 13);
    drawPlayer(keeperX, keeperY, '#f0ad15', 14);

    let ballX;
    let ballY;
    if (progress <= 0.72) {
        const p = progress / 0.72;
        ballX = shooterX + (impactX - shooterX) * p;
        ballY = shooterY + (impactY - shooterY) * p;
    } else {
        const p = (progress - 0.72) / 0.28;
        ballX = impactX + (reboundX - impactX) * p;
        ballY = impactY + (reboundY - impactY) * p;
    }

    drawBall(ballX, ballY, 8);
}

function drawRoundedRect(x, y, w, h, r, fill, stroke) {
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

function drawCameraIcon(cx, cy) {
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

function render(ts) {
    drawPitch();

    const anim = state.anim;
    if (anim) {
        const progress = Math.min(1, (ts - anim.startTs) / anim.duration);
        if (anim.type === 'goal') drawGoalAnimation(anim, progress);
        if (anim.type === 'penalty') drawPenaltyAnimation(anim, progress);
        if (anim.type === 'shotontarget') drawShotOnTargetAnimation(anim, progress);
        if (anim.type === 'varreview') drawVarAnimation(anim, progress);
        if (anim.type === 'matchended') drawMatchEndedAnimation();
        if (progress >= 1) {
            state.anim = null;
        }
    }

    requestAnimationFrame(render);
}

async function hydrateTeamsFromApi() {
    if (!matchId) return;
    try {
        const response = await authFetch(`/matches/${matchId}`);
        if (!response.ok) return;
        const data = await response.json();
        if (data.homeTeam) state.homeTeamName = data.homeTeam;
        if (data.awayTeam) state.awayTeamName = data.awayTeam;
        updateScoreboard();
    } catch (error) {
        console.warn('Unable to fetch match names for key events view', error);
    }
}

async function fetchKeyEvents() {
    const response = await authFetch(`/matches/${matchId}/key-events`);
    if (!response.ok) {
        return null;
    }
    return response.json();
}

async function pollAndLoadHighlights() {
    if (!matchId) {
        lastEventBox.textContent = 'Missing matchId';
        return;
    }

    lastEventBox.textContent = 'Preparing key events...';

    const maxAttempts = 40;
    for (let i = 0; i < maxAttempts; i += 1) {
        const keyEvents = await fetchKeyEvents();
        if (Array.isArray(keyEvents) && keyEvents.length > 0) {
            state.queue = keyEvents;
            state.loaded = true;
            lastEventBox.textContent = 'Highlights loaded';
            processQueue();
            return;
        }

        // If match result exists and there are no key events, stop polling gracefully.
        try {
            const matchResponse = await authFetch(`/matches/${matchId}`);
            if (matchResponse.ok) {
                const match = await matchResponse.json();
                const hasFinalScore = Number.isFinite(match?.homeGoals) && Number.isFinite(match?.awayGoals);
                if (hasFinalScore) {
                    state.homeScore = match.homeGoals;
                    state.awayScore = match.awayGoals;
                    updateScoreboard();
                    state.loaded = true;
                    lastEventBox.textContent = 'No key events in this match';
                    return;
                }
            }
        } catch (error) {
            console.warn('Match poll failed in key events view', error);
        }

        await new Promise(resolve => setTimeout(resolve, 600));
    }

    lastEventBox.textContent = 'No key events available yet';
}

function setupBackButton() {
    document.getElementById('backBtn').addEventListener('click', () => {
        window.location.href = '/dashboard.html';
    });
}

function init() {
    updateScoreboard();
    setupBackButton();
    hydrateTeamsFromApi();
    pollAndLoadHighlights();
    requestAnimationFrame(render);
}

init();
