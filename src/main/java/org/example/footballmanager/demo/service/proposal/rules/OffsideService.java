package org.example.footballmanager.demo.service.proposal.rules;

import org.example.footballmanager.demo.service.proposal.engine.EngineInterfaces;
import org.example.footballmanager.demo.service.proposal.model.*;
import java.util.List;

/**
 * Offside service — continuous tracking + per-pass check + VAR integration.
 *
 * Responsibilities:
 * - Track offside positions for both teams every tick
 * - Check offside at pass/cross/through-ball moment
 * - Calculate offside margin (second-to-last defender)
 * - Flag offenders (consecutiveOffsideCount++)
 * - Trigger VAR offside review
 *
 * NOTE: offside retreat (pulling back an attacker who keeps getting caught)
 * is handled by ThreatOverrideEngine (TYPE C) — not here.  This service
 * only detects offside and flags the offender.
 *
 * Placeholder — all methods return safe defaults.
 */
public class OffsideService implements EngineInterfaces.OffsideService {

    private final MatchState state;
    private final VARService varService;

    public OffsideService(MatchState state, VARService varService) {
        this.state = state;
        this.varService = varService;
    }

    @Override
    public void trackOffsidePositions(MatchState state) {
        // TODO per backlog:
        // 1. For each attacking player (both teams), compute offside line
        // 2. If player is ahead of second-to-last defender AND forward of ball
        //    → increment consecutiveOffsideCount
        // 3. If player returns onside → reset consecutiveOffsideCount
        // 4. Emit OFFSIDE event for stats
    }

    @Override
    public OffsideResult checkOffside(Player receiver, Position passOrigin, MatchState state) {
        // TODO per backlog:
        // 1. Compute offside line from second-to-last defender
        // 2. Check if receiver is ahead of that line AND forward of pass origin
        // 3. Apply margin tolerance (~0.2 cells)
        // 4. If offside: flag receiver, trigger VAR review (frequency gate ~20%)
        // 5. Return OffsideResult with confirmation
        return new OffsideResult(false, false);
    }

    @Override
    public void resolvePendingVAROffside(MatchState state) {
        // TODO: after VAR review, confirm or overturn offside call
        // Confirm → free kick for defending team
        // Overturn → play continues from where ball was
    }

    // --- Helpers ---

    /**
     * Compute the offside line (row of the second-to-last defender).
     * Home attacks toward row 8; away attacks toward row 1.
     */
    private double computeOffsideLine(MatchState state, boolean home) {
        String defendingTeam = home ? "AWAY" : "HOME";
        List<Player> defenders = state.getPlayers().stream()
                .filter(p -> defendingTeam.equals(p.getTeam()))
                .filter(p -> !p.isSentOff() && !p.isInjured())
                .toList();
        if (defenders.size() < 2) return home ? 8.0 : 1.0;

        List<Double> rows = defenders.stream()
                .map(p -> p.getPosition().getRow())
                .sorted(home ? java.util.Comparator.<Double>reverseOrder()
                             : java.util.Comparator.naturalOrder())
                .toList();
        return rows.get(1); // second-to-last
    }

    /** Compute offside margin: how far ahead of the line the player is. */
    private double offsideMargin(Player receiver, double offsideLine, boolean home) {
        double receiverRow = receiver.getPosition().getRow();
        return home ? receiverRow - offsideLine : offsideLine - receiverRow;
    }
}
