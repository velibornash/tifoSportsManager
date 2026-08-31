# Football Simulation Engine

## System Architecture & Source of Truth

**Document Type:** System Architecture & Domain Specification
**Status:** Authoritative Design Specification
**Audience:** System Architects, Simulation Engineers, Football Analysts, AI/Decision Engineers, QA Engineers
**Scope:** Match simulation engine, tactical model, player decision-making, spatial behavior, football rules, event resolution, randomness, observability and service-oriented execution

---

# 1. Purpose

This document defines the authoritative design of the football simulation engine.

The simulator is intended to model a football match as a **stateful, continuous decision-and-event system** in which:

* players operate within tactical structures;
* tactical instructions establish intended behavior rather than scripted movement;
* players perceive the match from their own contextual perspective;
* players evaluate available actions;
* football rules constrain and resolve those actions;
* physical, technical, tactical and mental attributes influence outcomes;
* contextual threat can override normal tactical behavior;
* small controlled randomness prevents identical situations from producing mechanically identical outcomes;
* the complete simulation state remains observable and reproducible.

The objective is **credible football behavior**, not merely visually plausible player movement.

The engine must therefore model the relationship between:

> **Tactics → Situation → Perception → Decision → Action → Interaction → Rules → New Match State**

---

# 2. Core Design Principles

## 2.0 Football Authenticity Over Statistical Targets

**The primary goal is that football actions are justified and realistic — not that statistics match exact targets.**

Goals up to 5.5/match are acceptable. Corners, offsides, shots — if they result from sound football logic, they are correct even if slightly above real-world averages. What matters is that every action (shot, pass, cross, tackle, offside call) is **football-justified** — a player shoots because they are in a good position, not because the engine forces a random shot. A corner happens because the ball was deflected over the line by a defender under pressure, not because a counter says "we need more corners."

> **TL;DR: Football-justified actions > statistical accuracy.**

## 2.1 The simulator is state-first

The authoritative source of truth is the current match state.

No UI, tactical editor, commentary layer or visualization may independently maintain a competing version of football reality.

```text
Clients / UI / Tactical Editor
            ↓
      Simulation API
            ↓
     Simulation Services
            ↓
   AUTHORITATIVE MATCH STATE
```

---

## 2.2 Tactical instructions are intentions, not scripts

A tactical instruction describes what a player or team should normally attempt to do.

It does not guarantee that the player will do it.

For example:

> "Stay wide."

does not mean:

```text
player.x = widePosition
```

It means:

> Under normal circumstances, the player's tactical intent favors maintaining width.

If an opponent enters a dangerous zone, the ball becomes immediately threatening, or another tactical responsibility becomes more urgent, the player's behavior may legitimately deviate.

---

## 2.3 Football context has priority over rigid positioning

The simulator must distinguish between:

* tactical responsibility;
* current tactical intent;
* immediate football situation;
* threat;
* player capability;
* available actions;
* actual action.

A player may therefore abandon a nominal tactical position because the football situation requires it.

This is a fundamental principle, not an exception.

---

## 2.4 Pitch orientation and coordinate system

The playable pitch is a **continuous** grid (manufactured coordinates are used
directly, never cell-centre snapping):

* **rows 1–7** are the playing field. Each row covers a `[n.00, n+1.00)` range
  with cell **centre** at `n + 0.5` (e.g. row 7 spans `[7.00, 7.99]`, centre
  `7.5`). The row axis is the primary forward/backward axis;
* **columns 1–6** are the playing width. Each column covers a `[m.00, m+1.00)`
  range with cell centre at `m + 0.5` (e.g. col 6 spans `[6.00, 6.99]`, centre
  `6.5`).

**Out-of-bounds zones (explicit):**

