package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.BallState;
import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.event.*;

import java.util.List;
import java.util.Random;

public final class SetPieceHandler {

    private static final Random RNG = new Random();
    private static final double MIN_X = MatchState.MIN_X, MAX_X = MatchState.MAX_X;
    private static final double MIN_Y = MatchState.MIN_Y, MAX_Y = MatchState.MAX_Y;

    public record RestartParams(
        String mode,
        double ballX, double ballY,
        long takerId, String teamSide,
        MatchState.StoppageType stoppage,
        int pauseTicks,
        String setPieceType
    ) {}

    public static RestartParams handleGoalKick(MatchState state, int minute) {
        String restartTeam = state.oppositeTeam(state.lastTouchTeam);
        Player goalkeeper = findGoalkeeper(state, restartTeam);
        long gkId = goalkeeper != null ? goalkeeper.id() : -1;
        double ballX = "HOME".equals(restartTeam) ? 8.0 : 92.0;
        double ballY = 50.0;

        state.addEvent(new SetPieceEvent(minute, state.tick, restartTeam, gkId,
            goalkeeper != null ? goalkeeper.name() : null, SetPieceEvent.SetPieceType.GOAL_KICK, ballX, ballY));

        return new RestartParams("GOAL_KICK", ballX, ballY, gkId, restartTeam, MatchState.StoppageType.GOAL_KICK, 25, "GOAL_KICK");
    }

    public static RestartParams handleCorner(MatchState state, int minute) {
        String restartTeam = state.lastTouchTeam;
        boolean upper = state.ball.y() < 50;

        // Place ball in corner
        double ballX = "HOME".equals(restartTeam) ? 99.0 : 1.0;
        double ballY = upper ? 1.0 : 99.0;

        Player taker = findSetPieceTaker(state, restartTeam);
        long takerId = taker != null ? taker.id() : -1;

        state.addEvent(new SetPieceEvent(minute, state.tick, restartTeam, takerId,
            taker != null ? taker.name() : null, SetPieceEvent.SetPieceType.CORNER, ballX, ballY));

        return new RestartParams("CORNER", ballX, ballY, takerId, restartTeam, MatchState.StoppageType.CORNER, 40, "CORNER");
    }

    public static RestartParams handleThrowIn(MatchState state, int minute) {
        String restartTeam = state.oppositeTeam(state.lastTouchTeam);
        double ballX = clamp(state.ball.x(), 8.0, 92.0);
        double ballY = state.ball.y() <= 50 ? 6.5 : 93.5;

        Player taker = findSetPieceTaker(state, restartTeam);
        long takerId = taker != null ? taker.id() : -1;

        state.addEvent(new SetPieceEvent(minute, state.tick, restartTeam, takerId,
            taker != null ? taker.name() : null, SetPieceEvent.SetPieceType.THROW_IN, ballX, ballY));

        return new RestartParams("THROW_IN", ballX, ballY, takerId, restartTeam, MatchState.StoppageType.THROW_IN, 30, "THROW_IN");
    }

    public static RestartParams handleFreeKick(MatchState state, int minute, double foulX, double foulY, String attackingTeam) {
        Player taker = findSetPieceTaker(state, attackingTeam);
        long takerId = taker != null ? taker.id() : -1;

        boolean direct = DecisionEngine.estimateGoalDistance(taker != null ? taker : state.homePlayers().getFirst(), state, attackingTeam) <= 24.0;

        state.addEvent(new SetPieceEvent(minute, state.tick, attackingTeam, takerId,
            taker != null ? taker.name() : null, SetPieceEvent.SetPieceType.FREE_KICK, foulX, foulY));

        return new RestartParams("FREE_KICK", foulX, foulY, takerId, attackingTeam, MatchState.StoppageType.FREE_KICK, 30, "FREE_KICK");
    }

    public static RestartParams handlePenalty(MatchState state, int minute, long fouledPlayerId) {
        String attackingTeam = state.teamSideOf(fouledPlayerId);
        boolean attacksRight = "HOME".equals(attackingTeam);
        String defendingTeam = state.oppositeTeam(attackingTeam);

        Player taker = findPenaltyTaker(state, attackingTeam);
        Player goalkeeper = findGoalkeeper(state, defendingTeam);

        double spotX = attacksRight ? 88.0 : 12.0;
        double keeperX = attacksRight ? 96.0 : 4.0;

        // Place taker and goalkeeper
        MovementEngine.startBlend(state, taker.id(), spotX, 50.0, 40);
        MovementEngine.startBlend(state, goalkeeper.id(), keeperX, 50.0, 40);

        // Place other players outside box
        placePenaltySupportPlayers(state, attackingTeam, taker, goalkeeper);

        state.pendingPenaltyTakerId = taker.id();

        state.addEvent(new PenaltyEvent(minute, state.tick, taker.id(), taker.name(),
            attackingTeam, false, false, 0.76));

        // The actual resolution happens after pause in the match loop
        return new RestartParams("PENALTY", spotX, 50.0, taker.id(), attackingTeam, MatchState.StoppageType.PENALTY, 50, "PENALTY");
    }

