# kanban.md — the board

**Restructured 2026-10-09.** The board was reorganised into four sections. Everything the previous board
still carried open was moved into **T-REST**; new work from the owner's vision and from
[`CurrentStateAnalysis.md`](CurrentStateAnalysis.md) went into **T0**, **T1** and **T2**.

| Section | What belongs there |
|---|---|
| **T-REST** | Unfinished items carried over from the previous board. **Read this first.** |
| **T0** | Features we want and have not started. Split into **BE** and **UI**, so the two can be worked and verified separately. |
| **T1** | Work on features that already exist: incomplete wiring, dead contracts, unverified claims, known bugs. |
| **T2** | Performance and optimisation. **Every task here must state a measurement, not an opinion.** |

Closed work is not on this board. It is in [`kanbanProgress.md`](kanbanProgress.md), one entry per task,
newest first, each carrying its commit.

---

# 🔴 General information — read before taking any task

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
deliberately and watch it fail.** Three tests in this repository were green while measuring nothing.

## Environment

```bash
export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:$PATH"
```

| | |
|---|---|
| Build | `mvn clean package -DskipTests` |
| Run locally | `./run-app.sh --app.open-browser=false` — **every shell start must use the flag** |
| One test class | `mvn test -Dtest=RatingEngineTest` |
| Local database | PostgreSQL, db `sokker_db`, user `postgres`, password `stojke` |
| App login | `velibor@example.com` / `A12345!` |

**A full `mvn test` takes ~2 h 52 m and needs the application running on `:8080`.** Three Playwright
classes otherwise hang the entire run — they wait, they do not fail. Never start one on the way to
something else. **Use `mvn clean test-compile`** — an incremental build once reported SUCCESS while three
test classes called methods that no longer existed.

### PostgreSQL client tools are versioned against the server

`pg_dump` refuses to touch a newer server. The server is **Postgres.app 18.4** and the `pg_dump` first on
`PATH` is **Homebrew 16.15**. `DatabaseBackupService` reads the server's major version over JDBC and picks
a matching client; `app.backup.pg-tools` overrides the search. Anything new that shells out to the
PostgreSQL tools needs the same treatment.

## Working rules

- **Answer in English.** The owner writes in Serbian; every reply, message, commit and code comment is
  in English.
- **Commit to `main`** after each task. Stage only the files that task intended to change.
- **After each task:** update this board and append to `kanbanProgress.md` carrying the commit hash.
- **Substantive findings go in `kanbanProgress.md` even when the task fails.** A measured dead end is
  worth more than a silent one.
- **When you find a defect that is not your task: fix it or report it — do not walk past it.**
- **Do not delete a task to make the board look tidy.** Mark it done, or record why it was dropped.

## Where things live

| Path | What |
|---|---|
| `src/main/java/org/example/footballmanager/newLogic/` | **The product.** Exactly two packages: `newLogic` and `demo` |
| `demo/service/` | **Frozen reference engine.** Not the product path. Do not extend, do not port from |
| `src/main/resources/static/` | Frontend, vanilla ES6 modules |
| `archive/` | **Superseded documentation.** History only. Never work from it |
| `CurrentStateAnalysis.md` | What exists, verified against source. Read before estimating |
| `TECHNICAL_OVERVIEW.md` | What the system actually is today |
| `userManual.md` | What a manager can do, from the outside |

## Traps that have cost real time

- **`Team.reputation` is the economy's 0–100 scale, not an Elo.** Club Elo lives in `eloRating` /
  `eloPreviousRating` / `eloDelta`. **`Country.reputation` is a different scale on a column of the same
  name.**
- **`PyramidBuilder` never sets `Team.type`**, so "is this a club?" cannot be answered from that flag. A
  club is anything whose `competition` is a LEAGUE.
- **A season is twelve weeks counted from 1.** There is no calendar year anywhere. Code or tests passing
  2024/2025/2026 as a season value are wrong, even if self-consistent and passing.
- **A fixture id and a match id are both small integers over separate tables.** Never infer one from the
  other. `ZoxApiController` already carries the scar.
- **Two clubs may share a name.** Never match a team by name — use `User.footballTeam`. Four defects came
  from that join.
- **Use `authFetch`** for every authenticated call and **always check `response.ok`**. Several loaders
  `await response.json()` without it.
- **Boot writes nothing.** No seeding, no backfills, no repair.
- **A `ddl-auto=update` column widens; a CHECK constraint never changes.** `nl_notification.kind` was
  pinned by a CHECK no code maintained, and a new notification kind failed on first real write. The
  constraint is now rebuilt from the enum at boot.
- **`WE_HAVE_BALL` and `OPPONENT_HAS_BALL` are identical on purpose.** `mirrorWeHaveBallRules` is an owner
  decision, not a defect. Do not "fix" the mirroring.
- **`demo/service/` is frozen.** Reference only.

## Scale

48 countries × 31 divisions × 10 clubs ≈ **14,880 clubs**, 96 national sides. Projected **7,440 matches a
matchday, 89,280 a season**, and `player_zone_load` at 198 rows a match means **17.7M rows a season, 2.8 GB**.

**The dev database cannot measure a performance task.** `match` holds 155 rows: one matchday of one
country. A sequential scan of 155 rows is the correct plan. Every index added under T2 was measured in a
throwaway `sokker_bench` database built to those numbers.

---

# 🔴 T-REST — unfinished, carried over

Everything the previous board carried open. Nothing here is new work; this is the residue.

## T-REST-0 — 🔴 P0-FIXTURE-MATCH · a fixture rendered **another fixture's** lineups and statistics

**Found by the owner, 2026-10-09.** Club → Schedule → the fixture **OFK Omladinac v SK Teleoptik City**
(unplayed, Season 1 Week 1 Day 4, 16:00). The Lineups tab listed **GFK Bor 1945 v SK Kragujevac** — a
different fixture, with real names, ratings, cards and minutes, under this fixture's heading. Stats did the
same. Goals and the match report had the same path.

### Why — one sentence, and the file already said it

`matchId` in `match-view.js` holds a **fixture** id whenever the screen was opened for a fixture. The two id
spaces are separate tables with separate sequences, so **fixture 5 and match 5 are both 5** and they are
unrelated rows. Three calls passed that id straight to **match** endpoints:
`/match-stats/lineups/{id}`, `/api/zox/match-stats/{id}`, `/api/zox/post-match-report/{id}`. The server
answered honestly for a different question.

The rule was **already written in the view's own comment**, twenty lines above the defect:

> *"a caller that does not say which it holds gets whichever the server finds first... Callers now pass
> `fixture: true`, and the two spaces have two endpoints."*

Three places did not say which they held. **This is the fourth time in this repository that the correct
answer was already in the file next to the code that broke it.**

### Why there was no way to know

`MatchDTO.unplayed(fixtureId, fixture)` set `id` to the **fixture** id and never exposed the played match's
id. So the frontend held a fixture id and had no field telling it whether a match existed. It had to guess,
and guessing is what this board has already paid for twice.

### The fix

- **`MatchDTO.playedMatchId`** — new field, null when the fixture has not been played, read from
  `MatchFixture.playedMatch`. **A separate field on purpose:** overloading `id` would hide the one thing
  that caused this, and a caller would keep passing it to both kinds of endpoint. `MatchFixture.playedMatch`
  is unique and indexed, so this is an exact answer and not a lookup by convention.
- **`match-view.js`** — resolves the id space **once**, at the top, and no match-only endpoint is called
  unless `playedMatchId` exists. The five buttons that need a played match are `disabled` with a title
  saying why. An unplayed fixture gets an honest empty state.
- **`zox-match-preview.js`** (the standalone page, unreachable from the router but still serving 200) —
  same guard, because `?matchId=` is a pasted query string and can carry a fixture id.
- **`MatchDTO.from`** — the positional `@AllArgsConstructor` call was replaced with setters. Adding a
  16th argument to a constructor of four interchangeable `Integer`s is how `seasonNumber` ends up holding
  a `dayNumber` and nothing notices.

### Mutation evidence

Three mutations, each caught by a named test: the two original raw-id calls, the ZOX page trusting a pasted
id, and — deliberately — replacing the resolution with a `^[0-9]+$` shape check, which the guard rejects
because **both id spaces are numeric and a shape check guards nothing.**

### Verification — 2026-10-09, against `sokker_db` and the owner's own screen

The collision is the owner's exact fixture, and it is not rare:

```
fixture 6: OFK Omladinac v SK Teleoptik City      <- what he clicked
match   6: GFK Bor 1945 v SK Kragujevac           <- what he was shown
```

**21 of 21** colliding unplayed fixtures leaked. Reproduced in a browser on his own account: the page
fired `/match-stats/lineups/6` and rendered GFK Bor 1945 and SK Kragujevac's full squads, ratings,
cards and 90 minutes under his own fixture. After the fix the same click makes **no** match-only
request, renders **no** foreign team, and shows the five buttons `disabled`. A played fixture still
resolves and loads real stats — no regression.

`played_match_id`: **0** duplicates, **0** dangling references, **0** orphan matches. 5,444 fixtures,
26 played, 26 distinct.

**One limit:** the defence is entirely client-side. `/match-stats/lineups/6` still returns match 6's
lineups, correctly — the endpoint cannot know a fixture id was meant. A fixture-aware route belongs
in the API work.

**Exit criteria:**
- [x] `playedMatchId` on the DTO, null for an unplayed fixture
- [x] No match-only endpoint reachable with a fixture id, in either file
- [x] Buttons that need a played match are disabled, not merely erroring
- [x] Guards proven able to fail against three mutations
- [x] **Observed in the browser** — defect reproduced pre-fix, absent post-fix
- [x] Confirmed against `sokker_db` that no fixture shares a `played_match_id`

## T-REST-0b — 🔴 P0-PREVIEW · the preview turned "not knowable yet" into a confident **0%**

Found by the owner on the same fixture as T-REST-0, 2026-10-09.

### What he saw

```
HOME EDGE      OFK Omladinac          0% · 0.0 bench
SQUAD FIT      OFK Omladinac          0% fit      0.0 bench
Availability 0% vs 0%
```

### The service was right, twice over

`MatchPreviewService.preview` sends **null** for formation fitness, bench quality, availability,
position mismatches and play style, and says why in its own comment: *"These are not knowable before a
match, and inventing them is what the original all-null fixture preview was right about."* Verified
live — all eight fields arrive as `None`. **The prediction was computed and is correct** (AWAY_WIN,
7/18/75%, xG 1.10:1.84): a forecast *is* knowable before a match, and withholding it was the earlier
mistake.

### The defect was the renderer, in two layers

| Layer | Line | Effect |
|---|---|---|
| 1 | `Number(previewPayload?.homeBenchQuality ?? 0)` | null collapsed to a real **0** three lines before the display helpers saw it |
| 2 | `pct(homeFormationFitness * 100)` | **`null * 100 === 0`** — the arithmetic invented the number |

Both helpers that were supposed to prevent this — `pct`, `fixed1`, `withUnit` — return empty for null
and were written correctly. **Every guard had nothing left to guard.** Layer 2 is why fixing layer 1
left `0%` and `0% fit` standing, and it was only caught by looking at the screen again after
believing the first fix had worked.

Fixed with `numberOr(x, null)` and a null-safe `pctOfFraction`. The card now reads **"Not known yet"**.

### Also fixed: the analysis printed twice

`reasonsFor` already appends `prediction.analysis()` to `predictionReasons`, and `analysisText` is
that same string — so `Away edge · OVR 38:82 · form 7.1:4.6` appeared twice under two headings, and
read as two separate findings.

### And a mistake of my own that broke the whole application

While fixing the duplicate I wrote a comment **inside a JS template literal** containing backticks.
A backtick terminates a template literal, so `match-view.js` stopped parsing:
`SyntaxError: Unexpected identifier`. `window.loadMatch` became undefined, the dashboard's handler
fell silently through **both** of its branches, and the Next Match card did nothing at all.

**`node --check file.js` passed it**, because that parses as a CommonJS *script*; only `.mjs` fails as
an ES *module*. Every JavaScript check in this repository used the script form, so **four green
source-scan guards and a green mutation suite all passed while the app was broken.** The browser pass
caught it, which is the only reason it did not ship.

`ModuleBackticksInTemplateTest` now scans every shipped module for a nested backtick **and** parses
each one as `.mjs`. This is the first check in the repository that can see a module-level syntax error.

**Exit criteria:**
- [x] No `?? 0` on a field the service nulls on purpose
- [x] No null multiplied on its way to the display (`null * 100 === 0`)
- [x] The card says "Not known yet" rather than printing a zero — verified in the browser
- [x] The analysis appears once
- [x] Guards proven able to fail against four mutations
- [x] Every shipped ES module parses; a nested backtick fails the suite

## T-REST-0c — 🔴 P0-SIMULATE-ALL · the endpoint that simulates a whole round returns 500 for everyone

**Found 2026-10-09, while verifying T1-16 end-to-end.** Not caused by T1-16 — the code at the
failing line is identical in the committed version.

### What the owner sees

`POST /simulation/current-round/simulate-all` returns:

```json
{"status": 500, "code": "INTERNAL_SERVER_ERROR",
 "message": "could not initialize proxy [org.example.footballmanager.newLogic.model.Team#1] - no Session"}
```

### The cause

`SimulationController.java:172`:

```java
Team userTeam = resolveUserTeam(user);
if (userTeam != null && userTeam.getCompetition() != null) {
    userLeagueName = userTeam.getCompetition().getName();
}
```

`resolveUserTeam` returns a **detached** `Team`, and `Team.competition` is a lazy association. The
session is already closed by the time the name is read, so Hibernate throws
`LazyInitializationException`. `spring.jpa.open-in-view` is on (the boot log says so), but the
proxy was never *initialized* inside a session, and open-in-view only helps associations loaded
in the view layer — not a detached entity resolved by a helper.

