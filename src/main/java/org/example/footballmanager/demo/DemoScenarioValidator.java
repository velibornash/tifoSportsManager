package org.example.footballmanager.demo;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DemoScenarioValidator — ODGOVORNOST: VALIDACIJA DEMO KONFIGURACIJE.
 *
 * Proverava konzistentnost koordinatnog sistema (grid) i igraca kreiranih iz
 * {@link DemoScenario}. Pravila, poruke i rezultati su IDENTICNI kao ranije u
 * {@link TacticalGridDemo} — samo su izdvojeni iz UI sloja. Ne uvodi nove
 * provere i ne menja postojece ponasanje.
 */
public class DemoScenarioValidator {

    /**
     * Validacija koordinatnog sistema. Vraća true ako su sve provere prošle.
     */
    public static boolean validateGrid() {
        boolean ok = true;
        StringBuilder report = new StringBuilder();
        report.append("=== TacticalGridDemo validation ===\n");

        int totalCells = DemoScenario.GRID_ROWS * DemoScenario.GRID_COLS;
        int pitchCells = 0;
        int outsideCells = 0;
        for (int row = 0; row < DemoScenario.GRID_ROWS; row++) {
            for (int col = 0; col < DemoScenario.GRID_COLS; col++) {
                if (DemoScenario.isPitchCell(row, col)) {
                    pitchCells++;
                } else {
                    outsideCells++;
                }
            }
        }

        report.append("grid rows   = ").append(DemoScenario.GRID_ROWS).append(" (expect 9)\n");
        report.append("grid cols   = ").append(DemoScenario.GRID_COLS).append(" (expect 8)\n");
        report.append("total cells = ").append(totalCells).append(" (expect 72)\n");
        report.append("pitch cells = ").append(pitchCells).append(" (expect 42 = 7x6)\n");
        report.append("outside     = ").append(outsideCells).append(" (expect 30)\n");

        ok &= check(report, DemoScenario.GRID_ROWS == 9, "exactly 9 rows");
        ok &= check(report, DemoScenario.GRID_COLS == 8, "exactly 8 columns");
        ok &= check(report, pitchCells == 42, "exactly 42 pitch cells");
        ok &= check(report, outsideCells == 30, "exactly 30 outside cells");

        int[][] corners = {{1, 1}, {1, 6}, {7, 1}, {7, 6}};
        for (int[] c : corners) {
            ok &= check(report, DemoScenario.isPitchCell(c[0], c[1]),
                "corner " + c[0] + "," + c[1] + " is pitch");
        }

        boolean borderOutside = true;
        for (int col = 0; col < DemoScenario.GRID_COLS; col++) {
            borderOutside &= !DemoScenario.isPitchCell(0, col);
            borderOutside &= !DemoScenario.isPitchCell(8, col);
        }
        for (int row = 0; row < DemoScenario.GRID_ROWS; row++) {
            borderOutside &= !DemoScenario.isPitchCell(row, 0);
            borderOutside &= !DemoScenario.isPitchCell(row, 7);
        }
        ok &= check(report, borderOutside, "row 0, row 8, col 0, col 7 are all outside");

        ok &= check(report, DemoScenario.screenRow(1) == 7, "row 1 maps to bottom screen row (7)");
        ok &= check(report, DemoScenario.screenRow(7) == 1, "row 7 maps to top screen row (1)");
        ok &= check(report, DemoScenario.screenCol(1) == 1 && DemoScenario.screenCol(6) == 6,
            "columns increase left to right");

        report.append(ok ? "\nVALIDATION: PASS" : "\nVALIDATION: FAIL");
        System.out.println(report);
        return ok;
    }

