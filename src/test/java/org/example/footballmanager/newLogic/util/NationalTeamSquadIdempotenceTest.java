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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ── A generated squad is not a squad ───────────────────────────────────────────────────────────

    /**
     * The owner's report, verbatim: *"zasto su u u-21 i prvom timu u 25 lazni igraci (verovatno nastali
     * tokom init db) umesto stvarnih (koji se nalaze u poolu ispod)? AKTIVNA liga MORA imati STVARNE
     * igrace a ne simulirane!!!"*
     *
     * <p>Reproduced exactly: the side is filled while the country has <b>no clubs</b>, which is what
     * happens on every install because the national sides are seeded before the pyramid exists. Then
     * the clubs arrive with real players. The old guard saw 25 players and stopped, so the generated
     * ones were permanent - Serbia fielded {@code N. SRB-GK01} at 82 while Zoran Zivadinovic sat in
     * the pool at 94.
     */
    @Test
    @Transactional
    @DisplayName("a side filled before its clubs existed is replaced once real players arrive")
    void aGeneratedSquadIsReplacedByRealPlayers() {
        Country country = aCountry();
        NationalTeamSeeder seeder = new NationalTeamSeeder(teams, players, squadBot());
        Team senior = teams.save(side("Senior", country));

        // The country has no clubs yet, so the side can only be filled with generated players.
        seeder.squadsFor(senior, country, false);
        List<Player> generated = players.findByTeamId(senior.getId());
        assertEquals(25, generated.size());
        assertTrue(generated.stream().allMatch(squadBot()::isGenerated),
                "precondition: the country had no clubs, so this squad had to be generated");

        // The pyramid builds now. These are the players the owner can see in the pool.
        Team club = aClub(country);
        List<Player> real = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            real.add(aRatedClubPlayer(club, i, 90 - i));
        }

        seeder.squadsFor(senior, country, false);

        List<Player> after = players.findByTeamId(senior.getId());
        assertEquals(25, after.size(), "still a squad of 25, not 25 plus 25");
        assertTrue(after.stream().noneMatch(squadBot()::isGenerated),
                "the generated players must be gone: " + after.stream().map(Player::getName).toList());
        assertEquals(25, after.stream().filter(p -> p.getSourcePlayerId() != null).count(),
                "every squad member must now be a real club player, tracked by source id");
        assertTrue(after.stream().anyMatch(p -> p.getName().equals(real.get(0).getName())),
                "the best available player must be in the squad: " + after.stream().map(Player::getName).toList());
    }

    /**
     * The pool excludes called-up players by {@code sourcePlayerId}, and {@code squadsFor} never set it -
     * only {@code addToSquad} did. So a seeded squad member had a null and appeared in the squad *and* in
     * the pool it was drawn from, which is exactly what the comment on that column says it prevents.
     *
     * <p>Without this the previous fix would have replaced 25 bots with 25 real players and then shown
     * all 25 of them twice on the same screen.
     */
    @Test
    @Transactional
    @DisplayName("a drawn player is excluded from the pool he was drawn from")
    void aDrawnPlayerRemembersWhereItCameFrom() {
        Country country = aCountry();
        Team club = aClub(country);
        for (int i = 0; i < 30; i++) {
            aRatedClubPlayer(club, i, 90 - i);
        }
        Team senior = teams.save(side("Senior", country));

        new NationalTeamSeeder(teams, players, squadBot()).squadsFor(senior, country, false);

        List<Player> squad = players.findByTeamId(senior.getId());
        for (Player member : squad) {
            assertNotNull(member.getSourcePlayerId(),
                    member.getName() + " has no source id, so the pool cannot exclude him and he appears "
                            + "in the squad and the pool at once");
            assertNotNull(players.findById(member.getSourcePlayerId()).orElse(null),
                    "the source id must point at a real club player that still exists");
        }
    }

    /** A side with no clubs anywhere keeps its generated players: it is the only XI it can field. */
    @Test
    @Transactional
    @DisplayName("a country with no clubs keeps its generated side rather than being left empty")
    void aClublessCountryKeepsItsGeneratedSquad() {
        Country country = aCountry();
        Team senior = teams.save(side("Senior", country));
        NationalTeamSeeder seeder = new NationalTeamSeeder(teams, players, squadBot());

        seeder.squadsFor(senior, country, false);
        seeder.squadsFor(senior, country, false);

        List<Player> after = players.findByTeamId(senior.getId());
        assertEquals(25, after.size(), "not deleted, not doubled");
        assertTrue(after.stream().allMatch(squadBot()::isGenerated),
                "still generated - there is nothing real to replace them with, and an empty national side "
                        + "cannot be drawn against, which is what stopped the internationals from being drawn");
    }

    private Player aRatedClubPlayer(Team club, int i, int rating) {
        Player player = new Player();
        player.setName("Real player " + i + " " + UUID.randomUUID());
        player.setTeam(club);
        player.setRating(rating);
        player.setAge(20 + (i % 10));
        return players.save(player);
    }

    private BotSquadGenerator squadBot() {
        return new BotSquadGenerator(players);
    }

    private Team side(String label, Country country) {
        Team team = new Team();
        team.setName(label + " side " + UUID.randomUUID());
        team.setCountry(country);
        // The type matters, and leaving it off made this test lie. `findClubTeamsForCountry` selects
        // `type is null or type = CLUB`, because `PyramidBuilder` never sets type on a club. A side with
        // no type therefore matches the club predicate, so the side became its own draw pool and fed its
        // own players back in - 25 generated players re-copied into 25 fresh rows that still read as
        // generated, which looked exactly like the bug this task is fixing. Production is not like this:
        // `NationalTeamSeeder` sets NATIONAL_TEAM on every side it creates, and that is what keeps a
        // national side out of the club pool.
        team.setType(org.example.footballmanager.newLogic.model.CompetitionTeamType.NATIONAL_TEAM);
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