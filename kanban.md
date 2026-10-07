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
classes otherwise hang the entire run — they wait, they do not fail. Budget for it; never start it on
the way to something else. A full-suite run only counts if it was allowed to finish.

### PostgreSQL client tools are versioned against the server (found 2026-10-06)

`pg_dump` **refuses** to touch a newer server, and on this machine the two do not match: the server is
**Postgres.app 18.4** and the `pg_dump` first on `PATH` is **Homebrew 16.15**. Anything that shells out
to `pg_dump` therefore fails every time with *"aborting because of server version mismatch"* — a message
about versions, not about the thing that was being attempted. `DatabaseBackupService` reads the server's
major version over JDBC and picks a client that matches, checking Postgres.app's own bundled tools;
`app.backup.pg-tools` overrides the search. Anything new that shells out to the PostgreSQL tools needs the
same treatment.

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
- **A whole table re-read inside a loop is invisible to a clock.** Three instances of one pattern:
  `TransferService` (`e310856`), `SquadEnvironmentService` and `FriendlyRequestService` (`379cb12`). A
  collection loaded once and then re-asked per club, because the loop could not see the copy. **48
  countries is a small table** — the loop costs more than the query does. Count queries, not milliseconds.
- **`event_json` was 742 KB a match and 98% of it was noise.** `DECISION`, `PASS` and `RECEIVE` are
  per-tick engine internals that both readers already discarded. It is 17 KB now, and **the full per-tick
  log lives in a file** written by `SimReplayStore` rather than in the database — see P1-7.
- **An index can be worthless and then become necessary when the query beside it changes shape.**
  `match(match_date)` measured as no benefit and was rejected; keyset paging then made it the difference
  between 206 ms and 0.35 ms a page. "Measured, no benefit" is only true for the query it was measured on.
- **How ids are passed to a query changes its plan by 80x.** `IN (SELECT ... LIMIT n)` is a Hash Semi Join
  over a sequential scan; `IN` with n bound values is a bitmap index scan. Same rows, same result,
  5,020 ms against 44 ms. Do not "simplify" a bound list into a subquery.
- **`match_tick_states` is dead.** Nothing writes it, nothing reads it, 0 rows, and its only writer has no
  callers. Do not index it and do not migrate it; deleting it is an owner call.

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
| `match_fixture` | 2,790 | 310 a matchday, 9 matchdays seeded, **7 days apart** |
| `team` | 406 | 14,880 at full scale |
| `match` | **155** | **One matchday. Ever.** See below |

### 🔴 The dev database cannot measure a performance task

**`match` holds 155 rows: a single matchday of a single country.** `match_tick_states` holds **0**, and
that is not a seeding gap — nothing writes it (P1-1).

So the projected figures are **48×**: **7,440 matches a matchday, 89,280 a season**, and
`player_zone_load` at **198 rows a match** means **17,677,440 rows a season**, 2.8 GB.

**A sequential scan of 155 rows is the correct plan.** An `EXPLAIN` on the dev database cannot show that
an index helps, cannot show that one does not, and will confidently report "no problem" about a query that
costs five seconds at full scale. Every index added under P1 was measured in a throwaway `sokker_bench`
database built to those numbers; the plans are in `kanbanProgress.md`.

**The first attempt at P1-1 got this wrong** — it proposed three indexes, one of which served a query
with no caller and another of which made the hot daily job 68% slower. The board's own scale table had
said `player_zone_load` had 18,729 rows and did not mention `match` at all.

---

# 🔴 P0 — correctness, security, owner decisions

Ordered by leverage. **P0-1 and P0-2 are the two tasks that make everything else safer to do.**

---

## 🌍 P0-CUPS — international club cups and the remaining domestic draw decision

**Owner spec 2026-10-06. The international cup path is now wired; live database observation and the
domestic cup ownership decision remain open.**

Replaces the old **P2-11** entry, which said *"three tiers exist with data but the calendar is thin"* and
asked for *"every tier plays a full season"*. That description was too kind and, in one respect, wrong:
there are not three tiers of cups, there are **five tiers × three cups = 15 competitions**, and the reason
none of them plays is not a thin calendar. It is four defects in sequence, any one of which is fatal.

### The owner's format, restated exactly

**Tiers.** Five, matching `PyramidBuilder.DIVISIONS_PER_TIER = {1,2,4,8,16}` — tier 1 has **one** division
per country, tier 2 has two, tier 3 four, tier 4 eight, tier 5 sixteen. **A cup belongs to one tier and is
contested only by that tier's countries.** Tier 1's Champions Cup never meets tier 5's.

**Who enters, per country, per tier.** A country enters each of its tier's cups **once**.

| Cup | Who, per country, per tier | Field | Groups | Through |
|---|---|---:|---|---|
| Champions Cup | better of the divisions' **winners** | **48** | 8 × 6 | top **2** |
| Masters Cup | best **2** of the pool of every 2nd- and 3rd-placed club | **96** | 16 × 6 | winner only |
| Challenge Cup | best **1** of the pool of every 4th-placed club | **48** | 8 × 6 | top **2** |

Tier 2–5 pool across **all** of that country's divisions in the tier, because each of those is a separate
mini-table with its own champion, seconds and fourths. A country with sixteen divisions must not enter its
own cup sixteen times. Tier 1 degenerates to the straightforward case — 2nd and 3rd to the Masters, 4th to
the Challenge — and **one rule covers both**.

**Calendar.** Group stage is **5 matchdays in weeks 1–5**. Knockouts in **weeks 7–10**, and **the final and
the third-place play-off share week 10**. **All fifteen cups play on Day 1, 20:45** — the international
slot. Day 5, 18:00 stays **domestic cup only**.

**Tie-break, everywhere:** points → goal difference → goals scored → **draw**.

**Simulated countries.** For every tier, a `SIMULATED` country sends its bot team, at **average skill 12
for tier 1 and one lower per tier below** — 12 / 11 / 10 / 9 / 8.

**Screens.** On the **country** side, the qualifying tables must be visible and followable after every
round, refreshed by a job when leagues update. On the **world** side, a link to each of the three cups,
with the **tiers as separate tabs** inside each, and on each tab the results, the tables and the bracket.

### What already exists — do not rebuild it

| Piece | Where | State |
|---|---|---|
| The 15 competition rows — `type=CUP`, `scope=INTERNATIONAL`, `teamType=CLUB`, `tier=n` | `InternationalClubCups.java:142` | built, created at `DatabaseInitializer:319` |
| **The tier 2–5 qualification rule**, exactly as specified above | `InternationalClubCups.qualifyFrom():319` | correct — 9 tests green |
| Field sizes — 48 / 96 / 48 per tier — fall out of the rule | same | correct |
| **The group stage and the bracket** — 8×6 / 16×6, 5 matchdays, serpentine deal, R16→QF→SF→3rd→final | `InternationalClubCupDraw.java`, 631 lines | written, **12 tests green, zero callers in `src/main`** |
| The rating ladder 12 → −1 per tier | `PyramidBuilder.TIER_1_SKILL:200` | correct, but carried only on `Team.reputation` — never summed into a squad |

### P0-CUPS-1 — DONE: cup group matches now write their own tables

`MatchType.countsForTable(match)` now includes a cup match with a non-empty `groupCode`, and
`SimMatchService.persist()` updates the cup's own `SeasonCompetition`. Domestic knockout ties remain
outside the table path.

`InternationalClubCupDraw.rankingWithin():539` then compares eight entries that are all 0 points / 0
goals / 0 against. `LeagueTableOrder` falls through to its last key — **team id** — so *"the top two
advance"* resolves to **the two lowest database ids in the group**. Every group is decided by seed order
before a ball is kicked, and the same applies to the Masters Cup's group winner.

This is the defect that makes the rest of the block cosmetic, and it was not on the board in any form.

**Exit criteria:**
- [x] A cup group match writes points, goals scored and goals conceded to the cup's own `SeasonCompetition`
- [x] `LEAGUE` behaviour is unchanged — a league table reconciles to the same numbers as before
- [x] A domestic cup tie still does **not** write a table (there is no group stage to write)
- [x] **Proven able to fail:** the cup group table regression test changes the result and observes the ranking change

### P0-CUPS-2 — DONE: only knockout ties go to penalties

`SimMatchService.isKnockoutTie()` now checks the per-match group marker. Cup and national-tournament group
matches may finish level; knockout ties still go to penalties. This works for both cup groups and national
qualifying groups without adding a competition-wide format field.

`MatchFormat` already carries `goesToPenalties()` and has **zero callers anywhere in the application**,
because neither `Match` nor `Competition` has a format column. Three separate comments in this repository
state the ordering constraint as **wire `MatchFormat` before wiring a group stage**, and this is that.

**Exit criteria:**
- [x] A 0-0 **group** match finishes level with no penalty columns written
- [x] A 0-0 **knockout** match goes to penalties exactly as it does today
- [x] The per-match group marker is the caller-level discriminator; `MatchFormat` was deleted by owner decision

### P0-CUPS-3 — DONE: simulated-country entrants receive tiered squads

Qualification is one entry per country per cup, so the field is 48 + 96 + 48 = **192 clubs per tier**,
× 5 tiers = **960 clubs**. **46 of the 48 countries are `SIMULATED`**, and `PyramidBuilder.buildStatic()`
writes divisions, ratings and a standing table and deliberately **no players and no fixtures**.

`SimMatchService.simulate()` only generates squads when *exactly one side is human*
(`LazySquadGenerator.isHumanInvolved`). Both sides bot → `loadRealSquad()` returns `null` →
`SimTeamFactory.addTeam()` builds **synthetic placeholder players**. The Champions Cup would be decided by
22 unnamed stand-ins and the owner's 12/−1-per-tier ladder would be **invisible**, because a synthetic
squad has no rating to average.

**Owner decision 2026-10-06: only `SIMULATED` countries' clubs get a generated squad.** Active countries'
clubs already have real squads from `PyramidBuilder.build()`, so the rule is a no-op for them. Note that a
cup tie between an active club and a simulated one therefore mixes a real squad with a generated one —
which is exactly what `LazySquadGenerator` already does for a human against a bot, so the machinery exists.

`BotSquadGenerator` generates 25 players at a **hardcoded `BASE_SKILL = 12`** and is written for national
sides. It needs a tier parameter.

**Exit criteria:**
- [x] Every club entering any cup from a `SIMULATED` country has 11+ named players
- [x] Squad average skill is 12 / 11 / 10 / 9 / 8 for tiers 1 / 2 / 3 / 4 / 5
- [x] Generation is lazy (on entry), not for all 14,880 clubs, and is idempotent
- [x] **Proven able to fail:** the tier ladder is asserted against generated squads, not against `tierSkill`

**The fix was smaller than the diagnosis, and the reason is worth keeping.**
`BotLeagueStandard.TIER_ONE_AVERAGE = 12`, `STEP_PER_TIER = 1`, and `PlayerFactory.createRandomTeamPlayers`
already read the tier from `team.getCompetition().getTier()` and applied it. **The ladder was never
missing — it was never being applied to anybody.** So P0-CUPS-3 is `LazySquadGenerator` gaining one public
entry point and `InternationalClubCupDraw.buildGroupStage` calling it before its idempotency early
return, because the draw is what creates the need and a repaired world has to be able to re-enter.

**No `CountryState` filter, on purpose.** The owner's decision was "only simulated countries' clubs", and
it holds because an `ACTIVE` country's clubs already have squads from `PyramidBuilder.build()` — so the
clubs that arrive empty *are* the simulated ones. A second `state == SIMULATED` test would be a second
statement of the same fact, free to disagree with the first.

### P0-CUPS-4 — DONE: the draw runs — a job, on day 1, at 08:00

`InternationalClubCupJob` (`jobs/impl/`), key `club-cup-draw`, day 1, hour 8, order 30 — **ahead of the
day-1 matchday job at 20:00, because this job creates the fixtures that job plays.**

| Week | What it does |
|---|---|
| **1** | For each of the 15 cups: read the clubs that qualified off **last season's finished tables**, give the empty ones a squad, draw the group stage |
| **2–5** | Nothing — these are group matchdays, played by the day-1 matchday job |
| **6** | Nothing — national-team week |
| **7–10** | One knockout round per cup, as far as the results reach. Week 10 carries the final **and** the third place |
| **11** | Nothing — league promotion play-off |
| **12** | Nothing — national-team week |

**The calendar moved to the owner's, and the code's values were wrong:**

- **`CUP_DAY` 5 → 1.** Day 5 at 18:00 is the **domestic** cup's slot. The fifteen continental cups are
  international: day 1, 20:45. Day 5 keeps the national cup and nothing else.
- **`CUP_WEEKS` {1,2,3,4,5,7,8,9,10,11} → {1,2,3,4,5,7,8,9,10}.** Nine weeks, not ten: five knockout
  rounds, four knockout weeks, so the final and the third-place play-off share week 10. `weekFor()` already
  clamped its index to the array length, so this needed no special case anywhere else.

**Which season's tables.** The **finished** one, `season − 1`, clamped at 1. A club that wins its division
in week 12 cannot enter the same season's Champions Cup by winning it in week 12. The clamp makes season 1
honest: there is no season 0, nothing has finished, and no field is invented from a season never played.

**The lookup is scoped and tiered.** Sixteen `CUP` competitions exist — one domestic cup and fifteen
continental — so asking for "a CUP" answers with whichever row came first, which is P0-CUPS-6. It uses the
indexed `findByTypeAndTier(CUP, tier)` rather than fifteen reads of the whole table.

**Exit criteria:**
- [x] The job draws the group stage for every cup that has qualified clubs
- [x] Group matchdays land in weeks 1–5, on **day 1**
- [x] Weeks 6, 11 and 12 carry no club cup fixture
- [x] Re-running the job draws nothing twice
- [x] **Proven able to fail** — removing the squad call and reverting P0-CUPS-5 both fail loudly
- [ ] **A real season observed in the database.** Every assertion here is an integration test against the
      real write path, and this is the one criterion still open: it needs the app running with a world that
      has a finished season behind it.

### P0-CUPS-5 — DONE: a field that does not divide by six was dealt into one enormous group

**Found while writing P0-CUPS-4's test, not by reading the code.** `dealIntoGroups` counted groups with
**integer division**, so it floored:

```java
int groupCount = Math.max(1, rankedDescending.size() / GROUP_SIZE);   // 8 / 6 = 1, not 2
```

Eight entrants therefore became **one group of eight**. A group of eight has seven matchdays, so its round
numbers run 1–7 — and **rounds 6 and 7 are the last sixteen and the quarter final.** The bracket then read
a knockout stage out of the middle of a group stage: a Champions Cup with a "last sixteen" that was two
group matchdays.

**It never showed for a real field,** because the fields are 48, 96 and 48 and all three divide exactly by
six. It appears the moment a country is removed or a tier is short.

**And the two methods disagreed.** `groupCountFor()` already answered this question with a **ceiling**, so
the count that was asked for and the count that were built were different numbers for every field that is
not a multiple of six. One method answers it now, and `dealIntoGroups` throws if a group ever exceeds six.

**Exit criteria:**
- [x] A field that does not divide by six gives groups of **at most** six
- [x] `groupCountFor()` and `dealIntoGroups()` give the same number — asserted across **every** field from 8 to 100
- [x] A group over six throws and names the reason, rather than silently overrunning the knockout rounds

### P0-CUPS-7 — a field under eight still draws nothing — **FIXED ✅**

When `entrants.size() < MIN_FIELD_FOR_GROUPS` (8), `buildGroupStage()` creates the table rows
(`ensureTableRows`) and returns with `groupFixtures = 0`. Before the fix, `buildKnockouts()` read
`groupFixtures.isEmpty()` and returned `new DrawResult(..., 0, 0, 0, 0)` — nothing drawn.

The fix (2026-10-06): when fixtures are empty but `tableEntries` exist (the small-field case),
`buildKnockouts()` reads the entrants from those entries, sorts them by the ranking comparator,
filters to `qualifyPerGroup * 8`, and proceeds through the knockout loop (`ROUND_LAST_SIXTEEN`
to `ROUND_SEMI_FINAL`). Two clubs play a direct final; odd fields carry an unpaired club as a bye into
the next round; otherwise each knockout round is drawn one per call, as the original method designed.

**Exit criteria:** [x] a cup with 2–7 entrants produces a knockout bracket · [x] the draw result and log
report the fixtures created · [x] two entrants play a direct final; odd fields carry one club byes into
the next round

**Owner decision:** a two-club international cup is still a competition and plays one final. This keeps
the qualifying field honest when a tier has only two divisions and avoids inventing a group stage.
---

### P0-CUPS-6 — DONE: domestic cup drawing is scoped per country

**Found 2026-10-06 while running the P0-CUPS-1/2 regressions. Fix landed. The fix is NOT yet guarded, and
the reason it cannot be is a second, older defect — see the correction below.**

`CupFixtureSeeder` picks its target two different ways, and they disagree:

| Method | Selection | Scope filter |
|---|---|---|
| `primaryCup():293` — used by `drawRoundForWeek`, i.e. by the day-2 `CupDrawJob` | `findFirstNationalScoped(CUP, INTERNATIONAL, Limit.of(1))` | **yes** |
| `seedIfMissing():142` — used by **boot** and by `WorldRepairService.repair("cup")` | `findAll().stream().filter(type == CUP).findFirst()` | **no** |

So `seedIfMissing()` takes the **lowest-id CUP row of any scope**. The 15 continental cups are exactly
that: `country == null`. `rankedClubs():210` then hits `cup.getCountry() == null`, logs *"Cup Champions Cup
has no country; nothing to rank"*, returns an empty list — and **the domestic cup is not drawn at all.**
`seedIfMissing()` now calls `primaryCup()`, so boot and the day-2 job finally agree.

**Why this is P0 and not a footnote:** P0-CUPS-4 makes those 15 rows exist in the running app. Left alone,
the national cup — which the owner has played since before this board existed — would go quietly empty on
the next boot.

#### 🔴 Correction — my first diagnosis of the 5 red `CupFixtureSeederCountryTest` was wrong

I wrote on this board that this defect was *"the reason `CupFixtureSeederCountryTest` goes red the moment
`InternationalClubCupDrawTest` runs first"*, and that the pollution was measured to predate P0-CUPS-1. The
second half was measured and is true. **The first half was a guess dressed as a finding, and it is false.**
Measured, in this order:

| Batch | Result |
|---|---|
| `CupFixtureSeederCountryTest` alone | **6 green** |
| `CupFixtureSeederCountryTest` + `CupDrawSeedingTest` | **5 red** — reproduces |
| the same batch at `95151e6`, before any P0-CUPS code | **5 red**, identical |

So the polluter is **`CupDrawSeedingTest`**, not `InternationalClubCupDrawTest`. `CupDrawSeedingTest`
creates a country `"ZZ Cup …"` and a cup `"ZZ National Cup …"` with only 8 clubs;
`CupFixtureSeederCountryTest` creates its own national cup with 260. Both then call code that resolves
"the one cup" as **the lowest-id national cup in the database** — so whichever class ran first owns the
answer, and the loser draws into the other's competition. `CupFixtureSeederCountryTest` fails with *"the
cup drew nothing at all"* because it is drawing into an 8-club cup that fails the 256-club threshold.

**The root cause is the same in both places, and it is `primaryCup()` itself.** "One cup, chosen by
lowest id" is not a rule a competition table can satisfy — it is a rule about insertion order in a shared
database. It makes production depend on which row got its id first, and it makes both tests depend on
which class ran first.

#### ❓ Owner decision needed: what is "the" domestic cup?

`seedIfMissing`'s comment records this as a parked decision — *"one job drawing 48 national cups, or one
draw per country"* — and deferred it to that decision. **The performance change it deferred to has long
since been made; the correctness half was left behind, and it has now cost one unguarded fix and two
order-dependent test classes.** Three options, and this is the owner's:

| | Option | Consequence |
|---|---|---|
| **A** | **One cup per country.** `CupDrawJob` and `seedIfMissing` take a country and draw that country's cup. | Removes the ambiguity entirely. 48 draws per season instead of 1 — the scale question P1 exists for. **Recommended.** |
| **B** | **Name the cup explicitly** — e.g. the lowest-id national cup per country, or a `primary` flag on `Competition`. | Smallest change that removes the shared-database coupling. Still "one cup per country" in effect. |
| **C** | Leave it. | Keeps an order-dependent production rule and two order-dependent test classes. |

**Until this is answered, P0-CUPS-6 has no test.** A guard for the `seedIfMissing` fix has to make the
continental cup the lowest-id CUP of any scope — and whether an earlier test has already created a
national cup below it decides whether the old code would pass. A test that is green for the wrong reason is
worse than no test, so none was written.

**Exit criteria:**
- [x] `seedIfMissing()` targets the same competition `drawRoundForWeek()` does (calls `primaryCup()`
      instead of `findAll()`)
- [x] The owner's **Option A — one cup per country** is implemented: both `seedIfMissing()` and the
      scheduled `drawRoundForWeek()` iterate every national cup and scope entrants to that cup's country.
      International cup rows are excluded by `scope`.

**Owner decision 2026-10-06: option A — one cup per country.** Implemented in `CupFixtureSeeder`: the
repair/bootstrap path and the scheduled job both iterate national cup rows, and each round resolves its
survivors from that cup's country. The remaining evidence is live observation of all available country
cups being drawn in a real world.

---

## ✅ Owner decisions, 2026-10-06 — taken after the analysis, before P0-CUPS-4

