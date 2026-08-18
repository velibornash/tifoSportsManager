package org.example.footballmanager.demo;

import java.util.Random;

/**
 * Cist resolution sloj za duel.
 *
 * Ne menja igrace, loptu ili Action. Pozivalac odlucuje kada i kako rezultat
 * prelazi u posledicu, cime ostaju odvojeni DECISION, EXECUTION i RESULT.
 */
public final class DuelResolver {
    private final Random random;

    public DuelResolver(Random random) {
        this.random = random;
    }

    public DuelResult resolve(Duel duel) {
        int attackerBase = skillValue(duel.getAttacker(), duel.getType(), true);
        int defenderBase = skillValue(duel.getDefender(), duel.getType(), false);
        int attackerPower = attackerBase + random.nextInt(6);
        int defenderPower = defenderBase + random.nextInt(6);

        if (attackerPower >= defenderPower) {
            return new DuelResult(duel.getAttacker(), DuelOutcome.ATTACKER_WINS,
                    Ball.BallState.IN_POSSESSION, duel.getAttacker(),
                    attackerPower, defenderPower);
        }
        return new DuelResult(duel.getDefender(), DuelOutcome.DEFENDER_WINS,
                Ball.BallState.IN_POSSESSION, duel.getDefender(),
                attackerPower, defenderPower);
    }

    /**
     * Maps duel type + role to the relevant skill (1–20).
     */
    private int skillValue(Player player, DuelType type, boolean attacker) {
        PlayerSkills skills = player.getSkills();
        double value = switch (type) {
            case CHASE_BALL -> skills.pace();
            case DRIBBLE -> attacker ? skills.technique() : skills.defender();
            case RECEIVE_PASS -> attacker ? skills.technique() : skills.technique();
            case SHOT -> attacker ? skills.striker() : skills.keeper();
        };
        return Math.max(1, (int) Math.round(value));
    }

    /** Tekstualni trag identičan formuli korišćenoj u resolve(). */
    public String skillDescription(Duel duel, boolean attacker) {
        Player player = attacker ? duel.getAttacker() : duel.getDefender();
        int base = skillValue(player, duel.getType(), attacker);
        return "skill=" + base + "/20 + random(0..5)";
    }
}
