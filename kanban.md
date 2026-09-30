# 📋 kanban.md

**Derived from [`sprintBacklog.md`](sprintBacklog.md).** Everything still open that was in the
backlog is here, organised as tasks rather than as a sprint log. Where a task came from a numbered
sprint item (S1.4, S8.1, …) that is kept so the two files can be cross-checked.

**History lives in [`sprintProgress.md`](sprintProgress.md).** That is the running log of what was
attempted, what broke and why. This file holds no history — if something is here, it is not done.

Last updated **2026-09-30**.

**History lives in [`kanbanProgress.md`](kanbanProgress.md)** — one entry per task, each carrying the
commit that landed it. This file holds state; that one holds what happened, including the fixes that
were wrong the first time.

---

## How to read this

| Column | Meaning |
|---|---|
| **📋 Backlog** | Known work, not started or partially done. No active effort. |
| **🔄 In progress** | Being worked on right now. One at a time. |
| **👀 Review** | Built, needs the owner to look at it and confirm it is right. |
| **✅ Done** | Finished and verified. Kept briefly so the next session can see what not to redo. |

Nothing moves to ✅ on the strength of a passing test alone if the owner has not seen it running.
The one exception is work with a number attached — a count either meets its target or it does not.

---

## 🔄 In progress

*Nothing. The result-hiding task is done and verified live; the next session starts on the World
queue (bot league tier standards).*

---

## 👀 Review

The owner rebuilt and restarted these; they have not been looked at in the running app yet.

| Task | What to check | From |
|---|---|---|
| World page | 48 countries listed, all clickable, competitions shown disabled, human-player count populated | `b80b4a0` |
| World integrity in Admin | Country / side / squad counts on page load; the three repair buttons | `b80b4a0` |
| World in the header | Between Serbia and Community | static edit |

**Needs a restart, not a reload:** the season-number change and the menus below are code, not
static HTML, and `SeasonNumberBackfill` only runs on boot. The log must show
`Season numbers: rewrote N row(s)`. A boot without that line means the world still holds calendar
years and every fixture reader will find nothing.

---

## 📋 Backlog

### World, countries and internationals

| Task | State | Note |
|---|---|---|
| Verify the 48-country world in the running app | pending | 48 countries / 96 sides / 48 squads proven in tests and in the boot log, **not yet confirmed in the running app** |
| **Elo ratings** | **done** — see below. `RatingEngine` had zero callers; the column now replays from match history | — |
| **Online-user presence** | **done** — see below. Registered and online are now two numbers, each labelled for what it is | — |
| **Champions Cup / Masters Cup / Challenge Cup** | not started | International club competitions. Champions = winners, Masters = 2nd and 3rd, Challenge = 4th. Each needs a record, a draw and a per-tier league link. |
| **NT Qualifiers + World Cup (senior)** | mechanism only | `InternationalFixtureSeeder` draws senior sides already. Only the competition records and formats are missing. |
| **U-21 Qualifiers + U-21 World Cup** | not started | Separate competitions from the senior ones, with their own qualification phases — not tabs on one competition. |
| **Admin: activate a country** | **done** — see below. The flag existed and nothing read it; activation now builds the football | — |
| **Bot league tier standards** | **done** — see below. The note here was wrong: bot squads were *not* all skill 12, they were a uniform 1-17 draw | — |
| `Player.rating` = skill × 8 | **done, but not a conversion** — see below. Three writers, three scales, and the OVR formula read all three as one | — |

### The day/hour engine

| Task | State | Note |
|---|---|---|
| **Zone-based morale and daily recovery** | **the writer is wired, and `Zone` itself was wrong** — see the progress log. The `recovery` day-job's firing hour is a separate day/hour defect |
| **Simulate-all is week-based** | not started | Should be day- and hour-accurate, and must include cup ties. |
| **`advanceWeek` never changes the day** | **this is why the promotion ladder has never run — see below.** It bumps the week counter, adds one day of game time, and dispatches jobs for the day it was *already* on |
| **Cup ties in the schedule view** | not started | The schedule shows the week template, not the actual day's ties. All the data exists. |
| **Penalty shootouts** | **done** — see below. The board's note was half wrong: penalties *are* taken, by `PenaltyEngine`. What was missing was the **shootout** after a drawn tie | — |
| ~~A freshly seeded `Random` answers the first narrow draw with a constant~~ | **done** — fixed at the source in `SimulationRandom.seed()`, not at the call site. The engine seeds from `fixture.getId()` and its first narrow draw was constant across all 500 consecutive ids | — |

### Match engine realism

**Defer until last, and re-baseline before touching anything.** The numbers in these rows were
measured against code that has since changed, and `sprintProgress.md` records the owner decision that
mechanics come before statistics. `S1.0a` already warned the audit baseline was stale.

| Task | State | Sprint |
|---|---|---|
| **T1 engine defect** — a 1-1 with 38-4 shots and 9.5-0.9 xG. Needs a real event dump before it can be diagnosed | not started | — |
| **Goal mouth width 9 m → 7.4 m and `POST_RADIUS`** — the rest of S1.1 landed as `562785f` | not started | S1.1 |
| **Restart inversion, part 2** — goal kicks landed as `744dc71` (35.9 → 21.9). **Throw-ins are the open half: 75.5 against a real 35–45**, and they come from passes leaving the pitch sideways, not from clearances | not started | S1.2 |
| **Corner skew** — measure it again before acting. The 0.6 HOME / 4.4 AWAY split predates the day-stamping work; the current total is 6.4 | not started | S1.3 |
| **Duel count** — landed as `e4c3b1c` (373 → 268, real ~100). Whether to push further is a numbers decision, not a mechanics one | partly done | S1.4 |
| **Box fouls under-production** | deferred 2026-09-26 | S1.7c |


