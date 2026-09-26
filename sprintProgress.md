# Sprint Progress Log

Running log of completed work. Newest entry at the top.

**Project:** TIFO Football Manager — UI Football
**Backlog:** `sprintBacklog.md` · **Audit:** `expertAudit.md`
**Started:** 2026-09-26

---

## Pre-Sprint 0 — Dead code quarantine

**Date:** 2026-09-26 · **Duration:** ~50 min · **Commit:** `80bf15a` — *"Quarantine dead match engines and orphaned pages (~40k LOC)"*
**Files changed:** 129 (125 renames R100, 2 modified, 2 added)

### What was done

Moved 125 unreachable files (≈40,000 LOC) into `footballForDelete/`, which is **outside the Maven build**. Nothing is lost; `rm -rf footballForDelete` when confidence is high.

| Area | Files | LOC |
|---|---:|---:|
| `newLogic/engine_v1/` (all 12) | 12 | ~9,775 |
| `newLogic/engine/` (v2 package) | 26 | ~4,563 |
| `demo/swingUIDemo/` | 55 | ~11,900 |
| Dead services (`SimulationService`, `WeekPreparationAsyncService`, `RoundSimulationAsyncService`, `PlayerMovementDecisionService`, `MatchLiveService`, `MatchLiveSession`, `MatchOrchestrator`, `TacticsAdjustmentService`) | 8 | ~2,500 |
| `NewMatchController` | 1 | ~200 |
| `util/RuntimeSaveToDB` + `util/events/*` | 7 | ~600 |
| `tools/SimulationRunner`, `old/*` | 5 | ~800 |
| v2 `model/MatchState`, `model/MatchRuntime` | 2 | ~200 |
| Orphaned frontend (`realisticDemo*.html/js`, `cleanSheet*`, `index.html`, `dasboardBackup.css`) | 8 | ~8,000 |
| `README.md` (rationale + salvage notes) | 1 | — |

### How deadness was verified

Not by counting references — that produced a wrong answer. Instead:

1. For every class, enumerate references across `src/main/java` excluding its own file.
2. Walk each caller chain **upward** until reaching an HTTP endpoint, a live `@Component`, or a dead end.
3. Move the verified set, then treat **`mvn compile` as ground truth** for the orphans it revealed, and move those too. Repeat until clean.
4. `mvn test`.

### Two traps found

- **`MatchOrchestrator` name collision** — `newLogic/service/MatchOrchestrator.java` (v2, dead) vs `newLogic/sim/engine/MatchOrchestrator.java` (**live**). A name-based reference search flags both.
- **`MatchState` name collision** — `newLogic/model/MatchState.java` (v2, dead) vs `newLogic/sim/model/MatchState.java` (**live**). All live usage imports the `sim.model` one. Compiler-confirmed: only `model/MatchState.java` referenced the moved packages.

Also fixed a self-inflicted error mid-task: my first exclusion filter used `\|` inside `grep -E`, where it is a literal pipe, not alternation — so the filter silently did nothing. Switched to letting the compiler drive.

### Audit correction this triggered

Verifying `engine_v1/` properly **overturned a P1 finding in my own audit.**

`expertAudit.md` §4.1 claimed AI-vs-AI league matches were produced by a Poisson dice roll (`MatchEngine.simulateQuickScore`), leaving the league table statistically incoherent with the match engine. **That was wrong.** The live `POST /simulation/current-round/simulate-all` (`SimulationController.java:129-158`) already routes every fixture through `SimMatchService.simulate()` — the proposal engine:

- user's league: synchronously in-loop (`:133-137`)
- all other leagues: `AsyncSimulationRunner.simulateInBackground()` → `simMatchService.simulate(fixture, false)` (`AsyncSimulationRunner.java:59`)

The league table is likewise written by `SimMatchService:246-261`. `simulateQuickScore` was only reachable from `WeekPreparationAsyncService` and `RoundSimulationAsyncService` — **both of which have 0 callers.** A reference search had reported `RoundSimulationAsyncService` as live; it is injected nowhere.

**Consequence:** Sprint 7 was cut from 5–7 days to 1–2 days and reduced to a regression test that locks the invariant in place.

### Collateral finding: the live engine has no injury generator

`RealisticMatchEngine.maybeTriggerInjury:1410-1489` was the **only** injury generator in the codebase — the best mechanic in the audit (fatigue → injury risk, position-weighted, minute-windowed, with `pickInjuryRiskPlayer` weighting selection by fatigue). It was quarantined with the rest of `engine_v1/`.

**The proposal engine currently produces no injuries at all.** Sprints 1, 2 and 4 all assumed injuries exist.

**Action taken:** the file is preserved in `footballForDelete/backend/newLogic/engine_v1/RealisticMatchEngine.java`, the salvage requirement is documented at the top of `footballForDelete/README.md`, and migration is now tracked as **Sprint 1.6** with full source line references. Per your instruction, the move went first and the migration follows.

### Documentation updated

| File | Change |
|---|---|
| `expertAudit.md` §4 | Marked resolved; added the two name collisions; added salvage list |
| `expertAudit.md` §4.1 | **Retracted and replaced** with the verified finding and its cause |
| `expertAudit.md` §3.5, §9.1, §9.2, §11.3, §11.4 | Repointed file references to `footballForDelete/` |
| `expertAudit.md` appendix | Index rows updated; `AI-vs-AI dice roll` struck through with the correction |
| `sprintBacklog.md` Sprint 1 | **+3 tasks:** S1.6 injury port, S1.7 penalties, S1.8 substitutions. Effort 8–10 d → 16–20 d |
| `sprintBacklog.md` Sprint 6 | Engines marked done; remainder narrowed to stubs + docs |
| `sprintBacklog.md` Sprint 7 | Replaced with the 1–2 day verification sprint |
| `sprintBacklog.md` | Dependency graph, effort table, critical path, 30-day plan all recalculated |
| `footballForDelete/README.md` | New — per-file rationale, verification method, salvage warnings |
| `sprintProgress.md` | This file |

### Verification

| Check | Result |
|---|---|
| `mvn -o compile` | ✅ clean, 0 errors |
| `mvn -o test` | ✅ **84 tests, 0 failures, 0 errors**, BUILD SUCCESS |
| Dangling references in `static/` | ✅ none (`realisticDemo`, `cleanSheet`, `swingUIDemo`, `api/v2/match` all clear) |
| Live path intact | ✅ `newLogic/sim/**` untouched; `demo/service/**` untouched; `static/demo/service/ui/proposal/**` untouched |
| LOC removed from build | ~40,000 |

### Deliberately kept

| Path | Why |
|---|---|
| `newLogic/sim/**` | The live engine |
| `demo/service/**` (19,488 LOC) | Design reference (`corePrinciples.md` §1–48) + calibration oracle. Sprint 6.2 freezes it and moves it out of the build path |
| `static/demo/service/ui/proposal/**` | The replay viewer the SPA actually navigates to |

### Follow-ups raised

- Sprint 1 grew by 3 tasks. Sprint 7 shrank by ~5 days. Net Sprint 1 +8 days, Sprint 7 −5 days.
- Sprint 6.5 (documentation rewrite) is now **urgent** — `AGENTS.md` describes engines deleted in this commit, and the two name collisions are precisely the kind of thing that misleads a future agent.
- `util/match/MatchContext.java`, `util/analysis/MatchAnalyzer.java`, `util/players/{PlayerActionProbabilityModel, PlayerConditionService}.java` are now orphans. Left in place to keep this commit focused; Sprint 0.6 / 6.4.

---

## Sprint 0 — Stop the bleeding

*(✅ COMPLETE — 5 of 7 done, 1 skipped, 1 done with a corrected scope)*

### S0.1 — Close the €1 transfer exploit ✅ DONE

**Date:** 2026-09-26 · **Duration:** ~25 min · **Commit:** `0a62d08` — *"S0.1: close the EUR 1 transfer exploit"*

**The bug:** `normalizePrice` was `Math.max(1.0, requested)` with no lower bound against the asking price. `buyListedPlayer` passed the client-supplied price straight into `completeTransfer`, so any listed player could be bought for €1. The UI prefilled a `window.prompt` with the asking price but only validated `numeric > 0`.

**The fix:**

| Change | Detail |
|---|---|
| `normalizePrice` → `resolveAgreedPrice` | New single choke point for every agreed price. Asking price is now a hard floor → `422 PRICE_BELOW_ASKING` |
| Invalid input rejected | `≤ 0`, `NaN`, `Infinity` → `422 INVALID_PRICE`. Prevents the old `Math.max(1.0, …)` behaviour reappearing via a numeric edge case |
| `directBuyPlayer` routed through the guard | Closes the second hole where direct-buy on an *already-listed* player completed with **no** price check (`:209-214`) |
| Defence in depth in `completeTransfer` | Re-checks the floor so no future caller can bypass the guard by calling it directly |

**Deliberate boundary behaviour** (decided with PO, tested): exactly the asking price closes the deal, and overpaying is allowed. Rationale: a player who is on the transfer list has already agreed to be sold at that price, so the fee is the only open question. This matches the existing one-click "Buy listed" UI and avoids adding clicks to the common path. A listed-player sale therefore does **not** require a separate seller-acceptance roll.

**Tests:** `TransferServicePriceGuardTest` — 10 tests, mocked repositories, no Spring context. Includes one that asserts the exploit moves **no money at all** (buyer budget, seller budget, player club, listing status all unchanged, and `verify(never())` on both repository writes).

**Verification:** `mvn test` → **94/94 green** (was 84).

**Note:** the UI still uses `window.prompt` for prices. That is cosmetic and tracked in Sprint 8.3; the server-side guard is what matters and it is now authoritative.

---

### S0.2 — Fix the transfer-list soft-lock ✅ DONE

**Date:** 2026-09-26 · **Duration:** ~35 min · **Commit:** `2a67162` — *"S0.2: fix the transfer-list soft-lock"*

**The bug:** a bare "register interest" entry (club name, no price) set `canRemove` to false, but since it is not a priced offer `canRejectOffer` also stayed false. Once any club registered interest, the player could **never** be delisted again — no seller action, no admin path. The UI rendered a permanently disabled button.

**The fix** — three escape routes where there were none:

| Change | Detail |
|---|---|
| `hasPricedOffer()` helper | Separates a priced offer (`"X offered €Y"`) from a bare interest entry |
| `removeFromTransferList` | Blocks only on a **priced** offer; clears stale interest on delist |
| DTO: `hasPricedOffer`, `canClearInterest` | UI can now explain *why* removal is blocked |
| `clearAllInterest()` | Seller wipes all interest/offers without accepting one. `rejectOffers` was unreachable in the soft-lock case because it needs a priced offer to exist |
| `withdrawInterest()` | An interested club can back out cleanly, so it cannot hold a seller's player hostage |
| `forceUnlist()` | Admin operator escape hatch |

**Security decision:** `force-unlist` is routed through **`/admin/transfer-list/{playerId}`** in `AdminController`, not `/transfers/**`. Only `/admin/**` is role-guarded (`SecurityConfig.java:107`), so putting it under `/transfers` would have let any authenticated user delist another club's player. Caught before committing.

**UI:** both transfer panels now render a **Clear interest** button, and the disabled "Remove" tooltip distinguishes a live offer from bare interest.

**Tests:** `TransferListSoftLockTest` — 10 tests. Two assert no money moves on clear-interest; one asserts force-unlist beats even a live priced offer; one asserts withdrawing a priced offer entry is parsed correctly out of the legacy string format.

**Verification:** `mvn test` → **104/104 green** (was 94).

---

### S0.3 — Idempotency guard on weekly training ✅ DONE

**Date:** 2026-09-26 · **Commit:** `14a414b`

Two changes, both about failures that were invisible.

**1. `MatchPersistenceService.saveTickHistory` swallowed every exception** with `catch (Exception e) { }`.
That is precisely why the `match_tick_states` schema defect went unnoticed: every tick silently
failed to persist, replay playback had no data, nothing logged. Tick granularity means a few bad
frames shouldn't abort a match, so the save stays best-effort — but the first three failures now log
at WARN with tick number and match id, and a summary reports saved-vs-skipped.

