package org.example.footballmanager.newLogic.sim.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.MatchLineupService;
import org.example.footballmanager.newLogic.service.SquadRegistrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The eleven a manager picks for one fixture.
 *
 * <p><b>The side comes from the session, never from the request</b> — the same rule as the tactical plan, and
 * for the same reason: a body carrying a team id would let a manager write a squad sheet for a club he
 * does not manage, and a squad sheet decides who is fit to play.
 *
 * <p><b>What the screen is given up front.</b> Every player who could be picked, with his availability and
 * — when he has one — the reason he is suspended, so the manager can see the state of his own squad before
 * choosing rather than after. The warnings come back on the same view, because a warning the manager can
 * only discover by submitting is a warning he will read too late.
 */
@RestController
@RequestMapping("/api/sim/fixtures/{fixtureId}/lineup")
@RequiredArgsConstructor
public class MatchLineupController {

    private final MatchFixtureRepository fixtures;
    private final MatchLineupService lineups;
    private final SquadRegistrationService squadRegistration;
    private final TeamRepository teams;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable Long fixtureId,
            @AuthenticationPrincipal org.example.commonmanager.model.User user) {

        Optional<MatchFixture> found = fixtures.findById(fixtureId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        MatchFixture fixture = found.get();

        Team club = clubOf(user);
        if (club == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "NO_TEAM",
                    "message", "Your account does not manage a club."));
        }
        if (!playsIn(fixture, club)) {
            return ResponseEntity.badRequest().body(Map.of("error", "NOT_IN_THIS_FIXTURE",
                    "message", "Your club is not playing in this fixture, so it has no team to pick."));
        }

        // The fixture's own XI if one is saved, and the standing template otherwise — which is also what
        // the simulation will use, so the screen starts from what would actually be fielded.
        Lineup chosen = lineups.resolve(club.getId(), fixture);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("fixtureId", fixture.getId());
        view.put("teamName", club.getName());
        view.put("opponentName", "HOME".equals(sideOf(fixture, club))
                ? nameOf(fixture.getAwayTeam()) : nameOf(fixture.getHomeTeam()));
        view.put("kickoff", fixture.getMatchDate() == null ? null : fixture.getMatchDate().toString());
        view.put("editable", !fixture.isPlayed());
        view.put("formation", chosen == null ? "4-4-2" : chosen.getFormation());
        view.put("starters", idsOf(chosen, true));
        view.put("bench", idsOf(chosen, false));
        // Whether this XI is saved for THIS fixture or is the club's standing template - so the screen can
        // say which one it is showing rather than leaving the manager to guess.
        view.put("perMatch", chosen != null && chosen.getMatch() != null);

        List<Map<String, Object>> players = new ArrayList<>();
        List<Player> squad = squadRegistration.availablePlayers(club.getId());
        if (squad != null) {
            for (Player player : squad) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", player.getId());
                row.put("name", player.getName());
                row.put("role", player.effectiveRole() == null ? null : player.effectiveRole().name());
                row.put("injured", player.isInjured());
                String suspension = lineups.suspensionOf(player, fixtureId).orElse(null);
                row.put("suspended", suspension != null);
                row.put("suspensionReason", suspension);
                players.add(row);
            }
        }
        view.put("players", players);

        List<Long> starterIds = idsOf(chosen, true);
        List<Long> benchIds = idsOf(chosen, false);
        view.put("warnings", lineups.warningsFor(club.getId(), fixtureId, starterIds, benchIds));
        return ResponseEntity.ok(view);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable Long fixtureId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal org.example.commonmanager.model.User user) {

        Optional<MatchFixture> found = fixtures.findById(fixtureId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        MatchFixture fixture = found.get();

        Team club = clubOf(user);
        if (club == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "NO_TEAM",
                    "message", "Your account does not manage a club."));
        }
        if (!playsIn(fixture, club)) {
            return ResponseEntity.badRequest().body(Map.of("error", "NOT_IN_THIS_FIXTURE",
                    "message", "Your club is not playing in this fixture."));
        }

        try {
            lineups.save(club.getId(), fixtureId,
                    longList(body.get("starters")), longList(body.get("bench")),
                    str(body.get("formation")), str(body.get("style")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "XI_INVALID", "message", e.getMessage()));
        }
        return get(fixtureId, user);
    }

    /** Drops this fixture's XI, so the club's template applies again. */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear(
            @PathVariable Long fixtureId,
            @AuthenticationPrincipal org.example.commonmanager.model.User user) {
        Optional<MatchFixture> found = fixtures.findById(fixtureId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        MatchFixture fixture = found.get();
        Team club = clubOf(user);
        String side = club == null ? null : sideOf(fixture, club);
        if (side != null && fixture.getPlayedMatch() != null && fixture.getPlayedMatch().getId() != null) {
            lineups.clearPerMatch(club.getId(), fixture.getPlayedMatch().getId());
        }
        return get(fixtureId, user);
    }

    private static List<Long> idsOf(Lineup lineup, boolean starters) {
        if (lineup == null) return List.of();
        return starters ? lineup.getOrderedStarterIds() : lineup.getOrderedBenchIds();
    }

    private Team clubOf(org.example.commonmanager.model.User user) {
        if (user == null) return null;
        if (user.getFootballTeam() != null && user.getFootballTeam().getId() != null) {
            return teams.findById(user.getFootballTeam().getId()).orElse(null);
        }
        if (user.getCTeam() != null && user.getCTeam().getName() != null) {
            return teams.findByName(user.getCTeam().getName()).orElse(null);
        }
        return null;
    }

    private static boolean playsIn(MatchFixture fixture, Team club) {
        return (fixture.getHomeTeam() != null && club.getId().equals(fixture.getHomeTeam().getId()))
                || (fixture.getAwayTeam() != null && club.getId().equals(fixture.getAwayTeam().getId()));
    }

    private static String sideOf(MatchFixture fixture, Team club) {
        if (fixture.getHomeTeam() != null && club.getId().equals(fixture.getHomeTeam().getId())) return "HOME";
        if (fixture.getAwayTeam() != null && club.getId().equals(fixture.getAwayTeam().getId())) return "AWAY";
        return null;
    }

    private static String nameOf(Team team) { return team == null ? "Unknown" : team.getName(); }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number n) {
                ids.add(n.longValue());
            } else if (item != null) {
                try {
                    ids.add(Long.parseLong(String.valueOf(item).trim()));
                } catch (NumberFormatException ignored) {
                    // A value the server cannot read as an id is dropped here and refused as a short
                    // eleven by the service, which says how many it got.
                }
            }
        }
        return ids;
    }
}