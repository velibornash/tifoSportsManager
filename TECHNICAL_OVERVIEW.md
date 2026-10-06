# TECHNICAL_OVERVIEW.md — the football UI as it exists

**Written 2026-10-06 against the source.** Scope is the graphical football manager: the lobby path
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

These counts were taken from the tree on 2026-10-06. They are orientation numbers, not contracts.

| Area | Count |
|---|---:|
| Football controllers | 28 |
| Football services | 85 |
| Football model files | 145 |
| Football repositories | 50 |
| Match simulation Java files | 85 |
| Static JavaScript files | 44 |
| Test classes in `src/test/java` | 200 |
| `@Test` annotations | 1,401 |

The old overview's counts of 25 controllers, 76 services and 78 frontend modules are stale. The
frontend has many modules below `js/pages/`; the count above counts files, including shared and legacy
mode files, so it should not be compared directly with an old module count.

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
- season-flow and clock information.

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
| `js/notifications.js` | Polling, unread badge and notification panel |
| `js/ui/escape.js` | The one HTML escaping helper |
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
| Competition | `leagueTable`, `leagueSchedule`, `leagueTeam`, `cup`, `international`, `world`, `country`, `clubCup`, `countryCup`, `countryPlayoffs`, `nationalTeam`, `u21Team` |
| Community | `forum`, `forumSection`, `forumTopic`, `messages`, `messageThread` |
| Reports | `playerStats`, `teamStats`, `topScorers`, `topAssists`, `analytics` |
| Identity/admin | `userProfile`, `publicUserProfile`, `admin` |

Some names are compatibility or partial paths. A route name existing in the switch does not prove that
the backend or a useful screen exists; the gaps are listed in §12.

### 5.3 World and country navigation

The World page calls `GET /countries/world`, ranks countries by country reputation and shows active vs
simulated state. A country row opens the country page. Active countries show their league structure;
simulated countries show the national-team record because their static clubs may have no players or
fixtures.

The World page now shows the three international club cups as rows in one competition table. Each row
shows the expected field size (Champions 48, Masters 96, Challenge 48) and opens `club-cup-view.js`,
which has tier tabs, group tables, results and knockout bracket data. The four national-team competitions
are listed from `/api/national-tournaments`; drawn rows open the shared national-tournament view and
undrawn rows show their planned week.

## 6. What the manager can do

### 6.1 Club and squad

The club area reads and updates the first team, lineup template, formations, tactics, stadium, staff,
finances, training, medical state, juniors and transfer activity. Club and league rows expose the
manager profile through the `User.footballTeam` link.

The current dashboard can show club milestones: top scorer, top assist, biggest win, heaviest loss and
attendance. These are derived from persisted match and crowd data and may be empty in a new world.

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

## 7. Clock, calendar and jobs

### 7.1 Game time

`GameClockService` advances hour, day, week and season. The calendar is a twelve-week season, with
season day values counted from 1. `LeagueSlotSchedule` maps the league rounds to calendar slots; a
ten-club double round robin has 18 rounds and two league slots per week in the current model.

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

Registered jobs cover matchdays, day opening, finance, training, recovery, league-table reconciliation,
cup draws, international club cups, national draws and matchdays, week rollover and season rollover.

### 7.3 Clock controls

The dashboard exposes development controls for advancing an hour, day or week and for running due jobs.
The API also exposes the current clock, job definitions and recent runs. Production runs the hourly
scheduler; development deliberately does not, so a test or inspection session does not move the world
under the user.

The season rollover and job-run paths remain high-risk code. The open board items cover recovery,
duplicate application, slot expansion and owner authorization decisions. Do not infer correctness from
a green build alone; a clock task is complete only after observing the resulting database state.

## 8. World building and competitions

### 8.1 Admin world actions

The Admin page starts long database operations and polls `/admin/database-job/status` for progress.
Current operations include:

| UI action | Backend effect |
|---|---|
| Reset DB | Deletes football data while preserving the owner account and tactic-editor setups |
| Initialize DB | Builds the Serbian football structure and its fixtures/players |
| Seed other nations | Builds static pyramids for countries that are not activated; no players or matches |
| Repair world | Repairs the country catalogue and national-team baseline |
| Re-seed national teams | Tops up missing national-team squads |
| Re-draw the cup | Draws missing domestic cup rounds |
| Repair international cups | Creates the 15 international club cup rows and fills missing simulated-country structures |
| Seed national tournaments | Creates and draws national qualifying/tournament structures |
| Reset national ratings | Explicit admin correction/backfill for the rating columns |

The exact button set is in `pages/views/admin-view.js`; the operation remains synchronous inside some
service classes despite the `Async` naming, while the frontend receives a job-shaped response and
polls it.

### 8.2 Pyramid and fixtures

`PyramidBuilder` creates five tiers, 31 divisions and ten clubs per division. The active-country path
creates squads and fixtures. The simulated-country path creates static clubs, ratings and tables but
does not create players or fixtures; squads can be created lazily when an international club cup needs
them.

`SeasonService.ensureDoubleRoundRobinSchedule` is the single league schedule generator. It is
idempotent, uses competition entries, writes first and return legs, and reads the calendar slot for
each round. It does not invent a date when no slot exists.

Promotion and relegation are represented by the country-wide ladder and playoff services. The calendar
and scheduled rollover paths are still under active repair, so a built ladder is not evidence that an
entire season rollover has been observed end to end.

### 8.3 Domestic cup

The domestic cup is a national competition represented by `Competition` plus `MatchFixture.roundNumber`;
there is no separate `CupRound` entity. The domestic draw is country-scoped: `CupFixtureSeeder` iterates
every national cup for both repair and scheduled draw paths, and each cup ranks clubs from its own country.
International cup rows are excluded by competition scope.

The draw is deterministic from ranked entrants and does not use a coin flip to settle an unresolved
winner. A cup field below the normal group threshold now has a small-field knockout path, but the
two-club edge decision remains open.

### 8.4 International club cups

There are three named international club cups across five tiers: Champions, Masters and Challenge.
The backend creates 15 competition rows, qualifies clubs from finished domestic tables, gives simulated
entrants lazy squads, draws group stages, records group tables and advances knockout rounds.

`InternationalClubCupJob` is now a live job at week/day/hour order 30. The previous unreachable-only
draw path was replaced. `ClubCupController` exposes:

```text
GET /club-cups
GET /club-cups/{key}?tier=N
```

The World page links into `club-cup-view.js`, which renders each tier's groups, results and bracket. The
three World rows report 48, 96 and 48 expected entrants. Small fields of 2–7 entrants now use a knockout
path; two entrants play a direct final and odd fields carry a bye. The repair action durably creates
missing international rows before filling static simulated-country tables, and the
week-1 job repeats that durable repair boundary before drawing. The domestic cup draw is separately country-scoped
and no longer selects one primary cup globally.

The repair boundary was verified against PostgreSQL on 2026-10-06: all 15 competition rows existed, all
three tier-1 cup endpoints returned HTTP 200, and the World payload exposed competition IDs for every tier.
The simulated-world seed continues separately and must finish before its qualifying counts are treated as
the final live field sizes.

### 8.5 National teams and tournaments

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

The tournament champion path now includes the final feed-forward round. The frontend World rows now
link drawn senior and U-21 national competitions to their groups, standings, results and bracket; undrawn
rows remain visible with their planned week.
Undrawn competitions remain unavailable by design, while drawn competitions are reachable from the World
page. `NationalTournamentController` backs the shared national-tournament view for both senior and U-21
groups, standings, results and knockout rounds.

### 8.6 Country/world integrity

`WorldIntegrityService` reports catalogue and squad state. `WorldRepairService` performs explicit repair
and returns the after-state. `WorldCatalogSeeder` is idempotent; simulated-world seeding is guarded by
existing competitions; bot squads are guarded by an existing squad. National squad copying still has a
known idempotence concern for club-backed countries and should be observed in the database when fixed.

