package org.example.footballmanager.newLogic.dto;

/**
 * One row of a league table, as the manager reads it.
 *
 * <p>{@code rating} and {@code ratingDelta} are the club's Elo and the movement since its previous value
 * (owner, 2026-10-01), which is what makes a ranking row able to say "+3" or "−11" rather than only a
 * number. The points columns and the rating answer different questions — where a club is in the race,
 * and how strong it is — and a table that showed only the first would make the second invisible.
 *
 * <p>Both are nullable because "never rated" is a real state on a world whose replay has not run yet, and
 * it must be expressible rather than shown as zero — zero is a rating a club can hold.
 */
public record LeagueTableDTO(
        Long teamId,
        String name,
        Integer points,
        Integer goalsScored,
        Integer goalsConceded,
        Integer goalDifference,
        Integer wins,
        Integer draws,
        Integer losses,
        Integer position,
        Boolean humanControlled,
        Double rating,
        Double ratingDelta
) {}