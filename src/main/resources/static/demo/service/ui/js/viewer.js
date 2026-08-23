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
const CELL_W = 90;
const CELL_H = 80;

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
  GOAL: '⚽', SHOT: '⚽', SHOT_SAVED: '🧤', SHOT_MISSED: '❌',
  PENALTY_KICK: '🎯', PENALTY_MISS: '❌', PENALTY_SAVED: '🧤',
  PASS: '➡️', PASS_COMPLETED: '✅', PASS_LOOSE: '💨',
  CARRY: '🏃', CARRY_COMPLETED: '🏃',
  DUEL_START: '⚔️', DUEL_RESOLVED: '⚔️', DUEL_WON: '🏆',
  CROSS: '↗️', CORNER: '🏟️',
  POSSESSION_CHANGE: '🔄', CHASE: '🏃', CHASE_POSSESSION: '🏃',
  VAR_OFFSIDE_CONFIRMED: '📺', VAR_OFFSIDE_OVERTURNED: '📺',
  VAR_GOAL_CONFIRMED: '📺', VAR_GOAL_OVERTURNED: '📺',
  VAR_RED_CONFIRMED: '📺', VAR_RED_OVERTURNED: '📺',
  VAR_PENALTY_CONFIRMED: '📺', VAR_PENALTY_OVERTURNED: '📺',
  YELLOW_CARD: '🟨', RED_CARD: '🟥',
  FREE_KICK: '🎯', GOAL_KICK: '🧤', THROW_IN: '🤾',
  DECISION: '🧠', ACTION_EXECUTION: '⚡', ACTION_OUTCOME: '📋',
  FOUL: '⚠️', CARD: '🟨', RESTART: '🔄', POSSESSION: '📊',
  INFO: '📝',
};

