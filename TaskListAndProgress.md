# TaskListAndProgress.md — consolidated task list and progress log

> **📋 FRESH SESSION BOOTSTRAP — read this first, then `AGENTS.md`, then `TECHNICAL_OVERVIEW.md`.**
> Last updated: 2026-10-30. Last commit: `dc09deb5` (short pass speed reduction + nudge reduction to prevent OOB). All work is on `origin/main`.
> Archived documents (historical, not current): `archive/kanban.md`, `archive/kanbanProgress.md`, `archive/MVPtaskList.md`, `archive/MVPprogress.md`, `archive/MATCH_ENGINE_ANALYSIS.md`, `archive/MATCH_ENGINE_ANALYSIS_REPORT.md`.

---

## Part 1 — Rules and session bootstrap

### Language and communication
- **Answer in English. Always.** The owner writes in Serbian; every reply, commit, message and code comment is in English.
- **Behave as a professional senior fullstack developer.** Clean, simple code, no overengineering. If in doubt, ask rather than go in circles.
- **Ask before anything unclear.** Asking costs a message; guessing costs the owner his application.

### The rule that outranks everything (owner, 2026-10-20, verbatim)
> Do what the owner asked for, and only that. Stay inside the frame. Every deviation needs his explicit
> permission, asked for in advance. Never touch Oracle without asking. Never change the visual identity.

- Do what the owner asked for, and only that. Not a bigger version of it, not a smaller one.
- Stay inside the frame the owner set. Every deviation needs his explicit permission, asked for in advance.
- Never touch Oracle — not the app, not the container, not the dashboard, not a file, not a restart.
- Never change the application's visual identity on your own (navbar, dashboard layout, type).
- Ask before anything unclear.

### Build & run (fresh session)
```bash
export JAVA_HOME=/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:$PATH"
```
- Build: `mvn clean package -DskipTests`
- Run: **`./run-app.sh`** — never `mvn spring-boot:run` directly, never `java -jar` as a substitute
- Browser: **`agent-browser`** (`export PATH="$HOME/.npm-global/bin:$PATH"` first — it is not on the default PATH)
- One class: `mvn test -Dtest=RatingEngineTest`
- One method: `mvn test -Dtest=RatingEngineTest#theSeedIsTheOwnersScale`
- Database: PostgreSQL `sokker_db`, user `postgres`, password `stojke`
- Login: `velibor@example.com` / `A12345!`

### Critical rules
- **Never rebuild or redeploy Oracle.** The Oracle deployment is the owner's. Local work only: build and test locally, commit, push to `origin/main`, update `.md` files, and say what is ready.
- **Never build while the app is running.** Any build rewrites `target/classes` and the JVM caches the failure. Stop the app, build, start the app.
- **Start the app with `./run-app.sh`**, and open the browser with `agent-browser`.
- **A job is not done until it has been seen to change data.** Observed in the database afterwards, not logged as done.
- **A test that cannot fail proves less than no test.** When you write a guard, break the code deliberately and watch it fail.
- **A baseline is only comparable to a baseline measured the same way.**
- **Commit to `main` after each task**, then update `.md` files. Stage only the files that task intended to change.
- **Two lines of work share this working tree.** Never stage a change you are not about to commit. Stage, commit, in one breath.
- **Boot writes nothing.** No seeding, no backfills, no repair. World building happens on admin buttons.
- **Answer in English. Always.**
- **A green status is not evidence.** This codebase has failed that way repeatedly.

### Traps that have cost real time
- `Team.reputation` is the economy's 0–100 scale, not an Elo. Club Elo lives in `eloRating`.
- `Country.reputation` is a different scale on a column of the same name.
- A season is 12 weeks counted from 1. There is no calendar year anywhere.
- `PyramidBuilder` never sets `Team.type`. A club is anything whose `competition` is a LEAGUE.
- Use `authFetch` for every authenticated frontend call and always check `response.ok`.
- `WE_HAVE_BALL` and `OPPONENT_HAS_BALL` are identical on purpose (`mirrorWeHaveBallRules`).
- `demo/service/` is frozen. Reference only.
- A guard that reads the production source must resolve the path, never be told it (use `ProductionSource`).
- `var/tactics-editor-profiles.json` is the owner's world default, tracked.
- A new column on an entity needs `columnDefinition` with a default, or PostgreSQL refuses it on a non-empty table.
- `Player`'s all-args constructor is positional — three tests spell every argument out.
- `*.md` files are gitignored — update, never commit.

