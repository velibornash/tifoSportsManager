package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.StadiumRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Pitch wear and the maintenance programme that answers it (Sprint 2.2).
 *
 * <p>Stadium had a {@code pitchQuality} number that never moved. This gives the pitch a life of its
 * own: every match played on it costs condition, the club sets a weekly maintenance budget, and
 * condition only recovers out of that budget. At zero budget the pitch keeps degrading, which is
 * the decision that matters — a club in financial trouble cannot afford to keep its surface.
 *
 * <h2>Two separate numbers, on purpose</h2>
 * <ul>
 *   <li><b>Condition</b> (0-100) — the surface right now. Moves every week, up and down.</li>
 *   <li><b>Quality</b> (0-100) — the long-term ceiling: the underlying surface and the equipment
 *       behind it. Condition is dragged back toward it by maintenance, so a neglected pitch
 *       recovers to a poor ceiling rather than to 100.</li>
 * </ul>
 *
 * <p>Collapsing them into one number was the previous design and it is why the number did nothing:
 * there was no way to express "this club stopped investing", only "this pitch is at 80".
 */
@Service
public class PitchMaintenanceService {

    /** Condition points a single match costs a pitch, before the wear factor. */
    private static final double WEAR_PER_MATCH = 2.2;

    /**
     * Condition points one euro of maintenance buys — €2,000 per point.
     *
     * <p>Calibrated against the wear figure: a club playing twice a week loses ~4.4 points, so a
     * €9,000 week roughly holds the surface and a €20,000 week visibly improves it. A first pass used
     * 0.02 (€50 a point), which made the entire €50,000 weekly cap worth more than the pitch's
     * whole condition range and reduced the decision to "spend the maximum, always".
     */
    private static final double RESTORE_PER_EURO = 0.0005;

    /** Below this, the pitch is visibly bad and starts costing attendance and player condition. */
    public static final int POOR_PITCH_THRESHOLD = 60;

    /** Below this, it is a crisis the manager will be asked about. */
    public static final int CRITICAL_PITCH_THRESHOLD = 40;

    /** Ceiling on the weekly spend, so one club cannot spend its whole budget on turf. */
    private static final double MAX_WEEKLY_MAINTENANCE = 50_000.0;

    private final TeamRepository teamRepository;
    private final StadiumRepository stadiumRepository;

    public PitchMaintenanceService(TeamRepository teamRepository, StadiumRepository stadiumRepository) {
        this.teamRepository = teamRepository;
        this.stadiumRepository = stadiumRepository;
    }

    /**
     * One match played. Wear scales with how good the surface already is — a great pitch takes
     * more punishment, and a bad one is already past caring.
     */
    @Transactional
    public void registerMatchPlayed(Stadium stadium) {
        if (stadium == null) return;
        int condition = conditionOf(stadium);
        double quality = qualityOf(stadium);
        double wear = WEAR_PER_MATCH * (0.6 + 0.4 * (quality / 100.0));
        stadium.setPitchCondition(clampInt(condition - (int) Math.round(wear)));
    }

    /**
     * A week of maintenance spending.
     *
     * @param budget euros the club has earmarked for the pitch this week
     * @return what the money actually bought, for the ledger and the UI
     */
    @Transactional
    public MaintenanceResult applyWeeklyMaintenance(Stadium stadium, double budget) {
        if (stadium == null) return MaintenanceResult.none();

        double spend = Math.max(0, Math.min(MAX_WEEKLY_MAINTENANCE, budget));
        int before = conditionOf(stadium);
        int remainingBefore = remainingOf(stadium);

        if (spend <= 0) {
            // No money: the programme carries whatever was left, the pitch does not recover, and
            // the pitch keeps wearing. This is the failure the manager is meant to feel.
            stadium.setMaintenanceRemaining(remainingBefore);
            return new MaintenanceResult(before, before, spend, 0.0, remainingBefore, true);
        }

        int ceiling = (int) Math.round(qualityOf(stadium));
        // Spend is spent whether or not it is needed, but it cannot lift the pitch above the
        // long-term quality of the surface underneath it.
        double restored = spend * RESTORE_PER_EURO;
        int after = clampInt((int) Math.round(before + restored));

        int overshoot = Math.max(0, after - ceiling);
        if (overshoot > 0) after = ceiling;
        double wasted = overshoot / RESTORE_PER_EURO;

        stadium.setPitchCondition(after);
        // Whatever was not needed rolls forward, so the club can save up for a resurfacing.
        stadium.setMaintenanceRemaining(clampInt(remainingBefore + (int) Math.round(spend - wasted)));

        return new MaintenanceResult(before, after, spend, wasted, remainingOf(stadium), false);
    }

    /** Sets the weekly maintenance budget and clears the carried-over programme. */
    @Transactional
    public Stadium setMaintenanceProgramme(Long teamId, double weeklyBudget) {
        Stadium stadium = stadiumOf(teamId);
        if (stadium == null) return null;
        stadium.setMaintenanceRemaining(Math.max(0, (int) Math.round(Math.min(MAX_WEEKLY_MAINTENANCE, weeklyBudget))));
        return stadiumRepository.save(stadium);
    }

    public Stadium stadiumOf(Long teamId) {
        if (teamId == null) return null;
        return teamRepository.findWithStadiumById(teamId).map(Team::getStadium).orElse(null);
    }

    /** Condition penalty applied to match speed and player sharpness, 0.0 to 1.0. */
    public double conditionPenalty(Stadium stadium) {
        int condition = conditionOf(stadium);
        if (condition >= POOR_PITCH_THRESHOLD) return 0.0;
        return (POOR_PITCH_THRESHOLD - condition) / 100.0;
    }

    public String describe(Stadium stadium) {
        int c = conditionOf(stadium);
        if (c <= CRITICAL_PITCH_THRESHOLD) return "The surface is in poor condition and the players have complained.";
        if (c < POOR_PITCH_THRESHOLD) return "The pitch is worn and starting to show.";
        if (c < 85) return "The surface is playing well enough.";
        return "The surface is in excellent condition.";
    }

    public int conditionOf(Stadium stadium) {
        if (stadium == null) return 100;
        Integer c = stadium.getPitchCondition();
        if (c != null) return clampInt(c);
        // Fall back to the legacy quality number for a ground seeded before this existed.
        return (int) Math.round(qualityOf(stadium));
    }

    public int remainingOf(Stadium stadium) {
        if (stadium == null) return 0;
        Integer r = stadium.getMaintenanceRemaining();
        return r == null ? 0 : Math.max(0, r);
    }

    private double qualityOf(Stadium stadium) {
        if (stadium == null) return 75.0;
        Double q = stadium.getPitchQuality();
        return q == null ? 75.0 : Math.max(10.0, Math.min(100.0, q));
    }

    private int clampInt(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /**
     * What one week of maintenance did.
     *
     * @param before        condition before
     * @param after         condition after
     * @param spent         euros spent
     * @param wasted        euros spent above the long-term quality ceiling — carries forward
     * @param remaining     maintenance programme left
     * @param unfunded      true when there was no budget and the pitch is simply wearing
     */
    public record MaintenanceResult(int before, int after, double spent, double wasted,
                                    int remaining, boolean unfunded) {
        public static MaintenanceResult none() {
            return new MaintenanceResult(100, 100, 0, 0, 0, true);
        }

        public int restored() {
            return after - before;
        }
    }
}
