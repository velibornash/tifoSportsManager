package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.result.ActionLogService;
import org.example.footballmanager.demo.service.result.MatchStatsCollector;

import java.util.List;

/**
 * Manages all restart events: kickoff, corners, goal kicks, throw-ins.
 *
 * Extracted from MatchSimulator (Phase 1 refactoring) to enforce the
 * corePrinciples §37 boundary: the orchestrator delegates restart
 * placement and taker selection here, rather than embedding it inline.
 *
 * This service DOES mutate MatchState (ball position, carrier, set-piece flag,
 * action delay) — restarts are state transitions, not evaluations.
 *
 * Per corePrinciples §48 (the user-driven restart spec):
 *  - No visible ball flight from OOB to restart position. The ball
 *    teleports to its restart spot the tick OOB is detected.
 *  - No OOB_HOLD delay. The clock runs continuously during a restart.
 *  - Players are placed at tactical positions (TacticsRules) smoothly.
 *  - The designated taker walks to the ball at normal movement speed.
 *  - No action delay (`setActionDelayTicks(OOB_HOLD_TICKS)`) anywhere.
 */
public class RestartManager {

    private final MatchState state;
    private final PlayerSelectionEngine selection;
    private final ActionLogService logger;

    // Starting XIs — needed for kickoff taker selection (order-sensitive).
    // Substitutes are appended to state.getPlayers() and would shift findFirst
    // results if we queried the live list instead.
    private final List<Player> homePlayers;
    private final List<Player> awayPlayers;

    public RestartManager(MatchState state, PlayerSelectionEngine selection,
                          ActionLogService logger,
                          List<Player> homePlayers, List<Player> awayPlayers) {
        this.state = state;
        this.selection = selection;
        this.logger = logger;
        this.homePlayers = homePlayers;
        this.awayPlayers = awayPlayers;
    }

    /**
     * Execute the kickoff ceremony: select kicker, place at center, signal
     * kickoff-action-pending to the decision engine.
     *
     * The kicker MUST be placed at exactly (4, 3.5) because
     * PlaymakingDecisionEngine.buildContext() detects kickoff by
     * position equality — this is a fragile contract with the decision engine.
     */
    public void handleKickoff(MatchStatsCollector stats) {
        String kickoffTeam = state.getKickoffTeam();
        List<Player> teamPlayers = "HOME".equals(kickoffTeam) ? homePlayers : awayPlayers;

        // Reset all players to their initial positions (each team on own half)
        // and place the ball at center. The kicker is then selected and placed
        // at the center spot.
        state.resetPositionsForKickoff();

        // Prefer an attacker for the kickoff (mirrors swingUIDemo: finds ST/ATT first),
        // falling back to any midfielder or attacker, then any player.
        Player kicker = teamPlayers.stream()
                .filter(p -> p.isAttacker())
                .findFirst()
                .orElse(teamPlayers.stream()
                        .filter(p -> p.roleLine().equals("MID"))
                        .findFirst()
                        .orElse(teamPlayers.get(0)));

        // Place the ball AND the kicker exactly at the center spot.
        Position centerSpot = new Position(4, 3.5);
        kicker.setPosition(centerSpot);
        state.getBall().setPosition(centerSpot);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(kicker);
        state.setCarrier(kicker);
        kicker.setLocked(false);
        kicker.setTarget(null);
        state.setKickoffPending(false);

        // No delay — kickoff pass fires on the next tick immediately
        state.setActionDelayTicks(0);

        // Signal to the decision engine that the NEXT decision is a kickoff:
        //   - generateKickoffPass() will be invoked (only backward PASS + CARRY)
        //   - CLEAR / SHOT / CROSS / THRU / CENTER are suppressed
        //   - Offside check is skipped for the kickoff pass
        state.setKickoffActionPending(true);

        // Increment the round counter so that the round-based kickoff fallback
        // in buildContext() also works (matches swingUIDemo: incrementRound at L122).
        state.incrementRound();

        state.setPhase(MatchPhase.OPEN_PLAY);
        state.setStatus("KICK OFF: " + kicker.getLabel() + " at center (4, 3.5)");
        logger.logRestart(state, "KICK OFF by " + kicker.getLabel()
                + " (" + kickoffTeam + ") at center (4, 3.5)", "KICKOFF");
    }

