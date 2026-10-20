package org.example.footballmanager.demo.service.model;

public enum TeamSide {
    HOME, AWAY;

    public static final String HOME_STRING = "HOME";
    public static final String AWAY_STRING = "AWAY";

    public static TeamSide fromString(String team) {
        return "AWAY".equals(team) ? AWAY : HOME;
    }

    public String toStringValue() {
        return this == HOME ? HOME_STRING : AWAY_STRING;
    }
}
