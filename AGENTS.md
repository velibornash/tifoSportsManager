# AGENTS.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project Summary

TIFO Sports Manager is a multi-sport club management simulation game. The backend is a Spring Boot 3.3.3 REST API (Java 21) and the frontend is vanilla JS (ES6 modules) served as static files from the same Spring Boot app. PostgreSQL is the production database; H2 in-memory is used for tests. Main class: `org.example.footballmanager.SportsManagerApplication`.

## Build & Run Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Run locally (dev profile, requires PostgreSQL on localhost:5432)
mvn spring-boot:run

# Run all tests (uses H2 in-memory via test profile)
mvn test

# Run a single test class
mvn test -Dtest=RealisticMatchEngineTest

# Run a single test method
mvn test -Dtest=RealisticMatchEngineTest#testSomeMethod

# Build Docker image (uses image.dockerfile, not Dockerfile)
docker build -f image.dockerfile -t tifo-manager .

# Export match for web viewer (no Spring Boot needed)
mvn exec:java -Dexec.mainClass=org.example.footballmanager.demo.service.ui.MatchSnapshotExporter -Dexec.args=42
```

> **Note:** The compiler is configured with `--enable-preview` for Java 21. If invoking `javac` directly outside Maven, include this flag.

### Playwright UI Tests (TifoUITest)

Before running UI tests for the first time, install Playwright browsers:
```bash
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install"
```

UI tests (`TifoUITest`) require a running application on `http://localhost:8080`. They are `setHeadless(false)` by default — set to `true` for CI. Run separately:
```bash
mvn test -Dtest=TifoUITest
```

## Environment & Configuration

| Profile | Activated by | Database |
|---------|-------------|----------|
| `dev` (default) | `application.properties` sets `spring.profiles.active=dev` | PostgreSQL `localhost:5432/sokker_db`, user `postgres` |
| `prod` | Deploy env vars | `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` env vars |
| `test` | `@ActiveProfiles("test")` in test classes | H2 in-memory (`jdbc:h2:mem:testdb`) |

The JWT secret and expiration for tests are hardcoded in `src/test/resources/application-test.properties`.

## Backend Architecture

**Base package:** `org.example.footballmanager`

### Layer Overview

```
controller/   → REST controllers (one per domain, see routes below)
service/      → Business logic
engines/      → Match simulation engines
model/        → JPA entities
  model/event/    → MatchEvent hierarchy (Goal, Card, Injury, Sub, VAR, etc.)
  model/tactics/  → Tactics/formation models
repository/   → Spring Data JPA repositories
config/       → SecurityConfig, JwtAuthenticationFilter, WebSocketConfig, AppConfig
dto/          → Request/response DTOs
  dto/junior/
  dto/training/
  dto/transfer/
util/         → Stateless helpers
  util/match/     → MatchRatingCalculator, MatchContext, MatchReplayService, MatchAnalyticsService
  util/CPlayers/
  util/teams/     → TeamStrengthCalculator
  util/events/    → MatchEventMapper
  util/websocket/
zox/          → ZoxReplayService (serves tick chunks for realistic match viewer)
cleanSheet/   → Legacy/alternative match engine (not the primary path)
old/          → Legacy controllers/services/simulators (do not extend)
exception/    → ApiExceptionHandler (global @ControllerAdvice)
```

**`demo/` package** — standalone Swing football grid simulation (not part of the Spring app). Composition root is `TacticalGridDemo` (`main()`), which wires:

The detailed current architecture, rules, limitations and extension points are
maintained in [DEMO_SIMULATION_PROGRESS.md](DEMO_SIMULATION_PROGRESS.md).

```
TacticalGridDemo          → composition root (main, static test delegates)
  ├── DemoScenario        → grid config, colors, teams, 22 player defs, ball start
  ├── DemoPlayerFactory   → PlayerDef → Player objects (random skills 1–20 per role)
  ├── DemoSimulationFactory → assembles SimulationEngine (+ TacticsRules)
  ├── DemoScenarioValidator → validateGrid / validatePlayers
  └── DemoUI              → all Swing rendering + interaction (speaks to engine API only)
```

Simulation core: `SimulationEngine` (orchestrator facade) → `SimulationState` (mutable state),
`SimulationStepEngine` (decisions), `ActionEngine`/`Action` (PASS/CARRY/PASS/SHOT/CHASE lifecycle),
`ExecutionQuality` (skill-based deviation for PASS/SHOT),
`MovementEngine`/`BallMovementEngine` (geometry), `TacticalIntentEngine` + `TacticsRules`
(DB-loaded tactical rules), `PlayerSelectionEngine` (closest/nearest selection).

**Decision quality layer** (playmaking): `PlaymakingDecisionEngine` delegates to
`VisionFilter` (PM vision tiers — which action types are visible) and
`OptionSelector` (PM decision-accuracy table + weighted-random fallback).
`DecisionContext` / `DecisionOption` / `DecisionType` are the immutable data
model passed between them. See `DEMO_SIMULATION_PROGRESS.md` for full details.

### Demo duel architecture

`DuelEngine` detects one deterministic active opponent contest using continuous
coordinates and a configurable 0.5-cell radius. It supports `CHASE_BALL`,
`DRIBBLE`, `RECEIVE_PASS`, and `SHOT`, and logs lifecycle events. `DuelResolver`
is a side-effect-free resolution layer: it maps `PlayerSkills` fields
(`pace`, `technique`, `striker`, `defender`, `keeper`) to the relevant skill,
adds only `random(0..5)`, and returns `DuelResult` with winner, outcome, ball
state, possession, and power values.

`DuelResolutionCoordinator` is the boundary between detection/resolution and
match consequences. It applies the shared duel calculation log and loser
cooldown exactly once, while `SimulationEngine` remains responsible for the
resulting possession, clearance, carry, pass, save, or shot consequence.

`PlayerSkills` has 8 football-relevant fields (each 1–20):
`pace`, `stamina`, `keeper`, `technique`, `playmaking`, `passing`, `striker`, `defender`.
`DemoPlayerFactory` generates random skills per role via `PlayerSkills.randomForRole()`.
`SimulationEngine` applies the result after execution: Chase/Carry/Receive can
change the carrier, while a goalkeeper can save a good shot. Poor shot
execution remains a miss and does not create a duel result. The resolver itself
never mutates state; consequences are centralized in `ActionEngine`.

The demo action lifecycle has no artificial post-action hold, and PASS/SHOT
completion is checked only after the ball reaches its exact animation target.
Duel losers are blocked for 3 seconds;
the goalkeeper is exempt when the goalkeeper wins a shot duel. All action and
duel lifecycle/calculation messages are appended to the Action Log and mirrored
to the App log.

Shots have three result families: GOAL, MISS, and SAVE. A save continues as a
smooth field rebound or corner rebound. Corner rebounds travel through row 0,
hold for 3 seconds, return to the exact top corner point (row 7, column 1 or 6),
then the side-specific ML/MR taker holds for 2 seconds and passes into the box.
The receiver is still subject to the normal RECEIVE_PASS duel flow. Coordinates
printed in demo logs are formatted to two decimal places.

**Playmaking** is implemented as a decision-quality layer
(`PlaymakingDecisionEngine`). PM determines (1) which action types a player
*can see* via `VisionFilter` (vision tiers by PM bracket), and (2) the
probability of selecting the highest-scoring visible option via
`OptionSelector` (PM→accuracy table with linear interpolation, plus
weighted-random fallback for character). PLAYMAKING ≠ PASSING: passing controls
execution quality, playmaking controls decision quality. `PlayerSelectionEngine.selectBestCandidate()`
and `ActionCandidate` remain INERT — next sprint introduces real positioning
selection logic there.

