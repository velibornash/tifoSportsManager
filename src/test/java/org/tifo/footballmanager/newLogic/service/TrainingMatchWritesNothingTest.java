package org.tifo.footballmanager.newLogic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.tifo.commonmanager.model.User;
import org.tifo.commonmanager.model.UserRole;
import org.tifo.commonmanager.repository.UserRepository;
import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.ProductionSource;
import org.tifo.footballmanager.newLogic.exception.ApiException;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.Lineup;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.model.Skills;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.LineupRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.tifo.footballmanager.newLogic.repository.MatchRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.sim.TrainingOpponentFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A training match writes nothing, and the day's limit is the only thing it leaves behind.
 *
 * <p><b>The owner's spec (2026-10-27), and this class is its exit criteria.</b> He picks an
 * opponent's strength, watches ninety minutes, reads the full statistics — and none of it is saved
 * to the database. Not the fixture, not the match, not the player stats, not the condition, not a
 * table, not a career record. The page says so in words, and here it is measured rather than
 * promised: the table counts are taken before and after the match, and the service's own source is
 * scanned for a write path.
 *
 * <p><b>Why a count and not only a scan.</b> A scan proves the service has no {@code save} in it;
 * it says nothing about a save three calls deep, in the engine, on a path nobody looked at. The
 * counts catch that. The scan catches the other direction: a write that only happens under a
 * condition no fixture in this test reaches. The two together, and deliberately broken once each,
 * are what makes this a guard.
 *
 * <p>The daily limit is asserted through the same service, because that is where the owner's rule
 * lives: one match a day for a normal manager, and none at all for {@code velibor@example.com} so
 * he can test the engine as often as he likes.
 */
class TrainingMatchWritesNothingTest extends BaseTest {

    @Autowired private TrainingMatchService service;
    @Autowired private TrainingMatchLimitCache limits;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private MatchRepository matches;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private MatchPlayerStatsRepository stats;
    @Autowired private LineupRepository lineups;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private CountryRepository countries;
    @Autowired private org.tifo.footballmanager.newLogic.repository.CompetitionRepository competitions;
    @Autowired private UserRepository users;

    // ---------- the exit criteria ----------

    @Test
    @DisplayName("a training match leaves no row behind, and spends the day's one match")
    void aTrainingMatchWritesNothing() {
        Team club = aManagedClub();
        User manager = aUser(club);
        Map<String, Object> before = service.status(manager);
        assertEquals(Boolean.TRUE, before.get("canPlay"),
                "the fixture must start with a match in hand, or nothing is being tested");

        Counts start = new Counts(club);

        Map<String, Object> result = service.play(manager, 14, null);

        // What the page is promised: a full match, a score, a recording, and the words.
        assertEquals("ok", result.get("status"));
        assertNotNull(result.get("homeGoals"), "the score is what Show results displays");
        assertNotNull(result.get("awayGoals"));
        assertTrue(((Number) result.get("replayId")).longValue() > 0,
                "\"Watch your match\" opens a recording, so there has to be one");
        assertTrue(String.valueOf(result.get("viewerUrl")).contains(TrainingMatchService.VIEWER_PATH),
                "the viewer must be the same viewer every other match opens");
        assertEquals(TrainingMatchService.NOTICE, result.get("notice"),
                "the page has to state that the match is not saved, or a manager reads a number "
                        + "that means nothing as one that counts");
        assertNotNull(result.get("outcome"), "Show results renders the full statistics from this");
        assertEquals(14, result.get("strength"));
        assertEquals(Boolean.FALSE, result.get("unlimited"));

        start.assertUnchanged();

        // The limit is the one thing remembered — and only for this game day.
        Map<String, Object> after = service.status(manager);
        assertEquals(Boolean.FALSE, after.get("canPlay"),
                "a normal manager must get exactly one training match per game day");

        ApiException refusal = assertThrows(ApiException.class, () -> service.play(manager, 14, null),
                "the second match of the day must be refused, and refused before the engine runs");
        assertEquals("TRAINING_LIMIT", refusal.getCode());

        start.assertUnchanged();
        assertFalse(limits.canPlay(accountOf(manager), gameDay(after)),
                "the refusal came from somewhere; if not from the cache, from what?");
    }

