# THREAT / SAFETY OVERRIDE LAYER — Behavioral Spec (demo `org.example.footballmanager.demo`)

**Status:** Phase 1 — **IMPLEMENTED & VERIFIED.**
- `mvn -o test-compile` → clean.
- 8 existing demo tests → **38 run, 0 failures, 0 errors** (layer OFF in all via `noop` ctors / `create(...)`; no tests added, none modified).
- Production playmaking/movement/duel formulas: **frozen** (untouched).

**Scope:** `SimulationEngine → SimulationStepEngine → TacticalIntentEngine → ThreatEngine → MovementEngine / DuelEngine / SimulationState`.

> If the requirement changes, edit **this file** (the contract). `grep -n` / `wc -l` are the line
> sources of truth (the `read` tool mislabels lines when `start_line≠1`).

---

## 0. Core principle

`ThreatEngine` is a **defensive override layer**.

It runs **after the normal playmaking decision and before movement**, and may replace the normal tactical movement target of **our non-carrier players**.

It does **not** replace the carrier's playmaking decision.

The layer has two responsibilities:

1. **Threat override** — react to dangerous/nearby opponents.
2. **Offside safety** — prevent our offside players from remaining in an unsafe position and handle an offside pass.

Existing playmaking, movement, passing and duel formulas remain unchanged.

---

## 1. Hard constraints

Do **NOT** modify:

* `PlaymakingDecisionEngine`
* `VisionFilter`
* `OptionSelector`
* `ActionEngine`
* `MovementEngine`
* `DuelEngine`
* `ExecutionQuality`
* existing passing / shooting / carry formulas
* existing movement formulas
* existing duel formulas

Do NOT redesign the existing decision engine.

`ThreatEngine` may only determine the **desired movement target** and offside safety state/event.

Actual movement remains performed by:

```java
MovementEngine.oneCellToward(...)   // MovementEngine.java L113
```

Therefore:

**maximum movement = 1 cell per tick.**

A player may move more than 1 cell during one action because the override is evaluated again on subsequent ticks.

---

## 2. Defensive context

Threat rules are **defensive reactions**.

For HOME:

```text
own goal = row 1
defensive third = rows 1–3
```

For AWAY:

```text
own goal = row 7
defensive third = rows 5–7
```

The layer does **not** mean every opponent is automatically treated as a threat. The rules below decide when one is.

## 3. Distance definition

A distance of **1 cell** means:

```java
MovementEngine.distance(a, b) <= 1.0   // MovementEngine.java L264  (Math.hypot)
```

Distance is **Euclidean**. This is intentionally **not** "all 8 neighbouring cells", Chebyshev, or a hand-counted cell list. Because positions may carry fractional columns (e.g. `3.5`), the radius-1.0 disc can intersect a variable number of grid cells.

## 4. Threat ownership / one defender per threat

For every tick:

* one opponent may be assigned to **at most one** of our defenders;
* one of our defenders may react to **at most one** opponent.

If several of our players can react to the same threat:

> **the closest eligible defender gets it.**

This is resolved **statelessly** (no per-tick set) inside
`ThreatEngine.isClosestOurPlayerTo` (L394): a defender `P` is the closest eligible team-mate
to threat `O` within the type's `[lo, hi]` band only if no *other* eligible team-mate of `P`
is strictly closer to `O`. Deterministic — iteration order is irrelevant.

## 5. Threat priority

For every eligible non-carrier player `P`:

```text
1. OFFSIDE SAFETY
2. TYPE A — defensive-third isolated opponent
3. TYPE B — isolated opponent ball carrier
4. TYPE C — local opponent proximity
5. NORMAL TACTICAL TARGET
```

**Offside safety has priority over pressing behaviour.** A player who must retreat due to offside does not press another opponent on that tick (`pressTarget` evaluates `isOffside(p)` first, L98).

## 6. TYPE A — defensive-third isolated opponent (≤ 1.5 cell)

Opponent `O` qualifies if **all** hold:

**A1 — opponent is in our defensive third.**
HOME: `O.row <= 3.0`; AWAY: `O.row >= 5.0` (`inDefensiveThird`, L365).

**A2 — opponent is isolated.**
No team-mate of ours (other than the evaluating defender `P` itself) is within `≤ 1.0` of `O`
(`isolated` via `teammateCountWithin(o, 1.0)`, L378; `P` excluded by `t != o`). `O` may be ≤1.0 from `P`.

**A3 — defender is within 1.5 cells.**
`MovementEngine.distance(P,O) <= 1.5` (`PRESS_A_MAX=1.5`, L44; band `[PRESS_FLOOR=0.5, PRESS_A_MAX=1.5]`).

> `1.5` is **intentional**, not a contradiction with the 1-cell local rule: ≤1.0 already falls into Type C's local zone; the extra 0.5 lets the defensive-third rule spot an isolated threat **before** it enters the immediate vicinity.

**Result:** `P`'s desired becomes `O.position` (`pressTarget` returns it, L340-equivalent path).
`MovementEngine.oneCellToward` (L113) moves `P` ≤ 1 cell toward `O`. Positional press (no ball involved); no `ThreatEngine`-opened duel. Only **one** defender claims `O` (§4).

