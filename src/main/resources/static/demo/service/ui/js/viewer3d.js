/**
 * TIFO Demo Service — 3D Match Viewer
 *
 * Canvas-based 3D replay of a simulated match using a single skinned Mixamo
 * humanoid (35 animation clips, merged into one .glb) instanced per player.
 *
 * Orientation / scale:
 *   - world X = engine row  (1 → 0 m, 8 → 112 m; HOME attacks +X)
 *   - world Z = engine col  (goal lane col 3.5 → 35 m, col 1 → 10 m, col 7 → 70 m)
 *   - player model units are metres (rig is ~1.78 m tall), ground at y = 0
 *   - pawns are scaled 2.4× for visibility on a wide pitch
 */

import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { clone as cloneSkeleton } from 'three/addons/utils/SkeletonUtils.js';
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js';

/* ═══════════════════════════════════════════════════════════════
   CONSTANTS (match engine)
   ═══════════════════════════════════════════════════════════════ */
const TICKS_PER_MINUTE = 40;
const FIELD_ROW_MIN = 1.0, FIELD_ROW_MAX = 8.0;
const FIELD_COL_MIN = 1.0, FIELD_COL_MAX = 7.0;
const M_ROW = 16, M_COL = 10;
const rx = r => (r - 1) * M_ROW;
const cz = c => (c - 3.5) * M_COL + 35;

const HOME_OUTFIELD = 0x171a21;
const AWAY_OUTFIELD = 0xececf1;
const GK_COLOR = 0xf0b429;

const HOME_COLOR = '#539bf5';
const AWAY_COLOR = '#f97583';

const SPEEDS = [0.25, 0.5, 1, 2, 4];

/* ═══════════════════════════════════════════════════════════════
   HELPERS (mirror viewer.js)
   ═══════════════════════════════════════════════════════════════ */
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
function lerp(a, b, t) { return a + (b - a) * t; }

function tickToMinute(tick) {
  const totalSec = Math.floor(tick / TICKS_PER_MINUTE * 60);
  return `${String(Math.floor(totalSec / 60)).padStart(2, '0')}:${String(totalSec % 60).padStart(2, '0')}`;
}
function matchMinute(tick) {
  const totalSec = Math.floor(tick / TICKS_PER_MINUTE * 60);
  return `${Math.floor(totalSec / 60)}:${String(totalSec % 60).padStart(2, '0')}`;
}

const EV_ICON = {
  GOAL: '\u26BD', SHOT: '\u26BD', SHOT_SAVED: '\uD83E\uDD25', SHOT_MISSED: '\u274C',
  SHOT_BLOCKED: '\uD83D\uDEE1\uFE0F', SHOT_POST: '\uD83D\uDEA3\uFE0F',
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
  INFO: '\uD83D\uDCDD', GOAL_DISALLOWED: '\u26A0\uFE0F', KICKOFF: '\uD83C\uDFC0', OFFSIDE: '\uD83D\uDEA9',
};

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

const MINOR_EVENTS = new Set([
  'PASS', 'PASS_COMPLETED', 'PASS_LOOSE',
  'CARRY', 'CARRY_COMPLETED',
  'CHASE', 'DECISION', 'ACTION_EXECUTION', 'ACTION_OUTCOME',
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
    return `${ev.team} ${ev.playerName ? ev.playerName + ': ' : ''}${ev.description}`;
  }
  if (ev.team) return `${ev.team}: ${ev.description || ev.type}`;
  return ev.description || ev.type || '';
}

/* ═══════════════════════════════════════════════════════════════
   PITCH
   ═══════════════════════════════════════════════════════════════ */