### Key references
- `AGENTS.md` — guidance for AI agents (short, current rules)
- `TECHNICAL_OVERVIEW.md` — what the system is today
- `archive/kanban.md` — the old board (historical, not current)
- `archive/kanbanProgress.md` — the old append-only log (historical)
- `archive/MVPtaskList.md` — the old consolidated open work (historical)
- `archive/MVPprogress.md` — the old log for that list (historical)
- `archive/MATCH_ENGINE_ANALYSIS.md` — match engine findings (historical)
- `archive/MATCH_ENGINE_ANALYSIS_REPORT.md` — match engine report (historical)
- `archive/` — superseded planning, audits, per-mode notes (history only)
- `replay-data/replay-5633.json` — ground-truth replay of owner's complaints
- `src/test/java/org/tifo/footballmanager/newLogic/sim/ProductionSource.java` — source path resolver for guards

### Environment
- Java: `/Users/velja/Library/Java/JavaVirtualMachines/corretto-21.0.12/Contents/Home`
- Database: PostgreSQL `sokker_db`, user `postgres`, password `stojke`, local port 5433 (docker)
- Login: `velibor@example.com` / `A12345!`
- Agent-browser: `export PATH="$HOME/.npm-global/bin:$PATH"` (NOT on default PATH)
- Screenshots: `/tmp/`

---

## Part 2 — Open tasks (clear and precise)

### Match engine — owner order of 2026-10-23 ("NISTA ne valja")
Owner watched matches 3 and 215 in the browser and approved all items below. Work one task at a time, commit after each, update this file's progress section.

| # | Task | Severity | State |
|---|---|---|---|
| ME-1 | A red card REMOVES the player (pitch, snapshots, all pools) | P0 | **DONE** `6caedf67` |
| ME-2 | No pass back into his own half from a shooting position (hard veto) | P0 | **DONE** `cf4840d2` |
| ME-3 | Remove the shot frequency gate — the player decides from the position | P0 | **OPEN** |
| ME-4 | Duels resolve ONLY at physical contact (circles touch, ~1 m); approach runs OK; ball flies to the winner | P1 | **OPEN** |
| ME-5 | Deflections honest + visible: strike from the ball's spot, clear flight, ball touches the player, then bounces | P1 | **OPEN** |
| ME-6 | One shared id: fixture = match = replay; a replay is served only for ITS match | P1 | **OPEN** |
| ME-7 | Keeper stays near his goal: small step toward the shooter, mostly lateral | P2 | **OPEN** |
| ME-8 | Nobody stands still (always drift inside his 14×10 m cell); no two players on one spot; clamp to the 7×6 grid | P1 | **OPEN** (F8 added idle drift ±0.15, but ME-8 has more criteria) |
| ME-9 | Omladinac's current tactic = world default for ALL teams, survives Reset DB, fresh-world default | P1 | **OPEN** |
| ME-10 | Match setup with no orders → the default tactic (guard + UI check) | P2 | **OPEN** |

**Note:** ME-8 partially addressed by F8 (idle drift ±0.15). ME-9 and ME-10 remain fully open.

### Match engine — replay 5633, the first 40 seconds (owner order of 2026-10-26)
Owner watched replay 5633 and approved fixes F1–F11. All F-tasks below are **DONE**.

| # | Task | State |
|---|---|---|
| F7 | The carrier is never frozen on the ball | **DONE** `76b58fde` |
| F1+F2 | A pass visibly arrives (arrival tick kept + ball stops on receiver) | **DONE** `d29a9f4b` |
| F4+F5+F6 | Duels visible (DUEL_START/DUEL_RESOLVED, SIDE_ON_TIE_RADIUS 0.6, loser lock) | **DONE** `85507b8e` |
| F8 | Idle drift ±0.15 within cell when target reached | **DONE** `d7db5463` |
| F9 | Pass-repetition penalty (same passer→receiver pair penalised) | **DONE** `fe3c9c92` |
| F10 | No-dribble in own 16m + backward-pass penalty after no progression | **DONE** `667c4858` |
| F11 | Remove CHASE_SPRINT_MULTIPLIER (1.18); fatigue is the only legal speed modifier | **DONE** `e3f4ea7f` |
| F-RS-1 | Throw-in to self forbidden | **DONE** `b40a6a3f` |
| F-RS-2 | Ball never changes direction without a carrier | **DONE** `b40a6a3f` |