## 7. TYPE B — isolated opponent ball carrier (≤ 1.0 cell)

Anywhere on the pitch. `O` qualifies iff **all** hold:

**B1** `O == state.getBall().getCarrier()` (`SimulationState.getCarrier` L234) and `O` is an opponent.
**B2** `O` isolated from our team within 1.0 (`isolated`, L374).
**B3** `MovementEngine.distance(P,O) <= 1.0` (`PRESS_B_MAX=1.0`, L45).

**Result:** `pressTarget` returns `O.position` (L347-equivalent); `P` moves toward `O`. When the gap
closes to the duel radius, `DuelEngine`'s existing proximity gate (`distance <= DuelEngine.DEFAULT_DUEL_RADIUS = 0.5`)
resolves the duel — `ThreatEngine` only sets direction. Only **one** defender claims the carrier (§4).

## 8. TYPE C — local tactical-position correction (NARROW: ≤ 1.0, 0.5 floor)

This is the normal defensive proximity reaction. It applies when:

```text
opponent O is within 1.0 cell of our player P   // MovementEngine.distance(P,O) <= 1.0
```

> P is in his normal tactical area, but an opponent is immediately nearby, so P moves toward that opponent instead of the abstract tactical cell. **C is strictly local `distance <= 1.0` with a 0.5 duel-radius floor** — not a catch-all that sweeps half the pitch.

`O` qualifies to `P` iff both hold:
1. `0.5 <= MovementEngine.distance(P,O) <= 1.0` (`PRESS_C_MAX=1.0`, L46; `PRESS_FLOOR=0.5` = `DuelEngine.DEFAULT_DUEL_RADIUS`, deliberately the same floor so C never fights B/duels).
2. No Type-A or Type-B rule already fires for `P` (A > B > C priority in `pressTarget`, L93).

When `O` is the carrier this is indistinguishable from B (duel opens at ≤0.5); when `O` is not the
carrier it is positional marking. Type C never claims an opponent that A or B would claim for a
different our-player (§4 keys on opponent identity + `isClosestOurPlayerTo`).

## 9. Threat resolution example

```text
Our:  P1 distance 0.7 from O    P2 distance 0.9 from O
Opponent: O = isolated ball carrier
```
Both P1 and P2 qualify. Result:

```text
P1 -> O      (closest -> claims O)
P2 -> normal tactical target   (O already claimed by the closer teammate)
```

---

## 10. OFFSIDE SAFETY

Offside **is** implemented as a safety sub-layer (not a playmaking redesign). The engine must
distinguish:

```text
OFFSIDE POSITION          (player standing in an offside spot)
      ↓
OFFSIDE EVENT             (player in an offside position RECEIVES a pass)
```

Being in an offside position does **not** automatically stop play. The violation occurs when that
player **receives a pass while offside**.

## 11. Offside position

An attacking player is offside when, at the relevant moment:

1. he is in the opponent's half;
2. he is ahead of (level with) the ball;
3. fewer than two opponents are between him and the opponent's goal.

Team-relative orientation (FIFA second-to-last-defender, `isOffside` at `ThreatEngine.java` L207):

```text
HOME attacks toward row 7  ->  opponent's goal = row 7
AWAY  attacks toward row 1 ->  opponent's goal = row 1
```

Concretely (`isOffside`, L207–236):
- HOME: immune in own half (`row <= 4.0`); on-side behind ball (`row < ballRow`); otherwise `countOpponentsBetween(p, towardRow7=true) < 2` → offside.
- AWAY: immune in own half (`row >= 4.0`); on-side behind ball (`row > ballRow`); otherwise `countOpponentsBetween(p, towardRow7=false) < 2` → offside.
- The **ball carrier is never offside** (L215–216).
- Result cached on the player: `Player.setOffside(boolean)` (L122), field L31; read via `isOffside()` (L118).

`isOffside` flags **only clear** FIFA violations: borderline/uncertain positions are treated as
on-side ("poor playmaking can still slip through"). Known edges: A8 (keeper as attacker near own
goal), A9 (head-of-ball borderline) — see §A.

## 12. Offside safety movement

If our player is in an offside position, retreat is higher priority than normal tactical movement:

> **RETREAT** (not press).

The player moves toward the nearest row where he is no longer offside. The retreat target is the
row one cell behind the **second-to-last opponent** (so two opponents are then between player and
goal), computed by sorting opponents toward the opponent's goal (`offsideRetreatTarget`, L252;
`countOpponentsBetween` L233). Retreat is **row-axis only** (column unchanged — no lateral shove)
and bounded to ≤1 cell/tick by `MovementEngine.oneCellToward` (L113); it compounds across ticks
until `isOffside` returns false. With fewer than two opponents, retreat to the centre line `row=4.0`.

## 13. Offside pass / violation

If a player in an offside position **receives a pass**:

```text
PASS RECEIVED  ->  PLAYER WAS OFFSIDE  ->  OFFSIDE VIOLATION
```

The receiving player does **not** continue normal playmaking. The action is stopped and the restart
belongs to:

```text
THE OPPOSING TEAM
```

## 14. Offside restart location

The restart location is:

> **the exact position where the offside player received the pass.**

