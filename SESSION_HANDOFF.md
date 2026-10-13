# SESSION_HANDOFF — read this first, in this order, before touching anything

**Written 2026-10-13, at the end of a long session. A blank session should be able to pick up from here
without reading the 13,000-line progress log.**

---

## 0. The three rules that were learned the hard way

1. **A job is not done until it has been seen to change data.** Not logged — observed in PostgreSQL
   afterwards. Several things in this repo are "committed" and still unproven; they are marked below.

2. **A test that cannot fail proves less than no test.** Every guard added this session was verified by
   deliberately breaking the code and watching it fail. Do the same with anything you add. Three tests in
   this repo were green while measuring nothing.

3. **Static checks are not proof.** `node --check` returned `PARSE OK` on a file the browser refused to
   execute (`const` redeclaration). Load the page. Query the database. Do not trust a green build alone.

---

## 1. Environment — this is the first thing that will bite you

```bash
export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:/usr/local/opt/libpq/bin:/Users/velja/.npm-global/bin:$PATH"
```

| | |
|---|---|
| Build | `mvn -o clean package -DskipTests` — **always check the real exit code.** `grep ERROR` after a piped `mvn` returns grep's status, not maven's. This mistake shipped a broken jar once. |
| **Run the app** | **`./run-app.sh`** — see below. **Never `mvn spring-boot:run` directly**, and never `java -jar …` as a substitute. |
| psql | `/Applications/Postgres.app/Contents/Versions/18/bin/psql -h localhost -U postgres -d sokker_db` |
| **Browser** | **`agent-browser`** — see below. Login `velibor@example.com` / `A12345!` |
| API token | `curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' -d '{"username":"velibor@example.com","password":"A12345!"}'` → `.token`. **The field is `username`, not `email`.** |

### Starting the app — `./run-app.sh`, always

```bash
./run-app.sh                 # foreground, does NOT open a browser
./run-app.sh --with-browser  # opt in to the browser for one run
```

**This is the only supported way to start the app from a shell** (owner, 2026-09-29). It runs
`mvn spring-boot:run` with `--app.open-browser=false` for you and strips its own flag before Maven sees
it. `BrowserLauncher` opens `http://localhost:8080/login.html` on every startup, which is right when the
owner runs `main()` from the IDE and wrong for any shell — and **the application cannot tell those two
cases apart**, so the default stays `true` for the IDE and the shell opts out through this script.

**Do not "fix" this by flipping the default in code.** It was briefly opt-in, and the owner's IDE start
then did nothing, silently.

An earlier version of this handoff told you to run
`java -jar target/footballmanager-1.0-SNAPSHOT.jar --app.open-browser=false`. **That is not what the owner
asked for and it is not what you should do.** It also loses the `--with-browser` opt-in and leaves the app
running from whatever terminal started it, with no log file to read.

### Browser work — `agent-browser`

```bash
export PATH="$HOME/.npm-global/bin:$PATH"    # NOT on the default shell PATH — this bites first
agent-browser open http://localhost:8080/login.html
```

`agent-browser` is at `/Users/velja/.npm-global/bin/agent-browser`. It is **not on the default PATH**, so
`agent-browser` alone gives `command not found` until you add it. Login `velibor@example.com` / `A12345!`.

**It is a SPA.** `location.pathname` never changes, so to see what a page rendered read
`document.getElementById('main-content')` — **not** `document.body.innerText`, which starts at the top bar
and reads as though the page is empty.

**But the element id is per page.** The in-game SPA pages have `#main-content`; the **Sports Lobby does
not**, so `getElementById('main-content')` returns `null` there and looks like a dead page when it is
working. `agent-browser snapshot -i` works on every page and is the safe first move.

```bash
agent-browser snapshot -i                                        # what is on this page, anywhere
agent-browser eval "document.getElementById('main-content')?.innerText"   # in-game pages only
```

**A page that has not been opened in a browser has not been verified.** Four items on this board are
committed, tested and unverified for exactly that reason. Any exit criterion that says "in the browser" is
answered with `agent-browser` and a screenshot, or it is not answered.

