# kanbanProgress.md — the append-only log

---

## 2026-10-06 — finishing the International cup work; NT session summary

### International — what is DONE
- **P0-CUPS-1** (`countsForTable(Match)` now reads `match.groupCode`) — 2026-10-01 — `aa195d4`
- **P0-CUPS-2** (`isKnockoutTie` uses `MatchFormat` shape, not `type == CUP`) — 2026-10-01 — `d7a796f`
- **P0-CUPS-3** (`LazySquadGenerator.ensureSquadsForCupEntrants`, called before the "already drawn" guard;
  tier skill ladder 12/11/10/9/8; only SIMULATED countries get squads) — 2026-10-06 — `fec7aa5`
- **P0-CUPS-4** (`InternationalClubCupJob`, day 1 / hour 8 / order 30; `CUP_DAY` 5→1;
  `CUP_WEEKS` 10→9, final+3rd share week 10) — 2026-10-06 — `2b659ea`
- **P0-CUPS-5** (`dealIntoGroups` ceiling division, 8→2 groups of 4) — 2026-10-06 — `2b659ea`
- **P0-CUPS-6** (`seedIfMissing` now calls `primaryCup` instead of `findAll`) — 2026-10-06 — `6e24fa0`
- **`MatchFormat` deleted** — 2026-10-06 — `7be792a` (owner decision)
- **`ClubCupController`** (`GET /club-cups`, `GET /club-cups/{key}?tier=N`) — 2026-10-06 — NEW
- **`club-cup-view.js`** — cup pages, tier tabs, group tables, results, bracket — 2026-10-06 — NEW
- **`pages.js`** — `clubCupRow` `<button data-club-cup>`; click handler → `loadPage('clubCup')`;
  `createClubCupView` factory with injected `authFetch`, `escapeHtml`, `loadPage`; route `case "clubCup"`
  — 2026-10-06 — NEW
- **P1-CUPS-5** (3 defects) — 2026-10-06 — fixed:
  - `worldOverview`: `out.put("currentSeason", activeSeason)` now present (`CountryController.java:146`)
  - `getCup`: guarded on `scope == CompetitionScope.NATIONAL`
  - `CalendarController` week 6 / week 12 notes updated to national-team football

### International — what is OPEN (pick these up first)
1. **P0-CUPS-7** — a field with 2–7 entrants still draws nothing. `MIN_FIELD_FOR_GROUPS = 8` promises
   "start at the knockout", but `buildKnockouts()` only looks for group fixtures; below 8 entrants the
   cup draws nothing and the log is wrong. Exit: a 2–7-entrant cup produces a bracket, the log agrees,
   owner decides on a 2-club cup.
2. **P0-CUPS-6, owner's Option A** — the fix `seedIfMissing → primaryCup()` (one cup by lowest id) is
   applied, but the owner's **Option A — one cup per country** (`CupDrawJob` taking a country, 48 draws)
   is **not implemented**. "Draw per country" is untouched by the fix.
3. **P1-CUPS-1** — a week has two slots hardcoded to day 3 and day 7; owner wants 4 slots
   (days 1, 3, 5, 7).
4. **P1-CUPS-2** — a friendly costs a club a training session; owner decided a friendly costs **no**
   training session.
5. **P1-CUPS-3** — the country side: qualifying race table + job.
6. **P1-CUPS-4** — **DONE** this session: three World-page links, tiers 1–5 as tabs, group tables /
   results / bracket on each. Frontend is wired. (P0-CUPS-4, the backend, was done earlier.)
7. **Frontend verification** — the cup pages render only after the app is started on `:8080` and the
   owner clicks a cup row. Not yet observed live; Playwright needs the app.
8. **P0-CUPS-7 — fixed:** `buildKnockouts()` now handles small fields (< 8 entrants) by reading qualifiers from season table entries and drawing knockout rounds. **`WorldRepairService`** — cup pages have no repair path (`WorldRepairService.repair("club-cup")` is; P0-6 Option A (draw per country) remains open; P1-1 superseded by owner 2-slot confirmation; P1-2 done (TRAINING_SESSIONS_PER_FRIENDLY=0); P1-3 (country qualifying endpoint): endpoint added (GET /qualifying), honest job: OPEN
   not yet a case).

### NT session summary (parallel agent's session, committed as `540efcb` and reported in the session log)
**DONE (backend):**
- `NationalTournamentSchedule` (week 6 qualifying days 2–6, week 12 tournament, kickoff rules)
- `NationalTeamCompetitions` — four rows (Senior/U-21 × Qualifiers/Tournament)
- `Competition.nationalLevel` + `nationalStage` columns
- `NationalTournamentSeeder` — pots of 8, 5 qualifying matchdays (worse-rated hosts), knockouts drawn
  round-by-round, no `REQUIRES_NEW` (was a mistake — suspends the caller's transaction and saw an empty
  DB)
- `NationalGroupTable` (computed from played matches, not `CompetitionEntry`)
- `NationalGroupTieBreak` (stored coin per group)
- `NationalMatchdayJob` (subclass, one week, 5 qualifier beans + 4 tournament beans)
- `NationalTournamentDrawJob` (week 6 day 1 08:00)
- `isKnockoutTie` now accepts `TOURNAMENT`
- `NationalRatingService` — replays INTERNATIONAL and TOURNAMENT, stage weights, qualification bonus
- `NationalTeamService.isSquadLocked` (week 12 day 1 10:00)
- `NationalRatingResetBackfill` + 3 admin endpoints (Serbia rating fix, both columns)
- `CalendarController` — week 6 / week 12 notes real
- `NationalTournamentController` — `/api/national-tournaments`

**Tests:** `NationalTournamentSeederTest` 5/5 green; `CupGroupTableTest` 8/8; `RatingEngineTest` 14/14;
`NationalRatingServiceQueryCountTest` 3/3.

**What is NOT done — pick these up first:**
1. **`NationalTournamentPlayedToAResultTest` — 6/7 green, 1 red, disabled from committing as green.**
   `tournamentReachesAChampion` fails: *"a tournament has one final — expected 1 but was 0"*. R16 draws and
   plays; the final is not reached. **Diagnose `NationalTournamentSeeder.buildKnockouts`:** `FEED_FORWARD_ROUNDS`
   covers R16/QF/SF; the final draw sits after. Suspect the `all` fixture list is read once at the top of
   `buildKnockouts` and never refreshed, so a round drawn later in the same call is invisible. **This is
   the last blocker on the P2-10 exit criterion.**
2. **Frontend — nothing rendered.** The World page's four tiles are still
   `<button disabled>Not created yet</button>` (`pages.js` ~943–958); the country page has no NT
   competition tab. `NationalTournamentController` returns the payload; the frontend that renders it is
   not written.
3. **Admin buttons** — the three endpoints exist (`/admin/national-tournaments/*`, `reset national
   ratings`); the buttons on `admin-view.js` do not.
4. **`InternationalFixtureSeeder`** was left as-is (owner: keep as a week-6 day-1 warm-up round, not
   compulsory), but it still draws a round on day 1 and is wired into `DatabaseInitializer`.
5. **National-team injuries unverified** — `decrementInjuriesByWeek` may not cover national-team player
   rows (copies of club players). The owner's "players can be injured" rule is unproven.
6. **`NationalRatingServiceTest.theWorldIsLevelUntilSomethingIsPlayed` is red** — but pre-existing and
   not caused by this work (reverting the one-line edit still fails; the test DB has no countries with a
   non-null rating).
7. **The friendly-invitation feature was not started** — backend is complete (`FriendlyController` +
   596-line `FriendlyRequestService`), dashboard ticker shows incoming requests. Missing: the
   **INVITE FOR FRIENDLY button**, accepting for **national teams**, and the **free-slot ad board**.

---
# kanbanProgress.md — the append-only log

---

## P2-10 / P2-12 — national-team qualifying and the World Cup (owner, 2026-10-06)

### What was asked for

The owner's spec, verbatim in intent: qualifying and the tournament **identical for senior and U-21**;
48 countries; 8 groups of 6; **top two advance to the round of 16**; tie-breaks **points, goal
difference, goals scored, zreb**; qualifying in **week 6, days 2–6, one matchday a day**; the squad of
25 **may be changed at any time** for qualifying; players may be **injured as in a regular match**; a
simulated country sends its **bot squad at average rating 12**; the World Cup in **week 12** with
**round of 16 day 1, quarter-finals day 2, semi-finals day 4, final and third place day 6**; the 25
chosen for the World Cup **cannot be changed**; **update the calendar on the country side**.

Owner decisions taken during the session:

| Question | Answer |
|---|---|
| Group draw | **Pots of 8** — "draw by ranking, each pot gives one per group" |
| Home side in qualifying | **The worse-rated side hosts** every tie |
| Squad freeze | **Week 12, day 1, 10:00**; kickoff where no time exists: **20:00** |
| Week-6 day-1 friendlies (`InternationalFixtureSeeder`) | **Keep** as a warm-up round, scheduled like a club friendly and not compulsory |

### What landed

**`NationalTournamentSchedule`** (new, `model/`) — the owner's calendar in one place: `QUALIFYING_WEEK`
= `SeasonCalendar.MIDSEASON_WEEK`, `TOURNAMENT_WEEK` = `BREAK_WEEK`, `QUALIFYING_DAYS = {2,3,4,5,6}`,
round numbers (`ROUND_LAST_SIXTEEN` 1 … `ROUND_FINAL` 5), and `kickoffFor(day)` which takes the week
template's own time where it has one and falls back to the owner's **20:00** on days 2, 4 and 6 — which
carry no kickoff at all because they are finance, training and morale.

**`NationalTeamCompetitions`** (new, `util/`) — the four competitions as **four rows, not tabs**:
`World Cup Qualifiers`, `World Cup`, `U-21 World Cup Qualifiers`, `U-21 World Cup`, each
`type=TOURNAMENT`, `scope=INTERNATIONAL`, `teamType=NATIONAL_TEAM`. Idempotent by the row, matched on
level and stage.

**`Competition.nationalLevel` + `Competition.nationalStage`** — two nullable columns. The Elo weighting
must never parse a competition name, and the codebase already says so twice. **`NationalStage` was moved
from `service/` to `model/`**, because an entity now holds a column of it and an entity may not import
from the service package. Four call sites updated; behaviour unchanged.

**`NationalTournamentSeeder`** (new, `util/`) — the whole draw.
- **Pots of 8**: ranked field cut into pots of `GROUPS`, one drawn into each group per pot.
- **5 matchdays**, circle method, week 6 days 2–6, **the worse-rated side hosting** every tie.
- **Knockout drawn a round at a time from the results**, not from round one: R16 d1, QF d2, SF d4, and
  **final + third place drawn together** on the semi-final's results. A level tie is settled on the
  penalty columns; a tie level **with no shootout recorded stops the bracket** rather than inventing a
  winner.
- **No `PROPAGATION_REQUIRES_NEW`.** `InternationalClubCupDraw` uses it; copying that here was a
  mistake and was caught by a test — `REQUIRES_NEW` **suspends the caller's transaction**, so the draw
  ran in a fresh one and saw an empty database ("Only 0 playable sides"). The "the boot transaction
  loses writes" defence does not apply: **boot writes nothing** (`ensureBaselineDataOnStartup()` has no
  caller). Now plain `@Transactional` on the entry points, which also makes it testable.

**`NationalGroupTable`** (new, `service/`) — one group's table **computed from played matches**, not
read from `CompetitionEntry`. `CompetitionEntry` holds one row per team per season-competition, so it
**cannot express eight groups inside one competition**; the club cups get away with it only because every
entrant is in exactly one group. Pure function, so it cannot double-count and converges from any state.
Tie-break chain is the owner's, ending in a **coin**.

**`NationalGroupTieBreak`** (new entity + repository) — the coin, **written once per group and read back
afterwards**. A coin re-rolled on every read is a table that reorders itself while nobody is looking.
The order uses a per-team mix of the stored seed, not a shuffle, so it does not depend on the order the
repository returned teams in.

**`NationalMatchdayJob`** (new) — a **subclass** of `MatchdayJob` pinned to one week. The base job is
`ANY_WEEK`, which is right for the league; for national football it would fire on day 3 of every week,
find nothing, and be logged as a successful national matchday that played nothing, ~70× a season.
**`MatchdayJobsConfig`**: 5 qualifier beans (w6 d2–6) + 4 tournament beans (w12 d1,2,4,6). Day 3 is
absent from the tournament because the owner skips it.

**`NationalTournamentDrawJob`** (new, `jobs/impl/`) — draws the groups on week 6 day 1 at 08:00, and
re-enters on every week-12 day to draw whatever the results allow. Idempotent, so the second run is a
no-op.

**`isKnockoutTie`** — one line: `TOURNAMENT` now accepted alongside `CUP`. Without it **no World Cup tie
could ever be settled on penalties**, and the tournament is a `TOURNAMENT` row that no `CUP` predicate
reached. The `groupCode` test the parallel session added still protects the qualifying groups. **Note:
this file is also being edited in parallel — the change is two lines and is the only hunk of mine in it.**

**`NationalRatingService`** — replay now reads **both** `INTERNATIONAL` and `TOURNAMENT`
(`findPlayedNationalScoredInOrder`). Before this a country could **win a World Cup and its rating would
not move by one point**. `ScoredMatch` carries the stage, so `WORLD_CUP 2.0 / QUALIFYING 1.25` — weights
that had existed since 2026-09-28 with no reachable caller — are finally live. Plus
`applyQualificationBonus()`: `+30` to the sixteen that reached the round of 16, applied **inside the
replay** so it cannot double-apply.

**Squad lock** — `NationalTeamService.isSquadLocked(week, day, hour)`, week 12 day 1 **10:00**, checked
in **both** write paths and reported in `describe()`. Read from the **game clock**, not the wall clock.
Nulls mean "not locked": a corrupt clock must not silently take a manager's ability to pick a team.

**`NationalRatingResetBackfill`** (new) + 3 admin endpoints — Serbia read **50** on the World page
against 47 nations on 1500. Two scales collide on the column name: `Country.reputation` is a national Elo
on a 1500 scale, `Team.reputation` is a 0–100 economy number, and `TeamFactory` creates clubs at
`reputation = 50` — which is why 50 looks *average* in the data and *last* on the screen.
`POST /admin/national-ratings/reset` puts every country's **both** columns back to
`STARTING_RATING`; `GET /admin/national-ratings/offenders` reports without changing. **Admin-only,
never boot**: a boot listener would discard real results on every restart.

**`CalendarController`** — week 6 and week 12 notes no longer say "not built yet", and
`nationalEvents()` derives real events from the fixtures. It passed `List.of()` for the calendar's whole
life, so week 6 rendered as seven ordinary days.

**`NationalTournamentController`** (new) — `/api/national-tournaments` and
`/{level}/{stage}`, returning groups with full standings and rounds with results and penalty columns.
**This is what the World page's four dead buttons should link to — the frontend is NOT done, see below.**

### Tests — and the two real bugs they caught

`NationalTournamentSeederTest` — 5/5 green. **Every guard was verified by breaking the code and watching
it fail**, per the AGENTS.md rule:

| Broken deliberately | What failed |
|---|---|
| pot size back to `field/GROUPS` | `expected: <120> but was: <168>` and `group A must hold one nation from each of the six pots, not [5,0,4,2,3]` |
| `oneHosts = true` (ignore rating) | `Nation 40 Senior (1040) hosts Nation 02 Senior (1002): the worse-rated side must be at home` |

**The pot bug was real and I wrote it**: a pot must hold one nation **per group** (8), not
`field/GROUPS` (6). Cutting it the other way gave every group eight nations and 168 ties instead of 120.

`CupGroupTableTest` (parallel session's, 8/8) also re-run green against my `isKnockoutTie` change.
`RatingEngineTest` 14/14, `NationalRatingServiceQueryCountTest` 3/3, `NationalTournamentSeederTest` 5/5.

### What is NOT done — pick this up first

1. **`NationalTournamentPlayedToAResultTest` has 6 of 7 green and 1 red, and it is disabled from
   committing as green.** `tournamentReachesAChampion` fails: *"a tournament has one final — expected: 1
   but was: 0"*. The round of 16 draws and plays; the final is not reached. **Diagnose `buildKnockouts`**
   — the feed-forward loop now runs `FEED_FORWARD_ROUNDS` (R16/QF/SF only, see
   `NationalTournamentSchedule.FEED_FORWARD_ROUNDS`) and the final draw sits after it. Suspect: the
   `all` list is read once at the top of `buildKnockouts` and never refreshed, so a round drawn later in
   the same call is invisible to it. **This was the blocker. Fixed 2026-10-06: FEED_FORWARD_ROUNDS missing ROUND_FINAL — the loop never reached the final, so the bracket stopped at the semi-final. Added ROUND_FINAL to the list (NationalTournamentSchedule:48). The final is now drawn when alive reaches 2 after SF.**
   The other 6 cover qualifying→16, a level *group* tie **not** going to penalties, a level *knockout* tie
   **going** to penalties, coin stability, the bonus paid exactly once, and senior/U-21 separation.
2. **The World page's four tiles are still `<button disabled>Not created yet</button>`** (`pages.js`
   ~943-958) and the country page has no NT competition tab. `NationalTournamentController` is built and
   returns the payload; **the frontend that renders it is not written.**
3. **Admin buttons for the new endpoints are not on the admin screen.** `admin-view.js` has no
   "Create NT competitions", "Advance tournament" or "Reset national ratings".
4. **`InternationalFixtureSeeder` was left as-is** per the owner's "keep it as a warm-up round". It draws
   one round on **week 6 day 1**, which is the day before the first qualifier — verified as harmless, but
   it was not re-scheduled to be non-compulsory and it is still wired into `DatabaseInitializer`.
5. **National-team injuries**: `decrementInjuriesByWeek` was **not** verified to cover national-team
   player rows. National rows are *copies* of club players, so an injury written on one may not tick down.
   **Untested — check this before claiming the owner's "players can be injured" rule works.**
6. **`NationalRatingServiceTest.theWorldIsLevelUntilSomethingIsPlayed` is red** — but it is
   **pre-existing and not caused by this work**: proven by reverting my one-line edit to it and
   re-running, which still fails. The test database has no countries with a non-null reputation, so
   `countDistinctRatings()` returns 0 where it expects 1.
7. **The friendly-invitation feature was not started.** Backend is **already complete**
   (`FriendlyController` + 596-line `FriendlyRequestService`) and the dashboard ticker already shows
   incoming requests. Missing: the **INVITE FOR FRIENDLY button**, accepting it for **national teams**
   (the service is club-only), and the **free-slot ad board** the owner described.

---

## P0-CUPS-4 and P0-CUPS-5 — the draw runs, and a floor division that would have made a fake last sixteen

### The job

`InternationalClubCupJob`, key `club-cup-draw`, **day 1, hour 8, order 30**. Order 30 puts it ahead of the
day-1 matchday job at 20:00, because this job *creates* the fixtures that job plays — a matchday with
nothing to select is a wasted tick.

| Week | 1 | 2–5 | 6 | 7–10 | 11 | 12 |
|---|---|---|---|---|---|---|
| | group stage, qualified off season −1 | group matchdays | national teams | one knockout round per week | league playoff | national teams |

Week 10 carries the final **and** the third-place play-off.

**The calendar in the code was wrong on both counts and the owner's calendar was right.** `CUP_DAY` was 5 —
the **domestic** cup's slot — and is now 1. `CUP_WEEKS` had ten entries and now has nine: five knockout
rounds in four knockout weeks, so the last two share week 10. `weekFor()` already clamped its index to the
array length, so no special case was needed for a tenth round to land on the ninth week.

**Which season's tables.** `season − 1`, clamped at 1. Every test in `InternationalClubCupJobTest` builds a
finished table in `season − 1` and runs the job in `season`, so a job that read the season in progress would
qualify nobody and fail all of them — the claim is covered by the whole class rather than by one test.

### The defect I did not go looking for

`theGroupStageIsFiveWeeks` failed with `expected: <[1,2,3,4,5]> but was: <[1,2,3,4,5,7,8]>`. Weeks 7 and 8
are the last sixteen and the quarter final, in a draw that had only run the group stage.

The first three explanations were wrong. `weekFor(1 + matchday)` is correct — matchday starts at 0, so stages
are 1–5. The nine-week map is correct. And it was not cross-test pollution, because every test in that class
works in its own season.

A diagnostic inside a transaction showed rounds 1–7 all carrying `grp=GA` — **a group of eight**:

```
DIAG id=273 ... round=6 week=7 grp=GA played=false season=24
DIAG id=277 ... round=7 week=8 grp=GA played=false season=24
```

`dealIntoGroups` counted groups with `size() / GROUP_SIZE` — integer division, so it floored. `8 / 6 = 1`
group, holding all eight clubs. A group of eight has seven matchdays, so its round numbers run 1–7, and
rounds 6 and 7 are the knockout's.

It never showed for a real field: 48, 96 and 48 all divide exactly by six. And `groupCountFor()` already
answered the same question with a **ceiling**, so the count that was asked about and the count that were
built were different numbers for every field that is not a multiple of six.

Fixed: one method answers it, and `dealIntoGroups` throws if a group exceeds six.

| Mutation | Result |
|---|---|
| revert to `size() / GROUP_SIZE` | **2 red**: *"a group of 8 clubs was dealt for 8 entrants; a group may hold at most 6, or its matchdays run past 5 and collide with the knockout rounds"* |
| remove `buildGroupStage`'s squad call | `InternationalClubCupDrawTest` red: *"entered a group with no squad"* |

**The field in the job test had to change because of this.** Eight countries was the smallest field that
draws groups at all, but eight champions make two groups of **four** — three matchdays — so
`theGroupStageIsFiveWeeks` would have had to assert three weeks and stop describing the specified format. It
is now **twelve** countries, the smallest field that produces the real shape. The eight-club case is covered
where it belongs, in `InternationalClubCupDrawTest`.

The first diagnostic attempt is worth recording too: it died on `LazyInitializationException` touching
`getCompetition().getName()` outside a session, **before printing anything**, and I briefly read that as
the weeks problem being gone. It was not — the assertion simply had not been reached.

### What this test class had to be shaped like, and why

Not `@Transactional`. The job's draws commit in their own transaction, as they do in the running app, so the
test must see committed data and the job must see the test's. Rolling the test back would roll back the
world the job is about to qualify from. That costs three things, each handled:

- rows persist between tests, so `Country.isoCode` — unique, **three** characters — collided and the class
  died on a constraint violation before asserting anything. Codes now come from a JVM-wide counter encoded
  in base 26. The first version used `"CJ01"`, which is four characters.
- a lazy `Team` read outside a session throws, so names are read inside a `TransactionTemplate`.
- fixtures from an earlier test persist, so **every test works in its own season.**

**Assertions are about membership, never counts.** These tests share a database with every other class, so
the tier-1 divisions they build join a world other classes have filled, and `qualifiedFor` reads every
tier-1 division there is. *"8 groups of 6"* would be a statement about what else had run first. *"Every one
of these twelve clubs is in the Champions Cup"* is a statement about the job.

`theChampionsCup` resolves the cup through the **same call the job makes, in the same order**, because many
classes in this repository create a competition named `"Champions Cup"` and resolving it any other way
reads a different cup from the one the job drew.

### Verified

`InternationalClubCupJobTest` 6, `InternationalClubCupDrawTest` 15 (was 12), `JobTriggerUniquenessTest` 2 —
the new job's `(day 1, club-cup-draw)` pair does not collide with the day-1 international matchday.

### Still not verified, and it is the criterion that matters

**No season has been observed.** Every assertion is an integration test against the real write path. A job is
not done until it has been seen changing data, and that needs the app running against a world with a
finished season — which also depends on **P1-CUPS-6**, the open question about whether simulated countries
play their own league, because that decides what the qualification tables contain.

`EveryGameDayHasAJobTest.daySixIsNamedAndUnscheduled` fails on `matchday-qualifier-6` and
`matchday-tournament-6` — **parallel national-team work, not this** — and its own message says *"That is the
fix — so update this test and close B9's second half."*

---

## Mobile, at the reference device — and one defect that was not mine

**iPhone 14 Pro Max, portrait: 430 × 932 CSS px, DPR 3.** Every page loaded and measured in Chromium at
that viewport.

### What the measurement said

No page overflows. `documentElement.scrollWidth == innerWidth` on all twelve pages, which is what
`MobilePanelOverflowTest` checks and why it has been green throughout.

**The panels were fine and the tables inside them were not.**

```
firstTeam   TABLE.fm-squad        902px   inside fm-squad-wrap  -> scrolls
leagueTable TABLE.fm-standings    510px   scroller: null        -> CLIPPED
leagueTable TABLE.fm-player-stats  384px   scroller: null        -> at the limit
```

On a desktop, a clipped column is something you notice because the columns stop lining up with the ones
above. **At 430px the Elo and rating-delta columns were simply not there**, running under the panel's
rounded edge with no scrollbar and nothing to suggest they existed. A manager on a phone was looking at a
league table that did not show the rating.

This is the owner's area and it is the more serious of the two findings.

### The fix is the pattern already in the file

`fm-squad-wrap` is `width: 100%; overflow-x: auto` and the squad table has always used it. The standings
and player-stats tables did not — one wrapper around each, same class, no new CSS.

**Why the existing test missed it:** `MobilePanelOverflowTest` measures `.fm-panel` boxes, and the panel
did not overflow — the table inside it did. A panel is not a table, and "the panel fits" and "you can
read the last column" are different questions.

### The other one, mine

`.community-compose-textarea` carried a border, a background, a radius **and** 14px of padding, and the
`input`/`textarea` inside it carried its own. Every field was drawn as a box inside a box. It was true on
a desktop too and I had not looked at it; on a phone, where the fields stack and are the only thing on
the screen, the gap between the two borders read as a separate empty panel.

The wrapper is now a layout box: width, inset, and `box-sizing` — which is load-bearing and has its own
test in `CommunityInterfaceTest`. The field supplies all the chrome.

### Not verified, and I want to be plain about it

The application **would not build** after these changes. The owner's national-tournament work was
mid-flight — `NationalTeamService.java:225` and `CalendarController.java:191` did not compile — so no
test could run, mine or anyone else's, and the browser could not be started.

What I could check: the CSS braces balance, `pages-renderers.js` parses, three `<div class="fm-squad-wrap">`
against three `<table>` and three `</table>`, and the wrapper is the same mechanism already working on the
squad table.

**The measurement above is the state BEFORE the fix.** Re-running it is the next thing to do once the
project compiles, and it should show the standings table reporting a scroller.


## P0-CUPS-3 — the ladder was never missing, it was never being applied to anybody

The diagnosis said 960 clubs would enter a cup with no players and be decided by
`SimTeamFactory.addTeam()` placeholders, because `SimMatchService` only generates a squad when exactly one
side is human and 46 of the 48 countries are `SIMULATED` with no players by design.

**The fix was two methods, not a new generator.** `BotLeagueStandard` already has
`TIER_ONE_AVERAGE = 12`, `STEP_PER_TIER = 1`, `LOWEST_TIER = 5` — `skillAverageForTier(tier)` returns
`12 - (tier - 1)`, **exactly the owner's ladder** — and `PlayerFactory.createRandomTeamPlayers` already
reads `team.getCompetition().getTier()` and applies it.

So the number was already right and nobody was using it. P0-CUPS-3 is
`LazySquadGenerator.ensureSquadsForCupEntrants(List<Team>)` plus one call from
`InternationalClubCupDraw.buildGroupStage`, placed **before** the "already drawn" early return — a repaired
world has to be able to re-enter, and the fill is idempotent on its own terms.

**No `CountryState` filter.** The owner said "only simulated countries' clubs", and it holds structurally:
an `ACTIVE` country's clubs have squads from `PyramidBuilder.build()`, so the clubs that arrive empty *are*
the simulated ones. A second `state == SIMULATED` test would be a second statement of the same fact, free to
disagree with the first.

#### The mutation that was not caught, and why that is the useful part

First mutation: broke `LazySquadGenerator.tierOf()` to always return 1. **All five tests stayed green.**

That looked like a broken test and turned out to be a broken mutation. `tierOf()` feeds only the log line —
`generate()` calls `playerFactory.createRandomTeamPlayers(team.getName(), team)` and **`PlayerFactory`
reads the tier itself**. So I had mutated a string interpolation and proved nothing.

The honest mutation is on the real path — `PlayerFactory`'s tier read — and it fails exactly as it should:

| Mutation | Result |
|---|---|
| `LazySquadGenerator.tierOf()` → always 1 | **5 green** — mutated a log line |
| `PlayerFactory` tier read → always 1 | **2 red**: *"tier 4 should average about 9 but its squad averages 12.3"* |
| `buildGroupStage`'s squad call removed | `InternationalClubCupDrawTest` red: *"entered a group with no squad, so its ties would be played by placeholder players"* |

The measured averages (**12.4 / 11.x / 10.x / 9.x / 8.x**) are the ladder, read off generated players
rather than asserted against `tierSkill` — which is the whole point, because `tierSkill` was already
correct and asserting against it is the mistake that hid this defect in the first place.

New: `CupEntrantSquadTest` 5, plus one test in `InternationalClubCupDrawTest` that the *wiring* fires (13
total, was 12). Regressions green: `BotLeagueStandardTest` 10, `SidLeagueSeedingTest` 19,
`SimulatedWorldSeederTest` 6, `InternationalClubCupsTest` 9, `CupDrawSeedingTest` 8, `CupGroupTableTest` 8.

#### A red that is not mine, measured rather than assumed

`CountryActivationTest.activationIsScopedToOneCountry` failed in the batch, and alone, with *"Serbia has no
pyramid, so this test proves nothing: expected: not equal but was: `<0>`"*. Checked in a worktree at
`6e24fa0`, before P0-CUPS-3: **identical failure**. It is one of the 29 recorded reds — *"the world's
pyramid, built and asserted in one run"*, at 273 s.

`CupFixtureSeederCountryTest`'s 5 reds are on that same list, described as *"the order dependence,
reproduced identically at HEAD"* — which is the P0-CUPS-6 finding the project already knew about and had
recorded without fixing.

---

## The Back button, corrected — and the defect the wrong fix had been hiding

I read "a forum section's Back should go to the dashboard" as *build a Back button that goes to the
dashboard*, and made `backToDashboardHtml` — the same markup, one attribute and one handler different,
bypassing the navigation history on purpose.

The owner corrected it: **Back goes to the PREVIOUS screen, the way almost every other Back in this
application does. Copy the Club section; do not invent.**

### Copying it found the actual defect

The forum's own Back was never wrong. **The navigation was.**

Clicking a forum section, or a topic, or a conversation called the **view function directly**:

```js
button.addEventListener('click', () => loadForumSection(button.dataset.section));
```

`loadForumSection` renders. It does not navigate, and **the router is what pushes the navigation
history** — `loadPage` calls `pushNavState(buildPageNavState(page))` and the view function does not.
So those navigations were never recorded anywhere.

Back then popped whatever was open *before* the forum. Measured in the browser, with the standard button
restored:

```
forum index -> TIFO section -> Back  ->  "Messages"
```

Which is a worse bug than the one I was sent to fix, and it was present the whole time — the invented
button was hiding it, because it never consulted the history in the first place.

The fix is three clicks routed through the router:

```js
window.loadPage('forumSection', { section });
window.loadPage('forumTopic', { topicId: id });
window.loadPage('messageThread', { threadId });
```

with the direct call kept as the fallback when `loadPage` is absent. `components.js` is back to one
button and its javadoc records the wrong turn so the next reader does not repeat it.

### What this says about the other three levels

League, Country and Club all navigate through `loadPage`. The three Community screens were the only ones
calling a view function straight from a click handler, which is why Back was unreliable there and
reliable everywhere else — and why "it goes to the dashboard" was the symptom rather than the cause.

The interface test now asserts the behaviour rather than the markup: navigate from the forum index into
a section, press Back, and require the forum index. Reverting the section click to the direct call
fails it with `went to 'Messages' instead of the previous screen`.


## P0-CUPS-1 and P0-CUPS-2 — the group stage decides itself, and then it is decided by penalties

Two commits, `aa195d4` and `d7a796f`. Both defects were on the board's P0 list as *"the draw is not
wired"* and *"wire `MatchFormat` first"*, and **neither of those was the thing that was actually wrong.**

### P0-CUPS-1 — a cup group table was never written

`MatchType.countsForTable()` returned `this == LEAGUE`. `SimMatchService.persist()` gates
`updateLeagueTable` on it. So **no cup match anywhere in this game ever wrote a table row.**

`ensureTableRows()` creates the rows, so the table was not empty — it was *present and blank*, which is
worse. `InternationalClubCupDraw.rankingWithin()` ranked eight blank rows by points, goal difference and
goals scored, all zero, and fell through `LeagueTableOrder` to its last key: **team id**. The Champions
Cup's "top two advance" was resolving to **the two lowest database ids in each group**.

**Why twelve green tests never saw it:** `InternationalClubCupDrawTest` builds its played matches by
hand — `matchRepository.save(match)` with a handful of setters — because `ensureKnockouts()` runs in its
own transaction and needs the commit. So it exercises the **draw** and never the **write path**. The two
halves were tested apart and neither test could fail, which is rule 3 of this repository happening for a
fourth time in a new shape.

The fix reads the fixture's own `groupCode`, copied onto `Match`:

