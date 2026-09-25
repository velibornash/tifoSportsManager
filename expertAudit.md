# TIFO Football Manager — Expert Audit

**Date:** 2026-09-26
**Scope:** UI Football only (the SPA at `/dashboard.html`, backend `newLogic/` + `commonmanager/`).
Excluded: `footballtextmanager/`, `americanfootballmanager/`, `basketballmanager/`.

**Measured codebase size:**

| Layer | LOC |
|---|---:|
| Java main | 94,165 (689 files) |
| Java test | 3,605 (20 files) |
| JavaScript | 29,792 (65 files) |
| CSS | 12,342 |
| HTML | 5,071 |
| Markdown | 11,856 |

**Test/main ratio: 3.8%.**

---

## 1. Executive verdict

You have a **genuinely excellent match engine bolted onto a non-existent management game.**

The engine (`newLogic/sim/`, ~11.8k LOC) is real physics with proper calibration discipline, deterministic seeding, a defensible goalkeeper model, and a working offside/VAR/discipline layer. That is *better than the engine Sokker ships today*, and it is the moat. Keep it and keep polishing it.

Everything around it — economy, contracts, board, wages, training depth, AI club development, consequences — is either a stub, a single `Double` field, or client-side fiction. Right now the game is: *"watch a beautifully simulated 90 minutes, then click 'Advance Week' to watch a dice roll."*

**The biggest structural problem:** there are **four match engines and one is live**. Roughly **40,000 LOC of dead simulation code** is still in the build.

**The biggest design problem:** the *entire economy* of a 310-club football pyramid is one field — `Team.budget` — seeded to `0.0` for every club and only ever changed by transfer fees. No income, no wages, no sponsors, no prize money, no board, no failure state.

The effort has gone where it is hardest and most visible (physics, calibration, tactical AI) and skipped where it is boring but decisive (money, contracts, wages, board, AI development). That inversion is why the app *feels* deep while watching a match and *feels* shallow the moment you try to manage a club.

---

## 2. What is genuinely well done

Credit where it is due — this is not a codebase in trouble, it is a codebase with a very uneven distribution of effort.

| Area | Why it is good |
|---|---|
| **Proposal match engine architecture** (`newLogic/sim/`) | Thin orchestrator + single-responsibility engines (`BallPhysicsEngine`, `DuelService`, `GoalkeeperEngine`, `OffsideService`, `VARService`, `DisciplineService`, `FatigueSystem`, `ThreatOverrideEngine`), contracts in `EngineInterfaces`. Real ball with velocity, per-mode deceleration, lateral spin, goal-plane crossing, OOB bands. Per-tick pipeline order is explicit at `engine/MatchOrchestrator.java:114-368`. This is professional structure. |
| **Determinism** | `SimulationRandom.seed(fixture.getId())` (`SimMatchService.java:64-65`) + thread-local `Random` (`util/SimulationRandom.java:17-29`). Batch runs are reproducible, which is the only reason calibration was possible at all. |
| **Goalkeeper model** (`sim/engine/GoalkeeperEngine.java`) | Bisector positioning between ball and goal centre, advance ramp as the ball closes, 1v1 rush (`RUSH_ADVANCE 2.6`), `MAX_ADVANCE 2.8`, clamped to the mouth ± `POST_MARGIN 0.9`, never crosses the ball. Save model: `REACH = 0.34 + skill/20 × 0.32`, scaled by `1 − speedFactor × 0.40`, `chance = 1.10 × (0.70 + skill/20 × 0.50) × (1 − (d/reach)³)`. Correctly solves both Sokker failure modes (keeper magnet, keeper never saves). Covered by 11 unit tests. **Do not touch it again.** |
| **League pyramid infrastructure** | 31 leagues / 5 tiers / 310 clubs (`DatabaseInitializer.java:291-348`), circle-method double round-robin with odd-team bye padding (`SeasonService.java:173-237`), mirrored second half, **real promotion/relegation with a 2-leg playoff** (`SeasonService.java:407-566`, `:667-687`), season rollover with aging and table reset (`:360-405`). League table deliberately recomputed from scratch each round to avoid double-counting (`MatchStatisticEngine.java:231-306`). This is a lot of correct, non-obvious work. |
| **Training Reports UI** (`static/js/pages/views/training-view.js:577-799`) | Per-player, per-skill `before / after / decimalΔ / integerΔ` with the focus skill accented, plus a full season×week matrix graph rebuilt from serialized JSON reports (`TrainingProgressionService.java:160-188`). **Better reporting than Sokker has.** |
| **Junior promotion reveal** (`academy.js:186-193`, `player-view.js:290-318`) | Backend returns `allocationSequence`; UI blanks the 7 skill cells and allocates 1 point per second with a "remaining budget" banner. The best-designed single UX moment in the app. |
| **Tactic editor** (`tactic-editor-view.js`, 311 lines) | Zone-state drag-to-shape tactical rules, localStorage drafts with discard/save, version counter, 5 set-piece taker selectors. A real tool, not a mockup. |
| **Pass-failure taxonomy** | Measured: DEFLECT 22.2% / LOOSE_PICKUP 14.7% / INTERCEPT 14.7% / DUEL 9.1% / OFFSIDE 3.4% / OOB 2.0% / FOUL 0.6%. Those are *football* failures, not model artefacts. A strong signal that the ball model is honest. |
| **Threat override discipline** | TYPE A (press carrier within 1.5 cells) / TYPE B (isolated opponent in own 3-cell band) / TYPE C (offside retreat after 3 consecutive). `isClosestEligiblePresser` (`ThreatOverrideEngine.java:262-276`) guarantees exactly **one** presser per threat — no 3-player swarm. This is a solved problem most engines never get to. |
| **Auth + role separation** | JWT filter, `/admin/**` correctly gated to ADMIN/OWNER/DEV, `/auth/**` public. |

---

## 3. P0 — Blocking defects

These are correctness and integrity bugs. Each one is small to fix and expensive to leave in.

### 3.1 There is no economy. `Team.budget = 0.0` for all 310 clubs.

`util/teams/TeamFactory.java:56-58`:
```java
newTeam.setBudget(0.0);
newTeam.setReputation(50.0);
newTeam.setJuniorCoachSkill(35 + new Random().nextInt(51));   // 35-85
```

`DatabaseInitializer.addTeamToLeague()` (`:483-512`) never overrides budget or reputation for any club, including `OFK Omladinac`.

The **only** money movement in the entire main domain is `TransferService.completeTransfer:332-334`:
```java
buyerTeam.setBudget(round2(buyerBudget - price));
sellerTeam.setBudget(round2(sellerBudget + price));
```

Repo-wide grep for `wage|salary|sponsor|prize|financ|revenue|income|expense|profit|ledger|loan|debt` in `newLogic/` returns only: the `budget` field, the two lines above, an `INSUFFICIENT_BUDGET` guard, a `BUYER_BUDGET_CHANGED` notice, and a ≤6% attendance boost in `AttendanceService:49`.

**Consequences:**

- Every club starts broke. The transfer market is economically dead on a fresh database.
- **No wages.** `Player.earnings` (`model/Player.java:26`) is seeded with realistic values in `PlayerFactory.java:41` and then **read by nothing in the domain.** It is a display-only column.
- No sponsors, no prize money, no TV/broadcast money, no gate revenue (`Stadium.ticketPrice` is seeded and used by nothing), no Financial Fair Play, no board budget, no ledger, no loans, no debt.
- Week advance (`SeasonService.advanceWeekAndHandleSeasonTransition:318-338`) does injuries → youth intake → transfer market. **It does not touch finances at all.**
- **All 310 clubs have `reputation = 50.0`.** Reputation drives attendance (`AttendanceService`), the playoff coin flip (`SeasonService:660-665`), and offer-acceptance odds (`TransferService:676-680`) — so all three are flatlines.
- `JuniorCoachSkill` is a random 35–85 roll with **no UI to change it** (`YouthAcademyService.java:369-374`).

**The Finances page is 100% client-side fiction.** `static/js/pages/features/club-management.js:21-66`:
- Fetches `/demo/teams/${teamId}/profile` → `DummyDataController:57` returns a hardcoded `budget: 1,200,000` for **any** club (the real route is `/teams/{id}/profile` at `TeamController.java:122`).
- `transferBudget = max(budget × 0.38, squadValue × 0.04, 50000)`
- `weeklyWageBudget = max(squadSize × 1850, avgValue × 0.0015, 12000)`
- `monthlyIncome = max(budget × 0.055, squadValue × 0.018) + squadSize × 4500`
- 3 sponsors are literal objects named `${profile.name} Main Partner`, `Regional Media Deal`, `Matchday Hospitality`
- The 6-month balance history is generated from `new Date()` with fudged multipliers

