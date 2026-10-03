# AGENTS.md

Guidance for AI agents (and for Warp) working in this repository.

> **Rewritten 2026-10-01.** The previous version of this file described a codebase that no longer
> exists. It named `RealisticMatchEngine`, `SimulationService`, `RuntimeSaveToDB`,
> `RoundSimulationAsyncService` and `ZoxReplayService` as the primary match path, documented a
> `cleanSheet/` package, an `old/` package, a `newLogic/engine/` package and a Swing `demo/` grid
> simulator, and listed test classes that are not in the repository. All of it has been deleted or
> renamed. **If a claim here contradicts the code, the code is the bug — report it rather than working
> around it.**

---

## 📌 Where the state of this project actually lives

**Read these two files first. This one is orientation; they are the truth.**

| File | What it is |
|---|---|
| **[`kanban.md`](kanban.md)** | **The board.** What is open, what is done, what order, and the agreed specifications. Everything still open that was in the old `sprintBacklog.md` is here. |
| **[`kanbanProgress.md`](kanbanProgress.md)** | **The append-only log.** One entry per task, newest first, each carrying the commit that landed it. This file holds no history; that one does. |

> The board is currently referred to as `kanban.md` / `kanbanProgress.md`. If you are following a
> document that calls them `backlog.md` / `backlogProgress.md`, they are the same pair.

**Do not treat the other root `*.md` files as current.** They are kept as history and several are
actively misleading — `sprintBacklog.md` still claims five stub services were deleted (they exist) and
still prints "Sprint 5 ◀ CURRENT" twice; `expertAudit.md` (2026-09-26) is superseded by
`experAudit01102026.md`; the `PROPOSAL_*` calibration documents are three generations behind. The
audit's own verdict is that the documentation is now the largest single liability, and its
recommendation is to consolidate on the kanban pair.

---

## 🔴 Agent Working Rules (non-negotiable)

> 1. **You always answer in English. Always.** The user writes in Serbian; every reply, message and code
>    comment is in English. Writing a reply in Serbian is a mistake — fix it immediately. This holds for
>    every session and must not be forgotten.
> 2. **Languages:** the user converses in Serbian; you answer in English. They have 100% English and find
>    it easier to type Serbian — do not make them translate your answers.
> 3. **Behave as a professional senior fullstack developer:** clean, simple code without overengineering.
>    If you have any doubt, **ask the user rather than going in circles.** Tasks are small and clear; you
>    should not lose time spinning like a junior. If you do not use something, do not build a separate
>    helper for it — slimming is on the backlog, not a personal instinct.
> 4. **Testing:** test like a professional senior QA — short, clear, professional. No fiddling, no fake
>    `[x]` checkboxes.
> 5. **Feature thinking:** think like a Product Owner *and* a football coach/analyst — what the game
>    realistically needs, not what is technically interesting.
> 6. Write code to clean SOLID and OOP principles — one responsibility per class, readable, no
>    overengineering.
> 7. If two files share a name (or two similar packages), **use the one the task names**; the other is
>    legacy and is not to be touched.

### The standing rules that matter most

**A green status is not evidence.** The recurring failure shape in this codebase is code that reports
success while doing nothing. Four separate bugs had it in one session alone: a seeding job that caught
its own failure and returned normally, a pyramid builder that gave up before the first league and still
reported a clean rebuild, a Back button with a `dataset` and no listener, and an `escapeHtml` used in a
`catch` and never imported. **Three of the four could only be found by opening the app, not by reading
the code.**

**A job is not done until it has been seen to change data.** Not logged as done — observed in the
database afterwards.

**Never hand back a half-built world.** Every seeding path must end in a world that passes the integrity
check. Two sessions were lost to this.

**A test that cannot fail proves less than no test.** This is not theoretical here: a rating guard was
written and commented as protection against needless writes, and comparing two boxed `Double`s with `==`
meant it never worked. A test written to catch it passed anyway, because Hibernate statistics were off
and it was comparing two zeroes. **When you write a guard test, break the code deliberately and watch it
fail.** Three tests in this repository were green while measuring nothing before they were fixed.

**Measure, don't assume.** `position: sticky` silently does nothing in this app's shell, and the obvious
fix has to be measured at 390×844 rather than reasoned about. A 1-1 with 38-4 shots needs a real event
dump before it can be diagnosed.

**Verify against a live database, not the shape of the log.** The log said `healthy=true` in a boot that
then rolled everything back.

---

## Project Summary

TIFO Sports Manager is a multi-sport club management simulation. The football game is a Spring Boot 3.3.3
REST API (Java 21) with a vanilla-JS ES6-module frontend served from the same app. PostgreSQL in
production, H2 in-memory under the `test` profile. Main class:
`org.example.SportsManagerApplication`.

