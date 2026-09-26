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

    /**
     * Share of fouls committed inside the penalty area that become penalties.
     * Real football: a penalty is a rare, denied-chance offence, not the default
     * for contact in the box — roughly 1% of all fouls end up as penalties.
     */
    /**
     * Share of fouls committed inside the penalty area that become penalties.
     *
     * <p>Real football: ~0.27 penalties per match. That is the figure this is calibrated to, and
     * it is reached through two measured quantities rather than assumed:
     *
     * <ul>
     *   <li>the engine commits <b>1.655</b> box fouls per match (over 200 matches, seed 42). Real
     *       football is nearer 2.5-3.5, so box-foul volume is itself on the low side.</li>
     *   <li>VAR confirms <b>99.1%</b> of penalty calls (3 overturns in 331). The review gate and
     *       overturn rate in {@code VARService.checkPenalty} therefore barely touch the total.</li>
     * </ul>
     *
     * <p>So {@code 1.655 x rate x 0.991 = 0.27} gives ~0.165. This was 0.06, which produced
     * 0.07 penalties per match - about a quarter of the real rate - and went unnoticed for the
     * whole life of the engine because an un-taken penalty is indistinguishable in the aggregate
     * from a rare one.
     *
     * <p>1-in-6 box fouls converting sounds high next to a real 1-in-11, and it is: the honest
     * reading is that the engine under-produces box fouls and this constant is compensating. The
     * cleaner fix is more box contact; that is a separate calibration and is logged as S1.7c.
     */
    public static final double PENALTY_FROM_BOX_FOUL = 0.165;

    /** Straight red (violent tackle / DOGSO) as a share of all fouls. */
    public static final double STRAIGHT_RED_RATE = 0.004;

    /** Caution as a share of all fouls (real: ~3-4 yellows a match a side). */
    public static final double YELLOW_RATE = 0.20;

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
        // A foul inside the box is NOT automatically a penalty — that was worth
        // 6.9 penalties per match (24% of all fouls; real football is ~1%).
        // Most challenges in the area are survived by the attacker or are
        // ordinary contact, so the box only makes a penalty CANDIDATE.
        boolean inBox = isInsidePenaltyArea(attacker, defender);
        boolean penaltyAwarded = inBox && SimulationRandom.nextDouble() < PENALTY_FROM_BOX_FOUL;
        boolean freeKickAwarded = !penaltyAwarded;
        String varDecision = "NONE";

        // Penalty call reviewed by VAR first — an overturn downgrades to free kick.
        if (penaltyAwarded && varService != null
                && !varService.checkPenalty(attacker.getPosition(), homeAttacking)) {
            penaltyAwarded = false;
            freeKickAwarded = true;
            varDecision = reviewDecision();
        }

        // Sanction ladder. Real matches: ~3-4 yellows, ~0.2 reds per side. The
        // straight-red roll was 2% of fouls (≈0.6/match on its own) and the
        // caution rate 35% (≈10 yellows/match), which is what produced 2.7 reds
        // and 7.5 yellows per match.
        boolean straightRedRolled = SimulationRandom.nextDouble() < STRAIGHT_RED_RATE;
        boolean yellowWorthy = !straightRedRolled && SimulationRandom.nextDouble() < YELLOW_RATE;

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

    /**
     * The box band must be mirrored about 4.5. HOME attacks a box in front of
     * the AWAY goal (row 8.0), so the band starts 1.5 cells out: row >= 6.5.
     * AWAY therefore needs 1.0 + 1.5 = row <= 2.5, not row <= 1.5 — the old
     * bound made HOME's penalty area 1.5 cells deep and AWAY's only 0.5, a
     * 3x one-directional difference in how often fouls became penalties.
     *
     * The column window is the real 40.3 m width, not "most of the pitch":
     * cols 2.5-5.5 is 3 cells of the 6 playable ones.
     */
    private static final double BOX_HALF_WIDTH = 1.5;
    private static final double BOX_MOUTH_CENTRE = 3.5;

    private boolean isInsidePenaltyArea(Player attacker, Player defender) {
        Position aPos = attacker.getPosition();
        Position dPos = defender.getPosition();
        if (aPos == null || dPos == null) return false;
        boolean homeAttacking = "HOME".equals(attacker.getTeam());
        double row = homeAttacking
                ? Math.max(aPos.getRow(), dPos.getRow())
                : Math.min(aPos.getRow(), dPos.getRow());
        double col = (aPos.getColumn() + dPos.getColumn()) / 2.0;
        return homeAttacking
                ? (row >= 6.5 && Math.abs(col - BOX_MOUTH_CENTRE) <= BOX_HALF_WIDTH)
                : (row <= 2.5 && Math.abs(col - BOX_MOUTH_CENTRE) <= BOX_HALF_WIDTH);
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