    public static void resolveCornerDelivery(MatchState state, int minute) {
        String attackingTeam = state.lastTouchTeam;
        String defendingTeam = state.oppositeTeam(attackingTeam);

        double targetX = "HOME".equals(attackingTeam) ? 85.0 : 15.0;
        double targetY = 50.0 + (RNG.nextDouble() - 0.5) * 30.0;

        // GK interception — punch if close enough to the delivery target
        Player goalkeeper = findGoalkeeper(state, defendingTeam);
        if (goalkeeper != null) {
            var gkSnap = state.snapshotById(goalkeeper.id());
            if (gkSnap != null) {
                double gkDist = gkSnap.distanceToPoint(targetX, targetY);
                double gkSkill = goalkeeper.skills().goalkeeping() / 20.0;
                if (gkDist <= 3.0 + gkSkill * 1.5 && RNG.nextDouble() < 0.25 + gkSkill * 0.45) {
                    // GK punches/catches the corner
                    MovementEngine.startBlend(state, goalkeeper.id(), targetX, targetY, 20);
                    state.carrierId = goalkeeper.id();
                    state.carrierTeamSide = defendingTeam;
                    state.lastTouchTeam = defendingTeam;
                    state.ball = BallState.at(targetX, targetY, 1.0);
                    state.addEvent(new DuelEvent(minute, state.tick, -1, "GK", -1, "",
                        defendingTeam, true, "PUNCH"));
                    return;
                }
            }
        }

        Player attacker = findNearest(state, attackingTeam, targetX, targetY);
        Player defender = findNearest(state, defendingTeam, targetX, targetY);

        if (attacker != null && defender != null) {
            var result = DuelResolver.resolveTackle(attacker, defender);
            state.addEvent(new DuelEvent(minute, state.tick, attacker.id(), attacker.name(),
                defender.id(), defender.name(), attackingTeam, result.attackerWins(), "HEADER"));

            if (result.attackerWins()) {
                MovementEngine.startBlend(state, attacker.id(), targetX, targetY, 30);
                state.carrierId = attacker.id();
                state.carrierTeamSide = attackingTeam;
                state.lastTouchTeam = attackingTeam;
                state.ball = BallState.at(targetX, targetY, 2.0);
            } else {
                MovementEngine.startBlend(state, defender.id(), targetX, targetY, 30);
                // Clearance
                var clearance = PhysicsEngine.calculateClearance(state, defender, targetX, targetY, defendingTeam);
                startTransit(state, clearance);
                state.lastTouchTeam = defendingTeam;
            }
        }
    }

    public static void startTransit(MatchState state, PhysicsEngine.TransitParams tp) {
        state.ballInTransit = true;
        state.transitStartX = tp.startX();
        state.transitStartY = tp.startY();
        state.transitTargetX = tp.targetX();
        state.transitTargetY = tp.targetY();
        state.transitMaxTicks = tp.maxTicks();
        state.transitMode = tp.mode();
        state.pendingReceiverId = tp.receiverId() >= 0 ? tp.receiverId() : null;
        state.pendingPasserId = tp.passerId() >= 0 ? tp.passerId() : null;
        state.pendingPassTeam = tp.passTeam();
        state.transitInterceptable = tp.interceptable();
        state.transitTicks = 0;
    }

    private static Player findGoalkeeper(MatchState state, String team) {
        List<Player> players = "HOME".equals(team) ? state.homePlayers() : state.awayPlayers();
        return players.stream().filter(p -> p.position() == Position.GK).findFirst().orElse(null);
    }

    private static Player findSetPieceTaker(MatchState state, String team) {
        List<Player> players = "HOME".equals(team) ? state.homePlayers() : state.awayPlayers();
        return players.stream().filter(p -> p.position() != Position.GK).findFirst().orElse(null);
    }

    private static Player findPenaltyTaker(MatchState state, String team) {
        List<Player> players = "HOME".equals(team) ? state.homePlayers() : state.awayPlayers();
        return players.stream()
            .filter(p -> p.position() == Position.ATT || p.position() == Position.WNG)
            .max(java.util.Comparator.comparingInt(p -> p.skills().shooting()))
            .orElseGet(() -> players.stream().filter(p -> p.position() != Position.GK).findFirst().orElse(null));
    }

    private static Player findNearest(MatchState state, String team, double x, double y) {
        List<Player> players = "HOME".equals(team) ? state.homePlayers() : state.awayPlayers();
        return players.stream()
            .filter(p -> {
                var s = state.snapshotById(p.id());
                return s != null && s.distanceToPoint(x, y) <= 18.0;
            })
            .min(java.util.Comparator.comparingDouble(p -> {
                var s = state.snapshotById(p.id());
                return s != null ? s.distanceToPoint(x, y) : Double.MAX_VALUE;
            }))
            .orElse(null);
    }

    private static void placePenaltySupportPlayers(MatchState state, String attackingTeam, Player taker, Player goalkeeper) {
        double targetX = "HOME".equals(attackingTeam) ? 79.2 : 20.8;
        double[] lanes = {30.0, 38.0, 46.0, 54.0, 62.0, 70.0};

        for (String team : List.of(attackingTeam, state.oppositeTeam(attackingTeam))) {
            List<Player> players = "HOME".equals(team) ? state.homePlayers() : state.awayPlayers();
            int idx = 0;
            for (var p : players) {
                if (p.id() == taker.id() || p.id() == goalkeeper.id()) continue;
                double y = lanes[idx % lanes.length];
                MovementEngine.startBlend(state, p.id(), targetX + (idx % 2 == 0 ? -1.1 : 1.1), y, 40);
                idx++;
            }
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
