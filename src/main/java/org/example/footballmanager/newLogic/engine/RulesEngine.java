package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.event.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class RulesEngine {

    private static final Random RNG = new Random();

    public void checkFoul(MatchState state, PlayerSnapshot defender, PlayerSnapshot attacker) {
        // Don't award penalty if currently in offside stoppage
        if (state.stoppage == MatchState.StoppageType.GOAL_KICK) {
            return;
        }

        if (RNG.nextDouble() < 0.35) {
            state.addEvent(new FoulEvent(state.minute, state.tick,
                defender.playerId(), defender.name(),
                attacker.playerId(), attacker.name(),
                defender.teamSide(), false, defender.x(), defender.y()));

            if (isInPenaltyBox(defender, attacker)) {
                state.addEvent(new PenaltyEvent(state.minute, state.tick,
                    attacker.playerId(), attacker.name(), attacker.teamSide(),
                    false, false, 0.76));
                state.stoppage = MatchState.StoppageType.PENALTY;
                state.stoppageTicks = 8;
            } else {
                // Outside box — free kick stoppage should fire more reliably
                if (RNG.nextDouble() < 0.60) {
                    state.stoppage = MatchState.StoppageType.FREE_KICK;
                    state.stoppageTicks = 4;
                }
            }

            if (RNG.nextDouble() < 0.3) {
                boolean secondYellow = state.playerYellowCards.getOrDefault(defender.playerId(), 0) > 0;
                CardEvent.CardType cardType = secondYellow ? CardEvent.CardType.RED : CardEvent.CardType.YELLOW;
                state.addEvent(new CardEvent(state.minute, state.tick,
                    defender.playerId(), defender.name(), defender.teamSide(), cardType));

                if (secondYellow) {
                    state.sentOffPlayers.add(defender.playerId());
                } else {
                    state.playerYellowCards.merge(defender.playerId(), 1, Integer::sum);
                }
            }
        }
    }

    public void checkGoal(MatchState state, PlayerSnapshot shooter, double xG, boolean onTarget) {
        if (!onTarget) return;

        // Check if shooter is offside - no goal from offside
        String attackingTeam = shooter.teamSide();
        String defendingTeam = "HOME".equals(attackingTeam) ? "AWAY" : "HOME";
        double offsideLine = calculateOffsideLine(state, defendingTeam);
        boolean isOffside = isPlayerOffside(shooter, attackingTeam, offsideLine, state.ball.x());

        if (isOffside) {
            // Disallow goal from offside
            return;
        }

        if (RNG.nextDouble() < xG) {
            if (shooter.teamSide().equals("HOME")) {
                state.homeGoals++;
            } else {
                state.awayGoals++;
            }

            state.addEvent(new GoalEvent(state.minute, state.tick,
                shooter.playerId(), shooter.name(), null, null,
                shooter.teamSide(), xG, state.homeGoals, state.awayGoals));

            state.stoppage = MatchState.StoppageType.GOAL_CELEBRATION;
            state.stoppageTicks = 10;
        }
    }

    private double calculateOffsideLine(MatchState state, String defendingTeam) {
        List<Double> defenderXPositions = new ArrayList<>();

        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(defendingTeam)) continue;
            if (snap.position() == Position.GK) continue;
            defenderXPositions.add(snap.x());
        }

        if (defenderXPositions.isEmpty()) return 0;

        defenderXPositions.sort(java.util.Comparator.reverseOrder());

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

    public void checkCorner(MatchState state, String attackingTeam) {
        state.addEvent(new SetPieceEvent(state.minute, state.tick,
            attackingTeam, null, null,
            SetPieceEvent.SetPieceType.CORNER, 0.0, 0.0));
        state.stoppage = MatchState.StoppageType.CORNER;
        state.stoppageTicks = 5;
    }

    public void checkGoalKick(MatchState state, String defendingTeam) {
        state.addEvent(new SetPieceEvent(state.minute, state.tick,
            defendingTeam, null, null,
            SetPieceEvent.SetPieceType.GOAL_KICK, 0.0, 0.0));
        state.stoppage = MatchState.StoppageType.GOAL_KICK;
        state.stoppageTicks = 5;
    }

    public void checkThrowIn(MatchState state, String team) {
        state.addEvent(new SetPieceEvent(state.minute, state.tick,
            team, null, null,
            SetPieceEvent.SetPieceType.THROW_IN, 0.0, 0.0));
        state.stoppage = MatchState.StoppageType.THROW_IN;
        state.stoppageTicks = 3;
    }

    private boolean isInPenaltyBox(PlayerSnapshot defender, PlayerSnapshot attacker) {
        boolean inX = attacker.teamSide().equals("HOME") ? attacker.x() > 78 : attacker.x() < 22;
        boolean inY = attacker.y() > 30 && attacker.y() < 70;
        return inX && inY;
    }
}
