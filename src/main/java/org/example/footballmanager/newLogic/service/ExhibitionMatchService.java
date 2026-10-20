package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.exception.ApiException;
import org.example.footballmanager.newLogic.model.MatchType;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.sim.SimMatchService.SimMatchOutcome;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * A manager's practice match: played in full, changing nothing (P2-8).
 *
 * <p>The competitive analysis calls this the feature that "multiplies the value of 14,880 clubs" — it is
 * the one thing that makes the size of the world an asset rather than a cost, because it gives a human
 * manager somewhere to spend a season with fourteen thousand clubs he will never manage.
 *
 * <p><b>Played inline rather than through the matchday job</b>, and that is the whole design. A fixture
 * with a competition would be picked up by {@code MatchdayJob}, put in a table by
 * {@code updateLeagueTable}, and then <em>counted again</em> by
 * {@code LeagueTableReconciliationService}, which rebuilds tables from {@code match} rows and knows
 * nothing about practice matches. A fixture with no competition and no matchday is never picked up at
 * all — which is exactly why the friendly fixtures this codebase already had
 * ({@code FriendlyRequestService.createFixture}) <b>could never be played</b>.
 *
 * <p>So the fixture is built here, marked {@link MatchType#EXHIBITION}, and played on the spot through
 * the same {@code SimMatchService} path a competitive match takes. Every rule about what an exhibition
 * changes lives in {@link MatchType}, and is enforced there rather than here — this class only decides
 * <em>that</em> it is an exhibition, never what that means.
 */
@Service
public class ExhibitionMatchService {

    private final TeamRepository teams;
    private final SimMatchService simMatchService;

    public ExhibitionMatchService(TeamRepository teams, SimMatchService simMatchService) {
        this.teams = teams;
        this.simMatchService = simMatchService;
    }

    /**
     * Plays one exhibition and returns the persisted match id.
     *
     * <p>Fatigue is charged exactly as in a competitive match, because the players did play. Injury risk
     * is lower. The table, the ratings, every player's career goals, assists, morale and form are left
     * alone, and the match is recorded so it can be read back and filtered by its type.
     *
     * @return the match id, or a failure the caller can explain
     */
    @Transactional
    public Long playExhibition(Long homeTeamId, Long awayTeamId, Integer seasonYear, Integer week) {
        Team home = requireTeam(homeTeamId, "home");
        Team away = requireTeam(awayTeamId, "away");
        if (home.getId().equals(away.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_TEAM",
                    "A club cannot play an exhibition against itself.");
        }

        var fixture = new org.example.footballmanager.newLogic.model.MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setMatchType(MatchType.EXHIBITION);
        fixture.setSeasonYear(seasonYear);
        fixture.setWeekNumber(week);
        fixture.setMatchDate(LocalDateTime.now());
        fixture.setPlayed(false);

        SimMatchOutcome outcome = simMatchService.simulate(fixture, false);
        ProposalMatchOutcome result = outcome == null ? null : outcome.outcome();
        if (result == null) {
            throw new ApiException(HttpStatus.CONFLICT, "EXHIBITION_NOT_PLAYED",
                    "The exhibition could not be simulated.");
        }
        Long matchId = simMatchService.persist(fixture, result, -1L,
                outcome.snapshots() == null ? null : outcome.snapshots());
        if (matchId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "EXHIBITION_NOT_SAVED",
                    "The exhibition was played but could not be recorded.");
        }
        return matchId;
    }

    private Team requireTeam(Long teamId, String role) {
        if (teamId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEAM_REQUIRED",
                    "Which club is the " + role + " side?");
        }
        Team team = teams.findById(teamId).orElse(null);
        if (team == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND",
                    "The " + role + " club does not exist.");
        }
        return team;
    }
}