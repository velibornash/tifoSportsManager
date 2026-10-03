package org.example.footballmanager.newLogic.sim.restarts;

import org.example.footballmanager.newLogic.sim.engine.TacticalIntentEngine;
import org.example.footballmanager.newLogic.sim.model.*;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.example.footballmanager.newLogic.sim.util.SimUtils;

/**
 * Restart Manager — handles ALL match restarts.
 *
 * CORE PRINCIPLE (from corePrinciples.md §48):
 * - INSTANT restart: ball teleports to restart spot
 * - Taker walks smoothly to ball (no teleport)
 * - Clock NEVER stops (no OOB_HOLD_TICKS)
 * - All 22 players reposition toward their TACTICAL positions (TacticsRules,
 *   loaded from team_tactics_profile DB or bundled tactics_fallback.json)
 * - KICKOFF is special: every player is clamped to his OWN HALF (FIFA law 8),
 *   the kicker is placed on the center spot (4.5, 4.0)
 *
 * Tactical rules store 0-BASED editor cells (row/col from 0); TacticsRules
 * converts them to 1-based field positions on load. See TacticsRules.
 */
public class RestartManager {

    public enum RestartType {
        KICK_OFF,
        GOAL_KICK_HOME,
        GOAL_KICK_AWAY,
        CORNER_HOME,
        CORNER_AWAY,
        THROW_IN_HOME,
        THROW_IN_AWAY,
        FREE_KICK,
        PENALTY_HOME,
        PENALTY_AWAY
    }

    /** Authoritative field center: row 4.5 (half-way line), col 4.0. */
    public static final Position KICK_OFF_SPOT = new Position(4.5, 4.0);

    /** Penalty spot: 11 m (0.78 cells) from goal line, goal-mouth centre (col 3.5). */
    public static final Position PENALTY_SPOT_HOME = new Position(7.2, 3.5); // HOME attacking AWAY goal
    public static final Position PENALTY_SPOT_AWAY = new Position(1.8, 3.5); // AWAY attacking HOME goal

    /** FIFA Law 13 minimum distance for opponents at a restart: 9.15 m = 0.65 cells. */
    public static final double RESTART_OPPONENT_DISTANCE = 0.65;

    private final TacticsRules tactics;
    private final TacticalIntentEngine tacticalEngine;

    public RestartManager() {
        this(new TacticsRules());
    }

    public RestartManager(TacticsRules tactics) {
        this.tactics = tactics;
        this.tacticalEngine = new TacticalIntentEngine(tactics);
    }

    public TacticsRules getTactics() { return tactics; }

    /**
     * The tactical target, or the player's own position when the tactic says nothing about his role.
     *
     * <p>A restart has to put twenty-two players somewhere specific, so a null here cannot be carried
     * on. The tactics return null for a role they do not name — which is now reachable, because a club's
     * own formation decides its role keys and a 4-3-3 side can be asked about a 4-4-2's rules. This
     * player's position was placed from HIS formation's anchors by {@code RealSquadFactory}, so it is
     * both non-null and the correct answer: he holds his shape rather than being teleported onto
     * whatever cell the fallback used to be.
     */
    private Position targetOrHold(Player p, Position desired) {
        return desired != null ? desired : p.getPosition();
    }

    /**
     * Execute an instant restart. No visible ball flight from OOB.
     * Clock continues running throughout.
     * @param oobExit ball position where it left the pitch — used to place
     *                throw-in at the correct touchline, corner at the correct flag
     */
    public void handleRestart(MatchState state, String oobType, Position oobExit) {
        executeRestart(state, parseRestartType(oobType), oobExit);
    }

    /** Overload without exit position — legacy callers / kickoff. */
    public void handleRestart(MatchState state, String oobType) {
        executeRestart(state, parseRestartType(oobType), null);
    }