    /**
     * Pre-match kickoff setup — positions players and ball at center BEFORE
     * the clock starts ticking. The viewer sees ball at center with 0:00,
     * then the first tick of the main loop fires the kickoff pass immediately
     * at 0:01.
     */
    public void handleKickoffPreMatch(MatchStatsCollector stats) {
        String kickoffTeam = state.getKickoffTeam();
        List<Player> teamPlayers = "HOME".equals(kickoffTeam) ? homePlayers : awayPlayers;

        state.resetPositionsForKickoff();

        Player kicker = teamPlayers.stream()
                .filter(p -> p.isAttacker())
                .findFirst()
                .orElse(teamPlayers.stream()
                        .filter(p -> p.roleLine().equals("MID"))
                        .findFirst()
                        .orElse(teamPlayers.get(0)));

        Position centerSpot = new Position(4, 3.5);
        kicker.setPosition(centerSpot);
        state.getBall().setPosition(centerSpot);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(kicker);
        state.setCarrier(kicker);
        kicker.setLocked(false);
        kicker.setTarget(null);

        // NO delay — kickoff pass fires on the very first tick of the main loop
        state.setActionDelayTicks(0);
        state.setKickoffActionPending(true);
        state.incrementRound();
        state.setPhase(MatchPhase.OPEN_PLAY);
        state.setStatus("KICK OFF: " + kicker.getLabel() + " at center (4, 3.5)");
        logger.logRestart(state, "KICK OFF by " + kicker.getLabel()
                + " (" + kickoffTeam + ") at center (4, 3.5)", "KICKOFF");
    }

