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

## `PENDING` — the zone model gets a writer, and `Zone` itself was wrong

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
