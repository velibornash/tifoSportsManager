package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.result.ActionLogService;
import org.example.footballmanager.demo.service.result.MatchStatsCollector;

/**
 * Handles offside checks + VAR review + indirect free kick awarding.
 *
 * Extracted from MatchSimulator.executeDecision() (Phase 2) to eliminate
 * the duplicated ~30-line offside handling block that appeared in both
 * PASS and THRU branches.
 *
 * This service DOES mutate MatchState (stats, consecutive offside counts,
 * ball carrier via actionEngine.giveBallTo).
 */
public class OffsideService {

    private final VARService varService;
    private final FootballRulesService rulesService;
    private final ActionLogService logger;
    private final PlayerSelectionEngine selection;
    private final MatchStatsCollector stats;

    public OffsideService(VARService varService, FootballRulesService rulesService,
                          ActionLogService logger, PlayerSelectionEngine selection,
                          MatchStatsCollector stats) {
        this.varService = varService;
        this.rulesService = rulesService;
        this.logger = logger;
        this.selection = selection;
        this.stats = stats;
    }

    /**
     * Result of an offside check, telling the caller what to do.
     */
    public record OffsideResult(boolean confirmed, boolean wasChecked) {}

    /**
     * Check if a receiver is offside on a forward pass.
     *
     * @param receiver       the player being passed to
     * @param passOrigin     the passer's position
     * @param ballPos        current ball position
     * @param carrierTeam    the team with the ball ("HOME" or "AWAY")
     * @param state          current match state
     * @param actionEngine   for giving the ball to the free-kick taker
     * @return OffsideResult indicating what happened
     */
    public OffsideResult checkOffside(Player receiver, Position passOrigin, Position ballPos,
                                       String carrierTeam, MatchState state, ActionEngine actionEngine) {
        // Offside only applies to forward passes during normal play.
        // Skip if kickoff or not actually offside.
        // NOTE: No distance exemption — real football has none.
        if (state.isKickoffActionPending()
                || !rulesService.isOffside(receiver, passOrigin, ballPos)) {
            return new OffsideResult(false, false);
        }

        // VAR check — close offside calls get reviewed
        Player[] defenders = state.getPlayers().stream()
                .filter(p -> !p.getTeam().equals(carrierTeam) && !"GK".equals(p.getRole()))
                .toArray(Player[]::new);
        boolean confirmed = varService.checkOffside(receiver, passOrigin, defenders);
        varService.logVARDecision("OFFSIDE", receiver.getLabel());

        if (confirmed) {
            stats.onOffside(carrierTeam);
            receiver.incrementConsecutiveOffside();

            String defendingTeam = "HOME".equals(carrierTeam) ? "AWAY" : "HOME";
            Player freeKickTaker = selection.anyGoalkeeper(defendingTeam);
            if (freeKickTaker == null) {
                freeKickTaker = selection.nearestNonGoalkeeperTo(receiver.getPosition(), defendingTeam);
            }

            if (freeKickTaker != null) {
                actionEngine.giveBallTo(freeKickTaker,
                        "offside → indirect free kick for " + defendingTeam);
                state.setSetPiecePending(true);
            } else {
                actionEngine.executeClearance();
            }

            return new OffsideResult(true, true);
        } else {
            // VAR overturned — onside, play continues
            receiver.resetConsecutiveOffside();
            return new OffsideResult(false, true);
        }
    }
}
