package org.example.footballmanager.newLogic.dto.scouting;

import lombok.Data;

/**
 * One line of a club's scouting network, as the manager sees it.
 *
 * <p>{@code reach} is the whole point of the screen. A club can cover four countries and still learn
 * nothing, or cover one with an excellent scout and learn a great deal, and a list of country names
 * cannot tell the manager which of those two they have.
 */
@Data
public class ScoutAssignmentDTO {

    private Long assignmentId;
    private Long countryId;
    private String countryName;
    private String countryIsoCode;

    /** The country's {@code youthRating}, 45-95. Seeded since Sprint 2.1 and previously read by nothing. */
    private Integer countryYouthRating;

    private Long scoutId;
    private String scoutName;

    /** The scout's {@code scouting} attribute, 1-20. */
    private Integer scoutScouting;

    private Integer assignedSeasonNumber;

    /**
     * How much this single posting is worth, 0-100.
     *
     * <p>The product of a good scout and a rich country, not a sum of the two. A brilliant scout
     * watching a country with no talent pipeline and a poor scout watching the best pipeline on the
     * board both land near the middle, which is the honest answer: one factor cannot rescue the other.
     */
    private int reach;

    /** Plain-language reading of {@link #reach}, so the manager is not left to interpret a number. */
    private String reachLabel;
}
