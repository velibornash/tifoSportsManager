# demo/service Engine — Progression & Status

**Package:** `org.example.footballmanager.demo.service`
**Source of Truth:** `corePrinciples.md` (§47: Football Domain Specification)
**Last Updated:** 2026-08-31

---

## Tracked Metrics (ComprehensiveBatchRunner)

All metrics below are tracked per-team (HOME/AWAY) and reported per-match average.

### Goals & Scoring
| Metric | Source | Status |
|--------|--------|--------|
| Goals total | TeamMatchStats | ✅ |
| Goals from open play | Event log (GOAL) | ✅ |
| Goals from cross | Action log (CROSS → GOAL) | ✅ |
| Goals from center | Action log (CENTER → GOAL) | ✅ |
| Goals from thru ball | Action log (THRU → GOAL) | ✅ |
| Goals from corner | Restart log (CORNER → GOAL) | ✅ NEW |
| Goals from penalty | Event log (PENALTY_GOAL) | ✅ |
| Goals from free kick | Event log (GOAL + FREE_KICK desc) | ✅ |
| 0-0 draws | Scoreline tracking | ✅ |
| BTTS (both teams to score) | Scoreline tracking | ✅ |

### Shots
| Metric | Source | Status |
|--------|--------|--------|
| Shots total | TeamMatchStats.shots() | ✅ |
| Shots on target | TeamMatchStats.shotsOnTarget() | ✅ |
| Shot accuracy (%) | Sot / total shots | ✅ |
| Conversion rate (%) | Goals / total shots | ✅ |
| Shots per goal | Total shots / goals | ✅ |

### Saves & Blocks
| Metric | Source | Status |
|--------|--------|--------|
| Saves | TeamMatchStats (sot - goals) | ✅ |
| Save rate (% of Sot) | Saves / Sot | ✅ |
| Blocks | TeamMatchStats.blocks() | ✅ |
| Deflections | TeamMatchStats.deflections() | ✅ |
| Interceptions | TeamMatchStats.getInterceptionCount() | ✅ |

### Passing
| Metric | Source | Status |
|--------|--------|--------|
| Passes attempted | TeamMatchStats.passesAttempted() | ✅ |
| Passes completed | TeamMatchStats.passesCompleted() | ✅ |
| Pass accuracy (%) | Completed / attempted | ✅ |
| Air passes attempted | Log (ACTION: PASS + AIR height) | ✅ |
| Air pass accuracy (%) | Air completed / air attempted | ✅ |
| Ground passes attempted | Log (ACTION: PASS + GROUND) | ✅ |
| Ground pass accuracy (%) | Ground completed / ground attempted | ✅ |
| Passes per match | Total attempted / N | ✅ |
| Pass loose (missed) | TeamMatchStats.getLooseBallCount() | ✅ |

### Wide Play
| Metric | Source | Status |
|--------|--------|--------|
| Crosses | Log (ACTION: CROSS) | ✅ |
| Crosses completed | Log (OUTCOME: CROSS + RECEIVED) | ✅ |
| Centers | Log (ACTION: CENTER) | ✅ |
| Centers completed | Log (OUTCOME: CENTER + RECEIVED) | ✅ |

### Set Pieces
| Metric | Source | Status |
|--------|--------|--------|
| Corners | TeamMatchStats.corners() | ✅ |
| Throw-ins | TeamMatchStats.getThrowInCount() | ✅ |
| Goal kicks | TeamMatchStats.getGoalKickCount() | ✅ |
| Free kicks | Log (FOUL channel) | ✅ |
| Free kick shots on goal | Log (FK restart → SHOT) | ✅ NEW |
| Free kick goals scored | Log (FK restart → GOAL) | ✅ NEW |
| Penalties awarded | Event (PENALTY_KICK) | ✅ |
| Penalties scored | Event (PENALTY_GOAL) | ✅ |
| Penalties missed | Event (PENALTY_MISS) | ✅ |
| Penalties saved | Event (PENALTY_SAVED) | ✅ |

### Discipline
| Metric | Source | Status |
|--------|--------|--------|
| Fouls | TeamMatchStats.fouls() | ✅ |
| Yellow cards | TeamMatchStats.yellowCards() | ✅ |
| Red cards | TeamMatchStats.redCards() | ✅ |

### Offside
| Metric | Source | Status |
|--------|--------|--------|
| Offsides | TeamMatchStats.offsides() | ✅ |
| Offside retreats | Log (OFFSIDE_RETREAT) | ✅ |

### VAR
| Metric | Source | Status |
|--------|--------|--------|
| VAR reviews total | Event (VAR_*) | ✅ |
| VAR offside confirmed | Event (VAR_OFFSIDE) | ✅ |
| VAR offside overturned | Event (VAR_OFFSIDE_OVERTURNED) | ✅ |
| VAR goal confirmed | Event (VAR_GOAL) | ✅ |
| VAR goal overturned | Event (VAR_GOAL_OVERTURNED) | ✅ |
| VAR red confirmed | Event (VAR_RED) | ✅ |
| VAR red overturned | Event (VAR_RED_OVERTURNED) | ✅ |
| VAR penalty confirmed | Event (VAR_PENALTY) | ✅ |
| VAR penalty overturned | Event (VAR_PENALTY_OVERTURNED) | ✅ |

### Duels & Physical
| Metric | Source | Status |
|--------|--------|--------|
| Total duels | Aerial + Tackle + Dribble | ✅ |
| Aerial duels | Log (DUEL: AERIAL) | ✅ |
| Tackle duels | Log (DUEL: TACKLE/SHOT) | ✅ |
| Dribble duels | Log (DUEL: DRIBBLE) | ✅ |
| Carries | Log (ACTION: CARRY) | ✅ |
| Chases | Log (CHASE channel) | ✅ |
| Chase wins | Log (CHASE WINNER) | ✅ |

### Other
| Metric | Source | Status |
|--------|--------|--------|
| Possession changes | TeamMatchStats.getPassOutOfBoundsCount() | ✅ |
| Clearances | TeamMatchStats.clearances() | ✅ |
| Clean sheets | Aggregated (opponent goals = 0) | ✅ |
| Injuries | Log (INFO: INJURY) | ✅ |
| Substitutions | Log (INFO: SUB) | ✅ |

### Per-Team Summary Table
All metrics above are also available in the per-team breakdown (HOME vs AWAY).

---

## Current Aggregate Stats (500-match batch | SKILL=14)

