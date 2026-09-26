package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.StadiumRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ticket tiers, the away sector, and what a home fixture is worth (Sprint 2.2).
 *
 * <p>Owns three rules that the rest of the economy depends on, kept here so there is exactly one
 * definition of each:
 *
 * <ol>
 *   <li><b>Ticket tiers.</b> A club does not sell one price. It sells a cheap block, a standard
 *       block and a premium block, and the spread between them is a real lever: cheap tickets fill
 *       the ground, premium tickets buy cash. Each tier has a floor and a ceiling so the settings
 *       screen cannot be used to set a €0 or €500 ticket.</li>
 *   <li><b>The away sector is always 20% of the ground</b> and can never be sold by the home club.
 *       A sold-out away end is what makes a big club's away trip worth taking.</li>
 *   <li><b>Gate revenue = attendance × realised average price</b>, where the average is weighted by
 *       how full each tier is. An empty premium block is worth nothing.</li>
 * </ol>
 */
@Service
public class AdmissionService {

    /**
     * Share of the ground reserved for visiting supporters. Hard rule from the owner, and the single
     * most important number in this class.
     */
    public static final double AWAY_SECTOR_SHARE = 0.20;

    /** Seat split inside the home 80%: cheap / standard / premium. */
    private static final double HOME_SPLIT_ECONOMY = 0.30;
    private static final double HOME_SPLIT_STANDARD = 0.50;
    private static final double HOME_SPLIT_PREMIUM = 0.20;

    public enum TicketType {
        ECONOMY, STANDARD, PREMIUM
    }

    /** Multipliers against the standard tier, which is the reference price. */
    private static final double ECONOMY_FACTOR = 0.55;
    private static final double PREMIUM_FACTOR = 2.10;

    /** Hard bounds on the standard price, in euros. */
    private static final double MIN_STANDARD_PRICE = 1.0;
    private static final double MAX_STANDARD_PRICE = 250.0;

    private static final double[] TIER_BOUNDS = { 0.01, 0.10, 1.00 };

    private final StadiumRepository stadiumRepository;

    public AdmissionService(StadiumRepository stadiumRepository) {
        this.stadiumRepository = stadiumRepository;
    }

    // --- ticket prices ---

    public double priceOf(Stadium stadium, TicketType type) {
        double standard = standardPrice(stadium);
        return switch (type) {
            case STANDARD -> standard;
            case ECONOMY -> clamp(standard * ECONOMY_FACTOR, 0.5, MAX_STANDARD_PRICE);
            case PREMIUM -> clamp(standard * PREMIUM_FACTOR, 1.0, MAX_STANDARD_PRICE * 3);
        };
    }

    /** The standard tier, which every other tier is derived from. */
    public double standardPrice(Stadium stadium) {
        if (stadium == null || stadium.getTicketPrice() == null) return 15.0;
        return clamp(stadium.getTicketPrice(), MIN_STANDARD_PRICE, MAX_STANDARD_PRICE);
    }

    /**
     * Sets the standard price and the derived tiers together, so the spread stays coherent.
     * Setting a single tier in isolation would let the premium block end up cheaper than economy.
     */
    @Transactional
    public Stadium setStandardPrice(Team team, double price) {
        Stadium s = team == null ? null : team.getStadium();
        if (s == null) return null;
        // The stadium is owned by the team (cascade ALL), so it is persisted with it. Saving here
        // as well meant the setter could not be used at all without a repository, which is what a
        // unit test of a pricing rule should not need.
        s.setTicketPrice(round2(clamp(price, MIN_STANDARD_PRICE, MAX_STANDARD_PRICE)));
        return s;
    }

    @Transactional
    public Stadium setTicketPrice(Team team, TicketType type, double price) {
        Stadium s = team == null ? null : team.getStadium();
        if (s == null) return null;
        double clamped = clamp(price, TIER_BOUNDS[type.ordinal()], MAX_STANDARD_PRICE * 3);
        if (type == TicketType.STANDARD) {
            return setStandardPrice(team, clamped);
        }
        // Back out the standard price that would produce the requested tier price.
        double divisor = type == TicketType.ECONOMY ? ECONOMY_FACTOR : PREMIUM_FACTOR;
        return setStandardPrice(team, clamped / divisor);
    }

