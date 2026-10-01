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

*Nothing. The last task — settling `dataFixSuggestions.md` §1.1 — is done and committed.*

---

## 📌 NEXT SESSION — start here

**Read the INGESTED section above first.** Two documents landed on 2026-10-01 and both reorder this board:
`experAudit01102026.md` and `COMPETITIVE_ANALYSIS.md`. Between them they put a correctness cluster
(schedulers, the game clock, who may touch them) **above every feature on this board**, and one item —
**A4, the season counter skipping a season on every rollover** — above everything because it is three
lines and it permanently corrupts data.

Both documents agree, and it is worth saying plainly: **the world grew 48× in five days and the code that
schedules it was written for one country.** Everything in cluster A is that sentence.

### 0. Owner decisions needed before anything else

1. **Confirm the cluster-A ordering.** It is two documents' opinion, not yours, and it reorders a board you
   have been working from.
2. **`DefensiveShape`** — the engine now derives its out-of-possession shape arithmetically and that wins on
   every lookup, so it defends in a different shape than it attacks with no data edit. Both documents say
   this needs an owner ruling, not a code change.
3. **`/demo` routes** — still waiting on you. Seven of the thirteen unreachable pages fetch fake data from
   `DummyDataController`; the audit's verdict is that the controller is "the thing to delete", which makes
   the decision easier rather than harder.
4. **`Network error during authFetch` on Oracle** — still not diagnosed. Re-check first: if it is still
   happening with the server definitely up, it outranks cluster A.

### 1. Cluster A — the scheduler and the clock (P0)

`A4` first (three lines, permanent damage), then `A1` + `A2` together (a guard that works and a re-queue
that exists), then `A3` (a role check that exists only in the browser), then `A6` (delete 186 orphaned
lines) and `A5` (168 transactions that should be one).

### 2. Cluster C + E2 together — the security surface has no tests

Five reachable defects and nine untested controllers. Doing them in one pass is the only way the tests can
be written against the fixes rather than after them.

### 3. Club ratings — the two ranking tables ✅ **done**

The columns, the replay and the seeding landed in `c46786f`. The two tables that read them landed after it,
and both are now live:

| Table | What it shows |
|---|---|
| **League table** | `Elo` and `±` columns after Pts. `±` is signed and always printed — `+12`, `−17`, `–` for no movement, `—` for never rated |
| **Country ranking list** (World) | A plain ordinal in the row, sorted by rating, and **the whole row is the link** |

**The country row is no longer a button wrapped around the name.** It was the reason the list read as 48
names rather than a ranking: no position column anywhere, a hit area of the text alone, and a row that
could not be tabbed to. Now the row carries the target, has `tabindex`/`role="link"`, **and handles Enter
and Space** — a `<tr>` with `role="link"` does not get that for free, and a row that looks clickable but
cannot be reached from the keyboard is a worse regression than the one it replaced.

**One honest state worth naming:** on a world whose replay has not run, every club reads `—`. That is
deliberate — **zero is a rating a club can legitimately hold**, so printing 0 would invent one. It also
means the columns are empty until the first matchday after the upgrade, because boot writes nothing and
there is no admin button for club Elo specifically. Whether that wants a button is an owner call.

**Verified:** `LeagueTableEloColumnsTest` 2 — real JWT, real league, asserts **values not keys**, because a
test checking `rating` exists passes happily with it permanently null. Plus `WorldPageNavigationTest` +2
static guards, both of which were **broken deliberately and watched fail** before being restored.

**Live, in the running app:** league table headers `# Club P W D L GF GA GD Pts Elo ±`, row 1 rendering
`… 3 1543 +12`, and the unrated clubs beside it rendering `— —`. The World list rendering 48 rows with
`1 | Australia | 1506 | Simulated` at the top. The ratings were then reverted, because hand-setting an
Elo in the owner's database is the same fabrication the ZOX page commits.

### 4. The international cup — data but no matches

Knockout progression to final and third place (B7), wire `CupFixtureSeeder` to a **named** cup rather than
the first CUP row (B2), give the draw an actual shuffle (B1), and delete the three stale global cup rows.
**`MatchFormat` before the group stage** (B8), or every level group match is settled by a shootout.

### 5. Verify `dataFixSuggestions.md` §1.2–1.5 before fixing any of it

§1.1 turned out to be **wrong** — recovery is committed, and the missing `save()` was never a lost write.
That document was produced by reading source, and three of its findings had a configuration where the claim
"passed" while measuring nothing. Treat the rest as unverified:

| § | Claim | Status |
|---|---|---|
| 1.2 | "Simulate all" can silently discard an entire league | unverified — plausible, real trade-off |
| 1.3 | `@Transactional` on `totalSides()` not `seedIfMissing()` in `NationalTeamSeeder` | unverified |
| 1.4 | `MatchPersistenceService` is dead code (402 lines, zero callers) | unverified — the audit agrees it has zero callers |
| 1.5 | `MatchEventRepository.save()` is a no-op | unverified — probably a design choice, badly named |

### 6. Smaller, still open

- National Team Qualifiers + World Cup (senior) — mechanism exists, competitions and formats do not
- U-21 as its own competitions with its own qualification — **not tabs on one competition**
- calendar-year fixtures (E3) — **before** the tactics wiring, per the audit
- reset-db deadlock against a running simulation
- match engine realism — **last, per the owner**, and re-baseline first: the numbers on that board were
  measured against code that has since changed

---

## 🔄 In progress

*Nothing. The result-hiding task is done and verified live.*

---

## 🔴 INGESTED 2026-10-01 — `experAudit01102026.md` + `COMPETITIVE_ANALYSIS.md`

Two documents arrived and both **reorder this board**. Read this section before the older tables, because
several rows in them are now superseded and two of them were wrong.

**The single most important thing in this section:** both documents rank the same four-item cluster above
everything else, and it is not features. `COMPETITIVE_ANALYSIS.md` §9.0 is a **new P0 that outranks every
other gap in that document**, and its own words are that it "did not exist on 2026-09-26 and it now
outranks everything below". The audit's §14 top-5 is four of these five items plus a deletion.

> **The world grew 48× in five days — 310 clubs became 14,880 — and the code that schedules it was written
> for one country.** Everything in cluster A is that sentence.

**Standing caveat, and it is the audit's own:** it ran no tests, no diagnostics and no queries. Every
figure in §6 and §9 is quoted or derived, not measured. Treat each cluster-A row as **a source-reading
hypothesis with a cited line**, and measure before fixing — the `dataFixSuggestions.md` §1.1 precedent is
that one of these documents' confident claims was simply wrong.

### Cluster A — the scheduler, the clock and who may touch them (P0, both documents)

