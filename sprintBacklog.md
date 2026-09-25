# TIFO Football Manager — Sprint Backlog

**Created:** 2026-09-26
**Companion document:** `expertAudit.md`
**Scope:** UI Football (the SPA at `/dashboard.html` + backend `newLogic/` + `commonmanager/`)

---

## How to read this

| Field | Meaning |
|---|---|
| **Effort** | Engineering days of focused work. Not calendar. |
| **Blocks** | What cannot start until this lands. |
| **Verify** | How you know it is done. Prefer a command or a number over "it looks right". |
| **Type** | `FIX` (correctness) · `FEATURE` (new capability) · `DELETE` (removal) · `INFRA` (build/ops) |

### Standing constraints

- **The proposal engine (`newLogic/sim/`) is the only engine.** Do not add a second one.
- **Every calibration change must be measured** with `ProposalSeasonDiag` / `ProposalBatchDiag` over **≥ 50 matches** before it is accepted. Single-match observations are noise. The existing 200-match baseline in `PROPOSAL_SEASON_REPORT.md` is the reference to beat.
- **Every Sprint 0 fix gets a regression test.** That is the whole point of Sprint 0.
- **Do not touch `GoalkeeperEngine`.** It is correct and it is the most-tested component in the repo. If goals are too high, fix `ExecutionQuality`, not the keeper.
- **Manual season flow is intentional for now** (`Advance Week` / `Simulate All Results` are human-clicked while we are the only player). Do not add schedulers until Sprint 8.6.
- **Nothing goes in `demo/` or the dead engines.** If a change is needed there, it belongs in `newLogic/sim/`.

---

## Sprint dependencies

```
Sprint 0  Fix the exploits            ──┐
Sprint 1  Engine calibration          ──┼── must land before any balance work
Sprint 2  The economy                 ──┘   (economy depends on Sprint 1's
Sprint 3  Contracts & transfers            honest per-match numbers for
Sprint 4  Training v2                       broadcast/prize income)
Sprint 5  Juniors v2
Sprint 6  Delete the dead code        ✅ DONE (engines); stubs + docs remain
Sprint 7  AI-vs-AI verification       ── reduced to a regression test, depends on S1
Sprint 8  Presentation + deployment   ── last
```

**Suggested order:** S0 → S1 → S2 → S4 (you flagged training as critical) → S3 → S5 → S7 → S8. S6 docs are cheap and should be done right after S1 so `AGENTS.md` stops describing deleted engines.

---

# Sprint 0 — Stop the bleeding

**Effort:** 3–4 days · **Type:** `FIX` · **Blocks:** everything

Seven small, high-impact defects. Every one of these is a correctness bug, not a design gap. Do them first because each is cheap and each one currently corrupts a live system.

---

### S0.1 — Close the €1 transfer exploit

**File:** `newLogic/service/TransferService.java`

The bug: `normalizePrice:421-424` returns `Math.max(1.0, resolved)` with no check against `askingPrice`, and `buyListedPlayer:181-186` passes the client-supplied price straight into `completeTransfer`.

| # | Task | File:line |
|---|---|---|
| 1 | `buyListedPlayer` must reject `price < transfer.askingPrice` with a 422 `PRICE_BELOW_ASKING` | `TransferService.java:181-186` |
| 2 | Remove `normalizePrice` as a silent fallback. If the client omits a price, use `askingPrice`; if the client sends a price, **validate it** | `TransferService.java:421-424` |
| 3 | `direct-buy:189-221` — a listed player currently completes with **no acceptance check** (`:209-214`). Route it through the same validation as `buyListedPlayer` | `TransferService.java:209-214` |
| 4 | `completeTransfer:320-356` is the last line of defence — add the `price >= askingPrice` guard there too, so no future caller can bypass it | `TransferService.java:320` |
| 5 | Client: replace `window.prompt` (`club-management.js:267-275,464`; `player-view.js:169`) with a proper numeric input pre-filled to the asking price, disabled if the user tries to go lower | `club-management.js:181-191` |

**Verify:** attempt to buy a listed player at €1 → 422, listing unchanged, no money moves.
**Test:** `TransferServiceTest#buyListedPlayerRejectsPriceBelowAsking`, `#directBuyListedRequiresSellerAcceptance`, `#completeTransferRejectsUnderAsking`

---

### S0.2 — Fix the transfer-list soft-lock

**File:** `newLogic/service/TransferService.java`

The bug: a bare-club-name "Register interest" (`:159`) is not an "offer" (`isOfferEntry` requires `" offered "`), so `canRejectOffer` is false (`:778`), but `removalAllowed` is also false (`:776`). The player can never be delisted. The UI shows a permanently disabled button (`player-view.js:164-166`).

| # | Task | File:line |
|---|---|---|
| 1 | `removalAllowed` should only be blocked by **priced offers** the seller is able to act on, not by bare interest entries | `TransferService.java:776` |
| 2 | Add `POST /transfers/{playerId}/withdraw-interest` so an interested club can withdraw cleanly (AI clubs need this too) | new |
| 3 | Give the seller an explicit "reject all interest" path that does not require `canAcceptOffer` | `TransferService.java:248-269` |
| 4 | Add an admin override to force-unlist a player | new |

**Verify:** register interest from club B → owner can still delist from club A.
**Test:** `TransferServiceTest#removalAllowedWithForeignBareInterest`, `#forceUnlistAdmin`

---

### S0.3 — Idempotency guard on weekly training

**File:** `newLogic/service/TrainingProgressionService.java:68-130`

The bug: no guard. `TrainingWeekReport` is found-or-new and overwritten (`:116-128`), but `applyWeeklyGrowth` runs unconditionally on every call. The "Run Weekly Training" button calls it directly.

| # | Task | File:line |
|---|---|---|
| 1 | Add a `hasReport(teamId, season, week)` check. If a report already exists, return 409 `TRAINING_ALREADY_RUN` with the existing report id | `TrainingProgressionService.java:68` |
| 2 | `AdvanceWeekAsyncService:78-82` keeps calling it — the guard makes the button the only risk, so also disable the button server-side after success | `AdvanceWeekAsyncService.java:81` |
| 3 | Add a `force` flag restricted to admin for legitimate re-runs | new |

**Verify:** click "Run Weekly Training" 5× in one week → exactly one growth application.
**Test:** `TrainingProgressionServiceTest#runWeeklyTrainingIsIdempotentWithinWeek`, `#adminForceRerun`

---

### S0.4 — Feed `*Exact` into the engine (make training matter)

**File:** `newLogic/model/Skills.java:118-126,137-144`

The bug: the `*Exact` doubles exist because weekly growth is fractional (0.05–0.6 pts). But `getRatingScore()`, `getTotalForRating()` and **every** fluent accessor return the floor'd `int`. A player at 13.9 technique is identical to one at 13.0 in the match engine, so ~90% of all training work has zero effect.

| # | Task | File:line |
|---|---|---|
| 1 | Convert the fluent accessors to return the `*Exact` value | `Skills.java:137-144` |
| 2 | `getRatingScore()` / `getTotalForRating()` to use `*Exact` | `Skills.java:118-133` |
| 3 | Re-measure engine calibration. This **will** shift every number in `expertAudit.md` §5 — the delta is the size of the discarded fraction (~0.4 skill points average) | — |
| 4 | Add a `visibleInt()` accessor for the UI so the frontend keeps rendering integers | new |

> ⚠️ **This is the single highest value-per-line change in the whole backlog.** It is one edit that makes the entire training system start working. But it perturbs the engine, so do it *before* Sprint 1 calibration and re-baseline.

**Verify:** train a player for 4 weeks → their `*Exact` rises monotonically and their `*Exact`-derived rating rises with it; `int` display value rises at the expected floor boundaries.
**Test:** `SkillsTest#accessorsReturnExactNotFloored`, `#ratingRespondsToSubIntegerProgress`

---

### S0.5 — Passive fatigue recovery

**File:** `newLogic/service/SeasonService.java:341-358` + `newLogic/service/TeamMedicalService.java:39-72`

The bug: fatigue is written in 4 places and recovered in exactly 1 (the manual button). No weekly passive decay anywhere. The UI at `medical-view.js:95` claims "Weekly passive healing still applies" — **false**.

| # | Task | File:line |
|---|---|---|
| 1 | Add weekly passive fatigue decay in `decrementInjuriesByWeek` (or a sibling `recoverFatigueWeekly`) | `SeasonService.java:341-358` |
| 2 | Tune so a starter who plays every week ends the season meaningfully fatigued, a fringe player recovers fully, and a 35-year-old recovers slower than a 22-year-old | — |
| 3 | Add a medical-staff quality factor to `applyRecovery` (use `Team.juniorCoachSkill` as a placeholder until Sprint 4 ships real staff) | `TeamMedicalService.java:39-72` |
| 4 | Cooldown / daily limit on the recovery button | `TeamMedicalService.java:39` |
| 5 | **Fix the false claim in `medical-view.js:95`** | `static/js/pages/views/medical-view.js:95` |
| 6 | Also apply decay to `Player.injured` desync — `setInjured(false)` on day 0 | `SeasonService.java:341-358` |

**Verify:** play a full season without touching the medical button → end-of-season squad fatigue in a realistic band (target: starters 45–70, fringe players 0–20), and injury frequency stays near the 0.2–0.4/match band.
**Test:** `FatigueRecoveryTest#passiveDecayScalesWithAgeAndMinutes`, `#medicalButtonRespectsCooldown`