**Scale, because most of the current work is a consequence of it:** 48 countries × 31 divisions × 10
clubs ≈ **14,880 clubs**, 96 national sides, a promotion ladder per country, and three tiers of
international club cups. That world grew roughly **48× in five days** and the code that schedules it was
written for one country. See cluster A in `kanban.md`.

---

## Build & Run

```bash
mvn clean package -DskipTests      # build
./run-app.sh                       # run locally (dev profile, needs PostgreSQL on :5432)
mvn test                           # full suite (H2) — 2 h 52 m over 159 classes. Needs the app on :8080
mvn test -Dtest=RatingEngineTest   # one class
mvn test -Dtest=RatingEngineTest#theSeedIsTheOwnersScale   # one method
```

> The compiler runs with `--enable-preview` for Java 21. If you invoke `javac` directly, include the flag.

### 🔴 Never start the app from a shell without `--app.open-browser=false`

`BrowserLauncher` opens `http://localhost:8080/login.html` on every startup. That is correct when the owner
runs `main()` from the IDE and wrong for any shell start — the page pops up over the terminal, every time.

**The application cannot tell the two cases apart.** An IDE start and a shell start are the same JVM with
the same properties. The default therefore cannot be right for both; it stays `true` for the IDE and
**every shell start must opt out.**

- Shell start → **`./run-app.sh`**, never `mvn spring-boot:run` directly
- `./run-app.sh --with-browser` to opt in for one run
- Playwright is unaffected — it launches its own Chrome and never used this launcher

Do not "fix" this by flipping the default off. It was briefly opt-in, and the owner's IDE start then did
nothing with no explanation, which is the confusing case the launcher exists to avoid.

### Boot writes nothing

Starting the application starts the application. **No seeding, no backfills, no repair, no catalogue.**
This is deliberate: the seeding used to run on every boot before the app was usable, so a cold start on a
small server looked like a hang, and there was no way to look at a world before it was changed underneath
you. World building moved to the admin buttons.

| Button | What it does |
|---|---|
| **Reset DB** | Clears the football data. Keeps user accounts and tactic-editor setups. Rebuilds nothing. |
| **Initialize DB** | The **Serbian** structure: 31 divisions, fixture list, players, owner. |
| **Repair world** | Rebuilds anything missing: countries, national squads, legacy rows. |
| **Re-seed national teams** | A 25-player squad for any national side that has none. |
| **Seed other nations** | Every country that is not activated: divisions, clubs, ratings, standing table. **No players, no matches.** Idempotent. |
| **Re-draw the cup** | Any cup round that never got drawn. |

**Neither boot nor "Initialize DB" is a world builder any more.** `ensureBaselineDataOnStartup()` has no
caller; do not add one without a decision, because three separate seeding steps were lost to that
transaction before.

---

## Architecture

**Base package:** `org.example.footballmanager.newLogic`. There are exactly two packages under
`org.example.footballmanager`: **`newLogic`** (the product) and **`demo`** (a frozen reference engine,
below).

```
newLogic/
  sim/          → the match engine. The heart of the game.
  service/      → business logic (78 classes)
  controller/   → REST (27)
  model/        → JPA entities
  repository/   → Spring Data JPA
  dto/          → request/response DTOs
  jobs/         → the day/hour job framework (DayJob, JobRunner, JobContext)
  util/         → seeders, backfills, integrity repair
  config/       → security, JWT, scheduling
  exception/    → ApiExceptionHandler
  store/, tools/
```

### The match engine — `newLogic/sim/`

**One engine, one entry point.** `ProposalEngineIsTheOnlyFixtureProducerTest` exists to keep it that way:
exactly one production site builds a football `Match`, and it is `SimMatchService`. That test exists
because a previous audit was **retracted** for claiming AI-vs-AI matches came from a Poisson dice roll.

- `SimMatchService` — the official path: simulate a fixture, persist score, events, player stats, zone load,
  Elo, league table, replay
- `MatchOrchestrator` — the tick loop; coordinates the engines, holds almost no logic itself
- `engine/` — `MovementEngine`, `BallPhysicsEngine`, `TacticalIntentEngine`, `DuelEngine`,
  `ExecutionQuality`, `ActionExecutor`, `ThreatOverrideEngine`, `decision/CleanDecisionEngine`
