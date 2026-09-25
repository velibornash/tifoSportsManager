# footballForDelete — quarantined code

**Quarantined:** 2026-09-26
**Reason:** unreachable dead code. Verified by exhaustive reference analysis + `mvn compile` + `mvn test` (84 tests green) after the move.

**This folder is NOT part of the Maven build.** Nothing here is compiled, deployed, or reachable at runtime. It exists only so that nothing is lost if a removal decision turns out to be wrong. When confidence is high, `rm -rf footballForDelete`.

---

## Why this folder exists

The repository contained **four** match engines and only one was live. Roughly 40,000 lines of simulation code sat in the build with **zero callers**, which is why exploits and rot accumulated unnoticed (e.g. `TrainingProgressionService` has no idempotency guard and no tests; `TransferService` lets you buy any player for €1).

`newLogic/sim/` is the only engine. Everything below was unreachable from any HTTP endpoint.

---

## How deadness was verified

1. For each class, grep every reference across `src/main/java`, excluding its own file.
2. Walk the caller chain upward until an HTTP endpoint, a `@Component` with a live caller, or a dead end is reached.
3. Move, then let **`mvn compile`** be ground truth for the remaining orphans.
4. **`mvn test`** — 84 tests, 0 failures.

### The two traps found during verification

- **`MatchOrchestrator` name collision.** Two different classes share the name: `newLogic/service/MatchOrchestrator.java` (v2, dead) and `newLogic/sim/engine/MatchOrchestrator.java` (LIVE proposal orchestrator). A naive name-based reference search would have flagged both.
- **`MatchState` name collision.** Same trap: `newLogic/model/MatchState.java` (v2, dead) vs `newLogic/sim/model/MatchState.java` (LIVE). All live usage imports the `sim.model` one.

---

## Contents

### `backend/newLogic/engine_v1/` — 12 files, ~9,775 LOC

The original "realistic" engine. Dead chain:

```
RealisticMatchEngine (231 KB)
  └─ SimulationService
       └─ WeekPreparationAsyncService   ← 0 callers
```

Also dead: `AIDecisionMaker`, `PositionalDefense`, `RealisticEventGenerator`, `BroadcastEngine`, `MatchPlaybackEngine`, `SetPieceHandler`, `PhysicsEngine`, `DuelCalculator`, `TeamStrengthCalculator`, `MatchEngine`, `MatchStatisticEngine`.

> ⚠️ **Before deleting:** port `RealisticMatchEngine.maybeTriggerInjury` (lines 1410–1489) into the proposal engine. It is the **only** injury generator in the codebase and the best mechanic found in the audit:
> ```java
> if (minute < 8 || minute > 88) return;
> int fatigue = fatigueOf(injured);
> double chance = 0.00028 + max(0, fatigue - 18) * 0.00008;
> if (position == WNG || position == ATT) chance += 0.00008;
> ```
> `pickInjuryRiskPlayer` (1477–1489) weights selection by `1 + max(0, fatigue - 10) * 0.25`, so tired players are both more likely to be chosen *and* more likely to be injured. A correctly-signed chain: play → fatigue → injury risk → injury → rehab → reduced training.
> **Tracked as Sprint 1.6.**

### `backend/newLogic/engine/` — 26 files, ~4,563 LOC

Tick-based v2 simulator (`MatchSimulator`, `DecisionEngine`, `DuelResolver`, `ZonePositionCalculator`, `MoraleSystem`, …).

Dead chain:
```
newLogic/engine/  →  controller/NewMatchController  (/api/v2/match/*)
                  →  static/realisticDemo.html   ← nothing links to this page
```

Worth stealing before deletion:
- `MatchSimulator.clampPaceThisTick()` (lines 361–379) — a hard no-teleport speed invariant, not present in this form in the proposal engine.
- `ZonePositionCalculator` (229 lines) — the 5×5 zone model.

Was not production quality: `UtilityScorer` is an empty 10-line interface, `DuelResolver.findBestClearanceTarget()` returns `null`, `resolvePass` has a hard `forcedOut = rand < 0.18` on every pass, and there were **zero tests**.

### `backend/newLogic/service/` — 8 files

`SimulationService`, `WeekPreparationAsyncService` (0 callers), `RoundSimulationAsyncService` (**0 callers**), `PlayerMovementDecisionService` (1,172 lines, only used by the dead `MatchEngine`), `MatchLiveService`, `MatchLiveSession`, `MatchOrchestrator` (v2 — see collision note), `TacticsAdjustmentService` (only used by dead `MatchEngine` + `old/DemoSimulator`).

