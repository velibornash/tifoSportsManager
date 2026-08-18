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

    /** Kreira sve igrace iz zadatog scenarija sa nasumicnim skillovima. */
    public List<Player> createPlayers(DemoScenario scenario) {
        List<Player> players = new ArrayList<>();
        for (DemoScenario.PlayerDef def : scenario.getPlayers()) {
            PlayerSkills skills = PlayerSkills.randomForRole(def.role(), random);
            players.add(new Player(def.label(), def.label(), def.team(), def.role(),
                                   def.color(), def.position(), def.position(), skills));
        }
        return players;
    }
}
