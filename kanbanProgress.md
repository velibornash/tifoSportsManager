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

## `17c05c1`, `dc8ed66`, `97595ae` — four tables that were empty, and two id-space bugs behind them

**Task:** the owner reported from the app that the league top scorers, the league top assists and the
club milestones' top scorer and top assist were all blank, while the match view showed every goal
correctly. He also reported his own player page reading `MC 0 matches` and a Matches tab saying it was
"UI ready, match log can be wired later".

That last detail was the useful clue: the match view was fine. So this was never a data problem. The
goals had been written correctly all along, into `match.event_json`, and three separate readers of that
column were not reading it.

### The first bug: a repository whose methods all returned nothing

`GoalEventRepository` is a `@Component`, not a Spring Data interface — it looks like a repository, sits
next to the real ones, and takes `MatchRepository` and `ObjectMapper` as constructor arguments it never
used. All three of its methods returned `Collections.emptyList()`. Every blank table the owner saw came
out of it: `StatsController.getTopScorers`, `getTopAssists`, and `LeagueMilestoneService`, whose top
scorer and top assist are the club milestones.

`17c05c1` reads the goals out of the event log instead. Deliberately **not** by writing them into a
`goal_event` table: the log is the record, and a second table is a second thing to keep in step, which is
how a page ends up showing a goal the match view says was never scored.

### The second bug, and the reason the first fix appeared not to work

With the repository returning real goals, all four tables were **still empty**. `StatsController` filtered
them like this:

```java
List<Long> teamIds = entries.stream().map(e -> e.getTeam().getId()).toList();  // club ids
...
.filter(g -> teamIds.contains(g.scorerId()))                                    // a player id
```

Two unrelated id spaces. It can never legitimately match, so every goal was discarded at the last step.
Player 2409 belongs to team 1, and no team 2409 exists.

**This is the same mistake as the fixture/match id collision, in the reader instead of the frontend.**
It survived the first fix untouched, and it is why the tables stayed blank after it. Two bugs stacked —
which is worth recording, because the first fix looked wrong for a full session until the second was
found.

`97595ae` resolves the club from the player, in one bulk read rather than a query per goal, and drops a
goal by somebody outside the league instead of crediting it to the wrong table.

### Two more errors that were invisible only because the rows were empty

- Every row sent the literal string `"Team"` as the club name. There was no club name to send.
- The club milestones' leader took `goal.teamSide()` as the club name — `"HOME"` or `"AWAY"`. The top
  scorer of your club was credited to a side of the pitch.

### Verification

`GoalEventRepositoryTest`, **4/4**. Its event JSON is copied from a real played match rather than tidied:
`playerId` and `assistantId` arrive as **strings**, the second goal has no assistant keys at all, and
non-goal entries are the bulk of the array. A fixture built from clean data would have passed against an
implementation that cannot read what the engine writes. It also rejects the engine's synthetic `"HOME-1"`
placeholders, which would otherwise put a row in the top-scorer table for somebody who cannot be clicked
through to.

Live against the local database, league 1: **top scorers 0 → 10 rows** (Goy Negovanović 3 goals, TSK
Surdulica), **top assists 0 → 10 rows** (Draža Đetić 2, TSK Partizan Inđija), **club milestones 0 goals
→ Goy Negovanović 3 and Draža Đetić 2**, both now carrying a real club name.

### What `dc8ed66` is

The test, committed separately and one commit after the implementation. It exists because **the
implementation was committed before it had been run**, and saying so is the point: at that moment
`target/classes/.../newLogic/sim/` was empty, because two Maven builds had been writing the same output
directory and unrelated test classes would not compile against missing classes. The first attempt at this
test had already been lost that way — the file silently reverted to the 22-line stub mid-edit, and only
a re-read caught it.

### Still open, from the same report

- **`MC 0 matches`.** The same repository, so the count is fixed by `17c05c1` — but not verified in a
  browser, only through the endpoints the two tables use.
- **The Matches tab** is an acknowledged stub needing a real player match log. That is new work.
- **B9** — the dashboard says `Kickoff is at 20:00` and the calendar says day 1 is 20:45.

## `1f90a82`, `37115b0`, `874a38e` — the appearances counter, the 20:45 kickoff, and a table repair that was never needed

**Task 1 — "MC 0 matches".** Owner, from the app: Ivica Tomić's profile showed `MC 0 matches` and `Apps 0`
while the same page showed his 1 goal and his 1 assist, and his match page showed him starting at 84
minutes with a goal, an assist, a yellow and a red.

**`37115b0`.** `fetchPlayerRatingSummary(playerId, authFetch)` takes two arguments. Both call sites passed
one — `player-view.js:610` and `league-view.js:453` — so `authFetch` was `undefined`, the call threw a
`TypeError`, and the function's own `catch` returned `matchesPlayed: 0`. **The count was never fetched.**
Goals and assists come from different columns, which is why the page contradicted itself and why this
survived being looked at twice.

`matchesPlayed` is now `null` on failure rather than `0`, and the UI prints an em dash. Zero means "played no
matches", which is a fact about the player; null means "we could not find out", and a caller printing null
is visibly broken instead of confidently wrong.

**`874a38e` — my regression, in the very next commit.** `appearancesText` was a local const inside
`buildPlayerProfileHeroHtml` and I used it in `buildPlayerProfileHtml`, which cannot see it. The profile
threw `ReferenceError: appearancesText is not defined`, so it never rendered and **no player was
clickable**. `node --check` passed: both functions are valid syntax alone. Only running it found this.
Replaced with one `appearanceCount(ratingSummary, player)` helper used by both.

Verified in a browser, Club → First team → Ivica Tomić:

| | before | after |
|---|---|---|
| Match rating | 0.0 | **10.0** |
| MC | 0 matches | **1 matches** |
| Apps | 0 | **1** |
| Statistics | Apps 0, Goals 1, Assists 1 | **Apps 1, Goals 1, Assists 1** |

Zero console errors on a clean load.

**Task 2 — B9, the 20:45 kickoff.** The dashboard said `Kickoff is at 20:00` on a day the owner specified as
20:45. `GameDay.kickoffHour()` returned `kind.kickoff().getHour()`, and `WeekTemplate` has always held a
`LocalTime` — so the template was right and 45 minutes were discarded on the way to the screen.

**On the gate, deliberately not given a minute.** A comment in `GameClockService` says `hour` is an explicit
counter, decoupled from the wall clock so a job fires the same way whatever time the manager pressed the
button. That is load-bearing, so the first attempt at this — reading the minute from `Instant.now()` —
was reverted: it reintroduces exactly that coupling, and it then failed to parse `gameTime`, which is an
`Instant`. With whole-hour ticks, an exact slot (19:00, 18:00, 16:00) opens at its own hour and a slot
inside an hour (20:45) opens on the next tick. Verified live: `Kickoff is at 20:45. It is now 09:00.`

**Task 3 — the league table does not need a button.** The board listed "no local button" as open. **Closed
without writing one**, because the owner asked whether the table already updates after matches and the
answer is yes.

`SimMatchService:525-535` adds points, wins and draws inside the same transaction that saves the score, so
the table is correct the moment a manager watches his own match. Measured on the live database: **155 played
matches across 31 leagues, zero disagreements** in points, goals for or goals conceded.

That check was proven able to fail first — `+7 points` was injected into league 1 and the identical query
reported the gap (`1|7|0`), then was undone. A clean result from a query that cannot return a dirty one is
worth nothing. (Two earlier versions of that SQL were wrong and would have reported catastrophic drift;
they summed a league total once per match row.)

So the button is off the board: the 1 AM job already covers the only case the incremental path cannot — a
replayed or interrupted fixture double-applying a result — and a manual button would be a second way to do
something the schedule already does correctly.

**What did land for it: `61a172a`, the test.** `reconcile` had no coverage, and its two reasons to exist
(idempotent, convergent from any state) are exactly what a happy-path test cannot check. Every test corrupts
the table first; the idempotency test demands a **second** run correct nothing. 4/4 green, and verified the
guard can fail by rewriting the rebuild into an add — it failed with `expected: <0> but was: <2>`.

**Still open:** the Matches tab remains a stub — *"player-by-player match log can be wired later"*.

## `91f5250` — the player Matches tab, and a rating scale that disagreed with itself

**Task:** owner, from the app: *"sredi Matches tab da se u listi pojave mecevi koje je odigrao, mec je
klikabilan i vodi na mec, pored imena dva tima i rezultata stoji broj minuta, ucinak u tom mecu ako ga ima
(ikonice lopte, asistencije, kartona) i rating"*.

**It was a stub saying "player-by-player match log can be wired later."** The data was never missing:
`MatchPlayerStats` carries `minutesPlayed`, `rating`, `goals`, `assists`, `yellowCards` and `redCards` per
match. Nothing joined it to the match, so there was nothing to render.

New `GET /match-stats/player/{id}/matches` — one row per appearance, most recent first, ordered through
`Match` because the season, week and day live there rather than on the stat row.

### Three decisions worth recording

