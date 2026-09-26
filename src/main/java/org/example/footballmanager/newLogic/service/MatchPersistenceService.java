package org.example.footballmanager.newLogic.service;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.MatchEventRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchTickStateRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.util.match.MatchRatingCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class MatchPersistenceService {

    private final MatchRepository matchRepository;
    private final MatchEventRepository matchEventRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final MatchTickStateRepository matchTickStateRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PlayerRepository playerRepository;
    private final MatchTeamStatsService matchTeamStatsService;
    private final ObjectMapper objectMapper;

    public MatchPersistenceService(MatchRepository matchRepository,
                                   MatchEventRepository matchEventRepository,
                                   MatchPlayerStatsRepository matchPlayerStatsRepository,
                                   MatchTickStateRepository matchTickStateRepository,
                                   SeasonCompetitionRepository seasonCompetitionRepository,
                                   CompetitionEntryRepository competitionEntryRepository,
                                   PlayerRepository playerRepository,
                                   MatchTeamStatsService matchTeamStatsService) {
        this.matchRepository = matchRepository;
        this.matchEventRepository = matchEventRepository;
        this.matchPlayerStatsRepository = matchPlayerStatsRepository;
        this.matchTickStateRepository = matchTickStateRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.playerRepository = playerRepository;
        this.matchTeamStatsService = matchTeamStatsService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void saveMatchResult(MatchResult result, Match match) {
        updateMatchEntity(match, result);
        matchRepository.save(match);
        saveMatchEvents(result, match);
        List<MatchPlayerStats> playerStats = savePlayerStats(result, match);
        saveMatchTeamStats(result, match, playerStats);
        saveTickHistory(result, match);
    }

    public void saveMatchResultAndUpdateTable(MatchResult result, Match match) {
        saveMatchResult(result, match);
        updateLeagueTable(match);
    }

    public void updateLeagueTable(Match match) {
        try {
            Integer seasonYear = match.getSeasonYear();
            if (seasonYear == null) {
                log.debug("Match {} has no seasonYear, skipping table update", match.getId());
                return;
            }

            List<SeasonCompetition> seasonCompetitions = seasonCompetitionRepository.findBySeasonYear(seasonYear);
            if (seasonCompetitions.isEmpty()) {
                log.debug("No season competitions found for year {}", seasonYear);
                return;
            }

            for (SeasonCompetition sc : seasonCompetitions) {
                recalculateTableForSeasonCompetition(sc);
            }
            log.info("League table updated after match {} ({})", match.getId(), match.getHomeTeam().getName() + " vs " + match.getAwayTeam().getName());
        } catch (Exception e) {
            log.error("Failed to update league table after match {}: {}", match.getId(), e.getMessage());
        }
    }

    private void recalculateTableForSeasonCompetition(SeasonCompetition sc) {
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        if (entries.isEmpty()) return;

        Map<Long, CompetitionEntry> byTeamId = entries.stream()
                .filter(e -> e.getTeam() != null && e.getTeam().getId() != null)
                .collect(Collectors.toMap(e -> e.getTeam().getId(), e -> e));

        entries.forEach(e -> {
            e.setPoints(0);
            e.setGoalsScored(0);
            e.setGoalsConceded(0);
            e.setWins(0);
            e.setDraws(0);
            e.setLosses(0);
        });

        List<Match> playedMatches = matchRepository
                .findByCompetitionIdAndSeasonYear(sc.getCompetition().getId(), sc.getSeasonYear())
                .stream()
                .filter(Match::isPlayed)
                .filter(m -> m.getHomeTeam() != null && m.getAwayTeam() != null)
                .toList();

        for (Match m : playedMatches) {
            CompetitionEntry homeEntry = byTeamId.get(m.getHomeTeam().getId());
            CompetitionEntry awayEntry = byTeamId.get(m.getAwayTeam().getId());
            if (homeEntry == null || awayEntry == null) continue;

            int homeG = m.getHomeGoals();
            int awayG = m.getAwayGoals();

            homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeG);
            homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayG);
            awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayG);
            awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeG);

            if (homeG > awayG) {
                homeEntry.setWins(homeEntry.getWins() + 1);
                awayEntry.setLosses(awayEntry.getLosses() + 1);
                homeEntry.setPoints(homeEntry.getPoints() + 3);
            } else if (awayG > homeG) {
                awayEntry.setWins(awayEntry.getWins() + 1);
                homeEntry.setLosses(homeEntry.getLosses() + 1);
                awayEntry.setPoints(awayEntry.getPoints() + 3);
            } else {
                homeEntry.setDraws(homeEntry.getDraws() + 1);
                awayEntry.setDraws(awayEntry.getDraws() + 1);
                homeEntry.setPoints(homeEntry.getPoints() + 1);
                awayEntry.setPoints(awayEntry.getPoints() + 1);
            }
        }

        List<CompetitionEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(CompetitionEntry::getPoints, Comparator.reverseOrder())
                .thenComparing(e -> e.getGoalsScored() - e.getGoalsConceded(), Comparator.reverseOrder())
                .thenComparing(CompetitionEntry::getGoalsScored, Comparator.reverseOrder()));

        for (int pos = 0; pos < sorted.size(); pos++) {
            sorted.get(pos).setPosition(pos + 1);
        }
        competitionEntryRepository.saveAll(sorted);
    }

    private void updateMatchEntity(Match match, MatchResult result) {
        match.setHomeGoals(result.homeGoals());
        match.setAwayGoals(result.awayGoals());
        match.setPossessionHome(result.homePossession());
        match.setPossessionAway(result.awayPossession());
        match.setHomeFormation(result.homeFormation());
        match.setAwayFormation(result.awayFormation());
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

    private void saveMatchTeamStats(MatchResult result, Match match, List<MatchPlayerStats> playerStats) {
        try {
            MatchTeamStats stats = matchTeamStatsService.compute(match, result, playerStats);
            match.setStatsJson(objectMapper.writeValueAsString(stats.toMap()));
        } catch (Exception e) {
            log.warn("Failed to persist team stats for match {}: {}", match.getId(), e.getMessage());
        }
    }

    private List<MatchPlayerStats> savePlayerStats(MatchResult result, Match match) {
        if (match.getHomeTeam() == null || match.getAwayTeam() == null) return List.of();
        Team homeTeam = match.getHomeTeam();
        Team awayTeam = match.getAwayTeam();
        Map<Long, PlayerStatsAccumulator> accs = new HashMap<>();

        for (MatchEvent event : result.events()) {
            if (event instanceof GoalEvent g) {
                accs.computeIfAbsent(g.scorerId(), k -> mkAcc(g.scorerName(), sideTeam(g.teamSide(), homeTeam, awayTeam))).addGoals(1);
                if (g.assistantId() != null) {
                    accs.computeIfAbsent(g.assistantId(), k -> mkAcc(g.assistantName(), sideTeam(g.teamSide(), homeTeam, awayTeam))).addAssists(1);
                }
            } else if (event instanceof ShotEvent s) {
                PlayerStatsAccumulator a = accs.computeIfAbsent(s.shooterId(), k -> mkAcc(s.shooterName(), sideTeam(s.teamSide(), homeTeam, awayTeam)));
                a.addShot();
                if (s.onTarget() || s.isGoal()) a.shotsOnTarget++;
            } else if (event instanceof ShotSavedEvent ss1) {
                PlayerStatsAccumulator a = accs.computeIfAbsent(ss1.shooterId(), k -> mkAcc(ss1.shooterName(), sideTeam(ss1.teamSide(), homeTeam, awayTeam)));
                a.addShot();
                a.shotsOnTarget++;
                accs.computeIfAbsent(ss1.goalkeeperId(), k -> mkAcc(ss1.goalkeeperName(), sideTeam(opposite(ss1.teamSide()), homeTeam, awayTeam))).addSave();
            } else if (event instanceof ShotMissedEvent sm) {
                PlayerStatsAccumulator a = accs.computeIfAbsent(sm.shooterId(), k -> mkAcc(sm.shooterName(), sideTeam(sm.teamSide(), homeTeam, awayTeam)));
                a.addShot();
            } else if (event instanceof ShotBlockedEvent sb) {
                accs.computeIfAbsent(sb.shooterId(), k -> mkAcc(sb.shooterName(), sideTeam(sb.teamSide(), homeTeam, awayTeam))).addShot();
            } else if (event instanceof CrossHeaderEvent hd) {
                if (hd.headerId() != 0) {
                    PlayerStatsAccumulator a = accs.computeIfAbsent(hd.headerId(), k -> mkAcc(hd.headerName(), sideTeam(hd.teamSide(), homeTeam, awayTeam)));
                    a.addShot();
                    if (hd.onTarget()) a.shotsOnTarget++;
                }
            } else if (event instanceof PassEvent p) {
                accs.computeIfAbsent(p.passerId(), k -> mkAcc(p.passerName(), sideTeam(p.teamSide(), homeTeam, awayTeam))).addPass(p.completed());
            } else if (event instanceof PassIncompleteEvent pi) {
                accs.computeIfAbsent(pi.passerId(), k -> mkAcc(pi.passerName(), sideTeam(pi.teamSide(), homeTeam, awayTeam))).addPass(false);
            } else if (event instanceof PassInterceptedEvent pi) {
                accs.computeIfAbsent(pi.interceptorId(), k -> mkAcc(pi.interceptorName(), sideTeam(pi.interceptorTeamSide(), homeTeam, awayTeam))).addInterception();
            } else if (event instanceof GkSaveEvent gk) {
                accs.computeIfAbsent(gk.goalkeeperId(), k -> mkAcc(gk.goalkeeperName(), sideTeam(gk.teamSide(), homeTeam, awayTeam))).addSave();
            } else if (event instanceof FoulEvent f) {
                accs.computeIfAbsent(f.takerId(), k -> mkAcc(f.takerName(), sideTeam(f.teamSide(), homeTeam, awayTeam))).addFoul();
            } else if (event instanceof CardEvent c) {
                accs.computeIfAbsent(c.playerId(), k -> mkAcc(c.playerName(), sideTeam(c.teamSide(), homeTeam, awayTeam))).addCard(c.cardType() == CardEvent.CardType.YELLOW);
            } else if (event instanceof DuelEvent d) {
                accs.computeIfAbsent(d.player1Id(), k -> mkAcc(d.player1Name(), sideTeam(d.teamSide(), homeTeam, awayTeam))).addDuel(d.attackerWon());
            } else if (event instanceof TackleEvent t) {
                accs.computeIfAbsent(t.defenderId(), k -> mkAcc(t.defenderName(), sideTeam(t.defenderTeamSide(), homeTeam, awayTeam))).addTackle(t.success());
            } else if (event instanceof CrossEvent cr) {
                accs.computeIfAbsent(cr.crosserId(), k -> mkAcc(cr.crosserName(), sideTeam(cr.teamSide(), homeTeam, awayTeam))).addCross();
            } else if (event instanceof CrossHeaderEvent ch) {
                accs.computeIfAbsent(ch.headerId(), k -> mkAcc(ch.headerName(), sideTeam(ch.teamSide(), homeTeam, awayTeam))).addHeader();
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

        int homeScored = result.homeGoals();
        int awayScored = result.awayGoals();

        List<MatchPlayerStats> saved = new ArrayList<>();

        for (Map.Entry<Long, PlayerStatsAccumulator> e : accs.entrySet()) {
            PlayerStatsAccumulator a = e.getValue();
            if (a.team == null) continue;
            Long playerId = e.getKey();
            if (playerId == null) continue;

            Player p = null;
            try {
                p = playerRepository.findById(playerId).orElse(null);
            } catch (Exception ex) {
                log.warn("Error finding player {}: {}", playerId, ex.getMessage());
                continue;
            }
            if (p == null) {
                log.debug("Player {} not found in DB, skipping stats", playerId);
                continue;
            }

            boolean isHome = homeTeam.equals(a.team);
            int teamGoals = isHome ? homeScored : awayScored;
            int conceded = isHome ? awayScored : homeScored;
            boolean cleanSheet = conceded == 0 && a.minutesPlayed >= 60
                    && (p.getPositionEnum() == Position.GK || p.getPositionEnum() == Position.DEF);
            int rating = MatchRatingCalculator.calculate(
                    p, a.goals, a.assists, a.interceptions, a.saves, cleanSheet,
                    a.yellowCards, a.redCards, teamGoals, conceded, a.minutesPlayed);

            MatchPlayerStats s = new MatchPlayerStats();
            s.setMatch(match);
            s.setPlayer(p);
            s.setGoals(a.goals);
            s.setAssists(a.assists);
            s.setYellowCards(a.yellowCards);
            s.setRedCards(a.redCards);
            s.setMinutesPlayed(a.minutesPlayed);
            s.setRating(rating);
            s.setInterceptions(a.interceptions);
            s.setSaves(a.saves);
            s.setCleanSheet(cleanSheet);
            s.setShots(a.shots);
            s.setPassesAttempted(a.passesAttempted);
            s.setPassesCompleted(a.passesCompleted);
            saved.add(s);
            matchPlayerStatsRepository.save(s);
        }

        return saved;
    }

    private void saveTickHistory(MatchResult result, Match match) {
        if (result.tickHistory().isEmpty()) return;
        matchTickStateRepository.deleteByMatch(match);
        int saved = 0;
        int skipped = 0;
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
                saved++;
            } catch (Exception ex) {
                // Previously swallowed with `catch (Exception e) { }`. That hid a real defect: the
                // match_tick_states table did not exist on H2 (two incompatible column mappings),
                // so every tick silently failed to persist and replay playback had no data - with
                // no error anywhere. Tick granularity means a few bad frames are not worth
                // failing the whole match over, so the save is still best-effort, but the count
                // is now logged so a systemic failure is impossible to miss.
                skipped++;
                if (skipped <= 3) {
                    log.warn("Tick {} of match {} could not be persisted: {}",
                            tick.tick(), match.getId(), ex.toString());
                }
            }
        }
        if (skipped > 0) {
            log.warn("Tick history for match {} partially persisted: {} saved, {} skipped. "
                    + "Replay playback for this match will be incomplete.", match.getId(), saved, skipped);
        } else {
            log.debug("Tick history for match {} persisted: {} ticks.", match.getId(), saved);
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

    private static String opposite(String side) {
        return "HOME".equals(side) ? "AWAY" : "HOME";
    }

    private PlayerStatsAccumulator mkAcc(String name, Team team) {
        return new PlayerStatsAccumulator(name, team);
    }

    private static class PlayerStatsAccumulator {
        final String name;
        final Team team;
        int goals, assists, yellowCards, redCards;
        int interceptions, saves;
        int shots, shotsOnTarget;
        int passesAttempted, passesCompleted;
        int minutesPlayed = 90;

        PlayerStatsAccumulator(String name, Team team) { this.name = name; this.team = team; }

        void addGoals(int n) { goals += n; }
        void addAssists(int n) { assists += n; }
        void addShot() { shots++; }
        void addPass(boolean completed) {
            passesAttempted++;
            if (completed) passesCompleted++;
        }
        void addInterception() { interceptions++; }
        void addSave() { saves++; }
        void addFoul() {}
        void addCard(boolean yellow) { if (yellow) yellowCards++; else redCards++; }
        void addDuel(boolean won) {}
        void addCross() {}
        void addHeader() {}
        void addTackle(boolean won) {}
        void addInjury() { minutesPlayed = Math.max(15, minutesPlayed - 30); }
        void setSubIn(boolean v) {}
    }
}
