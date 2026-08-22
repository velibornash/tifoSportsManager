# demo/service Engine — Progression & Status

**Package:** `org.example.footballmanager.demo.service`
**Source of Truth:** `corePrinciples.md`
**Last Updated:** 2026-08-23

---

## Current Aggregate Stats (100-match batch)

| Metric | Before | After | Real Football | Status |
|--------|--------|-------|--------------|--------|
| Goals/match | 4.1 | 1.7 | 2.5-2.8 | ⬇️ Dropped — balanced teams |
| Home goals/match | 3.8 | 1.2 | 1.6 | ⬇️ More balanced |
| Away goals/match | 0.2 | 0.5 | 1.0 | ⬆️ 3:1 ratio (was 17:1) |
| BTTS | 11% | 21% | 50-55% | ⬆️ Improved |
| Possession (home) | 57% | 50% | 50-55% | ✅ PERFECT |
| Shots/match | 12.4 | 14.7 | 12-15 | ✅ IN RANGE |
| Corners/match | 9.4 | 7.0 | 5-8 | ✅ IN RANGE |
| Crosses/match | 0 | 38.1 | 15-25 | ⚠️ Too high — needs tuning |
| Centers/match | 0 | 4.3 | 10-15 | ⬆️ Appeared, needs increase |
| Throw-ins/match | 2.0 | 2.6 | 15-25 | 🔴 Still too low |
| Goal kicks/match | 38.8 | 36.0 | 8-12 | 🔴 Still too high |
| Penalties/match | 2.4 | 2.3 | 0.3 | 🔴 Still too high |
| VAR reviews/match | 14.5 | 9.9 | 1-3 | 🔴 Still too high |
| Fouls/match | 9.4 | 9.4 | 15-22 | ⚠️ Slightly low |
| Yellow cards/match | 0.8 | 0.9 | 2-3 | ⚠️ Slightly low |
| Offsides/match | 4.8 | 5.9 | 2-4 | 🔴 Too high |
| Air pass ratio | 19% | 18% | 20-25% | ⚠️ Close |

---

## Changes Applied (2026-08-23)

### Bug Fixes
1. **VAR offside for AWAY** — `VARService.checkOffside()` line 52: `row < minDefenderRow` was always false for AWAY (Double.MAX_VALUE). Fixed to compute correct offside line per team.
2. **AWAY penalty kick target** — `ActionEngine.executePenaltyKick()` used `GOAL_POSITION` (7, 3.5) for both teams. Fixed: AWAY aims at `new Position(1, 3.5)`.
3. **Miss ball reset** — Shot miss reset from `(7, 3.5)` to `(4, 3.5)` center.
4. **VAR batch detection** — Changed from `desc.contains("VAR_")` to `ch.equals("VAR") || desc.contains("VAR ") || desc.contains("VAR(")`.
5. **Free kick batch detection** — Changed from `desc.contains("FREE_KICK")` to `ch.equals("FREE_KICK")`.
6. **CROSS/CENTER inFinalThird bug** — `inFinalThird = row >= 6` and `inTheBox = row >= 6` were identical, making CROSS/CENTER impossible. Fixed: `inFinalThird = row >= 5` (HOME) / `row <= 3` (AWAY).
7. **Balanced team generation** — Added `generateTeam(String teamSide, String teamName, long skillSeed)` overload. Batch runner uses same seed for both teams, eliminating skill asymmetry.

### Tuning
8. **CROSS conditions** — Restricted to `inFinalThird && onWing` (was broader).
9. **CENTER conditions** — Restricted to `inFinalThird && !onWing` (was broader).
10. **CENTER scoring weights** — Reduced from `boxPresence * 0.3` and `crossingQuality * 0.35` to lower CENTER frequency.
11. **Pass lateral deviation** — Increased from 0.40 to 1.20 for more throw-ins.
12. **Row clamping** — Tightened from 0.85-7.15 to 0.92-7.08 for fewer goal kicks.
13. **Column clamping** — Widened from 0.0-7.0 to -0.5-7.5 for more sideline exits.
14. **Penalty box foul bonus** — Reduced from 0.02 to 0.005.
15. **VAR frequency gates** — Offside 20%, Goal 15%, Penalty 25%, Red 40%, Yellow 10%.

---

