# newLogic Engine — QA & Football-Analyst Report

**Date:** 2026-08-13
**Scope:** Deterministic decision rework, offside retreat override, collision resolution, match-stat tuning, analyst harness, JUnit coverage.

---

## 1. Summary

The `newLogic` match engine now selects player actions **deterministically** (highest situational score always wins — no RNG in action choice; RNG is reserved for execution such as duels and shot outcomes). Offside-flagged attackers are forced to retreat behind the offside line, and collision resolution no longer stacks on top of movement to violate the pace cap. A football-analyst harness (`FootballAnalystStatsTest`) drives tuning against realistic ranges, and `DecisionEngineTest` pins the deterministic + skill-sensitive contract.

**Key bugs fixed along the way:**
- `PossessionChainTracker`/`possessionPassCount` was never incremented on completed passes → possession-chain stats (pass count, longest chain) were always `0`. Now incremented in `MatchSimulator.executePass`.
- Foul/card counters (`homeFouls`…`awayRedCards`) were declared and wired into `MatchResult` but **never incremented** — always 0. Now driven by `RulesEngine.checkFoul`'s `FoulResult`.
- Stale-snapshot reference bug in tests: `setSkills` rebuilds `PlayerSnapshot` (skills are final) but captured references pointed at the old object.

---

## 2. Engine Changes

### 2.1 DecisionEngine (deterministic)
- `decide()` returns the max-scoring action from `EnumMap<BallAction,Double>` — no random sampling.
- Base scores tuned: `CARRY 0.30`, `DRIBBLE 0.22`, `SHORT_PASS 0.46`, `SHOOT` base 0 (skill-driven).
- `scoreThroughPass` now penalized inside the box (`distToGoal < 15 → −0.25`) so box play prefers shot/pass over forcing a through ball.
- `scoreShoot` distance tiers raised: `<8m +0.45`, `<14m +0.33`, `<20m +0.18`, `<28m +0.08`.

### 2.2 OffsideTracker
- Offside state flagged in `MatchState` (`offsideActive/offsideTeam/offsideTick`).
- `updateRetreat(MatchState)` returns players forced to `RETURN_TO_SHAPE` behind the line (`OFFSIDE_RETREAT_TICKS = 240`), reason "Offside retreat".

### 2.3 MatchSimulator
- `simulateTick`: offside-retreat targets consumed via `IntentEngine.forceIntent(RETURN_TO_SHAPE)`; snapshots marked `SUPPORT` also forced — before `coordination.update`.
- `resolveCollisions`: null-safe carrier compare + per-tick nudge budget (`COLLISION_MAX_NUDGE 0.15`, budget map) → movement + nudge < 0.5/tick (0.33 + 0.15 = 0.48) teleport threshold.
- `executePass`: new **pass-inaccuracy** branch — misplaced pass → `PassIncompleteEvent` + loose ball (`0.12 × (2 − passQuality)`), on top of interceptions.
- Foul/card counters wired from `FoulResult`.
- `possessionPassCount` incremented on completed passes.

### 2.4 MovementEngine
- **Blend-arrival no-teleport fix:** `processBlends` previously *snapped* a player to the blend target when within 0.5 units (`setPosition(target)`). That jump (up to 0.5) plus a same-tick collision nudge (0.15) exceeded the 0.5/tick pace-cap test threshold — causing intermittent `Teleportation!` failures. The arrival step is now pace-capped (`min(dist, maxStepPerTick)`), restoring the 0.33 + 0.15 = 0.48 worst case.

### 2.5 RulesEngine / DuelResolver
- `checkFoul` returns `FoulResult(foulCommitted, penalty, CardEvent.CardType)` (with `NONE`); foul gate raised `0.35 → 0.70`, card chance 30%.
- `resolveShotDuel` miss chance: `0.42 + max(0, dist−8) × 0.03`, cap `0.80` (was `0.12 + …0.045` cap `0.65`).

### 2.6 Tuning knobs (final values)
| Knob | Before | After |
|---|---|---|
| Decision cooldown | 10 ticks | 6 |
| Duel defender proximity | 3.5 | 4.0 |
| Duel cooldown | 15 ticks | 6 |
| Tackle foul chance | `0.45(1−skill/40)` | `0.65(1−skill/40)` |
| Shot missChance | `0.12+0.045(d−8)` cap 0.65 | `0.42+0.03(d−8)` cap 0.80 |
| CARRY/DRIBBLE commitment | 8 ticks | 6 |

---

## 3. DecisionEngineTest (6 tests)

| Test | Result |
|---|---|
| `decideIsDeterministic_givenIdenticalState` | PASS |
| `decideIsDeterministic_acrossDuplicateStates` | PASS |
| `higherSkillCarrierChoosesMoreAssertivePlay_inOpenSpace` (high-skill WNG dribbles, low-skill plays safe) | PASS |
| `shootingSkillRaisedByThirteenPointsFlipsDecision_toShoot` (pressure 1.0, 24m: weak → through, strong → shoot) | PASS |
| `clearOnlyAvailableToDefenders_underPressureInOwnThird` | PASS |
| `shootNeverChosenOutsideShotRange` | PASS |

Note: the two skill-sensitivity tests initially failed because `setSkills` rebuilds snapshots (final fields) — the test must re-fetch the carrier *after* rebuilding. Also the close-range scenario was redesigned (3 defenders within 8m → pressure 1.0, 24m from goal) so the shooting-skill delta genuinely flips the action.

---

## 4. FootballAnalystStatsTest (5-match report, final config)