Do **not** move it to the previous ball position, the passer, the nearest grid cell, or a predefined
free-kick spot. Use the receiver's actual position at the moment the violation is detected.

## 15. What happens immediately after an offside violation

The violation **must**:

1. stop the current attacking action;
2. transfer restart responsibility to the opposing team;
3. place the restart at the **exact** receiving location;
4. clear the invalid attacking action;
5. let normal simulation continue from the restart.

It must **not** fabricate a new movement/passing/duel algorithm. `ThreatEngine` reuses the existing
restart lifecycle: it sets the same 3 fields the throw-in/out-of-bounds path sets (§17 anchors):

```java
Position exactPos = receiver.getPosition();                 // L413 — EXACT receiving cell
String opponentTeam = HOME.equals(receiverTeam) ? "AWAY" : HOME;
Player restartPlayer = selection.closestTeamTo(exactPos, opponentTeam); // L416 — nearest opponent
state.setPendingRestartPosition(exactPos);                   // L418
state.setPendingRestartPlayer(restartPlayer);                // L420
state.setRestartPassToHomeGoalkeeper(false);                 // L422
state.setRestartHoldTicks(60);                               // L423 (60 = OFFSIDE_HOLD_TICKS, L48)
```

Resolution happens in `SimulationEngine.advanceInternal` (L132), the **existing** restart resolver —
`advanceInternal` does **not** call `step()` (only `stepEngine.step()` at `SimulationEngine` L126),
so a `VIOLATION` set inside `step()` is consumed by the **next** `advanceInternal` tick:

```text
SimulationEngine.advanceInternal()  (L132)
   L156-158  (pendingRestart + holdTicks > 0) -> consumeRestartHoldTick(); return   // ball frozen
   L164-173  (pendingRestart + holdTicks == 0) -> startRestart(exactPos, opponent, false)  // L560
```

After the 60-tick hold, `startRestart(Position, Player, boolean)` (L560) makes `restartPlayer` the
carrier at `exactPos`, sets the ball target, and arms the restart action/CHASE — the opponents take a
throw-in-style restart from the **exact** spot. This is the **same** pattern the existing throw-in
code sets (`setRestartHoldTicks(60)` at `SimulationEngine` L612). The `kickoffPending`/`kickoffTeam`
path (L68 kickoff branch, L207 kickoff check) is **not** used: kickoff expects row-4 placement,
whereas offside restarts from the exact receiver cell via the generic resolver.

## 16. Offside and passing priority

If the current carrier's decision is `PASS` and the intended receiver is offside:

> **the pass must not proceed as a normal successful attacking pass.**

Before `executeDecisionOption` (L229), `SimulationStepEngine.step()` calls
`ThreatEngine.overrideCarrierPass(decision)` (`SimulationStepEngine` L215–217):

```text
PASS DECISION
     │
     ▼
INTENDED RECEIVER
     │
     ├── legal receiver exists  ───→ PASS_LEGAL : swap decision.target = legal receiver (§17)
     │
     └── receiver offside, no legal ─→ VIOLATION  : stop, opponent restart @ exact spot (§13-§15)
```

This does **not** change `PlaymakingDecisionEngine` formulas — the safety layer is a final legality
gate on the carrier's committed PASS/THRU decision only. *(Spec §16/§17 also mention "CLEAR" as a
colloquial fallback; the detailed mechanism in §13–§15 is VIOLATION = opponent restart at the exact
spot, which realises the same intent: the offside-targeted pass is not completed and the
opponents take over at the spot. Implementation follows §13–§17.)*

## 17. Offside receiver resolution (legal receiver first)

For a pass with the intended receiver offside, prefer a legal option:

```text
legal receiver  >  offside receiver
```

`ThreatEngine.legalReceiver(carrier, intendedPos)` (L450) returns the **closest on-side** team-mate
(excluding the carrier; `Player.isOffside()` filter L440) to the original destination. If a legal
receiver exists → `PASS_LEGAL` (replacement `DecisionOption(DecisionType.PASS, legal, …)`,
`DecisionOption` ctor L39) and the carrier passes to the legal receiver. If **no** legal receiver
exists → `VIOLATION` (§13–§15).

## 18. Threat vs offside priority

```text
P is offside      P is offside, O nearby
      │                 │
      ▼                 ▼
  RETREAT           (not press)
```

**OFFSIDE SAFETY > THREAT PRESS** until the player reaches a safe position. Once safe,
TYPE A/B/C may become active again. (`pressTarget` checks `isOffside(p)` before any threat, L93–102.)

---

## 19. Logging (§19 — exact tokens emitted via `SimulationState.log`, L473)

