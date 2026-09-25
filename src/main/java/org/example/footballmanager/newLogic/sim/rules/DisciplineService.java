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
 * sanction follows — yellow / red / penalty. Cards are seeded through
 * {@link SimulationRandom} so matches stay reproducible.
 *
 * Sanctions:
 * <ul>
 *   <li>second yellow from the SAME player in this match auto-upgrades to red
 *       (FIFA Law 12) — the player is sent off ({@link Player#setSentOff}),
 *       which every engine respects (10v11: no ball/target/duel/restart for
 *       him).</li>
 *   <li>a red card (straight or second yellow) goes to VAR
 *       ({@link VARService#checkRedCard}) unless this service is constructed
 *       with {@code null}.</li>
 *   <li>a yellow may be VAR-reviewed ({@link VARService#checkYellowCard}:
 *       upgrade to red / downgrade to none).</li>
 *   <li>a penalty call may be VAR-reviewed ({@link VARService#checkPenalty}
 *       : overturn → free kick).</li>
 * </ul>
 */
public class DisciplineService implements EngineInterfaces.DisciplineService {

    private final VARService varService;

    public DisciplineService(MatchState state, VARService varService) {
        this.varService = varService;
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

        state.incrementFouls();
        boolean homeAttacking = "HOME".equals(attacker.getTeam());
        boolean penaltyAwarded = isInsidePenaltyArea(attacker, defender);
        boolean freeKickAwarded = !penaltyAwarded;
        String varDecision = "NONE";

        // Penalty call reviewed by VAR first — an overturn downgrades to free kick.
        if (penaltyAwarded && varService != null
                && !varService.checkPenalty(attacker.getPosition(), homeAttacking)) {
            penaltyAwarded = false;
            freeKickAwarded = true;
            varDecision = reviewDecision();
        }

        // Baseline sanction BEFORE the card's own VAR review: straight red is
        // rare; otherwise a yellow-worthy foul (35%).
        boolean straightRedRolled = SimulationRandom.nextDouble() < 0.02;
        boolean yellowWorthy = !straightRedRolled && SimulationRandom.nextDouble() < 0.35;

        boolean yellowConfirmed = false;
        boolean redConfirmed = false;

        if (straightRedRolled) {
            redConfirmed = true;
            if (varService != null) {
                redConfirmed = varService.checkRedCard(defender, false); // true = confirmed
                varDecision = reviewDecision();
            }
        } else if (yellowWorthy && varService != null) {
            // VAR reviews the caution: may upgrade to red or downgrade to nothing.
            String varYellow = varService.checkYellowCard(defender);
            if ("UPGRADE_TO_RED".equals(varYellow)) {
                redConfirmed = true;
            } else if ("DOWNGRADE_TO_NONE".equals(varYellow)) {
                yellowConfirmed = false;
            } else {
                yellowConfirmed = true;
            }
            varDecision = reviewDecision();
        } else {
            yellowConfirmed = yellowWorthy;
        }

        // FIFa Law 12: a second caution in the same match is an automatic red.
        // VAR never overturns a second yellow (checkRedCard returns confirmed).
        boolean secondYellow = false;
        if (yellowConfirmed && defender.getYellowCardsInMatch() >= 1) {
            secondYellow = true;
            yellowConfirmed = false;
            redConfirmed = true;
            if (varService != null) {
                varService.checkRedCard(defender, true);
                varDecision = reviewDecision();
            }
        }

        // Apply the sanction to the player + match counters.
        if (yellowConfirmed) {
            defender.incrementYellowCardsInMatch();
            state.incrementYellowCards();
        }
        if (redConfirmed) {
            state.incrementRedCards();
            defender.setSentOff(true);
            if (defender.getSentOffTick() < 0) {
                defender.setSentOffTick(state.getMatchTicks());
            }
        }

        String description = secondYellow ? "Second yellow -> red card"
                : penaltyAwarded ? "Penalty awarded"
                : redConfirmed ? "Red card"
                : yellowConfirmed ? "Yellow card" : "Free kick";

        return new DisciplineResult(true, yellowConfirmed, redConfirmed,
                penaltyAwarded, freeKickAwarded, description, varDecision);
    }

    /** Surfaces a VAR verdict only for a REAL review — noise gates ("NO_REVIEW") stay "NONE". */
    private String reviewDecision() {
        if (varService == null) return "NONE";
        String d = varService.getLastVARDecision();
        if (d == null || d.equals("NONE") || d.equals("NO_REVIEW")) return "NONE";
        return d;
    }

    /** Foul is a spot kick when it lands in the defensive penalty area:
     *  HOME defends rows ≤ 2.5, AWAY defends rows ≥ 6.5, cols 2–5. */
    private boolean isInsidePenaltyArea(Player attacker, Player defender) {
        Position aPos = attacker.getPosition();
        Position dPos = defender.getPosition();
        if (aPos == null || dPos == null) return false;
        boolean homeAttacking = "HOME".equals(attacker.getTeam());
        double row = homeAttacking
                ? Math.max(aPos.getRow(), dPos.getRow())
                : Math.min(aPos.getRow(), dPos.getRow());
        double col = (aPos.getColumn() + dPos.getColumn()) / 2.0;
        // The box band must be mirrored about 4.5. HOME attacks a box in front of
        // the AWAY goal (row 8.0), so the band starts 1.5 cells out: row >= 6.5.
        // AWAY therefore needs 1.0 + 1.5 = row <= 2.5, not row <= 1.5 — the old
        // bound made HOME's penalty area 1.5 cells deep and AWAY's only 0.5, a
        // 3x one-directional difference in how often fouls became penalties.
        boolean inBox = homeAttacking
                ? (row >= 6.5 && col >= 2 && col <= 5)
                : (row <= 2.5 && col >= 2 && col <= 5);
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
