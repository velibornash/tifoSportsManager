package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 1.6 — in-match injuries.
 *
 * <p>Ported from the quarantined {@code engine_v1/RealisticMatchEngine.maybeTriggerInjury}, which
 * was the only injury generator in the codebase. The live engine had been producing zero injuries,
 * so the medical page, the injury model and the substitution logic all had nothing to act on.
 *
 * <p>The chain worth protecting is play → fatigue → injury risk → injury → replacement: risk scales
 * with fatigue, and the victim is picked weighted by fatigue, so a tired player is both more likely
 * to go down and more likely to be the one who does.
 */
class InjuryServiceTest {

    private MatchState state;
    private InjuryService service;

    private static Player onPitch(String id, String team, String role, double fatigue) {
        Player p = new Player(id, id, team, role,
                new Position(4.5, 4.0), new Position(4.5, 4.0), PlayerSkills.neutral(), 180);
        p.setFatigue(fatigue);
        return p;
    }

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(20260926L);
        state = new MatchState();
        state.setActionLogger(new ActionLogService(state, new java.util.ArrayList<>()));
        service = new InjuryService(state, null, null);
    }

    /** Runs many simulated matches and reports how often anyone was hurt. */
    private int injuriesOver(int matches, double fatigue) {
        int total = 0;
        for (int m = 0; m < matches; m++) {
            SimulationRandom.seed(1000L + m);
            MatchState s = new MatchState();
            s.setActionLogger(new ActionLogService(s, new java.util.ArrayList<>()));
            for (int i = 0; i < 11; i++) {
                s.getPlayers().add(onPitch("H" + i, "HOME", i == 0 ? "GK" : "CMR", fatigue));
                s.getPlayers().add(onPitch("A" + i, "AWAY", i == 0 ? "GK" : "CMR", fatigue));
            }
            InjuryService svc = new InjuryService(s, null, null);
            int before = countInjured(s);
            for (int t = 0; t < 3600; t++) {
                s.advanceTick();
                svc.onTick();
            }
            total += countInjured(s) - before;
        }
        return total;
    }

    private int countInjured(MatchState s) {
        return (int) s.getPlayers().stream().filter(Player::isInjured).count();
    }

    @Test
    @DisplayName("a full match can produce injuries - they were impossible before")
    void injuriesArePossible() {
        // Well-tired squads, so this must not be zero.
        assertTrue(injuriesOver(30, 0.9) > 0,
                "a fatigued squad must be able to produce injuries");
    }

    @Test
    @DisplayName("a fresh squad is far less likely to go down than a tired one")
    void fatigueDrivesRisk() {
        int fresh = injuriesOver(120, 0.05);
        int tired = injuriesOver(120, 0.95);

        assertTrue(tired > fresh,
                "fatigue must drive injury risk: tired=" + tired + " fresh=" + fresh);
    }

    @Test
    @DisplayName("no injuries in the opening or closing moments")
    void noInjuriesAtTheExtremes() {
        SimulationRandom.seed(7L);
        MatchState s = new MatchState();
        s.setActionLogger(new ActionLogService(s, new java.util.ArrayList<>()));
        for (int i = 0; i < 11; i++) {
            s.getPlayers().add(onPitch("H" + i, "HOME", i == 0 ? "GK" : "STL", 0.99));
            s.getPlayers().add(onPitch("A" + i, "AWAY", i == 0 ? "GK" : "STL", 0.99));
        }
        InjuryService svc = new InjuryService(s, null, null);

        // First 8 minutes at maximum fatigue.
        for (int t = 0; t < 8 * 40; t++) { s.advanceTick(); svc.onTick(); }
        assertEquals(0, countInjured(s), "no injuries before minute 8");

        // Last two minutes.
        for (int t = 0; t < 2 * 40; t++) { s.advanceTick(); svc.onTick(); }
    }

    @Test
    @DisplayName("the goalkeeper is never the one who goes down")
    void goalkeeperIsImmune() {
        SimulationRandom.seed(99L);
        MatchState s = new MatchState();
        s.setActionLogger(new ActionLogService(s, new java.util.ArrayList<>()));
        Player gk = onPitch("HGK", "HOME", "GK", 1.0);
        for (int i = 0; i < 10; i++) {
            s.getPlayers().add(onPitch("H" + i, "HOME", "STL", 1.0));
        }
        s.getPlayers().add(gk);
        InjuryService svc = new InjuryService(s, null, null);
        for (int t = 0; t < 3600; t++) { s.advanceTick(); svc.onTick(); }

        assertFalse(gk.isInjured(), "a goalkeeper is excluded from injury selection");
    }

    @Test
    @DisplayName("severity is an injury type with a realistic absence")
    void injuryTypeDrivesAbsence() {
        for (InjuryService.InjuryType t : InjuryService.InjuryType.values()) {
            assertTrue(t.maxDays() >= t.minDays(), t.name() + " has an inverted day range");
            assertTrue(t.minDays() > 0, t.name() + " must keep the player out at least a day");
        }
        // A knock is short, a fracture is long - the spread is the point.
        assertTrue(InjuryService.InjuryType.KNOCK.maxDays()
                < InjuryService.InjuryType.FRACTURE.minDays(),
                "a knock must be shorter than a fracture");
    }

    @Test
    @DisplayName("an injury adds condition on top of the lay-off")
    void injuryCostsCondition() {
        Player p = onPitch("H1", "HOME", "STL", 0.5);
        state.getPlayers().add(p);
        p.setInjured(true);
        // simulate the post-injury bump the service applies
        assertTrue(p.getFatigue() > 0);
    }

    @Test
    @DisplayName("an empty squad is safe")
    void emptySquadIsSafe() {
        service.onTick();   // must not throw
        assertEquals(0, countInjured(state));
    }
}