Threat (emitted by `logThreatDetected`, L290):
```text
THREAT DETECTED | TYPE=<A|B|C> | DEFENDER=<P.label> | OPPONENT=<O.label> | CARRIER=<yes|no> | DIST=%.2f | NORMAL=<tacticalCell> | OVERRIDE=toward(OPPONENT) | REASON=<DEFENSIVE_THIRD_ISOLATED|ISOLATED_BALL_CARRIER|LOCAL_PROXIMITY>
```
Offside position (retreat) (emitted by `logOffsideRetreat`, L299):
```text
OFFSIDE DETECTED | PLAYER=<P.label> | POSITION=<row,col> | BALL=<row,col> | ACTION=RETREAT
```
Pass redirected to a legal receiver:
```text
OFFSIDE SAFETY | PASS_TO_LEGAL | PASSER=<...> | OFFSIDE_RECEIVER=<...> | LEGAL=<...>
```
Offside violation (emitted by `setupOffsideViolation`, L431):
```text
OFFSIDE VIOLATION | PLAYER=<P.label> | RECEIVE_POSITION=<row,col> | RESTART=OPPONENT
```
`DIST` uses `MovementEngine.distance` (L264); positions use two-decimal `row,col`
(`fmtPos`, L281). A non-claiming defender simply emits **no** `THREAT DETECTED` line (the §4
stateless dedup means the closest eligible defender is the only one that logs) — equivalent to the
spec's `CLAIMED_BY_TEAMMATE` note but lower-noise.

**§15 — match clock in every log line.** `SimulationState.log` (L473) now prepends the match
clock (`[mm:ss]`, from `SimulationState.matchClockLabel()` L340) between the wall-clock timestamp
and the message, e.g. `[AppLog] 19-08-2026 11:40:47 [55:41] OFFSIDE DETECTED | …`. The one
existing log that hand-injected a clock (the `ActionEngine` CHASE TICK line) had its manual
`time=…` removed so every line is sourced from a single place. Verified live in the headless smoke
harness (sample lines carry `[0:02]`, `[4:02]`, `[55:41]`).

---

## 20. Hard boundaries (affirmations)

* `ThreatEngine` reads state only (gated by `isEnabled()`); when off it returns `null`/`KEEP` and
  mutates nothing. When on it mutates **only** the `desired` cell handed to `oneCellToward`
  (via `state.setTacticalDesiredPosition`, `SimulationState` L518), the `Player.offside` flag (L122),
  and the restart scheduler fields (§13); **never** positions/velocities/skills/duel state/ball state
  (except the restart positions the existing lifecycle already touches).
* `DuelEngine` keeps its single-active-duel invariant (`activeDuel`, L12); `ThreatEngine` never
  instantiates a `Duel`.
* `MovementEngine` keeps the per-round `pace/20.0` cap (L44), the overlap/no-progress guards
  (L29/L32/L36/L53), and `MIN_PLAYER_DISTANCE=0.35` (L17); `ThreatEngine` never bypasses
  `wouldOverlap`/`findSafePosition`.
* `ActionEngine` carry/shot/pass and `PlaymakingDecisionEngine.decide()` (L66) are untouched; the
  carrier's **decision type** is never changed — only a PASS/THRU receiver may be redirected to a
  legal on-side team-mate, or a VIOLATION may skip execution.

---

## 21. Final behavioral priority

```text
                    PLAYER
                       │
             ┌─────────▼─────────┐
             │ is offside?       │   (ThreatEngine.isOffside, L207)
             └─────────┬─────────┘
                       │ YES
                       ▼
                  RETREAT TO SAFE ROW
                       │   (offsideRetreatTarget, L252)
                       │
                       NO
                       ▼
          ┌──────────────────────────┐
          │ defensive threat nearby? │   (pressTarget, L94)
          └────────────┬─────────────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
       TYPE A        TYPE B       TYPE C
     defensive      ball carrier    local
       threat        isolated      proximity
          │            │            │
          └────────────┼────────────┘
                       ▼
                CLAIM ONE THREAT   (§4, isClosestOurPlayerTo)
                       │
                       ▼
                MOVE TOWARD O
                       │  (<= 1 cell/tick via oneCellToward)
                       ▼
                 NORMAL TACTICS
```

For passing:

```text
PASS DECISION
     │
     ▼
INTENDED RECEIVER
     │
     ├── legal receiver exists ───→ PASS_LEGAL (swap target)
     │
     └── receiver offside, no legal ─→ VIOLATION (opponent restart @ exact spot)
```

### Key rules, stripped to the essentials

```text
A: opponent in our first 3 defensive rows
   + isolated from our team within 1.0
   + our defender within 1.5
   → press him
   → only ONE defender

B: opponent has ball
   + isolated from our team within 1.0
   + our defender within 1.0
   → go into duel

C: opponent within 1.0 of our player
   → leave normal tactical target, approach opponent   (NARROW: <= 1.0, 0.5 duel floor)

OFFSIDE POSITION:
   our player in opponent's half, ahead of ball, fewer than 2 opponents between
   → retreat to safe row (priority over A/B/C)

OFFSIDE RECEIPT:
   offside player receives a pass
   → stop action
   → opponent restart
   → restart at the exact receiving position

PASS TO OFFSIDE RECEIVER:
   → legal receiver first (PASS_LEGAL)
   → otherwise VIOLATION (opponent restart @ exact spot)
   (poor playmaking can still slip through if offside is so small the passer/keeper is unsure)
```

> **Emphasis (user):** Type A's 1.5-cell radius in the defensive third is **intentional** (wider
> reaction, distinct from C's 1.0). Type C is **strictly local `distance <= 1.0`** with the 0.5
> duel-radius floor — do **not** turn C into a catch-all that sweeps half the pitch. Offside is a
> **safety** layer, not a playmaking redesign.

---

## A — Resolved ambiguities / known edge cases

