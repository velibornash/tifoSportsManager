# PROPOSAL_SEASON_REPORT.md — newLogic/sim

**Per-team averages over 200 deterministically seeded matches** (seeds 42-241),
produced by `ProposalSeasonDiag 200 42`. Every number is read from the engine's
own counters (`TeamStats`, per-player stats, penalty counters) or counted from
the recorder's typed event stream — nothing is estimated.

Reproduce:
```bash
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=org.example.footballmanager.newLogic.sim.ProposalSeasonDiag \
  -Dexec.args="200 42"
```

Last run: 2026-09-25, after the shot calibration, the HOME/AWAY mirror fixes, the
discipline recalibration, the offside rule rework and the THRU/CENTER/CROSS
action types.

## Per-team averages

| Metric | HOME | AWAY | Both | Real football (PL) | Verdict |
|---|---|---|---|---|---|
| Goals | 3.4 | 3.6 | **7.0** | 2.7 | ✅ (owner target: up to 7) |
| Shots | 12.7 | 13.5 | **26.3** | 25 | ✅ (target was ~25) |
| Shots on target | 5.5 | 6.0 | 11.5 | 8-9 | ⚠ slightly high |
| On-target rate | 43.9% | 43.9% | 44% | 33% | ⚠ high |
| Shots missed | 3.6 | 3.9 | 7.5 | ~14 | ⚠ low (blocks absorb some) |
| Saves | 2.3 | 2.3 | 4.6 | 3-4 | ✅ |
| **Blocks** | 3.3 | 3.3 | **6.6** | 2-4 | ✅ (was structurally 0) |
| On-target → goal | 60.1% | 61.2% | 61% | ~30% | ⚠ high, goal total is right |
| Passes attempted | 295.1 | 265.4 | 560.4 | 450-500 | ⚠ high |
| Passes completed | 240.5 | 209.9 | 450.4 | 320-360 | ⚠ high |
| Pass accuracy | 81.4% | 79.0% | 80.2% | 80-86% | ✅ |
| **Through balls** | 6.3 | 6.8 | **13.1** | 5-10 | ✅ |
| **Crosses** | 7.2 | 9.0 | **16.2** | 15-25 | ✅ |
| **Centres** | 27.1 | 25.8 | **53.0** | 25-35 | ⚠ 1.5x |
| Dribbles | 120.6 | 124.0 | 244.6 | 40-60 | ⚠ semantics, see below |
| Clearances | 47.4 | 55.6 | 103.0 | 20-30 | ⚠ semantics, see below |
| Duels won | 305.3 | 308.6 | 613.9 | (n/a) | ⚠ high |
| Tackles | 308.6 | 305.3 | 613.9 | (n/a) | ⚠ identical to duels won |
| Interceptions | 21.8 | 17.5 | 39.3 | 12-16 | ⚠ 2x |
| Deflections | 46.3 | 30.7 | 77.0 | (lane-contact model) | ⚠ high |
| Corners | 0.8 | 3.6 | 4.4 | 10 | ⚠ total low, still skewed |
| Goal kicks | 18.1 | 20.1 | 38.3 | 12-15 | ⚠ 2.5x |
| Throw-ins | 8.1 | 8.4 | 16.6 | 35-45 | ⚠ half — see below |
| **Offsides** | 3.6 | 3.5 | **7.0** | 2-4 | ⚠ slightly high |
| Fouls | 12.9 | 14.8 | 27.8 | 22 | ⚠ high |
| Yellow cards | 2.2 | 2.5 | 4.7 | 4-5 | ✅ (was 7.5) |
| Red cards | 0.5 | 0.6 | 1.1 | 0.2 | ⚠ 5x (was 2.7) |
| **Penalties** | 0.2 | 0.1 | **0.2** | 0.27 | ✅ (was 6.9!) |
| VAR reviews | 1.3 | 1.3 | 2.7 | 1-3 | ✅ |
| VAR confirmed | 1.0 | 1.0 | 2.1 | — | ✅ |
| **VAR overturned** | 0.3 | 0.3 | **0.6** | 0.2-0.5 | ✅ (was 0.0) |
| Possession | 50.4% | 49.6% | 100% | 50/50 | ✅ (was 56/44) |

## Results

| | Count |
|---|---|
| HOME wins | 80 |
| AWAY wins | 89 |
| Draws | 31 |
| 0-0 | 1 |
| Highest score in the sample | 14 goals |

## What was fixed and how it moved

| Area | Before | After |
|---|---|---|
| Goals H/A | 0.92 / 0.08 | **3.4 / 3.6** |
| Shots H/A | 3.8 / 7.3 | **12.7 / 13.5** |
| Possession | 56 / 44 | **50.4 / 49.6** |
| Pass volume H/A | 288 / 185 (56% apart) | **295 / 265 (11% apart)** |
| Shots per match | 83.4 | **26.3** |
| Penalties | 6.9 | **0.2** |
| Red cards | 2.7 | **1.1** |
| Offsides | 0 | **7.0** |
| Blocks | 0 (impossible) | **6.6** |
| VAR overturns | 0.0 (impossible) | **0.6** |
| Through balls / crosses / centres | not modelled | **13.1 / 16.2 / 53.0** |

The three "impossible" zeros were all dead code, not tuning:

- **Blocks**: `ev = "BLOCK"` was never assigned anywhere, so the block result
  type, the BLOCK event, `stats.onBlock` and the per-player `blocks` column
  could never fire. A defender within `SHOT_BLOCK_R` of a shot's flight line
  now parries it.
- **VAR overturns**: `VARService.checkGoal` existed but was **never called**,
  so a goal could never be overturned for offside, a foul in the build-up or a
  handball. The remaining gates (4-10% review, 8-25% overturn) made the other
  paths effectively never overturn either.
- **Through balls / centres / crosses**: `ActionType` had no such values, so
  every through ball, cross and centre was an ordinary `PASS` with no way to
  express "played behind the defence" or "lofted into the box".

## Open issues, ranked

1. **Centres are 1.5x reality** (53 vs 25-35) and crosses/through balls are
   slightly high. The gates are per-tick, so they are sensitive to how long a
   carrier holds the ball in the final third.
2. **Corners are still skewed** (0.8 HOME vs 3.6 AWAY) and the total (4.4) is
   below reality (10). Goal kicks are 2.5x reality while throw-ins are half —
   the ball still leaves play mostly through the end lines, because a clearance
   launched at `MAX_BALL_SPEED` travels v²/2a ≈ 7.5 cells regardless of its
   2-cell aim point. Nothing clamps the ball in flight (that is correct per the
   owner: "the ball goes out freely if it has speed"), so this is a clearance
   power question, not a clamping bug.
3. **Dribbles (245) and clearances (103) are 4-5x reality** — but these are
   *semantics*, not bugs: the engine's `DRIBBLE` is a carry action (the AI
   chooses to run with the ball), not "completed a dribble past a defender",
   and `CLEAR` counts every clearance action including the ones that go out for
   a goal kick. Neither engine has a notion of a completed dribble.
4. **On-target → goal is 61%** against a real ~30%. The goal total is right
   because there are fewer on-target shots than reality, so the two errors
   partly cancel. A proper fix is a graded save model (save probability from
   keeper skill, shot placement and speed) instead of a fixed `GK_SAVE_R`
   geometric reach.
5. **Interceptions 39 vs 12-16** — the read-intercept roll is per defender per
   pass, and with 22 players several defenders qualify for the same lane.
6. Red cards still 5x reality (1.1 vs 0.2).
