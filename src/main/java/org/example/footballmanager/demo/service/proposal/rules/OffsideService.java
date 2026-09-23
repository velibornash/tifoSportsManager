package org.example.footballmanager.demo.service.proposal.rules;

import org.example.footballmanager.demo.service.proposal.engine.EngineInterfaces;
import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import org.example.footballmanager.demo.service.proposal.model.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OffsideService: per-pass FIFA Law 11 check with margin bands and VAR
 * integration (proposal surface port).
 *
 * Band rules (margin = receiver forward of the second-to-last defender incl. GK):
 *   margin > 0.5        CLEAR offside, whistle + indirect FK
 *   0 < margin <= 0.5   MARGINAL, flag held, play continues; VAR confirms offside
 *                       only if the next action attacked forward or led to a GOAL
 *   -0.8 < margin <= 0  tight onside margin, check held; VAR confirms ONSIDE only
 *                       if the next action was a GOAL
 *   margin <= -0.8      clear onside, no call
 */
public class OffsideService implements EngineInterfaces.OffsideService {

    /**
     * Diagnostic flag (ProposalPhysicsDiagnostic): print a trace for every PASS
     * offside check — receiver position, second-to-last defender's distance from
     * the goal line, and whether the receiver was actually forward of the ball /
     * in the opponent's half (FIFA Law 11 preconditions). Behavior is unchanged;
     * this is stdout-only diagnostics.
     */
    public static boolean TRACE = false;

    private final MatchState state;
    private final VARService varService;

    public OffsideService(MatchState state, VARService varService) {
        this.state = state;
        this.varService = varService;
    }