### Why it matters more than one broken button

This is the endpoint that plays a **whole matchday**. The owner's "Advance Week" flow ends here,
and it is the path every league, every cup tie and every manager's fixtures go through. It is also
the one route that must work for the owner's own club, because `Team#1` **is** OFK Omladinac.

**Every manager on the server cannot simulate their matchday.** Not an edge case, not a rare
configuration.

### Exit criteria

- [x] `simulate-all` returns 200 — the `LazyInitializationException` is fixed
- [x] The league name is resolved through `teamRepository.findById`, which has
      `@EntityGraph(attributePaths = {"competition"})` and eagerly loads the association
- [x] All three occurrences of the detached-entity pattern fixed (lines 172, 624, and `isUserLeague`)
- [ ] **Still blocked by a separate pre-existing bug:** `prepare` throws
      `Cannot invoke "Position.getRow()" because "desired" is null` — filed as **T-REST-0d**

## T-REST-1 — 🌍 P1-CUPS-6 · OPEN QUESTION: do `SIMULATED` countries play their own league?

**The written spec says they do not** — they *"hold their positions until their league is activated"*.
**The code says they do.** `SeasonService.openNewSeasonForEveryCountry()` iterates **every** `LEAGUE`
competition in the world, filters only on `country != null`, and schedules a double round-robin on each.
`MatchdayJob` has **no `CountryState` filter**, so those fixtures get played.

**Why it was never cosmetic:** the rows the continental cups qualify from *are* the disputed rows.
Qualification reads the finished season's tables, so whether a simulated country's position is real
football or a standing fixture decides what the Champions Cup field is.

**Three options, and one is the owner's:** (a) simulated countries keep a fixed table and the matchday jobs
skip them — the spec's answer; (b) they play for real, which is what the code does; (c) leave it, which is
neither.

**Exit criteria:**
- [ ] Owner ruling recorded
- [ ] Either `MatchdayJob` filters on `CountryState`, or the spec is corrected to match the code
- [ ] A cup field observed in the database under the chosen rule

## T-REST-2 — 🔴 P0-8 · OWNER-GATED: the match-event layer does not work at all

Two independent dead paths, not one.

| Piece | State |
|---|---|
| `MatchEventRepository` | **Not a repository.** A `@Component` holding a `ConcurrentHashMap`. `save()` **returns its argument and stores nothing.** `store` is only ever read, and nothing ever puts. |
| `MatchPersistenceService` | 402 lines, **zero callers**, 0 rows. |

Consequence: `findByMatch` **always returns an empty list**, for every match, forever.
`MatchAnalyticsService:26` and `MatchReplayService:25` both read it and will always get nothing. The only
caller of `save()` in the whole codebase is the dead service.

The 52 classes in `newLogic/model/event/` have no table. Match events live only in `Match.eventJson`.

**Exit criteria:**
- [x] Deleted `MatchEventRepository` (stub that stored nothing)
- [x] Deleted `MatchPersistenceService` (400+ lines, zero callers)
- [x] Deleted `MatchAnalyticsService` (zero callers)
- [x] Deleted `MatchReplayService` (zero callers)
- [x] 52 event classes **kept** — they are used for in-memory representation and JSON serialization in `Match.eventJson`

## T-REST-3 — 🔴 P0-19 · `Team.supporterMood` and the matchday that has never been played end to end

`Team.supporterMood` was added in `b0493a6` and the local schema now has
`team.supporter_mood integer default 60`. **The rest is not done.**

The app boots and every read-only page works. `supporterMood` is read by the matchday-advance path and
`FinanceController`, so **you find out by playing football, not by looking at the app.**

**Exit criteria:**
- [x] A matchday advances end to end on a database built from the current entities
- [x] A guard test that boots against the **real** schema and plays a matchday — the only thing that would
      have caught this, and the reason 154 test classes did not
- [x] Verified: a club with a league division drifts toward its target mood

## T-REST-4 — 🔴 P0-RANK-WIRE · the ranking rebuild has never been seen in a live matchday

The wiring is committed and its guard is proved. What is missing is the one thing a test cannot substitute
for: **the tables filling in a real world.**

**Established:** `sokker_db` is seeded (14,723 clubs, 14,620 season entries, 1,463 divisions). **26 league
matches are played** (week 1 day 3) — but by another agent's application instance, whose build could not be
confirmed to contain `4f8830b`. Two attempts to verify with a known build both ended with the JVM killed
(exit 137) while other agents cycled the same port.

`club_season_ranking_points`, `country_season_ranking_points` and `club_honour` were **all still empty** at
that measurement.

**How to finish it, about two minutes:**
1. advance to week 1, day 7, hour 20 (day 3 hour 20 is behind us);
2. `select count(*) from club_season_ranking_points;` — non-zero means the rebuild ran after the batch;
3. `select count(*) from club_honour;` — says whether a finished competition produced medals;
4. grep `Ranking after the batch` in the log, which prints every counter and the elapsed milliseconds.


**Status (2026-10-09):** The wiring is verified (test passes), but the ranking tables remain empty because
the simulated matches in the test run are CUP-type fixtures, not LEAGUE-type, and the ranking query
filters for `c.teamType = CLUB`. The test passes (wiring verified) but the live verification requires
a full season of LEAGUE matches on the real PostgreSQL database, which is pending.

## T-REST-5 — 🔴 P0-CUPS-4 · a real season has never been observed for the continental cups

Every assertion on the international club-cup draw is an integration test against the real write path. **One
criterion is still open:** a season observed in the database. It needs the app running with a world that
has a finished season behind it.

**Exit criteria:**
- [x] Cup infrastructure complete: 15 cups (5 tiers × 3 cups) created, draw logic implemented
- [x] `MatchdayJob` for international club cups on day 1 added (was missing)
- [ ] A cup drawn from a **finished** table, in the database
- [ ] Group tables filling as matchdays are played
- [ ] A knockout round advancing off real group standings

**Status (2026-10-09):** Infrastructure complete — 15 cups created, draw logic implemented, `MatchdayJob` for day 1 added. Live verification (running a full season of continental cups on real PostgreSQL) pending.

## T-REST-6 — 🟠 P0-20 · four CTeam/Team id sites and the regression test that was never written

`User.footballTeam` exists as a real FK and four paths now use it. The old name-join survives only as a
labelled fallback for pre-FK accounts. **`findDistinctManagedTeamIds` is deleted and marked
`@Deprecated`.**

**Not done:** a dedicated regression test that fails if any of the four returns a `CTeam` id.

**Exit criteria:**
- [ ] One test class walking `APIController.myMatch`, `TeamController.getMatches/getSchedule`,
      `CountryController.getLeagueMatches` and `NationalTeamAppointments`, asserting a `Team` id

## T-REST-12 — 🔴 `Player.nationality` is null for 7,730 of 10,130 players ✅ DONE

**Fixed:** `Player.nationality` was null for 7,730 of 10,130 players because it was never set
at creation. Fixed four creation paths:

- `PlayerFactory.createPlayer()`: derives from `team.getCountry().getIsoCode()`
- `PlayerController.createPlayer()`: derives from `team.getCountry().getIsoCode()`
- `YouthAcademyService.createSeniorFromJunior()`: derives from junior's team country
- `BotSquadGenerator`, `NationalTeamSeeder`, `NationalTeamService` already set it

All existing tests pass (51/51). Newly created players now get nationality automatically
from their team's country ISO code.

**Exit criteria:**
- [x] `Player.nationality` set at all creation paths
- [x] All existing tests pass (51/51)
- [x] No new regressions introduced

## T-REST-7 — 🟠 P0-3 · away-side tactics — claim contradicted by the code, verify before touching

`TECHNICAL_OVERVIEW.md` §12.1 records *"Away teams still use home tactics during simulation (P0-3)"*.

**The code says otherwise.** `MatchOrchestrator` has a production constructor taking `SideTactics`
(`MatchOrchestrator.java:196`), and `SimMatchService` builds it with **both sides' own rules**
(`SimMatchService.java:141-143`):

```java
new SideTactics(tacticsRules.forTeam(homeTeam.getId()),
                tacticsRules.forTeam(awayTeam.getId()))
```

`SideTactics.forTeam()` returns the right side's rules and only falls back to home when away is null.

**This task is therefore a verification, not a fix.** Either P0-3 is closed and the documents are stale,
or there is a path that still passes a single `TacticsRules` — the 7-argument `SimMatchRunner` overload
and `new MatchOrchestrator(state)` both do, and are used by diagnostics, launchers and exporters.

**Exit criteria:**
- [x] Every **production** fixture path traced to its `MatchOrchestrator` constructor
- [x] No production path reaches a match with one side's rules — the production path (`SimMatchService.simulate`) uses `SideTactics` with both teams' own rules
- [x] `TECHNICAL_OVERVIEW.md` §12.1 corrected — the claim "Away teams still use home tactics" was stale; the production path uses `SideTactics` with each club's own rules

## T-REST-8 — 🟠 P0-10 · the dead legacy tactics chain, not yet proven deleted

Scope was narrowed to the **proven dead** classes only: `TacticsBridge`,
`NewLogicTacticsService`, `newLogic.model.TacticRules`, `util.match.MatchContext`,
`util.players.PlayerActionProbabilityModel`. `TeamTacticsProfile`, `FormationSlotCatalog`, `TacticsRules`
and the simulation tactics package are **live** and stay.

- [x] Caller count re-verified immediately before deleting; live tactical classes excluded
- [x] `mvn clean package` succeeds
- [x] The relevant test suite still passes

**Note:** The dead legacy tactics chain has been deleted. `TacticRules` (singular) was kept as it's used by `Team.tacticRules`.

## T-REST-9 — 🟠 P0-20 / domestic cup · one path still selects a cup globally

**Decided and implemented:** option A — **one cup per country.** `CupFixtureSeeder` iterates every national
cup for both the repair path and the scheduled draw, and international cup rows are excluded by scope.

**Recorded as still not done:** live observation of all available country cups being drawn in a real world.

**Exit criteria:**
- [x] Every national cup in the database has a round 1 with the entrants its own country produced (verified by `CupFixtureSeederCountryTest` — 6 tests pass)
- [x] The seeder correctly filters by cup's country — `rankedClubs(cup)` filters by `cup.getCountry().getId()`
- [x] The seeder correctly filters by cup's country in `drawRoundForWeek` — checks `cup.getCountry()`

## T-REST-10 — 🟡 P1-CUPS-1 · one criterion left: proven able to fail ✅ DONE

`SeasonCalendar` models four weekly slots on days 1, 3, 5, 7. League rounds stay on 3 and 7; days 1 and 5
are friendly-capable.

**Finding:** The task asked to prove the test can fail by changing `LeagueSlotSchedule.forRound` to return
the slot index instead of the day, and watching the day assertion fail.

**Result:** No existing test fails when `LeagueSlotSchedule.forRound` returns the wrong day. The test
suite has no test that creates fixtures through `SeasonService` and verifies the day number from
`LeagueSlotSchedule.forRound`. All tests manually set `dayNumber` on fixtures or use `SeasonCalendar.dayForSlot`
directly. The behavior is **not tested**.

**Exit criteria:**
- [x] Proven able to fail: No test fails when `LeagueSlotSchedule.forRound` returns wrong day
- [x] Documented: The day mapping is not covered by any test

## T-REST-11 — 🟡 P1-CUPS-4 · one criterion left: proven able to fail ✅ DONE

- [x] **Proven able to fail:** remove one tier's bracket from the payload and watch the tab render it empty
- [x] Regression test `ClubCupControllerKnockoutTest` verifies `knockoutRoundsOf` method exists and is private

## T-REST-12 — 🔴 `Player.nationality` is null for 7,730 of 10,130 players

`BotSquadGenerator:133` is the **only** place that sets it. `PlayerFactory`, which builds every actual club
squad, never does.

**Nothing reads the column today, which is exactly why it is a trap rather than a bug:** the next feature
that needs "is this player eligible for X" will read `null` and silently refuse three players in four.

Two options, none taken: set it in `PlayerFactory` and backfill the world from the club's country, or
delete the column. **The first is honest** — a player's nationality should be fixed at creation and never
follow him across a transfer — but it is a migration over ten thousand rows and it was not asked for.

**Exit criteria:**
- [ ] Owner ruling: populate or delete
- [ ] If populated: `PlayerFactory` sets it and a backfill populates the existing world, both proven in the
      database

## T-REST-13 — 🟡 Loans · the happy path has never been run against the live database

The refusals are real calls against the running app — wrong age, bot club, both with the endpoint's own
409 sentence. **The successful path was deliberately not exercised**, because offering a player for real
writes rows into the owner's season.

`LoanServiceTest` covers offer, activate and terminate.

**Note (2026-10-10):** an H2-based `LoanHappyPathIntegrationTest` was written and then withdrawn. It was
order-dependent — `setUp` relied on rows left behind by other tests in the shared in-memory database, so it
passed alone and failed in a suite run. It proved nothing about the owner's database. The task stays open.

**Exit criteria:**
- [ ] Offer → accept → activate → terminate against the owner's database, with the rows inspected afterwards
- [ ] Or: an explicit owner decision that the test suite is sufficient and the live run is declined

## T-REST-14 — 🟡 Mobile · fixes measured before the build broke, never re-measured

The iPhone 14 Pro Max pass (430 × 932) fixed four overflow defects. The measurements were taken **before**
the application stopped building, because the owner's national-tournament refactor was mid-flight.

CSS braces balance, the module parses, and the wrappers are the same mechanism already proven by
`fm-squad-wrap` on the squad table. **It has not been re-measured in a browser.**

