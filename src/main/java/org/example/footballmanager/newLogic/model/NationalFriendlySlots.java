package org.example.footballmanager.newLogic.model;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * When a national side may play a friendly (owner, 2026-10-06).
 *
 * <p><b>"NT friendly can only be in week 6, day 1."</b> One slot, stated in one place.
 *
 * <h2>Why national teams do not use the club slots</h2>
 *
 * <p>{@code SeasonCalendar.friendlySlots} is the league's two slots a week — day 3 and day 7 — and
 * {@link FriendlyRequestService} is written around them: slot 1 is the first, slot 2 the second, and
 * everything from the playoff rule to the training cost is indexed by that pair. A national side has
 * neither a league fixture nor a playoff, and more to the point <b>a club and a nation have no slot in
 * common</b>: day 3 of week 6 is a qualifying matchday, so the two cannot agree on when to play without
 * either a new lane or a collision with the tournament.
 *
 * <p>So a national friendly is <b>national against national, on week 6 day 1</b> — which is also the day
 * before the first qualifying matchday, and exactly the warm-up round the owner wanted. Nothing is
 * invented here: this class only says when it may happen and names the one slot.
 *
 * <h2>Why the slot number is 1</h2>
 *
 * <p>It is an index into <i>that team's</i> open slots, and a national side has exactly one. Keeping the
 * column the same as a club's means a request row carries the same meaning for both, and the service
 * that reads it does not need to know which lane it is in.
 */
public final class NationalFriendlySlots {

    private NationalFriendlySlots() {
    }

    /** The one week a national side may arrange a friendly in. */
    public static final int WEEK = 6;

    /** The one day of that week. The day before the first qualifying matchday. */
    public static final int DAY = 1;

    /** The one slot. An index into this lane's open slots, which has a single entry. */
    public static final int SLOT = 1;

    /** The kickoff: the template's day-1 international slot, 20:45. */
    public static final LocalTime KICKOFF = NationalTournamentSchedule.kickoffFor(DAY);

    /** Whether this week offers a national friendly at all. */
    public static boolean availableIn(int week) {
        return week == WEEK;
    }

    /**
     * A label for the screen and the log, so the one slot is never described three ways.
     */
    public static String describe(int week) {
        return availableIn(week)
                ? "Warm-up friendly, week " + WEEK + " day " + DAY
                : "No national friendly slot this week. Only week " + WEEK + " day " + DAY + " is available.";
    }

    /**
     * The date a national friendly is played, measured from the season start.
     *
     * <p>From the clock, for the reason every other fixture date in this codebase is: a hardcoded date
     * falls out of the recovery window once the clock passes it, and the symptom is zone loads written
     * and never read.
     */
    public static LocalDateTime matchDate(LocalDateTime seasonStart) {
        return NationalTournamentSchedule.matchDate(seasonStart, WEEK, DAY);
    }
}