**2. `runWeeklyTraining` had no idempotency guard.** The report row was found-or-new and
overwritten while growth was re-applied every call — the "Run Weekly Training" button was an
unlimited free skill-point exploit. Growth is now applied at most once per (season, week).

**The trap a naive guard would have hit:** the season-advance path also calls training, and a
manager can legitimately press "Advance Week" after training manually. A blanket 409 would have
made the week **impossible to advance**. Added `runWeeklyTrainingIfDue` for the two advance call
sites (`AdvanceWeekAsyncService`, `SimulationController`) — a no-op returning the stored report.
Kept `force=true` for an admin re-run.

8 tests, including the two that matter: a rejected second run must apply no growth and issue no
repository write; the advance path must return the stored report without writing anything.

---

### S0.4 — Feed `*Exact` into ratings and OVR ✅ DONE (scope corrected)

**Date:** 2026-09-26 · **Commit:** `cff62db`

**My audit and the backlog both had this wrong.** They claimed the match engine read the floored
`int` and discarded ~90% of training work. It does not — `RealSquadFactory.toSimSkills:185-196`
already calls `getExact(...)` for all eight skills. Training has always reached the engine.

The real gap was the **display and rating layer**: `getRatingScore()` read the ints, so displayed
OVR (`PlayerDTO`) and match rating (`MatchRatingCalculator`) did not move for weeks of training.
That is fixed, plus a `visibleInt()` accessor.

Left alone deliberately: `getTotalForRating()` (coarse display helper) and the fluent `int`
accessors (no `newLogic` consumer — `PlayerSnapshot` uses a different `Skills` class).

A test asserts whole-number ratings are **numerically identical** to the old formula, so this is a
precision gain with no balance shift on existing saves.

---

### S0.5 — Passive fatigue recovery ⏭️ SKIPPED

**Date:** 2026-09-26

The task assumed fatigue accumulates and never recovers. **It does not accumulate either.**
Verified across the live domain:

| Writer | Status |
|---|---|
| `Player.addFatigue` | **0 callers** |
| `util/match/MatchContext:59,63` | Dead — only consumer has 0 callers |
| `engine_v1/RealisticMatchEngine` | **Quarantined** to `footballForDelete/` |
| `sim/engine/FatigueSystem:24` | Writes `sim.model.Player`'s own 0..1 field, a **different class**, never persisted to `Skills.fatigue` |
| `TeamMedicalService:54` | Only live writer, and it only *reduces* |

So `Skills.fatigue` is never increased anywhere in the live path. Adding passive recovery would be
recovery for a permanently-zero value — dead code by the project's own rule. **The mechanic needs a
source before it needs a sink.**

Folded into **S1.6** (port the injury model), which now owns the whole chain: accumulate fatigue
from minutes played → passive weekly recovery → injury risk. Also carries the fix for the false
"Weekly passive healing still applies" claim in `medical-view.js:95`.

---

### S0.6 — Delete the dead offside / rules paths ✅ DONE

**Date:** 2026-09-26 · **Commit:** `7feb6d6`

- **`sim/rules/FootballRules.java` deleted** — instantiated at `MatchOrchestrator:84` into a field
  never read, a full duplicate of the live offside geometry in `OffsideService` (with its own 0.5
  threshold against `OffsideService`'s 0.30). The unused `EngineInterfaces.FootballRules` placeholder
  went too.
- **`pointSegmentDistance` consolidated into `SimUtils`** — was copy-pasted into three classes.
- **`isPathBlocked()` was a literal `return false`**, making the caller's "+12 clear path" carry
  bonus unconditional: a winger into a wall scored identically to one into space. Implemented as a
  lane-segment check.
- **`nearestOpponentBeatsHimToIt()` had no call site** — the bug it was written to fix (passing to
  a receiver whose marker arrives first) was never actually fixed. Wired into `scorePassOptions` (−35).
- Documented that `duelsWon` and `tackles` always totalling the same number is **correct**, not a
  duplicated field: one winner, one loser, loser's counter credited as a tackle attempt.

Two items are deliberate **behaviour changes**, which is why they belong before Sprint 1
re-calibration. Engine sanity-checked: `ProposalSeasonDiag` 8 matches → 3.4 goals, 34.5 shots, 82%
pass accuracy, 49/51 possession, 374 duels, 6 corners, 15.5 fouls, 1 scoreless.

> ⚠️ **The calibration baseline in `expertAudit.md` §5 is stale** — measured before the quarantine
> and before the concurrent REC engine work. **Sprint 1 must re-baseline from current HEAD.**

---

### S0.7 — Security and endpoint hygiene ✅ DONE (far larger than specified)

**Date:** 2026-09-26 · **Commit:** `a6476b7`

The backlog said "move the replay API behind JWT". The `permitAll` list actually contained the
**entire game API**: `/api/**`, `/teams/**`, `/players/**`, `/matches/**`, `/match-stats/**`,
`/training/**`, `/countries/**`, `/commonmanager/**`, `/proposal/api/**`, `/api/v2/**`,
`/dashboard.html`, `/zox/**`, `/start-realistic-demo`.

Any anonymous visitor could read every squad, read and rewrite lineups and the tactic editor, run
training, list and buy players, trigger matches, and advance the season. For a game of competing
managers that is fatal.

**Left public deliberately:** `/basketballmanager/**` and `/americanfootballmanager/**` — neither
reads the JWT anywhere under its `/js` folder, so they depend entirely on `permitAll`, and I cannot
test them. Breaking two untouched modes to secure the one under development is the wrong trade.
Flagged for their own auth pass.

**Bonus fix:** `shouldReturnUnauthorized` only matched paths *starting* with `/api/`, so an
unauthenticated GET to `/proposal/api/**` returned **302 to the HTML login page**. The fetch
followed it, `response.json()` threw on HTML, and the SPA rendered a generic "API Error" card
instead of "please log in" — the exact misleading-error pattern that hid a dozen dead routes.

The proposal viewer sent no `Authorization` header on its four API calls; added an `apiFetch()`
helper there, leaving static assets on plain `fetch`.

`ProposalViewerMatchIdentityTest` now sends a **real signed token**, exercising the actual
`JwtAuthenticationFilter` — including its `loadUserByUsername` lookup, so the subject must be the
seeded owner. Added `proposalApiRejectsUnauthenticatedRequests()` to lock the 401 in.

**Deferred to Sprint 8** (each logged in the backlog): `sortBy` whitelist, validation on
`create` endpoints, `LineupController` exception type, `POST /auth/register` (**needs PO
decision**), admin registration endpoints, `fetchPlayerRatingSummary` arity, `login.js` status
element.

**Tests: 84 → 124 (+40).**

---

## Sprint 1 — Engine calibration

### S1.0a — Re-baseline + pending-shot classification ✅ PARTIAL

**Date:** 2026-09-26 · **Commit:** `e5b739c`

**Re-baselined from current HEAD** (50 matches, seed 42). The old §5 numbers in `expertAudit.md`
were measured before the quarantine and before the concurrent REC engine work, so every Sprint 1
target had to be re-derived. All Sprint 1 targets are now stale and must be rewritten against this
table before tuning begins.

| Metric | Fresh baseline | Real PL | Old (stale) audit |
|---|---:|---:|---:|
| goals | 3.4 | 2.7 | 5.4 |
| shots | 32.4 | 25 | 29.4 |
| shots on target | 12.4 | 8–9 | 12.9 |
| on-target % | 37.9% | 33% | 43.9% |
| **saves** | **14.1** | **~5.8** | 9.8 |
| pass accuracy | 81.5% | 80–86% | 77.9% |
| passes | 740 | 450–500 | 582 |
| duels won | 373 | ~100 | 598 |
| interceptions | 27.7 | 12–16 | 36.1 |
| corners | 5.6 | ~10 | 5.0 |
| goal kicks | 19.8 | 12–15 | 40.7 |
| throw-ins | 73.4 | 35–45 | 18.7 |
| fouls | 16.6 | 22 | 29.6 |
| yellow cards | 2.3 | 4–5 | 5.0 |
| offsides | 4.25 | 2–4 | 3.6 |
| red cards | 0.15 | 0.2 | 1.0 |
| possession | 48.7 / 51.3 | 50 / 50 | 49.4 / 50.6 |
| scoreless | 6% | ~6% | 0% |

Several things already improved on their own (goal kicks 40.7 → 19.8, throw-ins into range,
red cards 1.0 → 0.15, scoreless 0% → 6%). The remaining outlier is **saves**.

### 🔴 RESOLVED — the saves anomaly was a physics bug, not a counter bug

**I got this wrong twice and am recording both mistakes.**

**Mistake 1 (e5b739c):** I called "14.1 saves against 12.4 shots on target" arithmetically
impossible and built an investigation on it. **It is not impossible** — a keeper legitimately
saves off-target attempts, and `BallPhysicsEngine` never checked on-targetness before recording a
save. My diagnosis was wrong even though the numbers looked wrong.

**Mistake 2 (749fd21, the real fix):** the actual defect, found by splitting the counter rather than
arguing about it — **26% of off-target attempts were being "saved"**, ~5.3 phantom saves per match,
purely because the flight segment passed near the keeper's arms. `saves/SOT` was 1.16 against a real
0.68. The pending-shot classification work in `e5b739c` was correct in itself and was kept, but it
was never the cause.

**The fix:** `onTrajectoryForGoal()` extends the flight to the goal line and requires the crossing
point to be inside the frame plus a 0.35-cell (~3.5 m) fingertip margin. Geometry gate only — it does
not make any save easier, it stops the keeper fishing at balls that were already missing.
**`GoalkeeperEngine` is untouched.**

| Metric | Before | After | Real PL |
|---|---:|---:|---:|
| saves | 14.4 | **7.1** | ~5.8 |
| saves / SOT | 1.16 | **0.56** | 0.68 |
| shots missed | 9.3 | **14.9** | ~14 |
| shot conversion | 51.5% | **30%** | ~32% |
| on-target save rate | 73.4% | **70%** | ~68% |

**The keeper was never the problem.** His on-target save rate was always realistic; he was simply
also catching shots that sailed wide.

### Where the calibration actually stands now

| Metric | Current | Real PL | Status |
|---|---:|---:|---|
| saves | 7.1 | ~5.8 | ✅ |
| saves / SOT | 0.56 | 0.68 | ✅ |
| shots missed | 14.9 | ~14 | ✅ |
| shot conversion | 30% | ~32% | ✅ |
| on-target % | 39% | 33% | ⚠️ high |
| **shots** | **33.1** | **25** | ❌ **32% high** |
| **shots on target** | **12.6** | **8.5** | ❌ **48% high** |
| **goals** | **3.8** | **2.7** | ❌ **41% high** |
| scoreless | 0/50 | ~6% | ❌ none |

**Shot VOLUME is now the single dominant outlier, and goals follow from it.** With the statistics
finally trustworthy, S1.1 continues: reduce shot volume toward 25/match and SOT% toward 33%, which
should pull goals to ~2.7 and bring nil-draws back on their own.

**Tests: 124 → 132.**

### S1.1b — Shot conversion calibration ✅ DONE

**Date:** 2026-09-26 · **Commit:** `562785f` · **Docs:** `e088cec`

On-target probability and post-aim distribution rebalanced once the save statistics were
trustworthy. On-target % 39% → 33% band, conversion 30% → ~32%.

### S1.4 / S1.5 — Duel count and the press ✅ DONE

**Date:** 2026-09-26 · **Commit:** `e4c3b1c` · **Docs:** `3f8b1f4`

A bounded chase burst was restored (`CHASE_SPRINT_MULTIPLIER`) and the press radius tightened so
pressing is a challenge rather than a steal. Duels 373 → 268.

**Owner decision recorded in `3f8b1f4`:** shot volume, goal count and the remaining numeric
outliers are **no longer the priority**. Connecting the mechanics comes first; the numbers get
re-derived once the mechanics stop lying. S1.1c/S1.2/S1.3's original targets are therefore
deferred, not abandoned.

### S1.8 — Substitutions ✅ DONE

**Date:** 2026-09-26 · **Commits:** `0f4c8dd`, `b334e91` · **Docs:** `0ae6ea4`

The single biggest engine gap. `isUnavailable()` existed and nothing ever set it, so the same
eleven played 90 minutes regardless of fatigue, injury or bookings, and a red card meant 10-vs-11
with no recourse — which is also *why* fatigue and injuries barely mattered, since being tired was
only ever a slightly slower player.

Shipped: real bench of 9, five substitutions, three windows, injury and fatigue-triggered automatic
replacement, live viewer status.

**`b334e91` — correction from the owner:** a **sent-off player is never replaced**. The team stays a
player down for the rest of the match. The first implementation substituted him, which quietly
cancelled the entire cost of a red card.

### S1.6 — Injuries, fatigue persistence, weekly recovery ✅ DONE

**Date:** 2026-09-26 · **Commit:** `1a2d5f0`

**The live engine produced zero injuries.** The only injury generator in the codebase was
`RealisticMatchEngine.maybeTriggerInjury`, quarantined to `footballForDelete/` earlier the same
session — so the medical page, the injury model and the substitution logic all had nothing to act
on. Sprints 1, 2 and 4 all assumed injuries existed.

- `InjuryService` ports `maybeTriggerInjury`. Risk scales with fatigue and the victim is picked
  weighted by fatigue, so a tired player is both more likely to go down and more likely to be the
  one who does. Goalkeepers excluded.
- Two deliberate changes: severity is an injury *type* with a realistic absence range (was three
  hardcoded buckets), and **one roll per team, not per player** — the original's per-player loop
  would have multiplied risk by squad size.
- **Fatigue now persists.** `FatigueSystem` accumulated during a match but only on the engine's own
  `Player` and never left it. The Medical Center, the injury model and season recovery all read the
  DB entity, so a player could run a season at zero recorded fatigue however hard he played.
- **Weekly passive recovery added**, age-scaled (22 pts at ≤24 → 11 at 34+). Fatigue had *no sink*
  at all except the free, unbounded medical button.
- Corrected the medical page, which had been telling players "weekly passive healing still applies"
  while nothing of the kind existed.

**7 tests**, including one asserting fatigue actually drives risk.

### S1.7 — Penalties are taken again ✅ DONE

**Date:** 2026-09-26 · **Commit:** `115b818`

A penalty was awarded, counted, logged as `PENALTY_AWARDED` — and then **never taken**. The taker
picked the ball up off the spot and the generic final-rows hard-SHOT rule fired, so a penalty was an
11 m shot with no run-up, no dive and no nerve. `PENALTY_KICK` / `PENALTY_SAVED` / `PENALTY_MISS`
were declared in `ActionLogService` and produced by nothing.

`PenaltyEngine`, split into a pure `resolve()` and an `apply()`. A penalty is not a shot: the keeper
**commits to a dive before the kick**, so it is modelled as a read probability, not through
`GoalkeeperEngine.trySave` — that is a geometric proximity test and structurally cannot express a
keeper who has already guessed wrong.

Calibration over 400k samples:

| taker (striker/tech) | keeper | scored | saved | missed |
|---|---|---:|---:|---:|
| 12 / 12 | 12 | **76.6%** | 19.4% | 4.0% |
| 12 / 12 | 20 | 67.9% | 28.0% | 4.0% |
| 12 / 12 | 2 | 86.1% | 9.9% | 4.0% |
| 20 / 20 | 12 | 80.7% | 18.3% | 1.0% |

Real conversion is ~76%, so the average row is on target and the spread across the keeper range
(~10% poor → ~28% elite) matches reality.

**Three bugs the tests caught, all of which would have shipped:**

1. **"Guessed wrong" was silently right one time in three.** Failing the read rolled a *fresh
   uniform* side, which coincided with the taker's actual side ~33% of the time — turning a 37% read
   into an effective 58% and pushing saves to 27%.
2. **A goalkeeper could be handed the ball.** `selectTaker` filtered on `row > 6.0` to mean "not a
   defender", so a keeper standing in the opposition half won on shooting skill.
3. **The freeze.** The claim block clears the set-piece type on the same tick the taker reaches the
   ball, so the type was gone before anything could read it.

`ProposalBatchDiag` now prints `*** PENALTY CHAIN BROKEN ***` if an awarded penalty is not followed
by a kick. It earned its keep immediately — it reported `5 awarded but 0 taken` before any test did.

### S1.7b — Realistic penalty rate, and a penalty that can no longer be erased ✅ DONE

**Date:** 2026-09-26 · **Commits:** `705bee6`, `1cec395`

Once penalties could actually be taken, the award rate became measurable for the first time:
**0.07/match against a real ~0.27**. S1.7's task text had asserted the rate was already correct on
the strength of `PENALTY_FROM_BOX_FOUL = 0.06`; that was never measured. It went unnoticed for the
life of the engine because an un-taken penalty is indistinguishable in the aggregate from a rare
one — the counter looked plausible and nothing followed it.

The constant was derived from measured quantities:

| quantity | measured (200 matches) | real |
|---|---:|---:|
| box fouls/match | 1.655 | ~2.5–3.5 |
| VAR confirms a penalty | 99.1% (3 overturns in 331) | — |
| penalties/match before | 0.07 | ~0.27 |

`1.655 × rate × 0.991 = 0.27` → **0.165**. VAR was measured specifically to rule it out as a lever.

**Result: 0.24 penalties/match.** Goals/shots/pass unchanged, so it is an isolated change.

#### The chain invariant then found a worse bug

`48 awarded but 47 taken`. Bisected to seed 123:

```
tick 2653  award  spt=PENALTY_HOME  carrier=null  rtaker=H10
tick 2654          spt=FREE_KICK    carrier=null  rtaker=H10   <-- displaced
tick 2655          spt=null         carrier=H10                 <-- played as a free kick
tick 2658  ball has left the pitch. No PENALTY_KICK, ever.
```

The collision was not a random race — it was a **rules** question. A foul that is both a penalty
*and* an offside fires both; the offside path called `handleFreeKick`, overwrote `setPieceType`, and
the penalty — re-derived from that mutable field at the moment the taker reached the ball — read
`FREE_KICK` and downgraded itself.

**Owner rule: offside has priority.** Implemented at the root in `DuelService.evaluateDiscipline` —
if the fouled player is the `offsideFlaggedReceiver` the penalty is never created; an `OFFSIDE` event
is recorded and `handleOffsideFreeKick` restarts play.

The penalty is also now an explicit `MatchState.penaltyPending` flag latched at the award rather than
re-derived from a mutable field, with a per-tick watchdog that forces the kick after 40 ticks so a
penalty can never evaporate.

> **Open question for the PO, deliberately not decided unilaterally.** This is stricter than Law 11.
> The Laws penalise *"whichever offence occurs first"*; IFAB's FAQ covers our case exactly (attacker
> plays the ball, then is fouled in the box → indirect free kick, not a penalty). The divergence is
> the **reverse** order: an attacker fouled **before** playing the ball is still a penalty under Law
> 11, and is not one here. Coherent as a game rule, stricter than the Laws.