- `rules/` — `FootballRules` (offside), `VARService`, `DisciplineService`, `OffsideService`
- `tactics/TacticsRules` — formation targets
- `recording/` — `MatchRecorder`, `SimReplayView` (down-sampled replay, stride 10)
- `result/` — `ProposalMatchOutcome`, `ProposalStatsCollector`, `SimReportMapper`
- Diagnostics, each with a `main()`: `ProposalBatchDiag`, `ProposalSeasonDiag`,
  `ProposalPhysicsDiagnostic`, `ProposalPassFailDiag`, `MatchSimulationLauncher`

**A fixture is seeded by its own id**, which is what makes the engine's output worth trusting for a league
table: a fixture cannot be re-rolled.

**Determinism is a development moat.** Every calibration is a committed seeded run, and a replay
regenerated from a seed is reproducible. Neither browser competitor can offer that — so it is worth
protecting, and `SimulationRandom.seed()` is the place that guarantees it.

### `demo/service/` — frozen reference module

A second, older service-oriented engine (97 classes) with its own `corePrinciples.md` as its source of
truth, plus a batch runner and a chain trace.

**It is not the product path and must not be extended.** It is kept as a reference. Do not port from it
into `newLogic`, do not add features to it, and do not treat it as the architecture.

---

## The day/hour job framework — `newLogic/jobs/`

A scheduled game clock drives the season. `GameClockService` steps hours; `JobRunner` dispatches `DayJob`s
at their `(week, day, hour)`.

Four matchdays are registered in `MatchdayJobsConfig`:

| Key | Type | Day | Hour |
|---|---|---:|---:|
| `matchday-international` | INTERNATIONAL | 1 | 20 |
| `matchday-league-a` | LEAGUE | 3 | 19 |
| `matchday-cup` | CUP | 5 | 18 |
| `matchday-league-b` | LEAGUE | 7 | 16 |

**A season is twelve weeks.** There is no calendar year anywhere — `BASE_SEASON_YEAR` was deleted and every
`BASE_SEASON_YEAR + (season - 1)` site removed. A season is a *number counted from 1*. Any code or test
that passes 2024/2025/2026 as a season value is wrong, even if it is self-consistent and passes.

> **This framework has the highest-priority open defects in the project** — the guard row is written after
> the job body, `FAILED` is terminal with no re-queue, the season counter is incremented twice so every
> rollover skips a season, and any authenticated user can advance the world. See cluster A in `kanban.md`
> before changing anything here.

---

## API route prefixes

| Controller | Prefix |
|---|---|
| `UserController` | `/auth` (register, login) |
| `AdminController` | `/admin` |
| `APIController` | `/api` (clock, jobs, server-time) |
| `TeamController` | `/teams` |
| `PlayerController` | `/players` |
| `MatchController` | `/matches` |
| `SimulationController` | `/simulation` |
| `LineupController` | `/lineups` |
| `TrainingController` | `/training` |
| `TransferController` | `/transfers` |
| `ScoutingController` | `/scouting` |
| `JuniorController` · `JuniorSchoolController` | `/juniors` · `/juniors/school` |
| `CountryController` | `/countries` |
| `SeasonController` | `/seasons` |
| `CommunityController` | `/community` |
| `CalendarController` | `/calendar` |
| `StatsController` | `/stats` |
| `MatchPlayerStatsController` | `/match-stats` |
| `StadiumController` · `StadiumSettingsController` | `/stadiums` · `/api/teams/{teamId}/stadium` |
| `StaffDirectoryController` | `/api/teams/{teamId}` |
| `FinanceController` | `/api/teams/{teamId}/finances` |
| `FriendlyController` | `/api/season/friendlies` |
| `ProposalMatchController` | `/api/proposal` **and** `/proposal/api` — both are mapped; the second is what the frontend and the standalone viewer launcher use |
| `SimReplayController` | `/api/sim/replay` |
| `SubstitutionPlanController` | `/api/sim/matches/{matchId}/substitution-plan` |
| `ZoxApiController` · `ZoxReplayController` · `ZoxViewController` | `/api/zox` · `/api/zox/replay` · `/zox` |
| `AfController` · `BbController` | `/api/af` · `/api/bb` (other sports) |
| `CleanSheetController` | `/api/cs` (text-based mode) |
| `DummyDataController` | `/demo` — **fake data, hardcoded to team 1, 0 DB access. Awaiting an owner decision; do not wire it to anything.** |
| `CompetitionController` | *empty stub, 0 routes* |

Everything except `/auth/**` and the small explicit permit list requires a JWT.

---

## Frontend

All under `src/main/resources/static/`.

**Entry points:** `login.html` · `register.html` → auth → `home.html` (game-mode picker) →
`dashboard.html` (the SPA).

```
dashboard.html
  └── app.js · dashboard.js
  └── pages.js                 → the page router; imports every feature module
        ├── pages-renderers.js
        ├── auth.js            → JWT storage + authFetch wrapper
        └── pages/views/       → club, country, league, match, player, training, …
```

