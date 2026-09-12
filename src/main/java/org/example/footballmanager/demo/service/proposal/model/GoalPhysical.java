package org.example.footballmanager.demo.service.proposal.model;

/**
 * Physical goal structure: posts + crossbar dimensions.
 * In 2D physics only posts are solid (point obstacles with radius).
 * Crossbar height is stored for future 3D / UI rendering.
 */
public class GoalPhysical {

    public static final double POST_RADIUS = 0.03;    // cells (~0.42 m)
    public static final double POST_HEIGHT_M = 2.44;  // meters
    public static final double MOUTH_LEFT = 3.5;      // col
    public static final double MOUTH_RIGHT = 4.5;     // col

    private final double goalLineRow;  // 1.0 (HOME) or 8.0 (AWAY)
    private final Position leftPost;   // (goalLineRow, 3.5)
    private final Position rightPost;  // (goalLineRow, 4.5)

    public GoalPhysical(double goalLineRow) {
        this.goalLineRow = goalLineRow;
        this.leftPost = new Position(goalLineRow, MOUTH_LEFT);
        this.rightPost = new Position(goalLineRow, MOUTH_RIGHT);
    }

    public double getGoalLineRow() { return goalLineRow; }
    public Position getLeftPost() { return leftPost; }
    public Position getRightPost() { return rightPost; }
    public double getMouthLeft() { return MOUTH_LEFT; }
    public double getMouthRight() { return MOUTH_RIGHT; }

    /** Check if a column is strictly inside the mouth (posts excluded). */
    public boolean isInsideMouth(double col) {
        return col > MOUTH_LEFT && col < MOUTH_RIGHT;
    }

    /** Distance from a point to the nearest post. */
    public double distToNearestPost(double row, double col) {
        double dl = Math.hypot(row - goalLineRow, col - MOUTH_LEFT);
        double dr = Math.hypot(row - goalLineRow, col - MOUTH_RIGHT);
        return Math.min(dl, dr);
    }

    /** Segment (prev→curr) hits either post? */
    public boolean segmentHitsPost(Position prev, Position curr, double ballRadius) {
        double threshold = POST_RADIUS + ballRadius;
        return pointSegmentDist(goalLineRow, MOUTH_LEFT, prev, curr) <= threshold
                || pointSegmentDist(goalLineRow, MOUTH_RIGHT, prev, curr) <= threshold;
    }

    static double pointSegmentDist(double r, double c, Position p1, Position p2) {
        double dr = p2.getRow() - p1.getRow(), dc = p2.getColumn() - p1.getColumn();
        double len2 = dr * dr + dc * dc;
        if (len2 < 1e-9) return Math.hypot(r - p1.getRow(), c - p1.getColumn());
        double t = Math.max(0, Math.min(1, ((r - p1.getRow()) * dr + (c - p1.getColumn()) * dc) / len2));
        return Math.hypot(r - (p1.getRow() + dr * t), c - (p1.getColumn() + dc * t));
    }
}