    /**
     * Kickoff after a goal (or at match start).
     * The conceding team takes the kickoff.
     * ALL players on their own half; kicker on the center spot.
     */
    public void handleKickoff(MatchState state, String kickoffTeam) {
        state.clearPassContext();
        state.getBall().setPosition(KICK_OFF_SPOT);
        state.getBall().stop();
        state.setCarrier(null);
        state.setRestartTaker(null);
        state.setPendingReceiver(null);
        state.setKickoffPending(true);
        state.setKickoffHalfHold(true);
        state.setKickoffHalfHoldTick(state.getMatchTicks());

        // Every player to his own half (tactical position clamped to own half).
        tacticalEngine.placeOnOwnHalf(state, KICK_OFF_SPOT);

        // Kicker: nearest attacker of the kickoff team to the center spot
        // (fallback: any available player of that team), placed exactly on spot.
        Player taker = findNearestAttacker(state, kickoffTeam, KICK_OFF_SPOT);
        if (taker == null) {
            taker = findNearestPlayerOfTeam(state, kickoffTeam, KICK_OFF_SPOT);
        }
        if (taker != null) {
            taker.setPosition(KICK_OFF_SPOT);
            taker.setTarget(null);
            state.setCarrier(taker);
            state.getBall().setPosition(KICK_OFF_SPOT);
        }

        state.setPhase(MatchPhase.SET_PIECE);
        state.setSetPieceType("KICK_OFF");
        state.setRestartTeam(kickoffTeam);

        // KICKOFF in the log. handleKickoff previously wrote nothing anywhere, so
        // a replay had no kickoff line at all — the match just started. Logged
        // through the shared action logger (tag RST, which is already a
        // console-notable tag) so it lands in the app log, match.json and the UI
        // timeline.
        if (state.getActionLogger() != null) {
            state.getActionLogger().log("RST", "KICKOFF " + kickoffTeam
                    + " | ball at center (4.5,4.0) | taker "
                    + (taker == null ? "none" : taker.getLabel())
                    + (taker == null ? "" : " (" + taker.getRole() + ")"));
        }
    }

    /**
     * Offside indirect free kick (user rule 2026-09-23): called at the moment
     * an offside receiver TOUCHES the ball. The ball stays EXACTLY where it
     * physically arrived (no teleport — the ball never re-accelerates). The
     * defending team's nearest available field player walks to the spot and
     * claims it via the standard restart-taker path (MatchOrchestrator step 9);
     * all other players reposition toward their tactical targets for the spot.
     * Clock never stops (§48 instant-restart style). restartTeam must be set
     * to the defending team before calling.
     */
    public void handleOffsideFreeKick(MatchState state, Position spot) {
        // Offside supersedes a penalty awarded for the same incident: the attacker's involvement
        // was a prohibited action, so there is no penalty to take. Cleared here rather than blocked
        // upstream because this method is reached from three call sites and they must all agree.
        state.setPenaltyPending(false);
        state.getBall().setPosition(spot);
        state.getBall().stop();
        state.setPendingReceiver(null);

        for (Player p : state.getPlayers()) {
            if (p.isUnavailable()) continue;
            p.setTarget(targetOrHold(p, tactics.desiredCell(p.getRole(), spot, p.getTeam())));
        }

        Player taker = findNearestPlayerOfTeam(state, state.getRestartTeam(), spot);
        positionTaker(state, taker, spot);

        state.setCarrier(null);
        state.setPhase(MatchPhase.SET_PIECE);
        state.setSetPieceType("FREE_KICK");
    }

    /**
     * Direct free kick after a foul. Ball teleports to the foul spot; the
     * fouled team's nearest available player walks to it and claims it via the
     * standard restart-taker path (MatchOrchestrator step 9). Opponents are
     * pushed at least 9.15 m away (FIFA Law 13). Clock never stops (§48).
     * @param spot       position of the foul (where the fouled player was)
     * @param takingTeam team that was fouled and takes the kick
     */
    public void handleFreeKick(MatchState state, Position spot, String takingTeam) {
        startSetPiece(state, spot, takingTeam, false);
    }

    /**
     * Penalty kick after a foul in the defensive penalty area. Ball teleports
     * to the penalty spot; the fouled team's nearest attacker takes it.
     * Continued play is simplified: once in SET_PIECE the final-rows hard-SHOT
     * rule turns the carrier's decision into a shot.
     * @param takingTeam team that was fouled and takes the kick
     */
    public void handlePenalty(MatchState state, String takingTeam) {
        // Latched here, on the award, because by the time the taker reaches the spot the
        // set-piece type may already have been overwritten by a free kick for the same incident.
        state.setPenaltyPending(true);
        if ("HOME".equals(takingTeam)) {
            state.incrementHomePenalties();
        } else if ("AWAY".equals(takingTeam)) {
            state.incrementAwayPenalties();
        }
        Position spot = "HOME".equals(takingTeam)
                ? PENALTY_SPOT_HOME
                : PENALTY_SPOT_AWAY;
        startSetPiece(state, spot, takingTeam, true);
    }

