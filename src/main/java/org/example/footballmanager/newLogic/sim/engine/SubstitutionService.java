package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.example.footballmanager.newLogic.sim.util.SimUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Substitutions (Sprint 1.8).
 *
 * <p>Until now a match squad was exactly eleven and the rest of the squad list was discarded, so:
 * a red card left a team playing the rest of the match with ten and no recourse; a tiring player
 * could not be replaced; and fatigue had no consequence because there was nothing to substitute
 * into. {@code Player.substituted} existed and was never set.
 *
 * <p>Rules implemented: five substitutions per team across three windows. A red card or an injury
 * does not consume a window (the player has to come off), which is how the real laws treat it.
 */
public class SubstitutionService {

    /** Fatigue at which the manager is expected to make a change, per player. */
    private static final double FATIGUE_SUB_THRESHOLD = 0.62;
    /** Never sub before this fraction of the match has been played, except for forced changes. */
    private static final double EARLY_GUARD = 0.20;

    private final MatchState state;
    private final MatchRecorder recorder;
    private final ProposalStatsCollector stats;

    public SubstitutionService(MatchState state, MatchRecorder recorder, ProposalStatsCollector stats) {
        this.state = state;
        this.recorder = recorder;
        this.stats = stats;
    }

    /**
     * Brings {@code on} on for {@code off}, if the laws allow it.
     *
     * @param forced true for an injury - the game is already stopped for the injury, so the change
     *               costs no time and consumes no window. It still counts toward the five.
     * @return true if the change was made
     */
    public boolean substitute(Player off, Player on, boolean forced) {
        if (off == null || on == null) return false;
        if (!off.getTeam().equals(on.getTeam())) return false;
        // A sent-off or injured player IS unavailable - and replacing him is exactly why a forced
        // substitution exists, so that check must not apply to a forced change. It did, and the
        // red-card replacement was rejected: a team was left with ten for the rest of the match.
        if (off.isOnBench()) return false;
        // A sent-off player can never be replaced. Only an injured player can be brought off.
        if (off.isSentOff()) return false;
        if (!forced && off.isUnavailable()) return false;
        if (state.getBench(on.getTeam()).stream().noneMatch(p -> p.getId().equals(on.getId()))) {
            return false;   // not actually on the bench any more
        }

        if (!forced) {
            if (state.getSubsUsed(off.getTeam()) >= MatchState.MAX_SUBSTITUTIONS) return false;
            double progress = (double) state.getMatchTicks() / totalTicks();
            if (progress < EARLY_GUARD) return false;
            // A substitution either opens a new window or joins the current one. Joining the
            // current window only works inside the stoppage the window was opened in, which the
            // engine models as the ticks since the last restart; to keep this simple and safe a
            // discretionary sub always opens a window if none is currently open.
            if (state.getSubWindowsUsed(off.getTeam()) >= MatchState.MAX_SUB_WINDOWS
                    && !insideOpenWindow(off.getTeam())) {
                return false;
            }
            if (!insideOpenWindow(off.getTeam())) {
                state.addSubWindowUsed(off.getTeam());
            }
            state.addSubUsed(off.getTeam());
        }

        // The incoming player takes the outgoing man's place on the pitch.
        state.bringOn(off, on);

        state.setCameOnTick(on.getId(), state.getMatchTicks());
        state.setWentOffTick(off.getId(), state.getMatchTicks());

        String msg = "SUB " + off.getLabel() + "(" + off.getRole() + ") off"
                + (forced ? " [forced]" : "") + " -> " + on.getLabel() + "(" + on.getRole() + ") on";
        log(msg);
        if (recorder != null) {
            recorder.appendEvent(state.getMatchTicks(), "SUBSTITUTION", msg, on, off);
        }
        if (stats != null) {
            stats.onSubstitution(off.getTeam(), off.getId(), on.getId(), forced);
        }
        return true;
    }

    /**
     * The window is open while play is stopped.
     *
     * <p>Now answered by the real {@link StoppageClock} rather than by "is a restart taker walking
     * to the ball". That heuristic was a coincidence, not a rule: it read roughly right for a
     * substitution straight after a restart and wrong for a window opened by a stoppage the restart
     * system never sees. A window is a property of the referee's stoppage, so that is what it now
     * asks.
     */
    private boolean insideOpenWindow(String team) {
        if (stoppage != null) return stoppage.isStopped();
        return state.getRestartTaker() != null;
    }

    /** Injected by the orchestrator; null keeps this service usable standalone. */
    private StoppageClock stoppage;

    void setStoppage(StoppageClock stoppage) {
        this.stoppage = stoppage;
    }

    /**
     * 3600 ticks for 90 minutes at the engine's 40 ticks per minute. Halftime sits at 1800
     * (MatchOrchestrator:441).
     */
    private static final int FULL_MATCH_TICKS = 3600;

    private int totalTicks() {
        return FULL_MATCH_TICKS;
    }

    /**
     * Reacts to the match: an emergency replacement after a red card or an injury, and a routine
     * change once someone is genuinely exhausted. Called once per tick by the orchestrator.
     */
    /** True when the most recent onTick() actually put a substitute on the pitch. */
    private boolean lastTickSubstituted;

