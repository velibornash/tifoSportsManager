/**
 * TIFO Proposal Engine — Match Viewer
 *
 * Parses the proposal engine log format: [mm:ss|TAG] message
 * Renders horizontal pitch with event timeline.
 * Field: HOME left (row 1) attacks → right (row 7). AWAY right (row 7) attacks → left (row 1).
 * Playing field: rows 1-7, cols 1-6. Center: 4.5, 4.0.
 */

const TICKS_PER_MINUTE = 40;
const HOME_COLOR = '#539bf5';
const AWAY_COLOR = '#f97583';
const GK_COLOR = '#f0b429';
const BALL_COLOR = '#ffffff';
const BALL_SHADOW = 'rgba(0,0,0,.35)';
const PITCH_GREEN = '#3a7d44';
const PITCH_OOB = '#2d6a35';
const PITCH_STRIPE = 'rgba(255,255,255,.04)';
const PITCH_LINE = 'rgba(255,255,255,.55)';

const GRID_ROWS = 10;
const GRID_COLS = 9;
const FIELD_ROW_MIN = 1.0;
const FIELD_ROW_MAX = 8.0;
const FIELD_COL_MIN = 1.0;
const FIELD_COL_MAX = 7.0;
const CELL_W = 196;
const CELL_H = 120;

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
function lerp(a, b, t) { return a + (b - a) * t; }
function tickToMinute(tick) {
  const totalSec = Math.floor(tick / TICKS_PER_MINUTE * 60);
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${String(min).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
}

/** Parse a proposal log line: [mm:ss|TAG] message */
function parseLogLine(line) {
  const m = line.match(/^\[(\d+):(\d+)\|([A-Z_]+)\]\s*(.*)$/);
  if (!m) return null;
  const [, min, sec, tag, msg] = m;
  const tick = (parseInt(min) * 60 + parseInt(sec)) * TICKS_PER_MINUTE;
  return { tick, tag, msg };
}

/** Parse player reference like H10(STL) or A1(GK) */
function parsePlayerRef(ref) {
  if (!ref) return null;
  const m = ref.match(/^([HA])(\d+)\(([A-Z]{2,3})\)$/);
  if (!m) return null;
  const [, teamChar, num, role] = m;
  return { team: teamChar === 'H' ? 'HOME' : 'AWAY', label: ref, role, num: parseInt(num) };
}

/** Parse ball position from message: ball(r,c) */
function parseBallPos(msg) {
  const m = msg.match(/ball\(([^)]+)\)/);
  if (!m) return null;
  const [r, c] = m[1].split(',').map(parseFloat);
  return { row: r, col: c };
}

/** Parse player position: Label(r,c) */
function parsePlayerPos(msg, label) {
  const escaped = label.replace(/[()]/g, '\\$&');
  const m = msg.match(new RegExp(escaped + '\\s*\\(([^)]+)\\)'));
  if (!m) return null;
  const [r, c] = m[1].split(',').map(parseFloat);
  return { row: r, col: c };
}

const EV_ICON = {
  GOAL: '⚽', SHOT: '🎯', SAVE: '🧤', BLOCK: '🛡️', POST: '🥅',
  PASS: '➡️', RECEIVE: '📥', INTERCEPT: '🚫', DEFLECT: '💥',
  CARRY: '🏃', DUEL: '⚔️', LOOSE: '💨',
  OOB: '📤', RESTART: '🔄', KICKOFF: '🎬',
  DEC: '🧠', TAC: '📋', RST: '🔄', BAL: '⚽', DUL: '⚔️', ORC: '📋',
  LCH: '🚀'
};