> **You already wrote the economy — in the wrong package.** `footballtextmanager/CleanSheetService.java:427-434` implements `applyRoundFinances` with a real wage bill (`roster.earnings × 0.58`), sponsor income (`10000 + reputation × 180`) and gate income, and `CSDataInitializer.java:144` seeds budgets at `500_000 + rng.nextInt(500_000)`. None of it is shared with the main UI football domain. This is a port, not a greenfield build.

### 3.2 Free players for €1

`TransferService.normalizePrice:421-424`:
```java
private double normalizePrice(Double requestedPrice, double fallbackPrice) {
    double resolved = requestedPrice == null ? fallbackPrice : requestedPrice;
    return Math.max(1.0, resolved);
}
```

There is **no check that `price >= askingPrice`**, in `normalizePrice` or in `completeTransfer`. `buyListedPlayer:181-186` passes the user-supplied price straight through. The UI uses `window.prompt` with only a `numeric <= 0` guard (`club-management.js:185-189`).

**Enter 1, own any listed player.**

### 3.3 Infinite free training

`TrainingProgressionService.runWeeklyTraining:68-130` has **no idempotency guard**. `TrainingWeekReport` is found-or-new per `(team, season, week)` and overwritten (`:116-128`), but `applyWeeklyGrowth` runs unconditionally for every player on every call.

The "Run Weekly Training" button (`training-view.js:331,405-420`) calls it directly. **Spam it for unlimited skill points** — and because the report is overwritten, the output still looks like a single clean week.

### 3.4 ~90% of all training work has zero effect on matches

The dual `int` + `Double *Exact` representation in `model/Skills.java:17-34` exists **precisely because weekly growth is fractional** (0.05–0.6 points/week, see §6). But:

- `getRatingScore():118-126` and `getTotalForRating():128-133` read the **`int`**
- Every fluent accessor — `pace()`, `shooting()`, `passing()`, `technique()`, `defending()`, `playmaking()`, `goalkeeping()`, `stamina()` (`:137-144`) — returns the **floor'd `int`**
- `syncVisibleFromExact():107-116` does `Math.floor` into the display ints

A player at 13.9 technique is **byte-identical** to one at 13.0 as far as the match engine is concerned. The entire `*Exact` layer — the reason the dual representation exists — is display-only. Only the Training Reports UI reads the doubles (`training-view.js:613`).

### 3.5 Fatigue never recovers on its own

`fatigue` is written in 4 places and recovered in **one**:

| Location | Change |
|---|---|
| `engine_v1/RealisticMatchEngine.java:1353` | GK +1 every 18 min |
| `engine_v1/RealisticMatchEngine.java:1374` | outfield +1 at 16–24%/min, +2 after min 75 |
| `engine_v1/RealisticMatchEngine.java:1456` | injury +6 |
| `service/TeamMedicalService.java:55` | **−12, manual button only** |

There is **no weekly passive decay** anywhere. `SeasonService.decrementInjuriesByWeek:341-358` touches only injury days. `TrainingProgressionService` never touches fatigue.

And `static/js/pages/views/medical-view.js:95` **tells the user** *"Weekly passive healing still applies."* That is false in the backend.

**Consequence:** fatigue is a monotonic ratchet toward 100 — which is exactly the input to `maybeTriggerInjury` (`RealisticMatchEngine:1426`) and to `Team.getAvailablePlayers()` (`model/Team.java:69`, `fatigue < 8`). The medical button stops being a strategic option and becomes a mandatory chore that dominates the medical page. It is also **completely free and unbounded** — no cooldown, no cost, no medical-staff quality factor. Click nine times: fatigue 100 → 0.

### 3.6 Penalties are awarded but never taken

`sim/engine/DuelService.java:101-105`:
```java
appendDisciplineEvent("PENALTY_AWARDED", msg, fouled.getTeam(), ...);
stats.onPenalty(fouled.getTeam());
```

`sim/engine/ActionLogService.java:50` declares the channel names `"PENALTY_KICK"`, `"PENALTY_MISS"`, `"PENALTY_SAVED"`.

**No code in the repository produces any of those three strings.** There is no taker selection, no run-up, no keeper dive, no conversion model. A penalty increments a counter and play resumes.

Calibration is otherwise fine — `PENALTY_FROM_BOX_FOUL = 0.06` (`rules/DisciplineService.java:39`) produces 0.2 penalties/match vs a real ~0.27, so the *award rate* is right. Only the *execution* is missing.

> Sokker's famous bug is 80% missed penalties. This engine's bug is 100% un-taken penalties. Strictly worse, and much easier to fix.

### 3.7 No substitutions. Ever.

`sim/model/Player.java:112` has `isUnavailable() { return sentOff || injured || substituted; }` and a `substituted` flag (`:25`, `:109-110`) — but **nothing ever sets it.**

`newLogic/sim/backlog.md:489-492` states it plainly:
> *"currently constructs starting XIs only; there is no bench/slot/substitution-limit contract exists"*
> *"Injury risk increase with fatigue — SKIPPED 2026-09-25: injury/substitution activation depends on the missing bench contract above."*

Consequences: the same 11 play 90 minutes regardless of fatigue, injuries or bookings. A red card (`rules/DisciplineService.java:144` sets `setSentOff(true)`) means 10-vs-11 for the rest of the match with no recourse. No tactical change, no injury replacement, no rotation option.

**This is the single biggest gap in the match engine — bigger than any physics refinement.** It is also the reason fatigue and injuries barely matter: the cost of being tired is only a slightly slower player, never a lineup change.

### 3.8 Transfer-list soft-lock

A bare-club-name "Register interest" (`addInterest:141-161`) writes a club name with no price (`:159`). Then:

- `hasOpenOffer:387-392` requires `isOfferEntry` (contains `" offered "`) → a bare name is **not** an offer → `canAcceptOffer` / `canRejectOffer` are false
- `removalAllowed = ownedByViewer && isActiveListing && sortedInterests().isEmpty()` → **false** (`TransferService.java:776`; same at `:131`, `:171-174` throws 409 `ACTIVE_INTEREST`)
- `rejectOffers:248-269` is the only clear path, and it is only reachable when `canAcceptOffer` is true

**Result: the moment any other club registers interest, that player can never be delisted again.** The UI confirms it — `player-view.js:164-166` renders a permanently `disabled` button, `club-management.js:421,501` disables it with *"Cannot remove while another club has already registered interest."* No admin override exists.

---

## 4. P1 — Four engines, one live

| Engine | LOC | Reachable from UI? | Verdict |
|---|---:|---|---|
| `newLogic/sim/` (proposal) | 11,783 | ✅ **LIVE** — `/simulation/current-round/prepare` → `SimMatchService` → `SimReplayStore` → `demo/service/ui/proposal/index.html` | **Keep and finish** |
| `newLogic/engine_v1/` (RealisticMatchEngine) | 9,775 | ❌ Spring bean whose only entry point, `WeekPreparationAsyncService.startOrGetRunningJob`, has **0 callers** | Delete |
| `newLogic/engine/` (MatchSimulator v2) | 4,563 | ❌ only `/api/v2/match/*`, called only by `realisticDemo.html`, which **nothing links to** | Delete |
| `demo/service/` | 19,488 | ❌ only its **viewer HTML/JS/CSS** is reused | Freeze as reference |
| `demo/swingUIDemo/` | 11,912 | ❌ desktop JVM `main()` | Delete |

The dashboard button is still `id="start-realistic-demo-btn"` (`dashboard.js:164`) and `/start-realistic-demo` is still in the security permit list (`SecurityConfig.java:90`) — **no controller maps it any more.** The name is a fossil. `AGENTS.md` still documents that path as the primary runtime.

**Two concepts from the dead engines are worth salvaging before deletion:**

- `newLogic/engine/MatchSimulator.clampPaceThisTick():361-379` — the hard no-teleport invariant (speed-capped movement, blended over many ticks). Conceptually correct and not present in the proposal engine in this form.
- `newLogic/engine/ZonePositionCalculator` (229 lines) + the 5×5 zone model — cleaner than the proposal engine's `TacticsRules` cell model in places.

### 4.1 The worse half: AI-vs-AI is a dice roll

The user's own match goes through the tick engine. Everyone else's goes through `engine_v1/MatchEngine.simulateQuickScore:1578-1602`:

