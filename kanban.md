# kanban.md — the board

**Read this file first. Then `kanbanProgress.md` for what has already been tried.**

Everything still open is here, in three categories:

| | Category | What belongs there |
|---|---|---|
| **P0** | **Correctness, security, and anything blocked on the owner** | Defects that make the product wrong or unsafe, plus every task waiting on an owner decision. P0 is ordered by *leverage*, not by cluster. |
| **P1** | **Performance** | Reads and writes that get slower as the world grows. Every task here must state a **measurement**, not an opinion. |
| **P2** | **Features and visual work** | Anything that can be completed on its own without a decision from anyone. Includes the other sports. |

---

# 📌 General information — read before taking any task

## The three rules

**1. A green status is not evidence.** The recurring failure shape in this codebase is code that reports
success while doing nothing. Four separate bugs had it in one session: a seeding job that caught its own
failure and returned normally, a pyramid builder that gave up before the first league and reported a
clean rebuild, a Back button with a `dataset` and no listener, and an `escapeHtml` used in a `catch` and
never imported. **Three of the four could only be found by opening the app.**

**2. A job is not done until it has been seen to change data.** Not logged as done — observed in the
database afterwards. Never hand back a half-built world: every seeding path must end in a world that
passes the integrity check.

**3. A test that cannot fail proves less than no test.** When you write a guard, **break the code
deliberately and watch it fail.** Three tests in this repository were green while measuring nothing
before they were fixed.

## Environment

```bash
# The agent shell does NOT inherit the owner's exports. Run this first, every session.
export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:$PATH"
```

| | |
|---|---|
| Build | `mvn clean package -DskipTests` |
| Run locally | `./run-app.sh` — **never** `mvn spring-boot:run` directly |
| One test class | `mvn test -Dtest=RatingEngineTest` |
| Local database | PostgreSQL, db `sokker_db`, user `postgres`, password `stojke` |
| App login | `velibor@example.com` / `A12345!` |
| Browser launch | `./run-app.sh` pops open a browser on every start. `--app.open-browser=false` opts out; **every shell start must use it** |

**A full `mvn test` takes ~2 h 52 m and needs the application running on `:8080`.** Three Playwright
classes otherwise hang the entire run — they wait, they do not fail. Budget for it; never start it on the
way to something else. A full-suite run only counts if it was allowed to finish.

## Working rules

- **Answer in English.** The owner writes in Serbian; every reply, message, commit and code comment is in
  English. This is not a preference to be revisited.
- **Commit to `main`** after each task. Stage only the files that task intended to change — check
  `git status` for untracked files that were already there.
- **After each task:** update `kanban.md` (mark it done, move it to the log) and `kanbanProgress.md`
  (append one entry carrying the commit hash), then commit.
- **Substantive findings go in `kanbanProgress.md` even when the task fails.** A measured dead end is
  worth more than a silent one.
- **When you find a defect that is not your task: fix it or report it — do not walk past it.** Record it
  in `kanbanProgress.md` with what it was and whether it is fixed.
- **Do not delete a task to make the board look tidy.** Mark it done, or record why it was dropped.

## Where things live

| Path | What |
|---|---|
| `src/main/java/org/example/footballmanager/newLogic/` | **The product.** Exactly two packages: `newLogic` and `demo` |
| `demo/service/` | **Frozen reference engine.** Not the product path. Do not extend, do not port from |
| `src/main/resources/static/` | Frontend, vanilla ES6 modules |
| `archive/` | **Superseded documentation.** History only. Never work from it |
| `TECHNICAL_OVERVIEW.md` | What the system actually is today |
| `AGENTS.md` | Repo conventions and the working rules above |

## Traps that have cost real time

- **`Team.reputation` is the economy's 0–100 scale, not an Elo.** Eight services read it, four clamp it.
  Club Elo lives in `eloRating` / `eloPreviousRating` / `eloDelta`. `Country.reputation` is a *different*
  scale on a column of the same name.
- **`PyramidBuilder` never sets `Team.type`**, so "is this a club?" cannot be answered from that flag. A
  club is anything whose `competition` is a LEAGUE.
- **A season is twelve weeks counted from 1.** There is no calendar year anywhere. Any code or test
  passing 2024/2025/2026 as a season value is wrong, even if it is self-consistent and passes.
- **`use authFetch` for every authenticated frontend call**, and **always check `response.ok`**. Several
  loaders `await response.json()` without it, so a 404 escapes to the router and the page becomes a
  generic "API Error" card.
- **Boot writes nothing.** No seeding, no backfills, no repair. World building happens on admin buttons.
- **`DummyDataController` is fake data**, hardcoded to team 1, with zero DB access, and five frontend
  files still fetch it. Awaiting an owner decision. Do not wire it to anything.

## Scale — most of P1 exists because of this

48 countries × 31 divisions × 10 clubs ≈ **14,880 clubs**, 96 national sides, a promotion ladder per
country, three tiers of international club cups. The world grew ~48× in five days and the code that
schedules it was written for one country.

Measured row counts on a **Serbia-only** dev database, for scale:

| Table | Rows (Serbia only) | Notes |
|---|---:|---|
| `player_zone_load` | 18,729 | Largest table. **198 rows written per match, one at a time** |
| `player` | 10,130 | ~300k at full scale |
| `match_player_stats` | 3,410 | |
| `match_fixture` | 2,790 | 310 a matchday, 9 matchdays seeded, **7 days apart** |
| `team` | 406 | 14,880 at full scale |
| `match` | **155** | **One matchday. Ever.** See below |

### 🔴 The dev database cannot measure a performance task

**`match` holds 155 rows: a single matchday of a single country.** `match_tick_states` holds **0**, and
that is not a seeding gap — nothing writes it (P1-1).

So the projected figures are **48×**: **7,440 matches a matchday, 89,280 a season**, and
`player_zone_load` at **198 rows a match** means **17,677,440 rows a season**, 2.8 GB.

**A sequential scan of 155 rows is the correct plan.** An `EXPLAIN` on the dev database cannot show that
an index helps, cannot show that one does not, and will confidently report "no problem" about a query that
costs five seconds at full scale. Every index added under P1 was measured in a throwaway `sokker_bench`
database built to those numbers; the plans are in `kanbanProgress.md`.

**The first attempt at P1-1 got this wrong** — it proposed three indexes, one of which served a query
with no caller and another of which made the hot daily job 68% slower. The board's own scale table had
said `player_zone_load` had 18,729 rows and did not mention `match` at all.

---

# 🔴 P0 — correctness, security, owner decisions

Ordered by leverage. **P0-1 and P0-2 are the two tasks that make everything else safer to do.**

### P0-1 — Nine controllers have no tests at all, so the security surface is untested

**Why this is first:** only three tests in the whole repository exercise any controller. Five reachable
security defects were previously found by reading source, not by a failing test. Writing these tests
against the current code is how the *next* five get caught.

**Scope, corrected against the source on 2026-10-03:** `LineupController`, `PlayerController`,
`TeamController`, `UserController`, `AdminController`, `CommunityController`, `DummyDataController`,
`TransferController`. **Two names on this list never existed** — there is no `CompetitionController` and no
`StadiumController`. The real ones are `StadiumSettingsController` (for the stadium) and, for competitions,
no controller of their own. `TransferController` was substituted **on the owner's decision**, because
`AdminController`'s own javadoc records that `/transfers` is "not role-guarded, so putting it there would
let any authenticated user delist another club's player" — a named hole rather than a coverage tick.

**Split in two, because one commit was not a reviewable unit:**

| | Controllers | State |
|---|---|---|
| **P0-1a** | `LineupController`, `PlayerController`, `TeamController`, `UserController`, `AdminController` | **done**, `66553b4` |
| **P0-1b** | all four — `TransferController`, `StadiumSettingsController`, `DummyDataController`, `CommunityController` | **done**, `6fd6521` |