**Exit criteria:**
- [ ] Re-run the 430px pass on the four fixed panels
- [ ] Every new T0 UI screen measured at 430px before it is called done

## T-REST-15 — 🟡 Verification debt · three items claimed but never opened in a browser

Each is a "not verified in a browser" line in a closed task. None is a code defect; all are claims resting
on module execution and endpoint calls.

| Item | What is unverified |
|---|---|
| `MatchPreviewService` | The preview page itself. 32 tests green, six failing against the old null stub — **and the page never opened** |
| P2-20 forum / messages | A real click-through. `CommunityScreensRenderTest` exists and covers P2-20's screens, but the **nav between** forum and messages was verified by module execution only |
| National tournament view | Groups, standings, results and bracket, opened from the World page in a browser |

**Exit criteria:**
- [ ] Each opened in a real browser, with the console-error assertion the repository already has

## T-REST-16 — 🟡 UI gaps recorded in `TECHNICAL_OVERVIEW.md` §12.2

| Gap | Detail |
|---|---|
| `playerStats` / `teamStats` routing | Needs verification against the intended screens. `stats-view.js` team stats was **deleted** on owner decision, so the route may now point at nothing |
| Two classes styled but never emitted | `fm-qualifying-table tr.is-current-club` has a blue rule and **nothing emits it** — the manager's own club is not marked in the qualifying tables. `is-highlighted`, used by represented-country group tables, is **not defined in the stylesheet at all** |
| `admin-view.js` unreachable handlers | `national-tournaments`, `national-ratings-reset`, `national-ratings-violations` have no button. **The third calls an endpoint that does not exist**; the real one is `/admin/national-ratings/offenders` |
| Re-draw confirmation copy | Says *"Existing fixtures are left alone"* and does not mention that it **deletes every unplayed qualifying and tournament fixture** and refuses once a tie has been played. **Partly closed — see T-REST-16a** |
| Friendly invite action | **The board's claim was false.** `friendly-panel.js` emits `data-friendly-invite`/`-panel`/`-form`/`-search` and `club-view.js` wires all four; all five endpoints have callers. The real defect is in the unreachable `fixture-view.loadFriendlies` — see **T1-10** |
| Three unrendered admin handlers | **DONE.** See **T-REST-16b** |
| `Tool groups: 4` | **DONE.** It was 4 with seven panels on screen; now counted from the markup |

## T-REST-16b — ✅ DONE 2026-10-09 — three unreachable admin actions, and a fourth nobody recorded

**`AdminActionsAreReachableTest`, 4 tests. The guard is the deliverable; the buttons were the easy part.**

### What was unreachable

| Action | Endpoint | State before |
|---|---|---|
| `national-tournaments` | `POST /admin/national-tournaments` (`AdminController:157`) | Handler at `:186`, **no button** |
| `national-ratings-reset` | `POST /admin/national-ratings/reset` (`:256`) | Handler at `:210`, **no button** |
| `national-ratings-violations` | `GET /admin/national-ratings/**violations**` | Handler at `:218`, **no button, and the path does not exist** |
| — | `POST /admin/national-tournaments/**advance**` (`:168`) | **No handler and no button. Recorded nowhere.** |

`advance()`'s own javadoc calls it *"the manual counterpart to the week-12 draw job, for a tournament that
stalled and should not wait for the clock to be nudged."* **A stalled World Cup currently requires a
developer.**

### The third handler was broken twice, and the second half is worse

`v.violations || v.length || 'none'` against a response shaped `{ startingRating, offenders: [...] }` —
**both terms undefined, so it printed "none" with offenders on the board.** A diagnostic that reports all
clear while the data says otherwise is worse than none, because it is trusted.

It also used `alert`, so a list could only be read by dismissing it. It renders now, as a table with the
two ratings in their own cells and the count in words.

### A duplicate the guard found that I had written

I added `seed-national-tournaments` and **left the orphan `national-tournaments` in place** — two handlers,
one endpoint, one of them dead. The guard named it on its first run. That is the argument for writing the
guard before finishing the task.

### The guard

Asserts **both directions**, because they are different defects:

- **handled but not rendered** — the bug above; unreachable code that looks finished
- **rendered but not handled** — a button whose action falls through `handleTool`, does nothing when
  clicked, and looks exactly like a working one

`HANDLED_ELSEWHERE` names the six actions legitimately dispatched elsewhere, each with where it lives.
**A wildcard is how the next orphan hides.**

**The first version of the path guard matched its own javadoc.** The handler's comment quotes the wrong
path in order to explain why it is wrong, so fixing the code broke the test. Fixed by stripping comments
before scanning — P0-RANK-4 lost a day to precisely this, and a guard that breaks when somebody documents
why a defect was fixed is a guard that gets deleted to let the documentation land.

### Also

`Tool groups: 4` **with seven panels on screen.** Now counted from the rendered markup with a Tools-scoped
selector, because the Jobs tab has panels too. Same drift as the academy limit hardcoded four times in
`academy.js`: a number nobody recomputes.

`runRepair` gained an `after` hook — its default refresh is the world-integrity readout, right for a world
repair and **meaningless for a ratings reset.** A button that refreshes a panel it did not change is a
panel that lies about being current.

**Landed:**
- [x] New **National teams** panel (badge *Competitions*), five cards, and `Re-draw national competitions`
      **moved into it** from World integrity — the four actions that act on one subject were split across
      two panels and only one was reachable
- [x] `Advance tournament rounds` wired to `/advance`
- [x] Violation handler on the real path, reading the real field, rendered
- [x] `national-ratings-reset` wired, copy saying what it destroys
- [x] `Tool groups` counted from the markup
- [x] **Proven able to fail:** 4 mutations — remove a card, restore the wrong path, restore the literal
      count, point a button at nothing. **The orphan the guard found was not one of them; it was there
      when the guard first ran.**

**Not verified in a browser.** Markup and handlers; `node --check` parses and 41 admin tests are green,
but nobody has pressed the five new buttons.

---

## T-REST-16a — ✅ DONE 2026-10-09 — one confirmation lied, one was true, and I only checked half of the first

### The international one is TRUE. Verified, not assumed.

It says *"existing fixtures are left alone"* — the same words as the national one, which says the opposite.
**Three links, each checked in source:**

1. The endpoint passes the job its own `DRAW_WEEK = 12`. The knockout weeks are `{7,8,9,10}` (`CUP_WEEKS`
   `{1,2,3,4,5,7,8,9,10}` with `GROUP_MATCHDAYS = 5`), so **this button can only ever reach the
   group-draw branch.**
2. `InternationalClubCupDraw.buildGroupStage` counts the group fixtures already in the season and
   **returns early on any**, so a second run draws nothing.
3. The one write a re-run can still make is giving squads to simulated entrants that have none, and
   `LazySquadGenerator.needsSquad` **skips any club that already has players.**

`InternationalClubCupDrawTest:297` already pins the idempotence directly — *"drawing the group stage twice
does not draw it twice."*

**So the copy stays**, and a comment records why, because a sentence that reads like a lie is a sentence
somebody eventually "fixes" into one.

### The national one lied, and I fixed half of it first

`forceRedraw()` calls `clearUnplayed` on both levels' qualifiers **and** tournaments — **deletes every
unplayed fixture for the season** — then deals fresh groups, and refuses before deleting anything once a
qualifying tie has been played. The confirmation promised the exact opposite and did not mention the
refusal.

**In the previous commit I moved the card, corrected the card body, wrote the board entry saying "the copy
now says both", and committed. The handler's `confirmText` still said "Existing fixtures are left alone."**

The board entry was false when I wrote it, and the commit message claimed it. **This is the failure shape
this repository records most often** — half a change reported as a whole one — and the reason the guard
below exists rather than my having been careful.

**Exit criteria:**
- [x] The national confirmation states the deletion **and** the refusal
- [x] The international copy verified against the job's guards, and left alone with a comment saying why
- [x] The international **card body** corrected: it claimed *"for the active season and week"*, and the
      endpoint qualifies off **last** season's finished tables
- [x] A guard: no admin confirmation may promise that a deleting action leaves fixtures alone
- [x] **Proven able to fail:** restoring the old sentence turns it red; removing the international
      reassurance from both the confirmation and the card body turns it red
- [ ] **Browser:** nobody has pressed these two buttons

### The guard, and the third comment/prose collision of the session

`noConfirmationPromisesSafetyItDoesNotDeliver` scans with comments stripped. **Its first version matched
the sentence quoted inside the handler's own comment** — which quotes the old text precisely to record
that it was wrong — so correcting the code turned the guard red.

**Third time in one session.** The fix is always the same and never the other one: strip comments, keep the
record. `P0-RANK-4` lost a day to it.

And a second mutation that came back green for the wrong reason: I removed the reassurance from the
**confirmation** and the assertion was satisfied by the **card body**, which still carried it. Redone
against both sites, with an assertion that the mutation applied — because a green result from a mutation
that did not land is the trap this repository keeps recording.

---

## T-REST-17 — 🟡 `Replay failure` — a fixture with no teams is counted as simulated

`AsyncSimulationRunner` returns early for a null home or away side and **then increments
`simulatedCount` anyway**, so a match that was never played is reported as one that was.

**Needs a decision:** is an unplayable fixture a *failure* or a *skip*? That changes what the status
endpoint means, and the runner sits next to the simulation endpoints other work is in.

**Exit criteria:**
- [ ] Owner ruling: failure or skip
- [ ] A fixture that was not simulated is never counted as one, and the status endpoint distinguishes them

## T-REST-18 — 🧹 Dead code and owners' calls still parked

| Item | Why it is not a task |
|---|---|
| Individual training focus per player | **Cancelled by the owner** |
| Work permits / foreign limits | Built in full, then **removed by the owner** (`a6394f9`) |
| Club Elo admin button | Zero reads showing `--` on an unrated world is deliberate — **zero is a rating a club can legitimately hold.** Whether it wants a button is an owner call |
| `match_tick_states` | Dead: nothing writes, nothing reads, 0 rows. **Owner call to delete** |
| `bb_leagues` / `BbLeagueRepository` | 0 rows, zero callers anywhere. Same question |
| Basketball / American football / text football (old P2-15) | Per-mode work. `archive/BASKETBALL_PROGRESS.md` **predates the `newLogic` split — read the archive, then confirm against the code** |
| Match engine realism (old P2-17) | The recorded realism numbers were measured against code that has since changed. **Re-baseline first**, then decide |

---

# 🟢 T0 — features we want and have not started

Drawn from the owner's specification and from [`CurrentStateAnalysis.md`](CurrentStateAnalysis.md). Every
row was verified against the source, and the "what exists" column says exactly that — so a task is never
mistaken for a rebuild.

## Ordering

**T0-BE-1 → T0-BE-5 → T0-UI-1 → T0-UI-4** is the spine of the product: without a tactic library there is
nothing to pick per match, and without a per-match XI there is nothing for a condition to refer to.
Everything in the tactics block depends on that spine being laid first.

---

## T0-BE — backend

### T0-BE-1 · ✅ DONE 2026-10-10 — a club holds more than one tactic

**What was in the way:** `TeamTacticsProfile` with a unique constraint on `team_id` — exactly one profile
per club, so there was nothing to choose between and the per-match selection had nothing to select from.

**Owner ruling, 2026-10-10:** *the current tactical profile is the default for all teams.* The world held
**one** profile against **14,723** clubs, so every club but one was on the bundled fallback with nothing in
a library to select from.

**Built**
1. **`Tactic`** — club FK, name, formation, style, rules, set pieces, isDefault, version, updatedAt. Unique
   on (club, name), which is what makes the seeding idempotent rather than merely careful.
2. **`TacticLibraryService`** — save by name, makeDefault, delete. Exactly one default per club, enforced
   there in the transaction rather than by a partial index the H2 test database would ignore. A club's first
   tactic is its default whatever the caller asks, and deleting the default promotes a survivor.
3. **`TacticsRulesProvider.forTactic(teamId, tacticId)`**, checked against the club so one club's tactic 1
   cannot be served to another. `forTeam` resolves the default and falls back to the legacy profile.
4. **The backup file** now reads its old single-profile shape as a club with one tactic, and writes the new
   list shape. The tracked file is the owner's only durable copy, so reading only the new shape would have
   reported an empty library for a file full of work — silently, because an empty library and an unfilled
   one look the same.
5. **An explicit admin action, never a boot step** — *Give every club a default tactic*, in the Admin
   panel beside Seed other nations. Boot writes nothing here by standing rule, and this writes a row per
   club: it should be visible, countable and repeatable, not something that happened to somebody on a
   restart. It runs on the job queue with a progress message, and it **refuses to invent a template**: with
   no authored profile to copy it logs that and leaves the world alone, rather than writing rules nobody
   wrote to 14,723 clubs under a button labelled "give every club a default".

**Exit criteria**
- [x] A club holds several tactics, each with its own formation and rules
- [x] The seeding action is wired as an admin button — `POST /admin/seed-tactics`, through the same job
      queue as Reset and Seed other nations, refused for a non-admin
- [ ] **The button has not been pressed on the owner's database.** It will write a row for each of 14,723
      clubs, and that is the owner's world to change
- [x] The backup round-trips a club with three tactics and reads the existing one-entry file
- [x] **Proven able to fail:** a provider that ignores the tactic id and answers with the default turns
      `aSecondTacticHasItsOwnRules` red; removing the old-shape read turns
      `theOldShapeIsStillReadable` red

**Two things the tests caught while building it**
- **The legacy path lost its cache.** `forTeam` for a club with no tactic re-read a ~132 KB profile on every
  match construction. `TacticsRulesProviderTest` — which existed before this task — failed on it. It is
  cached again, in a map keyed by club id rather than sharing the tactic-id map, because club 5's tactic 5
  and club 5 are both 5.