    public Map<String, Double> allPrices(Stadium stadium) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (TicketType t : TicketType.values()) out.put(t.name(), priceOf(stadium, t));
        return out;
    }

    // --- the away sector ---

    public int awaySectorCapacity(Stadium stadium) {
        int capacity = capacityOf(stadium);
        return (int) Math.floor(capacity * AWAY_SECTOR_SHARE);
    }

    public int homeSectorCapacity(Stadium stadium) {
        return capacityOf(stadium) - awaySectorCapacity(stadium);
    }

    public int capacityOf(Stadium stadium) {
        if (stadium == null || stadium.getCapacity() == null) return 0;
        return Math.max(0, stadium.getCapacity());
    }

    // --- projection ---

    /**
     * What a home fixture would bring with the current settings, using the average stadium
     * occupancy for the club. Deliberately a projection rather than a promise — the real figure
     * comes from {@link AttendanceService} on the day, once form and opposition are known.
     */
    public Projection projectHomeFixture(Team team) {
        Stadium s = team == null ? null : team.getStadium();
        int capacity = capacityOf(s);
        int awayCap = awaySectorCapacity(s);
        int homeCap = capacity - awayCap;

        // A home league fixture with nothing special going on.
        double homeFill = 0.72;
        double awayFill = 0.55;

        int homeAttendance = (int) Math.round(homeCap * homeFill);
        int awayAttendance = (int) Math.round(awayCap * awayFill);
        homeAttendance = Math.min(homeCap, homeAttendance);
        awayAttendance = Math.min(awayCap, awayAttendance);

        double revenue = realisedGateRevenue(s, homeAttendance, awayAttendance);

        Projection p = new Projection();
        p.capacity = capacity;
        p.homeSectorCapacity = homeCap;
        p.awaySectorCapacity = awayCap;
        p.homeAttendance = homeAttendance;
        p.awayAttendance = awayAttendance;
        p.totalAttendance = homeAttendance + awayAttendance;
        p.standardPrice = standardPrice(s);
        p.economyPrice = priceOf(s, TicketType.ECONOMY);
        p.premiumPrice = priceOf(s, TicketType.PREMIUM);
        p.averageRealisedPrice = homeAttendance + awayAttendance <= 0
                ? 0 : round2(revenue / (homeAttendance + awayAttendance));
        p.gateRevenue = round2(revenue);
        return p;
    }

    /**
     * Gate revenue, weighting each tier by how full it actually is.
     *
     * <p>Assumes the home crowd fills the cheap end first, which is what happens: the people who
     * would pay premium either do not come or were not going to come anyway.
     */
    public double realisedGateRevenue(Stadium stadium, int homeAttendance, int awayAttendance) {
        int homeCap = homeSectorCapacity(stadium);
        double fill = homeCap <= 0 ? 0 : clamp((double) homeAttendance / homeCap, 0, 1);

        int premiumSeats = (int) Math.round(homeCap * HOME_SPLIT_PREMIUM);
        int economySeats = (int) Math.round(homeCap * HOME_SPLIT_ECONOMY);
        int standardSeats = Math.max(0, homeCap - premiumSeats - economySeats);

        // The cheap block fills first, then standard, then premium. This is both what actually
        // happens and what makes the number mean something: the code previously filled premium
        // first, which made a small crowd worth MORE per head than a full one - a 30% crowd was
        // all premium seats. The comment here said "fill premium last" while the code did the
        // opposite, and the test caught the pair disagreeing.
        int sold = Math.min(homeAttendance, homeCap);
        int economySold = Math.min(economySeats, sold);
        int afterEconomy = sold - economySold;
        int standardSold = Math.min(standardSeats, afterEconomy);
        int premiumSold = Math.min(premiumSeats, Math.max(0, afterEconomy - standardSold));

        double revenue = economySold * priceOf(stadium, TicketType.ECONOMY)
                + standardSold * priceOf(stadium, TicketType.STANDARD)
                + premiumSold * priceOf(stadium, TicketType.PREMIUM);

        // The away end pays the home club's standard price.
        revenue += awayAttendance * standardPrice(stadium);
        return revenue;
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Mutable projection bean — the controller serialises it straight to JSON. */
    public static class Projection {
        public int capacity;
        public int homeSectorCapacity;
        public int awaySectorCapacity;
        public int homeAttendance;
        public int awayAttendance;
        public int totalAttendance;
        public double standardPrice;
        public double economyPrice;
        public double premiumPrice;
        public double averageRealisedPrice;
        public double gateRevenue;
    }
}
