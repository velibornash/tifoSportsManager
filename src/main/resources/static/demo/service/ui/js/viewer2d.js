/**
 * TIFO Demo Service — Match Viewer 2D (human figures)
 *
 * Parallel viewer: identical playback/data logic to viewer.js, but players are
 * drawn as animated human figures (jersey, running, kicking, sliding) instead
 * of circles. Home = black jersey, Away = white jersey, GK = gold.
 *
 * This file is fully self-contained — it does NOT depend on viewer.js. The two
 * viewers can coexist: index.html loads viewer.js, index2d.html loads this.
 *
 * Horizontal pitch: HOME left (row 1), attacks left→right (rows 1→8).
 * AWAY right (row 8), attacks right→left (rows 8→1).
 * Playing field: rows 1-8, cols 1-7. Rows 0 + 8+, cols 0 + 7+ = out-of-bounds.
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
// Grid: 10 rows (0–9) x 9 cols (0–8). Playing field = rows 1–8, cols 1–7.
const GRID_ROWS = 10;          // 0..9 (matches FIELD_ROW_MAX 8.0)
const GRID_COLS = 9;           // 0..8 (matches FIELD_COL_MAX 7.0)
const FIELD_ROW_MIN = 1.0;     // home goal line
const FIELD_ROW_MAX = 8.0;     // away goal line
const FIELD_COL_MIN = 1.0;     // gornja touchline
const FIELD_COL_MAX = 7.0;     // donja touchline
const CELL_W = 196;  // 126 * 1.3 — extended 30%
const CELL_H = 120;  // 80 * 1.4

/* ═══════════════════════════════════════════════════════════════
   HUMAN FIGURE CONSTANTS
   ═══════════════════════════════════════════════════════════════ */

// Canonical skeleton proportions (before scaling). Root = feet centre at (0,0),
// facing +X. The figure is translated to the player position and rotated to
// face the movement direction.
const FIG_BODY = {
  hipY: -24,
  torsoLen: 11,      // hip → shoulder
  headOffset: 7.5,   // shoulder → head centre
  headR: 5.2,
  wTop: 5.2,         // shoulder half-width (torso)
  wBot: 2.6,         // hip half-width (torso)
  thigh: 12,
  shin: 13,
  armUp: 9,
  armLo: 8,
};

// Figure rendering height in screen px (target ~35 px).
const FIGURE_H = 36;
const FIGURE_SCALE = FIGURE_H / (Math.abs(FIG_BODY.hipY) + FIG_BODY.torsoLen + FIG_BODY.headOffset + FIG_BODY.headR);

const SKIN = '#e8b98a';
const SKIN_DARK = '#c99a6c';
const SHOE = '#1e1e22';
const HAIR = '#3a2a20';

// Team jerseys — HOME black, AWAY white, GK gold.
const JERSEYS = {
  HOME: {
    shirt: '#262b33',
    shirtTrim: 'rgba(255,255,255,.30)',
    shorts: '#14181e',
    number: '#ffffff',
  },
  AWAY: {
    shirt: '#ededed',
    shirtTrim: 'rgba(20,24,30,.45)',
    shorts: '#2c3036',
    number: '#101317',
  },
  GK: {
    shirt: '#f0b429',
    shirtTrim: 'rgba(0,0,0,.35)',
    shorts: '#353940',
    number: '#1b1c20',
  },
};

// Limb angle convention: 0 = pointing DOWN (toward feet), positive = forward
// (+X = facing). Torso lean: 0 = upright, positive = leaning forward.
const POSES = {
  idle: {
    torso: 0,
    legL: { thigh: 0, shin: 0 },
    legR: { thigh: 0, shin: 0 },
    armL: { sh: 0, elb: -6 },
    armR: { sh: 0, elb: 6 },
  },
  keeperIdle: {
    torso: 10,
    legL: { thigh: 14, shin: 26 },
    legR: { thigh: -14, shin: 26 },
    armL: { sh: -35, elb: -40 },
    armR: { sh: 35, elb: -40 },
  },
  walkA: {
    torso: 6,
    legL: { thigh: 24, shin: 12 },
    legR: { thigh: -18, shin: -8 },
    armL: { sh: -22, elb: -28 },
    armR: { sh: 24, elb: -28 },
  },
  walkB: {
    torso: 6,
    legL: { thigh: -18, shin: -8 },
    legR: { thigh: 24, shin: 12 },
    armL: { sh: 24, elb: -28 },
    armR: { sh: -22, elb: -28 },
  },
  runA: {
    torso: 16,
    legL: { thigh: 48, shin: 22 },
    legR: { thigh: -38, shin: -18 },
    armL: { sh: -48, elb: -70 },
    armR: { sh: 52, elb: -70 },
  },
  runB: {
    torso: 16,
    legL: { thigh: -38, shin: -18 },
    legR: { thigh: 48, shin: 22 },
    armL: { sh: 52, elb: -70 },
    armR: { sh: -48, elb: -70 },
  },
  kick: {
    torso: -8,
    legL: { thigh: 0, shin: 0 },      // planted leg
    legR: { thigh: 74, shin: 46 },    // striking leg
    armL: { sh: 55, elb: -35 },       // balance arm forward
    armR: { sh: -30, elb: -60 },      // balance arm back
  },
  slide: {
    torso: 72,                        // near-horizontal body
    legL: { thigh: 80, shin: 74 },
    legR: { thigh: 86, shin: 80 },
    armL: { sh: -72, elb: -45 },
    armR: { sh: -72, elb: -45 },
  },
  celebrate: {
    torso: -6,
    legL: { thigh: 0, shin: 2 },
    legR: { thigh: 0, shin: 2 },
    armL: { sh: 168, elb: -20 },      // arms overhead
    armR: { sh: -168, elb: -20 },
  },
};

// Motion thresholds (cells per match-tick).
const MOVE_IDLE_TH = 0.006;   // below → standing still
const MOVE_WALK_RUN_TH = 0.05; // above → running, else walking
// Full A→B→A stride cycle covered over this many match-cells of ground covered.
const STRIDE_DISTANCE = 0.4;

/* ═══════════════════════════════════════════════════════════════
   HELPERS
   ═══════════════════════════════════════════════════════════════ */
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
function lerp(a, b, t) { return a + (b - a) * t; }
function rad(d) { return d * Math.PI / 180; }

// One grid cell is 14 m x 10 m on the real pitch. Offside margins come from
// the engine in cells; convert to meters for the overlay ("0.50 cell, 7 m").
const OFFISIDE_CELL_METERS = 14;
function formatOffsideMargin(raw) {
  const v = parseFloat(raw);
  if (!isFinite(v)) return '';
  const meters = Math.round(v * OFFISIDE_CELL_METERS);
  return `${v.toFixed(2)} cell, ${meters} m`;
}

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
   FIGURE GEOMETRY HELPERS
   ═══════════════════════════════════════════════════════════════ */

