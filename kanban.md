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
| **P0-1b** | `CommunityController`, `DummyDataController`, `StadiumSettingsController`, `TransferController` | open |

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
- [ ] The same four, for the four P0-1b controllers

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

---

### P0-13 — `TrainingController` writes the whole world on a request thread — NEW, 2026-10-03

Found while writing P0-1a, outside its scope, reported rather than absorbed.

**Both routes have zero callers** — not in `static/js`, not in `src/main`, not in one test:

| Route | What it does |
|---|---|
| `POST /training/train-all` | `playerRepository.findAll()` → `trainPlayer` on each → `saveAll`, returning every player as a raw entity. **~300,000 rows at full scale.** It also duplicates day 4's `TrainingJob`, which is how the world is actually trained |
| `POST /training/train/{playerId}` | Trains and returns **any** player in the world, by id, as a raw entity |

**The rule already exists in the same file, 40 lines below `trainPlayer`.** `setIntensity` documents
*"a player who does not play for this club is a 403, not a bad request"* — and `plusFeatures` is already
injected. So the inconsistency is the finding: half this controller enforces ownership and half does not.

**Owner's decision, 2026-10-03:** fix both and **record `/train-all` as a deletion candidate.** A role
guard was explicitly *not* treated as the answer, because it makes an operation nobody wants reachable on
purpose — it is still the most expensive request in the game and it still duplicates a scheduled job. A role
check answers "who may" without answering "should this exist".

**Exit criteria:**
- [ ] `/train/{playerId}` refuses with 403 unless the player is in the caller's own club
- [ ] `/train-all` is administrator-only, and returns a DTO rather than `List<Player>`
- [ ] Neither returns a raw `Player` entity
- [ ] **`/train-all` ruled on:** deleted, or kept with the reason written down

---

### P0-14 — `LineupController`'s writes are unreachable from the frontend — OWNER-GATED

Ruled on 2026-10-03 as **guard, do not delete.** Recorded because the reasoning matters more than the ruling:
the frontend files a squad sheet through `TeamController`'s `lineup-template`, and `grep` over `static/js`
finds no caller for `POST /lineups` or `DELETE /lineups/{id}`. They are now guarded and working.

**Unreachable is not the same as harmless** — three defects lived in exactly these routes, including one
that had never worked. The deletion question is open but is not urgent.

**Also found here and fixed in passing:** `LineupController`'s sort whitelist offered `"name"`, a column
`Lineup` does not have, so a caller could ask for a sort on a property that does not exist.

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

### P0-4 — ANSWERED 2026-10-03, deferred by the owner: both stay identical for now

**The owner:** both stay identical *until* he decides whether a user should be able to build a separate
tactic for each phase. **Not a defect, and not a deferral of the work — a deliberate hold.**

**What that holds in place, and it is the same hold as before:**

- `TeamTacticsService.mirrorWeHaveBallRules` keeps mirroring on every save and every read
- `DefensiveShape` stays **load-bearing rather than decorative** — it manufactures the defensive shape the
  data cannot carry
- the multi-tactics work stays unwritten, because the storage design depends on the answer

**What it unblocks:** **P0-3.** The away side carrying its own `TacticsRules` is a runtime concern about
perspective, not a storage decision, so it does not wait on this.

**Still open, and it is a different question from the one answered here:** whether a manager should ever be
*able* to author two phases. If yes, the tactic table becomes keyed by `(tactic, possession_context)` and
roughly doubles the rows; if no, `mirrorWeHaveBallRules` and the duplication both go away and the data model
gets simpler than it is today. That is the follow-up, not this task.

**The evidence the decision rests on, unchanged:** `TeamTacticsService.mirrorWeHaveBallRules` makes
`WE_HAVE_BALL` and `OPPONENT_HAS_BALL` identical on **every save and every read**. All 506/506
out-of-possession rules in the shipped data are byte-identical to their in-possession twins. That is why
`DefensiveShape` is **load-bearing rather than decorative** — it manufactures the defensive shape the data
cannot carry.

