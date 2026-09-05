package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.result.ActionLogService;
import org.example.footballmanager.demo.service.result.MatchStatsCollector;

/**
 * Handles foul detection, card assignment, VAR review, and penalty/free-kick
 * awarding after a duel is resolved.
 *
 * Extracted from MatchSimulator.recordDuelStats() (Phase 3) to enforce the
 * corePrinciples §37 boundary: the orchestrator delegates discipline decisions
 * here rather than embedding ~160 lines of card/VAR/penalty logic inline.
 *
 * This service DOES mutate MatchState (cards, sent-off, ball carrier, stats).
 */
public class DisciplineService {

    private final VARService varService;
    private final FootballRulesService rulesService;
    private final ActionLogService logger;
    private final PlayerSelectionEngine selection;
    private final MatchStatsCollector stats;

    public DisciplineService(VARService varService, FootballRulesService rulesService,
                             ActionLogService logger, PlayerSelectionEngine selection,
                             MatchStatsCollector stats) {
        this.varService = varService;
        this.rulesService = rulesService;
        this.logger = logger;
        this.selection = selection;
        this.stats = stats;
    }

    /**
     * Result of a discipline evaluation, telling the caller what to do.
     */
    public record DisciplineResult(
            boolean foul,
            boolean redCardIssued,
            boolean yellowCardIssued,
            boolean penaltyAwarded,
            Player freeKickTaker,       // null if penalty was awarded instead
            Player penaltyTaker,        // null if free kick was awarded
            Player fouledPlayer,        // the attacker who was fouled
            boolean playContinues       // true when VAR downgraded to no card and no penalty
    ) {}

/**
     * Evaluate a foul after a defender wins a duel.
     *
     * @param state          current match state
     * @param actionEngine   for giving ball to free-kick/penalty taker
     * @param attacker       the player who was fouled
     * @param defender       the player who committed the foul
     * @param duelType       what kind of duel this was
     * @param hadDuel        true if a duel was actively resolved this tick (card can only be issued if true)
     * @return DisciplineResult telling the caller what action to take
     */
    public DisciplineResult evaluateFoul(MatchState state, ActionEngine actionEngine,
                                           Player attacker, Player defender, DuelType duelType,
                                           boolean hadDuel) {
        // A card/yVAR can only be issued if a duel was actively resolved this tick.
        // If no duel occurred, award a free kick without card (same as handleNoCard logic).
        if (!hadDuel) {
            return handleNoCard(state, actionEngine, defender, attacker,
                    defender.getTeam(), false, false, null);
        }
        // Shot duels: GK save is not a foul — skip foul check
        if (duelType == DuelType.SHOT) {
            logger.logInfo(state, "Shot saved by " + defender.getLabel()
                    + " — clean save", "DUEL", defender);
            actionEngine.shotSaved(defender);
            return new DisciplineResult(false, false, false, false, null, null, null, false);
        }

        boolean foul = rulesService.isFoul(defender, attacker);
        if (!foul) {
            // Clean tackle — defender wins and keeps the ball
            actionEngine.giveBallTo(defender, "clean tackle won");
            return new DisciplineResult(false, false, false, false, null, null, null, false);
        }

        // Foul confirmed
        String defendingTeam = defender.getTeam();
        stats.onFoul(defendingTeam, defender.getId());

        int existingYellows = state.getYellowCardCount(defender.getId());
        FootballRulesService.CardType card =
                rulesService.determineCard(defender, existingYellows >= 1);

        // Check if foul is in the penalty box
        // Real penalty box: ~1 row deep (row 7 for HOME, row 1 for AWAY)
        // and columns 2-5 (not full width — excludes wide areas)
        boolean homeAttacking = "HOME".equals(attacker.getTeam());
        Position defenderPos = defender.getPosition();
        Position attackerPos = attacker.getPosition();
        Position foulPos = homeAttacking
                ? (defenderPos.getRow() >= attackerPos.getRow() ? defenderPos : attackerPos)
                : (defenderPos.getRow() <= attackerPos.getRow() ? defenderPos : attackerPos);
        // Penalty box foul → ~35% actually result in penalty (the user wants
        // "ponekad penal" — occasionally, NOT 2-3 per match — so we aim for
        // roughly 1 penalty every 3-4 matches: with ~6-9 fouls per match and
        // ~1-2 in the box, a 35% gate yields ~0.3 penalties per match on
        // average. Real football is 3-5% of fouls but with 20+ fouls per
        // match, equivalent here).
        boolean inPenaltyBoxRaw = homeAttacking
                ? (foulPos.getRow() >= 7 && foulPos.getColumn() >= 2 && foulPos.getColumn() <= 5)
                : (foulPos.getRow() <= 1 && foulPos.getColumn() >= 2 && foulPos.getColumn() <= 5);
        boolean inPenaltyBox = inPenaltyBoxRaw && state.getRandom().nextDouble() < 0.35;

        if (card == FootballRulesService.CardType.RED) {
            return handleRedCard(state, actionEngine, defender, attacker, defendingTeam,
                    existingYellows, inPenaltyBox, homeAttacking, foulPos);
        } else if (card == FootballRulesService.CardType.YELLOW) {
            return handleYellowCard(state, actionEngine, defender, attacker, defendingTeam,
                    existingYellows, inPenaltyBox, homeAttacking, foulPos);
        } else {
            return handleNoCard(state, actionEngine, defender, attacker, defendingTeam,
                    inPenaltyBox, homeAttacking, foulPos);
        }
    }