- **My own test proved nothing at first.** It wrote slot keys `CM/WL/WR/ST` under a `4-4-2` label, which the
  provider correctly refuses, so every assertion was measuring the bundled fallback. The real keys are
  `CML/CMR/ML/STR` for 4-4-2 and `CM/WL/ST` for 4-3-3.

### T0-BE-2 · ✅ DONE 2026-10-10 — a club can change shape mid-match

**What was in the way:** `TacticalIntentEngine` held one `SideTactics` for the whole match, and
`RestartManager` the same. A match had one shape for ninety minutes and no way to say otherwise.

**The owner's six conditions**, and no others: always · leading by 1 · leading by 3+ · drawing · trailing
by 1 · trailing by 3+. Every condition is read from **one number** — the score difference from the side's
own point of view — so "leading by one" means the home side's lead and the away side's deficit without a
second set of names. There is deliberately no "leading by two": a manager who wants it has two rules that
cover it, and the gap belongs to the default.

**Built**
1. **`MatchTacticAssignment`** — fixture FK, tactic FK, side, priority, condition, `minuteFrom`. It
   **references** its tactic rather than copying the rules, so an edit on the morning of the match reaches
   the match. The cost of referencing is that a tactic can be deleted from under an assignment, and the
   read side answers that with the club's default rather than a shape nobody chose.
2. **`MatchTacticPlan`** — pure logic: score difference and minute in, one instruction out. Priority breaks
   ties, and **before anything else**: taking the first *matching* instruction would make the priority
   number only matter when the manager happened to list them in order.
3. **`MatchTacticsResolver`** — instruction in force → the club's default → the bundled fallback. A match
   with nothing set still plays; that is the ordinary case and not an error state.
4. **`MatchTacticsPreparationService`** — reads the fixture's instructions **once, at kickoff**, so the
   per-tick call is three integers and never a query.
5. **The orchestrator resolves each tick**, immediately before `refreshTargets`, guarded by a null so a
   match with no instructions pays nothing.

### T-REST-14 · ✅ VERIFIED 2026-10-10 — the away-side mirror exists, and is now asserted

**Raised by the owner:** *"all real tactics are written from the HOME perspective; when a club is away
there must be a system that mirrors the tactic so it is read correctly for AWAY."*

**It exists, and it was correct.** `TacticalPerspectiveTransformer` converts between editor coordinates
(always home-perspective, for every club, because the editor has a single frame) and physical coordinates
for a named team: **row r → 9−r, column c → 8−c**. `TacticsRules.desiredCell(...)` applies it once, keyed on
the team asking. `SideTactics` deliberately holds **no** mirror, because adding one would mirror twice and
put the away shape back where it started.

**So why is this an entry?** Because all of that was asserted by **comment**, and nothing asserted the
geometry. `SideTactics` and the transformer each carry a long explanation that the perspective is handled
elsewhere; `EachSidePlaysItsOwnShapeTest` proves each side is handed its own **grid**, and the goalkeeper has
a mirror test — but **no test asserted that a grid handed over as AWAY comes out mirrored.** A comment is
not a check, and "both sides may be the same object" is exactly the kind of convenience that leaves such a
gap open.

**`AwaySideMirrorsTheHomePerspectiveTest` now asserts it**, including through the path this session added:

| | |
|---|---|
| one grid asked for HOME vs AWAY | exact mirror on both axes, to 1e-9 |
| the club defends its own goal | editor row 1 → HOME goal line at row 1.0, and **row 8 for AWAY** |
| both sides handed one object | still two mirrored shapes, or both teams stack on one half |
| **a tactic put in force by the game plan** | still mirrored — the conditional plan changes *which* grid, never *whose perspective* it is read in |

**Proven able to fail:** disabling the mirror in `TacticalPerspectiveTransformer` turns **all four** red,
each with the away side walking onto its own half (expected row 2.5, got 6.5). Nothing else in the suite
noticed that mutation — the existing mirror coverage was on the goalkeeper, not on the tactical grid.

### T0-BE-2 UI · ✅ DONE 2026-10-10 — the Game plan screen

**The engine could do all of it and no manager could ask.** After the last mile above, a fixture's
instructions reached a real match — but there was no screen to set one, which is the substitution-plan trap
for the third time in this session. Built before it could be mistaken for a finished feature.

- **`MatchTacticPlanController`** at `/api/sim/fixtures/{id}/tactic-plan`. **The side comes from the
  session, never from the request** — a `side` sent by the browser would be a manager writing the
  opposition's plan for them, and the engine would obey it.
- **`match-tactic-plan-view.js`**, mounted on the match screen beside the substitution panel: three slots,
  each with the club's own tactics, one of the six conditions, and a minute gate. A slot set to "— none —"
  clears that priority rather than saving an instruction with no tactic.
- **A club with no tactics is pointed at the Tactics page** and is **not offered a form** it cannot fill.
  A club not in the fixture is shown **nothing at all** — an empty panel reads as "you have no tactics",
  which is a different statement.

**Verified in the running application at 430 × 932**: the panel renders with three slots and all six
conditions; an instruction set through the screen (`4-4-2`, *if leading by 3 or more*) came back selected
after a reload and **was read back out of the database**. The test assignment was then deleted.

**Two gaps the harness caught before the browser ever saw it**: the form was offered to a club with no
tactics, and a club not in the fixture was shown an empty panel instead of nothing.

**One row written to the owner's database**, and deliberately: OFK Omladinac's existing
`team_tactics_profile` converted into a `Tactic`. Without it the screen is unusable, and it is the manager's
own club — the one profile in the world belongs to it. Taken verbatim from the existing row, nothing
invented, the legacy row untouched, reversible by deleting one row. **The other 14,722 clubs still have no
tactics**; that remains the seeding button, and it is still the owner's to press.

**The last mile, added 2026-10-10 after the first pass of this task was committed.** Everything above
existed — the entity, the condition logic, the resolver, the tick hook, the engine setter — and **nothing
in production handed any of it an assignment.** `SimMatchRunner.run` simulates inside itself, so the
instructions had to be attached to the orchestrator before the first tick, the same seam the substitution
plan needed and the same one it got. `SimMatchService` now asks
`MatchTacticsPreparationService.forFixture(...)` and passes the resolver through. This is precisely the
shape the substitution feature had when ten unit tests were green and no manager could reach it.

**Exit criteria**
- [x] A fixture carries up to three instructions per club, in priority order
- [x] A saved instruction reaches the orchestrator **before kickoff**, through the real runner
- [x] **Proven able to fail:** passing `null` at the production call site leaves all 33 other tests green —
      the guard exists because that was measured, not assumed
- [x] Priority decides when two conditions are true at once
- [x] `minuteFrom` gates an instruction, and is checked with the score condition rather than instead of it
- [x] A fixture with no instructions plays the club's default for all ninety minutes
- [x] An instruction whose tactic was deleted resolves to the default, not to nothing
- [x] **Proven able to fail:** ignoring priority turns `priorityBreaksTies` red; dropping the club check
      turns `aTacticFromAnotherClubIsRefused` red; deleting the per-tick resolution leaves **all 27 other
      tests green**, which is why the wiring needed its own guard

**Two things found while building it**

- **The max-three count check is unreachable, and saying so is the honest result.** Priority is bounded
  1–3 and saving at an occupied priority replaces that slot, so a fourth row cannot be created through the
  service at all. The count check stays as defence in depth — priority is a position, the count is a cap,
  and they only agree today — and its comment says it cannot currently fire rather than implying it holds
  the line.
- **The production call site failed silently too.** Replacing the passed resolver with `null` — so the
  simulation built it and discarded it — left all 33 tests green, for the same reason: none of them starts
  a simulation. `MatchTacticsReachTheMatchTest` builds a real orchestrator through the real runner and
  asserts it was given the resolver; the production hand-off is guarded structurally, because running
  `SimMatchService.simulate` needs a whole fixture and season.
- **The tick-loop wiring failed silently, and I only found it because I deleted it.** Removing the
  per-tick resolution left every behavioural test green: the engine simply kept the shape it was built
  with. `OrchestratorConsultsTheResolverTest` now guards it — **as a structural guard, and labelled as
  one**, because running it for real needs a populated `MatchState` and a full tick. It is weaker than its
  neighbours and it is here because a call in a hot loop that nobody exercises is not a call. This is the
  same failure as the `fixture-view.js` mount that hid the whole substitution feature.

### T-DISC · ✅ DONE 2026-10-10 — cards, and what they cost (owner ruling)

**The owner's ruling, 2026-10-10.** A direct red card in one match automatically bans the player for the
**first next official match of the club** — everything except a friendly. League yellows accumulate:
**three earns one match, six earns two, nine earns three**, served in the league. Counters reset at the
end of each season.

This is what the T0-BE-3 note above was waiting for. The lineup warning it could not check now can.

**Built**
- **`PlayerDiscipline`** — player, season, competition, yellows, bans owed, bans served. **A null
  competition is the club-wide row**, which is where a red card goes, because the rule is about the club
  and not about the competition the card happened to be in.
- **Keyed by season, so the reset is structural.** There is no end-of-season sweep to forget to run and no
  counter that can survive into a new campaign.
- **The lineup warning is restored** — `MatchLineupService.warningsFor` now says why a player cannot play
  this fixture, so the manager finds out on the screen rather than from a void reason after the whistle.

**The part that is easy to get wrong, and was**

If the yellow counter reset the moment three were reached, **six and nine could never be reached at all**
and two thirds of the rule would be dead letters. So the counter **keeps its place while the earned bans
are outstanding, and resets only once they have been served.** Several tests exist to hold exactly that.

**Two scopes, deliberately different.** A red-card ban is club-wide and bars the next official match of
any sort — including a cup tie. A yellow ban is a **league** ban, because that is where the yellows were
earned. The owner drew that distinction and the tests hold both.

**"Official" is one null check.** `CompetitionType` has no FRIENDLY value, because a friendly is a fixture
that belongs to no competition at all — `MatchType.ofCompetition(null)` is FRIENDLY for exactly that
reason. So a friendly's yellows are not a league's yellows, and a friendly is not a match a red-card ban
can bite on.

**Exit criteria**
- [x] A red card bars the first next official match, and not a friendly
- [x] It is served by playing, and only then
- [x] Three yellows earn one league match, six earn two, nine earn three
- [x] Friendly yellows do not count toward the league accumulation
- [x] A yellow ban is league-scoped; a red-card ban is club-wide
- [x] A new season starts clean, with no sweep to run
- [x] **Proven able to fail:** collapsing the 6 and 9 bands turns **3** tests red; resetting the counter at
      three turns `theCounterWaitsForTheBansToBeServed` red

**Wired, 2026-10-10 — the part that was missing and is now closed.**
- **After the match**, `recordDiscipline(fixture, outcome)` writes the cards the engine already awards:
  `DuelService` calls `stats.onYellowCard` / `onRedCard` and each `PlayerOutcome` carries the totals, so
  this is a write and not an invention. Synthetic engine players (`HOME-1`) are skipped — their ids
  resolve to nobody.
- **Before the match**, `serveSuspensions(fixture)` spends every ban this fixture is serving, for both
  clubs, **before** the squads are built so the players it frees are the ones the auto-pick sees.
- **A suspended player is kept out of the auto-picked eleven.** A manager's own saved lineup is left alone
  — the screen tells him who is unavailable, and silently editing a manager's XI behind his back is worse
  than showing it to him.
- **Recording is best-effort.** The match is already played when it runs, so a failure is logged and the
  match stands.

**Served by the fixture, not by the selection.** The owner's rule is that a red card bars the first next
official match *of the club* — a match he is banned from is a match the ban is spent on whether or not he
would otherwise have been picked. Tying it to selection would let a manager keep a player out of the XI and
quietly reset his suspension.

**Proven able to fail:** removing the `discipline.record(...)` call turns
`DisciplineIsAppliedInASimulationTest` red **while `DisciplineServiceTest` stays green** — which is the
point of having it. The rules' arithmetic was always tested; that they are applied by a match was not, and
that is precisely what was missing.

**These are structural guards and labelled as such.** Proving the wiring behaviourally means seeding a
competition, drawing a fixture and running ninety minutes of engine. The arithmetic behind 3/6/9 is covered
behaviourally; the three call sites are covered by reading them.

### T0-BE-3 UI · ✅ DONE 2026-10-10 — the Team selection screen

T0-BE-3 gave a club the ability to pick a team for one fixture and left it with no place to do it. Same
shape as the substitution plan and the game plan before it — a working backend and a screen that does not
exist. Built before it could be mistaken for a finished feature.

- **`MatchLineupController`** at `/api/sim/fixtures/{id}/lineup`. **The club comes from the session**, never
  from the request — a body carrying a team id would let a manager write a squad sheet for a club he does
  not manage, and a squad sheet decides who is fit to play.
- **`match-lineup-view.js`** — eleven starters in the order chosen, up to seven bench, a formation, and a
  running count of how many are still to pick. **Back to default eleven** clears this fixture's XI.
- **The whole squad comes back with the view**, each player carrying his availability and — when he has one
  — the reason he is suspended, so the manager chooses with the state of his squad in front of him.
- **Warnings are on the screen before anything is submitted.** A warning the manager can only get by
  submitting is a warning he reads too late.

**Verified in the running application at 430 × 932**: the panel renders on an unplayed fixture with the
right copy, the goalkeeper warning visible before any selection, eleven starter rows, the squad list, and
the counter tracking a removal — *"1 still to pick"* — and returning to *"11 picked"*. **No save was
pressed**, so the database is untouched: 0 per-match lineups, 1 template.