### Clubs, academy and transfer market

| Task | State | Sprint |
|---|---|---|
| **AI demand model and a live market** | partly built 2026-09-26 | S3.6 |
| **Transfer activity feed** | seeder only | — |
| **AI clubs train** | partly built 2026-09-27 | S4.7 |
| **Facilities** items 4–5 | backlogged | S4.4 |
| **Scouting** — the reports the network feeds | deferred by owner | S5.1 |
| **Academy structure** — 5 of 8 done | 3 outstanding | S5.3 |
| **Junior pathways** — 2 of 5 done | 3 outstanding | S5.4 |
| **Scouting produces intel, never signings** | not started | — |
| **Club can end a season with no youth pipeline** — shutting the school in week 12 dumps the intake | not started | — |
| **Intake is a purchase, so graduation collides with money** | not started | — |
| **Loan out** — wiring on a service that already exists | not started | S5.4 |
| ~~S3.5 work permits~~ | removed by owner | — |
| ~~S4.1 individual training focus~~ | removed by owner 2026-09-28 | — |

### Codebase and frontend debt

| Task | State | Sprint |
|---|---|---|
| **Make AI-vs-AI fixtures inspectable** | not started | S7.2 |
| **League table: three comparators, one implementation** | not started | S8.4 |
| **13 routed-but-unreachable pages** — **6 wired, 7 are not, and "wiring" was the wrong fix for most of them.** See the diagnosis below | partly done | S8.1 |
| **Remaining frontend debt** | not started | S8.3 |
| **Test fixtures still use calendar years as season values** — `WeeklyFinanceServiceTest`, `StaffSponsorServiceTest`, `PlayerContractServiceTest` and others pass 2024/2025/2026 into `seasonYear` and `expirySeason`. They are self-consistent so they pass under either scheme, which is the problem: **they do not pin the season semantics at all**. Left alone rather than rewritten blind | not started | — |
| **Presentation and realism content** | not started | S8.5 || **Freeze `demo/service/` as a reference module** | partial | S6.2 |
| **Documentation rewrite** | mostly done 2026-09-27 | S6.5 |
| **Deployment infrastructure** | deferred until the instance goes up | S8.6 |

#### S8.1 — the 13 routes are not 13 features, and 7 of them point at fake data

The backlog called this "navigation only". It is not, and the reason matters: **seven of the thirteen
fetch from `/demo/...`, which is `DummyDataController`** — fake data, with every route hardcoded to
team 1 (`/demo/cups/1`, `/demo/matches/teams/1/upcoming`, `/demo/events/teams/1`, …). The frontend
calls them with the logged-in manager's team id, so they 404 — and they `await response.json()`
without checking `ok`, so the throw escapes to the router and the page becomes a generic "API Error"
card. A menu entry on one of those is a fabricated table, or an error card, in the manager's
navigation.

| Route | Fetches | Real? | Now |
|---|---|---|---|
| `results` | `/teams/{id}/matches` | yes | **wired** — Club action row |
| `topScorers` · `playerStats` | `/stats/leagues/{id}/topscorers` | yes | **wired** — League action row. `playerStats` is a second name for the same screen, so it needs no entry of its own |
| `topAssists` · `teamStats` | `/stats/leagues/{id}/topassists` | yes | **wired**, same as above |
| `leagueMatches` | `/countries/leagues/{id}/matches` | yes | **wired** — League action row |
| `training` | — | works since `9dd11ef` | alias of `trainingSetup`; no entry needed |
| `upcoming` | `/demo/matches/teams/{id}/upcoming` | **fake** | open — `/teams/{id}/schedule` is the real equivalent |
| `friendlies` | `/demo/matches/teams/{id}/friendlies` | **fake** | open — `/api/season/friendlies/{id}/week` exists |
| `coaches` | `/demo/.../coaches` | **fake** | open — `/teams/{id}/coaches` exists |
| `events` | `/demo/events/teams/1` | **fake** | open — no equivalent found |
| `cup` | `/demo/cups/{id}` | **fake** | open — the real cup now exists and renders on the country page |
| `international` | `/demo/internationals/{id}` | **fake** | open — no equivalent found |
| `analytics` | redirects to `zox-match-preview.html` | — | open, and the backlog itself calls that page broken (S8.1 item 3) |

**Owner decision needed on the 7.** Repointing to the real endpoint turns each into a working page
and is a one-line change per page; deleting the route removes dead surface. The three with a real
equivalent (`upcoming`, `friendlies`, `coaches`) are cheap either way. `events`, `international` and
`analytics` have no equivalent and need a decision, not a line edit.

**Also done here:** `loadResults` and `loadTopScorersAndAssists` checked `response.ok` — a menu
entry pointed at a page that renders a generic error card is worse than no entry. A manager in no
league used to get a blank page with no way out, and now gets a sentence and the action row.


---

## ✅ Done

Kept so the next session does not redo them.

### `2a9cf8f` — a knockout tie can be settled from the spot

The board said "penalties are awarded but never taken, which also blocks cup progression: a level
knockout tie has no winner, so round 2 cannot be drawn". **The first half was stale** — `PenaltyEngine`
is fully wired and a penalty in open play is taken. The second half was exactly right, and the reason is
that nobody took a **shootout**: `CupFixtureSeeder.winnerOf` returned null for a level tie, logged *"no
shootout recorded"*, and dropped the club. So every level tie cost a knockout round a team.

Now:

- **`PenaltyShootout`** — five kicks each, then sudden death, stopping the moment the tie is decided
  rather than after ninety minutes of kicking. The side that loses the toss kicks first and therefore
  kicks last. A shootout is refused for a match that is not level, because running one would overwrite a
  real result with a coin toss.
