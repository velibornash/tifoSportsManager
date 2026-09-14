package org.example.footballmanager.demo.service.proposal.restarts;

import org.example.footballmanager.demo.service.proposal.engine.TacticalIntentEngine;
import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.tactics.TacticsRules;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

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
        state.getBall().setPosition(KICK_OFF_SPOT);
        state.getBall().stop();
        state.setCarrier(null);
        state.setRestartTaker(null);
        state.setPendingReceiver(null);
        state.setKickoffPending(true);

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
        // 1. INSTANT ball teleport to restart spot (derived from OOB exit position
        //    so throw-ins land on the correct touchline, corners on the correct flag)
        Position ballPos = getRestartPosition(type, oobExit);
        state.getBall().setPosition(ballPos);
        state.getBall().stop();

        // 2. All 22 players reposition toward tactical positions for the
        //    restart spot (players move smoothly, only the ball teleports).
        for (Player p : state.getPlayers()) {
            if (p.isUnavailable()) continue;
            p.setTarget(tactics.desiredCell(p.getRole(), ballPos, p.getTeam()));
        }

        // 3. Select and position the taker — teleport fast-path if far, then walk
        Player taker = selectTaker(state, type, ballPos);
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
                        org.example.footballmanager.demo.service.proposal.model.PitchEnvironment.HOME_GOAL_LINE,
                        org.example.footballmanager.demo.service.proposal.model.PitchEnvironment.AWAY_GOAL_LINE);
                taker.setPosition(new Position(behindRow, ballPos.getColumn()));
            }
            taker.setTarget(ballPos);
            state.setCarrier(null);
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
                    findWinger(state, takingTeam, true);
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
                .filter(p -> !p.isSentOff() && !p.isInjured() && p.getTeam().equals(team))
                .filter(p -> !attackersOnly || p.isAttacker())
                .filter(p -> !defendersOnly || p.isDefender())
                .min((a, b) -> Double.compare(
                        SimUtils.distance(a.getPosition(), target),
                        SimUtils.distance(b.getPosition(), target)))
                .orElse(null);
    }

    private Player findWinger(MatchState state, String team, boolean left) {
        return state.getPlayers().stream()
                .filter(p -> !p.isSentOff() && !p.isInjured() && p.getTeam().equals(team))
                .filter(p -> left ? (p.getRole().equals("ML") || p.getRole().equals("DL"))
                                : (p.getRole().equals("MR") || p.getRole().equals("DR")))
                .findFirst().orElse(null);
    }
}