# demo/service Engine — Progress Tracker

**Package:** `org.example.footballmanager.demo.service`
**Source of Truth:** `corePrinciples.md`
**Last Updated:** 2026-08-22

---

## Current Aggregate Stats (10-match batch)

| Metric | Before Fixes | After Fixes | Target | Status |
|--------|-------------|-------------|--------|--------|
| Goals/match | 40.0 | 3.5 | 2-4 | ✅ OK (varies by team strength) |
| Shots/match | 196 | 10.2 | 10-15 | ✅ OK |
| Shots on target | — | 81% | 60-75% | ⚠️ Slightly high |
| Pass accuracy | — | 66% | 75-85% | ⚠️ Low |
| Fouls/match | 107 | 27.2 | 20-25 | ✅ Close |
| Yellow cards/match | — | 2.1 | 2-3 | ✅ OK |
| Red cards/match | — | 0.3 | 0.1-0.2 | ✅ Close |
| Corners/match | 0 | 1.3 | 5-8 | ❌ Too low |
| Offsides/match | 347 | 16.3 | 2-5 | ❌ Too high |
| THRU passes/match | — | 1.4 | 1-3 | ✅ OK |
| Possession (home avg) | — | 56% | 48-52% | ⚠️ Slight home bias |

---

## Bugs Fixed (Session: 2026-08-22)

### BUG 1: Pressure = 0 for ball carrier (CRITICAL)
- **File:** `PlaymakingDecisionEngine.java` (buildContext method)
- **Root Cause:** `ThreatAssessmentService.calculatePersonalPressure()` returned 0 for any player on the carrier's team. Carrier always had pressure = 0, making SHOT score artificially high.
- **Fix:** Added `calculateCarrierPressure()` method that counts non-GK opponents within 1.5 cells of the carrier. Pressure now correctly reflects defensive presence around the ball.
- **Impact:** SHOT scores dropped from 15-27 to 1-10 range.

### BUG 2: SHOT duels only considered GK as opponent
- **File:** `DuelEngine.java` (closestOpponentTo method, line 170)
- **Root Cause:** `if (action.getType() == ActionType.SHOT && !"GK".equals(candidate.getRole())) continue;` — only GK could challenge shots. Outfield defenders within 1.0 cell couldn't block/tackle.
- **Fix:** GK always eligible; DEF/MID within 1.0 cells of carrier can now challenge shots.
- **Impact:** Shots are contested by defenders in the box, reducing shot quality.

### BUG 3: countDefendersNearGoal counted defenders near GOAL not CARRIER
- **File:** `PlaymakingDecisionEngine.java` (countDefendersNearGoal method)
- **Root Cause:** Counted non-GK opponents within 3.0 cells of the goal. Defenders between carrier and goal weren't counted if they were far from the goal line.
- **Fix:** Now counts non-GK opponents within 2.0 cells of the carrier AND closer to the goal than the carrier. Penalty is per-defender (5.0 each) instead of flat -15 for 3+.
- **Impact:** Strikers in crowded boxes get proportional shot penalty.

### BUG 4: ShotDefender used keeper() skill for outfield blockers
- **File:** `DuelResolver.java` (shotDefender method)
- **Root Cause:** All shot defenders used `s.keeper() * 0.60` formula regardless of role.
- **Fix:** GK uses keeper formula; outfield defenders use `s.defender() * 0.50 + s.technique() * 0.25 + s.pace() * 0.15`.

### BUG 5: consecutiveCarries never reset on ball receive
- **File:** `ActionEngine.java` (giveBallTo, pickupPass methods)
- **Root Cause:** `resetConsecutiveCarries()` was only called in MatchSimulator after non-CARRY decisions. But for PASS/SHOT, carrier becomes null and reset is skipped. Players who received the ball could have stale consecutiveCarries.
- **Fix:** Added `receiver.resetConsecutiveCarries()` in both `giveBallTo()` and `pickupPass()`.

### BUG 6: CENTER ping-pong between strikers
- **File:** `PlaymakingDecisionEngine.java` (generateOptions)
- **Root Cause:** CENTER was available from any position in the final third, including inside the box. Strikers in the box kept centering to each other.
- **Fix:** CENTER restricted to wing positions AND outside the box. Central positions outside the box can also center. Carriers inside the box cannot center.