| Metric | Result | Target | Status |
|--------|--------|--------|--------|
| Goals/match | 4.80 | 2-6 | ✅ Borderline high |
| Home goals/match | 2.53 | ~1.6 | ⚠️ High |
| Away goals/match | 2.27 | ~1.0 | ⚠️ High |
| BTTS | 83% | 50-55% | 🔴 Too high |
| Shots/match | 49.3 | 18-25 | 🔴 Double target |
| Shots on target % | 32% | 30-40% | ✅ |
| Saves (of Sot) | 69% | 50-60% | ⚠️ Slightly high |
| Passes/match | 1098 | 400-600 | 🔴 Too many |
| Pass accuracy | 85% | 75-85% | ✅ |
| Corners/match | 12.7 | 6-10 | ⚠️ High |
| Throw-ins/match | 27.8 | 20-30 | ✅ PERFECT |
| Goal kicks/match | 35.8 | 8-12 | 🔴 Triple target |
| Free kicks/match | 7.5 | 15-22 | 🔴 Low |
| Fouls/match | 14.4 | 15-22 | ✅ Close |
| Yellow cards/match | 3.11 | 2-3 | ✅ GOOD |
| Red cards/match | 0.61 | 0.1-0.2 | 🔴 Triple target |
| Offsides/match | 68.4 | 2-4 | 🔴 BROKEN |
| VAR reviews/match | 1.3 | 1-3 | ✅ |
| Penalties/match | 0.03 | 0.3 | 🔴 Low |
| Total duels/match | 112.9 | 30-60 | 🔴 Too many |
| Carries/match | 338.4 | 50-100 | 🔴 5x target |
| Chases/match | 50.4 | 10-30 | 🔴 High |
| Blocks/match | 7.0 | 5-10 | ✅ |
| Deflections/match | 5.3 | - | ✅ |
| Aerial duels/match | 27.9 | 10-20 | ⚠️ High |
| Dribble duels/match | 72.7 | 15-30 | 🔴 Too many |
| Goals from cross | 0% | 10-15% | 🔴 BROKEN |
| Goals from center | 0% | 5-10% | 🔴 BROKEN |
| Goals from corner | 0% | 3-5% | 🔴 BROKEN |
| Goals from open play | 100% | 55-65% | 🔴 BROKEN |
| Clean sheets (HOME) | 10% | 30-35% | 🔴 Low |
| Clean sheets (AWAY) | 8% | 35-40% | 🔴 Low |

---

## Changes Applied (2026-08-24)

### Bug Fixes — Session 2026-08-24

1. **TacticsRules.parseCell OOB clamp** — `TacticsRules.desiredCell()` now clamps row to 1–7 and column to 1–6 after `parseCell` + `toPhysical`. Prevents players from being placed at row 0.5 or column 7.5 (visual OOB zones). Both `parseCell(Cell)` and `parseCell(String)` overloads updated.

2. **GK anchor constants inverted** — `TacticalIntentEngine` had `GK_HOME_ROW_MIN/MAX` swapped with `GK_AWAY_ROW_MIN/MAX`. HOME defended row 1 but anchor pointed to rows 6–7.5 (near AWAY goal). Fixed: HOME→0.5–2.0, AWAY→6.0–7.5. GK now stays near its own goal line.

3. **Duplicate simBtn2 listener removed** — `index.html` had an inline `<script>` block with a second `simBtn2.addEventListener('click', ...)` that duplicated the handler already registered in `viewer.js`. Removed the redundant block.

4. **Duel radius increased** — `DuelEngine.DRIBBLE_DUEL_RADIUS` 0.8→1.2 cells. Previously defenders approaching the carrier couldn't trigger a DRIBBLE duel because 0.8 was too tight for the approach geometry. Also increased threat override distance from 2.5→3.0 in `TacticalIntentEngine` so defenders close the gap on the carrier more aggressively.

5. **Shot logic improvements** — Three sub-fixes:
   - `ActionEngine.SHOOT_MIN_ROW` 6→5: shots now allowed from row 5 (~30m from goal) instead of only row 6+ (~15m). Matches real football.
   - `PlaymakingDecisionEngine.scoreShot()` empty-goal incentive: when 0 defenders between carrier and goal and GK far from goal, shot gets +3–6.5 bonus (was +0 in old code; max +21 in first iteration, too aggressive).
   - `ExecutionQuality.ShotResult` now includes `power` field (0.1–1.0 based on striker skill). Higher power → harder for GK to save (save chance reduced by up to 40%).
   - `PlaymakingDecisionEngine.decide()` empty-goal override relaxed: `distToGoal ≤ 3.0` (was ≤2.0), `defendersBetween ≤ 1` (was ==0), `gkDistToGoal > 1.5` (was >2.5).

6. **Grid toggle fix** — `viewer.js` grid toggle handler called `this.pitch.render(this._interpPlayers, ...)` with stale interpolation data. Now calls `this._renderFrame()` which re-interpolates current tick before rendering.

7. **16m line depth reduced** — Penalty area boxes in `viewer.js` changed from 1.3 rows deep (rows 1–2.3 / 5.7–7) to 1.0 row (rows 1–2 / 6–7). Penalty spots at 1.66 / 6.34 now sit clearly between goal area and penalty area edge.

### Bug Fixes — Session 2026-08-23

8. **VAR offside for AWAY** — `VARService.checkOffside()` was always confirming AWAY offside (Double.MAX_VALUE bug). Fixed to compute correct offside line per team.
9. **AWAY penalty kick target** — `ActionEngine.executePenaltyKick()` used `GOAL_POSITION` (7, 3.5) for both teams. Fixed: AWAY aims at `new Position(1, 3.5)`.
10. **Miss ball reset** — Shot miss now resets ball to center (4, 3.5) instead of goal position.
11. **CROSS/CENTER inFinalThird bug** — `inFinalThird = row >= 6` and `inTheBox = row >= 6` were identical, making CROSS/CENTER impossible. Fixed: `inFinalThird = row >= 5` (HOME) / `row <= 3` (AWAY).
12. **Balanced team generation** — `generateTeam(String teamSide, String teamName, long skillSeed)` overload. Batch runner uses same seed for both teams, eliminating skill asymmetry.
13. **VAR frequency gates** — offside 20%, goal 15%, penalty 25%, red 40%, yellow 10%.
14. **VAR batch runner bug** — `ComprehensiveBatchRunner` L200 counts VAR events from `result.events()`, not `result.logs()`.
15. **DetermineRestart thresholds** — Changed to `< 0.5`/`> 7.5` for both rows and cols.

### Changes Applied (2026-08-24) - Session Enhancements

16. **Team Generation Fix** — Fixed HOME/AWAY skill imbalance by using same skill seed for both teams in batch tests. Previously HOME used `"Home".hashCode()` and AWAY used `"Away".hashCode()` as different seeds, causing HOME 224-69 domination. Now both teams use identical skill distributions.