    /**
     * Awards an indirect free kick to the fouled attacker's team. The ball is
     * placed at the foul spot, opponents within 1 cell are pushed back (via
     * MovementEngine.enforceRestartPushback during the next walk tick), the
     * attacker is designated as the free-kick taker, and the carrier is left
     * NULL so the restart-walk logic in MatchSimulator kicks in (the taker
     * walks to the ball at RESTART_WALK_SPEED rather than being teleported).
     *
     * The last-touch team is set to the attacker's team so that any subsequent
     * OOB (e.g. a shot going out) awards possession to the defending side.
     */
    private void awardFreeKick(MatchState state, ActionEngine actionEngine,
                               Player attacker, Position foulSpot) {
        Ball ball = state.getBall();
        ball.setCarrier(null);
        ball.setTarget(null);
        state.setCarrier(null);
        actionEngine.complete("FREE KICK → " + attacker.getTeam());
        ball.setPosition(foulSpot);
        state.setFreeKickTaker(attacker);
        state.setSetPiecePending(true);
        state.setLastTouchTeam(attacker.getTeam());
    }

    private DisciplineResult handleRedCard(MatchState state, ActionEngine actionEngine,
                                            Player defender, Player attacker, String defendingTeam,
                                            int existingYellows, boolean inPenaltyBox,
                                            boolean homeAttacking, Position foulPos) {
        boolean redConfirmed = varService.checkRedCard(defender, existingYellows >= 1);
        varService.logVARDecision("RED_CARD", defender.getLabel());

        if (redConfirmed) {
            if (existingYellows >= 1) {
                state.incrementYellowCards(defender.getId());
            }
            stats.onRedCard(defendingTeam, defender.getId());
            defender.setSentOff(true);
            defender.setLocked(true);
            logger.logFoulWithCard(state, defendingTeam, defender.getId(),
                    defender.getLabel(), "RED (VAR " + varService.getLastVARDecision() + ")",
                    existingYellows, "FOUL");
        } else {
            // VAR overturned red → downgrade to yellow
            state.incrementYellowCards(defender.getId());
            stats.onYellowCard(defendingTeam, defender.getId());
            logger.logFoulWithCard(state, defendingTeam, defender.getId(),
                    defender.getLabel(), "YELLOW (VAR overturned red)",
                    existingYellows, "FOUL");
        }

        if (inPenaltyBox) {
            boolean penaltyConfirmed = varService.checkPenalty(foulPos, homeAttacking, true);
            varService.logVARDecision("PENALTY", attacker.getLabel());
            if (penaltyConfirmed) {
                logger.logInfo(state, "PENALTY awarded (foul in box, red card, VAR " + varService.getLastVARDecision() + ")", "PENALTY", attacker);
                return new DisciplineResult(true, redConfirmed, false, true, null, attacker, attacker, false);
            } else {
                logger.logInfo(state, "VAR OVERTURNED penalty — free kick outside box", "VAR", attacker);
                awardFreeKick(state, actionEngine, attacker, foulPos);
                return new DisciplineResult(true, redConfirmed, false, false, attacker, null, attacker, false);
            }
        } else {
            awardFreeKick(state, actionEngine, attacker, foulPos);
            return new DisciplineResult(true, redConfirmed, false, false, attacker, null, attacker, false);
        }
    }

