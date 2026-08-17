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
     * PlayerSkills trenutno nema receiving/tackling/goalkeeping polja.
     * Existing extension points se koriste kao neutralni placeholderi:
     * positioning predstavlja prijem/tackling/GK dok se model kasnije ne
     * prosiri namenskim atributima.
     */
    private int skillValue(Player player, DuelType type, boolean attacker) {
        PlayerSkills skills = player.getSkills();
        double value = switch (type) {
            case CHASE_BALL -> skills.speed();
            case DRIBBLE -> attacker ? skills.dribbling() : skills.positioning();
            case RECEIVE_PASS -> attacker ? skills.positioning() : skills.positioning();
            case SHOT -> attacker ? skills.shooting() : skills.positioning();
        };
        return Math.max(1, (int) Math.round(value * 10.0));
    }
}