---

### S0.6 — Delete the dead offside / rules paths

**Files:** `newLogic/sim/rules/OffsideService.java`, `newLogic/sim/engine/MatchOrchestrator.java`, `newLogic/sim/engine/decision/CleanDecisionEngine.java`

Three documented-but-inert code paths. Small cleanup, but each one is a trap for the next person.

| # | Task | File:line |
|---|---|---|
| 1 | The pass-moment offside block is unreachable — `checkOffside` never returns `confirmed=true`. Either make `confirmOffside()`'s return value flow to the caller, or delete the block | `OffsideService.java:121,186,196,199`; `MatchOrchestrator.java:204-216` |
| 2 | `FootballRules` is instantiated and never read. **Delete the field and the class** — offside lives entirely in `OffsideService` | `MatchOrchestrator.java:48,80`; `sim/rules/FootballRules.java` |
| 3 | `nearestOpponentBeatsHimToIt` is written but never called, yet documented as an active fix in 3 MD files. Wire it in or delete it — and fix the docs | `CleanDecisionEngine.java:689-703` |
| 4 | `isPathBlocked()` always returns `false`, making the carry "+12 clear path" bonus unconditional. Implement or remove the bonus | `CleanDecisionEngine.java:862-865` |
| 5 | `tackles` stat is a mirrored copy of `duelsWon` (`598.5 = 598.5` in diagnostics). Label it honestly or compute it properly | `ProposalStatsCollector.java:209-214` |

**Verify:** `ProposalBatchDiag` output has no field that equals another field. Grep for the deleted symbols returns nothing.
**Test:** extend `ProposalStatsCollectorTest` to assert `tackles != duelsWon`.

---

### S0.7 — Security and endpoint hygiene

**Files:** `newLogic/config/SecurityConfig.java`, `newLogic/controller/PlayerController.java`, `newLogic/controller/TeamController.java`, `newLogic/controller/LineupController.java`

| # | Task | File:line | Type |
|---|---|---|---|
| 1 | Move `/api/v2/match/**`, `/api/sim/replay/**`, `/api/zox/**` behind JWT. The proposal viewer must send `Authorization` (it already imports `authFetch` — use it) | `SecurityConfig.java:86`; `static/demo/service/ui/proposal/js/viewer.js:956-957` | `FIX` |
| 2 | Validate `sortBy` against a whitelist in `getPaged` — currently an unvalidated field name goes into `Sort.by()` | `PlayerController.java:48` | `FIX` |
| 3 | Add validation + authorisation to `TeamController.create:100` and `PlayerController.create:43` (raw entity save, no checks) | — | `FIX` |
| 4 | Replace raw `RuntimeException` in `LineupController` with `ApiException` so it returns 400 not 500 | `LineupController.java:35,41` | `FIX` |
| 5 | Implement `POST /auth/register`, or delete `register.html` + `js/register.js` | `static/js/register.js:40` | `FIX` |
| 6 | Implement `POST /admin/registration-requests/{id}/{action}`, or hide the buttons | `static/js/pages/features/community.js:248` | `FIX` |
| 7 | Wire `club-management.js:24` to the real `/teams/{id}/profile` instead of `/demo/teams/{id}/profile` | `club-management.js:24` | `FIX` |
| 8 | Wire `staff-directory.js:79` and `fixture-view.js:17,225` to the real endpoints (they currently 404 for any team ≠ 1) | — | `FIX` |
| 9 | Fix `fetchPlayerRatingSummary` call sites (1 arg instead of 2) so player average rating is not permanently `—` | `utils.js:362`; `player-view.js:567`; `league-view.js:425` | `FIX` |
| 10 | Fix `login.js:4-34` — `#loginStatus` does not exist in any page, so the error-diagnostics feature is invisible | `static/js/login.js:4-34` | `FIX` |

**Verify:** every `fetch`/`authFetch` URL in `static/js` resolves to a real controller route. Write a script that extracts them and check against the Spring mappings.
**Test:** add `SecurityConfigTest` asserting `/api/zox/**` returns 401 without a token.

---

### Sprint 0 exit criteria

- [ ] No free-skill-point exploit
- [ ] No free-player exploit
- [ ] No delisting soft-lock
- [ ] Training progress is visible to the match engine
- [ ] Fatigue has a passive decay curve
- [ ] All SPA endpoints resolve to real routes
- [ ] `/api/**` requires JWT
- [ ] **+14 regression tests** (target: 35% test/main ratio on the touched services)

---

# Sprint 1 — Engine calibration

**Effort:** 16–20 days · **Type:** `FIX` + `FEATURE` · **Depends on:** S0.4 (perturbation)
**Revised 2026-09-26:** +3 tasks added — S1.6 (port the injury model, now orphaned by the quarantine), S1.7 (implement penalties, currently never taken), S1.8 (implement substitutions, currently impossible). These are correctness gaps, not polish, and the injury model is the only one in the codebase.
**Reference:** `PROPOSAL_SEASON_REPORT.md` and `expertAudit.md` §5

> **Method:** change one thing → run `ProposalBatchDiag 100` → record → decide. Never change two calibrations in one commit.

---

### S1.1 — Fix the goal inflation chain

**Root cause is three compounding factors, not one.** Work them in this order.

| # | Task | File:line | Detail |
|---|---|---|---|
| 1 | **On-target probability down.** `skillBase = 0.08 + skill × 0.020` → `0.06 + skill × 0.015`; close-range lift `+0.12` → `+0.08`. Cap stays 0.85 | `sim/engine/ExecutionQuality.java:133-138` | Target: SOT 44% → 33% |
| 2 | **Aim distribution cluster at the posts.** Currently uniform across `3.55–4.45` = 9 m of spread. Real SOT clusters in the two corners; the keeper covers the middle ~2–2.5 m. Sample the outer 40% of the mouth instead | `ExecutionQuality.java:133-138` | Target: on-target → goal 43% → 30% |
| 3 | **Narrow the goal mouth to 7.4 m.** `MOUTH_LEFT 3.5 → 3.63`, `MOUTH_RIGHT 4.5 → 4.37`. (Cell width is **10 m** — cols 1–6 over a 60 m pitch — so 0.74 col = 7.4 m vs the real 7.32 m. Today it is 10 m, i.e. 1.37×.) | `sim/model/GoalPhysical.java:12-13` | Do this **last** — 1 and 2 are worth more |
| 4 | **Correct `POST_RADIUS`.** `0.03` cells = 0.3 m against a real 6 cm post, and the comment says `0.42 m` which assumes the wrong cell width. Reduce to ~0.006 cells and fix the comment | `GoalPhysical.java:8` | Immaterial to physics, but it is wrong |
| 5 | Re-run the batch. **Target: 2.7–3.0 goals/match, 8–10 SOT, 3–5 saves** | — | Do not chase 2.7 exactly; 3.0 is fine |

**Verify:** `ProposalBatchDiag 100` → goals in [2.6, 3.1], SOT% in [30, 36], saves in [3, 6].
**Do not touch:** `GoalkeeperEngine`. If saves stay too high after 1–3, the problem is elsewhere.

---

### S1.2 — Fix the restart inversion (40 goal kicks / 19 throw-ins / 5 corners)

**Root cause:** `ActionExecutor.executeClear:353-361` launches clearances at `MAX_BALL_SPEED` (1.5 c/t) with `AIR_DECEL` 0.15, so flight distance = v²/2a = **7.5 cells regardless of the 2-cell aim point.** Every clearance leaves down an end line, converting throw-ins and corners into goal kicks.

| # | Task | File:line |
|---|---|---|
| 1 | Launch clearances at ~0.95 c/t (≈9 m/s) toward a 3-cell lane | `ActionExecutor.java:353-361` |
| 2 | Add a `desiredClearancePower` derived from the aim distance, not a fixed max | same |
| 3 | Re-measure. **Target: goal kicks 40.7 → 14, throw-ins 18.7 → 32, corners 5.0 → 9** | — |

**Verify:** `ProposalBatchDiag 100` → goal kicks ∈ [10, 18], throw-ins ∈ [26, 40], corners ∈ [7, 12].
**Test:** `ActionExecutorTest#clearanceStaysOnPitchForShortAims`

---

### S1.3 — Diagnose and fix the corner skew (0.6 HOME / 4.4 AWAY)

Everything upstream is symmetric — shot volume, SOT, interceptions, clearances, goal kicks, possession. **Only corners are 7:1.** That asymmetry should not exist and its cause is not yet understood.

| # | Task | File:line |
|---|---|---|
| 1 | **Diagnose first.** Add corner-side and corner-origin columns to `ProposalBatchDiag`. Do not guess | `sim/diagnostics` |
| 2 | Prime suspect A: `oobRestartType` decides by **row only**, with no column awareness — a ball out behind the left flank and one behind the right flank both become corners | `PitchEnvironment.java:53-64` |
| 3 | Prime suspect B: `GoalkeeperEngine.java:93` — `targetCol = ballCol + (goalCentreCol − ballCol) × t` with `goalCentreCol = 4.0`, interacting with the 9-row / 8-col mirror. The keeper's resting asymmetry may bias which end-line deflection he reaches first | `GoalkeeperEngine.java:93` |
| 4 | Fix whichever is confirmed. **Target: within 20% of symmetric** (e.g. 4.8 / 5.2) | — |

