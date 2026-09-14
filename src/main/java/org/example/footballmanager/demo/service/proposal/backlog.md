# Proposal Engine — Backlog

Ordered by priority. Implementation of individual engine logic is deferred —
stubs exist, logic comes later.

---

## P1 — Stats layer (highest gap per user requirement)

> Every action must be recorded in stats for statistics, analysis, and future
> linking with the main manager. Per team AND per player.

**P1 STATUS (2026-09-14): CLOSED — svaki finishable item je DONE i verifikovan.**
Svi `[x]` ispod su implementirani + verifikovani (possession chains: HOME avg
11.6 / longest 58, AWAY avg 18.1 / longest 952 — izvezeno + renderovano).
Oznake `[~]` NISU "nedovršeni P1 rad" — to su **placeholders pokazivači na
P7 enginee** (DisciplineService / OffsideService / VARService / PenaltyService
+ akcioni subtipovi) koji ne postoje u engine-u. Oni se ne mogu završiti
pre nego ti engine-i nastanu; kad nastanu, track-uju se u P7, NE u P1.
Nijedan P1 item ne zavisi od P7 stuba da bi P1 bio COMPLETE za svoj scope.

> **DEFERRED → P7 (NE checkbox stavke — isključeni iz P1 liste, idu u P7):
>**
> 1. Goals breakdown by type (open-play / center / cross / penalty / FK /
>    corner) — needs subtype actions (P7 ActionEngine subtypes)
> 2. Pass types (thru / center / cross / air / ground) — needs subtype actions (P7)
> 3. Free-kicks / penalties restarts — when DisciplineService/PenaltyService
>    exist (P7)
> 4. Cards: yellow / red / double-yellow — when DisciplineService exists (P7)
> 5. Offside / VAR counts — when OffsideService/VARService exist (P7)
> 6. Per-player fouls committed / received — when DisciplineService exists (P7)
> 7. Per-player cards (yellow, red, double-yellow) — when DisciplineService
>    exists (P7)
> 8. Per-player offside count — when OffsideService exists (P7)
> 6. Per-player fouls committed / received — when DisciplineService exists (P7)
> 7. Per-player cards (yellow, red, double-yellow) — when DisciplineService
>    exists (P7)
> 8. Per-player offside count — when OffsideService exists (P7)

### P1a — Per-team stats
- [x] Shots: total / on-target / saved / missed / blocked / post
- [x] Passes: total / successful (+ pass accuracy)
- [x] Dribble: total / successful
- [x] Interceptions, deflections
- [x] Restarts: corners / throw-ins / goal-kicks
- [x] Possession: % (chain avg duration + longest chain — added 2026-09-14
  via chain tracking in `ProposalStatsCollector`/`TeamStats`, rendered as
  "Poss. chain (longest)" row in the viewer stats panel)

### P1b — Per-player stats
- [x] Minutes played, average rating
- [x] Shots: total / on-target / goals
- [x] Passes: total / successful (+ pass accuracy)
- [x] Dribble: total / successful
- [x] Interceptions, deflections
- [x] Duels: total / won (tackles + duelsWon)
- [x] Saves (GK), clearances, assisted goals

### P1c — Implementation
- [x] `MatchStats` model: per-team + per-player counters (`PlayerStats` / `TeamStats` records)
- [x] `StatsCollector`: wired into orchestrator — fed on every action + physics result
- [x] Export to `match.json` (`stats` field: teams + players)
- [x] Viewer sidebar: Match Stats panel (team comparison + possession bar + player ratings)

---

## P2 — Orchestrator slimming

> Orchestrator has ~340 lines: log formatting, event recording, result
> handling, duel detection.  Too much for "just coordinates."

- [x] Extract `handleBallPhysicsResult()` → `BallResultHandler` helper (2026-09-14, seed 42: 3 real callers — orchestrator slim 480→325, DEFLECT/SAVE/POST_HIT/blocked-switch handled in helper)
- [ ] Extract `detectAndResolveDuels()` → `DuelService` helper
- [ ] Extract log formatting → `ActionLogService` (structured log with tags)
- [ ] Orchestrator keeps only: clock → unlock → ball → decision →
  execution → tactical → movement → restart → rules → duels → stats

