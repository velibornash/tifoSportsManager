package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 1.8 — substitutions.
 *
 * <p>Until this existed a match squad was exactly eleven, the rest of the squad list was discarded,
 * and {@code Player.substituted} was never set. A red card left a team with ten for the rest of
 * the match, a tiring player could not be replaced, and fatigue had no consequence because there
 * was nothing to substitute into.
 */
class SubstitutionServiceTest {

    private MatchState state;
    private SubstitutionService service;

    private static Player onPitch(String id, String team, String role, double row, double col) {
        return new Player(id, id, team, role,
                new Position(row, col), new Position(row, col), PlayerSkills.neutral(), 180);
    }

    private static Player benched(String id, String team, String role) {
        return benched(id, team, role, PlayerSkills.neutral());
    }

    private static Player benched(String id, String team, String role, PlayerSkills skills) {
        Player p = new Player(id, id, team, role,
                new Position(0, 0), new Position(0, 0), skills, 180);
        p.setOnBench(true);
        return p;
    }

    @BeforeEach
    void setUp() {
        state = new MatchState();
        // A realistic second-half situation so discretionary subs are legal.
        for (int i = 0; i < 2000; i++) state.advanceTick();
        service = new SubstitutionService(state, null, null);
    }

    @Test
    @DisplayName("a benched player is not in the live player list until he comes on")
    void benchIsOutsideTheLiveSimulation() {
        Player on = onPitch("H1", "HOME", "STL", 6.0, 3.0);
        Player bench = benched("H7", "HOME", "STL");
        state.getPlayers().add(on);
        state.getBench("HOME").add(bench);

        assertEquals(1, state.getPlayers().size(), "the bench must be invisible to the running sim");
        assertEquals(1, state.getBench("HOME").size());
        assertTrue(state.getBench("HOME").contains(bench));
    }

    @Test
    @DisplayName("a substitution puts the incoming player in the outgoing player's place")
    void substitutionTransfersPosition() {
        Player on = onPitch("H1", "HOME", "STL", 6.0, 3.0);
        Player bench = benched("H7", "HOME", "STL");
        state.getPlayers().add(on);
        state.getBench("HOME").add(bench);

        assertTrue(service.substitute(on, bench, false));

        assertFalse(bench.isOnBench(), "he is on the pitch now");
        assertTrue(state.getPlayers().contains(bench), "he must join the live list");
        assertFalse(state.getBench("HOME").contains(bench), "and leave the bench");
        assertTrue(on.isSubstituted(), "the outgoing player is substituted");
        assertEquals(6.0, bench.getPosition().getRow(), 1e-9, "he inherits the position");
        assertEquals(3.0, bench.getPosition().getColumn(), 1e-9);
        assertTrue(on.isUnavailable(), "a substituted player is unavailable");
    }

    @Test
    @DisplayName("minutes played are measured from the moment he came on")
    void minutesPlayedIsRecorded() {
        Player on = onPitch("H1", "HOME", "STL", 6.0, 3.0);
        Player bench = benched("H7", "HOME", "STL");
        state.getPlayers().add(on);
        state.getBench("HOME").add(bench);

        service.substitute(on, bench, false);

        assertEquals(2000, state.getCameOnTick("H7"));
        assertEquals(2000, state.getWentOffTick("H1"));
        assertEquals(40, state.minutesOnPitch(bench, 3600),
                "on at 50 minutes with 40 minutes left, so 40 minutes played");
        assertEquals(50, state.minutesOnPitch(on, 3600), "50 minutes at 40 ticks per minute");
    }

    @Test
    @DisplayName("a red card forces a replacement and costs no substitution")
    void redCardReplacementIsForcedAndFree() {
        Player sent = onPitch("H1", "HOME", "DCL", 5.0, 2.5);
        Player spare = benched("H7", "HOME", "DCL");
        state.getPlayers().add(sent);
        state.getBench("HOME").add(spare);
        sent.setSentOff(true);

        assertTrue(service.substitute(sent, spare, true), "a forced change must always be allowed");

        assertEquals(0, state.getSubsUsed("HOME"), "a red card does not consume one of the five");
        assertTrue(state.getPlayers().contains(spare));
    }

