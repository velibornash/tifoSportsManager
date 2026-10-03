# kanbanProgress.md — the append-only log

**One entry per task, newest first, each carrying the commit that landed it.**

This file holds no plan and no board — that is `kanban.md`. It holds **what was actually done, what was
measured, and what did not work.** The failures are the point: a measured dead end is worth more than a
silent one, because the next session will otherwise try it again.

**A baseline is only comparable to a baseline measured the same way.** The Maven `[ERROR]` summary prints
at the *end* of a run, so a killed run reports a different set of failures than one allowed to finish. Two
numbers are only comparable if both were allowed to finish.

**A green status is not evidence.** The recurring failure in this codebase is code that reports success
while doing nothing. Where a test passed suspiciously, the entry below says whether it was broken
deliberately to check.

---

## 2026-10-03 — P1-1: four indexes on `match`, and two of the board's three claims refuted

**The board asked for three indexes. Two are wrong and one is irrelevant. Four unlisted ones are the
real win.** Every number below was measured on a throwaway database holding a **full projected season**,
because the dev database cannot measure this at all: it holds **155** `match` rows, which is one
matchday of one country, and a sequential scan of 155 rows is the *correct* plan. An `EXPLAIN` there
proves nothing either way.

### The harness, and the scale it derives rather than assumes

Scale came out of the live dev database, not out of the board's estimate:

| measured on `sokker_db` | |
|---|---|
| competitions | 31, all leagues |
| fixtures per matchday | 310, of which **155** played |
| matches played, in total | **155** — one matchday, ever |
| matchday spacing | **7 days** (2026-10-03, -10, -17 …) |
| weeks in a season | 12 |
| zone-load rows per match | 198 (22 players × 9 zones) |

So **full scale = 48 × 155 = 7,440 matches a matchday, 89,280 a season**, and `player_zone_load` gains
**17,677,440 rows a season**. The harness holds exactly that: 89,280 matches, 17,677,440 zone loads,
2.8 GB. Foreign keys to `competition`/`team`/`player`/`lineup`/`stadium` are dropped — no query under test
joins them, and their absence cannot change a plan.

**Measurement discipline.** The machine is shared with three other agents' work, so **every figure is the
minimum of three or five runs**, never a mean. This is not fastidiousness: the same query measured 4,441 ms
and 8,338 ms on different runs, and an early single-run reading convinced me an index I later dropped had
saved 154 ms. It had not.

### What was landed

| Query | Where it runs | Before | After | Index |
|---|---|---:|---:|---|
| `findByCompetitionIdAndSeasonYear` | top scorers / assists, **request path** | 158.7 ms | **0.19 ms** | `ix_match_competition_season` |
| `findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc` | club match history, **request path** | 170.3 ms | **0.26 ms** | `ix_match_home_team_date` + `ix_match_away_team_date` |
| `findByHomeTeamIdOrAwayTeamId` | club page, **request path** | 140.6 ms | **0.20 ms** | the same two |
| `findBySeasonYearAndWeekNumber` | `GoalEventRepository`, 12× a season job | 156.4 ms | **27.8 ms** | `ix_match_season_week` |

**Every one of those four was a sequential scan of `match`, and three of the four are on a request path
the manager loads to look at his own club.** None of them was on the board. The board's `match` claim was
about the daily recovery job, which is one query a day, while these are one per page view.

The plans, before and after:

```
-- findByCompetitionIdAndSeasonYear, 60 rows out of 89,280
 Seq Scan on match                                     Execution Time: 158.714 ms
 Index Scan using ix_match_competition_season on match  Execution Time:   0.276 ms

-- findByHomeTeamIdOrAwayTeamId...PlayedTrue...OrderByMatchDateDesc, 12 rows out of 89,280
 Sort  (actual time=57.174..57.177 rows=12)
   ->  Seq Scan on match  (actual time=29.662..57.128 rows=12)     Execution Time:  57.199 ms

 Sort  (Sort Key: match_date DESC, Sort Method: quicksort  Memory: 42kB)   Execution Time: 0.176 ms
   ->  Bitmap Heap Scan on match  (actual time=0.066..0.073 rows=12)
         ->  BitmapOr
               ->  Bitmap Index Scan on ix_match_home_team_date
               ->  Bitmap Index Scan on ix_match_away_team_date
```

