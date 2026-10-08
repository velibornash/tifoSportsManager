# TECHNICAL_OVERVIEW.md — the football UI as it exists

**Written 2026-10-06 against the source, revised 2026-10-07 against `f8c492d`.** Scope is the graphical
football manager: the lobby path
`home.html` → `dashboard.html`, its vanilla JavaScript frontend, and the `footballmanager.newLogic`
backend that serves it. The text football mode, basketball and American football are mentioned only
where they share application boot or authentication. This document does not describe the frozen
`demo/` engine as product code.

The code is the authority. `kanban.md` describes work that should change; `kanbanProgress.md` records
what was measured and committed; `archive/` is historical context. If this document disagrees with the
source, report the documentation and follow the source until it is corrected.

## Contents

1. [System shape](#1-system-shape)
2. [Application boot and runtime](#2-application-boot-and-runtime)
3. [Authentication and request security](#3-authentication-and-request-security)
4. [Login, lobby and dashboard](#4-login-lobby-and-dashboard)
5. [Frontend architecture and routes](#5-frontend-architecture-and-routes)
6. [What the manager can do](#6-what-the-manager-can-do)
7. [Clock, calendar and jobs](#7-clock-calendar-and-jobs)
8. [World building and competitions](#8-world-building-and-competitions)
9. [Match simulation and persistence](#9-match-simulation-and-persistence)
10. [Management systems](#10-management-systems)
11. [API surface](#11-api-surface)
12. [Known gaps and unfinished paths](#12-known-gaps-and-unfinished-paths)
13. [Development and verification](#13-development-and-verification)

Section 8 carries its own subsections: 8.1 Admin page, 8.2 Reset DB, 8.3 Pyramid and fixtures,
8.4 Domestic cup, 8.5 International club cups, 8.6 National teams and tournaments, 8.7 Country/world
integrity, 8.8 Database backup and restore.

## 1. System shape

### 1.1 Product boundary

The application is a multi-sport Spring Boot application. The football UI is one product path inside
it:

```text
org.example/
  SportsManagerApplication
  config/                         shared JWT, security and browser startup
  commonmanager/                  shared users, registration and account support
  footballmanager/
    newLogic/                     live graphical football manager
    demo/                          frozen reference engine and replay viewer
  footballtextmanager/            separate text football mode
  basketballmanager/              separate basketball mode
  americanfootballmanager/        separate American football mode
```

`SportsManagerApplication` explicitly scans the shared package and each sports package. JPA repositories
and entities are also explicitly listed. Adding a football feature normally means working in
`footballmanager.newLogic`; extending `demo/` is prohibited by the repository instructions.

The live football product is:

| Layer | Location | Responsibility |
|---|---|---|
| Static UI | `src/main/resources/static/` | HTML shells, CSS and ES modules |
| HTTP API | `newLogic/controller/` | Authenticated JSON endpoints |
| Application services | `newLogic/service/` | Management rules and use cases |
| Domain model | `newLogic/model/` | JPA entities, enums and calendar types |
| Persistence | `newLogic/repository/` | Spring Data repositories and explicit queries |
| Clock/jobs | `newLogic/jobs/` | Game time and scheduled world work |
| Match engine | `newLogic/sim/` | Tick simulation, results and replay |
| World tools | `newLogic/util/` | Seeders, repair, reset, ratings and backfills |

### 1.2 Current source size

These counts were taken from the tree at `f8c492d` on 2026-10-07. They are orientation numbers, not
contracts.

| Area | Count |
|---|---:|
| Football controllers | 30 |
| Football services | 88 |
| Football model files | 147 |
| Football repositories | 51 |
| Match simulation Java files | 85 |
| Static JavaScript files under `static/js` | 47 |
| Test classes in `src/test/java` (files named `*Test.java`) | 224 |
| `@Test` annotations | 1,510 |

The static tree holds 86 `.js` files in total, the rest belonging to the text-football, basketball and
American-football modes and to the frozen demo viewer. The count above counts files under `static/js`,
including shared and legacy mode files, so it should not be compared directly with an old module count.

Two things worth knowing about these numbers. `DatabaseBackupService` lives in `commonmanager/service`,
not in `newLogic/service`, so the football service count does not include it. And three of the counts
above (controllers, services, model files) were already stale in the previous revision of this document;
they are now measured rather than inherited.

### 1.3 Season and scale terminology

A season is a number counted from 1 and contains twelve weeks. It is not a calendar year. Passing
`2024`, `2025` or `2026` as a season value is wrong even when a test happens to be self-consistent.

The world catalogue has 48 entries, including `OTHER`. A built country has five tiers with
`1 + 2 + 4 + 8 + 16 = 31` divisions and ten clubs per division: 310 clubs per country and roughly
14,880 clubs at full scale. National teams are separate from clubs. A club is identified by its league
competition in several queries because `PyramidBuilder` does not set `Team.type`.

`Team.reputation` is the 0–100 economy and club-strength scale. Club Elo is stored separately in
`eloRating`, `eloPreviousRating` and `eloDelta`. `Country.reputation` is a different country-rating
scale despite sharing the column name.

## 2. Application boot and runtime

### 2.1 Runtime

The application is Spring Boot 3.3.3 on Java 21. Development uses PostgreSQL `sokker_db`; tests use
the test profile and H2 where configured. The default development profile and port are defined in
`src/main/resources/application.properties` and `application-dev.properties`.

The normal shell entry point is:

```bash
./run-app.sh --app.open-browser=false
```

`BrowserLauncher` opens `/login.html` when the property is true. The JVM cannot distinguish an IDE
launch from a shell launch, so the IDE default remains suitable for the owner and every shell start
must opt out.

### 2.2 What runs at boot

Boot does not build the football world. There is no active caller for
`DatabaseInitializer.ensureBaselineDataOnStartup()`. Seeding, repair and backfill are admin actions.

There are still boot-time actions in the application:

| Component | Trigger | Effect |
|---|---|---|
| `SimReplayStore` | `@PostConstruct` | Loads the bounded replay cache from disk |
| `StartupInitializer` | `CommandLineRunner` | Ensures the configured owner account and shared team links |
| `DatabaseInitializer.sanitizeLegacySchemaOnStartup` | `ApplicationReadyEvent` | Applies the small legacy schema repairs required by the current model |
| `GameClockScheduler` | hourly `@Scheduled` method | Advances the clock only when scheduling is enabled |
| `BrowserLauncher` | application runner | Opens the browser when `app.open-browser` is true |

The three other sport modes also have application-ready initializers. A failure in a shared boot path
can therefore affect the whole application even when the football UI is the only mode being examined.

The important boundary is that boot does not call `initialize-db`, `seed-other-nations`, world repair,
cup drawing, national tournament drawing or a general backfill. `ensureBaselineDataOnStartup()` is
deliberately retained as a callable method for explicit flows, but has no startup caller.

### 2.3 Clock profiles

| Profile | Scheduler | Auto advance | Purpose |
|---|---|---|---|
| `dev` | disabled | disabled | Manual clock controls and repeatable development |
| `prod` | enabled | enabled | Hourly scheduled world |

The development database is PostgreSQL at `localhost:5432/sokker_db`, user `postgres`, password
`stojke`, unless overridden by the environment. Production reads datasource settings from environment
variables.

## 3. Authentication and request security

### 3.1 Login and token

`UserController` exposes:

| Request | Result |
|---|---|
| `POST /auth/login` | Authenticates by username or email and returns a JWT |
| `POST /auth/register` | Creates a pending registration request |
| `GET /auth/me` | Returns the signed-in manager and resolved football context |

The token is stateless and stored in `sessionStorage` under `token`. `auth.js` removes the old
`localStorage` token and redirects an expired session to the login page. There is no refresh-token or
server-side session flow.

### 3.2 Security rules

`SecurityConfig` is stateless and has method security enabled. The public allowlist contains static
assets, page shells, `/auth/**`, the country catalogue, server time and the public game-clock read.
The football data API is authenticated by default.

`/admin/**` requires `ADMIN`, `OWNER` or `DEV`. Other API routes require an authenticated user, with
method-level role checks where the operation has a narrower rule. The world-advance endpoints carry
role protection now that `@EnableMethodSecurity` is active; the dashboard's button visibility is only a
UI convenience and is not the authorization boundary.

`/admin/jobs` and `/admin/backups` inherit the `/admin/**` guard. `/api/jobs`, `/api/jobs/runs` and
`/api/jobs/run-due` are administrator-only by `@PreAuthorize("hasAnyRole('OWNER', 'DEV', 'ADMIN')")`
rather than by being unauthenticated: they return the game's machinery — every registered job, its
trigger, and the failure messages of runs that went wrong — which is an operations view rather than
something a club manager needs.

The non-football basketball and American-football static trees remain public because their current
clients do not send the shared JWT. Their API security is a separate concern.

### 3.3 Ownership link

The football manager now has a real `User.footballTeam` foreign key and `ClubOwnershipLinker` for
forward and reverse lookup, including legacy-row repair. The old `CTeam` and football `Team` id spaces
still overlap in several open paths. This is tracked as P0-20 and must not be treated as solved merely
because the new foreign key exists.

## 4. Login, lobby and dashboard

### 4.1 Page flow

```text
login.html
  ├─ register.html → pending registration
  └─ successful login → home.html
                         ├─ dashboard.html (graphical football)
                         ├─ tifo.html (text football)
                         ├─ americanfootballmanager/index.html
                         └─ basketballmanager/index.html
```

`home.html` only checks for a token and selects a mode. The football dashboard is a single document;
its content is replaced in `#main-content` by JavaScript. There is no server-side route per dashboard
tab and no browser URL routing for the in-dashboard pages.

### 4.2 Dashboard startup

`dashboard.js` loads `/auth/me`, stores the manager's club, country, league and role, applies the
admin visibility rules, wires the notification bell and starts the 30-second notification poll. It
then renders the club overview and loads its independent panels:

- important updates ticker;
- position, points, W-D-L and goal difference;
- next fixture, with competition, round, venue, home/away and team strength;
- recent results;
- season club milestones;
- season-flow and clock information, which for an administrator also carries the Advance Hour / Day / Week
  buttons.

The next fixture is ordered by season, week and day, then date. It opens the match preview from a
fixture id, so a not-yet-played fixture can be previewed even though no `Match` row exists yet.

### 4.3 Shared UI rules

`pages.js` injects shared dependencies into feature views. The important shared rules are:

- use `authFetch` for data requests;
- use the single `escapeHtml` implementation in `js/ui/escape.js`;
- check HTTP success before consuming response bodies;
- keep page-specific rendering inside its view module where possible;
- treat fixture ids and played-match ids as different concepts even though both are numeric.

The frontend uses HTML templates and event listeners rather than a framework. CSS is split between the
dashboard layout/components/overrides files and the football-specific `fm.css` styles.

## 5. Frontend architecture and routes

### 5.1 Entry modules

The football graphical path is centered on:

| File | Role |
|---|---|
| `dashboard.html` | Shell, top navigation, sidebar, notifications and main content host |
| `js/dashboard.js` | Authenticated dashboard, clock, ticker and admin database actions |
| `js/pages.js` | In-document router and dependency composition |
| `js/pages-renderers.js` | Shared table, fixture, player and empty/error renderers |
| `js/auth.js` | JWT storage and `authFetch` |
| `js/notifications.js` | Polling, unread badge, unread-only ticker and the arrival chime |
| `js/demo.js` | The dashboard's week/clock action handlers (`advanceWeekTest`, `advanceClockTest`) |
| `js/ui/escape.js` | The one HTML escaping helper |
| `js/ui/components.js` | `backButtonHtml` and other shared markup fragments |
| `js/pages/features/` | Club, match, academy, staff and training feature facades |
| `js/pages/views/` | Page-specific football views |

The router creates view factories for forum, messages, matches, players, formations, tactics, training,
medical, league, fixtures, country, international club cups, statistics, club, stadium and admin.

### 5.2 Current page names

The current `loadPage` switch contains these product paths:

| Area | Pages |
|---|---|
| Club | `firstTeam`, `juniors`, `medicalCenter`, `formations`, `tactics`, `tacticEditor`, `staff`, `finances`, `transfers`, `coaches`, `training`, `trainingSetup`, `trainingReports`, `profile`, `stadium` |
| Matches | `upcoming`, `results`, `schedule`, `fixtures`, `match`, `fixture`, `friendlies`, `leagueMatches` |
| Competition | `leagueTable`, `leagueSchedule`, `leagueTeam`, `cup`, `international`, `world`, `country`, `clubCup`, `countryCup`, `countryPlayoffs`, `nationalTournament`, `nationalTeam`, `u21Team` |
| Community | `forum`, `forumSection`, `forumTopic`, `messages`, `messageThread` |
| Reports | `playerStats`, `teamStats`, `topScorers`, `topAssists`, `analytics` |
| Identity/admin | `userProfile`, `publicUserProfile`, `admin` |

Some names are compatibility or partial paths. A route name existing in the switch does not prove that
the backend or a useful screen exists; the gaps are listed in §12.

### 5.3 World and country navigation

The World page calls `GET /countries/world`, ranks countries by country reputation and shows active vs
simulated state. A country row opens the country page. The country page has four tabs — General,
Calendar, National Team and U-21 — and the last two are rendered only for the side's selector, because a
permanently greyed-out tab reads as locked.

A **represented** country (no club pyramid) does not get the divisions page at all. `country-view.js`
routes it to `renderRepresentedCountry`, which shows the country's position and rating in the senior
ranking, and for each of its two sides the qualifying group it is drawn in with that group's whole table.
The previous text in this section said simulated countries "show the national-team record"; that was the
state at `6456cdb`'s predecessor and is no longer what the page does.

The World page now shows the three international club cups as rows in one competition table. Each row
shows the expected field size (Champions 48, Masters 96, Challenge 48) and opens `club-cup-view.js`,
which has tier tabs, group tables, results and knockout bracket data. The four national-team competitions
are listed from `/api/national-tournaments`; every one of those rows is clickable and opens the shared
national-tournament view, which states plainly when a competition has not been drawn. The status badge
carries either a fixture count and week, or "Not drawn yet".

### 5.4 Back navigation

`goBackSmart` pops an in-document history stack (`navHistoryStack`, bounded at 50 entries) and falls back
to a named target when the stack is empty. Two changes in this revision:

- `match-view.js` used to map the opening caller to a Back target through an allowlist and fell through
  to the league match list for anything else, so Back on a national-team tie landed in a league the
  manager was not in. An unrecognised caller now passes no target, which means "pop the stack"; the five
  named cases keep explicit targets because Back from a league table is expected to land on the table.
- `pages.js` used to pass an absent fallback straight into `loadPage(null)`, which renders nothing. A
  missing target now becomes `dashboard`.

The country page carries its own `data-nav-back="dashboard"` button in the header of each of its pages.

## 6. What the manager can do

### 6.1 Club and squad

The club area reads and updates the first team, lineup template, formations, tactics, stadium, staff,
finances, training, medical state, juniors and transfer activity. Club and league rows expose the
manager profile through the `User.footballTeam` link.

The current dashboard can show club milestones: top scorer, top assist, biggest win, heaviest loss,
attendance and **honours**. These are derived from persisted match and crowd data and may be empty in a new
world.

**Honours** (P2-TROPHY-1) are stored, not derived on read: `ClubHonour` rows written by
`HonourService.derive(season)` — league 1/2/3 → gold/silver/bronze from the final table, cup final winner
gold, final loser silver, third-place winner bronze, and **no bronze at all in a cup without a
third-place match**. `derive` runs inside `RankingPointsRebuildService` after every matchday batch, so the
table fills without anyone pressing anything. `HonourService.honoursOf` is the read side, the medals ride
on `LeagueMilestonesDTO.trophies` — the payload the Club page already reads — and `buildMilestoneBoardHtml`
draws a coloured medal with its competition and season. A club that has won nothing gets an empty list and
the sentence "No medals yet."; the league-wide milestone read deliberately has none.

The ground is built from the club profile's **Open Stadium View**: eight sections, each with a seating
type, seats to add, a roof over that section only, and its own ticket price beside a recommendation. Work
is **quoted first** — price and the weeks that stand holds nobody — and only a *Yes* spends. See §10.3.

### 6.2 Tactics and match influence

The tactical editor persists a `TeamTacticsProfile` and role rules. `TacticsRulesProvider` loads the
home club's profile and passes it through `SimMatchService` into the match orchestrator. Role positions
fall back to the player's own formation anchor when a tactic does not name that role.

`WE_HAVE_BALL` and `OPPONENT_HAS_BALL` are deliberately mirrored on save and read. `DefensiveShape`
supplies the defensive shape while the owner decision about separate mirrored rules remains open. This
is load-bearing behaviour and must not be changed as a cleanup.

The away side is still positioned using the home club's rules. That is the open P0-3 defect.

### 6.3 Training, medical and academy

Training has weekly setup and reports, player training, intensity choices, coaching staff effects,
fatigue/injury consequences and AI-club weekly training. Juniors have an academy/school path, scouting
and talent ranges; the manager-facing view intentionally narrows talent information through scouting.

Medical pages expose injuries and recovery. The national-team injury path remains insufficiently
verified for copied national-team player rows.

`TrainingJob` runs on day 4 at 10:00 and drives both the club squads and the squad-environment week. It was
always registered; what was missing was any evidence that the scheduler reaches it.
`TrainingJobFiresTest` now drives the real `JobRunner` with the real job list and asserts that at day 4
hour 10 the outcomes contain `key=training status=DONE` alongside day-opened, recovery, table-reconcile
and national-tournament-draw. It asserts *membership and no failures* rather than a count, because the
truth is five jobs at that position and a count assertion would pass for the wrong reason.
`JobTriggerCoverageTest` walks the real clock a week hour by hour and records which `(day, hour)` pairs the
runner is actually offered — the reasoning that "the clock steps the day and hour together, so 23:00 is
never asked" turned out to be wrong, and the test is kept because whether a job fires cannot be answered
by reading its day and hour alone.

### 6.4 Transfers and finance

The transfer system includes listing, interest, direct buy, offers, named-offer acceptance, objections,
loans, contracts, free agency, transfer windows and an AI auction path. The finance area includes a
weekly ledger, wages from player earnings, sponsorship/prize income, stadium and pitch costs, and FFP
bands.

The current product still lacks several competitive market mechanics: listing fee and sale tax rules,
anti-day-trading, work permits and a fully resolved seller choice path in all flows. Debt, interest,
bankruptcy and a board cash ceiling are also not implemented.

### 6.5 Community

The old `CommunityMessage` chat has been deleted. The current community surface is:

- Forum sections `TIFO` and `GENERAL`;
- topics and threaded posts;
- self-edit and self-delete with soft deletion;
- moderator/admin/owner edit and delete of other posts;
- forum writing bans with expiry and reason;
- private message threads with subject on the first message and body-only replies;
- notifications stored in the database and polled every 30 seconds;
- registration approvals on the Admin tab.

The page is reached from the Community menu button. There is no separate events tab and no old chat
migration.

#### Message list previews

Every thread row now carries `lastMessage`, the newest non-deleted body in that conversation, truncated
to 140 characters on a word boundary in `messages-view.js`. It is fetched with **one query per page**, not
one per row: `DirectMessageRepository.findNewestPerThread` is a `SELECT DISTINCT ON (m.thread_id)`
ordered by thread, then `created_at DESC, id DESC`.

That query is PostgreSQL-only. `MessageService.newestBodies` catches the failure and returns an empty map,
so on H2 — which is what the test profile uses — the list renders without previews. `MessageServiceTest`
therefore verifies the fallback, and says so in its own assertion; the `DISTINCT ON` path was verified
against the owner's database instead. A thread whose only message was deleted carries no preview rather
than an empty one, so the view can omit the line.

#### Notification bell

`notifications.js` is a read-only ticker. `buildDropdownHtml` filters to `row.read !== true`, and the
header count is the number of rows shown rather than the payload's count, so the two cannot disagree.
Rows stay in the database; the ticker is a view over the unread set, not a delete.

`consumeNotification(id, row)` serves both ways of reading a notification — clicking the row, and
clicking "Open the topic" / "Open the conversation" — which used to be handled differently, so a
notification could be acted on for ever and still sit in the ticker. It removes the row and decrements
the badge and the red dot **before** the `POST /notifications/{id}/read`, and the next poll corrects it
if that request failed.

The chime is synthesised with the Web Audio API (two notes, a fifth apart, the second quieter and later).
It rings only when the unread count *increases*, and it resumes one `AudioContext` for the page rather
than abandoning a suspended one: the previous version closed the context and returned whenever
`context.state === 'suspended'`, which is true on every ring in every current browser, inside a `try`
with an empty `catch` — a silent permanent failure. `unlockAudioOnFirstGesture` attaches `pointerdown`
and `keydown` listeners once and never removes them, so one click anywhere unlocks audio for the rest of
the session.

## 7. Clock, calendar and jobs

### 7.1 Game time

`GameClockService` advances hour, day, week and season. The calendar is a twelve-week season, with
season day values counted from 1. `LeagueSlotSchedule` maps the league rounds to calendar slots; a
ten-club double round robin has 18 rounds, and a week has **four** match moments — day 1, 3, 5 and 7
(owner rule 2026-10-06) — of which two carry the league rounds and the other two are friendly
**opportunities**, since a friendly is something a club asks for rather than something it is handed.

> This section previously said *two* league slots per week. That was the 2-slot week, and it had
> propagated into `SeasonCalendarTest`, which was red on three tests for exactly this reason — closed
> 2026-10-08.

The four ordinary matchday jobs currently use these slots:

| Job key | Competition | Day | Hour |
|---|---|---:|---:|
| `matchday-international` | international warm-up | 1 | 20 |
| `matchday-league-a` | league | 3 | 19 |
| `matchday-cup` | cup | 5 | 18 |
| `matchday-league-b` | league | 7 | 16 |

National qualifying has its own week-6 schedule on days 2–6. The national tournament is scheduled in
week 12. The owner has confirmed that the week-6 day-1 international round remains as a warm-up and is
not compulsory for the national tournament.

### 7.2 Job contract

`DayJob` is selected by `(season, week, day, hour)`. `JobRunner` records `JobRun` state and prevents
the same logical job key from being applied repeatedly. Matchday jobs select unplayed fixtures for the
current calendar slot and hand them to `AsyncSimulationRunner`.

Registered jobs cover matchdays (league, cup, international, four friendly slots), day opening, finance,
training, recovery, league-table reconciliation, cup draws, international club cups, national draws and
matchdays, week rollover and season rollover. `TrainingJob` runs on **day 4 at 10:00**, ahead of the
day-3 league matchday's reconciliation on the same day; `RecoveryJob` is every day at 06:00;
`FinanceJob` is day 2 at 10:00; the two `LeagueTableReconcileJob` instances run on day 4 and — late, at
22:00 on day 7 itself — after their matchday.

### 7.3 Job status

`JobStatusService.report()` is the reader for the `job_run` rows that already existed and previously had
none. It backs `GET /admin/jobs` and the Admin **Jobs** tab, and it reports per job: key, trigger as a
sentence, last status, last run timestamp, last message, next trigger with hours-to-go, and the run and
failure counts for the current season. A `failing` flag and a total `failureCount` drive the warning
banner.

Two design points in it are load-bearing. `nextTrigger` is **walked forward hour by hour** for up to a
week rather than computed by subtraction, because a job's day and hour do not determine whether its
trigger is reached — the hours the clock actually offers do. And `runsThisSeason` /
`failuresThisSeason` come from the same pass as the last status, so the panel cannot disagree with
itself. The whole `job_run` table is read into memory per call (`runs.findAll()`); at the current
volume that is fine, and it is listed in §12 as a scale item.

### 7.4 Clock controls

The dashboard exposes development controls for advancing an hour, day or week. Advancing runs whatever
is due at each hour step, so the advance response carries the jobs that ran. `POST /api/jobs/run-due`
runs due work without moving the clock and is what the Jobs tab's **Run due jobs now** button calls.

`GameClockService.advanceHours` now accumulates outcomes across every step it takes rather than returning
only the last hour's result, and attaches `advance: {hoursAdvanced, ran, skipped, failed, jobsRan,
jobsFailed}` to the response. `advanceDay()` is `advanceHours(24)` and `advanceWeek()` is
`advanceHours(168)`, so no trigger is stepped over in either case.

The season rollover and job-run paths remain high-risk code. The open board items cover recovery,
duplicate application, slot expansion and owner authorization decisions. Do not infer correctness from
a green build alone; a clock task is complete only after observing the resulting database state.

## 8. World building and competitions

### 8.1 Admin page

The Admin page is two tabs, **Tools** and **Jobs**, switched with `hidden` panels rather than a CSS
class — a `display:none` panel still fetches, and Jobs would have been reading the server while invisible.
It loads only when its tab is opened. The exact button set is in `pages/views/admin-view.js`.

#### Tools tab

| Panel | UI action | Backend effect |
|---|---|---|
| Database controls | Reset DB | Empties every table except the keep-list (§8.2) |
| Database controls | Initialize DB | Builds the Serbian football structure and its fixtures/players |
| Database controls | Save Default Tactics | Stores the current tactical-editor profile as the post-reset default |
| Database backup | Create backup | `pg_dump` to `yyyy-MM-dd-HH-mm-ss.dump` (§8.8) |
| Database backup | Restore (per row) | Replaces the database with one dump; restart required |
| World integrity | Repair world | Repairs the country catalogue and national-team baseline |
| World integrity | Re-seed national teams | Tops up missing squads and renames legacy senior sides |
| World integrity | Seed other nations | Builds static pyramids for countries that are not activated; no players or matches |
| World integrity | Re-draw the cup | Draws missing domestic cup rounds |
| World integrity | Repair international cups | Creates the 15 international club cup rows and fills missing simulated-country structures |
| World integrity | Re-draw international cups | Runs `InternationalClubCupJob` for the active season and week |
| World integrity | Re-draw national competitions | `NationalTournamentWorldService.forceRedraw()` (§8.6) |
| Activate a country | Activate / Top up | Builds a five-tier pyramid: 31 divisions, 310 clubs, ~7,750 players |
| Accounts | Repair club links | Fills the `User.footballTeam` foreign key on pre-existing accounts |
| Applications | Approve / Reject | Registration queue; a rejection stores a note on the request |

The page also opens with a world-integrity readout (countries vs expected, national sides, sides with a
squad, legacy rows) so an admin can see whether the world is whole before touching it. Each repair
confirms first, then reports the server's whole payload — including "nothing to do", which is a normal
outcome. Long database operations (reset, initialize, seed-other-nations) go through
`AdminDatabaseAsyncService` and are polled at `/admin/database-job/status`; the operation remains
synchronous inside some service classes despite the `Async` naming.

`handleTool` also carries handlers for `national-tournaments`, `national-ratings-reset` and
`national-ratings-violations` that **no button currently renders**. The first two have working
endpoints (`POST /admin/national-tournaments`, `POST /admin/national-ratings/reset`); the third calls
`/admin/national-ratings/violations`, which does not exist — the controller's read is
`/national-ratings/offenders`. Treat these three as unreachable code.

#### Jobs tab

`GET /admin/jobs` renders one row per registered job: key, trigger, last status with timestamp and
message, next trigger with hours-to-go, and run/failure counts for the season. A failed row is tinted and
a banner appears while anything has ever failed, because a failed job is retried on the next hour and a
permanently broken one otherwise looks identical to a healthy one forever. **Run due jobs now** posts to
`/api/jobs/run-due` and reports what ran, what was skipped and what failed.

The table uses `fm-squad` inside an `fm-table-wrap`. The previous class, `fm-table`, is defined nowhere in
the stylesheet — no padding, no header styling, no borders — which is what "zbrkano" was. Below 640px the
trigger and next-trigger columns are dropped; the job's name, last outcome and failure count remain,
because the outcome is why the panel exists.

### 8.2 Reset DB

`ResetService.resetDatabase()` is a **keep-list**, not a delete-list. The previous version enumerated 39
tables to truncate against a schema of 125, so 86 tables were never touched — the whole forum, private
messages, notifications, transfers, scouting, finance — and the owner was right that pressing Reset DB
left his forum topics in place. A keep-list is a promise about the three things that must survive; a new
table is cleared by default, which is the correct default for a button labelled "delete everything".

Preserved: `app_user`, `user`, `tactics`, `formation`, `formation_positions`, and inside `app_user` the
two accounts `velibor@example.com` and `kecko@example.com`. Those are named rather than "the first row",
because a rule that preserves whatever happens to be id 1 deletes whichever manager registered first.
`team_tactics_profile` is deliberately **not** on the list — it carries a foreign key to `team`, so
truncating the teams cascades it away regardless, and it is preserved by the snapshot/restore around the
call instead.

#### Why the emptying suspends referential integrity

The schema has genuine foreign-key cycles — `cteam ↔ cscountry`, `new_logic_lineup ↔ new_logic_match`,
`country ↔ team` — and **no ordering of row-by-row deletes satisfies an immediate foreign key around a
cycle**. `deleteWithIntegritySuspended` therefore sets `session_replication_role = 'replica'` (or
`REFERENTIAL_INTEGRITY FALSE` on H2), empties the tables, and restores the setting in a `finally`. The
children-first ordering from `information_schema` is kept, but only as a nicety: with constraints
suspended the deletes succeed in any order.

Three implementations failed before this one, and they are worth keeping on the record because each failed
in a way that reading the code does not show:

- a children-first ordering from `information_schema` handled an acyclic graph, and on a cycle ran out of
  safe candidates and fell back to catalogue order — arbitrary, and it deleted a parent while its children
  still pointed at it;
- the parameter is `session_replication_role`, not `session_replica_role` — the typo shipped
  "unrecognized configuration parameter" and took the button out entirely;
- one `TRUNCATE ... CASCADE` runs, clears the cycles, and is **still wrong**, because `CASCADE` follows
  references in both directions and eight tables reference `app_user`. Measured on the owner's schema,
  truncating `nl_notification` alone took `app_user` from 8 rows to 5.

The suspension is verified rather than assumed: after the `SET`, the session is asked what it reports,
because a pooled connection handed back between the `SET` and the `DELETE`s makes the statement return
without taking effect.

**Neither H2 nor the test schema can see any of this.** `ResetServiceKeepsOnlyAccountsAndTacticsTest` is
6/6 on H2 and stays so, and restoring the inverted comparison also passes there, because H2 carries
neither those basketball and legacy foreign keys nor `CASCADE`. `ResetServiceOnRealPostgresTest` runs the
real thing against a real copy of the world and refuses to run against any database whose name lacks a
safety marker. This is the second time in this feature that a green H2 suite hid a defect the owner's
database would have raised.

### 8.3 Pyramid and fixtures

`PyramidBuilder` creates five tiers, 31 divisions and ten clubs per division. The active-country path
creates squads and fixtures. The simulated-country path creates static clubs, ratings and tables but
does not create players or fixtures; squads can be created lazily when an international club cup needs
them.

The builder no longer asks the database whether a club exists once per club. It used to call
`findAllByNameIgnoreCase` per club, whose `LOWER(name) = LOWER(?)` no functional index can serve: measured
at 10 ms a call against a 14,880-club table, about 149 seconds for one world, and the cost grows with the
table. `existingClubNamesIn(country)` now runs `findNamesForCountry` **once per country** (a projection,
not entities) and the existence check becomes a set membership test — 14,880 round trips become 48. A
name that *is* in the set still goes through `findAllByNameIgnoreCase`, because the re-seeding path has
to load the row anyway, so the saving is largest exactly where it matters: a fresh world.
`PyramidBuilderQueryCountTest` asserts the query count rather than the result, because every behavioural
test for this builder passed while it took minutes.

`SeasonService.ensureDoubleRoundRobinSchedule` is the single league schedule generator. It is
idempotent, uses competition entries, writes first and return legs, and reads the calendar slot for
each round. It does not invent a date when no slot exists.

Promotion and relegation are represented by the country-wide ladder and playoff services. The calendar
and scheduled rollover paths are still under active repair, so a built ladder is not evidence that an
entire season rollover has been observed end to end.

### 8.4 Domestic cup

The domestic cup is a national competition represented by `Competition` plus `MatchFixture.roundNumber`;
there is no separate `CupRound` entity. The domestic draw is country-scoped: `CupFixtureSeeder` iterates
every national cup for both repair and scheduled draw paths, and each cup ranks clubs from its own country.
International cup rows are excluded by competition scope.

The draw is deterministic from ranked entrants and does not use a coin flip to settle an unresolved
winner. A cup field below the normal group threshold now has a small-field knockout path, but the
two-club edge decision remains open.

#### Domestic cup draw job

`CupDrawJob` (`cup-draw`) is registered for every week at **day 2, 08:00**, before the domestic cup
tie on day 5. It calls `CupFixtureSeeder.drawRoundForWeek(currentWeek)` for every country cup. The
current round is drawn only when its entrants are known; a later round waits until the previous round
has been played. Existing ties are left untouched, so a missed job can safely be run again.

### 8.5 International club cups

There are three named international club cups across five tiers: Champions, Masters and Challenge.
The backend creates 15 competition rows, qualifies clubs from finished domestic tables, gives simulated
entrants lazy squads, draws group stages, records group tables and advances knockout rounds.

`InternationalClubCupJob` is a live job, hour 8, order 30, on **any** week; it decides what to do from the
week number. The group draw runs on **week 12, day 7** — the last day of the season — qualifying off that
season's finished tables and creating **next** season's competition (owner, 2026-10-08). It used to run on
week 1 and read the season before, which meant the field was decided before the season that decides it
finished, and a promotion could leave a club in a cup that is no longer its division's. Weeks 7-10 of the
cup season are the knockouts. `ClubCupController` exposes:

```text
GET /club-cups
GET /club-cups/{key}?tier=N
```

The World page links into `club-cup-view.js`, which renders each tier's groups, results and bracket. The
three World rows report complete 48, 96 and 48 fields once a finished season exists. Small fields of 2–7 entrants now use a knockout
path; two entrants play a direct final and odd fields carry a bye. The repair action durably creates
missing international rows before filling static simulated-country tables, and the
draw-night job repeats that durable repair boundary before drawing. The domestic cup draw is separately country-scoped
and no longer selects one primary cup globally.

The repair boundary was verified against PostgreSQL on 2026-10-06: all 15 competition rows existed, all
three tier-1 cup endpoints returned HTTP 200, and the World payload exposed competition IDs for every tier.
The simulated-world seed continues separately and must finish before its qualifying counts are treated as
the final live field sizes.

### 8.6 National teams and tournaments

National teams have senior and U-21 levels, elections/appointments and squad management. The newer
tournament model has four competition rows:

- World Cup Qualifiers;
- World Cup;
- U-21 World Cup Qualifiers;
- U-21 World Cup.

The intended schedule is 48 countries in eight groups of six, week-6 qualifying across days 2–6, and
week-12 knockouts. Pots of eight, worse-rated hosts and stored group tie-break values are implemented
in `NationalTournamentSeeder`. `NationalMatchdayJob`, `NationalTournamentDrawJob`, ratings and the
controller are present.

The tournament champion path includes the final feed-forward round. Every World-page row for a
national-team competition is clickable and opens `national-tournament-view.js`, which renders groups,
standings, results and the knockout bracket for both levels; an undrawn competition says so on the page
rather than refusing to open. `NationalTournamentController` backs that view.

**The qualifying draw is at season start, not the week before.** `NationalTournamentDrawJob` is registered
`ANY_WEEK`, `ANY_DAY`, hour 0, order 20, and branches: in **week 1 on day 1** it runs
`ensureGroupStage` for both levels, and in week 12 it runs `ensureKnockouts`. It used to fire on week 6
day 1, which is after the fact for anyone watching the calendar — the groups and fixtures are now known
from week 1. Running it on every day is what lets a knockout be drawn a round at a time, since the
quarter-finals do not exist until the round of sixteen has been played; the second and later runs are
no-ops.

`forceRedraw()` is the deterministic admin re-draw: it creates any missing competition, **refuses** if any
qualifying tie for the active season has been played, then clears every unplayed qualifying and
tournament fixture for that season and draws fresh groups for both levels. The refusal is the point rather
than a limitation — a played fixture carries a group code, and re-drawing would leave that result sitting
on a table its two nations are no longer in. There is no "redraw but keep the results" that is also
consistent. Note that this is *not* a run of `NationalTournamentDrawJob`, and it does not leave existing
fixtures alone; the previous text in this document said it did both.

#### Senior side naming

A senior side is called after its country and nothing else — `Germany`, not `Germany National Team`. The
U-21 side keeps its suffix (`Germany U-21`), because `Germany` alone would be ambiguous there.
`renameSeniorSides` is a standalone idempotent entry point rather than a step inside `seedIfMissing`,
which had left a correct, compiled rename reachable only by pressing Re-seed; `WorldRepairService` calls
it on `national-teams` and reports the count as `renamedSeniorSides`. It only rewrites a name that is
exactly the country name plus the old suffix, so a hand-renamed side is left alone.

#### Squads hold real club players

`NationalTeamSeeder.squadsFor` used to stop at "a squad exists, so do not draw another", which could not
tell a real squad from a generated one. Since the sides are seeded before the pyramid exists,
`squadsFor` found an empty eligible list and took the bot fallback; the pyramid then created thousands of
real players and the idempotence guard made the generated ones permanent. Serbia fielded `N. SRB-GK01` at
82 while Zoran Zivadinovic sat in the pool at 94 — 2,400 generated national players in total.

The fix has four parts and any one of them alone leaves the defect in place:

- `BotSquadGenerator.isGenerated(Player)` recognises its own output from a pattern built from the same
  prefix list and the same `Position` values the generator uses, so the two cannot drift. There is no flag
  on the row, so the name is the record.
- generated players are dropped and real ones drawn, sorted on the stored `rating` rather than the display
  composite, and the squad is **topped up** rather than rebuilt so a selector's existing call-up survives;
- `sourcePlayerId` is stamped on every copy, which it never was — only `NationalTeamService.addToSquad` set
  it, and it is the column that keeps a called-up player out of the pool he was drawn from, so fixing the
  rest alone would have listed the same 25 players twice;
- `clubsIn(country)` no longer memoises an **empty** result. It memoises on an instance field of a
  singleton bean, the sides are seeded before any club exists, so every country cached "no clubs" and a
  cached empty list outlives the pyramid that was going to fill it. Repair world would have reported
  success and replaced nothing.

**Not done where a country has no clubs.** The 47 club-less nations keep generated sides: deleting a
side's only XI to leave it empty is worse than a squad that reads as generated, and an empty national side
cannot be drawn against — which is what stopped the internationals drawing at all.

#### The national pool is one query

`NationalTeamService.describe` used to call `countryPlayers` twice — once for the rows, once for the count
— and each call loaded **every club in the world**, filtered to one country in Java, then queried each of
that country's clubs. On the country page that was two full club reads and roughly 620 player queries per
load at 7,730 players in one country. It is now `PlayerRepository.findByTeamCountryId`, one indexed join
on `team.country_id`. `availablePlayers` computes the pool once and `poolRows` takes the first 80, so
`poolSize` and the rows come from the same list and cannot disagree. Unsorted on purpose: the caller
sorts by rating then name, and that comparator is the definition of "best first" for the pool.

### 8.7 Country/world integrity

`WorldIntegrityService` reports catalogue and squad state. `WorldRepairService` performs explicit repair
and returns the after-state. `WorldCatalogSeeder` is idempotent; simulated-world seeding is guarded by
existing competitions.

The remaining idempotence concern in this area is now stated precisely rather than open-ended. `squadsFor`
is idempotent for club-backed countries: it recognises its own generated output, tops up rather than
rebuilds, and does not cache an empty club list. What it still does not do is re-draw a squad that is
already entirely real, so the repair changes nothing there by design — verify that in the database rather
than from the repair's exit code.

### 8.8 Database backup and restore

`DatabaseBackupService` (in `commonmanager/service`, not `newLogic`) dumps the whole database with
`pg_dump --format=custom` to `backups/yyyy-MM-dd-HH-mm-ss.dump` and lists those files newest first. The
directory is `app.backup.dir` (default `backups`, git-ignored).

Restore is the feature; the order of operations is what makes it safe. `pg_restore --list` reads the
archive's table of contents **without touching the database**, so a truncated or foreign file fails with
the world intact. Only then is the schema dropped — as one `drop schema if exists public cascade; create
schema public` rather than `pg_restore --clean`, which drops objects one at a time in the archive's own
order and can stop halfway through a dependency chain, leaving half the old world and half the new one.
The response carries a note that the application must be restarted: the process that just replaced the
database still holds a connection pool and a persistence context built against the old one.

**No shell, ever.** Every command is a `ProcessBuilder` list, so a filename from a request cannot become a
command. The password goes in `PGPASSWORD`, not an argument, so it does not appear in `ps` output to
every other process on the machine. A restore name is validated three times over — the
`\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}\.dump` shape, no `..`, and the resolved path still inside the
directory. `stdin` is not inherited, because an interactive `dropdb` asking "force?" on the console hangs
the caller for ever.

**The tools are resolved, not trusted.** `pg_dump` refuses to read a newer server, and here they do not
match: the server is Postgres.app 18.4 while the first `pg_dump` on `PATH` is Homebrew 16. Anything new
shelling out to these tools needs the same treatment — read the server's major version over JDBC (the
driver is already there) and take the first client new enough to read it. `app.backup.pg-tools` overrides
the search.

### 8.9 Ranking points — one number, wired and rebuilt after every matchday

`RankingPointsEngine` (`service/RankingPointsEngine.java`) is the owner's ranking system. It is **pure
arithmetic** — no Spring, no repository, no clock — which is what makes it testable row by row.

**It is not the Elo that already exists, and the difference is the whole point.** `RatingEngine`
weights a result by the rating gap, so beating a strong side moves a club more than beating a weak one.
The owner rejected that in one line: *"snaga tima moze da utice na projekciju rezultata ali ne i na
rejting poene"* — a team's strength may move the forecast, never the points. `RankingPointsEngine`
therefore has **no gap term**, and a test asserts that the ladder is a function of exactly two numbers.
`RatingEngine.clubK` has had its gap term removed; the two-argument overload ignores both arguments.

```
displayed = 1500 + Σ ( seasonPoints × windowWeight )      window 1.00 / 0.75 / 0.50 / 0.25
expectedMargin = expectedGoals(own) − expectedGoals(them)  expected to win at ≥ 2, draw inside 1
```

The rule in one sentence: **staying inside the outcome you were expected to achieve is worth nothing,
crossing it is worth a lot, and the size of the crossing is graded** — ±20 / ±30 / ±40 / ±50, capped.
Winning short of the forecast margin and losing short of it are both worth **0**, not a penalty, because
"a win is still a win" and "a loss is still a loss".

Per-match value: league 1.00 · national cup 1.25 · international club cup 1.50 · qualifying 1.20 ·
World Cup 2.00 · friendly 0.30. Division weight: tier 1–5 at 1.00 / 0.85 / 0.70 / 0.55 / 0.40, and a
national team has no tier. **This is why the totals are decimals** — `40 × 0.70 = 28.0`.

`CompetitionType` and `CompetitionScope` alone cannot tell a continental club cup from a World Cup
qualifier; `teamType` can, and a test pins that.

**State: wired.** `ClubSeasonRankingPoints` and `CountrySeasonRankingPoints` are the per-season ledger;
`ClubRankingPointsService` and `NationalRankingPointsService` replay the played matches into them;
`AchievementBonusService` adds qualification, phase and trophy bonuses in the same pass. All four are
driven by **`RankingPointsRebuildService.rebuild(season)`**, called by `AsyncSimulationRunner` **once per
matchday batch** — the owner's decision, 2026-10-08. Until that call existed the services had no caller at
all: the ranking lists sorted an empty ledger.

**Ties are settled by a stored coin.** `RankingTieBreakSeed` (`ranking_tie_break_seed`) holds one seed per
ladder — the world ladder, and one per country for its club ladder — written the first time the ladder needs
it and read from then on. `RankingTieBreakService.coin(seed, id)` mixes the stored seed with the id, so two
clubs level on points get **two distinct positions** in a **stable** order. It was previously a shared
rank with the tie broken alphabetically, which is an arbitrary rule presented as a sporting result. The
pattern is the same one `NationalGroupTieBreak` uses for a group table, and for the same reason: a coin
re-rolled per read is a table that reorders itself while nobody is watching.

### 8.9 Loans

`LoanService` owns the rules and the controller owns none of them, so every caller gets them: under 24
(younger than 24), domestic only, lender strictly above borrower in tier, bottom-tier clubs cannot lend,
one loan per player at a time.

**Room is checked when the borrower accepts, not when the offer is made.** An offer costs the lending club
nothing — the borrower may still sell somebody before accepting — so `offer` deliberately does no room
check and `activate` calls `requireRoomAt`. A loan is `AGREED` until then, and the rows say so:

| Status | Lending club | Borrowing club |
|---|---|---|
| `AGREED` | *waiting for them to take him in* | **Take him in** (`POST /loans/{id}/activate`) |
| `ACTIVE` | Request return | Send him back |
| notice outstanding | Accept return | Accept return |

The status decides the action and the side only refines it. It used to branch on the **side** first, which
meant every borrowing row fell through to *"Send him back"* — refused by the service with
`LOAN_NOT_ACTIVE` — and the one button that starts the loan was on no row at all.

**Termination is symmetrical, never immediate.** Seven days' notice unless the other club agrees; the
world ticks weekly, so a notice raised in week N closes at the end of week N+1. A recall that took effect at
once would be the *borrowing* club's problem — it has built the week around a player who is no longer
there, with no matchday left to replace him.

**A loan tells you it happened.** Four moments notify both clubs where both are affected — offered,
activated (arrived / left), termination requested, and closed, including the weekly sweep that ends loans
nobody pressed anything for — as `LOAN_PROPOSED` and `LOAN_MOVED`. Before this, a player arrived silently
and a manager found out by opening the loans screen.

#### Two things that cost a real outage, both about notifications

**A CHECK constraint pins the kinds, and nothing maintained it.** `nl_notification.kind` had a constraint
listing the four kinds that existed when the table was created. `ddl-auto=update` widens a column and
**never rewrites a CHECK**, so a new `NotificationKind` compiled, passed every test — the test database is
built from the entities and has no constraint — and then failed on the first real write.
`ResetService.alignNotificationKindConstraint()` rebuilds it from `NotificationKind.values()` at boot, so
adding a kind is a one-line change and the list cannot drift from the enum.

**`NotificationService.notify` runs in `REQUIRES_NEW`, and that is the whole point.** It catches its
exceptions and documents itself as unable to fail its caller — and it *was* failing its caller, because a
rejected statement poisons the persistence context and the caller's own transaction then dies at flush with
`HHH000099: null id in Notification entry`. Taking a player in on loan succeeded and then answered 500
because a courtesy row could not be written.

## 9. Match simulation and persistence

#### A `ReferenceError` in a template string needs an engine, not a lint

The national warm-up panel was written into `buildGeneralTab`, which destructures only
`sortedLeagues, senior, u21` out of its context, and it referenced five names that do not exist there.
**Every tab of the country page** threw `ReferenceError: tab is not defined`.

Four static guards on that file were green throughout: the endpoint strings were present, the copy was
present, the reads were gated on the two national-team tabs, and `node --check` passed — it **parses**, and
the name was only undefined at run time.

`CountryPageRendersWithoutReferenceErrorTest` loads the real `country-view.js` in Node with the fetch layer
stubbed and drives the real `loadCountryPage` over all six tabs. No browser, no running application, and it
reproduces the owner's exact message. `CountryPageRendersTest` (Chromium, real login) remains the better
check for anything in the fetch layer, and is skipped in CI for want of infrastructure — so the Node one is
the half that always runs.

The rule this leaves behind: **the data a builder function uses has to arrive in its context.** Closing over
the caller's variables is how a panel ends up rendering inside a function that never had them.

#### Reading a lazy entity outside a transaction is a live defect, not a style note

`POST /simulation/week/advance` returned **500 and did nothing** until 2026-10-08, because the controller
method has no `@Transactional` and read `userTeam.getCompetition().getName()` — a lazy proxy with no
session. Two hops of laziness for one string.

The rule this leaves behind: **ask a service for the value, not for the entity.** `SeasonService`
exposes `competitionIdOf(teamId)` and `competitionNameOf(teamId)`, which read inside a read-only
transaction. `SeasonServiceCompetitionNameReadTest` guards the one method that had no transaction around
it — scoped deliberately, because `prepareCurrentRound` *is* transactional and its lazy read is correct.

### 9.1 One live fixture path

`SimMatchService` is the production fixture producer. `ProposalEngineIsTheOnlyFixtureProducerTest`
guards the intended allowlist. `MatchOrchestrator` coordinates a tick pipeline and delegates movement,
ball physics, tactical intent, duels, execution quality, action execution, restarts, substitutions,
discipline, VAR, offside, injuries and penalties to focused components.

The simulation is deterministic per fixture seed. A fixture id seeds `SimulationRandom`, which makes a
fixture reproducible and prevents a league result from being silently re-rolled. This is a development
property and the reason calibration/replay comparisons are possible.

### 9.2 Engine capabilities

The current engine includes:

- spatial tick simulation and a match clock with stoppage time;
- velocity-based ball physics, spin, goal-plane and out-of-bounds handling;
- movement, passing, dribbling, duels, interceptions and threat overrides;
- goalkeeper positioning, saves and one-on-one behaviour;
- offside, VAR, fouls, cards, corners, throw-ins, goal kicks and free kicks;
- fatigue and eight injury types;
- penalty kicks with taker selection and goalkeeper read;
- up to five substitutions across three windows, including automatic injury/fatigue substitutions;
- player ratings, possession chains, pass-failure taxonomy, duels and restart statistics;
- file-backed bounded replays and a 2D/3D browser viewer.

### 9.3 Persistence after a match

The normal simulation path writes a finished `Match`, links it back to its `MatchFixture`, writes team
and player match statistics, career totals and zone load, updates the league table and produces replay
data. Manager result-reveal flags can hide a result until the manager opens it.

The frontend match view supports:

- preview for an upcoming fixture;
- lineups;
- match statistics;
- goals and event details;
- replay link;
- ZOX pre-match and post-match views;
- team/player navigation from the match.

The Back button's target is derived from the caller (§5.4). A match opened from a surface the view does
not know goes back through the history stack instead of to the league match list.

`demo/service/ui/proposal` is a viewer/reference asset used by the replay link. It is not the live
match engine and must not be extended as product logic.

## 10. Management systems

### 10.1 Players and squads

Players have skills, position, preferred foot, age, talent/ability information, form, morale, contracts,
earnings, injuries and training state. Player pages show match history, rating summaries, transfer
actions and links back to the owning club.

Player retirement exists in the service layer and is part of the current management model. Youth
development, hidden talent ranges and scouting are separate from current ability display.

A national-squad row is a **copy** of a club player, not a move: the player keeps playing for the club and
the national row is a separate record keyed by `sourcePlayerId` (§8.6). The country page squad table shows
the club each national player plays for.

`PlayerRatingBackfill.backfill()` no longer calls `findAll()`. It read the entire player table into one
`List<Player>` plus a second list of the rows that moved; a loaded entity runs about three times its
287-byte stored tuple, so that was a few hundred MB of heap in a single method and it grew with the table
while the answer did not. It now pages by id in batches of 500 and writes each batch in its own
`requiresNew` transaction: bounded peak memory, no transaction held open across the table, and a partial
failure keeps the batches already committed rather than rolling back the world's ratings. Paging by id is
safe because only ratings change — never the row count, never the ids.

### 10.2 Economy and board

`WeeklyFinanceService` settles club ledgers for wages and income categories. Stadium construction,
maintenance, sponsorship/prize income, attendance and FFP bands feed the club view. `BoardExpectationService`
and morale provide a partial meta-layer.

There is still no complete failure state: board trust can be calculated and a sacking review can be
reported, but the manager cannot yet be removed through a full end-to-end sacking flow. Debt,
bankruptcy and retirement consequences remain product gaps.

### 10.3 Staff, stadium and supporters

Staff directory and sponsor data are exposed from the club management area. Supporter
mood/expectation logic exists in the service layer; the board tracks remaining work where the value is
not yet fully connected to a visible management consequence.

#### The ground is eight sections, not one number

`StadiumSection` is the build-out model (owner, 2026-10-08): four sides and four corners, each with its
own seating type (`STANDING`, `BENCHES`, `SEATS`, `HEATED`), its own capacity, its own optional **roof**, its
own ticket price and the recommended one, and the season/week it reopens after work.

- `StadiumSectionService` owns it. `quote` answers **price and weeks closed** and spends nothing;
  `build` spends and closes that one section; `setPrice` prices one section.
- `Stadium.capacity` is **recomputed as the sum of the eight**, `seatQuality` as the capacity-weighted
  comfort of what was built, `roof` as "all eight hold seats and are covered". Those columns are derived,
  so nothing else writes them — the whole-ground `expand` / `roof` / `seats` actions were removed rather
  than left dormant.
- Laying out the eight is the migration, done lazily on first read: a legacy ground keeps every seat,
  divided across the eight. Nothing runs at boot.
- `AdmissionService.priceLadder` is the ground's sellable blocks, cheapest first, taken from the club's own
  sections; a section nobody priced sells at the ground's standard price. The three derived ticket tiers
  remain the fallback for a ground that was never laid out. `demandPrice` (the capacity-weighted average)
  is what `AttendanceService` runs its price elasticity against, so attendance moves with the eight prices.
- `StadiumBuildService` is now only the shared `costPerSeat` basis and the free colouring.
- The page is reached from the club profile ("Open Stadium View"): eight rows, each with its closure week,
  its price and its recommendation, then quote → yes/no.

There is **no demolition**: a section built as a terrace cannot be rebuilt as heated seats, and the quote
says so.

## 11. API surface

The following groups are the routes used by the football UI. This is a capability map, not a promise
that every route has a current screen.

| Controller/group | Main prefix | Main responsibilities |
|---|---|---|
| `UserController` | `/auth` | login, registration, current manager |
| `AdminController`, admin user controller | `/admin` | database actions, approvals, roles, bans, repair, job status, backups |
| `APIController` | `/api` | clock, jobs (`/jobs`, `/jobs/runs`, `/jobs/run-due`), server time, manager match watch |
| `TeamController` | `/teams` | club profile, squad, matches, schedule, tactics, medical |
| `PlayerController` | `/players` | player search and creation paths |
| `MatchController` | `/matches` | match detail, fixture lookup, reveal |
| `MatchPlayerStatsController` | `/match-stats` | match/player stats and lineups |
| `SimulationController` | `/simulation` | round preparation, exhibition, simulation status |
| `LineupController` | `/lineups` | lineup reads and templates |
| `TrainingController` | `/training` | setup, player and weekly reports |
| `TransferController` | `/transfers` | listing, offers, buying, objections and loans |
| `LoanController` | `/loans` | rules, destinations, available players, offers in/out, offer, activate, terminate, accept-termination |
| `ScoutingController` | `/scouting` | assignments and scouting views |
| `JuniorController`, `JuniorSchoolController` | `/juniors` | academy and school |
| `CountryController` | `/countries` | world, country, leagues, cups, national sides |
| `ClubCupController` | `/club-cups` | international club cup page payloads |
| `NationalTournamentController` | `/api/national-tournaments` | national qualifiers and tournament payloads |
| `SeasonController`, `CalendarController` | `/seasons`, `/calendar` | seasons and calendar screens |
| `ForumController` | `/forum` | sections, topics, posts and moderation rules |
| `MessageController` | `/messages` | direct message threads |
| `NotificationController` | `/notifications` | unread/read notifications |
| `UserProfileController` | `/users` | public manager profiles and display name |
| `StatsController` | `/stats` | aggregate scoring/statistics views |
| `FinanceController`, `StaffDirectoryController` | `/api/teams/{id}` | finance/staff payloads |
| `StadiumController`, `StadiumSettingsController` | `/stadiums`, `/api/teams/{id}/stadium` | stadium and image settings, and the eight sections: `/sections/quote`, `/sections/build`, `/sections/price` |
| `FriendlyController` | `/api/season/friendlies` | friendly invitations and requests |
| `Zox*` controllers | `/api/zox`, `/zox` | match preview, reports and ZOX screens |
| simulation controllers | `/api/proposal`, `/proposal/api`, `/api/sim` | proposal simulation, replay and substitutions |

`CompetitionController` is not a useful route surface. Several old dummy-data paths and compatibility
methods remain in the backend, but a route's existence does not make it a valid football feature.

## 12. Known gaps and unfinished paths

These are current source/board findings, not historical audit claims.

### 12.1 Open correctness and ownership work

- Away teams still use home tactics during simulation (P0-3).
- Several controller/service paths still interpret a `CTeam` id as a football `Team` id (P0-20).
- Small international fields use direct knockouts, including a one-final two-club competition.
- Domestic cup drawing is still globally selected in one path instead of being explicitly one cup per country.
- The four-slot calendar is implemented on days 1, 3, 5 and 7; runtime booking evidence on days 1 and 5
  is still pending.
- Friendly training cost is explicitly zero. The training service retains a three-session baseline and
  match minutes continue to influence development.
- Country pages show only the relevant international qualifying candidates by tier: direct tier-1 places,
  then the pooled winners, 2nd/3rd places and 4th places for tiers 2–5. The races are derived from
  reconciled league standings on page load instead of maintaining a second persisted table.
- Friendly requests have backend support and dashboard ticker visibility, but inviting, national-team
  acceptance and the free-slot board are unfinished.
- `Re-draw national competitions` deletes every unplayed qualifying and tournament fixture for the active
  season. That is what it does, and it refuses once a qualifying tie has been played (§8.6), but the admin
  button's confirmation text says "Existing fixtures are left alone" and does not mention the refusal.
- The 47 countries with no club pyramid still field generated national squads. This is deliberate (§8.6),
  not finished.
- `admin-view.js` carries three handlers no button renders: `national-tournaments`,
  `national-ratings-reset` and `national-ratings-violations`. The third calls an endpoint that does not
  exist; the real one is `/admin/national-ratings/offenders`.

### 12.2 UI gaps

- National tournament rows on the World page and country national-team summaries link to the shared national
  tournament view; live browser verification remains open.
- Some legacy page names remain in the router for compatibility or partial functionality.
- `playerStats` and `teamStats` routing and aggregate loaders need continued verification against the
  intended screens.
- The new forum/private-message UI has not yet been verified by a real browser click-through; endpoint
  and rendered-module checks were recorded instead.
- The friendly invite action is not rendered even though the request service exists.
- Two classes are styled but never applied. `fm-qualifying-table tr.is-current-club` has a blue rule in
  the stylesheet and nothing in `country-view.js` emits it, so the manager's own club is not marked in the
  qualifying tables. `is-highlighted`, used by the represented-country group tables to mark the country
  being viewed, is not defined in the stylesheet at all — the same "class defined in JavaScript, absent
  from the CSS" failure the Jobs table hit.

### 12.3 Technical debt that affects the UI

- `pages.js` remains a large central router and dependency composition point.
- Some loaders still consume JSON without an explicit `response.ok` check.
- The old CTeam/Team id confusion produces plausible but wrong results in several read paths.
- The match, tick-state and zone-load data paths remain scale-sensitive at the 48-country world size.
- `JobStatusService.report()` reads the whole `job_run` table into memory on every `GET /admin/jobs` and
  then re-filters it per job in Java. The table grows with clock movement — roughly twenty rows per game
  day — so this is bounded now and unbounded later. It wants a per-key aggregate query.
- `PlayerRatingBackfill` and the national pool were the two patterns that would have turned a full 48-country
  pyramid into a cliff, and both are now batched or single-query. The measurement behind that decision is
  in `kanbanProgress.md` for `f8c492d`: ~363,000 extra player rows and roughly 190–300 MB, with daily use
  barely moving because every hot path is country-scoped and both required indexes already exist.
- The full test suite requires a running app for Playwright classes and can trigger expensive world-clock
  work. A green partial run is not evidence for the whole UI.

### 12.4 What H2 cannot see

Three defects in the twelve commits this revision covers were invisible to the suite, all because the test
schema is smaller than PostgreSQL's:

- restoring the inverted foreign-key comparison in `ResetService` **passed** — H2 carries neither the
  basketball and legacy foreign keys nor `TRUNCATE ... CASCADE`;
- the `session_replica_role` typo surfaced as "unrecognized configuration parameter" on the real server
  only, and had already shipped with the button out of service;
- the message-preview `DISTINCT ON` query is PostgreSQL-only, so `MessageServiceTest` exercises the
  fallback and not the query.

Anything touching the real schema, or a PostgreSQL-only feature, is not verified by a suite running on H2.

### 12.5 Deliberately retained behaviour

- `WE_HAVE_BALL` and `OPPONENT_HAS_BALL` are mirrored intentionally.
- The demo engine and its UI are frozen references. `js/demo.js` is not that engine — it is the dashboard's
  action-handler file and is live product code.
- Boot does not seed or repair the football world.
- Simulated countries have static clubs and receive players lazily where a competition requires them.
- The application uses polling for notifications, not WebSockets.
- The notification ticker shows unread items only. Rows stay in the database.
- A national-squad row is a copy, never a move.

## 13. Development and verification

### 13.1 Commands

```bash
export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:$PATH"

mvn clean package -DskipTests
./run-app.sh --app.open-browser=false
mvn test -Dtest=RatingEngineTest
```

Do not start the app from a shell without the browser opt-out. Playwright needs its browser installed
once with the Maven exec command described in `AGENTS.md`, a running application, and headless mode for
CI.

### 13.2 Evidence standard

For a world-building or scheduling change, inspect the database after the operation. A log line or green
HTTP response is not enough. For a guard test, deliberately break the guarded code and observe that the
test fails before accepting the test as evidence. Compare baselines measured with the same command and
allowed runtime; a killed Maven run does not produce the same failure summary as a completed run.

Where a behaviour is only reachable on real PostgreSQL, the test class says so in its own assertion rather
than implying it covered more than it did — see `MessageServiceTest` and
`ResetServiceOnRealPostgresTest`. A test that quietly exercises the fallback is worse than no test, because
it is read as evidence.

### 13.3 Documents that explain the history

- `kanban.md` — current P0/P1/P2 board and owner decisions;
- `kanbanProgress.md` — append-only implementation and measurement log;
- `archive/TECHNICAL_OVERVIEW.md` — previous detailed implementation snapshot, useful for historical
  comparison but stale where code has since moved;
- `archive/experAudit01102026.md` and `archive/expertAudit.md` — audits and corrections that explain
  why several old claims must not be copied forward;
- `archive/COMPETITIVE_ANALYSIS.md` — product depth and roadmap context;
- `src/main/java/org/example/footballmanager/newLogic/sim/*.md` — match-engine state and diagnostics.

The root document should be updated when the implemented UI or its load-bearing backend changes. The
board and progress log remain the places for open-task status and commit history.