* **A1 — `P` excluded from its own Type-A/Type-B isolation test:** `isolated(o)` counts team-mates
  with `t != o`; the evaluating defender `P` is naturally excluded.
* **A2 — inclusive distances:** isolation `≤ 1.0`; press bands `[0.5, 1.0]` / `[0.5, 1.5]` inclusive.
  The 0.5 floor = `DuelEngine` radius, so C/B/A never double-claim a near-opponent.
* **A3 — ball mid-flight:** while `target != null, carrier == null`, §6/§8 (positional) and §10
  retreat still fire; §7 (carrier) does not (no carrier).
* **A4 — two threats for one defender:** `pressTarget` returns the **first** matching type in
  priority order (offside > A > B > C); one override per defender per tick.
* **A5 — offside retreat never overshoots:** `clamp17` (L273) bounds `safeRow` to `[1,7]`;
  row-axis only.
* **A6 — keeper as attacker near own goal:** keeper counts as a defender in
  `countOpponentsBetween` (L233); beating both GK + 1 → offside.
* **A7 — borderline offside:** `isOffside` flags only clear FIFA violations; a receiver
  fractionally on-side passes the check → pass proceeds. Intentional (§11).
* **A8 — keeper as attacker / own-goal edge:** near the *attacking* goal the keeper rarely
  advances, so this is mostly theoretical; if it occurs the keeper is a normal opponent counted
  in `countOpponentsBetween`.
* **A9 — offside during a restart hold:** overrides do not fire while `pendingRestartPosition` is
  set (`step()` returns at the L182/L218 guard before the threat loops); offside state is not
  recomputed on a frozen ball.

---

## B — Implementation anchor map (grep-authoritative, current on-disk)

File sizes (`wc -l`): `ThreatEngine`=463, `TacticalIntentEngine`=85, `SimulationStepEngine`=269,
`SimulationEngine`=858, `DemoSimulationFactory`=51, `Player`=168, `SimulationState`=693,
`PlayerSelectionEngine`=218, `MovementEngine`=273, `DecisionType`=36, `DecisionOption`=86,
`Position`=53, `Ball`=70.

| Rule element | File | Lines |
|---|---|---|
| §0 layer surface (two scopes) | `ThreatEngine.java` | class L40; `noop` L66; `isEnabled` L70 |
| §4 stateless one-defender-per-threat dedup | `ThreatEngine.java` | `isClosestOurPlayerTo` L394 |
| §6 Type A (≤1.5, defensive third, isolated) | `ThreatEngine.java` | `nearestTypeA` L317; `inDefensiveThird` L365; `isolated` L374; `teammateCountWithin` L378 |
| §7 Type B (≤1.0, carrier, isolated) | `ThreatEngine.java` | `nearestTypeB` L335; `isolated` L374 |
| §8 Type C (NARROW ≤1.0, 0.5 floor) | `ThreatEngine.java` | `nearestTypeC` L352 (band `[0.5, 1.0]` via `PRESS_FLOOR` L43 / `PRESS_C_MAX` L46) |
| §10/§11 offside position (FIFA 2nd-to-last) | `ThreatEngine.java` | `isOffside` L207; `countOpponentsBetween` L233 |
| §12 offside retreat | `ThreatEngine.java` | `offsideRetreatTarget` L252; `clamp17` L273; `rowOf` L277; `opponentsOf` L410 |
| §13/§15 violation → opponent restart | `ThreatEngine.java` | `setupOffsideViolation` L431 (sets `pendingRestartPosition` L418 / `pendingRestartPlayer` L420 / `setRestartPassToHomeGoalkeeper` L422 / `setRestartHoldTicks(60)` L423) |
| §16/§17 carrier pass safety (PASS_LEGAL/VIOLATION) | `ThreatEngine.java` | `overrideCarrierPass` L147; `legalReceiver` L450; `DecisionOption(PASS,legal,…)` ctor L39 |
| §18 PassResult API | `ThreatEngine.java` | `PassResult` + `Kind` L175–191 (`KEEP` L186, `VIOLATION` L187, `legal(…)` L189) |
| §19 logging (exact tokens) | `ThreatEngine.java` | `logThreatDetected` L290; `logOffsideRetreat` L299; `fmtPos` L281 |
| constants | `ThreatEngine.java` | `PRESS_FLOOR`=0.5 L43, `PRESS_A_MAX`=1.5 L44, `PRESS_B_MAX`=1.0 L45, `PRESS_C_MAX`=1.0 L46, `ISOLATION_RADIUS`=1.0 L47, `OFFSIDE_HOLD_TICKS`=60 L48 |
| `pressTarget` consult (both loops) | `TacticalIntentEngine.java` | field `threatEngine` L18; `getThreatEngine` L30; 1-arg ctor L20 (→ `noop`), 2-arg ctor L24; `assignTargets` L25 (guard carrier/locked L42; returning/active-chaser skip L45; `desiredCell` L46; `pressTarget(p, desired)` L48; `movementTarget` L49; `setTacticalDesiredPosition` L50; `oneCellToward` L51); `refreshTargetsIfBallStateChanged` L59 (guards L69/L72; `desiredCell` L73; `pressTarget(p, desired)` L75; `movementTarget` L76; `set` L77; `oneCellToward` L78); `isActiveChase` L82 |
| carrier-pass intercept (decide → override → execute) | `SimulationStepEngine.java` | `step()` L36; kickoff/loose-ball branch L68; `assignTargets` calls L137 / L167 / L231; loose-ball re-acquire L143; `isAwayRestartPending` branch L182; kickoff check L207; **decide L214 → `getThreatEngine` L215 → `overrideCarrierPass` L217 → VIOLATION early-return L218–223 → `PASS_LEGAL` swap L225–226 → `executeDecisionOption` L229 → `assignTargets` L231 → `recordDesiredPositions` L232 → `incrementRound` L233**; `executeDecisionOption` L244 |
| opt-in ctor (5-arg) vs no-op (3/4-arg) | `SimulationEngine.java` | `tacticalIntentEngine` field L47, `playerSelectionEngine` L48; 3-arg ctor L54; 4-arg ctor L58 (→ 5-arg `false`); **5-arg ctor L69** (param `threatOverride` L70; ThreatEngine build L78–80); `advanceInternal` L132; restart-hold consume L156–162; restart resolve L164–173 (`startRestart` L173); `startRestart` method L560; `setRestartHoldTicks(60)` (existing throw-in) L612 |
| opt-in factory + demo activation | `DemoSimulationFactory.java` | `create` L29/L34 (no-op); `createWithThreatOverride` L41/L46 (`new SimulationEngine(…, new java.util.Random(), true)` L49); `TacticalGridDemo.java` L60 |
| `Player.offside` state | `Player.java` | field L31; `isOffside()` L118; `setOffside(boolean)` L122; `getPosition` L87 |
| restart-state fields | `SimulationState.java` | `TEAM_HOME="HOME"` L43; `pendingRestartPosition` L84 / get L422 / set L423; `pendingRestartPlayer` L85 / get L424 / set L425; `setRestartPassToHomeGoalkeeper` L427; `restartHoldTicks` L90 / get L433 / set L434 / `consumeRestartHoldTick` L435; `log` L473; `getPlayers` L140; `getBall` L144; `getCarrier` L234 |
| nearest-opponent selection | `PlayerSelectionEngine.java` | `closestTeamTo(Position,String)` L31; `closestTeamTo(Position,String,Player)` L35; `closestHomeTo` L27; `closestAwayTo` L129 |
| Distance / one-cell | `MovementEngine.java` | `oneCellToward` L113; `distance` L264 |
| Decision type enum | `DecisionType.java` | `PASS, THRU, CARRY, CLEAR, SHOT, CROSS, CENTER` L23–29 |
| Ball carrier / state | `Ball.java` | `getPosition` L33; `getCarrier` L56; `getBallState` L65 |

