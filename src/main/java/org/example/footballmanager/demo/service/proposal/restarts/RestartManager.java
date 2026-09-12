package org.example.footballmanager.demo.service.proposal.restarts;

import org.example.footballmanager.demo.service.proposal.engine.TacticalIntentEngine;
import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.tactics.TacticsRules;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

/**
 * Restart Manager - handles ALL match restarts.
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
     */
    public void handleRestart(MatchState state, String oobType) {
        executeRestart(state, parseRestartType(oobType));
    }

    /**
     * Kickoff after a goal (or at match start).
     * The conceding team takes the kickoff.
     * ALL players on their own half; kicker on the center spot.
     */
    public void handleKickoff(MatchState state, String kickoffTeam) {
        state.getBall().setPosition(KICK_OFF_SPOT);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(null);
        state.getBall().setSpeed(0);
        state.getBall().setAirborne(false);
        state.getBall().setRollDirection(null);
        state.setCarrier(null);
        state.setRestartTaker(null);
        state.setPendingReceiver(null);

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
            state.getBall().setCarrier(taker);
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

    private void executeRestart(MatchState state, RestartType type) {
        // 1. INSTANT ball teleport to restart spot
        Position ballPos = getRestartPosition(type);
        state.getBall().setPosition(ballPos);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(null);
        state.getBall().setSpeed(0);
        state.getBall().setAirborne(false);
        state.getBall().setRollDirection(null);

        // 2. All 22 players reposition toward tactical positions for the
        //    restart spot (players move smoothly, only the ball teleports).
        for (Player p : state.getPlayers()) {
            if (p.isUnavailable()) continue;
            p.setTarget(tactics.desiredCell(p.getRole(), ballPos, p.getTeam()));
        }

        // 3. Select and position the taker - walks to the ball
        Player taker = selectTaker(state, type);
        if (taker != null) {
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

    private Position getRestartPosition(RestartType type) {
        return switch (type) {
            case KICK_OFF -> KICK_OFF_SPOT; // center spot (4.5, 4.0)
            case GOAL_KICK_HOME -> new Position(1.5, 3.5); // HOME goal area (HOME takes)
            case GOAL_KICK_AWAY -> new Position(7.5, 3.5); // AWAY goal area (AWAY takes)
            case CORNER_HOME -> new Position(8.0, 1.0); // HOME attacking corner (near AWAY goal)
            case CORNER_AWAY -> new Position(1.0, 1.0); // AWAY attacking corner (near HOME goal)
            case THROW_IN_HOME, THROW_IN_AWAY -> new Position(4.5, 1.0); // sideline
            case FREE_KICK -> new Position(4.5, 4.0); // placeholder
            case PENALTY_HOME -> new Position(7.5, 3.5);
            case PENALTY_AWAY -> new Position(1.5, 3.5);
        };
    }

    private Player selectTaker(MatchState state, RestartType type) {
        // The "_HOME"/"_AWAY" suffix encodes which team TAKES the restart.
        String takingTeam = type.name().endsWith("_AWAY") ? "AWAY" : "HOME";
        Position ballPos = getRestartPosition(type);
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