    @Test
    @DisplayName("the five-substitution limit is enforced")
    void substitutionLimitEnforced() {
        for (int i = 0; i < MatchState.MAX_SUBSTITUTIONS; i++) {
            state.addSubUsed("HOME");
        }
        Player on = onPitch("H1", "HOME", "STL", 6.0, 3.0);
        Player bench = benched("H7", "HOME", "STL");
        state.getPlayers().add(on);
        state.getBench("HOME").add(bench);

        assertFalse(service.substitute(on, bench, false), "a sixth discretionary sub is not allowed");
        assertTrue(service.substitute(on, bench, true), "but a forced one still is");
    }

    @Test
    @DisplayName("a player from the other team can never be brought on")
    void cannotSubAcrossTeams() {
        Player on = onPitch("H1", "HOME", "STL", 6.0, 3.0);
        Player bench = benched("A7", "AWAY", "STL");
        state.getPlayers().add(on);
        state.getBench("AWAY").add(bench);

        assertFalse(service.substitute(on, bench, false));
    }

    @Test
    @DisplayName("somebody who is not on the bench cannot be substituted in")
    void cannotBringOnAPlayerNotOnTheBench() {
        Player on = onPitch("H1", "HOME", "STL", 6.0, 3.0);
        Player notOnBench = onPitch("H9", "HOME", "STL", 4.0, 3.0);
        state.getPlayers().add(on);
        state.getPlayers().add(notOnBench);

        assertFalse(service.substitute(on, notOnBench, false),
                "a player already on the pitch is not a substitute");
    }

    @Test
    @DisplayName("a substitute must be able to fill the role he is replacing")
    void replacementMatchesRole() {
        // No DCL on the bench, so a centre-back going off must not bring on a striker.
        Player cb = onPitch("H1", "HOME", "DCL", 5.0, 2.5);
        // Both reserves are neutral-rated by default, which gives the selector no signal at all.
        // Give the winger real technique so "best available cover" has something to work with.
        Player striker = benched("H7", "HOME", "STL",
                new PlayerSkills(10, 10, 1, 6, 6, 6, 16, 4));
        Player winger = benched("H8", "HOME", "ML",
                new PlayerSkills(12, 11, 1, 15, 9, 11, 6, 7));
        state.getPlayers().add(cb);
        state.getBench("HOME").add(striker);
        state.getBench("HOME").add(winger);
        cb.setSentOff(true);

        service.onTick();

        assertTrue(state.getPlayers().contains(winger), "the winger can cover, the striker cannot");
        assertFalse(state.getPlayers().contains(striker));
    }

    @Test
    @DisplayName("onTick replaces a sent-off player automatically")
    void onTickHandlesRedCard() {
        Player sent = onPitch("H1", "HOME", "DCL", 5.0, 2.5);
        Player spare = benched("H7", "HOME", "DCL");
        state.getPlayers().add(sent);
        state.getBench("HOME").add(spare);
        sent.setSentOff(true);

        service.onTick();

        assertTrue(state.getPlayers().contains(spare), "the team must not be left with ten");
        assertTrue(sent.isSubstituted());
    }

    @Test
    @DisplayName("a full bench means nothing happens rather than a crash")
    void emptyBenchIsSafe() {
        Player sent = onPitch("H1", "HOME", "DCL", 5.0, 2.5);
        state.getPlayers().add(sent);
        sent.setSentOff(true);

        service.onTick();   // must not throw

        assertEquals(1, state.getPlayers().size());
    }

    @Test
    @DisplayName("the goalkeeper is never swapped automatically for fatigue")
    void keeperIsNotAutoSubbed() {
        Player gk = onPitch("H1", "HOME", "GK", 7.5, 4.0);
        Player outfield = onPitch("H2", "HOME", "CMR", 4.5, 4.0);
        Player spareGk = benched("H7", "HOME", "GK");
        Player spareCmr = benched("H8", "HOME", "CMR");
        state.getPlayers().add(gk);
        state.getPlayers().add(outfield);
        state.getBench("HOME").add(spareGk);
        state.getBench("HOME").add(spareCmr);
        gk.setFatigue(0.99);
        outfield.setFatigue(0.95);

        service.onTick();

        assertFalse(gk.isSubstituted(), "the keeper stays on");
        assertTrue(outfield.isSubstituted(), "the tired outfield player comes off");
    }
}