- **The result goes in its own two columns** on the match, and the scoreline is untouched. A tie that
  finished 1-1 and was won 4-3 on penalties is a **1-1 match**; folding the kicks into the goals would
  report it as 5-4 to the table, the replay and the page.
- **`winnerOf`** reads those columns, and warns only when a tie is genuinely undecidable. It invents no
  winner.
- Only for a `CUP`. That is the whole of this game's knowledge of knockouts — `Competition` has no
  format column — so a league draw is never settled from the spot.

### The bias the shootout found, which is not about the shootout

The toss is a coin flip, so it is the first thing the test checked — and it came back **the same side,
every time**. Not a test artefact:

| first draw from a freshly seeded `java.util.Random`, seeds 1..2000 | one way | the other |
|---|---|---|
| `nextBoolean()` | **2000** | 0 |
| `nextInt(2)` | **2000** | 0 |
| `nextDouble() < 0.5` | **2000** | 0 |
| `nextInt(65536) & 1` | 1001 | 999 |
| `nextLong() & 1` | 1018 | 982 |
| `nextInt() & 1` | 1000 | 1000 |

A **narrow** first draw reads the top bits of the freshly scrambled seed, and for a small seed those are
always identical. So any code that seeds a fresh `Random` and then immediately asks a yes/no question
gets a constant. Six call sites seed the engine (`SimMatchService` and the diagnostic and viewer
launchers); whether the engine's own first decision is one of these is **not checked** and is the next
thing to look at, because a constant coin flip in a match engine is a much bigger problem than a constant
coin flip in a cup.

### `9e3c5c3` — three World page bugs the owner reported

All three verified in a real browser at iPhone 14 size and at 1440x900.

| Reported | Cause |
|---|---|
| World not in the mobile menu | The desktop bar is `desktop-only` and the drawer is `mobile-only`, so the entry simply did not exist on a phone |
| Clicking a country goes to Serbia | The World page set the league context; the country page then asked for **the manager's own country** and ignored it. Croatia, Japan and Brazil all rendered as Serbia |
| Back to dashboard does not work | It worked. On a phone the page was **3,227px tall** and after 1,200px of scroll the button measured `top: -1095` — entirely off-screen |

**The country is now resolved as "an explicit choice, else the manager's own."** Separate state from the
league context, because the Country menu button has to keep meaning *my country*: if the World click
reused the league context, looking at Croatia would follow you to the Country button with no way back. A
represented country now says so plainly, instead of showing an empty divisions table that reads as a
broken page.

**The back button is the one worth writing down.** The click handler fired and the navigation happened —
on a desktop. `position: sticky` is the obvious fix and it **silently does nothing in this shell**:
`<body>` carries `overflow: hidden auto` with `scrollHeight === clientHeight`, so it is a scroll container
that cannot scroll, and a sticky element is confined to its scrollport. Measured at scroll 700: sticky gave
`top: -587`, `fixed` gave `top: 0`. The bar is therefore `fixed`, **scoped to the World page** rather than
to every page, with `pointer-events: none` on the bar so it does not swallow taps, and the 48-row list is
capped so the page is 1,553px instead of 3,227px. Back button now measures `top: 10`, reachable at scroll
0, 400 and 1553, and navigates.

### `274d3ff` — a week is a week, and now a week takes as long as a week

`GameClockService.advanceWeek()` bumped the week counter, added exactly one day of game time, and
dispatched the job runner **once** — for the day the clock was already on. So the day never changed, and
every job pinned to day 5 or day 7 was skipped for ever.

It is now `advanceHours(168)`, the same stepping `advanceDay` already used and the one that cannot skip a
trigger. `advanceDay` lost a redundant pre-loop on the way: it offered every remaining hour to the runner
*and then* called `advanceHours` over the same range, so a day cost 48 runner calls for 24 hours. The
pre-loop existed to make sure a 23:00 job fired on its own day, and that worry was unfounded —
`advanceHour` dispatches after the hour increment and before the day rolls.

The owner's requirement still holds: **the week counter goes up by one, and the end position does not
depend on the hour the button was pressed**, because 168 hours lands on the same day and hour of the next
week whatever hour it started from. Pinned by a test that presses at 00:00, 05:00, 12:00 and 23:00.

**Live: days 1, 2 and 3 of a week are now reached for the first time.** `job_run` had never held a row
at day 1 or day 2. It is still grinding towards day 7, and that is the next item.

### The new blocker: a week now takes as long as a week

Making the clock honest exposed that the jobs behind it are priced for a village, not a world of **716
clubs and 16,354 players**. `RecoveryJob` alone logged:

> `Recovery: 7408 player(s) recovered on season 2 week 12 day 1` — **42 minutes**

And this is the same defect behind the other half of the board: the week advance kept refusing with *"Still
5 unplayed fixture(s) in your league"* because the day-3 matchday is now doing two matchdays' worth of
work across 31 divisions per country. So the fix is right and the button is unusable, and the honest
answer is not to put the clock back.

Three things are on the table and they are a decision, not a bug:

1. **Make the expensive jobs incremental.** `RecoveryJob` walks every player in the world on every day it
   fires. It should walk the players who *played* since the last run, or the day it has not processed.
2. **Move the week's work off the request thread.** There is already a background simulation runner; the
   week advance could hand the world over to it and return, the way simulate-all does.
3. **Decide what "Advance Week" means to the owner** — a calendar step, or a week of football. Right now
   it is the second, and it takes hours.

### `8fc9876` — the promotion ladder now covers every country, and we know why it has never run