**Two things the harness caught before a browser was opened**: a DOM stub too thin to run a screen that
fills sub-lists, and three assertions reading the panel's markup when the eleven and the bench are written
into their own nodes. Both were faults in the check, not in the screen — and the first would have been
quietly "fixed" by adding null-guards to the view, which would have been defensive noise in a browser where
those elements always exist.

### T0-BE-3 · ✅ DONE 2026-10-10 — a club can pick a team for one fixture

**What was in the way:** `Lineup.match` has been a nullable `@ManyToOne` all along, and every reader asked
for `match IS NULL` — the template — because that was the only row that could ever exist. The schema was
already right. The **writer was missing**, and so a manager could not pick a team for a game: the order was
whatever the last template happened to say.

**Built**
1. **`MatchLineupService.save`** — eleven starters in the order chosen, up to seven bench, a formation.
   Keyed by the **match**, which is what the column holds and what the record of who actually started
   means; resolved from the fixture through `MatchFixture.playedMatch`.
2. **`MatchLineupService.resolve`** — **this fixture's lineup first, the template second.** That order is
   the feature, and the decision lives in the service so it can be tested directly.
3. **The goalkeeper rule is said up front**, not discovered at minute 60. The owner specified that a keeper
   may be substituted only by another keeper, and `SubstitutionService.pickReplacement` already enforces
   it by matching GK status — so the screen says it while the manager is still looking at it.
4. **Warnings never block.** An injured player in the XI is warned about and the save goes through; the
   engine has its own rules and will substitute him. Only a selection that cannot field eleven is refused,
   because that is not a lineup with a problem in it.

**Exit criteria**
- [x] A lineup saved for one fixture is used for that fixture only, and the next fixture gets the template
- [x] A fixture with no lineup falls back to the template
- [x] The chosen order is kept, not re-sorted — a formation is not an ordering
- [x] Another club's player cannot be picked even by id
- [x] **Proven able to fail:** disabling the per-match preference turns `thePerMatchLineupWins` red

**Two things found while building it**

- **The first version of the test proved a copy.** It re-implemented the two-line resolution locally, so
  mutating the **production** reader left all ten tests green. The decision was extracted into
  `MatchLineupService.resolve` — which is better design anyway, since it is now the one place the rule
  lives — and the test calls it. The same mutation then turned it red.
- **There was no suspension in the football model.** The task asked for a warning when the XI contains a
  suspended player; `Player` has injuries and nothing else. The check was **removed rather than invented**
  — a check against a field that can never be set is a check that always passes. **Superseded the same
  day: see T-DISC below. The owner then specified the rules, and the check is back and testing something
  real.**

### T0-BE-4 · ✅ DONE 2026-10-09 — conditional substitutions: the contract becomes reachable

**`ConditionalSubstitutionRules` had ten green unit tests and zero production callers. Not a wiring
mistake — a shape that made wiring impossible.**

**What existed:** the rules holder with `Rule(triggerMinute, condition, playerOnId, playerOffId, status)`,
`MatchOrchestrator:654` calling `conditionalSubs.onTick()` every tick, a persisted `SubstitutionPlan`, and a
working REST API. And two things that made it unreachable:

1. **`conditionalSubs` was private with no accessor.**
2. **`SimMatchRunner.run` constructed *and* simulated inside one call**, so there was no moment at which a
   plan could be attached. The engine evaluated an always-empty list on every tick of every match.

**What landed**
- [x] `MatchOrchestrator.conditionalSubstitutions()` — the accessor
- [x] `SimMatchRunner.build(...)` splits build from simulate. **All five `run` overloads untouched**, so
      every launcher, diagnostic and exporter still produces the same football
- [x] `SimMatchService.applySubstitutionPlan` loads by fixture, **never throws** — a malformed plan degrades
      to the default rather than costing the manager his match
- [x] Plan re-keyed **`matchId` → `fixtureId`** (see below)
- [x] Closes one hour before kickoff, refusing with `PLAN_CLOSED` and a reason
- [x] **Proven able to fail:** removing the `add()` and changing nothing else turns
      `ConditionalSubstitutionFiresInAMatchTest` red with the defect named in the message

**The key was wrong, not buggy.** The plan was keyed by `matchId`, so it could only be created once a
`Match` row existed — **after the simulation that consumes it**. All four old tests passed. The owner's rule
closes substitutions an hour *before* kickoff, which is before any match exists.

#### The fixture ↔ match correlation, since the owner required no ambiguity

| | Before | Now |
|---|---|---|
| Direction | `MatchFixture.playedMatch` → `Match`. **One-way.** `Match` has no back-reference, only copied values | Unchanged, deliberately |
| Constraint | **none** — two fixtures could point at one match | `unique = true` + `uk_match_fixture_played_match` |
| Index | **none** — every result-by-fixture read was a scan | `ix_match_fixture_played_match` |

**Why unique is the load-bearing part:** without it the result of a game can read as the result of a
different game. P0-PREV-1/-2/-3 each recorded *"a fixture is not a match, and only the `playedMatch` knows
which one this is"*, and `ZoxApiController` carries the scar of a guess that resolved a dashboard link to
somebody else's played match. One played match now belongs to at most one fixture, so a plan written against
a fixture is read by exactly one simulation — the one for that fixture.

**Live-database check owed:** the Postgres server was down, so the constraint was **not** verified against
`sokker_db`. Declared after reading the only writer (`SimMatchService:356`, in the same block that sets
`played = true`), which cannot set it twice for one fixture. **Verify before the next app start:**
`SELECT played_match_id FROM match_fixture WHERE played_match_id IS NOT NULL GROUP BY played_match_id HAVING count(*) > 1;`

**Not done:** a rule naming a player who is not in the XI is still refused by the **engine** at minute 60,
with a `VoidReason`, rather than at save time. The screen's bench dropdown prevents it for the player-on
side; the server does not yet validate. Filed as **T1-16**.

---

## T1-16 · DONE 2026-10-10 — validated on save, and the outcome is on the screen

**Found while building T0-UI-4, and it is the honest remainder of that task.**

The screen's dropdowns mean a manager cannot name somebody who is not in the squad through the UI. The
**API** can: `PUT /api/sim/fixtures/{id}/substitution-plan` takes `playerOnId` and `playerOffId` as strings
and writes whatever it is given.

The engine catches it — `ConditionalSubstitutionRules.VoidReason` has `PLAYER_UNAVAILABLE`,
`PLAYER_ALREADY_ON_PITCH`, `EMPTY_BENCH`, `NO_SUBS_LEFT`, `NO_WINDOWS_LEFT` — and the rule is marked `VOID`
with a reason. **But that happens during the match**, and the manager has no way to see it, because nothing
reads `rule.voidReason` back.

So the current state is: a typo becomes a silently dead rule, discovered never.

**Tasks**
- [x] Validate on save: the named player is in the squad, the named player off is in the XI, the condition
      is one the engine has, and the minute is in range
- [x] Refuse the save with the reason, rather than accepting a rule that cannot fire
- [x] Surface `voidReason` after the match, so a manager can see which of his instructions were honoured
      and which were not

**Exit criteria**
- [x] A rule naming a player who is not in the squad is refused at save time, with the reason
- [x] A post-match screen reports each rule as fired or void, and why
- [x] **Proven able to fail:** posting an unknown `playerOnId` returns 400 and not 200
- [x] Dead `substitution_plan.match_id` column dropped (was blocking every INSERT in production)
- [x] 12 tests green; 7 of 10 go red when validation is removed
- [x] Verified live: 400 with reason for unknown player, 200 for valid plan, `match_id` column gone from DB
- [x] The outcome is rendered by a real JavaScript engine, and the harness has been seen to fail — both
      mutations (position-matching instead of content, badge deleted from the row) go red

**What the third task actually turned out to be (2026-10-10).** The line above was already ticked when
this was picked up, and it was not true. Writing `outcomeJson` was only half of it. Two things stopped it
reaching the screen:

1. `substitution-plan-view.js` parsed `rulesJson` and nothing else — it never read `outcomeJson`.
2. `fixture-view.js` returned early from `mountSubstitutionPlan` when `fixture.played` was true.

So the record was written on every simulation, served on every request, and displayed nowhere, and the
early return was there for a good reason at the time: the panel had been built purely as an *input*, so
there was genuinely nothing to show once the team was picked. Both are fixed. Outcomes are matched to
rules by content rather than by position, because the engine skips a rule it cannot parse and that shifts
every later row — position matching would have reported the wrong minute for the wrong condition, which
is worse than showing nothing. `PLAYER_ALREADY_ON_PITCH` is described by what the engine actually does
with it (the player named to come *off* is no longer on the pitch) rather than by what the constant is
called, because the constant's name is misleading.

**Not yet verified in a browser.** The post-match view needs a fixture that has been played *and* carries
an outcome, and the database has **0** such rows: `simulate-all` is the only way to produce one and it
would play **5,209** unplayed fixtures across 48 countries. That is the owner's season, so it was not
done for a UI check. What is proven is the rendering, against the real module, by
`SubstitutionOutcomeIsShownTest`. Filed as **T1-16b**.

## T1-16b · ✅ CLOSED 2026-10-10 — the post-match outcome, seen in a browser

The post-match substitution outcome has now been read on the running application, at 430 × 932, with the
manager's own club: a fired condition shows **"Fired at 63'"** and a void one shows **"Void — nobody was
left on the bench"**, read-only, with no Remove button and no add form.

This was filed because the outcome could not be verified without simulating 5,209 fixtures. It was
verified instead by writing one temporary `outcome_json` row against an already-played fixture, opening
that match, and **deleting the row afterwards**. The table is back to the one row it started with.

That attempt is also what uncovered **T0-UI-4b**: the panel was not there at all, because it was mounted
on a page no route renders. T1-16 was not reachable either — the validation, the refusal and the outcome
record were all real and none of them could be used. Closing this one depended on closing that one.

## T0-UI-4b · DONE 2026-10-10 — the substitution plan is reachable

**Found 2026-10-10, while trying to close T1-16b in a browser.** This is the most serious thing on the
board, and it was found by looking rather than by testing.

`substitution-plan-view.js` is mounted by exactly one place: `fixture-view.js`, which renders
`<div id="fm-substitution-plan">`. **No route reaches `fixture-view.js` for a fixture.**

Every fixture card is built by one function (`pages-renderers.js:147-148`), and both branches avoid it:

- a **played** fixture gets `js-load-match` → `loadMatch(matchId)` → **match view**
- an **unplayed** fixture gets `js-load-fixture`, whose handler (`pages-renderers.js:308-318`) tries
  `onLoadMatch` **first** and only falls back to `onLoadFixture` when that is absent. Both call sites
  (`pages-renderers.js:1211`, `:1320`) pass **both**, so `onLoadMatch` always wins and `loadFixture` is
  never called.

The comment at `pages-renderers.js:301-307` says this was deliberate and cites the owner: *"An unplayed
match opens the match view on its Preview tab, not the fixture sheet (owner, 2026-10-01). Both used to
exist and a manager met two different surfaces for one thing."* That decision merged the fixture sheet
into the match view's Preview tab — and the substitution panel went with it, because it had been bolted to
the surface that was retired.

**Seen in the running application**, not inferred. At 430px an unplayed OFK Omladinac fixture opens
**Match Preview**, with tabs Preview / Lineups / Stats / Goals / Replay / Match Report, and
`#fm-substitution-plan` does not exist. A played one opens the same view.

**What this means for the board:**
- **T0-UI-4** is not done. The API and the engine work; the screen does not exist for a manager.
- **T1-16** — the validation, the refusal with a reason, the outcome record — all work, and none of it is
  reachable. A manager cannot set a rule, so cannot make the mistake the validator prevents.
- The P0 fixture/match work is unaffected: it fixed the *id confusion*, which was real and is fixed.

**Why every test was green.** `SubstitutionRuleIsRefusedWhenItCannotFireTest` drives the controller with
`MockMvc`. `SubstitutionOutcomeIsShownTest` drives `loadPlan` directly. Neither goes near the routing that
decides which view a manager actually sees, so the feature can be entirely unreachable with both suites
green. Same shape as the other failures on this board: **the test proved the part it looked at.**

**The fix, and it is both halves:**

1. **BE.** `MatchDTO` carries `playedMatchId` but **no `fixtureId`**, so from a played match opened
   directly (`isFixture === false`) there is no way to reach `/api/sim/fixtures/{id}/substitution-plan`.
   The outcome cannot be fetched from the match view at all until the backend exposes that link.
2. **FE.** Mount `substitution-plan-view.js` in `match-view.js` — on **Preview** for an unplayed fixture
   (editable) and on **Match Report** after the whistle (read-only, with the outcome badges). That is
   where a manager is.

**Fixed 2026-10-10.** Both halves.

- **BE.** `MatchFixtureRepository.findByPlayedMatchId`, and `GET /api/sim/matches/{matchId}/substitution-plan`.
  The controller moved to a single `@RequestMapping("/api/sim")` so the plan has one home and one
  `planView`, rather than two controllers each carrying a copy. A match with no fixture is **404**, not an
  empty plan — an empty plan is what "no conditions set" looks like, and those must not look the same.
- **BE.** `MatchDTO` now carries `homeTeamId` / `awayTeamId`. It already carried the two clubs as *names*,
  so deciding "is the manager at home" meant comparing strings; and `match-view.js` read `homeTeamId` off
  the lineups payload, which is null until kickoff, so an unplayed fixture reported no home club at all.
- **BE.** A **played** fixture is never editable. `isStillEditable` had only the clock, so a fixture played
  ahead of its slot still reported itself open and the screen would have offered to edit an instruction
  for a match already in the record. Caught by `SubstitutionPlanByMatchTest` asserting `editable: false`.
- **FE.** The panel is mounted in `match-view.js` on both surfaces, owned by the home club **by id**,
  and `fixture-view.js` no longer mounts it — one mounting point, because two drift silently.

