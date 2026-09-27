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

### Training: talent, coach and minutes

The owner's rule, implemented as one number: **a player gets 100% of a week's training only when
talent, the coach's rating for the skill being trained, and minutes played are all maximal** —
talent maxed, coach maxed *at that skill*, and 120+ minutes in the week. Everything short of
maximal reduces it proportionally, and the instruction on the shape of that reduction was *not
dramatic, a lazy curve*.

**The implementation choice that matters is a weighted mean, not a product.** Multiplying the three
factors is the obvious way to write "all three must be maximal", and it is badly wrong for the brief:
three factors at 80% multiply to **51%**, which is exactly the dramatic collapse that was ruled out.
Sharing a budget of 100% means three at 80% give **80%** — lazy — and 100% still requires all three
to be maximal. The test asserts exactly that, because it is the one property that separates the two
implementations.

```
pct = 0.40·talent^0.75 + 0.35·coach^0.50 + 0.25·(0.30 + 0.70·(min(1, m/120))^0.70)
```

The agreed grid is pinned as assertions — talent 20/15/10 × coach 20/15/10 × minutes 60/90/120 — so a
later tune cannot quietly move numbers the owner signed off. The floor (talent 1, a 1/20 coach, no
minutes → **22.4%**) is pinned too, because "a forgotten squad still moves" is a design decision and
not an accident.

**The coach curve is deliberately the flattest.** A coach rated 10/20 still delivers 71% of his
curve, so a mediocre coach holds a squad together instead of freezing it. The owner's rule is that
nothing below maximum *reduces* training; it does not remove it.

#### What the percentage is not

Not affected by how good the player already is, or how old. Those change the **fragment** gained — the
actual skill points. A 34-year-old at 20/20 and a 19-year-old at 2/20 can both be at 100% of the
training, and the difference shows up as what those points are worth. Keeping the two apart is what
lets the number mean one thing.

#### 🐛 A national-team call-up is just minutes

The minutes query goes through `Match.competition` and deliberately **does not filter on it**, so
league, cup, European, national team and friendly all count toward the 120 for free — which is
exactly what the owner specified. There is no national-team staff, so `coachFor` returns the club head
coach (falling back to the assistant, never null, because null would read as a rating of 1). That
answers the "whose coach for the NT" question for good rather than as a special case.

**384 tests.**

#### Still open, deliberately

The **talent and percentage visibility** is not wired. The owner said to agree exactly what plus users
see once everything is in place, and that talent and the percentage will be plus options for their
own team only. Implementing a guess now would be building the wrong rule twice.

### The transfer window, and the ticker that says so

Windows confirmed by the owner at **weeks 5–6 and 11–12**, which is what was already built — so no
change was needed, and the calendar is now pinned by test to that reading.

**The manager had no way of knowing when the window was open.** It had to be inferred by trying a
deal. `TransferWindowService` already computed the whole thing — which window, how many weeks left,
whether it is deadline day — for the transfer centre, and nothing else could read it. There is now a
`GET /transfers/window`, verified against the live calendar:

```
week 1  ->  CLOSED, reopens week 5
week 6  ->  SUMMER, open, weeksLeft 0, deadlineDay true
```

**Three ticker states, because they are three urgencies.** "Open — 2 weeks left" is an opportunity
and must not shout. Deadline day is `alert`. "Closed" is worth saying once, with what stays possible
named in the same breath, or a manager will assume everything is frozen and stop pursuing free agents
and released players for a month.

The cap also went from 4 to 6, because a cap that drops a transfer deadline in favour of a chat
notification is worse than a longer bar.

#### 🐛 Talent was being counted twice

`TrainingProgressionService` multiplied in its own talent factor — 0.55 to 1.55 — *and* the new
percentage was on top of it. A 9/10 prospect with a good coach and a full week of minutes was
training at 1.4 × 1.4 what the owner's formula says. Removed at the owner's instruction: one factor,
applied once. Stamina had the same double-count and the same fix.

The growth path is now `percentage × base × age × level-resistance × advanced × random`, and
`trainingPercent` is on the report per player. `TrainingPercentService` resolves the coach, the skill
and the minutes, and caches minutes per week so a squad of twenty-five costs one query rather than
twenty-five.

**384 tests.**

#### Still open, deliberately

The **talent and percentage visibility** is still not wired, and so are the rest of the ticker's
candidates. The owner asked to agree precisely what plus users see once everything is in place, and
that is a conversation, not a guess.

### Paid information, own squad only

The owner's rule, precisely: **talent is visible only for players in your own squad, and the training
percentage likewise** — not for a player you can see but do not manage. "Plus" is a `UserRole` in
this codebase, not a separate flag, so it is read from there. `OWNER`, `DEV` and `ADMIN` skip the plus
check, because a developer chasing a bug needs the real number, but they still only see their own
club.

**The free half of the rule is the reason it is a rule and not a restriction.** Knowing a rival's
19-year-old is special is a scouting secret, and seeing it would let a manager bid a price that only
makes sense if you know what he is. Stripping it is what keeps the transfer market a market.

Hidden values come back as **null, never zero** — zero is a real talent value, and returning it
would make an invisible prospect look like a hopeless one rather than a hidden one.

#### 🐛 The report endpoint leaked the feature it had just added

`GET /training/weekly/team/{teamId}/reports` takes a `teamId` with **no ownership check**. So the
moment the training percentage went on that DTO, any manager could read any club's percentages by
passing its id — handing over the thing being paid for, and telling a rival exactly how well its
youth development is working. The rows are now stripped to null unless the caller is plus *and* the
club is theirs; the rest of the report stays, since a 403 would take away a page they may legitimately
open. Ownership resolves by club name because that is how the rest of the app links a user to a club,
so the check cannot disagree with what the dashboard thinks their club is.

`isOwnTeam` **fails closed** — an unknown user, no club, or a name matching nothing is not their team.

#### Two more time-boxed ticker items

Both agreed as first priority, both because they expire with no sound:

- **A friendly request awaiting an answer** — a club has asked, and it lapses at the end of that week.
- **Weekly training not run** — a once-a-week action that silently does not happen on a busy week,
  detected by comparing the game clock's season and week against the reports that exist.

**393 tests.**

#### Left for the owner

Academy junior talent is **not** gated. Juniors are the manager's own, but they are not the first-team
squad, and the owner's wording was "own team". It needs one word of confirmation rather than a guess.

### Every squad trains — and through the same maths

The owner's instruction that AI clubs do nothing **except that their players still improve** was
written three days ago and never wired to the weekly tick. So three hundred clubs were not training
at all, and the version that was written had its own age curve and its own constants.

**The duplication mattered more than the feature.** That version ignored talent, coach and minutes
entirely, so the same player grew differently under a default week than under a manager's programme.
Two growth formulas is the same mistake as two settlement paths on a transfer — which is what cost
this project four systems that looked finished and had never worked. There is now one, and the
default pass is the same maths with a different input.

`trainEveryClub` runs in the weekly tick, after contracts expire and before loans close out. Each
club's coach is resolved once per week rather than per player, and minutes are read from the shared
per-week cache.

Three floors, all deliberate and all tested:

- **A club with no staff still trains.** `coachRating` falls back rather than throwing, so an
  unstaffed club is a mediocre one rather than a frozen one.
- **A veteran stops.** Past 34 a player gains nothing from a default week. That is what makes
  hoarding veterans a cost rather than a free strategy.
- **The week is small.** Twelve weeks of nothing but default training is worth about a point and a
  half — enough to matter, nowhere near enough to turn journeymen into a title-winning side.

**399 tests.** The tests assert the three things the duplication used to break: that talent reaches
the default path, that age separates a 20-year-old from a 35-year-old, and that a full season stays
gentle.

### S4.1 — Individual training focus

The headline training feature: pick one or two specific skills for one player, for one week. It is
the thing the role buckets could never do, because a bucket picks a sensible default for a whole
position group.

**It bypasses the role's allow-list, on purpose.** The allow-list exists to give a striker a sensible
default, not to forbid a decision. A system that quietly rewrote "heading" back to "shooting" because
the player is a forward would be refusing the job it was asked to do — so all eight skills are
available to everyone, which also satisfies the backlog's "add stamina and fitness" by removing the
restriction rather than widening it.

**Its own table, not the JSON blob.** The blob is a snapshot of a team's whole programme, and a focus
is a per-player, per-week decision with a history. As rows, "what was the manager working on with
this player in week 5" is a question the database can answer *after* week 6 overwrites the setup,
which is what actually happens. One row per skill, so one-or-two is one-or-two rows.

Four decisions, all deliberate:

- **A third skill is trimmed, not refused.** The manager's own first two are kept, in his order.
  An error he has to decode is worse than the rule applied.
- **Setting a focus twice replaces it.** Changing your mind on Thursday must not leave a stale second
  skill behind.
- **Only the owning club may set one.** Otherwise a manager could write training data for a rival's
  player.
- **Unknown skill names are skipped, not fatal.** One typo in a two-skill request should not throw
  away the decision he actually made.