**Verify:** `ProposalBatchDiag 100` → HOME corners / AWAY corners ∈ [0.8, 1.25].

---

### S1.4 — Cut the duel count (598/match → ~120)

`DuelService.detectAndResolveDuels:48-83` loops all opponents × every carrier × every tick, and radii fire on **proximity alone** — no requirement that the defender be goal-side, facing the ball, or interposing.

| # | Task | File:line |
|---|---|---|
| 1 | Add an **interposition requirement**: a DRIBBLE duel only fires if the defender is inside the cone between the carrier and the carrier's nearest teammate | `DuelService.java:48-83` |
| 2 | Same for RECEIVE_PASS — the defender must be on the pass lane segment, not merely nearby (this was already done in the `demo/service` reference at `INTERCEPT_R = 0.14`; check whether the proposal port lost it) | `DuelService.java`; cf. `BallPhysicsEngine.java:54-67` |
| 3 | Make `DRIBBLE_DUEL_RADIUS` context-dependent rather than a flat 0.15 | `DuelEngine.java:20` |
| 4 | Cache the per-pair nearest-opponent lookup instead of recomputing all 11 opponents every tick | `DuelService.java:48-83` |
| 5 | **Target: 100–150 duels/match** | — |

**Verify:** `ProposalBatchDiag 100` → duels won ∈ [100, 150]. Also confirm possession % stays near 50/50 (duel count changes ball-winning rates).

---

### S1.5 — Add a bounded chase multiplier, then tighten the press radius

Currently `MovementEngine` has **no** sprint/chase bonus at all (deliberate owner rule, 2026-09-14), so a chaser can never be faster than the defender they are chasing. The workaround is `PRESS_DRIB_DUEL_RADIUS = 0.50` (7 m) — a 7 m tackle range, which is why pressing looks like wrestling.

| # | Task | File:line |
|---|---|---|
| 1 | Add `CHASE_SPRINT_MULTIPLIER = 1.15–1.25`, applied **only** to active chasers, scaled down by fatigue | `MovementEngine.java:45-49` |
| 2 | Reduce `PRESS_DRIB_DUEL_RADIUS` from 0.50 back to a realistic 0.20–0.25 (≈3 m) | `DuelEngine.java:20-26` |
| 3 | Verify the press still reliably ends in a duel (this was a prior regression — see `AGENTS.md` 2026-09-12 notes) | — |

**Verify:** pressing defender closes and tackles inside 3 m; `PressDuelDiagnostic` shows TYPE A presses ending in duels at a high rate.

---

### S1.6 — Port the injury model into the proposal engine

> ⚠️ **Added 2026-09-26.** The only injury generator in the codebase was `RealisticMatchEngine.maybeTriggerInjury:1410-1489`, which was quarantined to `footballForDelete/backend/newLogic/engine_v1/RealisticMatchEngine.java`. **The live proposal engine currently has no injury generator at all.** Sprints 1, 2 and 4 all assume injuries exist.

This is the best mechanic found in the whole audit. Port it:

| # | Task | Source |
|---|---|---|
| 1 | Port `maybeTriggerInjury` — minute window `8..88`, `chance = 0.00028 + max(0, fatigue − 18) × 0.00008`, `+0.00008` for WNG/ATT | `footballForDelete/.../RealisticMatchEngine.java:1410-1443` |
| 2 | Port `pickInjuryRiskPlayer` — weight selection by `1 + max(0, fatigue − 10) × 0.25` so tired players are both more likely to be chosen *and* more likely to be injured | same, `:1477-1489` |
| 3 | Port `applyInjury` — stamp season/week, add `+6` fatigue, set injury days | same, `:1445-1460` |
| 4 | Improve `rollInjuryDays`: replace the 3 hardcoded buckets (72% → 1-10d, 23% → 11-16d, 5% → 17-20d) with an **injury type** model (hamstring / ankle / knee / fracture / concussion) that maps to realistic day ranges and a recurrence chance | same, `:1462-1467` |
| 5 | Wire it into the proposal tick loop and emit an `INJURY` event so the viewer and stats see it | `sim/engine/MatchOrchestrator.java` |
| 6 | Fix `Player.injured` desync — `isInjured()` derives from `injuryDaysRemaining` and ignores the flag; `decrementInjuriesByWeek` never calls `setInjured(false)` | `SeasonService.java:341-358` |
| 7 | **Blocked by S1.7 (substitutions)** — an injured player must be replaceable, or injuries are just a number that reduces goals | `sim/backlog.md:489-492` |

**Verify:** over 50 matches, injury frequency lands in a realistic 0.3–0.6 per match per team, and it correlates with fatigue.

---

### S1.7 — Implement penalties (they are awarded but never taken)

> ⚠️ **Added 2026-09-26.** `DuelService.java:101-105` awards the penalty and increments a stat. `ActionLogService.java:50` declares the channels `PENALTY_KICK` / `PENALTY_SAVED` / `PENALTY_MISS` — and **no code in the repo produces them.** The award rate is already correct (`PENALTY_FROM_BOX_FOUL = 0.06` → 0.2/match vs a real 0.27). Only the execution is missing.

| # | Task |
|---|---|
| 1 | Penalty taker selection: highest `shooting + technique` on the attacking team, respecting the striker if they are on the pitch |
| 2 | Run-up + strike: reuse `ExecutionQuality.evaluateShot` with a fixed, very short target and no distance penalty |
| 3 | Goalkeeper dive: `GoalkeeperEngine` needs a penalty-specific branch — a keeper cannot cover a 10 m mouth from a 2 m run-up |
| 4 | Conversion model targeting ~76% (real: 75–78%). Keeper skill and taker skill both matter, plus a small angle factor |
| 5 | Emit `PENALTY_KICK` / `PENALTY_SAVED` / `PENALTY_MISS` / `PENALTY_SCORED`, with the taker credited with a goal + assist |
| 6 | VAR interaction already exists (`VARService:128-134`) — wire the outcome through |
| 7 | Miss → goal kick. Goal → restart, centre spot |

**Verify:** over 50 matches, ~0.2 penalties/match and ~76% conversion. No penalty awarded without a kick event following it.

> Sokker's famous bug is 80% missed penalties. This engine's bug is 100% un-taken penalties. Strictly worse, and much cheaper to fix.

---

### S1.8 — Implement substitutions (currently the biggest engine gap)

> ⚠️ **Added 2026-09-26.** `sim/model/Player.java:112` has `isUnavailable() { return sentOff || injured || substituted; }` and a `substituted` flag — **but nothing ever sets it.** `backlog.md:489-492` states it plainly: *"there is no bench/slot/substitution-limit contract exists"*.

Consequences today: the same 11 play 90 minutes regardless of fatigue, injuries or bookings. A red card means 10-vs-11 for the rest of the match with no recourse. **This is also why fatigue and injuries barely matter** — being tired is only a slightly slower player, never a lineup change.

| # | Task |
|---|---|
| 1 | `SubstitutionSlot` model: bench of 9, 5 substitutions allowed, 3 substitution windows (S1.8a below) |
| 2 | Pick the bench automatically from squad role, condition and tactics — or let the manager pre-select it (do both; auto is the default) |
| 3 | **Manual substitution** UI in the viewer: click a player, click a substitute |
| 4 | **Automatic substitution** on injury, on red card, and on fatigue threshold |
| 5 | **Tactical substitution** — change shape mid-match. This is the single biggest immersion win available |
| 6 | Substitution windows: 3 moments, max 5 players. (Optional: allow rolling substitutions if the squad depth is high — a nice modern rule) |
| 7 | A substituted player cannot return (unless you adopt rolling subs) |
| 8 | Feed the sub into stats: minutes played, and a rating that reflects entry minute |

**Verify:** a full match shows 0–5 substitutions depending on injuries/fatigue; a red card forces an emergency sub; the tactical view can change shape at half time.

---

### S1.9 — Fix the remaining calibration outliers

| Metric | Now | Target | Approach |
|---|---:|---:|---|
| Interceptions | 36.1 | 12–16 | `BallPhysicsEngine` intercept gate is `pm + def > 18` with `prob = min(0.45, (0.25 + (pm+def−18)/30) × speedFactor)`. Tighten the probability, keep the gate. Interception needs the S1.4 interposition work first |
| Red cards | 1.0 | 0.2 | `DisciplineService:68` `straightRed = rand < 0.004` with `foulProb` 0.16–0.22 → product ≈ 0.0008/duel × ~600 duels. **Will drop sharply once S1.4 cuts duels to ~120.** Re-measure before touching |
| Fouls | 29.6 | 22 | Same — re-measure after S1.4 |
| Nil-draws | 0 / 200 | ~6% | Should resolve once goals come down to 2.7–3.0. If not, check the finishing model for a floor |
| Through balls | 13.2 | 5–10 | `THRU_FREQUENCY_GATE = 0.032` → try 0.022. Note THRU is only reachable from the final-2-row hard rule (`CleanDecisionEngine.java:141`) — see S1.10 |
| Highest score | 10 | 7–8 | Should resolve with S1.1 |

**Rule: re-measure S1.9 items after S1.4 lands.** Most of them are downstream of the duel count.

---

### S1.10 — Persist replays

