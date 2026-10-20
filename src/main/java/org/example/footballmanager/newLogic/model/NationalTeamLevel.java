package org.example.footballmanager.newLogic.model;

/** Senior national side, or the Under-21 side. One country has one of each. */
public enum NationalTeamLevel {
    SENIOR,
    U21;

    public static NationalTeamLevel from(String raw) {
        if (raw == null) {
            return SENIOR;
        }
        String value = raw.trim().toLowerCase();
        return "u21".equals(value) || "u-21".equals(value) || "under21".equals(value) ? U21 : SENIOR;
    }
}