**Playwright tests in `newLogic/service/` need a running app and will hang a full suite. Never start one
on the way to something else.**

### Clock control — you need this constantly

There is **no** `week` unit. Only `hour(s)` and `day(s)`.

```bash
curl -X POST "localhost:8080/api/game-clock/advance?unit=days&amount=7" -H "Authorization: Bearer $T"
```

The walk runs **asynchronously** on a background executor, so the response's `jobs.ran` is usually `0`
even when work follows. **Wait 2–4 minutes, then query `job_run`.** Do not read the response as the result.

> **Trap:** training runs on **day 4** and finance settles on its own day. Advancing `days=3` from day 1
> steps *over* day 4 and no training report is ever produced. To hit a specific window, advance in
> `hours`, not `days`.

---

## 2. 🔴 The single most important thing to know

**`game_clock`'s `current_week` / `current_day` / `current_hour` / `current_season` columns are DEAD.**
They are leftovers from the old two-clock design, rebuilt out 2026-10-11. Nothing reads them.

The real clock is **`now() + advance_offset_seconds`**, and position is a pure function of it
(`GameTimeline.positionAt`). To move the world you change the offset.

**I read those dead columns and reported a phantom "clock mismatch" twice before catching it. Do not
repeat that.** Always check position through the API (`/calendar/week`, `/api/server-time`) or the offset.

Related: `GameClockService.synchronise()` is a **catch-up runner**, not a time source. It never advances
the clock and it only logs when a job actually ran — silence means "nothing was due", not "broken".

---

## 3. Where the world stands right now

- Database `sokker_db`, 14,976 teams, **3,472 matches**, 5,708 fixtures. Clock stands at
  **season 1, week 5, day 7, hour 22**.
- **Week 5 day 7 was played end to end on 2026-10-13** — the 155-fixture matchday P0-1 was waiting on. It is
  the reference example; `kanbanProgress.md` has the timings and the before/after numbers.
- **824 fixtures are stranded in the past** (weeks 4-5, days 1/3/5/7). See P0-5. The world is otherwise
  consistent, but those ties will not be played by anything that exists today.
- The cup looks drawn: **128 fixtures at week 2 day 5**, which is the target, plus 32 at week 4 day 5.
- Safety dump taken on 2026-10-13 before the cup work: `backups/pre-cupfix-20261013-164421.dump`.
- `var/tactics-editor-profiles.json` is a **tracked runtime file that always shows as modified. Never
  stage it** unless the task is about tactics data.

**The app is usually already running, from a terminal the agent cannot see, so it has no log file.** If a
task needs the job log, restart it through `run-app.sh` with the output redirected:

```bash
nohup ./run-app.sh > /tmp/tifo.log 2>&1 &
```

`job_run` is the honest record and is enough for most things; the log is only needed to read a stack trace.

---

## 4. What was fixed this session (all committed to `main`)

Newest first. `git log --oneline` is authoritative.

| Commit | What it fixed |
|---|---|
| *(this session)* | **P0-1 closed** — a whole 155-fixture matchday watched end to end. **P0-6** — the lock named the manager's own club back at him; found live, not by a test. |
| `9a801c0a` | Training report names the facility it trained under (`facilityLevel` / `facilityFactor`, a **Facility** column). **Not yet seen in a real report.** |
| `6796dc3b` | **Corrected a false claim** — all four facility levels *do* act; only the `tacticalMultiplier()` method is dead. |
| `0ed5576e` | T-ECON-1 owner decision: OFK Omladinac is the exception. Bill 18,478,000 → **498,148**/wk (top of tier-1; tier-1 max excluding it was 498,148, median 435,591). Contracts synced. **A settled week at the new bill is not yet observed.** |
| `f3d0c604` | Two duplicate `player_discipline` rows were failing **whole matchdays** (`NonUniqueResultException`). Now count-based + oldest-row-wins. Test proven by mutation. Duplicate data deleted. |
| `3b758f45` | **P0: a clock watermark ahead of game time killed every scheduled job, silently.** `walk()` now repairs it. Verified by reintroducing the drift. |
| `d6f07217` | The club ranking is fed by the **ranking-points ledger**, which was only rebuilt on the async batch path — a second instance of the same dead-ledger bug. |
| `56133fec` | The four UI asks. 2 of 4 verified in a browser; 2 committed but **never seen running**. |
| `088704c5` | Club Elo now moves: incremental resume via `rating_watermark`, hooked into the reconcile job. `order()` 20 → 90. |
| `2dfb1720` | A `const` redeclaration took down `loadPage` entirely. |
| `42fc45c7` | National teams were being drawn into the club cup (Serbia + Serbia U-21), making round 2 = 258 entrants / 129 ties. |

