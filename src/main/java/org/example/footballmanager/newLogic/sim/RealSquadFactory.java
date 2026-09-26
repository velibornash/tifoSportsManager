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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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
     * starters.
     */
    public static List<Player> buildSquad(Lineup lineup, String team) {
        return buildSquad(lineup, team, null);
    }

    /**
     * Builds the starting eleven plus a bench (Sprint 1.8).
     *
     * <p>The bench is returned separately rather than appended to the starting XI, because the
     * engine keeps benched players out of the live player list entirely. Until now a squad was
     * exactly eleven and the rest of the squad list was thrown away, so a red card left a team
     * with ten for the rest of the match and fatigue had no consequence.
     *
     * @param benchOut if non-null, receives the reserve players (positioned off the pitch)
     */
    public static List<Player> buildSquad(Lineup lineup, String team, List<Player> benchOut) {
        if (lineup == null) return null;
        List<org.example.footballmanager.newLogic.model.Player> ordered =
                lineup.getOrderedStartingPlayers();
        if (ordered == null || ordered.size() < 11) return null;
        List<org.example.footballmanager.newLogic.model.Player> starters =
                new ArrayList<>(ordered.subList(0, 11));
        List<Player> xi = buildFromStarters(starters, team);
        if (benchOut != null && ordered.size() > 11) {
            benchOut.addAll(buildBench(ordered.subList(11, ordered.size()), team));
        }
        return xi;
    }

    /**
     * Builds reserve players. They carry their real name and skills so a substitute is a real
     * player with a real MOTM chance, but they are marked on-bench and never enter the live list.
     */
    private static List<Player> buildBench(
            List<org.example.footballmanager.newLogic.model.Player> reserves, String team) {
        List<Player> bench = new ArrayList<>();
        // A matchday squad is 18 in the league game: seven reserves is a usable bench.
        int limit = Math.min(reserves.size(), 7);
        for (int i = 0; i < limit; i++) {
            org.example.footballmanager.newLogic.model.Player db = reserves.get(i);
            if (db == null || db.getId() == null) continue;
            String role = simRoleFor(db.getPosition());
            Player p = new Player(
                    String.valueOf(db.getId()),
                    db.getName(),
                    team,
                    role,
                    new Position(0, 0),
                    new Position(0, 0),
                    toSimSkills(db.getSkills()),
                    heightCm(db.getHeight()));
            p.setOnBench(true);
            bench.add(p);
        }
        return bench;
    }

    /** DB position -> the engine's role vocabulary. */
    private static String simRoleFor(org.example.footballmanager.newLogic.model.Position p) {
        return switch (p == null ? org.example.footballmanager.newLogic.model.Position.MID : p) {
            case GK -> "GK";
            case DEF -> "DCL";
            case WNG -> "ML";
            case ATT -> "STL";
            case MID -> "CMR";
        };
    }

    /**
     * Builds a sim squad (11 players) directly from the team's real DB players
     * when NO lineup template exists: sort by position, keep a single GK, take
     * the best 11. Mirrors MatchOrchestrator.loadTeamFromDB's fallback so real
     * DB ids/names flow through the sim and into MatchPlayerStats/MOTM even for
     * teams that never saved a tactical lineup. Returns {@code null} when fewer
     * than 11 valid players exist — the caller then falls back to synthetic.
     */
    public static List<Player> buildSquadFromPlayers(
            List<org.example.footballmanager.newLogic.model.Player> players, String team) {
        if (players == null) return null;
        List<org.example.footballmanager.newLogic.model.Player> valid = players.stream()
                .filter(p -> p != null && p.getId() != null && p.getName() != null)
                .sorted(Comparator.comparingInt(p -> positionOrder(p.getPosition())))
                .collect(Collectors.toList());
        if (valid.size() < 11) return null;
        ensureSingleGK(valid);
        return buildFromStarters(new ArrayList<>(valid.subList(0, 11)), team);
    }

    /** Maps an ordered list of exactly-11 DB starters onto the slot grid. */
    private static List<Player> buildFromStarters(
            List<org.example.footballmanager.newLogic.model.Player> starters, String team) {
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

    private static int positionOrder(org.example.footballmanager.newLogic.model.Position p) {
        if (p == null) return 9;
        return switch (p) {
            case GK -> 0;
            case DEF -> 1;
            case MID -> 2;
            case WNG -> 3;
            case ATT -> 4;
        };
    }

    /** Ensure at most one GK among the first 11; swap extras out (match
     *  the MatchOrchestrator fallback behaviour). */
    private static void ensureSingleGK(List<org.example.footballmanager.newLogic.model.Player> sorted) {
        boolean haveGK = sorted.stream()
                .limit(11L)
                .anyMatch(p -> p.getPosition() == org.example.footballmanager.newLogic.model.Position.GK);
        if (!haveGK) {
            for (int i = 11; i < sorted.size(); i++) {
                if (sorted.get(i).getPosition() == org.example.footballmanager.newLogic.model.Position.GK) {
                    Collections.swap(sorted, 0, i);
                    break;
                }
            }
        } else {
            for (int i = 1; i < 11; i++) {
                if (sorted.get(i).getPosition() == org.example.footballmanager.newLogic.model.Position.GK) {
                    for (int j = sorted.size() - 1; j >= 11; j--) {
                        if (sorted.get(j).getPosition() != org.example.footballmanager.newLogic.model.Position.GK) {
                            Collections.swap(sorted, i, j);
                            break;
                        }
                    }
                }
            }
        }
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