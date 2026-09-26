package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * When transfers are allowed (Sprint 3.3).
 *
 * <p>There was no window enforcement anywhere. A club could sign a striker in week 15 of the season,
 * mid-promotion-run-in, with no rule and no reason given — which removed the single largest
 * scheduling tension in a football management game. Registration days and the January window are
 * when a season is actually decided.
 *
 * <h2>The exceptions are the interesting part</h2>
 * Two things bypass the window, and both are real:
 * <ul>
 *   <li><b>Signing a free agent.</b> His contract has already expired; nobody is being deprived of
 *       anything by taking him, and refusing it would be absurd — a player cannot be forbidden from
 *       joining a club because it is March.</li>
 *   <li><b>Recalling a loan.</b> The recall is the <em>parent club's</em> right, exercised against
 *       the player's will, so a loan recall is never blocked by the window.</li>
 * </ul>
 * A <b>loan in</b> is not exempt: a club taking a player on loan in April is doing something a
 * manager should have to think about, and no league lets it.
 */
@Service
public class TransferWindowService {

    public enum Window {
        SUMMER, WINTER, CLOSED;

        /** Whether ordinary business happens in this window. */
        public boolean permitsBusiness() {
            return this != CLOSED;
        }
    }

    // The season shape lives in SeasonCalendar, which is the single definition. These are
    // re-exported rather than redefined so the window service and the fixture generator cannot
    // drift apart - which is exactly what happened when each held its own numbers.
    public static final int SEASON_WEEKS = SeasonCalendar.WEEKS_PER_SEASON;
    public static final int LEAGUE_END = SeasonCalendar.LEAGUE_END_WEEK;
    public static final int PLAYOFF_WEEK = SeasonCalendar.PLAYOFF_WEEK;

    /** Mid-season window: weeks 5 and 6. */
    public static final int SUMMER_OPEN = SeasonCalendar.MID_WINDOW_OPEN;
    public static final int SUMMER_CLOSE = SeasonCalendar.MID_WINDOW_CLOSE;

    /**
     * End-of-season window: weeks 11 and 12.
     *
     * <p>Opens at the start of the playoff week and closes at the end of the mid-season break, so a
     * club can rebuild during the off-season and the window is not shut until the break is over.
     */
    public static final int WINTER_OPEN = SeasonCalendar.END_WINDOW_OPEN;
    public static final int WINTER_CLOSE = SeasonCalendar.END_WINDOW_CLOSE;

    /** What a transfer would be. Decides which exception, if any, applies. */
    public enum Kind {
        /** A registered, listed player moving between clubs. */
        PERMANENT,
        /** Signing a player whose contract has expired. */
        FREE_AGENT,
        /**
         * Signing a player his club has released. Available at any time, same reasoning as a free
         * agent: the club has already given him away, so refusing the move would be refusing to let
         * a released man find a club.
         */
        RELEASED,
        /**
         * Signing a player the club has not listed and set no asking price on.
         *
         * <p>The owner's third category, and explicitly one to tune later. A club that has not put a
         * price on a player has not put him out of reach, so a club may still approach him directly
         * — but he is not for sale and cannot be bid on. What happens if that approach is refused is
         * the part flagged for later tuning.
         */
        UNLISTED,
        /** A club taking a player on loan. */
        LOAN_IN,
        /** A parent club pulling a loaned player back. */
        LOAN_RECALL;

        /** Whether this kind of move bypasses a closed window. */
        public boolean bypassesWindow() {
            return this == FREE_AGENT || this == RELEASED || this == UNLISTED || this == LOAN_RECALL;
        }
    }

    /**
     * The clock is read straight from its repository rather than through {@code SeasonService}.
     *
     * <p>{@code SeasonService} depends on {@code TransferService}, which needs this service to know
     * whether the market may run — so depending on {@code SeasonService} here closes a cycle and the
     * application context refuses to start. This service only ever wanted the current week.
     */
    private final GameClockRepository clocks;

    public TransferWindowService(GameClockRepository clocks) {
        this.clocks = clocks;
    }

    public Window currentWindow() {
        return windowForWeek(weekOf());
    }

    public static Window windowForWeek(Integer week) {
        if (week == null) return Window.CLOSED;
        if (SeasonCalendar.isMidSeasonWindow(week)) return Window.SUMMER;
        if (SeasonCalendar.isEndOfSeasonWindow(week)) return Window.WINTER;
        return Window.CLOSED;
    }

    public static boolean isOpenFor(Window window, Kind kind) {
        if (kind != null && kind.bypassesWindow()) return true;
        return window != Window.CLOSED;
    }

    /** Whether this kind of move is permitted right now, and if not, why not. */
    public Decision decide(Kind kind) {
        return decide(windowForWeek(weekOf()), kind, weekOf());
    }

