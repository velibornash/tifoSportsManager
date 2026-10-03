package org.example.footballmanager.newLogic.dto;

import lombok.Data;

import java.util.List;

/**
 * A squad sheet as a caller sends one.
 *
 * <p><b>This exists because {@code Lineup} cannot be a request body.</b> The entity sits inside a
 * {@code @JsonManagedReference} graph — {@code Lineup} holds {@code List<Player>}, and {@code Player} holds a
 * managed reference with no matching back property — so Jackson cannot deserialise it at all. The symptom was
 * not a 400: {@code POST /lineups} answered
 * {@code HttpMediaTypeNotSupportedException: Content-Type 'application/json' is not supported} for every
 * caller, because no message converter would claim the body. The route looked alive and had never worked.
 *
 * <p>The shape is deliberately the one {@code TeamController}'s {@code lineup-template} already accepts —
 * {@code teamId}, {@code formation}, {@code style}, and ids rather than nested players — so there is one way to
 * describe a squad sheet in this game rather than two.
 */
@Data
public class LineupSaveRequestDTO {

    /** The club the sheet belongs to. Checked against the caller; never trusted. */
    private Long teamId;

    private String formation;

    private String style;

    /** Eleven ids, in the order they should play. */
    private List<Long> starterIds;

    /** Seven ids, in the order they should come on. */
    private List<Long> benchIds;
}