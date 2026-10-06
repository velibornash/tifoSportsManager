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
import java.util.HashMap;
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
    private final org.example.footballmanager.newLogic.repository.TeamRepository teams;

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
        body.put("incoming", friendlyRows(friendlies.incoming(teamId, resolvedSeason, resolvedWeek), teamId, true));
        body.put("outgoing", friendlyRows(friendlies.outgoing(teamId, resolvedSeason, resolvedWeek), teamId, false));
        return ResponseEntity.ok(body);
    }

    /**
     * A request as the client needs it, which the entity is not.
     *
     * <p>{@link FriendlyRequest} carries two team <b>ids</b> and no names, so a screen rendering a
     * request can only show a number. Resolved here rather than in the service because the service
     * returns its own entity everywhere else and a screen-shaped record there would be a second
     * representation of the same thing.
     *
     * <p>Names are read once per request and memoised, so ten requests between two clubs cost two lookups
     * rather than twenty. An unknown id is carried through as null and rendered as "Unknown club" rather
     * than being dropped: a request whose team has since been deleted still exists and still has to be
     * answerable.
     */
    private List<Map<String, Object>> friendlyRows(List<FriendlyRequest> list, Long teamId, boolean incoming) {
        Map<Long, String> names = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (FriendlyRequest request : list) {
            Long otherId = incoming ? request.getRequesterTeamId() : request.getOpponentTeamId();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", request.getId());
            row.put("season", request.getSeason());
            row.put("week", request.getWeek());
            row.put("slot", request.getSlot());
            row.put("status", request.getStatus() == null ? null : request.getStatus().name());
            row.put("declineReason", request.getDeclineReason());
            row.put("opponentTeamId", otherId);
            row.put("otherName", nameOf(names, otherId));
            out.add(row);
        }
        return out;
    }

    private String nameOf(Map<Long, String> names, Long teamId) {
        if (teamId == null) {
            return null;
        }
        return names.computeIfAbsent(teamId,
                id -> teams.findById(id).map(org.example.footballmanager.newLogic.model.Team::getName).orElse(null));
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

    /**
     * Clubs this one could ask for a friendly, narrowed to its own country.
     *
     * <p><b>Why this exists rather than reusing {@code GET /teams}.</b> That endpoint pages at a hard
     * cap of 200 rows, and the world holds roughly 14,880 clubs. A picker fed from it would offer the
     * first 200 alphabetically and nothing else, which looks like a working search over an empty world.
     *
     * <p>National sides are excluded. They are not opponents a club asks, and they are picked up by the
     * national-team invitation path instead; mixing them here would put 96 entries in a club's list that
     * could never be sent to.
     *
     * @param q an optional name filter; empty returns the first page of the country
     */
    @GetMapping("/{teamId}/opponents")
    public ResponseEntity<List<Map<String, Object>>> opponents(@PathVariable Long teamId,
                                                              @RequestParam(required = false) String q,
                                                              @RequestParam(defaultValue = "60") int limit) {
        org.example.footballmanager.newLogic.model.Team team = teams.findById(teamId).orElse(null);
        if (team == null || team.getCountry() == null) {
            return ResponseEntity.ok(List.of());
        }
        int cap = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> out = new ArrayList<>();
        for (org.example.footballmanager.newLogic.model.Team candidate
                : teams.findByCountryId(team.getCountry().getId())) {
            if (candidate.getId() == null || candidate.getId().equals(teamId)) {
                continue;
            }
            if (candidate.getType() != null
                    && candidate.getType() == org.example.footballmanager.newLogic.model.CompetitionTeamType.NATIONAL_TEAM) {
                continue;
            }
            if (q != null && !q.isBlank()
                    && (candidate.getName() == null
                        || !candidate.getName().toLowerCase().contains(q.toLowerCase()))) {
                continue;
            }
            out.add(Map.of("id", candidate.getId(), "name", candidate.getName()));
            if (out.size() >= cap) {
                break;
            }
        }
        return ResponseEntity.ok(out);
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