| # | Task | State | Evidence |
|---|---|---|---|
| **A1** | **`job_run` guard is written after the job body**, so two concurrent advances both run it. The unique constraint saves the duplicate *row*, not the duplicate **work** | not started | `JobRunner.java:116` reads the guard unlocked, `:140-141` runs and commits the body, `:145` writes the guard in a second transaction. The class javadoc at `:26-28` claims the opposite. Fix shape: insert `PENDING`, then flip to `DONE` — a pattern the audit notes is **used nowhere** |
| **A2** | **`FAILED` is terminal and there is no re-queue.** Four files promise an operator can re-queue; `JobRunRepository` has no reset method | not started | `JobRunner.java:124-130` returns on `FAILED`. One transient throw disables that job for that (season, week, day) — and a `MatchdayJob` failure means **a whole matchday is never played and never retried** |
| **A3** | **Any authenticated user can advance the whole world**, and `amount` is unvalidated | not started | `SecurityConfig.java:140` is `.anyRequest().authenticated()` with no role; the admin check exists **only in the browser** (`dashboard.js:205-207`). The codebase already has the rule written down: *"A hidden button is not a permission"* (`CountryController.java:210`). `amount = 2147483647` runs a 2.1-billion-iteration loop; `amount * 24` overflows negative above 89,478,485 |
| **A4** | **The season counter is incremented in two places, so every rollover skips a season** — 12 → 14 → 16 | not started | `GameClockService:152-155` wraps the season, and `SeasonService:605` increments it again independently. `getOrCreateClock:83-91` only rescues `> 1000` or `< 1`, so the skip is permanent. The audit calls it *"a 3-line fix that will silently corrupt every future season number"* |
| **A5** | **`advanceWeek()` is 168 independent transactions.** Self-invocation bypasses the proxy, so `advanceHour`'s own `@Transactional` is inert | not started | `GameClockService:230-232` self-invokes `:237`, which self-invokes `:139`. A crash at step 100 leaves the clock five days on with 100 hours of jobs applied and 68 not, and no reconciliation |
| **A6** | **Delete `AdvanceWeekAsyncService`** — 186 lines, **0 callers**, and the last holder of the legacy inline week | not started, safe | `grep -rn AdvanceWeekAsyncService src/main` returns only its own declaration. It hardcodes `findById(1L)` and a Serbia-only loop, and five of its operations **also exist in the job path** — wiring it back would double-apply the week. Ranked **#5 overall** by the audit |
| **A7** | **`MatchdayJob` runs one query per competition** — and there are now 16 CUP competitions, so a cup matchday issues **16 identical full-table queries** | not started | `MatchdayJob.java:91-98` calls `findUnplayedOnDay` **inside** the `flatMap`. 1 national cup + 15 per-tier international cups. The audit calls it *"the worst query pattern in the framework, and it worsens with every tier added"* |
| **A8** | **`buildPlayoffSummary` names the wrong clubs** — it hardcodes positions 9-10 while the code that actually moves clubs computes them dynamically | not started | `SeasonService:910-918` vs `:800-802, 816-822`. Any league that is not exactly 10 clubs with 2 below it gets the wrong names. **This is the one previous-audit finding the new audit actively escalates** |

**A4 first.** It is the smallest item here and it is the only one that permanently damages data: every
season number after the first rollover is wrong, and no later fix can recover them.

### Cluster B — the world and its competitions (P0/P1)

| # | Task | State | Note |
|---|---|---|---|
| **B1** | **The national cup draw has no randomness at all.** `DRAW_SEED` is declared, assigned and **never read**; clubs are paired by list index after sorting by strength, so **the strongest club is drawn against the weakest in every round, for ever** | not started | `CupFixtureSeeder.java:62` (seed), `:98`/`:105`/`:115` (declared/assigned), `:394-436` (`drawRound`), pairing at `:412-414`. **Three javadocs describe a shuffle the code does not perform.** `Collections.shuffle` *is* used in `InternationalClubCupDraw:293-294`, which is why the international cups are random and the national one is not |
| **B2** | **The cup is hardwired to the lowest-id club's country, then gives up permanently.** `rankedClubs` takes the country from the first row the repository returns and discards the rest; the idempotency guard is `existing > 0` on round-1 fixtures | not started | `CupFixtureSeeder:178-190`, guard `:130-134`, give-up `:138-142`. "A Serbia dependency expressed as a `continue`." Related: `ENTRY_ROUND_TEAMS = 108` / `MAIN_DRAW_TEAMS = 256` only close because a country has exactly 310 clubs — **any country under 256 has no cup at all** |
| **B3** | **The international fixture seeder creates the competition, then gives up — and reports success.** It persists the row at `:75` and only checks `entrants.size() < 2` at `:99`; the guard is "does any INTERNATIONAL competition exist" | not started | `InternationalFixtureSeeder:66-103`. So the first boot creates it, draws nothing, and can never run again. `MatchdayJob` then finds it, finds no fixtures, logs at debug and is **marked DONE** — every week records a successful international matchday that played nothing |
| **B4** | **Cup and international fixtures are dated 2026-07-01**, hardcoded, with no season offset | not started | `InternationalFixtureSeeder:49`, `CupFixtureSeeder:56, 71-74`. Every season's round 1 gets the same date. The recovery window is `currentDate − 2 days` (`ZoneLoadService:69, 127`), so once the clock passes 2026-07-06 **no cup or international fixture ever falls inside it** — loads are written and never read, and `RecoveryJob` reports zero forever |
| **B5** | **`NationalTeamSeeder.squadsFor` is not idempotent** — no "does this side already have players" check, on a path that runs on both seeding branches and from five call sites | not started | `:149-193`. Compare `BotSquadGenerator.ensureSquad:71-77`, which has exactly that check. Up to **2,400 duplicate player rows per pass**, and each call costs `findClubTeamsForOperations()` — **96 full scans of the club table** |
| **B6** | **Continental qualification for 47 of 48 countries is alphabetical.** Every club in a division gets an identical reputation (it is a function of tier alone), then the table is sorted by reputation and **name** | not started | `PyramidBuilder:301-304` (`reputationFor(tier)`), sort at `:275-277`; read by `InternationalClubCups.qualifiedFor:238-239`, and `poolAt(...).sort(reputationOf)` at `:270` is a comparator over identical values. **This is the half of `COMPETITIVE_ANALYSIS.md` #14 that "matters"** |
| **B7** | **The international club cups cannot progress past their first knockout.** The `return` inside the `while` is **unconditional**, so `ROUND_QUARTER_FINAL` and `ROUND_SEMI_FINAL` are unreachable and `ROUND_THIRD_PLACE` is never referenced | not started | `InternationalClubCupDraw:289-304`, constants `:307`. Also: `ensureGroupStage` and `ensureKnockouts` have **0 callers**, and a small field returns from `buildGroupStage` promising a knockout that is then never drawn (`:271-273`). **Same territory as the NEXT SESSION item already on this board** |
| **B8** | **`MatchFormat` has zero callers** — and wiring the group stage without it would settle **every level group match by a shootout** | not started | `SimMatchService:222` + `isKnockoutTie:457-460` is `type == CUP`, whose own comment says *"A group stage would need a real flag."* **Ordering constraint: wire `MatchFormat` before the group stage, not after** |
| **B9** | **Internationals kick off 45 minutes early**, and day 6 has no job at all | not started | Job hour `20` (`MatchdayJobsConfig:30-31`), `WeekTemplate` kickoff `20:45`, `GameDay.kickoffHour()` `20`. The owner's schedule names day 6 *"form and morale"* and nothing implements it |
| **B10** | **`GAME_ZONE` is declared and dead**, and the in-game date moves by wall-clock time rather than by game time | not started | `GameClockService:59` declares `Europe/Belgrade`; `currentDate` is built with `ZoneOffset.UTC` (`:165`); `/api/server-time` reports Belgrade. Because `currentDate = now() + advanceOffset`, two owners clicking a week apart get different `currentDate` from the same 168 advances — and `ZoneLoadService:181-186` reads exactly that field |
| **B11** | **Delete the three stale global cup rows** | open | Already on this board from the 2026-10-01 session; repeated here because B1/B2/B7 all sit in the same file |