    /**
     * Shared restart for fouls (free kick / penalty): ball teleports to spot,
     * all players reposition toward tactical targets, opponents pushed 9.15 m
     * away, nearest (attacker for penalty) available teammate walks to the ball.
     */
    private void startSetPiece(MatchState state, Position spot, String takingTeam, boolean penalty) {
        // An ordinary free kick must not quietly erase a penalty the referee awarded.
        //
        // An offside free kick is the deliberate opposite: offside is a prohibited action and takes
        // precedence over the penalty (user rule 2026-09-26), so it supersedes one rather than being
        // blocked by it. It does not route through here - handleOffsideFreeKick sets the restart up
        // itself and clears the pending penalty explicitly, which is where that rule lives.
        if (!penalty && state.isPenaltyPending()) {
            return;
        }
        state.clearPassContext();
        state.getBall().setPosition(spot);
        state.getBall().stop();
        state.setCarrier(null);
        state.setPendingReceiver(null);
        state.setRestartTeam(takingTeam);

        for (Player p : state.getPlayers()) {
            if (p.isUnavailable()) continue;
            p.setTarget(targetOrHold(p, tactics.desiredCell(p.getRole(), spot, p.getTeam())));
        }

        pushOpponentsAwayFromBall(state, takingTeam, spot);

        Player taker = penalty
                ? findNearestAttacker(state, takingTeam, spot)
                : findNearestPlayerOfTeam(state, takingTeam, spot);
        positionTaker(state, taker, spot);

        String type = penalty
                ? ("HOME".equals(takingTeam) ? "PENALTY_HOME" : "PENALTY_AWAY")
                : "FREE_KICK";
        state.setPhase(MatchPhase.SET_PIECE);
        state.setSetPieceType(type);
    }

    /**
     * Team the taker up to walk to the ball spot (or snap him behind it if he
     * is more than 4 cells away). Moves nobody else; returns the placed taker.
     */
    private void positionTaker(MatchState state, Player taker, Position spot) {
        if (taker == null) {
            state.setRestartTaker(null);
            return;
        }
        double dist = SimUtils.distance(taker.getPosition(), spot);
        if (dist > 4.0) {
            double behindRow = taker.getTeam().equals("HOME")
                    ? spot.getRow() - 0.6
                    : spot.getRow() + 0.6;
            behindRow = SimUtils.clamp(behindRow,
                    PitchEnvironment.HOME_GOAL_LINE,
                    PitchEnvironment.AWAY_GOAL_LINE);
            taker.setPosition(new Position(behindRow, spot.getColumn()));
        }
        taker.setTarget(spot);
        state.setRestartTaker(taker);
    }

    /**
     * Push every opponent of {@code takingTeam} to at least
     * {@link #RESTART_OPPONENT_DISTANCE} (9.15 m) away from the ball spot —
     * FIFA Law 13 minimum distance. Keeps players inside the pitch bounds.
     */
    public void pushOpponentsAwayFromBall(MatchState state, String takingTeam, Position ballPos) {
        PitchEnvironment pe = new PitchEnvironment();
        for (Player p : state.getPlayers()) {
            if (p.isUnavailable()) continue;
            if (takingTeam.equals(p.getTeam())) continue;
            Position pos = p.getPosition();
            if (pos == null) continue;
            double dx = pos.getRow() - ballPos.getRow();
            double dy = pos.getColumn() - ballPos.getColumn();
            double dist = Math.hypot(dx, dy);
            if (dist >= RESTART_OPPONENT_DISTANCE) continue;
            double row = ballPos.getRow() + (dist < 1e-6 ? RESTART_OPPONENT_DISTANCE : dx * RESTART_OPPONENT_DISTANCE / dist);
            double col = ballPos.getColumn() + (dist < 1e-6 ? RESTART_OPPONENT_DISTANCE : dy * RESTART_OPPONENT_DISTANCE / dist);
            p.setPosition(new Position(
                    SimUtils.clamp(row, pe.HOME_GOAL_LINE, pe.AWAY_GOAL_LINE),
                    SimUtils.clamp(col, pe.LEFT_TOUCHLINE, pe.RIGHT_TOUCHLINE)));
        }
    }

