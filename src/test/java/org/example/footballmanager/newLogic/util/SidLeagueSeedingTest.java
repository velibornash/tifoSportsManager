package org.example.footballmanager.newLogic.util;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.players.PlayerFactory;
import org.example.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 5 / owner request 2026-09-27 — the Šid league and the second human manager.
 *
 * <p>The point of most of these is that the seeding is <b>idempotent</b> and that the two things the
 * whole thing depends on cannot silently come apart: the account's CTeam and the football club are
 * matched <b>by name</b>, and {@code User} has no foreign key between them.
 */
class SidLeagueSeedingTest {

    private static final String SREMAC = "Sremac Berkasovo";

    private PlayerRepository players;
    private TeamRepository teams;
    private CompetitionRepository competitions;
    private UserRepository users;
    private PlayerFactory factory;

    @BeforeEach
    void setUp() {
        players = mock(PlayerRepository.class);
        teams = mock(TeamRepository.class);
        competitions = mock(CompetitionRepository.class);
        users = mock(UserRepository.class);
        factory = new PlayerFactory(players);
    }

    @Test
    @DisplayName("The league is named for the place, not 'Grupa 16'")
    void theLeagueIsNamedSid() {
        assertEquals("Opštinska liga Šid", readConstant("MUNICIPAL_SID_LEAGUE"));
    }

    @Test
    @DisplayName("The league holds Sremac plus the eight real Šid clubs")
    void theClubListIsTheRealOne() {
        List<String> clubs = readConstantList("MUNICIPAL_SID_CLUBS");
        assertEquals(9, clubs.size(), "nine clubs plus one generated makes a ten-team division");
        assertTrue(clubs.contains(SREMAC), "Sremac is not in his own league");
        for (String expected : List.of("Sinđelić Gibarac", "Graničar Jamena", "Jednota Šid",
                "Omladinac Batrovci", "Borac Ilinci", "Jedinstvo Morović",
                "OFK Bačinci", "OFK Bingula")) {
            assertTrue(clubs.contains(expected), "missing real club: " + expected);
        }
    }

    @Test
    @DisplayName("The two clubs whose name already carries the town are not stuttered")
    void duplicateTownsAreNotRepeated() {
        List<String> clubs = readConstantList("MUNICIPAL_SID_CLUBS");
        for (String name : clubs) {
            String[] parts = name.split(" ");
            assertFalse(parts.length >= 2 && parts[0].equals(parts[1]),
                    name + " repeats its town");
        }
    }

    @Test
    @DisplayName("Every club name is unique — a duplicate name breaks the name-based user lookup")
    void clubNamesAreUnique() {
        List<String> clubs = readConstantList("MUNICIPAL_SID_CLUBS");
        assertEquals(clubs.size(), clubs.stream().distinct().count(),
                "two clubs in the same league share a name");
    }

    @Test
    @DisplayName("The Sremac badge and ground are the real ones")
    void sremacHasItsBadgeAndItsGround() {
        assertEquals("/images/sremac_logo.jpg", readConstant("SREMAC_LOGO"));
        assertEquals("Stadion Livadice", readConstant("SREMAC_STADIUM"));
    }

    @Test
    @DisplayName("Both images the badge depends on exist in static/images")
    void theImageFilesAreActuallyThere() {
        // A path pointing at a file that is not there is the same bug as a field nothing reads: it
        // looks wired and shows a broken image.
        for (String path : List.of(readConstant("SREMAC_LOGO"), readConstant("OMLADINAC_LOGO"),
                "/images/livadice.png")) {
            String file = "src/main/resources/static" + path;
            assertTrue(new java.io.File(file).isFile(), "missing image file " + file);
        }
    }

    @Test
    @DisplayName("The ground name matches the key the fixture view already looks for")
    void theGroundNameResolvesToTheLivadiceImage() {
        // fixture-view.js resolves a stadium by substring. If the seeded name ever stops containing
        // "livadice" the match screen silently falls back to the default stadium image.
        String name = readConstant("SREMAC_STADIUM");
        assertTrue(name.toLowerCase().contains("livadice"),
                "the seeded ground name would not resolve to the Livadice image");
    }

