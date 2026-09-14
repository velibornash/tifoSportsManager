package org.example.footballmanager.demo.service.proposal.model;

/** Result of one BallPhysicsEngine step — pure physics outcome, no side effects. */
public class BallStepResult {

    public enum Type {
        FLIGHT,         // moved, still in play
        STOPPED,        // decelerated to 0 on the pitch
        RECEIVE,        // pending receiver got it (carrier set)
        INTERCEPT,      // opponent intercepted (carrier set)
        SAVE,           // goalkeeper saved a shot (carrier set to GK)
        BLOCK,          // fast ball hit opponent -> deflect, no carrier
        DEFLECT,        // hit any body -> deflect
        POST_HIT,       // hit post -> deflect
        GOAL,           // crossed goal line inside mouth
        OOB_ENTER,      // first entered OOB zone (hold starts)
        OOB_HOLD,       // in OOB hold, still counting down
        OOB_RESTART,    // hold finished, restart due
        OOB_CANCEL,     // ball rolled back into play during hold
        LOOSE_PICKUP    // stopped loose ball picked up by nearest
    }

    private final Type type;
    private final String detail;       // e.g., player name, restart type, "scorerTeam"
    private final String scorerTeam;   // non-null only for GOAL
    private final String restartType;  // non-null only for OOB_RESTART

    private BallStepResult(Type type, String detail, String scorerTeam, String restartType) {
        this.type = type;
        this.detail = detail;
        this.scorerTeam = scorerTeam;
        this.restartType = restartType;
    }

    public Type getType() { return type; }
    public String getDetail() { return detail; }
    public String getScorerTeam() { return scorerTeam; }
    public String getRestartType() { return restartType; }

    public static BallStepResult flight() { return new BallStepResult(Type.FLIGHT, "", null, null); }
    public static BallStepResult stopped() { return new BallStepResult(Type.STOPPED, "", null, null); }
    public static BallStepResult receive(String player) { return new BallStepResult(Type.RECEIVE, player, null, null); }
    public static BallStepResult intercept(String player) { return new BallStepResult(Type.INTERCEPT, player, null, null); }
    public static BallStepResult save(String player) { return new BallStepResult(Type.SAVE, player, null, null); }
    public static BallStepResult block(String player) { return new BallStepResult(Type.BLOCK, player, null, null); }
    public static BallStepResult deflect(String player) { return new BallStepResult(Type.DEFLECT, player, null, null); }
    public static BallStepResult postHit() { return new BallStepResult(Type.POST_HIT, "post", null, null); }
    public static BallStepResult goal(String scorerTeam) { return new BallStepResult(Type.GOAL, "", scorerTeam, null); }
    public static BallStepResult oobEnter(String restartType) { return new BallStepResult(Type.OOB_ENTER, restartType, null, restartType); }
    public static BallStepResult oobHold(String restartType, int ticks) { return new BallStepResult(Type.OOB_HOLD, restartType + " t=" + ticks, null, restartType); }
    public static BallStepResult oobRestart(String restartType) { return new BallStepResult(Type.OOB_RESTART, "", null, restartType); }
    public static BallStepResult oobCancel() { return new BallStepResult(Type.OOB_CANCEL, "rolled back in", null, null); }
    public static BallStepResult loosePickup(String player) { return new BallStepResult(Type.LOOSE_PICKUP, player, null, null); }
}