# Football Simulation Service Layer

## Overview

Self-contained match simulation engine under `org.example.footballmanager.demo.service`.
Zero dependency on `newLogic` or any other package outside `demo/service/`.

---

## Architecture (per corePrinciples.md)

```
Tactical Configuration
        ↓
   Tactical Service (TacticsRules, TacticalIntentEngine, TacticalPerspectiveTransformer)
        ↓
   Spatial / Positioning (PlayerSelectionEngine, MovementEngine)
        ↓
   Threat Assessment (ThreatAssessmentService)
        ↓
   Player Perception (PlayerPerceptionService)
        ↓
   Decision Selection (PlaymakingDecisionEngine, VisionFilter, OptionSelector)
        ↓
   Action Execution (ActionEngine, ExecutionQuality, BallMovementEngine)
        ↓
   Player-Ball Interaction (DuelEngine, DuelResolver)
        ↓
   Football Rules (FootballRulesService)
        ↓
   Event Resolution (EventBus, MatchRecorder)
        ↓
   Match State Transition (MatchState)
```

---

## Package Structure

```
demo/service/
├── MatchState.java                    ← Central mutable state (422 lines)
├── corePrinciples.md                  ← Authoritative architecture spec (1968 lines)
│
├── engine/
│   ├── ActionEngine.java              ← Action lifecycle: PASS/SHOT/CARRY/CROSS/CENTER/CLEAR/CHASE
│   ├── BallMovementEngine.java        ← Ball flight + carrier follow + pickup distance
│   ├── DecisionTraceService.java      ← Configurable debug traces for decisions
│   ├── DuelEngine.java                ← Duel detection + lifecycle + resolution coordination
│   ├── DuelResolver.java              ← Side-effect-free duel resolution (skill-weighted)
│   ├── EventBus.java                  ← Decoupled publish/subscribe (events, phases, goals, possession)
│   ├── ExecutionQuality.java          ← Pass/shot deviation from intended target
│   ├── FatigueService.java            ← Gradual fatigue, speed/decision/execution multipliers
│   ├── FootballRulesService.java      ← Offside, foul, card, restart type determination
│   ├── MovementEngine.java            ← Player movement with collision avoidance
│   ├── OptionSelector.java            ← PM-based decision selector (accuracy roll + weighted random)
│   ├── PlayerPerceptionService.java   ← What each player can see/know
│   ├── PlayerSelectionEngine.java     ← Closest/nearest player search by team/role
│   ├── PlaymakingDecisionEngine.java  ← Decision quality layer (scores action options)
│   ├── SimulationRandom.java          ← Seeded Random with seed tracking
│   ├── SimUtils.java                  ← Static geometry utilities
│   ├── TacticalIntentEngine.java      ← Assigns movement targets from TacticsRules
│   ├── ThreatAssessmentService.java   ← Contextual danger evaluation + tactical override
│   ├── TransitionService.java         ← Possession change detection + transition phase
│   └── VisionFilter.java              ← PM-based visibility filter for action types
│
├── model/
│   ├── Action.java                    ← Current action data (type, players, execution tracking)
│   ├── ActionOutcome.java             ← Enum: PASS_COMPLETED, PASS_LOOSE, SHOT_GOAL, etc.
│   ├── ActionType.java                ← Enum: CHASE, CARRY, PASS, SHOT, CROSS, CENTER, AERIAL
│   ├── Ball.java                      ← Ball data + BallState enum (IN_POSSESSION, IN_TRANSITION, LOOSE)
│   ├── DecisionContext.java           ← Immutable context for playmaking decisions
│   ├── DecisionOption.java            ← Mutable scored option with visibility flag
│   ├── DecisionType.java              ← Enum: PASS, THRU, CARRY, CLEAR, SHOT, CROSS, CENTER
│   ├── DuelOutcome.java               ← Enum: ATTACKER_WINS, DEFENDER_WINS
│   ├── DuelType.java                  ← Enum: CHASE_BALL, DRIBBLE, RECEIVE_PASS, SHOT, AERIAL
│   ├── GameContext.java               ← Score, time, urgency, risk tolerance
│   ├── GoalRecord.java                ← Record: minute, scorerId, scorerLabel, team
│   ├── MatchPhase.java                ← Enum: OPEN_PLAY, ATTACK, TRANSITION_TO_*, SET_PIECE, etc.
│   ├── PassHeight.java                ← Enum: GROUND, AIR
│   ├── PassLength.java                ← Enum: SHORT, LONG, THRU
│   ├── Player.java                    ← Player data (id, label, team, role, skills, position, fatigue)
│   ├── PlayerIntent.java              ← Enum: RETURN_TO_SHAPE, PRESS, MARK, INTERCEPT, etc.
│   ├── PlayerSkills.java              ← Record: 8 abilities (pace, stamina, keeper, technique, etc.)
│   ├── Position.java                  ← Value object (row, column) on 9x8 grid
│   ├── SpaceInfo.java                 ← Record: pressure, openness, passLaneScore, shotLaneScore
│   ├── TeamSide.java                  ← Enum: HOME, AWAY
│   └── ThreatLevel.java              ← Enum: NONE, LOW, MEDIUM, HIGH, CRITICAL
│
├── recording/
│   ├── MatchEvent.java                ← Record: immutable event (tick, round, actionId, type, description)
│   ├── MatchRecorder.java             ← Append-only recorder for events + tick snapshots
│   ├── MatchRecording.java            ← Record: complete match recording read model
│   ├── MatchSnapshot.java             ← Record: scene snapshot at a specific tick
│   └── PlayerSnapshot.java            ← Record: player state at a specific tick
│
└── tactics/
    ├── FormationSlotCatalog.java      ← Formation anchor positions (4-4-2, 4-3-3, etc.)
    ├── TacticalPerspectiveTransformer.java ← HOME↔AWAY coordinate mirroring
    ├── TacticsRules.java              ← 3-tier loader: DB → bundled JSON → catalog anchors
    ├── TacticsRuleDTO.java            ← DTO: (slot, ballState, possession) → targetCell
    └── TacticsSlotDTO.java            ← DTO: slot key, label, role, line, anchor cell
```

