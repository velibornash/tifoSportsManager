package org.example.footballmanager.simulator;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.MatchEvent;

import java.util.List;
import java.util.Random;

public class MatchEventFactory {

    private final Random random = new Random();

    public MatchEvent createRandomEvent(MatchContext context,
                                        List<Player> homePlayers,
                                        List<Player> awayPlayers,
                                        org.example.footballmanager.model.tactics.Formation homeFormation,
                                        org.example.footballmanager.model.tactics.Formation awayFormation) {

        int minute = context.getCurrentMinute();
        double homeStrength = TeamStrengthCalculator.calculateTeamStrength(homePlayers, homeFormation, context.getHomeTactics(), true);
        double awayStrength = TeamStrengthCalculator.calculateTeamStrength(awayPlayers, awayFormation, context.getAwayTactics(), false);

        boolean isHome = random.nextDouble() < (homeStrength / (homeStrength + awayStrength));
        Team team = isHome ? context.getHomeTeam() : context.getAwayTeam();
        List<Player> teamPlayers = isHome ? homePlayers : awayPlayers;

        double roll = random.nextDouble();
        return EventCreator.createEventByRoll(roll, context.getMatch(), team, teamPlayers, minute);
    }
}
