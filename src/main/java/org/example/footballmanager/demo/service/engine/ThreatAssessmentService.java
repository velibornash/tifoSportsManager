package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Threat Assessment Service — corePrinciples Section 4.4 / Section 6.
 *
 * Evaluates contextual danger from:
 * - ball position and carrier
 * - opponent proximity and movement
 * - passing lanes
 * - goal proximity
 * - numerical superiority
 * - urgency
 *
 * Threat is contextual and dynamic. A threat can override normal tactical positioning
 * when its urgency exceeds the player's current tactical responsibility.
 */
public class ThreatAssessmentService {

    private static final double PRESSING_RANGE = 1.5;
    private static final double DANGER_ZONE_ROW_HOME = 6.0; // close to away goal
    private static final double DANGER_ZONE_ROW_AWAY = 2.0; // close to home goal
    private static final double DEFENSIVE_SUPPORT_RANGE = 3.0;

    private final MatchState state;

    public ThreatAssessmentService(MatchState state) {
        this.state = state;
    }

    /**
     * Evaluate overall threat level for a player's team.
     * Considers ball position, opponent proximity, and match context.
     */
    public ThreatAssessment evaluateTeamThreat(String team) {
        double ballThreat = evaluateBallThreat(team);
        double proximityThreat = evaluateOpponentProximityThreat(team);
        double dangerZoneThreat = evaluateDangerZoneThreat(team);
        double numericalThreat = evaluateNumericalThreat(team);

        double total = ballThreat * 0.35
                     + proximityThreat * 0.25
                     + dangerZoneThreat * 0.25
                     + numericalThreat * 0.15;

        ThreatLevel level = ThreatLevel.fromScore(total);
        String explanation = buildTeamThreatExplanation(team, total, level,
                ballThreat, proximityThreat, dangerZoneThreat, numericalThreat);

        return new ThreatAssessment(total, level, explanation,
                ballThreat, proximityThreat, dangerZoneThreat, numericalThreat);
    }

    /**
     * Evaluate threat for a specific player.
     * Includes personal pressure from nearby opponents.
     */
    public PlayerThreat evaluatePlayerThreat(Player player) {
        String team = player.getTeam();
        ThreatAssessment teamThreat = evaluateTeamThreat(team);

        double personalPressure = calculatePersonalPressure(player);
        boolean shouldOverrideTactics = shouldOverrideTactics(player, teamThreat, personalPressure);

        PlayerIntent overrideIntent = null;
        if (shouldOverrideTactics) {
            overrideIntent = determineOverrideIntent(player, teamThreat, personalPressure);
        }

        return new PlayerThreat(
                teamThreat.totalScore(),
                teamThreat.level(),
                personalPressure,
                shouldOverrideTactics,
                overrideIntent,
                teamThreat.explanation()
        );
    }

