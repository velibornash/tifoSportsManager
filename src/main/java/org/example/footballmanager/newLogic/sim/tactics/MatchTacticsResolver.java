package org.example.footballmanager.newLogic.sim.tactics;

import java.util.List;
import java.util.function.Function;

/**
 * The tactics actually in force at a moment in a match, from the assignments set on the fixture.
 *
 * <p><b>The fallback chain, and every link of it matters.</b> An instruction in force → the club's default
 * tactic → the bundled fallback. The manager forgetting is not an error state and must never stop a match,
 * so there is always an answer; but the chain is written out rather than collapsed, because each link is a
 * different kind of absence and only the last one is the engine's own.
 *
 * <p><b>An instruction whose tactic has been deleted resolves to the default, not to nothing.</b> The
 * assignment references its tactic rather than copying it — so an edit to a tactic reaches the match, which
 * is what a manager fixing one on the morning of the game wants. The cost of referencing is that a tactic
 * can be deleted out from under an assignment, and the honest answer then is the club's default rather
 * than a match that silently plays a shape nobody chose.
 */
public final class MatchTacticsResolver {

    private final List<MatchTacticPlan.Entry> homeEntries;
    private final List<MatchTacticPlan.Entry> awayEntries;

    /**
     * One lookup per side, each already bound to that side's club.
     *
     * <p>Two functions rather than one over all the assignments, because a tactic id is only meaningful
     * together with the club that owns it — {@code TacticsRulesProvider.forTactic(teamId, tacticId)} checks
     * exactly that, since club 1's tactic 1 and club 2's tactic 1 are both 1. Binding the club here means
     * the caller cannot pass a bare id and get somebody else's shape, and the per-tick call takes no
     * decision about whose id it is holding.
     */
    private final Function<Long, TacticsRules> homeRulesForTactic;
    private final Function<Long, TacticsRules> awayRulesForTactic;

    private final TacticsRules homeDefault;
    private final TacticsRules awayDefault;

    public MatchTacticsResolver(List<MatchTacticPlan.Entry> homeEntries,
                                List<MatchTacticPlan.Entry> awayEntries,
                                Function<Long, TacticsRules> homeRulesForTactic,
                                Function<Long, TacticsRules> awayRulesForTactic,
                                TacticsRules homeDefault,
                                TacticsRules awayDefault) {
        this.homeEntries = homeEntries == null ? List.of() : List.copyOf(homeEntries);
        this.awayEntries = awayEntries == null ? List.of() : List.copyOf(awayEntries);
        this.homeRulesForTactic = homeRulesForTactic;
        this.awayRulesForTactic = awayRulesForTactic;
        this.homeDefault = homeDefault;
        this.awayDefault = awayDefault;
    }

    /**
     * The rules for both sides at this moment.
     *
     * <p>Called once per tick, so it must not touch the database. The assignments and the default rules are
     * read when the match starts; what arrives here is three integers.
     *
     * @param homeScore goals by the home side
     * @param awayScore goals by the away side
     * @param minute    the match minute
     */
    public SideTactics resolve(int homeScore, int awayScore, int minute) {
        // Read from each side's own point of view: the home side's lead is the away side's deficit, so
        // one set of condition names serves both without either being special.
        TacticsRules home = pick(MatchTacticPlan.inForce(homeEntries, homeScore - awayScore, minute),
                homeDefault, homeRulesForTactic);
        TacticsRules away = pick(MatchTacticPlan.inForce(awayEntries, awayScore - homeScore, minute),
                awayDefault, awayRulesForTactic);
        return new SideTactics(home, away);
    }

    private TacticsRules pick(java.util.Optional<MatchTacticPlan.Entry> inForce, TacticsRules fallback,
                              Function<Long, TacticsRules> rulesForTactic) {
        if (inForce.isEmpty()) {
            return fallback;
        }
        TacticsRules rules = rulesForTactic.apply(inForce.get().tacticId());
        return rules == null ? fallback : rules;
    }

    /** Whether this fixture has any instruction at all — for the one-off log line at kickoff. */
    public boolean hasAssignments() {
        return !homeEntries.isEmpty() || !awayEntries.isEmpty();
    }
}