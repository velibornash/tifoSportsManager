package org.example.footballmanager.util.events;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.util.match.MatchContext;
import org.example.footballmanager.util.players.PlayerActionProbabilityModel;

import java.util.Random;

public class MatchEventOutcomeResolver {
    public boolean resolveShotOutcome(Player shooter, Player goalkeeper, MatchContext context) {
        double shotChance = new PlayerActionProbabilityModel().calculateGoalProbability(shooter, shooter.getPositionEnum(), context);
        double keeperSaveChance = goalkeeper.getSkills().getGoalkeeper() / 100.0;
        return new Random().nextDouble() < (shotChance - keeperSaveChance);
    }
}