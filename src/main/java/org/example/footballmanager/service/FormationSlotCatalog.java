package org.example.footballmanager.service;

import org.example.footballmanager.dto.TacticsRuleDTO;
import org.example.footballmanager.dto.TacticsSlotDTO;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FormationSlotCatalog {
    public static final String WE_HAVE_BALL = "WE_HAVE_BALL";
    public static final String OPPONENT_HAS_BALL = "OPPONENT_HAS_BALL";

    private static final List<String> SUPPORTED_TARGET_CELLS = buildTargetCells();
    private static final List<String> SUPPORTED_BALL_STATES = buildBallStates();

    private final Map<String, List<TacticsSlotDTO>> layouts = new LinkedHashMap<>();

    public FormationSlotCatalog() {
        layouts.put("4-4-2", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DCL", "DEF", "DEF", 2, "CELL_1_1"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_3"),
                slot("DR", "DEF", "DEF", 4, "CELL_1_4"),
                slot("ML", "MID", "MID", 5, "CELL_2_0"),
                slot("CML", "MID", "MID", 6, "CELL_2_1"),
                slot("CMR", "MID", "MID", 7, "CELL_2_3"),
                slot("MR", "MID", "MID", 8, "CELL_2_4"),
                slot("STL", "ATT", "ATT", 9, "CELL_4_1"),
                slot("STR", "ATT", "ATT", 10, "CELL_4_3")
        ));
        layouts.put("4-3-3", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DCL", "DEF", "DEF", 2, "CELL_1_1"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_3"),
                slot("DR", "DEF", "DEF", 4, "CELL_1_4"),
                slot("CML", "MID", "MID", 5, "CELL_2_1"),
                slot("CM", "MID", "MID", 6, "CELL_2_2"),
                slot("CMR", "MID", "MID", 7, "CELL_2_3"),
                slot("WL", "WNG", "ATT", 8, "CELL_4_0"),
                slot("ST", "ATT", "ATT", 9, "CELL_4_2"),
                slot("WR", "WNG", "ATT", 10, "CELL_4_4")
        ));
        layouts.put("4-2-3-1", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DCL", "DEF", "DEF", 2, "CELL_1_1"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_3"),
                slot("DR", "DEF", "DEF", 4, "CELL_1_4"),
                slot("DML", "MID", "MID", 5, "CELL_2_1"),
                slot("DMR", "MID", "MID", 6, "CELL_2_3"),
                slot("AML", "MID", "ATT", 7, "CELL_3_0"),
                slot("AMC", "MID", "ATT", 8, "CELL_3_2"),
                slot("AMR", "MID", "ATT", 9, "CELL_3_4"),
                slot("ST", "ATT", "ATT", 10, "CELL_4_2")
        ));
        layouts.put("4-1-4-1", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DCL", "DEF", "DEF", 2, "CELL_1_1"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_3"),
                slot("DR", "DEF", "DEF", 4, "CELL_1_4"),
                slot("DM", "MID", "MID", 5, "CELL_2_2"),
                slot("ML", "MID", "MID", 6, "CELL_3_0"),
                slot("CML", "MID", "MID", 7, "CELL_3_1"),
                slot("CMR", "MID", "MID", 8, "CELL_3_3"),
                slot("MR", "MID", "MID", 9, "CELL_3_4"),
                slot("ST", "ATT", "ATT", 10, "CELL_4_2")
        ));
        layouts.put("4-5-1", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DCL", "DEF", "DEF", 2, "CELL_1_1"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_3"),
                slot("DR", "DEF", "DEF", 4, "CELL_1_4"),
                slot("ML", "MID", "MID", 5, "CELL_3_0"),
                slot("CML", "MID", "MID", 6, "CELL_3_1"),
                slot("CM", "MID", "MID", 7, "CELL_3_2"),
                slot("CMR", "MID", "MID", 8, "CELL_3_3"),
                slot("MR", "MID", "MID", 9, "CELL_3_4"),
                slot("ST", "ATT", "ATT", 10, "CELL_4_2")
        ));
        layouts.put("3-5-2", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DCL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DC", "DEF", "DEF", 2, "CELL_1_2"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_4"),
                slot("WL", "WNG", "MID", 4, "CELL_2_0"),
                slot("CML", "MID", "MID", 5, "CELL_3_1"),
                slot("CM", "MID", "MID", 6, "CELL_3_2"),
                slot("CMR", "MID", "MID", 7, "CELL_3_3"),
                slot("WR", "WNG", "MID", 8, "CELL_2_4"),
                slot("STL", "ATT", "ATT", 9, "CELL_4_1"),
                slot("STR", "ATT", "ATT", 10, "CELL_4_3")
        ));
        layouts.put("3-4-3", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DCL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DC", "DEF", "DEF", 2, "CELL_1_2"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_4"),
                slot("ML", "MID", "MID", 4, "CELL_3_0"),
                slot("CML", "MID", "MID", 5, "CELL_3_1"),
                slot("CMR", "MID", "MID", 6, "CELL_3_3"),
                slot("MR", "MID", "MID", 7, "CELL_3_4"),
                slot("WL", "WNG", "ATT", 8, "CELL_4_0"),
                slot("ST", "ATT", "ATT", 9, "CELL_4_2"),
                slot("WR", "WNG", "ATT", 10, "CELL_4_4")
        ));
        layouts.put("3-4-2-1", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DCL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DC", "DEF", "DEF", 2, "CELL_1_2"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_4"),
                slot("WL", "WNG", "MID", 4, "CELL_2_0"),
                slot("CML", "MID", "MID", 5, "CELL_3_1"),
                slot("CMR", "MID", "MID", 6, "CELL_3_3"),
                slot("WR", "WNG", "MID", 7, "CELL_2_4"),
                slot("AML", "MID", "ATT", 8, "CELL_4_1"),
                slot("AMR", "MID", "ATT", 9, "CELL_4_3"),
                slot("ST", "ATT", "ATT", 10, "CELL_4_2")
        ));
        layouts.put("5-3-2", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DCL", "DEF", "DEF", 2, "CELL_1_1"),
                slot("DC", "DEF", "DEF", 3, "CELL_1_2"),
                slot("DCR", "DEF", "DEF", 4, "CELL_1_3"),
                slot("DR", "DEF", "DEF", 5, "CELL_1_4"),
                slot("CML", "MID", "MID", 6, "CELL_2_1"),
                slot("CM", "MID", "MID", 7, "CELL_2_2"),
                slot("CMR", "MID", "MID", 8, "CELL_2_3"),
                slot("STL", "ATT", "ATT", 9, "CELL_4_1"),
                slot("STR", "ATT", "ATT", 10, "CELL_4_3")
        ));
        layouts.put("5-4-1", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DCL", "DEF", "DEF", 2, "CELL_1_1"),
                slot("DC", "DEF", "DEF", 3, "CELL_1_2"),
                slot("DCR", "DEF", "DEF", 4, "CELL_1_3"),
                slot("DR", "DEF", "DEF", 5, "CELL_1_4"),
                slot("ML", "MID", "MID", 6, "CELL_3_0"),
                slot("CML", "MID", "MID", 7, "CELL_3_1"),
                slot("CMR", "MID", "MID", 8, "CELL_3_3"),
                slot("MR", "MID", "MID", 9, "CELL_3_4"),
                slot("ST", "ATT", "ATT", 10, "CELL_4_2")
        ));
    }

    public List<TacticsSlotDTO> getSlots(String formation) {
        List<TacticsSlotDTO> layout = layouts.getOrDefault(normalizeFormation(formation), layouts.get("4-4-2"));
        return layout.stream()
                .map(slot -> new TacticsSlotDTO(slot.getSlotKey(), slot.getLabel(), slot.getRole(), slot.getLine(), slot.getOrder(), slot.getAnchorCellKey()))
                .toList();
    }

    public List<String> getSupportedBallStates() {
        return new ArrayList<>(SUPPORTED_BALL_STATES);
    }

    public List<String> getSupportedTargetCells() {
        return new ArrayList<>(SUPPORTED_TARGET_CELLS);
    }

    public List<TacticsRuleDTO> buildDefaultRules(String formation) {
        List<TacticsSlotDTO> slots = getSlots(formation);
        List<TacticsRuleDTO> rules = new ArrayList<>();
        for (TacticsSlotDTO slot : slots) {
            for (String ballState : SUPPORTED_BALL_STATES) {
                int[] syntheticBall = syntheticBallCell(ballState);
                int[] weHaveBallTarget = defaultTarget(slot, syntheticBall[0], syntheticBall[1], true);
                int[] opponentHasBallTarget = defaultTarget(slot, syntheticBall[0], syntheticBall[1], false);
                rules.add(new TacticsRuleDTO(slot.getSlotKey(), ballState, WE_HAVE_BALL,
                        toCellKey(weHaveBallTarget[0], weHaveBallTarget[1])));
                rules.add(new TacticsRuleDTO(slot.getSlotKey(), ballState, OPPONENT_HAS_BALL,
                        toCellKey(opponentHasBallTarget[0], opponentHasBallTarget[1])));
            }
        }
        return rules;
    }

    public String normalizeFormation(String formation) {
        String normalized = formation == null ? "4-4-2" : formation.trim();
        return layouts.containsKey(normalized) ? normalized : "4-4-2";
    }

    public int[] parseCellKey(String cellKey) {
        if (cellKey == null || !cellKey.startsWith("CELL_")) {
            return new int[]{2, 2};
        }
        String[] parts = cellKey.split("_");
        if (parts.length != 3) {
            return new int[]{2, 2};
        }
        try {
            return new int[]{clamp(Integer.parseInt(parts[1])), clamp(Integer.parseInt(parts[2]))};
        } catch (NumberFormatException ex) {
            return new int[]{2, 2};
        }
    }

    public String toCellKey(int progressBand, int widthBand) {
        return "CELL_" + clamp(progressBand) + "_" + clamp(widthBand);
    }

    private int[] defaultTarget(TacticsSlotDTO slot, int ballProgress, int ballWidth, boolean weHaveBall) {
        int[] anchor = parseCellKey(slot.getAnchorCellKey());
        int progressShift = switch (slot.getLine()) {
            case "GK" -> weHaveBall ? 0 : 0;
            case "DEF" -> weHaveBall ? 1 : -1;
            case "MID" -> weHaveBall ? 1 : 0;
            case "ATT" -> weHaveBall ? 0 : -1;
            default -> 0;
        };
        int widthPull = switch (slot.getRole()) {
            case "WNG" -> 1;
            case "DEF" -> 1;
            default -> 0;
        };

        int progress = clamp(Math.round(anchor[0] + progressShift + (ballProgress - anchor[0]) * (weHaveBall ? 0.25f : 0.18f)));
        int width = clamp(Math.round(anchor[1] + (ballWidth - anchor[1]) * (weHaveBall ? 0.20f : 0.35f)));

        if ("WNG".equals(slot.getRole())) {
            width = anchor[1] <= 1 ? Math.min(width, 1 + widthPull) : Math.max(width, 3 - widthPull);
        }
        if ("GK".equals(slot.getRole())) {
            progress = weHaveBall ? 0 : 0;
            width = 2 + (ballWidth < 2 ? -1 : ballWidth > 2 ? 1 : 0);
        }
        if ("DEF".equals(slot.getLine())) {
            progress = Math.max(Math.max(0, anchor[0] - 1), Math.min(progress, anchor[0] + (weHaveBall ? 1 : 0)));
        }
        if ("MID".equals(slot.getLine())) {
            progress = Math.max(Math.max(1, anchor[0] - 1), Math.min(progress, anchor[0] + (weHaveBall ? 1 : 0)));
        }
        if (!weHaveBall && "ATT".equals(slot.getLine())) {
            progress = Math.max(Math.max(2, anchor[0] - 1), progress);
        }
        return new int[]{clamp(progress), clamp(width)};
    }

    private int[] syntheticBallCell(String ballState) {
        return switch (String.valueOf(ballState)) {
            case "ATTACK_LEFT_CORNER" -> new int[]{4, 0};
            case "ATTACK_RIGHT_CORNER" -> new int[]{4, 4};
            case "DEFEND_LEFT_CORNER" -> new int[]{0, 0};
            case "DEFEND_RIGHT_CORNER" -> new int[]{0, 4};
            default -> parseCellKey(ballState);
        };
    }

    private static TacticsSlotDTO slot(String key, String role, String line, int order, String anchorCellKey) {
        return new TacticsSlotDTO(key, key, role, line, order, anchorCellKey);
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(4, value));
    }

    private static List<String> buildTargetCells() {
        List<String> cells = new ArrayList<>();
        for (int progress = 0; progress < 5; progress++) {
            for (int width = 0; width < 5; width++) {
                cells.add("CELL_" + progress + "_" + width);
            }
        }
        return Collections.unmodifiableList(cells);
    }

    private static List<String> buildBallStates() {
        List<String> cells = new ArrayList<>(SUPPORTED_TARGET_CELLS);
        cells.add("ATTACK_LEFT_CORNER");
        cells.add("ATTACK_RIGHT_CORNER");
        cells.add("DEFEND_LEFT_CORNER");
        cells.add("DEFEND_RIGHT_CORNER");
        return Collections.unmodifiableList(cells);
    }
}