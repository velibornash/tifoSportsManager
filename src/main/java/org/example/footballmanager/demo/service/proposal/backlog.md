# Proposal Engine — Backlog

Ordered by priority. Implementation of individual engine logic is deferred —
stubs exist, logic comes later.

---

## P1 — Stats layer (highest gap per user requirement)

> Every action must be recorded in stats for statistics, analysis, and future
> linking with the main manager. Per team AND per player.

### P1a — Per-team stats
- [ ] Shots: total / on-target / blocked / saved / missed
- [ ] Goals: total / open-play / center / cross / penalty / free-kick / corner
- [ ] Passes: total / successful / thru / center / cross / air / ground
- [ ] Dribble: total / successful
- [ ] Interceptions, deflections
- [ ] Restarts: corners / throw-ins / goal-kicks / free-kicks / penalties
- [ ] Cards: yellow / red / double-yellow
- [ ] Offside, VAR: total / confirmed / overturned
- [ ] Possession: %, average possession duration, longest possession

### P1b — Per-player stats
- [ ] Minutes played, average rating
- [ ] Shots: total / on-target / goals (same breakdown as team)
- [ ] Passes: total / successful / thru / center / cross / air / ground
- [ ] Dribble: total / successful
- [ ] Interceptions, deflections
- [ ] Fouls committed / received
- [ ] Cards (yellow, red, double-yellow)
- [ ] Duels: total / won / lost
- [ ] Offside count

### P1c — Implementation
- [ ] `MatchStats` model: per-team + per-player counters (new class)
- [ ] `StatsCollector`: wired into orchestrator — called on every event
  (RECEIVE, INTERCEPT, DEFLECT, SHOT, GOAL, DUEL, RESTART, PASS...)
- [ ] Export to `match.json` (new `statistics` field)
- [ ] Viewer sidebar: Match Stats panel (table of key stats)

---

## P2 — Orchestrator slimming

> Orchestrator has ~340 lines: log formatting, event recording, result
> handling, duel detection.  Too much for "just coordinates."

- [ ] Extract `handleBallPhysicsResult()` → `BallResultHandler` helper
- [ ] Extract `detectAndResolveDuels()` → `DuelService` helper
- [ ] Extract log formatting → `ActionLogService` (structured log with tags)
- [ ] Orchestrator keeps only: clock → unlock → ball → decision →
  execution → tactical → movement → restart → rules → duels → stats

---

## P3 — Ball physics calibration

- [ ] DEFLECT_R: 0.035 → 0.05 or 0.07 — test both, measure impact
- [ ] readIntercept: verify prob is realistic (not 0.45 near receiver)
- [ ] Goal plane detection: confirm off-target shots never cross goal
  line inside mouth (tested in session 6.9)
- [ ] Post hit: test bounce angle/damp (currently reflect + damp = 0.6)
- [ ] OOB hold: confirm ball goes 1 cell past line on miss

---

## P4 — Player movement improvements

> Players move by pace skill A→B, can go around obstacles.

- [ ] Obstacle avoidance: `separateFromOpponents` is slide-ring only —
  implement real perpendicular go-around when blocked ahead
- [ ] Per-tick tactical target refresh: when ball crosses a new grid cell,
  all non-carrier players recalculate tactical targets from TacticalIntentEngine
- [ ] Carrier speed modulation: faster when free (no defenders in 1 cell),
  slower under active pressure (TYPE A override active)
- [ ] Wall avoidance: when blocked by teammate, slide perpendicular
  (same as opponent separation but for own team)

---

## P5 — Offside full implementation

- [ ] Continuous tracking: `trackOffsidePositions()` every tick for all
  attackers on both teams (per corePrinciples §16)
- [ ] Per-pass check: offside at pass/cross/through-ball moment
- [ ] Second-to-last defender rule (not last — FIFA Rule 11)
- [ ] Offside retreat: already in ThreatOverrideEngine stub (TYPE C) —
  implement `applyOffsideRetreat()` logic from demo/service
- [ ] Counter reset: when player is clearly onside, counter resets

---

## P6 — Pass completion calibration (67% → target ~98%)

> Deferred — implementation comes later.

- [ ] Investigate root cause: `readIntercept` still too permissive
  (launch-speed model active, 0.14 lane, every tick)
- [ ] Test DEFLECT_R 0.035 vs 0.05 vs 0.07 — measure deflection
  count + pass completion impact
- [ ] Tune `interceptChance` formula / lane threshold / pm+def gate
- [ ] Run `ProposalBatchDiag 50` after each change, record metrics

---

## P7 — Rules stubs (already created, logic later)

| Class | Package | Status |
|---|---|---|
| `VARService` | `rules/` | stub — method signatures, no logic |
| `DisciplineService` | `rules/` | stub — modular rule methods, no logic |
| `OffsideService` | `rules/` | stub — tracking + check, no logic |
| `ThreatOverrideEngine` | `engine/` | stub — TYPE A/B/C methods, no logic |

All four already compile. Logic to be filled per respective phases above.

---

## P8 — Backlog (not scheduled)

### Fatigue system
- [ ] Stamina drain per tick (based on running distance)
- [ ] Speed multiplier from fatigue (max 30% loss per `MAX_FATIGUE_SPEED_LOSS`)
- [ ] Auto-sub at configurable threshold
- [ ] Injury risk increase with fatigue

### Transition logic (possession change)
- [ ] When ball is lost (intercept/deflect/tackle): immediate reorganization
- [ ] Losing team shifts shape to defensive (no 2-3 second delay)
- [ ] Winning team shifts shape to attacking
- [ ] Track possession chains (chain ID, pass count per possession)

### Hard rules → boost refactoring (backlog only)
- [ ] `CleanDecisionEngine` final-2-row SHOT hard rule: refactor so that
  the rule becomes a large score boost (e.g. +200) so the decision
  engine naturally picks it; if random misses, there's a strong reason
- [ ] Same for kickoff special-case (lines 43-51)
- [ ] The override system as a formal layer is NOT needed now — hard
  rules stay until they can be expressed as boosts

### App log (structured, debug-quality)
- [ ] `ActionLogService` with structured tag system (DEC, ORC, BAL, DUL,
  RST, TAC, THREAT, VAR, DISC, OFF)
- [ ] Two output levels: FULL (stdout/file for debug) + COMPACT (sidebar/UI)
- [ ] All engines log through this service (not raw println)

### Rating system
- [ ] Per-player average rating based on actions (goals, assists, tackles,
  passes, fouls, cards)
- [ ] Export in match.json

### Viewer enhancements
- [ ] Stats tab in sidebar (P1c)
- [ ] Player highlight on click (show stats)
- [ ] Possession % bar

---

## Rules of engagement

- All changes verified via `ProposalBatchDiag 10` (or 50 for stats)
- Engine is non-deterministic — judge via batches, never single traces
- `mvn -q compile` must pass after every change
- `match.json` is 58MB and gitignored — never commit
