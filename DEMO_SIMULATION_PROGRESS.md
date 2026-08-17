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
- no unified `ActionResult` model exists for every action type;
- ball state changes are not yet emitted as dedicated `BALL STATE` events;
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
