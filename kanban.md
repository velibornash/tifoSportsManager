# kanban.md — the board

**Read this file first. Then `kanbanProgress.md` for what has already been tried.**

Everything still open is here, in three categories:

| | Category | What belongs there |
|---|---|---|
| **P0** | **Correctness, security, and anything blocked on the owner** | Defects that make the product wrong or unsafe, plus every task waiting on an owner decision. P0 is ordered by *leverage*, not by cluster. |
| **P1** | **Performance** | Reads and writes that get slower as the world grows. Every task here must state a **measurement**, not an opinion. |
| **P2** | **Features and visual work** | Anything that can be completed on its own without a decision from anyone. Includes the other sports. |

---

# 📌 General information — read before taking any task

## The three rules

**1. A green status is not evidence.** The recurring failure shape in this codebase is code that reports
success while doing nothing. Four separate bugs had it in one session: a seeding job that caught its own
failure and returned normally, a pyramid builder that gave up before the first league and reported a
clean rebuild, a Back button with a `dataset` and no listener, and an `escapeHtml` used in a `catch` and
never imported. **Three of the four could only be found by opening the app.**

**2. A job is not done until it has been seen to change data.** Not logged as done — observed in the
database afterwards. Never hand back a half-built world: every seeding path must end in a world that
passes the integrity check.

**3. A test that cannot fail proves less than no test.** When you write a guard, **break the code
deliberately and watch it fail.** Three tests in this repository were green while measuring nothing
before they were fixed.

## Environment

```bash
# The agent shell does NOT inherit the owner's exports. Run this first, every session.
export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:$PATH"
```

| | |
|---|---|
| Build | `mvn clean package -DskipTests` |
| Run locally | `./run-app.sh` — **never** `mvn spring-boot:run` directly |
| One test class | `mvn test -Dtest=RatingEngineTest` |
| Local database | PostgreSQL, db `sokker_db`, user `postgres`, password `stojke` |
| App login | `velibor@example.com` / `A12345!` |
| Browser launch | `./run-app.sh` pops open a browser on every start. `--app.open-browser=false` opts out; **every shell start must use it** |

**A full `mvn test` takes ~2 h 52 m and needs the application running on `:8080`.** Three Playwright
classes otherwise hang the entire run — they wait, they do not fail. Budget for it; never start it on the
way to something else. A full-suite run only counts if it was allowed to finish.

## Working rules

- **Answer in English.** The owner writes in Serbian; every reply, message, commit and code comment is in
  English. This is not a preference to be revisited.
- **Commit to `main`** after each task. Stage only the files that task intended to change — check
  `git status` for untracked files that were already there.
- **After each task:** update `kanban.md` (mark it done, move it to the log) and `kanbanProgress.md`
  (append one entry carrying the commit hash), then commit.
- **Substantive findings go in `kanbanProgress.md` even when the task fails.** A measured dead end is
  worth more than a silent one.
- **When you find a defect that is not your task: fix it or report it — do not walk past it.** Record it
  in `kanbanProgress.md` with what it was and whether it is fixed.
- **Do not delete a task to make the board look tidy.** Mark it done, or record why it was dropped.

## Where things live

| Path | What |
|---|---|
| `src/main/java/org/example/footballmanager/newLogic/` | **The product.** Exactly two packages: `newLogic` and `demo` |
| `demo/service/` | **Frozen reference engine.** Not the product path. Do not extend, do not port from |
| `src/main/resources/static/` | Frontend, vanilla ES6 modules |
| `archive/` | **Superseded documentation.** History only. Never work from it |
| `TECHNICAL_OVERVIEW.md` | What the system actually is today |
| `AGENTS.md` | Repo conventions and the working rules above |

## Traps that have cost real time

- **`Team.reputation` is the economy's 0–100 scale, not an Elo.** Eight services read it, four clamp it.
  Club Elo lives in `eloRating` / `eloPreviousRating` / `eloDelta`. `Country.reputation` is a *different*
  scale on a column of the same name.
- **`PyramidBuilder` never sets `Team.type`**, so "is this a club?" cannot be answered from that flag. A
  club is anything whose `competition` is a LEAGUE.
- **A season is twelve weeks counted from 1.** There is no calendar year anywhere. Any code or test
  passing 2024/2025/2026 as a season value is wrong, even if it is self-consistent and passes.