### The bug chain worth understanding

Three ledgers were dead and shared **one** cause plus two siblings:

1. The job runner was switched **off entirely** by `last_synced_at` being ahead of game time
   (`3b758f45`). Silent — the app looked healthy.
2. `ClubRatingService.recomputeDurably()` had exactly two callers; the clock-driven matchday was neither.
3. `RankingPointsRebuildService.rebuildAfterBatch()` — same two callers only. So the ranking list, which is
   **not** ordered by Elo at all, showed a flat `1500.00` for all 310 Serbian clubs.

**Verified working end to end afterwards:** 10 distinct point values in the first 10 rows, 10/10 rated,
top of the Serbian list `GFK Zemun — Superliga Srbije — tier 1 — 1622.50`, confirmed in a browser on the
Clubs tab with `scrollY === 0`.

---

## 5. Remaining work, in the order I would take it

### Owner decisions taken 2026-10-13 — both scheduled for the next session

**P0-8 · Re-draw Kup Srbije — the owner chose option (b).**
*"ovo je testna baza, onemoguci za ubuduce da upadne nt tim i sve ok."* Void Serbia U-21's four ties, then
press the redraw button **once** — it is idempotent as of P0-3. It redraws **round 1 only**; it is not a
"reset the tournament" control. The **prevention** half is already done and mutation-proven:
`noNonLeagueClubIsEverDrawnIntoACup` refuses a senior side, a U-21 side, and a club misfiled into a CUP.

**The tiebreaker — *pts, gd, zreb*, everywhere.** *"da, ok je global."*
`LeagueTableOrder` is *pts → gd → goals scored → team id* and becomes *pts → gd → draw*. **Do not invent a
draw**: `RankingTieBreakService.coin(seed, teamId)` is already the owner's rule — a stored per-ladder coin,
replayable, in use on the country and club ranking lists. Make the comparator take a coin. It touches every
league table in the world, so give it its own task and its own test.

| # | Pri | Task | State |
|---|---|---|---|
| **P0-8** | 🔴 | **Re-draw Kup Srbije, U-21 out** | **Owner chose (b).** Prevention already proven; the re-draw is left to do. |
| **P0-5** | 🔴 | **824 fixtures sit in the past and will never be played** | **Needs an owner decision** — (a) write off, (b) publish at once, (c) catch up from `match_date`. |
| **TIE** | 🔴 | **Tiebreaker becomes pts → gd → draw, globally** | Owner approved. Needs its own task and test. |
| **P0-7** | 🔴 | **The qualifying race had no tie-break** | ✅ **Fixed** — the union of two sorted pools was never sorted. |
| **P0-9** | 🔴 | **The national cup had no final** | ✅ **Fixed** — 8 rounds where the owner's table has 9. |
| **P0-3** | 🔴 | **The redraw button was not idempotent** | ✅ **Fixed** — it asked "any *unplayed* ties?". |
| **P0-6** | 🔴 | **The lock named the wrong club to an away manager** | ✅ **Fixed** — the home id compared with itself. |
| **P0-1** | 🔴 | **Verify one matchday end to end** | ✅ **Done** — 155 fixtures, zero new FAILED. |
| **P0-2** | 🔴 | **"Cup round 2 has never been drawn"** | ✅ **Premise was stale** — it is drawn, 128 ties. |
| **P0-4** | 🔴 | `/admin/world-reseed?what=national-teams` | Broken — `player` FK violation. |

