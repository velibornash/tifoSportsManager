# PROPOSAL_CURRENT_STATE.md — demo/service/proposal

**Authoritative description of the current state** of the proposal engine.
This document is **always updated** when `PROPOSAL_PROGRESS.md` changes.

> Last update: 2026-09-14 (pass completion tuning + physics calibration session).

---

## 1. GOAL

A runnable, deterministic, tactically-realistic 11v11 football match sim with:

- Separated, single-responsibility engines (decision, action, physics,
  movement, tactical intent, restarts, duels, rules). The Orchestrator only
  **calls** the engines in order — it holds no logic itself.
- Tactical positions from the DB (`team_tactics_profile`) or fallback JSON.
  A player without the ball follows their tactical target every tick.
- A live ball: pass/shot → chase → possession; shots, goals, saves, duels;
  restarts (corner, goal kick, throw-in, penalty, free kick); VAR (skeleton).
- "Ball at feet": the carrier always has the ball at their feet at the
  moment of decision.
- Configurable sides: HOME attacks rows 1→7; side swap in the second half
  must be possible without breaking the system.
- Verification via the `MatchSimulationLauncher` and `ProposalBatchDiag`.

---

## 2. PITCH — authoritative values (source of truth)

All measurements in **cells** unless stated in meters.
Pitch grid: **7 rows × 6 columns**, each cell **14 m × 10 m**.
"Cell length" means **14 meters**.

**Grid coordinates (row | col):**

| Zone | Row range | Column range |
|---|---|---|
| row 0 (OOB behind HOME goal) | 0.00–0.99 | — |
| row 1 | 1.00–1.99 | — |
| row 2 | 2.00–2.99 | — |
| row 3 | 3.00–3.99 | — |
| row 4 | 4.00–4.99 | — |
| row 5 | 5.00–5.99 | — |
| row 6 | 6.00–6.99 | — |
| row 7 | 7.00–8.00 | — |
| row 8 (OOB behind AWAY goal) | 8.01–9.00 | — |
| column 0 (OOB left of touchline) | — | 0.00–0.99 |
| column 1 | — | 1.00–1.99 |
| column 2 | — | 2.00–2.99 |
| column 3 | — | 3.00–3.99 |
| column 4 | — | 4.00–4.99 |
| column 5 | — | 5.00–5.99 |
| column 6 | — | 6.00–7.00 |
| column 7 (OOB right of touchline) | — | 7.01–8.00 |

**OOB = out of bounds** (ball off the pitch).

**Lines and goals:**
- Left touchline: **col 1.00**; right touchline: **col 7.00**.
- HOME goal line: **row 1.00**; goal from **1.00|3.50 to 1.00|4.50**
  (width 1.0, center col **4.00**).
- AWAY goal line: **row 8.00**; goal from **8.00|3.50 to 8.00|4.50**.
- Pitch center: **4.50|4.00**.
- row 0 = visual OOB zone behind HOME goal; row 8 = behind AWAY goal.

**Corners:** HOME left **1.0|1.0**, HOME right **1.0|7.0**,
AWAY left **8.0|1.0**, AWAY right **8.0|7.0**.

**Penalties:** HOME **1.79|4.00**, AWAY **7.21|4.00**.

**Penalty boxes:**
- HOME: **1.00|2.40, 1.00|5.60, 2.14|2.40, 2.14|5.60**.
- AWAY: **8.00|2.40, 8.00|5.60, 6.86|2.40, 6.86|5.60**.

**Attack direction:** HOME attacks left→right (from row 1 toward row 7).
Side swap in the second half: MUST remain possible without breaking the system.

> **NOTE — engine mismatch:** the engine still uses goal center at **col 3.5**
> (not 4.0). Engine box/penalty constants differ from the authoritative values
> above. Alignment is in the backlog.

---

## 3. PLAYER DATA

### 3.1 Eight base skills (1–20)