- **`use authFetch` for every authenticated frontend call**, and **always check `response.ok`**. Several
  loaders `await response.json()` without it, so a 404 escapes to the router and the page becomes a
  generic "API Error" card.
- **Boot writes nothing.** No seeding, no backfills, no repair. World building happens on admin buttons.
- **`DummyDataController` is fake data**, hardcoded to team 1, with zero DB access, and five frontend
  files still fetch it. Awaiting an owner decision. Do not wire it to anything.

## Scale — most of P1 exists because of this

48 countries × 31 divisions × 10 clubs ≈ **14,880 clubs**, 96 national sides, a promotion ladder per
country, three tiers of international club cups. The world grew ~48× in five days and the code that
schedules it was written for one country.

Measured row counts on a **Serbia-only** dev database, for scale:

| Table | Rows (Serbia only) | Notes |
|---|---:|---|
| `player_zone_load` | 18,729 | Largest table. **198 rows written per match, one at a time** |
| `player` | 10,130 | ~300k at full scale |
| `match_player_stats` | 3,410 | |
| `match_fixture` | 2,790 | |
| `team` | 406 | 14,880 at full scale |

---

# 🔴 P0 — correctness, security, owner decisions

Ordered by leverage. **P0-1 and P0-2 are the two tasks that make everything else safer to do.**

### P0-1 — Nine controllers have no tests at all, so the security surface is untested

**Why this is first:** only three tests in the whole repository exercise any controller. Five reachable
security defects were previously found by reading source, not by a failing test. Writing these tests
against the current code is how the *next* five get caught.

**Scope:** `LineupController`, `PlayerController`, `TeamController`, `UserController`, `AdminController`,
`CommunityController`, `DummyDataController`, `CompetitionController`, `StadiumController`.

**How:** copy the JWT pattern from `WorldAdvanceAuthorizationTest`. **Mock the repository _interface_, not
the injected bean** — Spring Data returns a JDK proxy that Mockito cannot wrap, and this is the single
most common way a test of this kind fails for the wrong reason.

**Exit criteria:**
- [ ] Every one of the nine has at least one test that calls it **without** a JWT and asserts 401/403
- [ ] Every world-moving or admin-only route asserts the role check, not just authentication
- [ ] At least one test per controller asserts a **successful** path, so the guard tests cannot all pass
      against a controller that returns 500 for everything
- [ ] Each new test class is proven able to fail by breaking the authorization annotation and watching it

**Not:** a coverage percentage. A test that asserts 401 on a route that was always going to 401 proves
nothing about authorization.

---

### P0-2 — Six test classes are red and the owner has ruled: rewrite them to assert what the product guarantees

**Owner decision, already made:** do not make the red tests pass by changing the product to suit them.
Rewrite each to assert a guarantee the product actually makes.

`ScoutingServiceTest` is **already green, 10/10** — it is the worked example of this fix. Read it first.

**Exit criteria, per class:**
- [ ] The failure is read and the *intended* guarantee is written down in the test's javadoc
- [ ] The test asserts a **value**, not that a key exists (a test checking `rating` exists passes happily
      with it permanently null)
- [ ] The fixture builds the minimum world the guarantee needs, via `TestCountryCatalogue` (118 ms) or
      `TestPyramid.Builder` (37 s, once per JVM)
- [ ] Each rewritten class is green **alone** and green **in a full run** — several of these fail only in
      company, and a fix that only holds alone has moved the problem

---

### P0-3 — The away side plays the home club's shape

**The defect:** `SimMatchService` builds one `TacticsRules` — the home club's — and hands it to
`MatchOrchestrator`, `RestartManager` and `TacticalIntentEngine`. Since `abef6a2` a club's own formation
decides its own role keys, so **a 4-3-3 side facing a 4-4-2's rules asks about `CM`, `WL`, `WR` and
`ST`, which those rules do not name.**

**What is already done, do not redo it:** `1420306` made the miss path safe. A role the tactic does not
name now returns null, and every caller falls back to the player's own position, which `RealSquadFactory`
has already placed from his formation. Before that, every unmentioned outfielder was sent to the same
hardcoded cell `(1.5, 3.5)` — nine players on one square metre.

**Exit criteria:**
- [ ] Each side carries its own rules, authored in **its own perspective** — the perspective question is
      the actual design problem here, not the plumbing
