# Sprint Progress Log

Running log of completed work. Newest entry at the top.

**Project:** TIFO Football Manager — UI Football
**Backlog:** `sprintBacklog.md` · **Audit:** `expertAudit.md`
**Started:** 2026-09-26

---

## Pre-Sprint 0 — Dead code quarantine

**Date:** 2026-09-26 · **Duration:** ~45 min · **Commit:** _(see git log — "Quarantine dead match engines and orphaned pages")_

### What was done

Moved 125 unreachable files (≈40,000 LOC) into `footballForDelete/`, which is **outside the Maven build**. Nothing is lost; `rm -rf footballForDelete` when confidence is high.

| Area | Files | LOC |
|---|---:|---:|
| `newLogic/engine_v1/` (all 12) | 12 | ~9,775 |
| `newLogic/engine/` (v2 package) | 26 | ~4,563 |
| `demo/swingUIDemo/` | 55 | ~11,900 |
| Dead services (`SimulationService`, `WeekPreparationAsyncService`, `RoundSimulationAsyncService`, `PlayerMovementDecisionService`, `MatchLiveService`, `MatchLiveSession`, `MatchOrchestrator`, `TacticsAdjustmentService`) | 8 | ~2,500 |
| `NewMatchController` | 1 | ~200 |
| `util/RuntimeSaveToDB` + `util/events/*` | 7 | ~600 |
| `tools/SimulationRunner`, `old/*` | 5 | ~800 |
| v2 `model/MatchState`, `model/MatchRuntime` | 2 | ~200 |
| Orphaned frontend (`realisticDemo*.html/js`, `cleanSheet*`, `index.html`, `dasboardBackup.css`) | 8 | ~8,000 |
| `README.md` (rationale + salvage notes) | 1 | — |

### How deadness was verified

Not by counting references — that produced a wrong answer. Instead:

1. For every class, enumerate references across `src/main/java` excluding its own file.
2. Walk each caller chain **upward** until reaching an HTTP endpoint, a live `@Component`, or a dead end.
3. Move the verified set, then treat **`mvn compile` as ground truth** for the orphans it revealed, and move those too. Repeat until clean.
4. `mvn test`.

### Two traps found

- **`MatchOrchestrator` name collision** — `newLogic/service/MatchOrchestrator.java` (v2, dead) vs `newLogic/sim/engine/MatchOrchestrator.java` (**live**). A name-based reference search flags both.
- **`MatchState` name collision** — `newLogic/model/MatchState.java` (v2, dead) vs `newLogic/sim/model/MatchState.java` (**live**). All live usage imports the `sim.model` one. Compiler-confirmed: only `model/MatchState.java` referenced the moved packages.

Also fixed a self-inflicted error mid-task: my first exclusion filter used `\|` inside `grep -E`, where it is a literal pipe, not alternation — so the filter silently did nothing. Switched to letting the compiler drive.

### Audit correction this triggered

Verifying `engine_v1/` properly **overturned a P1 finding in my own audit.**

`expertAudit.md` §4.1 claimed AI-vs-AI league matches were produced by a Poisson dice roll (`MatchEngine.simulateQuickScore`), leaving the league table statistically incoherent with the match engine. **That was wrong.** The live `POST /simulation/current-round/simulate-all` (`SimulationController.java:129-158`) already routes every fixture through `SimMatchService.simulate()` — the proposal engine:

- user's league: synchronously in-loop (`:133-137`)
- all other leagues: `AsyncSimulationRunner.simulateInBackground()` → `simMatchService.simulate(fixture, false)` (`AsyncSimulationRunner.java:59`)

The league table is likewise written by `SimMatchService:246-261`. `simulateQuickScore` was only reachable from `WeekPreparationAsyncService` and `RoundSimulationAsyncService` — **both of which have 0 callers.** A reference search had reported `RoundSimulationAsyncService` as live; it is injected nowhere.

**Consequence:** Sprint 7 was cut from 5–7 days to 1–2 days and reduced to a regression test that locks the invariant in place.

