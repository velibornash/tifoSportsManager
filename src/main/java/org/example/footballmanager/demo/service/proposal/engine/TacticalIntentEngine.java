package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import org.example.footballmanager.demo.service.proposal.model.Position;
import org.example.footballmanager.demo.service.proposal.tactics.TacticsRules;

/**
 * Tactical Intent Engine — the ONLY source of movement targets for players
 * who do not already have an action-driven target (carrier on CARRY/CLEAR,
 * restart taker walking to the ball, in-flight receiver holding position).
 *
* Every tick it asks TacticsRules for the desired cell of each non-carrier
     * player given the current ball position, and stores it as the player target.
     * MovementEngine then moves players toward those targets at pace-capped speed.
     *
     * The pending receiver is NOT left standing: it runs onto the pass by
     * targeting the stored receivePoint (demo/service model — a pass is served
     * into the receiver's run, and interception requires being ON the flight
     * line). The restart taker walks to the ball instead.
     *
     * Rules are loaded from the team_tactics_profile DB table (fallback: bundled
     * JSON → FormationSlotCatalog anchors). Coordinates in the rules are 0-based
     * editor cells; TacticsRules.parseCell() converts them to 1-based field
     * positions. See TacticsRules for the conversion contract.
     */
    public class TacticalIntentEngine {

    private final TacticsRules tactics;

    public TacticalIntentEngine(TacticsRules tactics) {
        this.tactics = tactics;
    }

    /**
     * Refresh tactical targets for all players EXCEPT:
     *  - the ball carrier (its target is set by the executed action),
     *  - the restart taker (walks to the ball),
     *  - unavailable/locked players.
     *
     * The pending receiver gets the in-flight receivePoint as its target so it
     * runs onto the pass instead of standing still.
     */
    public void refreshTargets(MatchState state) {
        Player carrier = state.getCarrier();
        Player taker = state.getRestartTaker();
        Player receiver = state.getPendingReceiver();

        for (Player p : state.getPlayers()) {
            if (p == carrier || p == taker) continue;
            if (p.isUnavailable() || p.isLocked()) continue;
            if (p == receiver && state.getReceivePoint() != null) {
                // Run onto the pass — arrive at where the ball will land.
                p.setTarget(state.getReceivePoint());
                continue;
            }
            Position desired = tactics.desiredCell(p.getRole(), state.getBall().getPosition(), p.getTeam());
            p.setTarget(desired);
        }
    }

    /**
     * Kickoff placement override: place every player at their tactical position
     * for the center-spot ball, but CLAMP TO THE OWN HALF so that at kickoff all
     * 22 players are on their own half (FIFA law 8). The kicker itself is placed
     * exactly at the center spot by RestartManager.
     *
     * HOME half = rows [1.0, 4.5], AWAY half = rows [4.5, 8.0].
     */
    public void placeOnOwnHalf(MatchState state, Position centerSpot) {
        for (Player p : state.getPlayers()) {
            if (p.isUnavailable()) continue;
            Position desired = tactics.desiredCell(p.getRole(), centerSpot, p.getTeam());
            playableOwnHalf(desired, p.getTeam(), p);
        }
    }

    private void playableOwnHalf(Position desired, String team, Player p) {
        double row = desired.getRow();
        if ("HOME".equals(team)) {
            row = Math.min(row, 4.5);   // never across the half-way line
        } else {
            row = Math.max(row, 4.5);
        }
        Position pos = new Position(row, desired.getColumn());
        p.setPosition(pos);
        p.setTarget(pos);
    }
}