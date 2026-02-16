// scoreboard.js
let homeTeamName="Omladinac";
let awayTeamName="Sremac";
let homeScore=0;
let awayScore=0;
let currentMinute=0;

export function initScoreboard(){ updateDisplay(); }

export function updateScore(ev){
    if(ev.type?.toLowerCase()!=='goal') return;
    if(ev.teamName===homeTeamName) homeScore++;
    else if(ev.teamName===awayTeamName) awayScore++;
    updateDisplay();
}

export function updateMinute(min){
    if(min>currentMinute) currentMinute=min;
    updateDisplay();
}

export function initTeams(ev){
    if(ev.homeTeamName) homeTeamName=ev.homeTeamName;
    if(ev.awayTeamName) awayTeamName=ev.awayTeamName;
}

function updateDisplay(){
    const scoreboard = document.getElementById('scoreboard');
    scoreboard.textContent=`[${currentMinute}'] ${homeTeamName} ${homeScore} - ${awayScore} ${awayTeamName}`;
}