### BUG 7: Offside free kick went to random nearest player
- **File:** `MatchSimulator.java` (offside handling)
- **Root Cause:** After offside, ball given to `findNearestNonGoalkeeperTo()` — random outfield player.
- **Fix:** Ball given to `findKeeper()` for proper GK distribution restart.

---

## Stats Fixes Applied (Session: 2026-08-22)

### Shot Volume Control
- **SHOT_GOAL_THRESHOLD:** 0.35 → 0.25 (ExecutionQuality.java)
- **goalProximity multiplier:** 15 → 8
- **strikerQuality multiplier:** 0.4 → 0.3
- **attackerBonus:** 3.0 → 1.5
- **boxBonus:** 2.0 → 1.0
- **Fresh receive penalty:** 0/8/15 for 0/1/2+ consecutive carries
- **Defender penalty:** per-defender × 5.0 (was flat -15 for 3+)
- **Distance gate:** -15 for > 4.0 cells
- **Low skill gate:** -20 for striker < 8 at > 3.0 cells; -25 for striker < 5 at > 2.0 cells

### Foul Reduction
- **Base foul probability:** 0.65 → 0.15 → 0.08
- **Defender skill modifier:** 0.10 → 0.06
- **Attacker technique modifier:** 0.05 → 0.03
- **Final third bonus:** removed (was 0.05, overlapped with penalty box)
- **Penalty box bonus:** 0.05 → 0.04

### Card Reduction
- **Yellow card rate:** 0.30 → 0.12 → 0.10
- **Straight red base:** 0.02 → 0.005 → 0.002
- **Straight red penalty box:** 0.05 → 0.015 → 0.005

### Corner Generation
- **Deflection chance:** 0.45 → 0.30 → 0.45
- **Corner rebound from save:** 1/3 → 1/7 (~14%)

### Offside Reduction
- **Consecutive offside penalties:** -20 / -80 / -200 for 1/2/3+ (PASS); -20 / -60 / -120 (THRU)
- **VAR tolerance:** 0.3 cells (level + margin = onside)
- **Pre-pass offside check:** -30 if receiver has 0 defenders ahead; -10 if 1 ahead
- **THRU scoring:** spaceBehind multiplier 0.6 → 0.35; offside penalty when 1 defender ahead: -10.0

### Pass Viability
- **Lateral backward penalty:** 3.0 → 1.5 (was killing lateral passes in 4-4-2)
- **Backward penalty:** 5.0 → 4.0
- **Lateral safety multiplier:** 0.2 → 0.5 (was making safe lateral passes worthless)
- **Lateral openness multiplier:** 0.2 → 0.3
- **GK receiver candidates:** 2 → 5 (both in scorePass and executePassTo)

### CENTER Restriction
- **Wing only + outside box:** CENTER cannot be used from central positions inside the box
- **Prevents striker ping-pong:** carriers in the box must SHOT, PASS, or CARRY

---

## corePrinciples.md Compliance