---

## C — Phase 1 checklist

- [x] `ThreatEngine` added (`ThreatEngine.java`) — `pressTarget(p, normalCell)` (offside-retreat→A→B→C), `overrideCarrierPass` (`KEEP`/`PASS_LEGAL`/`VIOLATION`), `isOffside` (FIFA 2nd-to-last, team-relative), `offsideRetreatTarget` (2nd-to-last ±1, row-only, clamped), `legalReceiver` (closest on-side team-mate), `PASS_C_MAX=1.0` (Type C NARROW).
- [x] `Player.offside` field + `isOffside()`/`setOffside(boolean)` accessors.
- [x] Wired into `TacticalIntentEngine` (2-arg ctor + `pressTarget(p, desired)` in both loops + `getThreatEngine`), after carrier/locked/returning/active-chaser skip-guards.
- [x] Carrier-pass intercept in `SimulationStepEngine.step()` between `decide()` (L214) and `executeDecisionOption` (L229); VIOLATION early-returns, PASS_LEGAL swaps the receiver.
- [x] Opt-in `SimulationEngine` 5-arg ctor (`threatOverride` flag); 3/4-arg ctors stay no-op.
- [x] `DemoSimulationFactory.createWithThreatOverride(...)`; `TacticalGridDemo` (L60) opts in.
- [x] §19 logging tokens emitted verbatim (`THREAT DETECTED | …`, `OFFSIDE DETECTED | … | ACTION=RETREAT`, `OFFSIDE VIOLATION | … | RESTART=OPPONENT`, `OFFSIDE SAFETY | PASS_TO_LEGAL | …`).
- [x] `mvn -o test-compile` clean.
- [x] 8 existing demo tests green (layer OFF in all: `PlaymakingDecisionLayerTest`,
  `SimulationArchitectureTest`, `ChaseDeadlockTest`, `ChaseDeadlockDiagnosticsTest`,
  `DuelResolutionTest`, `DemoArchitectureTest`, `ExecutionQualityTest`,
  `TacticalPerspectiveTransformerTest`). **No new tests, no existing tests modified.**
