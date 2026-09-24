package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;

/**
 * Detects and resolves a deterministic single-opponent duel for the current
 * ball carrier. Every non-carrier opponent of the opposite team is checked;
 * if a duel fires ({@link DuelEngine#checkDuel}), it is resolved and its
 * result applied. The duels layer of the orchestrator is just this delegate.
 */
public class DuelService {

    private final MatchState state;
    private final MatchRecorder recorder;
    private final ProposalStatsCollector stats;
    private final DuelEngine duelEngine = new DuelEngine();

    public DuelService(MatchState state, MatchRecorder recorder,
                       ProposalStatsCollector stats) {
        this.state = state;
        this.recorder = recorder;
        this.stats = stats;
    }

    public void detectAndResolveDuels() {
        if (state.getCarrier() == null) return;
        Player carrier = state.getCarrier();

        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(carrier.getTeam())) continue;
            if (opponent.isUnavailable() || opponent.isLocked()) continue;

            DuelEngine.DuelType duelType = duelEngine.checkDuel(carrier, opponent, state);
            if (duelType != null) {
                Player winner = duelEngine.resolveDuel(carrier, opponent, duelType, state);
                duelEngine.applyDuelResult(state, winner, winner == carrier ? opponent : carrier);
                String duelMsg = "DUEL " + duelType + " won by " + winner.getLabel()
                        + " (" + carrier.getLabel() + p(carrier.getPosition())
                        + " v " + opponent.getLabel() + p(opponent.getPosition()) + ")"
                        + " ball" + p(state.getBall().getPosition());
                log("DUL", duelMsg);
                recorder.appendEvent(state.getMatchTicks(), "DUEL", duelMsg, state);
                stats.onDuelWon(winner.getId(), (winner == carrier ? opponent : carrier).getId());
            }
        }
    }

    private void log(String tag, String msg) {
        // Route through the shared action logger so the compact-console filter
        // and the full-log file (target/proposal-app.log) apply uniformly.
        state.getActionLogger().log(tag, msg);
    }

    private String p(Position pos) {
        return pos == null ? "?" : "(%.1f,%.1f)".formatted(pos.getRow(), pos.getColumn());
    }

    private String minute() {
        return String.format("%d:%02d",
                state.getMatchTicks() / 40,
                state.getMatchTicks() % 40 * 90 / 40);
    }
}
