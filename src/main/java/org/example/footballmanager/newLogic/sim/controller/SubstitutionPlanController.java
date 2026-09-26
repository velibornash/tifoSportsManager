package org.example.footballmanager.newLogic.sim.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.SubstitutionPlan;
import org.example.footballmanager.newLogic.repository.MatchRepository;
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
import java.util.Map;

/**
 * Manager's conditional substitution plan, per match.
 *
 * <p>Server-side and keyed by match. The plan has to survive a page reload mid-match: a manager who
 * sets "if we are losing at 60, bring on the striker" and then refreshes the page must not silently
 * lose the rule, and must not find it applied at a random moment instead.
 */
@RestController
@RequestMapping("/api/sim/matches/{matchId}/substitution-plan")
@RequiredArgsConstructor
public class SubstitutionPlanController {

    private final SubstitutionPlanRepository plans;
    private final MatchRepository matches;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long matchId) {
        Match match = matches.findById(matchId).orElse(null);
        if (match == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(planView(matchId, findOrCreate(matchId, match)));
    }

    /**
     * Replaces the plan wholesale. A put rather than a patch on purpose: the rules are always edited
     * and submitted as a set, and a merge would leave rules the manager believes they deleted still
     * queued to fire.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable Long matchId,
            @RequestBody Map<String, Object> body) {

        Match match = matches.findById(matchId).orElse(null);
        if (match == null) return ResponseEntity.notFound().build();

        Object rules = body.get("rules");
        String json = String.valueOf(rules == null ? "[]" : rules);

        SubstitutionPlan plan = plans.findByMatchId(matchId).orElseGet(
                () -> new SubstitutionPlan(matchId, match.getHomeTeam().getName(),
                        match.getAwayTeam().getName(), json));
        plan.setRulesJson(json);
        plan.setUpdatedAt(Instant.now());
        plans.save(plan);

        return ResponseEntity.ok(planView(matchId, plan));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@PathVariable Long matchId) {
        plans.deleteByMatchId(matchId);
        return ResponseEntity.noContent().build();
    }

    private SubstitutionPlan findOrCreate(Long matchId, Match match) {
        return plans.findByMatchId(matchId).orElseGet(
                () -> new SubstitutionPlan(matchId, match.getHomeTeam().getName(),
                        match.getAwayTeam().getName(), "[]"));
    }

    private Map<String, Object> planView(Long matchId, SubstitutionPlan plan) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("matchId", matchId);
        view.put("homeTeam", plan.getHomeTeam());
        view.put("awayTeam", plan.getAwayTeam());
        view.put("rulesJson", plan.getRulesJson());
        view.put("updatedAt", plan.getUpdatedAt());
        return view;
    }
}
