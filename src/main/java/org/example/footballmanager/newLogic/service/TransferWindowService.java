package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.GameClock;
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
        SUMMER, WINTER, CLOSED
    }

    /** Summer registration: the first weeks of a season. */
    public static final int SUMMER_OPEN = 1;
    public static final int SUMMER_CLOSE = 6;

    /** Winter registration: the mid-season window. */
    public static final int WINTER_OPEN = 10;
    public static final int WINTER_CLOSE = 12;

    /** What a transfer would be. Decides which exception, if any, applies. */
    public enum Kind {
        /** A registered player moving between clubs. */
        PERMANENT,
        /** Signing a player with no club. */
        FREE_AGENT,
        /** A club taking a player on loan. */
        LOAN_IN,
        /** A parent club pulling a loaned player back. */
        LOAN_RECALL
    }

    private final SeasonService seasons;

    public TransferWindowService(SeasonService seasons) {
        this.seasons = seasons;
    }

    public Window currentWindow() {
        return windowForWeek(weekOf());
    }

    public static Window windowForWeek(Integer week) {
        if (week == null) return Window.CLOSED;
        if (week >= SUMMER_OPEN && week <= SUMMER_CLOSE) return Window.SUMMER;
        if (week >= WINTER_OPEN && week <= WINTER_CLOSE) return Window.WINTER;
        return Window.CLOSED;
    }

    public static boolean isOpenFor(Window window, Kind kind) {
        if (kind == Kind.FREE_AGENT || kind == Kind.LOAN_RECALL) return true;
        return window != Window.CLOSED;
    }

    /** Whether this kind of move is permitted right now, and if not, why not. */
    public Decision decide(Kind kind) {
        return decide(currentWindow(), kind);
    }

    public static Decision decide(Window window, Kind kind) {
        boolean open = isOpenFor(window, kind);
        return new Decision(open, window, kind, describe(window, kind, open));
    }

    private static String describe(Window window, Kind kind, boolean open) {
        if (open && (kind == Kind.FREE_AGENT || kind == Kind.LOAN_RECALL)) {
            return switch (kind) {
                case FREE_AGENT -> "He is a free agent, so there is nobody to disappoint. He can sign at any time.";
                case LOAN_RECALL -> "A recall is the parent club's right and is exercised against the player's will, so it is never blocked by the window.";
                default -> "";
            };
        }
        if (open) {
            return (window == Window.SUMMER ? "Summer" : "Winter") + " registration is open.";
        }
        return switch (kind) {
            case PERMANENT -> "The transfer window is shut. A registered player cannot move between clubs now; "
                    + "the window reopens at week " + WINTER_OPEN + ".";
            case LOAN_IN -> "The transfer window is shut. A club cannot take a player on loan now; "
                    + "the window reopens at week " + WINTER_OPEN + ".";
            case FREE_AGENT -> "He is a free agent and can sign at any time.";
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
        GameClock clock = seasons.getOrCreateClock();
        Integer week = clock == null ? null : clock.getCurrentWeek();
        Window window = windowForWeek(week);

        Integer opensOn = null;
        Integer closesOn = null;
        String nextLabel = null;

        if (window == Window.SUMMER) {
            closesOn = SUMMER_CLOSE;
            nextLabel = "the summer window closes after week " + SUMMER_CLOSE;
            opensOn = WINTER_OPEN;
        } else if (window == Window.WINTER) {
            closesOn = WINTER_CLOSE;
            nextLabel = "the winter window closes after week " + WINTER_CLOSE;
        } else {
            opensOn = nextOpenWeek(week);
            nextLabel = opensOn == null ? null : "the window reopens at week " + opensOn;
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
        out.put("alwaysAllowed", List.of("Signing a free agent", "Recalling a loan"));
        return out;
    }

    private Integer nextOpenWeek(Integer week) {
        if (week == null) return SUMMER_OPEN;
        if (week < SUMMER_OPEN) return SUMMER_OPEN;
        if (week < WINTER_OPEN) return WINTER_OPEN;
        return null;      // after the winter window, nothing reopens until next season
    }

    private Integer weekOf() {
        GameClock clock = seasons.getOrCreateClock();
        return clock == null ? null : clock.getCurrentWeek();
    }

    /** The verdict, with the reason attached. */
    public record Decision(boolean permitted, Window window, Kind kind, String reason) { }
}
