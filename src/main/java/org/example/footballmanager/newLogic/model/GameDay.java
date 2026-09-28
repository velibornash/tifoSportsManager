package org.example.footballmanager.newLogic.model;

/**
 * The seven-day game cycle (owner, 2026-09-28).
 *
 * <p>Day 1 is INTERNATIONAL and day 5 is CUP, matching the order the owner gave when the weekly
 * schedule was specified: day 1 international 20:45, day 2 finance, day 3 league 19:00, day 4
 * training, day 5 cup 18:00, day 6 form and morale, day 7 league 16:00.
 *
 * <p>Day 7 and day 3 are both LEAGUE matches on purpose - two league rounds a week, at different
 * times. The enum names them {@link WeekTemplate.DayKind#LEAGUE} and
 * {@link WeekTemplate.DayKind#LEAGUE_SECOND} rather than reusing one constant, because a job
 * triggering on day 3 must be distinguishable from one triggering on day 7.
 */
public enum GameDay {

    DAY_1(1, WeekTemplate.DayKind.INTERNATIONAL, "International"),
    DAY_2(2, WeekTemplate.DayKind.FINANCE, "Finance"),
    DAY_3(3, WeekTemplate.DayKind.LEAGUE, "League"),
    DAY_4(4, WeekTemplate.DayKind.TRAINING, "Training"),
    DAY_5(5, WeekTemplate.DayKind.CUP, "Cup"),
    DAY_6(6, WeekTemplate.DayKind.MORALE, "Form and morale"),
    DAY_7(7, WeekTemplate.DayKind.LEAGUE_SECOND, "League");

    public static final int FIRST = 1;
    public static final int LAST = 7;

    private final int number;
    private final WeekTemplate.DayKind kind;
    private final String label;

    GameDay(int number, WeekTemplate.DayKind kind, String label) {
        this.number = number;
        this.kind = kind;
        this.label = label;
    }

    public int number() {
        return number;
    }

    public WeekTemplate.DayKind kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    public boolean isMatchDay() {
        return kind.matchDay();
    }

    /** The kind's kickoff hour, or null on a day with no fixture (finance, training, morale). */
    public Integer kickoffHour() {
        return kind.kickoff() == null ? null : kind.kickoff().getHour();
    }

    /**
     * The day for a 1..7 value.
     *
     * <p>Out-of-range input falls back to day 1 rather than throwing: a corrupt clock value should
     * not stop the game from starting, and a visible day-1 schedule is a far better failure than a
     * blank page. {@code null} maps to day 1 as well.
     */
    public static GameDay of(Integer number) {
        if (number == null) {
            return DAY_1;
        }
        for (GameDay day : values()) {
            if (day.number == number) {
                return day;
            }
        }
        return DAY_1;
    }

    /** The next day in the cycle, wrapping 7 -> 1. */
    public GameDay next() {
        return of(number == LAST ? FIRST : number + 1);
    }
}
