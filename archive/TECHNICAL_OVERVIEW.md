# TIFO Sports Manager — Football UI: Technical Overview

Written 2026-10-01. Scope: **the football UI mode only** (`/dashboard.html`, reached from
`home.html` → "TIFO UI MANAGER"). The text-based football mode, basketball and American football
are listed where they touch the shared boot path, and are otherwise out of scope.

Every claim below cites `file:line`. Anything I could not confirm from code is in
[§10 Open questions](#10-open-questions). Where the code contradicts a comment or an existing doc,
that is called out inline — the code is treated as the truth.

---

## Table of contents

1. [What runs at boot](#1-what-runs-at-boot)
2. [Authentication](#2-authentication)
3. [Login → home → the football dashboard](#3-login--home--the-football-dashboard)
4. [What the dashboard loads](#4-what-the-dashboard-loads)
5. [The page router and the full tab list](#5-the-page-router-and-the-full-tab-list)
6. [The game clock: hour, day, week, season](#6-the-game-clock-hour-day-week-season)
7. [The job framework](#7-the-job-framework)
8. [The admin panel and world building](#8-the-admin-panel-and-world-building)
9. [Defects and gaps found while writing this](#9-defects-and-gaps-found-while-writing-this)
10. [Open questions](#10-open-questions)

---

## 1. What runs at boot

### 1.1 The application class

`SportsManagerApplication` (`src/main/java/org/example/SportsManagerApplication.java`) is a plain
Spring Boot 3.3.3 / Java 21 app. It does not use package-default scanning — it enumerates every
package explicitly in three places, and adding a feature means adding it to all three:

| Annotation | Packages |
|---|---|
| `@SpringBootApplication(scanBasePackages)` | `org.example.config`, `…footballmanager.newLogic`, `…footballtextmanager`, `…basketballmanager`, `…americanfootballmanager`, `…commonmanager` |
| `@EnableJpaRepositories(basePackages)` | the five `*.repository` packages |
| `@EntityScan(basePackages)` | the five `*.model` packages **plus** `…newLogic.model.event` |

`@EnableAsync` is on the class (`SportsManagerApplication.java:14`), which is what lets
`AsyncSimulationRunner` and the admin job runner work.

### 1.2 Boot sequence, in order

The context comes up, then four things happen in this order. **Two of them write to the database on
every single boot.**

| # | What | Trigger | Writes to DB? |
|---|---|---|---|
| 1 | `SimReplayStore` warms the replay cache from disk | `@PostConstruct` — `…/sim/SimReplayStore.java:72` | no |
| 2 | **`StartupInitializer` — the owner account** | `CommandLineRunner` — `…/commonmanager/util/StartupInitializer.java:25` | **yes, every boot** |
| 3 | **`DatabaseInitializer.sanitizeLegacySchemaOnStartup`** | `@EventListener(ApplicationReadyEvent)` — `…/util/DatabaseInitializer.java:191` | **yes, every boot** |
| 4 | `GameClockScheduler.onTheHour` — hourly tick | `@Scheduled(cron)` — `…/jobs/GameClockScheduler.java:56` | only if enabled |
| 5 | `BrowserLauncher` opens `/login.html` | `ApplicationRunner` — `…/config/BrowserLauncher.java:56` | no |

#### Step 2 — the owner account is created or rewritten on every boot

`StartupInitializer.run` (`:53-63`) looks the owner up by username/email and then either creates or
**updates** them. The update path is not a no-op — it unconditionally rewrites:

- `password` → re-encoded from `app.owner.password`, default `A12345!` (`:146`)
- `role` → `OWNER` (`:145`)
- `cteam`, `tifoCTeam`, `basketballTeam`, `americanFootballTeam` → looked up by name, **creating a
  placeholder team if none matches** (`:118-139`)
- `countryCode` → `SRB` (`:109` on create)

This means **a password change made through the app is reverted on the next restart.** It is also
why the owner can always log in with the documented credentials.

#### Step 3 — three schema repairs run on every boot

`sanitizeLegacySchemaOnStartup` (`DatabaseInitializer.java:191-201`) is deliberately *not* world
seeding — it is schema maintenance:

1. `sanitizeLegacyLineupOrderSchema()` — drops `lineup_starting_players.slot_order` and
   `lineup_substitutes.bench_order` (`ResetService.java:22-26`)
2. `migrateTickStateMinuteColumn()` — drops `match_tick_states.minute` (`ResetService.java:35-38`)
3. `enforceOneSeasonCompetitionPerSeason()` — collapses duplicate `season_competition` rows, keeping
   the one with the most `competition_entry` rows, then adds a `UNIQUE (competition_id,
   season_year)` constraint if absent (`ResetService.java:61-66`)

Step 3 runs **before** anything that could create a season row — the comment at `:195-199` explains
that a world still holding calendar years would otherwise get a second season row created beside the
first, and `findByCompetitionAndSeasonYear` throws on duplicates rather than degrading.

#### Step 4 — the clock scheduler, prod only

`GameClockScheduler` is registered in every profile but does nothing unless
`game.clock.scheduler-enabled` is true:

| Profile | `scheduler-enabled` | `auto-advance` |
|---|---|---|
| `application-dev.properties:16-17` | `false` | `false` |
| `application-prod.properties:17-19` | `true` | `true` (cron `0 0 * * * *`) |

Off in dev deliberately, so the season does not move underneath the tests. The manual **Advance
Hour / Advance Day / Advance Week** buttons are the dev path.

#### Step 5 — the browser launcher

`BrowserLauncher` opens `http://localhost:8080/login.html` on startup. The application cannot tell
an IDE start from a shell start, so `app.open-browser` stays `true` for the IDE and
`./run-app.sh` must opt out.

### 1.3 What does **not** run at boot

`DatabaseInitializer.ensureBaselineDataOnStartup()` (`DatabaseInitializer.java:280`) is **no longer
a boot hook**. Its javadoc at `:260-278` records why: it used to seed the entire world on every
boot, which on a small server looked like a hang, and on the owner's Oracle instance it had failed
silently for a whole season because the seeding failure was swallowed by a catch.

World building is now admin-button-only — see [§8](#8-the-admin-panel-and-world-building).

> **Correction to `AGENTS.md`.** That file says "Boot writes nothing". That is not accurate: steps 2
> and 3 above both write on every boot. What is true is that **no world seeding** happens at boot.

### 1.4 Other modes' boot hooks

Three more `@EventListener(ApplicationReadyEvent)` beans exist and will also fire:
`CSDataInitializer` (text football, `:41`), `BbDataInitializer` (basketball, `:63`),
`AfDataInitializer` (American football, `:63`). They are out of scope for this document but they do
run, so a boot failure in any of them affects all modes.

---

## 2. Authentication

### 2.1 Token

`JwtUtil` (`src/main/java/org/example/commonmanager/util/JwtUtil.java`) issues a **stateless HS512
JWT**:

| Property | Value | Line |
|---|---|---|
| Secret | hardcoded string `"VeljaTestSecretKeyVeryLongAndSecure12345678901234567890"` | `:15` |
| Expiry | `86400000` ms = **24 hours** | `:18` |
| Claims | `sub` = username, `role`, `iat`, `exp` | `:21-27` |

There is **no refresh token and no server-side session.** When the token expires the user is
redirected to login.

### 2.2 Endpoints

| Endpoint | Behaviour |
|---|---|
| `POST /auth/login` | `{username, password}` → `{token}` (`UserController.java:80-87`) |
| `POST /auth/register` | Creates a **PENDING** `RegistrationRequest`. **No account exists yet** — an admin must approve it (`UserController.java:52-77`) |
| `GET /auth/me` | Returns the user, including the resolved football `teamId`, `competitionId`, `seasonYear`, `countryCode`, `role` (`UserController.java:89-188`) |

`UserDetailsService` resolves by username **or** email (`SecurityConfig.java:34-40`), which is why
the login form sends the email address in the `username` field.

### 2.3 The security rules

`SecurityConfig.securityFilterChain` (`:62-141`) is a three-tier allowlist:

1. **`permitAll`** — static assets, `/auth/**`, `/countries/catalog`, `/api/server-time`,
   `/api/game-clock`, and `"/*.html"` (every page shell, single segment, so it cannot reach
   `/api/**`). `/basketballmanager/**` and `/americanfootballmanager/**` are also open, with a
   comment at `:101-104` admitting they read no JWT and need their own auth pass.
2. **`hasAnyRole("ADMIN","OWNER","DEV")`** — `/admin/**` only (`:137`)
3. **`anyRequest().authenticated()`** — everything else

`CSRF` is disabled (`:64`). A previous, much wider permit list that exposed `/teams/**`,
`/countries/**`, `/api/**` and `/proposal/api/**` was removed — the comment at `:66-72` records that
it left the whole game API readable and mutable by anyone.

> **There is no method-level authorization anywhere.** `grep` for `@PreAuthorize`, `@Secured` or
> `hasRole` across `newLogic/controller/` returns **nothing**. `/admin/**` is protected only by its
> URL rule. Everything else is gated by authentication alone — see [§9](#9-defects-and-gaps-found-while-writing-this).

### 2.4 The client side

`js/auth.js` is the single authenticated-fetch wrapper, `authFetch(url, options)` (`:184-343`):

- injects `Authorization: Bearer <token>` and `X-Requested-With` (`:198-202`)
- adds `Content-Type: application/json` only when there is a body and it is not `FormData` (`:204-207`)
- **throws on any non-2xx**, including a bespoke `AuthFetchError` with a `code`:
  `NO_TOKEN`, `NETWORK_ERROR`, `LOGIN_REDIRECT`, `INVALID_RESPONSE` (`:185-340`)
- on 401: stores the error, shows a full-screen overlay with a 3-second countdown, then clears the
  token and `replace()`s to `/login.html` (`:146-165`)

**The token lives in `sessionStorage`** under the key `'token'` (`:3`, `:23-25`), and
`setAuthToken` actively removes it from `localStorage` (`:27-34`). Consequence: **the session is
per-tab** and dies when the tab closes.

Role handling is client-side for visibility only: `ADMIN_ROLES = {ADMIN, OWNER, DEV}` (`auth.js:356`)
and `applyAdminVisibility` toggles `hidden` on `[data-admin-only]` elements (`:390-401`).

> `js/tifo.js` (the text mode) carries a **second, independent** hand-rolled fetch wrapper `csApi`
> (`tifo.js:19-31`). `clock.js:1-10` explicitly complains about exactly this pattern.

---

## 3. Login → home → the football dashboard

### 3.1 `login.html`

Loads `/js/auth.js` and `/js/login.js` (`:46-47`). Form `#loginForm` posts to `/auth/login` via
plain `fetch`, checks `response.ok`, and on success:

```js
setAuthToken(data.token);
window.location.href = '/home.html';
```

(`js/login.js:36-68`)

**The owner credentials are hardcoded in the markup** as `value` attributes —
`value="velibor@example.com"` (`login.html:28`) and `value="A12345!"` (`:32`) — so the form is
submittable with one click. This is a real exposure and must not ship.

There is also a **dead reference**: `login.js:5-6` looks for `#loginStatus` and `:70` calls
`renderLastError()`, but no such element exists in `login.html`, so the "last app error" banner
persisted by `auth.js` is never shown on the login page.

### 3.2 `home.html` — the game-mode picker

The page loads **no JS module**. It is one inline script (`home.html:69-93`). It guards on
`sessionStorage.getItem('token')` and redirects to `/login.html` if absent (`:70-74`).

Four cards, none disabled:

| Card | Target | Note |
|---|---|---|
| ⚽ **TIFO UI MANAGER** | `/dashboard.html` | **the football mode this document covers** |
| 📋 TIFO TEXT BASED | `/tifo.html` | football, but text mode — out of scope |
| 🏈 AMERICAN FOOTBALL | `/americanfootballmanager/index.html` | out of scope |
| 🏀 BASKETBALL | `/basketballmanager/index.html` | out of scope |

**No role or team is chosen here.** There is no team picker, no role picker and no `/auth/me` call
on this page. The team is resolved later, inside the dashboard.

### 3.3 `dashboard.html` — the shell

Inline token guard (`:156-162`), then seven ES modules in order (`:164-170`):
`/js/auth.js`, `/js/reveal-ui.js`, `/js/demo.js`, `/js/sidebar.js`, `/js/dashboard.js`,
`/js/pages.js`, `/js/clock.js`.

**Persistent chrome:**

- **Top bar** (desktop): Sports Lobby, Club, League, Country, World, Community, Admin (hidden unless
  admin), the account menu, a live clock, and the logo (`:14-57`)
- **Mobile drawer** only — **there is no desktop sidebar.** `js/sidebar.js:22-37` documents that the
  desktop `#clubSidebar` was removed because `.sidebar` is fixed at `left:-260px` and nothing ever
  applied `.active`. The module now only provides the mobile menu and accordion toggles.
- The logo calls `loadDashboard()` (`:54`), so it is a **back-to-dashboard** button from every page.
  Only `onclick` is wired, despite the element carrying `role="button"` and `tabindex="0"` — it is
  not keyboard-operable.
- The clock shows `Season N • Week N · Day N (label)` — the **game position**, not a calendar date
  (`clock.js:76-85`; the calendar date was removed deliberately).

> **Dead menu entry.** "Club settings" calls `loadPage('clubProfileSettings')` (`dashboard.html:40`),
> but `clubProfileSettings` is **not a case in the router**. It falls through to `default:` and
> renders "Page not found" (`pages.js:628-629`).

---

## 4. What the dashboard loads

### 4.1 First paint — two independent `/auth/me` calls

There are **two separate `window.addEventListener('load')` handlers** that each fetch `/auth/me`:

1. `dashboard.js:472-514` — the real bootstrap. Caches `footballTeamId`, `footballTeamName`,
   `footballTeamLogoUrl`, `competitionId/Name`, `seasonYear`, `countryName`, `countryIsoCode`,
   `role` (`:484-492`), then applies admin visibility, repaints the account menu and country label,
   and calls `loadDashboard()`.
2. `demo.js:596-624` — fetches `/auth/me` **again** and keeps only `currentUserTeamId` (`:604-607`).

`clock.js:100-105` also fires three clock syncs at module load, and `pages.js` registers its
account-menu and back-button listeners at import time.

### 4.2 How the shell decides which club to show

It does not decide — it reads `/auth/me`. The chain is:

```
GET /auth/me
  → User.cTeam name
  → teamRepository.findAllByNameIgnoreCase(name)
  → on duplicate names, the humanControlled one wins, else the first
  → footballTeamId / footballTeamName / footballTeamLogoUrl on the DTO
```

(`UserController.java:139-171`)

That value is then cached in **three separate module-level variables** — `dashboard.js:484`,
`pages.js:232` and `demo.js:606` — each from its own `/auth/me` call. They agree in practice;
nothing enforces it.

### 4.3 `loadDashboard()` fan-out

`loadDashboard()` (`dashboard.js:516-596`) writes the shell HTML and then fires five loaders
(`:591-595`):

| Loader | Endpoint | Checks `response.ok`? |
|---|---|---|
| `loadRecentMatches` | `GET /teams/{teamId}/matches` (`:850`) | yes |
| `loadHomeTeamStats` | `GET /countries/leagues/{leagueId}/table?seasonYear=` (`:972`) | yes |
| `loadDashboardMilestones` | `GET /teams/{teamId}/milestones` (`:702`) | yes |
| `loadNextMatch` | `GET /teams/{teamId}/schedule` (`:600`) | yes |
| `loadImportantUpdates` | **8 calls in one `Promise.all`** (`:405-416`) | yes, via `.then(r => r.ok ? r.json() : null).catch(() => null)` |

The eight "important updates" calls: `/teams/{id}/medical`, `/teams/{id}/lineup-template`,
`/transfers/team/{id}`, `/community/summary`, `/transfers/window`,
`/api/season/friendlies/{id}/week`, `/api/game-clock`, `/training/weekly/team/{id}/reports`.

Dashboard regions: the updates ticker, the club card with four clickable tiles, Next Match, Recent
Matches (with hidden-result reveal buttons), Club Milestones, and **Match Week Controls**
(`:172-211`) — the season-flow panel:

| Button | Endpoint | Gated in UI? |
|---|---|---|
| ⚽ Watch Your Match | `POST /simulation/current-round/prepare` | no |
| 🧮 Simulate All Results | `POST /simulation/current-round/simulate-all` | no |
| 📅 Advance Week | `POST /simulation/week/advance` | yes (`isAdminUser()`, `dashboard.js:205`) |
| 📆 Advance Day / ⏩ Advance Hour | `POST /api/game-clock/advance?unit=…` | yes (`dashboard.js:206-207`) |

> **The gate is only in the frontend.** `POST /api/game-clock/advance` (`APIController.java:59-68`)
> has **no role check** — see [§9](#9-defects-and-gaps-found-while-writing-this).

---

## 5. The page router and the full tab list

### 5.1 There is no URL routing

No `hashchange`, no `history.pushState`, no `popstate`. `currentPageId` is a plain variable
(`pages.js:47`) and the only "route" is a **string argument** to `loadPage` (`pages.js:448`).
Navigation happens through inline `onclick="loadPage('x')"` handlers in `dashboard.html` and in
every rendered action strip, plus ~50 functions published on `window` for those inline handlers
(`:1072-1138`).

**Consequence: the browser Back button does not navigate the SPA, and a reload always returns to
the dashboard.**

`loadPage` steps (`pages.js:448-636`):

1. sets `currentPageId`
2. **team guard** — if `!currentUserTeamId`, awaits `loadUserTeamId()`; if still null it
   **returns without rendering anything** (`:452-455`)
3. resets the league context for league pages unless explicitly preserved (`:456-459`)
4. `pushNavState(buildPageNavState(page))` (`:460`)
5. `switch (page)` over the route table (`:463-630`)
6. `catch` → renders a generic **"API Error"** card (`:632-635`)

Unknown routes render **"Page not found"** with no log and no throw (`:628-629`).

Navigation history is a 50-deep in-memory stack (`:49`, `:157`) with `goBackSmart(fallback)`
(`:201-226`).

### 5.2 The route table — 41 `case` labels

Counted mechanically over `pages.js:463-630`. Because several are **fall-through aliases**
(`formations`/`tactics`, `schedule`/`fixtures`, `training`/`trainingSetup`, `forum`/`events`/`chat`),
there are **fewer distinct screens than cases**.

**Club (14 cases)**

| Route | Screen | Endpoint(s) |
|---|---|---|
| `firstTeam` | Squad table, milestones, medical | `/teams/{id}/players`, `/milestones`, `/medical` |
| `juniors` | Academy prospects, intake | `/juniors/team/{id}`, `/juniors/school/team/{id}` |
| `medicalCenter` | Injuries, recovery | `/teams/{id}/medical` |
| `formations` / `tactics` | Formation, XI, bench | `/teams/{id}/formations`, `/players`, `/lineup-template` |
| `tacticEditor` | Movement rules per ball state | `/teams/{id}/tactics-editor` |
| `staff` | Staff by department, sponsors | `/api/teams/{id}/staff`, `/sponsors` |
| `coaches` | **alias of `staff`** (`club-management.js:623`) | same |
| `finances` | Budget, wages, income/cost | `/api/teams/{id}/finances` + history/board |
| `transfers` | Market, listings, offers | `/transfers`, `/transfers/team/{id}` |
| `training` / `trainingSetup` | Group→skill training, weekly run | `/training/setup/team/{id}` |
| `trainingReports` | Per-week skill deltas, graphs | `/training/weekly/team/{id}/reports` |
| `profile` | Club identity, badge, stadium link | `/teams/{id}/profile` |
| `results` | Played matches | `/teams/{id}/matches` |
| `schedule` / `fixtures` | Upcoming + played | `/teams/{id}/schedule` |

**Competition (6 cases)**

| Route | Screen | Endpoint(s) |
|---|---|---|
| `leagueTable` | Table, rounds, scorers, summary | `/countries/leagues/{id}/table`, `/schedule`, `/stats/leagues/{id}/topscorers` |
| `leagueSchedule` | Fixtures by round | `/countries/leagues/{id}/schedule` |
| `leagueMatches` | League results | `/countries/leagues/{id}/matches` |
| `world` | Country ranking | `/countries/world` |
| `country` | **4 sub-tabs**: General, Calendar, National Team, U-21 | `/countries/{iso}/leagues`, `/national-team`, `/cup`, `/playoffs` |
| `countryCup`, `countryPlayoffs`, `nationalTeam`, `u21Team` | Country sub-pages | same family |

**Community (3 cases → 1 screen)**

`chat`, `forum`, `events` all dispatch to `loadChat` (`community.js:314-320`). The comment at
`:300-313` records that the admin tooling moved to the Admin tab.

**Stats (4 cases)**

`topScorers`, `topAssists`, `playerStats`, `teamStats` — **all four** dispatch to
`loadTopScorersAndAssists` (`pages.js:602-614`). See [§9](#9-defects-and-gaps-found-while-writing-this).

**Other (6 cases)**

| Route | Notes |
|---|---|
| `admin` | See [§8](#8-the-admin-panel-and-world-building) |
| `stadium` | Capacity, prices, pitch |
| `userProfile` | Name, role, subscription, club, country |
| `analytics` | Redirects to `/zox-match-preview.html`; no markup reaches it |
| `cup`, `international`, `friendlies` | **`/demo/*` — fake data**, see below |
| `upcoming` | `/demo/matches/...` — fake data |

**Not reached by a route key**, but reachable by row click: `loadPlayer`, `loadMatch`, `loadFixture`,
`loadLeagueTeam`, `loadLeagueTeamPlayer`. The match page has 6 tabs — Preview, Lineups, Stats,
Goals, Replay, Match Report (`match-view.js:117-124`).

### 5.3 Fake data still wired into the router

Routes `cup`, `international`, `friendlies` and `upcoming` all call `/demo/**`, which is
`DummyDataController` — **hardcoded to team 1, with zero database access**
(`DummyDataController.java:73`, `:81`, `:88`, `:118`).

`buildLeagueActionsHtml` deliberately excludes all `/demo/*` routes (`pages-renderers.js:441-444`),
so the buttons are hidden — but the routes still resolve if called.

### 5.4 `response.ok` discipline is about half

`authFetch` throws on any non-2xx before returning (`auth.js:258-340`), so a bare `.json()` after
it cannot silently read an error body — it throws and the router renders "API Error". These are
still real defects, because a page-specific failure becomes a generic card:

| Location | Call |
|---|---|
| `pages.js:978-980`, `:986-988`, `:230-231` | `loadCup`, `loadInternational`, `loadUserTeamId` |
| `features/matches.js:41-42` | `/teams/{id}/schedule` |
| `views/fixture-view.js:42-44`, `:255-257` | both `/demo/*` readers |
| `views/club-view.js:11` | `/teams/{id}/profile` |
| `views/stats-view.js:36-38`, `:45-47`, `:81-82` | player stats, team stats, **topscorers + topassists** |
| `features/club-management.js:376-378` | all three transfers calls |
| `views/training-view.js:481-482` | weekly report |
| `views/league-view.js:322-323`, `views/player-view.js:49-50` | transfer status lookups |
| `demo.js:817-818`, `:834-835` | admin job start + status poll |

`country-view.js` is the counter-example: one guarded `readJson` helper reused everywhere
(`country-view.js:25-34`). `match-view.js:40` and `tactic-editor-view.js:20-23` also guard correctly.

### 5.5 Polling

**No WebSocket and no SSE anywhere in the frontend.** Everything is `setInterval`:

| Interval | What | Endpoint |
|---|---|---|
| 1 s | Clock text rendering (DOM only) | — |
| 20 s | `syncGameClock` | `GET /api/game-clock` |
| 5 min | `syncWithServerTime` | `GET /api/server-time` |
| 1.5 s ×3 | Season-flow job polling | `/simulation/current-round/prepare/status`, `/…/status`, `/simulation/week/advance/status` |
| 1 s, max 240 | Admin DB job polling | `GET /admin/database-job/status` |

(`clock.js:100-105`, `demo.js:469/484-490,497/518-524,538/554-560`, `dashboard.js:831-846`)

The three season-flow pollers **stop themselves on the first error** rather than retrying
(`demo.js:475-481`, `:509-515`, `:545-551`).

**The live match viewer is not a server stream.** `/demo/service/ui/proposal/index.html` replays a
pre-computed event timeline client-side; the text mode does the same with a local `setInterval`
(`tifo.js:2954-2960`).

---

## 6. The game clock: hour, day, week, season

### 6.1 Two representations, on purpose

`GameClockService` (`…/service/GameClockService.java`) keeps **four explicit counters** —
`currentSeason`, `currentWeek`, `currentDay`, `currentHour` — **plus one offset**:

```java
gameTime = Instant.now() + advanceOffsetSeconds   // GameClockService.java:78
```

The offset is why "advance hour" is permanent while the clock still ticks 1:1 with real seconds.
A stored hour would be overwritten by the next render (`:23-39`).

The counters are explicit **rather than derived from the date**, because deriving them made a job at
23:00 fire or not depending on what time of day the manager pressed the button — which is why
week-rollover and season-rollover "never ran" (`:133-137`, `:190-208`).

`snapshot()` (`:97-119`) is what the API and the header read. It returns season, week, day number,
day label, day kind, `matchDay`, `kickoffHour`, hour, `gameTime`, and the offset.

The game zone is **`Europe/Belgrade`** (`:59`), and it must match what `clock.js` formats in,
"or the header and the API would disagree about what hour it is" (`:53-58`).

### 6.2 The wrap rules

`advanceHour` (`:139-169`), exactly:

```
hour  23 → 0,  and day  += 1
day    7 → 1,  and week += 1
week  12 → 1,  and season += 1
```

and in every case `advanceOffsetSeconds += 3600`.

The composite moves are thin wrappers so no trigger is ever stepped over:

| Method | Implementation |
|---|---|
| `advanceDay()` | `advanceHours(24)` (`:186-188`) |
| `advanceWeek()` | `advanceHours(168)` (`:211-213`) |
| `advanceHours(n)` | loops `advanceHour()` n times (`:231-240`) |
| `advanceToHour(h)` | forward-only; a passed target is a no-op, not an error (`:249-258`) |

`advanceDay` and `advanceWeek` both used to be buggy — `advanceDay` offered **48** runner calls for
a 24-hour day (`:171-184`), and `advanceWeek` moved the week counter but offered only one day to the
runner, so **every job pinned to day 5 or 7 was skipped permanently** (`:190-208`).

### 6.3 The season shape

| Constant | Value | Line |
|---|---|---|
| `WEEKS_PER_SEASON` | **12** | `SeasonCalendar.java:56` |
| `LEAGUE_ROUNDS` | **18** | `SeasonCalendar.java:102` |
| `SLOT_ONE_DAY` / `SLOT_TWO_DAY` | 3 / 7 | `SeasonCalendar.java:113-114` |
| `SLOTS_PER_WEEK` | 2 | `SeasonCalendar.java:115` |

**A season is a number counted from 1. There is no calendar year.** `SeasonNumberBackfill` rewrites
any `season_year >= 1000` to `1` (`SeasonNumberBackfill.java:41-42`, `:94-123`).

The week → round calendar (`SeasonCalendar.SLOTS`, `:194-232`):

| Week | Contents |
|---|---|
| 1–5 | league rounds 1–10, on day 3 and day 7 |
| **6** | **no league** — internationals land here (day 1) plus friendlies |
| 7–10 | league rounds 11–18 |
| 11 | playoffs (`FRIENDLY_IF_NOT_IN_PLAYOFF`) |
| 12 | friendlies |

### 6.4 What triggers the jobs

`advanceHour` ends with `afterMove` → `jobRunner.runDue(season, week, day, hour)`
(`GameClockService.java:220-227`). **Dispatch happens after the hour is incremented but before the
day rolls over**, so the step from 22:00 to 23:00 offers `(today, 23)` and only the step past 23:00
moves the day (`:171-184`).

Three things can call the runner:

| Trigger | Notes |
|---|---|
| Manual advance | `POST /api/game-clock/advance?unit=hour\|day&amount=` (`APIController.java:59-68`) |
| `GameClockScheduler.onTheHour` | prod only, cron `0 0 * * * *` (`GameClockScheduler.java:56-86`) |
| `POST /api/jobs/run-due` | runs due jobs **without** moving the clock (`APIController.java:83-89`) |

The scheduler deliberately **checks as well as advances** — even with `autoAdvance=false` it still
runs whatever is due, so a job missed while the app was down is caught rather than lost
(`GameClockScheduler.java:19-22`, `:66-74`). The `JobRun` done-flags make repeat ticks harmless.

---

## 7. The job framework

### 7.1 The contract

`DayJob` (`…/jobs/DayJob.java`) — five members:

```java
String key();  int week();  int day();  int hour();
default int order() { return 100; }
default boolean dueAtMidnight() { return true; }
void run(JobContext context);
```

`ANY_WEEK` and `ANY_DAY` are both `0` (`DayJob.java:5-6`). `JobContext` is a record of
`(seasonYear, weekNumber, dayNumber, hour)`.

> `dueAtMidnight()` is declared but **never read** by `JobRunner` — it is dead.

### 7.2 `JobRunner` — run-once and containment

`JobRunner.runDue(season, week, day, hour)` (`…/jobs/JobRunner.java:72-90`):

**Ordering.** Jobs are sorted by `order()`, then `key()` (`:52-54`). Spring does not guarantee the
order of a `List<Interface>` injection, so the sequence of financial effects would otherwise depend
on classpath scanning.

**Due-ness** (`isDue`, `:99-107`):

```java
if (job.week() != ANY_WEEK && job.week() != weekNumber) return false;
if (job.day()  != ANY_DAY  && job.day()  != dayNumber)  return false;
return hour >= job.hour();
```

The hour test is `>=`, not `==` — a job at 14:00 is not due at 09:00, which is the case that
necessitated the framework.

**Run-once** (`runOnce`, `:109-159`) keys on
`findBySeasonYearAndWeekNumberAndDayNumberAndJobKey`:

| Existing row | Outcome |
|---|---|
| `DONE` | `ALREADY_DONE`, skipped |
| `FAILED` | `FAILED`, **terminal — never retried** (`:124-130`) |
| none | run the job, then write `DONE` |

**Transaction containment.** Each job runs in its own `REQUIRES_NEW` transaction via a
programmatic `TransactionTemplate`, not `@Transactional` (`:55-62`). The comment explains why: the
calls are on `this`, Spring's proxy is bypassed by self-invocation, so `@Transactional(REQUIRES_NEW)`
would silently do nothing — and a throwing job would then roll back the record of every job that had
already succeeded, making them all run again.

`describeJobs()` (`:169-179`) exposes the full job table to `GET /api/jobs`.

### 7.3 The 13 registered jobs

| # | Key | Week | Day | Hour | Order | What it does |
|---|---|---|---|---|---|---|
| 1 | `day-opened` | any | any | 0 | 0 | **Log line only** (`DayOpenedJob.java:51-54`) |
| 2 | `recovery` | any | any | 6 | 10 | `zoneLoad.applyDailyRecovery()` |
| 3 | `finance` | any | **2** | 10 | 20 | `seasons.settleWeeklyFinancesForAllClubs()` |
| 4 | `league-table-reconcile-a` | any | **4** | 1 | 20 | Rebuild season-1 tables from played matches |
| 5 | `league-table-reconcile-b` | any | **8** | 1 | 20 | Same, after the day-7 matchday |
| 6 | `training` | any | **4** | 10 | 20 | `trainEveryClub` + squad environment `advanceWeek` |
| 7 | `cup-draw` | any | **2** | 8 | 30 | `seeder.drawRoundForWeek(week)` |
| 8 | `matchday-international` | any | **1** | 20 | 40 | Play all `INTERNATIONAL` fixtures |
| 9 | `matchday-league-a` | any | **3** | 19 | 40 | Play all `LEAGUE` fixtures |
| 10 | `matchday-cup` | any | **5** | 18 | 40 | Play all `CUP` fixtures |
| 11 | `matchday-league-b` | any | **7** | 16 | 40 | Play all `LEAGUE` fixtures |
| 12 | `week-rollover` | any | **7** | 23 | 90 | See below |
| 13 | `season-rollover` | **12** | **7** | 23 | 95 | Promotion, relegation, open new season |

Sources: `MatchdayJobsConfig.java:27-53`, `LeagueTableJobsConfig.java:14-32`, and each `impl/*.java`
`key()/week()/day()/hour()/order()`.

**Day semantics.** Matchdays are day 1/3/5/7; cup draw and finance are day 2; training and the first
reconcile are day 4; the second reconcile is day 8. Week rollover and season rollover are both at
**day 7 hour 23**, i.e. the very end of the week.

**`order()` 20 is shared by three jobs** — `finance`, `training` and both reconciles — so their
relative order is decided by the `key()` tiebreak, not by intent.

### 7.4 What each significant job does

**`MatchdayJob` (×4)** (`impl/MatchdayJob.java:83-110`) — selects competitions of its own
`CompetitionType`, selects `findUnplayedOnDay(season, week, day)` for them, dedupes the ids and hands
them to `AsyncSimulationRunner.simulateInBackground(ids)`. It **never creates fixtures** — it only
plays what already exists (`:24-27`). It is asynchronous and returns immediately; a job that blocked
on 300 matches would hold the transaction open.

**`CupDrawJob`** (`impl/CupDrawJob.java:78-85`) — `seeder.drawRoundForWeek(week)`. Fires day 2 at
08:00, three days before the day-5 cup ties (`:59-64`).

**`WeekRolloverJob`** (`impl/WeekRolloverJob.java:81-103`) — `applyWeekMaintenance()`, closes
finished loans, expires stale friendly requests, then in **week 2** generates the season's junior
intake and in every other week progresses active juniors, then simulates weekly transfer-market
activity.

**`SeasonRolloverJob`** (`impl/SeasonRolloverJob.java:66-82`) — first checks that **any** `LEAGUE`
competition exists; if not it warns and returns. The guard used to be "did we find Superliga
Srbije", "a Serbia-shaped question asked before a country-agnostic job" — a world with no Serbia
skipped the rollover for every country in it. Then `performPromotionRelegationAndNewSeason()`.

### 7.5 Promotion, relegation and opening the new season

`SeasonService.performPromotionRelegationAndNewSeason()` (`SeasonService.java:599-617`):

1. `applyPromotionRelegation(endingSeason)` — every country's divisions, tier ascending then
   `divisionLevel` then id (`:693-783`)
2. `agePlayersAndJuniorsOneYear()`
3. **clock: `currentSeason += 1`, `currentWeek = 1`**
4. `ensureActiveSeasonEntity()`
5. `openNewSeasonForEveryCountry(nextSeason)` — `ensureEntries` → `ensureDoubleRoundRobinSchedule`
   → zero the P/GF/GA/W/D/L for every division (`:632-644`)

The movement itself (`:785-869`): `movementSlots = childLeagues.size()`,
`safeCount = expectedTeams - movementSlots * 2`; positions `[safeCount, safeCount+slots)` go to the
playoff band, the next `slots` are relegated, the champion of each child league is promoted
directly, the runner-up enters the playoff. `resolvePlayoffWinner` reads the played week-11 playoff
fixture, and only if the scoreline does not decide it falls back to a **reputation-weighted coin
flip** clamped to `[0.35, 0.72]` (`:963-990`).

> `PyramidBuilder.addPromotionRuleForTopFlight` writes `PromotionRule` rows with
> **`targetCompetition = null`** — its own comment at `:360-367` admits "a relegated club leaves
> the top flight and does not arrive anywhere". And `PromotionRuleRepository` is **read nowhere**:
> `grep` finds only the repository, the entity, `PyramidBuilder` and `DatabaseInitializer`. These
> rows are never applied. The ladder that actually runs is `SeasonService.applyPromotionRelegation`.

---

## 8. The admin panel and world building

### 8.1 Where it lives

`AdminController` is **`org.example.commonmanager.controller.AdminController`**, not
`newLogic/controller` — the football controller package has 27 classes and none is an admin
controller. Base path `/admin` (`:24`), gated by `hasAnyRole("ADMIN","OWNER","DEV")`
(`SecurityConfig.java:137`).

It has **12 endpoints**:

| Method | Path | Effect |
|---|---|---|
| GET | `/admin/registration-requests` | Pending signups. **No frontend caller** |
| POST | `/admin/registration-requests/{id}/approve\|reject` | Creates the `User`, marks the team human-controlled |
| POST | `/admin/initialize-db` | Builds the **Serbian** structure |
| POST | `/admin/seed-other-nations` | Every non-activated country: divisions, clubs, ratings, table. **No players, no matches** |
| POST | `/admin/reset-db` | Clears football data. **Rebuilds nothing** |
| POST | `/admin/world-reseed?what=national-teams\|cup\|all` | Tops up squads / draws undrawn cup rounds |
| GET | `/admin/countries` | Per-country division counts and state |
| POST | `/admin/countries/{iso}/activate` | Full pyramid for one country |
| GET | `/admin/world-integrity` | Read-only integrity report |
| POST | `/admin/world-integrity/repair` | Re-seeds catalogue + squads, returns the **after** state only |
| GET | `/admin/database-job/status` | Poll the long admin job |
| DELETE | `/admin/transfer-list/{playerId}` | Force-unlist. **No frontend caller** |

The three heavy buttons return `202` but **run synchronously inside the request**
(`AdminDatabaseAsyncService.java:59-87`), despite the class name. The frontend polls
`/admin/database-job/status` once a second up to 240 times (`dashboard.js:826-846`).

The three DB buttons are also inconsistent in their guarantees: `verifyFootballWorldWasBuilt()`
throws only if `Competition` **or** `Team` count is 0, and deliberately does **not** assert a
minimum club count (`AdminDatabaseAsyncService.java:152-167`); and `world-reseed` with an unknown
`what` returns **HTTP 200 with an error body** (`WorldRepairService.java:57-60`).

### 8.2 The league skeleton — `PyramidBuilder`

`…/util/PyramidBuilder.java`. Per country:

| Constant | Value | Line |
|---|---|---|
| `DIVISIONS_PER_TIER` | `{1, 2, 4, 8, 16}` = **31 divisions** | `:52` |
| `CLUBS_PER_DIVISION` | **10** | `:54` |
| tier skill | 12/11/10/9/8 for tiers 1–5 | `:198-203` |

**310 clubs per country.** Across the 48-entry `CountryCatalog` (`CountryCatalog.java:37-95`,
47 named + `OTHER`) that is ~14,880 clubs.

Two entry points:

| Method | What it builds | Called by |
|---|---|---|
| `build(country, season)` (`:99-136`) | divisions + clubs + **25-player squads** + table + **fixtures** | `CountryActivationService.activate` |
| `buildStatic(country, season)` (`:226-249`) | divisions + clubs + table, **no players, no fixtures** | `SimulatedWorldSeeder.seed` |

Both guard on the database, not the state flag: if the country already has `LEAGUE` competitions
they return early (`:101-107`, `:227-233`).

Division fields (`:138-160`): `type=LEAGUE`, `scope=NATIONAL`, `teamType=CLUB`, `teamsPerCompetition=10`,
`reputationWeight = tier * 20`.

Club naming (`:343-348`):

```java
isoCode + " " + shortDivisionName(tier) + (division > 1 ? letter(division) : "") + " FC%02d"
```

e.g. `GER FirstA FC01`. The division letter is in the name **on purpose** — trimming produced
`GER First FC01` in two divisions and `findByName` throws on a duplicate (`:335-342`).

Ratings differ by path, and this matters:

| Path | `reputation` | Budget |
|---|---|---|
| `fillDivision` (active) | `40 + tier * 8` → 48/56/64/72/80 (`:320`) | `2_000_000 + (6 - tier) * 1_500_000` |
| `fillStaticDivision` (simulated) | `30 + tierSkill(tier) * 3` → 66/63/60/57/54 (`:301-304`) | same |

> **`Team.type` is never set by `PyramidBuilder`.** There is no `setType(...)` call in the class.
> Every club it creates has `type = null` — which is why
> `TeamRepository.findClubTeamsForOperations` has to query `t.type is null OR t.type = CLUB`
> (`TeamRepository.java:87-89`) and says so in its javadoc (`:56-63`). A club is effectively
> "anything whose competition is a LEAGUE".

`build` calls `ensureDoubleRoundRobinSchedule` for **every** division (`:127`). The comment at
`:118-126` records that an earlier version scheduled only the top flight, producing 30 divisions
with clubs and no fixtures.

### 8.3 The schedule

One generator: `SeasonService.ensureDoubleRoundRobinSchedule(competition, seasonYear)`
(`SeasonService.java:242-332`). Circle method, double round-robin:

1. **Idempotence** — returns early if any fixture for (competition, season) already has
   `roundNumber >= 1` (`:245-249`)
2. Teams come from the **`CompetitionEntry`** rows sorted by id; returns if fewer than 2 (`:251-258`)
3. Odd count gets a `null` bye slot (`:260-261`)
4. For 10 clubs: `rounds = 9`, `matchesPerRound = 5` first leg, then **9 return legs** → **18
   rounds** total, matching `SeasonCalendar.LEAGUE_ROUNDS`
5. Each pairing takes **week and day from `LeagueSlotSchedule.forRound(round)`** (`:289`). **If the
   calendar schedules no such round the fixture is skipped, not guessed** (`:290-294`)
6. `matchDate = clock.getCurrentDate().plusWeeks(week - 1)`, `played = false`
7. Return legs swap home/away and take `roundNumber + 9`, **re-reading the calendar for that round**
   (`:306-328`). The comment at `:314-317` records the bug this fixed: copying the first leg's week
   stacked four rounds into one week

Called from `PyramidBuilder.build`, `initSerbianFootballStructure`, `openNewSeasonForEveryCountry`,
`TeamController.getSchedule`, `CountryController.getLeagueSchedule`, and `AdvanceWeekAsyncService`
— the last of which has **zero callers** and should be deleted.

Note that `ensureEntriesForSeasonCompetition` is called from **read paths** as well
(`TeamController.java:256`, `CountryController.java:566,593,634,670,759`). Its javadoc
(`SeasonService.java:148-160`) records that it used to delete and rebuild every row on a membership
mismatch, wiping mid-season records; it now applies a set difference.

### 8.4 Results and how a played match is stored

`MatchdayJob` selects `findUnplayedOnDay(season, week, day)` and hands the ids to
`AsyncSimulationRunner`. `SimMatchService.persist` (`:189-`) then:

- builds a `Match` with `played/started/finished = true` (`:208-210`)
- sets `homeResultRevealed/awayResultRevealed = !involvesManager` (`:237-238`)
- saves, writes player stats, career totals and zone load
- **sets `fixture.played = true` and `fixture.playedMatch = match`** (`:270-271`)
- settles a level cup tie from the spot **only when `Competition.type == CUP`** (`:215-227`) — the
  comment at `:218-224` states there is no format column
- recomputes country ratings when the competition is `INTERNATIONAL` (`:279-286`); club Elo is
  deliberately not done here because the replay is world-wide (`:270-278`)

Table order is points ↓, goal difference ↓, goals scored ↓, team id ↑ (`LeagueTableOrder.java:49-82`).

### 8.5 Cups

**Domestic cup** — created in exactly one place:
`DatabaseInitializer.createCupCompetitionIfNotExists(serbia, "Kup Srbije", 64, season)`
(`DatabaseInitializer.java:1091`, impl `:1488-1507`), with `hasSeeding = true` and
`seededTeamsCount = 32`. **Only on the Serbian initialise path.**

**There is no `CupRound` entity.** A round is `MatchFixture.roundNumber` on a `CUP` competition,
and "drawing a round" means writing that round's fixture rows.

`CupFixtureSeeder` (`…/util/CupFixtureSeeder.java`):

| Constant | Value | Line |
|---|---|---|
| `CUP_WEEKS` | `{1,2,3,4,5,7,8,11}` — 8 rounds, final on week 11 | `:50` |
| `ENTRY_ROUND_TEAMS` | 108 | `:53` |
| `MAIN_DRAW_TEAMS` | 256 | `:54` |
| `CUP_DAY` / `CUP_HOUR` | 5 / 18 | `:58-59` |

`seedIfMissing()` (`:119-170`):

1. finds **the first** `CUP` competition by `findAll().stream().filter(...).findFirst()` (`:121-128`)
2. returns early if round-1 fixtures already exist (`:131-135`)
3. ranks clubs by **average squad rating** then name — but restricts to one country by comparing
   against `clubs.get(0).getCountry().getId()`, a **first-club heuristic** (`:185-187`)
4. **bails with a warning if fewer than 256 clubs**, leaving the cup empty (`:139-143`)
5. `directEntrantCount = max(0, ranked.size() - 108)`; **`firstKnockout` is the bottom 108**, the
   direct entrants the top N (`:157-160`)
6. draws **round 1 only** (`:161`)

The draw rule (`drawRound`, `:414-456`): re-rank survivors by squad strength, split top half vs
bottom half, **`home` is the non-favourite and `away` the favourite** (`:436-440`).

Rounds 2+ are drawn by `CupDrawJob` through `drawRoundForWeek` (`:264-284`). `survivorsOf`
(`:293-331`) reads the previous round's fixtures and **returns an empty list if the previous round
is empty or any tie is unplayed** — a deliberate "no draw rather than a draw against unqualified
teams". `winnerOf` (`:346-366`) resolves level scores from the penalty columns, and if those are
absent or equal it logs a warning and drops the club — **never a coin flip**.

### 8.6 International club cups

`InternationalClubCups` (`…/util/InternationalClubCups.java`) defines **5 tiers × 3 cups = 15
competitions** (`:93-101`):

| Cup | Places | Qualification |
|---|---|---|
| Champions Cup | 1 | Each country's **winner** |
| Masters Cup | best 2 | Seconds + thirds |
| Challenge Cup | best 1 | Fourths |

`ensureCompetitions()` (`:146-165`) creates each missing one with `type=CUP`,
**`scope=INTERNATIONAL`**, `teamType=CLUB`, and is idempotent by name (`:129-130`).
Qualification (`qualifyFrom`, `:319-337`) pools each country's divisions and sorts by
**`Team.reputation`**, reading the **finished** season's tables.

`InternationalClubCupDraw` (`…/util/InternationalClubCupDraw.java`) runs a real group stage —
`GROUP_SIZE = 6`, `GROUP_MATCHDAYS = 5` (`:68`, `:71`), then last-16 → QF → SF → 3rd → final across
weeks 1–11 (`:81`, `:89-93`). Groups are dealt serpentine (`:206-221`); knockouts wait until every
group fixture is played (`:299-304`) and draw **at most one round per call** (`:317-347`).

> **Both of these have no live production caller.** `ensureCompetitionsDurably()` is only reached
> from `ensureBaselineDataOnStartup` (`DatabaseInitializer.java:319`), which is no longer a boot
> hook, and `InternationalClubCupDraw` has no caller at all. The competitions are created but never
> populated.

### 8.7 Internationals and national teams

**International fixtures** — `InternationalFixtureSeeder` (`…/util/InternationalFixtureSeeder.java`):

- One competition named `"Internationals"`, `type=INTERNATIONAL`, `teamType=NATIONAL_TEAM`, created
  only if **no** `INTERNATIONAL` competition exists (`:66-69`) — so it is **one-shot per database,
  not per season**
- Window: `QUALIFIER_WEEK = 6` (`:45`), `GameDay.INTERNATIONAL_DAY = 1` (`:116`), kickoff 20:45 (`:47-48`)
- Entrants: `teams.findByType(NATIONAL_TEAM)`, filtered to names ending `"National Team"` (excluding
  U-21) and to sides with at least one player (`:79-96`)
- **Pairing is naive sequential** — `entrants[0]v[1], [2]v[3], …` (`:106-108`), one round only.
  The class javadoc states it plainly: "It is a single round, not the owner's 8-group qualifying
  structure. That structure needs 48 nations" (`:34-37`)
- Its only caller is the unreachable boot path (`DatabaseInitializer.java:410`). **No admin endpoint
  and no job redraws internationals.**

**National squads** — split across four classes:

| Concern | Class |
|---|---|
| Create the `Team` rows and their squads | `NationalTeamSeeder` |
| Fill a squad for a country with no clubs | `BotSquadGenerator` |
| Read/edit squad, selector rights | `NationalTeamService` |
| Appointments and elections | `NationalTeamAppointments`, `NationalTeamElectionService` |

`NationalTeamSeeder` (`SQUAD_SIZE = 25`, `:41`):

- `ensureSenior` (`:103-123`) — an **existing side is topped up, not skipped**; the comment names
  BIH, BRA, MKD, MNE, SVN as the five that were permanently empty
- `squadsFor` (`:149-193`) — takes the best 25 players **from that country's club squads** and
  writes **new `Player` rows copying** name/age/position/rating/form/skills (`:176-190`).
  `playerValue = 0`, and **`sourcePlayerId` is not set** — only `NationalTeamService.addToSquad`
  sets it (`:244`)
- if the country has no clubs, `BotSquadGenerator.ensureSquad` builds 25 deterministic players,
  guarded by `if (!findByTeamId(id).isEmpty()) return` (`:75-77`)

> **`seedIfMissing` is not idempotent for a country that has clubs.** `squadsFor` re-adds up to 25
> more copies on every call (`:149-193`). The 25-player cap is enforced on the *manual* write path
> (`NationalTeamService.addToSquad`, `:227-229`) but **not** on the seed path — it truncates to 25
> per call without checking the existing count.

### 8.8 World integrity

`WorldIntegrityService.report()` is `@Transactional(readOnly = true)` (`:58`) and returns a map;
`repair()` (`:102-111`) runs `catalogue.seedAll()` + `nationalTeams.seedIfMissing(...)`, logs
before and after, and returns **only the after-state** — the frontend therefore never shows the
before.

### 8.9 Seeders and backfills — idempotence summary

| Class | Idempotent? |
|---|---|
| `WorldCatalogSeeder.seedAll()` (`:86-141`) | **Yes** — re-asserts the name, writes state only when different |
| `WorldCatalogSeeder.dropLegacyRows()` (`:154-192`) | Yes — fixed ISO list, children before parents |
| `NationalTeamSeeder.seedIfMissing()` | **Partially** — team creation yes, squad population **no** |
| `BotSquadGenerator.ensureSquad()` | **Yes** — guarded on a non-empty squad |
| `BotLeagueStandardBackfill.backfill()` (`:72-127`) | **Yes** — `Random(team.getName().hashCode())` |
| `ResetService.resetDatabase()` (`:166-234`) | A wipe by design; preserves owner account + tactics |
| `ResetService.enforceOneSeasonCompetitionPerSeason()` (`:61-66`) | **Yes** — constraint lookup first |
| `DatabaseInitializer.backfillClubCountries()` (`:696-710`) | **Yes** — filter is "no country" |
| `DatabaseInitializer.refreshClubIdentities()` (`:737-743`) | **Yes** — unconditional re-assert |
| `DatabaseInitializer.backfillJuniorArrivalAges()` (`:796-805`) | **Yes** — filter is "is null" |
| `YouthAcademyService.assignMissingPositions()` (`:624-632`) | Only on a settled world — uses the class `Random` |
| `NationalRatingService.recomputeDurably()` (`:144-146`) | **Yes by construction** — pure replay from 1500 |
| `ClubRatingService.recomputeDurably()` (`:133-135`) | **Yes** — pure replay, skips unchanged rows |
| `LeagueFixtureDayBackfill.backfill()` (`:47-70`) | **Yes** — filter is "day is null" |
| `SeasonNumberBackfill.backfill()` (`:94-123`) | **Yes** — rewrites `season_year >= 1000` to 1 |

---

## 9. Defects and gaps found while writing this

These are observations from reading the code, verified where possible. Not fixed.

| # | Finding | Evidence |
|---|---|---|
| 1 | **`AGENTS.md` is wrong that boot writes nothing.** The owner user is re-created or rewritten on every boot, and three schema repairs run. | `StartupInitializer.java:53-63,146`; `DatabaseInitializer.java:191-201` |
| 2 | **A password changed in the app is reverted on restart.** `updateExistingOwner` unconditionally re-encodes the configured default. | `StartupInitializer.java:146` |
| 3 | **Any authenticated user can advance the world.** `/api/game-clock/advance` and `/api/jobs/run-due` have no role check; admin gating exists only in the dashboard buttons. No `@PreAuthorize` exists anywhere in `newLogic/controller`. | `APIController.java:59-89`; `dashboard.js:205-207`; grep returns nothing |
| 4 | **The season counter is incremented twice per rollover** — once by `advanceHour` on the week-12 wrap, once by `performPromotionRelegationAndNewSeason`. Every rollover skips a season. | `GameClockService.java:152-156`; `SeasonService.java:605` |
| 5 | **The `JobRun` guard row is written after the job body.** A crash in between leaves no row, so the job re-runs. | `JobRunner.java:140-145` |
| 6 | **`FAILED` is terminal with no re-queue path.** `runOnce` returns `FAILED` and never retries. | `JobRunner.java:124-130` |
| 7 | **Top scorers and top assists are permanently empty.** `StatsController:93` reads `goal_event`, and **that table has 0 rows** — as do all 23 `*_event` tables. The goals exist elsewhere: `match` carries 412 goals over 142 matches, and `match_player_stats` has 3,410 rows with 306 scorers. Verified against the live database. | `StatsController.java:93-94`; `pg_stat_user_tables` all 0 |
| 8 | **`Team.type` is never set by `PyramidBuilder`**, so "is this a club?" cannot be answered from that flag. | `PyramidBuilder.java` — no `setType`; `TeamRepository.java:87-89` |
| 9 | **`PromotionRule` rows are written and never read.** `targetCompetition = null`, and no reader outside the two seeders. | `PyramidBuilder.java:360-390`; grep finds no consumer |
| 10 | **`NationalTeamSeeder` is not idempotent** for any country that has clubs — up to 25 duplicate player copies per call. | `NationalTeamSeeder.java:149-193` |
| 11 | **International club cups are created but never populated**; international fixtures are seeded from an unreachable boot path. | `DatabaseInitializer.java:319,410`; `InternationalClubCupDraw` has no caller |
| 12 | **`clubProfileSettings` is a dead route** wired to a live menu item — renders "Page not found". | `dashboard.html:40`; `pages.js:628-629` |
| 13 | **`playerStats` and `teamStats` dispatch to the scorers/assists loaders.** Their real loaders exist and are exported on `window` but nothing calls them. | `pages.js:602-608,1119-1120` |
| 14 | **`/demo/*` fake-data routes are still live** in the router (`cup`, `international`, `friendlies`, `upcoming`), hardcoded to team 1. | `DummyDataController.java:73,81,88,118` |
| 15 | **The JWT secret is a hardcoded literal in source** and the owner credentials are prefilled in `login.html`. | `JwtUtil.java:15`; `login.html:28,32` |
| 16 | **`order() == 20` is shared by three jobs** (`finance`, `training`, both reconciles), so their relative order is a `key()` tiebreak rather than intent. | `JobRunner.java:52-54` |
| 17 | **`CupFixtureSeeder` needs 256 clubs in one country and silently gives up below that**, leaving the cup empty. | `CupFixtureSeeder.java:139-143` |
| 18 | **`AdvanceWeekAsyncService`** — 186 orphaned lines, zero callers, last holder of a hardcoded `findById(1L)`. | grep: no callers |
| 19 | **`dueAtMidnight()` is dead** — declared on `DayJob`, never read. | `DayJob.java:16-18` |
| 20 | **Three separate `currentUserTeamId` variables** from two independent `/auth/me` calls. | `dashboard.js:484`; `pages.js:232`; `demo.js:606` |
| 21 | **The logo is not keyboard-operable** — `role="button"` and `tabindex="0"` are set but only `onclick` is wired. | `dashboard.html:54` |

---

## 10. Open questions

Points where I could not settle the intent from the code alone, and did not want to guess:

1. **Which football mode is "the" football mode?** `home.html` offers two — graphical
   (`/dashboard.html`) and text-based (`/tifo.html`). This document covers the graphical one, as
   instructed. The text mode is a separate 55-class module (`org.example.footballtextmanager`, all
   `CS*`-prefixed) with its own `CSDataInitializer` boot hook, its own `CleanSheetController` at
   `/api/cs`, and its own 153 KB `tifo.js` — worth its own document.

2. **`/admin/registration-requests` and `/admin/transfer-list/{playerId}` have no frontend caller.**
   Are they intended for a UI that was not built, or are they dead?

3. **`ClubProfileSettings` — should the route exist, or should the menu item go?**

4. **`playerStats` / `teamStats`** — was routing them to the scorers loader intentional, or a copy-paste?

5. **The 48 vs 47 country discrepancy.** `CountryCatalog` has 48 entries including `OTHER("OTH")`.
   Should `OTH` be a buildable country, or a bucket only?

6. **`/api/cs` (text mode) shares the `User.cTeam` slot** with the graphical football team
   (`StartupInitializer.java:103-104` sets both `cTeam` and `tifoCTeam` to the same `CTeam`). Is
   one owner meant to run both modes as the same club?

7. **Season numbering after defect #4.** Fixing the double increment will renumber existing seasons.
   Do you want a data migration, or a fresh world?