**How:** copy the JWT pattern from `WorldAdvanceAuthorizationTest`. **Mock the repository _interface_, not
the injected bean** — Spring Data returns a JDK proxy that Mockito cannot wrap, and this is the single
most common way a test of this kind fails for the wrong reason.

**Exit criteria:**
- [x] Every one of the eight has at least one test that calls it **without** a JWT and asserts 401/403 — **P0-1a**
- [x] Every world-moving or admin-only route asserts the role check, not just authentication — **P0-1a**
- [x] At least one test per controller asserts a **successful** path, so the guard tests cannot all pass
      against a controller that returns 500 for everything — **P0-1a**
- [x] Each new test class is proven able to fail by breaking the authorization annotation and watching it
      — **P0-1a, five mutations, all in `kanbanProgress.md`**
- [ ] The same four, for the four P0-1b controllers

**Not:** a coverage percentage. A test that asserts 401 on a route that was always going to 401 proves
nothing about authorization.

**Six defects P0-1a found and closed.** Recorded in full in `kanbanProgress.md`; the two that mattered most
were **not** missing guards:

- **`POST /lineups` had never accepted a request body at all.** It took the raw `Lineup` entity, and Jackson
  cannot deserialise that graph — every caller, including an administrator, got
  `HttpMediaTypeNotSupportedException` before the controller was entered. A test asserting only 403 would have
  been green throughout, because a route that cannot bind refuses everyone equally. Now takes a DTO.
- **`POST /players/create` answered 500 on every call.** `PlayerDTO.from` dereferences `player.getSkills()`
  unconditionally and `createPlayer` never set one.

### The most severe finding in the whole P0 segment — `TransferController`, P0-1b

**Ten writes, and not one of them asked who was acting.** Every write takes the acting club as a
caller-supplied parameter, and `TransferService` can only compare that parameter against the seller — it
cannot know who holds the token. So the question "is this your club?" was never asked on the surface. The
codebase says so itself: `AdminController.forceUnlist` carries a javadoc explaining it lives under `/admin`
**because** `/transfers` is not role-guarded. The hole was documented in prose and left open.

**And on four routes, omitting the parameter turned the check off.** The seller guards read
`if (actingTeamId != null && !Objects.equals(...))`, so `null` skipped the comparison entirely. The guard was
strictest when a caller could prove who they were and absent when they could not — exactly backwards. The
same file's `requireSeller` already had it right: null is a 400.

What that allowed, all now refused:

| Route | Was |
|---|---|
| `POST /list/{playerId}` | list **any** player in the world at **any** price |
| `DELETE /remove/{playerId}` | delist **any** player |
| `POST /buy/{playerId}` | spend **any** club's budget — the buyer is named in the body |
| `POST /interest/{playerId}` | register interest as **any** club |
| `/accept-offer`, `/reject-offers`, `/interest/{id}/clear` | act on **any** listing |

The rule applied is the one the rest of the game already uses: **the club named in the request must be the
club the caller runs.** Naming a rival satisfies the service's check and not this one, which is precisely how
every route was reachable. Reads are untouched — the market page is for every manager, and the country filter
already defaults to the viewer's own.

**`TransferController` is the only route in the repository where one manager could move another club's
money.**

### P0-16 — `DummyDataController`'s callers are all broken except for team 1 — NEW, 2026-10-03

Found while writing P0-1b, and **not fixable here**: the board rules *"do not wire it to anything"*, and
deleting the routes would break five pages.

**Every mapping carries a literal `1` and there is no `@PathVariable` anywhere in the class.** So

```
/demo/teams/1/profile     answers 200, "Omladinac FC"
/demo/teams/57/profile    answers 404
```

and **five frontend files call `/demo/teams/${teamId}/profile`** — `club-management.js`,
`staff-directory.js`, `pages.js`, `demo.js`. Every one of them works for club 1 and 404s for every other
club, so a manager opening his own club page gets an empty screen. The controller returns a fabricated
profile for the one club that happens to be id 1, which is worse than 404 because it looks real.

**Owner decision needed:** wire these five callers to the real endpoints, or delete the callers. Both are
outside P0's authority — the first is exactly what the board forbids, the second removes product surface.

**Exit criteria:**
- [ ] The owner rules: rewire the five callers, or remove them
- [ ] Whichever it is, no page fetches `/demo/**` for a club id it chose itself
- [ ] `DummyDataController` then has either a `@PathVariable` and a decision, or no callers

---

### P0-17 — the community chat hides applicant details behind one boolean — NEW, 2026-10-03

`CommunityController.shouldHideFromNonAdmin` decides whether a message bound to a **pending registration
request** — an applicant's username and email — is shown to a non-administrator. It is a single boolean
method, `/chat` returns the thread, and **nothing tests it.**

Recorded rather than tested on purpose: pinning it needs a message bound to a pending request, and a test
built that way would assert almost nothing about authorization. A test that cannot fail is worse than no
test.

**Exit criteria:**
- [ ] A message attached to a pending registration request is proven invisible to a `REGULAR` manager
- [ ] The test asserts on the **absence of the applicant's address**, not on a count or a key

---

### P0-13 — DONE: training asked who was acting on one route out of two

**`TrainingController`, 9 tests, two mutations.**

**The rule already existed in the same file.** `setIntensity`, forty lines below, documents its refusal:
*"a player who does not play for this club is a 403, not a bad request, because the request is well formed
and the manager simply is not allowed to make it."* And `plusFeatures` was already injected to make exactly
that check. So `POST /train/{playerId}` — which trained and returned **any** player in the world, by id,
with no club involved — was the one route in the controller that did not use the rule its neighbour enforced.

| | Was | Now |
|---|---|---|
| `POST /train/{playerId}` | **200**, and returned a raw `Player` | 403 unless the player is in the caller's own club; answers `PlayerDTO` |
| `POST /train-all` | **200** to any logged-in manager | administrator-only |

**Both routes have zero callers** — not in `static/js`, not in `src/main`, not in one test. The training
screen uses `POST /training/weekly/team/{teamId}/run`, and the world is trained by day 4's `TrainingJob`.

**The raw `Player` was a third disclosure surface**, after `/players/paged` in P0-1a and `/players` in
P0-1b: `talent`, `earnings`, the injury record, `personality`, `skills`. The test caught `9.1` where the
entitlement rule says `null`.

**`/train-all` is guarded, not deleted — the owner's decision.** A role guard answers "who may"; it does
not answer "should this exist at all". **The deletion question is still open** and is criterion 4 below.

**Exit criteria:**
- [x] `/train/{playerId}` refuses with 403 unless the player is in the caller's own club
- [x] `/train-all` is administrator-only
- [x] Neither returns a raw `Player` entity — `/train-all` returned a count, thanks to another agent
- [ ] **`/train-all` ruled on:** deleted, or kept with the reason written down — **still the owner's**

---

### P0-18 — `viewerTeamId` returns an id from the wrong table for the owner — NEW, 2026-10-03

Found because a fixture caught it, and it is worth more than the bug it looked like.

`PlusFeatureService.viewerTeamId` checks `tifoCTeam` **first**:

```java
if (user.getTifoCTeam() != null && user.getTifoCTeam().getId() != null) {
    return user.getTifoCTeam().getId();      // a CTeam id, not a Team id
}
```

`CTeam` is `footballtextmanager.model.CTeam` — a different entity with its own `IDENTITY` sequence. So that
value is **not** a `Team` id, and every caller comparing it against `Team.id` fails.

**It is not only a fixture problem.** `DatabaseInitializer:899` and `StartupInitializer:104,142` all set the
**owner's** `tifoCTeam`. So the owner gets a `CTeam` id back from `viewerTeamId`, which means
`talentOrNull` withholds talent from the owner for his own players.

**Why it went unnoticed:** `RegistrationService` sets only `cTeam`, so an ordinary manager never takes the
branch. Only the owner does.

**Exit criteria:**
- [ ] `viewerTeamId` resolves to a `Team` id in every case, or the `tifoCTeam` branch is removed
- [ ] The owner can see his own players' talent, verified against a real account
- [ ] A test asserts the **value** of `viewerTeamId` for a user with a `tifoCTeam`, not just that it is non-null