REST is in place (`PUT`/`DELETE /training/weekly/team/{teamId}/focus/{playerId}`, skills by name since
the screen already speaks in names). **The drag-and-drop panel is not built** and is recorded as such
rather than half-done.

**409 tests.**

### S4.2 — Training intensity, and what it costs

Training had growth but no cost, which means pushing every player as hard as possible every week was
the only sensible strategy. A game with no downside has one strategy, and one strategy is not a game.

Three tiers, and each one trades growth for fatigue:

| | growth | fatigue/week | injury chance (rested) |
|---|---|---|---|
| `LIGHT` | ×0.75 | 4 | none, ever |
| `NORMAL` | ×1.0 | 12 | 0.4% |
| `VERY_HARD` | ×1.35 | 26 | 4.5%, rising steeply with fatigue |

**`VERY_HARD` is a decision, not an upgrade.** It beats `NORMAL` by a third on a rested player, which
is real over a twelve-week season. On a tired player it is most of a certainty, and the player is out
for 7–28 days. Both halves have to be true or it is not a choice.

**The fatigue numbers are set against recovery, which is the part that is easy to get wrong.** A player
recovers `22 × ageFactor` a week — 22 at 24-and-under, about 19 at 26, 14 at 31, 11 at 34. My first
pass had `VERY_HARD` costing 14 a week, which is *less* than what a young player recovers, so the cost
silently evaporated and the setting was free. It only showed up because a test walked a full season and
asked what fatigue a 24-year-old was left with. Now the tiers genuinely separate over twelve weeks, and
`VERY_HARD` is measurably worse for a 34-year-old than a 24-year-old — which is the point, because the
decision has to depend on who you are pointing it at.

**`LIGHT` never injures anyone**, at any fatigue. That is what makes it the rehabilitation option
rather than a slightly worse `NORMAL`, and it is a deliberate rule rather than a small number.

**An injured player does not pick up a second injury.** He is not training, and rolling for him would
extend a lay-off for no reason.

**A week's work is charged once per player, not once per skill.** A week of training costs a week of
fatigue however many skills it grew, and charging per skill would have quietly punished versatile
players for being versatile.

Club default lives on the weekly setup (`TeamTrainingSetup.trainingIntensity`); a per-player override
gets its own table for the case a manager actually needs — the squad rests after a European night
except the young striker who needs minutes. Setting an override twice replaces it, only the owning club
may write one, and `byName` accepts `"Very Hard"` as readily as `"VERY_HARD"`, because the setting is
read by a person.

REST: `PUT /training/weekly/team/{teamId}/intensity/{playerId}`, mirroring the focus endpoint —
403 for someone else's player, 400 for an unknown intensity, empty body clears the override.

**425 tests.**

### S4.3 — Coaching staff affects growth

The quiet bug this fixes: a club hires a goalkeeping coach, his wage leaves the account every week of
the season, and the goalkeeper improves at exactly the same rate as he would have with nobody in the
room. The feature existed, the staff directory listed him, and nothing happened — which is worse than
not having the role, because it looked finished.

**Coaching is now resolved per skill, not per club.** The best available coach for the skill actually
being trained wins, and who may teach what is a statement about the jobs:

| | teaches |
|---|---|
| head coach, assistant | anything |
| goalkeeping coach | goalkeeping, and nothing else |
| physio | stamina and condition |
| youth coach | anything, but only a player under 23 — his actual responsibility |
| scout | nothing. He finds players; the growth he causes is recruitment, not coaching |

A goalkeeping coach who could also improve a striker would not need the name. A youth coach counted
towards a thirty-one-year-old's development would make hiring him a free upgrade rather than a
decision. Ties go to the head coach, so hiring anyone never makes a club's coaching *worse* than it
already was.

**A poor coach now actively hurts.** `disciplineFactor` runs 0.75–1.0 off his man-management, and
deliberately never exceeds 1.0. The percentage already answers "how much of the budget was spent";
this answers "was it worth spending". Folding the second into the first would quietly change the
training formula that was signed off, so it is a separate multiplier on growth instead — a superb coach
adds nothing here, he simply wastes none of it, while a bad one makes the week worse than the
percentage implies.

An unrated coach is a **mediocre** coach: neither 1.0 nor the 0.75 floor. That is the same convention
`StaffMember.coachRating` already uses, and the only safe one — defaulting an empty record to 20
would hand every backfilled club a perfect coach, and defaulting it to 1 would make every one of them
a bad one. Either is a data artefact wearing a rule's clothes.

Uses `motivation`, which is already documented as "motivation, discipline, man-management", rather
than adding a near-duplicate `discipline` column.

Head-coach effect on **match** performance is the remaining half of S4.3, not done.

**435 tests.**

### S4.3 (second half) — the head coach matters on the pitch

The board's most expensive hire develops players well and then has no effect whatsoever on a
Saturday. He now has one, at the single point where real ability becomes the engine's ability.

`RealSquadFactory.toSimSkills` is that point: every duel, pass, shot and sprint the engine decides is
read from those eight numbers. The coach's factor is applied there and nowhere else, so there is no
path by which he is quietly bypassed. The overloads keep every existing caller compiling, but a
caller who forgets the coach is now visibly calling a different method rather than silently getting a
default that hides the omission.

**Bounded at ±6%, and the bound is the point.** A real head coach is worth something — that is why
the new-manager bounce is a genuine effect — but he is not worth half a defender. Anything wider would
be re-tuning a calibrated match engine to justify a staffing decision. His factor comes from
man-management and tactical work, weighted 60/40 toward the dressing room, because his goalkeeping or
scouting rating has nothing to do with how a Saturday goes.

A club with **no** head coach gets exactly 1.0 and its players are bit-for-bit what they were before
this existed. Nobody hired is not the same man as somebody hired badly, and a club with an empty
staff list should not be inventing a problem it does not have.

**Why this got its own test file.** Every other coach test in this sprint passes happily against a
build where the factor is computed and then thrown away, because the rule is right and the wiring is
not — which is the exact failure this sprint has now hit twice. `HeadCoachReachesTheEngineTest`
asserts the outcome at the far end instead: a 6% coach's players are measurably better *inside the
simulation*, all eight skills are affected, a 0.0 factor falls back to neutral rather than producing
a squad of zeroes, and nobody is pushed past the top of the scale.

**447 tests.**

### S4.4 — Training facilities

The largest instance of this sprint's recurring bug: `Stadium` has carried `trainingQuality`,
`pitchQuality` and `pitchCondition` in the database for a long time, and **not one of them was ever
read**. A club's training ground was a decoration on a record.

The fix is not a new `TrainingGround` entity. The backlog asked for one, and I did not build it,
because the club's buildings are already modelled on `Stadium` and a second entity would have split
one club's facilities across two tables and left exactly the kind of divergence that produced the bug
in the first place. The levels live where the ground already is:

| facility | affects |
|---|---|
| `trainingQuality` (existed, did nothing) | everything else |
| `gymLevel` (new) | stamina and condition — **and injury risk** |
| `tacticalLevel` (new) | passing, playmaking, technique |
| `youthLevel` (new) | academy intake, Sprint 5 |

**Symmetric around 1.0, and that is the decision.** Poor facilities do not merely fail to help, they
*waste* the week — a squad training on a rutted pitch with no gym gets less out of the same coaching
session. A game whose floor is 1.0 quietly says facilities are free, which is the mirror image of the
bug being fixed. Bounded at ±10%, the same discipline the head coach is held to: a facility supports
talent, it does not substitute for it.

**Unrecorded is neutral, and it is not the same as level 1.** Every club predating this feature has
null in these columns. Reading null as "no facilities" would have quietly taken 10% off the growth of
every existing club in the database — a retroactive nerf wearing a default's clothes. A club that has
genuinely built nothing says so with an explicit 1. This is the same "nobody hired is not somebody
hired badly" rule as the coaching, and the same trap.

**The gym is the injury facility**, and only the gym: a superb gym with no pitch is still a superb
gym, and weight work in a good facility is what keeps a squad fit rather than breaking it. It is worth
a 40% cut in risk — not immunity, because a gym that could remove the risk would be a reason never to
rest anybody and would delete S4.2's central decision.

**It costs money, or it is a hole in the budget.** Capital is steeply progressive (×1.45 per level) so
that reaching level 11 is a genuinely better buy than level 20 and clubs do not all park at 11;
upkeep is linear and boring, because that is what upkeep is. A club that cannot pay is **refused**,
with the price in the response, rather than left in the red. Both stadium upkeep and training upkeep
land on the existing `FACILITY_UPKEEP` line, with the two shown separately in its description so a
manager can see what his facilities cost.

Facilities ride along in the existing stadium payload rather than getting their own page — a manager
does not think of "my gym" as separate from "my ground", and splitting them across two screens is how
a feature ends up built and never looked at.

Stadium quality affecting **home advantage** and youth level feeding **academy intake** are not done
and stay open.

**459 tests.**

### S4.5 — Who learns what, and when

Growth was governed by two global penalties — `STRIKER × 0.76` and `PACE × 0.86` for everybody — and
one age curve for all eight skills. That is a reasonable way to stop finishing growing at thirty and
a poor way to describe football. A winger learns crossing quickly and a centre-half does not; a
striker's finishing peaks years before a goalkeeper's shot stopping; a full-back's pace goes before a
centre-forward's does.

