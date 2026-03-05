// scoreboard.js
import { getQueryParam } from './utils.js';

let homeTeamName = 'Home';
let awayTeamName = 'Away';
let homeScore = 0;
let awayScore = 0;
let currentMinute = 0;

function normalized(value) {
    return (value || '').trim().toLowerCase();
}

function areSameTeam(left, right) {
    return normalized(left) !== '' && normalized(left) === normalized(right);
}

function parseScore(value) {
    if (!value || typeof value !== 'string') return null;
    const match = value.match(/(\d+)\s*[:\-]\s*(\d+)/);
    if (!match) return null;
    return { home: Number(match[1]), away: Number(match[2]) };
}

async function hydrateFromMatchApi() {
    const matchId = getQueryParam('matchId');
    if (!matchId) return;

    const token = localStorage.getItem('token');
    const headers = token ? { Authorization: `Bearer ${token}` } : {};

    try {
        const response = await fetch(`/matches/${matchId}`, { headers });
        if (!response.ok) return;

        const data = await response.json();
        if (data.homeTeam) homeTeamName = data.homeTeam;
        if (data.awayTeam) awayTeamName = data.awayTeam;
        updateDisplay();
    } catch (err) {
        console.warn('Scoreboard fallback /matches/{id} failed:', err);
    }
}

export function initScoreboard() {
    homeTeamName = 'Home';
    awayTeamName = 'Away';
    homeScore = 0;
    awayScore = 0;
    currentMinute = 0;
    updateDisplay();
    hydrateFromMatchApi();
}

export function initTeams(ev) {
    if (!ev) return;
    if (ev.homeTeamName) homeTeamName = ev.homeTeamName;
    if (ev.awayTeamName) awayTeamName = ev.awayTeamName;
    if (ev.homeTeam) homeTeamName = ev.homeTeam;
    if (ev.awayTeam) awayTeamName = ev.awayTeam;
    updateDisplay();
}

export function updateScore(ev) {
    if (!ev) return;
    const type = ev.type?.toLowerCase();

    if (type === 'goal' && ev.scored === false) {
        updateDisplay();
        return;
    }

    const parsedScore = parseScore(ev.scoreAfterGoal);
    if (parsedScore) {
        homeScore = parsedScore.home;
        awayScore = parsedScore.away;
        updateDisplay();
        return;
    }

    if (Number.isFinite(ev.homeGoals) && Number.isFinite(ev.awayGoals)) {
        homeScore = ev.homeGoals;
        awayScore = ev.awayGoals;
        updateDisplay();
        return;
    }

    if (type === 'varreview' &&
        (ev.decision || '').toLowerCase() === 'overturned' &&
        (ev.reviewTarget || '').toLowerCase() === 'goal') {
        if (areSameTeam(ev.teamName, homeTeamName)) {
            homeScore = Math.max(0, homeScore - 1);
        } else if (areSameTeam(ev.teamName, awayTeamName)) {
            awayScore = Math.max(0, awayScore - 1);
        }
        updateDisplay();
        return;
    }

    if (type !== 'goal') return;

    if (areSameTeam(ev.teamName, homeTeamName)) {
        homeScore++;
    } else if (areSameTeam(ev.teamName, awayTeamName)) {
        awayScore++;
    }
    updateDisplay();
}

export function updateMinute(min) {
    if (min > currentMinute) currentMinute = min;
    updateDisplay();
}

export function updateDisplay() {
    const scoreboard = document.getElementById('scoreboard');
    if (!scoreboard) return;
    scoreboard.textContent = `[${currentMinute}'] ${homeTeamName} ${homeScore} - ${awayScore} ${awayTeamName}`;
    document.title = `${homeTeamName} ${homeScore} - ${awayScore} ${awayTeamName}`;
}

export function getHomeTeamName() {
    return homeTeamName;
}

export function getAwayTeamName() {
    return awayTeamName;
}

window.initScoreboard = initScoreboard;
window.initTeams = initTeams;
window.updateScore = updateScore;
window.updateMinute = updateMinute;
window.updateDisplay = updateDisplay;