---

### P0-14 — `LineupController`'s writes are unreachable from the frontend — OWNER-GATED

Ruled on 2026-10-03 as **guard, do not delete.** Recorded because the reasoning matters more than the ruling:
the frontend files a squad sheet through `TeamController`'s `lineup-template`, and `grep` over `static/js`
finds no caller for `POST /lineups` or `DELETE /lineups/{id}`. They are now guarded and working.

**Unreachable is not the same as harmless** — three defects lived in exactly these routes, including one
that had never worked. The deletion question is open but is not urgent.

**Also found here and fixed in passing:** `LineupController`'s sort whitelist offered `"name"`, a column
`Lineup` does not have, so a caller could ask for a sort on a property that does not exist.

---

### P0-2 — Six test classes are red

**Two classes share the same missing-`GameClock` trap**, found while writing P0-13, both **pre-existing**:
`NegotiationServiceTest` (10 errors) and `SquadTrainingServiceTest` (6 errors) both do
`clocks.findAll().stream().findFirst().orElseThrow()` in `setUp`. Boot writes nothing, so the test database
has no clock row and **every method fails before it asserts anything**. Three lines each to fix — create the
row rather than expect it, as `TransferControllerAuthorizationTest` now does. and the owner has ruled: rewrite them to assert what the product guarantees

**Owner decision, already made:** do not make the red tests pass by changing the product to suit them.
Rewrite each to assert a guarantee the product actually makes.

`ScoutingServiceTest` is **already green, 10/10** — it is the worked example of this fix. Read it first.

**Exit criteria, per class:**
- [ ] The failure is read and the *intended* guarantee is written down in the test's javadoc
- [ ] The test asserts a **value**, not that a key exists (a test checking `rating` exists passes happily
      with it permanently null)
- [ ] The fixture builds the minimum world the guarantee needs, via `TestCountryCatalogue` (118 ms) or
      `TestPyramid.Builder` (37 s, once per JVM)
- [ ] Each rewritten class is green **alone** and green **in a full run** — several of these fail only in
      company, and a fix that only holds alone has moved the problem

---

### P0-3 — The away side plays the home club's shape

**The defect:** `SimMatchService` builds one `TacticsRules` — the home club's — and hands it to
`MatchOrchestrator`, `RestartManager` and `TacticalIntentEngine`. Since `abef6a2` a club's own formation
decides its own role keys, so **a 4-3-3 side facing a 4-4-2's rules asks about `CM`, `WL`, `WR` and
`ST`, which those rules do not name.**

**What is already done, do not redo it:** `1420306` made the miss path safe. A role the tactic does not
name now returns null, and every caller falls back to the player's own position, which `RealSquadFactory`
has already placed from his formation. Before that, every unmentioned outfielder was sent to the same
hardcoded cell `(1.5, 3.5)` — nine players on one square metre.

**Exit criteria:**
- [ ] Each side carries its own rules, authored in **its own perspective** — the perspective question is
      the actual design problem here, not the plumbing
- [ ] A test simulates **4-4-2 home vs 4-3-3 away** and asserts both sides are positioned from their own
      profile
- [ ] The test is proven able to fail by collapsing both sides onto one `TacticsRules` and watching it
- [ ] Replay of that match shows two different shapes

---

### P0-4 — ANSWERED 2026-10-03, deferred by the owner: both stay identical for now

**The owner:** both stay identical *until* he decides whether a user should be able to build a separate
tactic for each phase. **Not a defect, and not a deferral of the work — a deliberate hold.**

**What that holds in place, and it is the same hold as before:**

- `TeamTacticsService.mirrorWeHaveBallRules` keeps mirroring on every save and every read
- `DefensiveShape` stays **load-bearing rather than decorative** — it manufactures the defensive shape the
  data cannot carry
- the multi-tactics work stays unwritten, because the storage design depends on the answer

**What it unblocks:** **P0-3.** The away side carrying its own `TacticsRules` is a runtime concern about
perspective, not a storage decision, so it does not wait on this.

**Still open, and it is a different question from the one answered here:** whether a manager should ever be
*able* to author two phases. If yes, the tactic table becomes keyed by `(tactic, possession_context)` and
roughly doubles the rows; if no, `mirrorWeHaveBallRules` and the duplication both go away and the data model
gets simpler than it is today. That is the follow-up, not this task.

**The evidence the decision rests on, unchanged:** `TeamTacticsService.mirrorWeHaveBallRules` makes
`WE_HAVE_BALL` and `OPPONENT_HAS_BALL` identical on **every save and every read**. All 506/506
out-of-possession rules in the shipped data are byte-identical to their in-possession twins. That is why
`DefensiveShape` is **load-bearing rather than decorative** — it manufactures the defensive shape the data
cannot carry.

---

### P0-5 — ANSWERED 2026-10-03: drop the four, keep `OFK Omladinac`

**The owner drops the four orphans.** Verified against the file rather than the board: five profiles, and
**only `OFK Omladinac` (4-4-2) names a club that exists.** All four orphans are 4-3-3 — `FK Beograd`,
`GFK Dinamo Šabac`, `GFK Tamiš Gornji Milanovac 1901`, `SK Čačak 1912`.

**This closes the decision, not the work.** The code change — editing
`var/tactics-editor-profiles.json` down to one profile, and dropping the restore's "reports them by name"
behaviour that exists only because of them — is **not yet made.** Tracked under P0-15.

---

### P0-15 — DONE: the tactics file is down to the one profile that places

**The file `var/tactics-editor-profiles.json` is tracked in git** and is the only durable copy of a club's
tactical-editor work. Five profiles, **one** of which named a club the world has. Now one.

**Verified against the live database, not inferred:**

| | |
|---|---|
| `OFK Omladinac` exact matches in 406 club names | **1** |
| Already in `team_tactics_profile` | 4-4-2, ATTACKING, **132,532** chars, version 5 |
| The surviving file profile | 4-4-2, ATTACKING, **132,532** chars |

The two copies agree exactly, so the restore's "database wins where both have the club" path keeps the
authoritative row and the file remains a genuine backup of the same thing.

**Why dropping beats mapping — and it is not the reason the board gave.** The board said *"the world has
five Beograd clubs and none is called FK Beograd"*. The stronger fact is that **twelve clubs are near-misses
and there is no way to choose between them**:

| Removed profile | Clubs it could plausibly mean |
|---|---|
| `FK Beograd` | `NK Beograd`, `GFK Grafičar Beograd 1945`, `SK Balkan Beograd City` |
| `GFK Dinamo Šabac` | `OFK Šabac 1928`, `SK Kolubara Šabac`, `NK Car Konstantin Šabac 1931` |
| `GFK Tamiš Gornji Milanovac 1901` | `NK Tamiš 1950`, `OFK Tamiš Kragujevac` |
| `SK Čačak 1912` | `FK Čačak 1931`, `GFK Mlava Čačak 1913`, `OFK Čačak`, `SK Čačak Sport` |

**Two of those are the trap.** The profile says `Čačak 1912`; the world holds `FK Čačak 1931` and
`GFK Mlava Čačak 1913` — **adjacent founding years, different clubs.** A fuzzy matcher would attach a
4-3-3 profile authored for one club to a confidently-named wrong one, and the mistake would be invisible
thereafter. There is no mapping to make. Dropping is the only safe answer.

**A correction to this board's own criterion.** It said *"the restore no longer reports unplaceable profiles
by name, because there are none to report"* — which reads as an instruction to delete the `unmatched` warning.
**Do not delete it.** That warning replaced a silent `continue`, and the silent `continue` is precisely how
four of the owner's five profiles vanished without a word. The criterion is satisfied by the **file** no longer
containing any, and the warning stays as the guard for the next one.

**A test fixture was polluting this file, and it was mine.** `Rival b0542c46` — a club named after a
P0-1a fixture — had been written into it by a test reaching the editor through HTTP. Removed. The cause was
fixed in P0-1a (the backup path is now a property); this removes the pollution it left behind.

