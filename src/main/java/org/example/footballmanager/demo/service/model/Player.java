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
    private boolean sentOff;    // red card — permanently out for this match
    private boolean injured;    // injured — out until substituted
    private boolean substituted; // has been substituted off
    private double velX;
    private double velY;
    private double fatigue; // 0.0 = fresh, 1.0 = exhausted
    private int consecutiveOffsideCount; // offside retreat: 3+ triggers override
    private int consecutiveCarries; // track consecutive carries to prevent over-dribbling
    private int lastShotTick = -100; // cooldown: prevent rapid re-shots after miss/save
    private int lastSaveTick = -100; // cooldown: GK cannot save twice within a few ticks
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

    /**
     * Returns the tactical line (GK, DEF, MID, ATT, WNG) derived from the
     * formation-specific slot key (DL, DCL, DCR, DR, ML, CML, CMR, MR, STL, STR).
     * This is the line-level categorisation used by threat/perception/transitions.
     */
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
    public void setPosition(Position position) { this.position = Objects.requireNonNull(position); }

    public Position getTarget() { return target; }
    public void setTarget(Position target) { this.target = target; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public boolean isSentOff() { return sentOff; }
    public void setSentOff(boolean sentOff) { this.sentOff = sentOff; }

    public boolean isInjured() { return injured; }
    public void setInjured(boolean injured) { this.injured = injured; }

    public boolean isSubstituted() { return substituted; }
    public void setSubstituted(boolean substituted) { this.substituted = substituted; }

    /** Player is unavailable for any reason: sent off, injured, or substituted off. */
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

    public int heightSkill() {
        return Math.max(1, Math.min(20, (int) Math.round((heightCm - 160) / 2.0)));
    }

    @Override
    public String toString() {
        return label + "(" + team + "/" + role + ") " + position;
    }
}