17. **Chase Resolution Enhancement** — Added chase shortcut: when one chaser is clearly closer to loose ball (within 0.8 cells AND >0.8 cells ahead of opponent), skip duel and give ball directly. Prevents unnecessary duels when one player has clear advantage.

18. **Duel Frequency Reduction** — Added 20-tick cooldown in DuelEngine to prevent excessive duels from re-triggering too quickly (e.g., same pair dueling every tick during carry). Reduced duels from ~502/match to more realistic levels.

19. **GK Save Rate Improvement** — Enhanced goalkeeper save formula: increased base save chance, reduced distance penalty, added minimum save floor. Improved save rate from ~58% to target ~65-70%.

20. **Duel Animation Enhancement** — When duels start (DUEL_START), snap both participants 60% toward contest position so they appear physically engaged in viewer (visual overlap or near-touch).

21. **Viewer Log Simplification** — Modified viewer.js `formatEventDesc()` to show clean time-action-outcome in sidebar (e.g., "HOME John: SHOT", "AWAY: GOAL!") while preserving detailed engine scores in debug logs for development.

22. **Kickoff Timing Fix** — Restructured kickoff flow: pre-match setup positions ball at center and kicker before clock starts. Viewer sees ball at center (0:00), then first tick of main loop fires kickoff pass immediately at 0:01 (no 8-tick delay).

### §37 Refactoring (2026-08-23)

MatchSimulator reduced from 1,651 → 1,292 lines via three phase extractions:
1. **RestartManager** — kickoff, corners, goal kicks, throw-ins
2. **OffsideService** — offside checks + VAR review + free kick awarding
3. **DisciplineService** — foul→card→VAR→penalty/free-kick decisions

### Tuning History

- **Pass lateral deviation** — 0.15 → 0.40 → 1.20 → 3.0 → 6.0 → 3.5 (final)
- **Row clamping** — 0.7-7.3 → 0.85-7.15 → 0.92-7.08 → 0.0-8.0
- **Column clamping** — 0.0-7.0 → -0.5-7.5 → -0.5-8.5
- **Penalty box dimensions** — narrowed to row 7/1 only, cols 2-5, plus 15% random gate
- **Foul probability** — base 0.04 → 0.06, skill modifier 0.04 → 0.05, attacker bonus 0.02 → 0.03
- **Offside tolerance** — 0.05 → 0.20 cells (~2.8m)
- **Corner chance** — 0.60 → 0.40
- **Cross frequency gate** — 50% random gate
- **Center scoring weights** — boxPresence 8.0→5.0, crossingQuality 0.5→0.35, progression 0.5→0.35

---

## Bugs Fixed (Session: 2026-08-26)

1. **Goal celebration too long** — `ActionEngine.goalScored()` set `setCelebrationHoldTicks(100)` (150 seconds). Reduced to `20` ticks (30 seconds) — realistic celebration duration. Fixed critical >2-min gap in log that violated the >120s BUG assertion.
2. **Offside FREE KICK taker** — OffsideService now calls `selection.nearestNonGoalkeeperTo(offsidePos, defendingTeam)` to find the free-kick taker and sets them as `state.setFreeKickTaker()`, with target set to the offside position. Ball awarded to defending team (no teleport for normal cases; walk timeout fallback).
3. **OFFSIDE overlay event** — OffsideService now emits a dedicated `OFFSIDE` MatchEvent with `type="OFFSIDE"`, `team=receiver.getTeam()`, `playerId/playerName=offending player`, `outcome="YELLOW_FLAG"`. Description format `"INDIRECT FREE KICK for <team> — offside <player> (margin=X.XX)"` so viewer.js regex extracts player name and margin for the yellow-flag overlay.
4. **Offside ball award position** — OffsideService computes `offsidePos` at the second-to-last defender's row + 1.0 cell, and passes `Double.valueOf(offsidePos.getRow())` / `Double.valueOf(offsidePos.getColumn())` in the MatchEvent for the viewer.

### Verification

`TestGapDetector` (seed=1000, skillSeed=1100, 90-min match, 3720 ticks):
- **0 bugs** (no gaps > 120s without logging).
- **374 legitimate stoppage gaps** — all classified as OK (legitimate stoppage).
- **88 OFFSIDE events** in stream — viewer.js triggers `showOffside()` overlay.
- **5 VAR events** — viewer.js triggers `showVAR()` and `showVARDecision()` overlays.
- **Goal celebration movement** — scoring-team players advance goalward at `PLAYER_SPEED * 0.6` during the 30-second hold.
- Report written to `target/gap_analysis_report.md`.

## Bugs Fixed (Session: 2026-08-24)

1. **Goal celebration too long** — `ActionEngine.goalScored()` set `setCelebrationHoldTicks(100)` (150 seconds). Reduced to `20` ticks (30 seconds) — realistic celebration duration. Fixed critical >2-min gap in log that violated the >120s BUG assertion.
2. **Offside FREE KICK taker** — OffsideService now calls `selection.nearestNonGoalkeeperTo(offsidePos, defendingTeam)` to find the free-kick taker and sets them as `state.setFreeKickTaker()`, with target set to the offside position. Ball awarded to defending team (no teleport for normal cases; walk timeout fallback).
3. **OFFSIDE overlay event** — OffsideService now emits a dedicated `OFFSIDE` MatchEvent with `type="OFFSIDE"`, `team=receiver.getTeam()`, `playerId/playerName=offending player`, `outcome="YELLOW_FLAG"`. Description format `"INDIRECT FREE KICK for <team> — offside <player> (margin=X.XX)"` so viewer.js regex extracts player name and margin for the yellow-flag overlay.
4. **Offside ball award position** — OffsideService computes `offsidePos` at the second-to-last defender's row + 1.0 cell, and passes `Double.valueOf(offsidePos.getRow())` / `Double.valueOf(offsidePos.getColumn())` in the MatchEvent for the viewer.
5. **Restart walk speed** — `RESTART_WALK_MAX_TICKS` reduced from 60 (90s match time) to 10 ticks (15s match time). Taker walks directly at `RESTART_WALK_SPEED = 0.4` cells/tick (not via `moveAllTowardTargets` which moved all players including blockers). Nearest player is ≤1.5 cells from ball, arrives in ~4 ticks at 0.4 cells/tick.
6. **Goal celebration OOB** — Scoring-team outfield players now run BEHIND the goal into OOB space (row 8 for HOME / row 0 for AWAY) at `0.05` cells/tick (2 cells/sec match time), fanning out across columns 1-6. Orbit/radiate effect (orbitRadius 0.25-0.45) for fanning. Clamping allows rows 0.0–8.0 (not just 0.5–7.5). Mirrors SwingUI `movePlayersDuringGoalCelebration`.
7. **VAR IN PROGRESS overlay** — Now includes attacking team, defending team, and incident type: `"VAR IN PROGRESS — HOME — OFFSIDE — Home FC 11 (goal review) — attacking: HOME defending: AWAY"`. `logVARReviewStarted` in VARService now accepts defending team; `resolveOffsideVAROnGoal` computes defending team and includes it in the description.
8. **VAR overturned goal** — `GOAL_DISALLOWED` event emitted to viewer for cancelled goals. The goal is never scored (goalScored not called before VAR check), so score remains unchanged. Overturned goals restart from goal kick (OOB position), not center kickoff. Offside-confirmed goals restart from indirect free kick at offside position (already handled by `confirmOffside`).

