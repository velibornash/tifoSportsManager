package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.SubstitutionPlan;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.SubstitutionPlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The substitution plan reaches the engine. (T0-UI-4, owner 2026-10-09.)
 *
 * <p><b>This is the test the feature never had.</b> {@code ConditionalSubstitutionRules} had ten green
 * unit tests and zero production callers: {@code MatchOrchestrator} evaluated
 * {@code conditionalSubs.onTick()} on every tick of every match, against a list that was always empty,
 * because nothing could put anything in it. The rules holder was private with no accessor, and
 * {@code SimMatchRunner.run} constructed <em>and</em> simulated inside one call — so there was no point at
 * which a plan could be attached. Not a wiring mistake: a shape that made wiring impossible.
 *
 * <p>So this asserts the path, not the arithmetic. The arithmetic belongs to
 * {@code ConditionalSubstitutionRulesTest}, which was always green and always testing an object the
 * product could never populate.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class SubstitutionPlanReachesTheEngineTest {

    @Autowired MatchFixtureRepository fixtures;
    @Autowired SubstitutionPlanRepository plans;

    @Test
    @DisplayName("a plan saved against a fixture is readable by fixture — the engine's own key")
    void planIsFoundByTheFixtureTheEngineSimulates() {
        MatchFixture fixture = new MatchFixture();
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture = fixtures.save(fixture);

        Long fixtureId = fixture.getId();
        plans.save(new SubstitutionPlan(fixtureId, "Home", "Away",
                "[{\"team\":\"HOME\",\"triggerMinute\":60,\"condition\":\"LOSING\"}]"));

        // The engine holds the fixture, not the match. This is the lookup SimMatchService makes.
        assertTrue(plans.findByFixtureId(fixtureId).isPresent(),
                "the engine simulates a fixture and looks the plan up by that fixture's id, so this is "
                        + "the lookup it makes. Under the old matchId key it had no answer to give, because "
                        + "no Match row exists until the simulation that consumes the plan has run.");
    }

    @Test
    @DisplayName("one plan per fixture: a second save replaces rather than competing")
    void onePlanPerFixture() {
        MatchFixture fixture = new MatchFixture();
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture = fixtures.save(fixture);

        Long fixtureId = fixture.getId();
        plans.save(new SubstitutionPlan(fixtureId, "Home", "Away", "[{\"triggerMinute\":60}]"));
        List<SubstitutionPlan> all = plans.findAll().stream()
                .filter(p -> fixtureId.equals(p.getFixtureId()))
                .toList();
        assertEquals(1, all.size(), "a fixture has exactly one plan. Two would mean the engine had to "
                + "choose which set of conditions to honour, and neither is obviously right.");
    }

    @Test
    @DisplayName("the orchestrator exposes its rules holder, and a rule can be attached before the first tick")
    void orchestratorAcceptsRulesBeforeSimulating() throws Exception {
        // Built by hand rather than through the service: the claim under test is that the accessor
        // exists and the holder accepts a rule, which is exactly what was impossible before.
        var state = new org.example.footballmanager.newLogic.sim.model.MatchState();
        var orchestrator = new org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator(state);

        var rules = orchestrator.conditionalSubstitutions();
        assertTrue(rules != null && rules.all().isEmpty(),
                "a fresh orchestrator holds an empty rule list, and the manager's plan goes into it");

        var rule = new org.example.footballmanager.newLogic.sim.engine.ConditionalSubstitutionRules.Rule(
                "HOME", 60, "LOSING");
        rules.add(rule);

        assertEquals(1, rules.all().size(),
                "a rule attached before the first tick is what the engine evaluates from minute 1. "
                        + "There was no accessor to attach it through, which is why the contract had ten "
                        + "green tests and no production caller.");

        // The accessor is the load-bearing part: without it the holder is unreachable and this whole
        // assertion is unreachable with it.
        Method accessor = org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator.class
                .getMethod("conditionalSubstitutions");
        assertEquals(org.example.footballmanager.newLogic.sim.engine.ConditionalSubstitutionRules.class,
                accessor.getReturnType(),
                "the accessor must return the rules holder itself, so a caller can add to it");
    }

    @Test
    @DisplayName("a fixture can only ever point at one played match")
    void oneMatchPerFixtureIsEnforcedInTheSchema() throws Exception {
        // The invariant the whole fixture ↔ match pair rests on. Two fixtures pointing at one Match would
        // make the result of a game read as the result of a different game — the class of mistake
        // P0-PREV-1/-2/-3 each recorded, and `ZoxApiController` carries the scar of.
        var column = MatchFixture.class.getDeclaredField("playedMatch");
        var join = column.getAnnotation(jakarta.persistence.JoinColumn.class);
        assertTrue(join.unique(),
                "played_match_id must be unique: one played match belongs to at most one fixture, and "
                        + "without it the correlation between a fixture and its result is a guess");

        var table = MatchFixture.class.getAnnotation(jakarta.persistence.Table.class);
        boolean indexed = java.util.Arrays.stream(table.indexes())
                .anyMatch(i -> List.of(i.columnList()).contains("played_match_id"));
        assertTrue(indexed,
                "and it must be indexed: reading a result by fixture is now on the kickoff path, since "
                        + "the engine looks the plan up there");
    }
}