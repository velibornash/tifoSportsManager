package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.model.*;

import java.util.Random;

/**
 * Duel resolution — side-effect-free calculation layer.
 * Identical formulas to demo/DuelResolver but using service model.
 */
public class DuelResolver {

    private final Random random;

    public DuelResolver(Random random) {
        this.random = random;
    }

    public DuelResult resolve(Player attacker, Player defender, DuelType type, Action action) {
        double attackerPower = computePower(attacker, type, true, action);
        double defenderPower = computePower(defender, type, false, action);
        int attRounded = (int) Math.round(attackerPower);
        int defRounded = (int) Math.round(defenderPower);

        if (attRounded >= defRounded) {
            return new DuelResult(attacker, DuelOutcome.ATTACKER_WINS, attRounded, defRounded);
        }
        return new DuelResult(defender, DuelOutcome.DEFENDER_WINS, attRounded, defRounded);
    }

    private double computePower(Player player, DuelType type, boolean attacker, Action action) {
        PlayerSkills s = player.getSkills();
        return switch (type) {
            case SHOT -> attacker ? shotAttacker(s, player, action) : shotDefender(s, player);
            case DRIBBLE -> attacker
                    ? s.technique() * 0.45 + s.playmaking() * 0.25 + s.pace() * 0.20 + s.stamina() * 0.10
                    : s.defender() * 0.45 + s.pace() * 0.25 + s.playmaking() * 0.20 + s.stamina() * 0.10;
            case RECEIVE_PASS -> attacker
                    ? receivePassAttacker(s, player, action)
                    : s.defender() * 0.45 + s.playmaking() * 0.25 + s.pace() * 0.20 + s.stamina() * 0.10;
            case CHASE_BALL -> s.pace() * 0.60 + s.stamina() * 0.20 + s.technique() * 0.20;
            case AERIAL -> attacker
                    ? player.heightSkill() * 0.40 + s.technique() * 0.30 + s.striker() * 0.20 + s.pace() * 0.10
                    : player.heightSkill() * 0.40 + s.defender() * 0.30 + s.technique() * 0.20 + s.pace() * 0.10;
        };
    }

    private double shotAttacker(PlayerSkills s, Player player, Action action) {
        double distanceBonus = 0;
        double angleBonus = 0;
        if (action != null && action.getActingPlayer() != null) {
            Position goal = ActionEngine.goalPositionFor(action.getActingPlayer().getTeam());
            double dist = SimUtils.distance(player.getPosition(), goal);
            distanceBonus = Math.max(0, 5.0 - dist * 0.5);
            double colDiff = Math.abs(player.getPosition().getColumn() - goal.getColumn());
            angleBonus = Math.max(0, 3.0 - colDiff * 1.0);
        }
        return s.striker() * 0.50 + s.technique() * 0.30 + distanceBonus + angleBonus;
    }

    private double shotDefender(PlayerSkills s, Player player) {
        double heightBonus = Math.max(0, (player.getHeightCm() - 175) / 6.25);
        if ("GK".equals(player.getRole())) {
            return s.keeper() * 0.60 + s.technique() * 0.25 + heightBonus;
        }
        // Outfield defender blocking a shot: use defender skill, not keeper
        return s.defender() * 0.50 + s.technique() * 0.25 + s.pace() * 0.15 + heightBonus;
    }

    private double receivePassAttacker(PlayerSkills s, Player player, Action action) {
        double basePower = s.technique() * 0.50 + s.playmaking() * 0.30 + s.pace() * 0.20;
        if (action != null && action.getSkill() > 0) {
            double passQualityBonus = (action.getSkill() - 10) * 0.15;
            basePower += passQualityBonus;
        }
        return basePower;
    }

    public record DuelResult(Player winner, DuelOutcome outcome,
                             int attackerPower, int defenderPower) {}
}
