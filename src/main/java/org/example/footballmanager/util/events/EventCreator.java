package org.example.footballmanager.util.events;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.util.teams.TeamStrengthCalculator;
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

        if (roll < goalThreshold) {
            Player scorer = pickWeightedAttacker(players);
            if (scorer == null) return null;

            GoalEvent goal = new GoalEvent();
            goal.setMinute(minute);
            goal.setMatch(match);
            goal.setTeam(team);
            goal.setScorer(scorer);

            Player assistant = pickAssistant(players, scorer);
            if (assistant != null) {
                goal.setAssistant(assistant);
            }
            goal.apply();
            return goal;
        }

        if (roll < yellowThreshold) {
            Player offender = pickWeightedDefender(players);
            if (offender == null) return null;

            YellowCardEvent event = new YellowCardEvent();
            event.setMatch(match);
            event.setTeam(team);
            event.setPlayer(offender);
            event.setMinute(minute);
            return event;
        }

        if (roll < redThreshold) {
            Player offender = pickWeightedDefender(players);
            if (offender == null) return null;

            RedCardEvent event = new RedCardEvent();
            event.setMatch(match);
            event.setTeam(team);
            event.setPlayer(offender);
            event.setMinute(minute);
            return event;
        }

        if (roll < penaltyThreshold) {
            Player taker = pickWeightedAttacker(players);
            if (taker == null) return null;

            PenaltyEvent event = new PenaltyEvent();
            event.setMatch(match);
            event.setTeam(team);
            event.setTaker(taker);
            event.setMinute(minute);

            double scoreChance = ((taker.getSkills().getStriker() * 0.65) + (taker.getSkills().getTechnique() * 0.35)) / 100.0;
            event.setScored(RANDOM.nextDouble() < scoreChance);
            return event;
        }

        if (roll < freeKickThreshold) {
            Player taker = pickSetPieceTaker(players);
            if (taker == null) return null;

            FreeKickEvent event = new FreeKickEvent();
            event.setMatch(match);
            event.setTeam(team);
            event.setTaker(taker);
            event.setPlayer(taker);
            event.setMinute(minute);
            event.setDirect(RANDOM.nextDouble() < 0.55);
            event.setDangerous(RANDOM.nextDouble() < 0.35 + 0.30 * strengthBias);
            return event;
        }

        if (roll < cornerThreshold) {
            Player taker = pickSetPieceTaker(players);
            if (taker == null) return null;

            CornerEvent event = new CornerEvent();
            event.setMatch(match);
            event.setTeam(team);
            event.setPlayer(taker);
            event.setMinute(minute);
            return event;
        }

        if (roll < shotOnTargetThreshold) {
            Player shooter = pickWeightedAttacker(players);
            if (shooter == null) return null;

            ShotOnTargetEvent event = new ShotOnTargetEvent();
            event.setMatch(match);
            event.setTeam(team);
            event.setShooter(shooter);
            event.setMinute(minute);
            return event;
        }

        if (roll < shotOffTargetThreshold) {
            Player shooter = pickWeightedAttacker(players);
            if (shooter == null) return null;

            ShotOffTargetEvent event = new ShotOffTargetEvent();
            event.setMatch(match);
            event.setTeam(team);
            event.setShooter(shooter);
            event.setMinute(minute);
            return event;
        }

        ChanceEvent event = new ChanceEvent();
        Player player = pickWeightedAttacker(players);
        if (player == null) return null;

        event.setMinute(minute);
        event.setMatch(match);
        event.setTeam(team);
        event.setPlayer(player);
        event.setDangerous(RANDOM.nextDouble() < 0.20 + 0.30 * strengthBias);
        return event;
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

        double scoringStrength = estimateTeamStrength(scoringPlayers, scoringTeam.equals(match.getHomeTeam()));
        double opponentStrength = estimateTeamStrength(opponentPlayers, scoringTeam.equals(match.getAwayTeam()));
        double dominance = scoringStrength / Math.max(1.0, scoringStrength + opponentStrength);

        Player scorer = pickWeightedAttacker(scoringPlayers);
        if (scorer == null) {
            return null;
        }

        GoalEvent goal = new GoalEvent();
        goal.setMatch(match);
        goal.setTeam(scoringTeam);
        goal.setScorer(scorer);
        goal.setMinute(rnd.nextInt(90) + 1);

        scorer.setTotalGoals(scorer.getTotalGoals() + 1);
        playerRepository.save(scorer);

        double assistChance = 0.45 + 0.35 * dominance;
        if (rnd.nextDouble() < assistChance) {
            Player assistant = pickAssistant(scoringPlayers, scorer);
            if (assistant != null) {
                goal.setAssistant(assistant);
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
