package org.example.footballmanager.newLogic.util.events;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.model.tactics.Formation;
import org.example.footballmanager.newLogic.model.tactics.Tactics;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.engine_v1.TeamStrengthCalculator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

@Component
@RequiredArgsConstructor
public class EventCreator {

    private static final Random RANDOM = new Random();
    private final PlayerRepository playerRepository;

    public static MatchEvent createEventByRoll(double roll, Match match, Team team, List<Player> players, int minute) {
        if (players == null || players.isEmpty()) {
            return null;
        }

        double strengthBias = estimateAttackingBias(players);
        double goalThreshold = 0.05 + 0.06 * strengthBias;
        double yellowThreshold = goalThreshold + 0.05;
        double redThreshold = yellowThreshold + 0.02;
        double penaltyThreshold = redThreshold + 0.03;
        double freeKickThreshold = penaltyThreshold + 0.05;
        double cornerThreshold = freeKickThreshold + 0.06;
        double shotOnTargetThreshold = cornerThreshold + 0.14;
        double shotOffTargetThreshold = shotOnTargetThreshold + 0.18;
        String teamSide = match.getHomeTeam() != null && match.getHomeTeam().getId().equals(team.getId()) ? "HOME" : "AWAY";

        if (roll < goalThreshold) {
            Player scorer = pickWeightedAttacker(players);
            if (scorer == null) return null;

            Player assistant = pickAssistant(players, scorer);
            return new GoalEvent(minute, 0,
                    scorer.getId(), scorer.getName(),
                    assistant != null ? assistant.getId() : null,
                    assistant != null ? assistant.getName() : null,
                    teamSide, 0.8, 0, 0);
        }

        if (roll < yellowThreshold) {
            Player offender = pickWeightedDefender(players);
            if (offender == null) return null;
            return new CardEvent(minute, 0, offender.getId(), offender.getName(), teamSide, CardEvent.CardType.YELLOW);
        }

        if (roll < redThreshold) {
            Player offender = pickWeightedDefender(players);
            if (offender == null) return null;
            return new CardEvent(minute, 0, offender.getId(), offender.getName(), teamSide, CardEvent.CardType.RED);
        }

        if (roll < penaltyThreshold) {
            Player taker = pickWeightedAttacker(players);
            if (taker == null) return null;

            double scoreChance = ((taker.getSkills().getStriker() * 0.65) + (taker.getSkills().getTechnique() * 0.35)) / 100.0;
            boolean scored = RANDOM.nextDouble() < scoreChance;
            return new PenaltyEvent(minute, 0, taker.getId(), taker.getName(), teamSide, scored, !scored, 0.76);
        }

        if (roll < freeKickThreshold) {
            Player taker = pickSetPieceTaker(players);
            if (taker == null) return null;
            return new SetPieceEvent(minute, 0, teamSide, taker.getId(), taker.getName(),
                    SetPieceEvent.SetPieceType.FREE_KICK, 0.0, 0.0);
        }

        if (roll < cornerThreshold) {
            Player taker = pickSetPieceTaker(players);
            if (taker == null) return null;
            return new SetPieceEvent(minute, 0, teamSide, taker.getId(), taker.getName(),
                    SetPieceEvent.SetPieceType.CORNER, 0.0, 0.0);
        }

        if (roll < shotOnTargetThreshold) {
            Player shooter = pickWeightedAttacker(players);
            if (shooter == null) return null;
            return new ShotSavedEvent(minute, 0, 0, shooter.getId(), shooter.getName(), teamSide,
                    0L, "GK", 0.5, "Shot on target", 0.0, 0.0);
        }

        if (roll < shotOffTargetThreshold) {
            Player shooter = pickWeightedAttacker(players);
            if (shooter == null) return null;
            return new ShotMissedEvent(minute, 0, 0, shooter.getId(), shooter.getName(), teamSide,
                    0.3, "Shot off target", 0.0, 0.0);
        }

        return null;
    }

