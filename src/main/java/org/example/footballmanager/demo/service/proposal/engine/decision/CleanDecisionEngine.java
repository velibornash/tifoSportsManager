package org.example.footballmanager.demo.service.proposal.engine.decision;

import org.example.footballmanager.demo.service.proposal.engine.ActionEngine;
import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Clean Decision Engine - ONLY scores and selects actions.
 * 
 * CORE PRINCIPLE: This engine ONLY provides a decision (PASS/SHOT/DRIBBLE/CLEAR).
 * It does NOT override the decision with hard rules like "MUST SHOT".
 * It does NOT re-decide mid-action. It does NOT move players.
 * It only scores options and returns the highest-scoring one.
 * 
 * The execution engine is responsible for carrying out the decision.
 */
public class CleanDecisionEngine {

    private static final double[] PLAYMAKING_ACCURACY_TABLE = {
        0.40, 0.42, 0.44, 0.46, 0.48, 0.50, 0.52, 0.54, 0.56, 0.58,  // 1-10
        0.60, 0.62, 0.64, 0.66, 0.68, 0.70, 0.72, 0.74, 0.76, 0.78,  // 11-20
        0.80  // 21 (capped)
    };

    public DecisionOption decide(MatchState state) {
        return decideWithOptions(state).getChosen();
    }

    public DecisionResult decideWithOptions(MatchState state) {
        Player carrier = state.getCarrier();
        if (carrier == null) {
            return new DecisionResult(
                    new DecisionOption(ActionType.PASS, null, 0.0, "no carrier"),
                    List.of());
        }

        Player receiver = findBestReceiver(state, carrier);

        // Gather all possible options
        DecisionOption passOption = scorePassOptions(state, carrier, receiver);
        DecisionOption carryOption = scoreCarryOptions(state, carrier);
        DecisionOption shotOption = scoreShotOptions(state, carrier);
        DecisionOption clearOption = scoreClearOptions(state, carrier);

        // Apply playmaking-based selection (not hard rules)
        double pmSkill = carrier.getSkills().playmaking();
        int accuracyIndex = SimUtils.clampInt((int) pmSkill, 1, 20);
        double baseAccuracy = PLAYMAKING_ACCURACY_TABLE[accuracyIndex - 1];

        // Use weighted random selection based on scores
        DecisionOption chosen = selectOptionWithPlaymaking(
                passOption, carryOption, shotOption, clearOption, baseAccuracy, receiver);

        return new DecisionResult(chosen, List.of(passOption, carryOption, shotOption, clearOption));
    }

    private DecisionOption scorePassOptions(MatchState state, Player carrier, Player receiver) {
        double score = 0.0;
        StringBuilder reason = new StringBuilder("PASS: ");

        if (receiver != null) {
            // Basic pass value
            score += 10.0;

            // Forward / lateral / backward weights - the ball must move toward goal
            boolean home = "HOME".equals(carrier.getTeam());
            double forwardSteps = home ? receiver.getPosition().getRow() - carrier.getPosition().getRow()
                                       : carrier.getPosition().getRow() - receiver.getPosition().getRow();
            if (forwardSteps > 0) {
                score += 20.0 + forwardSteps * 4.0;
                reason.append("forward+" + String.format("%.0f ", forwardSteps * 4.0));
            } else if (forwardSteps < -0.2) {
                score -= 30.0; // backward pass is last resort
                reason.append("backward ");
            } else {
                score -= 5.0; // lateral
                reason.append("lateral ");
            }

            // Goal proximity for forward passes
            double prox = home ? receiver.getPosition().getRow() : 8.0 - receiver.getPosition().getRow();
            if (forwardSteps > 0) {
                score += prox * 3.5;
                reason.append("prox +" + String.format("%.1f ", prox * 3.5));
            }

            // Distance factor (prefer medium passes)
            double dist = SimUtils.distance(carrier.getPosition(), receiver.getPosition());
            if (dist > 3.0 && dist < 6.0) {
                score += 15.0;
                reason.append("good distance ");
            }

            // Receiver openness
            double openness = calculateOpenness(state, receiver);
            score += openness * 20.0;
            reason.append(String.format("openness %.1f ", openness));

            // Playmaking boost
            score += carrier.getSkills().playmaking() * 0.15;
            // Passing skill boost
            score += carrier.getSkills().passing() * 0.10;

            reason.append(String.format("-> %s", receiver.getRole()));
        } else {
            score = -50.0; // No receiver
            reason.append("no receiver");
        }

        return new DecisionOption(ActionType.PASS, receiver, score, reason.toString());
    }