| Name | Abbr | Role in physics / decision |
|---|---|---|
| Stamina | sta | Fatigue drain (↑stamina = slower fatigue) |
| Keeper | kep | GK: save/catch/punch chance; DuelEngine aerial |
| Pace | pac | Player speed = `pace/20 * 0.75` cells/tick (no chase sprint) |
| Defending | def | Tackle/block/interception; DuelEngine DRIBBLE/TACKLE power |
| Technique | tec | First touch, ball control, execution quality (pass/shot deviation) |
| Playmaking | pm | Option vision (VisionFilter), decision quality (OptionSelector) — **not** passing |
| Passing | pas | Pass speed (max ball speed for PASS/CROSS/CLEAR), accuracy |
| Striker | str | Shot: xG, ball speed, aim (far post), goal chance |

> **Code fields:** the `PlayerSkills` record has fields in order
> `(pace, stamina, keeper, technique, playmaking, passing, striker, defender)`.
> The documented order above is authoritative for the specification.

### 3.2 Non-standard attributes

| Attribute | Type / range | Role |
|---|---|---|
| Fatigue | double 0.0–1.0 | MovementEngine reduces speed up to −30% |
| Injury | boolean | `isUnavailable()` = true |
| Form | double 0.5–1.2 (default 1.0) | Multiplier on all outputs; **documented only**, not implemented |

---

## 4. BALL PHYSICS — authoritative specification

### 4.1 Basic model

- Ball is **pure physics**: position + velocity (velX, velY in cells/tick) +
  rotation/spin (0..1) + Airborne flag.
- **NOT** a carrier, NOT a target, NOT "who called it" — all that lives in `MatchState`.
- Launch: `launch(aim, speedCellsPerTick, airborne, spin)` —
  direction = `aim - origin`, speed = given, along the direction.

### 4.2 Speeds (match time: 1 tick = 1.5 s, 40 TPM)

| | m/s | cells/tick |
|---|---:|---:|
| Player pace 20 | 7.0 | 0.75 |
| Ball MIN (weakest pass) | 7.0 | **0.75** |
| Ball MAX (strongest shot/pass) | 14.0 | **1.50** |

Speed is **not clamped** to the target; the ball flies toward the target as
far as it gets, then decelerates and stops (or becomes LOOSE). Minimum launch
speed: **0.75**.

### 4.3 Deceleration

| Type | decel (cells/tick²) | note |
|---|---:|---|
| Ground (ground pass/clearance) | **0.35** | ~2.2 m/s² friction |
| Air (air shot/cross) | **0.15** | ~0.9 m/s² air resistance |
| Stop | when speed ≤ **0.02** → 0, `airborne=false` |

**Landing:** an air ball becomes a ground ball as soon as it drops below
`LANDING_SPEED = 0.30` (continues with ground decel).

### 4.4 Spin

- `spin ∈ [0, 1]` → lateral acceleration every tick: `rotation(speed, spin * 0.05)`.
- Slight trajectory curve (for free kick, corner, cross).

### 4.5 Collisions (every tick)

The ball checks collisions in this order (earlier = higher priority):

1. **Posts/crossbar (GoalPhysical):** left/right post (radius 0.03 cells) on
   the goal line. Collision → bounce (reflect along normal) + `BOUNCE_DAMP = 0.5`.
   Ball becomes ground ball.
2. **Goal plane:** intersection of segment `prev→new` with goal line (HOME 1.0,
   AWAY 8.0). If intersection is inside the mouth (3.50–4.50, excluding posts)
   → **GOAL**. Attributed to `lastTouchTeam`.
3. **Players** (first contact along the segment wins):
   - pendingReceiver (same team) within `RECEIVE_R = 0.35` → **RECEIVE**.
   - Opponent within `INTERCEPT_R = 0.30`:
     - if ball **slow** (< `FAST_CONTACT = 1.00` cells/tick) → **INTERCEPT**.
     - if ball **fast** (≥ 1.00) → **BLOCK** (bounce + damp, NO possession).
   - Anyone within `DEFLECT_R = 0.18` → **DEFLECT** (slight bounce + damp).
