# TECHNICAL_OVERVIEW.md — what this system actually is

**Written 2026-10-03 against the source, not against a plan.** Where this file and `kanban.md` disagree,
this file describes what exists and `kanban.md` describes what should change. Where either disagrees with
the code, **the code is the bug — report it.**

Superseded versions of this document are in `archive/`. They described an architecture that no longer
exists.

---

## What it is

A multi-sport club management simulation. The football game is a **Spring Boot 3.3.3 REST API (Java 21)**
with a **vanilla-JS ES6-module frontend** served from the same application. PostgreSQL in production, H2
in-memory under the `test` profile. Main class `org.example.SportsManagerApplication`.

| | Count |
|---|---:|
| Controllers | 25 |
| Services | 76 |
| JPA entities | 83 |
| Repositories | 43 |
| Classes under `sim/` | 84 |
| Frontend modules | 78 |
| Test classes | 154 |
| **Frozen reference engine** (`demo/`) | **97** |

## The two packages, and only two

```
org.example.footballmanager/
  newLogic/     ← the product
  demo/         ← a frozen reference engine
```

### `newLogic/` — the product

```
  sim/          the match engine: the heart of the game
  service/      business logic
  controller/   REST
  model/        JPA entities
  repository/   Spring Data JPA
  dto/          request/response DTOs
  jobs/         the day/hour job framework
  util/         seeders, backfills, integrity repair
  config/       security, JWT, scheduling
  exception/    ApiExceptionHandler
  store/, tools/
```

### `demo/service/` — frozen, reference only

An older, service-oriented engine (97 classes) with its own `corePrinciples.md`. **It is not the product
path and must not be extended.** Do not port from it into `newLogic`, do not add features to it, do not
treat it as the architecture. It exists to be read.

---

## The match engine — `newLogic/sim/`

**One engine, one entry point.** `ProposalEngineIsTheOnlyFixtureProducerTest` exists to keep it that way:
exactly one production site builds a football `Match`, and it is `SimMatchService`. That test exists
because a previous audit was **retracted** for claiming AI-vs-AI matches came from a Poisson dice roll.

| Piece | Responsibility |
|---|---|
| `SimMatchService` | The official path. Simulates a fixture, persists score, events, player stats, zone load, Elo, league table, replay |
| `MatchOrchestrator` | The tick loop. Coordinates the engines and holds almost no logic itself |
| `engine/` | `MovementEngine`, `BallPhysicsEngine`, `TacticalIntentEngine`, `DuelEngine`, `ExecutionQuality`, `ActionExecutor`, `ThreatOverrideEngine`, `decision/CleanDecisionEngine` |
| `rules/` | `FootballRules` (offside), `VARService`, `DisciplineService`, `OffsideService` |
| `tactics/TacticsRules` | Formation targets, read from the tactical editor |
| `recording/` | `MatchRecorder`, `SimReplayView` (down-sampled replay, stride 10) |
| `result/` | `ProposalMatchOutcome`, `ProposalStatsCollector`, `SimReportMapper` |
| Diagnostics | `ProposalBatchDiag`, `ProposalSeasonDiag`, `ProposalPhysicsDiagnostic`, `ProposalPassFailDiag`, `MatchSimulationLauncher` — each with a `main()` |

**A fixture is seeded by its own id**, which is what makes the engine's output worth trusting for a league
table: a fixture cannot be re-rolled.

**Determinism is a development moat.** Every calibration is a committed seeded run, and a replay
regenerated from a seed is reproducible. Neither browser competitor offers that.
`SimulationRandom.seed()` is where it is guaranteed.

### Tactics — how the editor reaches the engine

`TacticsRulesProvider` loads the **home club's** `TeamTacticsProfile` and `SimMatchService` passes it
through `MatchOrchestrator(state, tactics)` into `RestartManager` and `TacticalIntentEngine`, which ask it
for a target cell every tick.

Two properties of this that are easy to get wrong:

- **A role the tactic does not name returns `null`, and every caller falls back to the player's own
  position** — which `RealSquadFactory` already placed from his own formation's anchors. This is not a
  detail: the old fallback was a hardcoded `(1.5, 3.5)`, which stacked nine outfielders onto one square
  metre whenever two clubs had different formations.
- **`WE_HAVE_BALL` and `OPPONENT_HAS_BALL` are identical in every saved profile, on purpose.**
  `TeamTacticsService.mirrorWeHaveBallRules` overwrites them on save *and* on read, because the owner has
  not decided whether to keep both variants. `DefensiveShape` is therefore load-bearing: it manufactures
  the defensive shape the data cannot carry. **Do not "fix" the mirroring.**

**The away side is positioned by the home club's rules.** That is a known, open defect — **P0-3** in
`kanban.md`.

---

## The day/hour job framework — `newLogic/jobs/`