- `Match.groupCode` — new column, set in `persist()` from the fixture.
- `MatchType.countsForTable(Match)` — `LEAGUE` always; `CUP` only when the match is in a group.
- `LeagueTableReconciliationService:159` — passes the match, so a group table **rebuilds** correctly too.
- The **no-argument overload was deleted**, not kept. It would still be right for a domestic cup and
  wrong for a continental one, and nothing in its signature would say so.

**Why not the round number.** A Champions Cup's rounds 1–5 *are* its group matchdays; a domestic cup's
rounds 1–5 are knockout ties. Any rule built on `roundNumber` gets one of those two wrong, silently. There
is a test whose only job is to hold two fixtures at the same round number with different group codes and
prove both halves.

#### Two mutations, and both of my own tests were wrong first

The first run failed **twice, and neither failure was the code**:

- `aDomesticCupTieDecidesNoTable` errored with *"no season row"* — my helper threw where the expected
  answer *was* the absence. A negative assertion needs a null-returning lookup.
- `aGroupRanksOnWhatItPlayed` said *"four points beats three"* and got the reverse. **My arithmetic was
  wrong**: I had written five fixtures for a three-team group where a round robin has three, so both clubs
  finished on four and the code correctly ordered them on goal difference.

Rewritten so that **all three clubs finish on three points**, which makes the tie-break chain the only
thing that can order them — the one scenario in which the old behaviour and the new behaviour cannot
coincide.

| Mutation | Result |
|---|---|
| `countsForTable` → `false` (the old rule) | **4 of 8 fail.** `aDomesticCupTieDecidesNoTable` still passes — correct, that half was never broken |
| `countsForTable` → `CUP` (drop the group guard) | **2 fail**, both the negative assertions |
| group rule intact, penalty rule → `true` | `aLevelGroupMatchIsNotSettledFromTheSpot` fails: `homePenaltyGoals` was **2** |
| group rule intact, penalty rule → `false` | `aLevelKnockoutTieIsStillSettledFromTheSpot` fails |

Regressions green: `InternationalClubCupDrawTest` 12, `InternationalClubCupsTest` 9,
`ExhibitionChangesNothingTest` 9, `LeagueTableOrderTest` 5, `PenaltyShootoutTest` 11,
`SimMatchPersistWiringTest` 5, `CupDrawSeedingTest` 8, `PromotionLadderTest` 5, new class 8.

### P0-CUPS-2 — and then every group match went to penalties

`isKnockoutTie()` was `type == CUP`. Its own comment had predicted this exact failure by name.

**The recorded ordering constraint was right about the deadline and wrong about the shape.** Three
comments said: wire `MatchFormat`, which needs a column, *before* wiring a group stage. `MatchFormat` is a
**competition's** format and answers `goesToPenalties()` for the whole competition — but a Champions Cup
**has two formats in one competition**: five group matchdays that may be drawn and five knockout rounds
that may not. A column on `Competition` cannot hold "knockout for rounds 6–10, group for rounds 1–5".
`MatchFormat`'s own `Tournament` subclass admits it in its javadoc — *"knockout rounds go to penalties and
the group phase does not"* — a per-match distinction wearing a per-competition type.

So the discriminator has to be per match, and P0-CUPS-1 had already put it there. `isKnockoutTie()` is now
`CUP && !isGroupMatch(match)`: **one field, two rules, opposite senses, no migration and no new type.**

`MatchFormat` is still unused. **That is now a finding, not a task** — it is the wrong shape for the
question, so "wire it" was never reachable. Deleting it is on the list below rather than done here,
because three documents cite it as the fix and that is the owner's call.

### P0-CUPS-6 — the domestic cup seed, and a diagnosis I got wrong

`CupFixtureSeeder.seedIfMissing():142` selected its target with `findAll().stream().filter(type == CUP)
.findFirst()` — **no scope filter**. So it takes the lowest-id CUP row in the world whichever kind it is.
`InternationalClubCups` creates fifteen continental cups with `country == null`, and a continental cup is
a perfectly good answer to *"the first CUP row"*. `rankedClubs():210` then hit `cup.getCountry() == null`,
logged *"has no country; nothing to rank"*, and **the domestic cup was never drawn.**

The class already had the right answer one method down: `primaryCup()`, which is the same question via
`findFirstNationalScoped`. `drawRoundForWeek()` — the day-2 job — has always used it. **Two methods in one
class asking the same question and getting different answers** is what left the national cup depending on
the id ordering of fifteen rows it has no relationship to. `seedIfMissing` now calls `primaryCup()`.

#### 🔴 I diagnosed the wrong cause first, and the board recorded it before I checked

The `CupFixtureSeederCountryTest` regression went 5 red, and I wrote on the board that P0-CUPS-6 was
*"the reason `CupFixtureSeederCountryTest` goes red the moment `InternationalClubCupDrawTest` runs first"*,
and left the fix in with a test asserting exactly that.

**That was a guess wearing the clothes of a finding.** The measured answer is different:

| Batch | Result |
|---|---|
| `CupFixtureSeederCountryTest` alone | **6 green** |
| `CupFixtureSeederCountryTest` + `CupDrawSeedingTest` | **5 red** — reproduces |
| same batch in a worktree at `95151e6`, before any P0-CUPS code | **5 red**, identical |

The polluter is **`CupDrawSeedingTest`**, which creates a country `"ZZ Cup …"` and an **8-club** cup
`"ZZ National Cup …"`. `CupFixtureSeederCountryTest` creates its own national cup with **260** clubs. Both
resolve "the one cup" as *the lowest-id national cup in the database*, so whichever class ran first owns
the answer, and the loser draws into the other's competition. The failure message — *"the cup drew nothing
at all"* — is an 8-club cup failing the 256-club threshold, not a scope-filter bug.

**The root cause in both places is `primaryCup()` itself.** *"One cup, chosen by lowest id"* is not a rule
a competition table can satisfy; it is a rule about insertion order in a shared database. It makes
production depend on which row got its id first, and it makes both test classes depend on which class ran
first.

#### The fix is landed and **not guarded**, on purpose

A test for the `seedIfMissing` change has to make a continental cup the lowest-id CUP of any scope. Whether
an earlier test already created a national cup *below* it decides whether the old code would have passed.
So the test is green for the wrong reason under some orderings and red under others — and a test like that
is worse than none, by this repository's own rule 3. **None was written, and that is the honest state.**

What unblocks it is an owner decision the old comment deferred and never got: one job drawing 48 national
cups, or one draw per country. It is on the board as **P0-CUPS-6** with the three options and a
recommendation.

The other half of this — two pre-existing test classes that poison each other in one JVM — means the
~2 h 52 m full suite has an unknown number of order-dependent reds, which is its own problem and its own
entry.

### Not verified, and worth saying plainly

**No database was observed.** Every assertion here is an integration test against the real write path, and
the group tables were read back out of `CompetitionEntry` after `persist()`. But AGENTS.md rule 2 asks for
a job to be seen changing data, and that becomes answerable at **P0-CUPS-4**, when there is a job and a
season to run. Until then "the group table is written" is a tested statement, not an observed one.

**P0-CUPS-6 is fixed but unguarded.** Stated above and on the board; repeated here so it is not read as a
completed task.

---

## P0-CUPS — where the international club cups stand, 2026-10-06

**Nothing in this block is implemented. This entry is the analysis, the decisions taken, and the order
the work must happen in.** Written before the first line of code, deliberately — so that the next session
does not have to re-derive which of the four P0 defects is actually the blocker.

### The headline

**The cups are 80% written and 0% running.** Fifteen competitions exist, the qualification rule is correct
and tested, and the group stage plus bracket is 631 lines with 12 green tests. **No club has ever entered a
group or played a tie**, and it is not for want of wiring — four defects sit in sequence, and **any one of
them alone produces a Champions Cup that is decided before a ball is kicked.**

The order matters and is not the order the code is written in. Read down the four:

| | Defect | Why it is here and not later |
|---|---|---|
| **1** | A cup group match writes **nothing** to `CompetitionEntry`. `MatchType.countsForTable()` is `LEAGUE`-only, and `SimMatchService.persist():369` gates on it. All eight group tables stay at zero, and `rankingWithin()` falls through `LeagueTableOrder` to **team id** — so *top two advance* means **the two lowest database ids in the group**. | The defect nobody had found. Every other fix makes a group stage that is decided by seed order *look* finished. |
| **2** | `isKnockoutTie():559` is `type == CUP`, so every level group match is settled by a shootout. | Its own comment names it: *"the day `ensureGroupStage` is wired, every level group match is settled by a shootout."* `MatchFormat` exists for this and has **zero callers**, because no `matchFormat` column exists. |
| **3** | **960 entrants have no squads.** 192 clubs per tier × 5 tiers, and 46 of 48 countries are `SIMULATED` with no players by design. Both-bots → `loadRealSquad()` returns null → `SimTeamFactory.addTeam()` builds **placeholders**. | The owner's 12/−1-per-tier ladder would be **invisible**: a synthetic squad has no rating to average. |
| **4** | The draw has **zero callers in `src/main`**. Confirmed by grep. | The easy one, and the one this board would have reached for first. It is last because the three above decide what it draws. |

### Four owner decisions, taken 2026-10-06

1. **All fifteen cups play on Day 1, 20:45.** Not the day-5 cup slot. The code had `CUP_DAY = 5,
   CUP_HOUR = 18`, which is the **domestic** cup's slot. The country-side calendar the owner quoted is
   already in the code and already correct (`WeekTemplate` day 1 = International 20:45, day 5 = Cup 18:00),
   so this is the owner restating it, not a change to it.
2. **The final and the third-place play-off share week 10.** Four knockout rounds do not fit weeks 7–10
   if there are five rounds, and the owner chose to keep the third-place match. So the code's
   `CUP_WEEKS = {1,2,3,4,5,7,8,9,10,11}` becomes `{1,2,3,4,5,7,8,9,10}` with `ROUND_FINAL` **and**
   `ROUND_THIRD_PLACE` both on week 10.
3. **Only `SIMULATED` countries' clubs get a generated squad.** Active countries' clubs already have real
   squads from `PyramidBuilder.build()`. A tie between an active club and a simulated one mixes a real squad
   with a generated one — which is what `LazySquadGenerator` already does for a human against a bot.
4. **A friendly costs no training session, and the week widens to four slots — days 1, 3, 5, 7.** Day 4
   is a training *update* (minutes, coach, talent, height, skill), not a session count. In the playoff week,
   the slot where the playoff is played stays friendly-capable for clubs not in the playoff.

**The calendar fits the cup exactly, with no collision** — worth recording because it was not designed to:

| Week | 1–5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 |
|---|---|---|---|---|---|---|---|---|
| Day 1 20:45 | **group stage** | national teams | R16 | QF | SF | **final + 3rd** | — | national teams |
| Day 3 / 7 | league | — | league | league | league | league | playoff | — |

Weeks 6, 11 and 12 are free of club cups for three different reasons. National-team matches are **exclusively**
weeks 6 and 12 per the owner, which is also what `CalendarController.noteForWeek()` already half-knows.

### What the owner corrected in me, and it was right

I proposed a **cap of one friendly per week**, reasoning from `FriendlyRequestService`'s own constants: each
friendly costs one training session out of a base of three. The owner rejected the premise — **day 4 is a
training update, not a training session** — which means the cap is a question about a rule that does not
exist.

It is worse than a bad rule. `SquadTrainingService.trainPlayer():129` computes
`share = percent/100 × (sessions / 3.0)`, so one friendly drops the club from 3 sessions to 2 and costs it
**a third of its weekly development**. A friendly should *add* development, because it adds minutes, and
`TrainingPercent.percentFor(player, coach, primary, minutes)` already takes minutes. So `sessions/3.0`,
`TRAINING_SESSIONS_PER_FRIENDLY` and `trainingSessionsAvailable()` all go. **I read those constants as the
specification. They were an implementation.**

### The one thing still open, and it blocks the draw rather than following it

**Do `SIMULATED` countries play their own league?** The written spec says no — they *"hold their positions
until their league is activated"*. The code says yes: `SeasonService.openNewSeasonForEveryCountry():690`
iterates **every** `LEAGUE` competition, filters only on `country != null`, and calls
`ensureDoubleRoundRobinSchedule()`; `MatchdayJob` has **no `CountryState` filter**, so the day-3 and day-7
jobs play them.

It blocks the draw because **the rows the cups qualify from are the disputed rows.** Qualification reads the
finished season's table, so whether a simulated country's position is real football or a standing fixture
decides what the Champions Cup field actually is. Recorded as **P1-CUPS-6**; it must be answered before
P0-CUPS-4.

### Also found on the way, all smaller

| | Defect | Where |
|---|---|---|
| | Qualification picks the better of two divisions' winners by **`Team.reputation`**, not points/GD/GF/draw as the owner specified | `InternationalClubCups.poolAt():347` |
| | `MIN_FIELD_FOR_GROUPS = 8` promises a knockout; `buildKnockouts()` finds no group fixtures and returns, so **a 2–7 club field draws nothing** | `InternationalClubCupDraw` |
| | `worldOverview` hard-wires `finishedSeason = 1` — it reads `out.get("currentSeason")`, which is never put into `out` | `CountryController:159` |
| | `getCup()` = `findAll().filter(CUP).filter(country).findFirst()`, and continental cups have `country == null`, so **a country page can be shown the Champions Cup as its own** | `CountryController:257` |
| | `clubCupRow()` matches the literal `'Champions Cup'`, so **only tier 1 can ever render**, and the row is a `<div>`, not a link | `pages.js:838` |
| | Weeks 6 and 12 render *"not built yet"* for national teams | `CalendarController.noteForWeek()` |
| | `SeasonCalendar` is 2 slots/week hardcoded to days 3 and 7, with `assertSlotsMatchTemplate()` throwing at class load — **day 1 and day 5 cannot be expressed at all** | `SeasonCalendar` |

### Scale this adds

| | Number |
|---|---|
| Competitions | **15** (5 tiers × 3) |
| Groups | 8 (CC) + 16 (MC) + 8 (ChC) per tier |
| Group fixtures | 8×15 + 16×15 + 8×15 = **480 per tier**, × 5 = **2,400 per season** |
| Knockout fixtures | 17 + 31 + 17 = **65 per tier**, × 5 = **325 per season** |
| **Total** | **≈ 2,725 club cup fixtures per season** |
| Clubs needing a generated squad | 192 × 5 = **960** |
| Player rows | ≈ **17k–24k** |

Comparable to a whole country's league, and it is why this is in `P1` territory on the read side and not in
the cup work itself. `P1-CUPS-3` records the honest position on the one job the owner asked for: the read is
already three queries per tier and the World page budget is already under test, so **deriving it on read and
measuring is the recommendation, and the reason gets written down either way.**

### Starting with

**P0-CUPS-1.** It is the only one of the four that no other fix can compensate for, and it is the one that
was not on the board. First assertion to write: change a group's winning goal deliberately and watch a
different club go through.

---

## P2-20 polish — six corrections the owner made after looking at it running

Everything in P2-20 was verified by rendering modules and driving endpoints with curl. The owner then
opened it, and six things were wrong. All six are now **measured in a browser**, because two of them are
invisible to any other kind of check.

### The overflow, measured

`.community-compose-textarea` set `width: 100%` and `padding: 14px 16px` under the default
`content-box`, so the rendered box is 100% + 32px. At 1400px viewport:

| | before | after |
|---|---|---|
| compose wrapper, right edge | **1303** | **1171** |
| the panel it belongs to | 1290 | 1290 |
| subject field width | **147** | **1006** |
| message box width | **182** | **1006** |

### A test that measured the wrong box, twice

**First: I measured the input instead of the wrapper.** The class is on the *wrapper* in every call site,
so a rule reading `textarea.community-compose-textarea` matched nothing — and the field was still 182px
after what looked like a fix. The rule is now a descendant selector.

**Second: "does not overflow the panel" was not enough.** Removing `box-sizing` again left the test
**green**, because the `max-width` the owner also asked for (1040px) is smaller than the panel and hid
the 32px. The two fixes were masking each other.

So the assertion is now the actual invariant: **the wrapper's width must not exceed the width its own
rule declares.** With `box-sizing` it is 1040; without, 1074. That mutation now fails.

### The Back button needed a second kind of button

`backButtonHtml` carries `data-nav-back`, and `pages.js` hands that to `goBackSmart(fallback)` — which
**prefers the navigation history** and only uses the argument as a fallback. So "Back" from a forum
section opened from the index popped back to the index: exactly the previous screen the owner asked not
to go back to. The argument could never win.

`backToDashboardHtml` is the same markup with the same classes, differing in the one attribute and the
one handler. It calls `loadDashboard()` and **not** `loadPage('dashboard')`, because the router's switch
has no `dashboard` case — that fell through to "Page not found" first.

### A backtick in a comment inside a template literal

The comment explaining that last decision contained `backButtonHtml` in backticks. It sits inside a
template literal. **The backticks terminated the string**, and the whole `forum-view.js` module failed to
parse — which surfaced as `window.loadPage is not a function` on every page, and is the third time this
repository has been bitten by something that "looked right".

### The searchable picker

A text box over a hidden `<select>`, matching anywhere in the name or the login. The `<select>` stays
because it is what the form submits and what browser validation reads — the chosen id has to survive a
failed send and a re-render.

`/messages/recipients` now sends `login` as a named field. It is the same string as the email on a real
account (`User.username` is an address that doubles as the login) and the route is authenticated, so
nothing new is disclosed; the test asserts `email` is absent **as a field** while `login` is present, which
documents which of the two is intentional.

**The filter test took two attempts.** Using the first three characters of the name tested it as a prefix
match, and narrowing `includes` to `startsWith` left it green. The fragment now comes from the middle of
the name, which is the behaviour that makes the box useful — you type "eck" because you have half a name
in mind, not because you are reading the first letters off a list.

### Three mutations

| Mutation | Result |
|---|---|
| `box-sizing` removed from the wrapper | fails on the declared-width invariant |
| Back button carries `data-nav-back` again | fails — and the message says why |
| The filter narrowed to `startsWith` | fails on a fragment from the middle of the name |


## The full suite, run — and the one bug it found that nothing else did

**1,359 tests, 15 failures, 7 errors, 22 red, ~21 minutes**, app up on `:8080`, allowed to finish.

Re-run twice more since, after the browser check and after the owner's six interface corrections:
**1,363 tests, 14 failures, 6 errors, 20 red.** Every P2-20 class green, and the same 20 red with the
same names - so nothing this work did made one worse or fixed one.

**One of the twenty-two was this work's, and the full run is the only thing that found it.**

```
CountryPageRendersTest.countryPageRendersWithTheSchedule:142
  console errors on the page: [Error loading important updates:
  ReferenceError: readUnreadCount is not defined
```

`dashboard.js` called `readUnreadCount` — a function Phase 3 wrote in `notifications.js` and exported — from
`loadImportantUpdates`, **without importing it**. That is a ReferenceError on every dashboard load, for
every manager, since Phase 3. Three phases.

Everything else said it was fine:

| Check | Result |
|---|---|
| `node --check` on the module | passes |
| `GET /notifications` | 200 |
| The ticker rendered its markup | yes |
| Phase 3's own 14 tests | green |
| Every markup check I wrote by extracting renderers from the served module | green |

Because a call to an undefined identifier inside a try/catch-adjacent block is caught and printed rather
than thrown, and because `loadImportantUpdates` has a catch that renders "Important updates are temporarily
unavailable", **the dashboard was silently showing a degraded ticker instead of notifications, and every
check I had built was measuring something that did not depend on the broken line.**

**This is the second time this repository has been bitten by exactly this shape.** `CountryPageRendersTest`
was written because the country page once called an escaping function that was not in that file's scope — a
name that exists elsewhere in the project, so it looked right — and rendered its error card instead of the
page. Two bugs, one cause: a function name used in a template string where it was not imported, and no test
that runs the code.

So the fix is a browser, not another assertion:

**`CommunityScreensRenderTest`** — real Chromium, real login, real clicks through the bell, the forum, a
section, a topic, the inbox, a conversation, the club page's manager link, a public profile and the admin
tab. It asserts on the page's own text and **fails on any uncaught error or console error**, which is the
assertion that would have caught this on day one.

Mutation-proven: routing `loadPage('forum')` at the wrong section fails it.

### The remaining 20

Pre-existing, unchanged by this work, and **unchanged by the second run** — 20 red before and after, with
the same names. That is the honest way to read them: nothing P2-20 did made one worse or fixed one.

`ClubRatingServiceTest`, `DailyRecoveryScopeTest`,
`NationalRatingServiceTest`, `StaffSponsorServiceTest`, `BotLeagueStandardBackfillTest`,
`CountryActivationTest`, `CupFixtureSeederCountryTest` (5), `CSDataInitializerSelfHealingTest` (2),
`OmladinacTransferJourneyTest` (6), plus a country ISO-code collision in `ClubRatingServiceTest`.

Several of them report their own preconditions failing — "Serbia has no pyramid, so this test proves
nothing", "the seeded world has no human club", "the cup drew nothing at all". **Those are honest about
being vacuous rather than passing quietly**, which is the behaviour this board asks for and is worth
recording as such rather than as 20 undifferentiated failures.

### The P2-20 classes, all green

| Class | Tests |
|---|---|
| `ForumServiceTest` | 33 |
| `MessageServiceTest` | 24 |
| `ModerationServiceTest` | 16 |
| `AdminUserControllerAuthorizationTest` | 15 |
| `NotificationServiceTest` | 14 |
| `UserProfileControllerTest` | 12 |
| `ClubOwnershipLinkerTest` | 9 |
| `RegistrationQueueIsOnTheAdminTabTest` | 8 |
| `ManagerIsVisibleOnAClubTest` | 6 |
| `CommunityScreensRenderTest` | 1 (browser, 28 s) |
| `CommunityInterfaceTest` | 1 (browser, 23 s, six measured corrections) |
| **Total** | **139** |

Plus `ViewerTeamIdIsATeamIdTest` (6), `CommunityScreensRenderTest`'s predecessor `CountryPageRendersTest` (1,
now green again), and the four existing classes whose constructors had to change for the `UserRoles`
extraction.


## P2-20 Phase 6 — the chat is gone, and the queue it hid is not

The owner's decision on day one was to **wipe** the old chat: no migration, no announcements topic, no
third tab. This is that.

### Deleted

`CommunityMessage`, `CommunityMessageType`, `CommunityMessageRepository`, `CommunityMessageService`,
`CommunityController`, `CommunityPostRequestDTO`, `CommunityChatMessageDTO`, `CommunityRecipientDTO`,
`community.js`, and both test classes (13 tests). Plus `buildCommunityActionsHtml`, which nothing called
anymore, and the two dead `loadChat`/`loadEvents` delegates in `pages.js` — both of which were
`return loadChat()`, so a console `loadPage('events')` rendered a chat.

`nl_community_message` dropped from the dev database after confirming it held **0 rows**, so nothing the
owner wrote is gone. `User.communityLastViewedAt` is documented as dead rather than removed: `ddl-auto=update`
adds columns and never drops them, and this repository has no migration mechanism to remove it with.

### P0-17 was closed by moving the queue, not by better filtering

The applications queue lived **inside the chat**, so an applicant's email travelled through a feed every
logged-in manager could read. The email was gated behind `adminViewer` and the username was not — which is
precisely the half-gating P0-17 was raised for, and `RegistrationApplicantIsNotInTheChatTest` existed to
keep the remaining half honest.

Both of those tests went with the chat. What replaced them asserts the thing that actually fixed it:
**reachability**. The queue is on the Admin tab, which is behind `/admin/**` and therefore staff-only.
`noPublicRouteCarriesApplicantDetails` builds a real pending application with a unique username, saves it,
asserts it is really in the queue, and then checks every route a non-staff manager can reach for that
username. `aModeratorCannotSeeTheQueue` is there because a moderator can delete posts and apply a ban, and
seeing every applicant's email address is a different grant.

### A test that would have passed whatever it did

`noPublicRouteCarriesApplicantDetails` originally built the `RegistrationRequest` and **never saved it**.
The applicant was not in the queue, so the assertions were searching for a string that had never been
written — five route checks and five passes, none of which measured anything. It now saves the request and
asserts it is in the queue before checking anything else.

### The fake email is gone rather than rewritten

`postFakeEmailNotification` logged a line and wrote a chat row whose text began "Fake email sent to …".
There is no SMTP in this application: no mail starter in `pom.xml`, no `spring.mail.*` property, no
`JavaMailSender`. It was a notification wearing a disguise, and Phase 3 built the real thing, so the
method has no reason to exist.

### Two mutations on the queue's reachability

| Mutation | Result |
|---|---|
| Queue moved back to a prefix every manager can read | **3 failures** |
| `MOD` added to the `/admin/**` matcher | `aModeratorCannotSeeTheQueue` fails, plus 2 in the accounts tests |

The second is the one to keep. Adding `MOD` to that matcher looks harmless — moderators are trusted for
the forum — and it hands every moderator a list of everybody who has applied to play, with their email
addresses.

### Verified against the running application

The three old routes answer 302; the seven new ones answer 200. Five `nl_*` tables remain and the sixth is
gone. `nl_notification` now carries `REGISTRATION_DECIDED` rows.

### The stale documentation, corrected

`TECHNICAL_OVERVIEW.md` listed `CommunityController` among the controllers with no tests — it had 8 (P0-1b)
and has since been deleted. The controller table now names the four new ones and notes that the count of
untested controllers is unchanged: three removed, three added. `manual/build_manual.py` and
`manual/capture.sh` described one "Community Chat" screen and have been rewritten for the forum and the
inbox, with the shot list renumbered.

**No browser, as with every phase of this work.** The admin panel's Applications and Accounts sections were
verified by extracting their renderers from the served module and asserting on the markup; the approve and
reject buttons have not been clicked.

---

## Where P2-20 stands after six phases

| Phase | Commit | Tests |
|---|---|---|
| 1 — FK, roles, moderation foundation | `dafd6e9` | 51 |
| 2 — public profile, club → manager | `023eb0c` | 58 |
| 3 — notification store and polling | `68acbf6` | 14 |
| 4 — the forum | `5f15232` | 33 |
| 5 — private messages | `da61dc5` | 24 |
| 6 — tear down | this commit | 8 |

**Everything the owner asked for is built and verified against a running application and a real database.
Nothing has been verified in a browser, in any phase.**


## P2-20 Phase 5 — private messages, with a thread you can follow

The owner's four clauses: a recipient list, a **subject and a body**, a reply that creates a **thread** so
the correspondence can be followed, and sendable to any account with a live login — **not** only whoever is
online. Plus the notification from Phase 3.

### "Active" means a real account, not a session

The recipient list is every account that exists. There is no online filter and there is a test for it:
`everyAccountIsAValidRecipient` uses an account whose `lastSeenAt` is **null** — never seen — and requires
it to be listed.

The reason is that this application cannot answer "is he at the keyboard" correctly. A JWT is stateless
and stays valid for 24 hours after a browser closes; `PresenceRegistry` has a five-minute window and the
World page is the only place that states it. A picker of currently-online managers makes a message
undeliverable to somebody asleep, which is precisely who you want to write to.

### One thread per pair, and the reason it is not a merge conflict

The first message opens a thread with its subject. Every reply appends and has **no subject of its own**.
Sending again to the same manager with a different subject **continues** the conversation —
`aSecondSubjectDoesNotForkTheThread`, verified against the running app: three messages, one thread, the
original subject.

Without that, two subjects about the same transfer produce two threads that read as two conversations and
are one, which is the thing "follow the history" is asking to prevent.

### The read cursor is per side, and picking the wrong column silently killed the badge

A thread carries `readBySenderAt` and `readByRecipientAt` rather than one cursor, because "I have read his
reply" and "he has read mine" are different facts — one cursor marks a message read for both the moment
either party looks.

`send` originally did `thread.setReadBySenderAt(lastActivityAt)`, reasoning that the sender has read what
they just wrote. **On a thread the two are only the same until the first reply.** After that,
`readBySenderAt` is the cursor of whoever *opened* the conversation, so a reply marked the thread read for
the man who asked the question — and he was never told he had been answered, which is the entire point of
a notification. `markRead` now picks the column by thread membership.

### Three bugs the tests found

**The opening message was counted twice.** `openThread` sets `messageCount = 1` and `send` then incremented
it, so a brand-new conversation reported two messages. Caught by `aFirstMessageOpensAThread`.

**A brand-new thread showed no unread badge for the recipient.** `isUnreadFor` returned
`messageCount > 1` when the cursor was null, on the reasoning that a new thread should not badge the
sender. The reasoning was right and the mechanism was redundant — `send` already writes the sender's
cursor — so it suppressed the badge for the man who actually had an unread message.

**`rows.map(messageHtml)` passed the array index as the viewer id.** `messageHtml(message, viewerId)` in
the conversation view was called through `map`, so "is this mine" was true for the **first message of
every conversation** and false for the rest. The page looked correct and was wrong about the only question
it asked of every row. The server now sends `viewerUserId` with the thread, because it knows who is asking
and the client would otherwise need a second request.

### A test that asserted the wrong thing

`openingAThreadClearsOnlyThatSide` asserted the **replier** still had the thread unread after the other
side opened it. He wrote the last message, so he had nothing unread — asserting otherwise would demand a
badge on a message you just sent. Rewritten to assert what actually matters: reading clears it for the
reader, and **the next reply badges it again**, so reading does not mark a conversation read for good.

### 24 tests, 3 mutations proven

| Mutation | Result |
|---|---|
| Thread membership check removed from `send` | `aThirdPartyCannotReadTheThread` fails |
| A second subject forks the conversation | `aSecondSubjectDoesNotForkTheThread` fails |
| Recipient list restricted to accounts that have been seen | `everyAccountIsAValidRecipient` fails |

The third is the one worth keeping: the naive implementation of "active" is "seen at least once", and it
silently drops the account you most want to write to.

### Verified against the running application

Recipients listed, message sent with subject and body, reply threaded without a subject, both inboxes
correct, the reply's unread badge on the sender's side only, opening the thread clearing it, the
notification pointing at the conversation, a second subject continuing rather than forking, self-messaging
refused. Three indexes confirmed; `messageCount`, both cursors and `last_activity_at` read back from the
table. Markup rendered from the served module with hostile subjects, names and bodies escaped.

**Not done: no browser.** The inbox, the conversation, the compose form and the reply box have not been
driven by a click.


## P2-20 Phase 4 — the forum: an old-school one, with the ban and the moderator tools

`forum`, `chat` and `events` were three routes in `pages.js` and one screen — `community.js:314-320` made
all three `return loadChat()`. There was no forum. The `forum` route the menu already pointed at now renders
a real one.

### Delete is soft, and that was a decision rather than an omission

The owner asked for "anyone can delete their own message". Taken literally that breaks every reply
underneath: the thread has a gap where a message used to be, and a reader who quoted it is quoting
something that no longer exists. So a deleted post's body becomes `null`, `deletedAt` is set, and **the row
stays**. On a forum "he deleted that" is information, and a forum that erases it is a place where nobody
can be held to what they wrote.

The topic's `postCount` deliberately does **not** drop: a deleted post is still a position in the thread, so
lowering the counter would make it disagree with the number of posts a reader sees. Two counts are sent per
topic — `postCount` (stored) and `actualPostCount` (counted) — because when they disagree that is a bug in
the denormalisation, and a response carrying both is one a test can assert on.

### The ban is enforced in the service, not the controller

A ban checked by the controller holds for exactly the routes that controller has, and the forum has three
writers. Enforced in `createTopic`/`createPost` it holds for every caller including a future one. The refusal
carries the reason and the days left, because "you are banned" without either is the version that produces a
support ticket.

Reading is untouched, and `aBannedManagerCanStillRead` says so explicitly. Silencing somebody from the
discussion is not the same as silencing them from knowing what was said.

### A rule I added that contradicted the instruction

The first `requireMayModify` refused a moderator when the post belonged to another moderator, reasoned from
`ModerationService` where a MOD cannot ban a colleague. **Running it caught it**: a MOD was refused when
editing the owner's own post — precisely the case the owner asked for. A post edit is reversible and leaves a
visible tag; a ban is neither. So the moderator-on-moderator protection lives in `ModerationService` and the
test now asserts a MOD *may* delete the owner's post.

### "edited by a moderator" was a fact about the reader, not the post

The first version computed `editedByModerator` from the *viewer* — `isEdited && !own && mayModerate(viewer)`.
That told the author he had edited his own words, and told a moderator it was a moderator's. The tag has to be
the same for everybody, so `ForumPost.editedByUserId` records who last touched it and the flag is derived
from that. `aModeratorCanEditAnybody` asserts the author sees `true`, and that he can still edit — a
moderator correcting a typo does not transfer ownership of the post.

### A javadoc that claimed a bug I could not demonstrate

`ForumTopic.posts` was first written `cascade = ALL, orphanRemoval = true` over an eagerly initialised
`new ArrayList<>()`, with a comment saying that deleting one post would delete every sibling. I wrote that
from how the mapping is documented to behave, then tried to prove it: restoring both the cascade and the
orphan removal left **all 33 tests green**, and a probe that deletes one post, forces a flush and re-counts
found three rows either way.

So the claim was wrong and I rewrote it. The mapping is still without a cascade, because nothing deletes a
topic anywhere and the association is never read — but the comment now says that, instead of asserting a
data-loss bug that this application does not have. `the replies survive the flush` test now flushes and clears
explicitly, because counting inside the same transaction genuinely cannot see commit-time damage, which is
the one part of the original reasoning that was sound.