    /**
     * Handle ball leaving the pitch: corner, goal kick, or throw-in.
     * The team that did NOT last touch the ball receives the restart.
     *
     * Per corePrinciples §48 — "instant restart":
     *  1. Ball teleports to its final restart position (no animation).
     *  2. Opponents within 1 cell of the ball get a target away from the
     *     ball (they walk smoothly on the next tick — no teleport).
     *  3. The closest same-team outfield player to the ball becomes the
     *     taker (free-kick taker). He walks to the ball at normal movement
     *     speed on subsequent ticks (no teleport).
     *  4. setPiecePending + restartFirstTouch are set so the taker's first
     *     decision is restricted to PASS / CENTER / SHOT / CLEAR.
     *  5. **No** `setActionDelayTicks(OOB_HOLD_TICKS)` — the clock runs
     *     continuously. The taker-walk block in MatchSimulator's main loop
     *     handles the smooth approach on subsequent ticks.
     */
    public void handleBallOutOfBounds(MatchStatsCollector stats,
                                      FootballRulesService.RestartType restart,
                                      Position ballPos, String lastTouchTeam) {
        String defendingTeam = "HOME".equals(lastTouchTeam) ? "AWAY" : "HOME";
        // All restarts go to the team that did NOT touch the ball last.
        // Goal kick: awarded to the team defending the goal the ball went over.
        // Corner/throw-in: awarded to the team that didn't touch last.
        String restartTeam = defendingTeam;
        state.setKickoffTeam(restartTeam);
        state.setCelebrating(false);

        // Clear any lingering OOB/hold flags (the new spec has no OOB_HOLD;
        // restart happens instantly in this same call).
        state.clearBallOOBPending();
        state.setActionDelayTicks(0);

        // Clear any lingering action / chasers so we get a clean restart.
        // The ball is teleported INSTANTLY (no animation) to its final
        // restart position inside the playing area — never OOB.
        state.setAction(null);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(null);
        state.setCarrier(null);
        state.clearActiveChasers();

        // Release any locked receivers — the action that went out is abandoned.
        // But do NOT unlock sent-off (red card) or injured players.
        for (Player p : state.getPlayers()) {
            if (p.isLocked() && !"GK".equals(p.getRole())
                    && !p.isSentOff() && !p.isInjured()) {
                p.setLocked(false);
            }
        }

        // Clear any OOB hold delay (legacy constant — no longer set, but
        // make sure nothing lingers from a previous restart).
        state.setActionDelayTicks(0);

        Position restartSpot;
        String restartKind;

        switch (restart) {
            case CORNER -> {
                stats.onCorner(defendingTeam);
                boolean rightCorner = ballPos.getColumn() >= 3.5;
                boolean homeEnd = ballPos.getRow() > 7;
                // Corner flag at EXACT intersection of goal line and touchline.
                // HOME attacks row 8 → AWAY goal line is row 7.5 (corner row).
                // AWAY attacks row 1 → HOME goal line is row 0.5.
                // Touch lines at col 0/1 and col 6/7 (corner columns 0.5 / 6.5).
                double cornerRow = homeEnd ? 7.5 : 0.5;
                double cornerCol = rightCorner ? 6.5 : 0.5;
                restartSpot = new Position(cornerRow, cornerCol);
                restartKind = "CORNER";
                break;
            }
            case GOAL_KICK -> {
                stats.onGoalKick(restartTeam);
                boolean homeGk = "HOME".equals(restartTeam);
                // Goal kick ~5m from own goal, INSIDE the playing area
                // (HOME defending: row 1.5; AWAY defending: row 7.5).
                double gkRow = homeGk ? 1.5 : 7.5;
                restartSpot = new Position(gkRow, 3.5);
                restartKind = "GOAL_KICK";
                break;
            }
            case THROW_IN -> {
                stats.onThrowIn(defendingTeam);
                // Throw-in spot: row where ball crossed, ON the touchline.
                double row = SimUtils.clamp(ballPos.getRow(), 1, 7);
                double col = ballPos.getColumn() < 1 ? 1.0 : 6.0;
                restartSpot = new Position(row, col);
                restartKind = "THROW_IN";
                break;
            }
            default -> {
                // Loose ball / fallback — ball stays at OOB spot, hand to
                // nearest player of the side that did NOT last touch.
                restartSpot = ballPos;
                restartKind = "LOOSE";
            }
        }

        // ── TELEPORT the ball to the restart spot — no animation. ──
        state.getBall().setPosition(restartSpot);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(null);
        state.setCarrier(null);

        // ── Push opponents back ≥ 1 cell from the ball (no teleport). ──
        // Give them a target toward their own goal so they walk smoothly
        // away during the next tick.
        String opponentTeam = "HOME".equals(restartTeam) ? "AWAY" : "HOME";
        for (Player p : state.getPlayers()) {
            if (!opponentTeam.equals(p.getTeam())) continue;
            if (p.isSentOff() || p.isInjured()) continue;
            double dist = SimUtils.distance(p.getPosition(), restartSpot);
            if (dist >= 1.0) continue;
            boolean pHome = "HOME".equals(p.getTeam());
            double ownRow = pHome ? 1.0 : 7.0;
            double dir = Math.signum(ownRow - restartSpot.getRow());
            double pushTarget = 1.0 - dist + 0.1;
            double pushedRow = restartSpot.getRow() + pushTarget * dir;
            pushedRow = SimUtils.clamp(pushedRow, 1.0, 7.9);
            // Give a target so they move toward it smoothly next tick.
            p.setTarget(new Position(pushedRow, p.getPosition().getColumn()));
        }

        // ── Select the taker — NEAREST same-team outfield player to ball. ──
        Player taker = selection.nearestNonGoalkeeperTo(restartSpot, restartTeam);
        if (taker == null) {
            // Fallback: GK (very unusual).
            taker = selection.anyGoalkeeper(restartTeam);
        }
        if (taker == null) {
            logger.logRestart(state, restartKind + " for " + restartTeam
                    + " at " + restartSpot + " — NO TAKER FOUND", restartKind);
            state.setStatus("RESTART: " + restartKind + " for " + restartTeam);
            return;
        }

        taker.setLocked(false);
        taker.setTarget(restartSpot);   // walks to ball smoothly next tick
        state.setFreeKickTaker(taker);
        state.setSetPiecePending(true);
        state.setRestartFirstTouch(true);

        logger.logRestart(state, restartKind + " for " + restartTeam + " at " + restartSpot
                + " (ball was OOB at " + ballPos + ") — "
                + taker.getLabel() + " walking to ball", restartKind);
        state.setStatus("RESTART: " + restartKind + " for " + restartTeam);
    }

    /**
     * LEGACY — kept only for backwards compatibility. The new spec
     * (corePrinciples §48) never teleports the taker to the ball; instead
     * the taker walks smoothly via the movement engine.
     *
     * @deprecated since pass 10 — use {@link #handleBallOutOfBounds} which
     *             leaves the ball at the restart spot and lets the taker
     *             walk to it on subsequent ticks.
     */
    @Deprecated
    private void giveBallToRestartTaker(Player taker, Position pos) {
        // Kept only so any external direct callers compile. No-op now.
    }
}