The ladder itself was **fine**. `applyPromotionRelegationForLeague` computes a safe zone, a playoff band
and a relegation band from the division's size and the number of divisions below it, and for a ten-club
division with two below it that is 6 safe, 7-8 playoff, 9-10 relegated — exactly what the
`PromotionRule` rows describe. The `PromotionRule` table is a faithful description of the ladder; the
engine derives the bands from tiers and does not read that table, which is worth knowing before anyone
wires it up as if it were the source.

**The country was hard-coded in three places**, all of them now fixed:

| Was | Now |
|---|---|
| `findSerbianLeagues()` — the country read as a literal `"SRB"` inside the ladder | `allLeagueCompetitionsByCountry()`, every country grouped into its own ladder |
| `performPromotionRelegationAndNewSeason` built season two for `findSerbianLeagues()` only | `openNewSeasonForEveryCountry(seasonYear)` — extracted so it takes the season number and is callable without moving the world a season forward |
| `applyPromotionRelegation(Competition superLiga, …)` accepted a top flight and **never used it** | the parameter is gone; the method says which countries it is working on |

That unused `superLiga` parameter is what hid the bug. A caller could hand in Croatia's top flight and
get Serbia's ladder, and nothing in the signature said so. The season rollover had the same shape: a
country the owner activated got a full 31-division pyramid, played one season, and then had **no season
two** — no table rows, no fixtures, nothing for the matchday jobs to select. Everything activation built
correctly was dropped on the floor twelve weeks later.

### The bigger finding: the rollover job can never fire

Proving this live meant advancing the clock to the end of the season, and the clock went 12 → 1 with
season 2 and **no rollover at all**. `job_run` says why:

| | |
|---|---|
| Job runs recorded | **13** |
| ...at day 3 | **13** |
| ...at day 7 | **0** |
| `season-rollover` runs, ever | **0** |

`SeasonRolloverJob` fires at `week = WEEKS_PER_SEASON, day = 7`. `GameClockService.advanceWeek()` bumps
the week counter, adds exactly one day of game time, and dispatches jobs **for the day the clock was
already on** — it never advances the day. So the week goes up, the day stays 3, and every job pinned to
day 5 or 7 is skipped forever. The league matchday jobs are on day 3 and day 7; the day-7 half has never
run either, which is the other half of the "5 unplayed fixtures" the week advance kept refusing to move
past.

**So the promotion ladder has never executed in the running app, for any country — this fix makes it
correct but does not make it happen.** Turning `advanceWeek` into a real seven-day step is a day/hour
engine change with a calendar decision attached (what happens to a day whose jobs have not fired), so it
is written up above as its own task rather than quietly done inside a promotion fix.

### `a5cdbc9` — the World page stopped implying 48 people are at their desks

The "Human players" stat was `countByRoleIsNotNull()` — **registered accounts**, every one of them
counted since the day it registered. The board's note was the instruction: *"Do not label the number
'online' until this exists."* It did not exist, so the honest choices were to build it or to stop
implying it. This is the building half, and the number is now two numbers:

| | Before | After |
|---|---|---|
| World page stat | "Human players" — 2 | **Registered players** — 2 |
| | *(no such number)* | **Online now** — 1, with the window stated: *a request in the last 5 minutes* |

`User.lastSeenAt`, stamped on the one moment every authenticated request passes through — the JWT filter.
**Wall-clock time, never the game clock:** the owner can advance the game a week in one admin click, and
a presence system driven by it would report every account in the world as online the moment he moved it.

**The writes are throttled to once a minute per account.** The SPA polls the clock, so one manager with
the page open is a request every few seconds, and the filter is on the hot path of all of them. Writing
`last_seen_at` per request would be a database write per request for a column read once per page view.
The in-memory map is the live truth; the column is a durable shadow of it, and a restart empties the map
and honestly drops the count to zero until people come back.

**7 new tests green. Full suite: 791 green, exit 0** — a clean run covering this and `dfa946d` together.

### `dfa946d` — a country is active when it has football in it

`CountryState` was set once for Serbia by the catalogue seeder, styled one way on the World page, and
read by nothing that did any work. So the activation panel the owner asked for would have been a switch
for a label. It now builds the football.

`POST /admin/countries/{iso}/activate`, behind the existing `/admin/**` role guard — it writes 7,750
player rows, which is not something any authenticated user should do to the world by accident.

**Live: Croatia, from nothing to a league system in 65 seconds.**

| | Croatia after activation |
|---|---|
| Divisions | **31** (1 + 2 + 4 + 8 + 16) |
| Clubs | **310**, ten per division |
| Players | **7,750**, twenty-five per club, at least two keepers |
| Table rows | **310** — one per club |
| Fixtures | **2,790**, on two matchdays, and **0 of 31 divisions with an empty fixture list** |
| Strength | tier 1 **12.19** → tier 5 **8.11** |

Serbia is untouched, because activation asks the database which divisions a country has rather than
trusting the flag. A country can hold a pyramid and still read SIMULATED if a previous activation was
interrupted, and the second call heals exactly that.

**The flag is written last, always.** It is a claim that the rest of the transaction succeeded. Written
first, an activation that died halfway through 7,750 rows would leave a country marked ACTIVE over a
third of a pyramid, and every reader would believe it was playable.

**8 new tests green. Full suite: 791 green, exit 0** — the same clean run, recorded in both entries. The
first attempt at it was interrupted part-way through and was **not** counted: a test run is no different
from a seeding job, and the standing rule is that a job is shown to have changed something rather than
having logged that it did.

### `a0d34f8` — the rating column finally means something

`RatingEngine` had existed since 2026-09-28 with **no caller at all** — not a stub, not a disabled
path, nothing. Every country was written at 1500 by the catalogue seeder and nothing could ever move
them, so the World page's rating column was real data that could only read 1500.

