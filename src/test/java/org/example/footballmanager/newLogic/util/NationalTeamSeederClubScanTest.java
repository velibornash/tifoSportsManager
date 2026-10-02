package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1: drawing a national squad must ask for one country's clubs, not every club in the world.
 *
 * <p>{@code clubsIn} used to take {@code findClubTeamsForOperations()} — the whole club table — and
 * keep the rows whose country id matched. At the scale this project targets that is 14,880 clubs read
 * to select the 310 of one country, and the world's squads are drawn country by country, so the same
 * full scan is repeated for each of the forty-eight.
 *
 * <h2>Why a mock, and why not a statement count</h2>
 *
 * <p><b>A mock, and the reasoning is the board's own from C1.</b> SQL statement counts were tried for
 * exactly this class of defect and failed: a whole-table load and a narrow lookup each issue one query,
 * so the number is identical and the test is green against the code it was written to catch. Hibernate's
 * entity counter is worse — it reads zero inside a transaction. A mock asks the question directly and
 * cannot be disarmed by a statistics switch being off.
 *
 * <p><b>Mock rather than spy</b> because the injected repository is already a JDK proxy and Mockito
 * cannot wrap it. The repository is passed to the seeder's constructor, so the real interface is mocked
 * and handed over rather than the bean being wrapped.
 *
 * <p>The second test is the counterweight: a cheap query that returns nothing satisfies the first test
 * perfectly. So there is also a test that a country's squad is still drawn from that country's clubs,
 * and that another country's clubs are not.
 */
class NationalTeamSeederClubScanTest extends BaseTest {

    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;

    @Test
    @Transactional
    @DisplayName("the world's clubs are never scanned to draw one country's squad")
    void theWorldIsNotScannedForOneCountry() {
        Country country = aCountry();
        long countryId = country.getId();

        TeamRepository mockedTeams = Mockito.mock(TeamRepository.class);
        when(mockedTeams.findClubTeamsForCountry(countryId)).thenReturn(List.of());

        NationalTeamSeeder seeder = new NationalTeamSeeder(mockedTeams, players, new BotSquadGenerator(players));
        seeder.squadsFor(side(country), country, false);

        verify(mockedTeams, never()).findClubTeamsForOperations();
        verify(mockedTeams, atLeastOnce()).findClubTeamsForCountry(countryId);
    }

    @Test
    @Transactional
    @DisplayName("a country's squad is drawn from its own clubs and no other country's")
    void theSquadComesFromThisCountryOnly() {
        // The counterweight to the test above. A repository method that returned an empty list, or the
        // wrong country's clubs, would satisfy "never scans the world" perfectly while silently emptying
        // every national side in the game — which is exactly what happened when these clubs had no
        // players and the internationals drew nothing.
        Country home = aCountry();
        Country rival = aCountry();

        Team homeClub = aClub(home, "Home");
        Team rivalClub = aClub(rival, "Rival");
        for (int i = 0; i < 30; i++) {
            aClubPlayer(homeClub, "Home", 40 + i);
            aClubPlayer(rivalClub, "Rival", 40 + i);
        }
        Team nationalSide = side(home);

        NationalTeamSeeder seeder = new NationalTeamSeeder(teams, players, new BotSquadGenerator(players));
        seeder.squadsFor(nationalSide, home, false);

        List<Player> drawn = players.findByTeamId(nationalSide.getId());
        assertEquals(25, drawn.size(), "the side should have been filled from its own country's clubs");
        assertEquals(0, drawn.stream().filter(p -> p.getName().contains("Rival")).count(),
                "a player from another country was drafted into this side. The old code filtered the whole "
                        + "club table by country id in Java, and a predicate that has been narrowed into a "
                        + "query is exactly where that filter goes missing.");
        assertEquals(25, drawn.stream().filter(p -> p.getName().contains("Home")).count(),
                "the squad was not drawn from this country's clubs");
    }

    @Test
    @Transactional
    @DisplayName("a country's clubs are read once for both of its sides")
    void theClubsAreReadOncePerCountryNotOncePerSquad() {
        Country country = aCountry();
        Team club = aClub(country, "Once");
        for (int i = 0; i < 30; i++) {
            aClubPlayer(club, "Once", 40 + i);
        }

        TeamRepository countingTeams = Mockito.mock(TeamRepository.class);
        when(countingTeams.findClubTeamsForCountry(anyLong())).thenReturn(List.of(club));

        NationalTeamSeeder seeder = new NationalTeamSeeder(countingTeams, players, new BotSquadGenerator(players));
        seeder.squadsFor(side(country), country, false);
        seeder.squadsFor(side(country), country, true);

        verify(countingTeams, Mockito.times(1)).findClubTeamsForCountry(country.getId());
    }

    // --- fixture ---

    private Team side(Country country) {
        Team team = new Team();
        team.setName("Query budget side " + UUID.randomUUID());
        team.setCountry(country);
        return teams.save(team);
    }

    private Country aCountry() {
        Country country = new Country();
        country.setName("Query budget " + UUID.randomUUID());
        country.setIsoCode("I" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }

    private Team aClub(Country country, String label) {
        Team club = new Team();
        club.setName(label + " club " + UUID.randomUUID());
        club.setCountry(country);
        return teams.save(club);
    }

    private void aClubPlayer(Team club, String label, int rating) {
        Player player = new Player();
        player.setName(label + " player " + rating + " " + UUID.randomUUID());
        player.setTeam(club);
        player.setRating(rating);
        player.setAge(20 + (rating % 10));
        players.save(player);
    }
}