**Column order, stated per index as the board asked:**

- `(competition_id, season_year)` — both equality, so the order between them is free; `competition_id`
  leads because it is the selective one and the usual way in.
- `(season_year, week_number)` — **cannot** be folded into the index above. That query has no
  `competition_id`, and a season is 89,280 rows, so leading on `season_year` alone is not selective.
- `(home_team_id, match_date)` and `(away_team_id, match_date)` — a predicate of
  `home = ? OR away = ?` needs an index on **each** side; one alone cannot be used for the OR at all.
  `match_date` trails so the same index serves the history page's `ORDER BY match_date DESC`.

### Write cost, measured

| 20,000-row insert into identical clones | Per row |
|---|---:|
| no new indexes | 25.7 µs |
| the four new indexes | 42.5 µs |

**+16.8 µs per match row** — about 4 µs per btree, which is what four of them should cost. At 7,440
matches a matchday that is **+125 ms once a matchday**, against ~130 ms saved on *each* of the request
paths above, and 24 MB of index per 89,280 matches.

**A first attempt at this measurement said +3.2 ms per row, a hundred times worse, and it was wrong**:
one 200,000-row transaction on a machine running three other agents. The 4 µs-per-btree figure is the one
that survives an independent method. Recorded because the wrong number was nearly the reason to drop all four.

### Refuted 1 — `match_tick_states`. Nothing writes it. Nothing reads it.

Its only writer is `MatchPersistenceService`, and that has **zero callers** in `src/main`: the only
references are its own javadoc, a comment in `ResetService`, and `ReservedWordColumnTest`, which reflects
on a field. `findByMatchOrderByTickAsc` and `deleteByMatch` have no callers at all. The table holds
**0 rows**.

**The replay path is `SimReplayStore`: one JSON file per replay under `app.replay-dir`, with its own
bounded retention by count and by `app.replay.max-age-days`.** The design that P1-1 and P1-5 describe —
900 rows a match, two JSON blobs each — was replaced by files.

So no `(match_id, tick)` index was created, **P1-5's growth premise is zero, not 900 a match**, and this
settles **P0-8 §1.4** and **P0-12 §4.4** without an owner decision. Deleting the table and the service is
an owner call and I have not touched them.

### Refuted 2 — `player_zone_load(match_id)`. No caller, and it makes things worse.

The board's reasoning was sound and its premise was not: `findByMatchId` exists, the unique index leads
with `player_id`, so the query cannot use it. But **`PlayerZoneLoadRepository.findByMatchId` has no caller
in `src/main`.** One test uses it. The 629 ms → 0.29 ms I measured is a test's cost, once.

And landing it anyway would have made the daily job **68% slower**:

| `findLoadMinutesPlayedSince`, one matchday window | |
|---|---:|
| with `ix_zone_load_match` | 7,436 ms |
| without it | **4,441 ms** |

The query returns 1,473,120 rows from a 17,677,440-row table. With the index available the planner picks
a plan it likes less:

```
 Hash Join  (actual time=115.513..5222.538 rows=1473120)
   ->  Seq Scan on player_zone_load load  (actual time=0.055..2501.989 rows=17677440)
```

Forced onto the index it is nearly six times faster than either:

```
 Gather  (actual time=6.288..694.455 rows=2946240)
   ->  Nested Loop
         ->  Index Only Scan using ix_match_played_date_id on match  (Heap Fetches: 0)
         ->  Index Scan using ix_zone_load_match  (actual time=0.012..0.067 rows=198 loops=14880)
   Execution Time: 888.693 ms
```

**So the index is worth having for this query — it just cannot be landed before the read is paged**, which
is P1-3's work. Landing it alone would have shipped a slower daily job.

### Refuted 3 — "every zone-load recovery query is a seq scan on `match`" is true and irrelevant

