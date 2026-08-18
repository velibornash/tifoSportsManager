package org.example.footballmanager.demo;

import java.util.Random;

/**
 * PlayerSkills — 8 core abilities, each 1–20.
 *
 * <pre>
 * pace       → movement speed (20 = 1 cell/round, lower = proportionally slower)
 * stamina    → duel physical component + fatigue resistance
 * keeper     → shot saving (min 10 for GK)
 * technique  → execution of all actions (receive, dribble, control)
 * playmaking → decision quality / breadth of available choices
 * passing    → pass execution accuracy
 * striker    → shot quality + power
 * defender   → defensive duel / tackle / deflection
 * </pre>
 *
 * Performance model (future):
 * <pre>
 * base skill  →  form modifier  →  fatigue modifier  →  effective skill
 * </pre>
 * For now, form and fatigue are NOT simulated — only the model slot exists.
 */
public record PlayerSkills(
        double pace,
        double stamina,
        double keeper,
        double technique,
        double playmaking,
        double passing,
        double striker,
        double defender) {

    public static PlayerSkills neutral() {
        return new PlayerSkills(10, 10, 10, 10, 10, 10, 10, 10);
    }

    /**
     * Generate random skills (1–20) for a player, then apply role adjustments.
     *
     * <pre>
     * GK:  keeper ≥ 10,  striker −2
     * DEF: defender +3,  striker −2
     * ST:  striker +3,   defender −2
     * MID: playmaking +1 (general balance)
     * </pre>
     *
     * Adjustments are clamped to 1–20.
     */
    public static PlayerSkills randomForRole(String role, Random random) {
        double pace       = clamp(random.nextInt(20) + 1);
        double stamina    = clamp(random.nextInt(20) + 1);
        double keeper     = clamp(random.nextInt(20) + 1);
        double technique  = clamp(random.nextInt(20) + 1);
        double playmaking = clamp(random.nextInt(20) + 1);
        double passing    = clamp(random.nextInt(20) + 1);
        double striker    = clamp(random.nextInt(20) + 1);
        double defender   = clamp(random.nextInt(20) + 1);

        return switch (role) {
            case "GK" -> new PlayerSkills(
                    pace, stamina,
                    Math.max(keeper, 10),
                    technique, playmaking, passing,
                    clamp(striker - 2), defender);
            case "DL", "DR", "DCL", "DCR" -> new PlayerSkills(
                    pace, stamina, keeper, technique, playmaking, passing,
                    clamp(striker - 2), clamp(defender + 3));
            case "STL", "STR" -> new PlayerSkills(
                    pace, stamina, keeper, technique, playmaking, passing,
                    clamp(striker + 3), clamp(defender - 2));
            default -> new PlayerSkills(
                    pace, stamina, keeper, technique,
                    clamp(playmaking + 1), passing, striker, defender);
        };
    }

    private static double clamp(double value) {
        return Math.max(1, Math.min(20, value));
    }
}
