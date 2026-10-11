# SESSION_HANDOFF — 2026-10-11 (evening)

Supersedes the morning handoff. Read this first, then `kanban.md`.

---

## 1. What was done, in order

| Commit | What | Verified how |
|---|---|---|
| `4addf0b` | **The game clock is one clock and it ticks** | 15 tests, 2 mutations, live in the browser |
| `c1d6b8e` | **A fixture's day and its kickoff hour agree** | 7 tests, 1 mutation |
| `4c8f677` | **U-21 squad topped up to 25** | 7 tests, 1 mutation, live DB inspected |
| `11579eec` | **Income scales with the club** | 39 tests, 1 mutation |
| `86f27268`, `cd177fbe` | Board and log | — |

Four mutations were run against the fixes and every one turned the suite red. That is the only evidence
in this repository that a green suite means anything.

---

## 2. The clock, in one paragraph

There used to be two clocks: `now() + offset`, which the header rendered, and four stored counters, which
the job runner read, which moved only when a button was pressed. They disagreed and nothing said so.

There is now one. `GameClock` holds an anchor and an offset and nothing else; season, week, day and hour
are `GameTimeline.positionAt(anchor, gameTime)`. The getters keep their old names so ~20 readers keep
working; **they have no setters**, and that is the point. The header shows `HH:MM:SS` anchored on the
server's own time, and derives season/week/day from that same instant.

Everything that moves the world — advance hour/day/week, `POST /api/game-clock/offset`, the half-hourly
tick — adds to the offset and then calls `synchronise()`. One path, so "the clock moved but the jobs did
not run" is not a state this codebase can reach.

**Migration is done on the owner's database.** It reads the old counters, places an anchor that puts the
game back exactly where it was, and folds the old display offset in so it is not applied twice. That
second half was a real bug: the first version moved a day-4 world to day 7.

---

## 3. Read this before touching performance

**One training pass over 14,880 clubs takes over six minutes of pure CPU, with the database idle.**

388 seconds of CPU in 398 seconds of wall, all inside `Hibernate Cascade.cascade`, no query running.
Cause: `SquadEnvironmentService.advanceWeek` and `SquadTrainingService.trainEveryClub` walk every club in
one transaction and one persistence context; each player's query auto-flushes and cascades over every
entity dirtied so far. O(clubs²).

An attempt — separating the read phase from the write phase, and flushing/clearing every 50 clubs —
**measured at 393 s CPU in 408 s and was reverted.** It is in `git reflog`, not in the tree. Do not
ship an unmeasured perf change.

The fix that is likely to work is reading through a projection instead of entities, so nothing enters the
persistence context. Not started. Board entry `T-PERF-1`.

---

## 4. Open, with the owner's decision needed

### 🔴 The wage scale (board `T-ECON-1`)

OFK Omladinac pays **18,478,000 a week across thirteen players** — 74m a year each — on a ground that
brings 173,591. Sixty times a normal club's wage bill on twice the ground.

No broadcast coefficient balances that club and an ordinary one, because the wage scale is the outlier,
not the income side. Re-pricing every wage and value is a migration over **31,230 players** and a
decision about how much money is in this world. **This needs the owner. It was not guessed at.**

### 🟠 The Finances page (board `T-FIN-UI-1`)

The owner asked for individual line items and a week/month/season scope. `/api/teams/{id}/finances/ledger`
exists and is **never called**; `byCategory` is returned and never rendered; there is no week or month
scope at all. Every ledger row already carries a populated `note`. This is a rendering task and was not
started.

### 🟠 Playoffs (board `T-CALENDAR-1`)

`SeasonService.ensurePlayoffWeekFixtures` has **zero callers**, so no playoff fixture is generated. Its
day and hour are now correct for whenever it is wired up.

### 🟠 Not started from the owner's list

- **Match prediction** — the "Home edge"/"Away edge" cards show formation fitness and bench quality, not
  home advantage; they read `null` until an XI is picked, hence "Not known yet" beside a confident
  prediction. Real home advantage is a magic `+0.7` at `ScheduleInsightService.java:222` and **the
  simulation engine has no home advantage term at all**. The played-match endpoint hardcodes
  `draw 25%`, `fitness 92%`.
- **Match ratings** — `tackles` is actually **duels lost** (`ProposalStatsCollector.java:209-214`), so a
  striker dispossessed eight times banks +0.64 in "tackles"; ~268 duels per match; minutes are always 90
  for substitutes; no team-result term; the 10.0 clip is silent; **no test covers the formula**.
- **Pre-match preparation job** — nothing prepares a fixture before kickoff; the matchday jobs only play.

---

## 5. Method that works on this machine

```bash
export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:$PATH"
mvn clean package -DskipTests
./run-app.sh -DskipTests          # never mvn spring-boot:run
export PATH="/usr/local/opt/libpq/bin:$PATH"     # psql
```

`pg_dump` on PATH is Homebrew 16 and the server is 18 — it refuses. Use
`/Applications/Postgres.app/Contents/Versions/18/bin/pg_dump`.

**Starting the app in the background from a tool shell:** `(nohup java -jar … & disown)`. Without
`disown`, a tool timeout kills the process group and takes the app with it — which cost a confusing
"connection has been closed" stack trace mid-session.

---

## 6. Traps hit this session

- **`mvn -q` hides the build result.** `mvn -q -o package` printed nothing on success and the next
  command failed on a compile error that had scrolled past. Grep for `ERROR` explicitly.
- **An `Edit` can report success and change nothing** if the string did not match what you thought. The
  renewal fix did this, and only a behavioural test caught it.
- **The H2 test profile is not the owner's world.** Tests that place the clock through the retired
  setters were rewritten to `GameClocks`, which moves the anchor and the offset.
- **Two of the mutations I ran did not fail.** A mutation that passes means the guard was not measuring
  what I claimed. One of them (the national cup's hardcoded 18:00) passed because it happened to equal
  the template's value — which is why the test was moved to the continental cup.
