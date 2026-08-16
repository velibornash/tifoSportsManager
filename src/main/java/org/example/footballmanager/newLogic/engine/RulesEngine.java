package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.event.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public final class RulesEngine {

    private static final Random RNG = new Random();

    public FoulResult checkFoul(MatchState state, PlayerSnapshot defender, PlayerSnapshot attacker) {
        // Don't award penalty if currently in offside stoppage
        if (state.stoppage == MatchState.StoppageType.GOAL_KICK) {
            return FoulResult.NONE;
        }

        if (RNG.nextDouble() >= 0.65) {
            return FoulResult.NONE;
        }

        state.addEvent(new FoulEvent(state.minute, state.tick,
            defender.playerId(), defender.name(),
            attacker.playerId(), attacker.name(),
            defender.teamSide(), false, defender.x(), defender.y()));

        boolean penalty = false;
        if (isInPenaltyBox(defender, attacker)) {
            penalty = true;
            state.pendingPenaltyTakerId = attacker.playerId();
            state.pendingPenaltyTeamSide = attacker.teamSide();
            state.stoppage = MatchState.StoppageType.PENALTY;
            state.stoppageTicks = 3;
        } else {
            // Outside box — free kick stoppage should fire reliably
            if (RNG.nextDouble() < 0.95) {
                state.stoppage = MatchState.StoppageType.FREE_KICK;
                state.stoppageTicks = 4;
                state.addEvent(new SetPieceEvent(state.minute, state.tick,
                    attacker.teamSide(), defender.playerId(), defender.name(),
                    SetPieceEvent.SetPieceType.FREE_KICK, attacker.x(), attacker.y()));
            }
        }

        CardEvent.CardType card = null;
        boolean alreadyBooked = state.playerYellowCards.getOrDefault(defender.playerId(), 0) > 0;
        // A short rebook cooldown (5 min) reflects that refs CAN show two
        // cards in close succession — a tactical foul minutes after a yellow
        // is the classic example. We just need to avoid back-to-back identical
        // offences on the exact same tick.
        // Note: a player who has never been booked has no entry, so the
        // getOrDefault returns 0 — not MIN_VALUE — and they're NOT marked
        // "recently carded".
        int lastCardTick = state.playerLastCardTick.getOrDefault(defender.playerId(), 0);
        boolean recentlyCarded = state.tick > 0 && lastCardTick > 0
            && (state.tick - lastCardTick) < (5 * 120); // 5 min × 120 ticks/min
        if ((RNG.nextDouble() < 0.22 || alreadyBooked) && !recentlyCarded) {
            card = alreadyBooked ? CardEvent.CardType.RED : CardEvent.CardType.YELLOW;
            state.addEvent(new CardEvent(state.minute, state.tick,
                defender.playerId(), defender.name(), defender.teamSide(), card));
            state.playerLastCardTick.put(defender.playerId(), state.tick);

            if (card == CardEvent.CardType.RED) {
                state.sentOffPlayers.add(defender.playerId());
                // Reset the yellow counter so the player is tracked as sent off
                // rather than still carrying an old yellow card.
                state.playerYellowCards.remove(defender.playerId());
            } else {
                state.playerYellowCards.merge(defender.playerId(), 1, Integer::sum);
            }
        }

        return new FoulResult(true, penalty, card);
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

        // FIFA rule: second-to-last opponent measured from the attacker's goal.
        // HOME defends the x=0 goal (2nd-smallest x), AWAY the x=100 goal (2nd-largest).
        if ("HOME".equals(defendingTeam)) {
            defenderXPositions.sort(Comparator.naturalOrder());
        } else {
            defenderXPositions.sort(Comparator.reverseOrder());
        }

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
        // Real penalty box: 16.5m deep × 40m wide. Only fouls in this tight
        // zone become penalties; outside it they become free kicks.
        boolean home = attacker.teamSide().equals("HOME");
        double goalX = home ? 100.0 : 0.0;
        double distX = Math.abs(attacker.x() - goalX);
        boolean inY = attacker.y() > 28 && attacker.y() < 72;
        return distX < 16.5 && inY;
    }

    public record FoulResult(boolean foulCommitted, boolean penalty, CardEvent.CardType card) {
        public static final FoulResult NONE = new FoulResult(false, false, null);
    }
}
