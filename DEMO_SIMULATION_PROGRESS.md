# Demo Simulation — Current Architecture and Progress

This document is the source of truth for the standalone Swing football grid
demo in `org.example.footballmanager.demo`. It is separate from the Spring
match engines and is launched through `TacticalGridDemo`.

## Purpose

The demo is a small tick-based match simulator. Its current vertical is:

```text
Tactical rules / ball position
        ↓
Player movement
        ↓
Carrier
        ↓
Action decision
        ↓
Execution quality
        ↓
Duel detection / resolution
        ↓
Action result
        ↓
Ball state
        ↓
Next action
```

The action decision is intentionally still random. Decision, execution and
actual result must remain separate concepts.

## Composition root and responsibilities

```text
TacticalGridDemo
 ├─ DemoScenario              grid, colors, players and initial ball
 ├─ DemoPlayerFactory         PlayerDef → Player
 ├─ DemoSimulationFactory     SimulationEngine assembly
 ├─ DemoScenarioValidator      grid/player validation
 └─ DemoUI                    Swing rendering and controls
```

Simulation components:

| Component | Responsibility |
|---|---|
| `SimulationEngine` | Tick orchestration and action consequences |
| `SimulationState` | Mutable ball, players, action, cooldowns, restart and logs |
| `SimulationStepEngine` | One decision/rule step |
| `ActionEngine` | Action lifecycle and PASS/CARRY/SHOT/CLEAR execution |
| `Action` | Current action data and execution metadata |
| `ExecutionQuality` | Skill-based PASS/SHOT deviation |
| `MovementEngine` | Continuous player movement and collision avoidance |
| `BallMovementEngine` | Smooth ball flight and carrier following |
| `TacticalIntentEngine` | Tactical desired positions during play |
| `TacticsRules` | DB → JSON → formation-anchor tactical rule loading |
| `PlayerSelectionEngine` | Deterministic closest-player queries and chase ownership |
| `DuelEngine` | Spatial duel detection and active-duel lifecycle |
| `DuelResolver` | Side-effect-free skill/random duel calculation |
| `DuelResolutionCoordinator` | Shared duel logging and loser cooldown |

## Action lifecycle

`step()` makes one decision. `advance()` progresses the animation one tick.
No artificial pause is inserted between completed actions.

```text
DECISION
  ↓
ACTION STARTED
  ↓
MOVEMENT / BALL FLIGHT
  ↓
EXECUTION RESULT
  ↓
DUEL, when spatially relevant
  ↓
ACTION COMPLETED
```

PASS and SHOT complete only after the ball reaches the exact `actualTarget`.
Pickup is not based on the old broad half-cell tolerance.

## Ball states

`Ball.getBallState()` derives the state from carrier and target:

| State | Condition | Next flow |
|---|---|---|
| `IN_POSSESSION` | `carrier != null` | carrier chooses next action |
| `IN_TRANSITION` | `target != null`, no carrier | ball continues smooth flight |
| `LOOSE` | no carrier and no target | closest HOME and closest AWAY chase the ball |

During a loose-ball chase, both the closest HOME and closest AWAY players
pursue the ball; whichever reaches first becomes carrier. All other players
receive tactical movement targets. The ball itself does not move until a
player wins possession.

## Tactical movement

`TacticsRules` loads desired positions in this order:

1. PostgreSQL tactical profile;
2. bundled `tactics_fallback.json`;
3. formation catalog anchors.

The current `DemoScenario` still owns the initial player positions. Tactical
rules control desired movement, not initial setup.

Both HOME and AWAY players recalculate targets when the ball enters a new
tactical cell, including during PASS and SHOT flight. AWAY tactical positions
are mirrored via `TacticalPerspectiveTransformer` (both axes: `8-row, 7-col`).
AWAY team plays by the same principles as HOME — they choose PASS/CARRY/SHOT
when they have the ball, and they chase loose balls equally.

