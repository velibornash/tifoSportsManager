package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.model.CalendarEvent;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.model.WeekTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The weekly schedule (owner, 2026-09-28).
 *
 * <p>Serves the shared seven-day template so the schedule screen reads it from the server rather than
 * restating it in JavaScript. That is the same lesson as the country catalog, arrived at twice today:
 * a schedule written in two places stops being a schedule, and the two versions differ in ways only
 * one of them ever notices.
 *
 * <p>Events are empty until the draws and playoffs that use them are built. The shape is right now so
 * that when they land, the screen already has somewhere to put them.
 */
@RestController
@RequestMapping("/calendar")
public class CalendarController {

    private final org.example.footballmanager.newLogic.service.SeasonService seasonService;

    public CalendarController(org.example.footballmanager.newLogic.service.SeasonService seasonService) {
        this.seasonService = seasonService;
    }

    /**
     * The <b>current</b> week.
     *
     * <p>The endpoint the schedule screen actually calls. The client is not asked to say which week it
     * wants, because it cannot reliably know: the clock is server state, it moves when a manager
     * advances the week, and a client that guessed would show last week's schedule with total
     * confidence. Resolved here instead, where the clock is.
     */
    @GetMapping("/week")
    public Map<String, Object> getCurrentWeek() {
        return buildWeek(seasonService.getOrCreateClock().getCurrentWeek());
    }

    /**
     * One week, day 1 to day 7, with anything scheduled into it.
     *
     * <p>Out-of-range weeks are clamped to the season rather than rejected: a schedule screen should
     * show the nearest real week, not an error, because the only way to ask for a bad week is a
     * client that has got its arithmetic wrong.
     */
    /**
     * The whole season at a glance: all twelve weeks, each flagged with what makes it different.
     *
     * <p>This is what a manager needs before a season rather than during one. Weeks 6 and 12 are the
     * two that matter most and neither is obvious: both have no league football at all, so a manager
     * who does not know would read them as a bug.
     */
    @GetMapping("/season")
    public Map<String, Object> getSeason() {
        int current = seasonService.getOrCreateClock().getCurrentWeek();
        List<Map<String, Object>> weeks = new java.util.ArrayList<>();
        for (int week = 1; week <= SeasonCalendar.WEEKS_PER_SEASON; week++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", week);
            row.put("current", week == current);
            row.put("note", noteForWeek(week));
            // What is on each of the two league days, so the season reads as a shape rather than
            // twelve identical rows. "Rounds 3-4" is more use than "League".
            row.put("dayThree", roundsFor(week, 0));
            row.put("daySeven", roundsFor(week, 1));
            weeks.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seasonWeeks", SeasonCalendar.WEEKS_PER_SEASON);
        body.put("currentWeek", current);
        body.put("weeks", weeks);
        return body;
    }

    /** The league rounds in one of a week's two slots, as a short label. */
    private String roundsFor(int week, int slotIndex) {
        var slot = SeasonCalendar.slot(week, slotIndex + 1);
        if (slot == null) {
            return null;
        }
        return switch (slot.kind()) {
            case LEAGUE -> "Round " + slot.leagueRound();
            case PLAYOFF -> "Playoff";
            case FRIENDLY -> "Friendly";
            case FRIENDLY_IF_NOT_IN_PLAYOFF -> "Friendly (if not in playoff)";
        };
    }

    @GetMapping("/week/{week}")
    public Map<String, Object> getWeek(@PathVariable int week) {
        return buildWeek(week);
    }

    private Map<String, Object> buildWeek(int requested) {
        int resolved = Math.max(1, Math.min(SeasonCalendar.WEEKS_PER_SEASON, requested));

        List<CalendarEvent.DayView> days = CalendarEvent.weekWith(resolved, List.of());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seasonWeeks", SeasonCalendar.WEEKS_PER_SEASON);
        body.put("week", resolved);
        // Named so a screen can show "National-team qualifiers" on week 6 rather than an empty week.
        body.put("note", noteForWeek(resolved));
        body.put("days", days.stream().map(this::describe).toList());
        return body;
    }

    private Map<String, Object> describe(CalendarEvent.DayView day) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("day", day.day());
        row.put("kind", day.slot().kind().name());
        row.put("label", labelFor(day.slot().kind()));
        row.put("matchDay", day.hasMatch());
        row.put("kickoff", day.kickoff() == null ? null : day.kickoff().toString());
        row.put("events", day.events().stream().map(this::describeEvent).toList());
        return row;
    }

    private Map<String, Object> describeEvent(CalendarEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", event.type().name());
        row.put("label", event.label());
        row.put("kickoff", event.kickoff() == null ? null : event.kickoff().toString());
        return row;
    }

    /**
     * Plain-English name for a kind of day.
     *
     * <p>On the server so the screen does not ship a copy of this table. A client-side translation
     * would be a second answer to "what is day 6" and would eventually disagree.
     */
    private static String labelFor(WeekTemplate.DayKind kind) {
        return switch (kind) {
            case INTERNATIONAL -> "International";
            case FINANCE -> "Finance update";
            case LEAGUE -> "League";
            case TRAINING -> "Training";
            case CUP -> "Cup";
            case MORALE -> "Form & morale";
            case LEAGUE_SECOND -> "League";
        };
    }

    /**
     * The one thing a manager most needs to know about a week that is not an ordinary one.
     *
     * <p>Week 6 and week 12 have no league football and currently have no national-team football
     * either, so they would render as a blank week. Saying so beats showing seven days of nothing
     * and letting the manager wonder whether the game has broken.
     */
    private static String noteForWeek(int week) {
        if (week == SeasonCalendar.MIDSEASON_WEEK) {
            return "No league football this week. Reserved for national-team qualifiers, which are not built yet.";
        }
        if (week == SeasonCalendar.BREAK_WEEK) {
            return "No league football this week. Reserved for the World Cup, which is not built yet.";
        }
        if (week == SeasonCalendar.PLAYOFF_WEEK) {
            return "Playoff week. Clubs in the playoff play instead of a friendly.";
        }
        if (week == SeasonCalendar.MID_WINDOW_OPEN) {
            return "Last week of the first half. The mid-season transfer window opens at the end of it.";
        }
        if (week == SeasonCalendar.END_WINDOW_OPEN) {
            return "The end-of-season transfer window is open this week.";
        }
        return null;
    }
}