### Mid-Action Movement (All Actions)

During ANY action (PASS, SHOT, CARRY, CHASE), every `advance()` tick:
1. `TacticalIntentEngine.refreshTargetsIfBallStateChanged()` — when ball crosses a new grid cell,
   all non-carrier players (both teams) recalculate their tactical desired position from `TacticsRules`.
2. `MovementEngine.moveAllTowardTargets()` — players move toward their recalculated targets.

This means players reposition dynamically during ball flight AND during carrier movement.

**Design principle — three-phase action lifecycle:**
1. **Decision** (once, at `step()`) — playmaking decision engine chooses PASS/CARRY/SHOT/CLEAR/THRU/CROSS/CENTER; does NOT change during action
2. **Movement** (every `advance()` tick) — players reposition relative to ball; updates mid-action
3. **Outcome** — result of the action (pass received/loose, shot goal/miss)

The decision is fixed for the action's duration. Movement reacts to ball trajectory.
When the action completes (regardless of outcome), a new action starts with the same principles.

### Execution Quality (PASS / SHOT)

Every PASS and SHOT generates a temporary demo skill (`random.nextInt(20) + 1`, value 1–20).
This skill determines how accurately the ball reaches its intended target:

- **PASS**: `maxDeviation = (20 - skill) * 0.15` cells. Skill 1 → up to 2.85 cells off, skill 20 → perfect.
  - If actual target is within 1.5 cells of receiver → **RECEIVED** (receiver gets ball)
  - If further → **LOOSE BALL** (ball free, triggers automatic CHASE recovery)
- **SHOT**: `maxDeviation = (20 - skill) * 0.12` cells. Goal at (7, 3.5).
  - If actual target is within 1.0 cells of goal → **GOAL** (celebration)
  - If further → **MISS — LOOSE BALL** (ball resets to center)

`ExecutionQuality` class encapsulates all deviation logic. `Action` stores skill, intendedTarget,
actualTarget, goodExecution for logging and result evaluation.

### Ball States

`Ball.BallState` — derived from carrier/target fields:
- **IN_POSSESSION** — `carrier != null` (ball controlled by player)
- **IN_TRANSITION** — `target != null, carrier == null` (ball flying: PASS/SHOT)
- **LOOSE** — `carrier == null && target == null` (free ball, triggers CHASE)

### Loose Ball Recovery

When PASS/SHOT results in LOOSE ball:
1. `carrier = null` (cleared in `passFailed()`/`shotMissed()`)
2. Next `step()` finds closest HOME and closest AWAY player via `PlayerSelectionEngine.closestTeamTo()`
3. Both chase the ball; whichever reaches first becomes carrier (all others get tactical targets)
4. The carrier chooses a normal action: PASS, CARRY, or SHOT
5. Both teams play by the same principles — AWAY tactical positions are mirrored via `TacticalPerspectiveTransformer`

SHOT miss additionally resets ball position to initial center.

### Collision Avoidance (Wall Behavior)

Players act as **walls** — cannot pass through each other. When blocked:
1. Try perpendicular slide (left/right relative to movement direction)
2. Try component-only fallback (X only, Y only)
3. If all blocked, stay in place

