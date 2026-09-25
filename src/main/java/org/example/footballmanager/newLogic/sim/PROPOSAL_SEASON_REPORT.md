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
| Goals | 2.4 | 2.8 | **5.2** | 2.7 | ✅ (owner target: up to 7) |
| Shots | 13.4 | 14.8 | **28.2** | 25 | ✅ (target was ~25) |
| Shots on target | 5.9 | 6.2 | 12.1 | 8-9 | ⚠ ~1.4x |
| On-target rate | 43.6% | 41.7% | 43% | 33% | ⚠ high |
| Shots missed | 2.9 | 3.4 | 6.3 | ~14 | ⚠ low |
| **Saves** | 4.7 | 4.8 | **9.4** | 3-4 | ⚠ 2.5x |
| Blocks | 3.5 | 2.9 | 6.4 | 2-4 | ⚠ 2x |
| **On-target → goal** | 42.1% | 45.5% | **44%** | ~30% | ⚠ close |
| Passes attempted | 299.2 | 279.5 | 578.7 | 450-500 | ⚠ high |
| Passes completed | 236.8 | 214.4 | 451.2 | 320-360 | ⚠ high |
| Pass accuracy | 79.0% | 76.7% | 77.9% | 80-86% | ⚠ slightly low |
| Through balls | 5.6 | 6.1 | 11.7 | 5-10 | ✅ |
| Crosses | 9.0 | 10.1 | 19.1 | 15-25 | ✅ |
| Centres | 21.7 | 18.7 | 40.5 | 25-35 | ✅ |
| Dribbles | 123.1 | 125.5 | 248.5 | 40-60 | ⚠ semantics, see below |
| Clearances | 43.1 | 48.6 | 91.7 | 20-30 | ⚠ semantics, see below |
| Duels won | 294.7 | 306.5 | 601.2 | (n/a) | ⚠ high |
| Tackles | 306.5 | 294.7 | 601.2 | (n/a) | ⚠ identical to duels won |
| Interceptions | 20.4 | 15.6 | 36.0 | 12-16 | ⚠ 2x |
| Deflections | 36.6 | 28.2 | 64.8 | (lane-contact model) | ⚠ high |
| Corners | 0.8 | 4.3 | 5.1 | 10 | ⚠ low, still skewed |
| Goal kicks | 21.4 | 20.5 | 41.9 | 12-15 | ⚠ 3x |
| Throw-ins | 9.0 | 8.6 | 17.6 | 35-45 | ⚠ half — see below |
| Offsides | 1.7 | 1.9 | 3.6 | 2-4 | ✅ |
| Fouls | 14.1 | 15.5 | 29.6 | 22 | ⚠ high |
| Yellow cards | 2.4 | 2.6 | 5.0 | 4-5 | ✅ |
| Red cards | 0.4 | 0.6 | 1.0 | 0.2 | ⚠ 5x |
| Penalties | 0.1 | 0.1 | 0.2 | 0.27 | ✅ |
| VAR reviews | 1.4 | 1.4 | 2.9 | 1-3 | ✅ |
| VAR confirmed | 1.1 | 1.1 | 2.2 | — | ✅ |
| VAR overturned | 0.3 | 0.3 | 0.6 | 0.2-0.5 | ✅ |
| Possession | 49.5% | 50.5% | 100% | 50/50 | ✅ |

## Results

| | Count |
|---|---|
| HOME wins | 67 |
| AWAY wins | 94 |
| Draws | 39 |
| 0-0 | 0 |
| Highest score in the sample | 11 goals |

## The goalkeeper model

The keeper used to be a fixed tactical anchor who saved anything inside a hard
`GK_SAVE_R` radius. A fixed radius cannot express shot placement, shot power or
keeper skill, so the only lever on goals was shrinking or growing a circle.

He is now a real model in `engine/GoalkeeperEngine.java`, tested by
`GoalkeeperEngineTest` (11 tests):

**Positioning** — he stands on the bisector between the ball and the centre of
his goal, which is the spot that makes the shooting angle narrowest, and shades
toward the ball's side. He advances off his line as the ball approaches (0.35
cells when it is far, ~1.5 cells when it is close) and rushes out for a genuine
one-on-one inside the box. He can never advance past the ball itself, and is
clamped to the goal-mouth span plus a margin. Verified in the log: saves now
happen from row 1.4 (on his line) out to row 7.7, at columns 3.7-4.2, instead of
always from one fixed spot.

**Saving** — graded, not geometric:

| Factor | Effect |
|---|---|
| Keeper skill | widens his reach (0.34 + skill/20 x 0.32 cells) and his hands (0.70 + skill/20 x 0.50) |
| Ball speed | a maximum-power shot removes 40% of his reach — under a tick to set himself |
| Placement | the chance of holding it falls off with the cube of the distance from his body, so a shot at the edge of his reach is far harder than one at his chest |

On-target shots now also spread across the **whole** goal mouth instead of
clustering within ±0.3 of the centre, which is exactly where a keeper stands.
That was a second reason nothing beat him: the aim was pre-loaded into his hands.

He also moves like a keeper: `MovementEngine` applies a
`GOALKEEPER_MOVEMENT_FACTOR` of 1.9, because a keeper's pace value describes his
ability to shuffle laterally in a set position, not his outfield speed — at the
generic pace cap he was always late to a ball served into the far corner.

Effect on the numbers: on-target → goal went 61% → **44%** (real ~30%), and
shots on target per match 11.5 → 12.1 with goals 7.0 → 5.2.

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

1. **Shots on target are 43% of shots** against a real 33%, so the keeper faces
   12.1 shots on frame instead of 8-9, and his 9.4 saves are 2.5x reality as a
   result. Lowering `ExecutionQuality`'s on-target probability is the direct fix;
   the keeper model itself is now behaving.
2. **Pass accuracy dipped to 77.9%** (was 80-86%) because the new THRU/CENTER/
   CROSS deliveries are lofted, harder to control passes. Worth watching.
3. **Corners are still skewed** (0.8 HOME vs 4.3 AWAY) and the total (5.1) is
   below reality (10). Goal kicks are 3x reality while throw-ins are half — the
   ball still leaves play mostly through the end lines, because a clearance
   launched at `MAX_BALL_SPEED` travels v²/2a ≈ 7.5 cells regardless of its
   2-cell aim point. Nothing clamps the ball in flight (that is correct per the
   owner: "the ball goes out freely if it has speed"), so this is a clearance
   power question, not a clamping bug.
4. **Dribbles (249) and clearances (92) are 4-5x reality** — *semantics*, not
   bugs: the engine's `DRIBBLE` is a carry action (the AI choosing to run with
   the ball), not "completed a dribble past a defender", and `CLEAR` counts
   every clearance including the ones that go out. The engine has no notion of a
   completed dribble.
5. **Interceptions 36 vs 12-16** — the read-intercept roll is per defender per
   pass, and with 22 players several defenders qualify for the same lane.
6. Red cards still 5x reality (1.0 vs 0.2).