`SimReplayStore` is an unbounded in-memory `ConcurrentHashMap` (`SimReplayStore.java:17-29`). A restart loses every replay; `Match.replayId` survives in the DB as a dangling id.

| # | Task |
|---|---|
| 1 | Persist `SimReplayView` to disk (or a `replay_blob` table) on store |
| 2 | Load on read, fall back to in-memory |
| 3 | Bound the retention (keep last N matches per team, or TTL) |
| 4 | Null out `Match.replayId` if the blob is gone, so the UI can say "replay expired" instead of hanging |

---

### S1.11 — Wire THRU / CROSS / CENTER into the decision engine

Today `selectOptionWithPlaymaking` only ever sees `pass, carry, shot, clear` (`:126-127`). Deliveries enter **only** via the final-2-row hard rule (`:141`). So the tactical variety in the design docs does not exist in play.

| # | Task | File:line |
|---|---|---|
| 1 | Add THRU / CROSS / CENTER as first-class options in `selectOptionWithPlaymaking`, each with its own gate and scorer | `CleanDecisionEngine.java:126-127,886-891` |
| 2 | CROSS gate: carrier wide (`|col − 4.0| ≥ 2.0`) in the opponent half | — |
| 3 | CENTER gate: 2+ attackers inside the box | — |
| 4 | THRU gate: an attacker running behind the second-to-last defender line | — |
| 5 | Remove the special-casing at `:141` once deliveries are first-class | `CleanDecisionEngine.java:141` |

**Verify:** crosses and throughs appear across the whole pitch, not only in the final two rows. Cross count should rise toward 20–30/match.

---

### Sprint 1 exit criteria

| Metric | Baseline | Target |
|---|---:|---:|
| Goals | 5.4 | **2.6 – 3.1** |
| Shots on target | 12.9 | **8 – 10** |
| On-target % | 43.9% | **30 – 36%** |
| Saves | 9.8 | **3 – 6** |
| Duels won | 598.5 | **100 – 150** |
| Interceptions | 36.1 | **12 – 18** |
| Goal kicks | 40.7 | **10 – 18** |
| Throw-ins | 18.7 | **26 – 40** |
| Corners | 5.0 (7:1 skew) | **8 – 12, within 20% symmetric** |
| Fouls | 29.6 | **19 – 26** |
| Red cards | 1.0 | **0.1 – 0.4** |
| Pass accuracy | 77.9% | **75 – 82%** (keep — do not chase 85%) |
| Possession | 49.4 / 50.6 | **keep symmetric** |
| Nil-draws | 0 / 200 | **≥ 2% of matches** |

---

# Sprint 2 — The economy

**Effort:** 15–18 days · **Type:** `FEATURE` · **Depends on:** S1 (for honest broadcast/prize income)
**This is the sprint that turns the game into a manager game.**

> **Note:** much of this already exists as plain code in `footballtextmanager/CleanSheetService.java:427-434` (`applyRoundFinances`: wage bill `earnings × 0.58`, sponsor income `10000 + reputation × 180`, gate income) and `CSDataInitializer.java:144` (budgets seeded 500k–1M). **Port it, do not rewrite it.** The logic is proven; only the wiring is new.

---

### S2.1 — Seed a real world

Today every one of the 310 clubs has `budget = 0.0` and `reputation = 50.0` (`TeamFactory.java:56-58`).

| # | Task | File:line |
|---|---|---|
| 1 | Budget from league tier: Superliga €3–8M, Prva liga €400k–1.2M, Srpska €80k–250k, Okružna €20k–60k, Opštinska €3k–12k | `TeamFactory.java:56` |
| 2 | Reputation from league tier **plus** per-club variance (±12 within a tier), so attendance, playoff odds and offer acceptance are not flatlines | `TeamFactory.java:57` |
| 3 | Give `OFK Omladinac` a hand-tuned budget/reputation profile alongside `applyOmladinacTalentProfile` | `DatabaseInitializer.java:514` |
| 4 | **Fix the 44% team-name collision hazard.** `usedTeamIdsInLeague` is per-league, so a colliding name puts the same `Team` in two leagues and `addTeamToLeague:484` reassigns its competition. Make the dedupe global per seeding run | `DatabaseInitializer.java:416-481` |
| 5 | Normalise `Team.reputation` when a club is promoted/relegated | `SeasonService.java:407-480` |

**Verify:** every club has `budget > 0` and a distinct reputation. No team appears in two leagues.

---

### S2.2 — `FinanceLedgerEntry` entity + weekly settlement

| # | Task |
|---|---|
| 1 | New entity `FinanceLedgerEntry` (id, team, seasonYear, weekNumber, category enum, amount, note, createdAt) |
| 2 | `enum FinanceCategory` — `GATE_REVENUE`, `BROADCAST`, `PRIZE_MONEY`, `SPONSORSHIP`, `MERCHANDISING`, `WAGES`, `STAFF_WAGES`, `FACILITY_UPKEEP`, `TRANSFER_FEE_IN`, `TRANSFER_FEE_OUT`, `LOAN_IN`, `LOAN_OUT` |
| 3 | `WeeklyFinanceService.applyWeeklyFinances(team, seasonYear, week)` |
| 4 | Call it for **every club** in `advanceWeekAndHandleSeasonTransition`, not just the user. AI economies must run | `SeasonService.java:318-338` |
| 5 | Wrap in a transaction per team so one club's failure does not roll back the week |

**Income formulas (starting points, tune against real ratios):**
- **Gate:** `attendance × Stadium.ticketPrice`. Both already seeded. `AttendanceService` already estimates attendance from capacity × reputation — wire ticket price into it.
- **Broadcast:** `leagueReputationWeight × basePerClub`. `Competition.reputationWeight` is already seeded (`tier × 20`) but **never read** — free hook.
- **Prize money:** per final position, e.g. `[Champion 45%, 2nd 28%, playoff winner 20%, 3rd 16%, … 10th 5%] × base`. `buildPlayoffSummary:585-658` already computes final positions.
- **Sponsorship:** see S2.3.
- **Merchandising:** `f(budget, reputation, squad size)`.

**Expense formulas:**
- **Wages: `Player.earnings`.** Already seeded with realistic values (`PlayerFactory.java:41`) and **read by nothing**. This is the single most important line in the sprint.
- **Staff wages:** see S2.3.
- **Facility upkeep:** `Stadium.capacity × upkeepFactor` + training ground (Sprint 4).

**Verify:** after 20 simulated weeks, every club's ledger is non-trivial and no club is bankrupt. Sum of all transfers = 0 across the pyramid.

---

### S2.3 — Sponsors and staff as real entities

There is **no `Staff` / `Coach` / `Sponsor` JPA entity anywhere in the codebase.** The staff directory is hardcoded in the browser (`static/js/pages/features/staff-directory.js:12-51`) and coaching has **zero** simulation effect.

| # | Task | Notes |
|---|---|---|
| 1 | `Sponsor` entity (name, tier, annualValue, contractYears, performanceBonusClause, startSeason, endSeason) | 3 tiers: `TITLE` (only Superliga), `MAJOR`, `MINOR` |
| 2 | `StaffMember` entity (team, role, name, age, contractEnd, wage, attributes) | Roles: `HEAD_COACH`, `ASSISTANT`, `GK_COACH`, `PHYSIO`, `SCOUT`, `YOUTH_COACH` |
| 3 | Staff attributes: `discipline`, `motivation`, `tactical`, `goalkeeping`, `fitness`, `development`, `scouting` (1–20) | Later consumed by Sprint 4 (training growth) and Sprint 5 (scouting) |
| 4 | Seed 3–7 staff per club from `Team.reputation` and league tier | Replaces `DummyDataController:28-35` |
| 5 | Seed 1–3 sponsors per club from `reputation` and league tier | |
| 6 | `StaffDirectoryController` — real endpoints replacing `/demo/teams/{id}/coaches` | |
| 7 | Staff contract expiry → vacancy the user must fill | |
| 8 | `Team.juniorCoachSkill` → replaced by the `YOUTH_COACH` staff member's `development` attribute | `YouthAcademyService.java:369-374` |

**Verify:** the Staff page shows real data for every club, coaching quality varies, and staff wages appear in the ledger.

---

### S2.4 — Board expectations and manager trust

This is what creates stakes. Without it there is no pressure loop.

| # | Task | Notes |
|---|---|---|
| 1 | `BoardExpectation` entity (team, seasonYear, targetPosition, spendCeiling, wageCeiling, trust 0–100) | |
| 2 | `BoardService.setExpectations(team, season)` — called at season start | Default: target = current position, ceiling = last year's spend ± 15% |
| 3 | `BoardService.evaluateAfterRound(...)` — called after every round | Miss target → trust drops. Beat it → trust rises |
| 4 | **Sackable:** trust below threshold for N consecutive rounds → sacking screen → replacement manager (attributes derived from the club) | |
| 5 | **Budget approval:** the board grants a spend ceiling. Exceed it and trust drops sharply | |
| 6 | Player-facing UI: an objectives board on the dashboard — target position, wage budget, transfer budget, current trust | |
| 7 | End-of-season review: pass = bonus, fail = retained on a reduced budget | |

> `footballtextmanager/model/CSClubMood.java:13-17` already models `boardConfidence` / `fanMood` / `mediaPressure` / `financialHealth` as plain POJOs. Promote those to entities.

