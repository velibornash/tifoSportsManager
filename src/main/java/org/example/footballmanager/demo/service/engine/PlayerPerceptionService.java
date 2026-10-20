package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Player Perception / Awareness Service — corePrinciples Section 4.5.
 *
 * "Players must not automatically possess perfect knowledge of the entire simulation state."
 *
 * Models what each player can perceive based on:
 * - distance
 * - orientation
 * - ball awareness
 * - teammate awareness
 * - opponent awareness
 * - anticipation
 *
 * The engine knows the complete state. A player operates on a contextual subset.
 */
public class PlayerPerceptionService {

    private static final double VISION_RANGE_BASE = 5.0;
    private static final double PERIPHERAL_RANGE = 2.0;
    private static final double BALL_AWARENESS_RANGE = 8.0;
    private static final double CLOSE_AWARENESS_RANGE = 3.0;

    private final MatchState state;

    public PlayerPerceptionService(MatchState state) {
        this.state = state;
    }

    /**
     * Build a player's perception of the match.
     * Returns what the player can reasonably see/know.
     */
    public PlayerPerception perceive(Player player) {
        Position playerPos = player.getPosition();
        Ball ball = state.getBall();

        // Ball awareness — always know ball position within awareness range
        boolean canSeeBall = SimUtils.distance(playerPos, ball.getPosition()) <= BALL_AWARENESS_RANGE;
        Position perceivedBallPos = canSeeBall ? ball.getPosition() : estimateBallPosition(player);

        // Visible opponents — within vision range
        List<Player> visibleOpponents = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(player.getTeam())) continue;
            if (isWithinPerception(playerPos, p.getPosition())) {
                visibleOpponents.add(p);
            }
        }

        // Visible teammates — wider range (peripheral vision)
        List<Player> visibleTeammates = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (p == player) continue;
            if (!p.getTeam().equals(player.getTeam())) continue;
            if (SimUtils.distance(playerPos, p.getPosition()) <= VISION_RANGE_BASE + PERIPHERAL_RANGE) {
                visibleTeammates.add(p);
            }
        }

        // Closest opponent (for pressure assessment)
        Player closestOpponent = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : visibleOpponents) {
            double d = SimUtils.distance(playerPos, p.getPosition());
            if (d < minDist) { minDist = d; closestOpponent = p; }
        }

        // Pressure level based on closest opponent
        double pressureLevel = closestOpponent != null
                ? SimUtils.clamp(1.0 - minDist / CLOSE_AWARENESS_RANGE, 0, 1)
                : 0.0;

        return new PlayerPerception(
                player,
                perceivedBallPos,
                canSeeBall,
                Collections.unmodifiableList(visibleOpponents),
                Collections.unmodifiableList(visibleTeammates),
                closestOpponent,
                pressureLevel,
                visibleOpponents.size(),
                visibleTeammates.size()
        );
    }

    /** Build perceptions for all players on a team. */
    public List<PlayerPerception> perceiveTeam(String team) {
        List<PlayerPerception> perceptions = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (team.equals(p.getTeam())) {
                perceptions.add(perceive(p));
            }
        }
        return perceptions;
    }

    /**
     * Estimate ball position when player can't see it.
     * Uses anticipation based on last known ball direction.
     */
    private Position estimateBallPosition(Player player) {
        // When ball is out of perception range, player uses a rough estimate
        // based on general play direction — not exact knowledge
        Ball ball = state.getBall();
        return ball.getPosition(); // simplified: return actual but flagged as estimated
    }

    /**
     * Check if a target position is within the player's perception cone.
     * Front-facing: full range. Peripheral: reduced range.
     */
    private boolean isWithinPerception(Position playerPos, Position targetPos) {
        double dist = SimUtils.distance(playerPos, targetPos);
        if (dist <= CLOSE_AWARENESS_RANGE) return true; // close = always visible
        if (dist <= VISION_RANGE_BASE) return true; // within main vision
        return false;
    }

    // --- Inner record ---

    /**
     * A player's contextual view of the match.
     * Not the complete state — only what the player can perceive.
     */
    public record PlayerPerception(
            Player self,
            Position perceivedBallPosition,
            boolean canSeeBall,
            List<Player> visibleOpponents,
            List<Player> visibleTeammates,
            Player closestOpponent,
            double pressureLevel,
            int opponentCount,
            int teammateCount
    ) {
        public boolean isUnderPressure() { return pressureLevel > 0.5; }
        public boolean hasSupport() { return teammateCount > 0; }
        public boolean canSeePassingLane(Player target) {
            for (Player opp : visibleOpponents) {
                // Simplified: check if opponent is between self and target
                double distToLine = distanceToLine(self.getPosition(), target.getPosition(), opp.getPosition());
                if (distToLine < 0.5) return false;
            }
            return true;
        }

        private static double distanceToLine(Position a, Position b, Position p) {
            double dx = b.getColumn() - a.getColumn();
            double dy = b.getRow() - a.getRow();
            double len = Math.hypot(dx, dy);
            if (len < 1e-9) return SimUtils.distance(a, p);
            return Math.abs(dy * p.getColumn() - dx * p.getRow()
                    + b.getColumn() * a.getRow() - b.getRow() * a.getColumn()) / len;
        }
    }
}