### P0 — correctness

**P0-8 · The national side Serbia U-21 is in Kup Srbije, rounds 1 to 4.** *(found 2026-10-13)*
It **won three knockout rounds** (4-0, 3-1, 3-2) and is in the unplayed round 4. `42fc45c7` stopped national
teams being *drawn* into the cup, but that filter only governs future draws and this bracket predates it.
**`SK Kikinda` is consequently not in the cup at all** — 309 of Serbia's 310 clubs are, it should be 310.
Options (a) let it play on, (b) void U-21's ties and re-draw from round 1, (c) substitute Kikinda. **(b) is
the honest one** and is now safe because P0-3 is fixed — but it discards played football, so **needs the
owner.** Do not get there by pressing "Re-draw the cup": that button redraws round 1 only and is not a
"reset the tournament" control.

**P0-5 · 824 fixtures are stranded in the past.** *(new, 2026-10-13)*
`walk()` is forward-only — `steps = Math.max(0, Math.min(delta, MAX))` — so an hour it skips is gone for
ever. Weeks 4 day 5, 4 day 7, 5 day 1 and 5 day 3 have fixtures and **no `job_run` row for their matchday at
all**. Caused by the watermark repair (`3b758f45`) and a 72-hour offset rewind, both of which discard hours
silently. **Options (a) write off, (b) publish at once, (c) catch up from the fixture's own `match_date`**
are set out in `kanban.md`; (c) is the one that fits the design. **Needs the owner.**

**P0-7 · The qualifying race had no tie-break at all.** ✅ **Fixed 2026-10-13.**
Owner-reported off the screen: a 14-point club ranked above a 16-point club. The Masters race is
`poolAt(1) + poolAt(2)` — two independently sorted lists concatenated, the union never sorted — so every
2nd-placed club outranked every 3rd-placed one **whatever their points**. Only Masters was affected;
Champions (`poolAt(0)`) and Challenge (`poolAt(3)`) are single sorted pools. Verified on all three cups in
all five tiers and in the browser. **One question left for the owner:** he said *pts, gd, zreb*, and
`LeagueTableOrder` is *pts, gd, goals scored, team id*. Changing the third key is global, so it is untouched.

**P0-9 · The national cup had no final.** ✅ **Fixed 2026-10-13.**
`CUP_WEEKS` read `{1,2,3,4,5,7,8,11}` — eight rounds, so no final, and the last round three weeks after the
quarter-final. The owner's nine weeks are `{1,2,3,4,5,7,8,9,10}`. `InternationalClubCupDraw.CUP_WEEKS` was
**already** correct, which is the only reason this survived: the national cup had nothing to be compared to.

**P0-3 · The admin "Re-draw the cup" button was not idempotent.** ✅ **Fixed 2026-10-13.**
It guarded with *"are there **unplayed** round-1 ties?"* — a played round has none, so the guard concluded it
had never been drawn and every press entered all 108 teams again. Both that guard and the scheduled one now
ask *"has this round been drawn?"*. The button is safe to press. It still only redraws **round 1**.

**P0-6 · The lock named the wrong club to an away manager.** ✅ **Fixed 2026-10-13.**
`MatchLockService.refusalReason` compared the home team's id with **itself**, so it always named the away
side — right by accident for a home manager, wrong for every away one, which reads *"Your match against
OFK Omladinac…"* to a manager whose club **is** Omladinac. Fixed by passing the club being refused
(`refusalReason(fixture, askingTeamId)`), six call sites. Found live during P0-1, **not** by a test — the
test asserted `$.reason` `isNotEmpty()`, which would pass against a message naming `null`.

**P0-2 · "Cup round 2 has never been drawn."** ✅ **Answered — the premise was stale.** Round 2 **is** drawn
with **128 ties**, which is the owner's arithmetic exactly (54 winners + 202 direct = 256). The real
remaining defect is P0-8 above.

### P1