// Lima extremity from origin. Angle convention: 0 = down, positive = forward.
function limbEnd(origin, angle, len) {
  return { x: origin.x + Math.sin(angle) * len, y: origin.y + Math.cos(angle) * len };
}

// Two-segment limb → ordered points [origin, joint, extremity].
function limbPoints(origin, a1, l1, a2, l2) {
  const k = limbEnd(origin, a1, l1);
  const e = limbEnd(k, a2, l2);
  return [[origin.x, origin.y], [k.x, k.y], [e.x, e.y]];
}

// Torso shoulder-centre from hip. lean 0 = straight up, positive = forward.
function torsoTop(hip, lean, len) {
  return { x: hip.x + Math.sin(lean) * len, y: hip.y - Math.cos(lean) * len };
}

// Draw one multi-segment limb. `segs` = [[p0,p1,width,color], ...].
function drawSegs(ctx, segs, alpha, widthFactor) {
  for (const s of segs) {
    ctx.beginPath();
    ctx.strokeStyle = s.color;
    ctx.lineWidth = s.width * (widthFactor || 1);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.globalAlpha = alpha;
    ctx.moveTo(s.p0.x, s.p0.y);
    ctx.lineTo(s.p1.x, s.p1.y);
    ctx.stroke();
  }
  ctx.globalAlpha = 1;
}

// Interpolate two pose objects field-by-field (for A↔B run/walk frame blending).
function lerpPose(pa, pb, t) {
  const out = { torso: lerp(pa.torso, pb.torso, t) };
  for (const side of ['legL', 'legR', 'armL', 'armR']) {
    out[side] = {
      thigh: lerp(pa[side].thigh, pb[side].thigh, t),
      shin: lerp(pa[side].shin, pb[side].shin, t),
    };
  }
  return out;
}

/* ═══════════════════════════════════════════════════════════════
   PLAYER MOTION — per-player animation tracker
   ═══════════════════════════════════════════════════════════════ */
class PlayerMotion {
  constructor(id) {
    this.id = id;
    this.speed = 0;         // cells/match-tick (from snapshot delta)
    this.facing = 0;        // radians, canvas angle (+X = right)
    this.phase = 0;         // 0..1 stride cycle position
    this.lastTick = NaN;
    this.kickUntil = -1;
    this.slideUntil = -1;
    this.celebrateUntil = -1;
  }

  // Called once per rendered frame with the player's interpolated snapshot
  // velocity (vrow/vcol per match-tick) and target position.
  update(tick, vrow, vcol, targetRow, targetCol) {
    const dTick = isFinite(this.lastTick) ? Math.max(0, tick - this.lastTick) : 0;
    this.lastTick = tick;

    const v = Math.hypot(vrow || 0, vcol || 0);
    this.speed = v;

    if (v > MOVE_IDLE_TH) {
      const ang = Math.atan2(vcol || 0, vrow || 0);
      this.facing = ang;
      // Advance the stride cycle by the match-distance covered this frame.
      this.phase = (this.phase + (v * dTick) / STRIDE_DISTANCE) % 1;
    } else if (targetRow != null && targetCol != null) {
      const tx = targetRow - 0;
      const ty = targetCol - 0;
      if (Math.hypot(tx, ty) > 0.01) {
        this.facing = Math.atan2(ty, tx);
      }
    }
  }

  // Resolved pose at the current tick (event poses take priority).
  resolvePose(tick, isGK) {
    if (this.kickUntil >= tick) return POSES.kick;
    if (this.slideUntil >= tick) return POSES.slide;
    if (this.celebrateUntil >= tick) return POSES.celebrate;

    if (this.speed <= MOVE_IDLE_TH) {
      return isGK ? POSES.keeperIdle : POSES.idle;
    }
    if (this.speed < MOVE_WALK_RUN_TH) {
      const t = this.phase < 0.5 ? this.phase * 2 : (1 - this.phase) * 2;
      return lerpPose(POSES.walkA, POSES.walkB, clamp(t, 0, 1));
    }
    const t = this.phase < 0.5 ? this.phase * 2 : (1 - this.phase) * 2;
    return lerpPose(POSES.runA, POSES.runB, clamp(t, 0, 1));
  }

  setKick(tick, durTicks) { this.kickUntil = tick + durTicks; }
  setSlide(tick, durTicks) { this.slideUntil = tick + durTicks; }
  setCelebrate(tick, durTicks) { this.celebrateUntil = tick + durTicks; }
}

/* ═══════════════════════════════════════════════════════════════
   FIGURE RENDERER — draws one human player figure at a canvas position
   ═══════════════════════════════════════════════════════════════ */