When a carrier is stuck (can't move at all), target is cleared so the action completes.
When a CHASE stalls with zero progress, a blocked chaser may hand off via
`CHASE_CONTINUE`; timeout/no-progress guards force resolution before match hang.
This prevents simulation freezes from deadlocks.

### Movement Constraints

- **1-cell round limit**: non-carrier players cannot move more than 1 cell from their round-start position
- **Carrier**: moves directly toward target (no inertia) — action completion depends on carrier reaching destination
- **Speed**: `PLAYER_SPEED = 0.03` cells/tick (non-carrier), carrier speed varies by action type

### Ball Speed

- `BALL_SPEED = 0.094` cells/tick (pass/shot flight)
- `CARRIER_FOLLOW_SPEED = 0.11` cells/tick (ball follows carrier)

### Action Constraints

- **No backward carry**: carrier cannot dribble backward; if `weightedForwardDr()` would return -1, it rerolls until forward (+1) or lateral (0). Result: ~67% forward, ~33% lateral.
- **No backward pass in final 2 rows**: in rows 6–7 (HOME attacking away goal) or rows 1–2 (AWAY attacking home goal), PASS is removed from action options. Carrier can only SHOT or CARRY (dribble).
- **Both teams play by the same principles**: AWAY team chooses PASS/CARRY/SHOT just like HOME when they have the ball. Tactical positions are mirrored via `TacticalPerspectiveTransformer` (both axes: `8-row, 7-col`).
- **Loose ball**: both teams chase equally — closest HOME and closest AWAY pursue; all other players get tactical targets.
- **CHASE pickup**: possession radius 0.5 cells (not exact coordinate); progress guard + 600-tick safety timeout prevent deadlocks when chasers collide.

### UI Circle Sizes

Player radius: 18px, ball radius: 12px. Carrier ring: 26px outer. Select radius: 25px.
Fan-stack rendering removed — players overlap directly on same cell.

### `demo/service/` Engine — Service-Oriented Match Simulation

Standalone service-engine under `org.example.footballmanager.demo.service`.
**Zero dependency on `demo/`, `newLogic/`, or any other package outside `demo/service/`.**
Source of truth for architecture: `corePrinciples.md` (inside this package).

Progression tracker: `demoServiceProgression.md`.

```
demo/service/
  ├── corePrinciples.md              → authoritative design specification (§1-48)
  ├── demoServiceProgression.md      → current status, what's done, what's next
  ├── MatchState.java                → authoritative match state container
  ├── MatchRunner.java               → orchestrates simulation from initial state
  ├── MatchBatchRunner.java          → main() — 10-match batch diagnostic
  ├── MatchChainTrace.java           → main() — first 10-minute chain trace
  ├── engine/
  │   ├── PlaymakingDecisionEngine   → action scoring & selection (PASS/CARRY/SHOT/THRU/CROSS/CLEAR)
  │   ├── VisionFilter               → PM-based action visibility tiers
  │   ├── OptionSelector             → weighted random among close options (§9.4)
  │   ├── ActionEngine               → PASS/CARRY/SHOT/THRU/CROSS/CLEAR execution
  │   ├── ExecutionQuality           → pass/shot deviation based on skill
  │   ├── MovementEngine             → tactical targets + collision avoidance + fatigue speed
  │   ├── BallMovementEngine         → ball transit & carrier following
  │   ├── TacticalIntentEngine       → tactical targets from TacticsRules
  │   ├── DuelEngine                 → DRIBBLE/RECEIVE/SHOT/CHASE_BALL duel detection
  │   ├── DuelResolver               → skill-based duel resolution
  │   ├── FootballRulesService       → offside, fouls, cards, corners, goal kicks, throw-ins
  │   ├── VARService                 → VAR reviews (offside, goal, red, penalty) with frequency gates
  │   ├── RestartManager             → kickoff, corners, goal kicks, throw-ins (§37 extraction)
  │   ├── OffsideService             → offside checks + VAR review + free kick awarding (§37 extraction)
  │   ├── DisciplineService          → foul→card→VAR→penalty/free-kick decisions (§37 extraction)
  │   ├── ThreatAssessmentService    → danger evaluation for defensive overrides
  │   ├── PlayerPerceptionService    → awareness-based perception
  │   ├── PlayerSelectionEngine      → nearest/closest player queries
  │   ├── FatigueService             → stamina drain + speed multiplier
  │   ├── TransitionService          → possession change transitions
  │   ├── SimUtils                   → clamp, distance, helpers
  │   ├── SimulationRandom           → seeded random source
  │   └── DecisionTraceService       → structured decision debug output
  ├── model/
  │   ├── Player, Ball, Position, Action, ActionType, DecisionType, DecisionOption,
  │   │   DecisionContext, DuelType, DuelOutcome, PlayerSkills, MatchPhase, etc.
  ├── tactics/
  │   ├── TacticsRules               → tactical target resolution from config
  │   ├── TacticalPerspectiveTransformer → mirrors AWAY tactical positions
  │   ├── FormationSlotCatalog       → formation slot definitions
  │   └── TacticsSlotDTO/TacticsRuleDTO → tactical configuration DTOs
  ├── result/
  │   ├── MatchSimulator             → tick-based simulation loop (§19)
  │   ├── MatchStatsCollector        → statistics derivation from events (§32)
  │   ├── ActionLogService           → structured action/decision logging
  │   └── MatchReport/MatchResult/PlayerMatchStats/TeamMatchStats → result models
  ├── recording/
  │   ├── MatchRecorder              → event & snapshot recording
  │   ├── MatchEvent, MatchSnapshot, MatchRecording, PlayerSnapshot
  ├── ui/
  │   └── MatchSnapshotExporter      → headless exporter: runs match → writes match.json
  └── controller/
      └── MatchSimulationController  → REST API for match simulation
```

### `demo/service/ui/` — Match Viewer (Web)

Canvas-based horizontal pitch viewer at `/demo/service/ui/index.html`.

```
static/demo/service/ui/
  ├── index.html                → viewer page (LED scoreboard + canvas pitch + event sidebar)
  ├── css/pitch.css             → dark theme, LED scoreboard, pitch, timeline styling
  ├── js/viewer.js              → PitchRenderer (canvas) + MatchViewer controller + event display
  └── match.json                → exported match data (generated by MatchSnapshotExporter)
```

**Pitch orientation:** horizontal — HOME left (row 1.0), AWAY right (row 8.0). Opposite of SwingUI vertical layout.
- **Coordinate convention (authoritative):** rows 1–7 playable (cell centre at row+0.5), cols 1–6 playable (cell centre at col+0.5). HOME goal line at row 1.0, AWAY goal line at row 8.0. Goal mouth 1 cell wide (col 3.0–4.0, centred at col 3.5). OOB: row ≤ 0.99 (behind HOME), row ≥ 8.01 (behind AWAY), col ≤ 0.99 (left touchline), col ≥ 7.01 (right touchline).
- **Tactical perspective:** `TacticalPerspectiveTransformer.toPhysical()` uses 9-row mirror — HOME row n → AWAY row 9−n. So AWAY GK at row 1.5 mirrors to row 7.5 (just in front of AWAY goal at row 8.0).
**Data sources:** `POST /api/generate` (MatchViewerLauncher) or load `match.json` file (standalone).
**Events displayed:** ALL events — merged feed from MatchRecorder events + ActionLogService logs (DECISION, ACTION, OUTCOME, DUEL, CHASE, FOUL, CARD, etc.) with team/player attribution.
**Timeline cap:** 200 entries max in DOM (prevents Firefox freeze at high event counts). Verbose engine logs (DECISION, ACTION_*) are kept in the Java app log but excluded from the side panel.
**Controls:** Play/Pause, seek bar, speed slider (0.25x–8x), keyboard (Space/arrows).
**Export:** `mvn exec:java -Dexec.mainClass=org.example.footballmanager.demo.service.ui.MatchSnapshotExporter -Dexec.args=42`
**Launcher:** `mvn exec:java -Dexec.mainClass=org.example.footballmanager.demo.service.ui.MatchViewerLauncher` (port 8765)

**Key design per corePrinciples:**
- Decision engine scores actions → football rules override illegal actions (§15)
- Kickoff is special center positioning event, not from TacticalEditor (§20)
- Threat override modifies movement targets, not the decision (§6) — TYPE A (carrier ≤ 1.0 cell, defender presses from ~14 m so DRIBBLE duel fires at 0.15 cells), TYPE B (opponent in defensive third, no defender within 0.5 cells); resolver `isClosestEligibleDefender` ensures only ONE defender claims the threat (no swarm)
- Controlled randomness via seeded Random (§9-10)
- Movement: ≤1 cell/tick non-carrier, collision avoidance (§11)
- Ball: POSSESSION / IN_TRANSITION / LOOSE states (§12) — uses `action.passSpeed` (1.0–3.0 cells/tick from passer passing skill) for in-flight movement
- Offside: second-to-last defender, checked EVERY tick for ALL attackers on both teams (§16)
- Duel cooldown: loser blocked for 60 ticks after duel loss
- Duel radii: 0.2 cells (~2.8 m) for RECEIVE_PASS / CHASE; 0.15 cells (~2 m) for DRIBBLE — tight, realistic (cell is 14 m × 10 m); 0.3 for SHOT block

### API Route Prefixes

| Controller | Prefix |
|---|---|
| `UserController` | `/auth` (register, login) |
| `APIController` | `/api` |
| `TeamController` | `/teams` |
| `PlayerController` | `/CPlayers` |
| `MatchController` | `/matches` |
| `SimulationController` | (top-level) `/start-demo`, `/start-realistic-demo`, `/simulation/*` |
| `TrainingController` | `/training` |
| `TransferController` | `/transfers` |
| `LineupController` | `/lineups` |
| `StatsController` | `/stats` |
| `MatchPlayerStatsController` | `/match-stats` |
| `CompetitionController` | *(check class)* |
| `JuniorController` | `/juniors` |
| `CommunityController` | `/community` |
| `SeasonController` | `/seasons` |
| `CountryController` | `/countries` |
| `StadiumController` | `/stadiums` |
| `ZoxViewController` | `/zox` |
| `AdminController` | `/admin` |
| `DummyDataController` | `/demo` |

All routes except `/auth/**` are protected by JWT. The `JwtAuthenticationFilter` validates the `Authorization: Bearer <token>` header on every request.

### Match Simulation Flow

The primary match path (triggered from the dashboard):

1. `GET /start-realistic-demo` → `SimulationController` finds the user's scheduled fixture
2. `SimulationService` runs `RealisticMatchEngine` (tick-based, CSPosition-aware)
3. `RuntimeSaveToDB` persists ticks, match events, and CPlayer stats
4. `ZoxReplayService` exposes replay metadata and tick chunks
5. `realisticDemo.html` (frontend) fetches and replays the match

Supporting engine classes: `AIDecisionMaker`, `DuelResolver`, `DuelCalculator`, `PositionalDefense`, `RealisticEventGenerator`, `BroadcastEngine`, `MatchStatisticEngine`.

`MatchEngine.java` handles match creation and fallback/non-realistic simulation. `cleanSheet/` contains an older simulation path — prefer `RealisticMatchEngine` for all new work.

### Season & Round Progression

`AdvanceWeekAsyncService` and `RoundSimulationAsyncService` run asynchronously. The simulation endpoints `/simulation/current-round/simulate-all` and `/simulation/week/advance` poll status via corresponding GET endpoints. Live match finalization can affect week/round state — ensure `RuntimeSaveToDB` completes before advancing.

### WebSocket

`WebSocketConfig` enables STOMP over WebSocket for community chat (`CommunityController`).

## Frontend Architecture

All frontend files live under `src/main/resources/static/`.

**Primary entry points:**
- `login.html` / `register.html` → auth
- `dashboard.html` → main SPA shell (loads sidebar, clock, pages router)
- `realisticDemo.html` → live/replay match viewer (standalone page)
- `zox-match-preview.html` → pre-match analysis

**JS module dependency chain (SPA):**
```
dashboard.html
  └── app.js          (DOM init, sidebar bootstrap)
  └── dashboard.js    (state management, section switching)
  └── pages.js        (page router — 5200+ lines, routes all feature views)
        ├── pages-renderers.js  (HTML generation helpers)
        ├── auth.js             (JWT storage, authFetch wrapper)
        └── pages/             (feature modules: academy, CTeam, matches,
                                club-management, community, training,
                                staff-directory)
```

**`auth.js`** exports `authFetch` — use this for all authenticated API calls from the frontend (it injects the `Authorization` header and handles 401 redirects).

**`pages.js`** is the central router; it imports all feature modules and calls their render functions when the user navigates. When adding new pages, register them here.

**`realisticDemo.js`** is standalone and communicates directly with `/zox` replay endpoints — it does not go through the `pages.js` router.

## Testing Layout

```
src/test/java/org/example/footballmanager/
  BaseTest.java                    → Abstract base; @SpringBootTest + @ActiveProfiles("test")
  config/TestConfig.java           → Provides Faker bean
  controller/                      → MockMvc / REST Assured controller tests
  engines/                         → Unit tests for RealisticMatchEngine, MatchEngine, AIDecisionMaker, DuelResolver
  service/                         → Unit tests for services
  util/                            → Unit tests for utilities
  demo/                            → Unit tests for SimulationArchitectureTest, ChaseDeadlockTest, ExecutionQualityTest, etc.
  integration/
    TifoBackendIntegrationTest     → REST Assured integration tests against H2
    TifoE2ETest                    → REST Assured E2E flows (auth → CTeam → match)
  ui/
    TifoUITest                     → Playwright browser tests (needs running app)
  zox/
    ZoxReplayServiceTest           → Replay service tests
```

Controller and integration tests extend `BaseTest`. Unit tests for engines/services/utils generally do not (they mock dependencies directly with Mockito).

## Home Page (Game Mode Selector)

After login, users land on `/home.html` — a standalone page (not SPA) with 4 game-mode cards:
- **TIFO UI MANAGER** → `/dashboard.html` (full SPA)
- **TIFO TEXT BASED** → `/tifo.html` (text-based simulation)
- **AMERICAN FOOTBALL** / **BASKETBALL** → "soon" placeholders

`home.html` has its own auth guard (redirects to login if no JWT). All other pages have "← Back to Home" navigation. Static files: `home.html`, `css/home.css`.

## New Match Engine (`newLogic/`)

A fresh, zero-dependency match simulation under `org.example.footballmanager.newLogic`. No imports from outside the package. REST endpoints at `/api/v2/match/` (start, status, replay metadata/chunks).

### Key Design Principles
- **NIKAD teleportacija** — no CPlayer CSPosition snapping; movement blends over multiple ticks
- Speed capped by pace skill (PACE_STEP_MIN=0.04 → PACE_STEP_MAX=0.33 per tick, linear 1-20, calibrated for 120 ticks/min)
- Zone-based 5×5 grid tactical positioning (ZonePositionCalculator)
- Passes have duration, offside only on forward passes, goals only from shots
- **Possession chains** — every event tagged with chainId for causal tracking

### Simulation Flow (`MatchSimulator.java`)
1. 90-minute loop (120 ticks/min = 10,800 base ticks + injury time)
2. Each tick: decision (8 actions) → transit → movement
3. Duels resolve tackles, fouls, penalties via skill-weighted probability
4. Set pieces: corners (header duel with GK punch), free kicks, penalties, goal kicks, throw-ins
5. GK reacts to crosses: moves towards landing zone during CROSS/CORNER transit
6. Injury time added at end of both halves based on stoppage ticks
7. Ball out-of-bounds detection triggers corner/goal kick/throw-in
8. **Possession chain tracking**: start/end events, pass count, causal relationships

### Key Engine Classes
- `DecisionEngine` — 8-action AI decision system:
  - **PASS_SHORT** (3-15m): base 0.85, boosted by playmaking (×0.40) and passing (×0.25), penalized by pressure (×0.30). Phase multipliers: BUILD_UP ×1.8, PROGRESSION ×1.2, FINAL_THIRD ×0.7, BOX_CHAOS ×0.3
  - **PASS_LONG** (15-35m): base 0.25, boosted by playmaking (×0.30) and passing (×0.35). Bonus +0.3 if free teammate on other side
  - **THROUGH_BALL** (behind defense): only in FINAL_THIRD/BOX_CHAOS, requires playmaking ×0.55. Only if attacker running behind defense within 8m of offside line
  - **CROSS** (from wing): only if carrier is wide (|y-50| ≥ 24) and in opponent half. Boosted by passing ×0.30 and technique ×0.25
  - **SHOT**: base 0.0, boosted by shooting ×0.45 and technique ×0.20. Distance tiers: <8m +0.35, <14m +0.22, <20m +0.12, <28m +0.05. Phase multipliers: BUILD_UP ×0.0, PROGRESSION ×0.15, FINAL_THIRD ×0.8, BOX_CHAOS ×1.8
  - **DRIBBLE**: base 0.15, boosted by technique ×0.40 and pace ×0.30, penalized by playmaking ×0.20. Bonus +0.4 if no defenders nearby, +0.2 for WNG/ATT
  - **CLEARANCE**: only for DEF in own third (x < 33) under pressure > 0.5
  - **GK_DISTRIBUTE**: only for goalkeepers after save/catch, prefers nearby DEF/MID
- `MovementEngine` — blend system, zone-based tactical movement, pace-capped velocity (calibrated: ~40 units/min max for pace=20)
- `DuelResolver` — xG shots, tackle, penalty, foul/card with penalty box detection
- `PhysicsEngine` — ball transit, clearance, deflection, CROSS mode parabolic arc
- `SetPieceHandler` — restart positions for all set pieces, corner delivery with GK punch. **Smart taker selection**: corners/free kicks prefer highest passing+technique, penalties prefer highest shooting+technique
- `FatigueSystem` — fatigue progression, injuries, auto-subs (72% min movement)
- `OffsideTracker` — offside line from **second-to-last defender** (not last, per FIFA rules)
- `PossessionChainTracker` — tracks possession chains with chainId, pass count, start/end events

### Event System (~35 event types)

All events are sealed records implementing `MatchEvent` interface. Each event carries `minute()`, `tick()`, `type()`, and event-specific data.

**Possession events:**
- `PossessionStartEvent` — team gains possession (chainId, teamSide, description)
- `PossessionEndEvent` — team loses possession (chainId, passCount, reason)

**Pass events:**
- `PassEvent` — completed pass (passerId, receiverId, completed, intercepted)
- `PassInterceptedEvent` — pass intercepted by defender
- `PassIncompleteEvent` — pass incomplete (out of play, wrong direction)
- `ThroughBallEvent` — pass behind defense (distance, receiver)
- `LongBallEvent` — long pass (15-35m, distance, receiver)

**Shot events:**
- `ShotEvent` — shot attempt (onTarget, saved, isGoal, xG). **Fixed bug**: isGoal flag added to distinguish goals from saved shots
- `ShotSavedEvent` — goalkeeper saves shot
- `ShotBlockedEvent` — defender blocks shot
- `ShotMissedEvent` — shot off target

**Dribble events:**
- `DribbleEvent` — successful dribble past defender
- `DribbleLostEvent` — lost ball during dribble

**Tackle events:**
- `TackleEvent` — tackle duel (success/failure)
- `TackleFoulEvent` — tackle resulted in foul

**Cross events:**
- `CrossEvent` — cross from wing
- `CrossClearedEvent` — defender clears cross
- `CrossHeaderEvent` — header from cross (onTarget, xG)

**Goalkeeper events:**
- `GkSaveEvent` — goalkeeper saves shot
- `GkCatchEvent` — goalkeeper catches cross/corner
- `GkPunchEvent` — goalkeeper punches ball away
- `GkDistributionEvent` — goalkeeper distributes after save/catch

**Other events:**
- `ClearanceEvent` — defender clears ball under pressure
- `GoalEvent`, `FoulEvent`, `CardEvent`, `OffsideEvent`, `SetPieceEvent`, `PenaltyEvent`, `InjuryEvent`, `SubstitutionEvent`, `DuelEvent`, `MatchStartEvent`, `MatchEndEvent`

**Event statistics:**
- Typical match: ~2400 events (90 minutes)
- Passes: ~800-1200 per match
- Duels: ~200-400 per match
- Shots: ~15-25 per match (calibrated from previous 5-8)

### Match Stats Tracked
- Goals, shots (on/off target), fouls, corners, yellow/red cards, possession, pass completion, avg rating
- Passing accuracy tracked internally (successful vs total passes per CTeam)
- **Possession chains**: pass count per possession, chain duration, causal relationships

### Disciplinary System
- **Yellow cards** tracked per CPlayer in `state.playerYellowCards`
- **Second yellow → red**: when a yellow-worthy foul is committed and the defender already has a yellow, auto-upgrade to red
- **Straight red**: dangerous tackles (last-man foul, penalty-box, or 15% random) have a 40% chance of direct red. **Fixed bug**: straight red no longer also counts as yellow
- **10v11**: red-carded CPlayer removed from `startingXI()` and `playerSnapshots`; remaining CPlayers blend to adjusted formation
- **Substitution snapshot fix**: old CPlayer's snapshot removed when substitute enters (prevents "12 CPlayers on pitch" bug)

### Bug Fixes (2026-07-25)
- **Offside line**: now uses second-to-last defender (per FIFA rules), not last defender
- **Kickoff after goal**: team that conceded gets kickoff, not always HOME
- **Shot event type**: added `isGoal` flag to distinguish goals from saved shots (was incorrectly classifying goals as off-target)
- **Red card counting**: straight red no longer also increments yellow card counter
- **Set piece taker selection**: corners/free kicks now prefer player with highest passing+technique (was first outfield player)
- **Penalty taker selection**: now prefers highest shooting+technique (was only shooting)
- **Pace calibration**: reduced PACE_STEP_MIN from 0.07 to 0.04, PACE_STEP_MAX from 0.84 to 0.33 (realistic ~40 units/min max instead of 100)
- **Duel frequency**: added 15-tick cooldown (`lastDuelTick`), reduced proximity from 3.0 to 2.0, added 6% random gate — prevents excessive duel triggers every tick
- **Offside check frequency**: offside tracker now checks every 60 ticks instead of every tick — reduces false offside flags from 155+ to reasonable levels
- **Support target rewriting**: attackers now stay forward in support stance instead of all converging on the ball carrier
- **Pass forward bias**: +0.5 forward, -0.2 backward, with skill-based pass quality
- **Duel frequency**: added 15-tick cooldown (`lastDuelTick`), reduced proximity from 3.0 to 2.0, added 6% random gate
- **Offside check frequency**: offside tracker now checks every 60 ticks instead of every tick
- **Persistence Layer**: new `MatchPersistenceService` saves match stats (goals, shots, passes, fouls, cards, ratings), match events, tick snapshots for replay, and player season stats to DB; `MatchOrchestrator.simulate()` calls persistence after simulation
- **MatchOrchestrator** updated: optional `MatchPersistenceService` parameter for DB persistence; `MatchStore` still used for in-memory caching

### Bug Fixes (2026-07-26)
- **checkBallOutOfBounds**: corners now fire 100% (was using goal kick for both sides); throw-ins properly assigned to defending team
- **checkCorner**: removed 15% random chance - corners now always fire when ball enters corner zone
- **checkFoul**: penalty box fouls now always produce penalty (was 8% chance); outside box fouls now produce free kick stoppage (25% chance)
- **releaseBallAfterStoppage**: ball now properly positioned at corner flag (x=95, y=7/93) for corners, at sideline for throw-ins

### Bug Fixes & Tuning (2026-08-31) — demo/service engine
- **Coordinate system alignment**: `corePrinciples.md` updated to match engine — HOME goal at row 1.0, AWAY goal at row 8.0 (was incorrectly row 1/row 7). Goal width 1 cell (col 3.0–4.0, centred 3.5).
- **TacticalPerspectiveTransformer 9-row mirror**: HOME GK at row 1.5 → AWAY GK at row 7.5 (just in front of AWAY goal at row 8.0).
- **AWAY goalkeeper positioning fix**: `GoalkeeperMovementEngine.goalLineRow` 7.0 → **8.0**, `AWAY_ROW_MIN` 6.0 → **6.86** (16 m from AWAY goal), `AWAY_ROW_MAX` 6.9 → **7.9**. Was clamping AWAY GK to midfield rows 6.0–6.9 (1.5–2.8 rows from AWAY goal); now correctly in front of goal.
- **AWAY restart / goal kick fix**: `RestartManager.gkRow` AWAY 6.5 → **7.5**; AWAY opponent clearance clamped to row [1.0, 7.9] (was [1.0, 7.0]).
- **Offside retreat threshold 2 → 3** (`OFFSIDE_RETREAT_THRESHOLD`), per user rule "3 uzastopne offside pozicije".
- **Offside retreat AWAY clamp** `Math.min(7.0, retreatRow)` → **`Math.min(7.9, retreatRow)`**.
- **Universal offside tracking**: `OffsideService.trackOffsidePositions()` rewritten to fire on EVERY tick (not just at forward-pass moments) and check BOTH teams' attackers. Main `MatchSimulator.simulate()` loop now calls it at tick start.
- **Duel radii tightened**: 1 cell = 14 m, so duels only fire within ~2.8 m. `DEFAULT_DUEL_RADIUS` 1.0 → **0.2**, `DRIBBLE_DUEL_RADIUS` 1.2 → **0.15** (tighter), `RECEIVE_PASS_RADIUS` 0.7 → **0.2**, SHOT block 1.5 → **0.3**.
- **Interception lane-strict**: `findPassInterceptor()` now requires defender to be on the LINE SEGMENT between ball and receiver (perpendicular ≤ 0.5 cells ground / 0.4 air), not just "nearby". Triangle check excludes players behind passer/receiver.
- **Threat override rewrite**: TYPE A = isolated ball carrier anywhere (≤ 0.2 cells); TYPE B = opponent in defensive third, no defender within 0.5 cells. Resolver `isClosestEligibleDefender` ensures only ONE defender claims (no 3-player swarm). Non-defenders no longer contest.
- **TYPE A radius widened**: TYPE A radius 0.2 → **1.0 cell** (~14 m). Defenders now press ball carrier from 1.0 cell away, closing the gap before the carrier can slip past. `DRIBBLE_DUEL_RADIUS` tightened 0.2 → **0.15 cells** (~2 m) for tighter tackle trigger. `DRIBBLE_DUEL_COOLDOWN_TICKS` 8 → **7** ticks.
- **Carry target 1 → 3-4 cells**: `executeCarry()` sets target 3-4 cells ahead instead of 1, so the carrier moves continuously for several seconds instead of jumping 1 cell at a time. Per-tick `re-decide()` preserved — carrier switches to better option (shoot/pass) when it appears.
- **Pass speed from skill**: `executePassTo()` sets `action.passSpeed` based on passer passing skill (1.0–3.0 cells/tick, +0.2 long). `BallMovementEngine` reads it — faster balls move faster, deflect more, intercept less.
- **Far-post shot aim**: `evaluateShot()` aims at far post when GK is off-centre (GK col ≈ 3 → shot col ≈ 4, clamped to goal mouth [3.0, 4.0]). `handleShotArrival` re-evaluates `gkInLane` against actual shot target so GK on near post correctly fails to save far-post shot.
- **Empty-goal line check**: `executeShot()` treats goal as empty if GK > 2 cells from goal OR if GK within 2 cells but **off the shot lane** (perpendicular > 1.2 cells). A GK on wrong post no longer covers the shot.
- **Duel-before-yellow guard**: `DisciplineService.evaluateFoul(hadDuel)` — if no duel was active, no card issued (free kick only). VAR yellow/red logic preserved but only fires after genuine duel resolution.
- **Timeline DOM cap 200**: `viewer.js` `_addTimelineEvent` prunes oldest entries beyond 200 to prevent Firefox freeze. Compact timeline events only; verbose engine logs in app log but not in side panel.
- **MatchViewer default skill 14**: `MatchSimulationController.randomSkills()` baseline 14 with ±2 variation plus role bonuses (GK better at keeper, ATT better at striker).
- **Tactical rules as source of truth**: `TacticalIntentEngine.applyDefensivePositionConstraint` restored to pre-override behaviour — engine does NOT override tactical rules from DB / bundled JSON with code clamps. Per user: "NE SMES DA PREGAZIS tactical rules".
- **Debug helper**: `TacticsRules.dumpLoadedRules(path)` writes resolved tactical targets to JSON for verification; `MatchSimulator.simulate()` logs the loaded source.
- **Carry drives toward goal centre (no more corner runs)**: `ActionEngine.executeCarry()` now biases carry column toward 3.5 when the lane to goal centre is open (new helper `isCarryLaneOpen` uses `SimUtils.pointSegmentDistance`). Prevents wingers running into the corner when goal is in clear sight.
- **16 m open-lane SHOT override**: `PlaymakingDecisionEngine.decide()` new trigger `closeWithOpenLane` (lane open + no defender within 1 cell + `distToGoal ≤ 1.2`) forces SHOT — wingers with clear sight now shoot at 17 m instead of carrying.
- **GK stays close to goal line**: `GoalkeeperMovementEngine` `MAX_ADVANCE` 0.7 → 1.0 (14 m cap). Non-threat pull 0.05–0.30 cells (~7 m typical), threat pull 0.25–1.0 cells (only steps out when shot is imminent).
- **Top-2 box attacker priority in final 2 rows**: `scorePassOptions()` adds +80 to the two teammates closest to opponent goal inside the box — carrier prefers box attackers over 20 m pass back.
- **1.5-cell rule (21 m no backward pass)**: in the same `scorePassOptions()`, when carrier within 1.5 cells (21 m) of goal, all PASS options to receivers NOT in box AND NOT forward of carrier are filtered out — only SHOT / CARRY forward / pass forward / pass into box remain.
- **CENTER target restricted in final 2 rows**: `ActionEngine.selectCenterTarget()` picks from top-2 box attackers closest to goal; best aerial among them wins.
- **Match Viewer side panel**: `viewer.js` `TIMELINE_EVENTS` now includes `DUEL_START`, `DUEL_RESOLVED`, `CHASE_POSSESSION` (per-tick `CHASE` progress logs stay excluded).

### Bug Fixes & Tuning (Session 2026-08-31) — demo/service engine — pass 3
- **Side panel timeline — OFFSIDE + shot epilogue**: `viewer.js` `TIMELINE_EVENTS` extended with `OFFSIDE`, `SHOT_BLOCKED`, `SHOT_POST` so the side panel always shows the shot outcome chain between SHOT and the next restart, and offside calls appear inline.
- **Tighter offside filter on passes**: `PlaymakingDecisionEngine.isClearlyOffsideAtPass()` margin **0.5 → 0.2 cells** (~2.8 m). Receiver > 0.2 cells offside is hard-filtered out — marginal offside (≤ 0.2) still goes through for referee/VAR.
- **Restart walk fast-path**: `MatchSimulator` `RESTART_WALK_SPEED` 0.4 → **0.7** cells/tick. New `RESTART_TELEPORT_DISTANCE = 4.0` cells — any taker further than 4 cells from the ball is snapped to a spot 0.6 cells behind it, then walks normally. Prevents "taker not arriving" freeze.
- **Unified pushback for all restarts**: `RestartManager.pushOpponentsAwayFromBall()` new helper, called from CORNER + GOAL_KICK + THROW_IN. Previously only GOAL_KICK pushed opponents back from the ball — corners and throw-ins could be contested from inside 1 cell.

### Bug Fixes & Tuning (2026-08-31 — user reported) — demo/service engine — pass 5
- **CRITICAL — shot miss direction bug (shot fired toward OWN goal)**: after the coordinate-system pass moved `GOAL_POSITION` to row 8.0, three stale end-line checks still read `== 7.0`, so a HOME shot miss pushed the ball to row **-0.5 (behind HOME's OWN goal)** instead of row 8.5 → a "SHOT by Home" became a CORNER for the opposition at the wrong end. Fixed `logicalGoal.getRow() == 7.0 → 8.0` (`ActionEngine.shotMissed()`) and `goal.getRow() == 7.0 → 8.0` (two sites in `MatchSimulator`).
- **DRIBBLE duel radius 0.15 → 0.5 cells (~7 m)**: user override. At 0.15 the defender had to be almost on top of the carrier, so a pressing defender and carrier just ran overlapped in the same cell with no tackle, and the carrier looked stopped. With 0.5 the defender who has closed via TYPE A engages as soon as they come alongside, and `snapPlayersForDuel` pulls both to the contest point; winner takes the ball, loser gets the 6-tick block.
- **CENTER/cross never targets a clearly-offside attacker**: `ActionEngine.selectCenterTarget()` now skips any receiver `isClearlyOffside(passer, receiver)` (margin > 0.2 cells beyond second-to-last defender — matches the PASS decision filter). The execution-time whistle (`checkOffside`) remains a backstop for marginal ≤ 0.2 cases.

### Bug Fixes & Tuning (2026-09-01 — user reported) — demo/service engine — pass 6
- **Pass scoring rebalanced (lane no longer dominant)**: `PlaymakingDecisionEngine.scorePassOptions()` lane weight **±150 → ±80**. Previously a clean-laned backward pass scored ~150 and beat every other factor; the carrier recycled to a "safe" defender even when a free forward attacker was available. Re-balanced so forward bias + receiver openness + goal proximity can tip the decision.
- **Forward bias added**: forward pass **+50**, lateral **-10**, backward **-80**. This is the deciding factor when multiple receivers have a clean lane — the ball must move toward the opponent goal, not back. A backward pass to an isolated fullback (35×25 m of space, openness ≥ 2 cells) is still viable — `openScore` adds ~20 so the total stays positive. Per user clarification: "nije problem da izabere pass unazad ako je bas bas sam igrac... samo nikako ne stoperu koji je pod pritiskom".
- **Receiver-pressure penalty**: a receiver with an opponent within 0.5 cells gets a flat **-40** score (previously only reduced the openness component, capped at 40). A pressured center-back can no longer beat a free forward attacker.
- **Goal-proximity weight 1.5 → 3.5** for forward passes only (backward/lateral passes don't collect proximity points). A pass to row 7 (HOME) now scores +21 vs row 3.5's +8.75 — real bite.
- **Shot: GK-on-wrong-post boost +45**: `scoreShot()` now detects when the GK is close to the goal line but off-centre (col ≤ 3.1 or ≥ 3.9) — the far post is wide open. Combined with `ActionEngine.executeShot()`'s existing far-post aim, even an average finisher puts the ball in the empty corner. The carrier now takes the shot instead of recycling a backward pass with GK glued to the wrong post.

### Bug Fixes & Tuning (2026-09-01 — user reported) — demo/service engine — pass 7
- **Shot outcome chain always shown in sidebar + JSON**: `ActionEngine.start()` and `shotMissed()` / `shotSaved()` now use the enriched `MatchRecorder.appendEvent(..., MatchState)` overload which populates **team, playerId, playerName, targetPlayerId, positionRow, positionColumn, skill, outcome** in the JSON event. Previously all of these were null because the basic 5-arg overload didn't pull from the live state.
- **SHOT_BLOCKED event always emitted**: when a SHOT duel is resolved with `DEFENDER_WINS`, `MatchSimulator.recordDuelStats()` now emits a `SHOT_BLOCKED` (outfield defender) or `SHOT_SAVED` (GK) event so the sidebar shows the full shot outcome chain: `SHOT → BLOCKED / SAVED / MISSED / GOAL`. Previously the sidebar jumped from `SHOT by X` straight to `Away United 2 wins | intercepted pass` with no shot outcome.
- **Far-post threshold `> 0.5 → ≥ 0.5`**: `ExecutionQuality.evaluateShot()` far-post aim now triggers when the GK is at col 3.0 (exactly on the post) instead of requiring |offset| > 0.5 (which excluded col 3.0 itself).
- **Execution logic bug — miss scatter was inside onTarget branch**: the miss scatter code was running only when `onTarget = true`, making on-frame shots scatter off frame at random. Rewritten: when the shot is **onTarget**, `actualTarget = far-post aim` (inside goal mouth); when **off-target**, `actualTarget = miss scatter` (off frame). The geometric-miss safety check still runs to guarantee the scatter doesn't accidentally land in the net.
- **GK on wrong post → onTargetProb ≥ 0.85**: when the GK is clearly off-centre (`|colOffset| ≥ 0.5`) and not in the lane (`gkInLaneFactor < 0.6`) within 2.5 cells, the on-target probability is raised to at least 0.85. A 16m shot with the GK hugging the wrong post used to miss 60% of the time (base onTargetProb ~0.40 for medium range); it now scores ~85% of the time even with the existing 60% far-post scatter — well above the realistic ~70-75% for a clear shot at the empty corner.
- **`recordDuelStats()` and `resolveChase()` and `executeDecision()` now take MatchRecorder**: required to emit enriched events from these helper methods that previously only logged via `ActionLogService`.

### Bug Fixes & Tuning (2026-09-01 — user reported) — demo/service engine — pass 8
- **Extended shooting zone — strikers can try from row 5.5–6 with mispositioned GK**: real football has top strikers occasionally testing the keeper from 28-35 m when the GK is clearly off their line. Added `canLongShot` to `DecisionContext` — true when carrier is in row ≥ 5.5 (HOME) / ≤ 2.5 (AWAY), striker skill ≥ 12, GK is > ~10 m off their line AND > 0.3 cells off-centre. Gated by a 15% random frequency inside `decide()` so it fires rarely; scored with a flat -35 penalty so it only wins when the situation is genuinely begging for a strike.
- **Long-range empty-goal guard**: `scoreShot()` no longer forces `score = 100` (empty-goal guarantee) when `distanceToGoal > 2.0`. The empty-goal rule still applies inside the regular zone (≤ 28 m), but a 30m attempt must still clear the bar.

### Bug Fixes & Tuning (2026-09-01 — user reported) — demo/service engine — pass 9
- **VAR overlay freeze fix**: `.overlay.visible` `pointer-events` no longer blocks controls underneath — the dim backdrop is `pointer-events:none` and only the inner content (text + skip button) catches clicks. The overlay can be dismissed by clicking it, pressing ESC, or pressing Space. Full-time overlay stays non-dismissible (match is over).
- **All event types emit structured fields**: every call site that used the 5-arg `MatchRecorder.appendEvent(...)` (including `DUEL_START`, `DUEL_RESOLVED`, `VAR_IN_PROGRESS`, `VAR_*_CONFIRMED`, `VAR_*_OVERTURNED`, `POSSESSION_CHANGE`, etc.) now uses the 6-arg `..., state)` overload so the JSON export populates team / playerId / playerName / targetPlayerId / positionRow / positionColumn / skill / outcome. The 5-arg overload stays for backward compatibility (returns null fields) but every internal call site has been migrated.
- **Hard rule: carry ≤ 1 cell along same row**: `ActionEngine.executeCarry()` caps `carryDistance = 1` when the carry direction is purely lateral (`dr == 0`). Forward / diagonal carries keep the 3-4 cell range. Prevents wingers from shuffling the entire sideline in one carry.
- **Threat override — defend isolated attacker in final 2.5 rows**: `TacticalIntentEngine.applyThreatOverride()` TYPE B now checks `isInFinalQuarter()` (rows 1-2.5 for HOME defending / rows 5.5-7 for AWAY defending) instead of the defensive third, with distance threshold raised to 2.0 cells. Defender presses the isolated attacker all the way to duel range.
- **Duel visualisation — loser shows cooldown ring**: `viewer.js` tracks the loser of each DUEL_RESOLVED event for 6 ticks and renders a faint pulsing red ring on them. Both duelists highlight in yellow while the duel is active; only the winner keeps the carrier ring.
- **Mobile / iPhone-14-Pro landscape layout**: `pitch.css` adds a landscape breakpoint (`max-height: 500px`) that makes the pitch fill the entire screen, hides the sidebar by default, and shows a slim 28px live ticker above the pitch with the most recent event. The ticker has a "LOG" button that slides the full sidebar up over the pitch on tap. Portrait phones keep the original stacked layout. iOS viewport uses `100dvh` to handle the Safari URL bar.
- **Timeline pre-populated on load**: the side-panel timeline now shows ALL events from minute 0 onward, not just events from the current playback position. The user can scroll up to inspect earlier events immediately. `_timelineShownIdx` tracks which events are already in the DOM to prevent double-rendering during playback.

### Bug Fixes & Tuning (2026-08-23) — demo/service engine
- **VAR offside for AWAY**: `VARService.checkOffside()` was always confirming AWAY offside (Double.MAX_VALUE bug). Fixed to compute correct offside line per team
- **AWAY penalty kick**: `ActionEngine.executePenaltyKick()` used `GOAL_POSITION` (7, 3.5) for both teams. Fixed: AWAY aims at `new Position(1, 3.5)`
- **Miss ball reset**: shot miss now resets ball to center (4, 3.5) instead of goal position
- **CROSS/CENTER inFinalThird bug**: `inFinalThird = row >= 6` and `inTheBox = row >= 6` were identical, making CROSS/CENTER impossible. Fixed: `inFinalThird = row >= 5` (HOME) / `row <= 3` (AWAY)
- **Balanced team generation**: `MatchSimulationController.generateTeam()` now accepts `skillSeed` parameter; batch runner uses same seed for both teams, eliminating skill asymmetry (was: `"Home".hashCode()` vs `"Away".hashCode()` producing 17:1 home bias)
- **VAR frequency gates**: offside 20%, goal 15%, penalty 25%, red 40%, yellow 10% — reduces VAR reviews from ~14/match
- **Pass lateral deviation**: 0.15 → 0.40 → 1.20 → 3.0 → 6.0 → 3.5 (final value)
- **Row clamping**: 0.7-7.3 → 0.85-7.15 → 0.92-7.08 → 0.0-8.0 (allows ball past end lines for goal kicks)
- **Column clamping**: 0.0-7.0 → -0.5-7.5 → -0.5-8.5 (allows ball past sidelines for throw-ins)
- **Penalty box foul bonus**: reduced from 0.02 to 0.005
- **Penalty box dimensions**: narrowed from rows 6-7, cols 1-6 to row 7 only, cols 2-5, plus 15% random gate
- **Foul probability**: base 0.04 → 0.06, skill modifier 0.04 → 0.05, attacker bonus 0.02 → 0.03
- **Offside tolerance**: 0.05 → 0.20 cells (~2.8m real-world)
- **Corner chance**: 0.60 → 0.40 (defender clearance over end line)
- **Cross frequency gate**: 50% random gate added
- **Center scoring weights**: boxPresence 8.0→5.0, crossingQuality 0.5→0.35, progression 0.5→0.35
- **VAR batch runner bug fixed**: `ComprehensiveBatchRunner` L200 now counts VAR events from `result.events()` (MatchRecorder), not `result.logs()` (ActionLogService) — channels are `VAR_OFFSIDE_CONFIRMED` etc., not `VAR`

### Match Viewer UI (2026-08-23)
- **New web-based viewer** at `static/demo/service/ui/index.html`
- Horizontal pitch: HOME left (row 1), AWAY right (row 7) — opposite of SwingUI vertical layout
- LED scoreboard with team names, score, match clock
- Canvas pitch with player dots, ball, carrier highlight
- Event timeline sidebar: GOAL, SHOT, SHOT_SAVED, PENALTY_*, VAR_*, CROSS
- Playback: Play/Pause, seek bar, speed slider (0.25x–8x), keyboard shortcuts
- Data: `POST /api/service/match/simulate` (live) or load `match.json` (standalone)
- Export: `MatchSnapshotExporter` runs headless simulation, writes `match.json` to static resources

### Frontend Match Viewer (`/newLogic/index.html`)
- Dark-themed UI, responsive (mobile + desktop)
- Fields for home/away CTeam names, "Play Match" button
- Calls `POST /api/v2/match/start` → displays score, goals, stats grid, event timeline
- Replay slider: seeks through tick snapshots, shows ball CSPosition on a pitch
- All events grouped by type: goals, cards, subs, injuries
- **Event timeline**: displays all ~35 event types with icons and descriptions

## Documentation Files

| File | Purpose |
|---|---|
| `AGENTS.md` | (this file) — technical architecture & conventions |
| `UI_FOOTBALL_MANAGER.md` | Agent instructions — UI Football SPA (what's done, what needs work) |
| `BASKETBALL_PROGRESS.md` | Agent instructions — Basketball |
| `AMERICAN_FOOTBALL_PROGRESS.md` | Agent instructions — American Football |
| `TIFO_TEXT_MANAGER_PROGRESS.md` | Agent instructions — TIFO Text mode |
| `TIFO_SPORTS_MANAGER_GUIDE.md` | **User-facing guide** in English — all sports explained for end users |
| `demo/service/demoServiceProgression.md` | Agent instructions — demo/service engine (what's done, what's next) |

The 4 sport-specific `.md` files are written as instructions for AI agents — they describe what's implemented and what's pending. The `TIFO_SPORTS_MANAGER_GUIDE.md` is a user manual written in English.

## Feature Audit: Old UI vs New SPA

**Tactic Editor**: ✅ Already in the SPA — `pages/views/tactic-editor-view.js` (advanced) + `pages/views/formations-view.js` (basic). Routes: `formations`, `tacticEditor`. Accessible via Club → Tactics / Tactic Editor.

**Admin Features**: ✅ Already in the SPA:
- Registration approve/reject in Community chat (`features/community.js`)
- DB Init/Reset in Community admin section
- Advance Week button on dashboard (admin-only)
- All guarded by `isAdminViewer()` checks

**No missing features found** — the old `old/` package contains only legacy demo/visualization code (canvas test, socket demo, old match controller) which has been fully replaced by the SPA.

## Known Hotspots

- `pages.js` (5200+ lines) — large single file; exercise care with imports and state mutations
- `RealisticMatchEngine.java` — core simulation; changes here affect match realism and replay correctness
- Round/week advancement is stateful and async — changes to `SimulationController`, `AdvanceWeekAsyncService`, or `RuntimeSaveToDB` can cause inconsistent season state
- `cleanSheet/` and `old/` packages are legacy — do not add new features there
- `newLogic/` — self-contained; coordinate via `/api/v2/match/` endpoints; test via `NewMatchSimulatorTest` and `NewMatchControllerTest`
- **demo/service/** — self-contained service engine; source of truth is `corePrinciples.md`; only modify files under `demo/service/`; test via `MatchBatchRunner` and `MatchChainTrace`
- **demo/service/ui/** — web-based match viewer; pitch rendering in `PitchRenderer`, playback in `MatchViewer`
- **AF match engine balance**: too many yards per game (1310 passing yds in 1 match) — first down resets downs, drives continue indefinitely
- **AF event storage**: separator changed to `||` (was `|`); old matches in DB have broken events

## Match Engine Architecture (`newLogic/engine/`)

The simulation engine follows a tick-based pipeline with these core classes:

```
Simulation Tick Pipeline (each tick):
  MatchState → TacticalEditor → AwarenessEngine → IntentEngine →
  MovementEngine → DecisionEngine → ActionCommitment → BallEngine →
  InteractionEngine → RulesEngine → EventBus → StatisticsEngine
```

### Core Engine Classes

| Class | Responsibility |
|---|---|
| `MatchPhase` | `BUILD_UP`, `PROGRESSION`, `FINAL_THIRD`, `FINISHING` — phase based on ball position |
| `BallState` | `POSSESSION`, `IN_FLIGHT`, `ROLLING`, `DEFLECTED`, `LOOSE` |
| `PlayerIntent` | `RETURN_TO_SHAPE`, `PRESS`, `MARK`, `INTERCEPT`, `CHASE_BALL`, `SUPPORT`, `OVERLAP`, `UNDERLAP`, `MAKE_RUN`, `HOLD_POSITION` |
| `PossessionContext` | Tracks ball owner, possession duration, phase, pass count, chain ID |
| `CurrentAction` | Tracks action type and remaining execution time (commitment duration) |
| `ActionCommitment` | Prevents decision changes while action is in progress |
| `SpaceInfo` | Pressure, openness, pass lane score, shot lane score, threat status |
| `SpaceAnalyzer` | Real-time spatial analysis for each player |
| `UtilityScorer` | Functional interface per scoring strategy (Carry, Shoot, Pass, Cross, etc.) |
| `MatchMetrics` | Live match statistics (shots, passes, tackles, corners, etc.) |
| `MatchEventBus` | Decoupled event publishing — stats, replay, and reporting subscribe independently |
| `StatisticsEngine` | Consumes events and produces `MatchMetrics` |
| `SimulationDebugger` | Produces "Simulation Health Report" with possession analysis |

### Decision Engine Architecture (refactored)

The `DecisionEngine` now uses:
- `UtilityScorer` interface — each action type has its own scorer class
- `SpaceInfo` from `SpaceAnalyzer` — decisions use real-time spatial data, not computed values
- `ActionCommitment` — prevents action switching mid-animation, creates realistic action durations
- `PlayerIntent` — intent drives movement, not the decision

### Simulation Debug HUD

After each match, the simulation prints a health report:
```
=== Simulation Health Report ===
Possession switches: N
Average possession: X.Xs
Longest possession: X.Xs [WARNING if >5s]

Shots: N | Passes: N | Tackles: N | Corners: N
Through balls: N | Crosses: N | Offsides: N
Loose ball time: X% | Ball in flight: X%

Warnings:
⚠️  Carry selected N% of all actions.
⚠️  No throw-ins detected.
⚠️  No shots from Team B.
```
