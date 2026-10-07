package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.ClubSeasonRankingPoints;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.ClubSeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.RankingPointsEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The country's clubs, ranked by ranking points ({@code P1-CTRY-1}).
 *
 * <p>The owner's words: <i>"nedostaje mi na stranici Country novi tab gde je ranking lista klubova iz te
 * zemlje"</i>.
 *
 * <p><b>Points, not the old Elo.</b> The ranking that used to sit on the country page was
 * {@code Team.eloRating}, which was head-to-head and is no longer a rating at all
 * ({@code RatingEngine.clubK} does not read either rating). A country page showing its national side by
 * achievement points and its clubs by a different number would be two systems on one screen.
 */
class CountryClubRankingTest extends BaseTest {

    @Autowired
    CountryController controller;

    @Autowired
    CountryRepository countries;

    @Autowired
    TeamRepository teams;

    @Autowired
    CompetitionRepository competitions;

    @Autowired
    ClubSeasonRankingPointsRepository ledger;

    @Autowired
    org.example.footballmanager.newLogic.service.SeasonService seasons;

    @Test
    @Transactional
    @DisplayName("the country's clubs come back ranked by points, not by the old Elo")
    void clubsAreRankedByPoints() {
        Country country = aCountry("ClubRank");
        Competition topFlight = aDivision(country, 1);
        Competition third = aDivision(country, 3);

        Team ordinary = aClub(country, topFlight, "Ordinary");
        Team stronger = aClub(country, topFlight, "Stronger");
        Team lowerTier = aClub(country, third, "Lower tier");

        // Team has no settable Elo - it is a derived column, written by the replay - so this cannot set
        // up a divergence between the old rating and the points. What it CAN do is prove the list is
        // ordered by points at all, by giving three clubs three different totals and requiring that order
        // back. The old rating is not written here because it no longer decides anything.
        int season = seasons.getActiveSeasonYear();
        ledger.save(new ClubSeasonRankingPoints(ordinary, season, 10.0));
        ledger.save(new ClubSeasonRankingPoints(stronger, season, 200.0));
        ledger.save(new ClubSeasonRankingPoints(lowerTier, season, 100.0));

        Map<String, Object> body = controller.clubRanking(country.getIsoCode(), 100);
        List<Map<String, Object>> clubs = cast(body.get("clubs"));

        assertEquals(3, clubs.size(), "every club of the country is listed");
        assertEquals("Stronger", clubs.get(0).get("name"),
                "the club with the most points leads");
        assertEquals("Lower tier", clubs.get(1).get("name"));
        assertEquals("Ordinary", clubs.get(2).get("name"));

        assertEquals(1, clubs.get(0).get("position"));
        assertEquals(1700.0, (Double) clubs.get(0).get("points"), 0.01, "1500 + 200");
        assertEquals(Boolean.TRUE, clubs.get(0).get("rated"));
    }

    @Test
    @Transactional
    @DisplayName("the division is on every row, because the points are scaled by tier")
    void everyRowCarriesItsDivision() {
        Country country = aCountry("DivRank");
        Competition fifth = aDivision(country, 5);
        Team club = aClub(country, fifth, "Bottom division club");

        Map<String, Object> body = controller.clubRanking(country.getIsoCode(), 100);
        Map<String, Object> row = cast(body.get("clubs")).get(0);

        assertEquals(5, row.get("tier"),
                "two clubs on the same points in different divisions are not equal, and a table that "
                        + "does not say which division a row is in cannot be read");
        assertEquals(fifth.getName(), row.get("division"));
    }

    @Test
    @Transactional
    @DisplayName("a club that has played nothing is listed but not marked as rated")
    void anUnratedClubSaysSo() {
        Country country = aCountry("Unrated");
        Competition flight = aDivision(country, 1);
        aClub(country, flight, "Never played");

        Map<String, Object> row = cast(controller.clubRanking(country.getIsoCode(), 100)
                .get("clubs")).get(0);

        assertEquals(Boolean.FALSE, row.get("rated"),
                "a seeded 1500 must not be presented as an earned result");
        assertEquals(RankingPointsEngine.START_POINTS, (Double) row.get("points"), 0.01);
    }

    @Test
    @Transactional
    @DisplayName("a country with no clubs says so rather than showing an empty table")
    void aClublessCountrySaysSo() {
        Country country = aCountry("NoClubs");

        Map<String, Object> body = controller.clubRanking(country.getIsoCode(), 100);

        assertTrue(cast(body.get("clubs")).isEmpty());
        assertEquals(0, body.get("totalClubs"));
        assertFalse(((String) body.get("country")).isBlank(), "and it still names the country it asked about");
    }

    @Test
    @Transactional
    @DisplayName("another country's clubs never appear")
    void anotherCountrysClubsDoNotLeak() {
        Country mine = aCountry("Mine");
        Country theirs = aCountry("Theirs");
        Competition myFlight = aDivision(mine, 1);
        Competition theirFlight = aDivision(theirs, 1);
        aClub(mine, myFlight, "My club");
        aClub(theirs, theirFlight, "Their club");

        List<Map<String, Object>> clubs = cast(
                controller.clubRanking(mine.getIsoCode(), 100).get("clubs"));

        assertEquals(1, clubs.size(), "one country must not see another's clubs");
        assertEquals("My club", clubs.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private Country aCountry(String prefix) {
        Country country = new Country();
        country.setName(prefix + " " + java.util.UUID.randomUUID().toString().substring(0, 4));
        country.setIsoCode(("C" + java.util.UUID.randomUUID().toString().substring(0, 2)).toUpperCase());
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }

    private Competition aDivision(Country country, int tier) {
        Competition division = new Competition();
        division.setName("Division " + tier + " " + country.getIsoCode());
        division.setType(CompetitionType.LEAGUE);
        division.setScope(CompetitionScope.NATIONAL);
        division.setTeamType(CompetitionTeamType.CLUB);
        division.setTier(tier);
        // A division belongs to its country. 32 of the owner's 51 competitions have one and the other 19
        // are the international club cups, which correctly have no single country - so a club's division
        // carrying one is the normal case, not a detail. Without it the ranking query's join matched no
        // rows, every club showed the same starting 1500, and the "wrong club leads" was the tie-break
        // sorting alphabetically. Fifth fixture gap in a row, and again it read like a product defect.
        division.setCountry(country);
        return competitions.save(division);
    }

    private Team aClub(Country country, Competition division, String name) {
        Team club = new Team();
        club.setName(name);
        club.setFormation("4-4-2");
        club = teams.save(club);
        club.setCompetition(division);
        // The club's own country reference, which every real club carries (310 of 310 in the owner's
        // database) and `findClubTeamsForCountry` reads. Without it the endpoint correctly returns
        // nothing, which the first version of this test reported as "every club of the country is
        // listed" failing. Fourth fixture gap in a row, and each one reads like a product defect.
        club.setCountry(country);
        return teams.save(club);
    }
}