// Draw a single player figure at canvas (x, y) = feet center.
// opts = { facing, pose, jersey, number, scale }
function drawFigure(ctx, x, y, opts) {
  const f = opts.facing || 0;
  const pose = opts.pose || POSES.idle;
  const jer = opts.jersey || JERSEYS.AWAY;
  const scale = opts.scale || FIGURE_SCALE;
  const hip = { x: 0, y: FIG_BODY.hipY };

  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(f);
  ctx.scale(scale, scale);

  // Ground shadow — keeps the figure "standing" on the pitch.
  ctx.fillStyle = 'rgba(0,0,0,.28)';
  ctx.beginPath();
  ctx.ellipse(0, 1.5, 11, 3.2, 0, 0, Math.PI * 2);
  ctx.fill();

  const lean = rad(pose.torso);
  const sh = torsoTop(hip, lean, FIG_BODY.torsoLen);

  // Limb geometry (angles in radians).
  const legL = limbPoints(
    { x: -2.2, y: hip.y }, rad(pose.legL.thigh), FIG_BODY.thigh, rad(pose.legL.shin), FIG_BODY.shin);
  const legR = limbPoints(
    { x: 2.2, y: hip.y }, rad(pose.legR.thigh), FIG_BODY.thigh, rad(pose.legR.shin), FIG_BODY.shin);
  const armL = limbPoints(
    { x: sh.x - 4.4, y: sh.y }, rad(pose.armL.sh), FIG_BODY.armUp, rad(pose.armL.elb), FIG_BODY.armLo);
  const armR = limbPoints(
    { x: sh.x + 4.4, y: sh.y }, rad(pose.armR.sh), FIG_BODY.armUp, rad(pose.armR.elb), FIG_BODY.armLo);

  const mkSegs = (pts, w1, c1, w2, c2) => [
    { p0: pts[0], p1: pts[1], width: w1, color: c1 },
    { p0: pts[1], p1: pts[2], width: w2, color: c2 },
  ];

  // (center→) Far arm and far leg first, slightly darker/slimmer for depth.
  drawSegs(ctx, mkSegs(armR, 4.2, jer.shirt, 3.6, SKIN), 0.66, 0.78);
  drawSegs(ctx, mkSegs(legR, 5.5, jer.shorts, 4.2, SKIN), 0.66, 0.82);

  // Torso (jersey).
  const shL = { x: sh.x - FIG_BODY.wTop, y: sh.y };
  const shR = { x: sh.x + FIG_BODY.wTop, y: sh.y };
  const hipL = { x: -FIG_BODY.wBot, y: hip.y };
  const hipR = { x: FIG_BODY.wBot, y: hip.y };
  ctx.beginPath();
  ctx.moveTo(shL.x, shL.y);
  ctx.lineTo(shR.x, shR.y);
  ctx.lineTo(hipR.x, hipR.y);
  ctx.lineTo(hipL.x, hipL.y);
  ctx.closePath();
  ctx.fillStyle = jer.shirt;
  ctx.fill();
  ctx.strokeStyle = jer.shirtTrim;
  ctx.lineWidth = 1.1;
  ctx.stroke();

  // Collar notch — tiny darker V at the top centre of the jersey.
  ctx.beginPath();
  ctx.moveTo(shL.x + 2, sh.y - 1.5);
  ctx.lineTo(sh.x, sh.y + 1.5);
  ctx.lineTo(shR.x - 2, sh.y - 1.5);
  ctx.strokeStyle = jer.shirtTrim;
  ctx.lineWidth = 0.8;
  ctx.stroke();

  // Head.
  const headC = torsoTop(sh, lean, FIG_BODY.headOffset);
  ctx.beginPath();
  ctx.arc(headC.x, headC.y, FIG_BODY.headR, 0, Math.PI * 2);
  ctx.fillStyle = SKIN;
  ctx.fill();
  ctx.strokeStyle = 'rgba(0,0,0,.25)';
  ctx.lineWidth = 0.8;
  ctx.stroke();

  // Hair — back half + crown.
  ctx.beginPath();
  ctx.arc(headC.x, headC.y, FIG_BODY.headR, rad(90), rad(270), false);
  ctx.arc(headC.x, headC.y, FIG_BODY.headR * 0.55, rad(270), rad(90), true);
  ctx.closePath();
  ctx.fillStyle = HAIR;
  ctx.fill();

  // (center→) Near leg and near arm in front.
  drawSegs(ctx, mkSegs(legL, 5.5, jer.shorts, 4.2, SKIN), 1, 1);
  drawSegs(ctx, mkSegs(armL, 4.2, jer.shirt, 3.6, SKIN), 1, 1);

  // Shoes — small dark ellipses at both feet.
  for (const seg of [legL, legR]) {
    const fx = seg[2][0], fy = seg[2][1];
    ctx.beginPath();
    ctx.ellipse(fx, fy, 3, 1.9, 0, 0, Math.PI * 2);
    ctx.fillStyle = SHOE;
    ctx.fill();
  }

  // Jersey number — counter-rotated so it stays upright on screen regardless
  // of facing (an AWAY player facing left must not show a mirrored number).
  if (opts.number) {
    const numCx = (sh.x + hip.x) / 2;
    const numCy = (sh.y + hip.y) / 2 + 1;
    ctx.save();
    ctx.translate(numCx, numCy);
    ctx.rotate(-f);
    ctx.font = 'bold 7px system-ui';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = jer.number;
    ctx.shadowColor = jer.shirt === '#ededed' ? 'rgba(0,0,0,.4)' : 'rgba(0,0,0,.5)';
    ctx.shadowBlur = 1.5;
    ctx.fillText(opts.number, 0, 0);
    ctx.restore();
  }

  ctx.restore();
}

