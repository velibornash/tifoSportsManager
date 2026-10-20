package org.example.footballmanager.newLogic.dto;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The result the manager has not asked to see (owner, 2026-09-29).
 *
 * <p>The score is masked in the DTO rather than in each renderer, because three screens read this DTO
 * and a fourth was written while the feature was being built. These tests exist to pin the contract
 * those screens depend on, so the next screen gets it right by reading the DTO instead of by
 * remembering.
 */
class MatchDTORevealTest {

    private static Match playedMatch() {
        Team home = new Team();
        home.setId(1L);
        home.setName("Omladinac");

        Team away = new Team();
        away.setId(2L);
        away.setName("Partizan");

        Competition competition = new Competition();
        competition.setName("Prva Liga");
        competition.setType(CompetitionType.LEAGUE);

        Match match = new Match();
        match.setId(50L);
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setCompetition(competition);
        match.setPlayed(true);
        match.setHomeGoals(2);
        match.setAwayGoals(1);
        // Day 3 of season 1, played in the 18:00 slot.
        match.setSeasonYear(1);
        match.setDayNumber(3);
        match.setWeekNumber(1);
        match.setMatchDate(LocalDateTime.of(2026, 2, 20, 18, 0));
        return match;
    }

    @Test
    @DisplayName("an unrevealed result reports no score at all, not a zero")
    void hiddenResultPublishesNoScore() {
        Match match = playedMatch();
        match.setHomeResultRevealed(false);
        match.setAwayResultRevealed(false);

        MatchDTO dto = MatchDTO.from(match, 1L);

        assertTrue(dto.isResultHidden(), "the home manager has not seen this result");
        // Zero is a real result. A 0-0 that was never played is indistinguishable from a goalless
        // draw, which is why the mask is null and not 0.
        assertNull(dto.getHomeGoals(), "a hidden result must not publish a score");
        assertNull(dto.getAwayGoals(), "a hidden result must not publish a score");
        // The fixture facts are not the secret and must survive the mask.
        assertEquals("Omladinac", dto.getHomeTeam());
        assertEquals("Partizan", dto.getAwayTeam());
    }

    @Test
    @DisplayName("the away manager is masked independently of the home manager")
    void eachSideIsMaskedOnItsOwnFlag() {
        Match match = playedMatch();
        match.setHomeResultRevealed(true);
        match.setAwayResultRevealed(false);

        assertFalse(MatchDTO.from(match, 1L).isResultHidden(), "the home manager has seen it");
        assertTrue(MatchDTO.from(match, 2L).isResultHidden(), "the away manager has not");
    }

    @Test
    @DisplayName("a revealed result shows the real score")
    void revealedResultShowsTheScore() {
        MatchDTO dto = MatchDTO.from(playedMatch(), 1L);

        assertFalse(dto.isResultHidden());
        assertEquals(2, dto.getHomeGoals());
        assertEquals(1, dto.getAwayGoals());
    }

    @Test
    @DisplayName("someone else's match is not masked")
    void aNeutralViewerSeesTheResult() {
        // A league table, a news item, the cup draw. A manager looking at a match he is not in is not
        // the case the reveal flags exist for.
        MatchDTO dto = MatchDTO.from(playedMatch(), 999L);

        assertFalse(dto.isResultHidden());
        assertEquals(2, dto.getHomeGoals());
    }

    @Test
    @DisplayName("the season calendar reads as Season 1, Day 3, 18:00")
    void seasonCalendarLabel() {
        MatchDTO dto = MatchDTO.from(playedMatch(), 1L);

        assertEquals("Season 1 · Day 3 · 18:00", dto.getSeasonDayLabel());
        assertEquals(1, dto.getSeasonNumber());
        assertEquals(3, dto.getDayNumber());
    }

    @Test
    @DisplayName("the calendar label leaves out what it does not know instead of printing zero")
    void seasonCalendarLabelOmitsUnknowns() {
        // A match from before this feature existed has no day number in the database. "Day 0" would
        // be a lie about a matchday, and "Season 1" on its own is still useful.
        Match match = playedMatch();
        match.setDayNumber(null);
        match.setMatchDate(null);

        assertEquals("Season 1", MatchDTO.from(match, 1L).getSeasonDayLabel());
    }

    @Test
    @DisplayName("a match with no season at all has no calendar label")
    void noSeasonMeansNoLabel() {
        Match match = playedMatch();
        match.setSeasonYear(null);

        assertNull(MatchDTO.from(match, 1L).getSeasonDayLabel());
    }

    @Test
    @DisplayName("the match header says what kind of match it was")
    void competitionTypeReachesTheHeader() {
        MatchDTO dto = MatchDTO.from(playedMatch(), 1L);

        assertEquals("LEAGUE", dto.getCompetitionType());
        assertEquals("Prva Liga", dto.getCompetitionName());
    }

    @Test
    @DisplayName("a match with no competition does not invent one")
    void missingCompetitionStaysNull() {
        Match match = playedMatch();
        match.setCompetition(null);

        MatchDTO dto = MatchDTO.from(match, 1L);
        assertNull(dto.getCompetitionType());
        assertNull(dto.getCompetitionName());
    }
}