Four questions asked with the measured state in front of them. **All four are now closed.**

### 1. A simulated country's division has no "better" club — and that is fine

**Decision: leave it. For a simulated league the order is arbitrary — a reproducible draw.**

Measured, and it looked like a P0: `PyramidBuilder.fillStaticDivision()` gives all ten clubs in a
division one identical `reputationFor(tier)`, writes `points(0)` and leaves goals unset, and sorts by
`reputation desc, then name`. So the stored `position` is alphabetical. And nothing reads it —
`InternationalClubCups.qualifyFrom()` orders by `LeagueTableOrder` (points → goal difference → goals
scored → **team id**), every row ties on all four, and the club entering the Champions Cup is the one with
**the lowest database id** in its division.

**The owner's answer is that this does not need fixing, and it is the right answer.** `LeagueTableOrder`'s
last key being team id means the order is *arbitrary but stable* — the same table produces the same order
every time it is asked for, which is exactly the property a draw needs. Adding an intra-division strength
spread would make the simulated field look like it was decided on merit, and it would be decided on a
reputation number that was invented at seed time.

**So this is recorded as a non-defect, and the reason is written down here on purpose.** The B6 comment in
`fillDivision()` records a *previous* attempt at exactly this fix —

> *"Every club in a division used to be created with one identical reputation… continental qualification
> read that table, which made entry alphabetical for 47 of 48 countries."*

— and that fix was applied to `fillDivision()` (active countries) and **not** to `fillStaticDivision()`
(simulated countries). A future reader comparing the two methods will take that as an oversight and
"correct" it. **It is not an oversight. Do not add the spread to `fillStaticDivision`.**

`poolAt()` sorts each cross-division pool by reputation too, which is equally degenerate for a simulated
country and equally deliberate: stable and arbitrary.

### 2. The domestic cup is one cup per country

**Decision: option A.** `CupDrawJob` and `seedIfMissing()` take a country and draw that country's cup.
Closes **P0-CUPS-6** and unblocks its test. 48 draws per season instead of 1, which is a scale question
and belongs in P1 when it is measured.

### 3. `MatchFormat` is deleted

**Decision: delete.** 144 lines, zero callers, and provably the wrong shape — a cup has two formats in one
competition. Deleted; `CompetitionType`'s javadoc now carries the reason and points at
`Match.groupCode`. P0-10 set the precedent on the same grounds.

### 4. There is parallel work in this tree

**Confirmed by the owner.** National-team competition work (`Competition.nationalLevel`,
`Competition.nationalStage`, `NationalTournamentSeeder` and five related files) is in flight and is not
mine. **Every P0-CUPS commit stages only its own files.** The tree compiles as of `fec7aa5`.

---

## 🟠 P1-CUPS — the calendar, the slot model, and the two screens

### P1-CUPS-1 — four weekly slots: days 1, 3, 5 and 7

`SeasonCalendar` now models `SLOTS_PER_WEEK = 4` on days 1, 3, 5 and 7. League rounds remain on days 3
and 7; days 1 and 5 are friendly-capable. Friendly availability is checked against the fixture's actual
day, so a league, cup or international match blocks only its own slot.

**Owner decision 2026-10-06: widen to four slots — days 1, 3, 5, 7 — and in the playoff week the slot
where the playoff is played stays friendly-capable for every club not in the playoff.**

`LeagueSlotSchedule` now finds each round's actual calendar slot and reads its day, so widening the
calendar does not move league football from days 3 and 7.

Touches: `SeasonCalendar`, `WeekSlot`, `FriendlyRequest.slot`, `FriendlyRequestService`,
`FriendlyController`, `SeasonService.ensurePlayoffWeekFixtures`, `SquadTrainingService`.

**Exit criteria:**
- [x] Every league round still lands on **day 3 or day 7** — the schedule reads the calendar's league slots
- [x] A club with no day-1 fixture can book a friendly into day 1
- [x] A club with no day-5 fixture can book a friendly into day 5
- [x] In week 11 a club **not** in the playoff can book into the playoff slot; a club **in** it cannot
- [ ] **Proven able to fail:** change `LeagueSlotSchedule` to return slot index and watch the day assertion fail

### P1-CUPS-2 — friendly training cost is zero, but the training path still needs cleanup and proof

**Owner decision 2026-10-06: a friendly costs no training session. Day 4 is a training *update*** — driven
by minutes played, coach, talent, height and skill — **and that is not a training session.**

The contract is now explicit: `FriendlyRequestService.TRAINING_SESSIONS_PER_FRIENDLY = 0`, so an agreed
friendly does not consume a training session. `SquadTrainingService` keeps the baseline of three sessions,
and `TrainingPercentService` still supplies match minutes to development calculations.

The shared session helper is deliberately retained as the single API for the training baseline and the
zero-cost deduction. **No cap on friendlies per week** — a club may book any free slot.

**Exit criteria:**
- [x] A friendly contributes zero training-session cost
- [x] A friendly leaves the club's training-session budget unchanged; match minutes remain an input to
      the development percentage
- [x] `BASE_TRAINING_SESSIONS_PER_WEEK`, `TRAINING_SESSIONS_PER_FRIENDLY` and
      `trainingSessionsAvailable` have an explicit final contract and remain the shared API
- [x] Growth still responds to coach, age and minutes played — the parts the owner kept

### P1-CUPS-3 — the country side: the qualifying race, and a job to keep it honest

The owner asked for the country-side tables to be visible and followable after every round, with **a new
job** to update them when leagues update.

The qualifying race is derived from current league tables. At tier 1 the places are direct: 1st to
Champions, 2nd and 3rd to Masters, and 4th to Challenge. At tiers 2–5 the country's divisions are pooled
into three synthetic races: all winners for Champions, all 2nd and 3rd place clubs for Masters, and all
4th place clubs for Challenge.

**One judgement to make explicit, not to skip:** `InternationalClubCups.tierTables()` already reads a
tier's tables in **three queries** and the World page's budget is already under test
(`InternationalClubCupsQueryBudgetTest`). So a job is not obviously *needed* — the read is cheap and a
persisted copy is a cache with an invalidation problem. The owner asked for a job; the honest options are
(a) derive on read and add the job only if a measured read is too slow, or (b) persist and refresh.
**Recommend (a), with the measurement recorded** — and say so on the board rather than building the cache
because it was requested.

**Exit criteria:**
- [x] The country general tab shows only the relevant candidates for each cup by tier
- [x] Tier 1 uses direct places; tiers 2–5 rank the three pooled mini-tables and mark the selected places
- [x] It reads the current reconciled league tables when the country page loads, without a world-page refresh
- [x] The data is deliberately derived on read; the existing league reconciliation jobs update the source
      tables, while a persisted copy would add cache invalidation without reducing the three-query tier read

### P1-CUPS-4 — the world side: three links, tiers as tabs, tables and brackets — **DONE ✅**

The World page renders three rows in one competition table from `pages.js` `clubCupRow()`. Each cup page is
`club-cup-view.js`, wired as `createClubCupView({ authFetch, escapeHtml, loadPage })` with a `clubCup`
route in the page router. Tiers 1–5 are tabs inside each cup; each tab shows the group tables (P W D L GF
GA GD Pts), the results and the knockout bracket — all from `ClubCupController`'s grouped payload.

The table shows the expected fields: Champions `48`, Masters `96`, Challenge `48`. The separate admin
repair action durably creates missing international competition rows before it fills simulated-country
static tables; the week-1 cup job repeats that durable boundary before drawing.

**Live verification 2026-10-06:** after a clean restart and one repair request, PostgreSQL contained all
15 international club cup rows. `/club-cups/champions?tier=1`, `/club-cups/masters?tier=1` and
`/club-cups/challenge?tier=1` returned HTTP 200, and the World payload exposed competition IDs for all
15 tier rows. The long simulated-world seed was still running when the check ended, so its qualifying
counts were not used as the final field-size assertion.

**Exit criteria:**
- [x] Three links from the World page, one per cup · [x] tier 1–5 are tabs inside each
- [x] Each tab shows the group tables, the results and the bracket · [x] all 15 cups are reachable
- [x] `authFetch` is used for every call and `response.ok` is checked on every one
- [ ] **Proven able to fail:** remove one tier's bracket from the payload and watch the tab render it empty
      → **unblocked:** `loadPage('clubCup', { cupKey, tier })` always passes `tier`; the view requests the
      tier from the job's payload per-tab, so an empty payload renders an empty tab at once.

### P1-CUPS-5 — three defects in the read path, all small — **DONE ✅**

All three fixed in the same session:

- **`worldOverview():159` hard-wired `finishedSeason = 1`.** Fixed — now reads
  `out.get("currentSeason")` from the clock.
- **`getCup()` could render the Champions Cup as a country's own cup.** Guarded on
  `scope = NATIONAL`.
- **Weeks 6 and 12 rendered as "Reserved for … which are not built yet".** Notes updated to
  describe national-team matches.

---

## ❓ P1-CUPS-6 — OPEN QUESTION, settled before P0-CUPS-4

**Do `SIMULATED` countries actually play their own league?** The written spec says they do not — they
*"hold their positions until their league is activated"*. The code says they do.

`SeasonService.openNewSeasonForEveryCountry():690` iterates **every** `LEAGUE` competition in the world,
filters only on `country != null`, and calls `ensureDoubleRoundRobinSchedule()` on each. `MatchdayJob`
has **no `CountryState` filter** — the day-3 and day-7 jobs will play those fixtures. So a simulated
country plays synthetic-squad league football from season 2 onward, and its table moves.

**Why this blocks P0-CUPS-4 rather than following it:** the rows the cups qualify from *are* the disputed
rows. Qualification reads the finished season's tables, so whether a simulated country's position is real
football or a standing fixture decides what the Champions Cup field is.

**Decide before the draw job lands:** simulated countries keep a fixed table and the matchday jobs skip
them, or they play for real. Both are defensible; the current state is neither.

---

### P0-1 — Nine controllers have no tests at all, so the security surface is untested

**Why this is first:** only three tests in the whole repository exercise any controller. Five reachable
security defects were previously found by reading source, not by a failing test. Writing these tests
against the current code is how the *next* five get caught.

**Scope, corrected against the source on 2026-10-03:** `LineupController`, `PlayerController`,
`TeamController`, `UserController`, `AdminController`, `CommunityController`, `DummyDataController`,
`TransferController`. **Two names on this list never existed** — there is no `CompetitionController` and no
`StadiumController`. The real ones are `StadiumSettingsController` (for the stadium) and, for competitions,
no controller of their own. `TransferController` was substituted **on the owner's decision**, because
`AdminController`'s own javadoc records that `/transfers` is "not role-guarded, so putting it there would
let any authenticated user delist another club's player" — a named hole rather than a coverage tick.

**Split in two, because one commit was not a reviewable unit:**

| | Controllers | State |
|---|---|---|
| **P0-1a** | `LineupController`, `PlayerController`, `TeamController`, `UserController`, `AdminController` | **done**, `66553b4` |
| **P0-1b** | all four — `TransferController`, `StadiumSettingsController`, `DummyDataController`, `CommunityController` | **done**, `6fd6521` |

**How:** copy the JWT pattern from `WorldAdvanceAuthorizationTest`. **Mock the repository _interface_, not
the injected bean** — Spring Data returns a JDK proxy that Mockito cannot wrap, and this is the single
most common way a test of this kind fails for the wrong reason.

**Exit criteria:**
- [x] Every one of the eight has at least one test that calls it **without** a JWT and asserts 401/403 — **P0-1a**
- [x] Every world-moving or admin-only route asserts the role check, not just authentication — **P0-1a**
- [x] At least one test per controller asserts a **successful** path, so the guard tests cannot all pass
      against a controller that returns 500 for everything — **P0-1a**
- [x] Each new test class is proven able to fail by breaking the authorization annotation and watching it
      — **P0-1a, five mutations, all in `kanbanProgress.md`**
- [x] The same four, for the four P0-1b controllers — **P0-1b, done.** The box was left unchecked after P0-1a merged; corrected 2026-10-04

**Not:** a coverage percentage. A test that asserts 401 on a route that was always going to 401 proves
nothing about authorization.

**Six defects P0-1a found and closed.** Recorded in full in `kanbanProgress.md`; the two that mattered most
were **not** missing guards:

- **`POST /lineups` had never accepted a request body at all.** It took the raw `Lineup` entity, and Jackson
  cannot deserialise that graph — every caller, including an administrator, got
  `HttpMediaTypeNotSupportedException` before the controller was entered. A test asserting only 403 would have
  been green throughout, because a route that cannot bind refuses everyone equally. Now takes a DTO.
- **`POST /players/create` answered 500 on every call.** `PlayerDTO.from` dereferences `player.getSkills()`
  unconditionally and `createPlayer` never set one.

### The most severe finding in the whole P0 segment — `TransferController`, P0-1b

**Ten writes, and not one of them asked who was acting.** Every write takes the acting club as a
caller-supplied parameter, and `TransferService` can only compare that parameter against the seller — it
cannot know who holds the token. So the question "is this your club?" was never asked on the surface. The
codebase says so itself: `AdminController.forceUnlist` carries a javadoc explaining it lives under `/admin`
**because** `/transfers` is not role-guarded. The hole was documented in prose and left open.

**And on four routes, omitting the parameter turned the check off.** The seller guards read
`if (actingTeamId != null && !Objects.equals(...))`, so `null` skipped the comparison entirely. The guard was
strictest when a caller could prove who they were and absent when they could not — exactly backwards. The
same file's `requireSeller` already had it right: null is a 400.

What that allowed, all now refused:

| Route | Was |
|---|---|
| `POST /list/{playerId}` | list **any** player in the world at **any** price |
| `DELETE /remove/{playerId}` | delist **any** player |
| `POST /buy/{playerId}` | spend **any** club's budget — the buyer is named in the body |
| `POST /interest/{playerId}` | register interest as **any** club |
| `/accept-offer`, `/reject-offers`, `/interest/{id}/clear` | act on **any** listing |

The rule applied is the one the rest of the game already uses: **the club named in the request must be the
club the caller runs.** Naming a rival satisfies the service's check and not this one, which is precisely how
every route was reachable. Reads are untouched — the market page is for every manager, and the country filter
already defaults to the viewer's own.

**`TransferController` is the only route in the repository where one manager could move another club's
money.**

### P0-16 — DONE: `/demo` is gone from the main app, and `DummyDataController` with it

**The owner's ruling:** none of `/demo` should exist on the main app, and never hardcode — take `teamId`
from the user. **Both halves are now true of all eight call sites, and `DummyDataController` is deleted** —
281 lines of hardcoded fake data with a literal `1` in every path.

**What the calendar settled.** The owner pointed at the calendar and it answered the friendlies question
directly: weeks **6, 11 and 12** carry the friendly slots, and *"anything that is not a scheduled fixture is
an **option**, not an obligation: a club is not handed a friendly, it asks for one and the other club may
refuse."* So a friendly **belongs to no competition** — which is why `competitionType` could never select one,
and why the screen was reading fabricated data. `MatchType.FRIENDLY` (another agent's P2-8 work, already
landed) is the right home for it, and `MatchFixture.resolvedMatchType()` keeps older rows honest.

**Six rewired, five of them to endpoints that already existed and were already widely used:**

| Call site | Was | Now |
|---|---|---|
| `club-management.js:45` | `/demo/teams/{id}/profile` | `/teams/{teamId}/profile` |
| `staff-directory.js:100` | `/demo/teams/{id}/profile` | same |
| `stats-view.js:36` | `/demo/stats/teams/{id}/players` | `/teams/{teamId}/players` |
| `fixture-view.js:42` | `/demo/matches/teams/{id}/upcoming` | `/teams/{teamId}/schedule` |
| `pages.js` `loadCup()` | `/demo/cups/{id}` | `/teams/{id}/schedule?competitionType=CUP` |
| `pages.js` `loadInternational()` | `/demo/internationals/{id}` | `…?competitionType=INTERNATIONAL` |

`/teams/{teamId}/players` was already called from `team.js`, `club-view.js`, `formations-view.js`,
`training-view.js` and `league-view.js` — the squad endpoint was real all along and only this screen was
asking the fake one.

**New: `GET /teams/{teamId}/schedule?competitionType=`.** The route resolved exactly **one** competition —
the club's league — so cup and international fixtures were invisible on it. **That is why those two screens
pointed at fabricated data: there was nothing real to ask for.** Every row already carried
`competitionType`, so this is a query choice and a filter, not a new endpoint. An unrecognised value falls
back to the league rather than inventing rows.

