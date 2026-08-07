package org.example.footballmanager.newLogic.engine;

public class SpaceInfo {
    private double pressure;
    private double openness;
    private double passLaneScore;
    private double shotLaneScore;
    private boolean underThreat;
    private double nearestOpponentDist;
    private double nearestTeammateDist;

    public static final SpaceInfo EMPTY = new SpaceInfo(1.0, 0.0, 0.0, 0.0, true, 999, 999);

    public SpaceInfo(double pressure, double openness, double passLaneScore,
                     double shotLaneScore, boolean underThreat,
                     double nearestOpponentDist, double nearestTeammateDist) {
        this.pressure = pressure;
        this.openness = openness;
        this.passLaneScore = passLaneScore;
        this.shotLaneScore = shotLaneScore;
        this.underThreat = underThreat;
        this.nearestOpponentDist = nearestOpponentDist;
        this.nearestTeammateDist = nearestTeammateDist;
    }

    public double getPressure() { return pressure; }
    public double getOpenness() { return openness; }
    public double getPassLaneScore() { return passLaneScore; }
    public double getShotLaneScore() { return shotLaneScore; }
    public boolean isUnderThreat() { return underThreat; }
    public double getNearestOpponentDist() { return nearestOpponentDist; }
    public double getNearestTeammateDist() { return nearestTeammateDist; }
}
