/**
 * TIFO Demo Service — Match Viewer
 *
 * Horizontal pitch: HOME left (row 1), AWAY right (row 7).
 * ALL events shown in timeline. Animated player positions from snapshots.
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
const PITCH_STRIPE = 'rgba(255,255,255,.04)';
const PITCH_LINE = 'rgba(255,255,255,.55)';
const GRID_ROWS = 9;
const GRID_COLS = 8;
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
  // Shots
  GOAL: '⚽', SHOT: '⚽', SHOT_SAVED: '🧤', SHOT_MISSED: '❌',
  // Penalties
  PENALTY_KICK: '🎯', PENALTY_MISS: '❌', PENALTY_SAVED: '🧤',
  // Passes
  PASS: '➡️', PASS_COMPLETED: '✅', PASS_LOOSE: '💨',
  // Carry
  CARRY: '🏃', CARRY_COMPLETED: '🏃',
  // Duels
  DUEL_START: '⚔️', DUEL_RESOLVED: '⚔️', DUEL_WON: '🏆',
  // Crosses & corners
  CROSS: '↗️', CORNER: '🏟️',
  // Possession
  POSSESSION_CHANGE: '🔄', CHASE: '🏃', CHASE_POSSESSION: '🏃',
  // VAR
  VAR_OFFSIDE_CONFIRMED: '📺', VAR_OFFSIDE_OVERTURNED: '📺',
  VAR_GOAL_CONFIRMED: '📺', VAR_GOAL_OVERTURNED: '📺',
  VAR_RED_CONFIRMED: '📺', VAR_RED_OVERTURNED: '📺',
  VAR_PENALTY_CONFIRMED: '📺', VAR_PENALTY_OVERTURNED: '📺',
  // Cards
  YELLOW_CARD: '🟨', RED_CARD: '🟥',
  // Other
  FREE_KICK: '🎯', GOAL_KICK: '🧤', THROW_IN: '🤾',
  // LogEntry types
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
  // For log entries, show rich description with team/player prefix
  if (ev.source === 'log' && ev.team) {
    const prefix = ev.playerName ? `${ev.team} ${ev.playerName}` : ev.team;
    return `${prefix}: ${ev.description}`;
  }
  // For events with team field, show it
  if (ev.team) {
    return `${ev.team}: ${ev.description || ev.type}`;
  }
  return ev.description || ev.type || '';
}

/* ═══════════════════════════════════════════════════════════════
   PITCH RENDERER
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
    const x = this.margin.left + (row / 8) * pitchW;
    const y = this.margin.top + (col / 7) * pitchH;
    return [x, y];
  }

  drawPitch() {
    const ctx = this.ctx;
    const pw = (GRID_ROWS - 1) * CELL_W;
    const ph = (GRID_COLS - 1) * CELL_H;
    const [ox, oy] = this.toCanvas(0, 0);

    ctx.fillStyle = '#0e1117';
    ctx.fillRect(0, 0, this.canvas.width / this.scale, this.canvas.height / this.scale);

    ctx.fillStyle = PITCH_GREEN;
    ctx.fillRect(ox, oy, pw, ph);

    // Stripes
    for (let r = 0; r < 8; r += 2) {
      const [sx] = this.toCanvas(r, 0);
      const [ex] = this.toCanvas(r + 1, 0);
      ctx.fillStyle = PITCH_STRIPE;
      ctx.fillRect(sx, oy, ex - sx, ph);
    }

    ctx.strokeStyle = PITCH_LINE;
    ctx.lineWidth = 2;
    ctx.strokeRect(ox, oy, pw, ph);

    // Center line
    const [cx] = this.toCanvas(4, 0);
    ctx.beginPath();
    ctx.moveTo(cx, oy);
    ctx.lineTo(cx, oy + ph);
    ctx.stroke();

    // Center circle
    const [ccx, ccy] = this.toCanvas(4, 3.5);
    ctx.beginPath();
    ctx.ellipse(ccx, ccy, 1.5 * CELL_W, 1.2 * CELL_H, 0, 0, Math.PI * 2);
    ctx.stroke();
    ctx.fillStyle = PITCH_LINE;
    ctx.beginPath();
    ctx.arc(ccx, ccy, 3, 0, Math.PI * 2);
    ctx.fill();

    // Penalty areas
    this._box(ctx, 0, 1.5, 2.0, 6.5);
    this._box(ctx, 6.0, 1.5, 8.0, 6.5);
    // Goal areas
    this._box(ctx, 0, 2.5, 1.2, 5.5);
    this._box(ctx, 6.8, 2.5, 8.0, 5.5);
    // Penalty spots
    const [p1x, p1y] = this.toCanvas(1.2, 3.5);
    const [p2x, p2y] = this.toCanvas(6.8, 3.5);
    ctx.fillStyle = PITCH_LINE;
    ctx.beginPath(); ctx.arc(p1x, p1y, 3, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(p2x, p2y, 3, 0, Math.PI * 2); ctx.fill();

    // Goals
    this._goal(ctx, 0, 3.5, 'left');
    this._goal(ctx, 8, 3.5, 'right');

    // Team labels
    ctx.font = 'bold 11px system-ui';
    ctx.textAlign = 'center';
    ctx.globalAlpha = 0.4;
    ctx.fillStyle = HOME_COLOR;
    const [hl, hly] = this.toCanvas(0.5, 0.3);
    ctx.fillText('HOME', hl, hly);
    ctx.fillStyle = AWAY_COLOR;
    const [al, aly] = this.toCanvas(7.5, 0.3);
    ctx.fillText('AWAY', al, aly);
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
    const goalH = 2.0 * CELL_H;
    const depth = 12;
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
      const [x, y] = this.toCanvas(p.position.row, p.position.column);
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

  render(snapshot, carrierId, flashEvent) {
    this.ctx.clearRect(0, 0, this.canvas.width / this.scale, this.canvas.height / this.scale);
    this.drawPitch();
    if (snapshot) {
      this.drawPlayers(snapshot.players, carrierId);
      this.drawBall(snapshot.ballPosition);
    }
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

    this.currentTick = 0;
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
    // Interpolation
    this._prevSnap = null;
    this._nextSnap = null;

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

    // ALL events from recorder — no filter
    const recorderEvents = (this.data.events || []).map(e => ({
      tick: e.tick,
      type: e.type,
      description: e.description || '',
      team: e.team || null,
      playerName: e.playerName || null,
      source: 'event'
    }));

    // ActionLogService entries — much richer detail (team, player, skill, outcome)
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

    // Merge: logs are primary (richer), events fill gaps
    const merged = new Map();
    for (const e of recorderEvents) merged.set(`e_${e.tick}_${e.type}`, e);
    for (const l of logEntries) merged.set(`l_${l.tick}_${l.type}`, l);
    this.events = [...merged.values()].sort((a, b) => a.tick - b.tick);

    // Detect goals from snapshot goalCount
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

    // Inject synthetic GOAL events
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

    const ticksPerSec = 40 * this.speed;
    this._tickAccum += dt * ticksPerSec;
    const advance = Math.floor(this._tickAccum);
    this._tickAccum -= advance;

    const fromTick = this.currentTick;
    this.currentTick += advance;

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
    const snap = this._findSnapshot(this.currentTick);
    const carrierId = snap?.ballCarrierId || null;
    this.pitch.render(snap, carrierId, this._flashEvent);
    this._updateScoreboard();
    this._updateSeek();
  }

  _findSnapshot(tick) {
    if (!this.snapshots.length) return null;
    let lo = 0, hi = this.snapshots.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (this.snapshots[mid].tick <= tick) lo = mid;
      else hi = mid - 1;
    }
    return this.snapshots[lo];
  }

  /* ─── UI updates ─── */
  _updateScoreboard() {
    const snap = this._findSnapshot(this.currentTick);
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

    // Auto-scroll only if near bottom
    const nearBottom = ul.scrollHeight - ul.scrollTop - ul.clientHeight < 100;
    if (nearBottom) ul.scrollTop = ul.scrollHeight;
  }

  _buildTimeline() {
    const ul = document.getElementById('timeline');
    ul.innerHTML = '';
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
      const speeds = [0.25, 0.5, 1, 2, 4, 8];
      speedSlider.max = speeds.length - 1;
      speedSlider.value = 2;
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
