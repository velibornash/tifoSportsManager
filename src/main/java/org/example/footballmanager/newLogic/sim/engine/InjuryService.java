package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In-match injuries (Sprint 1.6).
 *
 * <p>Ported from {@code engine_v1/RealisticMatchEngine.maybeTriggerInjury}, which was quarantined
 * to {@code footballForDelete/} on 2026-09-26. That was the ONLY injury generator in the codebase
 * and the best mechanic in the original audit, so the live engine had been producing zero injuries
 * and the medical page, the injury model and the substitution logic all had nothing to act on.
 *
 * <p>The mechanic is the chain worth keeping: play → fatigue → injury risk → injury → replacement.
 * Risk scales with fatigue, and the player most likely to be hurt is selected weighted by fatigue,
 * so a tired player is both more likely to go down and more likely to be the one who does.
 *
 * <p>Two deliberate changes from the original:
 * <ul>
 *   <li>Severity is an injury <em>type</em> with a realistic day range and a recurrence chance,
 *       not three hardcoded buckets.</li>
 *   <li>Fatigue is a 0..1 accumulator in this engine (the old code used 0..100 ints), so the
 *       thresholds are rescaled. The shape of the curve is unchanged.</li>
 * </ul>
 */
public class InjuryService {

    /**
     * Per-tick base probability. The original used 0.00028 per tick on a 0..100 fatigue scale over
     * a 90-minute match; this is rescaled to the engine's 0..1 fatigue and its 3600-tick match.
     */
    private static final double BASE_CHANCE_PER_TICK = 0.000045;

    /** Fatigue is 0..1 here; above ~0.5 the risk curve starts to bite. */
    private static final double FATIGUE_THRESHOLD = 0.5;
    private static final double FATIGUE_SLOPE = 0.00022;

    /** Winger and striker take more risks, so they go down slightly more often. */
    private static final double POSITIONAL_BONUS = 0.00003;

    /** No injuries in the opening or closing moments - the original used minutes 8..88. */
    private static final int MIN_INJURY_TICK = 8 * 40;
    private static final int MAX_INJURY_TICK = 88 * 40;

    private final MatchState state;
    private final MatchRecorder recorder;
    private final ProposalStatsCollector stats;

    /** Set by the orchestrator so an injury stops the referee's clock. */
    private Runnable onInjury;

    void setOnInjury(Runnable onInjury) {
        this.onInjury = onInjury;
    }

    public InjuryService(MatchState state, MatchRecorder recorder, ProposalStatsCollector stats) {
        this.state = state;
        this.recorder = recorder;
        this.stats = stats;
    }

    /** Injury types with realistic absence ranges. Weighted by frequency. */
    public enum InjuryType {
        KNOCK(1, 4, 0.20),          // dead leg, bruising
        HAMSTRING(7, 21, 0.16),
        ANKLE(4, 14, 0.24),          // the most common football injury
        KNEE(10, 35, 0.14),
        GROIN(5, 16, 0.10),
        CALF(6, 18, 0.08),
        FRACTURE(28, 90, 0.04),      // long term
        CONCUSSION(5, 12, 0.04);

        final int minDays;
        final int maxDays;
        final double weight;

        InjuryType(int minDays, int maxDays, double weight) {
            this.minDays = minDays;
            this.maxDays = maxDays;
            this.weight = weight;
        }

        public int minDays() { return minDays; }
        public int maxDays() { return maxDays; }
    }

    /** Called once per tick by the orchestrator. */
    public void onTick() {
        int tick = state.getMatchTicks();
        if (tick < MIN_INJURY_TICK || tick > MAX_INJURY_TICK) return;

        for (String team : List.of("HOME", "AWAY")) {
            maybeInjure(team, tick);
        }
    }

    private void maybeInjure(String team, int tick) {
        List<Player> onPitch = state.getPlayers().stream()
                .filter(p -> p.getTeam().equals(team))
                .filter(p -> !p.isOnBench())
                .filter(p -> !p.isUnavailable())
                .toList();
        if (onPitch.isEmpty()) return;

        // One roll for the team, not one per player: the original's per-player loop would have
        // multiplied the effective risk by the size of the squad.
        double chance = BASE_CHANCE_PER_TICK;
        Optional<Player> victim = pickInjuryRiskPlayer(onPitch);
        if (victim.isEmpty()) return;
        Player player = victim.get();
        chance += Math.max(0.0, player.getFatigue() - FATIGUE_THRESHOLD) * FATIGUE_SLOPE;
        if (isHighRiskPosition(player)) chance += POSITIONAL_BONUS;

        if (SimulationRandom.nextDouble() >= chance) return;

        InjuryType type = rollType();
        int days = SimulationRandom.nextInt(type.maxDays() - type.minDays() + 1) + type.minDays();

        player.setInjured(true);
        player.setInjuryType(type.name());
        player.setInjuryDaysRemaining(days);
        // An injury costs condition on top of the lay-off.
        player.setFatigue(Math.min(1.0, player.getFatigue() + 0.06));

        int minute = tick / 40;
        String msg = "INJURY " + player.getLabel() + "(" + player.getRole() + ") - " + type
                + ", " + days + " days, at " + minute + "'";
        if (state.getActionLogger() != null) state.getActionLogger().log("INJ", msg);
        if (recorder != null) {
            recorder.appendEvent(tick, "INJURY", msg, player, null);
        }
        if (stats != null) {
            stats.onInjury(team, player.getId(), type.name(), days);
        }
    }

    /**
     * Weighted pick: a fatigued player is more likely to be the one who goes down.
     * Goalkeepers are excluded, as in the original.
     */
    private Optional<Player> pickInjuryRiskPlayer(List<Player> onPitch) {
        List<Player> candidates = onPitch.stream()
                .filter(Objects::nonNull)
                .filter(p -> !p.isGoalkeeper())
                .toList();
        if (candidates.isEmpty()) return Optional.empty();

        double total = candidates.stream()
                .mapToDouble(p -> 1.0 + Math.max(0.0, p.getFatigue() - 0.25) * 0.25)
                .sum();
        double roll = SimulationRandom.nextDouble() * total;
        for (Player p : candidates) {
            roll -= 1.0 + Math.max(0.0, p.getFatigue() - 0.25) * 0.25;
            if (roll <= 0) return Optional.of(p);
        }
        return Optional.of(candidates.get(candidates.size() - 1));
    }

    private boolean isHighRiskPosition(Player p) {
        String role = p.getRole();
        return "STL".equals(role) || "STR".equals(role)
                || "ML".equals(role) || "MR".equals(role);
    }

    private InjuryType rollType() {
        double total = 0;
        for (InjuryType t : InjuryType.values()) total += t.weight;
        double roll = SimulationRandom.nextDouble() * total;
        InjuryType chosen = InjuryType.ANKLE;
        for (InjuryType t : InjuryType.values()) {
            roll -= t.weight;
            if (roll <= 0) { chosen = t; break; }
        }
        return chosen;
    }
}