**Tests: 152 → 176.**

### S1.10 — Replays survive a restart, and the store is bounded ✅ DONE

**Date:** 2026-09-26 · **Commit:** this section

`SimReplayStore` was an unbounded in-memory `ConcurrentHashMap`. Two problems, both of which only
appear once you play a season rather than a single match:

- **Replays died with the process.** `Match.replayId` is persisted, so after a restart *every* past
  match pointed at a blob that no longer existed — and the viewer got a bare 404, indistinguishable
  from a match that never had a replay. A restart turned "your replay expired" into "the replay
  feature is broken".
- **It was unbounded.** A season of simulations accumulated every replay view — each a downsampled
  tick snapshot of a whole match — in the heap, with nothing ever evicting them.

Now file-backed under `app.replay-dir` (default `./replay-data`), one JSON per replay, written
atomically. The in-memory map is only a read cache and is capped to the same retention as the files,
so the heap is bounded regardless of process lifetime. Retention: `app.replay.max-entries`
(default 200) and `app.replay.max-age-days` (default 14); eviction deletes the file, so disk is
bounded too. Ids resume past the highest on disk after a restart, so a new replay cannot collide
with a file from the previous process.

`SimReplayController` now answers **410 GONE** with a `replay_expired` body instead of 404, and
`by-match` clears the dangling `replayId` from the database on first read, so it is a one-way
transition rather than a repeated failure. `replay-data/` is gitignored.

**5 tests**, including that a fresh store instance over the same directory still serves the replay.

### S1.11 — Already implemented ⚠️ BACKLOG ENTRY WAS STALE