- [ ] A test simulates **4-4-2 home vs 4-3-3 away** and asserts both sides are positioned from their own
      profile
- [ ] The test is proven able to fail by collapsing both sides onto one `TacticsRules` and watching it
- [ ] Replay of that match shows two different shapes

---

### P0-4 — OWNER DECISION: do the two possession contexts survive?

**This blocks the largest single piece of tactics work** and nothing else in P0.

`TeamTacticsService.mirrorWeHaveBallRules` makes `WE_HAVE_BALL` and `OPPONENT_HAS_BALL` identical on
**every save and every read**. All 506/506 out-of-possession rules in the shipped data are byte-identical
to their in-possession twins.

**The owner has not decided whether to keep both variants.** So both stay identical, and `DefensiveShape`
is **load-bearing rather than decorative** — it manufactures the defensive shape the data cannot carry.

**The decision changes the storage design**, which is why the multi-tactics work is written up in
`archive/kanban.md` and not started:

- **Keep both** → the tactic table is keyed by `(tactic, possession_context)`, roughly double the rows
- **Drop to one** → `mirrorWeHaveBallRules` and the duplication both go away, and the data model gets
  simpler than it is today

**Exit criteria:** the owner states the answer. Record it here, then the multi-tactics work in P0-5 can be
scoped.

---

### P0-5 — OWNER DECISION: four saved tactics profiles cannot be placed

Reset restores tactics from `var/tactics-editor-profiles.json`. Four of five profiles name clubs that do
not exist in the current world, because the world has five Beograd clubs and none is called "FK Beograd":

- `FK Beograd`
- `GFK Dinamo Šabac`
- `GFK Tamiš Gornji Milanovac 1901`
- `SK Čačak 1912`

**Exit criteria:** the owner either supplies the mapping, or confirms the profiles should be dropped. The
restore already reports them by name rather than failing silently, so this is cosmetic **until** it is
decided — it is P0 because it is a decision owed, not because it is breaking anything.

---

### P0-6 — OWNER DECISION: what should day 6 compute?

Day 6 is honestly empty and nobody has decided what belongs there. **Not a bug to be filled in — an
undecided design question.** Days 1 and 5 are empty for the same reason until the fixture generators are
wired to their competitions.

**Exit criteria:** the owner says what day 6 does, or says it stays empty on purpose.

---

### P0-7 — `findTier2Leagues()` is hardcoded to Serbia

`SeasonService.java:1094`. Owner has already ruled: **an accident.** It filters on
`"SRB".equalsIgnoreCase(...)`, so `buildPlayoffSummary` reports nothing for any of the other 47
countries.

**Exit criteria:**
- [ ] Scoped to the league's own country rather than a literal
- [ ] `PromotionRelegationBoundaryTest` still documents why the boundary is where it is, and is updated to
      cover a **non-Serbian** country — the existing test asserts the Serbia-only behaviour, so it must
      change with the fix
- [ ] Verified against a country that is not Serbia, not inferred

---

### P0-8 — Verify `dataFixSuggestions.md` §1.2–1.5 before touching any of it

§1.1 turned out to be **wrong**: recovery is committed, and the missing `save()` was never a lost write.
That document was produced by reading source, and **three of its findings had a configuration where the
claim "passed" while measuring nothing.**

| § | Claim | Status |
|---|---|---|
| 1.2 | "Simulate all" can silently discard an entire league | unverified — plausible, real trade-off |
| 1.3 | `@Transactional` on `totalSides()` not `seedIfMissing()` | unverified |
| 1.4 | `MatchPersistenceService` is dead code, 402 lines, zero callers | unverified — the audit agrees |
| 1.5 | `MatchEventRepository.save()` is a no-op | unverified — probably a naming choice, badly named |

**Exit criteria:** each is confirmed or refuted **by running it**, and the verdict recorded in
`kanbanProgress.md`. §1.1 is settled — do not reopen it.

---

### P0-9 — Calendar-year test fixtures pin nothing

`WeeklyFinanceServiceTest`, `StaffSponsorServiceTest` and `PlayerContractServiceTest` pass 2024/2025/2026
as `seasonYear`. A season is **twelve weeks counted from 1**; these values are self-consistent under either
scheme, so they assert nothing about the thing they name.

