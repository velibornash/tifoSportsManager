package org.example.footballmanager.model;

public enum SkillLevel {
    TRAGIC, HOPELESS, UNSATISFACTORY, POOR, WEAK, AVERAGE, ADEQUATE,
    GOOD, SOLID, VERY_GOOD, EXCELLENT, FORMIDABLE, OUTSTANDING,
    INCREDIBLE, BRILLIANT, MAGICAL, UNEARTHLY, DIVINE;

    public static String getLabel(int value) {
        return values()[value].name().replace('_', ' ').toLowerCase();
    }
}