### Collateral finding: the live engine has no injury generator

`RealisticMatchEngine.maybeTriggerInjury:1410-1489` was the **only** injury generator in the codebase — the best mechanic in the audit (fatigue → injury risk, position-weighted, minute-windowed, with `pickInjuryRiskPlayer` weighting selection by fatigue). It was quarantined with the rest of `engine_v1/`.

**The proposal engine currently produces no injuries at all.** Sprints 1, 2 and 4 all assumed injuries exist.

**Action taken:** the file is preserved in `footballForDelete/backend/newLogic/engine_v1/RealisticMatchEngine.java`, the salvage requirement is documented at the top of `footballForDelete/README.md`, and migration is now tracked as **Sprint 1.6** with full source line references. Per your instruction, the move went first and the migration follows.

### Documentation updated

| File | Change |
|---|---|
| `expertAudit.md` §4 | Marked resolved; added the two name collisions; added salvage list |
| `expertAudit.md` §4.1 | **Retracted and replaced** with the verified finding and its cause |
| `expertAudit.md` §3.5, §9.1, §9.2, §11.3, §11.4 | Repointed file references to `footballForDelete/` |
| `expertAudit.md` appendix | Index rows updated; `AI-vs-AI dice roll` struck through with the correction |
| `sprintBacklog.md` Sprint 1 | **+3 tasks:** S1.6 injury port, S1.7 penalties, S1.8 substitutions. Effort 8–10 d → 16–20 d |
| `sprintBacklog.md` Sprint 6 | Engines marked done; remainder narrowed to stubs + docs |
| `sprintBacklog.md` Sprint 7 | Replaced with the 1–2 day verification sprint |
| `sprintBacklog.md` | Dependency graph, effort table, critical path, 30-day plan all recalculated |
| `footballForDelete/README.md` | New — per-file rationale, verification method, salvage warnings |
| `sprintProgress.md` | This file |

### Verification

| Check | Result |
|---|---|
| `mvn -o compile` | ✅ clean, 0 errors |
| `mvn -o test` | ✅ **84 tests, 0 failures, 0 errors**, BUILD SUCCESS |
| Dangling references in `static/` | ✅ none (`realisticDemo`, `cleanSheet`, `swingUIDemo`, `api/v2/match` all clear) |
| Live path intact | ✅ `newLogic/sim/**` untouched; `demo/service/**` untouched; `static/demo/service/ui/proposal/**` untouched |
| LOC removed from build | ~40,000 |

### Deliberately kept

| Path | Why |
|---|---|
| `newLogic/sim/**` | The live engine |
| `demo/service/**` (19,488 LOC) | Design reference (`corePrinciples.md` §1–48) + calibration oracle. Sprint 6.2 freezes it and moves it out of the build path |
| `static/demo/service/ui/proposal/**` | The replay viewer the SPA actually navigates to |

### Follow-ups raised

- Sprint 1 grew by 3 tasks. Sprint 7 shrank by ~5 days. Net Sprint 1 +8 days, Sprint 7 −5 days.
- Sprint 6.5 (documentation rewrite) is now **urgent** — `AGENTS.md` describes engines deleted in this commit, and the two name collisions are precisely the kind of thing that misleads a future agent.
- `util/match/MatchContext.java`, `util/analysis/MatchAnalyzer.java`, `util/players/{PlayerActionProbabilityModel, PlayerConditionService}.java` are now orphans. Left in place to keep this commit focused; Sprint 0.6 / 6.4.

---

## Sprint 0 — Stop the bleeding

*(in progress)*

### S0.1 — Close the €1 transfer exploit
### S0.2 — Fix the transfer-list soft-lock
### S0.3 — Idempotency guard on weekly training
### S0.4 — Feed `Skills.*Exact` into the engine
### S0.5 — Passive fatigue recovery
### S0.6 — Delete the dead offside / rules paths
### S0.7 — Security and endpoint hygiene
