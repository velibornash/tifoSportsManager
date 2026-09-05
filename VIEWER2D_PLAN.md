# Viewer 2D — Plan: Human Figures instead of Circles

## Goal

Replace the circle-based player rendering in the demo/service match viewer with
**animated human figures** (jersey, running motion, kicking, sliding). The
existing `viewer.js` / `index.html` stay untouched; we build a parallel
`viewer2d.js` + `index2d.html` so both can coexist while the new renderer
matures.

**Home team = black jersey, Away team = white jersey, GK = gold.**

## Files

| File | Purpose |
|---|---|
| `src/main/resources/static/demo/service/ui/js/viewer2d.js` | NEW — full viewer with human-figure rendering |
| `src/main/resources/static/demo/service/ui/index2d.html` | NEW — HTML shell loading `viewer2d.js` (copy of `index.html`, same elements/IDs, same CSS) |

No changes to `viewer.js`, `index.html`, `pitch.css`, or any Java code.

## 1. The Human Figure — `PlayerFigure` module (drawn on Canvas2D, no image assets)

We draw figures with the Canvas2D API so there are **zero external assets** and
the color/number are dynamic. Each figure is an articulated skeleton. All joints
are defined **relative to the feet-center root** at `(0,0)`, facing +X axis
(right). The figure is translated to the player position and rotated to face the
movement direction.

### Figure proportions (at scale 1.0)

```
         ◯  <- head (circle r=5)
        /  \
      /      \  <- shoulders (width 14)
     |        |
     \  torso /   jersey color fill, number printed here
      |  8   |
    hip (0, -20)
     |     \
  thigh    thigh
   (10)     (10)
   knee    knee
  shin     shin
   foot    foot
```

- Total height ≈ 38 px (vs old circle diameter 28–36 px — same footprint)
- **Head**: circle radius 5, skin tone (light tan), small darker hair-top arc
- **Torso**: filled polygon (shoulder line → hip line) in jersey color
  - HOME jersey = near-black `#20242c` with subtle white stroke
  - AWAY jersey = light gray/white `#f0f0f0` with dark stroke
  - GK jersey = gold `#f0b429`
  - **Number** printed on the torso (contrast color)
- **Arms**: 2-joint limbs (shoulder → elbow → hand), drawn as rounded strokes,
  sleeve-colored for the upper half (jersey color), skin for the lower
- **Legs**: 2-joint limbs (hip → knee → foot) in shorts color (darker shade of
  jersey) + skin shin + black shoe
- **Shadow**: soft ellipse under the figure (keeps the "grounded" feel)

### Poses (joint configurations)

| Pose | Description |
|---|---|
| `IDLE` | Standing, arms slightly bent at sides, legs straight |
| `WALK` | Slow leg swing, minimal arm motion (used below run speed) |
| `RUN_1` | Left leg forward, right leg back, arms opposite (frame A) |
| `RUN_2` | Right leg forward, left leg back, arms opposite (frame B) |
| `KICK` | Right leg extended forward, left leg planted, arms spread for balance |
| `SLIDE` | Body leaning back ~40°, both legs extended forward, arms back |
| `CELEBRATE` | Both arms raised, slight jump (goal celebrations) |

### Running animation

- The renderer tracks each player's movement direction + speed (from the
  ***velocityX / velocityY*** fields already in `PlayerSnapshot`).
- When speed > threshold: alternate `RUN_1 ↔ RUN_2`, cycle period inversely
  proportional to speed — faster = quicker steps.
- When speed < threshold but > 0: `WALK`.
- When ~0: `IDLE`.
- On `KICK` event: single-frame kick pose triggered by SHOT/PASS events.
- On `DUEL` with slide outcome: brief `SLIDE` pose.

### Facing direction

- Angle = `atan2(velocityY, velocityX)` (or target−position if no velocity).
- Canvas rotation applied around the figure root. Home/away ground-truth
  direction is already in the data (row 1 = HOME goal line, dribble direction
  always toward opponent goal) → the figure naturally faces the right way.