The backlog claimed THRU / CROSS / CENTER were unreachable and entered only via the final-two-row
hard rule. **They are first-class options** (`CleanDecisionEngine:1075` — "ALL options compete.
THRU / CROSS / CENTER are included on purpose"), and over 3 matches they fire at:

| action | per match |
|---|---:|
| PASS | 641 |
| SHOT | 41 |
| CLEAR | 33 |
| CROSS | 23 |
| CENTER | 23 |
| THRU | 8.7 |

Crosses at ~23/match sit in the real 20–30 band. No work needed; the entry needs correcting rather
than implementing.

### Browser popup — root-caused and fixed

Not a UI test at all. `SportsManagerApplication` implemented `CommandLineRunner` and opened a browser
at `http://localhost:8080/home.html` on **every context start — including the ones inside
`@SpringBootTest`**. The two `BaseTest` integration classes were each launching a browser at a port
nothing was listening on, which is why a dead tab appeared on every test run.

Replaced by `BrowserLauncher`: opt-in via `app.open-browser=true` (default **off**), refuses to run
on the `test` profile regardless of the property, honours `server.port` instead of hardcoding 8080,
and opens `/login.html` rather than `/home.html` — an unauthenticated visitor has no token, so
`home.html` only bounced them to login one hop later. Verified no browser process spawns during a
Spring context test.

### S1.2 — Restart inversion ✅ DONE (goal kicks only)

**Commit:** `744dc71` · **Docs:** this section

A clearance was launched at `MAX_BALL_SPEED` (1.5 c/t) airborne, and a ball in flight only ends by
decelerating below `STOP_SPEED` or leaving the pitch — **there is no flight target**. With
`AIR_DECEL` 0.03 that is `1.5² / (2 × 0.03)` = **37.5 cells of travel on a pitch 7 rows long.**
Every clearance left through an end line.

Power is now solved from the intended range against the deceleration the ball will actually
experience, `v = sqrt(2 × GROUND_DECEL × range)`, and the aim is hooked toward a flank — previously it
was pure ±row with no lateral component, so a clearance could never reach a touchline.

| | before | after | real PL |
|---|---:|---:|---:|
| goal kicks | 35.9 | **21.9** | 12–15 |
| corners | 7.4 | 6.4 | ~10 |
| throw-ins | 76.7 | 75.5 | 35–45 |

The backlog's root-cause note assumed `AIR_DECEL` 0.15; it is 0.03, so the real overshoot was **5×
worse** than estimated.

**Coupled effects, deliberately not tuned** (owner: mechanics first, stats later): clearances that
stay on the pitch create second balls, so goals 3.6 → 4.3, shots 35 → 40, interceptions 53 → 72, and
box fouls rise with it, taking penalties to 0.47/match. That is the deferred shot-volume problem plus
the box-foul under-production already logged as **S1.7c**, not a new defect.

**Throw-ins at 75.5 against a real 35–45 are now the largest restart outlier** and are the next
thing to trace — they come from passes leaving the pitch sideways, not from clearances.

### Stoppage time — the real clock ✅ DONE

**Date:** 2026-09-26 · **Commit:** this section

`SubstitutionService.insideOpenWindow()` answered "is a substitution window open?" with
`state.getRestartTaker() != null` — *"is anybody walking to a dead ball"*. Its own comment admitted
*"the engine has no explicit stoppage clock"*. That is not a rule, it is a coincidence: roughly right
when a substitution followed a restart, wrong for a window opened by a stoppage the restart system
never sees.

`StoppageClock` is the real thing, and it has two halves that are easy to conflate:

1. **Play stopped** — the clock does not run, so a VAR check or a treatment does not quietly consume
   match time.
2. **Added time announced** — time lost to goals, injuries, substitutions and cards is announced at
   45 and 90 and then played out. This was missing entirely: the half simply ended on schedule, so
   every one of those events silently ate playing time.

Booked per half, from measured Premier League behaviour: goal 40s, injury 50s, VAR 60s, red 35s,
penalty 30s, substitution 25s, yellow 20s, and 4–8s for the ordinary restarts. Capped at 6 minutes
a half so a flurry of injuries cannot produce an absurd announcement, and floored at one tick because
every half of every real match has some.

#### Three bugs, each found by the batch going wrong

- **A stoppage that only stopped the clock was not a stoppage.** The first version set
  `state.setStopped(true)` and left the rest of the pipeline running — players moved, duels resolved,
  the ball travelled with nobody playing. A "paused" match produced ~50% more events and every
  statistic inflated. A stoppage now halts the whole tick, not just the clock.
- **`state.stopped` was doing double duty.** It already meant *half-time*. Sharing it deadlocked any
  match where a goal fell near half-time: both flags were set, and a stoppage could never clear the
  one it did not own, so the clock stayed stopped for the rest of the game. The stoppage now keeps
  its own state and the clock consults both.
- **The added-time logic was circular, then backwards.** The clock needed an announcement that only
  happened because the clock had stopped; the first fix then applied the *first* half's added time to
  the *second* half and every match finished at 47 minutes. Each half now runs in two explicit phases
  — the scheduled 45, then the announced added time — and `isMatchFinished` follows the current half
  end instead of a hardcoded 3600, which had been discarding the added time anyway.

A measured run announces 90–270 seconds a half, which is the real range.

**Deliberately not chased** (owner: mechanics first, statistics later): with the match now genuinely
playing 90+ minutes, goals 4.26, shots 40, throw-ins 77, goal kicks 22, corners 6.7, penalties 0.41,
and no 0-0 in 100. All of these were previously measured against Premier League figures; that
comparison is retired for now. The one worth keeping an eye on is the absence of goalless draws —
with ~4 goals/match a 0-0 is genuinely rare, so it may be nothing.

**`RealSquadSimulationSmokeTest` asserted `getMatchTicks() == 3600` exactly.** It was pinning the
bug: the whistle at 90:00 regardless of the time booked. Corrected to assert the match finishes and
runs at least 90 minutes, with the six-minute cap as the upper bound.

**9 tests in `StoppageClockTest`. 190 total.**

### Conditional substitutions — the last mechanic gap in Sprint 1 ✅ DONE (engine + persistence)

**Date:** 2026-09-26 · **Commit:** this section

Manager's live rules: *"at minute 60, if we are losing, bring on X for Y"*. The backlog's own
assessment was right that this is a layer on top of S1.8 rather than a rebuild — the bench, the
five-sub budget, the three windows and role-aware replacement selection all already existed.

**`ConditionalSubstitutionRules`** evaluates every rule each tick. Status is one of
`PENDING` / `WAITING_FOR_STOPPAGE` / `FIRED` / `VOID`, and a void rule carries a **reason** —
`PLAYER_ALREADY_ON`, `PLAYER_UNAVAILABLE`, `NO_SUBS_LEFT`, `NO_WINDOWS_LEFT`,
`PLAYER_ALREADY_ON_PITCH`, `EMPTY_BENCH` — so the manager can see *"1 rule no longer possible —
why"* rather than watching a rule silently do nothing.

**The window rule is now honest.** A substitution is only legal while play is stopped, so a rule that
comes true mid-flow reports itself `WAITING_FOR_STOPPAGE` rather than changing players illegally. It
completes at the next real stoppage — which now exists, courtesy of the stoppage clock.

**Precedence chain**, exactly as the owner specified — `onTick()` was split so the orchestrator can
seat the rules between the two passes:

1. **Injury** — `onTickInjuriesOnly()`. Not a decision anybody made; free of time and of a window.
2. **Manager's rule** — `conditionalSubs.onTick()`.
3. **Fatigue auto-sub** — `onTickFatigueOnly()`. The fallback when nobody asked.

A red card sits outside the chain entirely: a sent-off player is never replaced, so it cannot
consume a slot a rule was counting on.

**Persistence** is server-side and keyed by match (`SubstitutionPlan` + `SubstitutionPlanController`).
Holding the plan in the browser means a reload at minute 55 silently loses every rule set at minute 0.
`PUT` replaces the whole plan rather than merging, because a merge leaves rules the manager believes
they deleted still queued to fire.

#### Two bugs the tests caught, both of which made the feature look broken

- **A rule silently died reporting "empty bench" with five players on it.** `pickReplacement` is
  role-aware and returns `null` when the bench has nobody for that role. The report was a lie, and
  the fix is a fallback to any available player plus an honest reason.
- **The rule tried to take the goalkeeper off.** With a squad where every outfielder is equally tired,
  "most tired player" returned the keeper — who is normally first in the list — and then failed,
  because no keeper was on the bench. An automatic swap of the goalkeeper is not a decision a rule
  should ever make; the fatigue pass already refuses it, and now so does this.

#### Not done

The **UI** — the rules builder and the live view of which rules fired, which are spent and which are
void. That is the part the manager actually touches and the backlog correctly identifies as the bulk
of the work. The engine and the API it needs are in place.

**14 tests** (10 rule semantics, 4 controller). The controller tests mint a real JWT against a real
user row rather than mocking the filter away, so they also prove the endpoint is genuinely protected.

---

## Sprint 2 — The economy

**Started:** 2026-09-26 · **Owner direction:** connect the system; statistics are not the target.
**Owner rules honoured throughout:** teams are identified by `teamId` and never by name; the away
sector is always 20% of the ground.

### S2.1 — Seed a real world ✅ DONE · `305e5b1`

Every one of the 310 clubs was created with `budget = 0.0` and `reputation = 50.0`, so the whole
economy was inert — gate income, sponsorship, prize money and transfer budgets all scale off those
two numbers, which made them all zero and all flat.

`EconomyProfileService` derives them from the division tier with per-club variance. The variance
matters as much as the tier: a reputation that is a pure function of tier makes attendance, playoff
odds and offer acceptance flatlines. It is derived from the **team id** rather than drawn at random,
so a re-seed does not reshuffle the country's strength order and invalidate a saved league table.

Stadium scale follows the tier too — a 5,000-seat ground for a Superliga club makes gate income
meaningless.

#### The team-name collision: not a style issue, a correctness bug

> Owner, 2026-09-26: *"apsolutno se mogu dva tima zvati isto zato je potrebno da se uvek gledaju po
> teamId"*

`Team.name` has **no unique constraint** and `TeamRepository.findByName` returns `Optional<Team>`, so
two clubs sharing a name break it outright. What it was actually doing:

`populateLeagueWithTeams` rebuilt its "already used" set **per league**. League A took a random name,
League B drew the same one, `findOrCreate` returned the **same** `Team` by name, and
`addTeamToLeague` then called `team.setCompetition(...)` — moving a club that was already in another
division. One club, two leagues, league table pointing at the wrong one.

- The used-ids set is now **global per seeding run**.
- `addTeamToLeague` refuses to reassign a club that already has a division, and says so in the log.
- `AttendanceService`'s rivalry bonus compared `homeTeam.getName().charAt(0)` to
  `awayTeam.getName().charAt(0)` — so any two clubs starting with the same letter were a "derby",
  and a club was a rival with itself. Now keyed on ids.
- The derby check compares **country ids**, not country names, for the same reason.

### S2.2 — The gate, ticket tiers, and pitch maintenance ✅ DONE · `305e5b1`

`AdmissionService` owns three rules so there is exactly one definition of each:

1. **Ticket tiers.** A club does not sell one price — it sells economy / standard / premium, and the
   spread is a real lever. Cheap seats fill the ground, premium seats buy cash. Each tier has a floor
   and a ceiling so the settings screen cannot set a €0 or €500 ticket.
2. **The away sector is always 20% of the ground**, and the home club can never sell it. A sold-out
   away end is what makes a big club's away trip worth taking.
3. **Gate revenue weights each tier by how full it is**, so an empty premium block is worth nothing.

`AttendanceService` responds to what the owner asked: home **reputation, success and recent form**,
plus the away club at a much lower weight — a big club travelling still brings a crowd, but never
past the cap. **Ticket price affects demand**, and hits the walk-up home crowd harder than the
travelling end. Recent form is what makes a turnaround feel like one: reputation does not move for a
week, form does.

**Pitch wear is real.** Every match costs condition; the club sets a weekly maintenance budget;
condition only recovers out of it, **capped by the long-term quality of the surface**, so a
neglected pitch recovers to a poor ceiling rather than to 100. Condition and quality are separate
numbers because the old single `pitchQuality` could not express *"this club stopped investing"*.
Unspent budget carries forward, so a club can save up for a resurfacing.

#### Three calibrations the tests caught

- `RESTORE_PER_EUR` was €50 **per condition point**, so the whole €50,000 weekly cap was worth more
  than the pitch's entire 0–100 range. Now €2,000/point, calibrated against the wear figure.
- `realisedGateRevenue` filled the **premium block first** while its own comment said "fill premium
  last" — so a 30% crowd was worth *more* per head than a full one. Real crowds fill the cheap end
  first. The comment and the code disagreed; the test caught the pair.
- `setStandardPrice` saved through the repository, so a pricing rule could not be unit tested without
  one. The stadium is cascaded from the team anyway.

### S2.2b — The ledger, and an economy for every club ✅ DONE · `4d09e40`

`FinanceLedgerEntry` + `FinanceCategory` (12 categories, income and cost) and
`WeeklyFinanceService.applyWeeklyFinances(team, seasonYear, week)`.

**Append-only, not a running balance.** A manager who cannot see *why* the money moved cannot fix
it, and a single number on `Team.budget` cannot answer "which week did the wage bill double, and
what happened that week". Amounts are stored signed — income positive, cost negative — so a category
sums to the net without re-reading the enum.

**Income:** gate (realised per-tier price), broadcast (`Competition.reputationWeight × base` — that
field has been seeded as `tier × 20` since the beginning and **read by nothing**, so a second-tier and
a first-tier club were on the same money), merchandising. **Costs:** the wage bill
(`Player.earnings`, seeded realistically and read by nothing until now), facility upkeep, and pitch
maintenance charged from the programme the club actually funded.

**Every club settles, not just the user's.** This is the difference between a game and a spreadsheet.
An AI club with no economy can never be bought, sold, or promoted out of trouble, which quietly breaks
promotion and relegation. Per-club `REQUIRES_NEW` transaction, and a failure for one club is logged
and stepped over rather than rolling back the other 309.

**Idempotent per club and week** — `advanceWeek` is reachable from more than one path, and a double
settlement is a silent double wage bill.

### S2.4 — Board expectations and manager trust ✅ DONE · `fc558a2`

A manager game needs a manager. Without a board there is no consequence for a bad season, no
tension in a transfer window, and no reason to care about the wage bill beyond it being a number on
a screen.

**FFP-lite is the headline rule:** `weekly wage bill ÷ weekly income`. Shown to the player rather
than applied behind their back — a trust penalty the manager cannot see is a gotcha, not a rule.
Bands: under 0.90 prudent, under 1.15 healthy, under 1.35 strained, above that critical. Under 0.9
the board notices too, because a manager who never spends anything is also not winning anything.

**An unplayed season is `UNKNOWN`, not insolvent.** With no settled weeks there is no income to
divide by, so the ratio is `null` and health is `UNKNOWN`. Treating "no data" as "bankrupt" would
have shown every new club as a crisis in its first week.

**Trust** starts at 60 and moves on the three things a board actually reacts to: solvency, league
position, and whether the players are happy — the last feeding straight into S2.6's morale. Below 20
a sacking review is flagged. Concerns and plaudits come back as sentences, because "trust 43" on its
own is not actionable.

**The whole client-side finance fiction is deleted.** `club-management.js` was inventing three
sponsors, a monthly income of `budget * 0.055`, a wage budget of `squadSize * 1850`, and six months
of history that had never been played. None of it was in the database, so the screen looked identical
for a solvent club and a bankrupt one. It now reads the ledger, and if the API is unavailable it
**says so** rather than inventing plausible numbers — a wrong number on this screen is worse than no
number.

### S2.3 — Staff and sponsors as real entities ✅ DONE

**Date:** 2026-09-26 · **Commit:** this section

There was **no `StaffMember` or `Sponsor` entity anywhere in the codebase.** The staff directory was
a hardcoded array of literal strings in the browser (`staff-directory.js:12-51`) reading from an
endpoint that returned a constant, so every club in the country had the same four coaches with the
same ages, contracts and wages — and the wages shown were the wages of nobody. Coaching had **zero**
simulation effect.

- **`StaffMember`** — role, name, age, contract end, weekly wage, and six 1-20 attributes
  (`development`, `tactical`, `motivation`, `goalkeeping`, `fitness`, `scouting`) consumed by
  Sprint 4 and Sprint 5. Staff are **specialised**: a scout's scouting attribute is genuinely high, a
  physio's fitness is.
- **`Sponsor`** — name, tier (`TITLE` only for a top-flight club), annual value, term, and a
  **performance bonus clause**, so winning a cup is worth more than a mid-table finish. An expired
  contract pays nothing, which is what makes losing a sponsor an event.
- **Size and quality scale** with the division tier and the club's reputation: 3–6 staff, wages from
  €355/week at the bottom to over €12,000 for a top-flight head coach.
- **The specific people are derived from the team id, not drawn at random.** A random draw reshuffles
  every club's staff on each reseed, which makes a saved league meaningless and evaporates any
  relationship the manager had built with their head coach. There is a test that reseeds and asserts
  the head coach is the same person.
- **Both now reach the ledger** as `STAFF_WAGES` (a real weekly cost) and `SPONSORSHIP` (real weekly
  income). Before this, the wage bill was being measured against income that existed only in the
  browser.

`StaffDirectoryController` replaces `/demo/teams/{id}/coaches` and reports vacancies — a club with no
head coach is visibly broken rather than quietly employing three of them.

#### Three bugs, all from one habit

`stableUnit` returns a **signed** long. Three places took `% N` without `Math.abs`:

1. `spread` could go to −2, so `attr()` **divided by zero** and the seeder threw for every club.
2. Contract end could land in the **past**, so a freshly seeded club had already-expired staff.
3. (Same class of error, caught in review rather than by a test.)

And one design bug the tests caught: **staff and sponsorship lines were written with a `null` week**,
so they were paid but invisible to the weekly view and to the idempotency check — money leaving the
account with nothing to show for it.

**8 tests. 243 total.**

### S2.5 — The transfer budget, and the wage bill as the real constraint ✅ DONE

**Date:** 2026-09-26 · **Commit:** this section

The transfer budget was `max(budget * 0.38, squadValue * 0.04, 50000)` — a formula in the browser
that the player could see and nobody could act on. It is now a **board-granted** allowance, and the
reasoning is attached to it so the manager is told *why* the number is what it is.

Graded from **annual income left after wages, less a 15% reserve**, then capped at half the cash on
hand. The cash cap is the important half: a club with €20m in the bank has not got €20m to spend,
and a club that spends it all on one striker is how you go bust the season after winning the league.

**The wage bill is the primary constraint, not the fee.** A transfer fee is a one-off; the wages are
forever. So `canAfford` tests the wage bill *after* signing against a ceiling of 1.15× weekly income,
and when it refuses it says which limit stopped it — "the fee is affordable; the wage is not" — rather
than a bare no. That is the constraint that makes this a management game rather than a shopping list.

A club with no settled income has been granted **nothing**, and says so, rather than inventing a
figure from its cash balance.

### S2.6 — Morale, and form finally meaning something ✅ DONE

**Date:** 2026-09-26 · **Commit:** this section

`Player.form` was a creation-time constant, read by the rating maths as `(form − 6) × 1.2` and by the
DTO as a flat boost. A player who had scored four in five was exactly as likely to be good on Saturday
as one who had not played, and nothing the manager did could change it.

- **Morale 0-100 and form 0-10 are separate on purpose.** Form is a week-to-week swing; morale is the
  season-long state that decides whether the swing happens at all. Form **decays to 6 at the end of a
  season** — without that, a great run is permanent and every player eventually sits at 10.
- **Morale moves on what actually happens:** minutes played (a token appearance is nearly as bad as
  none; not being picked is the worst thing there is), goals and assists, the result, his own rating,
  whether he is being paid what he is worth, and whether he has been listed.
- **It reaches the match engine.** `Player.confidence` on the sim model, 0.5–1.5, folds into
  `ExecutionQuality.evaluateShot` **through a new player-aware overload**, and the shooter is passed
  in. It scales the striker's *finishing*, not the chance of attempting the shot — a player who
  cannot believe in himself does not hit the target less often so much as he hits it worse. Making it
  an on-target bonus would have turned morale into a shot-volume knob.
- **The morale effect is deliberately narrow**, 0.90–1.10. A wide band turns a bad run into a
  feedback loop: a player who misses once stops scoring, stops being picked, and misses more. There
  is a test asserting the band stays under 0.25 wide.
- Wired in `SimMatchService.bumpCareerStats`, the one place where every player's line for the match is
  already in hand. The team result is derived from the scoreline rather than stored per player — it
  is a property of the match, and 22 copies of it can disagree with each other.

**`PlayerConditionService` deleted** — 40 lines, zero callers.

#### Note on a stale backlog entry

S2.6 task 1 said *"wire `MoraleSystem.getConfidenceModifier()` into the engine — it has zero
callers"*. **`MoraleSystem` does not exist.** There is no such class in the codebase. The task was
describing a plan as though it were a defect in existing code. The morale→performance link is built
here from scratch.

#### One ripple worth recording

Adding `morale` to the JPA entity grew Lombok's positional `@AllArgsConstructor` by one argument, and
two test factories construct `Player` positionally. Both were updated. A 21-argument all-args
constructor on an entity is a liability that will keep paying this tax; worth replacing with a builder
the first time a third field is added.

**11 tests. 254 total.**

### S2 UI — the Finances and Staff pages, on real data ✅ DONE

**Date:** 2026-09-26 · **Commit:** this section

The last of Sprint 2: the two screens that were still reading hardcoded arrays after the API
existed.

#### Staff directory — fully fabricated, now real

`staff-directory.js` built four coaches and five support staff out of **literal strings** and read
them from `/demo/teams/{id}/coaches`, which returned a constant. The header reported
`Departments 3 · Filled slots 9 · Owner Velja` — all constants. Every club in the country had the
same staff, the same ages, the same contracts, and **the wages on screen were the wages of nobody**.

Rewritten against `/api/teams/{id}/staff` and `/api/teams/{id}/sponsors`. Two things worth noting:

- **The tabs are derived from the data, not a hardcoded list of three.** An unmapped role falls into
  an "other" department that always renders, so adding a role later cannot make staff silently
  vanish. `YOUTH_COACH` therefore became a visible Academy tab rather than being hidden.
- **The "focus" line is built from the attributes actually stored** — the two strongest of the six —
  instead of a per-index string. A scout's row now says *Recruitment (18) · Scouting (16)* because
  those are his numbers, not because he is the second scout in a literal.

The profile page shows the real contract season, the real wage, and all six attributes. Vacancies
are surfaced: a club with no head coach says so.

#### Finances — a season selector, and honest empties

The ledger was already per season on the server but the page was hard-wired to the current one, so
**a manager reviewing last season — the main thing the page exists for — could not**. The summary
endpoint is now season-aware, `seasonsWithLedger` lists the seasons that exist, and the page offers a
picker that survives navigating away and back.

A season with no weeks reads as unsettled and carries a notice. It does not render zeros, because
zeros and "nothing has happened yet" look identical and only one of them is true.

**9 ledger tests after the change. 256 total.**

### Sprint 2 test count

221 before the ledger, 228 after, **235** after the board, **243** after staff and sponsors, **254**
after morale and the transfer budget, **256** after the UI. 9 ledger, 7 board, 8 admission,
9 pitch-maintenance, 8 staff/sponsor and 11 morale/budget tests this sprint.

## Sprint 3 — Contracts and transfers v2

**Started:** 2026-09-26

### S3.1 — Contracts ✅ DONE · `ae725b8`

There was **no contract entity at all**. A player's wage was a field on the player and nothing tracked
when it ended. That is the whole reason the free-agent market was *impossible by construction*: a
transfer required a selling team, and a player could never stop having one.

- **`PlayerContract`** — player, club, weekly wage, length, signed/expiry season, release clause,
  squad role, squad number, and `onLoanFrom` for loans. Expiry is the mechanism that lets a club be
  **rebuilt rather than only bought from**.
- **`SquadRole`** — `STAR` / `STARTER` / `ROTATION` / `PROSPECT` / `YOUTH`, and it is not decoration:
  a squad's wage structure is a *shape*, and a model that paid every role the same per unit of value
  made every squad cost the same.
- **Expiry runs in the week advance** (`SeasonService.expirePlayerContracts`). A contract expires at
  the *end* of its season, not during it, and the record is kept with no club rather than deleted —
  the wage history and squad role stay meaningful, and a released player is still a player.
- **Renewal with a wage demand derived from four things**, all of which a real agent would use: what
  he is worth, his squad role, his **age** (a 19-year-old accepts less, because the next contract is
  where the money is), and his **form** (he asks for what he is doing now, not what he did last
  season) plus a small morale term. Refusing is not a null result — the player **asks for a
  transfer**, and that is recorded.
- **Squad registration is checked before signing**: 25 senior + 8 academy, with the reason returned.
  A club cannot quietly build a 40-man squad.
- **Backfill gives every existing player a plausible contract** — long deals for the young, short
  ones for veterans, release clauses on roughly a third of the senior squad, all derived from the
  player id so a redeploy does not reshuffle the world. Safe to run repeatedly.

#### An 18× performance bug in the week advance

The backfill's first version looped **every player in the database** and ran a contract lookup for
each one — an N+1 over the whole table, on a path that runs on every week advance. The test that
exposed it took **358 seconds**.

Replaced with a single query returning the players who need a contract. Same test: **19.6 seconds**.
This was a production defect, not a test annoyance, and it was only visible because the test
database had grown to a few thousand players.

**11 tests. 267 total.**

### S3.2 — Real negotiation ✅ DONE · entity + service, legacy path made safe

**Date:** 2026-09-26 · **Commit:** this section

`Transfer.interestedTeams` was a `Set<String>` of `"Partizan offered €450000"`, parsed back with
`indexOf(" offered €")` and `replaceAll`. Two real defects, both now pinned by tests:

- **Offers were deduplicated by a prefix match on the club name.** A club named `Partizan` wiped the
  offers of `Partizan United Youth` — the strings are indistinguishable by prefix.
- **The buyer was resolved with `findByName`.** That returns `Optional<Team>` and therefore
  **throws** on a duplicate name, and two clubs sharing a name is explicitly allowed. It was a
  guaranteed crash, not a theoretical one.

**`TransferOffer`** replaces the string set. The buyer is a foreign key, so identity is never
inferred from a label. Fee, wage and contract length are three separate columns because they are
three separate things to negotiate — the fee is where the seller has leverage, the wage is where they
do not, and a single number cannot express a deal where the two sides have agreed the fee and are
still stuck on the wage.

**`NegotiationService`**: multi-round threads (max 5 — football does not have infinite haggling),
seller counters, buyer counters back, and per-offer accept / reject / withdraw.

- **The seller chooses which offer to accept**, and rejecting one bid does not clear the rest. The
  old `acceptBestOffer` wiped every offer on acceptance, so a seller could not pick a lower bid from a
  better-fitting club without destroying the whole auction.
- **The player is a third party.** A move can be agreed between two clubs and still fail, because the
  player refuses the personal terms. An offer that meets the fee but misses the wage is recorded as
  *agreed between the clubs, refused by the player* — with the reason — rather than silently
  completing.
- **Agent fee** 2–5% of the fee, scaling with deal size, and the seller receives the fee less it.
- **Offers expire** after a week rather than sitting open forever.

#### The legacy path is made safe, not just documented

`TransferService.resolveOffer` still reads the old prose strings, so it is still resolving a buyer by
name. It now uses a list-based lookup that **cannot throw** on a duplicate, picks deterministically by
id, and **logs the ambiguity** rather than pretending the lookup was clean. Migrating the legacy
strings onto the new entity is tracked in `sprintBacklog.md` rather than being half-done and claimed
as complete.

#### A calibration the tests caught

`SquadRole.wageExpectationFactor()` for a star was 0.55 of value **per year**, which made a €12m
player demand **€105,000 a week**. The real relationship is roughly 30% of the fee per year — a club
signing for €50m on four years pays about €15m a year in wages, so the total outlay is the fee plus
roughly 120% of it. The factor is now 0.32, and the chain of other roles came down with it.

The number was not merely high: at 0.55 such a player was **unaffordable for every club in the
game**, so S2.5's wage ceiling was never actually being tested — it was just always refusing. The
test that caught it asserted a €60k offer would be accepted for a player demanding more, which the
model correctly refused; the fix was to recalibrate the model *and* make the test offer what the
player actually wanted.

**10 tests. 277 total.**

### S3.3 — Transfer windows ✅ DONE

**Date:** 2026-09-26 · **Commit:** this section

There was **no window enforcement anywhere**. A club could sign a striker in week 15 of the season,
mid-run-in, with no rule and no reason given — which removed the largest single piece of scheduling
tension in a football management game. Registration days and the January window are when a season
is actually decided.

- **Summer** weeks 1–6, **winter** weeks 10–12, closed otherwise. Tested exhaustively across weeks
  1–45 rather than sampled, because these are pure rules and there is no reason to be vague about
  them.
- **Two exemptions, and they are the interesting part:**
  - **Signing a free agent** works at any time. His contract has already expired, nobody is being
    deprived of anything, and refusing it would be absurd — a player cannot be forbidden from joining
    a club because it is March.
  - **A loan recall** is never blocked. The recall is the *parent club's* right, exercised against the
    player's will.
- **A loan IN is not exempt.** A club taking a player on loan in April is doing something a manager
  should have to think about, and no league lets it. There is a test asserting exactly that.
- Out-of-window attempts are **refused with a reason**, via a distinct `TransferWindowClosedException`
  rather than a boolean — "the window is shut" is not the same failure as "that player does not
  exist" and the caller has to say which.
- `status()` gives the transfer centre a **countdown** and, importantly, states explicitly what is
  *still* allowed while shut. "The window is shut" without that is exactly the kind of thing a
  manager works around wrongly.

### S3.2 (cont.) — Instalments and sell-on clauses ✅ DONE

**Date:** 2026-09-26 · **Commit:** this section

Two things the transfer market actually does, and the game had neither.

**Instalments.** Nobody pays €40m on the day. A small fee is paid outright; above €2m it is spread
over the contract, and the *bigger* the fee the *less* changes hands on the day. Until it is paid the
selling club still carries the risk — a club that sold a player on instalments is exposed if he is
injured in year two, which is a different risk from having taken the money and moved on. There is now
a real outstanding balance that feeds the finance screen.

**Sell-on clauses.** The club that sold a player keeps a share of the next fee, **capped at 50%** —
above that a club can never sell anyone and the market dies. This is how a club funds itself after a
sale, and it is why selling a teenager can be worth more in total than keeping him. A zero clause and
no clause are different, and both are represented.

#### A hazard the test found

`agree()` **overwrote** an existing fee structure, which silently wiped any outstanding balance — a
club owed a year of instalments would find the debt gone because something re-saved the deal. Now the
first agreed terms stand and only a fully settled deal may be re-agreed.

**15 tests. 292 total.**

### S3.3b — Transfer window weeks, per the owner's season shape ✅ DONE

**Date:** 2026-09-26 · **Commit:** this section

The window weeks were guessed. They are now the owner's definition (2026-09-26):

| weeks | what |
|---|---|
| 1–4 | league, no transfers |
| **5–6** | **first window** |
| 7–8 | league, no transfers |
| **9–11** | **second window** — league finishes, playoffs, mid-season break |
| 12+ | closed |

`SEASON_WEEKS`, `LEAGUE_END`, `PLAYOFF_WEEK` and `MID_SEASON_WEEK` are named constants rather than
bare numbers, because the season shape is a rule the rest of the code has to agree with.

**The second window opens as the league finishes, not after it.** A club can do its business while
the table is still settling, and being able to sign **during the playoffs** is how a season is
actually won.

#### The three kinds of off-window availability, named separately

The owner distinguished three, and they were collapsed into one:

- **Free agent** — contract expired. Nobody is being disappointed, so it is always available.
- **Released** — his club gave him away. Refusing the move would be refusing to let a released man
  find a club.
- **No asking price** — the club has not listed him or set a price, so he is **not for sale and
  cannot be bid on**, but a club may still approach him directly. Flagged by the owner as one to tune
  later; what happens when that approach is refused is the open part.

Each has its own explanation, because "the window is shut" alone is the kind of thing a manager works
around wrongly. A **loan IN** is still not exempt.

13 window tests, 294 total.

#### Enforcing the window, and the cycle that enforcing it exposed

Adding the window check to `NegotiationService.openOffer` immediately broke ten negotiation tests —
**correctly**. A club cannot negotiate in April, whoever it is. The tests were exercising a path that
cannot happen in the game, so they now set the calendar into a window first.

The same reasoning applied to the **AI market**: `simulateWeeklyMarketActivity` runs from the season
advance every week, so with the check in place it would have attempted transfers for six weeks out of
eleven. It is now guarded and simply does not run while the window is shut — a club does not make
offers in April, and neither does the AI.

Wiring the window service in surfaced a **circular dependency** the codebase had been quietly
avoiding: `TransferService → TransferWindowService → SeasonService → … → TransferService`. Spring
refuses to start the context. `TransferWindowService` only ever wanted the current week, so it now
reads `GameClockRepository` directly and the cycle is gone. Two existing tests that construct
`TransferService` by hand needed the new argument.

**297 tests.**

#### ⚠️ Open question for the PO — the season is not 11 weeks yet

The window weeks are implemented and tested against the owner's season shape, but **the rest of the
codebase still generates a 19–20 week season**, so the windows only describe part of it.

`SeasonService.generateFixtures` builds a standard double round-robin: `rounds = n − 1` per half, with
every match in a round played in the **same week**. For 10 teams that is 9 rounds + 9 reverse rounds
= **18 weeks, 5 matches a week**.

The owner's shape is **18 matches over 9 weeks at 2 matches a week** — which only works if a
5-match round is spread over ~2.5 weeks rather than played in one. That is a different fixture
scheduler, and it changes what "week 9" and "week 10" mean for the table, the playoffs and promotion.

Not changed unilaterally: it is a large, cross-cutting change and there is more than one way to
schedule it. **Three questions:**

1. **2 matches per week** — is that 2 league matches *in total* across the league each week (so a
   5-match round takes 2.5 weeks), or 2 per *team*? 10 teams × 2 per team would be 10 matches a week
   and 18 matches would take under 2 weeks, which contradicts the 9.
2. **Mid-season week 11** — is that a played round, or a genuine break where nothing is simulated?
3. **Playoffs in week 10** — how many teams, and do they play more than one match that week?

Until that is settled, `SEASON_WEEKS = 11` is a stated intention that the fixture generator does not
yet implement, and the two disagree.

### The season is twelve weeks, and it has no months in it

The fixture generator and the calendar disagreed, and the calendar was right. `generateFixtures` gave
every round its own week, so eighteen rounds took eighteen weeks — which put the transfer windows in
weeks that could never happen and stretched a season out to nearly six months. The whole thing was
wrong because the season shape was never written down anywhere; it had been guessed in three
different places and each guess was different.

So it is now written down once, in `SeasonCalendar`, and the fixture generator, the transfer windows
and the playoff/friendly generators all read it. `SeasonService`, which held the old `PLAYOFF_WEEK =
19` and `FRIENDLY_WEEK = 20`, now re-exports the calendar's values rather than keeping its own
opinion — which moved the existing playoff and friendly machinery onto week 11 and week 12 without
rewriting it. The test for that is in `SeasonShapeTest`, and the first assertion it makes is that the
old behaviour is gone, because a calendar nothing reads is a comment.

**No months anywhere.** A manager's season lasts twelve real weeks, so about four run in a year. That
is the whole reason the game does not use a January-to-May calendar: showing "March" would imply a
season lasts five months of the player's life, which it does not. The clock keeps a `LocalDateTime`
because the column is not null and something orders fixtures by it, but it is only ever used to
compare two fixtures, never shown to a manager.

The friendlies in the schedule are new. A friendly "round" is one match per club — ten clubs make
five — the same shape as a league round, so a club still fits inside its two weekly slots. Week 11 is
the awkward one: the playoff clubs are busy, so whoever did not qualify plays a friendly instead.
Rather than make the caller work out who is busy, `ensureFriendlyFixturesForCurrentWeek` reads it off
the playoff fixtures already saved. Both existing call sites were pointed at it and neither now needs
to know the rules.

**316 tests.**

#### ⚠️ The promotion and relegation ladder is backlogged, not built

The owner asked for it to be designed properly rather than guessed at, so it is written out in
`sprintBacklog.md` and left alone. What it says, briefly: 1, 2, 4, 8 and 16 leagues across five
tiers; first goes up, second into a playoff, seventh and eighth play out, ninth and tenth go down,
and tier 5 has no drop at all. The playoff is paired by strength — the seventh plays the weaker
second, the eighth plays the stronger.

One real bug fell out of writing it down: `ensurePlayoffWeekFixtures` pairs the seventh with the
second of tier-2 league A and the eighth with league B's second **by league order, not by
strength**, so on a normal table that is the wrong draw. Left in the backlog rather than half-fixed.

The European places are genuinely undecided ("videcemo ko sve ide u evropska takmicenja") and cup and
European competitions are explicitly later, so nothing was invented for either.

### Friendlies are requested, not scheduled

The owner corrected two things here, and both were mine to get wrong.

**A friendly is an ask, not an appointment.** The previous version quietly generated a full round of
friendlies in weeks 5, 6, 11 and 12 — a manager was handed matches they had never agreed to, and a
club had no way to trade one for training time. That is now a request with a state machine: a club
asks, the other club accepts or refuses, and a refusal carries a reason so it is not silent. The
manager's own club is deliberately left out of the AI's weekly negotiation, so whether to take a
friendly stays their call.

**Every week has two named slots**, Thursday and Sunday, and each is filled by a league round, a
playoff, or an open friendly slot. This is the owner's model and it explains something the previous
version could not: weeks 5 and 6 run in *opposite* directions. Week 5 is round 9 on Thursday and the
friendly on Sunday; week 6 is the friendly on Thursday and round 10 on Sunday. So a round number no
longer says which day a match is on, and `slotOfRound` exists for that.

**Week 11 works out as the owner described.** The playoff takes Thursday, so a club in it keeps only
the Sunday slot, while everyone else may play in both. `FRIENDLY_IF_NOT_IN_PLAYOFF` encodes exactly
that, and the service never asks a playoff club to give up its one remaining slot.

**The cost of playing is derived, not stored.** One friendly costs one training session
(`BASE_TRAINING_SESSIONS_PER_WEEK` 3, `TRAINING_SESSIONS_PER_FRIENDLY` 1). It is computed from the
agreed friendlies rather than kept in a counter, because a counter can drift away from the fixtures
and Sprint 4 owns real training — this gives it something to read instead of a number to invent.

#### 🐛 A table that silently shifted every season

Worth recording because it was invisible: the season table is a nested array literal, and written as
`{a, b}, {c, d}` on one line Java reads that as **two rows**, not one row of two. The table came out
with 22 rows instead of 12, so week 10 read a one-column row and threw on the tenth week of every
season. The unit tests had passed earlier only because that version of the table was built with a
loop instead. It is now an explicit `SlotSpec[][]` with one row per week, a length check, and a test
that walks all twelve weeks.

#### The playoff draw is fixed

`ensurePlayoffWeekFixtures` paired the seventh with the second of tier-2 league A and the eighth with
league B's second **by league order, not by strength**. On a normal table — where league B's second
is the better side — the seventh got the harder tie and the eighth the easier one. The two
second-placed clubs are now ranked on points then goal difference, and the seventh meets the weaker
while the eighth meets the stronger. The seventh hosts, the eighth is away, as the owner specified.

**328 tests.**

### A signing that accomplished nothing

`PlayerContractService.sign` validated its arguments, wrote a contract row and returned a signed
outcome — and changed nothing. Three things were missing, all of them load-bearing:

- the contract was written with **no club on it** (`Team club = teamId == null ? null : null`, and
  the `teamId` argument was then never used),
- the player was **never moved** to the new club,
- the wage was **never applied**, so he stayed on his old money.

So a free agent who "signed" for a new club remained at his old one, which made the free-agent route
— the whole reason expiry and contracts were built in Sprint 3.1 — a no-op that looked like progress.
The squad limit was also not consulted, so a full club would quietly over-register.

Expiry had the mirror bug: it cleared the contract's club but left the player pointing at the club he
no longer played for, so an expired player still counted in that club's squad.

All four are fixed, and there are four regression tests. The old tests passed throughout because
they only ever read the contract record and never asked where the player ended up — which is exactly
what a test has to do here.

One thing worth flagging: the fix needed the current season, and taking it from `SeasonService`
closed the same cycle as before, since `SeasonService` depends on this service. It reads
`GameClockRepository` directly, and deliberately uses the **same** season value `expireContracts` is
called with, so a contract signed and a contract expired are on the same scale.

**332 tests.**

### An accepted offer has to actually transfer somebody

The negotiation service was a very careful conversation about a thing that never happened. Offers
opened, countered, were objected to by the player, and were accepted; the winning offer's status
flipped — and then the player stayed with the selling club, the buyer was never charged, no contract
existed and no ledger line was written. A market where every deal is agreed and no deal ever
completes is worse than no market, because it looks finished.

`completeTransfer` now settles it, in a deliberate order:

1. **Charge the buyer first.** A club that cannot pay must never end up holding the asset, so the
   affordability check happens before anything moves. A refusal is now logged with the reason rather
   than swallowed — a deal that quietly fails is indistinguishable from one never agreed.
2. **Record the instalment schedule** if the fee is too big to pay at once.
3. **Move the player**, on the agreed wage, and write the contract for the agreed term.
4. **Close the transfer** and set the completed timestamp.

#### 🐛 The ledger had two different ideas of what a season is

Chasing this uncovered something much larger than the missing completion. `FinanceLedgerService`
decided which season to read with `Year.now().getValue()` — the **wall-clock year** — while the weekly
settlement wrote the **game season**. The two never matched. On top of that, gate receipts,
merchandising, wages, pitch maintenance and facility upkeep were written with a **null** season
because those helpers were never passed one.

So the consequence was: every club's income read back as zero → `budgetFor` answered "no settled
income yet" → `canAfford` refused everything → **no transfer could ever complete, for any club, at
any budget.** The transfer budget built in Sprint 2 had never once worked; it was reading an empty
ledger. The board's FFP assessment had the same problem, and the finances page had nothing to show.

Both sides are fixed. Every settlement line now carries its season and week, and the reader resolves
the season from the game clock. Three existing tests encoded the old convention and were reading
through the bug, so they now settle the season the clock is actually in — which is the invariant the
fix restored.

One test premise needed strengthening rather than repairing: a club paying 250,000 a week was
assumed to be bankrupted, but once gate receipts counted as income a 30,000-seat ground earns enough
that it no longer is. The wage is now genuinely absurd rather than comfortably unaffordable.

**335 tests.**

### Loans

A loan is the answer to a youth cap: a club with fifteen teenagers cannot register them all, so it
sends some out. That only means anything if the move is genuinely temporary — the player keeps his
contract, the wage stays with the club that owns him, and he comes back. Modelled as a transfer it
would have been simpler and permanently wrong.

Two rules the owner was explicit about, and both are enforced:

- **A loan in is not exempt from the window.** It goes through the same `TransferWindowService` gate
  as a permanent move, so a club cannot sign in April by calling it a loan. The gate is on
  `start`, not on `offer`: offering costs nothing and a club may line a player up months ahead,
  while *signing* him is what needs the window.
- **A loanee is squad depth, not a registration.** `isRegisteredByNobody` is the whole point — a
  player on loan is registered by nobody, which is the only reason a cap can be worked around
  honestly rather than by breaking it.

Also modelled: a wage contribution so the borrowing club carries only its share (usually zero for a
youngster sent out for minutes), a buy clause that only exists if both a clause was agreed *and* the
loan is running, mid-loan recall by the parent club (not window-gated — pulling back your own player
is not a transfer), early return by the borrower, and automatic closure when the weeks run out,
wired into the weekly tick so squads do not accumulate players who left weeks ago.

**342 tests.**

### Work permits and the non-EU quota

The constraint that shapes a Serbian transfer window: four non-EU players in the top flight and fewer
below, each needing a permit. So the interesting question is not "can I afford him" but "can I
register him" — and a club at its quota has to **sell before it can buy**, which is an awkward thing
to plan a season around and exactly the tension the rule exists to create.

`Player` gained a `nationality` and `Competition` a `foreignPlayerLimit`, so the rule lives on the
competition rather than in a hardcoded tier table — correcting a league's quota is a data change.
Signing now consults it, and a refusal always says which of the three limits stopped it: the quota is
full, the club's standing is too low, or the player has no pedigree to justify the paperwork. The
full-quota message tells the manager that selling is the only way, because no amount of money changes
it.

The strategic axis the backlog asked for — buying from a weaker league grants a permit more easily —
falls out of the arithmetic rather than being written in: below the top two tiers a club under 70
reputation needs a player worth €8m to justify the paperwork, so a club that cannot outspend its
quota rivals can still outscout them.

#### 🐛 Serbia is not in the EU

The first version asked "is this player a non-EU national?" and counted **every Serbian player at a
Serbian club as a foreigner** — which would have filled the quota with homegrown talent and quietly
broken every transfer in the game. The test caught it immediately.

Foreign has to mean two things: from *another country*, **and** from outside the EU. A Spaniard at a
Serbian club is foreign but free; a Brazilian is foreign and counts; a Serbian at home is simply
domestic. A player with no nationality on record, and a competition with no country, are both treated
as domestic — the safe direction, since the alternative would block every signing in a database that
predates the column.

#### One more constructor trap

Adding `nationality` to `Player` recompiled Lombok's all-args constructor and broke two tests that
construct a player with all 24 arguments spelled out positionally. The field is now declared **last**,
with a comment saying why, so the signature survives the next field somebody adds.

**350 tests.**

### AI clubs bid, and they bid for a reason

`maybeCreateIncomingOffer` opened with `if (humanManagedTeamIds.isEmpty()) return;` and then picked
a buyer with `randomItem(candidateBuyers)`. So **AI clubs never traded with each other at all** — the
market belonged to the player's club alone — and the one bid that could happen ignored both whether
the buyer needed the player and whether it could pay for him.

Now every listed player goes to auction, every club is a potential buyer, and a club bids in
proportion to how badly it wants *that* player. A club with no gap at that position does not bid at
all, which is the difference between a market and noise.

`ClubNeedService` is the needs model. A club looks at its own squad, works out where the gaps are, and
rates the player against the gap he would fill — thin positions, an upgrade on what is already there,
and an ageing squad at that position all move the number. What it will *pay* is a view rather than
market value: age (a curve peaking in the mid-twenties), contract length, form, and how far the club's
standing lets it stretch for someone it really wants.

#### Two flaws the tests found in my own new code

- **`gaps` reported positions this game does not have.** The list was hand-written as Football
  Manager's eleven (CB, LB, RB, AM, LW, RW, ST) while `Position` here is five (GK, DEF, MID, ATT,
  WNG). Every report said a club had no goalkeeper, no striker and no full back — none of which exist
  — while never reporting a real gap. It is now built from the enum.