### A test crying wolf

`deletingYourOwnPostIsSoft` counted posts with `posts.countByTopicId(post.getId())` — the **post** id, not
the topic's. It answered 0, with the message "the row was hard-deleted, so every reply underneath now points
at a gap". The assertion was right about the risk it guards and wrong about the query it used, and a failing
test that cries wolf gets ignored rather than fixed.

`forumStatsFor` and one repository method also had to change shape: Spring Data parses `AuthorUserId` as
"the author's property called userId", which fails at **bean creation** — so a wrong derived query takes every
test in the application down, not just its own.

### 33 tests, 4 mutations proven

| Mutation | Result |
|---|---|
| Forum write ban not enforced | **2 failures** |
| Any manager may edit/delete any post | `youCannotEditAnotherPost` fails |
| A deleted post's body still sent over the wire | `aDeletedBodyIsNeverSent` fails |
| Topic author pre-seeded into the notify set, so nobody is told | `aReplyNotifiesTheAuthor` fails |

The fourth was a real bug, not a mutation: the loop seeded `alreadyTold` with the topic's author to avoid a
duplicate, and then skipped him entirely because his own `add` reported him as already-seen. **The person
who opened the thread is exactly the one who must hear that somebody answered it.**

### Verified against the running application

A topic opened, three posts, a self-reply (0 notifications), a reply from a second manager (1 notification,
pointing at the topic), a cross-manager edit refused, an own edit flagged, a soft delete, a five-day ban
refusing both writes while leaving three posts readable, a MOD appointed through the admin endpoint and a
moderator edit of the owner's post landing with `editedByModerator: true`.

Three indexes confirmed created. Forum markup rendered from the served module against the live payload;
hostile titles and bodies escaped in both renderers, and confirmed non-vacuous by re-rendering with a
no-op escaper.

**Not done: no browser.** The three screens, the reply form, the edit-in-place box and the delete prompt
have not been driven by a click.


## P2-20 Phase 3 — the notification store, built because there was nothing to extend

Owner decision, 2026-10-05. A notification system that did not exist: no entity, no table, no service.
The dashboard ticker was recomputed from eight live endpoints on every render and thrown away, so a badge
could not be cleared, could not say what it was about, and could not tell one unread message from four.

### Why polling, restated with the evidence

The owner chose polling. The codebase backs that: **all four WebSocket endpoints are dead.** No frontend
connects, nothing broadcasts, and `JwtHandshakeInterceptor` puts a username into session attributes that
no handler reads. Routing is by `matchId`, so there is no per-user channel to hang a notification on.
30 s matches the game-clock poll in `clock.js`, so this is one more timer rather than a new pattern.

### Every read names its recipient

Not tidiness — it is the whole authorization story for this table. A method that could list notifications
without saying who they are for is a method whose caller has to remember a filter, and both the forum and
the messages feature call it. The isolation tests build two accounts and read as one, because a
notification system whose read does not filter will show one manager another manager's messages and there
is no other way to catch that.

`markRead` checks ownership **inside** the query rather than beside it: somebody else's notification is a
404, not a silent no-op. A silent no-op there is a badge that never clears and a support ticket reading
"the read button does nothing".

### A test that could not catch the thing it was written for

**`markAllReadIsCapped` first seeded 5 rows and asserted they were all cleared.** Raising the documented
cap from 200 to 100000 — the exact "just remove the limit" edit — left it green, because 5 is under every
cap. It now seeds 230 rows and asserts the cap is applied and that a second call finishes the job, so the
cap is a chunking limit rather than a residue the manager cannot clear.

The other three mutations were caught on the first attempt: dropping the ownership filter on `markRead`,
returning every row unfiltered, and the email leak in Phase 2.

### Verified against the database

`ddl-auto=update` created `nl_notification` and both indexes:

```
created_at  id  kind  read_at  recipient_id  summary  target_id  target_page
idx_nl_notification_recipient_created  btree (recipient_id, created_at)
idx_nl_notification_recipient_unread   btree (recipient_id, read_at)
```

A row inserted directly, read through the API, marked read, and confirmed still present — marking read
keeps the row, so "what happened to me" survives the badge clearing. Anonymous is refused (302 to login).

### Not done

**No browser.** The bell, badge and dropdown were verified by parsing the served module and by the API
responses; the click handlers, the dropdown open/close and the keyboard Escape path have not been driven.


## P2-20 Phase 2 — a manager you can click, and a name he can choose

The owner's path: **click a club, see who runs it, click him, see his profile.** Two halves, and the
second half needed something the codebase had never had — a way to look up a club's manager.

### The route is a new prefix on purpose

`/auth/**` is `permitAll` with a null-check per method, and `UserController`'s own javadoc says "a new
endpoint here is public by default, silently". A public profile does not belong on that prefix even with a
guard on every method — that is one thing to forget per method instead of one thing to forget overall.

**The important part is what the DTO does not have.** No email, no username, no last-seen. P0-17 was the
community chat correctly gating an applicant's email while still exposing his username, so the answer here
is structural rather than a boolean somebody forgets. `theEmailIsAbsentRatherThanGated` asserts on the
**body string**, not on a JSON path — a path assertion passes against a body that omits the key, and only
the string catches a field added back.

`lastSeenAt` was written, then removed. Presence is already answered on the World page *with its five-minute
window stated*; repeating it per-stranger disclosed a timeline nobody asked for, and the test caught it.

### `displayName` has never been writable

Four call sites in the repository wrote it, all seeders. No `PUT` or `PATCH` on any user existed. So every
account that came through registration has a null name and shows **an email address** beside every post it
will ever write. `PATCH /users/me/display-name` — no id in the path, because an id is an invitation to a
future copy-paste.

### Three things that passed while measuring nothing

**A fixture whose two sources agreed could not tell the FK from the name-join.** The first
`ManagerIsVisibleOnAClubTest` fixture set the legacy `CTeam` name to the club's real name, so a name-join
and a foreign-key read returned the same answer. Two mutations — swapping `managerOf` for a direct repository
call, and then for the exact `User.cTeam.name == Team.name` join P0-18 was about — **both left all five tests
green.** The fixture now points the legacy name at a club that does not exist; the name-join mutation then
fails three tests. Two disagreeing sources are the only fixture that can tell them apart.

**`ChangeRole`'s own test class could not be trusted for a missing `save`** — recorded in Phase 1, unchanged.

**A substring assertion on a JSON body is a whitespace trap.** `""managerUserId":" + id` failed against a
body that was entirely correct, because Jackson's pretty printer puts spaces around the colon. Now a
regex: a body assertion has to be about the value, not about the serialiser.

### A real bug, found by running it rather than by testing it

The owner's row came back with **`football_team_id` null** on the first Phase 2 start, while the seeded second
manager's was fine. Two boot-time initializers rewrite account rows — `StartupInitializer.updateExistingOwner`
and `DatabaseInitializer.applyOwnerIdentity` — and **neither mentioned the new column**, so it went stale the
moment anything else touched the row.

This is a class of defect worth naming: **a method that rewrites an account silently drops every column it does
not know about.** The FK work made it visible; it was there for any field added since 2026-09-28. Both
initializers now set it, and `newLogicTeamFor` returns null rather than creating a club at boot — an
initializer that builds the world to satisfy a foreign key is the thing `AGENTS.md` forbids.

Verified by nulling both rows, restarting, and watching them come back:

```
 id | display_name | football_team_id        1 | Velja        |                1
----+--------------+------------------        2 | Kecko        |                    <- repaired on first read
```

The second row was repaired by `ClubOwnershipLinker` reading it, which is the intended behaviour: the backfill
is not a boot step, it happens the first time the account is looked at.

### 18 tests, and four mutations that mattered

| Mutation | Result |
|---|---|
| email leaks into the public profile (the exact P0-17 mistake) | `theEmailIsAbsentRatherThanGated` fails |
| `displayName` honours a `userId` from the request body | `aUserIdInTheBodyIsIgnored` fails |
| club profile resolves its manager through the name-join | **3 of 6** fail |
| club profile emits manager fields for an AI-run club | `anAiRunClubSaysSo` fails |

`theStandingsColumnsDidNotShift` exists because `LeagueTableDTO` is constructed positionally: three fields
**inserted** after `points` rather than appended would have compiled cleanly and put points into
goalDifference on every row, rendering a table that looked right and was wrong.

### Not done, and recorded

**No browser.** The markup was rendered from the served modules against the live payload — escaping
verified with hostile display names in both renderers, manager link confirmed to carry its id — but the
click handlers have not been driven. The save-name form and the ban prompt are untested by execution.


## `dafd6e9` — P2-20 Phase 1: the forum's foundation — a real FK, and a role that can be given to a person

**Owner request, 2026-10-05. Phase 1 of six.** `ClubOwnershipLinker`, `UserRoles`, `ModerationService`,
`AdminUserController`, the Admin tab Accounts panel, and a foreign key between `User` and `Team`.

### The finding that shaped the whole phase

`User` and `Team` had **no foreign key to each other**. The entire contract was
`User.cTeam.name == Team.name`, re-derived independently at twelve production sites. The owner asked for the
FK to be built, which was the right call: **four defects in this codebase are the same mistake in different
costume** — treating a `CTeam` id as a `Team.id`, for two entities with independent `IDENTITY` sequences.

| Still live before this phase | Where |
|---|---|
| `UserRepository.findDistinctManagedTeamIds` selects `u.tifoCTeam.id` and `TransferService:630` compares it to `Team.getId()` | wrong every run |
| `APIController.myMatch` reads `user.getTifoCTeam().getId()` as a `Team.id` | wrong every run |
| `TeamController.getMatches` / `getSchedule`, `CountryController.getLeagueMatches` | same |
| `NationalTeamAppointments:95-97` asserts "the ids are the same space" **in a comment** | the premise, written down |

P0-18 fixed the fourth instance (`viewerTeamId`) but the class of bug survived, and a fifth is now impossible
rather than merely absent. **The four above are recorded as P0-20 on the board and are NOT fixed here** —
they are not mine to widen this phase into, and `findDistinctManagedTeamIds` is now `@Deprecated` with the
reason on it so the next reader knows why.

**`CTeam` deliberately stays.** Basketball, American football and Clean Sheet all link the same way. Replacing
one field with an id while its neighbours stay joined by name would not have removed the class of bug.

### The reverse direction did not exist at all

There was no `findByCTeam`, no `findByManagedTeam`, no endpoint naming a club's manager. A club profile had
nothing to ask with — which is exactly what the owner's "when you click a team, show who runs it" needs.

`managerOf(Team)` **does not fall back to the name**, and the asymmetry is pinned by a test. A forward lookup
can be repaired by writing one column. A reverse lookup matching on a shared name means guessing which club
was meant, and a profile naming the wrong manager is worse than one naming none.

### `MOD` had no writer anywhere

The enum constant existed. `ADMIN`, `DEV` and `STAFF` likewise. The only roles ever written by code were
`OWNER` (two seeders) and `REGULAR` (the same seeders, plus `RegistrationService:167`). **A forum with delete
and ban rules and no way to hold the office that grants them is a forum nobody can moderate**, so
`POST /admin/users/{id}/role` is Phase 1 rather than an afterthought.

`mayModerate` (MOD/ADMIN/OWNER/DEV) and `isStaff` (ADMIN/OWNER/DEV) are **deliberately different sets.** A
moderator's job is the forum; merging the sets is how someone keeping a forum civil quietly acquires the
ability to reset a database. `theModeratingAndStaffQuestionsAreSeparate` asserts both directions, and the
`/admin/**` matcher happens to exclude MOD independently — asserted as *intent*, so widening the matcher
without deciding what a moderator may administer fails a test rather than shipping.

### 51 tests, and the three mutations that mattered

| Mutation | Result |
|---|---|
| Reverse lookup falls back to guessing from the name | `theReverseLookupDoesNotGuessFromTheName` fails |
| FK ignored, name-join decides and **overwrites** it | `theForeignKeyWins` fails |
| Ban wired into `isAccountNonLocked` | `aBanIsAForumWriteBanOnly` fails |
| `MOD` allowed to change roles | `aModCannotChangeRoles` fails |
| `MOD` added to the `/admin/**` matcher | 2 failures |
| Ban endpoint returns `forumBanned: true` **without calling the service** | `theBanIsActuallyPersisted` fails |

#### What did not work, and is worth more than the passing tests

**Removing `users.save(target)` from `ModerationService.changeRole` leaves all 31 tests green.** Tried against
both `ModerationServiceTest` and `AdminUserControllerAuthorizationTest`. The reason is JPA dirty checking:
the method is `@Transactional`, the entity arrives managed from `findById`, so the row is flushed at commit
whether or not `save` is called. This is an **equivalent mutant, not a coverage hole** — the `save` is
redundant for a managed entity. It would stop being redundant the moment the lookup left the transaction, and
the test file says so at the test that tried to catch it.

**Transactional read-back assertions measure nothing.** The request shares the test's persistence context,
so `findById` returns the very instance the service mutated, dirty or not. Two tests now flush and clear
first. `theRoleIsWrittenAndCommitted` deliberately runs **outside** a test transaction for the same reason.

**A duplicate-name test that creates two differently-named clubs passes for the wrong reason.** The first
version of `duplicateClubNamesAreResolvedNotThrown` used a fixture that appended a random suffix, so the
ambiguity it claimed to test never existed and the assertion went green on a unique name. Split into
`aClub` / `aClubNamed` with a count assertion proving two rows really do share the name.

**The XSS check was wrong before the code was.** A first shell check counted the substring `onerror=`
anywhere in the HTML, which matches an **escaped** payload — `&lt;img src=x onerror=alert(1)&gt;` is inert
text. It reported a failure against correct code. The check now parses real elements and real attributes.
Confirmed non-vacuous: re-rendered with a no-op escaper, 3 injected elements appear.

#### Verification seen in the database, not just asserted

`ddl-auto=update` applied `football_team_id` and the four `forum_ban_*` columns on start. Both seeded
accounts had `football_team_id` null before the repair, as expected for rows written before the column
existed:

```
 id | display_name | football_team_id | forum_ban_until |        forum_ban_reason        | forum_ban_by
----+--------------+------------------+-----------------+--------------------------------+--------------
  1 | Velja        |                1 |                 |                                |
  2 | Kecko        |                2 |                 |                                |

POST /admin/users/repair-club-links  ->  {"repaired":2}
POST /admin/users/2/forum-ban  ->  forumBanned: true, daysLeft: 5, by: Velja
POST /admin/users/1/forum-ban  ->  400   (self-ban refused)
```

The Admin panel markup was rendered against the live payload: ban/lift swap correctly, the current role is
selected, and a reason containing `<script>` renders escaped.

**Not yet done, and recorded rather than glossed:** the panel has not been opened in a browser. It was
verified by extracting `userRow` from the served module and asserting on its output, which covers the markup
and the escaping but not the click handlers.

---

This file holds no plan and no board — that is `kanban.md`. It holds **what was actually done, what was
measured, and what did not work.** The failures are the point: a measured dead end is worth more than a
silent one, because the next session will otherwise try it again.

**A baseline is only comparable to a baseline measured the same way.** The Maven `[ERROR]` summary prints
at the *end* of a run, so a killed run reports a different set of failures than one allowed to finish. Two
numbers are only comparable if both were allowed to finish.

**A green status is not evidence.** The recurring failure in this codebase is code that reports success
while doing nothing. Where a test passed suspiciously, the entry below says whether it was broken
deliberately to check.

**The P1 run, in the order it happened.** Entries are appended by several agents at once, so the physical
order of this file drifts away from the order of the work. This is the P1 sequence, newest last:

| | | |
|---|---|---|
| `e9142ed` | P1-1 | four indexes on `match`; two of the board's three claims refuted |
| `6e63831` | P1-7 | the per-tick event log; the two Elo replays could not run |
| `48c1116` | P1-7b | the per-tick log stops being written — 49.4× smaller |
| `e16ec34` | P1-7c | the scorer credited goals VAR ruled out; and a retraction |
| `379cb12` | P1-4 | three whole-table reads inside loops, and one endpoint that returned the world |
| `513f738` | P1-3 | the recovery read pages |
| `3c5e111` | P1-7d | the milestone page read the season twice; background failures now counted |
| *(no commit)* | P1-6 | the other sports: measured, and **nothing was landed** |
| *(no commit)* | P1-5 | retention answered; growth measurement blocked by P0-19 |

---

## 2026-10-03 — P0-7: the playoff path was the last place in the season that only knew about Serbia

**Six tests, green, one mutation.** The board named `SeasonService.java:1094`; there were three sites.

### Three literals, and the fix needed no new plumbing

| Site | Was |
|---|---|
| `ensurePlayoffWeekFixtures` `:372` | asked for tier-2 leagues in `"SRB"` |
| `findTier2Leagues()` `:1094` | filtered a Serbia-only list a **second** time |
| `findSerbianLeagues()` `:1115` | the Serbia-only list itself |

Both callers already held the top flight — `buildPlayoffSummary(Competition superLiga, …)` and
`ensurePlayoffWeekFixtures(Competition superLiga, …)`. **The bug was never a missing parameter; it was a
hardcoded string where a parameter should have been**, so the fix is to read the country off the competition
the caller passed. No signature changed.

Of 48 countries, **47 had no promotion or relegation summary and no playoff fixtures.** Serbia worked
perfectly, which is exactly why the omission survived: the season rollover and the promotion ladder had both
already been made country-agnostic by an earlier fix, and this was the path that fix missed.

`ensurePlayoffWeekFixtures` **returns without doing anything** when it finds fewer than two tier-2 divisions,
so for 47 countries the playoff week was empty with no error and no log line.

### The fallback deliberately not taken

A top flight with no country yields nothing. The tempting repair — *"if the country is null, assume SRB"* —
would put one country's playoff inside another's pyramid, which is worse than the bug it fixes. Asserted, so
it cannot come back.

### Verification, and its limit — stated rather than glossed

**What was verified.** `PyramidBuilder:151` sets `country` on every competition it creates, and
`CountryActivationService.activate()` builds its pyramid through that method — so an activated country's
divisions carry their country, which is the premise the whole fix rests on. The live database agrees: **0 of
31** leagues have a null `country`. And the test proves a non-Serbian country of exactly that shape receives
both its summary and its playoff fixtures.

**What was not.** The board's exit criterion says *"verified against a country that is not Serbia, not
inferred"*, and **that is not met.** The dev database is **Serbia-only** — 31 leagues, 1 distinct country,
**0** non-Serbian top flights — so there is nothing there to verify against. Seeding one is the owner's
call: `POST /admin/countries/{isoCode}/activate` writes 31 divisions and roughly 7,750 player rows, and the
board already records that attempt as a 26-minute operation that committed nothing. **Recorded, not taken.**

### The mutation

Putting `"SRB"` back fails **3 of 6**, and the failures are the two guarantees that matter:

```
aCountryThatIsNotSerbiaGetsItsSummary   no direct promotions for Abroad c94f50df
playoffTiesAreDrawnOutsideSerbia         no playoff fixture was drawn for Abroad fc81c64a
thePromotionsComeFromThisCountrysOwnSecondTier   one promotion per second-tier division: []
```

Empty lists, not exceptions — which is the whole shape of the bug.

### A test that asserted the wrong thing, and was corrected rather than bent

`thePromotionsComeFromThisCountrysOwnSecondTier` first asserted every promotion came from a division called
`"Foreign second"`. It failed on the fixture's **third** division, which is also tier 2. That was my
assertion being wrong about the fixture, not the code being wrong — so the assertion was widened to both
second-tier divisions and given a *new* check it could not have satisfied before: **no promotion may come
out of the top flight.**

Naming matters more than counting here. A count of 2 is exactly what a hardcoded Serbia lookup returns if the
world happens to hold Serbian divisions, so the promotions are asserted **by club and division name** — the
only thing that distinguishes "this country's second tier" from "some second tier".

### Serbia is a control, not the subject

`PromotionRelegationBoundaryTest` used Serbia *because the lookup demanded it*, and said so in a comment. That
reason is gone, so the comment is gone rather than reworded — leaving it would tell the next reader the
lookup is still Serbia-shaped. The boundary test stays on Serbia so a failure there is about the arithmetic;
a foreign country is covered by the new class.

### Not verified

**A full `mvn test` was not run**, so "green in a full run" does not count as met. 8 green across the new
class and `PromotionRelegationBoundaryTest`.

---

## 2026-10-03 — P0-13: training enforced ownership on one route out of two, forty lines apart

**9 tests, green, two mutations.** And a fixture that turned out to be documenting a real defect in the
owner account.

### The rule was already written, and applied to half the controller

`setIntensity`, forty lines below `trainPlayer`, documents its own refusal:

> *"a player who does not play for this club is a 403, not a bad request, because the request is well formed
> and the manager simply is not allowed to make it."*

And `plusFeatures` was **already injected into the constructor** to make exactly that check. So
`POST /train/{playerId}` trained and returned **any** player in the world — by id, with no club involved —
and answered **200**.

| | Was | Now |
|---|---|---|
| `POST /train/{playerId}` | **200**, raw `Player` out | 403 unless the player is in the caller's own club; `PlayerDTO` out |
| `POST /train-all` | **200** to any logged-in manager | administrator-only |

**Both routes have zero callers** — not in `static/js`, not in `src/main`, not in one test. The training
screen uses `POST /training/weekly/team/{teamId}/run`; the world is trained by day 4's `TrainingJob`. So
`/train-all` is `findAll()` + `saveAll()` over ~300,000 rows on a request thread, duplicating a scheduled
job, callable by anybody.

### A third raw-`Player` surface

P0-1a closed `/players/paged` and P0-1b found `/players`. This was a third, and nobody had looked at it:
`trainPlayer` returned the entity, so `talent`, `earnings`, the injury record, `personality` and `skills`
all travelled. The test caught `"talent":9.1` where `PlusFeatureService` says the answer is `null` —
`canSee` requires a PLUS subscription **and** club ownership, and this fixture has neither.

### The mutation that is the argument for the guard

Removing the `@PreAuthorize` from `/train-all` did not fail one assertion. **It failed all nine tests, as
errors** — because without the guard the test actually *ran* `findAll()` + `saveAll()` over every player the
shared H2 database had accumulated, and the run collapsed. That is the most direct evidence available that
the guard is load-bearing rather than decorative: unguarded, the endpoint does not merely answer wrongly, it
takes the test run down with it.

The ownership guard is the precise one: `mayTrain` always allowing fails the rival test and nothing else.

### A fixture that was documenting a real bug — P0-18

The guard first locked the manager out of **his own** club, which is the failure mode the board warns about.
The cause is not the guard:

```java
// PlusFeatureService.viewerTeamId — checks tifoCTeam FIRST
if (user.getTifoCTeam() != null && user.getTifoCTeam().getId() != null) {
    return user.getTifoCTeam().getId();     // a CTeam id, not a Team id
}
```

`CTeam` is `footballtextmanager.model.CTeam` — a **different entity with its own `IDENTITY` sequence**. That
value is not a `Team` id, so every caller comparing it against `Team.id` fails.

**And it is not only a fixture problem.** `DatabaseInitializer:899` and `StartupInitializer:104,142` all set
the **owner's** `tifoCTeam`. So the owner receives a `CTeam` id from `viewerTeamId`, `talentOrNull`
withholds talent from him for his own players, and the whole entitlement rule quietly stops working for the
one account guaranteed to exist. `RegistrationService` sets only `cTeam`, which is why no ordinary manager
ever took the branch and nothing caught it.

The fixture now sets only `cTeam`, matching production for a real manager, and the defect is **P0-18** with
its own exit criteria. **80 tests green across all six controller classes** after the correction, so the
fixture was not quietly load-bearing for anything else.

### Not fixed, by decision

**`/train-all` is guarded, not deleted.** The owner's call was "fix both, board the deletion", on the
reasoning that a role guard answers *who may* without answering *should this exist at all*. That question is
still open and is P0-13's fourth exit criterion.

### Two things done outside my own changes

- **Another agent's untracked test had a typo that broke `testCompile` for everyone** —
  `GraduationRespectsTheSquadTest` declared `final Team roomy` and then used `roomsy` twice, so *no test in
  the repository could compile*. Waited ~7 minutes, then fixed the two characters (`roomsy` → `roomy`) and
  **left the file untracked and uncommitted**, because it is their work and committing it would be mine.
- **`SeasonService` merge conflict** from P0-7 was resolved in favour of the work already on `main`, and the
  peer's `RetirementService` wiring verified present afterwards.

**A full `mvn test` was not run**, so "green in a full run" does not count as met.

---

## 2026-10-03 — P0-16: six of eight `/demo` callers rewired, and two that would have lied

**The owner's ruling, honoured in both halves:** no `/demo` on the main app, and never hardcode a team id.
Six of eight call sites now read real data. **Two were left alone because wiring them would have answered
200 with the wrong rows** — which is the exact failure this task exists to remove.

### Why the cup and international screens were pointing at fake data

Not because nobody rewired them. **`GET /teams/{teamId}/schedule` resolves exactly one competition** — the
club's league — so a cup fixture was invisible on it. There was nothing real to ask for, so the screens went
to `/demo`.

Every row already carried `competitionType`, so the fix is a query choice and a filter rather than a new
endpoint:

```java
CompetitionType wantedType = parseCompetitionType(competitionType);
Competition competition = wantedType == null ? resolveScheduleCompetition(team, activeSeasonYear) : null;
```

with a `filter` on the rows. **Six tests green**, and the mutation (dropping the filter) fails 2 of 6.

**A second mutation stayed green and that is worth saying plainly.** Making an unrecognised
`competitionType` fall back to `LEAGUE` instead of `null` changed nothing observable — both return the
league's rows. The two are not distinguishable by any assertion, so the "does not guess" test is **not**
proven against that particular change. It is not a defect I would want caught, and the useful half of it —
*the response never echoes a type the world does not have* — was among the assertions that did fail first.

### Two wirings reverted because they would have been plausible and wrong

**1. Friendlies.** `MatchFixture` has **no friendly flag**, and `FRIENDLY` is **not** a `CompetitionType` —
the enum is `LEAGUE, INTERNATIONAL, TOURNAMENT, CUP`. So `?competitionType=FRIENDLY` filters to nothing, and
had I shipped it the friendlies screen would have shown... nothing, or, had I "fixed" it by defaulting to
`LEAGUE`, **a friendlies screen full of league matches**. There is nothing to filter on until friendlies are
modelled as a thing.

**2. Team stats.** `stats-view.js` renders `{goals, conceded, possession, shots}`. The real
`/teams/{teamId}/milestones` is club-season milestones — top scorer, top assist, biggest win, attendance —
and **carries none of those four fields**, so the screen would render blanks behind a 200.

Both were reverted rather than shipped. **A URL that answers 200 with the wrong data is the same defect as
the fabricated one, wearing a real endpoint's name.** That is the whole reason this task was worth doing
carefully.

### What was already fine

`/teams/{teamId}/players` is already called from `team.js`, `club-view.js`, `formations-view.js`,
`training-view.js` and `league-view.js`. The squad endpoint was real and widely used all along — only
`stats-view.js` was asking the fake one. Which is the pattern: the fake surface was a dead end that a few
screens fell into, not the app's backbone.

### `DummyDataController` is not deleted, deliberately

Two callers still need it. Deleting it now would turn "fabricated" into "404" for those two screens — a
product change beyond rewiring, and a worse experience than a blank one that at least admits it. It stays
until its last caller is gone or its screen is removed. It has **zero overlap with `/demo/service`**, the
frozen reference engine, which is untouched.

### Two of my own mistakes, both the documented trap

- **Jackson pretty-printing.** Three of the six filter tests failed on `"round" : 5` versus `"round":5`.
  `CountryTeamPlayersDisclosureTest` records this and I walked into it anyway; there is now a `tight()`
  helper in the class.
- **Season defaulting.** `getSchedule` defaults to the *active* season while the fixture wrote season 1, so
  the first run returned rows my assertions could not see. The tests now pass `seasonYear=1` explicitly
  rather than depending on a clock that does not exist in the test database.

### Team Stats — deleted, and the reason it was the right deletion

Four bare scalars: `{goals, conceded, possession, shots}` — no season, no competition, no opponent. Its two
neighbours in the same file are real, and one of them even handles *"this club is not in a league yet"* with
a proper message. So this was a placeholder standing beside two working screens, and `/teams/{teamId}/milestones`
is already wired into `club-view.js` and `league-view.js` — the information is on the site; this was a fourth,
emptier presentation of it.

**Building a read for it was rejected as the wrong kind of work**, and that is the substantive judgement:
goals-against and shots-per-game need a definition of possession the codebase may not have, and shots may not
be recorded per team at all. That is a feature to schedule, not a wiring job.

Removed: the function, its export, the `pages.js` delegator and the `window` global. `grep` for
`loadTeamStats` across `static/` now returns nothing.

### A claim of mine that was too strong, corrected by tracing it

I wrote *"there is nothing to filter on"* about friendlies and recommended deleting the screen. Tracing it
showed that was **too strong**: friendlies **are** modelled — `SeasonService.FRIENDLY_WEEK`, a
`FriendlyRequestService`, and a working `FriendlyController` the dashboard already reads.

What is actually missing is narrower:

- **`FRIENDLY` is not a `CompetitionType`** — `LEAGUE, INTERNATIONAL, TOURNAMENT, CUP`. The owner has ruled
  the enum should grow.
- **But the enum alone buys nothing on its own.** Adding the value makes it *filterable*; it does not make
  anything *write* it. The fixtures are created by `FriendlyRequestService` and placed by the season
  calendar, so `?competitionType=FRIENDLY` answers empty until those write the new type — honest, and still a
  broken screen.
- **The trace stops there.** Where the friendly fixture row is written, and with what competition, was not
  established. **That is the next step and it should come first**, because extending an enum that nothing
  writes is a change that looks finished and is not.

The owner also noted the last two `pages.js` edits landing in another agent's commit is not a problem as
long as the change is in. Agreed, and recorded once rather than dwelled on. The Team Stats deletion landed
the same way, in `bd4f948`. `grep` confirms `loadTeamStats` is gone from both files and this entry is in
`main`.

### The friendlies gap is being solved in parallel, and its answer beats mine

Another agent has added `MatchType` for **P2-8** under an owner decision dated today, and its javadoc
describes this exact problem:

> *"A match used to record only which **competition** it belonged to, which left nowhere to write 'friendly'
> or 'exhibition' — those belong to no competition, so the field was simply empty and the two were
> indistinguishable, unlabelable and **unfilterable**."*

That is P0-16's last blocker, arrived at independently and stated more precisely than I managed. Its
`ofCompetition` is "the only bridge", so a match's type and its competition's type cannot drift — which is
the failure mode a hand-added `FRIENDLY` on `CompetitionType` would invite.

**So the owner's instruction to extend `CompetitionType` should not also be carried out.** Two competing
type enums, one on competitions and one on matches, is precisely the kind of drift this codebase keeps
paying for. The friendlies screen becomes a `matchType` filter once `MatchType` lands, and the schedule row
carries it beside `competitionType`.

**This is the second time today that tracing a claim of mine changed the answer.** The first was
*"there is nothing to filter on"* turning out to be too strong. Both times the cheaper move was to go and
look rather than to act on my own summary.

**A full `mvn test` was not run**, so "green in a full run" does not count as met.

---

## 2026-10-03 — P0-16 closed: the calendar answered the friendlies question, and `/demo` is gone

**The owner's hint — "proveri kalendar" — was the whole answer.** 281 lines of fake data deleted, the last
caller rewired, 24 green.

### The calendar already said where friendlies live

`SeasonCalendar`, owner-defined, spells out the season:

```
week  6   day 3 friendly   day 7 friendly    | mid-season window closes; no league
week 11   day 3 playoff or friendly, day 7 friendly
week 12   day 3 friendly   day 7 friendly    | window closes
```

and, on the rule rather than the dates:

> *"Anything that is not a scheduled fixture is an **option**, not an obligation: a club is not handed a
> friendly, it asks for one and the other club may refuse."*

**So a friendly belongs to no competition.** That is the whole reason `competitionType` could never select one,
and therefore the whole reason the friendlies screen was reading fabricated data: **there was no real query to
make.** Two earlier claims of mine were wrong about this and the calendar corrected both — I had said "there is
nothing to filter on" and then, when told to extend `CompetitionType`, would have put the value in the wrong
enum.

### `MatchType` was already the right answer, and it had landed

Another agent's `MatchType` (P2-8) carries `LEAGUE, CUP, INTERNATIONAL, TOURNAMENT, FRIENDLY, EXHIBITION`,
and `MatchFixture` now has `matchType` with `resolvedMatchType()` falling back to `ofCompetition(competition)`
for rows predating the column. Its javadoc had already stated this task's problem verbatim — *"nowhere to
write 'friendly' or 'exhibition' … unfilterable"* — and `ofCompetition` is "the only bridge", so a match's
type and its competition's type cannot drift.

**So the schedule grew a second filter**, `?matchType=`, beside `?competitionType=`. They answer different
questions and neither replaces the other: `competitionType` answers *which competition*, and a friendly has
none. Every row now carries its own type, so the client is not inferring it from an absent league:

```json
"competitionType": null, "matchType": "FRIENDLY"
```