const IMPORTANT_EVENTS = new Set([
  'GOAL', 'SHOT', 'SHOT_SAVED', 'SHOT_MISSED',
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
  'INFO', 'RESTART', 'POSSESSION',
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

    // Penalty areas (16.5m ≈ 1 row deep, 40.3m ≈ 3.6 cols wide)
    // HOME penalty: rows 1-3, cols ~1.2-5.8
    this._box(ctx, 1, 1.2, 3, 5.8);
    // AWAY penalty: rows 5-7, cols ~1.2-5.8
    this._box(ctx, 5, 1.2, 7, 5.8);

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

    // Penalty arcs (outside penalty area)
    const arcR = 0.55 * CELL_W;
    // HOME arc (at row 3, only the part outside penalty area)
    ctx.beginPath();
    ctx.ellipse(p1x, p1y, arcR, arcR * (CELL_H / CELL_W), 0, -0.7, 0.7);
    ctx.stroke();
    // AWAY arc
    ctx.beginPath();
    ctx.ellipse(p2x, p2y, arcR, arcR * (CELL_H / CELL_W), 0, Math.PI - 0.7, Math.PI + 0.7);
    ctx.stroke();

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
    ctx.fillText('▶', ar1x, ar1y);
    ctx.fillStyle = AWAY_COLOR;
    const [ar2x, ar2y] = this.toCanvas(5, 3.5);
    ctx.fillText('◀', ar2x, ar2y);
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
    const goalH = 1.5 * CELL_H;
    const depth = 14;
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 3;
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

  drawPlayers(players, carrierId) {
    const ctx = this.ctx;
    for (const p of players) {
      const [x, y] = this.toCanvas(p.row, p.col);
      const isHome = p.team === 'HOME';
      const isGK = p.role === 'GK';
      const isCarrier = carrierId && p.id === carrierId;
      const r = isGK ? 9 : 7;

      if (isCarrier) {
        ctx.beginPath();
        ctx.arc(x, y, r + 6, 0, Math.PI * 2);
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
      ctx.font = `bold ${isGK ? 8 : 7}px system-ui`;
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
    ctx.arc(x + 1, y + 2, 6, 0, Math.PI * 2);
    ctx.fillStyle = BALL_SHADOW;
    ctx.fill();
    ctx.beginPath();
    ctx.arc(x, y, 5, 0, Math.PI * 2);
    ctx.fillStyle = BALL_COLOR;
    ctx.fill();
    ctx.strokeStyle = 'rgba(0,0,0,.3)';
    ctx.lineWidth = 1;
    ctx.stroke();
  }

  drawEventFlash(event) {
    if (!event) return;
    const ctx = this.ctx;
    const [x, y] = this.toCanvas(4, 3.5);
    const t = event._age || 0;
    if (t > 1) return;
    const alpha = Math.max(0, 1 - t);
    if (event.type === 'GOAL') {
      for (let i = 0; i < 3; i++) {
        const phase = i / 3 + (1 - t) * 0.5;
        const r = 40 + phase * 200;
        ctx.beginPath();
        ctx.arc(x, y, r, 0, Math.PI * 2);
        ctx.strokeStyle = `rgba(240,180,41,${alpha * 0.6})`;
        ctx.lineWidth = 6;
        ctx.stroke();
      }
      ctx.font = `bold ${48 + Math.sin(Date.now() / 120) * 4}px system-ui`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = `rgba(240,180,41,${alpha})`;
      ctx.fillText('GOAL!', x, y);
    }
  }

  render(players, ballPos, carrierId, flashEvent) {
    this.ctx.clearRect(0, 0, this.canvas.width / this.scale, this.canvas.height / this.scale);
    this.drawPitch();
    if (players) this.drawPlayers(players, carrierId);
    if (ballPos) this.drawBall(ballPos);
    this.drawEventFlash(flashEvent);
  }
}

/* ═══════════════════════════════════════════════════════════════
   MATCH VIEWER
   ═══════════════════════════════════════════════════════════════ */
class MatchViewer {
  constructor() {
    this.pitch = new PitchRenderer(document.getElementById('pitch'));
    this.snapshots = [];
    this.events = [];
    this.goals = [];
    this.data = null;

    this.currentTick = 0;  // float for smooth interpolation
    this.startTick = 0;
    this.endTick = 0;
    this.playing = false;
    this.speed = 1;
    this._lastFrame = 0;
    this._tickAccum = 0;
    this._rafId = null;

    this._flashEvent = null;
    this._flashStart = 0;
    this._displayedEventIdx = 0;
    this._prevGoalCount = [0, 0];

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
      this._initFromData();
      this.play();
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
      this.play();
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
        description: `⚽ GOAL for ${g.team}! (${g.score})`,
      });
    }
    this.events.sort((a, b) => a.tick - b.tick);

    this.startTick = this.snapshots[0]?.tick || 0;
    this.endTick = this.snapshots[this.snapshots.length - 1]?.tick || 0;
    this.currentTick = this.startTick;

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

    // 10 ticks/sec at 1x (was 40, halved twice = 10)
    const ticksPerSec = 10 * this.speed;
    this._tickAccum += dt * ticksPerSec;

    const fromTick = this.currentTick;
    this.currentTick += this._tickAccum;
    this._tickAccum = 0;

    if (this.currentTick >= this.endTick) {
      this.currentTick = this.endTick;
      this.pause();
    }

    this._processEventsForTick(fromTick, this.currentTick);
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
        }
      }
      this._displayedEventIdx++;
    }
    if (this._flashEvent) {
      this._flashEvent._age = (performance.now() - this._flashStart) / 2000;
      if (this._flashEvent._age > 1.5) this._flashEvent = null;
    }
  }

  _renderFrame() {
    const interp = this._interpolateTick(this.currentTick);
    const players = this._getInterpolatedPlayers(interp);
    const ball = this._getInterpolatedBall(interp);
    const carrierId = this._getCarrierId(interp);
    this.pitch.render(players, ball, carrierId, this._flashEvent);
    this._updateScoreboard();
    this._updateSeek();
  }

  /* ─── UI updates ─── */
  _updateScoreboard() {
    const intTick = Math.floor(this.currentTick);
    const snap = this._snapIndex?.get(intTick) || null;
    const hg = snap?.goalCount ?? this._prevGoalCount[0];
    const ag = snap?.awayGoalCount ?? this._prevGoalCount[1];
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
    const icon = EV_ICON[ev.type] || '📝';
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
      const speeds = [0.25, 0.5, 1, 2, 5];
      speedSlider.max = speeds.length - 1;
      speedSlider.value = 2;  // default = 1x
      const update = () => {
        this.speed = speeds[Number(speedSlider.value)];
        document.getElementById('speedLabel').textContent = this.speed + 'x';
      };
      speedSlider.addEventListener('input', update);
      update();
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
