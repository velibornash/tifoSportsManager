package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.DecisionOption;
import org.example.footballmanager.demo.service.proposal.model.DecisionResult;
import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import org.example.footballmanager.demo.service.proposal.model.Position;

import java.util.List;

/**
 * Owns all structured log / minute / position formatting. Engines that used to
 * hand-assemble {@code [minute|TAG] ...} lines now go through here, so the event
 * log stays one consistent, tagged shape across DEC / DUL / BAL / SHK / VAR.
 */
public class ActionLogService {

    private final MatchState state;
    private final List<String> eventLog;

    public ActionLogService(MatchState state, List<String> eventLog) {
        this.state = state;
        this.eventLog = eventLog;
    }

    /** Append a tagged, minute-prefixed line to the shared log (and stdout). */
    private void log(String tag, String msg) {
        String line = "[" + minute() + "|" + tag + "] " + msg;
        eventLog.add(line);
        System.out.println(line);
    }

    public String p(Position pos) {
        return pos == null ? "?" : "(%.1f,%.1f)".formatted(pos.getRow(), pos.getColumn());
    }

    /** Structured DECISION line: chosen option + alternatives + ball + receiver. */
    public String formatDecision(Player carrier, DecisionResult result) {
        Player receiver = result.getChosen().getTarget();
        StringBuilder sb = new StringBuilder();
        sb.append("DECISION ").append(carrier.getLabel()).append("(").append(carrier.getRole()).append(")")
                .append(" -> ").append(result.getChosen().getType())
                .append(" score=").append(String.format("%6.1f", result.getChosen().getScore()));
        for (DecisionOption o : result.getOptions()) {
            if (o == result.getChosen()) continue;
            sb.append(" | ").append(o.getType()).append("=")
                    .append(String.format("%5.1f", o.getScore()));
        }
        sb.append(" | ball").append(p(state.getBall().getPosition()));
        if (receiver != null) {
            sb.append(" -> ").append(receiver.getLabel())
                    .append("(").append(receiver.getRole()).append(")").append(p(receiver.getPosition()));
        }
        return sb.toString();
    }

    /** Minute of play, formatted {@code M:SS} (40 ticks = 1 min of match time). */
    public String minute() {
        return String.format("%d:%02d",
                state.getMatchTicks() / 40,
                state.getMatchTicks() % 40 * 90 / 40);
    }
}
