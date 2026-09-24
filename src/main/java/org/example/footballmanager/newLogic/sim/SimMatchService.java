package org.example.footballmanager.newLogic.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.recording.SimReplayView;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.example.footballmanager.newLogic.sim.result.SimReportMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.footballmanager.newLogic.service.AttendanceService;
import org.example.footballmanager.newLogic.service.SeasonService;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Official match path: runs the sim engine headless for a fixture and persists
 * the outcome into the newLogic Match row — score, formations, statsJson (the
 * canonical report), report eventJson (goals timeline), lineupJson and the
 * league table. Optionally keeps a replay for the proposal viewer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimMatchService {

    private final MatchRepository matchRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final SeasonService seasonService;
    private final SimReplayStore replayStore;
    private final LineupRepository lineupRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final PlayerRepository playerRepository;
    private final AttendanceService attendanceService;
    private final ObjectMapper objectMapper;

    /** Simulate a full match between two DB teams using their real saved squads
     *  (mapped into the engine's 4-4-2 slot structure). Falls back to synthetic
     *  squads when a team has no lineup template. */
    public SimMatchOutcome simulate(MatchFixture fixture, boolean storeReplay) {
        Team homeTeam = fixture.getHomeTeam();
        Team awayTeam = fixture.getAwayTeam();
        String homeName = homeTeam != null ? homeTeam.getName() : "Home FC";
        String awayName = awayTeam != null ? awayTeam.getName() : "Away FC";

        List<Player> homeSquad = loadRealSquad(homeTeam, "HOME");
        List<Player> awaySquad = loadRealSquad(awayTeam, "AWAY");

        var orchestrator = SimMatchRunner.run(homeName, awayName, SimMatchRunner.FULL_MATCH_TICKS,
                homeSquad, awaySquad);
        ProposalMatchOutcome outcome = orchestrator.buildOutcome();

        long replayId = -1L;
        if (storeReplay) {
            replayId = replayStore.store(SimReplayView.build(orchestrator, homeName, awayName));
        }
        return new SimMatchOutcome(outcome, replayId, fixture.getHomeTeam(), fixture.getAwayTeam());
    }

    private List<Player> loadRealSquad(Team team, String side) {
        Lineup lineup = loadLineup(team);
        if (lineup == null) return null;
        return RealSquadFactory.buildSquad(lineup, side);
    }

    @Transactional
    public Long persist(MatchFixture fixture, ProposalMatchOutcome outcome, long replayId) {
        try {
            Match match = new Match();
            match.setHomeTeam(fixture.getHomeTeam());
            match.setAwayTeam(fixture.getAwayTeam());
            match.setCompetition(fixture.getCompetition());
            match.setSeasonYear(fixture.getSeasonYear());
            match.setRoundNumber(fixture.getRoundNumber());
            match.setWeekNumber(fixture.getWeekNumber());
            match.setMatchDate(fixture.getMatchDate() != null ? fixture.getMatchDate() : LocalDateTime.now());
            match.setHomeGoals(outcome != null ? outcome.homeGoals() : 0);
            match.setAwayGoals(outcome != null ? outcome.awayGoals() : 0);
            match.setPossessionHome(outcome != null ? outcome.possessionHome() : 50.0);
            match.setPossessionAway(outcome != null ? outcome.possessionAway() : 50.0);
            match.setHomeFormation(outcome != null ? outcome.homeFormation() : null);
            match.setAwayFormation(outcome != null ? outcome.awayFormation() : null);
            match.setPlayed(true);
            match.setStarted(true);
            match.setFinished(true);
            match.setReplayId(replayId);
            match.setHomeResultRevealed(true);
            match.setAwayResultRevealed(true);

            match.setHomeLineup(loadLineup(fixture.getHomeTeam()));
            match.setAwayLineup(loadLineup(fixture.getAwayTeam()));

            if (outcome != null) {
                match.setEventJson(SimReportMapper.eventJson(objectMapper, outcome));
                match.setStatsJson(objectMapper.writeValueAsString(SimReportMapper.statsMap(outcome)));
                match.setLineupJson(SimReportMapper.lineupJson(objectMapper, outcome));
            }

            attendanceService.ensureAttendance(match);
            match = matchRepository.save(match);

            if (outcome != null) {
                persistPlayerStats(match, outcome);
                bumpCareerStats(outcome);
            }

            fixture.setPlayed(true);
            fixture.setPlayedMatch(match);
            matchFixtureRepository.save(fixture);

            updateLeagueTable(match, outcome != null ? outcome.homeGoals() : 0, outcome != null ? outcome.awayGoals() : 0);

            log.info("Persisted sim match to DB: id={}, {} {} - {} {} (replayId={})",
                    match.getId(),
                    fixture.getHomeTeam().getName(), outcome != null ? outcome.homeGoals() : 0,
                    outcome != null ? outcome.awayGoals() : 0, fixture.getAwayTeam().getName(),
                    replayId);
            return match.getId();
        } catch (Exception e) {
            log.error("Failed to persist sim match for fixture={}", fixture.getId(), e);
            return null;
        }
    }

    private Lineup loadLineup(Team team) {
        if (team == null || team.getId() == null) return null;
        return lineupRepository
                .findFirstByTeamIdAndMatchIsNullOrderByIdDesc(team.getId())
                .orElse(null);
    }

    /** Writes one MatchPlayerStats row per real DB player who took part (synthetic
     *  fallback ids like "HOME-1" cannot resolve and are skipped). */
    private void persistPlayerStats(Match match, ProposalMatchOutcome outcome) {
        Team homeTeam = match.getHomeTeam();
        Team awayTeam = match.getAwayTeam();
        int homeConceded = outcome.awayGoals();
        int awayConceded = outcome.homeGoals();

        for (ProposalMatchOutcome.PlayerOutcome po : outcome.players()) {
            Long dbId = parsePlayerId(po.playerId());
            if (dbId == null) continue;
            org.example.footballmanager.newLogic.model.Player dbPlayer = playerRepository.findById(dbId).orElse(null);
            if (dbPlayer == null) continue;

            boolean isHome = homeTeam != null && homeTeam.getName().equals(po.teamName());
            int conceded = isHome ? homeConceded : awayConceded;
            boolean cleanSheet = conceded == 0
                    && po.minutesPlayed() >= 60
                    && (dbPlayer.getPositionEnum() == Position.GK || dbPlayer.getPositionEnum() == Position.DEF);

            MatchPlayerStats st = new MatchPlayerStats();
            st.setMatch(match);
            st.setPlayer(dbPlayer);
            st.setGoals(po.goals());
            st.setAssists(po.assists());
            st.setYellowCards(po.yellowCards());
            st.setRedCards(po.redCards());
            st.setMinutesPlayed(po.minutesPlayed());
            st.setRating(rating100(po.rating()));
            st.setInterceptions(po.interceptions());
            st.setSaves(po.saves());
            st.setCleanSheet(cleanSheet);
            st.setShots(po.shots());
            st.setPassesAttempted(po.passesAttempted());
            st.setPassesCompleted(po.passesCompleted());
            matchPlayerStatsRepository.save(st);
        }
    }

    /** Bumps career totalGoals/totalAssists and sets match rating (10-100 scale)
     *  on every real DB player who took part. */
    private void bumpCareerStats(ProposalMatchOutcome outcome) {
        Set<org.example.footballmanager.newLogic.model.Player> updated = new LinkedHashSet<>();
        for (ProposalMatchOutcome.PlayerOutcome po : outcome.players()) {
            Long dbId = parsePlayerId(po.playerId());
            if (dbId == null) continue;
            org.example.footballmanager.newLogic.model.Player dbPlayer = playerRepository.findById(dbId).orElse(null);
            if (dbPlayer == null) continue;
            dbPlayer.setTotalGoals(dbPlayer.getTotalGoals() + po.goals());
            dbPlayer.setTotalAssists(dbPlayer.getTotalAssists() + po.assists());
            int rating = rating100(po.rating());
            if (rating > 0) dbPlayer.setRating(rating);
            if (updated.add(dbPlayer)) {
                playerRepository.save(dbPlayer);
            }
        }
    }

    private static Long parsePlayerId(String playerId) {
        try {
            return playerId != null && !playerId.isEmpty() ? Long.valueOf(playerId) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int rating100(double rating10) {
        return (int) Math.round(Math.max(0.0, Math.min(10.0, rating10)) * 10.0);
    }

    private void updateLeagueTable(Match match, int homeGoals, int awayGoals) {
        if (match.getCompetition() == null || match.getSeasonYear() == null) return;

        SeasonCompetition sc = seasonService.ensureSeasonCompetition(match.getCompetition(), match.getSeasonYear());

        CompetitionEntry homeEntry = seasonService.findOrCreateEntry(sc, match.getHomeTeam());
        CompetitionEntry awayEntry = seasonService.findOrCreateEntry(sc, match.getAwayTeam());

        if (homeGoals > awayGoals) { homeEntry.setPoints(homeEntry.getPoints() + 3); homeEntry.setWins(homeEntry.getWins() + 1); }
        else if (homeGoals == awayGoals) { homeEntry.setPoints(homeEntry.getPoints() + 1); homeEntry.setDraws(homeEntry.getDraws() + 1); }
        else { homeEntry.setLosses(homeEntry.getLosses() + 1); }
        homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeGoals);
        homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayGoals);

        if (awayGoals > homeGoals) { awayEntry.setPoints(awayEntry.getPoints() + 3); awayEntry.setWins(awayEntry.getWins() + 1); }
        else if (homeGoals == awayGoals) { awayEntry.setPoints(awayEntry.getPoints() + 1); awayEntry.setDraws(awayEntry.getDraws() + 1); }
        else { awayEntry.setLosses(awayEntry.getLosses() + 1); }
        awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayGoals);
        awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeGoals);

        competitionEntryRepository.saveAll(List.of(homeEntry, awayEntry));
    }

    public record SimMatchOutcome(ProposalMatchOutcome outcome, long replayId,
                                  Team homeTeam, Team awayTeam) {
        public int homeGoals() { return outcome != null ? outcome.homeGoals() : 0; }
        public int awayGoals() { return outcome != null ? outcome.awayGoals() : 0; }
    }
}