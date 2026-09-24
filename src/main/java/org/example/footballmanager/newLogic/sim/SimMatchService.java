package org.example.footballmanager.newLogic.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.sim.recording.SimReplayView;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.example.footballmanager.newLogic.sim.result.SimReportMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.footballmanager.newLogic.service.SeasonService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    private final ObjectMapper objectMapper;

    /** Simulate a full match. Returns the outcome plus an in-memory replay key (-1 if none requested). */
    public SimMatchOutcome simulate(String homeName, String awayName, boolean storeReplay) {
        var orchestrator = SimMatchRunner.run(homeName, awayName, SimMatchRunner.FULL_MATCH_TICKS);
        ProposalMatchOutcome outcome = orchestrator.buildOutcome();

        long replayId = -1L;
        if (storeReplay) {
            replayId = replayStore.store(SimReplayView.build(orchestrator, homeName, awayName));
        }
        return new SimMatchOutcome(outcome, replayId);
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

            if (outcome != null) {
                match.setEventJson(SimReportMapper.eventJson(objectMapper, outcome));
                match.setStatsJson(objectMapper.writeValueAsString(SimReportMapper.statsMap(outcome)));
                match.setLineupJson(SimReportMapper.lineupJson(objectMapper, outcome));
            }

            match = matchRepository.save(match);

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

    public record SimMatchOutcome(ProposalMatchOutcome outcome, long replayId) {
        public int homeGoals() { return outcome != null ? outcome.homeGoals() : 0; }
        public int awayGoals() { return outcome != null ? outcome.awayGoals() : 0; }
    }
}