Movement is smooth and collision-aware, but it is not a complete physics,
pathfinding or inertia system.

## Decisions and hard action rules

For normal outfield play (both HOME and AWAY), the available decisions are:

```text
PASS, CARRY, SHOT (when the row allows shooting)
```

Hard rules currently enforced:

- a goalkeeper can never CARRY or SHOT;
- a goalkeeper chooses PASS to one of the two nearest eligible players,
  or CLEAR;
- a normal pass may not target row 1 (HOME) or row 7 (AWAY) or a goalkeeper;
- such a forbidden pass is converted into CLEAR;
- CLEAR sends the ball two to four rows forward toward the opponent's goal and
  then produces a loose ball;
- **no backward carry**: carrier cannot dribble backward; if `weightedForwardDr()`
  would return -1, it rerolls until forward (+1) or lateral (0);
- **no backward carry in final 2 rows**: in rows 6–7 (HOME) or 1–2 (AWAY),
  carrier can only carry forward (+1) or lateral (0), never backward;
- **no backward pass in final 2 rows**: in rows 6–7 (HOME) or 1–2 (AWAY),
  PASS receiver filter only allows same/forward row (cannot be backward);
- restart-specific passes, such as AWAY restart to the HOME goalkeeper, are
  explicit exceptions to the normal-pass restrictions;

## Execution quality

PASS and SHOT receive temporary demo skill values from 1 to 20.

```text
decision target
      ↓
execution skill
      ↓
actual ball target
      ↓
received / loose / out / goal / miss
```

Passes remain directed at the intended receiver. Skill affects how near or far
the actual landing position is. Short passes have a distance-aware deviation
cap so a poor short pass cannot miss unrealistically far from the receiver.

## Duel system

`DuelEngine` uses continuous coordinates and a configurable half-cell radius.
It supports:

```text
CHASE_BALL
DRIBBLE
RECEIVE_PASS
SHOT
```

The lifecycle is:

```text
detect
  ↓
create one active duel
  ↓
resolve exactly once
  ↓
log calculation and winner
  ↓
block loser for 3 seconds
  ↓
apply action consequence
  ↓
close duel
```

The goalkeeper is exempt from the loser freeze when the goalkeeper wins a shot
duel, so it can continue the save/rebound flow.

Relevant current skill mapping:

| Duel | Attacker | Defender |
|---|---|---|
| `CHASE_BALL` | speed | speed |
| `DRIBBLE` | dribbling | positioning placeholder |
| `RECEIVE_PASS` | positioning placeholder | positioning placeholder |
| `SHOT` | shooting | positioning placeholder for GK |

Resolution is skill-dominant with a small `random(0..5)` bonus. It does not
mutate the simulation directly. `DuelResolutionCoordinator` owns the shared
logging and freeze, while `SimulationEngine` applies possession/action effects.

## Shot and restart flow

Shots have three top-level outcomes:

```text
GOAL
MISS
SAVE
```

- `GOAL`: ball reaches the goal line, continues into row 8, then celebration;
- `MISS`: ball continues toward row 8 and AWAY goalkeeper restarts;
- `SAVE`: ball reaches the goalkeeper contact position and continues as a field
  rebound or corner rebound.

Corner rebound flow:

```text
GK contact
  ↓
smooth ball flight over row 0
  ↓
3 second hold
  ↓
exact top corner position: row 7, column 1 or 6
  ↓
ML/MR taker approaches
  ↓
2 second hold
  ↓
pass into the box
  ↓
RECEIVE_PASS duel
```

Side aut uses the exact left/right boundary of the pitch cells, not cell
centres. Goal-kicks and side restarts select the appropriate restart team and
player.

## Logging

Simulation messages are queued in `SimulationState` and drained by `DemoUI`
into the append-only Action Log. The same event is also printed once through
the App log path. The UI no longer prints a second identical console line.

Relevant telemetry includes:

```text
Action started
Action completed
DUEL START
DUEL CALC
DUEL END
GOAL
SAVE / MISS / CLEAR
restart and corner events
```

