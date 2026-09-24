package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.example.footballmanager.newLogic.sim.rules.DisciplineService;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

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
    private final DisciplineService discipline;

    public DuelService(MatchState state, MatchRecorder recorder,
                       ProposalStatsCollector stats) {
        this.state = state;
        this.recorder = recorder;
        this.stats = stats;
        this.discipline = new DisciplineService(state, null);
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

                // Discipline — only on a genuine defensive contest where the DEFENDER
                // won (tackle/dribble/receive). Evaluated BEFORE applyDuelResult so
                // state.getCarrier() still points at the fouled attacker for the foul
                // position/penalty-box checks.
                if (winner == opponent && (duelType == DuelEngine.DuelType.DRIBBLE
                        || duelType == DuelEngine.DuelType.TACKLE
                        || duelType == DuelEngine.DuelType.RECEIVE_PASS)) {
                    evaluateDiscipline(opponent, carrier);
                }

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

    private void evaluateDiscipline(Player offender, Player fouled) {
        state.setLastTouchPlayer(offender);
        DisciplineService.DisciplineResult res = discipline.evaluateFoul(state);
        if (!res.foul()) return;

        stats.onFoul(offender.getTeam(), offender.getId());
        String event = res.penalty() ? "PENALTY"
                : res.redCard() ? "RED_CARD"
                : res.yellowCard() ? "YELLOW_CARD" : "FOUL";
        String msg = res.penalty()
                ? "Penalty for " + fouled.getTeam() + " — foul by " + offender.getLabel()
                : res.redCard()
                ? "Red card: " + offender.getLabel()
                : res.yellowCard()
                ? "Yellow card: " + offender.getLabel()
                : "Free kick: foul by " + offender.getLabel() + " on " + fouled.getLabel();
        log("FOU", msg);
        recorder.appendEvent(state.getMatchTicks(), event, msg, state);
        if (res.yellowCard()) stats.onYellowCard(offender.getTeam(), offender.getId());
        if (res.redCard()) stats.onRedCard(offender.getTeam(), offender.getId());
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
