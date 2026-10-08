package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.StadiumSection;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.StadiumRepository;
import org.example.footballmanager.newLogic.repository.StadiumSectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Ticket tiers, the away sector, and what a home fixture is worth (Sprint 2.2).
 *
 * <p>Owns three rules that the rest of the economy depends on, kept here so there is exactly one
 * definition of each:
 *
 * <ol>
 *   <li><b>A seat costs what that seat's section costs.</b> A club built section by section prices eight
 *       products on one ground; the crowd fills the cheapest first, and an empty premium block is worth
 *       nothing. A ground whose sections were never built falls back to the three derived tiers, so no
 *       ground is ever unsellable.</li>
 *   <li><b>The away sector is always 20% of the ground</b> and can never be sold by the home club.
 *       A sold-out away end is what makes a big club's away trip worth taking.</li>
 *   <li><b>Gate revenue = attendance × realised average price</b>, where the average is weighted by
 *       how full each block is. An empty premium block is worth nothing.</li>
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
    private final StadiumSectionRepository sectionRepository;

    public AdmissionService(StadiumRepository stadiumRepository, StadiumSectionRepository sectionRepository) {
        this.stadiumRepository = stadiumRepository;
        this.sectionRepository = sectionRepository;
    }

    /**
     * One sellable block of seats, at one price.
     *
     * <p>The ladder used to be three of these, built from the three ticket tiers. A ground that has been
     * built section by section has eight instead, each priced by the club, and the same rule reads either:
     * the cheap block sells first.
     */
    public record PriceBlock(double price, int capacity) {
    }

    /**
     * This ground's blocks of seats, cheapest first.
     *
     * <p>The eight sections are the honest answer now that a club prices its own sections; the three
     * derived tiers are the fallback for a ground whose sections have never been built, so that a club
     * which has never opened the stadium page still has a ground that can sell tickets.
     *
     * <p>Section capacities are scaled to the home 80% rather than sold in full: the away sector is a
     * fifth of the ground whoever sits in it, so a section cannot sell away fans' seats twice.
     */
    public List<PriceBlock> priceLadder(Stadium stadium) {
        int total = capacityOf(stadium);
        int homeCap = homeSectorCapacity(stadium);

        List<PriceBlock> fromSections = sectionLadder(stadium, total, homeCap);
        if (!fromSections.isEmpty()) {
            return fromSections;
        }
        double share = total <= 0 ? 0 : (double) homeCap / total;
        int premiumSeats = (int) Math.round(homeCap * HOME_SPLIT_PREMIUM);
        int economySeats = (int) Math.round(homeCap * HOME_SPLIT_ECONOMY);
        int standardSeats = Math.max(0, homeCap - premiumSeats - economySeats);
        if (share <= 0) return List.of();
        // No share scaling here: these three blocks are already sized out of the home sector. Applying
        // the home share to them again took 20% off every block and left the sold seats unable to fill
        // the ground — a full house came out worth less than the same crowd with fewer cheap seats.
        return Stream.of(
                        new PriceBlock(priceOf(stadium, TicketType.ECONOMY), economySeats),
                        new PriceBlock(priceOf(stadium, TicketType.STANDARD), standardSeats),
                        new PriceBlock(priceOf(stadium, TicketType.PREMIUM), premiumSeats))
                .filter(b -> b.capacity() > 0)
                .sorted(Comparator.comparingDouble(PriceBlock::price))
                .toList();
    }

    /**
     * The sections that can actually be sold, priced cheapest first.
     *
     * <p>A null repository or a ground with no rows is a ground that has never been laid out section by
     * section, and the tier ladder takes over rather than this returning nothing.
     */
    private List<PriceBlock> sectionLadder(Stadium stadium, int total, int homeCap) {
        if (sectionRepository == null || stadium == null || stadium.getId() == null) {
            return List.of();
        }
        double share = total <= 0 ? 0 : (double) homeCap / total;
        if (share <= 0) return List.of();
        return sectionRepository.findByStadiumIdOrderByPositionAsc(stadium.getId()).stream()
                .filter(s -> s.getCapacity() != null && s.getCapacity() > 0)
                .map(s -> new PriceBlock(seatPrice(stadium, s), (int) Math.round(s.getCapacity() * share)))
                .filter(b -> b.capacity() > 0)
                .sorted(Comparator.comparingDouble(PriceBlock::price))
                .toList();
    }

    /**
     * What a seat in this section costs.
     *
     * <p>A section the club has not priced sells at the ground's own standard price. The alternative —
     * leaving an unpriced section out of the ladder — means a manager who sets one premium price
     * silently stops selling the six sections they never touched, which is a trap rather than a default.
     */
    private double seatPrice(Stadium stadium, StadiumSection section) {
        if (section.getTicketPrice() != null) {
            return section.getTicketPrice();
        }
        return standardPrice(stadium);
    }

    /**
     * What one seat costs on average across this ground, weighted by how many of them there are.
     *
     * <p>This is the price the crowd reacts to — {@code AttendanceService} runs its price elasticity off
     * it — so it is the eight sections' own prices, not one headline number.
     */
    public double demandPrice(Stadium stadium) {
        if (sectionRepository == null || stadium == null || stadium.getId() == null) {
            return standardPrice(stadium);
        }
        List<PriceBlock> ladder = sectionLadder(stadium, capacityOf(stadium), homeSectorCapacity(stadium));
        int seats = 0;
        double weighted = 0.0;
        for (PriceBlock block : ladder) {
            // The ladder is already scaled to the home sector, so its blocks are counted here too.
            seats += block.capacity();
            weighted += (double) block.capacity() * block.price();
        }
        return seats <= 0 ? standardPrice(stadium) : round2(weighted / seats);
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
     * Gate revenue, filling the cheapest seats first.
     *
     * <p>One rule over either ladder: this was written for three tiers and now reads the club's own eight
     * sections, because both are the same statement — the people who would not pay much come, and the
     * people who would pay a lot only come if there is anything left.
     *
     * <p>Filling premium first made a small crowd worth MORE per head than a full one, so a 30% crowd was
     * all premium seats. The old comment said "fill premium last" while the code did the opposite, and the
     * test caught the pair disagreeing. Cheapest-first is what keeps a near-empty ground honest.
     */
    public double realisedGateRevenue(Stadium stadium, int homeAttendance, int awayAttendance) {
        int homeCap = homeSectorCapacity(stadium);
        int sold = Math.min(homeAttendance, homeCap);

        double revenue = 0;
        int remaining = sold;
        for (PriceBlock block : priceLadder(stadium)) {
            if (remaining <= 0) break;
            int take = Math.min(block.capacity(), remaining);
            revenue += take * block.price();
            remaining -= take;
        }

        // The away end pays the home club's average seat price, which is what the home club's own
        // sections say one seat is worth.
        revenue += awayAttendance * demandPrice(stadium);
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