### Cluster C — security (P1, and it is *new* exposure)

`/api/**` is genuinely closed now (12-entry permit list). These five remain. **Nothing here is a
theoretical risk: all five are reachable by a logged-in manager.**

| # | Task | State | Note |
|---|---|---|---|
| **C1** | `GET /countries/catalog` is **`permitAll`** and calls `teamRepository.findAll()` | not started | `CountryController:163`. An unauthenticated 14,880-row load, each with EAGER `Country.clubs` and two EAGER `@OneToOne`. The endpoint was narrowed to names-and-flags; the query defeats that |
| **C2** | `GET /countries/teams/{teamId}/players` returns **raw `Player` entities** for any `teamId`, with no ownership or country check | not started | `CountryController:781-783`. Leaks every rival's squad including skills, contracts and injuries. `TeamController.getPlayers:176-193` is the correct sibling — mirror it |
| **C3** | `GET /countries/{iso}/cup/fixture/{fixtureId}` **discards its own authorization check** | not started | `CountryController:309-314` calls `requireCountry(isoCode)` and throws the result away, then loads by id. Any user reads any tie by guessing |
| **C4** | `GET /countries/leagues/{id}/schedule` **generates fixtures on a GET** | not started | `:669` |
| **C5** | `GET /api/jobs` and `/api/jobs/runs` are **world-readable** with no role check | not started | `APIController:103-127` |
| **C6** | Unvalidated `sortBy` into `Sort.by()` ×3; raw entity create with no validation and no auth ×2; `LineupController` raw `RuntimeException` → 500 with a leaked message ×2 | not started, carried over | `PlayerController:48-53`, `TeamController:95`, `LineupController:27`, creates at `TeamController:103-106` / `PlayerController:43-46`, leaks at `LineupController:35, 41`. Flagged in the 2026-09-26 audit and unchanged since |

### Cluster D — scale: what will not survive 14,880 clubs (P2, mostly unmeasured)

| # | Task | State | Note |
|---|---|---|---|
| **D1** | **Ten whole-table loads inside loops or on request paths** | one fixed this session | The `/countries/world` one is **fixed** — see the entry below. The other nine: `SeasonService:577`/`:594` (every player in the world, weekly), `:525`, `:1027`, `NationalRatingService:239` (**4× per international match**), `NationalTeamSeeder:151`, `CupFixtureSeeder:180`/`:218`, `SimulationController:235`, `PlayerZoneLoadRepository:24-26` (**no LIMIT, no pagination**), `ClubRatingService` (every played club match, per recompute) |
| **D2** | **Zone loads: ~1.47 M rows per game day, loaded whole into a `HashMap` once a day in one transaction** | not started | 22 players × 9 zones = 198 rows per match, saved one at a time (`ZoneLoadRecorder:156`); ~7,440 matches per game day. The commit that made this one query replaced *"16,354 round trips / 42 minutes"* — and the audit's judgement is that it **traded round trips for a result set three orders of magnitude larger**. The figures are **derived, not measured**; confirm against the live schema first |
| **D3** | **Whole-world day jobs.** `RecoveryJob` walks every player who ever played; `FinanceJob` settles every club, each in its own transaction | open, unchanged | This is the defect recorded under `274d3ff` in this file: *"the jobs are priced for a village, not a world of 716 clubs"* |
| **D4** | **264 job-guard lookups per game day** — `isDue` is `hour >= job.hour()`, so 11 jobs are evaluated 24 times each — plus a `gameClockRepository.save()` on a **read** at `SeasonService:98`, 168 times per week advance | not started | Arithmetic over the `isDue` comparison |
| **D5** | **Nine missing indexes and an ID strategy** | not started | Lives only in `dataFixSuggestions.md`; both documents point at it and neither enumerates it |

### Cluster E — the tests and the documents (P1, cheap)

| # | Task | State | Note |
|---|---|---|---|
| **E1** | **There is no recorded green run at HEAD.** The `791 green` figure is from `941cf5e`, **26 commits behind**, and 11 of those touched `src/test` | open | The audit's own most important caveat, and its recommended action is one command: `mvn test`, then write the number into this board. **879 `@Test` annotations is not a passing count** |
| **E2** | **Nine controllers have zero tests** — `Lineup`, `Player`, `Team`, `User`, `Admin`, `Community`, `DummyData`, `Competition`, `Stadium`. Only three tests exercise any controller | open | So **the whole cluster-C security surface is untested.** That is the reason to do C and E2 together rather than separately |
| **E3** | **Calendar-year test fixtures** — `WeeklyFinanceServiceTest`, `StaffSponsorServiceTest`, `PlayerContractServiceTest` pass 2024/2025/2026 as `seasonYear` | open, now ordered | Self-consistent under either scheme, so they pin nothing. **Explicit ordering constraint from the audit: fix these *before* the tactics wiring, not after** |
| **E4** | **Consolidate the documentation on this file and `kanbanProgress.md`** | part done this session | `AGENTS.md` was rewritten on 2026-10-01 (it described four classes that do not exist as the primary architecture). Still drifting: `sprintBacklog.md` says "722 passing" and twice says "Sprint 5 ◀ CURRENT"; `PROPOSAL_CURRENT_STATE.md` lists two P0s as OPEN that are solved and contains the same paragraph twice with **opposite statuses**; `PROPOSAL_SEASON_REPORT.md` is three calibration generations behind; `expertAudit.md` (2026-09-26) is superseded by `experAudit01102026.md` and should be marked as such |
| **E5** | **Five stub services and controllers still exist** despite `sprintBacklog.md` S6.4 recording them as deleted | open | `CompetitionController` (7 L, 0 routes), `StadiumController` (8 L, 0 routes), `LeagueService` (9 L), `TrainingService` (21 L, 0 callers, **holds an un-injected `playerRepository` that would NPE**), `StadiumService` (7 L). **The backlog is the stale document here, not the audit** |

