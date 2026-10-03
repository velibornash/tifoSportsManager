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