---

## How Each Service Works

### MatchState (Central State)

The single source of truth. All engines read/write through this object.

**Key fields:** players, ball, carrier, action, phase, score, matchTicks, goals, passAttempts, passCompletions, shotsOnTarget, activeChasers, duelCooldowns, tactical positions.

**Key methods:** `advanceMatchClock()`, `startMatchSimulation()`, `recordGoal()`, `beginRound()`, `matchClockLabel()`, `matchMinute()`.

### ThreatAssessmentService (§4.4)

Evaluates contextual danger from 4 sources:
- **Ball threat** — how dangerous is the ball position for our goal
- **Proximity threat** — opponents in dangerous positions
- **Danger zone threat** — ball near our goal
- **Numerical threat** — outnumbered in defensive third

Produces `PlayerThreat` per player: team threat score, personal pressure, whether to override tactics, and which intent to use.

### PlayerPerceptionService (§4.5)

Models what each player can perceive:
- **Ball awareness** — within 8 cells
- **Visible opponents** — within 5 cells
- **Visible teammates** — within 7 cells (peripheral vision)
- **Pressure level** — based on closest opponent distance
- **Passing lane check** — whether an opponent blocks the lane

Players do NOT have perfect information. They operate on a contextual subset.

### PlaymakingDecisionEngine + VisionFilter + OptionSelector

**Decision quality layer** (not execution quality):
1. `VisionFilter` determines which action types a player can see based on PM tier
2. `PlaymakingDecisionEngine` scores each visible option (PASS/THRU/CARRY/CLEAR/SHOT/CROSS/CENTER)
3. `OptionSelector` picks the best option using PM-based accuracy + weighted random fallback

**PM ≠ Passing.** Passing controls execution quality. Playmaking controls decision quality.

### ActionEngine

Manages the full action lifecycle:
- **CHASE** — ball pickup, no-progress timeout (600 ticks), stalemate resolution
- **PASS** — skill-based accuracy, receiver locking, loose ball on failure
- **THRU PASS** — behind-defense targeting
- **CARRY** — forward/lateral bias, no backward carry
- **SHOT** — goal detection, goalkeeper save, miss
- **CROSS/CENTER** — aerial target selection (height + technique + striker skills)
- **CLEAR** — defensive clearance under pressure

