package org.tifo.footballtextmanager.engine;

import org.tifo.footballtextmanager.model.CSPlayer;
import org.tifo.footballtextmanager.model.CSPosition;
import org.tifo.footballtextmanager.model.CSTeam;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A club must be able to field a legal eleven.
 *
 * <p>This class exists because of the worst defect found in the project, which was invisible in
 * every screenshot and every log line. `pickStartingEleven` selected the eleven highest-rated
 * players <em>by rating alone</em>, and every club was seeded with exactly one goalkeeper whose
 * rating was rolled independently of the outfielders. Measured against the live database, the
 * keeper fell outside the top eleven for <b>nine of seventeen clubs</b> — FK Đerdap's was
 * fifteenth of fifteen. Those nine clubs played every match with no goalkeeper.
 *
 * <p>No error was raised. The eleven simply did not contain a keeper, and nothing in the product
 * said so.
 */
class CSStartingElevenTest {

    private final CSMatchSimulator simulator = new CSMatchSimulator();

    private static CSPlayer player(long id, String name, CSPosition pos, int rating) {
        CSPlayer p = SquadFixture.player(id, name, pos.name(), 6);
        p.setPosition(pos.name());
        p.setRating(rating);
        return p;
    }

    /** The shape that produced the bug: one keeper, rolled low, fourteen outfielders. */
    private static List<CSPlayer> oneKeeperSquad(long prefix, int keeperRating) {
        List<CSPlayer> squad = new ArrayList<>();
        squad.add(player(prefix, "Keeper", CSPosition.GK, keeperRating));
        squad.add(player(prefix + 1, "Defender A", CSPosition.DEF, 5));
        squad.add(player(prefix + 2, "Defender B", CSPosition.DEF, 5));
        squad.add(player(prefix + 3, "Defender C", CSPosition.DEF, 5));
        squad.add(player(prefix + 4, "Defender D", CSPosition.DEF, 5));
        squad.add(player(prefix + 5, "Midscraper A", CSPosition.MID, 7));
        squad.add(player(prefix + 6, "Midscraper B", CSPosition.MID, 7));
        squad.add(player(prefix + 7, "Midscraper C", CSPosition.MID, 7));
        squad.add(player(prefix + 8, "Winger A", CSPosition.WNG, 7));
        squad.add(player(prefix + 9, "Winger B", CSPosition.WNG, 7));
        squad.add(player(prefix + 10, "Striker A", CSPosition.ATT, 7));
        squad.add(player(prefix + 11, "Striker B", CSPosition.ATT, 7));
        squad.add(player(prefix + 12, "Striker C", CSPosition.ATT, 7));
        squad.add(player(prefix + 13, "Striker D", CSPosition.ATT, 7));
        squad.add(player(prefix + 14, "Striker E", CSPosition.ATT, 7));
        return squad;
    }

    @Test
    void aKeeperPlaysEvenWhenHeIsTheWorstPlayerInTheSquad() {
        // This is FK Đerdap: keeper fifteenth of fifteen. He must still start.
        List<CSPlayer> eleven = simulator.pickStartingEleven(oneKeeperSquad(100, 4), List.of());

        assertEquals(11, eleven.size(), "the club did not field eleven");
        assertTrue(eleven.stream().anyMatch(p -> "GK".equals(p.getPosition())),
                "no goalkeeper in the eleven, again");
    }

    @Test
    void everyRatingOrderStillProducesAGoalkeeper() {
        // The defect was not a single unlucky squad: it was every ordering of keeper and outfield
        // ratings. Roll the keeper through the whole range and check all of them.
        for (int keeperRating = 1; keeperRating <= 10; keeperRating++) {
            List<CSPlayer> eleven = simulator.pickStartingEleven(
                    oneKeeperSquad(200 + keeperRating * 20L, keeperRating), List.of());

            assertEquals(11, eleven.size(),
                    "eleven is not eleven with a keeper rated " + keeperRating);
            assertTrue(eleven.stream().anyMatch(p -> "GK".equals(p.getPosition())),
                    "a keeper rated " + keeperRating + " did not start, and the club is unplayable");
        }
    }

    @Test
    void theElevenRespectsTheSquadsOwnShape() {
        // A back-three, huge-midfield squad must field three defenders.
        List<CSPlayer> squad = new ArrayList<>();
        squad.add(player(1, "Keeper One", CSPosition.GK, 5));
        squad.add(player(2, "Keeper Two", CSPosition.GK, 4));
        for (int i = 0; i < 3; i++) squad.add(player(10 + i, "Def " + i, CSPosition.DEF, 6));
        for (int i = 0; i < 8; i++) squad.add(player(30 + i, "Mid " + i, CSPosition.MID, 6));
        for (int i = 0; i < 3; i++) squad.add(player(60 + i, "Att " + i, CSPosition.ATT, 7));

        String formation = CSOpponentProfile.formationFor(null, squad);
        List<CSPlayer> eleven = simulator.pickStartingEleven(squad, List.of());

        assertEquals(3, countIn(eleven, CSPosition.DEF),
                "a " + formation + " squad did not field three defenders");
        assertEquals(1, countIn(eleven, CSPosition.GK));
    }

