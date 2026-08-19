package org.example.footballmanager.demo;

import org.example.footballmanager.demo.swingUIDemo.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression testovi za FINALNI ARHITEKTURNI REFAKTOR.
 *
 * Stite izdvojene komponente kompozicionog korena:
 *  - {@link DemoPlayerFactory} pravi igrace IDENTICNE definicijama scenarija
 *  - {@link DemoSimulationFactory} sklapa engine sa istim igracima/loptom
 *  - extension point-ovi ({@link PlayerSkills}, {@link MovementProfile},
 *    {@link ActionCandidate}, {@link PlayerSelectionEngine#selectBestCandidate})
 *    su INERTNI — ne menjaju ponasanje.
 */
class DemoArchitectureTest {

    @Test
    void playerFactoryMatchesScenarioDefinitionsExactly() {
        DemoScenario scenario = DemoScenario.standard();
        List<Player> players = new DemoPlayerFactory().createPlayers(scenario);

        assertEquals(scenario.getPlayers().size(), players.size(),
            "broj igraca mora odgovarati definicijama scenarija");
        for (int i = 0; i < players.size(); i++) {
            DemoScenario.PlayerDef def = scenario.getPlayers().get(i);
            Player p = players.get(i);
            assertEquals(def.label(), p.getLabel(), "label[" + i + "]");
            assertEquals(def.label(), p.getId(), "id[" + i + "]");
            assertEquals(def.team(), p.getTeam(), "team[" + i + "]");
            assertEquals(def.role(), p.getRole(), "role[" + i + "]");
            assertEquals(def.color(), p.getColor(), "color[" + i + "]");
            assertEquals(def.position().getRow(), p.getPosition().getRow(), 1e-9, "row[" + i + "]");
            assertEquals(def.position().getColumn(), p.getPosition().getColumn(), 1e-9, "col[" + i + "]");
            assertEquals(p.getPosition().getRow(), p.getAlternativePosition().getRow(), 1e-9, "altRow[" + i + "]");
            assertEquals(p.getPosition().getColumn(), p.getAlternativePosition().getColumn(), 1e-9, "altCol[" + i + "]");
        }
    }

    @Test
    void playerFactoryCreatesFreshInstancesPerCall() {
        List<Player> a = new DemoPlayerFactory().createPlayers(DemoScenario.standard());
        List<Player> b = new DemoPlayerFactory().createPlayers(DemoScenario.standard());
        assertNotSame(a, b);
        assertNotSame(a.get(0), b.get(0), "svaki poziv mora praviti NOVE Player objekte");
    }

    @Test
    void simulationFactoryAssemblesEngineWithSamePlayersAndBall() {
        DemoScenario scenario = DemoScenario.standard();
        SimulationEngine engine = new DemoSimulationFactory()
            .create(scenario, TacticsRules.defaults());

        List<Player> expected = new DemoPlayerFactory().createPlayers(scenario);
        assertEquals(expected.size(), engine.getPlayers().size());
        for (int i = 0; i < expected.size(); i++) {
            assertNotSame(expected.get(i), engine.getPlayers().get(i));
            assertEquals(expected.get(i).getLabel(), engine.getPlayers().get(i).getLabel());
        }
        assertEquals(scenario.getBallStartPosition().getRow(),
            engine.getBall().getPosition().getRow(), 1e-9, "ball row");
        assertEquals(scenario.getBallStartPosition().getColumn(),
            engine.getBall().getPosition().getColumn(), 1e-9, "ball column");
        assertEquals(scenario.getBallStartPosition().getRow(),
            engine.getBall().getInitialPosition().getRow(), 1e-9, "ball initial row");
    }

    @Test
    void playerSkillsAreNeutralPlaceholders() {
        PlayerSkills skills = PlayerSkills.neutral();
        assertEquals(10, skills.pace());
        assertEquals(10, skills.stamina());
        assertEquals(10, skills.keeper());
        assertEquals(10, skills.technique());
        assertEquals(10, skills.playmaking());
        assertEquals(10, skills.passing());
        assertEquals(10, skills.striker());
        assertEquals(10, skills.defender());

        Player p = new Player("x", "x", "HOME", "GK", java.awt.Color.YELLOW,
            new Position(1, 3.5), new Position(1, 3.5));
        assertEquals(PlayerSkills.neutral(), p.getSkills(), "igrac default ima neutralne skills");
    }

    @Test
    void movementProfileReflectsCurrentConstant() {
        MovementProfile standard = MovementProfile.standard();
        assertEquals(MovementEngine.PLAYER_SPEED, standard.maxSpeed(), 1e-12,
            "standardni profil mora ogolavati trenutnu konstantu brzine");
        assertEquals(0.0, standard.acceleration(), 1e-12);
        assertEquals(1.0, standard.movementCapability(), 1e-12);

        List<Player> players = new DemoPlayerFactory().createPlayers(DemoScenario.standard());
        SimulationState state = new SimulationState(players,
            new Ball(new Position(4, 3.5), new Position(4, 3.5)), TacticsRules.defaults(), new Random(1));
        MovementEngine movement = new MovementEngine(state);
        // profileFor je extension point i ne sme da menja stvarno kretanje.
        assertEquals(standard, movement.profileFor(players.get(0)),
            "profileFor vraca standardni profil bez uticaja na ponasanje");
    }

    @Test
    void selectionExtensionPointBehavesLikeClosest() {
        List<Player> players = new DemoPlayerFactory().createPlayers(DemoScenario.standard());
        Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));
        PlayerSelectionEngine selection = new PlayerSelectionEngine(
            new SimulationState(players, ball, TacticsRules.defaults(), new Random(1)));
        Position ballPos = new Position(4, 3.5);
        assertEquals(selection.closestHomeTo(ballPos).getLabel(),
            selection.selectBestCandidate(ballPos).getLabel(),
            "selectBestCandidate se za sada ponasa kao closestHomeTo");
    }

    @Test
    void actionCandidateIsNeutralPlaceholder() {
        Player p = new Player("x", "x", "HOME", "GK", java.awt.Color.YELLOW,
            new Position(1, 3.5), new Position(1, 3.5));
        ActionCandidate candidate = ActionCandidate.neutral(Action.Type.CARRY, p);
        assertEquals(Action.Type.CARRY, candidate.type());
        assertEquals(p, candidate.actingPlayer());
        assertEquals(0.0, candidate.suitability(), 1e-12);
    }

    @Test
    void scenarioPlayerCountAndBallStartAreStable() {
        DemoScenario scenario = DemoScenario.standard();
        assertEquals(22, scenario.getPlayers().size());
        assertEquals(4.0, scenario.getBallStartPosition().getRow(), 1e-9);
        assertEquals(3.5, scenario.getBallStartPosition().getColumn(), 1e-9);
        assertTrue(scenario.getPlayers().get(0).label().equals("HGK"));
        assertTrue(scenario.getPlayers().get(21).label().equals("HSTR"));
    }
}
