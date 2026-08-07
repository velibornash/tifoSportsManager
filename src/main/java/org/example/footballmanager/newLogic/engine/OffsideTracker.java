package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class OffsideTracker {

    private static final Logger log = LoggerFactory.getLogger(OffsideTracker.class);

    private final Set<Long> flaggedOffside = new HashSet<>();

    public OffsideTracker() {}

    public Long checkOffsideOnPass(MatchState state, PlayerSnapshot passer, PlayerSnapshot receiver) {
        if (passer == null || receiver == null) return null;
        if (!passer.teamSide().equals(receiver.teamSide())) return null; // Must be same team

        String attackingTeam = passer.teamSide();
        String defendingTeam = "HOME".equals(attackingTeam) ? "AWAY" : "HOME";

        double offsideLine = calculateOffsideLine(state, defendingTeam);

        boolean home = attackingTeam.equals("HOME");
        boolean isForwardPass = home ? receiver.x() > passer.x() : receiver.x() < passer.x();
        if (!isForwardPass) return null;

        boolean isOffside = isPlayerOffside(receiver, attackingTeam, offsideLine, state.ball.x());

        if (isOffside && !flaggedOffside.contains(receiver.playerId())) {
            flaggedOffside.add(receiver.playerId());
            state.addEvent(new OffsideEvent(state.minute, state.tick,
                receiver.playerId(), receiver.name(), attackingTeam));
            if (state.simulatorMetrics != null) state.simulatorMetrics.onOffside();

            // Reset all attackers to behind offside line
            resetAttackersBehindOffsideLine(state, attackingTeam, offsideLine);

            // Trigger goal kick for defending team
            state.stoppage = MatchState.StoppageType.GOAL_KICK;
            state.stoppageTicks = 5;
            state.possessionTeam = defendingTeam;

            return receiver.playerId();
        }

        return null;
    }

    private void resetAttackersBehindOffsideLine(MatchState state, String attackingTeam, double offsideLine) {
        boolean home = attackingTeam.equals("HOME");
        double resetX = home ? offsideLine - 2.0 : offsideLine + 2.0; // 2 units behind offside line

        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(attackingTeam)) continue;
            if (snap.position() == Position.GK) continue;

            // Only reset attackers who are ahead of offside line
            boolean aheadOfLine = home ? snap.x() > offsideLine : snap.x() < offsideLine;
            if (aheadOfLine) {
                double newX = Math.max(0, Math.min(100, resetX));
                double newY = Math.max(0, Math.min(100, snap.y()));
                // Blend back to the offside line gradually - never teleport players
                MovementEngine.startBlend(state, snap.playerId(), newX, newY, 40);
            }
        }
    }

    public void update(MatchState state) {
        // Legacy update — no longer used for tick-based checks
    }

    public boolean isOffside(MatchState state, PlayerSnapshot snap) {
        String attackingTeam = snap.teamSide();
        String defendingTeam = "HOME".equals(attackingTeam) ? "AWAY" : "HOME";
        double offsideLine = calculateOffsideLine(state, defendingTeam);
        return isPlayerOffside(snap, attackingTeam, offsideLine, state.ball.x());
    }

    private double calculateOffsideLine(MatchState state, String defendingTeam) {
        List<Double> defenderXPositions = new ArrayList<>();

        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(defendingTeam)) continue;
            if (snap.position() == Position.GK) continue;
            defenderXPositions.add(snap.x());
        }

        if (defenderXPositions.isEmpty()) return 0;

        defenderXPositions.sort(Comparator.reverseOrder());

        if (defenderXPositions.size() >= 2) {
            return defenderXPositions.get(1);
        }
        return defenderXPositions.get(0);
    }

    private boolean isPlayerOffside(PlayerSnapshot snap, String attackingTeam, double offsideLine, double ballX) {
        if ("HOME".equals(attackingTeam)) {
            return snap.x() > offsideLine && snap.x() > ballX;
        } else {
            return snap.x() < offsideLine && snap.x() < ballX;
        }
    }

    public void reset() {
        flaggedOffside.clear();
    }
}