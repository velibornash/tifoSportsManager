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

### 🟢 STANDING RULE — owner, 2026-09-27: everything gets fully wired

> **"If it isn't there, build it. If it is, connect it. Wire it so it is a real feature and not
> something that looks like a feature."**

The project is in its final polish phase. This supersedes the earlier "don't add new dead code" instinct
with something stricter: **a half-wired feature is worse than no feature**, because it reads as
finished. It costs a manager real attention and gives nothing back.

**What this has already caught**, all in one sprint, all the same shape — a rule implemented, and
implemented *on a path nobody looks at*:

| Found | The seam |
|---|---|
| `PlusFeatureService` | All five methods had **zero callers**. The PLUS rule was documented and unit-tested and applied to no DTO the UI read |
| Coaching staff | The wage left the account every week and the teaching did nothing |
| The promotion reveal | The rule was on `JuniorAcademyItemDTO`; the reveal screen reads `JuniorPromotionResultDTO`, which had no talent field at all |
| `MoraleService.moraleModifier` | **Zero callers.** `PlayerContractService` had its own inline copy of the same morale→growth formula, and *that* was the live one |

**The test to apply before calling anything done:** *trace the value from where it is written to where
a manager or a match can observe it.* If the chain has a break anywhere, the feature is not done. And
"it is on the DTO" is not an answer — the question is which DTO **that screen** reads.

Fields carried for later use do not get an exemption. If a field is carried, something reads it in the
same task, or it does not ship.

