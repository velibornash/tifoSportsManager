# kanbanProgress.md — the append-only log

**One entry per task, newest first, each carrying the commit that landed it.**

This file holds no plan and no board — that is `kanban.md`. It holds **what was actually done, what was
measured, and what did not work.** The failures are the point: a measured dead end is worth more than a
silent one, because the next session will otherwise try it again.

**A baseline is only comparable to a baseline measured the same way.** The Maven `[ERROR]` summary prints
at the *end* of a run, so a killed run reports a different set of failures than one allowed to finish. Two
numbers are only comparable if both were allowed to finish.

**A green status is not evidence.** The recurring failure in this codebase is code that reports success
while doing nothing. Where a test passed suspiciously, the entry below says whether it was broken
deliberately to check.

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

## 2026-10-03 — P1-7: the per-tick event log, and the two Elo replays that could not run

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