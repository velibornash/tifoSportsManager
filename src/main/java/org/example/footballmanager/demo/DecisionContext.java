package org.example.footballmanager.demo;

import java.util.List;

/**
 * Immutable snapshot of the game state at the moment a carrier must decide.
 *
 * <p>Built once per decision by {@link PlaymakingDecisionEngine#buildContext}.
 * All derived flags (isHome, isGoalkeeper, inFinalThird, onWing, etc.) mirror
 * the exact same conditions that the old random-selection code in
 * {@code SimulationStepEngine} used to compute (lines ~180–192), so the
 * decision layer is a drop-in evolution — never a behavioral override of those
 * constraints.</p>
 *
 * <p>The {@code playmaking} field (1–20) is the carrier's own
 * {@link PlayerSkills#playmaking()} value and drives both vision tiers and
 * decision accuracy in the engine.</p>
 */
public final class DecisionContext {

    private final Player player;
    private final Ball.BallState ballState;
    private final Position ballPosition;
    private final List<Player> teammates;
    private final List<Player> opponents;
    private final double pressure;
    private final double danger;
    private final double fieldPosition;   // rows from own goal (1..7 scale)
    private final double playmaking;
    private final boolean isHome;
    private final boolean isGoalkeeper;
    private final boolean inFinalThird;
    private final boolean onWing;
    private final boolean inOpponentHalf;
    private final boolean canShoot;
    private final boolean isKickoff;
    private final List<DecisionOption> options;

    public DecisionContext(Player player,
                           Ball.BallState ballState,
                           Position ballPosition,
                           List<Player> teammates,
                           List<Player> opponents,
                           double pressure,
                           double danger,
                           double fieldPosition,
                           double playmaking,
                           boolean isHome,
                           boolean isGoalkeeper,
                           boolean inFinalThird,
                           boolean onWing,
                           boolean inOpponentHalf,
                           boolean canShoot,
                           boolean isKickoff,
                           List<DecisionOption> options) {
        this.player = player;
        this.ballState = ballState;
        this.ballPosition = ballPosition;
        this.teammates = teammates;
        this.opponents = opponents;
        this.pressure = pressure;
        this.danger = danger;
        this.fieldPosition = fieldPosition;
        this.playmaking = playmaking;
        this.isHome = isHome;
        this.isGoalkeeper = isGoalkeeper;
        this.inFinalThird = inFinalThird;
        this.onWing = onWing;
        this.inOpponentHalf = inOpponentHalf;
        this.canShoot = canShoot;
        this.isKickoff = isKickoff;
        this.options = options;
    }

    public Player player() { return player; }
    public Ball.BallState ballState() { return ballState; }
    public Position ballPosition() { return ballPosition; }
    public List<Player> teammates() { return teammates; }
    public List<Player> opponents() { return opponents; }
    public double pressure() { return pressure; }
    public double danger() { return danger; }
    public double fieldPosition() { return fieldPosition; }
    public double playmaking() { return playmaking; }
    public boolean isHome() { return isHome; }
    public boolean isGoalkeeper() { return isGoalkeeper; }
    public boolean inFinalThird() { return inFinalThird; }
    public boolean onWing() { return onWing; }
    public boolean inOpponentHalf() { return inOpponentHalf; }
    public boolean canShoot() { return canShoot; }
    public boolean isKickoff() { return isKickoff; }
    public List<DecisionOption> options() { return options; }
}