That scan costs **112 ms of a 5,318 ms query: 2%.** Indexing `match(match_date)` changed the total by less
than the run-to-run noise, so no such index was created. I proposed it, measured it, and dropped it.

| also measured and dropped | |
|---|---:|
| `ix_match_played_date_id (played, match_date, id)` for the Elo replay | 421 ms → **449 ms**. No gain. An early single run said 291 → 137 ms; that was noise. |
| `ix_match_date (match_date)` | no measurable change on any query |

### The guard

`MatchIndexDeclarationTest` — 4 tests, 89 ms, no database. It pins the four names **and their column
order**, because an index on the right columns in the wrong order is the same as no index, and this
repository already contains that mistake. It also asserts the **count**, because a fifth index is a write
tax on every match row and should have to break a test on purpose.

**Proven able to fail, twice:**

1. Swapping `competition_id, season_year` → fails, printing both orders.
2. Adding an unmeasured fifth index → **two** tests fail, one naming the drift between the entity and
   `tools/create-match-indexes.sql`.

### Two files, two jobs

The indexes are declared on the `Match` entity, because both profiles run `ddl-auto=update` and the schema
should be readable from the code. `tools/create-match-indexes.sql` states the same four as
`CREATE INDEX CONCURRENTLY` for a database that already has rows, because `ddl-auto` issues a plain
`CREATE INDEX` that **holds a write lock for the length of the build** — instant on 155 rows, not instant
on 89,280 and growing. The fourth test keeps the two from drifting.

**Verified in the database, not asserted:** run against `sokker_db`, and `pg_indexes` afterwards shows all
four alongside `match_pkey`.

### Left standing for the next session

- **`sokker_bench` still exists** — the harness, for P1-3 and P1-5. Drop it when P1 is finished.
- The recovery query's own planner statistics are wrong: `n_distinct` on `player_zone_load.match_id` reads
  **31,004** against **89,280** distinct values, so the planner predicts 569 rows per match instead of 198.
  `SET STATISTICS 1000` fixes the estimate and does **not** change the plan. Recorded, not landed — it is
  not expressible in `@Index`, and on its own it buys nothing.

---

## 2026-10-03 — restructuring the documentation, and a P1 index finding

### The board is rebuilt into P0 / P1 / P2

The root had four generations of planning documents, several actively contradicting each other. **Twelve
files moved to `archive/`** with a README explaining what each one is and why it is no longer the truth.

Every task in the new `kanban.md` is written to be executable from a blank session: what the defect is,
where it is, and **exit criteria as a checklist**. The general information a new session needs — the
exports, the database, the traps, the scale, the three rules — is above the task list rather than
alongside it.

**Two documentation contradictions were found and confirmed in source while doing it:**

1. The old board listed **`nationalCup()` returns the lowest-id domestic cup** as *owner-ruled but not
   implemented*. **It is implemented** — `CupFixtureSeeder.java:283` now calls
   `findFirstNationalScoped(CUP, INTERNATIONAL, limit 1)`. B2 closed it and the board never caught up.
2. A method called **`nationalCup()` queries `CompetitionScope.INTERNATIONAL`**, and lives in a class that
   also seeds national cups. The name and the scope disagree. Recorded as **P0-11**.

Also confirmed still open, against the source rather than the board: **`findTier2Leagues()` is hardcoded to
`"SRB"`** (`SeasonService.java:1094`), so `buildPlayoffSummary` reports nothing for the other 47 countries.

### Three hot tables have no usable index — read out of `pg_indexes`, not inferred

> **Superseded by the P1-1 entry above, which measured all three.** Two had no query behind them and one
> was 2% of the query it was blamed for. Kept as written: reading `pg_indexes` is how the candidates were
> found, and it is a good way to find candidates. It is not a way to know what a query costs.

Checked while writing P1, and better than expected:

| Table | Indexes that exist | Consequence |
|---|---|---|
| `match` | **primary key only** | `findLoadsPlayedSince` joins to `match` and filters `m.matchDate > :after`. Every zone-load recovery query is a **seq scan on `match`** |
| `match_tick_states` | **primary key only** | `findByMatchOrderByTickAsc` filters `match_id` and orders by `tick`. Replay path, ~900 new rows per match |
| `player_zone_load` | `ix_zone_load_player(player_id)`, unique `(player_id, match_id, zone)` | `findByMatchId` exists, but **the unique index leads with `player_id`**, so a query on `match_id` alone cannot use it. Seq scan on the largest table in the schema |

**Note the third one carefully: the index exists, and still does not serve the query.** An index on the
right columns in the wrong order is the same as no index. That is why P1-1 requires the column order to be
justified per index rather than listed.

**P0-12 §4.4's warning is not thereby answered.** It says *"do not index it as-is"*; a targeted
`(match_id, tick)` index is a different proposal from blind indexing, and P1-1 has to address that rather
than assume it.

Also recorded: the other three sports have **zero** `@Index` declarations (P1-6), and neither `match` nor
`match_tick_states` has any retention policy (P1-5).

### Suite status when this was written

A full `mvn test` was in flight from 18:03. At the 80-class mark I read it as **stalled and said so — and
that was wrong**: the log was live and the class was mid-run seeding a Croatian pyramid. A stalled suite
and a slow class look identical from the outside; **the log mtime is the check**, not the class count.

The three heavy classes — `PromotionLadderTest` (321 s), `CountryActivationTest` (273 s) and the two clock
classes — are the recorded reason a full run takes ~2 h 52 m. Its result is appended below when it lands.

---

## 2026-10-03 — the weekly rollover was priced for a village

**Found by a thread dump, not by reading the code.** A full-suite run printed nothing for two and a half
hours and it was natural to assume it was wedged. It was not: `main` had **15,343 seconds of CPU** and
7.4 GB resident, and the stack said exactly where it was.

```
GameClockService.advanceHours -> WeekRolloverJob
  -> TransferService.simulateWeeklyMarketActivity -> maybeCreateIncomingOffer
  -> needsInterest -> ClubNeedService.interest -> ClubNeedService.clubSquad
```

`clubSquad` runs `players.findByTeamId(club.getId())` — one query per call. The shape is a **cross
product**: for each listed player the market asks every club whether it is interested, and each answer
loaded that club's squad. Then `weightedBuyer` asked again for every willing buyer, and `valuation` a
third time.

| | before | after |
|---|---:|---:|
| per-club squad reads (30 clubs, 3 listings) | 180 | **0** |
| bulk squad reads | 0 | **1** |

Three decisions worth keeping:

- **`null` means "load it yourself", an empty list means "this club has nobody."** Both are real answers.
  Collapsing them silently re-introduces the query per call, because an absent key is a fact, not a gap.
- **The squad is resolved _after_ the early guards.** A club has no interest in its own player, and
  answering that without touching the database is the point of the early returns.
- **One snapshot per pass is correct, not merely cheaper.** Nothing in the pass completes a transfer — a
  bid is only recorded — so there is no mid-pass player move for the snapshot to miss.

`weightedBuyer` also computed each buyer's appetite **twice**, once to sum and once while walking. And an
`if/else` had **two identical arms**, so the human-managed check decided nothing.

#### The guard test that passed while measuring nothing

Worth recording because **both** failures were mine:

1. A first attempt used `@SpyBean` in a Spring test. It **skipped** on the empty H2 database and reported
   green. Rewritten as a pure mocked unit test — milliseconds, exact counts.
2. The rewritten version still passed on the broken code. The market gates on
   `nextRandomDouble() > 0.68`, so unpinned **the test did nothing, read zero squads, and passed** —
   because a budget of thirty is satisfied by zero. `nextRandomDouble()` is `protected`, so a test subclass
   pins it.

The guard now asserts **exact** counts — `0` per-club and exactly `1` bulk — plus a second case asserting
two identical passes read the same total, so accumulated state would fail it. It failed at **180** before
the fix.

#### Not mine

