package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.TacticsRuleDTO;
import org.example.footballmanager.newLogic.dto.TacticsSlotDTO;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.service.FormationSlotCatalog;
import org.example.footballmanager.newLogic.service.TeamTacticsService;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncSimulationRunner {

    private final MatchStore matchStore;
    private final MatchFixtureRepository matchFixtureRepository;
    private final MatchRepository matchRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final TeamTacticsService teamTacticsService;
    private final FormationSlotCatalog formationSlotCatalog;
    private final SeasonService seasonService;
    private final LineupRepository lineupRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger simulatedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);

    public boolean isRunning() { return running.get(); }
    public int getSimulatedCount() { return simulatedCount.get(); }
    public int getTotalCount() { return totalCount.get(); }

    @Async
    public void simulateInBackground(List<Long> fixtureIds) {
        log.info("Background simulation started for {} fixtures", fixtureIds.size());
        if (!running.compareAndSet(false, true)) {
            log.warn("Background simulation already running");
            return;
        }
        totalCount.set(fixtureIds.size());
        simulatedCount.set(0);

        transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            for (Long fixtureId : fixtureIds) {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        MatchFixture fixture = matchFixtureRepository.findById(fixtureId).orElse(null);
                        if (fixture == null || fixture.isPlayed()) return;
                        if (fixture.getHomeTeam() == null || fixture.getAwayTeam() == null) return;

                        // Učitaj timove unutar transakcije
                        String homeName = fixture.getHomeTeam().getName();
                        String awayName = fixture.getAwayTeam().getName();
                        Long homeTeamId = fixture.getHomeTeam().getId();
                        Long awayTeamId = fixture.getAwayTeam().getId();

                        // Load tactical editor rules
                        TacticsWithSlots homeTactics = loadTacticsForTeam(homeTeamId);
                        TacticsWithSlots awayTactics = loadTacticsForTeam(awayTeamId);

                        MatchOrchestrator orchestrator = new MatchOrchestrator(matchStore, teamRepository, playerRepository, lineupRepository);
                        long matchStoreId = orchestrator.startMatch(
                            homeName, awayName,
                            homeTactics != null ? homeTactics.rules() : null,
                            homeTactics != null ? homeTactics.slots() : null,
                            awayTactics != null ? awayTactics.rules() : null,
                            awayTactics != null ? awayTactics.slots() : null
                        );
                        org.example.footballmanager.newLogic.model.Match storeMatch = matchStore.getMatch(matchStoreId);
                        if (storeMatch != null) {
                            storeMatch.setUserMatch(false);
                        }
                        orchestrator.simulate(matchStoreId);
                        MatchResult result = matchStore.getResult(matchStoreId);

                        persistMatchToDB(fixture, result, matchStoreId);
                    });
                    simulatedCount.incrementAndGet();
                    if (simulatedCount.get() % 10 == 0) {
                        log.info("Background progress: {}/{}", simulatedCount.get(), totalCount.get());
                    }
                } catch (Exception e) {
                    log.error("Failed to simulate fixture {} in background", fixtureId, e);
                }
            }
            log.info("Background simulation complete: {} fixtures", simulatedCount.get());
        } finally {
            running.set(false);
            log.info("Background simulation runner stopped");
        }
    }

    private void persistMatchToDB(MatchFixture fixture, MatchResult result, long matchStoreId) {
        Match match = new Match();
        match.setHomeTeam(fixture.getHomeTeam());
        match.setAwayTeam(fixture.getAwayTeam());
        match.setCompetition(fixture.getCompetition());
        match.setSeasonYear(fixture.getSeasonYear());
        match.setRoundNumber(fixture.getRoundNumber());
        match.setWeekNumber(fixture.getWeekNumber());
        match.setMatchDate(fixture.getMatchDate() != null ? fixture.getMatchDate() : java.time.LocalDateTime.now());
        match.setHomeGoals(result != null ? result.homeGoals() : 0);
        match.setAwayGoals(result != null ? result.awayGoals() : 0);
        match.setPossessionHome(result != null ? result.homePossession() : 50.0);
        match.setPossessionAway(result != null ? result.awayPossession() : 50.0);
        match.setPlayed(true);
        match.setStarted(true);
        match.setFinished(true);
        match.setReplayId(matchStoreId);
        match.setHomeResultRevealed(true);
        match.setAwayResultRevealed(true);

        if (result != null && result.events() != null) {
            try {
                match.setEventJson(objectMapper.writeValueAsString(result.events()));
            } catch (Exception e) {
                log.warn("Failed to serialize events for fixture {}", fixture.getId(), e);
            }
        }

        match = matchRepository.save(match);
        fixture.setPlayed(true);
        fixture.setPlayedMatch(match);
        matchFixtureRepository.save(fixture);

        updateLeagueTable(match, result);
    }

    private void updateLeagueTable(Match match, MatchResult result) {
        if (match.getCompetition() == null || match.getSeasonYear() == null) {
            log.warn("updateLeagueTable: competition or seasonYear is null for match {}", match.getId());
            return;
        }

        SeasonCompetition sc = seasonService.ensureSeasonCompetition(match.getCompetition(), match.getSeasonYear());

        int homeGoals = result != null ? result.homeGoals() : 0;
        int awayGoals = result != null ? result.awayGoals() : 0;

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
        log.debug("updateLeagueTable: Updated entries for {} vs {}", 
                match.getHomeTeam().getName(), match.getAwayTeam().getName());
    }

    private record TacticsWithSlots(TacticRules rules, List<String> slots) {}

    private TacticsWithSlots loadTacticsForTeam(Long teamId) {
        if (teamId == null) return null;
        try {
            org.example.footballmanager.newLogic.dto.TacticsEditorDTO editor = teamTacticsService.getTacticsEditor(teamId, null);
            if (editor == null) return null;

            List<String> slotKeys = editor.getSlotDefinitions().stream()
                .map(TacticsSlotDTO::getSlotKey).toList();
            List<TacticsRuleDTO> rulesList = editor.getMovementRules();

            TacticRules rules = TacticRules.createDefault(slotKeys);
            for (TacticsRuleDTO rule : rulesList) {
                if (rule == null) continue;
                boolean inPossession = "WE_HAVE_BALL".equals(rule.getPossessionContext());
                rules.setRule(rule.getSlotKey(), rule.getBallStateKey(), inPossession, rule.getTargetCellKey());
            }

            return new TacticsWithSlots(rules, slotKeys);
        } catch (Exception e) {
            log.warn("Failed to load tactics for teamId={}, using defaults", teamId, e);
            return null;
        }
    }
}