### Owner's open items (from kanban.md)
- **ELO on league tile**: leave as-is (owner's ruling: "ostavi kako jeste").
- **Indexes**: `match_tick_states` table no longer exists; `player_zone_load` already has `ix_zone_load_match`. Nothing to do.
- **P1 list (MVP)**: P1-2 and P1-15…P1-26 are **deferred** ("ovo cemo kasnije"). P1-3's national-tournament half is waiting on owner's decision.
- **HonourService crowns nobody in a final decided on penalties.** It ignores the penalty columns. Owner's call; closing it changes who is awarded a trophy.
- **InjuryServiceTest** (3 errors — NPE `recorder` is null) — test-side gap, not code defect.
- **HomeAdvantageIsRealTest** (3 red) — the home duel edge reads exactly 50%; parallel line's uncommitted DuelEngine WIP. Do not touch.

### UI — Pool layout stays fixed (2026-10-29)
- **Issue:** In the Club-tactic-tactic setup, when a player is moved from the Available pool to
  the selection deck (Starting XI or bench), the remaining players in the pool expand to fill the
  entire pool area. The pool should stay fixed — chips should not stretch when players are removed.
- **Root cause:** `.club-lineup-pool-list` had `display: grid` but no `grid-template-columns`,
  so each chip took the full width of the pool. When a chip was removed, the remaining ones
  stretched to fill the space.
- **Fix:** Added `grid-template-columns: repeat(auto-fill, minmax(120px, 1fr))` to
  `.club-lineup-pool-list` in `dashboard.css`. `auto-fill` keeps empty tracks, so remaining
  chips don't expand when players are removed.
- **Commit:** TBD
- **Guard:** visual — verified that the pool area stays fixed when players are moved.

### UI — Training tactic setup: players on circles with position labels (2026-10-29)
- **Issue:** Training players should stand on circles with position next to number (DEF 1, MID 1, ATT 1)
  because currently it says "1" for both defenders, midfielders, and strikers so they can't be
  distinguished.
- **Fix:** Pitch slots are now circular (border-radius: 50%) and the slot label (DEF 1, MID 1, ATT 1)
  is shown on the player chip itself. Players stand on circles with position labels.
- **Commit:** `26fc0cc9`

### Engine — Throw-in sequence (2026-10-29)
- **Rule:** OOB restart (throw-in) must follow this exact sequence:
  1. Ball teleports to throw-in spot
  2. Taker walks to the spot (not teleported)
  3. Taker picks a pass target or center
  4. Execute the command and only then can the player move
  5. Play continues normally
- **Status:** Already implemented in the engine. The throw-in taker claims the ball only when
  physically on it (orchestrator step 9), then the decision engine runs (step 6). The self-pass
  issue is fixed by F-RS-1 (justTookRestart flag). No additional code changes needed.
- **Commit:** covered by F-RS-1 (`b40a6a3f`)

### Owner's rules that must NEVER be violated
1. **No speed multiplier ever** — "NI ZA JEDNO kretanje ne sme NIKAD da bude multiplier u odnosu na pace skill osim fatigue". FatigueSystem.speedFactor is the only legal speed modifier (it can only slow).
2. **Carrier slowdown factors (0.85–0.96) stay** — they make the carrier slower, never faster.
3. **Throw-in to self forbidden** — never throw to oneself or into empty space; only to a free player within 2-2.5 cells, or into the box/center for a duel.
4. **Ball never changes direction without a carrier** — no action starts without a carrier on the ball.
5. **Ball on ground is primary objective** — especially in own box; if ball is within 0.5 cells, it is the primary target.
6. **No dribble in own 16m** — only pass/clear are legal.
7. **Received pass must be unambiguously visible** — player and ball on same coordinate.
8. **No dribble in own 16m and around the box** — rows 1.0–2.5 HOME, 5.5–8.0 AWAY.

---

## Part 3 — Progress (chronological, newest first)

### 2026-10-30 — Short pass speed reduction + nudge reduction (prevent OOB)
- Commit: `dc09deb5`.
- ActionExecutor: pass launch speed scaled by distance for passes under 2.5 cells (short passes are slower, no overshoot). openingTarget nudge reduced from 0.5 to 0.2 (prevent pushing ball toward touchline/OOB).
- Also reverted viewer FE rendering to good state (11:53) with only position labels kept.


### 2026-10-30 — Replay 5633 round-2: receive-hold, receiver chase, throw-in min distance, pass scoring, position labels
- Commit: `1f7cacbd`.
- sim Player: added `receiveHoldTicks`; carrier who just received holds the ball for one tick (no receive-and-pass in same tick). BallPhysicsEngine sets it on all three receive paths; MatchOrchestrator skips the decision block while it is >0 (snaps ball, no action); MovementEngine roots the player.
- TacticalIntentEngine: pending-receiver chase cap 0.5 -> 0.15 so the receiver stays near his tactical position.
- CleanDecisionEngine: throw-in minimum teammate distance 0.5 -> 1.0 cell; pass scoring: progressive long-pass penalty beyond 2.0 cells, space weight 15->10, safe-short-pass bonus 10->15.
- viewer.js: show position role (first 3 chars) in the player circle instead of extracting a number from the label.
Commit: 4ddb9174. Owner clarified that the visual circle representing a player is max 0.20 cells (~2.8m), not 7m. Previous values of 0.5 were too large. Updated MIN_PLAYER_DISTANCE, DRIBBLE_DUEL_RADIUS, PRESS_DRIB_DUEL_RADIUS, and SIDE_ON_TIE_RADIUS from 0.5 to 0.2. All 13 guard tests green.

### 2026-10-29 — Replay 5633 F11: remove CHASE_SPRINT_MULTIPLIER (1.18)
Commit: `e3f4ea7f`. Owner rulings: F3 rolling-ball pickup, F11 no multiplier except fatigue (carrier slowdown stays). Removed `CHASE_SPRINT_MULTIPLIER = 1.18` from MovementEngine, `move.chaseSprint` from MatchEngineVersion. FatigueSystem.speedFactor is the only legal speed modifier. All 13 guard tests green.

### 2026-10-29 — UI: training players on circles with position labels
Commit: 26fc0cc9. Pitch slots are now circular (border-radius: 50%) and the slot label (DEF 1, MID 1, ATT 1) is shown on the player chip itself, so defenders, midfielders, and strikers are distinguishable by position and number.

### 2026-10-29 — F-RS-3: fix risky pass out of bounds
Commit: e3fb4b96. OOB risk penalty (-20) when pass target is near touchline (col < 1.5 or col > 6.5). Safe short pass bonus (+10) for short, safe passes to nearby teammates. Risky pass penalty increased from -26 to -40. All 13 guard tests green.

### 2026-10-29 — Pool area stays fixed in tactic setup
Commit: d0ade0be. Added `grid-template-columns: repeat(auto-fill, minmax(120px, 1fr))` to `.club-lineup-pool-list` so remaining chips don't expand when players are moved.

### 2026-10-29 — Replay 5633 F10: no-dribble in own 16m + backward-pass penalty
Commit: `667c4858`. Dribble forbidden in own 16m (HOME rows 1.0–2.5, AWAY rows 5.5–8.0). Backward-pass penalised after 100 ticks (~150 s) without forward progress (extra -20). Clear always an option. All 13 guard tests green.

### 2026-10-29 — Replay 5633 F9: pass-repetition penalty
Commit: `fe3c9c92`. `MatchState.beginPass` records `lastPassPasserId`/`lastPassReceiverId`. `CleanDecisionEngine.findBestReceiver` penalises same pair by 20.0. All 13 guard tests green.

### 2026-10-29 — Replay 5633 F8: idle drift ±0.15 within cell
Commit: `d7db5463`. Drift applied in MovementEngine after target reached, before target cleared. ±0.15 cells random offset for non-carrier, non-chaser. Guard: `IdleDriftTest` (3 tests).

### 2026-10-29 — Replay 5633 F4+F5+F6: duels visible
Commit: `85507b8e`. `DUEL_START` + `DUEL_RESOLVED` events emitted. `SIDE_ON_TIE_RADIUS` 0.40 → 0.6. Loser lock 2 ticks. Guard: `FourDuelsVisibleTest` (3 tests).

### 2026-10-29 — Replay 5633 F1+F2: pass visibly arrives
Commit: `d29a9f4b`. `SimReplayView.downsample` keeps arrival ticks. Received ball stops ON receiver. `RECEIVE_R_BOOSTED` 0.5 → 0.25. Guard: `PassArrivesVisiblyTest` (4 tests).

### 2026-10-29 — Replay 5633 F7: the carrier is never frozen on the ball
Commit: `76b58fde`. Receiverless pass scores UNAVAILABLE. Emergency clearance when nothing available. Guard: `CarrierIsNeverFrozenWithNoOptionTest` (3 tests).

### 2026-10-29 — Owner rules: throw-in to self forbidden + ball never changes direction without carrier
Commit: `b40a6a3f`. `justTookRestart` flag in MatchState. `findBestReceiver` filters receivers for throw-in taker. `ActionExecutor.execute` guard: carrier must be on ball (CLEAR is exception). All 13 guard tests green.

### 2026-10-26 — Replay 5633 diagnosis delivered
Owner watched replay 5633 and approved fixes F1–F11. Diagnosis presented with file/line references. Owner rulings on F3 (rolling-ball pickup) and F11 (no multiplier) received.

### 2026-10-23 — ME-1 baseline run
Measured: 404 tests, 7 red. None from ME-1; all pre-existing. 3 classes red: InjuryServiceTest (3 errors), ProposalEngineIsTheOnlyFixtureProducerTest (1 red), HomeAdvantageIsRealTest (3 red).

### 2026-10-22 — P0-6 first-run onboarding closed
Commit: `7c9f7b6c`, `d7c06eec`. Readout names the button; first run produces a whole world.

### 2026-10-22 — P0-7 stranded/missed matchdays closed
Commit: `588318d4`, `53a7f1af`. Check built; walk's own guard fixed.

### 2026-10-22 — P0-11 Day 6 form & morale job closed
Commit: `ddae56a1`, `722eb2e6`. Day 6 computes form and morale.

### 2026-10-22 — P0-15 debt, interest, bankruptcy closed
Commit: `3a7b0592`. Two weeks in the red → sell players → bankruptcy.

### 2026-10-22 — P0-19 dead WebSocket endpoints closed
Commit: `18c8ec6f`. Removed 4 handlers, kept @EnableScheduling.

### 2026-10-22 — P0-21 manager H2H in match preview closed
Commit: `0a17e2f2`. MatchPreviewService adds managerH2H block.

### 2026-10-22 — P0-54 cup prepare OOM closed
Commit: `14a5ba11`. Tactics cache bounded LRU.

### 2026-10-22 — P0-53 test run advances live world closed
Commit: `990d13b7`. Surefire sets scheduler-enabled=false.

### 2026-10-22 — P0-47 player objection closed
Commit: `7ddb3167`. Removed requireResolved guards.

### 2026-10-20 — P0-32 T-KIT-1 players wear no kit closed
Commit: `31ab2f51`. Team gained kit colors, viewer uses them.

### 2026-10-19 — P0-3 restart removes a player the manager added closed
Commit: `6caedf67` (ME-1). Sent-off player leaves the pitch.

### 2026-10-19 — P0-8 TIE tiebreaker closed
Commit: pts → gd → goals scored → draw, globally.

### 2026-10-19 — P0-9 pre-match tactical preview endpoint fixed
Commit: route now answers through MatchPreviewService.

### 2026-10-19 — P0-10 standing game plan visible
Commit: panel dropped as duplicate; tactic orders are the standing plan.

### 2026-10-19 — P0-37 day-1 matchday could not be prepared fixed
Measured and fixed on live database. 480 fixtures prepared in ~6 min.

### 2026-10-19 — P0-49 live preview/report/stats leak fixed
All three ZoxApiController routes now go through MatchPublicationGuard.

---

*This file is the current source of truth for tasks and progress. The archived files in `archive/` are historical and must not be treated as current.*
