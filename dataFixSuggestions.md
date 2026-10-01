# Data & Performance Analysis — UI Football Manager

**Scope:** `/footballmanager` (Spring Boot 3.3.3 / Java 21 + vanilla JS SPA), with emphasis on
database work, world/team seeding, and match generation.

**Status:** investigation only. No files were modified, no commands were run against the
database or `target/`. Every figure below is either read directly from source or measured from
an existing artifact on disk (noted where applicable).

**Related docs:** `AGENTS.md`, `sprintBacklog.md`, `sprintProgress.md`, `kanban.md`,
`kanbanProgress.md`, `newLogic/sim/backlog.md`, `newLogic/sim/PROPOSAL_CURRENT_STATE.md`,
`newLogic/sim/PROPOSAL_PROGRESS.md`, `newLogic/sim/PROPOSAL_SEASON_REPORT.md`.

---

## How to read this

Section 1 is **not** a performance list. Those are correctness bugs where code reports success
while doing nothing — the failure shape this project has already been bitten by five times and
has an explicit standing rule about. They should be fixed before any optimisation work, because
optimising a path that silently does the wrong thing makes the wrong thing faster.

Sections 2–5 are the performance plan, ordered by payoff-per-risk.

Nothing here has been benchmarked end-to-end. See [Measurement gaps](#measurement-gaps) at the
end for what is missing and how to get it cheaply.

---

## 1. Correctness bugs found during the investigation

### 1.1 `RecoveryJob` — CORRECTED. The write lands, but the computation is thrown away

> **CORRECTION (2026-10-01, second pass).** The first draft of this document claimed
> `ZoneLoadService.applyDailyRecovery()` mutates morale and never calls `save()`, so the job
> "writes zero rows". **That was wrong.** The method is `@Transactional` and
> `players.findByLastPlayedAtIsNotNull()` returns **managed** entities, so JPA dirty checking
> flushes `setMorale()` at commit. The write lands. Verified by reading
> `ZoneLoadService.java:125-162` directly and `git log` on the file (`bb0cbe0`, `9e3c5c3`).
>
> This is the second time in this project that "no explicit `save()`" was read as "no write" — see
> `sprintProgress.md`'s *"A test that passed while proving nothing"* and the
> `REQUIRES_NEW`-backfill finding. **Hibernate flushes managed state; absence of `save()` is not
> absence of a write.** Do not repeat this reading.

The job is wired and works. But reading the same method surfaced two *real* defects that the
original finding missed:

**(a) The computed recovery amount is discarded.** `ZoneLoadService.java:151-155`:

```java
double work = workedSinceWindow.get(player.getId());
if (work <= 0.0) { continue; }
player.setMorale(player.getMorale() + 0.2);   // flat, ignores `work`
```

`work` — the player's summed effective minutes across all zones in the window — is used **only as
a positivity gate**. The `DAILY_RECOVERY_CAP` (90) and `DAILY_RECOVERY_RATE` (0.65) that
`recoveryFor` (`:81`) applies are **never applied here.** A player who played one minute of a 1–2
and a player who played a full 90 minutes both get exactly `+0.2`.

So there are two different recovery models in one class: `recoveryFor` (the version the player
screens read, which honours the cap and rate) and `applyDailyRecovery` (the version the world gets,
which is a flat `+0.2`). The class javadoc at `:132-135` claims *"The arithmetic is identical to
recoveryFor(): same window, same sum, same cap, same rate."* **That claim is false.** Call
`recoveryFor(player.getId())` — it is already the correct function and is already in the class.

**(b) No upper clamp.** `MoraleService` clamps to `0..100` (`:125, :144, :157`) and
`SquadEnvironmentService` clamps with `Math.min(100.0, …)` (`:120`). `ZoneLoadService:155` does
not. A player who appears in the zone table accrues `+0.2` every game day and is never reduced by
this path, so morale walks off the top of the scale.

**Fix:** replace the three lines with a call to `recoveryFor` (or replicate its cap/rate) and
clamp the result to `0..100`. Both are small. **Then add a live assertion that the morale column
changed**, per the standing rule that a green `job_run` row is not evidence.

### 1.2 "Simulate all" can silently discard an entire league

`newLogic/service/AsyncSimulationRunner.java:36-39` — the re-entrancy CAS guard sits **inside**
the `@Async` method, i.e. after dispatch. If `MatchdayJob` is already simulating, a manager
clicking "Simulate all" returns immediately at line 38 having logged a warning.

`newLogic/controller/SimulationController.java:159` then calls
`asyncSimulationRunner.simulateInBackground(otherLeagueFixtures)` and proceeds to build a
response payload implying the round is done.

Net effect: the manager's `otherLeagueFixtures` are never simulated, there is no re-queue and no
compensation, and the UI reports success. The CAS protects against double-simulation but trades
that for silent data loss.

**Fix options, in order of preference:**

1. Make it a queue rather than a drop — if a run is in progress, append the fixture IDs to a
   pending set that the running job drains before exiting.
2. At minimum, propagate the "declined" state back to the controller so the response says so
   and the frontend can offer a retry.

### 1.3 `@Transactional` is on the wrong method in `NationalTeamSeeder`

`newLogic/util/NationalTeamSeeder.java:71` — the annotation is on `totalSides()`, which sits
above `seedIfMissing()` (line 84). `seedIfMissing()` therefore runs **non-transactionally**, and
`BotSquadGenerator.populate` performs 25 `players.save(player)` calls per national side,
per-row. Across 96 sides that is up to **2,400 player rows saved outside any transaction**.

If the process dies mid-seed you get a half-built national squad with no rollback. The class
comments document this annotation placement convention for the *other* seeders; it was missed
here.

**Fix:** move the annotation to `seedIfMissing()`.

### 1.4 `MatchPersistenceService` is dead code that makes the schema look wired

`newLogic/service/MatchPersistenceService.java` (402 lines, `@Service`, class-level
`@Transactional` at line 27) declares `MatchRepository`, `MatchEventRepository`,
`MatchPlayerStatsRepository`, **`MatchTickStateRepository`** and **`MatchTeamStatsService`** — but
it has **zero callers**. `saveMatchResult` and `saveMatchResultAndUpdateTable` only ever call each
other (line 71).

This is actively misleading during investigation: `MatchTickStateRepository` and
`MatchTeamStatsService` appear wired in the source tree, and `MatchTickState` is the model for the
`match_tick_states` volume problem discussed in §4.4 — but **the live pipeline writes neither**.
The live persistence path is `SimMatchService.persist()`.

**Fix:** delete it. Its removal is what makes the remaining wiring honest.

### 1.5 `MatchEventRepository` is not a repository, and its `save()` is a no-op

`newLogic/repository/MatchEventRepository.java` is a `@Component` wrapping a
`ConcurrentHashMap<Long, List<MatchEvent>>`:

```java
public <T extends MatchEvent> T save(T event) { return event; }   // writes nothing
```

Consequences:

- **Zero match events reach the database.** The live path serialises the whole event list into
  the `match.eventJson` TEXT column via `SimReportMapper.eventJson`.
- The 52 classes in `newLogic/model/event/` are listed in `@EntityScan` but **none is annotated
  `@Entity`** — they have no table at all.
- `deleteAll` (lines 25-29) is dead code containing
  `Long matchId = events.get(0).minute() >= 0 ? null : null;`.
- Any future per-event querying (pass networks, duel logs, xG) will require JSON parsing in Java.

**This is a design decision, not necessarily a bug** — but it is currently undocumented and the
class name actively implies otherwise. Either rename it to something honest (e.g.
`InMemoryMatchEventStore`) or make events real entities. Do not leave it named `*Repository`.

### 1.6 `SimMatchService.persist()` swallows exceptions inside a transaction

`newLogic/sim/SimMatchService.java:295-298` — the method is `@Transactional` (line 189), catches
`Exception`, and returns `null`.

A failure partway through leaves everything flushed before the throw **committed**, and the
caller receives `null` as if nothing happened. This is the documented `UnexpectedRollbackException`
family from `sprintProgress.md` arriving from a different direction: here the transaction *does*
roll back the failed statement, but the earlier successful writes still land because the
exception never propagates.

**Fix:** rethrow, or mark the transaction rollback-only before returning.

### 1.7 `persistMatchCondition()` writes outside any transaction

`newLogic/sim/SimMatchService.java:399-425` — runs *after* `persist()`, outside the transaction.
For each of ~22 players it does `playerRepository.findById` then `playerRepository.save`. That is
~22 separate autocommit round trips per match, each its own transaction.

**Fix:** fold it into `persist()` (it is part of the same logical unit) and batch it.

### 1.8 `fileLog` is `static` + `TRUNCATE_EXISTING` — each match destroys the previous log

`newLogic/sim/engine/ActionLogService.java:65-69, 101-103`.

`fileLog` is a static field opened with `TRUNCATE_EXISTING`. **The second match simulated in a
JVM run overwrites the first match's entire log file.** In a background run of N fixtures, only
the last one survives. This is why per-match log analysis has been awkward to do historically.

Secondary issue: the IO-error fallback (line 107) is `new PrintWriter(System.out, true)` — on a
disk problem the engine silently starts dumping all ~30 K lines to the console instead.

**Fix:** per-match log file (name it by match/fixture id), append mode, and a real logger.

### 1.9 Six bulk `@Modifying` queries can be silently undone by stale entities

Without `clearAutomatically = true` / `flushAutomatically = true`:

- `PlayerRepository:41-43` — `update Player p set p.age = p.age + 1` (**every player**)
- `JuniorRepository:52-54` — same, filtered by status
- `CSPlayerRepository:22-24` — same
- `AfPlayerRepository:25-27` / `BbPlayerRepository:25-27` — full-table `resetAllPlayers()`
- `AfCompetitionEntryRepository:18-20` / `BbCompetitionEntryRepository:18-20` — `resetAllEntries()`
- `AfMatchFixtureRepository:18-20` / `BbMatchFixtureRepository:18-20` — `resetAllFixtures()`

Any *managed* `Player` already in the persistence context keeps its stale pre-update `age` and, on
any later flush, writes the stale value back — undoing the increment. There is no `@Version`
anywhere in the codebase (71 entities, zero optimistic locking), so there is no safety net.

The age-increment queries are the dangerous ones: they run on season rollover against 16,000+
players and any concurrent read-then-write in the same session corrupts them.

---

## 2. Match generation pipeline

### 2.1 Four entry points, two of which persist

```
Path A — "Watch Your Match" (interactive, single match)
  POST /simulation/current-round/prepare
    SimulationController.prepareCurrentRound()          SimulationController.java:50  [@Transactional on the controller]
      resolvePreparedMatch(user)                        :322  ← matchFixtureRepository.findAll(), filtered in Java
      simulateAndStore(fixture, true)                   :62 / :305
        SimMatchService.simulate(fixture, storeReplay=true)    SimMatchService.java:78
      persistSimMatchToDB(fixture, sim)                 :63 / :313
        SimMatchService.persist(...)                    SimMatchService.java:190  [@Transactional]

Path B — "Simulate all" (round)
  POST /simulation/current-round/simulate-all           SimulationController.java:82  [NOT @Transactional]
    matchFixtureRepository.findAll(), filter in Java    :88
    user's own league fixtures — simulated SERIALLY on the request thread  :135-154
    otherLeagueFixtures → simulateInBackground(...)      :159   ← see bug 1.2

Path C — scheduled matchday job
  GameClockScheduler (hourly, cron 0 0 * * *)
    JobRunner → MatchdayJob.run(JobContext)             jobs/impl/MatchdayJob.java:83
      competitions.findAll(), filter by type
      fixtures.findUnplayedOnDay(...) PER COMPETITION   :92-96   ← N+1
      runner.simulateInBackground(ids)                   :107  (fire-and-forget)

Path D — headless / viewer only (does NOT persist)
  POST /api/proposal/generate | /proposal/api/generate
    ProposalMatchController.generateMatch(seed)          sim/controller/ProposalMatchController.java:42
      → writes a ~30 MB match.json INTO THE SOURCE TREE  :73, :125-135
```

`MatchController` (`/matches/**`) is read-only — detail, lineups, player stats, report, reveal.

**Note on stale docs:** `AGENTS.md` describes `RealisticMatchEngine`, `SimulationService`,
`RuntimeSaveToDB`, `RoundSimulationAsyncService` and `/api/v2/match/**` as the primary path.
**None of them exist in `src/main/java`.** They survive only in `footballForDelete/backend/`,
outside the Maven source root. The sole live engine is `newLogic/sim/`. This is the
"AGENTS.md describes engines deleted in this commit" item already flagged in `sprintProgress.md:199`
— it is still unaddressed and it will mislead anyone (human or agent) planning DB work.

### 2.2 What `persist()` actually writes

`SimMatchService.persist()`, `@Transactional`, ~60-250 rows per match:

| # | Operation | Line | Rows | Mechanism |
|---|---|---|---|---|
| 1 | `Match` row + `eventJson`/`lineupJson`/`statsJson` TEXT blobs | :255 | 1 INSERT | `save()` |
| 2 | `MatchPlayerStats` | :343 | ~22 INSERTs | per-row `save()` |
| 3 | `Player` career bumps (goals/assists/morale) | :383 | ~22 UPDATEs | per-row `save()`, deduped via `LinkedHashSet` |
| 4 | `PlayerZoneLoad` (player × zone visited) | ZoneLoadRecorder:156 | ~40-198 INSERTs | per-row `save()` |
| 5 | `Player.lastPlayedAt` | ZoneLoadRecorder:160 | ~22 UPDATEs | per-row `save()` |
| 6 | `MatchFixture` (played + playedMatch) | :272 | 1 UPDATE | `save()` |
| 7 | `CompetitionEntry` × 2 (league table) | :535 | 2 UPDATEs | `saveAll` — the only batch call |
| 8 | `SeasonCompetition` / `CompetitionEntry` creation | :518-521 | 0-2 INSERTs | via `SeasonService` |

**What is NOT written, despite the entities existing:**

| Entity | Rows | Reality |
|---|---|---|
| `MatchEvent` | **0** | Serialised into `match.eventJson`. See bug 1.5. |
| `MatchTickState` | **0** | Ticks go to the replay JSON file (`SimReplayStore` → `./replay-data/replay-N.json`) and/or `match.json`. **Not the database.** |
| `MatchTeamStats` | **0** | Replaced by `match.statsJson` TEXT. Only the dead `MatchPersistenceService:189` calls it. |
| `Lineup` | **0 inserts** | `match.setHomeLineup(loadLineup(team))` (:245) only re-points the `@JoinColumn` at the existing entity. |

This matters for the `match_tick_states` volume concern in §4.4: the table exists and is
modelled, but nothing currently writes it. **Decide before indexing it** — if the replay file
stays the system of record, the table should probably be deleted rather than indexed.

### 2.3 Per-tick cost in `MatchOrchestrator.tick()`

One match = 3,600+ ticks (loop bound `ticks * 2` at line 718, with stoppage/celebration
early-returns). Player count is constant at 22 on the pitch, so the *constant factors and
allocations* dominate, not the asymptotics.

| Step | Engine | Line | Complexity | Allocations in hot path |
|---|---|---|---|---|
| 1 | `clockService.tick` | :327 | O(1) | — |
| 1b | `OffsideService.trackOffsidePositions` | :335 | **O(n²)** | — |
| 2 | duel-loser unlock | :371 | O(n) | — |
| 4 | `BallPhysicsEngine.stepBall` | :386 | O(n); `nearestPlayer`:676 and `nearest3`:697 each rescan, `nearest3` **sorts** | `new BallStepResult` per tick; `new ArrayList<>()` |
| 5 | `BallResultHandler.handle` | :389 | event-driven | — |
| 6 | `CleanDecisionEngine.decideWithOptions` | :424 | **heaviest — see §2.5** | `String.format` per option; `new StringBuilder` per reason; 7 options + 2 `List.of` |
| 7 | `TacticalIntentEngine.refreshTargets` | :508 | O(n) | `new Position(...)` per player; **1 log line per player whose target moved >0.01 cells** (:131-139) |
| 7b | `ThreatOverrideEngine.evaluate` | :517 | **O(n³) — see §2.4** | `new Position(...)`; **1 `THR` line per overridden player** (:120) |
| 8 | `MovementEngine.moveAllTowardTargets` | :525 | **O(n²)** — `separateFromOpponents`:193→:244, `carrierSpeedFactor`:131→:351, 2× `looseBallChaser`:78-79 | 3-6 `new Position(...)` per player per tick, plus up to 3 more inside the slide loop (:253-263) |
| 8b | `fatigueSystem.update` | :526 | O(n), `Math.hypot` per player | — |
| 9 | restart-taker claim | :544 | O(1) | — |
| 10 | `offsideService.resolvePendingVAROffside` | :581 | O(n) when pending | `new ArrayList<>()` (:299) |
| 11 | `DuelService.detectAndResolveDuels` | :631 | O(n), **breaks after first duel** (:87) | — |
| 11b | substitutions / conditional subs | :641-643 | O(n) each | `SubstitutionService` allocates `List.of("HOME","AWAY")` + `new ArrayList<>()` **per team, every tick** (:151, :168) |
| 11c | `injuries.onTick` | :652 | O(n) + `getPlayers().stream()` per tick (:106) | stream pipeline per tick |
| 13 | `recorder.captureSnapshot(state)` | :599 | O(n) | **`getPlayers().stream().map(...).toList()` per tick → 22 `PlayerSnapshot` + 1 `MatchSnapshot`** |

### 2.4 `ThreatOverrideEngine` is O(n³) with a `stream()` inside a nested loop

`newLogic/sim/engine/ThreatOverrideEngine.java:62-127`:

- outer loop over 22 players (:71)
- each calls `pressIsolatedOpponent` (:163), which loops over opponents (:166)
- **each opponent then rebuilds a fresh stream over all players** to test isolation (:179-181)
- each qualifying candidate calls `isClosestEligiblePresser` (:188 → :262), another O(n) full scan
- `offsideRetreat` (:202) calls `findSecondLastDefenderRow` (:231), which **streams + sorts** every
  call, plus `isClearlyOnside` (:242) O(n)

Worst case per tick: 22 × 22 × 22 ≈ **10,600 distance computations** and ~22 stream-pipeline
constructions. Over 3,600 ticks: **~38 M distance ops and ~8,000 stream objects per match.**

**Fix:** hoist `state.getPlayers()` into a local `List<Player>` once per `evaluate()` call, and
build a single distance matrix (22×22 = 484 entries) reused by all three passes. This is a
mechanical, behaviour-preserving change and the single largest pure-CPU win in the engine.

### 2.5 `CleanDecisionEngine` builds log strings nobody reads

`newLogic/sim/engine/decision/CleanDecisionEngine.java`:

- A `String.format` `reason` is built for **all 7 options** in 13 places (`:313, 333, 349, 380,
  393, 395, 501, 504, 509, 511, 607, 683, 759`) — **whether or not the line is ever printed**.
- `List.of(...)` of all 7 options is allocated **twice per tick** (`:267` and `:271`) — the second
  purely to feed the log formatter.
- `ActionLogService.formatDecision:118` then does `String.format("%6.1f")` per option —
  7 more `String.format` calls plus a `StringBuilder` **per tick, ungated**.
- `secondLastDefenderRow:697` and `offsideMargin:884` each allocate a `new ArrayList<>()` **and
  sort**, per call, per tick.
- `findBestReceiver:763` is O(n); `nearestOpponentBeatsHimToIt:841` → `closestMarkerTo:857` is
  O(n²).

**Fix:** make `reason` lazy (supplier, or only build for the winning option), drop the duplicate
`List.of`, and gate the whole decision log on the log level.

### 2.6 Structural inefficiencies

- `MatchRecorder.getEvents()` / `getSnapshots()` return `List.copyOf(...)` (:109-110), and
  `ProposalMatchOutcomeBuilder.build()` calls `getEvents()` **three times** (:36, :46, :62) —
  3 full defensive copies of the event list per match.
- `toPlayerOutcomes:120` and `averageRating:146` each call `stats.buildPlayerStats(team)`, which
  streams and sorts (:343-359) — the same work twice.
- `MatchState.getRoundPaceSkill(p)` is a map lookup per player per tick; fine, but it sits inside
  the movement loop.

---

## 3. Logging: 2.8 MB per match, 80% of it noise

`newLogic/sim/engine/ActionLogService.java` **bypasses SLF4J/Logback entirely** — there is no
`logback-spring.xml` in `src/main/resources`. The engine writes to its own static `PrintWriter`
plus `System.out`.

`log(String tag, String msg)` at :81-94:

```java
String line = "[" + minute() + "|" + tag + "] " + msg;   // minute() = String.format  (:146)
eventLog.add(line);                                      // retained forever
fileLog().println(line);                                 // static synchronized (:96) + autoflush
System.out.println(line);                                // gated on CONSOLE_NOTABLE (:50-61)
```

Four costs per line: a `String.format`; a **`static synchronized` acquisition on a JVM-global
monitor** (~30 K per match); an autoflush `println` = **one write syscall per line**; and
retention in an unbounded `List<String>` that is later shipped as `match.json` `logs`.

### Measured from the real artifact

`target/proposal-app.log` — 30,513 lines, first line `[0:00|RST] KICKOFF HOME`, last
`[90:01|CLK] full time`, i.e. **exactly one match**:

| Metric | Value |
|---|---|
| Total lines | **30,513** |
| Total bytes | **2,837,495** (avg 93 B/line) |
| `TAC` | **16,101** (52.8%) — ~4.5 per tick |
| `THR` | **8,234** (27.0%) — ~2.3 per tick |
| `DEC` | 2,556 |
| `EXE` | 1,300 |
| `ORC` | 1,237 |
| `BAL` | 745 |
| `DUL` / `RST` / `FOU` / `CLK` / `PEN` | 175 / 152 / 7 / 4 / 2 |
| **`TAC` + `THR`** | **24,335 = 79.7%** |

**~2.8 MB, ~30 K write syscalls, ~30 K global-lock acquisitions, and ~30 K `String.format` calls
per match — for 11 lines (`DUL` + `RST`) that describe the actual football.** The class javadoc
(:24-26) already concedes this.

**Fix, in order:**
1. Gate `TacticalIntentEngine:131` and `ThreatOverrideEngine:120` behind a `proposal.log.tactical`
   flag (default off), or fold them into the existing `proposal.log.console` level.
2. Buffer the writer (drop `autoflush`) or move to Logback.
3. Per-match log file, append mode — fixes bug 1.8 at the same time.
4. Bound or drop `eventLog` retention; it currently duplicates the file contents in heap and
   then serialises them into `match.json`.

---

## 4. Database: schema and indexes

### 4.1 `IDENTITY` has silently disabled all JDBC batching

**70 of 71 entities use `GenerationType.IDENTITY`.** Zero `SEQUENCE`, zero UUID, one manual ID
(`GameClock`, `id = 1L`).

On PostgreSQL, `IDENTITY` forces Hibernate to execute each INSERT immediately and separately to
obtain the generated key, which **disables JDBC batching for that entity**.

`application.properties:9-12` sets:

```
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.properties.hibernate.jdbc.batch_versioned_data=true
```

**This configuration is dead code for every entity in the system.** Every `save()` inside a loop
is a separate network round trip.

**Fix:** switch the hot write paths to a `SEQUENCE` generator with `allocationSize` matching the
batch size (e.g. `pooled-lo`, `allocationSize = 50`). Prioritise, in this order:

1. `Player` — ~16,000 rows, written on every match (22 UPDATEs) and every seeding run
2. `MatchPlayerStats` — 22 INSERTs per match
3. `PlayerZoneLoad` — 40-198 INSERTs per match
4. `Team`, `Competition`, `CompetitionEntry`, `MatchFixture` — seeding paths
5. `Match`, `MatchEvent` if/when events become real entities

There is **no `@Version` anywhere** in 71 entities — no optimistic locking, last-writer-wins on
concurrent `save()`. Consider whether that should change for the entities the day jobs touch.

### 4.2 Only 9 indexes and 7 unique constraints exist across 71 entities

All are in the football `newLogic` module. **Basketball, American football, text-football and
common have zero declared indexes.**

| # | Index | Table | Column(s) | Declared in |
|---|---|---|---|---|
| 1 | `ix_team_name_prefix` | `team` | `name` | `Team.java:19` |
| 2 | `ix_team_competition` | `team` | `competition_id` | `Team.java:20` |
| 3 | `ix_competition_country_type` | `competition` | `country_id, type` | `Competition.java:9` |
| 4 | `ix_competition_tier` | `competition` | `type, tier` | `Competition.java:10` |
| 5 | `ix_competition_entry_sc` | `competition_entry` | `season_competition_id` | `CompetitionEntry.java:9` |
| 6 | `ix_competition_entry_sc_pos` | `competition_entry` | `season_competition_id, position` | `CompetitionEntry.java:10` |
| 7 | `ix_country_state` | `country` | `country_state` | `Country.java:13` |
| 8 | `ix_job_run_season_week` | `job_run` | `season_year, week_number` | `JobRun.java:38` |
| 9 | `ix_zone_load_player` | `player_zone_load` | `player_id` | `PlayerZoneLoad.java:30` |

**`ix_competition_entry_sc` (5) is a strict prefix of `ix_competition_entry_sc_pos` (6) and is
dead weight — drop it.**

**`ix_team_name_prefix` is misnamed** — it is a plain btree on `name`, not a prefix/functional
index. Postgres btree with default `C` collation will not accelerate `ILIKE 'SRB%'`, so
`TeamRepository.findByNameStartingWithIgnoreCase` (whose javadoc at :36-43 credits with fixing a
15-minute test) may still be scanning. It needs `text_pattern_ops` or a functional index on
`lower(name)`.

### 4.3 Missing indexes, ranked by impact

| Table | Missing index | Why it hurts |
|---|---|---|
| `match_fixture` | `(season_year, week_number, day_number, played)` | **8 repository methods key on exactly this triple, and the hourly production job calls them every hour.** `MatchFixtureRepository` is the busiest repository in the codebase at 18 methods. The identical triple *is* indexed on `job_run` — the bookkeeping table got it, the data table didn't. Clearest single omission in the schema. |
| `player` | `team_id` | **No index at all** on the largest table (~16,000 rows). `findByTeamId` is the most-called query in the app. Also `(team_id, position)`, `(team_id, injured)`, and `source_player_id` (self-FK, unindexed). |
| `match` | `home_team_id`, `away_team_id`, `season_year`, `competition_id`, `match_date` | All unindexed FKs/status columns. `findByHomeTeamIdOrAwayTeamId...` uses `OR` across two columns, which defeats a single index — needs a UNION or two indexed lookups. `match_date` is the sole `ORDER BY` in 4+ queries. Needed composites: `(competition_id, season_year, round_number)`, `(played, match_date, id)`. |
| `match_player_stats` | `(player_id, match_id)` | Both unindexed. `findByPlayerId` is called **inside a `.map()`** at `TeamController:200` (1+N per squad) — the sibling method at :180 was already fixed with `findByPlayerIdIn` and this one was missed. **No unique constraint at all**, so duplicate (match, player) rows are possible and nothing prevents them. |
| `app_user` | `username`, `email` | Unindexed, hit on **every login and every registration** (`findByUsername`, `findByEmail`, `existsByEmailIgnoreCase`). Also `last_seen_at`, `role`. |
| `player` | partial `WHERE last_played_at IS NOT NULL` | The daily bulk-recovery query. `IS NOT NULL` on an unindexed column is a full scan of 16,354 rows every day. The javadoc at `PlayerRepository:18-24` notes it replaced a `findAll()` — better, but still a scan. |
| `junior` | `team_id`, `status` | **Zero indexes.** `YouthAcademyService:57,62` does 2 COUNTs **per team in a 310-team loop** = 620 queries per season rollover, against an unindexed table. |
| `competition_entry` | `team_id` | `findBySeasonCompetitionAndTeam` is called **inside loops** at `DatabaseInitializer:1226, 1249, 1270`. No unique constraint on `(season_competition_id, team_id)`, so the probe is genuinely necessary. |
| `season_competition` | `competition_id`, `season_year` | `findByCompetitionAndSeasonYear` is called **inside loops** in 6 places (`SeasonService:354, 724, 830, 922, 1027`; `DatabaseInitializer:676`). No unique constraint on `(competition_id, season_year)` — the same season-competition can be created twice. |
| `transfer` | `player_id`, `status`, `listed_at` | **Zero indexes.** `TransferService:174-178` does `findByTeamId` per club (up to 310) **then** `findByPlayerId` per player on top. `findByStatusAndBuyerTeamIsNullOrderByListedAtDesc` drives the whole market page. |
| `finance_ledger_entry` | `team_id`, `season_year`, `week_number` | **Zero indexes.** `deleteBySeasonYear` is a bulk season wipe. |
| `player_contract` | `player_id` | `findPlayersWithoutContract()` is a correlated `NOT IN` over all players × all contracts (`PlayerContractRepository:30-31`). |
| `lineup` | partial on `findFirstByTeamIdAndMatchIsNullOrderByIdDesc` | Plus `team_id`, `match_id`. The `findFirst…MatchIsNull` pattern needs a partial index. |
| `scout_assignment` | `(team_id, country_id, active)` | `existsActiveForCountry` is an existence probe on exactly this triple. |
| `team` | `human_controlled` (partial), `country_id`, `type` | `findByHumanControlledTrue` full-scans. `findByType` / `findAllByTypeOrderByIdAsc` and the `findClubTeamsForOperations` `@Query` (`t.type IS NULL OR t.type = 'CLUB'`) are unindexed — `ix_team_competition` does not help. Also `stadium_id` has `@OneToOne` intent but **no unique constraint**, so "one stadium per team" is unenforced. |
| `player_zone_load` | `match_id` | The unique constraint `uk_zone_load_player_match_zone` leads with `player_id`, so `findByMatchId` is served by no index. |
| `bb_*`, `af_*` (15 tables) | `season_year`, `competition_id`, `home_team_id`, `away_team_id`, `team_id`, `player_id`, `played`, `match_date` | **Nothing indexed in either sport.** |
| `cteam`, `cplayer`, `cscompetition`, `csseasoncompetition`, `cscompetitionentry`, `csjunior` | `cplayer.team_id` and equivalents | **Nothing indexed.** `cplayer.team_id` has the same problem as `player.team_id`. |

### 4.4 `match_tick_states` — a design decision, not an index problem

`MatchTickState` stores **one row per simulation tick** with two JSONB columns
(`player_positions_json`, `ball_position_json`) covering all 22 players, ticks `0..900`. At ~2 KB
per row that is **~1.8 MB per match**, and a 2,000-match season **~3.6 GB in one unindexed
table** — which is why the design moved to replay JSON files.

`MatchTickStateRepository` has exactly two methods, `findByMatchOrderByTickAsc` and `deleteByMatch`,
neither served by an index on `match_id`. The javadoc at `MatchPersistenceService:333-338`
documents a past incident where this table silently failed to persist.

**But per §2.2, nothing currently writes it** — the only writer is dead code.

**Decision needed before any work here:** either
- delete the entity and repository, keeping replay files as the system of record (smallest change,
  removes a 3.6 GB latent problem), or
- make it the system of record, in which case it needs a unique constraint on `(match_id, tick)`,
  an index on `match_id`, and a retention/partitioning policy.

Do not index it as-is.

### 4.5 EAGER fetch traps

**`Country.clubs` is the most structurally expensive mapping defect in the schema.**
`Country.java:33` declares it as an EAGER `@OneToMany`, and `Competition.country`,
`Team.country` and `ScoutAssignment.country` are all EAGER `@ManyToOne`. So
`teamRepository.findById(id)` — which uses `@EntityGraph({"competition","country"})` at
`TeamRepository:18` — **loads all ~310 clubs of that country** plus each club's FK. One
"optimized" query becomes 1 + 310. `findWithStadiumById` and every other `Team` fetch inherit it.
This is invisible from repository code.

**18 associations fall back to `FetchType.EAGER` because no `fetch` attribute was specified.** The
dangerous ones:

- `MatchPlayerStats.match` and `.player` — EAGER. The highest-cardinality football table (22+ rows
  per match) eagerly joins twice on every load.
- `User` — **4 EAGER `@OneToOne`** cross-sport team refs, so loading any user fans out to 4 team
  rows on **every authenticated request**.
- `Lineup.team`, `Lineup.match`, and both `@ManyToMany` player lists — loading a `Lineup` always
  loads 11 + 7 players. `MatchRepository.findDetailedById` adds `LEFT JOIN FETCH` on top.
- `Transfer.player`, `Training.player`, `PromotionRule`'s two competitions,
  `Tactics.formation`, `*CompetitionEntry.team` — all EAGER.

**`open-in-view=true` is set explicitly in `application-prod.properties:4`** (and defaults to true
elsewhere), so nothing catches these — lazy collections are silently re-queried during response
serialisation. The code has to compensate with fetch joins, which it mostly does not.

The 6 `LEFT JOIN FETCH` and 9 `@EntityGraph` declarations that *do* exist are all correct. The
problem is the undeclared EAGER defaults and the entity-graph inheritance above.

### 4.6 DDL is 100% Hibernate-generated, and it never drops anything

`ddl-auto=update` in both `application-dev.properties:7` and `application-prod.properties:5`.
No Flyway, no Liquibase, no `schema.sql` / `data.sql` (verified: zero hits).

Two consequences:

1. **Any index must be an `@Index` annotation** to be created. `update` will never *drop* an
   index you remove, so index changes accumulate irreversibly — plan them accordingly and
   verify against the live schema, not just the annotation.
2. The schema is a permanent accumulation of every column ever added (`Team.logoUrl`'s comment
   explicitly relies on this).

`SeasonNumberBackfill.java:84, 105` is the one place building SQL by string concatenation:

```java
jdbcTemplate.update("UPDATE " + table + " SET season_year = ? WHERE season_year >= 1000", 1);
```

Safe today (fixed constant array) but it is raw string-built DML and the only occurrence in the
codebase. Worth a comment or a whitelist assertion so it stays that way.

### 4.7 Confirmed N+1 patterns

**Request paths — fix first:**

| # | Location | Pattern | Queries |
|---|---|---|---|
| A1 | `TeamController:200` | `.map(p -> toPlayerDto(p, matchPlayerStatsRepository.findByPlayerId(p.getId()), user))` | **1 + N** (N = squad size). Sibling at :180 already fixed with `findByPlayerIdIn` — this one was missed. |
| A2 | `TransferService:174` | `.flatMap(t -> playerRepository.findByTeamId(t.getId()).stream())` | **1 + N** (N = clubs in viewer's country, up to 310) |
| A3 | `TransferService:178` | `.filter(p -> transferRepository.findByPlayerId(p.getId())…)` | **N** — per player, *on top of* A2 |
| A4 | `TransferService:740` | `.filter(t -> playerRepository.countByTeam(t) >= 14)` | **1 + N** over AI teams |
| A5 | `ScheduleInsightService:103` | `lineupRepository.findFirstByTeamIdAndMatchIsNull…` in a lambda | per-iteration, and triggers the EAGER `@ManyToMany` → 1 + 18 player loads |
| A6 | `CountryController:163` | `teamRepository.findAll()` in a lambda | all 310+ clubs, each with EAGER `Country.clubs` + 2 EAGER O2O |
| A7 | `MatchController:112` | `playerStatsRepository.findByMatchId(matchId)` in a lambda | per-iteration on the table whose `match`+`player` are both EAGER |
| A8 | `CleanSheetService:81` | `CSPlayerRepository.findByCTeam(CTeam)` in a lambda | per-iteration |

**Match persistence hot path — the most expensive group.** This runs on every simulated match.
`persistPlayerStats`, `bumpCareerStats`, `persistMatchCondition` and `recordZoneLoad` each iterate
the same ~22 players and independently re-fetch:

| # | Location | Queries per match |
|---|---|---|
| C5 | `SimMatchService:319` | ~22 SELECTs |
| C7 | `SimMatchService:354` | ~22 SELECTs — **duplicate of C5** |
| C9 | `SimMatchService:409` | ~22 SELECTs |
| C10 | `SimMatchService:505` | ~22 SELECTs |

**Four separate passes over the same 22 players, each doing individual `findById`.** One
`findAllById(ids)` + a `Map<Long, Player>` collapses ~88 round trips to 1. (The dead
`MatchPersistenceService` has the same pattern four more times at :274, :309, :330, :183 — another
reason to delete it.)

**Job / season progression:**

| # | Location | Pattern |
|---|---|---|
| B1 | `YouthAcademyService:57,62` | 2 COUNTs × 310 teams = **620 queries per season rollover**, unindexed |
| B2-B4 | `SeasonService:354/356, 830/834, 922/926` | `findByCompetitionAndSeasonYear` + `findBySeasonCompetition` inside league loops |
| B5 | `SeasonService:193` | `competitionEntryRepository.deleteById(...)` in a lambda — 1 DELETE + 1 round trip per entry; should be `deleteAllInBatch` |
| B6 | `SeasonService:1027` | `for (Competition c : competitionRepository.findAll())` — each EAGER-loads `country` → `clubs` |
| B7 | `TrainingProgressionService:224` | N+1 on lazy `team` per training report |
| B8 | `SimMatchService:161` | `staffMemberRepository.findByTeamId` per team, 2× per match, no query cache |

**`findAll()` full-table scans: 37 call sites**, several in request paths. The worst is
`SeasonService:577` — `playerRepository.findAll()` loads all 16,354 `Player` entities with their
`@Embedded Skills` (~20 columns each). `TrainingController:137` does the same in a request
handler. `SimulationController:88, 234, 337, 413` each load the entire fixture table and filter in
Java.

**Good news:** the codebase does know about N+1. There are 6 correct `LEFT JOIN FETCH` queries and
9 `@EntityGraph` declarations, consistently applied on `TransferRepository` (6 methods). The gap
is the undeclared EAGER defaults (§4.5) and the loops above.

---

## 5. Seeding and bootstrap

### 5.1 Two different regimes

| Regime | Games | Runs on boot? | Entry point |
|---|---|---|---|
| **Football (`newLogic`)** | TIFO Sports Manager | **NO** (as of 2026-10-01) | Admin buttons → `DatabaseInitializer.buildSerbianStructure()` / `SimulatedWorldSeeder.seedAllSimulated()` |
| **Other sports + text** | AF, Basketball, CS | **YES, every boot** | `@EventListener(ApplicationReadyEvent.class)` |
| **Owner account** | all | **YES, every boot** | `StartupInitializer implements CommandLineRunner` |

Football's `DatabaseInitializer.ensureBaselineDataOnStartup()` is annotated `@Transactional` but is
**no longer an `@EventListener`** — the comment at lines 260-278 records that this was
deliberately removed on 2026-10-01. The only football code still bound to boot is the DDL-only
`sanitizeLegacySchemaOnStartup()` (line 191).

### 5.2 The biggest seeding volume: ~31,000 single-row INSERTs

`SimulatedWorldSeeder` → `PyramidBuilder.buildStatic()`. Per SIMULATED country (46 of 48; `SRB` is
ACTIVE):

- `DIVISIONS_PER_TIER = {1,2,4,8,16}` → **31 divisions**
- `CLUBS_PER_DIVISION = 10` → **310 clubs**
- 31 `competition` + 31 `season_competition` + 310 `team` + 310 `competition_entry`

**Total ≈ 31,336 rows in ≈31,336 single-row INSERTs.** Every `teams.save(...)` and `entries.save(...)`
is per-row; there is no `saveAll` and no batching in this path (and per §4.1, batching would not
help even if configured, because of `IDENTITY`).

Guard: `competitions.findByCountryIsoCodeAndType(iso, LEAGUE)` non-empty → `alreadyBuilt=true`.
Idempotent. Deliberately no players and no fixtures for simulated countries — `LazySquadGenerator`
adds 18 per club on demand.

**Fix:** `saveAll` + `SEQUENCE` id (or `JdbcTemplate.batchUpdate`). There is **no `batchUpdate` and
no `JdbcTemplate.batchInsert` anywhere in the project** — every bulk write is JPA.

### 5.3 Serbian pyramid

`DatabaseInitializer.initSerbianFootballStructure():1007-1085`:

- 1 `season`; **31 competitions** (1 Superliga, 2 Prva, 4 Srpska, 8 Okružna, 16 Opštinska) +
  `Kup Srbije` (CUP, 64)
- each division filled to 10 clubs → **310 `team`** + **310 `competition_entry`**, all per-row
- ≈**310 clubs × 25 players = 7,750-7,800 player rows**
- `ensureDoubleRoundRobinSchedule` for all 31 leagues → 90 fixtures/division ⇒ **≈2,790
  `match_fixture` rows**
- `D1` N+1: `DatabaseInitializer:568` → `teamRepository.findAll().forEach(squadNumberAssigner::…)`
  → `SquadNumberAssigner:31` does `playerRepository.findByTeam(team)` **per team** = 1 + 310
  queries, then `saveAll` **per team** (310 batches)
- `D2`: `findBySeasonCompetitionAndTeam(...).isEmpty()` inside loops at :1226, :1249, :1270

### 5.4 National teams

`NationalTeamSeeder` / `BotSquadGenerator` — 2 sides × 48 countries = **96 national sides**,
`SQUAD_SIZE = 25`, so up to **2,400 player rows**, all per-row `save()` and all non-transactional
(bug 1.3). See also the `PyramidBuilder.build()` path, which calls
`playerFactory.createRandomTeamPlayers` **without a `countByTeam` guard** — so activating a country
writes 25 players per club regardless of whether they already exist.

### 5.5 Backfills (run in the "baseline exists" branch, `REQUIRES_NEW`)

| Class | Rows | Mechanism |
|---|---|---|
| `BotLeagueStandardBackfill` | every player of every non-human LEAGUE club (~4,620 measured) | `findByTeamId` per club then **`saveAll`** — the only genuine `saveAll` in the seeding path |
| `PlayerRatingBackfill` | all players where `rating != careerRating()` (2,350 at 96, 5,250 at 0) | `players.findAll()` into memory, then **per-row `save()`** |
| `LeagueFixtureDayBackfill` | fixtures with null `dayNumber` (2,790) | **per-row `fixtures.save()`** |
| `SeasonNumberBackfill` | `season_year >= 1000` across 9 tables | raw JDBC, 1 `UPDATE` per table |

`PlayerRatingBackfill` and `LeagueFixtureDayBackfill` are the two to convert to `saveAll` + batch
first — they run on every initialise and touch thousands of rows one at a time.

### 5.6 Other-sport initializers still seed on every boot

| | `AfDataInitializer` | `BbDataInitializer` | `CSDataInitializer` |
|---|---|---|---|
| Trigger | `@EventListener(ApplicationReadyEvent)` + `@Transactional` | same | same |
| Guard | `findBySport("AMERICAN_FOOTBALL").size() > 0` → return | same | `csCountryRepository.count() > 0` → return |
| Orphan cleanup | **`TRUNCATE TABLE af_… RESTART IDENTITY CASCADE`** + `DELETE FROM common_competitions` (raw native) | same for `bb_*` | none |
| Rows on a fresh DB | **31 competitions + 310 teams + 5,580 players + ~2,790 fixtures ≈ 9,300** | **≈9,300** | 16 teams, 240 players, per-row saves |

So a single boot on a fresh DB writes **~15,000-16,000 rows** for AF+BB alone. If the guard ever
finds an empty table, they `TRUNCATE` and rewrite all of it.

Football's initializer was correctly unbound from boot on 2026-10-01. **These two were not.** Move
them behind the same admin-only gate.

`CSDataInitializer` is smaller but has 8 repository calls inside seeding loops (:116, 140, 152,
164, 168, 181, 185, 221) and creates a stadium per team without `saveAndFlush`.

### 5.7 `StartupInitializer` rewrites the owner password on every boot

`commonmanager/util/StartupInitializer.java` — `@Transactional` `CommandLineRunner`. Finds the
owner by `velibor@example.com`; if it exists, it **re-`save`s the user with a freshly
BCrypt-encoded password** and re-assigns teams (lines 115-149). Tiny (1-4 rows), but it means the
stored hash changes on every boot and the update is unconditional.

### 5.8 Seeding failures are swallowed

Every step in `ensureBaselineDataOnStartup` is wrapped in `catch (RuntimeException) { log.warn(...) }`
(lines 452-466, deliberate — the class documents why). This is exactly what hid a season of failed
seeding on the owner's Oracle instance (`DatabaseInitializer:266-273`).

Verification exists only on the admin path (`verifyFootballWorldWasBuilt()`,
`AdminDatabaseAsyncService:150`). Given the standing rule about jobs that succeed while doing
nothing, this is worth revisiting: a warn-and-continue is defensible for optional steps but not
for the pyramid build itself.

### 5.9 Caching: there is none

**Zero Spring Cache in the project** — no `@Cacheable`, `@CacheEvict`, `@CachePut`,
`@EnableCaching`, `Caffeine` or `CacheManager` anywhere in `src/main`, and no cache config in any
`application*.properties`.

Unbounded in-memory maps that exist:

| Location | Map | Bounded? |
|---|---|---|
| `sim/SimReplayStore:55` | replay views | **Yes** — `app.replay.max-entries` (200) + `max-age-days` (14) |
| `newLogic/store/MatchStore:13-14` | matches, results | **No** |
| `newLogic/repository/MatchEventRepository:13` | match events | **No** |
| `service/SquadEnvironmentService:37,39` | `pairingCache`, `reverseCache` | **No** |
| `service/AdvanceWeekAsyncService:29` | `jobsByTeamId` | No |
| `service/PresenceRegistry:54-55` | per-user last request/write | No |
| `commonmanager/util/websocket/*WSHandler:21` | sessions per match | No |
| `footballtextmanager/CleanSheetService:30` | `activeGames` | No |

`SquadEnvironmentService`'s two caches are the ones most likely to be worth bounding *and* making
authoritative — see §6.4.

---

## 6. Batch/season runs are entirely single-threaded

**Exhaustive grep of `src/main/java` for `parallelStream`, `.parallel()`, `ForkJoinPool`,
`ExecutorService`, `Executors.`, `newFixedThreadPool`, `ThreadPoolTaskExecutor`: zero hits.**

| Class | File:line | Shape | Parallelism |
|---|---|---|---|
| `ProposalBatchDiag` | `sim/ProposalBatchDiag.java:20` | `for (i < n)` | none |
| `ProposalSeasonDiag` | `sim/ProposalSeasonDiag.java:51` | `for (i < n)`, default **150** | none |
| `ProposalPassFailDiag` | `sim/ProposalPassFailDiag.java:32` | `for (i < n)` | none |
| `ProposalPhysicsDiagnostic` | `sim/ProposalPhysicsDiagnostic.java:90` | per-tick trace | none |
| `TeamStrengthProbe` | `sim/probe/TeamStrengthProbe.java:67` | nested `for (Pairing) × for (i)` | none |
| `DecisionEngineProbe` | `sim/probe/DecisionEngineProbe.java:347,402,439,471` | `for (pm) × for (seed)` | none |
| `BallPhysicsProbe` | `sim/probe/BallPhysicsProbe.java:339,423` | nested sweeps | none |
| `MatchBatchRunner` (demo) | `demo/.../MatchBatchRunner.java:50` | `for` | none |
| `ComprehensiveBatchRunner` (demo) | `demo/.../ComprehensiveBatchRunner.java:97,169` | `for` — **and simulates every match twice** (home/away then away/home) | none |
| `AsyncSimulationRunner` | `service/AsyncSimulationRunner.java:46` | `for (fixtureId)` | **none** — one `@Async` thread |

Every calibration run required by the standing rule ("measured over ≥50 matches", "never two
calibrations in one commit") is single-threaded. This is the cheapest throughput win available
and it touches no behaviour.

**Safety note, consistent with the existing determinism rule:** parallelise **across matches**
(each with its own seed via `SimulationRandom.seed(fixtureId)`), never within a match.
`MatchState.activeChasers` is already a `LinkedHashSet` specifically so seeded runs don't diverge —
that invariant is per-match and unaffected.

### 6.1 `AsyncSimulationRunner` holds a transaction open across a full simulation

`newLogic/service/AsyncSimulationRunner.java:43-55`:

```java
transactionTemplate.setPropagationBehavior(PROPAGATION_REQUIRES_NEW);   // :43  ← mutates a SHARED bean
for (Long fixtureId : fixtureIds) {                                     // :46  ← serial
    transactionTemplate.executeWithoutResult(status -> {
        matchFixtureRepository.findById(fixtureId);                     // :49
        simMatchService.simulate(fixture, false);                       // :53  ← 3,600 TICKS INSIDE THE TX
        simMatchService.persist(fixture, sim.outcome(), -1L, ...);      // :54
    });
}
```

Three separate defects:

1. **The 3,600-tick simulation runs inside the open transaction.** ~79 K snapshot allocations,
   ~30 K log writes, tens of millions of distance computations — all while holding a pooled JDBC
   connection, for pure CPU work.
2. **`TransactionTemplate` is mutated, not scoped** (:43). The injected bean is a shared singleton;
   setting `PROPAGATION_REQUIRES_NEW` here leaks into every other user of that template for the
   rest of the process lifetime. It should be `new TransactionTemplate(txManager)`.
3. **`@Async` has no bounded executor.** No `ThreadPoolTaskExecutor` bean and no
   `spring.task.execution.*` in `application.properties`, so Spring Boot's auto-configured
   `applicationTaskExecutor` (core 8, unbounded queue) is used. Combined with the CAS bug (§1.2),
   concurrent matchday job pairs quietly discard work.

**Fix:** `simulate()` outside the transaction, `persist()` inside a short one; scope the
`TransactionTemplate`; declare a dedicated bounded executor for match simulation.

### 6.2 `AdvanceWeekAsyncService` — transaction scope is defensible but worth checking

`newLogic/service/AdvanceWeekAsyncService.java:38-40` runs on `ForkJoinPool.commonPool()`
(`@EnableAsync` at `SportsManagerApplication:11` does **not** apply to `CompletableFuture.runAsync`).

It holds one transaction open across the whole week-advance, including
`countRemainingFixturesAcrossLeagues` (which loops every Serbian league calling
`ensureEntriesForSeasonCompetition` + `ensureDoubleRoundRobinSchedule`, :114-121). It simulates no
matches — it only checks none are left and refuses otherwise (:68-76). So the risk is season
maintenance, not simulation. The re-entrancy guard at :32-42 keeps one job per team but not
across teams.

### 6.3 `MatchdayJob` N+1

`newLogic/jobs/impl/MatchdayJob.java:92-96` — `competitions.findAll()`, filter by type, then
`fixtures.findUnplayedOnDay(...)` **per competition**. With 31 divisions per country this is 31
queries per country per matchday, each an unindexed scan (see §4.3).

### 6.4 Only two `@Scheduled` methods exist

| Class | Annotation | Enabled |
|---|---|---|
| `newLogic/jobs/GameClockScheduler:56` | `@Scheduled(cron = "${game.clock.cron:0 0 * * * *}")` — top of every hour | `game.clock.scheduler-enabled` — **`true` in prod**, `false` in dev; `auto-advance` likewise |
| `commonmanager/util/old/MatchEventWebSocketHandler:46` | `@Scheduled(fixedRate = 25000)` | legacy `old/`, no DB writes |

The single hourly tick dispatches by (week, day, hour) to `JobRunner`, which guards each job with a
`job_run` row keyed on `(season_year, week_number, day_number, job_key)` and runs it in its own
`REQUIRES_NEW` transaction, writing one `job_run` row per job per tick (DONE or FAILED + truncated
500-char message).

`isDue()` is `hour >= job.hour()`, so within one day each job is *considered* on every hourly tick
after its trigger — but only *considered*; DONE short-circuits.

Day jobs: `day-opened` (h0, writes nothing), `recovery` (h6, **writes nothing — bug 1.1**),
`finance` (d2 h10, **all clubs**), `training` (d4 h10, **all clubs' players**), `cup-draw` (d2 h8),
`matchday-international` (d1 h20), `matchday-league-a` (d3 h19), `matchday-cup` (d5 h18),
`matchday-league-b` (d7 h16), `week-rollover` (d7 h23), `season-rollover` (week 12, d7 h23).

---

## 7. Frontend

### 7.1 `loadDashboard()` fires ~14 requests per invocation

`static/js/dashboard.js:516-596` — 5 sequential loaders plus a `Promise.all` of **9 requests** at
:399-431 (`/teams/{id}/medical`, `/teams/{id}/lineup-template`, `/transfers/team/{id}`,
`/community/summary`, `/transfers/window`, `/api/season/friendlies/{id}/week`, `/api/game-clock`,
`/training/weekly/team/{id}/reports`).

It is a global (`window.loadDashboard`, :996) called from the sidebar and back-navigation, so
**dashboard → tab → back re-runs the entire set.** `/api/game-clock` is fetched both here and by
the 20 s poller.

`league-view.js:81-87` fires 7 parallel requests for the league table, plus an 8th
`player-directory` at :134, plus `/leagues/{id}/seasons` at :68.

### 7.2 Two permanent pollers that never stop

`static/js/clock.js:102-103`:

| Interval | Endpoint | Stops? |
|---|---|---|
| **5 min** | `GET /api/server-time` | **No** — module-level, never cleared |
| **20 s** | `GET /api/game-clock` | **No** — comment at :40-42 admits it exists to publish `window.__fmWatchStatus` for one button |
| 1 s | none (DOM only) | n/a |

~3 requests/minute for the life of the tab, on every page, forever. The three `demo.js` pollers
(:484, :518, :554) are correctly stopped via `stopWeekPreparationPolling()` etc. — those are the
well-behaved ones. The two in `clock.js` are the largest steady-state request volume in the SPA.

**Also:** `dashboard.js:832-846` polls `/admin/database-job/status` up to **240 times at 1 s**
(4 minutes). It is bounded and self-throttling (`await` in the loop), but it is a 4-minute
request stream by design.

### 7.3 Essentially no response caching, and no request cancellation

Only **three** caches exist in the whole SPA, all function-local:

1. `match-view.js:127-128` — `cachedMatchPreview` / `cachedMatchReport`, declared **inside**
   `loadMatch()`. Survive tab switches within one match page, die on back-navigation.
2. `dashboard.js:165-169` — `readWatchStatus._cached`, memoising what the clock poller set. A
   genuine deliberate cache and the only one documented as such.
3. `club-management.js:359` — `transferMarketCountry` module-scope (state, not data).

Everything else refetches from scratch on every navigation. `loadPage()` (`pages.js:448-636`)
dispatches to a loader that always calls `authFetch`. Switching to the *same* page refetches
everything. `forum`, `chat` and `events` are three separate routes all bound to the same
`loadChat()`.

**No `AbortController` anywhere.** Navigating away mid-flight leaves the old promise to land and
write into `#main-content` *after* the new page rendered. There is a defensive workaround at
`dashboard.js:950-957` rather than a cancel.

Two specific misses:

- `showStats()` (`match-view.js:325-336`) is the **one match tab with no cache** — refetches on
  every click, unlike preview and report.
- The report tab re-POSTs `POST /matches/{id}/reveal` on **every** click (`:307-308`), *before* the
  cache check.

### 7.4 The two heaviest endpoints

| Endpoint | Controller | Problem |
|---|---|---|
| `GET /countries/leagues/{id}/player-directory` | `CountryController:751-779` | **Every player in every club in the league** (id, name, teamId, teamName), no paging, no season effect on size. A 20-team league ≈ 500+ rows. Fetched on **both** the league table (`league-view.js:134`) and the stats page (`stats-view.js:88`). **Plus a server-side N+1** — it calls `player.getTeam().getId()` per row over the whole league. |
| `GET /countries/teams/{id}/players` | `CountryController:781-783` | Returns **raw JPA `List<Player>` entities** — full object-graph serialisation, bypassing every DTO the rest of the app uses. Sibling `TeamController.getPlayers` (:176-193) returns a proper `PlayerDTO`. No paging. |

Also unpaged: `GET /teams/{id}/matches` (**all** matches ever, no limit — used by the dashboard and
the pre-match preview), `GET /teams/{id}/schedule` (full season, used by the dashboard next-match
card which only needs element `[0]`), `GET /transfers` (the entire global market, sorted and
reduced client-side), `GET /community/recipients` (`userRepository.findAllByIdNotOrderByUsernameAsc`
— **every user in the system** into a `<select>`), `GET /countries/catalog` (iterates
`countryRepository.findAll()` **and** `teamRepository.findAll()` on every call).

`GET /api/zox/replay/{matchId}/chunks` is chunked and correctly designed.

### 7.5 Client-side work

- **Full `innerHTML` teardown on every render**, no diffing: `dashboard.js:526, 940, 985`;
  `community.js:235`; `league-view.js:412`; `match-view.js:86, 269`; `training-view.js:562`.
- **Full-array sorts/reduces on every render**, no memo: `dashboard.js:610-624` sorts the entire
  schedule to pick `[0]`; `club-management.js:385-398` does **four** passes over the transfer list
  plus a `Set` build; `training-view.js:504-561` sorts + maps the whole report and builds a
  `new Map` of skills **per player row** (:515).
- **No pagination on any list** — squad tables, the ~200-country world table, league schedule, all
  league matches, the whole community feed, the whole transfer market. Zero pagination controls.
- **No per-row fetch N+1 in the SPA** — this is done correctly. `loadWorldPage` (`pages.js:763-786`),
  `loadChat` (`community.js:219-285`) and `loadLeagueTeam` (`league-view.js:311-412`) all fetch one
  payload and map in memory. `league-view.js:132-146` and `stats-view.js:85-95` fetch the whole
  `player-directory` once and build a client-side name→id `Map` rather than fetching per row —
  the right pattern, undermined only by that endpoint's own size and N+1.

### 7.6 The community chat ignores the STOMP backend that already exists

There is **no WebSocket or STOMP client anywhere in `src/main/resources/static/`** — zero hits for
`WebSocket`, `SockJS`, `stomp`, `stompjs` across the whole static tree (the bundled
`three.module.js` files in `static/demo/service/ui/*/vendor/` are unrelated 3D viewer libraries).

`CommunityController` (`/community/**`) is REST-only, but a STOMP-enabled `WebSocketConfig` exists
on the backend. Instead:

- `community.js:154-160` — `POST /community/chat`, then `await loadChat()` → `GET /community/chat`
  **+ `GET /community/recipients`**. **Sending a message re-downloads the entire feed plus the
  entire user list.**
- `community.js:184-190` — admin approve/reject does the same 2 requests.
- **No live updates.** New messages appear only on navigation/reload. The dashboard unread
  indicator comes from `GET /community/summary`, re-read only when `loadDashboard()` runs — never
  on a timer.

---

## 8. Proposed plan

Phases 1-2 are where the return is. Phase 1 is hours of work; Phase 2 is the durable win.

### Phase 0 — Correctness first (half a day)

Do this before any optimisation. Optimising a path that silently does the wrong thing just makes
the wrong thing faster, and items 1.1-1.3 are the exact failure shape the project has an explicit
standing rule about.

| # | Action | File |
|---|---|---|
| 0.1 | Make `applyDailyRecovery()` honour `recoveryFor`'s cap/rate instead of a flat `+0.2`, and clamp to 0–100 (see §1.1 — the write itself is fine) | `ZoneLoadService.java:151-155` |
| 0.2 | Make the CAS decline visible, or convert it to a queue | `AsyncSimulationRunner.java:36-39` + `SimulationController.java:159` |
| 0.3 | Move `@Transactional` to `seedIfMissing()` | `NationalTeamSeeder.java:71` |
| 0.4 | Rethrow or mark rollback-only instead of returning `null` | `SimMatchService.java:295-298` |
| 0.5 | Delete the dead `MatchPersistenceService` (402 lines, 0 callers) | `MatchPersistenceService.java` |
| 0.6 | Rename `MatchEventRepository` or make events real entities — do not leave a no-op `save()` behind a `*Repository` name | `repository/MatchEventRepository.java` |
| 0.7 | Add `clearAutomatically`/`flushAutomatically` to the 6 bulk `@Modifying` queries, especially the age increments | `PlayerRepository:41`, `JuniorRepository:52`, `CSPlayerRepository:22`, `Af*`/`Bb*` repositories |
| 0.8 | Per-match log file, append mode | `ActionLogService.java:65-69, 101-103` |
| 0.9 | Fold `persistMatchCondition()` into `persist()` and batch it | `SimMatchService.java:399-425` |
| 0.10 | Move the other-sport initializers behind the admin-only gate (football's was done 2026-10-01; AF/BB were not) | `AfDataInitializer`, `BbDataInitializer`, `CSDataInitializer` |

Each of 0.1, 0.2, 0.9 needs a **live check against a running app**, not a green unit test.

### Phase 1 — Stop throwing away 80% of the work (1-2 days)

| # | Action | Expected effect | File |
|---|---|---|---|
| 1.1 | Gate `TAC` and `THR` per-tick logging behind a `proposal.log.tactical` flag (default off) | **−24,335 lines / −2.2 MB / −24 K syscalls / −24 K global-lock acquisitions per match**, no behaviour change | `TacticalIntentEngine:131`, `ThreatOverrideEngine:120` |
| 1.2 | Move `simulate()` out of the transaction; scope the `TransactionTemplate`; declare a bounded executor for match simulation | No pooled connection held for multi-second CPU work; fixes the singleton-bean mutation leak; stop discarding concurrent matchday work | `AsyncSimulationRunner:43-55` |
| 1.3 | Parallelise the batch/season diagnostics and `AsyncSimulationRunner` **across matches** (each already independently seeded) | Near-linear speedup on every calibration run and every matchday batch | all of §6 |
| 1.4 | Hoist the player list and build one 22×22 distance matrix per tick in `ThreatOverrideEngine.evaluate()` | Removes ~38 M distance ops and ~8,000 stream objects per match | `ThreatOverrideEngine:62-127` |
| 1.5 | Make `DecisionOption.reason` lazy; drop the duplicate `List.of`; gate the decision log | −13 `String.format` per tick, −1 list allocation per tick, −7 `String.format` in the formatter | `CleanDecisionEngine:263-271, 313-759`, `ActionLogService:118` |
| 1.6 | Use `SimReplayView` downsampling (stride 10) for the HTTP path instead of embedding the full undownsampled snapshot list | 30.3 MB response → ~3 MB; stop writing 30 MB into the source tree per request | `ProposalMatchController:73, 125-135` |
| 1.7 | Call `recorder.getEvents()` once; compute `buildPlayerStats(team)` once | −2 full event-list copies and −1 stream+sort per match | `ProposalMatchOutcomeBuilder:36, 46, 62, 120, 146` |
| 1.8 | Bound `eventLog` retention; stop duplicating the whole log into heap and then into `match.json` | Memory + payload | `ActionLogService:81-94` |

### Phase 2 — Indexes and ID strategy (3-5 days, the durable win)

| # | Action | Why |
|---|---|---|
| 2.1 | Switch hot write paths from `IDENTITY` to `SEQUENCE` (`pooled-lo`, `allocationSize=50`), starting with `Player`, `MatchPlayerStats`, `PlayerZoneLoad` | **Makes the existing `batch_size=50` config work at all.** Currently dead code. Prerequisite for 3.1, 3.2, 5.5. |
| 2.2 | Index `match_fixture (season_year, week_number, day_number, played)` | 8 repository methods + the hourly production job. The identical triple is already indexed on `job_run`. Single clearest omission. |
| 2.3 | Index `player (team_id)`, `(team_id, position)`, `(team_id, injured)`, and partial on `last_played_at IS NOT NULL` | Largest table (~16,000 rows), most-called query in the app, daily full-scan recovery query |
| 2.4 | Index `match`: `home_team_id`, `away_team_id`, `season_year`, `competition_id`, `played`; composites `(competition_id, season_year, round_number)` and `(played, match_date, id)` | 4+ `ORDER BY match_date` queries with no index; `OR` across two team columns needs a UNION or two lookups |
| 2.5 | Index `match_player_stats (player_id, match_id)` **+ add the missing unique constraint** on `(match_id, player_id)` | Fixes the `TeamController:200` N+1 and makes duplicate stat rows impossible |
| 2.6 | Index `app_user (username)`, `(email)` | Every login and registration |
| 2.7 | Index `junior (team_id, status)`, `competition_entry (team_id)`, `season_competition (competition_id, season_year)` | Kills 620-query season rollover and the loop probes; add the missing unique constraint on `(competition_id, season_year)` |
| 2.8 | Index `transfer`, `finance_ledger_entry`, `player_contract`, `loan`, `lineup` FKs and status columns | The market page, the season finance wipe, the contract `NOT IN` subquery |
| 2.9 | Index `bb_*` and `af_*` (15 tables) — currently **zero** | Neither sport has any index at all |
| 2.10 | **Make `Country.clubs` LAZY** | `findById` on a Team currently loads all ~310 clubs of that country. The single most expensive mapping defect, and invisible from repository code. |
| 2.11 | Make `MatchPlayerStats.match`/`.player` and `User`'s 4 cross-sport `@OneToOne` refs LAZY | Avoids fan-out on the highest-cardinality table and on every authenticated request |
| 2.12 | Drop the redundant `ix_competition_entry_sc`; fix `ix_team_name_prefix` → `text_pattern_ops` or `lower(name)` | Redundant prefix index; the "prefix" index does not accelerate `ILIKE 'SRB%'` |
| 2.13 | Decide `match_tick_states`: delete it, or make it the system of record with a unique `(match_id, tick)`, an index on `match_id`, and a retention policy | Latent ~3.6 GB/season problem on a table nothing currently writes. **Do not index as-is.** |

Reminder: `ddl-auto=update` **never drops** an index. Verify against the live schema, and remember
2.12's removals will persist.

### Phase 3 — Make seeding fast (2-3 days, depends on 2.1)

| # | Action | Expected effect |
|---|---|---|
| 3.1 | `PyramidBuilder.buildStatic()` → `saveAll` for competitions, teams, entries | **≈31,336 single-row INSERTs → 3 batched ones** |
| 3.2 | `WorldCatalogSeeder.seedAll()` → `saveAll` (48 countries) | 48 round trips → 1 |
| 3.3 | `NationalTeamSeeder` / `BotSquadGenerator` → `saveAll` | up to 2,400 rows |
| 3.4 | `PlayerRatingBackfill` (5,250 rows) and `LeagueFixtureDayBackfill` (2,790 rows) → `saveAll` + batch | Runs on every initialise |
| 3.5 | Replace `DatabaseInitializer:568` `findAll().forEach(...)` with one `findAll` of players grouped by team | 1 + 310 queries → ~2 |
| 3.6 | Resolve `SeasonService:193` `deleteById` loop → `deleteAllInBatch` | 1 DELETE + 1 round trip per entry → 1 statement |
| 3.7 | Consider Spring Cache (`@Cacheable`) for read-mostly lookups: `tactics_fallback.json` rules (506 rules, loaded per tactical refresh), squad environments, formations | Currently zero caching anywhere; `SquadEnvironmentService`'s two unbounded maps are the best candidates to make authoritative *and* bounded |

### Phase 4 — Frontend (2-3 days)

| # | Action | Expected effect |
|---|---|---|
| 4.1 | Stop the two `clock.js` pollers when no page needs them; or drive the watch-status button from the 20 s poll only while the dashboard is visible | −3 requests/minute for the life of the tab, on every page |
| 4.2 | Cache `/api/game-clock` so `loadDashboard()` doesn't duplicate the poller's request; make `loadDashboard()` idempotent so back-navigation reuses state | −1 to −14 requests per dashboard cycle |
| 4.3 | Add a module-level response cache in `loadPage()` keyed by (route, params) with explicit invalidation on mutation | Eliminates the refetch-every-navigation pattern; fixes `showStats()` and the `reveal` re-POST |
| 4.4 | Add `AbortController` to `loadPage()` and cancel on navigation | Fixes the stale-response race that `dashboard.js:950-957` currently works around |
| 4.5 | Page `/countries/leagues/{id}/player-directory` and return a DTO (not raw `Player`) from `/countries/teams/{id}/players`; add `LIMIT` to `/teams/{id}/matches` and `/transfers` | 500+ row payload, one N+1, and full-entity serialisation |
| 4.6 | Wire the existing STOMP `WebSocketConfig` into a chat client | Send = 1 POST instead of POST + 2 full-payload GETs; live updates instead of navigation-triggered refreshes |
| 4.7 | Sort/paginate server-side; stop re-sorting full arrays on every render (`club-management.js:385-398`, `dashboard.js:610-624`) | Client CPU + payload size |

### Phase 5 — Housekeeping (low effort, prevents future mistakes)

| # | Action |
|---|---|
| 5.1 | **Update `AGENTS.md`** — it still documents `RealisticMatchEngine`, `RuntimeSaveToDB`, `SimulationService`, `RoundSimulationAsyncService`, `/api/v2/match/**`, `cleanSheet/` and `old/` as live. None exist in `src/main/java`. Already flagged in `sprintProgress.md:199`; still unaddressed, and it will mislead anyone planning DB work. |
| 5.2 | Document the deliberate divergences: match events live in `match.eventJson`, not a table; ticks live in replay JSON files, not `match_tick_states` |
| 5.3 | Bound the remaining unbounded maps: `MatchStore`, `MatchEventRepository`, `SquadEnvironmentService`, `PresenceRegistry`, both WS handlers, `CleanSheetService` |
| 5.4 | Add a whitelist assertion to `SeasonNumberBackfill`'s string-concatenated table names |
| 5.5 | Give `AsyncSimulationRunner` and the day jobs a documented bounded executor; record in `sprintBacklog.md` that batch diagnostics are parallel across matches and why that is deterministic |
| 5.6 | Reconcile the sprint docs: `sprintBacklog.md` still says "722 passing" (actual 791) and "Sprint 5 ◀ CURRENT" (kanban has moved on), and `newLogic/sim/PROPOSAL_CURRENT_STATE.md` is three calibration generations behind `PROPOSAL_PROGRESS.md` |

---

## Recommended first afternoon

If you want one day's work with the largest measurable return, in this order:

1. **0.1** — make `applyDailyRecovery()` use `recoveryFor` instead of a flat `+0.2`, and clamp.
   The morale write itself already works; the amount applied is wrong.
2. **0.2** — make the `AsyncSimulationRunner` CAS visible or queue the work. A manager clicking
   "Simulate all" can currently lose an entire league silently.
3. **1.1** — gate `TAC`/`THR` logging. −2.2 MB and −24 K syscalls per match, no behaviour change.
4. **1.2** — move `simulate()` out of the transaction.
5. **2.2** — the `match_fixture` index. One annotation, unblocks the hourly production job.

Then Phase 2 in earnest — the `IDENTITY` → `SEQUENCE` switch plus the nine indexes is the durable
win, and it is also the prerequisite for every seeding speedup in Phase 3.

---

## Measurement gaps

Stated plainly, because it affects how much to trust the estimates above:

- **No timing instrumentation exists anywhere in the engine.** There is no `nanoTime` or
  `currentTimeMillis` in `MatchOrchestrator` or any diagnostic. The proposal docs cite counts,
  never durations — so there is no recorded baseline for how long a match takes to simulate, and
  every "this will be faster" claim above is an estimate from operation counts, not a measurement.
- **The log-volume figures in §3 are the one hard measurement**, taken from the existing
  `target/proposal-app.log` artifact (30,513 lines, 2.8 MB, exactly one match).
- **Row counts come from reading the code and the docs**, cross-checked against the doc-recorded
  figures (48 countries, 96 sides, 2,140 zone-load rows, 4,650 club players). They have not been
  counted against the live database.
- **Nothing was executed.** No query was run, no `EXPLAIN` was taken, and `target/` and the 30 MB
  `match.json` in the source tree were deliberately left untouched.

**To get a real baseline before committing to Phase 2**, this is the cheapest useful step:

```bash
time mvn exec:java \
  -Dexec.mainClass=org.example.footballmanager.newLogic.sim.ProposalBatchDiag \
  -Dexec.args="10 42"
```

That gives per-match wall clock and the H/A side-split line, which is also the mirror-bug detector
the calibration process already relies on. Repeat it before and after each phase.

For the database side, the highest-value measurement is `EXPLAIN (ANALYZE, BUFFERS)` on the five
queries that matter most — `MatchFixtureRepository.findUnplayedOnDay`,
`PlayerRepository.findByTeamId`, `MatchRepository.findByHomeTeamIdOrAwayTeamIdAndPlayedTrue…`,
`MatchPlayerStatsRepository.findByPlayerId`, and the
`PlayerZoneLoadRepository.findLoadsPlayedSince` behind `RecoveryJob` — against the **prod** schema,
since `ddl-auto=update` means the live database has drifted from the annotations.