    /**
     * Validacija igraca. Vraća true ako su sve provere prošle.
     */
    public static boolean validatePlayers(List<Player> players) {
        boolean ok = true;
        StringBuilder report = new StringBuilder();
        report.append("=== TacticalGridDemo player validation ===\n");

        ok &= check(report, players.size() == 22, "exactly 22 players");

        Map<String, Position> expected = new LinkedHashMap<>();
        expected.put("HGK", new Position(1, 3.5));
        expected.put("AGK", new Position(7, 3.5));
        expected.put("HDL", new Position(2, 2));
        expected.put("HDCL", new Position(2, 3));
        expected.put("HDCR", new Position(2, 4));
        expected.put("HDR", new Position(2, 5));
        expected.put("ADL", new Position(6, 2));
        expected.put("ADCL", new Position(6, 3));
        expected.put("ADCR", new Position(6, 4));
        expected.put("ADR", new Position(6, 5));
        expected.put("HML", new Position(3, 1));
        expected.put("HCML", new Position(3, 3));
        expected.put("HCMR", new Position(3, 4));
        expected.put("HMR", new Position(3, 6));
        expected.put("AML", new Position(5, 1));
        expected.put("ACML", new Position(5, 3));
        expected.put("ACMR", new Position(5, 4));
        expected.put("AMR", new Position(5, 6));
        expected.put("ASTL", new Position(4.5, 2));
        expected.put("ASTR", new Position(4.5, 5));
        expected.put("HSTL", new Position(4, 3.075));
        expected.put("HSTR", new Position(4, 4));

        Set<String> seenLabels = new java.util.HashSet<>();
        for (Player player : players) {
            if (player == null) {
                continue;
            }
            seenLabels.add(player.getLabel());

            ok &= check(report, player.getId() != null && !player.getId().isBlank(),
                player.getLabel() + " has id");
            ok &= check(report, player.getLabel() != null && !player.getLabel().isBlank(),
                player.getLabel() + " has label");
            ok &= check(report, player.getTeam() != null, player.getLabel() + " has team");
            ok &= check(report, player.getRole() != null, player.getLabel() + " has role");
            ok &= check(report, player.getColor() != null, player.getLabel() + " has color");
            ok &= check(report, player.getPosition() != null, player.getLabel() + " has position");
            ok &= check(report, player.getAlternativePosition() != null,
                player.getLabel() + " has alternativePosition");

            Position exp = expected.get(player.getLabel());
            ok &= check(report, exp != null, player.getLabel() + " is a known player");
            if (exp != null) {
                ok &= check(report, positionsEqual(exp, player.getPosition()),
                    player.getLabel() + " position matches");
            }

            String expectedTeam = player.getLabel().startsWith("H") ? DemoScenario.TEAM_HOME : DemoScenario.TEAM_AWAY;
            ok &= check(report, expectedTeam.equals(player.getTeam()),
                player.getLabel() + " team = " + expectedTeam);

            String expectedRole = player.getLabel().substring(1);
            ok &= check(report, expectedRole.equals(player.getRole()),
                player.getLabel() + " role = " + expectedRole);

            Color expectedColor = "GK".equals(expectedRole)
                ? DemoScenario.COLOR_GOALKEEPER
                : ("H".equals(player.getLabel().substring(0, 1)) ? DemoScenario.COLOR_HOME : DemoScenario.COLOR_AWAY);
            ok &= check(report, expectedColor.equals(player.getColor()),
                player.getLabel() + " color = " + expectedColor);
        }

        for (String label : expected.keySet()) {
            ok &= check(report, seenLabels.contains(label), label + " exists in players");
        }

        report.append(ok ? "\nVALIDATION: PASS" : "\nVALIDATION: FAIL");
        System.out.println(report);
        return ok;
    }

    private static boolean check(StringBuilder report, boolean condition, String label) {
        report.append("  [").append(condition ? "OK" : "FAIL").append("] ").append(label).append("\n");
        return condition;
    }

    private static boolean positionsEqual(Position a, Position b) {
        return Math.abs(a.getRow() - b.getRow()) < 1e-6
            && Math.abs(a.getColumn() - b.getColumn()) < 1e-6;
    }
}