## 9. Match simulation and persistence

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

`demo/service/ui/proposal` is a viewer/reference asset used by the replay link. It is not the live
match engine and must not be extended as product logic.

## 10. Management systems

### 10.1 Players and squads

Players have skills, position, preferred foot, age, talent/ability information, form, morale, contracts,
earnings, injuries and training state. Player pages show match history, rating summaries, transfer
actions and links back to the owning club.

Player retirement exists in the service layer and is part of the current management model. Youth
development, hidden talent ranges and scouting are separate from current ability display.

### 10.2 Economy and board

`WeeklyFinanceService` settles club ledgers for wages and income categories. Stadium construction,
maintenance, sponsorship/prize income, attendance and FFP bands feed the club view. `BoardExpectationService`
and morale provide a partial meta-layer.

There is still no complete failure state: board trust can be calculated and a sacking review can be
reported, but the manager cannot yet be removed through a full end-to-end sacking flow. Debt,
bankruptcy and retirement consequences remain product gaps.

### 10.3 Staff, stadium and supporters

Staff directory and sponsor data are exposed from the club management area. Stadium settings, image
upload, attendance and pitch maintenance are available through separate endpoints and views. Supporter
mood/expectation logic exists in the service layer; the board tracks remaining work where the value is
not yet fully connected to a visible management consequence.

## 11. API surface

The following groups are the routes used by the football UI. This is a capability map, not a promise
that every route has a current screen.

| Controller/group | Main prefix | Main responsibilities |
|---|---|---|
| `UserController` | `/auth` | login, registration, current manager |
| `AdminController`, admin user controller | `/admin` | database actions, approvals, roles, bans, repair |
| `APIController` | `/api` | clock, jobs, server time, manager match watch |
| `TeamController` | `/teams` | club profile, squad, matches, schedule, tactics, medical |
| `PlayerController` | `/players` | player search and creation paths |
| `MatchController` | `/matches` | match detail, fixture lookup, reveal |
| `MatchPlayerStatsController` | `/match-stats` | match/player stats and lineups |
| `SimulationController` | `/simulation` | round preparation, exhibition, simulation status |
| `LineupController` | `/lineups` | lineup reads and templates |
| `TrainingController` | `/training` | setup, player and weekly reports |
| `TransferController` | `/transfers` | listing, offers, buying, objections and loans |
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
| `StadiumController`, `StadiumSettingsController` | `/stadiums`, `/api/teams/{id}/stadium` | stadium and image settings |
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

### 12.2 UI gaps

- National tournament rows on the World page and country national-team summaries link to the shared national
  tournament view; live browser verification remains open.
- Some legacy page names remain in the router for compatibility or partial functionality.
- `playerStats` and `teamStats` routing and aggregate loaders need continued verification against the
  intended screens.
- The new forum/private-message UI has not yet been verified by a real browser click-through; endpoint
  and rendered-module checks were recorded instead.
- The friendly invite action is not rendered even though the request service exists.

### 12.3 Technical debt that affects the UI

- `pages.js` remains a large central router and dependency composition point.
- Some loaders still consume JSON without an explicit `response.ok` check.
- The old CTeam/Team id confusion produces plausible but wrong results in several read paths.
- The match, tick-state and zone-load data paths remain scale-sensitive at the 48-country world size.
- The full test suite requires a running app for Playwright classes and can trigger expensive world-clock
  work. A green partial run is not evidence for the whole UI.

### 12.4 Deliberately retained behaviour

- `WE_HAVE_BALL` and `OPPONENT_HAS_BALL` are mirrored intentionally.
- The demo engine and its UI are frozen references.
- Boot does not seed or repair the football world.
- Simulated countries have static clubs and receive players lazily where a competition requires them.
- The application uses polling for notifications, not WebSockets.

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
