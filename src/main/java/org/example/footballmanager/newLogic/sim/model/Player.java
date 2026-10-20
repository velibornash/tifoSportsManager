package org.example.footballmanager.newLogic.sim.model;

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
    private int lockTicks;
    /**
     * Tick of this player's last duel (win or loss). A duel only starts when
     * BOTH contestants are outside the duel cooldown, which guarantees a carrier
     * cannot chain duels and freeze the match.
     */
    private int lastDuelTick = -9999; // remaining ticks the player is blocked after duel loss
    private boolean offside;
    /** Which way the keeper committed on a penalty: -1 left, 0 centre, +1 right. Replay only. */
    private int diveSide;

    private boolean sentOff;
    private int sentOffTick = -1;
    /** Yellow cards already shown to this player IN THIS MATCH (second yellow = red). */
    private int yellowCardsInMatch;
    private boolean injured;
    /** Injury classification, e.g. ANKLE. Sprint 1.6 - severity drives the absence length. */
    private String injuryType;
    /** Days out, decremented by the season loop. Sprint 1.6. */
    private int injuryDaysRemaining;
    private boolean substituted;
    /**
     * On the bench: in the matchday squad but not on the pitch. A benched player exists so he can
     * be brought on, but takes no part in play - no movement, no duels, no ball contact. Added in
     * Sprint 1.8; until now a squad was exactly 11 and the rest of the squad list was discarded,
     * which is why a red card left a team with ten for the rest of the match.
     */
    private boolean onBench;
    private double velX;
    private double velY;
    private double fatigue;

    /**
     * Confidence in this match, 0.5 to 1.5. Starts at 1.0 and moves with morale, which moves
     * with what actually happens to the player: minutes, goals, results, whether he is being paid
     * what he is worth, and whether he has been dumped from the XI.
     *
     * <p>This is the number that finally makes {@code form} mean something. It exists as a field
     * rather than being derived from the database player's {@code form} because the match engine
     * needs it per-tick and cannot afford a repository call.
     */
    private double confidence = 1.0;

    /** Signed, -1 (desperate) to +1 (flying). Drives confidence. */
    private double mood;
    private double form;   // 0.5..1.2 multiplier, default 1.0 (documented, not yet used)
    private int consecutiveOffsideCount;
    private int consecutiveCarries;
    private int lastShotTick = -100;
    private int lastSaveTick = -100;
    private int carryStartTick = -100;
    private boolean threatOverrideActive;
    /** Remaining ticks the player must stay rooted after striking the ball
     * (PASS/SHOT/CLEAR) — the ball must be visibly leaving before he moves. */
    private int strikeHoldTicks;

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
        this.form = 1.0;
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

    public int getLastDuelTick() { return lastDuelTick; }
    public void setLastDuelTick(int t) { this.lastDuelTick = t; }

    public int getLockTicks() { return lockTicks; }
    public void setLockTicks(int lockTicks) { this.lockTicks = Math.max(0, lockTicks); }

    public int getDiveSide() { return diveSide; }

    public void setDiveSide(int diveSide) { this.diveSide = diveSide; }

    public boolean isSentOff() { return sentOff; }
    public void setSentOff(boolean sentOff) { this.sentOff = sentOff; }

    /** Tick at which the player was sent off (-1 = not sent off) — drives minutes. */
    public int getSentOffTick() { return sentOffTick; }
    public void setSentOffTick(int sentOffTick) { this.sentOffTick = sentOffTick; }

    public int getYellowCardsInMatch() { return yellowCardsInMatch; }
    public void setYellowCardsInMatch(int yellowCardsInMatch) { this.yellowCardsInMatch = Math.max(0, yellowCardsInMatch); }
    public void incrementYellowCardsInMatch() { this.yellowCardsInMatch++; }

    public boolean isInjured() { return injured; }
    public void setInjured(boolean injured) { this.injured = injured; }

    public boolean isOnBench() { return onBench; }
    public void setOnBench(boolean onBench) { this.onBench = onBench; }
    public String getInjuryType() { return injuryType; }
    public void setInjuryType(String injuryType) { this.injuryType = injuryType; }
    public int getInjuryDaysRemaining() { return injuryDaysRemaining; }
    public void setInjuryDaysRemaining(int d) { this.injuryDaysRemaining = d; }

    public boolean isSubstituted() { return substituted; }
    public void setSubstituted(boolean substituted) { this.substituted = substituted; }

    public boolean isUnavailable() { return sentOff || injured || substituted; }

    public boolean isOffside() { return offside; }
    public void setOffside(boolean offside) { this.offside = offside; }

    public double getVelX() { return velX; }
    public void setVelX(double velX) { this.velX = velX; }

    public double getVelY() { return velY; }
    public void setVelY(double velY) { this.velY = velY; }

    public double getConfidence() { return confidence; }

    public void setConfidence(double confidence) {
        this.confidence = Math.max(0.5, Math.min(1.5, confidence));
    }

    /** A confidence multiplier that feeds shot and pass execution. */
    public double confidenceModifier() {
        // A player who cannot believe in himself misses by more than a metre.
        return 0.85 + 0.15 * ((confidence - 0.5) / 1.0);
    }

    public double getMood() { return mood; }

    public void setMood(double mood) {
        this.mood = Math.max(-1.0, Math.min(1.0, mood));
    }

    public double getFatigue() { return fatigue; }
    public void setFatigue(double fatigue) { this.fatigue = Math.max(0, Math.min(1.0, fatigue)); }

    public double getForm() { return form; }
    public void setForm(double form) { this.form = Math.max(0.5, Math.min(1.2, form)); }

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

    public int getStrikeHoldTicks() { return strikeHoldTicks; }
    public void setStrikeHoldTicks(int ticks) { this.strikeHoldTicks = Math.max(0, ticks); }

    public int heightSkill() {
        return Math.max(1, Math.min(20, (int) Math.round((heightCm - 160) / 2.0)));
    }

    @Override
    public String toString() {
        return label + "(" + team + "/" + role + ") " + position;
    }
}