**Exit criteria:**
- [ ] Each fixture passes a season **number**, not a year
- [ ] Each test still passes afterwards — if one fails, the test was asserting a calendar assumption and
      that is a finding, not an obstacle

---

### P0-10 — Delete the dead tactics plumbing

Verified **zero callers** across `src/main` and `src/test`: `TacticsBridge`, `NewLogicTacticsService`,
`newLogic.model.TacticRules`, the `Formation` / `Tactics` / `MatchContext` chain, and
`tactcal_editor_positions.json` (the filename's typo is the original's). Roughly 400 lines.

**Exit criteria:**
- [ ] Caller count re-verified immediately before deleting, not copied from this board
- [ ] `mvn clean package` succeeds
- [ ] The relevant test suite still passes

---

### P0-11 — Documentation drift found while restructuring, 2026-10-03

Two concrete contradictions, both confirmed in source:

1. The old board listed **`nationalCup()` returns the lowest-id domestic cup** as *owner-ruled but not
   implemented*. **It is implemented.** `CupFixtureSeeder.java:283` now calls
   `findFirstNationalScoped(CUP, INTERNATIONAL, limit 1)`. B2 closed this; the board never caught up.
2. **A method called `nationalCup()` queries `CompetitionScope.INTERNATIONAL`.** The name says national,
   the scope says international, and it lives in a class that also seeds national cups. Rename it or
   document why the name is load-bearing.

**Exit criteria:** both resolved in code or in documentation, and the board stops disagreeing with itself.

---

### P0-12 — `dataFixSuggestions.md` §4.1 and §4.4 — OWNER-GATED

- **§4.1** — the `IDENTITY` generation type disables JDBC batching for **70 of 71 entities**, so
  `batch_size=50` is dead code.
- **§4.4** — `match_tick_states` says *"Decision needed… Do not index it as-is."*

Both are owner decisions. Note that P1-2 proposes a **targeted** index on this table, which is a different
thing from indexing it blindly — the board's warning is about the second.

---

# 🟠 P1 — performance

The world grew **48× in five days**. Everything here is about the code that was written for one country
meeting 14,880 of them.

**Every task in this category must state a before and after measurement.** A P1 task with no number is not
finished. Where a number is already known it is given here — do not re-derive it, but do re-confirm it if
the code near it has changed.

### Measured already — do not redo

| Change | Before | After | Commit |
|---|---:|---:|---|
| Daily recovery zone-load read | 21.0 ms | **10.8 ms** | `d95da9d` |
| Weekly rollover squad reads (30 clubs, 3 listings) | 180 queries | **0** + 1 bulk | `e310856` |
| Four fixture endpoints: game week used as a round | 155 fixtures for every week and day | correct matchday | `7374c69` |

---

### P1-1 — Three hot tables have no usable index. Verified against the live database, 2026-10-03

**This is the highest-value P1 task.** These were read out of `pg_indexes`, not inferred.

| Table | Indexes that exist | What needs one |
|---|---|---|
| `match` | **primary key only** | `findLoadsPlayedSince` joins to `match` and filters `m.matchDate > :after`, and the daily recovery job's entire purpose is a time window. Every zone-load recovery query is a **seq scan on `match`** |
| `match_tick_states` | **primary key only** | `MatchTickStateRepository.findByMatchOrderByTickAsc` filters `match_id`, orders by `tick`. This is the replay path, and the table grows by ~900 ticks per match |
| `player_zone_load` | `ix_zone_load_player(player_id)`, unique `(player_id, match_id, zone)` | `findByMatchId` exists, but **the unique index leads with `player_id`**, so a query on `match_id` alone cannot use it. Seq scan on the largest table in the schema |

**Exit criteria:**
- [ ] Each index proposed, and **why that column order** — an index on the right columns in the wrong order
      is the same as no index
- [ ] `EXPLAIN ANALYZE` before and after, pasted into `kanbanProgress.md`, showing the seq scan gone
- [ ] Write cost measured too. These are insert-heavy tables and an index is not free
- [ ] P0-12 §4.4's warning addressed explicitly for `match_tick_states`, not assumed away

---

### P1-2 — `ZoneLoadRecorder` writes 198 rows per match, one at a time

22 players × 9 zones, each row saved individually. There is already a unique index on
`(player_id, match_id, zone)`, so the rows are known to be distinct — the batch is safe to attempt.