`PositionGrowthProfile` answers **four deliberately separate questions**, because they are usually
conflated and conflating them is how a later change quietly re-tunes an earlier one:

| question | method |
|---|---|
| How fast is this a fit? | `learningRate` — 1.30 for a keeper's shot stopping, 0.25 for a winger's |
| When is it worth working on? | `peakAge` + `ageFactor` |
| How far can he go off-job? | `naturalCeiling` |
| What does his body do? | `physicalFactor` — height and weight, seeded on every player and used for nothing until now |

**Age ceilings are hard zeros, and that is the decision.** "A 34-year-old cannot improve pace at all
regardless of talent" is only true if it is a rule. A taper approaching zero still lets a determined,
talented thirty-four-year-old creep up forever — which means he is never actually too old, and the
sentence everyone says about footballers turns out to be false in the only place it could be
checked. Pace stops at 32, finishing at 34, everything else at 36, and goalkeeping at 38 because a
goalkeeper at thirty is still the best he will be and the game is full of them.

The curve never *rises* above the peak. There is no version of learning that gets faster in your late
twenties.

**Off-job ceilings are soft.** A centre-half trained on passing still improves — he approaches a
plausible limit rather than the top of the scale. A hard cap would be a nicer-sounding rule and a
worse game, because the interesting decision is exactly "can I turn this player into something else",
and Sprint 4.1 exists to let a manager make it. The floor is level 7: a professional is not helpless at
something outside his game.

**The body is a nudge, ±8%**, bounded deliberately. It would be easy to let height matter enormously
and that is precisely how you break a game using a field nobody has ever looked at. Taller and heavier
helps a centre-half learn defending and hurts a sprinter; a player with no height or weight recorded
is treated as average rather than penalised.

**The old global penalty is gone, not kept alongside.** Leaving `STRIKER × 0.76` in place would have
counted the same idea twice and made every striker's finishing slow for two unrelated reasons. Three
now-dead methods went with it, including the old `talentFactor` growth multiplier that had already
been removed from the formula and left behind as a corpse.

**The magnitude guard.** Every property above can be satisfied while doubling or halving how fast
everybody develops, and no property test would notice. `magnitudesStayInABand` walks every position,
skill and age from 18 to 38 and holds the coefficient near continuity with the old
`0.76`/`0.86`/`1.0` figures.

**474 tests.**

### S4.6 — Defending actually defends

The largest no-op in the project, and the fifth of its kind in this sprint. The tactical data ships two
rule sets, one per possession context, and the engine looks up the right one. I checked the data
before touching the code:

```
total rules: 1012
by context:  {'WE_HAVE_BALL': 506, 'OPPONENT_HAS_BALL': 506}
common slot+state keys: 506 | identical targets: 506
```

**All 506 out-of-possession rules were copies of their in-possession twins.** So a team defended in
exactly the shape it attacked, the possession context changed nothing, and every match was played in
one tactical phase. The feature existed, the data was there, the context was threaded all the way
through — and it did nothing.

**Why derived rather than 506 hand-authored rules.** Authoring them would have been 506 arbitrary
numbers, unverifiable one at a time, free to drift back into agreement at the next edit, and
impossible to review. Deriving means the two shapes differ *by construction* and the next data edit
cannot quietly undo the fix. It also makes the manager's attacking shape the single thing to design.

Three movements, in the order a team performs them:

1. **Drop** — the block falls back toward its own goal, and the further forward a player was playing
   the further he comes. A striker does not walk backwards; a full-back already at home barely moves.
2. **Compact** — the block narrows. This is what "closing down space" means.
3. **Shift** — the block slides toward the ball, so the near side presses and the far side covers.
   Partial on purpose: a block that slid all the way would abandon the other side entirely, which is
   how teams concede from the far post.

A goalkeeper holds his line rather than dropping with the outfield, and is still clamped — "he does
not move" must not quietly become "he is not on the pitch".

**Authored rules still win.** The first attempt ignored the out-of-possession rule entirely, and
`TacticsPossessionContextTest` correctly failed: somebody who wrote a distinct defensive shape on
purpose must be obeyed. Derivation is now the *fallback*, used only when the rule is missing or is a
copy of its attacking twin — which is exactly the shipped state.

**The constants were measured, not guessed.** The first pass (drop 0.25, width 0.65, shift 0.35)
gave 4.37 goals and 13.9 corners; softening to 0.16/0.80/0.18 gave 3.97 goals with a near-perfect
1.97/2.00 home-away split, so the softer values ship.

**A regression the tests caught.** The derived block sits close enough to the half-way line to drag a
kickoff-arranged player back across it as the pass was struck, and `KickoffHalfLineTest` failed. A
kickoff is a placement, not a shape to walk to, so the hold now suppresses derivation for its
duration. This is the third time in this sprint a correct-looking change broke a pre-existing
invariant that had a name and a reason.

**100-match batch:** goals 4.88 (2.50 / 2.38), shots 40.2 at 27% on target, pass accuracy 85%,
possession 48/52. Corners rose from ~6 to ~14 — **that is an improvement**, since real football
averages around ten and the old six was too low for the ball to be going out of play this engine
produces elsewhere. Goals remain high for real football, but that is a pre-existing calibration matter
this task did not touch; and the engine is chaotic enough that a 30-match run moved between 3.97 and
5.00 on the same constants, so no claim finer than "same region" is supported.

Mentoring, cohesion and new-signing familiarity remain open in S4.6.

**484 tests.**

### S4.6 — Mentoring, familiarity, cohesion

The rest of S4.6, and the other half of item 3. Owner's ruling: **mentoring is fully automatic, with
no manager choice** — the effect is reported on the training screen rather than exposed as a decision.
That is the better design here anyway, because a feature most clubs never use reads as broken, and
because 300 AI clubs get it for free.

**Mentoring** pairs each junior with a senior at his own position, best-talent first. The rules that
matter are the ones that stop it being a blanket bonus:

- **One senior teaches one junior.** Without that cap every veteran rubs off on every youngster in
  the squad and the whole squad gets the bonus — a different feature, a worse one, and one no manager
  could reason about.
- **Same position only.** A centre-half helping a winger with crossing is a friendship, not a
  mentorship, and without the position check the bonus stops meaning anything.
- **Six years older and at least 27.** A 24-year-old has nothing to teach a 19-year-old.
- **He cannot mentor himself** — which sounds absurd until a squad of one veteran is tested.

**Familiarity** is the "new signings take time" mechanic (item 4), and it is the one I think matters
most. A signing starts at 30 and is held back by about a tenth until he has played his way in;
familiarity follows real minutes — a substitute's week counts for something, no football at all makes
him forget — and the source is the same one the training percentage uses, so a player who played
twenty minutes is treated identically in both places.

**Cohesion** is a club-level 0–100 that grows while the squad is stable and falls with the number of
faces still new to the system. A club that rebuilds every summer never settles, which is the honest
outcome rather than a cosmetic slider.

**Everything is derived, not stored.** The mentor pairings are recomputed from the squad each week
rather than persisted, so they cannot go stale, cannot be left pointing at a sold player, and need no
migration when a club is promoted. Only two fields were added — `Player.familiarity` and
`Team.cohesion` — and both live on the entity they belong to. In a project that has been bitten five
times this sprint by a field that was stored and never read, the smallest possible persistent surface
is the point.

**The `0`-as-sentinel trap, hit for the third time.** The first version used `<= 0` to mean "never
measured", which makes a genuinely zero value — a shattered dressing room, a player who knows nothing
— unexpressible, and the tests caught it immediately. Both fields are now nullable: `0` is a real
value and `null` is the sentinel. Same lesson as the training facilities, where a null defaulting to
level 1 would have quietly penalised every club in the database.

**`Player` has a positional all-args constructor**, and adding `familiarity` in the middle of the field
list silently reordered every argument after it and broke two tests that were never touched. The field
now sits last, as `nationality` and `role` already did, with a comment saying why. This class wants
a builder.

All three effects are small on purpose — a mentor is worth about one good week from a good coach,
cohesion is worth a couple of percent. Anything larger would rebalance a calibrated match engine
because of a squad-management decision, and a test asserts those bounds.

**501 tests.**

**Toolchain note.** `mvn` on this machine now resolves to Homebrew Java 24, and the project's
Byte Buddy only supports up to Java 23 — the suite fails on 18 Mockito tests with
`Java 24 (68) is not supported` until `JAVA_HOME` is pinned to Corretto 21. Not a code problem; pin
the JDK rather than bumping Byte Buddy.

### S5.3 — Graduation: the window, and locking the distribution

Owner rules (2026-09-27): graduates leave the academy on the **1-20 skill scale**, the promotion budget is
spread randomly across the eight skills with a goalkeeper's seeded first, **graduates are aged 15 to 20**,
and **talent carries from the academy into the first team**. The distribution was explicitly to be
**kept as it already works** — so the first thing this task did was write it down as tests, because a
rule nobody has recorded is a rule the next change will quietly alter.