    private RestartType parseRestartType(String oobType) {
        return switch (oobType) {
            case "GOAL_KICK_HOME" -> RestartType.GOAL_KICK_HOME;
            case "GOAL_KICK_AWAY" -> RestartType.GOAL_KICK_AWAY;
            case "CORNER_HOME" -> RestartType.CORNER_HOME;
            case "CORNER_AWAY" -> RestartType.CORNER_AWAY;
            case "THROW_IN_HOME" -> RestartType.THROW_IN_HOME;
            case "THROW_IN_AWAY" -> RestartType.THROW_IN_AWAY;
            default -> RestartType.FREE_KICK;
        };
    }

    private void executeRestart(MatchState state, RestartType type, Position oobExit) {
        state.clearPassContext();
        // 1. INSTANT ball teleport to restart spot (derived from OOB exit position
        //    so throw-ins land on the correct touchline, corners on the correct flag)
        Position ballPos = getRestartPosition(type, oobExit);
        state.getBall().setPosition(ballPos);
        state.getBall().stop();

        // 2. All 22 players reposition toward tactical positions for the
        //    restart spot (players move smoothly, only the ball teleports).
        for (Player p : state.getPlayers()) {
            if (p.isUnavailable()) continue;
            p.setTarget(targetOrHold(p, tactics.desiredCell(p.getRole(), ballPos, p.getTeam())));
        }

        // 3. Select and position the taker — teleport fast-path if far, then walk
        Player taker = selectTaker(state, type, ballPos);
        // The restart ball belongs to nobody until the taker is ON it (RIGID RULE).
        // Clear the carrier UNCONDITIONALLY: a stale carrier left over from open
        // play would pass the orchestrator's "carrier on ball" gate on the very
        // next tick and play a pass the taker never touched.
        state.setCarrier(null);
        if (taker != null) {
            // Teleport fast-path (demo/service §48): if the nearest taker is
            // > 4.0 cells from the ball spot, snap them to a point 0.6 cells
            // behind the ball (toward own goal) so the walk is short and the
            // ball is never visually alone for long.
            double dist = SimUtils.distance(taker.getPosition(), ballPos);
            if (dist > 4.0) {
                double behindRow = taker.getTeam().equals("HOME")
                        ? ballPos.getRow() - 0.6   // behind HOME goal side
                        : ballPos.getRow() + 0.6;  // behind AWAY goal side
                behindRow = SimUtils.clamp(behindRow,
                        org.example.footballmanager.newLogic.sim.model.PitchEnvironment.HOME_GOAL_LINE,
                        org.example.footballmanager.newLogic.sim.model.PitchEnvironment.AWAY_GOAL_LINE);
                taker.setPosition(new Position(behindRow, ballPos.getColumn()));
            }
            taker.setTarget(ballPos);
            state.setRestartTaker(taker);
        } else {
            state.setRestartTaker(null);
        }

        // 4. Update state
        state.setPhase(MatchPhase.SET_PIECE);
        state.setSetPieceType(type.name());

        // 5. Clock NEVER stops - handled by MatchClockService
    }

    private Position getRestartPosition(RestartType type, Position oobExit) {
        PitchEnvironment pe = new PitchEnvironment();
        switch (type) {
            case KICK_OFF:
                return KICK_OFF_SPOT;
            case GOAL_KICK_HOME:
                return new Position(1.5, 3.5);
            case GOAL_KICK_AWAY:
                return new Position(7.5, 3.5);
            case CORNER_HOME: {
                // HOME attacking AWAY goal (row 8.0). The exit column determines
                // which corner flag the ball went out near (FIFA Rule 17).
                double exitCol = oobExit != null ? oobExit.getColumn() : 4.0;
                double col = exitCol < pe.CENTER_COL ? pe.LEFT_TOUCHLINE : pe.RIGHT_TOUCHLINE;
                return new Position(pe.AWAY_GOAL_LINE, col);
            }
            case CORNER_AWAY: {
                // AWAY attacking HOME goal (row 1.0). Exit column → flag.
                double exitCol = oobExit != null ? oobExit.getColumn() : 4.0;
                double col = exitCol < pe.CENTER_COL ? pe.LEFT_TOUCHLINE : pe.RIGHT_TOUCHLINE;
                return new Position(pe.HOME_GOAL_LINE, col);
            }
            case THROW_IN_HOME:
            case THROW_IN_AWAY: {
                // Throw-in at the touchline where the ball crossed. Exit column
                // determines left (1.0) vs right (7.0) touchline; the row is
                // clamped to the playable zone so the taker always arrives.
                double exitCol = oobExit != null ? oobExit.getColumn() : 4.0;
                double exitRow = oobExit != null ? oobExit.getRow() : pe.CENTER_ROW;
                double col = exitCol < pe.CENTER_COL ? pe.LEFT_TOUCHLINE : pe.RIGHT_TOUCHLINE;
                double row = SimUtils.clamp(exitRow,
                        pe.HOME_GOAL_LINE + 0.5, pe.AWAY_GOAL_LINE - 0.5);
                return new Position(row, col);
            }
            case FREE_KICK:
                return KICK_OFF_SPOT; // placeholder
            case PENALTY_HOME:
                return new Position(7.5, 3.5);
            case PENALTY_AWAY:
                return new Position(1.5, 3.5);
            default:
                return KICK_OFF_SPOT;
        }
    }

