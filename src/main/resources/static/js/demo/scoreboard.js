// scoreboard.js

let homeTeamName = "Home";   // default placeholder
let awayTeamName = "Away";
let homeScore = 0;
let awayScore = 0;
let currentMinute = 0;

export function initScoreboard() {updateDisplay();}
export function initTeams(ev) {
    // Ovo se poziva kad dođe event sa imenima timova
    if (ev.homeTeamName) homeTeamName = ev.homeTeamName;
    if (ev.awayTeamName) awayTeamName = ev.awayTeamName;
    updateDisplay(); // odmah osveži sa novim imenima
}
export function updateScore(ev) {
    if (ev.type?.toLowerCase() !== 'goal') return;
    if (ev.teamName === homeTeamName) homeScore++;
    else if (ev.teamName === awayTeamName) awayScore++;
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
}

window.initScoreboard = initScoreboard;
window.initTeams = initTeams;
window.updateScore = updateScore;
window.updateMinute = updateMinute;
window.updateDisplay = updateDisplay;