### Cluster F — the product roadmap, in the order `COMPETITIVE_ANALYSIS.md` §10 gives it

The document's intake list, 39 items, "ordered by leverage per day of work, not by how fun the feature
is". **Reproduced rather than summarised, because the ordering is the roadmap.** Estimates are its own,
in dev-days. ✅ marks are re-verified in source on 2026-10-01.

| # | Feature | Est | State |
|---:|---|---:|---|
| 1 | **Wire the tactical grid to the engine** | 2–3 | open — **estimate cut from 5-8**: the engine already reads an identical copy of the editor's data (owner's clarification), so the hard problem is answered. See the notes below |
| 2 | Position-locked minute-proportional training with a 90-min cap; only the last match's role counts | 1–2 | open — intensity and minute-weighting are done; the hard cap and last-match role are not. "The cheapest remaining training feature" |
| 3 | **Player can refuse to be listed** — the anti-daytrade mechanic | 1 | open — the `objection` field now exists to support it |
| 4 | **Supporter mood (7) + supporter expectations (9)** | 1–2 | open — "now the cheapest win in the meta layer, since board trust computes but cannot act" |
| 5 | Listing fee as % of asking price | 0.5 | open — 2 lines |
| 8 | Graduation caps | 0.5 | open — "stops the academy flattening the economy" |
| 9 | 3 transfer advertisements per club; advertising unlocks skill visibility | 1–2 | open — `scoutedUnlisted()` and `ClubNeedService` cover part of it |
| 10 | **Zero-consequence exhibition mode** | 1 | open — "multiplies the value of 14,880 clubs" |
| 11 | Corners decided by grid geometry, not a nominated taker | 0.5 | open — and easier once #1 lands |
| 12 | Pitch dimensions as a tactical lever | 1–2 | open |
| 13 | A documented, blessed read API | 1–2 | open — `/api/**` is authenticated now, which was the precondition |
| 14 | World ranking points driving cup entry and draw seeding | 2–3 | **partly** — country Elo exists and is deterministic, but see **B6**: 47 of 48 countries qualify continentally by alphabetical order. "The half that is missing is the half that matters" |
| 15 | Coach/scout market with per-skill training ratings | 3–5 | **partly** — `StaffMember` exists and reaches the engine; **no market to hire from** |
| 17 | **Pre-match tactical preview** | 3–5 | open — `MatchController/{id}/preview` returns `Map.of()`. "Hattrick's single best idea", and the cheapest way to make the wiring visible on day one |
| 18 | Named tactics with derived levels | 3–4 | open — the 6 slider fields still have **zero readers** |
| 19 | Specialities + special events | 2–3 | open |
| 20 | In-match dynamics: pullback when leading | 1–2 | open |
| 21 | Weather + pitch condition | 1–2 | open |
| 22 | Man marking order | 2–3 | open — "the one genuinely novel Hattrick mechanic" |
| 23 | Experience + nerves in cup matches | 1 | open — "free drama" |
| 24 | Formation experience + confusion | 2 | open |
| 25 | Promotion/relegation qualifiers as a distinct phase | 2 | open — **⚠️ fix A8 first** |
| 26 | Board cash ceiling with a weekly release rate | 2–3 | open — "now cheap, because the ledger exists to model it" |
| 27 | **A retirement age** | 1 | open — "still the cheapest 1-day item on this list", and the highest-value item in the long game: without it neither the pyramid nor the market can turn over |
| 28 | Injury risk as a per-team-per-match budget | 1 | open — we generate **8 types**, which is richer than Hattrick's; the question is frequency, not existence |
| 31 | Facilities 1–20 | 3–4 | **partly** — upkeep is a real ledger line; no build UI |
| 32 | Faceted board confidence + **sacking** | 4–6 | **partly** — trust computes and displays; **sacking does not exist** |
| 33 | **Honest per-match analytics: xG, xA, PPDA, tilt** | 6–10 | open — "and now the largest single gap in the product". The differentiator is that only we can: a real spatial simulation makes these computable **from the same tick data that drives the animation**, so the numbers and the pictures cannot disagree, and determinism makes a figure recomputable and checkable |
| 34 | Scouting ranges that narrow with assignment (squad players) | 4–6 | **done for juniors**; squad players still exact |
| 35 | Set-piece routines with role categories | 4–6 | open — "FM's genuine tactical innovation" |
| 36 | Manager attribute drift + a Style Focus slider | 2 | open — "gives the manager a build" |
| 37 | A `Responsibilities` delegation screen | 2–3 | open — "attacks our worst risk (the grind)" |
| 38 | **Seller chooses which offer to accept** | 0.5 | open — `NegotiationService.acceptOffer(transferId, offerId)` exists and correctly rejects the rest, and **no controller exposes an `offerId`**. "The service can do it; the product cannot." Cheapest fix in the whole transfer area |
| 39 | Manager-to-manager messaging with a real client | 1–2 | open — chat and PM work but **there is no WebSocket client despite a STOMP config existing on the backend**. Sending a message re-downloads the whole feed and the whole user list |
| — | **Debt / interest / bankruptcy** | — | open — no debt entity, no interest accrual, no bankruptcy path. Neither competitor has a soft version either |
| — | **Prize money table has no caller** | small | open — `awardPrizeMoney` is implemented and nothing calls it |
| — | **Pitch quality tiers** | 1–2 | open — 6 tiers in Sokker; ours has upkeep as a ledger line and no tiers |

**Cancelled by the owner:** #29 individual training focus per player (4–6 d) — deleted as S4.1.
**Deliberately not a gap:** work permits / foreign limits — built in full, then removed by the owner
(`a6394f9`, *"No foreigner limit for now"*).

#### Three notes on the tactics item (#1), because it is the only P0 that is a feature

1. **The bridge already exists and is unwired.** `TacticsBridge.fromRuntimeMap()` has **zero callers**,
   and `NewLogicTacticsService.loadTacticRules(teamId, formation)` returns a default unconditionally,
   **discarding both of its own arguments**, and also has zero callers. *"Wire this one; do not write a
   third."*
2. **`DefensiveShape` may have silently superseded the 2026-09-26 possession-context ruling.** It derives
   the out-of-possession shape arithmetically and wins on every lookup, because all 506 shipped rule pairs
   are identical. The engine now defends in a different shape than it attacks **with no data edit**.
   **This needs an owner ruling, not a code change** — and the audit says so explicitly.