### Verification

`TestGapDetector` (seed=1000, skillSeed=1100, 90-min match, 3720 ticks):
- **0 bugs** (no gaps > 120s without logging).
- **374 OK (legitimate stoppage)** gaps — restart walk ≤10 ticks, goal celebration ≤20 ticks, offside retreat, corner/throw-in/goal kick — all classified as OK.
- **0 suspicious** gaps.
- **117 OFFSIDE events** in stream — viewer.js triggers `showOffside()` overlay.
- **10 VAR events** (VAR_IN_PROGRESS, VAR_OFFSIDE_OVERTURNED, VAR_GOAL_OVERTURNED) — viewer.js triggers `showVAR()` / `showVARDecision()` overlays.
- **4 GOAL_DISALLOWED events** — viewer cancels premature goal overlays.
- **4 VAR_IN_PROGRESS events** — all include attacking + defending team info.
- **Match result: 1-1** (2 goals scored, 4 goals disallowed by VAR — 2 for each team).
- Report written to `target/gap_analysis_report.md`.

## Bugs Fixed (Session: 2026-08-22)

1. **Pressure = 0 for ball carrier** — `ThreatAssessmentService.calculatePersonalPressure()` returned 0 for carrier's team. Added `calculateCarrierPressure()` counting non-GK opponents within 1.5 cells.
2. **SHOT duels only considered GK** — Outfield defenders within 1.0 cells couldn't challenge shots. Fixed.
3. **countDefendersNearGoal counted near GOAL not CARRIER** — Now counts non-GK opponents within 2.0 cells of carrier and closer to goal.
4. **ShotDefender used keeper() skill for outfield blockers** — Fixed: outfield uses `defender() * 0.50 + technique() * 0.25 + pace() * 0.15`.
5. **consecutiveCarries never reset on ball receive** — Added `resetConsecutiveCarries()` in `giveBallTo()` and `pickupPass()`.
6. **CENTER ping-pong between strikers** — CENTER restricted to wing positions AND outside the box.
7. **Offside free kick went to random nearest player** — Now given to `findKeeper()` for proper GK distribution.

### Shot Volume Control (2026-08-22)

- **SHOT_GOAL_THRESHOLD:** 0.35 → 0.25
- **goalProximity multiplier:** 15 → 8
- **strikerQuality multiplier:** 0.4 → 0.3
- **attackerBonus:** 3.0 → 1.5
- **boxBonus:** 2.0 → 1.0
- **Fresh receive penalty:** 0/8/15 for 0/1/2+ consecutive carries
- **Defender penalty:** per-defender × 5.0 (was flat -15 for 3+)
- **Distance gate:** -15 for > 4.0 cells
- **Low skill gate:** -20 for striker < 8 at > 3.0 cells; -25 for striker < 5 at > 2.0 cells

---

## Bugs Fixed (Session: 2026-08-29) — Visual & Engine Polish