---

### P0-5 — ANSWERED 2026-10-03: drop the four, keep `OFK Omladinac`

**The owner drops the four orphans.** Verified against the file rather than the board: five profiles, and
**only `OFK Omladinac` (4-4-2) names a club that exists.** All four orphans are 4-3-3 — `FK Beograd`,
`GFK Dinamo Šabac`, `GFK Tamiš Gornji Milanovac 1901`, `SK Čačak 1912`.

**This closes the decision, not the work.** The code change — editing
`var/tactics-editor-profiles.json` down to one profile, and dropping the restore's "reports them by name"
behaviour that exists only because of them — is **not yet made.** Tracked under P0-15.

---

### P0-15 — carry out P0-5: reduce the tactics profiles to the one that places — NEW, 2026-10-03

**Why the four cannot be placed:** reset restores tactics from `var/tactics-editor-profiles.json`, and the
world has five Beograd clubs and none is called "FK Beograd". The restore already reported them by name
rather than failing silently, which is why this was cosmetic rather than breaking — it was P0 because a
decision was owed, not because anything was on fire.

**Exit criteria:**
- [ ] `var/tactics-editor-profiles.json` holds one profile, `OFK Omladinac`
- [ ] The restore no longer reports unplaceable profiles by name, because there are none to report
- [ ] `TacticsRulesProviderTest` still green, and the reset path still verified against a real database

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
| 1.4 | `MatchPersistenceService` is dead code, 402 lines, zero callers | **CONFIRMED by P1-1** — zero callers in `src/main`, its table holds 0 rows. Deleting it is the owner's call |
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

### Measured already — do not redo

| Change | Before | After | Commit |
|---|---:|---:|---|
| Daily recovery zone-load read *(155-match dev database)* | 21.0 ms | **10.8 ms** | `d95da9d` |
| Weekly rollover squad reads (30 clubs, 3 listings) | 180 queries | **0** + 1 bulk | `e310856` |
| Four fixture endpoints: game week used as a round | 155 fixtures for every week and day | correct matchday | `7374c69` |
| League top scorers / assists *(89,280-match season)* | 158.7 ms | **0.19 ms** | P1-1 |
| Club match history, played, ordered *(same)* | 170.3 ms | **0.26 ms** | P1-1 |
| Club page, all matches *(same)* | 140.6 ms | **0.20 ms** | P1-1 |
| One week of a season *(same)* | 156.4 ms | **27.8 ms** | P1-1 |
| Club Elo replay — 155 matches, with the 742 KB event log | 443–554 ms | **6.5–11.2 ms** | P1-7 |
| `event_json` written per match | 840,136 B | **17,001 B** | P1-7 |
| A match's 198 zone loads — **no index created, query has no caller** | 629 ms | — | P1-1 |
| Recovery read, one matchday *(17.7M-row table)* | 4,441 ms | **still open, P1-3** | — |

**Read the scale column.** The first three rows were measured on a 155-match village; the P1-1 rows on a
full 89,280-match season. **A number is only comparable to a number measured the same way**, and the
first three are not comparable to the last five.

---

### P1-7 — Partly done. The two Elo replays and the blob itself are fixed; three readers are not.

`Match.eventJson` is the whole per-tick decision log in one text column. **It was 742 KB – 1,035 KB a
match: 124 MB for 155 matches, ~66 GB a season.** Two things were wrong with it — the Elo replays read it
without needing it, and it was 98% noise even for the pages that do read it.

**Landed, both measured in the database:**

| | before | after |
|---|---:|---:|
| Club Elo replay, 155 matches | 443–554 ms, ~66 GB a season | **6.5–11.2 ms, ~5 MB a season** |
| `event_json` per match, written by the app | 840,136 B | **17,001 B — 49.4× smaller** |
| events per match | 3,065 | **21** |