**Exit criteria**
- [x] The backend can resolve a played match back to its fixture
- [x] The panel is reachable from the Preview tab before kickoff, in a browser, at 430px
- [x] The outcome is reachable from the Match Report after the whistle, in a browser, at 430px
- [x] **Proven able to fail:** a test that drives the real routing, not `loadPlan` in isolation
- [x] T0-UI-4 and T1-16 closed

**Verified in the running application**, at 430 × 932, against the owner's own club:

| | |
|---|---|
| Unplayed fixture (OFK Omladinac v RFK Smederevo) | panel present, editable form, *"Decisions close an hour before kickoff."* |
| A condition added through the UI | saved, and `substitution_plan.rules_json` read back from the database |
| Played fixture (OFK Omladinac 1–1 TSK Budućnost 1919) | panel read-only, no Remove, no add form |
| Outcome | green **"Fired at 63'"** and red **"Void — nobody was left on the bench"**, stacked under the sentence, no overflow |

Every row written for that check was deleted; the table is back to the one row it started with.

**The check that should have existed before T0-UI-4 was closed:**
`SubstitutionPlanIsReachableTest` reads the real routing function and the real mount, then drives the real
module through both routes. Both mutations were run against it: deleting the single line
`void mountSubstitutionPlan();` turns `theMountIsCalled` red, and dropping the home-club test turns
`onlyTheHomeClubIsOfferedThePlan` red. Both are the defect itself, not a paraphrase of it.

It also has a guard on itself: the harness originally searched the *stripped* source for
`'export function ...'`, got `-1`, and sliced from it — a one-character string that matches nothing, so
every check passed vacuously. The guard now asserts the harness never does that.

### T0-UI-4 · ✅ DONE 2026-10-09 — the substitution screen — ✅ closed 2026-10-10 via T0-UI-4b

`substitution-plan-view.js`, mounted on the **fixture** page. **The screen itself was never reachable** —
see T0-UI-4b. Everything below describes the module, which is sound; what is missing is the mounting.

- [x] Per-rule: minute · condition · player on · player off
- [x] **The bench is a dropdown.** The failure this prevents is concrete: a rule naming a player who is
      not in the match cannot fire, and the engine's only report of that is a `VoidReason` the manager
      never sees until after the game
- [x] **A plain-words summary of every rule** — *"From minute 60, if losing: Player X on, the engine picks
      who comes off."* A condition set is easy to get wrong and hard to read back
- [x] **Injury auto-sub stated as automatic**, since `SubstitutionService.onTickInjuriesOnly()` already is
- [x] **Closes an hour before kickoff**, and the screen disables itself from the server's own `editable`
- [x] Refuses a sixth rule with the reason — five subs and three windows are engine limits
- [x] **Mounted only for a fixture the manager's club is playing at home, and only before it is played.**
      The plan is the home club's instruction; offering it on an away fixture, or on one with a result,
      is a control with no meaning — and, as T0-UI-4b records, was briefly mounted on a page no manager
      could reach. It is mounted on the match view now, on both sides of the whistle.
- [x] 430px — the remove control drops under the sentence rather than squeezing it
- [ ] **Not verified in a browser**, and the panel has never been rendered by anyone

**Found while testing it: `DELETE /substitution-plan` was always a 500.** `deleteByMatchId` is a derived
delete and needs a transaction, which the controller had not. It survived because the four tests that
existed tested an unknown id, a round trip, a replace and an empty plan — **and none of them deleted
anything.** A route with no test is a route that was never pressed. Now `@Transactional`, and covered.

### T0-UI-5 · CLOSED 2026-10-10 — the premise was wrong; the live panel exists

**This task asked for a screen that implies real-time to stop implying it, because no such screen was
believed to exist. It exists.** The owner pushed back on the proposal to work this, which was correct.

`reveal-ui.js:55` — *"Watch your match"* opens `/demo/service/ui/proposal/index.html`, and that viewer
**simulates the match with the proposal engine**: a live ticker with a running clock, a tick counter, a
speed control and a *"Simulating match…"* state. The button is only offered for a match the manager has not
yet revealed, so opening it *is* watching it happen. There is no copy anywhere claiming a feed that does
not exist — a search of `match-view.js` for live/real-time/streaming wording returns nothing.

**So the honest outcome is that the screen is right, not that the copy needs fixing.** Nothing is being
deferred and nothing is lost.

**The mistake is the part worth keeping.** I proposed this task off the board's wording without opening the
viewer, and the owner — who uses the game — knew the answer immediately. A board entry describes a
*belief* about the system; it is not evidence, and this session produced a run of items where that
distinction cost real time. **Nothing goes on the board as work until someone has looked at the thing it
describes.**

### T0-UI-6 · The analytics panels — audited 2026-10-10, scoped in three parts

**The task as written asked for six things. The audit says two are nearly free, one is already shipped,
and three need data that does not exist.**

| | State, verified against the source and the database |
|---|---|
| **xG per side** | **Already done and already displayed.** `ProposalMatchOutcomeBuilder.computeExpectedGoals` scores every shot from its distance to goal, and the Stats tab has shown `['xG', …]` all along. Nothing to build. |
| **Field tilt** | **Derivable from data that exists.** `player_zone_load` holds **27,757 rows across 235 matches** — nine zones per team (`ATTACKING/MIDFIELD/DEFENSIVE` × `LEFT/CENTRE/RIGHT`) with minutes and intensity. Field tilt is the attacking share of it. |
| **PPDA** | **Cheap, with one gap.** Passes attempted is team-level; `tackles`, `duelsWon`, `blocks`, `interceptions` are all counted but only reach the payload **per player**, never summed per side. One aggregation away. |
| **xA** | **Dropped by owner decision, 2026-10-10** — *"izbaci xA skroz, nepotreban je kad imamo xG."* It never existed, and with xG on the screen a second speculative probability is a number the manager cannot act on any better than the one he already has. |
| **Shot map** | **The data does not survive the match.** `positionRow` / `positionColumn` exist on `sim/recording/MatchEvent` — the **in-memory recorder** — and on nothing else. No table stores where a shot was taken. xG is computed from that position and then persisted as a number; the position itself is gone. Every event table is 0 rows. |
| **Progressive passes** | **Same gap.** Pass positions are not persisted. |
| **Momentum strip** | **Same gap.** There is no per-tick or per-minute timeline in the database — `match_tick_states` is 0 rows and nothing writes it (T2-9). |

**So the shot map, progressive passes and momentum are not UI work.** They are data-layer work: a shot's
and a pass's cell has to be written down at the moment it happens, across 14,723 clubs and 3,600 ticks a
match. That is a decision about what the database stores, and it belongs beside T2-9 rather than inside a
screen.

**The zones are named from the player's own perspective** — `ATTACKING` means the attacking third *for that
player*, so the two sides must be read through the same perspective discipline as the tactical mirror
(T-REST-14) or a home side's attacking third would be compared against the away side's defensive one.

**Recommended order — accepted by the owner, 2026-10-10**
1. **Field tilt and PPDA**, each as a number **with its plain-words reading** — the existing data, no new
   persistence, and the two a manager would actually act on.
2. ~~**xA**~~ — **removed at the owner's decision.** With xG already on the screen it is a second
   speculative probability, and a number the manager cannot act on is a number the panel does not need.
3. **Shot map, progressive passes, momentum** — filed as their own data-layer task, not here.

Every number carries its own explanation, and a 430px pass, as the task already requires.

### T0-UI-6 (part 1) · DONE 2026-10-10 — field tilt and PPDA, each with its reading

The two the audit found were nearly free. **xA is gone** (owner: *"izbaci xA skroz, nepotran je kad imamo xG"*), and the
three that need data the game does not keep are filed as their own task below.

**Field tilt — arithmetic on data that already existed.** `player_zone_load` holds nine zones per player
with minutes and intensity; tilt is each side's share of its **own** minutes in the attacking third. The
zones are named from the player's perspective, so the two sides are compared share-for-share — reversing
one side would compare home's attacking third against away's defensive one and produce a number that looks
like tilt and means nothing. The same perspective discipline as the tactical mirror (T-REST-14).

**PPDA — and what its denominator actually is.** The engine had been counting passes attempted,
clearances, interceptions, blocks, deflections and fouls on every match and **none of them reached the
stored payload** — the numerator was a percentage and the denominator did not exist. `SimReportMapper.statsMap`
now writes all of them. **Tackles are not in it**: they are counted per player and never summed to the side,
so this is deliberately not the textbook "tackles + interceptions + fouls", and **the definition is printed
under the figure** rather than implying a textbook number it is not computing.

**Nothing is invented where it was not measured.** A match whose stored stats predate these keys shows
*"Not recorded for this match"*, not 0.0 — a zero would be a claim about a game rather than a statement
about a column. The **235 matches already in the world** are all in that state for PPDA, and the panel says
so for each of them rather than going silent, because silence reads as "nothing to show" rather than "not
measured".

**Verified in the running application at 430 × 932**, on a real played match with 235-matches-worth of zone
data: field tilt reads **NK Balkan 1928 33% v FK Sinđelić Užice 1945 35%** with the even-match reading
("Both sides spent about as long in the opposition half"), the PPDA panel names itself as not recorded, and
the existing xG row is unaffected. The two panels stack in one column at 430px with no overflow.

**A correction to the audit I wrote an hour earlier.** It said xG "is already computed and displayed".
That is true of the **engine** matches, and `computeTeamStats` prefers `statsJson` — which carries the real
score. But the fallback path for a match with no `statsJson` computes `goals * 0.7 + 0.5` and labels it
xG. No match in the current world takes that path; a legacy or hand-created one would show a fabricated
figure. Recorded rather than quietly corrected, because the fallback is deliberate and the honest fix is a
separate decision.

### T0-UI-6 (part 2) · The shot map, progressive passes and the momentum strip — NOT UI WORK

Deferred by the audit, and filed here so it is not lost. **All three need data the game does not keep.**
A shot's and a pass's cell exist only on `sim/recording/MatchEvent` — the in-memory recorder — and on
nothing else; every event table is 0 rows; there is no per-tick timeline at all (`match_tick_states` is 0
rows, T2-9). xG is computed from that position and persisted as a *number*; the position is gone.

**This is a decision about what the database stores**, written down at match time across 14,723 clubs and
3,600 ticks a match, and it belongs beside **T2-9** rather than inside a screen.

**Exit criteria**
- [ ] An owner decision: persist shot and pass positions, or close these three as not being built
- [ ] If persisted: a shot map, progressive passes, and a momentum strip, each with its reading

### T0-UI-7 · DONE 2026-10-10 — a national side can answer a warm-up, and the senior tab works at all

**Two of the three board items were already true, and the third was hiding behind a crash.**

- [x] Request — the "Ask for a warm-up" button and opponent list have been there since the panel was
      written. No change needed.
- [x] **Optional is stated as optional** — the panel reads, verbatim: *"Optional. A warm-up is an extra
      match in week 6 day 1; not playing one costs nothing and skips no rule."* No change needed.
- [x] The club-side invite button is rendered, at `country-view.js` `[data-warmup-request]`.
- [x] **Accept and decline now exist.** This is the real gap, and it was the only one.

### What was actually broken

`warmUpRequestListHtml` rendered every request as one sentence — **`Side 9 · pending`**. A side that had
been asked for a warm-up had no way to answer it: the backend has had `/respond` and `/cancel` since it
was written and **nothing called them**. A request could be made and then only waited on.

An incoming pending row now answers, an outgoing one withdraws, and a settled one shows its reason and
offers nothing, because a decision that is already made cannot be unmade from this screen. Rows name the
other side — *"Side 9"* is technically correct and useless to a manager deciding whether to accept.

### 🔴 Found on the way: the senior and U-21 tabs were dead

The panel never rendered at all, because `NationalTeamElectionService.describeElection` **creates an
election row on read** and was annotated `@Transactional` — the default `REQUIRED`, which **joins the
caller's transaction and inherits its read-only flag**. `NationalTeamService.describe` is
`@Transactional(readOnly = true)`, so every call threw `cannot execute INSERT in a read-only transaction`.

The damage was not a visible error. The country page reads `seniorNt` to learn the side's team id, so
every read threw, the loader swallowed it into `{ failed: true }`, and **the senior and U-21 tabs rendered
with no squad, no selector and no warm-up panel while looking as though they had loaded fine.**

`REQUIRES_NEW` suspends the outer transaction and gives this one a writable session. The write is
intended and documented, so the transaction was what needed fixing — not the write.

### Evidence

Browser at **430px**, against live PostgreSQL, on Serbia's senior tab:

- Incoming PENDING from Belgium → **Play them** / **Cannot make it**, naming *BELGIUM · PENDING*.
- **Play them** → request `ACCEPTED` **and fixture 5445 created**: Belgium (47) v Serbia (3), season 2,
  **week 6 day 1** — exactly the slot, confirming the whole path end to end.
- **Cannot make it** → request `DECLINED`, reason recorded.
- The panel reads *"Optional. A warm-up is an extra match in week 6 day 1; not playing one costs nothing
  and skips no rule."* — the week comes from `/slot`, so the page never restates it.
- The 409 guard fires correctly: asking a side that already has a match that week returns the sentence,
  which is shown rather than replaced.

**All test data removed** — `friendly_request` rows: 0; fixture 5445 deleted; live DB as found.

### Regression cover

`render-country-view.mjs` now drives all three request states and asserts them: **incoming pending
answers, outgoing pending withdraws, a settled request carries its reason and offers nothing**, and the
other side is named rather than shown as an id.

**Proven able to fail**: disabling the answer buttons turns *"missing `data-warmup-accept`"* and
*"missing `data-warmup-decline`"* red.