    @Test
    @DisplayName("velibor@example.com may play again on the same day, because he is testing the engine")
    void theOwnersAccountIsNotLimited() {
        Team club = aManagedClub();
        User owner = aUser(club, "velibor@example.com");

        Map<String, Object> status = service.status(owner);
        assertEquals(Boolean.TRUE, status.get("unlimited"),
                "the owner's own account is exempt from the daily limit (owner, 2026-10-27)");

        Counts start = new Counts(club);
        service.play(owner, 8, null);
        start.assertUnchanged();

        assertEquals(Boolean.TRUE, service.status(owner).get("canPlay"),
                "having just played must not spend his match — that is the whole point of the "
                        + "exemption, and a record() that forgot to skip unlimited accounts would "
                        + "put it back under the limit");
    }

    @Test
    @DisplayName("the strength on the slider is clamped into 8..20, never obeyed outside it")
    void theStrengthIsClamped() {
        Team club = aManagedClub();
        User manager = aUser(club);

        Map<String, Object> status = service.status(manager);
        assertEquals(TrainingOpponentFactory.MIN_STRENGTH, status.get("minStrength"));
        assertEquals(TrainingOpponentFactory.MAX_STRENGTH, status.get("maxStrength"));

        Map<String, Object> played = service.play(manager, 999, null);
        assertEquals(TrainingOpponentFactory.MAX_STRENGTH, played.get("strength"),
                "a strength past the band must play against the ceiling of the band");
    }

