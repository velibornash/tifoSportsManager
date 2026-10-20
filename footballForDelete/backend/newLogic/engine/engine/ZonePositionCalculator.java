package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class ZonePositionCalculator {

    private static final Logger log = LoggerFactory.getLogger(ZonePositionCalculator.class);

    static final double[] X_BANDS = {8.33, 25, 41.67, 58.33, 75, 91.67};
    static final double[] Y_BANDS = {7.14, 21.43, 35.71, 50, 64.29, 78.57, 92.86};
    static final int GRID_COLS = 6;
    static final int GRID_ROWS = 7;

    private static final List<String> FORMATION_4_3_3_SLOTS = List.of(
        "GK", "DL", "DCL", "DCR", "DR", "CML", "CM", "CMR", "WL", "ST", "WR"
    );

    private final Map<String, double[]> cachedTargets = new HashMap<>();
    public int lastBallZoneX = -1;
    public int lastBallZoneY = -1;

    public void updateTargets(MatchState state, TacticRules homeTactics, TacticRules awayTactics) {
        int[] zone = ballZone(state.ball.x(), state.ball.y());
        int bx = zone[0], by = zone[1];

        if (bx == lastBallZoneX && by == lastBallZoneY && !cachedTargets.isEmpty()) {
            return;
        }

        lastBallZoneX = bx;
        lastBallZoneY = by;
        cachedTargets.clear();

        boolean homePoss = "HOME".equals(state.possessionTeam);
        boolean awayPoss = "AWAY".equals(state.possessionTeam);

        for (PlayerSnapshot snap : state.playerSnapshots) {
            String slotKey = state.playerSlotKeys.get(snap.playerId());
            String teamSide = snap.teamSide();
            boolean inPoss = teamSide.equals(state.possessionTeam);
            TacticRules tactics = "HOME".equals(teamSide) ? homeTactics : awayTactics;

            double[] target = computeTacticalTarget(snap, teamSide, inPoss, bx, by, slotKey, tactics);
            cachedTargets.put(snap.playerId() + "_" + slotKey, target);
        }
    }

    public double[] getTarget(long playerId, String slotKey) {
        double[] cached = cachedTargets.get(playerId + "_" + slotKey);
        if (cached != null) return cached;
        return new double[]{50.0, 50.0};
    }

    private double[] computeTacticalTarget(PlayerSnapshot snap, String teamSide, boolean inPoss,
                                            int ballXBand, int ballYBand, String slotKey, TacticRules tactics) {
        String anchorCellKey = anchorCellForSlot(slotKey, snap.position());
        double[] anchorTarget = anchorCellKey != null ? cellCenter(anchorCellKey, teamSide, slotKey) : null;

        if (slotKey != null && tactics != null) {
            String ballCellKey = "HOME".equals(teamSide)
                ? cellKey(ballXBand, ballYBand)
                : cellKey(GRID_COLS - 1 - ballXBand, ballYBand);

            var rule = tactics.getRule(slotKey, ballCellKey, inPoss);
            if (rule != null) {
                double[] tacticalTarget = cellCenter(rule.targetCellKey(), teamSide);
                if (anchorTarget != null && tacticalTarget != null) {
                    double targetX = lerp(anchorTarget[0], tacticalTarget[0], 0.90);
                    double targetY = lerp(anchorTarget[1], tacticalTarget[1], 0.90);
                    targetY = compactCenterBackY(slotKey, targetY);
                    return new double[]{targetX, targetY};
                }
                return tacticalTarget;
            }
        }

        return fallbackTarget(snap, teamSide, inPoss, ballXBand, ballYBand);
    }

    public static List<String> buildSlotKeys(String formation, List<Player> startingXI) {
        if (startingXI == null || startingXI.isEmpty()) return FORMATION_4_3_3_SLOTS;

        List<String> formationSlots = getFormationSlots(formation);
        if (formationSlots.size() != 11) formationSlots = FORMATION_4_3_3_SLOTS;

        List<String> result = new ArrayList<>();
        int posIdx = 0;
        for (Player p : startingXI) {
            if (posIdx < formationSlots.size()) {
                result.add(formationSlots.get(posIdx));
            } else {
                result.add("UKN_" + posIdx);
            }
            posIdx++;
        }
        return result;
    }

    private static List<String> getFormationSlots(String formation) {
        if (formation == null) return FORMATION_4_3_3_SLOTS;
        return switch (formation) {
            case "4-3-3" -> List.of("GK", "DL", "DCL", "DCR", "DR", "CML", "CM", "CMR", "WL", "ST", "WR");
            case "4-2-3-1" -> List.of("GK", "DL", "DCL", "DCR", "DR", "DML", "DMR", "AML", "AMC", "AMR", "ST");
            case "3-4-3" -> List.of("GK", "DCL", "DC", "DCR", "WL", "CML", "CMR", "WR", "STL", "ST", "STR");
            case "3-5-2" -> List.of("GK", "DCL", "DC", "DCR", "WL", "CML", "CM", "CMR", "WR", "STL", "STR");
            case "4-1-4-1" -> List.of("GK", "DL", "DCL", "DCR", "DR", "DM", "ML", "CML", "CMR", "MR", "ST");
            case "4-5-1" -> List.of("GK", "DL", "DCL", "DCR", "DR", "ML", "CML", "CM", "CMR", "MR", "ST");
            default -> FORMATION_4_3_3_SLOTS;
        };
    }

    static String anchorCellForSlot(String slotKey, Position pos) {
        if (slotKey == null) return null;
        return switch (slotKey) {
            case "GK" -> "CELL_0_2";
            case "DL" -> "CELL_1_0";
            case "DCL" -> "CELL_1_1";
            case "DC", "DCR" -> "CELL_1_2";
            case "DR" -> "CELL_1_4";
            case "DML" -> "CELL_1_1";
            case "DM", "DMR" -> "CELL_1_3";
            case "CML", "ML" -> "CELL_2_1";
            case "CM" -> "CELL_2_2";
            case "CMR", "MR" -> "CELL_2_3";
            case "AML" -> "CELL_3_1";
            case "AMC" -> "CELL_3_2";
            case "AMR" -> "CELL_3_3";
            case "WL" -> "CELL_3_0";
            case "WR" -> "CELL_3_4";
            case "ST", "STL" -> "CELL_4_1";
            case "STR" -> "CELL_4_3";
            default -> "CELL_2_2";
        };
    }

    private static double[] cellCenter(String cellKey, String teamSide) {
        return cellCenter(cellKey, teamSide, null);
    }

    static double[] cellCenter(String cellKey, String teamSide, String slotKey) {
        int[] parsed = parseCellKey(cellKey);
        int progress = parsed[0];
        int width = parsed[1];

        double x = "HOME".equals(teamSide) ? X_BANDS[progress] : X_BANDS[GRID_COLS - 1 - progress];
        double y = Y_BANDS[width];

        if (slotKey != null && slotKey.startsWith("DC") && !slotKey.equals("DC")) {
            y = compactCenterBackY(slotKey, y);
        }

        return new double[]{x, y};
    }



    private static double compactCenterBackY(String slotKey, double y) {
        if ("DCL".equals(slotKey)) return lerp(y, 50.0, 0.15);
        if ("DCR".equals(slotKey)) return lerp(y, 50.0, 0.15);
        return y;
    }

    private static double[] fallbackTarget(PlayerSnapshot snap, String teamSide, boolean inPoss,
                                            int ballXBand, int ballYBand) {
        double baseX = "HOME".equals(teamSide) ? X_BANDS[2] : X_BANDS[2];
        double baseY = Y_BANDS[2];

        if (snap.position() == Position.GK) {
            baseX = "HOME".equals(teamSide) ? 6.0 : 94.0;
            baseY = 50.0;
        } else if (snap.position() == Position.DEF) {
            baseX = "HOME".equals(teamSide) ? X_BANDS[1] : X_BANDS[3];
        } else if (snap.position() == Position.ATT || snap.position() == Position.WNG) {
            baseX = "HOME".equals(teamSide) ? X_BANDS[3] : X_BANDS[1];
        }

        return new double[]{baseX, baseY};
    }

    private static String cellKey(int progress, int width) {
        return "CELL_" + clamp(progress, 0, 6) + "_" + clamp(width, 0, 5);
    }

    private static int[] parseCellKey(String cellKey) {
        if (cellKey == null || !cellKey.startsWith("CELL_")) {
            return new int[]{3, 2};
        }
        String[] parts = cellKey.split("_");
        if (parts.length != 3) return new int[]{3, 2};
        try {
            return new int[]{clamp(Integer.parseInt(parts[1]), 0, 6), clamp(Integer.parseInt(parts[2]), 0, 5)};
        } catch (NumberFormatException e) {
            return new int[]{3, 2};
        }
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int clampToGrid(int v) { return Math.max(0, Math.min(4, v)); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    public static int[] ballZone(double bx, double by) {
        int xb = clamp((int) ((bx - 8.33) / 16.67), 0, 5);
        int yb = clamp((int) ((by - 7.14) / 14.29), 0, 6);
        return new int[]{xb, yb};
    }

    public static double zoneCenterX(int band, boolean home) {
        return home ? X_BANDS[band] : X_BANDS[GRID_COLS - 1 - band];
    }

    public static double zoneCenterY(int band) {
        return Y_BANDS[band];
    }

    public static double[] tacticalTarget(Player player, String teamSide, boolean inPossession,
                                           int ballXBand, int ballYBand,
                                           String slotKey, TacticRules tactics) {
        ZonePositionCalculator calc = new ZonePositionCalculator();
        PlayerSnapshot tempSnap = PlayerSnapshot.fromPlayer(player, teamSide,
            "HOME".equals(teamSide) ? 50.0 : 50.0, 50.0);
        return calc.computeTacticalTarget(tempSnap, teamSide, inPossession,
            ballXBand, ballYBand, slotKey, tactics);
    }
}
