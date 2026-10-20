package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerZoneLoad;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.Zone;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerZoneLoadRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.ZoneLoadMinutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2: the zone-load aggregate reads a projection, and the projection must agree with the entity.
 *
 * <p>{@code applyDailyRecovery} used to load a {@code PlayerZoneLoad} per row and call
 * {@code effectiveMinutes()} on it — a managed entity, with a persistence-context entry behind it,
 * plus every column of the joined match, for an arithmetic sum over four numbers. The projection
 * returns those four numbers and loads no entity at all.
 *
 * <p><b>That makes {@link ZoneLoadMinutes#effectiveMinutes()} a second copy of a rule that already
 * exists</b> on the entity, which is the thing this codebase keeps paying for. So the first test here
 * is not about speed: it asserts the two agree, on a row for <b>every</b> zone, because a copy of a
 * scale rule that drifts is how a recovery figure becomes a number nobody can explain.
 *
 * <p>The rows are built the way the engine writes them — minutes, intensity and a zone per player —
 * rather than from tidy numbers, because a fixture that agrees with the code proves nothing.
 */
class ZoneLoadProjectionTest extends BaseTest {

    @Autowired private PlayerZoneLoadRepository rows;
    @Autowired private MatchRepository matches;
    @Autowired private PlayerRepository players;
    @Autowired private TeamRepository teams;

    /**
     * A date no other test will use, so a window bounded to it can only contain this test's rows.
     * The H2 test database is shared across the suite and nothing rolls it back between classes.
     */
    private static final LocalDateTime FAR_FUTURE_MATCH = LocalDateTime.of(2099, 1, 1, 19, 0);

    @Test
    @Transactional
    @DisplayName("the projection's arithmetic equals the entity's, for every zone")
    void theProjectionAgreesWithTheEntityOnEveryZone() {
        Match match = aMatch();
        Team club = aClub();
        Player player = players.save(aPlayer(club));

        for (Zone zone : Zone.values()) {
            rows.save(aRow(player, match, zone, 37.0, 0.62));
        }

        List<PlayerZoneLoad> entities = rows.findByMatchId(match.getId());
        assertEquals(Zone.values().length, entities.size(),
                "the fixture did not build a row per zone, so this test would compare nothing");

        for (PlayerZoneLoad entity : entities) {
            ZoneLoadMinutes projection =
                    new ZoneLoadMinutes(player.getId(), entity.getZone(), entity.getMinutes(), entity.getIntensity());
            assertEquals(entity.effectiveMinutes(), projection.effectiveMinutes(), 1e-9,
                    "the projection and the entity disagree for zone " + entity.getZone()
                            + ". Recovery is summed from the projection, so a drift here is a recovery figure "
                            + "nobody can explain, and it would not fail anywhere else.");
        }
    }

    @Test
    @Transactional
    @DisplayName("the projection is what the recovery window actually returns")
    void theProjectionReturnsTheWindowsRows() {
        // The counterweight to the test above. A projection that is arithmetically correct but not wired
        // into the query satisfies it perfectly while recovery reads nothing at all.
        Match match = aMatch();
        Team club = aClub();
        Player player = players.save(aPlayer(club));
        rows.save(aRow(player, match, Zone.MIDFIELD_CENTRE, 41.0, 0.8));
        rows.save(aRow(player, match, Zone.ATTACKING_LEFT, 12.0, 0.5));

        // The window is bounded to this test's own match, NOT "everything". The first version asked from
        // 2000 and asserted every returned row was this test's player — and it failed the moment another
        // class put a zone load in the shared database, which is this repository's most repeated lesson.
        List<ZoneLoadMinutes> projected =
                rows.findLoadMinutesPlayedSince(FAR_FUTURE_MATCH.minusHours(1));

        assertFalse(projected.isEmpty(), "the projection returned no rows for a window that contains them");
        assertEquals(2, projected.size(),
                "the window bounded to this test's match returned " + projected.size() + " rows, expected the "
                        + "two it created");
        assertTrue(projected.stream().allMatch(p -> player.getId().equals(p.playerId())),
                "the projection returned rows for a player it should not have");

        double total = projected.stream().mapToDouble(ZoneLoadMinutes::effectiveMinutes).sum();
        assertEquals(41.0 * 0.8 * Zone.MIDFIELD_CENTRE.workRate()
                        + 12.0 * 0.5 * Zone.ATTACKING_LEFT.workRate(), total, 1e-9,
                "the projected rows do not sum to what the same rows sum to as entities");
    }

    // --- fixture, built the way ZoneLoadRecorder writes it ---

    private PlayerZoneLoad aRow(Player player, Match match, Zone zone, double minutes, double intensity) {
        PlayerZoneLoad load = new PlayerZoneLoad();
        load.setPlayer(player);
        load.setMatch(match);
        load.setZone(zone);
        load.setMinutes(minutes);
        load.setIntensity(intensity);
        return rows.save(load);
    }

    private Match aMatch() {
        Match match = new Match();
        match.setSeasonYear(1);
        match.setWeekNumber(1);
        match.setPlayed(true);
        match.setHomeGoals(1);
        match.setAwayGoals(0);
        match.setMatchDate(FAR_FUTURE_MATCH);
        match.setEventJson("[]");
        return matches.save(match);
    }

    private Team aClub() {
        Team team = new Team();
        team.setName("ZZ Zone club " + UUID.randomUUID());
        return teams.save(team);
    }

    private Player aPlayer(Team club) {
        Player player = new Player();
        player.setName("ZZ Zone player " + UUID.randomUUID());
        player.setTeam(club);
        player.setAge(23);
        return player;
    }
}
