package org.example.footballmanager.demo.service.model;

import java.util.Objects;

/**
 * Player model for the service layer. Data-only — no simulation logic.
 * Identical structure to demo/Player but independent.
 */
public class Player {

    private final String id;
    private final String label;
    private final String team;
    private final String role;
    private final PlayerSkills skills;
    private final double heightCm;
    private final Position alternativePosition;

    private Position position;
    private Position target;
    private boolean locked;
    private boolean offside;
    private double velX;
    private double velY;
    private double fatigue; // 0.0 = fresh, 1.0 = exhausted

    public Player(String id, String label, String team, String role,
                  Position position, Position alternativePosition, PlayerSkills skills) {
        this(id, label, team, role, position, alternativePosition, skills, 180);
    }

    public Player(String id, String label, String team, String role,
                  Position position, Position alternativePosition, PlayerSkills skills,
                  double heightCm) {
        this.id = Objects.requireNonNull(id);
        this.label = Objects.requireNonNull(label);
        this.team = Objects.requireNonNull(team);
        this.role = Objects.requireNonNull(role);
        this.position = Objects.requireNonNull(position);
        this.alternativePosition = Objects.requireNonNull(alternativePosition);
        this.skills = Objects.requireNonNull(skills);
        this.heightCm = heightCm;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public String getTeam() { return team; }
    public String getRole() { return role; }
    public PlayerSkills getSkills() { return skills; }
    public double getHeightCm() { return heightCm; }
    public Position getAlternativePosition() { return alternativePosition; }

    public Position getPosition() { return position; }
    public void setPosition(Position position) { this.position = Objects.requireNonNull(position); }

    public Position getTarget() { return target; }
    public void setTarget(Position target) { this.target = target; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public boolean isOffside() { return offside; }
    public void setOffside(boolean offside) { this.offside = offside; }

    public double getVelX() { return velX; }
    public void setVelX(double velX) { this.velX = velX; }

    public double getVelY() { return velY; }
    public void setVelY(double velY) { this.velY = velY; }

    public double getFatigue() { return fatigue; }
    public void setFatigue(double fatigue) { this.fatigue = Math.max(0, Math.min(1.0, fatigue)); }

    public int heightSkill() {
        return Math.max(1, Math.min(20, (int) Math.round((heightCm - 160) / 2.0)));
    }

    @Override
    public String toString() {
        return label + "(" + team + "/" + role + ") " + position;
    }
}
