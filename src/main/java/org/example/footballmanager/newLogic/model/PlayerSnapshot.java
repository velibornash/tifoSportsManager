package org.example.footballmanager.newLogic.model;

public final class PlayerSnapshot {
    private final long playerId;
    private final String name;
    private final String teamSide;
    private final Position position;
    private double x;
    private double y;
    private String state;
    private boolean hasBall;

    private final int pace;
    private final int technique;
    private final int passing;
    private final int playmaking;
    private final int shooting;
    private final int defending;
    private final int stamina;

    public PlayerSnapshot(long playerId, String name, String teamSide, Position position,
                          double x, double y, String state, boolean hasBall) {
        this(playerId, name, teamSide, position, x, y, state, hasBall, 10, 10, 10, 10, 10, 10, 10);
    }

    public PlayerSnapshot(long playerId, String name, String teamSide, Position position,
                          double x, double y, String state, boolean hasBall,
                          int pace, int technique, int passing, int playmaking,
                          int shooting, int defending, int stamina) {
        this.playerId = playerId;
        this.name = name;
        this.teamSide = teamSide;
        this.position = position;
        this.x = x;
        this.y = y;
        this.state = state;
        this.hasBall = hasBall;
        this.pace = pace;
        this.technique = technique;
        this.passing = passing;
        this.playmaking = playmaking;
        this.shooting = shooting;
        this.defending = defending;
        this.stamina = stamina;
    }

    public static PlayerSnapshot fromPlayer(Player p, String teamSide, double x, double y) {
        Skills s = p.skills();
        return new PlayerSnapshot(
            p.id(), p.name(), teamSide, p.position(),
            x, y, "NORMAL", false,
            s != null ? s.pace() : 10,
            s != null ? s.technique() : 10,
            s != null ? s.passing() : 10,
            s != null ? s.playmaking() : 10,
            s != null ? s.shooting() : 10,
            s != null ? s.defending() : 10,
            s != null ? s.stamina() : 10
        );
    }

    public PlayerSnapshot copy() {
        return new PlayerSnapshot(playerId, name, teamSide, position, x, y, state, hasBall,
            pace, technique, passing, playmaking, shooting, defending, stamina);
    }

    public long playerId() { return playerId; }
    public String name() { return name; }
    public String teamSide() { return teamSide; }
    public Position position() { return position; }
    public double x() { return x; }
    public double y() { return y; }
    public String state() { return state; }
    public boolean hasBall() { return hasBall; }

    public int pace() { return pace; }
    public int technique() { return technique; }
    public int passing() { return passing; }
    public int playmaking() { return playmaking; }
    public int shooting() { return shooting; }
    public int defending() { return defending; }
    public int stamina() { return stamina; }
    public int dribbling() { return technique; }
    public int vision() { return playmaking; }

    public void setPosition(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public void setState(String state) {
        this.state = state;
    }

    public void setHasBall(boolean hasBall) {
        this.hasBall = hasBall;
    }

    public double distanceTo(PlayerSnapshot other) {
        return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(y - other.y, 2));
    }

    public double distanceToPoint(double px, double py) {
        return Math.sqrt(Math.pow(x - px, 2) + Math.pow(y - py, 2));
    }
}