**The result is shown from the player's own side.** 3–4 is a defeat for the home side and a win for the
away side. The endpoint sends `wasHome` rather than letting the frontend infer it from the team's current
id — a player who has since transferred would otherwise be shown the wrong side of his own history, which
is the same id-inference mistake as the fixture/match collision, in a quieter form.

**Rows are wired with `fixture: false`.** A stat line can only exist for a match that was played, and a
fixture and a Match are separate tables with overlapping numbers, so the id is never inferred.

**The impact icons reuse `buildLineupEventBadges`** rather than new markup, so a goal here looks exactly as
it does on the match page — including the rule that two yellows with no red become a red.

### The rating scale: my bug, caught by the owner

The first version rendered **`100`** while the hero on the same page read **`MATCH RATING 10.0`**.
`MatchPlayerStats.rating` is stored **0–100**; every rating the app shows is **1–10**. The lineup already
normalised it in `toLineupDto` — values above 10 are divided by 10, then clamped to 1.0–10.0 — and the new
endpoint skipped that step entirely.

The owner's question was "rejting treba da je 10.0 a ne 100?" and the answer was yes.

Fixed **at the source**, not by dividing in the frontend: the conversion is now a named
`ratingOutOfTen(int)` that both the lineup and the match log call. **A second copy of a scale rule is how
the two drift apart in the first place** — which is exactly what happened the first time.

### Verification

Browser, Club → First team → Ivica Tomić → Matches:

```
L  OFK Omladinac 3–4 OFK Mladost Niš  Superliga Srbije  84'  ⚽ 🅰️ 🟨 🟥  10.0
```

Clicking the row opens **Match Details · OFK Omladinac 3 - 4 OFK Mladost Niš**. Zero console errors.

`escapeHtml` is imported from `ui/escape.js`, the single copy, per AGENTS.md — rather than adding another.

### Not done

**No test on the row order or on `wasHome`.** The endpoint is verified live and in the browser, but neither
rule is protected against a regression. Worth adding before anyone changes the query.

## `629a0f5` — Club Milestones were answering a league question

**Task:** owner, from the app: *"na Club - First team, Club milestones top scorers i assists moraju da budu iz
tima usera a ne lige!"* — plus, in the same report, goals and assists shown as the app's own icons rather
than as the words "goals" and "assists", and a question about a flat line under the Top Scorers / Top
Assists titles.

### The bug

`buildTeamMilestones` read **every goal in the season, in every competition, by every club in the world** —
it called `findByMatchSeasonYearAndScoredTrue(seasonYear)`, which is the *league* query with no team filter
at all. So OFK Omladinac's Club Milestones named **TSK Surdulica's** top scorer.

The giveaway was in the method itself: the `playedMatches` list directly above it *was* filtered to the club,
which is why **biggest loss and attendance were right and only the two leaders were not**. A page that is
half right is harder to spot than one that is wholly wrong.

Both leaders are now filtered to players of that club. The club's players are read **once per call**, not
once per goal — the same discipline as the club-name lookup beside it, which had already been written to
avoid loading every player in the world.

**The league path is untouched and still league-wide**, which is correct. Verified: league 1's top scorer is
Goy Negovanović (3, TSK Surdulica), matching the league top scorers list exactly, and the biggest win and
loss are each other's mirror (Vranje Sport 4-0 Mlava).

### Two things that nearly shipped broken

**`buildMilestoneBoardHtml` exists in two files.** The club page imports the copy in `views/utils.js`;
`pages-renderers.js` carries its own. The first attempt edited the copy that nothing calls, and the page did
not change. **`node --check` passed both times** — both copies are valid syntax. Only loading the page in a
browser caught it.

**Not consolidated here.** Two live copies of one renderer is a standing hazard and the right fix is to
delete one, but which one is a decision rather than a cleanup, so it is left open rather than done silently.

**The card escapes its meta argument wholesale** — correct, and it would have rendered the new `<span>`
icons as visible tags. The meta is now passed as `{ club, icons }`: the club escaped, the icons markup the
file built itself. Never caller markup in `meta`.

### Verification

Browser, Club → First team:

```
Top scorer   OFK Omladinac · ⚽     Borislav Negovanović
Top assist   OFK Omladinac · 🅰️     Ivica Tomić
```

League page column headers are now `⚽` and `🅰️`.

### Not done, deliberately

**The flat line under the titles.** Measured rather than guessed at: it is the table's own `thead th` border
(`1px rgba(255,255,255,0.08)`), and the titles are already left-aligned at every width — `text-align: start`,
no pseudo-element, `justify-content: space-between` on a flex head with a single child. So it is the header
rule of the table, not part of the heading. Left alone until the owner says which they want.

## `eaedd92`, `2763bbf` — B2 closed, B1's shuffle landed, and a bug only the test could find

**Task:** the board's Cluster B, in the order it says B1 depends on B2. Both are now closed.

### B2 — the cup drew the world's clubs, or one country by accident

`rankedClubs()` **took no argument**. It asked for every club in the world and kept the ones whose country
matched *whichever club the unordered query returned first* — the board's phrase, "a Serbia dependency
expressed as a `continue`". Two things wrong: it scanned every club on Earth for a question about one
country, and the answer depended on database row order.

`survivorsOf(cup, round)` has the cup in hand and called it with nothing, so **round 1's field was the
world's bottom 108 clubs** rather than this cup's. It now takes the cup and reads
`teams.findByCountryId(cup.country.id)` — a direct indexed query.

**The test matters more than the fix, because the first version of it was green against the buggy code.**
`findClubTeamsForOperations` returns clubs in id order, so a test that creates Serbia first makes "the first
club returned" Serbian and the old code passes it. The test now populates **Hungary first** and asserts the
lowest-id club really is Hungarian, so the trap cannot silently disarm. Verified both ways:

```
old code  ->  a round-1 tie has a home club that is not Serbian: 54   FAILS
fixed     ->  6/6 green
```

That is also **why the bug survived in production**: on a fresh database Serbia is often seeded first, so
the draw looked right until another country landed ahead of it in id order.

### B1 — the draw paired the strongest club with the weakest, for ever

`favourites.get(i)` against `nonFavourites.get(i)`, over a list sorted strongest first. `DRAW_SEED` was
declared, assigned to a `Random` and **never read**, and three javadocs described a shuffle the code did not
perform.

Both halves are now shuffled, seeded `DRAW_SEED + cupId * 1000 + round`. The split is untouched: a favourite
still meets a non-favourite and the non-favourite still hosts.

**The dead `random` field was deleted rather than used.** Shuffling from one shared instance gives different
answers depending on how many times it has already been used, so the draw would change between boots — which
for a cup is worse than not shuffling at all.

**The B1 assertion is about order, not about one unlucky tie.** "The strongest club did not meet the
weakest" is a coin flip across 54 ties. Index pairing leaves the favourites in strictly descending strength
because they are walked in the order they were sorted, and a shuffle does not — so that is what the test
asks. Removing the shuffle fails it with `[147, 146, 145, 144, ...]`, which is the bug printed.

### A second bug the test found, not the reading

The idempotency guard counted unplayed fixtures by `(season, week, day)` with **no competition filter**. So
one country's round-1 ties stopped every other cup from ever drawing its own: in a 48-country world exactly
one cup could ever be drawn, and it did so silently. Added a competition-scoped count.

### Three of my own assertions were wrong before the code was

Worth recording, because all three failed on correct code and would have been "fixed" by breaking it:

- "a small country gets no draw" — false. Week 1 is the preliminary and legitimately draws whatever clubs
  exist; 40 clubs gave 20 ties. The real constraint is `MAIN_DRAW_TEAMS`, so the test now asserts week 2 is
  left empty.
- "the preliminary holds the highest indexes" — backwards. Club `i` is created with rating `40 + i`, so low
  indexes are **weak**, and the weakest 108 are indexes 0..107.
- "the favourites are the top half in creation order" — creation order is index order; strength order runs
  the other way. Replaced with the property that holds however the halves are shuffled: **the away side is
  stronger than the home side in every tie**.

### Found, not fixed — a design decision

`nationalCup()` returns the lowest-id domestic cup, so `drawRoundForWeek` **only ever draws one country's
cup** however many exist. The shuffle is correct; the *selection* is not. Fixing it means deciding whether
one job draws 48 cups or each country gets its own, and that is the owner's call.

## `4b8077b` — the world could be advanced by anyone, and the guard did nothing until three things were fixed

**Task (A3):** *"Any authenticated user can advance the whole world, and `amount` is unvalidated."*

`/api/game-clock/advance`, `/api/game-clock/advance-to-hour` and `/api/jobs/run-due` all took
`.anyRequest().authenticated()` with no role. The only protection was `dashboard.js` hiding the buttons — which
this codebase already writes down as the mistake to avoid: **"a hidden button is not a permission"**
(`CountryController`). Any logged-in manager could move every club in every country.

All three now carry `@PreAuthorize("hasAnyRole('OWNER','DEV','ADMIN')")` — deliberately the same three roles
`PlusFeatureService` already treats as privileged, so "who is an administrator" has one answer in the codebase.

### Three things had to be fixed before the guard did anything

