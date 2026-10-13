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
| Run | `java -jar target/footballmanager-1.0-SNAPSHOT.jar --app.open-browser=false` — **the flag is mandatory from a shell.** The IDE `main()` keeps the default. |
| psql | `/Applications/Postgres.app/Contents/Versions/18/bin/psql -h localhost -U postgres -d sokker_db` |
| Browser | `agent-browser` (on PATH above). Login `velibor@example.com` / `A12345!` |
| API token | `curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' -d '{"username":"velibor@example.com","password":"A12345!"}'` → `.token`. **The field is `username`, not `email`.** |

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

- Database restored from `backups/2026-10-11-01-31-52.dump` (14,976 teams, 1,508 competitions, 33,612
  players). That dump **has no `last_synced_at` column** — `ddl-auto=update` adds it after restore.
- Clock has been advanced to roughly **season 1, week 5**. It has run under both a broken and a repaired
  job runner, so **do not trust current figures without a fresh dump.**
- Safety dump taken this session: `backups/pre-cupfix-20261013-164421.dump`.
- `var/tactics-editor-profiles.json` is a **tracked runtime file that always shows as modified. Never
  stage it** unless the task is about tactics data.

---

## 4. What was fixed this session (all committed to `main`)

Newest first. `git log --oneline` is authoritative.

| Commit | What it fixed |
|---|---|
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

### P0 — correctness

**P0-1 · Verify one matchday completes clean, end to end.**
The duplicate-discipline fix (`f3d0c604`) is committed, tested by mutation, and the offending rows are
deleted — but **no matchday has been watched completing since.** Two jobs failed during the catch-up
(`matchday-prepare-1`, `matchday-publish-1`, week 3 day 1 hour 23). Nothing new has failed since, which is
weak evidence. Advance to a matchday, watch `job_run`, confirm zero FAILED.

**P0-2 · Cup round 2 has never been drawn.**
Only round 1's 54 ties exist. The draw fires at **week 2 day 2, 08:00** and we are past that, so it needs
either the repaired draw path or a wind-back. `CUP_WEEKS = {1,2,3,4,5,7,8,11}`; `ENTRY_ROUND_TEAMS = 108`
and the owner's own rule is in the constant's comment: *ranks 1-202 join the winners in week 2* → 256
entrants → **128 ties**. Verify 128, not 129.

**P0-3 · The admin "Re-draw the cup" button is not idempotent.**
`seedIfMissing()` **added 54 duplicate round-1 ties on every press** — all 108 teams ended up drawn twice.
Its Javadoc claims the opposite. I removed the duplicates by hand. **Do not press that button** until
fixed. It is the reason P0-2 is not simply "click the button".

**P0-4 · `/admin/world-reseed?what=national-teams` is broken** — fails on a `player` foreign-key
violation, unrelated to the above.

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

---

## 8. If you only do one thing

Verify **P0-1**, one clean matchday. Everything else this session rests on the claim that the repaired job
runner actually runs jobs on the clock path, and that has been true in pieces but never watched end to end.

Second: close **P1-1** by generating a real training report. It is thirty minutes of clock-walking and it
converts "built and tested" into "seen".
