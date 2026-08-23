# demo/service Engine — Progression & Status

**Package:** `org.example.footballmanager.demo.service`
**Source of Truth:** `corePrinciples.md`
**Last Updated:** 2026-08-23

---

## Current Aggregate Stats (30-match batch)

| Metric | Now | Real Football | Status |
|--------|-----|--------------|--------|
| Goals/match | 3.4 | 2.5-2.8 | ✅ User: "up to 5.5 is OK" |
| Home goals/match | ~1.9 | 1.6 | ✅ |
| Away goals/match | ~1.5 | 1.0 | ✅ Balanced (was 0.2) |
| BTTS | 33% | 50-55% | ⚠️ Low — needs improvement |
| Possession (home) | 50% | 50-55% | ✅ PERFECT |
| Shots/match | 25.7 | 12-15 | ⚠️ High |
| Corners/match | 12.8 | 5-8 | ⚠️ High — cornerChance 0.40 |
| Crosses/match | 18.1 | 15-25 | ✅ IN RANGE |
| Centers/match | 3.8 | 10-15 | 🔴 Low — needs increase |
| Throw-ins/match | 6.8 | 15-25 | 🔴 Low — lateral 3.5 insufficient |
| Goal kicks/match | 8.7 | 8-12 | ✅ IN RANGE |
| Free kicks/match | 12.4 | 15-22 | ⚠️ Slightly low |
| Penalties/match | 0.2 | 0.3 | ✅ |
| Fouls/match | 14.1 | 15-22 | ✅ Close |
| Yellow cards/match | 1.3 | 2-3 | ⚠️ Slightly low |
| Red cards/match | 0.17 | 0.1-0.2 | ✅ |
| VAR reviews/match | 1.6 | 1-3 | ✅ Fixed — was 9.9 |
| Offsides/match | 2.8 | 2-4 | ✅ IN RANGE |
| Air pass ratio | 47% | 20-25% | ⚠️ High |

---

## Changes Applied (2026-08-23)

### Bug Fixes
1. **VAR offside for AWAY** — `VARService.checkOffside()` line 52: `row < minDefenderRow` was always false for AWAY (Double.MAX_VALUE). Fixed to compute correct offside line per team.
2. **AWAY penalty kick target** — `ActionEngine.executePenaltyKick()` used `GOAL_POSITION` (7, 3.5) for both teams. Fixed: AWAY aims at `new Position(1, 3.5)`.
3. **Miss ball reset** — Shot miss reset from `(7, 3.5)` to `(4, 3.5)` center.
4. **CROSS/CENTER inFinalThird bug** — `inFinalThird = row >= 6` and `inTheBox = row >= 6` were identical, making CROSS/CENTER impossible. Fixed: `inFinalThird = row >= 5` (HOME) / `row <= 3` (AWAY).
5. **Balanced team generation** — Added `generateTeam(String teamSide, String teamName, long skillSeed)` overload. Batch runner uses same seed for both teams, eliminating skill asymmetry.
6. **VAR frequency gates** — All gates now set `lastVARDecision = "NO_REVIEW"` instead of `"_CONFIRMED"` when gate fires. `logVARDecision()` already skips on `"NO_REVIEW"`.
7. **VAR batch runner bug** — `ComprehensiveBatchRunner` L200 now counts VAR events from `result.events()` (MatchRecorder), not `result.logs()` (ActionLogService). Old code counted INFO log entries mentioning "VAR " as reviews.

### Tuning
8. **Pass lateral deviation** — 0.15 → 0.40 → 1.20 → 3.0 → 6.0 → 3.5 (final)
9. **Row clamping** — 0.7-7.3 → 0.85-7.15 → 0.92-7.08 → 0.0-8.0 (allows ball past end lines for goal kicks)
10. **Column clamping** — 0.0-7.0 → -0.5-7.5 → -0.5-8.5 (allows ball past sidelines for throw-ins)
11. **Penalty box dimensions** — Narrowed from rows 6-7, cols 1-6 to row 7 only, cols 2-5, plus 15% random gate on penalty awards
12. **Foul probability** — base 0.04 → 0.06, skill modifier 0.04 → 0.05, attacker bonus 0.02 → 0.03
13. **Offside tolerance** — 0.05 → 0.20 cells (~2.8m real-world)
14. **Corner chance** — 0.60 → 0.40 (defender clearance over end line)
15. **Cross frequency gate** — 50% random gate added
16. **Center scoring weights** — boxPresence 8.0→5.0, crossingQuality 0.5→0.35, progression 0.5→0.35
17. **DetermineRestart thresholds** — Changed from `< 1`/`> 6` (cols) and `< 1`/`> 7` (rows) to `< 0.5`/`> 7.5` (both) — balls must be clearly past boundary lines

