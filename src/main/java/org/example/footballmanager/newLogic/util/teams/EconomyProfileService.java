package org.example.footballmanager.newLogic.util.teams;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Seeds a club's economy from the tier of the division it lands in (Sprint 2.1).
 *
 * <p>Every club used to be created with {@code budget = 0.0} and {@code reputation = 50.0}, which
 * made the whole economy inert: gate income, sponsorship, prize money and transfer budgets all
 * scale off those two numbers, so a flat 50 meant every club in the country was equally valuable
 * and the transfer market had nothing to price against.
 *
 * <p>Two properties matter more than the exact figures:
 * <ul>
 *   <li><b>Budget and reputation fall with the tier.</b> A Superliga club is not an Opstinska club.</li>
 *   <li><b>Variance within a tier.</b> Reputation that is a pure function of tier makes attendance,
 *       playoff odds and transfer-offer acceptance flatlines, and two clubs in the same division
 *       become indistinguishable. So each club gets a deterministic offset from its id.</li>
 * </ul>
 *
 * <p>The offset is derived from the team id rather than drawn at random, so a re-seed of the same
 * club produces the same profile — otherwise every restart reshuffled the country's strength order
 * and any saved league table became meaningless.
 */
@Component
public class EconomyProfileService {

    /** Budget band in euros, by division tier (1 = top). */
    private static final double[] MIN_BUDGET = { 0, 3_000_000, 400_000, 80_000, 20_000, 3_000 };
    private static final double[] MAX_BUDGET = { 0, 8_000_000, 1_200_000, 250_000, 60_000, 12_000 };

    /** Reputation band by tier. Reputation is 0-100 and drives attendance, odds and interest. */
    private static final double[] MIN_REPUTATION = { 0, 72, 56, 44, 33, 22 };
    private static final double[] MAX_REPUTATION = { 0, 94, 70, 55, 42, 30 };

    /** How far a club may sit above or below its tier's band, so a tier is not a flatline. */
    private static final double REPUTATION_VARIANCE = 12.0;

    /** Reputation is clamped to this because a few outliers make the rest of the game unreadable. */
    private static final double REPUTATION_FLOOR = 8.0;
    private static final double REPUTATION_CEILING = 99.0;

    /**
     * Applies the profile for the division the club has just joined.
     *
     * <p>Never overwrites a club whose economy has already been set deliberately — the player's own
     * club is hand-tuned, and a re-seed must not quietly reset a club the user has been managing.
     */
    public void apply(Team team, Competition league) {
        if (team == null || league == null) return;
        if (team.getBudget() != null && team.getBudget() > 0) return;

        int tier = tierOf(league);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        double minB = pick(MIN_BUDGET, tier);
        double maxB = pick(MAX_BUDGET, tier);
        if (maxB <= minB) maxB = minB * 1.5 + 1;

        double minR = pick(MIN_REPUTATION, tier);
        double maxR = pick(MAX_REPUTATION, tier);

        team.setBudget(round(rng.nextDouble(minB, maxB)));
        team.setReputation(reputationFor(team.getId(), minR, maxR));

        applyStadiumToTier(team, tier, rng);
    }

    /**
     * Reputation within the tier's band, offset deterministically by id.
     *
     * <p>Deterministic so the same club is always the same club: promotion and relegation
     * normalise against these numbers, and a random draw each time would make a promoted club a
     * different strength on every run.
     */
    private double reputationFor(Long id, double min, double max) {
        long seed = id == null ? 0L : id;
        // A stable hash into [-1, 1].
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 31);
        double offset = ((h & 0xFFFF) / 32767.5) - 1.0;   // [-1, 1]
        double mid = (min + max) / 2.0;
        double halfSpan = (max - min) / 2.0;
        double value = mid + offset * (halfSpan + REPUTATION_VARIANCE);
        return Math.max(REPUTATION_FLOOR, Math.min(REPUTATION_CEILING, value));
    }

    /**
     * Stadium scale follows the tier too. A 5,000-seat ground for a Superliga club makes its gate
     * income meaningless, and gate income is the main thing the new stadium controls feed.
     */
    private void applyStadiumToTier(Team team, int tier, ThreadLocalRandom rng) {
        Stadium stadium = team.getStadium();
        if (stadium == null || (stadium.getCapacity() != null && stadium.getCapacity() > 0
                && stadium.getCapacity() != 5000)) {
            return;
        }
        int capacity = switch (tier) {
            case 1 -> rng.nextInt(12_000, 45_001);
            case 2 -> rng.nextInt(6_000, 16_000);
            case 3 -> rng.nextInt(3_000, 8_000);
            case 4 -> rng.nextInt(1_500, 4_000);
            default -> rng.nextInt(600, 2_000);
        };
        stadium.setCapacity(capacity);
        // Ticket price scaled to the ground: a 40,000-seat Superliga stadium charging what a
        // village ground charges would be a pricing mistake, not a choice.
        stadium.setTicketPrice(roundMoney(2 + capacity / 9000.0));
        stadium.setPitchQuality(rng.nextDouble(62, 92));
        if (stadium.getPitchCondition() == null) stadium.setPitchCondition(100);
        if (stadium.getCondition() == null) stadium.setCondition(100);
    }

    private int tierOf(Competition league) {
        Integer tier = league.getTier();
        if (tier == null || tier < 1) tier = 1;
        return Math.min(tier, MAX_BUDGET.length - 1);
    }

    private double pick(double[] band, int tier) {
        return band[Math.max(0, Math.min(band.length - 1, tier))];
    }

    private double round(double v) {
        return Math.round(v);
    }

    /** Ticket money to 2dp — it is a price, not a score. */
    private double roundMoney(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
