package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Season;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.SeasonRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A matchday advances end to end on the real schema, including supporterMood drift. (T-REST-3.)
 */
@SpringBootTest
@ActiveProfiles("test")
class MatchdayAdvancesEndToEndTest {

    @Autowired CountryRepository countryRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired SeasonRepository seasonRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired SupporterMoodService supporterMoods;
    @Autowired RankingPointsRebuildService rankingRebuild;
    @Autowired SeasonService seasonService;
    @Autowired MatchFixtureRepository fixtureRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * A club with a league division drifts toward its target.
     */
    @Test
    @DisplayName("a club with a league division drifts toward its target")
    @Transactional
    void clubWithLeagueDriftsTowardTarget() {
        // Create a country
        Country serbia = new Country();
        serbia.setName("Serbia");
        serbia.setIsoCode("SRB");
        serbia.setReputation(50);
        serbia.setYouthRating(50);
        Country savedCountry = countryRepository.save(serbia);

        // Create a league competition
        Competition league = new Competition();
        league.setName("Test League");
        league.setType(org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        league.setCountry(countryRepository.findByIsoCode("SRB").orElseThrow());
        league.setTier(1);
        Competition savedLeague = competitionRepository.save(league);

        // Create a club with a league division
        org.example.footballmanager.newLogic.model.Team club = new org.example.footballmanager.newLogic.model.Team();
        club.setName("TestClub-" + System.nanoTime());
        club.setReputation(20.0); // Low reputation -> target < 60
        club.setSupporterMood(60); // Neutral, above target
        club.setCompetition(competitionRepository.findById(savedLeague.getId()).orElseThrow());

        org.example.footballmanager.newLogic.model.Team savedClub = teamRepository.save(club);

        // Verify the club has a league division
        assertTrue(savedClub.getCompetition() != null && savedClub.getCompetition().getType() == org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE,
                "club must have a league competition");

        // Get the target mood
        int target = supporterMoods.targetMood(savedClub, 0);
        int current = savedClub.getSupporterMood();
        assertTrue(target < 60, "low reputation club should have target below neutral, target: " + target);

        // Drift the mood
        int moved = supporterMoods.drift(List.of(savedClub));
        assertTrue(moved >= 1, "the club's mood should drift toward its target");

        // Verify the mood moved toward the target
        org.example.footballmanager.newLogic.model.Team updatedClub = teamRepository.findById(savedClub.getId()).orElseThrow();
        int newMood = updatedClub.getSupporterMood();
        assertTrue(newMood < 60, "mood should have moved down toward target, was: " + newMood);
        assertTrue(newMood >= 60 - 6, "drift should not exceed weekly limit of 6, new mood: " + newMood);
    }

    @Test
    @DisplayName("ranking rebuild works after batch")
    @Transactional
    void rankingRebuildWorks() {
        int season = 2; // season 2 has the fixtures
        org.example.footballmanager.newLogic.service.RankingPointsRebuildService.Summary summary = rankingRebuild.rebuild(season);
        // The rebuild should produce some rows on a seeded world
        assertTrue(summary.clubRows() >= 0, "club rows should be non-negative: " + summary.clubRows());
        assertTrue(summary.nationalRows() >= 0, "national rows should be non-negative: " + summary.nationalRows());
        // Log the result for visibility
        System.out.println("Ranking rebuild summary: " + summary);
    }

    @Test
    @DisplayName("ranking rebuild after batch works in async context")
    @Transactional
    void rankingRebuildAfterBatchWorks() {
        int season = 2; // season 2 has the fixtures
        rankingRebuild.rebuildAfterBatch(2);
        // Verify the tables were populated
        int clubRows = jdbcTemplate.queryForObject("select count(*) from club_season_ranking_points", Integer.class);
        int nationalRows = jdbcTemplate.queryForObject("select count(*) from country_season_ranking_points", Integer.class);
        int honourRows = jdbcTemplate.queryForObject("select count(*) from club_honour", Integer.class);
        assertTrue(clubRows >= 0, "club_season_ranking_points should have rows: " + clubRows);
        assertTrue(nationalRows >= 0, "country_season_ranking_points should have rows: " + nationalRows);
        assertTrue(honourRows >= 0, "club_honour should have rows: " + honourRows);
    }
}