**New guard:** `TacticsBackupIsNotWrittenByTests` asserts the tracked file is byte-identical either side of a
tactics write — **and asserts the sandbox file *did* change**, so the first assertion cannot pass vacuously.
Its first version omitted the path override, wrote to the tracked file, and **the guard caught it in the same
commit.** That is the guard working, and the reason the override is code rather than a comment.

**Exit criteria:**
- [x] `var/tactics-editor-profiles.json` holds one profile, `OFK Omladinac`
- [x] The restore no longer has any unplaceable profile to report, **and the warning stays**
- [x] `TacticsRulesProviderTest` and `TacticsProfileRestoreTest` green — **26 green** with `TeamAuthorizationTest`
- [x] A test now prevents any test from writing the tracked file
- [ ] **Outstanding:** the final run of the new guard class was blocked by another agent's untracked test file
      failing to compile (`AsyncSimulationRunnerCountsFailuresTest`, missing `SimMatchService`). Everything
      above was verified; that one class has not been seen green.

---

### P0-6 — ANSWERED 2026-10-03: day 6 is form & morale

**The answer was already written down**, in the Country tab's calendar, which the owner quoted:

> Day 1 20:45 International · Day 2 Finance update · Day 3 19:00 League · Day 4 Training ·
> Day 5 18:00 Cup · **Day 6 Form & morale** · Day 7 16:00 League

**Verified against the code, and the calendar is right about everything except day 6.** Registered matchdays
and jobs: day 1 internationals 20:45, day 2 `FinanceJob` and `CupDrawJob`, day 3 league 19:00, day 4
`TrainingJob`, day 5 cup 18:00, day 7 league 16:00 (`MatchdayJobsConfig`, plus `WeekRolloverJob` and
`SeasonRolloverJob` on day 7). **Nothing at all is registered for day 6** — no `DayJob` returns 6.

So the decision is made and **the gap it names is now a known omission rather than an open question.** It is
feature work, not a correctness defect, so it belongs in P2 — but it should not be lost, because the calendar
now promises a manager something the server does not do.

**Also confirmed from the same calendar, and both are absences rather than bugs:** weeks 6 and 12 carry no
league football and are "reserved for national-team qualifiers" and "the World Cup", neither of which is
built (that is P2-10); week 11 is playoff week.

**Exit criteria:** the owner says what day 6 does, or says it stays empty on purpose. **— met. Carried to
P2 as a job to build.**

---

### P0-7 — DONE: the playoff path was the last place in the season that only knew about Serbia

**`295511b`'s successor, `see kanbanProgress.md`.** The board named one site; there were **three**.

**The fix needed no new plumbing.** Both callers already held the top flight —
`buildPlayoffSummary(Competition superLiga, …)` and `ensurePlayoffWeekFixtures(Competition superLiga, …)` —
so the country is read off the competition the caller passed. **The bug was never a missing parameter; it
was a hardcoded string where a parameter should have been.**

| Site | Was |
|---|---|
| `ensurePlayoffWeekFixtures` `:372` | asked for tier-2 leagues in `"SRB"` |
| `findTier2Leagues()` `:1094` | filtered a Serbia-only list a **second** time |
| `findSerbianLeagues()` `:1115` | the Serbia-only list itself |

So of 48 countries, **47 had no promotion or relegation summary and no playoff fixtures**, and Serbia worked
perfectly — which is exactly why the omission was invisible. The season rollover and the promotion ladder
had already been made country-agnostic; the playoff path was the one the earlier fix missed.

**A top flight with no country yields nothing rather than defaulting to Serbia.** The tempting fix — "if the
country is null, assume SRB" — would put one country's playoff inside another's pyramid, which is worse than
the bug. Asserted, so the fallback cannot come back.

**Verification, and its limit.** `PyramidBuilder:151` sets `country` on every competition it creates and
`CountryActivationService.activate()` builds through it, so an activated country's pyramid carries its
country; the live database has **0 of 31** leagues with a null country. The test proves a non-Serbian country
of exactly that shape gets both its summary and its fixtures.

**But the live database is Serbia-only** — 31 leagues, 1 country, 0 non-Serbian top flights — so this was
**not** run against a real activated foreign pyramid. The board asked for that and it is not met. Activating
one writes ~7,750 player rows, and the board records that attempt as a 26-minute operation that committed
nothing. **The owner's call, recorded rather than taken.**

---

### P0-8 — Verify `dataFixSuggestions.md` §1.2–1.5 before touching any of it

§1.1 turned out to be **wrong**: recovery is committed, and the missing `save()` was never a lost write.
That document was produced by reading source, and **three of its findings had a configuration where the
claim "passed" while measuring nothing.**

| § | Claim | Status |
|---|---|---|
| 1.2 | "Simulate all" can silently discard an entire league | unverified — plausible, real trade-off |
| 1.3 | `@Transactional` on `totalSides()` not `seedIfMissing()` | unverified |
| 1.4 | `MatchPersistenceService` is dead code, 402 lines, zero callers | **CONFIRMED by P1-1** — zero callers in `src/main`, its table holds 0 rows. Deleting it is the owner's call |
| 1.5 | `MatchEventRepository.save()` is a no-op | unverified — probably a naming choice, badly named |

**Exit criteria:** each is confirmed or refuted **by running it**, and the verdict recorded in
`kanbanProgress.md`. §1.1 is settled — do not reopen it.

---

### P0-9 — Calendar-year test fixtures pin nothing

`WeeklyFinanceServiceTest`, `StaffSponsorServiceTest` and `PlayerContractServiceTest` pass 2024/2025/2026
as `seasonYear`. A season is **twelve weeks counted from 1**; these values are self-consistent under either
scheme, so they assert nothing about the thing they name.

**Exit criteria:**
- [ ] Each fixture passes a season **number**, not a year
- [ ] Each test still passes afterwards — if one fails, the test was asserting a calendar assumption and
      that is a finding, not an obstacle

---

### P0-10 — Delete the dead tactics plumbing

Verified **zero callers** across `src/main` and `src/test`: `TacticsBridge`, `NewLogicTacticsService`,
`newLogic.model.TacticRules`, the `Formation` / `Tactics` / `MatchContext` chain, and
`tactcal_editor_positions.json` (the filename's typo is the original's). Roughly 400 lines.

**Exit criteria:**
- [ ] Caller count re-verified immediately before deleting, not copied from this board
- [ ] `mvn clean package` succeeds
- [ ] The relevant test suite still passes

---

### P0-11 — Documentation drift found while restructuring, 2026-10-03

Two concrete contradictions, both confirmed in source:

1. The old board listed **`nationalCup()` returns the lowest-id domestic cup** as *owner-ruled but not
   implemented*. **It is implemented.** `CupFixtureSeeder.java:283` now calls
   `findFirstNationalScoped(CUP, INTERNATIONAL, limit 1)`. B2 closed this; the board never caught up.
2. **A method called `nationalCup()` queries `CompetitionScope.INTERNATIONAL`.** The name says national,
   the scope says international, and it lives in a class that also seeds national cups. Rename it or
   document why the name is load-bearing.

**Exit criteria:** both resolved in code or in documentation, and the board stops disagreeing with itself.

---

### P0-12 — `dataFixSuggestions.md` §4.1 and §4.4 — OWNER-GATED

- **§4.1** — the `IDENTITY` generation type disables JDBC batching for **70 of 71 entities**, so
  `batch_size=50` is dead code.
- **§4.4** — `match_tick_states` says *"Decision needed… Do not index it as-is."*
  **Answered by P1-1, 2026-10-03: the table is vestigial.** Nothing writes it, nothing reads it, it holds
  0 rows, and replays are file-backed JSON with their own retention. **No index was created and none
  should be.** Whether to delete the table and `MatchPersistenceService` is an owner call.

