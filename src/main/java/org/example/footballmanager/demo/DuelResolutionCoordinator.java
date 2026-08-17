package org.example.footballmanager.demo;

/**
 * Coordinates the transition from an active spatial duel to a resolved,
 * logged result. It deliberately does not apply possession/action effects;
 * {@link SimulationEngine} still owns those consequences.
 */
public final class DuelResolutionCoordinator {
    private final SimulationState state;
    private final DuelEngine duelEngine;
    private final DuelResolver resolver;

    public DuelResolutionCoordinator(SimulationState state, DuelEngine duelEngine,
                                     DuelResolver resolver) {
        this.state = state;
        this.duelEngine = duelEngine;
        this.resolver = resolver;
    }

    public DuelResult resolve(Action action) {
        duelEngine.update(action);
        Duel duel = duelEngine.getActiveDuel();
        DuelResult result = duelEngine.resolveActiveDuel(resolver);
        if (duel == null || result == null) {
            return result;
        }

        Player loser = result.winner() == duel.getAttacker()
                ? duel.getDefender() : duel.getAttacker();
        state.log("DUEL CALC: " + duel.getType() + " | "
                + duel.getAttacker().getLabel() + " [" + resolver.skillDescription(duel, true)
                + " = " + result.attackerPower() + "] vs "
                + duel.getDefender().getLabel() + " ["
                + resolver.skillDescription(duel, false) + " = " + result.defenderPower() + "]"
                + " | winner: " + result.winner().getLabel());

        // The goalkeeper who saves a shot remains immediately available for
        // the rebound. Every other loser is frozen for three seconds.
        if (!(duel.getType() == DuelType.SHOT
                && duel.getDefender() == result.winner()
                && "GK".equals(duel.getDefender().getRole()))) {
            state.blockAfterDuel(loser);
            loser.setTarget(null);
        }
        return result;
    }
}
