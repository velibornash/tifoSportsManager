package org.example.footballmanager.demo.service.model;

import java.util.Random;

/**
 * PlayerSkills — 8 core abilities, each 1-20.
 * Identical to demo/PlayerSkills but in the service package.
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

    public static PlayerSkills randomForRole(String role, Random random) {
        return switch (role) {
            case "GK" -> new PlayerSkills(
                    clampMax(random, 2),
                    clampMax(random, 2),
                    clampMin(random, 13),
                    clampMax(random, 2),
                    clampMax(random, 2),
                    clampMax(random, 2),
                    clampMax(random, 2),
                    clampMax(random, 2));
            case "DL", "DR", "DCL", "DCR" -> new PlayerSkills(
                    clampMin(random, 12),
                    randomRange(random, 10, 20),
                    clampMax(random, 2),
                    randomRange(random, 8, 18),
                    randomRange(random, 6, 16),
                    randomRange(random, 6, 20),
                    clampMax(random, 8),
                    randomRange(random, 12, 20));
            case "STL", "STR" -> new PlayerSkills(
                    clampMin(random, 14),
                    randomRange(random, 10, 20),
                    clampMax(random, 2),
                    clampMin(random, 11),
                    randomRange(random, 6, 14),
                    randomRange(random, 5, 16),
                    clampMin(random, 13),
                    randomRange(random, 4, 12));
            default -> new PlayerSkills(
                    randomRange(random, 10, 20),
                    randomRange(random, 10, 20),
                    clampMax(random, 2),
                    randomRange(random, 8, 10),
                    randomRange(random, 8, 20),
                    randomRange(random, 11, 20),
                    randomRange(random, 5, 14),
                    randomRange(random, 8, 15));
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
}
