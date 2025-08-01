package org.example.footballmanager.model.tactics;

import lombok.Getter;

@Getter

public enum Formation {
    F_433(1.2, 0.8, 1.0),
    F_3223(1.5,0.7,1.0),
    F_442(1.0, 1.0, 1.0),
    F_541(0.8, 1.2, 0.9),
    F_352(1.1, 1.0, 1.2),
    F_343(1.3, 0.7, 1.1);

    private final double offenseModifier;
    private final double defenseModifier;
    private final double possessionModifier;

    Formation(double offenseModifier, double defenseModifier, double possessionModifier) {
        this.offenseModifier = offenseModifier;
        this.defenseModifier = defenseModifier;
        this.possessionModifier = possessionModifier;
    }

    public static Formation fromString(String code) {
        return switch (code) {
            case "4-3-3" -> F_433;
            case "3-2-2-3" -> F_3223;
            case "4-4-2" -> F_442;
            case "5-4-1" -> F_541;
            case "3-5-2" -> F_352;
            case "3-4-3" -> F_343;

            default -> F_442;
        };
    }
}