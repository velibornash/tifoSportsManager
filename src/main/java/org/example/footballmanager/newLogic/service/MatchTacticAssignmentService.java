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

    /**
     * Rewrites one side's whole order in a single transaction.
     *
     * <p>The owner's *"bilo koja koju promeniš"* — any tactic into any slot. Done as three one-slot writes
     * it cannot be: putting the third tactic into slot 1 while the first is still there is an
     * intermediate state where two slots hold one tactic, and a failure halfway through leaves a mangled
     * plan that no longer says what the manager meant. One transaction means the plan is either the old
     * order or the new one.
     *
     * <p><b>Reorder replaces the plan for that side</b>, so everything not in the list is dropped. Each
     * entry comes back as "always, from the start", which is what reorder is for: it says <em>which three
     * tactics and in what order</em>. The minute and the condition on an instruction belong to the orders
     * page, and a version of this that tried to carry them across a move would be claiming to preserve
     * something it had to overwrite anyway — the table holds one row per (fixture, team, priority), so a
     * move cannot keep a row in place.
     *
     * @param tacticIdsInOrder the tactics, slot 1 first; at most {@link #MAX_PER_SIDE}, no duplicates,
     *        and every one of them belonging to <em>this side's</em> club
     * @throws IllegalArgumentException naming the rule that was broken
     */
    @Transactional
    public void reorder(Long fixtureId, String side, java.util.List<Long> tacticIdsInOrder) {
        java.util.List<Long> order = tacticIdsInOrder == null ? java.util.List.of() : tacticIdsInOrder;
        String normalised = normaliseSide(side, null);

        if (order.size() > MAX_PER_SIDE) {
            throw new IllegalArgumentException("A match takes " + MAX_PER_SIDE
                    + " tactics, not " + order.size() + ".");
        }
        if (new java.util.HashSet<>(order).size() != order.size()) {
            // Two slots holding one tactic is not "three of the ten": it is one tactic and a gap, and the
            // engine would resolve the same rules whichever slot it read.
            throw new IllegalArgumentException("The same tactic cannot hold two slots at once.");
        }

        // **Checked against the club on this side**, not the home club. Club 1's tactic 1 and club 2's
        // tactic 1 are both 1, so an away-side reorder validated against the home club would either
        // refuse the away manager's own tactics or accept the home manager's.
        Long clubId = clubForSide(fixtureId, normalised);
        if (clubId == null) {
            throw new IllegalArgumentException("Fixture " + fixtureId + " has no " + normalised + " side.");
        }
        for (Long tacticId : order) {
            if (tacticId == null || tactics.findByIdAndTeamId(tacticId, clubId).isEmpty()) {
                throw new IllegalArgumentException(
                        "Tactic " + tacticId + " does not belong to this club.");
            }
        }

        // **The guarantee that a refusal changes nothing is this method's @Transactional, not the order of
        // these statements.** Clearing first and validating afterwards was tried, and a test asserting the
        // manager's three tactics survive a refusal stayed green either way - the throw rolls the clear
        // back. So the order is not the safety, and a future reader must not reorder these lines and then
        // drop @Transactional, which would turn a harmless difference into one that takes a manager's
        // plan away and refuses to put it back.
        clear(fixtureId, normalised);
        for (int i = 0; i < order.size(); i++) {
            assign(fixtureId, normalised, order.get(i), i + 1, TacticMatchCondition.ALWAYS, 0);
        }
    }

    /** The club on one side of a fixture, which is what a tactic id has to be checked against. */
    private Long clubForSide(Long fixtureId, String side) {
        return fixtures.findById(fixtureId)
                .map(f -> "AWAY".equals(side)
                        ? (f.getAwayTeam() == null ? null : f.getAwayTeam().getId())
                        : (f.getHomeTeam() == null ? null : f.getHomeTeam().getId()))
                .orElse(null);
    }

    @Transactional
    public void clear(Long fixtureId, String side) {
        assignments.deleteAll(forSide(fixtureId, normaliseSide(side, null)));
    }

    /**
     * Empties one priority slot, leaving the others alone.
     *
     * <p>What the screen's "— none —" needs. Clearing the whole side instead would take out the two
     * instructions the manager did want, so the alternative was making a slot unfillable, which is worse.
     */
    @Transactional
    public void clearPriority(Long fixtureId, String side, int priority) {
        String team = normaliseSide(side, null);
        for (MatchTacticAssignment assignment : forSide(fixtureId, team)) {
            if (assignment.getPriority() == priority) {
                assignments.delete(assignment);
            }
        }
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