3. **What remains is 2-3 days of wiring, not a build:** read `TeamTacticsProfile` per team in
   `SimMatchService`, pass per-side `TacticsRules` into `MatchOrchestrator`, delete the raw-JDBC path and
   the `FORMATION` constant (still hardcoded `"4-4-2"` in two places), make `FormationSlotCatalog` serve
   the chosen formation (9 layouts exist, the engine applies 1).

### The ZOX "pre-match report" is a report about a match that has already been played

Found by the owner asking whether ZOX analytics was wired to the pre-match report. **It is wired to the
page, and the page is fiction.** This is worse than #17 above records, which only said the preview was
missing — it exists, and most of it is invented.

**Three preview surfaces, one with real arithmetic in it, and the ZOX page does not use it.**

| Surface | State |
|---|---|
| `/zox-match-preview.html` → `GET /api/zox/match-preview/{id}` | **Fabricated** — see below |
| `ScheduleInsightService` on the club and country **schedule** pages | **Real** — strength, form, a prediction. Wired at `TeamController:266,463` and `CountryController:70` |
| `GET /matches/{id}/preview` | **Empty stub** — `"prediction", Map.of()` and `"h2h", Map.of()` (`MatchController.java:157-168`) |

**The page is not an orphan.** `pages.js:619` and `:970` both navigate to it, and the page fetches all
three ZOX endpoints. So "is it connected" is yes, and the answer to the better question — *should a manager
see this* — is no.

| Field | Where it comes from |
|---|---|
| `drawProbability` | the literal `0.25` |
| formation fitness | `0.92` / `0.91`, constants |
| availability | `95` / `93`, constants |
| position mismatches | `0` / `0`, constants |
| play style | `"Balanced"` on both sides |
| `analysisText`, `predictionReasons` | fixed strings |
| recent form, insights, lineups, absentees | `""`, `"Unknown"`, `[]` |

**The two computed numbers are the actual defect.** `homeTeamRating` / `awayTeamRating` are averages of
`MatchPlayerStats.getRating()` (`ZoxApiController.java:35-45`) — **the ratings from the match that was
already played**. A pre-match endpoint reading post-match player ratings, and the win probability is their
ratio rather than a model. The page then picks its match from `localStorage.lastMatchId`, falling back to
`matches[0]` from `/teams/{id}/matches` (`zox-match-preview.js:9-28`) — so **by default it is analysing the
last match that happened**, and calling the answer a prediction.

The post-match report is real data with templated prose: `generateSummary` returns *"deservedly won"* or
*"secured an important away win"* from `hg > ag` alone, and `generateTacticalVerdict` says *"tactically
superior"* or *"matured game, capitalising on counter-attacks"* without reading a single event.

**What to do, in order:**

1. **Either compute it or stop showing it.** The cheapest correct fix is to point the page at
   `ScheduleInsightService`, which already does the job the page claims to do, and delete the constants.
2. **Make the default match a *fixture*, not a played match** — `MatchFixtureRepository.findUnplayedOnDay`,
   the same source the matchday job uses. As it stands the page cannot show a genuine preview because it
   has no unplayed match to look at.
3. **`MatchController/{id}/preview` and `/api/zox/match-preview/{id}` should be one endpoint**, not two that
   disagree about whether they know anything.
4. The post-match prose is cosmetic next to #1 and **should not be touched until #1 is settled** — a
   wrong number dressed up is worse than a missing one.

### Two findings from these documents that are **wrong**, and should not be re-"fixed"

**1. §7.8 — "all 14,880 clubs are rewritten on every matchday".** The mechanism is real and was in code
written earlier the same day: `ClubRatingService` compared two **boxed** `Double`s with `==`, which
compares references, and ratings at 1100–1500 sit outside `Double`'s identity cache of −128…127, so the
`"has this row changed"` check was **always false**. That comparison is fixed.

**The consequence is not real, and this was measured rather than argued.** The clubs are loaded inside the
replay's own transaction, so they are **managed**; `save()` on a managed entity is a `merge()` that does
nothing, and Hibernate skips an UPDATE whose columns are unchanged regardless. Reinstating the broken
comparison and re-running the test still issues **zero** UPDATEs. Dirty checking is what protects the
database, not the guard.

The guard was still worth fixing — **a guard that does not work is worse than none, because the next
reader trusts it and stops looking** — and `ClubRatingPersistenceTest#aSettledReplayIssuesNoUpdates` now
pins the behaviour that is actually true.

**2. `sprintBacklog.md` S6.4 — "all 8 stubs deleted".** False. All five empty services/controllers listed
in E5 exist, and so does `PlayerSkillProgressionService` (71 lines, hard cap **17**, **inverted** talent
factor) which `TrainingController:130` reaches. S6.4 did delete a class of that name — at the old
`service/` path. The backlog is the stale document here.

### Two places these documents are wrong about *us*, because they predate this session

- **`experAudit01102026.md` §7.7 "every league match is rated as a cup match"** — true when written, fixed
  3 minutes later by a test that caught it rating a league match at the cup's 0.90. Do not re-fix.
- **§9 "about 11,000 queries per `GET /countries/world`"** — true, and fixed. See the entry below.

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
| **Champions Cup / Masters Cup / Challenge Cup** | **the competitions and the entry rule are done** — see below. **The draw is not wired**: `CupFixtureSeeder` still seeds only the first CUP row | — |
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

### Club ratings, the columns and the recalc — and two things the World page was doing wrong

**The arithmetic was finished on 2026-10-01 (`b68346c`) and had no caller.** This is the storage and
wiring: three columns on `Team`, a replay from match history, and the two calls that make it true.

| | |
|---|---|
| **`Team.eloRating` / `eloPreviousRating` / `eloDelta`** | **Deliberately not `reputation`.** Eight services read `Team.reputation` and four clamp it to 0–100 — attendance, wages, sponsorship, transfer pulling. `Country.reputation` is already a 1500-scale Elo on a column of the same name, and that collision has cost a session once. The club Elo gets its own columns for the same reason |
| **Replay, not increment** | Same three reasons as the national ratings, and the first decides it: Serbia has already played, so an incremental job leaves every one of those matches uncounted and the owner has to reset the database to see the feature work. A replay is a pure function of the match table, so it is idempotent, self-repairing, and identical on every machine |
| **Seeding is free** | The seed is where the replay *begins*, so a club is rated from its own tier (1500 / 1400 / 1300 / 1200 / 1100) the first time the replay runs and there is no separate backfill to forget to call |
| **No season reset, deliberately** | None was asked for and a replay does not want one. Persisting across seasons is what a pure function gives for nothing. Re-seeding each season would throw away a season of football |
| **Once per batch, not per match** | The replay is world-wide. Called per fixture it would replay the whole world 155 times on a matchday, and the cost would grow with the world rather than with the batch. It runs at the end of `AsyncSimulationRunner` and after a single match in `SimulationController` |
| **`qualificationBonus()` is NOT applied** | The owner asked for a group-stage bonus and there is nothing to detect it from: the international cups have qualified clubs and **no fixtures**, because the draw is not wired (B7), and the national cup is a straight knockout. A branch that provably never fires is a green log line and no rating movement. **It waits for the draw** |
| **National sides get no club rating** | A national side has no competition at all, so a club is "anything whose division is a league" and the join rules out every national side for free. Relying on the `type` flag instead would drop every club in the world, because `PyramidBuilder` creates all of them and **never sets `type`** |