**None of them would show on a green build**, which is the whole reason they are written down:

1. **`@EnableMethodSecurity` was absent from `SecurityConfig`.** Without it `@PreAuthorize` is read by nobody:
   the annotation compiles, the build is green, and the hole is exactly as open as before. Only a test that
   asserts the denial notices.
2. **A denial arrives as `AuthorizationDeniedException`** (Spring Security 6) from the method-security proxy, and
   it fell into the catch-all `GlobalApiExceptionHandler` and arrived as a **500**. A manager who was correctly
   refused was told the server had broken, and the real refusal was logged as an unhandled exception. Security
   exceptions are now re-thrown so the filter chain can turn them into a 403. This also affects every other
   `@PreAuthorize` in the codebase, not just this one.
3. **`amount` was unvalidated.** `2147483647` ran a two-billion-iteration loop, and `amount * 24` overflows `int`
   above 89,478,485 — so a large `days` request asked the clock to go **backwards**. Now bounded to 168 hours,
   and `IllegalArgumentException` maps to **400** rather than 500.

A hand-thrown `AccessDeniedException` from a controller body was tried first and rejected for the same reason as
(2): it also lands in the catch-all and becomes a 500.

### Verification

Live: the owner still advances his world (**200**), and `amount=2147483647` now returns
**400 INVALID_REQUEST** — "amount must be between 1 and 168 hours" — instead of hanging or reversing the clock.

`WorldAdvanceAuthorizationTest`, **6/6**, and **verified it can fail**: with the three guards removed a
`REGULAR` manager gets **200** and moves the world.

### Two of my own mistakes, both failing on correct code first

- The test looked up a **seeded account** (`kecko@example.com`) that does not exist on the H2 test profile. It now
  creates its own users — **a test about a role should build the role, not find it**, and depending on a
  particular row in a particular database passes on one machine and fails in CI for unrelated reasons.
- A **fixed email per role** meant the third test method's lookup returned two users, because
  `@SpringBootTest` does not roll back between methods. Every test then failed at 401 for a reason that had
  nothing to do with authorization. The address is now unique per call.

### Standing note

There are **two `UserRole` enums** in this codebase — `commonmanager` and `newLogic` — and the compiler picked
the wrong one on the first attempt. Not consolidated here; it is the same duplicate-definition hazard as
`buildMilestoneBoardHtml`.

## `5db4cf6` — the public country catalog, and a test that passed against the unfixed code four times

**Task (C1):** the board's item — *"`GET /countries/catalog` is `permitAll` and calls
`teamRepository.findAll()`"* — described as "an unauthenticated 14,880-row load, each with EAGER
`Country.clubs` and two EAGER `@OneToOne`. The endpoint is narrowed to names-and-flags; the query defeats
that."

### Why it stays public, and what actually changed

`register.js` calls this endpoint **before anyone has logged in** — a registration form needs the country
codes. So `permitAll` is correct by design and the fix is to make it cheap, not to lock it down.

It answered "which countries have clubs" with `teamRepository.findAll()`: every club in the world, as
entities, each mapped to its country. Locally 406 rows; **14,880 at the scale this project targets**, on an
endpoint anyone can repeat. A projection returning distinct ISO codes now answers it, loading no `Team` and
no `Country` at all. Verified live: **45ms, 4KB, 48 countries**, `seeded` and `hasClubs` correct.

**The board's own diagnosis was slightly wrong**, and worth correcting: `Country.clubs` is LAZY. The two
`@OneToOne` national sides have no `fetch` attribute and so **are** eager. A projection makes the question
moot anyway.

### The test: four attempts, all of which passed against the unfixed code

This is the part worth keeping.

- **SQL statement count** — the wrong metric. The entity load and the projection each issue **exactly one
  query**, so the count is identical and the test was green against the code it was written to catch.
- **Hibernate's entity-load counter** — reads 0 inside `@Transactional`, because rows the test itself saved
  are already in the persistence context, and 0 outside it, because each test rolls back. **This is the
  comparing-two-zeroes failure this repository has already recorded once — and the test guarding against it
  had to be caught by the same failure first.**
- **Counting managed entities** — the probe fetched ids as `Team` entities, which put them in the session,
  so it counted its own setup: all 300 clubs reported against code that loads none.

A **mock** on `TeamRepository` asks the question directly — `verify(never()).findAll()` and
`verify(atLeastOnce()).findDistinctIsoCodesOfCountriesWithClubs()` — and cannot be fooled by a statistics
switch being off. Verified both directions: reintroducing `findAll()` fails it.

Mock rather than spy because the injected repository is already a JDK proxy and Mockito cannot wrap it
(`NotAMockException: $Proxy177`). **Lombok orders `@RequiredArgsConstructor` parameters by fully-qualified
type name, not by field declaration order** — reading the argument order off the source cost three compile
errors before `javap` was asked.

### Also

`hibernate.generate_statistics=true` is now set for the test profile, so a future counter-based guard is
not silently comparing zeroes.

## `3465f26` — a rival's squad, returned as raw entities

**Task (C2):** *"returns raw `Player` entities for any `teamId`, with no ownership or country check. Leaks
every rival's squad including skills, contracts and injuries."*

`GET /countries/teams/{teamId}/players` was `playerRepository.findByTeamId(teamId)` — raw entities, any
`teamId`, no check at all. A raw `Player` carries `skills`, `talent`, `earnings`, the whole injury record and
`personality`.

**Talent is the one that matters.** It is the number `PlusFeatureService` exists to withhold — a scouting
subscription pays for exactly that information — and here it travelled with no entitlement check whatsoever.

It now does what `TeamController.getPlayers` does: the same `PlayerDTO`, the same `PlusFeatureService` gate. So
there is one rule for what a viewer may see about a player rather than one per controller. A rival's talent is
`null`, exactly as from the endpoint this now mirrors. Nothing called it; the route stays because it is public
surface and narrowing costs nothing.

Verified both ways: restoring `findAll()` fails with *"a stranger can read a rival's exact scouting value"*.

### Injury left as-is — deliberately, and it is a question for the owner

`PlayerDTO` carries `injured` and `injuryDaysRemaining`, so this endpoint discloses them — **and so does
`TeamController.getPlayers`**, the sibling the board calls correct. The board lists injuries among what C2
leaks; it does not say the correct sibling hides them.

Both fields are read by the player's own profile page, so narrowing them means changing a shared DTO with other
consumers. **Whether a rival should be able to see that a player is out for three weeks is a product decision,
so it is reported rather than decided here.**

### Two of my assertions were wrong before the code was

- **"The talent key should be absent."** `PlayerDTO` always serialises the field and sends `null` — the shape is
  a contract with the frontend. The assertion is now on the **value** being null and on `9.87` never appearing.
- **"An anonymous caller gets 401."** The security config redirects to `/login.html`, so it is a **302**. The
  point is that no squad is disclosed, not which code says so.

### Not a product bug

A player with no `Skills` makes `PlayerDTO.from` throw on `getRatingScore(position)`. That reads exactly like a
bug and is not one: every real player is given skills by the seeder, so it was an incomplete fixture.

Also worth knowing: **`CountryController` has an explicit constructor, not a Lombok-generated one**, so adding
the two dependencies was a manual edit there — and C1's test, which builds a controller by hand, needed the new
arguments.

## `4f6ce16` — a country check that ran and did nothing

**Task (C3):** *"discards its own authorization check."*

`GET /countries/{iso}/cup/fixture/{fixtureId}` called `requireCountry(isoCode)` and **threw the result away**,
then loaded the tie by id alone. Any logged-in manager could read any country's cup tie — lineups, ratings, and
the result once played — by guessing an id.

**The check ran and did nothing, which is worse than not having it:** the code reads as though the route were
scoped. It is the same shape as the fixture/match id collision — a route that looks scoped and is not.

A tie belongs to the country of its competition, and that is now verified. A tie whose competition has **no**
country cannot be shown under any country's path either: there is nothing to prove it is being asked for
properly. The refusal is **404 rather than 403**, because telling a caller a tie exists but is not theirs is the
same information as the score they asked for.

Verified both ways: removing the check serves another country's tie with **200**.

### A wider bug found on the way, fixed centrally

A `ResponseStatusException` thrown **anywhere in the application** was flattened into a **500** by the catch-all,
so every deliberate refusal — 404, 403, 409 — read as a broken server and was logged as an unhandled exception.
There is now a handler that respects the status it carries.

**That is the third instance of the same shape**, after `IllegalArgumentException` (A3) and the security
exceptions (A3): *a catch-all that owns every exception ends up owning the exceptions that already knew what
they were.* Each was found only because a test asserted a specific status.

Also corrected here: "No such cup tie" threw `IllegalArgumentException`, which the handler maps to **400** — so
asking for a tie that does not exist looked like a malformed request rather than a failed lookup. It is a 404
now.

### A test-side trap

These tests share one database and `@BeforeEach` does not roll back, so fixed ISO codes collided on the unique
index across methods. **A random two-character code collides too** — the space is only 676 — so it is a counter.

## `e693fa3` — the job schedule is not a manager's view, and the board was wrong about how open it was

