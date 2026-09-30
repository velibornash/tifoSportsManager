# 📈 kanbanProgress.md

**The running log for [`kanban.md`](kanban.md).** One entry per task, newest first, each carrying the
commit that landed it — so a claim in the board can always be checked against a diff.

**Why this file exists.** `kanban.md` is the *state*: what is open, what is done, what order. It gets
rewritten as things land, which is exactly what makes it lose history — four Sprint 1 rows sat there
claiming "not started" for work `sprintProgress.md` had already recorded as landed, and the numbers in
three more predated the code they described. This file is the append-only half. It is not edited
retroactively: an entry is written when the task lands, and if a later task invalidates it, that is a
new entry that says so.

Rules for an entry:

- **The commit goes in the heading.** If a task needed three commits to land correctly — because a
  guard test caught a wrong fix, or a fix was reverted — all three are named, and which one was wrong
  is said.
- **What was verified, and how.** A passing test is not evidence on its own. A count that meets a
  number is; a live database read is; a real click in a browser is. "It compiles" is not.
- **What was assumed and not checked.** Stated plainly. Most of the value of this file is in the
  sentences recording what is *still* unverified.

## `0056bc5` — the zone model gets a writer, and `Zone` itself was wrong

**Task:** *"Zone-based morale and daily recovery — model only. `Zone`, `PlayerZoneLoad` and
`RecoveryJob` exist, but the match engine never writes `lastPlayedAt` or the load table, so recovery
correctly reports zero. Needs the engine to feed it."*

**`Player.rating` was the previous task's, and it turned up here too.** Not worth repeating.

### The zone enum was self-inconsistent, and nobody could tell

`Zone` has two numbers per constant, a `workRate()` keyed to the constant's **name**, and an `of()`
that derives (third, lane) from a row and a column. Those three disagreed. The constants were declared
lane-first while the constructor assigned the first argument to `third`, so **every name carried its
third and lane swapped**: `DEFENSIVE_CENTRE` held (third 1, lane 0), which are the coordinates of a
defensive *left*.

Proved by running the enum, not by reading it:

```
Zone.of(1.5, 3.5) = MIDFIELD_LEFT      <- a keeper on his own goal line
Zone.of(1.5, 1.5) = DEFENSIVE_LEFT
Zone.of(7.5, 3.5) = MIDFIELD_RIGHT
```

`workRate()` then charged that keeper the busiest rate on the pitch. It survived **because the table was
empty**: `of()` is only reached when a match has been played and a load row written, and `workRate()`
only reads a row's own zone. With no writer anywhere, a keeper standing on his line was never classified
at all. The declaration order is now (third, lane) and the reason is recorded on the constants.

This is the second time in two days that a model was wrong in a way only the absence of data could
hide. The pattern is the same as the retired `simulateQuickScore` claim: an empty table proves nothing,
and a test over the arithmetic proves nothing about the arrival.

### The writer

`ZoneLoadRecorder` reads the load off the match that was played, from the positions the engine already
records every tick — not from events, because a player who covered four zones for ninety minutes and a
keeper who held one are different and that difference is invisible if you only follow the ball.

- **Minutes** come from the tick count, forty ticks to a minute, the engine's own figure.
- **Intensity** is the average speed while he was in that zone, normalised against the engine's own
  full-pace ceiling — so a keeper waiting on his line scores near zero and a winger at full speed scores
  one. Kept apart from minutes on purpose: a lot of ground slowly is not repeated sprinting.
- **Zones are from the player's own perspective.** The engine's rows run from one fixed goal line, so an
  away player's row is mirrored before it means "his own third", and his left is the pitch's right. Two
  players on the same column at the same row land on opposite sides of their own pitch, which is the
  whole reason for the rule — the loads have to be comparable between the two teams to be worth
  recording.
- `lastPlayedAt` is stamped at the same time. It had **zero writers** in the codebase.
- Wired into `SimMatchService`, which `ProposalEngineIsTheOnlyFixtureProducerTest` establishes is the
  only place a football match is produced. Both entry points pass the snapshots; the three-argument
  `persist` still exists and every existing caller is untouched.
- Best-effort: a failure to write a zone row logs a warning and never costs a match result.

### Verified — a real matchday through the app's own endpoint

| Check | Result |
|---|---|
| `POST /simulation/current-round/simulate-all` | 200 |
| `player_zone_load` afterwards | **2,140 rows, 418 players, 19 matches** |
| Minutes | every starter's zones sum to **exactly 90.0** — nothing double-counted, nothing lost |
| Zones | all nine used; MIDFIELD_CENTRE and ATTACKING_CENTRE busiest by minutes, which is where the ball is |
| Intensity | 0.01 to 1.00 across the range, and 0.12 for a holding midfielder against 0.55 for a attacking one |
| `last_played_at` | 616 players stamped — exactly the ones who played |
| Tests | 740 green |

`ZoneLoadWiringTest` asserts the arrival rather than the arithmetic, which is what the area was
missing: play a match, assert rows exist, assert recovery is now positive, assert the zones sum to a
match.

### Two things I got wrong

1. The mirror test. I asserted an away player at row 1.5 was in his **defensive** third. Row 1 is the
   *home* goal, so it is his attacking third — the code was right and the expectation was wrong. The
   test now shows the mirroring where it is actually visible: the same column and row, home and away,
   landing on opposite sides.
2. A first pass declared `import ... Position as EnginePosition` — Kotlin syntax in a Java file — and
   included a test asserting `90 * 40 == 3600`, which is arithmetic about nothing and would have been a
   fake checkbox. Replaced with the real check, inside the test that has the data.

### Not verified

**I never saw the `recovery` day-job report a non-zero count in the running app.** It has run twice and
completed `DONE`, but both times were before the zone data existed, and it is idempotent per
day/hour so it does not re-run. Advancing the clock to day 3 and through hour 10 produced no new run,
and `job_run` records its hours as 6 and 8 while `RecoveryJob` is configured for 10. That mismatch is a
**day/hour scheduling defect and a separate task** — the kanban already carries "simulate-all is
week-based, should be day- and hour-accurate". The read path is proven by the integration test; the
scheduling is not proven at all.

---

## `48c9b8d` — the startup failure, and two boot-ordering bugs under it

**Task:** whatever was blocking. Started as a `NonUniqueResultException` the owner hit on the league
table.

`GET /countries/leagues/1/table` and `GET /teams/1/schedule` both returned
`Query did not return a unique result: 3 results were returned`. Three defects stacked, each hiding
the next.

**1. `season_competition` had no unique constraint on `(competition_id, season_year)`, and
`findByCompetitionAndSeasonYear` returns an `Optional`.** So two rows for one league and season did
not degrade — they threw, and the league table and the club schedule went down together. The
duplicates were recent, not ancient: rewriting 2025 → 1 means anything that creates a season row
*between* those two moments asks for season 1, finds nothing, and creates a row beside the one it
should have reused. League 1 had three season competitions, each with its own ten table entries.

Found by querying the database, not by reading the log: `SELECT competition_id, season_year ... HAVING
count(*) > 1` returned one row with `ids {1,33,34}`, and all three held 10 entries.

**2. My first constraint check made the application unbootable** — caught by the owner pasting the
startup failure. I wrote it to catch the "already exists" error, which does not work: a failed statement
inside a transaction marks it rollback-only whether or not you catch the exception, so the catch
swallowed it and the commit threw `UnexpectedRollbackException`. It is the same trap `sprintProgress.md`
records the world catalogue falling into — caught, reported as success, fatal at commit. Rewritten to
look in `INFORMATION_SCHEMA` first and only alter when the constraint is genuinely absent.

**3. All three backfills sat in the wrong branch.** They were inside the *"baseline already exists"*
early-return of `ensureBaselineDataOnStartup`, so a fresh Reset + Initialize — which goes through the
pyramid and the world build below — never ran them. Evidence from a world I reset and reinitialised:
**4,650 club players with `rating = 0`**, while the 2,400 national-squad players were fine, because
the seeder that creates them writes a rating. They now sit beside the integrity repair, which is the
one thing that runs whichever door the world came in by.

