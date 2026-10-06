package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.example.footballmanager.newLogic.model.FriendlyRequest.FriendlyStatus;
import org.example.footballmanager.newLogic.model.NationalFriendlySlots;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.NationalFriendlyRequestService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * National-team warm-up requests, over HTTP (owner, 2026-10-06).
 *
 * <p>"NT friendly can only be in week 6 day 1" is the whole of the slot logic, so the endpoint does not
 * take a slot parameter at all — it takes a season and a week and refuses anything that is not that week,
 * which is a smaller contract and one that cannot be asked wrong.
 *
 * <p>Both sides of a warm-up are national sides, and the request is meaningful only for exactly one of
 * the two levels, so an opponent is named by national-team id rather than club id: a senior cannot warm
 * up against a U-21.
 */
@RestController
@RequestMapping("/api/national/friendly-requests")
public class NationalFriendlyController {

    private final NationalFriendlyRequestService service;
    private final SeasonService seasons;
    private final CountryRepository countries;
    private final TeamRepository teams;

    public NationalFriendlyController(NationalFriendlyRequestService service,
                                      SeasonService seasons,
                                      CountryRepository countries,
                                      TeamRepository teams) {
        this.service = service;
        this.seasons = seasons;
        this.countries = countries;
        this.teams = teams;
    }

    /** The one week and day a warm-up can be played. The page reads this rather than restating it. */
    @GetMapping("/slot")
    public Map<String, Object> slot() {
        return Map.of("week", NationalFriendlySlots.WEEK,
                "day", NationalFriendlySlots.DAY,
                "kickoff", NationalFriendlySlots.KICKOFF.toString());
    }

    /** Every national side of a level, for the opponent list. */
    @GetMapping("/opponents")
    public List<Map<String, Object>> opponents(@RequestParam(defaultValue = "senior") String level) {
        NationalTeamLevel resolved = parseLevel(level);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Country country : countries.findAll()) {
            Team side = resolved == NationalTeamLevel.U21
                    ? country.getU21NationalTeam() : country.getSeniorNationalTeam();
            if (side == null || side.getId() == null) {
                continue;
            }
            out.add(Map.of("id", side.getId(),
                    "name", side.getName() == null ? "National team" : side.getName(),
                    "country", country.getName()));
        }
        return out;
    }

    /** What is coming in and what has been sent, one side's view. */
    @GetMapping("/{teamId}")
    public Map<String, Object> view(@PathVariable Long teamId,
                                    @RequestParam(required = false) Integer season,
                                    @RequestParam(required = false) Integer week) {
        int s = season != null ? season : seasons.getActiveSeasonYear();
        int w = week != null ? week : NationalFriendlySlots.WEEK;
        return Map.of(
                "season", s,
                "week", w,
                "incoming", rows(service.incoming(teamId, s, w)),
                "outgoing", rows(service.outgoing(teamId, s, w)));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> request(@RequestParam Long requesterId,
                                                      @RequestParam Long opponentId,
                                                      @RequestParam(required = false) Integer week) {
        int w = week != null ? week : NationalFriendlySlots.WEEK;
        int season = seasons.getActiveSeasonYear();
        var maybe = service.requestFriendly(requesterId, opponentId, season, w);
        if (maybe.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "That warm-up cannot be arranged");
            body.put("detail", "A national friendly is only week " + NationalFriendlySlots.WEEK
                    + " day " + NationalFriendlySlots.DAY + ", both sides must be national sides, "
                    + "and neither may already have a match or a request on it.");
            return ResponseEntity.status(409).body(body);
        }
        return ResponseEntity.ok(rows(List.of(maybe.get())).get(0));
    }

    @PostMapping("/{requestId}/respond")
    public ResponseEntity<Map<String, Object>> respond(@PathVariable Long requestId,
                                                      @RequestParam Long teamId,
                                                      @RequestParam boolean accept) {
        try {
            FriendlyRequest r = service.respond(requestId, teamId, accept);
            return ResponseEntity.ok(rows(List.of(r)).get(0));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long requestId,
                                                     @RequestParam Long teamId) {
        try {
            FriendlyRequest r = service.cancel(requestId, teamId);
            return ResponseEntity.ok(rows(List.of(r)).get(0));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    private List<Map<String, Object>> rows(List<FriendlyRequest> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FriendlyRequest r : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("requesterTeamId", r.getRequesterTeamId());
            row.put("opponentTeamId", r.getOpponentTeamId());
            row.put("season", r.getSeason());
            row.put("week", r.getWeek());
            row.put("status", r.getStatus() == null ? null : r.getStatus().name());
            row.put("declineReason", r.getDeclineReason());
            out.add(row);
        }
        return out;
    }

    private NationalTeamLevel parseLevel(String level) {
        if (level == null) {
            return NationalTeamLevel.SENIOR;
        }
        return switch (level.toLowerCase()) {
            case "u21", "u-21", "under21" -> NationalTeamLevel.U21;
            default -> NationalTeamLevel.SENIOR;
        };
    }
}