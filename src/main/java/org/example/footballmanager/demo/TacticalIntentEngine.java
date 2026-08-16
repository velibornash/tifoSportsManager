package org.example.footballmanager.demo;

/**
 * Odgovornost: TAKTICKA NAMERA / TAKTICKO CILJANJE.
 *
 * Granica koja izdvaja dodelu taktickih ciljeva kretanja:
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
        for (Player p : state.getPlayers()) {
            if (!SimulationState.TEAM_HOME.equals(p.getTeam())) {
                continue;
            }
            if (p == state.getCarrier() || p.isLocked()) {
                continue;
            }
            Position desired = state.getTacticsRules().desiredCell(p.getRole(), state.getBall().getPosition());
            state.setTacticalDesiredPosition(p, desired);
            p.setTarget(MovementEngine.oneCellToward(p.getPosition(), desired));
        }
    }
}
