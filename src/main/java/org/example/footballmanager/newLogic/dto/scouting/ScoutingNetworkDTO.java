package org.example.footballmanager.newLogic.dto.scouting;

import lombok.Data;

import java.util.List;

/**
 * A club's scouting network, and whether it is worth anything yet.
 *
 * <p>{@code totalReach} is deliberately separate from the list. A manager who has assigned three
 * scouts should be able to see at a glance that the network is thin, without adding up three numbers
 * — and a network with no assignments should say so rather than render an empty table.
 */
@Data
public class ScoutingNetworkDTO {

    private Long teamId;
    private List<ScoutAssignmentDTO> assignments;

    /** Sum of every posting's reach, 0-100 scale but not capped at 100. */
    private int totalReach;

    /** True when at least one country is covered. False means "you are not scouting". */
    private boolean hasCoverage;

    /**
     * What the network is currently worth, in one sentence. Empty string when there is no coverage,
     * because "no countries covered" is a state to act on rather than a sentence to read.
     */
    private String summary;
}