`ClubRatingServiceTest` 10, `ClubRatingPersistenceTest` 3, `RatingEngineTest` 14.

**One test caught a real bug in the code written minutes earlier.** The competition→weight mapping treated
a league match as the leftover branch and rated every league game at the cup's 0.90 — so a league season
moved ratings nine points fewer than the owner's scale says, in a game whose premise is that league
football is what a rating is *for*. The test that caught it is `theCompetitionDecidesHowMuchAMatchIsWorth`,
and it also corrected **me**: I had asserted a cup tie outweighs a league match, and the owner's recorded
order is the opposite — league is the reference point and a cup tie is deliberately below it, which
`MatchValue` had got wrong once at 1.15 until a property test caught it.

**The World page was taking about 11,000 queries per view.** The owner reported it as "clicking World is
very slow" on the Oracle instance, and it was not the network. `summarise` asked **fifteen** cups to read
the finished season's tables when there are only **three** distinct sets of them — three cups per tier, all
off the same divisions — and then read each division with two queries of its own. Tier 5 has sixteen
divisions in each of forty-eight countries, so **768 divisions × 2 queries × 3 cups**, plus thirty full
scans of the competition table. Over a network, every one of those is a round trip.

A tier's tables are now loaded **once** (three queries, whatever its size) and its three cups are selected
from them. The selection rules are untouched — same comparator, same pools, same bands — because the body
was moved rather than rewritten. Three repository methods that were `findAll().stream().filter(…)` became
indexed lookups.

**`InternationalClubCupsQueryBudgetTest` asserts the shape rather than a number**, because a count would rot
the next time a repository grows one lookup. Adding divisions to a tier must not add queries. Measured:
**1 division 20 queries, 16 divisions 22, 64 divisions 22.** Flat — and under the old code the 16-division
step alone would have cost 96 more.

**The World page header now looks like the Country tab.** The owner asked for it: back button hard left with
the title right is the reverse of every other page. It reuses the Country tab's own header component
rather than copying it — the three CSS selector pairs became one, scoped to the class — so they cannot drift
apart again. The mobile `position: fixed` bar was **retargeted, not deleted**: it was keyed on
`.fm-page-toolbar`, and leaving that selector in place would have kept the rule alive against nothing and
quietly reintroduced the off-screen-button bug the owner reported two sessions ago.

`WorldPageNavigationTest` caught the retargeting immediately, because it pinned the old selector — which is
the guard doing its job rather than a nuisance, and the assertion was updated to name the header the page
now uses, with the scoping requirement kept intact.

**One number on that page is reasoned, not measured:** the mobile `padding-top: 240px` that clears the fixed
bar. The header is now a card carrying a title, the Back button and five wrapping facts, so at 390px it is
roughly 210–220px tall and 240 leaves a margin. The two errors are not symmetric — over-padding is
whitespace, under-padding hides the first panel — so it is deliberately generous. **Worth one look at
390×844.**

### `0ecc3af` — the three international club cups, and who is allowed in them

The World page listed Champions, Masters and Challenge as a disabled row reading **"Not created yet"** since
the page was written. The competitions were missing — and so was the rule that says who may enter them,
which is the part that turns "a competition exists" into "a competition means something".

**The owner's bands are the whole design:** Champions for the winners, Masters for the second and third,
Challenge for the fourth. Every division in the world sends a club to all three, so a **fifth-division
champion reaches the Champions Cup exactly as a first-division one does** — the rule is about the place in
*your* division, not the strength of it.

**Entry is decided by the season that has finished**, not the one in progress: a club that wins its
division in week 12 does not enter the same season's Champions Cup by winning it in week 12. A world
part-way through season one has nothing to qualify from, so the counts are honestly zero rather than
invented — "no club has finished a season" is a real state, and it is now distinguishable from broken.

Live, with Croatia active so the world has 62 divisions:

| Cup | Qualified | id |
|---|---|---|
| Champions Cup | **62** — one per division | 68 |
| Masters Cup | **124** — the second and third of each | 69 |
| Challenge Cup | **62** — the fourth of each | 70 |

Positions come from `LeagueTableOrder`, the one comparator in the codebase, not from the stored `position`
column: a table is only ordered when it is read, and a test now proves a club whose stored position says
*first* is **not** entered for the Champions Cup.

**Not done, and said so:** the **draw**. `CupFixtureSeeder` seeds only the first CUP row it finds, so these
three have qualified clubs and no fixture list. It is written up as not done rather than left for someone
to discover from a "Not created yet" badge on a page that now says they exist.

### `3ea6dd9` — the first draw after seeding, which was not a coin flip

`SimMatchService` seeds the engine from `fixture.getId()` — small consecutive database ids. The **first**
draw of each kind across seeds 1..500 was constant: `nextInt(2)` gave 0 one way and 500 the other,
`nextBoolean()` the reverse, `nextDouble() < 0.5` the first again. A narrow first draw takes the top bits
of the freshly scrambled seed, and for a small seed those are always identical. So whatever the engine
asks first, it gets the same answer for every match in the world, decided by which number a fixture id
happens to be.

Fixed in `SimulationRandom.seed()` rather than at a call site, because the call site is one of however
many there are: **discard one wide value.** The same three measurements become 252/248, 248/252, 252/248.
Found by the penalty shootout's toss, which was the engine's first draw after seeding.

This changes every seeded run — a replay regenerated from the same seed now differs from the stored one.
It is still deterministic, which is all the exporter and the viewer launchers promise, and stored replays
are tick snapshots that are not re-simulated.

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

## 🌍 International club cups — agreed specification (owner, 2026-09-30)

Written down here because this was got wrong twice before it was got right, and the wrong versions
were both plausible-looking. If this section and the code ever disagree, the code is the bug.

### The world is 48 countries, and "active" and "in the world" are different things

48 countries: Serbia + 46 others + Other. Two are **ACTIVE** (Serbia, Croatia) and 46 are
**SIMULATED**. Never conflate the two:

- **ACTIVE** countries have a full playable club pyramid and simulate every round.
- **SIMULATED** countries get clubs, random ratings and a **fixed table**, and **do not play matches**.
  They exist so the international field is complete and cheap — they hold their positions until their
  league is activated, at which point they start simulating and take over their own table.

### The cups are per tier, and a country enters its tier once per cup

A cup belongs to one tier and is contested only between that tier's countries. Tier 1's Champions Cup
never meets tier 5's. Within a tier, each country contributes:

| Cup | Who, per country, per tier | Field |
|---|---|---|
| **Champions Cup** | the **better** of the divisions' winners | 48 |
| **Masters Cup** | the **best two** of the pool of every 2nd- and 3rd-placed club | 96 |
| **Challenge Cup** | the **best one** of the pool of every 4th-placed club | 48 |

Tier 1 has **one** division per country, so it degenerates to exactly the straightforward case the
owner started from: winner → CC, 2nd and 3rd → MC, 4th → ChC. One rule covers both cases.

Lower tiers have several divisions per country and each is a mini-table, which is why the pools are
taken across all of a country's divisions in that tier rather than from one division. A country with
sixteen divisions must not enter its own cup sixteen times.

The Challenge Cup is the same size as the Champions Cup (48) on purpose.

### Format (unchanged from the owner's original spec)

- **Champions**: 8 groups of 6, single round-robin, **top 2** advance.
- **Masters**: 16 groups of 6, single round-robin, **winner only** advances.
- **Challenge**: same as Champions — 8 groups of 6, top 2.
- Group stage is 5 matchdays. **Week 6 is the national-team pause.**
- Then round of 16 → quarter-final → semi-final → **3rd place** → final.

### Ratings for seeded material

Average skill **12** for tier 1 and for national teams, then **−1 per tier below**:

| Tier | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| Avg skill | 12 | 11 | 10 | 9 | 8 |

**U21 = 10.**

### Simulated countries: clubs and ratings, players lazily

The owner's decision (2026-09-30): **no players for simulated clubs to begin with.** Seed the clubs and
their ratings and a standing table; generate players when the league is activated, or on the fly for the
names in a match against a human side. Everything else is just a result.

The point of the static table is to keep the cost off the database at match time, so a simulated
country is seeded as a **fixture of the world rather than a simulation of it**: divisions, clubs,
ratings, and a standing position. No players, no fixtures, no matches. It holds that table until
somebody activates its league, at which point the ordinary builder takes over.

### CONFIRMED LIVE on the Oracle instance (2026-10-01)

```
Static pyramid for Other Nations: 31 division(s), 310 club(s), no players, no fixtures.
Simulated world: 47 country/countries seeded (0 already had a pyramid),
                 1457 division(s), 14570 club(s). No players, no fixtures.
```

47 x 31 = 1457 divisions, 1457 x 10 = 14,570 clubs. The arithmetic checks out exactly, "Other" is
included as the 48th, and **no players and no fixtures were created** — which is the whole point of
the static world. First run of the seeding on a real server, and it finished clean.

### State at commit time

Green: `InternationalClubCupsTest` 9/9, `CupDrawSeedingTest` 7/7, `SimulatedWorldSeederTest` 6/6.

**Still to do, in this order:**

1. Wire `SimulatedWorldSeeder` into `ensureBaselineDataOnStartup()` and **prove it against the live
   database** — not just the tests. Standing rule: a job is not done until it has been seen to change data.
2. **Lazy lineup generation.** Clubs-and-ratings-only leaves a bot club with no players, so a cup match
   against a human side has no lineup to show. This is a real gap, not a nicety.
3. Activate `InternationalClubCupDraw` in the matchday jobs; today only the national cup is drawn.
4. Progressive knockout through to final and 3rd place.
5. Delete the three global cup rows the first wrong version created.

**Cost note:** the whole world's simulated half is ~14,300 clubs. Serbia at activation produced 7,750
players for 310 clubs, so seeding players for all of it would be ~370k rows — which is exactly what
skipping them avoids.


---

## 🔍 `dataFixSuggestions.md` — read, and one finding is already in doubt

The analysis is at the repo root and its Section 1 is the part that matters: correctness bugs where
code reports success while doing nothing, which is the failure shape this project has been bitten by
repeatedly and has a standing rule about.

**Its §1.1 claims `RecoveryJob` writes zero rows and still reports DONE** — `applyDailyRecovery()`
mutates `player.setMorale(...)`, counts it, and never calls `playerRepository.save(player)`, so the
morale is discarded and a green `job_run` row is written anyway.

**That finding is probably wrong, and it needs checking rather than fixing.** The method is
`@Transactional` and the players come from `players.findByLastPlayedAtIsNotNull()` **inside** that
transaction, so they are managed and Hibernate dirty-checks them at commit. A missing `save()` on a
managed entity is not a lost write. The analysis was done by reading source, which its own
"Measurement gaps" section admits is the weak part.

The failure mode to watch for is the opposite one: adding an explicit `saveAll` on top of dirty
checking is harmless, but "fixing" this by adding saves would be fixing nothing and would hide the
real question.

**SETTLED — and the document is wrong.** `ZoneLoadRecoveryPersistenceTest` runs
`applyDailyRecovery()`, then **clears the persistence context and re-reads** morale from the database,
so the only way the assertion can pass is if the write was committed. It passes.

Hibernate's dirty checking does the save that the missing `save()` seemed to require. The players are
read inside the method's own transaction, so they are managed and their dirtied fields are flushed at
commit. **Daily morale recovery has been working.**

Three things had to be true before that test measured anything, and each one silently measured nothing
if missed:

- the player needs **`lastPlayedAt`** — recovery keys off that, not off having zone-load rows;
- the load needs a **zone** and a **match** — the window query joins the match and reads its date;
- the load must be dated on the **game clock**, not `now()`, or on a world whose season runs to a
  different date it falls outside the window.

That last one is worth keeping in mind for the rest of this document's findings: several of them may
well be this same shape — a claim about a write that never checked whether the write was needed.

**Do not "fix" §1.1 by adding `saveAll`.** It would fix nothing and would bury the question.

The rest of Section 1 (§1.2 simulate-all silently dropping a league, §1.3 `@Transactional` on the
wrong method in `NationalTeamSeeder`, §1.4 dead `MatchPersistenceService`, §1.5 a `*Repository`
whose `save()` is a no-op) has not been verified yet.

---

## 🐛 Bugs found while working, not yet fixed

Kept out of the task tables on purpose — none of them is a feature, and each one was found by
touching the app rather than by reading it.