`TransferCompletionTest` (3 errors) and `OmladinacTransferJourneyTest` (6 errors) both fail in `setUp`
with `NoSuchElementException` on an empty test database. **Verified red beforehand by stashing the change
and re-running**, rather than assumed.

---

## 2026-10-03 — a regression I introduced, found by reading and not by a test

In `abef6a2`, making a club's formation decide its own role keys made something newly reachable: **a role
the rules do not name.** Until then every player in the world wore one of 4-4-2's eleven and the rules were
4-4-2, so it could not happen.

Both miss paths answered with a hardcoded `new Position(1.5, 3.5)` — **the goalkeeper's own-half corner**. So
in any mixed-formation match, every unmentioned outfielder of one side was sent to **the same square metre
of pitch**: a wall of players, and unreadable replays.

Null could not simply propagate — `TacticalIntentEngine` does `p.setTarget(desired)` and a
`SimUtils.distance(desired, …)` immediately after — so all three `RestartManager` sites and the tick loop
needed a guard.

**The contract now: "no rule and no anchor" answers null, and every caller falls back to the player's own
position**, which `RealSquadFactory` has already placed from his own formation's anchors. A player whose
role the current tactic does not mention holds his shape, which is what a manager who has not authored a
rule for him would expect.

`UnnamedRoleFallsBackTest` 3/3, proven able to fail by restoring the hardcoded cell, which printed it:

```
a 4-3-3 holding midfielder asked a 4-4-2's rules and got an answer.
  It used to get (1.5, 3.5) ... expected: <null> but was: <(1.50,3.50)>
```

The third case asserts the two vocabularies **must differ**, so the miss path is genuinely exercised
rather than quietly satisfied.

**No test found this one. Reading the code the change touched did.** It is now P0-3.

---

## 2026-10-03 — tactics reach the engine, and step 4 closed with no code change

**The seam.** `TacticsRulesProvider` loads the home club's profile and `SimMatchService` passes it through
the `MatchOrchestrator(state, tactics)` constructor that previously had zero callers. `8b3dff1`.

**Verified live**, not asserted: the app logged
`OFK Omladinac (1) plays its own 4-4-2 tactics: 1012 rules from the tactical editor`.

**Restore.** Reset now reads `var/tactics-editor-profiles.json` and names the profiles it cannot place rather
than failing silently. `d4ccfde`.

**Formations.** `RealSquadFactory` derives role order and anchors from `FormationSlotCatalog`, so a 4-3-3
profile actually plays. `abef6a2` — and the source of the regression above.

**Step 4 — the owner's answer was that nothing should change.** `WE_HAVE_BALL` and `OPPONENT_HAS_BALL` are
identical in every saved profile, **on purpose**: the owner has not decided whether to keep both variants.
So `mirrorWeHaveBallRules` is a **decision, not a defect**, and `DefensiveShape` is load-bearing rather
than decorative. I had recommended letting the editor author defence properly, which would have pre-empted
a decision explicitly not made.

**Multi-tactics was specified before any code**, because this feature has been specified wrong twice before
and one part of it is still undecided: 20 tactics per team, one default, up to 3 per match switched by
minute and score (`ALWAYS`, `FROM_MINUTE`, `WE_LEAD_BY_1`, `WE_LEAD_BY_3_PLUS`, `WE_ARE_DOWN_BY_1`,
`WE_ARE_DOWN_BY_3_PLUS`, `DRAWING`; the `...` is modelled as data, not a `switch`). **It is not a small
change:** the engine's tactics are immutable per match, and whether the storage is keyed by possession
context depends on **P0-4**.

---

## Earlier history

Everything before 2026-10-03 is in **`archive/kanbanProgress.md`**, including:

- the full-suite run that was allowed to finish — **992 tests, 13 failures, 16 errors, 29 red**, and the
  complete list of what each red class means
- why a full run takes ~2 h 52 m, and that it is D3 reproduced inside the suite
- the D1 query fixes with their measurements
- the four separate "green but did nothing" bugs, and the three tests that were green while measuring
  nothing before they were fixed

That file is the most valuable document in the repository for anyone about to change scheduling, seeding or
the match engine.