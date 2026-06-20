package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;

public final class OffsideTracker {

    public static boolean isOffside(MatchState state, PlayerSnapshot receiver) {
        boolean homeAttack = "HOME".equals(receiver.teamSide());
        boolean inOppositionHalf = homeAttack ? receiver.x() > 50.0 : receiver.x() < 50.0;
        if (!inOppositionHalf) return false;

        double offsideLine = calculateOffsideLine(state, receiver.teamSide());
        boolean aheadOfBall = isAheadOfBall(state, receiver, homeAttack);

        boolean beyondLine = homeAttack ? receiver.x() > offsideLine + 1.5 : receiver.x() < offsideLine - 1.5;
        return aheadOfBall && beyondLine;
    }

    private static double calculateOffsideLine(MatchState state, String attackingTeam) {
        String defendingTeam = state.oppositeTeam(attackingTeam);
        var defenders = "HOME".equals(defendingTeam) ? state.homeSnapshots() : state.awaySnapshots();
        var xPositions = defenders.stream()
            .filter(s -> s.position() != org.example.footballmanager.newLogic.model.Position.GK)
            .mapToDouble(PlayerSnapshot::x)
            .sorted()
            .toArray();

        if (xPositions.length == 0) return "HOME".equals(attackingTeam) ? 95.0 : 5.0;

        return "HOME".equals(attackingTeam) ? xPositions[xPositions.length - 1] : xPositions[0];
    }

    private static boolean isAheadOfBall(MatchState state, PlayerSnapshot receiver, boolean homeAttack) {
        PlayerSnapshot carrier = state.ballCarrierSnapshot();
        if (carrier == null) return true;
        return homeAttack ? receiver.x() > carrier.x() + 0.5 : receiver.x() < carrier.x() - 0.5;
    }
}
