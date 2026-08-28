package org.example.footballmanager.demo.service.model;

/**
 * Current action model. Data-only — no simulation logic.
 * Corresponds to demo/Action but independent.
 */
public class Action {

    public enum SaveType { NONE, FIELD_REBOUND, CORNER_REBOUND }

    private final ActionType type;
    private final Player actingPlayer;
    private Player targetPlayer;
    private Position targetPosition;
    private final boolean inFlight;
    private String actionId;

    private int skill;
    private Position intendedTarget;
    private Position actualTarget;
    private Position executionOrigin;
    private Position logicalGoalPosition;
    private Position dribbleBypassTarget;
    private boolean goalLineResolved;
    private boolean goodExecution;
    private SaveType saveType = SaveType.NONE;
    private boolean clearance;
    private PassLength passLength = PassLength.SHORT;
    private PassHeight passHeight = PassHeight.GROUND;
    private double passSpeed = 2.0; // cells/tick — based on passer's passing skill

    private long startTick;
    private int chaseTicks;
    private double chaseLastDistance = Double.NaN;
    private int chaseNoProgressTicks;

    public Action(ActionType type, Player actingPlayer) {
        this.type = type;
        this.actingPlayer = actingPlayer;
        this.inFlight = type == ActionType.PASS || type == ActionType.SHOT
                || type == ActionType.CROSS || type == ActionType.CENTER;
        if (type == ActionType.CHASE) {
            resetChaseTracking();
        }
    }

    public void resetChaseTracking() {
        chaseTicks = 0;
        chaseLastDistance = Double.NaN;
        chaseNoProgressTicks = 0;
    }

    public void recordChaseTick(double distanceToBall, double progressEpsilon) {
        chaseTicks++;
        if (!Double.isNaN(chaseLastDistance)
                && chaseLastDistance - distanceToBall <= progressEpsilon) {
            chaseNoProgressTicks++;
        } else {
            chaseNoProgressTicks = 0;
        }
        chaseLastDistance = distanceToBall;
    }

    public ActionType getType() { return type; }
    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }
    public Player getActingPlayer() { return actingPlayer; }
    public Player getTargetPlayer() { return targetPlayer; }
    public void setTargetPlayer(Player targetPlayer) { this.targetPlayer = targetPlayer; }
    public Position getTargetPosition() { return targetPosition; }
    public void setTargetPosition(Position targetPosition) { this.targetPosition = targetPosition; }
    public boolean isInFlight() { return inFlight; }
    public boolean isPassInFlight() { return inFlight && type == ActionType.PASS; }
    public boolean isShotInFlight() { return inFlight && type == ActionType.SHOT; }
    public boolean isCrossInFlight() { return inFlight && (type == ActionType.CROSS || type == ActionType.CENTER); }
    public int getSkill() { return skill; }
    public void setSkill(int skill) { this.skill = skill; }
    public Position getIntendedTarget() { return intendedTarget; }
    public void setIntendedTarget(Position intendedTarget) { this.intendedTarget = intendedTarget; }
    public Position getActualTarget() { return actualTarget; }
    public void setActualTarget(Position actualTarget) { this.actualTarget = actualTarget; }
    public Position getExecutionOrigin() { return executionOrigin; }
    public void setExecutionOrigin(Position executionOrigin) { this.executionOrigin = executionOrigin; }
    public Position getLogicalGoalPosition() { return logicalGoalPosition; }
    public void setLogicalGoalPosition(Position logicalGoalPosition) { this.logicalGoalPosition = logicalGoalPosition; }
    public Position getDribbleBypassTarget() { return dribbleBypassTarget; }
    public void setDribbleBypassTarget(Position dribbleBypassTarget) { this.dribbleBypassTarget = dribbleBypassTarget; }
    public boolean isGoalLineResolved() { return goalLineResolved; }
    public void setGoalLineResolved(boolean goalLineResolved) { this.goalLineResolved = goalLineResolved; }
    public boolean isGoodExecution() { return goodExecution; }
    public void setGoodExecution(boolean goodExecution) { this.goodExecution = goodExecution; }
    public SaveType getSaveType() { return saveType; }
    public void setSaveType(SaveType saveType) { this.saveType = saveType; }
    public boolean isClearance() { return clearance; }
    public void setClearance(boolean clearance) { this.clearance = clearance; }
    public PassLength getPassLength() { return passLength; }
    public void setPassLength(PassLength passLength) { this.passLength = passLength; }
    public PassHeight getPassHeight() { return passHeight; }
    public void setPassHeight(PassHeight passHeight) { this.passHeight = passHeight; }
    public double getPassSpeed() { return passSpeed; }
    public void setPassSpeed(double passSpeed) { this.passSpeed = passSpeed; }
    public int getChaseTicks() { return chaseTicks; }
    public int getChaseNoProgressTicks() { return chaseNoProgressTicks; }
    public long getStartTick() { return startTick; }
    public void setStartTick(long startTick) { this.startTick = startTick; }

    @Override
    public String toString() {
        return type + " by " + actingPlayer.getLabel();
    }
}
