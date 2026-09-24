package org.example.footballmanager.newLogic.sim.rules;

import org.example.footballmanager.newLogic.sim.engine.EngineInterfaces;
import org.example.footballmanager.newLogic.sim.model.*;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

/**
 * Discipline service — foul detection, card issuance, VAR integration.
 *
 * The wiring layer (DuelService) calls {@link #evaluateFoul(MatchState)} AFTER
 * a defender wins a defensive duel on a ball carrier and sets the defending
 * player as {@code lastTouchPlayer}. This implementation decides *whether* a
 * foul occurred (probability-gated by the defender's tackling skill) and what
 * sanction follows — yellow / straight-red / penalty-box penalty. Cards are
 * seeded through {@link SimulationRandom} so matches stay reproducible.
 */
public class DisciplineService implements EngineInterfaces.DisciplineService {

    public DisciplineService(MatchState state, VARService varService) {
    }

    @Override
    public DisciplineResult evaluateFoul(MatchState state) {
        Player attacker = state.getCarrier();
        Player defender = state.getLastTouchPlayer();

        // Only evaluate a genuine defensive contest on a ball carrier.
        if (attacker == null || defender == null) {
            return new DisciplineResult(false, false, false, false, false, "No contest");
        }

        // Foul probability follows the defender's tackling skill: skilled
        // defenders win the ball cleanly, weaker ones mistime the challenge.
        // Base 0.13 at skill 10 → 0.06 at skill 20, 0.20 at skill 1.
        double defenderSkill = defender.getSkills() != null
                ? defender.getSkills().defender() : 10.0;
        double foulProb = Math.max(0.05, Math.min(0.22, 0.16 - defenderSkill * 0.005));

        if (SimulationRandom.nextDouble() >= foulProb) {
            return new DisciplineResult(false, false, false, false, false, "Clean tackle");
        }

        // Foul confirmed — determine the sanction.
        boolean redConfirmed = SimulationRandom.nextDouble() < 0.02;   // straight red (rare)
        boolean yellowConfirmed = !redConfirmed && SimulationRandom.nextDouble() < 0.35;

        // Penalty instead of free kick when the foul lands in the penalty area.
        boolean penaltyAwarded = isInsidePenaltyArea(attacker, defender);
        boolean freeKickAwarded = !penaltyAwarded;

        state.incrementFouls();
        if (yellowConfirmed) state.incrementYellowCards();
        if (redConfirmed) state.incrementRedCards();

        String description = penaltyAwarded ? "Penalty awarded"
                : redConfirmed ? "Red card"
                : yellowConfirmed ? "Yellow card" : "Free kick";

        return new DisciplineResult(true, yellowConfirmed, redConfirmed,
                penaltyAwarded, freeKickAwarded, description);
    }

    /** Foul is a spot kick when it lands in the defensive penalty area:
     *  HOME defends rows ≤ 1.5, AWAY defends rows ≥ 6.5, cols 2–5. */
    private boolean isInsidePenaltyArea(Player attacker, Player defender) {
        Position aPos = attacker.getPosition();
        Position dPos = defender.getPosition();
        if (aPos == null || dPos == null) return false;
        boolean homeAttacking = "HOME".equals(attacker.getTeam());
        double row = homeAttacking
                ? Math.max(aPos.getRow(), dPos.getRow())
                : Math.min(aPos.getRow(), dPos.getRow());
        double col = (aPos.getColumn() + dPos.getColumn()) / 2.0;
        boolean inBox = homeAttacking
                ? (row >= 6.5 && col >= 2 && col <= 5)
                : (row <= 1.5 && col >= 2 && col <= 5);
        return inBox;
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
}