### Verified — a real Reset + Initialize, then read the database

| Check | Result |
|---|---|
| World integrity | `healthy=true` — 48 countries, 96 sides, 48 squads, 310 clubs, no legacy rows |
| `PlayerRatingBackfill` | logged `recomputed 4750 of 7150`, spread `{0=4750}` — exactly the unrated ones |
| Ratings afterwards | 7,200 derived, 9–97, **none at 0** |
| `GET /countries/leagues/1/table` | 200 |
| `GET /teams/1/schedule` | 200 |
| `GET /countries/SRB/cup` | 200, **54 round-1 ties** — the bracket that read "0 ties across 8 rounds" |
| Duplicate `(competition, season)` rows | 0, constraint present |
| Startup errors | 0 |
| Tests | 738 green |

`SidebarAccordionOpensTest` re-pointed at the mobile drawer. It is a live Playwright test that logs in
as the owner, because a collapsed panel and a dead panel look identical from outside and only a real
click tells them apart. It now walks the `.accordion-content` panels rather than the headers: most
headers in the drawer are leaf navigation buttons wearing that class, and their `nextElementSibling`
is null. Its leftover `PROBE` printlns are gone.

### Not verified

- The owner's browser session. I killed their app and started my own while two instances fought for
  8080; the JWT survives a restart so it should be fine, but nobody has looked.

---

## `052f2c7` — `Player.rating` is one thing, derived from skills

**Task:** the board said *"`Player.rating` = skill × 8 — wrong. A skill-12 bot reads as rating 96.
Conversion needs calibrating."*

It was not a conversion problem, and rescaling would have treated the symptom. The column had **three
writers on three scales**, and `PlayerDTO.calculateOverall` read it three times as if it were one
number:

| Writer | Wrote | When |
|---|---|---|
| `BotSquadGenerator` | `BASE_SKILL * 8` = **96** | once, at seeding |
| `YouthAcademyService` | **50**, hardcoded | per graduate |
| `SimMatchService.bumpCareerStats` | **the last match's rating** | every match, overwriting |

**Verified in the live database rather than reasoned about:** 2,350 players at exactly 96, 5,250 at 0,
and **none** in the match-rating band. The formula only applied its bonus when `rating > 0`, so two
players of identical ability sat about six OVR points apart — decided by which seeder created the row.
`form` was already carrying "how he has been playing lately", written weekly by `MoraleService` and
already in the formula.

Owner decision: **a career rating derived from skills.**

- `Player.careerRating()` is the one definition — a 1-100 rating from `Skills.getRatingScore(position)`,
  the same function the OVR formula already normalises, so the two cannot drift.
- The match engine no longer writes it. The per-match rating lives on `MatchPlayerStats`, where it
  already was.
- The unbounded `(rating - 62) / 5.5` term is bounded to ±1.5, and the defender and keeper role terms
  that read the same column twice more no longer do. **Even a rating of 1000 now moves OVR by ≤2.**
- The per-position maxima were duplicated between `Player` and `PlayerDTO`, and **four of the five
  agreed on a maximum skill of 17 while the defender's was hand-tweaked to 93.6 where 17 gives 98.6**.
  One definition now, so a defender's OVR drops very slightly — which is the point of having one.
- `PlayerRatingBackfill` recomputes the column on boot, own transaction, writes only rows that do not
  already match, so a settled world is left alone and a re-boot does no work.

### Two things I got wrong, both caught

1. `ratingRisesWithAbility` failed with **every keeper clamped to 100**, because I assumed a maximum
   skill of 4.5. The existing normaliser was the thing that told me the real answer: four of its five
   values are exactly 17 × the weight sum.
2. `SimMatchPersistWiringTest` asserted the match rating (80) lands on the player — **that was the
   bug**. My first correction used `assertNotEquals(80, …)`, which is unsound: that player's derived
   rating also happens to be 80, so it would have passed or failed by coincidence. The assertion that
   is actually sound is that persist leaves the player's rating untouched, and that is what it says.

### Not verified

Every OVR in the game shifts once. The boot log reports how many rows moved and what they were — the
owner has not yet looked at a squad screen to confirm the numbers read sensibly.

---

## `ca175c4` — one league-table order, and the read path stopped deleting the season

**Task:** S8.4 — *"League table: three comparators, one implementation"*, plus
`ensureEntriesForSeasonCompetition` deleting and rebuilding entries on membership drift.

**The board's file references were stale** — `MatchStatisticEngine` does not exist. There were **four**
implementations, not three, and they agreed on points and goal difference while disagreeing after that:

1. points, goal difference, goals scored, team id — the most complete
2. points, goal difference, goals scored — **the table the manager reads**
3. points, goal difference, goals scored — the one that **writes** the stored position
4. points, goal difference — **the playoff draw**, missing goals scored entirely

What the disagreements cost, all real:

- Two runners-up level on points and difference were ordered arbitrarily in the playoff draw while the
  table ordered them by goals scored. That is the exact *stronger-club-gets-the-easier-tie* bug the
  playoff code's own comment says was already fixed once — reintroduced a few lines below by a
  comparator one key short.
- Only one of the four had a final tiebreak on team id, so a total tie was ordered by whatever order
  the repository returned and the same table could render differently between two requests.
- One **writes** `position` and another **reads** it, so the number in the teams list and the number in
  the table came from two different comparators.
- Two of the four did `getGoalsScored() - getGoalsConceded()`, which unboxes. One was the endpoint the
  manager reads, so a club with a half-created entry threw instead of sorting.

**The read path was deleting the season.** `ensureEntriesForSeasonCompetition` deleted every entry and
rebuilt from zero on any membership drift — and the league table endpoint calls it *before it reads*.
One club joining a division reset every other club's points, wins, draws, losses and goals, mid-season,
for a manager who had done nothing but open the page. Now the difference is applied as a difference.

### Verified

`theOrderIsPointsThenGoalDifferenceThenGoalsScoredThenId` makes every one of the four keys
load-bearing — a comparator that dropped goals scored would still pass the first three positions.
`aTotalTieIsBrokenStablyById` orders the same either way round. `aNullSortsAsZero` covers the two
unboxing comparators. `aDepartedClubLosesOnlyItsOwnRow` covers the one case where losing a row is right.

**`aNewClubDoesNotWipeTheTable` was proved against the old code**: restoring delete-and-rebuild makes
it report `expected: <21> but was: <0>` — Alpha's 21 points, gone. My first attempt at that proof was
itself wrong: the ordering test failed on a fixture I had mislabelled, and the code was right.

---

## `f93a695` — the guard that every fixture is played by the proposal engine

**Task:** S7.1. The audit that produced it was **retracted** — it claimed AI-vs-AI league matches came
from a Poisson dice roll, and that was wrong. `simulateQuickScore` was only reachable from two
services with zero callers, one of which was not injected anywhere. Sprint 7 was cut from five days to
a regression test; this is it.

So the test is not "the engine works" — it is **"nothing has grown beside it"**. A dice-roll path is
the easiest thing to reintroduce: fast, deterministic if you seed it, and it makes the league table
look tidy.

- Exactly one production site constructs a football `Match`, and it is `SimMatchService`. The other
  `matchRepository.save` calls are named and allowed, with the reason: a fixture is *played* when its
  Match row is born, and the rest update a row that already exists.
- `simMatchService.simulate` is entered from `SimulationController` and `AsyncSimulationRunner` and
  nowhere else.
- Both structural scans strip comments first, because the retracted audit counted a mention.
- Behaviourally: a real engine run persists with a full 90+ minutes, 22 player rows, possession
  summing to 100, a replay, and a played fixture pointing at the match that played it.
- A fixture is seeded by its own id, so it cannot be re-rolled.

American football and basketball are outside the scan on purpose — they have their own engines, and a
scan that included them would report their code as a violation.