    private DisciplineResult handleYellowCard(MatchState state, ActionEngine actionEngine,
                                               Player defender, Player attacker, String defendingTeam,
                                               int existingYellows, boolean inPenaltyBox,
                                               boolean homeAttacking, Position foulPos) {
        String varResult = varService.checkYellowCard(defender);
        varService.logVARDecision("YELLOW_CARD", defender.getLabel());

        if ("UPGRADE_TO_RED".equals(varResult)) {
            // VAR upgraded yellow → red
            state.incrementYellowCards(defender.getId());
            stats.onRedCard(defendingTeam, defender.getId());
            defender.setSentOff(true);
            defender.setLocked(true);
            logger.logFoulWithCard(state, defendingTeam, defender.getId(),
                    defender.getLabel(), "RED (VAR upgraded yellow)", existingYellows, "FOUL");
            if (inPenaltyBox) {
                boolean penaltyConfirmed = varService.checkPenalty(foulPos, homeAttacking, true);
                varService.logVARDecision("PENALTY", attacker.getLabel());
                if (penaltyConfirmed) {
                    logger.logInfo(state, "PENALTY awarded (foul in box, red card)", "PENALTY", attacker);
                    return new DisciplineResult(true, true, false, true, null, attacker, attacker, false);
                } else {
                    awardFreeKick(state, actionEngine, attacker, foulPos);
                    return new DisciplineResult(true, true, false, false, attacker, null, attacker, false);
                }
            }
            awardFreeKick(state, actionEngine, attacker, foulPos);
            return new DisciplineResult(true, true, false, false, attacker, null, attacker, false);

        } else if ("DOWNGRADE_TO_NONE".equals(varResult)) {
            // VAR downgraded yellow → no card, play continues
            logger.logInfo(state, "VAR DOWNGRADED yellow card — no card given", "VAR", defender);
            awardFreeKick(state, actionEngine, attacker, foulPos);
            return new DisciplineResult(true, false, false, false, attacker, null, attacker, true);

        } else {
            // Yellow confirmed — BUG FIX: give the ball to the ATTACKER (fouled team),
            // NOT the defender. Previously called giveBallTo(defender, ...) which
            // incorrectly awarded possession to the player who committed the foul.
            state.incrementYellowCards(defender.getId());
            stats.onYellowCard(defendingTeam, defender.getId());
            logger.logFoulWithCard(state, defendingTeam, defender.getId(),
                    defender.getLabel(), "YELLOW (VAR confirmed)", existingYellows, "FOUL");
            if (inPenaltyBox) {
                boolean penaltyConfirmed = varService.checkPenalty(foulPos, homeAttacking, true);
                varService.logVARDecision("PENALTY", attacker.getLabel());
                if (penaltyConfirmed) {
                    logger.logInfo(state, "PENALTY awarded (foul in box, yellow card, VAR " + varService.getLastVARDecision() + ")", "PENALTY", attacker);
                    return new DisciplineResult(true, false, true, true, null, attacker, attacker, false);
                } else {
                    logger.logInfo(state, "VAR OVERTURNED penalty — free kick outside box", "VAR", attacker);
                    awardFreeKick(state, actionEngine, attacker, foulPos);
                    return new DisciplineResult(true, false, true, false, attacker, null, attacker, false);
                }
            }
            awardFreeKick(state, actionEngine, attacker, foulPos);
            return new DisciplineResult(true, false, true, false, attacker, null, attacker, false);
        }
    }

    private DisciplineResult handleNoCard(MatchState state, ActionEngine actionEngine,
                                           Player defender, Player attacker, String defendingTeam,
                                           boolean inPenaltyBox, boolean homeAttacking, Position foulPos) {
        if (inPenaltyBox) {
            boolean penaltyConfirmed = varService.checkPenalty(foulPos, homeAttacking, true);
            varService.logVARDecision("PENALTY", attacker.getLabel());
            if (penaltyConfirmed) {
                logger.logInfo(state, "PENALTY awarded (foul in box, VAR " + varService.getLastVARDecision() + ")", "PENALTY", attacker);
                return new DisciplineResult(true, false, false, true, null, attacker, attacker, false);
            } else {
                logger.logInfo(state, "VAR OVERTURNED penalty — free kick outside box", "VAR", attacker);
                awardFreeKick(state, actionEngine, attacker, foulPos);
                return new DisciplineResult(true, false, false, false, attacker, null, attacker, false);
            }
        }
        logger.logFoul(state, defendingTeam, defender.getId(),
                defender.getLabel(), "Tackle foul (no card) → FREE KICK", "FREE_KICK");
        awardFreeKick(state, actionEngine, attacker, foulPos);
        return new DisciplineResult(true, false, false, false, attacker, null, attacker, false);
    }
}