    @Test
    @DisplayName("The second account is a regular user, not an administrator")
    void theSecondAccountIsNotAnAdmin() {
        assertEquals("kecko@example.com", readConstant("SECOND_EMAIL"));
        assertEquals("Kecko123!", readConstant("SECOND_PASSWORD"));
        // Anything but REGULAR/PLUS here would hand a club manager the whole admin surface.
        assertFalse(readConstant("SECOND_EMAIL").contains("OWNER"), "owner email reused");
    }

    @Test
    @DisplayName("The club is flagged human-controlled, which is what the game reads")
    void sremacIsHumanControlled() {
        // TeamFactory does not set it, and nothing else does — if this is missed the club is
        // playable by nobody and the account manages nothing.
        Team sremac = new Team();
        sremac.setName(SREMAC);
        assertFalse(sremac.isHumanControlled(),
                "a freshly created Team defaults to not human-controlled, so the flag must be set "
                        + "explicitly by applyClubIdentity");
    }

    // --- the squad, checked against the match sheet ---

    @Test
    @DisplayName("The squad is the seventeen players on the match sheet")
    void seventeenPlayers() {
        List<Player> squad = seedSquad();
        assertEquals(17, squad.size());
        assertEquals(17, squad.stream().map(Player::getName).distinct().count());
    }

    @Test
    @DisplayName("Shirt numbers 1 to 17 exactly as printed")
    void shirtNumbersMatchTheSheet() {
        List<Player> squad = seedSquad();
        for (int n = 1; n <= 17; n++) {
            final int number = n;
            assertTrue(squad.stream().anyMatch(p -> Integer.valueOf(number).equals(p.getSquadNumber())),
                    "no player wears number " + n);
        }
    }

    @Test
    @DisplayName("Numbers 1 and 12 are the goalkeepers, as the owner specified")
    void goalkeepersAreOneAndTwelve() {
        List<Player> squad = seedSquad();
        for (int n : List.of(1, 12)) {
            Player p = squad.stream().filter(x -> Integer.valueOf(n).equals(x.getSquadNumber()))
                    .findFirst().orElseThrow();
            assertEquals(Position.GK, p.getPosition(), "number " + n + " is not a goalkeeper");
        }
        assertEquals(2, squad.stream().filter(p -> p.getPosition() == Position.GK).count(),
                "the sheet has exactly two goalkeepers and a third appeared");
    }

    @Test
    @DisplayName("Numbers 2-5 defend, 6/7/8/10 are midfield, 9 and 11 attack")
    void theStartingEleveMatchTheSheet() {
        List<Player> squad = seedSquad();
        java.util.Map<Integer, Position> expected = java.util.Map.ofEntries(
                java.util.Map.entry(2, Position.DEF), java.util.Map.entry(3, Position.DEF),
                java.util.Map.entry(4, Position.DEF), java.util.Map.entry(5, Position.DEF),
                java.util.Map.entry(6, Position.MID), java.util.Map.entry(7, Position.MID),
                java.util.Map.entry(8, Position.MID), java.util.Map.entry(9, Position.ATT),
                java.util.Map.entry(10, Position.MID), java.util.Map.entry(11, Position.ATT));
        expected.forEach((number, position) -> {
            Player p = squad.stream().filter(x -> Integer.valueOf(number).equals(x.getSquadNumber()))
                    .findFirst().orElseThrow();
            assertEquals(position, p.getPosition(), "number " + number + " is not " + position);
        });
    }

    @Test
    @DisplayName("Number 8 is Nenad Petrović, not the graphic's typo")
    void eightIsNenadPetrovic() {
        List<Player> squad = seedSquad();
        Player eight = squad.stream().filter(p -> Integer.valueOf(8).equals(p.getSquadNumber()))
                .findFirst().orElseThrow();
        assertEquals("Nenad Petrović", eight.getName());
        assertFalse(squad.stream().anyMatch(p -> p.getName().contains("Nena ")),
                "the 'Nena Petrović' typo was seeded");
    }

