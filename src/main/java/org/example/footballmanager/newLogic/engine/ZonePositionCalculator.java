package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;

import java.util.*;

public final class ZonePositionCalculator {

    static final double[] X_BANDS = {10, 30, 50, 70, 90};
    static final double[] Y_BANDS = {10, 26, 50, 74, 90};
    static final int GRID = 5;
    private static final double PRIMARY_CELL_WEIGHT = 0.90;
    private static final double BALL_BIAS_WITH_POSSESSION = 0.015;
    private static final double BALL_BIAS_WITHOUT_POSSESSION = 0.005;

    private static final List<String> FORMATION_4_3_3_SLOTS = List.of(
        "GK", "DL", "DCL", "DCR", "DR", "CML", "CM", "CMR", "WL", "WR", "ST"
    );

    private static final Map<Position, List<String>> POSITION_TO_SLOTS = new LinkedHashMap<>();
    static {
        POSITION_TO_SLOTS.put(Position.GK, List.of("GK"));
        POSITION_TO_SLOTS.put(Position.DEF, List.of("DL", "DCL", "DCR", "DR"));
        POSITION_TO_SLOTS.put(Position.MID, List.of("CML", "CM", "CMR", "ML", "CMR", "MR"));
        POSITION_TO_SLOTS.put(Position.ATT, List.of("ST", "STL", "STR"));
        POSITION_TO_SLOTS.put(Position.WNG, List.of("WL", "WR"));
    }

    private ZonePositionCalculator() {}

    public static int[] ballZone(double bx, double by) {
        int xb = clamp((int) ((bx - 4) / 18.4), 0, 4);
        int yb = clamp((int) ((by - 4) / 18.4), 0, 4);
        return new int[]{xb, yb};
    }

    public static double zoneCenterX(int band, boolean home) {
        return home ? X_BANDS[band] : X_BANDS[GRID - 1 - band];
    }

    public static double zoneCenterY(int band) {
        return Y_BANDS[band];
    }

    public static String cellKey(int progress, int width) {
        return "CELL_" + clamp(progress) + "_" + clamp(width);
    }

    public static int[] parseCellKey(String cellKey) {
        if (cellKey == null || !cellKey.startsWith("CELL_")) {
            return new int[]{2, 2};
        }
        String[] parts = cellKey.split("_");
        if (parts.length != 3) return new int[]{2, 2};
        try {
            return new int[]{clamp(Integer.parseInt(parts[1])), clamp(Integer.parseInt(parts[2]))};
        } catch (NumberFormatException e) {
            return new int[]{2, 2};
        }
    }

    /**
     * Compute tactical target using formation slot keys and tactic rules.
     * If no rules or slot keys available, falls back to CSPosition-based heuristic.
     */
    public static double[] tacticalTarget(Player player, String teamSide, boolean inPossession,
                                          int ballXBand, int ballYBand,
                                          String slotKey, TacticRules tactics) {
        String anchorCellKey = anchorCellForSlot(slotKey, player != null ? player.position() : null);
        double[] anchorTarget = anchorCellKey != null ? cellCenter(anchorCellKey, teamSide, slotKey) : null;

        if (slotKey != null && tactics != null) {
            // Mirror ball zone for AWAY: progress bands are inverted
            String ballCellKey = "HOME".equals(teamSide)
                ? cellKey(ballXBand, ballYBand)
                : cellKey(GRID - 1 - ballXBand, ballYBand);

            var rule = tactics.getRule(slotKey, ballCellKey, inPossession);
            if (rule != null) {
                double[] tacticalTarget = cellCenter(rule.targetCellKey(), teamSide);
                if (anchorTarget != null && tacticalTarget != null) {
                    double targetX = lerp(anchorTarget[0], tacticalTarget[0], PRIMARY_CELL_WEIGHT);
                    double targetY = lerp(anchorTarget[1], tacticalTarget[1], PRIMARY_CELL_WEIGHT);
                    targetY = compactCenterBackY(slotKey, targetY);
                    double ballBias = inPossession ? BALL_BIAS_WITH_POSSESSION : BALL_BIAS_WITHOUT_POSSESSION;
                    double[] ballTarget = new double[]{zoneCenterX(ballXBand, "HOME".equals(teamSide)), zoneCenterY(ballYBand)};
                    targetX = lerp(targetX, ballTarget[0], ballBias);
                    targetY = lerp(targetY, ballTarget[1], ballBias);
                    return new double[]{targetX, targetY};
                }
                return tacticalTarget;
            }
        }

        return fallbackTarget(player, teamSide, inPossession, ballXBand, ballYBand);
    }

