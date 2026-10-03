package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.util.SimTeamFactory;

import java.util.List;

/**
 * Headless full-match runner for the sim engine. Builds the two synthetic
 * squads, kicks off, and runs the tick loop to completion. The caller keeps
 * the {@link MatchOrchestrator} to read the recorder / stats / outcome.
 */
public final class SimMatchRunner {

    public static final int FULL_MATCH_TICKS = 3600;

    private SimMatchRunner() {}

    public static MatchOrchestrator run(String homeName, String awayName, int ticks) {
        return run(homeName, awayName, ticks, null, null);
    }

    /**
     * Runs a full match with explicitly provided squads. A null/empty squad for a
     * side falls back to the synthetic {@link SimTeamFactory} squad so the engine
     * always has 11 players per side.
     */
    public static MatchOrchestrator run(String homeName, String awayName, int ticks,
                                        List<Player> homeSquad, List<Player> awaySquad) {
        return run(homeName, awayName, ticks, homeSquad, awaySquad, null, null);
    }

    /**
     * Runs a full match with explicit squads and benches (Sprint 1.8).
     *
     * <p>Benched players go into {@code MatchState.getBench(side)} and never into the live player
     * list, so the running simulation cannot see them until a substitution actually happens.
     */
    public static MatchOrchestrator run(String homeName, String awayName, int ticks,
                                        List<Player> homeSquad, List<Player> awaySquad,
                                        List<Player> homeBench, List<Player> awayBench) {
        return run(homeName, awayName, ticks, homeSquad, awaySquad, homeBench, awayBench, null);
    }

    /**
     * Runs a full match with the home club's own tactics (owner, 2026-10-03).
     *
     * <p><b>This is the overload the game path uses and the rest are kept for the launchers.</b> The
     * tactics are passed in rather than read here because this class is deliberately outside Spring —
     * the diagnostics, the viewer and the exporter all construct a match through it — so a null here
     * still produces exactly the football it always did.
     *
     * <p><b>One set of rules, both sides.</b> Rules are authored from the home perspective and
     * {@code TacticsRules} mirrors them for AWAY, which is what it has always done. Giving the away
     * club its <em>own</em> shape needs a second rules object and a change to how perspective is
     * resolved, and that is deliberately not smuggled in here — see the board on cluster F #1.
     */
    public static MatchOrchestrator run(String homeName, String awayName, int ticks,
                                        List<Player> homeSquad, List<Player> awaySquad,
                                        List<Player> homeBench, List<Player> awayBench,
                                        org.example.footballmanager.newLogic.sim.tactics.TacticsRules tactics) {
        MatchState state = new MatchState();
        boolean homeReal = homeSquad != null && homeSquad.size() >= 11;
        boolean awayReal = awaySquad != null && awaySquad.size() >= 11;
        if (homeReal) state.getPlayers().addAll(homeSquad); else SimTeamFactory.addTeam(state, "HOME");
        if (awayReal) state.getPlayers().addAll(awaySquad); else SimTeamFactory.addTeam(state, "AWAY");

        if (homeBench != null) state.getBench("HOME").addAll(homeBench);
        if (awayBench != null) state.getBench("AWAY").addAll(awayBench);

        MatchOrchestrator orchestrator = tactics == null
                ? new MatchOrchestrator(state)
                : new MatchOrchestrator(state, tactics);
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