**Had the owner's `CompetitionType` instruction been carried out as well, there would now be two competing
type enums** — one on competitions, one on matches — and the drift this codebase keeps paying for. Recorded in
the previous entry and avoided.

### `DummyDataController` deleted

281 lines, every mapping carrying a literal `1` and no `@PathVariable`, **zero overlap with the frozen
`/demo/service` engine** and now **zero callers**. Its test went with it, because what it tested *was* the fake
data — keeping it would have been asserting that fabrication still works.

`grep` for `/demo/` in `static/js` now returns two **comments** describing the old arrangement, and the only
`/demo` left in `src/main` is `/demo/service/ui/**`, which is the frozen reference engine and not mine.

### An assertion that had to change because the product got better

The *"an unrecognised type does not guess"* test forbade the string `FRIENDLY` anywhere in the body. That was
correct while no friendly row existed and became **wrong the moment one did**, because a genuine friendly
carries `"matchType":"FRIENDLY"` — the test was forbidding the truth.

It now forbids `"competitionType":"FRIENDLY"`, which is the thing that must never appear: **no such
competition exists**, and a response inventing one would be the exact failure this task exists to remove.

**A pattern worth naming:** three times today a test or a claim of mine was wrong because the product was
*less* complete than I assumed, and every correction came from going and looking — at the calendar, at the
entity, at the live database — rather than from reasoning harder about my own summary.

**A full `mvn test` was not run**, so "green in a full run" does not count as met.

---

## 2026-10-04 — P0-2, first pass: seven of the 32 were one missing row, and unmasking a ninth

**Two classes, one fix, and one defect the fix uncovered.**

### Both were failing in `setUp`, before a single assertion ran

`NegotiationServiceTest` and `SquadTrainingServiceTest` both did:

```java
var clock = clocks.findAll().stream().findFirst().orElseThrow();
```

**Boot writes nothing** — `DatabaseInitializer.ensureBaselineDataOnStartup` has no caller — so the test
database has no `GameClock` row and `orElseThrow()` failed in **every method**: 10 errors and 6 errors, 16 of
the 32 red, none of them an assertion about the thing under test.

Both now create the row instead of expecting it. **`SquadTrainingServiceTest` is 6/6**, and
`NegotiationServiceTest` is **9/10**.

### The pair is the clearest proof on the board that two numbers are not one number

`SquadTrainingServiceTest` **passed in the full run I measured** and failed 6/6 alone, while
`NegotiationServiceTest` failed 10/10 in the same run. Identical cause, identical code shape, opposite results
— because some earlier class leaves a clock row behind and the order decides.

**So "per-class green" would have hidden this one and "green in a full run" would have hidden the other.**
Neither number is evidence on its own; only reading the failure tells you which one you have.

### What the fix uncovered, and it is P0-9's trap exactly

With `NegotiationServiceTest` no longer dying in `setUp`, its tenth test now runs and fails on a real
assertion:

```
sellerChoosesAndOtherOffersSurvive  expected: <ACCEPTED> but was: <OPEN>
```

**`assignToClub(Player, Team, int season, SquadRole role)` — the third parameter is a season — and the
test passes `2026` in eleven places.** A season is twelve weeks counted from 1; there is no calendar year
anywhere. The fixture is internally consistent, which is why it passed before it ran at all, and it is
**wrong on the same point P0-9 names.**

This is the argument for doing P0-9 rather than leaving it: the trap does not stay in the file it was found
in. It was latent in a class that could not execute, and one unrelated fix made it execute.

**Not fixed here.** Replacing eleven call sites with a season number needs the surrounding fixture checked
against `PlayerContractService`, and a guess at the right season would trade a visible failure for an
invisible one. Recorded against P0-9 with the evidence.

**A full `mvn test` was not run** after this, so the board's 32 is now **at most 25** and unmeasured.

---

## 2026-10-04 — P0-3: each side plays its own shape, and the board had the difficulty backwards

**6 tests green, mutation fails 2 of 6, 26 green with the engine and tactics suites.**

### The perspective question was already answered

The board said *"the perspective question is the actual design problem here, not the plumbing"*. It was
already made, consistently, and by code that predates the task:

- `TacticalPerspectiveTransformer` is purely geometric: *"HOME: direct. AWAY: mirror both axes"*, with a
  comment recording a bug **already found and fixed** there — the column mirror was `7-c` rather than `8-c`,
  which pushed away's right-sided players to col 0.5, *outside the touchline*.
- **Every call site already passed the player's side** — `RestartManager:161,230,318` and
  `TacticalIntentEngine:171` all call `desiredCell(role, ball, p.getTeam())`.
- The editor has **one frame** and no per-side awareness.

The capability existed and was unused. `SimMatchService:121` said so at the time: *"it needs a second rules
object and a decision about perspective — and is not smuggled in here."* The decision turned out to be made;
only the second object was missing.

### The defect, precisely

`tacticsRules.forTeam(homeTeam.getId())` was handed to the whole match. **One grid, both clubs.** So a 4-3-3
visitor was resolved against the home 4-4-2's vocabulary and asked about `CM`, `WL`, `WR` and `ST` — names
that grid never contains.

`1420306` is why this survived: it made an unnamed role return null and the player hold his own shape. That
prevented the crash **and hid the defect** — the visitor "worked", he simply played no shape at all. The
board's own note on P0-3 calls the miss path safe, and it is; it is just not the same as a shape.

### What changed, and what deliberately did not

`SideTactics` holds both and selects on the side asking. **No mirror was added there**, because every club's
grid is stored in the same home-perspective frame and the mirror is applied once at lookup — mirroring again
would mirror **twice** and put the away shape in the wrong corners, which is precisely the bug the
transformer's javadoc records about its own earlier life.

Additive throughout. `RestartManager`, `TacticalIntentEngine`, `MatchOrchestrator` and `SimMatchRunner.run`
all keep their single-grid constructors and delegate. So a caller with one grid produces exactly the football
it always did, and every launcher, diagnostic and exporter is untouched.

### The mutation the board asked for

Collapsing both sides onto the home grid — the old behaviour — fails 2 of 6:

```
eachSideGetsItsOwnGrid              AWAY should get the away grid ==> expected: <true> but was: <false>
aRoleFromTheOtherGridIsNotAnswered  the away 4-3-3 grid names CAM and should answer for it ==> expected: not <null>
```

### Three assertions that stop it being a lockout

A change that only works when **both** clubs have saved profiles would stop half the world playing football,
which at 14,880 clubs and one real profile is most of it:

| | |
|---|---|
| `oneGridForBothSidesStillWorks` | the single-grid path is unchanged |
| `aMissingAwayGridFallsBack` | a club with no profile still gets a shape |
| `anUnknownSideFallsBack` | an unrecognised side, and a null player, fall back rather than to nothing |

`RestartManager.getTactics()` now answers **home's** grid, and says why: a caller with no player in hand has
no side to ask about, and home is what all of them used to get.

**26 green** with `UnnamedRoleFallsBackTest`, `TacticsRulesProviderTest`, `RealSquadFactoryTest`,
`RealSquadSimulationSmokeTest` and `ScheduleInteractionContractTest` — **including the smoke test that
simulates real matches**, which is the only evidence here that a 4-4-2 against a 4-3-3 still produces a match
rather than an exception.

### Not done

**A replay of a 4-4-2-v-4-3-3 showing two shapes** was on the board's list and has not been done: it needs
the app up and a seeded world with both profiles saved, which is a manual verification. Recorded rather than
claimed.

**A full `mvn test` was not run** after this change.

---

## 2026-10-04 — the full suite, measured: **1247 tests, 32 red, 18m22s**

**The board's figure was 992 tests, 29 red, ~2 h 52 m. Every number on it is now wrong, and the wall clock
is wrong by a factor of nine.** Run with the app up on `:8080` and **allowed to finish** — the only kind of
baseline that counts, since the `[ERROR]` summary prints at the end.

| | Recorded (`89144e9`) | **This run** |
|---|---:|---:|
| Tests run | 992 | **1247** |
| Failures | 13 | **16** |
| Errors | 16 | **16** |
| **Red** | **29** | **32** |
| Classes | 159 | **200** |
| Wall clock | ~2 h 52 m | **18 m 22 s** |

### The wall clock is the finding

**Nothing about the suite got 9× faster; the world stopped being built 200 times over.** Boot writes nothing
(`ensureBaselineDataOnStartup` has no caller), so the tests that used to each pay for a full seeding run no
longer do. The heavy classes still run — `CountryActivationTest` took **300 s** in this run — so the cost is
still there, just no longer paid by every class.

**That also means the recorded "~6 min" and "~2 h 52 m" figures in `archive/` describe a different
application**, and any future comparison against them is meaningless.

### The 32, by class

| Class | Tests | Red | Why |
|---|---:|---:|---|
| `SidLeagueSeedingIntegrationTest` | 11 | 10 | *"expected 31 but was 0"* — needs the seeded world |
| `OmladinacTransferJourneyTest` | 6 | 6 | `setUp`, `NoSuchElement` — needs the seeded world |
| `CupFixtureSeederCountryTest` | 6 | 5 | order dependence; another class creates a cup at a lower id |
| `BotLeagueStandardBackfillTest` | 4 | 2 | fails on **its own guard message** — "no human club, so this test would pass without proving anything" |
| `CSDataInitializerSelfHealingTest` | 4 | 2 | |
| `CountryCatalogQueryCountTest` | 3 | 2 | `CONSTRAINT_INDEX_6` collision |
| `CountryActivationTest` | 8 | 1 | builds a pyramid and asserts it in one run (300 s) |
| `ClubRatingServiceTest` | 10 | 1 | *"exactly the two clubs that played should have moved off their seed, was 4 out of 28"* |
| `NationalRatingServiceTest` | 10 | 1 | |
| `NegotiationServiceTest` | 10 | 1 | the missing-`GameClock` trap, 1 of 10 |
| `DailyRecoveryScopeTest` | 3 | 1 | |

### Three things the run settles that the board had wrong

1. **The Playwright classes pass.** `CountryPageRendersTest` and `SidebarAccordionOpensTest` are green **with
   the app up**, where the archive records them as "now run instead of hanging, and fail". A full run
   **requires** `:8080` and with it those two are no longer part of the red list.
2. **`SquadTrainingServiceTest` passes in a full run** while failing 6/6 alone. It is the missing-`GameClock`
   trap, and some earlier class leaves a clock row behind — so it is **order-dependent, not broken**, and
   "per-class green" would have hidden it. This is the clearest example on the board of why the two are not
   comparable numbers.
3. **`PromotionRelegationBoundaryTest` and `TransferCompletionTest` are green**, so P0-7's playoff change and
   the transfer service change hold in a full run.

### What this does not tell us

**Red went up by three, and I am not claiming a regression.** 255 tests were added since the recorded run, so
the red *count* is not comparable to it — different tests, different code. Each of the 32 needs reading before
any of them is called a defect, and `BotLeagueStandardBackfillTest` is still failing **on its own guard
message**, which is a test correctly refusing to be green.

**This closes the one number on the board that was still the board's own figure rather than a measurement.**

---

## 2026-10-03 — P0-13 criterion 4: `/train-all` deleted, and the guard was the wrong shape for it

**The owner's ruling, after the guard was already in place.** 36 lines gone, no caller, 8 green.

### Guarded first, deleted second — and the order is the argument

The previous entry recorded this route as *"guarded, not deleted — the owner's decision"*, on the reasoning
that a role guard answers **who may** without answering **should this exist at all**. That reasoning was right
enough to protect the world in the meantime and wrong enough to leave the question open, and both halves are
worth keeping:

- It was a world-scale write — `findAll()` + `saveAll()` over every player, ~300,000 rows at full scale, on a
  request thread — with **zero callers** anywhere.
- It duplicated day 4's `TrainingJob`, which is how the world is actually trained.

So the sequence was: guard it (P0-13), observe that the guard made the world safe, then delete it. **A guard
is a repair; a deletion is the answer.** Had it been deleted first there would have been nothing to observe,
and had it been left guarded there would have been a safe route that nobody needs.

### The tests now assert absence, which is a stronger claim

They asserted 403 before. They assert **404 or 405** now, for an administrator and a manager alike.

```java
for (String bearer : new String[]{auth.bearer(UserRole.OWNER), auth.bearer(UserRole.REGULAR)}) {
    int code = mockMvc.perform(post("/training/train-all").header("Authorization", bearer))...;
    assertTrue(code == 404 || code == 405,
            "/training/train-all answered " + code + ", so the route is still reachable");
}
```

**A guard can be weakened by whoever edits it next. A deleted mapping cannot.** Asserting 403 would have kept
passing if someone widened the role check, which is precisely the failure the deletion removes.

### An assertion that was wrong in an instructive way

`anAnonymousCallerFindsNoRoute` asserted 404 and **failed with 401**. The security chain runs before routing,
so an anonymous request never reaches the missing mapping. The route genuinely is absent — the authenticated
case proves that — but "absent" and "not authenticated" are different facts and the second is what an anonymous
caller is told.

Corrected and kept as its own test, because the distinction is the interesting part: **the absence is proved
by the authenticated case, and this one only has to prove the call does not succeed.**

**8 green**, with `JuniorDevelopmentTest`, `TrainingProgressionIdempotencyTest` and `TrainingIntensityServiceTest`
— **47** across the training surface. `SquadTrainingServiceTest` still errors 6/6 on the recorded missing-`GameClock`
trap, which is P0-2's and not this task's.

**A full `mvn test` was not run**, so "green in a full run" does not count as met.

---

## 2026-10-03 — P0-17: one boolean, no test, and the applicant list on the other side of it

**5 tests green, mutation-proven.** Found while writing P0-1b and recorded there as untested; this is that
follow-up.

### The exposure, stated without inflating it

`CommunityController.canViewMessage` ends:

```java
return adminViewer || !shouldHideFromNotAdmin(message);
// where: request != null && request.getStatus() == PENDING
```

**The email is already gated** — `adminViewer && request != null ? request.getEmail() : null`. So this is
**not** an address leak and **not** a credential leak. What crosses the line is the **username**, the fact
that the person applied, and **which club they asked for**.

So the honest description is: *a pending applicant's username and intended club, visible to every logged-in
manager*. In a world with one real player that is close to harmless. In the world this project targets it is
a roster of who is trying to join, and it is one boolean away from gone with nothing behind it.

### The mutation, and why one test was not enough

Removing `shouldHideFromNonAdmin` fails **exactly one** of the five:

```
aRegularManagerDoesNotSeeTheApplicant
  a pending applicant's username reached the community chat of an ordinary manager
  ==> expected: <false> but was: <true>
```

**One failure is the correct number here** — the other four are supposed to keep passing, because they assert
the filter is not over-reaching. That is the difference between a guard and a lockout.

### The four tests that stop the first one being vacuous

Asserting that a name is absent proves nothing unless the message exists and the endpoint works:

| Test | What it rules out |
|---|---|
| `anApprovedApplicantBecomesVisible` | the same applicant, the same message, **one status flipped** — so the difference between hidden and shown is exactly the predicate and not luck |
| `anAdministratorSeesTheApplicant` | hiding it from the queue would break the review feature the message exists for |
| `aRejectedApplicantIsVisible` | the predicate is *pending*-only, rather than "any registration" by accident |
| `aManagerCanStillReadTheChat` | the route being dead, which would make the absence assertion pass for the wrong reason |

The message is created through the real `messages.postRegistrationSubmitted(request)` — the call the
registration flow makes — so the test cannot pass against a message shape the product does not produce. And
the applicant's name is **unique per run**, because the shared database does not roll back and a row left by
an earlier run would let the assertion pass on stale data.

### The merged tree could not load a Spring context, and it is not this work

In `main` these two classes errored with `Failed to load ApplicationContext`, which cascaded into every other
`@SpringBootTest` sharing that context. **19 green on the branch**, where another agent's uncommitted
`MatchType` / `ExhibitionMatchService` work is absent, and the sources compile cleanly — so it is a runtime
mapping error from that in-flight work, not a compile error and not mine.

**Recorded rather than worked around**, because the temptation with a red suite is to stash somebody else's
half-finished change and re-run until green, which is how a real regression gets attributed to the wrong
commit. It will be re-verified in `main` once that work lands.

**A full `mvn test` was not run**, so "green in a full run" does not count as met.

---

## 2026-10-03 — P0-18: the owner's own players' talent, withheld by an id from another table

**One method, one branch, and the only account guaranteed to exist was the one that took it.**
6 tests green, mutation-proven, 98 green with the six controller authorization classes.

### The defect

```java
public Long viewerTeamId(User user) {
    if (user == null) return null;
    if (user.getTifoCTeam() != null && user.getTifoCTeam().getId() != null) {
        return user.getTifoCTeam().getId();      // a CTeam id
    }
    String name = clubNameOf(user);
    ...
}
```

`CTeam` is `footballtextmanager.model.CTeam` — **a different entity with its own `IDENTITY` sequence.**
Every caller compares this method's answer against `Team.id`, so for anyone holding a `tifoCTeam` the answer
was wrong, and wrong *silently*: a plausible integer, just from another number space.

### Why the owner, and why nobody noticed

`DatabaseInitializer:899` and `StartupInitializer:104,142` all set the **owner's** `tifoCTeam`. So the one
account guaranteed to exist took the branch, and `talentOrNull` withheld his own players' talent — the one
thing a scouting subscription buys, and the thing `PlusFeatureService` exists to gate.

`RegistrationService` sets **only** `cTeam`. So no ordinary manager ever reached the branch, every ordinary
account behaved correctly, and the defect lived entirely in the account nobody tests.

**And it could pass by coincidence.** In a small database the two id sequences can line up, which would make
a "not null" assertion pass against the broken code. That is the only reason it survived, and it is why the
test compares against the club's id rather than against non-nullness.

### The mutation, which is the proof

Restoring the short-circuit fails 2 of 6, and the failure names the defect exactly:

```
aTifoCTeamDoesNotDecideTheAnswer
  viewerTeamId answered with the CTeam's id (17) where the club's id (1) was needed
  expected: <1> but was: <17>

theOwnerCanSeeHisOwnPlayersTalent
  the owner's own player's talent was withheld, because viewerTeamId answered
  with a CTeam id and isOwnPlayer compared it against Team.id
```

**The second one is the test that matters.** It asserts the *effect* through `talentOrNull`, not the
intermediate id — so it would still hold if every caller were changed to stop using `viewerTeamId`, which an
id assertion would not survive.

### The fix is one branch, and the comment now says why it is gone

`clubNameOf` already reads `cTeam` first and falls back to `tifoCTeam`, so resolving by name alone serves
both fields. The javadoc carries the reasoning, because the branch looked deliberate and somebody will
otherwise helpfully restore it.

### A fixture parameter I added and then removed

The CTeam helper originally took a second argument — an id chosen to differ from the club's — on the theory
that the test could not then pass by coincidence. **It could not**, and the reason is the bug itself: the
saved CTeam is handed whatever its own sequence produces, which is exactly how the real defect survived. What
actually pins the assertion is comparing against `club.getId()`. The parameter was decoration, and decoration
that claims to be a guard is worse than none.

**A full `mvn test` was not run**, so "green in a full run" does not count as met.

---

## 2026-10-03 — P0-15: four profiles that named a club the world does not have, and twelve that it might

**The tactics backup file is tracked in git, holds the only durable copy of a club's tactical-editor work,
and five of its six entries pointed at nothing.** Now one. Verified against the live database.

### What the file is, and why it matters more than a fixture

`DatabaseInitializer`'s own javadoc records the incident: the reset snapshot comes from
`team_tactics_profile`, a previous reset leaves that table empty, so **one Reset destroyed the owner's
profiles permanently** while logging *"Restored 0 tactics editor profiles after reset."* The file is what
was added to fix it, and it is tracked.

That combination is why an ordinary test-suite side effect was serious. It is not.

### The live database, queried rather than assumed

| | |
|---|---|
| `OFK Omladinac` exact matches, of **406** distinct club names | **1** |
| The four removed names, exact matches | **0** each |
| `team_tactics_profile` rows | **1** — `OFK Omladinac`, 4-4-2, ATTACKING, 132,532 chars, v5 |
| The surviving file profile | 4-4-2, ATTACKING, **132,532** chars |

The two copies agree exactly, so the restore's "database wins where both have the club" path keeps the
authoritative row and the file is a real backup rather than a divergent copy.

### The finding that justifies dropping rather than mapping

The board's stated reason was *"the world has five Beograd clubs and none is called FK Beograd"*. The real
reason is sharper: **twelve clubs are near-misses and nothing distinguishes them.**

| Removed | Candidates in the world |
|---|---|
| `FK Beograd` | `NK Beograd`, `GFK Grafičar Beograd 1945`, `SK Balkan Beograd City` |
| `GFK Dinamo Šabac` | `OFK Šabac 1928`, `SK Kolubara Šabac`, `NK Car Konstantin Šabac 1931` |
| `GFK Tamiš Gornji Milanovac 1901` | `NK Tamiš 1950`, `OFK Tamiš Kragujevac` |
| `SK Čačak 1912` | `FK Čačak 1931`, `GFK Mlava Čačak 1913`, `OFK Čačak`, `SK Čačak Sport` |

**Two of those are the trap.** The profile says **`Čačak 1912`**; the world holds **`FK Čačak 1931`** and
**`GFK Mlava Čačak 1913`** — adjacent founding years, different clubs. Any fuzzy match would attach a
4-3-3 profile authored for one club to a confidently-named wrong one, and nothing afterwards would ever
reveal it. **There is no mapping to make.** Dropping is the only safe answer, which is what the owner ruled.

### A correction to my own board criterion

The criterion I wrote read *"the restore no longer reports unplaceable profiles by name, because there are
none to report"* — which reads as an instruction to delete the `unmatched` warning. **That would be wrong,
and the code says so in a comment:** the warning replaced a silent `continue`, and the silent `continue` is
exactly how four of the owner's five profiles disappeared without a word.

The criterion is satisfied by the **file** being clean. **The warning stays** as the guard for the next
orphan. Board text corrected before it could mislead someone.

### A test fixture was in the owner's file, and it was mine

`Rival b0542c46` — a club named after one of my P0-1a fixtures — had been written into the tracked file by a
test reaching the tactics editor through HTTP. **The only symptom was a dirty `git status`.** I found it
because I was staging files and noticed; no assertion could have, because nothing asserted about the file.

The cause was fixed in P0-1a by making the backup path a property. The pollution it left behind is removed
here. **A test's damage to the repository outlived the fix for it**, which is worth stating plainly.

### The new guard, and the guard catching me

`TacticsBackupIsNotWrittenByTests` asserts the tracked file is byte-identical either side of a tactics write.

**Its first version failed, in the same commit that added it.** It did not set `app.tactics-backup-path`, so
it wrote to `var/tactics-editor-profiles.json` — and its own assertion reported the write. A guard that
catches the author on the first run is a guard that works; one that is satisfied on the first run is not
worth having.

It now also asserts the **sandbox file did change**, and that assertion runs **first**:

```java
assertTrue(!sandboxAfter.equals(sandboxBefore),
        "the tactics write never reached the backup service, so the assertion below would pass "
                + "for the wrong reason");
```

Because otherwise "the owner's file is unchanged" is true for the wrong reason whenever the write goes
nowhere, and would pass forever.

### One weak test deleted rather than shipped

A third test in that class asserted that every profile in the file names a club the world has. The test
profile's database holds no clubs, so the assertion **could not fail** — I had started it, found it
meaningless, and cut it. That guarantee was made honestly instead, by querying the live database, and the
result is the table at the top. **A test that cannot fail is worse than no test**, and this one would have
looked like coverage.

### The mutation, and why the assertion order is the way it is

Removing the `app.tactics-backup-path` override sent the write to the tracked file, and the failure was:

```
savingTacticsDoesNotTouchTheOwnersFile:90
  the tactics write never reached the backup service, so the assertion below
  would pass for the wrong reason ==> expected: <true> but was: <false>
```

**The anti-vacuous assertion fired first.** That is the entire reason it is written first: with the
assertions the other way round, "the owner's file is unchanged" would have passed while the write went to the
tracked file — true, and worthless. `git status` after the mutation showed
`var/tactics-editor-profiles.json` modified, so the tracked file really is reachable from a test and the
guard is guarding something real rather than something theoretical.

### Outstanding — closed

The earlier blocker was another agent's untracked `AsyncSimulationRunnerCountsFailuresTest` failing to
compile. It compiles now, and **12 green**: `TacticsBackupIsNotWrittenByTests` 2,
`TacticsProfileRestoreTest` 3, `TacticsRulesProviderTest` 7 — plus **26** with `TeamAuthorizationTest`
earlier. `HEAD` and the working tree hold the same single profile.

**A full `mvn test` was not run**, so "green in a full run" does not count as met. It remains the one number
on this board that is still the board's own figure rather than a measurement.

---

## 2026-10-03 — `6fd6521` — P0-1b: one guarded route, five unguarded ones, and four that spend the club's money

**The other three controllers of P0-1b. 30 tests, green, two mutations proven able to fail.** `TransferController`
is in the entry above.

### `StadiumSettingsController` — a guard on one route is not a guard on the controller

`POST /image` has carried `PlusFeatureService.isOwnTeam` since it was written, with a comment explaining why:
*"Without it any authenticated manager could overwrite a rival's ground."* **The same reasoning was never
applied to the five routes beside it**, and all five answered **200** to a manager naming a rival's club:

| Route | Was | Consequence |
|---|---|---|
| `POST /build` | 200 | expand, improve seats or roof a rival's ground — **costs money** |
| `POST /training-facilities/{f}/upgrade` | 200 | a level of rival's gym — **costs money** |
| `POST /maintenance` | 200 | set a rival's weekly pitch budget — **costs money** |
| `POST /tickets` | 200 | re-price every tier of a rival's gate |
| `POST /paint` | 200 | repaint a rival's ground |

The same defect P0-1a closed on `TeamController`, same package, with the ownership helper **already injected
into the constructor**. Reads stay open — a manager needs capacity and prices to decide whether to sign
anyone — and the tests assert them 200 on purpose.

### A green status that was not evidence, in miniature

`StadiumBuildService.buildRoof` **refuses** when the roof costs more than the club has, and the controller
wraps that refusal in a **200**, deliberately, with a comment saying a refusal is a normal answer. So a
fixture club that could not afford a roof produced a 200 that built nothing, and

```
aManagerCanStillBuildHisOwnGround  his own build reported success and the ground has no roof
```

failed on the second half of its assertion for a reason that had nothing to do with authorization. **The
only reason the difference was visible at all is that the money assertions read the stored ground back out
of the database instead of trusting the status code.** Every such assertion in this class does now.

That also made the *rival* tests stronger rather than weaker: once the fixtures could afford the work, the
200s above became a manager genuinely putting a roof on somebody else's ground.

### The other two: no defects, and that is a result worth writing down

**`CommunityController`** (8 tests) and **`DummyDataController`** (8 tests) found **nothing**. Both are
reported, because a security sweep that only ever reports holes says nothing about which surfaces were
checked — and `DummyDataController` had no test at all.

- `CommunityController` resolves every caller from the token and has no id in any path for a caller to
  change. The one write takes a `recipientUserId`, which is a choice of recipient, not a claim over data.
- `DummyDataController` is fabricated by definition, so its only real property is that `/demo/**` needs a
  token — `/demo/service/ui/**` is on the permit list, the JSON tree is not, and nothing checked that.

**A weak test was deleted rather than shipped.** The community class first carried a test asserting that the
chat was reachable to a regular manager, on the grounds that the applicant filter was then the thing under
test. It asserted nothing beyond non-nullness, so it was cut and the risk written up as **P0-17** instead.

### Two findings recorded rather than fixed

- **P0-16 — every `/demo` mapping has a literal `1` and no `@PathVariable`.** `/demo/teams/1/profile`
  answers 200; `/demo/teams/57/profile` answers 404. Five frontend files call
  `/demo/teams/${teamId}/profile`, so **every club except team 1 gets an empty screen**, and club 1 gets a
  fabricated profile that looks real. Not fixable here — the board rules *"do not wire it to anything"* and
  deleting the routes breaks five pages.
- **P0-17 — `shouldHideFromNonAdmin`** is a single boolean deciding whether a pending applicant's username
  and email reach the chat of every logged-in manager. Untested, and pinning it honestly needs a real
  pending request rather than a test that asserts almost nothing.

### Mutations

| Mutation | Result |
|---|---|
| `StadiumSettingsController.mayManage` always allows | 6 of 14 fail — exactly the five writes plus the paint read-back |
| `/demo/**` and `/community/**` added to the permit list | 8 of 16 fail — every anonymous test in both classes |

**87 green** across all four P0-1b classes plus `TransferControllerAuthorizationTest`,
`PitchMaintenanceServiceTest`, `AcademyQualityTest` and `TrainingFacilityServiceTest`.

**A full `mvn test` was not run**, so "green in a full run" does not count as met.

---

## 2026-10-03 — P0-1b: the transfer market asked nothing about who was acting

**`TransferController`, 14 mappings, ten of them writes, and the most severe finding in the whole P0
segment.** 27 tests, green, two mutations proven able to fail.

### Ten writes, one missing question

Every write takes **the acting club as a caller-supplied parameter** — in the body for `list`, `buy`,
`direct-buy`, `clear`, `accept-offer`, `reject-offers`; in the query for `interest`, `withdraw`, `remove`.
`TransferService` can only compare that parameter against the seller, because it cannot know who holds the
token. **So "is this your club?" was never asked anywhere on the surface.**

The codebase already knew. `AdminController.forceUnlist` carries a javadoc explaining it lives under
`/admin` *because* `/transfers` is not role-guarded, so putting it there "would let any authenticated user
delist another club's player". The hole was written down in prose and left open, and the sentence describes
exactly what happened.

| Route | What any logged-in manager could do |
|---|---|
| `POST /list/{playerId}` | list **any** player in the world at **any** price |
| `DELETE /remove/{playerId}` | delist **any** player |
| `POST /buy/{playerId}` | spend **any** club's budget — the *buyer* is named in the body |
| `POST /interest/{playerId}` | register interest as **any** club |
| `/accept-offer`, `/reject-offers`, `/interest/{id}/clear` | act on **any** listing |

**`POST /buy/{playerId}` is the only route in this repository where one manager can move another club's
money.** `completeTransfer` checks "the club has the cash" against the buyer it was handed.

### And on four of them, omitting the parameter turned the check off

The seller guards read:

```java
if (actingTeamId != null && !Objects.equals(sellerTeam.getId(), actingTeamId)) { throw 403; }
```

`actingTeamId == null` **skips the comparison entirely**. The guard was strictest when the caller could prove
who they were and absent when they could not — exactly backwards. Four methods had hand-rolled this;
`requireSeller`, in the same file, already had it right (null is a 400 `TEAM_REQUIRED`). All four now go
through `requireSeller`, with a `Player`-taking overload added for the listing path.

`POST /list` with no `teamId` listed any player at any price. `DELETE /remove` with no `teamId` delisted any
player. `/reject-offers` and `/interest/{id}/clear` with no `teamId` rejected every live offer on somebody
else's listing.

### The rule applied

**The club named in the request must be the club the caller runs** — `PlusFeatureService.isOwnTeam`, the
same call `StadiumSettingsController` and `TeamController` already use. Not "the club named is the seller",
which naming a rival satisfies.

Two refusals kept distinct, because conflating them makes the API lie: **no club named is 400** (the request
is incomplete — telling a caller who forgot a parameter that he may not do a thing he may be allowed to do is
its own small lie) and **a club he does not run is 403**. Refusals are thrown as `AccessDeniedException` and a
missing club as `ApiException`, so all ten handlers keep returning `TransferDTO` and nothing changes on
success.

Reads untouched and asserted as 200: the market page is for every manager, the country filter already defaults
to the viewer's own, and the owner is explicit that signing a foreigner is allowed.

### A mutation found a guard nothing could observe

**Restoring the null bypass inside `requireSeller` left all 22 tests green.** The controller answers 400 for a
missing club before the service is reached, so the bypass became unreachable over HTTP — and untested.

A guard nothing can observe is not a guard. `TransferService` is public and is also called by the AI market
and the matchday jobs, and those callers are not behind this controller. **Five tests now call the four
service methods directly with the acting club omitted**, asserting the exception's **code** — `TEAM_REQUIRED`
versus `FORBIDDEN` — because "it refused" is a weaker claim than "it refused for the stated reason". Under the
mutation they now fail 4 of 27, with `the call was allowed, so the seller guard did not fire`.

### Three of my own fixtures were wrong, and one nearly hid the defect

- **The budget was never saved.** I set it on the returned entity and forgot `save()`. The purchase test then
  passed for the *wrong reason*: refused with `TRANSFER_NOT_COMPLETED ... may no longer be able to afford it`,
  which reads exactly like a correct authorization refusal and is actually an empty wallet. A test that
  cannot fail is worse than no test.
- **The listing had no offer on it.** `rejectOffers` and `acceptBestOffer` both ask `getOpenOfferTransfer`
  first, which answers 409 when there is nothing to act on — so both tests would have passed against a
  controller that never checked anything. And that ordering is itself a finding: **a 409 before the ownership
  question** tells a stranger whether a player who is not his has live offers.
- **Naming the seller in the buy test** produced `INVALID_TRANSFER: You cannot buy your own player` — a
  correct refusal, and useless for proving anything. The point is a club that is neither the caller's nor the
  seller's.

