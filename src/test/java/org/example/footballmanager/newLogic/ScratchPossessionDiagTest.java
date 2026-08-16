package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.*;

public class ScratchPossessionDiagTest {

    @Test
    void possessionBreakdown() {
        MatchStore store = new MatchStore();
        MatchOrchestrator orch = new MatchOrchestrator(store);
        long id = orch.startMatch("Crvena Zvezda", "Partizan");
        MatchResult r = orch.simulate(id);

        // Per-team carrier ticks, phase-agnostic
        long homeTicks = 0, awayTicks = 0, noneTicks = 0;
        int homeKickoffs = 0, awayKickoffs = 0;
        int homePickups = 0, awayPickups = 0;
        int homeGoals = 0, awayGoals = 0;
        int homeFouls = 0, awayFouls = 0;
        int homeThrowIns = 0, awayThrowIns = 0;
        int homeCorners = 0, awayCorners = 0;
        int homeGK = 0, awayGK = 0;

        List<MatchEvent> ev = r.events();
        for (MatchEvent e : ev) {
            switch (e) {
                case GoalEvent g -> { if ("HOME".equals(g.teamSide())) homeGoals++; else awayGoals++; }
                case FoulEvent f -> { if ("HOME".equals(f.teamSide())) homeFouls++; else awayFouls++; }
                case SetPieceEvent sp -> {
                    if (sp.setPieceType() == SetPieceEvent.SetPieceType.THROW_IN) {
                        if ("HOME".equals(sp.teamSide())) homeThrowIns++; else awayThrowIns++;
                    } else if (sp.setPieceType() == SetPieceEvent.SetPieceType.CORNER) {
                        if ("HOME".equals(sp.teamSide())) homeCorners++; else awayCorners++;
                    }
                }
                case GkSaveEvent g -> { if ("HOME".equals(g.teamSide())) homeGK++; else awayGK++; }
                default -> { }
            }
        }

        Long prevCarrier = null;
        String prevSide = null;
        int kickoffSeq = 0;
        for (TickSnapshot ts : r.tickHistory()) {
            Long c = ts.carrierId();
            if (c == null) {
                noneTicks++;
                prevCarrier = null;
                prevSide = null;
                kickoffSeq = 0;
                continue;
            }
            PlayerSnapshot snap = ts.players().stream().filter(p -> p.playerId() == c).findFirst().orElse(null);
            String side = snap != null ? snap.teamSide() : null;
            if ("HOME".equals(side)) homeTicks++;
            else if ("AWAY".equals(side)) awayTicks++;

            // Loose-ball pickup detection: previous tick had no carrier
            if (prevCarrier == null) {
                if ("HOME".equals(side)) homePickups++;
                else if ("AWAY".equals(side)) awayPickups++;
            }
            prevCarrier = c;
            prevSide = side;
        }

        System.out.println("=== POSSESSION DIAG ===");
        System.out.printf("Home carrier ticks: %d (%.1f%%) | Away: %d (%.1f%%) | None: %d%n",
            homeTicks, 100.0 * homeTicks / Math.max(1, homeTicks + awayTicks),
            awayTicks, 100.0 * awayTicks / Math.max(1, homeTicks + awayTicks), noneTicks);
        System.out.printf("Loose pickups: home=%d away=%d%n", homePickups, awayPickups);
        System.out.printf("Goals home=%d away=%d | Fouls home=%d away=%d%n", homeGoals, awayGoals, homeFouls, awayFouls);
        System.out.printf("Throw-ins home=%d away=%d | Corners home=%d away=%d%n", homeThrowIns, awayThrowIns, homeCorners, awayCorners);
        System.out.printf("GK saves home=%d away=%d%n", homeGK, awayGK);
    }
}
