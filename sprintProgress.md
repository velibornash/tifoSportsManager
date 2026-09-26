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