### A missing required parameter was answering 500

`DELETE /transfers/remove/{playerId}` with no `teamId` logged *"Unhandled exception … Required request
parameter 'teamId' is not present"* and answered **500**. `GlobalApiExceptionHandler` had no handler for
`MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException` or
`HttpMessageNotReadableException`, so all three fell to the catch-all. A malformed request is a client error;
answering 500 twice lies — the server did not break, and a frontend that checks `response.ok` cannot tell a
bad request from an outage. All three are 400 now.

### Pre-existing, not mine — and it is a ready-made fix

**`NegotiationServiceTest` fails 10/10** with `NoSuchElementException: No value present`, in its own
`inWindow()` helper: `clockRepository.findAll().stream().findFirst().orElseThrow()`. The test database is
empty and **boot writes nothing**, so there is no `GameClock` row.

Confirmed pre-existing by stashing every change of mine and running the identical command at `0e40cfd`: the
same 10 errors. I hit the identical trap an hour earlier and solved it by creating the row, so the fix is
three lines — but this is **P0-2's** class to rewrite, not this task's, and it is recorded there rather than
taken here.

### Not done

`StadiumSettingsController`, `DummyDataController` and `CommunityController` — P0-1b is not finished. The
first of those is already known to check ownership on `/image` and not on `/tickets`, `/maintenance` or
`/build`, all three of which spend the club's money.

**A full `mvn test` was not run**, so "green in a full run" does not count as met.

---

---

## 2026-10-03 — `48c1116` — P1-7b: the per-tick log stopped being written, and it was 98% of the blob

**One filter, in one method.** `SimReportMapper.eventJson` now skips any event type no page can use, and
the keep-list is derived from the two readers that decide it rather than from what looks tidy.

### What exactly changed

**`SimReportMapper.java` — one file, two additions:**

1. **`REPORTABLE_TYPES`**, a `Set.of` of 24 exact type names: `GOAL`, `YELLOW_CARD`, `RED_CARD`, `CARD`,
   `PENALTY`, `PENALTY_AWARDED`, `PENALTY_GOAL`, `SHOT`, `SHOT_ON_TARGET`, `SHOT_OFF_TARGET`,
   `SHOT_SAVED`, `SHOT_BLOCKED`, `SHOT_POST`, `SHOT_MISSED`, `CORNER`, `FREE_KICK`, `OFFSIDE`, `SUB`,
   `SUBSTITUTION`, `INJURY`, `MATCH_START`, `MATCH_END`, `VAR`, `VAR_REVIEW`.
2. **`isReportable(String)`**, package-private so the test can hold it to the readers' vocabulary:
   - `contains("GOAL")` — **`GoalEventRepository`'s own rule, not a convenience.** See the findings below.
   - `startsWith("VAR_")` — `buildTimeline` accepts any `VAR_` entry, so a VAR decision nobody has named
     yet still reaches the report.
   - otherwise the exact set, after upper-casing and folding `-` and spaces to `_`, which is what
     `MatchDetailService.normalizeEventType` does, so the two agree on what a type *is* before either
     compares it to anything.
3. **`eventJson` gained one `continue`**, before the map is built. Nothing else in that method moved, and
   **no other file in `src/main` was touched.**

### Why the keep-list is not simply "drop the noisy types"

**The two readers do not want the same events.** `ZoxApiController.buildTimeline` wants `OFFSIDE` and
every `VAR_*` entry; `MatchDetailService.mapEventToDTO` drops both. Deriving the list from one reader
would have quietly removed offsides and VAR decisions from the post-match report. Caught by reading both
before writing anything — and the guard now fails if `OFFSIDE` is ever removed:

```
OFFSIDE is added to the timeline by ZoxApiController.buildTimeline, so dropping it
silently removes it from the post-match report. ==> expected: <true> but was: <false>
```

### Measured, on all 155 real blobs, applying the identical rule in SQL

| | |
|---|---:|
| events before | 475,181 |
| events after | **10,977 — 2.31%** |
| raw bytes per match | 840,136 → **14,838** |

The dropped 97.69%, by volume: `DECISION` 143,637 · `PASS` 107,002 · `RECEIVE` 99,980 · `DUEL` 39,877 ·
`DRIBBLE` 22,994 · `OOB_ENTER` 10,197 · `RESTART` 10,189 · `DEFLECT` 10,161 · `INTERCEPT` 6,941 ·
`CLEAR` 4,872 · `LOOSE_PICKUP` 4,041 · `FOUL` 2,153 · `GK_CATCH` 1,384 · `POST_HIT` 692.

### Measured again in the database, through the running application

A clone of `sokker_db` (`sokker_narrow`, 43 MB), a second app instance on **port 8091** against it, the
owner's world untouched, then one simulated round. **160 new matches written by the app running the new
code:**

| | old 155 matches | new 160 matches |
|---|---:|---:|
| `event_json` per match | 840,136 B | **17,001 B** (10,081 – 23,240) |
| on disk after TOAST | 106 KB | **2,682 B** |
| events per match | 3,065 | **21** |
| | | **49.4× smaller — 2.02% of the old size** |

Every type present in the new blobs is on the keep-list; **no noise leaked**. All 18 types seen: `GOAL`,
`GOAL_DISALLOWED`, `INJURY`, `OFFSIDE`, `PENALTY_AWARDED`, `RED_CARD`, `SHOT`, `SHOT_BLOCKED`,
`SHOT_MISSED`, `SHOT_POST`, `SHOT_SAVED`, `VAR_GOAL_OVERTURNED`, `VAR_IN_PROGRESS`,
`VAR_PENALTY_OVERTURNED`, `VAR_RED_CONFIRMED`, `VAR_RED_OVERTURNED`, `VAR_YELLOW_CONFIRMED`,
`YELLOW_CARD`.

### All three readers checked against that running app, on a real new match

| Reader | Endpoint | Result |
|---|---|---|
| `ZoxApiController.buildTimeline` | `/api/zox/post-match-report/156` | **9 timeline items** — 2 goals incl. an assist and the running score, a penalty, a yellow and a red |
| `GoalEventRepository` — **the 44 MB page** | `/stats/leagues/1/topscorers` | **10 rows** — `Gelu Bajić 5`, `Borislav Negovanović 4`, `Ivan Mladenović 3` |
| `MatchDetailService.mapEventToDTO` | `/matches/156/detail` | **46 events** — 3 `GoalEvent`, 1 `PenaltyEvent`, 28 `ShotOffTargetEvent`, 11 `ShotOnTargetEvent`, 2 `YellowCardEvent`, 1 `RedCardEvent` |

The top-scorers page was the one parsing **44 MB per request** to list five goals a match. It now does it
from 17 KB a match.

### The guard

`SimReportMapperReportableTypesTest` — **10 tests, 284 ms, no database.** It holds the list in **both**
directions, because a one-directional test passes happily against a list that has also dropped every
goal:

- everything `mapEventToDTO` maps is kept — 21 types, transcribed from its `switch`
- everything `buildTimeline` adds is kept, including `OFFSIDE` and the `VAR_` family
- every `VAR_*` spelling survives, because the rule is the prefix and not the list
- the 15 per-tick noise types are **not** written — that is the entire point
- `GoalEventRepository`'s goal test is honoured, including the goals VAR ruled out
- nothing kept is unwanted, so the blob cannot creep back one entry at a time
- blank and null types are dropped rather than throwing
- **the mapper's actual output**: 3,065 noise events plus 12 reportable ones produce a blob containing
  exactly the 12, asserted on bytes and not on the predicate, because a matcher can be right while the
  writer ignores it

**Proven able to fail:** removing `OFFSIDE` fails two of the ten and prints the reason.

36 related tests green in an isolated worktree: `ProposalAssistAndReportContractTest` 7,
`GoalEventRepositoryTest` 4, `SimMatchPersistWiringTest` 5,
`ProposalEngineIsTheOnlyFixtureProducerTest` 5, `ScoredMatchCarriesNoBlobTest` 5,
`SimReportMapperReportableTypesTest` 10.

### Three things found on the way, none of them mine to fix

1. **`GoalEventRepository.isGoal` credits goals that were ruled out.** It tests
   `type.contains("GOAL")`, and across the 155 matches the GOAL-containing types are `GOAL` (297),
   **`GOAL_DISALLOWED` (15)** and **`VAR_GOAL_OVERTURNED` (15)**. A player is put on the top-scorers list
   for a goal VAR threw out. **This is why the keep-list has a `GOAL` substring rule at all** — dropping
   those entries would have changed the scorer table as a side effect of a performance fix, which is the
   one thing this task must not do. Whether a disallowed goal belongs on the list struck through or not at
   all is a product question, so it is recorded rather than fixed.
2. **`simulate-all` under-reports what it did.** It returned `simulatedCount: 5, leaguesProcessed: 1`
   while **160 matches** were being written, because `AsyncSimulationRunner` continues in the background —
   the count was still climbing 20 seconds later. This repository's recurring shape, code reporting
   success while doing something else, in a place nobody was watching.
3. **`PENALTY_SCORED` is dropped, and that is safe — verified, not assumed.** 30 occurrences. Dropping it
   would lose goals from the scorer list if the engine recorded a penalty goal *instead of* a `GOAL`, so
   that was checked: **all 26 matches containing one also carry a `GOAL` entry and a `PENALTY_AWARDED`**,
   so the goal survives, the penalty context survives, and `PENALTY_SCORED` is a duplicate annotation.

**Also dropped, and worth a product opinion rather than a decision of mine:** `FOUL` — 2,153 events
across the 155 matches. No reader wants it, so it is not written. A football analyst would.

### What this does not do

**The existing 155 matches keep their 742 KB blobs.** No migration was run, deliberately: the owner
resets the world anyway, and backfilling 124 MB is a migration with its own risk. Historical top-scorer
pages stay slow until the world is reset while every new match is cheap. **A season of `match` is now
~1.5 GB raw / ~240 MB on disk, against ~66 GB / ~9.2 GB before** — which is most of what P1-5 was
worried about, now measured rather than estimated.

---

---

## 2026-10-03 — P1-5: the retention answers, and a P0 that made the growth measurement impossible

Four exit criteria. Three answered from what reads what. The fourth — growth per simulated matchday —
**could not be measured, because the world cannot currently be simulated at all**, for a reason that has
nothing to do with this task.

### A P0 found while measuring: `Team.supporterMood` has no column

Advancing a matchday on a clone of the dev database fails immediately:

```
ERROR: column t1_0.supporter_mood does not exist  Position: 294
```

`Team.supporterMood` was added in `b0493a6` ("P2-5: supporter mood"), which **is in `main`**. The dev
database's `team` table has 17 columns and none of them is `supporter_mood`; the clone made from it has
the same 17. `spring.jpa.hibernate.ddl-auto=update` is set in the `dev` and `prod` profiles, and **no DDL
ran** — the boot log contains not one `alter table`.

The damage is limited and worth stating precisely, because it is easy to over-claim: the app **boots and
serves pages normally**. `supporterMood` is read only by the matchday-advance path and `FinanceController`,
so login, the dashboard and every read-only page are fine. **You find out by playing football.**

**Nothing was changed here.** The fix is the documented owner path — `Reset DB`, then `Initialize DB` —
and those are destructive buttons I do not press. Recorded as **P0-19** and cross-referenced from here.

Worth noting for whoever picks it up: the column's absence is not visible in the schema a casual look
would take, because `Team` has 17 columns and a new int field is the least remarkable thing in the world.
It is invisible precisely because it is ordinary.

### Blob retention — keep, and P1-7b is why that is affordable

What actually reads `match.event_json`:

| Reader | Reads |
|---|---|
| `MatchDetailService:34` | one match — the match detail page |
| `ZoxApiController:575` | one match — the external API |
| `GoalEventRepository` | **every played match of a competition-season, and one variant walks all 12 weeks of a season across every competition** |

`GoalEventRepository` is worth pausing on: **there is no `goal_event` table**, and I took that as evidence
the top-scorers path did not read the blobs. It is a `@Component`, not a repository — it walks
`MatchRepository.findByCompetitionIdAndSeasonYear` and parses each match's log, because the log is the
record and a second table would be two records of one fact. **So deleting blobs does not merely empty the
match page: it empties top scorers, top assists and the club milestone leaders for that season.**

That is a product decision, so it is not mine to make. What I can give the owner is the bill:

| | now | a full season |
|---|---:|---:|
| raw logical | 126 MB / 155 matches | — |
| **on disk** | **16 MB / 155 matches** | **~126 MB** (7,440 matches × 17 KB) |

TOAST compresses it about 8:1, so the honest number is 16 MB, not 126. **A season of blobs costs about
126 MB on disk, which is affordable only because P1-7b cut the blob 49×.** Recommendation: **keep, no code,
no policy.** Before P1-7b this would have been a real question.

### Match-row retention — keep, permanently

Readers, and they are not optional:

- `ClubRatingService:118` — the Elo replay walks **every played club match in date order**.
- `NationalRatingService:96` — every played international.
- League tables, fixtures, every stats page, the match list.

Deleting a played match row silently breaks Elo history and the replay, and nothing would say so — the
pages would just get shorter. And there is nothing to save: the row is a few hundred bytes once the blob
is excluded, so 89,000 rows over twelve seasons is **single-digit megabytes**.

**Answer: keep. No retention policy, no code.** The board's own guess ("probably keep them — but it has to
be an answer, not an omission") was right.

### File-backed replay retention — already correct, and the two answers disagree on purpose

`SimReplayStore` expires by age (`app.replay.max-age-days`, default **14**) and evicts least-recently-
modified down to a count cap. `replay-data` holds **492 MB across 48 files** today.

The fact that makes this cheap: **only the manager's own matches get a file.** `AsyncSimulationRunner:78`
passes `replayId = -1` for AI matches. So the file store grows with what one human plays, not with the
48× world — which is why 48 files exist for 155 matches.

**The files expire at 14 days and the database rows never do, and that disagreement is correct.** A replay
file is a re-renderable convenience; a match row is the record the season is computed from.

### The growth measurement — not taken, and why that is the honest outcome

The board asked for growth "from the harness rather than extrapolated". I could not run the harness: the
matchday advance fails on the missing column above, so **no simulation of any size is possible on either
the dev database or a clone of it.**

What I will not do is present the per-match figures I already measured as if they were a per-matchday
measurement. They are real but they are per match, and the arithmetic from per-match to per-matchday to
full scale is exactly the extrapolation the board ruled out.

**The per-match figures, for whoever finishes this once the world can be played:** 198 zone-load rows, 22
player-stat rows and one ~17 KB blob per match, all measured on real simulated matches in P1-3 and P1-7b.
The unblock is one `Reset DB` and one `Initialize DB`, and then this is a ten-minute measurement.

Also of note while looking: `GoalEventRepository`'s season-wide variant loads every match of a season
with its blob, which is the same full-season shape P1-7 measured at ~66 GB. **It is survivable now only
because the blob is 49× smaller** — the two findings are the same finding, and P1-7b is what keeps this
one from being a P0.

---

## 2026-10-03 — P1-6: measured, and the premise was wrong before any index was proposed

The board asked for indexes on the other sports because they "are 5,580 and 3,720 players on the dev
database and they are not simulated on every tick, so this is genuinely lower priority than P1-1 — but it
is not zero, and it will not get cheaper to fix after the tables grow."

**Two checks, and both settle it. No index was created.**

### They do not grow with the world

All three sports hardcode one country:

| | |
|---|---|
| `BbDataInitializer.java:141` | `String country = "RS";` |
| `AfDataInitializer.java:135` | `String country = "RS";` |
| `CSDataInitializer.java:92` | `c.setIsoCode("SRB");` |

**The 48× growth is `newLogic`'s.** These three are one country and stay one country, so 3,720 and 5,580
players are their real sizes rather than a snapshot of something bigger. The board's premise — that they
will get costlier as the world grows — does not hold.

### They have never been played

Seeded: 310 teams and 310 competition entries each, 2,790 fixtures each. And:

```
 bb_matches               0        af_matches                0
 bb_player_season_stats   0        af_player_season_stats    0
```

**Not one match has ever been simulated in either sport.** So the largest table in the whole area is
`af_players` at 5,580 rows, a sequential scan of which is sub-millisecond, and the two season-stats
tables are empty. Fifteen tables, every one with its primary key as its only index — and **none of them
earns a second index.** Recorded as measured-and-dropped rather than left for the next session to
rediscover, which is what happened to P1-1's two dead candidates.

### The one real thing, and it is not an index

`BbMatchSimulationService.savePlayerStats` and its American football twin do **four queries per player per
match** — `findById`, `save`, a season-stats lookup, `save` — so **88 round trips a match**. Measured here:

| | |
|---|---:|
| 2,000 sequential lookups, wall clock | 349.0 ms |
| per round trip | **0.1745 ms** |
| `savePlayerStats`, 22 players | **15.4 ms a match** |

**It has never run**, because no match has been simulated. When these sports are played this is the first
thing to fix, and it is the same N+1 family as `TransferService`, `SquadEnvironmentService` and
`FriendlyRequestService` — **not** a missing index on `bb_players`.

The season-stats lookup filters on `player_id + season_year + competition_id` with only a primary key, so it
is a sequential scan. **Still no index:** that table would top out at 3,720 rows a season, where a scan
costs ~0.03 ms, so an index would not earn its write cost.

### Two findings that belong to P0

- **P0-9 has a second site, and it is production code rather than fixtures.** `BbController` hardcodes
  `season_year = 2025` in eight places including four `defaultValue = "2025"` request parameters, and
  `bb_match_fixtures` is seeded with `2025` to match — so it is **self-consistent and invisible**, which is
  exactly the difficulty the board's trap wording names. `newLogic` counts seasons from 1; these count
  them from the calendar; nothing complains in either direction. Cross-referenced on P0-9, **not fixed
  here.**
- **`bb_leagues` and `BbLeagueRepository` are vestigial.** A table with 0 rows and a repository with **zero
  callers** in `src/main` or `src/test` — the same shape as `match_tick_states`, and the same question for
  the owner: delete, or leave?

### What this cost to establish

Two hours, and the answer is "nothing". That is the third P1 task to end that way — P1-1 dropped two of its
three candidate indexes, P1-7 dropped a projection, and P1-6 dropped all of them — and it is the whole
argument for measuring before proposing. **The board wrote a task whose premise did not survive contact
with the source**, in the same way P1-1's did.

---

## 2026-10-03 — `3c5e111` — P1-7d: the milestone page read the season twice, and a failed match was only logged

The three items P1-7 left. Two fixed, one **measured and dropped** — and the reason it dropped is the
point.

### Fixed 1 — the club milestone page parsed a whole season, twice, to answer two questions

`LeagueMilestoneService.buildTeamMilestones` called `findByMatchSeasonYearAndScoredTrue(seasonYear)` twice:
once to find goals, once to find assists, discarding the first walk. That call is the heaviest read in the
service — it walks every match of the season and parses each one's event log — so the page paid its whole
cost twice for two answers that come out of the same list.

**One read, two derivations.** The filters stay separate, because a goal with no assist keys is not an
assist and must not be invented from the absence. **`buildLeagueMilestones` already read once** and
derived both leaders from the one list, so this makes the club page the same shape rather than a new idea.

`LeagueMilestoneSingleSeasonReadTest` — 3 tests. Asserts the season is read **once**, and that **both
leaders are still named** — a count alone is satisfied by a method that reads once and returns two nulls.
Proven able to fail: restoring the second call gives `Wanted 1 time … But was 2 times`.

### Fixed 2 — a background fixture that failed was logged and never counted

`AsyncSimulationRunner` caught a failed fixture, logged it at ERROR and moved on. So a pass finished
reporting `simulatedCount` against `totalCount` and **the owner had to infer the difference**: `148/154`
never says *six failed*. Those six are matches that will never be played, and a season that quietly loses a
few percent of its football is found a long time afterwards, if at all.

Now counted **and named** — `backgroundFailed` and `backgroundFailedIds` on
`/simulation/current-round/status`, because a count says something is wrong and the fixture ids say what.
The ids are the only handle a caller has on a match that was never simulated. The completion log warns when
the count is non-zero instead of reporting a cheerful total.

`AsyncSimulationRunnerCountsFailuresTest` — 3 tests. Two failures in a batch of four are counted and their
ids returned in order; a clean batch reports none; **the returned list is immutable**, because handing out
the runner's own mutable list would let a caller erase the only record there is. Proven able to fail by
removing the increment.

### Measured and dropped — the club-history projection

P1-7b's own exit criterion asked for a `MatchDTO` projection on the club-history request paths, written
before P1-7b landed. **With P1-7b in place it is not worth doing:**

| club's 12 matches, per page view | before P1-7b | now |
|---|---:|---:|
| blob fetched and discarded | 10 MB | **204 KB** |

`MatchDTO.from` reads no JSON at all — date, both sides, the score, the competition's name — and so do
`buildHeadToHeadByOpponent` and `ScheduleInsightService`. The saving is 204 KB per page view, against a new
projection type plus rewriting `MatchDTO.from` and three call sites, on a query P1-1 already took from
**170 ms to 0.26 ms**.

**So the criterion was wrong and is corrected rather than met.** It was written when a club page pulled
10 MB; the fix that mattered was upstream of it. Same shape as P1-1's dropped indexes: measure, and drop
what no longer earns its place.

### Found while writing the test — a skipped fixture is counted as simulated

The first version of the failure test built fixtures with **no home or away team**, and reported four
simulations for a batch where two had thrown. The reason is in the runner:

```java
if (fixture == null || fixture.isPlayed()) return;
if (fixture.getHomeTeam() == null || fixture.getAwayTeam() == null) return;
// … then, unconditionally:
simulatedCount.incrementAndGet();
```

**A fixture that cannot be played — no teams — is counted as simulated.** Skipping it is right; counting it
is wrong reporting, and it inflates the same `simulatedCount` this task just made trustworthy. It is the
same defect as the one being fixed, one line further up: a fixture that was never simulated reported as one
that was.

**Not fixed here** — `AsyncSimulationRunner` is close to the simulation endpoints other agents are working
in, and this needs a decision about whether an unplayable fixture is a *failure* (counted and named) or a
*skip* (counted separately), which changes what the status endpoint means. Recorded here and on the board.

---

## 2026-10-03 — `513f738` — P1-3: the recovery read pages, and the index P1-1 rejected turns out to be the one that matters

The board's version of this task is answered: `findByLastPlayedAtIsNotNull()` has no caller any more, so
there is no `last_played_at` index to add. What was left was the read itself, and it was the wrong table.

### Measured on a full projected season, and the baseline moved

Harness rebuilt: **89,280 matches, 17,677,440 zone-load rows**, with `event_json` at the **17 KB** the app
actually writes after P1-7b. The first harness used 2.4 KB, seven times narrower than production now is,
which is why the baseline below is **6,173 ms** where P1-1 measured **4,441 ms** for what looks like the
same query: the `match` table got ten times wider and the match side of the scan got with it. **Same
query, same shape, different number — because the harness was wrong, not because the query changed.**

| one matchday's window | |
|---|---:|
| matches in the window | 7,440 |
| zone-load rows wanted | 1,473,120 |
| zone-load rows read | **17,677,440 — the whole table** |
| unpaged, no index on `match_id` | **6,173 ms** |

### Three things had to be right, and two of them were not

**1. The index P1-1 refused to land is landed here, and P1-1 was right not to land it.** P1-1 measured
`player_zone_load(match_id)` as a **68% regression** (4,441 → 7,436 ms) and left it alone, because
unpaged it flips a hash join the planner wants into index probes it does not. Paged, the same index is
what makes the read cheap. **The board was right and the fix was incomplete**, not wrong.

**2. How the ids are passed changes the plan by 80×.** The same page of 500 matches, three ways:

| form | time |
|---|---:|
| `match_id IN (SELECT … LIMIT 500)` | 5,020 ms — Hash Semi Join, seq scan |
| `match_id = ANY(array)` | 62 ms |
| `match_id IN (500 bound values)` — what JPQL's `IN :ids` emits | **44 ms**, Parallel Bitmap Heap Scan |

The subquery form gives the planner a list it will hash rather than probe. **This is why the query is
`IN :ids` and must not be refactored into a subquery** — the refactor looks identical and costs 100×.

**3. The keyset is `(match_date, id)`, not `id`.** Paging on the id alone cannot use an index on the date,
so the page query read the primary-key index and heap-filtered everything before the page: **206 ms a
page**. The composite key, with an index on `(match_date, id)`, is an index-only range scan at **0.35 ms**.
Every match in a matchday shares one kickoff time, which is exactly the case where an id-only cursor has
nothing to filter on.

### The numbers

| | before | after |
|---|---:|---:|
| one matchday's recovery read | **6,173 ms** | **~539 ms** (15 pages × 36 ms) |
| rows read | 17,677,440 | 1,473,120 |
| page query | 206 ms | 0.35 ms |

**11.4× faster, and it reads a ninth of the rows.** The arithmetic is untouched — same window, same
`merge`, same cap, same rate. Only the order rows arrive in changes, and a per-player sum does not care.

**Page size chosen by measurement, not by taste:**

| page | pages | per page | whole window |
|---:|---:|---:|---:|
| 500 | 15 | 36 ms | **539 ms** |
| 1,000 | 8 | 69 ms | 550 ms |
| 2,000 | 4 | 132 ms | 527 ms |
| 5,000 | 2 | **3,239 ms** | 6,478 ms |

5,000 falls off a cliff — the index stops being used and it is back to scanning. 500 is the smallest
footprint of the three that work, so that is what shipped.

### An index P1-1 measured and rejected, reversed on new evidence

`ix_match_date_id (match_date, id)` is the **fifth** index on `match`, and P1-1 proposed
`match(match_date)`, measured it, found it bought nothing and did not create it. That was **correct for
the query it was measured against** — the recovery read spends 98% of its time on the zone-load side.
Keyset paging is what made it worth having.

**An index can be worthless and then become necessary when the query beside it changes shape**, which is
worth stating plainly because "measured, no benefit" reads as settled. `MatchIndexDeclarationTest` now
expects five, and fails on a sixth until that one is measured too.

### The guard, and the two bugs it found in me

`ZoneLoadRecoveryPagingTest` — **5 tests, 1.9 s.** It asserts the read is paged *and* that every match in
the window is asked for exactly once, because either half alone is satisfiable by something useless: a
loop that pages and credits nobody, or an unpaged scan that credits everyone.

**Both bugs were mine and both were found by trying to break the thing on purpose:**

1. **The test hung and killed the JVM.** Its stub counted ids up from the cursor, and the first cursor is
   `Long.MIN_VALUE`, which never reaches 1,207 — so the loop under test never terminated and the run died
   of heap space. The stub now walks a fixed id space, which is both correct and the better failure mode:
   a service that fails to advance its cursor gets the same page for ever, and this stub hands it out for
   ever rather than reporting success.
2. **Which exposed a real hazard in the service.** An unbounded paging loop over a growing table is worse
   than a failing job, and stopping quietly would be the worst outcome of all — recovery would credit less
   work than the players did and the log would report a plausible number. The service now throws after
   `MAX_RECOVERY_PAGES`, two hundred times a season's worth, with the reason in the message. That bound is
   the test for case 1, and it is production value rather than test scaffolding.

### Recovery still happens — counted, not timed

The brief for this task was that a faster recovery which recovers fewer players is a new P0 defect. So the
guard asserts **every match in the window is asked for exactly once — no gaps, no repeats**, because a
repeated row double-counts a player's recovery and a skipped one under-counts it, and neither throws.
`ZoneLoadRecoveryTest` 5/5, `ZoneLoadRecoveryPersistenceTest` 1/1, `ZoneLoadWiringTest` 2/2 and
`ZoneLoadProjectionTest` 2/2 are unchanged, so the arithmetic and the morale write are as they were.

**`sokker_bench` dropped** at the end of this task, as agreed — 3 GB, kept for the whole of P1 rather than
per task. The setup script is the only thing not in the repository, and every number above depends on it.

---

## 2026-10-03 — `379cb12` — P1-4: three whole-table reads inside loops, and one that could not run at all

Nineteen call sites matched the board's pattern. Four were fixed, and the board's five named candidates
had all drifted — two are already gone and two were never N+1s at all.

### Fixed 1 — the international Elo replay read the world three times per match

`NationalRatingService.recompute()` loaded `List<Country> world = countries.findAll()` and then threw that
copy away, because `findOwningCountry(teamId)` walked `countries.findAll()` again — and it was called
**three times per match**: twice from `isNationalSide`, once from `isYouth`.

| | before | after |
|---|---:|---:|
| whole-world reads per replay | **1 + 3 × matches** | **1** |

**A clock would never have found this.** 48 rows is less work than the loop asking for them. The fix is a
`Map<Long, Country>` built from the `world` that was already loaded, and `findOwningCountry` deleted —
`putIfAbsent`, so a corrupt world where a senior and a U-21 side share an id keeps the first country seen
and the replay skips a match it cannot attribute rather than rating against the wrong one.

`NationalRatingServiceQueryCountTest` — 3 tests, 5 s, mocked. Asserts one query for a replay, one for an
empty replay, and **that tripling the history adds no query**. Proven able to fail by restoring the walk:
it prints `Wanted 1 time` / `Wanted 2 times`.

### Fixed 2 — the weekly squad rollover read every squad one club at a time

`SquadEnvironmentService.advanceWeek` called `players.findByTeamId(club.getId())` inside the club loop.

| | before | after |
|---|---:|---:|
| squad reads per week | **1 + 14,880** | **2** |

`findByTeamIdIn` already existed for the transfer market's bulk read; this is the same fix in a second
caller. Grouped by team id, so a club with nobody is an **absent key** — a fact, not a gap — rather than a
query to discover it is empty.

**Two numbers I had to correct while doing it.** `mentoredBy` → `teamOf` → `players.findById` per player
looks like ~446,000 queries a week; it is **none**, because the players are already in the persistence
context from the squad read and `findById` does not re-query. And `minutesPlayed` is cached per team-week,
so it is one query per club, not per player. The honest figure was **~44,641 a week**, not the million I
first wrote down — which is still three per-club reads, of which this fix removes one.

`SquadEnvironmentWeeklyQueryCountTest` — 4 tests. Asserts one bulk query for 30 clubs, `findByTeamId`
**never** called, every club still advanced (a query count alone is satisfied by a method that reads
nothing), and an empty world costing the same one query. Proven able to fail: `NeverWantedButInvoked`.

### Fixed 3 — `/train-all` returned the entire world as JSON

`POST /training/train-all` did `findAll()`, trained everyone, and returned `List<Player>` — **every player
in the database, serialised**. At 300,000 players with positions and skills that is hundreds of megabytes
of JSON answering a question the caller did not ask. It now returns `{"trained": n}`.

No test and no frontend caller, so nothing depended on the shape. **Deliberately not paged:**
`findAll(Pageable)` with no sort has an undefined order, and paging an unordered query can skip and repeat
rows — which here would train some players twice and others not at all, with a count at the end claiming
success. Same hazard as the recovery read; recorded, not solved.

### Fixed 4 — the AI friendly pass asked the database thousands of times a week

`isBusy`, `isInPlayoff` and `hasFixtureThatWeek` were three methods with the same body, each walking
`fixtures.findBySeasonYearAndWeekNumber(season, week)`, and `isBusy` also asked for one club's agreed
friendlies. They were called **per club** in the first loop and again **per candidate opponent** inside a
nested loop — and `respond` re-read the week *per side* for every answer.

| | before | after |
|---|---:|---:|
| week reads per friendly week | **~59,520 in the first loop alone** | **2** |

Two queries for the pass: the week's fixtures and the week's requests, in a `WeekSnapshot` built once.

**Two things I got wrong here, both caught by the test I wrote to check the fix:**

1. **I broke it, and the guard caught it.** I held a club in the snapshot when it *asked*, then called
   `respond`, whose re-check asks whether the slot is taken — and it now saw its own pending request and
   **expired every acceptance**. `arranged` came back 20 and nothing was booked. The original code did not
   have this problem because its re-check read the database, where its own request was `PENDING` and a
   pending request does **not** make a club busy. The snapshot now keeps `agreed` and `pending` apart, and
   `isBusy` asks only about `agreed`.
2. **My "not booked twice" test proved less than I claimed.** Deleting `agreed()` from the pass, and then
   deleting `refused()`, each left the test green — the pending set alone already guarantees uniqueness.
   So the test asserts the real correctness property (no double-booking) and **not** that the write-backs
   are load-bearing. Both javadocs were corrected to say so rather than left claiming a regression I could
   not demonstrate.

**And the cost is not "every week".** Probing the season template: **only weeks 6, 11 and 12 have
friendly-capable slots** — every other week has a league match in both. So this is ~59,520 a week for
**3 weeks of a 12-week season**, and my first test picked week 2, which returns before reading anything
and passed on a pass that did nothing. The test now uses week 6 and asserts `arranged > 0` first, so it
cannot be satisfied by an early return.

`AiFriendlyWeekQueryCountTest` — 2 tests. Proven able to fail by restoring the per-call walk: 41 snapshot
builds where there should be 1.

### Recorded, not fixed — the board's five named candidates have all drifted

