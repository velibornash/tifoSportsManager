package org.example.footballmanager.newLogic.model;

/**
 * Where the game is in its cycle: season, week, day, hour.
 *
 * <p>A value, not a cursor. Nothing in the game owns one of these and advances it; it is computed
 * from the clock every time it is needed, which is the whole point of {@link GameTimeline}.
 *
 * <p><b>The order is the owner's, not a calendar's</b> (2026-10-11): a season counts from 1, a week runs
 * 1..{@link SeasonCalendar#WEEKS_PER_SEASON} and a day runs 1..{@link GameDay#LAST}. Passing from day 7
 * to day 1 moves the week on by one; passing from week 12 to week 1 moves the season on by one. There is
 * no date, because a date is not a category in this world — the position on the timeline <em>is</em> the
 * time.
 */
public record GamePosition(int season, int week, int day, int hour) {

    public GamePosition {
        if (season < 1) {
            throw new IllegalArgumentException("Season counts from 1, was " + season + ".");
        }
        if (week < 1 || week > SeasonCalendar.WEEKS_PER_SEASON) {
            throw new IllegalArgumentException(
                    "Week must be 1.." + SeasonCalendar.WEEKS_PER_SEASON + ", was " + week + ".");
        }
        if (day < 1 || day > GameDay.LAST) {
            throw new IllegalArgumentException("Day must be 1.." + GameDay.LAST + ", was " + day + ".");
        }
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("Hour must be 0..23, was " + hour + ".");
        }
    }

    /** The game's own kind of day, which is what carries the kickoff hour. */
    public GameDay gameDay() {
        return GameDay.of(day);
    }

    @Override
    public String toString() {
        return "season " + season + ", week " + week + ", day " + day + ", " + hour + ":00";
    }
}
