package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.util.SimTeamFactory;

/**
 * Headless full-match runner for the sim engine. Builds the two synthetic
 * squads, kicks off, and runs the tick loop to completion. The caller keeps
 * the {@link MatchOrchestrator} to read the recorder / stats / outcome.
 */
public final class SimMatchRunner {

    public static final int FULL_MATCH_TICKS = 3600;

    private SimMatchRunner() {}

    public static MatchOrchestrator run(String homeName, String awayName, int ticks) {
        MatchState state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");

        MatchOrchestrator orchestrator = new MatchOrchestrator(state);
        orchestrator.getStats().setDisplayNames(homeName, awayName);
        orchestrator.getRestartManager().handleKickoff(state, "HOME");

        for (Player p : state.getPlayers()) {
            state.setRoundStartPosition(p.getId(), p.getPosition());
            state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
        }

        orchestrator.simulate(ticks);
        return orchestrator;
    }
}