    @Test
    @DisplayName("the service has no write path in it at all")
    void theServiceHasNoWritePath() throws IOException {
        Path source = ProductionSource.file(TrainingMatchService.class);
        assertTrue(Files.exists(source),
                "the guard reads the real source; if it cannot find it, it is not guarding anything");
        String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));

        for (String write : List.of(".save(", "saveAll(", ".persist(", ".merge(", ".delete(",
                "saveAndFlush(", "EntityManager")) {
            assertTrue(!code.contains(write),
                    () -> "TrainingMatchService calls " + write + ". A training match writes no "
                            + "rows at all — the daily limit lives in a cache, by the owner's own "
                            + "words (2026-10-27), and everything else is handed to the page.");
        }
    }

    // ---------- fixtures ----------

    /** A unique three-letter ISO from the suite's own generator, so no two countries collide. */
    private static String randomIso() {
        return org.tifo.footballmanager.UniqueIsoCodes.next();
    }

    /**
     * A human club with a real eleven.
     *
     * <p>Eleven, not zero: a club with no squad would send the engine down its synthetic fallback,
     * and then the test would be measuring stand-ins rather than the manager's own side.
     */
    private Team aManagedClub() {
        Country country = new Country();
        country.setName("Training Country " + UUID.randomUUID().toString().substring(0, 8));
        country.setIsoCode(randomIso());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Competition league = new Competition();
        league.setName(country.getName() + " League");
        league.setType(CompetitionType.LEAGUE);
        league.setCountry(country);
        league.setTier(1);
        league = competitions.save(league);

        Team club = new Team();
        club.setName("Training Club " + UUID.randomUUID().toString().substring(0, 8));
        club.setCountry(country);
        club.setCompetition(league);
        club.setHumanControlled(true);
        club.setReputation(60.0);
        club.setBudget(10_000_000.0);
        club = teams.save(club);

        List<Player> squad = new ArrayList<>();
        Position[] shape = {Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID, Position.MID,
                Position.ATT, Position.ATT};
        for (int i = 0; i < shape.length; i++) {
            squad.add(players.save(aPlayer(club, "TT P" + (i + 1), shape[i])));
        }

        Lineup lineup = new Lineup();
        lineup.setTeam(club);
        lineup.setStartingPlayers(new ArrayList<>(squad));
        lineup.setStarterOrder(String.join(",", squad.stream()
                .map(Player::getId).map(String::valueOf).toList()));
        lineup.setFormation("4-4-2");
        lineups.save(lineup);
        return club;
    }

    private Player aPlayer(Team club, String name, Position position) {
        Skills skills = new Skills();
        for (SkillName skill : SkillName.values()) {
            skills.setSkill(skill, 13);
        }
        skills.initializeExactFromVisibleIfNeeded();

        Player player = new Player();
        player.setName(name);
        player.setTeam(club);
        player.setAge(24);
        player.setPosition(position);
        player.setSkills(skills);
        player.setPlayerValue(2_000_000);
        player.setEarnings(9_000);
        player.setMorale(60.0);
        player.setForm(6.0);
        player.setRating(player.careerRating());
        return player;
    }

    private User aUser(Team club) {
        return aUser(club, "training-" + UUID.randomUUID() + "@test.local");
    }

    private User aUser(Team club, String email) {
        User user = new User();
        user.setUsername(email);
        user.setEmail(email);
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Training tester");
        user.setRole(UserRole.REGULAR);
        user.setPlusSubscription(false);
        user.setFootballTeam(club);
        return users.save(user);
    }

    private String accountOf(User user) {
        return user.getEmail();
    }

    private int gameDay(Map<String, Object> status) {
        return ((Number) status.get("gameDay")).intValue();
    }

    private String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /**
     * Row counts of every table a match could plausibly touch, taken as one snapshot.
     *
     * <p>An inner class rather than a record of numbers taken elsewhere on purpose: the seven counts
     * have to be read together, and a helper that forgot one of them would silently stop guarding
     * that table.
     */
    private class Counts {
        private final long matches = TrainingMatchWritesNothingTest.this.matches.count();
        private final long fixtures = TrainingMatchWritesNothingTest.this.fixtures.count();
        private final long playerStats = TrainingMatchWritesNothingTest.this.stats.count();
        private final long players = TrainingMatchWritesNothingTest.this.players.count();
        private final long teams = TrainingMatchWritesNothingTest.this.teams.count();
        private final long lineups = TrainingMatchWritesNothingTest.this.lineups.count();
        private final long entries = TrainingMatchWritesNothingTest.this.entries.count();

        /** The eleven's condition, because writing it back is a save even though no row is added. */
        private final java.util.Map<Long, Double> fatigue = new java.util.HashMap<>();

        Counts(Team club) {
            for (Player player : TrainingMatchWritesNothingTest.this.players
                    .findByTeamId(club.getId())) {
                double before = player.getSkills() == null ? 0 : player.getSkills().getFatigue();
                fatigue.put(player.getId(), before);
            }
        }

        void assertUnchanged() {
            assertEquals(matches, TrainingMatchWritesNothingTest.this.matches.count(),
                    "a match row was written: a training match is not a fixture the world plays");
            assertEquals(fixtures, TrainingMatchWritesNothingTest.this.fixtures.count(),
                    "a fixture row was written: there is nothing to schedule, it is not saved");
            assertEquals(playerStats, TrainingMatchWritesNothingTest.this.stats.count(),
                    "per-player statistics were written: those are a record of a real match");
            assertEquals(players, TrainingMatchWritesNothingTest.this.players.count(),
                    "a player row was written — most likely the synthetic opponent, which exists "
                            + "for ninety minutes and then is gone");
            assertEquals(teams, TrainingMatchWritesNothingTest.this.teams.count(),
                    "a team row was written: the opponent is not a club in the world");
            assertEquals(lineups, TrainingMatchWritesNothingTest.this.lineups.count(),
                    "a lineup was written: a training match must not become anybody's team sheet");
            assertEquals(entries, TrainingMatchWritesNothingTest.this.entries.count(),
                    "a table entry moved: nothing about a training match counts for anything");

            for (var entry : fatigue.entrySet()) {
                Player fresh = TrainingMatchWritesNothingTest.this.players
                        .findById(entry.getKey()).orElseThrow();
                double now = fresh.getSkills() == null ? 0 : fresh.getSkills().getFatigue();
                assertEquals(entry.getValue().doubleValue(), now, 0.0001,
                        "a player's condition was charged for a match that is not saved: an update "
                                + "is a database write just as much as an insert is");
            }
        }
    }
}