> ⚠️ `PlayerMovementDecisionService` contains real shot-duel resolution logic (lines 600–660). Reference only if shot outcomes ever need revisiting.

### `backend/newLogic/controller/NewMatchController.java`

`/api/v2/match/*`. Called only by `static/js/realisticDemo.js`, which only the unlinked `realisticDemo.html` loads.

### `backend/newLogic/util/` — `RuntimeSaveToDB.java`, `events/` (6 files)

`util/events/*` all depended on the moved engines (compiler-confirmed). `MatchEventMapper` is also referenced by `old/oldSimulator/DemoSimulator.java`.

### `backend/newLogic/model/` — `MatchState.java`, `MatchRuntime.java`

The **v2** state classes. Distinct from the live `newLogic/sim/model/MatchState.java`. Verified: all live usage imports the `sim.model` one.

### `backend/newLogic/old/` — 4 files

`oldService/{CanvasSimulationService, DemoSimulationService, DemoMatchRuntime}`, `oldSimulator/DemoSimulator`. Legacy per `AGENTS.md` ("do not extend").

### `backend/newLogic/tools/SimulationRunner.java`

 depended on `service/MatchOrchestrator` + `engine/`.

### `backend/demo/swingUIDemo/` — 55 files

Desktop Swing visualisation. `main()` only, requires a desktop JVM. No Spring wiring, no references (only two prose code comments in `demo/service` name it).

### `frontend/` — 8 files

| File | LOC | Why dead |
|---|---:|---|
| `realisticDemo.html` | 1,670 | Unreachable — no page links to it |
| `realisticDemoLegacy.html` | 1,635 | Orphan duplicate; also loaded the *wrong* script (`realisticDemo.js`, not `realisticDemoLegacy.js`) |
| `js/realisticDemo.js` | 2,904 | Only used by the two pages above |
| `js/realisticDemoLegacy.js` | 2,777 | Fully orphaned |
| `cleanSheetTifo.html` + `js/cleanSheet.js` | 932 | Orphaned text-mode page; called `/api/clean-sheet/simulate-single`, which does not exist |
| `index.html` | 31 | Dead legacy login with hardcoded credentials `test@primer.rs` / `A12345!` |
| `dasboardBackup.css` | — | Typo in filename, unreferenced |

---

## What was deliberately KEPT

| Path | LOC | Why |
|---|---:|---|
| `newLogic/sim/**` | 11,783 | **The live engine.** Dashboard → `/simulation/current-round/prepare` → `SimMatchService` → `SimReplayStore` → `static/demo/service/ui/proposal/index.html` |
| `demo/service/**` | 19,488 | Design reference. `corePrinciples.md` §1–48 is the spec the proposal engine was ported from; `ComprehensiveBatchRunner` / `MatchDetailedAnalyzer` are the calibration oracle. **Freeze, do not edit.** Sprint 6.2 moves it out of the build path |
| `static/demo/service/ui/proposal/**` | — | The replay viewer the SPA actually navigates to (`demo.js:323,351`, `dashboard.js:781`, `match-view.js:442`) |
| `newLogic/engine_v1/MatchEngine.simulateRestOfMatchDay` | — | Was live via the dead `RoundSimulationAsyncService`. **Superseded** — see the correction below |

---

## Correction: AI-vs-AI already uses the live engine

The original audit claimed that league fixtures for AI-vs-AI matches were produced by a Poisson dice roll (`MatchEngine.simulateQuickScore`). **That was wrong.**

The live path `POST /simulation/current-round/simulate-all` (`SimulationController.java:129-158`) uses `SimMatchService.simulate()` — the **proposal engine** — for every fixture:
- the user's own league: synchronously, in-loop
- all other leagues: `AsyncSimulationRunner.simulateInBackground()` → `simMatchService.simulate(fixture, false)` (line 59)

`simulateQuickScore` was only reachable from `WeekPreparationAsyncService` and `RoundSimulationAsyncService`, **both of which have zero callers.** The league table is likewise written by `SimMatchService:246-261`, not by `MatchStatisticEngine`.

The error came from trusting a reference search that reported `RoundSimulationAsyncService` as live. It is not injected anywhere. This is why the move was verified by walking caller chains to an HTTP endpoint rather than by counting references.

**Consequence:** Sprint 7 in `sprintBacklog.md` was largely unnecessary and has been reduced to verification + cleanup.