`SimReportMapper.eventJson` now writes only the types a page can use, from a keep-list derived from
`MatchDetailService.mapEventToDTO`, `ZoxApiController.buildTimeline` and `GoalEventRepository.isGoal`.
All three readers were checked against a running app on a real new match: 9 timeline items, 10 scorer
rows, 46 detail events. **The full log is not lost** — `SimReplayStore` writes it to a file, which is what
the replay viewer reads.

**Still open, all three measured, none fixed:**

| Site | Cost | Why not fixed here |
|---|---:|---|
| `TeamController:250` club history, `MatchDTO` carries no JSON | 12 rows × 742 KB = **8.9 MB per page view** | A `MatchDTO` projection is wider than a replay fix |
| `TeamController:577`, `ScheduleInsightService:70` | same | same |
| `GoalEventRepository.findByMatchSeasonYearAndScoredTrue` — `LeagueMilestoneService:71,75` | **89,280 × 742 KB, twice** | Needs the query, not the blob |

**And two things that are now cheap to fix, because the blob stopped being the problem:**
`findByMatchCompetitionIdAnd…` was 44 MB per request and is now ~17 KB a match for new matches;
`LeagueMilestoneService` still calls it **twice**, once for goals and once for assists.

**Exit criteria for what is left:**
- [ ] The club-history paths read a `MatchDTO` projection, not `Match` entities
- [ ] `LeagueMilestoneService` parses a season once instead of twice
- [ ] The existing 155 matches keep their big blobs until the world is reset — **no migration was run,
      deliberately.** Say if a backfill is wanted

**Two defects found here and recorded, not fixed:**

- **`GoalEventRepository.isGoal` credits goals VAR ruled out.** It tests `type.contains("GOAL")`, so
  `GOAL_DISALLOWED` (15) and `VAR_GOAL_OVERTURNED` (15) count as goals and put a player on the
  top-scorers list. **A product decision, not a performance one:** does a disallowed goal belong on the
  list struck through, or not at all? The keep-list preserves today's behaviour on purpose until it is
  answered.
- **`simulate-all` under-reports.** It returned `simulatedCount: 5, leaguesProcessed: 1` while **160
  matches** were written, because `AsyncSimulationRunner` continues in the background.

---

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

**Exit criteria:**
- [ ] Rows-per-match and elapsed time measured before and after
- [ ] If `IDENTITY` blocks it, that is recorded and the task stops — it is P0-12's to unblock, not this
      task's to work around

---

### P1-3 — The recovery job's cost is a sequential scan of `player_zone_load`, not of `match`

**The board's version of this task is settled.** `findByLastPlayedAtIsNotNull()` no longer has a
caller — `dddf462` replaced it with `findByIdInAndLastPlayedAtIsNotNull(ids)`, which the primary key
serves. **So there is no index to add on `last_played_at`, because there is no longer a query for it
to serve.** That part of the board is answered, not open. `findByInjuryDaysRemainingGreaterThan` is
still a real unindexed scan on `injury_days_remaining` (`SeasonService:577`) and still needs one.

**What is actually left, measured at full scale** (89,280 matches, 17,677,440 zone-load rows — one
season at 48 countries, in a throwaway database):

| | |
|---|---:|
| `findLoadMinutesPlayedSince`, one matchday window | **4,441 ms** |
| rows it returns | 1,473,120 |
| rows it reads | 17,677,440 — the whole table, to find one matchday |
| the `match` scan the board blamed | **112 ms, 2% of the query** |

**The fix is not an index, and P1-1 proved it.** The projection's own javadoc names the right answer
and says it is not written yet: **keyset paging on `match.id`**. Paged, each batch is an index range
scan and the job reads 1.47M rows instead of 17.7M. Unpaged and forced onto the index, the same query
runs in **889 ms against 5,318 ms** — so the index is worth having *for this query*, it just cannot be
landed before the read is paged, which is why P1-1 left `player_zone_load` alone.

**Exit criteria:**
- [ ] The recovery read is paged, and **the page boundaries are keyset on `match.id`** — offset paging
      over a join with no total order can skip and duplicate rows, which would silently under-count a
      player's recovery