| Principle | Status | Notes |
|-----------|--------|-------|
| §1 Purpose | ✅ | Stateful decision-and-event system |
| §2.1 State-first | ✅ | MatchState is authoritative |
| §2.2 Tactics as intentions | ✅ | TacticalIntentEngine sets targets, not positions |
| §2.3 Football > rigid positioning | ✅ | Threat override exists |
| §3 Simulation model | ✅ | Decision → movement → interaction → rules cycle |
| §4 Service architecture | ✅ | Clear domain responsibilities |
| §4.1 Match State Service | ✅ | MatchState owns authoritative state |
| §4.2 Tactical Service | ✅ | TacticalIntentEngine + TacticsRules |
| §4.3 Spatial/Positioning | ✅ | MovementEngine + TacticalIntentEngine |
| §4.4 Threat Assessment | ✅ | ThreatAssessmentService (pressure now fixed for carrier) |
| §4.5 Player Perception | ✅ | PlayerPerceptionService |
| §5 Tactical Intent | ✅ | TacticsRules → TacticalIntentEngine → movement targets |
| §6 Threat Override | ✅ | ThreatAssessmentService determines override, adjusts targets |
| §7 Player Decision Model | ✅ | 7 action types scored and selected |
| §8 Action Evaluation | ✅ | Context-aware scoring per action type |
| §9 Controlled Randomness | ✅ | Seeded Random, skill-based probability, OptionSelector |
| §10 Seeded Randomness | ✅ | SimulationRandom with seed |
| §11 Movement Model | ✅ | Blend system, collision avoidance, fatigue multiplier |
| §12 Ball Model | ✅ | POSSESSION / IN_TRANSITION / LOOSE states |
| §13 Player-Ball Interaction | ✅ | ActionEngine handles all action lifecycles |
| §14 Player-Player Interaction | ✅ | DuelEngine + DuelResolver |
| §15 Football Rules | ✅ | FootballRulesService — offside, fouls, cards, restarts |
| §16 Offside | ✅ | Second-to-last defender, forward pass, tolerance margin |
| §17 Match Event Model | ✅ | Intent → Action → Interaction → Rule → Event → State |
| §18 State Transitions | ✅ | All transitions have identifiable causes |
| §19 Simulation Tick | ✅ | Tick-based loop in MatchSimulator |
| §20 Match Phases | ⚠️ | KICKOFF/SET_PIECE partially implemented |
| §21 Transition Football | ⚠️ | Minimal — needs expansion |
| §22 Team Shape | ⚠️ | Emergent from tactical targets, not explicitly calculated |
| §23 Player Attributes | ✅ | PlayerSkills with 8 football-relevant attributes |
| §24 Fatigue | ⚠️ | Speed only, not decision quality |
| §25 Game Context | ❌ | Score/time don't influence decision risk |
| §26 Tactical Editor Contract | ✅ | TacticsRules loaded from config |
| §27 Simulator API | ✅ | MatchSimulationController REST endpoints |
| §28 Snapshots | ✅ | MatchRecorder records events and snapshots |
| §29 Observability | ✅ | DecisionTraceService, ActionLogService |
| §30 Decision Trace | ✅ | Scored options logged per decision |
| §31 Randomness + Observability | ✅ | Seed-based, reproducible |
| §32 Statistics | ✅ | MatchStatsCollector derives from events |
| §33 Commentary/Visualization | ⚠️ | Headless only — no commentary layer |
| §34 Deterministic Replay | ⚠️ | Seed exists but no formal replay test |
| §35 Testing | ⚠️ | Unit tests exist, no scenario tests |
| §37 Architectural Boundaries | ✅ | Services have clear domain ownership |
| §39 Complexity Principle | ✅ | Simplest implementation per domain |
| §44 Source-of-Truth Hierarchy | ✅ | Rules > State > Spatial > Threat > Tactics |
| §46 Architectural North Star | ⚠️ | Mostly — game context and transitions need work |
| §47 Implementation Rule | ✅ | Each feature answers 10 questions |

---

## Remaining Work

### HIGH PRIORITY

1. **Offsides too high (16.3/match → target 2-5)**
   - Root cause: defenders push up with the attack, leaving space behind. Receivers get behind the defensive line on forward passes.
   - Possible fixes:
     - Check if TacticalIntentEngine positions DEF too aggressively (defensive line too high)
     - Add minimum row gap between ST and DEF line in THRU runner selection
     - Reduce forward pass distance threshold (> 2.0 → > 3.0 cells)
     - Add "hold line" instruction for DEF — don't push beyond ball row

2. **Corners too low (1.3/match → target 5-8)**
   - Root cause: ball rarely goes over end line. Deflections only 45%. Goal kicks are the dominant restart.
   - Possible fixes:
     - Increase deflection chance (0.45 → 0.55)
     - Add corner generation from blocked shots (not just saved shots)
     - Add corner from cross that goes past the goal line
     - Check if `determineRestart` is called often enough

3. **Pass accuracy too low (66% → target 75-85%)**
   - Root cause: many passes fail due to execution quality + interception. Lateral/backward passes still penalized.
   - Possible fixes:
     - Increase pass execution quality (skill multiplier)
     - Reduce interception range or success rate
     - Add short pass accuracy bonus (< 2.0 cells)

### MEDIUM PRIORITY