| | Before | After |
|---|---|---|
| Distinct values across 48 countries | **1** | **3** (1506 / 1500 / 1494) |
| `GET /countries/world` reputation column | flat | 1506 Australia, China, England… / 1494 United States, Uruguay… |

**It replays the match history instead of incrementing on each result.** The obvious implementation is
`rating += delta` in the matchday job, and it is the wrong one here for three reasons. It cannot fix a
world that has already played — 24 internationals are on the database now, all scored with every country
level, so an incremental job leaves the column flat and the owner has to reset to see anything. It needs
an "already rated" flag, and a flag is somewhere for a re-run or a restored backup to rate a match twice.
And drift has no floor, because every write is a rounding and a rounding is permanent. A replay from
1500 has none of those failure modes: it is a pure function of the match table, so it is idempotent by
construction, it repairs a bad write, and it gives the same answer on every machine.

Senior and under-21 are rated separately, because a twenty-year-old's result is not evidence about the
senior national team. Pooling them would let a youth tournament move a country's senior standing.

**The range is deliberately narrow right now** — 1494 to 1506 — and that is honest rather than broken.
Each country has played exactly one international, and one result against a level opponent is worth
about six points. The column spreads as the calendar fills; it is not supposed to be dramatic after one
fixture.

Bonus: Serbia had `reputation = 50`, a `TeamFactory` value on the 0-100 scale sitting in a 1500-scale
column. The replay overwrote it with a real figure. It is now 1500 because Serbia drew its one match.

### `26a000c` — the pyramid has a gradient

Measured on the live database before this task, average of the eight skills per player:

| Tier | Clubs | Average skill | Min | Max |
|---|---|---|---|---|
| 1 | 9 | **8.61** | 3.6 | 13.9 |
| 2 | 20 | **8.71** | 4.6 | 12.5 |
| 3 | 40 | **8.57** | 4.1 | 12.8 |
| 4 | 80 | **8.56** | 3.5 | 13.4 |
| 5 | 159 | **8.57** | 2.9 | 14.0 |

Five divisions inside a 0.15 band, and **tier 2 was the strongest in the country**. The note in this
table said "bot squads are all skill 12" — they were not 12, and they were not uniform either: the
generator drew every skill uniformly from 1-17 with no reference to the division, so every tier got the
same average *and* the same 2.9-to-14.0 spread. A flat pyramid means promotion and relegation decide a
table on reputation and tiebreaks rather than on football.

After, straight out of Postgres on the same world:

| Tier | Clubs | Legacy column | Exact column |
|---|---|---|---|
| 1 | 9 | **12.12** | 12.12 |
| 2 | 20 | **11.04** | 11.04 |
| 3 | 40 | **10.13** | 10.13 |
| 4 | 80 | **9.14** | 9.14 |
| 5 | 159 | **8.12** | 8.12 |

Tiers 4 and 5 continue the step to 9 and 8. Leaving them where the old draw put them would have made a
fifth-tier side a third-tier side, which is the same flattening one row lower. 4,620 players across 308
clubs re-standardised on boot; Omladinac (9.96) and Sremac (6.38) untouched, because
`Team.humanControlled` is the gate and one of them is the manager's own hand-written squad.

**Three things the number on its own would not have told you:**

- **A tier is a player average and the position redistributes within it.** A tier-1 keeper is 15 at
  goalkeeping and 9 at playmaker, and both are a 12 player — the bonus is paid for out of the attribute
  he will never use. The old code gave all eight skills the same value, so every player in the world
  was equally competent at everything and the engine had no reason to prefer a real keeper.
- **A squad needs a spine.** Twenty-five men all exactly on the tier number is a squad with no
  goalkeeper and no substitute, so each man draws a depth offset of −2…+2, weighted so roughly a
  quarter sit above their own standard.
- **Value and wage now follow the tier** (exponentially, the way wages actually are). The old code
  invented a value between 1m and 51m for every club in the world, so a fifth-tier side could outbid a
  top-flight one and the transfer market had no opinion about divisions.

Squads are 25 (was 15) and have three keepers, because a season of injuries and five substitutions
needs names to spend them on.

### `61c2a51` — the manager does not see their own result until they ask for it

The matchday job fires at 19:00 whether or not anyone is watching, so a manager's result exists the
moment the job finishes. `SimMatchService` marked every result revealed, which made **Watch your
match** a formality and handed the owner the season before he had decided to look at it. The dashboard
had reveal UI; the club schedule and the league results had none, so the same match showed a score in
one place and nothing in another.

| Surface | Was | Now |
|---|---|---|
| Dashboard recent | "Open report" / "Watch match", revealed through its own copy of the helper | **Show results** → details on Goals, **Watch your match** → viewer |
| Club schedule | the score, in full | no score, no W/D/L chip, no click-through; both buttons |
| League results | the score, in full | no score; both buttons |
| `GET /matches/{id}` | viewer ignored, so a direct fetch read the result | viewer-aware |

One implementation (`reveal-ui.js`) now serves all three, and the mask lives in `MatchDTO` rather
than in each renderer — a fourth screen was written during this task and read the real score out of
the DTO while rendering nothing. `Match.homeResultRevealed` / `awayResultRevealed` stay per-side, so
each manager is masked on his own flag.

**The calendar is its own fact from the date.** A played `Match` carried only a wall-clock date, so
"which day of the season was that?" had no answer once the fixture was gone: `Match.dayNumber` is new,
copied from the fixture, and `MatchDTO.seasonDayLabel` reads `Season 1 · Day 7 · 23:59` next to the
real time. Unknowns are left out rather than printed as `Day 0`. The next-match card now sorts by
season → week → day instead of by wall clock, which is the order the fixtures were generated in and
the order "next" means to a manager. Competition type (`League` / `Cup` / `International`) is on the
match header and on every fixture card.