## 2. `PitchRenderer2D` (replaces `PitchRenderer`)

Same constructor contract/message surface as `PitchRenderer`, so `MatchViewer2D`
diffs minimally. Differences:

- `drawPitch()` — **identical** (pitch, stripes, lines, boxes, goals, labels).
- `drawPlayers(players, carrierId, duelPairs)` — rewritten:
  - Same duel-convergence pre-pass (keep the visual "players touch during
    duel" behavior) but instead of overlapping circles, the two figures are
    pulled together and get a **pulsing yellow contact ring** + the `DUEL`
    pose (tackling defender leans in).
  - Each player drawn via `PlayerFigure.draw(ctx, {...})`:
    - team color → jersey
    - pose from current animation state (`RunSprite` per player)
    - rotation from movement direction
    - carrier → orange ground ring + slightly "active" brightening
    - GK → gold jersey + distinct keeper pose (`KICK` when punching)
    - cooldown loser → faint red ring (unchanged)
    - jersey number: white-on-black home, black-on-white away, dark-on-gold GK
  - Scale figures to fit cell size: player figure height = `min(CELL_H, CELL_W) * 0.30` so 22 figures never overlap pitch lines.

## 3. `MatchViewer2D` (replaces `MatchViewer`)

Copies the entire playback/data layer from `MatchViewer` **verbatim**
(loading, interpolation, timeline, overlays, controls, seek, speed). The class
definition is identical except:

- `this.pitch = new PitchRenderer2D(...)` instead of `PitchRenderer`
- A `_playerSprites` map (playerId → `PlayerMotion`) maintained across
  frames:
  - On each `_renderFrame`, feed each player's `(oldRow,oldCol)` → `(newRow,newCol)`
    delta + snapshot velocity into the motion tracker.
  - The tracker computes `pose`, `facingAngle`, `runPhase`, `isKicking`,
    `isSliding` and passes them to the renderer.
- Kicking trigger: on `SHOT` / `PASS` / `PENALTY_KICK` / `CROSS` events, find
  the acting player and set `kickUntil = tick + 6` on their tracker (fixed
  `KICK` pose for ~150 ms).
- Slide trigger: on `TACKLE` / `DUEL_RESOLVED (DEFENDER_WINS)` set
  `slideUntil`.

## 4. `index2d.html`

Byte-for-byte copy of `index.html` except the final `<script>` tag:
`<script src="js/viewer2d.js"></script>`. Uses existing `pitch.css`.

## 5. What's NOT changed

- `viewer.js` and `index.html` (original circle renderer stays available)
- `pitch.css` (shared by both viewers)
- All Java recording/engine code — `PlayerSnapshot` already carries
  `velocityX`/`velocityY`/`target` we need for facing + pose.

## 6. Implementation order

1. `PlayerFigure` core — joint config, pose definitions, jersey rendering,
   shadow, number. Static test: draw each pose once on a test overlay.
2. Run cycle — `PlayerMotion` tracker (idle/walk/run, frame alternation,
   facing angle).
3. `PitchRenderer2D.drawPlayers()` — wire figures into the existing render loop.
4. `MatchViewer2D` + event-driven poses (kick, slide, celebrate).
5. `index2d.html` + manual QA in browser against exported `match.json`.
6. Tuning pass — figure size relative to pitch, animation cadence, jersey
   contrast so black-vs-white reads instantly at a glance.

## 7. Open questions for the user

- **Number legibility**: black figure on dark pitch — confirm black jersey +
  white number is enough, or add a faint outline/halo behind figures near
  opponents.
- **Ball**: keep the plain round ball, or draw a small sphere with pentagon
  pattern (cosmetic, trivial either way)?
- **Scale sanity check**: figure height ≈ 30% of a cell (≈ 35 px at cell
  196×120). If too small/large, one constant to tune.