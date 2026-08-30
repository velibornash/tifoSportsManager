/**
 * TIFO Demo Service — Match Viewer
 *
 * Horizontal pitch: HOME left (row 1), attacks left→right (rows 1→7).
 * AWAY right (row 7), attacks right→left (rows 7→1).
 * Playing field: rows 1-7, cols 1-6. Rows 0,8 + cols 0,7 = out-of-bounds.
 * Smooth interpolated player positions.
 */

/* ═══════════════════════════════════════════════════════════════
   CONSTANTS
   ═══════════════════════════════════════════════════════════════ */
const TICKS_PER_MINUTE = 40;
const HOME_COLOR = '#539bf5';
const AWAY_COLOR = '#f97583';
const GK_COLOR = '#f0b429';
const BALL_COLOR = '#ffffff';
const BALL_SHADOW = 'rgba(0,0,0,.35)';
const PITCH_GREEN = '#3a7d44';
const PITCH_OOB = '#2d6a35';      // out-of-bounds area (darker)
const PITCH_STRIPE = 'rgba(255,255,255,.04)';
const PITCH_LINE = 'rgba(255,255,255,.55)';
// Grid: 9 rows (0-8) x 8 cols (0-7). Playing field = rows 1-7, cols 1-6.
const GRID_ROWS = 9;
const GRID_COLS = 8;
const FIELD_ROW_MIN = 1;
const FIELD_ROW_MAX = 7;
const FIELD_COL_MIN = 1;
const FIELD_COL_MAX = 6;
const CELL_W = 164;  // 126 * 1.3 — extended 30%
const CELL_H = 112;  // 80 * 1.4

/* ═══════════════════════════════════════════════════════════════
   HELPERS
   ═══════════════════════════════════════════════════════════════ */
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
function lerp(a, b, t) { return a + (b - a) * t; }