4. **OOB zone** (row ≤ 0.99 / ≥ 8.01 / col ≤ 0.99 / ≥ 7.01):
   - First entry → `oobPending = restartType`, `oobHoldTicks = 4`.
   - During hold the ball **keeps moving** (visible in the OOB zone).
   - If it returns to the pitch before expiry → `oobPending` cleared.
   - When `ticks == 0` → returns `dueRestart`.
   - **NEVER instant teleport** to a restart — 4-tick visibility.

### 4.6 Loose ball pickup

- Ball stopped (speed ≤ 0.02) with no carrier → every tick check the nearest
  player within `PICKUP_R = 0.35` → they become carrier.

### 4.7 GoalPhysical

```java
class GoalPhysical {
    double goalLineRow;       // 1.0 (HOME) or 8.0 (AWAY)
    double mouthLeftCol = 3.5;
    double mouthRightCol = 4.5;
    Position leftPost;   // (goalLineRow, 3.5)
    Position rightPost;  // (goalLineRow, 4.5)
    double postRadius = 0.03;
    double heightMeters = 2.44;
}
```
In 2D physics only the posts are checked. Crossbar = UI/rendering only.

### 4.8 What the engine MUST NOT do

- ❌ Clamp the ball target (row/col).
- ❌ Clamp player positions.
- ❌ Know "who called it" (ActionExecutor logs, BallPhysicsEngine does NOT).
- ❌ Ball target position ≠ where the ball stops.
- ❌ Goal detection by radius around center — only goal-line intersection in the mouth.

---

## 5. CURRENT ARCHITECTURE

### 5.1 Package

`org.example.footballmanager.demo.service.proposal`
- Imports nothing outside its own tree (clean, standalone).
- Spring Boot component scan includes this package.

### 5.2 Models

| Class | Description |
|---|---|
| `MatchState` | Central state: ball, carrier, clock, stats, OOB pending, VAR stub |
| `Player` | Position, skills, fatigue, injury, form, role, team |
| `Ball` | Pure physics: position, velX/velY, spin, airborne, launchSpeed |
| `Position` | 2D coordinate (row, col) |
| `PlayerSkills` | Record: pace, stamina, keeper, technique, playmaking, passing, striker, defender |
| `ActionType` | Enum: PASS, SHOT, DRIBBLE, CLEAR, CROSS, CENTER, THRU, HOLD |
| `DecisionResult` / `DecisionOption` / `DecisionContext` | Decision engine output |
| `PitchEnvironment` + `GoalPhysical` | Authoritative pitch geometry |

### 5.3 Engines

| Engine | Responsibility | Status |
|---|---|---|
| `CleanDecisionEngine` | Scoring + option selection (PASS/SHOT/DRIBBLE/CLEAR/CROSS/CENTER/THRU) | ✅ Works |
| `ActionExecutor` | Executes decision: PASS/SHOT/CLEAR = `ballEngine.launch(...)` | ✅ Works |
| `BallPhysicsEngine` | Pure physics: launch, deceleration, collisions, OOB, loose pickup | ✅ Works (calibrated) |
| `MovementEngine` | Moves all players toward targets; loose-ball chase; separation | ✅ Works |
| `TacticalIntentEngine` | Tactical targets from rules; refresh every tick | ✅ Works |
| `DuelEngine` | Duel detection + resolution (DRIBBLE, TACKLE, AERIAL) | ✅ Works (rare) |
| `RestartManager` | Kickoff, corner, goal kick, throw-in, penalty, free kick | ✅ Works |
| `FootballRules` | Offside check (minimal) | ✅ Works (offside only) |
| `ExecutionQuality` | Pass/shot deviation; ball speed mapping 1→20 → 7→14 m/s | ✅ Works |

### 5.4 Tactical system

| Class | Description |
|---|---|
| `TacticsRuleDTO` / `TacticsSlotDTO` | DTOs from `demo/service/tactics` |
| `FormationSlotCatalog` | 4-4-2, 4-3-3, 4-2-3-1, 4-1-4-1, 3-5-2, 5-3-2, 3-4-3, 4-5-1, 5-4-1 |
| `TacticalPerspectiveTransformer` | HOME direct; AWAY mirror `Position(9-row, 7-col)` |
| `TacticsRules` | 3-level loading: DB → JSON fallback → catalog |