    /** Evaluate threats for all players on a team. */
    public List<PlayerThreat> evaluateAllPlayers(String team) {
        List<PlayerThreat> threats = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (team.equals(p.getTeam())) {
                threats.add(evaluatePlayerThreat(p));
            }
        }
        return threats;
    }

    // --- Private evaluation methods ---

    private double evaluateBallThreat(String team) {
        Ball ball = state.getBall();
        if (ball.getCarrier() == null) return 0.3; // loose ball = moderate threat

        String carrierTeam = ball.getCarrier().getTeam();
        if (team.equals(carrierTeam)) return 0.0; // we have the ball = no defensive threat

        // Opponent has ball — how dangerous is their position?
        Position ballPos = ball.getPosition();
        double ballRow = ballPos.getRow();

        // Ball near our goal = high threat
        if ("HOME".equals(team)) {
            return SimUtils.clamp((ballRow - 1.0) / 6.0, 0, 1);
        } else {
            return SimUtils.clamp((7.0 - ballRow) / 6.0, 0, 1);
        }
    }

    private double evaluateOpponentProximityThreat(String team) {
        Ball ball = state.getBall();
        if (ball.getCarrier() == null) return 0.2;

        String carrierTeam = ball.getCarrier().getTeam();
        if (team.equals(carrierTeam)) return 0.0;

        // Count opponents in dangerous positions (near our goal)
        int dangerousOpponents = 0;
        for (Player p : state.getPlayers()) {
            if (!team.equals(p.getTeam())) {
                boolean nearGoal = "HOME".equals(team)
                        ? p.getPosition().getRow() >= DANGER_ZONE_ROW_HOME
                        : p.getPosition().getRow() <= DANGER_ZONE_ROW_AWAY;
                if (nearGoal) dangerousOpponents++;
            }
        }
        return SimUtils.clamp(dangerousOpponents / 4.0, 0, 1);
    }

    private double evaluateDangerZoneThreat(String team) {
        Ball ball = state.getBall();
        Position ballPos = ball.getPosition();

        double dangerScore;
        if ("HOME".equals(team)) {
            dangerScore = SimUtils.clamp((ballPos.getRow() - 4.0) / 4.0, 0, 1);
        } else {
            dangerScore = SimUtils.clamp((4.0 - ballPos.getRow()) / 4.0, 0, 1);
        }
        return dangerScore;
    }

    private double evaluateNumericalThreat(String team) {
        Ball ball = state.getBall();
        if (ball.getCarrier() == null) return 0.0;

        String carrierTeam = ball.getCarrier().getTeam();
        if (team.equals(carrierTeam)) return 0.0;

        // Count players in our defensive third
        int defenders = 0;
        int attackers = 0;
        for (Player p : state.getPlayers()) {
            boolean inOurThird = "HOME".equals(team)
                    ? p.getPosition().getRow() >= 5
                    : p.getPosition().getRow() <= 3;
            if (inOurThird) {
                if (team.equals(p.getTeam())) defenders++;
                else attackers++;
            }
        }
        if (defenders == 0) return 1.0;
        return SimUtils.clamp(1.0 - (double) defenders / Math.max(1, attackers), 0, 1);
    }

    private double calculatePersonalPressure(Player player) {
        Ball ball = state.getBall();
        if (ball.getCarrier() == null) return 0.0;
        if (player.getTeam().equals(ball.getCarrier().getTeam())) return 0.0;

        double pressure = 0;
        for (Player p : state.getPlayers()) {
            if (player.getTeam().equals(p.getTeam())) continue;
            double dist = SimUtils.distance(player.getPosition(), p.getPosition());
            if (dist < PRESSING_RANGE) {
                pressure += (PRESSING_RANGE - dist) / PRESSING_RANGE;
            }
        }
        return SimUtils.clamp(pressure, 0, 1);
    }

    private boolean shouldOverrideTactics(Player player, ThreatAssessment teamThreat, double personalPressure) {
        // GK never overrides
        if ("GK".equals(player.getRole())) return false;

        // Offside retreat: after 2+ consecutive offsides, force player to drop back
        if (player.getConsecutiveOffsideCount() >= 2) return true;

        // High threat + high personal pressure = override
        if (teamThreat.level().severity() >= 0.75 && personalPressure > 0.5) return true;

        // Critical team threat = override for defenders and midfielders
        if (teamThreat.level() == ThreatLevel.CRITICAL) {
            return player.roleLine().equals("DEF") || player.roleLine().equals("MID");
        }

        // Very high personal pressure regardless of team threat
        if (personalPressure > 0.8) return true;

        return false;
    }

    private PlayerIntent determineOverrideIntent(Player player, ThreatAssessment teamThreat, double personalPressure) {
        Ball ball = state.getBall();
        Position ballPos = ball.getPosition();
        Position playerPos = player.getPosition();

        // Offside retreat: force player to drop back toward own half
        if (player.getConsecutiveOffsideCount() >= 2) {
            return PlayerIntent.RETURN_TO_SHAPE;
        }

        boolean ballOnOurSide = "HOME".equals(player.getTeam())
                ? ballPos.getRow() >= 5
                : ballPos.getRow() <= 3;

        if (ballOnOurSide && personalPressure > 0.6) {
            return PlayerIntent.PROVIDE_DEFENSIVE_COVER;
        }

        if (teamThreat.ballThreat() > 0.6) {
            double distToBall = SimUtils.distance(playerPos, ballPos);
            if (distToBall < DEFENSIVE_SUPPORT_RANGE) {
                return PlayerIntent.TRACK_RUNNER;
            }
            return PlayerIntent.PROVIDE_DEFENSIVE_COVER;
        }

        if (personalPressure > 0.5) {
            return PlayerIntent.PRESS;
        }

        return PlayerIntent.RETURN_TO_SHAPE;
    }

    private String buildTeamThreatExplanation(String team, double total, ThreatLevel level,
                                              double ball, double proximity, double danger, double numerical) {
        return String.format("%s threat: %.2f (%s) | ball=%.2f proximity=%.2f dangerZone=%.2f numerical=%.2f",
                team, total, level, ball, proximity, danger, numerical);
    }

    // --- Inner records ---

    public record ThreatAssessment(
            double totalScore,
            ThreatLevel level,
            String explanation,
            double ballThreat,
            double proximityThreat,
            double dangerZoneThreat,
            double numericalThreat
    ) {}

    public record PlayerThreat(
            double teamThreatScore,
            ThreatLevel teamThreatLevel,
            double personalPressure,
            boolean shouldOverrideTactics,
            PlayerIntent overrideIntent,
            String teamThreatExplanation
    ) {}
}