### ExecutionQuality

Skill-based deviation for PASS and SHOT:
- **PASS**: `maxDeviation = (20 - skill) * 0.15` cells. Within 1.5 → received, else loose.
- **SHOT**: `maxDeviation = (20 - skill) * 0.12` cells. Within 1.0 of goal → goal, else miss.

### DuelEngine + DuelResolver

**Detection**: finds closest opponent within 2.0 cells, with 15-tick cooldown and 6% random gate.

**Resolution**: skill-weighted probability with `random(0..5)` component. Supports CHASE_BALL, DRIBBLE, RECEIVE_PASS, SHOT, AERIAL.

### FootballRulesService (§15-16)

- **Offside**: second-to-last defender check, forward pass detection
- **Restart type**: CORNER, GOAL_KICK, THROW_IN from ball position
- **Foul**: probability based on defender skill and attacker technique
- **Cards**: YELLOW/RED based on position and foul severity

### TransitionService (§21)

Detects possession changes and manages transition phases:
- **TRANSITION_TO_ATTACK** — ball enters attacking third
- **TRANSITION_TO_DEFENSE** — ball enters defensive third
- **Urgency** — decays over 60 ticks, varies by role

### FatigueService (§24)

Gradual fatigue model:
- Running: +0.0003/tick (carrier: 3x)
- Standing: -0.0001/tick (recovery)
- Speed multiplier: 1.0 → 0.7 (max 30% reduction)
- Decision multiplier: 1.0 → 0.8 (max 20% reduction)
- Injury risk: >92% fatigue → small chance per tick

### SimulationRandom (§10)

Seeded Random with:
- Seed stored for reproducibility
- `skillWeighted(skill, bound)` — higher skill = higher average
- `derive(context)` — sub-simulation seeds

### GameContext (§25)

Immutable match context:
- Score difference drives urgency
- Trailing late = high urgency + high risk tolerance
- Leading late = low urgency + conservative

### EventBus (§37)

Decoupled event system:
- `EventListener` — general match events
- `PhaseChangeListener` — phase transitions
- `GoalListener` — goal events
- `PossessionChangeListener` — possession changes

### DecisionTraceService (§29-30)

Configurable debug traces:
- Records: tick, player, phase, position, threat, intent, candidates, scores, selection, random
- Query by player or tick range
- `dumpTraces()` for full debug output

---

## Tactical System

### TacticsRules (3-tier loading)
1. **DB** — `team_tactics_profile.rules_json` (PostgreSQL)
2. **Bundled JSON** — `/tactics_fallback.json` in classpath
3. **FormationSlotCatalog** — default anchor positions per formation

### Supported Formations
4-4-2, 4-3-3, 4-2-3-1, 4-1-4-1, 3-5-2, 5-3-2, 3-4-3, 4-5-1, 5-4-1

### TacticalPerspectiveTransformer
- HOME: physical = editor coordinates
- AWAY: physical = mirror both axes (8-row, 7-col)

---

## Grid

- 9 rows (0-8), 8 columns (0-7)
- Pitch: rows 1-7, columns 1-6
- Goals: (7, 3.5) for HOME attacking, (1, 3.5) for AWAY attacking

---

## Key Constants

| Constant | Value | Purpose |
|---|---|---|
| PLAYER_SPEED | 0.03 cells/tick | Non-carrier movement |
| BALL_SPEED | 0.094 cells/tick | Pass/shot flight |
| POSSESSION_RADIUS | 0.5 cells | Ball pickup threshold |
| DUEL_LOSS_TICKS | 60 ticks | Cooldown after losing duel |
| TRANSITION_WINDOW | 60 ticks | Duration of transition phase |
| CHASE_MAX_TICKS | 600 ticks | Max chase before stalemate |

---

## File Count

- **Engine files**: 18
- **Model files**: 18
- **Tactics files**: 5
- **Recording files**: 5
- **MatchState**: 1
- **Total**: 47 Java files