```java
double homeExpectedGoals = calculateExpectedGoals(homeStrength, awayStrength, homeTactics, awayTactics, true);
return new QuickSimScore(sampleGoals(homeExpectedGoals, random, 6), sampleGoals(awayExpectedGoals, random, 6));
```

A strength-ratio → Poisson draw. Then:
- AI lineups are **hardcoded 4-4-2** regardless of the club's actual tactics (`:1285-1286`)
- Events are **synthesised after the fact from the final score** (`generateSimulatedMatchEvents:1323`)
- `MatchStatisticEngine.simulateInjuriesAndCards:31-46` is pure theatre — 5% chance to `log.info(...)` and **discard** the `InjuryEvent`

**So the league table is statistically incoherent with your match engine.** You watch 29 shots and 12 on target; the neighbours' results come from a different probability distribution entirely, with a hardcoded formation. 3,600 ticks × 22 players is trivially cheap in Java — there is no performance reason for this split.

---

## 5. P1 — Engine calibration (measured, 25 matches, seed 42)

Measured via `ProposalSeasonDiag`; cross-checked against the 200-match figures in `PROPOSAL_SEASON_REPORT.md`.

| Metric | Measured (25) | Real PL | | Verdict |
|---|---:|---:|---|---|
| **Goals** | **5.4** | 2.7 | ❌ | **2× too many** |
| Shots | 29.4 | 25 | | ✅ close |
| Shots on target | 12.9 | 8–9 | | ⚠️ 1.4× |
| **On-target %** | **43.9%** | 33% | | ⚠️ **root cause** |
| Saves | 9.8 | 3–4 | ❌ | 2.5× |
| Shots missed | 7.2 | ~14 | ⚠️ | low (mirror of SOT%) |
| On-target → goal | 43.3% | ~30% | ⚠️ | |
| Passes attempted | 582 | 450–500 | ⚠️ | 1.2× |
| Pass accuracy | 77.9% | 80–86% | ✅ | defensible, keep |
| Through balls | 13.2 | 5–10 | ⚠️ | slightly high |
| Crosses | 19.6 | 15–25 | ✅ | |
| Centres | 39.8 | 25–35 | ✅ | |
| Carries | 247.5 | — | ⚠️ | semantics, not a bug |
| Clearances | 90.9 | 20–30 | ⚠️ | semantics |
| **Duels won** | **598.5** | ~100 | ❌ | **6×** |
| **Interceptions** | **36.1** | 12–16 | ❌ | 2.4× |
| Deflections | 67.0 | — | ⚠️ | high |
| **Corners** | **5.0** (0.6 HOME / 4.4 AWAY) | ~10 | ❌ | half **and 7:1 skewed** |
| **Goal kicks** | **40.7** | 12–15 | ❌ | **3×** |
| **Throw-ins** | **18.7** | 35–45 | ❌ | half |
| Offsides | 3.6 | 2–4 | ✅ | |
| Fouls | 29.6 | 22 | ⚠️ | 1.4× |
| Yellow cards | 5.0 | 4–5 | ✅ | |
| **Red cards** | **1.0** | 0.2 | ❌ | 5× |
| Penalties | 0.2 | 0.27 | ✅ | (but never taken — §3.6) |
| VAR reviews / overturned | 2.8 / 0.3 | 1–3 / 0.2–0.5 | ✅ | |
| **Possession** | **49.4 / 50.6** | 50 / 50 | ✅ | **fixed**, was 75/25 pre-mirror-fix |
| Nil-draws | **0 / 200 matches** | ~6% | ❌ | never 0-0 |
| Highest score | 10 | 7–8 | ⚠️ | |

### 5.1 Goal mouth width — corrected

**Correction to an earlier draft of this audit:** the goal mouth is **10 m, not 14 m.**

`sim/model/PitchEnvironment.java` + `sim/model/GoalPhysical.java`:
- Pitch is 98 m × 60 m
- **7 rows over 98 m → 14 m per row** (length axis)
- **6 cols over 60 m → 10 m per col** (width axis)
- `GoalPhysical.MOUTH_LEFT = 3.5`, `MOUTH_RIGHT = 4.5` — defined in **column** space

So the mouth is `4.5 − 3.5 = 1.0` col = **10 m**, versus the real 7.32 m. That is **1.37×**, not 2×. An earlier draft of this audit mis-stated the axis and called it 14 m / 2×; that was wrong.

**10 m is still generous and worth tightening, but it is not the main driver of the goal inflation.** The main driver is the next point.

Secondary note: `POST_RADIUS = 0.03` cells = 0.3 m against a real 6 cm post. The code comment says `0.42 m`, which implies the 14 m figure was assumed when it was written. Immaterial to physics (posts are point obstacles on the goal line) but the comment should be corrected to 0.3 m, and the radius reduced to ~0.006 cells if posts should realistically block shots.

### 5.2 The actual goal-inflation chain

Three compounding causes, in order of impact:

1. **On-target % is 43.9%, should be ~33%.** `ExecutionQuality.evaluateShot:115-185`:
   ```
   skillBase    = 0.08 + striker × 0.020            → 0.10 (skill 1) .. 0.48 (skill 20)
   distFactor   = max(0.25, 1 - dist/9)
   onTargetProb = skillBase × distFactor × (1 - pressure/200) + 0.12 (if dist < 2.0 cells)
   capped at 0.85
   ```
   With 29.4 shots × 43.9% = 12.9 on target, and 43.3% of those going in → 5.4 goals.

   **Fix:** `skillBase = 0.06 + striker × 0.015`, close-range lift `+0.12 → +0.08`.
   **Predicted:** SOT ≈ 9.5, goals ≈ 4.0, saves ≈ 6.5.

2. **The on-target aim distribution is far too flat.** Shots on target are spread uniformly across `3.55–4.45` (`ExecutionQuality:133-138`) — 0.9 col = **9 m of aim spread**. Real shots on target cluster in the two corners; a keeper covers the middle ~2–2.5 m of a 7.32 m mouth, so roughly 60–70% of the on-target area is *not* covered.

   **Fix:** narrow the mouth to ~7.4 m (`MOUTH_LEFT = 3.63`, `MOUTH_RIGHT = 4.37`) and bias the aim distribution toward the posts — sample the outer 40% of the mouth rather than the full width. This alone should account for a large share of the excess.

3. **The mouth is 10 m instead of 7.32 m** (§5.1). +37% of width compounds the above.

Do (1) and (2) first — they are worth more than the mouth width and (2) is the one most engines get wrong.

### 5.3 Restarts are inverted: 40.7 goal kicks, 18.7 throw-ins, 5.0 corners

Root cause, documented at `PROPOSAL_SEASON_REPORT.md:143-148`: `ActionExecutor.executeClear:353-361` launches clearances at `MAX_BALL_SPEED` (1.5 c/t) with `AIR_DECEL` 0.15, so **flight distance = v²/2a = 7.5 cells regardless of the 2-cell aim point.** Every clearance leaves the pitch down an end line, converting what should be a throw-in or a corner into a goal kick.

**Fix:** launch clearances at ~0.95 c/t (≈9 m/s) toward a 3-cell lane. That alone should move ~15 goal kicks/match into throw-ins and corners.

### 5.4 Corner skew 0.6 vs 4.4 — genuinely un-diagnosed

Shot volume, SOT, interceptions, clearances, goal kicks and possession are all symmetric. **Only corners are 7:1.** Prime suspects:

- `PitchEnvironment.oobRestartType:53-64` decides the restart by **row only**, with no column awareness — a ball out behind the goal line on the left flank and one behind the right flank both become corners.
- `GoalkeeperEngine.java:93` computes `targetCol = ballCol + (goalCentreCol − ballCol) × t` with `goalCentreCol = 4.0`, interacting with the 9-row / 8-col mirror. The keeper's asymmetric resting position relative to the mirror may bias which end-line deflection he reaches first.

Needs one diagnostic run with a corner-side column before any fix is attempted.

### 5.5 598 duels per match

`DuelService.detectAndResolveDuels:48-83` loops **all opponents × every carrier × every tick**, and DRIBBLE / RECEIVE_PASS / SHOT_BLOCK radii fire on **proximity alone** — with no requirement that the defender be goal-side, facing the ball, or actually interposing. Real football has roughly 50 contested duels per team per match.

**Fix:** require the defender to be inside the carrier→nearest-teammate cone (i.e. genuinely interposing) before a duel fires, and make `DRIBBLE_DUEL_RADIUS` context-dependent.

