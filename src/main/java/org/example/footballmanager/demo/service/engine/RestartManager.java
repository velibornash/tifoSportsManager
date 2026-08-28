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
                boolean rightCorner = ballPos.getColumn() >= 3.5;
                // Corner flag is at the EXACT intersection of the goal line and the
                // touch line. HOME attacks toward row 7, so their goal line is the
                // row 7/8 boundary (corner row 7.5); AWAY attacks toward row 1, so
                // their goal line is the row 0/1 boundary (corner row 0.5).
                // Touch lines are the col 0/1 and col 6/7 boundaries (0.5 / 6.5).
                boolean homeEnd = ballPos.getRow() > 7;
                double cornerRow = homeEnd ? 7.5 : 0.5;
                double cornerCol = rightCorner ? 6.5 : 0.5;
                Position cornerPos = new Position(cornerRow, cornerCol);
                state.getBall().setPosition(cornerPos);
                Player cornerTaker = selection.nearestNonGoalkeeperTo(cornerPos, defendingTeam);
                // Taker must be AT the exact corner spot, become carrier, then pass/center.
                giveBallToRestartTaker(cornerTaker, cornerPos);
                logger.logCorner(state, defendingTeam, rightCorner, "CORNER");
                logger.logRestart(state, "CORNER for " + defendingTeam + " at " + cornerPos
                        + " (ball was at " + ballPos + ") taken by "
                        + (cornerTaker == null ? "?" : cornerTaker.getLabel()), "CORNER");
                break;
            }
            case GOAL_KICK -> {
                stats.onGoalKick(restartTeam);
                boolean homeGk = "HOME".equals(restartTeam);
                // Place ball roughly 5m from own goal — midway of the zone closest
                // to the defending goal, on the centre column. HOME defends row 1
                // (attacks toward row 7), so their goal kick is near row 1.5;
                // AWAY defends row 7, so their goal kick is near row 6.5.
                double gkRow = homeGk ? 1.5 : 6.5;
                Position gkPos = new Position(gkRow, 3.5);
                state.getBall().setPosition(gkPos);
                state.getBall().setTarget(null);

                // Hard rule: clear the restart team's OWN penalty box. Any opponent
                // inside (or on the edge of) the goal kick box, or closer than 1
                // cell to the ball, is pushed back toward their OWN goal — AWAY from
                // the ball — so the kick is never contested from close range and
                // the box empties to give the taker room.
                String opponentTeam = homeGk ? "AWAY" : "HOME";
                // Opponents are AWAY whenever HOME is kicking.
                boolean awayOpponent = homeGk;
                for (Player p : state.getPlayers()) {
                    if (!opponentTeam.equals(p.getTeam())) continue;
                    if (p.isSentOff() || p.isInjured()) continue;
                    double dist = SimUtils.distance(p.getPosition(), gkPos);
                    double row = p.getPosition().getRow();
                    // Box rows: HOME kicks → box is rows 1-2 (AWAY must clear).
                    // AWAY kicks → box is rows 6-7 (HOME must clear).
                    boolean inBox = awayOpponent ? row < 2.6 : row > 5.4;
                    if (dist < 1.0 || inBox) {
                        // Push away from the ball toward the opponent's own goal.
                        // AWAY own goal is row 7 (+), HOME own goal is row 1 (-).
                        double dir = awayOpponent ? 1 : -1;
                        double targetRow = inBox
                                ? (awayOpponent ? 3.0 : 5.0)
                                : row + dir * ((1.0 - dist) + 0.1);
                        targetRow = SimUtils.clamp(targetRow, 1.0, 7.0);
                        boolean movesAway = inBox || (targetRow - row) * dir >= 0;
                        if (movesAway) {
                            p.setPosition(new Position(targetRow, p.getPosition().getColumn()));
                        }
                        p.setTarget(null);
                    }
                }

                // Select the NEAREST outfield defender as the taker — NOT the
                // goalkeeper, and NOT teleported. The taker walks to the ball
                // (handled by the MatchSimulator set-piece walk-to-ball block)
                // while the clock runs, then the decision engine chooses a safe
                // PASS or a CLEAR.
                Player taker = selection.nearestNonGoalkeeperTo(gkPos, restartTeam);
                if (taker != null) {
                    taker.setLocked(false);
                    taker.setTarget(gkPos);
                    state.setFreeKickTaker(taker);
                    // Ball is at the spot already; the taker approaches it. We do
                    // NOT call giveBallToRestartTaker (that teleports). Instead we
                    // mark a set piece pending so the decision engine suppresses
                    // CARRY / SHOT and the carrier decides between PASS and CLEAR.
                    state.setSetPiecePending(true);
                    logger.logGoalKick(state, restartTeam, "GOAL_KICK");
                    logger.logRestart(state, "GOAL KICK for " + restartTeam + " at " + gkPos
                            + " — " + taker.getLabel() + " walking to ball (opponents pushed back ≥1 cell)",
                            "GOAL_KICK");
                } else {
                    // Fallback: give directly to the keeper if no outfield taker found
                    Player keeper = selection.anyGoalkeeper(restartTeam);
                    giveBallToRestartTaker(keeper, gkPos);
                    logger.logGoalKick(state, restartTeam, "GOAL_KICK");
                    logger.logRestart(state, "GOAL KICK for " + restartTeam + " at " + gkPos,
                            "GOAL_KICK");
                }
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