const TIMELINE_TAGS = new Set([
  'GOAL', 'SHOT', 'SAVE', 'BLOCK', 'POST',
  'PASS', 'RECEIVE', 'INTERCEPT', 'DEFLECT',
  'CARRY', 'DUEL', 'LOOSE',
  'OOB', 'RESTART', 'KICKOFF',
  'DEC', 'TAC', 'RST', 'DUL', 'ORC', 'LCH'
]);

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

    ctx.fillStyle = '#0e1117';
    ctx.fillRect(0, 0, canvasW, canvasH);

    ctx.fillStyle = PITCH_OOB;
    ctx.fillRect(ox, oy, pw, ph);

    const [fx1, fy1] = this.toCanvas(FIELD_ROW_MIN, FIELD_COL_MIN);
    const [fx2, fy2] = this.toCanvas(FIELD_ROW_MAX, FIELD_COL_MAX);
    ctx.fillStyle = PITCH_GREEN;
    ctx.fillRect(fx1, fy1, fx2 - fx1, fy2 - fy1);

    for (let r = FIELD_ROW_MIN; r < FIELD_ROW_MAX; r += 2) {
      const [sx] = this.toCanvas(r, 0);
      const [ex] = this.toCanvas(r + 1, 0);
      ctx.fillStyle = PITCH_STRIPE;
      ctx.fillRect(sx, fy1, ex - sx, fy2 - fy1);
    }

    ctx.strokeStyle = PITCH_LINE;
    ctx.lineWidth = 2;
    ctx.strokeRect(fx1, fy1, fx2 - fx1, fy2 - fy1);

    const [cx] = this.toCanvas(4.5, 0);
    ctx.beginPath(); ctx.moveTo(cx, fy1); ctx.lineTo(cx, fy2); ctx.stroke();

    const [ccx, ccy] = this.toCanvas(4.5, 4.0);
    const circleR = 0.55 * CELL_W;
    ctx.beginPath();
    ctx.ellipse(ccx, ccy, circleR, circleR * (CELL_H / CELL_W), 0, 0, Math.PI * 2);
    ctx.stroke();
    ctx.fillStyle = PITCH_LINE;
    ctx.beginPath(); ctx.arc(ccx, ccy, 3, 0, Math.PI * 2); ctx.fill();

    // Boxes
    this._box(ctx, 1.0, 1.7, 2.2, 6.3);
    this._box(ctx, 6.8, 1.7, 8.0, 6.3);
    this._box(ctx, 1.0, 2.7, 1.4, 5.3);
    this._box(ctx, 7.6, 2.7, 8.0, 5.3);

    // Penalty spots
    const [p1x, p1y] = this.toCanvas(1.8, 4.0);
    const [p2x, p2y] = this.toCanvas(7.2, 4.0);
    ctx.fillStyle = PITCH_LINE;
    ctx.beginPath(); ctx.arc(p1x, p1y, 3, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(p2x, p2y, 3, 0, Math.PI * 2); ctx.fill();

    // Goals - center at 4.0, width 3.5-4.5
    this._goal(ctx, 1, 4.0, 'left');
    this._goal(ctx, 8, 4.0, 'right');

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

    // Attack direction arrows
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
    const goalH = 1.4 * CELL_H;
    const depth = 80;
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

  drawPlayers(players, carrierId) {
    const ctx = this.ctx;
    for (const p of players) {
      const [x, y] = this.toCanvas(p.row, p.col);
      const isHome = p.team === 'HOME';
      const isGK = p.role === 'GK';
      const isCarrier = carrierId && p.id === carrierId;
      const r = isGK ? 18 : 14;

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
    const [x, y] = this.toCanvas(pos.row, pos.col);
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

  render(players, ballPos, carrierId) {
    this.ctx.clearRect(0, 0, this.canvas.width / this.scale, this.canvas.height / this.scale);
    this.drawPitch();
    if (players) this.drawPlayers(players, carrierId);
    if (ballPos) this.drawBall(ballPos);
  }
}

/* ═══════════════════════════════════════════════════════════════
   MATCH VIEWER
   ═══════════════════════════════════════════════════════════════ */
class MatchViewer {
  constructor() {
    this.pitch = new PitchRenderer(document.getElementById('pitch'));
    this.logs = [];
    this.currentTick = 0;
    this.startTick = 0;
    this.endTick = 0;
    this.playing = false;
    this.speed = 1;
    this._lastFrame = 0;
    this._tickAccum = 0;
    this._rafId = null;
    this._displayedLogIdx = 0;
    this.homeTeam = 'Home FC';
    this.awayTeam = 'Away United';
    this.homeScore = 0;
    this.awayScore = 0;
    this._lastKnownPlayers = [];
    this._lastKnownBall = null;
    this._lastCarrierId = null;

    this._bindControls();
    this._showEmpty();
  }

  _bindControls() {
    document.getElementById('simBtn').onclick = () => this.generateMatch();
    document.getElementById('playMatchBtn').onclick = () => this.loadMatch();
    document.getElementById('playBtn').onclick = () => this.play();
    document.getElementById('pauseBtn').onclick = () => this.pause();
    document.getElementById('seek').oninput = (e) => this.seek(parseFloat(e.target.value));
    document.getElementById('speedSlider').oninput = (e) => {
      this.speed = Math.pow(2, parseFloat(e.target.value) - 2);
      document.getElementById('speedLabel').textContent = this.speed.toFixed(2) + 'x';
    };
    document.getElementById('simBtn2').onclick = () => this.generateMatch();
    document.getElementById('fileBtn').onclick = () => document.getElementById('fileInput').click();
    document.getElementById('fileInput').onchange = (e) => this.loadFromFile(e.target.files[0]);
    document.getElementById('tickerToggle')?.onclick = () => this._toggleSidebar();
  }

  _showEmpty(show = true) {
    document.getElementById('emptyState').style.display = show ? 'flex' : 'none';
    document.getElementById('pitch').parentElement.style.display = show ? 'none' : 'block';
    document.querySelector('.sidebar').style.display = show ? 'none' : 'block';
    document.getElementById('liveTicker').style.display = 'none';
  }

  _showLoading(show, text = 'Loading...') {
    document.getElementById('loading').classList.toggle('hidden', !show);
    document.getElementById('loadingText').textContent = text;
  }

  async generateMatch() {
    this._showLoading(true, 'Simulating match...');
    try {
      const res = await fetch('/api/proposal/generate', { method: 'POST' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const mres = await fetch('/proposal/match.json?' + Date.now());
      if (!mres.ok) throw new Error('match.json not found');
      const data = await mres.json();
      this._loadData(data);
    } catch (e) {
      alert('Failed: ' + e.message);
    } finally {
      this._showLoading(false);
    }
  }

  async loadMatch() {
    this._showLoading(true, 'Loading match...');
    try {
      const res = await fetch('/proposal/match.json?' + Date.now());
      if (!res.ok) throw new Error('No match.json — generate first');
      const data = await res.json();
      this._loadData(data);
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
        this._loadData(JSON.parse(reader.result));
      } catch (e) { alert('Invalid JSON: ' + e.message); }
    };
    reader.readAsText(file);
  }

  _loadData(data) {
    this.logs = data.events || data.logs || [];
    this.homeTeam = data.homeTeamName || 'Home FC';
    this.awayTeam = data.awayTeamName || 'Away United';
    this.homeScore = data.homeGoals || 0;
    this.awayScore = data.awayGoals || 0;

    // Parse all logs
    this.parsedLogs = this.logs
      .map(l => parseLogLine(l))
      .filter(l => l !== null)
      .sort((a, b) => a.tick - b.tick);

    if (this.parsedLogs.length === 0) {
      // Fallback: use tick from event count
      this.parsedLogs = this.logs.map((l, i) => ({ tick: i * 10, tag: 'EVT', msg: l }));
    }

    this.startTick = this.parsedLogs[0]?.tick || 0;
    this.endTick = this.parsedLogs[this.parsedLogs.length - 1]?.tick || 3600;
    this.currentTick = this.startTick;
    this._displayedLogIdx = 0;

    // Extract initial players from first log with positions
    this._extractInitialState();

    document.getElementById('homeName').textContent = this.homeTeam;
    document.getElementById('awayName').textContent = this.awayTeam;
    this._updateScoreboard();
    this._buildTimeline();
    this._showEmpty(false);
    this.pitch._resize();
    this._renderFrame();
  }

  _extractInitialState() {
    // Find first log with player positions
    for (const log of this.parsedLogs) {
      if (log.msg.includes('HOME :') || log.msg.includes('AWAY :')) {
        this._lastKnownPlayers = this._parsePlayersFromLog(log.msg);
        if (log.msg.includes('ball(')) {
          this._lastKnownBall = parseBallPos(log.msg);
        }
        break;
      }
    }
    // If no kickoff log, try to find any log with positions
    if (this._lastKnownPlayers.length === 0) {
      for (const log of this.parsedLogs) {
        if (log.msg.includes('(') && log.msg.includes(')')) {
          const players = this._parsePlayersFromLog(log.msg);
          if (players.length > 0) {
            this._lastKnownPlayers = players;
            break;
          }
        }
      }
    }
    // Fallback: create default positions
    if (this._lastKnownPlayers.length === 0) {
      this._createDefaultPlayers();
    }
  }

  _parsePlayersFromLog(msg) {
    // Parse format: "HOME : H1(GK)(1.5,3.5) H2(DL)(3.5,2.5) ..."
    const players = [];
    const teamParts = msg.split('AWAY :');
    const homePart = teamParts[0].replace('HOME :', '').trim();
    const awayPart = teamParts.length > 1 ? teamParts[1].trim() : '';

    const parseTeam = (part, team) => {
      const regex = /([HA]\d+)\(([A-Z]{2,3})\)\(([\d.]+),([\d.]+)\)/g;
      let m;
      while ((m = regex.exec(part)) !== null) {
        const [, label, role, row, col] = m;
        players.push({
          id: label,
          label: label,
          team: team,
          role: role,
          row: parseFloat(row),
          col: parseFloat(col)
        });
      }
    };

    parseTeam(homePart, 'HOME');
    parseTeam(awayPart, 'AWAY');
    return players;
  }

  _createDefaultPlayers() {
    // Minimal fallback
    this._lastKnownPlayers = [
      { id: 'H1', label: 'H1', team: 'HOME', role: 'GK', row: 1.5, col: 3.5 },
      { id: 'H10', label: 'H10', team: 'HOME', role: 'STL', row: 6.5, col: 2.5 },
      { id: 'H11', label: 'H11', team: 'HOME', role: 'STR', row: 6.5, col: 4.5 },
      { id: 'A1', label: 'A1', team: 'AWAY', role: 'GK', row: 7.5, col: 3.5 },
      { id: 'A10', label: 'A10', team: 'AWAY', role: 'STL', row: 2.5, col: 2.5 },
      { id: 'A11', label: 'A11', team: 'AWAY', role: 'STR', row: 2.5, col: 4.5 }
    ];
  }

  _updateScoreboard() {
    document.getElementById('homeScore').textContent = this.homeScore;
    document.getElementById('awayScore').textContent = this.awayScore;
    document.getElementById('clock').textContent = tickToMinute(this.currentTick);
    const status = this.playing ? 'LIVE' : 'PAUSED';
    document.getElementById('statusLabel').textContent = status;
  }

  _buildTimeline() {
    const ul = document.getElementById('timeline');
    ul.innerHTML = '';
    for (const log of this.parsedLogs) {
      if (!TIMELINE_TAGS.has(log.tag)) continue;
      const li = document.createElement('li');
      li.dataset.tick = log.tick;
      const icon = EV_ICON[log.tag] || '📝';
      li.innerHTML = `<span class="event-time">${tickToMinute(log.tick)}</span>
        <span class="event-icon">${icon}</span>
        <span class="event-desc">${log.msg.substring(0, 100)}</span>`;
      if (log.tick <= this.currentTick) li.classList.add('past');
      ul.appendChild(li);
    }
    ul.scrollTop = ul.scrollHeight;
  }

  _addTimelineEvent(log) {
    if (!TIMELINE_TAGS.has(log.tag)) return;
    const ul = document.getElementById('timeline');
    const li = document.createElement('li');
    li.dataset.tick = log.tick;
    const icon = EV_ICON[log.tag] || '📝';
    li.innerHTML = `<span class="event-time">${tickToMinute(log.tick)}</span>
      <span class="event-icon">${icon}</span>
      <span class="event-desc">${log.msg.substring(0, 100)}</span>`;
    if (log.tick <= this.currentTick) li.classList.add('past');
    ul.appendChild(li);
    ul.scrollTop = ul.scrollHeight;
  }

  _updateSeekRange() {
    const seek = document.getElementById('seek');
    seek.max = this.endTick;
    seek.value = this.currentTick;
  }

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
    this._displayedLogIdx = 0;
    while (this._displayedLogIdx < this.parsedLogs.length &&
           this.parsedLogs[this._displayedLogIdx].tick <= this.currentTick) {
      this._displayedLogIdx++;
    }
    this._renderFrame();
  }

  _loop() {
    if (!this.playing) return;
    const now = performance.now();
    const dt = (now - this._lastFrame) / 1000;
    this._lastFrame = now;

    const ticksPerSec = 1.875 * this.speed;
    this._tickAccum += dt * ticksPerSec;
    const fromTick = this.currentTick;
    this.currentTick += this._tickAccum;
    this._tickAccum = 0;

    if (this.currentTick >= this.endTick) {
      this.currentTick = this.endTick;
      this.pause();
    }

    // Process events
    while (this._displayedLogIdx < this.parsedLogs.length &&
           this.parsedLogs[this._displayedLogIdx].tick <= this.currentTick) {
      const log = this.parsedLogs[this._displayedLogIdx];
      this._processLog(log);
      this._addTimelineEvent(log);
      this._displayedLogIdx++;
    }

    // Update carrier/ball from logs if we can
    this._updateStateFromLogs(fromTick, this.currentTick);

    this._renderFrame();
    this._updateScoreboard();
    this._rafId = requestAnimationFrame(() => this._loop());
  }

  _processLog(log) {
    // Update scores from GOAL
    if (log.msg.includes('GOAL') && log.msg.includes('score')) {
      const m = log.msg.match(/score\s+(\d+):(\d+)/);
      if (m) {
        this.homeScore = parseInt(m[1]);
        this.awayScore = parseInt(m[2]);
      }
    }
  }

  _updateStateFromLogs(fromTick, toTick) {
    // Look at recent logs to update player positions
    for (let i = this._displayedLogIdx - 1; i >= 0; i--) {
      const log = this.parsedLogs[i];
      if (log.tick < fromTick) break;

      // Update ball position
      if (log.msg.includes('ball(')) {
        const ball = parseBallPos(log.msg);
        if (ball) this._lastKnownBall = ball;
      }

      // Update player positions
      const playerPosRegex = /([HA]\d+)\(([A-Z]{2,3})\)\(([\d.]+),([\d.]+)\)/g;
      let m;
      while ((m = playerPosRegex.exec(log.msg)) !== null) {
        const [, label, role, row, col] = m;
        const p = this._lastKnownPlayers.find(p => p.label === label);
        if (p) {
          p.row = parseFloat(row);
          p.col = parseFloat(col);
        }
      }

      // Track carrier from DECISION logs
      if (log.tag === 'DEC' && log.msg.includes('->')) {
        const carrierMatch = log.msg.match(/DECISION\s+([HA]\d+)\([A-Z]{2,3}\)/);
        if (carrierMatch) {
          this._lastCarrierId = carrierMatch[1];
        }
      }
    }
  }

  _renderFrame() {
    // Interpolate player positions based on current tick vs log density
    // For simplicity, just use last known positions
    this.pitch.render(this._lastKnownPlayers, this._lastKnownBall, this._lastCarrierId);
  }

  _toggleSidebar() {
    const sidebar = document.querySelector('.sidebar');
    sidebar.style.display = sidebar.style.display === 'none' ? 'block' : 'none';
  }
}

/* ═══════════════════════════════════════════════════════════════
   INIT
   ═══════════════════════════════════════════════════════════════ */
document.addEventListener('DOMContentLoaded', () => {
  window.matchViewer = new MatchViewer();
});

// Keyboard shortcuts
document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT') return;
  if (e.code === 'Space') { e.preventDefault(); window.matchViewer?.playing ? window.matchViewer.pause() : window.matchViewer.play(); }
  if (e.code === 'ArrowLeft') { window.matchViewer?.seek(Math.max(0, window.matchViewer.currentTick - 40)); }
  if (e.code === 'ArrowRight') { window.matchViewer?.seek(window.matchViewer.currentTick + 40); }
});