Also fixed: `SimMatchPersistWiringTest` built fixtures with `seasonYear = 2026`, the calendar-year
scheme this project no longer uses. It passed anyway, because it hands `persist()` a hand-built outcome
and never queries by season — which is exactly why the stale value was invisible.

Cost: ~110 s for the class, because it runs the engine three times. 738 tests, 5:24.

---

## `1a3fae2` — the league area gets a navigation, and 6 of the 13 unreachable pages are wired

**Task:** S8.1. The board said "navigation only". It is not, and the reason changes what the task is.

**Seven of the thirteen fetch from `/demo/...`, which is `DummyDataController`** — fake data, every
route hardcoded to team 1. The frontend calls them with the logged-in manager's team id, so they 404,
and they `await response.json()` without checking `ok`, so the throw escapes to the router and the page
becomes a generic "API Error" card. A menu entry on one of those is a fabricated table, or an error
card, in the manager's navigation.

Wired, because a real endpoint exists: `results` (into the Club row — it renders the Club action row
already, so that is where it belongs), `topScorers`, `topAssists`, `leagueMatches`. `playerStats` and
`teamStats` are second names for the scorers and assists screens and need no entries of their own —
**which is why 13 routes are 11 screens**. `training` is an alias of `trainingSetup` and is working as
of `9dd11ef`.

Not wired, with the real equivalent recorded for each: `upcoming` → `/teams/{id}/schedule`, `friendlies`
→ `/api/season/friendlies/{id}/week`, `coaches` → `/teams/{id}/coaches`, and `events`,
`international` and `analytics` have no equivalent and need a decision rather than a line edit.

Also: both loaders now check `response.ok`, because a menu entry pointed at a page that renders a
generic card is worse than no entry; and a manager in no league used to get a **blank page** — the
bare `return` is now a sentence with a way onward.

Verified by rendering the real modules at 1280 and 390: correct routes, current page highlighted, no
horizontal overflow, club row at 13 buttons. Not verified in the running app.

---

## `113a7c7` — the board corrected against the progress log

Not a task. The board and `sprintProgress.md` disagreed and the board was the one being worked from.

- The Review column said Training under Club needed the owner's eyes. It needed a renderer, a second
  binding fix and a dead sidebar removed.
- The Review note told the owner to hard-reload `dashboard.html` for a change that had become code and
  needed a restart instead — and said the restart must log the season rewrite.
- Four Sprint 1 rows claimed "not started" for landed work; S1.1 was partly landed and S1.2 half
  landed. Working from them would have re-implemented finished work and reported the wrong open half of
  S1.2: the goal kicks were fixed, the throw-ins at 75.5 against a real 35–45 are not.
- The match engine section is marked last-and-re-baseline, per the owner decision recorded in
  `3f8b1f4`.

---

## `9dd11ef` — a season is a number, and the menus were unreachable

The first commit of the run. Five defects, all found by looking at what the app shows rather than what
the logs say.

**Seasons.** `SeasonCalendar` says in its own class comment that a season is twelve weeks, so four
run in a year and **no calendar year can name one**. The season was written as a year anyway:
`BASE_SEASON_YEAR` was 2025 and every caller asked for `BASE_SEASON_YEAR + (season - 1)`.
`sprintBacklog.md:3351` had already written the hazard down — the day-5 matchday job asked for 2025,
found no cup fixtures, returned SUCCESSFULLY and played nothing — and the fix that went in moved the
cup seeder onto the year rather than removing the offset. That fixed the job and broke the page.

- `BASE_SEASON_YEAR` deleted; all twelve offset computations removed.
- `CupFixtureSeeder.SEED_SEASON` was a constant that was wrong twice. Now `seedSeason()`, asking the
  clock, so a world on season 2 does not get season-1 cup ties. Changing the constant was never going
  to help: the call sites did not come from it.
- **Three copies of "which season is it"** each guessed the highest row in the season table instead
  of reading the clock. One does now — `CountryController`'s own Javadoc admits the guess reported "no
  election" while one was running.
- `FinanceController` returned `java.time.Year.now().getValue()` — the real wall-clock year, a third
  answer nobody had noticed.
- The frontend converted both ways: `formatSeasonLabel` rendered "2025/26" and `league-view` computed
  the season number as `selectedSeason - 2025 + 1`, so a season-1 league was labelled **Season 2025**.
- `SeasonNumberBackfill` rewrites `season_year >= 1000` to 1 across the nine football tables, own
  transaction, idempotent, carrying no year of its own.

**Mobile menu could not be opened at all.** `#mobileOverlay` carries the `mobile-only` class and the
responsive utility force-shows every `.mobile-only` element with `display: block !important`, which beat
the scrim's `display: none`. A full-viewport div at z-index 1190 sat permanently over the top bar at
z-index 10 and ate every tap, calling `closeMobileMenu` on each one. The hamburger's own `z-index: 1300`
was dead — it is scoped inside `.top-menu`'s stacking context. Found by measuring with
`document.elementFromPoint` in a real browser at 390×844, not by reading the CSS.

**Desktop Club sidebar was never rendered.** `.sidebar` is fixed at `left: -260px` with `width: 250px`,
only `.sidebar.active` brings it in, and nothing on the desktop path ever applied it —
`window.toggleSidebar` was defined and never called. Measured `left: -260px`, `right: -9px`, outside
the viewport. All twelve Club entries were invisible. Removed, with bindings that also fired `loadPage`
twice per click.

**Training Setup had a state layer and no renderer.** The router's `trainingSetup` case called a
function that was the setup screen wearing the wrong name, and its `render()` belonged to the reports
screen in another function's scope — the `ReferenceError` the previous commit fixed. The commit removed
the dead half and left the alias, so both buttons opened Training Reports. The CSS for the screen
(`.group-skill-select`, `.training-dropzone`, `.training-player-card`, `.group-tag`) all survived, and
so did the backend; only the renderer was missing, so it is written against that CSS. The group options
mirror `TrainingProgressionService.normalizeDtSkill` exactly, because it silently substitutes a default
for anything it does not recognise. **No drag and drop — it has never worked on touch.**

**`loadHomeTeamStats` null-dereferenced** — it wrote into the dashboard after awaiting the league
table, so a navigation mid-flight made every write fail. The nodes are resolved before the fetch now.

### Two guard tests fixed rather than worked around

`SidebarBindingTest` matched only the desktop wrapper that no longer exists; the real discriminator is
a following content panel, since every mobile leaf nav item is wrapped in `mobile-accordion` while
expanding nothing.

`TrainingViewNoShadowedDeclarationsTest` keyed scope on indentation, which cannot tell two *sibling*
functions from one function. Rewritten to key on the enclosing declaration chain, then **proved still
to catch the original defect** by injecting two `render` declarations at the same scope and confirming
`{render=2}`. My first attempt at that proof was a wrong injection and looked like the guard had broken;
it hadn't, the injection had.

### Not verified

The real backend — the app was down for most of that commit. Everything was checked against the
database and against the real modules with a stubbed API.

## `61c2a51` — the manager does not see their own result until they ask for it

**The owner's complaint was two bugs wearing one coat.** The results were showing, and the fixture
dates were wrong. They were separate, and the second was hiding the first.

### The result was never actually hidden

`SimMatchService` had one line that decided everything:

```java
boolean involvesManager = isHumanClub(fixture.getHomeTeam()) || isHumanClub(fixture.getAwayTeam());
match.setHomeResultRevealed(!involvesManager);
match.setAwayResultRevealed(!involvesManager);
```

That code was already correct. It was written in the previous session and never ran, because the
matches in the database were simulated by the **old** process — before this line existed. So the first
diagnosis was wrong in a way worth recording: the rule was not missing, the code that applied it was
not loaded. A restart and a fresh round is what proved the rule was fine.

**A hidden score is `null`, never `0`.** This is the whole reason the mask belongs in the DTO. A
0-0 that has not been played is indistinguishable from a goalless draw, and the fixture list
publishes a score column for every row, so a masked row that reported `0` would have invented a
result. `MatchDTO.homeGoals`/`awayGoals` changed from `int` to `Integer` for exactly this.

