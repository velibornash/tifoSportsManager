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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A national side is drawn once.
 *
 * <p>{@code squadsFor} had no idempotence guard and runs on both seeding branches and from five call sites, so
 * every pass added another squad of up to 25 players to a side that already had one — up to **2,400 duplicate
 * player rows per pass**, and each call cost a full scan of the club table.
 *
 * <p>{@code BotSquadGenerator.ensureSquad} has had exactly this check the whole time, with the reasoning
 * written down: *"Idempotent by squad size, not by a flag: the players are the record."*
 *
 * <p>The test asserts the count, because that is what the bug was: a second pass over the same side must leave
 * the squad the same size rather than double it.
 */
class NationalTeamSquadIdempotenceTest extends BaseTest {

    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;

    @Test
    @Transactional
    @DisplayName("drawing a national side twice leaves one squad, not two")
    void aNationalSideIsDrawnOnce() {
        Country country = aCountry();
        Team club = aClub(country);
        // A handful of eligible club players, enough that the squad is a real selection.
        for (int i = 0; i < 30; i++) {
            aClubPlayer(club, i);
        }

        Team nationalSide = new Team();
        nationalSide.setName("Idempotence side " + UUID.randomUUID());
        nationalSide.setCountry(country);
        nationalSide = teams.save(nationalSide);

        NationalTeamSeeder seeder = new NationalTeamSeeder(teams, players, squadBot());

        seeder.squadsFor(nationalSide, country, false);
        int afterFirst = players.findByTeamId(nationalSide.getId()).size();
        assertEquals(25, afterFirst, "the first draw should fill a squad of 25");

        seeder.squadsFor(nationalSide, country, false);
        int afterSecond = players.findByTeamId(nationalSide.getId()).size();

        assertEquals(afterFirst, afterSecond,
                "a second draw added " + (afterSecond - afterFirst) + " players to a side that already had "
                        + afterFirst + ". Without the guard every pass added another squad, and this method "
                        + "runs on both seeding branches and from five call sites.");
    }

    /** A second side of the same country is a different squad, and must still be drawn. */
    @Test
    @Transactional
    @DisplayName("the guard does not stop the other side being drawn")
    void theGuardDoesNotBlockASecondSide() {
        Country country = aCountry();
        Team club = aClub(country);
        for (int i = 0; i < 30; i++) {
            aClubPlayer(club, i);
        }

        Team senior = teams.save(side("Senior", country));
        Team youth = teams.save(side("Youth", country));

        NationalTeamSeeder seeder = new NationalTeamSeeder(teams, players, squadBot());
        seeder.squadsFor(senior, country, false);
        seeder.squadsFor(youth, country, true);

        assertEquals(25, players.findByTeamId(senior.getId()).size());
        assertEquals(25, players.findByTeamId(youth.getId()).size(),
                "the guard is per side, not per country: the U21 side must still be drawn");
    }

    private BotSquadGenerator squadBot() {
        return new BotSquadGenerator(players);
    }

    private Team side(String label, Country country) {
        Team team = new Team();
        team.setName(label + " side " + UUID.randomUUID());
        team.setCountry(country);
        return team;
    }

    private Country aCountry() {
        Country country = new Country();
        country.setName("Idempotence " + UUID.randomUUID());
        country.setIsoCode("I" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }

    private Team aClub(Country country) {
        Team club = new Team();
        club.setName("Idempotence club " + UUID.randomUUID());
        club.setCountry(country);
        return teams.save(club);
    }

    private void aClubPlayer(Team club, int i) {
        Player player = new Player();
        player.setName("Idempotence player " + i + " " + UUID.randomUUID());
        player.setTeam(club);
        player.setRating(40 + i);
        player.setAge(20 + (i % 10));
        players.save(player);
    }
}