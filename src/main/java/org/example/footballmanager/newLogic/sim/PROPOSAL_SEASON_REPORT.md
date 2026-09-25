# PROPOSAL_SEASON_REPORT.md — newLogic/sim

**Per-team averages over 200 deterministically seeded matches** (seeds 42-241),
produced by `ProposalSeasonDiag 200 42`. Every number is read from the engine's
own counters (`TeamStats`, per-player stats, penalty counters) or counted from
the recorder's typed event stream — nothing is estimated. A metric the engine
does not track is reported as such rather than invented.

Reproduce:
```bash
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=org.example.footballmanager.newLogic.sim.ProposalSeasonDiag \
  -Dexec.args="200 42"
```

Last run: 2026-09-25, after the shot-volume calibration and the HOME/AWAY mirror fixes.

## Per-team averages

| Metric | HOME | AWAY | Both | Real football (PL) | Verdict |
|---|---|---|---|---|---|
| Goals | 2.4 | 2.6 | **4.9** | 2.7 | ✅ |
| Shots | 11.4 | 12.2 | **23.6** | 25 | ✅ (target was ~25) |
| Shots on target | 4.5 | 4.6 | **9.1** | 8-9 | ✅ |
| Shots missed | 6.2 | 7.0 | 13.2 | ~14 | ✅ |
| On-target rate | 40.5% | 37.6% | 39% | 33% | ✅ |
| Saves | 2.3 | 2.7 | 5.0 | 3-4 | ✅ |
| Blocks | 0.0 | 0.0 | 0.0 | 2-4 | ❌ defenders never block |
| On-target → goal | 53.3% | 55.1% | 54% | ~30% | ⚠ high, but goal total is right |
| Passes attempted | 288.3 | 184.6 | 472.9 | 450-500 | ⚠ **sides differ by 56%** |
| Passes completed | 251.2 | 155.9 | 407.1 | 320-360 | ⚠ see possession |
| Pass accuracy | 87.0% | 84.2% | 85.6% | 80-86% | ✅ total, ⚠ sides differ |
| Dribbles | 118.5 | 128.5 | 246.9 | 40-60 | ❌ 4-5x too many |
| Clearances | 65.5 | 68.3 | 133.8 | 20-30 | ❌ 4-5x too many |
| Through balls | — | — | 0.0 | 5-10 | ⚠ **not tracked** |
| Centres | — | — | 0.0 | 25-35 | ⚠ **not tracked** |
| Crosses | — | — | 0.0 | 15-25 | ⚠ **not tracked** |
| Duels won | 250.2 | 246.8 | 497.0 | (n/a) | ⚠ very high |
| Tackles | 246.8 | 250.2 | 497.0 | (n/a) | ⚠ identical to duels won |
| Interceptions | 14.8 | 16.6 | 31.4 | 12-16 | ⚠ 2x too many |
| Deflections | 33.9 | 28.1 | 62.0 | (lane-contact model) | ⚠ high |
| Corners | 0.3 | **9.2** | 9.5 | 10 | ⚠ total right, **sides badly skewed** |
| Goal kicks | **37.0** | 21.7 | 58.7 | 12-15 | ❌ 4x too many |
| Throw-ins | 9.2 | 11.0 | 20.1 | 35-45 | ❌ 2x too few |
| Offsides | 0.0 | 0.0 | 0.0 | 2-4 | ❌ none are ever flagged |
| Fouls | 12.8 | 15.0 | 27.9 | 22 | ⚠ high |
| Yellow cards | 3.3 | 3.9 | 7.2 | 3-4 | ❌ 2x too many |
| Red cards | 1.1 | 1.6 | 2.7 | 0.2 | ❌ 13x too many |
| Penalties awarded | 1.8 | 2.1 | 3.9 | 0.27 | ❌ 15x too many |
| VAR reviews | 1.1 | 1.1 | 2.3 | 1-3 | ✅ |
| VAR confirmed | 1.1 | 1.1 | 2.2 | — | ✅ |
| VAR overturned | 0.0 | 0.0 | 0.1 | — | ⚠ almost never overturned |
| Possession | 56.3% | 43.7% | 100% | 50/50 | ⚠ HOME still edges it |

## Results

| | Count |
|---|---|
| HOME wins | 80 |
| AWAY wins | 94 |
| Draws | 26 |
| 0-0 | 0 |
| Highest score in the sample | 11 goals |

## What is balanced

Goals, shots, shots on target and results are now effectively symmetric
(2.4/2.6, 11.4/12.2, 4.5/4.6, 80/94). That took six mirror fixes — see
`backlog.md` §P0.

## Open issues, ranked

1. **Pass volume differs by 56% between the sides** (288 vs 185) and possession
   is 56/44. Everything about chance creation is balanced, so the ball spends
   more time in the HOME half — which is also why HOME takes 37 goal kicks to
   AWAY's 22 while AWAY takes 9.2 corners to HOME's 0.3. This is the last
   territorial asymmetry and it is upstream of the shot numbers.
2. **Discipline is an order of magnitude too high**: 2.7 reds and 3.9 penalties
   per match. A penalty almost always becomes a goal from 0.8 cells, which feeds
   the scoring total directly.
3. **Zero offsides.** After the 0.2-cell tolerance fix and the decision-level
   offside filter, no forward pass is ever flagged. Real matches have 2-4. The
   filter is now too effective and the ball is probably played backwards too
   often as a side effect.
4. **Dribbles and clearances are 4-5x too high** (247 and 134 per match), which
   inflates the action count and is consistent with 497 duels per match.
5. **Blocks are always zero** — no shot is ever parried by an outfield defender.
6. **Through balls, centres and crosses are not modelled at all** — `ActionType`
   has no such values, so every through ball and cross is an ordinary PASS. If
   these are wanted as distinct actions and stats, `ActionType` needs extending
   (a real feature, not a calibration task).
7. **Throw-ins are half as frequent as reality** while goal kicks are 4x — the
   ball leaves play mostly through the end lines, not the touchlines.