**Masking in the renderer would have leaked on the fourth screen.** While building the schedule card I
rendered from `match.homeGoals` — the schedule already masked those to null, so the card looked right
in isolation and was only right by luck. The league results list is the one that proves the point: it
read `match.homeGoals` straight off `MatchDTO` and printed the real score with a `resultHidden` flag
sitting right there, unused. Two renderers, two rules, already disagreeing. The mask now happens once,
in `MatchDTO.from`, and `GET /matches/{id}` passes the viewer too — it ignored them, so a direct fetch
was a way to read a result the manager had not asked for.

**`resultHidden` alone is not a mask.** The league row also prints a `W`/`D`/`L` chip, and the
schedule card a `fx-result-chip`. A 1-0 with no `W` still tells you the result, so both chips are
suppressed along with the score, and the click-through to the match goes with them — a row that will
not open is the clearest possible statement that there is nothing to open yet.

### Two buttons, because they are two questions

**Watch your match** opens the match in the viewer; you watch it happen. **Show results** reveals it
and opens the details on the goals. The dashboard already had this pair but called them "Watch match"
and "Open report", and "Open report" landed on the **report** tab — the owner asked for goals, so
`showGoals` was extracted from an anonymous arrow into a named function that `initialTab` can call.
That arrow was also the reason `initialTab: 'goals'` was impossible before.

**One implementation, three surfaces.** The dashboard had its own `revealMatchResult` and its own two
`forEach` blocks. The club schedule and the league results had nothing at all. Three copies of
"reveal, then navigate" is three places for them to disagree, and two of the three were already blank.
`reveal-ui.js` now owns the buttons and the binding, and the dashboard's local helper delegates to it.
Binding is marked per button (`dataset.revealBound`) because these lists are re-rendered wholesale —
a second bind must not stack a second handler, and a button that looks alive and does nothing is the
exact failure this feature exists to remove.

**A failed reveal does not block the navigation.** `revealMatch` catches and warns, then the match
opens anyway. The manager pressed the button; the score is already computed; the worst case is that
the row stays masked until a reload, which is a far smaller failure than a dead button.

### The calendar is a separate fact from the date

`Match` had `seasonYear`, `roundNumber`, `weekNumber` and `matchDate` — and no `dayNumber`, because
`MatchFixture` had one and the match was built from the fixture without copying it. So a played match
could not say which day of the season it was on, and `Season 1 · Day 3 · 18:00` had no source for the
middle of it. `Match.dayNumber` is new, copied in `SimMatchService.persist`, and `ddl-auto=update`
adds the column on boot.

**The label omits what it does not know.** A match played before this commit has no `dayNumber`, and
printing `Day 0` would be a lie about a matchday. It renders `Season 1 · 23:59` instead, and the live
check below confirms that is exactly what happened to the old rows.

**"Next match" sorted by the wall clock.** It filtered `!played` and took the first, which looked
right and mostly was — until a cup tie and a league round share a date, where the wall clock has no
way to say which is the next matchday. Season → week → day is the order the fixtures were generated
in, so it is the order a manager means by "next"; the timestamp is the tiebreaker and the only key
when the calendar columns are missing.

### Verified live, not just in tests

Team 1, week 2, after a restart onto the new code:

- simulated match → `homeGoals: null`, `awayGoals: null`, `resultHidden: true`, `dayNumber: 7`,
  `seasonDayLabel: "Season 1 · Day 7 · 23:59"`, `replayId: 14`, `competitionType: LEAGUE`
- `POST /matches/234/reveal` → `{revealed: true}`; the same GET then returned `0-0`, `resultHidden: false`
- the schedule row for the same match carried `day: 3`, `week: 1`, `seasonDayLabel`,
  `competitionType`, `replayId` — the last one matters, because a hidden row's **Watch your match**
  has nothing to open without it and the fixture id is not the replay id
- a match persisted before the change → visible score, `seasonDayLabel: "Season 1 · 23:59"`

**752 tests green** (740 before, plus 9 `MatchDTORevealTest` and 3 in `SimMatchPersistWiringTest`).
The persistence tests pin both directions: a manager's match persists unrevealed, an AI-vs-AI match
persists revealed, and the calendar is copied. The AI case is not a formality — the league table and
the cup draw read those, and a masked AI result would only ever make those two screens look broken.

### Not fixed, because it is not this task

`POST /admin/reset-db` died on a Postgres deadlock — `AccessExclusiveLock` on a relation while the
background league simulation was still running. The world survived intact, so this is a
reset-vs-simulation contention problem, not data loss, and fixing it would have meant changing the
reset path during a feature that does not touch it. It is written up in `kanban.md` for the next
session that has room.

## `26a000c` — the pyramid gets a gradient

### The number in the board was wrong in a way that mattered

The task line said "Tier 1 at average skill 12, then 11, 10 by tier. Bot squads are all skill 12
today." Neither half was true, and the second half being wrong is what made the first half sound
reasonable. So before writing anything I measured the live database:

| Tier | Clubs | Avg skill | Min | Max |
|---|---|---|---|---|
| 1 | 9 | 8.61 | 3.6 | 13.9 |
| 2 | 20 | 8.71 | 4.6 | 12.5 |
| 3 | 40 | 8.57 | 4.1 | 12.8 |
| 4 | 80 | 8.56 | 3.5 | 13.4 |
| 5 | 159 | 8.57 | 2.9 | 14.0 |

Not "all 12". A **uniform 1-17 draw on every skill, with no reference to the division at all** — so
every tier landed on the same average *and* the same spread, and tier 2 came out as the strongest
division in the country. The practical cost is not that the numbers are ugly: it is that promotion
and relegation were deciding the table on reputation and tiebreaks instead of on football, because
there was no football gradient to decide it with.

### The standard, and the two things a number cannot carry

`BotLeagueStandard` turns a tier into a player average: 12, 11, 10, 9, 8. Tiers 4 and 5 continue the
step rather than sitting where the old draw left them, because a fifth-tier side at the third-tier
number is the same flattening one row down. Out-of-range tiers clamp to the top flight — a league row
with a null tier must still produce a playable club, and competitive is the safe direction to fail in.

**A tier is a player average, and the position redistributes within it.** The eight skills are handed
out so their mean is the tier number, then the position's own attribute goes up by three and an
attribute the player will never use comes down by three. A tier-1 keeper is 15 at goalkeeping and 9 at
playmaker, and both are a 12 player. The old code wrote the same value into all eight columns, so
every player in the world was equally competent at everything — including the goalkeepers, which is the
one place a flat profile is a functional bug rather than an aesthetic one.

**A squad needs a spine.** Twenty-five men all sitting exactly on the tier number is a squad with no
goalkeeper, no substitute and no reason to pick anybody, so each man draws a depth offset in −2…+2,
weighted about a quarter above the standard. The club still averages the tier; the *squad* has a shape.

Value and wage follow the skills exponentially rather than linearly, because football wages are: the
step from solid to excellent costs several times what the step from semi-professional to solid does.
The old code invented a value between 1m and 51m per club regardless of division, so a fifth-tier
side could outbid a top-flight one and the transfer market had no opinion about tiers at all.

### The backfill, and why it is a separate class

Fixing the generator fixes every world built from now on and none of the ones already in existence,
and the owner is looking at an existing world. So `BotLeagueStandardBackfill` runs on boot and puts the
300-odd clubs already there onto their standards. It is seeded from the club name, so a given club is
re-standardised to the same squad on every machine and every run, and it is idempotent — it runs on
every boot.

**It had to move onto both boot paths.** It went in beside the other backfills, which are all after
the "world already exists, nothing to create" early return — so on the owner's world, the exact world
that needed it, it never ran. The first restart proved it: no log line at all. The backfills are
convergence steps, not creation steps, and the one world guaranteed to need convergence is the one that
already exists.