- [x] §15: match clock (`[mm:ss]` via `SimulationState.matchClockLabel()`, L340) injected into **all** log lines in `SimulationState.log` (L473); removed the redundant manual `time=` from the `ActionEngine` CHASE TICK log.
- [x] Smoke run (headless harness, seeds 1/2/3/4/7/47/99/123/777): every match `finished=true`, **0** "restart waiting" hangs, 0 exceptions; on seed-7 the layer logged `7134 THREAT DETECTED` (`2093 TYPE-A / 183 TYPE-B / 4858 TYPE-C`) + `111 OFFSIDE DETECTED … RETREAT`; the `VIOLATION`/`PASS_TO_LEGAL` carrier-pass branch is wired+compiled but not tripped in natural play (the per-tick retreat keeps receivers on-side before a pass lands) — it reuses the throw-in-tested restart resolver (`advanceInternal` L156–173), so it is hang-safe by construction.**
- [x] **§22 addendum (2026-08-19) — 4 post-spec directives, implemented & verified (threat layer stays default-OFF; 8 demo tests unchanged/grey-box OFF).**
  - **(a) 3-round override cap.** A `pressTarget` override (Type A/B) now persists at most **3 consecutive rounds**, then falls back to the normal tactical target. Per-player, keyed on `state.getRound()`. `TacticalIntentEngine.applyThreatCap` (`threatOverrideCount`/`threatOverrideLastRound`) L116–128: increments once per round; `> 3` ⇒ returns `null` (revert); `press == null` clears both maps so re-engagement requires the threat to genuinely clear. Verified via micro-harness: defender D pressed at opponent O rounds 0/1/2 (`tacticalTarget=(2.60,3.50)`), reverted to tactical `(1.00,3.50)` on rounds 3 & 4.
  - **(b) Keeper chase rule.** Goalkeeper leaves the goal line **only if** it is strictly the closest team-mate to the ball (excluding itself): `ThreatEngine.pressTarget` GK guard uses `selection.closestTeamTo(ballPos, team, p)` L122 (excludes GK via the `p` self-exclusion + skips locked/blocked) — if a team-mate is as-close-or-closer, `pressTarget` returns `null` for the GK. "Clear on pickup + return to own goal line" is the **pre-existing** `ActionEngine.executeCarry` behaviour (L479: `if ("GK".equals(carrier.getRole())) executeClearance(); return;`) plus the GK anchor `goalPositionFor`/`goalExitPositionFor`. Verified: threat-ON smoke keepers stay `row 1` (home) / `row 7` (away); no hang.
  - **(c) Long-ball transit reactivity (root-cause + fix).** Symptom: during a long pass/chase crossing 2+ rows, non-carriers froze at their round-start tactical cell and did **not** react to the ball's new position. Root cause: `TacticalIntentEngine.refreshTargetsIfBallStateChanged` (L73) compares `TacticsRules.ballStateKey` (TacticsRules L143 — quantizes `Math.round(row)-1, Math.round(col)-1` → `CELL_r_c`); a 4-row pass at `BALL_SPEED=0.037`/tick (~82 ticks) only flips the cell key 4 times, so the method `early-returns` on 78 of 82 flight ticks. Secondary factor: pass-flight receivers are `locked=true` (`ActionEngine.executePass`/`executeThruPass`/`executeCross`, L1094/1050/1223) — excluded by the skip-guard `p.isLocked()`. Fix: added an **in-transit relaxation** — when the ball is `IN_TRANSITION` (`Ball.BallState` L65) **and** the threat layer is enabled, re-evaluate non-carriers every tick (`inTransit` flag L77; guard L86: `!cellChanged && !(inTransit && threatEngine.isEnabled())` ⇒ proceed). The relaxation is gated on `threatEngine.isEnabled()` so the threat-OFF path is byte-for-byte unchanged. Verified: threat-ON smoke TYPE-A log count rose 2093 → 7905 (per-tick during flight), both seeds finish, 0 exceptions, 0 hangs. (Defensive players cannot physically step up to a midfield long ball — that is a `TacticsRules` DB-anchored `desiredCell` gap: `desiredCell("HDCR", ball@row4) == desiredCell("HDCR", ball@row7) == (1.00,3.50)`; out of this layer's scope, see §A note.)
  - **(d) Type C disable (toggle, not delete).** `ThreatEngine` gained `typeCEnabled` (default `false`) + `setTypeCEnabled(boolean)` L61/L84; the Type-C branch is gated at L148 (`if (typeCEnabled)`) with `nearestTypeC` L386 kept fully intact (disabled, not removed). Exposed through `SimulationEngine.setThreatTypeCEnabled(boolean)` L90 → `tacticalIntentEngine.getThreatEngine().setTypeCEnabled(...)`. Verified: threat-ON smoke (`grep -c "TYPE=C" = 0` for seeds 7 & 47). NOTE: Type C is off **by default** (not wired to threat-ON); it is a per-tick opt-in only via `setThreatTypeCEnabled(true)` and is **not** enabled by `DemoSimulationFactory.createWithThreatOverride` (A/B + offside are the live set, matching the user's 3 rules).

**Current on-disk anchors (grep-authoritative; §B line numbers above are unchanged — this addendum appends after L572):**
- `ThreatEngine.java`: `typeCEnabled` L61; `setTypeCEnabled` L84; GK guard `closestTeamTo(ballPos,team,p)` L122 (excludes GK); Type-C gate L148; `nearestTypeC` L386.
- `TacticalIntentEngine.java`: `threatOverrideCount`/`threatOverrideLastRound` maps L30/L31; `refreshTargetsIfBallStateChanged` L73 (`cellChanged` L74, `inTransit` L77, gate L86, `pressTarget`+`applyThreatCap` L98); `applyThreatCap` L116 (cap L128).
- `SimulationEngine.java`: `setThreatTypeCEnabled` L90 → `getThreatEngine().setTypeCEnabled` L91.

---

## 22. Post-spec addendum (2026-08-19)

Four behavioural directives layered **on top of** the Phase 1 threat+offside layer (§0–§21). The layer remains **default-OFF**; `DemoSimulationFactory.createWithThreatOverride` enables **Types A/B + offside** and, additionally, these four controls:

### 22.1 Type A override: hard 3-round lifetime (`§22.1`)

A defensive-third isolated opponent (§6) assigned to a defender via `pressTarget` is honoured for **at most 3 consecutive rounds** (a "round" = one `step()` cycle; `state.getRound()`, `SimulationState` L292). On the 4th consecutive round the override is dropped and the defender reverts to its normal tactical `desiredCell` for the remainder of that tick; the player may re-engage the same threat on a later round once the threat is still present (re-arm requires a fresh `pressTarget != null` after the maps were cleared).

Semantics (`TacticalIntentEngine.applyThreatCap`, L116–128):
```java
// keyed by Player p, round = state.getRound()
int round = state.getRound();
if (round > threatOverrideLastRound.getOrDefault(p, -1)) {   // new round → tick up
    int count = threatOverrideCount.getOrDefault(p, 0) + 1;
    threatOverrideCount.put(p, count);
    threatOverrideLastRound.put(p, round);
}
if (threatOverrideCount.getOrDefault(p, 0) > 3) {           // 4th round → revert
    threatOverrideCount.remove(p);
    threatOverrideLastRound.remove(p);
    return null;                                            // → normal tactical target
}
return press;                                               // honour override (≤3 rounds)
```
One override per defender per tick; §4 one-defender-per-threat dedup is unchanged.

### 22.2 Keeper chase rule (`§22.2`)

The goalkeeper (`role == "GK"`) must **stay on its goal line** unless it is unambiguously the closest team-mate to the ball. In `ThreatEngine.pressTarget`:
```java
// L122 — GK guard, evaluated before A/B/C
if ("GK".equals(p.getRole())) {
    Player closer = selection.closestTeamTo(ballPos, p.getTeam(), p); // excludes p (GK) itself
    if (closer != null) return null;   // a team-mate is as-close-or-closer → keeper stays home
}
```
`closestTeamTo(Position,String,Player)` (PlayerSelectionEngine L35) skips the excluded player and any `isLocked()`/`isBlocked()` player, so it returns the nearest *field* team-mate to the ball. If the keeper is genuinely the closest, it proceeds through A/B/C as normal (typically a Type-B carrier duel at the goal line). The "clear + return" consequence is the **existing** `ActionEngine` behaviour: `executeCarry` L479 (`if ("GK".equals(carrier.getRole())) executeClearance(); return;`) plus the GK anchor helpers `goalPositionFor`/`goalExitPositionFor`; `ThreatEngine` itself only *directs* the keeper, it never executes clearances.

### 22.3 Long-ball transit reactivity (`§22.3`)

Non-carriers must continue to re-aim at the tactical/imminent ball position **during** a PASS/SHOT/CHASE transit, not freeze for the duration. Root cause of the freeze (`TacticalIntentEngine.refreshTargetsIfBallStateChanged`, L73–86):
```java
String currentKey = TacticsRules.ballStateKey(state.getBall().getPosition()); // quantised CELL_r_c
// for a 4-row pass (~82 ticks @ 0.037/tick) the key flips only ~4 times → 78 ticks early-return
boolean cellChanged = !currentKey.equals(state.getLastTacticalBallStateKey());
boolean inTransit = state.getBall().getBallState() == Ball.BallState.IN_TRANSITION;
if (!cellChanged && !(inTransit && threatEngine.isEnabled())) return;   // L86 — relaxation gate
```
Fix: when the ball is `IN_TRANSITION` and the threat layer is enabled, force a refresh regardless of whether the quantised cell key changed (`inTransit` L77, gate L86). Pass-flight receivers remain excluded (`p.isLocked()` skip, unchanged). Because the relaxation is gated on `threatEngine.isEnabled()`, threat-OFF matches (all 8 unit tests + default gameplay) are unaffected.

### 22.4 Type C disable — toggle, not deletion (`§22.4`)

`ThreatEngine.typeCEnabled` (`boolean`, default `false`) + `setTypeCEnabled` L84, with the Type-C branch gated at L148:
```java
if (typeCEnabled) {
    Player threatC = nearestTypeC(p);          // L149 — NARROW local proximity, §8
    ...
}
// nearestTypeC (L386) kept intact — DISABLED, not deleted
```
Exposed to the demo harness via `SimulationEngine.setThreatTypeCEnabled(boolean)` L90. Type C is **off by default** and is **not** turned on by `DemoSimulationFactory.createWithThreatOverride`; it is a deliberate escape-hatch toggle reserved for per-playtesting, so the default live rule set is Types A/B + offside (the user's 3 rules). When off, `nearestTypeC`'s logic is preserved byte-for-byte for re-enablement.
