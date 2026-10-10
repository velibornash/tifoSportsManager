package org.example.footballmanager.newLogic.sim.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.Tactic;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.service.MatchTacticAssignmentService;
import org.example.footballmanager.newLogic.service.TacticLibraryService;
import org.example.footballmanager.newLogic.sim.tactics.TacticMatchCondition;
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
 * The acting manager's tactical instructions for one fixture.
 *
 * <p><b>The side comes from the session, never from the request.</b> A manager sets instructions for their
 * own club, whichever side of the fixture that club happens to be on — that is the whole of what they are
 * entitled to do. A {@code side} in the body would be a manager setting the opposition's plan for them,
 * which the engine would then obey.
 *
 * <p><b>Closed once the match is played, like the substitution plan.</b> An instruction that arrives after
 * the final whistle cannot change the match, and a screen that accepted it would look like it had.
 */
@RestController
@RequestMapping("/api/sim/fixtures/{fixtureId}/tactic-plan")
@RequiredArgsConstructor
public class MatchTacticPlanController {

    private final MatchFixtureRepository fixtures;
    private final MatchTacticAssignmentService assignments;
    private final TacticLibraryService library;
    private final org.example.footballmanager.newLogic.repository.TeamRepository teams;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable Long fixtureId,
            @AuthenticationPrincipal org.example.commonmanager.model.User user) {

        Optional<MatchFixture> found = fixtures.findById(fixtureId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        MatchFixture fixture = found.get();

        Team club = clubOf(user);
        if (club == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "NO_TEAM", "message", "Your account does not manage a club."));
        }
        String side = sideOf(fixture, club);
        if (side == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "NOT_IN_THIS_FIXTURE",
                    "message", "Your club is not playing in this fixture, so it has no tactics to set."));
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("fixtureId", fixture.getId());
        view.put("side", side);
        view.put("teamId", club.getId());
        view.put("teamName", club.getName());
        view.put("opponentName", "HOME".equals(side)
                ? nameOf(fixture.getAwayTeam()) : nameOf(fixture.getHomeTeam()));
        view.put("kickoff", fixture.getMatchDate() == null ? null : fixture.getMatchDate().toString());
        view.put("editable", !fixture.isPlayed() && fixture.getMatchDate() != null
                && java.time.Duration.between(java.time.LocalDateTime.now(), fixture.getMatchDate())
                        .toMinutes() > 0);

        List<Map<String, Object>> available = new ArrayList<>();
        for (TacticLibraryService.TacticView tactic : library.view(club.getId())) {
            available.add(Map.of(
                    "id", tactic.id(),
                    "name", tactic.name(),
                    "formation", tactic.formation(),
                    "style", tactic.style(),
                    "isDefault", tactic.isDefault()));
        }
        view.put("tactics", available);

        List<Map<String, Object>> set = new ArrayList<>();
        for (var assignment : assignments.forSide(fixtureId, side)) {
            Tactic tactic = assignment.getTactic();
            set.add(Map.of(
                    "priority", assignment.getPriority(),
                    "condition", assignment.getCondition().name(),
                    "conditionLabel", assignment.getCondition().label(),
                    "minuteFrom", assignment.getMinuteFrom(),
                    "tacticId", tactic == null ? 0L : tactic.getId(),
                    "tacticName", tactic == null ? "removed" : tactic.getName()));
        }
        view.put("assignments", set);
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
        String side = sideOf(fixture, club);
        if (side == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "NOT_IN_THIS_FIXTURE",
                    "message", "Your club is not playing in this fixture."));
        }
        if (fixture.isPlayed()) {
            return ResponseEntity.badRequest().body(Map.of("error", "PLAN_CLOSED",
                    "message", "This fixture has been played. Its tactics are part of the record now."));
        }

        Long tacticId = asLong(body.get("tacticId"));
        Long priorityValue = asLong(body.get("priority"));
        int priority = priorityValue == null ? 1 : priorityValue.intValue();
        String conditionName = body.get("condition") == null ? "ALWAYS" : String.valueOf(body.get("condition"));

        TacticMatchCondition condition;
        try {
            condition = TacticMatchCondition.valueOf(conditionName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "UNKNOWN_CONDITION",
                    "message", "'" + conditionName + "' is not a condition the engine understands."));
        }

        try {
            assignments.assign(fixtureId, side, tacticId, priority, condition, asInteger(body.get("minuteFrom")));
        } catch (IllegalArgumentException e) {
            // The service refuses with the reason, and the reason is the message: it names the limit, the
            // club whose tactic it is, or the side. Swallowing it would give a form that accepts an
            // instruction and drops it.
            return ResponseEntity.badRequest().body(Map.of("error", "RULE_CANNOT_FIRE", "message", e.getMessage()));
        }
        return get(fixtureId, user);
    }

    /**
     * Empties one priority slot, or the whole plan when no priority is given.
     *
     * <p>The screen's "— none —" is a per-slot action, and clearing everything for it would take out the
     * two instructions the manager did want.
     */
    @DeleteMapping
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> clear(
            @PathVariable Long fixtureId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer priority,
            @AuthenticationPrincipal org.example.commonmanager.model.User user) {
        Optional<MatchFixture> found = fixtures.findById(fixtureId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        if (found.get().isPlayed()) {
            return ResponseEntity.badRequest().body(Map.of("error", "PLAN_CLOSED",
                    "message", "This fixture has been played."));
        }
        Team club = clubOf(user);
        String side = club == null ? null : sideOf(found.get(), club);
        if (side != null) {
            if (priority == null) {
                assignments.clear(fixtureId, side);
            } else {
                assignments.clearPriority(fixtureId, side, priority);
            }
        }
        return ResponseEntity.ok(get(fixtureId, user).getBody());
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

    /** Which side of the fixture this club is on, or null when it is not playing in it. */
    private String sideOf(MatchFixture fixture, Team club) {
        if (fixture.getHomeTeam() != null && club.getId().equals(fixture.getHomeTeam().getId())) return "HOME";
        if (fixture.getAwayTeam() != null && club.getId().equals(fixture.getAwayTeam().getId())) return "AWAY";
        return null;
    }

    private static String nameOf(Team team) { return team == null ? "Unknown" : team.getName(); }

    private static Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer asInteger(Object value) {
        Long parsed = asLong(value);
        return parsed == null ? null : parsed.intValue();
    }
}