**Human clubs are never touched.** `Team.humanControlled` is the gate, not the club name: Omladinac
and Sremac have hand-written skill rows for named players, and Omladinac is the manager's own team. A
backfill keyed on anything else would eventually re-roll the one squad in the game the owner has
actually watched. Verified on the live world — 9.96 and 6.38, unchanged.

### The bug I introduced and only found by looking at the database

The first version wrote skills with `setExact`, which fills only the `*_exact` double columns. The
legacy integer columns were left at whatever they were — and `getExact` prefers the exact value, so
every test passed, the boot log reported a healthy `{1=12.21, 2=11.09, 3=10.15, 4=9.1, 5=8.12}`, and
`getSkills()` returned 0 for all 4,620 players. `Skills` stores each skill twice; `setSkill` writes
both, `setExact` writes one. I found it by running the same query I had used to measure the "before"
and getting `0.00` for every tier — which is the argument for measuring against the database rather
than against the log.

`bothSkillColumnsAreWritten` now pins it: for every tier and every man, the stored column and the
exact value must agree, or the player is two different players depending on which method you ask.

### The test that passed while proving nothing

`BotLeagueStandardBackfillTest` first built two clubs, called the backfill and asserted they had moved.
They had not: the backfill runs in `REQUIRES_NEW` — the only reason it survives the boot transaction —
so anything an `@Transactional` test inserts is invisible to it, and three of the four tests were
passing **because** the backfill had correctly ignored rows it could not see. A test that passes
because the code under test did nothing is worse than no test.

Rewritten against the seeded world, which is the thing the owner is looking at anyway. Two of its reads
then died on `LazyInitializationException`, because the tests are deliberately not `@Transactional` (a
long-lived test transaction would be reading a stale world) and `Team.competition` is a lazy proxy.
The reads now own their session via `readInTransaction`.

The ordering assertion is the one that matters and it is the one that would have caught the original
state on its own: **every tier must be at least 0.8 stronger than the tier below it.** The old world
had tier 2 stronger than tier 1. A tier system where that is possible is worse than no tier system.

### Verified

- **766 tests green** (765 before, plus 10 `BotLeagueStandardTest` and 4 `BotLeagueStandardBackfillTest`)
- live boot: `re-standardised 4620 player(s) across 308 club(s). Average by tier: {1=12.12, 2=11.04,
  3=10.13, 4=9.14, 5=8.12}`
- Postgres, legacy and exact columns agreeing, 12.12 → 8.12 down the five divisions
- Omladinac 9.96 and Sremac 6.38, unchanged; the national sides and cup entrants untouched

## `a0d34f8` — the rating column finally means something

### A well-written piece of arithmetic that nothing called

`RatingEngine` has been in the codebase since 2026-09-28: standard Elo, a 400-point scale, K driven by
`MatchValue` for clubs and by `NationalStage` for countries, a qualification bonus, a tier offset for
club start ratings. Every comment in it explains a decision, and the decisions are all sound. It had
**zero callers**. Not a stub — nobody referenced it, so every country in the world was written at
`STARTING_RATING` by the catalogue seeder and stayed there forever, and the World page's rating column
was real data that could only ever read 1500.

Measuring first, as the standing rules ask: 48 countries, **1 distinct reputation value**.

### Replay, not increment

The obvious implementation is `rating += delta` in the matchday job. It is the wrong one here:

- **It cannot fix a world that has already played.** Postgres holds 24 played internationals, all scored
  with every country level. An incremental job leaves the column exactly as flat as it is now, and the
  only way to see a change would be to reset the world — which is the complaint, not a solution.
- **It needs an "already rated" flag**, and a flag is a place for a re-run, a restored backup or a
  replayed fixture to rate a match twice. Nothing catches the second application.
- **Drift has no floor.** Each write is a rounding, and a rounding is permanent, so over a season the
  column stops being a function of the results.

A replay from 1500 has none of those failure modes. It is a pure function of the match table, so it is
idempotent by construction, it repairs a bad write, and it answers identically on every machine. The
table is 24 rows today and a few thousand at worst, so the cost of re-reading it is nothing.

Order matters and is therefore pinned in the query: `ORDER BY matchDate, id`. Each result is scored
against what the two countries were rated *at the time*, so the same results fed in a different order
give different numbers, and the two matches of one matchday share a date and would otherwise come back
in whatever order the database felt like.

Senior and under-21 are kept in separate columns (`Country.seniorNationalTeam` /
`u21NationalTeam`, never a name match — matching on "Serbia U-21" is how a youth side ends up rated as a
senior one). A twenty-year-old's result is not evidence about the senior national team.

The K is `nationalK(INTERNATIONAL, OTHER)`. There is no World Cup or qualifying competition in the world
yet — one competition called "Internationals", no stage — so the stage is honestly `OTHER` rather than
the code claiming a knockout that has not happened. When those competitions land, only the stage changes.

### The bug, and it is the same one for the third time

First version joined the boot transaction, logged `replayed 24 international(s) over 48 side(s). Senior
range 1506.0–1494.0` — and the database still read 1500 for all 48 countries. The boot transaction is
long and does a great deal afterwards; a rating written inside somebody else's transaction is a rating
that may never have happened. `LeagueFixtureDayBackfill` carries a long comment about exactly this, and
`BotLeagueStandardBackfill` in the previous commit got it right by opening its own transaction. This one
did not, and the log said everything was fine.

`recomputeDurably()` now owns its transaction, and the boot calls that. The log and the table can no
longer disagree. There are deliberately two entry points: `recompute()` joins the caller's transaction
for a caller that commits anyway (the matchday job, and the tests), and `recomputeDurably()` is the
boot path. Same computation, and it is free to call both because the replay is a pure function.

### A test that passed while testing nothing, twice over

`theRatingColumnStopsBeingFlat` read the seeded world and asserted the column had moved. It failed,
because **H2's seeded world draws its international fixtures but does not play them** — the table is
empty there. The real bug report is about Postgres, which has 24 played internationals. Rewritten to
build its own history, and the second version of that still failed: the fixture saved countries without
a `seniorNationalTeam`, so the matches it built had null sides, the replay had nothing to rate, and the
service was correct. Two failures, both in the test, and the second one is the same trap as the
`REQUIRES_NEW` backfill test in the previous commit — with the twist that here the fix is the opposite:
`recompute()` joins the caller's transaction, so a `@Transactional` test *can* build a history it will
see.

The seeded world still has its own assertion (`theWorldIsLevelUntilSomethingIsPlayed`) so the flat
column cannot come back unnoticed without anyone noticing it is expected.

### The range is small, and that is the honest answer

1494 to 1506, because every country has played exactly one international and one result against a level
opponent is worth about six points. It would have been easy to pick a bigger K to make the column look
impressive on one fixture. That is the move that makes a rating system look like it works before it
does, and the number will spread as the calendar fills.

Bonus: Serbia had `reputation = 50` — a `TeamFactory` value on the 0-100 scale sitting in a 1500-scale
column. The replay overwrote it with a real figure, 1500, because Serbia drew its one match.

### Verified

- **776 tests green** (766 before, plus 10 `NationalRatingServiceTest`)
- live boot: `National Elo: replayed 24 international(s) over 48 side(s). Senior range 1506.0–1494.0`
- Postgres: 13 countries at 1506 (won), 13 at 1494 (lost), 22 at 1500 (did not play) — and the
  distribution is checked against the actual match results
- `GET /countries/world` → 48 countries, 3 distinct reputation values
- idempotence and the no-ratchet property both pinned: three consecutive replays leave the column
  byte-identical

## `dfa946d` — a country is active when it has football in it

### A flag that nothing read

`CountryState.ACTIVE` / `SIMULATED` was set once for Serbia by the catalogue seeder, styled one way on
the World page via `fm-world-row--active`, and read by **nothing that did any work**. The board even
noted "the World page already keys off it, so the page needs no change" — true, and the reason it was
worth building: the display was finished and the behaviour was absent. A button that flipped that flag
would have been a switch for a label, which is the exact shape of thing this codebase keeps finding.