Coordinates in demo messages use two decimal places.

## Known limitations

These are intentionally not complete yet:

- initial player positions are scenario data, not DB tactical targets;
- action choice is random, without playmaking evaluation;
- receiving, tackling and goalkeeping are positioning placeholders;
- the timeline is intentionally separate from the legacy UI message buffer;
- corner tactical setup does not yet fully arrange every player in the box;
- movement has collision avoidance but no full pathfinding or inertia model;
- there is no fatigue, pressing, offside, foul or card layer in this demo;
- action choice is random (no skill-weighted selection yet);
- the demo is not the production `newLogic` match engine.

## Duel visualization and restart protection

When a duel is detected, the UI briefly shows an orange contest line, a
pulsing contest ring and a `DUEL <type>` label at the contest position. This is
visual-only state; the ball and players still move through the normal engine.
The effect makes the possession change readable before the winner starts their
next action (PASS, CARRY, or SHOT).

Restart approaches are protected from normal duel resolution. The restart
taker must first reach the restart coordinate; only after that does the normal
restart pass/goal-kick decision run. This prevents a nearby player from stealing
the ball during the short restart approach and sending the simulation back
into a restart/chase loop.

## Safe next extension points

Future changes should preserve these boundaries:

```text
Decision ≠ Execution ≠ Duel ≠ Result ≠ Ball State
```

Good next additions are a shared action-result model, explicit ball-state
telemetry, real player skills, and a dedicated set-piece positioning service.
Do not put these responsibilities back into one large `SimulationEngine`
method.

## Event timeline — Sprint B

The first persistence-oriented sprint introduced an append-only
`SimulationEventStore` in `SimulationState` and immutable event models. Every
started action receives a stable action id (`A-...`) and a monotonic simulation
tick. The runtime now appends:

```text
ActionStartedEvent
ActionResultEvent
BallStateChangedEvent
DuelEvent (STARTED / RESOLVED / ENDED)
```

`ActionOutcome` contains the stable outcome vocabulary for PASS, CARRY, SHOT,
CLEAR and CHASE, including pass interception by a duel winner. Action result
events retain intended target, actual target, execution skill, previous/new ball
state, carrier and duel winner. Duel events retain participants, type,
contest position, calculated powers and winner. This timeline is the source
for future replay/statistics projections; console/UI messages remain a separate
presentation path and existing behavior is unchanged.

## Tick snapshots — Sprint C

The demo now also stores a complete immutable `SimulationSnapshot` after every
animation tick in an append-only `SimulationSnapshotStore`. A snapshot contains
the ball position/target/state, carrier, active action metadata, score/status,
and every player's identity, position, target, lock and velocity.

The snapshot is captured in a `finally` block around tick execution, so early
return paths (holds, restarts, celebrations and completed actions) are recorded
as well. Replay consumers can read the saved scene timeline directly instead
of rebuilding it from console output or re-running random simulation decisions.

## Chase and goal-animation correction

An active loose-ball chase sets both the closest HOME and closest AWAY players
as active chasers. Both pursue the ball simultaneously; whichever reaches first
becomes carrier. A side waypoint is created around a blocking player when the
direct movement proposal is fully blocked. Once the waypoint is reached, the
chaser resumes the ball target; the ball itself never moves during this detour.

### CHASE lifecycle fix (2026-08-18)

**Problem fixed:** end-of-match deadlock when two chasers converged on a loose
ball but collision avoidance prevented exact coordinate pickup (`1e-9` threshold).

**Current rules:**
- Pickup uses **`POSSESSION_RADIUS = PICKUP_DISTANCE` (0.5 cells)**, not exact
  coordinate equality.
- `DuelEngine` opens `CHASE_BALL` when the closest active chaser is within
  possession radius and an opposing chaser is within duel radius.
- **Progress guard:** 40 consecutive ticks without meaningful distance reduction
  → forced resolution to closest eligible player.
