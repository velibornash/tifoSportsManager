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
| `LOOSE` | no carrier and no target | one HOME player starts CHASE |

During a loose-ball chase, exactly one HOME player owns the active chase target.
If a chase player is blocked, old chase targets are cleared before selecting a
replacement. The ball itself does not move until a player wins possession.

## Tactical movement

`TacticsRules` loads desired positions in this order:

1. PostgreSQL tactical profile;
2. bundled `tactics_fallback.json`;
3. formation catalog anchors.

The current `DemoScenario` still owns the initial player positions. Tactical
rules control desired movement, not initial setup.

HOME players recalculate targets when the ball enters a new tactical cell,
including during PASS and SHOT flight. AWAY players are intentionally mostly
static in this demo. They may move only for active chase/restart, clearance,
corner and other explicitly modelled set-piece situations.

Movement is smooth and collision-aware, but it is not a complete physics,
pathfinding or inertia system.

## Decisions and hard action rules

For normal HOME outfield play, the available decisions are:

```text
PASS, CARRY, SHOT (when the row allows shooting)
```

Hard rules currently enforced:

- a goalkeeper can never CARRY or SHOT;
- a goalkeeper chooses PASS to one of the two nearest eligible HOME players,
  or CLEAR;
- a normal HOME pass may not target row 1 or a goalkeeper;
- such a forbidden pass is converted into CLEAR;
- CLEAR sends the ball two to four rows forward toward the AWAY goal and then
  produces a loose ball;
- an AWAY player who wins a duel does not start a normal attack toward its own
  goal; it performs a clearance and returns to its alternative position;
- restart-specific passes, such as AWAY restart to the HOME goalkeeper, are
  explicit exceptions to the normal-pass restrictions.

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
- the demo is not the production `newLogic` match engine.

## Duel visualization and restart protection

When a duel is detected, the UI briefly shows an orange contest line, a
pulsing contest ring and a `DUEL <type>` label at the contest position. This is
visual-only state; the ball and players still move through the normal engine.
The effect makes the possession change readable before the winner starts a
clearance or the next action.

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

An active loose-ball chase now creates a side waypoint around a blocking player
when the direct movement proposal is fully blocked. Once the waypoint is
reached, the chaser resumes the exact ball coordinate; the ball itself never
moves during this detour. This keeps one active chaser while preventing a
permanent visual deadlock.

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

## Runtime diagnostics and carrier fallback

Every console application log line now starts with a local 24-hour timestamp:
`[AppLog] DD-MM-YYYY HH:MM:ss`. CARRY obstruction detection uses meaningful
distance progress rather than only exact zero movement. Tiny collision-avoidance
slides therefore still count as blocked; after three such ticks the carrier
falls back to PASS so two HOME players cannot keep the action in an apparent
collision loop.
