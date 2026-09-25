package org.example.footballmanager.newLogic.engine;

public class MatchMetrics {

    private int shots;
    private int shotsOnTarget;
    private int goals;
    private int passes;
    private int carries;
    private int tackles;
    private int interceptions;
    private int dribbles;
    private int clearances;
    private int fouls;
    private int throwIns;
    private int corners;
    private int goalKicks;
    private int offsides;
    private int throughBalls;
    private int crosses;
    private int duels;

    public MatchMetrics() {}

    public void onShot() { shots++; }
    public void onShotOnTarget() { shotsOnTarget++; }
    public void onGoal() { goals++; }
    public void onPass() { passes++; }
    public void onCarry() { carries++; }
    public void onTackle() { tackles++; }
    public void onInterception() { interceptions++; }
    public void onDribble() { dribbles++; }
    public void onClearance() { clearances++; }
    public void onFoul() { fouls++; }
    public void onThrowIn() { throwIns++; }
    public void onCorner() { corners++; }
    public void onGoalKick() { goalKicks++; }
    public void onOffside() { offsides++; }
    public void onThroughBall() { throughBalls++; }
    public void onCross() { crosses++; }
    public void onDuel() { duels++; }

    // Getters
    public int getShots() { return shots; }
    public int getShotsOnTarget() { return shotsOnTarget; }
    public int getGoals() { return goals; }
    public int getPasses() { return passes; }
    public int getCarries() { return carries; }
    public int getTackles() { return tackles; }
    public int getInterceptions() { return interceptions; }
    public int getDribbles() { return dribbles; }
    public int getClearances() { return clearances; }
    public int getFouls() { return fouls; }
    public int getThrowIns() { return throwIns; }
    public int getCorners() { return corners; }
    public int getGoalKicks() { return goalKicks; }
    public int getOffsides() { return offsides; }
    public int getThroughBalls() { return throughBalls; }
    public int getCrosses() { return crosses; }
    public int getDuels() { return duels; }

    public String toSummary() {
        return String.format(
            "Shots: %d | Shots OT: %d | Goals: %d | Passes: %d | Carries: %d%n" +
            "Tackles: %d | Interceptions: %d | Dribbles: %d | Clearances: %d | Fouls: %d%n" +
            "Throw-ins: %d | Corners: %d | Goal kicks: %d | Offsides: %d%n" +
            "Through balls: %d | Crosses: %d | Duels: %d",
            shots, shotsOnTarget, goals, passes, carries,
            tackles, interceptions, dribbles, clearances, fouls,
            throwIns, corners, goalKicks, offsides,
            throughBalls, crosses, duels
        );
    }
}