**Verify:** a deliberately bad season triggers the sacking flow. A good season raises the ceiling.

---

### S2.5 — Wage bill, FFP-lite, and the transfer budget

| # | Task |
|---|---|
| 1 | Squad wage total = `Σ Player.earnings`. Display it, and enforce the board's `wageCeiling` |
| 2 | **FFP-lite:** `wageBill / weeklyIncome` ratio. Above ~1.15 → trust penalty. Make it visible to the player so it is a *decision*, not a gotcha |
| 3 | Transfer budget = a **board-granted** amount, not a client-side formula. Delete `club-management.js:41` |
| 4 | Players become unhappy if their wage is far below their `playerValue` — feeds Sprint 2.6 |
| 5 | Contract renewals with wage demands (see S3.1) |
| 6 | Delete the entire client-side finance fiction (`club-management.js:21-66`) and the `/demo/teams/{id}/profile` dependency |

**Verify:** a club cannot sign a player it cannot afford, and the wage bill is the primary constraint, exactly as in FM.

---

### S2.6 — Dressing room and morale (make `form` mean something)

`Player.form` is a creation-time constant. `MoraleSystem.getConfidenceModifier()` has **zero callers**.

| # | Task | File:line |
|---|---|---|
| 1 | Wire `getConfidenceModifier()` into the proposal engine's shot and pass execution | `newLogic/engine/MoraleSystem.java:30` |
| 2 | `Player.form` responds to: minutes played, goals, assists, match rating, team results, wage vs value, transfer listings | `Player.java:29` |
| 3 | Delete the `PlayerConditionService` dead path or wire it (40 lines, 0 callers) | `PlayerConditionService.java` |
| 4 | Morale events: playing time, being dumped from the XI, being listed, a new signing, a bad run, board backing, wage disputes | |
| 5 | Unhappy players request transfers, refuse to renew, or agitate in the dressing room | |
| 6 | **Feed morale and form into training growth** (Sprint 4) — a happy player develops faster | |
| 7 | Fix the constant-offset artefacts: `PlayerDTO:90` `formBoost` and `MatchRatingCalculator:51` `(form − 6)×1.2` become live inputs | |

**Verify:** a player's OVR and match rating move with form, and morale visibly affects performance.

---

### Sprint 2 exit criteria

- [ ] Every club has budget, reputation, staff, sponsors
- [ ] A weekly ledger runs for all 310 clubs
- [ ] `Player.earnings` drives a real wage bill
- [ ] The Finances page reads real data and nothing else
- [ ] Board expectations, trust, and a sacking flow exist
- [ ] Morale and form are live and reach the engine
- [ ] No client-side finance fiction remains in the codebase

---

# Sprint 3 — Contracts and transfers v2

**Effort:** 12–15 days · **Type:** `FEATURE` · **Depends on:** S2

Target: Sokker-parity on the transfer market. Currently at ~15%.

---

### S3.1 — `PlayerContract` entity

| # | Task | Notes |
|---|---|---|
| 1 | Entity: player, team, weeklyWage, lengthMonths, signedSeason, expirySeason, releaseClause, squadRole, squadNumber, onLoanFrom | `squadRole`: `STAR` / `STARTER` / `ROTATION` / `PROSPECT` / `YOUTH` |
| 2 | Backfill every existing player with a 2–4 year contract at their current `earnings` | Migration script |
| 3 | Contract expiry → **free agent**. This unlocks the free-agent market that is currently impossible by construction (`requirePlayerTeam:402-407` throws 409) | |
| 4 | Contract renewal flow with a **wage demand** derived from `playerValue`, form, age, and squad role | |
| 5 | Refuse to renew → player leaves on a free, or asks for a transfer | |
| 6 | Release / mutual termination | |
| 7 | Squad registration rules (max 25 senior + 8 youth) | |

---

### S3.2 — Real negotiation, replacing the prose-string model

Today: offers are `Set<String>` of `"Partizan offered €450000"` (`Transfer.java:34`, `TransferService.java:566`), parsed with `indexOf(" offered €")` + `replaceAll`. A prefix-match dedupe at `:562-565` means a club named `Partizan` wipes offers from `Partizan United Youth`.

| # | Task |
|---|---|
| 1 | New entity `TransferOffer` (transfer, buyerTeam, fee, wage, contractYears, status, createdAt, expiresAt) — **delete `interestedTeams`** |
| 2 | Multi-round negotiation: initial offer → seller counters → buyer accepts/rejects/counters. 3–5 rounds max |
| 3 | Fee + wage + contract length as **three separate** negotiated terms |
| 4 | Player personal terms: a player with a high wage demand can refuse a move that meets the fee but not the wage |
| 5 | Agent fee (2–5% of fee) |
| 6 | Instalments / deferred fees for large deals | |
| 7 | Sell-on clauses |
| 8 | `acceptBestOffer` must let the **seller choose** which offer to accept, and reject individual offers without clearing the rest | `TransferService.java:229-245,579-597` |
| 9 | Offer expiry + transfer-window enforcement | |
| 10 | Delete `parseOfferDetails` / `isOfferEntry` / `replaceInterestFromClub` | `TransferService.java:641-664,566` |

**Verify:** two clubs bidding for the same player produce a visible negotiation thread with counters. Renaming a club no longer orphans offers.

---

### S3.3 — Transfer windows

| # | Task |
|---|---|
| 1 | Summer window (weeks 1–6) and winter window (weeks 10–12). Emergency exceptions for free agents and loan recalls |
| 2 | Window deadline countdown in the transfer centre |
| 3 | Deadline-day scramble (loans, free transfers) — a nice Sokker-flavoured event |
| 4 | Out-of-window attempts are rejected with a clear reason, not a silent 500 |

---

### S3.4 — Loans

| # | Task |
|---|---|
| 1 | Loan with optional buy clause and optional wage contribution |
| 2 | Loan players count as squad depth but not first-team registrations |
| 3 | AI clubs accept and make loan offers |
| 4 | **Loan out academy prospects** — the standard development pathway, and the answer to the 10-junior cap (Sprint 5) |

---

### S3.5 — Work permits and foreign-player limits

