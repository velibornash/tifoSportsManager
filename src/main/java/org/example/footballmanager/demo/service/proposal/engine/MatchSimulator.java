package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import org.example.footballmanager.demo.service.proposal.MatchSimulationLauncher;

/**
 * Proposal fixed driver — full 90-minute match (3600 ticks @ 40 TPM).
 *
 * Port directive (legacy reference {@code demo/service/result/MatchSimulator.java}
 * javadoc: "Port: proposal/engine/MatchSimulator.java").
 *
 * The reference launcher in this repo is {@code proposal/ui/ProposalMatchExporter.java};
 * this driver mirrors its proven call surface exactly (see
 * {@code ProposalMatchExporter#exportFullMatch()}):
 *   <ol>
 *   <li>build empty {@link MatchState} + register both teams via
 *       {@link MatchSimulationLauncher#addTeam} (generates 11 home + 11 away
 *       proposal-model players);</li>
 *   <li>construct the {@link MatchOrchestrator} and take HOME kickoff;</li>
 *   <li>record round-start positions + round pace skills (receiver-speed /
 *       pace-weighted movement source — must be set before the first tick);</li>
 *   <li>run {@link MatchOrchestrator#simulate} for the full 3600-tick match,
 *       which handles half-time (resume at 1800 + AWAY kickoff) internally.</li>
 *   </ol>
 * After a full run the caller reads {@code state.getEventLog()} (P6#1 aggregation)
 * and the per-tick snapshots captured by the proposal recorder.
 *
 * <p><b>Honesty note (§14 backlog):</b> this driver is deliberately thin — the
 * proposal orchestrator already owns the real tick loop (MovementEngine,
 * DuelEngine, ActionEngine, VAR, OffsideService…). It must NOT re-implement
 * the legacy simulator's restart-walk / VAR-hold bodies; those live in
 * {@code MatchOrchestrator.tick()} and are driven here. Any port that would
 * duplicate those bodies is over-engineering.</p>
 */
public class MatchSimulator {

    /** Full match length in ticks (90 min @ 40 TPM — see MatchClockService). */
    public static final int FULL_MATCH_TICKS = MatchClockService.TOTAL_MATCH_TICKS;

    private final MatchState state;
    private final MatchOrchestrator orchestrator;

    public MatchSimulator(MatchState state) {
        this.state = state;
        this.orchestrator = new MatchOrchestrator(state);
    }

    /** Run a full match from the current (kicked-off) state. */
    public void simulateFullMatch() {
        orchestrator.simulate(FULL_MATCH_TICKS);
    }

    public MatchState getState() { return state; }
    public MatchOrchestrator getOrchestrator() { return orchestrator; }

    /**
     * One-shot fixture: build teams, kick off, run 3600 ticks, return the state.
     * Convenience for batch diagnostics (P6#1) that only need the final state's
     * event log / stats without touching the exporter.
     */
    public static MatchState runFullMatch() {
        MatchState state = new MatchState();
        MatchSimulationLauncher.addTeam(state, "HOME");
        MatchSimulationLauncher.addTeam(state, "AWAY");
        MatchSimulator sim = new MatchSimulator(state);
        sim.getOrchestrator().getRestartManager().handleKickoff(state, "HOME");
        for (Player p : state.getPlayers()) {
            state.setRoundStartPosition(p.getId(), p.getPosition());
            state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
        }
        sim.simulateFullMatch();
        return state;
    }
}