* **row ≤ 0.99** is the OOB zone **behind the HOME goal** (corner flags etc.);
* **row ≥ 8.01** is the OOB zone **behind the AWAY goal**;
* **column ≤ 0.99** is OOB beside the **left touchline** (from HOME's perspective);
* **column ≥ 7.01** is OOB beside the **right touchline**.

**Goal lines and goal mouth:**

* HOME goal line at **row 1.0**, AWAY goal line at **row 8.0**.
* The goal mouth itself is the 1-cell column span centred on **column 3.5**
  (i.e. valid goal-column range is col 3.0–4.0 — the goal is 1 cell ≈ 10 m wide).
* The goal is centred at `column 3.5` for both teams; the home goal mouth
  covers col 3.0–4.0 at row 1.0, the away goal mouth covers col 3.0–4.0 at row 8.0.

**Real-world scale:** the playable grid is **7 rows × 6 columns** and maps to a
pitch of **98 metres × 60 metres**, so **each cell is a 14 m × 10 m rectangle**
(14 m along a row step, 10 m along a column step). Because a cell is ~14 m deep,
*everything* is decided on the exact floating-point position of the ball/shooter/
keeper/offside line at that moment — a whole cell is far too coarse for shot
angles, keeper footwork or offside margins.

**Exact-coordinate rule:** every simulation calculation (shot goal/save, keeper
positioning, duel geometry, offside margin, corner/throw-in/restart) must use the
current precise `Position` of the entities involved — never the containing cell.

Team orientation is fixed for the whole match:

* **HOME team** starts on the left, defends the goal at **row 1.0**, and attacks
  toward **row 8.0**;
* **AWAY team** starts on the right, defends the goal at **row 8.0**, and attacks
  toward **row 1.0**;
* the centre spot is at **(4.0, 3.5)** — the middle of the field.

This convention is authoritative for all tactical, movement, offside, and restart calculations.  Functions named `goalPositionFor(team)` return the goal that team is **attacking** (row 8.0 for HOME, row 1.0 for AWAY), not the goal it defends. A goalkeeper defends the goal that its own team defends: HOME's goalkeeper defends **(1.0, 3.5)**, AWAY's goalkeeper defends **(8.0, 3.5)**.

---

# 3. Simulation Model

The simulator operates as a repeated decision-resolution cycle.

At a high level:

```text
CURRENT MATCH STATE
        ↓
SPATIAL / SITUATIONAL CONTEXT
        ↓
TACTICAL INTENT
        ↓
THREAT ASSESSMENT
        ↓
PLAYER PERCEPTION
        ↓
AVAILABLE ACTIONS
        ↓
ACTION EVALUATION
        ↓
ACTION SELECTION
        ↓
ACTION EXECUTION
        ↓
PLAYER / BALL INTERACTION
        ↓
FOOTBALL RULE RESOLUTION
        ↓
EVENTS / STATE TRANSITIONS
        ↓
NEW MATCH STATE
        ↓
NEXT SIMULATION STEP
```

This is a conceptual flow, not a requirement that every stage become a separate class or microservice.

---

# 4. Service-Oriented Architecture

The simulator should be organized around **clear domain responsibilities**.

Service boundaries exist to improve:

* modularity;
* testability;
* debugging;
* replaceability;
* reasoning about behavior.

They do **not** exist to maximize the number of interfaces, classes or abstractions.

Avoid unnecessary:

* dependency injection frameworks;
* generic strategy factories;
* abstraction layers with one implementation;
* microservices for trivial operations;
* domain objects created solely for architectural purity.

A small deterministic helper should remain a small deterministic helper.

---

## 4.1 Match State Service

Owns the authoritative match state.

Responsibilities include:

* current time;
* score;
* players;
* teams;
* ball;
* possession;
* active phase;
* match status;
* current events;
* relevant tactical state;
* simulation metadata.

It is the authoritative source from which other services read the current state.

---

## 4.2 Tactical Service

Converts configured team and player tactics into contextual tactical intent.

Responsibilities:

* formation;
* roles;
* player responsibilities;
* team shape;
* attacking structure;
* defensive structure;
* transition behavior;
* pressing behavior;
* width;
* defensive line;
* marking responsibilities;
* player instructions.

The Tactical Service does **not** directly move players.

It establishes desired behavior.

---

## 4.3 Spatial / Positioning Service

Determines spatial relationships and movement targets.

Responsibilities include:

* tactical positions;
* zones;
* distances;
* space occupation;
* relative player positions;
* support positions;
* defensive coverage;
* movement targets.

It must distinguish:

```text
desired tactical position
target position
current position
```

These are not necessarily identical.

---

## 4.4 Threat Assessment

Determines whether the current situation creates a threat significant enough to influence normal behavior.

Threat may depend on:

* ball position;
* ball carrier;
* opponent position;
* opponent movement;
* open space;
* passing lanes;
* defensive structure;
* goal proximity;
* numerical superiority;
* player proximity;
* tactical responsibility;
* urgency.

Threat is contextual and dynamic.

A threat can override normal tactical positioning when its urgency exceeds the player's current tactical responsibility.

### Threat Override — `applyThreatOverride` in `TacticalIntentEngine`

When a defender's normal tactical position would leave a dangerous opponent
free, the threat override pulls the defender toward that opponent. Two
overrides fire in priority order, with a **resolver** so only ONE defender
claims each threat (no 3-player swarm):

* **TYPE A — carrier in our proximity**: the ball carrier is within **1.0
  cell** (~14 m) of this defender AND this defender can press them.
  Approach the carrier wherever they are on the pitch (not just in our
  half). Priority 1.
* **TYPE B — isolated opponent in our defensive third**: an opponent is in
  our defensive third AND no other defender is within **0.5 cells** of them.
  Press them to close the space. Priority 2.

```
TYPE A   = (carrier is opponent) AND distance(defender, carrier) ≤ 1.0
TYPE B   = isDefensiveThird(opponent, us) AND
           no other defender within 0.5 cells AND
           distance(defender, opponent) ≤ 1.5
priority = A=1, B=2, neither=∞
target   = threat with best priority, ties broken by distance
claim    = isClosestEligibleDefender(threat, candidate) — only the
           closest eligible defender (CB/DEF/LB/RB/DM, not locked/sent-off/
           injured/not carrier/not active-chaser) becomes the new target
```

Only **defenders** contest threats — non-defender outfield players keep their
tactical position. Otherwise three players (a CB, a DM, and a winger all
nearby) would all rush to the ball carrier at once.

**Pressing speed:** a defender whose target moved due to the threat override
(field `Player.threatOverrideActive`) moves at **1.6x** normal speed toward
the carrier. Carrier and defender otherwise share the same 0.25-cells/tick
speed, so a defender would chase from behind forever. The boost lets the
defender close a 1.0-cell gap in ~20 ticks (~10 s) and reach DRIBBLE duel
range (0.15 cells) before the carrier can carry far.

---

## 4.5 Player Perception / Awareness

Players must not automatically possess perfect knowledge of the entire simulation state.

The engine knows the complete state.

A player operates on a contextual subset influenced by:

* distance;
* orientation;
* visibility;
* proximity;
* ball awareness;
* opponent awareness;
* teammate awareness;
* anticipation;
* positioning;
* tactical awareness.

This allows players to make imperfect but believable decisions.

---

# 5. Tactical Intent

Tactical intent is the bridge between the Tactical Editor and the simulation engine.

The editor produces configuration such as:

```text
formation
role
position
zone
pressing
marking
width
tempo
passing style
transition behavior
player instructions
```

The simulator transforms that configuration into current intent.

Example:

```text
TACTICAL INSTRUCTION
    "Hold width"
          ↓
CURRENT TACTICAL INTENT
    "Maintain wide position"
          ↓
CURRENT SITUATION
    Dangerous opponent enters central channel
          ↓
THREAT OVERRIDE
    "Move inside to provide defensive support"
```

The player has not "ignored" the tactic.

The player has interpreted the tactic in the context of football reality.

---

# 6. Threat Override

Threat override is a first-class simulation mechanism.

It allows immediate football circumstances to temporarily supersede lower-priority tactical intent.

Conceptually:

```text
Normal Tactical Intent
        +
Current Situation
        +
Threat
        +
Urgency
        ↓
Final Current Intent
```

Threat override must not simply mean:

```text
if threat:
    chase threat
```

Instead, the system evaluates:

* threat severity;
* distance;
* urgency;
* player's responsibility;
* current position;
* available teammates;
* opponent danger;
* tactical consequences of responding.

A player should not abandon his entire role because an opponent exists somewhere on the pitch.

The override must be **proportional to the threat**.

---

# 7. Player Decision Model

A player should never simply execute a hard-coded action based on one condition.

The player first determines what actions are realistically available.

Possible actions include:

```text
MOVE
HOLD_POSITION
PRESS
TRACK
MARK
TACKLE
INTERCEPT
CLEAR
PASS
THROUGH_PASS
CROSS
DRIBBLE
SHOOT
RECEIVE
HEADER
TURN
BLOCK
```

The actual action set depends on the player's state and situation.

For example, a defender without possession may have:

```text
HOLD_POSITION
MOVE
PRESS
TRACK
MARK
INTERCEPT
TACKLE
```

A ball carrier may have:

```text
PASS
DRIBBLE
SHOOT
CROSS
HOLD
TURN
```

---

# 8. Action Evaluation

Each candidate action is evaluated against the current context.

Relevant factors may include:

* tactical intent;
* threat;
* player attributes;
* opponent pressure;
* teammate availability;
* space;
* passing lanes;
* distance;
* angle;
* goal proximity;
* risk;
* reward;
* current match state;
* game situation;
* player mentality;
* fatigue;
* confidence;
* urgency.

The simulator should not use a single universal formula for every football decision.

Different decisions may require different domain-specific evaluation logic.

The objective is **football reasoning**, not mathematical abstraction for its own sake.

---

# 9. Controlled Randomness

## 9.1 Randomness is fundamental

Football contains uncertainty.

Two players in the same nominal situation should not necessarily:

* choose the exact same action;
* pass with identical accuracy;
* control the ball identically;
* make the same tackle;
* shoot identically;
* react at exactly the same moment.

Therefore:

> **Wherever the simulator calculates a meaningful probabilistic or contestable football outcome, a small controlled random component should normally exist.**

This is not noise for its own sake.

It represents uncertainty and natural variation.

---

## 9.2 Randomness must be controlled

Randomness must never destroy the influence of football logic.

Bad model:

```text
random() > 0.5
    → pass
else
    → shoot
```

Better model:

```text
football evaluation
        ↓
probability distribution
        ↓
small controlled random variation
        ↓
selected outcome
```

Attributes, tactics, situation and context establish the probability.

Randomness selects among plausible outcomes.

---

## 9.3 Examples

A technically excellent passer under low pressure:

```text
95% expected success
+
small random variation
```

should usually succeed.

But occasional imperfect execution remains possible.

A poor passer under extreme pressure:

```text
40% expected success
+
small random variation
```

should fail substantially more often.

Randomness must **modify probability, not replace causality**.

---

## 9.4 Randomness in decision making

Randomness may influence close decisions.

Example:

```text
PASS: 0.61
DRIBBLE: 0.58
SHOOT: 0.31
```

The difference between PASS and DRIBBLE is small.

A small random factor can legitimately make either one win.

But:

```text
PASS: 0.91
SHOOT: 0.12
```

should not regularly result in SHOOT.

The closer two decisions are, the more meaningful small randomness becomes.

---

## 9.5 Randomness in execution

Randomness should also appear in execution:

* pass accuracy;
* first touch;
* shot placement;
* tackle timing;
* interception success;
* heading;
* goalkeeper saves;
* deflections;
* rebounds;
* acceleration response;
* reaction timing.

The exact implementation may vary by action.

---

# 10. Seeded Randomness and Reproducibility

Randomness must be **seeded and reproducible**.

A simulation should be reproducible when supplied with:

```text
same initial match state
+
same teams
+
same tactics
+
same player data
+
same simulation configuration
+
same random seed
```

This should produce the same simulation sequence.

This is essential for:

* debugging;
* regression testing;
* replay;
* bug reproduction;
* comparing engine versions;
* investigating unexpected behavior.

Randomness therefore becomes:

> **controlled uncertainty, not uncontrolled nondeterminism.**

The seed must be part of simulation metadata.

---

# 11. Movement Model

Movement is the result of intent and spatial context.

The engine should distinguish:

```text
TACTICAL POSITION
      ↓
CURRENT INTENT
      ↓
MOVEMENT TARGET
      ↓
ACTUAL MOVEMENT
```

Actual movement may be influenced by:

* speed;
* acceleration;
* direction;
* turning;
* pressure;
* fatigue;
* proximity to other players;
* ball trajectory;
* threat;
* collisions;
* reaction time.

Movement should not teleport a player directly to the tactical target.

---

# 12. Ball Model

The ball is an independent simulation entity.

Its state may include:

```text
position
velocity
direction
height
owner
target
trajectory
phase
```

Ball phases may include:

```text
CONTROLLED
IN_PLAY
IN_FLIGHT
DEFLECTED
FREE
OUT_OF_PLAY
```

Actions such as passes, shots, crosses, headers and clearances modify ball state.

The ball's state then becomes input for subsequent player decisions.

---

# 13. Player-Ball Interaction

The intention to perform an action is distinct from its result.

Example:

```text
Player chooses PASS
        ↓
Pass execution
        ↓
Ball trajectory
        ↓
Receiver / opponent interaction
        ↓
Result
```

The same separation applies to:

* shooting;
* tackling;
* interception;
* heading;
* receiving;
* crossing;
* blocking;
* goalkeeper saves.

---

# 14. Player-Player Interaction

Player interactions must be resolved independently from decision selection.

Examples:

```text
Tackle
Interception
Physical duel
Header duel
Block
Collision
Pressing interaction
Marking interaction
```

A player may decide:

> "Attempt tackle."

The interaction resolver determines:

> Was the tackle successful?

The outcome depends on:

* player attributes;
* relative movement;
* timing;
* ball position;
* opponent state;
* pressure;
* technique;
* contextual randomness.

---

# 15. Football Rules

Football rules are authoritative over player intentions.

A player may attempt an action that becomes illegal or invalid because of the rules.

Rules include:

* offside;
* fouls;
* advantage;
* penalties;
* free kicks;
* throw-ins;
* corners;
* goal kicks;
* goals;
* cards;
* substitutions;
* injuries;
* match termination.

Rules should not be embedded inside tactical decision logic.

---

# 16. Offside

Offside must be evaluated from the correct football moment.

For relevant attacking actions, the engine must be able to evaluate:

```text
attacker position
+
ball position
+
second-last opponent
+
relevant moment
+
attacker involvement
```

The engine must not simply ask:

> "Is the attacker currently behind the defensive line?"

The relevant timing and involvement matter.

This distinction is essential for realistic simulation.

---

# 17. Match Event Model

The simulator should distinguish:

### Intent

What the player wanted to do.

### Action

What the player attempted.

### Interaction

How the action interacted with another player or the ball.

### Rule

Whether the action is legal.

### Event

What happened as a result.

### State Transition

How the match state changed.

Example:

```text
INTENT
"Play forward"

        ↓

ACTION
PASS

        ↓

INTERACTION
Opponent attempts interception

        ↓

OUTCOME
Interception succeeds

        ↓

EVENT
POSSESSION_CHANGED

        ↓

STATE
Opponent becomes ball carrier
```

This separation is critical for debugging and replay.

---

# 18. Match State Transitions

The simulator must never modify the match state arbitrarily.

State transitions should have identifiable causes.

For example:

```text
Possession: Player A
        ↓
PASS
        ↓
INTERCEPTION
        ↓
Possession: Player B
```

The engine should be able to explain why the transition happened.

---

# 19. Simulation Tick / Step

A simulation step should approximately follow:

```text
1. Read authoritative state

2. Update spatial relationships

3. Determine tactical intent

4. Evaluate active threats

5. Apply contextual overrides

6. Determine player perception

7. Generate legal/possible actions

8. Evaluate candidate actions

9. Apply controlled randomness where appropriate

10. Select actions

11. Advance movement and/or ball state

12. Resolve player-ball interactions

13. Resolve player-player interactions

14. Resolve football rules

15. Generate events

16. Apply state transitions

17. Update statistics / telemetry

18. Produce next authoritative state
```

The actual implementation may combine some stages where doing so improves clarity and performance.

The sequence is the important part.

---

# 20. Match Phases

The simulator should recognize the current football phase.

Examples:

```text
OPEN_PLAY
ATTACK
DEFENSE
TRANSITION_TO_ATTACK
TRANSITION_TO_DEFENSE
SET_PIECE
DEAD_BALL
KICK_OFF
THROW_IN
CORNER
FREE_KICK
PENALTY
GOAL
MATCH_END
```

Player behavior depends heavily on phase.

A player's decision model during a counterattack should not be identical to his decision model during settled possession.

---

# 21. Transition Football

Transitions are particularly important.

When possession changes:

```text
OLD TEAM STATE
      ↓
POSSESSION CHANGE
      ↓
IMMEDIATE TRANSITION
      ↓
NEW TACTICAL PRIORITIES
```

Players should not instantly teleport from their previous tactical roles into their new roles.

They react to the transition.

This creates:

* counterattacks;
* defensive recovery;
* pressing;
* exposed spaces;
* temporary numerical advantages;
* tactical breakdowns.

These should emerge naturally from the simulation.

---

# 22. Team Shape

Team shape should be an emergent result of individual player positions and tactical responsibilities.

The engine may calculate:

* defensive line;
* compactness;
* width;
* spacing;
* occupied zones;
* support structure;
* pressing structure.

However, team shape must not force every player into mathematically perfect geometry.

Real football contains:

* asymmetry;
* gaps;
* delayed reactions;
* individual deviations;
* temporary overloads.

These imperfections are part of realistic behavior.

---

# 23. Player Attributes

Attributes influence behavior but should not directly dictate it.

Categories may include:

### Technical

* passing;
* first touch;
* dribbling;
* shooting;
* tackling;
* crossing;
* heading.

### Physical

* acceleration;
* speed;
* strength;
* agility;
* stamina.

### Mental

* composure;
* concentration;
* decision making;
* aggression;
* anticipation;
* positioning.

### Tactical

* tactical awareness;
* spatial awareness;
* pressing intelligence;
* defensive awareness.

Attributes should feed into probability, evaluation and execution rather than become simple binary conditions.

---

# 24. Fatigue and Match Context

Player state can change during the match.

Fatigue may affect:

* speed;
* acceleration;
* reaction;
* decision quality;
* technical execution;
* pressing;
* recovery;
* concentration.

Fatigue should modify probabilities and capabilities gradually rather than suddenly disabling abilities.

---

# 25. Game Context

Decision making may also depend on match context:

* scoreline;
* remaining time;
* competition situation;
* attacking urgency;
* defensive urgency;
* risk tolerance.

A team leading 1–0 in minute 88 should not necessarily make the same decisions as a team losing 0–1 in minute 88.

The tactical system establishes baseline behavior.

Match context modifies priorities.

---

# 26. Tactical Editor Contract

The Tactical Editor is a configuration and intent-definition tool.

It should define:

```text
formation
roles
player positions
zones
instructions
team behavior
pressing
marking
width
tempo
passing
transitions
defensive line
set-piece behavior
```

The editor should not contain simulation logic.

It should produce a versioned tactical configuration consumed by the simulator.

```text
TACTICAL EDITOR
       ↓
TACTICAL CONFIGURATION
       ↓
TACTICAL SERVICE
       ↓
CURRENT TACTICAL INTENT
       ↓
SIMULATION
```

---

# 27. Simulator API

The simulator should expose service-oriented operations such as:

```text
Create Match
Load Teams
Load Players
Load Tactical Configuration
Start Match
Pause Match
Resume Match
Advance Simulation
Get Match State
Get Snapshot
Get Events
Get Statistics
Get Decision Trace
Replay From Seed
```

The exact transport mechanism may be REST, WebSocket, gRPC or another protocol.

The domain model must remain independent from that transport.

---

# 28. Simulation Snapshots

A snapshot should be sufficient to reconstruct the relevant current match state.

It should contain or reference:

* match time;
* score;
* ball state;
* player positions;
* player states;
* possession;
* tactical state;
* current phase;
* active events;
* simulation seed/state where necessary.

Snapshots enable:

* replay;
* debugging;
* visualization;
* external clients;
* save/resume;
* deterministic regression.

---

# 29. Observability

Observability is a first-class requirement.

The simulator must be able to explain important decisions.

For example:

```text
Why did Player 6 leave his position?

TACTICAL INTENT:
Maintain width

THREAT:
Opponent entered dangerous central zone

THREAT SEVERITY:
High

DISTANCE:
8.4m

OVERRIDE:
Defensive support

SELECTED INTENT:
Move inside

ACTION:
TRACK
```

Similarly:

```text
Why did Player 10 pass instead of shoot?

PASS:
0.72

SHOOT:
0.41

DRIBBLE:
0.38

CONTEXT:
Two defenders blocking shooting lane

TACTICAL PRIORITY:
Progress possession

RANDOM VARIATION:
+0.02

SELECTED:
PASS
```

The exact numerical representation may differ, but the causal chain must be inspectable.

---

# 30. Decision Trace

Important decisions should generate structured debug information.

A decision trace may include:

```text
player
timestamp
phase
position
ball state
tactical intent
threats
available actions
action scores
player attributes used
random contribution
selected action
result
```

Tracing must be configurable so that production simulation does not require verbose debug output.

---

# 31. Randomness and Observability

When randomness affects a result, the trace should be able to identify:

```text
random seed
random stream / sequence
base evaluation
random contribution
final evaluation
```

This makes apparently random behavior reproducible.

The simulator should never have an unexplained:

```text
Math.random()
```

inside domain logic.

Randomness must come through the simulator's controlled random source.

---

# 32. Statistics

Statistics should be derived from authoritative events/state transitions.

Examples:

* possession;
* passes;
* pass attempts;
* pass completion;
* shots;
* shots on target;
* tackles;
* interceptions;
* fouls;
* cards;
* offsides;
* crosses;
* saves;
* touches;
* duels.

Statistics must not independently simulate football events.

The event/state system is authoritative.

---

# 33. Commentary and Visualization

Commentary, animation and UI are consumers of simulation output.

They must not decide what happened.

```text
SIMULATION
    ↓
EVENTS / STATE
    ↓
┌──────────┬─────────────┬──────────────┐
│ UI       │ Commentary  │ Statistics   │
│ Replay   │ Animation   │ Analytics     │
└──────────┴─────────────┴──────────────┘
```

This prevents presentation logic from contaminating simulation logic.

---

# 34. Deterministic Replay

A complete match should be replayable using:

```text
initial state
+
configuration
+
player data
+
tactical data
+
random seed
```

This enables:

* exact bug reproduction;
* simulation comparison;
* engine regression;
* tactical experimentation;
* AI tuning.

A simulation bug should ideally be reproducible from a recorded seed and state.

---

# 35. Testing Strategy

Testing must exist at multiple levels.

## Unit Tests

Test individual football calculations and rules.

Examples:

* offside;
* distance;
* threat score;
* action evaluation;
* movement;
* probability;
* possession transition.

## Integration Tests

Test interaction between services.

Examples:

```text
Threat → Tactical Override → Movement
Pass → Ball → Interception → Possession
Shot → Goalkeeper → Save
Pass → Offside → Rule Event
```

## Scenario Tests

Simulate realistic football situations.

Examples:

* defensive counterattack;
* high press;
* low block;
* through ball;
* winger tracking runner;
* numerical overload;
* counterattack after turnover.

## Deterministic Replay Tests

Same seed and same state must produce the same result.

## Invariant Tests

Examples:

* only valid players can participate;
* possession has a valid owner when controlled;
* a goal requires a valid goal event;
* state transitions have valid causes;
* ball cannot simultaneously be controlled and free;
* inactive players cannot participate;
* offside cannot be generated without a valid relevant situation.

---

# 36. Existing Java Prototype

The existing Java demo is considered a **behavioral reference implementation**.

It already demonstrates important simulation concepts including:

* threat override;
* offside;
* contextual action selection;
* football-aware decision making.

The production simulator must not discard this work and redesign the same behavior from scratch.

Instead:

```text
EXISTING DEMO
     ↓
IDENTIFY VALID BEHAVIOR
     ↓
FORMALIZE DOMAIN RULE
     ↓
PLACE IN APPROPRIATE SERVICE
     ↓
ADD TESTS
     ↓
INTEGRATE INTO AUTHORITATIVE ENGINE
```

The demo is therefore a source of validated behavior, while this document defines the architectural and domain structure into which that behavior evolves.

---

# 37. Architectural Boundaries

The following boundaries are authoritative:

### Tactical configuration

Defines intended behavior.

### Tactical service

Interprets tactical configuration.

### Threat system

Determines contextual urgency.

### Decision system

Chooses among plausible actions.

### Movement system

Executes spatial behavior.

### Ball system

Models ball state and trajectory.

### Interaction system

Resolves player/player and player/ball contests.

### Rules system

Determines legality and football rules.

### Match state

Stores authoritative result.

### Event system

Communicates meaningful state changes.

### Observability

Explains why decisions and outcomes occurred.

No single service should own all of these responsibilities.

---

# 38. What the Engine Must NOT Become

The simulator must not become:

### A scripted animation system

Players must not simply move through predefined animations.

### A pure statistical simulator

The engine must model spatial and contextual causality rather than generating match statistics independently.

### A tactical puppet system

Tactical instructions must not force impossible or contextually irrational behavior.

### A perfect-information AI

Players must not automatically know the complete match state.

### A random number generator

Randomness must never replace football logic.

### An enterprise architecture exercise

Complexity must be justified by domain behavior.

---

# 39. Complexity Principle

The governing engineering principle is:

> **Use the simplest implementation that correctly represents the football domain.**

Prefer:

```text
clear service
simple data structure
small helper
explicit rule
```

over:

```text
generic abstraction
unnecessary interface
strategy hierarchy
factory factory
dependency injection solely for testability
```

when the additional abstraction provides no meaningful value.

The architecture should be **modular, inspectable and replaceable**, not artificially sophisticated.

---

# 40. Performance Principle

The simulation may eventually execute many players and many simulation steps.

Therefore:

* avoid unnecessary object creation;
* avoid repeated expensive spatial calculations;
* cache derived values where appropriate;
* separate authoritative state from calculated context;
* keep debug tracing configurable;
* avoid recalculating information that has not changed.

Performance optimizations must not obscure football logic.

Correctness comes first.

---

# 41. Domain Vocabulary

The following terms must remain distinct:

| Term                   | Meaning                                             |
| ---------------------- | --------------------------------------------------- |
| Tactical Configuration | What the team/player has been instructed to do      |
| Tactical Intent        | What the player should currently try to do          |
| Threat                 | Immediate contextual danger requiring consideration |
| Override               | Temporary change in priority caused by context      |
| Perception             | What the player can reasonably perceive             |
| Candidate Action       | Action available to the player                      |
| Decision               | Selection of an action                              |
| Execution              | Physical/technical attempt to perform it            |
| Interaction            | Result of action against ball/player/environment    |
| Rule                   | Football legality/constraint                        |
| Event                  | Meaningful occurrence                               |
| State Transition       | Change in authoritative match state                 |

These concepts must not be casually merged.

---

# 42. End-to-End Example

Consider a winger instructed to maintain width.

```text
TACTICAL CONFIGURATION
Winger:
    Maintain width
```

The winger's normal intent is:

```text
CURRENT INTENT
Maintain wide attacking position
```

An opponent receives the ball centrally.

Threat analysis detects:

```text
Opponent:
dangerous central position

Distance:
short

Potential:
forward progression

Defensive support:
required
```

The tactical intent is temporarily overridden:

```text
OVERRIDE
Provide defensive support
```

The winger moves inside.

While moving, the opponent attempts a forward pass.

The winger evaluates:

```text
INTERCEPT
TRACK
PRESS
HOLD
```

Based on positioning, anticipation, distance and tactical context, interception becomes the preferred candidate.

A small controlled random factor is applied.

The winger successfully intercepts.

The ball state changes.

Possession changes.

The team enters transition-to-attack.

The winger's tactical priorities change again.

The simulation therefore produces:

```text
TACTICS
 → THREAT
 → OVERRIDE
 → MOVEMENT
 → DECISION
 → INTERCEPTION
 → POSSESSION CHANGE
 → TRANSITION
 → NEW TACTICAL INTENT
```

No single rule scripted the complete sequence.

The behavior emerged from interacting systems.

---

# 43. Fundamental Simulation Equation

At a conceptual level, player behavior can be understood as:

```text
PLAYER BEHAVIOR =
    Tactical Intent
  + Current Situation
  + Perception
  + Threat
  + Player Attributes
  + Match Context
  + Controlled Randomness
```

The result becomes:

```text
→ Decision
→ Action
→ Interaction
→ Outcome
→ State Change
```

This is the core behavioral model of the simulator.

---

# 44. Source-of-Truth Hierarchy

When systems disagree, the following hierarchy applies:

```text
1. Football Rules
2. Authoritative Match State
3. Physical / Spatial Reality
4. Current Situation / Threat
5. Player Capabilities
6. Tactical Intent
7. Decision Preferences
8. Presentation / UI
```

This does not mean tactics are unimportant.

It means tactics operate **inside football reality**, rather than replacing it.

---

# 45. Final Architecture

The complete conceptual architecture is:

```text
                         ┌───────────────────────┐
                         │    Tactical Editor    │
                         └───────────┬───────────┘
                                     │
                                     ▼
                         ┌───────────────────────┐
                         │ Tactical Configuration│
                         └───────────┬───────────┘
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────┐
│                 FOOTBALL SIMULATION ENGINE               │
│                                                          │
│  ┌──────────────┐      ┌──────────────────┐              │
│  │ Match State  │◄────►│ Tactical Service │              │
│  └──────┬───────┘      └────────┬─────────┘              │
│         │                       │                        │
│         ▼                       ▼                        │
│  ┌──────────────┐      ┌──────────────────┐              │
│  │ Spatial      │─────►│ Threat / Context │              │
│  │ Context      │      └────────┬─────────┘              │
│  └──────────────┘               │                        │
│                                 ▼                        │
│                       ┌──────────────────┐               │
│                       │ Player Perception │               │
│                       └────────┬─────────┘               │
│                                ▼                         │
│                       ┌──────────────────┐               │
│                       │ Action Selection │               │
│                       └────────┬─────────┘               │
│                                │                         │
│                                ▼                         │
│                       ┌──────────────────┐               │
│                       │ Action Execution │               │
│                       └────────┬─────────┘               │
│                                ▼                         │
│              ┌────────────────────────────────┐          │
│              │ Player / Ball Interaction      │          │
│              └───────────────┬────────────────┘          │
│                              ▼                           │
│                    ┌──────────────────┐                  │
│                    │ Football Rules   │                  │
│                    └────────┬─────────┘                  │
│                             ▼                            │
│                    ┌──────────────────┐                  │
│                    │ Event Resolution │                  │
│                    └────────┬─────────┘                  │
│                             ▼                            │
│                    ┌──────────────────┐                  │
│                    │ Match State      │                  │
│                    │ Transition       │                  │
│                    └──────────────────┘                  │
│                                                          │
│       ┌────────────────────────────────────────┐         │
│       │ Seeded Randomness + Observability     │         │
│       └────────────────────────────────────────┘         │
└──────────────────────────────────────────────────────────┘
                 │              │              │
                 ▼              ▼              ▼
              Match UI      Commentary      Analytics
```

---

# 46. Architectural North Star

The simulator is successful when the following statement is true:

> **Given the current match state, team tactics, player capabilities and contextual information, the engine can produce a plausible football decision, execute it according to the player's capabilities and the physical situation, resolve the applicable football rules, and explain why the resulting state occurred.**

And the same situation should **not always produce the same result**, because football contains uncertainty.

However, that uncertainty must remain:

* small where the situation is clear;
* meaningful where alternatives are close;
* influenced by player quality;
* influenced by context;
* seeded;
* reproducible;
* observable.

The simulator therefore combines:

```text
FOOTBALL LOGIC
+
TACTICAL INTELLIGENCE
+
PLAYER DIFFERENCES
+
SPATIAL CONTEXT
+
RULE ACCURACY
+
CONTROLLED UNCERTAINTY
+
DETERMINISTIC REPLAY
```

That combination, rather than any individual algorithm, defines the intended character of the engine.

---

# 47. Football Simulation — Action & Event Semantics

This section defines the **football-domain specification** — not implementation details, but what each action and event **means in real football**, when it is a candidate, what its alternatives are, and what happens when it fails.

The specification draws on the current IFAB framework 2026/27, particularly Laws 9–17, offside rules, and VAR/disciplinary provisions. ([IFAB](https://theifab.com/laws-of-the-game-documents/))

## 47.0 Pitch Orientation

```text
  OOB row 0
─────────────
AWAY GOAL    row 8 (goal line)
─────────────
row 7        (centre 7.5 — last playable row, AWAY GK zone)
row 6
row 5
row 4
row 3
row 2
row 1        (centre 1.5 — last playable row, HOME GK zone)
─────────────
HOME GOAL    row 1 (goal line)
─────────────
  OOB row 9
        col 1 ... col 6  (each col centre at n + 0.5)
```

### HOME

* Attacks from **row 1 → row 8** (HOME goal line at row 1.0, AWAY goal line at row 8.0)
* Own goal = row 1.0
* Opponent's goal = row 8.0
* col 1 = left from HOME perspective
* col 6 = right from HOME perspective

### AWAY

* Attacks from **row 8 → row 1**
* Own goal = row 8.0
* Opponent's goal = row 1.0
* Left/right from AWAY attacking perspective.

Row is the primary forward/backward axis. Column is width.

---

## 47.1 PASS

The most basic possession action. The ball carrier attempts to relay the ball to a teammate.

### Ground pass

Ball travels along the ground. Typical:

* short distance
* safe distribution
* high success rate
* used between close teammates
* used under pressure
* used to maintain possession

Example: `CB → CM`, `CM → FB`

**Mid-path deflection / interception:** ground passes navigate through
traffic and are subject to `SimUtils.pointSegmentDistance(defender, prevBallPos,
currentBallPos)` checks. A defender whose body is within `0.2 cells (~2.8 m)` of
the pass lane can deflect the ball (fast pass → bounces loose) or intercept
(slow pass → defender becomes carrier). Mid-path collision is the chief cause
of "stolen" passes.

### Air pass

Ball travels through the air. Used when:

* opponent closes the ground passing lane
* need to bypass the press
* need to switch the zone
* need a longer delivery
* no safe ground option exists

Higher risk of control/receive failure, but can bypass opponents. Air
passes are **easier to execute** (less deviation — `heightMultiplier = 0.7` in
`ExecutionQuality`) because they fly OVER obstacles. They are also more visible
in the viewer because the ball travels a longer arc.

**Mid-path collision:** air passes are EXEMPT from mid-path collision — they
are only contested at arrival via `findPassInterceptor`.

### Pass speed

Pass speed is **derived from the passer's passing skill** (range 1.0–3.0
cells/tick, where 1.0 = weak, 3.0 = elite):

```java
speedFromSkill = 1.0 + (passing / 20.0) * 2.0;
if (passLength == LONG) speedFromSkill += 0.2;
action.passSpeed = min(3.0, speedFromSkill);
```

`BallMovementEngine.moveBallTowardCurrentTarget()` reads `action.passSpeed` and
moves the ball at that speed — faster passes are harder to intercept, easier to
deflect, and visible for fewer ticks (so the viewer shows them as quick
flashes). The `passSpeed` field is consumed downstream by:

* Mid-path collision `contactProb *= 0.85 + 0.15 * passSpeed / 3.0`
* Deflection vs interception: `deflectProb = 0.15 + (passSpeed - 1.0) / 2.0 * 0.45`
  (slow ball → interceptor wins, fast ball → deflects)
* `findPassInterceptor` speed modifier: `max(0.2, 1.0 - (passSpeed - 1.0) / 2.5)`
  (slow ball = more reaction time = easier interception)

### Safe PASS

Candidate when:

* there is a relatively safe teammate
* passing lane is not seriously blocked
* no urgent need to break the line
* player is under pressure
* possession retention has higher value than progression

Example: `CB → CM`, `CM → FB`

### Risky PASS

Candidate when:

* there is a potential for significant progressive gain
* receiver is behind the defensive line
* counter may be exposed
* few safe options exist
* player has sufficient vision/playmaking ability

> **A risky PASS is not automatically a bad decision.** A good playmaker may deliberately attempt a risky pass when the potential benefit is large. Similarly, it is **perfectly normal for a poor playmaker to occasionally make a poor/risky pass**, including toward a player in a very tight offside position.

### Short vs Long

**Short** — small distance, higher precision, lower risk, faster control, often ground.

**Long** — larger distance, bigger progression, changes attacking zone, can bypass pressure, higher execution risk.

### Alternatives to PASS

```text
PASS → THRU, CARRY, CROSS, CENTER, SHOT, CLEAR
```

The Decision Engine selects based on situation, player ability, and option availability.

---

## 47.2 THRU PASS

A special type of PASS. The target is not a player's feet, but **space behind the defensive line**.

```text
PASS → player
THRU  → space behind defenders → runner
```

Candidate when:

* there is a runner
* there is space behind the defense
* the runner can reach the ball
* the pass can break the defensive line

Critical relationship: `vision + passing + runner position + space + offside risk`

### Alternative

If THRU is too risky: `PASS, CARRY, CROSS, CENTER`

---

## 47.3 CROSS

A cross is a ball delivered from a wide position toward the attacking zone/box. Not the same as CENTER.

```text
          BOX
       ↑ ↑ ↑ ↑
       ↑ ↑ ↑ ↑
WINGER ───────→
```

Candidate when:

* carrier is wide
* positioned high enough
* there are players in the box
* there is an attacking finishing opportunity
* a central passing lane is not better

Cross types: low cross, driven cross, high cross. For the simulator we can start with just `CROSS` with internal selection of height/target.

### Alternative

`PASS back, CARRY, THRU, CENTER, SHOT if the angle is good`

---

## 47.4 CENTER

> **CENTER = ball toward the central box area / central attacking target zone.**

Unlike CROSS:

```text
CROSS  = wide → box
CENTER = central/attacking zone → box
```

Candidate when:

* carrier has a sufficiently high position
* there is a target in the box
* a central ball makes sense
* there is numerical presence in the box

Important: **CENTER does not mean "any pass toward the middle."** It is an attacking delivery toward the box.

### Alternative

`CROSS if wide, PASS if safer, SHOT if in shooting position, CARRY if space exists`

---

## 47.5 CARRY / DRIBBLE

The carrier retains the ball and moves with it. This is not a pass.

### CARRY

More: `space → advance`

### DRIBBLE

More: `defender → attempt to beat him`

For the simulator both can share a `DecisionType`, but outcomes differ.

Candidate when:

* space ahead
* no good passing option
* can gain territory
* can draw an opponent
* can attack a defender 1v1

### Carry target — 3-4 cells ahead

`executeCarry()` sets the carrier's target **3-4 cells ahead** (in the forward
direction, with ±1 column variation). Short targets (1 cell) caused the
carrier to "jump-pause-jump" — completing a 1-cell carry, re-deciding,
starting a new carry, repeating. The 3-4 cell target makes the carrier move
**continuously** for several seconds before the next decision, which reads as
natural dribbling on the viewer.

### Per-tick re-decision during CARRY

While a CARRY is in progress the carrier re-evaluates EVERY tick:

```text
CARRY in progress
 ↓
every tick: assignTargets() + decide()
 ↓
new decision != CARRY?
    YES → complete current carry, execute new action
    NO  → carry continues to its normal target-reached completion
```

This lets a dribbler react to a shooting lane opening up, a defender closing
in, or a passing option appearing. Without re-decision, the carrier is
locked into the original carry for its full duration.

### When NOT carry

* **HARD RULE — never carry in own defensive last 2-3 rows** (HOME row ≤ 3.0,
  AWAY row ≥ 6.0): CARRY is blocked outright (score -300) — the carrier must
  pass or clear. Dribbling is an attacking-half option only, per user rule
  "ne sme da dribla u nasa zadnja 2-3 reda".
* opponent close — pressure scales with proximity (Σ(1.0-d)/cell within 1.0),
  so a closing defender forces a PASS/SHOT release long before duel range
* no space
* player is weak at dribbling
* much better PASS exists
* clear pressure

Alternative: `PASS, CLEAR, SHOT`

---

## 47.6 SHOT

An attempt to score a goal.

Candidate when:

* player has a sufficiently good shooting position
* distance/angle are acceptable
* there is an open shooting lane
* shot has greater value than pass

On our grid:

```text
row 8         AWAY GOAL
   ↑
row 7         (last playable row, AWAY GK zone)
row 6         attacking shooting zone
row 5
row 4
row 3
row 2
row 1         (last playable row, HOME GK zone)
row 1         HOME GOAL
   ↓
```

For HOME, the closer to row 8, the higher the shooting opportunity. For AWAY, the reverse.

### Shot outcome

Not simply `SHOT → GOAL / MISS`, but:

```text
SHOT
 ↓
Execution
 ↓
trajectory
 ↓
defender interaction?
 ↓
GK?
 ↓
goal / save / deflection / miss
```

### Shot aim — far post when GK is off-centre

When a shot is on-target, the ball is **aimed at the far post relative to the
GK position**, not always at the centre:

* GK on the left post (col ≈ 3) → shot goes to the right post (col ≈ 4)
* GK on the right post (col ≈ 4) → shot goes to the left post (col ≈ 3)
* GK centred (col ≈ 3.5) → shot goes to the centre (col 3.5)
* GK within 2.0 cells of goal centre but **off the shot lane**
  (perpendicular distance > 1.2 cells) → goal is treated as empty even
  though the GK is "near" the goal line

Goal width is 1 cell (col 3.0 to col 4.0, centre col 3.5) — the far-post aim
stays within the actual goal mouth. The save decision re-evaluates the
`gkInLane` factor against the actual shot target so a GK on the near post
correctly fails to save a far-post shot.

This matters because of: DEFLECTION, SAVE, REBOUND, CORNER, GOAL, VAR.

---

## 47.7 CLEAR

CLEAR is a **defensive emergency action**. The goal is not progression.

> **The goal is: remove the ball from the dangerous zone.**

Candidate when:

* player is under serious pressure
* no safe PASS
* ball is in the defensive zone
* danger of immediate loss exists
* possession retention is no longer a rational option

Typically: `CB under pressure → CLEAR → ball away`

### CLEAR alternative

If `SAFE PASS` exists, it is better. If not, `CLEAR`.

Important: **CLEAR is not a failed PASS.** It is a deliberate decision to eject the ball.

---

## 47.8 OFFSIDE

We must strictly separate:

### Offside position

Being in an offside position is not itself an offence. ([IFAB Law 11](https://www.theifab.com/laws/latest/offside/))

For the simulator, a player is in offside position when in the opponent's half and closer to the goal line than the ball and the second-last opponent. But:

```text
OFFSIDE POSITION ≠ OFFSIDE OFFENCE
```

Offside offence occurs when a player from that position becomes actively involved in play.

### For the simulator

Critical snapshot:

```text
PASS START
    ↓
capture positions
    ↓
was receiver offside?
    ↓
if yes:
    PASS continues
    ↓
receiver receives/touches ball
    ↓
OFFSIDE EVENT
```

### Restart

Offside → **indirect free kick** for the opposing team at the place of the offence. ([IFAB](https://theifab.com/laws/chapter/31/section/88/))

### Exceptions

No offside offence directly from:

* goal kick
* throw-in
* corner kick

This should be a **hard rule**, not a decision probability.

---

## 47.9 OFFSIDE RETREAT

This is a **simulation/gameplay rule we can add above the real Law 11**, because the football law itself does not say "after three actions the player must return."

Our rule:

```text
player remains offside
+
3 consecutive TICKS in an offside position
        ↓
FORCE RETREAT
```

The player receives at minimum: `RETREAT, RETREAT, ...` until entering a safe row.

The tracking is **universal**: `OffsideService.trackOffsidePositions(state)` is
called on EVERY tick (not just at forward-pass moments). It checks ALL
outfield players on BOTH teams — any attacker standing in an offside position
accumulates a consecutive-offside count; after 3 consecutive ticks offside the
threat override drops them back toward their own goal until onside.

### What is safe

For HOME: `position ≤ defensive line relative to ball/opponents`

For AWAY, the same but reversed direction.

This should not be implemented as "go three cells back." Better:

> **retreat toward the nearest row in which the player is no longer offside.**

The Movement Engine still limits per-tick movement.

---

## 47.10 When to attempt risky pass toward offside player

This is good AI material. Not:

```text
offside distance = 0 → NEVER PASS
```

But:

```text
small offside margin
+
high potential reward
+
good playmaker
+
good runner
+
risk acceptable
→ risky pass candidate
```

Example:

```text
runner: barely offside
space behind defence: HUGE
playmaker: high vision
pass: difficult but possible
→ THRU/RISKY PASS remains candidate
```

While:

```text
runner: clearly offside
space: small
playmaker: poor
pressure: high
→ don't attempt
```

This produces natural behavior: > a poor player may foolishly pass into offside, while a good player may deliberately attempt it "on the edge."

---

## 47.11 VAR

VAR is not an engine that decides every event. VAR is:

> **A review mechanism activated only for specific reviewable incidents.**

Current IFAB framework 2026/27 includes VAR capabilities for goals/penalties and certain red cards; IFAB has specifically expanded/clarified some VAR areas for 2026.

For the simulator:

```text
VAR EVENT
  ↓
Was incident reviewable?
  ↓
Was evidence sufficient?
  ↓
CONFIRM / OVERTURN
```

### VAR overlay timing (viewer)

* **VAR_IN_PROGRESS** — BLOCKING overlay, 3500 ms. Viewer playback pauses while
  the review is on screen.
* **VAR confirmed/overturned** — BLOCKING overlay, 2200 ms. Shown after the
  IN PROGRESS overlay is dismissed.

The viewer emits the IN PROGRESS event first, then the confirmed/overturned
verdict on the same tick; the `break` in `_processEventsForTick` ensures the
verdict waits until the IN PROGRESS overlay is dismissed, giving the proper
"review then verdict" sequence.

### VAR: GOAL

Review: `possible offside? possible foul? possible handball? possible illegal action?`

Result: `GOAL CONFIRMED` or `GOAL OVERTURNED → restart determined by actual offence`

### VAR — OFFSIDE

Not every offside needs VAR. In the simulator:

* **Obvious offside** (large distance) → referee/offside engine confirms
* **Marginal offside** (small distance) → VAR review candidate

Example: `OFFSIDE_MARGIN <= threshold → VAR_REVIEW_OFFSIDE`

This is especially elegant for simulation because it enables:

```text
GOAL → VAR → offside 0.2 cell → GOAL OVERTURNED
```

or:

```text
offside 0.05 → VAR → ON SIDE → GOAL
```

### VAR — RED CARD / FOUL

Distinguish:

* **Yellow** — normal disciplinary action
* **Straight red** — potential VAR review: `foul → possible red → VAR → RED CONFIRMED / OVERTURNED`
* **Second yellow**: `yellow + yellow = red + player sent off` — not the same as straight red. IFAB 2026/27 specifically provides VAR review for **factual errors involving a second yellow card**.

**Yellow-card VAR requires a resolved duel first.** Per user rule:
`DisciplineService.evaluateFoul(hadDuel)` — if no duel was active at the
foul moment, no card is issued (free kick only). This prevents VAR from
being called for phantom tackles that never happened.

### VAR — PENALTY

```text
DUEL → DEFENDER wins → no foul called
→ but attacker was in penalty area
→ VAR candidate
```

If VAR determines: `foul + inside penalty area → PENALTY`. If no offence: `play continues`.

---

## 47.12 DEFLECTION

This is not a decision. This is a **physical event**.

```text
SHOT → ball trajectory → DEFENDER intersects trajectory → DEFLECTION
```

or:

```text
PASS → defender touches ball → DEFLECTION
```

After deflection, re-evaluate: `ball direction, ball position, carrier?, possession?, out?, goal?, corner?, interception?`

> **Deflection does not choose a new action. It changes the ball state.**

---

## 47.13 INTERCEPTION

Not the same as a tackle.

### Interception

The defender **intercepts the ball**.

```text
PASS → ball trajectory → defender enters passing lane → INTERCEPTION → defender becomes carrier
```

No physical duel with the carrier is necessarily involved.

### Lane geometry — strict

The defender must be **on the LINE SEGMENT between the ball and the receiver**
(perpendicular distance ≤ 0.5 cells for ground passes, ≤ 0.4 for air passes),
not just "nearby". Interception is **not** triggered by proximity alone — a
defender standing 1 cell off the lane but close to the ball is NOT intercepting.

Triangle check: the defender must be roughly between the ball and the receiver
(distance from defender to ball < 1.3 × ball-to-receiver distance AND
defender-to-receiver < 1.3 × ball-to-receiver distance). This excludes players
behind the passer or behind the receiver from being eligible.

Skill gates: requires the defender to have `playmaking ≥ 12` AND `defending ≥ 12`
to deliberately read the pass. The interception chance then scales with
`(playmaking + defending) / 40 × speedModifier` where `speedModifier` decreases
for faster passes (slow ball = more reaction time).

---

## 47.14 TACKLE

A tackle is a **defensive action against a player who has the ball**.

```text
carrier → defender enters duel radius → TACKLE
```

Outcome:

```text
successful tackle → defender gets ball
failed tackle → carrier keeps ball
foul → free kick / penalty
serious foul → yellow / red / VAR candidate
```

This is why `Tackle/Duel` should be kept separate from `Interception`.

### Duel radius — tight, realistic

Because a cell is 14 m × 10 m, a duel only fires when a defender is **physically
on top of the ball**:

* **DRIBBLE**: 0.15 cells (~2 m) — tighter tackle trigger; a defender who has
  closed the gap via the TYPE A press override (1.0 cell awareness radius)
  engages a carrier as soon as they reach tackling distance.
* **RECEIVE_PASS / CHASE_BALL**: 0.2 cells (~2.8 m)
* **SHOT block**: 0.3 cells (~4.2 m)

A defender standing 1 cell away from a carrier cannot tackle — they have to
close the gap. The radius is intentionally tight so duels feel like genuine
shoulder-charges, not magical proximity grabs. Cooldowns:

* Default 10 ticks between duels for the same pair
* DRIBBLE: 8-tick cooldown so a carrier entering a crowd of defenders triggers
  multiple tackle attempts during a single CARRY action instead of being
  locked out for 20 ticks (~30s of silent play)

---

## 47.15 GOAL KICK

If the ball completely crosses the goal line and the last player to touch it was an attacking player, and it is not a goal:

```text
GOAL KICK
```

([IFAB Law 16](https://www.theifab.com/laws/latest/the-goal-kick/))

For the simulator:

```text
HOME attacking → shot/pass → ball exits over AWAY goal line
→ last touch = HOME → AWAY GOAL KICK
```

---

## 47.16 CORNER

If the ball completely crosses the goal line, is not a goal, and the last player to touch it was the defending team:

```text
CORNER
```

([IFAB Law 17](https://www.theifab.com/laws/latest/the-corner-kick/))

Example:

```text
HOME SHOT → AWAY defender deflects → ball crosses AWAY goal line → CORNER HOME
```

This is the classic `DEFLECTION → CORNER` scenario.

---

## 47.17 OUT OF BOUNDS / THROW-IN

If the ball completely crosses the touchline:

```text
THROW-IN
```

for the opposing team of the last player who touched the ball. ([IFAB Law 15](https://www.theifab.com/laws/latest/the-throw-in/))

For our pitch: `col < 1` or `col > 6` → out.

Important: **it does not matter whether the ball went out on the ground or through the air.**

---

## 47.18 SAVE

This must be explicitly modeled, because currently we have SHOT but the physical event between shot and goal must exist.

```text
SHOT → GK reaches ball → SAVE
```

After save:

```text
SAVE → CONTROLLED?
├── yes → GK carrier
└── no → REBOUND
```

If it goes behind the goal line: `SAVE/DEFLECTION → CORNER`

### Save decision vs shot target

The GK beats the shot only when the GK **is on the lane of the actual shot
target** (perpendicular distance ≤ 1.2 cells to the shooter → actual-target
line, with the projection also between shooter and goal — coverage factor
`t ∈ [0.2, 1.0]`). The `handleShotArrival` recomputes `gkInLane` against the
real shot target — a GK on the near post does NOT cover a far-post shot,
even though the GK is "in the goal area".

Save chance formula:

```text
positionFactor  = 0.22 + 0.85 * gkInLane            (in-lane dominates)
keeperFactor    = 0.74 + keeper / 20.0 * 0.40    (skill matters)
strikeFactor    = 1.0 - striker / 20.0 * 0.24    (good finishers beat easier)
reach           = max(0.62, 1.0 - max(0, dist - 1.2) * 0.32)
saveChance      = clamp(positionFactor × keeperFactor × strikeFactor × reach,
                          0.03, 0.90)
saveCooldown    = 4 ticks — a GK who just saved can't immediately save again
```

If a shot is **clearly off-lane** (`gkInLane ≤ 0.2` after recomputation), the
goal is effectively open — the save chance is set to the minimum (~3%) and
the shot becomes a goal. Random alone cannot turn a clear open-goal into a save.

---

## 47.19 REBOUND

Also added as a separate event.

```text
SHOT → GK SAVE / DEFLECTION → ball remains playable → REBOUND
```

Then: `nearest player → CHASE` or if someone is already in good position: `player → carrier`

---

## 47.20 BALL OUT / DEAD BALL

This should be an **event state**, not an action.

```text
BALL_OUT
```

Then the resolver determines: `GOAL KICK, CORNER, THROW-IN, FREE KICK, PENALTY, OFFSIDE RESTART`

This is much cleaner than having every engine know the restart type independently.

---

## 47.21 FOUL

This is definitely missing as a separate domain event.

```text
FOUL
```

with: `fouler, victim, location, severity, advantage?, insidePenaltyArea?, disciplinaryAction`

Outcome:

```text
DIRECT_FREE_KICK, INDIRECT_FREE_KICK, PENALTY, YELLOW, RED, PLAY_ON
```

---

## 47.22 ADVANTAGE

Also to be added.

If a foul situation occurs, but the fouled team has clear benefit:

```text
FOUL → ADVANTAGE → PLAY CONTINUES
```

Later we can decide whether the simulator needs delayed cards.

---

## 47.23 FREE KICK

Another **restart type**, not just an action.

```text
DIRECT_FREE_KICK
INDIRECT_FREE_KICK
```

**Direct** — can score directly. **Indirect** — cannot score directly. Offside restarts with an indirect free kick.

---

## 47.24 PENALTY

A special restart/action.

```text
FOUL + inside penalty area + direct-free-kick offence → PENALTY
```

Then:

```text
PENALTY KICK → SHOT → GOAL / SAVE / MISS / REBOUND
```

So a penalty is not the same as a `SHOT`.

---

## 47.25 CHASE

This must be in the base model, especially given the existing architecture.

If:

```text
ball has no carrier + ball is playable → CHASE
```

CHASE is not a player decision like PASS/SHOT. It is:

> **A state-recovery mechanism.** The nearest valid player moves toward the ball.

---

## 47.26 BALL CONTROL / RECEIVE

Another thing to be explicit about.

```text
BALL MOVEMENT → player reaches ball → CONTROL / RECEIVE → carrier established
```

Only then does the `Decision Engine` get the carrier. Without this, the simulator silently skips an important part of football.

---

## 47.27 POSSESSION CHANGE

Also an event:

```text
POSSESSION_CHANGED
```

Can arise from: tackle, interception, successful duel, loose ball recovery, rebound, save/recovery, failed pass, deflection.

This is a **trigger**, not a decision.

---

## 47.28 Final Division

### Player Decisions

```text
PASS, THRU, CARRY / DRIBBLE, CROSS, CENTER, SHOT, CLEAR
```

### Defensive Actions

```text
TACKLE, PRESS / THREAT, INTERCEPTION, BLOCK
```

### Ball Events

```text
DEFLECTION, SAVE, REBOUND, BALL CONTROL, POSSESSION CHANGE, BALL OUT, GOAL
```

### Rules / Referee Events

```text
OFFSIDE, FOUL, YELLOW, RED, ADVANTAGE
```

### Restart Events

```text
KICK-OFF, FREE KICK, PENALTY, THROW-IN, GOAL KICK, CORNER
```

### Automatic Recovery

```text
CHASE
```

### Technology

```text
VAR_GOAL, VAR_OFFSIDE, VAR_PENALTY, VAR_RED_CARD
```

### Special Tactical Safety

```text
OFFSIDE RETREAT
```

---

## 47.29 The Key Architectural Insight

> **Not everything should be `DecisionEngine`.**

```text
DecisionEngine → PASS, CARRY, SHOT, CROSS, CENTER, CLEAR, THRU
```

But:

```text
OFFSIDE, DEFLECTION, INTERCEPTION, SAVE, CORNER, GOAL KICK, THROW-IN, FOUL, VAR
```

**are not player decisions.** They are **events / rules / resolution**.

And `TACKLE` is a player action but is mainly driven by **defensive/duel resolution**, not the playmaking decision of the carrier.

And `CHASE` is an automatic state-recovery action.

This separation is the **correct boundary between "an engine that plays football" and "an engine that runs a match simulation"**.

The best model:

```text
             ┌───────────────┐
             │ ORCHESTRATOR  │
             └───────┬───────┘
                     │
          ┌──────────┴───────────┐
          ↓                      ↓
   PLAYER DECISION          GAME STATE
          │                      │
          ↓                      ↓
    EXECUTION              RULE/EVENT ENGINE
          │                      │
          └──────────┬───────────┘
                     ↓
                 NEW STATE
                     ↓
              ACTION COMPLETE
                     ↓
               NEXT ACTION
```

---

# 48. Implementation Rule


When implementing any new feature, the engineer must first answer:

1. **What football concept does this represent?**
2. **Which service owns that concept?**
3. **What state does it read?**
4. **What state or event does it produce?**
5. **Does it represent intent, decision, execution, interaction, rule or state transition?**
6. **Where does randomness belong, if applicable?**
7. **Can the result be reproduced with the simulation seed?**
8. **Can the decision be explained in debug mode?**
9. **Can it be tested independently?**
10. **Does the feature introduce unnecessary abstraction?**

If these questions cannot be answered clearly, the feature is not yet sufficiently defined.

---

# 49. Final Principle

The simulator must not attempt to script football.

It must create the **conditions under which football behavior can emerge**.

Tactics establish intent.

The situation creates pressure.

Players perceive imperfectly.

Attributes influence capability.

Threat changes priorities.

Players select actions.

Randomness introduces natural variation.

Interactions determine outcomes.

Rules determine legality.

Events change state.

The new state creates the next situation.

And the cycle continues until the match ends.

That is the fundamental architecture of the Football Simulation Engine.

---

## §48 — RESTART SPECIFICATION (KICKOFF / GOAL KICK / CORNER / THROW-IN / FREE KICK / PENALTY)

This section is the **authoritative restarts reference** for the engine. The
previous OOB-hold / slow-walk implementation made goal kicks travel visibly
across the pitch while the carrier ran alongside the ball — players went OFF
SCREEN and the carrier selection was wrong (an AWAY player who happened to be
near the ball received it instead of the proper taker). The user explicitly
required a spec-driven rewrite. This section IS the spec.

### 48.1 Universal rules (apply to ALL restarts)

1. **The clock always runs.** No `OOB_HOLD_TICKS` freeze, no slow-walk
   animation across the pitch. Restarts happen instantly: the ball is
   teleported to its position, the players fan out via tactical positions,
   and the taker walks to the ball at normal carrier movement speed.
2. **No player teleporting.** Players are placed at their **tactical-desired
   positions** (per `TacticsRules`) computed for the restart spot. A
   player who needs to move more than 1 cell to reach the tactical spot is
   moved there smoothly (over multiple ticks), not snapped.
3. **The taker walks, not teleports.** The designated taker moves at
   `MovementEngine.PLAYER_SPEED` (0.25 cells/tick) toward the ball. The ball
   sits stationary until the taker reaches it.
4. **No carry-out from a restart.** A kickoff taker MUST pass backward (the
   existing `isKickoff` filter in `scorePassOptions` enforces this).
5. **OOB hold is GONE.** The `MatchState.OOB_HOLD_TICKS = 4s` animation is
   removed; the ball teleports and the next action starts on the next
   decision tick.

### 48.2 KICKOFF (also: start of second half; after a goal)

- Ball is at the centre spot `(4.0, 3.5)`.
- The taker (next attacker of the side that did **NOT** just concede) is the
  carrier. By football rules:
  - **Match kickoff**: HOME takes it (unless the rules state otherwise).
  - **Start of second half**: AWAY takes it (sides swap).
  - **After a goal**: the side that **conceded** takes the kickoff. The
    engine already sets `state.setKickoffTeam` to the conceding team; the
    kickoff handler picks that team.
- The kicker is an **attacker** adjacent to the ball (ML/MR/STL/STR nearest
  the centre spot). Taker walks to `(4.0, 3.5)` smoothly if needed.
- Other players are placed at tactical kickoff positions (existing
  `getKickoffPositions` flow).
- The kicker is locked to PASS backward (existing `isKickoff = true` row
  filter in `scorePassOptions`).

### 48.3 GOAL KICK

Triggered when the **attacking** side puts the ball past the **defending**
goal line without scoring (own defender's clearance, a missed shot that
goes out, a deflection off a defender). The defending side is awarded the
restart.

Steps:
1. **Ball teleport**: ball moves to the defender's goal-area corner:
   `(0.5, 3.5)` for HOME's goal (AWAY's goal kick), or `(7.5, 3.5)` for
   AWAY's goal (HOME's goal kick). The ball is INSIDE the playing area
   (row ≥ 1 for HOME, ≤ 7 for AWAY); it is not in OOB.
2. **Player tactical positions**: all 22 players are placed at their
   tactical desired positions for the restart spot (via `TacticsRules`).
3. **Opponent pushback**: if any opponent is closer than 1 cell to the
   ball, that opponent is **smooth-moved** back toward their own goal until
   the distance is ≥ 1 cell. No teleport.
4. **Taker selection**: the nearest SAME-team player (excluding GK) to the
   ball walks smoothly to the ball. When the taker reaches the ball, they
   become the carrier.
5. **Taker decision**: the taker may PASS, CLEAR, or (if in shooting
   range) shoot. **NO carry straight from a goal kick** unless the lane is
   clear and the taker is a high-skilled defender.
6. Clock keeps running.

### 48.4 CORNER

Triggered when the **defending** side deflects the ball over their own goal
line (block, save rebound out, own defender's clearance). The **attacking**
side takes the corner.

Steps:
1. **Ball teleport**: to the corner flag of the side where the ball exited.
   When the ball exited on the attacking side's left (col ≤ 2 for HOME),
   place at `(8.0, 1.0)` (top-left from HOME's POV = AWAY's goal-left =
   HOME's attacking "left" corner).
   - HOME attacking, ball exited behind AWAY goal on the left:
     `Position(8.0, 1.0)`.
   - HOME attacking, ball exited behind AWAY goal on the right:
     `Position(8.0, 7.0)`.
   - AWAY attacking (mirror): `(1.0, 1.0)` and `(1.0, 7.0)`.
   - If the ball exited in the corner-flag spot itself (within ±0.5 of a
     corner), use that exact corner.
2. **Player positions**: all 22 players placed at tactical positions for
   the corner spot. Defenders mark box attackers; attackers time their run.
3. **Taker**: the same-side WINGER (ML/MR) on the side where the corner is
   is the carrier (existing `selectCornerTaker` heuristic). Walks to the
   corner flag if not already there (smooth).
4. **Taker action**: the corner is played as a **CENTER** into the box,
   not a free pass. The `selectCenterTarget()` helper picks the best aerial
   target among box attackers.
5. **Offside rule for corners**: **DISABLED**. Corner passes are deemed
   "from the goal line"; the `isKickoff` / `closeToGoal` row filters still
   permit non-flag passes, but the realistic football convention is that
   a corner is played into the box, not as a flat long ball. The engine's
   `selectCenterTarget()` keeps the receiver in the box, so offside is
   naturally avoided.

### 48.5 THROW-IN (aut)

Triggered when the ball crosses the **sideline** (column ≤ 0.99 or ≥ 7.01)
after last being touched by the side opposing the ball-exit side. The
**non-last-touch** side takes the throw.

Steps:
1. **Ball teleport**: to the exact `(row, 1.0)` (left sideline) or
   `(row, 7.0)` (right sideline) at the same row where the ball crossed.
2. **Player tactical positions**: 22 players fanned out for the side
   throw spot.
3. **Taker**: nearest same-side outfield player walks to the ball (smooth).
4. **Throw action**: short pass to a teammate within ~2 cells. **NO carry
   straight from a throw-in** (carrier must pass).
5. **No offside on throw-ins** (rule of football).

### 48.6 FREE KICK (also: indirect free kick after offside)

Triggered when a foul is committed (or an offside is whistled). The
**fouling/offending side** is penalised; the **non-offending side** takes
the kick.

Steps:
1. **Ball teleport**: to the spot of the foul (offence location).
2. **Taker**: nearest same-side player to the ball walks to it (smooth).
   If the spot is inside the shooting zone, the engine may also select the
   **best striker** as taker (per existing `DisciplineService.evaluateFoul`
   logic) so a20 m direct free kick can be shot.
3. **Player tactical positions**: all 22 players placed per
   `TacticsRules` for that spot.
4. **Taker action**: any legal action — PASS, CROSS, CENTER, CLEAR, SHOT,
   or even CARRY if the lane is open. **No offside exception** (a free kick
   after offside is no exception; passes from a free kick obey normal
   offside rules).
5. **Free kick awarded at the spot of the foul** — the `kickOffSpot` is
   the `state.getCarrier().getPosition()` when the foul was committed.

### 48.7 PENALTY KICK

Triggered when a foul is committed inside the offending team's penalty box.
The **fouled** side takes the kick against the **goalkeeper** of the
defending side.

Steps:
1. **Ball teleport**: to the penalty spot. For HOME's goal-defending
   side (AWAY attacking): `Position(7.5, 3.5)`. For AWAY's goal-defending
   side (HOME attacking): `Position(0.5, 3.5)`.
2. **Taker**: **best striker** of the fouled side walks to the spot
   (smooth).
3. **Other players**: all other players (except the GK and taker) move
   OUTSIDE the penalty box and arc behind the ball (per football rules:
   outside the 16m box, behind the penalty spot). The GK is on the goal
   line inside the goal area.
4. **GK behaviour**: 1-2 ticks before the strike, the GK may make a
   lateral move (left or right) along the goal line. Random
   50/50 direction + random step length (per existing
   `PenaltyKickEngine`/`executePenaltyKick` — refine to add the pre-strike
   shimmy).
5. **Taker action**: SHOT. The engine's existing `evaluateShot` /
   `executePenaltyKick` handles the timing.

### 48.8 Implementation order

1. Create `RestartManager.handleOOB(state, restartType, restartSpot, ...)`
   that does the new teleport + tactical placement + smooth taker walk.
2. Delete / weaken the `setActionDelayTicks(OOB_HOLD_TICKS)` calls in
   MatchSimulator — restarts happen instantly.
3. The existing `RESTART_WALK_MAX_TICKS`, `RESTART_WALK_SPEED`,
   `OOB_HOLD_TICKS` constants become obsolete; mark them `@Deprecated`.
4. Add ball-in-OOB `Position` clamp **only** for the duration of the
   restart placement step (so the ball starts ON the goal-line corner /
   on the field), not during `ballMovementEngine.followCarrier`.

### 48.9 Test plan

After implementation:
1. Generate a match. Find a goal kick, corner, throw-in, free kick, and
   penalty. Open the replay at each one and verify:
   - Ball is **not** visibly traversing the field.
   - The taker walks to the ball smoothly.
   - The match keeps ticking (no OOB freeze).
2. Run `MatchChainTrace` and `MatchBatchRunner`; assert no exceptions
   during 5+ matches.
3. Run unit tests: `RestartManagerTest`, `MatchSimulatorTest`.

### 48.10 What this section explicitly forbids

- ❌ Visible ball flight from OOB to the taker.
- ❌ `setActionDelayTicks(OOB_HOLD_TICKS)` after a restart.
- ❌ `RESTART_WALK_SPEED=0.7` slow-ball animation.
- ❌ Carrier walking the ball when the taker is actually a different player.
- ❌ Offside on corners.
- ❌ Offside on throw-ins.
- ❌ `RESTART_TELEPORT_DISTANCE=4.0` snap-overrides that don't make
  football sense.