- **`bestTarget` let the price tag override the position.** It scored `interest × value`, so a club
  that badly needed a centre back would chase a €20m midfielder it already had three of, because
  0.6 × 20m beats 0.8 × 2m. Appetite now decides and value only breaks a near-tie, which is how a
  manager actually thinks — and it is how a club ends up with eleven of the same player otherwise.

#### Unlisted players are scoutable

`getAllTransfers` filtered on `status == LISTED`, so a rival's unlisted squad was invisible. That is
not realism, it is a missing feature: the owner allows approaching a player with no asking price, but
there was no way to see one. The transfer centre now reports listed players and scout reports
together, and an unlisted player shows with no asking price rather than pretending to be for sale.

**359 tests.**

#### ⚠️ Deliberately not done: the legacy `Transfer` migration

`Transfer.interestedTeams` (a `Set<String>` of club *names*) and the legacy
`TransferService.completeTransfer` still exist alongside the new `TransferOffer` engine. There are
now two completion paths on the same entity, which is duplication and a real double-completion risk.
It is a genuine refactor rather than a patch, so it is left in the backlog rather than half-done —
but it should be the next thing anyone picks up in Sprint 3.

### Position and role are two different questions

The owner's correction, and it is a real modelling bug rather than a naming preference.

**Position** is the broad unit — the five: GK, DEF, MID, ATT, WNG — and answers *which part of the
pitch*. **Role** is the specific job — fifteen of them, GK, CB, LB, LWB, RB, RWB, DM, CM, DM, AM,
W, IF, LW, RW, ST — and answers *what is he actually for*.