A scheduled game clock drives the season. `GameClockService` steps hours; `JobRunner` dispatches `DayJob`s at
their `(week, day, hour)`.

**Four matchdays** are registered in `jobs/config/MatchdayJobsConfig`:

| Key | Type | Day | Hour |
|---|---|---:|---:|
| `matchday-international` | INTERNATIONAL | 1 | 20 |
| `matchday-league-a` | LEAGUE | 3 | 19 |
| `matchday-cup` | CUP | 5 | 18 |
| `matchday-league-b` | LEAGUE | 7 | 16 |

The key includes `(season, week, day)`, because a shared key would let the day-3 job suppress the day-7
round.

**A season is twelve weeks.** There is no calendar year anywhere in the codebase — a season is a *number
counted from 1*. Any code or test passing 2024/2025/2026 as a season value is wrong, even if it is
self-consistent and passes.

**One press plays one matchday.** The four fixture endpoints read the clock's **week**, and `LeagueSlotSchedule`
puts **two rounds in each week**, so treating week as round skipped rounds entirely and **rounds 13–18 were
never reachable** — the clock stops at week 12. They now query `(season, week, day)`.

---

## Scale

48 countries × 31 divisions × 10 clubs ≈ **14,880 clubs**, 96 national sides, a promotion ladder per
country, three tiers of international club cups. **The world grew roughly 48× in five days**, and the code
that schedules it was written for one country. This is why `kanban.md` has a whole P1 category.

Measured on a **Serbia-only** dev database:

| Table | Rows | Note |
|---|---:|---|
| `player_zone_load` | 18,729 | Largest table. 198 rows written per match, one at a time |
| `player` | 10,130 | ~300k at full scale |
| `match_player_stats` | 3,410 | |
| `match_fixture` | 2,790 | |
| `team` | 406 | 14,880 at full scale |

**Three hot tables have no usable index** — `match` and `match_tick_states` have only their primary keys,
and `player_zone_load`'s unique index leads with `player_id` so a query on `match_id` alone cannot use it.
See **P1-1**.

---

## API

Everything except `/auth/**` and a small explicit permit list requires a JWT.

| Controller | Prefix |
|---|---|
| `UserController` | `/auth` |
| `AdminController` | `/admin` |
| `APIController` | `/api` (clock, jobs, server-time) |
| `TeamController` · `PlayerController` · `MatchController` | `/teams` · `/players` · `/matches` |
| `SimulationController` · `LineupController` · `TrainingController` | `/simulation` · `/lineups` · `/training` |
| `TransferController` · `ScoutingController` | `/transfers` · `/scouting` |
| `JuniorController` · `JuniorSchoolController` | `/juniors` · `/juniors/school` |
| `CountryController` · `SeasonController` | `/countries` · `/seasons` |
| `ForumController` · `MessageController` · `NotificationController` | `/forum` · `/messages` · `/notifications` |
| `UserProfileController` | `/users` — **not** `/auth`, which is `permitAll` with a per-method guard |
| `CalendarController` · `StatsController` · `MatchPlayerStatsController` | `/calendar` · `/stats` · `/match-stats` |
| `StadiumController` · `StadiumSettingsController` | `/stadiums` · `/api/teams/{teamId}/stadium` |
| `StaffDirectoryController` · `FinanceController` | `/api/teams/{teamId}` · `/api/teams/{teamId}/finances` |
| `FriendlyController` | `/api/season/friendlies` |
| `ProposalMatchController` | `/api/proposal` **and** `/proposal/api` — both mapped; the second is what the frontend uses |
| `SimReplayController` | `/api/sim/replay` |
| `SubstitutionPlanController` | `/api/sim/matches/{matchId}/substitution-plan` |
| `ZoxApiController` · `ZoxReplayController` · `ZoxViewController` | `/api/zox` · `/api/zox/replay` · `/zox` |
| `AfController` · `BbController` | `/api/af` · `/api/bb` |
| `CleanSheetController` | `/api/cs` (text-based mode) |
| `DummyDataController` | `/demo` — **fake data, hardcoded to team 1, zero DB access.** Five frontend files still fetch it. Awaiting an owner decision |
| `CompetitionController` | **empty stub, 0 routes** |

**Nine controllers have no tests at all** — `Lineup`, `Player`, `Team`, `User`, `Admin`, `DummyData`,
`Competition`, `Stadium` — and only three tests exercise any controller, so **the entire security surface
is untested**. That is **P0-1**.

**Stale as of P2-20:** this list no longer names `Community`. `CommunityController` had 8 tests
(P0-1b) and has since been **deleted entirely** along with the chat it served; the replacements are
`ForumController`, `MessageController`, `NotificationController` and `UserProfileController`, all with
tests. The count of untested controllers is unchanged — three were removed and three were added.

---

## Frontend

All under `src/main/resources/static/`.