So activation builds the football: 31 divisions, 310 clubs, 7,750 players, a table and a fixture list per
division. `GET /admin/countries` lists every country with its state and division count for the panel;
`POST /admin/countries/{iso}/activate` does the work, behind the `/admin/**` role guard that was already
there — it writes 7,750 player rows and is not something an authenticated user should do by accident.

### Reuses the season machinery rather than a second fixture writer

`SeasonService` already has `ensureSeasonCompetition`, `ensureEntriesForSeasonCompetition` and
`ensureDoubleRoundRobinSchedule`, and the calendar inside the last one is what decides a week and a day.
Writing fixtures here would have been a second opinion about it, which is precisely how four rounds once
ended up in one week. `PyramidBuilder` creates the divisions and the clubs and asks `SeasonService` for
the rest.

The calendar dates round one from the **current** game date, so a division created mid-season starts
playing this week instead of being born six weeks in the past and never being selected.

**Serbia is deliberately not migrated onto this.** `DatabaseInitializer` seeds it with real club names,
the Šid municipal league and two hand-authored squads, and every test in the suite runs through it. The
shape is the same; the content is not, and that is the reason they are two methods rather than one with
a flag.

### A bug the test was built to find, and did

The first version scheduled fixtures for the top flight only. So thirty of thirty-one divisions had
clubs and table rows and **no fixture list at all** — a league that never plays a match and looks
complete from the outside, which is the same shape of lie as an active country with no pyramid. The
assertion that caught it walks all thirty-one divisions and asks each one for its fixtures, because
"the pyramid exists" is true of a table and false of a football league.

Two name-collision bugs died before that one, and both came from the same root:

- `findByName` on `Team` **throws** on a duplicate rather than returning the first, and the repo says so
  in its own comment: two clubs may share a name, so anything resolving from a string must use
  `findAllByNameIgnoreCase`.
- Trimming "First Division A" and "First Division B" to "First" made two divisions of one tier generate
  the same ten club names. Division names carry the country for the same reason — two competitions in
  different countries are not allowed to share a name, and the lookup key is name plus ISO.

### The tests were order-dependent, which is a test bug with a nasty shape

Each method activated **Austria**. They share one H2 world, so whichever ran second found 31 divisions
already there, took the idempotent branch, and reported `clubs = 0` while reporting `divisions = 31` —
and the first test asserted `31 divisions` and `310 clubs` separately, so it read as a service bug
rather than a fixture bug. Every method now activates its own country (ARG, AUS, BEL, BIH, BRA, BUL, CAN),
which is also closer to what the panel actually does.

The reads are wrapped in a helper that owns its session, because `Team.country` and
`Competition.country` are lazy proxies and the tests are deliberately not `@Transactional` — activation
commits in its own transaction, so a long-lived test transaction would be asserting against a world that
had not been written yet.

### Verified live

`POST /admin/countries/CRO/activate` on the running app: 65 seconds, `{divisions: 31, clubs: 310,
alreadyBuilt: false}`. Then straight out of Postgres:

- 31 divisions / 310 clubs / 7,750 players / 310 table rows / 2,790 fixtures on two matchdays
- **0 of 31 divisions with an empty fixture list**
- tier 1 → 5 averages **12.19 / 11.13 / 10.08 / 9.15 / 8.11** — the gradient from the previous commit
  survives the activation, which is the point of having built it first
- `Croatia | ACTIVE`, Serbia unchanged

**Tests: 8 new, all green. Full suite: 791 green, exit 0.**

The first attempt at a full run was interrupted part-way through by something outside this session, and it
was deliberately not recorded as a pass. A test run is no different from a seeding job: the standing rule
is that a job is shown to have changed something rather than having logged that it did. The clean run came
after the presence task landed and covered both.

## `a5cdbc9` — the World page stopped implying everyone is at their desk

### A label doing work the number was not doing

The World page's stat read `humanUserRepository.countByRoleIsNotNull()` and rendered as **"Human
players"**. That is the number of accounts that have ever registered, unchanged since the day they did,
presented as though it described people. The board already knew: *"The World page shows registered
accounts, not people online. There is no session registry or last-seen column. Do not label the number
'online' until this exists."*

So there were two honest moves — build the thing, or stop implying it. This is the building half, and it
leaves **two numbers, each labelled for what it is**: `registeredPlayers` and `onlinePlayers`. The
legacy `users` key is gone rather than left as a third name for one of them.

### One hook, and it is the only place that could be one

`JwtAuthenticationFilter` is the single point every authenticated request passes through, so it is the
only honest place to answer "is anyone here". Stamping from a controller would have meant twenty
endpoints each remembering to do it, and the number would silently count whoever happened to be on a
particular page.

**Wall-clock, never the game clock.** Everything uses `LocalDateTime.now()`. The owner can advance the
season a week in one admin click, and a presence system driven by the game clock would report all 48
accounts as online the moment he moved it — a number that moves when a button is pressed is not a
measurement of anything.

**A JWT is not a session, and that is the whole problem.** A stateless token stays valid long after a
browser is closed, so "holds a valid token" and "is at the keyboard" are different questions. Presence
is a **wall-clock window** — five minutes since the last request — not a flag. An account stamped an
hour ago is offline, which is pinned by a test, because a presence system whose number can only grow is
not a presence system.

### Throttled, because the filter is on the hot path

The SPA polls the game clock, so one manager with the page open generates a request every few seconds,
and there is no way to add a filter hook that is not on the hot path of every request. Writing
`last_seen_at` per request would be a database write per request from a filter, for a column read once
per page view.

So the in-memory map is the live truth and the column is a durable shadow refreshed at most once a
minute per account. Two consequences, both intended: a burst of fifty requests writes once (pinned by a
test, and the account stays online throughout — a throttle that stopped *recording* presence would be
worse than one that stopped *writing* it), and a restart empties the map so the count honestly drops to
zero until people come back.

`isOnline` checks the map **and** the column. It did not at first, and the two answers disagreed: the
page would say three people are online while telling one of them they were offline — after every
restart, and for up to a minute after any account's first request.

### A test that passed while asserting the wrong thing

`writesAreThrottled` compared the post-burst column against the value from *before* the first request —
which is null — so it asserted "the column is non-null after fifty requests" and nothing about throttling
at
all. A throttle that never throttles passes that. It now marks once, reads the stamp, bursts fifty
times, and asserts the stamp is **unchanged**, which is the only version of this assertion that can fail.

### Verified live

`GET /countries/world` on the running app: `registeredPlayers: 2`, `onlinePlayers: 1`,
`onlineWindowMinutes: 5`, and the legacy `users` key gone. The one online account is the one making the
requests, and `app_user.last_seen_at` holds the matching timestamp — so the filter really is stamping, and
the number really is derived rather than hard-coded.

**Tests: 7 new, all green. Full suite: 791 green, exit 0** — one clean run covering this and the
activation task, which is what both entries now record.

The one failure the clean run found was in this task rather than the activation one. `rubbishInputIsIgnored`
asserted the global online count was zero and failed with `expected: <0> but was: <1>`, because the
integration tests in the same H2 world go through the same JWT filter and somebody else is legitimately
online. It was testing the world, not the blank input — now it asserts the count did not *move*.

## `8fc9876` — the promotion ladder covers every country, and we now know why it has never run

### The ladder was right; the country was a literal

`applyPromotionRelegationForLeague` computes a safe zone, a playoff band and a relegation band from the
division's size and how many divisions sit below it. Ten clubs with two below it is 6 safe, 7-8 playoff,
9-10 relegated — and the `PromotionRule` rows for Superliga say exactly that, which is a nice surprise:
the description and the implementation agree. The engine derives the bands from tiers and never reads
that table, so the table is a description, not a source. Worth knowing before somebody wires the engine
to read it, because wiring it up would be a second implementation of the same geometry.

