// canvasRenderer.js
let canvas, ctx;
let animationFrameId = null; // globalno za cleanup
const FIELD = { offsetX: 50, offsetY: 50, width: 800, height: 400 };
const MOVE_DURATION = 320;

let players = {};
let ball = { x:50,y:50,startX:50,startY:50,targetX:50,targetY:50,moveStartTime:performance.now() };
let currentMinute = 0;

export function initCanvas() {
    canvas = document.getElementById('pitch');
    if (!canvas) {
        console.error("Canvas #pitch nije pronađen!");
        return;
    }
    ctx = canvas.getContext('2d');
    console.log("Canvas inicijalizovan – pokrećem loop");
    loop(); // START ANIMACIJE ODMAH
}

function loop(){
    updatePositions();
    drawField();
    drawPlayers();
    drawBall();
    drawClock(currentMinute);
    animationFrameId = requestAnimationFrame(loop);
}

export function stopCanvasLoop() {
    if (animationFrameId) {
        cancelAnimationFrame(animationFrameId);
        animationFrameId = null;
        console.log("Canvas loop zaustavljen");
    }
}

export function setCurrentMinute(min) { currentMinute = min; }

export function updatePositionsData(data) {
    (data.players || []).forEach(p => {
        if (!players[p.id]) {
            players[p.id] = { ...p, startX:p.x, startY:p.y, targetX:p.x, targetY:p.y, moveStartTime:performance.now() };
        } else {
            let pl = players[p.id];
            pl.startX = pl.x; pl.startY = pl.y;
            pl.targetX = p.x; pl.targetY = p.y;
            pl.moveStartTime = performance.now();
        }
    });

    if (data.ball) {
        ball.startX = ball.x; ball.startY = ball.y;
        ball.targetX = data.ball.x; ball.targetY = data.ball.y;
        ball.moveStartTime = performance.now();
    }
}

function toCanvasX(x){ return FIELD.offsetX + (x/100)*FIELD.width; }
function toCanvasY(y){ return FIELD.offsetY + (y/100)*FIELD.height; }

function updatePositions(){
    const now = performance.now();
    Object.values(players).forEach(p=>{
        const progress = Math.min((now - p.moveStartTime)/MOVE_DURATION,1);
        p.x = p.startX + (p.targetX - p.startX)*progress;
        p.y = p.startY + (p.targetY - p.startY)*progress;
    });
    const bProg = Math.min((now - ball.moveStartTime)/MOVE_DURATION,1);
    ball.x = ball.startX + (ball.targetX - ball.startX)*bProg;
    ball.y = ball.startY + (ball.targetY - ball.startY)*bProg;
}

function drawField(){
    ctx.clearRect(0,0,canvas.width,canvas.height);
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 2;

    // Vanjski okvir
    ctx.strokeRect(FIELD.offsetX, FIELD.offsetY, FIELD.width, FIELD.height);

    // 16m linije
    ctx.strokeRect(FIELD.offsetX, FIELD.offsetY + 100, 120, 200);
    ctx.strokeRect(FIELD.offsetX + FIELD.width - 120, FIELD.offsetY + 100, 120, 200);

    // Peterac
    ctx.strokeRect(FIELD.offsetX, FIELD.offsetY + 150, 60, 100);
    ctx.strokeRect(FIELD.offsetX + FIELD.width - 60, FIELD.offsetY + 150, 60, 100);

    // Gol linije
    ctx.fillStyle = '#fff';
    ctx.fillRect(FIELD.offsetX - 10, FIELD.offsetY + 180, 10, 40);
    ctx.fillRect(FIELD.offsetX + FIELD.width, FIELD.offsetY + 180, 10, 40);

    // Sredina
    ctx.beginPath();
    ctx.moveTo(FIELD.offsetX + FIELD.width/2, FIELD.offsetY);
    ctx.lineTo(FIELD.offsetX + FIELD.width/2, FIELD.offsetY + FIELD.height);
    ctx.stroke();

    ctx.beginPath();
    ctx.arc(FIELD.offsetX + FIELD.width/2, FIELD.offsetY + FIELD.height/2, 60, 0, Math.PI*2);
    ctx.stroke();
}

function drawPlayers(){
    Object.values(players).forEach(p=>{
        const x = toCanvasX(p.x);
        const y = toCanvasY(p.y);
        ctx.beginPath();
        ctx.fillStyle = p.team === 'HOME' ? '#0066ff' : '#ff3333';
        ctx.arc(x, y, 12, 0, Math.PI*2);
        ctx.fill();

        // ID na centru
        ctx.fillStyle = '#fff';
        ctx.font = '11px Arial';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(p.id, x, y);
    });
}

function drawBall(){
    const x = toCanvasX(ball.x);
    const y = toCanvasY(ball.y);
    ctx.beginPath();
    ctx.fillStyle = '#fff';
    ctx.arc(x, y, 6, 0, Math.PI*2);
    ctx.fill();
}

function drawClock(currentMinute){
    ctx.fillStyle = '#fff';
    ctx.font = '18px Arial';
    ctx.textAlign = 'left';
    ctx.fillText(`Minute: ${currentMinute}`, 20, 35);
}




