package org.example.footballmanager.demo.service.proposal.model;

import java.util.Objects;

/** Player model for the proposal. Data-only. */
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
    private int lockTicks; // remaining ticks the player is blocked after duel loss
    private boolean offside;
    private boolean sentOff;
    private boolean injured;
    private boolean substituted;
    private double velX;
    private double velY;
    private double fatigue;
    private int consecutiveOffsideCount;
    private int consecutiveCarries;
    private int lastShotTick = -100;
    private int lastSaveTick = -100;
    private int carryStartTick = -100;
    private boolean threatOverrideActive;

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

    public String roleLine() {
        if (role == null || role.isEmpty()) return "MID";
        return switch (role.charAt(0)) {
            case 'G' -> "GK";
            case 'D' -> "DEF";
            case 'M' -> "MID";
            case 'W' -> "WNG";
            case 'A', 'S' -> "ATT";
            default -> "MID";
        };
    }

    public boolean isGoalkeeper() { return "GK".equals(role); }
    public boolean isAttacker() { return roleLine().equals("ATT"); }
    public boolean isDefender() { return roleLine().equals("DEF"); }

    public Position getPosition() { return position; }
    public void setPosition(Position position) { this.position = position; }

    public Position getTarget() { return target; }
    public void setTarget(Position target) { this.target = target; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public int getLockTicks() { return lockTicks; }
    public void setLockTicks(int lockTicks) { this.lockTicks = Math.max(0, lockTicks); }

    public boolean isSentOff() { return sentOff; }
    public void setSentOff(boolean sentOff) { this.sentOff = sentOff; }

    public boolean isInjured() { return injured; }
    public void setInjured(boolean injured) { this.injured = injured; }

    public boolean isSubstituted() { return substituted; }
    public void setSubstituted(boolean substituted) { this.substituted = substituted; }

    public boolean isUnavailable() { return sentOff || injured || substituted; }

    public boolean isOffside() { return offside; }
    public void setOffside(boolean offside) { this.offside = offside; }

    public double getVelX() { return velX; }
    public void setVelX(double velX) { this.velX = velX; }

    public double getVelY() { return velY; }
    public void setVelY(double velY) { this.velY = velY; }

    public double getFatigue() { return fatigue; }
    public void setFatigue(double fatigue) { this.fatigue = Math.max(0, Math.min(1.0, fatigue)); }

    public int getConsecutiveOffsideCount() { return consecutiveOffsideCount; }
    public void setConsecutiveOffsideCount(int count) { this.consecutiveOffsideCount = Math.max(0, count); }
    public void incrementConsecutiveOffside() { this.consecutiveOffsideCount++; }
    public void resetConsecutiveOffside() { this.consecutiveOffsideCount = 0; }

    public int getConsecutiveCarries() { return consecutiveCarries; }
    public void incrementConsecutiveCarries() { this.consecutiveCarries++; }
    public void resetConsecutiveCarries() { this.consecutiveCarries = 0; }

    public int getLastShotTick() { return lastShotTick; }
    public void setLastShotTick(int tick) { this.lastShotTick = tick; }
    public int getLastSaveTick() { return lastSaveTick; }
    public void setLastSaveTick(int tick) { this.lastSaveTick = tick; }
    public int getCarryStartTick() { return carryStartTick; }
    public void setCarryStartTick(int tick) { this.carryStartTick = tick; }

    public boolean isThreatOverrideActive() { return threatOverrideActive; }
    public void setThreatOverrideActive(boolean active) { this.threatOverrideActive = active; }

    public int heightSkill() {
        return Math.max(1, Math.min(20, (int) Math.round((heightCm - 160) / 2.0)));
    }

    @Override
    public String toString() {
        return label + "(" + team + "/" + role + ") " + position;
    }
}