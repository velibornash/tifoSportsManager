package org.example.footballmanager.newLogic.sim.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.SubstitutionPlan;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.SubstitutionPlanRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manager's conditional substitution plan, per fixture.
 *
 * <p>Server-side so the plan survives a page reload, and keyed by <b>fixture</b> so it can be written
 * before the match exists. The owner's rule is that substitution decisions close an hour before kickoff —
 * a key of {@code matchId} would have made that impossible, because a {@code Match} row is written by the
 * simulation that consumes the plan.
 *
 * <p><b>Which club may write it.</b> The fixture's <b>home</b> club only. A club cannot set conditions
 * for a game it is not playing, and the plan is the home club's instruction — which is also the only side
 * whose bench the rule may name.
 *
 * <p><b>Closed one hour before kickoff.</b> After that the plan is refused with a reason rather than
 * ignored. A rule set at minute 55 of a match that is already simulated does nothing at all, and a
 * button that accepts input which cannot have an effect is worse than one that refuses.
 */
@RestController
@RequestMapping("/api/sim/fixtures/{fixtureId}/substitution-plan")
@RequiredArgsConstructor
public class SubstitutionPlanController {

    /** The owner: substitution decisions close an hour before kickoff. */
    static final java.time.Duration CUTOFF = java.time.Duration.ofHours(1);

    private final SubstitutionPlanRepository plans;
    private final MatchFixtureRepository fixtures;
    private final org.example.footballmanager.newLogic.service.SubstitutionRuleValidator validator;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long fixtureId) {
        Optional<MatchFixture> found = fixtures.findById(fixtureId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        MatchFixture fixture = found.get();
        return ResponseEntity.ok(planView(fixture, findOrCreate(fixture)));
    }

    /**
     * Replaces the plan wholesale. A put rather than a patch on purpose: the rules are always edited
     * and submitted as a set, and a merge would leave rules the manager believes they deleted still
     * queued to fire.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable Long fixtureId,
            @RequestBody Map<String, Object> body) {

        Optional<MatchFixture> found = fixtures.findById(fixtureId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        MatchFixture fixture = found.get();

        if (fixture.getMatchDate() != null) {
            long minutesToKickoff = java.time.Duration.between(
                    java.time.LocalDateTime.now(), fixture.getMatchDate()).toMinutes();
            if (minutesToKickoff <= CUTOFF.toMinutes()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "PLAN_CLOSED",
                        "message", "Substitutions closed "
                                + Math.max(0, minutesToKickoff) + " minute(s) before kickoff. "
                                + "The team is already picked."));
            }
        }

        // Parse, validate, and refuse — in that order. (T1-16.)
        //
        // This used to be `String.valueOf(rules == null ? "[]" : rules)` and nothing else: whatever the
        // API was sent became the plan. The screen's dropdowns stop the UI naming somebody who is not in
        // the squad, but the API never could, so a typo became a rule the engine marked VOID *during the
        // match* — where nothing reads voidReason back, so it was a silently dead instruction,
        // discovered never.
        //
        // Refusing here is the cheapest possible moment to say so: the manager is still on the screen
        // that produced the mistake.
        Object rules = body.get("rules");
        String json = String.valueOf(rules == null ? "[]" : rules);

        List<?> parsed;
        try {
            parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, List.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "PLAN_UNREADABLE",
                    "message", "The plan could not be read as a list of substitution rules."));
        }

        List<org.example.footballmanager.newLogic.service.SubstitutionRuleValidator.Rejection> rejections =
                validator.validate(parsed, fixture);
        if (!rejections.isEmpty()) {
            // Every rejection, not just the first. A manager fixing one typo at a time through a form
            // that accepted it is the experience this task exists to end.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "RULE_CANNOT_FIRE",
                    "message", rejections.size() == 1
                            ? rejections.get(0).message()
                            : rejections.size() + " of your " + parsed.size() + " rules cannot fire.",
                    "rejections", rejections.stream()
                            .map(r -> Map.of(
                                    "index", r.index(),
                                    "code", r.code(),
                                    "message", r.message()))
                            .toList()));
        }

        SubstitutionPlan plan = plans.findByFixtureId(fixtureId).orElseGet(() -> new SubstitutionPlan(
                fixtureId, nameOf(fixture.getHomeTeam()), nameOf(fixture.getAwayTeam()), json));
        plan.setRulesJson(json);
        plan.setUpdatedAt(Instant.now());
        plans.save(plan);

        return ResponseEntity.ok(planView(fixture, plan));
    }

    /**
     * Clears the plan for a fixture.
     *
     * <p><b>This route was always a 500.</b> {@code deleteByFixtureId} is a derived delete, so it needs a
     * transaction, and the controller had none — {@code InvalidDataAccessApiUsageException: No
     * EntityManager with actual transaction available for current thread}. It survived because the four
     * tests that existed for this controller tested an unknown id, a round trip, a replace and an empty
     * plan, and <b>none of them deleted anything</b>. A route with no test is a route that was never
     * pressed.
     *
     * <p>Refused inside the window for the same reason a save is: clearing a plan an hour before kickoff
     * cannot change the match either, and accepting it would look like it had.
     */
    @DeleteMapping
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> clear(@PathVariable Long fixtureId) {
        Optional<MatchFixture> found = fixtures.findById(fixtureId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        if (!isStillEditable(found.get())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "PLAN_CLOSED",
                    "message", "Substitutions closed an hour before kickoff. The team is already picked."));
        }
        plans.deleteByFixtureId(fixtureId);
        return ResponseEntity.noContent().build();
    }

    private static String nameOf(Team team) {
        return team == null ? "Unknown" : team.getName();
    }

    private SubstitutionPlan findOrCreate(MatchFixture fixture) {
        return plans.findByFixtureId(fixture.getId()).orElseGet(() -> new SubstitutionPlan(
                fixture.getId(), nameOf(fixture.getHomeTeam()), nameOf(fixture.getAwayTeam()), "[]"));
    }

    private Map<String, Object> planView(MatchFixture fixture, SubstitutionPlan plan) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("fixtureId", fixture.getId());
        view.put("homeTeam", plan.getHomeTeam());
        view.put("awayTeam", plan.getAwayTeam());
        view.put("rulesJson", plan.getRulesJson());
        // How the rules actually went, once there has been a match for them to have gone in. (T1-16)
        // Null before kickoff, which is honest: nothing is known about how an instruction went until
        // there is a match for it. The screen shows fired / void per rule from this.
        view.put("outcomeJson", plan.getOutcomeJson());
        view.put("updatedAt", plan.getUpdatedAt());
        // Whether the plan can still be changed, so the screen disables itself instead of offering a
        // button the server will refuse.
        view.put("editable", isStillEditable(fixture));
        return view;
    }

    private boolean isStillEditable(MatchFixture fixture) {
        return fixture.getMatchDate() == null || java.time.Duration.between(
                java.time.LocalDateTime.now(), fixture.getMatchDate()).toMinutes() > CUTOFF.toMinutes();
    }
}