**Transformation:** editor cell is 0-based; `parseCell` for `CELL_r_c`
returns `(r + 1.5, c + 1.5)` (cell center).

### 5.5 Recording

| Class | Description |
|---|---|
| `MatchRecorder` | Event + snapshot recording |
| `MatchEvent` / `MatchSnapshot` / `MatchRecording` | JSON-friendly models |
| `PlayerSnapshot` | Player position at snapshot time |

### 5.6 Orchestrator — per-tick order

```
1.  clock tick
2.  unlock (action finished?)
3.  VAR timer
4.  ballEngine.stepBall       → BallStepResult
5.  handle result              → RECEIVE / INTERCEPT / BLOCK / GOAL / RESTART
6.  decision + execution       → only if carrier exists and ball NOT in flight
7.  tactical intent            → refreshTargets
8.  movement                   → moveAllTowardTargets
9.  restart taker claim        → taker walks to the ball
10. rules                      → offside
11. duels                      → DuelEngine
12. VAR timer update
```

**Log tags:** `[mm:ss|DEC]` decision, `[mm:ss|ORC]` orchestrator,
`[mm:ss|BAL]` ball physics, `[mm:ss|DUL]` duel, `[mm:ss|RST]` restart,
`[mm:ss|TAC]` tactical, `[mm:ss|LCH]` loose ball chase.

### 5.7 UI display

- `ProposalViewerLauncher` — port **8766** (separate from demo/service 8765).
- `POST /proposal/api/generate` — simulates a match (3600 ticks), writes
  `match.json` (58MB, ~7200 events + 3600 snapshots).
- `/proposal/match.json` — serves the JSON.
- `viewer.js` — 1:1 port of the demo/service viewer: LED scoreboard, canvas pitch,
  timeline, controls (Play/Pause/Seek/Speed).
- `index.html` — viewer page; `viewer.js?v=3` + `pitch.css?v=3` (cache-buster).
- `Cache-Control: no-store` on all files.

### 5.8 Statistics

**Current state (MAJOR GAP):**
MatchState has only basic global counters:
```
passAttempts, passesCompleted, shots, shotsOnTarget, fouls, yellowCards, redCards
```

**What is missing:**

| Category | Current | Needed |
|---|---|---|
| Shots | `shots`, `shotsOnTarget` | blocked, saved, missed, per player, per team |
| Goals | `homeGoals`, `awayGoals` | open play, center, cross, penalty, FK, corner, per player |
| Offside | none | total, per team |
| VAR | none | total, confirmed, overturned, per type |
| Passes | `passAttempts`, `passesCompleted` | thru, center, cross, air, ground, per player |
| Dribbles | none | total, successful, per player |
| Interceptions | none | interceptions, deflections, per player |
| Restarts | none | corners, throw-ins, goal kicks, free kicks, penalties, per team |
| Cards | `fouls`, `yellowCards`, `redCards` | yellow, red, double-yellow, per player |
| Per-player | none | position, minutes, rating, all actions |
| Rating | none | average rating based on actions |

### 5.9 Diagnostics

`ProposalBatchDiag` — diagnostic class: runs 10+ matches, aggregates
goals/shots/SOT/passes + H/A split + 0-0 count.

---

## 6. MEASURED VALUES (10 matches, 3600 ticks)

| Metric | Proposal | Demo/service | Status |
|---|---|---|---|
| Goals/match | 1.2 | 2.4 | lower (recheck at 3600 ticks) |
| Shots/match | 39.6 | 54 | lower |
| SOT % | 12% | 11% | ≈ target |
| Pass completion | 67% | 98% | **much lower — MAIN GAP** |
| H/A goals | 0.9/0.3 | ≈1/1 | H slightly dominant |
| 0-0 | 3/10 | 0-1/10 | too many |

**Main remaining gap — pass completion 67% vs 98%:**
- `readIntercept` is called every tick of the flight segment, for every defender
  within 0.14 cells of the line — cumulative interception probability too high.
- Defenders too dense in the middle (average intercept row 4.2).
- Decision engine picks "lane blocked" receivers because the −80 penalty is
  not enough when forward +50 and openness +30 make up for it.