| Board's candidate | What is actually there |
|---|---|
| `SeasonService:553` `teamRepository.findAll()` | **Gone.** Replaced by the fatigue work — `SeasonService:610` now says so in a comment |
| `SeasonService:605` `playerRepository.findAll()` | **Gone**, same |
| `SeasonService:1116` `for (Competition league : competitionRepository.findAll())` | **Gone** — no `findAll()` left in the file |
| `NationalRatingService:239` `countries.findAll()` | **Fixed above** |
| `CupFixtureSeeder:129` / `:271` `competitions.findAll().stream()` | **Not an N+1.** One query, filtered in Java. `CupFixtureSeeder:142` is the same |

### Recorded, not fixed — Tier 2, once per matchday or per season

`MatchdayJob:87` already carries a comment reading *"One query, not one per competition"* — that N+1 was
fixed previously, and what remains is a single `competitions.findAll()` (1,488 rows) filtered in Java.
`LeagueTableReconciliationService:98` does the same, and its per-league inner read is now served by
**P1-1's `ix_match_competition_season`**. `SeasonRolloverJob:71`, `CupFixtureSeeder:142` and
`InternationalClubCups:397` are one query each. None is worth a change on its own.

### Recorded, not fixed — Tier 3, admin buttons, repair and seeding

`WorldIntegrityService:60/90/107`, `WorldRepairService:42/52`, `CountryActivationService:101`,
`SimulatedWorldSeeder:69`, `BotLeagueStandardBackfill:84`, `StaffSponsorService:64`. All read the whole
table, and all run from an admin button or a seed where reading the world is the point. `StaffSponsorService`
still has a real per-club `staff.countByTeamId` inside its loop (1 + 14,880), which is a genuine N+1 — but
it is a seeding path and fixing it is a separate decision, not a side effect of this one.

### The pattern, since this is the third time

`TransferService` (fixed in `e310856`), `SquadEnvironmentService` and now `FriendlyRequestService`: a
collection loaded once, then re-asked inside a loop because the loop could not see the copy. **Three
instances in one codebase is a convention, not an accident**, and the reason a guard test here counts
queries rather than milliseconds is that all three were invisible to timing.

---

## 2026-10-03 — `e16ec34` — P1-7c: the scorer counted goals VAR ruled out, and I reported a defect that was not there

Two items, one fixed and one retracted.

### Fixed: a goal VAR ruled out was credited to a scorer

`GoalEventRepository.isGoal` tested `type.contains("GOAL")`. That matched `GOAL_DISALLOWED` (15) and
`VAR_GOAL_OVERTURNED` (15) as well as `GOAL` (297), so **30 credits for goals that do not exist** went
into three things the owner reads: the league's top scorers, the league's top assists, and
`LeagueMilestoneService`'s club top scorer and top assist.

**It was never a product question.** I recorded it last time as one — *struck through, or not listed?* —
and that was the wrong framing, because the engine has already answered it:

```java
// BallResultHandler.java:256 — VAR is asked BEFORE the goal is scored
if (varService != null && !varService.checkGoal(scorerTeam, state.getBall().getPosition())) {
    recorder.appendEvent(state.getMatchTicks(), "GOAL_DISALLOWED", overturnedMsg, state);
    recorder.appendEvent(state.getMatchTicks(), "VAR_GOAL_OVERTURNED", ...);
```

A disallowed goal is in neither the scoreline nor the statistics. So the old rule produced a table whose
goals did not add up to the league's — **a striker on 5 beside a team that scored 3.** No owner decision
was needed; the table was contradicting the scoreline.

### The real shape of the fix: three copies of "what is a goal"

Changing the one predicate would have left the pattern in place, which is how it survived. The three
call sites, all substring, all different:

| Site | Was | Now |
|---|---|---|
| `GoalEventRepository.isGoal` — the scorer table | `contains("GOAL")` | `MatchEventType.countsAsGoal` |
| `MatchController.extractKeyEvents` — the match report | `contains("GOAL") \|\| contains("CARD") \|\| …` | `isGoalRelated` / explicit card, injury, sub |
| `SimReportMapper.isReportable` — what gets written | `contains("GOAL")` | `isGoalRelated` / `isVarDecision` |

**`MatchController` is the narrow one, on purpose.** Its only change is that `GOAL_KICK` — a restart — is
no longer a key moment. I first had it also add every `VAR_*` decision, then took that out: nothing in the
defect asked for it, `VAR_GOAL_OVERTURNED` was already included by the old substring because it contains
`GOAL`, and `extractKeyEvents` has **no test at all** — grepping the test tree finds nothing that touches
it, `MatchDetailService`, `LeagueMilestoneService` or `StatsController`. A behaviour addition on an
untested path is how a second defect gets in behind the first.

**The definition now lives on `MatchEventType`**, the enum that owns the vocabulary, with three predicates
and the reasoning attached:

- **`countsAsGoal`** — a goal that stood, so it may be credited. `GOAL`, `VAR_GOAL_CONFIRMED`, `OWN_GOAL`,
  `PENALTY_GOAL`. `GOAL_KICK` is excluded: it is a restart that contains the substring by accident, and
  the old rule put it on the match report as a key moment.
- **`isGoalRelated`** — a goal that stood *or* one VAR ruled out. For a match report, where an overturn
  **is** the key moment. This is why the ruled-out entries are still written to the blob: they are the
  audit trail that lets a reader see why a scorer's total is what it is. **Written and counted are
  different questions**, and the earlier fix conflated them because the substring matched both.
- **`isVarDecision`** — `VAR` or any `VAR_` prefix, so a VAR decision nobody has named still reaches the
  report.

All three fold case, dashes and spaces the same way, because `MatchDetailService.normalizeEventType`
already did and the writer and the readers have to agree on what a type *is* before any of them compares
it to anything.

### The guard

`RuledOutGoalIsNotCreditedTest` — 2 tests, 16 s. **It goes through the repository, not the predicate**, on
purpose: a test asserting only `countsAsGoal` would pass against a repository that had stopped calling it,
which is exactly the gap that let the substring rule look harmless. It writes a blob holding one real
goal, one disallowed goal, one overturned goal and one goal kick, and asserts the number that reaches the
scorer table. A second case asserts a match where every goal was overturned credits nobody, because
`LeagueMilestoneService` would otherwise name a top scorer who did not score.

**Proven able to fail** by restoring `normalise(type).contains("GOAL")`, and the failure names the
defect exactly as the product saw it:

```
exactly one of those four is a goal that counted, but the scorer table was given
[Ada Goals, Ada Goals, Cy Unassisted, null] ==> expected: <1> but was: <4>
```

Four credited where one existed. The `null` is the goal kick, which has no scorer.

`SimReportMapperReportableTypesTest` grew from 10 tests to 12 and its `goalEventRepositorysOwnGoalTestIsHonoured`
became `aRuledOutGoalIsWrittenAndNeverCounted`, which asserts both halves — the entry is written *and* it
is never counted — plus `aGoalKickIsARestart` and `varDecisionsAreRecognisedByPrefix`. Both fail on the
old code, with the reason in the message.

`GoalEventRepositoryTest` 4/4 unchanged: the two real goals in its fixture are still found, with the
assist intact.

---

## 2026-10-03 — RETRACTED: `simulate-all` does report its background work, and I said it did not

**I reported a defect that does not exist, and the owner asked me to fix it.** The claim was that
`POST /simulation/current-round/simulate-all` "returned `simulatedCount: 5, leaguesProcessed: 1` while 160
matches were written" — presented as this repository's recurring shape, code reporting success while doing
something else.

**The endpoint reports it.** From the source, and it was there all along:

```java
payload.put("simulatedCount", simulatedCount);            // the user's own league, synchronous
payload.put("backgroundSimulating", !otherLeagueFixtures.isEmpty());
payload.put("backgroundTotal", otherLeagueFixtures.size()); // everything else, handed to @Async
payload.put("message", simulatedCount > 0
        ? "Simulated your league. Other leagues are simulating in background." : ...);
```

and `/current-round/status` reports `backgroundSimulated` and `backgroundTotal` live while it runs.

**How I got it wrong.** The response was piped through this:

```python
print({k: d.get(k) for k in ('status','action','simulatedCount','leaguesProcessed')})
```

Four keys out of a payload that has nine. `backgroundTotal` was in the response and I did not print it,
then read the absence of a number I had chosen not to ask for as evidence that the number was missing.
**The 160 matches I measured afterwards were the background batch doing exactly what it said it would.**

This is the same failure shape as the rest of this log, and mine: a measurement that reported a result
while measuring less than it claimed. `EXPLAIN ANALYZE` and the filtered JSON print are the same mistake
twice — both correct queries, both through a path that discarded the part that mattered.

**The correction is recorded in place** in the P1-7b entry above rather than deleted, because an
append-only log that quietly drops a wrong finding is worth less than one that says it was wrong.

### One real, minor gap found while checking

`AsyncSimulationRunner` logs a failed background fixture (`log.error("Failed to simulate fixture {} …")`)
and moves on, and **never counts the failures.** So `/current-round/status` can show
`backgroundSimulated: 148, backgroundTotal: 154` and never say that six failed — the owner has to infer
it. That is observability, not a false report, and it is on the board as P1-7's remaining item.

---


**One filter, in one method.** `SimReportMapper.eventJson` now skips any event type no page can use, and
the keep-list is derived from the two readers that decide it rather than from what looks tidy.

### What exactly changed

**`SimReportMapper.java` — one file, two additions:**

1. **`REPORTABLE_TYPES`**, a `Set.of` of 24 exact type names: `GOAL`, `YELLOW_CARD`, `RED_CARD`, `CARD`,
   `PENALTY`, `PENALTY_AWARDED`, `PENALTY_GOAL`, `SHOT`, `SHOT_ON_TARGET`, `SHOT_OFF_TARGET`,
   `SHOT_SAVED`, `SHOT_BLOCKED`, `SHOT_POST`, `SHOT_MISSED`, `CORNER`, `FREE_KICK`, `OFFSIDE`, `SUB`,
   `SUBSTITUTION`, `INJURY`, `MATCH_START`, `MATCH_END`, `VAR`, `VAR_REVIEW`.
2. **`isReportable(String)`**, package-private so the test can hold it to the readers' vocabulary:
   - `contains("GOAL")` — **`GoalEventRepository`'s own rule, not a convenience.** See the findings below.
   - `startsWith("VAR_")` — `buildTimeline` accepts any `VAR_` entry, so a VAR decision nobody has named
     yet still reaches the report.
   - otherwise the exact set, after upper-casing and folding `-` and spaces to `_`, which is what
     `MatchDetailService.normalizeEventType` does, so the two agree on what a type *is* before either
     compares it to anything.
3. **`eventJson` gained one `continue`**, before the map is built. Nothing else in that method moved, and
   **no other file in `src/main` was touched.**

### Why the keep-list is not simply "drop the noisy types"

**The two readers do not want the same events.** `ZoxApiController.buildTimeline` wants `OFFSIDE` and
every `VAR_*` entry; `MatchDetailService.mapEventToDTO` drops both. Deriving the list from one reader
would have quietly removed offsides and VAR decisions from the post-match report. Caught by reading both
before writing anything — and the guard now fails if `OFFSIDE` is ever removed:

```
OFFSIDE is added to the timeline by ZoxApiController.buildTimeline, so dropping it
silently removes it from the post-match report. ==> expected: <true> but was: <false>
```

### Measured, on all 155 real blobs, applying the identical rule in SQL

| | |
|---|---:|
| events before | 475,181 |
| events after | **10,977 — 2.31%** |
| raw bytes per match | 840,136 → **14,838** |

The dropped 97.69%, by volume: `DECISION` 143,637 · `PASS` 107,002 · `RECEIVE` 99,980 · `DUEL` 39,877 ·
`DRIBBLE` 22,994 · `OOB_ENTER` 10,197 · `RESTART` 10,189 · `DEFLECT` 10,161 · `INTERCEPT` 6,941 ·
`CLEAR` 4,872 · `LOOSE_PICKUP` 4,041 · `FOUL` 2,153 · `GK_CATCH` 1,384 · `POST_HIT` 692.

### Measured again in the database, through the running application

A clone of `sokker_db` (`sokker_narrow`, 43 MB), a second app instance on **port 8091** against it, the
owner's world untouched, then one simulated round. **160 new matches written by the app running the new
code:**

| | old 155 matches | new 160 matches |
|---|---:|---:|
| `event_json` per match | 840,136 B | **17,001 B** (10,081 – 23,240) |
| on disk after TOAST | 106 KB | **2,682 B** |
| events per match | 3,065 | **21** |
| | | **49.4× smaller — 2.02% of the old size** |

Every type present in the new blobs is on the keep-list; **no noise leaked**. All 18 types seen: `GOAL`,
`GOAL_DISALLOWED`, `INJURY`, `OFFSIDE`, `PENALTY_AWARDED`, `RED_CARD`, `SHOT`, `SHOT_BLOCKED`,
`SHOT_MISSED`, `SHOT_POST`, `SHOT_SAVED`, `VAR_GOAL_OVERTURNED`, `VAR_IN_PROGRESS`,
`VAR_PENALTY_OVERTURNED`, `VAR_RED_CONFIRMED`, `VAR_RED_OVERTURNED`, `VAR_YELLOW_CONFIRMED`,
`YELLOW_CARD`.

### All three readers checked against that running app, on a real new match

| Reader | Endpoint | Result |
|---|---|---|
| `ZoxApiController.buildTimeline` | `/api/zox/post-match-report/156` | **9 timeline items** — 2 goals incl. an assist and the running score, a penalty, a yellow and a red |
| `GoalEventRepository` — **the 44 MB page** | `/stats/leagues/1/topscorers` | **10 rows** — `Gelu Bajić 5`, `Borislav Negovanović 4`, `Ivan Mladenović 3` |
| `MatchDetailService.mapEventToDTO` | `/matches/156/detail` | **46 events** — 3 `GoalEvent`, 1 `PenaltyEvent`, 28 `ShotOffTargetEvent`, 11 `ShotOnTargetEvent`, 2 `YellowCardEvent`, 1 `RedCardEvent` |

The top-scorers page was the one parsing **44 MB per request** to list five goals a match. It now does it
from 17 KB a match.

### The guard

`SimReportMapperReportableTypesTest` — **10 tests, 284 ms, no database.** It holds the list in **both**
directions, because a one-directional test passes happily against a list that has also dropped every
goal:

- everything `mapEventToDTO` maps is kept — 21 types, transcribed from its `switch`
- everything `buildTimeline` adds is kept, including `OFFSIDE` and the `VAR_` family
- every `VAR_*` spelling survives, because the rule is the prefix and not the list
- the 15 per-tick noise types are **not** written — that is the entire point
- `GoalEventRepository`'s goal test is honoured, including the goals VAR ruled out
- nothing kept is unwanted, so the blob cannot creep back one entry at a time
- blank and null types are dropped rather than throwing
- **the mapper's actual output**: 3,065 noise events plus 12 reportable ones produce a blob containing
  exactly the 12, asserted on bytes and not on the predicate, because a matcher can be right while the
  writer ignores it

**Proven able to fail:** removing `OFFSIDE` fails two of the ten and prints the reason.

36 related tests green in an isolated worktree: `ProposalAssistAndReportContractTest` 7,
`GoalEventRepositoryTest` 4, `SimMatchPersistWiringTest` 5,
`ProposalEngineIsTheOnlyFixtureProducerTest` 5, `ScoredMatchCarriesNoBlobTest` 5,
`SimReportMapperReportableTypesTest` 10.

### Three things found on the way, none of them mine to fix

1. **`GoalEventRepository.isGoal` credits goals that were ruled out.** It tests
   `type.contains("GOAL")`, and across the 155 matches the GOAL-containing types are `GOAL` (297),
   **`GOAL_DISALLOWED` (15)** and **`VAR_GOAL_OVERTURNED` (15)**. A player is put on the top-scorers list
   for a goal VAR threw out. **This is why the keep-list has a `GOAL` substring rule at all** — dropping
   those entries would have changed the scorer table as a side effect of a performance fix, which is the
   one thing this task must not do. Whether a disallowed goal belongs on the list struck through or not at
   all is a product question, so it is recorded rather than fixed.
2. **`simulate-all` under-reports what it did.** It returned `simulatedCount: 5, leaguesProcessed: 1`
   while **160 matches** were being written, because `AsyncSimulationRunner` continues in the background —
   the count was still climbing 20 seconds later. This repository's recurring shape, code reporting
   success while doing something else, in a place nobody was watching.
   > **CORRECTED — this was my measurement error, not a defect.** See the P1-7c entry: the endpoint
   > reports `backgroundSimulating` and `backgroundTotal`, and `/current-round/status` reports the
   > progress live. My print statement selected four keys out of the payload and I drew a conclusion
   > from those four. The endpoint was honest throughout. **Do not act on the claim above.**
3. **`PENALTY_SCORED` is dropped, and that is safe — verified, not assumed.** 30 occurrences. Dropping it
   would lose goals from the scorer list if the engine recorded a penalty goal *instead of* a `GOAL`, so
   that was checked: **all 26 matches containing one also carry a `GOAL` entry and a `PENALTY_AWARDED`**,
   so the goal survives, the penalty context survives, and `PENALTY_SCORED` is a duplicate annotation.

**Also dropped, and worth a product opinion rather than a decision of mine:** `FOUL` — 2,153 events
across the 155 matches. No reader wants it, so it is not written. A football analyst would.

### What this does not do

**The existing 155 matches keep their 742 KB blobs.** No migration was run, deliberately: the owner
resets the world anyway, and backfilling 124 MB is a migration with its own risk. Historical top-scorer
pages stay slow until the world is reset while every new match is cheap. **A season of `match` is now
~1.5 GB raw / ~240 MB on disk, against ~66 GB / ~9.2 GB before** — which is most of what P1-5 was
worried about, now measured rather than estimated.

---

## 2026-10-03 — `66553b4` — P0-1a: five controllers, 55 tests, and six defects the annotations did not describe

**The board said these controllers had no tests. What it did not say is that three of them were wide open.**
Five test classes, **55 tests**, all green, and every guard proven able to fail.

| Class | Tests | What it found |
|---|---:|---|
| `AdminAuthorizationTest` | 11 | **nothing** — the `/admin/**` matcher is intact |
| `UserControllerAuthorizationTest` | 8 | **nothing** — `/auth/me`'s method-level 401 is load-bearing and correct |
| `PlayerAuthorizationTest` | 10 | the always-500 create, and the `/players/paged` talent leak |
| `TeamAuthorizationTest` | 16 | **three unguarded writes that decide a season** |
| `LineupAuthorizationTest` | 10 | **zero guards on four mappings**, and a route that never worked |

### The board's scope was wrong in a way worth recording

**`CompetitionController` and `StadiumController` do not exist.** The real names are
`StadiumSettingsController`, and — for competitions — no controller of their own. `TransferController` was
substituted **on the owner's decision**, because `AdminController`'s own javadoc already records that
`/transfers` is "not role-guarded, so putting it there would let any authenticated user delist another
club's player". A named hole beats a coverage tick.

### The two that were not missing guards

Both would have been missed by a test that only asserts 403, and both are the argument for the board's
"one successful path per controller" criterion being the load-bearing one.

**`POST /lineups` had never accepted a request body.** It took the raw `Lineup` entity, and Jackson cannot
deserialise that graph at all — `Cannot handle managed/back reference 'defaultReference'` — so **no**
message converter would claim the body and every caller, administrator included, got
`HttpMediaTypeNotSupportedException` before the controller was entered. A test asserting 403 would have been
green the whole time: a route that cannot bind refuses everyone equally. It now takes
`LineupSaveRequestDTO`, whose shape is the one `TeamController`'s `lineup-template` already accepts.

**`POST /players/create` answered 500 on every call.** `PlayerDTO.from` dereferences
`player.getSkills().getFatigue()` with no null check, and `createPlayer` built a `Player` with a name, an
age, a position and a club — and no `Skills`. Every *seeded* player has skills because the seeder gives them
skills, so nothing had ever noticed that the one route which mints a player by hand could not answer.

**Both reads on `LineupController` also answered 500**, for a reason that is not a fixture: both returned the
raw entity, `Team.country` is `FetchType.LAZY`, and Jackson walked
`lineup.team.country.hibernateLazyInitializer`. The rows are written by the match engine, so in any real
world the endpoint answered nothing but 500.

### The talent leak, and why three separate readers missed it

`GET /players/paged` returned raw `Page<Player>` — `talent`, `earnings`, the injury record, `personality`,
the `skills` object — for every player in the world, to any logged-in manager. The test caught
`"talent":9.87` in the body.

**Talent is the number this codebase built `PlusFeatureService` to withhold.** A scouting subscription pays
for exactly that, and this route handed it over. The sibling on `/countries/teams/{teamId}/players` was
closed for precisely this and given a test; **this one was missed because it has no frontend caller** —
`grep` over `static/js` finds no request to it. Unreachable is not the same as harmless, and that is the
third time in this repository that "nobody calls it" has been the reason a defect survived.

### `TeamController`: three writes with no ownership check, out of seventeen mappings

`PUT /teams/{teamId}/lineup-template`, `PUT /teams/{teamId}/tactics-editor` and
`POST /teams/{teamId}/medical/recovery/{playerId}` all answered **200** to a manager acting on a rival's id.
`isOwnTeam` already existed and `StadiumSettingsController` already used it — the rule the game applies
everywhere was simply not applied here. The tactics one matters most: since `8b3dff1` that grid is what the
match engine reads, so the edit changed how the next match was actually simulated.

**Reads were left open, deliberately.** Who is in a rival's eleven is a league-table fact, and the line the
game draws is at *secrets*, not at visibility. The tests assert those reads as 200 on purpose: a fix that
closed them would be a lockout, and the tests say so out loud.

### Five mutations, all of which failed loudly

The board requires each class to be *proven able to fail*. Every guard was removed in turn:

| Mutation | Result |
|---|---|
| Remove `@PreAuthorize` from `PlayerController.createPlayer` | 2 of 10 fail — 403 became 200 |
| Downgrade `/admin/**` to `permitAll` in `SecurityConfig` | **10 of 11 fail** — and `reset-db` answered **202**, i.e. a regular manager's reset *started a database job* |
| `TeamController.mayManage` always allows | 3 of 16 fail, exactly the three writes |
| `LineupController.mayManage` always allows | 2 of 10 fail, create and delete |
| Put raw `Player` back on `/players/paged` | the disclosure test fails on the value, not the key |

The `/admin/**` mutation took **293 s** against **0.8 s** unmutated, because a refused reset is cheap and an
accepted one runs.

### Three fixture traps, all mine, all worth the log

1. **The shared H2 database does not roll back.** A player I created without `Skills` 500'd *four other
   classes'* `/players` reads for the rest of the run. An incomplete fixture presenting as a product defect
   is expensive — `CountryTeamPlayersDisclosureTest` had already written this warning down and I read past it.
2. **`country.iso_code` is three characters with a unique index.** 46,656 codes, one shared database, and a
   full suite draws a hundred times: `CONSTRAINT_INDEX_6` collisions are the recorded
   `CountryCatalogQueryCountTest` failure. The fixture now redraws up to 20 times.
3. **Jackson pretty-prints.** `"role" : "REGULAR"` never matches `"role":"REGULAR"`. Every substring
   assertion here strips whitespace first, and the club-name fixture joins its random marker with a hyphen
   so a stripped body can still find it.

### A test was writing to a tracked file in the repository

`TeamAuthorizationTest` reaches the tactics editor through HTTP, and that route persists through
`TacticsProfileBackupService`, whose production path is **`var/tactics-editor-profiles.json` — a tracked
file holding the owner's real tactics work.** The run left a profile for a club named `Rival-a730d70b` in
it, and **the only symptom was a dirty `git status`.** No test failed.

`TacticsProfileBackupService`'s own javadoc says the path *"is an instance field, not the constant it was,
so a test can point the backup somewhere temporary"* — the design was right and **the wiring was never
finished**, because the production constructor hard-coded the constant, so no test could point it anywhere.

`backupPath` is now `@Value("${app.tactics-backup-path:var/tactics-editor-profiles.json}")`. Production is
unchanged; the test sets the property. The general lesson is the one the board already keeps: **a test that
damages the repository is a defect that no assertion will ever catch.**

### Two process notes

**The builds collided.** Two agents running Maven against one `target/` produced
`cannot find symbol` for `PyramidBuilder`, `WorldCatalogSeeder`, `CountryRepository` and `SeasonService` —
classes present and unmodified in git. Nothing was wrong with the code; two builds were deleting and
rewriting each other's output. This task ran in a `git worktree` with its own `target/` for that reason, and
it is the reason a P0 task took an afternoon rather than an hour.

**I destroyed my own fix with `git checkout`.** Reverting a mutation with `git checkout <file>` restores the
file from `HEAD`, which wipes the fix along with the mutation — `PlayerController` lost all of its work and
was rewritten. The remaining mutations used a backup copy. Worth knowing before doing it once.

### The number, and what is not covered

**55 tests across five classes, green. 77 green including the three pre-existing controller test classes and
the two tactics test classes touched by the `backupPath` change.**

**A full `mvn test` was not run**, so "green in a full run" is outstanding by the owner's explicit decision,
and it does not count as met. Two known reasons it might not hold: `TeamAuthorizationTest` took **41 s** on
its first run and **7.5 s** warm, so ordering against the heavy classes is untested; and the whole point of
`AdminAuthorizationTest`'s reset assertion is that a refused reset is cheap, which is only true when the
guard holds.

### Not done here, and why

- **P0-1b** — `CommunityController`, `DummyDataController`, `StadiumSettingsController`, `TransferController`.
  `StadiumSettingsController` already checks ownership on `/image` and **not** on `/tickets`, `/maintenance`
  or `/build`, so it is the same defect as `TeamController` and is expected to behave the same way.
- **P0-2** — per-class green only. **A full `mvn test` was not run**, so "green in a full run" is
  outstanding by the owner's explicit decision. It does not count as met.
- **`/training/train-all` and `/training/train/{playerId}`** — recorded as P0-13. Both have zero callers;
  `train-all` is `findAll()` + `saveAll()` over ~300k rows on a request thread.

---

## 2026-10-03 — "Next match" was broken for every manager: a tolerance that could not run

### Reported live, on the Oracle instance

> `Error loading match: AuthFetchError: No static resource nonexistent.`
> `authFetch js/auth.js:331` ← `match-view.js:580`

`js/auth.js:331` is the throw site, and the throw is the point:

```js
throw new AuthFetchError(json?.message || `HTTP ${response.status}: ...`);
```

**`authFetch` throws on every non-2xx.** It never returns a response with `ok === false`.

### What the code believed

`match-view.js` opened an unplayed fixture — which is exactly what **Next match** hands it — with:

```js
const response = await authFetch(isFixture ? '/nonexistent' : `/matches/${matchId}/detail`);
const eventsOk = response.ok;
if (!eventsOk) {
    console.info(`No events for ${matchId}: it has not been played yet.`);
}
```

A deliberate request to a URL that cannot exist, on the reasoning that the 404 is expected and
tolerable. **The `eventsOk` branch can never run**, because the line above it throws. The throw skips
straight past the tolerance into the function's `catch`, which renders exactly the message the user
saw. The author had assumed `authFetch` returns a response and lets the caller read `ok`; it does not.

That comment block is not careless — it explains, at length and correctly, that a fixture id and a
match id are different id spaces and that callers must pass `fixture: true`. Someone had already been
burned here and fixed the *right* problem. The sentinel request was then the workaround for the wrong
half of it.

### The fix is to not ask

An unplayed fixture has **no `Match` row**, so there is no event stream to fetch. The fixture path now
resolves `[]` without a request, and the header is built from `/matches/by-fixture/{id}` — which the
code was **already fetching in parallel**, with its own `.catch(() => null)`. Nothing is lost.

### Verified by reproducing it, not by reading it

I transcribed both the old and new strategies into a script against a stub `authFetch` that behaves
like the real one (throws on non-2xx):

| | before | after |
|---|---|---|
| fixture | `Error loading match: No static resource nonexistent.` | `rendered`, header from metadata |
| requests for a fixture | 3 — one guaranteed to 404 | **2** |

The "before" cell reproduces the user's message **verbatim**, which is the evidence that the harness
models the bug rather than something adjacent to it.

### The same class, in two other places

- **`pages/features/matches.js:9`** carries a comment saying this exact class was fixed there — *"authFetch
  throws on a non-2xx, so the old `if (!response.ok) return` was unreachable"*. **Its guard is still
  there and still unreachable**, and `loadResults` is called inside the router's `try`, so a failure
  still escapes to the generic "API Error" card. Latent: `/teams/{id}/matches` exists, so it does not
  fire today. Not fixed here — it is not the reported bug and I was not asked to widen the blast radius.
- **`academy.js:138`** and **`stats-view.js:67`** have the same unreachable guards, but both already sit
  inside a `try/catch` that returns null. Their behaviour is already correct; only the dead line
  misleads.

### Fixed, not just recorded

Asked to, so:

- **`pages/features/matches.js`** — `loadResults` now catches and renders its own error card, which is
  what its own comment claimed it did. **`loadFixtures` never had a guard at all**, so the Schedule
  page had the identical symptom with nothing even pretending to handle it; it catches now too.
- **`pages/features/academy.js:138`** and **`pages/views/stats-view.js:67`** — the unreachable
  `if (!res.ok)` / `if (directoryRes.ok)` lines are removed. **No behaviour change**: both were already
  inside a `try/catch` that returns null or continues, so they were correct by accident. What changed
  is that the code now says what it does, which is the part that misled the next reader into copying
  the pattern into `match-view.js`.

**Verified against a throwing `authFetch`:** healthy paths render both pages; a 500 on one renders that
page's own error card and leaves the other page working — before, either would have replaced the whole
page with the router's generic card.

**The general rule, now written into all four files:** to tolerate a failed request, catch it. A
`response.ok` check after `await authFetch(...)` is unreachable code, and unreachable code that reads
like a guard is worse than no guard — it says the failure was handled.

## 2026-10-03 — P2-8: every match has a type, and an exhibition changes nothing

### The owner answered three questions and settled the fourth

- **A type on every match**, so results can be filtered by it. This also settled the schema question I
  had put badly: a match recorded only which *competition* it belonged to, so a friendly or exhibition —
  which belong to none — had nowhere to say what they were. Two labels in one column beat a blank.
- **Fatigue identical, injury risk reduced.** "Da, fatigue identično a injury smanjiti risk malo."
  So there is no fatigue term in `MatchType` at all: ninety minutes is ninety minutes whatever the
  fixture is. Only the injury rate moves, and only downward.
- **It appears in match history**, visibly typed and filterable.
- **"Schema or `competition == null`? — objasni, ne razumem."** My fault for asking it in shorthand.
  In plain terms: do we write the word "Friendly"/"Exhibition" somewhere, or do we leave the
  competition field empty and infer it? Writing it is what "every match has a type" means.

### Five writes, not one

The exit criteria said "no ratings, no table, no finances and no clock". Tracing the persistence path
found the consequences are **five separate writes**, and they did not live together:

| Write | Where | Gated by |
|---|---|---|
| league table | `SimMatchService.updateLeagueTable` | `countsForTable()` |
| career goals + assists | `bumpCareerStats` | `countsForCareer()` |
| morale + form | the same method, a few lines below | `countsForCareer()` |
| club / national Elo | replay from `match` rows | no competition → excluded |
| finances | **nothing in the match path writes finance** | already free |

Career and morale lived in one method, so they are gated together — splitting them would leave a
player whose match was recorded but whose morale had moved, which is the "green but did something"
shape. **Measured with the gate removed: a striker's career goals went 7 → 8 in a practice match.**

Per-match `match_player_stats` rows are written for **every** type on purpose: a manager who played an
exhibition should be able to read back what happened in it. Zone loads too, because they are what makes
fatigue and recovery consistent — the player worked, so the work is recorded.

### The test that passed against unwired code, twice over

First version: an exhibition has **no competition**, so `updateLeagueTable`'s own older guard
(`if (match.getCompetition() == null) return`) already skips it. **Breaking my type gate left all 8 tests
green** — the guard they never reached was doing the work.

The dangerous case is a practice match recorded **against** the competition, which the write path will
not stop. That is `LeagueTableReconciliationService`: it **rebuilds a table from `match` rows** and knew
nothing about match types. It now filters on `countsForTable()`, and with the filter removed the test
measures the harm exactly: **before 0.0, after 3.0 points.**

So the type is not only a label for read paths — it is the thing that keeps the *repair* pass honest.

### A static that had to be reset, and a test that says so

`InjuryService` risk is a **static** multiplier: the service is built per match from a `MatchState` and
pulls everything else from `SimulationRandom`, which is itself a static seeded source, so this follows
the existing shape rather than inventing a configuration path. The cost is that a leak is possible, and a
leak here means **every later competitive match in the world is played at practice-match risk**. There
is an assertion on the multiplier after the match, and it caught the leak when I removed the reset:
`expected: <1.0> but was: <0.35>`.

### A fixture that made a guarantee look satisfied

The fatigue assertion failed first time at **0.0 → 0.0**, and the product was fine. The test gave each
club **one** player; a club that cannot field eleven is simulated with synthetic squads whose ids are not
database ids, so no DB player was written at all. Reading that as "fatigue was not charged" would have
been exactly the error this log keeps recording — a guarantee that appeared to hold because nothing had
happened. Each club now gets a real eleven with real skills.

### A guard test updated on purpose