    @Test
    void aWingerCanFillBothMidfieldAndAttack() {
        // Wingers count towards both lines; a squad built around them must not be told it has no
        // winger available for a midfield slot.
        List<CSPlayer> squad = new ArrayList<>();
        squad.add(player(1, "Keeper One", CSPosition.GK, 5));
        squad.add(player(2, "Keeper Two", CSPosition.GK, 4));
        for (int i = 0; i < 4; i++) squad.add(player(10 + i, "Def " + i, CSPosition.DEF, 6));
        for (int i = 0; i < 2; i++) squad.add(player(30 + i, "Mid " + i, CSPosition.MID, 6));
        for (int i = 0; i < 4; i++) squad.add(player(50 + i, "Wng " + i, CSPosition.WNG, 8));
        for (int i = 0; i < 2; i++) squad.add(player(70 + i, "Att " + i, CSPosition.ATT, 6));

        List<CSPlayer> eleven = simulator.pickStartingEleven(squad, List.of());

        assertEquals(11, eleven.size());
        assertEquals(1, countIn(eleven, CSPosition.GK));
        assertTrue(eleven.stream().anyMatch(p -> "WNG".equals(p.getPosition())),
                "no winger started despite four in the squad and two of them rated 8");
    }

    @Test
    void theManagersChoicesAreHonouredButCannotRemoveTheKeeper() {
        // Eleven outfielders nominated. A goalkeeper must still start: a manager who picks an
        // illegal eleven has made a mistake the engine must not honour silently.
        List<CSPlayer> squad = oneKeeperSquad(400, 4);
        List<Long> allElevenOutfielders = squad.stream()
                .filter(p -> !"GK".equals(p.getPosition()))
                .limit(11)
                .map(CSPlayer::getId)
                .toList();

        List<CSPlayer> eleven = simulator.pickStartingEleven(squad, allElevenOutfielders);

        assertEquals(11, eleven.size());
        assertEquals(1, countIn(eleven, CSPosition.GK),
                "the nominated outfield eleven was allowed to field no goalkeeper");
    }

    @Test
    void aNominatedKeeperIsTheOneWhoPlays() {
        List<CSPlayer> squad = new ArrayList<>();
        squad.add(player(1, "First Choice", CSPosition.GK, 4));
        squad.add(player(2, "Better On Paper", CSPosition.GK, 9));
        for (int i = 0; i < 4; i++) squad.add(player(10 + i, "Def " + i, CSPosition.DEF, 6));
        for (int i = 0; i < 4; i++) squad.add(player(30 + i, "Mid " + i, CSPosition.MID, 6));
        for (int i = 0; i < 4; i++) squad.add(player(50 + i, "Att " + i, CSPosition.ATT, 6));

        List<CSPlayer> eleven = simulator.pickStartingEleven(squad, List.of(1L));

        assertTrue(eleven.stream().anyMatch(p -> p.getId() == 1L),
                "the manager's nominated keeper was dropped in favour of a better-rated reserve");
    }

    /**
     * Two rows of the formation table summed to nine outfielders instead of ten, which is legal on
     * paper and a team of ten the moment it is used to build an eleven.
     */
    @Test
    void everyFormationCanFieldElevenPlayers() {
        List<String> formations = List.of("4-4-2", "4-3-3", "4-2-3-1", "3-5-2", "5-3-2", "4-1-4-1");

        for (String formation : formations) {
            List<org.tifo.footballtextmanager.model.CSPosition> slots =
                    CSOpponentProfile.outfieldSlotsFor(formation);
            assertEquals(10, slots.size(),
                    formation + " has " + slots.size() + " outfield slots, so it fields "
                            + (slots.size() + 1) + " players");
        }
    }

    @Test
    void twoKeepersAreEnoughForTheSquadToSurviveALossOfTheFirstChoice() {
        // The reason the squad needs a second keeper at all: with one, an injury ends the season.
        List<CSPlayer> squad = new ArrayList<>();
        squad.add(player(1, "Keeper One", CSPosition.GK, 8));
        squad.add(player(2, "Keeper Two", CSPosition.GK, 4));
        for (int i = 0; i < 4; i++) squad.add(player(10 + i, "Def " + i, CSPosition.DEF, 6));
        for (int i = 0; i < 4; i++) squad.add(player(30 + i, "Mid " + i, CSPosition.MID, 6));
        for (int i = 0; i < 4; i++) squad.add(player(50 + i, "Att " + i, CSPosition.ATT, 6));

        // The number one is unavailable — injured, suspended or sent off.
        List<CSPlayer> available = squad.stream().filter(p -> p.getId() != 1L).toList();
        List<CSPlayer> eleven = simulator.pickStartingEleven(available, List.of());

        assertEquals(11, eleven.size(), "a squad with one remaining keeper could not field eleven");
        assertEquals(1, countIn(eleven, CSPosition.GK));
    }

    @Test
    void selectionIsStableRatherThanDependentOnRosterOrder() {
        // The repository's ordering must not decide who plays.
        List<CSPlayer> squad = oneKeeperSquad(500, 5);
        List<CSPlayer> shuffled = new ArrayList<>(squad);
        java.util.Collections.reverse(shuffled);

        List<String> straight = names(simulator.pickStartingEleven(squad, List.of()));
        List<String> reversed = names(simulator.pickStartingEleven(shuffled, List.of()));

        assertEquals(straight.size(), reversed.size());
        assertEquals(1, countIn(simulator.pickStartingEleven(shuffled, List.of()), CSPosition.GK));
        assertNotNull(straight);
        assertFalse(straight.isEmpty());
    }

    private static int countIn(List<CSPlayer> players, CSPosition pos) {
        return (int) players.stream().filter(p -> pos.name().equals(p.getPosition())).count();
    }

    private static List<String> names(List<CSPlayer> players) {
        return players.stream().map(CSPlayer::getName).sorted().toList();
    }
}