They were being mixed, and the symptom was the squad report naming positions this game does not have
while never reporting a real gap. A club that counts its defenders as one group can have three centre
backs and no left back and believe it is fine. "No left back" is actionable; "no defenders" is not.

So:

- `PlayerRole` is a real enum, every role mapping to exactly one position, with a test that asserts
  no position is left without a role — otherwise nobody could ever play there.
- `Player` stores a role (as a name, so the column survives an enum insertion) and
  `effectiveRole()` derives one from the position when it is unset, so nothing anywhere reads null.
- **Setting a role sets the position too.** Leaving the two to drift is exactly how a striker ends
  up training as a full back.
- `ClubNeedService` counts gaps in **roles**, and a club with no cover at a role does not bid at all.
  `gapsByPosition` still exists for squad-balance questions — the two are no longer interchangeable
  by accident.

Every role has a readable label, because "SHOOTER" is not a job and the squad screen has to say
"Striker".

**369 tests.**

### No foreigner limit — removed at the owner's direction

S3.5 built the non-EU quota and work permits on the strength of "Serbia's rule is four in the top
flight, fewer below". The owner has since decided there is **no foreigner limit for now**, so the
whole thing is gone: `WorkPermit`, `WorkPermitService`, `WorkPermitRepository`, the tests, the
`Competition.foreignPlayerLimit` column and the gate in `PlayerContractService.sign`.

