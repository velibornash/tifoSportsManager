package org.example.footballtextmanager.model;

public enum CSSkillLevel {
    TRAGIC, HOPELESS, UNSATISFACTORY, POOR, WEAK, AVERAGE, ADEQUATE,
    GOOD, SOLID, VERY_GOOD, EXCELLENT, FORMIDABLE, OUTSTANDING,
    INCREDIBLE, BRILLIANT, MAGICAL, UNEARTHLY, DIVINE,
    CELESTIAL, LEGENDARY, MYTHICAL;

    public static String getLabel(int value) {
        int clamped = Math.max(0, Math.min(value, values().length - 1));
        return values()[clamped].name().replace('_', ' ').toLowerCase();
    }
}