**Verified live** (team 1, week 2, new code): a simulated match came back `homeGoals: null`,
`resultHidden: true`, `dayNumber: 7`, `seasonDayLabel: "Season 1 · Day 7 · 23:59"`, `replayId: 14`;
`POST /matches/234/reveal` flipped it to `0-0`, `resultHidden: false`. A match persisted before the
change degrades to `Season 1 · 23:59` and a visible score, which is the intended fallback. 752 tests
green (9 new DTO, 3 new persistence).

**Found while testing, not fixed here:** `POST /admin/reset-db` dies on a Postgres deadlock
(`AccessExclusiveLock`) when the background league simulation is still running. The world survived
intact. It is a reset-vs-simulation contention problem of its own, and fixing it here would have
meant changing the reset path during a feature that does not touch it.

### `9dd11ef` — seasons, menus and the training setup screen

| Done | Verified by |
|---|---|
| **A season is a number counted from 1** — `BASE_SEASON_YEAR` (2025) deleted and all twelve `BASE_SEASON_YEAR + (season - 1)` sites removed. The season is twelve weeks, so four run in a year and no calendar year can name one | 722 tests green |
| **The cup page reads the season the world is in** — it asked for a literal `1` while the seeder wrote the world's own season. This was the "0 ties across 8 rounds" against a log saying 54 drawn: two artefacts, two hardcoded answers | The database, not the log: 2 868 fixtures at `season_year = 2025` |
| **`SeasonNumberBackfill`** — rewrites `season_year >= 1000` to 1 across the nine football tables, own transaction, idempotent | runs on boot; **not yet run against a live world** |
| **The mobile menu could not be opened at all** — the scrim carries `mobile-only`, and the responsive utility force-showed every `.mobile-only` element, so a full-viewport div at z-index 1190 sat over the top bar and ate every tap | real mouse clicks at 390×844: click the hamburger, drawer opens, all 11 entries hit-testable |
| **The desktop Club sidebar was never rendered** — `.sidebar` is fixed at `left: -260px` and nothing ever applied `.active` to it, so all twelve Club entries were invisible. Removed, with bindings that also fired `loadPage` twice per click | `left: -260px`, `right: -9px`, outside the viewport, measured |
| **The Training Setup screen had a state layer and no renderer** — the router's `trainingSetup` case called a function that was the setup screen wearing the wrong name, and its `render()` belonged to the reports screen in another scope. It is written now, against the CSS that survived and the backend's own `normalizeDtSkill` | the real module with a stubbed API: renders, saves the right body, runs the week, honours the 10-slot cap |
| **Training was in neither visible navigation** — `buildClubActionsHtml` never had it, and the sidebar that did was off-screen | both entry points verified; no horizontal overflow at 390px |
| **`loadHomeTeamStats` null-dereferenced** — it wrote into the dashboard after awaiting the league table, so a navigation mid-flight made every write fail. Nodes are resolved before the fetch now | the reported console error |

### ✅ The startup failure, and the two boot-ordering bugs under it

`NonUniqueResultException: Query did not return a unique result: 3 results were returned` on
`GET /countries/leagues/1/table` and `GET /teams/1/schedule`. Three defects, each hiding the next.

**1. `season_competition` had no unique constraint**, and `findByCompetitionAndSeasonYear` returns an
`Optional` — so two rows for one league and season did not degrade, they threw, and the league table
and the club schedule both went down. The duplicates were not ancient: rewriting 2025 → 1 means
anything that creates a season row *between* those two moments asks for season 1, finds nothing, and
creates a row beside the one it should have reused. One league had three, each with its own ten table
entries. Collapse, then the constraint.

**2. The constraint check I first wrote made the app unbootable.** It caught the "already exists"
error — which does not work: a failed statement inside a transaction marks it rollback-only whether or
not you catch it, so the catch swallowed the error and the commit threw `UnexpectedRollbackException`.
It is the same trap the world catalogue fell into. It now looks in `INFORMATION_SCHEMA` first and only
then alters, and nothing fails.

**3. The backfills were in the wrong branch.** All three sat inside the *"baseline already exists"*
early-return, so a fresh Reset + Initialize — which goes through the pyramid and the world build below
— never ran them. Proof from a clean world: **4,650 club players with no rating at all**, while the
2,400 national-squad players were fine, because the seeder that creates them writes a rating. They now
sit next to the integrity repair, which is the one thing that runs whichever door the world came in by.

| Verified on a real Reset + Initialize | |
|---|---|
| `World integrity OK` | `healthy=true` — 48 countries, 96 sides, 48 squads, 310 clubs, no legacy rows |
| `PlayerRatingBackfill` | `recomputed 4750 of 7150` — and the spread it reports is `{0=4750}`, so the 4,750 are exactly the unrated ones |
| Player ratings afterwards | 7,200 derived, range 9–97, **none at 0** |
| `GET /countries/leagues/1/table` | 200 |
| `GET /teams/1/schedule` | 200 |
| `GET /countries/SRB/cup` | 200, and **54 ties in round 1** — the bracket the owner saw as "0 ties across 8 rounds" |
| Duplicate (competition, season) rows | 0, with the constraint in place |

`SidebarAccordionOpensTest` re-pointed at the mobile drawer. It is a live Playwright test that logs in
as the owner, because a collapsed panel and a dead panel look identical from outside and only a real
click can tell them apart. It walked the `.accordion-content` panels rather than the headers: most
headers in the drawer are leaf nav buttons wearing the class, and their `nextElementSibling` is null.
Its leftover `PROBE` printlns are gone.

