package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manager's conditional substitution rules.
 *
 * <p>Evaluated live against the match: *"at minute 60, if we are losing, bring on X for Y"*. The
 * hard parts of substitutions were built in S1.8 — the bench, the five-sub budget, the three
 * windows, role-aware replacement selection — so this is a layer on top rather than a rebuild.
 *
 * <h2>Precedence</h2>
 * <ol>
 *   <li><b>Injury.</b> The game is already stopped for it, so the change is free of time and of a
 *       window, and it is not a decision anybody made.</li>
 *   <li><b>The manager's rule.</b> A deliberate act.</li>
 *   <li><b>Fatigue auto-sub.</b> The fallback when nobody asked.</li>
 * </ol>
 * A red card sits outside the chain entirely: a sent-off player is never replaced, so it changes
 * nothing about the bench and cannot consume a slot a rule was counting on.
 *
 * <h2>Why rules wait for a stoppage</h2>
 * A substitution is only legal while play is stopped, so a rule that comes true mid-flow cannot
 * simply execute. It waits for the next stoppage, and if none arrives the referee is asked for one.
 * That is a real constraint, not an implementation detail, so a rule that fires mid-flow reports
 * itself as <em>waiting</em> rather than silently doing nothing.
 */
public class ConditionalSubstitutionRules {

    /** The state of a rule at this moment in the match. */
    public enum Status {
        /** Not yet due. */
        PENDING,
        /** Due, waiting for the next stoppage to make the change legally. */
        WAITING_FOR_STOPPAGE,
        /** Fired. */
        FIRED,
        /** Can never fire — see {@link Rule#voidReason}. */
        VOID
    }

    /** Why a rule can no longer happen. Surfaced to the manager rather than swallowed. */
    public enum VoidReason {
        NONE,
        /** The named player has already come on, so he cannot come on again. */
        PLAYER_ALREADY_ON,
        /** The named player is no longer in the squad (sold, released, injured out of the game). */
        PLAYER_UNAVAILABLE,
        /** The five substitutions are gone. */
        NO_SUBS_LEFT,
        /** All three windows are gone. */
        NO_WINDOWS_LEFT,
        /** The named player is already on the pitch. */
        PLAYER_ALREADY_ON_PITCH,
        /** Nobody left on the bench to bring on. */
        EMPTY_BENCH
    }

    /**
     * One rule.
     *
     * <p>Held in memory during a match and persisted per match on the server, so a plan set before
     * kickoff is still there at minute 55 after a page reload.
     */
    public static class Rule {
        public int id;
        public String team;
        /** Match minute from which this rule may fire. */
        public int triggerMinute;
        /** Losing / drawing / leading / anytime, from that team's point of view. */
        public String condition = "ANYTIME";
        /** Player id to bring on. Empty means "let the engine choose the best available". */
        public String playerOnId = "";
        /** Player id to take off. Empty means "let the engine choose". */
        public String playerOffId = "";
        public Status status = Status.PENDING;
        public VoidReason voidReason = VoidReason.NONE;
        /** Set when the rule fires, for the live view. */
        public int firedAtMinute = -1;
        /** The sub slot this rule was counting on, so a slot spent elsewhere is visible. */
        public int reservedSlot = -1;

        public Rule() { }

        public Rule(String team, int triggerMinute, String condition) {
            this.team = team;
            this.triggerMinute = triggerMinute;
            this.condition = condition;
        }
    }

    private final MatchState state;
    private final SubstitutionService substitutions;
    private final List<Rule> rules = new ArrayList<>();

    public ConditionalSubstitutionRules(MatchState state, SubstitutionService substitutions) {
        this.state = state;
        this.substitutions = substitutions;
    }

    public List<Rule> all() {
        return rules;
    }

    public void add(Rule rule) {
        rules.add(rule);
    }

    public void clear() {
        rules.clear();
    }

    public int minute() {
        return state.getMatchTicks() / MatchClockService.MATCH_TICKS_PER_MINUTE;
    }

    /**
     * Evaluates every rule for this tick. Called after the emergency and fatigue passes, so a rule
     * only sees substitutions that are genuinely still available.
     */
    public void onTick() {
        for (Rule rule : rules) {
            if (rule.status == Status.FIRED || rule.status == Status.VOID) continue;

            if (minute() < rule.triggerMinute) continue;

            if (isVoid(rule)) continue;

            if (!conditionHolds(rule)) continue;

            // Due. It is only legal to change players while play is stopped, so either the referee
            // has already stopped the match, or we ask for one.
            if (!stopped()) {
                rule.status = Status.WAITING_FOR_STOPPAGE;
                continue;
            }
            fire(rule);
        }
    }

    /** Asks the referee to stop play so a waiting rule can execute. */
    private boolean stopped() {
        return state.isStopped() || state.getRestartTaker() != null;
    }

    private boolean conditionHolds(Rule rule) {
        int mine = scoreFor(rule.team);
        int theirs = scoreFor(other(rule.team));
        return switch (rule.condition == null ? "ANYTIME" : rule.condition) {
            case "LOSING" -> mine < theirs;
            case "DRAWING" -> mine == theirs;
            case "LEADING" -> mine > theirs;
            default -> true;
        };
    }