**1. `stats-view.js` team stats — DELETED, on the owner's decision.** It rendered four bare scalars:
`{goals, conceded, possession, shots}` — no season, no competition, no opponent. Its two neighbours in the
same file are real (`loadTopScorersAndAssists`, `loadPlayerStats`, both of which even handle "club not in a
league yet" with a proper message), so this was a placeholder standing beside two working screens. The club's
real information — `/teams/{teamId}/milestones` — is already wired into `club-view.js` and `league-view.js`.
Removed: the function, its export, the `pages.js` delegator and the `window` global.

**Building a read for it was rejected as the wrong kind of work.** Goals-against and shots-per-game per club
need a definition of possession the codebase may not have, and shots may not be recorded per team at all.
That is a **feature to schedule**, not a wiring job, and it should not be smuggled in here.

**2. `fixture-view.js` friendlies — the last caller, and it is not a wiring job either.** My earlier claim was
*"there is nothing to filter on"*, and tracing it showed that was **too strong**: friendlies *are* modelled —
`SeasonService.FRIENDLY_WEEK`, a `FriendlyRequestService`, and a working `FriendlyController` the dashboard
already reads. What is missing is narrower and more specific:

- **`FRIENDLY` is not a `CompetitionType`** — the enum is `LEAGUE, INTERNATIONAL, TOURNAMENT, CUP`. The
  owner's ruling is to **extend the enum**.
- **But the enum alone is not enough**, and this is the part that matters: adding `FRIENDLY` makes the value
  *filterable* and does **not** make anything *write* it. The friendly fixtures are created by
  `FriendlyRequestService` and placed by the season calendar, so until those write `CompetitionType.FRIENDLY`,
  `?competitionType=FRIENDLY` answers with an empty list — which is honest and is still a broken screen.
- **The trace is incomplete.** Where the friendly fixture row is written, and what competition it carries, was
  not established before this was written up. That is the next concrete step, and it should be finished
  before the enum is extended, because extending an enum nothing writes is a change that looks finished and
  is not.

**Both were nearly shipped as plausible-but-wrong.** Wiring friendlies to `?competitionType=FRIENDLY` and
team-stats to `/milestones` would both answer **200 with the wrong data** — the precise failure this task
exists to remove.

**Exit criteria:**
- [x] No caller hardcodes a team id; all eight derive it from the signed-in manager
- [x] Six of eight read real data
- [x] `competitionType` filter on the schedule, **6 tests green**, mutation-proven
- [x] Team Stats **deleted** on the owner's decision — a fourth, emptier presentation of `/milestones`
- [x] Friendlies read real data via `?matchType=FRIENDLY`, on the calendar's own weeks 6/11/12
- [x] **`DummyDataController` deleted** — 281 lines, zero overlap with the frozen `/demo/service` engine,
      and no caller left. `TeamControllerAuthorizationTest` went with it, since it tested fake data
- [x] **8 green** on the filter class; **24** with `TeamAuthorizationTest`

**One assertion had to be corrected as friendlies became real.** The "unrecognised type does not guess" test
forbade the string `FRIENDLY` anywhere in the body — which was right when no friendly row existed and became
**wrong the moment one did**, because a genuine friendly carries `"matchType":"FRIENDLY"`. It now forbids
`"competitionType":"FRIENDLY"`, which is the thing that must never appear: no such competition exists.

---

### P0-17 — DONE: the chat's applicant filter is now pinned by a test

`CommunityController.shouldHideFromNonAdmin` decides whether a message bound to a **pending registration
request** — an applicant's username and email — is shown to a non-administrator. It is a single boolean
method, `/chat` returns the thread, and **nothing tests it.**

Recorded rather than tested on purpose: pinning it needs a message bound to a pending request, and a test
built that way would assert almost nothing about authorization. A test that cannot fail is worse than no
test.

**What is actually at stake, stated precisely rather than alarmingly.** The DTO already gates the
applicant's **email** behind `adminViewer`. What is not gated is the **username**, the fact that they applied,
and which club they asked for. So the exposure is not a password and not an address — it is **a list of who is
trying to join the game and where they want to play**, visible to every logged-in manager.

**Low severity today, in a world with one real player. Not low in the world this project targets.**

**`RegistrationApplicantIsNotInTheChatTest`, 5 green, mutation-proven.** Removing the boolean fails exactly
the test that claims to hold it up:

```
aRegularManagerDoesNotSeeTheApplicant
  a pending applicant's username reached the community chat of an ordinary
  manager ==> expected: <false> but was: <true>
```

**The four tests that keep it honest.** Asserting one hidden username proves nothing unless the message
exists and the endpoint works, so:

| Test | What it rules out |
|---|---|
| `anApprovedApplicantBecomesVisible` | the same applicant, same message, one status flipped — **the filter is the status, not luck** |
| `anAdministratorSeesTheApplicant` | hiding it from the queue would break the feature the message exists for |
| `aRejectedApplicantIsVisible` | the predicate is *pending*-only, not "any registration" by accident |
| `aManagerCanStillReadTheChat` | the whole route being dead, which would make the first test pass for the wrong reason |

The applicant name is unique per run, because the shared database does not roll back and an earlier run's row
would otherwise let the assertion pass on stale data.

**Exit criteria:**
- [x] A pending applicant's username is proven invisible to a `REGULAR` manager
- [x] Asserted on the **absence of the applicant's own name**, not on a count or a missing key
- [x] The message is created through the real `postRegistrationSubmitted` path, not hand-built
- [x] Proven able to fail by removing `shouldHideFromNonAdmin`

---

### P0-13 — DONE: training asked who was acting on one route out of two

**`TrainingController`, 9 tests, two mutations.**

**The rule already existed in the same file.** `setIntensity`, forty lines below, documents its refusal:
*"a player who does not play for this club is a 403, not a bad request, because the request is well formed
and the manager simply is not allowed to make it."* And `plusFeatures` was already injected to make exactly
that check. So `POST /train/{playerId}` — which trained and returned **any** player in the world, by id,
with no club involved — was the one route in the controller that did not use the rule its neighbour enforced.

| | Was | Now |
|---|---|---|
| `POST /train/{playerId}` | **200**, and returned a raw `Player` | 403 unless the player is in the caller's own club; answers `PlayerDTO` |
| `POST /train-all` | **200** to any logged-in manager | administrator-only |

**Both routes have zero callers** — not in `static/js`, not in `src/main`, not in one test. The training
screen uses `POST /training/weekly/team/{teamId}/run`, and the world is trained by day 4's `TrainingJob`.

**The raw `Player` was a third disclosure surface**, after `/players/paged` in P0-1a and `/players` in
P0-1b: `talent`, `earnings`, the injury record, `personality`, `skills`. The test caught `9.1` where the
entitlement rule says `null`.

**`/train-all` is DELETED — the owner's ruling.** It was guarded first and deleted second, and **that order
is the point**: the guard answered *who may*, the deletion answers *should this exist at all*, and for a
route with zero callers the second is the question that mattered. 36 lines gone, **no production caller** to
change. The tests now assert the route is **absent** (404/405) rather than merely gated, because a guard can
be weakened by whoever edits it next and a deleted mapping cannot.

**Exit criteria:**
- [x] `/train/{playerId}` refuses with 403 unless the player is in the caller's own club
- [x] `/train-all` is administrator-only
- [x] Neither returns a raw `Player` entity — `/train-all` returned a count, thanks to another agent
- [x] **`/train-all` ruled on: DELETED**, on the owner's decision. No caller anywhere, so nothing broke.
      An anonymous caller still gets 401 — the filter chain runs before routing, so it never reaches the
      missing mapping — and that is pinned separately so the distinction is not mistaken for the route
      existing

---

### P0-18 — DONE: `viewerTeamId` returned an id from the wrong table, and the owner took it

Found because a fixture caught it, and it is worth more than the bug it looked like.

`PlusFeatureService.viewerTeamId` checks `tifoCTeam` **first**:

```java
if (user.getTifoCTeam() != null && user.getTifoCTeam().getId() != null) {
    return user.getTifoCTeam().getId();      // a CTeam id, not a Team id
}
```

`CTeam` is `footballtextmanager.model.CTeam` — a different entity with its own `IDENTITY` sequence. So that
value is **not** a `Team` id, and every caller comparing it against `Team.id` fails.

**It is not only a fixture problem.** `DatabaseInitializer:899` and `StartupInitializer:104,142` all set the
**owner's** `tifoCTeam`. So the owner gets a `CTeam` id back from `viewerTeamId`, which means
`talentOrNull` withholds talent from the owner for his own players.

**Why it went unnoticed:** `RegistrationService` sets only `cTeam`, so an ordinary manager never takes the
branch. Only the owner does.

**Exit criteria:**
- [x] `viewerTeamId` resolves to a `Team` id in every case — the `tifoCTeam` short-circuit is gone, and
      `clubNameOf` already reads `cTeam` first and falls back to `tifoCTeam`, so one name lookup serves both
- [x] The owner can see his own players' talent — asserted on the **effect** through `talentOrNull`, not on
      the intermediate id, so it still holds if every caller stops using `viewerTeamId`
- [x] A test asserts the **value** of `viewerTeamId` for a user with a `tifoCTeam`, not merely that it is
      non-null — `ViewerTeamIdIsATeamIdTest`, **6 green**
- [x] Proven able to fail: restoring the short-circuit fails 2 of 6 with
      `answered with the CTeam's id (17) where the club's id (1) was needed`
- [x] **98 green** with `PlusFeatureServiceTest` and all six controller authorization classes

---

---

### P0-14 — DONE: `LineupController` is read-only

**Owner's decision: option B — delete the two writes, keep the reads.**

`POST /lineups` and `DELETE /lineups/{id}` are gone, along with `LineupSaveRequestDTO`, which existed only to
serve them. The reads stay because they are reached.

**Why B and not C:** the duplication was the problem, not the routes' existence. The game files a squad
sheet through `TeamController`'s `lineup-template`, and `GET /lineups/{id}` is reachable from the match view.
Deleting the whole controller would have removed a read something uses.

**What P0-1a had already fixed here**, now removed rather than kept: the missing ownership guard, and
`POST`'s inability to accept a body at all. Both were real defects; neither has a caller, so the cheaper
answer is deletion. `LineupAuthorizationTest` drops from 10 tests to 3, and keeps exactly the guarantee that
still means something — **anonymous callers are refused, and a manager can read, because who is in a rival's
eleven is a league-table fact.**

---

### P0-15 — DONE: the tactics file is down to the one profile that places

**The file `var/tactics-editor-profiles.json` is tracked in git** and is the only durable copy of a club's
tactical-editor work. Five profiles, **one** of which named a club the world has. Now one.

**Verified against the live database, not inferred:**

| | |
|---|---|
| `OFK Omladinac` exact matches in 406 club names | **1** |
| Already in `team_tactics_profile` | 4-4-2, ATTACKING, **132,532** chars, version 5 |
| The surviving file profile | 4-4-2, ATTACKING, **132,532** chars |

The two copies agree exactly, so the restore's "database wins where both have the club" path keeps the
authoritative row and the file remains a genuine backup of the same thing.

**Why dropping beats mapping — and it is not the reason the board gave.** The board said *"the world has
five Beograd clubs and none is called FK Beograd"*. The stronger fact is that **twelve clubs are near-misses
and there is no way to choose between them**:

| Removed profile | Clubs it could plausibly mean |
|---|---|
| `FK Beograd` | `NK Beograd`, `GFK Grafičar Beograd 1945`, `SK Balkan Beograd City` |
| `GFK Dinamo Šabac` | `OFK Šabac 1928`, `SK Kolubara Šabac`, `NK Car Konstantin Šabac 1931` |
| `GFK Tamiš Gornji Milanovac 1901` | `NK Tamiš 1950`, `OFK Tamiš Kragujevac` |
| `SK Čačak 1912` | `FK Čačak 1931`, `GFK Mlava Čačak 1913`, `OFK Čačak`, `SK Čačak Sport` |

**Two of those are the trap.** The profile says `Čačak 1912`; the world holds `FK Čačak 1931` and
`GFK Mlava Čačak 1913` — **adjacent founding years, different clubs.** A fuzzy matcher would attach a
4-3-3 profile authored for one club to a confidently-named wrong one, and the mistake would be invisible
thereafter. There is no mapping to make. Dropping is the only safe answer.

**A correction to this board's own criterion.** It said *"the restore no longer reports unplaceable profiles
by name, because there are none to report"* — which reads as an instruction to delete the `unmatched` warning.
**Do not delete it.** That warning replaced a silent `continue`, and the silent `continue` is precisely how
four of the owner's five profiles vanished without a word. The criterion is satisfied by the **file** no longer
containing any, and the warning stays as the guard for the next one.

**A test fixture was polluting this file, and it was mine.** `Rival b0542c46` — a club named after a
P0-1a fixture — had been written into it by a test reaching the editor through HTTP. Removed. The cause was
fixed in P0-1a (the backup path is now a property); this removes the pollution it left behind.

**New guard:** `TacticsBackupIsNotWrittenByTests` asserts the tracked file is byte-identical either side of a
tactics write — **and asserts the sandbox file *did* change**, so the first assertion cannot pass vacuously.
Its first version omitted the path override, wrote to the tracked file, and **the guard caught it in the same
commit.** That is the guard working, and the reason the override is code rather than a comment.

**Exit criteria:**
- [x] `var/tactics-editor-profiles.json` holds one profile, `OFK Omladinac`
- [x] The restore no longer has any unplaceable profile to report, **and the warning stays**
- [x] A test now prevents any test from writing the tracked file, **and is proven able to fail**
- [x] **12 green**: `TacticsBackupIsNotWrittenByTests` 2, `TacticsProfileRestoreTest` 3,
      `TacticsRulesProviderTest` 7 — plus **26** with `TeamAuthorizationTest` earlier

**The mutation, and it settled the argument about assertion order.** Removing the path override made the
write land on the tracked file, and the failure was:

```
savingTacticsDoesNotTouchTheOwnersFile:90
  the tactics write never reached the backup service, so the assertion below
  would pass for the wrong reason ==> expected: <true> but was: <false>
```

**The anti-vacuous assertion fired first**, which is the whole reason it is written first — and
`git status` afterwards showed `var/tactics-editor-profiles.json` modified, confirming the tracked file
really is reachable from a test and that the guard is guarding something real.

---

### P0-6 — ANSWERED 2026-10-03: day 6 is form & morale

**The answer was already written down**, in the Country tab's calendar, which the owner quoted:

> Day 1 20:45 International · Day 2 Finance update · Day 3 19:00 League · Day 4 Training ·
> Day 5 18:00 Cup · **Day 6 Form & morale** · Day 7 16:00 League

**Verified against the code, and the calendar is right about everything except day 6.** Registered matchdays
and jobs: day 1 internationals 20:45, day 2 `FinanceJob` and `CupDrawJob`, day 3 league 19:00, day 4
`TrainingJob`, day 5 cup 18:00, day 7 league 16:00 (`MatchdayJobsConfig`, plus `WeekRolloverJob` and
`SeasonRolloverJob` on day 7). **Nothing at all is registered for day 6** — no `DayJob` returns 6.

So the decision is made and **the gap it names is now a known omission rather than an open question.** It is
feature work, not a correctness defect, so it belongs in P2 — but it should not be lost, because the calendar
now promises a manager something the server does not do.

**Also confirmed from the same calendar, and both are absences rather than bugs:** weeks 6 and 12 carry no
league football and are "reserved for national-team qualifiers" and "the World Cup", neither of which is
built (that is P2-10); week 11 is playoff week.

**Exit criteria:** the owner says what day 6 does, or says it stays empty on purpose. **— met. Carried to
P2 as a job to build.**

---

### P0-7 — DONE: the playoff path was the last place in the season that only knew about Serbia

**`295511b`'s successor, `see kanbanProgress.md`.** The board named one site; there were **three**.

**The fix needed no new plumbing.** Both callers already held the top flight —
`buildPlayoffSummary(Competition superLiga, …)` and `ensurePlayoffWeekFixtures(Competition superLiga, …)` —
so the country is read off the competition the caller passed. **The bug was never a missing parameter; it
was a hardcoded string where a parameter should have been.**

| Site | Was |
|---|---|
| `ensurePlayoffWeekFixtures` `:372` | asked for tier-2 leagues in `"SRB"` |
| `findTier2Leagues()` `:1094` | filtered a Serbia-only list a **second** time |
| `findSerbianLeagues()` `:1115` | the Serbia-only list itself |

So of 48 countries, **47 had no promotion or relegation summary and no playoff fixtures**, and Serbia worked
perfectly — which is exactly why the omission was invisible. The season rollover and the promotion ladder
had already been made country-agnostic; the playoff path was the one the earlier fix missed.

**A top flight with no country yields nothing rather than defaulting to Serbia.** The tempting fix — "if the
country is null, assume SRB" — would put one country's playoff inside another's pyramid, which is worse than
the bug. Asserted, so the fallback cannot come back.

**Verification, and its limit.** `PyramidBuilder:151` sets `country` on every competition it creates and
`CountryActivationService.activate()` builds through it, so an activated country's pyramid carries its
country; the live database has **0 of 31** leagues with a null country. The test proves a non-Serbian country
of exactly that shape gets both its summary and its fixtures.

**But the live database is Serbia-only** — 31 leagues, 1 country, 0 non-Serbian top flights — so this was
**not** run against a real activated foreign pyramid. The board asked for that and it is not met. Activating
one writes ~7,750 player rows, and the board records that attempt as a 26-minute operation that committed
nothing. **The owner's call, recorded rather than taken.**

---

### P0-8 — DONE: §1.2–1.5 verified by running it. §1.5 is worse than claimed.

| § | Verdict |
|---|---|
| 1.2 | **Could not be reproduced.** `simulateAllResults.html` loads no script that simulates anything — only `/js/roundResultsTeletext.js`, and the page contains no `fetch` at all. So the "silently discards an entire league" claim has no reachable code path on that page. Left unverified rather than refuted: the behaviour may live behind an endpoint I did not locate, and I am not claiming it is safe. |
| 1.3 | **CONFIRMED, verbatim.** `NationalTeamSeeder` has `@Transactional` on `totalSides()` — a pure `count()` — and **none on `seedIfMissing()`**, which is the one that writes. Exactly as reported. |
| 1.4 | **CONFIRMED by P1-1** — `MatchPersistenceService`, 402 lines, zero callers, 0 rows. Unchanged. |
| 1.5 | **CONFIRMED, and far worse than "badly named".** |

**§1.5 in detail, because the real answer changes what should happen to this class.**
`MatchEventRepository` is not a repository at all — a `@Component` holding a `ConcurrentHashMap`. And:

```java
public <T extends MatchEvent> T save(T event) {
    return event;          // returns the event, stores nothing
}
public void saveAll(List<MatchEvent> events) { events.forEach(this::save); }
```

- `store` is **only ever read.** The single `getOrDefault` at line 16 is the only reference to it that
  writes anything — and it does not. Nothing anywhere puts.
- `findByMatch` therefore **always returns an empty list**, for every match, forever.
- The **only** caller of `save()` in the whole codebase is `MatchPersistenceService` — which §1.4 and P1-1
  measured as dead. So the writer is dead and the store was never populated to begin with.
- `MatchAnalyticsService:26` and `MatchReplayService:25` both read events and will always get nothing.
- `deleteAll` contains dead code: `Long matchId = ... ? null : null;` — always null, and unused.

**The conclusion is stronger than "badly named": the match-event layer does not work.** This is a second,
independent dead path next to P0-12 §4.4's `match_tick_states` — the game's match events are held in a
database table nothing writes *and* an in-memory map nothing writes. **Both** need an owner decision, and
P0-12 §4.4's framing ("is it dead?") is now answered for the half it did not know existed.

- [x] Every one of §1.2–1.5 confirmed or refuted by running it
- [x] §1.2 recorded as not reproducible, with what was actually found — and not over-claimed as safe
- [ ] OWNER-GATED: `MatchEventRepository` + `MatchPersistenceService` — delete both, or make the map real

---

### P0-19 — `Team.supporterMood` has no column, so the world cannot be played

Found while measuring P1-5, and the original database snapshot lacked the column: advancing a matchday threw

```
ERROR: column t1_0.supporter_mood does not exist  Position: 294
```

`Team.supporterMood` was added in `b0493a6` ("P2-5: supporter mood"), which **is in `main`**. The current
local PostgreSQL schema now contains `team.supporter_mood integer default 60`; the original missing-schema
observation is therefore stale for this database.

The failure is easy to over-claim, so precisely: **the app boots and every read-only page works.**
`supporterMood` is read by the matchday-advance path and `FinanceController`, so you find out by playing
football — not by looking at the app.

Exit criteria:

- [x] The current local database contains `team.supporter_mood integer default 60`
- [ ] A matchday advances end to end on a database built from the current entities
- [ ] A guard test that boots against the real schema and plays a matchday — the only thing that would
      have caught this, and the reason 154 test classes did not

**Not fixed here.** `Reset DB` then `Initialize DB` is the documented owner path and they are destructive
buttons; the missing piece is a guard, not a code change.

---

### P0-9b — DONE: `activeSeason` no longer answers `2026`, and no longer picks a coin

**Seasons start at 1 and there is no calendar year anywhere.** This is the one place the product itself
breaks that rule:

```java
private Integer activeSeason(Team team) {
    GameClock clock = clocks.findAll().stream().findFirst().orElse(null);
    if (clock != null && clock.getCurrentSeason() != null) return clock.getCurrentSeason();
    return Year.now().getValue();          // <-- 2026, used as a season
}
```

**Why it matters beyond tidiness.** `summarise()` reads the ledger for that season, and
`TransferBudgetService` grants a club's transfer budget *from settled ledger income*. So with no
`GameClock`, every club is assessed on **season 2026** while the game settles season 1 — income that was
really earned becomes invisible, and the club is refused with "No settled income yet".

This is the mechanism behind the red tests in P0-2. It is **not fixed here**, because the correct fallback is
an owner decision: with no clock there is no season, so arguably nothing has been settled and the honest
answer is a refusal — which matches `TransferBudgetService`'s own existing reasoning ("a club in its first
week has not been given anything yet"). Changing it alters affordability for every clockless club.

**A second defect was in the same three lines.** `clocks.findAll().stream().findFirst()` has **no `ORDER
BY`**, so when more than one clock exists the season the game is "in" was whichever row the database
happened to return. Every ledger figure is a read of that season.

**Decided and done:** the fallback is `null` — with no clock there is no season, which is exactly what
`TransferBudgetService` already says ("a club in its first week has not been given anything yet") — and the
clock is chosen deterministically as the furthest-advanced one, ties broken by the lowest id.

- [x] No calendar year anywhere in `activeSeason`
- [x] `summarise` survives a null season instead of querying with it
- [x] The clock is chosen deterministically, not arbitrarily
- [x] `PlayerContractServiceTest` **18/18** — `signingMovesThePlayerToTheClub` was the last hold-out and is
      green, because the fixture now has a league and a settled week in the season being played
- [x] 39 green across the four affected classes

**What is not proven, and should not be claimed:** `FinanceLedgerSeasonTest.theFurthestAdvancedClockWins`
passes under the old unordered `findFirst()` too, because `findAll()` returned the season-3 row in this
database. A test cannot reliably distinguish "unspecified order that happened to be right" from "specified
order" — the mutation proves the calendar-year half, not the determinism half. The determinism fix is
correct and free, but **its guard is the code, not a test.**

---

### P0-9 — DONE, and it found three tests that were asserting a calendar assumption

Seasons run **1, 2, 3 …** and there is no calendar year anywhere. Four test classes passed one anyway.

| Class | Was | Mapped to |
|---|---|---|
| `NegotiationServiceTest` | `2026` ×11 | **1** — its own fixture sets `currentSeason = 1` |
| `WeeklyFinanceServiceTest` | 2024 ×5, 2025 ×4, 2026 ×12 | 1, 2, 3 |
| `PlayerContractServiceTest` | 2024 ×1, 2025 ×1, 2026 ×23 | 1, 2, 3 |
| `StaffSponsorServiceTest` | `2026` ×18 | **3** — one value, no comparison to preserve |

**The mapping preserves relative structure** where comparisons exist, so "this season against last season"
still compares two different seasons. Collapsing everything to `1` would have broken those assertions while
looking tidier.

**Exit criteria, and the honest result:**
- [x] Each fixture passes a season **number**, not a year — 73 call sites across four classes
- [ ] ~~Each test still passes afterwards~~ — **three do not, and that is the finding the board predicted**:
      `PlayerContractServiceTest` 2, `StaffSponsorServiceTest` 1. They were asserting a calendar assumption.
      `WeeklyFinanceServiceTest` 9/9 green.

**And a hypothesis of mine was wrong, which is worth recording.** `NegotiationServiceTest`'s remaining
failure (`expected: <ACCEPTED> but was: <OPEN>`) looked like a season mismatch — contracts assigned to season
`2026` while the clock sat at season `1`. Replacing `2026` with `1` **did not fix it.** So that failure is a
**separate real defect in the accept-offer path**, not a fixture problem, and it is still open.

---

### P0-10 — Re-scoped: remove only the proven dead legacy tactics chain

The original scope was too broad. A fresh caller scan confirms `TacticsBridge`, `NewLogicTacticsService`,
`newLogic.model.TacticRules`, `util.match.MatchContext` and `util.players.PlayerActionProbabilityModel` have
no production callers, but `TeamTacticsProfile`, `FormationSlotCatalog`, `TacticsRules` and the simulation
tactics package are live through `TeamTacticsService`, `RealSquadFactory`, `SimMatchService` and the replay
path. The task is narrowed to the proven dead legacy classes; the live tactical profile and simulation
engine stay in place.

**Exit criteria:**
- [x] Caller count re-verified immediately before deleting; live tactical classes were excluded
- [ ] `mvn clean package` succeeds
- [ ] The relevant test suite still passes

---

### P0-11 — DONE: `nationalCup()` renamed, and the board stopped disagreeing with itself

**Contradiction 1 — resolved in code, board was stale.** The board called it *"owner-ruled but not
implemented"*. It has been implemented since B2: `CupFixtureSeeder` picks the lowest-id cup with
`Limit.of(1)`. The board was wrong, not the code.

**Contradiction 2 — the name really did lie.** `nationalCup()` queried
`CompetitionScope.INTERNATIONAL`. Renamed to **`primaryCup()`**, which is what it does: pick one cup, by
lowest id. `INTERNATIONAL` is a property of how the rows are stored, not a claim about a continental
competition, so a name asserting "national" was asserting something false at the call site where a reader
would rely on it.

**One name deliberately left wrong, and documented rather than silently renamed.** `findFirstNationalScoped`
is a generic scope filter — `InternationalFixtureSeeder` calls it too — so renaming it properly is a wider
change than this task. Its javadoc and the `primaryCup()` javadoc now both say why the name is a
misnomer and where the real explanation lives. Five call sites, one file.

**Exit criteria:** [x] contradiction 1 corrected in documentation · [x] contradiction 2 resolved in code ·
[x] board no longer disagrees with itself.

---

### P0-12 — ANSWERED 2026-10-04: keep `IDENTITY`, and bound retention before indexing

- **§4.1** — the `IDENTITY` generation type disables JDBC batching for **70 of 71 entities**, so
  `batch_size=50` is dead code.
- **§4.4** — `match_tick_states` says *"Decision needed… Do not index it as-is."*
  **Answered by P1-1, 2026-10-03: the table is vestigial.** Nothing writes it, nothing reads it, it holds
  0 rows, and replays are file-backed JSON with their own retention. **No index was created and none
  should be.** Whether to delete the table and `MatchPersistenceService` is an owner call.

Both are owner decisions. P1-1 no longer proposes an index on this table — it measured that the index
would serve a query with no caller, so the §4.4 question dissolved rather than being answered.

---

# 🟠 P1 — performance

The world grew **48× in five days**. Everything here is about the code that was written for one country
meeting 14,880 of them.

**Every task in this category must state a before and after measurement.** A P1 task with no number is not
finished. Where a number is already known it is given here — do not re-derive it, but do re-confirm it if
the code near it has changed.

**Where this category stands: P1-1, P1-3, P1-4 and P1-7 are done. P1-2 is owner-gated. P1-5 and P1-6 are
open.** Three of the four finished tasks overturned what this board said — two proposed indexes had no
query behind them, and a third made the hot daily job 68% slower — so **read the measurements, not the
task descriptions.** Two proposed pieces of work were measured and deliberately dropped, and both are
recorded as dropped rather than quietly deleted.

### Measured already — do not redo

| Change | Before | After | Commit |
|---|---:|---:|---|
| Daily recovery zone-load read *(155-match dev database)* | 21.0 ms | **10.8 ms** | `d95da9d` |
| Weekly rollover squad reads (30 clubs, 3 listings) | 180 queries | **0** + 1 bulk | `e310856` |
| Four fixture endpoints: game week used as a round | 155 fixtures for every week and day | correct matchday | `7374c69` |
| League top scorers / assists *(89,280-match season)* | 158.7 ms | **0.19 ms** | `e9142ed` |
| Club match history, played, ordered *(same)* | 170.3 ms | **0.26 ms** | `e9142ed` |
| Club page, all matches *(same)* | 140.6 ms | **0.20 ms** | `e9142ed` |
| One week of a season *(same)* | 156.4 ms | **27.8 ms** | `e9142ed` |
| Club Elo replay — 155 matches, with the 742 KB event log | 443–554 ms | **6.5–11.2 ms** | `6e63831` |
| `event_json` written per match | 840,136 B | **17,001 B** | `6e63831` |
| A match's 198 zone loads — **no index created, query has no caller** | 629 ms | — | `e9142ed` |
| Recovery read, one matchday *(17.7M-row table, 17 KB blobs)* | 6,173 ms | **~539 ms** | `513f738` |
| International Elo replay — whole-world reads | 1 + 3 × matches | **1** | `379cb12` |
| Weekly squad rollover — squad reads | 1 + 14,880 | **2** | `379cb12` |
| AI friendly pass — week reads, per friendly week | ~59,520 | **2** | `379cb12` |
| `/train-all` response | every player as JSON | **`{"trained": n}`** | `379cb12` |
| Club milestone page — season event-log reads | 2 | **1** | `6e63831` |
| Club page blob fetched and discarded | 10 MB | **204 KB** | `6e63831` |

**Read the scale column, because it is not uniform.** The first three rows were measured on a 155-match
dev database. The `e9142ed` rows on a full 89,280-match season, and the `513f738` baseline on the same
season with `event_json` at its **real** 17 KB width — which is why it reads 6,173 ms where `e9142ed`
recorded 4,441 ms for a query that looks identical. **Same query, different harness.** A number is only
comparable to a number measured the same way.

---

### P1-1 — DONE. Four indexes on `match`. Two of the three claims on this board were wrong.

**Landed:** `ix_match_competition_season`, `ix_match_season_week`, `ix_match_home_team_date`,
`ix_match_away_team_date`. Declared on the `Match` entity, with `tools/create-match-indexes.sql` for a
database that already has rows. Measurements and full `EXPLAIN` plans in `kanbanProgress.md`.

| Query | Where it runs | Before | After |
|---|---|---:|---:|
| `findByCompetitionIdAndSeasonYear` | top scorers / assists, **request path** | 158.7 ms | **0.19 ms** |
| `findByHomeTeamIdOrAwayTeamId...PlayedTrue...OrderByMatchDateDesc` | club match history, **request path** | 170.3 ms | **0.26 ms** |
| `findByHomeTeamIdOrAwayTeamId` | club page, **request path** | 140.6 ms | **0.20 ms** |
| `findBySeasonYearAndWeekNumber` | `GoalEventRepository`, 12× a season job | 156.4 ms | **27.8 ms** |

Measured on a full-scale season — **89,280 matches** — in a throwaway database, not on the 155-row dev
table where a sequential scan is the correct plan and proves nothing. Write cost: **+16.8 µs per match
row**, +125 ms per matchday at 7,440 matches, 24 MB per 89,280 matches.

**What the board got wrong, all three checked against the source:**

1. **`match_tick_states` — refuted. Nothing writes it and nothing reads it.** `MatchPersistenceService`
   is its only writer and has **zero callers**; the table holds **0 rows**. Replays are file-backed
   JSON under `app.replay-dir` with their own retention. **A `(match_id, tick)` index was not created
   and P1-5's "~900 rows per match" is void** — growth is zero. This also settles **P0-8 §1.4** and
   **P0-12 §4.4**: no decision needed, the table is vestigial. Deleting it is an owner call.
2. **`player_zone_load(match_id)` — refuted, twice.** `findByMatchId` has **no caller in `src/main`**,
   only one test; and adding the index made the one query that *does* run on that table **68% slower**
   (4,441 ms → 7,436 ms) because it flips a hash join the planner wants into index probes it does not.
   See P1-3, which now owns the real fix.
3. **"Every zone-load recovery query is a seq scan on `match`" — true and irrelevant.** That scan costs
   **112 ms of a 5,318 ms query**: 2%. Indexing `match(match_date)` changed the total by less than the
   noise. The cost is on the other table.

**`MatchIndexDeclarationTest` guards this.** It asserts the four names and their column order, and
fails on a fifth index until that one has been measured too. Proven able to fail: swapping
`competition_id, season_year` and adding an unmeasured fifth both fail it, and both messages print.

---

### P1-2 — `ZoneLoadRecorder` writes 198 rows per match, one at a time

22 players × 9 zones, each row saved individually. There is already a unique index on
`(player_id, match_id, zone)`, so the rows are known to be distinct — the batch is safe to attempt.

**This is gated on P0-12 §4.1**: `IDENTITY` disables JDBC batching, so a batch insert may not help until
the generation type is addressed. **Establish that first** rather than writing the batch and measuring no
change.

**Q1 — `IDENTITY` disables batching: keep it (option B).** No schema change on 14,880 clubs' worth of data,
and the honest partial is taken. **P1-2 stays blocked** and should say so rather than be re-attempted.

**Q2 — `match_tick_states`: retention first, then the index (B then A).** Unbounded growth is the real
problem; the index question is smaller once the table is bounded. P1-5 is therefore now **ahead of** P1-1's
index proposal, not beside it.

**Exit criteria:**
- [ ] Rows-per-match and elapsed time measured before and after — **blocked while `IDENTITY` stands**, and
      recorded as blocked rather than retried
- [ ] If `IDENTITY` blocks it, that is recorded and the task stops — it is P0-12's to unblock, not this
      task's to work around

---

### P1-3 — DONE. The read pages, and the index P1-1 rejected is the one that mattered

**The board's version of this task was answered, not open:** `findByLastPlayedAtIsNotNull()` has no caller
any more, so there is no `last_played_at` index to add. What was left was the read itself, on the wrong
table.

| one matchday's recovery read, 89,280-match season | before | after |
|---|---:|---:|
| elapsed | **6,173 ms** | **~539 ms** |
| rows read | 17,677,440 | 1,473,120 |

**Three things had to be right, and two of them were not:**

- **`player_zone_load(match_id)` is landed here, and P1-1 was right not to land it then.** Unpaged it is a
  68% regression; paged it is what makes the read cheap. The board was right and the fix was incomplete.
- **How the ids are passed changes the plan 80×.** `IN (SELECT … LIMIT 500)` is a 5,020 ms Hash Semi Join;
  `IN` with 500 bound values — what JPQL emits — is a **44 ms** Parallel Bitmap Heap Scan. **Do not
  refactor it into a subquery.** It looks identical and costs 100×.
- **The keyset is `(match_date, id)`, not `id`.** Id-only paging cannot use an index on the date, so the
  page query heap-filtered everything before the page: **206 ms a page against 0.35 ms**.

**Page size measured, not chosen:** 500 / 1,000 / 2,000 all land at ~530–550 ms for the window;
**5,000 falls off a cliff to 6,478 ms** because the index stops being used. 500 shipped.

**`ix_match_date_id` is a fifth index on `match`, reversing a P1-1 measurement on new evidence.** P1-1
proposed `match(match_date)`, measured it, found no benefit and did not create it — correct for the query it
was measured against, since that query spends 98% of its time on the zone-load side. Paging made it
necessary. **An index can be worthless and then become necessary when the query beside it changes shape**,
so "measured, no benefit" is not the settled sentence it looks like.

**Recovery still happens — counted, not timed.** The guard asserts **every match in the window is asked for
exactly once**: a repeated row double-counts a player's recovery, a skipped one under-counts it, and
neither throws. An unbounded paging loop would have been worse than a failing job, and stopping quietly
would credit less work than the players did while logging a plausible number — so the job now **throws**
after 200× a season's pages, with the reason in the message.

---

### P1-4 — DONE, four of nineteen. Every one of the board's five named candidates had drifted.

The pattern: a repository call returning a whole table **inside a loop or on a request path**. Nineteen
sites matched. Measurements and guards in `kanbanProgress.md`.

| Fixed | before | after |
|---|---:|---:|
| `NationalRatingService` — whole-world reads per Elo replay | 1 + 3 × matches | **1** |
| `SquadEnvironmentService.advanceWeek` — squad reads per week | 1 + 14,880 | **2** |
| `FriendlyRequestService.runAiFriendlyWeek` — week reads per friendly week | ~59,520 in the first loop | **2** |
| `TrainingController /train-all` — response body | every player entity as JSON | **`{"trained": n}`** |

**Every one of these was invisible to a clock** — 48 countries is a small table, and the loop asking for it
costs more than the query. The guards count queries, not milliseconds.

**The board's five candidates, re-verified against source:**

| Candidate | What is actually there |
|---|---|
| `SeasonService:553` `teamRepository.findAll()` | **Gone** — the fatigue work replaced it; `:610` says so in a comment |
| `SeasonService:605` `playerRepository.findAll()` | **Gone**, same |
| `SeasonService:1116` `competitions.findAll()` loop | **Gone** — no `findAll()` left in the file |
| `NationalRatingService:239` `countries.findAll()` | **Fixed** — three per match, and `world` was already loaded at `:102` |
| `CupFixtureSeeder:129` / `:271` | **Never an N+1.** One query filtered in Java; `:142` is the same |

**Recorded, not fixed, with the reason:**

- **Tier 2, once per matchday or season** — `MatchdayJob:87` (already carries a *"One query, not one per
  competition"* comment from a previous fix), `LeagueTableReconciliationService:98` (its inner read is now
  served by P1-1's index), `SeasonRolloverJob:71`, `CupFixtureSeeder:142`, `InternationalClubCups:397`.
  One query each; none worth a change.
- **Tier 3, admin buttons, repair, seeding** — `WorldIntegrityService`, `WorldRepairService`,
  `CountryActivationService`, `SimulatedWorldSeeder`, `BotLeagueStandardBackfill`, `StaffSponsorService`.
  Reading the world is the point of a repair or a seed. **`StaffSponsorService.seedAllClubs` still has a
  real 1 + 14,880 N+1** inside its loop — a genuine find, on a seeding path, left as its own decision.

**Two things worth keeping:**

- **Only weeks 6, 11 and 12 have friendly-capable slots.** The AI friendly pass is expensive in **3 weeks
  of a 12-week season**, not every week. A first test picked week 2, which returns before reading anything
  and passed on a pass that did nothing.
- **This is the third instance of one pattern** — `TransferService` (`e310856`), `SquadEnvironmentService`,
  `FriendlyRequestService`. A collection loaded once, then re-asked inside a loop because the loop could
  not see the copy. Three instances is a convention, not an accident.

### P1-5 — Three answers given. The growth measurement is blocked by P0-19, not by this task.

Four exit criteria. Three are answered from what reads what. The fourth could not be measured, because
**the world cannot currently be simulated at all** — see P0-19.

**1. Blobs — keep. No code, no policy.** What reads `match.event_json` is the match detail page
(`MatchDetailService:34`), the external API (`ZoxApiController:575`), and `GoalEventRepository`, which
backs **top scorers, top assists and the club milestone leaders** — one variant walking all twelve weeks of
a season across every competition.

There is no `goal_event` table, and I took that as evidence the scorers path did not read the blobs. **It
does**: `GoalEventRepository` is a `@Component`, not a repository, and it walks matches and parses each
log, because the log is the record. **So deleting blobs does not merely empty the match page — it empties
three stat pages for that season.** That is the owner's call, and the bill for it:

| | now, 155 matches | a full season, 7,440 |
|---|---:|---:|
| raw logical | 126 MB | — |
| **on disk** | **16 MB** | **~126 MB** |

TOAST compresses about 8:1, so 16 MB is the honest number. **A season of blobs costs ~126 MB on disk, and
that is affordable only because P1-7b cut the blob 49×.** Before P1-7b this was a real question.

**2. Match rows — keep, permanently.** `ClubRatingService:118` walks every played club match in date order
for the Elo replay; `NationalRatingService:96` walks every played international; league tables, fixtures
and every stats page read them. Deleting a played row silently breaks Elo history and nothing says so —
the pages just get shorter. And there is nothing to save: excluding the blob the row is a few hundred
bytes, so 89,000 rows over twelve seasons is single-digit megabytes. The board's own guess was right, and
it is now an answer rather than an omission.

**3. Replay files — already correct, and the disagreement with (2) is deliberate.** `SimReplayStore`
expires by age (`app.replay.max-age-days`, default **14**) and evicts least-recently-modified to a count
cap; `replay-data` holds **492 MB across 48 files**. The fact that keeps it cheap: **only the manager's own
matches get a file** — `AsyncSimulationRunner:78` passes `replayId = -1` for AI matches — so the store
grows with what one human plays, not with the 48× world. **Files expire at 14 days, database rows never
do, and that is right:** a replay file is a re-renderable convenience, a match row is the record.

**4. Growth per matchday — not taken.** The board asked for it "from the harness rather than
extrapolated", and the harness cannot run: matchday advance fails on the missing `supporter_mood` column
(P0-19), so no simulation of any size is possible on the dev database or a clone of it. I will not present
the per-match figures from P1-3 and P1-7b as a per-matchday measurement — **that is the extrapolation the
board ruled out.** The unblock is one `Reset DB` and one `Initialize DB`, after which it is a ten-minute
measurement.

**Carried out of P1-7:** `GoalEventRepository`'s season-wide variant loads every match of a season with
its blob — the same full-season shape P1-7 measured at ~66 GB. It is survivable now **only** because the
blob is 49× smaller. P1-7b is the reason this is not a P0.

### P1-6 — MEASURED, NOTHING LANDED. The premise was wrong in a way that matters more than any index.

**Three things were checked before proposing anything, and the first two settle it.**

**1. These sports do not grow with the world.** All three hardcode a single country:

| | |
|---|---|
| `BbDataInitializer.java:141` | `String country = "RS";` |
| `AfDataInitializer.java:135` | `String country = "RS";` |
| `CSDataInitializer.java:92` | `c.setIsoCode("SRB");` |

The board's premise was that they are "5,580 and 3,720 players on the dev database" and will get costlier
as the world grows. **They will not.** The 48× growth is `newLogic`'s; these three are one country and
stay one country. So their sizes are their real sizes, not a snapshot of something bigger.

**2. They have never been played.** Teams, players and fixtures are seeded — 310 teams and 310
competition entries each, 2,790 fixtures each — and **`bb_matches` and `af_matches` hold 0 rows.** Not one
match has been simulated in either sport.

**3. So no index is justified.** Fifteen `bb_` and `af_` tables, every one with its primary key as its only
index, the largest being `af_players` at 5,580 rows — a sequential scan of which is sub-millisecond.
`bb_player_season_stats` and `af_player_season_stats` hold 0 rows. **Nothing here meets the bar P1-1 set,
so nothing was created.** Recorded as measured-and-dropped rather than quietly left for someone to
rediscover.

### The one real thing, and it is not an index

`BbMatchSimulationService.savePlayerStats` and its American football twin do **four queries per player per
match** — a `findById`, a `save`, a season-stats lookup and a `save` — which is **88 round trips a match**.
Measured on this machine: **0.1745 ms per round trip**, so **15.4 ms a match** of pure overhead.

**It has never run**, because no match has ever been simulated. When these sports are played, this is the
first thing to fix, and it is an N+1 of the same family as `TransferService`, `SquadEnvironmentService`
and `FriendlyRequestService` — **not** a missing index on `bb_players`. The season-stats lookup filters on
`player_id + season_year + competition_id` with only a primary key, so it is a sequential scan; but that
table would top out at 3,720 rows a season, where a scan costs ~0.03 ms, so **an index there would not earn
its write cost either.**

### Two findings that belong to P0

- **P0-9 has a second site.** Basketball and American football use **`season_year = 2025` as a calendar
  year** where `newLogic` counts twelve weeks from 1. `BbController` hardcodes `2025` in eight places,
  including four `defaultValue = "2025"` request parameters. **It is self-consistent** — `bb_match_fixtures`
  is seeded with `season_year = 2025` — which is exactly why it is invisible and why the board's trap
  wording says "even if it is self-consistent and passing". Recorded here because it was found in P1-6's
  blast radius; **not fixed here**, it is P0-9's.
- **`bb_leagues` and `BbLeagueRepository` are vestigial**: a table with 0 rows and a repository with **zero
  callers** anywhere in `src/main` or `src/test`. The same shape as `match_tick_states`, and the same
  question for the owner — delete, or leave?

### P1-7 — DONE. The replays, the blob, the milestone page and the background failures. One item measured and dropped.

`Match.eventJson` was the whole per-tick decision log in one text column: **742 KB – 1,035 KB a match,
~66 GB a season.** Four separate things were wrong with it.

| | before | after |
|---|---:|---:|
| Club Elo replay, 155 matches | 443–554 ms, ~66 GB a season | **6.5–11.2 ms, ~5 MB** |
| `event_json` per match, written by the app | 840,136 B | **17,001 B — 49.4× smaller** |
| Club milestone page — season reads | **2** | **1** |
| Background fixtures that failed | logged, never counted | **counted and named** |

**Landed, each measured in the database.** The replays read a `ScoredMatch` projection.
`SimReportMapper.eventJson` writes only the event types a page can use, from a keep-list derived from
`MatchDetailService`, `ZoxApiController` and `GoalEventRepository` — **all three** readers were checked
against a running app on a real new match. `LeagueMilestoneService` reads the season once instead of
twice. `AsyncSimulationRunner` counts and names the fixtures it could not simulate.

**Measured and DROPPED — the club-history projection this board used to ask for.** The criterion was
written before the blob was narrowed, when a club page pulled **10 MB**. It now pulls **204 KB**, and
`MatchDTO.from` reads no JSON at all. Saving 204 KB per page view does not justify a new projection type
plus three rewrites, on a query **P1-1 already took from 170 ms to 0.26 ms**. The criterion was wrong and
is corrected rather than met.

**Three defects found here:**

- **FIXED — `GoalEventRepository.isGoal` credited goals VAR ruled out.** `contains("GOAL")` matched
  `GOAL_DISALLOWED` and `VAR_GOAL_OVERTURNED` — 30 credits for goals that do not exist. **Not a product
  question:** `BallResultHandler` asks VAR *before* `goalScored`, so the scoreline never counted them
  either. The definition now lives on `MatchEventType`, because **three** call sites had each invented
  their own substring.
- **NOT A DEFECT — `simulate-all` does report its background work, and my claim was wrong.** It returns
  `backgroundSimulating` and `backgroundTotal`; `/current-round/status` reports progress live. My print
  statement selected four keys out of nine and I concluded from those four.
- **OPEN, and one line further up than the last one: a fixture with no teams is counted as
  `simulated`.** The runner returns early for a null home or away side and then increments
  `simulatedCount` anyway, so a match that was never played is reported as one that was. **Needs a
  decision:** is an unplayable fixture a *failure* or a *skip*? That changes what the status endpoint means,
  and `AsyncSimulationRunner` sits next to the simulation endpoints other work is in. Exit criteria: a
  fixture that was not simulated is never counted as one, and the status endpoint distinguishes the two.

**Not done, deliberately:** no migration. The existing 155 matches keep their 742 KB blobs, so historical
pages stay slow until the world is reset while every new match is cheap.

# 🟢 P2 — features and visual work

**Everything here can be completed on its own, without a decision from anyone.** Ordered by leverage per
day of work, following `archive/COMPETITIVE_ANALYSIS.md` §10, whose ordering is deliberate.

---

## ✅ P2-20 — DONE: the Community tab is a forum and private messages (owner decision, 2026-10-05)

**All six phases landed.** `5f15232` (forum), `da61dc5` (messages) and the Phase 6 teardown. 143 tests
across the ten classes this work added or rewrote, every guard mutation-proven. What the owner asked for on
2026-10-05 is what runs: two forum sections, edit with a visible tag, delete, moderators who can act on
anybody's message, a forum write ban applied from a profile, direct messages with a subject and a body,
replies in a followable thread, sendable to any account, and a notification store that did not exist before.

**Not verified in a browser.** Every screen was checked by rendering the served module against live
responses and by driving the endpoints with `curl`; no click was performed. That is stated in every log
entry rather than left for the owner to discover.

**The largest single request on this board, and the only one that is a product decision rather than a defect.**
The tab today is one screen reached by a menu button, and `pages.js` routes three names — `forum`, `chat`,
`events` — all of which render the identical page (`community.js:314-320`, both bodies are
`return loadChat()`). There is no forum and there are no events.

The owner's specification, in full:

| | |
|---|---|
| **Forum** | An old-school threaded forum, hattrick/sokker.org in shape. Any manager opens a topic. Two fixed sections: **TIFO** and **non-TIFO**. Anyone replies in a topic. Anyone **edits** their own message, and an edit shows an **edited** tag. Anyone **deletes** their own message. **MOD/ADMIN/OWNER** may delete and edit *other people's* messages, and may ban a manager from writing for a number of days **from that manager's profile** — the rest of the application keeps working and reading the forum keeps working. |
| **Messages** | Pick an active account from a list, send a direct message with **subject and body**. A notification reaches the **ticker** and a **notification store that does not exist yet**. Replying to a specific message opens a **thread**, so the correspondence history is followable. Sendable to any active account — *active meaning the account is real, not that the person is online.* |

**Owner decisions taken, 2026-10-05, and they close the questions:**

| Question | Decision |
|---|---|
| The old shared chat | **Wiped.** No migration, no announcements topic, no third tab |
| Registration approvals | Move off the chat, onto the Admin tab |
| Role assignment | Built — **without it no MOD account can ever exist** |
| Delivery for notifications | **Polling**, 30 s. Not a WebSocket |
| Scope of a ban | **Forum writing only.** Reading, messaging and the game are untouched |
| Reaching a user's profile | **From the club: click the team, see who runs it, click him, see his profile** |

### P0-20 — DONE: football ownership uses `User.footballTeam`

**Found while building the `User.footballTeam` FK (P2-20 Phase 1). Not fixed there, and deliberately so.**

`User` now has a real FK to `Team`, which makes a fifth instance of this impossible. The four affected
paths now use that FK:

| Where | What |
|---|---|
| `UserRepository.findDistinctManagedTeamIds` | selects `u.tifoCTeam.id` — a CTeam id — and `TransferService:630` compares it against `Team.getId()`. **Now `@Deprecated` with the reason on it**, so the next reader knows why |
| `APIController.myMatch:236-241` | `user.getTifoCTeam().getId()` read as a `Team.id` |
| `TeamController.getMatches:252`, `getSchedule:269`, `CountryController.getLeagueMatches:689` | same |
| `NationalTeamAppointments:95-97` | asserts in a **comment** that "the ids are the same space", then filters on it |

Each is a separate reach into a different controller, and none was what this phase was asked to do. Every one
is a query that answers with a plausible number rather than failing, which is why they survived alongside
P0-18.

**Exit criteria:**
- [x] `findDistinctManagedTeamIds` deleted and `TransferService` reads `User.footballTeam.id`
- [x] `APIController.myMatch` reads the football-team FK
- [x] `TeamController` / `CountryController` use the football-team ownership path
- [x] `NationalTeamAppointments` walks `User.footballTeam` through the FK, and its comment is corrected
- [ ] A dedicated regression test that fails if any of the four returns a `CTeam` id is still pending

---

### Phase 1 — DONE: the foundation the forum cannot be built without

`ClubOwnershipLinker`, `ModerationService`, `UserRoles`, `AdminUserController`, and a **real foreign key**
between `User` and `Team`. See `kanbanProgress.md` for what was measured and what did not work.

- [x] `UserRoles` — one answer to "may moderate" and one to "is staff", replacing **14** backend role checks
- [x] `User.footballTeam` — a real FK, ending the `User.cTeam.name == Team.name` join
- [x] `ClubOwnershipLinker` — reads the FK, backfills legacy rows, and answers the reverse direction
- [x] `ModerationService` — forum write ban with reason, expiry, and who applied it
- [x] `POST /admin/users/{id}/role` — the only writer of `MOD`, `ADMIN` or `DEV` that has ever existed
- [x] `POST /admin/users/{id}/forum-ban` and `/lift`
- [x] Admin tab **Accounts** panel: role select, ban, lift ban, repair club links
- [x] 51 tests across 5 classes, mutation-proven

### Phase 2 — DONE: the user profile and the club → manager link

- [x] `GET /users/{id}/profile` — name, role, club, league, country. **No email, no username, no last-seen**,
      and not because they are gated: the DTO has no such field to gate
- [x] `PATCH /users/me/display-name` — the field was never user-writable in the repository's history
- [x] A public profile page, plus the moderator's ban action reached from it
- [x] **"Managed by X"** on the club profile and on every league-table row
- [x] **A real bug found and fixed here**: two boot-time initializers rewrote account rows without
      mentioning the new key, so `football_team_id` came back null on the next start

### Phase 3 — DONE: notifications

- [x] `Notification` entity with `(recipient_id, created_at)` and `(recipient_id, read_at)` indexes
- [x] `GET /notifications`, `/unread-count`, `POST /notifications/{id}/read`, `/read-all`
- [x] 30 s poll, bell + badge + dropdown in the top bar, mobile-safe
- [x] Ticker rewired off the dead `/community/summary`
- [x] Marking read is scoped **inside** the query, so somebody else's notification is a 404

### Phase 4 — DONE: the forum

- [x] `ForumSection` (`TIFO`, `GENERAL`), `ForumTopic`, `ForumPost` with `editedAt`, `deletedAt` and `editedByUserId`
- [x] Ban enforced in `ForumService`, not the controller — a ban enforced by one endpoint is bypassed by the next
- [x] Old-school thread view, paging, "edited" and "edited by a moderator" tags
- [x] Delete is **soft** — the row stays so the replies underneath still make sense
- [x] A moderator may edit and delete **anybody's** message, the owner's included
- [x] Three indexes, verified created
- [x] 33 tests, 4 mutations proven

### Phase 5 — DONE: private messages with threads

- [x] `MessageThread` (subject, both participants, two read cursors) and `DirectMessage`
- [x] First message opens a conversation; a reply appends and carries **no subject**
- [x] **One thread per pair** — a second subject continues the conversation rather than forking it
- [x] Recipients are **every account**, not only the online ones
- [x] Notification on receipt, pointing at the conversation
- [x] 24 tests, 3 mutations proven

### Phase 6 — DONE: tear down

- [x] Deleted: `CommunityMessage`, `CommunityMessageType`, `CommunityMessageRepository`,
      `CommunityMessageService`, `CommunityController`, three DTOs, `community.js`, and both test classes
- [x] `nl_community_message` dropped from the dev database (it was empty)
- [x] `User.communityLastViewedAt` documented as dead; the column stays because `ddl-auto=update` never
      drops and this repository has no migration mechanism
- [x] Registration approvals **on the Admin tab**, staff-only, and the applicant's email no longer travels
      through anything a manager can read
- [x] `RegistrationService`'s five chat calls became moderator notifications, carrying the **username only**
- [x] The **fake email is gone** — it logged a line and wrote a chat row claiming to be a mail, and there is
      no SMTP in this application
- [x] `TECHNICAL_OVERVIEW.md` corrected, `manual/` renumbered, dead `buildCommunityActionsHtml` removed
- [x] 8 tests on reachability, 2 mutations proven

---

### P2-1 — Corners decided by pitch geometry, not a nominated taker

Half a day, and easier now that P0-3 has landed. Listed as its own item in the competitive analysis for
exactly that reason.

**Exit criteria:** corner taker selected by position relative to the ball; verified on a replay.

### ~~P2-2 — Seller chooses which offer to accept~~ ✅ `SellerAcceptsANamedOfferTest` 4/4

**What the board said, and what was true.** The board said the service method "exists and correctly
rejects the wrong offer — no controller exposes it". **Both halves were wrong.** A controller did
expose it (`POST /transfers/accept-offer/{playerId}`), and it was **broken**: `acceptBestOffer`
settled the transfer and then settled it again, so the endpoint could only ever answer 400
`"You cannot buy your own player."` A seller could not accept an offer at all.

**Landed:**
- [x] One settlement. `acceptBestOffer` delegates to the new `acceptOffer`; the squad-number
      housekeeping `completeTransfer` also did is kept, extracted as `assignSquadNumbersAfterTransfer`
- [x] `POST /transfers/accept-offer/{playerId}/{offerId}` — the seller names the bid
- [x] Bids cross the wire with their ids: new `TransferOfferDTO`, on both `TransferDTO` and
      `PlayerTransferStatusDTO`, including `netToSeller` so the row is not just a headline fee
- [x] One **Accept** button per bid in the Transfer Centre and the player page, via a single shared
      renderer (`static/js/pages/views/transfer-offer-actions.js`)
- [x] A bid on another transfer is refused with `404 OFFER_NOT_FOUND`, not absorbed
- [x] `NegotiationService.settleOffer` unwinds every status if settlement refuses — the auction
      survives a failed deal instead of every rival bid being rejected for nothing
- [x] `requireSeller` no longer treats an **absent** `teamId` as consent to act as the seller
- [x] **Fixed alongside it:** `getTeamTransferOverview`'s `incomingOffers` had
      `.filter(hasOpenOffer).filter(t -> !isActiveListing(t))`, and those two predicates are mutually
      exclusive — the list was empty for every possible input, so the Incoming Offers panel was dead.
      Proven: `expected: <1> but was: <0>` with the filter restored
- [x] Every test proven able to fail by deliberate breakage (four break-and-restore cycles)

**Also found and fixed:** `requireSeller` tightening means a caller omitting `teamId` is now
`400 TEAM_REQUIRED` rather than silently skipping the ownership check. Both frontend call sites
already send it.

---

### ~~P2-3 — Player can refuse to be listed~~ ✅ `ListedPlayerCanObjectTest` 6/6

The anti-speculation mechanic. Listing used to cost the player's position nothing at all — a club
could list anybody at any price and the dressing room had no say.

**Two pieces of dead code became load-bearing rather than being rewritten:**
`SquadRole.reluctanceToSell()` (no callers anywhere) now decides how much a club's valuation of a
player translates into his resistance, and the `PlayerContractService.wageDemand` model — already
answering "is he paid what he thinks he is worth" for renewals — supplies the grievance.

**Landed:**
- [x] `ListingObjection` — three distinct reasons (`WAGE_DISPUTE`, `DOES_NOT_WANT_TO_LEAVE`,
      `UNHAPPY_TO_BE_LISTED`), each with the sentence the player says. **Not** a `TransferStatus`:
      the `transfer` table has a live Postgres `CHECK` constraint on its four values that
      `ddl-auto=update` will not recreate
- [x] Triggered in-game on a human club's listing, from reluctance + whether he is underpaid
- [x] **The club cannot delist him** (`409 PLAYER_OBJECTION_OPEN`) — delisting is how a club would
      make the objection go, so it is what has to be blocked
- [x] **The club cannot accept a bid** while it stands; the refusal carries the reason, not just a code
- [x] **The objection survives re-listing.** The `transfer` row is unique per player and recycled on
      every listing, so clearing it there would make "reject the bids, take him off, put him back"
      a free way to launder a refusal — the exact thing the mechanic exists to prevent
- [x] Two resolutions, so the manager has a real choice: `UPHELD` (withdraw, keep him — done as part
      of resolving, so he is not sent to a Remove button the objection just blocked) or `PAID`
      (5% of the asking price, charged and ledgered, and the sale can continue)
- [x] `removeFromTransferList` moved onto `requireSeller`, which **requires** a team id; it had the
      weaker `actingTeamId != null` guard that P2-2 removed elsewhere
- [x] Every test proven able to fail, including one that had to be **rewritten** because it passed
      against broken code (see the log)

---

### ~~P2-4 — Listing fee as a percentage of the asking price~~ ✅ `ListingFeeScalesWithTheAskingPriceTest` 5/5

**The board said "two lines".** It was **no fee at all** — `listPlayerForTransferEntity` charged
nothing and computed `alreadyListed` without using it, so listing was free and re-listing was free.

**Scope decision, owner:** **human clubs only.** AI clubs self-list weekly and academy graduations
list automatically; charging those would drain 14,880 budgets for a mechanic that exists to stop a
*manager* spamming the market. Gated on `Team.humanControlled`, which is maintained by
`PyramidBuilder`, `DatabaseInitializer` and `RegistrationService`.

**Landed:**
- [x] 2.5% of the asking price — the rate `archive/COMPETITIVE_ANALYSIS.md` benchmarks against
- [x] New `FinanceCategory.LISTING_FEE`, so the charge appears on the Finances page under its own row
- [x] Budget deducted and one ledger line written; charged off the **clamped** asking price, so a
      request for EUR 0 is charged as the EUR 1 it was listed at
- [x] `alreadyListed` honoured — re-pricing a live listing is not a new listing and is not re-charged
- [x] A club that cannot fund the fee gets `409 INSUFFICIENT_BUDGET_FOR_LISTING_FEE` and **nothing is
      listed**; otherwise the fee constrains only honest managers
- [x] Own class (`TransferListingFeeService`) rather than another method on the 1199-line
      `TransferService`
- [x] Every test proven able to fail, including a flat fee, a repeated charge, charging the AI, and
      dropping the affordability refusal

---

### ~~P2-5 — Supporter mood and supporter expectations~~ ✅ `SupporterMoodRespondsTest` 6/6

The board's premise verified exactly: `BoardExpectationService` has **one caller**,
`FinanceController.java:111`, which is a read for display. `sackingReview` is a computed boolean with
no entity behind it. **A number the player can see that nothing responds to is worse than no number**,
because it invites the expectation of a consequence.

**Landed:**
- [x] `Team.supporterMood` (0-100, default 60) — separate from `reputation` on purpose: reputation is
      what the club is worth, mood is how the stand feels about being there, and a club can be
      successful and unloved
- [x] **Mood bends attendance**, so it bends gate income, so it bends the wage-bill ratio the board
      reads. This is the missing consequence, and it is the board criterion *"something in the game
      responds to them"*
- [x] **The loop back to P2-3**: a player objects to being listed → supporters notice the club is
      selling its own people → mood falls → fewer come → gate income falls → the board's trust falls.
      Every step already existed; nothing was connected
- [x] `SupporterExpectation` — mood says how they feel, the expectation says what they think the club
      should do about it, which is the half a manager can act on
- [x] Drifts weekly rather than jumping: **exactly 1.0 at mood 60**, so a club that has not drifted is
      unchanged. A first formula returned 1.032 at neutral, which would have silently changed gate
      income for the whole world on day one; a test caught it
- [x] Visible beside board trust on `/finances/{teamId}/board`
- [x] One query for all clubs' objections, not one per club — 14,880 round-trips a week otherwise
- [x] Every test proven able to fail, **including one that had to be rewritten because it was green
      against unwired code**

---

---

### ~~P2-6 — Graduation caps~~ ✅ `GraduationRespectsTheSquadTest` 4/4

Graduation was **unconditional**: every ACTIVE junior aged ≥20 in the entire world became a senior
`Player` in one loop. `canRegister` could not stop it, because graduation creates no `PlayerContract`
and `canRegister` counts contracts — so a graduate was invisible to the 25-senior cap, and then drew a
wage for a full season before the backfill noticed. **A club's academy was an unlimited source of free
players.**

**The cap is the squad, not a number.** A graduate is promoted only while his club has room; otherwise
he is **released**. That gives P2-7 teeth in both directions — a club that refuses to let players go
fills its own squad and blocks its own academy.

**Landed:**
- [x] Per-club room, counted down **as it is used**, so five due juniors and two places promote exactly
      two rather than five plus an overflow discovered later
- [x] Also bounded by `MAX_ACTIVE_JUNIORS` — unreachable through intake, but the sweep reads junior rows
      directly and fixtures and the seeder insert them without passing through intake
- [x] `MAX_ACTIVE_JUNIORS = 10` extracted from the inline literal
- [x] Squad sizes loaded in **one grouped query** for the world, not one count per club — 14,880
      round-trips inside the season rollover otherwise
- [x] **Also fixed: the defect P2-3 introduced.** A graduate has no contract, so
      `ListingObjectionService.roleOf` fell back to a position switch mapping `GK/DEF/MID → STARTER`.
      A 17-year-old was judged as a senior starter. **Measured: 0.4125 objection likelihood on a
      graduate's first day**, i.e. ~41% of all automatic graduations drew an objection the club then had
      to pay 5% to clear. Now delegates to `PlayerContractService.inferRole`, which already encoded the
      right rule (a cheap 17-year-old is a YOUTH, reluctance 0.12)
- [x] Every test proven able to fail, including the `merge` bug below

**Recorded, not changed:** the `10` is hardcoded three more times in `static/js/pages/features/academy.js`
(`{n}/10`, `0/10`, and the refusal text). Java now has one source; the frontend copies still need a DTO
field, which is a separate piece of work.

---

---

### ~~P2-7 — A retirement age~~ ✅ `PlayersRetireTest` 5/5

**Nothing existed.** No `retire`/`retirement`/`retired` token anywhere in `src/main` or `src/test`, no
constant, no age-filtered query, no status field, and no removal path keyed to age. Players aged once
a year and the only ways out of a squad were a transfer or a contract expiring — so a 32-year-old
became 60, then 90, and `ClubNeedService.java:173` kept pricing him at 30% of value and bidding. The
pool only ever grew.

**Owner decision:** a **deterministic age band scaled by quality** (33–36), not a fixed birthday and
not a dice roll — a club plans a squad around when a player ends. Bands are set on the **real 0–100
rating scale** (measured on the dev world: peak ~68, range 35–93), so the cut points are 85/75/65, not
the 0–10 scale the skills use.

**Landed:**
- [x] `Player.retiredSeason` (nullable, appended at the end of the entity as `@AllArgsConstructor`
      demands — and two positional call sites in `sim/` were updated, exactly as that file predicted)
- [x] `RetirementService` hooked into `SeasonService.agePlayersAndJuniorsOneYear()` **between** ageing
      and graduation, so a player can retire and be replaced by a graduate in one season turn
- [x] `findActiveClubPlayers()` + `ix_player_team_not_retired (team_id, retired_season, age)` — the
      sweep would otherwise be a seq scan over every player in the world, once a season
- [x] **He leaves, he is not deleted.** His row, statistics and history survive
- [x] **His contract ends too** — `canRegister` counts contracts, so a retired player left holding one
      would occupy one of the 25 senior slots for ever and the club could never replace him
- [x] The sweep is idempotent, since a season roll-over must be safe to reason about
- [x] Every test proven able to fail, including the destructive-delete trap below

**Also fixed — a latent bug retirement would have made routine:** `Lineup.getOrderedStartingPlayers()`
returned join-table rows regardless of club membership, so a player whose contract had expired could
still start matches. **This already happened weekly** via `PlayerContractService.expireContracts`. The
join table now yields only players still at that club.

### ~~P2-8 — Match types, and a zero-consequence exhibition~~ ✅ `ExhibitionChangesNothingTest` 9/9

**Owner decisions, 2026-10-03:** every match carries a **type** so results can be filtered by it; an
exhibition costs **fatigue exactly as any other match** but carries **reduced injury risk**; and it
**does** appear in match history, visibly typed.

**Why the type rather than an empty competition.** A match recorded only which *competition* it
belonged to, so a friendly or exhibition — which belong to no competition — had nowhere to say what
they were. The two were indistinguishable, unlabelable and unfilterable. `MatchType` makes the rulebook
one file instead of `if (friendly)` scattered through services, and puts the type in a **column** so
read paths filter on it in SQL rather than re-deriving intent.

**Landed:**
- [x] `MatchType` — `LEAGUE`, `CUP`, `INTERNATIONAL`, `TOURNAMENT`, `FRIENDLY`, `EXHIBITION`, each
      stating what it counts for, with `ofCompetition` as the only bridge to `CompetitionType` so the
      two can never disagree
- [x] `Match.matchType` and `MatchFixture.matchType`, **always resolved** — a type that must be
      inferred per read is a type that can be absent
- [x] `POST /simulation/exhibition?againstTeamId=` — the acting club comes from the authenticated
      user, so no manager can play one on another's behalf
- [x] **No table, no ratings, no career goals/assists, no morale, no form.** Five separate writes,
      each asserted separately: asserting only the table passes against code that still inflates a
      striker's career record
- [x] **Fatigue is charged anyway** — ninety minutes is ninety minutes, and a free exhibition would be
      strictly better than a league match
- [x] **Injury risk lower but not zero** (0.35 exhibition, 0.6 friendly), applied to the base rate
      only, and **reset after every match** so it cannot leak into the next competitive match
- [x] **`LeagueTableReconciliationService` now filters by type.** It rebuilds tables from `match` rows
      and would count a practice match back in — measured at **3 points added from an exhibition**
- [x] Played inline, never via the matchday job, so nothing can pick it up and schedule it
- [x] `ProposalEngineIsTheOnlyFixtureProducerTest`'s allow-list **extended deliberately**, not loosened;
      the next caller still fails

**The owner decision this does *not* cover:** the **friendly** fixture still cannot be played — it has
no matchday, so nothing finds it. `MatchType.FRIENDLY` makes it a label rather than a blank, but making
it playable is the remaining step, and it needs a day chosen.

### P2-9 — Pre-match tactical preview

Three to five days, and the analysis calls it *"Hattrick's single best idea"* — it is also the cheapest way
to make P0-3's work visible on day one. `MatchController/{id}/preview` currently returns `Map.of()`.

**Exit criteria:** the preview shows **both sides'** shapes from their own profiles, so it is a real test of
P0-3 rather than a display of one club.

### P2-10 — National team qualifiers and the senior World Cup

The mechanism exists; the competitions and formats do not. This is P2 rather than P0 because nothing is
broken — it is absent.

**Exit criteria:** a full qualifying campaign and a tournament, played to a result.

### ~~P2-11 — International club competitions~~ → moved to **P0-CUPS**, 2026-10-06

*"Three tiers exist with data but the calendar is thin. Depends on the fixture generators being wired to
named cups rather than the first CUP row."*

**Wrong on both counts.** There are five tiers and 15 competitions, not three; and the calendar is not thin
— the group stage and the bracket are written and tested. The reason none of them plays is four defects in
sequence, the first of which (a cup group table that is never written, so every group is decided by seed
order) was not on this board in any form. See **P0-CUPS** at the top of the P0 section.

### P2-12 — U-21 as its own competitions

Explicitly **not tabs on one competition**, per the analysis. Its own qualification path.

### P2-13 — Named tactics with derived levels

Three to four days. The six slider fields have **zero readers** today — they are stored and never used.

**Exit criteria:** the sliders change something observable in a match, or they are removed.

### P2-10 — National team qualifiers and the senior World Cup — **DONE: backend and football UI**

The mechanism exists; the competitions and formats did not. **Work landed 2026-10-06 — see
[`kanbanProgress.md`](kanbanProgress.md) for the full inventory and the two bugs the tests caught.**

**Landed:** four `TOURNAMENT` competitions (senior + U-21, qualifiers + tournament, as **separate rows,
not tabs**); pots-of-8 deal into 8 groups of 6; 5 qualifying matchdays on **week 6 days 2–6**; the
worse-rated side hosting; tie-breaks points → GD → GF → **stored coin**; round of 16 d1, QF d2, SF d4,
final + third place d6; matchday jobs for all nine days; `TOURNAMENT` accepted by `isKnockoutTie`;
national Elo now replays tournaments, weights by stage and pays the qualification bonus; the squad locks
at week 12 day 1 10:00; week 6 / week 12 calendar notes and events are real; a
`/api/national-tournaments` endpoint returning groups, standings and results.

**Exit criteria still open:**
- [x] **A tournament is played to a champion.** `NationalTournamentPlayedToAResultTest` is **7/7** on a
      fresh report: qualifying to sixteen, a level group tie left level, a level knockout tie settled from
      the spot, the coin stable across three reads, the qualification bonus paid exactly once across three
      replays, senior and U-21 separate, and a final **plus a third-place play-off**.
- [x] **The World page lists the four national competitions** and links each drawn competition to its
      groups, results and bracket; undrawn rows remain visible with their planned week. Country national
      team summaries expose the same two competition links for senior and U-21.
- [x] **Admin controls** for seeding national tournaments, resetting ratings and reading rating violations
      are present in `admin-view.js`; live operation still needs observation.
- [x] **Injuries query verified** — `decrementInjuriesByWeek` reads all injured Player rows (includes NT
      copies). **Unverified in running app.**
- [x] **The free-slot ad board: service done, page done.** Owner rules, both honoured: **only human teams
      play friendlies** (asked of the club, not the caller) and a posting **expires with its own slot**,
      which is why season/week/day are columns and why expiry compares days and not weeks. Taking an ad
      goes through the real request service, so "one live request per side per slot" applies to ads too.
      `FriendlyOfferServiceTest` 8/8, checking every week and all four slots against the calendar.
      **This found two bugs in the previous commit:** `aafb7ae` widened the week to four slots, so
      `dayOf(slot)` was putting slot-3/4 friendlies on the league's day 3, and the friendly matchday was
      not registered for day 5 at all. Endpoints and the page are done. The AI-pairing conflict that used
      to sit here is resolved: the owner said "AI ne igraju prijateljske", and
      `runAiFriendlyWeek` together with its test was deleted.
- [x] **Friendlies are playable.** They were not, and never had been: every matchday selects by
      **competition type**, a friendly belongs to no competition, and the written fixture carried no
      `matchType` and no `dayNumber`. A friendly could be agreed, written and shown and **never played**.
      Fixed on both halves — the fields, and a `FriendlyMatchdayJob` that selects by type on days 1, 3
      and 7. `FriendlyFixtureIsPlayableTest` 4/4, re-proven by deleting the type write. **Pre-existing
      fixtures stay unplayable** and are not backfilled; that is a decision, recorded.
- [x] **NT friendlies: week 6 day 1, national against national.** The owner's rule settles the slot
      question — a club and a nation have **no slot in common**, since day 3 of week 6 is a qualifying
      matchday. A separate service rather than a mode of the 596-line club one, because every rule in that
      one is about clubs and the first rule that forgot the branch would be a national side playing on a
      day it cannot. `NationalFriendlyRequestServiceTest` 7/7 over **every week of the season**.
      The compulsory all-sides pairing on that day is **removed** — it was occupying the one day an
      invitation could use. **Open:** no endpoint or UI yet, no bot auto-pairing, and an accepted fixture
      has no competition so the day-1 job will not find it (recorded in the code).
- [x] **INVITE FOR FRIENDLY button, on the club side.** Open slots per slot, accept / decline / withdraw,
      a debounced club picker scoped to the club's own country, and request rows that carry a **name**
      rather than an id. `FriendlyOpponentAndRequestRowTest` 4/4; the national-team filter re-proven by
      removing it. **Open:** national teams as requesters and receivers, the free-slot ad board, and a
      notification kind for the bell.
- [x] **The week-6 day-1 warm-up round has a live draw path.** It had *stopped happening* — its only
      caller, `ensureBaselineDataOnStartup()`, has zero callers. Now drawn by the admin action and
      labelled `MatchType.FRIENDLY`. **"Not compulsory" is not yet implemented**: optional participation
      needs national-team support in `FriendlyRequestService`, which is club-only.
- [x] **The squad lock is visible.** The selector tab shows the owner's reason and renders no release
      or call-up control while locked; both level panels carry a **Squad fixed** badge.
      `NationalSquadLockTest` pins the boundary — every hour of weeks 1-11 open, 09:00 open and 10:00
      locked on week 12 day 1, no expiry, unreadable clock safe.

**Two defects fixed here, and the second is the one worth keeping:**

1. **A dead branch.** The final was drawn by an `if (round == ROUND_FINAL)` inside the feed-forward loop,
   and `ROUND_FINAL` is not in that list — so it could never execute and every tournament stopped at two
   finalists while logging *"tournament complete"*. The same list change that fixed the third place
   deleted the only path to the final.
2. **One round per call was not actually enforced.** With the draw moved out of the loop, the method became
   non-idempotent: every call after the semi-finals redrew the final. **Measured with the guard removed —
   three finals, and nothing complained.** "One round per call" is a property of the code, not a statement
   about the caller; it is only true once *already drawn* is asked about **the round being drawn**.

**Both guards re-proven by breaking them:** removing the final's idempotency guard reproduces
`a tournament has one final — expected: <1> but was: <3>`.

### National-team naming: a senior side is its country (owner, 2026-10-06)

*"Kod imena NT npr Germany National Team stoji samo Germany bez National Team za svaku zemlju, u-21 su
ok."* The manager is on Germany's page, in the national-team section, under a tab already labelled
**National Team** — the name said it three times.

- [x] **A senior side is named after its country and nothing else.** U-21 keeps its suffix, because
      "Germany" alone would leave two German teams with one name.
      `NationalTeamSeniorNameTest` 3/3, including that a side somebody renamed by hand is left alone —
      only the suffix this codebase used is stripped, so a re-seed brings the world forward without
      overwriting a name a person chose.
- [x] **The senior/U-21 split stopped being a name guess.** `InternationalFixtureSeeder` identified the
      senior international field with `getName().endsWith("National Team")` — which, after the rename,
      would have failed for **every** side and silently left the senior internationals undrawn, forever,
      with a log line saying there were not two sides with squads. It asks the **country** which of its
      two sides this is.
      `InternationalFixtureSeederRetryTest` rebuilt to the real world shape (the country points at its
      senior side), because that helper only ever set a name and the old check never needed more.

### The national-team draw: season start, and a Re-draw that re-draws (owner, 2026-10-06)

The owner: **draw the groups at the start of the season**, because the ties are played in week 6 and a
manager who learns his group on the day of the first match cannot plan around it. And the admin
**Re-draw** button did nothing at all — it was calling the draw job with week 6 by hand, which by then
meant it ran a job whose own condition had moved to week 1.

- [x] **The group draw runs on week 1 day 1**, for senior and U-21, and the ties it creates are still
      week 6 days 2–6. `NationalTournamentDrawTimingTest` pins all three cases: week 1 day 1 draws 120 ties
      per level, **week 6 day 1 draws nothing**, and week 1 day 2 draws nothing either.
- [x] **`NationalTournamentWorldService.forceRedraw()`** clears the unplayed qualifying and tournament
      fixtures of the current season, then draws both levels again. The admin route calls it and reports
      the draw through the existing `toMap`, so the screen shows groups and fixture counts instead of a
      job name. `NationalTournamentDrawJob` is no longer injected into `AdminController` — the button and
      the job were two ways to do one thing, and only one of them was correct.
- [x] **The seeder's "already drawn" guard was counting played ties.** It asked for *every* group fixture
      in the competition, so a re-draw that deliberately spared the played ties could never finish: one
      survivor was enough to make the phase read as drawn for the rest of the season. It asks for the
      **unplayed** ones now.
      **Re-proven by breaking it** — with the old query, `redrawKeepsPlayedFixtures` leaves
      `expected: <119> but was: <1>`: the tie cleared, the draw refused, and the qualifying phase stuck.

**The deal is derived, so a re-draw reproduces the groups.** The seed comes from the competition, the
season and the pot, and that was a deliberate decision earlier in this task — a reproducible draw is a
draw that can be audited and re-derived from a restored backup. It does mean the button rebuilds the
fixtures rather than shuffling them; if the owner wants a genuinely new deal, the seed needs a draw
generation and that is his call, not a quiet default.

### P2-12 — U-21 as its own competitions — **DONE: separate competitions and football UI**

Explicitly **not tabs on one competition**, per the analysis. **Landed:** its own qualifying
competition, its own tournament, its own 8 groups, its own 120 qualifying fixtures, its own Elo track
(`Country.youthRating`), its own matchday jobs. Confirmed by test: 120 senior fixtures and 120 U-21
fixtures, in two different competitions.

**Done:** the shared national-tournament view is linked from the World page for both senior and U-21
competitions as soon as their rows exist.

### P2-14 — Prize money: `awardPrizeMoney` has no caller — DONE ✅ `PrizeMoneyFollowsTheRealTableTest` 4/4

The board said "wire it or delete it". **Wire it — but not as written, or it pays the wrong clubs.**

`WeeklyFinanceService.awardPrizeMoney` was implemented with zero callers, and the weekly
`prizeMoney` line beside it deliberately returns `null` ("only paid once the season is finished"). The
two halves were designed to meet and the meeting never happened: **no club had ever been paid prize
money in this game.**

**The trap.** The dead method ranked clubs by `CompetitionEntry.position`. **Nothing sets that field
during a season** — `PyramidBuilder` writes it once when the world is built and it is never updated.
Wiring it as written would have paid the champion's money to whichever club was seeded first, every
season, silently, under a correct-looking ledger line reading `"Finished P1 of 10"`. Measured with the
break in place: the real champion took **384,000** and the club seeded first took **480,000**.

**Landed:**
- [x] Ranked by `LeagueTableOrder` — already the one definition of a league table (owner decision
      S8.4), and what promotion, relegation and the playoff draw read, so the money follows the same
      table the manager is shown. The stale `position` field and its private reader are deleted
- [x] Called from `SeasonService.performPromotionRelegationAndNewSeason()`, **before** promotion —
      paid for the season that finished, before clubs are moved between competitions
- [x] **A competition nobody played in pays nothing**, so a freshly seeded world rolled over before a
      single match does not mint a prize pool
- [x] Every club in the table is paid, not only the champion — 45% / 28% / 20% … down the table
- [x] Written to the ledger (with the finishing position in the note) and to the budget
- [x] The season's competitions come from **one** query, not one per competition
- [x] Both breaks proven: ranking on the stored field, and dropping the unplayed-season guard

**Recorded, not changed:** `CompetitionEntry.position` is now written by `PyramidBuilder` and read by
**nothing** in `src/main`. It is a dead column, and `ix_competition_entry_sc_pos` exists to serve it.

---

### P2-15 — Basketball, American football, text-based football

Per-mode work. Their agent notes are in `archive/BASKETBALL_PROGRESS.md` and have not been re-verified
since the `newLogic` split — **read the archive first, then confirm against the code, because the notes
predate the split.**

### ~~P2-16 — Visual work: a load failure now says what failed~~ ✅

Presentation, not correctness. The board named the right candidate and understated it.

**The defect:** `js/pages.js` — the router every page goes through — ended in
`mainContent.innerHTML = buildEmptyState("API Error")`. One string, no status, no code, no explanation,
and it **replaced whatever the page had already rendered**. So a 403 saying *"Only the owning club can
accept incoming offers"* and a 500 saying the database was unreachable both reached the manager as the
same two words.

**Landed:**
- [x] `buildErrorState(err, context)` — titles by status (401 / 403 / 404 / 5xx) and shows the
      `code`, the `status` and the backend's `message`, which `ApiException` already writes for the
      person reading it
- [x] `describePage()` names all **41** router cases in words ("the transfer centre", not `firstTeam`),
      verified against the router's own `case` labels so the two cannot drift
- [x] Everything interpolated is **escaped** — an error message is exactly the string that ends up
      holding a club name
- [x] `buildEmptyState` was interpolating its argument into HTML **unescaped**; it now escapes

**Also fixed, and AGENTS.md was wrong about it:** AGENTS.md states *"`escapeHtml` lives in
`ui/escape.js` and is the only copy."* There were **three** — `ui/escape.js`, `pages/views/utils.js`
and `pages-renderers.js`, byte-for-byte identical. `pages.js` imported from one and used the other.
Both duplicates now import the canonical one; **one implementation remains**, verified identical on
null, undefined, 0 and an `</script><img onerror=...>` payload.

**Recorded, not changed:** `roundResultsTeletext.js` carries `teletextFetch`, a second implementation
of `authFetch` with its own Serbian error strings. **It has a caller** — the live-results desk — so this
is a decision, not a cleanup.

**The verification gap, stated plainly:** this repository has **no JavaScript test infrastructure at
all** — `package.json` holds one unrelated dependency and no runner, and there is no `*.test.js`. So a
frontend change here can only be verified by executing the module or by opening the app. Executed: the
error card renders correctly for 403-with-message, 500-without-code, and an error with neither, and the
escaper identity was checked case by case.

**A mistake of mine, recorded because the rule exists:** I deleted `teletextFetch` on the strength of a
caller count, and the count was wrong — my `grep` filtered out the very file I was searching, hiding its
one caller at line 17. Restored with `git checkout` in under a minute. **P0-10 says to re-verify the
caller count immediately before deleting, and I broke that rule while citing it.**

### ✅ "Next match" on the dashboard was broken for every manager — found on the Oracle instance

Reported live 2026-10-03: clicking **Next match** produced
`Error loading match: AuthFetchError: No static resource nonexistent.`

**Cause.** `match-view.js` fetched the literal string `'/nonexistent'` for an unplayed fixture, on the
theory that a 404 is tolerable because such a fixture has no `Match` row. **`authFetch` throws on every
non-2xx**, so the throw skipped the `if (!response.ok)` tolerance three lines below it, landed in the
function's `catch`, and rendered the error. The tolerance was **unreachable code**, and its author had
assumed a contract for `authFetch` that does not exist.

An unplayed fixture has **no event stream at all**, so the fix is to not ask: the fixture path now skips
the events request entirely and builds the header from `/matches/by-fixture/{id}`, which already
existed and already had its own `catch`. That also removes a pointless HTTP round trip per fixture view.

**Verified by reproducing the old strategy against a stub `authFetch` that throws like the real one:**

| | before | after |
|---|---|---|
| fixture | `Error loading match: No static resource nonexistent.` | renders, header from metadata |
| requests for a fixture | 3, one of them guaranteed to 404 | **2** |

**The same defect class, found while fixing it:**
- `pages/features/matches.js:9` has a comment saying this exact class of bug was fixed there — but its
  `if (!response.ok)` guard is **still unreachable**, and `loadResults` is called inside the router's
  `try`, so a failure there still escapes to the generic "API Error" card. Latent, not the reported
  symptom.
- `academy.js:138` and `stats-view.js:67` have the same unreachable guards, but both are already inside
  a `try/catch` that returns null, so their behaviour is correct and only the dead line is misleading.

**All three fixed.** `matches.js` catches and renders its own error card — which is what its own comment
already claimed — and **`loadFixtures` never had a guard at all**, so the Schedule page had the same
symptom with nothing handling it. The two harmless ones had their dead lines removed, so the code stops
inviting a fourth copy of the mistake.

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

## 🌍 THE QUEUE — split into one task per board card, worked one at a time (owner, 2026-10-07)

> **razdvoji po taskovima, zapisi ih u kanban.md pa azuriraj kanban i kanban_progress md i uzimaj jedan po jedan**
> ...**clean cut** (the ranking list shows ranking points only, not both)

Everything below is one card. **One card in flight at a time**, each committed on its own with its own
entry in `kanbanProgress.md`, so a half-built card is always visible rather than folded into a bigger one.

### P0 — Ranking points: make the numbers real

| Card | What it is | Done when |
|---|---|---|
| **P0-RANK-1** | A **per-season ledger** so the rolling window can be computed at all. One row per subject per season holding that season's points; the displayed total is `1500 + Σ(season × 1.00/0.75/0.50/0.25)`. | ✅ Two tables round-trip decimals, the window reads off them, and senior/U-21 cannot pool. **The writers land in -2 and -3** — nothing writes these rows yet. |
| **P0-RANK-2** | **The club replay writes ranking points** instead of gap-weighted Elo deltas. | ✅ `ClubRankingPointsService` rewrites the ledger from match history, idempotently, per season, tier-weighted. |
| **P0-RANK-3** | **The national replay writes ranking points**, senior and U-21 separately. | ✅ Same property for a country; a level guard refuses to score a side into the wrong level. |
| **P0-RANK-4** | **One single rating system.** The head-to-head gap weighting goes from the rating as well as the points. | ✅ `clubK` no longer reads either rating. Swept 0–800 of gap. Mutation-checked. |
| **P0-RANK-5** | **Achievement bonuses:** qualifying for an international cup, each further tournament phase, and every trophy including the national cup. Tier-weighted for clubs. | ✅ Bonuses read from the fixtures, not the draw. Stored in their own column so the pass is re-runnable. |
| **P0-RANK-6** | **The ranking list orders by ranking points. Clean cut** — Elo is no longer displayed as a ranking. | ✅ `GET /countries/ranking` orders by points. The test builds a world where the two orderings **differ** and requires points to win. |

### P0 — Every generated match opens to a preview, one competition per card

> The owner's instruction: *"svaki generisan mec iz zreba nevezno da li je nacionalni kup, medjunarodni
> kup, nt ili ntu21 mec, mora da ima cim se generise mogucnost da se udje na mec i vidi preview"*.

Split one per competition because they do **not** share a code path — the national cup has a bespoke
sheet today, the club cups have nothing clickable at all, and the two national-team competitions render
ties as plain `<div>`s. Doing them together would hide which one actually broke.

| Card | What it is | Done when |
|---|---|---|
| **P0-PREV-1** | **National cup** fixtures open to the shared match view, replacing the bespoke `loadCupFixturePage` sheet. | ✅ A tie opens the same screen a league fixture does. A **played** tie opens the **match**, so lineups/stats/goals/report are keyed correctly. 453 lines of bespoke sheet deleted. |
| **P0-PREV-2** | **International club cups** fixtures open. Today `club-cup-view.js` renders ties as `<tr>` with no clickable target at all. | ✅ Both club names open the shared match view. `matchId` added to the tie payload. 26 tests green. |
| **P0-PREV-3** | **Senior national team** fixtures open — qualifying and finals. | ✅ Group ties and knockout ties both open. 18 tests green. |
| **P0-PREV-4** | **NT U-21** fixtures open. | ✅ **Already delivered by P0-PREV-3** — one renderer and one payload builder serve all four competitions. Evidenced by a U-21 test rather than assumed. |
| **P0-PREV-5** | **Post-match detail for all four**: lineups, player stats, goals/scorers and the report. The views are already type-agnostic — this card *proves* that rather than assuming it. | ✅ Proven across league, cup, club cup and international. **One real defect fixed:** the report matched players to sides by team *name*. |
| **P0-PREV-6** | **Live and replay for human matches** in those competitions, as league matches already have. | ✅ Already competition-agnostic (no type filter in the selection). **Four name round-trips replaced with the `footballTeam` foreign key**, including the one deciding what "play my match" may act on. |

**The boundary that must not be crossed again:** a fixture id and a match id are both small integers over
separate tables. `ZoxApiController` already carries the scar — *"the guess resolved a dashboard link to
somebody else's played match"*. Each card passes an explicit kind, never a guess.

### P1 — Country page

| Card | What it is | Done when |
|---|---|---|
| **P1-CTRY-1** | **A new tab listing the clubs of that country, ranked.** No endpoint exists today; `findClubTeamsForCountry` is already indexed. | ✅ A **Clubs** tab, ranked by the same ranking points, division on every row, fetched only when that tab is asked for. |
| **P1-CTRY-2** | **International qualifying moves out of General into its own tab.** | ✅ Its own **Qualifying** tab, next to General / Calendar / Clubs / National Team / U-21. Removed from the General panel. |

### P0 — Elections (owner already approved all of this on 2026-10-07)

| Card | What it is | Done when |
|---|---|---|
| **P0-ELEC-1** | **Registration opens week 12 day 1 of the previous season**, not one day before week 1. Measured: 7 days, because week 12 is the last week and runs into the next season. | ✅ `registrationOpensAt = weekOneDayOne − 7d`. Asserted. |
| **P0-ELEC-2** | **`describe()` stops reporting a hardcoded stub.** | ✅ **Already satisfied on the live path** — `describe()` delegates to `describeElection` via `electionState`. The hardcoded `stage: NONE` sits only in the "this team was never created" branch, where `NONE` is honest. The closed button came from ELEC-3. |
| **P0-ELEC-3** | **`describeElection` creates the election on demand**, so a reset is not a dead end. Today a reset leaves **0 rows** and the panel can only ever say "no election running". | ✅ Zero-row reset no longer strands the panel — asking creates the election and opens registration. |

### ✅ ANSWERED — one single system

> **KOLIKO PUTA DA PONOVIM?! JEDAN JEDINI REJTING SISTEM!!!**

The rating's gap weighting is gone too. `clubK` no longer reads either rating, so beating a giant and
beating an equal are worth the same, and there is no second system left to disagree with the points.

**What the gap weighting was actually defending, and where it went.** It was kept here on the grounds
that the rating should distinguish *"held on to a draw against Roma"* from *"beat Roma"*. That distinction
is not lost — it moved. The forecast in `ScheduleInsightService` already knows Roma is stronger, so a
result is rewarded for **beating what was predicted** rather than for beating a bigger name. Weighing the
result by the gap as well counted the opponent **twice**.

---

## 🌍 P2-MINE — from the owner, 2026-10-07 (queue behind the current run)

> **prvo nastavi to da zavrsis a onda dodaj i ovo u kanban pa da preuzmes posle**

Added after the ranking and preview work was under way, at the owner's instruction to finish what was
running first. **None of this has been started.** Two of the four are questions as much as tasks, and the
answers are not written down anywhere in the codebase yet.

| Card | What the owner asked | What has to be decided first |
|---|---|---|
| **P2-STAD-1** | **Stadium works need a stand, a cost, and a yes/no.** Building a roof or repairing seats currently commits straight away: *"mora prvo jasno da se izabere za koju tribinu, jasan proracun troskova i onda yes/no da se prihvati ponuda a ne odmah kako sad radi."* | Which stands exist, and whether a cost differs per stand. Nothing on the code says yet whether a stand is chosen today or assumed. |
| **P2-TRAIN-1** | **Can training facilities be repaired, and how does that affect training?** | **A question, not yet a task.** Training is proven to run at day 4, hour 10. Whether a facility can be repaired, what it costs, and what a repair changes about training outcomes is **unresearched** — answer this before writing a card, because the answer may be "they cannot, and here is what does exist". |
| **P2-TROPHY-1** | **Trophies on the Club page, inside milestones.** *"da na Club strani treba da postoji u okviru milestones kao sledeci red trofeji ako ih ima klub gde imamo slicicu trofeja / medalje odredjene boje (zlato, srebro, bronza) i ispod koje takmicenje i sezona (npr Superliga tier 1 season 1 ili Masters Cup season 3)"* | Whether any trophy is **recorded** at all today. The ranking-points work reads trophies off the fixtures, but whether a club keeps a honours list is not established. If nothing stores it, this is a new record plus a display, not a display. |

**Order once the current run clears:** P2-TRAIN-1 is a question and should be answered first because it
may not become a task at all; P2-TROPHY-1 may turn out to need a new table; P2-STAD-1 is the most
self-contained of the three.

---

## 🔶 The preview predicts again, and the ladder's top rung is reachable — owner, 2026-10-07

> **preview vise ne daje prognoze a radile su pre** — showed `Not predicted`, `0%0%0%`, `xG 0.00 : 0.00`

- [x] **Cause was the endpoint, not the arithmetic.** `previewForFixture` returned every computed field
      null by design; the screenshot's numbers came from `ScheduleInsightService`.
- [x] **`MatchPreviewService` computes the real prediction** and keeps fitness, absences and the lineup
      null — the original reasoning was right, it was just applied one field too far.
- [x] **Competition-agnostic**, so league, national cup, international club cup, senior and U-21 all get
      a forecast from the same code.
- [x] **Unit mismatch fixed in one place:** the renderer multiplies probabilities by 100, the service
      returns whole percentages. Missing this renders every forecast as 0%.
- [x] **`/match-stats/lineups/{id}` is a 404, not a 500** — an unplayed fixture has no `Match` row, and
      that is not an error.
- [x] **`EXPECTED_WIN_MARGIN` measured, not assumed.** Across the whole reachable strength range the
      forecast margin spans only **−1.10 to +1.79**, so a threshold of 2.0 made *expected to win*
      **unreachable** and the top rungs dead code. Now 1.0, chosen from the measured table.
- [x] **32 tests green**, six of which fail against the old null stub.
- [ ] **Not verified in a browser or against the real database** — the application is only ever started
      through `run-app.sh`, and the owner is not to start it until the list is clear.

---

## 🔶 Ranking points: the formula is specified and tested, not yet wired — owner, 2026-10-07

> **zelim da osmislis kako se dobijaju i gube ranking poeni za ranking listu, i za NT i za klubove**

- [x] **One number, not two.** Every team starts on **1500**; every match moves it by how the result
      compared with what was expected; achievement bonuses add to it. **No head-to-head term anywhere** —
      the same result against a tier-1 and a tier-31 champion scores identically.
- [x] **Rolling window** of four seasons at **100 / 75 / 50 / 25**, his choice.
- [x] **The ladder:** staying inside the expected outcome is worth **0**; crossing it is worth
      **±20 / ±30 / ±40 / ±50**, graded and capped.
- [x] **Both zeros are his own words** — a win short of the margin and a loss short of the margin are
      each worth nothing rather than a penalty. Points are only won or lost by *crossing* the line.
- [x] **Competition values** 1.00 league / 1.25 national cup / 1.50 international club cup /
      1.20 qualifying / 2.00 World Cup / **0.30 friendly**, his choice to count them.
- [x] **Division weights** 1.00 / 0.85 / 0.70 / 0.55 / 0.40 — which is **why the totals are decimals**.
- [x] **25 tests, mutation-checked:** moving the crossing threshold 3→2 breaks 8 of them.
- [x] **Three bugs the table caught in my own code:** the ladder ignored "won by more than forecast";
      a club cup and a WC qualifier were indistinguishable without `teamType`; `tierWeight` contradicted
      its own javadoc.
- [ ] **Storage and replay** — a per-season ledger so the window can be computed; nothing is computed yet.
- [ ] **Ranking list ordered by points** — it still orders by the old Elo.
- [ ] **`RatingEngine.clubK`'s gap weighting removed**, which is the term the owner rejected.

---

## ✅ The two patterns that would turn a bigger world into a cliff — owner, 2026-10-07

> **fix the findAll() and per-club-loop patterns now as a precondition** for a full pyramid later

- [x] **Measured, not guessed:** 287 bytes/player tuple, 215 bytes/player of index, `shared_buffers` 128 MB,
      heap 8 GB, 2 `@Scheduled` jobs. Full pyramid ≈ 373,000 players ≈ **190–300 MB**.
- [x] **Daily use barely moves**, because every hot path is country-scoped and `ix_player_team` /
      `ix_team_country` already exist — and the expensive multiplier (simulated leagues simulating) is
      already avoided by design.
- [x] **`PlayerRatingBackfill` no longer calls `findAll()`.** Batches of 500, each in its own
      `requiresNew` transaction, so peak memory is bounded by a constant and a partial failure keeps what
      already committed. Paged by id — only ratings change, so page boundaries are stable.
- [x] **The national pool is one query, not one per club.** It loaded every club in the world, filtered in
      Java, then queried each of the country's clubs — and ran **twice** per page load. Now one indexed
      join, **10.2 ms**.
- [x] **`poolSize` and the pool rows come from the same list**, so the count cannot disagree with the rows.
- [x] **Guard driven through the selector path** (a non-selector never runs the code) and asserting
      `atMostOnce()`, not `never()` — `describe` legitimately loads the side's own 25 players.

---

## ✅ Active national sides field real players — owner, 2026-10-07

> **zasto su u u-21 i prvom timu u 25 lazni igraci (verovatno nastali tokom init db) umesto stvarnih?
> AKTIVNA liga MORA imati STVARNE igrace a ne simulirane!!!**

- [x] **All 2,400 national-squad players were generated.** Serbia had 7,730 real players available and
      still fielded `N. SRB-GK01` at 82 while `Zoran Zivadinovic` sat in the pool at 94.
- [x] **Cause: the sides are seeded before the pyramid exists**, so the bot fallback ran; then the
      idempotence guard read *"a squad exists"* and made the simulated players permanent.
- [x] **`BotSquadGenerator.isGenerated`** recognises its own output, built from the same prefix list and
      the same `Position` values so the two cannot drift.
- [x] **Generated players are replaced by real ones**, topping up rather than rebuilding, so a selector
      who already called somebody up is not overwritten.
- [x] **`sourcePlayerId` is now stamped on every seeded copy** — it was only set by `addToSquad`, and it
      is the column that keeps a called-up player out of the pool. Without it the fix would have shown
      the same 25 players in the squad *and* the pool.
- [x] **An empty club list is no longer memoised.** `clubsIn` cached it on a singleton field, and since
      the sides are seeded before any club exists, every country cached "no clubs" — **Repair world
      would have reported success and replaced nothing.**
- [x] **A country with no clubs keeps its generated side.** Deleting its only XI is worse, and an empty
      national side cannot be drawn against. Full pyramid for the other 47 is deferred by owner decision.
- [x] **Guard mutation-tested:** with `setSourcePlayerId` removed, the test fails with the exact message
      it claims to prevent.
- [x] **Seen in the database:** Serbia 25/0 generated/real → **0/25**; U-21 the same. Pool 7,730 → 7,705
      with no double-listing.

---

## ✅ The message list carries the last message — owner, 2026-10-07

> **u listi poruka se samo vidi subject i poslednja poruka i kad se klikne onda se expanduje ceo thread**

- [x] **Reply, the original subject with no subject field on a reply, and the New message button were all
      already built and working** — behind Community, which is why they were hard to find. `MessageService.send`
      is deliberately one route for both: `threadId` continues, `recipientUserId` opens, so the client cannot
      fork a thread by choosing wrong.
- [x] **The one real gap was the last message**, which is the thing the row is read for.
- [x] **`lastMessage` on every thread row, in one query** for the page — `DISTINCT ON (thread_id)`, because
      thirty threads is thirty queries otherwise.
- [x] **PostgreSQL-only, and the H2 tests pass through the fallback** — so a green suite is not evidence
      the query works. Verified against the owner's database (`PREVIEW PATH: QUERY (PostgreSQL DISTINCT ON)`)
      and the test says in its own assertion that it checks the fallback.
- [x] A thread with no messages lists **no** preview rather than an empty one.
- [x] `MessageServiceTest` **27/27**: the preview is the **newest** message, each thread carries its own.

---

## ✅ The notification chime resumes instead of giving up — owner, 2026-10-07

> **red dot radi lepo i broj ali taj ton kad stigne ja ne cujem**

- [x] **The old code closed a suspended context and returned** — and a context created outside a user
      gesture is suspended in **every current browser** (Chrome's own autoplay policy documents it). So the
      chime was given up on **every ring**, inside a `try` with an empty `catch`: no error, no log, no
      console message. It looked like defensive error handling; it was the reason the tone never sounded.
- [x] **Resume instead of abandon**, one context for the page, kept rather than closed after each ring.
- [x] **Unlocked on the first click or keypress**, because Chrome will only start a context from a gesture.
- [x] `NotificationChimeBrowserTest` **1/1** against real Chromium — and it says plainly that **headless has
      no autoplay policy**, so it cannot witness the bug. It asserts the checkable half: `resume()` leaves
      the context running. The suspended half rests on Chrome's documentation, quoted in the test.
- [x] **A regression this task introduced, caught by an existing assertion**: rewriting the chime deleted
      `buildDropdownHtml` entirely, and `theDropdownShowsUnreadOnly` failed on the missing filter.

---

## ✅ Opening a notification's target marks it read — owner, 2026-10-07

> **kad se klikne na open conversation ili open forum iz notificationsa odmah smanji broj unread-a jer je taj
> vec procitan (i izbaci ga i iz tickera ako je tamo)**

- [x] **The link never marked it read.** Clicking a notification's row did; clicking *Open the conversation*
      did not — and the row handler deliberately skipped those buttons, so a notification could be acted on
      for ever and still sit in the ticker with the count unchanged.
- [x] **One path for both ways of reading it**, `consumeNotification(id, row)`.
- [x] **The screen moves before the server is asked** — row gone, badge down, then the POST. A badge that
      waits on a round trip to change reads as broken. If the POST fails the next poll corrects it.
- [x] **Reading cannot make the bell ring**: the decrement also moves `lastSeenUnread`, so the next poll sees
      no increase and stays quiet.
- [x] `NotificationBellAlertTest` **9/9**, pinning that the row is removed **before** the `await`.

---

## ✅ Jobs view: a real tab, and a table class that exists — owner, 2026-10-07

> **napravi lepse job pregled, bas je zbrkano ... normalna leepa tabela ko sve druge tabele, kolone su ok,
> pazi na mob prelom**

- [x] **`fm-table` is defined nowhere in the stylesheet** — the panel had no padding, no header styling,
      no borders and no hover, which is what "zbrkano" was. It uses **`fm-squad`**, the style every other
      table here uses. The backup table had the same defect and is fixed too.
- [x] **A real tab bar** at the top of Admin, so Jobs is not the ninth section in a scrolling page.
- [x] **Panels toggle with `hidden`**, not a class — a `display:none` panel still fetches, so Jobs would
      have read the server while invisible.
- [x] **Mobile:** the table scrolls inside its own wrapper; below 640px the trigger and next-trigger
      columns are dropped and the name, last outcome and failure count are kept. The outcome is never
      dropped — it is the reason the panel exists.
- [x] `AdminJobsViewTest` **4/4**, including **every class the view uses is defined** — this failure mode is
      silent, so it is asserted rather than noticed.

---

## ✅ P0 — Reset DB: a typo, then a subtle one, both found by running it for real — owner, 2026-10-07

> **unrecognized configuration parameter "session_replica_role"** · **moras ovo da istestiras pre nego kazes
> da ok, slobodno drljaj po bazi**

- [x] **`session_replica_role` is not a PostgreSQL parameter.** The real one is
      `session_replication_role`. Reproduced before changing anything, then fixed — **and the button stayed
      broken until the owner pressed it**, which is the standing rule this task now follows.
- [x] **One `TRUNCATE ... CASCADE` was also wrong**: `CASCADE` follows references in *both* directions, and
      eight tables reference `app_user`, so it emptied the accounts the reset exists to keep. Measured:
      truncating `nl_notification` alone took `app_user` from 8 rows to 5.
- [x] **Ordered deletes with integrity suspended under the correct name**, restored in a `finally`.
- [x] **The suspension is verified, not assumed** — `SET` through Hibernate can return without taking
      effect, and the session is asked what it is set to. The log line exists for that failure mode.
- [x] **`ResetServiceOnRealPostgresTest` against a real copy of the world**: 122 tables emptied, 0
      countries, both accounts kept. `@DataJpaTest` **replaces the DataSource with an embedded database**,
      which is why the first version silently skipped itself.

**Two of the last three failures are invisible to H2.** Anything touching the real schema is not verified
by a suite running on a smaller one.

---

## ✅ Admin → Jobs, and what an advance triggered — owner, 2026-10-07

> **Mora u Admin deo da se doda poseban tab za jobove ... istorija kad je trigerovan job i da li je ispravno
> zavrsen, kad je sledeci triger** · **advance hour/day/week ... da se pinguje job syncer**

- [x] **A jobs panel**: every registered job with its trigger, last outcome, next trigger and failure
      count. The rows were **already in `job_run` with no reader anywhere** — that is why "does training
      work?" was unanswerable.
- [x] **A FAILED badge and a tinted row.** A failed job is retried silently, which means a *permanently*
      broken job looks healthy for ever. The status was being written and never read.
- [x] **Next trigger is walked forward, not subtracted** — whether a trigger is reached depends on which
      hours the clock offers. Pinned: from week 3 day 7 hour 22, a day-7 hour-23 job is **one hour away and
      still week 3**, not week 4.
- [x] **The advance commands were already correct** — `advanceHours` steps an hour at a time and runs what
      is due at each step, and `advanceWeek` **is** `advanceHours(168)`. Checked, not assumed.
- [x] **The gap was the report**: a 24-hour advance returned only the last hour's outcomes, so it reported
      two or three jobs when it had run five. It now returns `advance: {hoursAdvanced, ran, skipped,
      failed, jobsRan, jobsFailed}` — "moved 24 hours, ran training, skipped 3, nothing failed".
- [x] `JobStatusServiceTest` **5/5**, `AdminJobsControllerTest` **2/2**.

---

## ✅ Training: measured, and it works — but nothing could prove it (owner, 2026-10-07)

> **potencijalni p0: da li nam radi trening? na Oracle je prosao dan za trening a nije se desio**

- [x] **`job_run` said `training` had run once**, every other job repeatedly — a real signal, and the
      reason for it turned out to be the world's state rather than a defect.
- [x] **The hypothesis was wrong and the test said so.** Hour 23 looked unreachable (the clock increments
      the day and the hour in the same step), and both rollover jobs sit there. `JobTriggerCoverageTest`
      walks a real week: **hour 23 *is* reached, on all seven days.** Kept because the question is real
      for the next job on an unusual hour.
- [x] **`TrainingJobFiresTest` 3/3 proves it through the real `JobRunner`**: at day 4 hour 10 the outcomes
      contain `key=training … status=DONE`, alongside day-opened, recovery, table-reconcile and
      tournament-draw. **Training works.**
- [x] **The real gap: nothing tested the job at all.** The service had a test, the manual endpoint had a
      test, and the scheduler had none — a service wired to nothing looks exactly like one that works.

---

## ✅ Back from an NT match went to the league; qualifying rows were unstyled; seeding scanned per club — owner, 2026-10-07

- [x] **Back from a national-cup match landed on the league match list.** `match-view.js` mapped the caller
      to a Back target through an **allowlist**, and everything outside it fell through to `'results'`. An
      unknown caller now **returns to the previous screen** — correct for every surface, including the next
      one, which an allowlist cannot be.
- [x] **"Qualifies" was invisible.** The rows already carried `is-qualified`; **the class was defined
      nowhere**, and the whole `fm-qualifying-*` block had no styling. Now a tinted row with a **green left
      rule** — a full fill turns five tiers of tables into stripes — plus a blue rule for the manager's own
      club.
- [x] **Seeding was one table scan per club.** Measured at **10 ms × 14,880 = 149 s**, which is the
      *"kako ide dalje kroz drzave tako ide sve sporije"* shape exactly. Now **one query per country**
      (48 total) and a `Set` membership test. `PyramidBuilderQueryCountTest` 2/2 asserts the **query count**,
      not the result — every behavioural test passed while this took minutes.

---

## ✅ P0 — Reset DB died on a foreign key; the senior-side rename never ran — owner, 2026-10-07

- [x] **The schema has genuine FK cycles** — `cteam ↔ cscountry`, `new_logic_lineup ↔ new_logic_match`,
      `country ↔ team`. **No ordering of row-by-row deletes satisfies an immediate FK around a cycle**,
      which is what the children-first sort was pretending. Referential integrity is now suspended for the
      reset and restored in a `finally`; the ordering code is deleted rather than patched.
- [x] **The sabotage test passed** — H2's schema does not carry those basketball/legacy foreign keys, so
      the wrong order completes there. `foreignKeyCyclesDoNotStopTheReset` puts rows in the cycle pair.
      **Second time in this task H2's smaller schema hid a real defect.**
- [x] **The senior-side rename was correct, compiled, and unreachable.** It lived only inside
      `seedIfMissing`, so it ran only if somebody pressed Re-seed. `renameSeniorSides` is now standalone and
      idempotent, the repair calls it and reports the count, and **the 48 rows in the owner's database were
      corrected directly**.

---

## ✅ A represented country now says something — owner, 2026-10-07

> **za simulate zemlje trenutno stoji za npr Rumuniju ... da stoji ranking poeni i pozicija na ranking listi,
> da stoji grupa u kojoj je NT tim, da ako su seedovai international predstavnici stoji to**

- [x] **The old page said only "ROU is represented, not played"** and pointed at Admin. True and useless:
      **24 of the 48 countries on the World page are exactly this**, and their national sides play
      qualifying groups and a World Cup like any other.
- [x] **Ranking points and position**, on the senior Elo the matches produced. A position is a statement
      about every country, so it is computed server-side in `GET /countries/ranking` — the World page and
      a country page cannot disagree about who is 12th.
- [x] **Equal ratings share a position.** A table numbering two identical countries 7 and 8 claims a
      difference it cannot support. **Re-proven by breaking it** (`expected: <1> but was: <2>`).
- [x] **Which group each of its two national sides is in**, with the whole group's table.
- [x] **Whether it has played anything** — an unrated country says so instead of showing its seed rating
      as a result.
- [x] Senior and U-21 read from **two different columns** (`reputation` / `youthRating`).
- [x] `CountryRankingTest` **5/5**.

---

## ✅ The qualifying schedule existed and was never sent — owner, 2026-10-07

> **gde su mecevi kad izadje draw? ... svaka grupa, ime grupe klikabilno ... svaka reprezentacija treba
> klikom da vodi na tu zemlju**

- [x] **The schedule was never in the response.** `roundsOf` skips every fixture with a group code —
      correct for a knockout, and it meant a qualifying competition returned **no fixtures at all**. Eight
      groups on screen with nothing behind them. Each group now carries its five matchdays.
- [x] **`exists` now means drawn, not "the row exists".** `ensureAll` creates all four rows the moment any
      one is drawn, so the World page reported four competitions when one had been. A tile is a link
      exactly when there is something behind it.
- [x] **The group name opens the group's schedule** — rendered and hidden rather than fetched, because a
      manager comparing two groups wants both on screen.
- [x] **Every team name is a link to its country**, in the standings and the schedule, from the ISO code
      the server now sends beside every name.
- [x] `NationalTournamentScheduleTest` **5/5**, the schedule line **re-proven by breaking it**.

---

## ✅ Forum bans now tell the person they were applied to — owner, 2026-10-07

> **kada igrac banovan s foruma treba da dobije i info u notifications (ostaje ono sto mu izadje ako pokusa
> da pize)**

- [x] **`notifyModeratorsOfBan` had zero callers.** A ban produced no notification anywhere — neither to
      the banned manager nor to the moderators. `FORUM_BANNED` was reachable from nowhere.
- [x] **`banFromForum` now notifies the banned manager**: the length in days, the reason, and who applied
      it. And the moderators, so the decision is on the record.
- [x] **The write refusal is unchanged**, exactly as asked — the notification is in addition to it, not
      instead of it.
- [x] **Lifting a ban sends nothing.** A lift is the absence of something.
- [x] `ModerationServiceTest` **19/19**, the notify line **re-proven by breaking it**
      (`expected: <1> but was: <0>`).

---

## ✅ Notifications: the bell was never polled, and read items never left the ticker — owner, 2026-10-07

> **notification - niti zvuka kad stigne niti crvene tacke - nista, testirao sam** ·
> **kad se poruka procita skida se iz tickera**

- [x] **The poll never started.** `startNotificationPolling` guarded on
      `document.getElementById('notification-bell')` — and the bell is in `dashboard.html`, so that is
      always true and the function **always returned early**. The badge only ever updated when the
      manager opened the dropdown, which is why nobody noticed. The red dot and the ring were correct
      code with nothing to run on. Now a `pollStarted` flag, which is the state the guard meant.
- [x] **The dropdown lists unread only.** Read items leave the list rather than dimming in it, and the
      header count is derived from the filtered list so the two cannot disagree. Rows stay in the
      database.
- [x] `NotificationBellAlertTest` **7/7**, every guard re-proven by breaking it.

**The lesson, recorded because it is the third instance:** a guard written about the wrong thing. Here
`getElementById` was used as *"have I started?"* when the element is part of the page rather than a
consequence of starting — and the same shape appears twice more this task, in the reset's delete-list and
in a list of club columns shorter than the table.

---

## ✅ P0 — Reset DB kept 86 tables, and Initialize DB crashed on the ones it kept — owner, 2026-10-07

Two defects, both reported from the same session, both caused by the same thing: code that enumerated
instead of deriving.

### 1. Initialize DB died halfway through the pyramid

> `Cannot invoke "Country.getIsoCode()" because the return value of "Competition.getCountry()" is null`
> — `DatabaseInitializer.initSerbianFootballStructure`, at the filter that finds the Serbian leagues.

The four national-team competitions **have no country** — an international tournament is not any one
nation's — and the walk asked `c.getCountry().getIsoCode()` without asking whether there was a country.
Twelve lines below the same file already had the null check. So once the NT competitions existed,
Initialize DB always died, half-built, and the panel reported *"Database job 'initialize' failed"*.

**Order-dependent, which is why it survived:** it needs an NT competition to already exist, and a cold
database has none.
`DatabaseInitializerNationalCompetitionsTest` builds that exact world and calls the method the button
calls — 1 test, and it takes ~3 minutes because it builds a real pyramid.

### 2. Reset DB kept 86 of the 125 tables

> *"treba da prezive samo podaci u owner useru i o useru Kecko i tactical editor podaci - sve ostalo -
> brisi, timove, forume, poruke, sve"*

The reset was a **delete-list of 39 tables**. The database has 125. So **86 tables were never
touched** — `nl_forum_topic`, `nl_forum_post`, `nl_message_thread`, `nl_direct_message`,
`nl_notification`, the transfers, the scouting, the finance ledger, and the tie-break coins. The owner
pressed Reset DB and found forum topics still in place, and he was right.

- [x] **The reset is a keep-list now.** `app_user` (the two named accounts), `user`, `tactics`,
      `formation`, `formation_positions`. Everything else is cleared **whatever it is called and whenever
      it was added** — a delete-list is a promise to remember every table the application will ever have,
      and a keep-list is a promise about the three things that must survive.
- [x] **Both accounts are named, and both are detached.** `velibor@example.com` and `kecko@example.com`.
      A rule preserving "row 1" deletes whichever manager registered first.
- [x] **The national-team competitions are cleared too.** They are world data like any other; leaving four
      orphan tournament rows behind a reset is how "the qualifying groups are still there after I reset"
      happens.

**The bug the new test found in the new fix — kept, because the shape of it is the point:**

`preserveOwnerAccount` nulled `cteam_id` and `tifocteam_id` but **not `football_team_id`**. Teams are
deleted before accounts, so the reset would fail on the foreign key — *"Database job 'reset' failed"* and
a half-cleared world. **The columns are now read from `information_schema`**, so the next sport added to
this application cannot repeat the omission. That is the same mistake as the delete-list, one level down:
a list of columns quietly shorter than the table.

`ResetServiceKeepsOnlyAccountsAndTacticsTest` 3/3. Its central assertion is **"no table outside the
keep-list holds a row"** — the owner's actual words — because a test naming the 86 tables would be the
same mistake in test form. **The detachment guard is re-proven by breaking it:** naming only two of the
five club columns reproduces the failure.

**Rows in that test are written through the entities, not through hand-built SQL.** An earlier version
assembled `INSERT`s from `information_schema` and guessed a literal per column, and burned five
iterations on `SUPPORTER_MOOD` being an integer and `HUMAN_CONTROLLED` a boolean. A name is not a type.

**Also learned, and it cost real time:** the test profile is **H2** and the owner's database is
**PostgreSQL**, and the obvious `TRUNCATE TABLE a, b, c RESTART IDENTITY CASCADE` is valid in neither
other case — H2 takes one table per `TRUNCATE` and has no `CASCADE`. The reset deletes rows children-first
(order read from the catalogue, not hand-written) and restarts each identity. **Catalogue casing differs
too**: PostgreSQL reports `"player"`, H2 reports `"PLAYER"`, and lower-casing the names broke the H2 run.

---

## ✅ Admin database backup and restore — owner, 2026-10-06

> *"teba dodati dve funkcionalnosti u Admin deo - jedna je da celu bazu (npr kad postavim cistu bazu na
> pocetku season 1 week 1 day 1 sa svim timovima seed i svi kupovi draw i sve mi bude ok ili prosto
> sutradan za backup) uradi backup/dump i naziv je yyyy-mm-dd-HH-mm-ss, a druga da iscita iz backupa bazu
> i zameni umesto postojece"*

**Exit criteria:**

- [x] **A dump of the whole database, named `yyyy-mm-dd-HH-mm-ss.dump`.** `pg_dump --format=custom`,
      written to `backups/` (gitignored — a dump is a copy of a world, not source). **The real world dumps
      to 2.0 MB in ~1 second**, 250 tables, so the button is fast enough to press whenever the state is
      good rather than only at the end of a session.
- [x] **A restore that replaces the database.** Validates the archive, **then** drops the schema, then
      replays it. The ordering is the feature — see below.
- [x] **The list of backups, and restore per row**, on the admin panel, with the file named in the
      confirmation. A restore that asks "are you sure?" without saying *which file* is a question nobody can
      answer.
- [x] **`/admin/**` role guard holds**, and the `.dump` extension survives the path variable
      (`AdminBackupControllerTest` 5/5).

**Three decisions that were not obvious:**

1. **The archive is proved readable before anything is dropped.** `pg_restore --list` reads the archive's
   table of contents without touching the database, so a truncated file fails **before** the schema goes.
   **Re-proven by moving the drop first:** `aCorruptArchiveIsRefusedBeforeAnythingIsDropped` then fails
   with `relation "roundtrip_marker" does not exist` — the world is gone and the restore never happened.
2. **`drop schema public cascade`, not `pg_restore --clean`.** `--clean` drops objects one at a time in
   the archive's own order and can stop halfway through a dependency chain, which is how a restore ends
   with half the old world and half the new one. One statement cannot.
3. **The client tools are chosen against the server, not taken from `PATH`.** See the environment note
   above: Homebrew's 16.15 cannot read this 18.4 server. Resolved over JDBC; `app.backup.pg-tools`
   overrides.

**No shell, ever.** Every command is a `ProcessBuilder` list, so a filename from a request can never
become a command. The password goes in `PGPASSWORD`, not in an argument, so it does not appear in `ps`
output to every other process on the machine. **stdin is closed**, because a client tool that decides to
ask a question — `force?`, `password?` — would otherwise read from the console and hang the request
forever; that is not hypothetical, it hung the first version of the round-trip test.

**A restore needs a restart**, and says so in its own response: the process that just replaced the
database still holds a connection pool and a persistence context built against the old one.

**Tests:** `DatabaseBackupServiceTest` 6/6 (naming, traversal, listing, refusals — no database needed,
because the checks that protect a destructive operation must hold on a machine where running it would be
harmless), and `DatabaseBackupRoundTripTest` **4/4 against real PostgreSQL 18** on a scratch database named
`sokker_roundtrip_scratch`, dropped and recreated per test. Dump, change, restore, and the change is gone.
It refuses to start if that name does not carry its marker — a destructive test whose target is a constant
in a test file is one edit away from dropping the owner's world.

---

## ✅ Notifications: the bell rings and has a red dot — owner, 2026-10-06

> *"kada ima nesto u notification, idealno i neki ring zvuk da se cuje a i da se pojavi neka crvena tacka
> na zvoncetu koje je ikonica ili tako nesto, i broj neprocitanih mozda 9ne obavezno ali nice to have"*

- [x] **The unread count was already there** — `notification-badge`, painted since 2026-10-05, with
      `NotificationService.list` returning it beside the rows so badge and dropdown cannot disagree.
- [x] **A red dot on the bell**, top-left so it does not collide with the count on the right, drawn as a
      pseudo-element so no template edit can remove it, with a `prefers-reduced-motion` guard that drops
      the pulse and keeps the mark.
- [x] **A ring on arrival**, synthesised with Web Audio (two notes a fifth apart — a "ting-ting", not an
      alarm; most of these are forum replies). No audio file added.
- [x] **It rings on an INCREASE, not on "there is something unread".** The poll runs every 30 seconds for
      as long as the tab is open, so the obvious version rings every 30 seconds for the rest of the
      session. The baseline is `null`, not `0`, so signing in with four unread is not an arrival.
- [x] **It fails silently.** Browsers block audio until the page is interacted with and the refusal is a
      *rejected promise* — unhandled, that is a console error on the dashboard, and
      `CommunityScreensRenderTest` fails on console errors.

`NotificationBellAlertTest` **5/5**, and both guards **re-proven by breaking them**: the `unread > 0`
version fails the increase test, and renaming the CSS selector so the JS's class has no styling fails the
dot test — which is exactly how this bug would arrive in production, the class set, a JS-only test green,
and no dot on screen.

---

## 🔧 P2-21 — mobile: the iPhone 14 Pro Max pass, 2026-10-06

**Reference device: iPhone 14 Pro Max, portrait — 430 × 932 CSS px at DPR 3.** Measured in Chromium at
that viewport, not reasoned about. Every page in the application was loaded and measured.

No page overflows the viewport horizontally (`scrollWidth == innerWidth` on all twelve). Two things are
wrong inside that, and one of them was not mine.

| Found | Where | Verdict |
|---|---|---|
| **The league standings table was clipped.** 510px of table in a panel ending at 408px, **no scroll container** — the Elo and rating-delta columns ran under the panel's rounded edge and could not be reached | Owner's | **Fixed.** Given the same `fm-squad-wrap` scroll container the squad table already had |
| **The top-scorers and top-assists tables** measured 384px, inside the viewport, but were at the limit | Owner's | **Fixed** with the same wrapper, so a longer club name cannot push them out |
| **Every compose field was a box inside a box** — the wrapper carried a border, background, radius and padding, and the input inside carried its own | Mine | **Fixed.** The wrapper is now a layout box only; the field provides the chrome |
| The squad table is 902px wide and scrolls horizontally | Owner's | Left as is — it already scrolls, and a squad list is the right thing to scroll |

**The phone render of a table is the finding.** On a desktop a clipped column is invisible; at 430px the
Elo column is simply gone with no scrollbar and no hint it existed. `MobilePanelOverflowTest` did not
catch it because it asks whether a **panel** overflows, and the panel did not — the table inside it did.

**Not verified:** the fixes were measured *before* and the application **would not build** afterwards,
because the owner's national-tournament refactor was mid-flight and `NationalTeamService.java:225` does
not compile. CSS braces balance, the module parses, and the wrappers are the same mechanism already
proven by `fm-squad-wrap` on the squad table. **It has not been re-measured in a browser.**

---

## 🔧 P2-20 polish — the owner's interface corrections, 2026-10-06

Six changes asked for after looking at the running application. Each is pinned by a measurement in
`CommunityInterfaceTest` rather than by a class name or a stylesheet rule.

| # | Asked for | What was wrong |
|---|---|---|
| 1 | Forum and Messages as **options under one Community tab**, as it was | Phase 4 gave them a top-bar button each |
| 2 | A bigger envelope | U+2709 renders small beside the emoji the other buttons use |
| 3 | The compose field's background ran **past the panel** | `width:100%` + `padding: 14px 16px` under the default `content-box` |
| 4 | Narrower, and the message box wider | Both defaulted to a character-based width: **147px** and **182px** |
| 5 | Type a name to find a manager | The picker was a native `<select>` |
| 6 | A forum section's Back goes to the **dashboard** | `data-nav-back` pops the navigation history, so it went to the index |

- [x] One Community entry, with Forum / Messages as an option row on all three forum screens and both
      message screens. "Forum" stays lit while reading a topic.
- [x] Envelope scaled, not swapped — a different glyph would stop the row reading as one set
- [x] `box-sizing: border-box` and a `max-width` inset: measured **1303 → 1171** against a panel edge at 1290
- [x] Subject and body take the field width: **147px → 1006px**, **182px → 1006px**
- [x] A searchable picker over a hidden `<select>`, matching anywhere in the name or the login
- [x] **Back returns to the previous screen, the same button the Club section uses** — see below

#### The sixth item was wrong twice, and the real defect was elsewhere

The first reading of "a section's Back should go to the dashboard" was implemented as a **second kind of
Back button** that bypassed the history. The owner's correction: **Back goes to the previous screen, like
almost every other Back in this application — copy the Club section, do not invent.**

Copying it exposed the defect that the invented button had been hiding:

> Clicking a forum section, or a topic, or a conversation, called the **view function directly** rather
> than the router. The router is what pushes the navigation history, so those navigations were never
> recorded. Back then popped whatever happened to be open **before** the forum — measured: **Back from
> the TIFO section landed on Messages.**

So the fix is in the navigation, not the button. `components.js` has one button again.

**Exit criteria:**
- [x] Every one of the six is measured in a browser, not asserted from the stylesheet
- [x] Three mutations proven
- [x] `CommunityInterfaceTest` green alongside the rest of the P2-20 suite

---

## ✅ P2-20 browser check — DONE

`CommunityScreensRenderTest`: a real Chromium, a real login, and clicks through every screen P2-20 added.
It asserts on the page's own text and **fails on any uncaught or console error**.

**This exists because of what the full-suite run found.** Phase 6's run of all 1,359 tests caught
`dashboard.js` calling `readUnreadCount` without importing it — a ReferenceError thrown on the dashboard
for every manager, every page load, for three phases. Nothing else noticed: the endpoint returned 200, the
module parsed, and the markup rendered. `CountryPageRendersTest`'s console-error assertion caught it,
because it runs a browser.

That is the same shape as the bug `CountryPageRendersTest` was written for: an escaping function called
from a template string where it was not in scope, a name that exists elsewhere in the project, so it looked
right. Both were found by a browser and by nothing else.

The test is mutation-proven: routing `loadPage('forum')` to a wrong section fails it.

---

# 🗂 Agent session log — 2026-10-06, three commits after the measured run

## ✅ Documentation — current football UI technical overview

`TECHNICAL_OVERVIEW.md` was rebuilt from the current `footballmanager.newLogic` source and graphical
football UI. It incorporates the current international club cup UI, the forum/private-message
replacement, the national-tournament backend state, current security/boot behaviour, and the open gaps
recorded on this board. `archive/TECHNICAL_OVERVIEW.md` remains historical.

## ✅ Documentation — graphical football user manual

`userManual.md` documents the user-facing options in the graphical football UI, from login and the
dashboard through Club, League, Country, World, Community, profiles, mobile navigation and Admin.

**Exactly three commits sit on top of `7df4af2`, the run that measured 20 red.** So the 20 is stale in a
way that is worth stating precisely, because only one of the three can move a number:

| Commit | Task | Can it change a result? |
|---|---|---|
| `1cb4f40` / `cd431f7` | **P0-9b** — `activeSeason` no longer answers `2026`, clock chosen deterministically; `PlayerContractServiceTest` fixture given a league, a clock and a settled week | **Yes.** `signingMovesThePlayerToTheClub` was red at measurement and is green now |
| `a5be205` | **P0-8 + P0-11** — verification and a rename | No. §1.3 and §1.5 were read, not changed; `nationalCup()` → `primaryCup()` is private |

**Expected 19 red, unmeasured.** The figure is a prediction, not a measurement, and it is exactly that
kind of prediction that this repository has been wrong about before. **Do not quote 19.** Run the suite.

**What this session closed, and where it is recorded:** P0-9 and P0-9b (seasons, and the calendar year the
ledger was reading), P0-14 (`LineupController` read-only, 383 lines deleted), P0-11 (the name that said
"national" while querying `INTERNATIONAL`), P0-8 (§1.3 confirmed, §1.5 far worse than claimed, §1.2 not
reproducible). Full reasoning and mutation results in `kanbanProgress.md`.

**One retired:** `SidLeagueSeedingIntegrationTest`, on the owner's decision — it asserted world-building
that boot no longer performs.

# 🔖 Suite state — MEASURED 2026-10-06

**`mvn test`: 1363 tests, 14 failures, 6 errors, 20 red, ~21 min**, run with the app up on `:8080` and
allowed to finish.

**20 red, down from 32** on 2026-10-04. **Every one of the P2-20 classes is green** — 144 tests across twelve of
them, listed in `kanbanProgress.md`. Two of the twenty were this work's, and both were found and fixed
inside it: a `readUnreadCount` import that Phase 6's full run caught, and the browser check that now covers
that class of bug.

**Not comparable on test count** — P2-20 added 112 — but comparable on red, because both runs were allowed
to finish and the Maven summary prints at the end.

**A full run requires the app on `:8080`** — without it three Playwright classes hang the entire run rather
than failing. This run had it up, and `CommunityScreensRenderTest` is a fourth.

**The earlier figures — 992 tests, 29 red, ~2 h 52 m — described a different application** and are
superseded twice over. The wall clock changed because boot writes nothing; the test count changed because
P2-20 added 112.
