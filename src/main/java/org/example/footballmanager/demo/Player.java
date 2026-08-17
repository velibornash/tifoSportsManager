package org.example.footballmanager.demo;

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
    private double velX;
    private double velY;

    /** Buduce sposobnosti igraca — OVAJ SPRINT INERTNE (neutralne), niko ih ne cita. */
    private final PlayerSkills skills;

    public Player(String id, String label, String team, String role, Color color,
                  Position position, Position alternativePosition) {
        this(id, label, team, role, color, position, alternativePosition, PlayerSkills.neutral());
    }

    public Player(String id, String label, String team, String role, Color color,
                  Position position, Position alternativePosition, PlayerSkills skills) {
        this.id = Objects.requireNonNull(id);
        this.label = Objects.requireNonNull(label);
        this.team = Objects.requireNonNull(team);
        this.role = Objects.requireNonNull(role);
        this.color = Objects.requireNonNull(color);
        this.position = Objects.requireNonNull(position);
        this.alternativePosition = Objects.requireNonNull(alternativePosition);
        this.skills = Objects.requireNonNull(skills);
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
     * Sposobnosti igraca — extension point za sledeci sprint. Trenutno uvek
     * neutralne; simulacija ih NE koristi.
     */
    public PlayerSkills getSkills() {
        return skills;
    }

    @Override
    public String toString() {
        return label + "(" + team + "/" + role + ") " + position;
    }
}