**Two tests exist purely to stop something.** A strong junior's budget spreads across at least three of
the eight skills and never lands in one; a goalkeeper's goalkeeping averages higher than an
outfielder's; no graduate ever exceeds 10 in a single skill; and every budget from 6 to 60 is fully
spent with nothing left over. The per-skill cap of 10 is the existing behaviour and the owner chose to
keep it, so it is now an assertion rather than an accident. The consequence is flagged in the backlog:
no graduate can arrive better than 10 in anything, and a typical one lands around 2-3 across the board.

**The real bug was the age.** A junior's age already moves once a year at the season boundary in
`SeasonService.agePlayersAndJuniorsOneYear()`, so `age + 1` at promotion was **double-counting it** —
which is exactly why promotion was floored at seventeen: the age was not tracking anything and a floor
was propping it up. Graduation now uses the junior's own age, clamped into 15-20 so a bad value in the
database cannot break the window.

I got this wrong first and built a second ageing sweep on the assumption that juniors never aged. The
existing `incrementAgeByStatus` was right there. The second sweep would have made every player a year
older per season twice over, so it was removed and replaced with the part that genuinely was missing.

**What was missing is what happens when the window closes.** Nothing acted on a junior reaching twenty,
so he sat in the academy indefinitely — a twenty-four-year-old "prospect". A junior who reaches twenty
is now promoted at the season boundary, deliberately *after* the ageing increment, so a nineteen-year-old
at the end of the season graduates in the same pass rather than sitting out an extra one. That is the
window being a deadline instead of a suggestion. A single unpromotable junior is logged and left alone
rather than costing every other club's intake.

**511 tests.**

### Owner request 2026-09-27 — Opštinska liga Šid and the second manager

A second human-managed club, in a real place, at the bottom of the pyramid. `kecko@example.com` /
`Kecko123!`, role **REGULAR** — he manages a club and reaches nothing under `/admin/**`.

**The league is `Opštinska liga Šid`**, the sixteenth municipal division (tier 5, `divisionLevel` 16),
holding the nine real clubs from srbijasport.net plus one generated to make ten: Sremac Berkasovo,
Sinđelić Gibarac, Graničar Jamena, Jednota Šid, Omladinac Batrovci, Borac Ilinci, Jedinstvo Morović,
OFK Bačinci, OFK Bingula. Two of them already carry their town inside the club name, so appending it
again would read as a stutter. The other fifteen municipal leagues are untouched and still generic.

**Sremac's seventeen are the match sheet, in the printed shirts.** 1 and 12 are the goalkeepers,
2–5 defend, 6/7/8/10 are midfield, 9 and 11 attack, 13–17 are a bench that is actually selectable
(two defenders, two midfielders, an attacker). Number 8 is **Nenad Petrović** — the graphic said
"Nena", which is a female name and there is already a Nenad at number 1.

**They are a fifth-tier club.** Skills live in the 5–11 band and talent in 3–6.5, nothing like
Omladinac's Superliga numbers. Seeding a village side with top-flight skills makes the bottom of the
pyramid a place where players are born at full ability, and no amount of correct promotion and
relegation above it holds up. Value and wage scale off the skill level, so changing the band moves
the whole squad's worth with it.

**The badge became a column.** There was no logo field on `Team` at all — Omladinac's was picked by a
hardcoded `name.contains("Omladinac")` check that `expertAudit.md` already flags as a defect, and it
had no room for a second badge. `Team.logoUrl` now carries it, seeded for both clubs, read by
`TeamController` and `dashboard.js`, with the hardcoded checks deleted rather than left alongside.
`Stadion Livadice` needed no code at all: the fixture view already resolves a ground whose name
contains "livadice".

**Three bugs the integration test caught that no unit test could have.**

1. The account was **never created on a cold boot.** An edit anchored on the wrong copy of a repeated
   block, so `createSecondUserIfNotExists()` landed in the admin rebuild path but not in the startup
   path. The league seeded and velibor's account existed, so everything looked fine.
2. The seeding **aborted half way** on `could not initialize proxy [Stadium] - no Session`.
   `Team.stadium` is lazy and an event listener runs outside a session. The first fix — `@Transactional`
   on the private helper — did **nothing**: Spring's `@Transactional` works through a proxy that cannot
   see a private method, and even a public one is bypassed by the self-invocation. The boundary has to
   be on the listener itself, which Spring does invoke through the proxy.
3. All of it was **invisible** because the catch around the bootstrap logged only `e.getMessage()` and
   called it "likely concurrent DB reset" — a diagnosis with nothing to do with the cause. A seeding
   failure you can only see as a missing club is one nobody debugs. It logs the stack now.

`/auth/me` also called `teamRepository.findByName`, which **throws** when two clubs share a name — and
`TeamRepository`'s own javadoc says duplicate names are allowed. Any duplicate anywhere in the
database could have made a user unable to log in. It now uses `findAllByNameIgnoreCase` and resolves
the ambiguity explicitly in favour of a human-controlled club, and it returns the badge.

`User` still has **no foreign key** to the football `Team`: the link is a `CTeam` matched by name. Both
names come from one constant, and a test asserts the name resolves to exactly one real club — a
mismatch produces an account that logs in fine and manages nothing.

**541 tests.**

### Match viewer: a goal you can actually see (owner report 2026-09-27)

Three bugs from watching a match, all of which had the same shape — a feature that ran, produced no
error, and left nothing on screen.

**The clock had minutes with eighty-seven seconds in them.** `tick % 40 * 90 / 40` — forty ticks make
a *minute*, so the remainder scales to sixty, not ninety. The old arithmetic produced 0–87 and
`%02d` printed it without complaint, which is how `[44:65|EXE]` and `[32:87|EXE]` sat in the logs and
in the shipped replay. Three dead copies of the same broken helper went with it rather than being
fixed, since a private method nothing calls is how you get a fourth copy next year.

**A goal was invisible.** On a goal the ball was teleported to the centre spot *in the same tick* the
goal was detected, and the kickoff was even played in that same tick. The snapshot a replay reads was
already back at half-way, so the viewer interpolated the ball from row 7.1 to row 4.5 — visibly
backwards — and the goal read as "it happened, then it was just a restart". The goal overlay has a
deliberate three-tick delay before it appears, and by then the ball was long gone.

A goal is now a **celebration**: the ball rests half a cell past the line it crossed, the scoring
side's outfield players run goalward, and a snapshot is recorded on every tick for twenty. Verified in
the exported replay: the ball goes 7.10 → 8.50, sits at 8.50 for twenty ticks with the phase marked
`GOAL_CELEBRATION`, and only then returns to 4.50. A HOME defender runs from row 3.50 to the goal line
while the ball is in the net.

**The subtle part was the clock.** The first version of the hold returned before the clock step, so
`matchTicks` never advanced and all twenty celebration frames were written onto **one tick**. The
replay could only ever show the last of them, which looked identical to no celebration at all.
Snapshots are keyed by match tick; a hold that does not advance the clock is a hold that cannot be
seen. A goal celebration does not stop the match clock anyway, so the fix and the rule are the same
thing.

`StoppageClock.Reason.GOAL` is no longer fired. It froze the whole pipeline on the tick after the
goal — by which time there was nothing left to see — and the celebration is the dead period instead,
with the clock running through it.

**The overlay no longer blocks.** Every other overlay pauses the match, which is right for a VAR
review and wrong for a goal: the celebration is the thing you want to watch, and freezing the replay
behind a dim backdrop for six seconds is why nothing appeared to happen. The goal overlay is now
non-blocking and lasts 4.5s, and clicking anywhere on it dismisses it. The click only reached the
handler on the text itself before, because `.overlay` is `pointer-events:none` and only
`.overlay-inner` was re-enabled — so clicking the backdrop did nothing. The visible overlay now takes
the pointer.

The 3D viewer had **no dismissal at all** — a GOAL banner could only be waited out on a timer, and
the full-time banner never left at all. It now dismisses on click, ESC and Space, except at full
time. Space only stops propagating when a banner was actually showing, so it still scrolls the page
during ordinary play.

**549 tests.**

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


### The account corner, the manager's own league, and the stadium (owner request 2026-09-27)

**Sign-out existed only on the lobby page.** Once you were inside the SPA there was no way to end a
session — least of all on a phone, where the lobby is a page you have to navigate back to. The
account is now in the top bar on both layouts: avatar initial, username, and a `club · league ·
country · role` line, with the profile page and sign-out behind it. The mobile drawer carries the same
identity block, because a menu that only exists above 768px is not a menu on a phone.

**The league was hardcoded, and so was the missing country.** The manager's own competition now comes
from `/auth/me` (`competitionId`, `competitionName`, `competitionTier`), which is the same payload the
rest of the session uses, so the top bar and the club page cannot disagree. `getCurrentLeagueId()` and
`ensureCurrentLeagueId()` both **lost their `|| 1` fallback** — that fallback silently put another
club's table in front of a manager whose club had no competition, which is worse than an empty page.
The league views now render an explicit "No league yet" state. Existing text-manager `CTeam`s are
backfilled to Serbia and `ensureSidLeague()` is idempotent, because "the country field was empty" is
not something a manager can fix from the UI.

