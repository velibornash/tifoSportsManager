package org.example.footballmanager.demo;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.List;

/**
 * TacticalGridDemo — KOMPOZICIONI KOREN / UL AZNA TACKA demoa.
 *
 * Ne poseduje igrace, simulaciju, renderersku niti validacionu logiku — sve je
 * izdvojeno u odgovorne komponente, a ovde se samo SKLAPA:
 *
 * <pre>
 *   TacticalGridDemo
 *       ├── DemoScenario          → "Kakav scenario izvodimo?"
 *       ├── DemoPlayerFactory     → definicije → {@link Player} objekti
 *       ├── DemoSimulationFactory → sklapa {@link SimulationEngine}
 *       └── DemoUI                → Swing prikaz + interakcija (preko API-ja engina)
 * </pre>
 *
 * Statičke delegate {@link #createPlayers()}, {@link #validateGrid()} i
 * {@link #validatePlayers(List)} zadržane su radi kompatibilnosti sa testovima
 * i CLI validacijom — telo im je u odgovornim klasama.
 */
public class TacticalGridDemo {

    /** Kreira sve igrace standardnog scenarija (delegira u DemoPlayerFactory). */
    public static List<Player> createPlayers() {
        return new DemoPlayerFactory().createPlayers(DemoScenario.standard());
    }

    /** Validacija grid-a (delegira u DemoScenarioValidator). */
    public static boolean validateGrid() {
        return DemoScenarioValidator.validateGrid();
    }

    /** Validacija igraca (delegira u DemoScenarioValidator). */
    public static boolean validatePlayers(List<Player> players) {
        return DemoScenarioValidator.validatePlayers(players);
    }

    public static void main(String[] args) {
        boolean valid = DemoScenarioValidator.validateGrid();
        valid &= DemoScenarioValidator.validatePlayers(createPlayers());

        boolean validateOnly = args.length > 0 && "--validate-only".equals(args[0]);
        if (validateOnly || GraphicsEnvironment.isHeadless()) {
            System.exit(valid ? 0 : 1);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            DemoScenario scenario = DemoScenario.standard();
            SimulationEngine simulation = new DemoSimulationFactory().create(scenario);
            new DemoUI(simulation);
        });
    }
}
