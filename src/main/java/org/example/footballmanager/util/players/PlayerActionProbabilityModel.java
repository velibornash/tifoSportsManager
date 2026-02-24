package org.example.footballmanager.util.players;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.util.match.MatchContext;

public class PlayerActionProbabilityModel {
    public double calculateGoalProbability(Player shooter, Position position, MatchContext context) {
        double skill = shooter.getSkills().getStriker();
        double distanceFactor = position == Position.ATT ? 1.2 : 0.8; // Napadači imaju veću šansu
       // double momentum = context.getHomeMomentum(); // ili awayMomentum
        return (skill / 100.0) * distanceFactor  * 0.3; // Max 30% šansa za gol
    }
}