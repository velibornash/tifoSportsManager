// eventProcessor.js
import { updateScore, updateMinute, initTeams, getHomeTeamName, getAwayTeamName } from './scoreboard.js';
import { setCurrentMinute, triggerGoalAnimation } from './canvasRenderer.js';

let eventQueue = [];
let isProcessing = false;
const EVENT_DELAY = 2400;
const GOAL_DELAY = 4200;

let lastEventBox = document.getElementById('lastEventBox');
let eventImage = document.getElementById('eventImage');
let playerImage = document.getElementById('playerImage');
let playerInfo = document.getElementById('playerInfo');

const images = {
    goal: '/images/goal-gol.gif',
    chance: '/images/shot.jpg',
    possession: '/images/shot.jpg',
    shotOnTarget: '/images/shot.jpg',
    shotOffTarget: '/images/shot.jpg',
    matchStarted: '/images/match_starting.jpg',
    matchEnded: '/images/match_ended.jpg',
    penalty: '/images/penalty.jpg',
    offside: '/images/offside_flag.jpg',
    corner: '/images/corner.jpg',
    throwIn: '/images/corner.jpg',
    goalKick: '/images/free_kick.png',
    freeKick: '/images/free_kick.png',
    injury: '/images/injury.jpg',
    yellowCard: '/images/yellowcard.jpg',
    redCard: '/images/redcard.png',
    varReview: '/images/var.jpg',
    substitution: '/images/substitution.jpg'
};

export function initEventProcessor() {}

export function enqueueEvent(ev) {
    const type = (ev?.type || '').toLowerCase();
    if (type === 'possession' && (isProcessing || eventQueue.length > 0)) {
        return;
    }
    eventQueue.push(ev);
    processNext();
}

function processNext() {
    if (!document.getElementById('pitch')) {
        console.log('Page is not active - stopping event processing');
        return;
    }
    if (isProcessing || eventQueue.length === 0) return;
    isProcessing = true;

    const ev = eventQueue.shift();
    if (!ev?.type) {
        isProcessing = false;
        processNext();
        return;
    }

    initTeams(ev);
    updateMinute(ev.minute || 0);
    setCurrentMinute(ev.minute || 0);

    let goalIncrement = 0;
    if (ev.type?.toLowerCase() === 'goal') {
        if (ev.scored !== false) {
            goalIncrement = 1;
            const eventTeam = (ev.teamName || '').trim().toLowerCase();
            const home = (getHomeTeamName() || '').trim().toLowerCase();
            const away = (getAwayTeamName() || '').trim().toLowerCase();
            const side = eventTeam && eventTeam === away ? 'AWAY' : eventTeam === home ? 'HOME' : 'HOME';
            triggerGoalAnimation(side);
        }
    }
    updateScore(ev);

    lastEventBox.textContent = buildLastEventText(ev);
    updateEventImage(ev);
    updatePlayerInfo(ev, goalIncrement);

    const delay = ev.type.toLowerCase() === 'goal' ? GOAL_DELAY : EVENT_DELAY;
    setTimeout(() => {
        isProcessing = false;
        processNext();
    }, delay);
}

function getPlayerName(ev) {
    return ev.playerName || ev.scorerName || ev.takerName || ev.goalkeeperName || ev.playerOutName || ev.playerInName || '';
}

function updatePlayerInfo(ev, goalIncrement = 0) {
    const name = getPlayerName(ev);
    if (!name) {
        playerInfo.innerHTML = '';
        return;
    }

    const oldGoals = ev.playerTotalGoals || 0;
    const newGoals = oldGoals + goalIncrement;
    const assists = ev.playerTotalAssists ?? 0;

    playerInfo.innerHTML = `
        <strong>${name}</strong><br>
        Height: ${ev.playerHeight ? Math.round(ev.playerHeight * 100) + ' cm' : '?'}<br>
        Weight: ${ev.playerWeight ? ev.playerWeight + ' kg' : '?'}<br>
        Age: ${ev.playerAge || '?'}<br>
        Goals: ${oldGoals}<span id="goalBlink"></span><br>
        Assists: ${assists}
    `;

    if (goalIncrement > 0) {
        setTimeout(() => {
            const span = document.getElementById('goalBlink');
            if (span) {
                span.textContent = ' -> ' + newGoals;
                span.classList.add('blink');
                setTimeout(() => span.classList.remove('blink'), 1000);
            }
        }, 800);
    }
}

function updateEventImage(ev) {
    const t = ev.type || '';
    eventImage.src = images[t] || '';
    eventImage.style.display = images[t] ? 'block' : 'none';
}

function buildLastEventText(ev) {
    let symbol = 'INFO';
    const type = (ev.type || '').toLowerCase();
    const min = ev.minute || '?';
    const player = getPlayerName(ev);

    switch (type) {
        case 'goal':
            if (ev.scored === false) {
                return `[${min}'] GOAL DISALLOWED: ${player}`;
            }
            return `[${min}'] GOAL! ${player}`;
        case 'chance': return `[${min}'] Chance for ${player}`;
        case 'possession':
            return `[${min}'] Possession: ${ev.teamName || 'N/A'}${ev.playerName ? ` (${ev.playerName})` : ''}`;
        case 'shotontarget':
        case 'shotofftarget': return `[${min}'] Shot by ${player}`;
        case 'matchstarted': return `[${min}'] Match started`;
        case 'matchended': return `[${min}'] Match finished`;
        case 'penalty': return `[${min}'] Penalty by ${player}`;
        case 'offside': return `[${min}'] Offside ${player}`;
        case 'corner': return `[${min}'] Corner by ${player}`;
        case 'throwin': return `[${min}'] Throw-in by ${player}`;
        case 'goalkick': return `[${min}'] Goal kick by ${player}`;
        case 'freekick': return `[${min}'] Free kick by ${player}`;
        case 'injury': return `[${min}'] Injury: ${player}`;
        case 'yellowcard': return `[${min}'] Yellow card: ${player}`;
        case 'redcard': return `[${min}'] Red card: ${player}`;
        case 'varreview': {
            const decision = (ev.decision || 'Pending').toUpperCase();
            const team = ev.teamName || 'N/A';
            const target = ev.reviewTarget || 'incident';
            const reason = ev.overturnReason ? ` (${ev.overturnReason})` : '';
            return `[${min}'] VAR ${decision}: ${target} - ${team}${reason}`;
        }
        default: return `${symbol} [${min}'] ${type}${player ? ' - ' + player : ''}`;
    }
}