Related: `PRESS_DRIB_DUEL_RADIUS = 0.50` (7 m) (`DuelEngine.java:20-26`) is a **workaround for the absence of a sprint multiplier.** `MovementEngine` has no chase/press speed bonus at all (deliberate owner rule, 2026-09-14), so a chaser can never be faster than the defender they are chasing — and the tackle radius had to be inflated to 7 m to make contact happen. Adding a bounded chase multiplier (`×1.15–1.25`, fatigue-scaled) would let that radius come back down to a realistic 2–3 m and make pressing look like football.

### 5.6 Structural engine limits (known, documented, deferred)

- **No Z axis.** No headers, no aerial duels, no crossbar physics. `AERIAL_DUEL_RADIUS` and `SHOT_BLOCK_RADIUS` (`DuelEngine.java:147-151`) are computed but unreachable. Crosses are lofted but never contested in the air.
- **THRU / CROSS / CENTER are only reachable from the final-2-row hard rule** (`CleanDecisionEngine.java:141`). `selectOptionWithPlaymaking` only ever sees `pass, carry, shot, clear` (`:126-127`). So the tactical variety exists on paper only.
- **All 506 possession-context tactical rules are identical** (`backlog.md:381-385`) → `TacticsRules.desiredCell(role, ball, team, possessionTeam)` ignores the possession argument in practice. In-possession vs out-of-possession shape is a no-op.
- **3 dead code paths:**
  - `FootballRules` instantiated at `MatchOrchestrator.java:80` and never read — offside is handled entirely by `OffsideService`
  - `nearestOpponentBeatsHimToIt` (`CleanDecisionEngine.java:689-703`) written but never called, yet documented as an active fix in three separate MD files
  - The pass-moment offside block (`MatchOrchestrator.java:204-216`) is unreachable because `OffsideService.checkOffside` never returns `confirmed=true` (`:121,186,196,199` all return false; only `confirmOffside():263` returns true and its value is discarded)
- `Player.form` (0.5–1.2) exists in `sim/model/Player.java:29` and is **never read** anywhere in the engine.
- `isPathBlocked()` always returns `false` (`CleanDecisionEngine.java:862-865`), so the carry "+12 clear path" bonus is unconditional.
- `tackles` in the stats output is a mirrored copy of `duelsWon` (`ProposalStatsCollector.java:209-214`) — visible in diagnostics as `598.5 = 598.5`. The loser's counter is labelled "tackles".
- **`SimReplayStore` is in-memory only** — an unbounded `ConcurrentHashMap` (`SimReplayStore.java:17-29`), never persisted. A server restart loses every replay, and `Match.replayId` survives in the DB as a dangling id.

---

## 6. P1 — Training: 3/10 depth

### 6.1 What actually happens each week

Triggered from `AdvanceWeekAsyncService.java:78-82` (skipped on `PLAYOFF_WEEK`) and the older `SimulationController.java:264`. Season shape: `LEAGUE_ROUNDS = 18`, `PLAYOFF_WEEK = 19`, `FRIENDLY_WEEK = 20` (`SeasonService.java:21-23`) → training fires **19× per season**.

Per-player loop (`TrainingProgressionService:87-110`): role from advanced assignment or `roleFromPosition()` → `dtSkillForRole(setup, role)` → `applyWeeklyGrowth` → `syncVisibleFromExact()`.

```
base = 0.52
dt   = base × talentFactor × ageTrainingFactor(age, skill)
            × levelResistance(currentExact) × advancedFactor × randomFactor
advancedFactor = 1.0 (Advanced) | 0.5 (formation only)
randomFactor   = 0.85 + random × 0.35        → 0.85 … 1.20
```

**Measured growth, direct skill (mean/week):**

| Scenario | min | mean | max |
|---|---:|---:|---:|
| 18yo, talent 7, skill 3, **Advanced** | 0.481 | **0.581** | 0.680 |
| 18yo, talent 7, skill 3, formation only | 0.241 | **0.290** | 0.340 |
| 18yo, talent 5, skill 8, Advanced | 0.312 | 0.377 | 0.441 |
| 23yo, talent 6, skill 10, Advanced | 0.271 | 0.327 | 0.383 |
| 28yo, talent 6, skill 14, Advanced | 0.152 | 0.184 | 0.215 |
| 31yo, talent 6, skill 16, Advanced | 0.091 | 0.110 | 0.129 |
| 35yo, talent 6, skill 18, Advanced | 0.030 | 0.036 | 0.042 |

Secondary ("general") growth: every *other* trainable skill gains `dt / 5.0 × generalSkillModifier × levelResistance` → typically **0.02–0.11 points/week**. The direct skill is excluded, so no double-dip.

Outfield general pool is `{DEFENDER, PACE, TECHNIQUE, PLAYMAKER, PASSING, STRIKER}` (6) — **so an outfield player's `goalkeeper` skill can never grow.** `normalizeDtSkill:415-430` only permits `"goalkeeper"` for role `GK`.

Slow-skill penalty (direct gain only): `STRIKER × 0.76`, `PACE × 0.86`.

Stamina: `if (week % 4 == 0)` → 5× per season, +0.03 to +0.243. Stamina is **never** in the general pool and **never** subject to aging decay (`:320-321`), so it climbs monotonically to the 20.99 cap and stays.

Aging decay: only `age >= 29`. `base = 0.03 + (age − 29) × 0.025` (×1.25 for PACE), scaled by `max(0.8, skill/12)` — **taller players decay more.** Hard cliff at 29 (28yo = ×0.75, 29yo = ×0.70).

Caps: hard 20.99 (`Skills.java:82`), soft via `levelResistance = max(0.08, 1 − (exact/22) × 0.85)` → 1.000 at 0, 0.614 at 10, 0.420 at 15, 0.227 at 20.

### 6.2 The design space is two knobs

`model/TeamTrainingSetup.java` is 32 lines total: `dtSkillGk`, `dtSkillDef`, `dtSkillMid`, `dtSkillAtt` (all `String`), plus `advancedAssignmentsJson`. Advanced is capped at 10 entries on save (`:59`).

`normalizeDtSkill:415-430` allow-list:
```
pace, defending, technique, passing          → all roles
+ goalkeeper                                → GK
+ playmaker                                 → MID
+ shooting                                  → ATT
```

So GK has 5 options, MID 5, ATT 5, DEF 4. **There is no stamina option, no fitness option, and no per-player skill choice.** The only individual control is which of the 4 *role buckets* a player trains.

`createDefaultSetup:190-214` auto-creates and **persists a default from a `@GetMapping` call** (`:32-40`): 4 sliders set, and the **first 10 players from an unordered `findByTeamId`** marked Advanced. A user who never opens the Training page gets 10 arbitrary players on 2× growth.

### 6.3 There is no trade-off

- **No intensity setting.** Not in the entity, not in the UI.
- **No training cost.** `runWeeklyTraining` never touches `Team.budget`.
- **No training-induced injury risk.**
- **No training-induced fatigue.** The classic FM dilemma — push a player hard for a cup run or protect him — does not exist.
- **The only cost in the whole system** is that Advanced slots are capped at 10.

So training is **strictly dominant and free.** The one real decision is *which 10 of your 20–25 players get 2× growth* — which is meaningful, but it is one decision, not a system.

### 6.4 Coaching staff is fake and has zero effect

There is **no `Staff` / `Coach` JPA entity anywhere in the codebase.** `static/js/pages/features/staff-directory.js` builds 4 coaches + 2 scouts + 3 medical staff entirely in the browser with hardcoded names, ages, contract ends, wages and ratings (`:12-51`). The only backend call is `/demo/teams/{id}/coaches` → `DummyDataController:28-35`, which returns three literal strings:
```java
list.add(new CoachDto("John Smith", "Head Coach", 85));
```

Coach quality has **zero** effect on training. The only coach-ish stat in the whole domain is `Team.juniorCoachSkill`, a random 35–85 roll with no UI (`YouthAcademyService.java:369-374`), and it affects only the academy.

### 6.5 AI teams never train — and never age out

`runWeeklyTraining` is called **only for `userTeam`** (`AdvanceWeekAsyncService.java:81`, `SimulationController.java:264`).

Consequences:
- Every other club in the database is **frozen forever** — no growth, no decline
- Aging decay lives *inside* `TrainingProgressionService`, so AI squads never decay either
- League balance never evolves; the pyramid is static
- A promoted club's players never improve, so promotion/relegation has no long-term meaning

### 6.6 Dead and duplicated training code