**Use `authFetch`** for every authenticated call — it injects the `Authorization` header and handles 401.
`escapeHtml` lives in `ui/escape.js` and is the only copy; do not redefine it.

**Always check `response.ok`.** Several loaders `await response.json()` without it, so a 404 escapes to the
router and the page becomes a generic "API Error" card. A menu entry pointing at an error card is worse
than no entry.

---

## Testing

```
src/test/java/org/example/footballmanager/
  BaseTest.java          → @SpringBootTest + @ActiveProfiles("test")
  newLogic/              → service, util, sim and controller tests, mirroring src/main
  demo/service/          → engine tests for the frozen reference module
  integration/           → REST Assured integration and E2E flows
```

**159 test classes, 992 tests — measured 2026-10-03, 13 failures + 16 errors = 29 red.** There is no
`ui/` package: the three Playwright classes (`CountryPageRendersTest`, `SidebarAccordionOpensTest`,
`MobilePanelOverflowTest`) live in `newLogic/service/`, which is why nothing in this tree hints that a
full `mvn test` needs a server.

**A full `mvn test` is 2 h 52 m and needs the application running on :8080.** The three Playwright
classes otherwise **hang the entire run** — they do not fail, they wait. Two further reasons it is slow:
`GameClockDateFollowsGameTimeTest` and `SeasonRolloverNumberTest` advance the clock, which fires the
matchday jobs, which simulate **986 matches between them**, engine tick trace and all. That is D3's
finding costing real wall clock. Budget for it; do not start it on the way to something else.

**Playwright** needs `mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install"`
once, a running app, and `setHeadless(true)` for CI.

**A full-suite run only counts if it was allowed to finish.** The `[ERROR]` summary is printed at the
end, so a run that is killed reports a *different* set of failures than one that completes — and two
baselines are only comparable if they were measured the same way.

### What the suite does not cover

**Nine controllers have no tests at all** — `Lineup`, `Player`, `Team`, `User`, `Admin`, `Community`,
`DummyData`, `Competition`, `Stadium` — and only three tests exercise any controller. **So the entire
security surface is untested**, which is why cluster C and the controller tests in `kanban.md` are meant to
be done in one pass.

---

## Known hotspots

- **`GameClockService` / `JobRunner`** — the scheduler. See the warning above; this is where the P0s are.
- **`pages.js`** — a large single router; every feature module is imported from it.
- **`PyramidBuilder`** — creates every club in the world and **never sets `Team.type`**, which is why
  "is this a club?" cannot be answered from that flag. A club is anything whose `competition` is a LEAGUE.
- **`Team.reputation` is the economy's 0–100 scale, not an Elo.** Eight services read it and four clamp it.
  Club Elo lives in `eloRating` / `eloPreviousRating` / `eloDelta`. `Country.reputation` is a *different*
  scale again on a column of the same name — that collision has already cost a session.
- **`DummyDataController`** — fake data, 18 routes, all hardcoded to team 1, zero DB access. Five frontend
  files still fetch it (`pages.js`, `club-management.js`, `staff-directory.js`, `fixture-view.js`,
  `stats-view.js`). Awaiting an owner decision; do not wire it to anything.
- **`AdvanceWeekAsyncService`** — 186 orphaned lines, zero callers, and the last holder of a hardcoded
  `findById(1L)`. Delete it.
- **`demo/service/`** — frozen. Reference only.

---

## Related documents

| File | What it is |
|---|---|
| [`kanban.md`](kanban.md) · [`kanbanProgress.md`](kanbanProgress.md) | **The board and its log. Read these first.** |
| `experAudit01102026.md` | The 2026-10-01 audit. Its §0 retracts three earlier claims; its Appendix B lists what it did not do, which is five items and matters. |
| `COMPETITIVE_ANALYSIS.md` | Product/architecture review against Sokker, Hattrick and FM. Its §10 intake list is the roadmap ordering. |
| `dataFixSuggestions.md` | Correctness and scale suggestions. **§1.1 is wrong** and is settled — read the kanban entry before touching anything here. |
| `ENGINE.md` | Short description of the live match path. |
| `TIFO_SPORTS_MANAGER_GUIDE.md` | User-facing manual, in English. |
| `BASKETBALL_PROGRESS.md` · `AMERICAN_FOOTBALL_PROGRESS.md` · `TIFO_TEXT_MANAGER_PROGRESS.md` | Per-mode agent notes for the other sports. |
| `sprintBacklog.md` · `sprintProgress.md` · `expertAudit.md` | **History. Superseded — see the warning at the top.** |
