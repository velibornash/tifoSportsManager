package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A position for every slot, not only the one a player usually plays (owner, 2026-10-11).
 *
 * <p>Sokker's squad page lets a manager put a midfielder at full-back and a striker in goal. The player
 * carries his own position, so a slot's position cannot be read from the man standing in it — it has to be
 * stored beside the slot, index-aligned with the starter order.
 *
 * <p><b>Falling back is the point, not a convenience.</b> Every lineup saved before this column existed
 * has no positions at all, and a manager who changes his eleven without setting them must not lose his
 * side. An absent position is therefore the player's own, and a short list is padded rather than the
 * lineup being rejected or truncated.
 */
class LineupSlotPositionTest extends BaseTest {

    @Autowired private MatchLineupService lineups;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private LineupRepository lineupRepo;
    @Autowired private MatchFixtureRepository fixtures;

    @Test
    @DisplayName("each slot's position is read back in the order the starters are")
    void positionsAreReadBackInStarterOrder() {
        Team club = aClub("Slot Club");
        List<Long> starters = elevenPlayers(club);
        Long bench = extraPlayer(club, "Bench One").getId();

        lineups.save(club.getId(), fixtureFor(club), starters, List.of(bench), "4-4-2", "Balanced",
                List.of(Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                        Position.MID, Position.MID, Position.MID, Position.MID,
                        Position.ATT, Position.ATT));

        assertThat(lineups.starterPositionsOf(template(club.getId())))
                .containsExactly(Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                        Position.MID, Position.MID, Position.MID, Position.MID,
                        Position.ATT, Position.ATT);
    }

    @Test
    @DisplayName("a slot can hold a player whose own position is elsewhere")
    void aSlotMayDisagreeWithThePlayer() {
        Team club = aClub("Disagreeing Club");
        List<Long> starters = new ArrayList<>();

        // A striker in goal, on purpose: the slot says GK and the player stays a striker.
        Player striker = extraPlayer(club, "Striker In Goal");
        striker.setPosition(Position.ATT);
        players.save(striker);
        starters.add(striker.getId());
        for (int i = 1; i < 11; i++) {
            starters.add(extraPlayer(club, "Filler " + i).getId());
        }

        lineups.save(club.getId(), fixtureFor(club), starters, List.of(), "4-4-2", "Balanced",
                List.of(Position.GK));

        assertThat(lineups.starterPositionsOf(template(club.getId())).get(0))
                .as("the slot in goal is a keeper's slot whoever is standing in it")
                .isEqualTo(Position.GK);
        assertThat(players.findById(striker.getId()).orElseThrow().getPosition())
                .as("and the player is still a striker afterwards - the slot does not rewrite the man")
                .isEqualTo(Position.ATT);
    }

    @Test
    @DisplayName("a lineup saved without positions still reads: each slot falls back to its player's own")
    void anUnpositionedLineupFallsBackToThePlayer() {
        Team club = aClub("Legacy Club");
        List<Long> starters = elevenPlayers(club);

        // No positions at all - the shape every lineup saved before this existed.
        lineups.save(club.getId(), fixtureFor(club), starters, List.of(), "4-4-2", "Balanced", null);

        List<Position> positions = lineups.starterPositionsOf(template(club.getId()));

        assertThat(positions).hasSize(11);
        assertThat(positions.get(0))
                .isEqualTo(players.findById(starters.get(0)).orElseThrow().getPosition());
    }

    @Test
    @DisplayName("a short position list is padded rather than rejected")
    void aShortListIsPadded() {
        Team club = aClub("Padded Club");
        List<Long> starters = elevenPlayers(club);

        // Two positions for eleven slots: the manager set the keeper and forgot the rest.
        lineups.save(club.getId(), fixtureFor(club), starters, List.of(), "4-4-2", "Balanced",
                List.of(Position.GK, Position.DEF));

        assertThat(lineups.starterPositionsOf(template(club.getId())))
                .as("a forgotten position must not cost the manager his eleven")
                .hasSize(11);
    }

    @Test
    @DisplayName("an unknown position name is ignored rather than taking the lineup down")
    void anUnknownPositionIsIgnored() {
        Team club = aClub("Odd Club");
        List<Long> starters = elevenPlayers(club);

        lineups.save(club.getId(), fixtureFor(club), starters, List.of(), "4-4-2", "Balanced", null);
        Lineup saved = template(club.getId());
        saved.setStarterPositionsFrom(List.of("NOT_A_POSITION"));

        assertThat(lineups.starterPositionsOf(saved))
                .as("hand-edited or stale JSON must not make the lineup unreadable")
                .isNotEmpty();
    }

    // ---------- helpers ----------

    /** The club's standing template. */
    private Lineup template(Long clubId) {
        return lineupRepo.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(clubId).orElseThrow();
    }

    /**
     * A real, unplayed fixture for the club.
     *
     * <p>A fixture is required - {@code save} refuses a club that is not playing in it. Saving against an
     * <b>unplayed</b> fixture is what writes the club's standing template, because a lineup can only be
     * linked to a {@code Match} and an unplayed fixture has none. That is the structural gap on the board,
     * and this test uses it rather than working around it.
     */
    private Long fixtureFor(Team club) {
        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(club);
        fixture.setAwayTeam(aClub("Opponent " + System.nanoTime()));
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setPlayed(false);
        return fixtures.save(fixture).getId();
    }

    private Team aClub(String name) {
        Team club = new Team();
        club.setName(name + " " + System.nanoTime());
        return teams.save(club);
    }

    /** Eleven players whose own positions cover a plausible shape. */
    private List<Long> elevenPlayers(Team club) {
        Position[] shape = {Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID, Position.MID, Position.ATT, Position.ATT};
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < shape.length; i++) {
            Player player = extraPlayer(club, "P" + i + " " + System.nanoTime());
            player.setPosition(shape[i]);
            ids.add(players.save(player).getId());
        }
        return ids;
    }

    private Player extraPlayer(Team club, String name) {
        Player player = new Player();
        player.setName(name);
        player.setTeam(club);
        player.setPosition(Position.MID);
        player.setRating(60);
        player.setAge(24);
        player.setForm(6.5);
        player.setMorale(60.0);
        return players.save(player);
    }
}
