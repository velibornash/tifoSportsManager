package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.sim.tactics.MatchTacticPlan;
import org.example.footballmanager.newLogic.sim.tactics.MatchTacticsResolver;
import org.example.footballmanager.newLogic.sim.tactics.TacticMatchCondition;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds the resolver a match runs on, from what a club set on its fixture.
 *
 * <p><b>Read once, at kickoff.</b> The resolver is handed the instructions and the club defaults as plain
 * values and is then asked for three integers a tick. It must not touch the database inside the tick loop —
 * with 40 ticks a minute and a season of matches, a query per tick per match is the kind of cost that shows
 * up as a slow simulation rather than as an error.
 *
 * <p>That is also why the assignments are read into {@link MatchTacticPlan.Entry}: the entity's tactic and
 * fixture are lazy, and resolving them inside a tick would fetch them.
 */
@Service
@RequiredArgsConstructor
public class MatchTacticsPreparationService {

    private final MatchTacticAssignmentService assignments;
    private final TacticsRulesProvider rulesProvider;

    /**
     * The resolver for this fixture, or null when nobody planned for it.
     *
     * @param homeTeamId the home club, whose default is the fallback for its side
     * @param awayTeamId the away club, likewise
     */
    public MatchTacticsResolver forFixture(MatchFixture fixture, Long homeTeamId, Long awayTeamId) {
        if (fixture == null || fixture.getId() == null) {
            return null;
        }

        List<MatchTacticPlan.Entry> home = entriesFor(fixture.getId(), "HOME");
        List<MatchTacticPlan.Entry> away = entriesFor(fixture.getId(), "AWAY");
        if (home.isEmpty() && away.isEmpty()) {
            // Nobody planned this fixture. The match keeps the shape it was built with, and the tick loop
            // never asks anything.
            return null;
        }

        // Each side's lookup is bound to that side's own club, because a tactic id means nothing without it:
        // club 1's tactic 1 and club 2's tactic 1 are both 1, and the provider checks the pair.
        return new MatchTacticsResolver(
                home, away,
                tacticId -> homeTeamId == null ? null : rulesProvider.forTactic(homeTeamId, tacticId),
                tacticId -> awayTeamId == null ? null : rulesProvider.forTactic(awayTeamId, tacticId),
                rulesProvider.forTeam(homeTeamId),
                rulesProvider.forTeam(awayTeamId));
    }

    private List<MatchTacticPlan.Entry> entriesFor(Long fixtureId, String team) {
        return assignments.forSide(fixtureId, team).stream()
                .map(assignment -> MatchTacticPlan.Entry.of(
                        assignment.getId(),
                        assignment.getTactic() == null ? null : assignment.getTactic().getId(),
                        assignment.getPriority(),
                        assignment.getCondition() == null
                                ? TacticMatchCondition.ALWAYS : assignment.getCondition(),
                        assignment.getMinuteFrom()))
                .toList();
    }

}