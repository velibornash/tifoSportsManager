package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.exception.ApiException;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.MatchType;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.sim.SimMatchService;
import org.tifo.footballmanager.newLogic.sim.SimMatchService.SimMatchOutcome;
import org.tifo.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * A short practice match: half length, no side effects (P2-32).
 *
 * <p>Played inline, like an exhibition, but charges nothing — no fatigue, no injury risk,
 * no table, no ratings, no career record. Daily limit: one per club per game day.
 */
@Service
public class PracticeMatchService {

    private final TeamRepository teams;
    private final SimMatchService simMatchService;

    public PracticeMatchService(TeamRepository teams, SimMatchService simMatchService) {
        this.teams = teams;
        this.simMatchService = simMatchService;
    }

    @Transactional
    public Long playPractice(Long homeTeamId, Long awayTeamId, Integer seasonYear, Integer week, int gameDay) {
        Team home = requireTeam(homeTeamId, "home");
        Team away = requireTeam(awayTeamId, "away");
        if (home.getId().equals(away.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_TEAM",
                    "A club cannot play a practice match against itself.");
        }
        // Daily limit: one practice match per club per game day.
        if (home.getLastPracticeMatchDay() == gameDay) {
            throw new ApiException(HttpStatus.CONFLICT, "PRACTICE_LIMIT",
                    "You have already played a practice match today.");
        }

        var fixture = new MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setMatchType(MatchType.PRACTICE);
        fixture.setSeasonYear(seasonYear);
        fixture.setWeekNumber(week);
        fixture.setMatchDate(LocalDateTime.now());
        fixture.setPlayed(false);

        // Half length (1800 ticks = 45 min), no side effects.
        SimMatchOutcome outcome = simMatchService.simulate(fixture, false, false, 1800);
        ProposalMatchOutcome result = outcome == null ? null : outcome.outcome();
        if (result == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PRACTICE_NOT_PLAYED",
                    "The practice match could not be simulated.");
        }
        Long matchId = simMatchService.persist(fixture, result, -1L);

        // Record the daily limit.
        home.setLastPracticeMatchDay(gameDay);
        teams.save(home);

        return matchId;
    }

    private Team requireTeam(Long id, String label) {
        return teams.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND",
                        "The " + label + " club could not be found."));
    }
}
