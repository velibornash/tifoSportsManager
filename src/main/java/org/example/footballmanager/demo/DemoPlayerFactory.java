package org.example.footballmanager.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * DemoPlayerFactory — ODGOVORNOST: PRETVARANJE DEFINICIJA IGRACA U MODEL.
 *
 * Pretvara {@link DemoScenario.PlayerDef definicije} iz {@link DemoScenario}
 * u konkretne {@link Player} objekte sa nasumicnim skillovima (1–20)
 * prilagodjenim roli.
 */
public class DemoPlayerFactory {

    private final Random random;

    public DemoPlayerFactory() {
        this(new Random());
    }

    public DemoPlayerFactory(Random random) {
        this.random = random;
    }

    /** Kreira sve igrace iz zadatog scenarija sa nasumicnim skillovima i fizickim osobinama. */
    public List<Player> createPlayers(DemoScenario scenario) {
        List<Player> players = new ArrayList<>();
        for (DemoScenario.PlayerDef def : scenario.getPlayers()) {
            PlayerSkills skills = PlayerSkills.randomForRole(def.role(), random);
            double[] phys = randomPhysical(def.role());
            players.add(new Player(def.label(), def.label(), def.team(), def.role(),
                                   def.color(), def.position(), def.position(), skills,
                                   phys[0], phys[1]));
        }
        return players;
    }

    /**
     * Generise fizicke osobine (height cm, weight kg) po roli.
     * <pre>
     * GK: 185-200 cm, 80-100 kg
     * DEF: 180-200 cm, 75-95 kg
     * MID: 170-190 cm, 65-85 kg
     * ST:  180-200 cm, 75-95 kg
     * </pre>
     */
    private double[] randomPhysical(String role) {
        int minH, maxH, minW, maxW;
        switch (role) {
            case "GK" -> { minH = 185; maxH = 200; minW = 80; maxW = 100; }
            case "DL", "DR", "DCL", "DCR" -> { minH = 180; maxH = 200; minW = 75; maxW = 95; }
            case "STL", "STR" -> { minH = 180; maxH = 200; minW = 75; maxW = 95; }
            default -> { minH = 170; maxH = 190; minW = 65; maxW = 85; }
        }
        double height = minH + random.nextInt(maxH - minH + 1);
        double weight = minW + random.nextInt(maxW - minW + 1);
        return new double[]{height, weight};
    }
}
