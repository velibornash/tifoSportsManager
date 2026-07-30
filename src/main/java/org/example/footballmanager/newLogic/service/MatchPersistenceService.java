package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.MatchEventRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchTickStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class MatchPersistenceService {

    private final MatchRepository matchRepository;
    private final MatchEventRepository matchEventRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final MatchTickStateRepository matchTickStateRepository;
    private final ObjectMapper objectMapper;

    public MatchPersistenceService(MatchRepository matchRepository,
                                   MatchEventRepository matchEventRepository,
                                   MatchPlayerStatsRepository matchPlayerStatsRepository,
                                   MatchTickStateRepository matchTickStateRepository) {
        this.matchRepository = matchRepository;
        this.matchEventRepository = matchEventRepository;
        this.matchPlayerStatsRepository = matchPlayerStatsRepository;
        this.matchTickStateRepository = matchTickStateRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void saveMatchResult(MatchResult result, Match match) {
        updateMatchEntity(match, result);
        matchRepository.save(match);
        saveMatchEvents(result, match);
        savePlayerStats(result, match);
        saveTickHistory(result, match);
    }

    private void updateMatchEntity(Match match, MatchResult result) {
        match.setHomeGoals(result.homeGoals());
        match.setAwayGoals(result.awayGoals());
        match.setPossessionHome(result.homePossession());
        match.setPossessionAway(result.awayPossession());
        match.setPlayed(true);
        match.setFinished(true);
        match.setEventJson(serializeEvents(result.events()));
    }

    private String serializeEvents(List<MatchEvent> events) {
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void saveMatchEvents(MatchResult result, Match match) {
        for (MatchEvent event : result.events()) {
            matchEventRepository.save(event);
        }
    }

    private void savePlayerStats(MatchResult result, Match match) {
        Team homeTeam = match.getHomeTeam();
        Team awayTeam = match.getAwayTeam();
        Map<Long, PlayerStatsAccumulator> accs = new HashMap<>();

        for (MatchEvent event : result.events()) {
            if (event instanceof GoalEvent g) {
                accs.computeIfAbsent(g.scorerId(), k -> mkAcc(g.scorerName(), sideTeam(g.teamSide(), homeTeam, awayTeam))).addGoal();
                if (g.assistantId() != null) {
                    accs.computeIfAbsent(g.assistantId(), k -> mkAcc(g.assistantName(), sideTeam(g.teamSide(), homeTeam, awayTeam))).addAssist();
                }
            } else if (event instanceof ShotEvent s) {
                accs.computeIfAbsent(s.shooterId(), k -> mkAcc(s.shooterName(), sideTeam(s.teamSide(), homeTeam, awayTeam))).addShot();
            } else if (event instanceof PassEvent p) {
                accs.computeIfAbsent(p.passerId(), k -> mkAcc(p.passerName(), sideTeam(p.teamSide(), homeTeam, awayTeam))).addPass(p.completed());
            } else if (event instanceof FoulEvent f) {
                accs.computeIfAbsent(f.takerId(), k -> mkAcc(f.takerName(), sideTeam(f.teamSide(), homeTeam, awayTeam))).addFoul();
            } else if (event instanceof CardEvent c) {
                accs.computeIfAbsent(c.playerId(), k -> mkAcc(c.playerName(), sideTeam(c.teamSide(), homeTeam, awayTeam))).addCard(c.cardType() == CardEvent.CardType.YELLOW);
            } else if (event instanceof DuelEvent d) {
                accs.computeIfAbsent(d.player1Id(), k -> mkAcc(d.player1Name(), sideTeam(d.teamSide(), homeTeam, awayTeam))).addDuel(d.attackerWon());
            } else if (event instanceof CrossEvent cr) {
                accs.computeIfAbsent(cr.crosserId(), k -> mkAcc(cr.crosserName(), sideTeam(cr.teamSide(), homeTeam, awayTeam))).addCross();
            } else if (event instanceof CrossHeaderEvent ch) {
                accs.computeIfAbsent(ch.headerId(), k -> mkAcc(ch.headerName(), sideTeam(ch.teamSide(), homeTeam, awayTeam))).addHeader();
            } else if (event instanceof TackleEvent t) {
                accs.computeIfAbsent(t.defenderId(), k -> mkAcc(t.defenderName(), sideTeam(t.defenderTeamSide(), homeTeam, awayTeam))).addTackle(t.success());
            } else if (event instanceof InjuryEvent i) {
                accs.computeIfAbsent(i.playerId(), k -> mkAcc(i.playerName(), sideTeam(i.teamSide(), homeTeam, awayTeam))).addInjury();
            } else if (event instanceof SubstitutionEvent sub) {
                accs.computeIfAbsent(sub.playerInId(), k -> mkAcc(sub.playerInName(), sideTeam(sub.teamSide(), homeTeam, awayTeam))).setSubIn(true);
            }
        }

        if (!result.tickHistory().isEmpty()) {
            for (PlayerSnapshot snap : result.tickHistory().get(0).players()) {
                accs.computeIfAbsent(snap.playerId(), k -> mkAcc(snap.name(), sideTeam(snap.teamSide(), homeTeam, awayTeam)));
            }
        }

        List<MatchPlayerStats> statsList = new ArrayList<>();
        for (Map.Entry<Long, PlayerStatsAccumulator> e : accs.entrySet()) {
            PlayerStatsAccumulator a = e.getValue();
            if (a.team == null) continue;
            MatchPlayerStats s = new MatchPlayerStats();
            s.setMatch(match);
            Player p = new Player();
            p.setId(e.getKey());
            s.setPlayer(p);
            s.setGoals(a.goals);
            s.setAssists(a.assists);
            s.setYellowCards(a.yellowCards);
            s.setRedCards(a.redCards);
            s.setMinutesPlayed(a.minutesPlayed);
            s.setRating(a.rating);
            matchPlayerStatsRepository.save(s);
        }
    }

    private void saveTickHistory(MatchResult result, Match match) {
        if (result.tickHistory().isEmpty()) return;
        matchTickStateRepository.deleteByMatch(match);
        for (TickSnapshot tick : result.tickHistory()) {
            try {
                String playersJson = objectMapper.writeValueAsString(
                    tick.players().stream()
                        .map(sp -> new PlayerPosDTO(sp.playerId(), sp.name(), sp.x(), sp.y(), sp.teamSide(), sp.hasBall(), sp.position().name()))
                        .collect(Collectors.toList())
                );
                String ballJson = objectMapper.writeValueAsString(new BallPosDTO(tick.ball().x(), tick.ball().y()));
                Integer carrierId = tick.carrierId() != null ? tick.carrierId().intValue() : null;
                Integer receiverId = tick.pendingReceiverId() != null ? tick.pendingReceiverId().intValue() : null;
                matchTickStateRepository.save(new MatchTickState(match, tick.tick(), playersJson, ballJson, carrierId, tick.ballInTransit(), receiverId));
            } catch (Exception e) { /* skip */ }
        }
    }

    private record PlayerPosDTO(long playerId, String name, double x, double y, String teamSide, boolean hasBall, String position) {}
    private record BallPosDTO(double x, double y) {}

    private PlayerPosDTO toPlayerPos(PlayerSnapshot snap) {
        return new PlayerPosDTO(snap.playerId(), snap.name(), snap.x(), snap.y(), snap.teamSide(), snap.hasBall(), snap.position().name());
    }

    private Team sideTeam(String side, Team home, Team away) {
        return "HOME".equals(side) ? home : away;
    }

    private PlayerStatsAccumulator mkAcc(String name, Team team) {
        return new PlayerStatsAccumulator(name, team);
    }

    private static class PlayerStatsAccumulator {
        final String name;
        final Team team;
        int goals, assists, yellowCards, redCards, minutesPlayed = 90, rating = 60;

        PlayerStatsAccumulator(String name, Team team) { this.name = name; this.team = team; }

        void addGoal() { goals++; rating += 5; }
        void addAssist() { assists++; rating += 3; }
        void addShot() {}
        void addPass(boolean completed) { if (completed) rating += 1; }
        void addFoul() { rating -= 1; }
        void addCard(boolean yellow) { if (yellow) { yellowCards++; rating -= 2; } else { redCards++; rating -= 5; } }
        void addDuel(boolean won) { rating += won ? 1 : -1; }
        void addCross() { rating += 1; }
        void addHeader() { rating += 1; }
        void addTackle(boolean won) { rating += won ? 2 : -2; }
        void addInjury() { rating -= 10; minutesPlayed = Math.max(15, minutesPlayed - 30); }
        void setSubIn(boolean v) {}
    }
}