**Halftime bug (eradicated in session 6.9):** `MatchClockService.tick()` returns
`true` when `matchTicks == 1800` but `simulate()` never called `resume()`.
Now fixed: after 1800 ticks → `resume()` + `handleKickoff("AWAY")`.

---

## 7. WHAT DOES NOT EXIST (missing engines / layers)

| Layer | Description | Status |
|---|---|---|
| **Override system** | Formal layer between `decide()` and `execute()` | ❌ NOT NEEDED — user keeps hard rules, refactor to boosts later |
| **VAR engine** | Review of decisions (offside, goal, penalty, red) | ❌ SKELETON stub exists (`rules/VARService.java`) |
| **Discipline** | Fouls + cards | ❌ STUB — `rules/DisciplineService.java` created, no logic |
| **Offside full** | Continuous tracking + per-pass check | ❌ STUB — `rules/OffsideService.java` created, no logic |
| **Threat override** | Defensive pressure on carrier (3 types) | ❌ STUB — `engine/ThreatOverrideEngine.java` created, no logic |
| **Fatigue** | Player fatigue | ❌ NOT PRESENT (backlog P8) |
| **Transition** | Possession-change logic | ❌ NOT PRESENT (backlog P8) |
| **Stats layer** | Per-team + per-player stats | ❌ NOT PRESENT (backlog P1) |
| **Orchestrator slimming** | ~340 lines: logging, recording, duel detection | ❌ TOO FAT (backlog P2) |

---

## 8. RUNNING

```bash
# Compile
mvn -q compile

# Launcher (12 min match = 480 ticks)
mvn -q exec:java -Dexec.mainClass=org.example.footballmanager.demo.service.proposal.MatchSimulationLauncher

# Full match / longer run to check freezes (3600 ticks = 90 min)
mvn -q exec:java -Dexec.mainClass=org.example.footballmanager.demo.service.proposal.MatchSimulationLauncher -Dexec.args=1440

# Batch diagnostics (10+ matches)
mvn -q exec:java -Dexec.mainClass=org.example.footballmanager.demo.service.proposal.ProposalBatchDiag

# UI viewer
# 1. Start ProposalViewerLauncher (port 8766)
# 2. Open /demo/service/ui/proposal/index.html
# 3. Click Generate or load match.json
```

**DB (if available):** `localhost:5432/sokker_db`, user `postgres`.
Loads `team_tactics_profile` for team 1 (4-4-2, version 5, 506 rules).
If DB is not available: fallback `/tactics_fallback.json`.

**Component scan:** `org.example.footballmanager.demo.service.proposal`
added to `@SpringBootApplication`.

---

## 9. BACKLOG (priorities)

Full prioritized task list is in `backlog.md`. Summary by priority:

| P | Focus |
|---|---|
| **P1** | Stats layer — per-team + per-player, all actions (biggest gap) |
| **P2** | Orchestrator slimming (extract BallResultHandler / DuelService / ActionLogService) |
| **P3** | Ball physics calibration (DEFLECT_R, readIntercept, goal plane) |
| **P4** | Player movement (obstacle go-around, per-tick refresh, carrier speed under pressure) |
| **P5** | Offside full implementation (tracking, per-pass check, retreat) |
| **P6** | Pass completion calibration (67% → ~98%) — deferred |
| **P7** | Rules stubs logic (VAR, Discipline, Offside, ThreatOverride — fill in later) |
| **P8** | Fatigue, transition, hard rules→boost, app log, rating, viewer enhancements |

---

## 10. RULES OF ENGAGEMENT

1. **Before any change** do `git commit` of the current state.
2. **Update `PROPOSAL_CURRENT_STATE.md`** whenever you change `PROPOSAL_PROGRESS.md`.
3. The engine package imports nothing outside the `proposal/` tree.
4. Authoritative values are in §2 and §4 — all deviations are bugs.
5. UI behaves IDENTICALLY to the `/demo/service` viewer — Generate only
   generates, Play loads + auto-starts, events appear 1-by-1.