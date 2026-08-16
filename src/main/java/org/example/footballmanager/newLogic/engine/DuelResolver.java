package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public final class DuelResolver {

    private static final Random RNG = new Random();

    public DuelResolver() {}

    /**
     * Pass outcome resolver. Determines what actually happened to a played
     * pass — completed, intercepted, misplaced (inaccurate), or deflected /
     * driven out of bounds — using skill-weighted probabilities. Pure
     * resolution: the caller (simulator) is responsible for events, stats and
     * state transitions. Returns the landing point for each outcome so the
     * ball always travels to a real location (no teleportation).
     */
    public PassResolution resolvePass(PlayerSnapshot carrier, PlayerSnapshot receiver,
                                      java.util.List<PlayerSnapshot> allSnapshots) {
        double passQuality = (carrier.passing() + carrier.technique()) / 40.0;
        double distance = carrier.distanceTo(receiver);

        // Interception by the nearest opponent marking the receiver zone
        double interceptChance = 0.08 * (1.0 - passQuality);
        if (RNG.nextDouble() < interceptChance) {
            PlayerSnapshot interceptor = findNearestOpponent(receiver, carrier.teamSide(), allSnapshots);
            if (interceptor != null && interceptor.distanceTo(receiver) < 6.0) {
                return new PassResolution(PassOutcomeType.INTERCEPTED, interceptor,
                    interceptor.x(), interceptor.y(), distance);
            }
        }

        // Misplaced pass: the ball flies to an error spot and lands loose
        double inaccuracyChance = 0.12 * (2.0 - passQuality);
        if (RNG.nextDouble() < inaccuracyChance) {
            double errX = clamp(receiver.x() + (RNG.nextDouble() - 0.5) * 8.0,
                MatchState.MIN_X, MatchState.MAX_X);
            double errY = clamp(receiver.y() + (RNG.nextDouble() - 0.5) * 8.0,
                 MatchState.MIN_Y, MatchState.MAX_Y);
            return new PassResolution(PassOutcomeType.INACCURATE, null, errX, errY, distance);
        }

        // Out of bounds: forced mis-hit (set piece generator) or deflection by
        // a defender crowding the receiver. Restarts are handled by the rules
        // engine when the ball crosses the line — no instant repositioning.
        int nearbyBlockers = 0;
        for (PlayerSnapshot opp : allSnapshots) {
            if (opp.teamSide().equals(carrier.teamSide())) continue;
            if (opp.distanceTo(receiver) < 4.0) nearbyBlockers++;
        }
        boolean forcedOut = RNG.nextDouble() < 0.18;
        boolean deflectedOut = nearbyBlockers > 0
            && RNG.nextDouble() < (0.15 + nearbyBlockers * 0.05);
        if (forcedOut || deflectedOut) {
            double ox = receiver.x() + (receiver.x() - carrier.x()) * 0.4;
            double oy = receiver.y() + (receiver.y() - carrier.y()) * 0.4;
            if (RNG.nextDouble() < 0.5) {
                oy = RNG.nextBoolean() ? MatchState.MAX_Y + 2.0 : MatchState.MIN_Y - 2.0;
            } else {
                ox = RNG.nextBoolean() ? MatchState.MAX_X + 2.0 : MatchState.MIN_X - 2.0;
            }
            return new PassResolution(PassOutcomeType.OUT_OF_BOUNDS, null, ox, oy, distance);
        }

        return new PassResolution(PassOutcomeType.COMPLETED, null,
            receiver.x(), receiver.y(), distance);
    }

    private static PlayerSnapshot findNearestOpponent(PlayerSnapshot from, String teamSide,
                                                      java.util.List<PlayerSnapshot> allSnapshots) {
        PlayerSnapshot nearest = null;
        double minDist = Double.MAX_VALUE;
        for (PlayerSnapshot snap : allSnapshots) {
            if (snap.teamSide().equals(teamSide)) continue;
            double d = from.distanceTo(snap);
            if (d < minDist) {
                minDist = d;
                nearest = snap;
            }
        }
        return nearest;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public enum PassOutcomeType {
        COMPLETED, INTERCEPTED, INACCURATE, OUT_OF_BOUNDS
    }

    public record PassResolution(PassOutcomeType outcome, PlayerSnapshot interceptor,
                                 double x, double y, double distance) {}


    public DuelResult resolveShotDuel(PlayerSnapshot shooter, PlayerSnapshot goalkeeper, double distance, double xG) {
        double shootSkill = shooter.shooting() + shooter.technique();
        double gkSkill = goalkeeper != null ? goalkeeper.defending() + (goalkeeper instanceof PlayerSnapshot ? 10 : 0) : 5;

        // Longer shots miss the target more often (real football: off-target rate
        // climbs with distance). Roughly a third of all shots are on target.
        double missChance = 0.42 + Math.max(0, (distance - 8.0)) * 0.03;
        missChance = Math.min(0.80, missChance);

        double saveChance = 0.45 + (gkSkill / 40.0) * 0.30;

        double adjustedXG = xG * (0.5 + (shootSkill / 40.0) * 0.3);

        double r = RNG.nextDouble();
        if (r < missChance) {
            return new DuelResult(false, "MISSED", adjustedXG, false, false);
        }
        boolean saved = RNG.nextDouble() < saveChance;
        boolean isGoal = !saved && RNG.nextDouble() < adjustedXG;

        return new DuelResult(isGoal, isGoal ? "GOAL" : saved ? "SAVED" : "MISSED", adjustedXG, isGoal, saved);
    }

    public DuelResult resolveOpenGoalShot(PlayerSnapshot shooter, double distance, double xG) {
        double shootSkill = shooter.shooting() + shooter.technique();
        double adjustedXG = Math.min(0.70, xG * 1.2 * (0.6 + (shootSkill / 40.0) * 0.3)); // Reduced multiplier
        boolean isGoal = RNG.nextDouble() < adjustedXG;
        return new DuelResult(isGoal, isGoal ? "GOAL" : "MISSED", adjustedXG, isGoal, false);
    }

    public DuelResult resolveTackleDuel(PlayerSnapshot attacker, PlayerSnapshot defender) {
        return resolveTackle(attacker, defender);
    }

    public static DuelResult resolveTackle(PlayerSnapshot attacker, PlayerSnapshot defender) {
        double attackPower = attacker.technique() + attacker.pace() + attacker.dribbling();
        double defendPower = defender.defending() + defender.pace();

        double attackChance = attackPower / (attackPower + defendPower);
        attackChance = Math.max(0.25, Math.min(0.75, attackChance));

        boolean attackerWins = RNG.nextDouble() < attackChance;
        return new DuelResult(attackerWins, attackerWins ? "TACKLE_WON" : "TACKLE_LOST", attackChance);
    }

    public static DuelResult resolvePenalty(PlayerSnapshot shooter, PlayerSnapshot goalkeeper) {
        double shootSkill = shooter.shooting() + shooter.technique();
        double gkSkill = goalkeeper != null ? goalkeeper.defending() : 10;

        double goalChance = 0.76 * (0.8 + (shootSkill / 40.0) * 0.4);
        double saveChance = 0.35 * (0.6 + (gkSkill / 20.0) * 0.8);
        goalChance *= (1.0 - saveChance);

        boolean isGoal = RNG.nextDouble() < goalChance;
        boolean saved = !isGoal && RNG.nextDouble() < saveChance;
        return new DuelResult(isGoal, isGoal ? "PENALTY_GOAL" : saved ? "PENALTY_SAVED" : "PENALTY_MISSED", goalChance, isGoal, saved);
    }

    public DuelResult resolveHeaderDuel(PlayerSnapshot attacker, PlayerSnapshot defender) {
        double attackPower = attacker.technique() + attacker.shooting();
        double defendPower = defender.defending() + defender.technique();

        double attackChance = attackPower / (attackPower + defendPower);
        attackChance = Math.max(0.25, Math.min(0.75, attackChance));

        boolean attackerWins = RNG.nextDouble() < attackChance;
        return new DuelResult(attackerWins, attackerWins ? "HEADER_WON" : "HEADER_LOST", attackChance);
    }

    public DuelResult resolveLooseBallDuel(PlayerSnapshot player1, PlayerSnapshot player2) {
        double power1 = player1.pace() + player1.technique();
        double power2 = player2.pace() + player2.technique();

        double chance1 = power1 / (power1 + power2);
        chance1 = Math.max(0.25, Math.min(0.75, chance1));

        boolean player1Wins = RNG.nextDouble() < chance1;
        return new DuelResult(player1Wins, player1Wins ? "LOOSE_BALL_WON" : "LOOSE_BALL_LOST", chance1);
    }

    public DuelResult resolveNumericDuel(PlayerSnapshot carrier, java.util.List<PlayerSnapshot> attackers,
                                          java.util.List<PlayerSnapshot> defenders) {
        if (defenders.isEmpty()) {
            return new DuelResult(true, "CARRIER_WINS", 0.9);
        }
        if (attackers.isEmpty()) {
            return new DuelResult(false, "TACKLE_WON", 0.9);
        }

        double attackPower = attackers.stream()
            .mapToDouble(a -> a.technique() + a.pace() + a.dribbling())
            .sum();
        double defendPower = defenders.stream()
            .mapToDouble(d -> d.defending() + d.pace())
            .sum();

        int attackCount = attackers.size();
        int defendCount = defenders.size();

        if (attackCount > defendCount) {
            attackPower *= 1.0 + (attackCount - defendCount) * 0.25;
        } else if (defendCount > attackCount) {
            defendPower *= 1.0 + (defendCount - attackCount) * 0.25;
        }

        double carrierChance = attackPower / (attackPower + defendPower);
        carrierChance = Math.max(0.15, Math.min(0.85, carrierChance));

        boolean carrierWins = RNG.nextDouble() < carrierChance;
        return new DuelResult(carrierWins,
            carrierWins ? "CARRIER_WINS" : "TACKLE_WON", carrierChance);
    }

    /**
     * Resolves what happens when a shot misses — does it go out for a corner
     * or a goal kick? This is pure resolution: the caller already decided to
     * shoot, we just determine the physical outcome.
     */
    public ShotMissOutcome resolveShotMissOutcome(PlayerSnapshot shooter, double shotX, double shotY) {
        boolean home = shooter.teamSide().equals("HOME");
        boolean nearOpponentGoal = home ? shotX > 85 : shotX < 15;
        boolean nearOwnGoal = home ? shotX < 15 : shotX > 85;

        // Shot went out near opponent's goal line → attacking team gets corner
        if (nearOpponentGoal) {
            return new ShotMissOutcome(true, "CORNER");
        }
        // Shot went out near own goal line → defending team gets goal kick
        if (nearOwnGoal) {
            return new ShotMissOutcome(false, "GOAL_KICK");
        }

        // Random direction for shots from distance
        if (RNG.nextDouble() < 0.5) {
            return new ShotMissOutcome(true, "CORNER");
        } else {
            return new ShotMissOutcome(true, "GOAL_KICK");
        }
    }

    /**
     * Resolves cross accuracy — does the cross find its target or go out?
     * Pure resolution: the carrier already decided to cross.
     */
    public CrossOutcome resolveCrossOutcome(PlayerSnapshot carrier, double crossQuality) {
        boolean accurate = RNG.nextDouble() < (0.4 + crossQuality * 0.4);

        if (!accurate) {
            // 15% chance goes out for goal kick regardless of accuracy
            if (RNG.nextDouble() < 0.15) {
                return new CrossOutcome(false, true, "GOAL_KICK");
            }
            return new CrossOutcome(false, false, "LOOSE");
        }

        // 15% chance accurate cross still goes out for goal kick
        if (RNG.nextDouble() < 0.15) {
            return new CrossOutcome(true, true, "GOAL_KICK");
        }

        return new CrossOutcome(true, false, "ACCURATE");
    }

    /**
     * Resolves clearance — does the defender find a teammate or clear to a random spot?
     */
    public ClearanceOutcome resolveClearanceOutcome(PlayerSnapshot carrier, double clearQuality) {
        boolean findsTeammate = RNG.nextDouble() < clearQuality * 0.3;

        if (findsTeammate) {
            PlayerSnapshot teammate = findBestClearanceTarget(carrier);
            if (teammate != null) {
                return new ClearanceOutcome(true, teammate.playerId(), teammate.x(), teammate.y());
            }
        }

        double targetX = carrier.teamSide().equals("HOME") ? 70.0 : 30.0;
        double targetY = 50.0 + (RNG.nextDouble() - 0.5) * 40.0;
        return new ClearanceOutcome(false, -1, targetX, targetY);
    }

    private PlayerSnapshot findBestClearanceTarget(PlayerSnapshot carrier) {
        // This is a simplified version — the full logic is in MatchSimulator
        return null;
    }

    public record ShotMissOutcome(boolean attackingTeamGetsRestart, String restartType) {}
    public record CrossOutcome(boolean accurate, boolean goalKick, String result) {}
    public record ClearanceOutcome(boolean foundTeammate, long teammateId, double targetX, double targetY) {}

    public record DuelResult(boolean attackerWins, String resultType, double xG, boolean goal, boolean saved) {
        public DuelResult(boolean attackerWins, String resultType, double probability) {
            this(attackerWins, resultType, probability, false, false);
        }
    }
}