A strong Serbia-specific constraint: the domestic league has a **non-EU player quota** (Serbia's rule is 4 in the top flight, fewer below). Each foreign player needs a work permit.

| # | Task |
|---|---|
| 1 | `WorkPermit` per foreign player (granted, refused, or pending based on reputation and league level) |
| 2 | Non-EU quota per competition (`Competition.foreignPlayerLimit`) |
| 3 | A signing is blocked (or the permit is pending) when the quota is full |
| 4 | Buying from a weaker league grants a permit more easily — a real strategic axis |

---

### S3.6 — AI demand model and a live market

Today: `maybeCreateIncomingOffer:465-469` **only ever targets human players** — AI↔AI transfers never happen. The buyer is `randomItem(candidateBuyers)` — uniform random, no needs model. Listings are 42%/week at `value × (0.88…1.12)`.

| # | Task |
|---|---|
| 1 | `ClubNeed` model: each AI club evaluates squad gaps by position and quality tier |
| 2 | Buyer selection weighted by need × budget tier × ambition |
| 3 | **Enable AI↔AI transfers** — this is what makes the market feel alive |
| 4 | Price realism: AI valuations should account for age, contract length, form, and squad depth, not just `playerValue` |
| 5 | Negotiated AI offers (fee + wage + length) using the S3.2 engine, not a single dice roll |
| 6 | AI clubs renew expiring contracts and reject players — the market should have supply and demand pressure |
| 7 | Unlisted players must be **scoutable** — currently `getAllTransfers:71-76` filters `status == LISTED`, so a rival's unlisted squad is invisible | |
| 8 | Transfer history per player | |
| 9 | Squad-size limits on purchasing | |

**Verify:** over 20 simulated weeks, AI clubs trade players with each other, squads improve, and academy graduates get bought.

---

### Sprint 3 exit criteria

- [ ] Every player has a contract with a wage
- [ ] Contracts expire → free agents exist
- [ ] Negotiation is multi-round with fee + wage + length
- [ ] `interestedTeams` prose encoding is gone
- [ ] Transfer windows are enforced
- [ ] Loans work both ways
- [ ] Work permits and the non-EU quota block signings
- [ ] AI↔AI transfers happen; squads visibly evolve
- [ ] The seller chooses which offer to accept

---

# Sprint 4 — Training v2

**Effort:** 12–15 days · **Type:** `FEATURE` · **Depends on:** S2.3 (staff) + S2.6 (morale)
**You flagged training as critical. This is that sprint.**

> Today's design space is **two knobs**: four role-bucket skill dropdowns, and a 10-slot Advanced dropzone. Everything below expands it without breaking what works.

---

### S4.1 — Individual training focus (the headline feature)

| # | Task | File:line |
|---|---|---|
| 1 | Per-player, per-week **individual focus**: pick 1–2 specific skills | new |
| 2 | Bypass the role-bucket restriction — allow `"goalkeeper"` for any player, and add `stamina`/`fitness` to the allow-list | `TrainingProgressionService.java:415-430` |
| 3 | First-class persistence — today the per-player choice only exists as an entry in the `advancedAssignmentsJson` blob | `TrainingProgressionService.java:59` |
| 4 | Individual focus overrides the role default; role default still applies to the rest | — |
| 5 | A drag-and-drop per-player panel in the Training Setup page | `static/js/pages/views/training-view.js:7-453` |

**Verify:** assign a striker `shooting` focus → his shooting grows at the direct rate and his other skills at the general rate.

---

### S4.2 — Training intensity: the core trade-off

| # | Task |
|---|---|
| 1 | Intensity setting: `LIGHT` / `NORMAL` / `VERY_HARD` (per player and per team) |
| 2 | Growth multiplier: `LIGHT 0.75×`, `NORMAL 1.0×`, `VERY_HARD 1.35×` |
| 3 | **Fatigue cost** — training now generates fatigue. This is the missing half of the loop |
| 4 | **Injury risk** — `VERY_HARD` on a fatigued player raises training-injury probability |
| 5 | Weekly recovery via Sprint 0.5 passive decay |
| 6 | Pre-season / training camp block with a temporary intensity boost at a cost |

**Verify:** `VERY_HARD` grows faster and tires more; a tired player on `VERY_HARD` gets injured. The decision is now real.

---

### S4.3 — Coaching staff affects growth (consume S2.3)

| # | Task |
|---|---|
| 1 | Growth multiplier from the relevant coach's `development` + `motivation` attributes | |
| 2 | Position-specific coaching: a `GK_COACH` improves goalkeeping growth, a scout improves `Country.youthRating` yield (Sprint 5) |
| 3 | A poor coach actively hurts growth (a `discipline`-driven penalty) |
| 4 | **Head coach quality affects match performance**, not just training — the board's most expensive hire should matter on the pitch |
| 5 | Staff wage is a real ledger expense (S2.2) — so good coaching is a budget decision |

**Verify:** replacing a 60-development coach with a 90 one measurably speeds growth; the batch shows a visible development-rate change.

---

### S4.4 — Facilities

| # | Task |
|---|---|
| 1 | `TrainingGround` entity per club: `grassQuality`, `gymLevel`, `tacticalLevel`, `youthLevel` (1–20) |
| 2 | Facilities multiply growth and **reduce injury risk** |
| 3 | Upgrades are a one-off capital cost + weekly upkeep in the ledger |
| 4 | Stadium quality affects home advantage and player attraction |
| 5 | Youth facility level feeds academy intake quality (Sprint 5) |

---

### S4.5 — Position-specific learning curves

Today only a global `STRIKER × 0.76` / `PACE × 0.86` penalty (`TrainingProgressionService.java:374-382`).

| # | Task |
|---|---|
| 1 | Per-position growth multipliers per skill. Wingers learn crossing fast; centre-backs learn heading slowly; strikers peak on finishing at 27; full-backs decline in pace earlier |
| 2 | A skill-position matrix, e.g. `Map<Position, Map<SkillName, Double>>` |
| 3 | **Age-based attribute ceilings** — a 34-year-old cannot improve pace at all regardless of talent |
| 4 | Natural ability caps by position: a 1.68 m centre-back should not be able to reach 20 heading |
| 5 | Height/weight influence heading, strength and jump (currently seeded at `YouthAcademyService:194-195` and never used) |

---

### S4.6 — Mentoring and cohesion

| # | Task |
|---|---|
| 1 | Pair a young player with a senior at the same position → growth bonus, mutual morale effect |
| 2 | Squad cohesion: players who play together accumulate familiarity, improving tactical execution |
| 3 | **Fix the no-op possession-context tactics.** All 506 rules in `TacticsRules` are identical, so in-possession vs out-of-possession shape does nothing (`backlog.md:381-385`) | 
| 4 | Team training familiarity: new signings take time to learn the tactical system |

---

### S4.7 — AI clubs train

| # | Task | File:line |
|---|---|---|
| 1 | Run `runWeeklyTraining` for **every** club in `advanceWeekAndHandleSeasonTransition`, not just `userTeam` | `AdvanceWeekAsyncService.java:81`; `SimulationController.java:264` |
| 2 | AI clubs pick training priorities from their `ClubNeed` model (S3.6) | |
| 3 | AI clubs assign Advanced slots by quality and age | |
| 4 | This also makes **aging decay** apply to AI squads, so the pyramid actually evolves | |

**Verify:** after 2 simulated seasons, AI squads have measurably changed, weak clubs have either improved or collapsed, and the league table is less predictable.

---

### Sprint 4 exit criteria

- [ ] Individual per-player skill focus
- [ ] Intensity with growth / fatigue / injury trade-offs
- [ ] Staff attributes measurably affect growth
- [ ] Facilities purchasable and effective
- [ ] Position learning curves and age ceilings
- [ ] Mentoring works
- [ ] Possession-context tactics are not a no-op
- [ ] AI clubs train and develop
- [ ] Growth remains 0.3–0.6/wk for a young Advanced player, 0.04 for a 35-year-old

---

# Sprint 5 — Juniors v2

**Effort:** 8–10 days · **Type:** `FEATURE` · **Depends on:** S2.3 (scouts) + S4.4 (youth facilities)

Target: Sokker-parity on the academy. Currently at ~"tier-3 academy, no scouts".

---

### S5.1 — Scouting network

**`Country.youthRating` is already seeded (45–95), already exposed via `CountrySummaryDTO`, and read by nothing.** A purpose-built hook for exactly this feature.

| # | Task |
|---|---|
| 1 | `ScoutingNetwork` entity per club: assigned scouts, regions covered |
| 2 | `ScoutAssignment` — assign a scout to a country or region; output volume scales with scout `scouting` attribute and the country's `youthRating` |
| 3 | Scout cost as a weekly ledger expense (S2.2) |
| 4 | Better scouts → more and better intakes |
| 5 | **Foreign youth recruitment** — with 9 countries seeded, scouting opens up a real transfer market for teenagers |

---

### S5.2 — Report uncertainty

| # | Task |
|---|---|
| 1 | Show an **estimated range**, not the true `academySkillExact`. Uncertainty shrinks with scouting level and time observed |
| 2 | `talent` becomes a hidden ceiling revealed by scouting, not a number shown on day one |
| 3 | Reports arrive progressively: initial impression → after a season → full assessment |
| 4 | This is the single biggest *feel* upgrade available in the academy — it turns a spreadsheet into a judgement |

---

### S5.3 — Academy structure

| # | Task |
|---|---|
| 1 | Position known at **intake**, not rolled at promotion (`YouthAcademyService.java:333-340`) |
| 2 | Individual junior training focus, integrated with Sprint 4 |
| 3 | Junior attributes visible as a profile (preferred foot, height, weight, personality, work rate, injury susceptibility) |
| 4 | **Mid-season promotion** — remove the artificial one-season freeze at `:81-85` (keep a shorter cooldown instead) |
| 5 | Academy quality from `TrainingGround.youthLevel` + the `YOUTH_COACH` staff member |
| 6 | Raise or make configurable the 10-active-junior cap |
| 7 | Continuity on promotion — carry junior progress into the senior system rather than starting from a rolled skillset |
| 8 | Personality, work rate, professionalism as growth inputs (Sprint 4) |

---

### S5.4 — Junior pathways

| # | Task |
|---|---|
| 1 | **Loan out** prospects (Sprint 3) — the standard route for a 17-year-old |
| 2 | Release to free agency with a small compensation |
| 3 | Junior market: AI clubs buy your listed prospects (already partially wired via `transferListJunior:153-162`) |
| 4 | Academy graduates visible on the global market board |
| 5 | Multi-season tracking: a junior's development history across seasons |

**Verify:** scouting Serbia, Brazil and England produces different quality intakes; prospects develop at visibly different rates; loans give young players minutes.

---

# Sprint 6 — Delete the dead code

**Effort:** 1 day remaining · **Type:** `DELETE` · **Independent — can run any time**

> ✅ **S6.1, S6.2 and S6.3 (the engines) are DONE — 2026-09-26.** 125 files moved to `footballForDelete/`, `mvn compile` clean, `mvn test` 84/84 green. See `footballForDelete/README.md` and `sprintProgress.md`.
>
> What was quarantined: `engine_v1/` (12 files, ~9,775 LOC), the v2 `newLogic/engine/` package (26 files, ~4,563 LOC), `NewMatchController`, 8 dead services, `RuntimeSaveToDB`, `util/events/` (6), `tools/SimulationRunner`, `old/` (4), the v2 `model/MatchState` + `MatchRuntime`, `demo/swingUIDemo/` (55), and 8 orphaned frontend files.
>
> **Still open in this sprint:** S6.4 (stub services + empty controllers) and S6.5 (documentation rewrite). The latter is now urgent — `AGENTS.md` describes engines that were just deleted, and the two `MatchOrchestrator` / `MatchState` name collisions are exactly the kind of thing that misleads the next agent.

---

### S6.1 — Delete the two dead engines ✅ DONE 2026-09-26

| Path | LOC | Reason |
|---|---:|---|
| `newLogic/engine_v1/RealisticMatchEngine.java` | 5,053 | Entry point `WeekPreparationAsyncService.startOrGetRunningJob` has 0 callers |
| `newLogic/engine_v1/AIDecisionMaker.java` | 985 | |
| `newLogic/engine_v1/PositionalDefense.java` | 50 | Self-described placeholder |
| `newLogic/engine_v1/RealisticEventGenerator.java` | 246 | |
| `newLogic/engine_v1/BroadcastEngine.java` | 88 | |
| `newLogic/engine_v1/MatchPlaybackEngine.java` | 436 | |
| `newLogic/engine/` (26 files) | 4,563 | Only reachable via the unlinked `realisticDemo.html` |
| `newLogic/controller/NewMatchController.java` | — | `/api/v2/match/*` |
| `newLogic/service/MatchLiveService.java`, `MatchLiveSession.java`, `MatchOrchestrator.java` | ~1,500 | |
| `demo/swingUIDemo/` (15 files) | 11,912 | Desktop JVM `main()` only |

⚠️ **Before deleting `engine_v1/RealisticMatchEngine`,** migrate `maybeTriggerInjury:1410-1489` into the proposal engine. That is the best mechanic in the whole codebase (fatigue → injury risk, position-weighted, minute-windowed) and it currently lives in a file about to be deleted.

⚠️ **Before deleting `newLogic/engine/`,** consider salvaging `clampPaceThisTick():361-379` (the no-teleport invariant) and `ZonePositionCalculator`.

---

### S6.2 — Freeze `demo/service/` as a reference module (partially done)

19,488 LOC. Its `corePrinciples.md` §1–48 is the design spec the proposal engine was ported from, and `ComprehensiveBatchRunner` / `MatchDetailedAnalyzer` are the calibration oracle. **Keep it. Stop editing it. Move it out of the main build path** so nobody "improves" it again.

- Move to a `reference/` module excluded from the Spring Boot build, or
- Keep the diagnostics runners, delete the engine classes

Also keep the **viewer assets** the SPA actually uses: `static/demo/service/ui/proposal/{index.html, viewer.js, viewer3d.js, pitch.css, vendor/, models/}`. Consider moving them to a properly named location (`static/replay/`) — the current path is confusing and actively misleads agents about which engine is live.

---

### S6.3 — Delete the dead frontend ✅ DONE 2026-09-26 (quarantined)

| File | LOC | Notes |
|---|---:|---|
| `static/realisticDemo.html` | 1,670 | Unreachable |
| `static/realisticDemoLegacy.html` | 1,635 | Loads the *wrong* script |
| `static/js/realisticDemo.js` | 2,904 | Only used by the two pages above |
| `static/js/realisticDemoLegacy.js` | 2,777 | Fully orphaned |
| `static/cleanSheetTifo.html` + `static/js/cleanSheet.js` | 932 | Orphaned text mode |
| `static/css/style.css`, `dasboardBackup.css` | — | Typo in the second filename |
| `static/js/pages/views/navigation.js` | 55 | Orphan |
| `static/js/pages/features/training.js` | 13 | Orphan |
| `static/js/main.js` | 8 | Orphan |
| `static/demo/service/ui/index.html` + `viewer.js` + `viewer3d.js` | — | Calls `/api/generate`, which does not exist in Spring |
| `static/index.html` | 31 | Dead legacy login with hardcoded creds |

Then fix the 13 dead routes (S8.1).

---

### S6.4 — Delete the stub services and empty controllers

| Item | Lines | Replacement |
|---|---:|---|
| `CompetitionController` | 7 | Empty class, 0 routes. Either implement league/competition admin or delete |
| `StadiumController` | 9 | Comment only |
| `LeagueService` | 9 | `SeasonService` does the real work |
| `TrainingService` | 21 | `TrainingProgressionService` is the live path |
| `StadiumService` | 7 | |
| `DummyDataService` | 172 | 0 callers |
| `PlayerConditionService` | 40 | 0 callers — or wire it in S2.6 |
| `PlayerSkillProgressionService` | 72 | 0 live callers, **inverted** talent factor, hard cap 17 |
| `model/TrainingAssignment` | — | Orphan POJO |
| `Crowd`, `Referee` entities | — | Orphans, never read |
| 6 dead `/training/*` endpoints | — | Never called by any frontend |
| `AGENTS.md` §"New Match Engine" etc. | ~900 lines | Rewrite per S6.5 |

**Preserve before deleting:** the working `engine_v1/MatchEngine.simulateRestOfMatchDay` — but replace its `simulateQuickScore:1578-1602` with the proposal engine first (Sprint 7).

---

### S6.5 — Rewrite the documentation

**11,856 lines of Markdown is now a liability.** It is more than the entire training + transfer + medical + academy layer combined, and much of it is confidently wrong.

| # | Task |
|---|---|
| 1 | Rewrite `AGENTS.md` — 92 KB describing engines that no longer exist. Target: 400–600 lines, current-state only |
| 2 | Delete `PROPOSAL_PROGRESS.md` (110 KB session log) or archive it to `docs/archive/`. Keep `PROPOSAL_CURRENT_STATE.md` and `backlog.md`, updated |
| 3 | Delete `THREAT_OVERRIDE_SPEC.md` (38 KB) — superseded by the implemented engine |
| 4 | Fix the 3 documented-but-inactive claims in `PROPOSAL_PROGRESS.md` (`nearestOpponentBeatsHimToIt`, the unreachable offside block, `/api/generate`) — or delete the file |
| 5 | `UI_FOOTBALL_MANAGER.md` — remove "✅ U potpunosti implementiran" while it lists 6 unimplemented items |
| 6 | Correct the goal-mouth note: **the mouth is 10 m (1 col × 10 m), not 14 m.** The 14 m figure is the *row* length (98 m / 7 rows) and applies to the length axis, not the goal |
| 7 | Add an `ENGINE.md` stating in one place: `newLogic/sim/` is the only engine; the dashboard button is `start-realistic-demo-btn` for historical reasons; the viewer is `static/demo/service/ui/proposal/` |
| 8 | Keep `expertAudit.md` and `sprintBacklog.md` as the living strategy docs |

---

# Sprint 7 — AI-vs-AI verification (reduced 2026-09-26)

**Effort:** 1–2 days · **Type:** `FIX` · **Depends on:** S1

> **This sprint was originally 5–7 days and has been cut to 1–2.** The audit claimed AI-vs-AI league matches were produced by a Poisson dice roll (`MatchEngine.simulateQuickScore`) with hardcoded 4-4-2 lineups, leaving the league table statistically incoherent with the match engine.
>
> **That was wrong.** `POST /simulation/current-round/simulate-all` (`SimulationController.java:129-158`) already routes **every** fixture through `SimMatchService.simulate()` — the proposal engine. The user's league runs synchronously in-loop (`:133-137`); all other leagues run via `AsyncSimulationRunner.simulateInBackground()` → `simMatchService.simulate(fixture, false)` (`AsyncSimulationRunner.java:59`). The league table is written by `SimMatchService:246-261`.
>
> The dead `simulateQuickScore` was only reachable from `WeekPreparationAsyncService` and `RoundSimulationAsyncService`, **both of which have 0 callers** and were quarantined on 2026-09-26. See `footballForDelete/README.md` and `expertAudit.md` §4.1.
>
> So this sprint is now purely about **locking in the invariant with a test**, so the next person does not reintroduce a second simulation path.

---

### S7.1 — Add a regression test that all fixtures use the proposal engine

| # | Task |
|---|---|
| 1 | Assert that `SimMatchService` is the only class that produces a `Match` result for a played fixture — no `simulateQuickScore` / `simulateRestOfMatchDay` equivalent exists |
| 2 | Assert `AsyncSimulationRunner` and `SimulationController.simulateCurrentRound` both go through `SimMatchService` |
| 3 | Add a static check (test or build step) that fails if any class outside `sim/` calls into a simulation loop directly. This is the guard that would have caught the original rot |
| 4 | Document in `ENGINE.md`: `newLogic/sim/` is the only engine; the dashboard button id `start-realistic-demo-btn` is a historical name; the viewer is `static/demo/service/ui/proposal/` |

**Verify:** the test fails if you reintroduce `engine_v1/MatchEngine` or `newLogic/engine/MatchSimulator` into the build.

---

### S7.2 — Make AI-vs-AI fixtures inspectable

The engine is consistent; the only real gap is observability. AI fixtures are simulated with replay recording suppressed, so unlike your own match they cannot be watched afterwards.

| # | Task |
|---|---|
| 1 | Offer to record a replay for a selection of AI fixtures (or all, if storage allows) so the user can review a neighbouring match |
| 2 | Keep the "results hidden until you play your match" gate — it is good design. But move the guard server-side; the current `sessionStorage.dashboardWeekConsumed` check is client-side only (`demo.js:634-637`) |
| 3 | Add a post-round summary showing which AI fixtures were close, so the round feels consequential rather than random |

**Verify:** a simulated round is followed by a summary; selected AI fixtures are watchable in the same viewer as your own match.

# Sprint 8 — Presentation, integration and deployment

**Effort:** 10–12 days · **Type:** `INFRA` + `FEATURE` · **Depends on:** all

---

### S8.1 — Wire or delete the 13 dead routes

`results`, `cup`, `international`, `friendlies`, `playerStats`, `teamStats`, `topScorers`, `topAssists`, `coaches`, `events`, `analytics`, `upcoming`, `training` — no menu entry, no action row, console-only. **6 of them crash into a generic "API Error" card** because they `await response.json()` without checking `response.ok`.

| # | Task |
|---|---|
| 1 | Wire `results`, `upcoming`, `playerStats`, `teamStats`, `topScorers`, `topAssists` — the endpoints exist, only the routing is missing |
| 2 | `cup` and `international` depend on the Cup being populated (S8.2). Until then, **remove them from the router and the menus** rather than leaving broken pages |
| 3 | `analytics` → replace the broken `zox-match-preview.html` (4 unbound tabs, unused Chart.js, doubled stylesheet) with a real analytics page, or route it into the existing match-detail view |
| 4 | Global fix: every fetch must check `response.ok` and surface a real error, not a generic card. Centralise this in `authFetch` |
| 5 | Add `loadPage` state to the browser URL (`#/leagueTable`) so pages are linkable and the back button works |

---

### S8.2 — Implement the cup and internationals

`DatabaseInitializer:611-630` creates `Kup Srbije` with `teamsPerCompetition = 64` and `hasSeeding = true`, but **never populates or schedules it** (the fixture loop filters `type == LEAGUE` at `:346`). `Competition.hasPlayoff`, `hasPlayout`, `hasSeeding`, `seededTeamsCount` and `reputationWeight` are all dead columns.

| # | Task |
|---|---|
| 1 | Populate the cup with 64 clubs across all 5 tiers |
| 2 | Single-elimination bracket with seeded draw and byes |
| 3 | Cup fixtures integrate with the weekly advance (or a mid-week cup round) |
| 4 | Cup prize money → ledger (S2.2) |
| 5 | International friendlies and a national-team competition |
| 6 | Implement national team and U21 — currently hardcoded placeholder pages (`country-view.js:186-208`) |

---

### S8.3 — Fix the remaining frontend debt

| # | Task | File:line |
|---|---|---|
| 1 | **Duplicate sidebar handlers** — `app.js` and `sidebar.js` both bind `#clubSidebar`, so `loadPage` fires twice per click | `app.js:8-36`; `sidebar.js:39-74` |
| 2 | Consolidate `escapeHtml` (3 copies) and `authFetch` (2 copies) | `dashboard.js:70`; `pages.js:532`; `utils.js:4`; `clock.js:1-23` |
| 3 | Mobile sidebar has **no `tacticEditor` entry** and uses different labels than desktop | `dashboard.html:31-92` vs `:95-133` |
| 4 | `bindScheduleInteractions` fallback passes `seasonYear` positionally, silently dropping it | `pages-renderers.js:201-203` |
| 5 | `training-view.js` — merge the two ~450-line parallel implementations | `training-view.js:7-453,455-907` |
| 6 | Delete the dead code in `pages.js` (`loadRecentLeagueMatches`, commented markup at `:428-433`) and `utils.js` (`resolveFixtureStadiumImage`, `emptyStateHtml`) | — |
| 7 | Club logo selection by name string compare — make it a team field | `dashboard.js:47` |
| 8 | Add `/images/default-stadium.png` or remove the reference | `fixture-view.js:34` |
| 9 | Only SRB has a flag image; 8 other countries have `flagImagePath = null` | `DatabaseInitializer:372-374` |
| 10 | `promote-reveal` uses `sessionStorage` — breaks on refresh and across devices. Move to a server-side reveal record | `academy.js:186-193` |

---

### S8.4 — League table: three comparators, one implementation

`MatchStatisticEngine:231-306`, `CountryController:109-112` and `SeasonService:745-753` each implement the sort. Extract one `LeagueTableService` and have all three call it. Then delete the `PromotionRule` hardcoding (`PromotionRuleRepository` is injected nowhere) in favour of the seeded `Competition.promotionSpots` / `relegationSpots` / `hasPlayoff` columns.

Also: `ensureEntriesForSeasonCompetition:126-134` deletes and rebuilds all entries on membership drift, which would wipe table data. Make it additive.

---

### S8.5 — Presentation and realism content

| # | Task |
|---|---|
| 1 | **Press conferences** — pick a response, affect `fanMood` / `mediaPressure` (S2.4) |
| 2 | **Squad goals** — players have season targets, reported in the weekly update |
| 3 | **Inbox** — match reports, board messages, transfer offers, injury news. Sokker's inbox is a core retention feature |
| 4 | **In-match decisions** — change tactics at half time / after a goal (`TacticsAdjustmentService` exists at 41 lines — check what it does and wire it) |
| 5 | Set your own **formation and mentality** mid-match, which currently only `MatchLiveService` (being deleted) could |
| 6 | Match preview quality — `ZoxApiController:59-60` hardcodes `drawProbability = 0.25`. Build a real model from team strength and home advantage |
| 7 | News feed for injuries, board decisions, transfer completed, record broken |
| 8 | Career-mode persistence: what happens across multiple seasons and multiple managed clubs |

---

### S8.6 — Deployment infrastructure (deferred until the instance goes up)

> **Currently intentional:** the season advances only when a human clicks `Advance Week` / `Simulate All Results` / `Watch Your Match`. There are no schedulers. That is correct while you are the only player and are tuning the engine. **These tasks are for when the instance is live.**

| # | Task |
|---|---|
| 1 | Decide the auto-advance model: cron-scheduled weekly tick, or "advance on last action" with a real-time clock |
| 2 | Move `GameClock.currentPhase` from a never-written field to a real state machine (PRE_SEASON / IN_SEASON / PLAYOFFS / SEASON_END) — the `SeasonPhase` enum already has 8 values |
| 3 | Scheduled job for weekly training, AI simulation, finance settlement, injury recovery |
| 4 | Idempotency keys on every scheduled job so a restart mid-tick cannot double-apply |
| 5 | Remove the `admin` DB init/reset buttons from the player-facing UI, or gate them to DEV only |
| 6 | Rate-limit and audit `/simulation/**` and `/admin/**` |
| 7 | Move `Player → team` binding from name string match to a real FK or `managedTeamId` column. **Currently renaming a club loses your team** (`SimulationController.java:363-372`) |
| 8 | Multi-manager support: more than one user managing different clubs, with a spectator mode |
| 9 | Observability: the engine logs a lot; add a `GapLogDiagnostic`-style check to CI so silent-freeze regressions fail the build |
| 10 | Profile the batch runner. 310 clubs × 20 weeks × 5,400 fixtures (Sprint 7) must fit in a reasonable window |

---

### Sprint 8 exit criteria

- [ ] No dead routes, no generic "API Error" cards
- [ ] Cup and internationals functional
- [ ] National team and U21 implemented
- [ ] One league-table comparator
- [ ] Press conferences, inbox, news feed
- [ ] Schedulers in place and idempotent
- [ ] Multi-user capable
- [ ] Engine quality gates running in CI

---

# Effort summary

| Sprint | Scope | Effort | Type |
|---|---|---:|---|
| **0** | Fix the exploits | 3–4 d | `FIX` |
| **1** | Engine calibration + injuries + penalties + subs | 16–20 d | `FIX` + `FEATURE` |
| **2** | **The economy** | 15–18 d | `FEATURE` |
| **3** | Contracts and transfers v2 | 12–15 d | `FEATURE` |
| **4** | **Training v2** | 12–15 d | `FEATURE` |
| **5** | Juniors v2 | 8–10 d | `FEATURE` |
| **6** | Delete the dead code — engines ✅ done, stubs + docs remain | 1 d | `DELETE` |
| **7** | AI-vs-AI verification (reduced from 5–7 d) | 1–2 d | `FIX` |
| **8** | Presentation, integration, deployment | 10–12 d | `INFRA` |
| | **Total** | **~79–95 days** | |

### Critical path

```
S0 (4d) → S1 (20d) → S2 (18d) → S4 (15d) → S3 (15d) → S5 (10d) → S8 (12d)
                                    └──────── S7 (2d) ────────┘
S6 (1d) — parallel, any time
```

### The first 30 days

| Days | Work | Outcome |
|---|---|---|
| 1–4 | **Sprint 0** | Exploits closed, training now reaches the engine, `/api/**` locked |
| 5–24 | **Sprint 1** | 5.4 → ~2.8 goals, 598 → ~130 duels, restarts fixed, corners unskewed, **injuries / penalties / substitutions implemented** |
| 25–42 | **Sprint 2** | Every club has money, wages, staff, sponsors, a board and a ledger. **The game is now a manager game.** |
| — | **Sprint 6** | ✅ Engines already quarantined (done 2026-09-26, 125 files). Docs + stubs remain |

After ~5 weeks the project goes from *"a beautiful match sim with 30 menu pages"* to *"a football manager that happens to have an excellent match engine."*

---

# Cross-cutting definitions of done

For any task in this backlog:

- [ ] **`mvn compile` is clean**
- [ ] **`mvn test` is green**, with new tests for the change
- [ ] **Any calibration change is measured** over ≥ 50 matches and the numbers are recorded in `PROPOSAL_CURRENT_STATE.md`
- [ ] **No new entity without a repository and a controller** — the codebase already has too many orphan POJOs (`TrainingAssignment`, `Crowd`, `Referee`, `MatchTeamStats`, `PossessionPhase`, `StoppageType`)
- [ ] **No hardcoded literals in production code paths** — `drawProbability = 0.25`, `founded = 1954`, `competitionRepository.findById(1L)`, `"John Smith"`, `budget: 1_200_000`, `"4-4-2"`
- [ ] **Every `fetch` checks `response.ok`** and surfaces a real error
- [ ] **`AGENTS.md` is updated** if the change alters architecture — it is read by every future agent, and it is currently wrong in several places
- [ ] **No new dead code** — if you add something and do not wire it, delete it. The repo has ~40k LOC of exactly that