The bug was `findSerbianLeagues()`, which reads a literal `"SRB"`, reached from three places: the ladder,
the season-two builder, and indirectly the `SeasonRolloverJob`. Croatia could be activated into a complete
31-division pyramid, play twelve weeks, and then have **no season two** — no table rows, no fixtures,
nothing for the day-3 and day-7 matchday jobs to select from. A country the owner switched on would go
silent a season later, and nothing in the log would say why.

### The unused parameter is what hid it

`applyPromotionRelegation(Competition superLiga, int seasonYear)` took a top flight and never referenced
it. A caller could pass Croatia's top flight and get Serbia's ladder, and the signature said nothing
about it. Same shape in `performPromotionRelegationAndNewSeason`. Both parameters are gone; the ladder
now loops over `allLeagueCompetitionsByCountry()` and says in its own log line how many country ladders
ran.

`openNewSeasonForEveryCountry(seasonYear)` is extracted so it takes the season number rather than reading
the clock. That is not style: it makes the new-season step callable — and testable — without moving the
whole world a season forward, which is the only reason the "does Croatia get a season two" assertion can
be written at all.

### Then the live run, which is where the real problem turned up

Advancing the clock to the end of the season produced: week 12 → 1, season 2, **and no rollover**. The
`job_run` table is unambiguous:

- 13 recorded job runs
- 13 of them at day 3
- **0 at day 7**
- `season-rollover` has never run, not once

`SeasonRolloverJob` fires at `week = WEEKS_PER_SEASON, day = 7`. `GameClockService.advanceWeek()` bumps
the week counter, adds one day of game time to the offset, and dispatches jobs **for the day the clock was
already sitting on**. It never advances the day. The week goes up, the day stays 3, and everything pinned
to day 5 or day 7 is skipped permanently. The day-7 league matchday has never fired either, which is the
same defect that has been refusing to let the week advance past "5 unplayed fixture(s) in your league".

**The promotion ladder therefore has never run in the running app, for any country.** What this commit
does is make it correct for every country and give it a test that would have caught the country bug. What
it does not do is make it happen — the trigger is unreachable, and the trigger is the day/hour engine's
business, not a promotion fix's. It is on the board under the day/hour table with the evidence.

### Two of my own tests were wrong about the world again

`PromotionLadderTest` was written against Croatia, which exists on the live Postgres world because I
activated it there — and not in H2, so four of five tests failed with "Croatia has no pyramid" while
blaming the fixture. The fix is `ensureCroatia()`: activate once, no-op after, called by every test, so
each is self-sufficient. This is the third order-dependence this session, after the activation tests and
the presence registry, and the shape is always the same — shared H2 world, a test that assumes it is
alone in it.

### Tests

**5 new, all green.** `PromotionLadderTest` walks all 31 of Croatia's divisions after a rollover and
asserts each has one table row per club and a non-empty fixture list, which is the assertion that failed
loudest when fixtures were scheduled for the top flight only. It also pins that opening the new season
twice adds no fixtures, that the bottom tier stays full (16 municipal divisions, nowhere to go), and that
an unactivated country acquires neither divisions nor a season from being in the catalogue.

Not claimed: a live rollover. It cannot be produced on the running app until the day/hour trigger is
fixed, and saying otherwise would be the shape of thing this log keeps refusing to write.

## `274d3ff` — a week is a week, and now a week takes as long as a week

### One method, and everything it cost

`GameClockService.advanceWeek()` did three things: bumped the week counter, added exactly one day of game
time, and dispatched the runner once — for the day the clock was *already sitting on*. The day never
changed. Every job pinned to day 5 or day 7 was therefore skipped permanently, and `job_run` recorded
thirteen runs, thirteen of them at day 3, and none at all at day 7.

Now it is `advanceHours(168)`. `advanceHour` already knew how to roll hour → day → week → season, and
`advanceDay` already composed from hours; `advanceWeek` was the one that had opted out, and opting out is
what made the season rollover and the day-7 matchday unreachable.

**The owner's requirement is still met.** They wanted the week counter to move, and the old comment said
composing the week from days made the end position depend on the hour the button was pressed. 168 hours
is exactly seven days, so it lands on the same day and hour of the next week whether it started at 00:00
or 23:00. That is pinned by pressing the button at four different hours and asserting the same week.

### `advanceDay` was paying twice, and the belt was already fastened

Writing the test surfaced it: `advanceDay` offered every remaining hour of the day to the runner in an
explicit loop and *then* called `advanceHours` over the same range. 48 runner calls for a 24-hour day.

The pre-loop was there so a job at 23:00 would be evaluated on the day it belonged to rather than the next
one. `advanceHour` dispatches **after** incrementing the hour and **before** rolling the day, so the step
from 22:00 to 23:00 offers (today, 23) and only the step past 23:00 moves the day. The pre-loop was belt
and braces on a belt that was already fastened, and it was doubling the cost of every day.

My first version of the test asserted 24 and failed with 48, and the honest reading of that failure is
"the method is wrong", not "the assertion is wrong". It is now `advanceHours(24)`, the same shape as the
week.

### Built directly rather than through Spring

The subject is which clock positions the runner is offered, and that is decided entirely inside
`GameClockService`. A full-context test would also run 168 hours of real matchday simulation across 31
divisions per country and would then be asserting on *that* — slow, and a failure would be ambiguous
between "the clock skipped a trigger" and "a job threw". So the test constructs the service with mocked
repositories and a runner that records and returns, and asserts on the offers.

### Live: the day counter moves, and the cost of that is the next problem

Days 1, 2 and 3 of week 12 have now been reached. `job_run` had never held a row at day 1 or day 2 — every
week in the world's history has the same single day-3 row. The clock is still grinding towards day 7 and
has not yet reached it, and the reason is the finding this commit is really about:

> `Recovery: 7408 player(s) recovered on season 2 week 12 day 1` — **42 minutes**

`RecoveryJob` walks **every player in the world** on every day it fires. The world is 716 clubs and 16,354
players, and with Croatia activated it is twice the size it was. So the clock is now honest and the button
is unusable, and the answer is not to put the clock back — it is that the jobs are priced for a village.

On the board as its own item, with three options rather than one guess: make the expensive jobs incremental
(recover the players who played since the last run), move the week's work off the request thread the way
simulate-all already does, and settle what "Advance Week" is supposed to mean to the owner — a calendar
step, or a week of football.

### Tests

**5 new, all green, in under three seconds** — which is the point of building the service directly. Each
one describes something that was true of the running app: 168 offers and not 1, every day 1-7 reached, the
season wrapping after week 12 with week-12-day-7 actually offered, and the end position independent of the
press hour.

## `9e3c5c3` — three World page bugs the owner reported

The owner sent three messages in a row, and none of them was a Java defect. That is worth noting before
the details: nothing here is reachable from a Spring test, and the third one is a bug that every
behavioural test in the suite would have passed.

### 1. World was missing from the mobile menu

The desktop bar is `desktop-only` and the mobile drawer is `mobile-only`. World was in the first and not
the second, so the page was reachable on a desktop and unreachable on a phone. Confirmed by reading the
drawer contents in the browser, not by eye: the accordion headers were Dashboard, Club, League, Serbia,
Community Chat, Admin, Sports Lobby, profile, sign out.

### 2. Clicking a country showed Serbia

The World page did set the context — `setActiveLeagueContext({ countryIsoCode: ... })` — and then
`loadCountryPage` asked `getCurrentUserCountryIsoCode()`, which is **the manager's own country**, and
never looked at the context. Two functions disagreed about what the click meant, and the one that was
consulted was the wrong one. Croatia, Japan and Brazil all rendered as Serbia.

The fix resolves the country as *an explicit choice, else the manager's own*. It is separate state from
the league context on purpose: if the World click reused the league context, then looking at Croatia
would follow you to the Country menu button, and there would be no way back to your own side except
reloading. So the World page sets the choice and the Country button clears it.

A represented country now says so — *"BRA is represented, not played. It has national sides, and no club
divisions. A country is given its own five-tier pyramid from Admin → Activate a country."* — because an
empty divisions table with no explanation is indistinguishable from a broken page.