| Item | Status |
|---|---|
| `service/TrainingService.java` (21 lines) | `trainTeam()` is `// TODO: implementirati trening`; `assignBasicTraining()` is `// dummy logika`. **0 callers.** |
| `service/PlayerSkillProgressionService.java` (72 lines) | Second legacy engine, `baseGrowth = 0.1`, hard cap **17**, and an **inverted** talent factor `talentFactor = (10 − talent)/10` (high talent = worse growth). Wired only to the 2 dead `POST /training/train*` endpoints. **0 live callers.** Contradicts the live engine. |
| `model/TrainingAssignment.java` | Orphaned POJO (not an entity, 0 references) with `playerId / trainingPosition / advanced / minutesPlayedLastWeek` — a half-designed richer model that was never built. |
| 6 of 13 `/training/*` endpoints | Never called by any frontend file (`GET /training`, `POST /player/{id}/formation`, `POST /advanced/{id}`, `DELETE /advanced/{id}`, `GET /player/{id}`, `POST /train/{id}`, `POST /train-all`). |
| `training-view.js` | Contains **two ~450-line parallel implementations** of the same reports UI (`loadTrainingReports` = Setup at `:7-453`, `loadTrainingReportsPage` = Reports at `:455-907`), each re-implementing `fetchSummaries` / `openPlayerGraph` / `renderGraph`. |

### 6.7 Verdict vs Sokker / FM

| Present | Missing |
|---|---|
| Talent + age + diminishing-returns growth | **Individual training focus** — the #1 FM feature. Cannot say "this striker drills finishing." |
| PACE/STRIKER slow-skill penalty | **Intensity vs injury risk** — completely absent |
| Aging decay from 29 | **Coaching staff attributes** — staff is fake frontend data with no simulation effect |
| Advanced/generation 2× split (cap 10) | **Facilities** — no training-ground entity, money cannot be spent |
| Excellent weekly reporting + season graphs | **Position-specific learning curves** — only a global PACE/STRIKER penalty |
| 4-weekly stamina tick | **Attribute caps by age** — a 40yo still trains at 1.10× |
| | **Training camps / pre-season block** |
| | **Mentoring pairs** |
| | **Morale / form / personality as growth inputs** |
| | **AI clubs training** |
| | **Any fatigue cost** |

Roughly: a correct weekly growth formula attached to a design space of 2 knobs.

---

## 7. P1 — Juniors: 3/10 depth

### 7.1 What works

- **Intake once per season, week 2** (`SeasonService.java:329-330`, guarded `if (newWeek == 2)`)
- Hard cap **10 active juniors**; `rollIntakeCount:376-386` weighted 1..10 by `{3,6,9,12,14,14,12,9,6,3}` → **mean ≈ 5.7/season**
- Age `15 + random(5)` → 15–19; talent `rollTalent:388-398` weighted `{1,2,8,12,16,18,17,14,3,1}` → mode 6, mean ≈ 5.9. **Talent ≥ 9 is only 4.3%.**
- `academySkillExact = random(0..15) + random × 0.99` → 0.00–15.99
- Weekly progression `computeWeeklyDelta:342-354`: `0.24 × coachFactor × talentFactor × levelFactor × randomFactor`, 8% chance of a small negative swing
- One-season decision lock (`loadDecisionJunior:173-184` — `arrivalSeasonNumber >= currentSeason` → 409 `DECISION_LOCKED`)
- `createSeniorFromJunior:186-225` — `age = max(17, junior.age + 1)`, talent copied verbatim, **position rolled at promotion** (12% GK, then ⅓ each DEF/MID/ATT), skillset built by spending a points budget one point at a time into 8 skills each capped at 10
- Budget `max(6, round(academySkillExact × 3 + random(9) − 4))` → an academy-skill-14 junior gets ~42 points → **average ~5.25 per skill**, i.e. a genuine squad-rotation player. Well tuned.
- `estimateJuniorMarketValue:283-317` — sensible, with age and position modifiers
- The reveal animation (see §2)

**Measured time to develop** (talent 6 / coach 60): **~53 weeks (2.8 seasons)** to go 5 → 14 academy skill, ignoring the frozen carryover weeks.

### 7.2 What's missing

- **No scouting or recruitment network.** `generateSeasonIntakeForWeek2` iterates clubs and rolls locally. No foreign youth, no multi-country scout network, no assignments, no discovery.
- **`Country.youthRating` is already seeded (45–95, `DatabaseInitializer.java:293-302`), exposed via `CountrySummaryDTO`, written by `TeamFactory.java:37` — and read by nothing.** A purpose-built dead hook for exactly this feature.
- **No report uncertainty.** `academySkillExact` is displayed raw (`academy.js:59`). Sokker shows a scouting *range* and hides the truth — that single change would add a whole dimension of academy management.
- **No potential vs current ability.** `talent` 1–10 is shown openly; there is no hidden ceiling.
- **Position is rolled at promotion, not at intake.** Nice surprise, but you cannot plan a development pathway.
- **Carryover juniors freeze completely** (`:81-85` — `arrivalSeasonNumber < season` → `delta = 0`). A pending 19yo wonderkid develops nothing for a whole season. Deliberate, but harsh.
- One decision per season, then the slot is frozen. **No mid-season promotion.**
- No academy quality / facilities rating — identical for every club except the un-editable random `juniorCoachSkill`.
- No loans, no "released to free agency", no compensation, no personality, no work rate, no injury susceptibility, no preferred foot, no nationality, no birth date.
- Junior training is fully decoupled from senior training — promoting a junior runs them through the senior system with zero continuity.

Roughly a Sokker "tier-3 academy with no scouts" — with a better promotion reveal than Sokker has.

---

## 8. P1 — Transfers: 2/10 depth, ~15% of Sokker

### 8.1 The data model is the root problem

`model/Transfer.java:34-35`:
```java
@ElementCollection
private Set<String> interestedTeams = new HashSet<>();
```

Offers are stored as **English prose strings**: `"Partizan offered €450000"` (`TransferService:566`), parsed back by `parseOfferDetails:641-660` via `indexOf(" offered €")` + `replaceAll("[^0-9.]","")`.

Consequences:
- A club name containing `" offered €"` breaks parsing
- Renaming a club orphans every offer it made (resolution is `teamRepository.findByName(...)` at `:600`)
- A bare name and a priced offer cannot be distinguished except by `isOfferEntry:662-664` — a `contains(" offered ")` string test
- The dedupe at `:562-565` is a **prefix** match, so a club named `Partizan` will wipe a genuine offer from `Partizan United Youth`
- Offers carry no timestamp, so there is no transfer history and no window enforcement

### 8.2 Negotiation is one HTTP call and one dice roll

`isOfferAccepted:666-684` is the entire negotiation model:
```java
ratio = price / player.playerValue
acceptanceChance = 0.18
  + 0.16 if ratio >= 0.85
  + 0.22 if ratio >= 1.0
  + 0.14 if ratio >= 1.1
  + 0.08 if ratio >= 1.2
  - 0.05 if age <= 21
  - 0.06 if buyerRep + 6 < sellerRep
clamp(0.12, 0.82)
```

No counter-offer, no rounds, no agent fee, no personal terms, no player refusal. `direct-buy:189-221` is worse — on a listed player it completes immediately with **no acceptance check at all** (`:209-214`); on an unlisted player it is the same coin flip.

**The seller cannot choose which offer to accept.** `acceptBestOffer:229-245` → `resolveBestAcceptableOffer:579-597` sorts valid offers descending by price and takes `getFirst()`. `rejectOffers:255` is all-or-nothing (`interestedTeams().clear()`).

### 8.3 The AI market is static

`maybeCreateAiListing:430-458` — 42% chance/week, squad ≥ 14, <2 open listings, asking = `value × (0.88…1.12)`. Reasonable.

`maybeCreateIncomingOffer:460-534` — 68% chance/week, but:
- **It only ever targets human players** (`:465-469`). **AI↔AI transfers never happen at all.**
- The buyer is `randomItem(candidateBuyers)` (`:498`) — uniform random. **No needs model, no position logic, no quality-vs-squad-strength logic, no budget tiers, no ambition.**
- Offer = `value × (0.80…1.20)`, silently **clipped to the buyer's budget** instead of rejected (`:499-506`)
- Exactly one offer per week, max

### 8.4 What does not exist

Repo-wide grep across `newLogic/` for `workPermit|foreignPlayer|loan|contractLength|wage|boardRating|managerRating|sponsor|scouting|transferWindow` → **zero matches.**