**This is gated on P0-12 §4.1**: `IDENTITY` disables JDBC batching, so a batch insert may not help until
the generation type is addressed. **Establish that first** rather than writing the batch and measuring no
change.

**Exit criteria:**
- [ ] Rows-per-match and elapsed time measured before and after
- [ ] If `IDENTITY` blocks it, that is recorded and the task stops — it is P0-12's to unblock, not this
      task's to work around

---

### P1-3 — The recovery job walks every player who ever played

`findByLastPlayedAtIsNotNull()` returns the whole table, and there is no index on `last_played_at`.
`findByInjuryDaysRemainingGreaterThan` and `findBySkillsFatigueGreaterThan` are also unindexed scans, though
the second is partly served by `ix_player_fatigue_tired`.

**Exit criteria:**
- [ ] The job's real cost measured on a populated world, not extrapolated from a village
- [ ] Recovery scoped to the window it actually recovers, as `dddf462` began — finish the job rather than
      reopening it
- [ ] **Recovery still happens.** This is a correctness-adjacent task: a faster recovery that recovers
      fewer players is a new P0 defect. Verify by counting recovered players, not by the clock.

---

### P1-4 — Whole-table loads still inside loops

Nine were found in D1 and fixed (`36c4d41`, `42f5305`, `037b576`, `61bb1f3`, `7374c69`). The pattern to
hunt for is a repository call returning an entire table **inside a loop or on a request path**.

Known remaining candidates, unverified:
- `SeasonService:553` `teamRepository.findAll()`
- `SeasonService:605` `playerRepository.findAll()`
- `SeasonService:1116` `for (Competition league : competitionRepository.findAll())`
- `NationalRatingService:239` `for (Country country : countries.findAll())` — **once per international match**
- `CupFixtureSeeder:129` and `:271` `competitions.findAll().stream()`

**Exit criteria:**
- [ ] Each candidate confirmed as still present, and each **either** fixed **or** recorded as already
      resolved — the line numbers above are from an old board and have drifted
- [ ] Each fix measured, as `d95da9d` did

---

### P1-5 — `match` and `match_tick_states` grow without bound

No retention policy exists for either. `match_tick_states` gains ~900 rows per match, each carrying two
JSON blobs. At full scale this becomes the dominant table in the database within a season.

**Exit criteria:**
- [ ] Growth rate measured per simulated matchday
- [ ] A retention decision recorded — and if rows are deleted, **what still needs them** answered first.
      Replay reads them. Deleting them silently breaks the replay path

---

### P1-6 — The other three sports have no declared indexes at all

Basketball, American football and text-based football have **zero** `@Index` declarations. They are 5,580
and 3,720 players on the dev database and they are not simulated on every tick, so this is genuinely lower
priority than P1-1 — but it is not zero, and it will not get cheaper to fix after the tables grow.

**Exit criteria:** the same treatment as P1-1 — proposed indexes with stated column order, and
`EXPLAIN` evidence for the queries that matter.

---

# 🟢 P2 — features and visual work

**Everything here can be completed on its own, without a decision from anyone.** Ordered by leverage per
day of work, following `archive/COMPETITIVE_ANALYSIS.md` §10, whose ordering is deliberate.

### P2-1 — Corners decided by pitch geometry, not a nominated taker

Half a day, and easier now that P0-3 has landed. Listed as its own item in the competitive analysis for
exactly that reason.

**Exit criteria:** corner taker selected by position relative to the ball; verified on a replay.

### P2-2 — Seller chooses which offer to accept

Half a day, and nearly free: `NegotiationService.acceptOffer(transferId, offerId)` **exists and correctly
rejects the wrong offer** — no controller exposes it. Wire it, and add the owner-facing UI.

**Exit criteria:** endpoint reachable, rejects an offer for a different transfer, and a manager with two
live offers can pick one.

### P2-3 — Player can refuse to be listed — the anti-daytrade mechanic

One day. The `objection` field already exists to support it. One of the more interesting differentiators
in the analysis, because it is a mechanic neither competitor has rather than a number being bigger.

**Exit criteria:** a listed player can object; the club cannot list him without resolving it; refusal has a
visible reason.

### P2-4 — Listing fee as a percentage of the asking price

