package org.example.footballmanager.demo;

/**
 * Odgovornost: TAKTICKA NAMERA / TAKTICKO CILJANJE.
 *
 * Granica koja izdvaja dodelu taktickih ciljeva kretanja za obe ekipe:
 *  - uzima trenutnu poziciju lopte
 *  - pita {@link TacticsRules} za desired poziciju role igraca
 *  - dodeljuje sledeci takticki cilj kretanja (max 1 celija)
 *
 * Pravila i dalje zive u {@link TacticsRules} (ucitana iz baze) — ovde je
 * samo granica odgovornosti. Ne uvodi nove possession state-ove, pressing,
 * coverage niti takticki scoring.
 */
public class TacticalIntentEngine {

    private final SimulationState state;

    public TacticalIntentEngine(SimulationState state) {
        this.state = state;
    }

    /**
     * Igraci bez lopte (nisu nosilac, nisu zakljucani) dobijaju cilj od
     * najvise 1 celije ka desired taktickoj poziciji za trenutnu poziciju lopte.
     */
    public void assignTargets() {
        state.setTacticalBallPosition(state.getBall().getPosition());
        state.setLastTacticalBallStateKey(TacticsRules.ballStateKey(state.getBall().getPosition()));
        for (Player p : state.getPlayers()) {
            if (p == state.getCarrier() || p.isLocked()) {
                continue;
            }
            if (p == state.getReturningPlayer() || isActiveChase(p)) continue;
            Position desired = state.getTacticsRules().desiredCell(p.getRole(),
                    state.getBall().getPosition(), p.getTeam());
            state.setTacticalDesiredPosition(p, desired);
            p.setTarget(MovementEngine.oneCellToward(p.getPosition(), desired));
        }
    }

    /**
     * Osvezava takticke ciljeve samo ako je lopta presla u novu grid celiju.
     * Poziva se tokom advance() tick-ova da bi igraci reagovali na kretanje
     * lopte tokom pas/sut leta, a ne samo na pocetku i kraju runde.
     */
    public void refreshTargetsIfBallStateChanged() {
        String currentKey = TacticsRules.ballStateKey(state.getBall().getPosition());
        String lastKey = state.getLastTacticalBallStateKey();
        if (currentKey.equals(lastKey)) {
            return;
        }
        state.setTacticalBallPosition(state.getBall().getPosition());
        state.setLastTacticalBallStateKey(currentKey);
        for (Player p : state.getPlayers()) {
            if (p == state.getCarrier() || p.isLocked()) {
                continue;
            }
            if (p == state.getReturningPlayer() || isActiveChase(p)) continue;
            Position desired = state.getTacticsRules().desiredCell(p.getRole(),
                    state.getBall().getPosition(), p.getTeam());
            state.setTacticalDesiredPosition(p, desired);
            p.setTarget(MovementEngine.oneCellToward(p.getPosition(), desired));
        }
    }

    private boolean isActiveChase(Player player) {
        Action action = state.getAction();
        return action != null && action.getType() == Action.Type.CHASE
                && action.getActingPlayer() == player;
    }
}
