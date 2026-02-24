package org.example.footballmanager.simulator.old;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.model.tactics.Tactics;
import org.example.footballmanager.model.tactics.Formation;
import org.example.footballmanager.util.match.MatchContext;
import org.example.footballmanager.util.events.MatchEventFactory;
import org.example.footballmanager.util.teams.TeamStrengthCalculator;
import org.example.footballmanager.util.old.MatchEventWebSocketHandler;
import org.example.footballmanager.util.TacticsAdjustmentService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

@Slf4j
@Component
public class MatchSimulator {

    private final MatchEventFactory eventFactory = new MatchEventFactory();
    private final TacticsAdjustmentService tacticsAdjustmentService;
    private final MatchEventWebSocketHandler webSocketHandler;
    private final Random random = new Random();

    public MatchSimulator(TacticsAdjustmentService tacticsAdjustmentService, MatchEventWebSocketHandler webSocketHandler) {
        this.tacticsAdjustmentService = tacticsAdjustmentService;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * Konvertuje entitet u DTO za WebSocket – svi eventovi pokriveni
     */
    private MatchEventDTO toDto(MatchEvent event) {
        if (event == null) return null;

        if (event instanceof GoalEvent g) {
            GoalEventDTO dto = new GoalEventDTO();
            dto.setType("goal");
            dto.setMinute(g.getMinute());
            dto.setDescription(g.getDescription());

            if (g.getScorer() != null) {
                Player p = g.getScorer();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }

            dto.setScorerName(g.getScorer() != null ? g.getScorer().getName() : null);
            dto.setAssistantName(g.getAssistant() != null ? g.getAssistant().getName() : null);
            dto.setTeamName(g.getTeam() != null ? g.getTeam().getName() : null);
            dto.setScoreAfterGoal(g.getScoreAfterGoal());
            return dto;

        } else if (event instanceof YellowCardEvent y) {
            YellowCardEventDTO dto = new YellowCardEventDTO();
            dto.setType("yellowCard");
            dto.setMinute(y.getMinute());
            dto.setDescription(y.getDescription());

            if (y.getPlayer() != null) {
                Player p = y.getPlayer();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }

            dto.setPlayerName(y.getPlayer() != null ? y.getPlayer().getName() : null);
            dto.setTeamName(y.getPlayer() != null && y.getPlayer().getTeam() != null ?
                    y.getPlayer().getTeam().getName() : null);
            return dto;

        } else if (event instanceof RedCardEvent r) {
            RedCardEventDTO dto = new RedCardEventDTO();
            dto.setType("redCard");
            dto.setMinute(r.getMinute());
            dto.setDescription(r.getDescription());

            if (r.getPlayer() != null) {
                Player p = r.getPlayer();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }
            return dto;

        } else if (event instanceof InjuryEvent i) {
            InjuryEventDTO dto = new InjuryEventDTO();
            dto.setType("injury");
            dto.setMinute(i.getMinute());
            dto.setDescription(i.getDescription());

            if (i.getPlayer() != null) {
                Player p = i.getPlayer();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }
            return dto;

        } else if (event instanceof PenaltyEvent p) {
            PenaltyEventDTO dto = new PenaltyEventDTO();
            dto.setType("penalty");
            dto.setMinute(p.getMinute());
            dto.setDescription(p.getDescription());

            if (p.getTaker() != null) {
                Player pl = p.getTaker();
                dto.setPlayerName(pl.getName());
                dto.setPlayerAge(pl.getAge());
                dto.setPlayerHeight(pl.getHeight());
                dto.setPlayerWeight(pl.getWeight());
                dto.setPlayerTotalGoals(pl.getTotalGoals());
                dto.setPlayerTotalAssists(pl.getTotalAssists());
                dto.setPlayerPosition(pl.getPosition() != null ? pl.getPosition().name() : null);
                dto.setPlayerRating(pl.getRating());
            }
            dto.setTakerName(p.getTaker() != null ? p.getTaker().getName() : null);
            dto.setTeamName(p.getTeam() != null ? p.getTeam().getName() : null);
            dto.setScored(p.isScored());
            return dto;

        } else if (event instanceof SubstitutionEvent s) {
            SubstitutionEventDTO dto = new SubstitutionEventDTO();
            dto.setType("substitution");
            dto.setMinute(s.getMinute());
            dto.setDescription(s.getDescription());

            if (s.getPlayerOut() != null) {
                Player p = s.getPlayerOut();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }

            dto.setPlayerOutName(s.getPlayerOut() != null ? s.getPlayerOut().getName() : null);
            dto.setPlayerInName(s.getPlayerIn() != null ? s.getPlayerIn().getName() : null);
            dto.setTeamName(s.getPlayerOut() != null && s.getPlayerOut().getTeam() != null ?
                    s.getPlayerOut().getTeam().getName() : null);
            return dto;

        } else if (event instanceof OffsideEvent o) {
            OffsideEventDTO dto = new OffsideEventDTO();
            dto.setType("offside");
            dto.setMinute(o.getMinute());
            dto.setDescription(o.getDescription());

            if (o.getPlayer() != null) {
                Player p = o.getPlayer();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }
            dto.setPlayerName(o.getPlayer() != null ? o.getPlayer().getName() : null);
            dto.setTeamName(o.getPlayer() != null && o.getPlayer().getTeam() != null ?
                    o.getPlayer().getTeam().getName() : null);
            return dto;

        } else if (event instanceof CornerEvent c) {
            CornerEventDTO dto = new CornerEventDTO();
            dto.setType("corner");
            dto.setMinute(c.getMinute());
            dto.setDescription(c.getDescription());
            dto.setTeamName(c.getTeam() != null ? c.getTeam().getName() : null);
            if (c.getPlayer() != null) {
                Player p = c.getPlayer();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }
            dto.setPlayerName(c.getPlayer() != null ? c.getPlayer().getName() : null);
            dto.setTakerName(c.getPlayer() != null ? c.getPlayer().getName() : null);
            dto.setTeamName(c.getTeam() != null ? c.getTeam().getName() : null);
            return dto;

        } else if (event instanceof FreeKickEvent f) {
            FreeKickEventDTO dto = new FreeKickEventDTO();
            dto.setType("freeKick");
            dto.setMinute(f.getMinute());
            dto.setDescription(f.getDescription());
            if (f.getPlayer() != null) {
                Player p = f.getTaker();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }
            if (f.getTaker() != null) {
                Player p = f.getTaker();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }
            dto.setPlayerName(f.getPlayer() != null ? f.getPlayer().getName() : null);
            dto.setTakerName(f.getTaker() != null ? f.getTaker().getName() : null);
            dto.setTeamName(f.getPlayer() != null && f.getPlayer().getTeam() != null ?
            f.getPlayer().getTeam().getName() : null);
            return dto;

        } else if (event instanceof ShotOnTargetEvent s) {
            ShotOnTargetEventDTO dto = new ShotOnTargetEventDTO();
            dto.setType("shotOnTarget");
            dto.setMinute(s.getMinute());
            dto.setDescription(s.getDescription());

            if (s.getShooter() != null) {
                Player p = s.getShooter();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }
            dto.setPlayerName(s.getShooter() != null ? s.getShooter().getName() : null);
            dto.setTeamName(s.getTeam() != null ? s.getTeam().getName() : null);
            return dto;

        } else if (event instanceof ShotOffTargetEvent s) {
            ShotOffTargetEventDTO dto = new ShotOffTargetEventDTO();
            dto.setType("shotOffTarget");
            dto.setMinute(s.getMinute());
            dto.setDescription(s.getDescription());

            if (s.getShooter() != null) {
                Player p = s.getShooter();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }
            dto.setPlayerName(s.getShooter() != null ? s.getShooter().getName() : null);
            dto.setTeamName(s.getTeam() != null ? s.getTeam().getName() : null);
            return dto;

        } else if (event instanceof VARReviewEvent v) {
            VARReviewEventDTO dto = new VARReviewEventDTO();
            dto.setType("varReview");
            dto.setMinute(v.getMinute());
            dto.setDescription(v.getDescription());
            dto.setDecision(v.getDecision());
            return dto;

        } else if (event instanceof ChanceEvent c) {
            ChanceEventDTO dto = new ChanceEventDTO();
            dto.setType("chance");
            dto.setMinute(c.getMinute());
            dto.setDescription(c.getDescription());

            if (c.getPlayer() != null) {
                Player p = c.getPlayer();
                dto.setPlayerName(p.getName());
                dto.setPlayerAge(p.getAge());
                dto.setPlayerHeight(p.getHeight());
                dto.setPlayerWeight(p.getWeight());
                dto.setPlayerTotalGoals(p.getTotalGoals());
                dto.setPlayerTotalAssists(p.getTotalAssists());
                dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                dto.setPlayerRating(p.getRating());
            }
            dto.setPlayerName(c.getPlayer() != null ? c.getPlayer().getName() : null);
            dto.setTeamName(c.getTeam() != null ? c.getTeam().getName() : null);
            dto.setDangerous(c.isDangerous());
            return dto;

        } else if (event instanceof MatchStartEvent ms) {
            MatchStartedDTO dto = new MatchStartedDTO();
            dto.setType("matchStarted");
            dto.setMinute(ms.getMinute());
            dto.setDescription(ms.getDescription());
            dto.setHomeTeamName(ms.getMatch().getHomeTeam().getName());
            dto.setAwayTeamName(ms.getMatch().getAwayTeam().getName());
            return dto;

        } else if (event instanceof MatchEndedEvent me) {
            MatchEndedDTO dto = new MatchEndedDTO();
            dto.setType("matchEnded");
            dto.setMinute(me.getMinute());
            dto.setDescription(me.getDescription());
            dto.setHomeTeamName(me.getMatch().getHomeTeam().getName());
            dto.setAwayTeamName(me.getMatch().getAwayTeam().getName());
            dto.setHomeGoals(me.getMatch().getHomeGoals());
            dto.setAwayGoals(me.getMatch().getAwayGoals());
            return dto;
        }

        log.warn("Nepoznat event tip za DTO: {}", event.getClass().getSimpleName());
        return null;
    }

    @SneakyThrows
    public void simulateMatch(Match match, Crowd crowd, Referee referee,
                              Tactics homeTactics, Tactics awayTactics,
                              List<Player> homePlayers, List<Player> awayPlayers) {

        MatchContext context = new MatchContext(match, crowd, referee, homeTactics, awayTactics);
        context.setPossessionTeam(match.getHomeTeam());

        Formation homeFormation = homeTactics.getFormation();
        Formation awayFormation = awayTactics.getFormation();

        Thread.sleep(3000); // Duže čekanje pre početka

        // Start meča
        MatchStartEvent startEvent = new MatchStartEvent();
        startEvent.setMinute(1);
        startEvent.setMatch(match);
        startEvent.apply();

        MatchEventDTO startDto = toDto(startEvent);
        if (startDto != null) {
            log.info("Broadcasting start event");
            try {
                webSocketHandler.broadcastEvent(startDto);
            } catch (Exception e) {
                log.error("Greška pri broadcastu start eventa", e);
            }
        } else {
            log.warn("Start DTO je null – nije poslat");
        }

        log.info("[1'] Event: {}", startEvent.getDescription());
        Thread.sleep(3000); // Duže da UI vidi početak

        for (int minute = 1; minute <= 90; minute++) {
            context.setCurrentMinute(minute);

            updateFatigue(context);
            updatePossession(context, homePlayers, awayPlayers, homeFormation, awayFormation);
            tacticsAdjustmentService.adjustTactics(context);

            if (random.nextDouble() < eventProbability(context, match)) {
                MatchEvent event = eventFactory.createRandomEvent(context, homePlayers, awayPlayers, homeFormation, awayFormation);
                if (event != null) {
                    event.setMinute(minute);
                    event.apply();
                    log.info("[{}'] Event: {}", minute, event.getDescription());

                    MatchEventDTO dto = toDto(event);
                    if (dto != null) {
                        log.info("Broadcasting event: {}", event.getClass().getSimpleName());
                        try {
                            webSocketHandler.broadcastEvent(dto);
                            Thread.sleep(3500); // Duže čekanje za svaki event
                        } catch (Exception e) {
                            log.error("WebSocket broadcast failed za {}", event.getClass().getSimpleName(), e);
                        }
                    } else {
                        log.warn("DTO je null za event: {}", event.getClass().getSimpleName());
                    }

                    // Penalty → Goal
                    if (event instanceof PenaltyEvent pen && pen.isScored()) {
                        GoalEvent goal = new GoalEvent();
                        goal.setMatch(match);
                        goal.setTeam(pen.getTeam());
                        goal.setScorer(pen.getTaker());
                        goal.setMinute(minute);
                        goal.setScored(true);

                        long homeGoals = match.getGoals().stream()
                                .filter(g -> g.getTeam().equals(match.getHomeTeam()))
                                .count() + (goal.getTeam().equals(match.getHomeTeam()) ? 1 : 0);

                        long awayGoals = match.getGoals().stream()
                                .filter(g -> g.getTeam().equals(match.getAwayTeam()))
                                .count() + (goal.getTeam().equals(match.getAwayTeam()) ? 1 : 0);

                        goal.setScoreAfterGoal(String.format("%d:%d", homeGoals, awayGoals));
                        goal.apply();

                        MatchEventDTO goalDto = toDto(goal);
                        if (goalDto != null) {
                            log.info("Broadcasting goal posle penala");
                            try {
                                webSocketHandler.broadcastEvent(goalDto);
                                Thread.sleep(4400); // Još duže za gol
                            } catch (Exception e) {
                                log.error("WebSocket broadcast failed for goal", e);
                            }
                        }

                        match.getGoals().add(goal);
                        match.getAllMatchEvents().add(goal);
                    }

                    if (event instanceof InjuryEvent)
                        performSubstitution(match, context, isHomeTeam(event) ? homePlayers : awayPlayers, isHomeTeam(event));
                }
            }

            if (minute == 65) {
                performSubstitution(match, context, homePlayers, true);
                performSubstitution(match, context, awayPlayers, false);
            }
        }

        // Kraj meča
        MatchEndedEvent endEvent = new MatchEndedEvent();
        endEvent.setMinute(90);
        endEvent.setMatch(match);
        endEvent.apply();

        MatchEventDTO endDto = toDto(endEvent);
        if (endDto != null) {
            log.info("Broadcasting end event");
            try {
                webSocketHandler.broadcastEvent(endDto);
            } catch (Exception e) {
                log.error("Greška pri broadcastu end eventa", e);
            }
        } else {
            log.warn("End DTO je null – nije poslat");
        }

        log.info("[90'] Event: {}", endEvent.getDescription());
        Thread.sleep(5000); // Duže čekanje na kraju da UI vidi rezultat

        match.setPlayed(true);
    }

    // OSTALE METODE (performSubstitution, updateFatigue, updatePossession, isHomeTeam, eventProbability) OSTAJU ISTE
    // Samo ih zadrži u klasi kao što su bile

    private void performSubstitution(Match match, MatchContext context, List<Player> teamPlayers, boolean isHomeTeam) {
        if (teamPlayers.size() < 12) return;

        Player out = teamPlayers.get(random.nextInt(11));
        Player in = teamPlayers.get(11 + random.nextInt(teamPlayers.size() - 11));

        SubstitutionEvent sub = new SubstitutionEvent();
        sub.setMatch(match);
        sub.setMinute(context.getCurrentMinute());
        sub.setPlayerOut(out);
        sub.setPlayerIn(in);
        sub.apply();

        log.info("[{}'] Substitution: {} out, {} in", context.getCurrentMinute(), out.getName(), in.getName());

        MatchEventDTO subDto = toDto(sub);
        if (subDto != null) {
            try {
                webSocketHandler.broadcastEvent(subDto);
                Thread.sleep(3500);
            } catch (Exception e) {
                log.error("Greška pri broadcastu zamene", e);
            }
        }

        teamPlayers.remove(out);
        teamPlayers.add(in);
    }

    private void updateFatigue(MatchContext context) {
        context.setFatigueFactor(Math.max(0.7, context.getFatigueFactor() - 0.002));
        log.info("Minute: {}, Fatigue Factor: {}", context.getCurrentMinute(), context.getFatigueFactor());
    }

    private void updatePossession(MatchContext context, List<Player> homePlayers, List<Player> awayPlayers,
                                  Formation homeFormation, Formation awayFormation) {
        double homeStrength = TeamStrengthCalculator.calculateTeamStrength(homePlayers, homeFormation, context.getHomeTactics(), true);
        double awayStrength = TeamStrengthCalculator.calculateTeamStrength(awayPlayers, awayFormation, context.getAwayTactics(), false);
        double total = homeStrength + awayStrength;

        if (random.nextDouble() < homeStrength / total) {
            context.setPossessionTeam(context.getMatch().getHomeTeam());
        } else {
            context.setPossessionTeam(context.getMatch().getAwayTeam());
        }
        log.info("Minute: {}, Possession: {}", context.getCurrentMinute(), context.getPossessionTeam().getName());
    }

    private boolean isHomeTeam(MatchEvent event) {
        if (event instanceof GoalEvent goal) return goal.getTeam().equals(goal.getMatch().getHomeTeam());
        if (event instanceof SubstitutionEvent sub) return sub.getPlayerOut().getTeam().equals(sub.getMatch().getHomeTeam());
        return false;
    }

    private double eventProbability(MatchContext context, Match match) {
        double base = 0.1;
        double strengthFactor = 0.2;
        return Math.min(0.3, base + strengthFactor * context.getFatigueFactor());
    }
}