# codeCleaning.md — refactor-only proposal

**Written 2026-10-09 against the working tree as it stands.** Nothing in this document has been applied. It
is a proposal, and every item in it is chosen because it can be done **without changing what the
application does**.

---

## 0. What this document is, and what it is not

### It is

A ranked list of changes that make the codebase cheaper to maintain: smaller units, one responsibility
per file, no copy-paste that can drift, no code that nobody calls. Every item states **what moves where**,
**why it is safe**, and **what proves it**.

### It is not

- Not a bug report. Where reading turned up a **live defect** I have listed it in
  [§8 — Found while reading, deliberately not folded in](#8-found-while-reading-deliberately-not-folded-in)
  and kept it out of the refactor list. A refactor commit that also changes behaviour is a commit nobody
  can review.
- Not a redesign. No new framework, no build step, no change to any endpoint, JSON key, CSS class,
  `data-` attribute or `window.*` name. **Every one of those is a contract**, and §2 lists the tests that
  read them as literal strings.
- Not a performance proposal. The N+1 loops and missing indexes are real and belong on `kanban.md` under
  `T2` with a measurement attached. They are named here (§7) so they are not lost, and not proposed as
  refactors.

### The one rule

> **A refactor task is not done until the diff contains no behaviour change and a reviewer can see why.**

This is `kanban.md`'s "a green status is not evidence" applied to maintenance work. The inverse rule
matters just as much here: **a refactor that needs a test to prove it did not break something is a
refactor of the wrong code.** The items in §9 are ordered so that the ones with no safety net come last
and are called out as such.

---

## 1. How the code is actually shaped

Measured, not estimated.

| Area | Files | Lines | Largest unit |
|---|---|---|---|
| Frontend (`static/js`) | 54 | 22,703 | `tifo.js` — 3,148 |
| `newLogic` (live Java) | 552 | 76,219 | `DatabaseInitializer.java` — 1,590 |
| `static/css/dashboard.css` | 1 | 5,747 | — |
| Frozen / out of scope | `demo/service` (3,349+ lines in the top 3 classes), `footballtextmanager`, `footballForDelete/` | | |

**The frontend is not one big file any more.** `pages.js` used to be the God module; it has already been
cut into `pages/views/*` (23 modules) and `pages/features/*` (7 modules), each exposing a
`create*View(deps)` factory. That work is real and good. What remains is:

- `pages.js` itself is **still 1,357 lines** — a router, a navigation history, a league context, a
  session cache, a world page and **61 `window.*` assignments**. It is the second God module.
- `tifo.js` is **3,148 lines and never got the same treatment.** It has no factory, no module boundary,
  112 top-level functions, and 26 `window.*` assignments. It is the largest God module in the repository
  and nobody has touched it.

**So the answer to "we have `pages.js`, maybe one more" is: yes, `tifo.js` — and it is 2.3× the size.**

### The shape of the remaining God modules

| File | Lines | Functions | What it is actually holding |
|---|---|---|---|
| `js/tifo.js` | 3,148 | 112 | state · HTTP · router · 14 page renderers · inbox parsing · attendance analytics · teletext builders · tactics persistence · 7 transfer mutations · round simulation · live animation |
| `js/pages.js` | 1,357 | 72 | router (47 cases) · nav history · league context · session cache · world page · 61 `window.*` exports |
| `js/pages-renderers.js` | 1,339 | 32 | 9 renderers, one of them 412 lines with 14 inner closures |
| `js/dashboard.js` | 1,031 | 43 | top-bar · session · 5 loaders · **and the admin DB job console** |
| `java …/util/DatabaseInitializer.java` | 1,590 | 48 | 9 responsibilities, **42 injected collaborators** |
| `java …/controller/CountryController.java` | 1,255 | 48 | **21 injected**, 12 of them repositories; ranking + sorting + DTOs inline |
| `java …/service/SeasonService.java` | 1,287 | 39 | **22 injected**; clock + fixtures + playoffs + rollover + promotion + finance |
| `java …/service/TransferService.java` | 1,373 | 56 | 12 injected; listing CRUD + market AI + offers + settlement + permissions |

Longest single functions, measured by brace matching (not by eye):

| Function | File | Lines |
|---|---|---|
| `tick()` | `sim/engine/MatchOrchestrator.java:321` | **313** |
| `stepBall()` | `sim/engine/BallPhysicsEngine.java:93` | 215 |
| `ensureBaselineDataOnStartup()` | `util/DatabaseInitializer.java:283` | 188 |
| `decideWithOptions()` | `sim/engine/decision/CleanDecisionEngine.java:135` | 139 |
| `persist()` | `sim/SimMatchService.java:308` | 135 |
| `loadPage()` | `js/pages.js:498` | 223 |
| `renderTableView()` | `js/pages-renderers.js:834` | 412 |
| `loadWorldPage()` | `js/pages.js:915` | 176 |
| `renderTactics()` | `js/tifo.js:2195` | 241 |
| `handleTool()` | `js/pages/views/admin-view.js:221` | 228 |
| `handleSeasonFlowResponse()` | `js/demo.js:300` | 149 |
| `loadJuniors()` | `js/pages/features/academy.js:240` | 324 |
| `playerContact()` | `sim/engine/BallPhysicsEngine.java:413` | 162 |

---

## 2. Before anything moves: what will break if we are careless

This repository has **nine tests that read `.js` files as text and assert on literal strings**. They are
good tests — they are the only thing standing between a refactor and a silent regression — and they will
**fail on a refactor that is otherwise correct.** That is not a reason to skip the refactor; it is a
reason to know the list.

| Test | Reads | Pins |
|---|---|---|
| `AdminActionsAreReachableTest` | `pages/views/admin-view.js` | `action === 'x'` strings vs `data-admin-action="x"` vs `action: 'x'`, **both directions** |
| `AdminJobsViewTest` | `pages/views/admin-view.js`, `css/dashboard.css` | `data-admin-tab="jobs"`, `panel.hidden = …`, `fm-squad fm-jobs-table` |
| `NationalWarmUpPanelRenderTest` | `pages/views/country-view.js` | endpoint strings, `buildNationalWarmUpHtml(level, warmUp.teamId`, and that `buildGeneralTab` does **not** call it |
| `TrainingViewNoShadowedDeclarationsTest` | `pages/views/training-view.js` | indentation-derived scope, `buildPlayerProfileHeroHtml`, `openPlayerGraph` |
| `ScheduleInteractionContractTest` | `pages-renderers.js` | `data-season-year`, `dataset.seasonYear`, `safe` parameter arity |
| `NotificationBellAlertTest` | `js/notifications.js` | `bell.classList.toggle('has-unread', unread > 0)`, `badge.textContent = unread > 99 ? '99+' : String(unread)`, `lastSeenUnread` handling |
| `LoanNotifiesAndOffersTheRightActionTest` | `pages/features/loans.js` | `data-loan-action="activate"`, `Take him in`, `action === 'activate'` |
| `ClubMilestonesRenderHonoursTest` | `pages/views/utils.js` | `MEDAL_ORDER`, `fm-honour--`, `No medals yet.` |
| `WorldPageNavigationTest` + `SidebarBindingTest` | `static/js/**`, `dashboard.html` | every `onclick` target resolves to a `window.` binding; no `app.js` |

**Consequences for this proposal:**

1. `renderTableView(payload, { … escapeHtml, formatGoalDiff })` — the `safe` parameter is checked for
   arity. Splitting it must keep the injected names and the `safe = escapeHtml || htmlEscape` pattern.
2. `admin-view.js`'s `handleTool` **cannot** become a lookup table of `{ action: handler }` without
   either (a) keeping the literal `action === '…'` comparisons, or (b) rewriting that test first. The
   test is right and should be rewritten to match the new shape — as a separate commit, with its own
   justification.
3. `notifications.js`'s badge expression is asserted **verbatim**. The duplicated `'99+'` computation
   (§4.9) can only be consolidated by also changing that test, in its own commit, after confirming the
   consolidated expression is character-identical in behaviour.
4. `SidebarBindingTest` scans **every** file under `static/js` for `onclick="…"` targets that have no
   `window.` binding. Moving a function between files while keeping its `window.*` export is safe.
   Renaming an exported global is not.

**Two other constraints, both from `AGENTS.md`:**

- **Never** flip a default to make a shell start convenient, and **never** "fix" the
  `WE_HAVE_BALL` / `OPPONENT_HAS_BALL` mirroring. Both are owner decisions.
- **`demo/service/` is frozen.** Not one item below touches it.

---

## 3. `pages.js` — the file the owner named

**Diagnosis: this is no longer a God *class*, it is a God *module*, and its two halves have different
fates.**

### 3.1 What is actually in it

| Lines | Responsibility |
|---|---|
| 41–61 | 21 module-level mutable variables — the entire client-side session cache |
| 63–140 | League + country context (`selectedCountryIsoCode`, `activeLeagueId`, `setActiveLeagueContext`, …) |
| 142–245 | Navigation: `buildPageNavState`, `sameNavState`, `pushNavState`, `renderNavState`, `goBackSmart` |
| 247–300 | Session: `loadUserTeamId`, `ensureUserTeamId`, `ensureCurrentLeagueId` |
| 303–317 | A document-level click listener |
| 319–497 | **Construction of all 22 view factories** — the composition root, and the one thing that genuinely belongs here |
| 498–720 | `loadPage()` — a 47-case `switch` |
| 728–774 | `PAGE_NAMES` + `describePage()` |
| 776–1257 | 30 thin delegating wrappers (`loadX() { return xView.loadX(); }`) |
| 859–1090 | **`loadWorldPage()` — 176 lines of markup and wiring** |
| 1266–1357 | 61 `window.*` assignments |

### 3.2 The proposals

#### P-1 · Extract the navigation history into `js/nav-history.js` — **safe, do first**

`buildPageNavState` … `goBackSmart` (142–245) is a self-contained data structure with exactly one
dependency: it calls back into `loadPage` / `loadPlayer` / `loadMatch` / `loadFixture` /
`loadLeagueTeam(…)`, all of which are already injectable through the factory pattern used everywhere
else in this codebase.

```js
// js/nav-history.js
export function createNavHistory({ loadPage, loadPlayer, loadMatch, loadFixture,
                                   loadLeagueTeam, loadLeagueTeamPlayer, loadDashboard,
                                   restoreLeagueNavState, isLeaguePage }) {
    return { current: …, push(nextState), goBack(fallback), render(state) };
}
```

- **Zero behaviour change:** the six call sites keep their exact signatures.
- **Why it is first:** it is the only block with *no* markup and *no* fetch in it, so the diff is
  reviewable line-by-line.
- **Exit criteria:** `node --check` passes; `window.goBackSmart` still bound and still pops; Back on the
  country page still returns to World; Back on a club profile's league still returns to the profile
  (these three are the paths `buildPageNavState`'s own comments say were broken before).

#### P-2 · Extract the session cache into `js/session.js` — **safe**

The 21 variables at 41–61 and the functions at 247–300 are one thing: *who am I, and where am I looking*.
That is a module with a getter per field, not 21 loose `let`s scattered through a router file.

```js
// js/session.js
export function createSession({ authFetch, handleAuthFailure, paintAccountMenu }) {
    return { teamId(), teamName(), username(), role(), competitionId(), competitionName(),
             competitionTier(), seasonYear(), countryName(), countryIsoCode(),
             ensureTeamId(), reload() };
}
```

The views already receive `getTeamId: () => currentUserTeamId` as a thunk. Replacing the thunk's body
with `() => session.teamId()` changes **nothing** at any call site — which is precisely why this is safe.

- **Do not** change the field names or the `/auth/me` payload keys. They are read by three other packages.
- `paintAccountMenu(user, currentUserCompetitionName || '')` moves with it, so the top bar cannot stop
  being painted.

#### P-3 · Move `loadWorldPage()` into `pages/views/world-view.js` — **safe**

It is 176 lines, it fetches exactly two endpoints, and it already has the shape of every other view. It
is the only page still living inside the router. Give it the same factory signature and register
`case "world": await worldView.load()`.

- Its `data-world-country` / `data-club-cup` / `data-national-level` attributes are **contracts** —
  `country-view.js`, `club-cup-view.js` and `national-tournament-view.js` are reached through them.
  They do not change.

#### P-4 · The 30 delegating wrappers — **delete, not move**

```js
async function loadJuniors()  { return academyFeature.loadJuniors(); }
async function loadLoans()    { return loansFeature.loadLoans(); }
```

Each exists only because `loadPage`'s `switch` names a local. When P-3 and P-1 land, the `switch` can
call `academyFeature.loadJuniors()` directly and the wrapper disappears. That removes ~150 lines of
`return f();`.

**Order matters:** P-4 is the *last* step of this file's refactor, not the first. It deletes the thing
P-1/P-2/P-3 make unnecessary.

#### P-5 · The 61 `window.*` assignments — **annotate, do not remove**

Every one of them is load-bearing: `dashboard.html:70,72,80` calls `window.loadDashboard`,
`admin-view.js:432` calls `window.resetDatabase`, and inline `onclick` strings across 11 files call
`loadLeagueTeam` (7×), `loadLeagueTeamPlayer` (5×), `loadPage` (9×).

The cleanup is not "delete them". It is:

1. **Group them** under one comment block that states the contract: *these exist because the dashboard
   and the club/league renderers emit inline `onclick` handlers; `SidebarBindingTest` enforces the
   pairing.* Right now the reason is scattered across five comments in five places.
2. **Delete only the two that nothing binds.** `window.loadRecentMatches` and `window.loadHomeTeamStats`
   (`dashboard.js:1030-1031`) have **no consumer anywhere in `static/` or `src/test`** — both are called
   internally at `dashboard.js:610-611`. Verified by repo-wide search.
3. **Keep every name.** Renaming a global breaks an `onclick` string in a file you are not reading.

#### P-6 · `loadPage()`'s 47-case switch — **leave the shape, fix the smell**

47 cases in one `switch` is not the problem; the problem is that **14 of the cases are aliases**:

```
formations / tactics      → loadFormations()
training / trainingSetup  → loadTrainingSetup()
schedule / fixtures       → loadFixtures()
playerStats / topScorers  → loadTopScorersAndAssists("scorers")
teamStats / topAssists    → loadTopScorersAndAssists("assists")
```

Two options, and the choice belongs to the owner:

- **(a) Leave it.** A `switch` with 47 arms is verbose but obvious, and it is one screen you can read
  top to bottom.
- **(b) Collapse aliases** into `case 'a': case 'b':` and add an alias table.

I recommend **(a)**, for one reason: `PAGE_NAMES` (728–770) is keyed by the same ids, so a page's name is
already in one table, and a second table of aliases would be a third place to keep in sync. **Aliasing is
where the "every screen once rendered the wrong thing" bugs of this repository came from.**

---

## 4. `tifo.js` — the one nobody has started

3,148 lines, 112 top-level functions, 26 `window.*` assignments, no module boundary, **no test coverage
in `src/test`** (searched — the only tests that touch these filenames are `StaticPageAccessTest` and the
`onclick`-resolution scan). It is reachable only from `home.html:33` → `/tifo.html`.

### 4.0 Read this before scheduling anything here

> **There is currently nothing that would catch a regression in `tifo.js`.** Not a unit test, not a
> render harness, not the `render-country-view.mjs` pattern that exists for `country-view.js`.
>
> `AGENTS.md`: *"A test that cannot fail proves less than no test."* Right now, every item in §4.2–§4.7
> would be shipped blind.
>
> **So §4.1 is a prerequisite, not an optional extra.** Do not refactor `tifo.js` before the harness
> exists.

### 4.1 P-7 · Build a `node` render harness for `tifo.js` — **prerequisite**

There is already a proven pattern in this repository: `src/test/resources/js/render-country-view.mjs`
loads the real module with imports stripped, stubs `document` and `fetch`, and
`CountryPageRendersWithoutReferenceErrorTest` drives it through Maven with `assumeTrue(nodeIsAvailable())`.

Copy that shape for `tifo.js`'s 14 `renderPage` cases. It will find real defects immediately — see §8.2,
where `fixturePreview` emits the literal text `escapeHtml(homeName)` in **seven** places.

- **Effort:** roughly what `country-view.js` already cost.
- **Exit criteria:** `mvn test -Dtest=TifoRendersWithoutReferenceErrorTest` is green, **and you have
  deleted one `${` from the fixture-preview template and watched it go red.** That second half is the
  rule from `kanban.md`, and it is not optional.

> Note: `node` is **not on `PATH` in the agent shell** but is installed at `/usr/local/bin/node` (v22.17.1).
> `assumeTrue(nodeIsAvailable())` skips silently on a machine where it is not — confirm it actually ran.

### 4.2 P-8 · Split by responsibility cluster — **safe once P-7 exists**

The clusters are already clean; they were just never separated. Each becomes a module:

| Lines | Cluster | New file |
|---|---|---|
| 4–16 | state (13 globals) | `tifo/state.js` |
| 19–31 | `csApi` fetch wrapper | `js/http.js` (see P-13) |
| 86–182 | index building | `tifo/indices.js` |
| 185–225 | `renderPage` router | stays — becomes the composition root |
| 264–491 | match/attendance analytics | `tifo/attendance.js` |
| 493–791 | inbox parsing + teletext builders | `tifo/inbox.js`, `tifo/teletext.js` |
| 794–2435 | 11 page renderers | `tifo/pages/*.js` |
| 2437–2841 | tactics + transfers + round sim | `tifo/transfers.js`, `tifo/tactics.js` |
| 2852–3055 | live teletext animation | `tifo/live.js` |
| 3057–3148 | delegated click router + `window.*` | stays |

**Rule for the split: every `window.*` name keeps its binding at the same path.** `tifo.html` calls 13 of
them from inline markup. Nothing about the HTML changes.

### 4.3 P-9 · Delete the dead code first — **safest possible win**

Verified by repo-wide search across `static/` and `src/test`:

| Symbol | Line | Evidence |
|---|---|---|
| `buildTeletextRoundDigestHtml` | `tifo.js:686` | 1 hit — its own declaration |
| `buildTeletextMatchReportHtml` | `tifo.js:736` | 1 hit |
| `showHalfTimeModal` | `tifo.js:2852` | 1 hit |
| `window.tifoFixturePreview` | `tifo.js:3130` | 1 live hit + 1 in a comment at 1676 |
| `window.tifoOpenRankedPlayer` | `tifo.js:3133` | 1 hit — the delegated handler at 1705 calls the local directly |
| `isEligibleForRole` | `tifo.js:2233` | 1 hit |
| `starterOptions` / `benchOptions` | `tifo.js:2263`, `:2269` | 1 hit each |

`showHalfTimeModal` is the interesting one: `window._pendingFullTimeContinue` is written **only** at 2870
inside it and read only at 3144 — so `window.tifoContinueFromHalf` (3142–3148) can never fire. Three dead
symbols in a chain, and a button at 2866 that does nothing.

> **Read `AGENTS.md`'s rule before deleting:** *"WE_HAVE_BALL and OPPONENT_HAS_BALL are identical on
> purpose."* These seven were verified by symbol search across the whole repo, which is the same standard.
> But confirm each one is not reachable from a **console** call the owner makes — `window.tifoFixturePreview`
> in particular. Ask before removing an exported global, even an unreferenced one.

### 4.4 P-10 · Collapse the duplicated renderers — **safe, mechanical**

Verified near-identical pairs:

| Duplication | Locations |
|---|---|
| `renderTopScorers` vs `renderTopAssists` | `tifo.js:2464` / `2488` — identical but for endpoint, heading, empty text, `p.goals`/`p.assists` |
| Control-percentage formula, verbatim | `tifo.js:636-646` and `tifo.js:2128-2138` |
| `posOrder = { GK:0, DEF:1, MID:2, WNG:3, ATT:4 }` | `tifo.js:1372, 1407, 1536, 1907` — and 3 more across `training-view.js:503`, `match-view.js:507`, `formations-view.js:38` |
| Roster row markup + inline style | `tifo.js:1375-1400, 1424-1445, 1548-1566` |
| Transfer POST → error → alert → re-render | `tifo.js:2641-2741`, six times, same 4-line tail |
| Budget write-back | `tifo.js:2683, 2707, 2722, 2737` — same line four times |
| `<select>` change handlers | `tifo.js:2377, 2391, 2408, 2422` — mobile/desktop branches byte-identical apart from indentation |

`posOrder` is the one to fix first: **seven literal copies across three files**, each a place to forget a
position. One exported `POSITION_ORDER` next to `escapeHtml` in `ui/` — the pattern already exists in
this codebase and already solved this exact problem for escaping.

`renderTopScorers`/`renderTopAssists` becomes `renderRankedTable({ endpoint, heading, valueKey, emptyText })`.
That is a pure function of its four arguments.

### 4.5 P-11 · `renderTactics()` — 241 lines, 13 closures — **last, and carefully**

Its internals: a state machine (2202–2260), two **dead** option builders (2263–2273), two
near-identical renderers (2313–2354), and four near-identical `change` handlers (2376–2434) that each
re-write `gameState.tactics = { … }` verbatim. It also branches on a hard-coded media query at 2372–2376.

It is the single worst function in the repository and it is **not** the first thing to touch: it has no
coverage, and the four handlers write shared state on every keystroke. Split it *after* P-7, *after* P-9
has removed the dead closures, and one handler at a time.

---

## 5. The rest of the frontend

### 5.1 P-12 · `pages-renderers.js` — `renderTableView` is 412 lines

`renderTableView` (`:834`) is one function containing **14 inner closures** (`zoneClass`,
`formatRatingDelta`, `ratingDeltaClass`, `ownershipBadgeHtml`, `managerLinkHtml`, `standingsRowsHtml`,
`fixturesHtml`, `statTableHtml`, `formatAttendance`, `iconCount`, `milestoneCardHtml`,
`milestoneBoardHtml`, `summaryListHtml`, `seasonSummaryBoardHtml`) and a 95-line template literal.

**The closures are not the problem — they are well-factored.** The problem is that they close over
`safe`, `rows`, `fixtures`, `milestones` and `seasonSummary`, which makes them untestable in isolation and
unmovable to another file.

Proposal: leave the closures alone. Move `zoneClass`, `formatRatingDelta`, `ratingDeltaClass` and
`iconCount` out — they take only their arguments and touch nothing. That is ~30 lines removed and the
rest becomes reviewable as one unit.

> **Constraint:** `ScheduleInteractionContractTest` checks this file for `data-season-year`,
> `dataset.seasonYear` and `safe` parameter arity. Read the test before the edit.

### 5.2 P-13 · One authenticated `fetch`, not three

| Copy | Location |
|---|---|
| **canonical** | `js/auth.js:184` `authFetch` |
| duplicate | `js/tifo.js:19` `csApi` |
| duplicate | `js/roundResultsTeletext.js:40` `teletextFetch` |

Both duplicates re-implement token attach, 401/403 message mapping and the `X-Requested-With` header that
`authFetch` already sets. `clock.js:1-10` carries a comment recording that a *third* local copy was
already removed — **the same consolidation was done for `escapeHtml` and never finished for `authFetch`.**

Also five separate `sessionStorage.getItem('token')` reads (`tifo.js:20`, `tifo.js:35` — twice in one
file — `dashboard.js:487`, `demo.js:597`, `roundResultsTeletext.js:41`).

- **Verify before doing it:** `csApi` and `teletextFetch` may map 401/403 differently. Diff the two
  against `authFetch` line by line. If the message differs, that difference is either deliberate (and
  gets a comment) or a bug (§8) — **do not silently unify it.**

### 5.3 P-14 · `dashboard.js` — extract the admin DB console

`dashboard.js:773-876` is an unrelated responsibility bolted onto a dashboard renderer: reset, seed,
initialize, a loading popup built from **ten imperatively-assigned style properties** and a 17-line
inline-style `<div>`, and a nested poll loop (`240` attempts × `1000 ms`).

It duplicates `demo.js:24`'s `showModal` and `tifo.js:228`'s overlay. Extract to
`js/admin-db-jobs.js`. Zero risk: the only entry points are `window.resetDatabase` /
`window.initializeDatabase` / `window.seedOtherNations`, all consumed at `admin-view.js:383,432`.

### 5.4 P-15 · `demo.js` — 14 storage functions and 9 polling functions where 3 of each will do

| Pattern | Copies | Lines |
|---|---|---|
| `persistJob` / `clearJob` / `hasJob` | **9** — three per job kind | 130–248 |
| `stop*Polling` | 3 | 450, 457, 526 |
| `poll*Status` | 3 — identical 19-line shape, different endpoint and label | 464, 492, 533 |
| `start*Polling` | 3 — identical, all at `1500 ms` | 484, 518, 554 |

All nine are signature-compatible with `persistJob(key, id)` / `clearJob(key)` / `hasJob(key)` and
`startPolling({ endpoint, buttonId, fallbackLabel, onTerminal })`. Pure collapse.

**Two cross-file items in the same file, which are the more important half:**

- `DASHBOARD_FLOW_FLASH_KEY` and `DASHBOARD_WEEK_CONSUMED_KEY` are **declared in both `dashboard.js`
  (18–19) and `demo.js` (10, 15)**, and `isWeekConsumed()` is **byte-identical** in both
  (`dashboard.js:160-167`, `demo.js:241-248`). Two ES modules coordinating through `sessionStorage` by
  agreeing on hardcoded strings, each holding its own copy of the constant. **A typo in one is silent.**
  Five button labels (`'⚽ Watch Your Match'`, `'📅 Advance Week'`, …) are likewise written out in both.
- `demo.js:693` calls `refreshGameClock()` under a `typeof === 'function'` guard — and **`refreshGameClock`
  is defined nowhere in the repository.** The guard can never pass. That is a live defect (§8.4); the
  refactor is to extract the storage keys, the defect is a separate task.

### 5.5 P-16 · `academy.js` — `loadJuniors()` is 324 lines

One function holding 20 locals, 7 inner closures (`statusColor`, `actionButton`, `renderStatus`,
`renderRows`, `renderSection`), a 90-line template and two event-binding blocks. The inner closures are
good. The problem is that they close over `academy`, `archive`, `currentSeason`, `canDecide`, `week`.

It also reads five `maxActive` / `intakeMax` / `decisionWeek` thresholds with a `?? 10` fallback each
(`academy.js:266-270`), while `YouthAcademyService` names the same five as constants — the class comment
at `YouthAcademyService:347-353` **records this duplication on the Java side already**.

- **First:** pull the five `??` defaults into one `ACADEMY_DEFAULTS` object so a server rename and a
  client fallback cannot disagree.
- **Then:** extract `renderSection` + `renderRows` (lines 299–375 by offset) to module scope with
  explicit arguments.

### 5.6 P-17 · Small things, all verified, all zero-risk

| Item | Evidence |
|---|---|
| `formatAttendance` duplicated verbatim | `tifo.js:331` and `pages-renderers.js:987` — identical bodies, differ **only** in fallback (`'-'` vs `'—'`). **Pick one and note which; that changes rendered output by one character, so this is a decision, not a refactor.** Raise it, don't decide it. |
| `formatMoney` ×4 | `tifo.js:247`, `club-management.js:215`, `stadium-view.js:637`, plus `formatBudget` in `views/utils.js:115` — **four different implementations of "print money"**, including one that escapes and one that doesn't |
| `back-to-dashboard` written by hand | **42 occurrences** across 19 files, while `backButtonHtml()` exists in `ui/components.js` for exactly this |
| `fm-panel-head` | 123 occurrences; `fm-page fm-page--` 58; `fm-squad` 66. A `panelHtml({ title, action, body })` helper would remove the majority. |

> On the 42 back buttons: `ui/components.js`'s own header comment says *"The Club profile writes this
> button by hand rather than calling a helper — see `club-view.js` — so the two are the same button either
> way."* That is a good comment, written by someone who knew. It is not an argument for leaving 42 copies;
> it is an argument that the consolidation was identified and deferred. Consolidating them is 42 one-line
> edits with a shared string; **it must not change `data-nav-back` values**, which are behaviour.

### 5.7 Deliberately **not** proposed

| File | Why |
|---|---|
| `js/notifications.js` (498) | One responsibility, documented `window` contract, no local `escapeHtml`, no local `fetch`, no globals for internal state. **This is the model the other files should follow.** The only item is the `'99+'` duplication, and §2 explains why consolidating it requires touching a test first. |
| `js/roundResultsTeletext.js` (463) | Same. The only real finding is the kickoff-phase constants inlined in two functions (`70 * 1280`, `10 * 1820`, `8 * 3040` at 356–367, recomputed at 376) — worth naming, low value. |
| `js/zox-match-preview.js` (311) | Best-organised large file in the set: rendering separated from data, six single-purpose section renderers. **It has an escaping gap (§8.3) — that is a defect, not a refactor.** |
| `js/clock.js` (147) | Its only export is a side effect (`window.__fmWatchStatus`), and the reader caches it forever (§8.5). Small file, but the watch-gating *policy* duplicates a server rule in 34 lines. That is an architecture conversation, not a cleanup. |

---

## 6. The backend — `newLogic` only

`demo/service/` is frozen. `footballtextmanager` is referenced only to note a live coupling (§7.3).

### 6.1 P-18 · `DatabaseInitializer` — 1,590 lines, **42 injected collaborators**, 9 responsibilities

| Lines | Responsibility |
|---|---|
| 161–169 | raw DDL (`jdbcTemplate.execute`, `ALTER TABLE`) |
| 191–204, 473–568 | boot / reset / clear / rebuild entry points |
| 574–620, 810–871 | **user account creation — two near-identical blocks** |
| 658–683, 1059–1093 | Serbian pyramid structure |
| 1095–1181 | tiered leagues + promotion rules + cup |
| 1260–1416 | divisions, clubs, entries, squads |
| 1429–1505 | club identity, talent profile, name normalisation |
| 1507–1540 | random club-name generator |
| 922–1051 | tactics profile snapshot/restore **to disk** |

**This is the worst God class in the repository** and it is a smaller problem than it looks, because the
seams are already visible.

- **P-18a · Split first, logic untouched.** `WorldSeeder` (pyramid + clubs + squads) ·
  `OwnerAccountProvisioner` (the two account blocks) · `ClubIdentityService` (badge/stadium/talent) ·
  `TacticsProfileSnapshotStore` (disk I/O) · `SchemaRepair` (the two DDL lines). `DatabaseInitializer`
  keeps the orchestration and the admin button entry points.
- **P-18b · Reduce the 42 collaborators** as each service is extracted. 42 is the number that makes this
  class untestable and impossible to reason about; it does not become 12 by editing, only by splitting.
- **P-18c · `@Transactional` on two `private` methods** (`DatabaseInitializer:536`,
  `:740`). **Spring's proxy cannot see a private method, so both annotations are inert.** Either make
  them package-private or move the boundary to the caller. **Verify before changing:** if the intent was
  that these run in their own transaction, the current code is not doing that — which is a behavioural
  question, not a refactor one. Report it; change it under a separate decision.
- **P-18d · `seedWorldBeforePyramid()` is called twice in a row** (`DatabaseInitializer:444-445`). Verified
  by reading. Before deleting one call, establish it is idempotent — if it is not, this is a live defect
  and the fix is not "remove the duplicate".

### 6.2 P-19 · `CountryController` — 21 injected, 12 of them repositories

A controller holding repositories directly, doing ranking (`clubRanking:253-317`, `ranking:320-371`),
table sorting (`getLeagueTable:847-897`), election authorisation (`isElectionAdmin:793-802`) and cup
qualification rules (`qualifying:1157-1206`). Seven identical
`competitionRepository.findById(leagueId).orElseThrow(…)` blocks at 822, 849, 946, 981, 1043, 1064, 1081.

**Proposal, in order:**

1. **Extract the pure ranking/ordering code.** `clubRanking` and `ranking` take repositories as arguments
   today; extracted, they take lists. That makes them unit-testable and moves the business rule out of
   the HTTP layer. **No behaviour change, and the diff is trivially reviewable.**
2. **Extract one `LeagueViewService`** owning the repeated preamble — the seven `findById`-and-throw plus
   the seven season-resolution preambles (`seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear()`).
3. **`toPredictionMap` is byte-identical in two controllers** — `CountryController:1049-1060` and
   `TeamController:710-721`, verified by reading both. It belongs on `ScheduleInsightService.Prediction` as
   `toMap()`, next to the record it maps.
4. **`displayNameOrLogin`** exists as a private helper in `CountryController:936` and hand-inlined at
   `NationalTeamElectionService:358` and `:470`, `NationalTeamService:144`, `ModerationService:198`,
   `NotificationService:238`, `ForumService:440,448`, `MessageService:334`. One method on `User`.

> **`qualifying` and `isElectionAdmin` are flagged, not proposed.** Moving cup-qualification rules out of
> a controller is correct, but the rules themselves are an owner decision. Do not touch them in a
> refactor commit.

> **Four `@GetMapping` handlers write** — `ensureEntriesForSeasonCompetition` is called at `CountryController:825`,
> `:852`, `:949`, `:1084`. `getLeagueSchedule` had the same call and it was **removed** at `:987` with a
> comment recording that it "was a bug". The other three still have it. **This is a behavioural question,
> not a refactor.** Report it on `kanban.md`; do not quietly change four endpoints.

### 6.3 P-20 · `SeasonService` — 22 injected, 39 methods

`advanceWeekAndHandleSeasonTransition` (`:473`, 66 lines) calls 12 other services. That is an
orchestration method, and it is fine to have one — what is not fine is that it has nowhere to live except
inside a class that also owns the game clock.

- **Extract a `WeekRollover` coordinator** holding `advanceWeekAndHandleSeasonTransition`,
  `applyWeekMaintenance` and the season-transition branch. `SeasonService` keeps clock, fixtures,
  playoffs and table ordering.
- **N+1 to name but not to fix here:** `applyPromotionRelegationForCountry` (`:811`) does
  `findByCompetitionAndSeasonYear` + `findBySeasonCompetition` per child league, inside a tier loop,
  inside a country loop — ×31 divisions ×48 countries. `openNewSeasonForEveryCountry` (`:730`) calls three
  services per division in a nested loop. Both are `T2` board items with a measurement attached.
- **`@Transactional` on `protected` methods called via self-invocation** (`SeasonService:620`, `:653`):
  the calls at `:585-591` are self-invocations, so the annotations do not apply at that call site.
  Same class of finding as P-18c — report it, decide it separately.

### 6.4 P-21 · `TransferService` — 1,373 lines, 56 methods

Twelve injected collaborators. The refactor that pays for itself:

| Duplication | Locations (verified) |
|---|---|
| `playerRepository.findById(playerId).orElseThrow(PLAYER_NOT_FOUND)` | 4× — `:102`, `:130`, `:259`, `:436` |
| `requireSeller(Transfer, …)` / `requireSeller(Player, …)` | `:777` / `:808` — same two throws, same messages. **The javadoc at 794–807 explains why they were split (four methods had hand-rolled a weaker guard). Consolidate behind `Player`; keep the comment.** |
| DTO assembly | 3 separate mapping blocks |
| Weekly AI market simulation | `:637-672`, `:1019-1135`, `:1171-1227` |

Note `TransferService:1015` has `private double round2` — **see P-22.**

### 6.5 P-22 · One `round2`, not 25

**`round2` is defined 25 times across `newLogic`.** Verified by grep. Every one is
`Math.round(v * 100.0) / 100.0`:

`AdmissionService:311` · `CoachWage:97` · `ZoneLoadRecorder:192` · `AttendanceService:275` ·
`JuniorDevelopment:244` · `AcademyQuality:108` · `ScheduleInsightService:296` · `TransferFeeService:151` ·
`TransferService:1015` · `WeeklyFinanceService:392` · `PlayerContractService:382` **and `:432`** (nested
class, so two in one file) · `TransferBudgetService:151` · `NegotiationService:474` · `ClubNeedService:326` ·
`BoardExpectationService:230` · `TransferListingFeeService:109` · `YouthAcademyService:1011` ·
`FinanceLedgerService:186` · `JuniorSchoolService:236` · `TrainingProgressionService:650` ·
`TalentRange:203` · `ListingObjectionService:219`

Twenty-five copies of a rounding rule that **the frontend rounds differently** — `formatPercent`
("two decimals is the house rule for any decimal"), `formatBudget` (capped at 2),
`formatGoalDiff`. The backend rounds to 2, the frontend rounds to 2, and nobody wrote down that they
must agree.

> **Proposal:** one `Rounding.to2(double)` in `newLogic/util`, mirroring `escapeHtml`'s role on the
> frontend. Mechanical, 25 deletions, no behaviour change **provided every copy is genuinely identical** —
> check for `Math.round(x * 10.0) / 10.0` variants (`ZoxApiController` uses those too) before collapsing.

### 6.6 P-23 · The sim engine — extract, do not restructure

The two monsters are `MatchOrchestrator.tick()` (313 lines) and `BallPhysicsEngine.stepBall()` (215).
Neither is safe to split blind, and neither needs to be split today.

**What is worth doing, because it is copy-paste with no domain judgement in it:**

| Finding | Locations |
|---|---|
| `pointSegmentDistance` **reimplemented twice** | `SimUtils:47` exists; `BallPhysicsEngine:724` and `BallPhysicsProbe:102` are copies. **`SimUtils`'s own javadoc says it exists precisely because this copy was made.** Delete the two, call the shared one. |
| `pointToLineDistance` — a third geometry copy | `CleanDecisionEngine:995` |
| `winnerOf(MatchFixture)` — **three near-identical implementations** | `InternationalClubCupDraw:642`, `NationalTournamentSeeder:512`, `CupFixtureSeeder:331` — verified by reading all three; same null check, same score compare, same penalty fallback, same warn. **One `CupTieResolver.resolve(fixture)`.** The `log.warn` messages differ in one copy — keep the most informative and note it. |
| `new CompetitionEntry()` zero-init block | **5 copies** — `SeasonService:240`, `SeasonService:265`, `DatabaseInitializer:1392`, `InternationalClubCupDraw:707,732`, `ClubCupController:402`. One `CompetitionEntries.emptyFor(competition, season, team)`. |
| find-or-create `SeasonCompetition` | **4 copies** — `SeasonService.ensureSeasonCompetition`, `InternationalClubCupDraw:747`, `NationalTournamentSeeder:534`, `DatabaseInitializer:1241` |
| `"HOME"` / `"AWAY"` as bare strings | **No `MatchTeam` enum exists.** 15 occurrences in `CleanDecisionEngine`, 8 in `RestartManager`, 3 in `MatchOrchestrator`. The highest-leverage type in the package, and a pure rename. |
| `"GK"` role detection, three ways | `CleanDecisionEngine:963` `isGoalkeeper()` · `sim/model/Player.isGoalkeeper():113` — **an existing entity method, re-implemented** · `MatchOrchestrator:169` `getRole().startsWith("GK")` |
| `resolvedMatchType()` byte-identical on two entities | `Match:128` and `MatchFixture:92` — belongs on the `MatchType` enum |
| Pitch coordinates, four places | `MatchState.CENTER_ROW/CENTER_COL` (4.5, 4.0) · `RestartManager.KICK_OFF_SPOT` (4.5, 4.0) · `KickoffOption:939` (3.5) — **and `RestartManager:379-412` re-declares the penalty spots as literals that `PENALTY_SPOT_HOME/AWAY` at 43-44 already define, with the rows swapped.** Verify before unifying. |

> **`toRevealKey` vs `skillToKey` — read this before touching either.**
> `YouthAcademyService:763-775` and `TrainingProgressionService:636-648` are the same nine-arm
> `SkillName → String` switch, **and they already disagree**: `"stamina"` vs `"fatigue"` for `FATIGUE`.
> Consolidating them changes which string a `SkillDeltaDTO` carries for that one constant. **This is not
> a pure rename and must not be done as one.** Check against the frontend first.

---

## 7. Named here so they are not lost — these are **board** items, not refactors

Per `AGENTS.md`, `T2` requires a measurement, not an opinion.

| # | Finding | Where |
|---|---|---|
| 7.1 | **Four hot tables have no usable index.** `match` and `match_tick_states` have primary keys only; `player_zone_load`'s unique index leads with `player_id`, so `findByMatchId` cannot use it. | §7 of `AGENTS.md` |
| 7.2 | N+1 in loops over the whole world: `SeasonService:827,993,1087,730`; `YouthAcademyService:59,64`; `DatabaseInitializer:571` (`findAll().forEach`) and `:1322,1345,1366`; `PyramidBuilder:310,406`; `SimMatchService:462,497,552,693` — **one match = 4 × 22 = 88 player reads** | as cited |
| 7.3 | **`DatabaseInitializer` injects `CSTeamRepository`/`CSCountryRepository` from `org.example.footballtextmanager` and constructs `CTeam`/`CSCountry` inline** at `:702,859-871,884-897,902-908`. The frozen module is being written to from the live seeding path. | as cited |
| 7.4 | `ResetService` holds 20+ `createNativeQuery` calls, hand-written DDL, `information_schema` introspection and `TRUNCATE … RESTART IDENTITY CASCADE` — **in a `@Service`.** Its `resetFootballDataOnly` carries a 41-entry table list inline; `supersededTruncateList` carries a second 40-entry list **with zero callers**, and the two have already drifted (`team_tactics_profile` in one, absent from the other). | `ResetService:229-234, 494-539, 551-591` |
| 7.5 | `settleWeeklyFinancesForAllClubs` has **no `@Transactional`** and loops every club, each iteration opening its own `REQUIRES_NEW`. 14,880 clubs. | `SeasonService:594` |
| 7.6 | `CUP_WEEKS` exists **twice with different arrays**: `{1,2,3,4,5,7,8,9,10}` (`InternationalClubCupDraw:100`) and `{1,2,3,4,5,7,8,11}` (`CupFixtureSeeder:51`). `CountryController:505,523` reads the **second** to bucket the **first** one's fixtures. Same name, two meanings. | as cited |
| 7.7 | Five `LocalDateTime.of(2026, 7, 1, 12, 0)` season-start fallbacks — **calendar-year literals surviving as a fallback path**, which is exactly what `AGENTS.md` warns about. | `NationalTournamentWorldService:203`, `NationalTournamentSeeder:115`, `InternationalFixtureSeeder:90`, `InternationalClubCupDraw:132`, `NationalFriendlyRequestService:226` |
| 7.8 | `Advance Week`'s confirmation text has already shipped wrong once (commit `35c254e`, *"an admin confirmation that promised the opposite of what the button does"*). 18 `confirm()` and 72 `alert()` call sites across the frontend. | frontend-wide |
| 7.9 | Dead Java, verified by repo-wide search — delete under §9.1 | see below |

### 7.9 The dead-code list (Java)

| Symbol | Where | Evidence |
|---|---|---|
| `minute()` | `MatchOrchestrator:685` | declared, never called |
| `resolveTeamByLabel()` | `MatchOrchestrator:690` | duplicate of `BallResultHandler:388` |
| `findWinger()` | `RestartManager:483` | no caller; the comment at 464 is its own eulogy |
| `supersededTruncateList()` | `ResetService:494` | **46 lines, zero callers** |
| `hasPendingOrAccepted()` | `FriendlyRequestService:324` | no caller (the national service has its own) |
| `WeekSnapshot.asked/agreed/refused` | `FriendlyRequestService:448-467` | no callers — the AI pass they served is gone |
| `ratingOf()` / `hasResults()` | `CountryController:390,401` | neither called; `hasResults` is the **only** consumer of the injected `matchRepository` |
| `safe(Integer)` | `SeasonService:1284` | no caller |
| `createCountryIfNotExists()` | `DatabaseInitializer:1183` | **no caller anywhere, including tests** |
| `resetOnly()` | `DatabaseInitializer:477` | no caller in `src` (the no-arg variant is used) |
| `scoutedUnlisted()` | `TransferService:200` | `public`, one internal caller → should be private |
| `PlayerContractRepository contracts` | `LoanService:81` | injected, never referenced |
| `LEAGUE_END`, `LEAGUE_ROUNDS` re-exports | `SeasonService:36-39` | **no `src/main` caller** — only `SeasonShapeTest` |

Plus on `MatchState`: **11 accessors with no caller in `src/main`**, of which `lastDecisionScore`,
`lastDecisionReason` and `currentAction` have **live setters** — written every tick, read nowhere.

---

## 8. Found while reading — deliberately **not** folded in

**These are defects, not cleanups. Each needs its own board entry, its own commit, and its own
verification. Bundling them into a refactor is how a 4,000-line diff stops being reviewable.**

### 8.1 `tifo.js:1736, 1742, 1747, 1755, 1766, 1768` — literal `escapeHtml(homeName)` in output

Seven places emit the **text** `escapeHtml(homeName)` instead of `${escapeHtml(homeName)}`. The fixture
preview modal prints the word `escapeHtml` where a team name should be.

This is precisely the function whose own comment at 1676–1687 records that it was fixed for XSS. **Any
refactor of `fixturePreview` must not "fix" it silently** — that is a behaviour change and it deserves
its own commit, its own test, and the §4.1 harness to prove it.

### 8.2 `demo.js:503-508` — a dead branch

```js
if (data.action === 'ROUND_SIMULATION_RUNNING') {
    handleSeasonFlowResponse(data, button, defaultLabel);
    return;
}
handleSeasonFlowResponse(data, button, defaultLabel);
```

Both arms are the same call. Provably dead control flow.

### 8.3 `zox-match-preview.js` — **zero `escapeHtml` calls**

~60 `safe()` calls, which null-coalesce but **do not escape**. Server-controlled strings — `teamName`,
`report.headline`, `report.summary`, `event.title`, `player.playerName` — reach `innerHTML` unescaped.
`AGENTS.md` states `escapeHtml` is the only copy; this file simply never calls it. **This is an XSS gap and
it is the highest-severity item in this document.**

### 8.4 `demo.js:693` — `refreshGameClock()` does not exist

Called under `typeof === 'function'`; **defined nowhere in the repository.** Advance Day / Advance Hour
never refresh the clock.

### 8.5 `dashboard.js:177-180` — a cache that never invalidates

`readWatchStatus` caches `window.__fmWatchStatus` **on the function object**. Once read, the watch
button's enabled state is fixed for the life of the page.

### 8.6 `tifo.js` — three dead symbols in a chain

`showHalfTimeModal` is the only writer of `window._pendingFullTimeContinue`, which is the only thing
`window.tifoContinueFromHalf` reads. So the button rendered at `tifo.js:2866` does nothing. (See P-9 — the
deletion is a refactor; the *fact that a button does nothing* is the defect.)

---

## 9. Sequencing

### 9.1 Wave 0 — zero-risk, no coverage needed (do these first)

| # | Item | Why it is first |
|---|---|---|
| W0-a | **Delete verified dead code**: §7.9 (Java), P-9 (`tifo.js`) | Pure deletion. A symbol-search miss cannot be compensated for by a caller that does not exist. |
| W0-b | **P-22 `round2`** → one utility | 25 identical copies. Any copy that turns out to differ is *found* by the consolidation, which is the point. |
| W0-c | **P-23 shared helpers**: `winnerOf`, `pointSegmentDistance`, `CompetitionEntry` zero-init, find-or-create `SeasonCompetition`, `resolvedMatchType` | Copy-paste with no domain judgement. The diff is additions and deletions, not rewrites. |
| W0-d | **P-19.3 `toPredictionMap`**, `formatDateTime` (3 copies), `displayNameOrLogin` | Byte-identical, verified by reading. |
| W0-e | **P-10 `posOrder`** (7 copies across 3 files) | One constant next to `escapeHtml`. |

> **Before W0-a:** the four `toRevealKey`/`skillToKey` **disagreements** and the `formatAttendance`
> one-character difference (§5.6) must be **decided by the owner**, not decided by you.

### 9.2 Wave 1 — frontend extraction, coverage exists or can be built

`P-1 nav-history` → `P-2 session` → `P-3 world-view` → `P-4 delete wrappers` (**in that order** — P-4
depends on the other three) → `P-12 renderTableView` → `P-14 admin-db-jobs` → `P-15 demo.js` storage
+ polling → `P-16 academy`.

**Gate before Wave 1:** `mvn test -Dtest=CountryPageRendersWithoutReferenceErrorTest` green **and proven
able to fail**. And build the §4.1 `tifo.js` harness before anything in §4.

### 9.3 Wave 2 — backend extraction

`P-18 DatabaseInitializer` (18a split → 18b collaborators) → `P-19 CountryController` (ranking
extraction first — it is pure and immediately testable) → `P-20 SeasonService` `WeekRollover`.

### 9.4 Wave 3 — `tifo.js`

Only after the harness exists. `P-8` split → `P-10` renderers → `P-11 renderTactics` **last**.

### 9.5 The three questions I am not answering alone

Per `AGENTS.md` — *"If you have doubt, ask rather than going in circles."*

1. **`P-6` — do you want the 14 alias cases in `loadPage` collapsed, or left as they are?** I lean leave
   them (§3.2), because `PAGE_NAMES` is already a second table keyed on the same ids.
2. **§5.6 `formatAttendance` — `'-'` or `'—'`?** The two copies differ by one character and both are live
   in different screens. Consolidating *must* pick one, and that changes rendered output.
3. **§4.1 — `tifo.js` has no coverage at all.** 3,148 lines, 26 exported globals, reachable from the home
   screen. Building the harness is a real piece of work. Do you want it before the refactor, or do you
   accept the first split being unverified?

---

## 10. What "done" means for any item here

Restating `AGENTS.md`, because on a refactor commit these are the only criteria that apply:

- [ ] The diff contains **no behaviour change**. Not "no intended change" — none.
- [ ] Every renamed identifier was searched repo-wide, **including `src/test`**, first.
- [ ] Every `window.*` name that existed before still exists with the same name.
- [ ] Every `data-*` attribute, CSS class, endpoint path and JSON key is byte-identical.
- [ ] The nine text-scanning tests in §2 were **read before the edit** and re-run after. If one fails,
      the fix is to decide whether the test's *contract* still holds — not to relax the assertion.
- [ ] `mvn test -Dtest=<the affected classes>` is green, **measured to completion** — `AGENTS.md`: the
      `[ERROR]` summary prints at the *end*, so a killed run reports a different set of failures.
- [ ] **A guard was broken deliberately and watched to fail.** At least one per item.
- [ ] For anything in §4 or §5: **the node harness ran**, not skipped. `node` is at
      `/usr/local/bin/node` and is **not on `PATH` by default** — `assumeTrue` skips silently.
- [ ] `git status` shows only the files this item intended to change.
- [ ] `kanban.md` updated and `kanbanProgress.md` appended, carrying the commit and **what did not work**.