package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.DecisionOption;
import org.example.footballmanager.newLogic.sim.model.DecisionResult;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;

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
     *  {@code target/proposal-app.log} and the match.json {@code logs}.
     *  {@code DATA_ORC} is kept only for its {@code ***} epilogues (GOAL /
     *  SHOT_SAVED / SHOT_MISSED) — RECEIVE/LOOSE recoveries stay in the file. */
    private static final Set<String> CONSOLE_NOTABLE = Set.of(
            "GOAL", "GOAL_DISALLOWED", "KICKOFF",
            "SHOT", "SHOT_SAVED", "SHOT_MISSED", "SHOT_BLOCKED", "SHOT_POST",
            "PENALTY_KICK", "PENALTY_MISS", "PENALTY_SAVED",
            "DUL", "OFF", "RST",
            "FOUL", "CARD", "YELLOW_CARD", "RED_CARD",
            "POSSESSION_CHANGE", "CHASE_POSSESSION",
            "VAR_OFFSIDE_CONFIRMED", "VAR_OFFSIDE_OVERTURNED",
            "VAR_GOAL_CONFIRMED", "VAR_GOAL_OVERTURNED",
            "VAR_RED_CONFIRMED", "VAR_RED_OVERTURNED",
            "VAR_PENALTY_CONFIRMED", "VAR_PENALTY_OVERTURNED",
            "DEC", "EXE");

    private static final boolean COMPACT_CONSOLE =
            !"full".equalsIgnoreCase(System.getProperty("proposal.log.console", "compact"));
    private static final String LOG_FILE =
            System.getProperty("proposal.log.file", "target/proposal-app.log");

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
        String line = "[" + minute() + "|" + tag + "] " + msg;
        eventLog.add(line);
        fileLog().println(line);
        if (!lineWritten) {
            lineWritten = true;
            System.out.println("⚽ Match event log: " + LOG_FILE
                    + " (full stream; console = " + (COMPACT_CONSOLE ? "compact" : "full") + ")");
        }
        if (!COMPACT_CONSOLE || CONSOLE_NOTABLE.contains(tag)
                || ("ORC".equals(tag) && msg.startsWith("*** "))) {
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

    /**
     * Minute of play, formatted {@code M:SS} (40 ticks = 1 min of match time).
     *
     * <p>The seconds are {@code (ticks % 40) * 60 / 40}, not {@code * 90 / 40}. Forty ticks make a
     * <b>minute</b>, so a tick is one and a half seconds and the remainder has to be scaled to
     * sixty. Multiplying by ninety produces 0-87 and prints things like {@code 44:65} and
     * {@code 32:87} — a clock with eighty-seven seconds in it. {@code %02d} does not care, it
     * prints whatever it is given, which is why this sat unnoticed.
     */
    public String minute() {
        return String.format("%d:%02d",
                state.getMatchTicks() / TICKS_PER_MINUTE,
                state.getMatchTicks() % TICKS_PER_MINUTE * 60 / TICKS_PER_MINUTE);
    }
}