    private Player selectTaker(MatchState state, RestartType type, Position ballPos) {
        // The "_HOME"/"_AWAY" suffix encodes which team TAKES the restart.
        String takingTeam = type.name().endsWith("_AWAY") ? "AWAY" : "HOME";
        return switch (type) {
            case GOAL_KICK_HOME, GOAL_KICK_AWAY ->
                    findNearestDefender(state, takingTeam, ballPos);
            case CORNER_HOME, CORNER_AWAY ->
                    findCornerTaker(state, takingTeam, ballPos);
            case KICK_OFF -> {
                String kickoffTeam = state.getRestartTeam() != null
                        ? state.getRestartTeam() : "HOME";
                yield findNearestAttacker(state, kickoffTeam, ballPos);
            }
            default -> findNearestPlayerOfTeam(state, takingTeam, ballPos);
        };
    }

    private Player findNearestAttacker(MatchState state, String team, Position target) {
        return findNearestPlayerOfTeam(state, team, target, true, false);
    }

    private Player findNearestDefender(MatchState state, String team, Position target) {
        return findNearestPlayerOfTeam(state, team, target, false, true);
    }

    private Player findNearestPlayerOfTeam(MatchState state, String team, Position target) {
        return findNearestPlayerOfTeam(state, team, target, false, false);
    }

    private Player findNearestPlayerOfTeam(MatchState state, String team, Position target,
                                           boolean attackersOnly, boolean defendersOnly) {
        return state.getPlayers().stream()
                .filter(p -> !p.isSentOff() && !p.isInjured() && !p.isLocked()
                        && p.getTeam().equals(team))
                .filter(p -> !attackersOnly || p.isAttacker())
                .filter(p -> !defendersOnly || p.isDefender())
                .min((a, b) -> Double.compare(
                        SimUtils.distance(a.getPosition(), target),
                        SimUtils.distance(b.getPosition(), target)))
                .orElse(null);
    }

    /**
     * Corner taker = the winger on the side of the corner flag, nearest to it.
     *
     * Previously every corner was taken by the FIRST ML/DL of the taking team
     * (findWinger(left = true) for BOTH CORNER_HOME and CORNER_AWAY), so a
     * right-flag corner (column 7.0) was always taken by a left winger — the
     * longest possible walk, which regularly tripped the >4.0-cell teleport
     * fast-path and produced a corner that "started" from a fake position.
     */
    private Player findCornerTaker(MatchState state, String team, Position ballPos) {
        boolean leftSide = ballPos.getColumn() < 4.0;
        return state.getPlayers().stream()
                .filter(p -> !p.isSentOff() && !p.isInjured() && !p.isLocked()
                        && p.getTeam().equals(team))
                .filter(p -> leftSide
                        ? (p.getRole().equals("ML") || p.getRole().equals("DL"))
                        : (p.getRole().equals("MR") || p.getRole().equals("DR")))
                .min((a, b) -> Double.compare(
                        SimUtils.distance(a.getPosition(), ballPos),
                        SimUtils.distance(b.getPosition(), ballPos)))
                .orElseGet(() -> findNearestPlayerOfTeam(state, team, ballPos));
    }

    private Player findWinger(MatchState state, String team, boolean left) {
        return state.getPlayers().stream()
                .filter(p -> !p.isSentOff() && !p.isInjured() && !p.isLocked()
                        && p.getTeam().equals(team))
                .filter(p -> left ? (p.getRole().equals("ML") || p.getRole().equals("DL"))
                                : (p.getRole().equals("MR") || p.getRole().equals("DR")))
                .findFirst().orElse(null);
    }
}