package org.example.footballmanager.newLogic.engine;

public class CurrentAction {

    private ActionType type;
    private double remainingTime;
    private double totalDuration;

    public CurrentAction() {
        this.type = ActionType.NONE;
        this.remainingTime = 0;
        this.totalDuration = 0;
    }

    public void start(ActionType type, double durationSeconds) {
        this.type = type;
        this.remainingTime = durationSeconds;
        this.totalDuration = durationSeconds;
    }

    public void update(double dtSeconds) {
        if (remainingTime > 0) {
            remainingTime = Math.max(0, remainingTime - dtSeconds);
        }
    }

    public boolean isBusy() {
        return remainingTime > 0;
    }

    public ActionType getType() { return type; }
    public double getRemainingTime() { return remainingTime; }
    public double getProgress() {
        return totalDuration > 0 ? 1.0 - (remainingTime / totalDuration) : 0;
    }

    public enum ActionType {
        NONE, CARRY, DRIBBLE, PASS, SHORT_PASS, LONG_PASS, CROSS, THROUGH_PASS,
        SHOOT, CLEAR, TACKLE, INTERCEPT, RUN, HOLD
    }
}
