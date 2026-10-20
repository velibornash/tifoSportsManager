package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.ClubHonourRepository;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Club page's milestones payload carries the medals (P2-TROPHY-1).
 *
 * <p>The medals were derived and stored for two commits before anything sent them to a screen, and the
 * page looked complete throughout. This is the seam in between: {@code GET /teams/{id}/milestones} is
 * what the Club page reads, so if the medals are not on that payload there is nowhere else for them to
 * appear from.
 *
 * <p>Asserted through the service rather than through MockMvc, because the same builder serves both the
 * Club page and {@code StatsController} — one assertion covers both callers instead of one route.
 */
class ClubMilestonesCarryHonoursTest extends BaseTest {

    @Autowired LeagueMilestoneService milestones;
    @Autowired HonourService honours;
    @Autowired ClubHonourRepository honourRows;
    @Autowired CompetitionRepository competitions;
    @Autowired SeasonCompetitionRepository seasons;
    @Autowired CompetitionEntryRepository entries;
    @Autowired TeamRepository teams;

    @Test
    @Transactional
    @DisplayName("a club's medals reach the milestones payload, newest first")
    void medalsReachThePayload() {
        Competition league = aLeague();
        Team champion = aTeam(), runnerUp = aTeam();
        // One SeasonCompetition per (competition, season): creating it inside each join call wrote the
        // same row twice and tripped the unique index.
        SeasonCompetition first = aSeason(league, 1);
        SeasonCompetition second = aSeason(league, 2);
        join(first, champion, 1);
        join(first, runnerUp, 2);
        join(second, champion, 1);

        honours.derive(1);
        honours.derive(2);

        var payload = milestones.buildTeamMilestones(champion, 2);

        assertNotNull(payload.getTrophies(), "the payload has no trophies field at all");
        assertEquals(2, payload.getTrophies().size(),
                "both of the champion's medals, not just this season's: " + payload.getTrophies());
        assertEquals("GOLD", payload.getTrophies().get(0).getMedal());
        assertEquals(2, payload.getTrophies().get(0).getSeasonYear(),
                "newest season first, so the row reads top-down");
        assertEquals(league.getName(), payload.getTrophies().get(0).getCompetitionName());
    }

    @Test
    @Transactional
    @DisplayName("a club that has won nothing gets an empty list, not a null the page has to guess at")
    void noMedalsIsAnEmptyList() {
        var payload = milestones.buildTeamMilestones(aTeam(), 1);

        assertNotNull(payload.getTrophies(), "null would leave the page deciding what empty means");
        assertTrue(payload.getTrophies().isEmpty(), "and it is empty: " + payload.getTrophies());
    }

    @Test
    @Transactional
    @DisplayName("the league-wide read has no trophies, because it is about a competition and not a club")
    void aLeagueReadHasNoTrophies() {
        Competition league = aLeague();
        Team first = aTeam(), second = aTeam(), third = aTeam();
        SeasonCompetition sc = aSeason(league, 1);
        join(sc, first, 1);
        join(sc, second, 2);
        join(sc, third, 3);
        honours.derive(1);

        assertTrue(milestones.buildLeagueMilestones(league, 1).getTrophies() == null
                        || milestones.buildLeagueMilestones(league, 1).getTrophies().isEmpty(),
                "a competition's milestone board is not a club's trophy cabinet");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private Competition aLeague() {
        Competition c = new Competition();
        c.setName("Honours " + UUID.randomUUID().toString().substring(0, 6));
        c.setType(CompetitionType.LEAGUE);
        c.setScope(CompetitionScope.NATIONAL);
        c.setTeamType(CompetitionTeamType.CLUB);
        c.setTier(1);
        return competitions.save(c);
    }

    private SeasonCompetition aSeason(Competition c, int year) {
        SeasonCompetition sc = new SeasonCompetition();
        sc.setCompetition(c);
        sc.setSeasonYear(year);
        sc.setFinished(true);
        return seasons.save(sc);
    }

    private Team aTeam() {
        Team t = new Team();
        t.setName("H " + UUID.randomUUID().toString().substring(0, 6));
        t.setFormation("4-3-3");
        return teams.save(t);
    }

    private void join(SeasonCompetition sc, Team team, int position) {
        CompetitionEntry entry = new CompetitionEntry();
        entry.setSeasonCompetition(sc);
        entry.setTeam(team);
        entry.setPosition(position);
        entry.setPoints(40 - position);
        entries.save(entry);
    }
}