**P1-1 · T-FAC-1, item 2 — one observation short of verified.**
Code and tests are done (`9a801c0a`, 40 green incl. `StadiumFacilityLevelTest`). To close it: advance the
clock **hour by hour** into a day-4 window, then check
`select report_json::jsonb->'players'->0->>'facilityLevel' from training_week_report where team_id=1`.
Until then the board item stays **unchecked**, correctly.

**P1-2 · T-FAC-1, item 3 — settlement and stadium page must agree on upkeep.**
`weeklyUpkeep = 120 × (sum of levels − 1)`, so four facilities at level 1 genuinely cost 0 (not a bug).
Unproven: that the amount the weekly settlement charges equals what the stadium page shows. Needs a test
asserting the two agree.

**P1-3 · T-FAC-1, item 4 — document the `JuniorDevelopment` overlap.**
`JuniorDevelopment.weeklyWeightChange` applies `gymLevel` to **weight change**, while
`Stadium.trainingFactorFor` applies it to **stamina/fatigue growth**. Same level, two effects, no comment.
Documentation only.

**P1-4 · T-FAC-1, item 5 — bot seeding.**
Seeded by reputation across the **310** grounds that exist (levels 2–5 on a 1–20 scale, which reads as
"barely built"). The other ~14,666 clubs have **no stadium row at all**. Either widen the range or display
"not yet" rather than `1/20`.

**P1-5 · Delete `TrainingFacilityService.tacticalMultiplier()`.**
No caller, and it duplicates an effect `tacticalLevel` already provides through `trainingFactorFor`.
Wiring it would apply the tactical level **twice**.

**P1-6 · T-U21-1 — a national side drawn short was short for ever.** Not started.

### P2

**P2-1 · T-KIT-1 — the players wear no kit.** Nine unchecked criteria in `kanban.md`: club picks shirt/
shorts/socks + number style; rendered wherever a shirt is drawn; separate keeper kit; no two clubs the same
colour with a clash rule; away alternative strip mirrored the way tactics are; readable default; tests that
fail on the old code.

### Waiting on the owner

- **The delta column** on the club ranking (deferred until the feed was live — it now is). Deliberately
  last: it would have rendered zero forever and looked like a working feature.
- **T-ECON-1 option 2** (global wage re-scaling across 31,230 players) was declined in favour of option 1.
- Nothing else is blocked on them right now.

---

## 6. UI items that were committed but never seen running

`56133fec` shipped four UI changes. **Only two were verified.** Do not assume the other two work:

| Item | Status |
|---|---|
| "Not drawn yet" → reads `Drawn · 600 ties` | ✅ verified in browser |
| Scroll to top on every page (`scrollY === 0`) | ✅ verified in browser |
| Cup rounds newest-first + own-tie highlight | ❌ **never seen running** |
| Competition-type filter (League/Cup/International) | ❌ **never seen running** |

They build and `node --check` passes, which is exactly the bar that let a broken file through once.

---

## 7. Traps that cost real time this session — read before debugging anything

1. **Dead `game_clock` columns.** See §2. Cost two phantom bug reports.
2. **The clock walk is asynchronous.** `jobs.ran: 0` in the response does not mean nothing ran. Wait, then
   query `job_run`.
3. **`node --check` passed a file the browser rejected.** Always load the page.
4. **`mvn … | grep ERROR` returns grep's exit code.** A build failure looked like a success and a stale jar
   got deployed.
5. **`REQUIRES_NEW` hides writes from the test that made them.** `ClubRatingService.recomputeIncrementallyDurably()`
   commits on a separate connection, so a `@Transactional` test asserting on its own entity handles reads
   **pre-call values and passes for the wrong reason.** Those tests must be non-transactional and reload via
   `em.clear()`.
6. **`Optional`-returning repository methods throw on duplicate rows** — and the throw can happen inside
   match simulation and kill a whole matchday. Ask the question the caller actually has (a count).
7. **Circular dependency, symptom points elsewhere.** `GameClockService` owns `JobRunner`, which owns every
   `DayJob`. A job depending on anything reaching back into the clock makes the whole app fail to start with
   an error naming `APIController`. New deps on jobs are `@Lazy`.