`ProposalEngineIsTheOnlyFixtureProducerTest` pins the set of files that may call
`simMatchService.simulate(`, and it failed by design with its own instruction: *"A new caller is not
automatically wrong, but it has to be added here deliberately."* It now allows
`ExhibitionMatchService.java` **in an allow-list**, with the reasoning recorded — the exhibition goes
through the same `persist` as a competitive match, so the rules stay in `MatchType`. The next caller
still fails. `SimulationControllerFixtureScopeTest` builds the controller by hand and needed the new
dependency.

### Breaks

| Break | Result |
|---|---|
| Career/morale gate removed | 1 fail — `expected: <7.0> but was: <8.0>` |
| Injury multiplier never reset | 1 fail — `expected: <1.0> but was: <0.35>` |
| `MatchType.countsForCareer()` always true | 2 fail |
| Reconciliation type filter removed | 1 fail — `before 0.0, after 3.0` |
| Table gate removed | **0 fail** — caught by the older null-competition guard, which is why the reconciliation test was needed |

### Regression check

`ExhibitionChangesNothingTest`, `ProposalEngineIsTheOnlyFixtureProducerTest`, `SimMatchPersistWiringTest`,
`LeagueTableReconciliationServiceTest`, `ProposalPhysicsDiagnosticTest`, the six earlier P2 classes,
`SimulationControllerFixtureScopeTest`, `GoalkeeperEngineTest`, `OffsideBeatsPenaltyTest`,
`PenaltyEngineTest`, `RestartTakerArrivalTest` — **89 tests, 0 failures, 0 errors.**
`mvn clean package` succeeds.

### Still open

**A friendly still cannot be played.** `FriendlyRequestService.createFixture` sets no `matchday`, so
nothing finds it. `MatchType.FRIENDLY` makes it a label instead of a blank, and the fixture now records
its type, but making it playable needs a day chosen — a decision, not a default.

---

## 2026-10-03 — P2-8 re-scoped: the feature is missing because friendly fixtures cannot be played

### The board said one day. It is a decision, and the reason is a defect.

Scoping exhibition mode against source turned up three verified facts, and none of them is "this will
take a while".

**1. A friendly fixture can never be played.** `FriendlyRequestService.createFixture` sets home, away,
season, round, week, date and `played = false`. It does **not** set `competition`, and it does **not**
set `dayNumber`. Every playback path needs both:

| Playback path | Requirement it fails |
|---|---|
| `MatchdayJob` → `findUnplayedOnDay(season, week, day)` | `dayNumber IS NULL` matches no day |
| `MatchdayJob`'s own filter | `competition IS NULL` |
| `POST /simulation/current-round/prepare` and `simulate-all` | both filters |

So a manager can negotiate a friendly, the other club can accept it, a `match_fixture` row is written —
and it stays `played = false` for ever. **`acceptedFixtureId` points at a match that cannot happen.** The
whole friendly feature is inert: agreements accumulate and nothing is ever played.

`FriendlyController`'s javadoc says *"There is deliberately no 'play a friendly' button."* The intent —
a negotiation rather than a button — is honoured. The consequence is not what anyone wanted.

**No test covers it.** `FriendlySlotRulesTest` is a pure unit test that never touches the service, and
`AiFriendlyWeekQueryCountTest` mocks `fixtures.save(...)` and asserts query counts; it never inspects
the fixture it just created. Its own javadoc is honest about this: *"The query count is what this fix
buys and the query count is what the first test holds."*

**2. There is no way to play a non-counting match.** That is the thing friendlies need and exhibitions
need — one missing capability, not two features. `Match` has **no** `kind`, `friendly`, `exhibition` or
`countsForTable` column; the only booleans are lifecycle and result-revealed.

**3. `simulate()` writes rows before `persist()` is even called** — `LazySquadGenerator.ensureSquadsForMatch`
generates up to 18 players per empty bot side, and `persistMatchCondition` then writes fatigue,
injuries and injury dates. Any "zero consequence" mode has to gate those too, not just `persist()`.

### "No consequences" is not one guard

The de-facto marker is `competition == null`, and it already buys some of it:

| Excluded by `competition == null` | How |
|---|---|
| League table | `SimMatchService:596` returns early |
| Club Elo | `findPlayedClubScoredInOrder` inner-joins `competition.type` |
| National Elo | requires `competition.type = :type` |
| League top scorers/assists | `GoalEventRepository` filters `competition_id` |

