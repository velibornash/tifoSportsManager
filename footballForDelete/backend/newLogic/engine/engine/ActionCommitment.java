package org.example.footballmanager.newLogic.engine;

public class ActionCommitment {

    private CurrentAction.ActionType action;
    private double commitmentLevel;
    private double remainingTicks;

    public ActionCommitment() {
        this.action = CurrentAction.ActionType.NONE;
        this.commitmentLevel = 0;
        this.remainingTicks = 0;
    }

    public void commit(CurrentAction.ActionType action, double commitTicks) {
        this.action = action;
        this.commitmentLevel = 1.0;
        this.remainingTicks = commitTicks;
    }

    public void update() {
        if (remainingTicks > 0) {
            remainingTicks--;
            if (remainingTicks <= 0) {
                commitmentLevel = 0;
            }
        }
    }

    public boolean isCommitted() {
        return commitmentLevel > 0;
    }

    public CurrentAction.ActionType getAction() { return action; }
    public double getCommitmentLevel() { return commitmentLevel; }
}
