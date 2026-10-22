package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.recording.MatchEvent;
import org.tifo.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.tifo.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;

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

    /**
     * Scales injury risk for the kind of match being played (P2-8, owner decision 2026-10-03).
     *
     * <p>An exhibition costs fatigue exactly like any other match — a player who works ninety minutes
     * works ninety minutes — but nobody contests a practice match properly, so the risk of picking up
     * an injury is lower. Not zero, and not a separate injury table: the same rolls, at a lower rate.
     *
     * <p><b>Static because the engine is not configured.</b> {@code InjuryService} is constructed per
     * match from a {@code MatchState} and pulls everything else from {@code SimulationRandom}, which is
     * itself a static seeded source, so this follows the existing shape rather than inventing a
     * configuration path. It is set immediately before a match runs and reset immediately after, and
     * {@link #resetRiskForNextMatch()} exists so a test or a failure cannot leave it skewed.
     */
    private static double riskMultiplier = 1.0;

    /** Scales injury risk for the match about to be played. Pass 1.0 for a competitive match. */
    public static void setRiskMultiplier(double multiplier) {
        riskMultiplier = Math.max(0.0, multiplier);
    }

    /** Back to a competitive match. Called after every match and by every test that moves it. */
    public static void resetRiskForNextMatch() {
        riskMultiplier = 1.0;
    }

    /** The multiplier currently in force, so a caller can assert on it rather than assume. */
    public static double riskMultiplier() {
        return riskMultiplier;
    }

    /** Fatigue is 0..1 here; above ~0.5 the risk curve starts to bite. */
    private static final double FATIGUE_THRESHOLD = 0.5;

    /** Above this a player was already spent when he went down, and the record says so. */
    private static final double EXHAUSTED_FATIGUE = 0.5;

    /** How many recent events are searched for the duel that caused an injury. */
    private static final int RECENT_EVENTS = 40;
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

    /**
     * The longest absence a single injury can cause, in days.
     *
     * <p><b>Owner, 2026-10-22:</b> *"34 dana povreda, sa 2 ligaske utakmice nedeljno plus
     * potencijalno internationl / cup ili friendly to je skoro 20 utakmica... koliko god to verovatno
     * bilo realno, ljude koji vole igrice to smara... ok imamo medical center but still"*.
     *
     * <p>A week is seven days and the club plays two league games in it, so a month is about eight
     * matches — and a player who misses eight matches in a season is a quarter of his football. A
     * thirty-four-day absence was a third of the season, and it was the engine's own roll rather than
     * a decision: a knee could run to thirty-five days and a fracture to ninety.
     *
     * <p><b>Three weeks.</b> Long enough to be a real absence and short enough that a season is not
     * decided by one injury — and the medical centre can halve what is left of it.
     */
    public static final int MAX_INJURY_DAYS = 23;

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

    /**
     * How many days this injury keeps him out.
     *
     * <p>The type's range is how long that injury <em>can</em> last; the cap is how long the game will
     * have anybody missing. A knee ran to thirty-five days and a fracture to ninety, and a week is
     * seven days and the club plays two league games in it - so a month was a third of a season.
     *
     * @param type the injury
     * @param rolled the number of days the roll produced
     */
    public static int daysOutFor(InjuryType type, int rolled) {
        return Math.min(rolled, MAX_INJURY_DAYS);
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
        Optional<Player> victim = pickInjuryRiskPlayer(onPitch);
        if (victim.isEmpty()) return;
        Player player = victim.get();

        // The multiplier applies to the base rate only. The fatigue and positional terms stay whole,
        // so an exhibition is a less dangerous match rather than a different game.
        double chance = BASE_CHANCE_PER_TICK * riskMultiplier
                + Math.max(0.0, player.getFatigue() - FATIGUE_THRESHOLD) * FATIGUE_SLOPE
                + (isHighRiskPosition(player) ? POSITIONAL_BONUS : 0);

        if (SimulationRandom.nextDouble() >= chance) return;

        InjuryType type = rollType();
        int days = SimulationRandom.nextInt(type.maxDays() - type.minDays() + 1) + type.minDays();
        // **And never more than three weeks.** The type's range is how long that injury *can* last; the
        // cap is how long the game will have anyone missing.
        days = daysOutFor(type, days);
        // **Why he went down.** The engine knew at this moment and wrote none of it down.
        String cause = causeOf(player, tick);

        player.setInjured(true);
        player.setInjuryType(type.name());
        player.setInjuryDaysRemaining(days);
        player.setInjuryCause(cause);
        // An injury costs condition on top of the lay-off.
        player.setFatigue(Math.min(1.0, player.getFatigue() + 0.06));

        int minute = tick / 40;
        String msg = "INJURY " + player.getLabel() + "(" + player.getRole() + ") - " + type
                + ", " + days + " days, at " + minute + "' (" + cause + ")";
        if (state.getActionLogger() != null) state.getActionLogger().log("INJ", msg);
        if (recorder != null) {
            recorder.appendEvent(tick, "INJURY", msg, player, null);
        }
        if (stats != null) {
            stats.onInjury(team, player.getId(), type.name(), days);
        }
    }

    /**
     * Why this player went down, in a sentence a manager can read.
     *
     * <p><b>Owner, 2026-10-22:</b> *"bilo bi dobro ako mozemo da saznamo kako i zasto bas ovaj
     * igrac (jel imao fatigue, jel bio duel, jel izvrsena izmena?)"* - was he fatigued, was it a
     * duel, was a substitution made?
     *
     * <p>Three things the engine knows at the moment it injures a player, and all three used to be
     * thrown away:
     * <ul>
     *   <li><b>How tired he was.</b> The fatigue the match has already put into him, on a 0-1 scale. A
     *       player above the halfway mark was already spent when it happened.</li>
     *   <li><b>Whether it came out of a duel.</b> The recorder holds every duel of the match with the
     *       two players named in it, so the last one this player was in is findable.</li>
     *   <li><b>Whether he had only just come on.</b> A substitute who is hurt five minutes after
     *       walking on is a different story from a starter who is hurt in the eightieth minute, and the
     *       manager reading the medical page cannot tell them apart without being told.</li>
     * </ul>
     */
    private String causeOf(Player player, int tick) {
        boolean exhausted = player.getFatigue() >= EXHAUSTED_FATIGUE;
        String duel = lastDuelInvolving(player.getLabel());
        if (duel != null) {
            return exhausted ? "in a duel, and he was already exhausted" : "in a duel";
        }
        if (player.isSubstituted()) {
            return exhausted ? "seconds after coming on, and exhausted" : "seconds after coming on";
        }
        return exhausted ? "the wear and tear of a tired player" : "the wear and tear of the season";
    }

    /**
     * The last duel this player was in, or null if the match had not seen one.
     *
     * <p>Read from the recorder, which holds every duel with both players named. Only the last few
     * events are looked at: a duel from the first minute is not what injured him in the eightieth.
     */
    private String lastDuelInvolving(String label) {
        List<MatchEvent> events = recorder.getEvents();
        for (int i = events.size() - 1; i >= 0 && i >= events.size() - RECENT_EVENTS; i--) {
            MatchEvent event = events.get(i);
            if (!"DUEL".equals(event.getType())) {
                continue;
            }
            String description = event.getDescription();
            if (description != null && description.contains(label)) {
                return "in a duel";
            }
        }
        return null;
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