---

## What Needs Work — USER-PRIORITIZED

### 🔴 Fix Soon

1. **Centers 3.8 → 10-15** — Center gate removed, weights reduced, but still too low. May need scoring weight increase.
2. **Throw-ins 6.8 → 15-25** — Lateral deviation 3.5 insufficient. Need higher value or different mechanism.
3. **Corners 12.8 → 5-8** — CornerChance 0.40 still too high. May need further reduction.
4. **BTTS 33% → 50-55%** — Away team not scoring enough despite balanced teams.
5. **Shots 25.7 → 12-15** — Too many shots per match.

### ⚠️ Medium Priority

6. **Yellow cards 1.3 → 2-3** — Slightly low, will improve with more fouls.
7. **Free kicks 12.4 → 15-22** — Slightly low.

---

## What's Done

### Core Architecture
- **MatchState** — authoritative state container (ball, players, score, clock, cards)
- **MatchSimulator** — tick-based simulation loop (refactored: 1651→1292 lines via §37 extractions)
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

### §37 Refactoring (2026-08-23)
MatchSimulator reduced from 1,651 → 1,292 lines via three phase extractions:
1. **RestartManager** — kickoff, corners, goal kicks, throw-ins (~100 lines extracted)
2. **OffsideService** — offside checks + VAR review + free kick awarding (~60 lines extracted)
3. **DisciplineService** — foul→card→VAR→penalty/free-kick decisions (~160 lines extracted)
All 16 existing unit tests pass. Batch stats verified identical to pre-refactoring baseline.

### Match Viewer UI (2026-08-23)
- **New web-based viewer** at `static/demo/service/ui/index.html`
- Horizontal pitch: HOME left (row 1), AWAY right (row 7) — opposite of SwingUI vertical layout
- LED scoreboard with team names, score, match clock
- Canvas pitch with player dots, ball, carrier highlight
- Event timeline sidebar: ALL events from MatchRecorder + ActionLogService logs
- Playback: Play/Pause, seek bar, speed slider (0.25x–8x), keyboard shortcuts
- Data: `POST /api/service/match/simulate` (live) or load `match.json` (standalone)
- Export: `MatchSnapshotExporter` runs headless simulation, writes `match.json` to static resources
- **Auto-load removed** — empty state shown until user clicks Generate or Play
- **LogEntry enriched** — `tick` field added for viewer timeline positioning, `context` field `@JsonIgnore`d
- **match.json includes logs** — ActionLogService entries (DECISION, ACTION, OUTCOME, DUEL, etc.) with team, player, tick data
- **Merged timeline** — viewer merges MatchRecorder events + ActionLogService logs into unified feed
- **Team attribution** — all log entries show team (HOME/AWAY) with color-coded highlighting

### Football Rules
- **Offside** — second-to-last defender, 0.20 cell tolerance (~2.8m real-world)
- **Fouls** — base 0.06, skill modifier 0.05, attacker bonus 0.03, penalty box 0.005 (row 7/1, cols 2-5, 15% gate)
- **Cards** — straight red dangerous tackles, yellow → auto second yellow → red
- **Corners** — 40% deflection → corner (was 60%), save-rebound 60%, shot-blocked 40%, cross/center end-line projection
- **Throw-ins** — from sideline exits (col < 0.5 or > 7.5) (6.8/match — needs more)
- **Goal kicks** — from behind goal exits (8.7/match — ✅ in range)
- **VAR** — frequency-gated: offside 20%, goal 15%, penalty 25%, red 40%, yellow 10%
- **Cross gate** — 50% random gate added

### Testing
- `MatchBatchRunner.java` — 10/100-match batch diagnostic
- `ComprehensiveBatchRunner.java` — full statistical analysis
- `MatchChainTrace.java` — action chain trace
- `MatchSnapshotExporter.java` — headless export for web viewer
- `MatchViewerLauncher.java` — embedded HTTP server (port 8765) + browser launcher

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
