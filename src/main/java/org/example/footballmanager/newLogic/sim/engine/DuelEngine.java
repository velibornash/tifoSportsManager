package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.*;
import org.example.footballmanager.newLogic.sim.util.SimUtils;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import java.util.List;

/**
 * Duel engine - ONLY resolves player duels (tackles, blocks, presses).
 * 
 * Core principle: Duels are resolved independently from decision selection.
 * A player may DECIDE to tackle, but whether it SUCCEEDS is resolved here.
 * 
 * CRITICAL: Dribble duels are tight - defender must be physically on top
 * of the carrier. This prevents "magic" tackles from far away.
 */
public class DuelEngine {

    // Duel radii (1 cell = 14m x 10m)
    public static final double DRIBBLE_DUEL_RADIUS = 0.15; // ~2m - very tight
    public static final double PRESS_DRIB_DUEL_RADIUS = 0.50; // ~7m - TYPE A presser engages here
    public static final double RECEIVE_PASS_RADIUS = 0.2;
    public static final double AERIAL_DUEL_RADIUS = 0.5;
    public static final double SHOT_BLOCK_RADIUS = 0.3;
    public static final int DUEL_COOLDOWN_TICKS = 10;
    public static final int DRIBBLE_COOLDOWN_TICKS = 7;

    /**
     * Last tick each unordered pair contested, so the same challenge is not
     * re-judged every tick while the two players stay in contact.
     *
     * This used to be a stub that always returned false ("would track last duel
     * tick in state"). With a presser parked within PRESS_DRIB_DUEL_RADIUS the
     * duel therefore fired EVERY tick: 713 duels per match, and — because a
     * defender win also runs the discipline check — roughly 350 independent
     * chances per match to be whistled, which is where 28 fouls, 6 penalties and
     * 2.7 reds per match came from. A challenge is judged once, not once per
     * frame of contact.
     */
    private final java.util.Map<String, Integer> lastDuelTick = new java.util.HashMap<>();

    /**
     * Check if a duel should be triggered between attacker and defender.
     * Returns duel type if duel fires, null otherwise.
     */
    public DuelType checkDuel(Player attacker, Player defender,
                                MatchState state) {
        if (attacker == null || defender == null) return null;
        if (attacker == defender) return null;

        // Calculate distance
        double distance = SimUtils.distance(
            attacker.getPosition(), defender.getPosition());

        // Dribble duel - tightest radius, EXCEPT for a TYPE A presser who chased the
        // carrier all the way to his press point. The Movement Engine wall
        // (MIN_PLAYER_DISTANCE = 0.35) parks the presser ~0.35-0.4 cells apart,
        // which is outside the 0.15 tight radius — without the wider press radius
        // a pressed carrier would never be tackled (the press "ends" in stares).
        double dribbleRadius = defender.isThreatOverrideActive()
                ? PRESS_DRIB_DUEL_RADIUS
                : DRIBBLE_DUEL_RADIUS;
        if (distance <= dribbleRadius) {
            if (!isOnCooldown(attacker, defender, DRIBBLE_COOLDOWN_TICKS, state)) {
                markDuel(attacker, defender, state);
                return DuelType.DRIBBLE;
            }
        }

        // Receive pass duel
        if (distance <= RECEIVE_PASS_RADIUS && attacker.getTarget() != null) {
            if (!isOnCooldown(attacker, defender, DUEL_COOLDOWN_TICKS, state)) {
                markDuel(attacker, defender, state);
                return DuelType.RECEIVE_PASS;
            }
            return null;
        }

        // Shot block
        if (distance <= SHOT_BLOCK_RADIUS && state.getCarrier() != null) {
            // Check if defender is between ball and goal
            if (!isOnCooldown(attacker, defender, DUEL_COOLDOWN_TICKS, state)) {
                markDuel(attacker, defender, state);
                return DuelType.SHOT_BLOCK;
            }
            return null;
        }

        return null; // no duel
    }

    /**
     * Resolve a duel based on skills.
     * Returns the winner (null if draw - random).
     */
    public Player resolveDuel(Player attacker, Player defender,
                                DuelType duelType, MatchState state) {
        double attackerPower = calculateDuelPower(attacker, duelType);
        double defenderPower = calculateDuelPower(defender, getDefensiveDuelType(duelType));

        // A duel is a MOMENT, not a comparison of two numbers. Resolving it as
        // `attackerPower + U(-1,1) > defenderPower` made it deterministic for any
        // skill difference at all: the noise band was +/-1 while a flat 18-vs-8
        // squad gap is 10 power points — and even a ONE point gap won 100% of the
        // time, because r > -1 always. So in any match with role-varied squads,
        // where mirrored matchups routinely differ by 1-3 points, the better player
        // simply won every duel.
        //
        // Measured consequence (TeamStrengthProbe, 30 matches per pairing): a 4-point
        // gap produced 30 wins from 30 with the weak side on 0.1 shots and ~0 goals,
        // because possession follows duels. A 4-point gap is not a 30-0-0.
        //
        // The contest is now a bounded probability of the skill difference, so a
        // favourite is favoured rather than guaranteed, and no gap is ever absolute.
        // One uniform draw from the seeded source keeps batches reproducible.
        double gap = attackerPower - defenderPower;
        double pWin = 1.0 / (1.0 + Math.exp(-DUEL_SKILL_SENSITIVITY * gap));

        return SimulationRandom.nextDouble() < pWin ? attacker : defender;
    }

