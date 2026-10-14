package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.service.FriendlyRequestService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.data.domain.PageRequest;
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
    private final org.example.commonmanager.repository.UserRepository users;
    private final org.example.footballmanager.newLogic.repository.PlayerRepository players;

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
     * Who this club could play a friendly with: managers, clubs and players, together.
     *
     * <p>Owner, 2026-10-14: *"slicno kao kad pisemo poruku... u standardnoj tabeli, i klik na to sto si
     * nasao vodi na to sto si nasao"*. So the answer is no longer a flat list of clubs in your own
     * country — it is one list of things a manager might mean, each row saying what kind of thing it is
     * and, where there is a club behind it, which club. A manager is found by his name or his login; a
     * player is found by his name and answers with the club he plays for.
     *
     * <p><b>Every row carries the club it can be invited from</b> ({@code teamId}), because a friendly is
     * between two clubs and never between a club and a person. That is what makes a player row
     * actionable rather than merely informative, and it is why the invite button is offered rather than
     * the row simply opening the player.
     *
     * <p>Search is across the whole world rather than one country, on the same rule the message composer
     * uses: match anywhere in the string, case-insensitively. The league and country are returned so the
     * manager can judge the match before sending it.
     *
     * <p>National sides are excluded from the club rows. They are not opponents a club asks, and they
     * come through the national-team invitation path instead.
     */
    @GetMapping("/{teamId}/opponents")
    public ResponseEntity<List<Map<String, Object>>> opponents(@PathVariable Long teamId,
                                                              @RequestParam(required = false) String q,
                                                              @RequestParam(defaultValue = "60") int limit) {
        org.example.footballmanager.newLogic.model.Team team = teams.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.ok(List.of());
        }
        String fragment = q == null ? "" : q.trim().toLowerCase();
        int cap = Math.max(1, Math.min(limit, 100));
        // An empty box has nothing to rank, so it returns nothing rather than the whole world: this is
        // a search, not a browser, and 31,000 players is not a list anybody reads.
        if (fragment.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        PageRequest page = PageRequest.of(0, cap);
        List<Map<String, Object>> out = new ArrayList<>();

        for (org.example.footballmanager.newLogic.model.Team candidate
                : teams.findByNameFragment(fragment, page)) {
            if (candidate.getId() == null || candidate.getId().equals(teamId)) {
                continue;
            }
            if (candidate.getType() != null
                    && candidate.getType() == org.example.footballmanager.newLogic.model.CompetitionTeamType.NATIONAL_TEAM) {
                continue;
            }
            out.add(row("TEAM", candidate.getId(), candidate.getName(), describeTeam(candidate),
                    candidate.getId(), candidate.getName(),
                    leagueTarget(candidate), candidate.getCompetition() == null
                            ? null : candidate.getCompetition().getId()));
        }

        for (org.example.commonmanager.model.User manager
                : users.findByNameFragment(fragment, page)) {
            org.example.footballmanager.newLogic.model.Team club = manager.getFootballTeam();
            String label = manager.getDisplayName() != null && !manager.getDisplayName().isBlank()
                    ? manager.getDisplayName() : manager.getUsername();
            String detail = club == null ? "No club" : "Runs " + club.getName() + " · " + describeTeam(club);
            out.add(row("USER", manager.getId(), label, detail,
                    club == null ? null : club.getId(), club == null ? null : club.getName(),
                    "user", manager.getId()));
        }

        for (org.example.footballmanager.newLogic.model.Player player
                : players.findByNameFragment(fragment, page)) {
            org.example.footballmanager.newLogic.model.Team club = player.getTeam();
            String detail = club == null ? "No club" : club.getName() + " · " + describeTeam(club);
            out.add(row("PLAYER", player.getId(), player.getName(), detail,
                    club == null ? null : club.getId(), club == null ? null : club.getName(),
                    "player", player.getId()));
        }

        return ResponseEntity.ok(out);
    }

    /** A club row opens the competition it plays in, when it plays in one. */
    private static String leagueTarget(org.example.footballmanager.newLogic.model.Team team) {
        return team.getCompetition() == null ? null : "league";
    }

    private static String describeTeam(org.example.footballmanager.newLogic.model.Team team) {
        StringBuilder sb = new StringBuilder();
        if (team.getCompetition() != null && team.getCompetition().getName() != null) {
            sb.append(team.getCompetition().getName());
        } else if (team.getCountry() != null && team.getCountry().getName() != null) {
            sb.append(team.getCountry().getName());
        }
        return sb.toString();
    }

    private static Map<String, Object> row(String type, Object id, String name, String detail,
                                           Long teamId, String teamName, String openFor, Object openArg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("id", id);
        out.put("name", name);
        out.put("detail", detail);
        out.put("teamId", teamId);
        out.put("teamName", teamName);
        out.put("invitable", teamId != null);
        // Where clicking the row goes. There is no page that shows an arbitrary club — the club screen
        // shows the viewer's own club only — so a club row opens the league table it plays in, which is
        // where a manager is told to look for it anyway.
        out.put("openFor", openFor);
        out.put("openArg", openArg);
        return out;
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
