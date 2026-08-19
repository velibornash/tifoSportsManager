package org.example.footballmanager.demo;

import java.util.HashMap;
import java.util.Map;

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
    private final ThreatEngine threatEngine;

    /**
     * Per-player consecutive threat-override round counter (§1: an override
     * target may persist for at most 3 consecutive rounds before reverting to
     * the ordinary tactical target). Both maps are key'd by player; the counter
     * advances at most once per round (keyed by round number) so it is stable
     * across the many advance() ticks that make up a single round.
     */
    private final Map<Player, Integer> threatOverrideCount = new HashMap<>();
    private final Map<Player, Integer> threatOverrideLastRound = new HashMap<>();

    public TacticalIntentEngine(SimulationState state) {
        this(state, ThreatEngine.noop());
    }

    public TacticalIntentEngine(SimulationState state, ThreatEngine threatEngine) {
        this.state = state;
        this.threatEngine = threatEngine;
    }

    /** @return the active ThreatEngine (a no-op instance when the threat layer is off). */
    public ThreatEngine getThreatEngine() {
        return threatEngine;
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
            Position press = applyThreatCap(p, threatEngine.pressTarget(p, desired));
            Position movementTarget = (press != null) ? press : desired;
            state.setTacticalDesiredPosition(p, movementTarget);
            p.setTarget(MovementEngine.oneCellToward(p.getPosition(), movementTarget));
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
        boolean cellChanged = !currentKey.equals(lastKey);
        boolean inTransit = state.getBall().getBallState() == Ball.BallState.IN_TRANSITION;
        // §14: during an active ball transit (long pass/shot/cross flying across
        // 2+ grid rows) the coarse CELL key lags the ball, so the cell gate alone
        // would skip per-tick re-aiming and players freeze mid-flight while the
        // ball moves through the air. When the threat layer is active, re-evaluate
        // every tick while the ball is actually in flight (both the threat override
        // and the tactical desiredCell are recomputed live); the strict cell gate is
        // retained for the (threat-off) static case so the 8 existing demo tests stay
        // byte-for-byte unaffected.
        if (!cellChanged && !(inTransit && threatEngine.isEnabled())) {
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
            Position press = applyThreatCap(p, threatEngine.pressTarget(p, desired));
            Position movementTarget = (press != null) ? press : desired;
            state.setTacticalDesiredPosition(p, movementTarget);
            p.setTarget(MovementEngine.oneCellToward(p.getPosition(), movementTarget));
        }
    }

    // ==================================================================
    // Threat-override time-cap (§1): a threat-driven movement override may
    // persist for at most 3 consecutive rounds. On the 4th round where the
    // threat still applies, the player reverts to the ordinary tactical target
    // and stays there until the threat genuinely clears (counter reset). The
    // counter advances AT MOST ONCE PER ROUND (keyed by round number), so it is
    // stable across the many advance() ticks — and the per-tick cell-gate
    // relaxation (§14) — that make up a single round; both assignTargets() and
    // refreshTargetsIfBallStateChanged() consult this cap on pressTarget()'s
    // output.
    // ==================================================================
    private Position applyThreatCap(Player p, Position press) {
        if (press == null) {
            threatOverrideCount.remove(p);
            threatOverrideLastRound.remove(p);
            return null;
        }
        int round = state.getRound();
        if (round > threatOverrideLastRound.getOrDefault(p, -1)) {
            int count = threatOverrideCount.getOrDefault(p, 0) + 1;
            threatOverrideCount.put(p, count);
            threatOverrideLastRound.put(p, round);
        }
        if (threatOverrideCount.getOrDefault(p, 0) > 3) {
            return null; // §1: 3 override rounds elapsed -> revert to tactical
        }
        return press;
    }

    private boolean isActiveChase(Player player) {
        return state.isActiveChaser(player);
    }
}
