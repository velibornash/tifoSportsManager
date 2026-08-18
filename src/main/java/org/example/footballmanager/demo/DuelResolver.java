package org.example.footballmanager.demo;

import java.util.Random;

/**
 * Cist resolution sloj za duel.
 *
 * FORMULA: EFFECTIVE POWER = weighted_skills + situational_modifiers + random(0..3)
 *
 * Random je mali (0..3) da bi skill razlike bile odlucujuce.
 * Svaki tip duela ima svoju formulu sa jasno definisanim tezinama.
 *
 * <pre>
 * SHOT:       attacker = STRIKER*0.50 + TECHNIQUE*0.30 + distance_bonus + angle_bonus
 *             defender = KEEPER*0.60  + TECHNIQUE*0.25 + height_bonus
 * DRIBBLE:    attacker = TECHNIQUE*0.45 + PLAYMAKING*0.25 + PACE*0.20 + STAMINA*0.10
 *             defender = DEFENDER*0.45 + PACE*0.25 + PLAYMAKING*0.20 + STAMINA*0.10
 * RECEIVE:    attacker = TECHNIQUE*0.50 + PLAYMAKING*0.30 + PACE*0.20
 *             defender = DEFENDER*0.45 + PLAYMAKING*0.25 + PACE*0.20 + STAMINA*0.10
 * CHASE:      player   = PACE*0.60 + STAMINA*0.20 + TECHNIQUE*0.20
 * AERIAL:     attacker = HEIGHT*0.40 + TECHNIQUE*0.30 + STRIKER*0.20 + PACE*0.10
 *             defender = HEIGHT*0.40 + DEFENDER*0.30 + TECHNIQUE*0.20 + PACE*0.10
 * TACKLE:     defender = DEFENDER*0.40 + PACE*0.25 + PLAYMAKING*0.15 + STAMINA*0.10 + TECHNIQUE*0.10
 * </pre>
 */
public final class DuelResolver {
    private final Random random;

    public DuelResolver(Random random) {
        this.random = random;
    }

    public DuelResult resolve(Duel duel) {
        double attackerPower = computePower(duel.getAttacker(), duel, true);
        double defenderPower = computePower(duel.getDefender(), duel, false);
        int attRounded = (int) Math.round(attackerPower);
        int defRounded = (int) Math.round(defenderPower);

        if (attRounded >= defRounded) {
            return new DuelResult(duel.getAttacker(), DuelOutcome.ATTACKER_WINS,
                    Ball.BallState.IN_POSSESSION, duel.getAttacker(),
                    attRounded, defRounded);
        }
        return new DuelResult(duel.getDefender(), DuelOutcome.DEFENDER_WINS,
                Ball.BallState.IN_POSSESSION, duel.getDefender(),
                attRounded, defRounded);
    }

    private double computePower(Player player, Duel duel, boolean attacker) {
        PlayerSkills s = player.getSkills();
        return switch (duel.getType()) {
            case SHOT -> attacker ? shotAttacker(s, player, duel) : shotDefender(s, player);
            case DRIBBLE -> attacker
                    ? s.technique() * 0.45 + s.playmaking() * 0.25 + s.pace() * 0.20 + s.stamina() * 0.10
                    : s.defender() * 0.45 + s.pace() * 0.25 + s.playmaking() * 0.20 + s.stamina() * 0.10;
            case RECEIVE_PASS -> attacker
                    ? s.technique() * 0.50 + s.playmaking() * 0.30 + s.pace() * 0.20
                    : s.defender() * 0.45 + s.playmaking() * 0.25 + s.pace() * 0.20 + s.stamina() * 0.10;
            case CHASE_BALL -> s.pace() * 0.60 + s.stamina() * 0.20 + s.technique() * 0.20;
            case AERIAL -> attacker
                    ? player.heightSkill() * 0.40 + s.technique() * 0.30 + s.striker() * 0.20 + s.pace() * 0.10
                    : player.heightSkill() * 0.40 + s.defender() * 0.30 + s.technique() * 0.20 + s.pace() * 0.10;
        };
    }

    /** STRIKER*0.50 + TECHNIQUE*0.30 + distance_bonus(0-5) + angle_bonus(0-3) */
    private double shotAttacker(PlayerSkills s, Player player, Duel duel) {
        double distanceBonus = 0;
        double angleBonus = 0;
        if (duel.getContestPosition() != null) {
            Position goal = ActionEngine.goalPositionFor(player.getTeam());
            double dist = MovementEngine.distance(player.getPosition(), goal);
            // Closer to goal = higher bonus (max 5 at point-blank)
            distanceBonus = Math.max(0, 5.0 - dist * 0.5);
            // More central = higher angle bonus (max 3)
            double colDiff = Math.abs(player.getPosition().getColumn() - goal.getColumn());
            angleBonus = Math.max(0, 3.0 - colDiff * 1.0);
        }
        return s.striker() * 0.50 + s.technique() * 0.30 + distanceBonus + angleBonus;
    }

    /** KEEPER*0.60 + TECHNIQUE*0.25 + height_bonus(0-4) */
    private double shotDefender(PlayerSkills s, Player player) {
        double heightBonus = Math.max(0, (player.getHeightCm() - 175) / 6.25);
        return s.keeper() * 0.60 + s.technique() * 0.25 + heightBonus;
    }

    /** Tekstualni trag formule za log. */
    public String skillDescription(Duel duel, boolean attacker) {
        Player player = attacker ? duel.getAttacker() : duel.getDefender();
        double power = computePower(player, duel, attacker);
        return "power=" + String.format(java.util.Locale.ROOT, "%.1f", power) + " + random(0..3)";
    }
}