Two lines. Named as one of the cheapest wins on the board.

**Exit criteria:** fee scales with the asking price; a pittance listing costs a pittance.

### P2-5 — Supporter mood and supporter expectations

One to two days. The analysis calls it *"now the cheapest win in the meta layer, since board trust
computes but cannot act."* Trust is computed and displayed; nothing acts on it.

**Exit criteria:** mood and expectations are visible, and something in the game responds to them.

### P2-6 — Graduation caps

Half a day. *"Stops the academy flattening the economy."*

**Exit criteria:** a capped intake produces capped graduates, verified over a season rather than one run.

### P2-7 — A retirement age

One day, and the analysis calls it *"still the cheapest 1-day item on this list"* and the highest-value
item in the long game: **without it neither the pyramid nor the market can turn over.**

**Exit criteria:** players retire; squads need replacing; the effect is visible in the league table over a
season.

### P2-8 — Zero-consequence exhibition mode

One day. *"Multiplies the value of 14,880 clubs"* — it is the only feature that makes the world size an
asset rather than a cost.

**Exit criteria:** an exhibition changes no ratings, no table, no finances and no clock.

### P2-9 — Pre-match tactical preview

Three to five days, and the analysis calls it *"Hattrick's single best idea"* — it is also the cheapest way
to make P0-3's work visible on day one. `MatchController/{id}/preview` currently returns `Map.of()`.

**Exit criteria:** the preview shows **both sides'** shapes from their own profiles, so it is a real test of
P0-3 rather than a display of one club.

### P2-10 — National team qualifiers and the senior World Cup

The mechanism exists; the competitions and formats do not. This is P2 rather than P0 because nothing is
broken — it is absent.

**Exit criteria:** a full qualifying campaign and a tournament, played to a result.

### P2-11 — International club competitions

Three tiers exist with data but the calendar is thin. Depends on the fixture generators being wired to
named cups rather than the first CUP row.

**Exit criteria:** every tier plays a full season; promotion and relegation between tiers work.

### P2-12 — U-21 as its own competitions

Explicitly **not tabs on one competition**, per the analysis. Its own qualification path.

### P2-13 — Named tactics with derived levels

Three to four days. The six slider fields have **zero readers** today — they are stored and never used.

**Exit criteria:** the sliders change something observable in a match, or they are removed.

### P2-14 — Prize money: `awardPrizeMoney` has no caller

Small. It is implemented and nothing calls it — either wire it or delete it. It is on this list because
"implemented and unreachable" is a defect in its own right.

### P2-15 — Basketball, American football, text-based football

Per-mode work. Their agent notes are in `archive/BASKETBALL_PROGRESS.md` and have not been re-verified
since the `newLogic` split — **read the archive first, then confirm against the code, because the notes
predate the split.**

### P2-16 — Visual work

Presentation, not correctness. League table and World page already carry the Elo columns. Candidates worth
naming: the dashboard's empty states, and any page where a load failure renders as a generic error card
instead of saying what failed.

### P2-17 — Match engine realism

**Last, per the owner, and re-baseline first.** The numbers recorded for realism were measured against code
that has since changed, so they are not a specification. Re-measure, then decide.

---

# 📋 Parked, with the reason

| Item | Why it is not a task |
|---|---|
| Individual training focus per player | **Cancelled by the owner** |
| Work permits / foreign limits | **Built in full, then removed by the owner** (`a6394f9`) |
| `DEFENSIVE_SHAPE` / `/demo` routes / Oracle auth error | Listed in an old board section and not re-examined since. Re-check before spending time |
| `MatchdayJobsConfig` day 6 | P0-6 — an open decision, not an omission |
| Club Elo admin button | Zero reads `--` on an unrated world is deliberate, since **zero is a rating a club can
  legitimately hold**. Whether it wants a button is an owner call |

---

# 🔖 State at the time of writing

- `mvn test`: **992 tests, 13 failures, 16 errors, 29 red**, ~2 h 52 m, app required on `:8080`. Measured
  at `89144e9`; **a re-measurement was in flight on 2026-10-03** and its result is in `kanbanProgress.md`.
- The full red list and what each failure means is in `archive/kanbanProgress.md`, in the entry recording
  the run that was allowed to finish.
- **P0-1 and P0-2 are the two tasks that make everything else safer to do.** Do them first.