`Player.nationality` **stays**, deliberately. The owner may later want a minimum number of players
from the club's own country, and a nationality column is what that needs; keeping it now costs
nothing and saves a migration later. Nothing reads it today except the club-need model, which does
not use it.

**361 tests.**

### S3.7 — the prose offer layer is gone

`Transfer.interestedTeams` was a `Set<String>` of **sentences**. A rival's bid was stored as
`"Rival FC offered €900000"`, and the seller accepting that offer meant parsing the sentence back to
work out who the buyer was — a string comparison, on a club name, to identify a club.

That was never going to hold together:

- a club's identity cannot be recovered from a label;
- **two clubs may share a name in this game** — explicitly — so the lookup was ambiguous by design;
- it had grown a parser, a resolver, a purge and two records to support it;
- and it sat alongside `NegotiationService`, which had a second copy of the settlement, reachable
  from the purchase endpoints. **Two settlement paths on one entity**, with the budget guard written
  twice and the "already completed" check in only one of them.

The owner said to remove it, so it is removed. Interest is a `TransferOffer` row with a foreign key,
and the whole prose layer went with it: `replaceInterestFromClub`, `extractBestOffer`,
`resolveBestAcceptableOffer`, `resolveOffer`, `parseOfferDetails`, `isOfferEntry`, `purgeInvalidOffers`,
`sortedInterests`, `resolveClubByName`, and the two private records.

What replaced it:

- **One settlement.** `NegotiationService.settle(transferId, buyer, fee, wage, years)` is the only
  place a player changes clubs and money moves. The legacy purchase path now validates what it owns
  (the asking price is a floor, the club has the cash) and then delegates. The double-completion risk
  is gone because there is only one completion.
