package org.example.footballmanager.simulator;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.repository.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchEventFactory {

    private final GoalEventRepository goalEventRepository;
    private final ShotOnTargetEventRepository shotOnTargetRepo;
    private final ShotOffTargetEventRepository shotOffTargetRepo;
    private final YellowCardEventRepository yellowCardRepo;
    private final RedCardEventRepository redCardRepo;
    private final FreeKickEventRepository freeKickRepo;
    private final PenaltyEventRepository penaltyRepo;
    private final ChanceEventRepository chanceRepo;
    private final InjuryEventRepository injuryRepo;
    private final CornerEventRepository cornerRepo;
    private final OffsideEventRepository offsideRepo;
    private final VARReviewEventRepository varReviewRepo;

    private final Random random = new Random();

    private List<Player> suspendedPlayers = new ArrayList<>();

    @SneakyThrows
    public MatchEvent createRandomEvent(MatchContext context, List<Player> homePlayers, List<Player> awayPlayers) {
        int minute = context.getCurrentMinute();
        Match match = context.getMatch();

        double homeStrength = TeamStrengthCalculator.calculateTeamStrength(
                homePlayers, Formation.fromString(match.getHomeFormation()), match.getHomeTactics(), true);
        double awayStrength = TeamStrengthCalculator.calculateTeamStrength(
                awayPlayers, Formation.fromString(match.getAwayFormation()), match.getAwayTactics(), false);
        double homeChance = homeStrength * context.getHomeMomentum();
        double awayChance = awayStrength * context.getAwayMomentum();

        log.info("Minute: {}, Home Strength: {}, Away Strength: {}, Home Chance: {}, Away Chance: {}",
                minute, homeStrength, awayStrength, homeChance, awayChance);

        double total = homeChance + awayChance;
        boolean isHome = random.nextDouble() < (homeChance / total);
        Team team = isHome ? match.getHomeTeam() : match.getAwayTeam();
        List<Player> teamPlayers = isHome ? homePlayers : awayPlayers;
        teamPlayers = teamPlayers.stream()
                .filter(p -> !suspendedPlayers.contains(p))
                .toList();

        double roll = random.nextDouble();
        log.info("Roll value: {}", roll);

        MatchEvent event = null;

        if (roll < 0.05) {
            event = createGoal(match, team, teamPlayers, minute);
        } else if (roll < 0.12) {
            event = createShotOnTarget(match, team, teamPlayers, minute);
        } else if (roll < 0.20) {
            event = createShotOffTarget(match, team, teamPlayers, minute);
        } else if (roll < 0.25) {
            event = createYellowCard(match, teamPlayers, minute);
        } else if (roll < 0.27) {
            event = createRedCard(match, teamPlayers, minute);
        } else if (roll < 0.32) {
            event = createFreeKick(match, team, teamPlayers, minute);
        } else if (roll < 0.35) {
            event = createPenalty(match, team, teamPlayers, minute);
        } else if (roll < 0.38) {
            event = createCorner(match, team, teamPlayers, minute);
        } else if (roll < 0.41) {
            event = createChance(match, team, teamPlayers, minute, context);
        } else if (roll < 0.43) {
            event = createInjury(match, teamPlayers, minute);
        } else if (roll < 0.45) {
            event = createOffside(match, team, teamPlayers, minute, context);
        } else {
            return null;
        }

        // Nakon kreiranja eventa, proveri da li ide VAR review
        if (event != null && (event instanceof GoalEvent || event instanceof PenaltyEvent || event instanceof OffsideEvent)) {
            double varChance = 0.1; // 10% šanse za VAR review
            if (random.nextDouble() < varChance) {
                int varType = random.nextInt(3); // 0,1,2
                switch (event) {
                    case GoalEvent goalEvent when varType == 0 ->
                            event = createVARReview(varType + 1, match, team, teamPlayers, minute, goalEvent, null, null);
                    case PenaltyEvent penaltyEvent when varType == 1 ->
                            event = createVARReview(varType + 1, match, team, teamPlayers, minute, null, penaltyEvent, null);
                    case OffsideEvent offsideEvent when varType == 2 ->
                            event = createVARReview(varType + 1, match, team, teamPlayers, minute, null, null, offsideEvent);
                    default -> {
                    }
                }
            }
        }

        if (event != null) {
            event.setMinute(minute);
            String ballPos = context.getBallPosition();
            event.setBallPosition(ballPos);
            if (isHome) {
                event.setPlayerPosition(mapToHomeHalf(ballPos));
            } else {
                event.setPlayerPosition(mapToAwayHalf(ballPos));
            }
            event.apply(context);

            log.info("Applied event: {}, Ball Position: {}, Player Position: {}", event.getDescription(), event.getBallPosition(), event.getPlayerPosition());

            if (event instanceof RedCardEvent) {
                suspendedPlayers.add(((RedCardEvent) event).getPlayer());
            }
        }

        return event;
    }

    private void saveEvent(MatchEvent event) {
        if (event instanceof GoalEvent e) goalEventRepository.save(e);
        else if (event instanceof ShotOnTargetEvent e) shotOnTargetRepo.save(e);
        else if (event instanceof ShotOffTargetEvent e) shotOffTargetRepo.save(e);
        else if (event instanceof YellowCardEvent e) yellowCardRepo.save(e);
        else if (event instanceof RedCardEvent e) redCardRepo.save(e);
        else if (event instanceof FreeKickEvent e) freeKickRepo.save(e);
        else if (event instanceof PenaltyEvent e) penaltyRepo.save(e);
        else if (event instanceof ChanceEvent e) chanceRepo.save(e);
        else if (event instanceof InjuryEvent e) injuryRepo.save(e);
        else if (event instanceof CornerEvent e) cornerRepo.save(e);
        else if (event instanceof OffsideEvent e) offsideRepo.save(e);
        else if (event instanceof VARReviewEvent e) varReviewRepo.save(e);
    }

    private GoalEvent createGoal(Match match, Team team, List<Player> players, int minute) {
        GoalEvent goal = new GoalEvent();
        goal.setMatch(match);
        goal.setTeam(team);
        goal.setScorer(randomNonGoalkeeper(players));
        goal.setAssistant(random.nextBoolean() ? randomOther(players, goal.getScorer()) : null);
        goal.setMinute(minute);
        goal.setKeyEvent(true);
        goal.setVisualize(true);
        goal.setImpact("HIGH");
        goal.setType("Goal");
        log.info("Goal created: Scorer: {}, Assistant: {}", goal.getScorer().getName(), goal.getAssistant());
        return goal;
    }

    private ShotOnTargetEvent createShotOnTarget(Match match, Team team, List<Player> players, int minute) {
        ShotOnTargetEvent e = new ShotOnTargetEvent();
        e.setMatch(match);
        e.setTeam(team);
        e.setShooter(randomNonGoalkeeper(players));
        e.setMinute(minute);
        e.setKeyEvent(false);
        e.setVisualize(true);
        e.setImpact("MEDIUM");
        e.setType("ShotOnTarget");
        log.info("ShotOnTarget created: Shooter: {}", e.getShooter().getName());
        return e;
    }

    private ShotOffTargetEvent createShotOffTarget(Match match, Team team, List<Player> players, int minute) {
        ShotOffTargetEvent e = new ShotOffTargetEvent();
        e.setMatch(match);
        e.setTeam(team);
        e.setShooter(randomNonGoalkeeper(players));
        e.setMinute(minute);
        e.setKeyEvent(false);
        e.setVisualize(true);
        e.setImpact("LOW");
        e.setType("Shot Off Target");
        log.info("ShotOffTarget created: Shooter: {}", e.getShooter().getName());
        return e;
    }

    private YellowCardEvent createYellowCard(Match match, List<Player> players, int minute) {
        YellowCardEvent e = new YellowCardEvent();
        e.setMatch(match);
        e.setPlayer(randomPlayer(players));
        e.setMinute(minute);
        e.setKeyEvent(true);
        e.setVisualize(true);
        e.setImpact("MEDIUM");
        e.setType("Yellow Card");
        log.info("YellowCard created: Player: {}", e.getPlayer().getName());
        return e;
    }

    private RedCardEvent createRedCard(Match match, List<Player> players, int minute) {
        RedCardEvent e = new RedCardEvent();
        e.setMatch(match);
        e.setPlayer(randomPlayer(players));
        e.setMinute(minute);
        e.setKeyEvent(true);
        e.setVisualize(true);
        e.setImpact("HIGH");
        e.setType("Red Card");
        log.info("RedCard created: Player: {}", e.getPlayer().getName());
        return e;
    }

    private FreeKickEvent createFreeKick(Match match, Team team, List<Player> players, int minute) {
        FreeKickEvent e = new FreeKickEvent();
        e.setMatch(match);
        e.setTeam(team);
        e.setTaker(randomPlayer(players));
        e.setMinute(minute);
        e.setKeyEvent(false);
        e.setVisualize(true);
        e.setImpact("LOW");
        e.setType("Free Kick");
        log.info("FreeKick created: Taker: {}", e.getTaker().getName());
        return e;
    }

    private PenaltyEvent createPenalty(Match match, Team team, List<Player> players, int minute) {
        PenaltyEvent e = new PenaltyEvent();
        e.setMatch(match);
        e.setTeam(team);
        e.setTaker(randomNonGoalkeeper(players));
        e.setScored(random.nextBoolean());
        e.setMinute(minute);
        e.setKeyEvent(true);
        e.setVisualize(true);
        e.setImpact("HIGH");
        e.setType("Penalty");
        e.setPlayerPosition("penalty_spot");
        log.info("Penalty created: Taker: {}, Scored: {}", e.getTaker().getName(), e.isScored());
        return e;
    }

    private CornerEvent createCorner(Match match, Team team, List<Player> players, int minute) {
        CornerEvent e = new CornerEvent();
        e.setMatch(match);
        e.setTeam(team);
        e.setTaker(randomNonGoalkeeper(players));
        e.setMinute(minute);
        e.setKeyEvent(false);
        e.setVisualize(true);
        e.setImpact("LOW");
        e.setType("Corner");
        log.info("⋏ [{}'] Corner created: Taker: {}", minute, e.getTaker().getName());
        return e;
    }

    private ChanceEvent createChance(Match match, Team team, List<Player> players, int minute, MatchContext context) {
        Player creator = randomNonGoalkeeper(players);
        Player defender = randomOther(players, creator);
        if (DuelCalculator.winDuel(creator, defender, context)) {
            ChanceEvent e = new ChanceEvent();
            e.setMatch(match);
            e.setTeam(team);
            e.setCreator(creator);
            e.setMinute(minute);
            e.setKeyEvent(false);
            e.setVisualize(true);
            e.setImpact("MEDIUM");
            e.setType("Chance");
            e.setPlayerPosition(creator.getPosition());
            log.info("Chance created: Creator: {}, Defender: {}, Duel Won", creator.getName(), defender.getName());
            return e;
        }
        log.info("Chance not created: Creator: {} lost duel against {}", creator.getName(), defender.getName());
        return null;
    }

    private InjuryEvent createInjury(Match match, List<Player> players, int minute) {
        InjuryEvent e = new InjuryEvent();
        e.setMatch(match);
        e.setInjuredPlayer(randomPlayer(players));
        e.setMinute(minute);
        e.setKeyEvent(true);
        e.setVisualize(true);
        e.setImpact("HIGH");
        e.setType("Injury");
        log.info("Injury created: Player: {}", e.getInjuredPlayer().getName());
        return e;
    }

    private OffsideEvent createOffside(Match match, Team team, List<Player> players, int minute, MatchContext context) {
        OffsideEvent e = new OffsideEvent();
        e.setMatch(match);
        e.setTeam(team);
        e.setOffender(randomNonGoalkeeper(players));
        e.setMinute(minute);
        e.setKeyEvent(true);
        e.setVisualize(true);
        e.setImpact("MEDIUM");
        e.setType("Offside");
        if (random.nextDouble() < 0.3) {
            createVARReview(3, match, team, players, minute, null, null, e);
        }
        log.info("🚩 [{}'] Offside created: Offender: {}", minute, e.getOffender().getName());
        return e;
    }

    private VARReviewEvent createVARReview(int number, Match match, Team team, List<Player> players, int minute,
                                           GoalEvent reviewedGoalEvent, PenaltyEvent reviewedPenaltyEvent, OffsideEvent reviewedOffsideEvent) {
        VARReviewEvent e = new VARReviewEvent();
        e.setMatch(match);
        e.setTeam(team);
        e.setNumber(number);
        if (number == 1) {
            e.setReviewedGoalEvent(reviewedGoalEvent);
        } else if (number == 2) {
            e.setReviewedPenaltyEvent(reviewedPenaltyEvent);
        } else {
            e.setReviewedOffsideEvent(reviewedOffsideEvent);
        }
        e.setMinute(minute);
        e.setKeyEvent(true);
        e.setVisualize(true);
        e.setImpact("HIGH");
        e.setType("VAR Review");
        if (random.nextBoolean()) {
            e.setDecision("Overturned");

            if (number == 1 && reviewedGoalEvent != null) {
                match.getGoals().remove(reviewedGoalEvent);
                match.setHomeGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getHomeTeam())).count());
                match.setAwayGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getAwayTeam())).count());
            } else if (number == 2 && reviewedPenaltyEvent != null) {
                match.getPenalties().remove(reviewedPenaltyEvent);
            } else if (number == 3 && reviewedOffsideEvent != null) {
                match.getOffsides().remove(reviewedOffsideEvent);
                match.setHomeGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getHomeTeam())).count());
                match.setAwayGoals((int) match.getGoals().stream().filter(g -> g.getTeam().equals(match.getAwayTeam())).count());

                // DODATO: Ako je ofsajd bio pogrešan, kreiraj GoalEvent
                GoalEvent goal = new GoalEvent();
                goal.setMatch(match);
                goal.setTeam(team);
                goal.setScorer(randomNonGoalkeeper(players));
                goal.setAssistant(null);
                goal.setMinute(minute);
                goal.setKeyEvent(true);
                goal.setVisualize(true);
                goal.setImpact("HIGH");
                goal.setType("Goal");

                match.getGoals().add(goal);
                if (team.equals(match.getHomeTeam())) {
                    match.setHomeGoals(match.getHomeGoals() + 1);
                } else {
                    match.setAwayGoals(match.getAwayGoals() + 1);
                }
                goalEventRepository.save(goal);
                log.info("🏁 VAR Overturned Offside → Goal created for {}", team.getName());
            }
        } else {
            e.setDecision("Confirmed");
        }

        return e;
    }

    private Player randomPlayer(List<Player> players) {
        return players.get(random.nextInt(players.size()));
    }

    private Player randomOther(List<Player> players, Player exclude) {
        Player p;
        do {
            p = players.get(random.nextInt(players.size()));
        } while (p.equals(exclude));
        return p;
    }

    private Player randomNonGoalkeeper(List<Player> players) {
        List<Player> fieldPlayers = players.stream()
                .filter(p -> p.getPositionEnum() != Position.GK)
                .toList();
        return fieldPlayers.get(random.nextInt(fieldPlayers.size()));
    }


    private String mapToHomeHalf(String ballPos) {
        return switch (ballPos) {
            case "left_wing", "center" -> "left_half";
            case "right_wing", "box" -> "right_half";
            default -> ballPos;
        };
    }

    private String mapToAwayHalf(String ballPos) {
        return switch (ballPos) {
            case "left_wing", "center" -> "right_half";
            case "right_wing", "box" -> "left_half";
            default -> ballPos;
        };
    }
}