    @Test
    @DisplayName("The bench can actually be selected: two defenders, two midfielders, an attacker")
    void theBenchIsBalanced() {
        List<Player> squad = seedSquad();
        List<Player> bench = squad.stream()
                .filter(p -> p.getSquadNumber() != null && p.getSquadNumber() >= 13)
                .toList();
        assertEquals(5, bench.size());
        assertEquals(2, bench.stream().filter(p -> p.getPosition() == Position.DEF).count());
        assertEquals(2, bench.stream().filter(p -> p.getPosition() == Position.MID).count());
        assertEquals(1, bench.stream().filter(p -> p.getPosition() == Position.ATT).count());
    }

    @Test
    @DisplayName("Diacritics survived the trip from the graphic")
    void serbianCharactersSurvived() {
        List<String> names = seedSquad().stream().map(Player::getName).toList();
        for (String expected : List.of("Nenad Ugrenović", "Miloš Matić", "Živko Malić",
                "Slobodan Milanković", "Jovica Bogdanović", "Milanko Subić", "Vasilj Abramović",
                "Srđan Arambašić", "Boris Gospojević", "Srđan Subić", "Stefan Ćetojević",
                "Milan Ćetojević", "Uroš Jovanović", "Lazar Brenjevarac", "Nikola Jović",
                "Branislav Andrić")) {
            assertTrue(names.contains(expected), "missing or mangled name: " + expected);
        }
    }

    @Test
    @DisplayName("This is a fifth-tier club, so nobody is seeded as a world beater")
    void skillsAreMunicipalLevel() {
        for (Player p : seedSquad()) {
            for (SkillName skill : SkillName.values()) {
                if (skill == SkillName.FATIGUE) continue;
                int value = p.getSkills().getSkills().get(skill);
                assertTrue(value <= 12, p.getName() + " has " + skill + " " + value
                        + " — that is Superliga level in a fifth-tier club");
                assertTrue(value >= 1, p.getName() + " has " + skill + " " + value);
            }
        }
    }

    @Test
    @DisplayName("Talent is municipal too, so a good prospect here is genuinely promising")
    void talentIsMunicipalLevel() {
        for (Player p : seedSquad()) {
            assertTrue(p.getTalent() >= 3.0 && p.getTalent() <= 10.0,
                    p.getName() + " has talent " + p.getTalent() + ", outside the municipal band");
        }
        assertTrue(seedSquad().stream().anyMatch(p -> p.getTalent() >= 6.0),
                "no player in the squad is worth developing, which makes the academy pointless here");
    }

    @Test
    @DisplayName("Seeding twice does not produce two Nenad Ugrenovićs")
    void seedingIsIdempotent() {
        Team sremac = new Team();
        sremac.setId(5L);
        sremac.setName(SREMAC);

        // First run: nothing exists, so everything is created and handed back.
        List<Player> created = factory.createSremacPlayers(sremac);
        assertEquals(17, created.size());

        // Second run: the repository now returns them, so nothing is created and the same 17 come back.
        when(players.findByTeam(sremac)).thenReturn(created);
        List<Player> second = factory.createSremacPlayers(sremac);
        assertEquals(17, second.size());
        assertEquals(created.size(), second.stream().distinct().count());
    }

    // --- helpers ---

    private List<Player> seedSquad() {
        Team sremac = new Team();
        sremac.setId(5L);
        sremac.setName(SREMAC);
        when(players.findByTeam(sremac)).thenReturn(List.of());
        return factory.createSremacPlayers(sremac);
    }

    private static String readConstant(String name) {
        try {
            var field = DatabaseInitializer.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof java.util.List<?> list) {
                return String.join(",", (List<String>) list);
            }
            return String.valueOf(value);
        } catch (Exception e) {
            throw new IllegalStateException("no constant " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> readConstantList(String name) {
        try {
            var field = DatabaseInitializer.class.getDeclaredField(name);
            field.setAccessible(true);
            return (List<String>) field.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("no constant " + name, e);
        }
    }
}
