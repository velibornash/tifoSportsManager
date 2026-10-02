package org.example.footballmanager.newLogic.util;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.example.footballmanager.TestCountryCatalogue;

/**
 * The Šid league and the second human manager, verified against a real seeded database.
 *
 * <p>The unit tests in {@code SidLeagueSeedingTest} check the data and the rules. This one boots the
 * actual context, lets {@code DatabaseInitializer} run for real, and checks the thing that only
 * breaks in a running application: that the account, the text-mode club and the football club are
 * wired to each other by <b>name</b>, because {@code User} has no foreign key between them. A typo
 * there produces an account that logs in successfully and manages nothing at all, and no unit test
 * on any single class would ever see it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestCountryCatalogue.class)
class SidLeagueSeedingIntegrationTest {

    @Autowired
    TestCountryCatalogue catalogue;

    private static final String SREMAC = "Sremac Berkasovo";
    private static final String LEAGUE = "Opštinska liga Šid";

    @Autowired
    private CompetitionRepository competitions;
    @Autowired
    private CompetitionEntryRepository entries;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private PlayerRepository players;
    @Autowired
    private UserRepository users;

    @Test
    @DisplayName("The league is seeded and named for the place")
    void theLeagueExists() {
        Competition league = competitions.findByNameAndCountryIsoCode(LEAGUE, "SRB").orElseThrow();
        assertNotNull(league, LEAGUE + " was not seeded");
        assertEquals(5, league.getTier());
        assertEquals(16, league.getDivisionLevel(), "it is the sixteenth municipal division, not a new tier");
        assertEquals(CompetitionTeamType.CLUB, league.getTeamType());
    }

    @Test
    @DisplayName("The league holds the nine real Šid clubs and one generated, making ten")
    void theLeagueIsFullOfTheRightClubs() {
        Competition league = competitions.findByNameAndCountryIsoCode(LEAGUE, "SRB").orElseThrow();
        List<String> names = clubsIn(league);

        assertEquals(10, names.size(), "a ten-team division, got " + names);
        for (String expected : List.of(SREMAC, "Sinđelić Gibarac", "Graničar Jamena",
                "Jednota Šid", "Omladinac Batrovci", "Borac Ilinci", "Jedinstvo Morović",
                "OFK Bačinci", "OFK Bingula")) {
            assertTrue(names.contains(expected), "missing " + expected + " — got " + names);
        }
    }

    @Test
    @DisplayName("The other fifteen municipal leagues are untouched and still generic")
    void theOtherMunicipalLeaguesAreUnchanged() {
        for (int i = 1; i <= 15; i++) {
            String name = "Opštinska liga Grupa " + i;
            assertTrue(competitions.findByNameAndCountryIsoCode(name, "SRB").isPresent(),
                    name + " disappeared when Šid was renamed");
        }
    }

    @Test
    @DisplayName("Sremac is in the league, is human-run, and has his badge and his ground")
    void sremacIsTheHumanClub() {
        Team sremac = teams.findAllByNameIgnoreCase(SREMAC).stream().findFirst().orElseThrow();
        assertEquals(LEAGUE, sremac.getCompetition().getName());
        assertTrue(sremac.isHumanControlled(), "nobody can manage the club");
        assertEquals("/images/sremac_logo.jpg", sremac.getLogoUrl());
        assertNotNull(sremac.getStadium());
        assertEquals("Stadion Livadice", sremac.getStadium().getName());
    }

    @Test
    @DisplayName("Sremac has the seventeen players from the match sheet, in the right shirts")
    void sremacHasHisSquad() {
        Team sremac = teams.findAllByNameIgnoreCase(SREMAC).stream().findFirst().orElseThrow();
        List<Player> squad = players.findByTeam(sremac);

        assertEquals(17, squad.size(), "expected the 17 on the match sheet");
        for (int n = 1; n <= 17; n++) {
            final int number = n;
            assertTrue(squad.stream().anyMatch(p -> Integer.valueOf(number).equals(p.getSquadNumber())),
                    "no player wears " + n);
        }
        assertTrue(squad.stream().anyMatch(p -> "Nenad Ugrenović".equals(p.getName())));
        assertTrue(squad.stream().anyMatch(p -> "Nenad Petrović".equals(p.getName())));
        assertFalse(squad.stream().anyMatch(p -> p.getName().startsWith("Nena ")),
                "the graphic's typo was seeded");
    }

    @Test
    @DisplayName("The starting eleven is a real shape: one keeper, four at the back, four in front of him")
    void sremacHasAPlausibleStartingEleven() {
        Team sremac = teams.findAllByNameIgnoreCase(SREMAC).stream().findFirst().orElseThrow();
        List<Player> xi = players.findByTeam(sremac).stream()
                .filter(p -> p.getSquadNumber() != null && p.getSquadNumber() <= 11)
                .toList();

        assertEquals(11, xi.size());
        assertEquals(1, xi.stream().filter(p -> p.getPosition() == Position.GK).count(),
                "the eleven has the wrong number of goalkeepers");
        assertEquals(4, xi.stream().filter(p -> p.getPosition() == Position.DEF).count());
        assertEquals(4, xi.stream().filter(p -> p.getPosition() == Position.MID).count());
        assertEquals(2, xi.stream().filter(p -> p.getPosition() == Position.ATT).count());
    }

    @Test
    @DisplayName("The account exists, is not an administrator, and owns Sremac")
    void theAccountIsARegularUserWhoOwnsSremac() {
        User kecko = users.findByUsernameOrEmail("kecko@example.com").orElseThrow();
        assertEquals(UserRole.REGULAR, kecko.getRole(),
                "he must not reach anything under /admin/**");
        assertNotNull(kecko.getCTeam());
        assertEquals(SREMAC, kecko.getCTeam().getName());
    }

    @Test
    @DisplayName("The account's club resolves to a real football club — the link that makes login work")
    void theAccountResolvesToARealClub() {
        // This is the whole risk in the feature. User holds a CTeam; the football club is found by
        // matching that name. If the two names drift apart the account logs in and manages nothing,
        // which is exactly what a single-class unit test cannot see.
        User kecko = users.findByUsernameOrEmail("kecko@example.com").orElseThrow();
        String name = kecko.getCTeam().getName();

        List<Team> matches = teams.findAllByNameIgnoreCase(name);
        assertEquals(1, matches.size(),
                "'" + name + "' matched " + matches.size() + " football clubs, so the name link is ambiguous");
        assertTrue(matches.get(0).isHumanControlled());
    }

    @Test
    @DisplayName("Velibor's account and club are untouched by any of this")
    void theFirstManagerIsUntouched() {
        User velibor = users.findByUsernameOrEmail("velibor@example.com").orElseThrow();
        assertEquals(UserRole.OWNER, velibor.getRole());
        assertEquals("OFK Omladinac", velibor.getCTeam().getName());

        Team omladinac = teams.findAllByNameIgnoreCase("OFK Omladinac").stream().findFirst().orElseThrow();
        assertTrue(omladinac.isHumanControlled());
        assertEquals("Superliga Srbije", omladinac.getCompetition().getName(),
                "Omladinac must still be in the top flight, not pushed down a tier");
        assertEquals("/images/omladinac.png", omladinac.getLogoUrl(),
                "the badge moved to a column and the old club's badge was forgotten");
    }

    @Test
    @DisplayName("No club name in the whole pyramid is ambiguous")
    void noDuplicateClubNamesAnywhere() {
        // The Šid league introduces a club called plain "Omladinac" alongside "OFK Omladinac". They
        // are different strings so nothing collides today, but the name-based lookup throws on an
        // exact duplicate, and that is a login crash rather than a cosmetic problem.
        List<String> all = teams.findAll().stream().map(Team::getName).toList();
        List<String> duplicates = all.stream()
                .filter(n -> n != null)
                .collect(java.util.stream.Collectors.groupingBy(n -> n, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(java.util.Map.Entry::getKey)
                .toList();
        assertTrue(duplicates.isEmpty(), "duplicate club names break the name-based user lookup: " + duplicates);
    }

    @Test
    @DisplayName("The pyramid is still 31 leagues and every one of them has ten clubs")
    void theRestOfThePyramidIsIntact() {
        // Renaming one division must not have changed the shape of the pyramid, and the other 30
        // leagues must still be full - a rename that quietly emptied a league would be invisible
        // from the Šid side.
        List<Competition> srb = competitions
                .findByCountryIsoCodeAndTypeOrderByTierAscDivisionLevelAscIdAsc("SRB",
                        org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        assertEquals(31, srb.size(), "the league count changed");

        for (Competition league : srb) {
            assertEquals(10, teams.findByCompetitionId(league.getId()).size(),
                    league.getName() + " is not full");
        }
    }

    private List<String> clubsIn(Competition league) {
        return teams.findByCompetitionId(league.getId()).stream().map(Team::getName).toList();
    }

    @BeforeEach
    void seedTheCatalogue() {
        catalogue.seed();
    }

}