---

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
Sprint 0  Fix the exploits            ✅ DONE 2026-09-26
Sprint 1  Engine calibration          ✅ DONE as mechanics 2026-09-26 (statistics deferred by owner)
Sprint 2  The economy                 ✅ DONE 2026-09-26
Sprint 3  Contracts & transfers       🟡 core done; loans built, 4 AI-market gaps open
Sprint 4  Training v2                 🟡 6 of 7 done; 5 items backlogged
Sprint 5  Juniors v2                  ◀ CURRENT. Only S5.3 graduation is done. S5.1/S5.2 unstarted
Sprint 6  Delete the dead code        ✅ DONE — engines, stubs and docs all cleared 2026-09-26/27
Sprint 7  AI-vs-AI verification       ❌ not started (1–2 d, cheap, do it after S5)
Sprint 8  Presentation + deployment   ❌ not started
```

**Suggested order:** ~~S0 → S1 → S2 → S4 → S3~~ **done.** Now **S5 → S7 → S8**, with the
Sprint 3 and 4 remainders (AI market supply pressure, AI training priorities, camp, stadium
quality, youth-facility intake) picked up opportunistically — none of them block anything.

**Every sprint below has been re-audited against the code on 2026-09-27.** Several entries in this
document were stale: Sprint 6's stub services are already deleted, Sprint 3's loans are built but
unmarked, and the Sprint 2/3/4 exit criteria were all-unchecked despite the work being done. Where a
claim below is marked ✅ it was verified in the source, not inferred from a commit message.

---

# Sprint 0 — Stop the bleeding

**Effort:** 3–4 days · **Type:** `FIX` · **Blocks:** everything

Seven small, high-impact defects. Every one of these is a correctness bug, not a design gap. Do them first because each is cheap and each one currently corrupts a live system.

---

### S0.1 — Close the €1 transfer exploit ✅ DONE (verified 2026-09-28 — guard present, method renamed)

**File:** `newLogic/service/TransferService.java`

> **Verified 2026-09-28.** The guard exists, but the method was **renamed** — grepping for
> `normalizePrice` finds nothing and looks like an open bug. It is now `resolveAgreedPrice`, which
> rejects NaN and infinite prices, rejects anything ≤ 0, and throws `PRICE_BELOW_ASKING` when the
> offer is under the asking price. `buyListedPlayer` goes through it, so the €1 exploit is closed.

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

### S0.2 — Fix the transfer-list soft-lock ✅ DONE (verified 2026-09-28 — `hasPricedOffer` gate in place)

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

### S0.3 — Idempotency guard on weekly training ✅ DONE (`TrainingProgressionIdempotencyTest`)

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

### S0.4 — Feed `*Exact` into ratings and OVR ✅ DONE 2026-09-26

**File:** `newLogic/model/Skills.java:118-141`

> **Scope corrected during implementation.** The backlog assumed the match engine read the floored `int`. It does not — `RealSquadFactory.toSimSkills:185-196` already calls `getExact(...)` for all eight skills, so training has always reached the engine. The real gap was the **display and rating layer** only.

| # | Task | File:line | Status |
|---|---|---|---|
| 1 | `getRatingScore()` to read `*Exact` | `Skills.java:118-141` | ✅ |
| 2 | Add `visibleInt(SkillName)` for UI/tests needing the floored view | `Skills.java` | ✅ |
| 3 | `getTotalForRating()` left on visible ints — coarse display helper | — | ✅ deliberate |
| 4 | Fluent accessors left as `int` | `Skills.java:135-144` | ✅ no `newLogic` consumer |
| 5 | Re-measure engine calibration | — | ✅ unnecessary, engine untouched |

**Consumers fixed:** `PlayerDTO.calculateOverall` (displayed OVR), `MatchRatingCalculator` (match rating), and the four `Team` strength methods (all have 0 callers — dead, useful for Sprint 8).

**Verify:** 8 tests in `SkillsExactRatingTest`, including that whole-number ratings are numerically identical to the old formula, so this is a precision gain with no balance shift on existing saves. `mvn test` 123/123.

---

### S0.5 — Passive fatigue recovery ⏭️ SKIPPED, folded into S1.6

**Why skipped (2026-09-26):** the task assumed fatigue accumulates and simply never recovers.
**It does not accumulate either.** Verified across the whole live domain:

| Writer | Status |
|---|---|
| `Player.addFatigue` (`model/Player.java:76-78`) | **0 callers** |
| `util/match/MatchContext:59,63` (`+1` per minute) | Dead — only consumer is `PlayerActionProbabilityModel`, which itself has 0 callers |
| `engine_v1/RealisticMatchEngine:1353,1374,1456` | **Quarantined** to `footballForDelete/` on 2026-09-26 |
| `sim/engine/FatigueSystem:24` | Writes `sim.model.Player.setFatigue` — the engine's own 0..1 field on a **different class**, never persisted back to `newLogic.model.Skills.fatigue` |
| `TeamMedicalService:54` (`-12`) | Only live writer, and it only *reduces* |
| `YouthAcademyService:259` (`setFatigue(0)`) | Resets on promotion |

So `Skills.fatigue` (int 0–100) is never increased anywhere in the live path. It is read by
`Team.getAvailablePlayers()` (`fatigue < 8`), `Player.getCurrentFatigue()` and `PlayerDTO`, but
always sits at 0.

**Adding passive recovery now would be recovery for a permanently-zero value** — dead code by the
"no new dead code" rule in this document. The mechanic needs a *source* before it needs a *sink*.

**Action taken:** folded into **S1.6** (port the injury model), which is where fatigue gets a real
input — the ported `maybeTriggerInjury` reads fatigue to scale injury probability, so fatigue must
be produced during the match for that port to mean anything. S1.6 now owns the whole chain:
accumulate fatigue from minutes played → passive weekly recovery → injury risk.

**Also logged for Sprint 4:** the false claim in `medical-view.js:95` ("Weekly passive healing still
applies") must be corrected whenever recovery actually lands.

---

### S0.6 — Delete the dead offside / rules paths ✅ DONE 2026-09-26

| # | Task | Result |
|---|---|---|
| 1 | `sim/rules/FootballRules.java` — instantiated, never read | ✅ deleted |
| 2 | Unused `EngineInterfaces.FootballRules` placeholder | ✅ deleted |
| 3 | `isPathBlocked()` returned literal `false`, making the +12 carry bonus unconditional | ✅ implemented (lane segment vs opponents) |
| 4 | `nearestOpponentBeatsHimToIt()` had no call site — the bug it documents was never fixed | ✅ wired into `scorePassOptions` (−35) |
| 5 | `pointSegmentDistance` copy-pasted in 3 places | ✅ consolidated into `SimUtils` |
| 6 | `tackles` looked like a duplicated `duelsWon` field | ✅ documented as complementary, not a bug |
| 7 | Unreachable pass-moment offside block | ⏭️ deferred — needs a decision on whether `confirmOffside()`'s discarded return should drive it. **Left for PO** |

Items 3 and 4 are deliberate behaviour changes, which is why they belong before Sprint 1
re-calibration. Engine sanity-checked: `ProposalSeasonDiag` 8 matches → 3.4 goals, 34.5 shots,
82% pass accuracy, 49/51 possession, 374 duels, 6 corners, 15.5 fouls, 1 scoreless.

> ⚠️ **The calibration baseline in `expertAudit.md` §5 is stale.** It was measured before the
> quarantine and before the concurrent REC engine work on this branch. **Sprint 1 must re-baseline
> from current HEAD**, not tune against those numbers.

---

### S0.7 — Security and endpoint hygiene ✅ DONE 2026-09-26

| # | Task | Result |
|---|---|---|
| 1 | Move `/api/**`, `/api/sim/replay/**`, `/api/zox/**` behind JWT | ✅ |
| 2 | Validate `sortBy` whitelist in `getPaged` | ⏭️ Sprint 8 |
| 3 | Validation + authorisation on `TeamController.create` / `PlayerController.create` | ⏭️ Sprint 8 |
| 4 | `LineupController` raw `RuntimeException` → `ApiException` | ⏭️ Sprint 8 |
| 5 | Implement `POST /auth/register` or delete the page | ⏭️ **needs PO decision** |
| 6 | Admin registration approve/reject or hide buttons | ⏭️ Sprint 8 (moves to Admin tab) |
| 7 | Point `club-management.js` at the real `/teams/{id}/profile` | ⏭️ Sprint 2 (needs real finance data first) |
| 8 | Wire staff-directory / fixture-view to real endpoints | ⏭️ Sprint 2 (needs real `StaffMember`) |
| 9 | Fix `fetchPlayerRatingSummary` arity | ⏭️ Sprint 8 |
| 10 | Fix `login.js` missing `#loginStatus` | ⏭️ Sprint 8 |
| 11 | **Lock the whole game API** | ✅ see below |

**Item 11 was far bigger than the task described.** The `permitAll` list contained the entire game
API, not just the replay endpoints:

```
/api/**  /teams/**  /players/**  /matches/**  /match-stats/**  /training/**
/countries/**  /commonmanager/**  /proposal/api/**  /api/v2/**  /dashboard.html
/zox/**  /start-realistic-demo
```

Any anonymous visitor could read every squad and player, read and rewrite lineup templates and the
tactic editor, run training, list and buy players, trigger matches, and advance the season. For a
game of competing managers that is fatal.

Now permitted: static assets, landing/login/register, `/auth/**`, the two clock endpoints, the
replay viewer assets, and the two legacy game modes.

**Left public deliberately:** `/basketballmanager/**`, `/americanfootballmanager/**` — neither
reads the JWT anywhere, they cannot be tested here, and breaking two untouched modes to secure the
one under development is the wrong trade. They need their own auth pass.

**Also fixed:** `shouldReturnUnauthorized` only matched paths *starting* with `/api/`, so an
unauthenticated GET to `/proposal/api/**` returned a 302 to the HTML login page. The fetch followed
it, `response.json()` threw on HTML, and the SPA showed a generic "API Error" card instead of
"please log in" — the exact misleading-error pattern that hid a dozen dead routes.

The proposal viewer sent no `Authorization` header on its four API calls; added an `apiFetch()`
helper there while leaving static assets (`match.json`, `player.glb`) on plain `fetch`.

---

### Sprint 0 exit criteria — ✅ COMPLETE 2026-09-26

| Criterion | Result |
|---|---|
| No free-player exploit | ✅ `0a62d08` |
| No delisting soft-lock | ✅ `2a67162` |
| No free-skill-point exploit | ✅ `14a414b` |
| Training progress visible to the engine | ⚠️ `cff62db` — visible to **ratings/OVR**; the engine already read exact values (audit correction) |
| Fatigue has a passive decay curve | ⏭️ skipped — nothing produces fatigue (see S0.5) |
| Silent failures now log | ✅ `14a414b` |
| Dead code removed | ✅ `7feb6d6`, `80bf15a` |
| Whole game API behind JWT | ✅ `a6476b7` |
| Every SPA endpoint resolves | ⚠️ partial — 13 dead routes remain (Sprint 8) |
| Tests | ✅ **84 → 124** (+40) |

**Sprint 0 shipped 5 commits.** Everything deferred is listed above with its destination sprint.

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

> ⚠️ **Added 2026-09-26.** `DuelService.java:101-105` awards the penalty and increments a stat. `ActionLogService.java:50` declares the channels `PENALTY_KICK` / `PENALTY_SAVED` / `PENALTY_MISS` — and **no code in the repo produces them.**
>
> ✅ **DONE 2026-09-26** (execution). ✅ The "award rate is already correct" premise below was **wrong** — measured 0.07/match, not 0.2. See the note at the end of this task.

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

> Sokker's famous bug is 80% missed penalties. This engine's bug was 100% un-taken penalties. Strictly worse, and much cheaper to fix.

**Implementation (2026-09-26):** `PenaltyEngine`, split into a pure `resolve()` (conversion model, no side
effects) and `apply()` (events, stats, restart).

Conversion is modelled as *the keeper commits before the kick*, not as a geometric proximity test:

| taker (striker/tech) | keeper | scored | saved | missed |
|---|---|---|---|---|
| 12 / 12 | 12 | **76.6%** | 19.4% | 4.0% |
| 12 / 12 | 20 | 67.9% | 28.0% | 4.0% |
| 12 / 12 | 2 | 86.1% | 9.9% | 4.0% |
| 4 / 4 | 12 | 71.5% | 20.5% | 8.0% |
| 20 / 20 | 12 | 80.7% | 18.3% | 1.0% |

Real penalties convert ~76% (75–78%), so the average row is on target and the spread across the
keeper range (~10% poor → ~28% elite) matches the real spread.

**Two bugs found while building it, both of which the unit tests caught:**

1. *"Guessed wrong" was silently right one time in three.* When the keeper failed the read test the
   code rolled a **fresh uniform side**, which coincided with the taker's actual side ~33% of the
   time. That turned a 37% read into an effective 58% and pushed saves to 27% — a keeper being
   beaten far less often than in real football. `wrongSide()` now excludes the taker's side.
2. **The taker could be handed the ball.** `selectTaker` filtered on row > 6.0 to mean "not an
   defender", so a keeper standing in the opposition half won the selection on his shooting skill.
   Now filtered on role.

**Also fixed:** the claim block in `MatchOrchestrator` clears the set-piece type on the same tick the
taker reaches the ball, so a penalty degraded into an ordinary 11 m shot and the log went silent. The
orchestrator now latches `penaltyPending` at the claim, before the clear.

**Corrected from the task text:** a penalty is **not** an assisted goal. `onGoal` is called with a
null assist; crediting one would invent a completed pass that never happened.

**Invariant added to `ProposalBatchDiag`** — it prints `*** PENALTY CHAIN BROKEN ***` if any awarded
penalty is not followed by a kick. It earned its keep immediately: it caught bug 1's symptom
(5 awarded / 0 taken) before any test did. Over 200 matches: 0.07 awarded = 0.07 taken.

> **Follow-up, deliberately NOT done here:** the award rate is **0.07/match against a real ~0.27**.
> The premise that `PENALTY_FROM_BOX_FOUL = 0.06` already gave 0.2/match was never verified and is
> ~3x off. Per the one-calibration-per-commit rule this is a separate change, not to be bundled with
> the execution work.

---

### S1.7b — Penalties are awarded 3x too rarely ✅ DONE 2026-09-26

Found while completing S1.7. Once penalties could actually be taken, the award rate became
measurable for the first time, and it is **0.07/match against a real ~0.27** (roughly one every
four matches). The S1.7 task text asserted the rate was already correct on the strength of
`PENALTY_FROM_BOX_FOUL = 0.06`; that was never measured and is ~3x off.

This was not visible before because an un-taken penalty is indistinguishable in the aggregate from
a rare one — the award counter looked plausible and nothing followed it.

Raised as its own task because it is a single calibration constant and the project rule is one
calibration per commit.

**Result:** `PENALTY_FROM_BOX_FOUL` 0.06 → **0.165**. Over 200 matches: **0.24 penalties/match**
(target band 0.20–0.30). Everything else held — goals 3.55, shots 35.7, pass 85% — so this is an
isolated change.

The constant was derived from two measured quantities rather than guessed:

| quantity | measured (200 matches, seed 42) | real football |
|---|---|---|
| box fouls per match | 1.655 | ~2.5–3.5 |
| VAR confirmation of a penalty | 99.1% (3 overturns in 331) | — |
| penalties per match (before) | 0.07 | ~0.27 |

`1.655 × rate × 0.991 = 0.27` → `rate ≈ 0.165`.

**Read this honestly:** 1-in-6 box fouls converting is high next to a real 1-in-11. The engine
under-produces box contact and this constant is compensating for that. The cleaner fix is more box
fouls, which is a separate calibration — logged as **S1.7c**. VAR was measured and is *not* a
useful lever: the review gate in `VARService.checkPenalty` overturns so rarely that it barely moves
the total.

#### Root cause of the erasure: offside has priority over a penalty (user rule 2026-09-26)

The collision was not a random race — it was a **rules** question. When the ball is played to an
attacker who is in an offside position, the whole phase is a prohibited action: a defender cannot
concede a penalty for a foul on a player who had no right to be contesting the ball. So offside
wins and **the penalty is never created.**

Fixed at the root, in `DuelService.evaluateDiscipline`: if the fouled player is the
`offsideFlaggedReceiver`, the penalty is suppressed, an `OFFSIDE` event is recorded, the offside
counter is fed, and `handleOffsideFreeKick` restarts play. `handleOffsideFreeKick` also clears
`penaltyPending` explicitly, since it is reached from three call sites that must all agree.

`startSetPiece` still refuses an *ordinary* free kick while a penalty is pending — that stays
correct, and is the opposite rule on purpose.

> ⚠️ **This is a deliberate divergence from Law 11, and it is worth knowing which case it changes.**
> The Laws penalise *"whichever offence occurs first"*. IFAB's own FAQ is explicit on the case we
> hit: an attacker who **plays the ball** and is then fouled in the box — *"the offside offence
> occurred before the foul, so the referee awards an indirect free kick … not a penalty kick."* That
> is exactly the user's rule and exactly the seed-123 case.
>
> The divergence is the **reverse** order: an attacker in an offside position who is fouled
> **before playing the ball**. Law 11 says that is still a penalty — *"the foul is penalised as it
> has occurred before the offside offence."* Under the rule implemented here it is not.
>
> The implementation keys off `offsideFlaggedReceiver`, which is set at pass-moment, so it covers
> both orders and resolves both in offside's favour. That is coherent as a game rule — "you cannot
> win a penalty from an attack that was never on" — but it is stricter than the Laws. If the intent
> was only the IFAB case, the guard should additionally require that the ball has reached the
> attacker. **Open question for the PO; not changed unilaterally.**

#### A second, worse bug found while calibrating: a penalty could be erased mid-flight

`ProposalBatchDiag`'s chain invariant flagged `48 awarded but 47 taken`. Bisected to seed 123:

```
tick 2653  award  spt=PENALTY_HOME  carrier=null rtaker=H10
tick 2654          spt=FREE_KICK    carrier=null rtaker=H10   <-- displaced
tick 2655          spt=null         carrier=H10                 <-- played as a free kick
tick 2658  ball has left the pitch; a throw-in follows. No PENALTY_KICK, ever.
```

A foul that is both a penalty **and** an offside in the same incident fires both. The offside path
called `handleFreeKick`, which overwrote `setPieceType`. The penalty was re-derived from the
set-piece type at the moment the taker reached the ball, read `FREE_KICK`, and downgraded itself.

**Fix — the penalty is now an explicit flag on `MatchState`, latched at the award:**
- `MatchState.penaltyPending`, set by `RestartManager.handlePenalty`, cleared by `PenaltyEngine`.
- `startSetPiece` **refuses any non-penalty restart while a penalty is pending.** A penalty is the
  more serious offence; restarting with a free kick would let the fouled side take a lesser restart
  and quietly erase what the referee awarded.
- The orchestrator's per-tick stall watchdog now keys off the flag, and forces the kick after 40
  ticks so a penalty can never evaporate even if the taker loses his way to the spot.

4 tests in `PenaltyChainTest`, including the exact overlap that caused it. 171 total.

**Converted at 83% over 48 kicks** — above the 76% model rate, because `selectTaker` hands the ball
to the best finisher on the pitch and a small sample runs high. The 400k-sample model figure
(76.6% for average skills) is the reliable one.

---

### S1.7c — Box fouls are under-produced [DEFERRED 2026-09-26]

> Deferred by owner decision: connect the system first, statistics later. Kept so the finding is
> not lost. Partly self-correcting now - the stoppage work means the match genuinely plays 90+
> minutes, which raised box fouls and the penalty rate with it.

### S1.7c ORIGINAL — Box fouls are under-produced ⚠️ OPENED 2026-09-26

Surfaced by S1.7b. The engine commits **1.655 box fouls per match**; real football is nearer
2.5–3.5. S1.7b compensated by raising `PENALTY_FROM_BOX_FOUL` to 0.165, so 1 in 6 box fouls now
converts to a penalty against a real 1 in 11. The penalty *rate* is right; the *box contact* that
feeds it is not.

Better to fix the cause than keep the compensation. `DisciplineService` decides `foulProb` before
it knows where the foul is, so a tackle in the box is no more likely than one on the halfway line.

**Verify:** box fouls/match lands in 2.0–3.5 over 200 matches, penalties/match stays in 0.20–0.30
with `PENALTY_FROM_BOX_FOUL` able to come back down toward 0.09.

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

### S1.9 — Fix the remaining calibration outliers [DEFERRED 2026-09-26]

> Deferred by owner decision (2026-09-26): fix what is clearly broken, but connect the system
> first and do the statistics afterwards. Only the clearly-broken were fixed (goal kicks
> 35.9 -> 21.9 in S1.2). The rest is recorded in `sprintProgress.md` as a position, not a
> scorecard, and is **no longer benchmarked against Premier League figures.**
>
> Worth an eye: **no 0-0 in 100 matches.** At ~4 goals/match a goalless draw is genuinely rare
> (~1.4%), so this may be nothing, but it is a behavioural signal rather than a calibration one.

### S1.9 ORIGINAL — Fix the remaining calibration outliers

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

### S1.10 — Persist replays [DONE 2026-09-26]

`SimReplayStore` is an unbounded in-memory `ConcurrentHashMap` (`SimReplayStore.java:17-29`). A restart loses every replay; `Match.replayId` survives in the DB as a dangling id.

| # | Task |
|---|---|
| 1 | Persist `SimReplayView` to disk (or a `replay_blob` table) on store |
| 2 | Load on read, fall back to in-memory |
| 3 | Bound the retention (keep last N matches per team, or TTL) |
| 4 | Null out `Match.replayId` if the blob is gone, so the UI can say "replay expired" instead of hanging |

---

### S1.11 — Wire THRU / CROSS / CENTER [ALREADY IMPLEMENTED 2026-09-26]

> **This entry was stale when picked up.** The claim was that deliveries entered only via the
> final-two-row hard rule. They are first-class options - `CleanDecisionEngine:1075` says so
> explicitly ("ALL options compete. THRU / CROSS / CENTER are included on purpose") - and over
> 3 matches they fire at CROSS 23/match, CENTER 23/match, THRU 8.7/match, which is the real
> 20-30 band for crosses. Nothing to build; the entry needed correcting.

---

### S1.10 — Persist replays

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

### Sprint 1 exit criteria — ✅ mechanics COMPLETE 2026-09-26 · statistics deferred by owner

**Read this before the table.** The owner closed Sprint 1 as **mechanics** on 2026-09-26 and
deferred every statistical target (see PRIORITY CHANGE at the foot of this document). The table is
kept as a **record of where the numbers were left**, not as a gate. What mattered was that the
mechanics exist and are not broken, and they now do.

| Task | State | Note |
|---|---|---|
| S1.1 goal inflation chain | ✅ done | on-target probability, post clustering, mouth width, `POST_RADIUS` |
| S1.2 restart inversion | 🟡 partial | goal kicks 40.7 → ~20, in a sane band. `desiredClearancePower` (item 2) was never built; the fix came from the launch speed alone |
| S1.3 corner skew | ❌ not done | no corner-side/origin diagnostic was ever written. Ratio is only mildly asymmetric (~0.75–0.85) |
| S1.4 duel count | 🟡 partial | `isPathBlocked` and `nearestOpponentBeatsHimToIt` are wired into pass scoring, so interposition exists. The duel **count** was never reduced to 100–150 — deferred |
| S1.5 chase multiplier + press radius | ✅ done | `CHASE_SPRINT_MULTIPLIER = 1.18`, `PRESS_DRIB_DUEL_RADIUS = 0.38` (~5 m, just past the 0.35 separation wall) |
| S1.6 injury model | ✅ done | `sim/engine/InjuryService.java` — fatigue-scaled, position-weighted, minute-windowed |
| S1.7 penalties | ✅ done | `PenaltyEngine`, 76% model conversion, keeper-commits-first |
| S1.7b penalty award rate | ✅ done | `PENALTY_FROM_BOX_FOUL` 0.06 → 0.165, 0.24/match |
| S1.7c box fouls | ⏭️ deferred | the compensation constant stands; root cause unfixed |
| S1.8 substitutions | ✅ engine, ❌ UI | `SubstitutionService` + bench + windows + role-aware selection. The **manager-facing UI is not built** |
| S1.9 calibration outliers | ⏭️ deferred | |
| S1.10 persist replays | ✅ done | |
| S1.11 THRU/CROSS/CENTER | ✅ already implemented | entry was stale; deliveries are first-class options |

**The three holes that were the point of this sprint are closed:** injuries, penalties and
substitutions all exist and fire. A red card still means ten men for the rest of the match, which is
correct football and is no longer a bug.

**Where the numbers were left (200-match reference, kept as a record):**

| Metric | Old target | **Measured** | Real PL | Status |
|---|---:|---:|---:|---|
| **Goals** | 2.6 – 3.1 | **2.8** | 2.7 | ✅ |
| **Shots on target** | 8 – 10 | **8.9** | 8–9 | ✅ |
| **Saves** | 3 – 6 | **5.1** | ~5.8 | ✅ |
| **Shot conversion** | — | **34%** | ~32% | ✅ |
| Shots | — | 32.3 | 25 | ✅ owner accepts 30–40 |
| On-target % | 30 – 36% | 28% | 33% | ✅ accepted trade-off |
| Pass accuracy | 75 – 82% | 81.5% | 80–86% | ✅ |
| Possession | symmetric | 48.7 / 51.3 | 50 / 50 | ✅ |
| Duels won | 100 – 150 | **373** | ~100 | ⏭️ deferred |
| Interceptions | 12 – 18 | **27.7** | 12–16 | ⏭️ deferred |
| Corners | 8 – 12, symmetric | **5.6** (2.2/3.3) | ~10 | ⏭️ deferred |
| Goal kicks | 10 – 18 | 19.8 | 12–15 | ⚠️ high |
| Fouls | 19 – 26 | 16.6 | 22 | ⚠️ low |
| Yellow cards | — | 2.3 | 4–5 | ⚠️ low |
| Red cards | 0.1 – 0.4 | 0.15 | 0.2 | ✅ |
| Nil-draws | ≥ 2% | 1/50 = 2% | ~6% | ⚠️ low |

> Note: these figures predate S4. The current engine measures **4.88 goals/match** over 100 matches
> with corners ~14, and the owner has explicitly accepted that. See the Sprint 4 owner decisions.

**Two deliberate non-changes, both recorded in the source:**

1. **Shot volume is not a defect.** `SHOT_FREQUENCY_GATE = 0.30` carries a note that the owner
   accepts "30-40 shots a match". Chasing a real 25 would contradict an explicit decision.
2. **On-target % sits at 28% against a real 33%, and that is the coherent trade.** 32 attempts with
   the same accuracy and the same goals as a real 25-shot match: more chances, no more scoring. The
   extra volume surfaces as misses, not as goals. Raising SOT to 33% would put goals back near 3.3 and
   break the metric that matters most.

**Root cause of the whole mess, for the record:** `GoalkeeperEngine.trySave` never checked whether
the ball was going between the posts, so 26% of off-target attempts were "saved". Until that was
fixed the save statistics were a broken denominator and no shot-model calibration could be trusted.

---

## Open items carried out of Sprint 1 (review at the end, not now)

Owner decision 2026-09-26: Sprint 1 is closed as **mechanics**. These are the numbers that were left
unfinished, to be looked at together at the end rather than chased now.

| Item | What is outstanding |
|---|---|
| **S1.1c** | Shot volume and goals are high; not being chased |
| **S1.3** | Corner skew (ratio ~0.75-0.85, i.e. mildly asymmetric) — no diagnostic was ever written |
| **S1.7c** | Box fouls under-produced; the penalty constant compensates for it |
| **S1.9** | Remaining calibration outliers |
| **0-0 draws** | None in 100 matches. At ~4 goals/match that is ~1.4% expected, so possibly nothing - but it is a behavioural signal, not a calibration knob |
| **Conditional subs UI** | The rules builder and live fired/spent/void view. Engine + API are done and tested |
| **Tactical editor** | Redesign placeholder, scheduled last |

---

# Sprint 2 — The economy

**Effort:** 15–18 days · **Type:** `FEATURE` · **Depends on:** S1 (for honest broadcast/prize income)
**This is the sprint that turns the game into a manager game.**

> **Note:** much of this already exists as plain code in `footballtextmanager/CleanSheetService.java:427-434` (`applyRoundFinances`: wage bill `earnings × 0.58`, sponsor income `10000 + reputation × 180`, gate income) and `CSDataInitializer.java:144` (budgets seeded 500k–1M). **Port it, do not rewrite it.** The logic is proven; only the wiring is new.

---

### S2.1 — Seed a real world ✅ DONE 2026-09-26

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

### S2.2 — `FinanceLedgerEntry` + weekly settlement ✅ DONE 2026-09-26

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

### S2.3 — Sponsors and staff as real entities ✅ DONE 2026-09-26

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

### S2.4 — Board expectations and manager trust ✅ DONE 2026-09-26

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

### S2.5 — Wage bill, FFP-lite, transfer budget ✅ DONE 2026-09-26

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

### S2.6 — Dressing room and morale ✅ DONE 2026-09-26

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

### Sprint 2 exit criteria — ✅ COMPLETE 2026-09-26

All six tasks (S2.1–S2.6) are done. The criteria below were written as unchecked boxes and never
updated; they are now marked against what is actually in the code.

| Criterion | Result |
|---|---|
| Every club has budget, reputation, staff, sponsors | ✅ S2.1, S2.3 — tier-scaled budgets, per-club reputation variance, global name dedupe, 3–7 staff and 1–3 sponsors per club |
| A weekly ledger runs for all 310 clubs | ✅ S2.2 — `FinanceLedgerEntry` + `WeeklyFinanceService`, called for **every** club in the week advance, one transaction per club |
| `Player.earnings` drives a real wage bill | ✅ S2.5 — `Σ Player.earnings` against a board wage ceiling; FFP-lite ratio visible to the player |
| The Finances page reads real data and nothing else | ✅ S2.5 item 6 — the client-side finance fiction in `club-management.js` is gone |
| Board expectations, trust, and a sacking flow exist | ✅ S2.4 — `BoardExpectation`, per-round evaluation, sacking below threshold |
| Morale and form are live and reach the engine | ✅ S2.6 — `getConfidenceModifier()` wired into shot and pass execution; `Player.form` responds to minutes, goals, rating, results and wage-vs-value |
| No client-side finance fiction remains | ✅ |

**One thing S2.6 explicitly deferred and Sprint 4 then delivered:** feeding morale and form into
training growth (S2.6 item 6) is now live via the coach and cohesion multipliers in S4.3/S4.6.

**Carried forward, not blocking:** `SquadGoals` and per-player wage **demands** (an unhappy player
requesting a rise rather than a transfer) are Sprint 8 material — see S8.5.

---

# Sprint 3 — Contracts and transfers v2

**Effort:** 12–15 days · **Type:** `FEATURE` · **Depends on:** S2

Target: Sokker-parity on the transfer market. Currently at ~15%.

---

### S3.1 — `PlayerContract` entity ✅ DONE 2026-09-26

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

### S3.2 — Real negotiation ✅ DONE 2026-09-26

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

### S3.3 — Transfer windows ✅ DONE 2026-09-26

| # | Task |
|---|---|
| 1 | Summer window (weeks 1–6) and winter window (weeks 10–12). Emergency exceptions for free agents and loan recalls |
| 2 | Window deadline countdown in the transfer centre |
| 3 | Deadline-day scramble (loans, free transfers) — a nice Sokker-flavoured event |
| 4 | Out-of-window attempts are rejected with a clear reason, not a silent 500 |

---

### S3.4 — Loans ✅ built 2026-09-26 (was never marked — re-audited 2026-09-27)

`LoanService` is a full lifecycle, not a stub: `offer` → `accept` → `start` → `recall` /
`returnEarly` → `closeFinishedLoans`, plus `buyOption` and `wageCarriedBy` so a loanee counts for
squad depth without distorting the wage bill.

| # | Task | State |
|---|---|---|
| 1 | Loan with optional buy clause and optional wage contribution | ✅ |
| 2 | Loan players count as squad depth but not first-team registrations | ✅ `isRegisteredByNobody` + `wageCarriedBy` |
| 3 | AI clubs accept and make loan offers | ✅ |
| 4 | **Loan out academy prospects** | ✅ the hook exists — `LoanService.loanOut` takes a player id and a `YOUTH` squad role exists on `PlayerContract` |

> The reason this entry sat unmarked for a sprint: S3.6 was re-audited on 2026-09-26 and S3.4 was
> simply never revisited. Loans were built in the same pass as the negotiation engine.

---

### S3.5 — Work permits and foreign-player limits ⏭️ BUILT, THEN REMOVED BY OWNER DECISION

> **Corrected 2026-09-27 — twice.** The first version of this entry said "✅ built" and named
> `WorkPermit`, `WorkPermitService` and `Competition.foreignPlayerLimit` as delivered, which was true
> when written (`1661c97`, 2026-09-26). A later audit then reported it as **"never built"**, which was
> wrong: the feature was **deliberately deleted** the same evening at the owner's direction
> (`a6394f9`), taking `WorkPermit`, `WorkPermitService`, `WorkPermitRepository`, its 218-line test, the
> `Competition.foreignPlayerLimit` column and the gate in `PlayerContractService.sign` with it —
> 643 lines removed.
>
> The lesson is the same one from S3.4 and S6.4, and it is worth stating precisely: **absence in the
> source is not evidence of never having existed.** A feature can be built, reverted, or removed on
> purpose, and only the git history distinguishes those. Checking `git log` for a deletion is now part
> of the audit method.

**The owner's decision, recorded at the time:** *"No foreigner limit for now."* Serbia's real rule is
four non-EU players in the top flight and fewer below, but the numbers were inferred rather than
confirmed, and rather than ship a guessed constraint the feature was removed.

`Player.nationality` was **kept on purpose** — the owner may later want a minimum number of players
from the club's own country, which is what a nationality column is for. It costs nothing now and saves
a migration later.

| # | Task | State |
|---|---|---|
| 1 | `WorkPermit` per foreign player | ⏭️ built in `1661c97`, removed in `a6394f9` |
| 2 | Non-EU quota per competition | ⏭️ built, column removed |
| 3 | A signing is blocked when the quota is full | ⏭️ built, gate removed |
| 4 | Buying from a weaker league grants a permit more easily | ⏭️ built, removed |

**If it is ever wanted back,** the history is intact — `1661c97` is the whole feature and `a6394f9` is
a clean revert of it. Nothing needs to be rebuilt from scratch, and no decision is blocked by its
absence. The standing question is still the owner's: what the per-tier numbers should be, given that
the discussed 4/3/2/1/0 would mean **tier 5 takes no foreign players at all** — and tier 5 is
`Opštinska liga Šid`, where Sremac Berkasovo plays.

---

### S3.6 — AI demand model and a live market — 🟡 partly built 2026-09-26

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

| # | Task | State |
|---|---|---|
| 1 | `ClubNeed` model: each AI club evaluates squad gaps by position and quality tier | ✅ `ClubNeedService` |
| 2 | Buyer selection weighted by need × budget tier × ambition | ✅ weighted by need; budget respected |
| 3 | **Enable AI↔AI transfers** | ✅ every listed player is auctioned to every club |
| 4 | Price realism: age, contract length, form, squad depth | ✅ `ClubNeedService.valuation` |
| 5 | Negotiated AI offers (fee + wage + length) using the S3.2 engine | ❌ still one number, not a thread |
| 6 | AI clubs renew expiring contracts and reject players | ❌ **not done — supply pressure is still missing** |
| 7 | Unlisted players must be **scoutable** | ✅ `TransferService.scoutedUnlisted` |
| 8 | Transfer history per player | ❌ **not done** |
| 9 | Squad-size limits on purchasing | ❌ **not done** — loans are exempt, purchases are not capped |

**Verify:** over 20 simulated weeks, AI clubs trade players with each other, squads improve, and academy graduates get bought.

**⚠️ The verification above has not been run.** It asks for 20 simulated weeks showing AI clubs
trading with each other, squads improving and academy graduates being bought. That is a batch
diagnostic and it has not been written. Items 5, 6, 8 and 9 are the honest remainder.

### S3.7 — Legacy `Transfer` migration — ✅ done 2026-09-26

`Transfer.interestedTeams` stored interest as prose strings (`"Rival FC offered €900000"`) and the
seller parsed the sentence back to identify the buyer, while `TransferService.completeTransfer` held a
second copy of the settlement next to `NegotiationService`'s. Both are gone.

- [x] `interestedTeams` removed from the entity, the DTOs and every call site
- [x] The parser, resolver, purge and their two records deleted
- [x] `NegotiationService.settle` is the only settlement path; the purchase endpoints delegate to it
- [x] Interest, withdrawal and AI bids are all real `TransferOffer` records keyed by club id
- [x] Controllers re-pointed; the `club` name parameter is gone
---

### Sprint 3 exit criteria — 🟡 core done, AI market supply pressure missing

Re-audited 2026-09-27. The transfer market is genuinely Sokker-shaped; what is missing is the
**supply** side of it — AI clubs do not create listings of their own, so the market is all demand.

| Criterion | Result |
|---|---|
| Every player has a contract with a wage | ✅ S3.1 — backfilled 2–4 years at current `earnings` |
| Contracts expire → free agents exist | ✅ S3.1 item 3 — the 409 that made this impossible is gone |
| Negotiation is multi-round with fee + wage + length | ✅ S3.2 — `TransferOffer` + `NegotiationService` |
| `interestedTeams` prose encoding is gone | ✅ S3.7 — entity, DTOs, parser, resolver and purge all deleted |
| Transfer windows are enforced | ✅ S3.3 — out-of-window attempts rejected with a reason |
| Loans work both ways | ✅ S3.4 — full lifecycle (see above) |
| Work permits and the non-EU quota block signings | ⏭️ built then **removed at the owner's direction** — see S3.5 |
| AI↔AI transfers happen; squads visibly evolve | 🟡 transfers happen, **evolution does not** — see below |
| The seller chooses which offer to accept | ✅ S3.2 item 8 |

**The four honest S3.6 remainder** (verified still open):

| # | Gap | Why it matters |
|---|---|---|
| 5 | AI offers are one number, not a negotiation thread | An AI club never negotiates, so a human seller never haggles |
| 6 | **AI clubs do not renew expiring contracts or reject players** | No supply pressure. Contracts expire into free agency rather than being fought over |
| 8 | No transfer history per player | Blocks the player-view History tab (see PLAYER VIEW below) |
| 9 | No squad-size limit on purchasing | A club can buy until it is bankrupt on wages |

**S3.6 item 6 is the one worth doing first.** Everything else on this list is a feature; that one is
a hole — 300 AI clubs will sit on expiring contracts doing nothing about them, and the transfer market
will stay a one-way drain. It is not scheduled into a sprint because it is a balance-and-behaviour
task and the owner has deferred statistical work; it is recorded here so it is not lost.

---

# Sprint 4 — Training v2

**Effort:** 12–15 days · **Type:** `FEATURE` · **Depends on:** S2.3 (staff) + S2.6 (morale)
**You flagged training as critical. This is that sprint.**

> Today's design space is **two knobs**: four role-bucket skill dropdowns, and a 10-slot Advanced dropzone. Everything below expands it without breaking what works.

---

## Owner decisions — 2026-09-27

Recorded here so they cannot drift. These are rulings, not proposals.

### Match balance is left as-is

The 100-match batch gives **4.88 goals per match** (real football ~2.7), 40 shots at 27% on target, 85%
pass accuracy, 48/52 possession, corners ~14. The owner was asked whether to retune the newLogic sim
and decided **no**. Goals stay high; that is a deliberate choice, not an oversight, and the number
above is the reference for anyone who asks about it later.

Corners rising from ~6 to ~14 is a *consequence* of S4.6 and is an improvement — real football averages
around ten, so the old six was too low.

### Sprint 6: prove it is dead, then delete

The instruction is not "delete these packages" but **"carefully check whether they are called from any
active code, then delete everything that is dead"**. So the method is: reference analysis first, a
concrete call graph second, and deletion only for what is provably unreachable. Nothing irreversible on
the strength of a filename or a comment claiming something is legacy.

### Mentoring is fully automatic

No manager choice, no UI. The effect is **reported on the training screen** so the manager can see it
happened. Reasoning: a feature most clubs would never configure reads as broken, and 300 AI clubs get
it for free.

### Talent and training percentages: the full access rule

- Talent and training percentages are **PLUS-gated**.
- Visible for **your own first team** and **your own academy juniors** — nothing else.
- **Access ends once the player signs for someone else.** A former player's numbers stop being yours.
- **When you sell a player you can choose to reveal his talent.** That is the deliberate moment to show
  it, and it is the seller's choice, not an automatic disclosure.
- `OWNER` / `DEV` / `ADMIN` bypass the subscription check but **not** the own-team rule. Non-PLUS users
  never see talent or training percentages.

Both halves are now live. **First team** (2026-09-28): `PlayerDTO.talent` carries the **exact** figure,
own-squad only, PLUS-gated — not a band, because the academy already reveals the real number on
promotion and carries it across, so a manager who owns the player simply knows him. The band's job was
to say how sure you were *before* you committed; in your own squad it is no longer needed. **Academy:**
a narrowing band, with the exact figure revealed on promotion under the same rule.

The remaining hole is the reverse direction: a **rival's** talent is hidden, which is correct, and
there is nothing yet that ever *tells* a manager that a rival has one. That is Sprint 6 territory.

### S4.1 — Individual training focus — ❌ REMOVED by owner 2026-09-28

**The headline feature of Sprint 4 was not wanted.** The owner's position, 2026-09-28:

> NEMAMO individual focus, trening je samo po onome sto je izabrano u advanced training i na training
> slotu, mozes obrisati bekend

Training follows **what the manager chose in Advanced Training and on the training slot, and nothing
else.** There is no second control competing with it.

| # | Task | State |
|---|---|---|
| 1–4 | Per-player focus, role-bucket bypass, own table, override semantics | ❌ **deleted** — `TrainingFocusService`, `PlayerTrainingFocus`, `PlayerTrainingFocusRepository`, the `PUT`/`DELETE .../focus/{playerId}` endpoints, `FocusRequest`, the `individualFocus` report field, and `TrainingFocusServiceTest` |
| 5 | Drag-and-drop focus panel | ❌ **will never be built** |

**What was preserved, deliberately:**

- **`SkillName` stays.** It is shared by 18 files — growth profiles, staff, stadium facilities, match
  context, the text-based game. Only the *focus* machinery was the feature; the enum is not.
- **`PlayerTrainingIntensity` stays**, and is the better model of the two: a per-player weekly override
  that is genuinely used, keeps its own row per week, and answers "what was he on in week 5".

**Behaviour after removal:** the direct training skill is the role default for the week
(`dtSkillForRole`), which is exactly what `primarySkillFor` fell back to when no focus existed. Growth
rates, intensity cost, injury risk and the coach-for-skill match are all untouched — the focus was
only ever an override on top of them.

**One loose end for the owner:** `ddl-auto=update` will not drop the now-orphaned
`player_training_focus` table from the development database. Harmless, and `DROP TABLE IF EXISTS
player_training_focus;` clears it whenever you want.

---

### S4.2 — Training intensity: the core trade-off — ✅ 1–5 done 2026-09-27, camp backlogged

| # | Task | State |
|---|---|---|
| 1 | Intensity setting: `LIGHT` / `NORMAL` / `VERY_HARD` (per player and per team) | ✅ club default on the weekly setup, per-player override in its own table |
| 2 | Growth multiplier: `LIGHT 0.75×`, `NORMAL 1.0×`, `VERY_HARD 1.35×` | ✅ as specified |
| 3 | **Fatigue cost** — training now generates fatigue. This is the missing half of the loop | ✅ 4 / 12 / 26 a week |
| 4 | **Injury risk** — `VERY_HARD` on a fatigued player raises training-injury probability | ✅ 0 / 0.4% / 4.5%, rising steeply with fatigue |
| 5 | Weekly recovery via Sprint 0.5 passive decay | ✅ and the numbers are set against it |
| 6 | Pre-season / training camp block with a temporary intensity boost at a cost | ❌ **backlogged** — not done |

**Verify:** `VERY_HARD` grows faster and tires more; a tired player on `VERY_HARD` gets injured. The
decision is now real. — covered by `TrainingIntensityServiceTest`.

The numbers are only right in relation to recovery, which is the part worth remembering. A player
recovers `22 × ageFactor` a week — 22 at 24 and under, ~19 at 26, 14 at 31, 11 at 34. `VERY_HARD` at
14/week was *cheaper* than what a young player recovers, so the cost evaporated and the setting was
free; it is 26 now, which is why the tiers actually separate over a twelve-week season.

`LIGHT` never injures anyone at any fatigue — that is what makes it the rehabilitation option rather
than a slightly worse `NORMAL`. An injured player never picks up a second injury.

---

### S4.3 — Coaching staff affects growth (consume S2.3) — ✅ done 2026-09-27

| # | Task | State |
|---|---|---|
| 1 | Growth multiplier from the relevant coach's `development` + `motivation` attributes | ✅ resolved per skill, not per club |
| 2 | Position-specific coaching: a `GK_COACH` improves goalkeeping growth | ✅ GK coach teaches goalkeeping only; physio teaches stamina; youth coach only under-23s; a scout is not a coach. Scouting's `youthRating` effect is Sprint 5 |
| 3 | A poor coach actively hurts growth (a `discipline`-driven penalty) | ✅ 0.75–1.0 off man-management, never above 1.0 |
| 4 | **Head coach quality affects match performance**, not just training | ✅ ±6% on ability at the `toSimSkills` funnel, the one place real ability enters the engine |
| 5 | Staff wage is a real ledger expense (S2.2) | ✅ already in the weekly ledger |

**Verify:** replacing a low-development coach with a high one measurably speeds growth; a goalkeeping
coach speeds goalkeeping and nothing else; a head coach's players are measurably better *inside the
sim*. — `TrainingPercentCoachResolutionTest` (10), `StaffMemberMatchFactorTest` (6),
`HeadCoachReachesTheEngineTest` (6).

The ±6% bound is load-bearing. A head coach who was worth half a defender would not be a staffing
decision, it would be a balance change wearing one. A club with no head coach gets exactly 1.0, so its
players are unchanged from before the feature existed.

The bug these fix is the quiet one: a club hires a goalkeeping coach, his wage leaves the account every
week, and the goalkeeper grows at exactly the same rate as with nobody in the room. The feature existed
and did nothing, which is worse than not having the role.

`disciplineFactor` is capped at 1.0 **on purpose**. The percentage answers "how much of the budget was
spent"; man-management answers "was it worth spending". Merging them would silently change the training
formula that was signed off.

---

### S4.4 — Facilities — 🟡 1–3 done 2026-09-27, 4–5 backlogged

| # | Task | State |
|---|---|---|
| 1 | Facility levels per club | ✅ on `Stadium` — **not** a new `TrainingGround` entity, see below |
| 2 | Facilities multiply growth and **reduce injury risk** | ✅ ±10% on growth; the gym removes up to 40% of training-injury risk |
| 3 | Upgrades are a one-off capital cost + weekly upkeep in the ledger | ✅ progressive capital, linear upkeep, on the existing `FACILITY_UPKEEP` line |
| 4 | Stadium quality affects home advantage and player attraction | ❌ **backlogged** — `pitchQuality` is still unread |
| 5 | Youth facility level feeds academy intake quality | ❌ **backlogged** — `youthLevel` exists and is consumed in Sprint 5 |

**Verify:** a club with a gym grows a better-conditioned player and injures him less; a club that
cannot pay is refused. — `TrainingFacilityServiceTest`, 12 tests.

`Stadium` already carried `trainingQuality`, `pitchQuality` and `pitchCondition` and **none of them
was ever read**. A new `TrainingGround` entity would have split one club's buildings across two
tables, so the levels went where the ground already is. The backlog asked for a separate entity and
did not get one, deliberately.

Facilities are symmetric around 1.0: poor ones *waste* the week rather than merely failing to help.
A game whose floor is 1.0 quietly says facilities are free, which is the mirror image of the bug being
fixed.

**Unrecorded is neutral and is not level 1.** Every pre-existing club has null in these columns, so
reading null as "no facilities" would have taken 10% off the growth of every club in the database.

---

### S4.5 — Position-specific learning curves — ✅ done 2026-09-27

| # | Task | State |
|---|---|---|
| 1 | Per-position growth rates, per skill, with per-skill peaks | ✅ `PositionGrowthProfile` |
| 2 | A skill-position matrix | ✅ rate + peak + ceiling, four separate questions |
| 3 | **Age-based attribute ceilings** — a 34-year-old cannot improve pace at all | ✅ hard zero: pace 32, finishing 34, rest 36, goalkeeping 38 |
| 4 | Natural ability caps by position, as a soft asymptote rather than a wall | ✅ floor at level 7, no cap on a natural skill |
| 5 | Height/weight influence heading, strength and pace | ✅ ±8%, average when unrecorded |

**Verify:** a winger learns crossing faster than a centre-half, a centre-half's pace peaks before a
striker's, nobody still improves pace at 34, and the whole rewrite did not quietly double or halve
the rate everybody develops at. — `PositionGrowthProfileTest`, 15 tests.

Replaces the global `STRIKER × 0.76` / `PACE × 0.86` penalty, which was **removed rather than kept
alongside** — stacking them would have counted the same idea twice. The dead `talentFactor` went with
it.

The magnitude guard matters: every behavioural property can be satisfied while doubling how fast
everybody develops, and no property test notices. The band test walks every position, skill and age
18–38 and holds the coefficient near continuity with the old figures.

---

### S4.6 — Mentoring and cohesion — ✅ done 2026-09-27

**Owner ruling:** mentoring is **fully automatic, no manager choice**; the effect is reported on the
training screen. A feature most clubs would never use reads as broken, and 300 AI clubs get it free.

| # | Task | State |
|---|---|---|
| 1 | Pair a young player with a senior at the same position | ✅ derived, not stored — one senior to one junior, same position, 6 years and 27+ |
| 2 | Squad cohesion from players playing together | ✅ 0–100 per club, ±3% on the pitch, +4% in training, capped at 5% by a test |
| 3 | **Fix the no-op possession-context tactics** | ✅ all 506 out-of-possession rules were identical copies; the defensive block is now derived |
| 4 | New signings take time to learn the system | ✅ `Player.familiarity` 30 on arrival → 100, holds growth back ~10% |

**Only two fields were added** — `Player.familiarity` and `Team.cohesion`. The mentor pairings are
recomputed from the squad each week, so they cannot go stale or point at a sold player.

Both fields are **nullable**: `0` is a real value (a shattered dressing room, a player who knows
nothing) and `null` is the "never measured" sentinel. Reading `<= 0` as unrecorded was the third
instance of that trap in this sprint, and the tests caught it.

`Player` carries a positional all-args constructor — a new field inserted mid-list reorders every
argument after it. `familiarity` sits last for that reason; the class wants a builder.

---

### S4.7 — AI clubs train — 🟡 partly built 2026-09-27

The owner's ruling: AI clubs do nothing **except that their players still improve**. A league where
three hundred clubs never train is a league where a manager's own training means nothing.

| # | Task | State |
|---|---|---|
| 1 | Every club trains each week, not just the manager's | ✅ `SquadTrainingService.trainEveryClub`, wired into the weekly tick |
| 2 | The default week goes through the **same** percentage as a manager's programme | ✅ no second growth formula |
| 3 | AI clubs pick training priorities from their `ClubNeed` model (S3.6) | ❌ **not done** — the default is the role's primary skill, not a need |
| 4 | AI clubs assign Advanced slots by quality and age | ❌ **not done** |
| 5 | Aging decay applies to AI squads, so the pyramid evolves | ❌ **not done** — players stop improving past 34, but do not decline |

**The duplication that was removed matters more than the feature.** The first version of the default
pass had its own age curve and its own constants, so the same player grew differently under a default
week than under a manager's programme — talent, coach and minutes ignored entirely. Two growth
formulas is the same mistake as two settlement paths on a transfer, which cost this project four
"finished" systems that had never worked. There is now one.

**Verify:** after 2 simulated seasons, AI squads have measurably changed, weak clubs have either improved or collapsed, and the league table is less predictable.

---

### Sprint 4 exit criteria — 🟡 6 of 7 features done; the UI and AI priorities are the gaps

Re-audited 2026-09-27. The training model is complete and coherent — one growth formula, position
curves, age ceilings, coaches, facilities, intensity, focus, mentoring, cohesion. What is missing is
the part a manager touches.

| Criterion | Result |
|---|---|
| Individual per-player skill focus | 🟡 **backend only** — `PlayerTrainingFocus` + REST, but the per-player panel in Training Setup is not built (S4.1 item 5) |
| Intensity with growth / fatigue / injury trade-offs | ✅ S4.2 items 1–5. The camp block (item 6) is not built |
| Staff attributes measurably affect growth | ✅ S4.3 — resolved per skill, ±6% head-coach effect reaching the engine |
| Facilities purchasable and effective | 🟡 levels, growth effect, injury effect and costs all done. **Stadium quality → home advantage is not** (S4.4 item 4), and youth level → intake quality is Sprint 5 |
| Position learning curves and age ceilings | ✅ S4.5 — `PositionGrowthProfile`, hard age ceilings, band test guards the magnitude |
| Mentoring works | ✅ S4.6 — derived pairings, cohesion, familiarity, and the 506 no-op possession rules fixed |
| Possession-context tactics are not a no-op | ✅ S4.6 item 3 |
| AI clubs train and develop | 🟡 every club trains through the **same** formula, but priorities come from the role default rather than `ClubNeed` (S4.7 item 3), Advanced slots are unassigned (item 4), and **AI squads never decline** (item 5) |
| Growth remains 0.3–0.6/wk for a young Advanced player, 0.04 for a 35-year-old | ✅ held by the S4.5 band test |

**The two items that actually matter, in order:**

1. **S4.1 item 5 — the focus panel.** The whole headline feature of the sprint is invisible. The REST
   is done and tested; a manager cannot use it. This is the same class of defect as the goalkeeping
   coach whose wage left the account and whose teaching did nothing.
2. **S4.7 item 5 — AI squads never decline.** Players stop improving at 34 and then hold forever, so
   the pyramid cannot regenerate. Over a long save the league converges instead of turning over.

Everything else on this list is an enhancement. Those two are holes.

---

# Sprint 5 — Juniors v2

**Effort:** 8–10 days · **Type:** `FEATURE` · **Depends on:** S2.3 (scouts) + S4.4 (youth facilities)
**Status 2026-09-27: this is the current sprint. Re-audited against the source before starting.**

Target: Sokker-parity on the academy. Currently at ~"tier-3 academy, no scouts".

**Where the academy actually stands, verified in the code:**

| Fact | Evidence |
|---|---|
| `Junior` has **no position field** | the model is `id, name, age, talent, academySkill, academySkillExact, lastWeeklyDelta, arrival*, archived, status, team, promotedPlayer` — so S5.3 item 1 is genuinely unstarted |
| `talent` is a raw `double` with no uncertainty model | S5.2 unstarted. `PlusFeatureService` returns `null` (fully hidden) rather than an estimate |
| **No scouting code exists at all** | zero files matching `*Scout*`. `Country.youthRating` is seeded 45–95 and read by **nothing** (verified) |
| Promotion window is correct | S5.3 item 4 ✅ `f22ee1a` — graduation at 15–20, once a year, legacy distribution locked by tests |
| Sell-time reveal exists | `promoteJuniorWithReveal` |
| Listing and release exist | `transferListJunior`, `releaseJunior` |
| Loans can take a junior | `LoanService` is a full lifecycle (S3.4) — S5.4 item 1 needs only wiring |
| `youthLevel` on `Stadium` is unread | S4.4 item 5 — the intake-quality hook Sprint 5 is supposed to consume |

**The gap in one sentence:** the academy produces a number, not a player. There is no scouting, no
uncertainty, no position at intake, and no pathway out — so a junior is a dice roll that becomes a
`Player` on graduation day.

---

### S5.1 — Scouting network 🟡 1–4 done 2026-09-27, item 5 deferred by owner

**`Country.youthRating` is already seeded (45–95), already exposed via `CountrySummaryDTO`, and until
this task read by nothing.** A purpose-built hook for exactly this feature, sitting in the schema
since Sprint 2.1.

**Owner rulings, 2026-09-27, taken before any code was written:**

1. **No non-EU quota for now** — S3.5 was believed built and is not, and the quota was what gated
   foreign recruitment.
2. **Scouting produces intel, never signings.** A scouted prospect is reported on, not acquired.

Ruling 2 is why `ScoutingService` has no dependency on the transfer or contract layer, and why S5.1
could ship before the missing quota: nothing here registers a foreign player, so nothing here can
breach one. **If that ever changes, the quota becomes a blocker again.**

| # | Task | State |
|---|---|---|
| 1 | `ScoutingNetwork` entity per club: assigned scouts, regions covered | ✅ shipped as **`ScoutAssignment`** — one row per scout-country posting rather than a per-club blob, so "who is watching where" is queryable and a revoked posting is a state change rather than a re-serialised list |
| 2 | `ScoutAssignment` — assign a scout to a country; output scales with scout `scouting` and country `youthRating` | ✅ `ScoutingService.reach = 100 × (scouting/20) × ((youthRating−40)/60)` — a **product**, so a brilliant scout cannot rescue a country with no pipeline and a rich pipeline cannot rescue a scout who cannot tell a prospect from a squad player |
| 3 | Scout cost as a weekly ledger expense | ✅ **already satisfied, deliberately not re-implemented.** A scout is a `StaffMember`, so his wage already flows through the weekly `STAFF_WAGES` line from Sprint 2.2. A separate scouting charge would bill the same man twice in the same week |
| 4 | Better scouts → more and better intakes | ✅ as reach. It does **not** yet change your own academy's intake, which is correct: the legacy graduation distribution is locked by owner decision, and the S5.3 items 1 + 5 are what make intake quality meaningful |
| 5 | **Foreign youth recruitment** | ⏭️ **deferred by owner ruling 2** — scouting reports, it does not sign. Revisit when the quota exists and an owner decision says what tier 5 may do |

**Not yet built, and it is the rest of the feature:** the reports themselves. Right now a posting
produces a `reach` number and a label, which is the *input* to a report rather than a report. S5.2
turns reach into an estimated range on an actual player.

**Two edges pinned deliberately:** the best possible posting is **92, not 100** (`youthRating` 95
against a floor of 40 over a span of 60 is 0.917 — "Embedded" is the top of the scale and nothing
reaches the end of it), and an **unrated scout defaults to 1, not 0**, because zero would make a
freshly seeded scout literally blind.

**Also owed from Sprint 4:** `Stadium.youthLevel` and the `YOUTH_COACH` staff member's `development`
attribute both feed intake quality and are both still unread (S4.3 item 2 defers the scout half of
scouting explicitly here). They belong to S5.3 items 1 and 5.

**Tests:** `ScoutingReachTest`, 9 — the product property, the 92 ceiling, monotonicity in both inputs,
0–100 boundedness across the whole input space, the unrated-scout floor, and that a mid-table posting
lands where it can be read.

---

### S5.2 — Report uncertainty ✅ DONE 2026-09-27, gate wired 2026-09-28

> **The distinction that matters, and it is easy to get wrong.** `PlusFeatureService` already hides
> talent entirely — `talentOrNull` returns `null` for anyone but the owner. That is the **subscription**
> rule from the owner decisions above, and it is correct. S5.2 is a different axis: within your own
> academy, a figure you *are* entitled to see should still be an **estimate that firms up**, because a
> 17-year-old labelled "talent 4" on day one is a spreadsheet, not a judgement. Hiding is the PLUS rule;
> uncertainty is the scouting mechanic. They compose, they do not replace each other.

**Owner's specification, 2026-09-27:**

| Question | Answer |
|---|---|
| Does a scout's reach narrow the range, or only time? | **Reach narrows the range.** Time-only would leave S5.1 with almost nothing to do |
| Exact talent, or a band? | **A range around the exact value, from intake, narrowing until promotion.** Width at intake is `±(1 + rnd(3))` — so between ±1 and ±4, rolled per junior. It narrows to **±1 (with decimals) by promotion**. **A better `YOUTH_COACH` narrows it faster.** On promotion the **exact talent with decimals is revealed** |
| Who sees it? | **PLUS users see the range. Non-PLUS see nothing.** Exact-on-promotion follows the same rule |
| Do AI clubs get uncertainty? | Moot — **AI clubs have no junior school at all** (see below), so they generate no juniors to report on |

**What this makes `YOUTH_COACH` do.** Its `development` attribute now has a consumer, which closes
S4.3 item 2's explicit deferral ("a scout's `youthRating` effect is Sprint 5") and half of S5.3 item 5.
A club with a strong youth coach does not produce better prospects — it **knows what it has, sooner.**

| # | Task | State |
|---|---|---|
| 1 | Show an **estimated range**, not the true value | ✅ `TalentRange` — `talentMin`/`talentMax` on `JuniorAcademyItemDTO` |
| 2 | The true value stops leaking | ✅ **fixed 2026-09-28.** The raw `talent` field is **gone** from the DTO; a non-PLUS or non-own-club viewer receives no exact value at all. `talentExact` exists but is only populated for an entitled viewer |
| 3 | Reports firm up over time | ✅ the range *is* the progression: intake `±(1 + rnd 0..3)`, narrowing to `±1` by graduation, faster with a better `YOUTH_COACH` |
| 4 | Exact reveal on promotion | ✅ `promoteJuniorWithReveal` → `JuniorPromotionResultDTO.talentExact`; the player is created either way, so a non-subscriber still gets the player and only loses the number |
| 5 | `YOUTH_COACH.development` has a consumer | ✅ closes S4.3 item 2's explicit deferral and half of S5.3 item 5 |

> **The leak in item 2 was the whole point of the sprint, and it took a second pass to actually close.**
> The range was built and the gate was documented and unit-tested, and the DTO still shipped the true
> value to every caller — because the controller applied the rule by hand instead of asking the
> service. That is now enforced by `PlusGateHasCallersTest`, which fails the build if any gate method
> has no caller.

#### ✅ `PlusFeatureService` was not wired to anything — FIXED 2026-09-28

**This was true when it was written and is now wrong. Kept because the shape of the bug is the
lesson.** All five public methods had zero callers in the live path: the owner's rule that talent and
training percentages are PLUS-gated and own-team-only was implemented, documented and unit-tested as a
service, and then never applied to a single DTO the UI read.

The reason is worth remembering, because it was not carelessness. **`TrainingController` was already
applying the rule correctly** — it just did so by open-coding `hasPlus && isOwnTeam` instead of asking
the service. The behaviour was right, so nothing looked broken, and the service sat there unused. A
second copy of a rule is indistinguishable from the rule until you count the copies.

Two more gaps surfaced once the gate was actually consulted:

| Gap | What it was | Fix |
|---|---|---|
| First-team talent not on the wire at all | `PlayerDTO` had **no** talent field, so own-squad talent was not a leak — it simply did not exist for anyone, PLUS or not | `PlayerDTO.talent`, populated by `talentOrNull` at the controller. The missing positive case |
| `hasPlusSubscription` dead | Duplicated `hasPlus` minus the role bypass. The profile read the column directly, so the method was never needed | Moved to `User.isPlusSubscriber()`, where the field lives, so the auth module does not depend on the football engine to ask a question about its own user |

**Enforcement, so this cannot recur silently:** `PlusGateHasCallersTest` fails the build if any public
method on the gate has no call site anywhere under `src/main/java`. It had to be written twice to be
correct — the first version counted a method's own Javadoc `{@link}` and its own signature as callers,
and cheerfully reported live methods as dead. It now strips comments and excludes the declaration
itself. A guard that certifies dead code is worse than no guard, because it is read as permission.

---

### S5.3a — The junior school (owner spec 2026-09-27) ✅ DONE

**Raised out of S5.2.** Answering "do AI clubs get the same uncertainty?" produced a better question:
**should AI clubs have an academy at all?** The answer was no, and that turns the academy from a
free background process into a **purchased, staffed, seasonal thing** — which makes it a decision
rather than a default.

Today `YouthAcademyService.generateSeasonIntakeForWeek2` loops **every** club from
`findClubTeamsForOperations()`. All 310 clubs roll an intake every season, for free, whether their
manager is a human or not.

**Owner's specification:**

| Question | Answer |
|---|---|
| Do AI clubs have a junior school? | **No. Not one.** AI squads keep the same players with only the default training pace applied |
| What does it cost? | **One-off activation cost + a weekly upkeep** |
| When can it be switched on? | **Week 1 only** |
| When can it be switched off? | **Week 12** |
| What happens to the intake when it closes? | **Every junior is automatically promoted and placed on the transfer list** |

| # | Task | State |
|---|---|---|
| 1 | School state per club: active, since when | ✅ `Team.juniorSchoolActive` + `juniorSchoolSinceSeason`, both nullable |
| 2 | One-off activation cost as a ledger entry | ✅ new `FinanceCategory.JUNIOR_SCHOOL`, charged on open |
| 3 | Weekly upkeep as a ledger expense | ✅ in `WeeklyFinanceService`, **not** charged in the opening week — that week is the fee's |
| 4 | Open **only** in week 1, close **only** in week 12 | ✅ the guard is the feature, and the refusal names the week it wants |
| 5 | **Intake runs only for human clubs with an active school** | ✅ the single biggest behavioural change in the sprint |
| 6 | Deactivation auto-promotes every junior **and lists each one** | ✅ — and it needed its own path, see below |
| 7 | UI: a toggle with the week window and the price shown | ✅ panel on the academy page, verified at 1280×900 and 390×844 |
| 8 | A club whose school is off has no intake, so it needs the market or scouting | ✅ by construction |

**Pricing:** one-off `5,000 + reputation × 900`, weekly `1,200 + reputation × 120`, both rounded to two
decimals **at the source** rather than at the screen. A flat fee either locks a small club out of its
own academy or is free to a large one, and the academy is the feature that makes a small club's season
mean anything. A full season of upkeep is deliberately worth more than the activation fee, so the two
figures cannot tell a manager opposite stories about what running a school costs.

**Scout wages are still not charged here** — a scout is a `StaffMember` and his wage is already on
`STAFF_WAGES` from Sprint 2.2. A second charge would bill the same man twice.

#### The mobile bug underneath it, which is not only ours — see S8.3a

Building the panel surfaced a **project-wide** overflow: every `.fm-panel` is `box-sizing:
content-box` with 14px of padding, so on a 390px phone each panel's total box is ~404px inside a
374px parent. The action button runs off the right edge, and `html { overflow-x: hidden }` makes it
**unreachable rather than obviously broken** — `scrollWidth > innerWidth` reports `false`, so the
usual check says the page is fine while a control is sitting off-screen.

The school panel sets `box-sizing: border-box` on itself, because a button you cannot tap is not a
cosmetic issue. **The other panels still overflow.** Filed as **S8.3a**.

A second cascade lesson, the same one that caught the top bar: **`@import` hoists the imported sheets
to the top of `dashboard.css`, so its own ~2,600 rules come *after* `overrides.css` and win on source
order.** A mobile `grid-template-columns` in `overrides.css` lost to
`.fm-medical-stat-grid { repeat(auto-fit, …) }` for exactly that reason and needed `!important`,
which is the same remedy the responsive utilities already use.

#### The bug that would have made the feature inert: a school could not graduate anyone

Closing the school routes each junior through `transferListJunior`, which enforces the *"decisions open
from next season"* lock — the rule that stops a manager impulsively deciding the fate of a prospect he
signed three days ago. But a school is opened in **week 1** and closed in **week 12 of the same
season**, so every junior in the intake was locked. **Closing the school graduated nobody, in the only
season the feature is used.** It surfaced as an `UnexpectedRollbackException` in the new test, not as
a wrong count, which is a good argument for testing the path rather than the endpoint.

`graduateForSchoolClosure` now exists alongside the locked path. The lock is right for a manager's
button and wrong for a scheduled season-end decision, and the two are not the same operation.

Also removed: a `catch (RuntimeException)` around each graduation. Swallowing an exception inside a
transaction marks it rollback-only, so the caller fails on commit with a rollback error that names
neither the junior nor the reason. One bad prospect now fails loudly.

**Two consequences worth stating plainly, because they are the point of the feature rather than
side-effects:**

1. **A club can end a season with no youth pipeline at all.** Shutting the school in week 12 dumps
   the intake onto the transfer list. That is a real, expensive mistake a manager can make, and the
   feature is worthless without it.
2. **Intake is now a purchase, so the graduation window interacts with money.** A club that cannot
   afford the school in week 1 has no juniors in week 2, and the first intake it gets is a full year
   away. The 10-junior cap and the 15–20 graduation window are unchanged — the owner ruled the
   **distribution** stays exactly as it is — but *whether there is anyone in the academy at all* is now
   a decision.

#### 🟡 Adjacent, and NOT part of this sprint: the takeover mechanic

Answering the AI question surfaced a second rule that has **no backlog entry at all** and is not
Sprint 5 work:

> When a human takes over a bot club's slot, the bot squad's **players all become free agents and go
> onto the transfer list**, and the human **gets a fresh team**. Bot clubs keep their squad and simply
> train at the default pace; weak ones relegate normally.

That is a club-takeover feature — inherited name and competition slot, inherited *nothing else*. It
touches user↔team binding (the same name-match fragility as S8.6 item 7), squad generation, free-agent
market supply and the transfer list. It is recorded here so it is not lost, and it should be its own
task with its own brief rather than folded into an academy sprint.

| # | Task | State |
|---|---|---|
### S5.3 — Academy structure 🟡 5 of 8 done (2 removed, 1 dropped)

| # | Task | State |
|---|---|---|
| 1 | Position known at **intake**, not rolled at promotion | ✅ `Junior.position`, set in both intake paths, read by `createSeniorFromJunior`. Six legacy rows repaired on boot |
| 2 | Individual junior training focus, integrated with Sprint 4 | ❌ **removed 2026-09-28** — depended on S4.1, which the owner deleted. Junior training follows Advanced Training like everyone else |
| 3 | Junior attributes visible as a profile | ✅ preferred foot, height, weight, personality, work rate. **`injurySusceptibility` was cut on the owner's instruction** rather than wired |
| 4 | **Mid-season promotion** — remove the artificial one-season freeze | ✅ `f22ee1a` — graduation window 15–20, once a year, legacy distribution byte-identical |
| 5 | Academy quality from `Stadium.youthLevel` + the `YOUTH_COACH` staff member | ✅ `AcademyQuality` — `1 + .15` per deviation, bounded `0.70–1.30`, neutral when unset. `YOUTH` is now a purchasable facility and counts toward upkeep |
| 6 | Raise or make configurable the 10-active-junior cap | ❌ still a hard cap |
| 7 | Continuity on promotion — carry junior progress into the senior system | 🟡 `academySkillExact` + `lastWeeklyDelta` exist and `promotedPlayer` links back, but the growth history stops at promotion |
| 8 | Personality, work rate, professionalism as growth inputs | ✅ work rate and personality drive growth. **Professionalism was never added** — it was named in the original list and has no field, no growth term and no UI. Dropped rather than left as a ❌ |

**Item 4 is the constraint to respect.** The owner ruled that the legacy promotion **distribution**
stays exactly as it is, random budget spread, goalkeeper bias and the per-skill cap of 10 included,
and `YouthAcademyGraduationTest` locks it. Anything added here — position at intake, academy quality,
a personality roll — may change *who* graduates and *what position* they play, but must not quietly
change the quality distribution. If a new input starts skewing it, that is a bug, not a tuning win.

---

### S5.4 — Junior pathways 🟡 2 of 5 done

| # | Task | State |
|---|---|---|
| 1 | **Loan out** prospects (Sprint 3) | 🟡 `LoanService` is complete; the junior-facing path is not wired |
| 2 | Release to free agency with a small compensation | ✅ `releaseJunior` |
| 3 | Junior market: AI clubs buy your listed prospects | ✅ `transferListJunior` |
| 4 | Academy graduates visible on the global market board | ❌ |
| 5 | Multi-season tracking: a junior's development history across seasons | ❌ |

**Verify (when built):** scouting Serbia, Brazil and England produces different quality intakes;
prospects develop at visibly different rates; loans give young players minutes.

---

### Sprint 5 exit criteria — ❌ NOT MET (0 of 4 features complete)

| Criterion | Result |
|---|---|
| A club can assign scouts to countries and the assignment changes what it finds | 🟡 the network and its reach model are built and tested; the reports it feeds are S5.2 |
| A junior's talent is a band that firms up, and it is paid information | ✅ S5.2 — the gate is wired, the raw field is gone, the band narrows with observation |
| The academy is a purchase, not a default | ✅ S5.3a — one-off plus weekly, week 1 open / week 12 close, closing graduates the intake |
| No AI club produces a junior | ✅ S5.3a — intake is gated on `humanControlled` **and** an active school |
| `Country.youthRating` is read by the simulation | ✅ read by `ScoutingService.reach` — unread since Sprint 2.1 until now |
| A junior's report is an estimate that firms up, not a number on day one | ✅ S5.2 — `TalentRange`, ±(1+rnd3) narrowing to ±1 |
| A junior has a position from intake | ✅ Sprint 5.3 — a goalkeeper cannot come out of the academy by surprise |
| A junior is signed into the first team in a registration window, not mid-season | ✅ Sprint 5.3 — weeks 1–2 only, with the age ceiling overriding it |
| Academy quality (`youthLevel` + youth coach) affects development | ✅ S5.3 — and `YOUTH` is now a **purchasable** facility, which it was not |
| Graduation distribution unchanged from the legacy model | ✅ `YouthAcademyGraduationTest` holds |

**Progress: 3 of 4 features substantially done** (S5.1 network, S5.2 talent reports, S5.3a junior
school). S5.3's remaining items are the academy's internal quality, and S5.2's reports on foreign
prospects are the obvious next slice.

**The gap now, in one sentence:** a scouting network nobody can read and a talent band with no
prospects to put it on. Both screens are API-only.

**Planned order, and why:**

1. ~~**S5.1** — scouting network.~~ ✅ the reach model is built and tested; **the reports it feeds are
   still missing**, and they are the reason the network exists.
2. ~~**S5.2** — report uncertainty.~~ ✅ the band, the gate and the intake roll are live.
3. ~~**S5.3 item 5** — academy quality.~~ ✅ done. Item 1 (position at intake) ✅ and the registration
   window ✅ landed with it.
4. ~~**UI for the junior school**~~ ✅ done — panel on the academy page, window stated in words, close
   confirmed by name and count. **The scouting network is still API-only.**
5. ~~**S5.3 item 2** — junior training focus.~~ **Dropped 2026-09-28** with S4.1.
6. **S5.4 item 1** — loan out, which is a wiring job on a service that already exists.

---

# Sprint 6 — Delete the dead code

**Effort:** 1 day remaining · **Type:** `DELETE` · **Independent — can run any time**

> ✅ **S6.1 – S6.5 are DONE — engines, frontend, stubs and docs, 2026-09-26/27.** 125 files moved to
> `footballForDelete/`, the eight stub services and two empty controllers deleted, `PROPOSAL_PROGRESS.md`
> / `THREAT_OVERRIDE_SPEC.md` / `UI_FOOTBALL_MANAGER.md` removed, `mvn compile` clean, `mvn test` 549/549
> green. See `footballForDelete/README.md` and `sprintProgress.md`.
>
> What was quarantined: `engine_v1/` (12 files, ~9,775 LOC), the v2 `newLogic/engine/` package
> (26 files, ~4,563 LOC), `NewMatchController`, 8 dead services, `RuntimeSaveToDB`, `util/events/` (6),
> `tools/SimulationRunner`, `old/` (4), the v2 `model/MatchState` + `MatchRuntime`, `demo/swingUIDemo/`
> (55), and 8 orphaned frontend files.
>
> **Three things genuinely remain,** all re-verified on 2026-09-27 rather than taken on trust: the
> orphan POJOs `TrainingAssignment` / `Crowd` / `Referee`, the 6 dead `/training/*` endpoints, and
> **`ENGINE.md`** — the one-page statement of which engine is live. That last one has caused more wasted
> time than everything else in this sprint combined.

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
> **Stance recorded 2026-09-28; the decision is the owner's.** `demo/service/` is **97 Java files** — a
> complete, working, unwired second engine. It **stays** for now, because `MatchBatchRunner` and
> `MatchChainTrace` are still useful as batch diagnostics. Deleting 97 files deserves its own commit
> and its own decision rather than riding along on a frontend sweep. `ENGINE.md` now states the live
> path explicitly, which is the part of this item that was actually urgent.

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

### S6.4 — Delete the stub services and empty controllers ✅ DONE (re-audited 2026-09-27)

Verified gone from the source: `CompetitionController`, `StadiumController`, `LeagueService`,
`TrainingService`, `StadiumService`, `DummyDataService`, `PlayerConditionService` and
`PlayerSkillProgressionService`. The inverted-talent `PlayerSkillProgressionService` is gone too,
which was the one deletion here that could have changed behaviour.

| Item | Lines | Result |
|---|---:|---|
| `CompetitionController` | 7 | ✅ deleted — league admin is served by the country/season controllers |
| `StadiumController` | 9 | ✅ deleted |
| `LeagueService` | 9 | ✅ deleted — `SeasonService` is the live path |
| `TrainingService` | 21 | ✅ deleted — `TrainingProgressionService` is the live path |
| `StadiumService` | 7 | ✅ deleted |
| `DummyDataService` | 172 | ✅ deleted |
| `PlayerConditionService` | 40 | ✅ deleted rather than wired; Sprint 2.6 solved the problem properly |
| `PlayerSkillProgressionService` | 72 | ✅ deleted — the live path is Sprint 4's |
| `model/TrainingAssignment` | — | ❌ **still an orphan POJO** |
| `Crowd`, `Referee` entities | — | ❌ **still orphans, never read** |
| 6 dead `/training/*` endpoints | — | ❌ still present |
| `AGENTS.md` rewrite | — | 🟡 see S6.5 |

> This entry said "still open" until 2026-09-27. The services were in fact deleted with the rest of
> the quarantine; the item was never revisited. The three orphan POJOs and the dead training
> endpoints are the real remainder, and they are small.

---

### S6.5 — Rewrite the documentation 🟡 MOSTLY DONE (re-audited 2026-09-27)

| # | Task | Result |
|---|---|---|
| 1 | Rewrite `AGENTS.md` — was 92 KB | 🟡 **1,036 lines**, down from ~2,600. Still above the 400–600 target and it still describes `demo/service/` and `newLogic/` engines at length |
| 2 | Delete or archive `PROPOSAL_PROGRESS.md` (110 KB) | ✅ **gone** |
| 3 | Delete `THREAT_OVERRIDE_SPEC.md` (38 KB) | ✅ **gone** |
| 4 | Fix the 3 documented-but-inactive claims | ✅ moot — the file is deleted |
| 5 | `UI_FOOTBALL_MANAGER.md` — remove "✅ U potpunosti implementiran" | ✅ **file deleted** |
| 6 | Correct the goal-mouth note (10 m, not 14 m) | ✅ the 10 m figure is used consistently |
| 7 | Add an `ENGINE.md` stating which engine is live | ✅ **done 2026-09-28** |
| 8 | Keep `expertAudit.md` and `sprintBacklog.md` as the living docs | ✅ |

**`ENGINE.md` is now written.** The single most expensive recurring mistake in this project was not
knowing which engine was live: there have been three `MatchOrchestrator`/`MatchState` name collisions,
a dashboard button called `start-realistic-demo-btn` for historical reasons, and a viewer under
`static/demo/service/ui/proposal/`.

The first draft of it **stated three packages that no longer exist** — `engines/`, `cleanSheet/`,
`old/` and `/api/v2/match` were all deleted in Sprint 6, and the draft was written from `AGENTS.md`
rather than from the tree. Everything in the file is now verified against the source. Worth
recording because it is the exact failure the file was written to prevent: **a document about which
code is live is itself documentation, and goes stale the same way.**

#### ✅ S8.3 #6 — two of the three "dead" items were decisions, not deletions (2026-09-28)

Checked each before deleting, and the backlog was wrong about two of the three.

**`emptyStateHtml` (in `ui/components.js`, not `utils.js`) — deleted.** Zero references anywhere. The
only importer of that module takes `backButtonHtml`.

**`loadRecentLeagueMatches` was not dead code — it was a disabled feature, and the owner has since confirmed Recent Matches covers it and asked for this to go (2026-09-28).** Deleted: the commented markup, the commented call, and the 67-line function. The function exists, the
container `#recent-league-matches-list` is written into the dashboard markup, and the only call is
commented out at `dashboard.js:563`. The markup itself is inside an `<!-- -->` block, so the "Recent
League Results" section was deliberately switched off, most likely during the `pages.js` refactor.
**Delete or restore is the owner's call, and it was skipped rather than guessed** — deleting a
section that was only disabled by accident throws away working code, and restoring one that was
disabled on purpose puts back something the owner removed.

**`resolveFixtureStadiumImage` is a symptom of a bigger hole.** It maps a stadium *name* to an image
by substring (`livadice`, `dunjareal`, `bilino`) and is never called. Investigating why turned up
something worse:

> **`Stadium` has no `image` field at all.** `stadium-view.js` reads `s.image || '/images/default-stadium.png'`,
> and nothing in the backend ever sends an `image` key. So the stadium page has **never** been able to
> show a real ground — it has always fallen through to the default, which until today was itself a
> **404**, meaning a broken-image glyph rather than a placeholder.

Three real ground images (`livadice.png`, `dunjareal.png`, `bilinopolje.png`) are reachable only
through that dead name-matching function, so all three are effectively orphaned assets.

**The fix, when it is wanted, is the same shape as S8.3 #7**: stop inferring from names. Add a real
image field to `Stadium`, populate it for the grounds that have artwork, serve it on both the stadium
and fixture payloads, and delete the substring matcher. That touches the schema, which is why it was
not done unattended.

| Sub-item | Status |
|---|---|
| `emptyStateHtml` | ✅ deleted |
| `loadRecentLeagueMatches` / "Recent League Results" section | ⏸ owner decision — restore or remove |
| Stadium image as data (schema change) | ⬜ open, scoped above |

---

### Stadium picture as a field + manager upload — ✅ DONE 2026-09-28 (owner)

Owner instruction: *"svaki stadion ce imati sliku, zaasad stavi fallback neka koja ima (dunjareal
stadion) tako da treba polje za sliku. omoguciti da korisnik uploaduje svoju sliku. ok for shema
change"*

**What was actually broken, which was more than "no field":**

`Stadium` had **no `image` field at all**, and the stadium page read `s.image` from a payload that
never contained an `image` key. So the ground page has **never once** shown a real ground. It always
fell through to a default that was itself a **404**, which — with the `onerror` handler pointing at
the same missing file — rendered as a browser broken-image glyph on every stadium in the game.

| Piece | What it does |
|---|---|
| `Stadium.image` | The column. Null means "use the fallback" |
| `StadiumImageService.DEFAULT_STADIUM_IMAGE` | `/images/dunjareal.png` — a real photograph, per the owner. **Not** the generated placeholder: a stadium with no artwork should look like a football ground, and a grey gradient reads as a broken image |
| `POST /api/teams/{teamId}/stadium/image` | The upload, multipart |
| `UploadResourceConfig` | Serves `/uploads/**` from a real directory |
| `StadiumSettingsController` | Sends `image` on the payload — the key the view already read |
| `TeamController` schedule row | Sends `stadiumImage`, so fixtures show the right ground |
| `resolveFixtureStadiumImage` | **Deleted.** The substring matcher that was never called |

**Two traps worth writing down, because both fail quietly:**

1. **Where the file goes.** Everything under `src/main/resources/static` is served from the
   **classpath**, and that copy is made at build time. An upload written into the source directory is
   stored successfully and then 404s until the next rebuild — and in a packaged jar the directory is
   not writable at all. Uploads therefore go to `app.uploads.dir` (default `./uploads`), served by a
   resource handler. Verified: the file is written to `uploads/stadiums/` and **not** into `static/`.
2. **`/uploads/**` must be public.** A browser loads an `<img>` with no `Authorization` header, so a
   JWT-gated upload would store the file and then render as a **302 on every page**. The `GET` is
   public like `/images/**`; the `POST` that writes a file still checks the manager owns the club.

**Ownership, verified live rather than assumed:** Velja manages **Omladinac (1)**, Kecko manages
**Sremac (2)** — both clubs are seeded as human-controlled, which nearly caused a false alarm here.
Upload to team 1 → 200. Upload to team 2 → **403**. A `text/plain` file → **400** with a message, not
a 500. The `GET` of an accepted upload → **200**, real PNG bytes.

**Safe by construction:** the stored filename is a **UUID**, never the manager's — a client-supplied
name is a path-traversal vector and gets reflected back into markup. The extension comes from the
content type. It is an **allow-list** (`png`, `jpeg`, `webp`, `gif`), and **`image/svg+xml` is
excluded on purpose**: SVG is a document that can carry script, and the file is served from this
origin to everyone who views the stadium.

**Tests: 8** — fallback when there is no artwork and when there is no stadium row at all, own artwork
winning, the file landing outside the classpath, the manager's filename being ignored, non-image and
SVG and empty uploads all refused, and a club with no stadium row getting one so the manager is not
told to go and create a stadium. **Full suite 670.**

---

### Sprint 6 exit criteria — 🟡 MET IN SPIRIT, THREE ITEMS OPEN

The sprint's purpose was "a reader can tell what is live". That is achieved — the dead engines are
gone, the stubs are gone and the runaway documents are deleted. The three open items are small and
one of them matters more than its size suggests.

| Criterion | Result |
|---|---|
| Only one match engine remains | ✅ 125 files quarantined, `mvn compile` clean |
| No dead frontend pages | ✅ 8 orphaned files removed, `navigation.js` and `training.js` among them |
| No stub services or empty controllers | ✅ all 8 deleted |
| No orphan POJOs | ❌ `TrainingAssignment`, `Crowd`, `Referee` |
| No dead endpoints | ❌ 6 dead `/training/*` routes |
| Documentation describes the current system | 🟡 `AGENTS.md` is a third of its old size but still over target |
| **A reader can tell which engine is live in one place** | ✅ **`ENGINE.md` written 2026-09-28** |

**The `ENGINE.md` rule, for whoever picks this up:** write down the live engine package, the entry
point class, the entry point *endpoint*, the dashboard button id even where it is historically
misnamed, and the viewer path. Five lines. Every hour lost to "which engine is this?" in this project
would have been avoided by those five lines.

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

### S7.1 — Add a regression test that all fixtures use the proposal engine ❌ not started

| # | Task | State |
|---|---|---|
| 1 | Assert `SimMatchService` is the only class that produces a `Match` result for a played fixture | ❌ |
| 2 | Assert `AsyncSimulationRunner` and `SimulationController.simulateCurrentRound` both go through `SimMatchService` | ❌ |
| 3 | Add a static check that fails if any class outside `sim/` calls into a simulation loop directly | ❌ — this is the guard that would have caught the original rot |
| 4 | Document in `ENGINE.md` which engine is live | ❌ — see S6.5 item 7, same deliverable |

**Verify:** the test fails if you reintroduce `engine_v1/MatchEngine` or `newLogic/engine/MatchSimulator`.

**Recommended right after Sprint 5.** It is 1–2 days, it is the cheapest insurance in the document,
and item 4 is shared with the one piece of S6.5 still open.

---

### S7.2 — Make AI-vs-AI fixtures inspectable ❌ not started

The engine is consistent; the only real gap is observability. AI fixtures are simulated with replay
recording suppressed, so unlike your own match they cannot be watched afterwards.

| # | Task | State |
|---|---|---|
| 1 | Offer to record a replay for a selection of AI fixtures so the user can review a neighbouring match | ❌ |
| 2 | Move the "results hidden until you play your match" gate server-side; the current `sessionStorage.dashboardWeekConsumed` check is client-side only | ❌ |
| 3 | Add a post-round summary showing which AI fixtures were close | ❌ |

**Verify:** a simulated round is followed by a summary; selected AI fixtures are watchable in the
same viewer as your own match.

---

### Sprint 7 exit criteria — ❌ NOT MET

- [ ] A test fails if a second simulation path is reintroduced
- [ ] `ENGINE.md` names the live engine, the historical button id and the viewer path
- [ ] The results-hidden gate is enforced server-side
- [ ] Selected AI fixtures are watchable

---

# Sprint 8 — Presentation, integration and deployment

**Effort:** 10–12 days · **Type:** `INFRA` + `FEATURE` · **Depends on:** all
**Status: not started. Nothing in this sprint has been built.**

---

### S8.1 — Wire or delete the 13 dead routes

`results`, `cup`, `international`, `friendlies`, `playerStats`, `teamStats`, `topScorers`, `topAssists`, `coaches`, `events`, `analytics`, `upcoming`, `training` — no menu entry, no action row, console-only. **6 of them crash into a generic "API Error" card** because they `await response.json()` without checking `response.ok`.

> **Re-verified 2026-09-28, and the list stands.** Every one of the 13 **does** have a `case` in
> the `pages.js` router, and **not one of the 13 has a menu entry** — checked against `dashboard.html`
> and both nav builders in `pages-renderers.js`, all thirteen at zero. So the accurate description is
> not "dead routes" but **routed and unreachable**: the page renders, nothing links to it, and the
> only way in is to type the route. That is a slightly different problem from a dead route and worth
> keeping the distinction, because wiring the router is done and the work is entirely in navigation.
>
> The count also moved this week for a different reason: the two individual-focus training endpoints
> were deleted with S4.1, and the Recent League Results section went with its dead function. Neither
> was on this list, so 13 is still 13.

| # | Task |
|---|---|
| 1 | Wire `results`, `upcoming`, `playerStats`, `teamStats`, `topScorers`, `topAssists` — the endpoints exist, only the routing is missing |
| 2 | `cup` and `international` depend on the Cup being populated (S8.2). Until then, **remove them from the router and the menus** rather than leaving broken pages |
| 3 | `analytics` → replace the broken `zox-match-preview.html` (4 unbound tabs, unused Chart.js, doubled stylesheet) with a real analytics page, or route it into the existing match-detail view |
| 4 | Global fix: every fetch must check `response.ok` and surface a real error, not a generic card. Centralise this in `authFetch` | 🟡 the centralisation is **done** (S8.3 #2 — there is now one `authFetch`, and it handles 401, redirects and network errors). The audit of whether every caller checks `response.ok` is **not** done |
| 5 | Add `loadPage` state to the browser URL (`#/leagueTable`) so pages are linkable and the back button works |

---

### S8.2 — Cups, national teams, internationals and the weekly day schedule 🔴 EXPANDED 2026-09-28 — NOT BUILT

> **This replaces the six-line stub below.** The owner specified the whole structure on 2026-09-28:
> national teams (senior and U-21) with **elections**, World Cup qualifying, a World Cup, a domestic
> cup, three international club cups, and a **seven-day weekly schedule**. The original six items were
> a guess at the size of it. Nothing here is started.

#### What already exists (audited 2026-09-28, so the tasks are not built on sand)

| Asset | State |
|---|---|
| `SeasonCalendar` | **Already models 12 weeks × 7 days**, and **already reserves week 6 for national-team qualifiers and week 12 for the World Cup** by owner rule. League sits on **day 3 and day 7** — which is exactly the spec. This is the single most useful thing already in the codebase |
| Days 1, 2, 4, 5, 6 | **Undefined.** Day 1/4/5/6 are new work; day 2 (finance) and day 6 (form/morale) are described but not implemented as day events |
| `Country.seniorNationalTeam`, `Country.u21NationalTeam` | Entity fields and DB columns **exist** — and are `NULL` for all 9 countries. No national team has ever been created |
| `CompetitionType` | `LEAGUE`, `CUP` |
| `MatchFixture` | Carries `seasonYear`, `roundNumber`, `weekNumber`, `matchDate` (`LocalDateTime`), `played`, `playedMatch` |
| `Kup Srbije` competition row | **Exists** with `teamsPerCompetition = 64`, `hasSeeding = true` — and is never populated or scheduled |
| `country-view.js` NT page | A hardcoded **placeholder** that prints "Placeholder / Later / Backend data" |
| Ranking list | **Does not exist at all** — not for nations, not for clubs. *Everything in this section depends on it* |

**The two structural gaps that gate the whole thing:** there is no **ranking list**, and there is no
**day-level scheduling**. Both are Part 0, and neither is optional.

---

#### Owner decisions — 2026-09-28 (all 14 questions answered)

Recorded here because they are the design, and the tasks below are the consequence.

**Ranking — three separate lists, and the answer is Elo.** Club ranking, senior NT ranking and U-21 NT
ranking are **independent**. The rules:

| Rule | Detail |
|---|---|
| Tier seeding | A club from a higher tier sits **higher in ranking** than any club from a lower league. Ranking is global across all tiers, not per-league |
| National start | NT and U-21 NT **begin at equal ratings** |
| NT movement | Senior ranking moves **only** on NT results. Beating a higher-ranked nation is worth more; losing to a lower-ranked nation costs more |
| WC > qualifying | A World Cup win is worth more than a qualifying win, plus a separate bonus for qualifying at all |
| Club movement | Clubs gain and lose rating in the matches they play |
| One list, two uses | A club's rating drives **both** its within-country position (across all tiers) **and** its international position. The owner's own illustration: a tier-5 club beating a tier-1 club in the cup is an enormous rating gain |
| Upsets | Handled by the rating maths, not a special case — this is what an upset *is* |
| Match value | **international cup > league > cup > friendly** |

**Elections — once per season, one-season mandate, secret ballot.**

| Rule | Detail |
|---|---|
| Registration | Opens **week 12 day 1** of the preceding season. **New candidates may still register during week 1**, after voting has already opened |
| Withdrawal | A candidate may **withdraw** their application |
| Voting | Starts **week 1 day 1**. Every user **of that country** votes |
| Dual role | A user may be **candidate and voter** |
| Changeable | A vote **can be changed** after casting |
| Registration window | Opens **week 12 day 1**; **new candidates may still register during week 1**, even after voting has opened |
| Deadline | Voting closes **week 1 day 7 at midday** and the winner is declared, becoming selector. *(The owner confirmed this is what the original "week 7 12:00 PM" meant.)* |
| Secrecy | **Results are invisible until declared.** Only admin/owner can see the count |
| Mandate | **One season**, then re-election. **Admins can annul an election** if they find an irregularity |
| Cadence | **Every season** (four a year) |

**Selector powers.** The selector picks the **25-player pool**, the **starting XI and substitutes**,
and **sets the tactics**.

**Player eligibility.** Any player of that nationality may be in the 25 — no conditions. A player may be
in the 25 and still be **unavailable for a specific match**: injured, suspended on an even number of
yellows, or red-carded. An **observe list** is wanted alongside the 25, for tracking players who might
soon be promoted into it. (See Q-B4 on whether the observe list is in scope now.)

**Call-up confirmation.** The **first** call-up is **approved automatically by the system**; **every
subsequent** call-up must be **confirmed by the club manager**. (See Q-B2 — what happens on refusal.)

**Cup final slot.** Week 11, but in the **cup slot, day 5** — the league's day 3 and day 7 are
untouched, so there is no collision with the week-11 playoff. Cup matches are a **day-5 slot** in
general.

**Schedule template.** Every country has the **same** template. The design allows **adding events to
the template** when something happens — the NT and U-21 qualifying draw, the cup draw, the playoff
draw. A country with no international fixture that week still shows the same week, with that day
simply empty.

**International clubs — by tier, with a promotion rule.** Entered by tier. A **tier-2 champion plays
Champions Cup tier 1** as the owner's baseline; if a club climbs in the ranking above where its tier
would place it, it plays in the **higher** cup. **Masters Cup** takes 2nd and 3rd, **Challenge Cup**
takes 4th. (See Q-B3 — the tier naming needs confirming before any bracket is designed.)

**Out of scope now.** The junior match on day 6 — confirmed deferred. The international cup **draw and
system** — the owner deferred it to its own task, so Part 5 stops at the entry list.

**My answer to "how should the random primitive work" (Q11), since the owner asked my view:** one
shared, **seeded** random utility used by every coin-flip and tie-break. Two reasons. It is
**reproducible** — a bracket or an election that went wrong can be replayed exactly, which is otherwise
impossible. And it is **testable** — a rigged constant in a tie-break is the single worst bug this
feature set can produce, because it silently decides outcomes and never announces itself. A single
`Random` seed recorded alongside the result means "why did this team go out?" has an answer. The
election tie ("plain random" between candidates on equal votes) and the tournament tie (points → goal
difference → goals scored → coin) both go through it, and the seed is stored on the tie so the
outcome is auditable afterwards.

#### The 48 nations (owner list, 2026-09-28)

**English names, three-letter codes.** The seeding of clubs, leagues and players is a **separate job
done 1-by-1 at the end**, once the application works. The nine countries in the database are enough
to build and test against until then.

| # | Country | Code | Its domestic league (when seeded) |
|---|---|---|---|
| 1 | **Serbia** | `SRB` | Serbia — Superliga |
| 2 | **Croatia** | `CRO` | Croatia — HNL |
| 3 | **Bosnia and Herzegovina** | `BIH` | Bosnia — Premier League |
| 4 | **Montenegro** | `MNE` | Montenegro — Prva liga |
| 5 | **North Macedonia** | `MKD` | Macedonia — First League |
| 6 | **Slovenia** | `SVN` | Slovenia — PrvaLiga |
| 7 | **Hungary** | `HUN` | Hungary — Nemzeti Bajnoksag |
| 8 | **Romania** | `ROU` | Romania — Liga I |
| 9 | **Bulgaria** | `BUL` | Bulgaria — First League |
| 10 | **Greece** | `GRE` | Greece — Super League |
| 11 | **Italy** | `ITA` | Italy — Serie A |
| 12 | **Austria** | `AUT` | Austria — Bundesliga |
| 13 | **France** | `FRA` | France — Ligue 1 |
| 14 | **Spain** | `ESP` | Spain — La Liga |
| 15 | **Portugal** | `POR` | Portugal — Primeira Liga |
| 16 | **Switzerland** | `SUI` | Switzerland — Super League |
| 17 | **Germany** | `GER` | Germany — Bundesliga |
| 18 | **Poland** | `POL` | Poland — Ekstraklasa |
| 19 | **Czechia** | `CZE` | Czechia — Czech First League |
| 20 | **Slovakia** | `SVK` | Slovakia — Nik liga |
| 21 | **Russia** | `RUS` | Russia — Premier League |
| 22 | **Netherlands** | `NED` | Netherlands — Eredivisie |
| 23 | **Belgium** | `BEL` | Belgium — Pro League |
| 24 | **Turkey** | `TUR` | Turkey — Super Lig |
| 25 | **England** | `ENG` | England — Premier League |
| 26 | **Scotland** | `SCO` | Scotland — Premiership |
| 27 | **Northern Ireland** | `NIR` | Northern Ireland — Premiership |
| 28 | **Ireland** | `IRL` | Ireland — Premier Division |
| 29 | **Denmark** | `DEN` | Denmark — Superliga |
| 30 | **Norway** | `NOR` | Norway — Eliteserien |
| 31 | **Sweden** | `SWE` | Sweden — Allsvenskan |
| 32 | **Finland** | `FIN` | Finland — Veikkausliiga |
| 33 | **United States** | `USA` | United States — MLS |
| 34 | **Canada** | `CAN` | Canada — CPL |
| 35 | **Australia** | `AUS` | Australia — A-League |
| 36 | **Brazil** | `BRA` | Brazil — Serie A |
| 37 | **Argentina** | `ARG` | Argentina — Primera Division |
| 38 | **Uruguay** | `URU` | Uruguay — Primera Division |
| 39 | **China** | `CHN` | China — Chinese Super League |
| 40 | **Japan** | `JPN` | Japan — J1 League |
| 41 | **Morocco** | `MAR` | Morocco — Botola Pro |
| 42 | **Egypt** | `EGY` | Egypt — Premier League |
| 43 | **India** | `IND` | India — Indian Super League |
| 44 | **Mauritius** | `MRI` | Mauritius — Mauritius League |
| 45 | **Georgia** | `GEO` | Georgia — Erovnuli Liga |
| 46 | **Saudi Arabia** | `KSA` | Saudi Arabia — Saudi Pro League |
| 47 | **Qatar** | `QAT` | Qatar — Qatar Stars League |
| 48 | **Other Nations** | `OTH` | the remaining nations of the world, ranked and fielded as nations but without a domestic league of their own |

**Exactly 48, so 47 named plus "Other Nations"** — which is a real entry, not a filler: it holds the
rest of the world, is ranked and fielded like any other nation, and simply has no league of its own
generating players.

> **Two code collisions to be careful about.** **Greece is `GRE` and Georgia is `GEO`** — neither may
> use `GRU`, and the country-view flag badge derives an emoji from the code, so a wrong code there
> shows the wrong flag. Separately, three codes in the database today are **non-standard and will
> change**: Croatia is `HRV` (should be `CRO`), Germany is `DEU` (should be `GER`) and England is
> `GBR` (should be `ENG`). `ENG` rather than `GBR` is deliberate — England and the United Kingdom are
> different football nations, and this list includes both Scotland and Northern Ireland separately.

#### 🔴 The whole system must be country-agnostic, and today it is not

Owner's requirement: *"ceo sistem koji napravis — sistem liga, nt, ntu21, kup itd — to sve treba da
radi ISTO za bilo koju zemlju i da se bira iz korisnikovog polja country pri registraciji."*

**Audited 2026-09-28, and there is no such field.** `RegisterRequestDTO` carries only
`username`, `email` and `password`. **`User` has no `country` column at all.** A user's country is
currently *derived* — `/auth/me` returns `countryIsoCode` and `countryName` by looking up the **club
they manage**, so a manager gets a country as a consequence of picking a club rather than by choosing
one.

| # | Task |
|---|---|
| 0.4a | **`User.countryCode`** — a real column, chosen at registration | ✅ done 2026-09-28 |
| 0.4b | A country picker on the registration form | ✅ done — populated from `/countries/catalog`, unseeded countries listed but not selectable |
| 0.4c | Club reservation scoped to the chosen country | ✅ done — the club is reserved from that country's leagues at submission |
| 0.4c2 | 🔴 **The club is actually linked on approval** | ✅ done — `approveRequest` now creates the `CTeam` the user is resolved through |
| 0.4c3 | 🔴 **`POST /auth/register` did not exist** | ✅ added |
| 0.4c4 | 🔴 **The admin queue and approve/reject did not exist** | ✅ added — `GET /admin/registration-requests`, `POST /admin/registration-requests/{id}/{approve,reject}` |
| 0.4d | Reads scope by that country | 🟡 **transfer market done** — country-scoped with a **filter**, cross-border signing untouched. Leagues and competitions still resolve through the manager's own club |
| 0.4e | Audit for hardcoded country or league assumptions | 🟡 seeding stays Serbian **by the owner's instruction** — it is test data. Read paths audited: league, country page and active-league resolution were already driven by the manager's own club |

> **This is the single most important architectural item in the section**, and it is cheap to get
> right now and expensive to retrofit. Every table that will be built in Parts 1–7 gets a country
> scope, and getting one of them wrong produces a Serbian league that a manager in Qatar can see and
> a transfer market that crosses borders for free.

#### Cup and international competitions need their own page

Owner's requirement: *"Kup (kao i internacionalna takmičenja) moraju imati svoju stranu gde se vidi
tabela ako postoji (kup nema tabelu), rezultati po rundama, schedule."*

| # | Task |
|---|---|
| 8.1 | A page per competition — cup, Champions Cup, Masters, Challenge, and each national tournament |
| 8.2 | **League table where one exists**; a cup shows **no table**, and must not render an empty one |
| 8.3 | **Results by round** — 1/16, 1/8, quarter, semi, final as a bracket or as a list per round |
| 8.4 | **Schedule** — the same day-by-day view as the country page, scoped to that competition |
| 8.5 | Reachable from the country page, so a manager never has to guess a URL |

#### Part 0 — Foundations (do these first; everything else is downstream)

| # | Task | Notes |
|---|---|---|
| 0.1 | **National ranking (senior and U-21 — two independent lists)** | **Elo, decided by the owner.** Moves only on NT results. Beating a higher-ranked nation pays more; losing to a lower-ranked one costs more. WC win > qualifying win, plus a qualifying bonus. Senior and U-21 start **equal**. Ties on equal rating broken by the shared random |
| 0.2 | **Club ranking list** | **Elo**, driven by the same match-value order: **international cup > league > cup > friendly**. Tier gives a starting offset so a tier-1 club outranks any tier-5 club. One list serves both the within-country order and the international order. Rating changes are recorded per match so an upset is visible as an event |
| 0.3 | Create the national team entities | 2 per country. `Country.seniorNationalTeam` / `u21NationalTeam` are already there waiting and are `null` for all 9 countries. They are **not clubs**: no league, no transfers, no wages — but they need players |
| 0.3a | Seed ~48 nations with their own leagues and clubs | **Deferred by the owner to a separate, 1-by-1 job at the END**, once the application works. The 9 in the database are enough to build and test against until then. Exactly **48**: 47 named plus "Other Nations". The 9 currently in the database are **Serbia plus neighbours** and carry **Serbian names** — they will be renamed to the English list above |
| 0.4 | **Day-level calendar slots** | `SeasonCalendar` has the days; it needs **named slots** for day 1 (international 20:45), day 2 (finance), day 4 (training), day 5 (cup 18:00), day 6 (form/morale + junior). With room to add more — the owner asked for space to grow |
| 0.5 | **Knockout resolution: penalties** | 90 minutes, then a shootout. The engine produces a 90-minute score; a shootout is a **new concept** that must not leak into league results |
| 0.6 | **Draw engine: seeded pots and byes** | Potted draws, one team per pot per group, and a bracket that handles a non-power-of-two field (256 is fine; 202 direct entrants is not) |
| 0.7 | **Tie-breaker chain + one shared seeded random** | Points → goal difference → goals scored → coin. The **same** primitive serves the election tie. The seed is **stored on the result** so any tie-break is replayable and auditable — see the owner's "how should it work" answer above |

> **0.5 and 0.6 are the quiet risk.** Everything above them is CRUD and scheduling. A bracket that is
> subtly wrong produces a plausible-looking tournament that quietly eliminates the right team, and
> nothing in the UI will ever say so. Both need a real test, not a smoke test.

---

#### Part 1 — National teams: elections and selection

| # | Task | Spec |
|---|---|---|
| 1.1 | NT section on the country page, containing **elections** | New section, above the existing country content |
| 1.2 | **Any user may stand as a candidate**, regardless of nationality | "because there are foreign coaches" — no nationality gate on candidacy |
| 1.3 | Candidate registers by clicking a button | Opens **week 12 day 1**; **still open during week 1**. A candidate may **withdraw**. Registering in week 12 is what makes a week-1 ballot meaningful — a candidate added on week 1 day 3 simply appears in the running |
| 1.4 | **Voting by clicking a candidate in the list** | Every user **of that country** votes. A user may be candidate **and** voter. **One vote, changeable** at any time before the deadline. Results **invisible until declared** — admin/owner may see the count |
| 1.5 | Winner = most votes; **tie broken by plain random** | Uses 0.7 |
| 1.6 | **Identical structure for U-21** | Separate ranking, separate election, separate squad — everything in 1.1–1.5 run twice |
| 1.7 | Selector sees **every player's skills** for their country — **not talent, all skills** | A deliberate difference from `PlusFeatureService`, which gates talent. A selector sees skills; **talent stays hidden** |
| 1.8 | Sortable player list: **by any skill, by wage, by value** | Sorting must be **server-side** — a country's player pool is far too large to sort in the browser |
| 1.9 | Selector marks **25 players**, changeable at any time | **No conditions** on which 25. A player may be in the 25 and still **unavailable for a given match** — injured, suspended on an even number of yellows, or red-carded. Availability is per-match, membership is not |
| 1.10 | Mandate and annulment | **One season**, then re-election. Elections every season. **Admins can annul** an election on irregularity |

---

#### Part 2 — World Cup qualifying (week 6, days 1, 2, 3, 5, 7)

| # | Task | Spec |
|---|---|---|
| 2.1 | Top **48 nations by national rating** enter | From 0.1. **Global** — the owner's correction: *"ne može biti da Srbija ima više država, Srbija je jedna država"*. The 9 countries in the database are **Serbia plus neighbours added while building**; the real set is **almost all of Europe plus the football nations of the world** |
| 2.2 | **8 groups of 6**, by seeding: **pots of 8** — pot 1, then pot 2, and so on. From each pot, **one team drawn at random into each group** | A pot is not a fixed group. Each group gets one team from every pot |
| 2.3 | **Round robin**, 5 matchdays: days **1, 2, 3, 5, 7** of week 6 | Day 4 is deliberately empty |
| 2.4 | **Home is the worse-rated side** | Not an alternation — the lower-rated side hosts every game, so it is always the weaker team's stadium |
| 2.5 | **Top 2 from each group advance** → 16 nations | 8 × 2 = 16, which is exactly a 1/16-final field. The arithmetic is self-consistent |
| 2.6 | Tie-breaker: points → goal difference → goals scored → coin | Uses 0.7 |
| 2.7 | **Identical for U-21** | |

---

#### Part 3 — The World Cup (week 12)

| # | Task | Spec |
|---|---|---|
| 3.1 | **1/16 final — day 1** | |
| 3.2 | **1/8 final — day 2** | |
| 3.3 | **Quarter-final — day 3** | |
| 3.4 | **Semi-final — day 5** | Day 4 empty |
| 3.5 | **Final and 3rd-place play-off — day 7** | |
| 3.6 | 90 minutes, then **penalties** if drawn | Uses 0.5 |
| 3.7 | Bracket from the 16 qualifiers, seeded from ranking | |
| 3.8 | **Identical for U-21** | |

---

#### Part 4 — Domestic cup (Kup Srbije)

| # | Task | Spec |
|---|---|---|
| 4.1 | **Week 1** — clubs ranked **203–310** (108 clubs) play one knockout round: **203 v 310, 204 v 309**, and so on → 54 winners | |
| 4.2 | **Week 2** — 202 direct entrants + 54 winners = **256**, play 1/256 | |
| 4.3 | **Week 3** 1/128 · **Week 4** 1/64 · **Week 5** 1/32 | |
| 4.4 | **Week 6 — no cup.** National-team qualifiers | Already reserved in `SeasonCalendar` |
| 4.5 | **Week 7** 1/16 · **Week 8** 1/8 · **Week 9** 1/4 · **Week 10** 1/2 · **Week 11 day 5** final | Five rounds across five weeks. The final is in the **day-5 cup slot**, so the league's day 3 and day 7 — including the week-11 playoff — are untouched |
| 4.6 | 90 minutes then penalties throughout | Uses 0.5 |
| 4.7 | Cup tie is a **single match** — no home/away legs anywhere in the cup | |

> **The cup weeks line up with `SeasonCalendar` exactly**, including week 6 being free. That was not a
> coincidence — the calendar already had week 6 reserved for qualifiers and week 12 for the World Cup
> from the owner's 2026-09-26 rules. Worth saying so this is not rebuilt by accident.

---

#### Part 5 — International club cups

| # | Task | Spec |
|---|---|---|
| 5.1 | **Champions Cup** — every **champion** enters | **The cup you enter is decided by the tier of the league you won, not the tier you are currently in.** The owner's example: win league tier 2 in season 1, get promoted into tier 1 for season 2 — and still play **Champions Cup tier 2**, because tier 2 is the league he won. Promotion does **not** move you up a cup |
| 5.2 | **Masters Cup** — every **2nd and 3rd** placed team enters | Per tier as well |
| 5.3 | **Challenge Cup** — every **4th** placed team enters | Per tier as well |
| 5.4 | **No rating-based promotion between cups** | Corrected 2026-09-28. An earlier reading of this rule as "climb the ranking to play a higher cup" was **wrong**. Entry tier comes from the **league won**, full stop. (Rating still decides seeding *within* a cup, and still decides the qualifying pots — just not cup entry) |
| 5.5 | Draw, bracket and schedule | **Deferred by the owner to a separate task.** Do not design it here |

> **Open and load-bearing:** which competitions feed these three cups. "All champions" across how many
> tiers and how many countries is not stated, and the answer changes the size of every bracket. See Q5.

---

#### Part 6 — The weekly schedule

| Day | Event | State |
|---|---|---|
| 1 | International 20:45 | new |
| 2 | Weekly finance update | partially exists (weekly settlement), not a day event |
| 3 | **League 19:00** | **exists** |
| 4 | Training | exists as a weekly action, not a day event |
| 5 | Cup 18:00 | new |
| 6 | Form/morale update, **eventually** a junior match | form/morale passive decay exists; the junior match is explicitly "eventually" |
| 7 | **League 16:00** | **exists** |

| # | Task | Notes |
|---|---|---|
| 6.1 | Render the week as a day-by-day schedule, with **room to add events** | **Every country has the same template.** Events can be **added to the template** when something happens — the NT and U-21 qualifying draw, the cup draw, the playoff draw. A day with nothing on it stays visibly empty rather than being hidden |
| 6.2 | Per-country schedule on the country page | ✅ done 2026-09-28 — a seven-day grid on the country page, the week resolved **server-side** so the client cannot show a stale one |

---

#### Part 7 — Wiring and rules

| # | Task |
|---|---|
| 7.1 | Replace the hardcoded NT placeholder in `country-view.js:186-208` |
| 7.2 | **Call-up confirmation flow** | The **first** call-up is **approved by the system automatically**; **every subsequent** one needs the **club manager to confirm**. This is the mechanism that stops a selector quietly stripping a rival of eleven players |
| 7.2a | **Refusal and silence** | The manager has **24 hours**. **No answer counts as approval** — silence must never read as a refusal. If the manager **refuses**, that player **cannot enter the 25 at all**; he is not merely benched, he is out of the squad |
| 7.3 | NT availability per match | Weeks 6 and 12 have no league football, so no one is absent *by the schedule*. But a called player **cannot play any other match** in weeks 6 and 12 — NT only |
| 7.4 | **National Pool (the observe list)** | **Build now — no approval needed, and no manager confirmation either.** The **25 are automatically on it**. Naming per the owner: the watch list is the **National Pool**, the 25 are the **Squad**. So the Pool contains the Squad plus anyone else being tracked for it |
| 7.5 | Election and tournament state must survive a database reset and a restart |
| 7.6 | AI nations need coaches and squads even where no human took the election — a season where nobody stood must not leave a nation with no selector |
| 7.7 | Everything the manager sees must be readable by someone who did not build it — no silent failures in brackets |

---

#### Verification, when it gets built

| Property | How it is checked |
|---|---|
| A pot is not a group | Every group has exactly one team from each pot — assert it, do not eyeball it |
| 8 groups × 6, one round robin of 5 | Every nation plays 5 qualifying matches, no more, no fewer |
| 16 qualifiers, and the cup's 256 | Field sizes are exact powers of two; a byes bug shows up as a team playing twice |
| Week 6 and week 12 carry no league | `SeasonCalendar` already enforces the intent; assert it rather than trust it |
| A draw is reproducible from a seed | Same seed, same draw — otherwise a bracket bug is unreproducible |
| The coin is fair | A tie-break must be able to go either way; a rigged constant is the worst bug in the set |



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
| 1 | **Duplicate sidebar handlers** — `app.js` and `sidebar.js` both bind `#clubSidebar`, so `loadPage` fires twice per click | ✅ **done 2026-09-28** — and the desktop accordions were **dead**, not just double-rendering. See below |
| 2 | Consolidate `escapeHtml` (3 copies) and `authFetch` (2 copies) | ✅ **done 2026-09-28** — it was **5** copies of `escapeHtml`, now one `ui/escape.js`. `authFetch` had a **behaviour** difference |
| 4 | `bindScheduleInteractions` fallback passes `seasonYear` positionally, silently dropping it | ✅ **fixed 2026-09-28** — contract unified to an options object; `ScheduleInteractionContractTest` |
| 5 | `training-view.js` — merge the two ~450-line parallel implementations | ✅ **done 2026-09-28** — not a merge: three functions were declared twice in one scope, so the first copies were **shadowed and unreachable**. 183 lines deleted |
| 6 | Delete the dead code in `pages.js` (`loadRecentLeagueMatches`, commented markup at `:428-433`) and `utils.js` (`resolveFixtureStadiumImage`, `emptyStateHtml`) | ✅ **done 2026-09-28** — `emptyStateHtml`, `loadRecentLeagueMatches` + its markup all deleted. `resolveFixtureStadiumImage` folded into the stadium-image work |
| 7 | Club logo selection by name string compare — make it a team field | ✅ **done 2026-09-28** — was already a `Team.logoUrl` field, but the *schedule* never sent it. Fixed |
| 8 | Add `/images/default-stadium.png` or remove the reference | ✅ **done 2026-09-28** — asset written; 3 references, all were 404 |
| 9 | Only SRB has a flag image; 8 other countries have `flagImagePath = null` | ✅ **done 2026-09-28** — it already degrades to a real flag emoji; one 404 path fixed. See below |
| 10 | `promote-reveal` uses `sessionStorage` — breaks on refresh and across devices. Move to a server-side reveal record | ✅ **closed 2026-09-28, owner decision** — the premise was wrong; see below |

---

#### ✅ S8.3 #5 — not a merge: 183 lines of *shadowed* code (2026-09-28)

The backlog said "merge the two ~450-line parallel implementations". It was not two implementations to
merge, and merging them would have been the wrong move.

`render`, `renderGraph` and `openPlayerGraph` were each **declared twice at the same brace depth**
inside `createTrainingView`. In JavaScript a later function declaration silently **shadows** an earlier
one. The first copies were unreachable — **183 lines** of code no call could ever reach:

| Shadowed | Surviving |
|---|---|
| `openPlayerGraph` (149–158) | 589 |
| `renderGraph` (187–235) | 707 |
| `render` (237–360) | 824 |

**Why deletion and not a merge:** the copies had already **diverged** — the surviving `renderGraph`
builds a player-profile hero (`buildPlayerProfileHeroHtml`) that the shadowed one never had. Reconciling
a dead function with a live one is a good way to lose the better half.

**Why it survived:** a shadowed declaration is **valid JavaScript**. `node --check` passes, there is no
bundler warning, and no linter in the project flags it. Only counting the names finds it.

`TrainingViewNoShadowedDeclarationsTest` now does that, scoping by indentation so a nested function of
the same name is not a false positive. Verified by re-introducing a shadowed `render` and watching it
report `{render=2}`.

**One bug of mine, worth recording because the test was green while the bug was present:** the guard
matched indentation with `\s+`. `\s` also matches newlines, so a greedy `\s+` swallows the blank line
above a declaration and the `^` anchor quietly stops meaning anything. The test passed with a shadowed
copy sitting right in front of it. Indentation is matched with `[ \t]+` now, and the reason is a
comment in the file.

---

#### ✅ S8.3 #9 — country flags: the field is wired, the fallback is real (2026-09-28)

Owner position: *"stavi mesto za sliku, kako budemo pravili drzave tako cemo dodavati zastave"* — put
a place for the image, and add artwork as countries are built.

**There was already a place, and it was already used.** `Country.flagImagePath` is seeded, exposed on
`CountrySummaryDTO`, and consumed by `buildCountryFlagBadgeHtml`. The important part is what it does
when the value is `null`, which is the case for 8 of the 9 countries:

```js
const flagEmoji = countryFlagEmojiFromIso(country?.isoCode);
return `<div class="fm-country-badge">${flagEmoji || '🌍'}</div>`;
```

`countryFlagEmojiFromIso` derives the emoji from the ISO code via regional indicator symbols
(`String.fromCodePoint(127397 + …)`), with an alpha-3 → alpha-2 table for codes stored long. So a
country with no artwork gets its **actual flag emoji**, not a blank box and not a generic globe —
which is the correct "missing image" behaviour for a flag, and much better than the alternative of
shipping eight placeholder images that all look the same.

**One real 404 found and fixed.** `CSDataInitializer` pointed Serbia at
`/images/flags/srb.png`; **`/images/flags/` does not exist**. That is the one country that *does*
have artwork, pointing at a file that is not there. Now `/images/serbiaflag.png`, which is what the
football game already used.

**Added:** a sweep that checks every `/images/...` string literal in `src/main/java` against the
filesystem. It now reports zero broken references (the single remaining hit is `/images/**` in
`SecurityConfig`, which is a security whitelist pattern, not an asset).

---

#### ✅ S8.3 #10 — `promote-reveal` in `sessionStorage`: closed, the premise was wrong (2026-09-28)

The recorded concern was that the reveal "breaks on refresh and across devices". It does neither, for
the thing that matters.

The **talent value is server-side already**: `promoteJuniorWithReveal` persists the promotion and the
revealed number onto the `Player`. The `sessionStorage` entry holds only the *celebration banner* —
`player-view.js` reads it, renders a flourish, and deletes it. Refresh before the flourish paints and
the flourish is gone; the number is not.

And since S8.3's sibling work wired first-team talent (873588d), the promoted player's exact talent is
now visible in the squad anyway, so the banner is decoration of decoration.

**Closed rather than built.** A server-side reveal record would buy a confetti animation that survives
a refresh. Not worth a table.

---

#### ⬜ S8.3 #3 — mobile sidebar has no Tactic Editor — MOVED TO END OF BACKLOG 2026-09-28 (owner)

**Confirmed real, deliberately not fixed now.** The two sidebars have drifted in both directions:

| | desktop `#clubSidebar` | mobile `#mobileSidebar` |
|---|---|---|
| entries | 10 | 12 |
| Tactic Editor | ✅ | ❌ **missing** |
| Training Setup · Training Reports · League Table | ❌ | ✅ mobile-only |

**Owner position (2026-09-28): the Tactic Editor's suitability on a phone is unknown, so this needs
to be scoped before it is touched.** Not a bug to fix but a question to answer first: is the advanced
Tactic Editor usable on a 390px screen at all, or should mobile reach the *basic* formations view
instead? Building a panel for a screen that may not work is worse than leaving the gap visible.

**Context that makes the second half of this table explicable, from the owner:** Training Setup and
Training Reports live inside the Club section on desktop, and the league area has its own table — so
the three mobile-only entries are shortcuts rather than missing navigation. That means the drift is
**not** three missing desktop links; it is three deliberate mobile shortcuts, and only Tactic Editor
is a genuine gap. Any eventual fix should add Tactic Editor to mobile and leave the other three alone.

---

#### 🟡 S8.3 #1 — the desktop sidebar accordions were completely inert, not merely double-rendering (2026-09-28)

The backlog called this "loadPage fires twice per click". That is true and it is **the lesser half**.
The accordions did not work at all.

Every `.accordion-header` in the desktop sidebar carries **two** bindings:

1. an inline `onclick="toggleAccordion(this)"` in the markup, and
2. an `addEventListener` on `.accordion-header` added by `sidebar.js`.

`toggleAccordion` is **not idempotent** — it reads the open state and then writes the opposite. Two
calls open the panel and close it again in the same tick. So all three groups (Players, Tactics,
Club) were no-ops.

**Why it survived so long:** a collapsed accordion and a dead accordion are the same picture from
outside. `after=0px` is exactly what a working accordion that has just closed looks like, so the
symptom matched "normal" perfectly. No amount of reading the code finds this — a browser finds it in
one click.

**And deleting `app.js` was not enough.** `app.js` was a genuine *third* binder and removing it
removed the double `loadPage`, but the accordions stayed dead, because the real duplicate was the
inline handler versus `sidebar.js`. Measured in Chromium after deleting `app.js`: still `0px`.
Only removing `sidebar.js`'s listener fixed it — now `102px / 102px / 276px`.

The inline handler is the binding that stays, because it is the only one that covers `#mobileSidebar`,
which is not in `sidebar.js`'s list.

| Layer | Test | Result |
|---|---|---|
| Static | `SidebarBindingTest` — one file may bind a sidebar; `sidebar.js` must not bind `.accordion-header`; every *wrapped* accordion header has exactly one toggle | ✅ verified by re-adding the listener and watching it fail |
| Live | `SidebarAccordionOpensTest` — logs in with a real browser and asserts `style.maxHeight` is non-zero after one click | ✅ `102 / 102 / 276`; **skips** when the app is not running |

Two things about those tests worth recording, because both were my mistakes first:

- `SidebarBindingTest` originally walked the JS with **`Files.list`, which is not recursive** — it
  could never see `js/pages/`, `js/ui/` or `js/demo/`. It would have passed while a duplicate binder
  sat in a subdirectory. Now `Files.walk`.
- The test then asserted *every* `.accordion-header` has a toggle, and failed with "13 headers, 5
  handlers". **The test was wrong, not the markup**: the class is also used to style ten flat mobile
  navigation buttons that call `loadPage` directly, so class reuse looked like eight missing
  handlers. It now counts only headers inside an `.accordion` wrapper, of which there are 3.

---

### S8.3a — Every panel overflows the phone viewport ✅ FIXED 2026-09-28

Found while building the junior-school panel (S5.3a) and **not** caused by it.

**The recorded diagnosis was wrong, and getting it right mattered.** The note above blamed
`.fm-panel` being `content-box`. That is true but harmless on its own: an `auto`-width block box
resolves identically under either model. The actual cause is the **mobile media query**, which gives
`.fm-panel` an explicit `width: 100%` — and `width: 100%` in `content-box` means *"100% of the
parent's content, then add the padding on top"*. On a 390px phone: 374 + 20 + 20 + 2px border =
**426px of panel in a 374px column**, putting 30px of every panel outside the viewport.

Fixed by `box-sizing: border-box` on the base `.fm-panel` rule. At desktop widths this changes
nothing, because the panel has no explicit width there.

**Why it survived this long:** `html, body { overflow-x: hidden }` at ≤768px clips the overflow, so the
usual smoke test (`document.documentElement.scrollWidth > window.innerWidth`) reports **false** while
a control is genuinely unreachable. Every panel on every page of the SPA had this — 243 panels.

| # | Task | |
|---|---|---|
| 1 | `box-sizing: border-box` on `.fm-panel` | ✅ done |
| 2 | Replace the `scrollWidth` smoke test with one that measures a real control's right edge against the viewport | ✅ done — `MobilePanelOverflowTest` measures every panel's box against both its parent and the viewport |
| 3 | Audit the other content-box containers with padding (`.fm-page`, table wrappers) the same way | ⬜ open |

**The new test was verified by breaking the fix.** It passes with `border-box` and fails with
`content-box`, reporting `right=420` against `vw=390` — the same symptom that was reported by hand.
It is checked against the stylesheet on disk rather than a running application, because the bug is
entirely in the CSS, and it uses the **real import order** (the overrides sheet is imported *before*
`dashboard.css`'s own rules, so testing the reverse would test a layout that never shipped).

One thing it caught that is worth keeping in mind: the first version of the fixture had no
`<meta name="viewport">`, so Chromium laid it out at the mobile default of **980px** and every
media query below 980 was never reached. The test was green, at the wrong width, measuring a tablet
and calling it a phone.

**Do this before the next mobile-facing feature.** It is a two-line fix with a wide blast radius, which
is exactly the combination that wants a deliberate pass rather than an opportunistic one.

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
| 3b | **The gym reshapes senior bodies** — mass, conditioning, and a body that responds to a season of training rather than a player who never changes shape. **Added by owner request 2026-09-27.** The gym already exists and already reduces training-injury risk; this is its third job | ❌ |
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

### Sprint 8 exit criteria — ❌ NOT MET (sprint not started)

- [ ] No dead routes, no generic "API Error" cards
- [ ] Cup and internationals functional
- [ ] National team and U21 implemented
- [ ] One league-table comparator
- [ ] Press conferences, inbox, news feed
- [ ] Schedulers in place and idempotent
- [ ] Multi-user capable
- [ ] Engine quality gates running in CI

**Two items inside Sprint 8 are smaller than they look and unblock other sprints:**

- **S8.1 item 4** (every fetch checks `response.ok`, centralised in `authFetch`) — this is the same
  class of bug that hid a dozen dead routes in Sprint 0, and it is a half-day.
- **S8.6 item 7** (move `User → team` from a name match to a real FK). **Renaming your club currently
  loses your team.** That is a data-loss bug wearing a Sprint 8 label, and it is the reason the
  `/auth/me` lookup needed duplicate-safe `findAllByNameIgnoreCase` as a workaround.

---

## ⚠️ PRIORITY CHANGE — 2026-09-26 (owner decision)

**Owner direction: statistical targets are deferred. What matters now is that the systems work and
that the decisions they produce are good — not that the numbers match a Premier League season.**

This supersedes the tuning half of Sprint 1. Two consequences:

1. **Numerical calibration is now polish.** The exit table in Sprint 1 stays as a record but stops
   being a gate. Goals at 3.1 instead of 2.7, duels at 268 instead of 100, SOT at 28% instead of
   33% — all accepted for now. S1.1b and S1.4/S1.5 are kept because they fixed *broken behaviour*
   (a keeper fishing at balls that were never going in; a 7 m steal radius), not because the numbers
   moved.
2. **Missing and broken mechanics are now the priority.** Those are not numbers, they are holes in
   the product:

| Gap | State today | Sprint |
|---|---|---|
| **Injuries** | **No generator at all.** The ported `maybeTriggerInjury` was quarantined; the live engine produces zero injuries | S1.6 |
| **Fatigue** | Never accumulated — `Player.addFatigue` has 0 callers | S1.6 |
| **Passive recovery** | Does not exist | S1.6 |
| **Penalties** | Awarded and counted, then **never taken** — no run-up, no dive, no conversion | S1.7 |
| **Substitutions** | **Impossible.** No bench contract exists; `substituted` is never set | S1.8 |

That last one is the single biggest gap in the engine. A red card today means playing the rest of
the match with ten and no recourse; a tired player cannot be replaced; fatigue has no consequence
because there is nothing to substitute into. S1.6–S1.8 are the work that makes the decisions the
rest of the system produces actually land.

**Statistical targets will be revisited after the system is connected.**

---

# Conditional substitutions — match settings

**Added 2026-09-26 at owner request.** Placeholder brief; detail to be confirmed before build.

## The idea

Per-match substitution rules, evaluated live: *"at minute 60, if we are losing, bring on X for
Y"* / *"if leading, protect the lead"*. Capped naturally at five rules, one per available sub.

## Effort assessment — the honest version

**The engine is roughly 20% of this. The UX and the rules interpretation are the other 80%.**

### Genuinely easy, because S1.8 just built the prerequisites

| Piece | Why it's cheap now |
|---|---|
| Trigger by minute | `state.getMatchTicks()` already exists and is polled every tick |
| Condition "if losing / drawing / leading" | `state.getHomeGoals()` / `getAwayGoals()` |
| "Player X on for position Y" | `SubstitutionService.pickReplacement(team, off)` already does role-aware selection, and a named player is simpler still |
| The bench | Just built — `MatchState.getBench(side)` |
| Sub budget and windows | Just built — `getSubsUsed`, `getSubWindowsUsed` |
| Emergency subs | Just built, and they take priority over rules, which is the correct precedence |

The hard parts of substitutions are done. Conditional subs are a *layer on top*, not a
rebuild.

### The real work, in order of difficulty

1. **Plan persistence (the biggest item).** A plan set at minute 0 must survive a page reload at
   minute 55. That means the plan lives **server-side, keyed by match**, not in JS memory. Needs an
   entity, a controller, and a decision on when it is discarded. Everything else is UI on top of
   this.
2. **The window rule is genuinely ambiguous.** A rule that fires at minute 60 needs play stopped
   before a sub is legal. The engine has no explicit stoppage clock — `insideOpenWindow()` currently
   sniffs `getRestartTaker()`. A conditional sub that triggers mid-flow therefore either has to
   wait for the next natural stoppage, or force one. **Needs a product decision:** does the engine
   force a stoppage, or do rules only fire at natural dead-ball moments?
3. **Rule cancellation and stale rules.** A rule whose named player has already come on, or whose
   window has passed, must be visibly disabled rather than silently ignored. The manager has to be
   able to see "3 of 5 subs still available, 1 rule no longer possible — why".
4. **Interaction with fatigue/injury auto-subs.** If the auto-sub already used the slot a rule
   needed, the rule dies. Precedence is: emergency (red/injury) > manager rule > fatigue auto-sub.
   The UI has to explain that rather than look broken.
5. **UI.** A rules builder — minute, condition, player, position — plus a live view of which rules
   fired, which are spent, and which are void. This is the bulk of the work and the part worth
   doing well, because it is the whole feature from the manager's point of view.

### Decisions from the owner (2026-09-26)

| Question | Answer |
|---|---|
| Force a stoppage when a rule fires, or only at natural dead balls? | **Introduce real stoppage time.** There are already moments that need it — waiting for a VAR decision, a penalty, and so on. |
| Plan persistence | Server-side, keyed by match. Confirmed. |
| Stale rules must show a reason | Confirmed. |
| Precedence | Confirmed, but **red cards are not in it.** Emergency is **injury** or **very tired** only — see below. |
| UI | Confirmed, build it properly. |

### 🚨 Correction — a sent-off player is never replaced

The owner caught a genuine football error in S1.8 that I had shipped: I was replacing a dismissed
player, and wrote a comment defending it. **That is not football.** A red card means the team plays
the rest of the match a man down. There is no replacement.

Corrected in the follow-up commit: `SubstitutionService` now refuses any substitution where the
outgoing player is sent off, and `onTick` only reacts to **injury**. Two tests that encoded the wrong
assumption were rewritten, and two new ones assert the correct behaviour.

This also matters for the precedence chain, which is therefore:

1. **Injury** — the game is already stopped, so the change is free of time and of a window
2. **Manager's conditional rule**
3. **Fatigue auto-sub** — "very tired"

...and a red card sits outside the chain entirely, because it changes nothing about the bench.

### New prerequisite: real stoppage time (owner-directed)

S1.8's `insideOpenWindow()` sniffs `getRestartTaker()`, which is a placeholder, not a stoppage
clock. The owner has directed that proper stoppage time be introduced, since the engine needs it
for VAR waits and penalties as well. That is now a **hard prerequisite** for conditional
substitutions, and it improves the engine in its own right.

Scope, to be built as its own task:

| # | Task |
|---|---|
| 1 | An explicit stoppage on the state — reason, tick entered, duration — rather than inferring it |
| 2 | **VAR review** stops play (the `VARService` gates already exist; nothing halts the clock) |
| 3 | **Penalty** awarded stops play until it is taken (S1.7) |
| 4 | Injury stops play until the replacement is made (ties S1.6 to S1.8 cleanly) |
| 5 | Added time at the end of each half, driven by actual stoppage minutes |
| 6 | Substitutions only inside a stoppage — this then makes the window rule honest instead of heuristic |

### Status 2026-09-26 - prerequisites done, engine + API done, UI outstanding

All three prerequisites are complete: **S1.7** (a penalty creates a stoppage), **stoppage time**
(`StoppageClock`, which also made the window rule honest instead of heuristic), and **S1.8**
(bench, budget, windows, role-aware selection).

**Built:**
- `ConditionalSubstitutionRules` - live evaluation, `PENDING` / `WAITING_FOR_STOPPAGE` / `FIRED` /
  `VOID`, with a **reason** on every void rule.
- Precedence chain seated correctly: `onTickInjuriesOnly()` then the conditional rules then
  `onTickFatigueOnly()`. `SubstitutionService.onTick()` was split to make that possible.
- `SubstitutionPlan` entity + `SubstitutionPlanController` - server-side, keyed by match, `PUT`
  replaces rather than merges.

**Outstanding: the UI only** - the rules builder (minute, condition, player on, player off) and the
live view of which rules fired, which are spent and which are void. That is the part the manager
touches and, per the assessment above, the bulk of the work.

---

# TACTICAL EDITOR REDESIGN

**Added 2026-09-26 at owner request — PLACEHOLDER ONLY.**

No scope defined yet. Owner will supply the brief when the time comes. Not estimated, not
scheduled, and deliberately not designed here.

Known starting point, for context when the brief arrives — the current editor
(`static/js/pages/views/tactic-editor-view.js`, 311 lines) supports:

- drag the ball onto a pitch zone to set the tactical state
- drag slot circles to define that state's shape
- localStorage drafts, version counter, discard/save
- five set-piece taker selectors

Everything else in the current model is known to be inert: the 506 possession-context tactical
rules are all identical, so in-possession and out-of-possession shape do nothing
(`backlog.md:381-385`), and the engine is hard-coded 4-4-2 regardless of the formation chosen.

## ⚠️ Remark — the editor is not merely inert, it is disconnected (owner note, 2026-09-26)

Recorded here so the brief is written against the real state rather than the assumed one.
**Flagged by the owner as directionally right but not literally exact** — the wiring
conclusion holds, the specific figures below are approximate and must be re-verified against
the code before the brief is written.

The claim: **the tactical editor writes to a table that no engine file ever reads.**

- `TeamTacticsProfile` — the entity the editor persists through `TeamController`
  `GET/PUT /teams/{id}/tactics-editor` — is referenced only by its own repository, the
  database initializer, the backup service and `TeamTacticsService`. **No file under
  `newLogic/sim/` opens it.**
- Every construction of the orchestrator uses the **no-arg** form, which is
  `this(state, new TacticsRules())` (`sim/engine/MatchOrchestrator.java:181`). There are
  roughly a dozen such sites (`SimMatchRunner`, `ProposalMatchController`, the launchers, the
  diagnostics, `ProposalBatchDiag`, `TeamStrengthProbe`, `MatchSimulator`, `RestartManager`).
- `new TacticsRules()` therefore falls to its own defaults: **raw JDBC to a hardcoded
  `jdbc:postgresql://localhost:5432/sokker_db`** (a database name left over from a previous
  project) reading **team id 1** (`sim/tactics/TacticsRules.java:37-49`). Otherwise it loads
  the bundled `tactics_fallback.json`. **Both sides of the match share one rules object.**
- `FORMATION` is `public static final String FORMATION = "4-4-2"` — the 10 formations offered
  in `formations-view.js` resolve to 3 layouts in `FormationSlotCatalog`, and the engine reads
  none of them.

> **Owner ruling 2026-09-26: the identical possession contexts are INTENTIONAL, not a defect.**
> `TeamTacticsService.mirrorWeHaveBallRules()` clones each `WE_HAVE_BALL` target onto the
> matching `OPPONENT_HAS_BALL` rule on every save and every load, so all 506 slot × ball-state
> pairs resolve to the same cell by design. **Do not "fix" this without a new owner decision.**
> What follows from the decision, and is worth knowing rather than correcting:
>
> - The tactical model is **one shape, not two** — positioning does not change when possession
>   changes. The editor gives the manager a *shape*, not a *tactic*.
> - **46 "ball states" = 42 reachable cells + 4 dead ones.** `TacticsRules.ballStateKey()` only
>   ever emits `CELL_r_c` on a 7×6 grid, so `ATTACK_LEFT_CORNER` and its three siblings are
>   unreachable.
> - **Not yet ruled on:** the generated defaults map *every* ball state to the slot's formation
>   anchor (`FormationSlotCatalog.buildDefaultRules`), so with default rules the shape does not
>   react to the ball either. That is a separate question from the possession context and is
>   still open.

**Why this belongs in a placeholder section and not a scheduled task:** Sokker's entire product
identity is this one editor — it is the reason their long-term players stay, and they run
40,000 clubs on it. We appear to already own roughly 80% of the equivalent (the data model,
the persistence, the editor UI, the dataset) with the engine read-path missing. Until that is
closed, the editor is a screen where a manager drags a shape, saves it, and nothing happens —
which is worse than not shipping the screen, because it is a broken promise in the product.

**The consequence for the brief:** this is a wiring, sanitisation and per-side-rules job, not a
greenfield rebuild. Budget it as such, and decide explicitly whether the shipped model keeps
Sokker's free-form geometry, Hattrick's named tactics with derived levels, FM's instruction
sliders, or a deliberate mix — `Tactics.java` already carries six 0–10 fields
(`aggression`, `defenseLine`, `pressing`, `possession`, `counterAttack`, `ballControl`), three
of which have no consumer and none of which have a UI.

Full analysis, including the Sokker / Hattrick / Football Manager comparison this judgement
rests on: **`COMPETITIVE_ANALYSIS.md`** §9.1.

---

# Effort summary — re-audited 2026-09-27

| Sprint | Scope | Effort | Type | State |
|---|---|---:|---|---|
| **0** | Fix the exploits | 3–4 d | `FIX` | ✅ done |
| **1** | Engine calibration + injuries + penalties + subs | 16–20 d | `FIX` + `FEATURE` | ✅ done as mechanics; statistics deferred |
| **2** | The economy | 15–18 d | `FEATURE` | ✅ done |
| **3** | Contracts and transfers v2 | 12–15 d | `FEATURE` | 🟡 core done; 4 AI-market gaps |
| **4** | Training v2 | 12–15 d | `FEATURE` | 🟡 6/7 done; focus UI + AI decline open |
| **5** | Juniors v2 | 8–10 d | `FEATURE` | ◀ **current**; 1 of 4 features started |
| **6** | Delete the dead code | 1 d | `DELETE` | ✅ done; 3 orphan POJOs + `ENGINE.md` left |
| **7** | AI-vs-AI verification | 1–2 d | `FIX` | ❌ not started |
| **8** | Presentation, integration, deployment | 10–12 d | `INFRA` | ❌ not started |
| | **Original total** | **~79–95 days** | | |
| | **Realistically remaining** | **~25–32 days** | | S5 + S7 + S8, plus the S3/S4 remainders |

### Critical path — as it actually stands

```
S0 ✅──┐
S1 ✅──┼──▶ S2 ✅ ──▶ S3 🟡 ──┐
       │                      ├──▶ S5 ◀ CURRENT ──▶ S7 ──▶ S8
S4 🟡 ─┘                      ┘        │
                                    └── S5 feeds S4.4 item 5 (youthLevel → intake quality)
S6 ✅ (parallel, done)

Pick up opportunistically, nothing is blocked by them:
  S4.1 item 5  focus panel        — the training sprint's headline feature is invisible
  S4.7 item 5  AI squads decline  — the pyramid cannot regenerate over a long save
  S3.6 item 6  AI contract renewals — no supply pressure in the transfer market
```

### The two sentences that matter for planning

1. **The three systems that make this a manager game exist.** Economy (S2), contracts and a real
   transfer market (S3), and a training model with genuine trade-offs (S4) are all built. That was
   the goal of the first four sprints and it is met.
2. **What is left is the academy, the documentation of which engine is live, and presentation.** The
   academy (S5) is the only sprint with a *missing mechanic* rather than a missing refinement — the
   game currently has no way to find a player it did not generate itself.

---

---

# PROMOTION, RELEGATION AND THE PLAYOFF LADDER — owner spec, 2026-09-26

Backlogged at the owner's request to design it properly rather than guess at it. **The week it plays
in is already built and tested** (week 11); the ladder below is not.

## The structure the owner described

One league in tier 1, then 2, 4, 8 and 16 leagues in tiers 2 to 5. Each league has ten clubs.

| Tier | Leagues | First | Second | 7th | 8th | 9th, 10th |
|---|---|---|---|---|---|---|
| 1 | 1 | — | — | playoff | playoff | **relegated** |
| 2 | 2 | promoted up | playoff | playoff | playoff | **relegated** |
| 3 | 4 | promoted up | playoff | playoff | playoff | **relegated** |
| 4 | 8 | promoted up | playoff | playoff | playoff | **relegated** |
| 5 | 16 | promoted up | playoff | playoff | playoff | **nothing — tier 5 has no drop** |

## The two-legged playoff

Four clubs are involved and they are paired by strength, not by luck:

- **7th of the league above** plays the **weaker** of the two second-placed clubs.
- **8th of the league above** plays the **stronger** of the two second-placed clubs.

The winner of each pair is safe. The loser is relegated. The ninth and tenth go down automatically,
with no playoff.

So a club's worst case is 9th — automatic relegation — and its best case is 7th, which is one win
from safety. That is a better spread than a flat bottom two, and it gives mid-table clubs something
to play for in the last two rounds.

## What exists today

- `SeasonService.ensurePlayoffWeekFixtures` already builds the two tier-1 pairs, but it pairs the
  seventh with the second of tier-2 league A and the eighth with the second of league B **by league
  order, not by strength**. On a table where the second of league B is clearly the better side, that
  is the wrong draw. Fixing the pairing is part of this task.
- The playoff week is now correctly week 11, and the clubs not involved in a playoff play a friendly
  that week instead (`ensureFriendlyFixturesForCurrentWeek` works the exclusion out from the saved
  fixtures).

## What is not built

- [ ] **Pair the playoff sides by strength**, not by league order
- [ ] **Promote the first-placed club of every tier below 1** into the league above
- [ ] **Tiers 2-5 playoffs**, which need the same seventh/eighth logic across 30 leagues rather than one
- [ ] **Tie-breaks** — points, goal difference, goals scored, and a final ordering when all three are level
- [ ] **European places.** The owner said it is undecided: "videcemo ko sve ide u evropska takmicenja". Not modelled. Do not guess.
- [ ] **Tier 5 has no relegation** — currently nothing implements the floor
- [ ] **The pyramid's entry points**: how a club joins tier 5, and what happens to a newly promoted club's first season
- [ ] **Cup and European competitions** — the owner's schedule says "kasnije cemo se baviti", so these are deliberately out of the season shape

## Open questions for the owner

1. **Do both legs of a playoff get played?** One match in week 11 is the current assumption. A
   home-and-away would need a second slot, and week 11 has exactly one.
2. **What happens to the fixtures if a playoff is drawn?** There is no extra time or penalties model
   in the season layer yet.
3. **Does a promoted club keep its tier-1 status on winning the tier-2 title next season**, or is
   promotion decided purely by finishing first?
4. **Where do European places sit** — is it a finish position, or a separate qualifying round after
   the league?


---

# NATIONAL TEAMS — placeholder (owner, 2026-09-27)

**Not built. Deliberately parked at the end of the last sprint, as instructed.**

What the owner specified:

- At the **start of each season**, every country picks a **human user** as its national-team
  manager. That user chooses the **squad**, the **lineup** and the **tactics**.
- A player selected for his country **earns minutes like any other match** — league, cup, European,
  national team, friendly all count together toward the 120-minute weekly maximum in the training
  formula. So national-team selection is a *development* decision, not a prestige one: a call-up
  is worth about 90 minutes of a week's training.
- The training percentage therefore does **not** care who the national-team coach is. There is no
  national-team staff in the game yet, so a player's club coach supplies the coach factor. This is
  the same answer as the "coach for minutes <= 0" question, and it holds for call-ups too.

## What this needs, when it gets built

- [ ] A country → selected manager mapping, chosen once per season
- [ ] Squad selection, lineup and tactics for the national team
- [ ] National-team fixtures in the season calendar — **and a decision on which weeks they occupy**,
      because every week already has exactly two slots filled. A call-up is worth less if it costs a
      league match.
- [ ] Minutes must be written to `MatchPlayerStats` the same way club minutes are, so the training
      aggregation picks them up with no special-casing. `Match.competition` already distinguishes
      them, so this is mostly wiring rather than new structure.
- [ ] Competition type for national football, distinct from club competitions, so a national match
      cannot be mistaken for a club fixture in the transfer or fixture logic

---

---

# PLAYER VIEW — the tabs are placeholders (owner, 2026-09-27)

Seen on **Ljupče Ožegović** (Omladinac, ATT, 20, OVR 79, value 33.7m — the star of the squad, so
this is the page a manager will look at most). The page shell is right; the content is not there.

Right now the page says, in plain text, in the place where data should be:

> *This tab UI is ready: player-by-player match log can be wired later*
> *This tab UI is ready: career timeline UI is ready for later API expansion*

**That sentence must not ship.** It is a note to a developer sitting where a manager is supposed to
be reading his player's season. Either the tab shows data or it does not exist.

## What each tab needs

| Tab | Already available | To do |
|---|---|---|
| **Overview** | condition, form, value, OVR, position, role | Career totals, current contract and wage, squad role, and the **training percentage** once Sprint 4 lands |
| **Matches** | nothing | Per-match log from `MatchPlayerStats`, which already holds minutes, goals, assists, rating, shots, interceptions and cards — it is written by both match engines, so this is a query and a table, not new data |
| **Transfer** | nothing | Asking price, live offers as real `TransferOffer` rows, contract, release clause, previous clubs, and sell-on clauses from `FeeStructure` |
| **History** | nothing | A career timeline: every club he has been registered with, the contract terms, and the fee paid for each move |

## Why it is not just a wiring job

- **Transfer and History overlap**, and the owner is right to be suspicious of building both blindly.
  A transfer *is* the history entry. The honest design is one timeline, with the transfer tab being a
  filtered view of it.
- `MatchPlayerStats` has no week/season of its own — it hangs off `Match`, which has `seasonYear`,
  `weekNumber` and `competition`. So the match log is a join, and it can answer "how did he do in the
  league versus the cup" for free.
- Ratings exist per match but there is no season aggregate for a player, so the season numbers a
  manager expects (goals, assists, average rating) have to be derived rather than read.

**Suggested order: Matches first** — the data is already there, it is the tab a manager checks
before every match, and it proves the pattern for the other three.


---

# NATIONAL TEAM PAGE — redesign (owner, 2026-09-27)

The owner's words: *"dodaj da se sredi NT stranica, trenutno je ruzna"* — the national team page is
ugly and needs tidying.

Worth doing **with** the national-teams feature rather than before it. A page that only ever showed
placeholder content should not be polished and then rebuilt; the layout decisions depend on what the
page is for. The one thing settled already: a country picks a **human manager** at the start of the
season, who chooses the squad, the lineup and the tactics — so the page is a selection screen first
and a results screen second, which is the opposite emphasis from a club page.

- [ ] Decide what the page is for: pick a squad, or follow one, or both
- [ ] Squad list with positions and roles, the same POSITION/ROLE split as the club squad screen
- [ ] Minutes in the week, since a call-up is worth about 90 of the 120 minutes that drive training
- [ ] Results and table for the national team
- [ ] Design it alongside the national-teams feature (see the placeholder at the end of the last sprint)

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

---

# 🔴 ME TWEAK

Items the owner raised directly, outside the sprint structure. Newest first. These are **not**
sequenced against the sprints — they are the owner's own list, and they sit here so they cannot be
lost in a re-audit.

---

## 🔴 T1 — a 1–1 draw with 38–4 shots and 9.5–0.9 xG. Those numbers are impossible (2026-09-28)

Owner: *"pazi ove brojke, nemoguce"* — look at these numbers, impossible. Reported from a real
match screen, Omladinac 1–1 Napredak, Season 1 Week 1.

| Stat | OFK Omladinac | NK Napredak 1931 |
|---|---|---|
| Possession | 57% | 43% |
| **xG** | **9.50** | **0.90** |
| Shots | 38 | 4 |
| Shots on target | 6 | 1 |
| Shots off target | 32 | 3 |
| Pass accuracy | 86% | 88% |
| Corners | 10 | 1 |
| Offsides | 0 | 0 |
| Yellow / Red | 0 / 0 | 1 / 0 |
| Penalties awarded | 0 | 1 |
| Fouls | 9 | 4 |

### Why it is impossible — four independent reasons

**1. 9.5 xG does not become 1 goal. Not once in a thousand matches.**

| | |
|---|---|
| P(0 goals from λ=9.5) | 0.000075 |
| P(1 goal) | 0.000711 |
| **P(≤1)** | **0.079% — about 1 match in 1,272** |

A 1–1 draw from 9.5 expected goals is a once-in-thirteen-hundreds event. Even allowing for a keeper
having an all-time game, this is not a tail anyone should expect to see in a season.

**2. 9.50 xG from 6 shots on target is 1.58 xG per shot on target — above the scale.**

A penalty is 0.79. A six-yard header is roughly 0.40–0.60. Only a shot from about two yards, straight
in, approaches 1.0, and almost no such shots exist. For six shots to average 1.58, essentially every
one of them would have to be a goal from a yard away.

**3. xG 9.5 and "32 of 38 off target" cannot both be true.**

These say opposite things. 9.5 xG means the chances were high quality. 84% of the shots missing the
target means they were not. Whichever number is being summed from a different set of events than the
one counting shots, one of the two is describing a match that did not happen.

**4. The team that was routed has the better pass completion.**

Outshot 4–38, out-cornered 1–10, and finishing with **88% pass accuracy against 86%**. A side taking
four shots concedes the game; it does not then complete a higher proportion of its passes than the
side taking thirty-eight.

### What this most likely is

**Two independent series that do not know they are in the same match.** The score and the shot count
describe a 1–1 draw in which one team had a handful of chances; the xG describes a 10–0. The most
likely mechanism is that **xG is summed over a different event set than the one the shot counter
counts** — xG accumulates across chances, while shots are counted from outcome events, or one of
them double-counts.

There is direct precedent for the double-counting half of that: `PROPOSAL_PROGRESS.md` records the xG
sum as *"suma `xG` nad GOAL/SHOT_SAVED/SHOT_MISSED/SHOT_BLOCKED/CROSS_HEADER/PENALTY, penali bez
duplog brojanja"* — penalties specifically excluded from double counting, which is a note you only
write after it has happened once.

Also out of range on its own: **38 shots**, where the project's own target is 15–25 per match.

### To diagnose

| # | Step |
|---|---|
| 1 | Dump every shot event for one match with its `xG`, and check that the number of events equals the reported shot count |
| 2 | Check the xG sum counts each shot **once** — specifically penalties and headers, the two that have form-double-counted before |
| 3 | Check the xG table is not keyed such that one event yields several xG readings |
| 4 | Sanity-gate the result: a match where xG and goals disagree by this much should be **impossible to serialise**, not merely unlikely. A post-hoc test in `ProposalMatchOutcomeBuilder` asserting `goals <= xG * 3 + 1` would have caught this match |
| 5 | Separately: 38 shots is above the 15–25 target and worth a look on its own |

**This is the owner's own report and is trusted over any reading of the numbers taken from a different
match.** The figures above are as reported.

---

## 🟡 Transfer activity is not visible because nothing generates it (found 2026-09-28)

Owner: *"takodje ne znam gde treba da se vide transfer izmene, Club-transfer nije to mesto, ja bar ne
vidim"* — where should transfer activity be visible? Club → Transfers is not the place, I don't even see
it.

**Audited, and the answer is that there is nothing to see.** The page renders and is reachable from
the sidebar on both desktop and mobile. The panels are all there. They are empty because the thing
that would fill them does not run.

| Panel on Club → Transfers | Why it is empty |
|---|---|
| **Incoming offers** | `TransferOffer` and `NegotiationService` are fully written — creating offers, countering, settling. **Nothing calls `NegotiationService`.** No AI club ever makes an offer, so the panel is permanently empty |
| **Transfer market** | Working. Populated — 60 listed players in Serbia |
| **My transfer desk** | Working. Budget, listed players, interest |
| **The board** / **Weekly ledger** | Working |

**So this is not a navigation problem and the fix is not a page.** Three separate pieces of work, and
the first is the important one:

| # | Task | Size |
|---|---|---|
| T1 | **Drive the weekly AI transfer loop** — AI clubs decide who they want, register interest, make offers, and settle. `NegotiationService` exists and is unwired, exactly like `RegistrationService` was this morning | Large, and the market becomes alive |
| T2 | **A transfer news feed** — "Omladinac signed X from Y", "Sremac listed Z". No such feed exists anywhere; grep for `transferNews`/`activityFeed` finds nothing | Medium |
| T3 | Decide **where** it goes. A dashboard ticker is the obvious home for news, and the club's own transfer page for anything addressed to you | Owner decision |

> **T1 before T2.** A news feed with nothing in it is a worse thing than no news feed, because it
> advertises that the market is dead. T1 is also the difference between a transfer market that is a
> list of prices and one that is a market.

**Note the pattern.** This is the third time in two days that a fully-written service turned out to be
called by nothing: `RegistrationService` this morning, and now `NegotiationService`. Both were
discovered by asking "what fills this screen" rather than "is this screen reachable". The backlog
tracks wired-ness of the *gate* (S8.3 #6, `PlusFeatureService`) but nothing was checking whether the
*engine* was switched on.

**Suggested guard, if you want one:** a test that every `@Service` in the transfer/negotiation package
has at least one caller, in the same spirit as `PlusGateHasCallersTest`. That test would have caught
this and `RegistrationService` both.


---

## P0 — Day-precise season engine (owner, 2026-09-28, agreed in full)

The step toward a production release. Agreed with the owner; all three open decisions were answered
and two requirements were added that were not in the original proposal.

### The three decisions, as answered

1. **Advance day AND advance hour.** Both. The owner added the reason hour matters: with
   `advance hour`, when the clock reaches kickoff, "Watch match" on the schedule becomes active, and
   only clicking it populates the stats - even though the match was already generated. That is the
   production behaviour, not a test shortcut.
2. **Job placement.** The owner pushed back on my day-1..7 buckets: not necessary once `advance day`
   exists. What matters is that whatever runs today on week-advance is *split into jobs* with a
   trigger and a done-flag, not re-homed by hand into days. Every job declares when it fires and
   keeps checking until it fires, then flags itself done.
3. **Simulate-all is day-accurate, and hour-accurate.** Not "the current day" - the current day
   *and hour*. A partial day must be simulable, or the hour model cannot be tested.

### Two things I had missed

- **There is already a clock in the header**: `#clock-time` (live wall clock), `#clock-date`
  (`28.09.2026. • Season 1 • Week 1`), `#clock-phase`. The day does not need a new widget, it goes
  next to season and week in `#clock-date`, fed by `/api/game-clock`. Adding a second clock would
  have been the obvious mistake here.
- **"Watch match" becomes a gate, not a generator.** It used to create the match. Now a fixture is
  generated by the clock, and Watch renders it. Active only at kickoff.

### P1 — The clock becomes day- and hour-precise

- `GameClock` gains `currentDay` (1..7) and `currentHour` (0..23).
- **The day is a game cycle, not a weekday.** It maps positionally onto `WeekTemplate.DayKind`
  (INTERNATIONAL, FINANCE, LEAGUE, TRAINING, CUP, MORALE, LEAGUE_SECOND). Deliberately *not* derived
  from `currentDate`, because the template is a game construct and tying it to real weekdays would
  mean the same season played from a different start date behaves differently.
- `currentDate` stays as the wall-clock anchor for display only.
- Invariant: one source of truth. The stored `currentDay`/`currentHour` are advanced explicitly by
  the clock service, never inferred from `currentDate`, so there is no drift to reconcile.
- `/api/game-clock` exposes `day`, `hour` and the `DayKind` label; `clock.js` renders the day in
  `#clock-date`.
- `POST /simulation/advance/day` and `POST /simulation/advance/hour`.
- `/week/advance` is re-implemented as seven `advance day` calls, so the existing admin button keeps
  working and there is exactly one advance path.

### P2 — The job framework (this is the part that matters)

- `DayJob`: `key()`, `week()`, `day()`, `hour()`, `run(JobContext)`.
- **Idempotency is the whole design.** A `JobRun` row keyed on `(season, week, day, key)` is the
  proof a job fired. A job that is already flagged done is skipped. This is what makes
  `advance hour` safe: advancing 24 times must not run the day's job 24 times.
- `JobRunner` scans for jobs whose trigger has been reached and that are not yet flagged, runs them
  in order, and flags them. "Keeps checking until it fires" - a job whose trigger is 14:00 does not
  fire at 09:00 no matter how many times the runner is called.
- `JobRunLog` so the admin can see what ran, when, and what it changed. Without it `advance day`
  becomes another opaque state machine, which is the failure mode of the current week-advance.
- Ordering within a day is explicit (finance before matches, training before form), because several
  jobs read each other's output.

### P3 — The jobs

Re-partitioned from what `advanceWeekAndHandleSeasonTransition` does today (week+1, date+1 week,
`decrementInjuriesByWeek`, `recoverFatigueForWeek`, `expirePlayerContracts`,
`settleWeeklyFinancesForAllClubs`) plus what the template declares. Triggers are a proposal to be
settled per job, not a day-bucket assignment:

| Job | Trigger | Notes |
|---|---|---|
| Election opens | w12 d1 (registration), w1 d1 (voting) | Already implemented, needs a trigger |
| International fixtures | d1 20:45 | NT matches |
| Finance | d2 | From the weekly bundle |
| League round A | d3 19:00 | |
| Training | d4 | |
| Cup ties | d5 18:00 | The new cup work |
| Form / morale | d6 | `MoraleService` |
| League round B | d7 16:00 | |
| Week rollover (injuries, fatigue, contracts, finance summary) | d7 after round B | End of week, not a separate day |
| Season rollover | w12 d7 after round B | |

### P4..P7

- simulate-all: day- **and hour-accurate, cup included.
- schedule shows day-5 cup ties.
- Watch Your Match: cup source, gated on kickoff hour, stats populated on click.
- dashboard clock shows the day.

### Explicitly not skipped

The job framework is not a nice-to-have. Bolting cup fixtures onto the current week-shaped logic
would be faster and would have to be rewritten when the real scheduled jobs land, which is the thing
the owner asked to avoid. Sequence is P1 -> P2 -> P3, and P4..P7 are small once those exist.

### Risk notes

- Advancing an hour past several job triggers in one step is expected and must run them in trigger
  order, not in job-declaration order.
- A job that throws must not flag itself done, and must not stop the jobs after it. Partial progress
  has to be visible in `JobRunLog` or an advancing clock becomes unrecoverable.
- The existing week-advance logic is wrapped in one method. Splitting it is the bulk of P3 and
  cannot be done safely without the done-flag from P2.

### P1-P3 addendum — clock semantics settled (owner, 2026-09-28)

The owner restated the rules after four attempts got them subtly wrong. Recorded here because the
wrong version looked correct every time.

    advance hour   offset += 1h
                   hour  23 -> 0  and day  +1
                   day    7 -> 1  and week +1
                   week  12 -> 1  and season +1

    advance day    every remaining hour of the day is offered to the job runner, then the date rolls

    advance week   week +1; if the week moved off 12, week -> 1 and season +1

**Explicit counters, not derived values.** The hour, day, week and season are stored integers that
wrap. An earlier version derived the hour from the game timestamp and worked the day out by
measuring the date the clock moved across. That tied the counters to the wall clock, so a job
triggering at 23:00 was evaluated or skipped depending on what time of day the manager pressed the
button — which is exactly why `week-rollover` (day 7, 23:00) and `season-rollover` (week 12, day 7,
23:00) never fired. Counters that wrap cannot be missed that way.

`advanceWeek` is deliberately NOT seven `advanceDay` calls. Composing it that way made the end of a
week depend on the hour the button was pressed.

`gameTime()` is still reported for the ticking display — one game second per real second, since only
the offset is stored — but it no longer decides what hour it is.

Verified after the change:
- day 7, hour 21 → 22 (day-opened) → 23 (**week-rollover**) → 0, day 1, week 2.
- week 12, day 7, hour 23, advance hour → **season 2, week 1, day 1, hour 0**.

The lesson worth keeping: three separate bugs here (self-invocation, `hours/24`, and a
timestamp-derived counter) all presented as "a job silently did not run". Any scheduled job that
quietly does nothing is indistinguishable from one that has not been wired yet, so the next
scheduled job gets a live check, not a unit test.

### Scheduler: the season plays itself in production (owner, 2026-09-28)

**The question asked: does a job fire on app start in production without anyone clicking advance?**
Before this change, no. A job ran only when a button was pressed. Fine for testing, useless in
production - nobody sits there clicking Advance Hour so the season plays itself.

`GameClockScheduler` runs on the hour (`game.clock.cron`, default `0 0 * * * *`) and does two things:

1. **Advances** the clock an hour, when auto-advance is on. Advancing is what makes a job due, so
   the two belong together.
2. **Checks** what is due regardless, whether or not it advanced. This is the half the owner asked
   for and it is the important one: a job missed because the app was down over a kickoff, or one
   that failed and was re-queued, is picked up instead of being skipped for good. The done-flags make
   the second and third tick harmless.

Cron rather than a fixed rate: a fixed rate drifts against the wall clock and can fire twice in an
hour across a DST change.

**Off by default, on per profile.** `game.clock.scheduler-enabled` / `game.clock.auto-advance` are
false in dev and true in prod. Auto-advancing in dev or test would move the season underneath the
test run and make every season test time-dependent. The advance buttons stay as the manual path.

**The header shows real Belgrade time, not the offset time.** The owner settled this: the offset is a
test accelerator that moves the season/week/day counters, not a second clock. Displaying offset time
made Advance Hour look as though it had jumped the wall clock by an hour, which is not what it does.
The header shows the real time, with the game position beside it as
`Season 1 - Week 1 - Day 1 (International)`. No calendar date is shown at all, so the manager reads
the day off the season position rather than off a date that has nothing to do with the fixture list.

### OPEN QUESTION for the owner

Auto-advance rate on prod. The hourly tick advances one game hour per real hour, so a 7-day week
takes 7 real days and a 12-week season takes 12 real weeks. That is realistic but slow for a live
game. Options: hourly, or a configurable multiplier (`game.clock.minutes-per-game-hour`). Left at 1:1
because the owner has not chosen, and it is one property to change.
