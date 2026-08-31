package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;

/**
 * Goalkeeper reactive positioning — an override of the tactical-editor target for
 * BOTH goalkeepers. Uses the exact continuous pitch position of the ball, the
 * shooter and the goal (the 7-row x 6-col grid maps to 98m x 60m, so a full cell
 * is ~14m x 10m — nowhere near fine enough to decide a goalkeeper's footwork).
 *
 * Positioning rules (from the user):
 *  1. Base: goal line, column 3.5 (centre of the goal).
 *  2. As the ball moves left/right of 3.5, the keeper shifts slightly toward the
 *     ball's side to cut the angle (laterally).
 *  3. When an opponent attacker can / is preparing to shoot, the keeper edges
 *     slightly toward them and lines up BETWEEN the ball and the goal.
 *  4. Right after a save the keeper is on a short cooldown (3-4 ticks) — they
 *     cannot save twice within ~2 seconds, and they hold position rather than
 *     lunging again.
 */
public class GoalkeeperMovementEngine {

    private static final double GOAL_CENTRE_COL = 3.5;
    // Lateral band the keeper may roam (never leaves the near goal area).
    private static final double COL_MIN = 1.8;
    private static final double COL_MAX = 5.2;
    // Row band around the goal line per team.
    // 16m penalty area = 16/14 ≈ 1.14 cells from goal line.
    // Tightened bounds to keep GK inside 16m mark (HOME goal = row 1, AWAY = row 8).
    private static final double HOME_ROW_MIN = 0.9;
    private static final double HOME_ROW_MAX = 2.0;   // 14m from goal, inside 16m arc
    private static final double AWAY_ROW_MIN = 6.86;   // 16m from AWAY goal at row 8.0 (edge of penalty area)
    private static final double AWAY_ROW_MAX = 7.9;    // just inside own 6-yard box
    // Reaction: how close the ball must be to our goal for the keeper to move.
    private static final double REACT_DIST = 4.0;
    // Max distance off the goal line the keeper advances toward a shooter.
    private static final double MAX_ADVANCE = 0.7;
    // GK cannot save twice within this many ticks (~2 seconds at 1 tick = 1.5s).
    public static final int SAVE_COOLDOWN_TICKS = 4;

    private final MatchState state;

    public GoalkeeperMovementEngine(MatchState state) {
        this.state = state;
    }

    /**
     * Reactive target for the given goalkeeper, overriding the tactical editor.
     *
     * Strategy (exact continuous coordinates — the grid is 14m/cell, far too
     * coarse for a keeper's footwork):
     *  1. Home position: on the goal line at column 3.5 (centre of goal).
     *  2. As the ball approaches our goal, the keeper slides along the line that
     *     runs from the BALL to the GOAL CENTRE, i.e. it positions itself between
     *     ball and goal to cut the angle. This maximises lane coverage.
     *  3. How far off the line the keeper steps depends on how close and how much
     *     of an immediate shooting threat the ball is.
     *  4. After a save the keeper is on a short cooldown (3-4 ticks) and holds a
     *     conservative centre position rather than lunging again.
     */
    public Position goalkeeperTarget(Player gk) {
        boolean home = "HOME".equals(gk.getTeam());
        double goalLineRow = home ? 1.0 : 8.0;
        Position goalCentre = new Position(goalLineRow, GOAL_CENTRE_COL);
        Position ball = state.getBall().getPosition();
        int tick = state.getMatchTicks();

        // The ball is a threat only when it is on our side of the pitch.
        boolean ballOnOurSide = home ? ball.getRow() <= 5.5 : ball.getRow() >= 2.5;

        double row = goalLineRow;
        double col = GOAL_CENTRE_COL;

        if (ballOnOurSide) {
            double ballDistGoal = SimUtils.distance(ball, goalCentre);
            // closeness: 1 when the ball is at our goal line, ~0 beyond REACT_DIST.
            double closeness = SimUtils.clamp(1.0 - ballDistGoal / REACT_DIST, 0, 1);
            // An opponent actually carrying the ball within REACT_DIST of our goal
            // is an immediate shooting threat -> keeper steps further off the line.
            Player carrier = state.getBall().getCarrier();
            boolean shooterThreat = carrier != null
                    && !carrier.getTeam().equals(gk.getTeam())
                    && !carrier.isSentOff() && !carrier.isInjured()
                    && ballDistGoal <= REACT_DIST;

            // Pull the keeper onto the ball->goal segment toward the ball.
            //  - general play: small pull (angle-cut), keeper mostly on the line;
            //  - immediate shooter threat: bigger pull (comes out to narrow angle).
            double pull = shooterThreat
                    ? 0.30 + 0.50 * closeness
                    : 0.12 + 0.30 * closeness;
            pull = Math.min(pull, MAX_ADVANCE);   // never run past the box

            double dr = ball.getRow() - goalLineRow;
            double dc = ball.getColumn() - GOAL_CENTRE_COL;
            row = goalLineRow + dr * pull;
            col = GOAL_CENTRE_COL + dc * pull;
        }

        // ----- 4) Save cooldown: after a save, hold a conservative centre. -----
        if (tick - gk.getLastSaveTick() < SAVE_COOLDOWN_TICKS) {
            row = goalLineRow;
            col = GOAL_CENTRE_COL;
        }

        double rowMin = home ? HOME_ROW_MIN : AWAY_ROW_MIN;
        double rowMax = home ? HOME_ROW_MAX : AWAY_ROW_MAX;
        return new Position(
                SimUtils.clamp(row, rowMin, rowMax),
                SimUtils.clamp(col, COL_MIN, COL_MAX));
    }
}
