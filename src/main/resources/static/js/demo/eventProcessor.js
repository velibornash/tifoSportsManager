// eventProcessor.js
import { updateScore, updateMinute, initTeams } from './scoreboard.js';
import { setCurrentMinute } from './canvasRenderer.js';

let eventQueue = [];
let isProcessing = false;
const EVENT_DELAY = 2200;
const GOAL_DELAY  = 2800;

let lastEventBox  = document.getElementById('lastEventBox');
let eventImage    = document.getElementById('eventImage');
let playerImage   = document.getElementById('playerImage');
let playerInfo    = document.getElementById('playerInfo');

const images = {
    goal: "/images/goal-gol.gif",
    chance: "/images/shot.jpg",
    shotOnTarget: "/images/shot.jpg",
    shotOffTarget: "/images/shot.jpg",
    matchStarted: "/images/match_starting.jpg",
    matchEnded: "/images/match_ended.jpg",
    penalty: "/images/penalty.jpg",
    offside: "/images/offside_flag.jpg",
    corner: "/images/corner.jpg",
    freeKick: "/images/free_kick.png",
    injury: "/images/injury.jpg",
    yellowCard: "/images/yellowcard.jpg",
    redCard: "/images/redcard.png",
    varReview: "/images/var.jpg",
    substitution: "/images/substitution.jpg"
};

export function initEventProcessor(){}

export function enqueueEvent(ev){
    eventQueue.push(ev);
    processNext();
}

function processNext(){
    if(isProcessing || eventQueue.length===0) return;
    isProcessing=true;

    const ev = eventQueue.shift();
    if(!ev?.type){ isProcessing=false; processNext(); return; }

    initTeams(ev);
    updateMinute(ev.minute || 0);
    setCurrentMinute(ev.minute || 0);

    // ───── Update score i goal increment ─────
    let goalIncrement = 0;
    if(ev.type?.toLowerCase() === 'goal'){
        updateScore(ev);  // ovo već povećava scoreboard
        goalIncrement = 1; // koristi za prikaz u playerInfo
    }

    lastEventBox.textContent = buildLastEventText(ev);
    updateEventImage(ev);
    updatePlayerInfo(ev, goalIncrement); // ovde prosleđujemo goalIncrement

    const delay = ev.type.toLowerCase()==='goal' ? GOAL_DELAY : EVENT_DELAY;
    setTimeout(()=>{
        isProcessing=false;
        processNext();
    }, delay);
}


function getPlayerName(ev){
    return ev.playerName || ev.scorerName || ev.takerName || ev.playerOutName || ev.playerInName || "";
}

function updatePlayerInfo(ev, goalIncrement=0){
    const name = getPlayerName(ev);
    if(!name){ playerInfo.innerHTML=''; return; }

    const oldGoals = ev.playerTotalGoals || 0;
    const newGoals = oldGoals + goalIncrement;

    // Prvo prikaži stari broj golova
    playerInfo.innerHTML = `
        <strong>${name}</strong><br>
        📏 Visina: ${ev.playerHeight ? Math.round(ev.playerHeight*100)+' cm':'?'}<br>
        ⚖️ Težina: ${ev.playerWeight ? ev.playerWeight+' kg':'?'}<br>
        🎂 Godine: ${ev.playerAge||'?'}<br>
        🥅 Golovi: ${oldGoals}<span id="goalBlink"></span>
    `;

    // load player image
/*    const file = name.replace(/\s+/g,'_')+'.jpg';
    playerImage.src = `/images/${file}`;
    playerImage.style.display='block';*/

    if(goalIncrement>0){
        // Delay da se vidi stari broj, pa blink i update na +1
        setTimeout(()=>{
            const span = document.getElementById('goalBlink');
            if(span){
                span.textContent = ' → ' + newGoals;
                span.classList.add('blink');
                // ukloni klasu posle 1s da može da se ponovi
                setTimeout(()=> span.classList.remove('blink'), 1000);
            }
        }, 800); // ~0.8s delay da se prvo vidi stari broj
    }
}


function updateEventImage(ev){
    const t = ev.type || '';
    eventImage.src = images[t]||'';
    eventImage.style.display = images[t] ? 'block':'none';
}

function buildLastEventText(ev){
    let symbol='📩';
    const type=(ev.type||'').toLowerCase();
    const min=ev.minute||'?';
    const player=getPlayerName(ev);

    switch(type){
        case 'goal': symbol='⚽'; return `${symbol} [${min}'] GOOOOL! ${player}`;
        case 'chance': symbol='⚡'; return `${symbol} [${min}'] Posed! ${player}`;
        case 'shotontarget':
        case 'shotofftarget': symbol='🎯'; return `${symbol} [${min}'] Šut ${player}`;
        case 'matchstarted': symbol='🏁'; return `${symbol} [${min}'] Početak meča!`;
        case 'matchended': symbol='🏁'; return `${symbol} [${min}'] Kraj meča`;
        case 'penalty': symbol='⚽'; return `${symbol} [${min}'] Penal! ${player}`;
        case 'offside': symbol='🚩'; return `${symbol} [${min}'] Ofsajd ${player}`;
        case 'corner': symbol='⛳'; return `${symbol} [${min}'] Korner ${player}`;
        case 'freekick': symbol='⚽'; return `${symbol} [${min}'] Slobodnjak ${player}`;
        case 'injury': symbol='❌'; return `${symbol} [${min}'] Povreda ${player}`;
        case 'yellowcard': symbol='🟨'; return `${symbol} [${min}'] Žuti ${player}`;
        case 'redcard': symbol='🟥'; return `${symbol} [${min}'] Crveni ${player}`;
        default: return `${symbol} [${min}'] ${type} ${player?' - '+player:''}`;
    }
}
