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

# 47. Implementation Rule

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

# 48. Final Principle

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