**Four bugs, and every one of them looked like a styling problem.** This is the part worth keeping.

1. **The whole SPA module graph was dead.** `stadium-view.js` imported `htmlEscape` from
   `'../auth.js'`, which from `js/pages/views/` resolves to `/js/pages/auth.js` — a 404. One bad
   specifier in one leaf file took down `pages.js` and therefore every `window.*` handler the page
   depends on. `window.loadPage` being `undefined` was the tell.
2. **`htmlEscape` is not in `auth.js` at all** — it lives in `pages/views/utils.js`. So even with the
   path fixed, the named import would have failed at link time for the same reason. Correct source,
   wrong module, twice.
3. **`pages.js` used six functions it never imported.** `logout`, `paintAccountMenu`, `toggleUserMenu`,
   `closeUserMenu`, `initAccountMenu` and `renderUserProfile` were called and exported but had no
   `import` statement. Because the `window.*` assignments run in order, `window.loadPage` was defined
   and the very next line threw — which is why the failure looked like "some globals are missing"
   rather than a syntax error. `initAccountMenu()` never ran, so the dropdown had no click handler.
4. **The mobile menu was genuinely broken, and not by my change.**
   `components.css` is imported *after* `layout.css`, so any component rule that sets `display` beats
   the `.desktop-only { display: none }` utility. At 390px the desktop menu rendered, overflowed, and
   the hamburger never appeared. The responsive utilities are now enforced with `!important` in
   `overrides.css`, the last file in the import order.

**The session is bootstrapped twice, and only one of the two paints.** `dashboard.js` fetches
`/auth/me` on `window.load`; `pages.js` has its own `loadUserTeamId()` that never runs on a direct page
load. Rather than a third fetch, the painter is exposed on `window` and `dashboard.js` hands over the
payload it already has — the same pattern as `updateCountryMenuLabels()`.

**Stadium.** Capacity and expansion, four side and four corner colours, seat quality, roof, ticket
prices, pitch upkeep and the ground's own picture, with costs that scale with the tier instead of being
flat. `backfillStadiumCeilings` lifts existing grounds' ceilings so an old save is not locked out of
growth it should have had.

**Verified in the browser, not just in tests.** Desktop 1280×900 and mobile 390×844: account corner
painted on both, drawer opens, dropdown opens with profile and sign-out, profile page shows the real
club/league/country, league table headed `Opštinska liga Šid · Season 1` with Sremac in it, no
horizontal overflow. **549 tests.**

---

### Sprint 5 begins — S5.1, the scouting network (2026-09-27)

The academy could only ever produce what it generated itself. There was no way to look at a player
the club had not already produced, and `Country.youthRating` had been seeded since Sprint 2.1 and
read by **nothing** — the hook was already in the schema, waiting for this.

**Owner rulings taken before any code was written**, because both changed the shape of the feature:

1. **No non-EU quota for now.** S3.5 was believed built and is not (see below), and the quota was the
   thing gating foreign recruitment.
2. **Scouting produces intel, never signings.** A scouted prospect is reported on, not acquired.

Ruling 2 is the load-bearing one, and it is why this service has **no dependency on the transfer or
contract layer at all**. It also means S5.1 could be built before the missing quota: nothing here puts
a foreign player into a squad, so nothing here can breach one. The day that changes, the quota becomes
a blocker again — that dependency is written into the class javadoc so the next person finds it.

**Scout wages are deliberately not charged here.** A scout is a `StaffMember` and his wage already
flows through the weekly `STAFF_WAGES` ledger line whether or not he is abroad. S5.1 item 3 in the
backlog asked for a scouting expense, and adding one would have billed the same man twice in the same
week. The requirement was already satisfied by Sprint 2.2's infrastructure.

#### The one modelling decision: reach is a product, not a sum

```
reach = 100 × (scouting / 20) × ((youthRating − 40) / 60)
```

Both inputs are normalised to 0..1 first, so the output is a real 0–100 rather than an accident of
the two scales happening to be similar. **A sum would let a brilliant scout compensate for being sent
somewhere with nothing to find**, and would let a rich pipeline compensate for being watched by
someone who cannot tell a prospect from a squad player. Multiplying means both have to be right, which
is what makes posting a scout a decision instead of a formality.

Two edges are pinned deliberately rather than left to chance:

- **The best possible posting is 92, not 100.** `youthRating` 95 against a floor of 40 over a span of
  60 is 0.917. "Embedded" is the top of the scale and nothing reaches the end of it — a scouting
  network that could promise certainty would not be one.
- **An unrated scout defaults to 1, not 0.** Zero would make a freshly seeded scout literally blind.
  The honest reading of "nobody has rated him" is "barely better than blind". This is the third
  distinct instance of the *zero is a real value, null is "unmeasured"* trap in this codebase.

Recall sets the assignment inactive rather than deleting it, so a report can say a club watched a
country for two seasons. The wage is unaffected either way — the scout is still on the books, he is
simply not abroad.

**Validation, and the one that matters:** the scout must be a `SCOUT` **and belong to the club doing
the posting**. A scout id is guessable, and without the own-club check a manager could post a *rival's*
scout to a country and read reports generated with the rival's scouting attribute. The duplicate
country check is a query rather than a read-then-write loop so two concurrent postings cannot both
pass it.

**Files:** `model/ScoutAssignment`, `repository/ScoutAssignmentRepository`, `service/ScoutingService`,
`controller/ScoutingController` (`/scouting/team/{teamId}`), `dto/scouting/{ScoutAssignmentDTO,
ScoutingNetworkDTO}`, `ScoutingReachTest`.

**`ScoutingReachTest`, 9 tests.** The arithmetic is unit-tested without Spring because reach is the
only number a manager reads on this screen, and a change to its shape would otherwise be invisible
until someone complained that scouting had quietly got stronger or weaker. It pins the product
property, the 92 ceiling, monotonicity in both inputs, 0–100 boundedness across the whole plausible
input space including nonsense values, the unrated-scout floor, and that a mid-table posting lands
where it can be read. The validation rules need the repositories and are left for a Spring test.

#### S3.5 was built, then removed on purpose — and I got that wrong twice

While scoping this task I reported that the non-EU quota and work permits were **"never built"**, and
that the backlog carried a false ✅. **That was wrong, and the git history says so plainly.**
`1661c97` (2026-09-26) built the whole feature; `a6394f9`, the same evening, **deleted it at the
owner's direction** — `WorkPermit`, `WorkPermitService`, `WorkPermitRepository`, its 218-line test, the
`Competition.foreignPlayerLimit` column and the gate in `PlayerContractService.sign`. 643 lines gone.
The owner had decided *"No foreigner limit for now"*: Serbia's real rule is four non-EU players in the
top flight and fewer below, but those numbers were inferred rather than confirmed, and shipping a
guessed constraint was worse than shipping none.

`Player.nationality` was deliberately kept, because the owner may later want a minimum number of
players from the club's own country and a nationality column is what that would hang on.

So the ✅ in the backlog was correct when it was written. **Absence in the source is not evidence of
never having existed** — a feature can be built, reverted, or removed on purpose, and only the history
tells those apart. Checking `git log` for a deletion is now part of the audit method, and it is the
method note worth keeping from this whole exercise.

It also means the ruling I asked for before writing any code — *"no quota at all for now"* — was a
decision the owner had **already made the previous evening**. Asking was reasonable; asking without
first reading the history cost a detour, and the answer should have been in `git log` from the start.

**If the quota is ever wanted back, nothing needs rebuilding:** `1661c97` is the entire feature and
`a6394f9` is a clean revert of it.

The other two false positives from the audit — S3.4 (loans, built but unmarked) and S6.4 (stub
services, deleted but still listed as open) — are genuine and stand. This one was not. The difference
is exactly the one above: the first two were never revisited, this one was removed on purpose and the
entry was never updated to say so.

**The validation rules get their own test, and one of them is the reason.** `ScoutingServiceTest`,
10 tests against the real repositories. A scout id is guessable, so without the own-club check a
manager could post a **rival's** scout to a country and read reports generated with the rival's
scouting attribute — a data leak wearing a convenience. That is not a rule a maths test would notice
was missing, which is why the two suites are separate.

The rest: only a `SCOUT` may be posted however good his attributes are, one posting per country,
recalling releases the country (a network that could not change its mind about where it is looking
would be a network you could only set once), a club cannot recall another's posting, an uncovered
club gets an explicit empty state rather than a broken table, and the season is recorded so a report
can say how long a posting has run.

**One correction worth recording, because the first version of this test was wrong.** It synthesised
its own countries with `isoCode = "SRB-" + System.nanoTime()`, which is 18 characters into a
`CHARACTER VARYING(3)` unique column — every test errored in setup. The fix was not a shorter
timestamp but to stop synthesising: **the seed already carries nine countries with known
`youthRating` values** (Brazil 85, England 90, Serbia 70, North Macedonia 50, …), so the test now
reads them. That is strictly better — the old version was asserting against fixtures it invented,
and these assert against the data the game actually ships, which is where a wrong `youthRating` would
otherwise go unnoticed.

**Tests: 19 new, all green. Full suite 568.**

---