- **Hard timeout:** 600 ticks (~30s) → `CHASE TIMEOUT` with closest-player
  assignment.
- **Blocked chaser handoff:** if acting chaser stalls and rival is closer,
  `CHASE_CONTINUE` is recorded and a new chase starts on the next `step()`.
- Logging: `CHASE START`, `CHASE TICK`, `CHASE RESOLUTION`, `CHASE TIMEOUT`,
  `CHASE NO PROGRESS`.

**Regression tests:** `ChaseDeadlockTest`, `ChaseDeadlockDiagnosticsTest`.

Goal qualification still uses the actual goal line at `(7, 3.5)`. After the
goalkeeper duel, the visual exit target is calculated as a continuation of the
shot's incoming vector toward row 8, avoiding an artificial right-angle turn.
Celebration targets are split across `(8,1)`, `(8,2)` and `(8,3)` based on the
players' side of the pitch, with a small local orbit once they arrive.

When the attacker wins a `DRIBBLE` duel, the defender remains frozen for the
normal three-second duel-loss cooldown while the carrier receives a temporary
half-cell side bypass target. After reaching that point, the original carry
target is restored, making the successful dribble visible instead of an
instantaneous possession switch.

For a successful shot, `(7,3.5)` is now only the logical goal-line boundary
used for outcome/GK resolution. The animated ball target is `(8,3.5)` from the
start, so an angled shot does not visibly turn at the goal line. A save switches
the target to the selected rebound path only after the goalkeeper resolution.
Celebration players use three side groups with independent orbit phases around
the three row-8 cells.

## Recording boundary — Sprint D

`SimulationRecording` is the read boundary for persistence consumers. It
contains immutable copies of the append-only action/duel event timeline and
per-tick scene snapshots, plus the current score. The initial scene and reset
scenes are also captured, so replay consumers do not need to infer them from
the first or last action. `SimulationEngine#getRecording()` exposes this
aggregate without exposing mutable `SimulationState` internals.

The action audit also records a `CHASE_CONTINUE` result when a blocked chaser
is replaced. A replay can therefore distinguish an unfinished pursuit handed
to a new player from a possession pickup or a silent action reset.

`SimulationReplayQueries.goalOnly(recording, before, after)` provides the first
replay projection: it selects already saved snapshots around `SHOT_GOAL`
events. Goal-only playback therefore uses persisted frames and does not invoke
the random decision or movement engines again.

The Swing demo exposes this through `Replay Last` and `Replay Goals` buttons.
Replay applies one saved frame per animation timer tick; stopping it leaves the
simulation ready for a fresh live decision instead of reconstructing a stale
in-flight action.

## Match presentation

The Swing demo now displays `OFK Omladinac` as HOME and `FK Mladost` as AWAY,
with a live score and match clock. The clock advances at 20 simulation ticks
per match minute, pauses for 12 seconds at half-time, resumes at minute 46,
and runs through three minutes of added time (`90+1` to `90+3`) before marking
the match finished. Home presentation statistics currently include pass
attempts, completed passes, pass accuracy, shots on target and goals. These
values and the match-clock state are included in tick snapshots for replay.
The manual `Reset State` control starts a fresh match; the automatic reset after
a goal only resets positions and preserves the running score/clock.

The match clock is gated by an explicit `matchStarted` state. The Swing control
is labelled `Start match simulation` before kickoff and `Stop match simulation`
while automatic execution is active; it uses a pale-green button style.

## Updated Duel Model (2026-08-18)

**PlayerSkills (1–20):**
- `pace` — movement speed, fatigue resistance
- `stamina` — duel physical component + fatigue resistance
- `keeper` — shot saving (GK primary)
- `technique` — execution of all actions (receive, dribble, control)
- `playmaking` — decision quality / breadth of available choices
- `passing` — pass execution accuracy
- `striker` — shot quality + power
- `defender` — defensive duel / tackle / deflection