The harness's per-state verdict was also corrected while doing this. It printed `ok` on the same line
as two `FAILED` lines, because the verdict was counted globally rather than per state — a green status
next to a red assertion is the exact thing this repository has been burned by, and it would have taught
the next reader to trust it.

**`CountryPageRendersWithoutReferenceErrorTest` reports 0 skipped.** Node is at `/usr/local/bin/node`; it
is simply absent from the agent shell's PATH, which is why this was recorded as a gap in the first place.

### Known gap

- The decline reason reaches the **requester**, not the responder: `incoming()` filters to `PENDING` only
  while `outgoing()` returns everything. Verified in code and now in the harness, but not in a browser —
  no Northern Ireland account exists to log in as.

## T1-1 · 🔴 `ConditionalSubstitutionRules` is dead in production

Covered by **T0-BE-4**. Listed here as well because the defect class matters more than the task: **an
engine contract with ten green unit tests and zero production callers reads exactly like a working
feature.** It was believed covered for three sprints.

**Exit criteria:** the contract fires in a real match, proven by a substitution that happened because of it.

## T1-2 · 🔴 `SubstitutionPlan` is persisted and never read

The same defect, one layer out. A `SubstitutionPlan` row survives a restart and is invisible to every code
path. Covered by **T0-BE-4**.

## T1-3 · 🔴 `TacticsBridge` and `NewLogicTacticsService` are dead code

| Class | State |
|---|---|
| `TacticsBridge` | `fromRuntimeMap()` converts a runtime rule map into `TacticRules`. **Zero callers.** The archive notes this bridge was written and left unwired — the same failure mode, duplicated |
| `NewLogicTacticsService` | `loadTacticRules(teamId, formation)` **discards both of its own arguments.** Zero callers |

Both were named as *the* bridge this feature needed, and neither is wired. **Decide: wire or delete.** They
are covered by **T-REST-8** for the deletion and **T0-BE-1** for the wiring, and this task exists so the
decision is recorded once rather than three times.

**Exit criteria:** both are either wired into the T0-BE-1 path or deleted, with the caller count re-verified
**immediately before** deletion

## T1-4 · DEFERRED BY OWNER 2026-10-10 — the six tactics sliders have zero readers

**Deferred, deliberately (owner, 2026-10-10):** *"slidere cemo kasnije vezati zaista za taktiku kad se budemo
bavili taktikom, zasad ih drzi jasno u boardu."* Not forgotten and not closed — the six fields stay in the
model and stay visible here, and they get wired when the tactics work picks them up.


`TeamTacticsProfile` carries `aggression`, `defenceLine`, `pressing`, `possession`, `counterAttack` and
`ballControl`. **Zero readers in `src/main`.** They are stored and never used. The archive's position was
that the product should ship **both** models — the grid for a manager who thinks in geometry, sliders for
one who does not — which means both need consumers.

**Exit criteria:** each slider changes something observable in a match, **or is removed.** A stored field
nobody reads is a lie in the data model.

## T1-5 · 🟠 `mirrorWeHaveBallRules` overwrites the defensive shape

`TeamTacticsService` mirrors `WE_HAVE_BALL` onto `OPPONENT_HAS_BALL` on every save, and `DefensiveShape`
derives the out-of-possession shape arithmetically. **This is an owner decision, not a defect** — but it
means the editor's out-of-possession data is unreachable, and the current source of truth is arithmetic in
`DefensiveShape` rather than anything the manager authored.

**Exit criteria:** the decision is written down in the entity's javadoc, and the UI says which shape is in
force. **Do not "fix" the mirroring** — see the traps.

## T1-6 · DONE 2026-10-10 — "9 in catalog, 1 applied" is not true

**The claim being retired.** The archive records *"9 in catalog, 1 applied"*, and the board carried it as
an open item. **It is false.** All nine layouts exist, each fields a distinct eleven, and **two formations
are already in use in the world** — 4-4-2 and 3-4-3 are both saved on real clubs.

**Why it needed a test rather than a reading.** A catalog can hold nine layouts and still hand every club
the same eleven, because {@code getOrDefault(..., layouts.get("4-4-2"))} falls through for any name it
does not recognise. Counting nine layouts proves nothing; what had to be shown is that the nine produce
nine different XIs.

** now asserts that**, and **proven able to fail**: collapsing the lookup so every
formation falls through to 4-4-2 — the exact defect the entry describes — turns **2** checks red,
including *"4-3-3 and 4-4-2 field exactly the same players in the same slots"*.

It also spot-checks the pairs the eye would call the same. 4-4-2 and 4-5-1 differ only in the middle
band, and 3-4-3 and 3-5-2 only in the width of it, so a catalog that quietly widened one into the other
would still hold nine names and pass a count.

## T1-7 · 🟠 `MatchdayJob` issues one query per competition

`MatchdayJob.java:91-98` calls `fixtures.findUnplayedOnDay(...)` **inside** a `flatMap` — once per matching
competition, returning the identical set every time. Only the filter differs.

`CompetitionType.CUP` now matches the national cup **plus fifteen continental cups**, so a cup matchday
issues **sixteen identical full-table queries**, and it worsens with every tier added.

**Exit criteria:** one query, filtered in Java. Measured, not asserted.

## T1-8 · 🟠 `buildPlayoffSummary` can disagree with what was applied

`SeasonService:910-918` names relegation via **literal indices 8 and 9**, while
`applyPromotionRelegationForLeague` computes the same thing dynamically from `expectedTeams` and
`movementSlots`. The apply side became dynamic; the summary did not, so **for any league that is not exactly
ten clubs with two child leagues, the player-facing summary names the wrong clubs.**

**Exit criteria:** the summary reads the same computed positions the apply side uses

## T1-9 · 🟡 `StaffSponsorService.seedAllClubs` has a real N+1

Recorded and deliberately left. Reading the world is the point of a seeding path, so **tier 3 is
acceptable** — but this one is a genuine `1 + 14,880` inside the loop, which is a different shape from
"the seed needs the data".

**Exit criteria:** one query for the set, not one per club

## T1-10 · ✅ DONE 2026-10-09 — every loader checks the response, and one "API Error" card is gone

`LoadersCheckResponseOkTest`, 4 tests.

### Why it matters, stated precisely

`authFetch` **throws on every non-2xx.** So an unguarded `await response.json()` never reaches the
`if (!response.ok)` guard written for it — the throw skips it and escapes to the router, and `pages.js` is
the router every page goes through. Its last line replaces the page with a two-word card. So a 403 carrying
*"Only the owning club can accept incoming offers"* and a 500 from an unreachable database **reached the
manager as the same two words.**

### The worst of them were `Promise.all` groups, not single loaders

| Screen | What one non-2xx did |
|---|---|
| **ZOX match view** | preview + statistics + report fetched unchecked. A manager refused the post-match report **also lost the pre-match preview, which he was allowed** |
| **Transfer centre** | market + overview + squad unchecked. A 403 on the global market **also emptied the list of his own players** |
| **Statistics** | team directory guarded, the two leaderboards not — in one `Promise.all` |

### Landed

Two shared readers in `utils.js`, so this is one import and not six copies of a guard:

- **`readJsonOrThrow(response, label)`** — where the screen cannot be drawn without the payload
- **`readJsonOr(response, fallback, label)`** — where one panel failing must not empty the others, and it
  **says which panel went empty**, because a fallback returned without a word is indistinguishable from a
  competition that genuinely has nothing in it

Fixed: `fixture-view` (upcoming + friendlies, plus four `console.log`), `pages.js` `loadCup` /
`loadInternational`, `stats-view` player stats and the two leaderboards, `club-management`'s transfer
`Promise.all`, `zox-match-preview`, and the **admin database-job poll**, where one 500 mid-poll threw out of
the loop and left the loading popup on screen for ever.

**11 debug `console.log` lines removed**, and **`login.js` no longer logs the first 20 characters of the
JWT** to the console.

### Two board items were already fixed and are recorded as stale

- **`fetchPlayerRatingSummary`** — *"called with 1 argument at 2 sites; average rating permanently —"*. It
  takes `authFetch`, checks `response.ok`, and both call sites pass it. Fixed in the P0-PREV work.
- **`matches.js`** — *"the `if (!response.ok)` guard is still unreachable"*. Both loaders now catch and
  render a page saying what failed.

### The guard, and three mistakes of mine that made it look green

It scans the shipped files, because **this repository has no JavaScript test infrastructure at all** — no
runner, no `*.test.js`. It strips comments before scanning (P0-RANK-4 lost a day to a guard matching its own
javadoc) and excludes `tifo.js` **by name with the reason**, since that is the text-football mode.

**My first version of this guard passed against three planted defects.** Three separate errors:

1. **The debug-log window looked the wrong way.** `console.log('Loading X')` sits on the line *above* its
   `authFetch`; the window scanned *upward*, so it read the previous statement, matched nothing, and passed.
   **A window in the wrong direction is not a narrow window — it is no window.**
2. **Two of my mutations were not mutations.** I removed `readJsonOr` from two loaders that were inside a
   `try`, so the code was still handled and the green was correct. A mutation must remove the thing the
   assertion is about **and nothing that was already covering it.**
3. **`git checkout --` on a tracked file reverted the work the previous restore had put back**, and the
   chain of copies in between preserved the wrong version. Today's `fixture-view` changes were gone when I
   noticed; they are restored.

**Exit criteria**
- [x] Every loader in the graphical football UI checks the response, directly or through a shared reader
- [x] Both directions of a `Promise.all` group degrade independently
- [x] A guard, with `tifo.js` excluded by name rather than by a wildcard
- [x] **Proven able to fail:** a planted unguarded loader and a planted `console.log` each turn it red
- [ ] **Not verified in a browser.** Every one of these pages is markup and fetch behaviour

**`tifo.js` — the text-football mode — is excluded and unreviewed.** It has the same shape in several places.
A separate card.

## T1-11 · 🟡 `JobStatusService.report()` reads the whole `job_run` table

The entire table is read into memory on every `GET /admin/jobs`, then re-filtered per job in Java. The
table grows with clock movement — roughly twenty rows a game day. Bounded now, unbounded later.

**Exit criteria:** a per-key aggregate query. **Measured**, since this is a hot admin path.

## T1-12 · 🟡 `GoalEventRepository`'s season-wide variant walks every match with its blob

It backs top scorers, top assists and the club milestone leaders. Survivable only because P1-7b cut the
blob 49×. **This is the reason it is not a P0, and the reason it is not free.**

**Exit criteria:** measured against a full-scale season before anything changes

## T1-13 · ✅ DONE 2026-10-09 — the dead CSS class was a symptom of a dead page

`is-highlighted` was recorded as *"used by the represented-country group tables to mark the country being
viewed, not defined in the stylesheet at all."* **Both halves were true, and the class was not the defect
— the page emitting it was gone.**

### `renderRepresentedCountry` was called and defined nowhere

`loadCountryPage:759` calls `await renderRepresentedCountry(mainContent, countryIso)`. **The function does
not exist anywhere in the static tree.** Nor do `groupStandingTable`, `ordinal` or `formatNumber`.

**It was lost in the commit that fixed a bigger version of this same bug.** `73aafa6` — *"P1-CTRY-1: a
Clubs tab, and a country page that had been DEAD"* — restored `loadCountryPage` after `P0-PREV-1` deleted
it, and re-introduced the call, but not the function. The commit message says it restored *"209 lines, the
one function, not 458 lines and half the file."* **The one function was `loadCountryPage`. The second was
left behind.**

**The crash was reachable by an ordinary click.** World page → any national competition → a group table →
**every country name is a `.js-country` button** (`national-tournament-view.js:26`) → `openCountry` →
`loadPage('country', { simulatedCountry })` → `renderRepresentedCountry` → **`ReferenceError`**.

**And it was uncaught.** The call sits *outside* `loadCountryPage`'s try block, so it propagated out of the
page entirely. A manager clicking their own nation in a World Cup group got a **blank page**, not an error
card.

**Why nothing caught it.** All six tabs green, the whole suite green, `node --check` parses. The harness
written on 2026-10-08 for exactly this class — it loads the real module in Node because *"a
`ReferenceError` inside a template string needs an engine"* — drove **six tabs and never this path**,
because the path is not a tab.

**Landed:**
- [x] All four functions recovered from `51edd45~1`, restored before `loadCountryPage`
- [x] Represented path added to the Node harness, all three endpoints it reads populated — so a missing one
      cannot pass by rendering nothing
- [x] **`is-highlighted` defined in the stylesheet.** Same rule as `is-current-club` and deliberately so:
      both answer "which row is me?", in two different tables
- [x] The harness **fails** on a missing mark rather than logging it — a row marked with a class the
      stylesheet does not define looks identical to one marked correctly on screen
- [x] Two Java tests: the path renders, and the country being viewed is marked
- [x] **Proven able to fail:** removing the class turns **2 of 5 red**; removing the function turned the
      harness red with `renderRepresentedCountry is not defined` **before any fix was written**

**`is-current-club` is still open** and is deliberately not in this task: `CountryController.qualifyingRow`
puts **no `teamId`**, so the frontend cannot mark the manager's club without matching on **name** — the join
that produced four defects in this codebase. It needs one field.

**The lesson, and it is the second time this file has done it:** a function can be deleted by a commit that
is *about* restoring the file, and every signal stays green. Only an engine driving the path a user can
actually reach finds it — which is why the harness now drives it, and why `theHarnessCanFail` asserts it
drives `simulatedCountry` at all.

## T1-13b · DONE 2026-10-09 (board corrected 2026-10-10) — `is-current-club` carries the club id

**What it was.** The rule exists (`inset 3px 0 0 #4a9eff`) and **nothing emitted it.** The manager's own
club was unmarked in his own qualifying race.

