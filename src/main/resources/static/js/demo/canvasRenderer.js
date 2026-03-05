// canvasRenderer.js
let canvas, ctx;
let animationFrameId = null;
const FIELD = { offsetX: 50, offsetY: 50, width: 800, height: 400 };
const MOVE_DURATION = 560;
const GOAL_SHOT_DURATION = 1000;
const GOAL_RESET_DURATION = 1100;

let players = {};
let ball = { x: 50, y: 50, startX: 50, startY: 50, targetX: 50, targetY: 50, moveStartTime: performance.now() };
let currentMinute = 0;
let scriptedBallAnimation = null;
let suppressIncomingBallUntil = 0;

export function initCanvas() {
    canvas = document.getElementById('pitch');
    if (!canvas) {
        console.error('Canvas #pitch was not found');
        return;
    }
    ctx = canvas.getContext('2d');
    console.log('Canvas initialized - starting render loop');
    loop();
}

function loop() {
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
        console.log('Canvas loop stopped');
    }
}

export function setCurrentMinute(min) {
    currentMinute = min;
}

export function updatePositionsData(data) {
    (data.players || []).forEach(p => {
        if (!players[p.id]) {
            players[p.id] = { ...p, startX: p.x, startY: p.y, targetX: p.x, targetY: p.y, moveStartTime: performance.now() };
        } else {
            const pl = players[p.id];
            pl.startX = pl.x;
            pl.startY = pl.y;
            pl.targetX = p.x;
            pl.targetY = p.y;
            pl.moveStartTime = performance.now();
        }
    });

    if (data.ball && performance.now() >= suppressIncomingBallUntil) {
        ball.startX = ball.x;
        ball.startY = ball.y;
        ball.targetX = data.ball.x;
        ball.targetY = data.ball.y;
        ball.moveStartTime = performance.now();
    }
}

function toCanvasX(x) {
    return FIELD.offsetX + (x / 100) * FIELD.width;
}

function toCanvasY(y) {
    return FIELD.offsetY + (y / 100) * FIELD.height;
}

function updatePositions() {
    const now = performance.now();
    Object.values(players).forEach(p => {
        const progress = Math.min((now - p.moveStartTime) / MOVE_DURATION, 1);
        const eased = 1 - Math.pow(1 - progress, 3);
        p.x = p.startX + (p.targetX - p.startX) * eased;
        p.y = p.startY + (p.targetY - p.startY) * eased;
    });
    if (scriptedBallAnimation) {
        const elapsed = now - scriptedBallAnimation.startTime;
        if (elapsed <= GOAL_SHOT_DURATION) {
            const t = Math.min(1, elapsed / GOAL_SHOT_DURATION);
            ball.x = scriptedBallAnimation.fromX + (scriptedBallAnimation.goalX - scriptedBallAnimation.fromX) * t;
            ball.y = scriptedBallAnimation.fromY + (scriptedBallAnimation.goalY - scriptedBallAnimation.fromY) * t;
        } else {
            const resetElapsed = elapsed - GOAL_SHOT_DURATION;
            if (resetElapsed <= GOAL_RESET_DURATION) {
                const t = Math.min(1, resetElapsed / GOAL_RESET_DURATION);
                ball.x = scriptedBallAnimation.goalX + (50 - scriptedBallAnimation.goalX) * t;
                ball.y = scriptedBallAnimation.goalY + (50 - scriptedBallAnimation.goalY) * t;
            } else {
                scriptedBallAnimation = null;
            }
        }
    } else {
        const bProg = Math.min((now - ball.moveStartTime) / MOVE_DURATION, 1);
        const eased = 1 - Math.pow(1 - bProg, 3);
        ball.x = ball.startX + (ball.targetX - ball.startX) * eased;
        ball.y = ball.startY + (ball.targetY - ball.startY) * eased;
    }
}

function drawField() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 2;

    ctx.strokeRect(FIELD.offsetX, FIELD.offsetY, FIELD.width, FIELD.height);
    ctx.strokeRect(FIELD.offsetX, FIELD.offsetY + 100, 120, 200);
    ctx.strokeRect(FIELD.offsetX + FIELD.width - 120, FIELD.offsetY + 100, 120, 200);
    ctx.strokeRect(FIELD.offsetX, FIELD.offsetY + 150, 60, 100);
    ctx.strokeRect(FIELD.offsetX + FIELD.width - 60, FIELD.offsetY + 150, 60, 100);

    ctx.fillStyle = '#fff';
    ctx.fillRect(FIELD.offsetX - 10, FIELD.offsetY + 180, 10, 40);
    ctx.fillRect(FIELD.offsetX + FIELD.width, FIELD.offsetY + 180, 10, 40);

    ctx.beginPath();
    ctx.moveTo(FIELD.offsetX + FIELD.width / 2, FIELD.offsetY);
    ctx.lineTo(FIELD.offsetX + FIELD.width / 2, FIELD.offsetY + FIELD.height);
    ctx.stroke();

    ctx.beginPath();
    ctx.arc(FIELD.offsetX + FIELD.width / 2, FIELD.offsetY + FIELD.height / 2, 60, 0, Math.PI * 2);
    ctx.stroke();
}

function drawPlayers() {
    Object.values(players).forEach(p => {
        const x = toCanvasX(p.x);
        const y = toCanvasY(p.y);
        ctx.beginPath();
        ctx.fillStyle = p.team === 'HOME' ? '#0066ff' : '#ff3333';
        ctx.arc(x, y, 12, 0, Math.PI * 2);
        ctx.fill();

        ctx.fillStyle = '#fff';
        ctx.font = '11px Arial';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(p.id, x, y);
    });
}

function drawBall() {
    const x = toCanvasX(ball.x);
    const y = toCanvasY(ball.y);
    ctx.beginPath();
    ctx.fillStyle = '#fff';
    ctx.arc(x, y, 6, 0, Math.PI * 2);
    ctx.fill();
}

function drawClock(minute) {
    ctx.fillStyle = '#fff';
    ctx.font = '18px Arial';
    ctx.textAlign = 'left';
    ctx.fillText(`Minute: ${minute}`, 20, 35);
}

export function triggerGoalAnimation(scoringSide) {
    const goalX = scoringSide === 'AWAY' ? 1 : 99;
    const goalY = 50;

    scriptedBallAnimation = {
        startTime: performance.now(),
        fromX: ball.x,
        fromY: ball.y,
        goalX,
        goalY
    };
    suppressIncomingBallUntil = performance.now() + GOAL_SHOT_DURATION + GOAL_RESET_DURATION + 150;
}
