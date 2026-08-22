package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.model.TeamSide;
import org.example.footballmanager.demo.service.tactics.TacticalPerspectiveTransformer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TacticalPerspectiveTransformer — verifies AWAY mirroring
 * (8-row, 7-col) and round-trip correctness.
 *
 * Fix #5: AWAY initial positions use TacticalPerspectiveTransformer.toPhysical.
 */
class TestTacticalPerspectiveTransformer {

    @Test
    void homePerspectiveIsIdentity() {
        Position p = new Position(4, 3.5);
        Position phys = TacticalPerspectiveTransformer.toPhysical(p, TeamSide.HOME);
        assertEquals(4.0, phys.getRow(), 1e-9);
        assertEquals(3.5, phys.getColumn(), 1e-9);
    }

    @Test
    void awayPerspectiveMirrorsBothAxes() {
        // A 4-4-2 tactical slot for GK at home-perspective (4, 3) 
        // should map to (4, 4) in physical space for AWAY (8-4=4, 7-3=4)
        Position homeGK = new Position(4, 3);
        Position awayGK = TacticalPerspectiveTransformer.toPhysical(homeGK, TeamSide.AWAY);
        assertEquals(4.0, awayGK.getRow(), 1e-9);
        assertEquals(4.0, awayGK.getColumn(), 1e-9);
    }

    @Test
    void awayPerspectiveMirrorsStricker() {
        // HOME STR tactical slot (6, 2) → AWAY STR physical (2, 5)
        Position homeSTR = new Position(6, 2);
        Position awaySTR = TacticalPerspectiveTransformer.toPhysical(homeSTR, TeamSide.AWAY);
        assertEquals(2.0, awaySTR.getRow(), 1e-9);
        assertEquals(5.0, awaySTR.getColumn(), 1e-9);
    }

    @Test
    void roundTripPreservesPosition() {
        Position original = new Position(3.5, 4.5);
        Position physical = TacticalPerspectiveTransformer.toPhysical(original, TeamSide.AWAY);
        Position back = TacticalPerspectiveTransformer.toHomePerspective(physical, TeamSide.AWAY);
        assertEquals(original.getRow(), back.getRow(), 1e-9);
        assertEquals(original.getColumn(), back.getColumn(), 1e-9);
    }

    @Test
    void stringToTeamPerspective() {
        Position home = new Position(6, 2);
        Position away = TacticalPerspectiveTransformer.toPhysical(home, "AWAY");
        assertEquals(2.0, away.getRow(), 1e-9);
        assertEquals(5.0, away.getColumn(), 1e-9);
    }
}
