package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.event.*;

import java.util.Random;

public final class RulesEngine {

    private static final Random RNG = new Random();

    public void checkFoul(MatchState state, PlayerSnapshot defender, PlayerSnapshot attacker) {
        if (RNG.nextDouble() < 0.15) {
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
            } else if (RNG.nextDouble() < 0.25) {
                state.stoppage = MatchState.StoppageType.FREE_KICK;
                state.stoppageTicks = 4;
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

    public void checkCorner(MatchState state, String attackingTeam) {
        state.addEvent(new SetPieceEvent(state.minute, state.tick,
            attackingTeam, null, null,
            SetPieceEvent.SetPieceType.CORNER, 0.0, 0.0));
        state.stoppage = MatchState.StoppageType.CORNER;
        state.stoppageTicks = 5;
    }

    public void checkGoalKick(MatchState state, String defendingTeam) {
        state.stoppage = MatchState.StoppageType.GOAL_KICK;
        state.stoppageTicks = 5;
    }

    public void checkThrowIn(MatchState state, String team) {
        state.stoppage = MatchState.StoppageType.THROW_IN;
        state.stoppageTicks = 3;
    }

    private boolean isInPenaltyBox(PlayerSnapshot defender, PlayerSnapshot attacker) {
        boolean inX = attacker.teamSide().equals("HOME") ? attacker.x() > 78 : attacker.x() < 22;
        boolean inY = attacker.y() > 30 && attacker.y() < 70;
        return inX && inY;
    }
}
