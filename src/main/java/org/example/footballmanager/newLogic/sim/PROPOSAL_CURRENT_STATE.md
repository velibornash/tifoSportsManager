# PROPOSAL_CURRENT_STATE.md — newLogic/sim

**Authoritative description of the current state** of the proposal engine.
This document is **always updated** when `PROPOSAL_PROGRESS.md` changes.

> Last update: 2026-09-25 (session 7.9 — **UI showed the WRONG MATCH: fixed**,
> P6 calibration, restart-taker invariant, UI parity audit).
>
> **CRITICAL, user-reported: the viewer displayed a completely different match
> than the engine had just simulated.** Not a rendering detail — a different
> match (different seed, different passes, different players). Three independent
> defects in the request/response chain:
> 1. the viewer POSTed `/proposal/api/generate` while the controller was mapped
>    at `/api/proposal`, and `/proposal/api/**` was not in the security permit
>    list — the call failed outright. Both prefixes now resolve;
> 2. `writeMatchFile` wrote to `src/main/resources/static/...` while Spring
>    serves static files from the **classpath** (`target/classes/static/...`),
>    so the file the browser downloaded was the match from the last **build**;
> 3. the viewer discarded the generate response and re-fetched that stale file
>    (`_initFromData()` was commented out).
> The replay no longer depends on the file: `GET /proposal/api/latest` serves
> the in-memory last-generated match, the generate response carries the full
> replay, and the viewer plays the payload it just received. The engine logs
> `=== PROPOSAL MATCH GENERATED === seed=… matchId=… …` and the viewer shows
> `seed · id` on the scoreboard, so a mismatch is verifiable instead of silent.
> Locked in by `ProposalViewerMatchIdentityTest` (3 tests).
>
> **Event log auto-scroll fixed:** the "am I near the bottom" guard was evaluated
> *after* appending, so any batch taller than 80 px (seek rebuild, goal burst)
> permanently stopped the log from following the newest event. The decision is
> now made before the DOM grows, and scrolling up pauses the follow while
> scrolling back to the bottom resumes it.
>
> **P6 pass completion: 76% → 84%.** Root cause was NOT `readIntercept` (that was
> already cached once per defender per pass and accounts for only ~15% of failed
> passes). It was over-firing offsides: 22 per match, i.e. 20% of all failed
> passes. Two fixes: (1) `OffsideService.OFFSIDE_WHISTLE_MARGIN = 0.2` cells
> (2.8 m, same tolerance as the reference engine) instead of flagging any receiver
> a centimetre beyond the line; (2) `CleanDecisionEngine.findBestReceiver` is now
> offside-aware — clearly-offside targets are excluded, marginal ones take a heavy
> score penalty. Offsides went 350 → 0 over 20 seeded matches. New
> `ProposalPassFailDiag` breaks failed passes down by terminal event.
> **The documented ~98% target is not football-realistic** (real clubs: 80-86%) and
> is pending owner confirmation; the engine now sits at a realistic 84%.
>
> **P0 HOME/AWAY asymmetry: 6 mirror bugs found and fixed.** The two synthetic
> squads were not mirror images (base skill hashed from the *team name*, so
> mirrored midfield duels resolved 100%/0% and possession was 35/65). Five more
> one-directional mirror breaks: shooting zone (AWAY's was half as deep), the
> defensive-third clearance band, the penalty-area depth, the goal-proximity
> term (a flat +1-cell HOME bonus), and the off-target shot aim (every AWAY miss
> landed *behind* his target goal line). Plus the AWAY column mirror reflected
> over col 3.5 instead of the pitch's only symmetry axis, col 4.0. Result over 50
> seeded matches: shots 3.8/7.3 → **40.5/43.4**, SOT 1.9/3.5 → **25.4/27.5**,
> interceptions 6.8/9.2 → **20.0/19.1**, possession 35% → **51%**, goals
> 0.92/0.08 → **2.68/1.20**. The residual 2.2:1 is a conversion gap, not a
> chance-creation gap. Squad skills are now an explicit, identical profile
> (`SimTeamFactoryMirrorTest`).
>
> **Shot calibration is now the open item:** 84 shots/match at 63% on target
> (real: 25 at 33%) with ~9% of on-target shots scoring (real: ~30%).
>
> **Restart-taker invariant (P-UI).** No action can start while a restart taker is
> still walking to the ball: `BallPhysicsEngine` performs no loose pickup while a
> taker is designated (an opponent could previously steal a restart from 7x
> `ON_BALL_EPS` away), the orchestrator's decision gate has an explicit
> `restartTaker == null` term, and `RestartManager` clears the carrier
> unconditionally. `MatchPhase.SET_PIECE` now returns to `OPEN_PLAY` when the
> restart is consumed. Covered by `RestartTakerArrivalTest` (5 tests).
>
> **UI parity audit vs `/demo/service` (12 features).** `proposal/js/viewer.js` is a
> 1:1 port of the reference, so almost every difference was data/engine-side.
> Fixed: half-time/full-time overlays never fired (recorder hardcoded
> `false, false` — now real state flags), VAR freeze/verdict never fired (event
> type was `"VAR"`, now `VAR_IN_PROGRESS` + typed `VAR_*_CONFIRMED|_OVERTURNED`),
> penalty award invisible (`PENALTY_AWARDED` was in no filter), 3D page orphaned
> (and it was calling the LEGACY endpoints — wrong engine), kickoff leaked AWAY
> players over the half-way line (clamp now keeps a half-cell buffer). Six
> remaining differences are documented deliberate divergences in `backlog.md`
> (`UI-PARITY-05..11`).
>
> **Viewer:** clicking a player on the pitch shows a stats card (name, role,
> rating, goals, assists, shots, passes, duels, minutes) and rings the selection.
>
> **Possession chains:** `ProposalStatsCollector.PossessionChain` is exported as
> `stats.possessionChains` (chain id, team, pass count). Tactical targets are
> now possession-aware (`WE_HAVE_BALL` / `OPPONENT_HAS_BALL` rules).
>
> Previous goal shot-guard, deferred offside, short-pass bias, deterministic seeds
> and distance-based fatigue remain active.

---

## 1. GOAL

A runnable, deterministic, tactically-realistic 11v11 football match sim with:

- Separated, single-responsibility engines (decision, action, physics,
  movement, tactical intent, restarts, duels, rules). The Orchestrator only
  **calls** the engines in order — it holds no logic itself.
- Tactical positions from the DB (`team_tactics_profile`) or fallback JSON.
  A player without the ball follows their tactical target every tick.
- A live ball: pass/shot → chase → possession; shots, goals, saves, duels;
  restarts (corner, goal kick, throw-in, penalty, free kick); VAR (skeleton).
- "Ball at feet": the carrier always has the ball at their feet at the
  moment of decision.
- Configurable sides: HOME attacks rows 1→7; side swap in the second half
  must be possible without breaking the system.
- Verification via the `MatchSimulationLauncher` and `ProposalBatchDiag`.

---

## 2. PITCH — authoritative values (source of truth)

All measurements in **cells** unless stated in meters.
Pitch grid: **7 rows × 6 columns**, each cell **14 m × 10 m**.
"Cell length" means **14 meters**.

**Grid coordinates (row | col):**

| Zone | Row range | Column range |
|---|---|---|
| row 0 (OOB behind HOME goal) | 0.00–0.99 | — |
| row 1 | 1.00–1.99 | — |
| row 2 | 2.00–2.99 | — |
| row 3 | 3.00–3.99 | — |
| row 4 | 4.00–4.99 | — |
| row 5 | 5.00–5.99 | — |
| row 6 | 6.00–6.99 | — |
| row 7 | 7.00–8.00 | — |
| row 8 (OOB behind AWAY goal) | 8.01–9.00 | — |
| column 0 (OOB left of touchline) | — | 0.00–0.99 |
| column 1 | — | 1.00–1.99 |
| column 2 | — | 2.00–2.99 |
| column 3 | — | 3.00–3.99 |
| column 4 | — | 4.00–4.99 |
| column 5 | — | 5.00–5.99 |
| column 6 | — | 6.00–7.00 |
| column 7 (OOB right of touchline) | — | 7.01–8.00 |

**OOB = out of bounds** (ball off the pitch).

**Lines and goals:**
- Left touchline: **col 1.00**; right touchline: **col 7.00**.
- HOME goal line: **row 1.00**; goal from **1.00|3.50 to 1.00|4.50**
  (width 1.0, center col **4.00**).
- AWAY goal line: **row 8.00**; goal from **8.00|3.50 to 8.00|4.50**.
- Pitch center: **4.50|4.00**.
- row 0 = visual OOB zone behind HOME goal; row 8 = behind AWAY goal.

**Corners:** HOME left **1.0|1.0**, HOME right **1.0|7.0**,
AWAY left **8.0|1.0**, AWAY right **8.0|7.0**.

**Penalties:** HOME **1.79|4.00**, AWAY **7.21|4.00**.

**Penalty boxes:**
- HOME: **1.00|2.40, 1.00|5.60, 2.14|2.40, 2.14|5.60**.
- AWAY: **8.00|2.40, 8.00|5.60, 6.86|2.40, 6.86|5.60**.

**Attack direction:** HOME attacks left→right (from row 1 toward row 7).
Side swap in the second half: MUST remain possible without breaking the system.

> **NOTE — engine mismatch:** the engine still uses goal center at **col 3.5**
> (not 4.0). Engine box/penalty constants differ from the authoritative values
> above. Alignment is in the backlog.

---

## 3. PLAYER DATA

### 3.1 Eight base skills (1–20)

| Name | Abbr | Role in physics / decision |
|---|---|---|
| Stamina | sta | Fatigue drain (↑stamina = slower fatigue) |
| Keeper | kep | GK: save/catch/punch chance; DuelEngine aerial |
| Pace | pac | Player speed = `pace/20 * 0.75` cells/tick (no chase sprint) |
| Defending | def | Tackle/block/interception; DuelEngine DRIBBLE/TACKLE power |
| Technique | tec | First touch, ball control, execution quality (pass/shot deviation) |
| Playmaking | pm | Option vision (VisionFilter), decision quality (OptionSelector) — **not** passing |
| Passing | pas | Pass speed (max ball speed for PASS/CROSS/CLEAR), accuracy |
| Striker | str | Shot: xG, ball speed, aim (far post), goal chance |

> **Code fields:** the `PlayerSkills` record has fields in order
> `(pace, stamina, keeper, technique, playmaking, passing, striker, defender)`.
> The documented order above is authoritative for the specification.

### 3.2 Non-standard attributes

| Attribute | Type / range | Role |
|---|---|---|
| Fatigue | double 0.0–1.0 | MovementEngine reduces speed up to −30% |
| Injury | boolean | `isUnavailable()` = true |
| Form | double 0.5–1.2 (default 1.0) | Multiplier on all outputs; **documented only**, not implemented |

---

## 4. BALL PHYSICS — authoritative specification

### 4.1 Basic model

- Ball is **pure physics**: position + velocity (velX, velY in cells/tick) +
  rotation/spin (0..1) + Airborne flag.
- **NOT** a carrier, NOT a target, NOT "who called it" — all that lives in `MatchState`.
- Launch: `launch(aim, speedCellsPerTick, airborne, spin)` —
  direction = `aim - origin`, speed = given, along the direction.

### 4.2 Speeds (match time: 1 tick = 1.5 s, 40 TPM)

| | m/s | cells/tick |
|---|---:|---:|
| Player pace 20 | 7.0 | 0.75 |
| Ball MIN (weakest pass) | 7.0 | **0.75** |
| Ball MAX (strongest shot/pass) | 14.0 | **1.50** |

Speed is **not clamped** to the target; the ball flies toward the target as
far as it gets, then decelerates and stops (or becomes LOOSE). Minimum launch
speed: **0.75**.

### 4.3 Deceleration

| Type | decel (cells/tick²) | note |
|---|---:|---|
| Ground (ground pass/clearance) | **0.35** | ~2.2 m/s² friction |
| Air (air shot/cross) | **0.15** | ~0.9 m/s² air resistance |
| Stop | when speed ≤ **0.02** → 0, `airborne=false` |

**Landing:** an air ball becomes a ground ball as soon as it drops below
`LANDING_SPEED = 0.30` (continues with ground decel).

### 4.4 Spin

- `spin ∈ [0, 1]` → lateral acceleration every tick: `rotation(speed, spin * 0.05)`.
- Slight trajectory curve (for free kick, corner, cross).

### 4.5 Collisions (every tick)

The ball checks collisions in this order (earlier = higher priority):

1. **Posts/crossbar (GoalPhysical):** left/right post (radius 0.03 cells) on
   the goal line. Collision → bounce (reflect along normal) + `BOUNCE_DAMP = 0.5`.
   Ball becomes ground ball.
2. **Goal plane:** intersection of segment `prev→new` with goal line (HOME 1.0,
   AWAY 8.0). If intersection is inside the mouth (3.50–4.50, excluding posts)
   → **GOAL**. Attributed to `lastTouchTeam`.
3. **Players** (first contact along the segment wins):
   - pendingReceiver (same team) within `RECEIVE_R = 0.35` → **RECEIVE**.
   - Opponent within `INTERCEPT_R = 0.30`:
     - if ball **slow** (< `FAST_CONTACT = 1.00` cells/tick) → **INTERCEPT**.
     - if ball **fast** (≥ 1.00) → **BLOCK** (bounce + damp, NO possession).
   - Anyone within `DEFLECT_R = 0.18` → **DEFLECT** (slight bounce + damp).
4. **OOB zone** (row ≤ 0.99 / ≥ 8.01 / col ≤ 0.99 / ≥ 7.01):
   - First entry → `oobPending = restartType`, `oobHoldTicks = 4`.
   - **The ball FREEZES at the crossing point** (`ball.stop()` on enter) — it
     must never keep sliding along the OOB zone, and the restart spot is
     computed from the TRUE exit (user rigid-ball rule 2026-09-23).
   - Step 0 central guard: an OOB ball is ALWAYS dead — possession, pickups and
     carrier are cleared before every other check (a RECEIVE that lands just
     outside the touchline can never become a held ball).
   - When `ticks == 0` → returns `dueRestart`.
   - **NEVER instant teleport** to a restart — 4-tick visibility.

### 4.6 Loose ball pickup

- Ball stopped (speed ≤ 0.02) with no carrier → every tick check the nearest
  player within `PICKUP_R = 0.35` → they become carrier.

### 4.7 GoalPhysical

```java
class GoalPhysical {
    double goalLineRow;       // 1.0 (HOME) or 8.0 (AWAY)
    double mouthLeftCol = 3.5;
    double mouthRightCol = 4.5;
    Position leftPost;   // (goalLineRow, 3.5)
    Position rightPost;  // (goalLineRow, 4.5)
    double postRadius = 0.03;
    double heightMeters = 2.44;
}
```
In 2D physics only the posts are checked. Crossbar = UI/rendering only.

### 4.8 What the engine MUST NOT do

- ❌ Clamp the ball target (row/col).
- ❌ Know "who called it" (ActionExecutor logs, BallPhysicsEngine does NOT).
- ❌ Ball target position ≠ where the ball stops.
- ❌ Goal detection by radius around center — only goal-line intersection in the mouth.

> **User overrides (2026-09-23):**
> - `MovementEngine` **clamps player positions** to the pitch (rows 1.0–8.0,
>   cols 1.0–7.0) — no player may ever stand off the field (fixes throw-in churn:
>   off-pitch MR attracted touchline passes that went out again). This overrides
>   the historical "no player clamp" rule.
> - An **OOB ball is always dead** — never possessed, never picked up (central
>   guard at `stepBall` step 0).
> - The **striker roots in place** for 1 tick after PASS/SHOT/CLEAR
>   (`Player.strikeHoldTicks` consumed by `MovementEngine`) — the ball visibly
>   leaves his foot before he moves.

---

## 5. CURRENT ARCHITECTURE

### 5.1 Package

`org.example.footballmanager.newLogic.sim`
- Imports nothing outside its own tree (clean, standalone).
- Apps are configured to scan `org.example.footballmanager` (whole base package), so the sim's
  `@Component`s (SimReplayStore, controllers) are picked up without an explicit per-package scan line.

### 5.2 Models

| Class | Description |
|---|---|
| `MatchState` | Central state: ball, carrier, clock, stats, OOB pending, VAR stub |
| `Player` | Position, skills, fatigue, injury, form, role, team |
| `Ball` | Pure physics: position, velX/velY, spin, airborne, launchSpeed |
| `Position` | 2D coordinate (row, col) |
| `PlayerSkills` | Record: pace, stamina, keeper, technique, playmaking, passing, striker, defender |
| `ActionType` | Enum: PASS, SHOT, DRIBBLE, CLEAR, CROSS, CENTER, THRU, HOLD |
| `DecisionResult` / `DecisionOption` / `DecisionContext` | Decision engine output |
| `PitchEnvironment` + `GoalPhysical` | Authoritative pitch geometry |

### 5.3 Engines

| Engine | Responsibility | Status |
|---|---|---|
| `CleanDecisionEngine` | Scoring + option selection (PASS/SHOT/DRIBBLE/CLEAR/CROSS/CENTER/THRU) | ✅ Works |
| `ActionExecutor` | Executes decision: PASS/SHOT/CLEAR = `ballEngine.launch(...)` | ✅ Works |
| `BallPhysicsEngine` | Pure physics: launch, deceleration, collisions, OOB, loose pickup | ✅ Works (calibrated) |
| `MovementEngine` | Moves all players toward targets; loose-ball chase; separation | ✅ Works |
| `TacticalIntentEngine` | Tactical targets from rules; refresh every tick | ✅ Works |
| `DuelEngine` | Duel detection + resolution (DRIBBLE, TACKLE, AERIAL) | ✅ Works (rare) |
| `RestartManager` | Kickoff, corner, goal kick, throw-in, penalty, free kick | ✅ Works |
| `FootballRules` | Offside check (minimal) | ✅ Works (offside only) |
| `ExecutionQuality` | Pass/shot deviation; ball speed mapping 1→20 → 7→14 m/s | ✅ Works |

### 5.4 Tactical system

| Class | Description |
|---|---|
| `TacticsRuleDTO` / `TacticsSlotDTO` | DTOs from `demo/service/tactics` |
| `FormationSlotCatalog` | 4-4-2, 4-3-3, 4-2-3-1, 4-1-4-1, 3-5-2, 5-3-2, 3-4-3, 4-5-1, 5-4-1 |
| `TacticalPerspectiveTransformer` | HOME direct; AWAY mirror `Position(9-row, 7-col)` |
| `TacticsRules` | 3-level loading: DB → JSON fallback → catalog |

**Transformation:** editor cell is 0-based; `parseCell` for `CELL_r_c`
returns `(r + 1.5, c + 1.5)` (cell center).

### 5.5 Recording

| Class | Description |
|---|---|---|
| `MatchRecorder` | Event + snapshot recording |
| `MatchEvent` / `MatchSnapshot` / `MatchRecording` | JSON-friendly models |
| `PlayerSnapshot` | Player position at snapshot time |
| `ProposalMatchOutcome` / `ProposalMatchOutcomeBuilder` (result/) | Post-match report payload (JSON-ready) + builder from `MatchOrchestrator` |

### 5.6 Orchestrator — per-tick order

```
1.  clock tick
2.  unlock (action finished?)
3.  VAR timer
4.  ballEngine.stepBall       → BallStepResult
5.  handle result              → RECEIVE / INTERCEPT / BLOCK / GOAL / RESTART
6.  decision + execution       → only if carrier exists and ball NOT in flight (checkOffside flag for PASS)
7.  tactical intent            → refreshTargets
8.  movement                   → moveAllTowardTargets
9.  restart taker claim        → taker walks to the ball
10. rules                      → offside (resolvePendingVAROffside only)
11. duels                      → DuelEngine
12. VAR timer update
```

**Offside flag → whistle flow (session 7.2):** `checkOffside` (step 6, pass-moment)
marks the intended receiver (`offsideFlaggedReceiver`) for CLEAR/MARG bands — the
pass is NOT blocked and nothing is teleported. The actual whistle fires in
`BallResultHandler` (step 5) on the RECEIVE result when the flagged player
touches the ball; it stops at the physically-arrived spot and
`RestartManager.handleOffsideFreeKick` produces an instant IFK. If anyone else
reaches the ball first the flag is cleared (no offense).

**Log tags:** `[mm:ss|DEC]` decision, `[mm:ss|ORC]` orchestrator,
`[mm:ss|BAL]` ball physics, `[mm:ss|DUL]` duel, `[mm:ss|RST]` restart,
`[mm:ss|TAC]` tactical, `[mm:ss|LCH]` loose ball chase.

### 5.7 UI display

- `ProposalViewerLauncher` — port **8766** (separate from demo/service 8765).
- `POST /proposal/api/generate` — simulates a match (3600 ticks), writes
  `match.json` (58MB, ~7200 events + 3600 snapshots).
- `/proposal/match.json` — serves the JSON.
- `viewer.js` — 1:1 port of the demo/service viewer: LED scoreboard, canvas pitch,
  timeline, controls (Play/Pause/Seek/Speed).
- `index.html` — viewer page; `viewer.js?v=3` + `pitch.css?v=3` (cache-buster).
- `Cache-Control: no-store` on all files.

### 5.8 Statistics

**Current state (2026-09-24 — out-of-match "izveštaj" shape is ready):**
`ProposalStatsCollector` gathers per-team + per-player stats live during play,
and `result/ProposalMatchOutcomeBuilder` turns a finished orchestrator into a
**`ProposalMatchOutcome`** (`result/ProposalMatchOutcome.java`) — the JSON-ready
"match report" payload that maps 1:1 onto the newLogic match/report model.

The outcome carries: score, possession, expected goals (derived per-shot from
distance-to-goal, same xG bands as newLogic), formations (derived from role
counts), full `TeamOutcome` / `PlayerOutcome` / `EventEntry` (typed timeline)
lists, and player-of-the-match. Fouls / cards are structural **0** until
`DisciplineService` is wired into the orchestrator.

| Category | Current | Needed |
|---|---|---|
| Shots | `shots`, `shotsOnTarget` per team+player | blocked, saved, missed (per player inherited from demo counters is partial) |
| Goals | `homeGoals`, `awayGoals` + per player | open play / penalty / FK split |
| Offside | derived from recorder `OFFSIDE` events (per team) | per player |
| VAR | none | total, confirmed, overturned, per type |
| Passes | `passesAttempted/passesCompleted` per team+player | thru / center / cross split |
| Dribbles | per team+player | successful dribble split vs lost |
| Interceptions | per team+player | — |
| Restarts | corners, throw-ins, goal kicks per team | free kicks, penalties per team |
| Cards | structural 0 (DisciplineService not wired) | yellow, red, double-yellow, per player |
| Rating | per player + team avg (`calculateRating`) | — |
| Expected goals | derived per-shot on outcome build | — |
| Match outcome JSON | `MatchOrchestrator.buildOutcome()` / launcher print | persist via future newLogic adapter |

> **Viewer note (2026-09-23):** stats are still computed and exported
> (`stats.teams`/`stats.players`) but the sidebar stats panel was REMOVED — the
> vacated space now holds the full events log which runs from match start.

### 5.9 Diagnostics

`ProposalBatchDiag <matches> <baseSeed>` — runs N deterministically seeded
matches and aggregates goals/shots/SOT/passes/interceptions/deflections/fouls/
cards, the H/A split (goals, shots, SOT, interceptions, possession) and the
0-0 count. The H/A split line is the mirror-bug detector.

`ProposalPassFailDiag <matches> <baseSeed>` — walks each match's event stream
and buckets every failed pass by the terminal event that killed it
(OFFSIDE / DEFLECT / LOOSE_PICKUP / DUEL / INTERCEPT / OOB_ENTER / …). This is
how the P6 root cause was found: the failure profile pointed at offsides, not
at interception.

`ProposalPhysicsDiagnostic` — per-tick physics trace.

---

## 6. MEASURED VALUES (200 matches, seeds 42-241, `ProposalSeasonDiag 200 42`)

Full per-team report: **`PROPOSAL_SEASON_REPORT.md`**. Headline:

| Metric | Proposal | Real football | Status |
|---|---|---|---|
| Goals/match | 5.2 (H 2.4 / A 2.8) | 2.7 | ✅ (owner target: up to 7) |
| Shots/match | **28.2** (H 13.4 / A 14.8) | 25 | ✅ target met |
| Shots on target | 12.1 (43% of shots) | 8-9 (33%) | ⚠ 1.4x |
| Saves | 9.4 | 3-4 | ⚠ follows from the above |
| On-target → goal | 44% | ~30% | ⚠ close |
| Pass completion | 77.9% | 80-86% | ⚠ slightly low |
| Through balls / crosses / centres | 11.7 / 19.1 / 40.5 | 5-10 / 15-25 / 25-35 | ✅ |
| Blocks | 6.6 | 2-4 | ✅ was structurally 0 |
| Offsides | 3.6 | 2-4 | ✅ |
| Fouls | 27.8 | 22 | ⚠ high |
| Yellow cards | 4.7 | 4-5 | ✅ was 7.5 |
| Red cards | 1.0 | 0.2 | ⚠ 5x (was 2.7) |
| Penalties | 0.2 | 0.27 | ✅ was 6.9 |
| VAR reviews / overturned | 2.7 / 0.6 | 1-3 / 0.2-0.5 | ✅ overturn was impossible |
| H/A goals | 2.4 / 2.8 | ≈1/1 | ✅ was 0.92 / 0.08 |
| H/A shots | 13.4 / 14.8 | ≈1/1 | ✅ was 3.8 / 7.3 |
| H/A pass volume | 299 / 280 | ≈equal | ✅ was 288 / 185 |
| Possession | 49.5 / 50.5 | 50 / 50 | ✅ was 56 / 44 |
| Results (200) | 67 W / 94 W / 39 D | ≈even | ✅ |

**Goalkeeper: a real model, not a fixed radius.** The keeper was a static
tactical anchor who saved anything inside a hard `GK_SAVE_R`, so placement, shot
power and skill could not be expressed at all. `engine/GoalkeeperEngine.java`
now positions him on the bisector between the ball and the centre of his goal,
advances off his line as the ball approaches, rushes out for a one-on-one, and
saves with a GRADED probability: his reach grows with keeper skill and shrinks
with ball speed (a 14 m/s shot leaves him under a tick to react), and the chance
of holding it falls off with the cube of the distance from his body. On-target
shots also spread across the whole mouth instead of clustering within ±0.3 of
the centre, which is exactly where a keeper stands. He moves with a
`GOALKEEPER_MOVEMENT_FACTOR` of 1.9 because a keeper's pace describes lateral
shuffling, not outfield speed. On-target → goal: 61% → 44% (real ~30%).
`GoalkeeperEngineTest` (11 tests) covers bisector positioning, the advance
ramp, the one-on-one rush, never advancing past the ball, exact HOME/AWAY
mirroring, and the graded save (skill widens reach, speed narrows it, a ball
past the reach always beats him).

**Three zeros were dead code, not tuning.** Blocks: `ev = "BLOCK"` was never
assigned anywhere, so the block result, event, stat and player column could
never fire. VAR overturns: `VARService.checkGoal` was never called, so a goal
could never be overturned, and the other gates (4-10% review × 8-25% overturn)
made them effectively never either. Through balls / centres / crosses:
`ActionType` had no such values, so all three were ordinary `PASS` actions with
no way to express "behind the defence" or "lofted into the box".

**P0 — HOME/AWAY asymmetry: 6 mirror bugs found and fixed.** The squads were not
mirror images (skills hashed from the *team name*, so mirrored midfield duels
resolved 100%/0%); the shooting zone, the defensive third, the penalty area, the
goal-proximity term and the off-target shot aim were each mirrored about the
wrong axis; and the AWAY column mirror reflected over col 3.5 instead of the
pitch's only symmetry axis, col 4.0. Every shot/SOT/interception/possession
metric is now symmetric. The residual 2.2:1 goal ratio comes from conversion
(HOME 9.2% of shots vs AWAY 3.7% at a near-identical save rate), not from
chance creation. See `backlog.md` §P0 for each defect and what is still open.

**Shot volume calibration: OPEN and separate.** 84 shots/match at 63% on target
(real: 25 at 33%) with only ~9% of on-target shots scoring (real: ~30%) means
the engine takes far too many shots and marks too many of them on target. This
is a distinct problem from the asymmetry and needs its own calibration pass.

**Halftime bug (eradicated in session 6.9):** `MatchClockService.tick()` returns
`true` when `matchTicks == 1800` but `simulate()` never called `resume()`.
Now fixed: after 1800 ticks → `resume()` + `handleKickoff("AWAY")`. The
`halfTime`/`matchFinished` replay flags — which the viewer overlays key off —
were separately hardcoded `false` in the recorder and are now real state.

**P6 — pass completion gap: CLOSED at a realistic level.** The previously
documented diagnosis ("`readIntercept` re-rolled every flight tick, cumulative
probability too high") was wrong: the read is already decided once per defender
per pass, and interception is only ~15% of failed passes. The real cause was
over-firing offsides (22/match) which the decision engine caused by scoring
purely-offside receivers as the best option. See `backlog.md` §P6 and
`ProposalPassFailDiag`.

**P0 — HOME/AWAY asymmetry: OPEN.** AWAY creates twice the shots and more shots
on target, but scores 0.08/match. Diagnosis so far: AWAY penetrates the box ~5x
more often, so the GK faces point-blank attempts and saves 84% of them (HOME 47%).
The goal-crossing and save code is symmetric, so the asymmetry is upstream in the
movement/threat/decision row comparisons. Same class of bug as the demo/service
"AWAY-goal-line mirror fix" (2026-09-11).

**Halftime bug (eradicated in session 6.9):** `MatchClockService.tick()` returns
`true` when `matchTicks == 1800` but `simulate()` never called `resume()`.
Now fixed: after 1800 ticks → `resume()` + `handleKickoff("AWAY")`. The
`halfTime`/`matchFinished` replay flags — which the viewer overlays key off —
were separately hardcoded `false` in the recorder and are now real state.

---

## 7. WHAT DOES NOT EXIST (missing engines / layers)

| Layer | Description | Status |
|---|---|---|
| **Override system** | Formal layer between `decide()` and `execute()` | ❌ NOT NEEDED — user keeps hard rules, refactor to boosts later |
| **VAR engine** | Review of decisions (offside, goal, penalty, red) | ✅ DONE (P7#2) — `rules/VARService.java` real body (174 l., 9 `@Override`, 5 gates), compile rc=0 |
| **Discipline** | Fouls + cards | ✅ DONE (P7#3) — `rules/DisciplineService.java` real `evaluateFoul()` body (139 l., 1 `@Override`); 4 honest TODOs for card/penalty follow-up |
| **Offside full** | Continuous tracking + per-pass check | ✅ DONE (P7#1) — `rules/OffsideService.java` real body (167 l., 3 `@Override`), 3-consecutive-offside retreat rule |
| **Threat override** | Defensive pressure on carrier (3 types) | ✅ DONE (P7#4) — `engine/ThreatOverrideEngine.java` full TYPE A/B/C implementation; compiles rc=0 |
| **Fatigue** | Player fatigue | ✅ PARTIAL (2026-09-25) — `engine/FatigueSystem.java`: stamina drains from actual movement distance and `MovementEngine` applies up to 30% speed loss. Auto-substitution + injury risk deferred: no bench/roster/substitution contract exists |
| **Transition** | Possession-change logic | ✅ DONE (2026-09-25) — targets refresh in the same tick as the possession change; `TacticsRules` loads both `WE_HAVE_BALL` and `OPPONENT_HAS_BALL`; possession chains tracked with chain id + pass count |
| **Restart contract** | Taker must reach the ball before any action | ✅ DONE (2026-09-25) — no loose pickup while a taker is designated, explicit `restartTaker == null` decision gate, unconditional carrier clear, `SET_PIECE → OPEN_PLAY` on consumption. `RestartTakerArrivalTest` |
| **Stats layer** | Per-team + per-player stats + possession chains | ✅ DONE (P1) — `result/ProposalStatsCollector.java`, exported as `stats.teams`/`stats.players` with `avgPossessionTicks`/`longestPossessionTicks`, rendered in viewer sidebar (chain avg row) |
| **Orchestrator slimming** | ~340 lines: logging, recording, duel detection | 🟢 P2#1-3 `[x]` — `handleBallPhysicsResult()` 162-line switch → `BallResultHandler` (engine/BallResultHandler.java); orchestrator 324 l. / helper 224 l., kompajl PASS. Ostalo: duels→`DuelService`, log→`ActionLogService`, slim loop |
| **Match outcome (report shape)** | Post-match report payload for future newLogic adapter + statsJson | ✅ DONE (2026-09-24) — `result/ProposalMatchOutcome.java` + `ProposalMatchOutcomeBuilder.java`; xG/offsides/formations/MOTM derived on build; `MatchOrchestrator.buildOutcome()` + launcher JSON print. Fallback: fouls/cards 0 (DisciplineService) |
| **newLogic connect** | Canonical per-team stats writer/reader so proposal output slots into the report | ✅ DONE (2026-09-24) — `Match.statsJson` + `newLogic/service/MatchTeamStatsService.java` + `newLogic/model/MatchTeamStats.java`; `ZoxApiController.computeTeamStats` reads statsJson first (legacy fallback unchanged) |

**Recent correctness fixes (2026-09-14, user-reported):**
- **Offside whistle at reception (7.2)**: no ball teleport/acceleration on an
  offside — pass flies normally (ball physics untouched), ball stops at the
  received spot, IFK is instant.
- **Possession glue**: the ball is now glued to the carrier AFTER movement
  (`MatchOrchestrator` step 8b) — a dribbling carrier never leaves the ball
  behind, and shot/pass actions always start from the carrier's feet
  (verified: 445/445 IN_POSSESSION snapshots, max gap 0.0000 cells).
- **Restart side correctness**: `RestartManager.getRestartPosition(type, oobExit)`
  now receives the OOB exit position — throw-ins land on the correct touchline
  (col 1.0 left / 7.0 right) at the exit row, corners on the correct flag.
  Taker teleport fast-path (>4.0 cells → snap to 0.6 behind the ball) prevents
  the "taker never arrives" freeze.

---

## 8. RUNNING

```bash
# Compile
mvn -q compile

# Launcher (12 min match = 480 ticks)
mvn -q exec:java -Dexec.mainClass=org.example.footballmanager.newLogic.sim.MatchSimulationLauncher

# Full match / longer run to check freezes (3600 ticks = 90 min)
mvn -q exec:java -Dexec.mainClass=org.example.footballmanager.newLogic.sim.MatchSimulationLauncher -Dexec.args=1440

# Batch diagnostics (10+ matches)
mvn -q exec:java -Dexec.mainClass=org.example.footballmanager.newLogic.sim.ProposalBatchDiag

# UI viewer
# 1. Start ProposalViewerLauncher (port 8766)
# 2. Open /demo/service/ui/proposal/index.html
# 3. Click Generate or load match.json
```

**DB (if available):** `localhost:5432/sokker_db`, user `postgres`.
Loads `team_tactics_profile` for team 1 (4-4-2, version 5, 506 rules).
If DB is not available: fallback `/tactics_fallback.json`.

**Component scan:** the sim lives under `org.example.footballmanager` (scanned by default in
`@SpringBootApplication`); no explicit per-package scan line is needed for `newLogic.sim`.

---

## 9. BACKLOG (priorities)

Full prioritized task list is in `backlog.md`. Summary by priority:

| P | Focus |
|---|---|
| ~~**P1**~~ | ~~Stats layer~~ — ✅ **DONE 2026-09-14** (event enrichment + collector + export + viewer sidebar) |
| **P2** | Orchestrator slimming (extract BallResultHandler / DuelService / ActionLogService) |
| **P3** | Ball physics calibration (DEFLECT_R, readIntercept, goal plane) |
| **P4** | Player movement (obstacle go-around, per-tick refresh, carrier speed under pressure) |
| **P5** | Offside full implementation (tracking, per-pass check, retreat) |
| **P6** | Pass completion calibration (67% → ~98%) — deferred |
| **P7** | Rules bodies + UI overlays | ✅ P7#1-5 FULLY DONE (OffsideService, VARService, DisciplineService, ThreatOverrideEngine TYPE A/B/C, and Proposal Viewer UI overlays) |
| **P8** | Fatigue, transition, hard rules→boost, app log, rating, viewer enhancements |

---

## 10. RULES OF ENGAGEMENT

1. **Before any change** do `git commit` of the current state.
2. **Update `PROPOSAL_CURRENT_STATE.md`** whenever you change `PROPOSAL_PROGRESS.md`.
3. The engine package imports nothing outside the `proposal/` tree.
4. Authoritative values are in §2 and §4 — all deviations are bugs.
5. UI behaves IDENTICALLY to the `/demo/service` viewer — Generate only
   generates, Play loads + auto-starts, events appear 1-by-1.