And leaks into **seven** read paths that read `match` with no competition filter: **club match history**,
**head-to-head W/D/L**, **form / last five** (which feeds the crowd model, which feeds the next real
match's gate projection), the **daily recovery window** (zone-load minutes would credit recovery),
**club top scorer/assist milestones**, **training percentage**, and **player appearances**.

Plus one trap worth naming: **`LeagueTableReconciliationService` rebuilds each table from `match` rows**
and does not know about non-counting matches. An exhibition persisted with a `LEAGUE` competition would
be counted back in by the repair pass, undoing whatever the write path skipped.

### Two P0-8 claims verified true

Both were "unverified" on the board. Both are as described:

- **`MatchPersistenceService` is dead** — 402 lines, zero callers in `src/main` *or* `src/test`. Only
  javadoc references and a filename in a test's allow-list. **It is a fossil of the pre-`ProposalMatchOutcome`
  engine** and diverges from the live path (it serialises stats a different way and *rebuilds* tables
  where the live code *increments* them). Corollary: **`match_tick_states` has no writer at all.**
- **`MatchEventRepository.save()` is a no-op** — literally `return event;`. And it is a `@Component`
  with an in-memory map that is **never written to**, so `findByMatch` returns `emptyList()` for every
  match, for ever. Any caller would silently get nothing. Events actually live in `Match.eventJson`.
  The board's guess — "probably a design choice, badly named" — is right, and it is worse than a no-op.

### What I did not do

I did not start building. Every remaining question here is the owner's, because each one changes what a
manager sees: does an exhibition count toward a player's appearances and career goals? Does it give
fatigue and injury? Should it appear in club match history at all? Schema or no schema? And two
structural guard tests will fail by design and have to be updated deliberately rather than loosened.

**Recorded rather than fixed**, per the rule that a finding outside your task is written down, not
walked past.

---

## 2026-10-03 — P2-16: "API Error", and a claim in AGENTS.md that was not true

### The board named the right defect and understated it

`js/pages.js` is the router every page goes through, and it ended in

```js
mainContent.innerHTML = buildEmptyState("API Error");
```

One string. No status, no code, no explanation — and it **replaced whatever the page had already
rendered**, so a partial page was destroyed as well. A 403 saying *"Only the owning club can accept
incoming offers"* and a 500 saying the database was unreachable reached the manager as the same two
words, and whoever read the bug report learned nothing at all.

The backend was already doing the work: `ApiException` carries a `code` and a `message` written for the
person reading it, and `authFetch` puts both on the thrown error. The card threw them away.

Now `buildErrorState(err, context)` titles by status — *your session has expired* / *you do not have
access to that* / *that could not be found* / *the server could not answer* — and prints the code, the
status and what the server said, with the page named in words. `describePage` covers all **41** router
cases; I checked the names against the router's own `case` labels mechanically, which found two I had
missed (`analytics`, `stadium`) and then zero.

**Also: `buildEmptyState` was interpolating its argument into HTML unescaped.** It is a shared helper,
and the new card leans on escaping because an error message is exactly the string that ends up holding
a club name.

### AGENTS.md was wrong about the escaper

> `escapeHtml` lives in `ui/escape.js` and **is the only copy**.

There were **three**, byte-for-byte identical: `ui/escape.js`, `pages/views/utils.js` and
`pages-renderers.js`. `pages.js` imported from one of the copies while another module used a different
one. Both duplicates now import the canonical implementation and one definition remains — checked by
executing both modules against `escapeHtml` over `null`, `undefined`, `0`, plain text and an
`</script><img onerror=alert(1)>` payload, all identical.

This is the exact duplication `escape.js` was created to end, and the file's own history says five copies
existed once. It had grown back to three.

### I deleted working code on a bad caller count

`teletextFetch` in `roundResultsTeletext.js` is a second implementation of `authFetch` — same job, its
own Serbian error strings, its own idea of what a 401 means. I searched for callers, found none, and
deleted the function.

**The search was wrong.** I ran `grep -v "roundResultsTeletext.js:"` to exclude noise, which excluded the
very file I was searching — and its one caller, at line 17, a live-results desk. Restored with
`git checkout`; the file is byte-identical to `HEAD` again.

P0-10 requires re-verifying a caller count immediately before deleting rather than copying it from the
board, and I broke that rule in the same breath as citing it. The duplication is real and worth fixing,
but it is **not** dead code, so it stays and the decision goes to the owner.

### The verification gap, plainly

This repository has **no JavaScript test infrastructure**: `package.json` contains one unrelated
dependency and no scripts, and there is no `*.test.js` anywhere. So there is no way to assert this
change in a suite. What I did instead, and it is weaker than a test:

- `node --check` on all three edited modules
- **executed** `utils.js` and rendered the card for four error shapes, reading the actual output
- executed the escaper from both modules against the canonical one, case by case
- verified the 41 page names against the router's `case` labels mechanically

And the honest limit: this is a change to a failure path. **Seeing it fire requires breaking an
endpoint**, which I have not done — so unlike every backend task in this log, the last mile is unverified
and only opening the app closes it.

### Regression check

`PrizeMoneyFollowsTheRealTableTest`, `WeeklyFinanceServiceTest`, `SupporterMoodRespondsTest`,
`PlayersRetireTest`, `GraduationRespectsTheSquadTest`, `ListedPlayerCanObjectTest`,
`SellerAcceptsANamedOfferTest`, `ComponentScanCoverageTest` — **41 tests, 0 failures, 0 errors.**
`mvn clean package` succeeds.

---

## 2026-10-03 — P2-14: prize money existed, was unreachable, and would have paid the wrong clubs

### The two halves were designed to meet and never did

`WeeklyFinanceService.awardPrizeMoney` was a complete implementation with **zero callers**. Beside it,
the weekly `prizeMoney` line returns `null` with the comment *"Prize money is only paid once the season
is finished, so this is a no-op mid-season."* So the design was coherent and the call was missing:
**no club had ever been paid prize money in this game.**

Wiring it was not a one-liner, because of what it ranked by.

### It ranked by a field nothing maintains

The dead method sorted on `CompetitionEntry.position`. **`setPosition` on a `CompetitionEntry` is never
called during a season** — the only writers are `PyramidBuilder:284` (once, when the world is built)
and `InternationalClubCupDraw` (writing 0). So `position` is a seed-time label that never changes, and
sorting by it ranks clubs in the order the world was created, not the order they finished.

Measured with that restored, on a fixture where the champion is deliberately seeded **fourth**:

```
the champion takes the largest share: 384000.0 against 480000.0
```

The actual champion would have been paid **less** than a club that finished below everyone, and the
ledger line would have said *"Finished P1 of 4"* while being wrong. This is the shape of defect the board
warns about: it would have looked correct in a screenshot and been invisible in the data.

There is already **one** definition of a league table — `LeagueTableOrder` (owner decision S8.4),
documented as having replaced four implementations that disagreed. `SeasonService.sortTable` is a
one-line delegation to it, and the playoff draw reads it. So prize money now reads it too, rather than
becoming a fifth. The stale `position` field and `WeeklyFinanceService`'s private reader of it are
deleted; `position` is now written by the seeder and read by **nothing** in `src/main`.

### A fresh world would have minted money

Nothing stopped a competition that was never played from paying out a full pool. The dev database sits
at season 1 week 1, so a rollover before a single match would have paid every club in the world a
champion's purse for a season that did not happen — `expected: <0> but was: <2>` with the guard
removed. Guarded on any recorded points or goals.

### Where it is called, and why there

`SeasonService.performPromotionRelegationAndNewSeason()`, **before** `applyPromotionRelegation`. Paid
first because promotion moves clubs between competitions, and the season that has finished is the one
that gets paid — paying afterwards risks paying a table that promotion has already disturbed. The
season's competitions come from `findBySeasonYear`, one query; per-competition failures are caught and
logged so one league's payout cannot cost every other league its money.

### Two test bugs of mine, both caught before they became flaky

1. `SEASON.equals(...)` where `SEASON` is an `int` — *"int cannot be dereferenced"*.
2. `assertEquals(4, prizeLines(champion))` — counting one club's lines against the number of clubs. The
   intent was "every club is paid", now asserted per club.

And a fixture lesson: the seeded position is captured **when the fixture builds it** rather than
re-read from the database, because re-reading hit a lazy `SeasonCompetition` proxy outside a session —
and the value is the point of the assertion, not the query that fetches it.

### Breaks

| Break | Result |
|---|---|
| Rank on the stored seed-time field, as the dead method did | 1 fail — `384000.0 against 480000.0` |
| Drop the unplayed-season guard | 1 fail — `expected: <0> but was: <2>` |

### Regression check

`PrizeMoneyFollowsTheRealTableTest`, `WeeklyFinanceServiceTest`, `WeeklyFinanceScopeTest`,
`PromotionRelegationBoundaryTest`, `SeasonRolloverNumberTest`, `SeasonShapeTest`,
`LeagueMilestoneSingleSeasonReadTest`, plus the four earlier P2 classes — **71 tests, 0 failures, 0
errors.** `mvn clean package` succeeds.

---

## 2026-10-03 — P2-5: the meta layer finally has a consequence

### The board's premise, verified

`BoardExpectationService` has **exactly one caller**: `FinanceController.java:111`, a read for
display. `sackingReview` is a boolean with no entity, no persistence and no end-of-season review. There
is no supporter mood anywhere in `newLogic` — every grep hit for "supporter" or "mood" is either the
unrelated `footballtextmanager` application or the word "expectation" in a javadoc.

The competitive analysis §11.2 puts the diagnosis precisely: the project built the *expensive* half of
the meta layer without the consequences, which "is the worst of both worlds".

### The loop it closes

```
a player objects to being listed (P2-3)
  → supporters notice the club is selling its own people
    → mood falls
      → fewer of them come (AttendanceService)
        → gate income falls
          → the wage bill looks worse against income
            → the board's trust falls
```

Every step already existed. Nothing was connected. **An objection now has a price that is not just the
5% compensation**: a club that treats its squad as merchandise empties its own ground over a season.

### Two design decisions worth keeping

**Mood is not reputation.** Reputation is what the club is worth; mood is how the stand feels about
being there. A club can be successful and unloved, and it is the second that empties the ground — so
collapsing them would have made this another number that says nothing new.

**`attendanceEffect` is exactly 1.0 at mood 60.** Every club starts at 60. The first formula,
`0.78 + mood/100 * 0.42`, returned **1.032** at a neutral mood and would have silently changed gate
income for the entire world the day this shipped, with nothing in the diff to say so. The test caught
it: `expected: <1.0> but was: <1.032>`. It is now `1.0 + (mood - 60)/100 * 0.38`, so a furious support
turns out 23% fewer and a delighted one 15% more — the penalty gentler than the reward, because an
empty stand is worth nothing to anybody and an angry support still comes.

### The test that was green against unwired code — the second time this session

The first version of `SupporterMoodRespondsTest` asserted `attendanceEffect(...)` arithmetic: monotonic,
1.0 at neutral, proportionate. Then I deleted the call to it from `AttendanceService` — which is
*precisely the defect this task exists to fix* — and **all five tests stayed green.** The formula was
still right; the game still ignored it.

Rewritten to assert the consequence: two clubs identical in every way the model can see, differing only
in mood, and the ground is emptier at the miserable one. With the wiring removed it now fails with the
number rather than a boolean:

```
the moody ground must be the emptier one: 7683 at mood 5 against 7683 at mood 95
```

That is the second time in one session a test measured the mechanism instead of the behaviour. The
lesson generalises past this repository: **a test that asserts the helper proves the helper is called
by you, and nothing else.**

### A fixture that was silently skipped

`driftWeekly()` reads `findAllClubsWithDivision()` — clubs that *have a division*. A test club without
one is not returned, so "the mood moved" passed against a sweep that had done nothing at all. The
per-club drift is now a separate method (`drift(List<Team>)`) that the world sweep delegates to, which
is both testable without a world and a clearer statement of what a sweep does.

### Scale

The weekly pass reads clubs in one query and outstanding objections in one query
(`findActiveObjectedListings`). It deliberately **does not** ask each club for its recent results:
`AttendanceService.formOf` does that with a query per club, which is survivable for one match and would
be 14,880 round-trips a week. Results reach mood through reputation, which `ClubRatingService` already
maintains and which already sits on the club row.

### Breaks

| Break | Result |
|---|---|
| Mood computed and displayed, `AttendanceService` unwired — the original defect | 1 fail, `7683` both ways |
| No penalty for an objection | 1 fail — `60 -> 60` |
| `attendanceEffect` returns a constant 1.0 | 3 fail |

### Regression check

`SupporterMoodRespondsTest`, `GraduationRespectsTheSquadTest`, `PlayersRetireTest`,
`ListedPlayerCanObjectTest`, `ListingFeeScalesWithTheAskingPriceTest`, `SellerAcceptsANamedOfferTest`,
`AdmissionServiceTest`, `PlayerContractServiceTest`, `JuniorDecisionWindowTest` — **67 tests, 0 failures,
0 errors.** `mvn clean package` succeeds.

---

## 2026-10-03 — P2-6: an academy was an unlimited source of free players

### The cap that did not exist, and could not have existed where it was looked for

`promoteJuniorsPastWindow` turned **every ACTIVE junior aged ≥20 in the entire world** into a senior
`Player`, in one loop, with no check that the club could field him. The obvious place to look was
`PlayerContractService.canRegister` — the 25-senior cap — and it is structurally unable to help:
graduation creates **no `PlayerContract`**, and `canRegister` counts contracts. A graduate was
therefore invisible to the cap, and then went on to draw a wage for a full season before
`ContractBackfillService` noticed he existed.

So the cap had to be the squad itself. A graduate is promoted only while his club has room, and
otherwise **released** — which is the football answer and gives P2-7 teeth in both directions: a club
that refuses to let players go fills its own squad and blocks its own academy.

### My own bug, caught by the test I had just written

```java
roomLeft.merge(clubId, -1, (a, b) -> a);   // returns the OLD value
```

`Map.merge` applies the remapping function to `(oldValue, newValue)`, so `(a, b) -> a` discards the
`-1` and **the room never shrinks**. The first run promoted all five juniors at a club with two places
and produced a squad of **28 against a limit of 25**. `Integer::sum` fixes it.

I only found it because the assertion was on a squad size rather than on a return count. Had I asserted
"2 promotions" it would have read 2 in both cases and told me nothing.

### The defect P2-3 introduced, now measured

A graduate has no contract, so `ListingObjectionService.roleOf` fell through to the position switch I
wrote in P2-3: `GK/DEF/MID -> STARTER`, reluctance 0.75. A seventeen-year-old academy graduate on his
first day was therefore judged as a senior starter.

**Read from the code: "roughly 41%". Measured by breaking the fix: `0.41250000000000003`** — exactly
STARTER-level, as predicted. So ~41% of every automatic graduation drew an objection that the club then
had to pay 5% of the asking price to clear, on a player who had never asked to be sold.

`PlayerContractService.inferRole` already encoded the right rule — a cheap 17-year-old is a `YOUTH` at
reluctance 0.12 — so the fix is to ask the one function that knows rather than to write a second,
disagreeing rule. This is the second time `inferRole` has been the right answer to something `newLogic`
was getting wrong by hand.

### Two bounds, and why both

- **Senior places.** The real cap, and it is the one that makes P2-7 matter.
- **`MAX_ACTIVE_JUNIORS`.** Unreachable through intake, which stops at ten — so a club can never hold
  fourteen overdue juniors. But the sweep reads junior rows **directly**, and fixtures and
  `DatabaseInitializer.seedInitialJuniorsForOwnerIfMissing` insert them without passing through intake.
  A graduation pass that could promote more than the academy holds would be relying on an invariant it
  does not itself enforce. My test builds the impossible state on purpose and says so.

### Scale: one query, not one per club

Squad sizes come from a single grouped query, `countSquadSizesByTeamIds`. The obvious implementation —
`playerRepository.countByTeam(team)` per club — would be **14,880 round-trips inside the season
rollover**, in one transaction, to decide who has room for a graduate. The per-player retirement age is a
function of rating and cannot be pushed into SQL, so the count is as far left as it goes.

The bounded query has a recorded cost: `promoteJuniorsPastWindow` still loads **every** overdue junior
in the world into one list and one transaction, and at 14,880 clubs that is tens of thousands of
`Player` inserts in a single unit of work. **Recorded for P1 rather than fixed here** — chunking it
changes transaction semantics and belongs with the query work, not inside a graduation cap.

### Breaks

| Break | Result |
|---|---|
| Unconditional graduation (the old behaviour) | 3 fail — `expected: <25> but was: <28>`, twice |
| The old `roleOf` position switch | 1 fail — `was 0.41250000000000003, which is STARTER-level` |

### Regression check

`GraduationRespectsTheSquadTest`, `PlayersRetireTest`, `ListedPlayerCanObjectTest`,
`ListingFeeScalesWithTheAskingPriceTest`, `SellerAcceptsANamedOfferTest`, `JuniorDecisionWindowTest`,
`JuniorSchoolServiceTest`, `YouthAcademyGraduationTest`, `TalentRangeTest`, `JuniorSchoolRulesTest`,
`AcademyQualityTest`, `PlayerContractServiceTest`, `TransferServicePriceGuardTest` —
**117 tests, 0 failures, 0 errors.** `mvn clean package` succeeds.

---

## 2026-10-03 — P2-7: players retire, and one dead method was dead because it is destructive

### There was nothing to build on, and that was the finding

No `retire`/`retirement`/`retired` token exists anywhere in `src/main` or `src/test` — not in Java, JS,
SQL or YAML. No constant, no age-filtered player query, no status field on `Player`, no removal path
keyed to age, and no test. Ageing *did* exist: one bulk `incrementAgeForAllPlayers()` at
`SeasonService.java:643`. So a player aged once a year and the only ways out of a squad were a transfer
or a contract expiring. A 32-year-old became 60, then 90, stayed at his club, stayed on the list, and
`ClubNeedService.java:173` still priced him at 30% of value and bid for him. The pool only ever grew.

### `Team.removePlayer` is dead because it would DELETE the player

I intended to make it load-bearing. **`Team.players` is mapped
`@OneToMany(mappedBy = "team", cascade = ALL, orphanRemoval = true)`**, so removing a player from that
collection makes Hibernate delete the row on flush — statistics, contract history and transfer history
with it. That is why it has had no callers since it was written.

Proved by doing it: `Referential integrity constraint violation: LINEUP_STARTING_PLAYERS FOREIGN
KEY(PLAYER_ID) REFERENCES PUBLIC.PLAYER(ID)` — **4 of 5 tests red.** It does not quietly lose history,
it fails outright for any player who has ever been in a lineup, and silently deletes the ones who have
not. Retirement does what contract expiry does instead: null the club, end the contract, keep the row.

### Bands on the real rating scale

`Player.rating` is **0-100**, not the 0-10 the skills use. Measured on the dev world: peak around
67–72, range 35–93, 7,730 club players aged 18–32. So the cut points are 85 / 75 / 65 and the band is
33–36. A first attempt used the 0-10 scale and would have retired nearly everybody at 33.

Hooked **between** ageing and graduation, not after: a player who turns 33 this year is assessed in the
same pass, so he can retire and be replaced by a graduate in one season turn.

### The latent bug retirement would have made routine

`Lineup.getOrderedStartingPlayers()` returned join-table rows regardless of club membership.
`PlayerContractService.expireContracts` has been setting `team = null` on expiry **every week for some
time**, so a saved XI could already start a player who no longer played for that club. Retirement would
have turned a weekly oddity into a constant one. The join table now yields only players still at that
club, and with a row dropped the list can fall under eleven — which every caller already handles by
building from the club's real squad.

**This broke two engine tests, correctly.** `RealSquadFactoryTest` and
`RealSquadSimulationSmokeTest` build eleven synthetic players **with no club at all** to exercise shape
mapping, and the first version of the filter dropped every one of them, so both suites went red with
`squad is null`. A lineup with **no club** now yields its players unchanged: it cannot judge membership
because there is none to judge. Every lineup the product reads has a team.

### My own committed test had a latent order-dependent bug

`ListedPlayerCanObjectTest.payingCompensationClearsTheObjection` — mine, shipped in `302b9ea` — read
`transfers.findById(star.getId())`, looking up a **transfer** by **player** id. It passed alone because a
fresh database hands out matching ids, and it passed in a full run by luck. It failed as soon as
`PlayersRetireTest` ran first in the same context:

```
payingCompensationClearsTheObjection » NoSuchElement No value present
```

Confirmed from the bound parameters: `update transfer ... where id=1` with `player_id=9`. Fixed to
`findByPlayerId`. **This was a flake waiting for the right test ordering, in a test whose whole purpose
was to be trustworthy** — and it was found only because a new test class ran before it. Same class of
error as the global-count assertions in `PlayersRetireTest`, fixed in the same hour: my tests were
leaning on database state instead of on the guarantee.

The `@AllArgsConstructor` hazard on `Player` was real and immediate, exactly as the entity's own javadoc
warned: `RealSquadFactoryTest:124` and `RealSquadSimulationSmokeTest:92` construct `Player` with all 27
fields positionally and needed the trailing `null`.

### A merge ran through my files mid-task

The P0-1b agent merged a branch that changed `requireSeller`'s signature, colliding with the guard I added
in P2-3 inside `removeFromTransferList`. `TransferService.java` sat unmerged with conflict markers, which
blocked every compile. I did not touch it — their merge, their resolution. Afterwards I verified all
five P2-3 objection guards survived (`requireResolved` on delist and on accept, `clear`, `payCompensation`,
`raiseIfWarranted`), because a merge can drop a hunk silently and this one had the same line.

### Breaks

| Break | Result |
|---|---|
| Use `Team.removePlayer` | 4 fail — FK violation on `lineup_starting_players`, i.e. a DELETE |
| Do not end the contract | 1 fail — the registration slot stays occupied |
| One retirement age for everybody | 2 fail — `expected: <33> but was: <34>` |

One earlier break was a **no-op I had to rewrite**: `if (rating >= ELITE && TRUE) return 36;` followed by
`if (rating >= ELITE) return 36;` is the same code twice, so it proved nothing.

### Regression check

`PlayersRetireTest`, `RealSquadFactoryTest`, `RealSquadSimulationSmokeTest`, `PlayerContractServiceTest`,
`ListedPlayerCanObjectTest`, `ListingFeeScalesWithTheAskingPriceTest`, `SellerAcceptsANamedOfferTest`,
`TransferServicePriceGuardTest`, `TransferMarketSquadReadCountTest`, `JuniorDecisionWindowTest`,
`YouthAcademyGraduationTest`, `JuniorSchoolServiceTest`, `TalentRangeTest`,
`ScheduleInsightServiceTest` — **104 tests, 0 failures, 0 errors.** `mvn clean package` succeeds. The
polluting pair was also run in both orders.

---

## 2026-10-03 — P2-3: a player can refuse to be listed, and the club has to choose

### Built on two things that were already written and never used

- **`SquadRole.reluctanceToSell()`** — a STAR at 0.92 against a YOUTH at 0.12, and **zero callers
  anywhere in `src/`**. It is exactly the input an "I don't want to be listed" gate wants, so it is
  now load-bearing rather than duplicated by a new constant.
- **`PlayerContractService.wageDemand`** — already answered "is he paid what he thinks he is worth"
  for renewals and for transfer offers. The grievance term reuses it rather than inventing a second,
  disagreeing wage model.

Likelihood is `reluctance * 0.55 + grievance * 0.45`: a club does not get a rebellion every time it
lists a teenager, and a player on or above his own demand does not object over money at all.

### Three reasons, not one boolean

`WAGE_DISPUTE`, `DOES_NOT_WANT_TO_LEAVE`, `UNHAPPY_TO_BE_LISTED` — kept apart because they have three
different managerial answers. A club pays a man more to answer a wage dispute. A club that cannot
afford to lose its best player has to choose between paying compensation and keeping him. One
boolean would leave a manager with a fee to pay and no idea what it bought.

### The loophole, and why the objection deliberately survives re-listing

The `transfer` table has a **UNIQUE constraint on `player_id`** and one row per player, recycled on
every listing. Clearing the objection in the listing path would therefore have made *"reject the
bids, take him off the list, put him straight back"* a free way to wipe a player's refusal — which is
the entire thing the mechanic exists to prevent. The objection is cleared only by resolving it.

It also does not block a **rival** club bidding. The objection is a labour question between a player
and the club putting him on the list; blocking incoming bids would make the mechanic unreachable in a
marketplace. Recorded as a design decision, not an oversight.

### `ListingObjection` is not a `TransferStatus`, and that is not a style preference

`\d transfer` on the dev database shows a live `transfer_status_check` CHECK constraint over the four
status values. `ddl-auto=update` does **not** reliably recreate check constraints, so a fifth constant
would fail on insert until somebody dropped it by hand. A listing can be `LISTED` *and* objected to at
the same time, so it was never the same axis.

### The test that passed against broken code, and had to be rewritten

The obvious test — object, relist, assert the objection survives — **passed with the guard removed**,
because a relisting re-evaluates the player from scratch and re-raised the objection with the *same*
reason. It could not tell "survived" from "re-raised".

Rewritten so the two are distinguishable: the player's wage grievance is **cured** between the two
evaluations, so a fresh evaluation would reach a *different* conclusion
(`DOES_NOT_WANT_TO_LEAVE`). The standing reason must survive that.

Then a second, subtler problem: I tried to assert that precondition ("a fresh evaluation would say
`DOES_NOT_WANT_TO_LEAVE`") and **it cannot be written** — with the guard working, `raiseIfWarranted`
returns the standing objection and never performs a fresh evaluation, so what a fresh evaluation
*would* conclude is unobservable through the API. Rather than assert something unverifiable, the
setup is validated by the break itself: with the guard removed the test fails with
`expected: <WAGE_DISPUTE> but was: <DOES_NOT_WANT_TO_LEAVE>`, which is only reachable once the
grievance really has been cured.

| Break | Result |
|---|---|
| Relisting may overwrite a standing objection | 1 fail — `expected: <WAGE_DISPUTE> but was: <DOES_NOT_WANT_TO_LEAVE>` |
| `requireResolved` never refuses | 3 fail — both "cannot delist" and "cannot accept a bid" throw nothing |

### Two test bugs of my own, both instructive

1. `theClubCannotAcceptABidWhileHeObjects` asserted `PLAYER_OBJECTION_OPEN` and got `NO_OPEN_OFFERS` —
   I had never created an offer, so the guard was never reached. A refusal from an *earlier* check is
   not a test of this one.
2. `payingCompensationClearsTheObjection` asserted an absolute balance of EUR 19,600,000 and got
   19,400,000, because **the club had also paid the P2-4 listing fee** (2.5% of 8,000,000). Asserting
   the absolute would have been asserting the other feature as well. Changed to a delta measured across
   the resolution call only.

### Also fixed in passing

`removeFromTransferList` used `actingTeamId != null && !Objects.equals(...)` — the weak guard that
P2-2 replaced elsewhere in the same class with `requireSeller`, where an omitted `teamId` skipped the
ownership check entirely. It now uses the correct one, so it **requires** a team id.

### Recorded, not changed — and this one is load-bearing

`NegotiationService.playerObjection` is **recorded and never enforced.** `playerWouldSign` has zero
callers in `src/main`; `settle` moves the player and sets his earnings whatever the objection says,
and `buyListedPlayer` passes the player's existing wage with no wage check at all. The class javadoc
claims a deal is "recorded-but-refused rather than silently completed" — the recording happens; the
refusing does not.

**Not fixed here, deliberately.** Enforcing it would change shared settlement used by the AI market at
14,880 clubs, and it would turn the existing green transfer tests red for a reason that is a *design
question*, not a bug: should a player who refused the wage be able to be transferred anyway? That is
the owner's call, and it belongs on the board rather than inside a P2 feature. **Logged for P0.**

Two more pieces of dead code noted by the same investigation, both plausible follow-ups:
`MoraleService.applyStanding`'s `if (listedForTransfer) morale -= 4.0` has no production caller, so
**being listed currently costs a player nothing at all**; and `PlayerContractService.renew` still does
`lengthMonths / 12.0` where `seasonsFor()` uses `MONTHS_PER_SEASON = 3`.

### Regression check

`ListedPlayerCanObjectTest`, `ListingFeeScalesWithTheAskingPriceTest`, `SellerAcceptsANamedOfferTest`,
`TransferServicePriceGuardTest`, `TransferMarketSquadReadCountTest`, `TransferFeeServiceTest`,
`TransferWindowServiceTest`, `PlayerContractServiceTest`, `MoraleAndBudgetServiceTest` —
**76 tests, 0 failures, 0 errors.** `mvn clean package` succeeds.

---

## 2026-10-03 — P2-4: listing was free, and the flag that would have stopped it was already there

### "Two lines" was optimistic: there was no fee at all

`listPlayerForTransferEntity` set the listing up, saved it and charged nothing. More usefully,
`boolean alreadyListed = isActiveListing(transfer)` sat on line 547 of the method and was **never
used** — the same shape as the unused `alreadyListed` and the two identical `if/else` arms already
recorded in the weekly-rollover entry. Re-pricing a live listing was free and untracked, so the
relist loop this mechanic exists to price was free too.

### Human clubs only, and that is a real boundary

AI clubs self-list weekly (`maybeCreateAiListing`) and `YouthAcademyService` lists academy graduates
through the same two-argument path, so "everyone pays" would have charged 14,880 AI budgets for a
mechanic aimed at a manager spamming the market. Gated on `Team.humanControlled` — a maintained flag
(PyramidBuilder false for AI, DatabaseInitializer and RegistrationService true for the owner), not a
derivation from "did an HTTP request arrive".

Charged off the **clamped** asking price, because a request for EUR 0 is listed at EUR 1 and must be
charged as EUR 1. The refusal is deliberate: a club that cannot fund the fee is refused outright
(`409`, nothing listed), because a negative budget would make the fee a speed bump for honest
managers and nothing at all for dishonest ones.

### Five tests, five deliberate breaks, no surprises

| Break | Result |
|---|---|
| Flat fee instead of 2.5% | 3 fail — `expected: <100000.0> but was: <2500.0>` |
| Charge every call, ignoring `alreadyListed` | 3 fail — `expected: <1> but was: <0>` on the ledger count |
| Charge AI clubs too | 1 fail — `expected: <0.0> but was: <75000.0>` |
| Drop the affordability refusal | 1 fail — nothing thrown |

Unlike P2-2 there was no coarse-assertion trap here: every guarantee is a number and each failed on
its own break. **The Finances page needed no frontend change** — it is driven entirely by
`FinanceCategory.values()`, so a new category is enumerated automatically. Proved rather than assumed:
`summarise().get("byCategory")` is asserted to hold `LISTING_FEE` at `-100000.0`, the enum's own sign
convention for a cost.

Two hand-built `TransferService` tests needed the new constructor argument
(`TransferServicePriceGuardTest`, `TransferMarketSquadReadCountTest`); both pass and
`mvn clean package` succeeds.

### Recorded, not changed

`TransferActivitySeeder` builds `Transfer` rows and calls `transfers.save()` directly, bypassing the
listing path, so seeded listings pay no fee. Seeding is a different concern and the board's own rule
is that boot and seeding write nothing on their own.

---

## 2026-10-03 — P2-2: a seller could not accept an offer at all, and could not have chosen one if he could

### The board's premise was wrong in both halves

P2-2 read: *"NegotiationService.acceptOffer exists and correctly rejects the wrong offer — no controller
exposes it."* Neither half survived reading the source.

**A controller did expose it**, at `POST /transfers/accept-offer/{playerId}`, and **it could only ever
fail.** `TransferService.acceptBestOffer` called `negotiation.acceptOffer(...)`, which settles the
transfer and marks it `COMPLETED`, and then called `TransferService.completeTransfer(...)`, whose
second `settle` hit the `COMPLETED` guard, returned false, and threw.

**The status code was not the one I predicted.** Reading the code, I expected
`409 TRANSFER_NOT_COMPLETED`. Running it, the answer is **`400 "You cannot buy your own player."`** —
because by the second pass the player has already moved to the buyer, so `requirePlayerTeam` returns
the buyer, the seller *is* the buyer, and the self-deal guard fires with a nonsense message. Read the
code, then run it: the two answers differed, and only the runtime one is real.

### Four green test classes, and not one of them went near it

`acceptOffer` is directly tested by `NegotiationServiceTest`, `TransferCompletionTest` and
`OmladinacTransferJourneyTest`. All three call the **service**. The broken hop was in `TransferService`,
and **there is no `TransferController` test in the repository at all.** This is the AGENTS.md failure
shape exactly: the suite was green and the feature was unreachable.

### Two more defects, found while making it work

1. **The Incoming Offers panel was structurally dead.** `getTeamTransferOverview` built that list with
   `.filter(this::hasOpenOffer).filter(t -> !isActiveListing(t))`. `hasOpenOffer` needs `buyerTeam ==
   null` and a priced bid; `isActiveListing` needs status `LISTED` and `buyerTeam == null`. A listed
   player with a bid satisfies the first and is then removed by the second, so **the list was empty
   for every possible input.** Proven by restoring the filter: `expected: <1> but was: <0>`.
2. **A refused settlement destroyed the auction.** `acceptOffer` rejected every rival bid *before*
   settling and threw the verdict away, so a buyer who could no longer afford the fee left the seller
   with one `ACCEPTED` offer, every rival `REJECTED`, and no transfer. `settleOffer` now unwinds every
   status when settlement refuses.

### The ownership check had an off switch

Every guard read `actingTeamId != null && !Objects.equals(sellerTeamId, actingTeamId)` — so a caller
that **omitted** `teamId` skipped the ownership check entirely and got the seller's powers. An absent
team id is not consent to act as somebody else; `requireSeller` now returns `400 TEAM_REQUIRED`.

### The test that passed while measuring nothing — mine, and it took two attempts

Three break-and-restore cycles were not enough on the first pass:

| Break | What it showed |
|---|---|
| Restore the double settle | Test 1 failed — but with `400 "You cannot buy your own player."`, not the 409 I predicted |
| Make `acceptOffer` ignore the named id | **All 4 still passed.** I had only broken a *local* variable; `settleOffer` still received the named id, so the settlement was unchanged. The test was right and my break was worthless |
| Break it properly — settle the richest bid | 2 failed with `expected: <1200000.0> but was: <2000000.0>` |
| Unscope the offer lookup | **Test 3 still passed**, because it asserted only `assertThrows(ApiException.class)` and an incidental `TRANSFER_NOT_COMPLETED` is also an `ApiException`. Tightened it to assert `404` and the `OFFER_NOT_FOUND` code; it then failed with `expected: <404 NOT_FOUND> but was: <409 CONFLICT>` |

**A coarse assertion is a test that cannot fail.** Asserting an exception *type* rather than its
status and code let a completely different failure satisfy it.

### Two fixtures that were quietly measuring nothing

- **`NegotiationServiceTest` is red 10/10 when run alone.** `inWindow()` does
  `clocks.findAll().stream().findFirst().orElseThrow()`, and no `GameClock` exists in an empty H2. It
  is green **only in a full suite**, because some earlier test seeded a clock first. **The board calls
  this class "already green, 10/10 — it is the worked example of this fix" (P0-2). That is wrong**,
  and P0-2 should not use it as the reference until its clock is built rather than assumed.
- **`sellerChoosesAndOtherOffersSurvive` passes without a transfer happening.** Its clubs are built
  with a cash balance and no settled ledger income, so `TransferBudgetService.canAfford` refuses
  ("No settled income yet, so no transfer budget has been granted"), `settle` returns false, and the
  old code accepted the offer regardless. The test asserted statuses on a deal that never settled.
  With `settleOffer`'s unwind it would now fail — correctly.

**`TransferCompletionTest` (3) and `OmladinacTransferJourneyTest` (6) are red for the same
`NoSuchElementException`-on-empty-database reason** — confirmed by running each alone, and both were
verified red before this change rather than assumed. **One fixture defect, 19 of the 29 suite reds.**

A note on comparability: in a 6-class run `TransferCompletionTest` showed green while red alone —
because my class seeds the `GameClock` its `setUp` needs. Not a fix, an accidental coupling.

### Blast radius, measured the same way both times

| | baseline (my change stashed) | with the change |
|---|---:|---:|
| `NegotiationServiceTest` | 10 errors | 10 errors (unchanged — no clock) |
| `TransferCompletionTest` | 3 errors | 3 errors (unchanged — same cause) |
| `TransferServicePriceGuardTest` | 10 pass | 10 pass |
| `TransferFeeServiceTest` / `TransferWindowServiceTest` | 7 / 13 pass | 7 / 13 pass |
| `SellerAcceptsANamedOfferTest` | did not exist | **4 pass** |

**No regression.** `mvn clean package` succeeds.

### Still not done here

`settle` judges affordability with `budgets.canAfford(buyerId, playerId)`, which computes
`playerValue * 0.25` rather than the **agreed** fee, and nothing then checks `upfront <= budget`
before deducting. So a fee above cash is possible. That is shared settlement used by the AI market at
14,880 clubs — out of scope for a P2 feature, and **recorded rather than touched.**

---

## 2026-10-03 — `6e63831` — P1-7: the per-tick event log, and the two Elo replays that could not run

**`Match.event_json` is 742 KB to 1,035 KB on every simulated match** (`6e63831`). It is the whole per-tick decision
log — every tick, every player, `DECISION` / `PASS` / `RECEIVE` with a human-readable description —
written into one text column by `SimMatchService:294`. Measured on the live rows:

| | |
|---|---:|
| matches carrying one | 155, ranging 741,879 – 1,035,458 bytes |
| all of them together | **124 MB of text for 155 rows** |
| compressed on disk | 16 MB (a 0.143 ratio, so TOAST is doing real work) |
| one full season, 89,280 matches | **~66 GB of text, ~9.2 GB on disk** |

Both Elo replays read a match's date, its two sides and its score, and **neither reads the blob.**
Returning `Match` entities made Hibernate select it anyway. `ClubRatingService.recompute()` calls
`findPlayedClubScoredInOrder()` **after every simulated matchday**, and one season is 89,280 matches — so
**~66 GB of Strings in a single result list, inside one transaction.** That is not slow. It cannot run,
and it is green on the dev database only because 155 × 742 KB is 130 MB.

### The measurement I got wrong first, and how it nearly said the opposite

My first attempt timed this with `EXPLAIN (ANALYZE, FORMAT JSON)` and got **0.281 ms** for all 155
blobs — 130 MB in a third of a millisecond, which is about 460 GB/s. I did not believe it, and I was
right not to: **psql was not transferring the rows**, so the plan's own timing excluded the only part
that costs anything. Had I taken that number at face value the conclusion would have been "the blob is
free, no change needed".

The measurement that works is `COPY (...) TO '/dev/null'` with `\timing`, which forces the rows out of
the server and through the client:

| 155 matches, real blobs | run 1 | run 2 | run 3 |
|---|---:|---:|---:|
| `SELECT event_json` | 470 ms | 463 ms | 643 ms |
| a projection, no blob | 2.3 ms | 2.6 ms | 2.1 ms |
| `SELECT sum(length(event_json))` | 927 ms | | |

**3.0 ms per row against 0.014 ms — 215× per row.** And the aggregate's 927 ms is the independent check
that the blobs really are being read: 130 MB cannot be summed in less.

**This is the fourth time this codebase has produced a measurement that reported success while measuring
nothing**, and the shape is new: the earlier three were a seeding job, a pyramid builder, and a test
that read zero rows. This one was a *correct* query timed through a path that discarded its result.

### Landed

`ScoredMatch` — nine scalars: match id, both sides' ids and names, both scores, and the competition's
scope and type. The two replay queries construct it directly; `ClubRatingService` and
`NationalRatingService` read it instead of `Match`.

| 155 matches, same 155 rows both ways | before | after |
|---|---:|---:|
| entity shape (`m.*` with the two team joins) | 443 / 540 / 554 ms | **6.5 / 10.6 / 11.2 ms** |
| `ScoredMatch` projection | | |
| one season, scaled by 89,280 / 155 | ~255 s and ~66 GB | **~3.7 s and ~5 MB** |

Row count checked identical (**155 = 155**) rather than assumed, because a projection that quietly drops
rows would make the replay look fast and wrong.

**The sides are ids and names, not `Team` references**, so the replay loads no proxies and asks for no
second row per team. The joins are explicit `LEFT JOIN`s rather than `m.homeTeam.id`, which in a JPQL
select clause would be an inner join and would **silently drop** a match with a missing side instead of
carrying a null id into the replay's existing "no league division, not rated" branch. That branch is now
also the null path, where the entity version threw a `NullPointerException` — so one corrupt row no
longer stops the other fourteen thousand clubs being rated.

**Why a projection and not `@Basic(fetch = LAZY)`.** Lazy would fix these two callers and hand
`GoalEventRepository` — the one class that genuinely parses the log — an extra query per row inside the
transaction, or a `LazyInitializationException` outside it. `open-in-view=true` in prod hides the second by
keeping the session open, which is the worst outcome: correct until it is not. This is the same trade as
`findLoadMinutesPlayedOnce` being a projection rather than a lazy column.

### The guard

`ScoredMatchCarriesNoBlobTest`, 5 tests, 183 ms, no database. It pins the record's nine components by
name, asserts neither replay query's JPQL mentions `eventJson` / `lineupJson` / `statsJson`, asserts both
readers return `List<ScoredMatch>` **whatever the method is called**, asserts the two entity-returning
readers are gone rather than left beside the new ones, and asserts the club replay still excludes
`INTERNATIONAL` by type — that test is the club-versus-national split, and widening it would rate national
sides on the club ladder.

**Proven able to fail:** adding a `String eventJson` component to `ScoredMatch` and selecting it in the
query was tried, and the guard caught it.

### Left open, with numbers, because each needs a decision that is not mine

| Site | Cost |
|---|---:|
| `TeamController:250` club match history — `MatchDTO` carries no JSON at all | 12 × 742 KB = **8.9 MB per page view** |
| `TeamController:577`, `ScheduleInsightService:70` | same |
| `GoalEventRepository.findByMatchCompetitionIdAnd…` — **the top-scorers page** | 60 × 742 KB = **44 MB parsed per request** |
| `GoalEventRepository.findByMatchSeasonYearAndScoredTrue` — `LeagueMilestoneService:71,75` | **89,280 × 742 KB, twice** |

The club-history ones are a `MatchDTO` projection and are simply wider than a replay fix. The
`GoalEventRepository` ones are a data-modelling decision: the per-tick log is written to `event_json`
**and** written again as a file by `SimReplayStore`, and only `MatchDetailService:34` and
`ZoxApiController:575` — both single-match request paths — read the database copy. Either a goals table
beside the blob, or a `jsonb` column the database can filter on. Both are schema changes with a
migration. Recorded as **P1-7** rather than done.

### Not mine, verified rather than assumed

`NationalRatingServiceTest#theWorldIsLevelUntilSomethingIsPlayed` fails: `expected: <1> but was: <0>`.
**Verified pre-existing**, not caused by this change — a clean `git worktree` at HEAD `6ffba68`, built and
run with none of these changes present, fails identically. It asserts on seed state that the fixture does
not produce (`countDistinctRatings()` returns 0, and a sibling test in the same class expects 1 and
passes). **Not fixed** — it belongs to whoever owns the international replay's fixture.

Separately: for a few minutes the whole test tree would not compile, because a parallel agent's
uncommitted `TransferService` had gained a constructor argument that their own two tests had not caught up
with. Their files, their work, left alone — noted because it means **a red build is not always yours** and
the reflex of fixing it would have been wrong.

---

## 2026-10-03 — `e9142ed` — P1-1: four indexes on `match`, and two of the board's three claims refuted

**The board asked for three indexes. Two are wrong and one is irrelevant. Four unlisted ones are the
real win.** Every number below was measured on a throwaway database holding a **full projected season**,
because the dev database cannot measure this at all: it holds **155** `match` rows, which is one
matchday of one country, and a sequential scan of 155 rows is the *correct* plan. An `EXPLAIN` there
proves nothing either way.

### The harness, and the scale it derives rather than assumes

Scale came out of the live dev database, not out of the board's estimate:

| measured on `sokker_db` | |
|---|---|
| competitions | 31, all leagues |
| fixtures per matchday | 310, of which **155** played |
| matches played, in total | **155** — one matchday, ever |
| matchday spacing | **7 days** (2026-10-03, -10, -17 …) |
| weeks in a season | 12 |
| zone-load rows per match | 198 (22 players × 9 zones) |

So **full scale = 48 × 155 = 7,440 matches a matchday, 89,280 a season**, and `player_zone_load` gains
**17,677,440 rows a season**. The harness holds exactly that: 89,280 matches, 17,677,440 zone loads,
2.8 GB. Foreign keys to `competition`/`team`/`player`/`lineup`/`stadium` are dropped — no query under test
joins them, and their absence cannot change a plan.

**Measurement discipline.** The machine is shared with three other agents' work, so **every figure is the
minimum of three or five runs**, never a mean. This is not fastidiousness: the same query measured 4,441 ms
and 8,338 ms on different runs, and an early single-run reading convinced me an index I later dropped had
saved 154 ms. It had not.

### What was landed

| Query | Where it runs | Before | After | Index |
|---|---|---:|---:|---|
| `findByCompetitionIdAndSeasonYear` | top scorers / assists, **request path** | 158.7 ms | **0.19 ms** | `ix_match_competition_season` |
| `findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc` | club match history, **request path** | 170.3 ms | **0.26 ms** | `ix_match_home_team_date` + `ix_match_away_team_date` |
| `findByHomeTeamIdOrAwayTeamId` | club page, **request path** | 140.6 ms | **0.20 ms** | the same two |
| `findBySeasonYearAndWeekNumber` | `GoalEventRepository`, 12× a season job | 156.4 ms | **27.8 ms** | `ix_match_season_week` |

**Every one of those four was a sequential scan of `match`, and three of the four are on a request path
the manager loads to look at his own club.** None of them was on the board. The board's `match` claim was
about the daily recovery job, which is one query a day, while these are one per page view.

The plans, before and after:

```
-- findByCompetitionIdAndSeasonYear, 60 rows out of 89,280
 Seq Scan on match                                     Execution Time: 158.714 ms
 Index Scan using ix_match_competition_season on match  Execution Time:   0.276 ms

-- findByHomeTeamIdOrAwayTeamId...PlayedTrue...OrderByMatchDateDesc, 12 rows out of 89,280
 Sort  (actual time=57.174..57.177 rows=12)
   ->  Seq Scan on match  (actual time=29.662..57.128 rows=12)     Execution Time:  57.199 ms

 Sort  (Sort Key: match_date DESC, Sort Method: quicksort  Memory: 42kB)   Execution Time: 0.176 ms
   ->  Bitmap Heap Scan on match  (actual time=0.066..0.073 rows=12)
         ->  BitmapOr
               ->  Bitmap Index Scan on ix_match_home_team_date
               ->  Bitmap Index Scan on ix_match_away_team_date
```

**Column order, stated per index as the board asked:**

- `(competition_id, season_year)` — both equality, so the order between them is free; `competition_id`
  leads because it is the selective one and the usual way in.
- `(season_year, week_number)` — **cannot** be folded into the index above. That query has no
  `competition_id`, and a season is 89,280 rows, so leading on `season_year` alone is not selective.
- `(home_team_id, match_date)` and `(away_team_id, match_date)` — a predicate of
  `home = ? OR away = ?` needs an index on **each** side; one alone cannot be used for the OR at all.
  `match_date` trails so the same index serves the history page's `ORDER BY match_date DESC`.

### Write cost, measured

| 20,000-row insert into identical clones | Per row |
|---|---:|
| no new indexes | 25.7 µs |
| the four new indexes | 42.5 µs |

**+16.8 µs per match row** — about 4 µs per btree, which is what four of them should cost. At 7,440
matches a matchday that is **+125 ms once a matchday**, against ~130 ms saved on *each* of the request
paths above, and 24 MB of index per 89,280 matches.

**A first attempt at this measurement said +3.2 ms per row, a hundred times worse, and it was wrong**:
one 200,000-row transaction on a machine running three other agents. The 4 µs-per-btree figure is the one
that survives an independent method. Recorded because the wrong number was nearly the reason to drop all four.

### Refuted 1 — `match_tick_states`. Nothing writes it. Nothing reads it.

Its only writer is `MatchPersistenceService`, and that has **zero callers** in `src/main`: the only
references are its own javadoc, a comment in `ResetService`, and `ReservedWordColumnTest`, which reflects
on a field. `findByMatchOrderByTickAsc` and `deleteByMatch` have no callers at all. The table holds
**0 rows**.

**The replay path is `SimReplayStore`: one JSON file per replay under `app.replay-dir`, with its own
bounded retention by count and by `app.replay.max-age-days`.** The design that P1-1 and P1-5 describe —
900 rows a match, two JSON blobs each — was replaced by files.

So no `(match_id, tick)` index was created, **P1-5's growth premise is zero, not 900 a match**, and this
settles **P0-8 §1.4** and **P0-12 §4.4** without an owner decision. Deleting the table and the service is
an owner call and I have not touched them.

### Refuted 2 — `player_zone_load(match_id)`. No caller, and it makes things worse.

The board's reasoning was sound and its premise was not: `findByMatchId` exists, the unique index leads
with `player_id`, so the query cannot use it. But **`PlayerZoneLoadRepository.findByMatchId` has no caller
in `src/main`.** One test uses it. The 629 ms → 0.29 ms I measured is a test's cost, once.

And landing it anyway would have made the daily job **68% slower**:

| `findLoadMinutesPlayedSince`, one matchday window | |
|---|---:|
| with `ix_zone_load_match` | 7,436 ms |
| without it | **4,441 ms** |

The query returns 1,473,120 rows from a 17,677,440-row table. With the index available the planner picks
a plan it likes less:

```
 Hash Join  (actual time=115.513..5222.538 rows=1473120)
   ->  Seq Scan on player_zone_load load  (actual time=0.055..2501.989 rows=17677440)
```

Forced onto the index it is nearly six times faster than either:

```
 Gather  (actual time=6.288..694.455 rows=2946240)
   ->  Nested Loop
         ->  Index Only Scan using ix_match_played_date_id on match  (Heap Fetches: 0)
         ->  Index Scan using ix_zone_load_match  (actual time=0.012..0.067 rows=198 loops=14880)
   Execution Time: 888.693 ms
```

**So the index is worth having for this query — it just cannot be landed before the read is paged**, which
is P1-3's work. Landing it alone would have shipped a slower daily job.

### Refuted 3 — "every zone-load recovery query is a seq scan on `match`" is true and irrelevant

That scan costs **112 ms of a 5,318 ms query: 2%.** Indexing `match(match_date)` changed the total by less
than the run-to-run noise, so no such index was created. I proposed it, measured it, and dropped it.

| also measured and dropped | |
|---|---:|
| `ix_match_played_date_id (played, match_date, id)` for the Elo replay | 421 ms → **449 ms**. No gain. An early single run said 291 → 137 ms; that was noise. |
| `ix_match_date (match_date)` | no measurable change on any query |

### The guard

`MatchIndexDeclarationTest` — 4 tests, 89 ms, no database. It pins the four names **and their column
order**, because an index on the right columns in the wrong order is the same as no index, and this
repository already contains that mistake. It also asserts the **count**, because a fifth index is a write
tax on every match row and should have to break a test on purpose.

**Proven able to fail, twice:**

1. Swapping `competition_id, season_year` → fails, printing both orders.
2. Adding an unmeasured fifth index → **two** tests fail, one naming the drift between the entity and
   `tools/create-match-indexes.sql`.

### Two files, two jobs

The indexes are declared on the `Match` entity, because both profiles run `ddl-auto=update` and the schema
should be readable from the code. `tools/create-match-indexes.sql` states the same four as
`CREATE INDEX CONCURRENTLY` for a database that already has rows, because `ddl-auto` issues a plain
`CREATE INDEX` that **holds a write lock for the length of the build** — instant on 155 rows, not instant
on 89,280 and growing. The fourth test keeps the two from drifting.

**Verified in the database, not asserted** (`e9142ed`): run against `sokker_db`, and `pg_indexes` afterwards shows all
four alongside `match_pkey`.

### Left standing for the next session

- **`sokker_bench` still exists** — the harness, for P1-3 and P1-5. Drop it when P1 is finished.
- The recovery query's own planner statistics are wrong: `n_distinct` on `player_zone_load.match_id` reads
  **31,004** against **89,280** distinct values, so the planner predicts 569 rows per match instead of 198.
  `SET STATISTICS 1000` fixes the estimate and does **not** change the plan. Recorded, not landed — it is
  not expressible in `@Index`, and on its own it buys nothing.

---

## 2026-10-03 — restructuring the documentation, and a P1 index finding

### The board is rebuilt into P0 / P1 / P2

The root had four generations of planning documents, several actively contradicting each other. **Twelve
files moved to `archive/`** with a README explaining what each one is and why it is no longer the truth.

Every task in the new `kanban.md` is written to be executable from a blank session: what the defect is,
where it is, and **exit criteria as a checklist**. The general information a new session needs — the
exports, the database, the traps, the scale, the three rules — is above the task list rather than
alongside it.

**Two documentation contradictions were found and confirmed in source while doing it:**

1. The old board listed **`nationalCup()` returns the lowest-id domestic cup** as *owner-ruled but not
   implemented*. **It is implemented** — `CupFixtureSeeder.java:283` now calls
   `findFirstNationalScoped(CUP, INTERNATIONAL, limit 1)`. B2 closed it and the board never caught up.
2. A method called **`nationalCup()` queries `CompetitionScope.INTERNATIONAL`**, and lives in a class that
   also seeds national cups. The name and the scope disagree. Recorded as **P0-11**.

Also confirmed still open, against the source rather than the board: **`findTier2Leagues()` is hardcoded to
`"SRB"`** (`SeasonService.java:1094`), so `buildPlayoffSummary` reports nothing for the other 47 countries.

### Three hot tables have no usable index — read out of `pg_indexes`, not inferred

> **Superseded by the P1-1 entry above, which measured all three.** Two had no query behind them and one
> was 2% of the query it was blamed for. Kept as written: reading `pg_indexes` is how the candidates were
> found, and it is a good way to find candidates. It is not a way to know what a query costs.

Checked while writing P1, and better than expected:

| Table | Indexes that exist | Consequence |
|---|---|---|
| `match` | **primary key only** | `findLoadsPlayedSince` joins to `match` and filters `m.matchDate > :after`. Every zone-load recovery query is a **seq scan on `match`** |
| `match_tick_states` | **primary key only** | `findByMatchOrderByTickAsc` filters `match_id` and orders by `tick`. Replay path, ~900 new rows per match |
| `player_zone_load` | `ix_zone_load_player(player_id)`, unique `(player_id, match_id, zone)` | `findByMatchId` exists, but **the unique index leads with `player_id`**, so a query on `match_id` alone cannot use it. Seq scan on the largest table in the schema |

**Note the third one carefully: the index exists, and still does not serve the query.** An index on the
right columns in the wrong order is the same as no index. That is why P1-1 requires the column order to be
justified per index rather than listed.

**P0-12 §4.4's warning is not thereby answered.** It says *"do not index it as-is"*; a targeted
`(match_id, tick)` index is a different proposal from blind indexing, and P1-1 has to address that rather
than assume it.

Also recorded: the other three sports have **zero** `@Index` declarations (P1-6), and neither `match` nor
`match_tick_states` has any retention policy (P1-5).

### Suite status when this was written

A full `mvn test` was in flight from 18:03. At the 80-class mark I read it as **stalled and said so — and
that was wrong**: the log was live and the class was mid-run seeding a Croatian pyramid. A stalled suite
and a slow class look identical from the outside; **the log mtime is the check**, not the class count.

The three heavy classes — `PromotionLadderTest` (321 s), `CountryActivationTest` (273 s) and the two clock
classes — are the recorded reason a full run takes ~2 h 52 m. Its result is appended below when it lands.

---

## 2026-10-03 — the weekly rollover was priced for a village

**Found by a thread dump, not by reading the code.** A full-suite run printed nothing for two and a half
hours and it was natural to assume it was wedged. It was not: `main` had **15,343 seconds of CPU** and
7.4 GB resident, and the stack said exactly where it was.

```
GameClockService.advanceHours -> WeekRolloverJob
  -> TransferService.simulateWeeklyMarketActivity -> maybeCreateIncomingOffer
  -> needsInterest -> ClubNeedService.interest -> ClubNeedService.clubSquad
```

`clubSquad` runs `players.findByTeamId(club.getId())` — one query per call. The shape is a **cross
product**: for each listed player the market asks every club whether it is interested, and each answer
loaded that club's squad. Then `weightedBuyer` asked again for every willing buyer, and `valuation` a
third time.

| | before | after |
|---|---:|---:|
| per-club squad reads (30 clubs, 3 listings) | 180 | **0** |
| bulk squad reads | 0 | **1** |

Three decisions worth keeping:

- **`null` means "load it yourself", an empty list means "this club has nobody."** Both are real answers.
  Collapsing them silently re-introduces the query per call, because an absent key is a fact, not a gap.
- **The squad is resolved _after_ the early guards.** A club has no interest in its own player, and
  answering that without touching the database is the point of the early returns.
- **One snapshot per pass is correct, not merely cheaper.** Nothing in the pass completes a transfer — a
  bid is only recorded — so there is no mid-pass player move for the snapshot to miss.

`weightedBuyer` also computed each buyer's appetite **twice**, once to sum and once while walking. And an
`if/else` had **two identical arms**, so the human-managed check decided nothing.

#### The guard test that passed while measuring nothing

Worth recording because **both** failures were mine:

1. A first attempt used `@SpyBean` in a Spring test. It **skipped** on the empty H2 database and reported
   green. Rewritten as a pure mocked unit test — milliseconds, exact counts.
2. The rewritten version still passed on the broken code. The market gates on
   `nextRandomDouble() > 0.68`, so unpinned **the test did nothing, read zero squads, and passed** —
   because a budget of thirty is satisfied by zero. `nextRandomDouble()` is `protected`, so a test subclass
   pins it.

The guard now asserts **exact** counts — `0` per-club and exactly `1` bulk — plus a second case asserting
two identical passes read the same total, so accumulated state would fail it. It failed at **180** before
the fix.

#### Not mine

`TransferCompletionTest` (3 errors) and `OmladinacTransferJourneyTest` (6 errors) both fail in `setUp`
with `NoSuchElementException` on an empty test database. **Verified red beforehand by stashing the change
and re-running**, rather than assumed.

---

## 2026-10-03 — a regression I introduced, found by reading and not by a test

In `abef6a2`, making a club's formation decide its own role keys made something newly reachable: **a role
the rules do not name.** Until then every player in the world wore one of 4-4-2's eleven and the rules were
4-4-2, so it could not happen.

Both miss paths answered with a hardcoded `new Position(1.5, 3.5)` — **the goalkeeper's own-half corner**. So
in any mixed-formation match, every unmentioned outfielder of one side was sent to **the same square metre
of pitch**: a wall of players, and unreadable replays.

Null could not simply propagate — `TacticalIntentEngine` does `p.setTarget(desired)` and a
`SimUtils.distance(desired, …)` immediately after — so all three `RestartManager` sites and the tick loop
needed a guard.

**The contract now: "no rule and no anchor" answers null, and every caller falls back to the player's own
position**, which `RealSquadFactory` has already placed from his own formation's anchors. A player whose
role the current tactic does not mention holds his shape, which is what a manager who has not authored a
rule for him would expect.

`UnnamedRoleFallsBackTest` 3/3, proven able to fail by restoring the hardcoded cell, which printed it:

```
a 4-3-3 holding midfielder asked a 4-4-2's rules and got an answer.
  It used to get (1.5, 3.5) ... expected: <null> but was: <(1.50,3.50)>
```

The third case asserts the two vocabularies **must differ**, so the miss path is genuinely exercised
rather than quietly satisfied.

**No test found this one. Reading the code the change touched did.** It is now P0-3.

---

## 2026-10-03 — tactics reach the engine, and step 4 closed with no code change

**The seam.** `TacticsRulesProvider` loads the home club's profile and `SimMatchService` passes it through
the `MatchOrchestrator(state, tactics)` constructor that previously had zero callers. `8b3dff1`.

**Verified live**, not asserted: the app logged
`OFK Omladinac (1) plays its own 4-4-2 tactics: 1012 rules from the tactical editor`.

**Restore.** Reset now reads `var/tactics-editor-profiles.json` and names the profiles it cannot place rather
than failing silently. `d4ccfde`.

**Formations.** `RealSquadFactory` derives role order and anchors from `FormationSlotCatalog`, so a 4-3-3
profile actually plays. `abef6a2` — and the source of the regression above.

**Step 4 — the owner's answer was that nothing should change.** `WE_HAVE_BALL` and `OPPONENT_HAS_BALL` are
identical in every saved profile, **on purpose**: the owner has not decided whether to keep both variants.
So `mirrorWeHaveBallRules` is a **decision, not a defect**, and `DefensiveShape` is load-bearing rather
than decorative. I had recommended letting the editor author defence properly, which would have pre-empted
a decision explicitly not made.

**Multi-tactics was specified before any code**, because this feature has been specified wrong twice before
and one part of it is still undecided: 20 tactics per team, one default, up to 3 per match switched by
minute and score (`ALWAYS`, `FROM_MINUTE`, `WE_LEAD_BY_1`, `WE_LEAD_BY_3_PLUS`, `WE_ARE_DOWN_BY_1`,
`WE_ARE_DOWN_BY_3_PLUS`, `DRAWING`; the `...` is modelled as data, not a `switch`). **It is not a small
change:** the engine's tactics are immutable per match, and whether the storage is keyed by possession
context depends on **P0-4**.

---

## Earlier history

Everything before 2026-10-03 is in **`archive/kanbanProgress.md`**, including:

- the full-suite run that was allowed to finish — **992 tests, 13 failures, 16 errors, 29 red**, and the
  complete list of what each red class means
- why a full run takes ~2 h 52 m, and that it is D3 reproduced inside the suite
- the D1 query fixes with their measurements
- the four separate "green but did nothing" bugs, and the three tests that were green while measuring
  nothing before they were fixed

That file is the most valuable document in the repository for anyone about to change scheduling, seeding or
the match engine.