Both are owner decisions. P1-1 no longer proposes an index on this table — it measured that the index
would serve a query with no caller, so the §4.4 question dissolved rather than being answered.

---

# 🟠 P1 — performance

The world grew **48× in five days**. Everything here is about the code that was written for one country
meeting 14,880 of them.

**Every task in this category must state a before and after measurement.** A P1 task with no number is not
finished. Where a number is already known it is given here — do not re-derive it, but do re-confirm it if
the code near it has changed.

### Measured already — do not redo

| Change | Before | After | Commit |
|---|---:|---:|---|
| Daily recovery zone-load read *(155-match dev database)* | 21.0 ms | **10.8 ms** | `d95da9d` |
| Weekly rollover squad reads (30 clubs, 3 listings) | 180 queries | **0** + 1 bulk | `e310856` |
| Four fixture endpoints: game week used as a round | 155 fixtures for every week and day | correct matchday | `7374c69` |
| League top scorers / assists *(89,280-match season)* | 158.7 ms | **0.19 ms** | P1-1 |
| Club match history, played, ordered *(same)* | 170.3 ms | **0.26 ms** | P1-1 |
| Club page, all matches *(same)* | 140.6 ms | **0.20 ms** | P1-1 |
| One week of a season *(same)* | 156.4 ms | **27.8 ms** | P1-1 |
| Club Elo replay — 155 matches, with the 742 KB event log | 443–554 ms | **6.5–11.2 ms** | P1-7 |
| `event_json` written per match | 840,136 B | **17,001 B** | P1-7 |
| A match's 198 zone loads — **no index created, query has no caller** | 629 ms | — | P1-1 |
| Recovery read, one matchday *(17.7M-row table, 17 KB blobs)* | 6,173 ms | **~539 ms** | P1-3 |
| International Elo replay — whole-world reads | 1 + 3 × matches | **1** | P1-4 |
| Weekly squad rollover — squad reads | 1 + 14,880 | **2** | P1-4 |
| AI friendly pass — week reads, per friendly week | ~59,520 | **2** | P1-4 |
| `/train-all` response | every player as JSON | **`{"trained": n}`** | P1-4 |
| Club milestone page — season event-log reads | 2 | **1** | P1-7 |
| Club page blob fetched and discarded | 10 MB | **204 KB** | P1-7 |

**Read the scale column.** The first three rows were measured on a 155-match village; the P1-1 rows on a
full 89,280-match season. **A number is only comparable to a number measured the same way**, and the
first three are not comparable to the last five.

---

### P1-7 — DONE. The replays, the blob, the milestone page and the background failures. One item measured and dropped.

`Match.eventJson` was the whole per-tick decision log in one text column: **742 KB – 1,035 KB a match,
~66 GB a season.** Four separate things were wrong with it.

| | before | after |
|---|---:|---:|
| Club Elo replay, 155 matches | 443–554 ms, ~66 GB a season | **6.5–11.2 ms, ~5 MB** |
| `event_json` per match, written by the app | 840,136 B | **17,001 B — 49.4× smaller** |
| Club milestone page — season reads | **2** | **1** |
| Background fixtures that failed | logged, never counted | **counted and named** |

**Landed, each measured in the database.** The replays read a `ScoredMatch` projection.
`SimReportMapper.eventJson` writes only the event types a page can use, from a keep-list derived from
`MatchDetailService`, `ZoxApiController` and `GoalEventRepository` — **all three** readers were checked
against a running app on a real new match. `LeagueMilestoneService` reads the season once instead of
twice. `AsyncSimulationRunner` counts and names the fixtures it could not simulate.

**Measured and DROPPED — the club-history projection this board used to ask for.** The criterion was
written before the blob was narrowed, when a club page pulled **10 MB**. It now pulls **204 KB**, and
`MatchDTO.from` reads no JSON at all. Saving 204 KB per page view does not justify a new projection type
plus three rewrites, on a query **P1-1 already took from 170 ms to 0.26 ms**. The criterion was wrong and
is corrected rather than met.

**Three defects found here:**

- **FIXED — `GoalEventRepository.isGoal` credited goals VAR ruled out.** `contains("GOAL")` matched
  `GOAL_DISALLOWED` and `VAR_GOAL_OVERTURNED` — 30 credits for goals that do not exist. **Not a product
  question:** `BallResultHandler` asks VAR *before* `goalScored`, so the scoreline never counted them
  either. The definition now lives on `MatchEventType`, because **three** call sites had each invented
  their own substring.
- **NOT A DEFECT — `simulate-all` does report its background work, and my claim was wrong.** It returns
  `backgroundSimulating` and `backgroundTotal`; `/current-round/status` reports progress live. My print
  statement selected four keys out of nine and I concluded from those four.
- **OPEN, and one line further up than the last one: a fixture with no teams is counted as
  `simulated`.** The runner returns early for a null home or away side and then increments
  `simulatedCount` anyway, so a match that was never played is reported as one that was. **Needs a
  decision:** is an unplayable fixture a *failure* or a *skip*? That changes what the status endpoint means,
  and `AsyncSimulationRunner` sits next to the simulation endpoints other work is in. Exit criteria: a
  fixture that was not simulated is never counted as one, and the status endpoint distinguishes the two.

**Not done, deliberately:** no migration. The existing 155 matches keep their 742 KB blobs, so historical
pages stay slow until the world is reset while every new match is cheap.

### P1-1 — DONE. Four indexes on `match`. Two of the three claims on this board were wrong.

**Landed:** `ix_match_competition_season`, `ix_match_season_week`, `ix_match_home_team_date`,
`ix_match_away_team_date`. Declared on the `Match` entity, with `tools/create-match-indexes.sql` for a
database that already has rows. Measurements and full `EXPLAIN` plans in `kanbanProgress.md`.

| Query | Where it runs | Before | After |
|---|---|---:|---:|
| `findByCompetitionIdAndSeasonYear` | top scorers / assists, **request path** | 158.7 ms | **0.19 ms** |
| `findByHomeTeamIdOrAwayTeamId...PlayedTrue...OrderByMatchDateDesc` | club match history, **request path** | 170.3 ms | **0.26 ms** |
| `findByHomeTeamIdOrAwayTeamId` | club page, **request path** | 140.6 ms | **0.20 ms** |
| `findBySeasonYearAndWeekNumber` | `GoalEventRepository`, 12× a season job | 156.4 ms | **27.8 ms** |

Measured on a full-scale season — **89,280 matches** — in a throwaway database, not on the 155-row dev
table where a sequential scan is the correct plan and proves nothing. Write cost: **+16.8 µs per match
row**, +125 ms per matchday at 7,440 matches, 24 MB per 89,280 matches.

**What the board got wrong, all three checked against the source:**

1. **`match_tick_states` — refuted. Nothing writes it and nothing reads it.** `MatchPersistenceService`
   is its only writer and has **zero callers**; the table holds **0 rows**. Replays are file-backed
   JSON under `app.replay-dir` with their own retention. **A `(match_id, tick)` index was not created
   and P1-5's "~900 rows per match" is void** — growth is zero. This also settles **P0-8 §1.4** and
   **P0-12 §4.4**: no decision needed, the table is vestigial. Deleting it is an owner call.
2. **`player_zone_load(match_id)` — refuted, twice.** `findByMatchId` has **no caller in `src/main`**,
   only one test; and adding the index made the one query that *does* run on that table **68% slower**
   (4,441 ms → 7,436 ms) because it flips a hash join the planner wants into index probes it does not.
   See P1-3, which now owns the real fix.
3. **"Every zone-load recovery query is a seq scan on `match`" — true and irrelevant.** That scan costs
   **112 ms of a 5,318 ms query**: 2%. Indexing `match(match_date)` changed the total by less than the
   noise. The cost is on the other table.

**`MatchIndexDeclarationTest` guards this.** It asserts the four names and their column order, and
fails on a fifth index until that one has been measured too. Proven able to fail: swapping
`competition_id, season_year` and adding an unmeasured fifth both fail it, and both messages print.

