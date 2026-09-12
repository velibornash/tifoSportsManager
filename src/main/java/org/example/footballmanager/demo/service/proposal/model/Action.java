package org.example.footballmanager.demo.service.proposal.model;

import java.util.Objects;

/** Action object - represents a single action being performed. */
public class Action {
    private final ActionType type;
    private final Player team;
    private final Player carrier;
    private final Player target;
    private final Position targetPosition;
    private final double speed;
    private final double targetSpeed;
    private final double angle;
    private final double targetAngle;
    private final boolean isDeclaration;
    private final boolean isCompleted;
    private final boolean isExtraBall;
    private final boolean isBallInPlay;
    private final boolean isFreeHit;
    private final boolean isPenalty;
    private final boolean isExtraTime;
    private final boolean isStoppageTime;
    private final boolean isGoal;
    private final boolean isOwnGoal;
    private final boolean isOut;
    private final boolean isCorner;
    private final boolean isFreeKick;
    private final boolean isThrowIn;
    private final boolean isKickOff;
    private final boolean isPenaltyKick;
    private final boolean isGoalKick;

    public Action(ActionType type, Player team, Player carrier, Player target,
                  Position targetPosition, double speed, double targetSpeed,
                  double angle, double targetAngle, boolean isDeclaration,
                  boolean isCompleted, boolean isExtraBall, boolean isBallInPlay,
                  boolean isFreeHit, boolean isPenalty, boolean isExtraTime,
                  boolean isStoppageTime, boolean isGoal, boolean isOwnGoal,
                  boolean isOut, boolean isCorner, boolean isFreeKick,
                  boolean isThrowIn, boolean isKickOff, boolean isPenaltyKick,
                  boolean isGoalKick) {
        this.type = Objects.requireNonNull(type);
        this.team = Objects.requireNonNull(team);
        this.carrier = carrier;
        this.target = target;
        this.targetPosition = targetPosition;
        this.speed = speed;
        this.targetSpeed = targetSpeed;
        this.angle = angle;
        this.targetAngle = targetAngle;
        this.isDeclaration = isDeclaration;
        this.isCompleted = isCompleted;
        this.isExtraBall = isExtraBall;
        this.isBallInPlay = isBallInPlay;
        this.isFreeHit = isFreeHit;
        this.isPenalty = isPenalty;
        this.isExtraTime = isExtraTime;
        this.isStoppageTime = isStoppageTime;
        this.isGoal = isGoal;
        this.isOwnGoal = isOwnGoal;
        this.isOut = isOut;
        this.isCorner = isCorner;
        this.isFreeKick = isFreeKick;
        this.isThrowIn = isThrowIn;
        this.isKickOff = isKickOff;
        this.isPenaltyKick = isPenaltyKick;
        this.isGoalKick = isGoalKick;
    }

    public ActionType getType() { return type; }
    public Player getTeam() { return team; }
    public Player getCarrier() { return carrier; }
    public Player getTarget() { return target; }
    public Position getTargetPosition() { return targetPosition; }
    public double getSpeed() { return speed; }
    public double getTargetSpeed() { return targetSpeed; }
    public double getAngle() { return angle; }
    public double getTargetAngle() { return targetAngle; }
    public boolean isDeclaration() { return isDeclaration; }
    public boolean isCompleted() { return isCompleted; }
    public boolean isExtraBall() { return isExtraBall; }
    public boolean isBallInPlay() { return isBallInPlay; }
    public boolean isFreeHit() { return isFreeHit; }
    public boolean isPenalty() { return isPenalty; }
    public boolean isExtraTime() { return isExtraTime; }
    public boolean isStoppageTime() { return isStoppageTime; }
    public boolean isGoal() { return isGoal; }
    public boolean isOwnGoal() { return isOwnGoal; }
    public boolean isOut() { return isOut; }
    public boolean isCorner() { return isCorner; }
    public boolean isFreeKick() { return isFreeKick; }
    public boolean isThrowIn() { return isThrowIn; }
    public boolean isKickOff() { return isKickOff; }
    public boolean isPenaltyKick() { return isPenaltyKick; }
    public boolean isGoalKick() { return isGoalKick; }

    @Override
    public String toString() {
        return type + " by " + team + " -> " + target;
    }
}