**Physical attributes (Player):**
- `heightCm` — 170–200 cm per role (DEF/ST: 180–200, GK: 185–200, MID: 170–190)
- `heightSkill()` — normalized 1–20: `(heightCm - 160) / 2`

**Duel formulas — EFFECTIVE POWER = weighted_skills + situational + random(0..3)**

| Duel | Attacker | Defender |
|---|---|---|
| `SHOT` | STRIKER×0.50 + TECHNIQUE×0.30 + distance_bonus(0–5) + angle_bonus(0–3) | KEEPER×0.60 + TECHNIQUE×0.25 + height_bonus(0–4) |
| `DRIBBLE` | TECHNIQUE×0.45 + PLAYMAKING×0.25 + PACE×0.20 + STAMINA×0.10 | DEFENDER×0.45 + PACE×0.25 + PLAYMAKING×0.20 + STAMINA×0.10 |
| `RECEIVE_PASS` | TECHNIQUE×0.50 + PLAYMAKING×0.30 + PACE×0.20 | DEFENDER×0.45 + PLAYMAKING×0.25 + PACE×0.20 + STAMINA×0.10 |
| `CHASE_BALL` | PACE×0.60 + STAMINA×0.20 + TECHNIQUE×0.20 | (same) |
| `AERIAL` | HEIGHT×0.40 + TECHNIQUE×0.30 + STRIKER×0.20 + PACE×0.10 | HEIGHT×0.40 + DEFENDER×0.30 + TECHNIQUE×0.20 + PACE×0.10 |
| `TACKLE/PRESS` | — | DEFENDER×0.40 + PACE×0.25 + PLAYMAKING×0.15 + STAMINA×0.10 + TECHNIQUE×0.10 |

Random range reduced to `0..3` so skill differences are decisive.

---

## Pass System (2026-08-18) — IMPLEMENTED

Pragmatic pass model: **occasional** ground interception, **occasional**
deflection, no separate trajectory service classes. Geometry, skill and outcome
are handled inline in `SimulationEngine` / `ActionEngine`.

Every pass has two independent properties:

### Length (namera)
- **SHORT** ≤ 5 cells — high accuracy, ground preferred
- **LONG** 5–15 cells — lower accuracy, can be air
- **THRU** — targets space 1–2 cells ahead of runner (40% chance in opponent
  half via `ActionEngine.executePass`)

### Height (visina)
- **GROUND** — can be intercepted during flight; deflection on failed control
- **AIR** — no mid-flight interception; start deflection if opponent ≤ 1.0
  cells from passer; destination contest uses `AERIAL` duel

Combinations: SHORT_GROUND, SHORT_AIR, LONG_GROUND, LONG_AIR, THRU_GROUND,
THRU_AIR.

### Pass execution quality
- Skill source: **`passer.getSkills().passing()`** (not random 1–20)
- Deviation: `(20 − skill) × 0.15 × lengthMultiplier`, capped by pass length
- SHORT ×0.6, LONG ×1.3, THRU ×0.9; AIR ×1.4 lateral multiplier
- Success: SHORT/LONG within **1.5** cells of receiver; THRU within **2.0**
  cells of runner
- `executionOrigin` stored on every pass for interception geometry

### GROUND interception (per tick, not every pass)
- Skipped for **clearance** (`!action.isClearance()`)
- Ball progress filter: **10%–90%** along pass line
- Defender projected onto **remaining** trajectory `[ballPos, actualTarget]`
- Evaluated only in the tick window where projection falls within one
  `BALL_SPEED` segment ahead of the ball
- Timing: `Δ = t_player − t_ball ≤ 0` (PACE scales movement speed)
- Three skill stages (short-circuit):
  - **READ:** PLAYMAKING×0.60 + DEFENDER×0.40 + random(0..5) > **12**
  - **CONTACT:** DEFENDER×0.55 + TECHNIQUE×0.30 + PACE×0.15 + random(0..4) > **13**
  - **CONTROL:** TECHNIQUE×0.50 + DEFENDER×0.30 + PLAYMAKING×0.20 + random(0..4) > **12**