| Feature | Status |
|---|---|
| Contract length / expiry | ❌ absent |
| Wages / wage demands | ❌ `Player.earnings` read by nothing |
| Transfer window / deadline | ❌ open 52 weeks/year |
| Free agents / out-of-contract | ❌ **impossible by construction** — `requirePlayerTeam:402-407` throws 409 `PLAYER_UNASSIGNED` |
| Loans / loan-with-option | ❌ |
| Sell-on clauses | ❌ |
| Installments / deferred fees | ❌ |
| Player refusing a move | ❌ (no wage demand → no refusal) |
| Work permits / foreign limits | ❌ |
| Squad registration limit | ❌ squads grow unbounded |
| Transfer history | ❌ only `listedAt` / `completedAt` |
| Negotiation rounds | ❌ single roll |
| **Seller's choice of offer** | ❌ forced to take the highest |
| AI demand model | ❌ uniform random buyer |
| AI↔AI market | ❌ completely static |

### 8.5 Frontend

4 panels in `club-management.js:302-532` (hero stats, transfer desk, global market board, own squad listing), 7 bound actions in `bindTransferCentreActions:227-300`. **All prices come from `window.prompt`** (`:181-191`). The per-player Transfer tab (`player-view.js:144-205`) shows status and 6 conditional buttons.

Market visibility gap: `getAllTransfers:71-76` filters `status == LISTED`, so a player who has received an unlisted AI offer is **invisible on the global board**. You cannot scout a rival's unlisted squad.

---

## 9. P1 — Medical, and the missing meta-layer

### 9.1 Medical: the good part and the bad part

**The good part — fatigue-driven injury risk is the best mechanic in the whole codebase.** `engine_v1/RealisticMatchEngine.maybeTriggerInjury:1410-1443`:
```java
if (minute < 8 || minute > 88) return;
int fatigue = fatigueOf(injured);
double chance = 0.00028 + max(0, fatigue - 18) * 0.00008;
if (position == WNG || position == ATT) chance += 0.00008;
```
`pickInjuryRiskPlayer:1477-1489` weights selection by `1 + max(0, fatigue − 10) × 0.25`, so tired players are both more likely to be picked *and* more likely to be injured. A correctly-signed causal chain: play → fatigue → injury risk → injury → rehab → reduced training. Well done. It just needs to survive the §3.5 fatigue fix and get a bench to substitute into (§3.7).

**The bad part:**

- **Severity is 3 hardcoded day buckets** (`rollInjuryDays:1462-1467`): 72% → 1–10 days, 23% → 11–16, 5% → 17–20. No injury *type* (hamstring vs fracture), no recurrence chance, no injury history, no long-term skill loss.
- **Recovery is one button, free, unbounded** (`TeamMedicalService.applyRecovery:39-72`): `fatigue −12`, `injuryDaysRemaining −3`, `form +0.2`. No cooldown, no cost, no medical-staff quality factor.
- **Medical staff cannot be bought** — no `Staff` entity, and the 3 "medical staff" in the UI are hardcoded browser objects.
- **`Player.injured` boolean is redundant and desynchronised.** `isInjured()` is derived from `injuryDaysRemaining > 0` and ignores the flag; `SeasonService.decrementInjuriesByWeek:341-358` never calls `setInjured(false)`.
- The medical queue only contains players with `injured || fatigue >= 18` (`:109-111`), so the page **only ever shows problems**, never squad condition.

### 9.2 Morale is completely inert

`newLogic/engine/MoraleSystem.java`, 34 lines:
```java
public double getConfidenceModifier(long playerId) { return 0.7 + (getMorale(playerId)/100.0)*0.6; }
```

**`getConfidenceModifier()` has zero callers repo-wide.** The only caller of `update()` is the **dead** `newLogic/engine/MatchSimulator:32,127,365`. The only morale event is `+0.5 to whoever happens to be the ball carrier this tick` (`:17-19`). The map is in-memory, per-simulator-instance, reset to 50.0 every match, never persisted.

**Net effect on gameplay: exactly zero.**

### 9.3 `Player.form` is a creation-time constant

`model/Player.java:29`. Four writers, two live, neither performance-based:

| Writer | Value | Live? |
|---|---|---|
| `PlayerFactory.java:159` | `4 + random × 6` | creation only |
| `YouthAcademyService.java:192` | `4.5 + random × 3.2` | creation only |
| `PlayerConditionService.java:23,30,37` | `−0.05`/min, `−2.0` injury, `+0.5` recovery | ❌ **DEAD — 0 call sites** |
| `TeamMedicalService.java:69` | `+0.2` per medical click | ✅ only live writer |

So form drifts **upward monotonically** and never responds to how a player performs. It feeds:
- `MatchRatingCalculator.java:51` — `base = 54 + skill×14 + (form − 6.0)×1.2` → ±4.8 rating
- `PlayerDTO.java:90` — `formBoost = (clamp(form,1,10) − 5.5) × 1.8` → ±8.1 **OVR**
- `PlayerDTO.java:96-97` — DEF/GK `roleContribution`
- `Team.java:69` — `getAvailablePlayers()` filter `form > 3.0`
- `ScheduleInsightService.java:84-91` — squad-form input to the pre-match predictor

Because form is constant, the `formBoost` and `(form − 6)×1.2` terms are **constant offsets** that inflate or deflate every player's rating identically and never respond to a run. That is a real correctness smell in the OVR and rating formulas, not merely a missing feature.

### 9.4 There is no meta-layer at all

Grep for `boardRating|managerRating|happiness|dressingRoom|chemistry|teamSpirit` across `newLogic/` (excluding `sim/`) → **zero matches.** There is:

- No `BoardExpectation` entity
- No manager trust / job security
- No dressing-room split, squad harmony, or player unrest
- No transfer-request refusal
- No press conferences
- No wage-driven happiness
- No season goal setting
- **No retirement.** `PlayerRepository` has no `findByAgeGreaterThan`. Players age forever past 35 with only the soft decay above. The only removal paths are `Team.removePlayer`, `Junior.releaseJunior` and transfer completion.

> Note: the **text-based** mode has all of this in plain POJOs — `footballtextmanager/model/CSClubMood.java:13-17` carries `boardConfidence`, `fanMood`, `mediaPressure`, `financialHealth`. Again: already written, wrong package.

**This is why the game has no stakes.** You cannot be sacked, you cannot overspend, you cannot have a disappointing season matter beyond the table. Football Manager's core loop is *pressure*. This game currently has none.

---

## 10. P2 — Backend stubs and frontend fiction

### 10.1 Backend

| Item | Location | State |
|---|---|---|
| `CompetitionController` | `:5-7` | **Empty class body, 0 routes.** `AGENTS.md` lists it as a real prefix. |
| `StadiumController` | `:5-9` | **Comment only** (`// Prazno, dodamo kasnije metode`). 0 routes. |
| `LeagueService` (9 lines) | `:8` | `// TODO: implementirati promocije/ispadanja`. 0 callers. `SeasonService` does the real work. |
| `TrainingService` (21 lines) | `:13,18` | `// TODO: implementirati trening` / `// dummy logika`. 0 callers. |
| `StadiumService` (7 lines) | — | `// Kasnije logika za stadione`. 0 callers. |
| `DummyDataController` | 19 routes | **100% hardcoded, 0 DB access.** Every path hardcodes team `1`. 2 of its payloads are duplicated as fakes inside `TeamController`. |
| `TeamController` `/coaches`, `/juniors`, `/formations` | `:106,146,155` | Hardcoded, **ignore `teamId`**. Real unused endpoints exist: `/teams/{id}/coaches`, `/teams/{id}/juniors`. |
| `TeamController` `/profile` | `:131` | Hardcodes `founded = 1954`, marked TODO. |
| `POST /auth/register` | — | **Does not exist.** `UserController` has only `/login` and `/me`. **`register.html` is 100% broken.** |
| `POST /admin/registration-requests/{id}/{action}` | — | **Does not exist.** The chat Approve/Reject buttons always `alert()` the error. |
| `ZoxApiController` preview | `:59-60` | `drawProbability = 0.25` hardcoded, rest derived. A fake 1-X-2 model. |
| `LineupController` | `:35,41` | Throws raw `RuntimeException` → global handler turns it into a 500. |
| `PromotionRule` + `Competition.promotionSpots/relegationSpots/hasPlayoff/hasSeeding` | — | **Seeded but never read.** `PromotionRuleRepository` is injected nowhere; the logic is hardcoded in `SeasonService`. |
| `hasSeeding`, `seededTeamsCount`, `reputationWeight` | — | Dead columns. |
| `Crowd`, `Referee` entities | — | **Orphans.** No repository, no service, never read or written. |
| `GameClock.currentPhase` / `SeasonPhase` | — | Enum exists, field **never written**. `APIController:45` hardcodes `phase = "Season in progress"`. |
| `Cup Srbije` | `DatabaseInitializer:611-630` | Created with `teamsPerCompetition = 64`, `hasSeeding = true`, but **never populated and never scheduled** (the fixture loop filters `type == LEAGUE` at `:346`). The **Cup is dead.** |
| `PlayoffWeek` | `SeasonService:239-285` | 2 legs between tier-1 positions 7–8 and tier-2 runners-up. Built, and the promotion path uses it — but `Competition.hasPlayoff` is ignored and the slots are hardcoded. |
| `Tactics` / `Formation` entities | — | 3 of 5 `Tactics` fields unused by the live path (which uses `TeamTacticsProfile` JSON + `TacticRules`). |
| `Team ↔ Stadium` | `Team.java:30`, `Stadium.java:28` | **Circular ownership** (`@OneToOne` both directions). |
| `User → newLogic.Team` | `User.java:35-45` | **No FK.** All 4 `@OneToOne` team links point at *other game modes'* entities. Identity is bridged by **name string matching** (`SimulationController.java:363-372` reads `user.getTifoCTeam().getName()` then `teamRepository.findByName(...)`). Rename a club → you lose your team. |
| `SimMatchService` table update | — | Calls `matchStatisticEngine` for the user's match only. |

