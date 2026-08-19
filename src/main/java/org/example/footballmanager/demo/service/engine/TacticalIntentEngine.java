package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.tactics.TacticsRules;

/**
 * Tactical intent — assigns movement targets from TacticsRules.
 * Simplified version without ThreatEngine (headless mode).
 */
public class TacticalIntentEngine {

    private final MatchState state;

    public TacticalIntentEngine(MatchState state) {
        this.state = state;
    }

    public void assignTargets() {
        state.setTacticalBallPosition(state.getBall().getPosition());
        state.setLastTacticalBallStateKey(TacticsRules.ballStateKey(state.getBall().getPosition()));
        for (Player p : state.getPlayers()) {
            if (p == state.getCarrier() || p.isLocked()) continue;
            if (p == state.getReturningPlayer() || isActiveChase(p)) continue;
            Position desired = state.getTacticsRules().desiredCell(
                    p.getRole(), state.getBall().getPosition(), p.getTeam());
            state.setTacticalDesiredPosition(p, desired);
            p.setTarget(SimUtils.oneCellToward(p.getPosition(), desired));
        }
    }

    public void refreshTargetsIfBallStateChanged() {
        String currentKey = TacticsRules.ballStateKey(state.getBall().getPosition());
        String lastKey = state.getLastTacticalBallStateKey();
        boolean cellChanged = !currentKey.equals(lastKey);
        if (!cellChanged) return;

        state.setTacticalBallPosition(state.getBall().getPosition());
        state.setLastTacticalBallStateKey(currentKey);
        for (Player p : state.getPlayers()) {
            if (p == state.getCarrier() || p.isLocked()) continue;
            if (p == state.getReturningPlayer() || isActiveChase(p)) continue;
            Position desired = state.getTacticsRules().desiredCell(
                    p.getRole(), state.getBall().getPosition(), p.getTeam());
            state.setTacticalDesiredPosition(p, desired);
            p.setTarget(SimUtils.oneCellToward(p.getPosition(), desired));
        }
    }

    private boolean isActiveChase(Player player) {
        return state.isActiveChaser(player);
    }
}