- [ ] **Recovery still happens.** Correctness-adjacent: a faster recovery that recovers fewer players
      is a new P0 defect. Verified by **counting recovered players**, not by the clock
- [ ] `findByInjuryDaysRemainingGreaterThan` measured and indexed, or recorded as cheap
- [ ] The whole job measured on the harness before and after, on the same basis as P1-1's numbers

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

### P1-5 — `match` grows without bound; `match_tick_states` does not grow at all

**Half of this task is refuted.** `match_tick_states` gains **zero** rows per match: nothing writes it.
Its writer, `MatchPersistenceService`, has no callers, and replays are file-backed JSON with a bounded
retention of their own. There is nothing to retain and nothing to decide. See P1-1.

What remains is `match`, and the honest question is what a full season costs:

| | |
|---|---:|
| matches per matchday, 48 countries | 7,440 |
| matches per 12-week season | **89,280** |
| `event_json` per match, **as written today** | **17,001 B** (was 742 KB – 1,035 KB) |
| one season, raw text | **~1.5 GB** (was ~66 GB) |
| one season, on disk (TOAST-compressed) | **~240 MB** (was ~9.2 GB) |

**The per-tick log stopped being written, so the growth question is much smaller than it was.** What is
left is a season's worth of results and reportable events. `SimReplayStore` keeps the full per-tick log in
files, with its own retention.

**Exit criteria:**
- [ ] Growth rate measured per simulated matchday, from the harness rather than extrapolated
- [ ] **A retention decision for the existing `event_json` blobs.** 155 rows hold 124 MB of the old
      format and nothing reads them but the top-scorers and match pages. Reset clears them; a backfill
      does not exist and probably should not
- [ ] A retention decision for the `match` row itself. **Before anything is deleted:** the Elo replay
      (`findPlayedClubScoredInOrder`) re-reads **every** played club match in date order, and the club Elo
      history needs its old ratings. Deleting match rows silently breaks both, so the answer is probably
      "keep them" — but it has to be an answer, not an omission
- [ ] The file-backed replay retention checked against the same question, so the two answers agree


---

### P1-6 — The other three sports have no declared indexes at all

Basketball, American football and text-based football have **zero** `@Index` declarations. They are 5,580
and 3,720 players on the dev database and they are not simulated on every tick, so this is genuinely lower
priority than P1-1 — but it is not zero, and it will not get cheaper to fix after the tables grow.

**Exit criteria:** the same treatment P1-1 actually got, which is not the treatment the board asked
for: **every index needs a named query and a before/after.** Two of P1-1's three candidates had no
query behind them at all, and the third made things worse. So:

- [ ] The queries those tables actually serve are listed first, from the source
- [ ] Each proposed index names the query it serves and carries a measured before/after on the harness
      — no index proposed on the argument that the column is obviously filtered on
- [ ] Write cost measured, as `match` cost 16.8 µs per row

---

# 🟢 P2 — features and visual work

**Everything here can be completed on its own, without a decision from anyone.** Ordered by leverage per
day of work, following `archive/COMPETITIVE_ANALYSIS.md` §10, whose ordering is deliberate.

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

### P2-3 — Player can refuse to be listed — the anti-daytrade mechanic

One day. The `objection` field already exists to support it. One of the more interesting differentiators
in the analysis, because it is a mechanic neither competitor has rather than a number being bigger.

**Exit criteria:** a listed player can object; the club cannot list him without resolving it; refusal has a
visible reason.

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
- The full red list and what each red class means is in `archive/kanbanProgress.md`, in the entry recording
  the run that was allowed to finish.
- **P0-1 and P0-2 are the two tasks that make everything else safer to do.** Do them first.
- **P1-1 is done, and it is the worked example for the rest of P1:** measure at projected scale, name the
  query behind every index, and be willing to land two fewer indexes than the board asked for. Two of its
  three candidates had no query behind them at all.
