// canvasRenderer.js
let canvas, ctx;
let animationFrameId = null;
const FIELD = { offsetX: 50, offsetY: 50, width: 800, height: 400 };
const RENDER_DELAY_MS = 120;
const POST_BLEND = 0.58;
const GOAL_SHOT_DURATION = 1000;
const GOAL_RESET_DURATION = 1100;

let players = {};
let playerNamesMap = {}; // New: store player names
let ball = { x: 50, y: 50 };
let currentCarrierId = null; // New: store current carrier
let currentMinute = 0;
let scriptedBallAnimation = null;
let suppressIncomingBallUntil = 0;
let frameBuffer = [];

export function setPlayerNames(names) {
    playerNamesMap = names;
}

export function initCanvas() {
    canvas = document.getElementById('pitch');
    if (!canvas) {
        console.error('Canvas #pitch was not found');
        return;
    }
    ctx = canvas.getContext('2d');
    players = {};
    ball = { x: 50, y: 50 };
    frameBuffer = [];
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
    const now = performance.now();
    const playerMap = {};
    (data.players || []).forEach(p => {
        playerMap[p.id] = { id: p.id, team: p.team, x: p.x, y: p.y };
        if (!players[p.id]) {
            players[p.id] = { id: p.id, team: p.team, x: p.x, y: p.y };
        }
    });

    currentCarrierId = data.carrierPlayerId;

    frameBuffer.push({
        at: now,
        players: playerMap,
        ball: data.ball ? { x: data.ball.x, y: data.ball.y } : { x: ball.x, y: ball.y },
        carrierId: data.carrierPlayerId
    });

    while (frameBuffer.length > 12) {
        frameBuffer.shift();
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
    const sampled = sampleState(now - RENDER_DELAY_MS);
    if (sampled) {
        Object.values(sampled.players).forEach(sp => {
            if (!players[sp.id]) {
                players[sp.id] = { id: sp.id, team: sp.team, x: sp.x, y: sp.y };
                return;
            }
            const p = players[sp.id];
            p.team = sp.team;
            p.x += (sp.x - p.x) * POST_BLEND;
            p.y += (sp.y - p.y) * POST_BLEND;
        });
    }

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
    } else if (sampled && now >= suppressIncomingBallUntil) {
        ball.x += (sampled.ball.x - ball.x) * POST_BLEND;
        ball.y += (sampled.ball.y - ball.y) * POST_BLEND;
    }
}

function sampleState(renderAt) {
    if (frameBuffer.length === 0) return null;
    if (frameBuffer.length === 1) return frameBuffer[0];

    while (frameBuffer.length > 2 && frameBuffer[1].at <= renderAt) {
        frameBuffer.shift();
    }

    const a = frameBuffer[0];
    const b = frameBuffer[Math.min(1, frameBuffer.length - 1)];
    if (!b || b.at <= a.at || renderAt <= a.at) {
        return a;
    }
    if (renderAt >= b.at) {
        return b;
    }

    const alpha = Math.max(0, Math.min(1, (renderAt - a.at) / (b.at - a.at)));
    const playersOut = {};

    Object.keys(a.players).forEach(id => {
        const ap = a.players[id];
        const bp = b.players[id] || ap;
        playersOut[id] = {
            id: ap.id,
            team: ap.team,
            x: ap.x + (bp.x - ap.x) * alpha,
            y: ap.y + (bp.y - ap.y) * alpha
        };
    });

    const ballOut = {
        x: a.ball.x + (b.ball.x - a.ball.x) * alpha,
        y: a.ball.y + (b.ball.y - a.ball.y) * alpha
    };

    return { at: renderAt, players: playersOut, ball: ballOut };
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
        
        // Is this player the current carrier?
        const isCarrier = currentCarrierId != null && Number(currentCarrierId) === Number(p.id);

        ctx.beginPath();
        ctx.fillStyle = p.team === 'HOME' ? '#0066ff' : '#ff3333';
        ctx.arc(x, y, 12, 0, Math.PI * 2);
        ctx.fill();

        // Highlight carrier
        if (isCarrier) {
            ctx.strokeStyle = '#fff';
            ctx.lineWidth = 3;
            ctx.stroke();
        }

        ctx.fillStyle = '#fff';
        ctx.font = '11px Arial';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(p.id, x, y);

        // Draw name bubble for carrier
        if (isCarrier) {
            const name = playerNamesMap[p.id] || `Player ${p.id}`;
            ctx.font = 'bold 12px Arial';
            const textWidth = ctx.measureText(name).width;
            
            ctx.fillStyle = 'rgba(0, 0, 0, 0.6)';
            ctx.beginPath();
            ctx.roundRect(x - (textWidth + 10) / 2, y - 35, textWidth + 10, 18, 5);
            ctx.fill();
            
            ctx.fillStyle = '#fff';
            ctx.fillText(name, x, y - 26);
        }
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