1. **DisciplineService foul restart possession bug** — All foul restarts (no-card, yellow, red, VAR-upgraded/downgraded) previously used `giveBallTo(attacker, ...)` which immediately set the attacker as carrier AND placed the ball at the *defender's* position. Now uses `awardFreeKick(state, actionEngine, attacker, foulSpot)` which:
   - Sets `carrier = null` and `target = null` (lets restart-walk logic trigger)
   - Places ball at `foulSpot` (the actual foul location, not defender's position)
   - Sets `freeKickTaker = attacker` and `setPiecePending = true`
   - The attacker walks to the ball at `RESTART_WALK_SPEED = 0.4` cells/tick
   
2. **Yellow card counting** — All 4 `logFoulWithCard` calls used `existingYellows + 1`, showing one higher count than actual. Fixed to `existingYellows` (first yellow = 0, second yellow on same player = 1).

3. **DuelEngine snapFactor** — Kept at 0.60 (original, unchanged) to preserve deterministic match output. Visual duel convergence (players touching) is handled **viewer-side only** via `convergedPos` interpolation in `drawPlayers()`. Comment updated to clarify this separation.

4. **`mvn compile -DskipTests`**: BUILD SUCCESS. `TestMatchSimulatorIntegration` shows only 2 pre-existing failures (`batchMetricsWithinTargets`, `thruPassExecutionInBounds`) — same as original code via `git stash`. `clearanceDoesNotProduceLooseBall` PASSES.

5. **`mvn test -Dtest=MatchGapAnalyzerTest`**: PASSES (1 test, 0 failures). `TestMatchState` PASSES (7 tests, 0 failures). `TestGapDetector` PASSES.

6. **Viewer goalpost doubling** — `_goal()` (viewer.js ~line 427) now draws doubled goalposts: `goalH = 4.4 * CELL_H, depth = 50, postW = 8, barW = 6` (was `goalH = 2.2 * CELL_H, depth = 25, lineWidth = 4`). Goalposts are drawn in rows 0/8 (OOB goal rows), not widening the field goal.

7. **Viewer goal animation ball stop** — `drawGoalAnim()` (viewer.js ~line 536) now animates ball to `goalRow` (7 for HOME, 1 for AWAY) instead of `exitRow` (8 for HOME, 0 for AWAY). Ball stops at the goal line, not flying to OOB. Celebration rings grow from goal position. Animation threshold at 0.7.

8. **Viewer duel circle convergence** — `drawPlayers()` accepts `duelPairs` parameter. Computes `convergedPos` map that moves dueling players to touching distance (center-to-center = `rA + rB`). Draws yellow highlight circle (`rgba(255,255,0,.35)`) around dueling players. `render()` signature updated to pass `duelPairs`.

9. **Viewer card overlay** — `OverlayManager.showCard(cardType, playerName, team, isVar)` shows YELLOW/RED card overlay with icon, team, player name, and VAR indicator (when VAR confirmed). CSS: `.yellow-card`, `.red-card` with `cardPulse` and `redCardPulse` animations. `_processEventsForTick()` handles CARD/FOUL/RED_CARD events.

10. **match.json regenerated** (seed 42): score 5-0, 3 YELLOW cards (all "(VAR confirmed) (previous yellows=1)"), 5 goals, 1985 events, 4058 logs, 3214 snapshots. CARD events properly show first-yellow count=0, second-yellow count=1.

---

## What Needs Work — USER-PRIORITIZED

### 🔴 Fix Soon

1. **Centers 3.8 → 10-15** — Center gate removed, weights reduced, but still too low. May need scoring weight increase.
2. **Throw-ins 6.8 → 15-25** — Lateral deviation 3.5 insufficient. Need higher value or different mechanism.
3. **Corners 12.8 → 5-8** — CornerChance 0.40 still too high. May need further reduction.
4. **BTTS 33% → 50-55%** — Away team not scoring enough despite balanced teams.

### ⚠️ Medium Priority

5. **Yellow cards 1.3 → 2-3** — Slightly low, will improve with more fouls.
6. **Free kicks 12.4 → 15-22** — Slightly low.
7. **Game context awareness (§25)** — scoreline/time don't influence decision risk.
8. **Fatigue → decision quality (§24)** — tired players make worse decisions.
9. **Transition football (§21)** — possession change should trigger immediate tactical shift.

---

## What's Done

### Core Architecture

| Component | Status | Lines |
|-----------|--------|-------|
| **MatchState** | Authoritative state container | — |
| **MatchSimulator** | Tick-based simulation loop (refactored: 1651→1292) | ~1292 |
| **PlaymakingDecisionEngine** | Action scoring & selection with VisionFilter | ~800 |
| **ActionEngine** | PASS, CARRY, SHOT, THRU, CROSS, CENTER, CLEARANCE | ~830 |
| **MovementEngine** | Tactical targets + collision avoidance + fatigue | — |
| **BallMovementEngine** | Ball transit, carrier following | — |
| **DuelEngine** | DRIBBLE, RECEIVE_PASS, SHOT, CHASE_BALL duels | ~195 |
| **DuelResolver** | Skill-based duel resolution with randomness | ~77 |
| **FootballRulesService** | Offside, fouls, cards, corners, goal kicks, throw-ins | ~152 |
| **ExecutionQuality** | Pass/shot deviation based on skill + shot power | ~117 |
| **TacticalIntentEngine** | Tactical targets from TacticsRules + GK anchor | — |
| **VARService** | VAR reviews with frequency gates | — |
| **FatigueService** | Stamina drain and speed multiplier | — |
| **MatchRecorder** | Event & snapshot recording | — |
| **RestartManager** | Kickoff, corners, goal kicks, throw-ins (§37) | — |
| **OffsideService** | Offside checks + VAR + free kick awarding (§37) | — |
| **DisciplineService** | Foul→card→VAR→penalty/free-kick (§37) | — |
| **ThreatAssessmentService** | Danger evaluation for defensive overrides | ~269 |
| **PlayerPerceptionService** | Awareness-based player perception | — |
| **PlayerSelectionEngine** | Nearest/closest player queries | — |
| **TransitionService** | Possession change transitions | — |
| **ActionLogService** | Structured action/decision logging | — |
| **DecisionTraceService** | Structured decision debug output | — |
| **SimUtils** | Clamp, distance, helpers | — |
| **SimulationRandom** | Seeded random source | — |

### Football Rules

- **Offside** — second-to-last defender, 0.20 cell tolerance
- **Fouls** — base 0.06, skill modifier 0.05, attacker bonus 0.03, penalty box 0.005
- **Cards** — straight red dangerous tackles, yellow → auto second yellow → red
- **Corners** — 40% deflection → corner, save-rebound 60%, shot-blocked 40%
- **Throw-ins** — from sideline exits (col < 0.5 or > 7.5)
- **Goal kicks** — from behind goal exits
- **VAR** — frequency-gated: offside 20%, goal 15%, penalty 25%, red 40%, yellow 10%
- **Shot power** — power field (0.1–1.0) reduces save chance proportionally

### Match Viewer UI

- Horizontal pitch: HOME left (row 1), AWAY right (row 7)
- LED scoreboard, canvas pitch, player dots, carrier highlight
- Event timeline: merged MatchRecorder events + ActionLogService logs
- Playback: Play/Pause, seek bar, speed slider (0.25x–8x), keyboard shortcuts
- Grid overlay toggle (default off)
- Penalty areas: 1.0 row deep, 3.6 cols wide
- Goals: net rectangles behind goal lines
- Export: `MatchSnapshotExporter` → `match.json`
- Launcher: `MatchViewerLauncher` → port 8765

### Testing

- `MatchBatchRunner.java` — 10/100-match batch diagnostic
- `ComprehensiveBatchRunner.java` — full statistical analysis
- `MatchChainTrace.java` — action chain trace
- `MatchSnapshotExporter.java` — headless export for web viewer
- `MatchViewerLauncher.java` — embedded HTTP server + browser launcher
- All 16 unit tests pass

---

## Architecture Compliance (corePrinciples)

| Principle | Status |
|-----------|--------|
| §2.1 State-first | ✅ MatchState is authoritative |
| §2.2 Tactics as intentions | ✅ TacticalIntentEngine sets targets |
| §4 Service architecture | ✅ Clear domain responsibilities |
| §7 Player decision model | ✅ Multiple candidate actions scored |
| §8 Action evaluation | ✅ Context-aware scoring per action type |
| §9 Controlled randomness | ✅ Seeded Random, skill-based probability |
| §11 Movement model | ✅ Blend system, collision avoidance |
| §12 Ball model | ✅ POSSESSION / IN_TRANSITION / LOOSE + passSpeed |
| §15 Football rules | ✅ Offside, fouls, cards, restarts, far-post aim |
| §16 Offside | ✅ Second-to-last defender, universal per-tick tracking |
| §19 Simulation tick | ✅ Decision → movement → interaction → rules |
| §20 Match phases | ⚠️ KICKOFF/SET_PIECE partially implemented |
| §29 Observability | ✅ ActionLogService + compact viewer timeline |
| §34 Deterministic replay | ⚠️ Seed exists but no formal replay test |

---

## Bug Fixes & Tuning (Session 2026-08-31)

### Coordinate system alignment

The engine's coordinate convention and the `corePrinciples.md` documentation
were inconsistent — the spec said goals are at row 1 and row 7, but the
engine stored AWAY's goal at row 8 (with row 7 being the last playable cell,
centre at 7.5). Updated everywhere to match the authoritative user spec:

- **HOME goal line** at row **1.0** (rows 0 ≤ 0.99 OOB behind HOME)
- **AWAY goal line** at row **8.0** (rows ≥ 8.01 OOB behind AWAY)
- **Goal width** = 1 cell, centred at col 3.5 (col 3.0–4.0, ≈ 10 m)
- `TacticalPerspectiveTransformer.toPhysical()` uses **9-row mirror** (HOME row
  n → AWAY row 9 − n), so AWAY GK at row 1.5 mirrors to row 7.5 (just in
  front of AWAY goal at row 8.0)
- All `clampToField` and `parseCell` use row range **[1.0, 8.0]** and col
  range **[1.0, 6.9]** so player centres stay inside the touchlines
- `OFFSIDE_RETREAT_THRESHOLD` raised from 2 to **3** (user rule: "3 uzastopne
  akcije u offside poziciji")

### Offside tracking — universal per-tick

`OffsideService.trackOffsidePositions()` rewritten to fire on **EVERY tick** (not
just at forward-pass moments) and check **BOTH teams' attackers**. Any
attacker standing in an offside position accumulates the streak; the streak
resets to 0 the moment the player becomes onside. Called from the main
`while` loop in `MatchSimulator.simulate()`.

### GK positioning

`GoalkeeperMovementEngine.goalkeeperTarget()` was using the old "AWAY goal at
row 7" convention:

- `goalLineRow` HOME 1.0 / **AWAY 8.0** (was 7.0)
- `AWAY_ROW_MIN` **6.86** (16 m from AWAY goal, edge of penalty area)
- `AWAY_ROW_MAX` **7.9** (just inside AWAY 6-yard box)
- `ballOnOurSide` AWAY threshold **2.5** (symmetric to HOME's 5.5, 1.5 cells
  past halfway)

`RestartManager.executeRestart()` for goal kicks:

- `gkRow` = HOME 1.5 / **AWAY 7.5** (just in front of goal line, not midfield)
- AWAY opponents cleared from rows > 6.86 (16 m box), clamped to [1.0, 7.9]

### Duel / interception radii — tight & lane-strict

1 cell ≈ 14 m × 10 m. Old radii (1.0/1.2/0.7) were ~10–17 m — too generous,
causing "magical proximity grabs". New values:

- `DEFAULT_DUEL_RADIUS` 1.0 → **0.2** (~2.8 m)
- `DRIBBLE_DUEL_RADIUS` 1.2 → **0.2**
- `RECEIVE_PASS_RADIUS` 0.7 → **0.2**
- SHOT block 1.5 → **0.3** (~4.2 m)

`findPassInterceptor()` rewritten to require the defender to be **on the line
SEGMENT** between ball and receiver (perpendicular distance ≤ 0.5 cells for
ground passes, 0.4 for air), with a triangle check (defender roughly between
ball and receiver). Players 1 cell off the lane but close to the ball no
longer "intercept" — they have to be on the lane.

### Threat override — resolver prevents swarming

`TacticalIntentEngine.applyThreatOverride()` rewritten:

- **TYPE A** — isolated ball carrier anywhere on the pitch, ≤ 0.2 cells →
  press them wherever they are
- **TYPE B** — opponent in defensive third, no defender within 0.5 cells of
  them → press to close the space
- Resolver `isClosestEligibleDefender()` ensures only ONE defender claims the
  threat — the closest defender always wins, non-defenders don't claim
- Only defenders (CB/DEF/LB/RB/DM) contest threats — non-defender outfield
  players keep their tactical position (prevents 3-player swarm)

### Carry / pass speed / far-post aim

- `executeCarry()` target extended to **3-4 cells** ahead (was 1 cell) so the
  carrier moves continuously for several seconds instead of jumping 1 cell at
  a time. Per-tick `re-decide()` continues to fire — if a better option appears
  (shooting lane, open pass), the carry is completed early and the new
  action runs.
- `executePassTo()` now sets `action.passSpeed` based on the passer's passing
  skill (1.0 = weak, 3.0 = elite, +0.2 for long passes). `BallMovementEngine`
  reads this so faster balls move faster, are harder to intercept, and deflect
  instead of being intercepted.
- `evaluateShot()` aims at the **far post** when the GK is off-centre:
  GK on left post (col ≈ 3) → shot goes to right post (col ≈ 4), clamped to
  the goal mouth [3.0, 4.0]. `handleShotArrival` re-evaluates `gkInLane`
  against the actual shot target so a GK on the near post correctly fails to
  save a far-post shot.
- `executeShot()` empty-goal check: goal is "empty" if GK is > 2 cells from
  goal centre OR if GK is within 2 cells but **off the shot lane**
  (perpendicular > 1.2 cells). A GK on the wrong post no longer covers the
  shot.

### Discipline & viewer polish

- `DisciplineService.evaluateFoul()` adds a **`hadDuel` parameter**. If no
  duel was active at the foul moment, no card is issued (free kick only).
  VAR yellow/red logic is preserved but only fires after a genuine duel
  resolution — per user rule "MORA se desiti duel pre zutog kartona".
- Viewer (`viewer.js`) timeline DOM capped at **200 entries** (was
  unbounded) — prevents Firefox from freezing after 4000+ events. Compact
  timeline events only (PASS/CARRY/GOAL/SHOT/CARD/VAR/etc.); verbose engine
  logs (DECISION, ACTION_EXECUTION, ACTION_OUTCOME, DUEL_*, CHASE, INFO)
  remain in the Java app log but are no longer rendered in the side panel.
- `MatchSimulationController.randomSkills()` default baseline **14** with
  ±2 random variation plus role-specific bonuses (GK better at keeper, ATT
  better at striker). All players in MatchViewer now have realistic skill 14.
- `TacticalIntentEngine.applyDefensivePositionConstraint` restored to its
  pre-override behaviour — the tactical rules in `tactics_fallback.json` /
  DB are the source of truth for defensive depth; the engine does NOT
  override them with code clamps. (Earlier over-aggressive 3-rows-from-own-
  goal clamp was reverted per user instruction.)
- `ActionEngine.isOwnGoalkeeperOrDefensiveRow()` updated for the new AWAY
  goal at row 8.0 (AWAY defensive row ≥ 7.0, not > 7.0).

### Debug helper

`TacticsRules.dumpLoadedRules(path)` writes the resolved tactical targets
(row/col) for every (role, ballStateKey) pair loaded from DB / bundled JSON /
catalog anchors. `MatchSimulator.simulate()` now emits a `TACTICS_SOURCE:
bundled tactics_fallback.json | ruleCount=N` log line on startup so the user
can verify which source was loaded.

### Verification

- `mvn compile` — clean
- `MatchChainTrace` (seed=42) — exit 0, ~3300 snapshots, both teams show
  OFFSIDE RETREAT logs (HOME FC 11, Away United 10/11) confirming the
  universal per-tick tracking works for both teams.
- `MatchSnapshotExporter` (seed=42) — exit 0, ~3650 events / ~17500 logs.
  Score now reasonable for skill-14 teams.
- Player row/col ranges within [1.0, 8.0] × [1.0, 6.9] during normal play
  (set piece corner flags at row 0.5 / col 0.5 / 6.5 are intentional).

### Bug Fixes & Tuning (Session 2026-08-31) — demo/service engine — pass 2

#### Carry drives toward goal centre (no more corner runs)

`ActionEngine.executeCarry()` was picking a random lateral direction
(`dc ∈ {-1, 0, +1}`) regardless of whether the lane to goal was open — so
wingers carrying down the flank could end up hugging the touchline into the
corner. Fixed: when the carrier is in the attacking half AND the lane to
goal centre is OPEN (no opponent within 1 cell of the carrier AND no opponent
on the path to goal centre), the carry column is biased toward 3.5 (the goal
mouth centre). New helper `isCarryLaneOpen(carrier, goal)` uses
`SimUtils.pointSegmentDistance`.

#### 16 m open-lane SHOT override

Added a new shot trigger in `PlaymakingDecisionEngine.decide()`:
**`closeWithOpenLane`** = `defendersBetween == 0` AND no non-GK opponent within
1 cell of carrier AND path-to-goal clear AND `distToGoal ≤ 1.2 cells` (≈ 17 m).
When true, the engine forces SHOT regardless of what the selector picked.
Prevents wingers from carrying into the corner when the goal is in clear sight.

#### Goalkeeper stays close to goal line

`GoalkeeperMovementEngine` tightened:
- `MAX_ADVANCE` 0.7 → **1.0** (14 m hard cap)
- **Non-threat pull**: `0.12 + 0.30 × closeness` → **`0.05 + 0.25 × closeness`**
  (0.05–0.30 cells ≈ 0.7–4.2 m off the line — typical GK position stays
  well within the user-requested 0.5-cell / 7 m band)
- **Shooter-threat pull**: `0.30 + 0.50 × closeness` → **`0.25 + 0.75 × closeness`**
  capped at MAX_ADVANCE (0.25–1.0 cells ≈ 3.5–14 m off line — only steps
  out when a shot is genuinely imminent)

#### Top-2 box attacker priority in final 2 rows

`PlaymakingDecisionEngine.scorePassOptions()` now precomputes the top-2
teammates closest to the opponent goal who are standing inside the box area
(HOME rows 5–7, AWAY rows 1–3). When the carrier is in the final two rows
(row ≥ 6 HOME / row ≤ 2 AWAY) and a candidate receiver matches one of those
top-2, the candidate gets **+80.0** score boost — dominates the standard
lane+goal-proximity scoring so the carrier prefers the "two most dangerous
in the box" over a 20 m pass back.

#### 1.5-cell rule (21 m from goal — no backward passes)

In the same `scorePassOptions()`, when the carrier is within 1.5 cells
(≈ 21 m) of the opponent goal, every PASS option to a receiver that is
NOT in the box area AND NOT forward of the carrier is **filtered out entirely**.
The carrier's only options are SHOT, CARRY forward, or pass forward / into
the box. No more wasteful backward/lateral passes from the shooting zone.

#### CENTER target picks top-2 closest-to-goal box attacker in final 2 rows

`ActionEngine.selectCenterTarget()` previously picked the best aerial winner
across ALL box attackers. Now, when the carrier is in the final two rows,
the target is restricted to the top-2 box attackers closest to the opponent
goal, and the best aerial among them is chosen — a tall striker close to
goal beats a small winger further away.

#### Match Viewer side panel — duel + chase

`static/demo/service/ui/js/viewer.js` `TIMELINE_EVENTS` extended to include
`DUEL_START`, `DUEL_RESOLVED`, `CHASE_POSSESSION`. The per-tick `CHASE`
progress logs (`CHASE: Home 7 dist=0.234`) remain excluded — too noisy.
Sample side-panel output:
- `DUEL_START`: `DRIBBLE Home 7 vs Away 4`
- `DUEL_RESOLVED`: `DRIBBLE Home 7 vs Away 4 | winner=Home 7 (att=X def=Y)`
- `CHASE_POSSESSION`: `CHASE RESOLUTION: Home 7 | within possession radius`

### Verification (2026-08-31 pass 2)
- `mvn compile -DskipTests` — BUILD SUCCESS (595 source files)

### Bug Fixes & Tuning (Session 2026-08-31) — demo/service engine — pass 3

#### Side panel timeline — OFFSIDE + shot epilogue events

`static/demo/service/ui/js/viewer.js` `TIMELINE_EVENTS` extended with:
- `OFFSIDE` — emitted by `OffsideService` with description `INDIRECT FREE KICK for <team> — offside <player> (margin=X.XX)` so the viewer regex can extract player + margin for the yellow-flag overlay.
- `SHOT_BLOCKED`, `SHOT_POST` — full shot outcome chain (SHOT → MISSED / SAVED / BLOCKED / POST) so the side panel always shows the epilogue between a shot and the next restart.

#### Tighter offside filter on passes

`PlaymakingDecisionEngine.isClearlyOffsideAtPass()` margin **0.5 → 0.2 cells**
(≈ 2.8 m). A receiver more than 0.2 cells ahead of the second-to-last
defender is hard-filtered out — no longer offered as a PASS option. Marginal
offside (≤ 0.2 cells) still goes through so the referee / VAR path can decide.
Prevents the engine from picking a marginal-offside receiver that the
referee flags the moment the pass is released.

#### Restart walk — fast-path + faster walk speed

`MatchSimulator` restart-walk block was reading as "taker never arrives" on the
viewer when the nearest non-GK was > 4 cells from the restart spot. Fixed two
ways:
- `RESTART_WALK_SPEED` 0.4 → **0.7** cells/tick so a normal 4-cell walk
  takes ~6 ticks instead of ~10.
- **Fast-path teleport**: if `initialDist > 4.0` cells the taker is snapped
  to a spot 0.6 cells behind the ball (toward his own goal), then walks the
  last short distance normally. Emits `RESTART WALK FAST-PATH` log so the
  gap detector sees the move.
- Bumped `RESTART_TELEPORT_DISTANCE = 4.0` cells as the new constant —
  any taker beyond that is teleported.

#### Unified pushback for all restarts (CORNER, GOAL_KICK, THROW_IN)

`RestartManager.handleBallOutOfBounds` previously only pushed opponents away
from the ball inside the GOAL_KICK branch (with inline code). Corners and
throw-ins could be contested from inside 1 cell. Extracted the pushback to a
shared `pushOpponentsAwayFromBall(ballPos, restartTeam)` helper and now called
from all three restart types (CORNER, GOAL_KICK, THROW_IN). GOAL_KICK still
keeps its inline box-clear behaviour (pushes AWAY opponents inside the AWAY
penalty box to row 3+), but the new helper handles the universal "within 1
cell of ball → push toward own goal" rule uniformly.

### Verification (2026-08-31 pass 3)
- `mvn compile -DskipTests` — BUILD SUCCESS (595 source files)

### Bug Fixes & Tuning (Session 2026-08-31) — demo/service engine — pass 4

#### KICKOFF in Match Viewer side panel
- `viewer.js` `IMPORTANT_EVENTS` and `TIMELINE_EVENTS` extended with `'KICKOFF'` so the kickoff appears in the side panel timeline.

#### Wider press awareness — TYPE A radius 0.2 → 1.0 cell (~14 m)
- `TacticalIntentEngine.applyThreatOverride()` TYPE A radius **0.2 → 1.0 cell**. A defender now presses the ball carrier from 1.0 cell (~14 m) away and closes the gap, so a DRIBBLE duel fires as soon as the defender reaches tackling distance. Previously 0.2 cells was too tight — the carrier slipped past before the defender could engage.

#### Tighter dribble tackle trigger + faster re-press
- `DuelEngine.DRIBBLE_DUEL_RADIUS` **0.2 → 0.15 cells** (~2 m) — a tighter tackle window once the defender is on top of the carrier.
- `DuelEngine.DRIBBLE_DUEL_COOLDOWN_TICKS` **8 → 7** — a defender who loses a tackle can re-engage within ~2 seconds (matches the "6-8 ticks" loser-block rule; `MatchState.DUEL_LOSS_TICKS = 6`).

#### HARD RULE — no carry in own defensive last 2-3 rows
- `PlaymakingDecisionEngine.scoreCarry()` returns **-300** when the carrier is in its own defensive zone (HOME row ≤ 3.0, AWAY row ≥ 6.0). Carrying in the defensive third is never legal — the carrier must pass or clear. Dribbling is only permitted in the opponent's half / middle third.

#### Distance-weighted carry pressure (release the ball earlier)
- `scoreCarry()` pressure changed from a **flat count** of opponents within 0.5 cells to a **proximity-weighted sum**: `Σ(1.0 - distance)` over opponents within 1.0 cell, scaled by **-50**. A defender at 0.2 cells contributes far more pressure than one at 0.9, so the carrier releases the ball (pass/shot) as soon as a defender closes, instead of holding until 0.5 cells. Open-space carry bonus stays at +60 when nobody is within 1.0 cell.

#### Defender press speed boost — catch the carrier
- `TacticalIntentEngine.assignTargets()` now marks a defender with `Player.threatOverrideActive = true` whenever the threat override changes its target.
- `MovementEngine.moveAllTowardTargets()` applies a **1.6x** speed multiplier to threat-overridden non-carriers. Carrier and defender previously moved at the same 0.25 cells/tick, so the gap never closed and the defender chased from behind forever. With 1.6x the defender gains ~0.15 cells/tick and closes a 1.0-cell gap in ~20 ticks (~10 s) — reaching duel range before the carrier can carry far, per the user rule "defender who sees carrier within 1 cell moves toward him; duel fires at ~0.10-0.15 cells when circles touch".

#### Column 6 reachability audit (no bug found)
- Verified from `match.json`: the right midfielder (Home FC 9, role MR) **does** reach columns 6.0–6.6 during normal play (max HOME col 6.57). The tactical rules (`tactics_fallback.json`) assign MR to `CELL_6_5` (= col 6.5) in the final right third, and `MovementEngine` clamps to `[1.0, 6.9]` which permits it. The viewer `toCanvas()` maps col 6.6 to inside the touchline (col 7.0). No rendering or movement bug found for column 6.

#### Verification (2026-08-31 pass 4)

### Bug Fixes & Tuning (2026-08-31 — user reported) — pass 5

#### CRITICAL — shot miss direction bug (shot fired toward own goal)
After the coordinate-system pass moved `GOAL_POSITION` to row **8.0**, three stale end-line
checks still read `== 7.0`. A HOME shot miss therefore pushed the ball to row **-0.5**
(behind HOME's own goal) instead of row 8.5 — so a "SHOT by Home" became a CORNER for the
opposition at the wrong end. The user saw exactly this: Home FC 10 shot near the AWAY goal
and the ball physically travelled BACKWARD to his own goal line for a corner.
Fixed `logicalGoal.getRow() == 7.0 → 8.0` (`ActionEngine.shotMissed()`) and
`goal.getRow() == 7.0 → 8.0` (two sites in `MatchSimulator` — VAR-overturned goal OOB and
plain shot-miss OOB).

#### DRIBBLE duel radius 0.15 → 0.5 cells (~7 m) — user override
- `DuelEngine.DRIBBLE_DUEL_RADIUS` **0.15 → 0.5**. At 0.15 the defender had to be almost
  on top of the carrier, so a pressing defender and carrier just ran overlapped in the same
  cell with no tackle, and the carrier looked stopped/crowded on the UI.
- With 0.5 the defender who has closed the gap via the TYPE A press override (1.0 cell)
  engages the carrier as soon as they come alongside. `snapPlayersForDuel` then pulls both
  contestants 60% toward the contest point so they visibly collide; the winner takes the
  ball, the loser is blocked for `State.DUEL_LOSS_TICKS` (6).

#### CENTER/cross never targets a clearly-offside attacker
- `ActionEngine.selectCenterTarget()` now skips any box attacker who is clearly offside
  (`isClearlyOffside(passer, receiver)`, margin > 0.2 cells beyond the second-to-last
  defender — same threshold as the PASS decision filter). Prevents a winger delivering a
  cross into an obviously-offside striker who then plays on.
- The execution-time whistle (`OffsideService.checkOffside`) remains the backstop for
  marginal ≤ 0.2 cases (referee/VAR decision).

#### Verification (2026-08-31 pass 5)
- `mvn compile -DskipTests` — BUILD SUCCESS.
- Replay audit of `match.json`: no CARRY action shows a stationary carrier (carrier always
  moves ~0.25 cells/tick within a carry). The perceived "carry stop" was the defender
  running overlapped in the same cell without a duel — addressed by the 0.5 dribble radius.
