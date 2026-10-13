**2026-10-13 update — ratings.** Club Elo was frozen because only two paths recomputed it and the
clock-driven matchday was neither; it is now an incremental resume (`rating_watermark`) called from
`LeagueTableReconcileJob`, whose `order()` moved 20 -90 so it cannot run before results are released.
National ratings ride the same job as a **full** replay on purpose (qualification bonus must not be
re-applied). Verified in the DB: 270 distinct Elo values, 1,165 clubs off seed, watermark 1871.
Two guards added: the resume must equal a cold replay, and a second run must be a no-op.

# SESSION_HANDOFF — 2026-10-12 (afternoon)

Supersedes the night handoff. Read this first, then [`kanban.md`](kanban.md) — section **T-MATCH** is the
open work and every task there carries its own exit criteria.

---

## 1. What was done, in order

| Commit | What | Verified how |
|---|---|---|
| `4addf0b` | **The game clock is one clock and it ticks** | 15 tests, 2 mutations, live in the browser |
| `c1d6b8e` | **A fixture's day and its kickoff hour agree** | 7 tests, 1 mutation |
| `4c8f677` | **U-21 squad topped up to 25** | 7 tests, 1 mutation, live DB inspected |
| `11579eec` | **Income scales with the club** | 39 tests, 1 mutation |
| `bc9e123b` | **Omladinac's wages were 45–84% of his own value** | live DB, 18.5m → 4.8m a week |
| `ca13cf6f` | **The Finances page shows the lines**; home advantage is real | grep proved nothing called the ledger |
| `126c6418` | **A 10.0 has to mean an exceptional match** | 14 tests, 3 mutations |
| `a04dca45` | **A training pass over 14,880 clubs completes** | 259s, measured |
| `841a1a10` | **13,630 squadless clubs were earning money from nothing** | live DB |
| `fae522e8` | **Only human clubs and active countries have an economy** | 310 of 14,880 settled, 60s → 12s |
| `f23dc810` | **A matchday is computed an hour early, released at kickoff** | 13 tests, 2 mutations, live DB |
| `f73961fc` | **The T-MATCH board**, seven tasks with exit criteria, the decisions of 2026-10-11 | — |
| `76dd5936` | **A prepared match cannot be read** by any of 7 match endpoints or 3 replay ones | 4 tests, 2 mutations |
| `188c9a44` | **…nor appear in the lists**, because the row is complete | 1 test, 1 mutation |
| `2f476d58` | **A rating moves at publication**, per the owner | 1 test, 1 mutation |
| `d68827d0` | **National matchdays get the pair**; day 6 was never a rest day | 2 tests, 1 mutation |
| `70873505` | **A matchday that failed on every fixture was recorded as one that happened** | 3 tests, 1 mutation |
| `fa25c5a5` | **The lock is real**; fixtures are loaded where they are played | 8 tests, 1 mutation |

Mutations were run against the fixes and each one turned a suite red. That is the only evidence in this
repository that a green suite means anything — and the one that did not is written up in §3.

---

## 2. Where the world stands

| | |
|---|---|
| Clubs | 14,880 across 48 countries |
| Squads | Serbia `ACTIVE` 310/310; the 47 `SIMULATED` countries hold 940 of 14,570 |
| Economy | Active-country clubs settle in ~12s. Net **−16m a week**, average −51,358 a club |
| OFK Omladinac | 4,845,682 a week in wages. **−862,775 a week** — reported, not fixed |
| Squad size | **13 is intentional.** Do not "correct" it |
| Match rows | 743. Fixture rows run into next season already, drawn in advance |

---

## 3. The traps in this session's code

**A mocked dependency cannot prove what the dependency does.** `MatchdayPreparePublishTest` drives the
scheduler with a mocked `SimMatchService`. Flipping the production branch so that preparing *published*
the result an hour early left it green. `PreparedMatchIsNotVisibleTest` drives the real bean and fails
on that mutation. Keep both: the first for wiring, the second because it can fail.

