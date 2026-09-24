package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.tactics.FormationSlotCatalog;
import org.example.footballmanager.newLogic.sim.tactics.TacticalPerspectiveTransformer;
import org.example.footballmanager.newLogic.sim.tactics.TacticsSlotDTO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Maps a real DB lineup onto the sim engine's 4-4-2 slot structure. The 11
 * ordered starting players keep their real names and skills but are re-slotted
 * into the engine's fixed GK/DL/DCL/DCR/DR/ML/CML/CMR/MR/STL/STR grid by their
 * DB position (GK/DEF/MID/WNG/ATT). Formation-aware slotting is a later phase —
 * the engine itself stays 4-4-2.
 */
public final class RealSquadFactory {

    private static final String[] SLOT_ORDER = {
            "GK", "DL", "DCL", "DCR", "DR", "ML", "CML", "CMR", "MR", "STL", "STR"
    };

    private RealSquadFactory() {}

    /**
     * Builds a sim squad (11 players) from the team's saved lineup template.
     * Returns {@code null} when the lineup is absent or has fewer than 11
     * starters — the caller then falls back to the synthetic squad.
     */
    public static List<Player> buildSquad(Lineup lineup, String team) {
        if (lineup == null) return null;
        List<org.example.footballmanager.newLogic.model.Player> ordered =
                lineup.getOrderedStartingPlayers();
        if (ordered == null || ordered.size() < 11) return null;
        List<org.example.footballmanager.newLogic.model.Player> starters =
                new ArrayList<>(ordered.subList(0, 11));

        int[] slotPlayer = assignSlots(starters);

        List<Player> squad = new ArrayList<>(11);
        for (int s = 0; s < SLOT_ORDER.length; s++) {
            String role = SLOT_ORDER[s];
            org.example.footballmanager.newLogic.model.Player db = starters.get(slotPlayer[s]);
            Position anchor = anchorForRole(role, team);
            squad.add(new Player(
                    String.valueOf(db.getId()),
                    db.getName(),
                    team,
                    role,
                    anchor,
                    anchor,
                    toSimSkills(db.getSkills()),
                    heightCm(db.getHeight())));
        }
        return squad;
    }

    /** Deterministic 11-player -> 11-slot assignment: position match first,
     *  then any remaining starter in lineup order. */
    private static int[] assignSlots(List<org.example.footballmanager.newLogic.model.Player> starters) {
        int n = SLOT_ORDER.length;
        int[] slotPlayer = new int[n];
        boolean[] used = new boolean[n];
        Arrays.fill(slotPlayer, -1);

        for (int s = 0; s < n; s++) {
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                if (prefersSlot(starters.get(i).getPosition(), SLOT_ORDER[s])) {
                    slotPlayer[s] = i;
                    used[i] = true;
                    break;
                }
            }
        }
        int next = 0;
        for (int s = 0; s < n; s++) {
            if (slotPlayer[s] != -1) continue;
            while (next < n && used[next]) next++;
            if (next >= n) break;
            slotPlayer[s] = next;
            used[next] = true;
            next++;
        }
        return slotPlayer;
    }

    private static boolean prefersSlot(org.example.footballmanager.newLogic.model.Position pos, String slot) {
        if (pos == null) return false;
        return switch (pos) {
            case GK -> "GK".equals(slot);
            case DEF -> slot.startsWith("D");
            case MID -> slot.startsWith("M") || slot.startsWith("C");
            case WNG -> "ML".equals(slot) || "MR".equals(slot)
                    || "STL".equals(slot) || "STR".equals(slot);
            case ATT -> "STL".equals(slot) || "STR".equals(slot);
        };
    }

    private static Position anchorForRole(String role, String team) {
        for (TacticsSlotDTO slot : new FormationSlotCatalog().getSlots("4-4-2")) {
            if (!slot.getSlotKey().equals(role)) continue;
            String[] parts = slot.getAnchorCellKey().split("_");
            double row = Double.parseDouble(parts[1]) + 1.5;
            double col = Double.parseDouble(parts[2]) + 1.5;
            Position home = new Position(row, col);
            return TacticalPerspectiveTransformer.toPhysical(home, team);
        }
        return new Position(team.equals("HOME") ? 3.5 : 4.5, 3.5);
    }

    private static PlayerSkills toSimSkills(Skills s) {
        if (s == null) return PlayerSkills.neutral();
        return new PlayerSkills(
                clamp1(s.getExact(SkillName.PACE)),
                clamp1(s.getExact(SkillName.STAMINA)),
                clamp1(s.getExact(SkillName.GOALKEEPER)),
                clamp1(s.getExact(SkillName.TECHNIQUE)),
                clamp1(s.getExact(SkillName.PLAYMAKER)),
                clamp1(s.getExact(SkillName.PASSING)),
                clamp1(s.getExact(SkillName.STRIKER)),
                clamp1(s.getExact(SkillName.DEFENDER)));
    }

    private static double clamp1(double v) {
        return Math.max(1.0, Math.min(20.0, v));
    }

    private static double heightCm(double meters) {
        double cm = meters * 100.0;
        return cm <= 0 ? 180.0 : cm;
    }
}