**738 tests green, 0 startup errors.**

### ✅ `Player.rating` — one meaning, derived from skills

The board said *"a skill-12 bot reads as rating 96, conversion needs calibrating"*. Rescaling would
have treated the symptom. The column had **three writers on three scales**, and `PlayerDTO.calculateOverall`
read it three times as if it were one number:

| Writer | Wrote | When |
|---|---|---|
| `BotSquadGenerator` | `BASE_SKILL * 8` = **96** | once, at seeding |
| `YouthAcademyService` | **50**, hardcoded | per graduate |
| `SimMatchService.bumpCareerStats` | **the last match's rating** | every match, overwriting |

**Verified in the database:** 2 350 players at exactly 96, 5 250 at 0, none in the match range. The
formula only applied its bonus when `rating > 0`, so two players of identical ability sat about six
OVR points apart — decided by which seeder created the row. And `form` was already carrying "how he
has been playing lately", written weekly by `MoraleService` and already in the formula.

- `Player.careerRating()` is now the one definition: a 1-100 career rating from
  `Skills.getRatingScore(position)`, the same function the OVR formula normalises, so the two cannot
  drift. All three writers use it, and the match engine no longer writes it at all — the per-match
  rating lives on `MatchPlayerStats`, where it already was.
- The unbounded `(rating - 62) / 5.5` term is bounded to ±1.5, and the defender and keeper role terms
  that read the same column twice more no longer do. Even a rating of 1000 now moves OVR by ≤2.
- The per-position maxima were duplicated between `Player` and `PlayerDTO`, and **four of the five
  agreed on a maximum skill of 17 while the defender's was hand-tweaked to 93.6 where 17 gives 98.6**.
  One definition now; a defender's OVR drops very slightly as a result.
- `PlayerRatingBackfill` recomputes the column on boot, own transaction, and only writes rows that do
  not already match — so a settled world is left alone and a re-boot does no work.

| Verified by | |
|---|---|
| `ratingRisesWithAbility` | at every position — this caught the first attempt, which clamped every keeper to 100 because I assumed a max skill of 4.5 |
| `ratingIsBounded` | a player with no skills is 1, not 0 — 0 is what the OVR formula reads as "never rated" |
| `identicalAbilityGivesIdenticalOverall` | computes the old +6.2 term explicitly, because the fixed formula can no longer demonstrate its own defect |
| `theRatingTermIsBounded` | rating 1000 and rating 1 both move OVR by ≤2 |
| `theBackfillConverges` / `theBackfillIsSafeToRunTwice` | only stale rows move, and a second run is a no-op |

**Two existing tests encoded the old behaviour and were corrected, not worked around.**
`SimMatchPersistWiringTest` asserted that the match rating (80) lands on the player — that *was* the
bug. My first correction asserted `assertNotEquals(80, …)`, which is unsound: that test player's
derived rating also happens to be 80, so it would have passed or failed by coincidence. The assertion
that is actually sound is that persist leaves the player's rating untouched.

**Not verified against the running app** — it is down, and every OVR in the game shifts once. The
boot log will say how many rows moved and what the old values were.

### ✅ S8.4 — one league-table order, and a table that survives being read

Two defects. The second is worse than the board described, and both are now closed.

**One order, not three — and it was four.** `LeagueTableOrder` is the only comparator now, used by
`CountryController.getLeagueTable`, `MatchPersistenceService`, `SeasonService.sortTable` and the
playoff pairing. They agreed on points and goal difference and disagreed after that:

- The playoff draw stopped at goal difference. Two runners-up level on points and difference were
  ordered arbitrarily there while the table ordered them by goals scored — the exact
  stronger-club-gets-the-easier-tie bug the playoff code's own comment says was already fixed once.
- Only one of the four had a final tiebreak on team id, so a total tie was ordered by whatever order
  the repository returned and the same table could render differently between two requests.
- One of the four **writes** `position` and another **reads** it, so the number in the teams list
  and the number in the table came from two different comparators.
- Two of the four did `getGoalsScored() - getGoalsConceded()`, which unboxes. One was the endpoint
  the manager reads, so a half-created entry threw instead of sorting.

**The read path was deleting the season.** `ensureEntriesForSeasonCompetition` deleted every entry
and rebuilt from zero on any membership drift — and the league table endpoint calls it *before it
reads*. One club joining a division reset every other club's points, wins, draws, losses and goals,
mid-season, for a manager who had done nothing but open the page. The difference is now applied as a
difference: a new club gets a row at zero, a departed club loses its row, and every other row keeps
its record.

| Verified by | |
|---|---|
| `theOrderIsPointsThenGoalDifferenceThenGoalsScoredThenId` | every one of the four keys is load-bearing — a comparator that dropped goals scored would still pass the first three positions |
| `aTotalTieIsBrokenStablyById` | the same table renders the same order whichever way the rows arrived |
| `aNullSortsAsZero` | the two unboxing comparators threw here; one of them was the page the manager reads |
| `aNewClubDoesNotWipeTheTable` | **proved against the old code**: restoring delete-and-rebuild gives `expected: <21> but was: <0>` |
| `aDepartedClubLosesOnlyItsOwnRow` | the one case where losing a row is right, and nothing else moves |

**732 tests green.** The kanban's file references were stale — `MatchStatisticEngine` does not exist.

### ✅ S7.1 — the guard that every fixture is played by the proposal engine

`ProposalEngineIsTheOnlyFixtureProducerTest`, 5 tests. The whole task was 1-2 days and is now done;
it exists because the audit that produced it was **retracted** — it claimed AI-vs-AI league matches
came from a Poisson dice roll, and that was wrong. The real paths already ran the proposal engine.