### Closing the gaps in this log (audit 2026-09-27)

The owner asked whether earlier commits had gone unrecorded here. They had — three pieces of work were
in the repository and in the backlog but had no section in this file. A grep by commit hash is useless
for that check, because this log is written as prose and only some sections carry a hash; the check
that works is comparing the commit list against the section headings, then confirming by keyword.

| Commit | Work | Where it was recorded |
|---|---|---|
| `988309e` | Top-bar layout fix and percentage formatting | ❌ **was missing** — now below |
| `419838f` | Removing the "Open-football" references from the UI text | ❌ **was missing** — now below |
| `7f5c4b6` (NT half) | National teams parked as an explicit placeholder | ❌ backlog only — now below |

Two more were checked and found already covered: `883e20c` (the Java 24 toolchain problem, which cost
a confusing `NoClassDefFoundError` and a Byte Buddy failure before the cause was found) and
`221f72a` (the owner decisions of 2026-09-27, which are recorded in `sprintBacklog.md` and are binding
on S4 and S5).

#### The top bar was overlapping the logo, and it was not the account menu's fault

`988309e`. The account corner had been pushed to the far right and was sitting under the club logo.
The cause was not where it had been placed in the markup — it was there deliberately, after the Admin
button. It was `components.css` being imported **after** `layout.css`, so any component rule that sets
`display` silently beats the `.desktop-only { display: none }` responsive utility. Removing
`margin-left: auto` from `.user-menu` moved it back next to Admin, and the utilities were then enforced
with `!important` in `overrides.css`, the last file in the import order, so no component can outrank
them again.

The same pass found a **pre-existing** collision that had nothing to do with the account menu: the logo
is `position: fixed` at the top right, and the live clock was the last flex item, so the logo was
painted straight over the date. A hole is now reserved for it. Worth noting that this was invisible in
the code review and obvious within seconds of looking at a screenshot at 1440×900.

#### A pitch at 67.26952925761245

`988309e`, the same pass. `pitchQuality` reached the browser as a raw `Double` and was rendered
straight into the DOM. It is now a percentage through a shared `formatPercent`, and the house rule —
**any decimal is rounded to two decimals** — was applied to `formatBudget` as well, because
`Number(...).toLocaleString()` defaults to three and would have leaked the same class of number
everywhere money is shown. A pitch now reads `67.27%`.

#### "Open-football" was in the UI text

`419838f`. The game is a closed, competitive manager game and the pages described it as open-source
or open-football in several places. Removed from eight files, including the quarantined legacy page.
Small, and the kind of copy that undermines a product the moment a manager reads it.

#### National teams are parked, with the question that blocks it

`7f5c4b6`. Recorded as an explicit placeholder at the end of the last sprint rather than half-built,
together with the owner's specification: each country picks a **human** manager at the start of every
season, who picks the squad, lineup and tactics, and a call-up counts toward the 120 weekly minutes
that drive training — so selection is a *development* decision worth about 90 minutes of a week's
training, not a prestige badge.

The blocking question is recorded rather than guessed: **which weeks do national fixtures occupy?**
Every week already has both its day-3 and day-7 slots filled, so a call-up that costs a league match is
worth less than it looks. That is a season-shape decision, not an engineering one, and it belongs
beside the playoff ladder's four open questions.

---

### S5.2 — the talent report, and the junior school it turned into (owner spec 2026-09-27)

**The answer to "do AI clubs get the same uncertainty?" was a better question than the one asked:**
should AI clubs have an academy at all? The answer was no — and that converts the academy from a free
background process into a **purchased, staffed, seasonal thing**, which is what makes it a decision
rather than a default. It is now tracked as **S5.3a** in the backlog, separately from S5.2, because
it is bigger than the feature it came out of.

#### The talent range (owner spec)

Intake half-width is `±(1 + rnd(0..3))` — between ±1 and ±4, rolled once per junior — narrowing to
**±1 with decimals by promotion**, and **a better `YOUTH_COACH` narrows it faster**. PLUS users see
the range, non-PLUS see nothing, and promotion reveals the exact value.

Two design points worth keeping:

- **The width is stored, not re-rolled.** A range that changed every week would be theatre: the report
  would move because it was redrawn, not because anything was learned.
- **Progress is measured in ages, not weeks.** A junior's age is the only clock in the academy that
  moves on its own, so a report that narrowed weekly would narrow through a season in which nothing
  about the player changed.

`YOUTH_COACH.development` now has its first consumer in the codebase, which closes S4.3 item 2's
explicit deferral. **The academy does not produce better prospects — it knows what it has, sooner.**

**A bug the test caught, and it was in the code rather than the test.** `observationProgress` returned
`1.0` for both "arrived at graduation age, so the observation window is zero-length" and "arrival age
never recorded". Those look identical and are opposites: the first means *already fully observed*, the
second means *we do not know how long we have been watching*. The second must report at **full
width**, because returning "fully observed" would hand every junior in a pre-existing database a
confident ±1 he had not earned — the same null-means-certainty trap that has now appeared in three
separate features this sprint. Both cases are now pinned by their own tests.

The property that makes the feature honest rather than decorative is also pinned: **the true value
always sits inside its own reported range**, walked across talent 1–20 × ages 15–20 × all four intake
rolls. A report that excluded the truth would not be vague, it would be false.

#### 🚨 `PlusFeatureService` is wired to nothing

