package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.example.footballmanager.newLogic.sim.util.SimUtils;

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

    /** Players whose TACTICAL TARGET lands within this distance of a restart
     *  ball spot are pushed off it (they never stand ON the restart ball). */
    private static final double RESTART_CLEAR_RADIUS = 0.6;
    private static final double RESTART_CLEAR_PUSH = 0.9;

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
        String possessionTeam = state.getCarrierTeam() != null
                ? state.getCarrierTeam()
                : state.getLastTouchTeam() != null
                ? state.getLastTouchTeam()
                : state.getRestartTeam();

        for (Player p : state.getPlayers()) {
            if (p == carrier || p == taker) continue;
            if (p.isUnavailable() || p.isLocked()) continue;
            if (p == receiver && state.getReceivePoint() != null) {
                // Run onto the pass — arrive at where the ball will land.
                p.setTarget(state.getReceivePoint());
                continue;
            }
            Position desired = tactics.desiredCell(
                    p.getRole(), state.getBall().getPosition(), p.getTeam(), possessionTeam);
            Position prev = p.getTarget();
            // RESTART-BALL CLEARANCE (user 2026-09-23): while a restart is pending
            // (taker designated), no non-taker may settle ON the ball spot. The
            // GK anchor for HOME is exactly the goal-kick spot (1.5,3.5) and the
            // home DCL anchors on it too — after every goal kick the GK + DCL +
            // pressing striker stacked directly ON the ball, so the taker's first
            // touch (CLEAR/DRIBBLE) launched from a scrum and instantly "deflected
            // off" a teammate standing at the kick origin (the in-air deflect).
            // Any desired target within keep-out distance of the ball spot is
            // pushed radially away from the ball.
            Position target = desired;
            if (taker != null && taker != p
                    && SimUtils.distance(desired, state.getBall().getPosition()) < RESTART_CLEAR_RADIUS) {
                double dr = desired.getRow() - state.getBall().getPosition().getRow();
                double dc = desired.getColumn() - state.getBall().getPosition().getColumn();
                double len = Math.hypot(dr, dc);
                if (len < 1e-9) {
                    // exactly ON the spot — push toward own half side
                    target = new Position(
                            desired.getRow() + ("HOME".equals(p.getTeam()) ? -RESTART_CLEAR_PUSH : RESTART_CLEAR_PUSH),
                            desired.getColumn());
                } else {
                    target = new Position(
                            desired.getRow() + dr / len * RESTART_CLEAR_PUSH,
                            desired.getColumn() + dc / len * RESTART_CLEAR_PUSH);
                }
            }
            p.setTarget(target);
            // Shared action logger — log a TAC line only when this player's
            // desired cell actually moved (receiver-on-pass run plus tactical
            // drift as the ball repositions). No log = no contract-drawn
            // movement that tick, so "players wandering with no order" becomes
            // attributable to either a desiredCell change here or an action.
            if (state.getActionLogger() != null && prev != null
                    && (Math.abs(prev.getRow() - target.getRow()) > 0.01
                    || Math.abs(prev.getColumn() - target.getColumn()) > 0.01)) {
                state.getActionLogger().log("TAC",
                        p.getLabel() + "(" + p.getRole() + ")"
                                + " target " + state.getActionLogger().p(prev)
                                + " -> " + state.getActionLogger().p(target)
                                + " (ball " + state.getActionLogger().p(state.getBall().getPosition()) + ")");
            }
        }
    }

    /**
     * Kickoff placement override: place every player at their tactical position
     * for the center-spot ball, but CLAMP TO THE OWN HALF so that at kickoff all
     * 22 players are on their own half (FIFA law 8). The kicker itself is placed
     * exactly at the center spot by RestartManager.
     *
     * HOME half = rows [1.0, 4.5], AWAY half = rows [4.5, 8.0].
     *
     * The clamp keeps a HALF-CELL BUFFER off the half-way line (HOME ≤ 4.0,
     * AWAY ≥ 5.0), not exactly 4.5. Pinning to exactly 4.5 left players standing
     * ON the line, and the first movement tick drifted them into the opponent
     * half — visible in the very first kickoff frame of the replay. Ported from
     * demo/service MatchState:644-645.
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
            row = Math.min(row, 4.0);   // half-cell buffer off the half-way line
        } else {
            row = Math.max(row, 5.0);
        }
        Position pos = new Position(row, desired.getColumn());
        p.setPosition(pos);
        p.setTarget(pos);
    }
}