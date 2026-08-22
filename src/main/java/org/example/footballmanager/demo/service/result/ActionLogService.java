package org.example.footballmanager.demo.service.result;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.engine.DuelResolver;
import org.example.footballmanager.demo.service.model.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive action logger for match simulation.
 * <p>
 * Records every decision, action, duel, foul, card, corner, goal and other
 * significant event with both a wall-clock timestamp (HH:mm:ss.SSS) and a
 * match-clock timestamp (minute:second) so the simulation can be debugged
 * action-by-action.
 */
public class ActionLogService {

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final List<LogEntry> logEntries = new ArrayList<>();

    /** Real-time timestamp for when the log entry is created. */
    public String getTimestamp() {
        return LocalDateTime.now().format(WALL_CLOCK);
    }

    /** Format the match clock from {@link MatchState#getMatchTicks()}. */
    public String matchClock(MatchState state) {
        int ticks = state.getMatchTicks();
        int minute = ticks / MatchState.MATCH_TICKS_PER_MINUTE;
        int second = (int) Math.round((ticks % MatchState.MATCH_TICKS_PER_MINUTE) * 60.0 / MatchState.MATCH_TICKS_PER_MINUTE);
        if (minute > MatchState.REGULATION_MINUTES) {
            return MatchState.REGULATION_MINUTES + "+" + (minute - MatchState.REGULATION_MINUTES)
                    + ":" + String.format("%02d", second);
        }
        return minute + ":" + String.format("%02d", second);
    }

    private LogEntry.Builder entry(LogEntry.EntryType type, MatchState state, String channel, String description) {
        return LogEntry.builder(getTimestamp(), type, matchClock(state), channel, description);
    }

    // ── Decision ──────────────────────────────────────────────────────────

    /**
     * Log a playmaking decision: which options were considered, their scores,
     * which was selected and why.
     */
    public void logDecision(MatchState state, DecisionOption selected, List<DecisionOption> allOptions, String channel) {
        StringBuilder desc = new StringBuilder();
        desc.append("DECISION: ").append(selected.getType());
        if (selected.getTarget() != null) {
            desc.append(" → ").append(selected.getTarget().getLabel());
        }
        desc.append("  score=").append(String.format("%.3f", selected.getScore()));
        desc.append("  reason=[").append(selected.getReason()).append("]");
        desc.append("  visible=").append(selected.isVisible());

        if (allOptions != null && !allOptions.isEmpty()) {
            desc.append("  | Options considered:");
            for (DecisionOption opt : allOptions) {
                desc.append("  ")
                        .append(opt.getType())
                        .append("(score=").append(String.format("%.3f", opt.getScore())).append(")")
                        .append(" visible=").append(opt.isVisible());
                if (opt.getTarget() != null) desc.append(" → ").append(opt.getTarget().getLabel());
            }
        }

        Player carrier = state.getCarrier();
        LogEntry.Builder b = entry(LogEntry.EntryType.DECISION, state, channel, desc.toString())
                .context(allOptions);
        if (carrier != null) {
            b.team(carrier.getTeam()).player(carrier.getId(), carrier.getLabel());
        }
        logEntries.add(b.build());
    }

    // ── Action execution ──────────────────────────────────────────────────

    /** Log the moment an action (pass / shot / dribble / chase) is dispatched. */
    public void logActionExecution(MatchState state, Action action, String outcome, Player actor, Player target, String channel) {
        StringBuilder desc = new StringBuilder();
        desc.append("ACTION: ").append(action.getType()).append(" by ").append(actor.getLabel());
        if (target != null) desc.append(" → ").append(target.getLabel());
        desc.append("  skill=").append(action.getSkill());
        desc.append("  intended=").append(action.getIntendedTarget());
        desc.append("  actual=").append(action.getActualTarget());
        desc.append("  goodExec=").append(action.isGoodExecution());
        desc.append("  chaseTicks=").append(action.getChaseTicks());
        desc.append("  chaseNoProgress=").append(action.getChaseNoProgressTicks());
        desc.append("  outcome=").append(outcome);

        LogEntry.Builder b = entry(LogEntry.EntryType.ACTION_EXECUTION, state, channel, desc.toString())
                .team(actor.getTeam())
                .player(actor.getId(), actor.getLabel())
                .context(action);
        if (target != null) {
            b.targetPlayer(target.getId(), target.getLabel());
        }
        logEntries.add(b.build());
    }