- All three pass → **INTERCEPTION** (possession to defender)
- READ+CONTACT pass, CONTROL fail → **GROUND DEFLECTION** (loose ball at
  random offset 0.3–0.8 cells from contact point via `deflectionLoose`)
- Best candidate: smallest `Δ` among eligible interceptors

### AIR deflection (at pass start only)
- Opponent ≤ 1.0 cells from passer
- Roll: deflector HEIGHT×0.40 + TECHNIQUE×0.30 + DEFENDER×0.30 vs passer
  protection HEIGHT×0.20 + TECHNIQUE×0.30
- On success: **`actualTarget`** redirected 0.5–1.5 cells from passer in random
  direction; ball flies to new target; `goodExecution = false`
- On arrival: normal pass-fail / loose flow (not instant termination)

### Destination duels
- GROUND pass → `RECEIVE_PASS`
- AIR pass / CROSS / CENTER → `AERIAL`

### Final row pass/carry restrictions (2026-08-18)
- **HOME rows 6-7**: PASS allowed but receiver filter only allows same/forward row (no backward passes)
- **AWAY rows 1-2**: PASS allowed but receiver filter only allows same/forward row (no backward passes)
- **HOME rows 6-7**: CARRY only forward (+1) or lateral (0), never backward
- **AWAY rows 1-2**: CARRY only forward (-1) or lateral (0), never backward

### Kickoff and chase fixes (2026-08-18)
- **Kickoff**: striker positioned exactly at center (row 4, column 3.5), first action must be pass backward
- **Pass stats**: fixed inconsistent team parameter in incrementPass calls
- **CHASE pickup**: player must reach exact ball position (0.01 tolerance), not just possession radius
- **General pickup**: exact coordinate requirement (0.01 tolerance) for all ball pickups
- Contest position for AIR: `actualTarget` (landing point), not receiver position
- `pickupPass` requires receiver within `PICKUP_DISTANCE`; else loose ball

### Intentionally NOT implemented (kept simple)
- Separate `PassTrajectory` / `InterceptionWindow` / `DeflectionResolver` classes
- Full earliest-interception-point optimization from design review 2.1
- Form / fatigue modifiers on skills
- Dedicated `ActionOutcome.DEFLECTION` (uses `PASS_LOOSE`)

---

## Action Types Added
- `PASS` with `PassLength` (SHORT/LONG/THRU) and `PassHeight` (GROUND/AIR)
- `CROSS` — wing to box, aerial target selection
- `CENTER` (centaršut) — final third to box, aerial target
- `AERIAL` — header duel in box
- Duel types extended: `AERIAL`

## Decision Logic (SimulationStepEngine)
- Final third on wing → CROSS, CENTER, CARRY, SHOT×2
- Final third central → CENTER, PASS, CARRY, SHOT×2
- Shoot zone not final third → PASS, CARRY, SHOT×2
- Elsewhere → PASS, CARRY

## Testing

All **29** demo tests pass (`mvn test -Dtest=ChaseDeadlockTest,ChaseDeadlockDiagnosticsTest,SimulationArchitectureTest,DemoArchitectureTest,DuelResolutionTest,ExecutionQualityTest,TacticalPerspectiveTransformerTest`):

| Test class | Focus |
|---|---|
| `SimulationArchitectureTest` | Action lifecycle, PASS/SHOT/THRU consistency |
| `ChaseDeadlockTest` | Loose-ball chase termination regression |
| `ChaseDeadlockDiagnosticsTest` | Full match to final whistle (seed=7) |
| `DemoArchitectureTest` | Composition root / scenario validation |
| `DuelResolutionTest` | Duel formulas and coordinator |
| `ExecutionQualityTest` | Pass/shot deviation |
| `TacticalPerspectiveTransformerTest` | AWAY mirroring |