### 10.2 Frontend

| Item | Location | State |
|---|---|---|
| **13 dead routes** | `pages.js:437-521` | `results`, `cup`, `international`, `friendlies`, `playerStats`, `teamStats`, `topScorers`, `topAssists`, `coaches`, `events`, `analytics`, `upcoming`, `training` — no menu entry, no action row, console-only. **6 of them crash into a generic "API Error" card** (`pages.js:529`) because they `await response.json()` without checking `response.ok`. |
| `nationalTeam` / `u21Team` | `country-view.js:186-208` | Pure placeholder pages. *"will be added here once backend endpoints are ready."* |
| Staff directory | `staff-directory.js:12-51` | **7 of 9 members hardcoded in the browser.** The 4th comes from an endpoint that 404s for any team ≠ 1. |
| Finances page | `club-management.js:21-66` | 100% client-side fiction (see §3.1). |
| Player profile | `player-view.js:347,349` | *Matches* and *History* tabs permanently empty. |
| Staff page | `staff-directory.js:118` | *History* tab permanently empty. |
| `zox-match-preview.html` | `:22-25` | **4 tab buttons never bound** (the renderer ignores `data-tab` and writes everything at once). Chart.js loaded and unused. Stylesheet linked twice. Reachable only from the dead `analytics` route. |
| `fetchPlayerRatingSummary` | `utils.js:362` | Called with **1** argument at `player-view.js:567` and `league-view.js:425`; the 2nd param `authFetch` is undefined → throws → catch returns all-null → **average rating is permanently `—` on both player pages.** |
| `resolveFixtureStadiumImage` | `fixture-view.js:29-35` | Dead code returning `/images/default-stadium.png`, which **does not exist**. |
| `login.js` `renderLastError` | `:4-34` | Early-returns because **no `#loginStatus` element exists** in any HTML page → the whole "last app error" diagnostics feature in `auth.js:56-89` is invisible. |
| `getCurrentTeamImagePath` | `dashboard.js:47` | Hardcoded club-name string compare `'OFK Omladinac'`. |
| Dashboard stat grid | `dashboard.js:389-403,866-890` | Renders `—` for all 4 values; the catch only logs, so it stays `—` on error. |
| `loadRecentLeagueMatches` | `dashboard.js:428-440,799` | Dead; call and markup both commented out. |

### 10.3 Orphaned files (nothing imports or links them)

`js/pages/views/navigation.js` (55 L) · `js/pages/features/training.js` (13 L) · `js/main.js` (8 L) · `js/realisticDemoLegacy.js` (2,777 L) · `realisticDemoLegacy.html` (1,635 L, and it loads the *wrong* script — `realisticDemo.js`, not `realisticDemoLegacy.js`) · `cleanSheetTifo.html` + `js/cleanSheet.js` (932 L) · `css/style.css` · `css/dasboardBackup.css` (typo in filename) · `js/ui/components.js` `emptyStateHtml` (dead export) · `static/demo/service/ui/index.html` (calls `/api/generate`, which does not exist in Spring — only in the standalone port-8765 launcher) · `demo/service/ui/viewer3d.js` copy (calls the wrong endpoints; only the `proposal/` copy was fixed).

---

## 11. P2 — Architecture, security and data-integrity debt

### 11.1 Security

- **`/api/**` is in `permitAll()`** (`SecurityConfig.java:86`). The entire match engine (`/api/v2/match/start`), the replay API (`/api/sim/replay/**`) and all zox analytics (`/api/zox/**`) are reachable **without a JWT**. The proposal viewer uses plain `fetch` with no `Authorization` header and depends on this.
- `PlayerController.getPaged:48` accepts an arbitrary `sortBy` **field name** into `Sort.by()` — unvalidated, a mass-assignment-style surface.
- `TeamController.create:100` and `PlayerController.create:43` save **raw entities with no validation** and no authorisation check.

### 11.2 Duplication

- `app.js:8-36` **and** `sidebar.js:39-74` both bind click handlers on `#clubSidebar` → **`loadPage` fires twice per sidebar click.**
- `escapeHtml` implemented 3×: `dashboard.js:70`, `pages.js:532`, `utils.js:4`.
- `authFetch` implemented 2×: `auth.js` (full) and `clock.js:1-23` (weaker — no JSON-vs-HTML detection, no 403 branch).
- `loadDashboard()` is called from `demo.js` via `window.loadDashboard` but `app.js:2-3` deliberately does not.
- `pages.js:41-182` re-implements the nav stack that the orphan `views/navigation.js` was written to encapsulate.
- `bindScheduleInteractions` fallback (`pages-renderers.js:201-203`) passes `seasonYear` positionally to `loadLeagueTeam(teamId, teamName, options)`, so the season is silently dropped on the `renderFixturesView` path.
- `training-view.js` — two ~450-line parallel implementations (see §6.6).

### 11.3 Backend duplication and data hazards

- **Two week-advance implementations:** async `AdvanceWeekAsyncService` + synchronous `SimulationController:218`.
- **Three copies of the league-table comparator:** `MatchStatisticEngine:231-306`, `CountryController:109-112`, `SeasonService:745-753`.
- `ensureEntriesForSeasonCompetition:126-134` **deletes and rebuilds all entries** on membership drift → would wipe table data mid-season.
- `SeasonService:59` hardcodes `competitionRepository.findById(1L)` for Superliga.
- Season advance is wrapped in a `TransactionTemplate` (`AdvanceWeekAsyncService:39`) *and* `@Transactional` services — a mid-run failure in league 14 of 16 rolls back the entire week.
- `NewLogicTacticsService:12` constructs `FormationSlotCatalog` with `new` instead of injecting it — manual DI defeating the container.
- **Team name collision ≈ 44%** across 310 draws (birthday problem on a ~109k-name space). Per-league dedup (`usedTeamIdsInLeague`) means the same `Team` can land in two leagues, and `addTeamToLeague:484` (`team.setCompetition(...)`) then **silently reassigns its home competition to the last league that claimed it.**
- `PromotionRuleRepository` never injected; promotion logic hardcoded instead. `buildPlayoffSummary:607-615` hardcodes relegation positions 9–10, duplicating the `safeCount + movementSlots` arithmetic in `applyPromotionRelegationForLeague` — **the summary can disagree with what was actually applied.**

### 11.4 Test coverage: 3.8%

**20 test files, 3,605 LOC against 94,165 LOC of main code.**

12 of 20 cover the proposal engine (`GoalkeeperEngineTest`, `FatigueSystemTest`, `RestartTakerArrivalTest`, `SimReplayFidelityTest`, `DisciplineAndRestartTest`, `TacticsPossessionContextTest`, `SimTeamFactoryMirrorTest`, `RealSquadFactoryTest`, `RealSquadSimulationSmokeTest`, `SimMatchPersistWiringTest`, `ProposalAssistAndReportContractTest`, `ProposalViewerMatchIdentityTest`). 5 cover `demo/service`. 1 is `BaseTest`.