    /**
     * Build slot key list for a team based on formation.
     * Maps starting XI order (GK, DEFxN, MIDxN, ATTxN) to formation-specific slot keys.
     */
    public static List<String> buildSlotKeys(String formation, List<Player> startingXI) {
        if (startingXI == null || startingXI.isEmpty()) return FORMATION_4_3_3_SLOTS;

        List<String> formationSlots = getFormationSlots(formation);
        if (formationSlots.size() != 11) formationSlots = FORMATION_4_3_3_SLOTS;

        // The formationSlots are in standard formation order (GK, then DEF, MID, ATT).
        // startingXI is also in that order. Verify by matching positions.
        // Since generation order matches: GK, DEFxN, MIDxN, ATTxN, we can just assign
        // formationSlots directly in order.

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
            case "4-3-3" -> List.of("GK", "DL", "DCL", "DCR", "DR", "CML", "CM", "CMR", "WL", "WR", "ST");
            case "4-2-3-1" -> List.of("GK", "DL", "DCL", "DCR", "DR", "DML", "DMR", "AML", "AMC", "AMR", "ST");
            case "3-4-3" -> List.of("GK", "DCL", "DC", "DCR", "WL", "CML", "CMR", "WR", "STL", "ST", "STR");
            case "3-5-2" -> List.of("GK", "DCL", "DC", "DCR", "WL", "CML", "CM", "CMR", "WR", "STL", "STR");
            case "4-1-4-1" -> List.of("GK", "DL", "DCL", "DCR", "DR", "DM", "ML", "CML", "CMR", "MR", "ST");
            case "4-5-1" -> List.of("GK", "DL", "DCL", "DCR", "DR", "ML", "CML", "CM", "CMR", "MR", "ST");
            default -> FORMATION_4_3_3_SLOTS;
        };
    }

    /** Kickoff / anchor point for a formation slot (matches tactics editor cells). */
    public static double[] anchorCenterForSlot(String slotKey, String teamSide) {
        String cellKey = anchorCellForSlot(slotKey, null);
        if (cellKey == null) return new double[]{50.0, 50.0};
        return cellCenter(cellKey, teamSide, slotKey);
    }

    private static double[] cellCenter(String cellKey, String teamSide) {
        return cellCenter(cellKey, teamSide, null);
    }

    private static double[] cellCenter(String cellKey, String teamSide, String slotKey) {
        if (cellKey == null) return null;
        int[] cell = parseCellKey(cellKey);
        double x = zoneCenterX(cell[0], "HOME".equals(teamSide));
        double y = zoneCenterY(cell[1]);
        if ("DCL".equals(slotKey)) y = 41.0;
        else if ("DCR".equals(slotKey)) y = 59.0;
        return new double[]{x, y};
    }

    // ── Legacy 5-param fallback ────────────────────────────────

    public static double[] tacticalTarget(Player player, String teamSide, boolean inPossession,
                                          int ballXBand, int ballYBand) {
        return fallbackTarget(player, teamSide, inPossession, ballXBand, ballYBand);
    }

    private static double[] fallbackTarget(Player player, String teamSide, boolean inPossession,
                                           int ballXBand, int ballYBand) {
        int homeBand = switch (player.position()) {
            case GK -> 0;
            case DEF -> 0;
            case MID -> 1;
            case WNG -> 2;
            case ATT -> 3;
        };
        int lane = switch (player.position()) {
            case GK -> 2;
            case DEF -> (int)(player.id() % 4);
            case MID -> 1 + (int)(player.id() % 3);
            case WNG -> (player.id() % 2 == 0) ? 0 : 4;
            case ATT -> (player.id() % 2 == 0) ? 1 : 3;
        };

        double targetX = zoneCenterX(homeBand, "HOME".equals(teamSide));
        double targetY = zoneCenterY(lane);

        double shift = inPossession ? 0.07 : 0.03;
        double shiftX = (zoneCenterX(ballXBand, "HOME".equals(teamSide)) - targetX) * shift;
        double shiftY = (zoneCenterY(ballYBand) - targetY) * shift;

        targetX += shiftX;
        targetY += shiftY;

        if (player.position() == Position.GK) {
            targetX = "HOME".equals(teamSide) ? 8.0 : 92.0;
            targetY = 50.0;
        }

        return new double[]{targetX, targetY};
    }

    private static String anchorCellForSlot(String slotKey, Position position) {
        if (slotKey == null) {
            return defaultAnchorForPosition(position);
        }
        return switch (slotKey) {
            case "GK" -> "CELL_0_2";
            case "DL" -> "CELL_1_0";
            case "DCL" -> "CELL_1_1";
            case "DC" -> "CELL_1_2";
            case "DCR" -> "CELL_1_3";
            case "DR" -> "CELL_1_4";
            case "DML" -> "CELL_2_1";
            case "DMR" -> "CELL_2_3";
            case "DM" -> "CELL_2_2";
            case "ML" -> "CELL_2_0";
            case "CML" -> "CELL_2_1";
            case "CM" -> "CELL_2_2";
            case "CMR" -> "CELL_2_3";
            case "MR" -> "CELL_2_4";
            case "WL" -> "CELL_4_0";
            case "WR" -> "CELL_4_4";
            case "AML" -> "CELL_3_0";
            case "AMC" -> "CELL_3_2";
            case "AMR" -> "CELL_3_4";
            case "STL" -> "CELL_4_1";
            case "ST" -> "CELL_4_2";
            case "STR" -> "CELL_4_3";
            default -> defaultAnchorForPosition(position);
        };
    }

    private static String defaultAnchorForPosition(Position position) {
        if (position == null) return null;
        return switch (position) {
            case GK -> "CELL_0_2";
            case DEF -> "CELL_1_2";
            case MID -> "CELL_2_2";
            case WNG -> "CELL_3_2";
            case ATT -> "CELL_4_2";
        };
    }

    private static double compactCenterBackY(String slotKey, double targetY) {
        if ("DCL".equals(slotKey)) return lerp(targetY, 41.0, 0.45);
        if ("DCR".equals(slotKey)) return lerp(targetY, 59.0, 0.45);
        return targetY;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(4, v));
    }
}
