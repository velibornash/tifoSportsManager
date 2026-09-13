package org.example.footballmanager.demo.service.proposal.recording;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.footballmanager.demo.service.proposal.model.Position;

/**
 * Per-player snapshot at a single tick.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlayerSnapshot {
    private final String id;
    private final String label;
    private final String team;
    private final String role;
    private final Position position;
    private final Position target;
    private final boolean locked;
    private final double velX;
    private final double velY;

    public PlayerSnapshot(String id, String label, String team, String role,
                          Position position, Position target, boolean locked,
                          double velX, double velY) {
        this.id = id;
        this.label = label;
        this.team = team;
        this.role = role;
        this.position = position;
        this.target = target;
        this.locked = locked;
        this.velX = velX;
        this.velY = velY;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public String getTeam() { return team; }
    public String getRole() { return role; }
    public Position getPosition() { return position; }
    public Position getTarget() { return target; }
    public boolean isLocked() { return locked; }
    public double getVelX() { return velX; }
    public double getVelY() { return velY; }
}