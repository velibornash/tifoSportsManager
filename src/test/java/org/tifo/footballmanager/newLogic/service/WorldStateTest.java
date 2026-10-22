package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world readout has to be right about a world that cannot be played.
 *
 * <p><b>What was wrong, measured on the owner's own stack.</b> The admin panel showed the world's
 * state, and the state it computed was: the countries, the national sides, the squads on them and
 * the legacy rows. <b>Not one club, not one player, not one fixture</b> — and those three are what a
 * world is made of. Two things followed from that, and both were wrong:
 *
 * <ul>
 *   <li>A world with <b>no clubs at all</b> was reported as <b>"World is whole"</b>. The club count
 *       was in the payload the whole time; the panel never read it.</li>
 *   <li>A fully built world was reported as <b>"World needs repair"</b>, because one national squad
 *       was missing and the sentence did not say which one or which button would fix it.</li>
 * </ul>
 *
 * <p>The second one was not only unhelpful — it was a false alarm on a world that was fine. The
 * panel pointed the owner at <b>Repair world</b> for a world with 310 clubs, 7,730 players and 2,844
 * fixtures in it.
 *
 * <p><b>Why these are written against a value and not a database.</b> The judgement is the thing
 * that was wrong, and it is now made by {@link WorldState} — a record that can be written down in
 * one line. A test that has to build forty-eight countries and thirty-one divisions to ask "is a
 * world with no clubs whole?" is a test that will be skipped, and a test that is skipped guards
 * nothing.
 */
class WorldStateTest {

    /**
     * A world that has everything: the catalogue, the pyramid, and nothing wrong anywhere.
     */
    private static WorldState whole() {
        return new WorldState(
                48, 48,      // countries, expected
                96,          // national sides
                48,          // senior sides
                48,          // senior sides with a squad
                14_976,      // clubs in the world
                "Serbia",
                310,         // clubs in the played country
                7_730,       // players on their teams
                2_844,       // fixtures of its competitions
                List.of(),   // sides without a squad
                List.of());  // legacy rows
    }

    @Test
    @DisplayName("a world with everything in it is whole, and names no button")
    void aWholeWorldIsHealthy() {
        WorldState state = whole();

        assertTrue(state.catalogueOk(), "the catalogue is complete");
        assertTrue(state.pyramidOk(), "the pyramid is complete");
        assertTrue(state.healthy(), "so the world is whole");
        assertNull(state.nextStep(), "and nothing needs building");
    }

    @Test
    @DisplayName("a world with no clubs in it is not whole, and says Initialize DB")
    void anEmptyWorldIsNotHealthy() {
        // The catalogue is complete and every senior side has a squad — which is exactly the state
        // "Repair world" leaves behind on a fresh database. **This is the case the old readout
        // called "World is whole"**: it counted countries, sides and squads, all of them fine, and
        // never looked at a club.
        WorldState state = new WorldState(
                48, 48, 96, 48, 48, 0, "Serbia",
                0, 0, 0,
                List.of(), List.of());

        assertTrue(state.catalogueOk(), "the catalogue really is complete — that is the point");
        assertFalse(state.pyramidOk(), "but there is no football in it");
        assertFalse(state.healthy(), "so the world is not whole");
        assertEquals("initialize", state.nextStep(),
                "and the button is Initialize DB, because it is the only one that can build a world "
                        + "from nothing — Repair world tops up the catalogue and builds no clubs");
    }

    @Test
    @DisplayName("clubs with no players are not a world")
    void clubsWithoutPlayersAreNotHealthy() {
        // The owner's own data once produced this: clubs that existed, held players, and every one
        // of those players was unusable. The world looked populated and could not play a match.
        WorldState state = new WorldState(
                48, 48, 96, 48, 48, 310, "Serbia",
                310, 0, 2_844,
                List.of(), List.of());

        assertFalse(state.pyramidOk(), "a club with no players cannot play a match");
        assertFalse(state.healthy(), "so the world is not whole");
        assertEquals("initialize", state.nextStep(),
                "and Initialize DB tops a club up — it creates a squad for a club that has none and "
                        + "leaves a club that already has one alone");
    }

    @Test
    @DisplayName("a world with clubs and players and no fixtures is not whole")
    void aWorldWithoutFixturesIsNotHealthy() {
        WorldState state = new WorldState(
                48, 48, 96, 48, 48, 310, "Serbia",
                310, 7_730, 0,
                List.of(), List.of());

        assertFalse(state.pyramidOk(), "there is nothing to play");
        assertFalse(state.healthy(), "so the world is not whole");
        assertEquals("initialize", state.nextStep(), "and the schedule is drawn by Initialize DB");
    }

    @Test
    @DisplayName("a damaged catalogue with a standing pyramid says Repair world")
    void aDamagedCatalogueSaysRepair() {
        // The measured case on the owner's world: 310 clubs, 7,730 players and 2,844 fixtures, and
        // one senior side with no squad. The old panel said "World needs repair" and left it there.
        WorldState state = new WorldState(
                48, 48, 96, 48, 47, 310, "Serbia",
                310, 7_730, 2_844,
                List.of("SRB"), List.of());

        assertFalse(state.catalogueOk(), "one senior side has no squad");
        assertTrue(state.pyramidOk(), "but the football is all there");
        assertFalse(state.healthy(), "so the world is not whole");
        assertEquals("repair", state.nextStep(),
                "and the button is Repair world — it tops the national squads up from the clubs that "
                        + "now exist, which is why the pyramid has to be standing first");
    }

    @Test
    @DisplayName("legacy rows are a damaged catalogue")
    void legacyRowsAreNotHealthy() {
        WorldState state = new WorldState(
                48, 48, 96, 48, 48, 310, "Serbia",
                310, 7_730, 2_844,
                List.of(), List.of("DEU", "GBR"));

        assertFalse(state.catalogueOk(), "three country rows are from before the catalogue");
        assertFalse(state.healthy(), "so the world is not whole");
        assertEquals("repair", state.nextStep(), "and Repair world removes them");
    }

    @Test
    @DisplayName("a world with nothing at all still says Initialize DB")
    void nothingAtAllStillSaysInitialize() {
        WorldState state = new WorldState(
                0, 48, 0, 0, 0, 0, null,
                0, 0, 0,
                List.of(), List.of());

        assertFalse(state.healthy(), "there is no world here");
        assertEquals("initialize", state.nextStep(),
                "and the first thing to press is the one that builds one");
    }
}