    private void fire(Rule rule) {
        Player off = resolvePlayerOff(rule);
        Player on = resolvePlayerOn(rule);
        if (off == null || on == null) {
            rule.voidReason = off == null ? VoidReason.PLAYER_ALREADY_ON_PITCH : VoidReason.EMPTY_BENCH;
            rule.status = Status.VOID;
            return;
        }
        if (substitutions.substitute(off, on, false)) {
            rule.status = Status.FIRED;
            rule.firedAtMinute = minute();
        } else {
            // The laws said no — most likely the window closed between the check and the change.
            rule.status = Status.WAITING_FOR_STOPPAGE;
        }
    }

    /** Marks rules that can never fire, with the reason, instead of ignoring them. */
    private boolean isVoid(Rule rule) {
        VoidReason reason = voidReasonFor(rule);
        if (reason != VoidReason.NONE) {
            rule.voidReason = reason;
            rule.status = Status.VOID;
            return true;
        }
        return false;
    }

    VoidReason voidReasonFor(Rule rule) {
        if (state.getSubsUsed(rule.team) >= MatchState.MAX_SUBSTITUTIONS) {
            return VoidReason.NO_SUBS_LEFT;
        }
        if (state.getSubWindowsUsed(rule.team) >= MatchState.MAX_SUB_WINDOWS
                && !isWindowOpen()) {
            return VoidReason.NO_WINDOWS_LEFT;
        }
        if (!rule.playerOnId.isBlank()) {
            Player named = findPlayer(rule.playerOnId);
            if (named == null || named.isOnBench() == false && named.isUnavailable()) {
                return VoidReason.PLAYER_UNAVAILABLE;
            }
            if (named != null && named.isOnBench() == false) {
                return VoidReason.PLAYER_ALREADY_ON;
            }
        }
        if (state.getBench(rule.team).isEmpty()) {
            return VoidReason.EMPTY_BENCH;
        }
        return VoidReason.NONE;
    }

    private boolean isWindowOpen() {
        return state.getRestartTaker() != null || state.isStopped();
    }

    private Player resolvePlayerOn(Rule rule) {
        if (!rule.playerOnId.isBlank()) {
            Player named = findPlayer(rule.playerOnId);
            if (named != null && named.isOnBench() && !named.isUnavailable()) return named;
        }
        // No named player, or the named one is gone: let the engine pick, preferring a player who
        // matches the role being taken off so the shape of the team does not change.
        Player off = resolvePlayerOff(rule);
        if (off != null) {
            Player roleMatch = substitutions.pickReplacement(rule.team, off);
            if (roleMatch != null) return roleMatch;
        }
        // pickReplacement is role-aware and returns null when the bench has nobody for that role -
        // e.g. an outfield rule with only a keeper left on the bench. The bench is not empty, so
        // falling through to "anybody available" is correct and reporting EMPTY_BENCH would be a
        // lie. Without this fallback a rule silently died with a full bench.
        return availableBench(rule.team).stream()
                .max(java.util.Comparator.comparingDouble(p -> p.getSkills().pace()))
                .orElse(null);
    }

    /** Bench players who can actually come on. */
    private List<Player> availableBench(String team) {
        List<Player> out = new ArrayList<>();
        for (Player p : state.getBench(team)) {
            if (!p.isUnavailable() && !p.isSentOff()) out.add(p);
        }
        return out;
    }

    private Player resolvePlayerOff(Rule rule) {
        if (!rule.playerOffId.isBlank()) {
            Player named = findPlayer(rule.playerOffId);
            if (named != null && !named.isOnBench() && !named.isUnavailable() && !named.isSentOff()) {
                return named;
            }
        }
        // Otherwise the most tired man on the pitch, which is nearly always the one a manager
        // would take off anyway.
        //
        // Never the goalkeeper unless the rule names him: swapping the keeper is a decision, not an
        // automatic one, and the fatigue pass already refuses to make it. With a squad where every
        // outfielder happens to be equally tired, max() returns the first player it sees - which is
        // usually the keeper - and the rule then died because no keeper was on the bench.
        return state.getPlayers().stream()
                .filter(p -> rule.team.equals(p.getTeam()))
                .filter(p -> !p.isOnBench() && !p.isUnavailable() && !p.isSentOff())
                .filter(p -> !p.isGoalkeeper())
                .max(java.util.Comparator.comparingDouble(Player::getFatigue))
                .orElse(null);
    }

    private Player anyOnPitch(String team) {
        return state.getPlayers().stream()
                .filter(p -> team.equals(p.getTeam()) && !p.isOnBench() && !p.isUnavailable())
                .findFirst().orElse(null);
    }

    private Player findPlayer(String id) {
        for (Player p : state.getPlayers()) if (p.getId().equals(id)) return p;
        for (Player p : state.getBench("HOME")) if (p.getId().equals(id)) return p;
        for (Player p : state.getBench("AWAY")) if (p.getId().equals(id)) return p;
        return null;
    }

    private int scoreFor(String team) {
        return "HOME".equals(team) ? state.getHomeGoals() : state.getAwayGoals();
    }

    private static String other(String team) {
        return "HOME".equals(team) ? "AWAY" : "HOME";
    }
}