function buildPitch() {
  const g = new THREE.Group();

  // Grass - playing field 112m x 70m (rows 0-8, cols 0-7)
  // X = rows (length, HOME at 0, AWAY at 112), Z = cols (width, top touchline 0, bottom 70)
  const grassGeo = new THREE.PlaneGeometry(112, 70);
  const grassMat = new THREE.MeshStandardMaterial({ color: 0x3a7d44, roughness: 1 });
  const grass = new THREE.Mesh(grassGeo, grassMat);
  grass.rotation.x = -Math.PI / 2;
  grass.position.set(56, -0.02, 35);
  g.add(grass);

  // Mow stripes along the length (X axis) every 14m (1 row) - only on playing field
  const stripeMat = new THREE.MeshStandardMaterial({ color: 0x2f6a38, roughness: 1, transparent: true, opacity: 0.35 });
  for (let r = 1; r <= 7; r += 2) {  // Only on playing field rows 1,3,5,7
    const s = new THREE.Mesh(new THREE.PlaneGeometry(14, 70), stripeMat);
    s.rotation.x = -Math.PI / 2;
    s.position.set(r * 14 + 7, -0.01, 35);
    g.add(s);
  }

  // Field lines - correct orientation per viewer.js
  const pts = [];
  const pushLine = (x1, z1, x2, z2) => { pts.push(x1, 0.01, z1, x2, 0.01, z2); };
  const x0 = 0, x1 = 112, z0 = 0, z1 = 70;
  
  // Touchlines (horizontal, run along X at z=0 and z=70)
  pushLine(x0, z0, x1, z0);  // top touchline
  pushLine(x0, z1, x1, z1);  // bottom touchline
  
  // Goal lines (vertical, at x=0 and x=112)
  pushLine(x0, z0, x0, z1);  // HOME goal line (left)
  pushLine(x1, z0, x1, z1);  // AWAY goal line (right)
  
  // Halfway line (vertical at x=56, PERPENDICULAR to touchlines)
  pushLine(56, z0, 56, z1);
  
  // Penalty areas (16.5m from goal line = 1.18 rows, 40.32m wide = 4.03 cols centered at 35)
  const paDepth = 16.5;  // meters from goal line
  const paWidth = 40.32; // meters wide
  const paHalf = paWidth / 2;
  const paCenterZ = 35;
  
  // HOME penalty area (at x=0, extends to x=16.5)
  pushLine(0, paCenterZ - paHalf, paDepth, paCenterZ - paHalf);
  pushLine(0, paCenterZ + paHalf, paDepth, paCenterZ + paHalf);
  pushLine(paDepth, paCenterZ - paHalf, paDepth, paCenterZ + paHalf);
  
  // AWAY penalty area (at x=112, extends to x=95.5)
  pushLine(112, paCenterZ - paHalf, 112 - paDepth, paCenterZ - paHalf);
  pushLine(112, paCenterZ + paHalf, 112 - paDepth, paCenterZ + paHalf);
  pushLine(112 - paDepth, paCenterZ - paHalf, 112 - paDepth, paCenterZ + paHalf);
  
  // Goal areas (5.5m from goal line, 18.32m wide)
  const gaDepth = 5.5;
  const gaWidth = 18.32;
  const gaHalf = gaWidth / 2;
  
  pushLine(0, paCenterZ - gaHalf, gaDepth, paCenterZ - gaHalf);
  pushLine(0, paCenterZ + gaHalf, gaDepth, paCenterZ + gaHalf);
  pushLine(gaDepth, paCenterZ - gaHalf, gaDepth, paCenterZ + gaHalf);
  
  pushLine(112, paCenterZ - gaHalf, 112 - gaDepth, paCenterZ - gaHalf);
  pushLine(112, paCenterZ + gaHalf, 112 - gaDepth, paCenterZ + gaHalf);
  pushLine(112 - gaDepth, paCenterZ - gaHalf, 112 - gaDepth, paCenterZ + gaHalf);
  
  // Penalty spots (11m from goal line)
  pts.push(11, 0.02, 35, 11, 0.02, 35, 101, 0.02, 35, 101, 0.02, 35);
  
  // Centre spot
  pts.push(56, 0.02, 35, 56, 0.02, 35);
  
  const lineGeo = new THREE.BufferGeometry();
  lineGeo.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3));
  const lineMat = new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.5 });
  g.add(new THREE.LineSegments(lineGeo, lineMat));

  // Centre circle (radius 9.15m)
  const circleMat = new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.5 });
  {
    const R = 9.15, pts = [], N = 96;
    for (let i = 0; i < N; i++) {
      const a = (i / N) * Math.PI * 2;
      pts.push(Math.cos(a) * R, 0.01, Math.sin(a) * R);
    }
    const circleGeo = new THREE.BufferGeometry();
    circleGeo.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3));
    const circle = new THREE.LineLoop(circleGeo, circleMat);
    circle.position.set(56, 0, 35);
    g.add(circle);
  }
  
  // Penalty arcs (radius 9.15m from penalty spot)
  const arcMat = new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.5 });
  const arc = (cx, fwd) => {
    const geo = new THREE.BufferGeometry();
    const a = [];
    const R = 9.15;
    for (let i = 0; i <= 32; i++) {
      const ang = Math.PI * i / 32;
      const z = 35 + (fwd > 0 ? R * Math.sin(ang) : -R * Math.sin(ang));
      const x = cx + (fwd > 0 ? 11 : -11);  // penalty spot at 11m from goal
      a.push(x, 0.01, z);
    }
    geo.setAttribute('position', new THREE.Float32BufferAttribute(a, 3));
    return new THREE.Line(geo, arcMat);
  };
  g.add(arc(0, 1));   // HOME arc (forward = +X)
  g.add(arc(112, -1)); // AWAY arc (forward = -X)

  // Goals (frames + nets) - at x=0 and x=112, centered at z=35
  const goal = (x, facing) => {
    const gg = new THREE.Group();
    const postMat = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.5 });
    const H = 2.44, half = 3.66;
    const post = new THREE.BoxGeometry(0.12, H, 0.12);
    const p1 = new THREE.Mesh(post, postMat); p1.position.set(0, H / 2, -half); gg.add(p1);
    const p2 = new THREE.Mesh(post, postMat); p2.position.set(0, H / 2, half); gg.add(p2);
    const bar = new THREE.Mesh(new THREE.BoxGeometry(0.15, 0.12, half * 2), postMat);
    bar.position.set(0, H, 0); gg.add(bar);
    const netMat = new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.10, side: THREE.DoubleSide });
    for (const depth of [1.2, 2.4]) {
      const n = new THREE.Mesh(new THREE.PlaneGeometry(half * 2 + 0.4, H + 0.4), netMat);
      n.position.set(facing * depth, H / 2, 0);
      n.rotation.y = Math.PI / 2;
      gg.add(n);
    }
    gg.position.set(x, 0, 35);
    return gg;
  };
  g.add(goal(0, 1));    // HOME goal at x=0
  g.add(goal(112, -1)); // AWAY goal at x=112

  return g;
}
/* ═══════════════════════════════════════════════════════════════
   ANIMATION CONTROL
   ═══════════════════════════════════════════════════════════════ */