    /**
     * How sharply a duel rewards skill. Chosen so the measured duel win rate of
     * the stronger side is ~52% at a 1-point gap, ~60% at 2, ~67% at 4 and ~86% at
     * 10 — a clear edge that is never certainty. At 0.5 a single point of skill
     * decided three duels in four.
     */
    private static final double DUEL_SKILL_SENSITIVITY = 0.18;

    /**
     * Apply duel result (winner gets ball).
     */
    public void applyDuelResult(MatchState state, Player winner, Player loser) {
        // Winner becomes carrier
        state.setCarrier(winner);
        state.setLastTouchTeam(winner.getTeam());

        // RIGID RULE (user 2026-09-17): the ball stays where it physically is —
        // the winner is already within a duel radius (≤ 0.3 cells) of it and the
        // Movement Engine walks him onto it before any action starts. No teleport.
        state.getBall().stop();

        // Loser is blocked for cooldown (unlocked by orchestrator after ticks expire)
        loser.setLocked(true);
        loser.setLockTicks(DRIBBLE_COOLDOWN_TICKS);

        // BOTH contestants enter the duel cooldown. The loser is additionally
        // movement-locked above; the winner is only duel-suppressed, so he keeps
        // the ball and runs with it instead of instantly re-contesting the next
        // opponent (which is what froze the match).
        winner.setLastDuelTick(state.getMatchTicks());
        loser.setLastDuelTick(state.getMatchTicks());
    }

    private double calculateDuelPower(Player player, DuelType duelType) {
        double power = 0.0;
        PlayerSkills skills = player.getSkills();

        switch (duelType) {
            case DRIBBLE -> {
                // Dribbling + technique + pace
                power = skills.technique() * 0.4 + skills.pace() * 0.3 + skills.striker() * 0.3;
            }
            case TACKLE -> {
                // Defending + technique + pace
                power = skills.defender() * 0.5 + skills.technique() * 0.3 + skills.pace() * 0.2;
            }
            case RECEIVE_PASS -> {
                // Technique + first touch
                power = skills.technique() * 0.6 + skills.pace() * 0.4;
            }
            case SHOT_BLOCK -> {
                // Defending + height
                power = skills.defender() * 0.7 + player.heightSkill() * 0.3;
            }
            case AERIAL -> {
                // Height + jumping
                power = player.heightSkill() * 0.6 + skills.striker() * 0.4;
            }
        }

        // Fatigue reduces power
        power *= (1.0 - player.getFatigue() * MovementEngine.MAX_FATIGUE_SPEED_LOSS);

        return power;
    }

    private DuelType getDefensiveDuelType(DuelType duelType) {
        return switch (duelType) {
            case DRIBBLE -> DuelType.TACKLE;
            case TACKLE -> DuelType.TACKLE;
            case RECEIVE_PASS -> DuelType.RECEIVE_PASS;
            case SHOT_BLOCK -> DuelType.TACKLE;
            case AERIAL -> DuelType.AERIAL;
            default -> DuelType.TACKLE;
        };
    }

    /**
     * True when EITHER player contested a duel less than {@code cooldown} ticks
     * ago.
     *
     * It used to be PAIRWISE ("did this exact pair fight recently"), which let a
     * carrier chain duels forever: he beat the defender on his left, that
     * defender was locked for 7 ticks, so he immediately beat the next defender
     * over, and when the first one's lock expired he beat him again. The winner
     * was never on cooldown, so he kept the ball and the match froze — a live
     * match showed the same "DUEL DRIBBLE won by ..." line every 2-3 ticks with
     * the ball never moving, for 44 minutes of match time.
     *
     * A PER-PLAYER cooldown makes duels mutually exclusive in time: after winning
     * one, the carrier is out of duels for the whole window, so he has to carry
     * the ball and complete an action before anyone can contest it again. The
     * cooldown suppresses only new contests — it does not lock the player, so the
     * winner can still move, pass and shoot.
     */
    private boolean isOnCooldown(Player a, Player b, int cooldown, MatchState state) {
        int now = state.getMatchTicks();
        if (now - a.getLastDuelTick() < cooldown) return true;
        if (now - b.getLastDuelTick() < cooldown) return true;
        Integer last = lastDuelTick.get(pairKey(a, b));
        return last != null && now - last < cooldown;
    }

    private void markDuel(Player a, Player b, MatchState state) {
        lastDuelTick.put(pairKey(a, b), state.getMatchTicks());
    }

    /** Order-independent key, so (a,b) and (b,a) are the same contest. */
    private static String pairKey(Player a, Player b) {
        String ka = a.getId(), kb = b.getId();
        return ka.compareTo(kb) <= 0 ? ka + "|" + kb : kb + "|" + ka;
    }

    public enum DuelType {
        DRIBBLE,
        TACKLE,
        RECEIVE_PASS,
        SHOT_BLOCK,
        AERIAL,
        CLEAR,
        HEADER,
        BLOCK
    }
}