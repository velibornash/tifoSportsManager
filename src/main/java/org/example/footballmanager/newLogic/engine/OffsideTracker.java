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

    private int checkCounter = 0;

    public void update(MatchState state) {
        checkCounter++;
        if (checkCounter % 60 != 0) return;

        if (state.carrierId == null || state.ballInTransit) return;

        String attackingTeam = state.carrierTeamSide;
        if (attackingTeam == null) return;
        String defendingTeam = "HOME".equals(attackingTeam) ? "AWAY" : "HOME";

        double offsideLine = calculateOffsideLine(state, defendingTeam);

        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(attackingTeam)) continue;
            if (snap.position() == Position.GK) continue;

            boolean isOffside = isPlayerOffside(snap, attackingTeam, offsideLine, state.ball.x());

            if (isOffside && !flaggedOffside.contains(snap.playerId())) {
                flaggedOffside.add(snap.playerId());
                state.addEvent(new OffsideEvent(state.minute, state.tick,
                    snap.playerId(), snap.name(), attackingTeam));
            } else if (!isOffside) {
                flaggedOffside.remove(snap.playerId());
            }
        }
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