**Task (C5):** *"GET `/api/jobs` and `/api/jobs/runs` are **world-readable** with no role check."*

**They were never world-readable.** `/api/jobs` is not on the permit list, so it was already
`authenticated()` — any logged-in manager, not the public. The board's wording implies an open endpoint;
it was a closed one with the wrong audience.

Both now require the same three roles as the world-advance endpoints, and the tests sit with A3's because it
is the same rule.

What they returned is the game's machinery rather than the game: every registered job with its week, day, hour
and ordering, and — through `/jobs/runs` — **the status and message of every job that ran, including the ones
that failed and why.** `/jobs/runs` is the more sensitive half. That belongs to whoever is diagnosing the
game, not to a manager running a club.

`WorldAdvanceAuthorizationTest` is 9/9 and **verified in both directions**: with the guards removed, all five
authorization assertions fail.

### Two board corrections now on the record

C1 blamed an EAGER `Country.clubs` that is LAZY (the two `@OneToOne` national sides are the eager ones), and
C5 called an `authenticated()` endpoint world-readable. Neither was a code defect, but both would have sent
whoever picked them up looking in the wrong place. **The board describes intent more often than it describes
code**, and reading the code is still the faster route to the truth.

## `d71f88e` — a GET that built the world

**Task (C4):** *"generates fixtures on a GET" — `:669`.*

`GET /countries/leagues/{id}/schedule` called `ensureEntriesForSeasonCompetition` and
`ensureDoubleRoundRobinSchedule`, so opening the page created the entries and **every fixture of a double round
robin** on a request that is supposed to be safe.

Three consequences, none of which need a bug report to explain:

- any authenticated manager **wrote to the world by opening a page**;
- two managers opening it at once **raced each other into generating the same fixtures**;
- anything that caches a GET — a proxy, a CDN, the browser — could **freeze the fixture list at the moment it
  first ran**.

**`TeamController` had the same two calls in its own schedule path** and was fixed the same way. Found while
looking, not asked for.

Both already happen where they belong: `PyramidBuilder` calls them when a pyramid is built, `SimulatedWorldSeeder`
reaches it, and `AdminController` exposes that as the seeding action. A league with no schedule now reads as
**honestly empty** rather than being quietly generated for whoever looked at it first — which is the same
principle as *"a hidden button is not a permission"*: the side effect was invisible because nobody asked for it.

Verified live: league 1 still returns its 90 fixtures, the League → Schedule page renders 44 club rows, zero
console errors. The test was verified in both directions.

### The fifth guard test this session that proved nothing

The first version of `LeagueScheduleGetDoesNotWriteTest` **created four bare clubs and no competition entries**.
`ensureDoubleRoundRobinSchedule` reads its entrants from `competitionEntryRepository` and returns having done
nothing below two — so the write was in place and the test passed.

A fixture without entries gives the generator nothing to generate, which makes the guard guard nothing. It is now
four clubs **registered in the competition**, so a double round robin is twelve fixtures and any write is obvious.

That is five this session: C1 (statement count), C1 (Hibernate counter), C1 (session count), C3 (random ISO codes
colliding), and this one. **The pattern is the same each time — the assertion was written before checking what
the code actually requires to be true for the behaviour to exist.**

## A5 measured and reclassified — the board described a bug that is not there, and described it backwards

**No code changed.** This is the fourth board item whose description does not survive contact with the code
(C1, C5, and now A5), and it is the one where acting on the description would have been actively harmful.

### What the board claims

*"advanceWeek() is 168 independent transactions. Self-invocation bypasses the proxy, so advanceHour's own
@Transactional is inert. A crash at step 100 leaves the clock five days on with 100 hours of jobs applied and 68
not, and no reconciliation."*

### What the code does

**It is not 168 transactions.** `advanceWeek()` and `advanceHours(int)` are both `@Transactional`, so the whole
168-hour loop is **one** transaction. Self-invocation *is* bypassing the inner `@Transactional` — that part is
true — but the outer one covers the same range, so nothing is lost.

**The crash consequence is backwards.** A crash rolls the **clock back**; it does not leave it five days on. What
survives is the *jobs*, because each job body runs in `PROPAGATION_REQUIRES_NEW` (`JobRunner:61-62`).

So the real residual risk is **jobs committed ahead of a clock that never moved** — not a clock stranded ahead of
its jobs.

### And that arrangement is deliberate, with tests

- `JobRunnerTest.failureIsContained` asserts *"a broken job must not freeze the season"*.
- `JobRunnerTest.failureIsNotRetriedBlindly` asserts a FAILED job is not re-run, because *"a half-applied job must
  not run twice"*.

**Making the week atomic would roll back 167 good hours over one bad job** and contradict both. Had this been
"fixed" as written, the season would have frozen on the first failure.

### The one honest question, now closed (`c499ade`)

On a hard crash, DONE job records that committed ahead of a clock which rolled back are re-evaluated on the retry
and skipped. That is self-healing **provided** the DONE key is unambiguous.

The key is `(season, week, day, jobKey)` — **without an hour**. That is safe only while every registered job
occupies a distinct `(day, key)` pair. If two jobs shared both, the first would mark the slot DONE and the second
would be skipped **for ever, silently, with no error anywhere**.

