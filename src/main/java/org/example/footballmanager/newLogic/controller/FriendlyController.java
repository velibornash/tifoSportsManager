package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.service.FriendlyRequestService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Friendlies, for the club the manager runs.
 *
 * <p>There is deliberately no "play a friendly" button. A manager asks another club, and the other
 * club may refuse — the whole point is that an open week is a choice between ninety minutes and a
 * training session, and a button that just creates the match would take the choice away.
 *
 * <p>Every route is scoped to a club id rather than to "my team", because the club is the thing
 * being authorised. A manager who passes someone else's club id gets that club's view, which is
 * exactly what the transfer market already does.
 */
@RestController
@RequestMapping("/api/season/friendlies")
@RequiredArgsConstructor
public class FriendlyController {

    private final FriendlyRequestService friendlies;
    private final SeasonService seasons;

    /**
     * The club's week as the manager needs to see it: what is scheduled, which slots are open, what
     * it costs to fill them, and who is waiting for an answer.
     */
    @GetMapping("/{teamId}/week")
    public ResponseEntity<Map<String, Object>> week(@PathVariable Long teamId,
                                                   @RequestParam(required = false) Integer season,
                                                   @RequestParam(required = false) Integer week) {
        int resolvedSeason = season != null ? season : seasons.getActiveSeasonYear();
        int resolvedWeek = week != null ? week : seasons.getCurrentWeek();

        List<Integer> openSlots = friendlies.openSlots(teamId, resolvedSeason, resolvedWeek);
        int agreed = friendlies.friendliesAgreed(teamId, resolvedSeason, resolvedWeek);
        int available = friendlies.trainingSessionsAvailable(teamId, resolvedSeason, resolvedWeek);

        List<Map<String, Object>> options = new ArrayList<>();
        for (int slot : openSlots) {
            SeasonCalendar.WeekSlot spec = SeasonCalendar.slot(resolvedWeek, slot);
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("slot", slot);
            option.put("day", spec == null ? null : spec.day());
            option.put("kind", spec == null ? null : spec.kind().name());
            options.add(option);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamId);
        body.put("season", resolvedSeason);
        body.put("week", resolvedWeek);
        body.put("label", SeasonCalendar.describe(resolvedWeek));
        body.put("openSlots", options);
        body.put("friendliesAgreed", agreed);
        body.put("trainingSessionsAvailable", available);
        body.put("trainingSessionsBase", FriendlyRequestService.BASE_TRAINING_SESSIONS_PER_WEEK);
        body.put("inPlayoff", friendlies.isInPlayoff(teamId, resolvedSeason, resolvedWeek));
        body.put("incoming", friendlies.incoming(teamId, resolvedSeason, resolvedWeek));
        body.put("outgoing", friendlies.outgoing(teamId, resolvedSeason, resolvedWeek));
        return ResponseEntity.ok(body);
    }

    /**
     * Asks another club for a friendly in an open slot.
     *
     * <p>Returns 409 rather than a stack trace for the ordinary refusals — a slot already taken, a
     * club already playing, a week that has gone — because those are decisions a manager makes by
     * accident and deserves a readable reason for.
     */
    @PostMapping("/{teamId}/request")
    public ResponseEntity<?> request(@PathVariable Long teamId,
                                     @RequestParam Long opponentId,
                                     @RequestParam int week,
                                     @RequestParam int slot) {
        Optional<FriendlyRequest> created = friendlies.requestFriendly(teamId, opponentId, week, slot);
        if (created.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "That friendly cannot be arranged",
                    "detail", "The slot may be taken, already covered by a league match, "
                            + "or in the past. Check the open slots for the week."));
        }
        return ResponseEntity.ok(created.get());
    }

    /** Accepts or refuses a request addressed to this club. */
    @PostMapping("/{teamId}/requests/{requestId}")
    public ResponseEntity<?> respond(@PathVariable Long teamId,
                                    @PathVariable Long requestId,
                                    @RequestParam boolean accept,
                                    @RequestParam(required = false) String reason) {
        try {
            return ResponseEntity.ok(friendlies.respond(requestId, teamId, accept, reason));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** Withdraws a request this club made. */
    @PostMapping("/{teamId}/requests/{requestId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long teamId, @PathVariable Long requestId) {
        try {
            return ResponseEntity.ok(friendlies.cancel(requestId, teamId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }
}