### P2-UI — Motion & restart correctness (user-reported 2026-09-14)

> KORISNIČKA PRIJAVA. Oba problema su viđena u UI viewer-u (proposal engine).
> **STATUS: oba fiksirana 2026-09-14 (isti dan kao prijava).**

- [x] **1) ACTION BEZ CARRIER-A NA LOPTI** — udarci iz polja (šut / pas / itd.)
  kreću iako carrier NIJE na lopti — lopta je vizuelno SAMA (leti bez igrača).
  **NE SME NIKAD.**
  - **ROOT CAUSE:** `MovementEngine.moveAllTowardTargets()` pomera carrier-a
    svaki tick, ali se lopta kači na carrier-a SAMO u decision bloku (pre
    movement-a). Tokom driblinga carrier se odmakne, lopta ostane na staroj
    poziciji — naredni šut/pas "teleportuje" loptu napred i kreće sa strane.
  - **FIX:** `MatchOrchestrator.tick()` — novi korak **8b POSSESSION GLUE**
    posle movement-a: ako `carrier != null`, lopta se postavlja na njegovu
    poziciju i stopira. Snapshot se snima posle toga, pa niti jedan kadar
    ne prikazuje loptu odvojeno od carrier-a.
  - **VERIFIKACIJA:** 445/445 IN_POSSESSION snapshot-a — max gap 0.0000 cells.
- [x] **2) RESTART POZICIONIRANJE — LOPTA RESTARTOVANA NA POGREŠNU STRANU** —
  taker ne stiže do lopte (restart walk problem), a desila se i katastrofa:
  lopta je izašla OOB kroz kolonu 6 u kolonu 7, pa se restartovala NA KOLONU 1
  (suprotna strana). Znači restart levo/desno + home/away gore/dole ima bag.
  - **ROOT CAUSE:** `RestartManager.getRestartPosition(type)` je IMAO HARDKODOVANE
    pozicije: THROW_IN uvek `(4.5, 1.0)` (leva aut linija bez obzira na to gde je
    lopta izašla), CORNER uvek levi ugao. Funkcija nije primala poziciju izlaska.
  - **FIX:** `handleRestart(state, restartType, oobExit)` — OOB izlazna pozicija
    (uzeta iz `state.getBall()` tokom OOB holdu) se prosleđuje; throw-in ide na
    ONU aut liniju (col 1.0 levo / col 7.0 desno) na izlaznom redu (clampan u
    playable zonu); CORNER ide na odgovarajući OLD corner flag na izlaznoj
    polovini (col 1.0 levo / 7.0 desno ako je izašao levo/desno od centra).
  - **TAKER STIŽE:** dodat DEMO/SERVICE teleport fast-path (§48) — taker udaljen
    > 4.0 cells se snapuje na 0.6 cells iza lopte (ka svom golu), pa hoda kratko.
  - **VERIFIKACIJA:** OOB col ≈ 7.3+ → restart ball(…, 7.0); OOB col ≈ 0.2-0.9 →
    restart ball(…, 1.0); red (row) očuvan (4.2→4.2, 7.3→7.3, 6.6→6.6, 7.0→7.0).

---

## P3 — Ball physics calibration

- [ ] DEFLECT_R: 0.035 → 0.05 or 0.07 — test both, measure impact
- [ ] readIntercept: verify prob is realistic (not 0.45 near receiver)
- [ ] Goal plane detection: confirm off-target shots never cross goal
  line inside mouth (tested in session 6.9)
- [ ] Post hit: test bounce angle/damp (currently reflect + damp = 0.6)
- [ ] OOB hold: confirm ball goes 1 cell past line on miss

---

## P4 — Player movement improvements

> Players move by pace skill A→B, can go around obstacles.

- [ ] Obstacle avoidance: `separateFromOpponents` is slide-ring only —
  implement real perpendicular go-around when blocked ahead