4. **MatchSimulator orchestrator refactor**
   - Current: MatchSimulator handles too many responsibilities (rules, VAR, restart, stats, lifecycle, duel resolution)
   - Target: MatchSimulator becomes a thin orchestrator; each concern delegated to its own service
   - Planned decomposition:
     ```
     MatchSimulator (orchestrator)
       ├── MatchState (state container)
       ├── PlaymakingDecisionEngine (decision)
       ├── ActionEngine (execution)
       ├── DuelEngine + DuelResolver (interactions)
       ├── FootballRulesService (rules)
       ├── VARService (VAR decisions)
       ├── MovementEngine (movement)
       ├── BallMovementEngine (ball)
       ├── TacticalIntentEngine (tactics)
       ├── ThreatAssessmentService (threat)
       ├── FatigueService (fatigue)
       ├── TransitionService (transitions)
       ├── MatchStatsCollector (statistics)
       ├── ActionLogService (logging)
       └── MatchRecorder (recording)
     ```
   - Steps:
     1. Extract foul/card logic from MatchSimulator into FootballRulesService
     2. Extract offside handling into FootballRulesService (currently mixed in MatchSimulator)
     3. Extract restart logic (corner/goal kick/throw-in) into FootballRulesService
     4. Extract VAR checking flow into VARService
     5. Extract stats update calls into MatchStatsCollector
     6. MatchSimulator becomes: tick loop + delegate to each service
   - File: `MatchSimulator.java` (~1570 lines, too many responsibilities)

5. **Home possession bias (56% → target 48-52%)**
   - AWAY team may have weaker tactical positioning or the decision engine favors home
   - Need to verify TacticalPerspectiveTransformer produces equal-quality targets

6. **Shots on target % too high (81% → target 60-75%)**
   - Execution quality may be too generous for shots
   - Consider increasing shot deviation or reducing SHOT_GOAL_THRESHOLD further

### LOW PRIORITY

7. **Game context awareness (§25)** — scoreline/time should influence risk tolerance
8. **Fatigue → decision quality (§24)** — tired players make worse decisions
9. **Transition football (§21)** — possession change should trigger immediate tactical shift
10. **Possession chain tracking** — tag events with chainId for causal analysis
11. **Deterministic replay tests** — same seed → same match regression tests
12. **Scenario tests** — counterattack, high press, low block, through ball
13. **Match phases (§20)** — formalize KICKOFF/SET_PIECE/CORNER/FREE_KICK/PENALTY phases

---

## File Inventory

| File | Lines | Responsibility |
|------|-------|---------------|
| MatchSimulator.java | ~1570 | Main loop + rules + VAR + restart + stats + lifecycle |
| PlaymakingDecisionEngine.java | ~800 | Action scoring & selection (7 action types) |
| ActionEngine.java | ~740 | Action lifecycle (start/execute/complete) |
| MovementEngine.java | — | Tactical targets + collision avoidance + fatigue |
| BallMovementEngine.java | — | Ball transit + carrier following |
| DuelEngine.java | ~195 | Duel detection + lifecycle |
| DuelResolver.java | ~77 | Skill-based duel resolution |
| FootballRulesService.java | ~152 | Offside, fouls, cards, restarts |
| VARService.java | — | VAR checks for offside/goals/red cards |
| ExecutionQuality.java | — | Pass/shot deviation based on skill |
| TacticalIntentEngine.java | — | Tactical targets from TacticsRules |
| ThreatAssessmentService.java | ~269 | Danger evaluation for defensive overrides |
| PlayerPerceptionService.java | — | Awareness-based player perception |
| FatigueService.java | — | Stamina drain + speed multiplier |
| TransitionService.java | — | Possession change transitions |
| PlayerSelectionEngine.java | — | Nearest/closest player queries |
| VisionFilter.java | — | PM-based action visibility |
| OptionSelector.java | — | Weighted random among close options |
| ActionLogService.java | — | Structured action/decision logging |
| MatchRecorder.java | — | Event & snapshot recording |
| SimUtils.java | — | Clamp, distance, helpers |
| SimulationRandom.java | — | Seeded random source |
| DecisionTraceService.java | — | Structured decision debug output |
| DecisionContext.java | ~27 | Immutable context record for decisions |
| MatchState.java | — | Authoritative match state container |

---

## Decision Engine Score Ranges (Current)

| Action | Typical Score Range | Dominant Factors |
|--------|-------------------|-----------------|
| PASS | -0.3 to +58 | openness, quality, progression, lateral penalty |
| CARRY | -16 to +12 | availableSpace, spaceAround, congestion, consecutivePenalty |
| SHOT | -7 to +10 | goalProximity, strikerQuality, defenderPenalty, freshReceivePenalty |
| THRU | -145 to +5 | spaceBehind, offsidePenalty, interceptionRisk |
| CROSS | 5 to 20 | boxAttackers, crossingQuality, progression |
| CENTER | 8 to 25 | boxAttackers, crossingQuality, progression |
| CLEAR | 0.5 to 32 | danger, pressure, pmClearBonus |