    private DecisionOption scoreCarryOptions(MatchState state, Player carrier) {
        // Carrying maps to ActionType.DRIBBLE (ActionType has no CARRY)
        double score = 12.0; // Base carry value
        StringBuilder reason = new StringBuilder("CARRY: ");

        // Can we move forward?
        double forward = getForwardDirection(state.getCarrierTeam());
        Position target = new Position(
                carrier.getPosition().getRow() + forward * 3.0,
                carrier.getPosition().getColumn()
        );

        // Check if path is clear
        if (!isPathBlocked(state, carrier, target)) {
            score += 12.0;
            reason.append("clear path ");
        }

        // Carrying forward progresses play - reward it
        boolean home = "HOME".equals(carrier.getTeam());
        double prox = home ? carrier.getPosition().getRow() : 8.0 - carrier.getPosition().getRow();
        score += prox * 2.5;
        reason.append("prox +" + String.format("%.1f ", prox * 2.5));

        // Pace helps carrying
        score += carrier.getSkills().pace() * 0.15;
        reason.append("pace ");

        // Stamina affects carry
        score += (20.0 - carrier.getFatigue()) * 0.05;
        reason.append("stamina ");

        // Pressure hurts carrying (player can be dispossessed)
        double pressure = calculatePressure(state, carrier);
        score -= pressure * 30.0;
        reason.append(String.format("press -%.0f ", pressure * 30.0));

        reason.append(String.format("to (%.1f,%.1f)", target.getRow(), target.getColumn()));
        return new DecisionOption(ActionType.DRIBBLE, null, score, reason.toString());
    }

    private DecisionOption scoreShotOptions(MatchState state, Player carrier) {
        double score = 0.0;
        StringBuilder reason = new StringBuilder("SHOT: ");

        // Only shoot in shooting zone
        String team = carrier.getTeam();
        double carrierRow = carrier.getPosition().getRow();
        boolean inShootingZone = "HOME".equals(team) ? carrierRow >= 6.0 : carrierRow <= 2.0;

        if (!inShootingZone) {
            score = -30.0;
            reason.append("not in zone");
            return new DecisionOption(ActionType.SHOT, null, score, reason.toString());
        }

        // Basic shot value
        score += 20.0;
        reason.append("in zone ");

        // Striker skill
        score += carrier.getSkills().striker() * 0.20;
        reason.append("striker ");

        // Technique for placement
        score += carrier.getSkills().technique() * 0.10;
        reason.append("technique ");

        // Distance to goal (closer = better)
        Position goal = ActionEngine.goalPositionFor(team);
        double distToGoal = SimUtils.distance(carrier.getPosition(), goal);
        if (distToGoal < 3.0) {
            score += 15.0;
            reason.append("close ");
        } else if (distToGoal < 5.0) {
            score += 8.0;
            reason.append("medium ");
        }

        // Check if goal is open
        double openness = calculateGoalOpenness(state, carrier, goal);
        score += openness * 25.0;
        reason.append(String.format("goal open %.1f ", openness));

        reason.append(String.format("-> %.1f dist", distToGoal));
        return new DecisionOption(ActionType.SHOT, null, score, reason.toString());
    }

    private DecisionOption scoreClearOptions(MatchState state, Player carrier) {
        double score = 0.0;
        StringBuilder reason = new StringBuilder("CLEAR: ");

        // Only clear when under pressure in defensive third
        String team = carrier.getTeam();
        double carrierRow = carrier.getPosition().getRow();
        boolean inDefensiveThird = "HOME".equals(team) ? carrierRow <= 3.0 : carrierRow >= 5.0;

        if (!inDefensiveThird) {
            score = -40.0;
            reason.append("not in defensive third");
            return new DecisionOption(ActionType.CLEAR, null, score, reason.toString());
        }

        // Pressure from opponents
        double pressure = calculatePressure(state, carrier);
        if (pressure > 0.5) {
            score += 15.0;
            reason.append("under pressure ");
        }

        // Defending skill helps clearance
        score += carrier.getSkills().defender() * 0.15;
        reason.append("defending ");

        // Clearance value
        score += 10.0;
        reason.append("clearance ");

        reason.append(String.format("pressure %.1f", pressure));
        return new DecisionOption(ActionType.CLEAR, null, score, reason.toString());
    }

