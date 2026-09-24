package org.example.footballmanager.newLogic.sim.rules;

import org.example.footballmanager.newLogic.sim.engine.EngineInterfaces;
import org.example.footballmanager.newLogic.sim.model.*;

/**
 * Discipline service — foul detection, card issuance, VAR integration.
 *
 * Modular design: each rule type (tackle foul, push, dangerous play,
 * second-yellow, straight-red, penalty-box foul) is a separate private
 * method so new rules can be added without touching existing ones.
 *
 * Placeholder — all methods return safe defaults.  Logic per backlog
 * (PROPOSAL_PROGRESS.md §8.6).
 */
public class DisciplineService implements EngineInterfaces.DisciplineService {

    private final MatchState state;
    private final VARService varService;

    public DisciplineService(MatchState state, VARService varService) {
        this.state = state;
        this.varService = varService;
    }

    @Override
    public DisciplineResult evaluateFoul(MatchState state) {
        Player attacker = state.getCarrier();
        Player defender = state.getLastTouchPlayer();
        boolean hadDuel = state.hasPendingVARReview();

        // No carrier and no pending duel => no card, just free kick / play on
        if (attacker == null && !hadDuel) {
            return new DisciplineResult(false, false, false, false, false, "No carrier or duel active");
        }

        // Determine the team defending the foul
        String defendingTeam = attacker != null ? attacker.getTeam() : "HOME";
        if (defender != null) defendingTeam = defender.getTeam();

        // Compute foul position: deeper of attacker/defender toward opponent goal
        Position foulPos = null;
        boolean inPenaltyBox = false;
        if (attacker != null && defender != null) {
            Position aPos = attacker.getPosition();
            Position dPos = defender.getPosition();
            boolean homeAttacking = "HOME".equals(attacker.getTeam());
            // foul pos = whichever player is deeper toward the opponent goal
            if (homeAttacking) {
                foulPos = aPos.getRow() >= dPos.getRow() ? aPos : dPos;
            } else {
                foulPos = aPos.getRow() <= dPos.getRow() ? aPos : dPos;
            }
            // penalty area check: row near goal line (HOME: row>=7, AWAY: row<=1)
            // and columns 2-5 (not full width)
            inPenaltyBox = homeAttacking
                    ? (foulPos.getRow() >= 7.0 && foulPos.getColumn() >= 2 && foulPos.getColumn() <= 5)
                    : (foulPos.getRow() <= 1.0 && foulPos.getColumn() >= 2 && foulPos.getColumn() <= 5);
        }

        // Use VARService to determine card and penalty decisions
        boolean redConfirmed = false;
        boolean yellowConfirmed = false;
        boolean penaltyAwarded = false;
        boolean freeKickAwarded = false;
        Player freeKickTaker = null;
        Player penaltyTaker = null;

        if (defender != null) {
            // VAR check for red card (standalone, not second-yellow context)
            redConfirmed = varService.checkRedCard(defender, false);

            // VAR check for yellow card
            String varYellowResult = varService.checkYellowCard(defender);
            if ("UPGRADE_TO_RED".equals(varYellowResult)) {
                // VAR upgraded yellow → red
                redConfirmed = true;
                yellowConfirmed = false;
            } else if ("DOWNGRADE_TO_NONE".equals(varYellowResult)) {
                // VAR downgraded yellow → no card, play continues
                yellowConfirmed = false;
                redConfirmed = false;
            } else {
                // Yellow confirmed by VAR
                yellowConfirmed = true;
            }
        }

        // Penalty-box gate (35% random) vs free kick
        if (inPenaltyBox && varService.checkPenalty(foulPos, "HOME".equals(attacker.getTeam()))) {
            penaltyAwarded = true;
            penaltyTaker = attacker;
        } else {
            freeKickAwarded = true;
            freeKickTaker = attacker;
        }

        // Build description string
        String description = "";
        if (penaltyAwarded) description = "Penalty awarded";
        else if (yellowConfirmed) description = "Yellow card";
        else if (redConfirmed) description = "Red card";
        else if (freeKickAwarded) description = "Free kick";

        // Record global stats (commented — proposal MatchState only has global counters)
        // if (defender != null) state.incrementFouls();
        // if (yellowConfirmed) state.incrementYellowCards();
        // if (redConfirmed) state.incrementRedCards();

        // Return the 6-field DisciplineResult matching the proposal surface
        return new DisciplineResult(true, yellowConfirmed, redConfirmed, penaltyAwarded, freeKickAwarded, description);
    }

    // --- Individual rule methods (add rules here) ---

    /** Tackle from behind — automatic yellow unless last-man (red). */
    private boolean isTackleFromBehind(Player defender, Player attacker) {
        // TODO: check relative positions, tackle direction
        return false;
    }

    /** Dangerous play without contact — caution (yellow). */
    private boolean isDangerousPlay(Player defender, Player attacker) {
        // TODO: e.g. high boot, studs-up, late challenge
        return false;
    }

    /** Professional foul — last-man stopping clear goal-scoring opportunity = red. */
    private boolean isProfessionalFoul(Player defender, Player attacker) {
        // TODO: check if attacker was through on goal
        return false;
    }

    /** Inside penalty area → penalty instead of direct FK. */
    private boolean isInsidePenaltyArea(Position foulPosition, boolean homeAttacking) {
        // TODO: use pitch geometry (HOME penalty area rows 1-1.5, cols 2-6)
        return false;
    }
}