8. **Spring JPQL uses the field name, not the column.** `d.playerId` fails; the entity had `player` →
   `d.player.id`. This broke the entire Spring test context.
9. **A destructuring array shorter than its `Promise.all` silently throws data away** — and binding a name
   twice in one scope is a `SyntaxError` that kills the module.
10. **agent-browser is a SPA.** `location.pathname` never changes. To see a page, read
    `document.getElementById('main-content')` — not `document.body.innerText`, which starts at the top bar.
    I wrongly reported the country page as dead because of this.
11. **Resetting `current_day`/`current_hour` does nothing** — they are not read (see §2).
12. **Only `APIController` lives under `/api`.** `MatchController` is `/matches/…`, `TeamController` is
    `/teams/…`, the sim controllers are `/sim/…`. Hitting `/api/matches/3359` returns a 404 that says
    **"No static resource"** — which looks exactly like the publication guard working and is not. **Read the
    404 message before believing a guard**: "No static resource" means the URL was wrong, not that the guard
    fired. I nearly recorded a false pass because of this.
13. **`zsh` does not word-split an unquoted parameter.** `PSQL="psql -h localhost …"` then `$PSQL -c "…"`
    looks right and runs as one giant command name. Use an array or a shell function. This cost ten minutes
    of a polling loop printing an empty backlog.
14. **`sum(elo_rating)` is invariant.** It read 17,615,987 before and after a 155-match matchday — that is Elo
    being zero-sum and working, **not** a stalled replay. Check per-club `elo_rating` against
    `elo_previous_rating`, and check that the direction agrees with the result.
15. **🔴 A `kill` that is not confirmed is not a stop.** `kill <pid>` on the running app **did not take**,
    the old JVM kept port 8080 and kept serving **pre-fix bytecode**, while the new one's log said
    *"Nothing to compile — all classes are up to date."* Two rounds of "verification" showed the old wrong
    answer and I nearly recorded a fix that had changed nothing. After any restart:

    ```bash
    kill -9 <pid>; sleep 3
    lsof -nP -iTCP:8080 -sTCP:LISTEN        # must be empty BEFORE starting again
    ./run-app.sh &                          # then wait for it to bind
    lsof -nP -iTCP:8080 -sTCP:LISTEN -t     # the serving pid must NOT be the old one
    ps -o pid,lstart -p <newpid>            # and must have started just now
    ```

    **A second instance failing to bind looks exactly like a working first one.**
16. **🔴 `mvn test` under a live `spring-boot:run` makes new classes invisible.** `run-app.sh` runs
    `mvn spring-boot:run`, which **indexes `target/classes` at startup**. A class compiled afterwards is on
    disk and still cannot be loaded — it is not in the index. Measured on 2026-10-13: the app started at
    **00:02:29**; `RankingPointsEngine.class` was written at **00:12:34** by a `mvn test` run; the endpoint
    answered `500 NoClassDefFoundError` while the source and `target/classes` both held the class, and the
    page told the owner *"This country has no clubs yet"* for a country with 310 of them.

    **After any `mvn` compile, restart the app.** The test run and the running app share one build
    directory, and `run-app.sh` compiling for you is exactly why this is easy to get wrong.
17. **`agent-browser click` does not take a CSS selector** — it wants an `@ref` from `snapshot -i`. To click
    something found by query, drive it: `agent-browser eval "document.querySelector('…').click()"`. A
    `click "[data-…]"` that quietly does nothing looks exactly like a broken handler.
18. **`node` is not installed**, so `node --check` cannot be used and its "PARSE OK" was never evidence.
    Load the page.

---

## 8. If you only do one thing

**P0-5: decide what happens to 824 stranded fixtures.** It is the only thing found this session that is both
open and needs the owner rather than an agent. It is a small decision — write them off, publish at once, or
catch up from the fixtures' own kickoff times — and P0-1 is already in the database proving that everything
downstream of a correctly played matchday works.

Second: close **P1-1** by generating a real training report. It is thirty minutes of clock-walking and it
converts "built and tested" into "seen".
