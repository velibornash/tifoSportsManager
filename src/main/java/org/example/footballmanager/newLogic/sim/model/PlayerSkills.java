package org.example.footballmanager.newLogic.sim.model;

/** PlayerSkills — 8 core abilities, each 1-20. */
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
}