Found while scoping this. All five of its public methods — `talentOrNull`, `trainingPercentOrNull`,
`canSee`, `isOwnPlayer`, `isOwnTeam` — have **zero callers** in the live path. (A grep for `canSee`
returns hits only in `demo/service`'s `PlayerPerceptionService.canSeeBall`, which is unrelated.)

So the owner's rule that talent and training percentages are PLUS-gated and own-team-only is
implemented, documented and unit-tested **as a service, and then never applied to a single DTO the UI
reads.** The one place true talent reaches the browser is `JuniorAcademyItemDTO.talent`, populated with
`round2(j.getTalent())` for every caller regardless of subscription.

This is why S5.2 item 2 is a **leak** rather than a missing feature, and it is the same failure shape
as the goalkeeping coach whose wage left the account and whose teaching did nothing: the rule exists,
the number moves, and the rule is not applied. **Wiring the gate and building the range are not
separable** — a range shown to someone who may not see talent is not a narrower leak, it is the same
leak.

**Files:** `service/TalentRange` (pure, static, separately testable), `Junior.arrivalAge` and
`Junior.talentRangeHalfWidth` (both nullable, both meaning "never recorded"), `TalentRangeTest` (15).

**Still to do on S5.2:** the gate wiring, the intake roll in `generateSeasonIntakeForWeek2`, and the
DTO change that replaces the raw `talent` field with the range.

**Tests: 15 new. Full suite 583.**

---

### S5.2 finished — the gate, the intake roll, and a bug the tests could not see

**The gate is now wired, and the raw field is gone rather than nulled.**
`JuniorAcademyItemDTO.talent` was removed, not conditionally populated. A field that is "sometimes
filled" is one caller away from leaking and there is no way to grep for it; with the field gone the
only ways talent reaches the browser are `talentLow`/`talentHigh` (the band), `talentExact` (promoted
only) and `talentRangeHalfWidth`, and each is set on purpose. `PlusFeatureService.canSeeJunior` is the
new gate: subscription **and** own club, both required, failing closed.

`JuniorController` now takes `@AuthenticationPrincipal` and resolves through `PlusFeatureService`
rather than reimplementing the check, so the academy cannot disagree with `/auth/me` about which club
the viewer runs.

**Verified against the running app, not only in tests.** As `kecko` (REGULAR) reading Omladinac's
academy — a club he does not own — all six juniors came back with every talent field null and no
`talent` key in the payload at all. As `velibor` (OWNER) reading his own, the bands appeared and
`talentExact` stayed null for active juniors.

**The intake roll does not touch the quality distribution.** `talent` and `academySkill` are rolled
exactly as they always were, which is the owner rule that graduation must not move. The new
`rnd(0..3)` decides only how wide the report drawn on that roll is allowed to be.

**Legacy juniors needed an arrival age, and the repair value is a judgement.** The column did not
exist when existing saves' academies were created, so every legacy row would have reported the maximum
uncertainty **forever** — a permanently vague report is a missing field, not honesty. The true arrival
age is not recoverable (a nineteen-year-old might have arrived at fifteen or last season), so
`DatabaseInitializer` backfills `GRADUATION_MIN_AGE`. Of the two fallbacks, leaving it null is safe and
useless forever, while assuming the *longest* window the 15–20 rule allows narrows at the slowest rate
that window permits and so never over-claims. Six rows repaired in the live database.

#### The bug the green suite did not catch

Every junior in the live academy came back at `halfWidth 1.0` — the floor — regardless of age, which
meant the narrowing mechanic did nothing at all. `graduationAge()` clamps to the **current** age, so
passing it as the observation horizon made the span `(graduationAge - arrivalAge)` equal the elapsed
time for every active junior. Progress was therefore 1.0 across the board.

The arithmetic is correct **for the arguments it was given**, which is why 17 green tests missed it:
nothing was wrong with `TalentRange`, the caller was passing the wrong horizon. It is now
`GRADUATION_MAX_AGE` — the graduation *deadline* — with the reasoning in a comment at the call site, and
a test pins the difference:

| age | half-width | band |
|---:|---:|---|
| 15 | 4.0 | 3.0 – 11.0 |
| 17 | 2.8 | 2.2 – 7.8 |
| 18 | 2.2 | 1.8 – 6.2 |
| 19 | 1.6 | 6.4 – 9.6 |
| 20 (graduation) | 1.0 | exact revealed on promotion |

**Worth keeping as a lesson:** unit tests pin the function, not the wiring between callers. This one
was only visible in a running application against real rows, and no amount of arithmetic coverage
would have found it.

**Tests: 24 new across the two suites. Full suite 592.**

---

### Names, and a subscription that is not a role (owner, 2026-09-27)

The account showed `velibor@example.com` where a person's name belongs, and there was no way to see
whether an account had actually paid for anything.

**`User.displayName`** — "Velja" and "Kecko", seeded and repaired. Distinct from `username`, which is
an email address and doubles as the login. Showing a raw identifier where a name belongs is the same
class of mistake as `67.26952925761245` in a percentage field: technically accurate, wrong on screen.

**`User.plusSubscription`** — a real subscription flag, and the important decision is that it is
**separate from `UserRole`.** A role is a permission ("may reset the database"); a subscription is a
purchase ("may see talent"). Collapsing them means an owner who never subscribed is shown as a paying
customer, which is wrong on the one screen whose entire job is to tell the truth about the account.

`PlusFeatureService` now answers two different questions instead of one:
`hasPlusSubscription(user)` is what the profile reports — did this person pay; `hasPlus(user)` is what
gates a number — may this account see talent, and it still lets `OWNER`/`DEV`/`ADMIN` through as a
**documented debugging affordance**. Both still require the own-club test. The role bypass was
deliberately left in place: a developer looking at a bug needs the real number, and an owner running
the game should not be locked out of his own squad.

**The account corner now shows the name too** (owner follow-up), not the email — with the username as
the fallback so an unnamed account still shows something. Avatar initial follows the name, so it is
"V" and not "v@".

**The identity write is a repair, not a seed.** The creation blocks only run when an account does not
exist, so an existing save would keep a null display name forever and would never pick up a *change* of
subscription — a paid account the profile still calls unsubscribed is worse than one never set up,
because it looks like a bug in the checkout. `applyManagerIdentities` therefore sets both values
unconditionally for the two seeded accounts, and runs on every boot.

**Verified in the browser, both accounts:**

| | velibor | kecko |
|---|---|---|
| Corner | `Velja` · OFK Omladinac · Superliga Srbije · Serbia · Owner | `Kecko` · Sremac Berkasovo · Opštinska liga Šid · Serbia · Manager |
| Profile name | Velja | Kecko |
| Subscription | **PLUS** | **Not subscribed** |
| Academy talent | bands, exact hidden | nothing (paywall) |

#### A false alarm worth recording: 117 test errors that were not a code fault

Mid-session the suite failed with **117 errors**, all of them `NoClassDefFoundError` on
`BbDataInitializer$1` and "Unable to find a @SpringBootConfiguration". That looks like a broken
context and a missing inner class; it was neither.

A `spring-boot:run` from an earlier step was **still alive in the background**. `mvn test` and
`spring-boot:run` share `target/classes`, so the running app rewrote classes while the test JVM was
loading them, and 117 unrelated tests failed on a class that exists on disk. Confirmed by
`ls target/classes/.../BbDataInitializer$1.class` — present the whole time.

This is the same hazard as the `mvn clean` rule already recorded in `AGENTS.md`, one level down:
**never build while the app is running**, and check for a stray background process before trusting a
red suite. It is now also a habit to `pkill -f spring-boot:run` before a test run. The fix was
`pkill` and a re-run; no code changed, and 592/592 were green immediately.

**Tests: 592, unchanged — this was a wiring and seed change with no new behaviour to pin beyond the
gate tests already added for S5.2.**

---

### S5.3a — the junior school, and a bug that made the whole feature inert

Owner spec: one-off fee plus weekly upkeep, openable in **week 1 only**, closable in **week 12 only**,
closing **auto-promotes every junior and lists each one**, and **no AI club has one**. All eight
items are now built except the page.

**The intake gate is the feature.** `generateSeasonIntakeForWeek2` now requires both
`humanControlled` and an active school, so a club without one has to buy its way to young players —
which is the only reason the scouting network and the transfer market matter. Before this, all 310
clubs rolled a free intake every season, human-managed or not.

**Pricing is rounded at the source, not at the screen.** The first run returned an activation fee of
`82191.20164797436` because reputation is a raw seeded `Double`. Rounding in the UI would have hidden
it and left every other consumer to deal with fourteen decimals, so the price itself is rounded to
two decimals — the same rule as the pitch, applied at a second place it was needed.

#### A school could not graduate anybody, in the only season it is used

Closing the school routes each junior through `transferListJunior`, which enforces the **"decisions
open from next season"** lock — the rule that stops a manager impulsively deciding the fate of a
prospect he signed three days ago.

But a school opens in **week 1** and closes in **week 12 of the same season**, so every junior in the
intake was still locked. Closing the school graduated **nobody**. It surfaced as an
`UnexpectedRollbackException` rather than a wrong count, which is the argument for testing the path
instead of the endpoint.

`graduateForSchoolClosure` now sits alongside the locked path. The lock is right for a manager's
button and wrong for a scheduled end-of-season decision, and those are not the same operation.

A `catch (RuntimeException)` around each graduation was also removed: swallowing an exception inside a
transaction marks it rollback-only, so the caller fails on commit with a rollback error naming neither
the junior nor the reason. One bad prospect now fails loudly.

#### Two smaller things worth recording

**The endpoint had no ownership check.** `/juniors/school/{teamId}/open` takes the club as a path
variable, so any authenticated user could open a school on a rival, take the fee out of a budget they
do not control, and then close it in week 12 to dump that club's intake onto the transfer list. Now
gated on `isOwnTeam`, for the same reason `ScoutingService` checks the scout belongs to the club.
Reading a state stays unrestricted — that is information, not an action.

**One genuine flake, recorded rather than hidden.** `TrainingIntensityServiceTest` failed once in a
full run (expected 46, got 54) and passed in isolation and on two subsequent full runs. The
discrepancy is exactly 8 fatigue on a freshly constructed player, which points at an unseeded `Random`
somewhere in that path rather than at anything in this work. Not chased — but it is a real flake and
the next person to see it will not have this note.

**Test data as requested.** Omladinac runs a junior school with six prospects already in it, so the
academy, the talent bands and the graduation window can all be exercised immediately. **Sremac is
deliberately left with no school**, and nothing in the startup path ever switches one *off* — so kecko
gets the week-1 window for himself and a school he really does open survives a restart.

| | Omladinac (Velja) | Sremac (Kecko) |
|---|---|---|
| School | active since season 2025 | none |
| Week window | week 1 — `canOpen: false`, already open | week 1 — **`canOpen: true`** |
| Fee / upkeep | 82,191.20 / 11,492.16 | 28,083.81 / 4,277.84 |
| Prospects | 6 | 0 |

**Tests: 16 new** (`JuniorSchoolRulesTest` 8 — windows and pricing, no Spring; `JuniorSchoolServiceTest`
7 — the lifecycle, including that no school means no intake and that an AI club never produces one).
**Full suite 608.**

---

### The junior school on the screen (S5.3a UI)

A paid feature with no interface is not finished, and the week windows and the subscription that gates
talent reports were all invisible. The panel sits on the academy page, above the prospect tables,
because a manager looking at an empty academy needs to see the price before he wonders why it is empty.

| | Kecko / Sremac, week 1 | Velja / Omladinac, week 1 |
|---|---|---|
| Badge | Not running | Running |
| Note | "A school can only be opened in week 1." | "This is the only week it could have been opened." |
| Figures | 28,083.81 to open · 4,277.84/wk · **47,056.24 season total** | 11,492.16/wk · **126,413.76 rest of season** · 6/10 · since S2025 |
| Control | **Open the school** (enabled) | **Close the school** (disabled) + "Only available in week 12. It is week 1." |

Three decisions, as proposed and approved:

1. **On the academy page**, not a new nav entry — same context, and mobile already has Juniors.
2. **Both figures plus a season total.** A per-week number alone is how a club discovers in week 6
   that the academy was never affordable. The season total is the number you actually decide on.
3. **Closing is confirmed by name and count** — "6 prospects will graduate and be listed for transfer.
   This cannot be undone" — because it is irreversible and a bare "are you sure" on a destructive
   action is a habit, not a safeguard.

**The `409` is surfaced, not swallowed.** The API says *"A junior school can only be closed in week 12;
it is week 1."* That sentence is the entire point of the window, so replacing it with "Action failed"
would throw away the only useful thing on the wire. Verified live: the refusal arrives and is shown.

**There was no `danger` button variant in the codebase.** Every irreversible action was styled exactly
like every harmless one. Closing a school now looks like closing a school, disabled or not — whether an
action is destructive is a property of the action, not of the week.

#### A project-wide mobile bug found through this, and the check that cannot see it

The panel was 404px wide inside a 374px parent on a 390px phone, and its button ran off the right edge.
**It was not only mine: every `.fm-panel` is `content-box` with 14px of padding, so every panel on
every page overflows by ~30px on a phone.**

`html { overflow-x: hidden }` clips it, so `document.documentElement.scrollWidth > window.innerWidth`
returns **false** — the standard mobile smoke test reports a clean page while a control is genuinely
unreachable. The school panel sets `box-sizing: border-box` on itself because a button you cannot tap
is not cosmetic; **the rest are still broken** and are filed as **S8.3a** with a note that the
`scrollWidth` check must be replaced by one that measures a known control against the viewport.

**The cascade lesson, for the second time.** `@import` hoists imported sheets to the top of
`dashboard.css`, so its own ~2,600 rules land *after* `overrides.css` and win on source order. A mobile
`grid-template-columns` in `overrides.css` silently lost to
`.fm-medical-stat-grid { repeat(auto-fit, …) }` and needed `!important` — the same remedy the
responsive utilities already use. **Overrides is not last. Nothing is, except `!important`.**

Also fixed en route: `formatBudget` was used in the panel but never added to the feature's dependency
list, so the academy page threw a `ReferenceError` and the router rendered a generic "API Error" card.
Worth noting as a pattern — a missing dep in a `create*Feature(deps)` call fails as an opaque API error
rather than anything pointing at the real cause.

**608 tests, unchanged** — this was presentation. Verified in Chrome at 1280×900 and 390×844: no
desktop change, panel fits on mobile, two price columns, button fully tappable.

---

### Position at intake, and a registration window instead of "any time you like" (owner, 2026-09-27)

Two questions in one conversation, answered together because they touch the same buttons and columns.

**A prospect now has a position from the day he signs.** It used to be rolled at *promotion* — you paid
for a school, signed a fifteen-year-old with no position listed anywhere, and got a goalkeeper out of the
academy. A prospect is signed for a position; that is the entire basis of a scouting decision. The
**distribution is unchanged on purpose** (same 12% goalkeepers, same three-way split) because the owner
ruled graduation must not move. Only the moment a position becomes *knowable* changes. Six legacy rows
repaired on boot: *"Assigned an intake position to 6 pre-existing academy juniors."*

**Decisions are now a registration window: weeks 1–2.** Promoting a youth player is a registration
decision, not a match-day one — real football submits squad lists at the start of a season, and nobody
signs a seventeen-year-old in week nine because he had a good month. Before this the window ran from the
start of the following season to the end of it, which is "always available" in all but name. Two weeks
rather than one rigid week, so logging in slightly late does not cost a prospect a whole season.

**Decision (a): the age ceiling overrides the window.** A junior who reaches 20 is promoted in whatever
week that falls — a twenty-one-year-old in an academy is a squad player described as a prospect. That
path holds no decision, so there is nothing for a window to protect against. It is pinned by a test that
fires it in **week 7**, precisely so nobody "fixes" it back into the window later.

**The age model needed no change and already does what the owner asked for.** Ages move once a year at
the season boundary and a junior keeps the age he has, so a prospect promoted in week 1 plays that whole
season at the age he exited with. The week-1/2 window is what makes that true: promote him in week 9 and
he is the same age, but the academy held him back a season for no sporting reason.

**School closure is a third path, deliberately outside the window.** Closing in week 12 graduates the
whole intake and has always bypassed the lock, or a school opened in week 1 could never close. Now
explicit in the code and the docs rather than an accident of implementation order.

**A UI detail that matters more than it looks.** A junior who is inside the window but too new to decide
on, and one who is outside the window entirely, are different situations. The table now says **"Too
new"** and **"Window closed"** respectively instead of showing the same empty cell, and the hero states
the window in words: *"Decisions open in weeks 1-2 only. It is week 7. A prospect keeps developing until
then, and still graduates at 20 whether you are ready or not."*

**A flaky fixture of my own, caught by the full run and not by isolation.** `carryover()` generated a
fresh intake on every call, so a test wanting three prospects intermittently hit the ten-junior cap and
found an empty pool — it passed alone and failed in a full run, because `rollIntakeCount()` is random. It
now draws from the existing pool before generating more, and says so when the academy is genuinely full.
Worth noting the shape of it: **a fixture that depends on a random count is a fixture that will pass
locally and fail in CI**, which is the opposite of what a test is for.

**Tests: 8 new** in `JuniorDecisionWindowTest` — the window is weeks 1–2 and nothing else, week 1 and
week 2 both allowed, week 3 refused with the junior left untouched, the refusal naming both the window
and the actual week, the age ceiling firing outside the window, position known at intake, promotion
keeping it, and the legacy repair. **Full suite 616.**

---

### Academy quality, and the two bugs the owner found by looking at it

**Both inputs already existed and were read by nothing.** `Stadium.youthLevel` was added in S4.4, whose
item 5 said outright it was "consumed in Sprint 5"; `YOUTH_COACH.development` is the half of S4.3
deferred here. A manager could see nothing, change nothing, and get identical prospects from a
Portakabin and a purpose-built academy.

**The multiplier sits on the development rate, never the intake roll.** That is the design decision
that keeps this a feature rather than a balance change. Scaling the roll would move the graduation
distribution the owner ruled must not move, and would mean a good academy *finds better raw material* —
a different game. A good academy develops what it has faster, which is what one does in real football.
Graduation **mechanics** are untouched; what changes is the level a graduate reaches.

`1.0 ± 0.15 per input`, bounded 0.70–1.30, with **each input worth at most ±15% on its own** so neither
rescues the other — a superb coach in a Portakabin is not a good academy. Same reasoning as the scouting
reach model, and the two are deliberately interchangeable so neither is secretly the important one.

**`YOUTH` was not a purchasable facility.** `Facility` held GROUND, GYM and TACTICAL only, so
`youthLevel` could be read but never *changed* — half the model was unreachable in play and a manager
could improve their academy only by hiring a better coach. It is now a fourth facility with the same
cost curve, upkeep and upgrade path, and it appears in `levels()` and the weekly total. The
controller's *"Use GROUND, GYM or TACTICAL"* message is now built from the enum, so a new facility
cannot be added without appearing there — a hardcoded list of valid values is a list that goes stale.

---

### Two owner-reported bugs, and they were the same shape

**A talent band could exceed the scale.** *Guliver Simić, 15, MID, 3.00 – 11.00* — on a **1–10** scale,
because `bounds()` was plain `talent ± width` with no clamp. Now clamped to `[1, 10]`, and
`TalentRange` owns the scale rather than assuming one.

The clamp costs something real and is worth saying plainly: **a band that ends at 10 tells you the
prospect is exceptional**, so a generational talent gives himself away at the edges of the scale. It is
the trade every scouting report makes, and the alternative — impossible numbers on screen — is worse
and would be reported as a bug. Verified live: the same junior now reads `3.0 - 10.0`.

**The promotion reveal showed no talent.** The screen is called *Junior Promotion Reveal* and displayed
a skill budget. The rule had been implemented on `JuniorAcademyItemDTO.talentExact` — but the reveal
screen reads **`JuniorPromotionResultDTO`, a different DTO with no talent field at all**, so the
feature existed and could not appear on the one screen named after it.

That is now the third time in this sprint the same failure has appeared: the rule is implemented, and
implemented *on a path nobody looks at*. `PlusFeatureService` (wired to nothing), the coaching staff
whose wage left the account and whose teaching did nothing, and now this. **When a rule is about what a
manager sees, the DTO that screen actually reads is the thing to check** — not the one where the rule
seemed to belong.

It is now present and gated on the same subscription as the band, with a non-subscriber still getting
the player and the skills. The subscription gates *information*, not the club's own promotion.

**"Exact talent with decimals" is an integer, and that is the right answer.** The owner asked for
decimals; `rollTalent` is a weighted **integer** on a 1–10 scale, and re-rolling it as a double would
move the graduation distribution, which the same owner ruled must not move. `revealExact` returns the
integer and the javadoc says why — the honest reading of two decisions that appear to conflict.

#### A flaky fixture of mine, twice, and the actual cause the second time

`carryover()` failed intermittently again, and the first fix was wrong in an instructive way. I assumed
the ten-junior cap was the problem and made it draw from the existing pool. It still failed, because
**`generateSeasonIntakeForWeek2` is idempotent per (team, season, week)** — it skips when that season
and week already produced an intake, so "generate another intake" silently did nothing and the helper
sat with an empty pool and a cheerful assertion failure. The fix is a **second club**, which has its own
ten slots and its own season/week, making the helper deterministic whatever the random intake size.

Confirmed: **three consecutive clean runs** of the class after the change. A fixture that depends on a
random count is a fixture that passes locally and fails in CI, and if the obvious fix does not work the
second time, the cause is not the one you assumed.

**Tests: 12 new** (9 `AcademyQualityTest`, 3 in `TalentRangeTest` and `JuniorDecisionWindowTest` for
the clamp, the reveal and the subscription gate). **Full suite 630.**