So this is not a test that the engine works. It is a test that **nothing has grown beside it**.

| Test | What it locks |
|---|---|
| `onlyOneSiteConstructsAMatch` | exactly one production site builds a football `Match`, and it is `SimMatchService`. The other `matchRepository.save` calls are named and allowed, with the reason: a fixture is *played* when its Match row is born, and the rest update a row that already exists |
| `onlyTheTwoKnownEntryPointsSimulate` | `simMatchService.simulate` is entered from `SimulationController` and `AsyncSimulationRunner` and nowhere else |
| `noDiceRollEntryPointExists` | the retracted `simulateQuickScore` stays gone, by name |
| `aRealEngineRunReachesTheDatabase` | a real run persists with a full 90+ minutes, 22 player rows, possession summing to 100, a replay, and a played fixture pointing at the match that played it. The half the retracted audit never established |
| `theSameFixtureProducesTheSameMatch` | a fixture is seeded by its own id, so it cannot be re-rolled — which is what makes the engine's output worth trusting for a league table |

Both structural scans strip comments first, because the retracted audit counted a mention. American
football and basketball are outside the scan on purpose: they have their own engines, and a scan
that included them would report their code as a violation.

Also fixed: `SimMatchPersistWiringTest` was building fixtures with `seasonYear = 2026`, the
calendar-year scheme this project no longer uses.

**727 tests green, 5:24.** The class costs about 110 s because it runs the engine three times. Worth
it for the one guard the backlog asked to exist.

### ✅ S8.1: the league area gets a navigation, and the reachable 6 of the 13 get menu entries

| Done | Verified by |
|---|---|
| **The league area had no navigation of its own** — it was reached from the top bar and then had nowhere to go, so league matches, the top scorers and the top assists were routes with no way in. `buildLeagueActionsHtml` now gives it Table / Schedule / Matches / Top Scorers / Top Assists | rendered at 1280 and 390: correct routes, current page highlighted, no horizontal overflow |
| **`results` wired** — it renders the Club action row already, so it belonged in it | 13 club buttons, including Results |
| **`topScorers` and `topAssists` wired** — `playerStats` and `teamStats` are second names for the same two screens, so they need no entries of their own | route names confirmed against the router |
| **The 7 that fetch `/demo/...` were left unwired on purpose** — `DummyDataController` is fake data hardcoded to team 1, so a menu entry would be a fabricated table or an error card | endpoint table below |
| **`loadResults` and `loadTopScorersAndAssists` now check `response.ok`** | both render a real message in the SPA shell, with the action row, instead of throwing to the router |
| **A manager in no league got a blank page** — `ensureCurrentLeagueId()` returning null did `return` with the page untouched | now a sentence and a way onward |

### Earlier

| Done | Verified by |
|---|---|
| **Legacy 9-country list removed** — the hard-coded list that created `HRV`/`DEU`/`GBR` on every reset, which is why the world kept showing 51 countries | 48 countries, no legacy rows |
| **Catalogue runs on the bootstrap path** — without it a fresh install built a pyramid in a world with no countries | 45 test errors → 0 |
| **`WorldIntegrityService`** — reports and repairs; runs after every boot step; `GET /admin/world-integrity`, `POST …/repair` | 48 countries / 96 sides / 48 squads / no legacy rows |
| **Boot seeding is no longer one transaction** — the catalogue and national sides run in `REQUIRES_NEW`, so a failure cannot mark the boot rollback-only and throw away the world | commit no longer throws `UnexpectedRollbackException` |
| **Initialize DB builds the whole world** — it called the pyramid step directly, so Reset + Initialize left 1 country and 0 national teams | now goes through the boot entry point |
| **Every country starts at 1500** | `STARTING_RATING` |
| **Scouting band moved to 1400/200** — the old 40/60 band clamped every country to zero reach on a 1500-based world | `ScoutingReachTest` |
| **National teams, cup, coaching, and page routing** | 722 tests green |
| **Injuries, fatigue persistence, weekly recovery, substitutions, conditional substitutions, penalty kicks, penalty rate, replay persistence** — all landed under the S1 sprint | see `sprintProgress.md` |

---

## 🐛 Bugs found while working, not yet fixed

Kept out of the task tables on purpose — none of them is a feature, and each one was found by
touching the app rather than by reading it.

| Bug | What happens | Why it is not fixed yet |
|---|---|---|
| **`POST /admin/reset-db` deadlocks** | Reset while the background league simulation is still running fails with `ERROR: deadlock detected / Process waits for AccessExclusiveLock`. The job reports `status: failed`. The world survived intact — no data loss — but the reset does not happen. | Found during `61c2a51`. It is a reset-vs-simulation contention problem, and fixing it means changing the reset path, which that task did not touch. Worth looking at together with the simulate-all scheduling below, because both are "two writers, one database". |

**Open question for that session:** the reset should either wait for a running simulation, or the
simulation should be cancellable and waited on. Neither is obvious from the outside, and the two
`AdvanceWeekAsyncService` / `RoundSimulationAsyncService` paths are the reason it happens at all.

---

## 🔴 Standing rules

**Never hand back a half-built world.** Every seeding path must end in a world that passes the
integrity check. Two sessions were lost to this: one where a caught exception rolled the whole boot
back, one where Initialize DB never ran the world seeders at all. `ensureBaselineDataOnStartup()` is
the single entry point — anything that builds the world goes through it.

**A successful job must be shown to have changed data.** Not logged as done — actually observed in
the database afterwards.

**Verify against a live database, not the shape of the log.** The log said `healthy=true` in a boot
that then rolled back everything.
