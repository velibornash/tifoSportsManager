package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.MatchTacticAssignment;
import org.example.footballmanager.newLogic.model.tactics.Tactic;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchTacticAssignmentRepository;
import org.example.footballmanager.newLogic.repository.TacticRepository;
import org.example.footballmanager.newLogic.sim.tactics.TacticMatchCondition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A club's tactics for one fixture: up to three, in priority order.
 *
 * <p><b>Why three.</b> That is the owner's limit, and it is enforced <em>on write</em> — a fourth is
 * refused with the reason rather than accepted and silently dropped. A rule that is accepted and ignored
 * is the same failure as the substitution rule that could never fire: the manager believes it is in force
 * for ninety minutes and nothing is.
 *
 * <p><b>Three per club, not three per match.</b> Each side sets its own, because each side reads the score
 * from its own point of view and a rule of "if leading by one" is a different instruction for the club
 * that is behind. Six rows is the most a fixture can hold.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchTacticAssignmentService {

    /** The owner's limit, per club per fixture. */
    public static final int MAX_PER_SIDE = 3;

    private final MatchTacticAssignmentRepository assignments;
    private final MatchFixtureRepository fixtures;
    private final TacticRepository tactics;

    public List<MatchTacticAssignment> forFixture(Long fixtureId) {
        return assignments.findByFixtureIdOrderByTeamAscPriorityAsc(fixtureId);
    }

    public List<MatchTacticAssignment> forSide(Long fixtureId, String team) {
        return assignments.findByFixtureIdAndTeamOrderByPriorityAsc(fixtureId, team);
    }

    /**
     * Assigns one of a club's tactics to a fixture.
     *
     * @param priority 1 is tried first; must be 1..{@value #MAX_PER_SIDE}
     * @throws IllegalArgumentException with the reason, for anything the manager can fix
     */
    @Transactional
    public MatchTacticAssignment assign(Long fixtureId, String side, Long tacticId, int priority,
                                        TacticMatchCondition condition, Integer minuteFrom) {
        MatchFixture fixture = fixtures.findById(fixtureId).orElseThrow(
                () -> new IllegalArgumentException("Unknown fixture " + fixtureId));

        String team = normaliseSide(side, fixture);
        Tactic tactic = tactics.findByIdAndTeamId(tacticId, resolveClubId(fixture, team)).orElseThrow(
                () -> new IllegalArgumentException(
                        "That tactic does not belong to the club playing on this side of the fixture"));

        if (priority < 1 || priority > MAX_PER_SIDE) {
            throw new IllegalArgumentException("Priority runs from 1 to " + MAX_PER_SIDE + ".");
        }

        List<MatchTacticAssignment> existing = forSide(fixtureId, team);
        boolean alreadyAtThisPriority = existing.stream().anyMatch(a -> a.getPriority() == priority);

        // Defence in depth, and currently unreachable through this API: priority is bounded 1..3 and
        // saving at an occupied priority replaces that slot, so a fourth row cannot be created from here.
        // It stays because the two limits are different rules that happen to agree today — priority is a
        // position, the count is a cap — and widening priority later (to let a manager reorder without
        // renumbering) must not turn the cap into a silent truncation. If it ever does fire, the message
        // names the limit rather than the constraint.
        if (!alreadyAtThisPriority && existing.size() >= MAX_PER_SIDE) {
            throw new IllegalArgumentException(
                    "A club can set " + MAX_PER_SIDE + " tactics for one match, and this club already has "
                            + MAX_PER_SIDE + ". Remove one before adding another — a fourth would never be "
                            + "reached, because only the first three are ever considered.");
        }

        // Saving at an occupied priority replaces that slot, rather than failing on the unique constraint
        // with a message about indexes. The manager editing priority 2 should not be told the database is
        // unhappy; they should see their edit.
        MatchTacticAssignment assignment = existing.stream()
                .filter(a -> a.getPriority() == priority)
                .findFirst()
                .orElseGet(MatchTacticAssignment::new);

        assignment.setFixture(fixture);
        assignment.setTactic(tactic);
        assignment.setTeam(team);
        assignment.setPriority(priority);
        assignment.setCondition(condition == null ? TacticMatchCondition.ALWAYS : condition);
        assignment.setMinuteFrom(minuteFrom == null ? 0 : Math.max(0, Math.min(90, minuteFrom)));
        return assignments.save(assignment);
    }

    @Transactional
    public void clear(Long fixtureId, String side) {
        assignments.deleteAll(forSide(fixtureId, normaliseSide(side, null)));
    }

    /** {@code HOME} / {@code AWAY}, case-insensitively, because the screen and the API disagree about case. */
    private String normaliseSide(String side, MatchFixture fixture) {
        String value = side == null ? "" : side.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "HOME", "AWAY" -> value;
            default -> throw new IllegalArgumentException(
                    "Side must be HOME or AWAY, not '" + side + "'");
        };
    }

    private Long resolveClubId(MatchFixture fixture, String team) {
        Team club = "HOME".equals(team) ? fixture.getHomeTeam() : fixture.getAwayTeam();
        return club == null ? null : club.getId();
    }
}