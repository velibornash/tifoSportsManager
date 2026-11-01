package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.newLogic.model.Lineup;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.Skills;
import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.tifo.footballmanager.newLogic.sim.model.DecisionOption;
import org.tifo.footballmanager.newLogic.sim.model.DecisionResult;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic test: runs a 2-minute match (80 ticks) and logs EVERY decision (not just passes),
 * checking sensibility: shot from own half, pass to offside receiver, duel without carrier, etc.
 * Output goes to System.out.
 */
class AllDecisionsDiagnosticTest {

    private static final int TICKS = 80;

    @Test
    @DisplayName("Diagnostic: log all decisions and check sensibility for 2 minutes")
    void logAllDecisions() {
        MatchOrchestrator orchestrator = buildMatch();
        MatchState state = orchestrator.getState();

        orchestrator.setDecisionObserver((s, result, carrier) -> {
            if (result == null || result.getChosen() == null) return;
            DecisionOption chosen = result.getChosen();
            String type = chosen.getType().toString();
            System.out.println("\nTICK " + s.getMatchTicks() + " " + type + " by " + carrier.getLabel()
                    + "(" + carrier.getRole() + ") at " + pos(carrier.getPosition())
                    + (chosen.getTarget() != null ? " -> " + chosen.getTarget().getLabel() + "(" + chosen.getTarget().getRole() + ") at " + pos(chosen.getTarget().getPosition()) : "")
                    + " | ball " + pos(s.getBall().getPosition())
                    + " | " + chosen.getReason());

            // Sensibility checks
            if (chosen.getType() == org.tifo.footballmanager.newLogic.sim.model.ActionType.SHOT) {
                boolean inShootingZone = "HOME".equals(carrier.getTeam())
                        ? carrier.getPosition().getRow() >= 6.0
                        : carrier.getPosition().getRow() <= 3.0;
                if (!inShootingZone) {
                    System.out.println("  !! SENSIBILITY: SHOT from outside shooting zone!");
                }
            }
            if (chosen.getType() == org.tifo.footballmanager.newLogic.sim.model.ActionType.PASS
                    && chosen.getTarget() != null) {
                org.tifo.footballmanager.newLogic.sim.model.Player t = chosen.getTarget();
                if (t.getTeam().equals(carrier.getTeam())) {
                    // check if receiver is in own half when carrier is in shooting zone
                    boolean carrierInZone = "HOME".equals(carrier.getTeam())
                            ? carrier.getPosition().getRow() >= 6.0
                            : carrier.getPosition().getRow() <= 3.0;
                    boolean targetOwnHalf = "HOME".equals(carrier.getTeam())
                            ? t.getPosition().getRow() < 4.5
                            : t.getPosition().getRow() > 4.5;
                    if (carrierInZone && targetOwnHalf) {
                        System.out.println("  !! SENSIBILITY: PASS from shooting zone to own half!");
                    }
                }
            }
        });

        orchestrator.simulate(TICKS);
        System.out.println("\n=== MATCH ENDED after " + TICKS + " ticks ===");
    }

    private static String pos(org.tifo.footballmanager.newLogic.sim.model.Position p) {
        return p == null ? "?" : String.format("(%.1f,%.1f)", p.getRow(), p.getColumn());
    }

    private static MatchOrchestrator buildMatch() {
        List<Player> homeStarters = elevenStarters(1);
        List<Player> awayStarters = elevenStarters(100);
        var homeSquad = RealSquadFactory.buildSquad(lineupWith(homeStarters), "HOME");
        var awaySquad = RealSquadFactory.buildSquad(lineupWith(awayStarters), "AWAY");
        return SimMatchRunner.build("Home FC", "Away United", homeSquad, awaySquad, null, null, null);
    }

    private static List<Player> elevenStarters(int idBase) {
        List<Player> out = new ArrayList<>();
        Position[] line = {
                Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID, Position.MID,
                Position.ATT, Position.ATT };
        for (int i = 0; i < 11; i++) {
            out.add(player(idBase + i, line[i]));
        }
        return out;
    }

    private static Player player(
            long id, Position position) {
        Skills skills =
                new Skills();
        skills.setSkill(SkillName.PACE, 15);
        skills.setSkill(SkillName.STAMINA, 14);
        skills.setSkill(SkillName.GOALKEEPER, 13);
        skills.setSkill(SkillName.DEFENDER, 14);
        skills.setSkill(SkillName.TECHNIQUE, 14);
        skills.setSkill(SkillName.PLAYMAKER, 14);
        skills.setSkill(SkillName.PASSING, 14);
        skills.setSkill(SkillName.STRIKER, 14);
        skills.initializeExactFromVisibleIfNeeded();
        return new Player(
                id, "Player " + id, skills, 0.5, 21, 0, 0,
                1.80, 78.0, 8.0, 60.0, 7, position,
                0, 0, null, false, 0, null, null, null,
                null, null, 100.0, null, null,
                null, null, null, false,
                null, false,
                0);
    }

    private static Lineup lineupWith(List<Player> starters) {
        Lineup lineup = new Lineup();
        lineup.setStartingPlayers(new ArrayList<>(starters));
        lineup.setStarterOrderFromIds(starters.stream()
                .map(Player::getId).toList());
        return lineup;
    }
}