**Zero tests for:**
- `SeasonService` — 758 lines: promotion, relegation, playoff resolution, aging, season rollover, fixture generation
- `TransferService` — 787 lines: **contains the €1 bug and the TL soft-lock**
- `TrainingProgressionService` — 467 lines: **contains the infinite-training bug**
- `YouthAcademyService` — 487 lines
- `TeamMedicalService`, `RoundSimulationAsyncService`, `WeekPreparationAsyncService`, `MatchPersistenceService`
- **Every controller** (21 files, 3,753 lines)
- Both dead engines (`newLogic/engine/`, `engine_v1/`)

**The exploits in §3 exist precisely because nobody wrote the test that would have caught them.** `AGENTS.md:568` claims `NewMatchSimulatorTest` and `NewMatchControllerTest` exist — they do not.

---

## 12. Deliberate pre-deployment state (not defects)

The following are intentional while development is local, and should **not** be treated as bugs:

- **Manual `Advance Week` / `Simulate All Results` / `Watch Your Match` flow.** There are no schedulers, no cron triggers, and no auto-advance. The season only moves when a human clicks. This is the correct choice while you are the only player and are tuning the engine. **At instance deployment, this becomes Sprint 8 infrastructure work** — see `sprintBacklog.md` §S8.6.
- `User` → team binding by name string match rather than a proper FK — acceptable for a single-tenant dev instance; **must** become a real FK or a `managedTeamId` column before multi-user deployment (Sprint 8.7).
- The `admin` DB init/reset buttons in the Community page — appropriate for local testing; gate or remove before public deployment (Sprint 8.8).
- Single hardcoded owner user seeded by `DatabaseInitializer.seedOwnerAfterReset` (`:143-148`).

---

## 13. Documentation is now a liability

**11,856 lines of Markdown — more than the entire training + transfer + medical + academy layer combined.**

- `AGENTS.md` — 92 KB. Documents `/start-realistic-demo → RealisticMatchEngine` as the primary runtime path. **That controller no longer exists.** Lists `CompetitionController` as a real prefix (it has 0 routes). Describes `pages.js` as "5200+ lines" (it is 792). Describes a `newLogic/index.html` viewer (the viewer is `demo/service/ui/proposal/index.html`). Documents the `demo/` Swing architecture as if it were the live product.
- `PROPOSAL_PROGRESS.md` — **110 KB / ~1,800 lines of session log.** Contains at least 3 claims contradicted by the code: `nearestOpponentBeatsHimToIt` is documented as active but is never called; the pass-moment offside block is documented as live but `checkOffside` never returns `confirmed=true`; a `demo/service/ui/index.html` endpoint is documented but `/api/generate` does not exist in Spring.
- `PROPOSAL_CURRENT_STATE.md` — 36 KB, overlaps `PROPOSAL_PROGRESS.md` heavily.
- `THREAT_OVERRIDE_SPEC.md` — 38 KB, mostly superseded by the implemented `ThreatOverrideEngine`.
- `UI_FOOTBALL_MANAGER.md` — claims "✅ U potpunosti implementiran" while listing 6 unimplemented items in the same document.
- `APPLICATION_OVERVIEW.md` — 447 lines, mostly accurate, but describes the cleanSheet text mode as a co-primary flow.

**The documentation is now larger and more confident than the code it describes.** That is actively dangerous: a future agent (or you in 3 months) reading `AGENTS.md` will wire `/start-realistic-demo` and find nothing there.

---

## 14. The one-line summary

**The engine is the product. The management layer is the roadmap.**

You have spent the effort where it is hardest and most visible, and skipped where it is boring but decisive. The single highest-leverage change is not a feature — it is making **`Player.earnings` mean something** and **giving `Team.budget` a reason to change on its own.** Everything FM-shaped hangs off that one loop: wages → transfer budget → squad quality → league position → board trust → whether you keep your job.

Right now that loop has two nodes and no edges.

Fix Sprint 0 (1 week), then Sprint 2 (3 weeks), and the project goes from *"a beautiful match sim with 30 menu pages"* to *"a football manager."*

---

## Appendix — Reference index

| Concern | File:line |
|---|---|
| Budget seeded to 0 | `newLogic/util/teams/TeamFactory.java:56` |
| Only money movement | `newLogic/service/TransferService.java:332-334` |
| €1 exploit | `newLogic/service/TransferService.java:421-424`, `:181-186` |
| TL soft-lock | `newLogic/service/TransferService.java:159,776,778` |
| Offer-as-prose model | `newLogic/model/Transfer.java:34`, `TransferService.java:566,641-660,662-664` |
| AI offer targets humans only | `newLogic/service/TransferService.java:465-469` |
| Seller forced to take highest | `newLogic/service/TransferService.java:229-245,579-597` |
| No training idempotency | `newLogic/service/TrainingProgressionService.java:68-130` |
| `*Exact` discarded by engine | `newLogic/model/Skills.java:118-126,137-144` |
| Fatigue has no decay | `newLogic/service/TeamMedicalService.java:55` only |
| False "passive healing" claim | `static/js/pages/views/medical-view.js:95` |
| Goal mouth 10 m | `newLogic/sim/model/GoalPhysical.java:12-13` |
| On-target prob model | `newLogic/sim/engine/ExecutionQuality.java:115-185` |
| Clearance over-powered | `newLogic/sim/engine/ActionExecutor.java:353-361` |
| Duel proximity-only | `newLogic/sim/engine/DuelService.java:48-83` |
| Duel radii | `newLogic/sim/engine/DuelEngine.java:20-26` |
| No sprint multiplier | `newLogic/sim/engine/MovementEngine.java:45-49` |
| GK model (do not touch) | `newLogic/sim/engine/GoalkeeperEngine.java:58-110,177-204` |
| Penalty awarded, never taken | `newLogic/sim/engine/DuelService.java:101-105`; `ActionLogService.java:50` |
| No substitutions | `newLogic/sim/backlog.md:489-492` |
| THRU/CROSS/CENTER unreachable | `newLogic/sim/engine/decision/CleanDecisionEngine.java:126-127,141` |
| Dead `FootballRules` | `newLogic/sim/engine/MatchOrchestrator.java:48,80` |
| Unreachable offside block | `newLogic/sim/rules/OffsideService.java:121,186,196,199` |
| Replay store in-memory | `newLogic/sim/SimReplayStore.java:17-29` |
| Fatigue → injury chain | `newLogic/engine_v1/RealisticMatchEngine.java:1410-1489` |
| AI-vs-AI dice roll | `newLogic/engine_v1/MatchEngine.java:1578-1602` |
| Hardcoded AI 4-4-2 | `newLogic/engine_v1/MatchEngine.java:1285-1286` |
| Promotion / relegation | `newLogic/service/SeasonService.java:407-566,667-687` |
| Season rollover | `newLogic/service/SeasonService.java:360-405` |
| Fixture generation | `newLogic/service/SeasonService.java:173-237` |
| Aging (no retirement) | `newLogic/service/SeasonService.java:384-388` |
| Pyramid seeding | `newLogic/util/DatabaseInitializer.java:291-348,416-512` |
| Cup created but never populated | `newLogic/util/DatabaseInitializer.java:611-630,346` |
| Youth intake | `newLogic/service/YouthAcademyService.java:34-74,342-354` |
| `juniorCoachSkill` random roll | `newLogic/service/YouthAcademyService.java:369-374` |
| `youthRating` dead hook | `newLogic/model/Country.java:25` |
| Junior reveal | `newLogic/service/YouthAcademyService.java:148,234-256`; `static/js/pages/features/academy.js:186-193` |
| Inert morale | `newLogic/engine/MoraleSystem.java:30` (0 callers) |
| `Player.form` drift | `newLogic/model/Player.java:29`; `TeamMedicalService.java:69` |
| Fake staff | `static/js/pages/features/staff-directory.js:12-51`; `DummyDataController.java:28-35` |
| Fake finances | `static/js/pages/features/club-management.js:21-66`; `DummyDataController.java:57` |
| `/api/**` public | `newLogic/config/SecurityConfig.java:86` |
| Empty controllers | `newLogic/controller/CompetitionController.java:5-7`; `StadiumController.java:5-9` |
| Missing register endpoint | `static/js/register.js:40` |
| Missing admin approval endpoint | `static/js/pages/features/community.js:248` |
| 13 dead routes | `static/js/pages.js:437-521` |
| Duplicate sidebar handlers | `static/js/app.js:8-36`; `sidebar.js:39-74` |
| Broken rating summary | `static/js/pages/views/utils.js:362`; `player-view.js:567`; `league-view.js:425` |
| Existing economy to port | `footballtextmanager/CleanSheetService.java:427-434`; `CSDataInitializer.java:144` |
| Existing mood POJOs | `footballtextmanager/model/CSClubMood.java:13-17` |
