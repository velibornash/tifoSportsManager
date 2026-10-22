package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.DecisionOption;
import org.tifo.footballmanager.newLogic.sim.model.DecisionResult;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;

/**
 * Owns all structured log / minute / position formatting. Engines that used to
 * hand-assemble {@code [minute|TAG] ...} lines now go through here, so the event
 * log stays one consistent, tagged shape across DEC / DUL / BAL / SHK / VAR.
 *
 * <p>Console verbosity (2026-09-23): a full match produces ~28k tagged lines
 * (TAC/THR target-recompute noise is ~80% of it), which scrolls the match start
 * out of the IntelliJ run-console buffer. By default the console shows only the
 * {@code CONSOLE_NOTABLE} tags (shots, goals, duels, restarts, cards, offside,
 * ...). The FULL line stream is always mirrored to the file
 * {@code target/proposal-app.log} and into the shared {@code eventLog} (which is
 * shipped as match.json {@code logs}) so nothing is lost.
 * <ul>
 *   <li>{@code -Dproposal.log.console=full} — print every line to stdout too
 *       (used by diagnostics/batch greps).</li>
 *   <li>{@code -Dproposal.log.file=<path>} — override the full-log file.</li>
 * </ul>
 */
public class ActionLogService {

    /** Forty ticks make a minute of match time, so a tick is 1.5 seconds. */
    public static final int TICKS_PER_MINUTE = 40;

    /** Tags that drive the on-pitch match story — the only lines printed to stdout
     *  in compact (default) mode. A full match stays ~900 lines (vs ~28k raw),
     *  so the IntelliJ run-console buffer never cuts the match start. Deeper
     *  detail (DEC per-action decisions, EXE executions, TAC/THR target
     *  recomputes, ball-flight/possession traces) is always mirrored to
     *  {@code target/proposal-app.log} and the match.json {@code logs}. */
    private static final Set<String> CONSOLE_NOTABLE = Set.of(
            "GOAL", "GOAL_DISALLOWED", "KICKOFF",
            "SHOT", "SHOT_SAVED", "SHOT_MISSED", "SHOT_BLOCKED", "SHOT_POST",
            "PENALTY_KICK", "PENALTY_MISS", "PENALTY_SAVED",
            "OFF",
            "FOUL",
            "POSSESSION_CHANGE", "CHASE_POSSESSION",
            "VAR_OFFSIDE_CONFIRMED", "VAR_OFFSIDE_OVERTURNED",
            "VAR_GOAL_CONFIRMED", "VAR_GOAL_OVERTURNED",
            "VAR_RED_CONFIRMED", "VAR_RED_OVERTURNED",
            "VAR_PENALTY_CONFIRMED", "VAR_PENALTY_OVERTURNED");

    private static final boolean COMPACT_CONSOLE =
            !"full".equalsIgnoreCase(System.getProperty("proposal.log.console", "compact"));
    private static final String LOG_FILE =
            System.getProperty("proposal.log.file", "target/proposal-app.log");

    /**
     * How many of the per-player lines the in-memory log keeps.
     *
     * <p><b>The log was unbounded, and it is the match's memory.</b> {@code log} fires per player per
     * tick, so a match held about 1.6 million lines — and the replay carried them all, 11-14 MB of
     * JSON per match, <b>of which the viewer reads none</b>.
     *
     * <p>So the noise is capped and the story is not. The match story (the notable tags) and every
     * decision are kept whatever happens, because those are what a reader and a diagnostic ask for;
     * the per-player traces are what nobody reads and what the heap paid for.
     *
     * <p><b>The file log still has everything.</b> {@code target/proposal-app.log} is written on every
     * line, so nothing is lost — it is the replay JSON that was carrying a million lines nobody wanted.
     */
    public static final int MAX_EVENT_LOG_LINES = 50_000;

    private static PrintWriter fileLog;
    private static boolean lineWritten;

    private final MatchState state;
    private final List<String> eventLog;

    public ActionLogService(MatchState state, List<String> eventLog) {
        this.state = state;
        this.eventLog = eventLog;
    }

    /** Append a tagged, minute-prefixed line to the shared log, the full-log
     *  file, and (compact mode: notable tags only) stdout. */
    public void log(String tag, String msg) {
        String line = "[" + String.format("%d:%02d", state.getMatchTicks() / TICKS_PER_MINUTE, state.getMatchTicks() % TICKS_PER_MINUTE * 60 / TICKS_PER_MINUTE) + "|" + tag + "] " + msg;
        // The story and the decisions are kept whatever happens; the per-player traces are capped.
        if (CONSOLE_NOTABLE.contains(tag) || "DECISION".equals(tag)
                || eventLog.size() < MAX_EVENT_LOG_LINES) {
            eventLog.add(line);
        }
        fileLog().println(line);
        if (!lineWritten) {
            lineWritten = true;
            System.out.println("⚽ Match event log: " + LOG_FILE
                    + " (full stream; console = " + (COMPACT_CONSOLE ? "compact" : "full") + ")");
        }
        if (!COMPACT_CONSOLE || CONSOLE_NOTABLE.contains(tag)) {
            System.out.println(line);
        }
    }

    private static synchronized PrintWriter fileLog() {
        if (fileLog == null) {
            try {
                Path p = Path.of(LOG_FILE).normalize();
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                fileLog = new PrintWriter(new BufferedWriter(Files.newBufferedWriter(
                        p, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)), true);
            } catch (IOException e) {
                System.err.println("proposal: cannot open " + LOG_FILE
                        + " (" + e.getMessage() + ") — console fallback to full");
                fileLog = new PrintWriter(System.out, true);
            }
        }
        return fileLog;
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

}
