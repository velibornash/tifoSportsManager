package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.util.Rounding;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.SquadRole;
import org.tifo.footballmanager.newLogic.model.Team;

import java.util.List;

/**
 * What a club costs and what it earns, from the club itself.
 *
 * <p><b>One place, because there were four.</b> A seeded player, a named player, an academy graduate and
 * a renewal demand were each priced by their own formula, at magnitudes three orders of magnitude apart:
 *
 * <ul>
 *   <li>{@code PlayerFactory.wageForSkills} — {@code 900 × 1.22^best};</li>
 *   <li>{@code PlayerFactory.valueForSkills} — {@code 25 000 × 1.28^best};</li>
 *   <li>the named-player path — {@code 250 + best × 170}, linear, unrelated to either;</li>
 *   <li>{@code YouthAcademyService} — {@code 500 + random(2500)}, a raw draw, for a graduate whose own
 *       market value is computed eight lines later.</li>
 * </ul>
 *
 * <p>A player worth 100 million appeared in three places at three different weekly wages, and the wage
 * bill — which is a plain sum of {@code Player.earnings} — was whichever writer happened to touch him
 * last. So "what does this club cost" had no answer you could compute twice and get the same figure.
 *
 * <h2>The relationship this holds to</h2>
 *
 * <p>A wage is a share of a player's value, annualised and divided by the weeks in a year. That is the
 * relationship real football runs on, and {@link SquadRole#wageExpectationFactor()} already encodes the
 * share; what was missing was anything on the other side of it. Here it is, once.
 */
public final class ClubEconomics {

    private ClubEconomics() {
    }

    /**
     * A player's weekly wage, from his market value, his role and his age.
     *
     * <p>Age and role are the two things that move a wage without moving a player's worth: a 21-year-old
     * on a star's terms is still cheaper than a 29-year-old on them, and a prospect is cheaper than a
     * starter even at the same value.
     */
    public static double weeklyWage(double playerValue, SquadRole role, int age) {
        double value = Math.max(0, playerValue);
        double annual = value * role.wageExpectationFactor() * ageFactor(age);
        return round2(annual / 52.0);
    }

    /**
     * The same, for a player who has one.
     *
     * <p>Reads his value and his age rather than being handed them, so a caller cannot accidentally
     * price a player off one and not the other.
     */
    public static double weeklyWage(Player player, SquadRole role) {
        if (player == null) return 0.0;
        return weeklyWage(player.getPlayerValue(), role, player.getAge());
    }

    /**
     * What share of a player's market value his annual wage represents.
     *
     * <p>For real football this is 15-25% for a star and much less further down a squad: a club that
     * signs for 50m typically commits 8-12m a year to wages for him.
     *
     * <p>It is used to price the <b>named, hand-entered squads</b>, and it is here rather than in a
     * table of literals for one reason: those squads carry a market value and a weekly wage as two
     * separate columns, and nobody checks one against the other. OFK Omladinac's thirteen players were
     * seeded at 45-84% of their own market value per year — a goalkeeper valued at 186.9m on a weekly
     * wage of 1,888,000, which is 98m a year, or more than half his transfer fee every year for ever.
     *
     * <p>Derived from the value instead, the two cannot disagree. The <b>values</b> are left alone:
     * they are the owner's own figures for his own club and re-pricing them is a different decision.
     */
    public static final double SEEDED_WAGE_SHARE_OF_VALUE = 0.20;

    /** The weekly wage for a hand-entered squad: a stated share of the value he is carried at. */
    public static double seededWeeklyWage(double playerValue, int age) {
        double annual = Math.max(0, playerValue) * SEEDED_WAGE_SHARE_OF_VALUE * ageFactor(age);
        return round2(annual / 52.0);
    }

    /**
     * How much of his best years are still ahead of him.
     *
     * <p>The same curve {@code PlayerContractService} uses for a renewal demand, so a player who signs
     * on these terms and later asks for more is being asked for the same thing twice rather than a
     * different number each time.
     */
    public static double ageFactor(int age) {
        if (age <= 20) return 0.72;
        if (age <= 24) return 0.86;
        if (age <= 28) return 1.0;
        if (age <= 32) return 1.12;
        return 1.05;
    }

    /** The club's weekly wage bill: what it actually pays, from the players it actually has. */
    public static double wageBill(Team club, List<Player> squad) {
        if (squad == null) return 0.0;
        return round2(squad.stream().mapToDouble(p -> Math.max(0, p.getEarnings())).sum());
    }

    /**
     * Broadcast income as a share of what the club pays its players.
     *
     * <p><b>This is the line that decides whether the economy means anything.</b> It used to be
     * {@code competition.reputationWeight × 145} — a pure function of the division, identical for every
     * club in a league and blind to the club. Measured on the owner's database, season 1 week 1:
     *
     * <pre>
     *   gate          +173 591     broadcast         +2 900
     *   merchandising   +1 739     wages         -18 478 000
     *                                ─────────────────────────────
     *   income        +176 491     total         -18 496 388
     * </pre>
     *
     * <p>A 105× deficit. No club in the world is solvent and a manager's spending decisions are
     * meaningless, because the number does not move whatever he does.
     *
     * <p>Tying the league's television money to the league's wage bill makes income proportional to
     * what a club is worth by construction: a stronger squad costs more <em>and</em> earns more, and a
     * weak club cannot sign players it cannot pay for. The coefficient is highest at the top, where the
     * money is, and falls away lower down, where a small club is carried by its gate and its sponsors
     * rather than by television — and whose wage bill is small enough that television is not what pays
     * the staff.
     *
     * <p><b>The coefficient is calibrated, and the calibration is an open question.</b> At 0.78 a club
     * whose gate and merchandising cover about a fifth of its wage bill roughly breaks even, which is
     * the only number here that was chosen rather than derived.
     *
     * <p>One club in the owner's database does not fit it: OFK Omladinac pays 18,478,000 a week
     * across thirteen players — 1.4m a player, 74m a year — while its stadium is 43,780 seats and its
     * gate brings 173,591. Sixty times a normal club's wage bill on a ground twice the size. No single
     * coefficient balances that club and an ordinary one, because they are not the same kind of club:
     * the wage scale is the outlier, not the income side. See the board entry.
     */
    public static double broadcastShareOfWageBill(Competition competition) {
        int tier = competition != null && competition.getTier() != null ? competition.getTier() : 1;
        return switch (Math.max(1, Math.min(5, tier))) {
            case 1 -> 0.78;
            case 2 -> 0.74;
            case 3 -> 0.70;
            case 4 -> 0.66;
            default -> 0.62;
        };
    }

    /**
     * How full the ground is, from how good the club is.
     *
     * <p>It used to be a literal: 72% home, 55% away, every club, every week, every opponent. A
     * fifth-tier club with a reputation of 20 sold the same ground out as a first-tier club with 90, and
     * the projection the manager is shown before a match was the projection he would have got from any
     * of them.
     *
     * <p>The away end barely moves with the visiting club's quality — travelling support is mostly a
     * function of the size of the trip — so only the home end is scaled.
     */
    public static double homeOccupancy(Team club) {
        double reputation = club == null || club.getReputation() == null ? 50 : club.getReputation();
        return clamp(0.34 + reputation / 100.0 * 0.52, 0.30, 0.94);
    }

    /** The away end, for contrast: nearly the same for every fixture in the game. */
    public static final double AWAY_OCCUPANCY = 0.55;

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static double round2(double value) {
        return Rounding.to2(value);
    }
}