const clipForIdle = 'mixamo.com';
const clipNames = new Set([
  'mixamo.com', 'Goalkeeper_Body_Block', 'Goalkeeper_Catch', 'Goalkeeper_Directing',
  'Goalkeeper_Diving_Save', 'Goalkeeper_Drop_Kick', 'Goalkeeper_Idle', 'Goalkeeper_Miss',
  'Goalkeeper_Overhand_Throw', 'Goalkeeper_Pass', 'Goalkeeper_Placing_Ball', 'Goalkeeper_Sidestep',
  'Goalkeeper_Scoop_1_', 'Goalkeeper_Diving_Save_1_', 'Header_Soccerball', 'Header',
  'Jog_Forward', 'Jog_Backward', 'Jog_Forward_Diagonal', 'Kick_Soccerball', 'Receive_Soccerball',
  'Receive', 'Soccer_Header', 'Soccer_Pass', 'Soccer_Penalty_Kick', 'Soccer_Spin',
  'Soccer_Tackle', 'Soccer_Trip', 'Strike_Foward_Jog', 'Throw_In', 'Victory',
  'Transition', 'Idle_Transition',
]);

function pickClipName(pawn, ctx) {
  // IDLE by default until we properly sync with backend events
  if (pawn.role === 'GK') {
    return { name: 'Goalkeeper_Idle', once: false };
  }
  return { name: clipForIdle, once: false };
}

/* ═══════════════════════════════════════════════════════════════
   MATCH VIEWER 3D
   ═══════════════════════════════════════════════════════════════ */
class Tifo3D {
  constructor() {
    this.playing = false;
    this.speed = SPEEDS[1];
    this.speedIdx = 1;
    this.currentTick = 0;
    this.lastTick = 0;
    this.data = null;
    this.snapIndex = new Map();
    this.snapTicks = [];
    this.startTick = 0;
    this.endTick = 0;
    this.events = [];
    this._displayedEventIdx = 0;
    this._displayedOverlayIdx = 0;
    this._pendingTimeline = [];
    this._prevHalfTime = false;
    this._prevMatchFinished = false;
    this._prevGoalCount = [0, 0];
    this._lastFrame = performance.now();
    this._loaded = false;
    this._modelReady = false;
    this._modelPart = null;
    this.pawns = [];
    this.pawnById = new Map();
    this._flashEvent = null;
    this._flashUntil = 0;
    this._lerpSnap = null;
    this._tickerFadeTimer = null;

    this._canvas = document.getElementById('pitch');
    this.renderer = new THREE.WebGLRenderer({ canvas: this._canvas, antialias: true });
    this.renderer.setSize(this._canvas.clientWidth || 960, this._canvas.clientHeight || 480);
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x0b0e13);