It holds today, and `MatchdayJobsConfig` says so in a comment: *"a shared key would let the day-3 job suppress the
day-7 round."* **That was a comment. It is now a check** — `JobTriggerUniquenessTest`, 2/2, both verified by
mutation (giving the second league-table job the first's key; giving the day-7 matchday the day-3 key). No
production code changed.

The board's own A1/A2 lesson is the standing instruction here: **measure the source-reading claim before editing
the code it points at.** Four times now the reading has been wrong.

## `05c33e3` — the boundary was written down twice and the copies disagreed

**Task (A8):** the one finding the audit escalated rather than listed.

`buildPlayoffSummary` told the manager which clubs were being promoted and relegated. The code that actually
**moves** clubs computes the boundary:

```
safeCount = expectedTeams - 2 * movementSlots
```

so a sixteen-club league over two lower leagues relegates the **15th and 16th**. The summary hardcoded
`top.get(8)` and `top.get(9)` — the **9th and 10th**.

**The game relegated one pair of clubs and reported another.** Both answers look entirely plausible, which is
why nothing caught it.

There is now one definition — `boundaryFor`, returning a bounds-checked `PromotionRelegationBoundary` record —
called by both paths. A table shorter than the rule expects reports a boundary of *nobody* rather than throwing
an `IndexOutOfBounds` at a manager.

Verified both ways. Restoring the hardcoded indices fails the test and prints the bug exactly:

```
the bottom club is not among the relegated:
[{toLeague=..., team=Club 09}, {toLeague=..., team=Club 10}]
```

### Also found

The summary reported promotions for only the **first two** lower leagues (`subList(0, 2)`) while the mover
iterates them all — so a third league's champion was promoted and not reported. `subList` also threw on a
country with fewer than two. Promotions otherwise already agreed; both take the lower champion.

**Found and deliberately not changed:** `findTier2Leagues` is hardcoded to `"SRB"` (`SeasonService:1083`), so
`buildPlayoffSummary` reports **nothing for any other country**, whatever league it is handed. That is a scoping
decision rather than a slip — the summary is Serbian by design or by accident, and only the owner knows which.

### Two of my assertions were wrong before the code was

- A **fixed** expected answer for a ten-club league. The boundary is *correctly* unusable once there are too
  many lower leagues (`safeCount` goes negative), and an empty list is honest — not a defect.
- **Assuming two tier-2 leagues exist** on the test profile. The summary produces nothing without two, and the
  profile's own set is not that. The test now guarantees what it needs.

Both failed on correct code, which is now the sixth time this session and the reason the mutation check exists.

## A1 and A2 measured — both real, and A1's stated fix shape has a hole in it

**No code changed.** Measured before acting, per the A1/A2 lesson the board itself carries — and after A5
and A8 both turned out to be misdescribed, that lesson earned its keep twice.

### A1 — confirmed, and worse than the board says

`JobRun` **does** carry the unique constraint (`@Table(name = "job_run", uniqueConstraints = ...)`), so the
sequence is:

1. `JobRunner:116` reads the guard **unlocked** — two concurrent advances both see "no row";
2. `:140-141` each runs the body in `REQUIRES_NEW` and **commits it** — *the duplicate work has now happened*;
3. `:145` each writes the guard — the unique constraint rejects the second **save**.

So the duplicate **row** is prevented and the duplicate **work** is not. The class javadoc at `:24-28` claims
the guard is the whole mechanism, so the code and its own documentation disagree.

**The board's fix shape is "insert PENDING, then flip to DONE" — and it has a hole the board does not
mention.** A crash between the insert and the flip leaves a **PENDING row that blocks that job for ever**, which
is a worse failure than the one being fixed: the job would never run again for that (season, week, day) with no
error and no way to recover. A PENDING status therefore needs a **staleness rule** — a claim older than N
minutes is treated as abandoned and retried — and that is a design decision, not a two-line insert.

### A2 — confirmed

`JobRunner:124-130` returns on `FAILED`, and `JobRunRepository` has **no reset, delete or update method at
all** — only three queries. Four places promise an operator can re-queue:

- `JobRun:31` — *"an operator re-queues it explicitly"*
- `JobRun:46` — *"Threw. Recorded and skipped; an operator re-queues it."*
- `JobRunner:122` — *"the case where a job failed and has been re-queued"*
- `GameClockScheduler:21` — *"a job that failed and was re-queued ... is picked up"*

So the promise is made four times and the capability exists nowhere. And the consequence is the serious part: a
`MatchdayJob` failure means **a whole matchday is never played and never retried**.

### Why this stopped here rather than becoming a diff

A1 and A2 are one design, not two: a claim-before-run protocol (A1) and a way to release or retry a claim (A2).
Doing A1 alone introduces the permanent-PENDING failure described above. Doing A2 alone leaves the duplicate
work. **They need to be designed together**, and the PENDING-staleness rule is the owner's call — it decides
whether a slow job gets stolen from while it is still legitimately running.

## `a4e0882`, `6ac283a`, `83fb875` — a week of orphans, sixteen identical queries, and a shootout trap

A batch of three while the owner was away, chosen because none of them needs a decision from anyone.

### A6 — the orphan, deleted (`a4e0882`)

`AdvanceWeekAsyncService`: 186 lines, **zero callers**, hardcoded `findById(1L)`, Serbian-only loop. Verified by
grep across `src/`, `src/test/` and any config binding — **not just the declaration** — before deleting.

Its danger is not its size. **Five of its operations also exist in the job path**, so wiring it back — which is
exactly what an audit finding invites — would double-apply the week. Orphaned code with a plausible name and a
working body is worse than no code: it reads like the answer to a question whose real answer is elsewhere.

### A7 — sixteen identical queries (`6ac283a`)

`findUnplayedOnDay` was called **inside** the `flatMap` over target competitions. Every call returned the identical
rows, because the competition filter ran in Java *after* the query, so the query could not narrow anything. With
sixteen CUP competitions that is sixteen identical full-table scans of the fixture table for a single matchday —
and the audit notes it worsens with every tier added.

Now fetched once and filtered by a set of competition ids. `MatchdayJobQueryCountTest`, 2/2, **verified both
ways**: restoring the flatMap fails with *"Wanted 1 time, was 16"*.

**Two tests, not one.** A count alone would be satisfied by a query that returns nothing. The second asserts the
answer is still right — sixteen competitions with one fixture between two of them must still play exactly the
target competition's fixture. A cheap query must not be a wrong one.

### B8 — documented, root cause recorded (`83fb875`)

`MatchFormat` has zero callers, and **the reason is the actual finding: nothing carries a format.** Neither
`Match` nor `Competition` has a `matchFormat` column, so `MatchFormat.goesToPenalties()` cannot be consulted
without a schema change. That is why this was not "fixed".

Done without a schema change: `isKnockoutTie` is named for what it decides rather than for the competition type,
and the shootout path carries an explicit note about the group-stage trap. Verified unchanged: **25/25** across the
engine wiring tests.

**The board's ordering constraint holds and is now recorded at the predicate**: wire the column, then the group
stage — `isKnockoutTie` is exactly the method that has to learn about a league phase, and it must do so by
consulting `MatchFormat`, not by widening the expression.

### B11 — closed as stale, not acted on

The board says delete three stale global cup rows. **Checked the database instead of trusting the note: one CUP
competition exists and none without a country.** The rows are not there — the 31 competitions are 31 Serbian
leagues plus one national cup. Closed as not applicable, and noted to re-check if a fresh seed reintroduces them.

## `89b8165`, `0db10aa` — only DONE is terminal, the claim comes before the body, and Serbia starts level

The four parked decisions, answered (owner, 2026-10-02) and acted on.

### A2 — a failed job is picked up again (`89b8165`)

The rule: *a job fires and must execute immediately; if it does not execute successfully, the status is not DONE
and the scheduler picks it up next time.*

The guard returned early on `FAILED` as well as `DONE`, so **one transient throw disabled that job for that
(season, week, day) for good** — and a `MatchdayJob` that failed meant a whole matchday was never played and
never retried.

The accepted cost is stated rather than hidden: a job that half-applied before throwing is re-run and half of it
applied twice. A test asserted the old behaviour (*"a FAILED job is not retried automatically — a half-applied job
must not run twice"*) and is **rewritten rather than left green and wrong**, with two counterweights — a `DONE` job
is never run again over three scans, and a job that fails then succeeds becomes `DONE` and stops.

### A1 — the claim comes before the body (`0db10aa`)

The guard was read unlocked and the body run **and committed** before the guard was written, so two concurrent
scans both ran the job and the unique constraint rejected only the second **save**. Duplicate row prevented,
duplicate work not.

`PENDING` — declared and never written, the audit's *"used nowhere"* — is now the claim, inserted before the body.

**A freshness window was needed and the board does not mention it.** The first version did not work: the second
scanner usually arrives by **reading** the row, not by attempting the insert, and since *"not DONE"* means retry,
a live `PENDING` was re-run by the very scanner the claim was added to stop. The test caught it — *"the job body
ran 2 times for one slot"*.

So a claim is honoured for ten minutes and assumed abandoned after that, stamped at claim time. That is how a
scan tells *"someone is in the body right now"* from *"someone took this slot and died"*. Without it, a killed
process leaves a job unplayed for ever; a job that legitimately runs longer than ten minutes must be re-entrant.

`JobClaimConcurrencyTest` runs **against the real database** — a mock repository cannot express the collision,
because it has no unique constraint to lose — with the body held open on a latch so the scans genuinely overlap.
Verified it can fail.

### Serbia's ranking (`0db10aa`)

`TeamFactory`'s fallback invented Serbia at **reputation 50 / youth 50** when the country row was missing, while
every other country is created at `WorldCatalogSeeder.STARTING_RATING = 1500`. The one country a manager actually
plays sat at the bottom of the World page's ranking, below forty-seven countries.

Both now reference `STARTING_RATING` rather than a retyped literal, so the two cannot drift again.

**Left alone deliberately:** the `55` in `DatabaseInitializer:882` and `CSDataInitializer:99` are the **text
manager's** `CSCountry` — a different table in a different game mode. And `DatabaseInitializer.createCountryIfNotExists`
has **no callers at all**, so its reputation argument is not worth chasing.

### A note on the environment

Partway through, `mvn` began resolving Homebrew's **Java 24** instead of the project's Java 21, and Mockito's
inline mock maker could no longer instrument classes — which surfaced as errors in tests I had already run
successfully. The project's JDK is Corretto 21; with `JAVA_HOME` set to it everything passes. **A test failure
caused by the wrong JDK is not a test failure**, and I initially misreported one such as pre-existing.

## `f1d8991` — the whole recovery window sat after the season's only possible cup date

**Task (B4):** *"Cup and international fixtures are dated 2026-07-01, hardcoded, with no season offset."*

**Three** seeders measured fixture dates from that literal: `CupFixtureSeeder`, `InternationalFixtureSeeder` and
`InternationalClubCupDraw`, which called into the first.

The recovery window is `currentDate - 2 days` (`ZoneLoadService:69,127`). So once the clock passed **2026-07-06**,
**no cup or international fixture ever fell inside it** — zone loads were written and never read, and `RecoveryJob`
reported zero for ever.

**A whole feature quietly dead, with nothing erroring anywhere.** No exception, no failed job, no red log line: the
job ran, wrote its rows, and found nothing to recover, for ever.

The league path was already right — `SeasonService:267` seeds from `clock.getCurrentDate()` — which is why this was
cup-only, and why the fix is to **use the same clock** rather than to invent a better date. All three now read the
game clock, with the literal kept only as a fallback for a world that has no clock row, which is the case the
league's own `getOrCreateClock` already handles.

`FixtureDateFollowsTheClockTest`, 3/3. The assertion is on **movement**: push the clock a year forward and the tie
must move with it. A test asserting a literal date would have passed against the old code for ever — which is
precisely the shape of the bug.

### Two of my own errors, both fixed rather than worked around

- `CupFixtureSeederCountryTest`'s trap guard (from B2) compared against the lowest club id in the **whole
  database**, so another test method creating Serbian clubs first made it fire — correctly, but measuring the wrong
  thing. It now compares only the clubs its own fixture created.
- The clock test assumed a clock row the H2 profile does not have, and compared a **post-push** date against a
  **pre-push** one.

## `11c3a02` — every national pass added another squad

**Task (B5):** *"`NationalTeamSeeder.squadsFor` is not idempotent — no 'does this side already have players'
check, on a path that runs on both seeding branches and from five call sites."*

**No guard at all.** Every pass added another squad of up to 25 players to a side that already had one — up
to **2,400 duplicate player rows per pass**.

`BotSquadGenerator.ensureSquad` has had exactly this check the whole time and writes down why: *"Idempotent by
squad size, not by a flag: the players are the record."* The same rule applies here now.

**Also a scan that bought nothing.** `teams.findClubTeamsForOperations()` was called *inside* `squadsFor`, so
the whole club table was walked for the senior side and again for the U21 side of **every country** — twice per
country, a full table scan each time, to answer a question whose answer is a property of the country. Clubs are
now read once per country and memoised.

`NationalTeamSquadIdempotenceTest`, 2/2, **verified in both directions**. Removing the guard reproduces the bug
exactly:

```
a second draw added 25 players to a side that already had 25
==> expected: <25> but was: <50>
```

The second test is the counterweight a guard like this can break: it is **per side, not per country**, so the
U21 side is still drawn.

`squadsFor` is now package-private. It has five call sites inside the class, and driving a whole seeding pass to
reach it would have tested the pass rather than the bug.

## `1806d2f` — the caller could name the column, and could mint a club

**Task (C6):** *"Unvalidated `sortBy` into `Sort.by()` ×3; raw entity create with no validation and no auth ×2;
`LineupController` raw `RuntimeException` → 500 with a leaked message ×2."* Flagged 2026-09-26, unchanged since.

### The sort column was the caller's to choose

Five sites across four controllers — not the three the board counted — took a caller-supplied column straight
into `Sort.by(sortBy)`. That hands the caller the column name, so they could:

- order by a column the page never intended to expose and never knew existed;
- order by an **unindexed** column, turning a two-character request into a full sort of every player of every
  club in every country;
- send a name that is not a property and get a failure from deep inside Hibernate whose message **names the
  entity and its columns** — a schema description handed to whoever asked.

`SortWhitelist.of(sortBy, direction, parameter, allowed)` allow-lists per endpoint. An unknown column is a **400**
that names what was asked for and what is permitted, instead of quoting the schema.

### Two create endpoints that took a raw entity

`PlayerController` and `TeamController` each took a `Player`/`Team`, saved it with **no administrator check and no
validation**, and returned the entity. So any logged-in manager could mint a club or a player — and a body
carrying an `id` would **overwrite an existing row through `save()`**, which is a write primitive dressed as a
create.

Both are now `PreAuthorize`-gated like the rest of the privileged surface, validated, and answered with the DTO
the surrounding code already returns.

### Two 500s that were refusals

`LineupController` threw raw `RuntimeException` for a missing lineup and for the wrong number of starters. Both
fell into the catch-all and arrived as **500**, so a manager who sent the wrong number of players was told the
server had broken, and the message went through the error path rather than being a deliberate refusal. They are
404 and 400 now.

`UnvalidatedInputTest`, 7/7, **verified in both directions** — reverting the club gate and its validation fails
two of the seven.

## `9641cdf` — the world was flat, and continental entry was alphabetical

**Task (B6):** *"Continental qualification for 47 of 48 countries is alphabetical."*

Every club in a division was created with **one identical reputation** — a function of tier alone. So the
standing table sorted by reputation and then **by name**, and continental qualification read that table.

**The comparator was over identical values, so the name tiebreak *was* the result.** Nothing was wrong, nothing
errored, and the world was simply flat.

This is the half of `COMPETITIVE_ANALYSIS.md` #14 the board calls *"the one that matters"*.

### Fixed where the clubs are made, not where they are sorted

Sorting on a column that holds one repeated value papers over the flatness: any other reader of that reputation
would still find ten identical clubs. The spread is applied in `fillDivision`, by **index within the division**:

- **stable across installs** — the same club name gets the same strength every time, unlike a hash of the name,
  which would look varied and change between installs;
- **wide enough** for a continental qualification order to mean something;
- a **starting ordering, not a claim about any real club** — `WorldCatalogSeeder` makes exactly this argument
  for country strength, and the manager world is not a replica of the real one.

`PyramidClubStrengthSpreadTest`, 2/2, asserting **distinctness and ordering** rather than a particular number.

### A stale pointer on the board

The board cites `InternationalClubCups:270` as *"a comparator over identical values"*. That is **stale**: the
code already delegates to `LeagueTableOrder.sort(rows)`, the one comparator in the codebase, with a comment
saying a table also ordered in SQL would be *"a second ordering waiting to disagree with it"*. It was fixed
before this session. Recorded so nobody re-investigates it.

## `09ad534` — the in-game date was the wall clock, 469 milliseconds at a time

**Task (B10):** *"`GAME_ZONE` is declared and dead, and the in-game date moves by wall-clock time rather than by
game time."*

### currentDate was wall-clock time

`advanceHour` overwrote it with `Instant.now()` truncated to UTC **on every single hour**. So the in-game date
tracked real time rather than the season: **two managers doing the same 168 advances got different dates** from
the same game state — and `ZoneLoadService:181-186` reads exactly that field.

Verified, and the numbers are the whole point:

```
two runs of the same 168 advances ended on different dates:
2026-10-09T16:31:59.388234 and 2026-10-09T16:31:59.857176
```

**469 milliseconds apart, two different in-game dates.** A test asserting a literal date would have passed for
ever and would not have noticed — which is why the assertion is the property itself: two runs of the same advances
end on the same date.

The date now moves when the **week counter** does, by one week — the rate `SeasonService:443` and `:635` have
always used. Everything else already treated the date as game time; only this line disagreed, and that is how B4's
fixture dates could be right while the recovery window was not.

### A game day is a day-slot, not a wall-clock day

My first fix moved the date one day per day-slot and **two of my own tests caught it by failing on correct
code**. The seven-day template is a game construct with no months in it. The rate that matters is the one the
codebase already agrees on, not one invented alongside it — and that is now what the test asserts.

### GAME_ZONE is no longer dead

It is now the single definition of the game's timezone, and `/api/server-time` uses it instead of repeating the
literal. One zone, named once, so a server-time that disagrees with the clock cannot happen.

## `c46786f` — clubs get a rating, and the World page stops asking the database 11,000 times

**Task:** the first item on the board — *"club ratings: a rating column on `Team`, plus previous-value
and delta columns, the replay from match history, seeding it at initialisation"* — plus two things the
owner reported from the Oracle instance on the way: the World page is slow, and its header looks wrong.

### The rating

`RatingEngine` had arithmetic and no caller, the same fate `NationalRatingService`'s arithmetic had for
three days. The gap was storage and wiring, not mathematics.

**Three columns on `Team`, and deliberately not on `reputation`.** Eight services read
`Team.reputation` and four clamp it to 0–100 — attendance, wages, sponsorship, transfer pulling.
`Country.reputation` is already a 1500-scale Elo on a column of the same name, and that collision has
already cost a session. So `elo_rating`, `elo_previous_rating`, `elo_delta`, with the reason written
down on the column.

**A replay, not an increment** — the same three reasons as the national ratings, of which the first
decides it: Serbia has been playing since the pyramid went in, so an incremental job leaves every one of
those matches uncounted and the owner has to reset the database to see the feature work at all.

**Seeding is free.** The replay *begins* at each club's tier seed, so a club is rated 1500/1400/1300/1200/1100
the first time it runs and there is no separate backfill to forget to call. **No season reset**, because none
was asked for and a pure function of the match table persists across seasons for nothing.

**Once per batch, not per match.** The replay is world-wide, so per fixture a matchday would replay the
world 155 times, and the cost would grow with the world rather than with the batch.

**`qualificationBonus()` is not applied, on purpose.** The international cups have qualified clubs and **no
fixtures**, because the draw is not wired, and the national cup is a straight knockout — so there is nothing
to detect a group stage from. A branch that provably never fires is a green log line and no rating
movement, which is the exact failure this codebase keeps being bitten by. It waits for the draw.

### Two things I got wrong, both caught

1. **Every league match in the game was being rated as a cup match.** `valueFor` checked only for
   international scope, so `CompetitionType.LEAGUE` fell through to `MatchValue.CUP` — nine points a
   season short of the owner's scale, in a game whose premise is that league football is what a rating is
   *for*. The test that caught it also **corrected me**: I had asserted that a cup tie outweighs a league
   match. The recorded owner order is the opposite — league is the reference point and a cup tie is
   deliberately below it, which `MatchValue` had wrong once at 1.15 until a property test caught it. The
   kanban's one-line summary of the spec ("a cup tie or an international outweighs a league game") is the
   loose sentence; `MatchValue` is the decision.

2. **`sameValue` compared two boxed `Double`s with `==`,** which compares references, and ratings at
   1100–1500 sit outside `Double`'s identity cache of −128…127 — so the "has this row changed" check was
   always false. Fixed.

   **Its predicted consequence turned out to be false, and that was measured rather than argued.** The
   audit's claim was ~14,880 UPDATEs after every matchday. The clubs are loaded inside the replay's own
   transaction, so they are **managed**; `save()` on a managed entity is a `merge()` that does nothing, and
   Hibernate skips an UPDATE whose columns are unchanged regardless. Reinstating the broken comparison and
   re-running the test still issues **zero** UPDATEs. Dirty checking is what protects the database, not the
   guard.

   The guard was still worth fixing — **a guard that does not work is worse than none, because the next
   reader trusts it and stops looking** — and the finding is written into `kanban.md` so it is not
   re-"fixed" from the audit text.

### The World page, twice

**It was slow, and it was not the network.** `summarise()` asked **fifteen** cups to read the finished
season's tables when there are only **three** distinct sets of them — three cups per tier, all off the same
divisions — and then read each division with two queries of its own. Tier 5 has sixteen divisions in each of
forty-eight countries: **768 divisions × 2 queries × 3 cups**, plus thirty full scans of the competition
table. On a server reached over a network, every one of those is a round trip.

A tier's tables are now loaded once and its three cups selected from them. **The selection rules are
untouched — same comparator, same pools, same bands — because the body was moved rather than rewritten.**

`InternationalClubCupsQueryBudgetTest` asserts the **shape** rather than a count, because a count would rot
the next time a repository grows one lookup: adding divisions to a tier must not add queries. Measured
**1 division 20 queries, 16 → 22, 64 → 22**. Under the old code the 16-division step alone cost 96 more.

**Its header was the reverse of every other page** — back button hard left, title hard right. It now reuses
the Country tab's own header component rather than copying it, so they cannot drift apart again: three CSS
selector pairs became one, scoped to the class. The mobile `position: fixed` bar was **retargeted, not
deleted** — it was keyed on `.fm-page-toolbar`, and leaving that selector alive against nothing would have
kept the rule running against no element and quietly reintroduced the off-screen-button bug reported two
sessions ago. `WorldPageNavigationTest` failed on the change, which is the guard working, and its assertion
was moved onto the new selector with the scoping requirement kept.

### Verified

| | |
|---|---|
| `ClubRatingServiceTest` | **10** — ladder, results, delta-vs-previous, competition weighting, idempotency, not-a-ratchet, national sides |
| `ClubRatingPersistenceTest` | **3** — written *not* `@Transactional` and reading back through a fresh transaction, because the only shape that catches a lost write |
| `InternationalClubCupsQueryBudgetTest` | **2** — flat query cost, and the qualified counts still correct |
| The guard test bites | `aSettledReplayIssuesNoUpdates` was written, found green, **broken the code deliberately** and watched it fail — twice, because its first version measured nothing at all |

**A third test in this session was green while measuring nothing**, which makes four in the repository's
history and the standing rule is the reason it is written down. `aSettledReplayIssuesNoUpdates` uses
Hibernate statistics, and the class had no `generate_statistics` property, so it compared zero updates to
zero updates and passed with the bug present. It also asserted the wrong thing — that the second replay
should write *as many* rows as the first, when it should write **none**.

### The World page's numbers are real; ZOX's are not

The owner asked whether ZOX analytics was wired to the pre-match report, and the screenshots in
`manual/images/` answer it visually. **ZOX's design is genuinely better than ours** — tabs, cards, clean
hierarchy — and its content is fabricated:

| ZOX preview | ours, League Schedule |
|---|---|
| `OVR 93.4 · Form N/A` | `OVR 83 / 80 / 74 / 82`, **Form 7.3 / 8.1 / 7.1 / 6.7 / 8.9** |
| prediction `51% / 25% / 24%` | — |
| `xG 1.30 : 1.20` | — |
| fitness `92%` / `91%`, mismatches `0 / 0` | — |

ZOX's two computed numbers average `MatchPlayerStats.getRating()` — **the ratings from the match that had
already been played** — and 93.4/(93.4+88.3) = 51%, which is the "prediction". `drawProbability` is the
literal `0.25`. Everything else is a constant. Meanwhile `ScheduleInsightService` has been computing real
strength, form and a prediction all along and is wired to the **schedule** pages, so there are three preview
surfaces, one real, and the good-looking one is not it.

Written up on the board in full. The fix order matters: point ZOX at `ScheduleInsightService` and delete
the constants, make its default match a **fixture** rather than the last played match, and merge the two
preview endpoints. **The post-match prose is cosmetic and must not be touched until that is settled** — a
wrong number dressed up is worse than a missing one.

### The suite is red at HEAD, which is a fact the board did not have

Both documents said there was **no recorded green run** at HEAD. The stronger fact: HEAD is not green.

| | tests | failures | errors |
|---|---:|---:|---:|
| clean `941cf5e`-era HEAD, this session | 864 | **16** | **51** |
| with this commit | 879 | 18 → **16 once the two of mine were fixed** | 51 |

So this commit adds 15 tests, all green, and moves no existing number. **`NationalRatingServiceTest` is
order-dependent** and flips depending on which classes run before it — it fails on a clean HEAD when run
alone and passes in the full suite. Worth recording, because the next person to add a test class will hit
it and blame themselves.

### What is *not* done

- **The two ranking tables that read the new columns.** That was the second item on the board and this is
  the first half of it.
- `qualificationBonus()` — waiting on the cup draw.
- The mobile `padding-top: 240px` on the World page is **reasoned, not measured**. The header grew to a card
  carrying a title, the Back button and five wrapping facts, which puts it at roughly 210–220px at 390px
  wide. The two errors are not symmetric — over-padding is whitespace, under-padding hides the first panel
  — so it is deliberately generous. **Worth one look at 390×844.**

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

## `3ea6dd9` — the first draw after seeding, which was not a coin flip

### It was not just the shootout

The shootout's toss was fixed with a wide draw and a low bit, and that left the question the board entry
was actually asking: **is the engine's own first decision after seeding one of these?** So it was
measured, and the answer is yes.

`SimMatchService.simulate` seeds from the fixture id:

```java
SimulationRandom.seed(fixture != null && fixture.getId() != null
        ? fixture.getId() : System.nanoTime());
```

So the seeds are small consecutive database ids — 1, 2, 3, 4 — which is the worst case and the only case
that matters. The first draw of each kind, across seeds 1..500:

| first draw after seeding | one way | the other |
|---|---|---|
| `nextInt(2)` | **0** | **500** |
| `nextBoolean()` | **500** | **0** |
| `nextDouble() < 0.5` | **0** | **500** |

Every one of them constant. Whatever the engine asks first, it gets the same answer for every match in
the world, and the answer is a function of which number a fixture id happens to be.

### Fixed at the source, because the call site is one of many

The obvious place to fix it is wherever the bad draw happens. The better place is the one function every
consumer goes through:

```java
public static void seed(long seed) {
    Random random = new Random(seed);
    random.nextDouble();   // a fresh Random does not mix its first output
    RNG.set(random);
}
```

One value, discarded, wide so it is a different part of the stream. The same three measurements after it:

| first draw after seeding | one way | the other |
|---|---|---|
| `nextInt(2)` | 252 | 248 |
| `nextBoolean()` | 248 | 252 |
| `nextDouble() < 0.5` | 252 | 248 |

The shootout's own `(nextInt() & 1)` is left in place. It is redundant now, and redundant in the right
direction: if the discarded value is ever removed, the shootout is still correct.

**This changes every seeded run.** A replay regenerated from the same seed will now differ from the one
that was stored. It is still deterministic — same seed, same match, which is all
`ProposalMatchExporter` and the viewer launchers actually promise — and the difference is a match that
is not decided by a constant. Stored replays are tick snapshots and are not re-simulated, so nothing that
has already been played changes.

### Five tests, one of which is about the JDK

The fix is invisible once made: `seed()` discards a value, nothing in the code says why, and the natural
tidy-up is to delete the line and put the bias back. So the measurements are the tests.

The fifth is the interesting one. It asserts that **a bare `java.util.Random` still shows the constant**,
which is the platform behaviour the workaround is written against. If a future JDK changes the mixing,
that test fails on purpose and the workaround gets re-measured rather than trusted or deleted. My first
version of it had the assertion inverted and failed immediately — 500 trues is the *problem*, not the fix
working.

Determinism is pinned too, because the fix must not cost reproducibility: the same seed gives the same
sequence, and two different seeds still diverge.

## `0ecc3af` — the three international club cups, and who is allowed in them

### "Not created yet" was true, and it was not the interesting part

The World page rendered Champions Cup, Masters Cup and Challenge Cup as a disabled row reading "Not
created yet". The competitions were indeed absent. But creating three competition rows would have made
the badge untrue and the page still meaningless — a competition nobody may enter is a name.

So the rule came with them, and it is the owner's: **Champions for the winners, Masters for the second
and third, Challenge for the fourth.** That is a better shape than it first looks. Every division in the
world sends a club to all three, so the **fifth-division champion reaches the Champions Cup exactly as
the first-division champion does** — the rule is about the place in *your* division, not the strength of
it. It also means the three cups together cover every club that has a table, which is what makes them
worth having rather than three parallel listings of the same elite.

**Entry comes from the season that has finished.** A club that wins its division in week 12 does not
enter the same season's Champions Cup by winning it in week 12 — the entry is decided by the table
everyone has already played. And a world part-way through season one has no finished table to read, so
the counts are honestly zero rather than invented. "No club has finished a season" is a real state and it
is now distinguishable from "broken", which the old badge could not manage.

Live, with Croatia active so the world has 62 divisions:

| Cup | Qualified |
|---|---|
| Champions Cup | **62** — one per division |
| Masters Cup | **124** — the second and third of each |
| Challenge Cup | **62** — the fourth of each |

### The table order is read, not remembered

Entry goes through `LeagueTableOrder`, the one comparator in the codebase, rather than the stored
`position` column. A table is only ordered when it is read, and that column is written by whatever last
read the table — so a club whose stored position says *first* can be the one that finished fourth. There
is a test for exactly that: it sets a fourth-placed club's stored position to 1 and asserts it is not
entered for the Champions Cup while the real winner is.

### Two mistakes, both mine, both found by looking rather than reasoning

**The read endpoint was writing.** `summarise()` called `ensureCompetitions()`, so a `GET` on the world
page issued an `INSERT` — into an endpoint that is transactional read-only. The whole page failed with
`cannot execute INSERT in a read-only transaction`. A read that writes is wrong twice over: it breaks, and
it makes the page work only if the page happens to be the thing that runs first. The competitions are
created on boot; `summarise()` is read-only; and a cup that is somehow missing is reported missing rather
than conjured by a page view. There is a test that reading the cups creates nothing.

**Then the cups were created inside the boot transaction and vanished.** They were gone from the database
even though the World page cheerfully reported 62, 124 and 62 qualified — the counts came from the
divisions, the competitions themselves were not there. That is **the third write in this codebase lost to
the boot transaction**, after the league-fixture day stamp and the national Elo replay, both of which now
write in `REQUIRES_NEW`. `ensureCompetitionsDurably()` does the same, and the pattern is now familiar
enough that the next step should be to find why that transaction does not survive rather than to keep
adding `REQUIRES_NEW` calls around it.

### Not done

**The draw.** `CupFixtureSeeder` seeds only the first CUP row it finds, so these three have qualified
clubs and no fixture list. Wiring it means generalising the seeder from "the cup" to "a named cup" rather
than reaching for the first one — a real change to code every national cup already depends on. It is
written up as not done rather than left for someone to discover from a "Not created yet" badge on a page
that now says these competitions exist.


---

# Session 2026-10-01 — the world stops seeding itself, and three things were found dead

Owner: *"start aplikacije treba da radi SAMO START aplikacije. Nikakav init nikakav seed nista."*

The Oracle instance had been failing silently for a season. Everything below came out of that report.

## What changed

| Commit | Change |
|---|---|
| `d368a2a` | International club cups scoped **per tier** — see the spec section in `kanban.md` |
| `16274aa` | `SimulatedWorldSeeder` — simulated countries as a static world, no players, no fixtures |
| `54b5e56` | `initialize-db` was reporting success on a world with **no leagues at all** |
| `bbdf397` | `escapeHtml` never imported; `/leagues/null/table` 500 |
| `6639545` | **Boot writes nothing.** Initialize = Serbian structure only; new *Seed other nations* button |
| `087c2f9` | The bottom Back button on a match view was never wired to anything |
| `8d66a56` | Baseline selector appointment moved into Initialize DB — it had been in the removed boot path |
| `c0ba3ba` | `LazySquadGenerator` — a playerless simulated club gets a squad when a match needs one |
| `b68346c` | Club rating seed corrected to 1500 −100 per tier |
| `cc8d6da` | `dataFixSuggestions.md` §1.1 settled: recovery **is** committed, the finding is wrong |

## The pattern of the whole session: things that reported success and did nothing

Four separate bugs, one shape:

1. `initialize-db` caught its own seeding failure, logged it, returned normally — and the job reported
   success off that return. A world with zero leagues looked like a clean rebuild.
2. `initSerbianFootballStructure()` opened with `orElseThrow()` on Serbia, and **every** league below it
   is built from that country. One missing row, and the whole pyramid was skipped before the first league.
3. The bottom Back button got a `dataset` and a `display` style and **no listener**. Clicking it did
   nothing, with no error, which is why it read as "the button isn't there" rather than as a bug.
4. `escapeHtml` was used in a `catch` and never imported, so the first failed country load replaced a
   readable message with a dead render.

The lesson is the standing rule again: **a green status is not evidence.** Three of these four could
only be found by opening the app, not by reading the code.

## Three bugs I caused in this session

Worth writing down, because each one was a correct decision applied one step too far.

**Removing boot seeding removed a step nothing else did.** The baseline selector appointment lived in
the boot path. So after Initialize DB, the national-team page opened for the owner saying "you are not
the selector" on a side that had **no selector at all**. Found by the owner, not by me.

**The cups were wrong twice before they were right.** First: three global competitions taking every
division on the planet. Then: per *division* rather than per *country per tier*. The owner said "nemam
ništa protiv full seed ostalih zemalja" and the shape became clear. Both wrong versions looked
plausible, which is why the spec is now written down before the code rather than after.

**The club rating seed was wrong in the source all along** — `1500 + (worst - tier) * 200` anchors the
*worst* tier at 1500 and puts tier 1 at 2300. The existing test only asserted "tier 1 is above tier 3",
which is exactly the weak assertion that let it through. **A test that cannot fail proves less than no
test**, and that one could not fail.

## The measurement trap, in three layers

Writing `ZoneLoadRecoveryPersistenceTest` took four attempts, and each failed version was green while
measuring nothing:

1. no `lastPlayedAt` → player skipped, `touched == 0`
2. load with no zone → not-null violation, then no window match
3. load dated with `now()` instead of the **game clock** → outside the recovery window
4. every one of those passed its own assertions

Only the fourth version — clear the persistence context, re-read from the database — could actually
fail. **This is the single most transferable thing from the session**, and it applies to
`dataFixSuggestions.md` §1.2–1.5, which are unverified and may be the same shape.

## Oracle verification

Confirmed live, and the first run on a real server:

```
Static pyramid for Other Nations: 31 division(s), 310 club(s), no players, no fixtures.
Simulated world: 47 country/countries seeded (0 already had a pyramid),
                 1457 division(s), 14570 club(s). No players, no fixtures.
```

47 × 31 = 1457. 1457 × 10 = 14,570. "Other" is the 48th country. Nothing was generated that was not
needed — which is the entire point of the static world.

## Not done

**Cup draw wiring.** `CupFixtureSeeder` still seeds only the first CUP row, so the 15 per-tier
competitions have qualified clubs and no fixture list. **Knockout progression** — no final, no third
place. **Three stale global cup rows** from the first wrong version still need deleting.

---

## Status at the end of the unattended session — what is parked, and why

Everything below is **waiting on a decision from the owner** and was deliberately not touched. Nothing here is
blocked on effort.

| Item | The question | Why it was not decided alone |
|---|---|---|
| **A1 + A2** | **How long before a `PENDING` job claim is considered abandoned and retried?** | A1's fix is claim-then-run; A2 is a way to release a claim. Doing A1 alone leaves a crashed job blocked **for ever** — worse than the bug. The staleness rule decides whether a slow job gets stolen from mid-run, and there is no obviously right number |
| **C2 injuries** | **Should a rival see that a player is out for three weeks?** | `PlayerDTO` carries it and the player's own profile page reads it, so narrowing it is a change to a shared DTO with other consumers |
| **A8 `findTier2Leagues`** | **Is the season summary Serbian by design or by accident?** | It is hardcoded to `"SRB"`, so it reports nothing for any other country. That is a scoping decision |
| **B1 residual** | **Does one job draw all 48 national cups, or does each country get its own?** | `nationalCup()` returns the lowest-id domestic cup, so only one country's cup is ever drawn. The shuffle is correct; the *selection* is not |

### Verified state at hand-off

- **`mvn compile` clean**, and the app boots and serves (200 on `/login.html`, dispatcher initialised, no
  compilation errors). The process is reaped when the shell session that started it ends, so later live calls
  in that window fail with connection refused — **an artifact of the harness, not a fault in the application**.
- **38/38** across every test written this session: `WorldAdvanceAuthorizationTest` (9), `MatchdayJobQueryCountTest`
  (2), `JobTriggerUniquenessTest` (2), `PromotionRelegationBoundaryTest` (2), `GoalEventRepositoryTest` (4),
  `CupFixtureSeederCountryTest` (6), `LeagueTableReconciliationServiceTest` (4), `CountryCupFixtureScopingTest` (4),
  `LeagueScheduleGetDoesNotWriteTest` (2), `CountryCatalogQueryCountTest` (3), `CountryTeamPlayersDisclosureTest` (3).
- **Every guard test written this session was proven able to fail** by reverting the fix, except two that were
  caught in that state *by the mutation itself* and one (`JobTriggerUniquenessTest`'s day+key case) that is
  subsumed by the stricter key check.

### The two patterns worth keeping

**Six guard tests this session passed against the code they were written to catch** — a statement count, a
Hibernate counter, a session count, colliding random ISO codes, a fixture with no competition entries, and a
fixed expected answer. Every one had the same cause: **the assertion was written before checking what the code
requires to be true for the behaviour to exist.** The mutation check is not ceremony; it is the only thing that
found them.

**Four board descriptions did not survive contact with the code** — C1 (blamed a LAZY relation), C5 (called an
`authenticated()` endpoint world-readable), A5 (described a bug that is deliberate, tested, and whose fix would
freeze the season), and B11 (rows that are not in the database). The board describes *intent* more often than it
describes *code*.