- **Interest is an offer.** `addInterest` opens a real offer; the AI bids with a real one; rejecting
  or clearing rejects the offer records rather than emptying a set, so the thread still shows what was
  on the table.
- **Withdrawal is by club id, not name.** `withdrawInterest` takes the id, which is the only way to
  tell two same-named clubs apart. The controller's `club` parameter is gone.
- **The AI offer carries three numbers.** A deal is a fee, a wage and a length, and the seller decides
  on the player's terms too, not just on the transfer fee.

`TransferListSoftLockTest` (224 lines) existed to test the prose behaviour — that a bare interest
entry must not soft-lock a seller on the list. That failure mode is now structurally impossible,
because a bare interest entry cannot exist. It is deleted rather than rewritten.

The price-guard tests were asserting the settlement inline; they now assert that the guard hands the
right price to the settlement, and `TransferCompletionTest` covers the settlement against a real
database. A price guard is only worth testing if what stands behind it is a collaborator rather than
a second copy of the same logic.

**351 tests.**

### Finishing the signing, and proving it end to end

The owner asked me to check what signing still needs to do. Two things it did not do, one of them
serious.

**It never checked the wage.** The method has always claimed it refuses a signing the club cannot
afford, and it never did — the only affordability call it made compared a *fee estimate* against a
*release clause*, which says nothing about a weekly wage. A board that cannot meet the wage bill was
told yes. That is now checked, and the answer names the ceiling.

**Contracts were four times too short.** Expiry was `season + lengthMonths / 12`, which treats a
season as a year. A season is **twelve weeks** — the owner defined it that way — so it is about three
months. A 24-month deal was expiring after two seasons, twenty-four weeks, roughly five and a half
months after signing. Nobody would be bound to anybody, and the free-agent market that depends on
expiry would churn the whole league every half year. It is now `seasonsFor(lengthMonths)` against
`MONTHS_PER_SEASON = 3`, so a 24-month deal runs eight seasons and a 6-month deal still outlasts the
season it was signed in.

**And it could poach.** `sign` overwrote any player's contract regardless of who owned him, so any
registered player could be signed out from under his club for nothing — not a transfer route, a
purchase button. It now refuses a contracted player who has no release clause and says he has to be
negotiated for. A signing is for free agents and renewals; everything else goes through the offer
thread.

#### 🧪 The owner's club, from a real start, through a real transfer

`OmladinacTransferJourneyTest` is the test I should have written months ago. **No mocks anywhere.**
Real clubs, real economies, a real ledger, a real window, a real offer thread, and assertions only on
the three things a manager would notice: the player is at the new club, the money moved, the paper
trail exists.

Six journeys, all named for the club the owner actually manages (`OFK Omladinac`):

1. buys a player, and he arrives with the paperwork
2. signs a released player directly — the free-agent route, which was a no-op until this morning
3. a big fee becomes instalments, and the first one actually pays the seller
4. nothing happens outside the window
5. Omladinac cannot sign a player away from the club that owns him
6. Omladinac has a transfer budget once it has income, and can spend it

Every one of the four broken systems from the last report would have been caught by journey 1. Each
of them had passing unit tests, and **that was the actual bug** — the unit tests asserted on a single
collaborator, and none of them asserted the thing a manager would notice. Journey 2 builds its expiring
contract through `sign` rather than poking the database, which is how the flush problem it hit taught
me the expiry is only real when the service wrote it.

**360 tests.**

### A refusal the game decided on was arriving as a server fault

Three bugs, one cause, all found from one screenshot of the Training page.

**1. A missing training report answered 500.** `getTeamReport` threw a bare `RuntimeException`, so
"you have not trained this week yet" — an entirely normal state — came back as a server error.

**2. `authFetch` throws on any non-2xx, so the frontend's own guards were dead code.** Every
`if (!res.ok) return` in `training-view.js` was unreachable; the throw escaped to the page router,
which replaced the **whole Training page** with "API Error" because one week had no report. That is
the screenshot. The view now catches a 404 and renders an empty week, and rethrows anything else.

**3. 🐛 There was no `@ExceptionHandler` for `ApiException` at all.** This is the real one. The
entire `newLogic` package signals ordinary, expected outcomes with that exception — the transfer
window is shut, the club cannot afford the fee, the squad is full, the price is below the asking
price — and **every single one fell through to the catch-all and reached the browser as a 500.** So
the frontend could not tell a decision the game made from a fault, and reacted by logging the
manager out or blanking a page. There is now a handler that reports the status and code the game
chose, logged at `warn` because a refusal is not an error.

The difference is stark and is why it was worth the detour:

```
before:  GET /training/weekly/team/1/reports/1/9  →  500
after:   GET /training/weekly/team/1/reports/1/9  →  404 REPORT_NOT_FOUND
```

**365 tests.** Verified negatively too: removing the handler puts all three back to 500.

### 🐛 The browser launcher was never a bean at all

The owner was right and I was wrong to call this fixed. Two separate faults, stacked, and the second
one was hiding the first.

**1. The bean did not exist.** `SportsManagerApplication` declares an explicit `scanBasePackages`
list, and **`org.example` — the application's own package — is not on it.** `BrowserLauncher` was a
`@Component` in that root package, so Spring never scanned it. No value of `app.open-browser` could
change that, because there was no bean. It looked entirely correct in the source and was invisible at
runtime. It has moved to `org.example.config`, which is scanned.

**2. My headless guard then blocked it anyway.** I checked `java.awt.headless` — which means "do not
initialise AWT", and which **Spring Boot sets to true by default for server applications.** So the
launcher always concluded there was no desktop, and the log cheerfully reported "no desktop
available" on a Mac with a browser sitting right there. It now checks the environment instead
(`DISPLAY`/`WAYLAND_DISPLAY` on Linux only), because that is the only thing that actually indicates a
display.

Verified by running `main` exactly the way the owner does:

```
INFO  o.e.config.BrowserLauncher : Opened http://localhost:9081/login.html in your browser.
```

`ComponentScanCoverageTest` now fails the build if an infrastructure bean lands outside the scanned
packages, because the failure mode is silence — no compile error, no warning, just a feature that
quietly does nothing. Verified negatively: moving the class back fails it with a message naming the
package and the scanned list.

## Where Sprint 1 stands

Statistics are **no longer benchmarked against Premier League figures** — owner decision 2026-09-26.
The table below is a record of where the engine sits, not a scorecard.

| Metric | Current (100 matches) | Real PL | Status |
|---|---:|---:|---|
| goals | 3.55 | 2.7 | ⚠️ deferred by owner |
| shots | 35.7 | 25 | ⚠️ deferred by owner |
| on-target % | 28% | 33% | ✅ |
| pass accuracy | 85% | 80–86% | ✅ |
| penalties | 0.24 | ~0.27 | ✅ |
| penalty conversion | 76.6% (model) | ~76% | ✅ |
| injuries | modelled, 8–88 min | 0.3–0.6/team | ✅ |
| substitutions | 5 / 3 windows | 5 / 3 | ✅ |
| fouls | 12.0 | 22 | ⚠️ low |
| duels | 268 | ~100 | ⚠️ deferred |
| goal kicks | 21.9 | 12–15 | ⚠️ improved, still high |
| corners | 6.4 | ~10 | ⚠️ low |
| throw-ins | 77.0 | 35–45 | ℹ️ not being chased |

**Tests: 84 → 176.** `mvn test` wall clock cut from ~5–6 min to **1:10** by moving `BaseTest` from
`RANDOM_PORT` to `MOCK` (no test used a real port; it was opening a listening socket for nothing).

### Still open in Sprint 1

| Task | What |
|---|---|
| **S1.7c** | Box fouls under-produced (1.655/match vs 2.5–3.5). S1.7b's constant compensates for it. Calibration — deferred |
| **S1.9** | Remaining calibration outliers. Calibration — deferred |
| **S1.1c / S1.3** | Shot volume and corner skew. Calibration — deferred |
| **stoppage time** | Real stoppage clock for VAR, penalties, injuries and substitution windows — currently `getRestartTaker()` is used as a heuristic in place of it |
| **conditional subs — UI** | Rules builder + live fired/spent/void view. Engine and API are done |
| **tactical editor** | Redesign placeholder, scheduled last |

---

## Open design questions---

## Open design questions — transfer market

**Date:** 2026-09-26 · raised by PO during S0.1

### Confirmed: this is a multiplayer human economy, not an AI sandbox

PO clarified the product: **AI teams do not buy and do not sell.** Only real players trade. An admin can force-list a specific player (e.g. an NT player so they do not sit in a bot club). `Team.humanControlled` already exists and the league table already surfaces it (`CountryController.java:89,130`); `RegistrationService:77` sets it on approval. The multiplayer claim flow is already built.

**This corrects two more findings in `expertAudit.md`:**

| Audit claim | Reality |
|---|---|
| §8.3 "`maybeCreateIncomingOffer:465-469` only ever targets human players — **AI↔AI transfers never happen at all**" — flagged as a bug | **Intentional design.** Not a defect. |
| §8.3 `maybeCreateAiListing:430-458` — 42%/week chance an AI club lists a player | **This one is a real bug.** AI clubs must not create listings. Needs a `humanControlled` filter. |
| §8.6 / Sprint 3.6 "Enable AI↔AI transfers" | **Wrong requirement.** Must be removed. |

Also invalid: Sprint 2.1's "seed a real world for all 310 clubs" and Sprint 2.2's "run finances for every club" were written assuming AI economies participate. The economy only needs to be correct for human clubs; AI clubs need only be plausible *on the pitch*.

**Consequence:** the transfer market is only as liquid as the human player count. With one player it is effectively a monologue. That is fine during testing, but the auction UI, notifications and rival-bidding must be built for ~10+ players across multiple countries from the start.

### Blocker found: the season is a global singleton advanced by any player's click

`GameClock` is a singleton row (`@Id Long id = 1L`). `POST /simulation/week/advance` (`SimulationController.java:218`) advances **the entire world** and performs **no ownership check** — any authenticated user moves the season for everyone. The only gate is "are the *clicker's* league fixtures played" (`:244-253`), and the week-consumed guard is client-side only (`demo.js:634-637`).

Consequences with real players: player A advances the week, and player B loses their un-played fixture and their week jumps forward. Training also only runs for the clicking user's team (`AdvanceWeekAsyncService:80-82`).

**This must be resolved before the auction**, because an auction needs a trustworthy deadline. A bid window cannot be defined in a world where the calendar moves on a click by an arbitrary player.

### Clock assessment (PO asked whether the existing clock is reusable)

The existing clock is `static/js/clock.js`, polling `/api/server-time` and `/api/game-clock`. It displays **real** wall-clock time and date (Europe/Belgrade) plus `Season N • Week N`.

- ✅ Reusable as-is for the UI shell — it is already the manager's time source.
- ❌ `/api/game-clock` (`APIController.java:39-47`) returns only `seasonNumber`, `weekNumber`, and a **hardcoded** `phase: "Season in progress"`. `GameClock.currentDate` (the in-game date) is **never sent to the frontend**.
- ❌ `GameClock.currentDate` only moves in 7-day steps on week advance, so it cannot express an auction window.
- ⚠️ `CommonGameClock.currentYear` exists and is unused.

**Proposal:** keep the existing clock and extend `/api/game-clock` with the in-game date, a day counter and a real `phase`, rather than building a second clock. Granularity decision pending.

### Decision taken on reserve behaviour

PO chose: **auction closes unsold below reserve → player stays listed and can be re-listed.** Reserve is a real negotiating position, not a formality.

### Plan of record

1. ✅ S0.1 — exploit closed, minimal guard only (done, keeps the hole shut while the model is replaced)
2. Resolve the season-advance model (global singleton vs scheduled) — **blocks the auction**
3. Extend `/api/game-clock` with game date + phase
4. Sprint 3 restructure: auction becomes the core, absorbing S3.2's data-model replacement
   - `TransferListing` + `TransferBid` entities → permanently kills the `Set<String>` prose encoding
   - Auction: proxy/max bids, anti-snipe extension (capped), resolution
   - Direct club-to-club agreement with min/max guard → **later task in the same sprint**, per PO
5. Add admin force-list capability
6. Fix `maybeCreateAiListing` to skip non-human clubs