    public GoalEvent createRandomGoalEventForSimulateMatch(
            Match match,
            Team scoringTeam,
            List<Player> scoringPlayers,
            List<Player> opponentPlayers,
            Random rnd
    ) {
        if (scoringPlayers == null || scoringPlayers.isEmpty()) {
            return null;
        }

        Player scorer = pickWeightedAttacker(scoringPlayers);
        if (scorer == null) {
            return null;
        }

        String teamSide = scoringTeam.equals(match.getHomeTeam()) ? "HOME" : "AWAY";

        GoalEvent goal = new GoalEvent(rnd.nextInt(90) + 1, 0,
                scorer.getId(), scorer.getName(),
                null, null, teamSide, 0.8, 0, 0);

        scorer.setTotalGoals(scorer.getTotalGoals() + 1);
        playerRepository.save(scorer);

        double scoringStrength = estimateTeamStrength(scoringPlayers, scoringTeam.equals(match.getHomeTeam()));
        double opponentStrength = estimateTeamStrength(opponentPlayers, scoringTeam.equals(match.getAwayTeam()));
        double dominance = scoringStrength / Math.max(1.0, scoringStrength + opponentStrength);
        double assistChance = 0.45 + 0.35 * dominance;
        if (rnd.nextDouble() < assistChance) {
            Player assistant = pickAssistant(scoringPlayers, scorer);
            if (assistant != null) {
                goal = new GoalEvent(goal.minute(), goal.tick(),
                        goal.scorerId(), goal.scorerName(),
                        assistant.getId(), assistant.getName(),
                        goal.teamSide(), goal.xG(),
                        goal.homeScoreAfter(), goal.awayScoreAfter());
                assistant.setTotalAssists(assistant.getTotalAssists() + 1);
                playerRepository.save(assistant);
            }
        }

        return goal;
    }

    private static double estimateAttackingBias(List<Player> players) {
        if (players == null || players.isEmpty()) {
            return 0.5;
        }

        double attack = players.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .mapToDouble(p -> (p.getSkills().getStriker() * 0.5) + (p.getSkills().getTechnique() * 0.3) + (p.getSkills().getPace() * 0.2))
                .average()
                .orElse(50.0);

        return Math.max(0.2, Math.min(0.8, attack / 100.0));
    }

    private static double estimateTeamStrength(List<Player> players, boolean isHome) {
        if (players == null || players.isEmpty()) {
            return 1.0;
        }

        Formation formation = new Formation();
        formation.setName("4-4-2");
        formation.setOffenseModifier(1.0);
        formation.setDefenseModifier(1.0);
        formation.setPossessionModifier(1.0);

        Tactics tactics = new Tactics();
        tactics.setFormation(formation);

        return TeamStrengthCalculator.calculateTeamStrength(players, formation, tactics, isHome);
    }

    private static Player pickWeightedAttacker(List<Player> players) {
        return weightedPick(players,
                p -> p.getPosition() == Position.GK ? 0.05 :
                        (p.getSkills().getStriker() * 0.50) + (p.getSkills().getTechnique() * 0.30) + (p.getSkills().getPace() * 0.20));
    }

    private static Player pickWeightedDefender(List<Player> players) {
        return weightedPick(players,
                p -> p.getPosition() == Position.GK ? 0.1 :
                        (p.getSkills().getDefender() * 0.60) + ((100 - p.getSkills().getTechnique()) * 0.20) + ((100 - p.getSkills().getPlaymaker()) * 0.20));
    }

    private static Player pickSetPieceTaker(List<Player> players) {
        return weightedPick(players,
                p -> p.getPosition() == Position.GK ? 0.01 :
                        (p.getSkills().getPassing() * 0.45) + (p.getSkills().getTechnique() * 0.35) + (p.getSkills().getPlaymaker() * 0.20));
    }

    private static Player pickAssistant(List<Player> players, Player scorer) {
        List<Player> candidates = new ArrayList<>(players.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .filter(p -> scorer == null || !p.getId().equals(scorer.getId()))
                .toList());

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparingInt(p -> -(p.getSkills().getPassing() + p.getSkills().getPlaymaker())));
        int top = Math.max(1, Math.min(4, candidates.size()));
        return candidates.get(RANDOM.nextInt(top));
    }

    private static Player weightedPick(List<Player> players, java.util.function.ToDoubleFunction<Player> weightFn) {
        if (players == null || players.isEmpty()) {
            return null;
        }

        double total = 0.0;
        for (Player player : players) {
            total += Math.max(0.01, weightFn.applyAsDouble(player));
        }

        double threshold = RANDOM.nextDouble() * total;
        double current = 0.0;

        for (Player player : players) {
            current += Math.max(0.01, weightFn.applyAsDouble(player));
            if (current >= threshold) {
                return player;
            }
        }

        return players.get(players.size() - 1);
    }
}
