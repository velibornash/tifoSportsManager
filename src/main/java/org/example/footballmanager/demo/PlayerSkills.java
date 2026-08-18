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
     * Generate random skills (1–20) with realistic ranges per role.
     *
     * <pre>
     * GK:  keeper 13–20,  all field skills max 2
     * DEF: defender 12–20, passing 6–20, striker max 8, pace 12+
     * MID: defender 8–15, playmaking 8–20, passing 11–20, technique 8–10
     * ST:  pace 14+, technique 11+, striker 13+
     * </pre>
     */
    public static PlayerSkills randomForRole(String role, Random random) {
        return switch (role) {
            case "GK" -> new PlayerSkills(
                    clampMax(random, 2),       // pace
                    clampMax(random, 2),       // stamina
                    clampMin(random, 13),      // keeper: 13–20
                    clampMax(random, 2),       // technique
                    clampMax(random, 2),       // playmaking
                    clampMax(random, 2),       // passing
                    clampMax(random, 2),       // striker
                    clampMax(random, 2));      // defender
            case "DL", "DR", "DCL", "DCR" -> new PlayerSkills(
                    clampMin(random, 12),      // pace: 12–20
                    randomRange(random, 10, 20), // stamina
                    clampMax(random, 2),       // keeper
                    randomRange(random, 8, 18), // technique
                    randomRange(random, 6, 16), // playmaking
                    randomRange(random, 6, 20), // passing: 6–20
                    clampMax(random, 8),       // striker: max 8
                    randomRange(random, 12, 20)); // defender: 12–20
            case "STL", "STR" -> new PlayerSkills(
                    clampMin(random, 14),      // pace: 14–20
                    randomRange(random, 10, 20), // stamina
                    clampMax(random, 2),       // keeper
                    clampMin(random, 11),      // technique: 11–20
                    randomRange(random, 6, 14), // playmaking
                    randomRange(random, 5, 16), // passing
                    clampMin(random, 13),      // striker: 13–20
                    randomRange(random, 4, 12)); // defender
            default -> new PlayerSkills(       // MID
                    randomRange(random, 10, 20), // pace
                    randomRange(random, 10, 20), // stamina
                    clampMax(random, 2),       // keeper
                    randomRange(random, 8, 10), // technique: 8–10
                    randomRange(random, 8, 20), // playmaking: 8–20
                    randomRange(random, 11, 20), // passing: 11–20
                    randomRange(random, 5, 14), // striker
                    randomRange(random, 8, 15)); // defender: 8–15
        };
    }

    private static int clampMin(Random random, int min) {
        return random.nextInt(20 - min + 1) + min;
    }

    private static int clampMax(Random random, int max) {
        return random.nextInt(max) + 1;
    }

    private static int randomRange(Random random, int min, int max) {
        return random.nextInt(max - min + 1) + min;
    }

    private static double clamp(double value) {
        return Math.max(1, Math.min(20, value));
    }
}
