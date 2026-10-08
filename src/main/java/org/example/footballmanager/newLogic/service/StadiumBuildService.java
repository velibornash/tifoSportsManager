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

/**
 * The shared cost basis for construction, and the colouring of the ground (owner request 2026-09-27).
 *
 * <p><b>What moved out of here, and why.</b> This class used to own three buttons — "expand", "better
 * seats", "build a roof" — each spending the club's whole budget against one global capacity and one
 * global roof. That is the model the owner replaced on 2026-10-07: a ground is built one section at a
 * time, each with its own seating type, capacity and roof, and the total is the sum of the eight. Those
 * actions now live in {@link StadiumSectionService}, and they were removed from here rather than left
 * dormant, because a second route that can write {@code Stadium.capacity} is a second way for the total
 * and the sections to disagree.
 *
 * <p>What is left is the arithmetic both halves share — what one more seat costs this club — and the
 * colouring, which stays free: a manager who has paid for a stand is not going to be stopped by a colour
 * picker.
 *
 * <p><b>Why the per-seat cost scales.</b> Small on purpose and scaled: this game's budgets are in the tens
 * of thousands for a municipal club and the millions for a top-flight one, so a single flat figure is
 * either meaningless at the top or impossible at the bottom. A fixed 850 per seat made the whole page
 * permanently refuse for the club the second manager actually plays for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StadiumBuildService {

    /**
     * Base cost of a seat, before the ground's own size is taken into account.
     */
    private static final double BASE_COST_PER_SEAT = 10.0;

    /** One more step per this many existing seats. A big ground's seats are dearer. */
    private static final int SEATS_PER_COST_STEP = 500;

    private final StadiumRepository stadiums;
    private final TeamRepository teams;

    /** What one more seat costs this club, right now. */
    public static double costPerSeat(Stadium stadium) {
        int capacity = stadium == null || stadium.getCapacity() == null ? 0 : stadium.getCapacity();
        return BASE_COST_PER_SEAT + capacity / (double) SEATS_PER_COST_STEP;
    }

    /**
     * Repaints the ground. Free.
     *
     * <p>Free because the expensive part already happened when the ground was built. Colours are validated
     * rather than trusted, because they end up inside a style attribute.
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
}