**Getting that note onto the screen took three edits, not one.** The router passed the option, the view
had the code to render it, and the wrapper in between took no arguments and passed none:

```js
async function loadCountryPage() {          // options arrive here and die
    return countryView.loadCountryPage();
}
```

The option existed in three of the four places between the click and the screen. My first browser run
still showed the full Brazil page, and the reason was in that wrapper rather than in the view.

### 3. The back button worked and could not be reached

This is the one that made me stop and measure, because "does not work" is a claim and I would otherwise
have gone looking for a broken handler.

The click handler fires. The navigation happens. It works on a desktop. On a phone the page is **3,227px
tall** — 48 country rows — and after 1,200px of scroll the button measured:

```
top: -1095   reachable: false   scrollY: 1200
```

So the button was working perfectly, entirely out of the viewport, and tapping the top of the screen did
not hit it. From a manager's point of view: dead.

`position: sticky` is the obvious fix and it **silently does nothing in this app's shell**. Walking the
ancestors explains it:

```
.fm-page-toolbar   overflow visible
.fm-page--world    overflow visible
#main-content      overflow visible
.dashboard-content overflow visible
BODY               overflow hidden auto   scrollHeight 3227  clientHeight 3227   <- not a scroller
HTML               overflow hidden auto   scrollHeight 3227  clientHeight  844   <- the real scroller
```

`<body>` has `overflow: hidden auto` and `scrollHeight === clientHeight`, so it is a scroll container that
cannot scroll. A sticky element is confined to its nearest scroll container's scrollport, so it never
leaves `<body>` and never moves. Verified by setting `position: fixed` on the same element in the console
at the same scroll position:

| | button `top` at scroll 700 |
|---|---|
| `position: sticky` | **-587** |
| `position: fixed` | **0** |

So the bar is `fixed`, and it is **scoped to the World page** rather than to `.fm-page-toolbar` generally,
because a fixed bar on every page in the game is a change nobody asked for and would need its own review.
The bar carries `pointer-events: none` with its children re-enabled, so a bar across the top of the page
does not swallow taps meant for the content behind it. And the 48-row list is capped at `58vh` with its
own scroll, which takes the page from 3,227px to 1,553px — the real fix, because the exit control should
not need to be a fixed overlay at all on a page a screen and a half tall.

The first version of the bar faded to transparent at the bottom, and the page title and the competition
hint scrolled up *through* it and collided with the button text. Solid, with a shadow, and 78px of
clearance underneath.

### Tests

**4 new, all green.** They are assertions about text files, which is the only kind that reaches a menu
entry, a dropped argument and a CSS positioning decision. The third one is the argument for this whole
approach: a test asserting "the back button is on the page" passes throughout, on desktop and phone, and
proves nothing about whether a manager can tap it.

Verified in the browser rather than inferred: back button at `top: 10` and reachable at scroll 0, 400 and
1553, and it navigates. Brazil shows its notice, Croatia shows its 31 leagues, the Country button returns
to Serbia, and the mobile drawer lists World. Desktop measured `position: static`, `padding-top: 0` and no
list cap, so the change does not leak off the World page.

## `2a9cf8f` — a knockout tie can be settled from the spot

### The board was half right, and the half that was right was the important half

"Penalty shootouts | not started | Penalties are awarded but never taken." The first clause is stale:
`PenaltyEngine` is wired into the orchestrator, selects a taker, resolves it against a keeper and records
it. A penalty in open play is taken today.

The second clause is exactly right and the reason is a **shootout**, which nothing took. A cup tie that
finished level had no winner, and `CupFixtureSeeder.winnerOf` said so:

```
Cup tie 41 finished level at 1-1 with no shootout recorded; no winner taken.
```

and returned null. The club dropped out. The next round was drawn from a short list. **Every level tie
cost a knockout round a team**, so a competition could not get past its first rounds — and the warning
was a `log.warn` in a file nobody was reading, which is the quietest way for a competition to be broken.

### What it does now

`PenaltyShootout` takes the two squads and the scoreline. Five kicks each, then sudden death, and it
**stops the moment the tie is decided** — a side two up with kicks left cannot be caught, and a shootout
that keeps kicking and then awards the result to whoever happened to be ahead at the end of five is a
different and much worse rule. The side that loses the toss kicks first, and therefore kicks last, which
is the whole of what the coin is worth.

It is refused for a match that is not level. A shootout settles a tie; running one for a match somebody
won in normal time would replace a real result with a coin toss, and that is the failure mode a shootout
makes possible.

**The result is stored apart from the scoreline**, in `Match.homePenaltyGoals` / `awayPenaltyGoals`. A tie
that finished 1-1 and was won 4-3 from the spot is a **1-1 match**. Folding the kicks into the goal
columns would report it as 5-4 to the league table, to the replay and to the scoreline on the page, and
the goals would stop meaning goals. `null` there means "no shootout", which is a different fact from
"0-0 on penalties", and both are different from a league draw.

`winnerOf` now reads those columns, and warns only when a tie is genuinely undecidable. It does not coin
toss: a cup that invents a winner is worse than a cup that is one team short and says why.

Only `CUP` ties are settled. That is the entirety of this game's model of knockouts — `Competition` has
no format column, so a cup tie is a knockout and a league match is not. A group stage would need a real
flag, and pretending otherwise would settle league draws from the spot.

### The bias the shootout found, which is not about the shootout

The test's first question was the obvious one — is the toss actually a coin flip? — and it came back **the
same side every time**. That is not a test artefact, and the measurement is worth keeping:

| first draw from a freshly seeded `java.util.Random`, seeds 1..2000 | one way | the other |
|---|---|---|
| `nextBoolean()` | **2000** | 0 |
| `nextInt(2)` | **2000** | 0 |
| `nextDouble() < 0.5` | **2000** | 0 |
| `nextInt(65536) & 1` | 1001 | 999 |
| `nextLong() & 1` | 1018 | 982 |
| `nextInt() & 1` | 1000 | 1000 |

A **narrow** first draw takes the top bits of the freshly scrambled seed, and for a small seed those are
always the same. So any code that seeds a `Random` and immediately asks a yes/no question gets a
constant — and the shootout's toss was the engine's *first* draw after seeding, which is the worst possible
place for it. Had one side kicked first in every tie, the whole cup would have been decided by which
letter a fixture id started with.

The fix is a **wide draw and the low bit**: `(random.nextInt() & 1) == 0`.

**What this means for the rest of the engine is not established and is the next thing to look at.** Six
call sites seed it — `SimMatchService` and the four diagnostic/viewer launchers. Whether the engine's own
first decision after seeding is a narrow draw I have not checked, and it is worth checking, because a
constant coin flip inside a match engine is a considerably larger problem than a constant coin flip in a
cup. It is on the board.

### Two of my own mistakes, both caught by the tests

**The chance formula had a floor of 0.5**, which sounds like a safety margin and is a bug: it clamped
every weak pairing to exactly 0.5, so a poor taker and a poor taker became indistinguishable and an
average shootout converted **exactly 50%** of its kicks. Rewritten as a shift away from 0.76 — the figure
anyone watching a shootout would give you — with a floor of 0.55 and a ceiling of 0.95. The floor above
0.5 keeps the taker relevant; the ceiling below 1.0 keeps the keeper relevant.

**The kick-count assertion had it backwards.** I asserted "at least ten kicks", which is the same mistake
as a shootout that keeps kicking after the result: it assumes the full five rounds are always taken. An
early finish at eight is correct, and the test now says *at most* ten unless it went to sudden death, and
also checks that the two sides alternate.

### Tests

**11 new, all green.** They are about the rules rather than about "it produced a winner", because a
shootout with the wrong rules still produces a winner: it is refused for a level-only tie, it always
produces a winner and never a draw across 400 seeds, it reaches sudden death about a quarter of the time
and decides in ninety minutes the rest, it stops early, both toss orders occur, the keeper never takes,
the strongest men go first, the chance moves with both men and never reaches certainty, an average
shootout converts 62-85%, and a side with no squad gets no result rather than a coin toss.