## What Needs Work — USER-PRIORITIZED

### 🔴 CRITICAL — Fix Immediately

1. **Goal kicks 36.0 → 8-12** — Row clamp 0.92-7.08 still too loose. Need tighter end-line control or different mechanism (GK distribution, clearance direction).

2. **Throw-ins 2.6 → 15-25** — Lateral deviation 1.20 insufficient. Most passes aimed at center of field. Need: deflection-based OOB, or carrier near sideline auto-throw-in probability.

3. **VAR reviews 9.9 → 1-3** — Gates applied but VAR still counts all offside events (5.9/match). Need to skip VAR event logging when gate fires (set `lastVARDecision = "NO_REVIEW"` instead of "OFFSIDE_CONFIRMED"`).

4. **Crosses 38.1 → 15-25** — `countBoxAttackers` uses same rows as `inFinalThird` (pr >= 5). Fix: use stricter box definition (pr >= 6 for HOME, pr <= 2 for AWAY).

5. **Centers 4.3 → 10-15** — Needs scoring weight increase or box attacker count fix.

6. **Penalties 2.3 → 0.3** — Penalty box foul probability still too high. Need additional gating mechanism.

7. **Offsides 5.9 → 2-4** — Offside check frequency or tolerance needs adjustment.

### ⚠️ MEDIUM — Fix Later

8. **Goals 1.7 → 2.5-2.8** — Dropped from 4.1 after balanced team fix. Need to boost scoring without touching shot mechanics (user constraint: "golove ne diraj").

9. **BTTS 21% → 50-55%** — Away scoring still low (0.5/match). Will improve with better away balance.

10. **Fouls 9.4 → 15-22** — Slightly low, can tune foul probability.

11. **Yellow cards 0.9 → 2-3** — Will improve with more fouls.

---

## What's Done

### Core Architecture
- **MatchState** — authoritative state container (ball, players, score, clock, cards)
- **MatchSimulator** — tick-based simulation loop
- **PlaymakingDecisionEngine** — action scoring & selection with VisionFilter
- **ActionEngine** — PASS, CARRY, SHOT, THRU, CROSS, CENTER, CLEARANCE execution
- **MovementEngine** — tactical targets + collision avoidance + fatigue multiplier
- **BallMovementEngine** — ball transit, carrier following
- **DuelEngine** — DRIBBLE, RECEIVE_PASS, SHOT, CHASE_BALL duels
- **DuelResolver** — skill-based duel resolution with randomness
- **FootballRulesService** — offside, fouls, cards, corners, goal kicks, throw-ins
- **ExecutionQuality** — pass/shot deviation based on skill
- **TacticalIntentEngine** — tactical targets from TacticsRules
- **VARService** — VAR reviews (offside, goal, red card, penalty) with frequency gates
- **FatigueService** — stamina drain and speed multiplier
- **MatchRecorder** — event & snapshot recording

### Football Rules
- **Offside** — second-to-last defender, 0.05 cell tolerance, smart retreat after 3 consecutive
- **Fouls** — reduced base 0.04, skill modifier 0.04, attacker bonus 0.02, penalty box 0.005
- **Cards** — straight red dangerous tackles, yellow → auto second yellow → red
- **Corners** — 90% deflection → corner, save-rebound 60%, shot-blocked 40%, cross/center end-line projection
- **Throw-ins** — from sideline exits (2.6/match — needs more)
- **Goal kicks** — from behind goal exits (36.0/match — too many)
- **VAR** — frequency-gated: offside 20%, goal 15%, penalty 25%, red 40%, yellow 10%

### Testing
- `MatchBatchRunner.java` — 10/100-match batch diagnostic
- `ComprehensiveBatchRunner.java` — full statistical analysis
- `MatchChainTrace.java` — action chain trace

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
| §12 Ball model | ✅ POSSESSION/IN_TRANSITION/LOOSE states |
| §15 Football rules | ✅ Offside, fouls, cards, restarts |
| §16 Offside | ✅ Second-to-last defender, forward pass check |
| §19 Simulation tick | ✅ Decision → movement → interaction → rules |
| §20 Match phases | ⚠️ KICKOFF/SET_PIECE partially implemented |
| §29 Observability | ✅ ActionLogService |
| §34 Deterministic replay | ⚠️ Seed exists but no formal replay test |
