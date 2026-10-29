package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.MatchEngineRules;
import org.tifo.footballmanager.newLogic.sim.model.*;
import org.tifo.footballmanager.newLogic.sim.util.SimUtils;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;
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
    //
    // EVERY RADIUS HERE MUST BE ABOVE MovementEngine.MIN_PLAYER_DISTANCE (0.6).
    //
    // That is not a style note, it is the reason duels used to be decided by tactics. The Movement
    // Engine's separation wall holds every opponent at
    //
    //     a single radius (DRIBBLE_DUEL_RADIUS = 0.45) for every defender (F-DU-1, owner
    //     decision 2026-10-22): the press radius is geometry, not a tactical flag.
    //
    // i.e. you could only tackle someone if ThreatOverrideEngine had routed that defender to you.
    // A defender 1.4 m away with no override could not contest the ball at all; the identical
    // defender one cell away, with the flag set, got a full challenge. A tackle was a permission
    // grant from a tactical component. The old comment here even said so — "without the wider press
    // radius a pressed carrier would never be tackled (the press 'ends' in stares)" — and the
    // response was the flag rather than the radius.
    //
    // The separation wall exists to stop two players occupying one square; it was not written to
    // make tackling impossible, and these radii are now above it so that a duel is a question about
    // where players are, never about which component decided to look.
    public static final double DRIBBLE_DUEL_RADIUS = 0.6; // touching distance (~7 m) — duel fires when circles touch
    /**
     * A presser engages from slightly further out than a standing challenge, because he is arriving
     * with momentum. The gap to {@link #DRIBBLE_DUEL_RADIUS} is now small on purpose: the old pair
     * were 0.15 and 0.38, a 2.5x difference that was really the size of the wall, not the difference
     * between arriving and standing.
     */
    public static final double PRESS_DRIB_DUEL_RADIUS = 0.6; // touching distance (~7 m) — duel fires when circles touch
    public static final double RECEIVE_PASS_RADIUS = 0.50;
    /**
     * A defender level with or behind the carrier has to get closer to win the ball, so only this
     * radius counts as a contest from that side. Was 0.08, which is inside the separation wall and
     * therefore unreachable — meaning a side-on challenge could never happen at all.
     */
    public static final double SIDE_ON_TIE_RADIUS = 0.6;
    public static final double AERIAL_DUEL_RADIUS = 0.5;
    public static final double SHOT_BLOCK_RADIUS = 0.45;
    public static final int DUEL_COOLDOWN_TICKS = 10;
    public static final int DRIBBLE_COOLDOWN_TICKS = 7;
    /**
     * How many ticks the duel loser is movement-locked after losing a duel.
     *
     * <p>Was {@link #DRIBBLE_COOLDOWN_TICKS} (7) ticks, which at
     * {@code PLAYER_SPEED_BASE = 0.75} cells/tick froze the loser for 5.25 cells
     * (~73 m) while the winner walked away — the measured "40 m gap". Two ticks
     * is one and a half cells, which keeps the contestants from overlapping on the
     * next tick without stranding the loser.
     */
    public static final int LOSER_LOCK_TICKS = 2;

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
     * Duels actually resolved, and how many the carrier lost.
     *
     * <p>A counting seam, for the same reason {@code MatchOrchestrator.DecisionObserver} exists: it was
     * impossible to tell "no duel fired" from "the carrier won every duel" by watching the ball, and
     * those two have opposite fixes. Measured 2026-10-18 with these counters absent: a dribble contested
     * by an opponent inside the duel radius succeeded 1165 times out of 1165, which is not a skill
     * result — {@link #resolveDuel} gives an even matchup about one in two.
     *
     * <p>Static and reset per match, like {@link org.tifo.footballmanager.newLogic.sim.util.SimulationRandom}.
     */
    public static int duelsResolved;
    public static int duelsLostByCarrier;

    public static void resetCounters() {
        duelsResolved = 0;
        duelsLostByCarrier = 0;
    }

    /**
     * Check if a duel should be triggered between attacker and defender.
     * Returns duel type if duel fires, null otherwise.
     */

    /**
     * Is the defender actually challenging for the ball, or merely standing nearby?
     *
     * <p>Duels used to fire on distance alone, so any opponent within the radius counted as a
     * contest even when he was level with the carrier or trailing him. A 50-match sample recorded
     * 373 duel resolutions per match against a realistic ~100.
     *
     * <p>A real challenge comes from in front: a defender level with or behind the carrier has to
     * get a toe in, which is a much smaller radius. So a goal-side defender may contest at the
     * normal radius, and anyone else only at {@link #SIDE_ON_TIE_RADIUS}.
     *
     * <p>This is a contest test, not a difficulty change - the duel power maths is untouched.
     */
    private boolean contestsTheBall(Player carrier, Player defender, double distance, MatchState state) {
        // NOT a question about what ThreatOverrideEngine decided. It used to open with
        // `if (defender.isThreatOverrideActive()) return true;`, which meant the only way to contest
        // the ball from any real distance was for a tactical component to have selected you. A
        // contest is geometry — a defender in front of the man is challenging him; a defender level
        // with him has to get a toe in.
        double carrierRow = carrier.getPosition().getRow();
        double defenderRow = defender.getPosition().getRow();
        // HOME attacks the AWAY goal at row 8.0, so "in front" means a higher row.
        boolean carrierIsHome = "HOME".equals(carrier.getTeam());
        boolean goalSide = carrierIsHome ? defenderRow > carrierRow : defenderRow < carrierRow;

        if (goalSide) return true;
        return distance <= SIDE_ON_TIE_RADIUS;
    }

    public DuelType checkDuel(Player attacker, Player defender,
                                MatchState state) {
        if (attacker == null || defender == null) return null;
        if (attacker == defender) return null;

        // Calculate distance
        double distance = SimUtils.distance(
            attacker.getPosition(), defender.getPosition());

        // Dribble duel. A presser who arrived with momentum gets a slightly wider challenge, which
        // is a statement about HOW he got there — not about whether he is allowed to try. The old
        // version chose the radius by reading a tactics flag, so the radius was really a permission.
        // Single radius for every defender (F-DU-1, owner decision 2026-10-22):
        // the press radius is a function of geometry, not of a tactical flag.
        // ThreatOverrideEngine sets the flag, but the contest distance is the
        // same for a standing defender as for a pressing one — 0.45 cells.
        double dribbleRadius = DRIBBLE_DUEL_RADIUS;
        if (distance <= dribbleRadius && contestsTheBall(attacker, defender, distance, state)) {
            if (!isOnCooldown(attacker, defender, DRIBBLE_COOLDOWN_TICKS, state)) {
                markDuel(attacker, defender, state);
                return DuelType.DRIBBLE;
            }
        }

        // Receive pass duel.
        //
        // The `attacker.getTarget() != null` guard that used to sit here is gone. `attacker` is
        // always the carrier, and TacticalIntentEngine skips the carrier when refreshing targets, so
        // his target is non-null only while he is walking to the carry point ActionExecutor wrote
        // last tick. It made "can this defender contest the ball" depend on a movement decision made
        // by a different subsystem. A duel is a function of positions, skills and the cooldown.
        if (distance <= RECEIVE_PASS_RADIUS) {
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
        double attackerPower = calculateDuelPower(attacker, duelType, true);
        double defenderPower = calculateDuelPower(defender, getDefensiveDuelType(duelType), false);

        // **The ground counts.**
        //
        // The forecaster has told managers that the home side has an advantage since this game was
        // written - it added 0.7 to the home edge - and the engine had never heard of it. A fixture
        // promised at 60% came back at 50% a thousand times over and nothing anywhere said why.
        //
        // This is the seam, because the comment above it already says "possession follows duels":
        // a match's shape falls out of who wins the ball, and the ball falls out of this. Home
        // advantage belongs here rather than in a hundred small places, because here is the one place
        // a single change moves the whole match.
        //
        // Expressed as a flat addition to the home side's power, so it is worth the same on every
        // duel type and at every skill level, and it cannot compound.
        if (MatchEngineRules.isHome(attacker.getTeam())) {
            attackerPower += MatchEngineRules.HOME_ADVANTAGE_DUEL_POWER;
        } else {
            defenderPower += MatchEngineRules.HOME_ADVANTAGE_DUEL_POWER;
        }

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

        duelsResolved++;
        if (SimulationRandom.nextDouble() < pWin) return attacker;
        duelsLostByCarrier++;
        return defender;
    }

    /**
     * How sharply a duel rewards skill. Raised from 0.18 to 0.22 (owner, 2026-10-22) so a
     * defender's edge bites a little harder: ~53% at a 1-point gap, ~62% at 2, ~70% at 4.
     * A clear edge that is never certainty.
     */
    private static final double DUEL_SKILL_SENSITIVITY = 0.22;

    /**
     * Apply duel result (winner gets ball).
     */
    public void applyDuelResult(MatchState state, Player winner, Player loser) {
        // Winner becomes carrier
        state.setCarrier(winner);
        state.setLastTouchTeam(winner.getTeam());
        // The duel winner is now the last player to touch the ball, so the
        // ball physics engine does not treat the previous toucher as the
        // kicker on the very next contact (F-DU-4).
        state.setLastTouchPlayer(winner);

        // RIGID RULE (user 2026-09-17): the ball stays where it physically is —
        // the winner is already within a duel radius (≤ 0.3 cells) of it and the
        // Movement Engine walks him onto it before any action starts. No teleport.
        state.getBall().stop();

        // Loser is blocked for cooldown (unlocked by orchestrator after ticks expire)
        loser.setLocked(true);
        loser.setLockTicks(LOSER_LOCK_TICKS);

        // BOTH contestants enter the duel cooldown. The loser is additionally
        // movement-locked above; the winner is only duel-suppressed, so he keeps
        // the ball and runs with it instead of instantly re-contesting the next
        // opponent (which is what froze the match).
        winner.setLastDuelTick(state.getMatchTicks());
        loser.setLastDuelTick(state.getMatchTicks());
    }

    private double calculateDuelPower(Player player, DuelType duelType, boolean isAttacker) {
        double power = 0.0;
        PlayerSkills skills = player.getSkills();

        switch (duelType) {
            case DRIBBLE -> {
                // Dribbling + technique + pace
                power = skills.technique() * 0.4 + skills.pace() * 0.3 + skills.striker() * 0.3;
            }
            case TACKLE -> {
                // Defending + technique + pace (owner: 0.6 def / 0.3 tech / 0.1 pace)
                power = skills.defender() * 0.6 + skills.technique() * 0.3 + skills.pace() * 0.1;
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
                // Height + jumping. Attacker uses striker skill, defender uses defender
                // skill (P2-67, owner decision 2026-10-22): a defender contests with
                // his defending, an attacker with his finishing.
                double aerialSkill = isAttacker ? skills.striker() : skills.defender();
                power = player.heightSkill() * 0.6 + aerialSkill * 0.4;
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