---

### P1-2 — `ZoneLoadRecorder` writes 198 rows per match, one at a time

22 players × 9 zones, each row saved individually. There is already a unique index on
`(player_id, match_id, zone)`, so the rows are known to be distinct — the batch is safe to attempt.

**This is gated on P0-12 §4.1**: `IDENTITY` disables JDBC batching, so a batch insert may not help until
the generation type is addressed. **Establish that first** rather than writing the batch and measuring no
change.

**Exit criteria:**
- [ ] Rows-per-match and elapsed time measured before and after
- [ ] If `IDENTITY` blocks it, that is recorded and the task stops — it is P0-12's to unblock, not this
      task's to work around

---

### P1-3 — DONE. The read pages, and the index P1-1 rejected is the one that mattered

**The board's version of this task was answered, not open:** `findByLastPlayedAtIsNotNull()` has no caller
any more, so there is no `last_played_at` index to add. What was left was the read itself, on the wrong
table.

| one matchday's recovery read, 89,280-match season | before | after |
|---|---:|---:|
| elapsed | **6,173 ms** | **~539 ms** |
| rows read | 17,677,440 | 1,473,120 |

**Three things had to be right, and two of them were not:**

- **`player_zone_load(match_id)` is landed here, and P1-1 was right not to land it then.** Unpaged it is a
  68% regression; paged it is what makes the read cheap. The board was right and the fix was incomplete.
- **How the ids are passed changes the plan 80×.** `IN (SELECT … LIMIT 500)` is a 5,020 ms Hash Semi Join;
  `IN` with 500 bound values — what JPQL emits — is a **44 ms** Parallel Bitmap Heap Scan. **Do not
  refactor it into a subquery.** It looks identical and costs 100×.
- **The keyset is `(match_date, id)`, not `id`.** Id-only paging cannot use an index on the date, so the
  page query heap-filtered everything before the page: **206 ms a page against 0.35 ms**.

**Page size measured, not chosen:** 500 / 1,000 / 2,000 all land at ~530–550 ms for the window;
**5,000 falls off a cliff to 6,478 ms** because the index stops being used. 500 shipped.

**`ix_match_date_id` is a fifth index on `match`, reversing a P1-1 measurement on new evidence.** P1-1
proposed `match(match_date)`, measured it, found no benefit and did not create it — correct for the query it
was measured against, since that query spends 98% of its time on the zone-load side. Paging made it
necessary. **An index can be worthless and then become necessary when the query beside it changes shape**,
so "measured, no benefit" is not the settled sentence it looks like.

**Recovery still happens — counted, not timed.** The guard asserts **every match in the window is asked for
exactly once**: a repeated row double-counts a player's recovery, a skipped one under-counts it, and
neither throws. An unbounded paging loop would have been worse than a failing job, and stopping quietly
would credit less work than the players did while logging a plausible number — so the job now **throws**
after 200× a season's pages, with the reason in the message.

---

### P1-4 — DONE, four of nineteen. Every one of the board's five named candidates had drifted.

The pattern: a repository call returning a whole table **inside a loop or on a request path**. Nineteen
sites matched. Measurements and guards in `kanbanProgress.md`.

| Fixed | before | after |
|---|---:|---:|
| `NationalRatingService` — whole-world reads per Elo replay | 1 + 3 × matches | **1** |
| `SquadEnvironmentService.advanceWeek` — squad reads per week | 1 + 14,880 | **2** |
| `FriendlyRequestService.runAiFriendlyWeek` — week reads per friendly week | ~59,520 in the first loop | **2** |
| `TrainingController /train-all` — response body | every player entity as JSON | **`{"trained": n}`** |

**Every one of these was invisible to a clock** — 48 countries is a small table, and the loop asking for it
costs more than the query. The guards count queries, not milliseconds.

**The board's five candidates, re-verified against source:**

| Candidate | What is actually there |
|---|---|
| `SeasonService:553` `teamRepository.findAll()` | **Gone** — the fatigue work replaced it; `:610` says so in a comment |
| `SeasonService:605` `playerRepository.findAll()` | **Gone**, same |
| `SeasonService:1116` `competitions.findAll()` loop | **Gone** — no `findAll()` left in the file |
| `NationalRatingService:239` `countries.findAll()` | **Fixed** — three per match, and `world` was already loaded at `:102` |
| `CupFixtureSeeder:129` / `:271` | **Never an N+1.** One query filtered in Java; `:142` is the same |

**Recorded, not fixed, with the reason:**