```
login.html · register.html   → auth → home.html (game-mode picker) → dashboard.html
dashboard.html
  └── app.js · dashboard.js
  └── pages.js               the page router; imports every feature module
        ├── pages-renderers.js
        ├── auth.js          JWT storage + authFetch wrapper
        └── pages/views/     club, country, league, match, player, training, …
```

- **Use `authFetch`** for every authenticated call. It injects the `Authorization` header and handles 401.
- **`escapeHtml` lives in `ui/escape.js` and is the only copy.** Do not redefine it. It was once used in a
  `catch` block and never imported, which is one of the four "green but did nothing" bugs.
- **Always check `response.ok`.** Several loaders `await response.json()` without it, so a 404 escapes to
  the router and the page becomes a generic "API Error" card.

---

## Running it

```bash
./run-app.sh                       # dev profile, needs PostgreSQL on :5432
./run-app.sh --with-browser        # opt in to the browser launcher for one run
```

**Never start the app from a shell without `--app.open-browser=false`.** `BrowserLauncher` opens
`http://localhost:8080/login.html` on every startup. That is correct when the owner runs `main()` from the
IDE and wrong for any shell start. **The application cannot tell the two cases apart** — an IDE start and a
shell start are the same JVM with the same properties — so the default stays `true` for the IDE and every
shell start must opt out. Do not "fix" this by flipping the default; it was briefly opt-in and the owner's
IDE start then did nothing with no explanation.

### Boot writes nothing

Starting the application starts the application. **No seeding, no backfills, no repair, no catalogue.**
This is deliberate: seeding used to run on every boot, so a cold start looked like a hang and there was no
way to look at a world before it changed underneath you.

| Button | What it does |
|---|---|
| **Reset DB** | Clears the football data. Keeps user accounts and tactic-editor setups. Rebuilds nothing |
| **Initialize DB** | The **Serbian** structure: 31 divisions, fixture list, players, owner |
| **Repair world** | Rebuilds anything missing: countries, national squads, legacy rows |
| **Re-seed national teams** | A 25-player squad for any national side that has none |
| **Seed other nations** | Every country not activated: divisions, clubs, ratings, standing table. **No players, no matches.** Idempotent |
| **Re-draw the cup** | Any cup round that never got drawn |

`ensureBaselineDataOnStartup()` has **no caller**. Do not add one without a decision — three separate
seeding steps were lost to that transaction before.

---

## Testing

```
src/test/java/org/example/footballmanager/
  BaseTest.java          @SpringBootTest + @ActiveProfiles("test")
  newLogic/              service, util, sim and controller tests
  demo/service/          engine tests for the frozen reference module
  integration/           REST Assured integration and E2E flows
```

**154 test classes. The last recorded full run: 992 tests, 13 failures, 16 errors — 29 red — in about
2 h 52 m**, with the application running on `:8080`. The full red list and what each failure means is in
`archive/kanbanProgress.md`.

**A full run is only affordable deliberately.** Three Playwright classes (`CountryPageRendersTest`,
`SidebarAccordionOpensTest`, `MobilePanelOverflowTest`) live in `newLogic/service/`, which is why nothing in
the tree hints that a full `mvn test` needs a server — **they wait rather than fail, and hang the whole
run.** Two further classes (`GameClockDateFollowsGameTimeTest`, `SeasonRolloverNumberTest`) advance the
clock, which fires the matchday jobs, which simulate every unplayed fixture of every division of every
simulated country.

There is no `ui/` package. Playwright needs `mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI
-Dexec.args="install"` once and `setHeadless(true)` for CI.

---

## Known hotspots

- **`GameClockService` / `JobRunner`** — the scheduler. The highest-priority open defects in the project
  were here; all four P0s in cluster A are closed and **A4 and A5 were measured and are not bugs**.
- **`pages.js`** — a large single router; every feature module is imported from it.
- **`PyramidBuilder`** — creates every club in the world and **never sets `Team.type`**, so "is this a
  club?" cannot be answered from that flag. A club is anything whose `competition` is a LEAGUE.
- **`Team.reputation` is the economy's 0–100 scale, not an Elo.** Eight services read it and four clamp
  it. Club Elo lives in `eloRating` / `eloPreviousRating` / `eloDelta`. **`Country.reputation` is a
  different scale on a column of the same name** — that collision has already cost a session.
- **`DummyDataController`** — fake data, 18 routes, hardcoded to team 1, zero DB access.
- **`AdvanceWeekAsyncService`** — 186 orphaned lines, zero callers, and the last holder of a hardcoded
  `findById(1L)`. Delete it.
- **`MatchEventRepository`** — an in-memory `ConcurrentHashMap` with `save`/`deleteAll`, not a Spring Data
  repository. Whether events survive a restart is **P0-8 §1.5**, unverified.