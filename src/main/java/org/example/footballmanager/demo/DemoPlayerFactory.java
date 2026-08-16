package org.example.footballmanager.demo;

import java.util.ArrayList;
import java.util.List;

/**
 * DemoPlayerFactory — ODGOVORNOST: PRETVARANJE DEFINICIJA IGRACA U MODEL.
 *
 * Pretvara {@link DemoScenario.PlayerDef definicije} iz {@link DemoScenario}
 * u konkretne {@link Player} objekte. Sama kreacija igraca (id, label, ekipa,
 * rola, boja, position, alternativePosition) identicna je kao ranije u
 * {@link TacticalGridDemo} — nista se ne menja u rezultujucim igracima.
 *
 * alternativePosition se inicijalizuje na istu poziciju kao position (samo
 * podatkovno polje, bez logike). Redosled igraca = redosled definicija u
 * scenariju = redosled crtanja.
 */
public class DemoPlayerFactory {

    /** Kreira sve igrace iz zadatog scenarija. Svaki poziv pravi NOVE objekte. */
    public List<Player> createPlayers(DemoScenario scenario) {
        List<Player> players = new ArrayList<>();
        for (DemoScenario.PlayerDef def : scenario.getPlayers()) {
            players.add(new Player(def.label(), def.label(), def.team(), def.role(),
                                   def.color(), def.position(), def.position()));
        }
        return players;
    }
}
