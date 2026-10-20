package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.dto.TacticsRuleDTO;
import org.example.footballmanager.newLogic.dto.TacticsSlotDTO;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
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
                slot("CML", "MID", "MID", 5, "CELL_2_0"),
                slot("CM", "MID", "MID", 6, "CELL_2_2"),
                slot("CMR", "MID", "MID", 7, "CELL_2_3"),
                slot("AML", "WNG", "ATT", 8, "CELL_4_0"),
                slot("AMC", "ATT", "ATT", 9, "CELL_4_2"),
                slot("AMR", "WNG", "ATT", 10, "CELL_4_4")
        ));
        layouts.put("4-1-4-1", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DCL", "DEF", "DEF", 2, "CELL_1_1"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_3"),
                slot("DR", "DEF", "DEF", 4, "CELL_1_4"),
                slot("DM", "MID", "MID", 5, "CELL_2_2"),
                slot("ML", "MID", "MID", 6, "CELL_2_0"),
                slot("CML", "MID", "MID", 7, "CELL_2_1"),
                slot("CMR", "MID", "MID", 8, "CELL_2_3"),
                slot("MR", "MID", "MID", 9, "CELL_2_4"),
                slot("ST", "ATT", "ATT", 10, "CELL_4_2")
        ));
        layouts.put("3-5-2", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DCL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DC", "DEF", "DEF", 2, "CELL_1_2"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_4"),
                slot("ML", "MID", "MID", 4, "CELL_2_0"),
                slot("CML", "MID", "MID", 5, "CELL_2_1"),
                slot("CM", "MID", "MID", 6, "CELL_2_2"),
                slot("CMR", "MID", "MID", 7, "CELL_2_3"),
                slot("MR", "MID", "MID", 8, "CELL_2_4"),
                slot("STL", "ATT", "ATT", 9, "CELL_4_1"),
                slot("STR", "ATT", "ATT", 10, "CELL_4_3")
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
        layouts.put("3-4-3", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DCL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DC", "DEF", "DEF", 2, "CELL_1_2"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_4"),
                slot("ML", "MID", "MID", 4, "CELL_2_0"),
                slot("CML", "MID", "MID", 5, "CELL_2_2"),
                slot("CMR", "MID", "MID", 6, "CELL_2_2"),
                slot("MR", "MID", "MID", 7, "CELL_2_4"),
                slot("WL", "WNG", "ATT", 8, "CELL_4_0"),
                slot("ST", "ATT", "ATT", 9, "CELL_4_2"),
                slot("WR", "WNG", "ATT", 10, "CELL_4_4")
        ));
        layouts.put("4-5-1", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DCL", "DEF", "DEF", 2, "CELL_1_1"),
                slot("DCR", "DEF", "DEF", 3, "CELL_1_3"),
                slot("DR", "DEF", "DEF", 4, "CELL_1_4"),
                slot("ML", "MID", "MID", 5, "CELL_2_0"),
                slot("CML", "MID", "MID", 6, "CELL_2_1"),
                slot("CM", "MID", "MID", 7, "CELL_2_2"),
                slot("CMR", "MID", "MID", 8, "CELL_2_3"),
                slot("MR", "MID", "MID", 9, "CELL_2_4"),
                slot("ST", "ATT", "ATT", 10, "CELL_4_2")
        ));
        layouts.put("5-4-1", List.of(
                slot("GK", "GK", "GK", 0, "CELL_0_2"),
                slot("DL", "DEF", "DEF", 1, "CELL_1_0"),
                slot("DCL", "DEF", "DEF", 2, "CELL_1_1"),
                slot("DC", "DEF", "DEF", 3, "CELL_1_2"),
                slot("DCR", "DEF", "DEF", 4, "CELL_1_3"),
                slot("DR", "DEF", "DEF", 5, "CELL_1_4"),
                slot("ML", "MID", "MID", 6, "CELL_2_0"),
                slot("CML", "MID", "MID", 7, "CELL_2_1"),
                slot("CMR", "MID", "MID", 8, "CELL_2_3"),
                slot("MR", "MID", "MID", 9, "CELL_2_4"),
                slot("ST", "ATT", "ATT", 10, "CELL_4_2")
        ));
    }

    private static TacticsSlotDTO slot(String key, String label, String role, int order, String cell) {
        String line = switch (key.charAt(0)) {
            case 'G' -> "GK";
            case 'D' -> "DEF";
            case 'M' -> "MID";
            case 'A' -> "ATT";
            case 'W' -> "WNG";
            case 'S' -> "ATT";
            default -> "MID";
        };
        return new TacticsSlotDTO(key, label, role, line, order, cell);
    }

    private static List<String> buildTargetCells() {
        List<String> cells = new ArrayList<>();
        for (int r = 0; r < 7; r++) for (int c = 0; c < 6; c++) cells.add("CELL_" + r + "_" + c);
        return Collections.unmodifiableList(cells);
    }

    private static List<String> buildBallStates() {
        List<String> states = new ArrayList<>();
        for (int r = 0; r < 7; r++) for (int c = 0; c < 6; c++) states.add("CELL_" + r + "_" + c);
        states.add("ATTACK_LEFT_CORNER");
        states.add("ATTACK_RIGHT_CORNER");
        states.add("DEFEND_LEFT_CORNER");
        states.add("DEFEND_RIGHT_CORNER");
        return Collections.unmodifiableList(states);
    }

    public String normalizeFormation(String formation) {
        if (formation == null) return "4-4-2";
        String f = formation.trim().replaceAll("\\s+", "");
        if (layouts.containsKey(f)) return f;
        return "4-4-2";
    }

    public List<TacticsSlotDTO> getSlots(String formation) {
        return layouts.getOrDefault(normalizeFormation(formation), layouts.get("4-4-2"));
    }

    public List<TacticsRuleDTO> buildDefaultRules(String formation) {
        List<TacticsSlotDTO> slots = getSlots(formation);
        List<TacticsRuleDTO> rules = new ArrayList<>();
        for (TacticsSlotDTO slot : slots) {
            for (String ballState : SUPPORTED_BALL_STATES) {
                rules.add(new TacticsRuleDTO(slot.getSlotKey(), ballState, WE_HAVE_BALL, slot.getAnchorCellKey()));
                rules.add(new TacticsRuleDTO(slot.getSlotKey(), ballState, OPPONENT_HAS_BALL, slot.getAnchorCellKey()));
            }
        }
        return rules;
    }

    public List<String> getSupportedBallStates() {
        return SUPPORTED_BALL_STATES;
    }

    public List<String> getSupportedTargetCells() {
        return SUPPORTED_TARGET_CELLS;
    }
}