    @Override
    public void trackOffsidePositions(MatchState state) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;
        Position origin = carrier.getPosition();
        for (Player p : state.getPlayers()) {
            if ("GK".equals(p.getRole())) continue;
            if (p == carrier) continue;
            if (p.isSentOff() || p.isInjured()) continue;
            boolean home = "HOME".equals(p.getTeam());
            String defendingTeam = home ? "AWAY" : "HOME";

            boolean forward = home
                    ? p.getPosition().getRow() > origin.getRow()
                    : p.getPosition().getRow() < origin.getRow();
            if (!forward) { p.resetConsecutiveOffside(); continue; }

            int opponentsGoalSide = 0;
            for (Player opp : state.getPlayers()) {
                if (!defendingTeam.equals(opp.getTeam())) continue;
                if (opp.isSentOff() || opp.isInjured()) continue;
                boolean goalSide = home
                        ? opp.getPosition().getRow() > p.getPosition().getRow()
                        : opp.getPosition().getRow() < p.getPosition().getRow();
                if (goalSide) opponentsGoalSide++;
            }
            if (opponentsGoalSide < 2) {
                p.incrementConsecutiveOffside();
            } else {
                p.resetConsecutiveOffside();
            }
        }
    }

    @Override
    public OffsideResult checkOffside(Player receiver, Position passOrigin, MatchState state) {
        if (state.isKickoffPending() || state.getSetPieceType() != null) {
            return new OffsideResult(false, false);
        }

        double margin = calculateOffsideMargin(receiver, passOrigin, state);

        if (TRACE) {
            boolean home = "HOME".equals(receiver.getTeam());
            List<Double> defRows = new ArrayList<>();
            String defendingTeam = home ? "AWAY" : "HOME";
            for (Player opp : state.getPlayers()) {
                if (!defendingTeam.equals(opp.getTeam())) continue;
                if (opp.isSentOff() || opp.isInjured()) continue;
                defRows.add(opp.getPosition().getRow());
            }
            defRows.sort(home ? Comparator.reverseOrder() : Comparator.naturalOrder());
            double lineRow = defRows.size() < 2
                    ? (home ? 0.0 : 9.0)
                    : defRows.get(1);
            double goalLineDist = home ? 8.0 - lineRow : lineRow - 1.0;
            boolean forwardOfBall = home
                    ? receiver.getPosition().getRow() > passOrigin.getRow()
                    : receiver.getPosition().getRow() < passOrigin.getRow();
            boolean inOppHalf = home
                    ? receiver.getPosition().getRow() > 4.5
                    : receiver.getPosition().getRow() < 4.5;
            String band = margin > 0.5 ? "CLEAR-OFF(whistle)"
                    : margin > 0 ? "MARG-OFF(VAR hold)"
                    : margin > -0.8 ? "TIGHT-ON(VAR hold)" : "ONSIDE";
            System.out.printf("[OFF-TRACE] pass %s(%s) -> receiver %s at (%.2f,%.2f) | ball at strike (%.2f,%.2f) | "
                            + "last-2-def row %.2f & 2nd-last %.2f (%.2f cells off goal line) | forward-of-ball:%b in-opp-half:%b | margin %+.3f -> %s%n",
                    state.getCarrier() == null ? "?" : state.getCarrier().getLabel(),
                    "HOME".equals(receiver.getTeam()) ? "H" : "A",
                    receiver.getLabel(), receiver.getPosition().getRow(), receiver.getPosition().getColumn(),
                    passOrigin.getRow(), passOrigin.getColumn(),
                    defRows.isEmpty() ? Double.NaN : defRows.get(0),
                    lineRow, goalLineDist,
                    forwardOfBall, inOppHalf, margin, band);
        }

        if (margin > 0.5) {
            return confirmOffside(receiver, carrierTeam(receiver, state), state,
                    "CLEAR offside (margin=" + String.format("%.2f", margin) + ")");
        }

        if (margin > 0) {
            state.setPendingVARReview("OFFSIDE", receiver,
                    "HOME".equals(receiver.getTeam()) ? "AWAY" : "HOME");
            state.setOffsideDeferred(true);
            state.setOffsideDeferredMargin(margin);
            state.setOffsideLedToGoal(false);
            state.setOffsideDeferredActionCount(state.getOffsideDeferredActionCount() + 1);
            return new OffsideResult(false, true);
        }

        if (margin > -0.8) {
            state.setPendingVARReview("ONSIDE_CHECK", receiver,
                    "HOME".equals(receiver.getTeam()) ? "AWAY" : "HOME");
            state.setOffsideDeferred(true);
            state.setOffsideDeferredMargin(margin);
            state.setOffsideLedToGoal(false);
            state.setOffsideDeferredActionCount(state.getOffsideDeferredActionCount() + 1);
            return new OffsideResult(false, true);
        }

        return new OffsideResult(false, false);
    }

    @Override
    public void resolvePendingVAROffside(MatchState state) {
        if (!state.hasPendingVARReview()) return;
        Player receiver = state.getPendingVARReviewPlayer();
        if (receiver == null) { state.clearPendingVARReview(); return; }

        if (!state.isOffsideDeferred()) {
            state.clearPendingVARReview();
            return;
        }

        if (state.isOffsideLedToGoal()) {
            boolean offside = varService.checkOffside(receiver,
                    state.getBall().getPosition(), state);
            if (offside) {
                confirmOffside(receiver, carrierTeam(receiver, state), state,
                        "VAR offside on goal - goal disallowed");
                return;
            }
            state.clearPendingVARReview();
            return;
        }

        if (state.isOffsideDeferredDecisionForward()) {
            confirmOffside(receiver, carrierTeam(receiver, state), state,
                    "offside (deferred, forward-attack)");
            return;
        }

        state.clearPendingVARReview();
    }

    private OffsideResult confirmOffside(Player receiver, String carrierTeam, MatchState state,
                                         String reason) {
        String defendingTeam = "HOME".equals(carrierTeam) ? "AWAY" : "HOME";
        state.setRestartTeam(defendingTeam);
        state.setCarrier(null);
        state.getBall().setPosition(receiver.getPosition());
        state.clearPendingVARReview();
        return new OffsideResult(true, true);
    }

    private String carrierTeam(Player receiver, MatchState state) {
        return state.getCarrierTeam();
    }

    private double calculateOffsideMargin(Player receiver, Position passOrigin, MatchState state) {
        boolean home = "HOME".equals(receiver.getTeam());
        double receiverRow = receiver.getPosition().getRow();

        // FIFA Law 11 preconditions (a player can only be offside if BOTH hold):
        //  (1) the receiver is in the OPPONENT'S half at the moment the pass is played,
        //  (2) the receiver is FORWARD OF THE BALL (closer to the opponents' goal line).
        // Backward / level passes and own-half receivers are always onside.
        boolean inOppHalf = home ? receiverRow > 4.5 : receiverRow < 4.5;
        boolean forwardOfBall = home
                ? receiverRow > passOrigin.getRow()
                : receiverRow < passOrigin.getRow();
        if (!inOppHalf || !forwardOfBall) {
            return -5.0; // clear onside, no whistle, no VAR hold
        }

        String defendingTeam = home ? "AWAY" : "HOME";
        List<Double> rows = new ArrayList<Double>();
        for (Player opp : state.getPlayers()) {
            if (!defendingTeam.equals(opp.getTeam())) continue;
            if (opp.isSentOff() || opp.isInjured()) continue;
            rows.add(opp.getPosition().getRow());
        }
        if (rows.size() < 2) return home ? 10.0 : -10.0;
        rows.sort(home ? Comparator.reverseOrder() : Comparator.naturalOrder());
        double lineRow = rows.get(1);
        return home
                ? receiverRow - lineRow
                : lineRow - receiverRow;
    }
}