**`JobRunner` wraps every job in one transaction, and that is wrong for a matchday.** A matchday is 155
independent matches. In one transaction it committed nothing for twenty minutes — ids ran to 918 while
the table ended at 743 — and one bad fixture would have lost the evening. Committing per fixture
deadlocked against the enclosing transaction, which already held the `player_discipline` rows.
`DayJob.transactionalRun()` is the escape hatch, and only the two matchday jobs use it.

**Spring does not fold an array or a `List<DayJob>` bean into the runner's job list.** Eight jobs were
registered that way, compiled, and had never run. There is no test that would have caught it; it was
found by dumping the injected list. **Eight explicit `@Bean` methods, as the rest of the package does.**

**A lazy proxy owned by a dead persistence context cannot be revived.** This one bit three times and cost
775 failures, then 155, then 155. The day's fixtures were fetched outside any session, so their `Team`
proxies belonged to a context that had closed; wrapping the work in a transaction afterwards changed
nothing. **Load the entity inside the session that will use it** — which is why `MatchdayScheduler` takes
ids and re-reads each fixture.

**`JobRunner` records a job DONE when its body returns normally, and for a matchday that is not the same
as "played".** A run that prepared 0 of 155 logged 155 warnings and was recorded as a successful matchday.
`MatchdayScheduler` now returns `MatchdayOutcome(prepared, failed)` and both jobs throw when `failed > 0`.
An **empty** matchday stays a clean pass — a week with no fixtures must not retry for ever.

**A test that carries its own `@Transactional` cannot reproduce a bug about sessions.** The first version
of `MatchdaySchedulerPreparesRealFixturesTest` passed because the test held a session open. That is the
whole fault, faithfully reproduced except for the thing that was broken.

---

## 4. What is open

[`kanban.md`](kanban.md) **T-MATCH-1** … **T-MATCH-7**, in order. The short version:

- **T-MATCH-1, one line left:** a whole 155-fixture matchday computed and released end to end. Both
  halves have been seen at 15, never together. It takes 10–15 min per matchday, the game clock is anchored
  to the wall clock so the window moves while you watch, and a bad run's `DONE` row suppresses the slot
  until deleted by hand. **It wants a human with a stopwatch.**
- **T-MATCH-2, two lines left:** the lock is enforced on tactics and the lineup template but **is not in
  the browser** — nothing tells a manager his match is closed. And the squad/orders writes of T-MATCH-5
  and T-MATCH-6 must call `MatchLockService`; that is the contract to check when they land.
- **T-MATCH-3 … T-MATCH-6** — tactics editor (10 per club), 3 per match, the squad page, the orders page
  (20 substitution orders, 10 tactic swaps per match, engine switching live at the trigger minute). None
  built.
- **T-MATCH-7** — a restart mid-matchday should resume, not re-decide football that is already published.

Decisions the owner took on 2026-10-11 are in the table at the top of the T-MATCH section. No set-piece
takers in this pass. A tactic-swap order switches the engine **live at the trigger minute**.

---

## 5. Traps that have cost real time

- **The dev database has 743 matches.** A performance claim measured here is a claim about one
  matchday of one country. Use the throwaway `sokker_bench` database.
- **A stale `PENDING` row in `job_run` blocks every later run** until it is deleted. It happens whenever
  the app is killed mid-job, and it looks like "the job stopped for no reason".
- **Homebrew `pg_dump` is v16 and refuses a v18 server.** Use
  `/Applications/Postgres.app/Contents/Versions/18/bin/pg_dump`.
- **Never start the app from a shell without `--app.open-browser=false`.**

---

## 6. Commands

```bash
export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:/usr/local/opt/libpq/bin:$PATH"

mvn -o test -Dtest=RatingEngineTest          # one class
mvn -o clean package -DskipTests             # build
java -jar target/footballmanager-1.0-SNAPSHOT.jar --app.open-browser=false

lsof -ti :8080 | xargs kill -9               # a stale app holds the port
psql -h localhost -U postgres -d sokker_db    # postgres / stojke
```