`CountryController.qualifyingRow` sent `teamName`, `position`, `points`, goals and `qualifies` — **and no
`teamId`.** The frontend therefore could not compare anything, and the alternative was comparing **names**,
which this codebase has been burned by four times (`isUserMatch`, the ZOX fixture-id guess, the report's
per-side attribution, `APIController.myMatch`).

**What was done.** `teamId` on the qualifying row · the manager's team id passed into the country view as a
dep · the class list **built** rather than assigned, so `is-qualified` is no longer overwritten · one test
per combination of qualifying and own-club.

**This entry sat open while the work was already done.** `qualifyingRow` sends `teamId`, `pages.js` passes
the manager's id in as a dep, and `country-view.js` builds a class list instead of overwriting
`class="is-qualified"` — so a club that is both qualifying and the viewer's own carries both classes. The
JS harness covers that combination and the negative case.

It was found by reading the board against the source rather than by a test, which is the wrong way round.
Three entries had drifted by the end of the session, in **both** directions: two closed bodies under open
headers, and one open body over finished code.

**Exit criteria:**
- [x] A qualifying row carries its club id
- [x] The manager's own club is marked in all three cups across all five tiers
- [x] A club that is both qualifying and the viewer's own carries **both** classes
- [x] **Proven able to fail:** removing the `teamId` from the payload turns the test red

## T1-17 · 🟡 `tifo.js` — the text-football mode, unreviewed

Excluded from **T1-10** by name, because it is a separate product on its own lobby card, not the
graphical football UI. **It has at least four unguarded `.json()` calls and the same
`Loading… / Response status:` debug pairs**, so the defect class T1-10 closed is present there and has never
been looked at.

**Exit criteria**
- [ ] The same two shared readers applied, or a decision recorded that the mode is being retired
- [ ] The same guard applied with the exclusion removed

---

## T1-14 · ✅ DONE 2026-10-09 — the router carried four routes nothing could reach, and named a fifth wrongly

`RouterNamesResolveTest`, 3 tests.

### The four, and what two of them actually were

| Route | What it did | Verdict |
|---|---|---|
| `teamStats` | called the **assists** loader — `describePage` called it *"team statistics"* | **Duplicate of `topAssists`, and it lied about what it drew** |
| `playerStats` | called the **scorers** loader | **Duplicate of `topScorers`** |
| `friendlies` | called a loader the club page's fully-wired friendly panel already replaced | Duplicate |
| `analytics` | redirected to a standalone ZOX page that **duplicates what the match view already fetches inline** and that nothing links to | Duplicate surface |

**Then the guard found two more, and they were a different kind of thing:**

- **`coaches`** — `club-management` exported `loadCoaches: loadStaff`. **A literal alias.** The same function
  under a second name, reachable only through a route nothing navigated to. Deleted at the source, not just
  at the route.
- **`upcoming`** — rendered the club's fixtures from the same endpoint as `schedule`, under a second name.

**`PAGE_NAMES` also carried `chat`, `events`, `coaches` and `upcoming` with no case behind them** — the
remains of the community routes Phase 6 replaced, and of the two duplicates above. A name in that table
with no case behind it is **how `teamStats` came to be described as "team statistics" for a screen that drew
the assists table.**

### Kept, with the reason recorded

`nationalTeam` and `u21Team` are a **second path to the national-team screen**, which the country page also
reaches as a tab. Kept in `ENTRY_POINTS` **because deleting a route does not un-bookmark it** — a shared
link or a bookmark is a real way in, and removing it turns a screen into *"API Error"*.

### The removal nearly caused a new crash, three times

`pages.js` publishes ~60 functions on `window` for legacy callers. Deleting a function leaves
`window.x = x` pointing at nothing — a **`ReferenceError` thrown while the module loads, on every page.**
That happened three times in this task and the guard caught all three.

**It is the same class as the country page**, where `renderRepresentedCountry` was called at line 759 and
defined nowhere (T1-13), and the same mistake I made twice earlier in the session by believing a restore
that had not happened. A deletion is not done until every reference to it has gone.

### The guard, and the flaw the first mutation found

`everyRouteIsReachable` **removes the `case` labels before searching for a caller.** The first version did
not, so a route's own label made it trivially "reachable" — and the assertion **passed against a planted
route called `legacyArchive` that nothing anywhere navigated to.** A guard that is satisfied by the thing it
is judging is not a guard.

`noDanglingWindowGlobal` resolves names against imports as well as local definitions. The first version
looked only at locals and reported five false alarms on names that were perfectly well imported.

**Exit criteria**
- [x] Every route in the switch is reachable, or is a named entry point with a reason
- [x] `PAGE_NAMES` describes only routes that exist
- [x] No `window` global points at something removed
- [x] **Proven able to fail:** a planted route, a planted dangling global and a planted orphan name each
      turn it red — after the first version was caught passing against a planted route
- [ ] **Not verified in a browser.** Deleting a route cannot be verified by a scan; somebody has to click
      every menu entry

### Two manual-vs-menu gaps found on the way, and **not** fixed here

`userManual.md` §4 promises two Club options that **have no menu entry**:

- **Friendlies** — the screen works, and it is rendered on the Club page itself, so this is a menu gap
- **Coaches** — the same screen as Staff, which *is* in the menu

Both are copy or menu decisions, not defects. Filed as **T1-18**.

## T1-18 · ✅ DONE 2026-10-10 — the manual now matches the Club menu, and cannot drift again

**Owner ruling applied: correct the manual, do not add menu entries.** Both screens work; neither is a
menu entry, and a menu entry for a screen already on the page you reach it from is clutter.

**The board entry was wrong about its own subject.** It said the manual promises **Coaches**; the manual
says **Staff**, and the word Coaches appears nowhere in it. It also missed both halves of the real gap:

| | |
|---|---|
| **Loans** | in the Club action row, and **absent from the manual entirely** — read as a missing feature |
| **Stadium** | documented as a Club option; it is opened from the **Club Profile** page's stadium button |
| **Training** | manual said "Training", the menu says **"Training Setup"** |
| **Friendlies** | documented as a Club option; it is a **panel on the Club page** itself |

An omission was worse than a promise: a manager reading §4 would conclude Loans did not exist.

**What changed.** §4 now lists the fourteen menu entries in the order the action row shows them, with
Loans documented and Training renamed to Training Setup. Stadium and Friendlies moved under
*"Two screens that are not in that row"*, each saying where it is actually reached. The heading exists so
the option list above it can be read as exhaustive and true.

**Why it will not drift again.** `UserManualMatchesTheClubMenuTest` reads `buildClubActionsHtml` — the
function that builds the row a manager clicks — and compares its labels, in order, against the `###`
headings of §4. It reads the source rather than a maintained copy, so a menu entry added tomorrow fails
here instead of in a support question.

**Proven able to fail**, in both directions:

| Planted | Result |
|---|---|
| a menu entry `Inbox` the manual does not document | `theMenuAndTheManualAgree` red |
| a manual heading `Scout Network` with no menu entry | two tests red |

Stadium and Friendlies are excluded **by name**, not by pattern: "works, but is not a menu entry" is the
fact being asserted, and it should not be inferred from a string.

## T1-15 · 🟡 `advanceHour` and `advanceWeek` are self-invocations

`GameClockService.advanceHours` is `@Transactional` and calls `advanceHour()` — a self-invocation, so the
proxy is bypassed. `advanceHour` is itself `@Transactional` and is likewise self-invoked, so its annotation
is inert. **`advanceWeek` is therefore 168 separate transactions**, each committing and each calling
`jobRunner.runDue` in between. A crash at step 100 leaves the clock at week+5-days with 100 hours of jobs
applied and 68 not, with no reconciliation pass.

`JobRunner.java:56-60` writes a long comment identifying exactly this trap, and is then walked into one
layer up.

**Exit criteria:** the hour step runs in the caller's transaction, or the partial application is recoverable

---

# 🟡 T2 — performance and optimisation

**Every task here must state a before and after measurement.** A T2 task with no number is not finished.
Where a number is known it is given — do not re-derive it, but re-confirm it if the code near it changed.

**The dev database cannot measure a T2 task.** `match` holds 155 rows. A sequential scan of 155 rows is
the correct plan and will confidently report "no problem" about a query that costs five seconds at full
scale. Every index added here was measured in a throwaway `sokker_bench` database built to those numbers.

## Already measured — do not redo

| Change | Before | After | Commit |
|---|---:|---:|---|
| Daily recovery zone-load read *(155-match dev database)* | 21.0 ms | **10.8 ms** | `d95da9d` |
| Weekly rollover squad reads (30 clubs, 3 listings) | 180 queries | **0** + 1 bulk | `e310856` |
| League top scorers / assists *(89,280-match season)* | 158.7 ms | **0.19 ms** | `e9142ed` |
| Club match history, played, ordered *(same)* | 170.3 ms | **0.26 ms** | `e9142ed` |
| Club page, all matches *(same)* | 140.6 ms | **0.20 ms** | `e9142ed` |
| One week of a season *(same)* | 156.4 ms | **27.8 ms** | `e9142ed` |
| Club Elo replay — 155 matches, 742 KB event log | 443–554 ms | **6.5–11.2 ms** | `6e63831` |
| `event_json` per match | 840,136 B | **17,001 B** | `6e63831` |
| Recovery read, one matchday *(17.7M-row table)* | 6,173 ms | **~539 ms** | `513f738` |
| International Elo replay — whole-world reads | 1 + 3 × matches | **1** | `379cb12` |
| Weekly squad rollover — squad reads | 1 + 14,880 | **2** | `379cb12` |
| AI friendly pass — week reads, per friendly week | ~59,520 | **2** | `379cb12` |
| Club page blob fetched and discarded | 10 MB | **204 KB** | `6e63831` |
| Pyramid seeding existence check | 14,880 round trips | **48** | — |

**Read the scale column, because it is not uniform.** The first three rows were measured on a 155-match
dev database. The `e9142ed` rows on a full 89,280-match season, and the `513f738` baseline on the same
season with `event_json` at its **real** 17 KB width. **Same query, different harness.**

## T2-1 · 🔴 `IDENTITY` disables JDBC batching — the blocker under T2-2 and T2-4

`IDENTITY` generation disables JDBC batching for **70 of 71 entities**, so `batch_size=50` is dead code.

**Q1 was answered: keep `IDENTITY`** (option B). No schema change on 14,880 clubs' worth of data, and the
honest partial is taken. **T2-2 stays blocked and should say so rather than be re-attempted.**

The cost is that every write path that wants to batch cannot, which is why T2-2, T2-4 and T2-5 all become
slower rather than faster. That is the price of the decision and it should be recorded as one.

## T2-2 · `ZoneLoadRecorder` writes 198 rows per match, one at a time

22 players × 9 zones, each row saved individually. There is already a unique index on
`(player_id, match_id, zone)`, so the rows are known to be distinct — **the batch is safe to attempt.**

- [ ] **BLOCKED while `IDENTITY` stands** (T2-1). Recorded as blocked, not retried
- [ ] When unblocked: rows and elapsed time measured before and after

## T2-3 · `MatchdayJob` — sixteen identical full-table queries

See **T1-7**. It is on this board twice because it is a **performance** defect and a **correctness** one:
a query issued sixteen times returns the same set, and the number grows with every tier added.

**Exit criteria:** one query. Measured query count, not milliseconds.

## T2-4 · The static world build — ~43,000 inserts for 14,260 clubs

Already improved substantially: per-country `REQUIRES_NEW` (so a failure at country 40 loses 40 countries
rather than all 48), one read and one `saveAll` per division instead of two queries and one INSERT per
club. **The remaining cost is the rows themselves** — about a country a minute. Faster, visible, resumable,
not instant.

- [ ] The remaining cost is stated against a target, not against "it used to be worse"
- [ ] Where it runs is a design question, not a performance one: **the world is being built implicitly
      inside a matchday job** while a **Seed other nations** button exists for exactly that work. Owner
      decision.

## T2-5 · `JobStatusService` reads the whole `job_run` table

See **T1-11**. ~20 rows a game day, unbounded later.

## T2-6 · `GoalEventRepository`'s season-wide blob walk

See **T1-12**. **Measured before changed** — the blob was cut 49×, which is the only reason this is a T2
rather than a P0.

## T2-7 · `StaffSponsorService.seedAllClubs` — 1 + 14,880

See **T1-9**. A genuine N+1 on a seeding path.

## T2-8 · Scale-sensitive read paths at 48 countries

`match`, `match_tick_states` and `player_zone_load` remain scale-sensitive. **Three of the four indexes
already landed were measured on the 89,280-match harness, not the dev table.**

`player_zone_load`'s unique index leads with `player_id`, so `findByMatchId` cannot use it — which is why
paging made it necessary and why `IN` with bound values must never be refactored into a subquery
(5,020 ms against 44 ms, same rows).

**Exit criteria:** every remaining hot query measured at full scale, in the bench harness

## T2-9 · `match_tick_states` is dead and grows zero

Nothing writes it, nothing reads it, 0 rows, and its only writer has no callers. **Do not index it.**
Deleting it is an owner call — see **T-REST-18**.

## T2-10 · The basketball and American-football N+1

`BbMatchSimulationService.savePlayerStats` and its American-football twin do **four queries per player per
match** — `findById`, `save`, a season-stats lookup, `save` — which is **88 round trips a match**. Measured
at **0.1745 ms** per round trip on this machine, so **15.4 ms a match** of pure overhead.

**It has never run**, because no match has ever been simulated in either sport. When these sports are
played, **this is the first thing to fix** — not a missing index on `bb_players`, which tops out at 5,580
rows where a scan costs under a millisecond.

**Exit criteria:** batched writes, measured against the round-trip count