- **Tier 2, once per matchday or season** — `MatchdayJob:87` (already carries a *"One query, not one per
  competition"* comment from a previous fix), `LeagueTableReconciliationService:98` (its inner read is now
  served by P1-1's index), `SeasonRolloverJob:71`, `CupFixtureSeeder:142`, `InternationalClubCups:397`.
  One query each; none worth a change.
- **Tier 3, admin buttons, repair, seeding** — `WorldIntegrityService`, `WorldRepairService`,
  `CountryActivationService`, `SimulatedWorldSeeder`, `BotLeagueStandardBackfill`, `StaffSponsorService`.
  Reading the world is the point of a repair or a seed. **`StaffSponsorService.seedAllClubs` still has a
  real 1 + 14,880 N+1** inside its loop — a genuine find, on a seeding path, left as its own decision.

**Two things worth keeping:**

- **Only weeks 6, 11 and 12 have friendly-capable slots.** The AI friendly pass is expensive in **3 weeks
  of a 12-week season**, not every week. A first test picked week 2, which returns before reading anything
  and passed on a pass that did nothing.
- **This is the third instance of one pattern** — `TransferService` (`e310856`), `SquadEnvironmentService`,
  `FriendlyRequestService`. A collection loaded once, then re-asked inside a loop because the loop could
  not see the copy. Three instances is a convention, not an accident.

### P1-5 — `match` grows without bound; `match_tick_states` does not grow at all

**Half of this task is refuted.** `match_tick_states` gains **zero** rows per match: nothing writes it.
Its writer, `MatchPersistenceService`, has no callers, and replays are file-backed JSON with a bounded
retention of their own. There is nothing to retain and nothing to decide. See P1-1.

What remains is `match`, and the honest question is what a full season costs:

| | |
|---|---:|
| matches per matchday, 48 countries | 7,440 |
| matches per 12-week season | **89,280** |
| `event_json` per match, **as written today** | **17,001 B** (was 742 KB – 1,035 KB) |
| one season, raw text | **~1.5 GB** (was ~66 GB) |
| one season, on disk (TOAST-compressed) | **~240 MB** (was ~9.2 GB) |

**The per-tick log stopped being written, so the growth question is much smaller than it was.** What is
left is a season's worth of results and reportable events. `SimReplayStore` keeps the full per-tick log in
files, with its own retention.

**Exit criteria:**
- [ ] Growth rate measured per simulated matchday, from the harness rather than extrapolated
- [ ] **A retention decision for the existing `event_json` blobs.** 155 rows hold 124 MB of the old
      format and nothing reads them but the top-scorers and match pages. Reset clears them; a backfill
      does not exist and probably should not
- [ ] A retention decision for the `match` row itself. **Before anything is deleted:** the Elo replay
      (`findPlayedClubScoredInOrder`) re-reads **every** played club match in date order, and the club Elo
      history needs its old ratings. Deleting match rows silently breaks both, so the answer is probably
      "keep them" — but it has to be an answer, not an omission
- [ ] The file-backed replay retention checked against the same question, so the two answers agree


---

### P1-6 — The other three sports have no declared indexes at all

Basketball, American football and text-based football have **zero** `@Index` declarations. They are 5,580
and 3,720 players on the dev database and they are not simulated on every tick, so this is genuinely lower
priority than P1-1 — but it is not zero, and it will not get cheaper to fix after the tables grow.

**Exit criteria:** the same treatment P1-1 actually got, which is not the treatment the board asked
for: **every index needs a named query and a before/after.** Two of P1-1's three candidates had no
query behind them at all, and the third made things worse. So:

- [ ] The queries those tables actually serve are listed first, from the source
- [ ] Each proposed index names the query it serves and carries a measured before/after on the harness
      — no index proposed on the argument that the column is obviously filtered on
- [ ] Write cost measured, as `match` cost 16.8 µs per row

---

# 🟢 P2 — features and visual work

**Everything here can be completed on its own, without a decision from anyone.** Ordered by leverage per
day of work, following `archive/COMPETITIVE_ANALYSIS.md` §10, whose ordering is deliberate.

### P2-1 — Corners decided by pitch geometry, not a nominated taker

Half a day, and easier now that P0-3 has landed. Listed as its own item in the competitive analysis for
exactly that reason.

**Exit criteria:** corner taker selected by position relative to the ball; verified on a replay.

### ~~P2-2 — Seller chooses which offer to accept~~ ✅ `SellerAcceptsANamedOfferTest` 4/4

**What the board said, and what was true.** The board said the service method "exists and correctly
rejects the wrong offer — no controller exposes it". **Both halves were wrong.** A controller did
expose it (`POST /transfers/accept-offer/{playerId}`), and it was **broken**: `acceptBestOffer`
settled the transfer and then settled it again, so the endpoint could only ever answer 400
`"You cannot buy your own player."` A seller could not accept an offer at all.

**Landed:**
- [x] One settlement. `acceptBestOffer` delegates to the new `acceptOffer`; the squad-number
      housekeeping `completeTransfer` also did is kept, extracted as `assignSquadNumbersAfterTransfer`
- [x] `POST /transfers/accept-offer/{playerId}/{offerId}` — the seller names the bid
- [x] Bids cross the wire with their ids: new `TransferOfferDTO`, on both `TransferDTO` and
      `PlayerTransferStatusDTO`, including `netToSeller` so the row is not just a headline fee
- [x] One **Accept** button per bid in the Transfer Centre and the player page, via a single shared
      renderer (`static/js/pages/views/transfer-offer-actions.js`)
- [x] A bid on another transfer is refused with `404 OFFER_NOT_FOUND`, not absorbed
- [x] `NegotiationService.settleOffer` unwinds every status if settlement refuses — the auction
      survives a failed deal instead of every rival bid being rejected for nothing
- [x] `requireSeller` no longer treats an **absent** `teamId` as consent to act as the seller
- [x] **Fixed alongside it:** `getTeamTransferOverview`'s `incomingOffers` had
      `.filter(hasOpenOffer).filter(t -> !isActiveListing(t))`, and those two predicates are mutually
      exclusive — the list was empty for every possible input, so the Incoming Offers panel was dead.
      Proven: `expected: <1> but was: <0>` with the filter restored
- [x] Every test proven able to fail by deliberate breakage (four break-and-restore cycles)

**Also found and fixed:** `requireSeller` tightening means a caller omitting `teamId` is now
`400 TEAM_REQUIRED` rather than silently skipping the ownership check. Both frontend call sites
already send it.

---

### ~~P2-3 — Player can refuse to be listed~~ ✅ `ListedPlayerCanObjectTest` 6/6

The anti-speculation mechanic. Listing used to cost the player's position nothing at all — a club
could list anybody at any price and the dressing room had no say.

**Two pieces of dead code became load-bearing rather than being rewritten:**
`SquadRole.reluctanceToSell()` (no callers anywhere) now decides how much a club's valuation of a
player translates into his resistance, and the `PlayerContractService.wageDemand` model — already
answering "is he paid what he thinks he is worth" for renewals — supplies the grievance.

**Landed:**
- [x] `ListingObjection` — three distinct reasons (`WAGE_DISPUTE`, `DOES_NOT_WANT_TO_LEAVE`,
      `UNHAPPY_TO_BE_LISTED`), each with the sentence the player says. **Not** a `TransferStatus`:
      the `transfer` table has a live Postgres `CHECK` constraint on its four values that
      `ddl-auto=update` will not recreate
- [x] Triggered in-game on a human club's listing, from reluctance + whether he is underpaid
- [x] **The club cannot delist him** (`409 PLAYER_OBJECTION_OPEN`) — delisting is how a club would
      make the objection go, so it is what has to be blocked
- [x] **The club cannot accept a bid** while it stands; the refusal carries the reason, not just a code
- [x] **The objection survives re-listing.** The `transfer` row is unique per player and recycled on
      every listing, so clearing it there would make "reject the bids, take him off, put him back"
      a free way to launder a refusal — the exact thing the mechanic exists to prevent
- [x] Two resolutions, so the manager has a real choice: `UPHELD` (withdraw, keep him — done as part
      of resolving, so he is not sent to a Remove button the objection just blocked) or `PAID`
      (5% of the asking price, charged and ledgered, and the sale can continue)
- [x] `removeFromTransferList` moved onto `requireSeller`, which **requires** a team id; it had the
      weaker `actingTeamId != null` guard that P2-2 removed elsewhere
- [x] Every test proven able to fail, including one that had to be **rewritten** because it passed
      against broken code (see the log)

---

### ~~P2-4 — Listing fee as a percentage of the asking price~~ ✅ `ListingFeeScalesWithTheAskingPriceTest` 5/5

**The board said "two lines".** It was **no fee at all** — `listPlayerForTransferEntity` charged
nothing and computed `alreadyListed` without using it, so listing was free and re-listing was free.

**Scope decision, owner:** **human clubs only.** AI clubs self-list weekly and academy graduations
list automatically; charging those would drain 14,880 budgets for a mechanic that exists to stop a
*manager* spamming the market. Gated on `Team.humanControlled`, which is maintained by
`PyramidBuilder`, `DatabaseInitializer` and `RegistrationService`.

**Landed:**
- [x] 2.5% of the asking price — the rate `archive/COMPETITIVE_ANALYSIS.md` benchmarks against
- [x] New `FinanceCategory.LISTING_FEE`, so the charge appears on the Finances page under its own row
- [x] Budget deducted and one ledger line written; charged off the **clamped** asking price, so a
      request for EUR 0 is charged as the EUR 1 it was listed at
- [x] `alreadyListed` honoured — re-pricing a live listing is not a new listing and is not re-charged
- [x] A club that cannot fund the fee gets `409 INSUFFICIENT_BUDGET_FOR_LISTING_FEE` and **nothing is
      listed**; otherwise the fee constrains only honest managers
- [x] Own class (`TransferListingFeeService`) rather than another method on the 1199-line
      `TransferService`
- [x] Every test proven able to fail, including a flat fee, a repeated charge, charging the AI, and
      dropping the affordability refusal

---

### ~~P2-5 — Supporter mood and supporter expectations~~ ✅ `SupporterMoodRespondsTest` 6/6

The board's premise verified exactly: `BoardExpectationService` has **one caller**,
`FinanceController.java:111`, which is a read for display. `sackingReview` is a computed boolean with
no entity behind it. **A number the player can see that nothing responds to is worse than no number**,
because it invites the expectation of a consequence.

**Landed:**
- [x] `Team.supporterMood` (0-100, default 60) — separate from `reputation` on purpose: reputation is
      what the club is worth, mood is how the stand feels about being there, and a club can be
      successful and unloved
- [x] **Mood bends attendance**, so it bends gate income, so it bends the wage-bill ratio the board
      reads. This is the missing consequence, and it is the board criterion *"something in the game
      responds to them"*
- [x] **The loop back to P2-3**: a player objects to being listed → supporters notice the club is
      selling its own people → mood falls → fewer come → gate income falls → the board's trust falls.
      Every step already existed; nothing was connected
- [x] `SupporterExpectation` — mood says how they feel, the expectation says what they think the club
      should do about it, which is the half a manager can act on
- [x] Drifts weekly rather than jumping: **exactly 1.0 at mood 60**, so a club that has not drifted is
      unchanged. A first formula returned 1.032 at neutral, which would have silently changed gate
      income for the whole world on day one; a test caught it
- [x] Visible beside board trust on `/finances/{teamId}/board`
- [x] One query for all clubs' objections, not one per club — 14,880 round-trips a week otherwise
- [x] Every test proven able to fail, **including one that had to be rewritten because it was green
      against unwired code**

---

---

### ~~P2-6 — Graduation caps~~ ✅ `GraduationRespectsTheSquadTest` 4/4

Graduation was **unconditional**: every ACTIVE junior aged ≥20 in the entire world became a senior
`Player` in one loop. `canRegister` could not stop it, because graduation creates no `PlayerContract`
and `canRegister` counts contracts — so a graduate was invisible to the 25-senior cap, and then drew a
wage for a full season before the backfill noticed. **A club's academy was an unlimited source of free
players.**

**The cap is the squad, not a number.** A graduate is promoted only while his club has room; otherwise
he is **released**. That gives P2-7 teeth in both directions — a club that refuses to let players go
fills its own squad and blocks its own academy.

**Landed:**
- [x] Per-club room, counted down **as it is used**, so five due juniors and two places promote exactly
      two rather than five plus an overflow discovered later
- [x] Also bounded by `MAX_ACTIVE_JUNIORS` — unreachable through intake, but the sweep reads junior rows
      directly and fixtures and the seeder insert them without passing through intake
- [x] `MAX_ACTIVE_JUNIORS = 10` extracted from the inline literal
- [x] Squad sizes loaded in **one grouped query** for the world, not one count per club — 14,880
      round-trips inside the season rollover otherwise
- [x] **Also fixed: the defect P2-3 introduced.** A graduate has no contract, so
      `ListingObjectionService.roleOf` fell back to a position switch mapping `GK/DEF/MID → STARTER`.
      A 17-year-old was judged as a senior starter. **Measured: 0.4125 objection likelihood on a
      graduate's first day**, i.e. ~41% of all automatic graduations drew an objection the club then had
      to pay 5% to clear. Now delegates to `PlayerContractService.inferRole`, which already encoded the
      right rule (a cheap 17-year-old is a YOUTH, reluctance 0.12)
- [x] Every test proven able to fail, including the `merge` bug below

**Recorded, not changed:** the `10` is hardcoded three more times in `static/js/pages/features/academy.js`
(`{n}/10`, `0/10`, and the refusal text). Java now has one source; the frontend copies still need a DTO
field, which is a separate piece of work.

---

---

### ~~P2-7 — A retirement age~~ ✅ `PlayersRetireTest` 5/5

**Nothing existed.** No `retire`/`retirement`/`retired` token anywhere in `src/main` or `src/test`, no
constant, no age-filtered query, no status field, and no removal path keyed to age. Players aged once
a year and the only ways out of a squad were a transfer or a contract expiring — so a 32-year-old
became 60, then 90, and `ClubNeedService.java:173` kept pricing him at 30% of value and bidding. The
pool only ever grew.

**Owner decision:** a **deterministic age band scaled by quality** (33–36), not a fixed birthday and
not a dice roll — a club plans a squad around when a player ends. Bands are set on the **real 0–100
rating scale** (measured on the dev world: peak ~68, range 35–93), so the cut points are 85/75/65, not
the 0–10 scale the skills use.

**Landed:**
- [x] `Player.retiredSeason` (nullable, appended at the end of the entity as `@AllArgsConstructor`
      demands — and two positional call sites in `sim/` were updated, exactly as that file predicted)
- [x] `RetirementService` hooked into `SeasonService.agePlayersAndJuniorsOneYear()` **between** ageing
      and graduation, so a player can retire and be replaced by a graduate in one season turn
- [x] `findActiveClubPlayers()` + `ix_player_team_not_retired (team_id, retired_season, age)` — the
      sweep would otherwise be a seq scan over every player in the world, once a season
- [x] **He leaves, he is not deleted.** His row, statistics and history survive
- [x] **His contract ends too** — `canRegister` counts contracts, so a retired player left holding one
      would occupy one of the 25 senior slots for ever and the club could never replace him
- [x] The sweep is idempotent, since a season roll-over must be safe to reason about
- [x] Every test proven able to fail, including the destructive-delete trap below

**Also fixed — a latent bug retirement would have made routine:** `Lineup.getOrderedStartingPlayers()`
returned join-table rows regardless of club membership, so a player whose contract had expired could
still start matches. **This already happened weekly** via `PlayerContractService.expireContracts`. The
join table now yields only players still at that club.

### P2-8 — Zero-consequence exhibition mode

One day. *"Multiplies the value of 14,880 clubs"* — it is the only feature that makes the world size an
asset rather than a cost.

**Exit criteria:** an exhibition changes no ratings, no table, no finances and no clock.

### P2-9 — Pre-match tactical preview

Three to five days, and the analysis calls it *"Hattrick's single best idea"* — it is also the cheapest way
to make P0-3's work visible on day one. `MatchController/{id}/preview` currently returns `Map.of()`.

**Exit criteria:** the preview shows **both sides'** shapes from their own profiles, so it is a real test of
P0-3 rather than a display of one club.

### P2-10 — National team qualifiers and the senior World Cup

The mechanism exists; the competitions and formats do not. This is P2 rather than P0 because nothing is
broken — it is absent.

**Exit criteria:** a full qualifying campaign and a tournament, played to a result.

### P2-11 — International club competitions

Three tiers exist with data but the calendar is thin. Depends on the fixture generators being wired to
named cups rather than the first CUP row.

**Exit criteria:** every tier plays a full season; promotion and relegation between tiers work.

### P2-12 — U-21 as its own competitions

Explicitly **not tabs on one competition**, per the analysis. Its own qualification path.

### P2-13 — Named tactics with derived levels

Three to four days. The six slider fields have **zero readers** today — they are stored and never used.

**Exit criteria:** the sliders change something observable in a match, or they are removed.

### P2-14 — Prize money: `awardPrizeMoney` has no caller

Small. It is implemented and nothing calls it — either wire it or delete it. It is on this list because
"implemented and unreachable" is a defect in its own right.

### P2-15 — Basketball, American football, text-based football

Per-mode work. Their agent notes are in `archive/BASKETBALL_PROGRESS.md` and have not been re-verified
since the `newLogic` split — **read the archive first, then confirm against the code, because the notes
predate the split.**

### P2-16 — Visual work

Presentation, not correctness. League table and World page already carry the Elo columns. Candidates worth
naming: the dashboard's empty states, and any page where a load failure renders as a generic error card
instead of saying what failed.

### P2-17 — Match engine realism

**Last, per the owner, and re-baseline first.** The numbers recorded for realism were measured against code
that has since changed, so they are not a specification. Re-measure, then decide.

---

# 📋 Parked, with the reason

| Item | Why it is not a task |
|---|---|
| Individual training focus per player | **Cancelled by the owner** |
| Work permits / foreign limits | **Built in full, then removed by the owner** (`a6394f9`) |
| `DEFENSIVE_SHAPE` / `/demo` routes / Oracle auth error | Listed in an old board section and not re-examined since. Re-check before spending time |
| `MatchdayJobsConfig` day 6 | P0-6 — an open decision, not an omission |
| Club Elo admin button | Zero reads `--` on an unrated world is deliberate, since **zero is a rating a club can
  legitimately hold**. Whether it wants a button is an owner call |

---

# 🔖 State at the time of writing

- `mvn test`: **992 tests, 13 failures, 16 errors, 29 red**, ~2 h 52 m, app required on `:8080`. Measured
  at `89144e9`; **a re-measurement was in flight on 2026-10-03** and its result is in `kanbanProgress.md`.
- The full red list and what each red class means is in `archive/kanbanProgress.md`, in the entry recording
  the run that was allowed to finish.
- **P0-1 and P0-2 are the two tasks that make everything else safer to do.** Do them first.
- **P1-1 is done, and it is the worked example for the rest of P1:** measure at projected scale, name the
  query behind every index, and be willing to land two fewer indexes than the board asked for. Two of its
  three candidates had no query behind them at all.