Clock: `POST /api/game-clock/advance?unit=hours&amount=N` and `.../advance-to-hour?hour=H`, both
`OWNER`/`DEV`/`ADMIN`. Login is `POST /auth/login` with **username** and password — not `email`.

---

## 2026-10-12 — session close

**Working tree:** clean apart from `var/tactics-editor-profiles.json`, which the app rewrites at runtime —
it is the owner's tactic backup and is **not** part of any task's changes.

**Where the board actually is.** Eleven commits today, all verified rather than merely green:

| | |
|---|---|
| `5e818602` | Match Setup tab; the tactic shelf moved below the club frame |
| `786abe5e` | the week in the match label — "two different matches on the same day" was one missing word |
| `8a8dbb7b` | the reset keeps the tactic library, which it was deleting |
| `ad1a2e22` | formation/style buttons look active, not disabled |
| `373b3a84` | a sponsor on the stands and on the Finance page |
| `9b71c25c` | all four stadium corners drawn as one bowl; failed panels report themselves |
| `c6c7e66a` | three defects only a browser found |
| `82f611bb` | the game plan open on a match four game-days away — a stopwatch where the game's lock belongs |
| `9e0b157c` | the calendar beside the kickoff; the club menu on the setup tab |
| `548f91a0` | T-MATCH-8: only the minutes spent in the training slot count |
| `2116e3d7` | the simulation write path, closed |
| `3f7ddea6` | substitution orders capped at twenty; the kickoff backstop narrowed |

**Three things worth remembering, because each cost real time:**

1. **A browser exists.** `agent-browser` — `npm i -g agent-browser`, and it lives in `~/.npm-global/bin`,
   which is **not** on the default PATH. Three changes this session were declared "needs your eyes" and
   could have been measured. It also caught two of my own bugs (`calendarSuffix is not defined`, and a club
   menu rendered into a template that is then replaced) that the suite passed.
2. **Fixture dates are stamped once and never track the game clock.** This caused *three* separate reports
   — a "day 7 fixture that has not been played yet", the match label showing two matches on the same day,
   and the Game plan closing on a match four game-days away. Every one of them was a wall-clock comparison
   being used where the game's own calendar was needed. If a panel closes on a time comparison, suspect
   this first.
3. **A guard can go blind without anything going red in the code.** `SubstitutionPlanIsReachableTest` reads
   the source for patterns. Match Setup moved a mount into a table and the guard stopped matching — while
   protecting exactly the defect that killed the fixture sheet. Shape-based guards need updating when the
   shape changes, or they protect nothing and still look green.

**Open, and why:**

- **T-MATCH-6** needs one owner decision before the rest of the orders page. The tactic-order cap: 3 is
  T-MATCH-4's three-tactics rule and 10 was a later ruling about *orders*, and raising the constant stops
  the approved rule holding. Three readings are written out on the board; none is the default. The
  substitution side is done, capped at 20 and stated in the UI.
- **T-MATCH-5** — per-slot controls on the squad page, and per-match lineup storage, which is a schema
  question (`Lineup` keys on `team` + `Match`, not `MatchFixture`).
- **T-MATCH-3c** Paste across clubs.
- **T-FAC-1** — the four facility levels affect only price, upkeep and the number on screen. Recorded with
  the questions that need answering, and one that is not obvious: `JuniorDevelopment` already reads the
  stadium's `gymLevel`, so the gym may already have one effect to extend rather than duplicate.
- **The 155-fixture prepare→publish proof** has still never been seen end to end. Prepare and publish were
  each seen on 15 fixtures.

**Verification habit that paid off:** mutating each fix and watching the test go red. The substitution
cap and the training filter both have mutations recorded in `kanbanProgress.md`, and twice a test caught a
change of mine that the suite alone would have passed.
