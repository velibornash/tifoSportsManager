package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.CompetitionEntry;

import java.util.Comparator;
import java.util.List;

/**
 * The one order a league table is in (owner decision, S8.4).
 *
 * <p>There were four implementations of it. All four agreed on the first two keys — points, then
 * goal difference — and disagreed after that:
 *
 * <ol>
 *   <li>points, goal difference, goals scored, team id — the most complete</li>
 *   <li>points, goal difference, goals scored — the table the manager reads</li>
 *   <li>points, goal difference, goals scored — the one that writes the stored position</li>
 *   <li>points, goal difference — <b>the playoff draw</b>, missing goals scored entirely</li>
 * </ol>
 *
 * <p>Two of the four fell over on a null. {@code getGoalsScored() - getGoalsConceded()} unboxes, so
 * an entry with no goals recorded threw rather than sorting, on the endpoint the manager actually
 * looks at.
 *
 * <p>What the disagreements cost:
 *
 * <ul>
 *   <li>Two clubs level on points and goal difference are ordered by goals scored on the table and
 *       arbitrarily in the playoff draw, so the stronger club can get the easier tie. That is the
 *       exact bug the playoff code's own comment says was already fixed once, by ordering the two
 *       runners-up against each other — and then reintroduced a few lines below, by a comparator
 *       that stops one key short.</li>
 *   <li>Only one of the four had a final tiebreaker on team id. Without it, two clubs level on
 *       everything are ordered by whatever order the repository returned them in, so the same table
 *       can render differently between two requests.</li>
 *   <li>One implementation <em>writes</em> {@code position} and another <em>reads</em> it, so the
 *       number in the teams list and the number in the table came from two different comparators.
 *       Both now come from this one.</li>
 * </ol>
 *
 * <p>Team id is the last key deliberately. It is arbitrary but it is <b>stable</b>, which is what a
 * tiebreak needs to be: the same table must produce the same order every time it is asked for.
 */
public final class LeagueTableOrder {

    private LeagueTableOrder() {
    }

    public static Comparator<CompetitionEntry> comparator() {
        return Comparator
                .comparingInt(LeagueTableOrder::points).reversed()
                .thenComparing(Comparator.comparingInt(LeagueTableOrder::goalDifference).reversed())
                .thenComparing(Comparator.comparingInt(LeagueTableOrder::goalsScored).reversed())
                .thenComparing(LeagueTableOrder::teamId);
    }

    /** @return the entries in league-table order. The input list is not modified. */
    public static List<CompetitionEntry> sort(List<CompetitionEntry> entries) {
        return entries.stream().sorted(comparator()).toList();
    }

    /**
     * A null is 0, not a crash.
     *
     * <p>Two of the four implementations this replaced threw on a null here. One of them was the
     * league table endpoint, so a club with a half-created entry took the page down.
     */
    public static int points(CompetitionEntry e) {
        return e.getPoints() == null ? 0 : e.getPoints();
    }

    public static int goalsScored(CompetitionEntry e) {
        return e.getGoalsScored() == null ? 0 : e.getGoalsScored();
    }

    public static int goalsConceded(CompetitionEntry e) {
        return e.getGoalsConceded() == null ? 0 : e.getGoalsConceded();
    }

    public static int goalDifference(CompetitionEntry e) {
        return goalsScored(e) - goalsConceded(e);
    }

    private static long teamId(CompetitionEntry e) {
        return e.getTeam() == null || e.getTeam().getId() == null ? Long.MAX_VALUE : e.getTeam().getId();
    }
}