    /** Log the resolved outcome of an action (received, loose, goal, miss, save...). */
    public void logActionOutcome(MatchState state, Action action, String outcome, Player actor, Player target, String channel) {
        StringBuilder desc = new StringBuilder();
        desc.append("OUTCOME: ").append(action.getType()).append(" → ").append(outcome);
        if (action.getActualTarget() != null) desc.append("  actualTarget=").append(action.getActualTarget());
        desc.append("  goodExec=").append(action.isGoodExecution());

        LogEntry.Builder b = entry(LogEntry.EntryType.ACTION_OUTCOME, state, channel, desc.toString())
                .context(action);
        if (actor != null) b.player(actor.getId(), actor.getLabel()).team(actor.getTeam());
        if (target != null) b.targetPlayer(target.getId(), target.getLabel());
        logEntries.add(b.build());
    }

    // ── Duel ─────────────────────────────────────────────────────────────

    /**
     * Log a resolved duel.
     *
     * @param result  the {@link DuelResolver.DuelResult} containing winner/power/outcome
     */
    public void logDuel(MatchState state, DuelResolver.DuelResult result, Player attacker, Player defender,
                        DuelType type, String channel) {
        StringBuilder desc = new StringBuilder();
        desc.append("DUEL ").append(type);
        desc.append("  attacker=").append(attacker.getLabel()).append(" power=").append(result.attackerPower());
        desc.append("  defender=").append(defender.getLabel()).append(" power=").append(result.defenderPower());
        desc.append("  winner=").append(result.winner().getLabel());
        desc.append("  outcome=").append(result.outcome());

        logEntries.add(entry(LogEntry.EntryType.DUEL, state, channel, desc.toString())
                .player(attacker.getId(), attacker.getLabel())
                .targetPlayer(defender.getId(), defender.getLabel())
                .context(result)
                .build());
    }

    // ── Fouls & cards ────────────────────────────────────────────────────

    public void logFoul(MatchState state, String team, String playerId, String playerName, String description, String channel) {
        logEntries.add(entry(LogEntry.EntryType.FOUL, state, channel, "FOUL (" + team + "): " + description)
                .team(team)
                .player(playerId, playerName)
                .build());
    }

    public void logCard(MatchState state, String team, String playerId, String playerName,
                        String cardType, int yellowCount, String channel) {
        String desc = "CARD: " + cardType + (yellowCount > 0 ? " (previous yellows=" + yellowCount + ")" : "");
        logEntries.add(entry(LogEntry.EntryType.CARD, state, channel, desc)
                .team(team)
                .player(playerId, playerName)
                .build());
    }

    public void logFoulWithCard(MatchState state, String team, String playerId, String playerName,
                                String cardType, int yellowCount, String channel) {
        logFoul(state, team, playerId, playerName, "Foul committed", channel);
        logCard(state, team, playerId, playerName, cardType, yellowCount, channel);
    }

    // ── Set pieces ───────────────────────────────────────────────────────

    public void logCorner(MatchState state, String team, boolean rightCorner, String channel) {
        String desc = "CORNER for " + team + " (" + (rightCorner ? "right" : "left") + ")";
        logEntries.add(entry(LogEntry.EntryType.CORNER, state, channel, desc)
                .team(team)
                .build());
    }