/* ═══════════════════════════════════════════════════════════════
   EVENT ICONS & CLASSIFICATION (identical to viewer.js)
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
  'CROSS', 'CORNER', 'FREE_KICK', 'GOAL_KICK', 'THROW_IN', 'KICKOFF',
  'OFFSIDE',
  'VAR_OFFSIDE_CONFIRMED', 'VAR_OFFSIDE_OVERTURNED',
  'VAR_GOAL_CONFIRMED', 'VAR_GOAL_OVERTURNED',
  'VAR_RED_CONFIRMED', 'VAR_RED_OVERTURNED',
  'VAR_PENALTY_CONFIRMED', 'VAR_PENALTY_OVERTURNED',
  'YELLOW_CARD', 'RED_CARD',
  'DUEL_START', 'DUEL_RESOLVED', 'DUEL_WON', 'CHASE_POSSESSION', 'POSSESSION_CHANGE',
  'FOUL', 'CARD',
]);

const MINOR_EVENTS = new Set([
  'PASS', 'PASS_COMPLETED', 'PASS_LOOSE',
  'CARRY', 'CARRY_COMPLETED',
  // Per-tick chase progress logs ("CHASE: Home 7 dist=0.234") are intentionally
  // excluded — too noisy. CHASE_POSSESSION (the resolution event) IS shown.
  'CHASE',
  'DECISION', 'ACTION_EXECUTION', 'ACTION_OUTCOME',
  'INFO', 'RESTART', 'POSSESSION', 'VAR_IN_PROGRESS',
]);

// Compact timeline events — what the user actually wants to see at a glance
// in the side panel. Verbose engine logs are kept in the app log but NOT shown.
const TIMELINE_EVENTS = new Set([
  'PASS', 'PASS_COMPLETED', 'PASS_LOOSE',
  'CARRY', 'CARRY_COMPLETED',
  'GOAL', 'GOAL_DISALLOWED', 'SHOT', 'SHOT_SAVED', 'SHOT_MISSED', 'SHOT_BLOCKED', 'SHOT_POST',
  'PENALTY_KICK', 'PENALTY_MISS', 'PENALTY_SAVED',
  'CROSS', 'CORNER', 'FREE_KICK', 'GOAL_KICK', 'THROW_IN', 'KICKOFF',
  'OFFSIDE',
  'VAR_OFFSIDE_CONFIRMED', 'VAR_OFFSIDE_OVERTURNED',
  'VAR_GOAL_CONFIRMED', 'VAR_GOAL_OVERTURNED',
  'VAR_RED_CONFIRMED', 'VAR_RED_OVERTURNED',
  'VAR_PENALTY_CONFIRMED', 'VAR_PENALTY_OVERTURNED',
  'YELLOW_CARD', 'RED_CARD',
  'DUEL_START', 'DUEL_RESOLVED', 'DUEL_WON',
  'CHASE_POSSESSION',
  'POSSESSION_CHANGE',
  'FOUL', 'CARD',
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
   OVERLAY SYSTEM — Halftime, Fulltime, VAR, Goal (identical to viewer.js)
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
    this._blocking = false;  // true = pause playback while visible

    this._el.addEventListener('click', () => {
      if (this._active && this._type !== 'fulltime') {
        this.dismiss();
      }
    });
    document.addEventListener('keydown', (e) => {
      if (this._active && (e.key === 'Escape' || e.key === ' ')) {
        if (this._type !== 'fulltime') this.dismiss();
        e.preventDefault();
      }
    });
  }

  get isActive() { return this._active; }
  get isBlocking() { return this._blocking; }
  get resumeTime() { return this._resumeTime; }

  showHalftime(homeName, awayName, homeScore, awayScore) {
    this._type = 'halftime';
    this._blocking = true;
    this._textEl.textContent = 'HALF TIME';
    this._subEl.textContent = `${homeName} ${homeScore} - ${awayScore} ${awayName}`;
    this._el.className = 'overlay visible halftime';
    this._active = true;
    this._resumeTime = performance.now() + 12000;
  }

  showFulltime(homeName, awayName, homeScore, awayScore) {
    this._type = 'fulltime';
    this._blocking = true;
    this._textEl.textContent = 'FULL TIME';
    this._subEl.textContent = `${homeName} ${homeScore} - ${awayScore} ${awayName}`;
    this._el.className = 'overlay visible fulltime';
    this._active = true;
    this._resumeTime = Infinity;
  }

  showVAR(reviewText, durationMs) {
    this._type = 'var';
    this._blocking = true;
    this._textEl.textContent = '\uD83D\uDCFA VAR IN PROGRESS';
    this._subEl.textContent = reviewText;
    this._el.className = 'overlay visible var-review';
    this._active = true;
    this._resumeTime = performance.now() + durationMs;
  }

  showVARDecision(decisionText) {
    this._type = 'var-decision';
    this._blocking = true;
    this._textEl.textContent = '\uD83D\uDCFA ' + decisionText;
    this._subEl.textContent = '';
    this._el.className = 'overlay visible var-decision';
    this._active = true;
    this._resumeTime = performance.now() + 2200;
  }

  showKickoff(homeName, awayName) {
    this._type = 'kickoff';
    this._blocking = true;
    this._textEl.textContent = 'KICK OFF';
    this._subEl.textContent = `${homeName} 0 - 0 ${awayName}`;
    this._el.className = 'overlay visible kickoff';
    this._active = true;
    this._resumeTime = performance.now() + 3000;
  }

  showGoal(team, homeName, awayName, homeScore, awayScore) {
    this._type = 'goal';
    this._blocking = true;
    this._textEl.textContent = '\u26BD GOAL!';
    this._subEl.textContent = `${team === 'HOME' ? homeName : awayName} ${homeScore} - ${awayScore} ${team === 'HOME' ? awayName : homeName}`;
    this._el.className = 'overlay visible goal';
    this._active = true;
    this._resumeTime = performance.now() + 6000;
    this._goalAnim = { team, startRealTime: performance.now() };
  }

  showOffside(playerName, team, margin) {
    this._type = 'offside';
    this._blocking = true;
    this._textEl.textContent = '\uD83D\uDEA9 OFFSIDE';
    const teamLabel = team || '';
    const marginText = margin ? ` (${formatOffsideMargin(margin)})` : '';
    this._subEl.textContent = `${playerName}${marginText} — ${teamLabel}`;
    this._el.className = 'overlay visible offside';
    this._active = true;
    this._resumeTime = performance.now() + 3500;
  }

  showGoalDisallowed(reason) {
    this._type = 'goal-disallowed';
    this._blocking = false;
    this._textEl.textContent = '\u26A0\uFE0F GOAL DISALLOWED';
    this._subEl.textContent = reason || '';
    this._el.className = 'overlay visible goal-disallowed';
    this._active = true;
    this._resumeTime = performance.now() + 4000;
    this._goalAnim = null;
  }

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
    this._resumeTime = performance.now() + 3000;
  }

  getGoalAnimProgress() {
    if (!this._goalAnim) return null;
    const elapsed = (performance.now() - this._goalAnim.startRealTime) / 6000;
    if (elapsed > 1) { this._goalAnim = null; return null; }
    return { team: this._goalAnim.team, t: clamp(elapsed, 0, 1) };
  }

  dismiss() {
    this._el.className = 'overlay';
    this._active = false;
    this._type = null;
    this._blocking = false;
    this._goalAnim = null;
  }

  checkAutoDismiss() {
    if (!this._active) return false;
    if (this._resumeTime === Infinity) return false;
    if (performance.now() >= this._resumeTime) {
      this.dismiss();
      return true;
    }
    return false;
  }
}

/* ═══════════════════════════════════════════════════════════════
   PITCH RENDERER 2D — same surface as PitchRenderer, players as figures
   ═══════════════════════════════════════════════════════════════ */
class PitchRenderer2D {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.margin = { top: 30, left: 40, right: 40, bottom: 30 };
    this.showGrid = true;
    this.currentTick = 0;
    this.motions = new Map();   // playerId → PlayerMotion
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

    // OOB area (darker green behind the field)
    ctx.fillStyle = PITCH_OOB;
    ctx.fillRect(ox, oy, pw, ph);