    this.camera = new THREE.PerspectiveCamera(50, 1, 0.1, 500);
    this.camera.position.set(56, 85, 35);
    this.camera.lookAt(56, 0, 35);

    // Lights
    const hemi = new THREE.HemisphereLight(0xffffff, 0x2b3a2b, 1.0);
    this.scene.add(hemi);
    const sun = new THREE.DirectionalLight(0xfff6e6, 1.4);
    sun.position.set(30, 60, 20);
    this.scene.add(sun);
    const fill = new THREE.DirectionalLight(0x9db8d8, 0.5);
    fill.position.set(-40, 30, 60);
    this.scene.add(fill);

    // Pitch
    this.pitch = buildPitch();
    this.scene.add(this.pitch);

    // Ball + shadow
    this.ball = new THREE.Mesh(
      new THREE.SphereGeometry(0.11, 20, 14),
      new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.45 }));
    this.ball.position.set(56, 0.11, 35);
    this.ball.scale.setScalar(2.4);
    this.scene.add(this.ball);
    this.ballShadow = new THREE.Mesh(
      new THREE.CircleGeometry(0.5, 20),
      new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.35 }));
    this.ballShadow.rotation.x = -Math.PI / 2;
    this.ballShadow.scale.setScalar(1.8);
    this.scene.add(this.ballShadow);

    this._wireControls();
    this._loadModel();

    this._rafId = requestAnimationFrame(this._loop);
  }

  /* ───────── model loading ───────── */
  async _loadModel() {
    try {
      const res = await fetch('models/player.glb');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const buf = await res.arrayBuffer();
      const loader = new GLTFLoader();
      loader.setMeshoptDecoder(MeshoptDecoder);
      const gltf = await new Promise((resolve, reject) => loader.parse(buf, '', resolve, reject));
      this._modelPart = gltf;
      this._onModelReady();
    } catch (e) {
      console.error('3D model load failed:', e);
    }
  }

  _onModelReady() {
    this._maybeBuild();
  }

  /* ───────── data loading ───────── */
  async loadUrl(url) {
    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      this._ingest(data);
    } catch (e) {
      console.error('match load failed:', e);
      this._showEmptyState();
    }
  }

  _showEmptyState() {
    const loading = document.getElementById('loading');
    if (loading) loading.classList.add('hidden');
    const empty = document.getElementById('emptyState');
    if (empty) empty.style.display = 'flex';
    const pitch = document.querySelector('.pitch-wrap');
    if (pitch) pitch.style.display = 'none';
    const sb = document.querySelector('.sidebar');
    if (sb) sb.style.display = 'none';
  }

  loadFile(file) {
    const reader = new FileReader();
    reader.onload = () => {
      try {
        this._ingest(JSON.parse(reader.result));
      } catch (e) {
        console.error('bad JSON', e);
      }
    };
    reader.readAsText(file);
  }

  _ingest(data) {
    this.data = data;
    this.snapIndex = new Map();
    this.snapTicks = [];
    for (const s of data.snapshots || []) {
      this.snapIndex.set(s.tick, s);
      this.snapTicks.push(s.tick);
    }
    this.snapTicks.sort((a, b) => a - b);
    this.startTick = this.snapTicks.length ? this.snapTicks[0] : 0;
    this.endTick = this.snapTicks.length ? this.snapTicks[this.snapTicks.length - 1] : 0;
    this.currentTick = this.startTick;
    this._displayedEventIdx = 0;
    this._displayedOverlayIdx = 0;
    this._prevHalfTime = false;
    this._prevMatchFinished = false;

    const merged = new Map();
    for (const e of data.events || []) merged.set(`e_${e.tick}_${e.type}`, e);
    for (const l of data.logs || []) merged.set(`l_${l.tick}_${l.type}_${l.description || ''}`, l);
    this.events = [...merged.values()].sort((a, b) => a.tick - b.tick);

    document.getElementById('homeName').textContent = data.homeTeamName || 'HOME';
    document.getElementById('awayName').textContent = data.awayTeamName || 'AWAY';
    document.getElementById('homeScore').textContent = data.homeGoals ?? 0;
    document.getElementById('awayScore').textContent = data.awayGoals ?? 0;
    document.getElementById('emptyState').style.display = 'none';
    document.querySelector('.pitch-wrap').style.display = 'flex';
    document.querySelector('.sidebar').style.display = 'flex';
    document.getElementById('loading').classList.add('hidden');

    requestAnimationFrame(() => {
      this._resize();
      this._maybeBuild();
    });
    this._loaded = true;
    this.play();
  }

  _maybeBuild() {
    if (this._built || !this._modelPart || !this.data) return;
    this._buildPawns();
    this._built = true;
    this._modelReady = true;
  }

  _buildPawns() {
    const gltf = this._modelPart;
    if (!gltf) return;
    for (const p of this.pawns) {
      this.scene.remove(p.group);
      if (p.mixer) p.mixer.stopAllAction();
    }
    this.pawns = [];
    this.pawnById.clear();

    const actions = gltf.animations.filter(a => clipNames.has(a.name));
    const clipMap = new Map(actions.map(a => [a.name, a]));

    const templateMaterial = (() => {
      let m = null;
      gltf.scene.traverse(o => { if (o.isMesh && !m) m = o.material; });
      return m;
    })();

    const teams = ['HOME', 'AWAY'];
    for (const t of teams) {
      if (!this.data) break;
      for (const first of this.snapTicks) {
        const snap = this.snapIndex.get(first);
        if (!snap) continue;
        for (const pl of snap.players || []) {
          if (pl.team !== t || this.pawnById.has(pl.id)) continue;
          this._createPawn(pl, clipMap, templateMaterial);
        }
        break;
      }
    }
    console.log('3D pawns built:', this.pawns.length);
  }

  _createPawn(pl, clipMap, templateMaterial) {
    const group = new THREE.Group();
    const root = cloneSkeleton(this._modelPart.scene);
    const mixer = new THREE.AnimationMixer(root);
    const actions = new Map();
    const team = pl.team === 'HOME';

    const tint = (() => {
      if (pl.role === 'GK') return GK_COLOR;
      return team ? HOME_OUTFIELD : AWAY_OUTFIELD;
    })();

    root.traverse(o => {
      if (o.isMesh) {
        const base = Array.isArray(o.material) ? o.material[0] : o.material || templateMaterial;
        const m = base && base.clone ? base.clone() : new THREE.MeshStandardMaterial({ color: tint, roughness: 0.8 });
        m.color.setHex(tint);
        o.material = m;
      }
    });

    group.add(root);
    group.scale.setScalar(2.4); // 2.4× player size for visibility
    this.scene.add(group);

    const pawn = {
      id: pl.id, team: pl.team, role: pl.role,
      group, root, mixer, actions, clipMap,
      vx: 0, vy: 0, yaw: 0,
      currentClip: null, currentAction: null,
      lastRow: rx(pl.position?.row ?? 4), lastCol: cz(pl.position?.column ?? 3.5),
    };
    this.pawns.push(pawn);
    this.pawnById.set(pl.id, pawn);

    for (const [name, clip] of clipMap) {
      const action = mixer.clipAction(clip);
      actions.set(name, action);
    }
    const idle = actions.get(clipForIdle) || actions.values().next().value;
    if (idle) {
      idle.setLoop(THREE.LoopRepeat, Infinity);
      idle.play();
      pawn.currentAction = idle;
      pawn.currentClip = idle.getClip().name;
    }
    return pawn;
  }

  /* ───────── controls ───────── */
  _wireControls() {
    document.getElementById('playBtn').addEventListener('click', () => this.play());
    document.getElementById('pauseBtn').addEventListener('click', () => this.pause());
    const seek = document.getElementById('seek');
    seek.addEventListener('input', () => { seek._dragging = true; this.seek(Number(seek.value)); });
    seek.addEventListener('change', () => { seek._dragging = false; });
    const slider = document.getElementById('speedSlider');
    const update = () => {
      this.speedIdx = clamp(Number(slider.value), 0, SPEEDS.length - 1);
      this.speed = SPEEDS[this.speedIdx];
      document.getElementById('speedLabel').textContent = this.speed + 'x';
    };
    slider.addEventListener('input', update);

    const fileBtn = document.getElementById('fileBtn');
    const fileInput = document.getElementById('fileInput');
    fileBtn.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', () => {
      if (fileInput.files && fileInput.files[0]) this.loadFile(fileInput.files[0]);
    });

    const simBtn = document.getElementById('simBtn');
    const simBtn2 = document.getElementById('simBtn2');
    const playMatchBtn = document.getElementById('playMatchBtn');
    const playMatch = async () => {
      document.getElementById('loading').classList.remove('hidden');
      document.getElementById('loadingText').textContent = 'Simulating match...';
      try {
        await fetch('/api/service/match/simulate', { method: 'POST' });
      } catch (e) { console.error(e); }
      try {
        await fetch('/api/generate', { method: 'POST' });
      } catch (e) { console.error(e); }
      document.getElementById('loadingText').textContent = 'Loading replay...';
      await this.loadUrl('match.json');
      document.getElementById('loading').classList.add('hidden');
    };
    simBtn.addEventListener('click', playMatch);
    simBtn2.addEventListener('click', playMatch);
    playMatchBtn.addEventListener('click', () => this.loadUrl('match.json'));

    window.addEventListener('keydown', (e) => {
      const tag = e.target && e.target.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      if (e.key === ' ') { e.preventDefault(); this.playing ? this.pause() : this.play(); }
      else if (e.key.startsWith('Arrow')) {
        const dir = e.key === 'ArrowLeft' ? -1 : e.key === 'ArrowRight' ? 1 : 0;
        const step = e.key === 'ArrowUp' || e.key === 'ArrowDown' ? 10 : 40;
        this.seek(this.currentTick + dir * step);
      }
    });

    window.addEventListener('resize', () => this._resize());
  }

  play() {
    this.playing = true;
    this._lastFrame = performance.now();
    document.getElementById('playBtn').classList.add('active');
    document.getElementById('pauseBtn').classList.remove('active');
  }

  pause() {
    this.playing = false;
    document.getElementById('playBtn').classList.remove('active');
    document.getElementById('pauseBtn').classList.add('active');
  }

  seek(tick) {
    this.currentTick = clamp(tick, this.startTick, this.endTick);
    this._displayedEventIdx = 0;
    while (this._displayedEventIdx < this.events.length && this.events[this._displayedEventIdx].tick <= this.currentTick) {
      this._displayedEventIdx++;
    }
    this._buildTimeline();
    this._updateFrame();
  }

  _resize() {
    const wrap = this._canvas.parentElement; // pitch-wrap3d
    const w = wrap.clientWidth || 960;
    const h = wrap.clientHeight || 480;
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(w, h, true);
  }

  /* ───────── interpolation ───────── */
  _interpolateTick(tick) {
    if (!this.snapTicks.length) return null;
    let lo = 0, hi = this.snapTicks.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (this.snapTicks[mid] <= tick) lo = mid;
      else hi = mid - 1;
    }
    const snap = this.snapIndex.get(this.snapTicks[lo]);
    const nextIdx = lo + 1;
    if (nextIdx >= this.snapTicks.length) return { snap, next: null, frac: 0 };
    const next = this.snapIndex.get(this.snapTicks[nextIdx]);
    const tickA = this.snapTicks[lo], tickB = this.snapTicks[nextIdx];
    return { snap, next, frac: tickB > tickA ? clamp((tick - tickA) / (tickB - tickA), 0, 1) : 0 };
  }

  _frameState(interp) {
    const a = interp.snap, b = interp.next, t = interp.frac;
    return {
      players: a.players,
      ball: a.ballPosition,
      ballState: a.ballState,
      carrierId: t > 0.5 && b ? b.ballCarrierId : a.ballCarrierId,
      actionType: a.actionType,
      actionActingPlayerId: a.actionActingPlayerId,
      actionTargetPlayerId: a.actionTargetPlayerId,
      goalCount: b ? (t > 0.5 ? b.goalCount : a.goalCount) : a.goalCount,
      awayGoalCount: b ? (t > 0.5 ? b.awayGoalCount : a.awayGoalCount) : a.awayGoalCount,
      halfTime: a.halfTime,
      matchFinished: a.matchFinished,
    };
  }

  _updateFrame() {
    const interp = this._interpolateTick(this.currentTick);
    if (!interp) return;
    const s = this._frameState(interp);
    const playerData = new Map();
    const snap = interp.snap;
    const next = interp.next;
    const frac = interp.frac;
    const bMap = new Map(next ? next.players.map(p => [p.id, p]) : []);
    for (const pa of snap.players) {
      const pb = bMap.get(pa.id);
      if (pb) {
        playerData.set(pa.id, {
          row: lerp(pa.position.row, pb.position.row, frac),
          col: lerp(pa.position.column, pb.position.column, frac),
          vx: pb.velocityX ?? 0, vy: pb.velocityY ?? 0,
        });
      } else {
        playerData.set(pa.id, { row: pa.position.row, col: pa.position.column, vx: 0, vy: 0 });
      }
    }
    for (const pawn of this.pawns) {
      const pd = playerData.get(pawn.id);
      if (!pd) continue;
      const x = rx(pd.row), z = cz(pd.col);
      const dx = x - pawn.lastRow, dz = z - pawn.lastCol;
      pawn.lastRow = x; pawn.lastCol = z;
      pawn.vx = pd.vx; pawn.vy = pd.vy;
      pawn.group.position.set(x, 0, z);
      if (dx * dx + dz * dz > 0.0004) pawn.yaw = Math.atan2(dx, dz);
      pawn.group.rotation.y = pawn.yaw;

      const pick = pickClipName(pawn, s);
      if (pick && clipNames.has(pick.name)) {
        const action = pawn.actions.get(pick.name);
        if (action && pawn.currentClip !== pick.name) {
          action.reset();
          action.setLoop(pick.once ? THREE.LoopOnce : THREE.LoopRepeat, Infinity);
          if (pick.once) action.clampWhenFinished = true;
          action.enabled = true;
          if (pawn.currentAction && pawn.currentAction.isRunning()) {
            pawn.currentAction.crossFadeTo(action, 0.22, false);
          }
          action.play();
          pawn.currentAction = action;
          pawn.currentClip = pick.name;
        }
      }
    }

    // Ball — interpolate between snapshots for smooth motion
    const bpA = snap.ballPosition;
    const bpB = next ? next.ballPosition : bpA;
    if (bpA) {
      const bRow = lerp(bpA.row, bpB.row, frac);
      const bCol = lerp(bpA.column, bpB.column, frac);
      const x = rx(bRow), z = cz(bCol);
      this.ball.position.set(x, s.ballState === 'IN_TRANSITION' ? 0.42 : 0.11, z);
      this.ballShadow.position.set(x, 0.002, z);
    }

    this._checkSnapshotOverlays(s);
    this._flushTimeline();
  }

  _checkSnapshotOverlays(s) {
    const hg = s.goalCount ?? 0, ag = s.awayGoalCount ?? 0;
    const homeName = this.data?.homeTeamName || 'HOME';
    const awayName = this.data?.awayTeamName || 'AWAY';

    if (s.matchFinished && !this._prevMatchFinished) {
      this._prevMatchFinished = true;
      this.showNews('FULL TIME', `${homeName} ${hg} - ${ag} ${awayName}`, 'fulltime', Infinity);
    } else if (s.halfTime && !this._prevHalfTime) {
      this._prevHalfTime = true;
      this.showNews('HALF TIME', `${homeName} ${hg} - ${ag} ${awayName}`, 'halftime', 8000);
    }
    if (hg > this._prevGoalCount[0] || ag > this._prevGoalCount[1]) {
      const team = hg > this._prevGoalCount[0] ? 'HOME' : 'AWAY';
      this._prevGoalCount = [hg, ag];
      this.showNews('\u26BD GOAL!', `${team === 'HOME' ? homeName : awayName} ${hg} - ${ag} ${team === 'HOME' ? awayName : homeName}`, 'goal', 4200);
    }
  }

  showNews(text, sub, cls, duration) {
    const overlay = document.getElementById('overlay');
    const textEl = document.getElementById('overlayText');
    const subEl = document.getElementById('overlaySub');
    textEl.textContent = text;
    subEl.textContent = sub;
    overlay.className = 'overlay visible ' + (cls || '');
    clearTimeout(this._newsTimer);
    if (duration && isFinite(duration)) {
      this._newsTimer = setTimeout(() => {
        if (!overlay.classList.contains('fulltime')) {
          overlay.classList.remove('visible');
        }
      }, duration);
    }
  }

  /* ───────── event timeline ───────── */
  _buildTimeline() {
    document.getElementById('timeline').innerHTML = '';
  }

  _processEventsForTick(from, to) {
    if (!this.events.length) return;
    while (this._displayedEventIdx < this.events.length && this.events[this._displayedEventIdx].tick <= to) {
      const ev = this.events[this._displayedEventIdx];
      if (ev.tick >= from && TIMELINE_EVENTS.has(ev.type)) {
        this._pendingTimeline.push(ev);
      }
      this._displayedEventIdx++;
    }
  }

  _flushTimeline() {
    if (this._pendingTimeline.length === 0) return;
    const ul = document.getElementById('timeline');
    const fragment = document.createDocumentFragment();
    for (const ev of this._pendingTimeline) {
      const li = document.createElement('li');
      li.className = 'event ' + classifyEvent(ev) + (MINOR_EVENTS.has(ev.type) ? ' minor' : '');
      const descHtml = formatEventDesc(ev)
        .replace(/(HOME\s*\w*)/g, '<span class="team-home">$1</span>')
        .replace(/(AWAY\s*\w*)/g, '<span class="team-away">$1</span>');
      li.innerHTML = `<span class="min">${matchMinute(ev.tick)}'</span>` +
        `<span class="icon">${EV_ICON[ev.type] || '\uD83D\uDCDD'}</span>` +
        `<span class="desc">${descHtml}</span>`;
      fragment.appendChild(li);
    }
    ul.appendChild(fragment);
    while (ul.children.length > 240) ul.removeChild(ul.firstChild);
    if (ul.scrollHeight - ul.scrollTop - ul.clientHeight < 80) ul.scrollTop = ul.scrollHeight;
    this._pendingTimeline.length = 0;
  }

  /* ───────── main loop ───────── */
  _loop = () => {
    const now = performance.now();
    const dt = Math.min((now - this._lastFrame) / 1000, 0.1);
    this._lastFrame = now;

    if (this._loaded && this.playing) {
      const ticksPerSec = 5 * this.speed;
      this.currentTick += dt * ticksPerSec;
      if (this.currentTick >= this.endTick) {
        this.currentTick = this.endTick;
        this.pause();
      }
      this._processEventsForTick(this.currentTick - dt * ticksPerSec - 1, this.currentTick);
      this._updateFrame();
      this._flushTimeline();
      this._updateScoreboard();
    } else if (this._loaded) {
      this._updateFrame();
      this._flushTimeline();
    }

    // Animation playback — breath at low speed while paused
    const animMul = this.playing ? 1 : 0.12;
    for (const pawn of this.pawns) pawn.mixer.update(dt * animMul);

    if (performance.now() - this._lastResizedHint > 2000) {
      this._lastResizedHint = performance.now();
      this._resizeListen();
    }

    this.renderer.render(this.scene, this.camera);
    this._rafId = requestAnimationFrame(this._loop);
  };

  _lastResizedHint = 0;

  _resizeListen() {
    const wrap = this._canvas.parentElement;
    const w = wrap.clientWidth, h = wrap.clientHeight;
    if (Math.abs(w - this._lastW) > 2 || Math.abs(h - this._lastH) > 2) {
      this._lastW = w; this._lastH = h;
      this.camera.aspect = w / Math.max(h, 1);
      this.camera.updateProjectionMatrix();
      this.renderer.setSize(w, h, true);
    }
  }

  _updateScoreboard() {
    const intTick = Math.floor(this.currentTick);
    const snap = this._loaded ? this.snapIndex.get(intTick) || null : null;
    const hg = snap?.goalCount ?? this.data?.homeGoals ?? 0;
    const ag = snap?.awayGoalCount ?? this.data?.awayGoals ?? 0;
    document.getElementById('homeScore').textContent = hg;
    document.getElementById('awayScore').textContent = ag;
    document.getElementById('clock').textContent = tickToMinute(this.currentTick);
    const status = snap?.halfTime ? 'HT' : snap?.matchFinished ? 'FT' : '';
    document.getElementById('statusLabel').textContent = status;
    const seek = document.getElementById('seek');
    if (seek && !seek._dragging) seek.value = this.currentTick;
  }
}

/* ───────── bootsrap ───────── */
const viewer = new Tifo3D();
// Attempt to load the exported match automatically.
viewer.loadUrl('match.json');

// Expose for debugging
window.tifo3d = viewer;