    public void logThrowIn(MatchState state, String team, String channel) {
        logEntries.add(entry(LogEntry.EntryType.THROW_IN, state, channel, "THROW-IN for " + team)
                .team(team)
                .build());
    }

    public void logGoalKick(MatchState state, String team, String channel) {
        logEntries.add(entry(LogEntry.EntryType.GOAL_KICK, state, channel, "GOAL KICK for " + team)
                .team(team)
                .build());
    }

    // ── Goals ────────────────────────────────────────────────────────────

    public void logGoal(MatchState state, String team, String scorerId, String scorerName,
                        String assistantName, String channel) {
        String desc = "GOAL! " + scorerName + " (" + team + "): " + state.getGoalCount() + "-" + state.getAwayGoalCount();
        if (assistantName != null && !assistantName.isEmpty()) desc += "  assist: " + assistantName;
        logEntries.add(entry(LogEntry.EntryType.GOAL, state, channel, desc)
                .team(team)
                .player(scorerId, scorerName)
                .build());
    }

    // ── Chase ────────────────────────────────────────────────────────────

    public void logChase(MatchState state, Player chaser, Player carrier, double distance, String channel) {
        String desc = "CHASE " + chaser.getLabel() + "  dist=" + String.format("%.3f", distance);
        if (carrier != null) desc += "  (ball carried by " + carrier.getLabel() + ")";
        logEntries.add(entry(LogEntry.EntryType.CHASE, state, channel, desc)
                .team(chaser.getTeam())
                .player(chaser.getId(), chaser.getLabel())
                .build());
    }

    public void logChaseWinner(MatchState state, Player winner, String channel) {
        logEntries.add(entry(LogEntry.EntryType.CHASE, state, channel,
                "CHASE WINNER: " + winner.getLabel() + " reached ball first")
                .team(winner.getTeam())
                .player(winner.getId(), winner.getLabel())
                .build());
    }

    // ── Possession & restarts ────────────────────────────────────────────

    public void logPossession(MatchState state, String holdingTeam, int possessionTicks, String channel) {
        double pct = state.getMatchTicks() > 0
                ? 100.0 * possessionTicks / state.getMatchTicks()
                : 0.0;
        logEntries.add(entry(LogEntry.EntryType.POSSESSION, state, channel,
                "POSSESSION: " + holdingTeam + " held ball for " + possessionTicks + " ticks ("
                        + String.format("%.1f", pct) + "% of elapsed match)")
                .team(holdingTeam)
                .build());
    }

    public void logRestart(MatchState state, String description, String channel) {
        logEntries.add(entry(LogEntry.EntryType.RESTART, state, channel, description).build());
    }

    // ── Free-form ────────────────────────────────────────────────────────

    public void logInfo(MatchState state, String description, String channel) {
        logEntries.add(entry(LogEntry.EntryType.INFO, state, channel, description).build());
    }

    public void logInfo(MatchState state, String description, String channel, Player actor) {
        LogEntry.Builder b = entry(LogEntry.EntryType.INFO, state, channel, description);
        if (actor != null) b.player(actor.getId(), actor.getLabel()).team(actor.getTeam());
        logEntries.add(b.build());
    }

    // ── Output ───────────────────────────────────────────────────────────

    public List<LogEntry> getAllLogs() {
        return new ArrayList<>(logEntries);
    }

    public void clear() {
        logEntries.clear();
    }

    /** Print every entry to stdout (for CLI / standalone debugging). */
    public void printLogs() {
        for (LogEntry e : logEntries) {
            System.out.println(e);
        }
    }

    /** Print a summary of entry counts by type. */
    public void printSummary() {
        System.out.println("=== ACTION LOG SUMMARY ===");
        System.out.println("Total entries: " + logEntries.size());
        for (LogEntry.EntryType type : LogEntry.EntryType.values()) {
            long count = logEntries.stream().filter(e -> e.getType() == type).count();
            if (count > 0) System.out.println("  " + type + ": " + count);
        }
    }
}