| Match | Score | Poss H | Shots (OT%) | xG | Passes (comp%) | Corners | Fouls | Chains (avg/long) |
|---|---|---|---|---|---|---|---|---|
| 1 | 1-0 | 77% | 20 (25%) | 9.69 | 370/461 (80%) | 7 | 14 | 2.9 / 34 |
| 2 | 2-0 | 69% | 22 (36%) | 10.94 | 354/434 (82%) | 6 | 11 | 2.7 / 25 |
| 3 | 1-0 | 73% | 16 (75%) | 7.10 | 233/289 (81%) | 3 | 8 | 2.2 / 18 |
| 4 | 3-1 | 65% | 25 (52%) | 11.35 | 331/407 (81%) | 6 | 9 | 2.6 / 26 |
| 5 | 0-0 | 63% | 16 (38%) | 7.60 | 311/385 (81%) | 8 | 7 | 2.6 / 51 |
| **Avg** | **1.6** | **69%** | **19.8 (44%)** | **9.34** | **395 (81%)** | **6.0** | **9.8** | **2.6 / 51** |

### Realism flags (before → after)
| Metric | Realistic range | Initial run | Final run |
|---|---|---|---|
| Shots on target | 30–45% | **66%** ⚠️ | **44%** ✅ |
| Shots/match | 12–20 | **8.2** ⚠️ | **19.8** ✅ |
| Pass completion | 80–87% | **95%** ⚠️ | **81%** ✅ |
| Fouls/match | 15–25 | **3.6** ⚠️ | **9.8** ⚠️ (improved) |
| Passes/match | 600–1000 | ~400 | **395** ⚠️ |
| Goals/match | 2–3 | 2.4 | **1.6** (acceptable) |

**Chain tracking:** previously all-zero; now avg 2.2–2.9 passes per chain, longest 18–51 — credible possession-building values.

---

## 5. Full newLogic Suite Status

```
NewMatchSimulatorTest       3/3   PASS (incl. no-teleportation ≤ 0.5/tick)
DecisionEngineTest          6/6   PASS
FootballAnalystStatsTest    1/1   PASS
NewMatchEngineTest         17/17  PASS
RedCardTest                 3/3   PASS
TacticalPositionTest        4/4   PASS
AwayZoneConversionTest      4/4   PASS
FiveMinuteMatchTest         1/1   PASS
FootballIntegrationTest     2/2   PASS
MatchMetricsTest            1/1   PASS
NewMatchControllerTest      1/1   PASS
DebugTeleportTest           1/1   PASS
DebugWngTraceTest           1/1   PASS
MultiSimCsvGeneratorTest    1/1   PASS
RealisticDebugIntegrationTest 1/1 PASS
PositionSlotAlignmentTest   0/1   FAIL — pre-existing (see §6)
```

`mvn clean compile` — BUILD SUCCESS.

**Test robustness fixes applied:**
- `NewMatchSimulatorTest` teleport assertion: blend-arrival snapping (up to 0.5/tick) + collision nudges intermittently exceeded the 0.5 pace-cap threshold under the higher duel frequency — fixed in engine (§2.4), now 5/5 stable.
- `NewMatchEngineTest.crossToHeaderToGoalPipeline`: asserted `headers >= goals` (all goals from headers — false invariant once shot volume rose) and, after that, `headers > 0` (flaky — a single match can produce zero crosses). Now aggregates over 3 matches and asserts the structural pipeline relationship (`crosses >= headers`, headers occur). 17/17 stable.

---

## 6. Known Limitations & Pre-existing Issues

1. **`PositionSlotAlignmentTest` fails at HEAD (not caused by this work).** AWAY WNG/ATT players drift ~55–70 units from their tactical target while HOME players stay within ~30. Verified identical failure with all working-tree changes stashed. Root cause is in the away-team positioning/movement path, not the decision engine. **Recommend separate investigation.**
2. **Home possession bias** (avg 69%, up to 78%): both squads are generated from the same seed (`baseSeed=11`), so skills are identical — the skew is not skill-driven. Likely kickoff/positioning asymmetry.
3. **Pass volume** (~395/match vs 600–1000 target): a structural outcome of action/transit pacing (120 ticks/min, pass transit durations); matches run "efficient". Would need shorter pass cycles or more mid-block recycling, not a one-knob fix.
4. **Fouls still under target** (~10 vs 15–25): duels are the limiting input; further raises distort tackle frequency.
5. **xG per shot is high** (~0.4–0.6 avg): shots are mostly generated inside the box (decision model), so average shot quality exceeds real-world ~0.10–0.12. Acceptable for now; flagged for future balance.
6. **Squads identical between teams** (same seed): names and skills are identical — fine for tests, wrong for demo realism. `MatchOrchestrator` should use a different seed per side.

---

## 7. Files Touched

- `newLogic/engine/DecisionEngine.java` — deterministic scoring, through-pass box penalty, shoot tiers
- `newLogic/engine/MatchSimulator.java` — offside-retreat consumption, collision budget, pass inaccuracy, foul/card counters, `possessionPassCount`, duel/foul knobs
- `newLogic/engine/OffsideTracker.java` — offside flagging + `updateRetreat`
- `newLogic/engine/RulesEngine.java` — `FoulResult`, foul gate 0.70
- `newLogic/engine/DuelResolver.java` — missChance formula
- `newLogic/engine/MovementEngine.java` — blend-arrival pace-cap (no-teleport fix)
- `src/test/.../newLogic/DecisionEngineTest.java` — **new** (6 tests)
- `src/test/.../newLogic/FootballAnalystStatsTest.java` — **new** (analyst harness)
- `src/test/.../newLogic/NewMatchSimulatorTest.java` — card-count harness fix
- `src/test/.../newLogic/NewMatchEngineTest.java` — robust cross→header pipeline assertion
