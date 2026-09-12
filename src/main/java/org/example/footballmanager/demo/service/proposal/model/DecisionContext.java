package org.example.footballmanager.demo.service.proposal.model;

import java.util.List;

/** Decision context - the state of the game when a decision is being made. */
public class DecisionContext {
    private final Player carrier;
    private final Ball ball;
    private final List<Player> teammates;
    private final List<Player> opponents;
    private final double pressure;
    private final double danger;
    private final boolean inOpponentHalf;
    private final boolean isOffside;
    private final boolean isRestartFirstTouch;

    public DecisionContext(Player carrier, Ball ball, List<Player> teammates,
                            List<Player> opponents, double pressure, double danger,
                            boolean inOpponentHalf, boolean isOffside,
                            boolean isRestartFirstTouch) {
        this.carrier = carrier;
        this.ball = ball;
        this.teammates = teammates;
        this.opponents = opponents;
        this.pressure = pressure;
        this.danger = danger;
        this.inOpponentHalf = inOpponentHalf;
        this.isOffside = isOffside;
        this.isRestartFirstTouch = isRestartFirstTouch;
    }

    public Player getCarrier() { return carrier; }
    public Ball getBall() { return ball; }
    public List<Player> getTeammates() { return teammates; }
    public List<Player> getOpponents() { return opponents; }
    public double getPressure() { return pressure; }
    public double getDanger() { return danger; }
    public boolean isInOpponentHalf() { return inOpponentHalf; }
    public boolean isOffside() { return isOffside; }
    public boolean isRestartFirstTouch() { return isRestartFirstTouch; }
}