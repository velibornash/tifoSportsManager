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
     * Handle ball leaving the pitch: corner, goal kick, or throw-in.
     * The team that did NOT last touch the ball receives the restart.
     */
    public void handleBallOutOfBounds(MatchStatsCollector stats,
                                      FootballRulesService.RestartType restart,
                                      Position ballPos, String lastTouchTeam) {
        String defendingTeam = "HOME".equals(lastTouchTeam) ? "AWAY" : "HOME";
        // For goal kicks, the restart team is lastTouchTeam (team whose goal
        // the ball went out near). For corners/throw-ins, it's defendingTeam
        // (team that didn't touch the ball last).
        String restartTeam = (restart == FootballRulesService.RestartType.GOAL_KICK)
                ? lastTouchTeam : defendingTeam;
        state.setKickoffTeam(restartTeam);
        state.setCelebrating(false);
        // Clear any lingering action / chasers so we get a clean restart
        state.setAction(null);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(null);
        state.setCarrier(null);
        state.clearActiveChasers();

        // Release any locked receivers — the action that went out is abandoned
        // But do NOT unlock sent-off (red card) or injured players
        for (Player p : state.getPlayers()) {
            if (p.isLocked() && !"GK".equals(p.getRole()) && !p.isSentOff() && !p.isInjured()) {
                p.setLocked(false);
            }
        }

        // NOTE: We do NOT set kickoffPending=true here.  That flag is reserved for
        // true kickoffs (start of match, after a goal, second half).  For
        // corners / goal-kicks / throw-ins the ball is placed at the correct
        // restart position and handed to the appropriate player, then a brief
        // hold delay lets other players reposition before the next decision.
        // This mirrors the swingUIDemo ScheduleRestart logic (SimEngine L599).

        switch (restart) {
            case CORNER -> {
                stats.onCorner(defendingTeam);
                boolean rightCorner = ballPos.getColumn() < 1;
                Position cornerPos;
                if (ballPos.getRow() > 7) {
                    cornerPos = new Position(6.5, rightCorner ? 0.5 : 6.5);
                } else {
                    cornerPos = new Position(1.5, rightCorner ? 0.5 : 6.5);
                }
                state.getBall().setPosition(cornerPos);
                Player cornerTaker = selection.nearestNonGoalkeeperTo(cornerPos, defendingTeam);
                giveBallToRestartTaker(cornerTaker, cornerPos);
                logger.logCorner(state, defendingTeam, rightCorner, "CORNER");
                logger.logRestart(state, "CORNER for " + defendingTeam + " at " + cornerPos
                        + " (ball was at " + ballPos + ")", "CORNER");
                break;
            }
            case GOAL_KICK -> {
                stats.onGoalKick(restartTeam);
                Position gkPos = "HOME".equals(restartTeam)
                        ? new Position(1, 3.5) : new Position(7, 3.5);
                state.getBall().setPosition(gkPos);
                Player keeper = selection.anyGoalkeeper(restartTeam);
                giveBallToRestartTaker(keeper, gkPos);
                logger.logGoalKick(state, restartTeam, "GOAL_KICK");
                logger.logRestart(state, "GOAL KICK for " + restartTeam + " at " + gkPos, "GOAL_KICK");
                break;
            }
            case THROW_IN -> {
                stats.onThrowIn(defendingTeam);
                double row = SimUtils.clamp(ballPos.getRow(), 1, 7);
                double col = ballPos.getColumn() < 1 ? 1.0 : 6.0;
                Position throwInPos = new Position(row, col);
                state.getBall().setPosition(throwInPos);
                Player throwInTaker = selection.nearestNonGoalkeeperTo(throwInPos, defendingTeam);
                giveBallToRestartTaker(throwInTaker, throwInPos);
                logger.logThrowIn(state, defendingTeam, "THROW_IN");
                logger.logRestart(state, "THROW-IN for " + defendingTeam + " at " + throwInPos
                        + " (ball was at " + ballPos + ")", "THROW_IN");
                break;
            }
            default -> {
                // Fallback: loose ball at the out-of-bounds position
                state.getBall().setPosition(ballPos);
                // Give to nearest player from the defending team so play can resume
                Player nearest = selection.nearestNonGoalkeeperTo(ballPos, defendingTeam);
                if (nearest != null) {
                    giveBallToRestartTaker(nearest, ballPos);
                }
                logger.logRestart(state, "LOOSE BALL for " + defendingTeam + " at " + ballPos, "RESTART");
            }
        }

        // Brief hold so players can reposition before the restart taker decides
        state.setActionDelayTicks(5); // Reduced from 20 to 5 ticks (0.125 seconds)
        state.setStatus("RESTART: " + restart + " for " + defendingTeam);
    }

    /**
     * Give the ball to a restart taker at a specific position.
     * Used for corners, goal kicks, and throw-ins — unlike handleKickoff,
     * this does NOT go through the center-circle kickoff path.
     */
    private void giveBallToRestartTaker(Player taker, Position pos) {
        if (taker == null) return;
        taker.setPosition(pos);
        taker.setTarget(null);
        taker.setLocked(false);
        state.getBall().setCarrier(taker);
        state.setCarrier(taker);
        state.setSetPiecePending(true);
    }
}