    public boolean didSubstituteLastTick() {
        return lastTickSubstituted;
    }

    /**
     * Injury replacements only.
     *
     * <p>Split out from the fatigue pass so the orchestrator can seat the manager's conditional
     * rules between them: injury is not a decision, a rule is, and fatigue is the fallback. Running
     * them in one pass meant a rule could find its slot already spent by a fatigue change.
     */
    public void onTickInjuriesOnly() {
        lastTickSubstituted = false;
        for (String team : List.of("HOME", "AWAY")) {
            List<Player> out = new ArrayList<>();
            for (Player p : state.getPlayers()) {
                if (!p.getTeam().equals(team) || p.isOnBench()) continue;
                if (p.isInjured()) out.add(p);
            }
            for (Player gone : out) {
                Player replacement = pickReplacement(team, gone);
                if (replacement != null) {
                    lastTickSubstituted |= substitute(gone, replacement, true);
                }
            }
        }
    }

    /** The fatigue fallback. See {@link #onTickInjuriesOnly()}. */
    public void onTickFatigueOnly() {
        for (String team : List.of("HOME", "AWAY")) {
            if (state.getSubsUsed(team) >= MatchState.MAX_SUBSTITUTIONS) continue;
            Optional<Player> exhausted = state.getPlayers().stream()
                    .filter(p -> p.getTeam().equals(team) && !p.isOnBench() && !p.isUnavailable())
                    .filter(p -> !p.isGoalkeeper())
                    .filter(p -> p.getFatigue() >= FATIGUE_SUB_THRESHOLD)
                    .max(Comparator.comparingDouble(Player::getFatigue));
            if (exhausted.isPresent()) {
                Player replacement = pickReplacement(team, exhausted.get());
                if (replacement != null) {
                    lastTickSubstituted |= substitute(exhausted.get(), replacement, false);
                }
            }
        }
    }

    public void onTick() {
        lastTickSubstituted = false;
        for (String team : List.of("HOME", "AWAY")) {
            // Emergency: a player who cannot continue because he is injured. NOT a red card -
            // a dismissal leaves the team a man down for the rest of the match.
            List<Player> out = new ArrayList<>();
            for (Player p : state.getPlayers()) {
                if (!p.getTeam().equals(team) || p.isOnBench()) continue;
                // INJURY ONLY. A sent-off player is never replaced - the team plays the rest of
                // the match a man down. This previously also replaced sent-off players, which
                // is simply not football, and it defeated the point of the red card entirely.
                if (p.isInjured()) out.add(p);
            }
            for (Player gone : out) {
                Player replacement = pickReplacement(team, gone);
                if (replacement != null) {
                    lastTickSubstituted |= substitute(gone, replacement, true);
                }
            }

            // Routine: fatigue.
            if (state.getSubsUsed(team) >= MatchState.MAX_SUBSTITUTIONS) continue;
            Optional<Player> exhausted = state.getPlayers().stream()
                    .filter(p -> p.getTeam().equals(team) && !p.isOnBench() && !p.isUnavailable())
                    .filter(p -> !p.isGoalkeeper())   // never swap the keeper automatically
                    .filter(p -> p.getFatigue() >= FATIGUE_SUB_THRESHOLD)
                    .max(Comparator.comparingDouble(Player::getFatigue));
            if (exhausted.isPresent()) {
                Player replacement = pickReplacement(team, exhausted.get());
                if (replacement != null) {
                    lastTickSubstituted |= substitute(exhausted.get(), replacement, false);
                }
            }
        }
    }

    /**
     * Best benched player who can cover the same role, falling back to anyone who can.
     *
     * <p>Matching on role rather than raw rating matters: swapping a centre-back for a winger
     * because the winger has a higher number is how you lose matches.
     */
    Player pickReplacement(String team, Player off) {
        String role = off.getRole();
        List<Player> bench = state.getBench(team).stream()
                .filter(p -> !p.isSentOff())
                .toList();
        if (bench.isEmpty()) return null;
        for (Player p : bench) {
            if (role != null && role.equals(p.getRole()) && !p.isGoalkeeper() == !off.isGoalkeeper()) {
                return p;
            }
        }
        return bench.stream()
                .filter(p -> p.isGoalkeeper() == off.isGoalkeeper())
                .max(Comparator.comparingDouble(this::benchQuality))
                .orElse(null);
    }

    /** Rating proxy for a benched player, favouring role-appropriate skills. */
    private double benchQuality(Player p) {
        String role = p.getRole() == null ? "" : p.getRole();
        double primary = switch (role) {
            case "GK" -> p.getSkills().keeper();
            case "STL", "STR" -> p.getSkills().striker();
            case "ML", "MR" -> p.getSkills().technique();
            case "CML", "CMR" -> p.getSkills().playmaking();
            case "DL", "DR", "DCL", "DCR" -> p.getSkills().defender();
            default -> p.getSkills().passing();
        };
        return primary * 2.0 + p.getSkills().pace() + p.getSkills().stamina() * 0.5;
    }

    private void log(String message) {
        if (state.getActionLogger() != null) {
            state.getActionLogger().log("SUB", message);
        }
    }
}