function tickToMinute(tick) {
  const totalSec = Math.floor(tick / TICKS_PER_MINUTE * 60);
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${String(min).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
}

function matchMinute(tick) {
  const totalSec = Math.floor(tick / TICKS_PER_MINUTE * 60);
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${String(sec).padStart(2, '0')}`;
}

/* ═══════════════════════════════════════════════════════════════
   EVENT ICONS & CLASSIFICATION
   ═══════════════════════════════════════════════════════════════ */
const EV_ICON = {
  GOAL: '\u26BD', SHOT: '\u26BD', SHOT_SAVED: '\uD83E\uDD25', SHOT_MISSED: '\u274C',
  PENALTY_KICK: '\uD83C\uDFAF', PENALTY_MISS: '\u274C', PENALTY_SAVED: '\uD83E\uDD25',
  PASS: '\u27A1\uFE0F', PASS_COMPLETED: '\u2705', PASS_LOOSE: '\uD83D\uDCA8',
  CARRY: '\uD83C\uDFC3', CARRY_COMPLETED: '\uD83C\uDFC3',
  DUEL_START: '\u2694\uFE0F', DUEL_RESOLVED: '\u2694\uFE0F', DUEL_WON: '\uD83C\uDFC6',
  CROSS: '\u2197\uFE0F', CORNER: '\uD83C\uDFDF\uFE0F',
  POSSESSION_CHANGE: '\uD83D\uDD04', CHASE: '\uD83C\uDFC3', CHASE_POSSESSION: '\uD83C\uDFC3',
  VAR_OFFSIDE_CONFIRMED: '\uD83D\uDCFA', VAR_OFFSIDE_OVERTURNED: '\uD83D\uDCFA',
  VAR_GOAL_CONFIRMED: '\uD83D\uDCFA', VAR_GOAL_OVERTURNED: '\uD83D\uDCFA',
  VAR_RED_CONFIRMED: '\uD83D\uDCFA', VAR_RED_OVERTURNED: '\uD83D\uDCFA',
  VAR_PENALTY_CONFIRMED: '\uD83D\uDCFA', VAR_PENALTY_OVERTURNED: '\uD83D\uDCFA',
  YELLOW_CARD: '\uD83D\uDFE8', RED_CARD: '\uD83D\uDD34',
  FREE_KICK: '\uD83C\uDFAF', GOAL_KICK: '\uD83E\uDD25', THROW_IN: '\uD83E\uDD39',
  DECISION: '\uD83E\uDDE0', ACTION_EXECUTION: '\u26A1', ACTION_OUTCOME: '\uD83D\uDCCB',
  FOUL: '\u26A0\uFE0F', CARD: '\uD83D\uDFE8', RESTART: '\uD83D\uDD04', POSSESSION: '\uD83D\uDCCA',
  INFO: '\uD83D\uDCDD', GOAL_DISALLOWED: '\u26A0\uFE0F',
};

const IMPORTANT_EVENTS = new Set([
  'GOAL', 'GOAL_DISALLOWED', 'SHOT', 'SHOT_SAVED', 'SHOT_MISSED',
  'PENALTY_KICK', 'PENALTY_MISS', 'PENALTY_SAVED',
  'CROSS', 'CORNER', 'FREE_KICK', 'GOAL_KICK', 'THROW_IN',
  'VAR_OFFSIDE_CONFIRMED', 'VAR_OFFSIDE_OVERTURNED',
  'VAR_GOAL_CONFIRMED', 'VAR_GOAL_OVERTURNED',
  'VAR_RED_CONFIRMED', 'VAR_RED_OVERTURNED',
  'VAR_PENALTY_CONFIRMED', 'VAR_PENALTY_OVERTURNED',
  'YELLOW_CARD', 'RED_CARD',
  'DUEL_WON', 'POSSESSION_CHANGE',
  'FOUL', 'CARD',
]);

const MINOR_EVENTS = new Set([
  'PASS', 'PASS_COMPLETED', 'PASS_LOOSE',
  'CARRY', 'CARRY_COMPLETED',
  'DUEL_START', 'DUEL_RESOLVED',
  'CHASE', 'CHASE_POSSESSION',
  'DECISION', 'ACTION_EXECUTION', 'ACTION_OUTCOME',
  'INFO', 'RESTART', 'POSSESSION', 'VAR_IN_PROGRESS',
]);

function classifyEvent(ev) {
  const t = ev.type;
  if (t === 'GOAL' || t === 'GoalEvent') return 'goal';
  if (t?.startsWith('VAR_')) return 'var-ev';
  if (t === 'SHOT' || t === 'SHOT_SAVED') return 'shot';
  if (t?.includes('RED') || t === 'RED_CARD') return 'card-r';
  if (t === 'YELLOW_CARD' || t === 'CARD') return 'card-y';
  if (t === 'FOUL') return 'foul';
  return '';
}

function formatEventDesc(ev) {
  if (ev.source === 'log' && ev.team) {
    const prefix = ev.playerName ? `${ev.team} ${ev.playerName}` : ev.team;
    return `${prefix}: ${ev.description}`;
  }
  if (ev.team) {
    return `${ev.team}: ${ev.description || ev.type}`;
  }
  return ev.description || ev.type || '';
}

/* ═══════════════════════════════════════════════════════════════
   OVERLAY SYSTEM — Halftime, Fulltime, VAR, Goal
   ═══════════════════════════════════════════════════════════════ */
class OverlayManager {
  constructor() {
    this._el = document.getElementById('overlay');
    this._textEl = document.getElementById('overlayText');
    this._subEl = document.getElementById('overlaySub');
    this._active = false;
    this._resumeTime = 0;
    this._type = null;
    this._goalAnim = null;   // { tick, team, startRealTime }
    this._blocking = false;  // true = pause playback while visible (kickoff/goal/HT/FT/offside)
  }

  get isActive() { return this._active; }
  get isBlocking() { return this._blocking; }
  get resumeTime() { return this._resumeTime; }

  /** Show halftime overlay for 12 real seconds */
  showHalftime(homeName, awayName, homeScore, awayScore) {
    this._type = 'halftime';
    this._blocking = true;
    this._textEl.textContent = 'HALF TIME';
    this._subEl.textContent = `${homeName} ${homeScore} - ${awayScore} ${awayName}`;
    this._el.className = 'overlay visible halftime';
    this._active = true;
    this._resumeTime = performance.now() + 12000; // 12 seconds
  }

  /** Show full-time overlay (stays visible, no auto-dismiss) */
  showFulltime(homeName, awayName, homeScore, awayScore) {
    this._type = 'fulltime';
    this._blocking = true;
    this._textEl.textContent = 'FULL TIME';
    this._subEl.textContent = `${homeName} ${homeScore} - ${awayScore} ${awayName}`;
    this._el.className = 'overlay visible fulltime';
    this._active = true;
    this._resumeTime = Infinity; // never auto-dismiss
  }

  /** Show VAR review overlay for a duration in real ms — BLOCKING (game stops during review) */
  showVAR(reviewText, durationMs) {
    this._type = 'var';
    this._blocking = true; // game stands while the review is shown
    this._textEl.textContent = '\uD83D\uDCFA VAR IN PROGRESS';
    this._subEl.textContent = reviewText;
    this._el.className = 'overlay visible var-review';
    this._active = true;
    this._resumeTime = performance.now() + durationMs;
  }

  /** Show VAR decision result overlay (confirmed/overturned) — BLOCKING */
  showVARDecision(decisionText) {
    this._type = 'var-decision';
    this._blocking = true; // hold during the brief verdict display
    this._textEl.textContent = '\uD83D\uDCFA ' + decisionText;
    this._subEl.textContent = '';
    this._el.className = 'overlay visible var-decision';
    this._active = true;
    this._resumeTime = performance.now() + 2200; // 2.2 seconds
  }

  /** Show kickoff overlay (0:0, ball at center, waiting to start) */
  showKickoff(homeName, awayName) {
    this._type = 'kickoff';
    this._blocking = true;
    this._textEl.textContent = 'KICK OFF';
    this._subEl.textContent = `${homeName} 0 - 0 ${awayName}`;
    this._el.className = 'overlay visible kickoff';
    this._active = true;
    this._resumeTime = performance.now() + 3000; // 3 seconds, then auto-start
  }

  /** Show goal overlay with ball animation */
  showGoal(team, homeName, awayName, homeScore, awayScore) {
    this._type = 'goal';
    this._blocking = true;
    this._textEl.textContent = '\u26BD GOAL!';
    this._subEl.textContent = `${team === 'HOME' ? homeName : awayName} ${homeScore} - ${awayScore} ${team === 'HOME' ? awayName : homeName}`;
    this._el.className = 'overlay visible goal';
    this._active = true;
    this._resumeTime = performance.now() + 6000; // 6 seconds
    this._goalAnim = { team, startRealTime: performance.now() };
  }

  /** Show offside overlay: yellow flag + player + team */
  showOffside(playerName, team, margin) {
    this._type = 'offside';
    this._blocking = true;
    this._textEl.textContent = '\uD83D\uDEA9 OFFSIDE';
    const teamLabel = team || '';
    const marginText = margin ? ` (${margin})` : '';
    this._subEl.textContent = `${playerName}${marginText} — ${teamLabel}`;
    this._el.className = 'overlay visible offside';
    this._active = true;
    this._resumeTime = performance.now() + 3500; // 3.5 seconds
  }

  /** Show GOAL DISALLOWED overlay — cancels any active goal overlay */
  showGoalDisallowed(reason) {
    this._type = 'goal-disallowed';
    this._blocking = false;
    this._textEl.textContent = '\u26A0\uFE0F GOAL DISALLOWED';
    this._subEl.textContent = reason || '';
    this._el.className = 'overlay visible goal-disallowed';
    this._active = true;
    this._resumeTime = performance.now() + 4000; // 4 seconds
    // Clear goal animation if active
    this._goalAnim = null;
  }

  /** Show card overlay (yellow/red) with team name, player name, VAR indicator */
  showCard(cardType, playerName, team, isVar) {
    this._type = cardType === 'RED' ? 'red-card' : 'yellow-card';
    this._blocking = true;
    const cardLabel = cardType === 'RED' ? 'RED CARD' : 'YELLOW CARD';
    const icon = cardType === 'RED' ? '\uD83D\uDD34' : '\uD83D\uDFE8';
    const varSuffix = isVar ? ' \uD83D\uDCFA VAR' : '';
    this._textEl.innerHTML = `${icon} ${cardLabel}${varSuffix}`;
    this._subEl.textContent = `${playerName} — ${team}`;
    this._el.className = `overlay visible ${cardType === 'RED' ? 'red-card' : 'yellow-card'}`;
    this._active = true;
    this._resumeTime = performance.now() + 3000; // 3 seconds
  }

  getGoalAnimProgress() {
    if (!this._goalAnim) return null;
    const elapsed = (performance.now() - this._goalAnim.startRealTime) / 6000;
    if (elapsed > 1) { this._goalAnim = null; return null; }
    return { team: this._goalAnim.team, t: clamp(elapsed, 0, 1) };
  }

  /** Dismiss overlay early */
  dismiss() {
    this._el.className = 'overlay';
    this._active = false;
    this._type = null;
    this._blocking = false;
    this._goalAnim = null;
  }

  /** Check if auto-dismiss timer has elapsed */
  checkAutoDismiss() {
    if (!this._active) return false;
    if (this._resumeTime === Infinity) return false;
    if (performance.now() >= this._resumeTime) {
      this.dismiss();
      return true; // dismissed
    }
    return false;
  }
}

/* ═══════════════════════════════════════════════════════════════
   PITCH RENDERER

   Grid: 9 rows × 8 cols (0-indexed).
   row → X axis (horizontal), col → Y axis (vertical).
   Playing field: rows 1-7, cols 1-6.
   Rows 0, 8 and cols 0, 7 = out-of-bounds (corners, goals, etc).

   HOME left (row 1), attacks → right (row 7).
   AWAY right (row 7), attacks → left (row 1).
   ═══════════════════════════════════════════════════════════════ */
class PitchRenderer {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.margin = { top: 30, left: 40, right: 40, bottom: 30 };
    this.showGrid = false;  // grid overlay toggle (default off)
    this._resize();
    window.addEventListener('resize', () => this._resize());
  }

  _resize() {
    const wrap = this.canvas.parentElement;
    const wrapW = wrap.clientWidth - 24;
    const wrapH = wrap.clientHeight - 24;
    const pitchW = (GRID_ROWS - 1) * CELL_W;
    const pitchH = (GRID_COLS - 1) * CELL_H;
    const totalW = pitchW + this.margin.left + this.margin.right;
    const totalH = pitchH + this.margin.top + this.margin.bottom;
    const scale = Math.min(wrapW / totalW, wrapH / totalH, 1);
    this.canvas.width = Math.floor(totalW * scale);
    this.canvas.height = Math.floor(totalH * scale);
    this.ctx.setTransform(scale, 0, 0, scale, 0, 0);
    this.scale = scale;
  }

  toCanvas(row, col) {
    const pitchW = (GRID_ROWS - 1) * CELL_W;
    const pitchH = (GRID_COLS - 1) * CELL_H;
    const x = this.margin.left + (row / (GRID_ROWS - 1)) * pitchW;
    const y = this.margin.top + (col / (GRID_COLS - 1)) * pitchH;
    return [x, y];
  }

  drawPitch() {
    const ctx = this.ctx;
    const pw = (GRID_ROWS - 1) * CELL_W;
    const ph = (GRID_COLS - 1) * CELL_H;
    const [ox, oy] = this.toCanvas(0, 0);
    const canvasW = this.canvas.width / this.scale;
    const canvasH = this.canvas.height / this.scale;

    // Background
    ctx.fillStyle = '#0e1117';
    ctx.fillRect(0, 0, canvasW, canvasH);

    // OOB area (darker green behind the field)
    ctx.fillStyle = PITCH_OOB;
    ctx.fillRect(ox, oy, pw, ph);

    // Playing field (rows 1-7, cols 1-6) — brighter green
    const [fx1, fy1] = this.toCanvas(FIELD_ROW_MIN, FIELD_COL_MIN);
    const [fx2, fy2] = this.toCanvas(FIELD_ROW_MAX, FIELD_COL_MAX);
    ctx.fillStyle = PITCH_GREEN;
    ctx.fillRect(fx1, fy1, fx2 - fx1, fy2 - fy1);

    // Stripes on playing field only
    for (let r = FIELD_ROW_MIN; r < FIELD_ROW_MAX; r += 2) {
      const [sx] = this.toCanvas(r, 0);
      const [ex] = this.toCanvas(r + 1, 0);
      ctx.fillStyle = PITCH_STRIPE;
      ctx.fillRect(sx, fy1, ex - sx, fy2 - fy1);
    }

    // Field boundary (touchlines + goal lines)
    ctx.strokeStyle = PITCH_LINE;
    ctx.lineWidth = 2;
    ctx.strokeRect(fx1, fy1, fx2 - fx1, fy2 - fy1);

    // Grid overlay (toggleable, default off)
    if (this.showGrid) {
      ctx.strokeStyle = 'rgba(255,255,255,.12)';
      ctx.lineWidth = 1;
      for (let r = FIELD_ROW_MIN; r <= FIELD_ROW_MAX; r++) {
        const [rx] = this.toCanvas(r, 0);
        const [rx2] = this.toCanvas(r, GRID_COLS - 1);
        ctx.beginPath(); ctx.moveTo(rx, fy1); ctx.lineTo(rx, fy2); ctx.stroke();
      }
      for (let c = FIELD_COL_MIN; c <= FIELD_COL_MAX; c++) {
        const [, cy1] = this.toCanvas(0, c);
        const [, cy2] = this.toCanvas(GRID_ROWS - 1, c);
        ctx.beginPath(); ctx.moveTo(fx1, cy1); ctx.lineTo(fx2, cy1); ctx.stroke();
      }
    }

    // Center line
    const [cx] = this.toCanvas(4, 0);
    ctx.beginPath();
    ctx.moveTo(cx, fy1);
    ctx.lineTo(cx, fy2);
    ctx.stroke();

    // Center circle (radius ~9.15m ≈ 0.55 rows)
    const [ccx, ccy] = this.toCanvas(4, 3.5);
    const circleR = 0.55 * CELL_W;
    ctx.beginPath();
    ctx.ellipse(ccx, ccy, circleR, circleR * (CELL_H / CELL_W), 0, 0, Math.PI * 2);
    ctx.stroke();
    ctx.fillStyle = PITCH_LINE;
    ctx.beginPath();
    ctx.arc(ccx, ccy, 3, 0, Math.PI * 2);
    ctx.fill();

    // Penalty areas (16.5m ≈ 1.0 row deep, 40.3m ≈ 3.6 cols wide)
    // HOME penalty: rows 1-2, cols ~1.2-5.8
    this._box(ctx, 1, 1.2, 2, 5.8);
    // AWAY penalty: rows 6-7, cols ~1.2-5.8
    this._box(ctx, 6, 1.2, 7, 5.8);

    // Goal areas (5.5m ≈ 0.33 rows deep, 18.3m ≈ 1.6 cols wide)
    // HOME goal area: rows 1-1.5, cols ~1.95-5.05
    this._box(ctx, 1, 1.95, 1.5, 5.05);
    // AWAY goal area: rows 6.5-7, cols ~1.95-5.05
    this._box(ctx, 6.5, 1.95, 7, 5.05);

    // Penalty spots (11m ≈ 0.66 rows from goal line)
    const [p1x, p1y] = this.toCanvas(1.66, 3.5);
    const [p2x, p2y] = this.toCanvas(6.34, 3.5);
    ctx.fillStyle = PITCH_LINE;
    ctx.beginPath(); ctx.arc(p1x, p1y, 3, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(p2x, p2y, 3, 0, Math.PI * 2); ctx.fill();

    // Goals (nets behind the goal lines)
    this._goal(ctx, 1, 3.5, 'left');
    this._goal(ctx, 7, 3.5, 'right');

    // Team labels
    ctx.font = 'bold 11px system-ui';
    ctx.textAlign = 'center';
    ctx.globalAlpha = 0.4;
    ctx.fillStyle = HOME_COLOR;
    const [hl, hly] = this.toCanvas(0.5, 0.5);
    ctx.fillText('HOME', hl, hly);
    ctx.fillStyle = AWAY_COLOR;
    const [al, aly] = this.toCanvas(7.5, 0.5);
    ctx.fillText('AWAY', al, aly);
    ctx.globalAlpha = 1;

    // Attack direction arrows (subtle)
    ctx.globalAlpha = 0.15;
    ctx.fillStyle = HOME_COLOR;
    ctx.font = '24px system-ui';
    const [ar1x, ar1y] = this.toCanvas(3, 3.5);
    ctx.fillText('\u25B6', ar1x, ar1y);
    ctx.fillStyle = AWAY_COLOR;
    const [ar2x, ar2y] = this.toCanvas(5, 3.5);
    ctx.fillText('\u25C0', ar2x, ar2y);
    ctx.globalAlpha = 1;
  }

  _box(ctx, r1, c1, r2, c2) {
    const [x1, y1] = this.toCanvas(r1, c1);
    const [x2, y2] = this.toCanvas(r2, c2);
    ctx.strokeStyle = PITCH_LINE;
    ctx.lineWidth = 1.5;
    ctx.strokeRect(x1, y1, x2 - x1, y2 - y1);
  }

  _goal(ctx, row, col, side) {
    const [gx, gy] = this.toCanvas(row, col);
    const goalH = 2.2 * CELL_H;
    const depth = 50;
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 4;
    ctx.beginPath();
    if (side === 'left') {
      ctx.moveTo(gx, gy - goalH / 2);
      ctx.lineTo(gx - depth, gy - goalH / 2);
      ctx.lineTo(gx - depth, gy + goalH / 2);
      ctx.lineTo(gx, gy + goalH / 2);
    } else {
      ctx.moveTo(gx, gy - goalH / 2);
      ctx.lineTo(gx + depth, gy - goalH / 2);
      ctx.lineTo(gx + depth, gy + goalH / 2);
      ctx.lineTo(gx, gy + goalH / 2);
    }
    ctx.stroke();
  }

  drawPlayers(players, carrierId, duelPairs) {
    const ctx = this.ctx;

    // Build a set of labels involved in an active duel for quick lookup
    const duelLabels = new Set();
    if (duelPairs) {
      for (const pair of duelPairs) {
        if (pair[0]?.label) duelLabels.add(pair[0].label);
        if (pair[1]?.label) duelLabels.add(pair[1].label);
      }
    }

    // For duel convergence: if both duel participants exist, draw them
    // at touching positions (center-to-center ≈ 2 × player radius).
    // Pre-compute converged positions for dueling players.
    const convergedPos = new Map(); // id → {row, col}
    if (duelPairs) {
      for (const pair of duelPairs) {
        const pa = players.find(p => p.label === pair[0]?.label);
        const pb = players.find(p => p.label === pair[1]?.label);
        if (pa && pb && pa.id && pb.id) {
          const rA = pa.role === 'GK' ? 18 : 14;
          const rB = pb.role === 'GK' ? 18 : 14;
          // Two circles touching: center-to-center = rA + rB (in canvas px)
          // In grid units: (rA + rB) / CELL_W rows and / CELL_H cols
          const contactDistRows = (rA + rB) / CELL_W * 0.8; // 0.8 = pixel-to-grid scale approx
          const contactDistCols = (rA + rB) / CELL_H * 0.8;
          const midRow = (pa.row + pb.row) / 2;
          const midCol = (pa.col + pb.col) / 2;
          const dx = pb.col - pa.col;
          const dy = pb.row - pa.row;
          const dist = Math.hypot(dx, dy);
          if (dist > 0.001) {
            // Only converge players if they are genuinely near each other
            // (duel proximity check). If they are far apart, drawing them at
            // "touching" positions would teleport them — NIKAD no teleporting.
            const MAX_CONVERGE_DIST = 2.0; // grid cells
            if (dist > MAX_CONVERGE_DIST) continue;
            const overlap = (contactDistRows + contactDistCols) / 2;
            const moveFrac = Math.max(0, (dist - overlap) / 2 / dist);
            convergedPos.set(pa.id, {
              row: pa.row + dy / dist * moveFrac,
              col: pa.col + dx / dist * moveFrac,
            });
            convergedPos.set(pb.id, {
              row: pb.row - dy / dist * moveFrac,
              col: pb.col - dx / dist * moveFrac,
            });
          }
        }
      }
    }

    for (const p of players) {
      let pos = { row: p.row, col: p.col };
      if (convergedPos.has(p.id)) {
        pos = convergedPos.get(p.id);
      }
      const [x, y] = this.toCanvas(pos.row, pos.col);
      const isHome = p.team === 'HOME';
      const isGK = p.role === 'GK';
      const isCarrier = carrierId && p.id === carrierId;
      const r = isGK ? 18 : 14;
      const inDuel = duelLabels.has(p.label);

      // Duel highlight circle behind the player
      if (inDuel) {
        ctx.beginPath();
        ctx.arc(x, y, r + 6, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,255,0,.35)';
        ctx.strokeStyle = '#ffcc00';
        ctx.lineWidth = 2.5;
        ctx.stroke();
        ctx.fill();
      }

      if (isCarrier) {
        ctx.beginPath();
        ctx.arc(x, y, r + 10, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,140,0,.25)';
        ctx.fill();
        ctx.strokeStyle = '#ff8c00';
        ctx.lineWidth = 2;
        ctx.stroke();
      }

      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fillStyle = isGK ? GK_COLOR : (isHome ? HOME_COLOR : AWAY_COLOR);
      ctx.fill();
      ctx.strokeStyle = 'rgba(0,0,0,.4)';
      ctx.lineWidth = 1.5;
      ctx.stroke();

      ctx.fillStyle = '#fff';
      ctx.font = `bold ${isGK ? 14 : 12}px system-ui`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      const num = p.label.replace(/.*\s/, '');
      ctx.fillText(num, x, y + 0.5);
    }
  }

  drawBall(pos) {
    if (!pos) return;
    const ctx = this.ctx;
    const [x, y] = this.toCanvas(pos.row, pos.column);
    ctx.beginPath();
    ctx.arc(x + 1, y + 2, 8, 0, Math.PI * 2);
    ctx.fillStyle = BALL_SHADOW;
    ctx.fill();
    ctx.beginPath();
    ctx.arc(x, y, 7, 0, Math.PI * 2);
    ctx.fillStyle = BALL_COLOR;
    ctx.fill();
    ctx.strokeStyle = 'rgba(0,0,0,.3)';
    ctx.lineWidth = 1;
    ctx.stroke();
  }

  drawGoalAnim(goalAnim) {
    if (!goalAnim) return;
    const ctx = this.ctx;
    const t = goalAnim.t;
    const isHome = goalAnim.team === 'HOME';

    // Ball travels from center (row 4) toward the goal line and STOPS there
    // (row 7 for HOME, row 1 for AWAY). It does NOT continue into the
    // out-of-bounds area — the ball is "frozen" at the goal line so it is
    // visually distinct from a miss (which goes to row 8/0).
    const goalRow = isHome ? 7 : 1;
    const [gx, gy] = this.toCanvas(goalRow, 3.5);

    // Animate ball: 0.0 → center, 0.7 → reaches goal line, 0.7-1.0 → celebration
    const animThreshold = 0.7;
    let targetRow;
    if (t <= animThreshold) {
      targetRow = lerp(4, goalRow, t / animThreshold);
    } else {
      // Hold at the goal line
      targetRow = goalRow;
    }
    const [ballX, ballY] = this.toCanvas(targetRow, 3.5);

    // Draw the animated ball (white, glowing)
    const ballScale = t <= animThreshold ? 5 + Math.sin((t / animThreshold) * Math.PI) * 3 : 8;
    ctx.beginPath();
    ctx.arc(ballX, ballY, ballScale + 8, 0, Math.PI * 2);
    ctx.fillStyle = `rgba(255,255,255,${0.3 * (1 - t)})`;
    ctx.fill();
    ctx.beginPath();
    ctx.arc(ballX, ballY, ballScale, 0, Math.PI * 2);
    ctx.fillStyle = '#fff';
    ctx.fill();

    // After ball reaches goal line, show celebration rings + player spray
    if (t >= animThreshold) {
      const celebrationT = (t - animThreshold) / (1 - animThreshold); // 0→1
      const glowT = Math.sin(celebrationT * Math.PI) * 0.4 + 0.3;
      ctx.beginPath();
      ctx.arc(ballX, ballY, 30 + celebrationT * 60, 0, Math.PI * 2);
      ctx.fillStyle = `rgba(240,180,41,${glowT})`;
      ctx.fill();
      for (let i = 0; i < 6; i++) {
        const phase = (i / 6 + celebrationT * 0.4) % 1;
        const radius = 30 + phase * 200;
        const alpha = Math.max(0, (1 - celebrationT) * 0.5);
        ctx.beginPath();
        ctx.arc(gx, gy, radius, 0, Math.PI * 2);
        ctx.strokeStyle = `rgba(240,180,41,${alpha})`;
        ctx.lineWidth = 4 + celebrationT * 2;
        ctx.stroke();
      }
    }
  }

  render(players, ballPos, carrierId, flashEvent, goalAnim, duelPairs) {
    this.ctx.clearRect(0, 0, this.canvas.width / this.scale, this.canvas.height / this.scale);
    this.drawPitch();
    if (players) this.drawPlayers(players, carrierId, duelPairs);
    if (ballPos) this.drawBall(ballPos);
    this.drawGoalAnim(goalAnim);
  }
}

/* ═══════════════════════════════════════════════════════════════
   MATCH VIEWER
   ═══════════════════════════════════════════════════════════════ */
class MatchViewer {
  constructor() {
    this.pitch = new PitchRenderer(document.getElementById('pitch'));
    this.overlays = new OverlayManager();
    this.snapshots = [];
    this.events = [];
    this.goals = [];
    this.data = null;

    this.currentTick = 0;  // float for smooth interpolation
    this.startTick = 0;
    this.endTick = 0;
    this.playing = false;
    this.speed = 0.5;
    this._lastFrame = 0;
    this._tickAccum = 0;
    this._rafId = null;

    this._flashEvent = null;
    this._flashStart = 0;
    this._displayedEventIdx = 0;
    this._prevGoalCount = [0, 0];
    this._prevHalfTime = false;
    this._prevMatchFinished = false;
    this._varOverlayShown = false;
    this._varOverlayTick = -1;
    this._varReviewQueued = false;
    this._duelState = { pairs: [], currentTick: -1, resolved: new Set() };

    // Grid overlay toggle (default off)
    this.showGrid = false;

    // Snapshot lookup: O(1) access
    this._snapIndex = null;  // Map<tick, snapshot>
    this._snapTicks = null;  // sorted array of ticks

    this._bindControls();
    this._showEmpty();
  }

  /* ─── Data loading ─── */
  async generateMatch() {
    this._showLoading(true, 'Simulating match...');
    try {
      const res = await fetch('/api/generate', { method: 'POST' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const mres = await fetch('/match.json?' + Date.now());
      if (!mres.ok) throw new Error('match.json not found');
      this.data = await mres.json();
     // this._initFromData();
      // Do NOT auto-play — user clicks Play Match
    } catch (e) {
      alert('Failed: ' + e.message);
    } finally {
      this._showLoading(false);
    }
  }

  async loadMatch() {
    this._showLoading(true, 'Loading match...');
    try {
      const res = await fetch('/match.json?' + Date.now());
      if (!res.ok) throw new Error('No match.json — generate first');
      this.data = await res.json();
      this._initFromData();
      // Show kickoff overlay, then auto-start after 3s
      const homeName = this.data.homeTeamName || 'HOME';
      const awayName = this.data.awayTeamName || 'AWAY';
      this.overlays.showKickoff(homeName, awayName);
      this.play(); // will pause on overlay, then resume when dismissed
    } catch (e) {
      alert(e.message);
    } finally {
      this._showLoading(false);
    }
  }

  loadFromFile(file) {
    const reader = new FileReader();
    reader.onload = () => {
      try {
        this.data = JSON.parse(reader.result);
        this._initFromData();
      } catch (e) { alert('Invalid JSON: ' + e.message); }
    };
    reader.readAsText(file);
  }

  _initFromData() {
    this.snapshots = this.data.snapshots || [];

    // Build O(1) lookup index for snapshots
    this._snapIndex = new Map();
    this._snapTicks = [];
    for (const s of this.snapshots) {
      this._snapIndex.set(s.tick, s);
      this._snapTicks.push(s.tick);
    }

    // ALL events from recorder
    const recorderEvents = (this.data.events || []).map(e => ({
      tick: e.tick,
      type: e.type,
      description: e.description || '',
      team: e.team || null,
      playerName: e.playerName || null,
      source: 'event'
    }));

    // ActionLogService entries
    const logEntries = (this.data.logs || []).map(l => ({
      tick: l.tick || 0,
      type: l.type || '',
      description: l.description || '',
      team: l.team || null,
      playerName: l.playerName || null,
      targetPlayerName: l.targetPlayerName || null,
      channel: l.channel || null,
      matchClock: l.matchClock || null,
      source: 'log'
    }));

    const merged = new Map();
    for (const e of recorderEvents) merged.set(`e_${e.tick}_${e.type}`, e);
    for (const l of logEntries) merged.set(`l_${l.tick}_${l.type}`, l);
    this.events = [...merged.values()].sort((a, b) => a.tick - b.tick);

    // Detect goals
    this.goals = [];
    this._prevGoalCount = [0, 0];
    for (const snap of this.snapshots) {
      const hg = snap.goalCount || 0;
      const ag = snap.awayGoalCount || 0;
      if (hg > this._prevGoalCount[0]) {
        this.goals.push({ tick: snap.tick, team: 'HOME', score: `${hg}-${ag}` });
        this._prevGoalCount = [hg, ag];
      }
      if (ag > this._prevGoalCount[1]) {
        this.goals.push({ tick: snap.tick, team: 'AWAY', score: `${hg}-${ag}` });
        this._prevGoalCount = [hg, ag];
      }
    }

    for (const g of this.goals) {
      this.events.push({
        tick: g.tick,
        type: 'GOAL',
        team: g.team,
        homeScore: parseInt(g.score.split('-')[0]),
        awayScore: parseInt(g.score.split('-')[1]),
        description: `⚽ GOAL for ${g.team}! (${g.score})`,
      });
    }
    this.events.sort((a, b) => a.tick - b.tick);

    this.startTick = this.snapshots[0]?.tick || 0;
    this.endTick = this.snapshots[this.snapshots.length - 1]?.tick || 0;
    this.currentTick = this.startTick;
    this._prevHalfTime = false;
    this._prevMatchFinished = false;
    this._varOverlayShown = false;
    this._varOverlayTick = -1;
    this._varReviewQueued = false;
    this._duelState = { pairs: [], currentTick: -1, resolved: new Set() };

    document.getElementById('homeName').textContent = this.data.homeTeamName || 'HOME';
    document.getElementById('awayName').textContent = this.data.awayTeamName || 'AWAY';
    this._updateScoreboard();
    this._buildTimeline();
    this._updateSeekRange();
    this._showEmpty(false);
    this.pitch._resize();
    this._renderFrame();
  }

  /* ─── Snapshot interpolation ─── */

  _interpolateTick(tick) {
    if (!this._snapTicks.length) return null;

    let lo = 0, hi = this._snapTicks.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (this._snapTicks[mid] <= tick) lo = mid;
      else hi = mid - 1;
    }

    const snapA = this._snapIndex.get(this._snapTicks[lo]);
    const nextIdx = lo + 1;
    if (nextIdx >= this._snapTicks.length) return { snap: snapA, next: null, frac: 0 };

    const snapB = this._snapIndex.get(this._snapTicks[nextIdx]);
    const tickA = this._snapTicks[lo];
    const tickB = this._snapTicks[nextIdx];
    const frac = (tickB > tickA) ? (tick - tickA) / (tickB - tickA) : 0;

    return { snap: snapA, next: snapB, frac: clamp(frac, 0, 1) };
  }

  _getInterpolatedPlayers(interp) {
    if (!interp || !interp.next) {
      return interp?.snap?.players || [];
    }
    const a = interp.snap;
    const b = interp.next;
    const t = interp.frac;
    const result = [];
    for (const pa of a.players) {
      const pb = b.players.find(p => p.id === pa.id);
      if (pb) {
        result.push({
          id: pa.id, label: pa.label, team: pa.team, role: pa.role,
          row: lerp(pa.position.row, pb.position.row, t),
          col: lerp(pa.position.column, pb.position.column, t),
        });
      } else {
        result.push({
          id: pa.id, label: pa.label, team: pa.team, role: pa.role,
          row: pa.position.row, col: pa.position.column,
        });
      }
    }
    return result;
  }

  _getInterpolatedBall(interp) {
    if (!interp) return null;
    const a = interp.snap.ballPosition;
    if (!a || !interp.next) return a;
    const b = interp.next.ballPosition;
    if (!b) return a;
    const t = interp.frac;
    return { row: lerp(a.row, b.row, t), column: lerp(a.column, b.column, t) };
  }

  _getCarrierId(interp) {
    if (!interp) return null;
    if (interp.frac > 0.5 && interp.next) return interp.next.ballCarrierId;
    return interp.snap.ballCarrierId;
  }

  /* ─── Playback ─── */
  play() {
    if (this.playing) return;
    this.playing = true;
    this._lastFrame = performance.now();
    this._tickAccum = 0;
    this._loop();
    document.getElementById('playBtn').classList.add('active');
    document.getElementById('pauseBtn').classList.remove('active');
  }

  pause() {
    this.playing = false;
    if (this._rafId) cancelAnimationFrame(this._rafId);
    document.getElementById('playBtn').classList.remove('active');
    document.getElementById('pauseBtn').classList.add('active');
  }

  seek(tick) {
    this.currentTick = clamp(tick, this.startTick, this.endTick);
    this._displayedEventIdx = 0;
    while (this._displayedEventIdx < this.events.length &&
           this.events[this._displayedEventIdx].tick <= this.currentTick) {
      this._displayedEventIdx++;
    }
    this._renderFrame();
  }

  _loop() {
    if (!this.playing) return;
    const now = performance.now();
    const dt = (now - this._lastFrame) / 1000;
    this._lastFrame = now;

    // If a BLOCKING overlay is active, pause playback (kickoff/goal/HT/FT/offside).
    // Non-blocking overlays (VAR) keep the match flowing.
    if (this.overlays.isBlocking) {
      this.overlays.checkAutoDismiss();
      if (this.overlays.isBlocking) {
        // Still active — keep rendering but don't advance ticks
        this._renderFrame();
        this._rafId = requestAnimationFrame(() => this._loop());
        return;
      }
      // Overlay just dismissed — resume
    }

    // 1.875 ticks/sec at 1x (half of old 0.25x rate for realistic pacing)
    const ticksPerSec = 1.875 * this.speed;
    this._tickAccum += dt * ticksPerSec;

    const fromTick = this.currentTick;
    this.currentTick += this._tickAccum;
    this._tickAccum = 0;

    if (this.currentTick >= this.endTick) {
      this.currentTick = this.endTick;
      this.pause();
    }

    this._processEventsForTick(fromTick, this.currentTick);
    this._checkSnapshotOverlays();
    this._renderFrame();
    this._rafId = requestAnimationFrame(() => this._loop());
  }

  _processEventsForTick(fromTick, toTick) {
    while (this._displayedEventIdx < this.events.length) {
      const ev = this.events[this._displayedEventIdx];
      if (ev.tick > toTick) break;
      if (ev.tick >= fromTick) {
        this._addTimelineEvent(ev);
        if (ev.type === 'GOAL') {
          this._flashEvent = ev;
          this._flashStart = performance.now();
          this._flashEvent._age = 0;
          const homeName = this.data.homeTeamName || 'HOME';
          const awayName = this.data.awayTeamName || 'AWAY';
          const hg = ev.homeScore ?? this.data.homeGoals ?? 0;
          const ag = ev.awayScore ?? this.data.awayGoals ?? 0;
          const goalTeam = ev.team === 'HOME' || ev.team === 'AWAY' ? ev.team : 'HOME';
          this.overlays.showGoal(goalTeam, homeName, awayName, hg, ag);
        }
        // OFFSIDE overlay
        if (ev.type === 'OFFSIDE') {
          const desc = ev.description || '';
          const playerName = desc.replace(/^.*offside\s+/i, '').replace(/\s*\(.*/, '').trim() || 'Player';
          const team = ev.team || '';
          const marginMatch = desc.match(/margin=([0-9.]+)/);
          const margin = marginMatch ? marginMatch[1] : '';
          this.overlays.showOffside(playerName, team, margin);
        }
        // VAR decision overlays (confirmed/overturned)
        if (ev.type?.startsWith('VAR_OFFSIDE_') || ev.type?.startsWith('VAR_GOAL_')
            || ev.type?.startsWith('VAR_RED_') || ev.type?.startsWith('VAR_PENALTY_')
            || ev.type?.startsWith('VAR_YELLOW_')) {
          const decisionLabel = ev.type.includes('CONFIRMED') ? 'CONFIRMED' : 'OVERTURNED';
          const reviewType = ev.type.replace('VAR_', '').replace('_CONFIRMED', '').replace('_OVERTURNED', '').replace('_', ' ');
          this.overlays.showVARDecision(`VAR ${reviewType}: ${decisionLabel}`);
          this._varReviewQueued = false;
        }
        // GOAL DISALLOWED overlay — cancels any active goal overlay
        if (ev.type === 'GOAL_DISALLOWED') {
          this.overlays.showGoalDisallowed(ev.description || 'GOAL DISALLOWED');
        }
        // VAR IN PROGRESS overlay — BLOCKING, shows team + incident. Breaks the
        // event batch so the CONFIRMED/OVERTURNED verdict (emitted on the same
        // tick) is shown only AFTER this review overlay is dismissed — creating
        // the IN PROGRESS -> decision sequence instead of them overwriting.
        if (ev.type === 'VAR_IN_PROGRESS') {
          if (!this._varReviewQueued) {
            this._varReviewQueued = true;
            this._varOverlayTick = ev.tick;
            this.overlays.showVAR(ev.description || 'Reviewing incident...', 3500);
            break;
          }
          // Re-entry after the review overlay dismissed — fall through to the
          // confirmed/overturned decision that immediately follows on this tick.
        }
        // CARD overlay — yellow/red card with team name, player name, VAR indicator
        if (ev.type === 'CARD' || ev.type === 'YELLOW_CARD' || ev.type === 'RED_CARD') {
          const isRed = ev.type === 'RED_CARD' || ev.description?.includes('RED');
          const cardType = isRed ? 'RED' : 'YELLOW';
          const desc = ev.description || '';
          // Parse player name and team from description like:
          // "CARD: YELLOW ... (previous yellows=1)" or "AWAY Player 11: FOUL → card YELLOW"
          const teamFromEv = ev.team || (desc.includes('HOME') ? 'HOME' : 'AWAY');
          const nameMatch = desc.match(/(?:HOME|AWAY)\s+(\w+\s+\d+)/);
          const playerName = ev.playerName || (nameMatch ? nameMatch[1] : 'Player');
          const teamLabel = teamFromEv === 'HOME'
            ? (this.data.homeTeamName || 'HOME')
            : (this.data.awayTeamName || 'AWAY');
          const isVar = desc.includes('VAR') || ev.channel?.includes('VAR');
          this.overlays.showCard(cardType, playerName, teamLabel, !!isVar);
        }
        // FOUL overlay — brief foul notification
        if (ev.type === 'FOUL') {
          const desc = ev.description || '';
          const teamMatch = desc.match(/(HOME|AWAY)/);
          const team = teamMatch ? teamMatch[1] : (ev.team || '');
          const teamLabel = team === 'HOME'
            ? (this.data.homeTeamName || 'HOME')
            : (team === 'AWAY' ? (this.data.awayTeamName || 'AWAY') : '');
          const playerMatch = desc.match(/(?:HOME|AWAY)\s+(\w+\s+\d+)/);
          const playerName = ev.playerName || (playerMatch ? playerMatch[1] : 'Player');
          const foulTypeMatch = desc.match(/foul type[:\s]+(\w+)/i) || desc.match(/\(([^)]+)\)/);
          const foulType = foulTypeMatch ? foulTypeMatch[1] : 'Tackle foul';
          // Show as a brief non-blocking flash in the timeline (not a blocking overlay)
          this._flashEvent = { type: 'FOUL', team, playerName, description: `${playerName} (${foulType})`, _age: 0 };
          this._flashStart = performance.now();
        }
      }
      this._displayedEventIdx++;
    }
    if (this._flashEvent) {
      this._flashEvent._age = (performance.now() - this._flashStart) / 2000;
      if (this._flashEvent._age > 1.5) this._flashEvent = null;
    }
    this._updateDuelState(this.currentTick);
  }

  /** Parse DUEL_START / DUEL_RESOLVED events and track active duel pairs per tick */
  _updateDuelState(currentTick) {
    if (!this.data) return;
    if (currentTick <= this._duelState.currentTick) return; // already processed

    // Process duel events in chronological order
    for (const ev of this.events) {
      if (ev.tick > currentTick) break;
      if (ev.tick <= this._duelState.currentTick) continue; // already processed

      if (ev.type === 'DUEL_START') {
        // Parse "TYPE AttackerLabel vs DefenderLabel" from description
        // Example: "RECEIVE_PASS Home FC 11 vs Away United 7"
        // Player labels are like "Home FC 11" or "Away United 7" (team name + number)
        const desc = ev.description || '';
        const parts = desc.split(' vs ');
        if (parts.length === 2) {
          // Strip the action type prefix from the first part (e.g. "RECEIVE_PASS ")
          const attackerPart = parts[0].replace(/^.*?\s+/, '').trim();
          const defenderPart = parts[1].trim();
          const pairKey = attackerPart + '|' + defenderPart;
          this._duelState.resolved.delete(pairKey);
          this._duelState.pairs.push({ tick: ev.tick, a: attackerPart, b: defenderPart });
        }
      } else if (ev.type === 'DUEL_RESOLVED') {
        // Immediately mark this duel pair as resolved so circles disappear
        // without the 1-2 tick delay from time-window expiry.
        // Description format: "TYPE labelA vs labelB | winner=..."
        const desc = ev.description || '';
        const parts = desc.split(' vs ');
        if (parts.length === 2) {
          const attackerPart = parts[0].replace(/^.*?\s+/, '').trim();
          // Strip any trailing detail (e.g. "| winner=...") from the defender label
          const defenderPart = parts[1].split('|')[0].trim();
          this._duelState.resolved.add(attackerPart + '|' + defenderPart);
        }
      }
    }
    this._duelState.currentTick = currentTick;
  }

  /** Return list of active duel player-label pairs at the current tick */
  _getActiveDuelPairs(tick) {
    const intTick = Math.floor(tick);
    const DUEL_DURATION = 2; // ticks — duels typically resolve within 1-2 ticks
    return this._duelState.pairs
      .filter(dp => dp.tick <= intTick && dp.tick + DUEL_DURATION >= intTick)
      .filter(dp => !this._duelState.resolved.has(dp.a + '|' + dp.b))
      .map(dp => [{ label: dp.a }, { label: dp.b }]);
  }

  /** Check snapshot flags for halftime, fulltime, and VAR events */
  _checkSnapshotOverlays() {
    if (!this.data) return;
    const intTick = Math.floor(this.currentTick);
    const snap = this._snapIndex?.get(intTick);
    if (!snap) return;

    const hg = snap.goalCount ?? 0;
    const ag = snap.awayGoalCount ?? 0;
    const homeName = this.data.homeTeamName || 'HOME';
    const awayName = this.data.awayTeamName || 'AWAY';

    // Halftime
    if (snap.halfTime && !this._prevHalfTime) {
      this._prevHalfTime = true;
      this.overlays.showHalftime(homeName, awayName, hg, ag);
    }

    // Fulltime
    if (snap.matchFinished && !this._prevMatchFinished) {
      this._prevMatchFinished = true;
      this.overlays.showFulltime(homeName, awayName, hg, ag);
    }

    // VAR events — show overlay when VAR events appear in the event stream
    while (this._displayedEventIdx < this.events.length) {
      const ev = this.events[this._displayedEventIdx];
      if (ev.tick > intTick) break;
      if (ev.tick <= intTick && ev.type === 'VAR_IN_PROGRESS' && ev.tick !== this._varOverlayTick) {
        this._varOverlayTick = ev.tick;
        this.overlays.showVAR(ev.description || 'Reviewing incident...', 3500);
        break;
      }
    }
  }

  _renderFrame() {
    const interp = this._interpolateTick(this.currentTick);
    const players = this._getInterpolatedPlayers(interp);
    const ball = this._getInterpolatedBall(interp);
    const carrierId = this._getCarrierId(interp);
    const goalAnim = this.overlays.getGoalAnimProgress();
    const duelPairs = this._getActiveDuelPairs(this.currentTick);
    this.pitch.showGrid = this.showGrid;
    this.pitch.render(players, ball, carrierId, this._flashEvent, goalAnim, duelPairs);
    this._updateScoreboard();
    this._updateSeek();
  }

  /* ─── UI updates ─── */
  _updateScoreboard() {
    const intTick = Math.floor(this.currentTick);
    const snap = this._snapIndex?.get(intTick) || null;
    const hg = snap?.goalCount ?? this._prevGoalCount[0] ?? this.data.homeGoals ?? 0;
    const ag = snap?.awayGoalCount ?? this._prevGoalCount[1] ?? this.data.awayGoals ?? 0;
    document.getElementById('homeScore').textContent = hg;
    document.getElementById('awayScore').textContent = ag;
    document.getElementById('clock').textContent = tickToMinute(this.currentTick);
    const status = snap?.halfTime ? 'HT' : (snap?.matchFinished ? 'FT' : '');
    document.getElementById('statusLabel').textContent = status;
  }

  _updateSeekRange() {
    const seek = document.getElementById('seek');
    seek.min = this.startTick;
    seek.max = this.endTick;
    seek.value = this.currentTick;
  }

  _updateSeek() {
    const seek = document.getElementById('seek');
    if (!seek._dragging) seek.value = this.currentTick;
  }

  _addTimelineEvent(ev) {
    const ul = document.getElementById('timeline');
    const li = document.createElement('li');
    const cls = classifyEvent(ev);
    const icon = EV_ICON[ev.type] || '\uD83D\uDCDD';
    const minute = matchMinute(ev.tick);
    const desc = formatEventDesc(ev);
    const isMinor = MINOR_EVENTS.has(ev.type);

    const descHtml = desc
      .replace(/(HOME\s*\w*)/g, '<span class="team-home">$1</span>')
      .replace(/(AWAY\s*\w*)/g, '<span class="team-away">$1</span>');

    li.className = `event ${cls} ${isMinor ? 'minor' : ''}`;
    li.innerHTML = `<span class="min">${minute}'</span><span class="icon">${icon}</span><span class="desc">${descHtml}</span>`;
    ul.appendChild(li);

    // Always auto-scroll to bottom
    ul.scrollTop = ul.scrollHeight;
  }

  _buildTimeline() {
    document.getElementById('timeline').innerHTML = '';
  }

  _showEmpty(show = true) {
    document.getElementById('emptyState').style.display = show ? 'flex' : 'none';
    document.querySelector('.pitch-wrap').style.display = show ? 'none' : 'flex';
    document.querySelector('.sidebar').style.display = show ? 'none' : 'flex';
  }

  _showLoading(show, text) {
    document.getElementById('loading').classList.toggle('hidden', !show);
    if (text) document.getElementById('loadingText').textContent = text;
  }

  /* ─── Controls ─── */
  _bindControls() {
    const playBtn = document.getElementById('playBtn');
    const pauseBtn = document.getElementById('pauseBtn');
    const seek = document.getElementById('seek');
    const fileBtn = document.getElementById('fileBtn');
    const fileInput = document.getElementById('fileInput');
    const simBtn = document.getElementById('simBtn');
    const simBtn2 = document.getElementById('simBtn2');
    const playMatchBtn = document.getElementById('playMatchBtn');
    const speedSlider = document.getElementById('speedSlider');

    playBtn.addEventListener('click', () => this.play());
    pauseBtn.addEventListener('click', () => this.pause());
    seek.addEventListener('input', () => { seek._dragging = true; this.seek(Number(seek.value)); });
    seek.addEventListener('change', () => { seek._dragging = false; });
    fileBtn.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', () => { if (fileInput.files[0]) this.loadFromFile(fileInput.files[0]); });
    if (simBtn) simBtn.addEventListener('click', () => this.generateMatch());
    if (simBtn2) simBtn2.addEventListener('click', () => this.generateMatch());
    if (playMatchBtn) playMatchBtn.addEventListener('click', () => this.loadMatch());

    if (speedSlider) {
      const speeds = [0.25, 0.5, 1, 2, 4];
      speedSlider.max = speeds.length - 1;
      speedSlider.value = 1;  // default = 0.5x
      const update = () => {
        this.speed = speeds[Number(speedSlider.value)];
        document.getElementById('speedLabel').textContent = this.speed + 'x';
      };
      speedSlider.addEventListener('input', update);
      update();
    }

    // Grid overlay toggle (default off) — created dynamically since we
    // don't control the HTML from viewer.js alone.
    const gridControlsRow = document.querySelector('.controls-row:last-child');
    if (gridControlsRow) {
      const gridLabel = document.createElement('label');
      gridLabel.style.cssText = 'display:flex;align-items:center;gap:4px;font-size:11px;color:#8b949e;cursor:pointer;';
      gridLabel.title = 'Toggle grid overlay';
      const gridCheck = document.createElement('input');
      gridCheck.type = 'checkbox';
      gridCheck.id = 'gridToggle';
      gridCheck.style.width = '14px';
      gridCheck.style.height = '14px';
      gridLabel.appendChild(gridCheck);
      const gridText = document.createTextNode(' GRID');
      gridLabel.appendChild(gridText);
      gridControlsRow.appendChild(gridLabel);
      gridCheck.addEventListener('change', () => {
        this.showGrid = gridCheck.checked;
        this.pitch.showGrid = this.showGrid;
        this._renderFrame();
      });
    }

    document.addEventListener('keydown', (e) => {
      if (e.target.tagName === 'INPUT') return;
      if (e.code === 'Space') { e.preventDefault(); this.playing ? this.pause() : this.play(); }
      if (e.code === 'ArrowLeft') this.seek(this.currentTick - TICKS_PER_MINUTE);
      if (e.code === 'ArrowRight') this.seek(this.currentTick + TICKS_PER_MINUTE);
    });
  }
}

/* ═══════════════════════════════════════════════════════════════
   BOOT
   ═══════════════════════════════════════════════════════════════ */
document.addEventListener('DOMContentLoaded', () => {
  const v = new MatchViewer();
  window.viewer = v;
});
