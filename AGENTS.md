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