| Bug | What happens | Why it is not fixed yet |
|---|---|---|
| **The Back button on a match did nothing** | Two Back buttons are rendered. The top one carries an inline `onclick` and works. The bottom one (`#back-button`) was given a `dataset.target` and a `display` style and **no click handler at all**, so clicking it did literally nothing — no navigation, no console error. | **Fixed.** Reported as a cup bug and it was not one: the button has been dead on every match view. The listener is now attached at render time rather than baked into markup, so the two buttons cannot drift apart again — the top one had its target in the HTML and this one did not, which is the shape that produced the bug. |
| **National team page opened but the owner was not the selector** | The baseline selector appointment lived in the boot seeding path, and that path no longer seeds anything. So after **Initialize DB** nobody was appointed and the side had no selector at all. | **Fixed** — a regression from removing boot seeding, caught the moment the two were separated. `appointBaselineSelectors` now runs inside `buildSerbianStructure()`, next to the owner it appoints. Both senior and U21 come from that one call. |
| `Network error during authFetch` / `DB init error` on the Oracle instance | Console showed a failed fetch rather than a 403, alongside a database init error. | Not diagnosed. Consistent with the server being down or restarting rather than with the selector bug above, but **not confirmed** — it could be the seeding still running when the request was made. |
|---|---|---|
| **Clicking a country from the header threw `escapeHtml is not defined`** | `admin-view.js` used `escapeHtml` in the error path of `showCountryActivation` but never imported it. It is exported from `ui/escape.js`. Because it was in a `catch`, the first country load that failed turned a readable message into a dead render — and it took the rest of the admin view with it, which is why clicking Serbia did nothing. | **Fixed.** The import was simply missing. `account-menu.js` had already worked around the same absence inline (`typeof escapeHtml === 'function' ? ...`), which is the tell that it was known to be unreliable and patched locally rather than imported once. |
| **`/countries/leagues/null/table` 500 — "For input string: null"** | A club with no competition has no league, and `getCurrentLeagueId()` correctly returns `null` for that. The caller interpolated it into the URL anyway, so the request went out as the literal string `null` and came back a 500. Every manager on a freshly rebuilt world hit this. | **Fixed.** The fetch is not made at all when there is no league, and the panel shows `—` / "No league yet" instead. The 500 was never a server problem: it was the client asking for something that does not exist. |
|---|---|---|
| **`initialize-db` reported success on a world with no leagues** | Owner reset the database, pressed initialise, and got "no leagues at all" with the panel saying it had worked. Two causes stacked. `initSerbianFootballStructure()` began with `findByIsoCode("SRB").orElseThrow()`, and **every** league below it is built from that country — so one missing row escaped before the first league existed. The caller caught it, logged it, and returned normally, and the job reported success off that return. | **Fixed** (`5c1a0e0`-ish, see log). Serbia's lookup now re-seeds the catalogue and self-heals; the job verifies league and club counts afterwards and fails loudly. The boot path deliberately still swallows, because throwing there would take down an app that has a usable partial world — resilience at boot, honesty at the job. The reset/initialise popup being identical is cosmetic and was left alone. |
|---|---|---|
| **`POST /admin/reset-db` deadlocks** | Reset while the background league simulation is still running fails with `ERROR: deadlock detected / Process waits for AccessExclusiveLock`. The job reports `status: failed`. The world survived intact — no data loss — but the reset does not happen. | Found during `61c2a51`. It is a reset-vs-simulation contention problem, and fixing it means changing the reset path, which that task did not touch. Worth looking at together with the simulate-all scheduling below, because both are "two writers, one database". |

**Open question for that session:** the reset should either wait for a running simulation, or the
simulation should be cancellable and waited on. Neither is obvious from the outside, and the two
`AdvanceWeekAsyncService` / `RoundSimulationAsyncService` paths are the reason it happens at all.

---

## 📈 Club ratings — agreed specification (owner, 2026-10-01)

Worked out and recorded before building, because this feature was specified wrong twice already.

**Every match counts.** League, cup and international alike. Not only the internationals — but certain
matches and tiers carry **more rating points**, which is what `RatingEngine.clubK(MatchValue)` already
does: a cup tie or an international outweighs a league game, and a bigger rating gap moves a number
further.

**The seed is a ladder from the top.** Tier 1 starts at **1500** and every tier below is **100 lower**:

| Tier | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| Starts | 1500 | 1400 | 1300 | 1200 | 1100 |

This was `1500 + (worst - tier) * 200`, which anchored the *worst* tier at 1500 and put tier 1 at 2300.
So tier 1 was not 1500 and the step was double what was asked for. Fixed, and the seed is now pinned
exactly in `RatingEngineTest` rather than left as "tier 1 is above tier 3", which is the assertion that
let both wrong versions through.

**The delta is stored against the previous value.** When a rating moves, how much it moved — up or
down — is kept, so a ranking list can show +/- rather than only the current number. Same as the national
teams, which already do this.

**Entering a group stage counts.** `RatingEngine.qualificationBonus()` already exists for exactly this.

**Not yet built:** the club rating column and its previous-value/delta columns, the replay from match
history, seeding it at initialisation, and the two ranking tables that read it. `RatingEngine` has the
arithmetic — this is storage and wiring, not a new formula.

---

## 🧩 Lazy squads for the simulated clubs

The simulated countries are seeded with clubs, ratings and a standing table but **no players** —
deliberately, because 46 countries is ~370,000 player rows for clubs that had never kicked a ball. The
cost of that is that a tie against a human club had no lineup to show.

`LazySquadGenerator` closes it on the match path, and only there:

- Trigger is **one side human-controlled**. Two bot clubs is still just a result, because a result is
  all that is displayed.
- Generated **at the club's own tier** (12 and −1 per tier), so a fifth-tier club cannot turn up with a
  first-team squad.
- **Persisted once** — the next match and the next boot find it. An existing squad is never touched,
  so this tops up an empty club and is not a refresh.
- **Display-only.** Does not feed ratings, Elo or transfers. A generated squad is not a real club.
- The precondition is enforced **inside the generator**, not only at the call site. It was originally
  only in `SimMatchService`, and the "two bots generate nothing" test caught that a second caller
  could have bypassed it.

`LazySquadGeneratorTest` 5/5. **Not yet verified against the live database** — the current world was
seeded by the old all-in-one path and has no playerless bot clubs, so there is nothing to exercise it
on. It gets its first real run after a reset + *Seed other nations*.

---

## 🟢 Admin tools — what each one does (owner, 2026-10-01)

Starting the application now starts the application. No seeding, no backfills, no repair, no catalogue —
nothing writes to the database on boot. This was the reported problem: the world's seeding ran on every
boot, before the app was usable, so a cold start on a small server looked like a hang, and there was no
way to look at a world before it was changed underneath you.

| Button | What it does |
|---|---|
| **Reset DB** | Clears the football data. Keeps user accounts and tactic editor setups. Rebuilds nothing. |
| **Initialize DB** | The **Serbian** structure: 31 divisions, fixture list, players, owner. |
| **Repair world** | Checks the world and rebuilds anything missing: countries, national squads, legacy rows. |
| **Re-seed national teams** | A 25-player squad for any national side that has none. Existing squads untouched. |
| **Seed other nations** | Every country that is **not** activated: divisions, clubs, ratings, standing table. No players, no matches. Idempotent. |
| **Re-draw the cup** | Any cup round that never got drawn. Existing rounds left alone. |

"Initialize" used to mean "build the entire world" — catalogue, 48 countries, national sides, cup draw,
every backfill — behind a label that said none of that. Split in two.

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