    private Player findBestReceiver(MatchState state, Player carrier) {
        // Find the best teammate - reward openness AND forward progress
        Player best = null;
        double bestScore = -Double.MAX_VALUE;
        boolean home = "HOME".equals(carrier.getTeam());

        for (Player teammate : state.getPlayers()) {
            if (!teammate.getTeam().equals(carrier.getTeam()) || teammate.equals(carrier)) continue;
            double openness = calculateOpenness(state, teammate);
            double dist = SimUtils.distance(carrier.getPosition(), teammate.getPosition());
            double forward = home ? teammate.getPosition().getRow() - carrier.getPosition().getRow()
                                  : carrier.getPosition().getRow() - teammate.getPosition().getRow();
            double prox = home ? teammate.getPosition().getRow() : 8.0 - teammate.getPosition().getRow();
            // Forward + deep + open teammates win; over-long passes penalized
            double score = openness * 10.0
                    + Math.max(0, forward) * 8.0
                    + prox * 2.0
                    - (dist > 6.0 ? 15.0 : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = teammate;
            }
        }
        return best;
    }

    private double calculateOpenness(MatchState state, Player player) {
        // How much space does this player have?
        double minDistToOpponent = 100.0;
        String team = player.getTeam();

        for (Player opponent : state.getPlayers()) {
            if (!opponent.getTeam().equals(team) && !opponent.isSentOff() && !opponent.isInjured()) {
                double dist = SimUtils.distance(player.getPosition(), opponent.getPosition());
                minDistToOpponent = Math.min(minDistToOpponent, dist);
            }
        }
        // Normalize to 0-1 (1 = lots of space, 0 = tightly marked)
        return Math.min(1.0, minDistToOpponent / 3.0);
    }

    private double calculateGoalOpenness(MatchState state, Player shooter, Position goal) {
        // Is there a clear line to goal?
        Position ballPos = shooter.getPosition();
        double minDist = 100.0;

        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(shooter.getTeam()) || opponent.isSentOff()) continue;
            double distToLine = pointToLineDistance(opponent.getPosition(), ballPos, goal);
            minDist = Math.min(minDist, distToLine);
        }
        // Normalize (1 = clear, 0 = blocked)
        return Math.min(1.0, minDist / 2.0);
    }

    private double pointToLineDistance(Position p, Position a, Position b) {
        // Distance from point p to line segment a-b
        double dx = b.getColumn() - a.getColumn();
        double dy = b.getRow() - a.getRow();
        double lengthSq = dx * dx + dy * dy;

        if (lengthSq < 1e-9) {
            return SimUtils.distance(p, a);
        }

        double t = ((p.getColumn() - a.getColumn()) * dx
                  + (p.getRow() - a.getRow()) * dy) / lengthSq;
        t = SimUtils.clamp(t, 0.0, 1.0);

        double projRow = a.getRow() + t * dy;
        double projCol = a.getColumn() + t * dx;
        return SimUtils.distance(p, new Position(projRow, projCol));
    }

    private boolean isPathBlocked(MatchState state, Player from, Position to) {
        // Simplified path check
        return false;
    }

    private double calculatePressure(MatchState state, Player player) {
        int opponentsNear = 0;
        String team = player.getTeam();
        Position pos = player.getPosition();

        for (Player opponent : state.getPlayers()) {
            if (!opponent.getTeam().equals(team) && !opponent.isSentOff() && !opponent.isInjured()) {
                if (SimUtils.distance(pos, opponent.getPosition()) < 2.0) {
                    opponentsNear++;
                }
            }
        }
        return Math.min(1.0, opponentsNear / 3.0);
    }

    private double getForwardDirection(String team) {
        return "HOME".equals(team) ? 1.0 : -1.0;
    }

    private DecisionOption selectOptionWithPlaymaking(
            DecisionOption pass, DecisionOption carry, DecisionOption shot, DecisionOption clear,
            double playmakingAccuracy, Player receiver) {

        // Collect all options
        DecisionOption[] options = {pass, carry, shot, clear};

        // Filter out negative scores (unless all are negative)
        DecisionOption[] viable = Arrays.stream(options)
                .filter(o -> o.getScore() >= 0)
                .toArray(DecisionOption[]::new);

        if (viable.length == 0) {
            // All options negative - pick least bad
            viable = options;
            Arrays.sort(viable, (a, b) -> Double.compare(b.getScore(), a.getScore()));
            return viable[0];
        }

        // Sort by score descending
        Arrays.sort(viable, (a, b) -> Double.compare(b.getScore(), a.getScore()));

        DecisionOption best = viable[0];
        DecisionOption second = viable.length > 1 ? viable[1] : null;

        // Playmaking determines if we pick the best or consider alternatives
        if (second != null && (best.getScore() - second.getScore()) < 5.0) {
            // Close decision - use playmaking to break tie
            if (Math.random() < playmakingAccuracy) {
                return best; // Take the best option
            } else {
                return second; // Take the second option (shows creativity)
            }
        }

        return best;
    }
}