package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.SeatingType;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.StadiumSection;
import org.example.footballmanager.newLogic.model.StandPosition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.StadiumRepository;
import org.example.footballmanager.newLogic.repository.StadiumSectionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The stadium built section by section (owner, 2026-10-07).
 *
 * <p>The owner's spec, verbatim: each of the four sides and each of the four corners is the same set of
 * choices — a <b>seating type</b>, a <b>capacity to add</b>, and a <b>roof over that section only</b> —
 * and the page returns the <b>price</b> and <b>how long the section is unusable</b>. Each of the eight
 * sections prices its seats separately, with a recommended price. Total capacity is the sum of the eight.
 *
 * <p>It used to be one block: a single capacity, one ticket price, one roof, one build button that spent
 * the money on the click. That could never answer the owner's question, which is always about a single
 * stand and never about the whole ground.
 *
 * <p><b>One number has one place it comes from.</b> {@link Stadium#getCapacity()} is recomputed from the
 * eight sections after every change, and so are {@code seatQuality} (the capacity-weighted comfort of what
 * was built) and {@code roof} (whether every section is covered). A capacity the sections cannot add up to
 * is a lie, and a column no code totals is how the two invariably drift apart.
 *
 * <p><b>Laying out the eight is the migration.</b> A ground built before this existed has one capacity and
 * no sections; the first time anyone asks for its sections it is split across all eight and priced from
 * the ground's own standard price, so no club loses a seat and no manager finds an empty ground. That
 * happens lazily on read rather than at boot — this codebase writes nothing on startup.
 */
@Service
public class StadiumSectionService {

    private static final double STANDING_BASE_RECOMMENDED = 8.0;
    private static final double BENCHES_BASE_RECOMMENDED = 15.0;
    private static final double SEATS_BASE_RECOMMENDED = 20.0;
    private static final double HEATED_BASE_RECOMMENDED = 34.0;

    /** One week of work fits this many extra seats in one section. */
    private static final int SEATS_PER_WEEK = 1500;

    /** What a roof over a section costs, per seat it then covers. */
    private static final double ROOF_COST_PER_SEAT = 60.0;

    /** But never a token roof: a corner cover is still a structure. */
    private static final double ROOF_MINIMUM = 15_000.0;

    /** What putting a roof up adds to the closure before any seating-type penalty. */
    private static final int ROOF_BASE_EXTRA_WEEKS = 2;

    private final StadiumSectionRepository sections;
    private final StadiumRepository stadiums;
    private final TeamRepository teams;

    public StadiumSectionService(StadiumSectionRepository sections, StadiumRepository stadiums,
                                 TeamRepository teams) {
        this.sections = sections;
        this.stadiums = stadiums;
        this.teams = teams;
    }

    /**
     * The eight sections of this ground: completed to eight, and the ground's own totals brought back
     * into line with them.
     *
     * <p>Idempotent, and safe on a ground that has never been asked about. A ground with no rows at all
     * is a legacy ground and gets its existing capacity divided across the eight; a ground with some rows
     * is missing rows and gets the empty ones added at zero seats, so a partial failure cannot leave a
     * ground that only has a north stand and no way to build a seventh.
     */
    @Transactional
    public List<StadiumSection> sectionsOf(Stadium stadium) {
        if (stadium == null || stadium.getId() == null) {
            return List.of();
        }
        List<StadiumSection> existing = sections.findByStadiumIdOrderByPositionAsc(stadium.getId());
        if (existing.isEmpty()) {
            layOutLegacyGround(stadium);
        } else {
            addMissingSections(stadium, existing);
        }
        recomputeDerived(stadium);
        stadiums.save(stadium);
        return sections.findByStadiumIdOrderByPositionAsc(stadium.getId());
    }

    /**
     * The recommendation for a seat in a section, before any markup.
     *
     * <p>Per seating type, because a heated seat and a folding chair are not the same ticket. It is the
     * number the manager sets their own price next to.
     */
    public double recommendedPrice(SeatingType type) {
        return switch (type == null ? SeatingType.SEATS : type) {
            case STANDING -> STANDING_BASE_RECOMMENDED;
            case BENCHES -> BENCHES_BASE_RECOMMENDED;
            case SEATS -> SEATS_BASE_RECOMMENDED;
            case HEATED -> HEATED_BASE_RECOMMENDED;
        };
    }

    /**
     * What a build on one section would cost and keep the section shut, <b>before any money moves</b>.
     *
     * <p>This is the owner's two answers: the price, and how long that one stand cannot be used. A
     * section is closed in full while it is rebuilt — there is no half-shut stand.
     *
     * @return price, weeks closed, the week it reopens, the section's and the ground's capacity after
     */
    public Map<String, Object> quote(Stadium stadium, StandPosition position, SeatingType seatingType,
                                     int capacityToAdd, boolean wantsRoof, int currentWeek) {
        if (stadium == null || stadium.getId() == null) {
            return mapOf("ok", false, "reason", "This club has no ground.");
        }
        if (position == null) {
            return mapOf("ok", false, "reason", "Pick one of the four sides or four corners.");
        }
        if (seatingType == null) {
            return mapOf("ok", false, "reason", "Pick a seating type.");
        }
        if (capacityToAdd < 0) {
            return mapOf("ok", false, "reason", "Capacity cannot shrink. A ground only grows here.");
        }
        if (capacityToAdd == 0 && !wantsRoof) {
            return mapOf("ok", false, "reason", "Nothing to build: choose some seats to add, or a roof.");
        }

        StadiumSection section = section(stadium, position);
        if (section.getSeatingType() != null && section.getSeatingType() != seatingType) {
            return mapOf("ok", false, "reason", "This section is already built with "
                    + section.getSeatingType().label().toLowerCase()
                    + ". A ground is not demolished section by section.");
        }

        int presentSeats = capacityOf(section);
        boolean newRoof = wantsRoof && !section.isRoof();
        int sectionCapacityAfter = presentSeats + capacityToAdd;

        int ceiling = stadium.getExpandableTo() == null ? Integer.MAX_VALUE : stadium.getExpandableTo();
        int totalNow = stadium.getCapacity() == null ? 0 : stadium.getCapacity();
        int totalAfter = totalNow + capacityToAdd;
        if (totalAfter > ceiling) {
            return mapOf("ok", false, "reason", "This ground is built out to " + ceiling
                    + " seats, and that would make it " + totalAfter + ".");
        }

        double workCost = capacityToAdd * StadiumBuildService.costPerSeat(stadium) * seatingType.costFactor();
        // Priced on what the roof then covers, not on what is there today: a roof over 2,000 fresh seats
        // is a bigger roof than a roof over the 200 already standing.
        double roofCost = newRoof
                ? Math.max(ROOF_MINIMUM, sectionCapacityAfter * ROOF_COST_PER_SEAT)
                : 0.0;
        double cost = round(workCost + roofCost);

        int weeksClosed = capacityToAdd <= 0 ? 0
                : Math.max(1, (int) Math.ceil(capacityToAdd / (double) SEATS_PER_WEEK));
        weeksClosed += newRoof ? ROOF_BASE_EXTRA_WEEKS + seatingType.roofBonusWeeks() : 0;

        return mapOf(
                "ok", true,
                "position", position.name(),
                "section", position.label(),
                "seatingType", seatingType.name(),
                "cost", cost,
                "workCost", round(workCost),
                "roofCost", round(roofCost),
                "roofAdded", newRoof,
                "capacityToAdd", capacityToAdd,
                "weeksClosed", weeksClosed,
                "closedUntilWeek", currentWeek + weeksClosed,
                "sectionCapacityAfter", sectionCapacityAfter,
                "totalCapacityAfter", totalAfter,
                "maxCapacity", ceiling == Integer.MAX_VALUE ? null : ceiling,
                "recommendedPrice", recommendedPrice(seatingType),
                "note", weeksClosed == 0
                        ? "Roof only: no seat is taken out of use."
                        : "The " + position.label().toLowerCase() + " section is closed to fans for about "
                                + weeksClosed + (weeksClosed == 1 ? " week." : " weeks.")
        );
    }

    /**
     * Spends the quote and closes that one section for the weeks it needs.
     *
     * @return what happened, including what the ground now holds
     */
    @Transactional
    public Map<String, Object> build(Team team, StandPosition position, SeatingType seatingType,
                                     int capacityToAdd, boolean wantsRoof, int currentSeason, int currentWeek) {
        Stadium stadium = team == null ? null : team.getStadium();
        Map<String, Object> quote = quote(stadium, position, seatingType, capacityToAdd, wantsRoof, currentWeek);
        if (!Boolean.TRUE.equals(quote.get("ok"))) {
            return refused(String.valueOf(quote.getOrDefault("reason", "Cannot build.")));
        }
        double cost = ((Number) quote.get("cost")).doubleValue();
        double budget = team.getBudget() == null ? 0.0 : team.getBudget();
        if (cost > budget) {
            return refused("Not enough budget. This costs " + money(cost) + " and you have " + money(budget) + ".");
        }

        StadiumSection section = section(stadium, position);
        section.setSeatingType(seatingType);
        section.setCapacity(capacityOf(section) + capacityToAdd);
        if (wantsRoof) {
            section.setRoof(true);
        }
        int weeks = ((Number) quote.get("weeksClosed")).intValue();
        if (weeks > 0) {
            section.setClosedUntilSeason(currentSeason);
            section.setClosedUntilWeek(currentWeek + weeks);
        }
        // A new section opens at the recommended price; an existing one keeps what the manager set.
        if (section.getTicketPrice() == null) {
            section.setTicketPrice(recommendedPrice(seatingType));
        }
        section.setRecommendedPrice(recommendedPrice(seatingType));
        sections.save(section);

        team.setBudget(round(budget - cost));
        teams.save(team);
        recomputeDerived(stadium);
        stadiums.save(stadium);

        return mapOf("built", true,
                "section", position.label(),
                "seatingType", seatingType.label(),
                "spent", cost,
                "weeksClosed", weeks,
                "closedUntilWeek", section.getClosedUntilWeek(),
                "sectionCapacity", section.getCapacity(),
                "newCapacity", stadium.getCapacity());
    }

    /**
     * Sets this section's ticket price on its own, with the recommendation still standing next to it.
     *
     * <p>Per section, because that is the owner's decision: eight products on one ground, priced apart.
     * A price that is not a price — negative, or nothing at all — is refused rather than stored.
     */
    @Transactional
    public Map<String, Object> setPrice(Team team, StandPosition position, Double price) {
        Stadium stadium = team == null ? null : team.getStadium();
        if (stadium == null || stadium.getId() == null) {
            return refused("This club has no ground.");
        }
        if (position == null) {
            return refused("Pick one of the four sides or four corners.");
        }
        if (price == null || !Double.isFinite(price) || price < 0) {
            return refused("A ticket price cannot be negative.");
        }
        StadiumSection section = section(stadium, position);
        if (capacityOf(section) == 0) {
            return refused("There is nothing to sell in this section until it is built.");
        }
        section.setTicketPrice(round(price));
        if (section.getRecommendedPrice() == null) {
            section.setRecommendedPrice(recommendedPrice(section.getSeatingType()));
        }
        sections.save(section);
        return mapOf("priced", true,
                "position", position.name(),
                "section", position.label(),
                "price", section.getTicketPrice(),
                "recommended", section.getRecommendedPrice());
    }

    /** Every section, as the page wants it: labels, prices, and whether it is shut right now. */
    @Transactional
    public List<Map<String, Object>> sectionViews(Stadium stadium) {
        List<StadiumSection> all = sectionsOf(stadium);
        return all.stream()
                .sorted(Comparator.comparingInt(s -> s.getPosition().ordinal()))
                .map(s -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("position", s.getPosition().name());
                    out.put("label", s.getPosition().label());
                    out.put("isSide", s.getPosition().name().indexOf('_') < 0);
                    out.put("capacity", capacityOf(s));
                    out.put("seatingType", s.getSeatingType() == null ? null : s.getSeatingType().name());
                    out.put("seatingLabel", s.getSeatingType() == null ? null : s.getSeatingType().label());
                    out.put("roof", s.isRoof());
                    out.put("ticketPrice", s.getTicketPrice());
                    out.put("recommendedPrice", s.getRecommendedPrice() != null
                            ? s.getRecommendedPrice()
                            : (s.getSeatingType() == null ? null : recommendedPrice(s.getSeatingType())));
                    out.put("closedUntilWeek", s.getClosedUntilWeek());
                    out.put("closedUntilSeason", s.getClosedUntilSeason());
                    return out;
                })
                .toList();
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Gives a ground its eight sections, from the capacity it already had.
     *
     * <p>A ground with no capacity has nothing to divide, so its eight sections start empty and unbuilt —
     * the manager chooses what goes in each one. A ground that <i>does</i> have a capacity is the legacy
     * case and is divided evenly, with the remainder handed out rather than rounded away, because
     * anything cleverer would be inventing a ground's shape.
     *
     * <p>The seating type comes from the ground's own seat quality, so a municipal terrace reads as a
     * terrace and not as premium seating, and an existing whole-ground roof is applied to all eight,
     * because that is what a whole-ground roof meant. Prices are deliberately left null: until a manager
     * prices a section the ground keeps selling on its own ticket tiers, so no existing club's gate income
     * moves because a table appeared.
     */
    private void layOutLegacyGround(Stadium stadium) {
        int capacity = stadium.getCapacity() == null ? 0 : Math.max(0, stadium.getCapacity());
        List<StandPosition> positions = List.of(StandPosition.values());
        if (capacity <= 0) {
            positions.forEach(position -> sections.save(emptySection(stadium, position)));
            return;
        }

        SeatingType type = typeForSeatQuality(stadium.getSeatQuality());
        int base = capacity / positions.size();
        int remainder = capacity % positions.size();
        for (int i = 0; i < positions.size(); i++) {
            StadiumSection section = emptySection(stadium, positions.get(i));
            section.setCapacity(i < remainder ? base + 1 : base);
            section.setSeatingType(type);
            section.setRoof(stadium.isRoof());
            section.setRecommendedPrice(recommendedPrice(type));
            sections.save(section);
        }
    }

    private StadiumSection emptySection(Stadium stadium, StandPosition position) {
        StadiumSection section = new StadiumSection();
        section.setStadium(stadium);
        section.setPosition(position);
        section.setCapacity(0);
        return section;
    }

    /** The missing rows, at zero seats, for a ground that somehow has fewer than eight. */
    private void addMissingSections(Stadium stadium, List<StadiumSection> existing) {
        EnumSet<StandPosition> present = EnumSet.noneOf(StandPosition.class);
        existing.forEach(s -> present.add(s.getPosition()));
        for (StandPosition position : StandPosition.values()) {
            if (!present.contains(position)) {
                sections.save(emptySection(stadium, position));
            }
        }
    }

    /**
     * The ground's own totals, recomputed from the eight sections that own them.
     *
     * <p>Capacity is the sum of the sections, because the owner's rule says so. Seat quality is the
     * capacity-weighted comfort of what was built, which gives a column that had been set by the world
     * builder and read by nothing an honest meaning.
     *
     * <p>The whole-ground roof flag is true only when all eight sections hold seats and every one of them
     * is covered. Counting only the built sections would report a ground with one roofed north stand as a
     * roofed ground, which is exactly the sort of green tick this codebase keeps being fooled by.
     */
    private void recomputeDerived(Stadium stadium) {
        List<StadiumSection> all = sections.findByStadiumIdOrderByPositionAsc(stadium.getId());
        int total = 0;
        int comfortWeighted = 0;
        for (StadiumSection s : all) {
            int seats = capacityOf(s);
            total += seats;
            if (seats <= 0) continue;
            comfortWeighted += seats * (s.getSeatingType() == null
                    ? SeatingType.STANDING.comfort() : s.getSeatingType().comfort());
        }
        stadium.setCapacity(total);
        if (total > 0) {
            // Weighted by seats, not by how many sections happen to be built: 1,000 terraces and
            // 100 heated seats are a ground that reads as standing, however many sections they sit in.
            stadium.setSeatQuality((int) Math.round((double) comfortWeighted / total));
        }
        stadium.setRoof(total > 0
                && all.stream().allMatch(s -> capacityOf(s) > 0 && s.isRoof())
                && all.size() == StandPosition.values().length);
    }

    /** A legacy ground's 1-20 seat quality, read as the seating type it amounts to. */
    private SeatingType typeForSeatQuality(Integer seatQuality) {
        int level = seatQuality == null ? 10 : Math.max(1, Math.min(20, seatQuality));
        if (level <= 6) return SeatingType.STANDING;
        if (level <= 11) return SeatingType.BENCHES;
        if (level <= 16) return SeatingType.SEATS;
        return SeatingType.HEATED;
    }

    /**
     * One section of one ground, creating the missing row on first ask.
     *
     * <p>Public because the page reads a single stand at a time — "the north side" is how a manager
     * thinks about it — and because it is the only handle on a row whose id nothing else knows.
     */
    @Transactional
    public StadiumSection section(Stadium stadium, StandPosition position) {
        Optional<StadiumSection> found = sections.findByStadiumIdAndPosition(stadium.getId(), position);
        if (found.isPresent()) {
            return found.get();
        }
        sectionsOf(stadium);
        return sections.findByStadiumIdAndPosition(stadium.getId(), position)
                .orElseThrow(() -> new IllegalStateException(
                        "No " + position + " section on stadium " + stadium.getId() + " and it would not create one"));
    }

    private static int capacityOf(StadiumSection section) {
        return section.getCapacity() == null ? 0 : Math.max(0, section.getCapacity());
    }

    private Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }

    private Map<String, Object> refused(String reason) {
        return mapOf("ok", false, "reason", reason);
    }

    private String money(double value) {
        return String.format("EUR %,.0f", value);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}