    // Playing field (rows 1-8, cols 1-7) — brighter green
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
      // Cell-center coordinate labels — disabled (clutter). Re-enable if needed.
      if (false) {
        ctx.font = '10px system-ui';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = 'rgba(255,255,255,.35)';
        for (let r = 1; r <= 8; r++) {
          for (let c = 1; c <= 7; c++) {
            const [cx2, cy2] = this.toCanvas(r + 0.5, c + 0.5);
            ctx.fillText(`${r}.${c}`, cx2, cy2);
          }
        }
        ctx.textBaseline = 'alphabetic';
      }
    }

    // Center line
    const [cx] = this.toCanvas(4.5, 0);
    ctx.beginPath();
    ctx.moveTo(cx, fy1);
    ctx.lineTo(cx, fy2);
    ctx.stroke();

    // Center circle (radius ~9.15m ≈ 0.55 rows)
    const [ccx, ccy] = this.toCanvas(4.5, 4.0);
    const circleR = 0.55 * CELL_W;
    ctx.beginPath();
    ctx.ellipse(ccx, ccy, circleR, circleR * (CELL_H / CELL_W), 0, 0, Math.PI * 2);
    ctx.stroke();
    ctx.fillStyle = PITCH_LINE;
    ctx.beginPath();
    ctx.arc(ccx, ccy, 3, 0, Math.PI * 2);
    ctx.fill();

    // Penalty boxes / goal areas
    this._box(ctx, 1.0, 1.7, 2.2, 6.3);
    this._box(ctx, 6.8, 1.7, 8.0, 6.3);
    this._box(ctx, 1.0, 2.7, 1.4, 5.3);
    this._box(ctx, 7.6, 2.7, 8.0, 5.3);

    // Penalty spots
    const [p1x, p1y] = this.toCanvas(1.0 + 0.8, 4.0);
    const [p2x, p2y] = this.toCanvas(8.0 - 0.8, 4.0);
    ctx.fillStyle = PITCH_LINE;
    ctx.beginPath(); ctx.arc(p1x, p1y, 3, 0, Math.PI * 2); ctx.fill();
    ctx.beginPath(); ctx.arc(p2x, p2y, 3, 0, Math.PI * 2); ctx.fill();

    // Goals
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

  _motionFor(p) {
    let m = this.motions.get(p.id);
    if (!m) {
      m = new PlayerMotion(p.id);
      this.motions.set(p.id, m);
    }
    return m;
  }

  drawPlayers(players, carrierId, duelPairs) {
    const ctx = this.ctx;

    // Duel participants as a quick-label lookup set.
    const duelLabels = new Set();
    if (duelPairs) {
      for (const pair of duelPairs) {
        if (pair[0]?.label) duelLabels.add(pair[0].label);
        if (pair[1]?.label) duelLabels.add(pair[1].label);
      }
    }

    // Duel convergence: pull both duelists close together so their figures
    // collide at the contest point (same "igraci se popnu jedan na drugog"
    // rule as the circle viewer — two figures overlapping = physical contact).
    const convergedPos = new Map();
    if (duelPairs) {
      for (const pair of duelPairs) {
        const pa = players.find(p => p.label === pair[0]?.label);
        const pb = players.find(p => p.label === pair[1]?.label);
        if (pa && pb && pa.id && pb.id) {
          const rA = 17, rB = 17;
          const dx = pb.col - pa.col;
          const dy = pb.row - pa.row;
          const dist = Math.hypot(dx, dy);
          if (dist > 0.001) {
            const MAX_CONVERGE_DIST = 2.5;
            if (dist > MAX_CONVERGE_DIST) continue;
            const overlapFactor = 0.92;
            const nx = -dy / dist;
            const ny = dx / dist;
            const jitter = 0.06;
            convergedPos.set(pa.id, {
              row: pa.row + dy * overlapFactor + nx * jitter,
              col: pa.col + dx * overlapFactor + ny * jitter,
            });
            convergedPos.set(pb.id, {
              row: pb.row - dy * overlapFactor - nx * jitter,
              col: pb.col - dx * overlapFactor - ny * jitter,
            });
          }
        }
      }
    }

    const cooldowns = this._duelState?.cooldowns;

    for (const p of players) {
      let pos = { row: p.row, col: p.col };
      if (convergedPos.has(p.id)) {
        pos = convergedPos.get(p.id);
      }
      const [x, y] = this.toCanvas(pos.row, pos.col);
      const isHome = p.team === 'HOME';
      const isGK = p.role === 'GK';
      const isCarrier = carrierId && p.id === carrierId;
      const inDuel = duelLabels.has(p.label);
      const inCooldown = cooldowns && cooldowns.has(p.label)
          && cooldowns.get(p.label) > Math.floor(this.currentTick);

      // Ground indicator rings (feet-level, so they read as "beneath" the figure).
      const groundY = y + 6;
      if (inDuel) {
        const pulse = 0.6 + 0.25 * Math.sin(performance.now() / 180 + (p.id?.length || 0));
        ctx.beginPath();
        ctx.arc(x, groundY, 16, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(255,220,60,${0.30 * pulse})`;
        ctx.fill();
        ctx.strokeStyle = `rgba(255,200,0,${pulse})`;
        ctx.lineWidth = 2.5;
        ctx.stroke();
      }
      if (inCooldown) {
        const pulse = 0.5 + 0.3 * Math.sin(performance.now() / 200);
        ctx.beginPath();
        ctx.arc(x, groundY, 14, 0, Math.PI * 2);
        ctx.strokeStyle = `rgba(248,81,73,${pulse})`;
        ctx.lineWidth = 2;
        ctx.stroke();
      }
      if (isCarrier) {
        ctx.beginPath();
        ctx.arc(x, groundY, 15, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,140,0,.28)';
        ctx.fill();
        ctx.strokeStyle = '#ff8c00';
        ctx.lineWidth = 2;
        ctx.stroke();
      }

      // Animation state: facing + stride driven by the snapshot velocity.
      const m = this._motionFor(p);
      m.update(
        this.currentTick,
        p.vrow || 0, p.vcol || 0,
        p.targetRow, p.targetCol
      );

      const pose = m.resolvePose(this.currentTick, isGK);
      const number = String(p.label || '').replace(/.*\s/, '');

      drawFigure(ctx, x, y, {
        facing: m.facing,
        pose,
        jersey: isGK ? JERSEYS.GK : (isHome ? JERSEYS.HOME : JERSEYS.AWAY),
        number: number || '',
        scale: FIGURE_SCALE * (isGK ? 1.1 : 1.0),
      });
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

    const goalRow = isHome ? 7 : 1;
    const [gx, gy] = this.toCanvas(goalRow, 3.5);

    const animThreshold = 0.7;
    let targetRow;
    if (t <= animThreshold) {
      targetRow = lerp(4, goalRow, t / animThreshold);
    } else {
      targetRow = goalRow;
    }
    const [ballX, ballY] = this.toCanvas(targetRow, 3.5);

    const ballScale = t <= animThreshold ? 5 + Math.sin((t / animThreshold) * Math.PI) * 3 : 8;
    ctx.beginPath();
    ctx.arc(ballX, ballY, ballScale + 8, 0, Math.PI * 2);
    ctx.fillStyle = `rgba(255,255,255,${0.3 * (1 - t)})`;
    ctx.fill();
    ctx.beginPath();
    ctx.arc(ballX, ballY, ballScale, 0, Math.PI * 2);
    ctx.fillStyle = '#fff';
    ctx.fill();

    if (t >= animThreshold) {
      const celebrationT = (t - animThreshold) / (1 - animThreshold);
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

  render(players, ballPos, carrierId, flashEvent, goalAnim, duelPairs, blockAnim) {
    this.ctx.clearRect(0, 0, this.canvas.width / this.scale, this.canvas.height / this.scale);
    this.drawPitch();
    if (players) this.drawPlayers(players, carrierId, duelPairs);
    if (ballPos) this.drawBall(ballPos);
    this.drawGoalAnim(goalAnim);
  }

  drawBlockTrail(blockAnim) { return; }
  drawBlockImpact(blockAnim) { return; }
}

/* ═══════════════════════════════════════════════════════════════
   MATCH VIEWER 2D — playback identical to viewer.js, renders via
   PitchRenderer2D + event-driven figure poses (kick / slide / celebrate)
   ═══════════════════════════════════════════════════════════════ */
class MatchViewer2D {
  constructor() {
    this.pitch = new PitchRenderer2D(document.getElementById('pitch'));
    this.overlays = new OverlayManager();
    this.snapshots = [];
    this.events = [];
    this.goals = [];
    this.data = null;

    this.currentTick = 0;
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

    this._blockAnim = null;
    this.showGrid = true;
    this._snapIndex = null;
    this._snapTicks = null;
    this._pendingTimelineEvents = [];
    this._labelToId = new Map();

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
      const homeName = this.data.homeTeamName || 'HOME';
      const awayName = this.data.awayTeamName || 'AWAY';
      this.overlays.showKickoff(homeName, awayName);
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

    this._snapIndex = new Map();
    this._snapTicks = [];
    this._labelToId = new Map();
    for (const s of this.snapshots) {
      this._snapIndex.set(s.tick, s);
      this._snapTicks.push(s.tick);
      for (const p of s.players || []) {
        if (p.label && p.id && !this._labelToId.has(p.label)) {
          this._labelToId.set(p.label, p.id);
        }
      }
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
    this._duelState = { pairs: [], currentTick: -1, resolved: new Set(), cooldowns: new Map() };

    document.getElementById('homeName').textContent = this.data.homeTeamName || 'HOME';
    document.getElementById('awayName').textContent = this.data.awayTeamName || 'AWAY';
    this._updateScoreboard();
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

    const prevIdx = lo - 1;
    const snapPrev = prevIdx >= 0 ? this._snapIndex.get(this._snapTicks[prevIdx]) : null;

    return { snap: snapA, next: snapB, prev: snapPrev, frac: clamp(frac, 0, 1), tickA, tickB };
  }

  // Interpolated player + per-tick velocity (for figure facing/stride) and
  // target position (for facing even when standing still).
  _getInterpolatedPlayers(interp) {
    if (!interp || !interp.next) {
      return (interp?.snap?.players || []).map(p => ({
        id: p.id, label: p.label, team: p.team, role: p.role,
        row: p.position.row, col: p.position.column,
        vrow: p.velocityX || 0, vcol: p.velocityY || 0,
        targetRow: p.target?.row, targetCol: p.target?.column,
      }));
    }
    const a = interp.snap;
    const b = interp.next;
    const t = interp.frac;
    const dt = Math.max(1, interp.tickB - interp.tickA);
    const bMap = new Map();
    for (const pb of b.players) bMap.set(pb.id, pb);
    const result = [];
    for (const pa of a.players) {
      const pb = bMap.get(pa.id);
      if (pb) {
        result.push({
          id: pa.id, label: pa.label, team: pa.team, role: pa.role,
          row: lerp(pa.position.row, pb.position.row, t),
          col: lerp(pa.position.column, pb.position.column, t),
          vrow: (pb.position.row - pa.position.row) / dt,
          vcol: (pb.position.column - pa.position.column) / dt,
          targetRow: pa.target?.row ?? pb.target?.row,
          targetCol: pa.target?.column ?? pb.target?.column,
        });
      } else {
        result.push({
          id: pa.id, label: pa.label, team: pa.team, role: pa.role,
          row: pa.position.row, col: pa.position.column,
          vrow: pa.velocityX || 0, vcol: pa.velocityY || 0,
          targetRow: pa.target?.row, targetCol: pa.target?.column,
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

    const out = this._isOutOfBounds(a);
    if (out) {
      const c = interp.prev && interp.prev.ballPosition ? interp.prev.ballPosition : null;
      let outPt = a;
      if (c) {
        const dr = a.row - c.row;
        const dc = a.column - c.column;
        const len = Math.hypot(dr, dc);
        if (len > 1e-6) {
          const roll = this._ballRollOutPoint(c, dr / len, dc / len);
          if (roll) outPt = roll;
        }
      }
      if (t < 0.5) {
        const s = (t / 0.5) * 0.35;
        return { row: lerp(a.row, outPt.row, s), column: lerp(a.column, outPt.column, s) };
      }
      const s = (t - 0.5) / 0.5;
      return { row: lerp(outPt.row, b.row, s), column: lerp(outPt.column, b.column, s) };
    }

    return { row: lerp(a.row, b.row, t), column: lerp(a.column, b.column, t) };
  }

  _isOutOfBounds(p) {
    return !p || p.row < FIELD_ROW_MIN || p.row > FIELD_ROW_MAX
      || p.column < FIELD_COL_MIN || p.column > FIELD_COL_MAX;
  }

  _ballRollOutPoint(p, dr, dc) {
    const rowMin = FIELD_ROW_MIN, rowMax = FIELD_ROW_MAX;
    const colMin = FIELD_COL_MIN, colMax = FIELD_COL_MAX;
    let tt = Infinity;
    if (dr > 1e-9) tt = Math.min(tt, (rowMax - p.row) / dr);
    else if (dr < -1e-9) tt = Math.min(tt, (rowMin - p.row) / dr);
    if (dc > 1e-9) tt = Math.min(tt, (colMax - p.column) / dc);
    else if (dc < -1e-9) tt = Math.min(tt, (colMin - p.column) / dc);
    if (!isFinite(tt) || tt <= 0) return { row: p.row, column: p.column };
    const outRow = p.row + dr * (tt + 0.15);
    const outCol = p.column + dc * (tt + 0.15);
    return { row: outRow, column: outCol };
  }

  _getCarrierId(interp) {
    if (!interp) return null;
    if (interp.frac > 0.5 && interp.next) return interp.next.ballCarrierId;
    return interp.snap.ballCarrierId;
  }

  /* ─── Figure action triggers (kick / slide / celebrate) ─── */

  _bid(label) { return label ? (this._labelToId.get(label) || null) : null; }

  _triggerFigureActions() {
    // Re-scan a short window of events around the playhead and apply visual
    // pose triggers. Kept cheap: only inspects events within the current frame.
    const intTick = Math.floor(this.currentTick);
    for (const ev of this.events) {
      if (ev.tick > intTick + 1) break;
      if (ev.tick < intTick - 8) continue;
      const dur = ev.type === 'GOAL' ? 8 : 2;
      if (ev.type === 'SHOT' || ev.type === 'PENALTY_KICK' || ev.type === 'PASS' || ev.type === 'CROSS') {
        const id = this._bid(ev.playerName) || (ev.playerId || null);
        if (id) this.pitch.motions.get(id)?.setKick(ev.tick, dur);
      } else if (ev.type === 'TACKLE' || ev.type === 'DUEL_RESOLVED') {
        const desc = ev.description || '';
        if (desc.includes('DEFENDER_WINS') || ev.type === 'TACKLE') {
          const id = this._bid(ev.playerName) || (ev.playerId || null);
          if (id) this.pitch.motions.get(id)?.setSlide(ev.tick, 3);
        }
      } else if (ev.type === 'GOAL') {
        const scorer = this._bid(ev.playerName) || (ev.playerId || null);
        if (scorer) this.pitch.motions.get(scorer)?.setCelebrate(ev.tick, 8);
      }
    }
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
    this._buildTimeline();
    this._renderFrame();
  }

  _loop() {
    if (!this.playing) return;
    const now = performance.now();
    const dt = (now - this._lastFrame) / 1000;
    this._lastFrame = now;

    if (this.overlays.isBlocking) {
      this.overlays.checkAutoDismiss();
      if (this.overlays.isBlocking) {
        this._renderFrame();
        this._rafId = requestAnimationFrame(() => this._loop());
        return;
      }
    }

    // 1.875 ticks/sec at 1x
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
    this._triggerFigureActions();
    this._renderFrame();
    this._rafId = requestAnimationFrame(() => this._loop());
  }

  _processEventsForTick(fromTick, toTick) {
    while (this._displayedEventIdx < this.events.length) {
      const ev = this.events[this._displayedEventIdx];
      if (ev.tick > toTick) break;
      if (ev.tick >= fromTick) {
        if (TIMELINE_EVENTS.has(ev.type)) {
          this._pendingTimelineEvents.push(ev);
        }
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
        if (ev.type === 'OFFSIDE') {
          const desc = ev.description || '';
          const playerName = desc.replace(/^.*offside\s+/i, '').replace(/\s*\(.*/, '').trim() || 'Player';
          const team = ev.team || '';
          const marginMatch = desc.match(/margin=([0-9.]+)/);
          const margin = marginMatch ? marginMatch[1] : '';
          this.overlays.showOffside(playerName, team, margin);
        }
        if (ev.type?.startsWith('VAR_OFFSIDE_') || ev.type?.startsWith('VAR_GOAL_')
            || ev.type?.startsWith('VAR_RED_') || ev.type?.startsWith('VAR_PENALTY_')
            || ev.type?.startsWith('VAR_YELLOW_')) {
          const decisionLabel = ev.type.includes('CONFIRMED') ? 'CONFIRMED' : 'OVERTURNED';
          const reviewType = ev.type.replace('VAR_', '').replace('_CONFIRMED', '').replace('_OVERTURNED', '').replace('_', ' ');
          let decisionText = `VAR ${reviewType}: ${decisionLabel}`;
          const marginMatch = (ev.description || '').match(/margin=([0-9.]+)/);
          if (marginMatch) decisionText += ` (${formatOffsideMargin(marginMatch[1])})`;
          this.overlays.showVARDecision(decisionText);
          this._varReviewQueued = false;
        }
        if (ev.type === 'GOAL_DISALLOWED') {
          this.overlays.showGoalDisallowed(ev.description || 'GOAL DISALLOWED');
        }
        if (ev.type === 'VAR_IN_PROGRESS') {
          if (!this._varReviewQueued) {
            this._varReviewQueued = true;
            this._varOverlayTick = ev.tick;
            this.overlays.showVAR(ev.description || 'Reviewing incident...', 3500);
            break;
          }
        }
        if (ev.type === 'SHOT_BLOCKED' || ev.type === 'SHOT_SAVED') {
          const prevSnap = this._snapIndex?.get(ev.tick - 1) || null;
          const curSnap = this._snapIndex?.get(ev.tick) || null;
          if (curSnap) {
            const prevCarrierId = prevSnap ? prevSnap.ballCarrierId || '' : '';
            let shooter = prevSnap
              ? prevSnap.players.find(p => p.id === prevCarrierId) || null
              : null;
            const srcList = (prevSnap ? prevSnap.players : [])
              .concat(curSnap ? curSnap.players : []);
            if (!shooter && ev.playerName && srcList.length) {
              shooter = srcList.find(p => p.label === ev.playerName) || null;
            }
            if (!shooter && ev.team) {
              shooter = srcList.find(p => p.team === ev.team) || null;
            }
            const shooterTeam = shooter ? shooter.team : ev.team;
            const blockTeam = shooterTeam === 'HOME' ? 'AWAY' : 'HOME';
            const defTeamPlayers = (curSnap.players || prevSnap.players).filter(p => p.team === blockTeam);
            const ref = curSnap.ballPosition || { row: 0, col: 0 };
            const dist = p => {
              const dr = p.position.row - ref.row;
              const dc = p.position.column - ref.column;
              return dr * dr + dc * dc;
            };
            let blocker = null;
            for (const p of defTeamPlayers) {
              if (!blocker || dist(p) < dist(blocker)) blocker = p;
            }
            if (shooter && blocker && curSnap.ballPosition) {
              this._blockAnim = {
                shooterPos: { row: shooter.position.row, col: shooter.position.column },
                blockerPos: { row: blocker.position.row, col: blocker.position.column },
                deflectPos: { row: curSnap.ballPosition.row, col: curSnap.ballPosition.column },
                startTick: ev.tick,
                defenderLabel: blocker.label,
                type: ev.type,
              };
            }
          }
        }
        if (ev.type === 'CARD' || ev.type === 'YELLOW_CARD' || ev.type === 'RED_CARD') {
          const isRed = ev.type === 'RED_CARD' || ev.description?.includes('RED');
          const cardType = isRed ? 'RED' : 'YELLOW';
          const desc = ev.description || '';
          const teamFromEv = ev.team || (desc.includes('HOME') ? 'HOME' : 'AWAY');
          const nameMatch = desc.match(/(?:HOME|AWAY)\s+(\w+\s+\d+)/);
          const playerName = ev.playerName || (nameMatch ? nameMatch[1] : 'Player');
          const teamLabel = teamFromEv === 'HOME'
            ? (this.data.homeTeamName || 'HOME')
            : (this.data.awayTeamName || 'AWAY');
          const isVar = desc.includes('VAR') || ev.channel?.includes('VAR');
          this.overlays.showCard(cardType, playerName, teamLabel, !!isVar);
        }
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
    if (this._blockAnim) {
      const ageTicks = this.currentTick - this._blockAnim.startTick;
      if (ageTicks > 8 || this.currentTick < this._blockAnim.startTick) {
        this._blockAnim = null;
      }
    }
    this._updateDuelState(this.currentTick);
  }

  _updateDuelState(currentTick) {
    if (!this.data) return;
    if (currentTick <= this._duelState.currentTick) return;

    for (const ev of this.events) {
      if (ev.tick > currentTick) break;
      if (ev.tick <= this._duelState.currentTick) continue;

      if (ev.type === 'DUEL_START') {
        const desc = ev.description || '';
        const parts = desc.split(' vs ');
        if (parts.length === 2) {
          const attackerPart = parts[0].replace(/^.*?\s+/, '').trim();
          const defenderPart = parts[1].trim();
          const pairKey = attackerPart + '|' + defenderPart;
          this._duelState.resolved.delete(pairKey);
          this._duelState.pairs.push({ tick: ev.tick, a: attackerPart, b: defenderPart });
        }
      } else if (ev.type === 'DUEL_RESOLVED') {
        const desc = ev.description || '';
        const parts = desc.split(' vs ');
        if (parts.length === 2) {
          const attackerPart = parts[0].replace(/^.*?\s+/, '').trim();
          const defenderPart = parts[1].split('|')[0].trim();
          this._duelState.resolved.add(attackerPart + '|' + defenderPart);
          const winnerMatch = desc.match(/winner=([^(]+)\s*\(/);
          if (winnerMatch) {
            const winnerLabel = winnerMatch[1].trim();
            const loserLabel = winnerLabel === attackerPart ? defenderPart : attackerPart;
            this._duelState.cooldowns = this._duelState.cooldowns || new Map();
            this._duelState.cooldowns.set(loserLabel, ev.tick + 6);
          }
        }
      }
    }
    this._duelState.currentTick = currentTick;
  }

  _getActiveDuelPairs(tick) {
    const intTick = Math.floor(tick);
    const DUEL_DURATION = 2;
    return this._duelState.pairs
      .filter(dp => dp.tick <= intTick && dp.tick + DUEL_DURATION >= intTick)
      .filter(dp => !this._duelState.resolved.has(dp.a + '|' + dp.b))
      .map(dp => [{ label: dp.a }, { label: dp.b }]);
  }

  _checkSnapshotOverlays() {
    if (!this.data) return;
    const intTick = Math.floor(this.currentTick);
    const snap = this._snapIndex?.get(intTick);
    if (!snap) return;

    const hg = snap.goalCount ?? 0;
    const ag = snap.awayGoalCount ?? 0;
    const homeName = this.data.homeTeamName || 'HOME';
    const awayName = this.data.awayTeamName || 'AWAY';

    if (snap.halfTime && !this._prevHalfTime) {
      this._prevHalfTime = true;
      this.overlays.showHalftime(homeName, awayName, hg, ag);
    }

    if (snap.matchFinished && !this._prevMatchFinished) {
      this._prevMatchFinished = true;
      this.overlays.showFulltime(homeName, awayName, hg, ag);
    }

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
    const blockAnim = this._blockAnim;
    this.pitch.showGrid = this.showGrid;
    this.pitch.currentTick = this.currentTick;
    // Feed duel cooldowns to the renderer (used for the loser red ring).
    this.pitch._duelState = this._duelState;
    this.pitch.render(players, ball, carrierId, this._flashEvent, goalAnim, duelPairs, blockAnim);
    if (this._pendingTimelineEvents.length > 0) {
      this._flushTimelineEvents();
    }
    this._updateScoreboard();
    this._updateSeek();
  }

  _flushTimelineEvents() {
    const ul = document.getElementById('timeline');
    if (!ul) {
      this._pendingTimelineEvents.length = 0;
      return;
    }
    const fragment = document.createDocumentFragment();
    for (const ev of this._pendingTimelineEvents) {
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
      fragment.appendChild(li);
    }
    ul.appendChild(fragment);

    while (ul.children.length > this._MAX_TIMELINE_EVENTS) {
      ul.removeChild(ul.firstChild);
    }

    const nearBottom = ul.scrollHeight - ul.scrollTop - ul.clientHeight < 80;
    if (nearBottom) {
      ul.scrollTop = ul.scrollHeight;
    }

    const ticker = document.getElementById('liveTicker');
    if (ticker) {
      let lastNotable = null;
      for (let i = this._pendingTimelineEvents.length - 1; i >= 0; i--) {
        if (!MINOR_EVENTS.has(this._pendingTimelineEvents[i].type)) {
          lastNotable = this._pendingTimelineEvents[i];
          break;
        }
      }
      if (lastNotable) {
        const tickerIcon = document.getElementById('tickerIcon');
        const tickerMin = document.getElementById('tickerMin');
        const tickerDesc = document.getElementById('tickerDesc');
        if (tickerIcon) tickerIcon.textContent = EV_ICON[lastNotable.type] || '';
        if (tickerMin) tickerMin.textContent = matchMinute(lastNotable.tick);
        if (tickerDesc) tickerDesc.textContent = formatEventDesc(lastNotable);
        ticker.classList.remove('hidden');
        ticker.classList.remove('fade-out');
        clearTimeout(this._tickerFadeTimer);
        this._tickerFadeTimer = setTimeout(() => {
          ticker.classList.add('fade-out');
        }, 3000);
      }
    }
    this._pendingTimelineEvents.length = 0;
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

  _MAX_TIMELINE_EVENTS = 200;

  _buildTimeline() {
    document.getElementById('timeline').innerHTML = '';
  }

  _showEmpty(show = true) {
    document.getElementById('emptyState').style.display = show ? 'flex' : 'none';
    document.querySelector('.pitch-wrap').style.display = show ? 'none' : 'flex';
    document.querySelector('.sidebar').style.display = show ? 'none' : 'flex';
    const ticker = document.getElementById('liveTicker');
    if (ticker) {
      const isLandscape = window.matchMedia('(orientation: landscape)').matches;
      const isShort = window.innerHeight < 500;
      ticker.style.display = (!show && isLandscape && isShort) ? 'flex' : 'none';
    }
  }

  _updateTickerVisibility() {
    const ticker = document.getElementById('liveTicker');
    const pitchWrap = document.querySelector('.pitch-wrap');
    if (!ticker || !pitchWrap) return;
    const isLandscape = window.matchMedia('(orientation: landscape)').matches;
    const isShort = window.innerHeight < 500;
    const isMatchLoaded = pitchWrap.style.display !== 'none';
    ticker.style.display = (isMatchLoaded && isLandscape && isShort) ? 'flex' : 'none';
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
      speedSlider.value = 1;
      const update = () => {
        this.speed = speeds[Number(speedSlider.value)];
        document.getElementById('speedLabel').textContent = this.speed + 'x';
      };
      speedSlider.addEventListener('input', update);
      update();
    }

    const gridControlsRow = document.querySelector('.controls-row:last-child');
    if (gridControlsRow) {
      const gridLabel = document.createElement('label');
      gridLabel.style.cssText = 'display:flex;align-items:center;gap:4px;font-size:11px;color:#8b949e;cursor:pointer;';
      gridLabel.title = 'Toggle grid overlay';
      const gridCheck = document.createElement('input');
      gridCheck.type = 'checkbox';
      gridCheck.id = 'gridToggle';
      gridCheck.checked = true;
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

    const tickerToggle = document.getElementById('tickerToggle');
    if (tickerToggle) {
      tickerToggle.addEventListener('click', () => {
        const sidebar = document.querySelector('.sidebar');
        if (sidebar) {
          const isOpen = sidebar.classList.toggle('expanded');
          tickerToggle.textContent = isOpen ? 'LOG \u25B2' : 'LOG \u25BC';
        }
      });
    }

    const onOrient = () => this._updateTickerVisibility();
    window.addEventListener('orientationchange', onOrient);
    window.addEventListener('resize', onOrient);
  }
}

/* ═══════════════════════════════════════════════════════════════
   BOOT
   ═══════════════════════════════════════════════════════════════ */
document.addEventListener('DOMContentLoaded', () => {
  const v = new MatchViewer2D();
  window.viewer = v;
});