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
 *
 * <h2>Who runs the club (owner, 2026-10-05)</h2>
 *
 * <p>{@code managerUserId}, {@code managerName} and {@code managerHasChosenName} answer a question a league
 * table has always implied and never stated: whose club is this? Read through {@code User.footballTeam},
 * which is a real foreign key, and resolved in one query for the whole table rather than one per row.
 *
 * <p><b>The three are appended, not inserted.</b> A record's positional constructor is called positionally
 * in several places in this codebase, and inserting three fields in the middle would have silently shifted
 * points into goalDifference at every one of them. Appending keeps the existing calls correct and forces the
 * new fields to be named where they are read.
 *
 * <p>All nullable: a bot club has no manager, and "AI-run" has to be expressible rather than rendered as a
 * blank cell. {@code managerHasChosenName} separates "Velja" from "velibor@example.com", so the table does
 * not present a login address as though it were how he wants to be known.
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
        Double ratingDelta,
        Long managerUserId,
        String managerName,
        Boolean managerHasChosenName
) {}