- [ ] Per-tick tactical target refresh: when ball crosses a new grid cell,
  all non-carrier players recalculate tactical targets from TacticalIntentEngine
- [ ] Carrier speed modulation: faster when free (no defenders in 1 cell),
  slower under active pressure (TYPE A override active)
- [ ] Wall avoidance: when blocked by teammate, slide perpendicular
  (same as opponent separation but for own team)

---

## P5 — Offside full implementation

- [ ] Continuous tracking: `trackOffsidePositions()` every tick for all
  attackers on both teams (per corePrinciples §16)
- [ ] Per-pass check: offside at pass/cross/through-ball moment
- [ ] Second-to-last defender rule (not last — FIFA Rule 11)
- [ ] Offside retreat: already in ThreatOverrideEngine stub (TYPE C) —
  implement `applyOffsideRetreat()` logic from demo/service
- [ ] Counter reset: when player is clearly onside, counter resets

---

## P6 — Pass completion calibration (67% → target ~98%)

> Deferred — implementation comes later.

- [ ] Investigate root cause: `readIntercept` still too permissive
  (launch-speed model active, 0.14 lane, every tick)
- [ ] Test DEFLECT_R 0.035 vs 0.05 vs 0.07 — measure deflection
  count + pass completion impact
- [ ] Tune `interceptChance` formula / lane threshold / pm+def gate
- [ ] Run `ProposalBatchDiag 50` after each change, record metrics

---

## P7 — Rules stubs (already created, logic later)

| Class | Package | Status |
|---|---|---|
| `VARService` | `rules/` | stub — method signatures, no logic |
| `DisciplineService` | `rules/` | stub — modular rule methods, no logic |
| `OffsideService` | `rules/` | stub — tracking + check, no logic |
| `ThreatOverrideEngine` | `engine/` | stub — TYPE A/B/C methods, no logic |

All four already compile. Logic to be filled per respective phases above.

---

## P8 — Backlog (not scheduled)

### Fatigue system
- [ ] Stamina drain per tick (based on running distance)
- [ ] Speed multiplier from fatigue (max 30% loss per `MAX_FATIGUE_SPEED_LOSS`)
- [ ] Auto-sub at configurable threshold
- [ ] Injury risk increase with fatigue

### Transition logic (possession change)
- [ ] When ball is lost (intercept/deflect/tackle): immediate reorganization
- [ ] Losing team shifts shape to defensive (no 2-3 second delay)
- [ ] Winning team shifts shape to attacking
- [ ] Track possession chains (chain ID, pass count per possession)

### Hard rules → boost refactoring (backlog only)
- [ ] `CleanDecisionEngine` final-2-row SHOT hard rule: refactor so that
  the rule becomes a large score boost (e.g. +200) so the decision
  engine naturally picks it; if random misses, there's a strong reason
- [ ] Same for kickoff special-case (lines 43-51)
- [ ] The override system as a formal layer is NOT needed now — hard
  rules stay until they can be expressed as boosts

### App log (structured, debug-quality)
- [ ] `ActionLogService` with structured tag system (DEC, ORC, BAL, DUL,
  RST, TAC, THREAT, VAR, DISC, OFF)
- [ ] Two output levels: FULL (stdout/file for debug) + COMPACT (sidebar/UI)
- [ ] All engines log through this service (not raw println)

### Rating system
- [x] Per-player average rating based on actions (goals, assists, tackles,
  passes, fouls, cards) — DONE in P1 (calculateRating in ProposalStatsCollector)
- [x] Export in match.json — DONE in P1 (`stats.players[].rating`)

### Viewer enhancements
- [x] Stats tab in sidebar (P1c) — DONE (team table + possession bar + player ratings)
- [ ] Player highlight on click (show stats)
- [x] Possession % bar — DONE in P1 stats panel

---

## Rules of engagement

- All changes verified via `ProposalBatchDiag 10` (or 50 for stats)
- Engine is non-deterministic — judge via batches, never single traces
- `mvn -q compile` must pass after every change
- `match.json` is 58MB and gitignored — never commit
