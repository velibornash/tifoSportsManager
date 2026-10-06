package org.example.footballmanager.newLogic.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * League round to game slot, read from {@link SeasonCalendar} (owner, 2026-09-29).
 *
 * <p>The owner, on seeing the Country tab's calendar: "it's all clean, lock it into a template
 * schedule by day and then just fill it in as you make the draw, right?" Yes. The calendar already
 * knows that 18 league rounds fill weeks 1-5 and 7-10 at two rounds a week (day 3 and day 7), with
 * week 6 given to qualifiers, week 11 to the playoff and week 12 to the World Cup.
 *
 * <p>The fixture generator was computing its own round-to-week mapping instead of asking. It put
 * two rounds in a week correctly, and then the return-leg loop copied each fixture's week, so week 1
 * collected round 1, round 2 <b>and both of their return legs</b> - four rounds in a week and a club
 * playing four times. That is what produced the 775/2015 day split, and it was a real scheduling bug,
 * not a labelling one.
 *
 * <p>Now the calendar is the only source. The generator walks rounds in order and asks where each one
 * goes.
 */
public final class LeagueSlotSchedule {

    private LeagueSlotSchedule() {
    }

    /** One league round's place in the season. */
    public record Placement(int roundNumber, int week, int day) {
    }

    /**
     * Every league round the calendar schedules, in order.
     *
     * <p>Built by walking the calendar rather than by arithmetic, so a change to the calendar - a
     * different playoff week, a longer season - moves the fixtures with it instead of drifting away
     * from it.
     */
    public static Map<Integer, Placement> byRound() {
        Map<Integer, Placement> placements = new HashMap<>();
        for (int week = 1; week <= SeasonCalendar.WEEKS_PER_SEASON; week++) {
            // roundsIn returns the week's league rounds in slot order, so the league entries are day 3
            // and day 7 even though friendly slots now surround them. Reading slot order from the
            // calendar rather than from a week number is the whole point: the calendar is the
            // authority on what happens where, and it changes.
            List<Integer> rounds = SeasonCalendar.roundsIn(week);
            for (int index = 0; index < rounds.size(); index++) {
                Integer round = rounds.get(index);
                if (round == null) {
                    continue;
                }
                int slotNumber = SeasonCalendar.slots(week).stream()
                        .filter(slot -> slot.leagueRound() == round)
                        .map(SeasonCalendar.WeekSlot::slot)
                        .findFirst()
                        .orElse(-1);
                if (slotNumber > 0) {
                    placements.putIfAbsent(round, new Placement(round, week,
                            SeasonCalendar.dayForSlot(slotNumber)));
                }
            }
        }
        return placements;
    }

    /** Where a round goes, or null if the calendar schedules no such round. */
    public static Placement forRound(int roundNumber) {
        return byRound().get(roundNumber);
    }

    /** How many league rounds the calendar schedules across the whole season. */
    public static int totalRounds() {
        return byRound().size();
    }
}
