package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.BallState;
import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;

public final class PhysicsEngine {

    public record TransitParams(
        double startX, double startY,
        double targetX, double targetY,
        int maxTicks,
        String mode,
        long passerId, long receiverId, String passTeam,
        boolean interceptable
    ) {}

    public static TransitParams calculatePassTransit(MatchState state, Player passer, PlayerSnapshot passerPos, PlayerSnapshot receiverPos) {
        double distance = distance(passerPos, receiverPos);

        String mode = classifyMode(passerPos, receiverPos, distance);
        double passing = passer.skills().passing();
        double technique = passer.skills().technique();
        double quality = (passing * 0.55 + technique * 0.30 + passer.skills().pace() * 0.15) / 20.0;

        double speedFactor = switch (mode) {
            case "GROUND_PASS" -> 1.00;
            case "LOFTED_PASS" -> 0.90;
            case "CROSS" -> 0.82;
            default -> 1.0;
        };

        double travelPerTick = switch (mode) {
            case "GROUND_PASS" -> 2.85 + quality * 0.48 * speedFactor;
            case "LOFTED_PASS" -> 2.45 + quality * 0.40 * speedFactor;
            case "CROSS" -> 2.15 + quality * 0.34 * speedFactor;
            default -> 2.45;
        };
        int ticks = Math.max(2, Math.min("CROSS".equals(mode) ? 9 : 7,
            (int) Math.round(distance / travelPerTick)));

        return new TransitParams(
            state.ball.x(), state.ball.y(),
            receiverPos.x(), receiverPos.y(),
            ticks, mode,
            passer.id(), receiverPos.playerId(), state.teamSideOf(passer.id()),
            true
        );
    }

    private static final double OOB_MIN = 2.0;
    private static final double OOB_MAX = 98.0;

    public static TransitParams calculateDeflectTransit(MatchState state, double startX, double startY, double distance, double angle) {
        double targetX = clamp(startX + Math.cos(angle) * distance, OOB_MIN, OOB_MAX);
        double targetY = clamp(startY + Math.sin(angle) * distance, OOB_MIN, OOB_MAX);
        int ticks = Math.max(10, Math.min(25, (int) Math.round(distance / 0.6)));

        return new TransitParams(startX, startY, targetX, targetY, ticks, "DEFLECTION", -1, -1, null, false);
    }

    public static TransitParams calculateClearance(MatchState state, Player defender, double startX, double startY, String teamSide) {
        double travel = 18.0 + RNG() * 12.0;
        double targetX = "HOME".equals(teamSide)
            ? clamp(startX + travel, OOB_MIN, OOB_MAX)
            : clamp(startX - travel, OOB_MIN, OOB_MAX);
        double targetY;
        boolean nearTouchline = startY <= 30.0 || startY >= 70.0;
        if (nearTouchline && RNG() < 0.70) {
            // Wide clearances should sometimes drift over the sideline and become throw-ins.
            targetY = startY <= 50.0 ? OOB_MIN - 0.75 : OOB_MAX + 0.75;
            targetX = clamp(startX + (RNG() - 0.5) * 8.0, 14.0, 86.0);
        } else {
            targetY = clamp(startY + (RNG() - 0.5) * 28.0, OOB_MIN, OOB_MAX);
        }
        int ticks = Math.max(10, Math.min(25, (int) Math.round(travel / 0.56)));

        return new TransitParams(startX, startY, targetX, targetY, ticks, "CLEARANCE", defender.id(), -1, teamSide, false);
    }

    public static void updateBallTransit(MatchState state) {
        if (!state.ballInTransit) return;

        state.transitTicks++;
        double progress = Math.min(1.0, (double) state.transitTicks / state.transitMaxTicks);

        double rawX = state.transitStartX + (state.transitTargetX - state.transitStartX) * progress;
        double rawY = state.transitStartY + (state.transitTargetY - state.transitStartY) * progress;

        double z = 0;
        if ("LOFTED_PASS".equals(state.transitMode)) {
            z = 4.0 * 8.0 * progress * (1.0 - progress);
        } else if ("CROSS".equals(state.transitMode)) {
            z = 4.0 * 6.0 * progress * (1.0 - progress);
        }

        state.ball = BallState.at(rawX, rawY, z);
    }

    private static String classifyMode(PlayerSnapshot passer, PlayerSnapshot receiver, double distance) {
        boolean wideDelivery = Math.abs(passer.y() - 50.0) >= 24.0
            && Math.abs(receiver.y() - 50.0) <= 18.0
            && ("HOME".equals(passer.teamSide()) ? receiver.x() > passer.x() : receiver.x() < passer.x());
        if (wideDelivery && distance >= 15.0) return "CROSS";
        if (distance >= 18.0) return "LOFTED_PASS";
        return "GROUND_PASS";
    }

    private static double distance(PlayerSnapshot a, PlayerSnapshot b) {
        return Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double RNG() { return java.util.concurrent.ThreadLocalRandom.current().nextDouble(); }
}
