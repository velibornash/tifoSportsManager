package org.example.footballmanager.demo.swingUIDemo;

import java.awt.Color;
import java.util.Objects;

/**
 * Model igraca. Igrac poseduje svoje stanje:
 * identitet (id, label), ekipu, rolu, boju i pozicije
 * (trenutna {@link #getPosition() position} i alternativna
 * {@link #getAlternativePosition() alternativePosition}).
 *
 * Za simulaciju/animaciju dodatno poseduje {@link #getTarget() target}
 * (celij ka kojoj trenutno hoda) i {@link #isLocked() locked} zastavicu
 * (igrac je rezervisan za neku tekuci akciju, npr. primaoc pasa).
 *
 * Ovo je ISKLJUCIVO podatkovni model — ne sadrzi nikakvu logiku kretanja,
 * taktike niti simulacije. Renderer i SimulationEngine samo cita/menja
 * stanje igraca.
 */
public class Player {

    private final String id;
    private final String label;
    private final String team;
    private final String role;
    private final Color color;
    private Position position;
    private final Position alternativePosition;
    private Position target;
    private boolean locked;
    private boolean offside;
    private double velX;
    private double velY;

    /** Buduce sposobnosti igraca. */
    private final PlayerSkills skills;

    /** Fizicke karakteristike. */
    private final double heightCm;
    private final double weightKg;

    public Player(String id, String label, String team, String role, Color color,
                  Position position, Position alternativePosition) {
        this(id, label, team, role, color, position, alternativePosition, PlayerSkills.neutral());
    }

    public Player(String id, String label, String team, String role, Color color,
                  Position position, Position alternativePosition, PlayerSkills skills) {
        this(id, label, team, role, color, position, alternativePosition, skills, 180, 75);
    }

    public Player(String id, String label, String team, String role, Color color,
                  Position position, Position alternativePosition, PlayerSkills skills,
                  double heightCm, double weightKg) {
        this.id = Objects.requireNonNull(id);
        this.label = Objects.requireNonNull(label);
        this.team = Objects.requireNonNull(team);
        this.role = Objects.requireNonNull(role);
        this.color = Objects.requireNonNull(color);
        this.position = Objects.requireNonNull(position);
        this.alternativePosition = Objects.requireNonNull(alternativePosition);
        this.skills = Objects.requireNonNull(skills);
        this.heightCm = heightCm;
        this.weightKg = weightKg;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getTeam() {
        return team;
    }

    public String getRole() {
        return role;
    }

    public Color getColor() {
        return color;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = Objects.requireNonNull(position);
    }

    public Position getAlternativePosition() {
        return alternativePosition;
    }

    /** Celija ka kojoj igrac trenutno hoda (null = stoji / nema cilja). */
    public Position getTarget() {
        return target;
    }

    public void setTarget(Position target) {
        this.target = target;
    }

    /** True ako je igrac rezervisan za tekuci akciju (npr. primaoc pasa). */
    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    /** True when the player is currently in an offside position (threat/safety layer). */
    public boolean isOffside() {
        return offside;
    }

    public void setOffside(boolean offside) {
        this.offside = offside;
    }

    public double getVelX() {
        return velX;
    }

    public void setVelX(double velX) {
        this.velX = velX;
    }

    public double getVelY() {
        return velY;
    }

    public void setVelY(double velY) {
        this.velY = velY;
    }

    /**
     * Sposobnosti igraca — extension point za sledeci sprint.
     */
    public PlayerSkills getSkills() {
        return skills;
    }

    /** Visina u centimetrima. */
    public double getHeightCm() {
        return heightCm;
    }

    /** Tezina u kilogramima. */
    public double getWeightKg() {
        return weightKg;
    }

    /** Visina na skali 1-20 (160cm=1, 200cm=20). */
    public int heightSkill() {
        return Math.max(1, Math.min(20, (int) Math.round((heightCm - 160) / 2.0)));
    }

    @Override
    public String toString() {
        return label + "(" + team + "/" + role + ") " + position;
    }
}
