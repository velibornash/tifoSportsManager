package org.tifo.footballmanager.newLogic.sim.controller;

import lombok.RequiredArgsConstructor;
import org.tifo.footballmanager.newLogic.model.Lineup;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.service.MatchLineupService;
import org.tifo.footballmanager.newLogic.service.SquadRegistrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.tifo.footballmanager.newLogic.service.LineupSuggester;
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
    private final org.tifo.footballmanager.newLogic.service.MatchLockService lockReasons;
    private final LineupSuggester suggester;

    /**
     * The best available eleven and the rest of the squad, for one fixture.
     *
     * <p>The owner asked for a squad page and a manager twenty minutes before kickoff wants the eleven
     * filled in, not an empty form. This **suggests**; it does not save. The manager changes what he
     * wants to change and saves through the ordinary endpoint, so a suggestion he ignored never becomes a
     * lineup.
     *
     * <p>423 on a computed fixture, because a suggestion for a match already decided is worse than no
     * suggestion: it looks like an invitation.
     */
    @GetMapping("/suggest")
    public ResponseEntity<Map<String, Object>> suggest(
            @PathVariable Long fixtureId,
            @AuthenticationPrincipal org.tifo.commonmanager.model.User user) {

        java.util.Optional<MatchFixture> found = fixtures.findById(fixtureId);
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
        if (fixture.isPrepared()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.LOCKED)
                    .body(Map.of("error", "MATCH_CLOSED",
                            "message", lockReasons.refusalReason(fixture, club.getId())));
        }

        LineupSuggester.Suggestion suggestion = suggester.forTeam(club.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggested", suggestion.complete());
        out.put("starters", picks(suggestion.starters()));
        out.put("bench", picks(suggestion.bench()));
        out.put("warnings", suggestion.warnings());
        out.put("maxBench", MatchLineupService.MAX_BENCH);
        out.put("startersRequired", MatchLineupService.STARTERS);
        return ResponseEntity.ok(out);
    }

    private static List<Map<String, Object>> picks(List<LineupSuggester.Pick> picks) {
        List<Map<String, Object>> out = new ArrayList<>(picks.size());
        for (LineupSuggester.Pick pick : picks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", pick.id());
            row.put("name", pick.name());
            row.put("position", pick.position() == null ? null : pick.position().name());
            row.put("rating", pick.rating());
            out.add(row);
        }
        return out;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable Long fixtureId,
            @AuthenticationPrincipal org.tifo.commonmanager.model.User user) {

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
        // A prepared fixture is NOT played, so `!isPlayed()` said yes to every edit during the hour
        // before kickoff - which is exactly the hour the owner closed. The screen already honours this
        // flag, so the whole fix is here.
        view.put("editable", !fixture.isPlayed() && !fixture.isPrepared());
        if (fixture.isPrepared()) {
            view.put("lockReason", lockReasons.refusalReason(fixture, club.getId()));
            view.put("lockedAt", fixture.getPreparedAt());
        }
        view.put("formation", chosen == null ? "4-4-2" : chosen.getFormation());
        view.put("starters", idsOf(chosen, true));
        view.put("bench", idsOf(chosen, false));
        // The slot each starter occupies, index-aligned with `starters`. Sent so the page's per-slot
        // picker opens on what is actually stored rather than on every man's own position - which is the
        // fallback, and a lineup whose every slot reads MID is exactly what this column exists to stop.
        view.put("starterPositions", chosen == null
                ? List.of()
                : lineups.starterPositionsOf(chosen).stream().map(Position::name).toList());
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
            @AuthenticationPrincipal org.tifo.commonmanager.model.User user) {

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
        // The same lock as the tactic editor, asked at the fixture rather than the club: a manager with
        // two fixtures in a week is refused for the one that is decided and not for the one that is not.
        if (fixture.isPrepared()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.LOCKED)
                    .body(Map.of("error", "MATCH_CLOSED",
                            "message", lockReasons.refusalReason(fixture, club.getId())));
        }

        try {
            // The per-slot positions, which the model has held since it was added and which nothing ever
            // wrote: `save` had an overload taking them and no caller passed it, so a lineup saved from the
            // page fell back to each man's own position for every slot. The squad page's slot picker sends
            // them on every save, and a save without them leaves the positions alone rather than clearing
            // them - an older client sending starters and bench only must not wipe a manager's setup.
            lineups.save(club.getId(), fixtureId,
                    longList(body.get("starters")), longList(body.get("bench")),
                    str(body.get("formation")), str(body.get("style")),
                    listOf(body.get("starterPositions")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "XI_INVALID", "message", e.getMessage()));
        }
        return get(fixtureId, user);
    }

    /**
     * A list from the request body, or null when there is not one.
     *
     * <p>Null means "the client did not send it", which is different from an empty list and has to stay
     * distinguishable: an older client sending starters and bench only must not wipe the slot positions.
     * Anything that is not a list is treated as absent rather than refused, for the same reason.
     */
    private List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : null;
    }

    /** Drops this fixture's XI, so the club's template applies again. */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clear(
            @PathVariable Long fixtureId,
            @AuthenticationPrincipal org.tifo.commonmanager.model.User user) {
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

    private Team clubOf(org.tifo.commonmanager.model.User user) {
        if (user == null) return null;
        if (user.getFootballTeam() != null && user.getFootballTeam().getId() != null) {
            return teams.findById(user.getFootballTeam().getId()).orElse(null);
        }
        if (user.getCTeam() != null && user.getCTeam().getName() != null) {
            return teams.findFirstByNameOrderByIdAsc(user.getCTeam().getName()).orElse(null);
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