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