    public static Decision decide(Window window, Kind kind) {
        return decide(window, kind, null);
    }

    /**
     * @param currentWeek used only to say when the window reopens; null says "not until next season"
     */
    public static Decision decide(Window window, Kind kind, Integer currentWeek) {
        boolean open = isOpenFor(window, kind);
        return new Decision(open, window, kind, describe(window, kind, open, currentWeek));
    }

    private static String describe(Window window, Kind kind, boolean open, Integer currentWeek) {
        if (open && kind != null && kind.bypassesWindow()) {
            return switch (kind) {
                case FREE_AGENT -> "His contract has run out, so there is nobody to disappoint. He can sign at any time.";
                case RELEASED -> "He has been released by his club, so he is available at any time.";
                case UNLISTED -> "No asking price has been set, so he is not for sale and cannot be bid on - but a club may still approach him directly.";
                case LOAN_RECALL -> "A recall is the parent club's right and is exercised against the player's will, so it is never blocked by the window.";
                default -> "";
            };
        }
        if (open) {
            return (window == Window.SUMMER ? "Summer" : "Winter") + " registration is open.";
        }
        Integer next = nextOpenWeek(currentWeek);
        String reopens = next == null
                ? "the window does not reopen until next season."
                : "the window reopens at week " + next + ".";
        return switch (kind) {
            case PERMANENT -> "The transfer window is shut. A listed player cannot move between clubs now; " + reopens;
            case LOAN_IN -> "The transfer window is shut. A club cannot take a player on loan now; " + reopens;
            case FREE_AGENT -> "He is a free agent and can sign at any time.";
            case RELEASED -> "He has been released and can sign at any time.";
            case UNLISTED -> "No asking price has been set, so he cannot be bid on, but a club may still approach him directly.";
            case LOAN_RECALL -> "A recall is the parent club's right and is not blocked by the window.";
        };
    }

    /**
     * Everything the transfer centre needs to show a countdown.
     *
     * <p>Deadline day is the point of the feature: a manager needs to know how long they have, and
     * what is about to shut.
     *
     * @return a map for the transfer centre, including the countdown and what stays allowed
     */
    public Map<String, Object> status() {
        GameClock clock = clocks.findAll().stream().findFirst().orElse(null);
        Integer week = clock == null ? null : clock.getCurrentWeek();
        Window window = windowForWeek(week);

        Integer opensOn = null;
        Integer closesOn = null;
        String nextLabel = null;

        if (window == Window.SUMMER) {
            closesOn = SeasonCalendar.MID_WINDOW_CLOSE;
            nextLabel = "the mid-season window closes at the end of week " + closesOn;
            opensOn = SeasonCalendar.END_WINDOW_OPEN;
        } else if (window == Window.WINTER) {
            closesOn = SeasonCalendar.END_WINDOW_CLOSE;
            nextLabel = "the end-of-season window closes at the end of week " + closesOn;
        } else {
            opensOn = nextOpenWeek(week);
            nextLabel = opensOn == null
                    ? "the window does not reopen until next season."
                    : "the window reopens at week " + opensOn + " of next season.";
        }

        Integer weeksLeft = closesOn == null || week == null ? null : Math.max(0, closesOn - week);
        boolean deadlineDay = week != null && closesOn != null && week.equals(closesOn);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", window.name());
        out.put("open", window != Window.CLOSED);
        out.put("week", week);
        out.put("closesOnWeek", closesOn);
        out.put("weeksLeft", weeksLeft);
        out.put("nextOpensOnWeek", opensOn);
        out.put("nextWindowLabel", nextLabel);
        out.put("deadlineDay", deadlineDay);
        out.put("deadlineDayLabel", deadlineDay
                ? "Deadline day. The window shuts after this week."
                : null);
        // Stated explicitly, because "the window is shut" without saying these still work is
        // exactly the kind of thing a manager works around wrongly.
        out.put("alwaysAllowed", List.of(
                "Signing a free agent whose contract has expired",
                "Signing a released player",
                "Approaching a player who has no asking price set",
                "Recalling a loan"));
        out.put("seasonWeeks", SEASON_WEEKS);
        return out;
    }

    private static Integer nextOpenWeek(Integer week) {
        if (week == null) return SUMMER_OPEN;
        if (week < SUMMER_OPEN) return SUMMER_OPEN;
        if (week < WINTER_OPEN) return WINTER_OPEN;
        return null;      // after the winter window, nothing reopens until next season
    }

    private Integer weekOf() {
        GameClock clock = clocks.findAll().stream().findFirst().orElse(null);
        return clock == null ? null : clock.getCurrentWeek();
    }

    /** The verdict, with the reason attached. */
    public record Decision(boolean permitted, Window window, Kind kind, String reason) { }
}
