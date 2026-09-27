package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.StadiumRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Building the ground out: capacity, seats, a roof, and the colours (owner request 2026-09-27).
 *
 * <p>Everything here costs money and everything here has a ceiling, because a stadium page where
 * every button is free is a page where nothing is a decision. The three that change how the ground
 * <b>works</b> are priced like construction; the colours are not, because a manager who has paid for
 * a roof is entitled to paint it.
 *
 * <p><b>Why expansion is capped and not open-ended.</b> An uncapped ground is a ground nobody has to
 * think about: the answer to every budget question is "more seats". {@code expandableTo} is the
 * finish line, and a club that reaches it has to get its next ground rather than growing this one
 * forever.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StadiumBuildService {

    /**
     * Base cost of a seat, before the ground's own size is taken into account.
     *
     * <p>Small on purpose, and scaled: this game's budgets are in the tens of thousands for a
     * municipal club and the millions for a top-flight one, so a single flat figure is either
     * meaningless at the top or impossible at the bottom. A fixed 850 per seat made the whole page
     * permanently refuse for the club the second manager actually plays for.
     */
    private static final double BASE_COST_PER_SEAT = 10.0;

    /** One more step per this many existing seats. A big ground's seats are dearer. */
    private static final int SEATS_PER_COST_STEP = 500;

    /** Replacing terraces with actual seats, per level of the 1-20 scale. */
    public static final double COST_PER_SEAT_LEVEL = 2_400.0;

    /**
     * A roof, as a one-off, scaled to the ground it goes on.
     *
     * <p>Set so that a municipal roof is about twenty weeks of income — a genuine long-term goal —
     * and a top-flight one is a serious purchase rather than a rounding error.
     */
    private static final double ROOF_PER_SEAT = 120.0;

    /** What one more seat costs this club, right now. */
    public static double costPerSeat(Stadium stadium) {
        int capacity = stadium == null || stadium.getCapacity() == null ? 0 : stadium.getCapacity();
        return BASE_COST_PER_SEAT + capacity / (double) SEATS_PER_COST_STEP;
    }

    /** Not less than this, so expansion is never free and never a rounding error. */
    private static final int MIN_EXPANSION = 250;

    private final StadiumRepository stadiums;
    private final TeamRepository teams;

    /**
     * What the next expansion step would cost, and how much room is left.
     *
     * @return {@code cost}, {@code maxCapacity}, {@code canExpand}, and why not when it cannot
     */
    public Map<String, Object> expansionQuote(Team team, int seats) {
        Map<String, Object> out = new LinkedHashMap<>();
        Stadium s = team == null ? null : team.getStadium();
        if (s == null) {
            out.put("canExpand", false);
            out.put("reason", "This club has no ground.");
            return out;
        }
        int current = s.getCapacity() == null ? 0 : s.getCapacity();
        int max = s.getExpandableTo() == null ? Integer.MAX_VALUE : s.getExpandableTo();
        int step = Math.max(MIN_EXPANSION, seats);

        if (current >= max) {
            out.put("canExpand", false);
            out.put("reason", "This ground is fully built out at " + current + " seats.");
            out.put("maxCapacity", current);
            return out;
        }
        int wanted = Math.min(current + step, max);
        out.put("canExpand", true);
        out.put("currentCapacity", current);
        out.put("maxCapacity", max == Integer.MAX_VALUE ? null : max);
        out.put("seatsAdded", wanted - current);
        out.put("cost", round((wanted - current) * costPerSeat(s)));
        return out;
    }

    /**
     * Adds seats, if the club can pay and the ground is not already at its ceiling.
     *
     * @return what happened: {@code expanded} with the new capacity, or {@code refused} with a reason
     */
    @Transactional
    public Map<String, Object> expand(Team team, int seats) {
        Map<String, Object> quote = expansionQuote(team, seats);
        if (!Boolean.TRUE.equals(quote.get("canExpand"))) {
            return refused(quote.get("reason") != null ? String.valueOf(quote.get("reason")) : "Cannot expand.");
        }
        double cost = ((Number) quote.get("cost")).doubleValue();
        double budget = team.getBudget() == null ? 0.0 : team.getBudget();
        if (cost > budget) {
            return refused("Not enough budget. This costs " + money(cost) + " and you have " + money(budget) + ".");
        }
        Stadium s = team.getStadium();
        int before = s.getCapacity() == null ? 0 : s.getCapacity();
        int after = before + ((Number) quote.get("seatsAdded")).intValue();
        s.setCapacity(after);
        team.setBudget(budget - cost);
        teams.save(team);
        stadiums.save(s);
        log.info("{} expanded {} from {} to {} seats for {}", team.getName(), s.getName(), before, after, money(cost));
        return mapOf("expanded", true, "capacity", after, "spent", cost);
    }

    /** What a roof costs this ground. A public one-off so the page can show the price. */
    public static double roofCost(Stadium stadium) {
        int capacity = stadium == null || stadium.getCapacity() == null ? 2_000 : stadium.getCapacity();
        return round(Math.max(40_000.0, capacity * ROOF_PER_SEAT));
    }

    /** The cost of lifting the seats one level, and the level above. */
    public Map<String, Object> seatQuote(Team team) {
        Stadium s = team == null ? null : team.getStadium();
        int level = s == null || s.getSeatQuality() == null ? 10 : s.getSeatQuality();
        int next = Math.min(20, level + 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level);
        out.put("nextLevel", next);
        out.put("cost", round((next - level) * COST_PER_SEAT_LEVEL * Math.max(0.4, s == null ? 1.0 : costPerSeat(s) / 20.0)));
        out.put("canImprove", level < 20);
        return out;
    }

    @Transactional
    public Map<String, Object> improveSeats(Team team) {
        Stadium s = team == null ? null : team.getStadium();
        if (s == null) return refused("This club has no ground.");
        Map<String, Object> quote = seatQuote(team);
        if (!Boolean.TRUE.equals(quote.get("canImprove"))) {
            return refused("The seats are as good as this ground will manage.");
        }
        double cost = ((Number) quote.get("cost")).doubleValue();
        double budget = team.getBudget() == null ? 0.0 : team.getBudget();
        if (cost > budget) {
            return refused("Not enough budget. Better seats cost " + money(cost) + " and you have " + money(budget) + ".");
        }
        s.setSeatQuality(((Number) quote.get("nextLevel")).intValue());
        team.setBudget(budget - cost);
        teams.save(team);
        stadiums.save(s);
        return mapOf("improved", true, "seatQuality", s.getSeatQuality(), "spent", cost);
    }

    /** A roof, once. There is no second roof to buy. */
    @Transactional
    public Map<String, Object> buildRoof(Team team) {
        Stadium s = team == null ? null : team.getStadium();
        if (s == null) return refused("This club has no ground.");
        if (s.isRoof()) return refused("This ground already has a roof.");
        double cost = roofCost(s);
        double budget = team.getBudget() == null ? 0.0 : team.getBudget();
        if (cost > budget) {
            return refused("A roof costs " + money(cost) + " and you have " + money(budget) + ".");
        }
        s.setRoof(true);
        team.setBudget(budget - cost);
        teams.save(team);
        stadiums.save(s);
        log.info("{} put a roof on {} for {}", team.getName(), s.getName(), money(cost));
        return mapOf("built", true, "roof", true, "spent", cost);
    }

    /**
     * Repaints the ground. Free.
     *
     * <p>Free because the expensive part already happened when the ground was built, and a manager
     * who has paid two and a half million for a roof is not going to be stopped by a colour picker.
     * Colours are validated rather than trusted, because they end up inside a style attribute.
     */
    @Transactional
    public Map<String, Object> paint(Team team, Map<String, String> colours) {
        Stadium s = team == null ? null : team.getStadium();
        if (s == null) return refused("This club has no ground.");
        if (colours == null || colours.isEmpty()) return refused("No colours were given.");

        int applied = 0;
        for (Map.Entry<String, String> entry : colours.entrySet()) {
            String clean = safeColour(entry.getValue());
            if (clean == null) continue;
            switch (entry.getKey()) {
                case "north" -> s.setNorthColour(clean);
                case "south" -> s.setSouthColour(clean);
                case "east" -> s.setEastColour(clean);
                case "west" -> s.setWestColour(clean);
                case "northEast" -> s.setNorthEastCornerColour(clean);
                case "northWest" -> s.setNorthWestCornerColour(clean);
                case "southEast" -> s.setSouthEastCornerColour(clean);
                case "southWest" -> s.setSouthWestCornerColour(clean);
                default -> { continue; }
            }
            applied++;
        }
        if (applied == 0) return refused("None of those colours were usable.");
        stadiums.save(s);
        return mapOf("painted", true, "coloursApplied", applied);
    }

    /**
     * A CSS colour, or null if it is not one.
     *
     * <p>Only {@code #rgb} and {@code #rrggbb} are accepted. These strings are interpolated into a
     * style attribute, and "validate it properly" means refusing anything that is not plainly a hex
     * colour rather than trying to parse a colour grammar.
     */
    static String safeColour(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.matches("^#[0-9a-fA-F]{3}$")) return v;
        if (v.matches("^#[0-9a-fA-F]{6}$")) return v;
        return null;
    }

    private Map<String, Object> refused(String reason) {
        return mapOf("refused", true, "error", reason);
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String money(double value) {
        return String.format("%,.0f", value);
    }
}
